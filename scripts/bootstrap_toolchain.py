#!/usr/bin/env python3
"""Install the checksum-pinned build tools; validate cached tools before reuse."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def contained(root, relative):
    path = PurePosixPath(relative)
    if not relative or path.is_absolute() or ".." in path.parts or "\\" in relative:
        raise ValueError("Unsafe toolchain archive path")
    destination = root.joinpath(*path.parts)
    if not destination.resolve().is_relative_to(root.resolve()):
        raise ValueError("Toolchain path escapes destination")
    return destination

def download(url, target):
    if not url.startswith("https://"):
        raise ValueError("Build downloads must use HTTPS")
    subprocess.run([
        "curl", "--fail", "--silent", "--show-error", "--location",
        "--proto", "=https", "--proto-redir", "=https", "--retry", "3",
        "--connect-timeout", "20", "--max-time", "300", "--output", str(target), url,
    ], check=True)

def install(destination, lock_path):
    destination = Path(destination).resolve()
    lock_bytes = Path(lock_path).read_bytes()
    lock = json.loads(lock_bytes)
    if lock.get("schema") != 1:
        raise ValueError("Unsupported toolchain lock schema")
    destination.mkdir(parents=True, exist_ok=True)
    state_path = destination / ".toolchain-state.json"
    lock_hash = hashlib.sha256(lock_bytes).hexdigest()
    try:
        state = json.loads(state_path.read_text())
        if state["lock_sha256"] == lock_hash and state["files"] and all(
            contained(destination, name).is_file()
            and digest(contained(destination, name)) == expected
            for name, expected in state["files"].items()
        ):
            print("Pinned toolchain cache verified")
            return
    except (OSError, ValueError, KeyError, TypeError):
        pass
    downloads = destination / ".downloads"
    downloads.mkdir(exist_ok=True)
    installed = {}
    for artifact in lock["artifacts"]:
        expected = artifact["sha256"]
        archive = downloads / expected
        if not archive.is_file() or digest(archive) != expected:
            with tempfile.NamedTemporaryFile(dir=downloads, delete=False) as temporary:
                pending = Path(temporary.name)
            try:
                download(artifact["url"], pending)
                if digest(pending) != expected:
                    raise ValueError("Checksum mismatch for " + artifact["name"])
                pending.replace(archive)
            finally:
                pending.unlink(missing_ok=True)
        if artifact.get("extract_to"):
            target = contained(destination, artifact["extract_to"])
            if target.exists():
                shutil.rmtree(target)
            target.mkdir(parents=True)
            with zipfile.ZipFile(archive) as zipped:
                for info in zipped.infolist():
                    member = contained(target, info.filename)
                    if stat.S_ISLNK(info.external_attr >> 16):
                        raise ValueError("Symlinks are not allowed in toolchain archives")
                    if info.is_dir():
                        member.mkdir(parents=True, exist_ok=True)
                        continue
                    member.parent.mkdir(parents=True, exist_ok=True)
                    member.write_bytes(zipped.read(info))
                    installed[str(member.relative_to(destination))] = digest(member)
        else:
            target = contained(destination, artifact["target"])
            target.parent.mkdir(parents=True, exist_ok=True)
            if artifact.get("member"):
                with zipfile.ZipFile(archive) as zipped:
                    target.write_bytes(zipped.read(artifact["member"]))
            else:
                shutil.copyfile(archive, target)
            installed[str(target.relative_to(destination))] = digest(target)
        print("Verified " + artifact["name"] + " " + artifact["version"])
    state_path.write_text(json.dumps({"lock_sha256": lock_hash, "files": installed}, indent=2) + "\n")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tools", type=Path, default=ROOT / "toolchain")
    parser.add_argument("--lock", type=Path, default=ROOT / "toolchain.lock.json")
    args = parser.parse_args()
    install(args.tools, args.lock)

if __name__ == "__main__":
    main()

