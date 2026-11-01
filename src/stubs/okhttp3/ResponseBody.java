package okhttp3;

import java.io.Closeable;
import java.io.InputStream;

public abstract class ResponseBody implements Closeable {
    public abstract long contentLength();
    public final InputStream byteStream() { return null; }
    @Override public void close() { }
    public static ResponseBody create(String content, MediaType type) { return null; }
}
