package okhttp3;

public final class MultipartBody extends RequestBody {
    public static final MediaType FORM = null;
    private MultipartBody() { }

    public static final class Builder {
        public Builder() { }
        public Builder setType(MediaType type) { return this; }
        public Builder addFormDataPart(String name, String value) { return this; }
        public Builder addFormDataPart(String name, String filename, RequestBody body) { return this; }
        public MultipartBody build() { return null; }
    }
}
