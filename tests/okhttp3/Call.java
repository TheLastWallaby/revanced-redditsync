package okhttp3;

import java.io.IOException;

/** Test stand-in for OkHttp's Call. */
public interface Call {
    Request request();
    Response execute() throws IOException;
    void cancel();
}
