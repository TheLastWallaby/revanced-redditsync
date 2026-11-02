# Hand-written model of Sync 23033's subreddit selection handler
# (VerticalPostsFragment.onSubredditSelected); not the original body.
.class public Lcom/laurencedawson/reddit_sync/ui/fragments/posts/VerticalPostsFragment;
.super Landroidx/fragment/app/Fragment;
.source "VerticalPostsFragment.java"

.method public onSubredditSelected(Lh7/d0;)V
    .registers 7
    iget-object v0, p1, Lh7/d0;->a:Ljava/lang/String;
    const-string v1, "random"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result v0
    const-string v1, "random_nsfw"
    if-nez v0, :random
    iget-object v0, p1, Lh7/d0;->a:Ljava/lang/String;
    invoke-virtual {v0, v1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :random
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->q1()Landroid/view/View;
    move-result-object v1
    const/4 v2, 0x0
    const/4 v3, 0x0
    invoke-virtual {v1, v2, v3}, Landroid/view/View;->post(Ljava/lang/Runnable;)Z
    return-void
    :random
    const-class v0, Lt9/m1;
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->Q0()Landroidx/fragment/app/FragmentManager;
    move-result-object v2
    iget-object p1, p1, Lh7/d0;->a:Ljava/lang/String;
    invoke-virtual {v1, p1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result p1
    invoke-static {p1}, Lt9/m1;->u4(Z)Landroid/os/Bundle;
    move-result-object p1
    invoke-static {v0, v2, p1}, Ls9/g;->e(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;Landroid/os/Bundle;)V
    return-void
.end method
