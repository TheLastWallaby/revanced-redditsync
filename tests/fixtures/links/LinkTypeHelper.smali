# Offline build-23033 method fixture. The q instruction body is unchanged.
.class public final Ly7/c;
.super Ljava/lang/Object;
.source "LinkTypeHelper.java"

.method public static q(Ljava/lang/String;)Z
    .registers 3
    invoke-static {p0}, Lwc/m;->a(Ljava/lang/CharSequence;)Z
    move-result v0
    const/4 v1, 0x0
    if-eqz v0, :cond_8
    return v1
    :cond_8
    const-string v0, "https://www.reddit.com/r/"
    invoke-static {p0, v0}, Lorg/apache/commons/lang3/StringUtils;->containsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :cond_19
    const-string v0, "?is_gallery=true"
    invoke-static {p0, v0}, Lorg/apache/commons/lang3/StringUtils;->endsWithIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result p0
    if-eqz p0, :cond_19
    const/4 v1, 0x1
    :cond_19
    return v1
.end method
