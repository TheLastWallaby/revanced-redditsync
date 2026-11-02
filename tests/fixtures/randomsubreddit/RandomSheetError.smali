# Hand-written model of the sheet's error listener (t9.m1$b); not the original body.
.class final Lt9/m1$b;
.super Ljava/lang/Object;
.source "RandomProgressDialogBottomSheet.java"

.field final a:Lt9/m1;

.method public onErrorResponse(Lcom/android/volley/VolleyError;)V
    .registers 3
    iget-object p1, p0, Lt9/m1$b;->a:Lt9/m1;
    invoke-virtual {p1}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object p1
    if-eqz p1, :done
    iget-object p1, p0, Lt9/m1$b;->a:Lt9/m1;
    invoke-virtual {p1}, Landroidx/fragment/app/c;->z3()Landroid/app/Dialog;
    move-result-object p1
    if-eqz p1, :done
    iget-object p1, p0, Lt9/m1$b;->a:Lt9/m1;
    invoke-virtual {p1}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object p1
    const-string v0, "Error grabbing random subreddit"
    invoke-static {v0, p1}, Lgb/p;->e(Ljava/lang/String;Landroid/content/Context;)V
    iget-object p1, p0, Lt9/m1$b;->a:Lt9/m1;
    invoke-virtual {p1}, Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V
    :done
    return-void
.end method
