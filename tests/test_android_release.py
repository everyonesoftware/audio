import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("android_release", Path(__file__).resolve().parents[1] / "scripts/android-release.py")
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        self.git("init", "--initial-branch=main")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], text=True, capture_output=True, check=True).stdout.strip()

    def commits(self, count):
        for _ in range(count):
            self.git("-c", "user.name=Release fixture", "-c", "user.email=fixture@example.invalid", "commit", "--allow-empty", "-m", "Fixture")

    def package_fixture(self):
        self.commits(3)
        source = self.root / "android/app/build/outputs/apk/release"
        source.mkdir(parents=True)
        # Deliberately non-APK bytes: packaging unit fixture, not a fabricated Android build.
        (source / "app-release.apk").write_bytes(b"packaging-unit-fixture")
        metadata = source / "output-metadata.json"
        metadata.write_text(json.dumps({"elements": [{"versionCode": 3, "versionName": "0.1.3", "outputFile": "app-release.apk"}]}))
        return metadata

    def test_counts_actual_full_git_history(self):
        self.commits(3)
        info = release.version(self.root)
        self.assertEqual(3, info["count"])
        self.assertEqual("0.1.3", info["version"])
        self.assertEqual("v0.1.3", info["tag"])
        self.assertEqual(self.git("rev-parse", "HEAD"), info["sha"])

    def test_unborn_repository_cannot_publish_a_fabricated_version(self):
        with self.assertRaisesRegex(ValueError, "commit|history|HEAD"):
            release.version(self.root)

    def test_shallow_history_is_rejected(self):
        self.commits(3)
        clone = Path(self.temp.name) / "shallow"
        result = subprocess.run(["git", "-c", "protocol.file.allow=always", "clone", "--no-local", "--depth=1", str(self.root), str(clone)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        with self.assertRaisesRegex(ValueError, "shallow|full history"):
            release.version(clone)

    def test_package_uses_apk_metadata_and_verifiable_checksum(self):
        self.package_fixture()
        output = Path(self.temp.name) / "dist"
        manifest = release.package(self.root, output)
        self.assertEqual("Audio-0.1.3.apk", manifest["apk"])
        self.assertEqual(3, manifest["count"])
        self.assertTrue((output / "Audio-0.1.3.apk.sha256").is_file())
        self.assertEqual(manifest, release.validate(output, release.version(self.root)))

    def test_mismatched_android_version_is_rejected(self):
        metadata = self.package_fixture()
        data = json.loads(metadata.read_text())
        data["elements"][0]["versionCode"] = 2
        metadata.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "version"):
            release.package(self.root, Path(self.temp.name) / "dist")

    def test_metadata_cannot_escape_apk_directory(self):
        metadata = self.package_fixture()
        data = json.loads(metadata.read_text())
        data["elements"][0]["outputFile"] = "../../elsewhere.apk"
        metadata.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            release.package(self.root, Path(self.temp.name) / "dist")

    def test_tampered_package_is_rejected_before_publish(self):
        self.package_fixture()
        output = Path(self.temp.name) / "dist"
        manifest = release.package(self.root, output)
        (output / manifest["apk"]).write_bytes(b"modified-fixture")
        with self.assertRaisesRegex(ValueError, "checksum|integrity"):
            release.validate(output, release.version(self.root))

    def test_existing_published_release_is_verified_without_replacing_assets(self):
        self.package_fixture()
        output = Path(self.temp.name) / "dist"
        release.package(self.root, output)
        expected = release.version(self.root)
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            if args[1:3] == ["release", "view"]:
                result = {"isDraft": False, "targetCommitish": expected["sha"]}
            elif args[1] == "api" and "/commits/" in args[2]:
                result = {"sha": expected["sha"]}
            elif args[1] == "api" and "/git/ref/tags/" in args[2]:
                result = {"object": {"type": "commit", "sha": expected["sha"]}}
            elif args[1] == "api" and "/git/ref/heads/" in args[2]:
                result = {"object": {"sha": "0" * 40}}
            elif args[1:3] == ["release", "download"]:
                target = Path(args[args.index("--dir") + 1])
                for file in output.iterdir():
                    shutil.copy2(file, target / file.name)
                return subprocess.CompletedProcess(args, 0, "", "")
            else:
                raise AssertionError(f"Unexpected remote mutation or command: {args}")
            return subprocess.CompletedProcess(args, 0, json.dumps(result), "")
        release.publish(self.root, output, "fixture/audio", run=run)
        self.assertFalse(any(args[1:3] in [["release", "upload"], ["release", "edit"], ["release", "create"]] for args in calls))

    def test_existing_release_tag_pointing_elsewhere_fails_closed(self):
        self.package_fixture()
        output = Path(self.temp.name) / "dist"
        release.package(self.root, output)
        def run(args, **kwargs):
            if args[1:3] == ["release", "view"]:
                return subprocess.CompletedProcess(args, 0, json.dumps({"isDraft": False}), "")
            if args[1] == "api":
                data = {"object": {"type": "commit", "sha": "0" * 40}} if "/git/ref/tags/" in args[2] else {"sha": "0" * 40}
                return subprocess.CompletedProcess(args, 0, json.dumps(data), "")
            raise AssertionError("Must not modify an unrelated release")
        with self.assertRaisesRegex(ValueError, "commit|tag"):
            release.publish(self.root, output, "fixture/audio", run=run)

    def fake_github(self, expected, initial_draft=False, tamper=False):
        remote = Path(self.temp.name) / "remote"
        remote.mkdir()
        state = {"release": {"isDraft": True, "targetCommitish": expected["sha"]} if initial_draft else None,
                 "latest": None, "calls": []}
        def run(args, **kwargs):
            state["calls"].append(args)
            result = None
            if args[1:3] == ["release", "view"]:
                if state["release"] is None:
                    return subprocess.CompletedProcess(args, 1, "", "release not found")
                result = state["release"]
            elif args[1] == "api" and "/commits/" in args[2]:
                if state["release"] is None or state["release"]["isDraft"]:
                    return subprocess.CompletedProcess(args, 1, "", "No commit found (HTTP 422)")
                result = {"sha": expected["sha"]}
            elif args[1] == "api" and "/git/ref/tags/" in args[2]:
                if state["release"] is None or state["release"]["isDraft"]:
                    return subprocess.CompletedProcess(args, 1, "", "not found (HTTP 404)")
                result = {"object": {"type": "commit", "sha": expected["sha"]}}
            elif args[1:3] == ["release", "create"]:
                self.assertIn("--draft", args)
                state["release"] = {"isDraft": True, "targetCommitish": args[args.index("--target") + 1]}
            elif args[1:3] == ["release", "upload"]:
                self.assertTrue(state["release"]["isDraft"])
                for path in args[4:args.index("--repo")]:
                    shutil.copy2(path, remote / Path(path).name)
            elif args[1:3] == ["release", "download"]:
                target = Path(args[args.index("--dir") + 1])
                for path in remote.iterdir():
                    shutil.copy2(path, target / path.name)
                if tamper:
                    next(target.glob("*.apk")).write_bytes(b"tampered remote fixture")
            elif args[1:3] == ["release", "edit"]:
                if "--draft=false" in args:
                    state["release"]["isDraft"] = False
                if "--latest" in args:
                    state["latest"] = expected["tag"]
            elif args[1] == "api" and "/git/ref/heads/" in args[2]:
                result = {"object": {"sha": expected["sha"]}}
            elif args[1] == "api" and args[2].endswith("/releases/latest"):
                result = {"tag_name": state["latest"]}
            else:
                raise AssertionError(f"Unexpected command: {args}")
            return subprocess.CompletedProcess(args, 0, json.dumps(result) if result is not None else "", "")
        return run, state

    def test_new_release_is_published_only_after_asset_readback(self):
        self.package_fixture()
        output = Path(self.temp.name) / "dist"
        package = release.package(self.root, output)
        run, state = self.fake_github(release.version(self.root))
        self.assertEqual(package, release.publish(self.root, output, "fixture/audio", run=run))
        self.assertFalse(state["release"]["isDraft"])
        self.assertEqual("v0.1.3", state["latest"])
        download = next(i for i, args in enumerate(state["calls"]) if args[1:3] == ["release", "download"])
        publish = next(i for i, args in enumerate(state["calls"]) if "--draft=false" in args)
        self.assertLess(download, publish)

    def test_partial_matching_draft_can_be_repaired(self):
        self.package_fixture()
        output = Path(self.temp.name) / "dist"
        package = release.package(self.root, output)
        run, state = self.fake_github(release.version(self.root), initial_draft=True)
        self.assertEqual(package, release.publish(self.root, output, "fixture/audio", run=run))
        self.assertFalse(any(args[1:3] == ["release", "create"] for args in state["calls"]))
        self.assertFalse(state["release"]["isDraft"])

    def test_failed_upload_readback_leaves_release_unpublished(self):
        self.package_fixture()
        output = Path(self.temp.name) / "dist"
        release.package(self.root, output)
        run, state = self.fake_github(release.version(self.root), tamper=True)
        with self.assertRaisesRegex(ValueError, "integrity|checksum"):
            release.publish(self.root, output, "fixture/audio", run=run)
        self.assertTrue(state["release"]["isDraft"])
        self.assertFalse(any("--draft=false" in args for args in state["calls"]))


if __name__ == "__main__":
    unittest.main()
