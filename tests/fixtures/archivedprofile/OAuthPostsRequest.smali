# Hand-written model of Sync 23033's OAuthPostsRequest (y8.k0); not the original bodies.
# b builds a profile listing URL the way Sync's profile branch does; parseNetworkResponse reads
# data.children by kind into PostParserHelper (c9.c.b) and stores data.after in the paging state.
.class public Ly8/k0;
.super Lx8/a;
.source "OAuthPostsRequest.java"

.field t:Ljava/lang/String;
.field u:Lo8/g;

.method public static b(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Lo8/g;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .registers 10
    new-instance v0, Ljava/lang/StringBuffer;
    invoke-direct {v0}, Ljava/lang/StringBuffer;-><init>()V
    const-string v1, "https://oauth.reddit.com/"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-static {p1}, Ld7/d;->J(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :subreddit
    invoke-static {p1}, Ld7/d;->u(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-static {p1}, Ld7/d;->v(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    const-string v3, "user/"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-virtual {v0, v2}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    const-string v3, "/"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-virtual {v0, v1}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    goto :json
    :subreddit
    const-string v3, "r/"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-virtual {v0, p1}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    const-string v3, "/"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-virtual {v0, p4}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    :json
    const-string v3, ".json"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    const-string v3, "?sort="
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-virtual {v0, p4}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    if-eqz p3, :done
    iget-object v1, p3, Lo8/g;->a:Ljava/lang/String;
    if-eqz v1, :done
    const-string v3, "&"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    const-string v3, "after="
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-virtual {v0, v1}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    const-string v3, "&limit=25"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    :done
    const-string v3, "&always_show_media=1"
    invoke-virtual {v0, v3}, Ljava/lang/StringBuffer;->append(Ljava/lang/String;)Ljava/lang/StringBuffer;
    invoke-virtual {v0}, Ljava/lang/StringBuffer;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 16
    move-object v1, p0
    move-object v2, p1
    const-string v3, "data"
    :try_start
    new-instance v0, Ljava/lang/String;
    iget-object v4, v2, Lcom/android/volley/NetworkResponse;->data:[B
    invoke-direct {v0, v4}, Ljava/lang/String;-><init>([B)V
    new-instance v4, Lorg/json/JSONObject;
    invoke-direct {v4, v0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    invoke-virtual {v4, v3}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v4
    const-string v5, "children"
    invoke-virtual {v4, v5}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v5
    new-instance v11, Ljava/util/ArrayList;
    invoke-direct {v11}, Ljava/util/ArrayList;-><init>()V
    const/4 v6, 0x0
    :loop
    invoke-virtual {v5}, Lorg/json/JSONArray;->length()I
    move-result v0
    if-ge v6, v0, :parsed
    invoke-virtual {v5, v6}, Lorg/json/JSONArray;->getJSONObject(I)Lorg/json/JSONObject;
    move-result-object v0
    const-string v7, "kind"
    invoke-virtual {v0, v7}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v7
    invoke-static {v7}, Lo8/c;->b(Ljava/lang/String;)I
    move-result v9
    invoke-virtual {v0, v3}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v10
    iget-object v8, v1, Lx8/a;->c:Landroid/content/Context;
    const/4 v12, 0x0
    const/4 v13, 0x0
    invoke-static/range {v8 .. v13}, Lc9/c;->b(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;ZLjava/lang/String;)V
    add-int/lit8 v6, v6, 0x1
    goto :loop
    :parsed
    iget-object v0, v1, Ly8/k0;->u:Lo8/g;
    const-string v7, "after"
    invoke-virtual {v4, v7}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v7
    iput-object v7, v0, Lo8/g;->a:Ljava/lang/String;
    invoke-virtual {v11}, Ljava/util/ArrayList;->size()I
    move-result v7
    iput v7, v0, Lo8/g;->b:I
    invoke-static {v2}, Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;
    move-result-object v7
    invoke-static {v0, v7}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
    move-result-object v0
    :try_end
    .catch Lorg/json/JSONException; {:try_start .. :try_end} :failed
    return-object v0
    :failed
    move-exception v0
    new-instance v1, Lcom/android/volley/ParseError;
    invoke-direct {v1, v0}, Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V
    invoke-static {v1}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object v0
    return-object v0
.end method
