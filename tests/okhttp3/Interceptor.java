package okhttp3;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Test stand-in for OkHttp's Interceptor; see OkHttpClient for what the stand-ins model. */
public interface Interceptor {
    Response intercept(Chain chain) throws IOException;

    interface Chain {
        Request request();
        Response proceed(Request request) throws IOException;
        Chain withConnectTimeout(int timeout, TimeUnit unit);
        Chain withReadTimeout(int timeout, TimeUnit unit);
        Chain withWriteTimeout(int timeout, TimeUnit unit);
    }
}
