.class public abstract Lokhttp3/WebSocketListener;
.super Ljava/lang/Object;

# Signature-only skeleton of the member the image upload patch checks; generated, not from the APK.

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public native onMessage(Lokhttp3/WebSocket;Ljava/lang/String;)V
.end method

.method public native onFailure(Lokhttp3/WebSocket;Ljava/lang/Throwable;Lokhttp3/Response;)V
.end method

.method public native onClosed(Lokhttp3/WebSocket;ILjava/lang/String;)V
.end method
