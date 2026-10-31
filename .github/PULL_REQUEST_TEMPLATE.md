Describe the concrete problem and resulting behavior. Reference the issue.

Validation:

- [ ] Python infrastructure tests pass.
- [ ] All applicable Java suites pass.
- [ ] ReVanced CLI successfully loads the rebuilt output/patches.rvp.
- [ ] Link the downloadable CI bundle artifact.
- [ ] State device/live Reddit checks performed, or explicitly say they are pending.
- [ ] For an APK build, verify every selected named patch reports success and
      check the private signing/base report. A saved/signed APK alone is insufficient.
- [ ] For shared builder changes, sync the relevant deployed private files or
      explicitly record the remaining sync step. Documentation-only edits need no APK build.

When a release/test APK is requested, use a release candidate and the private
workflow's exact tag input. Hand private build/download links to the owner
privately. Stable publication follows phone-test confirmation and the requested
release scope. See docs/AUTOMATION.md.

No APKs, signing keys, OAuth credentials or personal account data belong in this PR.
