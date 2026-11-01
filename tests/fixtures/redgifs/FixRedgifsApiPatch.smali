.class public Lapp/revanced/extension/syncforreddit/FixRedgifsApiPatch;
.super Lapp/revanced/extension/shared/fixes/redgifs/BaseFixRedgifsApiPatch;

# Layout model of the inherited installer: add the shared interceptor, then build.

.method public static install(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient;
    .registers 2
    sget-object v0, Lapp/revanced/extension/shared/fixes/redgifs/BaseFixRedgifsApiPatch;->INSTANCE:Lapp/revanced/extension/shared/fixes/redgifs/BaseFixRedgifsApiPatch;
    invoke-virtual {p0, v0}, Lokhttp3/OkHttpClient$Builder;->addInterceptor(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient$Builder;
    move-result-object p0
    invoke-virtual {p0}, Lokhttp3/OkHttpClient$Builder;->build()Lokhttp3/OkHttpClient;
    move-result-object p0
    return-object p0
.end method
