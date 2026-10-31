import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import bootstrap_toolchain as tools
from scripts.build_bundle import discover_java_tests, run_java_tests
from scripts.check_release import check_sums
from scripts.release_artifacts import package_release, verify_bundle, version, write_zip
from scripts.setup_private_builder import scaffold

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("personal_builder", ROOT / "private-builder/build_apk.py")
personal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(personal)

class InfrastructureTests(unittest.TestCase):
    def test_java_discovery_executes_packaged_and_default_package_suites(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources, classes = root / "sources", root / "classes"
            sources.mkdir()
            classes.mkdir()
            # A top-level source can declare a package, as SafeSLinksTest does.
            for name, package in [("PackagedTest", "package fixture . security;"),
                                  ("DefaultTest", "")]:
                marker = root / (name + ".ran")
                (sources / (name + ".java")).write_text(
                    "/* package misleading.comment; */\n" + package + "\n"
                    f"public class {name} {{ public static void main(String[] args) throws Exception {{\n"
                    f"java.nio.file.Files.write(java.nio.file.Paths.get({json.dumps(str(marker))}), new byte[] {{1}});\n"
                    "}}\n")
            nested = sources / "nested"
            nested.mkdir()
            (nested / "HelperTest.java").write_text(
                "// public static void main(String[] args) {}\nclass HelperTest {}\n")
            java = Path(shutil.which("java")).resolve()
            subprocess.run([str(java), "-m", "jdk.compiler/com.sun.tools.javac.Main",
                            "-d", str(classes), *map(str, sources.rglob("*.java"))], check=True)
            self.assertEqual(run_java_tests(sources, java, classes),
                             ["DefaultTest", "fixture.security.PackagedTest"])
            self.assertTrue((root / "PackagedTest.ran").is_file())
            self.assertTrue((root / "DefaultTest.ran").is_file())

    def test_security_suite_is_discovered_with_its_package(self):
        self.assertIn("app.revanced.extension.synccompat.SafeSLinksTest",
                      discover_java_tests(ROOT / "tests"))

    def test_award_suite_is_discovered_with_its_package(self):
        self.assertIn("app.revanced.extension.synccompat.AwardCompatTest",
                      discover_java_tests(ROOT / "tests"))

    def test_comment_video_suite_is_discovered_with_its_package(self):
        self.assertIn("app.revanced.extension.synccompat.CommentVideoTest",
                      discover_java_tests(ROOT / "tests"))
        self.assertIn("app.revanced.extension.synccompat.ImgurCommentVideoTest",
                      discover_java_tests(ROOT / "tests"))
        self.assertIn("app.revanced.extension.synccompat.VideoPreviewLoadingTest",
                      discover_java_tests(ROOT / "tests"))

    def test_java_runner_rejects_an_empty_test_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(SystemExit, "No Java test suites"):
                run_java_tests(temporary, "java", temporary)

    def test_checksum_mismatch_never_installs_tool(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            lock = root / "lock.json"
            lock.write_text(json.dumps({"schema": 1, "artifacts": [{
                "name": "test", "version": "1", "url": "https://example.invalid/tool",
                "sha256": "0" * 64, "target": "tool.jar"}]}))
            with patch.object(tools, "download", side_effect=lambda u, p: p.write_bytes(b"corrupt")):
                with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                    tools.install(root / "tools", lock)
            self.assertFalse((root / "tools/tool.jar").exists())

    def test_corrupted_installed_cache_is_repaired_from_pinned_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            good = b"expected tool"
            lock = root / "lock.json"
            lock.write_text(json.dumps({"schema": 1, "artifacts": [{
                "name": "test", "version": "1", "url": "https://example.invalid/tool",
                "sha256": hashlib.sha256(good).hexdigest(), "target": "tool.jar"}]}))
            with patch.object(tools, "download", side_effect=lambda u, p: p.write_bytes(good)) as downloader:
                tools.install(root / "tools", lock)
                (root / "tools/tool.jar").write_bytes(b"tampered")
                tools.install(root / "tools", lock)
                self.assertEqual(downloader.call_count, 1)
                self.assertEqual((root / "tools/tool.jar").read_bytes(), good)

    def test_archive_cannot_escape_install_directory(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("../escaped", b"unsafe")
        blob = data.getvalue()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            lock = root / "lock.json"
            lock.write_text(json.dumps({"schema": 1, "artifacts": [{
                "name": "test", "version": "1", "url": "https://example.invalid/archive",
                "sha256": hashlib.sha256(blob).hexdigest(), "extract_to": "compiler"}]}))
            with patch.object(tools, "download", side_effect=lambda u, p: p.write_bytes(blob)):
                with self.assertRaisesRegex(ValueError, "Unsafe"):
                    tools.install(root / "tools", lock)
            self.assertFalse((root / "tools/escaped").exists())

    def test_archives_are_deterministic_regardless_of_input_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_zip(root / "a.zip", {"b": b"two", "a": b"one"}, 1_700_000_000)
            write_zip(root / "b.zip", {"a": b"one", "b": b"two"}, 1_700_000_000)
            self.assertEqual((root / "a.zip").read_bytes(), (root / "b.zip").read_bytes())

    def test_version_cannot_be_a_path_or_shell_fragment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for invalid in ["../0.6.1", "0.6.1;echo x", "v0.6.1", "01.0.0"]:
                (root / "VERSION").write_text(invalid)
                with self.assertRaises(ValueError):
                    version(root)

    def test_rvp_manifest_version_must_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bundle.rvp"
            write_zip(path, {"META-INF/MANIFEST.MF": b"Version: 1.0.0\r\n",
                             "classes.dex": b"dex\n035\0", "extensions/synccompat.rve": b"dex\n035\0",
                             "Patch.class": b"fixture"}, 1_700_000_000)
            with self.assertRaisesRegex(ValueError, "version"):
                verify_bundle(path, "2.0.0")

    def test_checksums_reject_traversal_and_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for text in [("0" * 64) + "  ../outside\n",
                         ("0" * 64) + "  build-info.json\n" + ("0" * 64) + "  build-info.json\n"]:
                (root / "SHA256SUMS").write_text(text)
                with self.assertRaises((ValueError, FileNotFoundError)):
                    check_sums(root)

    def test_source_archive_excludes_outputs_and_refuses_tracked_signing_keys(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "Build test"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "build@example.invalid"], cwd=root, check=True)
            (root / "VERSION").write_text("1.0.0\n")
            (root / "toolchain.lock.json").write_text("{}")
            (root / ".gitignore").write_text("/output/\n")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "fixture"], cwd=root, check=True)
            output = root / "output"
            output.mkdir()
            bundle = output / "patches.rvp"
            bundle.write_bytes(b"bundle")
            (output / "private.apk").write_bytes(b"not for sharing")
            artifacts = package_release(root, output, bundle, 1_700_000_000, ["ExampleTest"])
            with zipfile.ZipFile(artifacts[1]) as archive:
                self.assertFalse(any("private.apk" in n for n in archive.namelist()))
            (root / "accidental.keystore").write_bytes(b"dummy fixture, not a real key")
            with self.assertRaisesRegex(ValueError, "Private input"):
                package_release(root, output, bundle, 1_700_000_000, ["ExampleTest"])

    def test_private_scaffold_contains_workflow_and_no_personal_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scaffold(root)
            self.assertTrue((root / ".github/workflows/build-apk.yml").is_file())
            self.assertTrue((root / "VerifyPersonalApk.java").is_file())
            self.assertTrue((root / "AGENTS.md").is_file())
            self.assertFalse(any(root.rglob("*.apk")))
            self.assertFalse(any(root.rglob("*.keystore")))

    def test_personal_builder_rejects_replaced_release_bytes_and_dirty_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_zip(root / "patches.rvp", {"META-INF/MANIFEST.MF": b"Version: 1.0.0\r\n"}, 1_700_000_000)
            info = {"version": "1.0.0", "working_tree_dirty": True}
            (root / "build-info.json").write_text(json.dumps(info))
            (root / "SHA256SUMS").write_text("".join(
                hashlib.sha256((root / name).read_bytes()).hexdigest() + "  " + name + "\n"
                for name in ["patches.rvp", "build-info.json"]))
            with self.assertRaisesRegex(ValueError, "clean-source"):
                personal.validate_bundle(root, "v1.0.0")
            (root / "patches.rvp").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "checksum"):
                personal.validate_bundle(root, "v1.0.0")

if __name__ == "__main__":
    unittest.main()
