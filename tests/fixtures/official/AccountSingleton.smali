.class public final Lcom/laurencedawson/reddit_sync/singleton/a;
.super Ljava/lang/Object;
.source "AccountSingleton.java"

.method public static e(Ljava/lang/String;)Ljava/util/HashMap;
    .registers 4
    new-instance v0, Ljava/util/HashMap;
    invoke-direct {v0}, Ljava/util/HashMap;-><init>()V
    const-string v1, "Authorization"
    const-string v2, "bearer "
    return-object v0
.end method

.method public static g()Ljava/lang/String;
    .registers 1
    const-string v0, "android:com.laurencedawson.reddit_sync:vv23.06.30-13:39 (by /u/ljdawson)"
    return-object v0
.end method
