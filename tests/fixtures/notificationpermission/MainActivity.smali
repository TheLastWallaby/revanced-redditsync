# Hand-written model of Sync 23033's MainActivity, the main screen. It has no onResume of its own:
# it inherits BaseMaterialActivity's.
.class public Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;
.super Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialActivity;
.source "MainActivity.java"

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialActivity;-><init>()V
    return-void
.end method

.method public onCreate(Landroid/os/Bundle;)V
    .registers 2
    invoke-super {p0, p1}, Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialActivity;->onCreate(Landroid/os/Bundle;)V
    return-void
.end method
