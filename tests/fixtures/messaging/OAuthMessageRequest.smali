.class public Ly8/z;
.super Lx8/a;
.source "OAuthMessageRequest.java"

# Hand-written model of Sync 23033's OAuthMessageRequest (y8.z): api/compose with to, subject and
# text; the parser turns json.errors into a VolleyError with Reddit's text (errors[0][1]).

.method public constructor <init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .registers 8
    const-string v0, "api/compose"
    iput-object p1, p0, Lx8/a;->c:Landroid/content/Context;
    new-instance v0, Ljava/util/HashMap;
    invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
    iput-object v0, p0, Lx8/a;->s:Ljava/util/Map;
    const-string v1, "to"
    invoke-interface {v0, v1, p2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v1, "subject"
    invoke-interface {v0, v1, p3}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v1, "text"
    invoke-interface {v0, v1, p4}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    return-void
.end method

.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 6
    const-string v0, "errors"
    :try_start
    new-instance v1, Lorg/json/JSONObject;
    new-instance v2, Ljava/lang/String;
    iget-object v3, p1, Lcom/android/volley/NetworkResponse;->data:[B
    invoke-direct {v2, v3}, Ljava/lang/String;-><init>([B)V
    invoke-direct {v1, v2}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    const-string v2, "json"
    invoke-virtual {v1, v2}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v1
    invoke-virtual {v1, v0}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v2
    invoke-virtual {v2}, Lorg/json/JSONArray;->length()I
    move-result v2
    if-lez v2, :success
    new-instance v2, Lcom/android/volley/VolleyError;
    invoke-virtual {v1, v0}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v0
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Lorg/json/JSONArray;->getJSONArray(I)Lorg/json/JSONArray;
    move-result-object v0
    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Lorg/json/JSONArray;->getString(I)Ljava/lang/String;
    move-result-object v0
    invoke-direct {v2, v0}, Lcom/android/volley/VolleyError;-><init>(Ljava/lang/String;)V
    invoke-static {v2}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object p1
    return-object p1
    :success
    sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
    invoke-static {p1}, Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;
    move-result-object v1
    invoke-static {v0, v1}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
    move-result-object p1
    :try_end
    .catch Lorg/json/JSONException; {:try_start .. :try_end} :catch_json
    return-object p1
    :catch_json
    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    invoke-static {p1}, Lcom/android/volley/toolbox/HttpHeaderParser;->parseCacheHeaders(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Cache$Entry;
    move-result-object p1
    invoke-static {v0, p1}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
    move-result-object p1
    return-object p1
.end method
