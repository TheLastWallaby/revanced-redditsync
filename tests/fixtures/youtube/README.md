Hand-written Smali models of the Sync 23033 code "Sync: open YouTube in the YouTube app"
edits or calls: the link router `LinkHelper.c` (`y7.b.c`, reduced to its opening log,
empty-link check, scheme handling, the YouTube branch and the default-link branch),
`LinkHandler.a0` (`y7.a.a0`, which starts the embedded player `CustomYouTubeVideoActivity`
when Sync's "YouTube videos" link setting is on, else calls `LinkHandler.X`), the YouTube
classifier `LinkTypeHelper.K` and its host helper `LinkTypeHelper.a` (`y7.c`), and the
empty-text check (`wc.m.a`). They keep the names, signatures, register layouts, branch
shapes and strings the patch checks; `K` uses `String.contains` where Sync calls
commons-lang `StringUtils.contains`. They are not the original method bodies. The router
also keeps three Reddit-video log strings so `PatchInteractionTest` can install the
comment-video link hook on it.

`YouTubeAppPatchTest` executes them before and after the patch. Its optional APK
argument runs the same checks on the real methods of a local stock Sync 23033 APK
without making or signing an APK.
