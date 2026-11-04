Hand-written Smali models of the Sync 23033 code "Sync: fix image peek" edits or calls:
the long-press peek `ImagePeekDialogFragment` (its picture loader `V3`, GIF loader `U3`,
the `T3` visibility check and the `E0` post / `F0` URL fields), its layout listener
`ImagePeekDialogFragment$f.onGlobalLayout` (reduced to the text-post branch, which shows
the post's `preview_url`, else its thumbnail, and a link branch for other posts), and the
post getters it reads (`xa.d`). They keep the names, signatures, register layouts, branch
shapes and calls the patch checks; the text-post branch uses `TextUtils.isEmpty` where
Sync calls commons-lang `StringUtils.isEmpty`. They are not the original method bodies.

`ImagePeekPatchTest` executes them before and after the patch. Its optional APK argument
runs the same checks on the real methods of a local stock Sync 23033 APK without making
or signing an APK.
