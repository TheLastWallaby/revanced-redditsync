# Hand-written model of Sync 23033's OAuthSubmitRequest (y8.x0); not the original bodies. The
# constructor builds kind link with url, or kind self with text, and ends by setting Sync's retry
# policy; the parser reads json.errors (errors[0][1] as the error), then json.data.id.
.class public Ly8/x0;
.super Lx8/a;
.source "OAuthSubmitRequest.java"

.method public constructor <init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;ZZZLp8/a;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    .registers 25
    move-object v6, p0
    move-object v7, p3
    move-object v8, p4
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "https://oauth.reddit.com/"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, "api/submit?validate_on_submit=True"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v3
    const/4 v2, 0x1
    move-object v1, p1
    move-object/from16 v4, p12
    move-object/from16 v5, p13
    move-object v0, p0
    invoke-direct/range {v0 .. v5}, Lx8/a;-><init>(Landroid/content/Context;ILjava/lang/String;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    new-instance v0, Ljava/util/HashMap;
    invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
    iput-object v0, v6, Lx8/a;->s:Ljava/util/Map;
    const-string v1, "api_type"
    const-string v2, "json"
    invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v1, "title"
    invoke-interface {v0, v1, p2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    if-eqz v8, :no_subreddit
    const-string v1, "sr"
    invoke-interface {v0, v1, v8}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    :no_subreddit
    const-string v1, "kind"
    if-nez p5, :link
    const-string v2, "self"
    invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v1, "text"
    invoke-interface {v0, v1, v7}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    goto :options
    :link
    const-string v2, "link"
    invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v1, "url"
    invoke-interface {v0, v1, v7}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    :options
    invoke-static/range {p8 .. p8}, Ljava/lang/Boolean;->toString(Z)Ljava/lang/String;
    move-result-object v2
    const-string v1, "sendreplies"
    invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    invoke-static/range {p9 .. p9}, Ljava/lang/Boolean;->toString(Z)Ljava/lang/String;
    move-result-object v2
    const-string v1, "spoiler"
    invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    invoke-static/range {p10 .. p10}, Ljava/lang/Boolean;->toString(Z)Ljava/lang/String;
    move-result-object v2
    const-string v1, "nsfw"
    invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    new-instance v0, Lx8/b;
    invoke-direct {v0}, Lx8/b;-><init>()V
    invoke-virtual {p0, v0}, Lcom/android/volley/Request;->setRetryPolicy(Lcom/android/volley/RetryPolicy;)Lcom/android/volley/Request;
    return-void
.end method

.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 5
    const-string v0, "errors"
    :try_start
    new-instance v1, Ljava/lang/String;
    iget-object v2, p1, Lcom/android/volley/NetworkResponse;->data:[B
    invoke-direct {v1, v2}, Ljava/lang/String;-><init>([B)V
    new-instance v2, Lorg/json/JSONObject;
    invoke-direct {v2, v1}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    const-string v1, "json"
    invoke-virtual {v2, v1}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v1
    invoke-virtual {v1, v0}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v2
    invoke-virtual {v2}, Lorg/json/JSONArray;->length()I
    move-result v2
    if-lez v2, :accepted
    new-instance p1, Lcom/android/volley/VolleyError;
    invoke-virtual {v1, v0}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v0
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Lorg/json/JSONArray;->getJSONArray(I)Lorg/json/JSONArray;
    move-result-object v0
    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Lorg/json/JSONArray;->getString(I)Ljava/lang/String;
    move-result-object v0
    invoke-direct {p1, v0}, Lcom/android/volley/VolleyError;-><init>(Ljava/lang/String;)V
    invoke-static {p1}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object p1
    return-object p1
    :accepted
    const-string v0, "data"
    invoke-virtual {v1, v0}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v0
    const-string v1, "id"
    invoke-virtual {v0, v1}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {p1}, Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;
    move-result-object p1
    invoke-static {v0, p1}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
    move-result-object p1
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :failed
    return-object p1
    :failed
    move-exception p1
    new-instance v0, Lcom/android/volley/ParseError;
    invoke-direct {v0, p1}, Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V
    invoke-static {v0}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object p1
    return-object p1
.end method
