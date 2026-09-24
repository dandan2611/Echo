"""Run serially against the dedicated local Redis/proxy; save evidence under build/."""
import json
import os
from pathlib import Path
import subprocess
import time

lab = Path(__file__).resolve().parent
root = lab.parent.parent
evidence = root / 'build' / 'placement-lock-lab'
evidence.mkdir(parents=True, exist_ok=True)
results = []
cases = []
for version in ['3.29.0', '3.32.0']:
    cases.append((version, 'baseline', False, 'fast'))
    for mode in ['unlock-lost', 'acquire-reply-lost']:
        cases.extend([(version, mode, False, 'fast')] * 2)
        cases.append((version, mode, True, 'fast'))
for mode in ['unlock-lost', 'acquire-reply-lost']:
    cases.append(('3.29.0', mode, False, 'default'))
cases.extend([
    ('3.29.0', 'acquire-reply-lost', False, 'default'),
    ('3.32.0', 'acquire-reply-lost', False, 'default'),
    ('3.29.0', 'acquire-reply-lost', True, 'default'),
])

for i, (version, mode, pause, timings) in enumerate(cases):
    name = f'{i:02d}-{version}-{mode}-pause{pause}-{timings}'
    command = ['cmd.exe', '/c', 'gradlew.bat'] if os.name == 'nt' else ['./gradlew']
    command += ['-I', str(lab / 'lab.init.gradle'), ':core:lockLab',
                f'-Dlab.mode={mode}', f'-Dlab.redisson={version}', f'-Dlab.pause={str(pause).lower()}',
                f'-Dlab.timings={timings}', '--offline', '--console=plain']
    started = time.monotonic()
    result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (evidence / f'{name}.log').write_text(result.stdout, encoding='utf-8')
    expected_red = mode != 'baseline' and not pause and not (mode == 'unlock-lost' and timings == 'default')
    correct = (result.returncode != 0 and 'BUG: empty lobby remains rejected' in result.stdout) if expected_red else (result.returncode == 0 and 'PASS no leaked lock' in result.stdout)
    row = dict(case=name, exit=result.returncode, expected_red=expected_red, matched=correct, seconds=round(time.monotonic()-started, 2))
    results.append(row)
    print(json.dumps(row), flush=True)
(evidence / 'results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
assert all(row['matched'] for row in results), 'Unexpected result; inspect case logs'
