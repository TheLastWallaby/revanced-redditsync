# Hand-written model of Sync 23033's RestoreCommentRequest (r8.h); not the original bodies.
.class public Lr8/h;
.super Lcom/android/volley/Request;
.source "RestoreCommentRequest.java"

.field a:Lxa/d;

.method public constructor <init>(Lxa/d;)V
    .registers 5
    invoke-static {p1}, Lr8/h;->a(Lxa/d;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    const/4 v2, 0x0
    invoke-direct {p0, v1, v0, v2}, Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V
    iput-object p1, p0, Lr8/h;->a:Lxa/d;
    const-string p1, "Restoring comment"
    invoke-static {p1}, Lgb/p;->d(Ljava/lang/String;)V
    return-void
.end method

.method private static a(Lxa/d;)Ljava/lang/String;
    .registers 3
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "https://api.pushshift.io/reddit/comment/search/"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, "?ids="
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {p0}, Lxa/d;->U()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# Stores author and body for the entry whose id equals the comment's, through Sync's provider.
.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 10
    :try_start
    new-instance v0, Ljava/lang/String;
    iget-object v1, p1, Lcom/android/volley/NetworkResponse;->data:[B
    invoke-direct {v0, v1}, Ljava/lang/String;-><init>([B)V
    new-instance v1, Lorg/json/JSONObject;
    invoke-direct {v1, v0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    const-string v0, "data"
    invoke-virtual {v1, v0}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v0
    const/4 v1, 0x0
    :loop
    invoke-virtual {v0}, Lorg/json/JSONArray;->length()I
    move-result v2
    if-ge v1, v2, :done
    invoke-virtual {v0, v1}, Lorg/json/JSONArray;->getJSONObject(I)Lorg/json/JSONObject;
    move-result-object v2
    const-string v3, "id"
    invoke-virtual {v2, v3}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v3
    iget-object v4, p0, Lr8/h;->a:Lxa/d;
    invoke-virtual {v4}, Lxa/d;->U()Ljava/lang/String;
    move-result-object v4
    invoke-static {v3, v4}, Lorg/apache/commons/lang3/StringUtils;->equals(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v4
    if-eqz v4, :next
    const-string v4, "author"
    invoke-virtual {v2, v4}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v5
    const-string v6, "body"
    invoke-virtual {v2, v6}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v6
    invoke-static {v6}, Lwc/p;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v6
    new-instance v7, Landroid/content/ContentValues;
    invoke-direct {v7}, Landroid/content/ContentValues;-><init>()V
    invoke-virtual {v7, v4, v5}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V
    const-string v4, "body_raw"
    invoke-virtual {v7, v4, v6}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V
    invoke-static {}, Lcom/laurencedawson/reddit_sync/RedditApplication;->f()Landroid/content/Context;
    move-result-object v4
    invoke-virtual {v4}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;
    move-result-object v4
    sget-object v5, Lcom/laurencedawson/reddit_sync/provider/RedditProvider;->q:Landroid/net/Uri;
    const/4 v6, 0x0
    invoke-virtual {v4, v5, v7, v3, v6}, Landroid/content/ContentResolver;->update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I
    :next
    add-int/lit8 v1, v1, 0x1
    goto :loop
    :done
    invoke-static {p1}, Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;
    move-result-object p1
    const/4 v0, 0x0
    invoke-static {v0, p1}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
    move-result-object p1
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :catch
    return-object p1
    :catch
    move-exception p1
    new-instance v0, Lcom/android/volley/ParseError;
    invoke-direct {v0, p1}, Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V
    invoke-static {v0}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object p1
    return-object p1
.end method
