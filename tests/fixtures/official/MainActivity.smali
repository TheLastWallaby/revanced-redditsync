.class public Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;
.super Landroid/app/Activity;
.source "MainActivity.java"

# The end of MainActivity.onCreate: show the Sync for Lemmy sheet on a fresh start.
.method public onCreate(Landroid/os/Bundle;)V
    .registers 8
    if-nez p1, :cond_end
    const-class p1, Laa/c;
    invoke-static {p0}, Lt7/j;->g(Landroid/content/Context;)Landroidx/fragment/app/FragmentManager;
    move-result-object v0
    invoke-static {p1, v0}, Ls9/g;->f(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;)V
    :cond_end
    return-void
.end method
