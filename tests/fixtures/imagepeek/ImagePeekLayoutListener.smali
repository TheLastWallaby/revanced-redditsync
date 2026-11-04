.class Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment$f;
.super Ljava/lang/Object;
.source "ImagePeekDialogFragment.java"

.implements Landroid/view/ViewTreeObserver$OnGlobalLayoutListener;

.field final synthetic a:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;

# Chooses what the peek shows once its layout is ready.
.method public onGlobalLayout()V
    .registers 9
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment$f;->a:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;
    iget-object v0, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->mRootView:Landroid/widget/RelativeLayout;
    invoke-virtual {v0}, Landroid/widget/RelativeLayout;->getViewTreeObserver()Landroid/view/ViewTreeObserver;
    move-result-object v0
    invoke-virtual {v0, p0}, Landroid/view/ViewTreeObserver;->removeGlobalOnLayoutListener(Landroid/view/ViewTreeObserver$OnGlobalLayoutListener;)V

    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment$f;->a:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;
    iget-object v0, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->E0:Lxa/d;
    invoke-virtual {v0}, Lxa/d;->Y0()I
    move-result v0
    const/4 v1, 0x2
    if-ne v0, v1, :other

    # Text post: its preview, else its thumbnail.
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment$f;->a:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;
    iget-object v1, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->E0:Lxa/d;
    invoke-virtual {v1}, Lxa/d;->H0()Ljava/lang/String;
    move-result-object v1
    iput-object v1, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->F0:Ljava/lang/String;
    invoke-static {v1}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
    move-result v1
    if-eqz v1, :show
    iget-object v1, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->E0:Lxa/d;
    invoke-virtual {v1}, Lxa/d;->Z0()Ljava/lang/String;
    move-result-object v1
    iput-object v1, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->F0:Ljava/lang/String;
    :show
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment$f;->a:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;
    iget-object v1, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->F0:Ljava/lang/String;
    invoke-virtual {v0, v1}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->V3(Ljava/lang/String;)V
    return-void

    # Other posts: their link, GIFs through the GIF loader.
    :other
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment$f;->a:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;
    iget-object v1, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->E0:Lxa/d;
    invoke-virtual {v1}, Lxa/d;->e1()Ljava/lang/String;
    move-result-object v1
    iput-object v1, v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->F0:Ljava/lang/String;
    const-string v2, ".gif"
    invoke-virtual {v1, v2}, Ljava/lang/String;->endsWith(Ljava/lang/String;)Z
    move-result v2
    if-eqz v2, :picture
    invoke-virtual {v0, v1}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->U3(Ljava/lang/String;)V
    return-void
    :picture
    invoke-virtual {v0, v1}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->V3(Ljava/lang/String;)V
    return-void
.end method
