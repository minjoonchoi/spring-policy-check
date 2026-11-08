"""Regression tests for license boundaries and missing distribution notices."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


audit = load_script("license-check")
package = load_script("package-release")


class AttributionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original_metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--locked", "--all-features", "--format-version", "1"], cwd=ROOT, text=True))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ["third_party", ".github", "gradle"]:
            shutil.copytree(ROOT / name, self.root / name)
        for name in ["Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "gradlew", "gradlew.bat"]:
            shutil.copyfile(ROOT / name, self.root / name)
        self.inventory = json.loads((ROOT / "third_party/dependencies.json").read_text())
        self.components = json.loads((ROOT / "third_party/components.json").read_text())
        self.policy = tomllib.loads((ROOT / "deny.toml").read_text())
        self.metadata = copy.deepcopy(self.original_metadata)
        for item in self.metadata["packages"]:
            if item["source"] is None:
                item["manifest_path"] = str(self.root / "Cargo.toml")

    def validate(self):
        audit.validate_inventory(self.root, self.metadata, self.inventory, self.components, self.policy)

    def test_reviewed_current_graph_passes(self):
        self.validate()

    def test_new_dependency_without_review_fails(self):
        item = copy.deepcopy(self.metadata["packages"][0])
        item["name"] = "unreviewed-crate"
        self.metadata["packages"].append(item)
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            self.validate()

    def test_changed_upstream_license_fails(self):
        next(p for p in self.metadata["packages"] if p["source"])["license"] = "GPL-3.0-only"
        with self.assertRaisesRegex(ValueError, "review changed"):
            self.validate()

    def test_missing_license_file_fails(self):
        (self.root / self.inventory["packages"][0]["notice_files"][0]["path"]).unlink()
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            self.validate()

    def test_modified_copyright_notice_fails(self):
        (self.root / self.inventory["packages"][0]["notice_files"][0]["path"]).write_text("Copyright removed")
        with self.assertRaisesRegex(ValueError, "Reviewed file changed"):
            self.validate()

    def test_unlisted_nested_unicode_notice_fails(self):
        p = next(p for p in self.inventory["packages"] if p["name"] == "regex-syntax")
        p["notice_files"] = [n for n in p["notice_files"] if n.get("registry_path") != "src/unicode_tables/LICENSE-UNICODE"]
        with self.assertRaisesRegex(ValueError, "nested notices"):
            self.validate()

    def test_changed_registry_checksum_fails(self):
        self.inventory["packages"][0]["checksum"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.validate()

    def test_unreviewed_action_revision_fails(self):
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.write_text(workflow.read_text().replace("actions/checkout@11d5960a326750d5838078e36cf38b85af677262", "actions/checkout@main"))
        with self.assertRaisesRegex(ValueError, "Unreviewed GitHub Action"):
            self.validate()

    def test_changed_wrapper_fails(self):
        (self.root / "gradle/wrapper/gradle-wrapper.jar").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "Reviewed file changed"):
            self.validate()

    def test_unapproved_embedded_license_fails(self):
        self.inventory["packages"][0]["additional_licenses"] = ["AGPL-3.0-only"]
        with self.assertRaisesRegex(ValueError, "Unapproved reviewed license"):
            self.validate()

    def test_bare_binary_archive_fails(self):
        path = self.root / "bare.tar.gz"
        with tarfile.open(path, "w:gz") as archive:
            member = tarfile.TarInfo("spring-policy")
            member.size = 6
            member.mode = 0o755
            archive.addfile(member, io.BytesIO(b"binary"))
        with self.assertRaisesRegex(ValueError, "missing"):
            package.verify_archive(path, self.root)


class LicenseExpressionTests(unittest.TestCase):
    def check_expression(self, expression, success):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "src").mkdir()
            (root / "src/lib.rs").write_text("// Synthetic dependency-policy input.\n")
            license_field = f"license = {json.dumps(expression)}\n" if expression is not None else ""
            (root / "Cargo.toml").write_text('[package]\nname = "license-policy-input"\nversion = "0.1.0"\nedition = "2021"\n' + license_field)
            subprocess.run(["cargo", "generate-lockfile", "--offline"], cwd=root, check=True, capture_output=True)
            result = subprocess.run(["cargo", "deny", "--manifest-path", str(root / "Cargo.toml"), "--config", str(ROOT / "deny.toml"), "--locked", "check", "licenses"], cwd=root, capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)

    def test_permissive_or_branch_passes(self):
        self.check_expression("MIT OR GPL-3.0-only", True)

    def test_copyleft_only_fails(self):
        self.check_expression("GPL-3.0-only", False)

    def test_copyleft_and_branch_fails(self):
        self.check_expression("MIT AND GPL-3.0-only", False)

    def test_unknown_license_fails(self):
        self.check_expression("LicenseRef-NonCommercial", False)

    def test_missing_license_fails(self):
        self.check_expression(None, False)


if __name__ == "__main__":
    unittest.main()
