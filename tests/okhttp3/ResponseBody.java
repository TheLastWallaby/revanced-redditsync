package okhttp3;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test stand-in for OkHttp's ResponseBody: buffered bytes, with closing tracked per call. */
public abstract class ResponseBody implements Closeable {
    private Runnable onClose;
    private boolean closed;

    public abstract long contentLength();
    abstract byte[] data();

    public final InputStream byteStream() { return new ByteArrayInputStream(data()); }
    public final byte[] bytes() { return data(); }
    public final String string() { return new String(data(), StandardCharsets.UTF_8); }

    void onClose(Runnable action) { onClose = action; }
    boolean isClosed() { return closed; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (onClose != null) onClose.run();
    }

    static ResponseBody of(byte[] data, long contentLength) {
        return new ResponseBody() {
            @Override public long contentLength() { return contentLength; }
            @Override byte[] data() { return data; }
        };
    }

    public static ResponseBody create(String content, MediaType type) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        return of(data, data.length);
    }
}
