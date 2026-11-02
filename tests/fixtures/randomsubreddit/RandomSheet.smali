# Hand-written model of Sync 23033's RandomProgressDialogBottomSheet (t9.m1); not the original bodies.
.class public Lt9/m1;
.super Landroidx/fragment/app/Fragment;
.source "RandomProgressDialogBottomSheet.java"

.field P0:Z

.method v4()V
    .registers 7
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v0
    new-instance v1, Lr8/d;
    iget-boolean v3, p0, Lt9/m1;->P0:Z
    const/4 v4, 0x0
    const/4 v5, 0x0
    invoke-direct {v1, v0, v3, v4, v5}, Lr8/d;-><init>(Landroid/content/Context;ZLcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    invoke-static {v0, v1}, Lm8/a;->c(Landroid/content/Context;Lx8/a;)V
    return-void
.end method
