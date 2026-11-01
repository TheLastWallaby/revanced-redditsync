package okhttp3;

public final class Request {
    public HttpUrl url() { return null; }
    public String method() { return null; }
    public String header(String name) { return null; }
    public boolean isHttps() { return false; }
    public Builder newBuilder() { return null; }
    private Request() { }

    public static class Builder {
        public Builder() { }
        public Builder url(String url) { return this; }
        public Builder header(String name, String value) { return this; }
        public Builder removeHeader(String name) { return this; }
        public Builder get() { return this; }
        public Request build() { return null; }
    }
}
