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

    public static class Builder {
        public Builder() { }
        public Builder request(Request request) { return this; }
        public Builder protocol(Protocol protocol) { return this; }
        public Builder code(int code) { return this; }
        public Builder message(String message) { return this; }
        public Builder header(String name, String value) { return this; }
        public Builder body(ResponseBody body) { return this; }
        public Response build() { return null; }
    }
}
