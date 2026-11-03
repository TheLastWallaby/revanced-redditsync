Hand-written Smali models of the Sync 23033 code "Sync: restore comments from Arctic Shift"
edits or checks: the comment options sheet (`CommentMoreDialogBottomSheet`, `t9.s`: `A4`
builds the items, `r0` handles a tap), `RestoreCommentRequest` (`r8.h`: the Pushshift URL
builder, constructor and `parseNetworkResponse`) and the `Post` getters it uses (`xa.d`,
`xa.a`, `xa.b`). They keep the names, signatures, register layouts and strings the patch
checks, but are not the original bodies. `volley-*.smali` are generated signature-only
skeletons of the Volley members the helper calls; the test also assembles the OkHttp
skeletons in `../redgifs/okhttp3-*.smali`.

They contain no account data, client credentials or tokens.

`ArcticShiftRestorePatchTest` executes them before and after the patch, with the tapped
restore going to a loopback archive and through Sync's parser. Its optional APK argument
runs the same checks on the real methods of a local stock Sync 23033 APK without making or
signing an APK.
