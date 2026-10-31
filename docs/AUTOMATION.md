# Build and update workflow

Each fix is a separate pull request. CI runs the infrastructure tests, every
executable top-level Java test suite, both compilers, bundle validation, and an
actual ReVanced CLI bundle-load check. Its downloadable artifact contains
`patches.rvp`, a source ZIP, `SHA256SUMS`, and `build-info.json`.

The bundle is cumulative: rebuild from the prepared base each time. Do not apply
new bundles over an APK containing earlier compatibility patches.

## Regular use

1. Ask Codex to fix one issue. The repository's `AGENTS.md` requires a built RVP
   and validation evidence, not just source edits.
2. Check the PR's CI result and merge the accepted change. Device/live Reddit
   checks remain separate from automated build checks.
3. Set `VERSION` to the intended next release in the release-preparation change.
   Open **Actions → Publish patch release → Run workflow**, use `main`, and enter
   the matching version tag, for example `v0.6.1`.
4. The workflow rebuilds and tests the exact revision, creates its version tag if
   needed, publishes the four release assets, and refreshes both Manager feeds.
5. A provisioned private builder checks twice an hour and makes your signed APK
   available under its private **Releases** page. You can also run **Build my Sync
   APK** manually to check immediately or choose a specific release.
6. Install that APK as an update and test the changed feature. Keep the previous
   working APK for recovery. Reporting a fix as device-tested still needs an
   actual phone test.

Existing version tags and releases are never replaced. A prerelease version such
as `0.7.0-rc.1` updates the `latest` feed; it does not replace the stable feed.
The private builder follows stable releases automatically; a manual run can
select a prerelease.

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
- Keep Java test mains under `tests/*Test.java` or extend discovery when adding
  another test layout. Python infrastructure tests use `tests/test_*.py`.
- Public workflow permissions are read-only except release publishing and feed
  updates. No private signing secrets are passed to public builds.
- If main requires pull requests for all changes, let the feed updater submit a
  PR instead of granting the workflow a branch-protection bypass.
- A patch that intentionally modifies the manifest or resources must update the
  APK verifier with a narrow reviewed allowance before personal builds accept it.
- GitHub Actions checks compilation and fixtures, not Reddit account capability
  or Android UI behavior. Preserve this distinction in release notes.
