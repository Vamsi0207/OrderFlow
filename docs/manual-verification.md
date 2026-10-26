# Manual application verification

Use Postman, a browser, or individual HTTP requests you send yourself. There is no
automated test runner, local Stripe mock, or end-to-end harness in this checkout.
The scenarios below describe expected behavior; they have not been executed as
part of the test-removal change. Compilation and packaging do not prove runtime
behavior.

## Build and start locally

Use a Java 21 JDK. From the repository root, package each service:

```bash
for service in auth-service discovery-server gateway product-service inventory-service order-service payment-service; do
  (cd "$service" && bash ./mvnw -B -Dmaven.test.skip=true clean package) || break
done
```

Each POM also sets `maven.test.skip=true` by default. It disables test resource
copying, test compilation, and test execution while retaining production compilation
and Spring Boot repackaging. The executable JAR is
`<service>/target/<service>-0.0.1-SNAPSHOT.jar`. Maven wrappers, compiler/Lombok
configuration and the Spring Boot plugin remain available.

For manual runtime verification, use a local development environment and a Stripe
sandbox/test-mode account. Provide `DB_PASSWORD`, a sufficiently long `JWT_SECRET`
shared by Auth/Gateway/Order/Inventory/Payment, `STRIPE_SECRET_KEY`, and
`STRIPE_WEBHOOK_SECRET` through private environment settings. Do not publish
passwords, JWTs, client secrets, API keys or exported request collections containing
them. Set the webhook signing secret for the actual Stripe endpoint or forwarding
session you use; there is no repository-provided mock secret.

When you choose to start the application, the normal deployment command is:

```bash
docker compose up --build -d
docker compose ps
```

This guide does not start it for you. Compose includes PostgreSQL, Redis, Kafka
and the seven services. Existing named data volumes are retained. The PostgreSQL
initialization script creates the five application databases only on initial
database provisioning; it does not migrate an existing volume. Do not reset a
database to fix a startup issue. Compose dependency ordering is not a readiness
guarantee: inspect health and registration before sending business requests.

| Compose service | Container | Local port / observation |
| --- | --- | --- |
| discovery-server | orderflow-discovery-server | 8761, browser UI `/` |
| gateway | orderflow-gateway | 8080, `/actuator/health` |
| auth-service | orderflow-auth-service | 8081, `/actuator/health` |
| product-service | orderflow-product-service | 8082, `/actuator/health` |
| order-service | orderflow-order-service | 8083, `/actuator/health` |
| inventory-service | orderflow-inventory-service | 8084, `/actuator/health` |
| payment-service | orderflow-payment-service | 8085, `/actuator/health` |
| postgres | orderflow-postgres | 5432 |
| kafka | orderflow-kafka | 9092; advertised address `kafka:9092` is for containers |
| redis | orderflow-redis | no published host port |

Inspect Gateway's `/actuator/health` response and configured security rules to
determine whether authentication is required. Supply authentication where the
configured rules require it. Expect
`UP` from available Actuator health endpoints and registrations in the Discovery
UI. Discovery does not include Actuator. Keep direct service ports within your
trusted local environment: some legacy APIs rely on Gateway identity headers.

## Auth and basic APIs

Set your HTTP client's base URL to `http://localhost:8080`. Use JSON bodies.

1. Register with `POST /api/v1/auth/register`:
   `{"firstName":"Manual","lastName":"Reviewer","email":"<unique email>","password":"<private password of at least 8 characters>"}`.
   Expect 201; record your returned user ID privately.
2. Login with `POST /api/v1/auth/login`:
   `{"email":"<same email>","password":"<same private password>"}`.
   Expect 200. Store the returned `accessToken` in a private client variable and
   use `Authorization: Bearer <accessToken>` on protected requests. Do not invent
   a JWT or supply identity headers as authentication.
3. Check `GET /api/v1/auth/me`. Confirm it represents your user. Independently
   check an invalid password, duplicate registration, and a protected request
   without a token; record their actual responses.
4. Create a uniquely named product using `POST /api/v1/products`:
   `{"name":"<unique manual product>","price":12.50,"stock":10,"category":"manual"}`.
   Expect 201. Save the product UUID. Inspect `GET /api/v1/products/{id}`,
   `GET /api/v1/products/{id}/info`, and
   `GET /api/v1/products/{id}/checkout-price`. The last route reads the current
   database price without Redis caching.
5. Create stock using `POST /api/v1/inventory`:
   `{"productId":"<product UUID>","availableQuantity":10}`. Expect 201.
   Inspect `GET /api/v1/inventory/{productId}`. Product `stock` is catalog
   metadata; Inventory's available/reserved quantities control purchases.
6. On a separate product you created, manually inspect Product update/read/delete
   and Inventory update/read/delete through their existing `PUT`/`DELETE` routes.
   Inventory updates use the same body as creation. Check catalog cache behavior
   after an update and confirm that checkout prices reflect the current database.
   Do not delete products or stock involved in an unresolved checkout.

## Buy Now and idempotency

Choose a fresh `Idempotency-Key` in your client and save it with the request.
Send `POST /api/v1/buy` with your actual user Bearer token and:

```json
{"items":[{"productId":"<your product UUID>","quantity":2}]}
```

For price 12.50 and sufficient stock, expect 201 and `Cache-Control: no-store`,
with one `checkoutAttemptId`, `orderId`, `orderStatus: PENDING`, totalAmount 25.00,
currency INR, and item unitPrice 12.50. Payment should have a stable ID,
status PENDING, initializationState READY, and an active intent's clientSecret.
Keep that secret private. `workflowStatus: COMPLETED` means initialization
completed, not that payment succeeded.

Manually repeat the identical request with the same key. Confirm stable attempt,
order, reservation and payment identities and no additional stock decrement.
An active concurrent request or uncertain initialization may return 202. Use the
same key and payload when retrying. Change the quantity with the same key and
expect 409 `IDEMPOTENCY_CONFLICT`. With a separate fresh key, a request exceeding
available inventory should return 409 without a payment; it can retain a PENDING
order/attempt for a later retry. Missing products return 404; invalid quantities
return 400. Retain IDs of unsuccessful attempts as well as successful ones.

## Stripe and eventual fulfillment

Payment requires an external Stripe account; the deleted harness supplied the
local mock. To check payment manually, use Stripe's supported sandbox/test-mode
client flow to confirm the **existing** intent from your checkout. This repository
has no storefront payment UI. Configure a reachable webhook endpoint or a trusted
Stripe forwarding session for `POST /api/v1/payments/webhook` through Gateway.
Do not bypass signature verification or manufacture payment state in the database.

The real webhook controller verifies `Stripe-Signature` and retrieves the current
intent with the Stripe SDK. Only corroborated full success completes payment;
definitive cancellation fails payment. `requires_payment_method`, action required,
processing and network uncertainty remain unresolved and retain reserved stock.
An invalid signature should return 400. Manually resend the same authentic event
using Stripe's event delivery tooling to inspect duplicate handling.

Inspect `GET /api/v1/orders/{orderId}`,
`GET /api/v1/payments/order/{orderId}`, `GET /api/v1/payments/{paymentId}` and
`GET /api/v1/inventory/{productId}` with the owner's token. After successful
payment and Kafka delivery, expect CONFIRMED order, COMPLETED payment,
DEDUCTED reservation, available stock 8 and reserved stock 0. Payment reads and a
terminal Buy replay must not disclose clientSecret. Replaying Buy after order
confirmation should return 200 with the original IDs. A second account must not
read the first account's payment. Do not initialize another payment to inspect one.

For legacy order creation, independently send `POST /api/v1/orders` with the same
items-only body shape, then `POST /api/v1/payments` with
`{"orderId":"<legacy order UUID>"}`. Use separate manual fixtures. Client amounts
are ignored; payment amount, currency and owner come from the persisted order.
Legacy quantity-only stock mutations are not safe to blindly retry after a timeout.

## Persistence, recovery and cleanup

The runtime schema uses the existing Hibernate `ddl-auto=update` convention, not a
new migration framework. Keep `docker/postgres/init/01-create-databases.sql` and
all entity mappings. Buy Now uses `checkout_attempts`, `orders`, `order_items`,
`stock_reservations`, `stock_reservation_items`, `inventories`, and `payments`.
`order_notifications` and payment-row pending-event fields provide durable
notification recovery; `legacy_payment_effects` guards ambiguous legacy mutations.
Where API observations are insufficient, inspect only your exact UUIDs using
read-only queries in the owning application database. A database read does not
replace the application or Stripe as the authority for a payment outcome.

Order and Payment recovery workers default on with 30-second scans, bounded
batches and retry budgets. Properties are `orderflow.recovery.enabled` and
`orderflow.recovery.delay-ms`. Payment polls known intent IDs and retries pending
notifications; it does not create replacement intents. Kafka uses `order.created`,
`payment.completed`, `payment.failed` and payment `.DLT` topics. Examine retained
dead letters and exhausted recovery records individually before any operator
action. Broker delivery is at-least-once, so duplicate events are expected.

There is no automatic reservation expiry. Unknown payment outcomes must not
release inventory merely because they are old. Checkout retries require the same
user/key/payload and a valid user token. Unknown intent IDs older than the 23-hour
safe initialization window require investigation. Never reset keys, delete an
unresolved payment, force a terminal status, or replay arbitrary broker messages.

Record each fixture you create. Use existing delete APIs only for independent
catalog/stock fixtures that are safe to remove. There is no public order/payment/
user deletion API or automated cleanup replacement supplied here. Retain related
checkout evidence until its outcome is resolved; any later database cleanup must
be reviewed for exact UUID ownership and referential dependencies. Do not reset
databases or remove named volumes. Store manual observations separately and mark
each scenario unverified until you actually perform it.
