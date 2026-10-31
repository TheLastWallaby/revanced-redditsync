The Smali fixtures are the original build-23033 `y8.q` (OAuthGalleryRequest)
and `o8.b` (Gallery) method bodies from the supported prepared base. They contain
no account data or credentials. `GalleryPatchTest` applies the compiled production
patch and checks its hook, cache handling, branches, register bounds and types, duplicate
rejection and DEX serialization. Its optional APK argument applies those checks
directly to a local base, without making or signing an APK.

`app.revanced.extension.synccompat.GalleryCompatTest` executes the production
parser and metadata-refresh logic with real Volley types and loopback HTTP. These
tests do not execute the Android gallery UI or establish live Reddit capability.
