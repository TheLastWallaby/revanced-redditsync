package okhttp3;

/** Test stand-in for OkHttp's MediaType. */
public final class MediaType {
    private final String value;

    private MediaType(String value) { this.value = value; }

    public static MediaType get(String value) { return new MediaType(value); }

    @Override public String toString() { return value; }
}
