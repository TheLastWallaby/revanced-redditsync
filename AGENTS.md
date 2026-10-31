# Working on Sync compatibility patches

This repository distributes a cumulative ReVanced patch bundle for the free Sync
23033 package. A source edit alone is not a completed fix.

- Read the issue, README, docs/AUTOMATION.md and relevant helpers before editing.
  Start from current main. Update an existing issue PR instead of opening a
  duplicate, and preserve the build/release infrastructure when resolving conflicts.
  Preserve gallery/comment behavior, continuation parents, retries and diagnostic privacy.
- Add a named, optional patch for unrelated functionality. Test the actual logic
  being changed. Do not assume code presence proves live Reddit functionality.
- Run: python3 -m unittest discover -s tests -p 'test_*.py' -v
- Run: python3 build.py --bootstrap
  This installs the locked tools, executes Java *Test.java mains under tests/,
  compiles both DEX files, validates the RVP and loads it with ReVanced CLI.
  Confirm every intended suite actually ran. Package-declared suites must execute
  by fully qualified class name; extend discovery and its regression coverage if
  introducing a test layout the runner does not support. Compilation is not execution.
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
- Use a supported ReVanced-prepared base with none of this bundle's patches.
  Never stack a new cumulative bundle over an already compatibility-patched APK.
- Verify every selected named patch reports success when making a test APK.
  ReVanced CLI 6.0.0 can save/sign an APK after a patch error; exit status, an
  output file, a green workflow or a valid signature alone is insufficient.
  Check the patch-step logs as well as the signing/base verification report.
- Keep independently developed fixes in separate PRs. Do not merge another fix as
  part of infrastructure work.

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
  APK download links, plus concise checks for the changed feature on the phone.
  Keep personal APK links in the private handoff, not public PRs or release notes.
- Leave the stable release until the user confirms the candidate works on the
  phone and stable publication is authorized. Promotion uses a separate VERSION
  change and release build; do not rename or overwrite the candidate assets.

## Private builder maintenance

- private-builder/ is the public source template for the separate private repo.
  Its AGENTS.md must be exported as the private repository's root AGENTS.md.
- When changing copied files, follow the mapping in docs/AUTOMATION.md, compare
  the deployed private files, and sync the relevant changes when that work is in
  scope. Updating the public template alone does not update an existing private repo.
- Preserve deployed private customizations, original signing secrets and the
  build-inputs release. Do not rerun repository creation to update an existing
  builder. Report an access or sync blocker without claiming both copies updated.
