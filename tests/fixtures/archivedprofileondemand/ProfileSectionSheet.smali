# Hand-written model of Sync 23033's ProfileSectionSelectionDialogBottomSheet (t9.l1); not the
# original bodies. A4 adds the profile sections as selection items (more on your own profile); r0
# posts the chosen section for the profile's user as a SubredditSelectedEvent and closes the sheet.
.class public Lt9/l1;
.super Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;
.source "ProfileSectionSelectionDialogBottomSheet.java"

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;-><init>()V
    return-void
.end method

.method private C4(Ljava/lang/String;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    .registers 4
    new-instance v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    invoke-static {p1}, Ld7/d;->t(Ljava/lang/String;)I
    move-result v1
    invoke-direct {v0, v1, p1}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;-><init>(ILjava/lang/String;)V
    return-object v0
.end method

.method public A4()V
    .registers 3
    const-string v0, "Overview"
    invoke-direct {p0, v0}, Lt9/l1;->C4(Ljava/lang/String;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    move-result-object v0
    invoke-virtual {p0, v0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    const-string v0, "Comments"
    invoke-direct {p0, v0}, Lt9/l1;->C4(Ljava/lang/String;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    move-result-object v0
    invoke-virtual {p0, v0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    const-string v0, "Submitted"
    invoke-direct {p0, v0}, Lt9/l1;->C4(Ljava/lang/String;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    move-result-object v0
    invoke-virtual {p0, v0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    # Your own profile adds the private sections; any other profile skips to the end.
    invoke-virtual {p0}, Ls9/f;->Y3()Ljava/lang/String;
    move-result-object v0
    invoke-static {}, Lcom/laurencedawson/reddit_sync/singleton/a;->d()Lcom/laurencedawson/reddit_sync/singleton/a;
    move-result-object v1
    invoke-virtual {v1}, Lcom/laurencedawson/reddit_sync/singleton/a;->h()Ljava/lang/String;
    move-result-object v1
    invoke-static {v0, v1}, Lorg/apache/commons/lang3/StringUtils;->equalsIgnoreCase(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :others
    const-string v0, "Upvoted"
    invoke-direct {p0, v0}, Lt9/l1;->C4(Ljava/lang/String;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    move-result-object v0
    invoke-virtual {p0, v0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    const-string v0, "Saved"
    invoke-direct {p0, v0}, Lt9/l1;->C4(Ljava/lang/String;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    move-result-object v0
    invoke-virtual {p0, v0}, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet;->t4(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;
    :others
    return-void
.end method

.method public getTitle()Ljava/lang/String;
    .registers 2
    const-string v0, "Profile"
    return-object v0
.end method

.method public r0(Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;)V
    .registers 5
    invoke-static {}, Lj9/a;->a()Lbd/b;
    move-result-object v0
    new-instance v1, Lh7/d0;
    invoke-virtual {p0}, Ls9/f;->Y3()Ljava/lang/String;
    move-result-object v2
    iget-object p1, p1, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/base/AbstractSelectionDialogBottomSheet$h;->b:Ljava/lang/String;
    invoke-static {v2, p1}, Ld7/d;->g(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p1
    const/4 v2, 0x0
    invoke-direct {v1, p1, v2}, Lh7/d0;-><init>(Ljava/lang/String;Z)V
    invoke-virtual {v0, v1}, Lbd/b;->i(Ljava/lang/Object;)V
    invoke-virtual {p0}, Lcom/google/android/material/bottomsheet/BottomSheetDialogFragment;->x3()V
    return-void
.end method
