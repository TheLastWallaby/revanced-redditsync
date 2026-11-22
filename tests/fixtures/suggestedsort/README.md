Hand-written Smali models of the Sync 23033 code "Sync: use subreddit suggested comment sort" edits:

- `OAuthCommentsRequest` (`y8.g`): the constructor that builds the comments URL once with the
  static `f`, and `f` itself (subreddit and post path, `/_/<comment>` for a comment thread,
  `&sort=` left out for the "suggested" sort). No `getUrl` override.
- the part of the listing item parser `PostParserHelper.a` (`c9.c.a`) that stores a post's
  `suggested_sort` (read with `optString`, cleaned with `c8.d.e`, put in the `ContentValues`),
  and the add to the result list.
- `c8.d.e`, the text cleaner (`"null"` or empty becomes null).

They are not the original method bodies. `SuggestedCommentSortPatchTest` runs them before and
after the patch. Its optional APK argument runs the same URL checks on the real `y8.g.f` and the
real parser of a local stock Sync 23033 APK, without making or signing an APK.
