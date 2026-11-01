package okhttp3;

import java.io.Closeable;

public final class Response implements Closeable {
    public int code() { return 0; }
    public boolean isSuccessful() { return false; }
    public String header(String name) { return null; }
    public Request request() { return null; }
    public ResponseBody body() { return null; }
    @Override public void close() { }
    private Response() { }
}
