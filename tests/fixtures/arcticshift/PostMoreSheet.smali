# Hand-written model of Sync 23033's PostMoreBottomSheetFragment; not the original bodies.
.class public Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;
.super Ls9/f;
.source "PostMoreBottomSheetFragment.java"

.field private P0:Lxa/d;
.field mProfile:Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;
.field mShare:Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;

# Binds the sheet's rows once its layout is inflated: the author's profile row ("/u/<author>", hidden for
# [deleted]), then Share's more-button listener, last, as in Sync.
.method public o2(Landroid/view/View;Landroid/os/Bundle;)V
    .registers 10
    invoke-super {p0, p1, p2}, Ls9/f;->o2(Landroid/view/View;Landroid/os/Bundle;)V
    const/4 v0, 0x0
    const/16 v1, 0x8
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;->mProfile:Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;
    new-instance p2, Ljava/lang/StringBuilder;
    invoke-direct {p2}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "/u/"
    invoke-virtual {p2, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    iget-object v2, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;->P0:Lxa/d;
    invoke-virtual {v2}, Lxa/d;->e()Ljava/lang/String;
    move-result-object v2
    invoke-virtual {p2, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {p2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object p2
    invoke-virtual {p1, p2}, Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;->k(Ljava/lang/CharSequence;)V
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;->P0:Lxa/d;
    invoke-virtual {p1}, Lxa/d;->e()Ljava/lang/String;
    move-result-object p1
    const-string p2, "[deleted]"
    invoke-static {p1, p2}, Lorg/apache/commons/lang3/StringUtils;->equalsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result p1
    if-eqz p1, :cond_share
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;->mProfile:Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;
    invoke-virtual {p1, v1}, Landroid/widget/LinearLayout;->setVisibility(I)V
    :cond_share
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;->mShare:Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;
    invoke-virtual {p1}, Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;->n()V
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;->mShare:Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;
    new-instance p2, Ls9/i;
    invoke-direct {p2, p0}, Ls9/i;-><init>(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/PostMoreBottomSheetFragment;)V
    invoke-virtual {p1, p2}, Lcom/laurencedawson/reddit_sync/ui/views/core/MaterialRow;->f(Landroid/view/View$OnClickListener;)V
    return-void
.end method
