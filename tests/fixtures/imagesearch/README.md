Hand-written Smali model of the Sync 23033 code "Sync: fix image search" edits:
`ImageViewerFragment.onSearchCliced`, the handler of the full-screen image viewer's search
button (`image_link_search`), which opens Google's old search-by-image address with the image
URL (`u0`) appended unencoded through `LinkHandler.X` (`y7.a.X`). It keeps the name, register
layout, field, strings and calls the patch checks. A second button handler
(`onOpenExternally`, simplified) shows that other buttons are not changed. They are not the
original method bodies.

`ImageSearchPatchTest` executes them before and after the patch. Its optional APK argument runs
the same checks on the real methods of a local stock Sync 23033 APK without making or signing
an APK.
