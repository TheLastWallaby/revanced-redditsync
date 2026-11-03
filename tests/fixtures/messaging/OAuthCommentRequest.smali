.class public Ly8/e;
.super Lx8/a;
.source "OAuthCommentRequest.java"

# Hand-written model of Sync 23033's OAuthCommentRequest (y8.e), which sends comment replies
# (type 0/1), private message replies (type 2, thing_id t4_) and edits (type 3) to api/comment.
# The parser reports Reddit's json.errors with d(text, true); an error status reaches
# parseNetworkError, which reports "Could not connect to Reddit". d builds Sync's failure
# notification, with a Retry action that keeps the text when retry is true.

.field private final u:I
.field private final x:Ljava/lang/String;

.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 23
    move-object/from16 v1, p0
    move-object/from16 v0, p1
    const-string v2, "data"
    new-instance v3, Ljava/lang/String;
    iget-object v4, v0, Lcom/android/volley/NetworkResponse;->data:[B
    invoke-direct {v3, v4}, Ljava/lang/String;-><init>([B)V
    const-string v4, "\"errors\": []"
    invoke-virtual {v3, v4}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v4
    const-string v5, "json"
    const/4 v6, 0x1
    const/4 v7, 0x0
    if-nez v4, :cond_success

    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    :try_start
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0, v3}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    invoke-virtual {v0, v5}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v0
    const-string v4, "errors"
    invoke-virtual {v0, v4}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v0
    move v4, v7
    :loop
    invoke-virtual {v0}, Lorg/json/JSONArray;->length()I
    move-result v5
    if-ge v4, v5, :cond_done
    invoke-virtual {v0, v4}, Lorg/json/JSONArray;->getJSONArray(I)Lorg/json/JSONArray;
    move-result-object v5
    invoke-virtual {v5, v6}, Lorg/json/JSONArray;->get(I)Ljava/lang/Object;
    move-result-object v5
    invoke-virtual {v2, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
    add-int/lit8 v4, v4, 0x1
    goto :loop
    :cond_done
    nop
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :catch_any
    goto :report
    :catch_any
    move-exception v0
    :report
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->length()I
    move-result v0
    if-nez v0, :cond_text
    const-string v0, "No error returned"
    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    :cond_text
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v1, v0, v6}, Ly8/e;->d(Ljava/lang/String;Z)V
    new-instance v0, Lcom/android/volley/ParseError;
    new-instance v2, Ljava/lang/Exception;
    invoke-direct {v2}, Ljava/lang/Exception;-><init>()V
    invoke-direct {v0, v2}, Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V
    invoke-static {v0}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object v0
    return-object v0

    :cond_success
    iget-object v3, v1, Ly8/e;->x:Ljava/lang/String;
    invoke-static {v3}, Lf9/a;->d(Ljava/lang/String;)V
    const/4 v2, 0x0
    invoke-static/range {p1 .. p1}, Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;
    move-result-object v0
    invoke-static {v2, v0}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
    move-result-object v0
    return-object v0
.end method

.method public parseNetworkError(Lcom/android/volley/VolleyError;)Lcom/android/volley/VolleyError;
    .registers 4
    invoke-super {p0, p1}, Lx8/a;->parseNetworkError(Lcom/android/volley/VolleyError;)Lcom/android/volley/VolleyError;
    const-string v0, "Could not connect to Reddit"
    const/4 v1, 0x1
    invoke-virtual {p0, v0, v1}, Ly8/e;->d(Ljava/lang/String;Z)V
    invoke-super {p0, p1}, Lx8/a;->parseNetworkError(Lcom/android/volley/VolleyError;)Lcom/android/volley/VolleyError;
    move-result-object p1
    return-object p1
.end method

.method public d(Ljava/lang/String;Z)V
    .registers 9
    iget v0, p0, Ly8/e;->u:I
    const/4 v1, 0x1
    const-string v2, "Error submitting"
    const/4 v3, 0x2
    if-ne v0, v3, :cond_comment
    const-string v2, "Error submitting message"
    goto :build
    :cond_comment
    const-string v3, " message"
    const-string v2, "Error submitting comment"
    :build
    new-instance v3, Landroidx/core/app/g$e;
    iget-object v4, p0, Lx8/a;->c:Landroid/content/Context;
    invoke-direct {v3, v4}, Landroidx/core/app/g$e;-><init>(Landroid/content/Context;)V
    invoke-virtual {v3, v2}, Landroidx/core/app/g$e;->m(Ljava/lang/CharSequence;)Landroidx/core/app/g$e;
    invoke-virtual {v3, p1}, Landroidx/core/app/g$e;->l(Ljava/lang/CharSequence;)Landroidx/core/app/g$e;
    invoke-static {}, Lt7/t;->a()Ljava/lang/String;
    move-result-object p1
    invoke-virtual {v3, p1}, Landroidx/core/app/g$e;->i(Ljava/lang/String;)Landroidx/core/app/g$e;
    if-eqz p2, :cond_build
    new-instance p1, Landroid/content/Intent;
    iget-object p2, p0, Lx8/a;->c:Landroid/content/Context;
    const-class v2, Lcom/laurencedawson/reddit_sync/receiver/SubmitCommentBroadcastReceiver;
    invoke-direct {p1, p2, v2}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    iget-object p2, p0, Ly8/e;->x:Ljava/lang/String;
    const-string v2, "inputText"
    invoke-virtual {p1, v2, p2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
    iget-object p2, p0, Lx8/a;->c:Landroid/content/Context;
    const/high16 v2, 0x14000000
    invoke-static {p2, v1, p1, v2}, Landroid/app/PendingIntent;->getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    move-result-object p1
    const p2, 0x7f080106
    const-string v2, "Retry"
    invoke-virtual {v3, p2, v2, p1}, Landroidx/core/app/g$e;->a(ILjava/lang/CharSequence;Landroid/app/PendingIntent;)Landroidx/core/app/g$e;
    :cond_build
    invoke-virtual {v3}, Landroidx/core/app/g$e;->c()Landroid/app/Notification;
    move-result-object p1
    iget-object p2, p0, Lx8/a;->c:Landroid/content/Context;
    const-string v2, "notification"
    invoke-virtual {p2, v2}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
    move-result-object p2
    check-cast p2, Landroid/app/NotificationManager;
    invoke-virtual {p2, v1, p1}, Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V
    return-void
.end method
