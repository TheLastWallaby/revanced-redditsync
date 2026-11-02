Hand-written Smali models of the Sync 23033 code "Sync: hide random subreddit" edits or
checks: the random sheet and its error listener (`RandomProgressDialogBottomSheet`,
`t9.m1`, `t9.m1$b`), `RandomRequest` (`r8.d`), the subreddit link opener and name reader
(`LinkHandler.T`/`o`, `y7.a`), the multireddit check (`y7.c.n`), the subreddit-selection
handler (`VerticalPostsFragment.onSubredditSelected`), `ActionsManager` (`t7.b`), the
"Action visibility" menu (`ActionsBottomSheetFragment.showMore`), the front-page chip group
and its ButterKnife binding, and the subreddit-selected event (`h7.d0`). They keep the names,
signatures, register layouts and strings the patch checks, but are not the original bodies.

`RandomSubredditPatchTest` executes them before and after the patch. Its optional APK
argument runs the same checks on the real methods of a local stock Sync 23033 APK without
making or signing an APK.
