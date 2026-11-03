.class public abstract Lx8/a;
.super Lcom/android/volley/Request;
.source "RedditRequest.java"

# Hand-written model of the parts of Sync 23033's RedditRequest (x8.a) the messaging requests use:
# the context and parameter fields and the base error handler (token refresh in Sync).

.field protected final c:Landroid/content/Context;
.field protected s:Ljava/util/Map;

.method public constructor <init>(Landroid/content/Context;ILjava/lang/String;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    .registers 6
    iput-object p1, p0, Lx8/a;->c:Landroid/content/Context;
    return-void
.end method

.method public parseNetworkError(Lcom/android/volley/VolleyError;)Lcom/android/volley/VolleyError;
    .registers 2
    return-object p1
.end method
