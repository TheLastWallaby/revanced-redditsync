# Hand-written model of Sync 23033's OAuthCommentRequest (y8.e); not the original bodies. c picks
# api/comment or api/editusertext; the constructor fills thing_id, text and api_type; d reports an
# error (Sync posts a notification with Retry). The parser looks for the literal "errors": [] and
# then hands json.data.things[0].data to Sync's comment parser (c9.c.b); otherwise it reports
# errors[0][1]. parseNetworkError reports "Could not connect to Reddit" through d.
.class public Ly8/e;
.super Lx8/a;
.source "OAuthCommentRequest.java"

.field private final t:Ljava/lang/String;
.field private final u:I
.field private final v:Lxa/d;
.field private final w:Lxa/d;
.field private final x:Ljava/lang/String;
.field private final y:Z
.field private final z:Ljava/lang/String;

.method public constructor <init>(Landroid/content/Context;Ljava/lang/String;Lxa/d;Lxa/d;Ljava/lang/String;IZLjava/lang/String;)V
    .registers 15
    invoke-static {p1, p6}, Ly8/e;->c(Landroid/content/Context;I)Ljava/lang/String;
    move-result-object v3
    const/4 v2, 0x1
    const/4 v4, 0x0
    const/4 v5, 0x0
    move-object v0, p0
    move-object v1, p1
    invoke-direct/range {v0 .. v5}, Lx8/a;-><init>(Landroid/content/Context;ILjava/lang/String;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    new-instance v0, Ljava/util/HashMap;
    invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
    iput-object v0, p0, Lx8/a;->s:Ljava/util/Map;
    const-string v1, "thing_id"
    invoke-interface {v0, v1, p2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v1, "text"
    invoke-interface {v0, v1, p5}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v1, "api_type"
    const-string v2, "json"
    invoke-interface {v0, v1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    return-void
.end method

.method private static c(Landroid/content/Context;I)Ljava/lang/String;
    .registers 3
    const/4 v0, 0x3
    if-ne p1, v0, :comment
    const-string v0, "api/editusertext"
    goto :url
    :comment
    const-string v0, "api/comment"
    :url
    const-string v1, "https://oauth.reddit.com/"
    invoke-virtual {v1, v0}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

.method public d(Ljava/lang/String;Z)V
    .registers 3
    invoke-static {p1}, Lgb/p;->d(Ljava/lang/String;)V
    return-void
.end method

.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 23
    move-object/from16 v1, p0
    move-object/from16 v0, p1
    const-string v2, "data"
    new-instance v3, Ljava/lang/String;
    iget-object v4, v0, Lcom/android/volley/NetworkResponse;->data:[B
    invoke-direct {v3, v4}, Ljava/lang/String;-><init>([B)V
    const-string v4, "\"errors\": []"
    invoke-static {v3, v4}, Lorg/apache/commons/lang3/StringUtils;->contains(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v4
    const-string v5, "json"
    const/4 v6, 0x1
    const/4 v7, 0x0
    if-nez v4, :accepted
    :try_errors
    new-instance v8, Lorg/json/JSONObject;
    invoke-direct {v8, v3}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    invoke-virtual {v8, v5}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v8
    const-string v9, "errors"
    invoke-virtual {v8, v9}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v8
    invoke-virtual {v8, v7}, Lorg/json/JSONArray;->getJSONArray(I)Lorg/json/JSONArray;
    move-result-object v8
    invoke-virtual {v8, v6}, Lorg/json/JSONArray;->get(I)Ljava/lang/Object;
    move-result-object v8
    invoke-virtual {v8}, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v8
    :try_errors_end
    .catch Lorg/json/JSONException; {:try_errors .. :try_errors_end} :no_error
    goto :report
    :no_error
    const-string v8, "No error returned"
    :report
    invoke-virtual {v1, v8, v6}, Ly8/e;->d(Ljava/lang/String;Z)V
    new-instance v0, Lcom/android/volley/ParseError;
    new-instance v2, Ljava/lang/Exception;
    invoke-direct {v2}, Ljava/lang/Exception;-><init>()V
    invoke-direct {v0, v2}, Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V
    invoke-static {v0}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object v0
    return-object v0
    :accepted
    new-instance v8, Lorg/json/JSONObject;
    invoke-direct {v8, v3}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    invoke-virtual {v8, v5}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v8
    invoke-virtual {v8, v2}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v8
    const-string v9, "things"
    invoke-virtual {v8, v9}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;
    move-result-object v8
    invoke-virtual {v8, v7}, Lorg/json/JSONArray;->getJSONObject(I)Lorg/json/JSONObject;
    move-result-object v8
    invoke-virtual {v8, v2}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v8
    iget-object v14, v1, Lx8/a;->c:Landroid/content/Context;
    const/16 v15, 0x1
    move-object/from16 v16, v8
    new-instance v17, Ljava/util/ArrayList;
    invoke-direct/range {v17 .. v17}, Ljava/util/ArrayList;-><init>()V
    const/16 v18, 0x1
    const/16 v19, 0x0
    invoke-static/range {v14 .. v19}, Lc9/c;->b(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;ZLjava/lang/String;)V
    const/4 v0, 0x0
    invoke-static {v0, v0}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
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
