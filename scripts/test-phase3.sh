#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
"$ROOT/scripts/test-phase2.sh" "$@"
"$ROOT/scripts/test-plans/final-buy-now.sh" start
