package okhttp3;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test stand-in for OkHttp's MultipartBody, written as OkHttp writes multipart/form-data: each part
 * as {@code --boundary}, a Content-Disposition with the quoted name (and filename), the part's
 * Content-Type when its body has one, its Content-Length, a blank line and the content; then
 * {@code --boundary--}.
 */
public final class MultipartBody extends RequestBody {
    public static final MediaType FORM = MediaType.get("multipart/form-data");

    private final String boundary;
    private final MediaType type;
    private final List<Part> parts;

    private MultipartBody(String boundary, MediaType type, List<Part> parts) {
        this.boundary = boundary;
        this.type = type;
        this.parts = parts;
    }

    @Override String contentType() { return type + "; boundary=" + boundary; }

    @Override byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : parts) {
            write(out, "--" + boundary + "\r\n");
            String disposition = "Content-Disposition: form-data; name=\"" + part.name + "\"";
            if (part.filename != null) disposition += "; filename=\"" + part.filename + "\"";
            write(out, disposition + "\r\n");
            String partType = part.body.contentType();
            if (partType != null) write(out, "Content-Type: " + partType + "\r\n");
            byte[] content = part.body.bytes();
            write(out, "Content-Length: " + content.length + "\r\n\r\n");
            out.write(content, 0, content.length);
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    private static final class Part {
        final String name;
        final String filename;
        final RequestBody body;

        Part(String name, String filename, RequestBody body) {
            this.name = name;
            this.filename = filename;
            this.body = body;
        }
    }

    public static final class Builder {
        private final String boundary = UUID.randomUUID().toString();
        private final List<Part> parts = new ArrayList<>();
        private MediaType type = MediaType.get("multipart/mixed");

        public Builder() { }

        public Builder setType(MediaType type) { this.type = type; return this; }

        public Builder addFormDataPart(String name, String value) {
            parts.add(new Part(name, null, RequestBody.create(value, null)));
            return this;
        }

        public Builder addFormDataPart(String name, String filename, RequestBody body) {
            parts.add(new Part(name, filename, body));
            return this;
        }

        public MultipartBody build() {
            if (parts.isEmpty()) throw new IllegalStateException("Multipart body must have at least one part.");
            return new MultipartBody(boundary, type, new ArrayList<>(parts));
        }
    }
}
