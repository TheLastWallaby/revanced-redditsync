#!/usr/bin/env python3
"""Deterministic release archives and structural checks, without private inputs."""
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?")

def version(root=ROOT):
    value = (Path(root) / "VERSION").read_text().strip()
    if not VERSION_PATTERN.fullmatch(value):
        raise ValueError("VERSION must look like 0.6.1 or 0.7.0-rc.1")
    return value

def git(*args, root=ROOT):
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()

def write_zip(path, entries, epoch):
    stamp = time.gmtime(max(int(epoch), 315532800))[:6]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compresslevel=9)

def verify_bundle(path, expected_version):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate entries in patch bundle")
        for name in ("classes.dex", "extensions/synccompat.rve"):
            if not archive.read(name).startswith(b"dex\n"):
                raise ValueError("Missing or invalid DEX: " + name)
        manifest = archive.read("META-INF/MANIFEST.MF").decode()
        if f"Version: {expected_version}\r\n" not in manifest:
            raise ValueError("Bundle version differs from VERSION")
        if not any(name.endswith(".class") for name in names):
            raise ValueError("Patch bundle contains no JVM patch classes")

def package_release(root, output, bundle, epoch, test_classes):
    root, output = Path(root), Path(output)
    current = version(root)
    commit = git("rev-parse", "HEAD", root=root)
    tracked = git("ls-files", "--cached", "--others", "--exclude-standard", root=root).splitlines()
    excluded = {"dist", "build", "output", "toolchain", "local-tools", "local-inspect", ".git"}
    private_suffixes = {".apk", ".keystore", ".jks", ".p12", ".pem", ".key"}
    entries = {}
    for name in tracked:
        path = root / name
        if name.split("/")[0] in excluded or "__pycache__" in path.parts:
            continue
        if path.suffix.lower() in private_suffixes:
            raise ValueError("Private input found among source archive candidates: " + name)
        if path.is_symlink():
            raise ValueError("Symlinks cannot be included in a source release: " + name)
        if path.is_file():
            entries[f"revanced-redditsync-{current}/{name}"] = path.read_bytes()
    source = output / f"sync-gallery-comments-compat-v{current}-source.zip"
    write_zip(source, entries, epoch)
    info = {
        "version": current, "source_commit": commit,
        "working_tree_dirty": bool(git("status", "--porcelain", root=root)),
        "source_date_epoch": int(epoch), "java_test_classes": test_classes,
        "toolchain_lock_sha256": hashlib.sha256((root / "toolchain.lock.json").read_bytes()).hexdigest(),
        "android_validation": "Not performed by public CI; use the private APK builder and a device.",
    }
    metadata = output / "build-info.json"
    metadata.write_text(json.dumps(info, indent=2) + "\n")
    artifacts = [Path(bundle), source, metadata]
    sums = []
    for path in artifacts:
        with path.open("rb") as stream:
            sums.append(f"{hashlib.file_digest(stream, 'sha256').hexdigest()}  {path.name}")
    (output / "SHA256SUMS").write_text("\n".join(sums) + "\n")
    return artifacts

