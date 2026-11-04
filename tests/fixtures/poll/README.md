Hand-written Smali models of the two Sync 23033 methods "Sync: show poll options" edits:

- the listing item parser `PostParserHelper.a` (`c9.c.a`). It keeps the signature, register
  count and the register roles the patch checks (the item JSON copied once from `p2` into `v2`,
  one `ContentValues` in `v5`, the result list copied from `p3` right before the single `add`),
  the `selftext_raw`/`selftext_processed`/`selftext_processed_preview` and comment `body_*`
  columns, a content-filter early return, and register reuse after the add;
- the start of the link router `LinkHelper.c` (`y7.b.c`) up to its `reddit.com/poll/` branch
  (`const-string "Was poll"`, the log call, `LinkHandler.W`, `return`), with every other link
  sent to `LinkHandler.X`.

They are not the original method bodies; Markdown rendering (`RedditHelper.r`, `d7.f.r`, native
AndDown), Sync's entity decoding (`WebsafeUtils.a`, `wc.p.a`), logging and the link openers are
answered by the suite.

`PollPatchTest` runs them before and after the patch. Its optional APK argument runs the same
checks on the real parser and link router of a local stock Sync 23033 APK without making or
signing an APK.
