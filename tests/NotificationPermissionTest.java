package app.revanced.extension.synccompat;

import java.util.*;

/**
 * Checks when NotificationPermission asks for POST_NOTIFICATIONS on a resumed main screen: once,
 * on Android 13 or later, while Sync's inbox checks are not Off and the permission is not granted,
 * with the "asked" flag in the bundle's own preferences file written first. Never on Android 12
 * or earlier, with inbox checks Off or unreadable, when granted, or a second time; Sync's settings
 * are only read; failures are swallowed.
 */
public final class NotificationPermissionTest {
    private static final String REQUEST = "request [android.permission.POST_NOTIFICATIONS] code=" + NotificationPermission.REQUEST_CODE;
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        constants();
        asksOnce();
        sdkLevels();
        inboxSettings();
        granted();
        alreadyAsked();
        failures();
        System.out.println("PASS: " + checks + " notification permission checks (Android 13+ only, inbox checks on, not granted, once per install)");
    }

    private static void constants() {
        equal("android.permission.POST_NOTIFICATIONS", NotificationPermission.PERMISSION, "permission");
        equal("message_preference_new", NotificationPermission.INBOX_CHECK, "Sync's inbox check setting");
        equal("3", NotificationPermission.INBOX_CHECK_DEFAULT, "Sync's default (every 60 minutes)");
        equal("MessagingService", NotificationPermission.INBOX_CHECK_TAG, "tag Sync passes to PreferencesHelper");
        check(NotificationPermission.REQUEST_CODE > 0 && NotificationPermission.REQUEST_CODE <= 0xffff
                && NotificationPermission.REQUEST_CODE != 10, "request code fits FragmentActivity and differs from onboarding's 10");
        // Sync names account preference files after lower-case Reddit usernames ([A-Za-z0-9_-]).
        check(!NotificationPermission.PREFERENCES.matches("[a-z0-9_-]+"), "bundle file cannot be an account's preferences");
    }

    private static void asksOnce() {
        NotificationPermissionDevice phone = new NotificationPermissionDevice().inboxCheck("3");
        Map<String, Object> settings = new TreeMap<>(phone.settings.values);
        t7.z.tags.clear();
        phone.resume();
        equal(List.of(
                "check android.permission.POST_NOTIFICATIONS",
                "open " + NotificationPermission.PREFERENCES + " mode=0",
                "commit " + NotificationPermission.PREFERENCES + " {notification_permission_asked=true}",
                REQUEST), phone.events, "first resume: flag written, then the request");
        equal(List.of("MessagingService"), t7.z.tags, "Sync's settings read through PreferencesHelper");
        equal(Map.of(NotificationPermission.ASKED, true), phone.files.get(NotificationPermission.PREFERENCES).values, "flag stored");
        equal(Set.of(NotificationPermission.PREFERENCES), phone.files.keySet(), "only the bundle's file is opened");
        equal(settings, phone.settings.values, "Sync's settings unchanged");
        check(phone.events.stream().noneMatch(e -> e.contains("sync-settings")), "Sync's settings never written");

        // Denied, or dismissed: the next resumes (the dialog itself pauses and resumes the screen) ask nothing.
        for (int n = 0; n < 3; n++) phone.resume();
        equal(List.of(REQUEST), phone.requests(), "asked once only");
        // Granted later: also nothing.
        phone.granted = true;
        phone.resume();
        equal(List.of(REQUEST), phone.requests(), "nothing after granting");
        // A new activity instance in the same install: still the stored flag.
        phone.granted = false;
        phone.resume(phone.activity());
        equal(List.of(REQUEST), phone.requests(), "flag survives a new activity");
        check(phone.events.stream().noneMatch(e -> e.startsWith("apply")), "flag written synchronously");
    }

    private static void sdkLevels() {
        for (int sdk : new int[] {16, 23, 26, 29, 30, 31, 32}) {
            NotificationPermissionDevice phone = new NotificationPermissionDevice();
            phone.sdk = sdk;
            phone.resume();
            equal(List.of(), phone.events, "Android API " + sdk + ": nothing checked, opened or asked");
            check(phone.files.isEmpty(), "Android API " + sdk + ": no flag");
        }
        for (int sdk : new int[] {33, 34, 35, 36}) {
            NotificationPermissionDevice phone = new NotificationPermissionDevice();
            phone.sdk = sdk;
            phone.resume();
            equal(List.of(REQUEST), phone.requests(), "Android API " + sdk + ": asked");
        }
    }

    private static void inboxSettings() {
        // Sync's own rule (MessageSchedulingHelper.g): parseInt(value) > 0; missing means "3".
        Object[][] cases = {
            {"0", false}, {"-1", false}, {"", false}, {"off", false}, {" 3", false}, {"3.0", false},
            {"1", true}, {"2", true}, {"3", true}, {"4", true}, {"5", true}, {"6", true}, {"7", true}, {"+3", true}, {null, true},
        };
        for (Object[] row : cases) {
            NotificationPermissionDevice phone = new NotificationPermissionDevice().inboxCheck((String) row[0]);
            phone.resume();
            boolean asks = (Boolean) row[1];
            equal(asks ? List.of(REQUEST) : List.of(), phone.requests(), "inbox check " + row[0]);
            if (!asks) {
                check(phone.files.isEmpty(), "inbox check " + row[0] + ": no flag, so turning checks on later still asks");
                check(phone.events.isEmpty(), "inbox check " + row[0] + ": permission not even checked");
            }
            equal(asks, NotificationPermission.inboxChecksOn(phone.settings), "inboxChecksOn " + row[0]);
        }
        // A value Sync never writes (not a string) counts as off.
        NotificationPermissionDevice odd = new NotificationPermissionDevice();
        odd.settings.values.put(NotificationPermission.INBOX_CHECK, 3);
        odd.resume();
        equal(List.of(), odd.requests(), "non-string setting");
        check(!NotificationPermission.inboxChecksOn(null), "no settings: off");

        // Off at first, then turned on: asked then.
        NotificationPermissionDevice later = new NotificationPermissionDevice().inboxCheck("0");
        later.resume();
        later.inboxCheck("3").resume();
        equal(List.of(REQUEST), later.requests(), "asked once checks are turned on");
    }

    private static void granted() {
        NotificationPermissionDevice phone = new NotificationPermissionDevice();
        phone.granted = true;
        phone.resume();
        equal(List.of("check android.permission.POST_NOTIFICATIONS"), phone.events, "granted: only checked");
        check(phone.files.isEmpty(), "granted: no flag");
        // Revoked later in system settings: asked then, once.
        phone.granted = false;
        phone.resume();
        phone.resume();
        equal(List.of(REQUEST), phone.requests(), "revoked later: asked once");
    }

    private static void alreadyAsked() {
        NotificationPermissionDevice phone = new NotificationPermissionDevice();
        phone.files.put(NotificationPermission.PREFERENCES, new NotificationPermissionDevice.MemoryPreferences(phone,
                NotificationPermission.PREFERENCES));
        phone.files.get(NotificationPermission.PREFERENCES).values.put(NotificationPermission.ASKED, true);
        phone.resume();
        equal(List.of(), phone.requests(), "flag already set");
        check(phone.events.stream().noneMatch(e -> e.startsWith("commit")), "flag not rewritten");
    }

    private static void failures() {
        // The flag cannot be written: no request (it could not be recorded).
        NotificationPermissionDevice unwritable = new NotificationPermissionDevice();
        unwritable.commitFails = true;
        unwritable.resume();
        equal(List.of(), unwritable.requests(), "unwritable flag: no request");

        // The request throws: swallowed, and the flag stays so it is not retried.
        NotificationPermissionDevice refused = new NotificationPermissionDevice();
        refused.requestFailure = new IllegalStateException("not attached");
        refused.resume();
        refused.requestFailure = null;
        refused.resume();
        equal(List.of(REQUEST), refused.requests(), "failed request not repeated");

        // Sync's preferences throw: swallowed, nothing asked.
        NotificationPermissionDevice broken = new NotificationPermissionDevice();
        broken.settingsFailure = new IllegalStateException("no account");
        broken.resume();
        equal(List.of(), broken.events, "settings failure: nothing asked");
        broken.settingsFailure = null;

        // No settings at all, and no activity.
        t7.z.settings = null;
        NotificationPermission.sdk = 33;
        NotificationPermission.onResume(null);
        check(true, "null activity ignored");
    }
}
