# Offline build-23033 MainActivity fixture. onCreate is original up to the cold-start
# link handoff; the remaining activity setup is replaced by return-void.
.class public Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;
.super Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialActivity;
.source "MainActivity.java"

.field public static f0:Ljava/lang/String;

.method public onCreate(Landroid/os/Bundle;)V
    .registers 6
    invoke-super {p0, p1}, Lcom/laurencedawson/reddit_sync/ui/activities/BaseMaterialActivity;->onCreate(Landroid/os/Bundle;)V
    invoke-static {p0}, Lbutterknife/ButterKnife;->a(Landroid/app/Activity;)Lbutterknife/Unbinder;
    invoke-static {p0, p1}, Lt7/h0;->c(Landroidx/appcompat/app/AppCompatActivity;Landroid/os/Bundle;)V
    invoke-direct {p0}, Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;->S0()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v1
    if-eqz v1, :cond_44
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v1
    sget-object v2, Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;->f0:Ljava/lang/String;
    invoke-virtual {v1, v2}, Landroid/content/Intent;->hasExtra(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :cond_44
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v1
    sget-object v2, Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;->f0:Ljava/lang/String;
    invoke-virtual {v1, v2}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v2
    sget-object v3, Lcom/laurencedawson/reddit_sync/ui/activities/MainActivity;->f0:Ljava/lang/String;
    invoke-virtual {v2, v3}, Landroid/content/Intent;->removeExtra(Ljava/lang/String;)V
    invoke-static {v1}, Ly7/c;->B(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :cond_3d
    invoke-static {v1}, Ly7/a;->o(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    goto :goto_44
    :cond_3d
    invoke-virtual {p0}, Lcom/laurencedawson/reddit_sync/ui/activities/BaseActivity;->e0()Landroid/app/Activity;
    move-result-object v2
    invoke-static {v2, v1}, Ly7/b;->b(Landroid/content/Context;Ljava/lang/String;)Z
    :cond_44
    :goto_44
    return-void
.end method
