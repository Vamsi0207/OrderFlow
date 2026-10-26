# Phase 3: Buy Now orchestration

`POST /api/v1/buy` requires a verified user Bearer JWT, `Idempotency-Key` (1–128
characters), and `{"items":[{"productId":"<uuid>","quantity":1}]}` (1–100
positive-quantity lines). Identity headers and client prices/totals are ignored.
Gateway routes the exact path to Order Service; Order also verifies the JWT on
direct access. Every Buy response uses `Cache-Control: no-store`.

Response fields: checkoutAttemptId, workflowStatus, code, orderId, orderStatus,
totalAmount, currency, items (productId, quantity, unitPrice), payment (id, status,
initializationState, clientSecret). A client secret is ephemeral, owner-only,
never saved in the workflow, and never returned for uncertain responses.

HTTP 201 means initialization READY, not payment success. HTTP 202 represents
another active workflow owner or unresolved payment; retry the same key/payload.
HTTP 409 represents payload conflict, insufficient stock or reconciliation; code
and workflow state distinguish them. Missing products return 404, invalid requests
400, invalid authentication 401, and unavailable pre-payment dependencies 503.
Once an order is confirmed, replay returns 200 without a secret; cancelled orders
return 409. No compensating stock release follows an ambiguous payment result.

## Durable state and transactions

Existing workflow enum values are retained, avoiding a destructive enum-constraint
migration: CREATED -> ORDER_CREATED -> INVENTORY_RESERVED -> COMPLETED (payment
initialization ready, not paid). RECOVERY_REQUIRED stops automatic initialization.
Retryable failures retain the last completed phase with an error code, retry count,
HTTP status and retry timestamp. Verified terminal order state is read on replay.

A database lease (60 seconds, refreshed before each bounded remote operation)
coordinates active requests. Every local checkpoint validates the lease UUID and
expiry; expired owners cannot overwrite a newer owner's progress. Remote requests
can outlive a lease, so their identities remain stable: one uniquely constrained
order per checkout attempt, reservation ID equal to order ID, and one Phase 2
payment per order. These constraints and downstream idempotency provide safety
beyond lease expiry. No database transaction spans the orchestrator's HTTP calls.
Order creation and its attempt association commit atomically. Product prices are
read uncached and stored as order-item snapshots. Retries reuse those snapshots.

An interrupted process leaves enough durable phase/identity information for the
same authenticated user's retry after lease expiry. There is no background
recovery worker or reservation expiry scheduler in this phase. Reconciliation
states require investigation; deleting attempts or changing keys is unsafe.

## Service changes and schema

Order adds Buy controller/authentication/orchestrator, short transaction workflow
store, and scoped inventory/payment clients. Checkout attempts add lease, payment
identity/status and response/retry fields. Orders add unique nullable
checkoutAttemptId, scopedReservation and inventoryReserved (default false for
legacy data). Existing Hibernate schema-update conventions remain; no existing
data is removed. New Buy orders are not eligible for payment until the durable
reservation checkpoint is committed. Existing legacy orders remain eligible.

Payment adds an authenticated owner-only GET `/api/v1/payments/order/{orderId}`
recovery lookup, and checks the order's paymentEligible field. All Phase 2 Stripe
idempotency and verification rules remain. The lookup never returns a client
secret and never initializes another payment.

New orders are explicitly scoped: payment-event handling finishes the order-ID
reservation directly and cannot fall back to anonymous stock operations. Existing
legacy processing retains its Phase 2 crash guard. Verified payment identity,
owner, amount and currency must still match the persisted order before fulfillment.

Inventory scoped APIs now require short-lived, action/reservation-ID scoped
service JWTs (subject order-service, audience inventory-service); ordinary user
JWTs are rejected. Gateway blocks public reservation access entirely. Legacy
inventory CRUD endpoints retain their contract. Inventory now needs the same
secure JWT_SECRET binding as Order; Compose passes it through. No `.env` values
or real credentials were added. The shared-key/trusted-service-network convention
remains; workload identity/mTLS is not introduced.

## Verification setup

`scripts/test-phase3.sh` runs all Phase 2 suites/packages, then an isolated Kafka
fixture and `scripts/checkout-integration.py`. The integration starts the five real
services with PostgreSQL, Redis and Kafka; only Stripe is mocked by a local HTTP
server. Payment's test-only launcher redirects the SDK to loopback; the launcher
is absent from the production jar. No production Stripe override was introduced.
The test uses signed fixture JWTs, not an authentication bypass. It exercises
actual Gateway routing, reservation persistence, Stripe request idempotency,
signed webhook delivery, real Kafka publication/consumption, confirmation and
cancellation. Auth Service token issuance and live Stripe are separate checks.

## File groups

- Order checkout package: new BuyActor, BuyAuthenticationFilter, BuyNowController,
  BuyNowService, BuyNowResponse, BuyErrorHandler, CheckoutWorkflowStore,
  CheckoutInventoryClient, CheckoutPaymentClient, ReservationTokens,
  LeaseLostException. Existing CheckoutAttempt/Repository/Request gain workflow
  metadata, locking lookup and the request-size bound.
- Order entity/response/service: scoped flags, unique attempt identity and payment
  eligibility. ProductClient adds a bounded lookup timeout. InventoryClient signs
  scoped requests. PaymentEventConsumer uses the explicit scoped marker.
- Inventory: ReservationAuthenticationFilter, JWT dependencies, JWT configuration,
  and Compose binding. Existing reservation logic/constraints remain unchanged.
- Payment: AuthoritativeOrder payment eligibility, PaymentService guard and
  owner-only lookup, PaymentController lookup route. No Stripe production behavior
  or webhook exception is weakened.
- Gateway: exact Buy route and public scoped-inventory block.
- Tests: BuyNowTest and BuyApiTest; test-only PaymentIntegrationApplication.
- Verification: compose.phase3-test.yml, scripts/test-phase3.sh,
  scripts/checkout-integration.py and this report.

## Operational boundaries

Recovery requires an authenticated retry with the original key/payload; no worker
stores or refreshes user JWTs. An expired user token must be replaced with a fresh
valid token for that same user. A 202 with an order ID is not permission to release
stock. Reconciliation may retain reservations until an operator establishes the
actual payment/reservation outcome. Client secrets are not stored in attempts;
READY replays obtain a fresh response from the same persisted PaymentIntent.
Legacy inventory CRUD authorization is unchanged and should be reviewed before
production exposure. Auth Service issuance, real Stripe accounts, workload
identity/mTLS and production deployment are not covered by the mock-Stripe test.

## Executed results

Java 21 and the existing Maven settings/cache were used:

```bash
export JAVA_HOME=/workspace/.orderflow/jdk
export MAVEN_USER_HOME=/workspace/.orderflow/maven
export MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
scripts/test-phase3.sh -s /workspace/.orderflow/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.orderflow/maven/repository
```

The wrapper completed the five Maven verify/package steps. Its first integration
attempt then exposed a test-helper error (plain-text webhook acknowledgement
parsed as JSON). After that correction, the connection-close injection was
transparently retried by the underlying HTTP transport, correctly returning the
same intent; the fixture was changed to a deterministic HTTP 500 *after* intent
creation to verify the unresolved 202 path. The corrected failed integration step
was rerun independently, without skipping any assertion:

```bash
JAVA_HOME=/workspace/.orderflow/jdk python3 -u scripts/checkout-integration.py
```

That command exited 0. It verified real HTTP, PostgreSQL, Redis, Stripe SDK calls
to a loopback mock, signed webhook ingress and real Kafka publication/consumption.
Successful scenarios: single/multiple items, authoritative totals, concurrent
same-key replay with one intent, payload conflict, limited-stock races, private
reservation denial, successful payment confirmation/deduction, definitive
cancellation/release, repeated webhooks, and ambiguous creation retry retaining
stock and reusing the same intent. It stopped only the service processes it
started. Logs: `/workspace/.orderflow/logs/phase3-integration-final-2.log` and
`/tmp/orderflow-checkout-it-wv7449df/`.

An additional actual database-constraint failure test was added and the complete
Order verify/package suite rerun successfully using the same settings/cache and
isolated PostgreSQL URL. Final unique suite counts (not summed repeated runs):

| Service | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: |
| Order | 39 | 0 | 0 |
| Inventory | 18 | 0 | 0 |
| Product | 4 | 0 | 0 |
| Payment | 34 | 0 | 0 |
| Gateway | 6 | 0 | 0 |
| Total | 101 | 0 | 0 |

Evidence: `phase3-final.log` (five-service suites/packages and initial integration
helper failure), `phase3-order-final.log` (latest Order suite), and the successful
integration log above. Baseline verification was also executed before the Phase 3
checks (`phase3-baseline.log`). `git diff --check`, shell syntax checks and Python
syntax parsing passed. Initial new-test errors came from eager Mockito stubbing
invoking previous answers with null arguments; changed to doReturn/doThrow/
doAnswer without removing or weakening assertions. The duplicate-key SQL error
in the final Order log is an expected, asserted isolated write-failure fixture.
Docker socket access needed the execution sandbox's escalated test permission;
the authorized fixture/test commands subsequently ran successfully.

No required automated check remains blocked. Live Stripe, production deployment,
Auth Service token issuance and production network/identity hardening are not
verified by this fixture. The tested checkout lifecycle uses a Stripe HTTP mock;
there were no real charges. Phase 1/2 work remains intact, and nothing was committed
or pushed. The full test wrapper now contains the corrected integration helper;
its entire wrapper was not rerun after the helper-only fixes because its Java
suites were already green and the failed integration step was rerun successfully.
