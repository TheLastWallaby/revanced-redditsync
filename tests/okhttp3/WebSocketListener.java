package okhttp3;

/** Test stand-in for OkHttp's WebSocketListener. */
public abstract class WebSocketListener {
    public void onOpen(WebSocket webSocket, Response response) { }
    public void onMessage(WebSocket webSocket, String text) { }
    public void onClosing(WebSocket webSocket, int code, String reason) { }
    public void onClosed(WebSocket webSocket, int code, String reason) { }
    public void onFailure(WebSocket webSocket, Throwable t, Response response) { }
}
