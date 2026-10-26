#!/bin/bash -p
set -euo pipefail
set +x
exec "$(dirname "$0")/test-plans/final-buy-now.sh" "$@"
