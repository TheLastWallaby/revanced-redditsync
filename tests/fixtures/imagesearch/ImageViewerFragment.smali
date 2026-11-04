# Hand-written model of Sync 23033's ImageViewerFragment: the field holding the image URL (u0),
# the search button's handler and, for comparison, the open-externally button.
.class public Lcom/laurencedawson/reddit_sync/ui/fragments/ImageViewerFragment;
.super Lcom/laurencedawson/reddit_sync/ui/fragments/c;
.source "ImageViewerFragment.java"

.field private u0:Ljava/lang/String;

# image_link_search: opens Google's old search-by-image address with the image URL appended as is.
.method public onSearchCliced()V
    .registers 4
    .annotation runtime Lbutterknife/OnClick;
    .end annotation
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v0
    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "https://images.google.com/searchbyimage?image_url="
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    iget-object v2, p0, Lcom/laurencedawson/reddit_sync/ui/fragments/ImageViewerFragment;->u0:Ljava/lang/String;
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    invoke-static {v0, v1}, Ly7/a;->X(Landroid/content/Context;Ljava/lang/String;)V
    return-void
.end method

# Another viewer button, which the patch must leave alone: opens the image URL itself.
.method public onOpenExternally()V
    .registers 3
    .annotation runtime Lbutterknife/OnClick;
    .end annotation
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v0
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragments/ImageViewerFragment;->u0:Ljava/lang/String;
    invoke-static {v0, v1}, Ly7/a;->X(Landroid/content/Context;Ljava/lang/String;)V
    return-void
.end method
