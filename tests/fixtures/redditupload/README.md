Hand-written Smali models of the Sync 23033 code "Sync: upload images to Reddit" edits or checks:
`UploadManager` (`u8.d.a`, which queues the Imgur upload), `CheckUploadStatusRequest` (`u8.a`),
`UploadImgurRequest` (`u8.c`, constructor only), `ImgurUploadError` (`u8.b`), `RedditRequest`
(`x8.a`), the `AccountSingleton` members it checks, `OAuthRequestManager` (`m8.a`: `b`, `c`, `g`),
`OAuthSubmitRequest` (`y8.x0`), `OAuthCommentRequest` (`y8.e`) and `EditorView` (`h`, `i`). They keep
the names, signatures, register layouts and strings the patch checks, but are not the original
bodies. `okhttp3-*.smali` and `volley-*.smali` are generated signature-only skeletons of the OkHttp
and Volley members the helper calls.

They contain no account data, client credentials, Imgur or RapidAPI keys, or tokens.

`RedditImageUploadPatchTest` executes them before and after the patch. Its optional APK argument
runs the same checks on the real methods of a local stock Sync 23033 APK without making or signing
an APK.
