.class Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;
.super Ljava/lang/Object;
.source "EditFragment.java"

# Hand-written model of the editor's send-message error listener in Sync 23033: dismisses the
# "Sending message" progress dialog, then toasts Reddit's text (or logs "Unknown error"). The editor
# and its text stay open.

.implements Lcom/android/volley/Response$ErrorListener;

.field final synthetic a:Landroid/app/ProgressDialog;
.field final synthetic b:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;

.method public onErrorResponse(Lcom/android/volley/VolleyError;)V
    .registers 4
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;->b:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;
    invoke-virtual {v0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v0
    if-nez v0, :cond_shown
    return-void
    :cond_shown
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;->a:Landroid/app/ProgressDialog;
    invoke-virtual {v0}, Landroid/app/ProgressDialog;->dismiss()V
    invoke-virtual {p1}, Ljava/lang/Exception;->getMessage()Ljava/lang/String;
    move-result-object p1
    invoke-static {p1}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_text
    const-string v0, "care to try these again?"
    invoke-virtual {p1, v0}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_text
    const-string p1, "Bad captcha"
    :cond_text
    invoke-static {p1}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
    move-result v0
    if-nez v0, :cond_unknown
    iget-object v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment$d;->b:Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/EditFragment;
    invoke-virtual {v0}, Landroidx/fragment/app/Fragment;->B0()Landroidx/fragment/app/FragmentActivity;
    move-result-object v0
    const/4 v1, 0x1
    invoke-static {v0, p1, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
    move-result-object p1
    invoke-virtual {p1}, Landroid/widget/Toast;->show()V
    goto :goto_end
    :cond_unknown
    const-string p1, "Unknown error"
    invoke-static {p1}, Lgb/p;->d(Ljava/lang/String;)V
    :goto_end
    return-void
.end method
