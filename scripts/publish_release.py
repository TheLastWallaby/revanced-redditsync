#!/usr/bin/env python3
"""Publish a new release without replacing a previous release or its assets."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from scripts.release_artifacts import ROOT, version

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    if args.tag != "v" + version():
        raise SystemExit("Tag does not match VERSION")
    repository = os.environ["GITHUB_REPOSITORY"]
    existing = subprocess.run(["gh", "release", "view", args.tag, "--repo", repository], capture_output=True)
    if existing.returncode == 0:
        raise SystemExit("Release already exists; refusing to replace existing assets")
    output = ROOT / "output"
    assets = [output / "patches.rvp", output / "SHA256SUMS", output / "build-info.json", *output.glob("*-source.zip")]
    if len(assets) != 4 or any(not p.is_file() for p in assets):
        raise SystemExit("Expected exactly four release assets")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tagged = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{args.tag}^{{commit}}"], cwd=ROOT, capture_output=True, text=True)
    if tagged.returncode != 0:
        subprocess.run(["gh", "api", f"repos/{repository}/git/refs", "-f", "ref=refs/tags/" + args.tag,
                        "-f", "sha=" + head], check=True, stdout=subprocess.DEVNULL)
    elif tagged.stdout.strip() != head:
        raise SystemExit("Existing tag differs from tested source")
    command = ["gh", "release", "create", args.tag, "--repo", repository, "--verify-tag",
               "--title", args.tag, "--draft", "--generate-notes", *map(str, assets)]
    subprocess.run(command, check=True)
    subprocess.run(["gh", "release", "edit", args.tag, "--repo", repository, "--draft=false",
                    "--prerelease=" + ("true" if "-" in args.tag else "false")], check=True)
    print("Published " + args.tag)

if __name__ == "__main__":
    main()
