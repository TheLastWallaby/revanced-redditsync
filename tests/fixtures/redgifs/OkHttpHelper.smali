.class public final La8/a;
.super Ljava/lang/Object;

# Hand-written layout model of Sync 23033's OkHttpHelper.k() (not copied from the APK): it
# configures the builder for the client Volley's BasicNetwork uses, optionally adds DNS over
# HTTPS (whose skip branch targets the install call), installs the inherited RedGifs
# interceptor and stores the built client in a8.a.e.

.field private static e:Lokhttp3/OkHttpClient;

.method private static k()V
    .registers 5

    new-instance v0, Lokhttp3/OkHttpClient$Builder;
    invoke-direct {v0}, Lokhttp3/OkHttpClient$Builder;-><init>()V

    sget-object v2, Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;
    const-wide/16 v3, 0xa
    invoke-virtual {v0, v3, v4, v2}, Lokhttp3/OkHttpClient$Builder;->readTimeout(JLjava/util/concurrent/TimeUnit;)Lokhttp3/OkHttpClient$Builder;
    move-result-object v0

    const/4 v1, 0x1
    invoke-virtual {v0, v1}, Lokhttp3/OkHttpClient$Builder;->followRedirects(Z)Lokhttp3/OkHttpClient$Builder;
    move-result-object v0

    invoke-static {}, La8/a;->dohEnabled()Z
    move-result v1
    if-eqz v1, :install
    invoke-static {v0}, La8/a;->f(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/Dns;
    move-result-object v1
    if-eqz v1, :install
    invoke-virtual {v0, v1}, Lokhttp3/OkHttpClient$Builder;->dns(Lokhttp3/Dns;)Lokhttp3/OkHttpClient$Builder;
    move-result-object v0

    :install
    invoke-static {v0}, Lapp/revanced/extension/syncforreddit/FixRedgifsApiPatch;->install(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient;
    move-result-object v0
    sput-object v0, La8/a;->e:Lokhttp3/OkHttpClient;
    return-void
.end method
