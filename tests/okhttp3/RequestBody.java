package okhttp3;

import java.nio.charset.StandardCharsets;

/** Test stand-in for OkHttp's RequestBody: buffered bytes and a media type. */
public abstract class RequestBody {
    abstract byte[] bytes();

    /** The Content-Type a call sends for this body, or null. */
    String contentType() { return null; }

    public static RequestBody create(String content, MediaType type) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        return new RequestBody() {
            @Override byte[] bytes() { return data; }
        };
    }

    public static RequestBody create(byte[] content, MediaType type) {
        byte[] data = content.clone();
        return new RequestBody() {
            @Override byte[] bytes() { return data; }
            @Override String contentType() { return type == null ? null : type.toString(); }
        };
    }
}
