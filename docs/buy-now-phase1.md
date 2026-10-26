# Buy Now Phase 1: checkout foundation

This phase adds reusable persistence and reservation primitives, not a Buy Now
controller or payment orchestrator. There is no `POST /api/v1/buy` yet.

## Order Service

`CheckoutAttemptService.begin(authenticatedUserId, key, CheckoutRequest)` commits
intent in its own READ COMMITTED transaction **before** any future orchestrator
performs remote side effects. The user ID must come from authenticated identity,
not a client body. Keys are case-sensitive, 1–128 characters, and not trimmed.

The v1 canonical payload contains only product IDs and positive integer quantities.
Repeated product lines are summed with overflow checks and sorted by UUID; input
item order and split lines are immaterial. It excludes catalog prices and totals.
When adding future intent fields (currency, shipping, etc.), include them in a new
version of the canonical form and preserve replay compatibility.

The database enforces `UNIQUE(user_id, idempotency_key)`. A PostgreSQL
`INSERT ... ON CONFLICT DO NOTHING` arbitrates simultaneous requests, waiting for
the winner to commit or roll back. A second statement reads the committed row.
This avoids catching uniqueness exceptions inside a rollback-only transaction.
Same intent returns the same attempt; changed intent throws
`CheckoutConflictException` (HTTP 409 mapping is declared for future API use).

Attempts retain normalized intent, its SHA-256 hash, order ID when assigned,
workflow status, retry count, last error, next retry time, timestamps and an
optimistic version. Future recovery code must save the order association and phase
transitions durably, handle optimistic conflicts, and reconcile remote state.
**Returning an existing attempt does not grant exclusive orchestration ownership.**
Leasing/claiming a recovery worker and order-creation idempotency belong to the
orchestration phase; do not have every retry independently create an order.

Existing order creation now obtains current prices through Product Service's
uncached endpoint. All product prices are validated before the first reservation;
server-calculated totals and unit-price snapshots remain persisted in order items.
No client prices or totals are accepted by the order request DTO.

## Inventory Service: new order-scoped contract

Use a stable, already durable UUID: an order ID or checkout-attempt ID. Keep the
same identity throughout the lifecycle and across ambiguous network failures.
These are service primitives: the calling orchestration layer must validate user
ownership before issuing mutations. This phase does not add ownership metadata
or gateway authorization policy to inventory.

```
PUT  /api/v1/inventory/reservations/{orderId}
{"items":[{"productId":"<uuid>","quantity":2}, ...]}

GET  /api/v1/inventory/reservations/{orderId}
POST /api/v1/inventory/reservations/{orderId}/release
POST /api/v1/inventory/reservations/{orderId}/deduct
```

Responses contain `orderId`, `state` and normalized `items` (a product-to-quantity
map). Successful calls return 200. Missing reservation identity returns 404;
different items for an existing identity or incompatible terminal transitions
return 409. Invalid request fields return 400; insufficient stock retains the
existing inventory API's 400 response. Missing inventory returns 404.

The reservation primary key arbitrates absent-row insert races. The header, item
rows, and every inventory change are committed in one local database transaction.
Each reservation header is locked first; then all product rows are locked using
consistent Java UUID ordering. All inventory writers, including legacy stock
mutations, acquire the same product-row locks. No read-before-write stock decision
is made without a lock. An insufficient or missing item rolls the entire new
reservation back; there is no partially committed header or stock change.

State machine:

```
RESERVED -> RELEASED
RESERVED -> DEDUCTED
```

Replaying the same transition has no further stock effect. The other terminal
transition returns 409. Repeating PUT with identical items returns the current
state, including terminal states, and never reopens a reservation. Clients **must
check the returned state**, rather than treating every 200 as newly RESERVED.
Duplicate product lines are combined before locking or checking stock.

### Ambiguous timeouts and recovery

If PUT times out, retry PUT with **the same identity and items**, or GET its state.
Never generate another identity or call anonymous release as compensation. A 404
can race a still-running PUT and is not proof that it will never commit. Continue
reconciling/retrying that identity; release only that identified reservation when
appropriate. Unknown release/deduct returns 404 without touching stock. A delayed
reserve can therefore leave a reservation needing recovery, but cannot cause a
duplicate stock decrement through retries. No expiry scheduler is added.

### Legacy compatibility

Existing `/reserve`, `/release`, `/deduct`, availability and inventory CRUD routes
and DTOs remain. They now lock rows for stock mutations, and anonymous release or
deduction can consume **only legacy reserved quantities**, never stock held by a
scoped reservation. Inventory with any reserved quantity cannot be deleted (409).
An admin update still sets the available quantity as before; treat that as an
explicit stock correction, not an ordinary purchase.

Legacy quantity-only calls carry no identity, so identical calls cannot be
distinguished from distinct operations. They remain non-idempotent and must not be
blindly retried. Existing order creation and its payment-event consumer still use
these legacy calls. Their multi-line sequence is not a distributed transaction;
order rollback cannot undo Inventory Service commits. Full order integration,
partial-failure compensation and crash recovery remain later orchestration work.
New Buy Now code must use the scoped batch contract, not the legacy endpoints.
No Stripe integration or Kafka architecture was changed.

## Product Service

`GET /api/v1/products/{id}/checkout-price` returns the existing product-info shape,
but reads PostgreSQL directly in a read-only transaction and never calls a cached
service method. It rejects nonexistent products and nonpositive prices. Catalog
`stock` is metadata; Inventory Service is authoritative for reservable quantities.
Normal `products` and `productInfo` caching and eviction behavior is unchanged.
Checkout price is a snapshot at lookup time, not a distributed lock against later
catalog edits. The accepted snapshot is persisted in the order.

## Schema and deployment

Affected services use Java 21 / Spring Boot 3.5.5 / Spring Cloud 2025.0.0.
The repository has no Flyway/Liquibase conventions: it uses Hibernate
`spring.jpa.hibernate.ddl-auto=update`. This phase retains that mechanism with
additive entity mappings; no manual database operation or data recreation is
required. PostgreSQL is required for the native `ON CONFLICT` statements.

New tables:

- `checkout_attempts`: generated UUID primary key, unique `(user_id,
  idempotency_key)`, index `(status, next_retry_at)`, immutable intent fields,
  recovery metadata, version and timestamps.
- `stock_reservations`: durable `order_id` UUID primary key, state, version,
  timestamps. The identity is cross-service, so there is no foreign key into
  Order Service's database.
- `stock_reservation_items`: foreign key to reservation header, unique
  `(order_id, product_id)`, product index and positive application-validated quantity.

Existing order, order-item and inventory data is retained. Deploy Product Service
with its new checkout-price route before the updated Order Service. Deploy Inventory
Service with the new tables before using new scoped requests. Do not use `create` or
`create-drop` against a shared database. Sites that disable Hibernate schema
updates must put these additive definitions through their own migration process
before deploying the code; this repository currently has no such process.

## Reproducible verification

Requirements: Java 21 **JDK**, Docker/Compose, repository Maven wrappers and normal
Maven Central access. From the repository root:

```bash
scripts/test-phase1.sh
# If your environment needs explicit Maven settings/cache:
scripts/test-phase1.sh -s /path/to/settings.xml -Dmaven.repo.local=/path/to/cache
```

The script starts only project `orderflow-phase1-test`, with PostgreSQL 17 in
**tmpfs**, database `orderflow_phase1`, and Redis 7 on loopback ports 55432/56379.
It does not attach development/production volumes. Override `PHASE1_POSTGRES_PORT`
and `PHASE1_REDIS_PORT` together with the script if these ports are occupied.
Local PostgreSQL uses trust authentication solely for this isolated test fixture.
The script executes each affected service's Maven `verify`: unit tests, existing
context-load regression tests, PostgreSQL concurrency/persistence tests, real
Redis stale-cache tests, compilation and packaging. Discovery and Kafka listeners
are disabled for these test contexts; payment events and Stripe are outside scope.
The order persistence test mocks remote clients/event publication while exercising
real PostgreSQL persistence. Tests do not silently skip missing PostgreSQL/Redis.

After inspecting results, discard **only this test fixture** if desired:

```bash
docker compose -p orderflow-phase1-test -f compose.phase1-test.yml down
```

The test database is disposable and disappears with its container. Concurrency
tests use independent threads, transactions and connections, not in-memory locks.
Real database constraints, atomic rollback, terminal-state races, legacy/scoped
coexistence, durable attempt replay and optimistic conflicts are exercised.

## File manifest

Modified files:

- `inventory-service/src/main/java/com/orderflow/inventory/exception/GlobalExceptionHandler.java`
- `inventory-service/src/main/java/com/orderflow/inventory/repository/InventoryRepository.java`
- `inventory-service/src/main/java/com/orderflow/inventory/service/InventoryService.java`
- `order-service/src/main/java/com/orderflow/order/client/ProductClient.java`
- `order-service/src/main/java/com/orderflow/order/service/OrderService.java`
- `product-service/src/main/java/com/orderflow/product/controller/ProductController.java`
- `product-service/src/main/java/com/orderflow/product/service/ProductService.java`

Added files:

- `compose.phase1-test.yml`
- `docs/buy-now-phase1.md`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/ReservationConflictException.java`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/ReservationNotFoundException.java`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/ReservationRequest.java`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/ReservationResponse.java`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/StockReservation.java`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/StockReservationController.java`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/StockReservationRepository.java`
- `inventory-service/src/main/java/com/orderflow/inventory/reservation/StockReservationService.java`
- `inventory-service/src/test/java/com/orderflow/inventory/reservation/StockReservationControllerTest.java`
- `inventory-service/src/test/java/com/orderflow/inventory/reservation/StockReservationTest.java`
- `order-service/src/main/java/com/orderflow/order/checkout/CheckoutAttempt.java`
- `order-service/src/main/java/com/orderflow/order/checkout/CheckoutAttemptRepository.java`
- `order-service/src/main/java/com/orderflow/order/checkout/CheckoutAttemptService.java`
- `order-service/src/main/java/com/orderflow/order/checkout/CheckoutConflictException.java`
- `order-service/src/main/java/com/orderflow/order/checkout/CheckoutPayload.java`
- `order-service/src/main/java/com/orderflow/order/checkout/CheckoutRequest.java`
- `order-service/src/test/java/com/orderflow/order/checkout/CheckoutAttemptTest.java`
- `order-service/src/test/java/com/orderflow/order/client/ProductClientTest.java`
- `order-service/src/test/java/com/orderflow/order/service/OrderPricingPersistenceTest.java`
- `order-service/src/test/java/com/orderflow/order/service/OrderPricingTest.java`
- `product-service/src/test/java/com/orderflow/product/CheckoutPricingTest.java`
- `scripts/test-phase1.sh`

## Verification completed for this change

The existing context-load tests/builds were run first for Order, Inventory and
Product (one test each, all passed). The expanded suite's final results were:

| Service | Tests | Failures/errors | Skipped | Maven verify/package |
| --- | ---: | ---: | ---: | --- |
| Order | 13 | 0 | 0 | passed |
| Inventory | 18 | 0 | 0 | passed |
| Product | 4 | 0 | 0 | passed |
| Total | 35 | 0 | 0 | all three passed |

Actual full-suite invocation in the prepared cloud environment:

```bash
export JAVA_HOME=/workspace/.orderflow/jdk
export MAVEN_USER_HOME=/workspace/.orderflow/maven
export MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
scripts/test-phase1.sh -s /workspace/.orderflow/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.orderflow/maven/repository
```

The script invokes `verify` for every affected service with an isolated PostgreSQL
URL and Redis port, discovery disabled, and Kafka listener startup disabled. The
Order Service was also reverified after its final import cleanup using the same
PostgreSQL URL and Maven configuration. There were no implementation test failures.
Review hardening included explicit READ COMMITTED insert transactions and flushing
JPA changes before the legacy/scoped balance check; dedicated regressions passed.

Packaged Inventory and Product jars were additionally started on ports 18084/18082
against the isolated fixtures. Actual HTTP requests verified health, product
creation and authoritative price lookup, stock creation, reservation retry without
a second decrement, payload-conflict 409, reserved-stock deletion 409, repeated
release and deduction, conflicting terminal transition 409, unknown identity 404,
and insufficient-stock rollback (400 followed by reservation GET 404). Temporary
product/inventory fixtures were removed, and only the two smoke-test processes
were stopped. The smoke runner used `python3 -u /tmp/phase1-http-smoke.py`; it is a
local verification helper, not part of the repository test contract.

`git diff --check`, `bash -n scripts/test-phase1.sh`, and test Compose configuration
validation passed. Database table inspection confirmed the generated constraints
and indexes. Machine-local evidence is in `/workspace/.orderflow/logs/`:
`phase1-final-verification.log`, `phase1-order-final.log`,
`phase1-http-smoke.log`, and the baseline service logs. Maven XML result files are
in each affected service's `target/surefire-reports/`.

No required Phase 1 check remains blocked. Full Buy Now orchestration, Stripe,
gateway webhook policy, recovery worker ownership/scheduling and end-to-end
payment/event flows were not implemented or validated. This is a verified
foundation for the next phase, not a complete production checkout flow. In
particular, legacy order reservations still need migration/compensation as
explained above. No commit or push was performed.
