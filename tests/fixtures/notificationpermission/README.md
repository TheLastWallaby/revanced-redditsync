Hand-written Smali models of the Sync 23033 code "Sync: ask for notification permission" edits
or checks: `MainActivity` (the main screen, which has no `onResume` of its own),
`BaseMaterialActivity` and `BaseActivity` (whose `onResume` it inherits), `HomeActivity` (the
launcher, which opens `MainActivity` or the onboarding), `OnboardingActivity` (reduced to its
notification-permission request, the only one in Sync), `MessageSchedulingHelper.g` (`z7.b`,
Sync's inbox check rule for `message_preference_new`) and `PreferencesHelper.f` (`t7.z`). They
keep the names, signatures, superclasses, strings and calls the patch checks. They are not the
original method bodies.

`NotificationPermissionPatchTest` applies the patch to them and resumes the main screen before
and after it. Its optional APK argument runs the same checks on the real classes of a local
stock Sync 23033 APK without making or signing an APK.
