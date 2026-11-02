#!/usr/bin/env python3
"""Build personal APKs from released RVPs using private inputs and the original key."""
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
from urllib.request import Request, urlopen
import zipfile

PUBLIC_REPOSITORY = "TheLastWallaby/revanced-redditsync"
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?")
ROOT = Path(__file__).resolve().parent

def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def public_json(url):
    request = Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "revanced-redditsync-personal-builder"})
    with urlopen(request, timeout=30) as response:
        return json.load(response)

def fetch_asset(asset, destination, maximum):
    if asset["size"] > maximum or not asset["browser_download_url"].startswith(f"https://github.com/{PUBLIC_REPOSITORY}/releases/download/"):
        raise ValueError("Unexpected release asset")
    subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--proto", "=https",
                    "--proto-redir", "=https", "--retry", "3", "--max-time", "180", "--output",
                    str(destination), asset["browser_download_url"]], check=True)
    if destination.stat().st_size != asset["size"]:
        raise ValueError("Release asset size mismatch")

def validate_bundle(directory, tag):
    directory = Path(directory)
    checksums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match or match[2] in checksums:
            raise ValueError("Malformed release checksums")
        checksums[match[2]] = match[1]
    for name in ("patches.rvp", "build-info.json"):
        if name not in checksums or sha(directory / name) != checksums[name]:
            raise ValueError("Release checksum mismatch: " + name)
    info = json.loads((directory / "build-info.json").read_text())
    if tag != "v" + info["version"] or info["working_tree_dirty"]:
        raise ValueError("Release version or clean-source verification failed")
    with zipfile.ZipFile(directory / "patches.rvp") as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode()
        if f"Version: {info['version']}\r\n" not in manifest:
            raise ValueError("RVP manifest version mismatch")
    return info

def run(*command, env=None, sensitive=False):
    result = subprocess.run(list(map(str, command)), env=env)
    if result.returncode:
        raise SystemExit(("Signing" if sensitive else "Build step") + f" failed (exit {result.returncode})")

def run_logged(*command, env=None, sensitive=False):
    """Run like run(), echoing the combined output as it arrives and returning it."""
    process = subprocess.Popen(list(map(str, command)), env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, errors="replace")
    lines = []
    for line in process.stdout:
        print(line, end="", flush=True)
        lines.append(line)
    if process.wait():
        raise SystemExit(("Signing" if sensitive else "Build step") + f" failed (exit {process.returncode})")
    return "".join(lines)

def enabled_patches(listing):
    """Names of the patches `revanced-cli list-patches` reports as enabled by default."""
    names, name = [], None
    for line in listing.splitlines():
        line = line.strip()
        if line.startswith("Name: "):
            name = line[len("Name: "):].strip()
        elif line.startswith("Enabled: ") and name is not None:
            if line[len("Enabled: "):].strip() == "true":
                names.append(name)
            name = None
    if not names or len(set(names)) != len(names):
        raise ValueError("Could not read the bundle's patch list")
    return names

PATCH_RESULT = re.compile(r'^(?:[A-Z]+: )?"(.+)" (succeeded|failed)\b', re.MULTILINE)

def check_patch_results(log, expected):
    """Require every expected patch, and only those, to report success exactly once.

    ReVanced CLI 6.0.0 can save and sign an APK after a patch fails, so a zero exit
    code or a saved APK does not show that each patch was applied.
    """
    results = {}
    for name, outcome in PATCH_RESULT.findall(log):
        if name in results:
            raise SystemExit(f'Patch "{name}" reported more than one result')
        results[name] = outcome
    failed = sorted(name for name, outcome in results.items() if outcome != "succeeded")
    missing = sorted(set(expected) - set(results))
    unexpected = sorted(set(results) - set(expected))
    if failed or missing or unexpected:
        problems = [f"{label}: {', '.join(names)}" for label, names in
                    [("failed", failed), ("no result", missing), ("not in bundle list", unexpected)] if names]
        raise SystemExit("Patch step did not succeed for every patch (" + "; ".join(problems) + ")")
    return sorted(results)

def set_outputs(built, url, message=None):
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as out:
            out.write("built=" + str(built).lower() + "\n")
            out.write("release_url=" + url + "\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write((message or (("Built and verified" if built else "Already available") + f": [your APK download]({url}).")) + "\n\n")
            out.write("Install the APK as an update and test the affected feature. Device/live Reddit validation is still pending.\n")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default="latest")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--tools", type=Path, default=ROOT / "toolchain")
    parser.add_argument("--output", type=Path, default=ROOT / "output")
    parser.add_argument("--local-bundle", type=Path)
    parser.add_argument("--local-inputs", type=Path)
    parser.add_argument("--local-keystore", type=Path)
    parser.add_argument("--no-publish", action="store_true")
    args = parser.parse_args()
    if args.tag != "latest" and not TAG.fullmatch(args.tag):
        parser.error("Tag must be latest or a version such as v0.6.1")
    args.output.mkdir(parents=True, exist_ok=True)
    private_repo = os.environ.get("GITHUB_REPOSITORY")
    if not args.no_publish:
        if not private_repo or private_repo == PUBLIC_REPOSITORY:
            raise SystemExit("A separate private builder repository is required")
        details = subprocess.check_output(["gh", "api", "repos/" + private_repo], text=True)
        if not json.loads(details)["private"]:
            raise SystemExit("Refusing to handle signing inputs in a public repository")
    with tempfile.TemporaryDirectory(prefix="sync-build-") as temporary:
        temp = Path(temporary)
        bundle_dir = args.local_bundle or temp / "bundle"
        if args.local_bundle:
            info = json.loads((bundle_dir / "build-info.json").read_text())
            tag = "v" + info["version"]
        else:
            selector = "latest" if args.tag == "latest" else "tags/" + args.tag
            release = public_json(f"https://api.github.com/repos/{PUBLIC_REPOSITORY}/releases/{selector}")
            if release["draft"] or not TAG.fullmatch(release["tag_name"]):
                raise ValueError("Unsupported public release")
            tag = release["tag_name"]
            if not any(a["name"] == "build-info.json" for a in release["assets"]):
                set_outputs(False, "", "Waiting for the first automation-enabled public release; older releases lack build information.")
                print("No automation-enabled public release yet")
                return
        private_tag = "sync-" + tag
        if args.force and not args.no_publish:
            private_tag += "-run" + os.environ["GITHUB_RUN_ID"]
        if not args.no_publish:
            existing = subprocess.run(["gh", "release", "view", private_tag, "--repo", private_repo,
                                       "--json", "url", "--jq", ".url"], capture_output=True, text=True)
            if existing.returncode == 0:
                set_outputs(False, existing.stdout.strip())
                return
        if not args.local_bundle:
            bundle_dir.mkdir()
            for name, maximum in [("patches.rvp", 30_000_000), ("SHA256SUMS", 50_000), ("build-info.json", 50_000)]:
                assets = [a for a in release["assets"] if a["name"] == name and a["state"] == "uploaded"]
                if len(assets) != 1:
                    raise ValueError("Release lacks required infrastructure asset: " + name)
                fetch_asset(assets[0], bundle_dir / name, maximum)
        info = validate_bundle(bundle_dir, tag)
        inputs_dir = args.local_inputs or temp / "inputs"
        if not args.local_inputs:
            inputs_dir.mkdir()
            run("gh", "release", "download", "build-inputs", "--repo", private_repo, "--pattern", "base.apk",
                "--pattern", "inputs.json", "--dir", inputs_dir)
        configuration = json.loads((inputs_dir / "inputs.json").read_text())
        base = inputs_dir / "base.apk"
        if sha(base) != configuration["base_apk_sha256"]:
            raise ValueError("Stock base APK checksum differs from private configuration")
        if not re.fullmatch(r"[0-9a-f]{64}", configuration["signer_sha256"]):
            raise ValueError("Expected original signing certificate is missing")
        # Since v0.7.0 the bundle includes the official ReVanced Sync patches: the base is stock Sync.
        with zipfile.ZipFile(base) as archive:
            for name in archive.namelist():
                if re.fullmatch(r"classes[0-9]*\.dex", name) and b"Lapp/revanced/" in archive.read(name):
                    raise ValueError("Base already contains ReVanced patches; use stock Sync 23033")
        client_id = os.environ.get("REDDIT_CLIENT_ID", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", client_id):
            raise SystemExit("REDDIT_CLIENT_ID has not been configured")
        # Spoof client options and extra patches (such as "Use /user/ endpoint") from inputs.json.
        spoof = configuration.get("spoof_client", {})
        extra = configuration.get("enable", [])
        if not isinstance(spoof, dict) or not isinstance(extra, list) or not all(isinstance(n, str) for n in extra):
            raise ValueError("Unsupported spoof_client/enable configuration")
        options = {"OAuth client ID": client_id}
        for key, option in [("redirect_uri", "Redirect URI"), ("user_agent", "User-Agent")]:
            if spoof.get(key):
                options[option] = str(spoof[key])
        keystore = args.local_keystore or temp / "signing.keystore"
        if not args.local_keystore:
            encoded = os.environ.get("SIGNING_KEY_B64", "")
            if not encoded:
                raise SystemExit("SIGNING_KEY_B64 has not been configured")
            keystore.write_bytes(base64.b64decode(encoded, validate=True))
            keystore.chmod(0o600)
        if not keystore.is_file():
            raise SystemExit("Original signing key is missing; refusing to create another")
        tools = args.tools.resolve()
        apk = args.output / f"Sync-v23.06.30-compat-{tag}.apk"
        java = str(Path(shutil.which("java")).resolve())
        signing_env = os.environ.copy()
        signing_env.pop("SIGNING_KEY_B64", None)
        signing_env.pop("REDDIT_CLIENT_ID", None)
        command = [java, "-XX:-UsePerfData", "-jar", tools / "revanced-cli.jar", "patch",
                   "-p", bundle_dir / "patches.rvp", "-b", "-o", apk, "-t", temp / "patch",
                   "--keystore", keystore]
        for key, option in [("KEYSTORE_PASSWORD", "--keystore-password"),
                            ("KEY_ALIAS", "--keystore-entry-alias"), ("KEY_PASSWORD", "--keystore-entry-password")]:
            if os.environ.get(key):
                command += [option, os.environ[key]]
        for option, value in options.items():
            command += ["-O", f"{option}={value}"]
        command += ["-e", "Spoof client"]
        for name in extra:
            command += ["-e", name]
        command.append(base)
        listing = subprocess.run([java, "-XX:-UsePerfData", "-jar", str(tools / "revanced-cli.jar"), "list-patches",
                                  "-p", str(bundle_dir / "patches.rvp"), "-b"],
                                 capture_output=True, text=True, errors="replace", check=True)
        expected = sorted(set(enabled_patches(listing.stdout + listing.stderr)) | set(extra))
        patched = check_patch_results(run_logged(*command, env=signing_env, sensitive=True), expected)
        print(f"All {len(patched)} patches succeeded: " + ", ".join(patched), flush=True)
        report = args.output / "verification.json"
        verifier = ROOT / "VerifyPersonalApk.java"
        if not verifier.is_file():
            verifier = ROOT.parent / "tools/VerifyPersonalApk.java"
        run(java, "-Xmx2G", "-cp", tools / "revanced-cli.jar", verifier,
            base, apk, configuration["signer_sha256"], report)
        record = json.loads(report.read_text())
        record.update({"public_release": tag, "source_commit": info["source_commit"], "apk_sha256": sha(apk),
                       "patches_succeeded": patched})
        report.write_text(json.dumps(record, indent=2) + "\n")
        sums = args.output / "SHA256SUMS"
        sums.write_text(f"{sha(apk)}  {apk.name}\n{sha(report)}  {report.name}\n")
        if args.no_publish:
            print("Personal APK verified: " + str(apk))
        else:
            run("gh", "release", "create", private_tag, "--repo", private_repo, "--title", "Sync " + tag,
                "--notes", f"Personal APK from {PUBLIC_REPOSITORY} {tag}. Signature and base checks passed; device test pending.",
                apk, report, sums)
            url = subprocess.check_output(["gh", "release", "view", private_tag, "--repo", private_repo,
                                           "--json", "url", "--jq", ".url"], text=True).strip()
            set_outputs(True, url)

if __name__ == "__main__":
    main()
