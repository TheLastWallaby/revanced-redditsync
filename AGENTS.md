# Working on Sync compatibility patches

This repository distributes a cumulative ReVanced patch bundle for the free Sync
23033 package. Since v0.7.0 it includes ports of the official ReVanced Sync patches
(src/patches/OfficialSyncPatches.kt) and is applied to stock Sync in one pass.
A source edit alone is not a completed fix.

- Read the issue, README, docs/AUTOMATION.md and relevant helpers before editing.
  Start from current main. Update an existing issue PR instead of opening a
  duplicate, and preserve the build/release infrastructure when resolving conflicts.
  Preserve gallery/comment behavior, continuation parents, retries and diagnostic privacy.
- If the main checkout has uncommitted changes, do the issue in a separate git
  worktree from current main (for example ../revanced-redditsync-issue<N>) and
  leave those changes untouched.
- When a fix depends on Sync's own code (method signatures, call sites, helpers
  such as rate or token handling), inspect the base instead of guessing:
  python3 scripts/inspect_base.py downloads base.apk and inputs.json from the
  private build-inputs release, checks the configured base checksum and that no
  ReVanced patch is present, and writes smali to the ignored local-inspect/.
  The base is stock Sync; official-patch edits come from this bundle's ports. Never
  commit, attach or publish that APK or its disassembly. If private access is
  unavailable, say which code could not be inspected.
- Add a named, optional patch for unrelated functionality. Test the actual logic
  being changed. Do not assume code presence proves live Reddit functionality.
- Run: python3 -m unittest discover -s tests -p 'test_*.py' -v
- Run: python3 build.py --bootstrap
  Run both locally before pushing whenever Java 17 and Python 3.12 are available;
  CI confirms a passing build rather than debugging one. On Windows use python
  (Git Bash has no python3). VideoPreviewLoadingTest's placeholder-repair check
  fails on Windows only, because File.renameTo cannot replace an existing file
  there; Linux CI (like Android) is authoritative for that check.
  The build installs the locked tools, executes Java *Test.java mains under tests/,
  compiles both DEX files, validates the RVP and loads it with ReVanced CLI.
  Confirm every intended suite actually ran. Package-declared suites must execute
  by fully qualified class name; extend discovery and its regression coverage if
  introducing a test layout the runner does not support. Compilation is not execution.
- Patches run in name order. When a patch edits a Sync method that another patch
  already edits, run PatchInteractionTest with local-inspect/inputs/base.apk (see
  docs/AUTOMATION.md) so every patch succeeds in either order with the same result.
- Provide the downloadable CI artifact/run link and test evidence in the PR.
  Canonical outputs are output/patches.rvp, the source ZIP, SHA256SUMS and
  build-info.json. Do not direct users to stale dist/ candidates as new CI outputs.
  State any missing device or authenticated Reddit validation clearly.
- VERSION is the only release-version source. Bump it for a patch release when
  preparing an actual release; do not publish or replace a release just to open a PR.
- Update the builder if additional compiler inputs or tests are introduced.
  Keep tool downloads pinned by SHA-256 in toolchain.lock.json.
- Never commit an APK, account credentials, signing key or secret. Public CI builds
  RVP/source artifacts only; personal APK signing runs in a private repository.
- Use stock Sync 23033 (v23.06.30-13:39) with no ReVanced patches as the base.
  Never stack a new cumulative bundle over an already patched APK. Patches that
  build on official ReVanced code must depend on the bundled port.
- Verify every selected named patch reports success when making a test APK.
  ReVanced CLI 6.0.0 can save/sign an APK after a patch error; exit status, an
  output file, a green workflow or a valid signature alone is insufficient.
  Check the patch-step logs as well as the signing/base verification report.
- Keep independently developed fixes in separate PRs. Do not merge another fix as
  part of infrastructure work.

## Test conventions

- JVM tests use small Android stand-ins under tests/android/ (android.net.Uri,
  android.text.TextUtils, android.util.Log). Extend these rather than adding an
  Android runtime; Log lets real Volley Request objects be constructed.
- Network behavior is tested against a loopback com.sun.net.httpserver server with
  test-only tokens, never live Reddit or real accounts.
- Limits a test must shorten (budgets, deadlines, timeouts) are non-final static
  fields set by reflection; javac inlines static final primitives.
- Print a one-line PASS summary with the check count so CI logs show each suite ran.

## Release and phone-test handoff

- A normal fix task delivers a PR, passing checks and a built cumulative RVP.
  Merge/release/private-build actions follow the user's requested scope. If those
  actions are already authorized, carry them through without asking again.
- For a requested test release, set VERSION to an unused candidate version such
  as 0.6.1-rc.1, merge after the latest PR checks pass when authorized, and run
  Publish patch release on main with the matching v-prefixed tag. Existing tags
  and releases are immutable. Do not bump VERSION for an instructions-only change.
- Manually run Build my Sync APK in TheLastWallaby/revanced-redditsync-personal
  with that exact candidate tag. Its schedule follows stable releases only.
  A public RVP build is not proof that the private APK build passed.
- Check all selected patch results and the private verification report: base
  checksum, original signing identity, preserved classes and unchanged resources/
  manifest. Current APK verification does not itself prove each patch succeeded.
- Return the PR, passing CI run, candidate release, private build run and private
  APK download links, plus concise numbered checks for the changed feature on the
  phone. Keep personal APK links in the private handoff, not public PRs or release notes.
- Ask for a result per numbered check and record those results, or record that
  only an overall result was given, in validation-phone-tests.json.
- Leave the stable release until the user confirms the candidate works on the
  phone and stable publication is authorized. A reply such as "looks good"
  confirms the candidate; ask "publish stable vX.Y.Z?" unless publication was
  already authorized. Promotion uses a separate VERSION change and release build;
  do not rename or overwrite the candidate assets.
- Merge the stable promotion PR only once publication is authorized, then publish
  the stable release immediately, so main's README never links to a missing
  release. If publishing is then blocked, report it and offer to revert the merge.

## Private builder maintenance

- private-builder/ is the public source template for the separate private repo.
  Its AGENTS.md must be exported as the private repository's root AGENTS.md.
- When changing copied files, follow the mapping in docs/AUTOMATION.md, compare
  the deployed private files, and sync the relevant changes when that work is in
  scope. Updating the public template alone does not update an existing private repo.
- Preserve deployed private customizations, original signing secrets and the
  build-inputs release. Do not rerun repository creation to update an existing
  builder. Report an access or sync blocker without claiming both copies updated.
