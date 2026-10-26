# Buy Now Phase 2: payment reliability and Stripe security

Phase 1 changes remain intact. This phase hardens Payment Service, the relevant
Order Service consumer, and Gateway. There is still no `POST /api/v1/buy` endpoint
and no claim of end-to-end checkout success.

## Public payment API contract

All public payment operations require a valid, unexpired **user Bearer JWT** issued
under the existing shared-key convention. Payment Service verifies the signature
itself, including direct calls to its exposed port. `X-User-Id`/`X-User-Role` are
not accepted as proof of identity. Service-purpose tokens cannot use these routes.

- `POST /api/v1/payments`: body `{"orderId":"<uuid>"}`. The old `amount` field is
  optional and deprecated; supplied amounts are ignored. Client currency is not
  used. Payment Service forwards the original verified Bearer token to Gateway's
  existing `GET /api/v1/orders/{id}` route. Gateway verifies the token and replaces
  identity headers. Payment Service independently compares order ID and owner,
  including for administrators, and requires persisted order status `PENDING`,
  positive two-decimal pricing and a supported two-decimal currency (INR/USD/EUR).
  No unrestricted internal order endpoint is introduced.
- Order absence is 404; another customer's order is hidden as 404 by Order Service
  or rejected as 403 on an ownership mismatch. Missing/invalid JWT is 401.
  Ineligible order, terminal payment, changed order/payment pricing, or a payment
  requiring reconciliation is 409. Unavailable order validation is 503: fail
  closed, with no Stripe initialization.
- Initialization response includes existing payment fields plus `currency` and
  `initializationState`. `READY` returns 201. Unknown/retryable initialization
  returns 202 with no client secret. `RECONCILIATION_REQUIRED` returns 409 with no
  secret. A known terminal Stripe intent can be READY without a secret while its
  verified webhook is pending; **201/READY never means paid**. Only verified
  webhook processing establishes a terminal payment outcome.
- Retrying POST for the same eligible order returns the existing payment/intent;
  an active, validated intent's secret is retrieved from Stripe rather than
  stored in the database. Secrets are never included for unknown, mismatched or
  terminal intents.
- `GET /api/v1/payments/{id}` verifies payment and order ownership and returns
  status without a client secret. `GET /api/v1/payments` now lists **only the
  caller's payments**. `GET /api/v1/payments/user/{userId}` requires that same
  authenticated user ID. List entries also revalidate order ownership. The old
  unrestricted all-customer listing is intentionally removed, including for
  administrators. These GET response shapes remain lists/payment objects.

## Durable initialization and Stripe idempotency

`PaymentStore.prepare` commits a row before any Stripe creation call, using the
existing unique order constraint and PostgreSQL `ON CONFLICT`. A separate
transaction locks that row during initialization. Concurrent requests serialize;
only the first calls Stripe create. Initialization persists frozen owner, amount,
currency and attempt timestamp. A second terminal-state check under the lock
rejects a payment completed/cancelled during request validation.

The stable Stripe key is `orderflow:payment:<durable-payment-uuid>:v1`. Retries use
identical amount, currency and recovery metadata (`paymentId`, `orderId`, `userId`).
Do not change that key version or mutate frozen parameters for an existing attempt
in future deployments. Stripe requests have bounded connection/read timeouts and
no automatic SDK network retries; application retries reuse the same attempt/key.

- A create timeout/ambiguous SDK result leaves payment `PENDING`, initialization
  `UNKNOWN`, no client secret and no failure event. Retrying safely replays the
  same key. A known intent is always retrieved; retrieval failure never creates
  a replacement.
- Returned/retrieved intents must match the frozen amount, currency and metadata.
  A mismatch requires reconciliation and discloses no client secret.
- If an intent ID remains unknown and the durable attempt is older than **23
  hours**, creation is stopped. This conservative limit is below Stripe's
  minimum 24-hour idempotency retention. The attempt remains unresolved for
  reconciliation; no replacement key or attempt is silently started. Database and
  service clocks must be synchronized. Legacy rows without a start timestamp and
  without an intent ID are also quarantined.
- Verified webhooks can recover an ID after an ambiguous creation result by its
  frozen metadata, even if our initializer never saved the Stripe response.

## Verified outcomes, notifications and order transitions

Gateway exempts only **POST `/api/v1/payments/webhook`** from its JWT filter and
strips untrusted identity headers on that route. GET, trailing-slash, child paths
and normal payment routes retain JWT requirements. The existing payment route
continues to forward the raw body and `Stripe-Signature` unchanged.

Payment Service independently has the same exact webhook JWT exception. It uses
Stripe's `Webhook.constructEvent` with the configured webhook secret and timestamp
tolerance. Invalid/expired signatures and missing headers are rejected. Valid
signatures are required before business processing. No request body status alone
is authoritative.

For supported events, the handler retrieves the **current** PaymentIntent from
Stripe, validates its identity/pricing metadata, and locks its payment row.
`succeeded` must corroborate the full received amount before `COMPLETED` is set.
Only `canceled` establishes definitive `FAILED`. Card failures
(`requires_payment_method`), required action and processing remain unresolved:
**no failure event and no inventory release**. Terminal verified states cannot
regress, and a cancelled payment is not resurrected by a later success delivery.
Old terminal rows are not automatically trusted: the new `terminal_verified`
flag defaults false, and status reads used by consumers remain PENDING until
Stripe corroborates the same terminal outcome. Inconsistent legacy rows require
reconciliation.

A durable pending-notification marker commits with a verified terminal payment.
Publication then waits for Kafka's acknowledgement in a separate transaction.
A broker failure returns webhook 500 and leaves the marker pending; redelivery
repairs publication. Duplicate deliveries after acknowledgement do not republish.
A crash after broker acknowledgement but before saving the flag may republish:
this remains at-least-once delivery, not exactly-once messaging. No second
Kafka-based initialization flow or broad outbox architecture is introduced.

Order Service locks each order while applying an event. It fetches payment status
from `/internal/v1/payments/{paymentId}` using a **30-second signed service JWT**,
scoped to that exact ID, subject `order-service`, audience `payment-service`, and
purpose `payment-status`. Payment Service checks all these claims plus signature
and expiry. User JWTs and unscoped service tokens are rejected. The route is not
exposed by Gateway and returns only IDs, amount, currency and verified status,
never a client secret. Both services reuse the established JWT signing key;
there is no anonymous internal endpoint or shared-token bypass.

The consumer compares payment ID, order ID, owner, total and currency to the
persisted order. Topics and event bodies are not proof of success/failure.
Unresolved status cannot confirm/cancel or change inventory. Only PENDING orders
transition: verified COMPLETED -> CONFIRMED, verified FAILED -> CANCELLED.
CONFIRMED, CANCELLED and DELIVERED orders never regress or resurrect. Failures
propagate for retry instead of being swallowed.

If an order-ID-scoped reservation exists, the consumer uses Phase 1's idempotent
batch release/deduction. Phase 3 must use that stable order identity for this
consumer, or explicitly persist and support a different reservation identity.
Legacy quantity-only operations use the new `legacy_payment_effects` crash guard:
each product's aggregated quantity and action are committed as STARTED before the
remote call, then COMPLETED after a successful response. Completed effects survive
an outer order rollback and are skipped on replay. STARTED effects are ambiguous
and block for reconciliation rather than repeating a possibly committed mutation.
This preserves legacy processing without blind timeout retries. It deliberately
favors correctness over automatic progress when old APIs cannot prove an outcome.

## Schema, dependencies and deployment

Existing Hibernate `ddl-auto=update` conventions are retained; no migration
framework, data deletion or manual schema manipulation is introduced.

- `orders.currency`: non-null varchar with historical default INR (the old Stripe
  integration always used INR). New order responses include persisted currency.
- `payments.currency`: same compatibility default; initialization state/start time,
  safe diagnostic code, terminal-verification flag, durable pending event,
  publication flag and optimistic version are additive. Existing terminal-status
  enum values are unchanged, avoiding incompatible legacy status constraints.
  Initialization/recovery and pending-notification indexes are added. Unique order
  and Stripe intent constraints remain enforced.
- `legacy_payment_effects`: additive table with composite primary key
  `(order_id, product_id)`, immutable logical payment/action/quantity and durable
  STARTED/COMPLETED status. It does not create a cross-service foreign key.
- Order and Payment reuse JJWT 0.12.6, already used by Auth/Gateway. Payment adds
  the established Spring Cloud load-balancer dependency for Gateway discovery.
  Stripe remains at the repository's version 30.0.0, Java 21 / Boot 3.5.5.

Deploy the updated Order response/currency support, Payment protection/status
endpoint, and Gateway exception together before enabling payment requests/events.
Share the **existing** JWT_SECRET securely with Order and Payment as well as
Auth/Gateway; Compose now passes that existing binding to both services. Keep
STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET in secure environment settings, never
files or source. Production internal traffic must remain on the trusted service
network or its existing TLS transport; the repository uses HTTP inside its Docker
network. Services holding the shared signing key are trusted under this existing
architecture. Future workload identity/mTLS isolation is not implemented here.

## Reproducible verification

```bash
# Java 21 JDK, Docker/Compose, Maven Central access (or prepared cache) required.
scripts/test-phase2.sh
# Optional existing Maven proxy/cache settings:
scripts/test-phase2.sh -s /path/to/settings.xml -Dmaven.repo.local=/path/to/cache
```

This first runs the reusable Phase 1 verification script (Order, Inventory,
Product), then Payment and Gateway Maven `verify`. All integration persistence is
in the disposable Phase 1 PostgreSQL fixture, never production/development tables.
Payment's H2/create-drop test defaults are explicitly overridden to PostgreSQL
and schema update for this run. Redis uses the separate fixture port. The scripts
supply an explicitly test-only JWT key; payment test properties contain test-only
Stripe strings. Stripe create/retrieve calls and broker publication are mocked;
no live Stripe calls or charges are made. Gateway routing tests run an actual
local HTTP destination and Gateway server, checking unchanged raw body/signature.
No assertions are disabled and missing required services do not silently skip tests.

## Remaining recovery boundaries

- No complete Buy Now orchestrator, cross-service transaction, refund handling,
  reservation expiry, lease worker or reconciliation UI is implemented.
- A lost process before/after Stripe can leave UNKNOWN initialization; retry the
  same payment within the safe window, or reconcile the existing Stripe intent.
  Do not reset keys, delete attempts, or release inventory on a mere timeout.
- Pending notifications depend on Stripe redelivery or future reconciliation to
  retry; there is no background scanner yet. Broker acknowledgements and consumer
  retries are still at-least-once.
- STARTED legacy stock effects require explicit reconciliation. If a process
  crashed before Phase 2's guard existed, historical quantity-only stock effects
  cannot be reconstructed automatically; reconcile those old uncertain orders.
  Prefer scoped reservations for every new Phase 3 flow.
- Durable side-effect guards require connection-pool headroom for their short
  REQUIRES_NEW transactions. Exhaustion fails closed before another mutation or
  leaves an operation requiring reconciliation; it must not trigger blind retries.
- A concurrently cancelled/confirmed order cannot be resurrected by its consumer.
  Phase 3 still needs explicit orchestration/recovery policy for cross-service
  races and terminal Stripe success whose webhook has not been applied.

## Phase 2 file manifest

Phase 1 files not listed here remain preserved. `OrderService.java` and the test
script listed here contain both preserved Phase 1 work and the Phase 2 additions.

- `docker-compose.yml`
- `docs/buy-now-phase2.md`
- `gateway/src/main/java/com/orderflow/gateway/security/JwtGatewayFilter.java`
- `gateway/src/test/java/com/orderflow/gateway/JwtGatewayFilterTest.java`
- `gateway/src/test/java/com/orderflow/gateway/WebhookRoutingTest.java`
- `order-service/pom.xml`
- `order-service/src/main/java/com/orderflow/order/client/InventoryClient.java`
- `order-service/src/main/java/com/orderflow/order/client/PaymentStatusClient.java`
- `order-service/src/main/java/com/orderflow/order/consumer/PaymentEventConsumer.java`
- `order-service/src/main/java/com/orderflow/order/dto/response/OrderResponse.java`
- `order-service/src/main/java/com/orderflow/order/entity/LegacyPaymentEffect.java`
- `order-service/src/main/java/com/orderflow/order/entity/Order.java`
- `order-service/src/main/java/com/orderflow/order/repository/OrderRepository.java`
- `order-service/src/main/java/com/orderflow/order/service/LegacyPaymentEffectStore.java`
- `order-service/src/main/java/com/orderflow/order/service/OrderService.java`
- `order-service/src/main/resources/application.yml`
- `order-service/src/test/java/com/orderflow/order/client/PaymentStatusClientTest.java`
- `order-service/src/test/java/com/orderflow/order/consumer/PaymentEventConcurrencyTest.java`
- `order-service/src/test/java/com/orderflow/order/consumer/PaymentEventConsumerTest.java`
- `order-service/src/test/java/com/orderflow/order/service/LegacyPaymentEffectStoreTest.java`
- `payment-service/pom.xml`
- `payment-service/src/main/java/com/orderflow/payment/client/AuthoritativeOrder.java`
- `payment-service/src/main/java/com/orderflow/payment/client/OrderClient.java`
- `payment-service/src/main/java/com/orderflow/payment/config/OrderClientConfig.java`
- `payment-service/src/main/java/com/orderflow/payment/controller/InternalPaymentController.java`
- `payment-service/src/main/java/com/orderflow/payment/controller/PaymentController.java`
- `payment-service/src/main/java/com/orderflow/payment/controller/PaymentWebhookController.java`
- `payment-service/src/main/java/com/orderflow/payment/dto/request/CreatePaymentRequest.java`
- `payment-service/src/main/java/com/orderflow/payment/dto/response/PaymentResponse.java`
- `payment-service/src/main/java/com/orderflow/payment/entity/Payment.java`
- `payment-service/src/main/java/com/orderflow/payment/enums/InitializationState.java`
- `payment-service/src/main/java/com/orderflow/payment/exception/GlobalExceptionHandler.java`
- `payment-service/src/main/java/com/orderflow/payment/producer/PaymentEventProducer.java`
- `payment-service/src/main/java/com/orderflow/payment/repository/PaymentRepository.java`
- `payment-service/src/main/java/com/orderflow/payment/security/PaymentActor.java`
- `payment-service/src/main/java/com/orderflow/payment/security/PaymentAuthenticationFilter.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentInitializer.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentNotificationService.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentService.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentStore.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentWebhookService.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentWebhookStore.java`
- `payment-service/src/main/java/com/orderflow/payment/service/StripePaymentService.java`
- `payment-service/src/main/resources/application.yml`
- `payment-service/src/test/java/com/orderflow/payment/OrderClientTest.java`
- `payment-service/src/test/java/com/orderflow/payment/PaymentEventProducerTest.java`
- `payment-service/src/test/java/com/orderflow/payment/PaymentReliabilityTest.java`
- `payment-service/src/test/java/com/orderflow/payment/PaymentSecurityTest.java`
- `payment-service/src/test/java/com/orderflow/payment/StripeRequestTest.java`
- `payment-service/src/test/resources/application.yml`
- `scripts/test-phase1.sh`
- `scripts/test-phase2.sh`

## Executed verification and results

Initial Payment and Gateway baselines each passed their existing context test and
Maven `verify`. The final combined invocation was:

```bash
export JAVA_HOME=/workspace/.orderflow/jdk
export MAVEN_USER_HOME=/workspace/.orderflow/maven
export MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
scripts/test-phase2.sh -s /workspace/.orderflow/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.orderflow/maven/repository
```

| Service | Tests passed | Failures/errors | Skipped | verify/package |
| --- | ---: | ---: | ---: | --- |
| Order | 27 | 0 | 0 | passed |
| Inventory | 18 | 0 | 0 | passed |
| Product | 4 | 0 | 0 | passed |
| Payment | 34 | 0 | 0 | passed |
| Gateway | 6 | 0 | 0 | passed |
| Total | 89 | 0 | 0 | all five passed |

The 89 count is the final suite, not a sum of repeated runs. Tests exercise real
PostgreSQL uniqueness/concurrency/durability, Phase 1 regression behavior, current
order ownership/pricing validation, stable Stripe request options, duplicate
initialization, retention expiry, uncertain responses and ID recovery, verified
terminal transitions, concurrent webhook delivery, unverified legacy statuses,
durable notification retries, broker acknowledgement/error/interrupt handling,
scoped service JWTs, invalid/expired signatures and exact Gateway routing.
Order tests cover parallel deliveries, terminal guards, unresolved failures,
legacy stock timeout blocking, and durable stock-effect completion surviving an
outer order rollback. Stripe and Kafka interactions are mocked, not charged/sent
to production.

Two compilation failures were encountered and fixed:

1. A wildcard JUnit import made `Order` ambiguous with JUnit's annotation. Replaced
   it with explicit test imports; the full Order suite and Phase 1 regressions
   subsequently passed.
2. A direct JSON exception import was not present on Payment's runtime compile
   classpath. Removed that dependency instead of adding an unnecessary library;
   webhook construction/signature rejection is now a separate try block from
   retryable business processing. The full Payment suite then passed, followed by
   the complete combined run above. No assertions were weakened or tests deleted.

An additional packaged-jar check ran with:

```bash
python3 -u /tmp/phase2-http-security.py
```

It booted Payment Service on a temporary port against the isolated test database,
then verified HTTP 401 for missing JWT/spoofed identity/incorrect webhook routes,
HTTP 400 for a JWT-authenticated malformed payment request and invalid signature,
HTTP 401 for a user JWT on internal status, and authorized internal routing with a
proper scoped service JWT (404 for the deliberately nonexistent payment). An
unsupported event signed with a test-only webhook secret returned 200 without a
user JWT and without making a Stripe API call. The temporary process was stopped.
Gateway integration tests separately ran an actual Gateway and local destination,
verifying the raw payload and signature arrive unchanged.

`git diff --check`, shell syntax validation for both verification scripts, and
Docker Compose configuration validation passed. Read-only PostgreSQL table
inspection confirmed the payment uniqueness/indexes/defaults, unchanged terminal
status constraint, and the legacy stock-effect composite primary key.

Evidence in this environment:

- `/workspace/.orderflow/logs/phase2-final-verification.log` (successful full run)
- `/workspace/.orderflow/logs/phase2-payment-final.log` (targeted run after the fix)
- `/workspace/.orderflow/logs/phase2-http-security.log` (packaged HTTP check)
- `/workspace/.orderflow/logs/phase2-baseline-payment-service.log` and
  `phase2-baseline-gateway.log`
- each service's `target/surefire-reports/TEST-*.xml`

No required automated Phase 2 check is blocked or failing. Live Stripe account
integration, real broker delivery and the complete checkout flow remain untested;
they were not prerequisites for the requested mocked verification and no real
credentials or charges were used. Phase 2 is ready as the foundation for Phase 3,
with the recovery boundaries above explicitly retained. Phase 1 work is preserved;
all changes remain uncommitted on `feature/buy-now-checkout`.
