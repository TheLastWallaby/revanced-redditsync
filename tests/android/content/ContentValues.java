package android.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Minimal JVM stand-in for Android's ContentValues: an ordered map of column values. */
public final class ContentValues {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public ContentValues() {
    }

    public void put(String key, String value) { values.put(key, value); }
    public void put(String key, Integer value) { values.put(key, value); }
    public void put(String key, Long value) { values.put(key, value); }
    public void put(String key, Boolean value) { values.put(key, value); }
    public void put(String key, Double value) { values.put(key, value); }
    public void putNull(String key) { values.put(key, null); }

    public Object get(String key) { return values.get(key); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public int size() { return values.size(); }
    public Set<String> keySet() { return values.keySet(); }

    public String getAsString(String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    public Integer getAsInteger(String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    /** A copy of the values, for comparing snapshots in tests. */
    public Map<String, Object> asMap() { return new LinkedHashMap<>(values); }

    @Override public boolean equals(Object other) {
        return other instanceof ContentValues && ((ContentValues) other).values.equals(values);
    }
    @Override public int hashCode() { return values.hashCode(); }
    @Override public String toString() { return values.toString(); }
}
