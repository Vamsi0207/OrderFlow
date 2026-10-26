#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Runs Phase 1/2 regressions, Auth/Discovery builds and the isolated final Buy Now scenario.
"$ROOT/scripts/test-phase2.sh" "$@"
for service in auth-service discovery-server; do
  (cd "$ROOT/$service" && bash ./mvnw -B "$@" verify)
done
"$ROOT/scripts/test-plans/final-buy-now.sh" start
