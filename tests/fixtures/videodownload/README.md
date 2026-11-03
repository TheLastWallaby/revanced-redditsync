Hand-written Smali models of the Sync 23033 code "Sync: fix video download manifests" edits
or checks: `RedditVideoRequest` (`r8.g`: Sync's own manifest loop with its `c`/`d` helpers,
then the result built after it, where the bundled "Fix video downloads" port hooks),
`RedditVideoDescription` (`o8.h`) and the download job's playlist listener
(`DownloadRedditVideoJob$b`). They keep the names, signatures, register layouts and strings
both patches check, but are not the original bodies. `volley-ParseError.smali` is a generated
signature-only skeleton.

They contain no account data, client credentials or tokens.

`VideoDownloadPatchTest` applies the port, then the patch, and runs the parser and the listener
on manifests before and after the patch. Its optional APK argument runs the same checks on the
real methods of a local stock Sync 23033 APK without making or signing an APK.
`app.revanced.extension.synccompat.VideoDownloadManifestTest` covers the link selection itself.
