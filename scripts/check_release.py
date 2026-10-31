#!/usr/bin/env python3
"""Validate immutable release inputs before granting them a published URL."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
from scripts.release_artifacts import ROOT, version, verify_bundle

def check_sums(directory):
    directory = Path(directory)
    expected = {"patches.rvp", "build-info.json"}
    seen = set()
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match:
            raise ValueError("Malformed SHA256SUMS entry")
        checksum, name = match.groups()
        if name in seen:
            raise ValueError("Duplicate checksum entry")
        seen.add(name)
        with (directory / name).open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != checksum:
                raise ValueError("Release checksum mismatch: " + name)
    if not expected.issubset(seen) or sum(name.endswith("-source.zip") for name in seen) != 1 or len(seen) != 3:
        raise ValueError("Release must contain bundle, build information and one source ZIP")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "output")
    args = parser.parse_args()
    current = version()
    if args.tag != "v" + current:
        raise SystemExit("Tag must match VERSION exactly")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tagged = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{args.tag}^{{commit}}"], cwd=ROOT, capture_output=True, text=True)
    if tagged.returncode == 0 and head != tagged.stdout.strip():
        raise SystemExit("Existing tag points to another commit")
    subprocess.run(["git", "merge-base", "--is-ancestor", head, "origin/main"], cwd=ROOT, check=True)
    check_sums(args.output)
    info = json.loads((args.output / "build-info.json").read_text())
    if info["source_commit"] != head or info["version"] != current or info["working_tree_dirty"]:
        raise SystemExit("Release artifact was not built from the clean tagged commit")
    verify_bundle(args.output / "patches.rvp", current)
    print("Verified tag, source revision, bundle structure and release checksums")

if __name__ == "__main__":
    main()
