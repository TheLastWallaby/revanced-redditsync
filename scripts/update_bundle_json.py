#!/usr/bin/env python3
"""Update Manager bundle feeds from this repository's published GitHub releases."""

import argparse
import json
from pathlib import Path
from urllib.request import Request, urlopen

REPOSITORY = "TheLastWallaby/revanced-redditsync"
PATCH_ASSET = "patches.rvp"
BUNDLES = Path(__file__).resolve().parents[1] / "bundles"


def patch_asset(release):
    return next(
        (asset for asset in release.get("assets", [])
         if asset.get("name") == PATCH_ASSET and asset.get("state") == "uploaded"),
        None,
    )


def update_feeds(releases):
    candidates = sorted(
        (release for release in releases
         if not release.get("draft") and release.get("published_at")
         and patch_asset(release)),
        key=lambda release: release["published_at"],
        reverse=True,
    )
    if not candidates:
        raise SystemExit("No published release with an uploaded patches.rvp was found.")

    channels = {
        "stable": next((release for release in candidates
                        if not release.get("prerelease")), None),
        "latest": candidates[0],
    }
    BUNDLES.mkdir(exist_ok=True)
    for channel, release in channels.items():
        if release is None:
            print(f"{channel}: no regular release; existing feed left unchanged")
            continue
        asset = patch_asset(release)
        metadata = {
            "created_at": release["published_at"].removesuffix("Z"),
            "description": release.get("body") or "",
            "download_url": asset["browser_download_url"],
            "signature_download_url": "N/A",
            "version": release["tag_name"],
        }
        path = BUNDLES / f"{channel}.json"
        path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        print(f"{channel}: {release['tag_name']} -> {asset['browser_download_url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--releases-json", type=Path,
                        help="Read a saved GitHub releases API response instead of fetching it.")
    args = parser.parse_args()
    if args.releases_json:
        releases = json.loads(args.releases_json.read_text(encoding="utf-8"))
    else:
        request = Request(
            f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100",
            headers={"Accept": "application/vnd.github+json",
                     "User-Agent": "revanced-redditsync-bundle-updater"},
        )
        with urlopen(request, timeout=30) as response:
            releases = json.load(response)
    if not isinstance(releases, list):
        raise SystemExit("Expected a GitHub releases API response containing a list.")
    update_feeds(releases)


if __name__ == "__main__":
    main()
