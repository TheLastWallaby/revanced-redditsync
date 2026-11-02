# Personal APK builder

AI agents: read `AGENTS.md` before changing this builder, and follow the public
[automation guide](https://github.com/TheLastWallaby/revanced-redditsync/blob/main/docs/AUTOMATION.md).

This template belongs in a separate **private** repository. It checks for a new
stable public patch release every 30 minutes, then builds a cumulative APK from
stock Sync and your original signing key. Run it manually to choose a release
or rebuild immediately. No cross-repository write token is needed.

The workflow refuses public repositories. Signing inputs never enter public CI
or public release artifacts. Keep this builder on its trusted default branch;
it does not run for pull requests.

One-time setup is automated by the public repository's
scripts/setup_private_builder.py. It creates the private repository, copies these
files plus the locked tool installer and APK verifier, stores your signing key as
a repository secret, and creates the private build-inputs release containing
base.apk and inputs.json. Since public v0.7.0 the bundle includes the official
ReVanced Sync patches, so the base is **stock** Sync v23.06.30-13:39 (version code
23033) with no ReVanced patches; the builder rejects an already patched APK. Builds
of public releases before v0.7.0 need the old ReVanced-prepared base instead. The
expected certificate fingerprint must match your installed APK.

Required secrets: SIGNING_KEY_B64 and REDDIT_CLIENT_ID (the OAuth client ID for
the bundled Spoof client patch). Optional secrets: KEYSTORE_PASSWORD, KEY_ALIAS
and KEY_PASSWORD. Empty passwords use the ReVanced CLI defaults. Never replace the
original signing key with a newly generated one.

inputs.json holds the base checksum, the expected signer and optional patch
settings:

```json
{
  "base_apk_sha256": "<stock APK SHA-256>",
  "signer_sha256": "<installed app certificate SHA-256>",
  "spoof_client": {"redirect_uri": "<registered redirect URI>", "user_agent": "<User-Agent>"},
  "enable": ["Use /user/ endpoint"]
}
```

spoof_client values become the Spoof client options; omitted ones keep the patch
defaults (Sync's own redirect URI and a random User-Agent). enable lists extra
default-off patches. Every enabled patch must report success.

After a successful build, open Releases and install the APK from Sync vX.Y.Z as
an update. Verification checks signatures, original signing identity, existing
classes, and unchanged resources/manifest. Android/live Reddit testing is still
manual. A future patch intentionally changing resources/manifest must update the
verifier with an explicit, reviewed allowance.

The initial public v0.6.0 release predates build-info.json. The first automatic
personal build starts with the next infrastructure-produced release. The builder
ignores older releases without that metadata rather than publishing an unverified APK.

## Test a release candidate

Run **Actions → Build my Sync APK → Run workflow** from the trusted default
branch. Set `tag` to the exact public candidate tag, for example `v0.6.1-rc.1`;
leave `force` false unless rebuilding an existing version. The schedule follows
stable releases and will not select a candidate automatically.

Before installing, inspect the build's patch-step logs: every selected named
patch must report success. CLI 6.0.0 can save/sign an APK after a patch error, so
a green run or valid signature alone does not prove all patches applied. The
builder now stops before publishing unless every patch the bundle enables reports
`succeeded` exactly once, and lists them in `verification.json` as
`patches_succeeded`; builds made before this check have no such list. Also
check `verification.json` for the original signer, preserved classes and unchanged
resources/manifest. The builder checks the configured stock base checksum separately.
Install the candidate as an update and test the changed feature on the phone.
Stable publication follows confirmation that the candidate works.

## Template updates

The public repository owns the shared builder source; updates to it are not
automatically deployed here. Use the source/destination mapping in the public
automation guide to compare and sync intended changes, including this README and
the root AGENTS.md. Preserve private workflow customizations, signing secrets
and the build-inputs release. Do not rerun repository creation for maintenance.
