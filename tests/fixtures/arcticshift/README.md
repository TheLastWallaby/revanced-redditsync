Hand-written Smali models of the Sync 23033 code "Sync: restore comments from Arctic Shift"
and "Sync: restore posts from Arctic Shift" edit or check: the comment options sheet
(`CommentMoreDialogBottomSheet`, `t9.s`: `A4` builds the items, `r0` handles a tap),
`RestoreCommentRequest` (`r8.h`: the Pushshift URL builder, constructor and
`parseNetworkResponse`), the post options sheet (`PostMoreBottomSheetFragment.o2`, which
binds the rows and ends with Share's listener) and the `Post` getters they use (`xa.d`,
`xa.a`, `xa.b`: ID `U`, author `e`, raw comment text `o`, selftext `N0`, type `Y0`). They
keep the names, signatures, register layouts and strings the patches check, but are not
the original bodies. `volley-*.smali`, `sync-*.smali` and `material-*.smali` are generated
signature-only skeletons of the Volley, Sync and Material members the helper calls; the
tests also assemble the OkHttp skeletons in `../redgifs/okhttp3-*.smali`.

They contain no account data, client credentials or tokens.

`ArcticShiftRestorePatchTest` and `ArcticShiftPostRestorePatchTest` execute them before and
after the patches, with the tapped restore going to a loopback archive. Their optional APK
argument runs the same checks on the real methods of a local stock Sync 23033 APK without
making or signing an APK.
