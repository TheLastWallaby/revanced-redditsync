# Hand-written model of Sync 23033's CommentMoreDialogBottomSheet (t9.s); not the original bodies.
.class public Lt9/s;
.super Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;
.source "CommentMoreDialogBottomSheet.java"

.field private Q0:Lxa/d;
.field d1:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
.field e1:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;

# Builds the sheet: "Restore comment" for a [removed]/[deleted] comment, with Sync Ultra and the
# ultra_restore remote-config switch; then "Read aloud".
.method public A4()V
    .registers 7
    invoke-static {}, Luc/b;->j()Z
    move-result v0
    if-eqz v0, :cond_read
    iget-object v0, p0, Lt9/s;->Q0:Lxa/d;
    invoke-virtual {v0}, Lxa/d;->e()Ljava/lang/String;
    move-result-object v0
    const/4 v3, 0x2
    new-array v3, v3, [Ljava/lang/CharSequence;
    const/4 v2, 0x0
    const-string v4, "[removed]"
    aput-object v4, v3, v2
    const/4 v1, 0x1
    const-string v2, "[deleted]"
    aput-object v2, v3, v1
    invoke-static {v0, v3}, Lorg/apache/commons/lang3/StringUtils;->equalsAnyIgnoreCase(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :cond_read
    invoke-static {}, Lt7/d0;->e()Z
    move-result v0
    if-eqz v0, :cond_read
    new-instance v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    const v1, 0x7f080642
    const-string v2, "Restore comment"
    invoke-direct {v0, v1, v2}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;-><init>(ILjava/lang/String;)V
    invoke-virtual {p0, v0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    move-result-object v0
    iput-object v0, p0, Lt9/s;->e1:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    :cond_read
    new-instance v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    const v1, 0x7f080643
    const-string v2, "Read aloud"
    invoke-direct {v0, v1, v2}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;-><init>(ILjava/lang/String;)V
    invoke-virtual {p0, v0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    move-result-object v0
    iput-object v0, p0, Lt9/s;->d1:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    return-void
.end method

# Handles the tapped item, then dismisses the sheet.
.method public r0(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)V
    .registers 5
    iget-object v0, p0, Lt9/s;->d1:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    invoke-virtual {p1, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_restore
    const-string v0, "Reading aloud"
    invoke-static {v0}, Lgb/p;->d(Ljava/lang/String;)V
    goto :goto_done
    :cond_restore
    iget-object v0, p0, Lt9/s;->e1:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    invoke-virtual {p1, v0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :goto_done
    new-instance p1, Lr8/h;
    iget-object v0, p0, Lt9/s;->Q0:Lxa/d;
    invoke-direct {p1, v0}, Lr8/h;-><init>(Lxa/d;)V
    invoke-static {p1}, Lm8/a;->a(Lcom/android/volley/Request;)V
    goto :goto_done
    :goto_done
    invoke-virtual {p0}, Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V
    return-void
.end method
