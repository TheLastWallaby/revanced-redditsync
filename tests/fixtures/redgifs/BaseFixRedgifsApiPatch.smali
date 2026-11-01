.class public abstract Lapp/revanced/extension/shared/fixes/redgifs/BaseFixRedgifsApiPatch;
.super Ljava/lang/Object;
.implements Lokhttp3/Interceptor;

# Layout model of the inherited interceptor: only the strings and calls the patch checks
# (RedGifs host, the emulated /v2/oauth/client path, header names, the cached-token lookup and
# the success test before its own retry). Behavior is modeled by the Java double in tests/app/.

.field protected static INSTANCE:Lapp/revanced/extension/shared/fixes/redgifs/BaseFixRedgifsApiPatch;

.method public intercept(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;
    .registers 6
    const-string v0, "api.redgifs.com"
    const-string v1, "Authorization"
    const-string v2, "User-Agent"
    const-string v3, "/v2/oauth/client"
    invoke-interface {p1}, Lokhttp3/Interceptor$Chain;->request()Lokhttp3/Request;
    move-result-object v0
    invoke-interface {p1, v0}, Lokhttp3/Interceptor$Chain;->proceed(Lokhttp3/Request;)Lokhttp3/Response;
    move-result-object v1
    invoke-virtual {v1}, Lokhttp3/Response;->isSuccessful()Z
    move-result v3
    if-nez v3, :done
    invoke-static {v2}, Lapp/revanced/extension/shared/fixes/redgifs/RedgifsTokenManager;->refreshToken(Ljava/lang/String;)Lapp/revanced/extension/shared/fixes/redgifs/RedgifsTokenManager$RedgifsToken;
    :done
    return-object v1
.end method
