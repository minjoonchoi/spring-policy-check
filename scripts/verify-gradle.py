#!/usr/bin/env python3
"""Verify real Gradle success, warning-only, and policy-error outcomes."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GRADLE = sys.argv[1] if len(sys.argv) > 1 else str(
    ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
)
COMMON = [GRADLE, "--console=plain", "--configuration-cache"]


def run(*tasks):
    return subprocess.run(COMMON + list(tasks), cwd=ROOT, check=False).returncode


def diagnostics(sample):
    path = ROOT / "build" / "reports" / "policy" / f"{sample}.json"
    return json.loads(path.read_text())["diagnostics"]


def main():
    if run("policySpecTest", "policyGoodCheck", "policyWarningCheck") != 0:
        raise RuntimeError("Rust tests or successful Gradle examples failed")
    if diagnostics("valid"):
        raise RuntimeError("The valid sample produced unexpected diagnostics")
    warnings = diagnostics("warnings")
    if not any(d["severity"] == "warning" for d in warnings):
        raise RuntimeError("The warning sample did not produce a warning")
    if any(d["severity"] == "error" for d in warnings):
        raise RuntimeError("The warning sample produced an error")

    report = ROOT / "build" / "reports" / "policy" / "violations.json"
    report.unlink(missing_ok=True)
    if run("policyCheck") != 1:
        raise RuntimeError("The policy-error Gradle example must exit 1")
    actual = sorted((d["rule"], d["code"], d["severity"]) for d in diagnostics("violations"))
    expected = sorted([
        ("AUTH001", "AUTH_OUTSIDE_BOUNDARY", "error"),
        ("NAME001", "NAME_MISMATCH", "error"),
        ("JPA001", "PREFER_DERIVED", "warning"),
    ])
    if actual != expected:
        raise RuntimeError(f"Unexpected error-sample diagnostics: {actual}")
    print("Verified: 90 Rust tests and Gradle success/warning/policy-error outcomes.")


if __name__ == "__main__":
    main()
