Hand-written Smali model of the Sync 23033 code "Sync: show hidden profile history from Arctic
Shift" edits or checks: `OAuthPostsRequest` (`y8.k0`), with `b`, which builds a listing URL (here
only the profile and subreddit branches), and `parseNetworkResponse`, which reads
`data.children` by kind into `PostParserHelper` (`c9.c.b`) and stores `data.after`. It keeps the
names, signatures, strings and calls the patch checks, but not the original bodies.
`volley-*.smali` are generated signature-only skeletons of the Volley members the patch and
helper use; the test also assembles the OkHttp skeletons in `../redgifs/okhttp3-*.smali`.

They contain no account data, client credentials or tokens; the archived items the test serves
are made up.

`ArchivedProfilePatchTest` executes them before and after the patch, with Reddit's answers
supplied by the test and the archive on loopback. Its optional APK argument runs the same checks
on the real methods of a local stock Sync 23033 APK without making or signing an APK.
