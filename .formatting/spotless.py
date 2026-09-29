"""Run the isolated formatter using this repository's Gradle wrapper."""

import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
command = [str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
command += ["-p", str(root / ".formatting"), "spotlessApply", "--console=plain"]

# Pass file selection through Gradle's project-property environment convention,
# not the .bat command line (cmd.exe limits it to 8191 characters). Bound each
# value as well, for Windows' environment-variable and Unix argument limits.
batches = []
batch = []
length = 0
for filename in sys.argv[1:]:
    path = str((root / filename).resolve())
    if batch and length + len(path) + 1 > 24000:
        batches.append(batch)
        batch = []
        length = 0
    batch.append(path)
    length += len(path) + 1
if batch:
    batches.append(batch)

for batch in batches or [[]]:
    env = os.environ.copy()
    env.pop("ORG_GRADLE_PROJECT_spotlessIdeHook", None)
    if batch:
        env["ORG_GRADLE_PROJECT_spotlessIdeHook"] = ",".join(batch)
    result = subprocess.call(command, cwd=root, env=env)
    if result:
        raise SystemExit(result)
