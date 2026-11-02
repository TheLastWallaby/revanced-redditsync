#!/usr/bin/env python3
"""Provision the private APK builder with GitHub CLI and private input files."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def run(*args, cwd=None, data=None):
    result = subprocess.run(list(map(str, args)), cwd=cwd, input=data, capture_output=True)
    if result.returncode:
        # Do not echo secret stdin or credential-bearing commands.
        raise SystemExit("GitHub setup command failed: " + result.stderr.decode(errors="replace")[:400])
    return result.stdout

def scaffold(destination):
    destination = Path(destination)
    (destination / ".github/workflows").mkdir(parents=True, exist_ok=True)
    for source, target in [
        ("private-builder/build-apk.yml", ".github/workflows/build-apk.yml"),
        ("private-builder/build_apk.py", "build_apk.py"),
        ("private-builder/AGENTS.md", "AGENTS.md"),
        ("private-builder/README.md", "README.md"),
        ("scripts/bootstrap_toolchain.py", "bootstrap_toolchain.py"),
        ("tools/VerifyPersonalApk.java", "VerifyPersonalApk.java"),
        ("toolchain.lock.json", "toolchain.lock.json"),
    ]:
        shutil.copyfile(ROOT / source, destination / target)
    (destination / ".gitignore").write_text("/toolchain/\n/output/\n*.apk\n*.keystore\n*.jks\n__pycache__/\n")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default="TheLastWallaby/revanced-redditsync-personal")
    parser.add_argument("--base", type=Path, help="Stock Sync 23033 APK (v23.06.30-13:39), with no ReVanced patches")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--signer-sha256")
    parser.add_argument("--redirect-uri", help="Spoof client Redirect URI registered for your Reddit client")
    parser.add_argument("--user-agent", help="Spoof client User-Agent (default: random, as the official patch)")
    parser.add_argument("--enable", action="append", default=[],
                        help="Also apply this default-off patch, for example 'Use /user/ endpoint'")
    parser.add_argument("--export", type=Path, help="Only prepare the private repository files; no API changes")
    args = parser.parse_args()
    if args.export:
        scaffold(args.export)
        print("Prepared private builder files")
        return
    if not shutil.which("gh"):
        parser.error("Install GitHub CLI and sign in with repo/workflow access, or use --export")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("Repository must be owner/name")
    if not args.base or not args.base.is_file() or not args.keystore or not args.keystore.is_file():
        parser.error("Supply the stock Sync APK and your original signing key")
    with zipfile.ZipFile(args.base) as archive:
        if any(re.fullmatch(r"classes[0-9]*\.dex", n) and b"Lapp/revanced/" in archive.read(n) for n in archive.namelist()):
            parser.error("The base already contains ReVanced patches; supply stock Sync 23033")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", os.environ.get("REDDIT_CLIENT_ID", "")):
        parser.error("Set REDDIT_CLIENT_ID to your Reddit OAuth client ID (stored as an Actions secret)")
    if not args.signer_sha256 or not re.fullmatch(r"[0-9a-fA-F]{64}", args.signer_sha256):
        parser.error("Supply the signing certificate SHA-256 of your installed app")
    if args.repository == "TheLastWallaby/revanced-redditsync":
        parser.error("The personal builder must be a separate private repository")
    with tempfile.TemporaryDirectory(prefix="sync-private-setup-") as temporary:
        directory = Path(temporary)
        scaffold(directory)
        run("git", "init", "-b", "main", cwd=directory)
        # Repository-local identity only.
        login = json.loads(run("gh", "api", "user"))["login"]
        run("git", "config", "user.name", login, cwd=directory)
        run("git", "config", "user.email", login + "@users.noreply.github.com", cwd=directory)
        run("git", "add", ".", cwd=directory)
        run("git", "commit", "-m", "Set up personal Sync APK builder", cwd=directory)
        run("gh", "repo", "create", args.repository, "--private", "--source", directory, "--remote", "origin", "--push")
        details = json.loads(run("gh", "api", "repos/" + args.repository))
        if not details["private"]:
            raise SystemExit("Repository is not private; private inputs were not uploaded")
        run("gh", "secret", "set", "SIGNING_KEY_B64", "--repo", args.repository,
            data=base64.b64encode(args.keystore.read_bytes()))
        for name in ["KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "REDDIT_CLIENT_ID"]:
            if os.environ.get(name):
                run("gh", "secret", "set", name, "--repo", args.repository, data=os.environ[name].encode())
        inputs = directory / "private-inputs"
        inputs.mkdir()
        shutil.copyfile(args.base, inputs / "base.apk")
        with args.base.open("rb") as stream:
            base_hash = hashlib.file_digest(stream, "sha256").hexdigest()
        configuration = {"base_apk_sha256": base_hash, "signer_sha256": args.signer_sha256.lower()}
        spoof = {key: value for key, value in [("redirect_uri", args.redirect_uri), ("user_agent", args.user_agent)] if value}
        if spoof:
            configuration["spoof_client"] = spoof
        if args.enable:
            configuration["enable"] = args.enable
        (inputs / "inputs.json").write_text(json.dumps(configuration, indent=2) + "\n")
        run("gh", "release", "create", "build-inputs", "--repo", args.repository, "--prerelease",
            "--title", "Private build inputs", "--notes", "Stock Sync base and patch settings only; signing key and client ID are stored separately in Actions secrets.",
            inputs / "base.apk", inputs / "inputs.json")
        print("Private builder provisioned: https://github.com/" + args.repository)

if __name__ == "__main__":
    main()
