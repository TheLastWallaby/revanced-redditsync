.class public final Lokhttp3/OkHttpClient$Builder;
.super Ljava/lang/Object;

# Signature-only skeleton of the member the image upload patch checks; generated, not from the APK.

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public native followRedirects(Z)Lokhttp3/OkHttpClient$Builder;
.end method

.method public native followSslRedirects(Z)Lokhttp3/OkHttpClient$Builder;
.end method

.method public native connectTimeout(JLjava/util/concurrent/TimeUnit;)Lokhttp3/OkHttpClient$Builder;
.end method

.method public native readTimeout(JLjava/util/concurrent/TimeUnit;)Lokhttp3/OkHttpClient$Builder;
.end method

.method public native writeTimeout(JLjava/util/concurrent/TimeUnit;)Lokhttp3/OkHttpClient$Builder;
.end method

.method public native callTimeout(JLjava/util/concurrent/TimeUnit;)Lokhttp3/OkHttpClient$Builder;
.end method

.method public native build()Lokhttp3/OkHttpClient;
.end method
