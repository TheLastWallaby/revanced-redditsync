package okhttp3;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/** Test stand-in for OkHttp's Response. */
public final class Response implements Closeable {
    private final Request request;
    private final int code;
    private final String message;
    private final List<String[]> headers;
    private final ResponseBody body;

    private Response(Builder builder) {
        request = builder.request;
        code = builder.code;
        message = builder.message;
        headers = new ArrayList<>(builder.headers);
        body = builder.body;
    }

    public int code() { return code; }
    public String message() { return message; }
    public boolean isSuccessful() { return code >= 200 && code < 300; }
    public Request request() { return request; }
    public ResponseBody body() { return body; }

    public String header(String name) {
        String value = null;
        for (String[] header : headers) if (header[0].equalsIgnoreCase(name)) value = header[1];
        return value;
    }

    @Override public void close() {
        if (body == null) throw new IllegalStateException("response is not eligible for a body and must not be closed");
        body.close();
    }

    public static class Builder {
        private Request request;
        private int code = -1;
        private String message = "";
        private final List<String[]> headers = new ArrayList<>();
        private ResponseBody body;

        public Builder() { }
        public Builder request(Request request) { this.request = request; return this; }
        public Builder protocol(Protocol protocol) { return this; }
        public Builder code(int code) { this.code = code; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder header(String name, String value) { headers.add(new String[]{name, value}); return this; }
        public Builder body(ResponseBody body) { this.body = body; return this; }

        public Response build() {
            if (request == null || code < 0) throw new IllegalStateException("incomplete response");
            return new Response(this);
        }
    }
}
