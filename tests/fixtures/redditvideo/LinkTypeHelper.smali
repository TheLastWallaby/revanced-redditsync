.class public Ly7/c;
.super Ljava/lang/Object;

# Hand-written layout model (not copied from the APK) of Sync 23033's player classifiers:
# s() is true for a v.redd.it source containing .mpd (DASH), t() for one containing .m3u8 (HLS).

.method public static s(Ljava/lang/String;)Z
    .registers 3
    const/4 v1, 0x0
    if-eqz p0, :no
    const-string v0, "v.redd.it"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const-string v0, ".mpd"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method

.method public static t(Ljava/lang/String;)Z
    .registers 3
    const/4 v1, 0x0
    if-eqz p0, :no
    const-string v0, "v.redd.it"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const-string v0, ".m3u8"
    invoke-virtual {p0, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :no
    const/4 v1, 0x1
    :no
    return v1
.end method
