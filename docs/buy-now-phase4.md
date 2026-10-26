# Buy Now Phase 4: recovery and final verification

Continues on `feature/buy-now-checkout`, preserving Phases 1–3. No commit, push,
branch change, `.env` edit, production data operation or real charge is part of
this work. Prior reports describe the foundation; this report supersedes their
statements that notification recovery requires webhook redelivery.

## Confirmed gaps and changes by service

### Payment

The existing terminal outcome and `pending_event`/`event_published` fields already
form a single-message transactional outbox: a payment has one immutable verified
terminal outcome. However, nothing drained that outbox without another webhook.
`PaymentRecoveryWorker` now scans at most 20 due records per tick, prioritizes pending notifications over
nonterminal polls, claims work in
short committed transactions, and publishes through the existing acknowledged
producer. It also retrieves **known** PaymentIntent IDs to recover terminal
outcomes when a webhook never arrives. Retrieval validates the frozen payment
snapshot through the same `PaymentWebhookStore` used by signed webhooks. It never
creates an intent, changes an idempotency key, returns a secret, or releases stock
on a timeout. Webhook signature verification remains mandatory on HTTP ingress.

A successful pending/processing retrieval schedules another read after 60 seconds;
it does not imply payment failure. Unrecognized/mismatched responses fail closed.
Crashes leave the record eligible after its 60-second claim. A durable random
claim token fences scheduling updates from an older worker. Concurrent outcomes
and publication still serialize on the payment row; duplicate publication after
broker acknowledgement/process crash remains possible and safe for consumers.

Each failed recovery backs off from 30 seconds up to one hour. Ten unsuccessful
claims stop automatic recovery and retain the row for investigation; healthy
retrieval resets that failure budget. A crash consumes a claim too. Exhausted
records are identifiable by `recovery_attempts >= 10`, even if the crash prevented
writing an error code. Scheduling defaults on, with fixed delay 30 seconds:
`orderflow.recovery.enabled` and `orderflow.recovery.delay-ms` are configurable.
Tests invoke workers explicitly, while the packaged integration enables them.
Scan/recovery logs contain fixed codes and record IDs, never SDK exceptions,
request bodies, JWTs or client secrets. Monitor warning codes and exhausted rows.
No alerting platform or reconciliation UI is introduced.

Standalone `POST /api/v1/payments` responses now include
`Cache-Control: no-store` for READY, UNKNOWN and RECONCILIATION_REQUIRED responses,
matching Buy Now. Kafka send blocking/delivery timeouts are bounded.

### Order

The legacy `OrderEventProducer` previously sent `order.created` asynchronously
inside the order transaction, allowing publication and order commit to diverge.
It now enqueues an immutable `order_notifications` row in that same transaction
(MANDATORY propagation). A scheduled drainer locks each message, waits for Kafka
acknowledgement, and marks it published. Failed messages retain attempt count,
safe code and next retry; at most 20 are selected per tick, with the same
30-second-to-one-hour backoff and ten-attempt budget. Rollback removes the enqueue;
a crash after acknowledgement may duplicate an event with the same order ID.
Buy Now retains its existing event contract; this change repairs the legacy
order-created notification without adding a new checkout event architecture.

The producer now honors `spring.kafka.bootstrap-servers`, rather than ignoring
that setting and constructing its own address solely from `KAFKA_HOST`. Its
blocking/delivery timeouts are bounded. Legacy Inventory HTTP calls also have a
10-second bound; an ambiguous mutation still trips the existing durable legacy
effect guard instead of being blindly repeated. Buy recovery logs safe attempt
IDs/status/error codes. Durable workflow leases, canonical request comparison,
unique order identity and short orchestrator transactions were preserved.

Payment consumers now use four retries after the initial delivery, separated by
one second, then acknowledged publication to `payment.completed.DLT` or
`payment.failed.DLT`. A DLT failure is propagated, so the original record is not
silently treated as recovered. An unavailable broker/DLT can continue infrastructure
retries; business poison records leave the main stream when DLT publication
succeeds. No DLT consumer loops records back automatically. Inspect and repair the
cause, then replay the original JSON to its original topic. Never invent payment
status or alter identities. The consumer revalidates authoritative payment status
on replay, locks the order, and uses idempotent scoped inventory effects. An
already RELEASED reservation rejects deduction with 409, so that consumer cannot
confirm an order after its stock was released. Terminal
orders remain unchanged. Legacy STARTED effects still require reconciliation.

DLT topics are provisioned with one partition/replica for this repository's
single-broker convention. Configure suitable retention, replication and monitoring
for a deployed cluster; retained dead letters require operator attention before
broker retention removes them. Broker acknowledgement is not exactly-once delivery.

### Auth

The expanded real-Auth run exposed an existing startup CommandLineRunner that
printed the JWT signing key. Its business assertions passed, but the final
log-safety assertion correctly failed. Removed that diagnostic runner (including
its expiration print). The existing context-load test now captures actual startup
output, positively checks that startup completed, and rejects any signing-key
value in that output. Authentication/token issuance behavior is otherwise unchanged.
The packaged integration also rejects fixture client secrets, JWT/webhook keys
and the Stripe API fixture key in application logs. Its cleanup/log-location
reporting now runs before raising that assertion, so failures still produce a
fixture manifest and safe cleanup instructions.

### Inventory, Product, Gateway, Discovery

No Phase 4 application changes were needed in these services. Existing database
product locks, atomic batch reservations, terminal reservation guards, uncached
pricing, JWT checks, exact signed webhook exception, and private reservation
boundary are preserved and exercised by the regression/integration checks. Auth
and Discovery are additionally packaged and started by the expanded test.

## Reservation expiry decision

There is no reservation expiry worker, and this phase deliberately does not enable
one. A PaymentIntent in `requires_payment_method`, `requires_action` or `processing`
can still succeed after 15 minutes. The current application has no coordinated
cancellation protocol that first prevents initialization, cancels/reconciles the
same Stripe intent, fences concurrent workers, and proves cancellation before
release. A timestamp alone would therefore create a late-success/stock-release
race. No expiration column or misleading disabled expiry worker was added.

Only a verified definitive cancellation leads to FAILED -> CANCELLED and scoped
release. Success leads to COMPLETED -> CONFIRMED and scoped deduction. Duplicate
and conflicting terminal operations remain guarded. The integration ages an
unresolved reservation beyond 15 minutes and checks that stock stays reserved.
Known-intent polling can recover a missed cancellation webhook and trigger the
normal release path. Unresolved abandoned carts may retain stock indefinitely;
this is an explicit limitation requiring safe payment cancellation work, not an
assumption that pending means failed.

## Checkout recovery and security boundaries

The existing PostgreSQL lease and database constraints remain the checkout safety
boundary: expired owners cannot checkpoint over a replacement, order creation and
attempt association commit together, reservation ID equals durable order ID, and
Payment has one durable attempt per order. Existing crash/expiry/concurrency tests
are retained. A customer retries with the same user, key and payload, using a
fresh valid JWT if necessary. Persisted order-price snapshots are reused.

A missing Stripe intent ID after ambiguous initialization is not permission for a
background worker to create one. The authenticated retry reuses the original
logical attempt/key within the existing 23-hour safety window; frozen metadata
can recover its ID from a verified webhook. Beyond that window, or after retry
exhaustion/mismatch, investigate the existing attempt in Stripe. Do not delete
attempts, reset keys, create replacement payments or release uncertain stock.
Pre-payment checkout work still resumes through authenticated retries, not by
storing customer JWTs. Workflow snapshots may retain initialization progress until
a replay; the persisted order/payment/reservation states remain authoritative.

Gateway overwrites identity headers; Buy verifies JWTs independently on direct
access. Payment verifies user JWTs and owner/order snapshots independently.
Internal payment status requires a narrowly scoped service JWT and has no Gateway
route. Scoped inventory routes require action/ID-specific service JWTs and are
blocked publicly at Gateway. Only the exact POST webhook route bypasses user JWT,
and it still requires a valid Stripe signature. Reads return no client secret;
owner-only initialization responses use no-store. No Stripe secret key is returned.
For precision, the Phase 3 report's “every Buy response” header wording was too
broad: authentication/validation/conflict advice errors carry no secret and may
omit that header. All controller workflow responses, including responses that
can contain a client secret, use no-store; this phase preserves that contract.

Legacy order and inventory APIs retain their established contracts. Legacy Order
Service still trusts Gateway identity headers on its existing routes, and legacy
inventory CRUD authorization is unchanged; directly exposing those ports outside
the trusted service network remains unsafe. Buy and Payment's direct protection
does not imply that all legacy endpoints independently authenticate JWTs. Legacy
multi-line order creation also retains its pre-existing anonymous reservation/
partial-failure limitation; new Buy orders use the scoped atomic protocol. No
broad legacy API migration was attempted.

## Files and schema

New production files:

- `order-service/src/main/java/com/orderflow/order/config/PaymentEventRetryConfiguration.java`
- `order-service/src/main/java/com/orderflow/order/outbox/OrderNotification.java`
- `order-service/src/main/java/com/orderflow/order/outbox/OrderNotificationRepository.java`
- `order-service/src/main/java/com/orderflow/order/outbox/OrderNotificationDelivery.java`
- `order-service/src/main/java/com/orderflow/order/outbox/OrderNotificationWorker.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentRecoveryStore.java`
- `payment-service/src/main/java/com/orderflow/payment/service/PaymentRecoveryWorker.java`

Additional Auth files: `auth-service/src/main/java/com/orderflow/auth/AuthServiceApplication.java`
and `auth-service/src/test/java/com/orderflow/auth/AuthServiceApplicationTests.java`.

Updated production files: Order `producer/OrderEventProducer`,
`config/KafkaProducerConfig`, `client/InventoryClient`, `checkout/BuyNowService`;
Payment `entity/Payment`, `controller/PaymentController`, `service/PaymentWebhookStore`, and
`src/main/resources/application.yml`.
Tests: new Order `outbox/OrderNotificationTest`; expanded Payment
`PaymentReliabilityTest`, `PaymentSecurityTest`, and test configuration.
Verification: updated `scripts/test-phase1.sh`, `scripts/test-phase2.sh`,
`scripts/checkout-integration.py`; new `scripts/test-phase4.sh` and this report.

Schema changes are additive through the existing Hibernate `ddl-auto=update`:

- New `order_notifications`: order UUID primary key, immutable owner/amount,
  published flag, attempt count, next-attempt timestamp and safe error code;
  index `(published,next_attempt_at)`.
- Payment adds `recovery_attempts` (non-null default zero), `recovery_next_at`,
  `recovery_token` UUID, `recovery_error`; index on due time/attempt count.

No enum changes, data deletion, migration framework or new service. Sites using
controlled migrations must provision these additions before deploying workers.

## Verification commands and evidence

The baseline command was executed before major service changes:

```bash
export JAVA_HOME=/workspace/.orderflow/jdk
export MAVEN_USER_HOME=/workspace/.orderflow/maven
export MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
scripts/test-phase3.sh -s /workspace/.orderflow/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.orderflow/maven/repository
```

Its five Maven suites/packages passed (101 tests, no failures/skips). Editing the
shell script while Bash was still reading it interrupted the wrapper after those
builds (`very.enabled=false: command not found`). The script contents are valid;
this was a verification orchestration mistake. The baseline's remaining step was
executed separately and passed:

```bash
JAVA_HOME=/workspace/.orderflow/jdk python3 -u scripts/checkout-integration.py
```

Evidence: `/workspace/.orderflow/logs/phase4-baseline.log` and
`phase4-baseline-integration.log`; service logs `/tmp/orderflow-checkout-it-q0gh8_p4`.
Auth and Discovery were also independently verified/package-built using:

```bash
for service in auth-service discovery-server; do
  (cd "$service" && bash ./mvnw -B \
    -s /workspace/.orderflow/maven/settings.xml \
    -Dmaven.repo.local=/workspace/.orderflow/maven/repository verify)
done
```

Both passed: Auth 10 tests and Discovery 1 test. Logs are
`phase4-auth-service.log` and `phase4-discovery-server.log`.

Full final verification uses the same Java/Maven environment above:

```bash
scripts/test-phase4.sh -s /workspace/.orderflow/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.orderflow/maven/repository
```

It invokes the Phase 3 script, which invokes Phase 2 and Phase 1, so every affected
service executes Maven `verify`, tests and packaging. It then verifies/packages
Auth and Discovery and executes the expanded seven-service integration. The
scripts preserve explicit PostgreSQL/Redis ports, disable listener/scheduler
startup for isolated suites, and enable real Kafka/scheduling in packaged tests.
The first expanded run passed Order 43, Inventory 18 and Product 4, then failed
one of Payment's 41 tests: its bounded scan selected nonterminal intent polls ahead
of a pending terminal notification. The production scan now prioritizes pending
notifications; the original assertion was preserved. A further regression verifies
that a newly verified terminal outcome gets a fresh outbox budget even if prior
Stripe-read recovery was exhausted. Repeated webhook delivery does not reset that
publication budget. The complete wrapper was rerun after these fixes, with log
`/workspace/.orderflow/logs/phase4-verification-final.log`. Final results are
recorded below after execution; repeated baseline/intermediate runs are not
counted again in totals. That wrapper completed all seven builds and all business
integration assertions, then failed its final log-safety assertion on the existing
Auth startup key diagnostic. After removing it, only the affected Auth suite/build
and the failed seven-service integration step were rerun; unchanged green suites
were not rerun again. An initial Auth edit command used the wrong working
directory, changed nothing, and started an additional pre-fix Auth verify; the
paths were corrected before final verification (`phase4-auth-pre-fix.log`).
That restricted execution also failed socket creation and Mockito/Byte Buddy JVM
attachment (six test errors); the final Auth run uses the same escalated local
execution access as the successful full suites. This was a sandbox limitation,
not an automatic approval rejection or an application defect.
The first permitted Auth rerun then exposed ambiguity in the new test's injection:
this application already registers two JwtProperties beans. The test now reads
`jwt.secret` directly, preserving both startup and non-disclosure assertions and
leaving existing authentication configuration unchanged. That failed run had
nine passing tests and one injection error (`phase4-auth-test-injection-failure.log`).

## Developer's final major end-to-end test

Prerequisites: Java 21 JDK, Python 3, Docker/Compose, Maven wrapper executable
support and dependency access/cache. Run from the repository root. The prepared
cloud environment uses the JAVA_HOME/Maven exports above; another workstation
should select its own JDK/cache/settings. Run the full command above, or, with
all seven jars/test launcher already built, run:

```bash
docker compose -p orderflow-phase1-test -f compose.phase1-test.yml \
  -f compose.phase3-test.yml up -d --wait
PHASE4_INTEGRATION=1 python3 -u scripts/checkout-integration.py
```

PostgreSQL/Redis/Kafka use loopback 55432/56379/59092; apps use 19080–19085,
Discovery 19761. Do not point this script at shared/production services. It starts
and stops only its own Java processes, uses unique fixture products/users, and
prints its temporary log directory. The Payment test launcher redirects Stripe
SDK traffic to a loopback HTTP mock and is absent from the production jar. It
never makes a live payment. The fixture supplies test-only settings internally;
no real Stripe/JWT credentials are needed.

For a separately configured normal local deployment, required secret environment
variable names are `DB_PASSWORD`, `JWT_SECRET`, `STRIPE_SECRET_KEY`, and
`STRIPE_WEBHOOK_SECRET`. Service host names are `DB_HOST`, `EUREKA_HOST`,
`KAFKA_HOST`, `REDIS_HOST`; toolchain variables are `JAVA_HOME`,
`MAVEN_USER_HOME`, `MAVEN_OPTS`. Optional scheduler environment variables are
`ORDERFLOW_RECOVERY_ENABLED` and `ORDERFLOW_RECOVERY_DELAY_MS`.
Never use real credentials with the loopback fixture.

The expanded test registers a unique user through real Gateway -> Auth
`POST /api/v1/auth/register` with firstName, lastName, email and password (at least
eight characters), then calls `POST /api/v1/auth/login` with email/password.
It uses the resulting `tokenType` and `accessToken` for every checkout. To obtain
a token manually on your own local deployment, put fixture inputs in private
local JSON files and use the real Auth endpoints:

```bash
curl -fsS -H 'Content-Type: application/json' \
  --data-binary @test-register.json "$GATEWAY_URL/api/v1/auth/register"
ACCESS_TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  --data-binary @test-login.json "$GATEWAY_URL/api/v1/auth/login" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
curl -fsS -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $CHECKOUT_KEY" -H 'Content-Type: application/json' \
  --data-binary @test-buy.json "$GATEWAY_URL/api/v1/buy"
```

`test-buy.json` contains only `{"items":[{"productId":"<existing UUID>","quantity":1}]}`.
Use the same key/payload on retry. Do not log token/secret response bodies.
The automated mock-based test performs this flow without requiring manual files.

Expected initial response: HTTP 201, `Cache-Control: no-store`, stable
checkoutAttemptId/orderId/payment.id, workflowStatus COMPLETED,
orderStatus PENDING, server-calculated totalAmount/currency and item unitPrice
snapshots, payment status PENDING/initializationState READY, and an ephemeral
owner-only clientSecret. COMPLETED here means initialized, not paid. Concurrent
replays return the same identities or 202; changed intent with the same key is
409. An ambiguous initialization is 202 without a secret and keeps stock held.

A verified current intent success (via a signed webhook, or server-side recovery
retrieval for an already-known intent) commits Payment COMPLETED and its outbox;
Kafka -> Order validates proof, deducts the order-ID reservation, and confirms the
order. Available stock remains reduced and reserved stock becomes zero.
Definitive Stripe cancellation instead sets FAILED, cancels the order, and
releases only that reservation. Repeated events never apply stock effects again.

The seven-service test additionally exercises missing-webhook recovery, an aged
pending reservation, legacy order/payment APIs and acknowledged order outbox
publication, and a poison record retained on Kafka DLT after bounded retries.
It starts Discovery and checks its HTTP UI, while clients use deterministic local
simple-discovery addresses; Eureka registration/routing is not certified by this
fixture. Docker application-image builds/full production Compose deployment and
live Stripe account behavior remain separate unverified paths.

### Cleanup and operational recovery

After the expanded run, its printed log directory contains `fixtures.json` and `cleanup.sql`, limited
to that run's UUIDs and generated after all its Java processes stop. Inspect it,
then remove **only those test rows**, without a database reset:

```bash
docker compose -p orderflow-phase1-test -f compose.phase1-test.yml \
  -f compose.phase3-test.yml exec -T postgres \
  psql -U postgres -d orderflow_phase1 -v ON_ERROR_STOP=1 < /path/from/output/cleanup.sql
```

Do not replay test Kafka records after deleting their fixture rows. Cleanup does
not delete topics, offsets, unrelated test suites' records, or production data.
Private manual test input files/token variables can then be removed/unset.

For production incidents, inspect the specific payment/order and Stripe outcome
before requeueing. Never edit terminal outcome, amount, owner, intent ID or
idempotency identity. After fixing the cause, an operator can reset scheduling
for **one investigated record** in its owning database:

```sql
UPDATE payments SET recovery_attempts=0, recovery_next_at=CURRENT_TIMESTAMP,
  recovery_token=NULL, recovery_error=NULL WHERE id='<investigated payment UUID>';
UPDATE order_notifications SET attempts=0, next_attempt_at=CURRENT_TIMESTAMP,
  last_error=NULL WHERE order_id='<investigated order UUID>' AND NOT published;
```

Quiesce workers before manual edits. Dead-letter replay should retain original
payload/IDs and use the original topic; do not reset the whole consumer group.
Legacy STARTED stock effects need manual evidence of the remote mutation before
any reconciliation. No automatic unsafe repair is provided.

## Final Java verification results

All seven Maven `verify` runs and executable-jar packages passed. Unique JUnit
suite counts, without adding repeated baseline/intermediate executions:

| Service | Passed | Failed/errors | Skipped | Verify/package |
| --- | ---: | ---: | ---: | --- |
| Order | 43 | 0 | 0 | passed |
| Inventory | 18 | 0 | 0 | passed |
| Product | 4 | 0 | 0 | passed |
| Payment | 42 | 0 | 0 | passed |
| Gateway | 6 | 0 | 0 | passed |
| Auth | 10 | 0 | 0 | passed |
| Discovery | 1 | 0 | 0 | passed |
| Total | 124 | 0 | 0 | all seven passed |

These include unit/MockMvc tests, Spring context tests, real PostgreSQL concurrency/
persistence tests and real Redis cache checks. Stripe and broker calls inside
isolated Java reliability tests are mocked. They are separate from the packaged
real-service HTTP/Kafka integration checks; no HTTP assertion counts are added to
the JUnit total.

The final wrapper's five-service packaged integration passed with the new recovery
code (`/tmp/orderflow-checkout-it-wx9mldku`). All original Phase 3 assertions were
preserved. The separate baseline integration also passed as recorded above.

`git diff --check`, `bash -n scripts/test-phase{1,2,3,4}.sh`, Python AST syntax
validation, and both production/test `docker compose ... config --quiet` checks
passed. Production Compose emitted expected unset-credential warnings; it was
syntax validation, not a production startup. Read-only `information_schema`
inspection confirmed the new tables/columns and recovery-attempt default zero in
the isolated PostgreSQL fixture. All seven existing POMs, Maven wrappers,
Dockerfiles and the main Compose configuration remain present.


The affected Auth verification after the signing-key diagnostic fix was run as:

```bash
(cd auth-service && bash ./mvnw -B \
  -s /workspace/.orderflow/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.orderflow/maven/repository verify)
```

Its evidence is `/workspace/.orderflow/logs/phase4-auth-final.log`.

The final affected Auth suite/build passed: 10 tests, no failures/errors/skips,
including the captured startup-output assertion. The seven-service integration
was then executed again, retaining every original assertion and the log-safety
check:

```bash
JAVA_HOME=/workspace/.orderflow/jdk PHASE4_INTEGRATION=1 \
  python3 -u scripts/checkout-integration.py
```

It exited **0**. Evidence: `/workspace/.orderflow/logs/phase4-integration-final.log`
and `/tmp/orderflow-checkout-it-zktyqzo5/`. All seven real jars started; Auth issued
the actual user token; PostgreSQL/Redis/Kafka and the local Stripe HTTP mock were
used. It passed missed-webhook terminal recovery, acknowledged payment/order
outbox delivery, no release of aged unresolved reservations, legacy order/payment
creation and fulfillment, bounded Kafka dead-letter retention, duplicate/concurrent
checkout and webhook scenarios, limited stock, frozen authoritative prices,
uncertain initialization recovery, and the absence of fixture client/API/signing/
webhook secrets from application logs. This is a mock-Stripe end-to-end lifecycle
check, **not** a live Stripe payment or production certification.

The full wrapper was not rerun after the isolated Auth correction: its unchanged
113 Order/Inventory/Product/Payment/Gateway tests, Discovery test, five-service
integration and packages had already passed; the affected Auth suite/package and
failed seven-service step were rerun successfully as shown above. The final unique
Java total remains **124 passed, zero failed/errors, zero skipped**. Historical
failures are preserved in the evidence and explained above, not counted as final
passing results or hidden by skipped assertions.

After the successful run stopped all seven Java processes, its generated cleanup
SQL was actually executed against only the isolated fixture database. Verification
confirmed removal of exactly eight fixture products, eight orders and one user,
with unrelated row counts preserved. All fixture IDs were absent afterward. The
verification used `psql -v ON_ERROR_STOP=1` with the generated SQL as input and
checked before/after counts plus UUID-specific absence; no database reset or
production deletion occurred. Evidence: `/workspace/.orderflow/logs/phase4-cleanup.log`.
The successful run's `fixtures.json` and `cleanup.sql` remain available for review;
reapplying that SQL is idempotent. PostgreSQL/Redis/Kafka test containers remain
available, while application processes started by the integration are stopped.

No required automated verification remains blocked. Still unverified: live Stripe
credentials/payment behavior, production application-image builds/full Compose
startup, deployed Eureka registration/routing, production TLS/workload identity,
and production broker replication/retention/operational alerting. Automatic safe
abandoned-reservation cancellation/expiry remains future work; pending payments
can retain stock. Unknown intent IDs, exhausted recovery, dead letters and legacy
ambiguous stock effects require the documented investigation/requeue process.
Real Auth registration/login/token issuance is now verified. Optional scheduler
environment settings must be injected into Order/Payment by the deployment when
using containers; the main Compose file uses application defaults.

Final Git verification: branch `feature/buy-now-checkout`, upstream
`origin/feature/buy-now-checkout`, HEAD
`f0389d0b668760f81e4d6759844d82948eac9782` unchanged. Working-tree implementation
changes remain uncommitted, with prior phases preserved. The index is empty; no
`.env` edit, branch change, merge, reset, commit or push was performed.
