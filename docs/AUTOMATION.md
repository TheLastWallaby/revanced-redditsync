# Build and update workflow

Each fix is a separate pull request. CI runs the infrastructure tests, every
executable Java test suite under tests/, both compilers, bundle validation, and an
actual ReVanced CLI bundle-load check. Its downloadable artifact contains
`patches.rvp`, a source ZIP, `SHA256SUMS`, and `build-info.json`.

The bundle is cumulative: rebuild from the prepared base each time. Do not apply
new bundles over an APK containing earlier compatibility patches.

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
   signature alone does not establish patch success. The current verifier checks
   the APK's structure and identity, not the success of every patch.
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

## One-time private builder setup

The public repository never contains the base APK, signing key or account
configuration. The private builder is a separate repository, normally
`TheLastWallaby/revanced-redditsync-personal`. Its workflow refuses to handle
signing inputs if the repository is public and never runs on pull requests.

With GitHub CLI signed in with repository/workflow access:

```sh
python3 scripts/setup_private_builder.py \
  --base /private/path/prepared-base.apk \
  --keystore /private/path/original.keystore \
  --signer-sha256 ORIGINAL_INSTALLED_APP_CERTIFICATE_SHA256
```

The script creates the private repository, commits the builder, stores the
existing signing key as `SIGNING_KEY_B64` in Actions secrets, and uploads the
prepared base and its checksums as private release assets. It does not generate
a replacement key. Optional signing passwords/alias are read from the
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables and
sent to GitHub through stdin, never written to source.

Use `--export /private/path/builder` to prepare the same repository files without
changing GitHub. This supports browser-based setup or review before provisioning.

The expected signer fingerprint must match the installed app, not merely a new
key that happens to load. After patching, the private builder verifies the APK's
signature, original signing identity, preservation of all existing classes, and
unchanged non-code resources/manifest. The base APK checksum must match its
private input configuration. A base that already contains this bundle is
rejected.

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
