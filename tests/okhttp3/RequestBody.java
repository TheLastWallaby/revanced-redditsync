package okhttp3;

import java.nio.charset.StandardCharsets;

/** Test stand-in for OkHttp's RequestBody. */
public abstract class RequestBody {
    abstract byte[] bytes();

    public static RequestBody create(String content, MediaType type) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        return new RequestBody() {
            @Override byte[] bytes() { return data; }
        };
    }
}
