# Hand-written model of Sync 23033's EditorView image members; not the original bodies. h(url) offers
# an uploaded image's link in the "Insert image" dialog (InsertImageDialogFragment); i(text) inserts
# text at the cursor.
.class public Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;
.super Landroid/widget/RelativeLayout;
.source "EditorView.java"

.field editor:Lcom/laurencedawson/reddit_sync/ui/views/editor/GifCustomEditText;

.method private c()Landroidx/fragment/app/FragmentManager;
    .registers 2
    const/4 v0, 0x0
    return-object v0
.end method

.method public h(Ljava/lang/String;)V
    .registers 4
    invoke-static {p1}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/InsertImageDialogFragment;->N3(Ljava/lang/String;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/InsertImageDialogFragment;
    move-result-object p1
    invoke-virtual {p1, p0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/InsertImageDialogFragment;->O3(Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;)V
    invoke-direct {p0}, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->c()Landroidx/fragment/app/FragmentManager;
    move-result-object v0
    sget-object v1, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/UploadImageDialogFragment;->H0:Ljava/lang/String;
    invoke-virtual {p1, v0, v1}, Landroidx/fragment/app/c;->L3(Landroidx/fragment/app/FragmentManager;Ljava/lang/String;)V
    return-void
.end method

.method public i(Ljava/lang/String;)V
    .registers 4
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->editor:Lcom/laurencedawson/reddit_sync/ui/views/editor/GifCustomEditText;
    invoke-virtual {v0}, Landroid/widget/EditText;->getText()Landroid/text/Editable;
    move-result-object v0
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->editor:Lcom/laurencedawson/reddit_sync/ui/views/editor/GifCustomEditText;
    invoke-virtual {v1}, Landroid/widget/EditText;->getSelectionStart()I
    move-result v1
    invoke-interface {v0, v1, p1}, Landroid/text/Editable;->insert(ILjava/lang/CharSequence;)Landroid/text/Editable;
    return-void
.end method
