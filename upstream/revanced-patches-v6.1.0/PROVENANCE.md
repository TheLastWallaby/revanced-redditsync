# ReVanced Patches v6.1.0: Sync for Reddit files

Unmodified copies of the files this bundle ports or includes, for review and
comparison. Source: https://gitlab.com/ReVanced/revanced-patches, tag `v6.1.0`,
commit `b632d0f04239d49f14425f86f21784a526594518` (GPL-3.0).

- `patches/.../customclients/sync/**` and the shared `customclients` and
  `shared/misc/extension` patch files: ported to build 23033 in
  `src/patches/OfficialSyncPatches.kt`. Not compiled.
- `extensions/syncforreddit/**` and `extensions/shared/.../fixes/**`: included in
  `src/extension/`. Local changes: `RedgifsTokenManager` reads its response itself
  instead of through the shared `Requester`/`Route` helpers, and
  `FixRedditVideoDownloadPatch` uses explicit types instead of `var` (Java 8).

Not included: ReVanced's shared extension (`Logger`, `Utils`, `requests/`,
settings, YouTube support) and the patch `util` helpers. `src/extension/.../shared/`
has small local `Logger` and `Utils` classes with the members Sync's code uses.
