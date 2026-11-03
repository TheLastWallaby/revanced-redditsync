Hand-written Smali models of the Sync 23033 code "Sync: hand off retired messaging to Reddit
Chat" edits or calls: the new-message request `OAuthMessageRequest` (`y8.z`, `api/compose`) and
its parser, the editor's send-message error listener (`EditFragment$d`), the reply request
`OAuthCommentRequest` (`y8.e`: its parser's `json.errors` report, `parseNetworkError` and the
failure notification `d`), the base request `RedditRequest` (`x8.a`) and `DraftManager`
(`f9.a`). They keep the names, signatures, register layouts, branch shapes and strings the patch
checks; Sync's commons-lang `StringUtils.contains` is `String.contains` here. They are not the
original method bodies.

`MessagingHandoffPatchTest` runs them before and after the patch. Its optional APK argument runs
the same checks on the real methods of a local stock Sync 23033 APK without making or signing an
APK.
