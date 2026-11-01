The Smali fixtures are build-23033 method bodies from the supported prepared base:
`y7.c.q` (gallery link classifier) unchanged, `y7.b.c` (LinkHelper) with its
original signature, register count, share-link hook and gallery branch, and
`MainActivity.onCreate` up to the cold-start link handoff. They contain no
account data or credentials.

`LinkPatchTest` applies the compiled production patch, checks each inserted hook,
executes the patched gallery classifier and the start of the patched LinkHelper,
and checks duplicate and unsupported-layout rejection. Its optional APK argument
runs the same checks against a local base's complete methods without making or
signing an APK. `app.revanced.extension.synccompat.LinkNormalizerTest` covers the
normalizer itself. Neither test exercises Android intents or live Reddit.
