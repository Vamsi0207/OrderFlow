#!/bin/bash -p
set -euo pipefail
set +x
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
umask 077
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
# -I rejects inherited Python customization; add only the reviewed local module path.
exec /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  LANG=C.UTF-8 HOME="$HOME" python3 -I -B -c \
  'import runpy,sys; sys.path.insert(0,sys.argv[1]); sys.argv=sys.argv[2:]; runpy.run_path(sys.argv[0],run_name="__main__")' \
  "$ROOT/scripts/test-plans" "$ROOT/scripts/test-plans/final-buy-now.py" "$@"
