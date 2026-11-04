Hand-written Smali models of the Sync 23033 code "Sync: don't report Amazon links" edits:
`CustomUrlSpan` (`mb.d`), the span of every link in a post body or comment, and
`AmazonAnalyticsRequest` (`e7.e`). `onClick` keeps the names, signature, register layout and
the shape of the Amazon report the patch checks (two `contains` checks for `amazon.com/` and
`/dp/` that branch past the report, then `new-instance e7.e`, the link, its constructor and
`RequestManager.a`), and is otherwise reduced to the flair, table and ordinary-link branches.
The request keeps its constructor's address, Volley method and the single `url` form field.
They are not the original method bodies.

`AmazonLinkPatchTest` executes them before and after the patch. Its optional APK argument runs
the same checks on the real methods of a local stock Sync 23033 APK without making or signing
an APK.
