# Phase 5: final-test harness safety and isolation

Implemented on `feature/buy-now-checkout`. No end-to-end scenario was executed in
this phase. No application users/products/orders were created, no webhook was
invoked, no database cleanup was executed, and no containers were created/started/
stopped/deleted. Docker operations were metadata inspection and Compose parsing.
No commit/push, branch change or `.env` edit was performed. Existing Phase 1–4
application changes are preserved; no business logic was changed.

## Paths discovered and changed

The previously supplied final-test plans existed only under
`/workspace/.orderflow/test-plans/`; `scripts/test-plans/` and
`scripts/checkout-integration.sh` did not exist. The canonical implementation is
now in the repository:

- New `scripts/test-plans/phase5.py`: explicit controller, journal, reconciliation,
  ownership/cleanup planner, signal handling, redaction, pinned snapshots and CLI.
- New `scripts/test-plans/Phase5Agent.java`: loopback mock, durable non-secret mock
  state, proxy-free/redirect-free API transport; no production packaging.
- New `scripts/test-plans/final-buy-now.py`, `final-buy-now.sh`, `final-buy-now.md`:
  canonical safe entry points and complete operator procedure.
- New `scripts/test-plans/test-harness.sh`, `tests/test_phase5.py`, and
  `tests/Phase5AgentTest.java`: harness-only verification.
- Replaced `scripts/checkout-integration.py` with a compatibility delegate. Added
  `scripts/checkout-integration.sh`; no-argument execution is a dry-run.
- Updated `scripts/test-phase3.sh` and `scripts/test-phase4.sh` to invoke the
  isolated runner explicitly instead of the shared-infrastructure integration.
- Tightened the test-only
  `payment-service/src/test/java/com/orderflow/payment/integration/PaymentIntegrationApplication.java`:
  strict local URL, dummy-key guard and bounded SDK run-identity probe before Spring.
- Replaced the three old machine-local plan files with delegates/documentation
  pointing to the canonical repository implementation.
- Added this completion report. Application `src/main` files were not edited.

The previous in-memory patching of the old harness is retired. The listed final
scenario assertions are implemented explicitly, not weakened into unit mocks.
Older phase reports remain historical records; their old integration commands no
longer start a shared test implicitly.

## Safety behavior

Each run has a UUID, private journal, unique Compose project/database, its own
PostgreSQL/Redis/Kafka, internal-only network, no host port publications and no
external network. All Java services share the owned runner namespace and bind to
its loopback. Kafka topics and the hardcoded group name are scoped to a fresh
broker, so existing application events cannot be consumed.

The harness loads digest-pinned cached images and immutable byte-verified snapshots.
Payment's runtime classes are extracted from its pinned jar; its test launcher is
compiled fresh and hashed. It never loads mutable target/classes or trusts only
launcher existence/timestamps. Container entrypoint/command/environment/mounts,
project/run labels, namespace and liveness must match before requests. Actuator
readiness corroborates run/artifact identity; the mock SDK probe must corroborate
the run before business fixtures exist. Unexpected environment/entrypoint injection
fails closed. Local SDK pinning, dummy credentials and the internal network provide
independent protections against live Stripe traffic.

An explicit process environment allowlist and private empty Docker client config
exclude host Spring/JVM/proxy/credential configuration. The RPC client uses no
proxy and follows no redirects. Docker raw logging is disabled; only redacted
captured live output is written to private logs. RPC responses with secrets are
captured only in parent memory. Manifests reject credential fields/values and
contain no plaintext credentials. Docker runtime environments contain generated
test-only values; Docker administrators remain a trusted boundary.

Run IDs, operation correlations, resource IDs, status and checkpoints are saved
atomically. A fresh start is blocked by any incomplete run. Read-only inspect works
even while a controller holds its lock. Resume reconciles uncertain results against
the owned initially-empty private database and identity marker, then continues the
original keys/IDs. Matching names alone do not establish ownership.

Cleanup validates exact UUIDs and relational ownership, requires terminal verified
payments and terminal order/reservation states, stops every owned application,
then deletes fixed allowlisted rows in dependency order inside a transaction.
The transaction checks the marker and absence before COMMIT. Lost commit responses
are reconciled; idempotent cleanup does not assume rollback. Unknown/unresolved
resources are preserved. Cleanup and shutdown are attempted after ordinary failures;
one child failure does not skip the others. SIGINT/SIGTERM set a checkpointed stop
flag. SIGKILL recovery uses the durable journal and Docker identities, never saved
host PIDs. Containers/volumes are retained rather than pruned.

## Verification actually executed

Final reusable verification command, from the repository root:

```bash
./scripts/test-plans/test-harness.sh
```

It exited 0. Final unique results:

- **48 Python unittest tests passed**, zero failures/errors/skips. Coverage includes
  journal persistence/permissions, incomplete-run blocking, ownership rejection,
  lost responses, cleanup dependency order, idempotence/uncertain commits, unresolved
  payment preservation, independent shutdown, signal handling, read-only inspect,
  redaction across chunks, environment/command injection, artifact tampering and
  static checks for unsafe commands/dynamic execution.
- **17 direct Java helper unit checks passed**. Invalid mock endpoints are rejected
  before any SDK call; direct mock-method tests cover authentication, metadata,
  stable intent identity, mismatch rejection and non-secret durable state. These
  tests start no HTTP server or Spring application and create no application rows.
- Python AST syntax checks and shell `bash -n` checks passed.
- Static checks for broad/destructive infrastructure commands, shell execution,
  unsafe environment inheritance and credential disclosure passed.
- Harmless dry-run passed against the available cached images and real Compose
  parser. It showed a unique project/database, 11 private services, zero host ports,
  loopback application/mock endpoints and exact-ID terminal-only cleanup targets.
- Test-only Java launcher/agent compilation passed with annotation processing off.
- `git diff --check` passed; HEAD remains
  `f0389d0b668760f81e4d6759844d82948eac9782`; index remains unstaged.

The command was run during development as the suite expanded (34, 43, 45 and 47
Python cases before the final 48); totals do not double-count those repetitions.
No executed harness test failed. Source review corrected a fresh-broker health
check: successful Kafka topic listing can be empty, so readiness uses command
success rather than nonempty output; the added regression passed. Additional
review hardened namespace restarts, pre-COMMIT validation, actual child configuration
checks and safe continuation after already-settled mock intents.

Evidence: `/workspace/.orderflow/logs/phase5-final-verification.log` contains the
48-test result, `DRY_RUN_ONLY` and `PHASE5_AGENT_UNIT_OK checks=17`. Earlier
harness-only logs are retained in that directory. There was no pre-existing Python
harness test suite; the new suite exercises these previously untested controls.
No application Maven suites, full Buy Now scenario or live Stripe checks were run
in Phase 5, and the earlier 124 Java application tests are not claimed as new runs.

## Operator command and remaining limits

The single explicit command to start the final test is:

```bash
/workspace/OrderFlow/scripts/test-plans/final-buy-now.sh start
```

Read `scripts/test-plans/final-buy-now.md` for exact dry-run, inspect, resume and
cleanup commands, credentials policy, requests/results and refusal conditions.
No infrastructure prerequisite blocker was found by the dry-run in this environment.
Runtime application startup, actual container namespace behavior, SDK probing,
webhooks, Kafka fulfillment and real database cleanup are **not executed/certified
by this phase**. They are guarded and asserted when the operator explicitly starts
the final test. A managed engine that injects unexpected configuration will be
rejected, not silently tolerated.

An unresolved payment or missing ownership/key evidence requires inspection; the
harness does not invent failure, force-release stock, reset keys or bypass application
reconciliation rules. Resume is not a repair mechanism for application defects or
already-dead-lettered terminal events. Do not remove an incomplete runner container,
its data volumes, pinned artifacts or journal. Cleaned runs retain stopped containers,
volumes and evidence; there is deliberately no automatic pruning command. Host/Docker
administrator compromise is outside the isolation guarantee. Live Stripe and
production deployment remain unverified.
