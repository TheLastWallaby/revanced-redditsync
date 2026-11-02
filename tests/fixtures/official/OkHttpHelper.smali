.class public La8/a;
.super Ljava/lang/Object;
.source "OkHttpHelper.java"

.field private static a:Lokhttp3/OkHttpClient;

# OkHttpHelper.k: builds the client Sync's Volley requests use.
.method private static k()V
    .registers 2
    new-instance v0, Lokhttp3/OkHttpClient$Builder;
    invoke-direct {v0}, Lokhttp3/OkHttpClient$Builder;-><init>()V
    const/4 v1, 0x1
    invoke-virtual {v0}, Lokhttp3/OkHttpClient$Builder;->build()Lokhttp3/OkHttpClient;
    move-result-object v0
    sput-object v0, La8/a;->a:Lokhttp3/OkHttpClient;
    return-void
.end method
