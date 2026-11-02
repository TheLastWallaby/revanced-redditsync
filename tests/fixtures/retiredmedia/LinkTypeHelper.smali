# Hand-written model of Sync 23033's link classifiers (y7.c); not the original method bodies.
.class public final Ly7/c;
.super Ljava/lang/Object;
.source "LinkTypeHelper.java"

.method public static a(Ljava/lang/String;)Ljava/lang/String;
    .registers 3
    const/4 v1, 0x0
    if-eqz p0, :none
    invoke-static {p0}, Landroid/webkit/URLUtil;->guessUrl(Ljava/lang/String;)Ljava/lang/String;
    move-result-object p0
    :try_start
    invoke-static {p0}, Ljava/net/URI;->create(Ljava/lang/String;)Ljava/net/URI;
    move-result-object p0
    invoke-virtual {p0}, Ljava/net/URI;->getHost()Ljava/lang/String;
    move-result-object p0
    :try_end
    .catch Ljava/lang/IllegalArgumentException; {:try_start .. :try_end} :none
    if-eqz p0, :none
    const-string v0, "www."
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :lower
    const/4 v0, 0x4
    invoke-virtual {p0, v0}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object p0
    :lower
    sget-object v0, Ljava/util/Locale;->ENGLISH:Ljava/util/Locale;
    invoke-virtual {p0, v0}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;
    move-result-object p0
    return-object p0
    :none
    return-object v1
.end method

.method public static j(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v0
    const/4 v1, 0x0
    if-eqz v0, :start
    return v1
    :start
    sget-object v0, Ljava/util/Locale;->ENGLISH:Ljava/util/Locale;
    invoke-virtual {p0, v0}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;
    move-result-object p0
    invoke-static {p0}, Lf8/a;->f(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_album
    return v1
    :not_album
    const/4 v2, 0x1
    invoke-static {p0}, Lf8/a;->g(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_g
    return v2
    :not_g
    const-string v0, "i.reddituploads.com"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :not_uploads
    return v2
    :not_uploads
    const-string v0, "v.redd.it"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :not_video
    return v2
    :not_video
    const-string v0, "i.redd.it"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :not_image
    return v2
    :not_image
    invoke-static {p0}, Ly7/c;->h(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_h
    return v2
    :not_h
    invoke-static {p0}, Ly7/c;->H(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_H
    return v2
    :not_H
    invoke-static {p0}, Ly7/c;->z(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_z
    return v2
    :not_z
    invoke-static {p0}, Ly7/c;->i(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_i
    return v2
    :not_i
    invoke-static {p0}, Ly7/c;->o(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_o
    return v2
    :not_o
    invoke-static {p0}, Ly7/c;->e(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_e
    return v2
    :not_e
    invoke-static {p0}, Ly7/c;->m(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_m
    return v2
    :not_m
    const-string v0, ".jpg"
    invoke-virtual {p0, v0}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_jpg
    return v2
    :not_jpg
    const-string v0, ".jpeg"
    invoke-virtual {p0, v0}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_jpeg
    return v2
    :not_jpeg
    const-string v0, ".gifv"
    invoke-virtual {p0, v0}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_gifv
    return v2
    :not_gifv
    const-string v0, ".gif"
    invoke-virtual {p0, v0}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_gif
    return v2
    :not_gif
    const-string v0, ".png"
    invoke-virtual {p0, v0}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :not_png
    return v2
    :not_png
    return v1
.end method

.method public static H(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "vid.me"
    invoke-virtual {v0, v2}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :no
    const-string v0, "https://vid.me/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static z(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "sli.mg"
    invoke-virtual {v0, v2}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :no
    const-string v0, "https://sli.mg/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :no
    const-string v0, "/a/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static e(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "deviantart.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :no
    const-string v0, "/gallery/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static C(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "tumblr.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const-string v0, "post"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static h(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "gfycat.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static o(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "redgifs.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static A(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "streamable.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static m(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "mixtape.moe"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static i(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "giphy.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static K(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "youtu"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static u(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "v.redd.it"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method
