# Hand-written model of Sync 23033's "Action visibility" menu (ActionsBottomSheetFragment.showMore);
# not the original body.
.class public Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/ActionsBottomSheetFragment;
.super Ljava/lang/Object;
.source "ActionsBottomSheetFragment.java"

.method public showMore(Landroid/view/View;)V
    .registers 8
    const/4 v0, 0x5
    invoke-static {p1, v0}, Lgb/j;->c(Landroid/view/View;I)Lm/e;
    move-result-object v0
    invoke-virtual {v0}, Lm/e;->a()Landroid/view/Menu;
    move-result-object v1
    const-string v2, "Action visibility"
    invoke-interface {v1, v2}, Landroid/view/Menu;->addSubMenu(Ljava/lang/CharSequence;)Landroid/view/SubMenu;
    move-result-object v1
    const/4 v4, 0x1
    const-string v2, "About"
    invoke-interface {v1, v2}, Landroid/view/SubMenu;->add(Ljava/lang/CharSequence;)Landroid/view/MenuItem;
    move-result-object v3
    invoke-interface {v3, v4}, Landroid/view/MenuItem;->setCheckable(Z)Landroid/view/MenuItem;
    const-string v2, "Random"
    invoke-interface {v1, v2}, Landroid/view/SubMenu;->add(Ljava/lang/CharSequence;)Landroid/view/MenuItem;
    move-result-object v3
    invoke-interface {v3, v4}, Landroid/view/MenuItem;->setCheckable(Z)Landroid/view/MenuItem;
    invoke-interface {v3, v4}, Landroid/view/MenuItem;->setChecked(Z)Landroid/view/MenuItem;
    const-string v2, "Random nsfw"
    invoke-interface {v1, v2}, Landroid/view/SubMenu;->add(Ljava/lang/CharSequence;)Landroid/view/MenuItem;
    move-result-object v3
    invoke-interface {v3, v4}, Landroid/view/MenuItem;->setVisible(Z)Landroid/view/MenuItem;
    const-string v2, "Settings"
    invoke-interface {v1, v2}, Landroid/view/SubMenu;->add(Ljava/lang/CharSequence;)Landroid/view/MenuItem;
    invoke-virtual {v0}, Lm/e;->a()Landroid/view/Menu;
    move-result-object v1
    const-string v2, "Contextual chips"
    invoke-interface {v1, v2}, Landroid/view/Menu;->add(Ljava/lang/CharSequence;)Landroid/view/MenuItem;
    new-instance v1, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/ActionsBottomSheetFragment$c;
    invoke-direct {v1, p0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/ActionsBottomSheetFragment$c;-><init>(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/ActionsBottomSheetFragment;)V
    invoke-virtual {v0, v1}, Lm/e;->d(Lm/e$d;)V
    invoke-virtual {v0}, Lm/e;->e()V
    return-void
.end method
