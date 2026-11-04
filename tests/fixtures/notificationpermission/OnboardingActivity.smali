# Hand-written model of Sync 23033's OnboardingActivity, reduced to its notification step's request
# for POST_NOTIFICATIONS (request code 10).
.class public Lcom/laurencedawson/reddit_sync/ui/activities/OnboardingActivity;
.super Landroidx/appcompat/app/AppCompatActivity;
.source "OnboardingActivity.java"

.method public i0()V
    .registers 4
    const/4 v0, 0x1
    new-array v0, v0, [Ljava/lang/String;
    const/4 v1, 0x0
    const-string v2, "android.permission.POST_NOTIFICATIONS"
    aput-object v2, v0, v1
    const/16 v1, 0xa
    invoke-virtual {p0, v0, v1}, Landroid/app/Activity;->requestPermissions([Ljava/lang/String;I)V
    return-void
.end method
