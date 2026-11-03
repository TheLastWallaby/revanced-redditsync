Hand-written Smali model of the Sync 23033 method "Sync: show poll options" edits: the listing
item parser `PostParserHelper.a` (`c9.c.a`). It keeps the signature, register count and the
register roles the patch checks (the item JSON copied once from `p2` into `v2`, one
`ContentValues` in `v5`, the result list copied from `p3` right before the single `add`), the
`selftext_raw`/`selftext_processed`/`selftext_processed_preview` and comment `body_*` columns,
a content-filter early return, and register reuse after the add. It is not the original method
body; Markdown rendering (`RedditHelper.r`, `d7.f.r`, native AndDown) and Sync's entity
decoding (`WebsafeUtils.a`, `wc.p.a`) are answered by the suite.

`PollPatchTest` runs it before and after the patch. Its optional APK argument runs the same
checks on the real parser of a local stock Sync 23033 APK without making or signing an APK.
