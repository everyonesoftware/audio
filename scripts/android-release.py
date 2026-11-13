"""Commit-count versioning, release packaging and guarded GitHub publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def version(root):
    def git(*args):
        result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
        if result.returncode:
            raise ValueError("A committed Git HEAD and full history are required for releases.")
        return result.stdout.strip()
    if git("rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("A shallow checkout cannot determine the version; fetch full history.")
    count = int(git("rev-list", "--count", "HEAD"))
    if not 1 <= count <= 2_100_000_000:
        raise ValueError("The commit count is outside Android's supported versionCode range.")
    name = f"0.1.{count}"
    return {"count": count, "version": name, "tag": f"v{name}", "sha": git("rev-parse", "HEAD")}


def digest(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def package(root, destination, metadata=None):
    info = version(root)
    metadata = Path(metadata or Path(root) / "android/app/build/outputs/apk/release/output-metadata.json")
    entries = json.loads(metadata.read_text(encoding="utf-8"))["elements"]
    if len(entries) != 1:
        raise ValueError("Expected exactly one universal release APK.")
    entry = entries[0]
    if entry["versionCode"] != info["count"] or entry["versionName"] != info["version"]:
        raise ValueError("APK version metadata does not match the Git commit count.")
    if entry["outputFile"] != "app-release.apk":
        raise ValueError("Expected the signed app-release.apk, not an unsigned or external file.")
    source = metadata.parent / entry["outputFile"]
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    name = f"Music-{info['version']}.apk"
    target = destination / name
    shutil.copy2(source, target)
    manifest = dict(info, apk=name, sha256=digest(target), bytes=target.stat().st_size)
    (destination / f"{name}.sha256").write_text(f"{manifest['sha256']}  {name}\n", encoding="utf-8")
    (destination / "release.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return validate(destination, info)


def validate(directory, expected):
    directory = Path(directory)
    manifest = json.loads((directory / "release.json").read_text(encoding="utf-8"))
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("Release metadata does not match the intended commit/version.")
    name = f"Music-{expected['version']}.apk"
    if manifest.get("apk") != name:
        raise ValueError("Unexpected APK asset name.")
    apk = directory / name
    checksum = digest(apk)
    if checksum != manifest.get("sha256") or apk.stat().st_size != manifest.get("bytes"):
        raise ValueError("APK integrity checksum or size mismatch.")
    if (directory / f"{name}.sha256").read_text(encoding="utf-8") != f"{checksum}  {name}\n":
        raise ValueError("APK checksum file does not match the asset.")
    return manifest


def publish(root, directory, repository, run=None):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository identifier.")
    expected = version(root)
    local = validate(directory, expected)
    run = run or subprocess.run
    tag = expected["tag"]

    def gh(*args, optional=False):
        result = run(["gh", *args], capture_output=True, text=True)
        if result.returncode:
            missing = "(http 404)" in result.stderr.lower() or "release not found" in result.stderr.lower()
            if optional and missing:
                return None
            raise RuntimeError(f"GitHub command failed: {result.stderr.strip()}")
        return result.stdout

    def view():
        data = gh("release", "view", tag, "--repo", repository, "--json", "isDraft,targetCommitish", optional=True)
        return json.loads(data) if data is not None else None

    def tag_commit(optional=False):
        # Missing refs return 404; /commits/<missing-ref> instead returns 422.
        data = gh("api", f"repos/{repository}/git/ref/tags/{tag}", optional=optional)
        if data is None:
            return None
        target = json.loads(data)["object"]
        if target["type"] == "commit":
            return target["sha"]
        resolved = gh("api", f"repos/{repository}/commits/{tag}")
        assert resolved is not None
        return json.loads(resolved)["sha"]

    def remote_package():
        with tempfile.TemporaryDirectory(prefix="audio-release-", dir=os.environ.get("RUNNER_TEMP") or os.environ.get("TMPDIR")) as temporary:
            gh("release", "download", tag, "--repo", repository, "--dir", temporary,
               "--pattern", local["apk"], "--pattern", local["apk"] + ".sha256", "--pattern", "release.json")
            return validate(temporary, expected)

    def promote_if_head():
        response = gh("api", f"repos/{repository}/git/ref/heads/main")
        assert response is not None
        head = json.loads(response)["object"]["sha"]
        if head == expected["sha"]:
            gh("release", "edit", tag, "--repo", repository, "--latest")
            response = gh("api", f"repos/{repository}/releases/latest")
            assert response is not None
            latest = json.loads(response)
            if latest["tag_name"] != tag:
                raise ValueError("The latest release could not be verified.")

    current = view()
    if current is not None and not current["isDraft"]:
        if tag_commit() != expected["sha"]:
            raise ValueError("Existing release tag points at a different commit; refusing to overwrite.")
        # Published assets are immutable here. Reruns verify existing bytes, not clobber them.
        result = remote_package()
        promote_if_head()
        return result

    existing_tag = tag_commit(optional=True)
    if existing_tag is not None and existing_tag != expected["sha"]:
        raise ValueError("Existing tag points at another commit; refusing to reuse its version.")
    if current is None:
        notes = (f"Android alpha build {expected['version']}.\n\nCommit: {expected['sha']}\n"
                 f"Git commit count / Android versionCode: {expected['count']}\n\n"
                 "Install the signed APK. SHA-256 and release metadata are attached.\n"
                 "Keep the same signing key for future updates. No offline downloads or Android Auto yet.")
        gh("release", "create", tag, "--repo", repository, "--target", expected["sha"], "--draft",
           "--title", f"Music {expected['version']}", "--notes", notes)
        current = view()
    if current is None or not current["isDraft"] or current["targetCommitish"] != expected["sha"]:
        raise ValueError("The draft release does not target this exact commit.")
    assets = [str(Path(directory) / name) for name in [local["apk"], local["apk"] + ".sha256", "release.json"]]
    # Only the matching unpublished draft may be repaired after a partial upload.
    gh("release", "upload", tag, *assets, "--repo", repository, "--clobber")
    if remote_package() != local:
        raise ValueError("Uploaded assets do not match the built package.")
    gh("release", "edit", tag, "--repo", repository, "--draft=false", "--latest=false")
    published = view()
    if published is None or published["isDraft"] or tag_commit() != expected["sha"]:
        raise ValueError("Published release commit/state verification failed.")
    result = remote_package()
    promote_if_head()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["version", "package", "publish"])
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--directory", type=Path, default=Path("dist/android"))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.command == "version":
        result = version(root)
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                for key, value in result.items():
                    output.write(f"{key}={value}\n")
    elif args.command == "package":
        result = package(root, args.directory)
    else:
        if not args.repository:
            parser.error("--repository is required for publication")
        result = publish(root, args.directory, args.repository)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
