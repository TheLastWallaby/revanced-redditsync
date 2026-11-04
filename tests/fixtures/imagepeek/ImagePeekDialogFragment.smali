.class public Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;
.super Ljava/lang/Object;
.source "ImagePeekDialogFragment.java"

.field E0:Lxa/d;
.field F0:Ljava/lang/String;
.field mImageView:Lcom/laurencedawson/reddit_sync/ui/views/images/CustomImageView;
.field mRootView:Landroid/widget/RelativeLayout;

# Whether the peek is still showing.
.method public T3()Z
    .registers 2
    const/4 v0, 0x1
    return v0
.end method

# Picture loader: Glide loads the URL into the peek's image view.
.method V3(Ljava/lang/String;)V
    .registers 5
    invoke-virtual {p0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->T3()Z
    move-result v0
    if-nez v0, :load
    return-void
    :load
    invoke-static {}, Lcom/laurencedawson/reddit_sync/RedditApplication;->f()Landroid/content/Context;
    move-result-object v0
    invoke-static {v0}, Lcom/bumptech/glide/b;->u(Landroid/content/Context;)Lcom/bumptech/glide/g;
    move-result-object v0
    invoke-virtual {v0, p1}, Lcom/bumptech/glide/g;->x(Ljava/lang/String;)Lcom/bumptech/glide/f;
    move-result-object v0
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->mImageView:Lcom/laurencedawson/reddit_sync/ui/views/images/CustomImageView;
    invoke-virtual {v0, v1}, Lcom/bumptech/glide/f;->B0(Landroid/widget/ImageView;)Lu3/k;
    return-void
.end method

# GIF loader: the same, through Glide's GIF request.
.method U3(Ljava/lang/String;)V
    .registers 4
    invoke-virtual {p0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->T3()Z
    move-result v0
    if-nez v0, :load
    return-void
    :load
    invoke-static {}, Lcom/laurencedawson/reddit_sync/RedditApplication;->f()Landroid/content/Context;
    move-result-object v0
    invoke-static {v0}, Lcom/bumptech/glide/b;->u(Landroid/content/Context;)Lcom/bumptech/glide/g;
    move-result-object v0
    invoke-virtual {v0}, Lcom/bumptech/glide/g;->n()Lcom/bumptech/glide/f;
    move-result-object v0
    invoke-virtual {v0, p1}, Lcom/bumptech/glide/f;->G0(Ljava/lang/String;)Lcom/bumptech/glide/f;
    move-result-object v0
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/ImagePeekDialogFragment;->mImageView:Lcom/laurencedawson/reddit_sync/ui/views/images/CustomImageView;
    invoke-virtual {v0, v1}, Lcom/bumptech/glide/f;->B0(Landroid/widget/ImageView;)Lu3/k;
    return-void
.end method
