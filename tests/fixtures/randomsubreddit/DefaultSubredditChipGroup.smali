# Hand-written model of Sync 23033's front-page chip group; onPreviewsClicked selects "random".
# Not the original body.
.class public Lcom/laurencedawson/reddit_sync/sections/posts/toolbar/chip_groups/DefaultSubredditChipGroup;
.super Ljava/lang/Object;
.source "DefaultSubredditChipGroup.java"

.method public onPreviewsClicked()V
    .registers 5
    invoke-static {}, Lj9/a;->a()Lbd/b;
    move-result-object v0
    new-instance v1, Lh7/d0;
    const-string v2, "random"
    const/4 v3, 0x0
    invoke-direct {v1, v2, v3}, Lh7/d0;-><init>(Ljava/lang/String;Z)V
    invoke-virtual {v0, v1}, Lbd/b;->i(Ljava/lang/Object;)V
    return-void
.end method
