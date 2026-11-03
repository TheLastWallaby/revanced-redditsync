# Hand-written model of Sync 23033's UploadManager (u8.d); not the original body.
# UploadManager.a wraps the Imgur request in Sync's remote on/off check and queues it.
.class public final Lu8/d;
.super Ljava/lang/Object;
.source "UploadManager.java"

.method public static a(Landroid/content/Context;Landroid/net/Uri;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    .registers 7
    sget-object v0, Lcom/laurencedawson/reddit_sync/RedditApplication;->q:Lcom/android/volley/RequestQueue;
    new-instance v1, Lu8/a;
    new-instance v2, Lu8/c;
    invoke-direct {v2, p0, p1, p2, p3}, Lu8/c;-><init>(Landroid/content/Context;Landroid/net/Uri;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    invoke-direct {v1, v2}, Lu8/a;-><init>(Lcom/android/volley/Request;)V
    invoke-virtual {v0, v1}, Lcom/android/volley/RequestQueue;->add(Lcom/android/volley/Request;)Lcom/android/volley/Request;
    return-void
.end method
