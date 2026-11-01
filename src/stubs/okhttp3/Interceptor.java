package okhttp3;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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
