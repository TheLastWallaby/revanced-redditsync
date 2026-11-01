.class public Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;
.super Ljava/lang/Object;

# Hand-written layout model (not copied from the APK) of Sync 23033's video player:
# setSource stores the source and starts the player; initPlayer picks DASH (y7.c.s), HLS
# (y7.c.t) or progressive playback for mSource. Player and factory setup are omitted; the chosen
# source is kept in mediaSource so a test can observe the branch.

.field private mSource:Ljava/lang/String;
.field private mHd:Z
.field private mediaSource:Ljava/lang/Object;

.method public setSource(Ljava/lang/String;ZZ)V
    .registers 4
    iput-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;->mSource:Ljava/lang/String;
    iput-boolean p2, p0, Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;->mHd:Z
    const/4 p1, 0x0
    invoke-virtual {p0, p1}, Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;->initPlayer(Z)V
    return-void
.end method

.method protected initPlayer(Z)V
    .registers 5
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;->mSource:Ljava/lang/String;
    invoke-static {v0}, Ly7/c;->t(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :dash
    const/4 v1, 0x0
    invoke-static {v0}, Lcom/google/android/exoplayer2/MediaItem;->fromUri(Ljava/lang/String;)Lcom/google/android/exoplayer2/MediaItem;
    move-result-object v2
    invoke-virtual {v1, v2}, Lcom/google/android/exoplayer2/source/hls/HlsMediaSource$Factory;->createMediaSource(Lcom/google/android/exoplayer2/MediaItem;)Lcom/google/android/exoplayer2/source/hls/HlsMediaSource;
    move-result-object v1
    goto :done
    :dash
    invoke-static {v0}, Ly7/c;->s(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :progressive
    const/4 v1, 0x0
    invoke-static {v0}, Lcom/google/android/exoplayer2/MediaItem;->fromUri(Ljava/lang/String;)Lcom/google/android/exoplayer2/MediaItem;
    move-result-object v2
    invoke-virtual {v1, v2}, Lcom/google/android/exoplayer2/source/dash/DashMediaSource$Factory;->createMediaSource(Lcom/google/android/exoplayer2/MediaItem;)Lcom/google/android/exoplayer2/source/dash/DashMediaSource;
    move-result-object v1
    goto :done
    :progressive
    const/4 v1, 0x0
    invoke-static {v0}, Lcom/google/android/exoplayer2/MediaItem;->fromUri(Ljava/lang/String;)Lcom/google/android/exoplayer2/MediaItem;
    move-result-object v2
    invoke-virtual {v1, v2}, Lcom/google/android/exoplayer2/source/ProgressiveMediaSource$Factory;->createMediaSource(Lcom/google/android/exoplayer2/MediaItem;)Lcom/google/android/exoplayer2/source/ProgressiveMediaSource;
    move-result-object v1
    :done
    iput-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/views/video/CustomExoPlayerView;->mediaSource:Ljava/lang/Object;
    return-void
.end method
