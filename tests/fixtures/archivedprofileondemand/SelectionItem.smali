# Hand-written model of Sync 23033's selection sheet item (AbstractSelectionDialogBottomSheet$h):
# an icon (a), a title (b), a value (c) and Sync's highlight for the chosen item (q, read by a()).
.class public Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
.super Ljava/lang/Object;
.source "AbstractSelectionDialogBottomSheet.java"

.field public a:I
.field public b:Ljava/lang/String;
.field public c:Ljava/lang/Object;
.field public q:Z

.method public constructor <init>(ILjava/lang/String;)V
    .registers 3
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;->a:I
    iput-object p2, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;->b:Ljava/lang/String;
    return-void
.end method

.method public constructor <init>(Ljava/lang/String;)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;->b:Ljava/lang/String;
    return-void
.end method

.method public a()Z
    .registers 2
    iget-boolean v0, p0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;->q:Z
    return v0
.end method
