# Working on the personal Sync APK builder

This file is exported to the root of the separate private repository
TheLastWallaby/revanced-redditsync-personal. When editing this public template,
also follow the public repository's root AGENTS.md and run its required checks.

- Read README.md and the public build instructions before editing:
  https://github.com/TheLastWallaby/revanced-redditsync/blob/main/docs/AUTOMATION.md
- This repository builds personal APKs from released cumulative patch bundles.
  Implement Reddit functionality fixes in the public patch repository; do not
  fork patch logic into this builder or stack bundles on an already patched APK.
- Use the trusted default branch and the existing Build my Sync APK workflow.
  Signing must remain private and must not run on pull requests or in public CI.
- Preserve the original signing key, its Actions secrets, the build-inputs
  release and its configured base checksum/expected signer. Never generate a
  replacement signing identity, print secrets or commit APKs/keys/account data.

## Candidate builds and handoff

- Scheduled builds follow the newest stable public release. To test a candidate,
  manually run Build my Sync APK with its exact tag, such as v0.6.1-rc.1.
  Use force only for an intentional rebuild of an already built version.
- Validate release checksums, version/build metadata and the prepared base
  checksum. Use the existing supported Sync 23033 prepared base with none of
  the custom compatibility patches and the original installed app's signer.
- Inspect the patch-step logs and require every selected named patch to report
  success. ReVanced CLI 6.0.0 can save/sign an APK after a patch error; a zero
  exit code, saved APK, green workflow or valid signature alone is insufficient.
  build_apk.py stops before publishing unless every patch the bundle enables
  reports success, and records them as patches_succeeded in verification.json;
  confirm that list as well. The structural/signing verifier itself does not
  prove each patch succeeded. Never weaken or bypass the patch-result check.
- Check verification.json for the original signing identity, preserved classes
  and unchanged resources/manifest. Do not weaken verification to pass a failed
  build; intentional resource changes require a narrow reviewed allowance.
- Return the public patch tag, private workflow run and private APK release/
  download links, plus feature-specific phone checks. Keep these personal links
  in the private handoff, not in public issues, PRs or release notes. Distinguish
  automated checks from device/live Reddit testing, which remains manual.
- A candidate handoff stops ready for phone testing. Stable publication follows
  the user's confirmation that it works and the authorized release scope in the
  public repository. Do not overwrite or rename existing candidate assets.
- Complete merge/release/build steps already authorized by the user without
  requesting the same permission again. Clearly report any access/build blocker.

## Maintaining the copied builder

- The public repository is the source of the shared builder template. Compare
  the mapping in docs/AUTOMATION.md before updating copied files, and preserve
  private workflow customizations. A public template change does not deploy here
  automatically; keep shared fixes aligned in both locations when in scope.
- Do not rerun repository creation to update this provisioned repository, or
  replace signing secrets or build-inputs assets during a template sync.
- For executable changes in this repository, run:
  python3 -m py_compile build_apk.py bootstrap_toolchain.py
  Run the matching public infrastructure tests for shared builder changes, then
  verify a private workflow run and its patch/signing results. Documentation-only
  edits do not require signing another APK or publishing a patch release.
