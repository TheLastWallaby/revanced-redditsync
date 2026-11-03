# Hand-written model of Sync 23033's RedditRequest (x8.a), the base of its OAuth requests; not the
# original body. OAuthRequestManager passes the token to a(String); getHeaders adds it via
# AccountSingleton.e; deliverResponse hands the result to the listener.
.class public abstract Lx8/a;
.super Lcom/android/volley/Request;
.source "RedditRequest.java"

.field protected final c:Landroid/content/Context;
.field protected final q:Lcom/android/volley/Response$Listener;
.field protected r:Ljava/lang/String;
.field protected s:Ljava/util/Map;

.method public constructor <init>(Landroid/content/Context;ILjava/lang/String;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    .registers 6
    invoke-direct {p0, p2, p3, p5}, Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V
    iput-object p1, p0, Lx8/a;->c:Landroid/content/Context;
    iput-object p4, p0, Lx8/a;->q:Lcom/android/volley/Response$Listener;
    const/4 p1, 0x0
    invoke-virtual {p0, p1}, Lcom/android/volley/Request;->setShouldCache(Z)Lcom/android/volley/Request;
    return-void
.end method

.method public a(Ljava/lang/String;)V
    .registers 2
    iput-object p1, p0, Lx8/a;->r:Ljava/lang/String;
    return-void
.end method

.method public deliverResponse(Ljava/lang/Object;)V
    .registers 3
    iget-object v0, p0, Lx8/a;->q:Lcom/android/volley/Response$Listener;
    if-eqz v0, :done
    invoke-interface {v0, p1}, Lcom/android/volley/Response$Listener;->onResponse(Ljava/lang/Object;)V
    :done
    return-void
.end method

.method public getHeaders()Ljava/util/Map;
    .registers 2
    iget-object v0, p0, Lx8/a;->r:Ljava/lang/String;
    invoke-static {v0}, Lcom/laurencedawson/reddit_sync/singleton/a;->e(Ljava/lang/String;)Ljava/util/HashMap;
    move-result-object v0
    return-object v0
.end method

.method protected getParams()Ljava/util/Map;
    .registers 2
    iget-object v0, p0, Lx8/a;->s:Ljava/util/Map;
    return-object v0
.end method
