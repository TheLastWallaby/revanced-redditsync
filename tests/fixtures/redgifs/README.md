These Smali files are hand-written layout models, not code copied from the APK:

- `OkHttpHelper.smali` models Sync 23033's `a8.a.k()`: builder settings, the DNS-over-HTTPS
  branch that skips to the install call, the inherited
  `FixRedgifsApiPatch.install(builder)` call, and the stored `a8.a.e` client that Sync's
  Volley `BasicNetwork` uses.
- `FixRedgifsApiPatch.smali`, `BaseFixRedgifsApiPatch.smali` and the two
  `RedgifsTokenManager*.smali` files hold only the members, strings and calls the patch
  checks in the inherited ReVanced RedGifs fix.
- `GrabIpRequest.smali` models Sync's `t8.a`: a Volley request whose code holds the
  `https://api.redgifs.com/info` URL and the `remote-addr` field the patch checks.
- `okhttp3-*.smali` are generated signature-only skeletons of the OkHttp members the refresh
  calls.

They contain no account data, client credentials or tokens.

`RedgifsTokenPatchTest` applies the compiled production patch, checks the one replaced call
(owner, signature, builder register, result storage and branch targets), duplicate rejection,
and that each unsupported inherited layout fails without editing OkHttpHelper. Its optional
APK argument runs the same checks against a local prepared base's real classes without making
or signing an APK. `app.revanced.extension.synccompat.RedgifsTokenRefreshTest` covers the
refresh behavior against loopback servers.
