package okhttp3;

/** Test stand-in for OkHttp's WebSocket. */
public interface WebSocket {
    boolean close(int code, String reason);
    void cancel();
}
