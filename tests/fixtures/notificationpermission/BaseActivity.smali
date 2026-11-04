# Hand-written model of Sync 23033's BaseActivity: onResume logs and calls FragmentActivity's.
.class public Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;
.super Landroidx/appcompat/app/AppCompatActivity;
.source "BaseActivity.java"

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Landroidx/appcompat/app/AppCompatActivity;-><init>()V
    return-void
.end method

.method public onResume()V
    .registers 2
    const-string v0, "onResume: "
    invoke-static {v0}, Lwc/i;->e(Ljava/lang/String;)V
    invoke-super {p0}, Landroidx/fragment/app/FragmentActivity;->onResume()V
    return-void
.end method
