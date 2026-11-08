#!/usr/bin/env python3
"""Package the supported Linux binary together with its verified license notices."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("license_check", ROOT / "scripts/license-check.py")
compliance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compliance)


def verify_archive(path, root=ROOT):
    required = {"spring-policy": root / "target/release/spring-policy", "LICENSE": root / "LICENSE", "THIRD_PARTY_NOTICES.md": root / "THIRD_PARTY_NOTICES.md", "docs/OPEN_SOURCE_POLICY.md": root / "docs/OPEN_SOURCE_POLICY.md"}
    required.update({p.relative_to(root).as_posix(): p for p in (root / "third_party").rglob("*") if p.is_file()})
    with tarfile.open(path, "r:gz") as archive:
        files = {m.name: m for m in archive.getmembers() if m.isfile()}
        if len(files) != len(archive.getmembers()) or set(files) != set(required):
            raise ValueError("Archive has missing, duplicate, or unexpected members")
        for name, source in required.items():
            if archive.extractfile(files[name]).read() != source.read_bytes():
                raise ValueError(f"Archive content differs: {name}")
        if files["spring-policy"].mode & 0o111 != 0o111:
            raise ValueError("Packaged binary is not executable")


def main():
    compliance.check()
    components = json.loads((ROOT / "third_party/components.json").read_text())["components"]
    runtime = next(c for c in components if c["id"] == "rust-standard-library")
    compiler = subprocess.check_output(["rustc", "-vV"], text=True)
    fields = dict(line.split(": ", 1) for line in compiler.splitlines() if ": " in line)
    if fields["release"] != runtime["version"] or fields["host"] != "x86_64-unknown-linux-gnu":
        raise ValueError("Unreviewed Rust toolchain or release target")
    binary = ROOT / "target/release/spring-policy"
    header = binary.read_bytes()[:20]
    if header[:6] != b"\x7fELF\x02\x01" or header[18:20] != b"\x3e\x00":
        raise ValueError("Expected an x86_64 Linux ELF binary")
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    destination = output / "spring-policy-linux-x86_64.tar.gz"
    with tempfile.TemporaryDirectory(dir=output) as temporary:
        archive_path = Path(temporary) / destination.name
        with tarfile.open(archive_path, "w:gz") as archive:
            inputs = [(binary, "spring-policy"), (ROOT / "LICENSE", "LICENSE"), (ROOT / "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"), (ROOT / "docs/OPEN_SOURCE_POLICY.md", "docs/OPEN_SOURCE_POLICY.md")]
            inputs += [(p, p.relative_to(ROOT).as_posix()) for p in sorted((ROOT / "third_party").rglob("*")) if p.is_file()]
            for source, name in inputs:
                archive.add(source, arcname=name, recursive=False)
        verify_archive(archive_path)
        archive_path.replace(destination)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  {destination.name}\n")
    print(f"Packaged and verified binary, license inventory, and full notices: {destination.name}")


if __name__ == "__main__":
    main()
