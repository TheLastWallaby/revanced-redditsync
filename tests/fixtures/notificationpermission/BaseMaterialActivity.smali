# Hand-written model of Sync 23033's BaseMaterialActivity: its onResume calls BaseActivity's, then
# applies Sync's theme and layout state (reduced here to a log call).
.class public Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialActivity;
.super Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;
.source "BaseMaterialActivity.java"

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;-><init>()V
    return-void
.end method

.method public onResume()V
    .registers 2
    invoke-super {p0}, Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;->onResume()V
    const-string v0, "BaseMaterialActivity resumed"
    invoke-static {v0}, Lwc/i;->e(Ljava/lang/String;)V
    return-void
.end method
