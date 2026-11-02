# Build and update workflow

Each fix is a separate pull request. CI runs the infrastructure tests, every
executable Java test suite under tests/, both compilers, bundle validation, and an
actual ReVanced CLI bundle-load check. Its downloadable artifact contains
`patches.rvp`, a source ZIP, `SHA256SUMS`, and `build-info.json`.

The bundle is cumulative: rebuild from the base each time. Do not apply new
bundles over an APK containing earlier compatibility patches. Since v0.7.0 the
bundle includes ports of the official ReVanced Sync patches, so the base is
**stock** Sync v23.06.30-13:39 (version code 23033); an APK that already contains
ReVanced code is rejected. Releases before v0.7.0 used a ReVanced-prepared base.

## Regular use

1. Ask Codex to fix one issue. The repository's `AGENTS.md` requires a built RVP
   and validation evidence, not just source edits.
2. Check the PR's CI result and merge the accepted change. Device/live Reddit
   checks remain separate from automated build checks.
3. For a new fix awaiting phone testing, set `VERSION` to an unused release
   candidate, such as `0.6.1-rc.1`, in the release-preparation change.
   Open **Actions → Publish patch release → Run workflow**, use `main`, and enter
   the matching version tag, for example `v0.6.1-rc.1`.
4. The workflow rebuilds and tests the exact revision, creates its version tag if
   needed, publishes the four release assets, and refreshes both Manager feeds.
5. In `TheLastWallaby/revanced-redditsync-personal`, manually run **Actions → Build
   my Sync APK → Run workflow** from its trusted default branch. Enter the exact
   candidate tag in `tag`; keep `force` false for the first build. Scheduled runs
   check twice an hour for stable releases and will not pick up this candidate.
6. Check the private build's patch-step logs: **every selected named patch must
   report success**. Then check `verification.json` for the original signing
   identity, preserved classes and unchanged resources/manifest. The build also
   checks the configured base checksum. CLI 6.0.0 can save/sign an APK after a
   patch error; a saved file, successful process exit, green workflow or valid
   signature alone does not establish patch success. The builder stops before
   publishing unless every patch the bundle enables reports `succeeded` exactly
   once, and records them as `patches_succeeded` in `verification.json`. The APK
   verifier itself checks the APK's structure and identity, not patch success.
7. Install that APK as an update and test the changed feature. Keep the previous
   working APK for recovery. Reporting a fix as device-tested still needs an
   actual phone test.
8. After the user confirms the candidate works and stable publication is
   authorized, set `VERSION` to the stable version, rerun the checks, merge the
   release-preparation change and publish the matching stable tag. This is a new
   build, not a rename of the candidate. The scheduled private builder will pick
   up the stable release; a manual run with its exact tag can build it immediately.

Existing version tags and releases are never replaced. A prerelease version such
as `0.7.0-rc.1` updates the `latest` feed; it does not replace the stable feed.
The private builder follows stable releases automatically; a manual run can
select a prerelease.

## AI agent completion criteria

Read `AGENTS.md` and this document at the start of a task. An issue fix must
produce a cumulative RVP and evidence that the intended tests executed; compiling
a test class alone is insufficient. Tests with a Java package declaration need
fully qualified class names when launched. Extend discovery if adding a layout
the current runner cannot execute.

A fix request normally ends at the PR with passing checks and a downloadable
bundle. When the user also requests merging, a candidate release or a private APK,
complete those authorized steps without asking for duplicate permission. Finish
that handoff with links to the PR, passing CI run, candidate release, private build
run and private APK download, and a short phone-test checklist. If a step is
blocked, name the exact remaining step and preserve completed work. Keep private
APK links and signing/build inputs out of public PRs and release notes.

Use the CI/release artifacts (`patches.rvp`, source ZIP, `SHA256SUMS`,
`build-info.json`) rather than old `dist/` candidates. Keep source-only, automated
build, patch-application, signing and phone-test evidence distinct. Instructions
and template maintenance do not require a new patch version or release.

## Local or Codex environment

Java 17, Python 3.12 and curl are sufficient to start. All remaining tools are
downloaded at the locked versions and verified by SHA-256:

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 build.py --bootstrap
```

The original `--tools /path/to/tools` entry point remains supported. Every clean
build removes old compiler outputs so deleted code cannot remain in a bundle.
Archive timestamps use the source commit time. CI artifacts are retained for 30
days; published releases remain available after those preview artifacts expire.

On Windows, run the same commands with `python`. To read Sync's own code for a
fix, run `python3 scripts/inspect_base.py` with `gh` access to the private
builder. It downloads `base.apk` and `inputs.json` from the private
`build-inputs` release, checks the configured base checksum and that the base
holds no ReVanced patches (stock Sync), and disassembles every DEX file into the
ignored `local-inspect/smali/` with the baksmali included in the pinned ReVanced
CLI. The APK and its smali never leave that ignored directory.

## One-time private builder setup

The public repository never contains the base APK, signing key or account
configuration. The private builder is a separate repository, normally
`TheLastWallaby/revanced-redditsync-personal`. Its workflow refuses to handle
signing inputs if the repository is public and never runs on pull requests.

With GitHub CLI signed in with repository/workflow access:

```sh
REDDIT_CLIENT_ID=YOUR_REDDIT_CLIENT_ID python3 scripts/setup_private_builder.py \
  --base /private/path/stock-sync-23033.apk \
  --keystore /private/path/original.keystore \
  --signer-sha256 ORIGINAL_INSTALLED_APP_CERTIFICATE_SHA256 \
  --redirect-uri YOUR_REGISTERED_REDIRECT_URI
```

The script creates the private repository, commits the builder, stores the
existing signing key as `SIGNING_KEY_B64` and the Reddit OAuth client ID (from the
`REDDIT_CLIENT_ID` environment variable) as `REDDIT_CLIENT_ID` in Actions secrets,
and uploads the stock base, its checksums and the optional Spoof client settings
(`--redirect-uri`, `--user-agent`) and extra patches (`--enable`, for example
`"Use /user/ endpoint"`) as private release assets. It does not generate
a replacement key. Optional signing passwords/alias are read from the
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables and
sent to GitHub through stdin, never written to source.

Use `--export /private/path/builder` to prepare the same repository files without
changing GitHub. This supports browser-based setup or review before provisioning.

The expected signer fingerprint must match the installed app, not merely a new
key that happens to load. After patching, the private builder verifies the APK's
signature, original signing identity, preservation of all existing classes, and
unchanged non-code resources/manifest. The base APK checksum must match its
private input configuration. A base that already contains ReVanced code is
rejected. The store's source stamp (`stamp-cert-sha256`), which re-signing
removes, is treated as a signing file.

### Moving an existing private builder to the stock base (v0.7.0)

Builders set up before v0.7.0 hold a ReVanced-prepared base. Before building the
first v0.7.0 candidate, with the owner's approval:

1. Sync the changed builder files (`build_apk.py`, the workflow, README.md,
   AGENTS.md and `VerifyPersonalApk.java`) using the mapping below.
2. Add the `REDDIT_CLIENT_ID` Actions secret with the client ID the current app
   uses.
3. Replace `base.apk` in the `build-inputs` release with stock Sync 23033 and
   update `inputs.json`: the stock checksum, the unchanged signer, and
   `spoof_client`/`enable` values that reproduce the old base's settings
   (redirect URI, User-Agent, and any default-off patches it used).

Building a pre-v0.7.0 tag afterwards needs the old prepared base again.

The initial v0.6.0 public release predates `build-info.json`. It remains unchanged;
automatic personal builds start with the next release produced by this
infrastructure. Until then the scheduled builder waits without generating a
failed or unverified update.

## Maintenance

- Update toolchain versions and their actual verified hashes together in
  `toolchain.lock.json`. Cached tools are rechecked before reuse.
- Java test mains are discovered recursively under `tests/**/*Test.java`.
  Package declarations determine the fully qualified launch names, which are
  recorded in `build-info.json`; extend discovery for other test layouts.
  Python infrastructure tests use `tests/test_*.py`.
- Java sources compile as UTF-8 on every platform. Production patch hooks compile
  before the Java suites; the tests can use those classes and the pinned CLI's
  Patcher/Smali APIs. `MarkdownPatchTest` assembles `tests/fixtures/markdown`,
  applies the production patch and executes its resulting string-method DEX
  instructions with JVM APIs. Its optional APK argument runs the same fenced-code
  and spoiler checks against a local build-23033 base, without signing an APK.
- ReVanced CLI applies the bundle's patches sorted by name, so renaming a patch
  can change the order. Patches that edit the same Sync method must give the
  same result in any order. `PatchInteractionTest` checks the shared LinkHelper
  entry on fixtures in CI. Given a local stock APK
  (`local-inspect/inputs/base.apk` from `scripts/inspect_base.py`), it applies
  every named patch, including the bundled official ones, each after its
  dependencies, in name order and reverse order. Each must succeed both ways,
  and every changed class must be identical. Run that before merging a patch
  that edits a method another patch already changes.
- `src/patches/OfficialSyncPatches.kt` ports the official ReVanced Sync patches
  (ReVanced Patches v6.1.0; originals in `upstream/revanced-patches-v6.1.0/`).
  Patches that build on official code depend on the port, so it is applied
  first. `OfficialSyncPatchTest` applies the ports with their dependencies,
  including the real extension merge, to stock-layout fixtures in CI and, given
  a local stock APK, to the real classes. The builder copies the extension DEX to
  `build/test-resources/extensions/synccompat.rve` on the test classpath for this.
- Public workflow permissions are read-only except release publishing and feed
  updates. No private signing secrets are passed to public builds.
- If main requires pull requests for all changes, let the feed updater submit a
  PR instead of granting the workflow a branch-protection bypass.
- A patch that intentionally modifies the manifest or resources must update the
  APK verifier with a narrow reviewed allowance before personal builds accept it.
- GitHub Actions checks compilation and fixtures, not Reddit account capability
  or Android UI behavior. Preserve this distinction in release notes.

### Keep the private repository and template aligned

The private repository is provisioned once; it does not automatically pull later
template changes. Keep a root `AGENTS.md` in both repositories. The public file
governs patch development; the private file governs building signed personal APKs.

| Public source | Private repository destination |
|---|---|
| `private-builder/AGENTS.md` | `AGENTS.md` |
| `private-builder/README.md` | `README.md` |
| `private-builder/build-apk.yml` | `.github/workflows/build-apk.yml` |
| `private-builder/build_apk.py` | `build_apk.py` |
| `scripts/bootstrap_toolchain.py` | `bootstrap_toolchain.py` |
| `tools/VerifyPersonalApk.java` | `VerifyPersonalApk.java` |
| `toolchain.lock.json` | `toolchain.lock.json` |

Use `python3 scripts/setup_private_builder.py --export /path/to/fresh-template`
to prepare files for comparison without creating a repository or changing its
secrets/releases. Inspect the deployed files and copy only intended updates,
preserving private customizations. Do not use the provisioning command to update
an existing builder. Its original signing secrets and `build-inputs` assets stay
in place. For public template changes, run the infrastructure tests and build;
for deployed executable/workflow changes, also verify a private workflow run.
Documentation-only changes do not require signing another APK.

An agent without private-repository access must report that limitation; a template
update is not evidence that the deployed private builder was updated. Do not copy
private inputs into the public repository to work around access problems.
