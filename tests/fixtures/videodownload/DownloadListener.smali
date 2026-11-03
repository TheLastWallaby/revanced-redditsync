# Hand-written model of Sync 23033's DownloadRedditVideoJob$b (the playlist listener); not the original bodies.
.class Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob$b;
.super Ljava/lang/Object;
.source "DownloadRedditVideoJob.java"

.field final a:Lj8/a;
.field final b:Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob;

.method public a(Lo8/h;)V
    .registers 5
    invoke-virtual {p1}, Lo8/h;->a()Z
    move-result v0
    const/4 v1, 0x1
    if-nez v0, :download
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob$b;->b:Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob;
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob$b;->a:Lj8/a;
    const-string v2, "No valid video files found"
    invoke-virtual {p1, v0, v2, v1}, Lcom/laurencedawson/reddit_sync/jobs/media/AbstractDownloadMediaJob;->A(Lj8/a;Ljava/lang/String;Z)V
    return-void
    :download
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob$b;->b:Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob;
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob$b;->a:Lj8/a;
    invoke-virtual {v0, v1, p1}, Lcom/laurencedawson/reddit_sync/jobs/media/DownloadRedditVideoJob;->I(Lj8/a;Lo8/h;)V
    return-void
.end method
