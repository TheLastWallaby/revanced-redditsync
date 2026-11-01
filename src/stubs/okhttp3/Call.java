package okhttp3;

import java.io.IOException;

public interface Call {
    Request request();
    Response execute() throws IOException;
    void cancel();
}
