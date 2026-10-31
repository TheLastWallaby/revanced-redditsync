# Personal APK builder

This template belongs in a separate **private** repository. It checks for a new
stable public patch release every 30 minutes, then builds a cumulative APK using
your prepared base and original signing key. Run it manually to choose a release
or rebuild immediately. No cross-repository write token is needed.

The workflow refuses public repositories. Signing inputs never enter public CI
or public release artifacts. Keep this builder on its trusted default branch;
it does not run for pull requests.

One-time setup is automated by the public repository's
scripts/setup_private_builder.py. It creates the private repository, copies these
files plus the locked tool installer and APK verifier, stores your signing key as
a repository secret, and creates the private build-inputs release containing
base.apk and inputs.json. The base is your supported ReVanced-prepared Sync 23033
APK without any version of the compatibility bundle. The expected certificate
fingerprint must match your installed APK.

Required secret: SIGNING_KEY_B64. Optional secrets: KEYSTORE_PASSWORD, KEY_ALIAS
and KEY_PASSWORD. Empty passwords use the ReVanced CLI defaults. Never replace the
original signing key with a newly generated one.

After a successful build, open Releases and install the APK from Sync vX.Y.Z as
an update. Verification checks signatures, original signing identity, existing
classes, and unchanged resources/manifest. Android/live Reddit testing is still
manual. A future patch intentionally changing resources/manifest must update the
verifier with an explicit, reviewed allowance.

The initial public v0.6.0 release predates build-info.json. The first automatic
personal build starts with the next infrastructure-produced release. The builder
ignores older releases without that metadata rather than publishing an unverified APK.
