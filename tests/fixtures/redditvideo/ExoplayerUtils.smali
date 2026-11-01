.class public Lxc/a;
.super Ljava/lang/Object;

# Hand-written layout model (not copied from the APK) of Sync 23033's ExoplayerUtils.a(String),
# which builds the feed autoplay media source: DASH when y7.c.s, else HLS when y7.c.t, else
# progressive. Factory construction is omitted; v1 stands in for each factory.

.method public static a(Ljava/lang/String;)Lcom/google/android/exoplayer2/source/MediaSource;
    .registers 5
    invoke-static {p0}, Ly7/c;->s(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :hls
    const/4 v1, 0x0
    invoke-static {p0}, Lcom/google/android/exoplayer2/MediaItem;->fromUri(Ljava/lang/String;)Lcom/google/android/exoplayer2/MediaItem;
    move-result-object v2
    invoke-virtual {v1, v2}, Lcom/google/android/exoplayer2/source/dash/DashMediaSource$Factory;->createMediaSource(Lcom/google/android/exoplayer2/MediaItem;)Lcom/google/android/exoplayer2/source/dash/DashMediaSource;
    move-result-object v0
    return-object v0
    :hls
    invoke-static {p0}, Ly7/c;->t(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :progressive
    const/4 v1, 0x0
    invoke-static {p0}, Lcom/google/android/exoplayer2/MediaItem;->fromUri(Ljava/lang/String;)Lcom/google/android/exoplayer2/MediaItem;
    move-result-object v2
    invoke-virtual {v1, v2}, Lcom/google/android/exoplayer2/source/hls/HlsMediaSource$Factory;->createMediaSource(Lcom/google/android/exoplayer2/MediaItem;)Lcom/google/android/exoplayer2/source/hls/HlsMediaSource;
    move-result-object v0
    return-object v0
    :progressive
    const/4 v1, 0x0
    invoke-static {p0}, Lcom/google/android/exoplayer2/MediaItem;->fromUri(Ljava/lang/String;)Lcom/google/android/exoplayer2/MediaItem;
    move-result-object v2
    invoke-virtual {v1, v2}, Lcom/google/android/exoplayer2/source/ProgressiveMediaSource$Factory;->createMediaSource(Lcom/google/android/exoplayer2/MediaItem;)Lcom/google/android/exoplayer2/source/ProgressiveMediaSource;
    move-result-object v0
    return-object v0
.end method
