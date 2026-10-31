# revanced-redditsync

Unofficial ReVanced compatibility patches for **Sync for Reddit
v23.06.30-13:39**, version code **23033**, free package
`com.laurencedawson.reddit_sync`.

The published v0.6 patch code was reported working by its tester. The source
now also prepares v0.6.1 with the share-link security fix described below.
This is a community/local bundle rather than an official ReVanced release.
Compatibility with Pro/dev packages, other Sync builds, or all account/post
combinations has not been established.

## Development — v0.6.1 share-link security fix

Adds **Sync: secure Reddit share links** for [issue #1](https://github.com/TheLastWallaby/revanced-redditsync/issues/1).
The inherited ReVanced `/s/` resolver now parses the actual URI before any
authentication or account-startup action. It accepts only HTTPS, the default
port or explicit `443`, no user-info, and a complete `/r/<subreddit>/s/<token>`
path with an optional trailing slash, query and fragment. Allowed hosts are
exactly `reddit.com`, `www.reddit.com`, `old.reddit.com`, `new.reddit.com`,
`sh.reddit.com`, `m.reddit.com` and `np.reddit.com`.

Redirects are handled explicitly, with at most five HEAD requests. Every
destination must pass the same origin validation. Authorization is permanently
dropped after a hop to another origin, including another allowed Reddit host.
Relative redirects work; a validated final post URL is handed to Sync without
another authenticated request. Failures keep the existing `#bypass` browser
fallback for the validated original share URL. The new resolver does not log
URLs, tokens, headers, account data or transport exception details.

The existing token setter and pending-link lifecycle are preserved. The patch
requires the inherited ReVanced resolver from the supported base; it reports
an error if that layout is missing or the security patch is already installed.
CLI 6.0.0 can still save an APK after a patch error: use only output for which
all three named patches report success.

Build with the tools below to produce
`output/sync-gallery-comments-compat-v0.6.1.rvp`. A tested candidate is also in
`dist/sync-gallery-comments-compat-v0.6.1.rvp`, with its own `.sha256` file.
Use a clean ReVanced-patched Sync 23033 free base with none of this repository's
compatibility patches, and apply all **three** named patches. Do not layer the
complete bundle over an existing v0.6 APK.

Validation: 746 stub-transport security checks and all 92 existing
Volley/loopback checks pass. The candidate was applied using CLI 6.0.0 / Patcher
22.0.0 to the supported base. APK verification preserves all 29,148 existing
classes, changes only the four existing compatibility targets plus the
inherited share-link resolver, and leaves all 3,116 non-code entries identical.
The test APK uses a disposable test signing key. Live account resolution,
cold-start/account-switch UI, Android Manager and phone testing remain
unverified. See `validation-share-links.json` for the recorded evidence.

The published downloads and Manager feeds below still point to v0.6.0 until a
new release is published.

## Downloads — v0.6.0

- [Latest release](https://github.com/TheLastWallaby/revanced-redditsync/releases/latest)
- [Download patches.rvp](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.6.0/patches.rvp)
- [Complete source ZIP](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.6.0/sync-gallery-comments-compat-v0.6.0-source.zip)
- [SHA-256 checksums](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.6.0/SHA256SUMS)

The expanded source is also available in this repository. The ZIP is the
original v0.6.0 sharing package; the repository adds release and remote-bundle
instructions. `patches.rvp` is byte-for-byte identical to the versioned `.rvp`
in `dist/`. Supply your own base APK and signing key when applying the patches.

## ReVanced Manager remote bundle

Add this JSON URL as a remote patch bundle:

```
https://raw.githubusercontent.com/TheLastWallaby/revanced-redditsync/main/bundles/stable.json
```

In Manager versions with the custom bundle editor, open **Patches**, tap the
**pencil icon**, choose **Add patches** → **Enter URL**, and paste the JSON URL.
The metadata points to the latest stable release's `patches.rvp`. You can also
download that file from Releases and import it as a local patch bundle.

`bundles/latest.json` is also provided and may include future prereleases.
Use the Sync base and patch selections described below. The validated patching
route is the CLI; Manager import and patching still need device verification.

## Included patches — published v0.6.0

- **Sync: fix Reddit galleries** loads native Reddit galleries through the
  comment-page endpoint and adapts the post listing for Sync's existing parser.
- **Sync: load more comments in batches** uses normal comment routes for up to
  100 requested IDs per tap, with eight concurrent additional requests. It
  preserves failed and untouched IDs, parent relationships and nested retries.

Version 0.6 treats an ID as completed with no available comment only when both
routes return HTTP 200, identify the expected post, and contain genuinely empty
comment listings. A more marker, unrelated comment, wrong post, unknown shape,
or failed request keeps the ID pending. Empty final continuations use Sync's
existing completion path. Refreshing the post fetches its continuations again.

The loading-details dialog remains available on failures. Its Copy details
button exports fixed labels, numeric counts and statuses, excluding account
tokens, headers, usernames, comment bodies, URLs and actual post/comment IDs.

## Apply the published v0.6.0 RVP

1. Select a **ReVanced-patched Sync 23033 free APK** with your usual OAuth/client
   configuration and the ReVanced shared extension already present.
2. Use a base that has **none of this bundle's earlier compatibility patches**.
   Do not reapply to an APK already patched by versions 0.1 through 0.6.
3. Load the downloaded `.rvp` (or `dist/sync-gallery-comments-compat-v0.6.0.rvp`
   in a clone of this repository) and apply its two named patches.
4. Sign the result with your own keystore. Matching the key used by your current
   installation allows an in-place update.

This bundle adds the gallery/comment fixes. It expects the existing ReVanced
shared Logger class; its source stub is for compilation only. It does not
supply OAuth configuration or unrelated Sync patches such as RedGifs fixes.

The validated route is **ReVanced CLI 6.0.0 / Patcher 22.0.0**. Android Manager
import/loading has not been tested. Example CLI command from a clone of this
repository, using your own paths for the other files:

```sh
java -jar revanced-cli-6.0.0-all.jar patch \
  -p dist/sync-gallery-comments-compat-v0.6.0.rvp -b \
  --keystore your-signing-key.keystore \
  -o Sync-v23.06.30-gallery-comments-v0.6.apk \
  Sync-v23.06.30-revanced-base.apk
```

Supply the CLI's password and alias options if your keystore requires them.
`-b` loads this custom unsigned patch bundle. Keep signing keys private.

## Source and building

- `src/patches/SyncCompatPatches.kt`: standalone ReVanced patch hooks.
- `src/extension/`: the Java runtime helpers.
- `src/stubs/`: compile-only shared Logger signatures, excluded from the APK.
- `tests/`: JVM/loopback HTTP tests and minimal Android test adapters.
- `tools/UpdateVerifier.java`: APK/code/signature verification.
- `upstream/`: original files from the upstream implementation.
- `changes-from-v0.5.1.diff`: this version's runtime changes.
- `validation.json`: recorded checks, output digest and user test status.
- `validation-share-links.json`: v0.6.1 security checks and APK verification.
- `bundles/`: ReVanced Manager remote-bundle metadata.
- `scripts/update_bundle_json.py`: refreshes the metadata from published releases.

Use Java 17, ReVanced CLI 6.0.0 (Patcher 22.0.0), Kotlin compiler 2.4.21,
R8 8.3.37, JSON-java 20240303, Volley 1.2.1 classes.jar from its AAR, and Android
4.1.1.4 android.jar for compile signatures. Dependency jars are not included.

Provide a tools directory with `revanced-cli.jar`, `r8.jar`, `json.jar`,
`android.jar`, `volley.jar`, and `kotlin/kotlinc/lib`, then run:

```sh
python3 build.py --tools /absolute/path/to/tools
```

Add `--apk /path/to/revanced-base.apk --keystore /path/to/your.keystore` to
build and sign an APK using the CLI's default key password settings.

To inspect a locally built v0.6.1 test APK, compile `tools/UpdateVerifier.java`
with `revanced-cli.jar` on the classpath, then run:

```sh
java -cp verifier-classes:/path/to/tools/revanced-cli.jar UpdateVerifier \
  /path/to/revanced-base.apk /path/to/patched.apk - build/dex-extension/classes.dex
```

Replace `-` with a previous APK path to additionally require a matching signing
certificate. A disposable-key smoke APK cannot establish update compatibility
with an existing installation.

## Publishing a release

1. Build and validate the patch, then create a versioned GitHub release.
2. Attach the `.rvp` as **`patches.rvp`**, along with the complete source ZIP and
   `SHA256SUMS`. Keep the runtime version and release tag in sync.
3. Run `python3 scripts/update_bundle_json.py` and commit `bundles/*.json`.
   The script uses GitHub's public API and requires no token. For an already
   fetched API response, pass `--releases-json /path/to/releases.json`.

The stable feed follows the newest published regular release with an uploaded
`patches.rvp`; the latest feed also considers prereleases. Drafts are excluded.

## Validation

92 checks passed using actual Volley 1.2.1 NetworkResponse and HttpURLConnection
against a loopback HTTP server. Tests cover verified empties, more-only results,
HTTP failures, wrong posts, malformed data, retry preservation, batch boundaries,
parent metadata, deduplication, metadata and diagnostic privacy.

The APK verifier preserved all 29,148 existing classes, limited modifications
to four intended app classes and limited added classes to the helper DEX.
All 3,189 non-code entries, including the manifest and resources, were identical
apart from signing metadata.

The tester subsequently reported v0.6 working on their phone on 2026-10-30.
That is a manual confirmation for the tested case, not exhaustive Android or
live-service coverage. The original server response bodies were not captured.
This standalone bundle does not represent a full upstream Gradle/CI build.

## Sharing and license

Share the RVP and this complete source ZIP together, retaining the licenses.
Neither contains an APK, OAuth client ID, private signing key or personal
account data. Dummy authorization strings and privacy markers in tests are
artificial fixtures.

The batch design derives from ReVanced merge request !6999 at commit
`f33036fe57220442e5c58077f9f73d878b158681`, plus local compatibility fixes:
https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/6999

The combined source uses GPL-3.0. Original authored compatibility code also
retains its MIT notice in LICENSE-MIT. Existing app code and dependencies
retain their own licenses.
