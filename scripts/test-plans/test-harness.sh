#!/usr/bin/env bash
set -euo pipefail
set +x
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/test-plans/tests -v
python3 - <<'PY'
import ast
from pathlib import Path
for file in list(Path('scripts/test-plans').rglob('*.py'))+[Path('scripts/checkout-integration.py')]:
    ast.parse(file.read_text(),filename=str(file))
print('PYTHON_SYNTAX_OK')
PY
bash -n scripts/test-plans/final-buy-now.sh scripts/test-plans/test-harness.sh scripts/checkout-integration.sh scripts/test-phase3.sh scripts/test-phase4.sh
# Dry-run compiles only test helpers, validates artifacts and Compose configuration.
scripts/test-plans/final-buy-now.sh dry-run
# Direct Java unit calls: no server, Spring context, Docker startup or webhook.
PYTHONDONTWRITEBYTECODE=1 python3 - <<'PY'
from pathlib import Path
import subprocess,sys,tempfile
sys.path.insert(0,str(Path('scripts/test-plans').resolve()))
import phase5
snapshot=phase5.artifacts(phase5.Engine());directory=Path(snapshot['directory'])
with tempfile.TemporaryDirectory(prefix='phase5-agent-unit-') as temporary:
    cp=str(directory/'test-classes')+':'+str(directory/'payment-classes')+':'+str(directory/'libs/*')
    subprocess.run([str(phase5.JAVA/'javac'),'-proc:none','--release','21','-cp',cp,'-d',temporary,'scripts/test-plans/tests/Phase5AgentTest.java'],env=phase5.environment(),check=True,capture_output=True)
    environment=phase5.environment({'PHASE5_RUN_ID':'11111111-1111-4111-8111-111111111111','PHASE5_RUN_KEY':'unit-only-in-memory-key'})
    result=subprocess.run([str(phase5.JAVA/'java'),'-Dphase5.state='+temporary+'/mock.json','-cp',temporary+':'+cp,'Phase5AgentTest'],env=environment,capture_output=True,text=True,check=True)
    print(result.stdout.strip())
PY
