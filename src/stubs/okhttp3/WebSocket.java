package okhttp3;

public interface WebSocket {
    boolean close(int code, String reason);
    void cancel();
}
