# Hand-written model of Sync 23033's AccountSingleton members the image upload patch checks; not the
# original bodies. d() is the instance, i() whether an account is signed in, e(token) the headers.
.class public final Lcom/laurencedawson/reddit_sync/singleton/a;
.super Ljava/lang/Object;
.source "AccountSingleton.java"

.field private static a:Lcom/laurencedawson/reddit_sync/singleton/a;
.field private b:Z

.method public static d()Lcom/laurencedawson/reddit_sync/singleton/a;
    .registers 1
    sget-object v0, Lcom/laurencedawson/reddit_sync/singleton/a;->a:Lcom/laurencedawson/reddit_sync/singleton/a;
    return-object v0
.end method

.method public i()Z
    .registers 2
    iget-boolean v0, p0, Lcom/laurencedawson/reddit_sync/singleton/a;->b:Z
    return v0
.end method

.method public static e(Ljava/lang/String;)Ljava/util/HashMap;
    .registers 4
    new-instance v0, Ljava/util/HashMap;
    invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
    const-string v1, "User-Agent"
    const-string v2, "android:com.laurencedawson.reddit_sync:test"
    invoke-virtual {v0, v1, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    if-eqz p0, :done
    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "bearer "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    const-string v2, "Authorization"
    invoke-virtual {v0, v2, v1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    :done
    return-object v0
.end method
