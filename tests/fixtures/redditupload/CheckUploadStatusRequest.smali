# Hand-written model of Sync 23033's CheckUploadStatusRequest (u8.a); not the original body.
# It holds the Imgur request until Sync's remote switch allows uploads.
.class public Lu8/a;
.super Lcom/android/volley/Request;
.source "CheckUploadStatusRequest.java"

.field final a:Lcom/android/volley/Request;

.method public constructor <init>(Lcom/android/volley/Request;)V
    .registers 5
    const/4 v0, 0x0
    const-string v1, "https://raw.githubusercontent.com/laurencedawson/reddit-sync-development/master/api/upload.json"
    const/4 v2, 0x0
    invoke-direct {p0, v0, v1, v2}, Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V
    iput-object p1, p0, Lu8/a;->a:Lcom/android/volley/Request;
    invoke-virtual {p0, v0}, Lcom/android/volley/Request;->setShouldCache(Z)Lcom/android/volley/Request;
    return-void
.end method
