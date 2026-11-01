# revanced-redditsync

Unofficial ReVanced compatibility patches for **Sync for Reddit
v23.06.30-13:39**, version code **23033**, free package
`com.laurencedawson.reddit_sync`.

**Stable v0.6.8** bounds "load more comments": each tap makes at most 32 extra
requests, follows Reddit's rate limits, reuses comments already returned and
stops when Sync cancels the load. It rebuilds the runtime code from the
phone-tested v0.6.8-rc.1 candidate, retaining v0.6.7's literal Markdown
replacements, v0.6.6's direct media resolution, v0.6.5's Imgur comment video
posters, v0.6.4's Reddit video support, v0.6.3's award data, v0.6.2's inline
comment media and v0.6.1's share-link security fixes.
This is a community/local bundle rather than an official ReVanced release.
Compatibility with Pro/dev packages, other Sync builds, or all account/post
combinations has not been established.

## Unreleased: load-more requests share Sync's rate helper (issue #3)

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
a recording stand-in for the helper. Not verified: phone behavior against Sync's
real SharedPreferences-backed helper.

## v0.6.9-rc.1 gallery resilience (issue #6)

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
Android gallery rendering, authenticated Reddit responses and expired media on
a phone remain unverified. This candidate is for phone testing; stable remains
v0.6.8 until those checks pass and stable publication is authorized.

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
  is fetched on its own and appears after the batch results.

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
| Enhanced autoplay: RedGifs | `ap.syncforreddit.com/image?url=redgifs-…` | Sync's own RedGifs resolver (with the base's RedGifs fix), then the RedGifs video. Errors and unexpected results use Sync's ordinary autoplay source. |
| Enhanced autoplay: Gfycat | `ap.syncforreddit.com/image?url=gfycat-…` | Sync's ordinary autoplay source, or no autoplay when there is none. |

- Imgur requests use fixed HTTPS Imgur hosts and validated IDs, current
  `/gallery/title-words-ID` links included. Media links are rebuilt as
  `https://i.imgur.com/<id>.<ext>` from Imgur's own list; nothing guesses file
  extensions. The cover lookup follows no redirects and sends no cookies or
  credentials. Sync's album requests keep their existing `sync_for_reddit`
  User-Agent and send no Reddit authorization.
- RedGifs results are used only when they are HTTPS `.mp4` links on a
  `redgifs.com` host. The RedGifs token stays inside Sync's RedGifs requests.
- Not changed: Sync's `ap.syncforreddit.com/stats.json` and
  `todo.syncforreddit.com` icon/licence links, which are not media proxies.

`MediaProxyCompatTest` covers the ID parsing, the adapted album and item
responses, album covers, website and Gfycat fallbacks, RedGifs autoplay and a
loopback HTTP server (redirects, size limit, no cookies or credentials). The
parsers were also run against 39 live Imgur album embed pages (278 items, all
matching Imgur's own counts) and live single-item pages. The tester reported
running every check on the v0.6.6-rc.1 phone checklist (Imgur albums and
gallery links, album link-card thumbnails, website/YouTube link cards, RedGifs
enhanced autoplay and the gallery/comment-media regression check) and approved
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
  gallery". It now separates the cases:

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
- The URL is never rebuilt. The old `&height` slice that dropped the signed
  `s=` parameter is gone, and the tap target stays the original link.
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

- [Stable v0.6.8 release](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.8)
- [Download patches.rvp](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.6.8/patches.rvp)
- [Source ZIP](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.6.8/sync-gallery-comments-compat-v0.6.8-source.zip)
- [SHA256SUMS](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.6.8/SHA256SUMS)
- [build-info.json](https://github.com/TheLastWallaby/revanced-redditsync/releases/download/v0.6.8/build-info.json)
- [Archived v0.6.8-rc.1 candidate](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.8-rc.1)
- [Previous stable v0.6.7](https://github.com/TheLastWallaby/revanced-redditsync/releases/tag/v0.6.7)

For unpublished changes, download the `patch-bundle-<run>-<attempt>` artifact from
the PR's passing [CI run](https://github.com/TheLastWallaby/revanced-redditsync/actions/workflows/ci.yml).
CI and releases provide the same four outputs: `patches.rvp`, the source ZIP,
`SHA256SUMS` and `build-info.json`. Local builds write them to `output/`.
Old `dist/` files are historical and are not current release artifacts.
Supply your own prepared base APK and original signing key when applying patches.

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

## Included patches — cumulative v0.6.8

- **Sync: secure Reddit share links** validates share URLs and redirects before
  sending credentials, as described above.
- **Sync: fix Reddit galleries** loads native Reddit galleries through the
  comment-page endpoint and adapts the post listing for Sync's existing parser.
- **Sync: load more comments in batches** uses normal comment routes, with at
  most 32 additional requests per tap (four at a time), Reddit rate-limit and
  backoff handling, a 20-second deadline and cancellation. It reuses comments
  already returned and preserves failed and untouched IDs, parent relationships
  and nested retries.
- **Sync: improve inline comment media** draws comment pictures, Reddit/Giphy
  GIFs and direct Imgur pictures inline at their real aspect ratio within the
  reply width, as described above.
- **Sync: handle unavailable award data** keeps listings usable when
  award fields are missing and gives the awards sheet accurate messages and an
  Open on Reddit row, as described above.
- **Sync: show comment video previews** opens Reddit comment video
  links in Sync's video viewer and draws them as a still frame with a play
  button, as described above.
- **Sync: replace legacy media proxies** resolves Imgur albums, gallery posts,
  album thumbnails and RedGifs enhanced autoplay directly instead of through
  Sync's defunct proxies, as described above.
- **Sync: preserve literal Markdown replacements** preserves dollar signs and
  backslashes in fenced code, spoiler-link labels and emote URLs.

Version 0.6 treats an ID as completed with no available comment only when both
routes return HTTP 200, identify the expected post, and contain genuinely empty
comment listings. A more marker, unrelated comment, wrong post, unknown shape,
or failed request keeps the ID pending. Empty final continuations use Sync's
existing completion path. Refreshing the post fetches its continuations again.

The loading-details dialog remains available on failures. Its Copy details
button exports fixed labels, numeric counts and statuses, excluding account
tokens, headers, usernames, comment bodies, URLs and actual post/comment IDs.

## Apply the stable RVP

1. Select a **ReVanced-patched Sync 23033 free APK** with your usual OAuth/client
   configuration and the ReVanced shared extension already present.
2. Use a base that has **none of this bundle's earlier compatibility patches**.
   Do not reapply to an APK already containing any earlier compatibility bundle.
3. Load the released `patches.rvp` (or `output/patches.rvp` after a local build)
   and apply all eight named patches (seven in v0.6.6, six in v0.6.4 and v0.6.5,
   five in v0.6.3, four in v0.6.2, three in v0.6.1). Check that each reports success.
4. Sign the result with your own keystore. Matching the key used by your current
   installation allows an in-place update.

This bundle adds the gallery/comment fixes, share-link hardening, inline
comment media, award data handling, comment video previews, direct media
resolution and literal Markdown replacements. It expects
the existing ReVanced shared Logger class; its source stub is for compilation only. It does not
supply OAuth configuration or unrelated Sync patches such as RedGifs fixes.

The validated route is **ReVanced CLI 6.0.0 / Patcher 22.0.0**. Android Manager
import/loading has not been tested. Example CLI command from a clone of this
repository, using your own paths for the other files:

```sh
java -jar revanced-cli-6.0.0-all.jar patch \
  -p output/patches.rvp -b \
  --keystore your-signing-key.keystore \
  -o Sync-v23.06.30-compat-v0.6.6.apk \
  Sync-v23.06.30-revanced-base.apk
```

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
- `src/stubs/`: compile-only signatures (shared Logger, Sync's Award and Gallery models),
  excluded from the APK.
- `tests/`: JVM/loopback HTTP tests and minimal Android test adapters.
- `tools/UpdateVerifier.java`: APK/code/signature verification.
- `upstream/`: original files from the upstream implementation.
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

The combined source uses GPL-3.0. Original authored compatibility code also
retains its MIT notice in LICENSE-MIT. Existing app code and dependencies
retain their own licenses.
