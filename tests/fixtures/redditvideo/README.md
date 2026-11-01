These Smali files are hand-written layout models of Sync 23033's video player path, not code
copied from the APK:

- `CustomExoPlayerView.smali`: `setSource(String, boolean, boolean)` stores `mSource` first and
  then calls `initPlayer`, which picks DASH (`y7.c.s`), HLS (`y7.c.t`) or progressive playback.
  This is the path for the full-screen viewer, the pager and the long-press peek.
- `ExoplayerUtils.smali` (`xc.a.a`): feed autoplay's media-source helper, with the same branches.
- `LinkTypeHelper.smali` (`y7.c.s`/`t`): the `v.redd.it` + `.mpd` / `.m3u8` classifiers.

Player and factory construction are omitted. Each branch stands in for its media source with a
`createMediaSource` call so `RedditVideoPatchTest` can execute the patched methods and see which
branch Sync takes. Run with a base APK path, the test checks the hook installation on the real
methods instead, without executing them and without making or signing an APK.
