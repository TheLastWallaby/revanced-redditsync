.class public Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;
.super Landroidx/fragment/app/c;
.source "EditFragment.java"

# Model of Sync 23033's editor dialog: the new-message send (f4) and the new-message fields (b4).

.field mEditorView:Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;
.field mUsernameInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
.field mSubjectInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
.field private K0:Lxa/d;
.field private L0:Ljava/lang/String;

.method private S3()Ljava/lang/String;
    .registers 2
    const-string v0, "editor"
    return-object v0
.end method

# Shows the recipient and Subject fields for a new message, fills a known recipient and focuses the
# message; for a reply (K0 set) previews the parent message instead.
.method private b4()V
    .registers 3
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->K0:Lxa/d;
    if-nez v0, :reply

    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mUsernameInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setVisibility(I)V
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mSubjectInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setVisibility(I)V

    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->L0:Ljava/lang/String;
    invoke-static {v0}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :ask_recipient
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mUsernameInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->L0:Ljava/lang/String;
    invoke-virtual {v0, v1}, Landroid/widget/EditText;->setText(Ljava/lang/CharSequence;)V
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mSubjectInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    invoke-virtual {v0}, Landroid/widget/EditText;->requestFocus()Z
    goto :message

    :ask_recipient
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mUsernameInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    invoke-virtual {v0}, Landroid/widget/EditText;->requestFocus()Z

    :message
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mEditorView:Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;
    invoke-virtual {v0}, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->s()V
    goto :done

    :reply
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mEditorView:Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;
    invoke-virtual {v0}, Lxa/d;->n()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v1, v0}, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->t(Ljava/lang/String;)V

    :done
    return-void
.end method

# Sends the message: a reply (K0 set) with OAuthCommentRequest, otherwise a new message with
# OAuthMessageRequest (y8.z) after checking the recipient and subject.
.method private f4(Ljava/lang/String;Ljava/lang/String;)V
    .registers 15
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mEditorView:Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;
    invoke-virtual {v0}, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->e()Ljava/lang/String;
    move-result-object v0
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mEditorView:Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;
    invoke-virtual {v1}, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->f()Z
    move-result v1
    if-nez v1, :valid
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mEditorView:Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;
    invoke-virtual {p1}, Lcom/laurencedawson/reddit_sync/ui/views/editor/EditorView;->j()V
    return-void

    :valid
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->K0:Lxa/d;
    if-eqz v1, :new_message
    # Reply: the recipient register is reused for the request's arguments on this path.
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object p1
    const/4 v3, 0x0
    invoke-static {p1, v1, v3, v0}, Lm8/a;->reply(Landroid/content/Context;Lxa/d;Ljava/lang/String;Ljava/lang/String;)V
    goto :done

    :new_message
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mUsernameInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    invoke-virtual {v1}, Landroid/widget/EditText;->getText()Landroid/text/Editable;
    move-result-object v1
    invoke-virtual {v1}, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v3
    invoke-static {v3}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
    move-result v1
    if-eqz v1, :has_recipient
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mUsernameInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    const-string p2, "Enter a username"
    invoke-virtual {p1, p2}, Landroid/widget/EditText;->setError(Ljava/lang/CharSequence;)V
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mUsernameInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    invoke-virtual {p1}, Landroid/widget/EditText;->requestFocus()Z
    return-void

    :has_recipient
    iget-object v1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mSubjectInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    invoke-virtual {v1}, Landroid/widget/EditText;->getText()Landroid/text/Editable;
    move-result-object v1
    invoke-virtual {v1}, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v4
    invoke-static {v4}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
    move-result v1
    if-eqz v1, :has_subject
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mSubjectInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    const-string p2, "Enter a subject"
    invoke-virtual {p1, p2}, Landroid/widget/EditText;->setError(Ljava/lang/CharSequence;)V
    iget-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->mSubjectInput:Lcom/laurencedawson/reddit_sync/ui/views/responsive/CustomEditText;
    invoke-virtual {p1}, Landroid/widget/EditText;->requestFocus()Z
    return-void

    :has_subject
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v1
    invoke-static {v1}, Lgb/l;->a(Landroid/content/Context;)Landroid/app/ProgressDialog;
    move-result-object v1
    const-string v2, "Sending message"
    invoke-virtual {v1, v2}, Landroid/app/ProgressDialog;->setMessage(Ljava/lang/CharSequence;)V
    invoke-virtual {v1}, Landroid/app/ProgressDialog;->show()V
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v10
    new-instance v11, Ly8/z;
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v2
    new-instance v8, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$c;
    invoke-direct {v8, p0, v1}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$c;-><init>(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;Landroid/app/ProgressDialog;)V
    new-instance v9, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;
    invoke-direct {v9, p0, v1}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;-><init>(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;Landroid/app/ProgressDialog;)V
    move-object v1, v11
    move-object v5, v0
    move-object v6, p1
    move-object v7, p2
    invoke-direct/range {v1 .. v9}, Ly8/z;-><init>(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/android/volley/Response$Listener;Lcom/android/volley/Response$ErrorListener;)V
    invoke-direct {p0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;->S3()Ljava/lang/String;
    move-result-object p1
    invoke-static {v10, v11, p1}, Lm8/a;->g(Landroid/content/Context;Lx8/a;Ljava/lang/String;)V

    :done
    return-void
.end method
