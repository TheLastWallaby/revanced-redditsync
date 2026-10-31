#!/usr/bin/env python3
"""Download the prepared Sync base APK from the private builder and disassemble it for reading.

The APK and its smali stay in the ignored local-inspect/ directory. Never commit or
publish them. Requires gh access to the private repository and a bootstrapped toolchain.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIVATE_REPO = "TheLastWallaby/revanced-redditsync-personal"
EXTENSION_MARKER = b"Lapp/revanced/extension/synccompat/"


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_base(base, inputs):
    """Return the base APK's DEX entry names after the same checks the private builder makes."""
    expected = json.loads(Path(inputs).read_text())["base_apk_sha256"]
    if not re.fullmatch(r"[0-9a-f]{64}", expected or ""):
        raise ValueError("Private configuration has no base APK checksum")
    if sha256(base) != expected:
        raise ValueError("Base APK checksum differs from the private configuration")
    with zipfile.ZipFile(base) as archive:
        dex = sorted(name for name in archive.namelist() if re.fullmatch(r"classes[0-9]*\.dex", name))
        if not dex:
            raise ValueError("Base APK contains no DEX files")
        if any(EXTENSION_MARKER in archive.read(name) for name in dex):
            raise ValueError("Base APK already contains this bundle's patches")
    return dex


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=PRIVATE_REPO, help="private builder repository")
    parser.add_argument("--out", type=Path, default=ROOT / "local-inspect")
    parser.add_argument("--tools", type=Path, default=ROOT / "toolchain")
    args = parser.parse_args()
    out = args.out.resolve()
    if out.is_relative_to(ROOT) and not out.is_relative_to((ROOT / "local-inspect").resolve()):
        parser.error("--out inside the repository must be local-inspect/ (ignored by git)")
    cli = args.tools / "revanced-cli.jar"
    if not cli.is_file():
        parser.error("Toolchain missing; run python build.py --bootstrap first")
    java = shutil.which("java")
    if not java:
        parser.error("Install Java 17 or later")

    inputs_dir = out / "inputs"
    inputs_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(["gh", "release", "download", "build-inputs", "--repo", args.repo,
                    "--pattern", "base.apk", "--pattern", "inputs.json",
                    "--dir", str(inputs_dir), "--clobber"], check=True)
    base = inputs_dir / "base.apk"
    dex = verify_base(base, inputs_dir / "inputs.json")

    dex_dir, smali_dir = out / "dex", out / "smali"
    for directory in (dex_dir, smali_dir):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    with zipfile.ZipFile(base) as archive:
        for name in dex:
            (dex_dir / name).write_bytes(archive.read(name))
            # baksmali ships inside the pinned ReVanced CLI jar.
            subprocess.run([java, "-cp", str(cli), "com.android.tools.smali.baksmali.Main", "disassemble",
                            "--output", str(smali_dir / Path(name).stem), str(dex_dir / name)], check=True)
    print(f"Verified base APK {sha256(base)}")
    print(f"Disassembled {len(dex)} DEX files into {smali_dir}")


if __name__ == "__main__":
    main()
