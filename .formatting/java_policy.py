"""Check explicit JetBrains nullability and instance qualifiers (local only)."""

import hashlib
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

VERSION = "14.3.0"
SHA256 = "754e218ab1fcabb1e1c5f8530e9d3aa37636806c22987bb279dd907a6ee749b2"
EXCLUDE = re.compile(
    r"^(server|run|logs|\.formatting)/|(^|/)(build|bin|dist|node_modules|__pycache__|"
    r"\.git|\.gradle|\.venv|\.terraform|\.build|\.tools)/"
)


def checkstyle_jar(cache):
    cache.mkdir(parents=True, exist_ok=True)
    jar = cache / f"checkstyle-{VERSION}-all.jar"
    if jar.exists() and hashlib.sha256(jar.read_bytes()).hexdigest() == SHA256:
        return jar
    url = f"https://github.com/checkstyle/checkstyle/releases/download/checkstyle-{VERSION}/{jar.name}"
    print(f"Downloading Checkstyle {VERSION} (SHA-256 verified)", flush=True)
    with urllib.request.urlopen(url, timeout=120) as response:
        content = response.read()
    if hashlib.sha256(content).hexdigest() != SHA256:
        raise RuntimeError("Checkstyle download has an unexpected SHA-256; nothing was executed")
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as download:
        download.write(content)
    Path(download.name).replace(jar)
    return jar


def audit(command, root):
    result = subprocess.run(command, cwd=root, capture_output=True, text=True, encoding="utf-8")
    sys.stderr.write(result.stderr)
    try:
        report = ET.fromstring(result.stdout)
    except ET.ParseError:
        print("Checkstyle did not produce a valid audit report.", file=sys.stderr)
        sys.stderr.write(result.stdout)
        return 1
    if report.tag != "checkstyle":
        print("Unexpected Checkstyle audit report.", file=sys.stderr)
        return 1
    violations = report.findall(".//error")
    for file in report.findall("file"):
        for error in file.findall("error"):
            rule = error.get("source", "").rsplit(".", 1)[-1].removesuffix("Check")
            print(
                f"{file.get('name')}:{error.get('line', '0')}:{error.get('column', '0')}: "
                f"{error.get('message')} [{rule}]"
            )
    # Checkstyle exits with the number of violations; POSIX truncates that to
    # eight bits. A report with 256 errors must fail even if the exit status is 0.
    return int(bool(result.returncode or violations or report.findall(".//exception")))


def main():
    root = Path(__file__).resolve().parent.parent
    names = sys.argv[1:] or subprocess.check_output(["git", "ls-files", "-z", "--", "*.java"], cwd=root).decode(
        "utf-8"
    ).split("\0")
    files = []
    for name in names:
        path = (root / name).resolve()
        relative = path.relative_to(root).as_posix()
        if path.suffix == ".java" and path.is_file() and not EXCLUDE.search(relative):
            files.append(str(path))
    if not files:
        return 0
    jar = checkstyle_jar(root / ".formatting/build")
    command = ["java", "-jar", str(jar), "-c", str(root / ".formatting/java-policy.xml"), "-f", "xml"]
    # Bounded native argv supports Windows Unicode paths as well as large commits.
    # Java @argfiles use the native code page on Windows and corrupt UTF-8 paths.
    base_length = sum(len(part) + 3 for part in command)
    batch = []
    length = base_length
    failed = 0
    for file in files:
        if batch and length + len(file) + 3 > 16000:
            failed |= audit(command + batch, root)
            batch = []
            length = base_length
        batch.append(file)
        length += len(file) + 3
    return failed | audit(command + batch, root)


if __name__ == "__main__":
    raise SystemExit(main())
