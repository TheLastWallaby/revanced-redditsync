# Hand-written model of Sync 23033's OAuthRequestManager (m8.a); not the original bodies.
# b(context, token, request): a known token is set and the request queued; without one the
# account's token is fetched first (b9.d). g(context, request, refreshToken): another account's
# refresh token is exchanged first (b9.c), otherwise b. c(context, request) is b without a token.
.class public final Lm8/a;
.super Ljava/lang/Object;
.source "OAuthRequestManager.java"

.method public static b(Landroid/content/Context;Ljava/lang/String;Lx8/a;)V
    .registers 5
    const-string v0, "CONTEXT"
    invoke-static {v0}, Lwc/i;->e(Ljava/lang/String;)V
    if-nez p0, :context
    return-void
    :context
    if-nez p2, :request
    return-void
    :request
    invoke-static {p1}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :fetch
    invoke-virtual {p2, p1}, Lx8/a;->a(Ljava/lang/String;)V
    sget-object v1, Lcom/laurencedawson/reddit_sync/RedditApplication;->c:Lcom/android/volley/RequestQueue;
    invoke-virtual {v1, p2}, Lcom/android/volley/RequestQueue;->add(Lcom/android/volley/Request;)Lcom/android/volley/Request;
    return-void
    :fetch
    sget-object v1, Lcom/laurencedawson/reddit_sync/RedditApplication;->b:Lcom/android/volley/RequestQueue;
    new-instance v0, Lb9/d;
    invoke-direct {v0, p0, p2}, Lb9/d;-><init>(Landroid/content/Context;Lx8/a;)V
    invoke-virtual {v1, v0}, Lcom/android/volley/RequestQueue;->add(Lcom/android/volley/Request;)Lcom/android/volley/Request;
    return-void
.end method

.method public static c(Landroid/content/Context;Lx8/a;)V
    .registers 3
    const/4 v0, 0x0
    invoke-static {p0, v0, p1}, Lm8/a;->b(Landroid/content/Context;Ljava/lang/String;Lx8/a;)V
    return-void
.end method

.method public static g(Landroid/content/Context;Lx8/a;Ljava/lang/String;)V
    .registers 5
    invoke-static {p2}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :exchange
    const/4 p2, 0x0
    invoke-static {p0, p2, p1}, Lm8/a;->b(Landroid/content/Context;Ljava/lang/String;Lx8/a;)V
    return-void
    :exchange
    sget-object v0, Lcom/laurencedawson/reddit_sync/RedditApplication;->q:Lcom/android/volley/RequestQueue;
    new-instance v1, Lb9/c;
    invoke-direct {v1, p0, p1, p2}, Lb9/c;-><init>(Landroid/content/Context;Lx8/a;Ljava/lang/String;)V
    invoke-virtual {v0, v1}, Lcom/android/volley/RequestQueue;->add(Lcom/android/volley/Request;)Lcom/android/volley/Request;
    return-void
.end method
