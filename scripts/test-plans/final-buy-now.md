# Final Buy Now test — Phase 5 safety and isolation

The canonical files are now in `scripts/test-plans/`. The earlier machine-local
`/workspace/.orderflow/test-plans/` files and `scripts/checkout-integration.py`
delegate here. The old in-memory source rewriting/shared-infrastructure harness
has been removed. **No end-to-end run is executed by default.**

## Commands

From `/workspace/OrderFlow`, the safest preparation command is:

```bash
./scripts/test-plans/final-buy-now.sh dry-run
```

After reviewing that output, explicitly start the final test:

```bash
./scripts/test-plans/final-buy-now.sh start
```

The controller prints a unique run UUID on success or failure. For an existing run,
set `RUN_ID` to that exact UUID and use:

```bash
RUN_ID='replace-with-the-printed-run-UUID'
./scripts/test-plans/final-buy-now.sh inspect "$RUN_ID"
./scripts/test-plans/final-buy-now.sh resume "$RUN_ID"
./scripts/test-plans/final-buy-now.sh cleanup "$RUN_ID"
```

`inspect` reads the journal without starting containers or making API requests.
`resume` restarts/reconciles only that run and continues its original user/product
correlations, idempotency key and payment attempt. `cleanup` never starts
application services, creates business fixtures, or sends a webhook; it may restart
only the owned PostgreSQL container to reconcile and remove proven terminal
fixtures. A second cleanup of a verified cleaned run is a no-op. No command resets
a database, deletes a Docker volume, prunes containers, or resets Kafka offsets.

A global private controller lock prevents concurrent commands that could race.
`start` refuses to allocate a new run while any earlier run remains incomplete;
it prints the unresolved run IDs. Use inspect/resume/cleanup rather than deleting
a journal or choosing a different idempotency key.

## Prerequisites and dry-run boundaries

- Linux, Python 3.9+, Bash, Docker with Compose v2, and the local
  `unix:///var/run/docker.sock` endpoint. Other contexts/remote engines are ignored.
- Prepared Java 21 JDK at `/workspace/.orderflow/jdk`.
- Current executable jars for Auth, Discovery, Gateway, Order, Inventory, Payment
  and Product under their existing `target/` directories. Main-source freshness
  checks fail closed; no automatic build against an unspecified database occurs.
- Cached images `eclipse-temurin:21-jdk`, `postgres:17`, `redis:7-alpine`, and
  `apache/kafka:4.1.0`. No image pull or build is performed. The dry-run records the
  actual immutable image IDs; subsequent containers must match those IDs.
- Writable private directories below `/workspace/.orderflow/` for immutable
  artifact snapshots, the sanitized Docker client config, and run journals.

No production credentials are needed or accepted. The shell/Python process and
Docker CLI use an explicit environment allowlist. The controller creates a private
empty Docker client config, avoiding user proxy/auth configuration. Child JVM,
Spring and proxy environment cannot be inherited from the invoking shell.

Dry-run compiles test-only helpers, verifies every snapshot file hash, inspects
cached image metadata, validates the generated Compose specification with
`docker compose config --quiet`, and prints the proposed services, network,
internal ports and fixed cleanup allowlist. It allocates no actual run, creates or
starts no containers, opens no application HTTP connections, creates no fixtures,
and executes no database SQL. A missing image, stale jar, unavailable local Docker
socket, unsupported Compose configuration, or unsafe image environment stops it.
Provision the specific missing prerequisite outside the harness, then repeat
`dry-run`; do not relax isolation checks or point it at existing application data.

## Infrastructure and process ownership

Every run uses project `orderflow-buy-<32 hex run UUID>` and database
`of_<32 hex run UUID>`. It creates its own:

- PostgreSQL container and named `pgdata` volume;
- Redis instance;
- Kafka broker and named `kafkadata` volume;
- `runner` container and seven Java service containers;
- private Docker bridge network with `internal: true`.

There are **no published host ports**, no external networks, no host networking,
no Docker socket inside containers, and no reused application data volumes.
The applications and test mock share only the owned runner network namespace.
All application listeners bind `127.0.0.1` inside it. Internal ports are Gateway
19080, Auth 19081, Product 19082, Order 19083, Inventory 19084, Payment 19085,
Discovery 19761 and mock 18086. PostgreSQL/Redis/Kafka are reachable only on the
new private network as `postgres:5432`, `redis:6379`, and `kafka:9092`.
These ports do not reserve or conflict with host listeners.

Kafka topics and the application's hardcoded `order-service` consumer group are
scoped to this fresh broker; no events from an existing broker can be consumed.
Hibernate schema update runs only against the new database. The database must
initially contain no public tables; the controller writes and subsequently checks
its own UUID identity marker before adopting or deleting any business resource.

Containers must have the exact run/project/service labels, pinned image,
entrypoint, command, environment, read-only artifact mount and expected namespace.
The runner network must be internal and belong to the run. Each API call checks
that its intended service container is alive; readiness also verifies each
Actuator service's run/artifact attestation. Discovery has no Actuator dependency,
so its UI is checked after container identity/liveness validation. The controller
never kills a saved/reused host PID or uses `pkill`; shutdown addresses only
verified container IDs. Log observers use their current Popen handles.

A managed Docker engine that injects an unexpected entrypoint or environment will
be rejected before fixture creation. A successful configuration dry-run is not a
claim that such runtime validation or application startup has been executed.

## Stripe and webhook verification

Payment runs the **verified executable jar's extracted classes**, not mutable
`payment-service/target/classes`. All jar files, extracted runtime classes/libraries,
and freshly compiled test launcher/agent bytecode are hashed in an immutable
snapshot. The run manifest pins these hashes. Changes cause startup/resume to
abort; timestamps alone are not accepted as proof of the loaded artifact.

The test-only `PaymentIntegrationApplication` requires exactly HTTP on
`127.0.0.1:18086`, with no credentials, alternate path, query or fragment. It sets
Stripe's API base before Spring starts, requires a dummy test API key, and performs
a bounded real Stripe SDK probe against the mock. The probe must corroborate the
run UUID. Fixture creation is blocked until that probe and all service readiness
checks pass. No code falls back to live Stripe, and the internal-only network has
no Internet egress. The launcher is never added to a production jar.

The agent's HTTP transport explicitly disables proxies and redirects. API RPC
responses, including JWTs/client secrets, travel through a captured Docker exec
pipe into controller memory, never a log or terminal.

Success simulation changes only the run's durable mock intent, then signs the
exact webhook bytes with its generated test-only webhook key. It sends the event
through the actual Gateway `POST /api/v1/payments/webhook` route. The real Payment
controller calls `Webhook.constructEvent`, retrieves the current intent through
the actual Stripe SDK, commits its outcome/outbox, and publishes to real Kafka.
The real Order consumer verifies payment proof and performs the actual inventory
effect. Invalid signatures must still return 400. Background recovery is disabled
for these test processes so it cannot substitute for this webhook path.

## Credentials, journals and logs

Runs live in `/workspace/.orderflow/buy-now-runs/<run UUID>/`, mode 0700.
`manifest.json`, `compose.json`, logs and generated SQL use mode 0600. Atomic
manifest replacement includes file/directory fsync. The journal records status,
last completed step, pending operation correlations, exact resource UUIDs,
container/image/PID identities, and shutdown failures.

Before an uncertain request, record the unique email/product correlation,
checkout key or webhook event UUID. Record every returned user/product/inventory/
checkout/order/reservation/payment identifier immediately. Reconciliation also
requires the exclusive initially empty private database, its run marker, recorded
request and linked IDs: a matching name or timestamp alone is insufficient.

No plaintext passwords, JWTs, API keys, webhook keys or client secrets are stored
in manifests. A random test-only root key lives in the owned runner's Docker
runtime environment; it derives stable test credentials for resume. Docker daemon
administrators can inspect container environments: the local daemon is a trusted
boundary. Do not remove the runner container or alter its environment while the
run is incomplete. If its key or ownership evidence is lost after fixtures exist,
resume fails closed rather than resetting credentials or payment identities.
The mock persists only non-secret intent identity/amount/metadata/status, never
its generated client secret, in the private `state/mock.json` file.

Docker raw application logging is disabled (`logging.driver: none`). Live output
is captured through an owned attach observer and redacted **before** writing host
logs. Known keys/passwords plus generic JWT, credential-field and generated-client-
secret patterns are masked. Whole lines are buffered so split values cannot leak;
oversized lines are suppressed rather than partially persisted. Failure reports
contain only controlled codes and run IDs, not response bodies or exception traces.
Early output before the observer attaches is discarded rather than stored raw.
Shell tracing is disabled. Do not enable external Docker/JVM debug capture.

## Expected final scenario (not executed in Phase 5)

1. Real Gateway/Auth `POST /api/v1/auth/register`, body firstName, lastName, unique
   email and generated password; then `POST /api/v1/auth/login` with email/password.
   Use only its issued `accessToken` and `tokenType`; never fabricate a JWT.
2. Through the real Gateway Product API, create two unique products with bodies
   `{"name":"<run correlation>","price":12.50,"stock":10,"category":"test"}`
   and price 20.00. Create Inventory through `POST /api/v1/inventory` with
   `{"productId":"<returned UUID>","availableQuantity":10}`. Expected 201.
3. `POST /api/v1/buy`, Authorization Bearer from Auth and fresh recorded
   `Idempotency-Key`, body
   `{"items":[{"productId":"<first UUID>","quantity":2},{"productId":"<second UUID>","quantity":1}]}`.
   For a fresh run, require 201, no-store, PENDING order/payment, READY initialization,
   nonempty masked client secret, 45.00 INR, and correct stored unit-price snapshots.
   Current prices are independently read from `/api/v1/products/{id}/checkout-price`.
4. Five concurrent same-key/same-body calls return 201/202 and retain exactly one
   attempt/order/reservation/payment/intent. Same key with quantity 3 instead
   returns 409 `IDEMPOTENCY_CONFLICT`; no stock or identity change.
5. Invalid signature returns 400. Send the same signed local success webhook twice.
   Require persisted CONFIRMED order, COMPLETED and terminal-verified payment,
   DEDUCTED reservation, available/reserved stock 8/0 and 9/0.
6. Terminal Buy replay returns 200, same identities and no client secret. Payment
   GET `/api/v1/payments/order/{orderId}` reports COMPLETED without a client secret.
7. Validate ownership, stop owned applications, execute exact-ID cleanup in a
   transaction, verify absence before/after commit, and stop owned infrastructure.
   Success prints `RUN_CLEANED <UUID> outcome=passed`. Assertions are explicit
   checks, not removable Python asserts; optimized Python invocation is rejected.

On resume, only already-proven checkpoints may differ: an already confirmed Buy
returns 200, and a previously verified initialization may no longer expose a client
secret after mock settlement. A fresh test still requires the full 201/READY/secret
contract; resume does not replace it with a weaker assertion.

## Cleanup and interruption recovery

Cleanup is deliberately data-scoped, not infrastructure destruction. It validates
every UUID and all database ownership/linkage before SQL. Its fixed allowlist is:
`order_notifications`, `legacy_payment_effects`, `checkout_attempts`, `payments`,
`stock_reservation_items`, `stock_reservations`, `order_items`, `orders`,
`inventories`, `products`, `users`. Child rows are deleted before parents, using
only exact recorded order/row IDs. No DELETE lacks a UUID predicate. The transaction
checks the run marker and verifies table emptiness before COMMIT. The marker/schema
remain as evidence; there is no reset. SQL runs only in the run's private database.

Ordinary assertion failures and recoverable exceptions attempt reconciliation and
best-effort cleanup, followed by independent shutdown attempts for every owned
child. If there is an unresolved payment, pending order or reserved inventory,
**no business deletion occurs**: retain evidence and reservation, stop the owned
applications, mark needs_recovery, and refuse a new start. Do not manually release
stock, reset payment flags or invent a terminal outcome to get cleanup to pass.
Resume continues the original legitimate flow against the original durable mock.

SIGINT/SIGTERM set a stop flag; they do not interrupt the spawn-and-record critical
section. The controller stops at a bounded-operation checkpoint, journals the
failure and attempts safe cleanup/shutdown. Allow that shutdown to complete.
SIGKILL cannot be caught: containers may remain running, but the manifest's
pre-recorded create/start intent and exact run labels/images permit subsequent
adoption/reconciliation. It never guesses ownership from a PID or container name.
If the runner namespace was restarted, applications are stopped/restarted into
the correct namespace before any fixture operation resumes.

For a lost cleanup response, the journal records `cleanup_uncertain`; subsequent
reconciliation checks actual row absence rather than assuming COMMIT or rollback.
A successful cleanup is idempotent, including an interrupted post-COMMIT response.
Unrelated/unknown rows, ownership mismatch, missing key after fixture creation,
changed artifact/Compose hashes, unexpected container configuration, and failure
to stop a child all cause refusal. Preserve the journal and inspect the fixed
failure code. Application defects, expired Payment reconciliation windows and
terminal events already dead-lettered may need investigation; the harness does
not force application state or blindly replay arbitrary broker messages.

Cleaned runs retain their private journal/logs, stopped containers and volumes for
review; no automatic volume/container pruning is provided. This consumes disk until
an operator separately retires proven-owned infrastructure. Never remove an
incomplete run's evidence. The test command does not touch the old
`orderflow-phase1-test` project or its data.

## Harness-only verification

```bash
./scripts/test-plans/test-harness.sh
```

This runs Python unit/static/syntax checks, shell syntax checks, the harmless
Compose dry-run, and direct Java unit checks for the agent/launcher guards. It
never starts an HTTP server or Spring app, creates containers or application
fixtures, invokes a webhook, or executes database cleanup. Synthetic rows and mock
intent files in unit-test temporary directories are not application records.

Phase 5 verification and limitations are recorded in `docs/buy-now-phase5.md`.
The older `test-phase3.sh`/`test-phase4.sh` now invoke this isolated final runner
explicitly; their preceding Maven regression suites still have their original
separate Phase 1 unit-test fixtures. Use the canonical command above for only the
final Buy Now test. No full end-to-end or live Stripe readiness is claimed here.
