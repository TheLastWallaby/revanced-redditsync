.class public abstract Lc9/c;
.super Ljava/lang/Object;
.source "PostParserHelper.java"

# Hand-written model of PostParserHelper.a (c9.c.a) in Sync 23033: the parameter copies, the one
# ContentValues per item, its id/kind columns, a post's selftext_raw/selftext_processed/
# selftext_processed_preview, a comment's body columns, a content-filter early return, the single
# add to the result list after the merge label, and register reuse after the add.

.method public static a(Landroid/content/Context;ILorg/json/JSONObject;Ljava/util/ArrayList;IZZLjava/lang/String;)V
    .registers 37

    move-object/from16 v9, p0
    move/from16 v1, p1
    move-object/from16 v2, p2
    move-object/from16 v10, p3
    move-object/from16 v11, p7

    const/4 v3, 0x3
    const/4 v4, 0x1
    if-eq v1, v4, :cond_kind
    if-eq v1, v3, :cond_kind
    const/16 v0, 0x9
    if-eq v1, v0, :cond_kind
    return-void

    :cond_kind
    if-eqz v9, :cond_end
    if-eqz v2, :cond_end
    if-nez v10, :cond_parse
    goto :cond_end

    :cond_parse
    new-instance v5, Landroid/content/ContentValues;
    invoke-direct {v5}, Landroid/content/ContentValues;-><init>()V

    const-string v0, "id"
    invoke-virtual {v2, v0}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v6
    const-string v7, "_id"
    invoke-virtual {v5, v7, v6}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V

    invoke-static/range {p1 .. p1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v6
    const-string v8, "kind"
    invoke-virtual {v5, v8, v6}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/Integer;)V

    if-ne v1, v4, :cond_post

    # Comment: body columns.
    const-string v0, "body"
    invoke-virtual {v2, v0}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lwc/p;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v11, v0}, Ld7/f;->r(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v12
    const-string v7, "body_raw"
    invoke-virtual {v5, v7, v0}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V
    const-string v7, "body_processed"
    invoke-virtual {v5, v7, v12}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V
    goto :cond_add

    :cond_post
    const-string v0, "title"
    invoke-virtual {v2, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v6
    invoke-virtual {v5, v0, v6}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V

    # Content filter: Sync returns before the add for an excluded post.
    const-string v0, "over_18"
    invoke-virtual {v2, v0}, Lorg/json/JSONObject;->optBoolean(Ljava/lang/String;)Z
    move-result v6
    if-eqz v6, :cond_text
    const-string v0, "Content filter excluded a NSFW post"
    invoke-static {v0}, Lwc/i;->e(Ljava/lang/String;)V
    return-void

    :cond_text
    const-string v0, "selftext"
    invoke-virtual {v2, v0}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lwc/p;->a(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v11, v0}, Ld7/f;->r(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v12
    const-string v7, "selftext_raw"
    invoke-virtual {v5, v7, v0}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V
    const-string v7, "selftext_processed"
    invoke-virtual {v5, v7, v12}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V
    const-string v7, "selftext_processed_preview"
    invoke-virtual {v5, v7, v12}, Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/String;)V

    :cond_add
    move-object/from16 v11, p3
    invoke-virtual {v11, v5}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

    # After the add Sync reuses v2 and v5 (replies of a comment).
    if-ne v1, v4, :cond_end
    const-string v2, "replies"
    const/4 v5, 0x0

    :cond_end
    return-void
.end method
