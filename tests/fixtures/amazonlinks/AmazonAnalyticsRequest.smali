# Hand-written model of Sync 23033's AmazonAnalyticsRequest (e7.e): a Volley POST of one form field,
# "url", to Sync's former ad server, with no listeners and no caching.
.class public Le7/e;
.super Lcom/android/volley/Request;
.source "AmazonAnalyticsRequest.java"

.field private a:Ljava/lang/String;

.method public constructor <init>(Ljava/lang/String;)V
    .registers 5
    const/4 v0, 0x1
    const-string v1, "https://us-central1-ad-server-f1010.cloudfunctions.net/add"
    const/4 v2, 0x0
    invoke-direct {p0, v0, v1, v2}, Lcom/android/volley/Request;-><init>(ILjava/lang/String;Lcom/android/volley/Response$ErrorListener;)V
    iput-object p1, p0, Le7/e;->a:Ljava/lang/String;
    const/4 v0, 0x0
    invoke-virtual {p0, v0}, Lcom/android/volley/Request;->setShouldCache(Z)Lcom/android/volley/Request;
    return-void
.end method

.method protected getParams()Ljava/util/Map;
    .registers 4
    new-instance v0, Ljava/util/HashMap;
    invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
    iget-object v1, p0, Le7/e;->a:Ljava/lang/String;
    const-string v2, "url"
    invoke-virtual {v0, v2, v1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    return-object v0
.end method
