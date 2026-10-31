# Working on Sync compatibility patches

This repository distributes a cumulative ReVanced patch bundle for the free Sync
23033 package. A source edit alone is not a completed fix.

- Read the issue, README and relevant helpers before editing. Preserve existing
  gallery/comment behavior, continuation parents, retries and diagnostic privacy.
- Add a named, optional patch for unrelated functionality. Test the actual logic
  being changed. Do not assume code presence proves live Reddit functionality.
- Run: python3 -m unittest discover -s tests -p 'test_*.py' -v
- Run: python3 build.py --bootstrap
  This installs the locked tools, executes every top-level Java *Test.java main,
  compiles both DEX files, validates the RVP and loads it with ReVanced CLI.
- Provide the CI artifact or output/patches.rvp plus test evidence in the PR.
  State any missing device or authenticated Reddit validation clearly.
- VERSION is the only release-version source. Bump it for a patch release when
  preparing an actual release; do not publish or replace a release just to open a PR.
- Update the builder if additional compiler inputs or tests are introduced.
  Keep tool downloads pinned by SHA-256 in toolchain.lock.json.
- Never commit an APK, account credentials, signing key or secret. Public CI builds
  RVP/source artifacts only; personal APK signing runs in a private repository.
- Use a supported ReVanced-prepared base with none of this bundle's patches.
  Never stack a new cumulative bundle over an already compatibility-patched APK.
- Keep independently developed fixes in separate PRs. Do not merge another fix as
  part of infrastructure work.
