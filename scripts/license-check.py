#!/usr/bin/env python3
"""Verify reviewed dependency provenance and attribution; never auto-approve changes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[1]


def fail(message):
    raise ValueError(message)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_file(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        fail(f"Missing or invalid attribution path: {relative}")
    return path


def verify_file(root, record):
    path = resolve_file(root, record["path"])
    if sha256(path) != record["sha256"]:
        fail(f"Reviewed file changed: {record['path']}")
    return path


def notice_name(path):
    return path.name.lower().startswith(("license", "copying", "notice", "copyright", "unlicense"))


def validate_inventory(root, metadata, inventory, components, policy):
    if inventory["schema_version"] != 2 or components["schema_version"] != 1:
        fail("Unsupported review manifest version")
    allowed = set(policy["licenses"]["allow"])
    records = {(p["name"], p["version"]): p for p in inventory["packages"]}
    if len(records) != len(inventory["packages"]):
        fail("Duplicate dependency review")
    actual = {(p["name"], p["version"]): p for p in metadata["packages"] if p["source"]}
    local = [p for p in metadata["packages"] if not p["source"]]
    if len(local) != 1 or Path(local[0]["manifest_path"]).resolve() != root / "Cargo.toml":
        fail("Unreviewed local/path dependency")
    if records.keys() != actual.keys():
        fail(f"Dependency inventory changed: new={actual.keys() - records.keys()}, stale={records.keys() - actual.keys()}")
    lock = tomllib.loads((root / "Cargo.lock").read_text())
    locked = {(p["name"], p["version"]): p for p in lock["package"]}
    expected_notices = set()
    for key, record in records.items():
        package = actual[key]
        for key_name, actual_value in [("declared_license", package["license"]), ("source", package["source"]), ("repository", package["repository"]), ("checksum", locked[key].get("checksum"))]:
            if record[key_name] != actual_value:
                fail(f"Dependency review changed: {key} {key_name}")
        selected = record["selected_licenses"] + record["additional_licenses"]
        if not selected or any(license_id not in allowed for license_id in selected):
            fail(f"Unapproved reviewed license: {key}")
        if not record["notice_files"]:
            fail(f"No license notices for {key}")
        registry_root = Path(package["manifest_path"]).parent
        vcs = registry_root / ".cargo_vcs_info.json"
        commit = json.loads(vcs.read_text()).get("git", {}).get("sha1") if vcs.exists() else None
        if commit != record["upstream_commit"]:
            fail(f"Upstream source changed: {key}")
        discovered = {p.relative_to(registry_root).as_posix() for p in registry_root.rglob("*") if p.is_file() and notice_name(p)}
        copied = {n["registry_path"] for n in record["notice_files"] if "registry_path" in n}
        if discovered != copied:
            fail(f"Missing or stale nested notices: {key}: {discovered ^ copied}")
        for notice in record["notice_files"]:
            destination = verify_file(root, notice)
            if not notice.get("source", "").startswith("https://"):
                fail(f"Notice has no upstream source URL: {notice['path']}")
            expected_notices.add(notice["path"])
            if "registry_path" in notice:
                original = resolve_file(registry_root, notice["registry_path"])
                if original.read_bytes() != destination.read_bytes():
                    fail(f"Notice differs from upstream crate: {notice['path']}")
    component_ids = [c["id"] for c in components["components"]]
    if len(component_ids) != len(set(component_ids)):
        fail("Duplicate component review")
    required_components = {"gradle-wrapper", "rust-standard-library", "cargo-deny", "actions/checkout", "actions/setup-java", "actions/upload-artifact"}
    if set(component_ids) != required_components:
        fail("Build/runtime component review inventory changed")
    for component in components["components"]:
        if not component["licenses"] or any(x not in allowed for x in component["licenses"]):
            fail(f"Unapproved component: {component['id']}")
        for notice in component["notice_files"]:
            verify_file(root, notice)
            expected_notices.add(notice["path"])
        for record in component.get("checked_files", []):
            verify_file(root, record)
    actual_notices = {p.relative_to(root).as_posix() for p in (root / "third_party").rglob("*") if p.is_file() and notice_name(p)}
    if actual_notices != expected_notices:
        fail(f"Untracked or missing attribution files: {actual_notices ^ expected_notices}")
    workflow_refs = set()
    for workflow in (root / ".github/workflows").glob("*.y*ml"):
        workflow_refs.update(re.findall(r"uses:\s*([^\s#]+)", workflow.read_text()))
    reviewed_refs = {c["action_ref"] for c in components["components"] if "action_ref" in c}
    if workflow_refs != reviewed_refs:
        fail(f"Unreviewed GitHub Action reference: {workflow_refs ^ reviewed_refs}")


def render_notices(inventory, components):
    lines = ["# Third-party notices", "", "This project is MIT licensed. Third-party components retain their own copyrights and licenses.", "License texts are preserved verbatim under `third_party/` and must accompany redistribution.", "", f"This inventory includes all {len(inventory['packages'])} locked Cargo dependencies (including build and target-specific dependencies), not only code linked into a particular binary.", "The selected column records the reviewed alternative for OR expressions; all AND terms and additional embedded notices remain applicable.", "", "| Component | Declared SPDX expression | Selected terms / embedded terms | Notices |", "| --- | --- | --- | --- |"]
    for package in inventory["packages"]:
        notices = ", ".join(f"[{Path(n['path']).name}]({n['path']})" for n in package["notice_files"])
        selected = " AND ".join(package["selected_licenses"] + package["additional_licenses"])
        lines.append(f"| {package['name']} {package['version']} | `{package['declared_license']}` | `{selected}` | {notices} |")
    lines += ["", "## Build and runtime components", "", "| Component | Scope | Reviewed terms | Notices |", "| --- | --- | --- | --- |"]
    for component in components["components"]:
        notices = ", ".join(f"[{Path(n['path']).name}]({n['path']})" for n in component["notice_files"])
        lines.append(f"| {component['id']} {component['version']} | {component['scope']} | {', '.join(component['licenses'])} | {notices} |")
    lines += ["", "The Rust runtime copyright document covers multiple targets. For example, its MPL-2.0 fortanix-sgx-abi entry is SGX-specific and is not part of the shipped Linux GNU target. This snapshot is not an approval for additional build targets.", "", "Tree-sitter embeds ICU Unicode headers; regex-syntax embeds Unicode data; unsafe-libyaml contains a translation of libyaml. Their original notices are preserved in addition to the crate-level declarations.", "", "No third-party source is modified by this project. The Kotlin samples are original test inputs; Spring, JPA and servlet imports do not bundle those libraries.", "", "The Linux binary dynamically uses system libraries such as glibc and libgcc_s. Those libraries, the JDK, the full Gradle distribution, Rust compiler and CI tools are not shipped in the binary archive. Repackaging them (for example in a container) requires a separate inventory and review.", "", "See [open-source policy](docs/OPEN_SOURCE_POLICY.md) for use, attribution, replacement and update rules.", ""]
    return "\n".join(lines)


def check(root=ROOT, write=False):
    inventory = json.loads((root / "third_party/dependencies.json").read_text())
    components = json.loads((root / "third_party/components.json").read_text())
    policy = tomllib.loads((root / "deny.toml").read_text())
    tool_version = next(c['version'] for c in components['components'] if c['id'] == 'cargo-deny')
    installed = subprocess.check_output(["cargo", "deny", "--version"], cwd=root, text=True).strip()
    if installed != f"cargo-deny {tool_version}":
        fail(f"Expected cargo-deny {tool_version}; got {installed}")
    subprocess.run(["cargo", "deny", "--locked", "check", "licenses"], cwd=root, check=True)
    metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--locked", "--all-features", "--format-version", "1"], cwd=root, text=True))
    validate_inventory(root, metadata, inventory, components, policy)
    expected = render_notices(inventory, components)
    target = root / "THIRD_PARTY_NOTICES.md"
    if write:
        target.write_text(expected)
    elif not target.is_file() or target.read_text() != expected:
        fail("Stale THIRD_PARTY_NOTICES.md; review manifests, then run scripts/license-check.py --write")
    print(f"Attribution OK: {len(inventory['packages'])} dependencies, {len(components['components'])} build/runtime components.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Render notices only; never update or approve review manifests")
    args = parser.parse_args()
    try:
        check(write=args.write)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"LICENSE_REVIEW_FAILED: {error}", file=sys.stderr)
        sys.exit(1)
