# Hand-written model of Sync 23033's post type helper (c8.e.h); not the original method bodies.
.class public final Lc8/e;
.super Ljava/lang/Object;
.source "SyncTypeHelper.java"

.method public static h(Lorg/json/JSONObject;I)I
    .registers 8
    const/4 v0, 0x1
    const/4 v1, 0x3
    if-eq p1, v1, :link
    const/16 v0, 0xb
    return v0
    :link
    const-string v2, "url"
    invoke-virtual {p0, v2}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    const-string v3, "imgur.com/gallery/xIolo"
    invoke-virtual {v2, v3}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v3
    if-eqz v3, :not_xiolo
    return v0
    :not_xiolo
    invoke-static {v2}, Lf8/a;->f(Ljava/lang/String;)Z
    move-result v3
    if-eqz v3, :not_album
    const/4 v3, 0x6
    return v3
    :not_album
    const-string v3, ".gif"
    invoke-virtual {v2, v3}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v3
    if-eqz v3, :not_gif
    const/4 v3, 0x4
    return v3
    :not_gif
    const-string v3, ".gifv"
    invoke-virtual {v2, v3}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v3
    if-eqz v3, :not_gifv
    const/4 v3, 0x4
    return v3
    :not_gifv
    invoke-static {v2}, Ly7/c;->h(Ljava/lang/String;)Z
    move-result v3
    if-eqz v3, :not_gfycat
    const/4 v3, 0x4
    return v3
    :not_gfycat
    invoke-static {v2}, Ly7/c;->j(Ljava/lang/String;)Z
    move-result v3
    if-eqz v3, :link_post
    return v0
    :link_post
    return v1
.end method
