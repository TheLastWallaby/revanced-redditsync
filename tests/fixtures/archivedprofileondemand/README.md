Hand-written Smali models of the Sync 23033 code "Sync: show profile history from Arctic Shift on
demand" edits or checks: the profile section sheet `ProfileSectionSelectionDialogBottomSheet`
(`t9.l1`), whose `A4` lists the sections (more on your own profile) and whose `r0` posts the chosen
section for the profile's user and closes the sheet; its base classes
`AbstractSelectionDialogBottomSheet` (`t4` adds an item) and `s9.f` (`Y3` is the profile's
username); and the selection item `AbstractSelectionDialogBottomSheet$h` (icon `a`, title `b`,
highlight `q`). They keep the names, signatures, strings and calls the patch checks, but not the
original bodies.

They contain no account data, client credentials or tokens.

`ArchivedProfileOnDemandPatchTest` executes them before and after the patch, with Sync's app
services (event bus, account, the sheet's list) recorded by the test. Its optional APK argument runs
the same checks on the real methods of a local stock Sync 23033 APK without making or signing an APK.
