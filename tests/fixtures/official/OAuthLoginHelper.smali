.class public Lw8/b;
.super Ljava/lang/Object;
.source "OAuthLoginHelper.java"

# Stock builds "Basic " + Sync's own credentials.
.method public static c()Ljava/lang/String;
    .registers 4
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "Basic "
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

.method public static d(Landroid/content/Context;)Ljava/lang/String;
    .registers 3
    const-string v1, "https://ssl.reddit.com/api/v1/authorize.compact?client_id=StockSyncId&response_type=code&state="
    return-object v1
.end method

.method public static f()Ljava/lang/String;
    .registers 1
    const-string v0, "http://redditsync/auth"
    return-object v0
.end method
