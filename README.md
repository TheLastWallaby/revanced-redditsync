# revanced-redditsync

Unofficial ReVanced compatibility patches for **Sync for Reddit
v23.06.30-13:39**, version code **23033**, free package
`com.laurencedawson.reddit_sync`.

One patch bundle that gets Sync working again with today's Reddit: sign-in,
galleries, "load more comments", Reddit videos, third-party media, share links
and more. Since **v0.7.0** it is applied to the stock Sync APK in one pass; the
official ReVanced Sync patches are included.

This is a community bundle, not an official ReVanced release. Only the free
Sync build above is supported; Pro/dev packages and other versions are not.

## Quick start (ReVanced Manager)

You need an Android phone with [ReVanced Manager](https://revanced.app/) and
these two things:

- **The stock Sync APK.** Sync for Reddit **v23.06.30-13:39** (version code
  23033), the free `com.laurencedawson.reddit_sync` package, from
  [APKMirror](https://www.apkmirror.com/apk/red-apps-ltd/sync-for-reddit/sync-for-reddit-v23-06-30-1339-release/).
  Use the unmodified APK, not one you already patched.
- **A Reddit OAuth client ID** for an "installed app". This is the hard part:
  since November 2025 Reddit approves new API apps individually, and approval is
  often slow or refused, so a client ID created today may not work. If you have
  one from before then, use it. Neither this bundle nor the official ReVanced
  patches include one.

Then:

1. In ReVanced Manager, add this bundle by URL (in Manager versions with the
   custom bundle editor: **Patches** → pencil icon → **Add patches** →
   **Enter URL**):

   ```
   https://raw.githubusercontent.com/TheLastWallaby/revanced-redditsync/main/bundles/stable.json
   ```

2. Select the stock Sync APK from storage.
3. Use only this bundle's patches. If the official ReVanced patches are also
   loaded, deselect their Sync patches: this bundle already includes them and
   refuses an APK that has ReVanced code in it.
4. Open the settings of **Spoof client** and enter your **OAuth client ID**. If
   your Reddit app was registered with a redirect URI other than
   `http://redditsync/auth`, enter that as **Redirect URI** too.
5. Patch. Every selected patch should report success; if any fails, don't
   install the result.
6. Install. It can't install over a Sync signed with a different key, such as
   the original Play Store app or a copy patched on another device: uninstall
   that first, which removes its settings. Later updates install over this one
   as long as you patch with the same Manager.

Problems? Open an
[issue](https://github.com/TheLastWallaby/revanced-redditsync/issues) and attach
Manager's patch log. ReVanced Manager patching has not been tested by the
maintainers yet; the tested route is ReVanced CLI (see
[Apply the stable RVP](#apply-the-stable-rvp)).

The rest of this page is version history and technical detail.

## v0.7.5 poll options and Reddit Chat handoff (issues #9, #10)

The [v0.7.5 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.5)
rebuilds the runtime code from the
[v0.7.5-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.5-rc.2).
Apply it to **stock** Sync and check all patch results (28 with Use /user/ endpoint).

On the phone, [v0.7.5-rc.1](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.5-rc.1)
left ordinary text posts unchanged, but **Open poll on Reddit** reopened the
post in Sync instead of Reddit's poll page; rc.2 opens that link in your
browser. On rc.2 the owner reported open and closed polls working, the inbox
loading (after switching on Sync's **Inbox** button, which is off by default)
and a reply to a new direct message sent from Sync's inbox, then authorized
promotion. Comment replies from the inbox, mark read, a new private message and
moderator mail were not reported. See `validation-phone-tests.json`.

It adds two selectable patches, on by default, described under
[Included patches](#included-patches--cumulative-v075):

- **Sync: show poll options**
  ([issue #9](https://github.com/TheLastWallaby/revanced-redditsync/issues/9)):
  a poll post shows its options, closing time or "Poll closed", and the vote
  counts Reddit provides, with **Open poll on Reddit** for voting. Voting in
  Sync itself is not added.
- **Sync: hand off retired messaging to Reddit Chat**
  ([issue #10](https://github.com/TheLastWallaby/revanced-redditsync/issues/10)):
  when Reddit refuses a new private message, or a reply to an old one, because
  direct messages moved to Reddit Chat, your text is kept and Sync offers
  **Open Reddit Chat** (and, for replies, **Open message archive**). The inbox,
  comment replies, mentions, mark-read and message history are unchanged. Chat
  itself is not added to Sync.

## v0.7.4 YouTube links open in the YouTube app (issue #60)

The [v0.7.4 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.4)
rebuilds the runtime code from the
[v0.7.4-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.4-rc.1).
Apply it to **stock** Sync and check all patch results (26 with Use /user/ endpoint).

The owner asked for stable promotion of rc.1 without reporting results for its
seven numbered phone checks, so none are recorded. See `validation-phone-tests.json`.

It adds one selectable patch, on by default, described under
[Included patches](#included-patches--cumulative-v075):

- **Sync: open YouTube in the YouTube app**
  ([issue #60](https://github.com/TheLastWallaby/revanced-redditsync/issues/60)):
  tapping a YouTube video, Shorts or live link opens it in the YouTube app (or
  the browser if the app isn't installed or is disabled), at its timestamp,
  instead of Sync's built-in player. That player can't be repaired: it is
  Google's retired YouTube Android Player API, which current YouTube apps no
  longer support, so it only showed "Get YouTube App" and then "An error
  occurred while initializing the YouTube player".

## v0.7.3 image uploads to Reddit (issue #53)

The [v0.7.3 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.3)
rebuilds the runtime code from the phone-tested
[v0.7.3-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.3-rc.2).
Apply it to **stock** Sync and check all patch results (25 with Use /user/ endpoint).

On rc.2 the tester reported that post and comment images work, said it looks
good and authorized stable publication; no individual results were given for
the other numbered checks. See `validation-phone-tests.json`.

On the earlier [v0.7.3-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.3-rc.1)
image posts worked, but a comment with an image showed "Submitting" and was never
posted, with no error shown: Sync reports comment errors only as a notification,
which Android may not show. Since rc.2 every failure of a comment or text post
with an image is also shown on screen, with Reddit's own reason, and the comment
is sent with an empty `text` next to `richtext_json`, as Infinity for Reddit does
(rc.1 left `text` out).

It adds one selectable patch, described under
[Included patches](#included-patches--cumulative-v075):

- **Sync: upload images to Reddit**
  ([issue #53](https://github.com/TheLastWallaby/revanced-redditsync/issues/53)):
  images for an image post, or inserted in a comment, reply or text post, go to
  Reddit's own image hosting (`i.redd.it`) instead of Imgur. **You don't need to
  sign in again**: Sync's sign-in already asks Reddit for the permissions this
  uses. Imgur is no longer used for uploads, not even as a fallback.

## v0.7.2 comment restore from Arctic Shift and video download manifests

The [v0.7.2 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.2)
rebuilds the runtime code from the phone-tested
[v0.7.2-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.2-rc.2).
Apply it to **stock** Sync and check all patch results (24 with Use /user/ endpoint).

On rc.2 the tester reported that it looks good and authorized stable
publication; only an overall result was given for the seven numbered checks
(restore an archived comment, a missing one, downloads with and without sound,
playback, the v0.7.1 checks, general regressions). See `validation-phone-tests.json`.

The earlier [v0.7.2-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.2-rc.1)
showed no **Restore comment** entry, not even under ⋯ More: it required Sync
Ultra, which Sync records only when its retired server confirms a purchase, so
no install can turn it on any more. Since rc.2 the entry is shown for every
`[deleted]` or `[removed]` comment.

It adds two selectable patches, described under
[Included patches](#included-patches--cumulative-v075):

- **Sync: restore comments from Arctic Shift**
  ([issue #52](https://github.com/TheLastWallaby/revanced-redditsync/issues/52)):
  **Restore comment** (no longer needs Sync Ultra) asks the Arctic Shift archive instead of the
  retired Pushshift API. **The restored text comes from that third-party
  archive**; only the comment ID is sent.
- **Sync: fix video download manifests**
  ([issue #7](https://github.com/TheLastWallaby/revanced-redditsync/issues/7)):
  downloading a Reddit video picks the right video and audio files from more
  manifest forms, saves silent videos as video only, and says "No valid video
  files found" instead of saving a broken file. Playback is not changed.

## v0.7.1 retired media hosts, random subreddit and media link IDs

The [v0.7.1 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.1)
rebuilds the runtime code from the phone-tested
[v0.7.1-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.1-rc.1).
Apply it to **stock** Sync and check all patch results (22 with Use /user/ endpoint).

On rc.1 the tester reported that it is all good and authorized stable
publication; only an overall result was given for the five numbered checks
(dead-host link cards, other media, random entries, NSFW in the browser, general
regressions). See `validation-phone-tests.json`.

It adds three selectable patches, described under
[Included patches](#included-patches--cumulative-v075):

- **Sync: skip retired media hosts**
  ([issue #57](https://github.com/TheLastWallaby/revanced-redditsync/issues/57)):
  links to vid.me, sli.mg, Eroshare, picsarus, Tumblr posts and DeviantArt pages
  become ordinary link cards that open the original URL, instead of going to
  resolvers that no longer answer.
- **Sync: hide random subreddit**
  ([issue #54](https://github.com/TheLastWallaby/revanced-redditsync/issues/54)):
  the Random chip, actions and toggles are hidden, and random links and
  selections show "Random subreddit is no longer available from Reddit." without
  asking Reddit's retired endpoints.
- A link-ID fix for a third-party video host: its links with a slash before
  their query open again.

Not changed: Sync's `old.reddit.com` handling, including its over-18 handoff.

## v0.7.0 one-pass patching from stock Sync (issue #70)

The [v0.7.0 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.0)
rebuilds the runtime code from the phone-tested
[v0.7.0-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.0-rc.1).
Apply it to **stock** Sync and check all patch results (19 with Use /user/ endpoint).

On rc.1 the tester reported that it all looks good and authorized stable
publication; only an overall result was given for the ten numbered checks.
ReVanced Manager patching was not part of that test. See
`validation-phone-tests.json`.

Earlier releases only worked on an APK already patched with the official ReVanced
Sync patches; on stock Sync they failed
([issue #70](https://github.com/TheLastWallaby/revanced-redditsync/issues/70)).
v0.7.0 includes ports of those patches, so stock Sync v23.06.30-13:39 (version
code 23033) is patched in one pass:

| Patch | Default | What it does |
| --- | --- | --- |
| Spoof client | on (needs options) | Uses your Reddit OAuth client ID; also moves Sync's `ssl.reddit.com` sign-in URLs to `www.reddit.com` and its Imgur upload to Imgur's API |
| Disable ads | on | Turns off Sync's ads |
| Disable Sync for Lemmy bottom sheet | on | Skips the sign-up sheet at startup |
| Fix /s/ links | on | Resolves Reddit share links |
| Fix post thumbnails | on | Unescapes `&amp;` in thumbnail URLs |
| Fix video downloads | on | Saves both the video and audio track |
| Use /user/ endpoint | off | Uses `/user/` instead of `/u/` for profile requests |

The eighth, an on-by-default API fix for a third-party video host, is listed
under [Included patches](#included-patches--cumulative-v075).

**Spoof client options.** `OAuth client ID` (required), `Redirect URI` (default
Sync's `http://redditsync/auth`; must match your Reddit app) and `User-Agent`
(default: a random one, as the official patch). The official patch has only the
client ID; the other two options keep a non-default setup working.

**How it relates to the official patches.** They are ports of ReVanced Patches
v6.1.0 (GPLv3; originals in `upstream/revanced-patches-v6.1.0/`) written for build
23033: each checks the exact Sync code it changes before editing and refuses to
run twice. The Sync-specific ReVanced extension code is included unchanged except
for two small local changes noted in the files. ReVanced's shared extension
(settings screens, YouTube support) is not included; small local `Logger` and
`Utils` classes replace the parts Sync uses. Patches that build on official code
(share links, link normalization, comment video links, video-host tokens) depend on
the port, so ReVanced applies it first. The bundle rejects an APK that already
contains ReVanced code; do not combine it with the official ReVanced Sync patches.

**Checks.** On the stock APK, ReVanced CLI applied all 19 patches
successfully, in name order and in reverse with identical results. Its Sync
classes, manifest and resources match the earlier prepared base plus v0.6.14,
except for the client ID. `OfficialSyncPatchTest` checks each port on
stock-layout fixtures in CI (and on the real APK locally). The private v0.7.0-rc.1
build reported all 19 patches succeeded and passed signature, class and resource
verification; the tester then reported the ten phone checks looking good.
ReVanced Manager patching has not been tested.

## v0.6.14 Reddit video playback (issue #74)

The [v0.6.14 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.14)
rebuilds the runtime code from the phone-tested
[v0.6.14-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.14-rc.1).
Rebuild from the prepared base and check all eleven named patch results.

On rc.1 the tester reported that it looks good and authorized stable
publication; only an overall result was given for the five numbered checks.
Downloading Reddit videos was not changed or tested. See
`validation-phone-tests.json`.

It adds the selectable **Sync: play Reddit videos via DASH** patch for
[issue #74](https://github.com/TheLastWallaby/revanced-redditsync/issues/74).
Reddit-hosted videos showed **COULD NOT LOAD VIDEO** with an
`InvalidResponseCodeException` for `https://v.redd.it/<id>/HLSPlaylist.m3u8`.

**Why it happened.** Sync 23033 does not play the `hls_url` Reddit sends. For any
`v.redd.it` link it takes the video ID (`y7.a.k`) and rebuilds a bare
`https://v.redd.it/<id>/HLSPlaylist.m3u8`, dropping Reddit's signed `?a=…`
parameter. Reddit now refuses that playlist (HTTP 403 AccessDenied). On
2026-11-01 the same video's `DASHPlaylist.mpd` and every file it lists (CMAF
video and audio renditions) still loaded without a signature.

**What the patch changes.** Sync's player already plays a `v.redd.it` source
containing `.mpd` through its DASH branch (`y7.c.s`), and one containing `.m3u8`
through HLS (`y7.c.t`). Where Sync hands a source to its player, the patch
rewrites exactly `http(s)://v.redd.it/<id>/HLSPlaylist.m3u8[?…]` to
`https://v.redd.it/<id>/DASHPlaylist.mpd`:

- `CustomExoPlayerView.setSource`: the full-screen viewer, pager and long-press
  peek;
- `ExoplayerUtils.a` (`xc.a`): feed autoplay.

Sync's own data sources, cache, HD/quality track selection and audio handling
then apply unchanged. Every other source is left as Sync passed it, including
other `v.redd.it` files, cached files, other hosts and malformed links. The
patch checks the player, autoplay helper and both classifiers before editing.
Not changed: `DownloadRedditVideoJob`, which parses the playlist itself and has
not been checked, and the comment video posters (see
[issue #7](https://github.com/TheLastWallaby/revanced-redditsync/issues/7)).

`RedditVideoCompatTest` checks the rewrite against a model of Sync's two
classifiers: Sync's rebuilt URL and the escaped and plain signed `hls_url` forms
move from the HLS to the DASH branch, while other `v.redd.it` files, cached
paths, other hosts, user-info, ports, fragments and malformed IDs are unchanged.
`RedditVideoPatchTest` applies the patch to layout fixtures and executes Sync's
player and autoplay helper before and after it (HLS before, DASH after). Run with
the base APK path, it checks the hooks on the real methods; that was done locally
on the checksum-verified base and is not part of CI.

## v0.6.13 third-party video lookups (issue #49)

The [v0.6.13 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.13)
rebuilds the runtime code from the phone-tested
[v0.6.13-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.13-rc.2).
Rebuild from the prepared base and check all ten named patch results. The
earlier [v0.6.13-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.13-rc.1)
did not fix the problem (see below).

On rc.2 the tester reported that the fix looks good and authorized stable
publication; only an overall result was given for the five numbered checks.
Reddit-hosted video playback ("Could not load video", HTTP 403 on Sync's
unsigned `v.redd.it` HLS link) is a separate, pre-existing problem this release
does not change; v0.6.14 above addresses it. See
`validation-phone-tests.json`.

It adds a selectable token-refresh patch, listed under
[Included patches](#included-patches--cumulative-v075), for
[issue #49](https://github.com/TheLastWallaby/revanced-redditsync/issues/49):
a third-party video played in the feed preview, but full screen showed a
connection error.

**Why it happened.** Sync's full-screen viewer, long-press peek, downloads and
v0.6.6's enhanced autoplay look these videos up with a short chain of API
requests. The host retired one of those endpoints, and the inherited ReVanced
fix kept resending a cached token after the host stopped accepting it, so
lookups failed until Sync restarted.

**What the patch changes.**

- The retired lookup is answered locally from one bounded token request that is
  reused for 10 minutes; its token also replaces the inherited cached one.
- After HTTP 401 or 403, it fetches one new token and retries that request once.
  A second rejection, or a failed token request, starts a 30-second backoff, so
  there are no loops.
- Only one token request runs at a time, with connect, read and total time
  limits, a 16 KiB response limit, no redirects and no cookies or
  `Authorization`.
- Untouched: other hosts, plain HTTP and other requests. Reddit's credentials
  never reach the host. **Tokens are never logged**, stored in diagnostics or
  exported, and the patch writes no log at all.
- v0.6.6's enhanced autoplay is unchanged and benefits from the same fix.

Tests run the requests against loopback servers with test-only tokens. They
cover the retired endpoint, address reuse and expiry, concurrent lookups, 401
and 403 recovery with exactly one refresh, a second rejection without a loop,
failed and unusable token responses, and that other requests are untouched. A
patch test applies the hook to layout fixtures; it was also run locally against
the checksum-verified base, which is not part of CI.

On rc.1 the tester reported the feed preview playing and full screen still
failing; see `validation-phone-tests.json`. rc.1 contained only the token
refresh, which never ran because the lookup failed at the retired endpoint
first.

## v0.6.12 no legacy Reddit endpoints (issues #48, #51, #55)

The [v0.6.12 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.12)
rebuilds the runtime code from the phone-tested
[v0.6.12-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.12-rc.1).
Rebuild from the prepared base and check all nine named patch results. It
contains the three changes below.

On rc.1 the tester reported load-more on a large thread and the general
regression checks (galleries, comment media, share links) passing, then
authorized stable publication. Literal entity text through load-more and a
gallery caption containing `&` were not tested on the phone. The awards sheets
were not reachable: Sync shows the post menu's Awards row and a comment's awards
only when Reddit reports `total_awards_received` above 0, and the tested items
reported none. See `validation-phone-tests.json`.

### Awards sheet and gallery fallback without `/api/info` (issue #48)

Changes **Sync: handle unavailable award data** and **Sync: fix Reddit
galleries** for [issue #48](https://github.com/TheLastWallaby/revanced-redditsync/issues/48).
Sync's awards sheet looked items up with `GET /api/info/?id=t3_…` or `t1_…`
(`OAuthAwardsRequest`, `y8.c`). It now reads the item's own comment page:

- a post: `/comments/<post>.json?limit=1&depth=1`
- a comment: `/comments/<post>/comment/<comment>.json?context=0&limit=1&depth=1`

Both are built only from validated IDs, with Reddit's default encoding (no
`raw_json`, as in #55). Sync's comment sheet receives only the comment ID, so the
patch records the comment's post (`link_id`) where Sync opens that sheet
(`AwardsTextView.H`, from `Post.U()` and `Post.j0()`; the patch checks that
`AutoValue_Post` maps them to `_id` and `link_id`). `AwardCompat` reads the item
from the thread listing, only if it is the requested post, or the requested
comment under the requested post, and keeps the message table and the Open on
Reddit row described under v0.6.3 below. Two lookups gain clearer text:

| Case | Sheet shows |
|---|---|
| Reddit answers HTTP 404 (missing or removed post) | "Couldn't find this item's awards on Reddit." |
| The item or a comment's post can't be identified | "Couldn't identify this item for Reddit's award lookup. Reopen it and try again." |

In the second case no request is sent. The lookup returns a non-HTTP(S) URL
(`sync-compat-unidentified:awards`), which Sync's network stack (Volley over
OkHttp) rejects before connecting, and the sheet names the cause. This happens
for a comment sheet restored after Android stopped Sync, for example.
`RequestCompat.galleryUrl` does the same (`sync-compat-unidentified:gallery`)
when no post ID can be read from a gallery link. Previously it requested
`/api/info.json?id=t3_` or the unrecognized link itself; Sync now shows its own
gallery load error without contacting Reddit.

`AwardCompatTest` requests post and comment awards in listing form from a
loopback server with a test-only token, and covers a JSON 404, an unrelated post
or comment, a removed comment, malformed JSON, HTTP 401/403/429/500/503 and the
unidentified case. No request reaches `/api/info`. `GalleryCompatTest` checks the
gallery fallback. The two new hooks were also applied to the checksum-verified
prepared base and register-type checked; that check is not part of CI.

### Load-more without `/api/morechildren` (issue #51)

Changes **Sync: load more comments in batches** for
[issue #51](https://github.com/TheLastWallaby/revanced-redditsync/issues/51).
Since v0.6.8 each tap first tried Reddit's `/api/morechildren` once and, when
Reddit refused it, cached the refusal for six hours. There is no evidence that
Reddit accepts that endpoint for Sync's client, so load-more now goes straight to
the per-comment route and sends no request to `/api/morechildren`. The batch code
is kept behind a switch that is off; the loading details report `batch=disabled`.
Budgets, rate-limit handling and Sync's rate helper, cancellation, token refresh,
continuation parents, retries, duplicate/subtree reuse and the diagnostic's
privacy are unchanged. A 100-ID row still loads 33 comments per tap.

`BoundedCommentsTest` and `BatchCommentsTest` check that 1, 20, 100 and 150 IDs
send no `/api/morechildren` request (the loopback endpoint would answer HTTP 200)
and make 0, 19, 32 and 32 extra requests under the per-tap budget (0, 19, 99 and
99 without it). The batch scenarios still run with the switch turned on.

### Reddit's default text encoding on every request (issue #55)

Changes **Sync: load more comments in batches** and **Sync: fix Reddit galleries**
for [issue #55](https://github.com/TheLastWallaby/revanced-redditsync/issues/55).
Sync's own Reddit requests never add `raw_json=1`: Reddit then HTML-escapes `&`,
`<` and `>`, and Sync undoes that itself. Sync 23033's comment parser
(`PostParserHelper` `c9.c.a`) stores `WebsafeUtils.a` (`wc.p.a`) of each `body`,
and its renderer (`RedditHelper.r`, `d7.f.r`) applies `WebsafeUtils.a` again;
gallery captions are unescaped by the same renderer, and Sync's gallery parser
unescapes media URLs with `WebsafeUtils.a`. The load-more fallback route
(`/comments/<post>.json?comment=`), the switched-off `/api/morechildren` route
and the gallery request still asked for `raw_json=1`, so their text was decoded
as if it were escaped and could differ from the same comment on Sync's own route.
No request adds `raw_json` now. Gallery media URLs still have only their escaped
ampersands decoded, so signed query bytes stay exact; captions stay as Reddit
sent them for Sync to unescape.

`CommentEncodingTest` serves comments the way Reddit encodes them and checks that
a comment with literal `&lt;3`, `&amp;`, `&amp;amp;`, `<`, `>` and `&` reaches
Sync's parser with the same bytes through the per-comment, fallback and batch
routes as through Sync's own request, and renders identically. `GalleryCompatTest`
checks escaped signed source, preview and MP4 URLs (also after a metadata refresh)
come out byte-identical and a caption containing `&` renders as typed. By the
base's code, Sync's own decoding (it decodes `&amp;` before `&lt;`) turns a
literal `&lt;3` into `<3` on its own route too; the fix makes load-more match
the thread view rather than change it.

## v0.6.11 signed inline image URLs

The [v0.6.11 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.11)
rebuilds the runtime code from the approved
[v0.6.11-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.11-rc.2).
Rebuild from the prepared base and check all nine named patch results.

Fixes pictures in text posts and comments that show a black inline box and
**GLIDE FAILED TO GRAB THIS IMAGE** when tapped. Sync appended `&height=<metadata height>` to signed
`preview.redd.it` URLs for layout. Reddit rejected the reported URL with HTTP
403; the original URL served its PNG successfully. The inline-media patch now
keeps the source URL intact during post/comment parsing, so both inline images and
taps use it. Dimensions still come from `media_metadata`. Actual URL parameters,
including a source's own `height`, remain unchanged.

`CommentImageUrlPatchTest` executes the original and patched DEX rewrite, checks
signed query bytes and surrounding text, metadata sizing, layout/duplicate
rejection and DEX serialization. Its optional base-APK argument also applies the
complete named patch to the verified prepared base.

On rc.1 the tester confirmed that tapping now loads the image, but it only
appeared as a link. That candidate captured dimensions only in the comment
parser branch, while this image is in a text post. rc.2 captures the item's
metadata at the shared parser entry for both `selftext` posts and `body` comments.
Its regression test feeds raw post/comment JSON through the emitted parser call
before checking the inline box; it no longer manually seeds the cache. The tester
approved rc.2 for stable publication after the three numbered phone checks;
only an overall result was given. See `validation-phone-tests.json` for that
scope and the earlier partial rc.1 result.

## v0.6.10 Reddit link normalization (issue #5)

Adds the selectable **Sync: normalize Reddit links** patch for
[issue #5](https://github.com/TheLastWallaby/revanced-redditsync/issues/5).
The
[v0.6.10 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.10) rebuilds the runtime code from the tested
[v0.6.10-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.10-rc.1). Rebuild from the prepared base
and check all nine named patch results.

Sync 23033 sorts links with regular expressions and string splits, so equivalent
links went to different places. For example `reddit.com/comments/ID?context=3`
and `redd.it/ID?utm_source=share` opened a post with the query as part of its ID,
`reddit.com/gallery/ID`, `sh.reddit.com` profiles and `np.reddit.com` subreddits
opened in a browser, and `/r/sub/top/?t=week` was treated as a post.

The patch rewrites each link once, with a real URI parser, into a form Sync's
own classifiers already handle, before Sync's link helper and the share-link
resolver see it. The same hook covers links tapped in Sync, Android VIEW and
SEND (shared text is reduced to its first link), warm start and cold start.

| Link | Opens as |
|---|---|
| Posts on `reddit.com`, `www`, `old`, `new`, `sh`, `m`, `amp`, `i`, `np`, with or without a title, trailing slash, `http`, fragment or tracking parameters; `/user/NAME/comments/ID`; `redd.it/ID`; Reddit-relative `/r/…` | `/r/SUB/comments/ID/` (or `/comments/ID/`). `np.reddit.com` keeps Sync's no-participation mode. |
| Comment links, old `/title/COMMENT` and new `/comment/COMMENT` forms | `/r/SUB/comments/ID/_/COMMENT/` with `context` and `sort` kept |
| `/gallery/ID` | Sync's gallery viewer (the gallery link classifier also accepts `https://www.reddit.com/gallery/ID`) |
| `/u/NAME`, `/user/NAME`, with `overview`, `submitted`, `comments` or `gilded` | The profile |
| `/r/SUB`, `/r/SUB/hot`, `new`, `rising`, `top`, `controversial` | The subreddit. Sync's subreddit route takes no query, so a `?t=` time range is dropped. |
| `/r/SUB/s/TOKEN`, now also `/u/NAME/s/TOKEN` | The secured share-link resolver, token case kept, `#bypass` browser fallback kept |
| `i.redd.it`, `preview.redd.it`, `external-preview.redd.it`, `v.redd.it` | HTTPS; signed CDN parameters kept byte-for-byte and in order |
| `reddit.com/media?url=…` | The wrapped media, only if it is on one of the four Reddit media hosts above. At most two rounds of percent-decoding; malformed, control-character, over-long, nested, duplicate or other-host targets are left as they were. |

Only `utm_*`, `share_id`, `ref`, `ref_source`, `ref_campaign`, `rdt`,
`correlation_id`, `_branch_match_id`, `_branch_referrer`, `$deep_link` and `$3p`
are removed. Other parameters are kept; on Reddit pages unusual characters are
percent-encoded so Sync's comment classifier still matches. Sync's own
`?is_gallery=true` links, wiki, search, compose, multireddit, domain, duplicate,
over-18, poll and other links it does not recognize are left unchanged. Sync's
comments screen does not take `context` or `sort`, so keeping them in the link
does not yet change the comment view.

The share-link resolver keeps its HTTPS, host, path and authorization checks.
It now also accepts profile share links, and each request may take up to four
seconds within an eight-second limit for the whole redirect chain, instead of a
fixed two seconds per request. The browser fallback is unchanged.

**Opening links from other apps.** The patch does not change the manifest (the
private APK verifier requires an unchanged manifest), and Reddit's Android App
Links verification only lists Reddit's own signing key, so adding hosts or
`autoVerify` could not make Android verify a self-signed Sync. On Android 12 and
later, open **Settings → Apps → Sync → Open by default**, turn on **Open supported
links** and add the Reddit links listed there (`reddit.com`, `www.reddit.com`,
`old.reddit.com`, `m.reddit.com`, `np.reddit.com`, `amp.reddit.com`, `redd.it`,
`v.redd.it` and others). `sh.reddit.com`, `new.reddit.com`, `i.redd.it` and
`preview.redd.it` are not in Sync's manifest; share those links to Sync instead.

`LinkNormalizerTest` runs a table of equivalent links (hosts, schemes, queries,
fragments, trailing slashes, shared text, relative links, wrappers and rejected
inputs) through the normalizer and then through a model of Sync's link helper,
classifiers and ID parsers transcribed from the base. It checks the destination
and the post, comment, subreddit, user or media each one yields, that the model
reproduces the original defects, and that normalizing twice changes nothing.
`LinkPatchTest` applies the patch to base-method fixtures, executes the patched
gallery classifier and the start of the patched link helper, and checks the
cold-start hook and duplicate/unsupported-layout rejection; run with the base APK
path it does the same on the real methods. `SafeSLinksTest` covers profile share
links and the redirect deadline.

On v0.6.10-rc.1 the tester reported that `sh.reddit.com` post and profile links
tapped in another app open in the browser, as expected without a manifest entry
(follow-up: [issue #61](https://github.com/TheLastWallaby/revanced-redditsync/issues/61)),
and then approved stable promotion. Individual results for the numbered link
checks were not supplied; see `validation-phone-tests.json`.

## v0.6.9 load-more requests share Sync's rate helper (issue #3)

Completes the v0.6.8 change to **Sync: load more comments in batches**. Sync's
own `OAuthRateHelper` (`d9.d`) stores Reddit's rate window, and Sync's Volley
refuses to send its requests once that window is exhausted. The extra load-more
requests now report each response's `x-ratelimit-*` and `date` headers to that
helper, as Sync's Volley does for its own requests. They also wait (or stop with
"rate limited") while the window Sync has stored is at the 10-request reserve and
not yet reset. A helper failure never fails a load. The patch checks the
helper's `e`, `b` and `c` signatures in build 23033 before installing.

`BoundedCommentsTest` covers reporting, unreported header-less responses, a
stored window at the reserve, an expired window and an unavailable helper, using
a recording stand-in for the helper.

On v0.6.9-rc.2 the tester reported all six numbered phone checks passing: about
33 comments per load-more tap with the row kept, follow-up taps without repeats or
gaps, normal post refresh and subreddit browsing after several taps, leaving
during a load, gallery swiping/captions/position (including crossposts where
available) and unavailable gallery items. See `validation-phone-tests.json`.

The [v0.6.9 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.9)
rebuilds the runtime code from the tested
[v0.6.9-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.9-rc.2).
Rebuild from the prepared base and check all eight named patch results.

## v0.6.9 gallery resilience (issue #6)

Extends **Sync: fix Reddit galleries**. A missing, failed, processing or unsupported
media entry now leaves an unavailable slot while the other items remain usable.
Slots retain their original index, caption and outbound link; opening a cached
gallery retains the saved position. The parser accepts available image, GIF and
MP4 sources without requiring previews or a particular source field.

The response must identify the requested post. Crossposts use their own gallery
first, then the parent identified by `crosspost_parent`; unrelated parent data
is rejected. A missing own media entry can use metadata from that matching
parent's gallery. Malformed thread/listing responses are rejected rather than
being turned into an empty gallery. The original raw-response logger is removed.

When a source explicitly declares an expired `expires`, `Expires` or `exp` query
timestamp and has no usable alternative rendition, the parser refreshes metadata
once through the same fixed OAuth origin. This request has a five-second budget,
a 1 MiB response limit and no redirects. Original order, captions and position
are retained, signed query bytes are preserved, and a failed refresh leaves usable
neighbors available and an explicit reopen retryable. Opaque signatures do not
establish expiry; a media viewer's later HTTP failure does not trigger this refresh.

`GalleryCompatTest` executes the production parser and refresh against real Volley
types and loopback HTTP. `GalleryPatchTest` applies the production patch to the
original Sync 23033 parser/model fixtures and checks hook installation, cache
handling, duplicate/layout rejection, branches, register bounds/types and DEX serialization.
First published in v0.6.9-rc.1; the tester's gallery checks passed on v0.6.9-rc.2,
which contains the same gallery code. Expired media on a phone was not observed;
the loopback tests cover the refresh.

## v0.6.8 bounded "load more comments" requests (issue #3)

Changes the existing **Sync: load more comments in batches** patch for
[issue #3](https://github.com/TheLastWallaby/revanced-redditsync/issues/3). A
large expansion could previously send roughly 100–200 requests with no rate
accounting, overall deadline or cancellation. Each tap now:

- makes at most 32 requests beyond Sync's own, four at a time, shared across
  taps. Untouched IDs stay in the continuation row ahead of failed ones, so
  the next tap carries on. A 100-ID row loads 33 comments per tap on the
  per-comment route.
- does not request IDs already returned inside another comment's reply tree,
  or duplicate IDs.
- follows Reddit's `X-Ratelimit-*` headers, including those on Sync's own
  response, keeping 10 requests in reserve. HTTP 429 waits for `Retry-After`
  (shared by later taps) and retries once.
- after HTTP 401 asks Sync's request once for its current headers, so Sync's own
  token refresh is used. Later taps holding the same rejected token reuse that
  result. Four consecutive request failures (such as a blanket 403) end the tap.
- ends after 20 seconds and stops when Sync cancels the request, closing requests
  that are still open. Pending IDs stay retryable.
- tries Reddit's `/api/morechildren` once (serially, one request at a time
  across taps) and caches the result for six hours. If it is refused or returns
  nothing usable, the per-comment route is used as before. An ID the batch omits
  is fetched on its own and appears after the batch results. Issue #51 turns
  this attempt off (see above): load-more no longer requests `/api/morechildren`.

When nothing loads, the error names the cause (rate limited, sign-in rejected,
timed out, requests failed) and the row stays. The loading details gain a
`transport:` line with request, budget, reuse, deferral, stop and batch-endpoint
counters only.

`BoundedCommentsTest` exercises these paths against a loopback server with real
Volley requests: request counts for 1, 20, 100 and 150 IDs, duplicates and
subtrees, 401/403/429, slow responses, canceling queued and in-flight work and
the batch endpoint. `BatchCommentsTest` keeps the existing continuation checks.

On v0.6.8-rc.1, the tester installed the candidate and reported that it "looks
good" after the supplied load-more checklist; individual checklist items were not
reported separately. Not verified: Sync's internal rate helper is not updated (no
APK was available to inspect it), whether `/api/morechildren` is ever usable for
Sync's client, and exhaustive authenticated Reddit behavior. See
`validation-phone-tests.json` for the scope and remaining checks.

The [v0.6.8 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.8)
rebuilds the runtime code from the tested
[v0.6.8-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.8-rc.1).
Rebuild from the prepared base and check all eight named patch results.

## v0.6.7 literal Markdown replacements (issue #2)

Adds the selectable **Sync: preserve literal Markdown replacements** patch for
[issue #2](https://github.com/TheLastWallaby/revanced-redditsync/issues/2).
Fenced code containing `$HOME`, `$12`, a literal dollar sign or backslashes
keeps that content instead of throwing or interpreting it as replacement groups.
Spoiler-link labels and image/GIF emote URLs receive the same literal handling.
The patch changes six calls in Sync 23033; existing formatting, search patterns,
branch targets and deliberate regex group substitutions are retained.

`MarkdownPatchTest` applies the compiled production patch to DEX fixtures and
executes the resulting fenced-code, spoiler and emote instructions with real JVM
string/regex APIs. It reproduces the original defect, covers Unicode, multiple
matches and unchanged ordinary links, and rejects duplicate application.
The fenced-code/spoiler fixtures contain the original build-23033 method bodies;
the emote fixture isolates the two original replacement sequences. A small strict
instruction runner covers these string methods, not the Android renderer or full
post parser.

On v0.6.7-rc.1, the tester supplied a phone screenshot showing literal dollar
signs, backslashes and Unicode in a fenced-code preview, ordinary bold/link
formatting and the spoiler label. The tester then reported gallery navigation
and load-more comments passing, followed by the emote comment in
[this test thread](https://www.reddit.com/r/deadbydaylight/comments/otk1ev/this_post_is_only_a_test/h6w2e7b/).
Reddit currently supplies a generic animated dizzy-face image for that old custom
emote; that is the image tested. Live emote URLs containing dollar signs or
backslashes were not observed; the offline fixtures cover those replacements.
See `validation-phone-tests.json` for the scope and remaining checks.

The [v0.6.7 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.7)
rebuilds the runtime code from the tested
[v0.6.7-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.7-rc.1).
Rebuild from the prepared base and check all eight named patch results.

## v0.6.6 direct media resolution (issue #4)

Adds an optional seventh patch, **Sync: replace legacy media proxies**, for
[issue #4](https://github.com/TheLastWallaby/revanced-redditsync/issues/4).
The
[v0.6.6 stable release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.6)
rebuilds the runtime code from the tested
[v0.6.6-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.6-rc.1).

Sync 23033 still sends several media requests through its own proxies, and
neither answers now: `images.syncforreddit.com` does not resolve and
`ap.syncforreddit.com/image` returns HTTP 401.

| What | Sync 23033 used | With the patch |
|---|---|---|
| Imgur album or gallery post | `images.syncforreddit.com/a/…` | Imgur's public album embed page. Album order, titles and descriptions, video items and the saved album position are kept. |
| Gallery link that is one item | `images.syncforreddit.com/image/…` | A missing album (HTTP 404) takes Sync's existing single-item fallback, read from the item's embed page. |
| Album link-card thumbnail in comments | `ap.syncforreddit.com/image?url=imgur-album-…` | The album's first item as a small thumbnail, looked up once in the background and loaded into the card. |
| Website preview (Sync Ultra) and Tenor/web-page card thumbnails | `ap.syncforreddit.com/image?url=<page>` | Sync's ordinary link card. YouTube keeps its `i.ytimg.com` thumbnail. Web pages are no longer handed to the image decoder. |
| Gfycat card thumbnail | `ap.syncforreddit.com/image?url=<gfycat link>` | The link card. Gfycat shut down. |
| Enhanced autoplay: third-party video host | `ap.syncforreddit.com/image?url=…` | Sync's own resolver for that host (with the base's fix for it), then the video. Errors and unexpected results use Sync's ordinary autoplay source. |
| Enhanced autoplay: Gfycat | `ap.syncforreddit.com/image?url=gfycat-…` | Sync's ordinary autoplay source, or no autoplay when there is none. |

- Imgur requests use fixed HTTPS Imgur hosts and validated IDs, current
  `/gallery/title-words-ID` links included. Media links are rebuilt as
  `https://i.imgur.com/<id>.<ext>` from Imgur's own list; nothing guesses file
  extensions. The cover lookup follows no redirects and sends no cookies or
  credentials. Sync's album requests keep their existing `sync_for_reddit`
  User-Agent and send no Reddit authorization.
- Third-party video results are used only when they are HTTPS `.mp4` links on
  that host's domain. Its token stays inside Sync's own requests to it.
- Not changed: Sync's `ap.syncforreddit.com/stats.json` and
  `todo.syncforreddit.com` icon/licence links, which are not media proxies.

`MediaProxyCompatTest` covers the ID parsing, the adapted album and item
responses, album covers, website and Gfycat fallbacks, third-party video autoplay and a
loopback HTTP server (redirects, size limit, no cookies or credentials). The
parsers were also run against 39 live Imgur album embed pages (278 items, all
matching Imgur's own counts) and live single-item pages. The tester reported
running every check on the v0.6.6-rc.1 phone checklist (Imgur albums and
gallery links, album link-card thumbnails, website/YouTube link cards,
third-party video enhanced autoplay and the gallery/comment-media regression check) and approved
stable promotion; see `validation-phone-tests.json`. This is phone confirmation
for the tested posts, not exhaustive Android or live-service coverage.

## v0.6.5 Imgur comment video previews (issue #23)

Extends **Sync: show comment video previews** to direct Imgur `.mp4` and `.gifv`
links on `i.imgur.com`, `imgur.com` and `www.imgur.com`, using the same
poster-and-play-button option as Reddit comment videos. The
[v0.6.5 stable release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.5)
rebuilds the runtime code from the tested
[v0.6.5-rc.4 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.5-rc.4).

- With **Inline image previews** on, the comment reserves a placeholder box and
  loads a still frame into it. The visible span takes the decoded aspect ratio
  and its TextView layout is rebuilt, so a wide frame need not stay in the first
  square box until the user scrolls away and back.
- The first image load uses a ready 69-byte opaque neutral PNG, then a shaped
  play placeholder and poster. Images are written atomically; the loader never
  starts with a future/missing poster file or repeatedly loads an unchanged placeholder.
- Image replacement preserves the span's weak TextView binding across Glide's
  target-clear callback and schedules a repaint, including cached synchronous
  completion. The patch validates the build-23033 view binding and dimension
  fields against the original constructor before enabling live metrics.
  Ordinary image spans keep their dimensions; unknown targets retain the fitted
  poster fallback.
- A frame finishing between placeholder selection and span registration is
  still delivered; readiness during registration no longer assumes it is displayed.
- Background jobs prefer a complete Imgur WebP still (at most 256 KB), then the
  first 2 MB of its MP4, then a bounded `<id>h.jpg` thumbnail if needed. Android
  decodes a bounded first frame and adds a play badge. HTTP 429 stops remaining
  fallbacks. Requests use fixed HTTPS media hosts, explicit media MIME types,
  no redirects, cookies or authorization, and no GIF renditions or website HTML.
  Case-sensitive Imgur IDs have separate cache keys from Reddit videos;
  `.mp4` and `.gifv` share a poster for the same ID.
- The original link, query and fragment stay the tap target for Sync's existing
  viewer. Unsupported URLs keep their cards. If no frame can be made, existing
  spans shrink to a compact play tile and subsequent bindings use the link card.
- With **Inline image previews** off, the card remains and no poster is queued.
  Posters are static; animated-image and autoplay settings do not start playback.

All nine Java suites exercise the URL, download, cache, sizing and span-update
logic with fake Android decoders, including weak view binding, cached completion,
registration races, row relayout, recycled/detached targets and terminal failures.
The tester confirmed automatic refresh on rc.3, then reported rc.4 looking good
and authorized stable promotion. This is phone confirmation for the tested case,
not exhaustive Android/live-service validation. The exact failed clip's decoder
path and settings-off behavior were not separately confirmed; see
`validation-phone-tests.json` for the scope of the report.

## v0.6.4 comment video previews

Adds **Sync: show comment video previews**, the poster-frame part of
[issue #23](https://github.com/TheLastWallaby/revanced-redditsync/issues/23).
The tester's phone notes for the v0.6.4 candidates are recorded in
`validation-phone-tests.json`. rc.1 and rc.2 showed the preview and opened the
video, but a video comment still showed as a link for a few seconds first:
rc.2's reserved box needed the video's size from Reddit's comment data, which
evidently isn't there for these comments. rc.3, which became this release,
reserves a box without it.

Reddit writes videos posted in comments as
`https://reddit.com/link/<post>/video/<media>/player`. Sync 23033 sends that
link to the browser. Reddit then redirects it to
`www.reddit.com/r/subreddit/comments/<post>/` (a placeholder subreddit and a
post that doesn't exist), which Sync opened as an empty comments page showing
"Error loading page: 404".

- **Tapping the link** now opens `https://v.redd.it/<media>`, which goes to
  Sync's own Reddit video route instead of the browser. Only the `/link/.../video/...`
  form on Reddit's own hosts changes; every other link is routed as before.
- **Inline preview.** With **Inline image previews** on, the link is drawn as the
  video's first frame with a play button, at the video's aspect ratio within the
  reply width, using the same sizing rules as comment pictures. Tapping the
  preview opens the video as above. `v.redd.it/<media>` links get the same
  preview.
- **How the frame is made.** In the background, the patch reads the video's
  DASH manifest from `v.redd.it`, picks the cheapest rendition with a short side
  of at least 360 px, and downloads at most its first 2 MB (a Range request, with
  no cookies or credentials and no redirects followed). Android's media
  decoder takes the first frame. The JPEG poster is kept in the app's cache
  (`sync-compat-video-posters`, at most 300 files). Two videos are fetched at
  a time.
- **Earlier start and reserved space (rc.2).** Posters are queued as soon as
  Sync parses the comment's `media_metadata`, before the comment is drawn. When
  that metadata gives the video's size, the comment immediately reserves the
  box with a placeholder (a dark frame with the play button, one per shape,
  also cached). The frame is then loaded into the same box when it is ready,
  without waiting for Sync to redraw the comment. If the frame can't be made,
  the placeholder stays and tapping still opens the video.
- **Default square box (rc.3).** When the video's size isn't known on the first
  draw, the comment reserves a square box (suited to portrait phones) with the
  placeholder. When the frame is ready it is loaded into that box: a square
  video fills it; other shapes are drawn centred with dark bars, never
  stretched. Later draws of the same video use its real shape.
- Videos don't play inline. Imgur clips (`.mp4`, `.gifv`) are unchanged.

Verified on the tester's phone: frame extraction, the inline preview, the tap
into Sync's video viewer and, on rc.3, the default box with the frame loading
into it. Not exercised on a phone: Inline image previews off, and regression
spot-checks of comment pictures, GIFs, share links and load more comments.
Reddit's comment JSON couldn't be read directly here (blocked without the
app), so whether `media_metadata` ever carries a comment video's size is
unknown; when it does, that shape is used instead of the square.

## v0.6.3 award data fix

Adds **Sync: handle unavailable award data** for [issue #11](https://github.com/TheLastWallaby/revanced-redditsync/issues/11).
The tester's v0.6.3-rc.1 phone notes are recorded in
`validation-phone-tests.json`.

Sync 23033 reads legacy award fields in two places. Both used to throw when a
field was missing or had changed shape.

- **Post and comment listings.** When an item reports awards, Sync required every
  `all_awardings` entry to be an object with `resized_static_icons` and
  `resized_icons` arrays, and `gildings` to be an object. One entry missing an
  icon array threw from the post/comment parse. These fields are now optional:
  unusable entries and icons are skipped, and the item loads without award icons.
  Icon URLs must be HTTPS on a Reddit image host (`redd.it`, `redditstatic.com`,
  `redditmedia.com`, `reddit.com`). Sync's own icon-size choice is unchanged.
- **Awards sheet** (post menu → Awards, or tapping a comment's awards). Its
  `/api/info` lookup threw on any missing field and reported "Could not load
  gallery". It now separates the cases (v0.6.12 replaces the `/api/info`
  lookup itself; see above):

  | Reddit's response | Sheet shows |
  |---|---|
  | Awards with a name | The awards. Count, description and icons are optional. |
  | `all_awardings` is empty | One row, **No awards listed. Open on Reddit** |
  | `all_awardings` is missing or unsupported | One row, **Award details unavailable. Open on Reddit** |
  | Another item, or no item | "Couldn't find this item's awards on Reddit." |
  | Malformed JSON | "Couldn't read Reddit's award details." |
  | HTTP or network failure | "Couldn't load awards from Reddit (HTTP *n*)." or without a status |

  The Open on Reddit row opens the item's `www.reddit.com` page with Sync's own
  "Open in browser" helper. The link is built only from the item's permalink and
  only if that permalink matches the requested post or comment ID. Without a
  verified permalink, the sheet shows "Reddit lists no awards for this item." or
  "Reddit didn't provide award details for this item." instead.
- Award text no longer crashes the award detail sheet when the description is
  missing. Only awards for the requested item are shown.

Sync 23033 has no native action for giving awards, so the patch adds none.
Viewing and giving Reddit's current awards is left to reddit.com. On the phone,
a post showing a current-style award in Reddit's own app showed no award in
Sync. Reddit appears not to report its current awards in the fields Sync reads,
so the awards sheets and the Open on Reddit row were not exercised on a device.
Reddit's live award fields were not checked directly.

## v0.6.2 inline comment media fix

Adds **Sync: improve inline comment media** for [issue #8](https://github.com/TheLastWallaby/revanced-redditsync/issues/8).
The tester confirmed the v0.6.2-rc.5 phone checks recorded in
`validation-phone-tests.json`.

- Reddit picture links (`preview.redd.it`, `external-preview.redd.it`,
  `i.redd.it`) read `width`/`height` in any parameter order. Missing,
  non-numeric, zero or oversized values no longer reach `Integer.parseInt`.
- When a link has no height, the comment's `media_metadata` supplies the source
  size and MIME type. Sync's parser feeds a small in-memory cache that holds
  media IDs, sizes and types only. The stored comment text is not changed.
- The picture keeps its aspect ratio. It fits the available reply width and is
  not stretched past its native size. Tall images stop at 1.5× the reply
  width. Extreme banners or strips, and boxes that rounding would distort by
  more than 10%, stay links.
- The renderer does not rebuild the URL. See the v0.6.11 correction above:
  Sync's parser must also keep its synthetic `&height` out of signed image URLs.
  The tap target and inline image then both use the original source link.
- WebP and other picture formats are recognized from the parsed path or the
  `format=` value, not from substrings. `format=mp4` renditions are not drawn as
  pictures. Detection only adds to Sync's own list and still respects the
  **Inline image previews** setting. Inline-sized pictures also require that
  setting; with it off, links stay links.
- Reddit comment GIFs (`![gif](giphy|ID)`, which Sync showed as a small
  100px preview beside the URL) and Giphy links (`media.giphy.com/...`,
  `giphy.com/gifs/...`) draw inline at their real aspect ratio and the reply
  width instead of the small preview or link card. Tapping still opens
  the original link. Reddit normally marks comment GIFs
  `{"status": "invalid"}` with no size, so the patch fetches only the
  10-byte GIF header from Giphy's HTTPS CDN in the background (a Range
  request with no cookies or credentials, two at a time, once per GIF per app
  session) to learn the size. A GIF first seen while drawing appears inline
  on its next redraw. Probing and inline GIFs only happen while both **Inline
  image previews** and animated comment images are on; the animated setting
  now also governs Reddit-hosted GIFs. If a probe fails, the card remains.
- Direct Imgur pictures (`i.imgur.com/<id>.gif|jpg|png|webp`) draw inline at
  their real aspect ratio, sized by reading at most the first 64 KB of the
  file (GIF, PNG/APNG, WebP and JPEG headers, including EXIF rotation). Files
  over 8 MB stay a card; Imgur can serve a 10-16 MB GIF even for a `.jpg`
  link. This needs the **Inline image previews** setting, and GIFs also need
  animated comment images. Imgur clips (`.mp4`, `.gifv`) stay cards.
- Giphy `id|width|height` markers with bad sizes fall back to Sync's unsized
  Giphy image instead of throwing.

Not covered: inline playback of comment videos, including Imgur `.mp4` and
`.gifv` clips. Video links stay tappable and open in Sync's existing viewer.
Rotation, very deeply nested replies and the settings-off paths were not
separately checked on a phone.

## v0.6.1 share-link security fix

Adds **Sync: secure Reddit share links** for [issue #1](https://github.com/TheLastWallaby/revanced-redditsync/issues/1).
The inherited ReVanced `/s/` resolver now parses the actual URI before any
authentication or account-startup action. It accepts only HTTPS, the default
port or explicit `443`, no user-info, and a complete `/r/<subreddit>/s/<token>`
path with an optional trailing slash, query and fragment. Allowed hosts are
exactly `reddit.com`, `www.reddit.com`, `old.reddit.com`, `new.reddit.com`,
`sh.reddit.com`, `m.reddit.com` and `np.reddit.com`.

Redirects are handled explicitly, with at most five HEAD requests. Every
destination must pass the same origin validation. Authorization is permanently
dropped after a hop to another origin, including another allowed Reddit host.
Relative redirects work; a validated final post URL is handed to Sync without
another authenticated request. Failures keep the existing `#bypass` browser
fallback for the validated original share URL. The new resolver does not log
URLs, tokens, headers, account data or transport exception details.

The existing token setter and pending-link lifecycle are preserved. The patch
requires the inherited ReVanced resolver from the supported base; it reports
an error if that layout is missing or the security patch is already installed.
CLI 6.0.0 can still save an APK after a patch error: use only output for which
all three named patches report success.

Use a prepared ReVanced-patched Sync 23033 free base with none of this
repository's compatibility patches, and apply all **three** named patches.
Do not layer this cumulative bundle over an existing compatibility-patched APK.

Automated validation runs the 746 stub-transport share-link security checks and
all 92 existing Volley/loopback checks. CI also builds both DEX files and loads
the cumulative RVP with ReVanced CLI. `build-info.json` records the executed Java
suites by fully qualified name, including
`app.revanced.extension.synccompat.SafeSLinksTest`.

The tester confirmed the candidate phone checks listed in
`validation-phone-tests.json`. Account-switch behavior and Android Manager
import/patching remain unverified; these checks are not exhaustive live-service
coverage. `validation-share-links.json` records the earlier
local smoke test with a disposable key; its hashes are historical evidence,
not the candidate release's hashes or proof of update compatibility. The private
APK pipeline separately verifies the prepared base checksum, original signing
identity, preserved classes and unchanged manifest/resources. Its patch-step logs
must also show success for every selected patch.

## Downloads

- [Stable v0.7.5 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.5)
- [Download patches.rvp](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.7.5/patches.rvp)
- [Source ZIP](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.7.5/sync-gallery-comments-compat-v0.7.5-source.zip)
- [SHA256SUMS](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.7.5/SHA256SUMS)
- [build-info.json](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.7.5/build-info.json)
- [Archived v0.7.5-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.5-rc.2)
  and [v0.7.5-rc.1](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.5-rc.1)
- [Previous stable v0.7.4](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.4)
- [Archived v0.7.4-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.4-rc.1)
- [Previous stable v0.7.3](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.3)
- [Archived v0.7.3-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.3-rc.2)
  and [v0.7.3-rc.1](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.3-rc.1)
- [Previous stable v0.7.2](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.2)
- [Archived v0.7.2-rc.2 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.2-rc.2)
  and [v0.7.2-rc.1](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.2-rc.1)
- [Previous stable v0.7.1](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.1)
- [Previous stable v0.7.0](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.7.0)
- [Previous stable v0.6.14](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.14)
  (needs an APK already patched with the official ReVanced Sync patches)

For unpublished changes, download the `patch-bundle-<run>-<attempt>` artifact from
the PR's passing [CI run](https://github.com/TheLastWallaby/revanced-redditsync/actions/workflows/ci.yml).
CI and releases provide the same four outputs: `patches.rvp`, the source ZIP,
`SHA256SUMS` and `build-info.json`. Local builds write them to `output/`.
Old `dist/` files are historical and are not current release artifacts.
Supply your own stock Sync APK, Reddit client ID and original signing key when
applying patches.

## ReVanced Manager remote bundle

Add this JSON URL as a remote patch bundle:

```
https://raw.githubusercontent.com/TheLastWallaby/revanced-redditsync/main/bundles/stable.json
```

In Manager versions with the custom bundle editor, open **Patches**, tap the
**pencil icon**, choose **Add patches** → **Enter URL**, and paste the JSON URL.
The metadata points to the latest stable release's `patches.rvp`. You can also
download that file from Releases and import it as a local patch bundle.

`bundles/latest.json` is also provided and may include future prereleases.
Use the Sync base and patch selections described below. The validated patching
route is the CLI; Manager import and patching still need device verification.

Since v0.7.0 the bundle includes the official ReVanced Sync patches, so in
Manager patch **stock** Sync v23.06.30-13:39 (version code 23033) in one pass:

1. Select only this bundle's patches. Deselect or remove the official ReVanced
   Sync patches: the bundle rejects an APK that already contains ReVanced code.
2. Set **Spoof client**'s `OAuth client ID` to your Reddit client ID, and its
   `Redirect URI` if your Reddit app does not use `http://redditsync/auth`.
3. Check that every selected patch reports success.

Releases up to v0.6.14 instead needed two passes: the official ReVanced Sync
patches first, then this bundle on that output
([issue #70](https://github.com/TheLastWallaby/revanced-redditsync/issues/70)).

## Included patches — cumulative v0.7.5

Official ReVanced Sync patches, ported for build 23033 (see
[v0.7.0](#v070-one-pass-patching-from-stock-sync-issue-70)): **Spoof client**,
**Disable ads**, **Disable Sync for Lemmy bottom sheet**, **Fix /s/ links**,
**Fix Redgifs API**, **Fix post thumbnails**, **Fix video downloads** and
**Use /user/ endpoint** (off by default).

This bundle's own patches:

- **Sync: secure Reddit share links** validates share URLs and redirects before
  sending credentials, as described above.
- **Sync: fix Reddit galleries** loads native Reddit galleries through the
  comment-page endpoint and adapts the post listing for Sync's existing parser,
  leaving unavailable items as placeholders while the rest stay usable. In
  v0.6.12 a gallery link without a post ID fails without any request
  instead of using `/api/info` (issue #48).
- **Sync: load more comments in batches** uses normal comment routes, with at
  most 32 additional requests per tap (four at a time), Reddit rate-limit and
  backoff handling, a 20-second deadline and cancellation, sharing Sync's own
  rate-limit helper. It reuses comments already returned and preserves failed and
  untouched IDs, parent relationships and nested retries. Since v0.6.12 it
  no longer tries Reddit's `/api/morechildren` endpoint first (issue #51), and no
  comment or gallery request adds `raw_json` (issue #55).
- **Sync: improve inline comment media** draws comment pictures, Reddit/Giphy
  GIFs and direct Imgur pictures inline at their real aspect ratio within the
  reply width, as described above.
- **Sync: handle unavailable award data** keeps listings usable when
  award fields are missing and gives the awards sheet accurate messages and an
  Open on Reddit row, as described above. Since v0.6.12 the sheet reads the
  item's comment page instead of `/api/info` (issue #48).
- **Sync: show comment video previews** opens Reddit comment video
  links in Sync's video viewer and draws them as a still frame with a play
  button, as described above.
- **Sync: replace legacy media proxies** resolves Imgur albums, gallery posts,
  album thumbnails and RedGifs enhanced autoplay directly instead of through
  Sync's defunct proxies, as described above.
- **Sync: preserve literal Markdown replacements** preserves dollar signs and
  backslashes in fenced code, spoiler-link labels and emote URLs.
- **Sync: normalize Reddit links** opens equivalent Reddit links at the same
  place in Sync, as described above.
- **Sync: play Reddit videos via DASH** opens
  Reddit-hosted videos through their DASH manifest instead of the unsigned HLS
  playlist Reddit now refuses, as described above.
- **Sync: refresh RedGifs tokens** answers Sync's
  retired RedGifs `/info` lookup locally, and fetches one new RedGifs token when
  RedGifs rejects the base's cached one and retries that request once, as
  described above.

New in [v0.7.1](#v071-retired-media-hosts-random-subreddit-and-media-link-ids):

- **Sync: fix RedGifs link IDs** reads the RedGifs video ID from links with a
  slash before their query or fragment, such as `redgifs.com/watch/<id>/?92`.
  Sync's own reader (`LinkHandler.d`) took the text after the last `/` and
  then cut at `?`/`#`, so it read an empty ID and full screen, peek, downloads
  and enhanced autoplay failed with "Error connecting to Redgifs". The patch
  removes the query, fragment and trailing slashes from RedGifs links before
  Sync reads them; links Sync already read correctly give the same ID.
  `RedgifsLinkPatchTest` runs the reader before and after the patch (a
  hand-written model of it in CI, and Sync's real method locally).
  The rc.1 phone checks did not include such a link.
- **Sync: skip retired media hosts**
  ([issue #57](https://github.com/TheLastWallaby/revanced-redditsync/issues/57))
  shows links to shut-down media services as ordinary link cards that open the
  original URL in the browser, as Sync does for links it doesn't recognize.
  Before, Sync 23033 opened them in its viewers and asked resolvers that no
  longer answer, or loaded previews from the dead hosts:

  | Links | Sync 23033 did | With the patch |
  |---|---|---|
  | vid.me | Viewer (`LinkTypeHelper.H`, `y7.c.H`) | Link card / browser |
  | sli.mg, `i.sli.mg` | Viewer (`y7.c.z`); previews rewritten to `i.sli.mg` | Link card / browser |
  | DeviantArt pages | Viewer, then plain-HTTP `http://backend.deviantart.com/oembed?url=` (`y7.c.e`, `CheckDeviantArtRequest` `r8.a`) | Link card / browser |
  | Tumblr posts | Album viewer, then `api.tumblr.com/v2/blog/` (`y7.c.C`, `TumblrRequest` `r8.j`) | Link card / browser |
  | Eroshare, picsarus links with an image or GIF extension | Image viewer and image/GIF post previews from the dead host; picsarus rewritten to plain-HTTP `http://picsarus.com/<id>.jpg` | Link card / browser |

  The four classifiers for vid.me, sli.mg, DeviantArt and Tumblr posts now
  always answer "no". Sync's media check (`y7.c.j`), post-type helper
  (`SyncTypeHelper.h`, `c8.e.h`) and image-URL helper (`ImageHelper.b`,
  `c8.a.b`) first ask `RetiredMediaHosts`, which matches `vid.me`, `sli.mg`,
  `eroshare.com` and `picsarus.com` and their subdomains over http, https or no
  scheme, in any case. Such a link is not media, a link post stays a link post
  (with Reddit's own thumbnail) and its URL is not rewritten. As a backstop, the
  three resolver requests (`r8.a`, Eroshare's `EroshareGalleryRequest` `r8.c`
  and `r8.j`) get a non-network URL (`sync-compat-retired:<service>`), which
  Sync's network stack rejects before connecting. Sync's own "vid.me" and
  "Tumblr posts" settings no longer have any effect. Imgur, RedGifs, Giphy,
  Gfycat, Streamable, YouTube, Reddit-hosted media, Tumblr media files
  (`*.media.tumblr.com`) and DeviantArt galleries keep Sync's handling.
  `RetiredMediaHostsTest` covers the host table (schemes, subdomains, casing,
  ports, look-alike hosts and other URI schemes). `RetiredMediaPatchTest`
  applies the patch and executes the classifiers, post-type and image-URL
  helpers and request constructors before and after it, and walks the media
  part of Sync's link router (`LinkHelper`, `y7.b.c`) with them: on hand-written
  models in CI, and on Sync's real methods locally. The tester reported the
  rc.1 phone checks as an overall pass.
- **Sync: hide random subreddit**
  ([issue #54](https://github.com/TheLastWallaby/revanced-redditsync/issues/54)).
  Reddit retired its random subreddit. Sync 23033 still offered it, and every
  entry opened its random sheet (`RandomProgressDialogBottomSheet`, `t9.m1`),
  which asked Reddit for `r/random.json` or `r/randnsfw.json`
  (`RandomRequest`, `r8.d`). The patch hides the entries it can and shows
  "Random subreddit is no longer available from Reddit." for the rest, without
  a request:

  | Entry | Sync 23033 code | With the patch |
  |---|---|---|
  | **Random** chip in the front-page toolbar chips | `DefaultSubredditChipGroup` (`chip_random`, `onPreviewsClicked`) | Hidden |
  | **Random** and **Random NSFW** action buttons | `ActionsManager` (`t7.b`) actions 5 and 6 | Hidden, also when already saved in the action list |
  | **Random** and **Random nsfw** in the actions sheet's "Action visibility" menu | `ActionsBottomSheetFragment.showMore` | Hidden |
  | Choosing `random` or `random_nsfw` as a subreddit (for example an entry you added to your own list) | `VerticalPostsFragment.onSubredditSelected` | Message |
  | Tapping an `r/random`, `r/random_nsfw` or `r/randnsfw` link | `LinkHandler.T` (`y7.a.T`) | Message |

  Before, an `r/randnsfw` link opened as an ordinary subreddit, which also asked
  Reddit for `r/randnsfw`; it now shows the message too. As a backstop, the
  random sheet itself shows the message and closes instead of sending
  `RandomRequest`. The stored Random action settings stay but have no effect.
  Other subreddits, links, actions and menu items are unchanged.
  `RandomSubredditPatchTest` runs each entry before and after the patch with
  stand-ins for Android and Sync's services that record what Sync asks for:
  stock Sync opens the sheet and queues `RandomRequest`; the patched entries
  show the message or are hidden, and nothing is queued. It runs on hand-written
  models in CI, and passed on Sync's real methods locally. The tester reported
  the rc.1 phone checks as an overall pass.

New in [v0.7.2](#v072-comment-restore-from-arctic-shift-and-video-download-manifests):

- **Sync: restore comments from Arctic Shift**
  ([issue #52](https://github.com/TheLastWallaby/revanced-redditsync/issues/52)).
  Sync's **Restore comment** action, in the ⋯ More sheet of a `[deleted]` or
  `[removed]` comment (`CommentHolder.onMoreClicked`), asked Pushshift
  (`https://api.pushshift.io/reddit/comment/search/?ids=<id>`), which now serves
  moderators only, so nothing happened. With the patch it asks the
  [Arctic Shift](https://github.com/ArthurHeitmann/arctic_shift) archive instead.

  **The restored author and text come from Arctic Shift, a third-party archive
  of Reddit, not from Reddit.** Arctic Shift only has comments it saved before
  they were deleted; a recent or never-archived comment shows "This comment
  isn't in the Arctic Shift archive." What is sent: one HTTPS `GET` to
  `https://arctic-shift.photon-reddit.com/api/comments/ids?ids=<id>&fields=id,author,body`,
  where `<id>` is the comment's base-36 ID (checked to be 1 to 13 characters
  `0-9a-z`; a `t1_` prefix is removed; anything else is never sent), with
  `Accept: application/json` and `User-Agent: sync_for_reddit`. The request uses
  a client of its own, not Sync's: no Reddit authorization, cookies, tokens or
  other Sync headers, and no redirects are followed. Your IP address is visible
  to Arctic Shift, as to any website.

  | | Sync 23033 | With the patch |
  |---|---|---|
  | Shown when | Sync Ultra (`UltraHelper`, `uc.b.j`) and Sync's remote-config switch `ultra_restore` (`RemoteConfigHelper.e`, `t7.d0.e`, off unless Sync's server turned it on) | Every `[deleted]` or `[removed]` comment. Sync records Ultra only when its retired server sends a purchase message (`MyFirebaseMessagingService` → `UltraHelper.o`), so Ultra can no longer be turned on; the switch governed Sync's retired Pushshift lookup. Both results are overridden for this entry only |
  | Tap (`CommentMoreDialogBottomSheet.r0`, `t9.s`) | Queues `RestoreCommentRequest` (`r8.h`) to Pushshift; it has no error listener, so a failure showed nothing | Builds the same request (Sync's "Restoring comment" message stays), then `ArcticShiftRestore` asks the archive in the background |
  | Archive has the comment | — | The answer is reduced to one `{"data":[{"id","author","body"}]}` entry, the shape Pushshift used, and handed to `RestoreCommentRequest`'s own parser, which stores the author and text and refreshes the thread |
  | Not archived: empty `data`, HTTP 404, malformed or over 256 KiB, or only `[deleted]`/`[removed]` text | — | "This comment isn't in the Arctic Shift archive." |
  | Other HTTP status, redirect, no connection, slower than 10 s per read or 20 s in all | — | "Couldn't reach the Arctic Shift archive. Try again later." |

  Not changed: Sync's parser and comment storage, its other Ultra features, and
  the Ultra settings screen. `ArcticShiftRestoreTest` covers the
  helper against a loopback archive: found, missing, empty, malformed,
  oversized, slow and trickling answers, redirects and other statuses, ID
  validation, and that the archive receives no Authorization or Cookie header.
  `ArcticShiftRestorePatchTest` builds and taps the sheet before and after the
  patch, with the tap going to a loopback archive and through Sync's own parser:
  on hand-written models in CI, and passed on Sync's real methods locally. The
  tester reported the rc.2 phone checks, including restoring a comment and the
  missing-comment message, as an overall pass.
- **Sync: fix video download manifests**
  ([issue #7](https://github.com/TheLastWallaby/revanced-redditsync/issues/7)).
  Applies only to **downloading** a Reddit video (`DownloadRedditVideoJob`);
  playback, including v0.6.14's DASH player, is not changed. Sync asks for
  `https://v.redd.it/<id>/DASHPlaylist.mpd` (`RedditVideoRequest`, `r8.g`) and
  hands two files to the job, which saves the video and, if there is one, the
  audio, then joins them.

  **Why it failed.** `r8.g.parseNetworkResponse` first runs Sync's own manifest
  loop: a namespace-unaware DOM, any AdaptationSet whose text mentions `audio`
  taken as audio, and `Integer.parseInt` on every other Representation's
  `bandwidth`. The bundled official port **Fix video downloads** only replaces
  the two links after that loop. So a missing or invalid `bandwidth`, a missing
  BaseURL or an empty AdaptationSet threw first ("Error grabbing the video
  playlist"). Where the loop survived, the port read only unprefixed
  AdaptationSets with a `contentType`: for `mimeType`-only or namespace-prefixed
  manifests it found nothing, and Sync's `RedditVideoDescription` (`o8.h`) turned
  the missing video into `https://v.redd.it/<id>/null`, a broken file.

  **What the patch changes.** It depends on the port (kept unchanged as the
  upstream copy) and adds code at the start of `r8.g.parseNetworkResponse`, so
  Sync's loop and the port's hook after it are no longer reached.
  `VideoDownloadManifest` chooses the files:

  - The manifest must be well-formed XML with an `MPD` root, at most 1 MiB,
    without a DOCTYPE. Otherwise: "Error grabbing the video playlist", as before.
  - Representations are read by `DashManifest`, the reader now shared with
    comment video posters (`CommentVideo`): any namespace prefix, comments
    skipped, XML references decoded. Video or audio comes from `mimeType` or
    `contentType` on the Representation, else its AdaptationSet; with neither,
    Sync's own rule (a file name with `audio`) and a picture size for video.
    Subtitles and other types are ignored.
  - Each track takes the highest valid `bandwidth`. A missing, zero or invalid
    one ranks below every valid one; video then takes the largest picture.
  - Only single files count. A Representation under a `SegmentTemplate` or
    `SegmentList` (at Period, AdaptationSet or Representation level) cannot be
    saved as one file and is skipped; `SegmentBase` is a single file.
  - BaseURLs are resolved against the manifest URL. Their own query, such as a
    signature, is kept; only HTTPS results are used.

  | Manifest | Before | With the patch |
  |---|---|---|
  | Video and audio | Video and audio (port) | Video and audio |
  | Silent video, such as a GIF-style clip | Video alone | Video alone |
  | `mimeType` instead of `contentType`, or namespace prefixes | `…/null` (broken file) | Video and audio |
  | A missing or invalid `bandwidth` | "Error grabbing the video playlist" | Best valid representation |
  | No video track | A broken `…/null` video plus the audio | "No valid video files found" |
  | `SegmentTemplate` only | "Error grabbing the video playlist" | "No valid video files found" |
  | Malformed XML | "Error grabbing the video playlist" | Same |

  The two messages are Sync's own. `VideoDownloadManifestTest` covers the
  choice, including the real Reddit manifest used by `CommentVideoTest`.
  `VideoDownloadPatchTest` applies the port and then the patch and runs Sync's
  parser and the job's playlist listener (`DownloadRedditVideoJob$b`) on these
  manifests: on hand-written models in CI, and passed on Sync's real methods
  locally, where the "Before" column is what Sync and the port produced. The
  tester reported the rc.2 phone checks, including downloads with and without
  sound, as an overall pass.

New in [v0.7.3](#v073-image-uploads-to-reddit-issue-53):

- **Sync: upload images to Reddit**
  ([issue #53](https://github.com/TheLastWallaby/revanced-redditsync/issues/53)).
  Sync 23033 sent every picked image to Imgur with its own built-in Imgur key
  (`UploadManager.a`, `u8.d.a`, behind an on/off switch it fetched from Sync's
  GitHub). Users see "Failed to upload image"; Imgur answered HTTP 429. With the
  patch, Sync uses Reddit's media-asset upload, as PRAW and Infinity for Reddit do.
  Both of Sync's upload buttons use it: the image post screen (`SubmitFragment`,
  `ma.n`) and the editor's image button (`UploadImageDialogFragment`).

  | Step | Sent to | What is sent |
  |---|---|---|
  | Lease | `https://oauth.reddit.com/api/media/asset.json` | The image's type (`mimetype`, read from the file's own header) and `api_type=json`, as one of Sync's own signed-in requests: Sync attaches its bearer token and User-Agent and refreshes the token as for any Reddit call |
  | Upload | `https://reddit-uploaded-media.s3-accelerate.amazonaws.com/`, Reddit's upload host | Reddit's upload fields, unchanged and in order, then the image. No Reddit token, no cookies, no redirects. Any other address in Reddit's reply (another host, plain HTTP, a port, user-info, a path) is refused before anything is sent |
  | Image post | Sync's own `oauth.reddit.com/api/submit` | `kind=image` with the uploaded image's address instead of a link post. Reddit answers with a websocket on `*.wss.redditmedia.com` that reports the new post; Sync then opens it as before |
  | Image in a comment, reply or text post | `oauth.reddit.com/api/convert_rte_body_format`, then Sync's own `api/comment` or `api/submit` | The editor inserts `![img](<id>)` (`![gif](<id>)` for a GIF) at the cursor instead of Sync's "Insert image" link dialog. When sending, Reddit's own converter turns the whole text into Reddit's rich-text form with the image, and Sync sends that (`richtext_json`) in place of the Markdown (a comment with an empty `text`, as Infinity sends it) |

  - **Types and size:** JPEG, PNG, GIF and WebP, at most 20 MB. Reddit allows
    larger GIFs; the patch reads the image into memory once and keeps one limit.
  - **Time:** 20 s for the lease, 15 s to connect and 30 s per read for the
    upload, 3 minutes for the whole upload, 30 s waiting for Reddit to confirm
    an image post.
  - **Errors keep the draft and say what happened:** not signed in, an unreadable
    file, too large, an unsupported type, a rejected sign-in (HTTP 401), a
    missing permission (HTTP 403 `insufficient_scope`), another refusal, rate
    limiting (HTTP 429), Reddit unavailable (HTTP 5xx), no connection, a
    timeout, an unexpected reply or upload address. Upload errors appear where
    Sync showed "Failed to upload image", and the image stays selected. A
    comment that can't be sent shows Sync's "Error submitting comment"
    notification, whose **Retry** keeps the text, and (since rc.2) the same
    reason as a message on screen, because Android may not show Sync's
    notifications. A refusal includes Reddit's own short explanation.
  - **Comment images need a subreddit that allows them.** Otherwise Reddit
    refuses the comment (`META_MEDIA_TYPE_NOT_ALLOWED`) and the notification
    says "This subreddit doesn't allow images in comments. Remove the image and
    try again." Right after posting, Reddit may show "Processing img …" until
    the image is ready.
  - **Image posts:** if Reddit can't process the image: "Reddit couldn't process
    this image, so the post wasn't created."; if it doesn't confirm the post
    within 30 seconds: "Reddit accepted the image post but didn't confirm it.
    Check your profile before posting again." The submit screen stays open.
  - **Not supported:** images in message replies, private messages and edits.
    A newly uploaded image there stops the send with "Reddit images can only be
    added to new comments, replies and text posts. Remove the image to send
    this." Existing text is never changed.
  - **Unchanged:** link posts, text posts and comments without an uploaded
    image, other submit options (flair, spoiler, NSFW, notifications),
    galleries, how comment media are shown, and the v0.7.1/v0.7.2 patches.
  - **Permissions:** Sync's sign-in already asks for `submit`, `edit`, `read`
    and `identity`, which cover these requests, so **no new sign-in is
    needed**. If Reddit refuses with a permission error anyway, the message
    says "Reddit needs a new permission for image uploads. Sign in to your
    account again, then retry."
  - **No Imgur fallback.** Sync's built-in Imgur key is shared by every install
    and is the one failing; a fallback would also put the image on another
    host without asking. Without this patch Sync keeps its Imgur upload.
  - **Nothing is logged:** no token, upload field, signature, websocket address
    or image data.

  Sources (no live upload was made): PRAW's recorded `submit_image` and
  inline-media exchanges (lease, Reddit upload host reply, websocket, converter);
  Infinity for Reddit's `UploadImageUtils`, `SubmitPost`, `SendComment` and
  `RichTextJSONConverter` (its image lease sends only `mimetype` since
  2026-11-01); [karmakameleon issue #111](https://github.com/dandroid126/karmakameleon/issues/111)
  (comment images through the converter, the bare comment reply,
  `META_MEDIA_TYPE_NOT_ALLOWED`). RedReader 1.24's comment pictures still go to
  Imgur.

  `RedditImageUploadTest` runs the helper against loopback Reddit and upload
  hosts with a test-only token: the exact lease and multipart upload; lease
  errors, upload errors and redirects; refused upload addresses; the token at
  Reddit only; size, type and time limits; the conversion and the adapted
  comment reply; and image posts identified through a scripted websocket.
  `RedditImageUploadPatchTest` runs Sync's upload manager, editor, request
  manager, submit and comment requests before and after the patch: on
  hand-written models in CI, and on Sync's real methods locally. The tester
  reported image posts and comment images working on rc.2 (image posts already
  on rc.1).

New in [v0.7.4](#v074-youtube-links-open-in-the-youtube-app-issue-60):

- **Sync: open YouTube in the YouTube app**
  ([issue #60](https://github.com/TheLastWallaby/revanced-redditsync/issues/60)),
  on by default.

  **Why Sync's player fails.** Sync 23033 plays YouTube links in
  `CustomYouTubeVideoActivity`, built on Google's YouTube Android Player API
  (`YouTubeBaseActivity`, `YouTubePlayerView`) with a key built into Sync. That
  library doesn't fetch the video itself: it binds to a service
  (`com.google.android.youtube.api.service.START`) inside the YouTube app.
  Google retired the API and current YouTube apps no longer provide that
  service, so the library reports `SERVICE_MISSING`: Sync shows **Get YouTube
  App**, then **An error occurred while initializing the YouTube player**, even
  with YouTube installed (reported with YouTube v21.43). Sync switches itself to
  external links only for `SERVICE_DISABLED` or `SERVICE_INVALID`, so it stays
  stuck. Sync sends no request to YouTube for playback (no InnerTube
  `youtubei` call; that string was in ReVanced's shared YouTube code merged into
  pre-v0.7.0 bases and is unused by Sync). Sync's own workaround is to untick
  **Settings → Link handling → YouTube videos**.

  **What the patch changes.** Sync's link router (`LinkHelper.c`, `y7.b.c`)
  sends links its YouTube classifier (`LinkTypeHelper.K`, `y7.c.K`) accepts to
  `LinkHandler.a0` (`y7.a.a0`), which starts the player when that setting is on
  (the default) and otherwise calls `LinkHandler.X`, a plain `ACTION_VIEW`. With
  the patch, `a0` always calls `LinkHandler.X` for a link on a YouTube host, so
  Android opens it in the YouTube app if it is installed and enabled, else in
  the browser (or asks, if you set it up that way). Sync's manifest claims no
  YouTube host, so the link never comes back to Sync. The classifier rejects
  Shorts, `youtube-nocookie.com` and any link containing "playlist", which Sync
  then opened in its browser; the router now also sends single-video links of
  those kinds to `a0`.

  | Link | Sync 23033 | With the patch |
  |---|---|---|
  | `youtube.com/watch?v=…`, `youtu.be/…`, `/live/…`, `m.`, `music.` | Built-in player | YouTube app (`music.youtube.com`: YouTube Music if installed), else browser |
  | `/shorts/…` | Sync's browser setting | YouTube app, else browser |
  | `/embed/…`, `/v/…`, `/e/…`, `youtube-nocookie.com/embed/…` | Player (nocookie: browser setting) | Same, as `https://www.youtube.com/watch?v=…` |
  | Watch link with `list=` (also with "playlist" in it) | Player (with "playlist": browser setting) | YouTube app, playlist kept |
  | Channel, `/c/`, `/user/`, `/clip/`, `/playlist?list=` pages | Sync's browser setting | Unchanged |
  | Other YouTube pages the classifier accepts (`/@name`, search) | Player (failing) | YouTube app, else browser |
  | Look-alikes such as `notyoutube.com` or `user@youtube.com` | Sync's handling | Unchanged |

  The link is handed over as it was, except where the YouTube app could not
  take it: a missing scheme gets `https://`; scheme and host become lower case
  (Android matches link hosts case-sensitively); `m.youtube.com` becomes
  `www.youtube.com`; Reddit's escaped `&amp;` becomes `&`. Embed and nocookie
  links become a standard watch link (or a playlist page for
  `/embed/videoseries?list=`), keeping the video ID, the timestamp (`t=90`,
  `t=90s`, `t=1h2m3s`, or `start=90` as `t=90`), `list` and `index`. Timestamps
  in other links stay as written, which the YouTube app reads.

  - **Settings:** with **YouTube videos** unticked, Sync already opened these
    links externally; that stays so, now with the fixes above. Your browser
    choice no longer applies to Shorts and the other video links above; to use
    the browser for YouTube, disable the YouTube app or set Android's
    **Open by default** for it.
  - **Not changed:** link-card and post thumbnails (`i.ytimg.com`), post types,
    the long-press link sheet (its small YouTube preview uses the same retired
    library and hides itself when it fails; its open action goes through the
    router), Reddit video, RedGifs, Imgur, galleries, link normalization and
    every non-YouTube link. No manifest or resource change. Nothing is logged.
  - **Why not repair the player?** Sync's player has no video stream of its
    own to fix: the retired library delegated playback to the YouTube app. A
    replacement would mean writing a new player screen without resources or
    manifest changes, either reverse-engineering YouTube's stream extraction
    (which breaks repeatedly) or embedding YouTube's iframe player in a WebView
    (refused by videos that disable embedding, age-restricted videos and
    YouTube's embed checks). The YouTube app is the dependable player.

  `YouTubeLinksTest` runs a table of 79 links (normal videos, Shorts, live,
  `youtu.be`, `m.`/`music.`/nocookie/embed forms, `t=` in seconds and `1h2m3s`
  form, `start=`, `list=`, odd casing, missing schemes, escaped `&amp;`, other
  YouTube pages, malformed IDs and look-alike hosts) through the helper.
  `YouTubeAppPatchTest` applies the patch and taps 37 links through Sync's
  router and `a0` before and after it, with the setting on and off, and checks
  where each one goes: on hand-written models in CI, and on Sync's real router,
  handler and classifier locally. `PatchInteractionTest` checks the router in
  either order with the comment-video link hook (fixtures in CI; every patch in
  both orders on the stock APK locally). No phone results were reported for
  rc.1; the owner asked for stable promotion.

New in [v0.7.5](#v075-poll-options-and-reddit-chat-handoff-issues-9-10):

- **Sync: show poll options**
  ([issue #9](https://github.com/TheLastWallaby/revanced-redditsync/issues/9)),
  on by default. Sync 23033 never reads a post's `poll_data`, so a poll post
  showed only its text. With the patch, the poll is added below the post's text
  in the post view:

  | Part | Shown |
  |---|---|
  | **Poll** line | "Voting closes" and the closing date and time (your phone's time zone and format), or "Poll closed"; then the total, such as "1,234 votes", if Reddit sends it |
  | Options | Each option in Reddit's order; "· 12 votes" only where Reddit sends that option's count; "· **your vote**" on your choice if Reddit reports one |
  | No counts | "Reddit hasn't shared the results." when Reddit sent no option counts. Nothing is estimated |
  | **Open poll on Reddit** | `https://www.reddit.com/poll/<post id>`, Reddit's own poll address, opened in your browser (with your browser's Reddit sign-in), where you can vote. Reddit redirects it to `https://www.reddit.com/comments/<post id>` (checked without an account on 2026-11-03; `new.reddit.com` and `sh.reddit.com` end at the same address) |

  - **No voting in Sync.** PRAW, the reference API client, can create polls
    but has no vote call, and this bundle doesn't use Reddit's private web
    endpoints. Vote with **Open poll on Reddit**, then refresh the post.
  - **Display only.** Option text is shown as plain text: it can't add links,
    formatting or HTML. Reddit's escaped `&amp;`, `&lt;`, `&gt;` are decoded as
    Sync decodes post text; control and text-direction characters are removed.
  - **Unchanged:** posts without a poll; a poll whose data is malformed or
    larger than the limits (more than 12 options, an option over 300
    characters, counts over a billion, a closing time after the year 3000); the
    feed preview; the post's Markdown, which Sync uses when you edit your post
    (the poll block is never sent back to Reddit); comments.
  - Sync's **Translate** rebuilds the post's text from its Markdown, so a
    translated poll post shows no poll block until it is reloaded. A post
    already in Sync's cache shows the poll after a refresh.

  **How.** The hook is one call in Sync's listing item parser
  (`PostParserHelper.a`, `c9.c.a`), just before it adds the item's values to
  its result: `PollCompat.apply` appends the block to `selftext_processed`, the
  HTML Sync draws, using only tags Sync's own HTML converter draws (`p`,
  `strong`, `br`, `ul`/`li`, `a`). The patch checks that the parser copies the
  item's JSON and makes its values once, never reassigns them before the add,
  and that no later code branches back to it.

  **The poll link (fixed in rc.2).** Sync's link router (`LinkHelper.c`,
  `y7.b.c`) opens any `reddit.com/poll/` link in a Custom Tab
  (`LinkHandler.W`). Reddit redirects the poll address to the post, and Sync
  handles `reddit.com`, `www`, `old`, `m`, `np` and `amp` addresses, so in
  rc.1 the tab handed the post back to Sync (phone result). Chromium's
  `ExternalNavigationHandler` lets a Custom Tab hand a redirect to the app
  that opened the tab ("FOR_TRUSTED_CALLER"), but keeps a link sent
  explicitly to the browser in the browser ("Launching intent explicitly
  targeted the browser"; [chromium/src `components/external_intents`](https://chromium.googlesource.com/chromium/src/+/main/components/external_intents/android/java/src/org/chromium/components/external_intents/),
  read 2026-11-03). The patch now adds three instructions to that poll branch:
  `PollCompat.openInBrowser` sends the link to your default browser's package
  (or, if the default is Sync or the chooser, the first browser that offers
  Custom Tabs, which Sync's manifest can already see), and skips the Custom
  Tab. With no browser found, the Custom Tab opens as before. This applies to
  every `reddit.com/poll/` link tapped in Sync. On rc.2 the owner reported the
  poll link working on the phone; other browsers than the one tried may differ.

  **Reddit's data.** Poll posts are text posts (`is_self`) whose `poll_data`
  has `options` (`id`, `text`, optional `vote_count`), `voting_end_timestamp`
  (milliseconds), `total_vote_count` and `user_selection` (the option ID you
  voted for), in the same form Sync's OAuth requests receive. Sources:
  PRAW's poll model and its recorded `oauth.reddit.com/comments/<id>` responses
  for closed, voted and new polls
  ([praw-dev/praw@b04c9fb](https://github.com/praw-dev/praw/tree/b04c9fbc872f03846b117e4a3b055cdf4e12c457):
  `praw/models/reddit/poll.py`, `tests/integration/cassettes/TestPollData.*`,
  `TestSubreddit.test_submit_poll.json`); Arctic Shift's 2026 post schema, where
  `vote_count` is optional and `poll_data` can also be `true`, `false` or null
  ([ArthurHeitmann/arctic_shift@7752f2d](https://github.com/ArthurHeitmann/arctic_shift/tree/7752f2d2c4e450a0c333e165a70d270e75b6cfa4):
  `schemas/RS/2026.ts`); Redlib's poll parser, which also treats `vote_count`
  as optional
  ([redlib-org/redlib@ba116c1](https://github.com/redlib-org/redlib/tree/ba116c1186f6b0f9d45f0ae50c827e13eb2f5ea2):
  `src/utils.rs`). GitHub code search finds no `poll_data` handling in
  RedReader or Infinity for Reddit (2026-11-03). **Needs
  a phone check:** exactly when Reddit leaves out the counts (before you vote
  on an open poll is expected, but no public source states it).

  `PollCompatTest` covers the block: open polls with hidden counts, closed
  polls with counts, voted polls, missing optional fields, zero, one and twelve
  options, hostile option text (tags, Markdown, links, quotes, entities,
  direction overrides), malformed and oversized data, unchanged values without
  a poll, and the poll link. `PollPatchTest` runs Sync's parser before and after
  the patch on poll posts, plain and malformed posts, a comment and a filtered
  post, and taps the poll link through Sync's link router (browser found: the
  browser opens and the Custom Tab is skipped; none: the Custom Tab opens as
  before; other links: unchanged): on hand-written models in CI, and on Sync's
  real parser and link router locally (the suite answers Markdown rendering,
  which is native code, app settings and the link openers). `PollCompatTest`
  also covers the browser choice and refuses non-web links.
  `PatchInteractionTest` applied all 28 patches to the stock APK in both name
  orders with identical results (the parser is also changed by the inline
  media, comment video and award patches; the link router by the official
  share-link port, link normalization and the YouTube handoff). Phone results:
  on rc.1 the poll link reopened the post in Sync and ordinary text posts were
  unchanged; on rc.2 open and closed polls worked.

- **Sync: hand off retired messaging to Reddit Chat**
  ([issue #10](https://github.com/TheLastWallaby/revanced-redditsync/issues/10)),
  on by default. Reddit's help pages call Chat "the home for direct messaging"
  and say old private messages are archived read-only: they "cannot be replied
  to", only deleted, reported or copied
  ([How to use Reddit Chat](https://support.reddithelp.com/hc/en-us/articles/39411701270036-How-to-use-Reddit-Chat),
  [private message archive](https://support.reddithelp.com/hc/en-us/articles/39673186812564-Accessing-existing-private-messages-private-message-archive),
  both read on 2026-11-03). Sync 23033's messaging, and what the patch does:

  **Finding the inbox.** Sync's **Inbox** button is one of its optional
  actions and is off unless you turn it on (setting `actions_messaging`,
  default off in Sync 23033's `SettingsSingleton`). Turn it on in Sync's
  actions menu: **Action visibility** > **Inbox** (`ActionsBottomSheetFragment`).
  The patch doesn't change this setting. On rc.2 the owner reported the inbox
  loading once the button was on.

  | Action | Sync code | Route | Reddit now (public sources) | With the patch |
  |---|---|---|---|---|
  | Inbox, unread, messages, comment and post replies, mentions, sent | `OAuthMessagesRequest` (`y8.a0`) | `message/inbox`, `unread`, `messages`, `comments`, `selfreply`, `mentions`, `sent` `.json` | Works: RedReader 1.27 (Sept 2026) reads the inbox; `message/messages` briefly redirected OAuth clients to the login page and Reddit fixed it ([PRAW #2081](https://github.com/praw-dev/praw/issues/2081), Sept 2025) | Unchanged |
  | Unread check for notifications | `OAuthNewMessagesRequest` (`y8.g0`) | `message/unread.json?mark=false` | Works (same listing) | Unchanged |
  | Moderator mail | `OAuthNewModMailRequest` (`y8.h0`); `y8.a0` `moderator`, `moderator/unread` | `message/moderator…` | Not confirmed by a public source (needs a phone check) | Unchanged |
  | Mark one read / all read | `OAuthMarkMessageRequest` (`y8.x`) / `OAuthMarkAllMessagesViewedRequest` (`y8.w`) | `api/read_message` / `api/read_all_messages` | Works: RedReader added both on 2026-09-15 ("there is no longer a public API for marking an item as unread"; Sync has no mark-unread action) | Unchanged |
  | Delete a message, block a user | `OAuthDeleteMessageRequest` (`y8.i`), `OAuthBlockRequest` (`y8.d`) | `api/del_msg`, `api/block_user` | Archived messages "can be deleted" (Reddit Help); API not confirmed | Unchanged |
  | Reply to a comment or post (editor, inbox, notification reply) | `OAuthCommentRequest` (`y8.e`), types 0 and 1 | `api/comment` (`t3_`, `t1_`) | Works | Unchanged |
  | **New private message** | `EditFragment.f4` → `OAuthMessageRequest` (`y8.z`) | `api/compose` | **Refused** with `RESTRICTED_TO_PM`: "User doesn't accept direct messages. Try sending a chat request instead." ([RES #5575](https://github.com/honestbleeps/Reddit-Enhancement-Suite/issues/5575); RES removed its compose feature on 2026-11-02, [RES #5723](https://github.com/honestbleeps/Reddit-Enhancement-Suite/pull/5723), because it "no longer works on Reddit") | Chat handoff for that answer only |
  | **Reply to a private message** (editor or notification reply) | `EditFragment.f4`, `MessageReplyReceiver` → `y8.e`, type 2 | `api/comment` (`t4_`) | Archived messages "cannot be replied to"; Reddit's API answer is not documented (needs a phone check) | Chat handoff only if Reddit answers `RESTRICTED_TO_PM`; any other answer keeps Sync's notification |
  | Message to a subreddit's moderators (to `/r/name`) | `y8.z` | `api/compose` | `to=/r/name` fails with `USER_DOESNT_EXIST`, `to=r/name` works ([PRAW #2092](https://github.com/praw-dev/praw/issues/2092), Dec 2025) | Unchanged (Sync shows Reddit's message); not part of this change |

  **New message refused.** Sync's editor stays open with your text (as Sync
  already does on an error) and a dialog replaces Sync's toast: "Message not
  sent. Reddit now sends direct messages through Reddit Chat. Your message is
  still here, so you can copy it into a chat." with **Open Reddit Chat** and
  **Close**.

  **Reply refused.** Sync closes the editor as soon as you send a reply and,
  unlike for comment replies, saves no draft. With the patch the reply is saved
  as one of Sync's editor drafts ("Draft saved"; Sync offers it the next time an
  editor opens), a toast says "Reddit now sends direct messages through Reddit
  Chat. Old private messages are read-only. Your reply was saved as a draft.",
  and Sync's "Error submitting message" notification says the same, with **Open
  Reddit Chat** and **Open message archive** instead of a **Retry** that Reddit
  would refuse again.

  - **Addresses:** `https://www.reddit.com/chat` and, for the archive,
    `https://www.reddit.com/notifications`: Reddit Help says the archive is "in
    the notifications inbox" (the archive icon at its top) and gives no direct
    link (**needs a phone check**). Both are fixed HTTPS addresses opened with
    Android's normal `ACTION_VIEW`, so the Reddit app opens them if it is
    installed and set to open Reddit links, otherwise the browser. If Sync itself
    would receive the link (it claims every `www.reddit.com` path when you
    enable its supported links), a browser is asked instead.
  - **Only that answer.** The handoff starts only for Reddit's `json.errors`
    entry `RESTRICTED_TO_PM` (also with an error status), on these two routes.
    Messages that send, other Reddit errors (wrong user, rate limit, captcha,
    deleted parent), network failures, a rejected sign-in (HTTP 401, which Sync
    refreshes), rate limiting (429) and server errors (5xx) keep Sync's handling.
  - **Not added:** Reddit Chat in Sync. Reddit's documented API has no Chat
    endpoints ([RedReader #1279](https://github.com/QuantumBadger/RedReader/issues/1279)).
  - Nothing is logged, and no message text goes anywhere but where Sync already
    sent it.

  **How.** Five small hooks, each checked against build 23033 first: the start
  of `OAuthMessageRequest.parseNetworkResponse` turns the refusal into a
  handoff error; the editor's send-message error listener (`EditFragment$d`)
  shows the dialog after Sync dismisses its progress dialog; in
  `OAuthCommentRequest`, the parser's report of `json.errors` and
  `parseNetworkError` note a refused message reply, and its notification
  method `d` saves the draft, changes the text, drops Retry and adds the two
  actions. The image upload patch also edits that parser and error handler;
  both patches give the same result in either order.

  `MessagingHandoffTest` serves Reddit's answers from a loopback server (with a
  test-only token): the refusal for both routes, sent messages and replies,
  other Reddit errors, comment replies and edits, network failures, 401, 403,
  429, 500 and 503, malformed and oversized answers, the fixed HTTPS addresses
  and when a browser is asked. `MessagingHandoffPatchTest` runs Sync's compose
  parser, the editor's error listener, the reply parser, network error handler
  and notification before and after the patch: on hand-written models in CI,
  and on Sync's real methods locally. `PatchInteractionTest` applied all 28
  patches to the stock APK in both name orders with identical results. On rc.2
  the owner reported the inbox showing new direct messages and a reply to a new
  one sent from Sync's inbox, so Reddit still accepts that reply and the
  handoff stays out of the way. Not reported: a new private message (whether
  Reddit refuses it with `RESTRICTED_TO_PM` for Sync's client), a reply to an
  old archived message, comment replies from the inbox, mark read and
  moderator mail.

New since v0.7.5 (not in a release yet):

- **Sync: don't report Amazon links**
  ([issue #101](https://github.com/TheLastWallaby/revanced-redditsync/issues/101)),
  on by default. Every link in a post body or comment is a `CustomUrlSpan`
  (`mb.d`). When you tapped one containing `amazon.com/` and `/dp/` (a product
  page), its `onClick` first queued `AmazonAnalyticsRequest` (`e7.e`): a POST of
  the whole link, as the form field `url`, to
  `https://us-central1-ad-server-f1010.cloudfunctions.net/add`, Sync's former
  ad backend. Nothing switched it off: not Sync Ultra, not the ads check that
  **Disable ads** turns off, not Sync's crash-reporting switch. The request
  carried no Reddit token, but it did send the link (which can include search
  or referral text) and your IP address. Sync never added an affiliate tag to
  the link, so the report did nothing for you.

  With the patch, `onClick` no longer builds or queues that request; the link
  then opens exactly as before, through Sync's link router. As a backstop,
  `AmazonAnalyticsRequest`'s own address is `sync-compat-disabled:amazon`, a
  non-network URL that Sync's network stack rejects before connecting, as for
  the retired-host requests above. Not changed: every other part of
  `onClick` (flair, code, settings and table links, the AMP bypass, Reddit and
  other links), Amazon links that aren't product pages (which Sync never
  reported) and how Amazon links open.

  `AmazonLinkPatchTest` taps links through `onClick` before and after the patch,
  with a recording request queue and link router: before, each Amazon product
  link queues the POST with the link and then opens; after, nothing is queued
  and the same link opens. Amazon search and other-country links, Reddit,
  image, YouTube, Imgur, profile, flair and table taps give the same result
  both times. It also checks that only the five report instructions are
  removed and only the request's address changes: on hand-written models in
  CI, and on Sync's real methods locally. `PatchInteractionTest` applied all
  29 patches to the stock APK in both name orders with identical results.
  Not checked on a phone or with a traffic capture.

- **Sync: fix image peek**, on by default. Long-pressing the thumbnail of a
  text post with a picture showed **Glide failed to grab this image**, while
  the feed thumbnail and the opened post showed the picture. Reddit gives such
  a post no `preview` block, only a thumbnail that is itself a signed
  `preview.redd.it` link (`?width=140&height=140&crop=…&s=…`). Sync asks for
  Reddit's default JSON (no `raw_json`, issue #55), so it stores that link
  with `&amp;` in place of each `&`. Reddit signs the whole query and refuses
  the escaped form (403). The bundled **Fix post thumbnails** port undoes the
  escaping in the feed's image loader, but the peek
  (`ImagePeekDialogFragment`) hands the stored string to Glide itself. Older
  `b.thumbs.redditmedia.com` thumbnails had no query string, so stock Sync
  never needed to.

  With the patch, the peek's picture and GIF loaders (`V3`, `U3`) undo the
  escaping for every post type. For a text post, the peek shows the post
  body's own link to the same picture (matched by Reddit's media ID): the
  full-size signed link the opened post loads, instead of the 140px
  thumbnail. Links Sync's body parser extended with `&height=`, unsigned
  `preview.redd.it` links and video renditions are not used; the thumbnail is
  then shown. Not changed: what the feed shows, the opened post, and the
  peek's other routes (videos, albums, GIF hosts).

  `ImagePeekCompatTest` checks the URL choice. `ImagePeekPatchTest`
  long-presses text posts through the peek's layout listener and calls both
  loaders before and after the patch, recording the URL Glide gets: on
  hand-written models in CI, and on Sync's real methods locally, where stock
  Sync requests the escaped thumbnail and the patched peek the body picture.
  `PatchInteractionTest` applied all 30 patches to the stock APK in both name
  orders with identical results. Not checked on a phone yet.

Version 0.6 treats an ID as completed with no available comment only when both
routes return HTTP 200, identify the expected post, and contain genuinely empty
comment listings. A more marker, unrelated comment, wrong post, unknown shape,
or failed request keeps the ID pending. Empty final continuations use Sync's
existing completion path. Refreshing the post fetches its continuations again.

The loading-details dialog remains available on failures. Its Copy details
button exports fixed labels, numeric counts and statuses, excluding account
tokens, headers, usernames, comment bodies, URLs and actual post/comment IDs.

## Apply the stable RVP

1. Start from **stock** Sync v23.06.30-13:39 (version code 23033, free
   package) with no ReVanced patches. An APK that already contains ReVanced code
   is rejected. (Releases up to v0.6.14 instead needed an APK already patched
   with the official ReVanced Sync patches.)
2. Load the released `patches.rvp` (or `output/patches.rvp` after a local build)
   and apply the default patches: the eight ported official patches except Use
   /user/ endpoint, plus this bundle's twenty. Set Spoof client's
   `OAuth client ID` (and `Redirect URI`/`User-Agent` if needed). Check that
   each patch reports success.
3. Sign the result with your own keystore. Matching the key used by your current
   installation allows an in-place update.

This bundle adds the official Sync fixes and its own gallery/comment fixes,
share-link hardening, inline comment media, award data handling, comment video
previews, direct media resolution, literal Markdown replacements, Reddit link
normalization, DASH video playback and RedGifs token refresh. Its small local
`Logger`/`Utils` replace ReVanced's shared extension; the OkHttp and Sync class
stubs under `src/stubs` are compile-only.

The validated route is **ReVanced CLI 6.0.0 / Patcher 22.0.0**. Android Manager
import/loading has not been tested. Example CLI command from a clone of this
repository, using your own paths for the other files:

```sh
java -jar revanced-cli-6.0.0-all.jar patch \
  -p output/patches.rvp -b \
  -O "OAuth client ID=your-reddit-client-id" -e "Spoof client" \
  --keystore your-signing-key.keystore \
  -o Sync-v23.06.30-compat-v0.7.5.apk \
  Sync-v23.06.30-stock.apk
```

Add `-O "Redirect URI=..."` before `-e "Spoof client"` if your Reddit app uses
another redirect URI, and `-e "Use /user/ endpoint"` to apply that optional patch.

Supply the CLI's password and alias options if your keystore requires them.
`-b` loads this custom unsigned patch bundle. Keep signing keys private.

## Source and building

Automated builds and release publishing are described in
[Build and update workflow](docs/AUTOMATION.md). Pull requests produce a downloadable
cumulative `patches.rvp`, source ZIP, checksums and build information. A separate
private builder can produce your signed app updates using your prepared base and
original signing key.

For a fresh local/Codex environment with Java 17, Python 3.12 and curl:

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 build.py --bootstrap
```

`toolchain.lock.json` pins and verifies downloads. `VERSION` controls release
versioning. The manual `--tools` route below remains available.

- `src/patches/SyncCompatPatches.kt`: standalone ReVanced patch hooks.
- `src/extension/`: the Java runtime helpers.
- `src/stubs/`: compile-only signatures (shared Logger, Sync's Award and Gallery models,
  OkHttp and the inherited RedGifs fix), excluded from the APK.
- `tests/`: JVM/loopback HTTP tests and minimal Android test adapters.
- `tools/UpdateVerifier.java`: APK/code/signature verification.
- `upstream/`: original files from the upstream implementation, including the
  official ReVanced Sync patches (`upstream/revanced-patches-v6.1.0/`) that
  `src/patches/OfficialSyncPatches.kt` ports.
- `changes-from-v0.5.1.diff`: this version's runtime changes.
- `validation.json`: recorded checks, output digest and user test status.
- `validation-share-links.json`: historical local security/smoke-test evidence.
- `validation-phone-tests.json`: tester-reported candidate phone checks.
- `bundles/`: ReVanced Manager remote-bundle metadata.
- `scripts/update_bundle_json.py`: refreshes the metadata from published releases.

Use Java 17, ReVanced CLI 6.0.0 (Patcher 22.0.0), Kotlin compiler 2.4.21,
R8 8.3.37, JSON-java 20240303, Volley 1.2.1 classes.jar from its AAR, and Android
4.1.1.4 android.jar for compile signatures. Dependency jars are not included.

Provide a tools directory with `revanced-cli.jar`, `r8.jar`, `json.jar`,
`android.jar`, `volley.jar`, and `kotlin/kotlinc/lib`, then run:

```sh
python3 build.py --tools /absolute/path/to/tools
```

Add `--apk /path/to/revanced-base.apk --keystore /path/to/your.keystore` to
build and sign an APK using the CLI's default key password settings.

To inspect a locally built test APK, compile `tools/UpdateVerifier.java`
with `revanced-cli.jar` on the classpath, then run:

```sh
java -cp verifier-classes:/path/to/tools/revanced-cli.jar UpdateVerifier \
  /path/to/revanced-base.apk /path/to/patched.apk - build/dex-extension/classes.dex
```

Replace `-` with a previous APK path to additionally require a matching signing
certificate. A disposable-key smoke APK cannot establish update compatibility
with an existing installation.

## Publishing a release

1. Set `VERSION` to an unused candidate version, run the required checks and
   merge the tested PR.
2. Run **Actions → Publish patch release** on `main` with the exact matching tag
   (for example `v0.6.7-rc.1` for a future candidate). The workflow rebuilds, checks the tested
   revision, publishes all four assets and updates the Manager feeds.
3. Manually run **Build my Sync APK** in the provisioned private repository with
   the same candidate tag. Scheduled builds follow stable releases only.
   Inspect every patch result and `verification.json` before installing.
4. Test on the phone. After the candidate is confirmed working and stable
   publication is authorized, set VERSION to the stable version and publish it
   through a separate release-preparation change and release build.

See [the automation guide](docs/AUTOMATION.md) for the full handoff. Existing
release tags and assets are immutable; candidates are not renamed into stable
releases. Private APK links and signing inputs stay out of public release notes.

The stable feed follows the newest published regular release with an uploaded
`patches.rvp`; the latest feed also considers prereleases. Drafts are excluded.

## Validation

92 checks passed using actual Volley 1.2.1 NetworkResponse and HttpURLConnection
against a loopback HTTP server. Tests cover verified empties, more-only results,
HTTP failures, wrong posts, malformed data, retry preservation, batch boundaries,
parent metadata, deduplication, metadata and diagnostic privacy.

The original v0.6.0 APK verifier preserved all 29,148 existing classes, limited
modifications to four intended app classes and limited added classes to the helper DEX.
All 3,189 non-code entries, including the manifest and resources, were identical
apart from signing metadata.

The tester subsequently reported v0.6 working on their phone on 2026-10-30.
That is a manual confirmation for the tested case, not exhaustive Android or
live-service coverage. The original server response bodies were not captured.
The v0.6.1-rc.1 candidate additionally passed the reported share-link, cold-start,
gallery and comment phone checks recorded in `validation-phone-tests.json`.
Stable v0.6.1 rebuilt the same runtime code under its stable version.
The v0.6.2 candidates passed the tester's picture, Giphy and Imgur comment
checks recorded in `validation-phone-tests.json`; stable v0.6.2 rebuilt the
v0.6.2-rc.5 runtime code under its stable version. Stable v0.6.3 rebuilds the
v0.6.3-rc.1 runtime code, which the tester ran on their phone before approving
it; see `validation-phone-tests.json` for what was and wasn't exercised. Stable
v0.6.4 rebuilds the v0.6.4-rc.3 runtime code, which the tester confirmed on
their phone before approving it. Stable v0.6.5 rebuilds the tested v0.6.5-rc.4
runtime code after the tester reported it looking good and authorized promotion.
Stable v0.6.6 rebuilds the v0.6.6-rc.1 runtime code, whose full phone
checklist the tester ran before approving promotion.
Stable v0.6.7 rebuilds the v0.6.7-rc.1 runtime code after the tester's Markdown
preview screenshot and reported gallery, load-more and emote checks passed.
Stable v0.6.8 rebuilds the v0.6.8-rc.1 runtime code after the tester reported
the candidate looking good on their phone.
Stable v0.6.9 rebuilds the v0.6.9-rc.2 runtime code after the tester reported
all six numbered load-more and gallery checks passing.
Stable v0.6.10 rebuilds the v0.6.10-rc.1 runtime code after the tester approved
promotion; only an overall result was given for the link checks.
Stable v0.6.11 rebuilds the v0.6.11-rc.2 runtime code after the tester approved
promotion; only an overall result was given for the inline-picture checks.
Stable v0.6.12 rebuilds the v0.6.12-rc.1 runtime code after the tester reported
load-more and regression checks passing and authorized promotion; two checks were
not tested and the awards sheets were not reachable on the phone.
Stable v0.6.13 rebuilds the v0.6.13-rc.2 runtime code after the tester reported
the video lookup fix looking good and authorized promotion; only an overall result
was given.
Stable v0.6.14 rebuilds the v0.6.14-rc.1 runtime code after the tester reported
Reddit video playback looking good and authorized promotion; only an overall
result was given.
Stable v0.7.0 rebuilds the v0.7.0-rc.1 runtime code after the tester reported
all ten one-pass checks looking good and authorized promotion; only an overall
result was given.
Stable v0.7.1 rebuilds the v0.7.1-rc.1 runtime code after the tester reported
the five retired-host, random-subreddit and regression checks as all good and
authorized promotion; only an overall result was given.
Stable v0.7.2 rebuilds the v0.7.2-rc.2 runtime code after the tester reported
the seven comment-restore, video-download and regression checks as looking good
and authorized promotion; only an overall result was given. On rc.1 the restore
entry was missing (it required Sync Ultra); rc.2 removed that requirement.
Stable v0.7.3 rebuilds the v0.7.3-rc.2 runtime code after the tester reported
post and comment images working and authorized promotion; no individual results
were given for the other checks. On rc.1 a comment with an image failed silently;
rc.2 shows such failures and sends comments as Infinity for Reddit does.
Stable v0.7.4 rebuilds the v0.7.4-rc.1 runtime code after the owner asked for
promotion; no phone results were reported for rc.1's checks.
Stable v0.7.5 rebuilds the v0.7.5-rc.2 runtime code after the owner reported
polls, the inbox and a direct-message reply working and authorized promotion.
On rc.1 the poll link reopened the post in Sync; rc.2 opens it in the browser.
These are checks of the supplied cases, not exhaustive Android/live Reddit tests.
This standalone bundle does not represent a full upstream Gradle build.

## Sharing and license

Share the RVP and this complete source ZIP together, retaining the licenses.
Neither contains an APK, OAuth client ID, private signing key or personal
account data. Dummy authorization strings and privacy markers in tests are
artificial fixtures.

The batch design derives from ReVanced merge request !6999 at commit
`f33036fe57220442e5c58077f9f73d878b158681`, plus local compatibility fixes:
https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/6999

The official Sync patch ports and the included ReVanced extension code derive
from ReVanced Patches v6.1.0 (commit `b632d0f04239d49f14425f86f21784a526594518`,
GPL-3.0): https://gitlab.com/ReVanced/revanced-patches

This repository is licensed under GPL-3.0 (see LICENSE). Existing app code and
dependencies retain their own licenses.
