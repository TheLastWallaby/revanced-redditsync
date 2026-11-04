# Hand-written model of Sync 23033's CustomUrlSpan (mb.d), the span of every link in a post body or
# comment. onClick keeps the Amazon report's shape (two contains checks, then new-instance
# AmazonAnalyticsRequest, the link, its constructor and RequestManager.a) and is otherwise reduced
# to the flair, table and ordinary-link branches.
.class public Lmb/d;
.super Landroid/text/style/CharacterStyle;
.source "CustomUrlSpan.java"

.field private a:Ljava/lang/String;

.method public constructor <init>(Ljava/lang/String;)V
    .registers 2
    invoke-direct {p0}, Landroid/text/style/CharacterStyle;-><init>()V
    iput-object p1, p0, Lmb/d;->a:Ljava/lang/String;
    return-void
.end method

# The tapped link; a bare /u/ profile path becomes a reddit.com link.
.method public a()Ljava/lang/String;
    .registers 3
    iget-object v0, p0, Lmb/d;->a:Ljava/lang/String;
    const-string v1, "/u/"
    invoke-static {v0, v1}, Lorg/apache/commons/lang3/StringUtils;->startsWith(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v1
    if-eqz v1, :plain
    const-string v1, "https://reddit.com"
    invoke-virtual {v1, v0}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    :plain
    return-object v0
.end method

.method public onClick(Landroid/view/View;)V
    .registers 5
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lwc/i;->e(Ljava/lang/String;)V

    # Amazon product pages are reported to Sync's ad server before the link opens.
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v0
    const-string v1, "amazon.com/"
    invoke-static {v0, v1}, Lorg/apache/commons/lang3/StringUtils;->contains(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :links
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v0
    const-string v1, "/dp/"
    invoke-static {v0, v1}, Lorg/apache/commons/lang3/StringUtils;->contains(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :links
    new-instance v0, Le7/e;
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v1
    invoke-direct {v0, v1}, Le7/e;-><init>(Ljava/lang/String;)V
    invoke-static {v0}, Lm8/a;->a(Lcom/android/volley/Request;)V

    :links
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v0
    const-string v1, "sync-flair://"
    invoke-static {v0, v1}, Lorg/apache/commons/lang3/StringUtils;->startsWith(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v0
    const-string v2, ""
    if-eqz v0, :table
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lgb/p;->d(Ljava/lang/String;)V
    goto :done

    :table
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v0
    const-string v1, "<table>"
    invoke-static {v0, v1}, Lorg/apache/commons/lang3/StringUtils;->startsWith(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v0
    if-eqz v0, :open
    const-class v0, Lcom/laurencedawson/reddit_sync/ui/fragment_dialogs/bottom/material_dialogs/TableDialogBottomSheet;
    invoke-virtual {p1}, Landroid/view/View;->getContext()Landroid/content/Context;
    move-result-object v1
    invoke-static {v1}, Lt7/j;->g(Landroid/content/Context;)Landroidx/fragment/app/FragmentManager;
    move-result-object v1
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v2
    invoke-static {v0, v1, v2}, Ls9/g;->j(Ljava/lang/Class;Landroidx/fragment/app/FragmentManager;Ljava/lang/String;)V
    goto :done

    # Every other link goes to Sync's link router.
    :open
    invoke-virtual {p1}, Landroid/view/View;->getContext()Landroid/content/Context;
    move-result-object v0
    invoke-virtual {p0}, Lmb/d;->a()Ljava/lang/String;
    move-result-object v1
    invoke-static {v0, v1}, Ly7/b;->b(Landroid/content/Context;Ljava/lang/String;)Z

    :done
    return-void
.end method
