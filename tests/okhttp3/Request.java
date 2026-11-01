package okhttp3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test stand-in for OkHttp's Request; header names are case-insensitive. */
public final class Request {
    private final HttpUrl url;
    private final String method;
    private final List<String[]> headers;
    private final RequestBody body;

    private Request(Builder builder) {
        url = builder.url;
        method = builder.method;
        headers = Collections.unmodifiableList(new ArrayList<>(builder.headers));
        body = builder.body;
    }

    public HttpUrl url() { return url; }
    public String method() { return method; }
    public boolean isHttps() { return url.isHttps(); }
    RequestBody body() { return body; }
    List<String[]> headerList() { return headers; }

    public String header(String name) {
        String value = null;
        for (String[] header : headers) if (header[0].equalsIgnoreCase(name)) value = header[1];
        return value;
    }

    public Builder newBuilder() { return new Builder(this); }

    public static class Builder {
        private HttpUrl url;
        private String method = "GET";
        private final List<String[]> headers = new ArrayList<>();
        private RequestBody body;

        public Builder() { }

        Builder(Request request) {
            url = request.url;
            method = request.method;
            headers.addAll(request.headers);
            body = request.body;
        }

        public Builder url(String url) { this.url = HttpUrl.get(url); return this; }
        public Builder header(String name, String value) { removeHeader(name); return addHeader(name, value); }
        public Builder addHeader(String name, String value) { headers.add(new String[]{name, value}); return this; }
        public Builder removeHeader(String name) { headers.removeIf(h -> h[0].equalsIgnoreCase(name)); return this; }
        public Builder get() { method = "GET"; body = null; return this; }
        public Builder method(String method, RequestBody body) { this.method = method; this.body = body; return this; }

        public Request build() {
            if (url == null) throw new IllegalStateException("url == null");
            return new Request(this);
        }
    }
}
