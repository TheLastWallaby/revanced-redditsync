Hand-written Smali model of the Sync 23033 code "Sync: start chats without a subject" edits: the
editor dialog `EditFragment`'s new-message send (`f4`: reads the message, recipient and subject,
refuses an empty recipient or subject, then builds `OAuthMessageRequest`, `y8.z`) and its
new-message fields (`b4`: shows the recipient and Subject fields, fills a known recipient and
focuses the message). It keeps the names, signatures, register layouts, branch shapes and strings
the patch checks. The reply branch of `f4` is reduced to one call. It is not the original method
bodies.

`ChatComposePatchTest` runs it before and after the patch. Its optional APK argument runs the same
checks on the real methods of a local stock Sync 23033 APK without making or signing an APK.
