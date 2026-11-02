# Hand-written model of Sync 23033's Imgur album checks (f8.a); not the original method bodies.
.class public Lf8/a;
.super Ljava/lang/Object;
.source "ImgurImageHelper.java"

.method public static g(Ljava/lang/String;)Z
    .registers 4
    invoke-static {p0}, Ly7/c;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    const/4 v1, 0x0
    if-eqz v0, :no
    const-string v2, "imgur.com"
    invoke-virtual {v0, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static f(Ljava/lang/String;)Z
    .registers 3
    const/4 v1, 0x0
    invoke-static {p0}, Lf8/a;->g(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :no
    const-string v0, "/a/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method
