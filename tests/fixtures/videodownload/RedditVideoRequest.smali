# Hand-written model of Sync 23033's RedditVideoRequest (r8.g); not the original bodies. Like Sync,
# it parses the manifest with a namespace-unaware DOM, takes the AdaptationSet whose text mentions
# "DASH_audio" as audio and parses every other set's bandwidths with Integer.parseInt.
.class public Lr8/g;
.super Lcom/android/volley/Request;
.source "RedditVideoRequest.java"

.field private c:Ljava/lang/String;

.method private static c(Lorg/w3c/dom/Element;)Ljava/lang/String;
    .registers 4
    const-string v0, "Representation"
    invoke-interface {p0, v0}, Lorg/w3c/dom/Element;->getElementsByTagName(Ljava/lang/String;)Lorg/w3c/dom/NodeList;
    move-result-object p0
    const/4 v0, 0x0
    invoke-interface {p0, v0}, Lorg/w3c/dom/NodeList;->item(I)Lorg/w3c/dom/Node;
    move-result-object p0
    check-cast p0, Lorg/w3c/dom/Element;
    const-string v1, "BaseURL"
    invoke-interface {p0, v1}, Lorg/w3c/dom/Element;->getElementsByTagName(Ljava/lang/String;)Lorg/w3c/dom/NodeList;
    move-result-object p0
    invoke-interface {p0, v0}, Lorg/w3c/dom/NodeList;->item(I)Lorg/w3c/dom/Node;
    move-result-object p0
    invoke-interface {p0}, Lorg/w3c/dom/Node;->getTextContent()Ljava/lang/String;
    move-result-object p0
    return-object p0
.end method

.method private d(Lorg/w3c/dom/Element;)Ljava/lang/String;
    .registers 9
    const-string v0, "Representation"
    invoke-interface {p1, v0}, Lorg/w3c/dom/Element;->getElementsByTagName(Ljava/lang/String;)Lorg/w3c/dom/NodeList;
    move-result-object p1
    const/4 v0, 0x0
    const/4 v1, -0x1
    const/4 v2, 0x0
    :loop
    invoke-interface {p1}, Lorg/w3c/dom/NodeList;->getLength()I
    move-result v3
    if-ge v2, v3, :done
    invoke-interface {p1, v2}, Lorg/w3c/dom/NodeList;->item(I)Lorg/w3c/dom/Node;
    move-result-object v3
    check-cast v3, Lorg/w3c/dom/Element;
    const-string v4, "BaseURL"
    invoke-interface {v3, v4}, Lorg/w3c/dom/Element;->getElementsByTagName(Ljava/lang/String;)Lorg/w3c/dom/NodeList;
    move-result-object v4
    const/4 v5, 0x0
    invoke-interface {v4, v5}, Lorg/w3c/dom/NodeList;->item(I)Lorg/w3c/dom/Node;
    move-result-object v4
    invoke-interface {v4}, Lorg/w3c/dom/Node;->getTextContent()Ljava/lang/String;
    move-result-object v4
    const-string v5, "bandwidth"
    invoke-interface {v3, v5}, Lorg/w3c/dom/Element;->getAttribute(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v5
    invoke-static {v5}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v5
    if-le v5, v1, :next
    move v1, v5
    move-object v0, v4
    :next
    add-int/lit8 v2, v2, 0x1
    goto :loop
    :done
    if-nez v0, :found
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Ljava/lang/String;
    :found
    return-object v0
.end method

.method public parseNetworkResponse(Lcom/android/volley/NetworkResponse;)Lcom/android/volley/Response;
    .registers 10
    const-string v0, "RVD"
    :try_start
    new-instance v1, Ljava/lang/String;
    iget-object v2, p1, Lcom/android/volley/NetworkResponse;->data:[B
    invoke-direct {v1, v2}, Ljava/lang/String;-><init>([B)V
    invoke-static {}, Ljavax/xml/parsers/DocumentBuilderFactory;->newInstance()Ljavax/xml/parsers/DocumentBuilderFactory;
    move-result-object v2
    invoke-virtual {v2}, Ljavax/xml/parsers/DocumentBuilderFactory;->newDocumentBuilder()Ljavax/xml/parsers/DocumentBuilder;
    move-result-object v2
    new-instance v3, Ljava/io/ByteArrayInputStream;
    const-string v4, "UTF-8"
    invoke-virtual {v1, v4}, Ljava/lang/String;->getBytes(Ljava/lang/String;)[B
    move-result-object v4
    invoke-direct {v3, v4}, Ljava/io/ByteArrayInputStream;-><init>([B)V
    invoke-virtual {v2, v3}, Ljavax/xml/parsers/DocumentBuilder;->parse(Ljava/io/InputStream;)Lorg/w3c/dom/Document;
    move-result-object v1
    const-string v2, "AdaptationSet"
    invoke-interface {v1, v2}, Lorg/w3c/dom/Document;->getElementsByTagName(Ljava/lang/String;)Lorg/w3c/dom/NodeList;
    move-result-object v1
    const/4 v2, 0x0
    const/4 v3, 0x0
    move-object v4, v3
    :loop
    invoke-interface {v1}, Lorg/w3c/dom/NodeList;->getLength()I
    move-result v5
    if-ge v2, v5, :done
    invoke-interface {v1, v2}, Lorg/w3c/dom/NodeList;->item(I)Lorg/w3c/dom/Node;
    move-result-object v5
    check-cast v5, Lorg/w3c/dom/Element;
    invoke-interface {v5}, Lorg/w3c/dom/Node;->getTextContent()Ljava/lang/String;
    move-result-object v6
    const-string v7, "DASH_audio"
    invoke-virtual {v6, v7}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
    move-result v6
    if-nez v6, :audio
    invoke-direct {p0, v5}, Lr8/g;->d(Lorg/w3c/dom/Element;)Ljava/lang/String;
    move-result-object v3
    goto :next
    :audio
    invoke-static {v5}, Lr8/g;->c(Lorg/w3c/dom/Element;)Ljava/lang/String;
    move-result-object v4
    :next
    add-int/lit8 v2, v2, 0x1
    goto :loop
    :done
    new-instance v0, Lo8/h;
    iget-object v1, p0, Lr8/g;->c:Ljava/lang/String;
    invoke-direct {v0, v1, v3, v4}, Lo8/h;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    const-wide/32 v1, 0x36ee80
    invoke-static {p1, v1, v2}, Ld9/a;->a(Lcom/android/volley/NetworkResponse;J)Lcom/android/volley/Cache$Entry;
    move-result-object p1
    invoke-static {v0, p1}, Lcom/android/volley/Response;->success(Ljava/lang/Object;Lcom/android/volley/Cache$Entry;)Lcom/android/volley/Response;
    move-result-object p1
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :catch
    return-object p1
    :catch
    move-exception p1
    new-instance v0, Lcom/android/volley/ParseError;
    invoke-direct {v0, p1}, Lcom/android/volley/ParseError;-><init>(Ljava/lang/Throwable;)V
    invoke-static {v0}, Lcom/android/volley/Response;->error(Lcom/android/volley/VolleyError;)Lcom/android/volley/Response;
    move-result-object p1
    return-object p1
.end method
