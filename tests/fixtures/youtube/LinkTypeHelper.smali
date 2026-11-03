# Hand-written model of Sync 23033's YouTube classifier (y7.c.K) and host helper (y7.c.a).
.class public abstract Ly7/c;
.super Ljava/lang/Object;
.source "LinkTypeHelper.java"

# The lower-case host without "www.", or null. Non-http(s) links get URLUtil.guessUrl first.
.method public static a(Ljava/lang/String;)Ljava/lang/String;
    .registers 3
    invoke-static {p0}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v0
    const/4 v1, 0x0
    if-eqz v0, :cond_8
    return-object v1
    :cond_8
    invoke-static {p0}, Landroid/webkit/URLUtil;->isHttpUrl(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :cond_18
    invoke-static {p0}, Landroid/webkit/URLUtil;->isHttpsUrl(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :cond_18
    invoke-static {p0}, Landroid/webkit/URLUtil;->guessUrl(Ljava/lang/String;)Ljava/lang/String;
    move-result-object p0
    :cond_18
    :try_start_18
    invoke-static {p0}, Ljava/net/URI;->create(Ljava/lang/String;)Ljava/net/URI;
    move-result-object p0
    invoke-virtual {p0}, Ljava/net/URI;->getHost()Ljava/lang/String;
    move-result-object p0
    if-eqz p0, :cond_36
    const-string v0, "www."
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_2f
    const/4 v0, 0x4
    invoke-virtual {p0, v0}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object p0
    :cond_2f
    sget-object v0, Ljava/util/Locale;->ENGLISH:Ljava/util/Locale;
    invoke-virtual {p0, v0}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;
    move-result-object p0
    :try_end_35
    .catch Ljava/lang/IllegalArgumentException; {:try_start_18 .. :try_end_35} :catch_36
    return-object p0
    :catch_36
    :cond_36
    return-object v1
.end method

# YouTube video link: the host ends with youtube.com or youtu.be, and the link contains none of
# /shorts/, gifyoutube.com, playlist, /channel/, /clip/, img.youtube.com, /c/ or /user/.
.method public static K(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v0
    const/4 v1, 0x0
    if-eqz v0, :cond_8
    return v1
    :cond_8
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v2
    if-eqz v2, :cond_13
    return v1
    :cond_13
    const-string v2, "/shorts/"
    invoke-virtual {p0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v2
    if-eqz v2, :cond_1c
    return v1
    :cond_1c
    const-string v2, "youtube.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v2
    if-nez v2, :cond_2c
    const-string v2, "youtu.be"
    invoke-virtual {v0, v2}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_66
    :cond_2c
    const-string v0, "gifyoutube.com"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_66
    const-string v0, "playlist"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_66
    const-string v0, "/channel/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_66
    const-string v0, "/clip/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_66
    const-string v0, "img.youtube.com"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_66
    const-string v0, "/c/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_66
    const-string v0, "/user/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result p0
    if-nez p0, :cond_66
    const/4 p0, 0x1
    return p0
    :cond_66
    return v1
.end method
