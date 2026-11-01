package okhttp3;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class OkHttpClient {
    public Builder newBuilder() { return null; }
    public Call newCall(Request request) { return null; }
    public final List<Interceptor> interceptors() { return null; }

    public static final class Builder {
        public Builder() { }
        public List<Interceptor> interceptors() { return null; }
        public List<Interceptor> networkInterceptors() { return null; }
        public Builder addInterceptor(Interceptor interceptor) { return this; }
        public Builder followRedirects(boolean follow) { return this; }
        public Builder followSslRedirects(boolean follow) { return this; }
        public Builder connectTimeout(long timeout, TimeUnit unit) { return this; }
        public Builder readTimeout(long timeout, TimeUnit unit) { return this; }
        public Builder writeTimeout(long timeout, TimeUnit unit) { return this; }
        public Builder callTimeout(long timeout, TimeUnit unit) { return this; }
        public OkHttpClient build() { return null; }
    }
}
