# Hand-written model of Sync 23033's HomeActivity, the launcher: it opens the onboarding until it
# is finished, then MainActivity.
.class public final Lcom/laurencedawson/reddit_sync/ui/activities/HomeActivity;
.super Landroidx/appcompat/app/AppCompatActivity;
.source "HomeActivity.java"

.method private k0(Z)V
    .registers 4
    if-eqz p1, :main
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/laurencedawson/reddit_sync/ui/activities/OnboardingActivity;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    invoke-virtual {p0, v0}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    return-void
    :main
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    invoke-virtual {p0, v0}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    return-void
.end method
