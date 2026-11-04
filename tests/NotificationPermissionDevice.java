package app.revanced.extension.synccompat;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.*;

/**
 * A phone for NotificationPermission (shared by its suites; not a suite): the API level, whether
 * POST_NOTIFICATIONS is granted, Sync's settings (as PreferencesHelper returns them), the app's
 * preference files and a record of what the activity was asked. The activity is a real
 * android.app.Activity subclass made without running android.jar's stub constructors.
 */
public final class NotificationPermissionDevice {
    public int sdk = 33;
    public boolean granted;
    public boolean commitFails;
    public RuntimeException requestFailure;
    public RuntimeException settingsFailure;
    public final MemoryPreferences settings = new MemoryPreferences(this, "sync-settings");
    public final Map<String, MemoryPreferences> files = new TreeMap<>();
    public final List<String> events = new ArrayList<>();

    /** Sync's inbox check setting; null removes it (Sync then uses its default, "3"). */
    public NotificationPermissionDevice inboxCheck(String value) {
        if (value == null) settings.values.remove(NotificationPermission.INBOX_CHECK);
        else settings.values.put(NotificationPermission.INBOX_CHECK, value);
        return this;
    }

    /** Resumes Sync's main screen with the production helper. */
    public void resume() {
        resume(activity());
    }

    public void resume(android.app.Activity activity) {
        NotificationPermission.sdk = sdk;
        t7.z.settings = settings;
        t7.z.failure = settingsFailure;
        NotificationPermission.onResume(activity);
    }

    /** The requests made so far. */
    public List<String> requests() {
        List<String> result = new ArrayList<>();
        for (String event : events) if (event.startsWith("request ")) result.add(event);
        return result;
    }

    public android.app.Activity activity() {
        try {
            Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            FakeActivity activity = (FakeActivity) ((sun.misc.Unsafe) field.get(null)).allocateInstance(FakeActivity.class);
            activity.device = this;
            return activity;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** An activity whose permission, preference and request calls go to the device. */
    public static final class FakeActivity extends android.app.Activity {
        NotificationPermissionDevice device;

        @Override public int checkCallingOrSelfPermission(String permission) {
            device.events.add("check " + permission);
            return device.granted && NotificationPermission.PERMISSION.equals(permission) ? 0 : -1;
        }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            device.events.add("open " + name + " mode=" + mode);
            return device.files.computeIfAbsent(name, n -> new MemoryPreferences(device, n));
        }

        /** Activity.requestPermissions (API 23), which android.jar 4.1 does not declare. */
        public void requestPermissions(String[] permissions, int requestCode) {
            device.events.add("request " + Arrays.toString(permissions) + " code=" + requestCode);
            if (device.requestFailure != null) throw device.requestFailure;
        }
    }

    /** In-memory SharedPreferences that records writes. */
    public static final class MemoryPreferences implements SharedPreferences {
        private final NotificationPermissionDevice device;
        public final String name;
        public final Map<String, Object> values = new TreeMap<>();

        MemoryPreferences(NotificationPermissionDevice device, String name) {
            this.device = device;
            this.name = name;
        }

        @Override public Map<String, ?> getAll() { return new TreeMap<>(values); }
        @Override public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        @Override @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) {
            return values.containsKey(key) ? (Set<String>) values.get(key) : fallback;
        }
        @Override public int getInt(String key, int fallback) { return values.containsKey(key) ? (Integer) values.get(key) : fallback; }
        @Override public long getLong(String key, long fallback) { return values.containsKey(key) ? (Long) values.get(key) : fallback; }
        @Override public float getFloat(String key, float fallback) { return values.containsKey(key) ? (Float) values.get(key) : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        @Override public Editor edit() {
            Map<String, Object> pending = new TreeMap<>();
            Set<String> removed = new TreeSet<>();
            boolean[] clear = {false};
            return new Editor() {
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
                @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                @Override public Editor remove(String key) { removed.add(key); return this; }
                @Override public Editor clear() { clear[0] = true; return this; }
                @Override public boolean commit() {
                    device.events.add("commit " + name + " " + pending + (removed.isEmpty() ? "" : " -" + removed));
                    if (device.commitFails) return false;
                    write();
                    return true;
                }
                @Override public void apply() {
                    device.events.add("apply " + name + " " + pending);
                    write();
                }
                private void write() {
                    if (clear[0]) values.clear();
                    for (String key : removed) values.remove(key);
                    values.putAll(pending);
                }
            };
        }
    }
}
