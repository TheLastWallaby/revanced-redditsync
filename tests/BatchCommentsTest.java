import app.revanced.extension.syncforreddit.FixLoadMoreCommentsPatch;
import app.revanced.extension.synccompat.RequestCompat;
import com.android.volley.NetworkResponse;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual HTTP requests to a loopback server; no Reddit account or credentials. */
public final class BatchCommentsTest {
    static int checks;
    static final Map<String,String> bodies = new ConcurrentHashMap<>();
    static final Map<String,Integer> statuses = new ConcurrentHashMap<>();
    static final AtomicInteger calls = new AtomicInteger(), authorized = new AtomicInteger();
    static final AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
    static final AtomicInteger fallbackCalls = new AtomicInteger();
    static String base;
    static final Map<String,String> headers = Collections.singletonMap("Authorization", "Bearer TEST_ONLY");
    static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    static JSONObject comment(String id, String parent, JSONObject... replies) {
        JSONObject data = new JSONObject().put("id",id).put("name","t1_"+id)
                .put("parent_id",parent).put("body","body "+id).put("body_html","<p>body "+id+"</p>");
        data.put("replies",replies.length==0?"":listing(new JSONArray(Arrays.asList(replies))));
        return new JSONObject().put("kind","t1").put("data",data);
    }
    static JSONObject more(String id, String parent, String... children) {
        return new JSONObject().put("kind","more").put("data",new JSONObject().put("id",id)
                .put("name","t1_"+id).put("parent_id",parent).put("count",children.length)
                .put("children",new JSONArray(Arrays.asList(children))));
    }
    static JSONObject listing(JSONArray children) {
        return new JSONObject().put("kind","Listing").put("data",new JSONObject().put("children",children));
    }
    static String thread(JSONObject... comments) {
        JSONObject post = new JSONObject().put("kind","t3").put("data",new JSONObject()
                .put("id","post123").put("subreddit","cats"));
        return new JSONArray().put(listing(new JSONArray().put(post)))
                .put(listing(new JSONArray(Arrays.asList(comments)))).toString();
    }
    static NetworkResponse response(String body) {
        return new NetworkResponse(200,body.getBytes(StandardCharsets.UTF_8),
                Collections.singletonMap("X-Test","preserved"),true,123L);
    }
    static Map<String,String> params(String ids) {
        Map<String,String> out = new HashMap<>();out.put("children",ids);out.put("link_id","t3_post123");return out;
    }
    static NetworkResponse load(String first, String ids) {
        return FixLoadMoreCommentsPatch.getMoreChildrenResponse(response(first),base,params(ids),headers);
    }
    static NetworkResponse loadWithParent(String first, String ids, String parent) {
        return FixLoadMoreCommentsPatch.getMoreChildrenResponse(response(first),base,params(ids),headers,parent);
    }
    static JSONArray things(NetworkResponse response) {
        return new JSONObject(new String(response.data,StandardCharsets.UTF_8))
                .getJSONObject("json").getJSONObject("data").getJSONArray("things");
    }
    static List<String> ids(JSONArray things, String kind) {
        List<String> out = new ArrayList<>();
        for(int i=0;i<things.length();i++) {
            JSONObject thing=things.getJSONObject(i);
            if(!kind.equals(thing.getString("kind")))continue;
            JSONObject data=thing.getJSONObject("data");
            if("more".equals(kind)) {
                JSONArray children=data.optJSONArray("children");
                if(children!=null)for(int j=0;j<children.length();j++)out.add(children.getString(j));
            } else out.add(data.getString("id"));
        }
        return out;
    }
    static void reset() { bodies.clear();statuses.clear(); }
    public static void main(String[] args) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService serverThreads=Executors.newCachedThreadPool();server.setExecutor(serverThreads);
        server.createContext("/comments/", exchange->{
            int running=active.incrementAndGet();maximum.accumulateAndGet(running,Math::max);calls.incrementAndGet();
            try {
                if("Bearer TEST_ONLY".equals(exchange.getRequestHeaders().getFirst("Authorization")))authorized.incrementAndGet();
                String path=exchange.getRequestURI().getPath();String id=path.substring(path.lastIndexOf('/')+1);
                boolean fallback = path.endsWith(".json");
                if (fallback) {
                    fallbackCalls.incrementAndGet();
                    for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                        String[] kv=pair.split("=",2);
                        if ("comment".equals(kv[0])) id=URLDecoder.decode(kv[1],"UTF-8");
                    }
                }
                String key=(fallback?"fallback:":"")+id;
                int status=statuses.getOrDefault(key,200);
                byte[] body=bodies.getOrDefault(key,thread(comment(id,"t3_post123"))).getBytes(StandardCharsets.UTF_8);
                try { Thread.sleep(10); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
            } finally { exchange.close();active.decrementAndGet(); }
        });server.start();
        base="http://127.0.0.1:"+server.getAddress().getPort()+"/api/morechildren?always_show_media=1&profile_img=true";
        // Continuation rules here are independent of the per-tap request budget,
        // which BoundedCommentsTest covers.
        Class<?> transport=Class.forName("app.revanced.extension.syncforreddit.CommentTransport");
        java.lang.reflect.Field budget=transport.getDeclaredField("maxRequests");budget.setAccessible(true);budget.setInt(null,1000);
        try {
            check(base.equals(FixLoadMoreCommentsPatch.getUrl(base,null)),"constructor-time null parameters");
            Map<String,String> p=params("t1_a, a,b");p.put("sort","best");
            String url=FixLoadMoreCommentsPatch.getUrl(base,p);
            check(url.contains("/comments/post123/comment/a?always_show_media=1&profile_img=true&sort=confidence"),"focal route, original query and normalized sort");
            p.put("sort","suggested");
            check(!FixLoadMoreCommentsPatch.getUrl(base,p).contains("sort="),"suggested sort uses service default");
            int before=calls.get();
            JSONArray all=things(load(thread(comment("a","t3_post123")),"a,b,c"));
            check(ids(all,"t1").equals(Arrays.asList("a","b","c")),"batch loads every requested sibling in order");
            check(ids(all,"more").isEmpty(),"successful batch has no redundant retry row");
            check(calls.get()-before==2,"first comment reuses Volley response");
            JSONObject normalized=all.getJSONObject(0).getJSONObject("data");
            check("t3_post123".equals(normalized.getString("link_id")),"required link_id reconstructed from original request");
            check("cats".equals(normalized.getString("subreddit")),"required subreddit reconstructed from matching post listing");

            reset();statuses.put("b",503);
            all=things(load(thread(comment("a","t3_post123")),"a,b"));
            check(ids(all,"more").equals(Collections.singletonList("b")),"HTTP 503 leaves failed ID available to retry");
            check(ids(all,"t1").equals(Collections.singletonList("a")),"partial success remains visible");
            all=things(load(thread(comment("a","t3_post123",comment("b","t1_a"))),"a,b"));
            check(ids(all,"more").isEmpty(),"failed request already covered by returned replies creates no retry");
            check(ids(all,"t1").equals(Arrays.asList("a","b")),"nested reply is flattened parent first");
            check(all.getJSONObject(0).getJSONObject("data").getString("replies").isEmpty(),"reply trees removed from flat envelope");

            all=things(load(thread(comment("a","t3_post123",more("b","t1_a","b","c"))),"a,b"));
            check(ids(all,"more").equals(Arrays.asList("b","c")),"existing nested retry represents failed ID exactly once");
            reset();
            all=things(load(thread(comment("a","t3_post123",more("b","t3_post123","b","c"))),"a,b"));
            check(ids(all,"more").equals(Collections.singletonList("c")),"later loaded ID removed from nested retry");

            // An ID already inside a returned tree is reused (issue #3); Reddit marks
            // truncated replies with their own continuation rows.
            bodies.put("b",thread(comment("b","t1_a",comment("c","t1_b"))));
            before=calls.get();
            all=things(load(thread(comment("a","t3_post123",comment("b","t1_a"))),"a,b"));
            check(ids(all,"t1").equals(Arrays.asList("a","b")) && calls.get()==before,"ID already in a returned tree is not requested again");
            reset();
            bodies.put("b",thread(comment("b","t3_post123",comment("c","t1_b"))));
            bodies.put("c",thread(comment("c","t1_b",comment("e","t1_c"))));
            all=things(load(thread(comment("a","t3_post123")),"a,b,c"));
            check(ids(all,"t1").equals(Arrays.asList("a","b","c","e")),"duplicate root fetched at the same time still contributes newly loaded descendants");

            reset();bodies.put("b",thread());bodies.put("fallback:b",thread());
            all=things(load(thread(comment("a","t3_post123")),"a,b"));
            check(ids(all,"more").isEmpty(),"two verified empty responses complete an unavailable extra ID");
            bodies.put("b","malformed");
            bodies.put("fallback:b","malformed");
            all=things(load(thread(comment("a","t3_post123")),"a,b"));
            check(ids(all,"more").equals(Collections.singletonList("b")),"malformed extra response retains unresolved ID");

            reset();before=calls.get();NetworkResponse malformed=response("malformed");
            check(ids(things(FixLoadMoreCommentsPatch.getMoreChildrenResponse(malformed,base,params("a,b"),headers)),"t1")
                    .equals(Arrays.asList("a","b")),"malformed first response recovered through canonical fallback");
            NetworkResponse missing=response(thread(comment("other","t3_post123")));
            check(ids(things(FixLoadMoreCommentsPatch.getMoreChildrenResponse(missing,base,params("a,b"),headers)),"t1")
                    .equals(Arrays.asList("a","b")),"missing focal comment recovered through canonical fallback");
            check(calls.get()-before==4,"one fallback and one sibling request per recovered batch");

            bodies.put("fallback:a",thread());before=calls.get();
            try { load(thread(),"a,b");throw new AssertionError("missing focal should fail without deleting retry row"); }
            catch (RuntimeException ex) {
                check("comment unavailable".equals(FixLoadMoreCommentsPatch.getFailureReason(ex)),"both routes missing the focal comment give controlled error");
                check(RequestCompat.commentsError(new com.android.volley.VolleyError(ex)).contains("comment unavailable"),"controlled failure reaches short user error message");
            }
            check(calls.get()-before==1,"unavailable focal starts no sibling batch");
            reset();statuses.put("fallback:a",503);
            try { load(thread(),"a,b");throw new AssertionError("fallback HTTP 503 should fail"); }
            catch (RuntimeException ex) {
                check("fallback request failed".equals(FixLoadMoreCommentsPatch.getFailureReason(ex)),"failed fallback preserves useful short error");
            }
            reset();
            check(ids(things(load("{broken","a")),"t1").equals(Collections.singletonList("a")),"malformed object first response recovered");
            String legacy="{\"json\":{\"errors\":[],\"data\":{\"things\":[]}}}";
            NetworkResponse legacyResponse=response(legacy);before=calls.get();
            check(FixLoadMoreCommentsPatch.getMoreChildrenResponse(legacyResponse,base,params("a"),headers)==legacyResponse,"valid legacy envelope preserved");
            check(calls.get()==before,"legacy envelope does not trigger network requests");

            JSONObject sparse=comment("a","t3_post123");sparse.getJSONObject("data").put("gildings",JSONObject.NULL);
            JSONObject fixed=things(load(thread(sparse),"a")).getJSONObject(0).getJSONObject("data");
            check(!fixed.has("gildings"),"null gildings no longer trips Sync's has/getJSONObject pair");
            check("t3_post123".equals(fixed.getString("link_id")) && "cats".equals(fixed.getString("subreddit")),"sparse comment satisfies original parser's required metadata fields");
            JSONObject focal=comment("a","t1_parent",comment("child","t1_a"));
            all=things(load(thread(comment("parent","t3_post123",focal)),"a"));
            check(ids(all,"t1").equals(Arrays.asList("a","child")),"only requested focal branch returned when endpoint includes ancestors");
            bodies.put("b",thread());before=fallbackCalls.get();
            all=things(load(thread(comment("a","t3_post123")),"a,b"));
            check(ids(all,"t1").equals(Arrays.asList("a","b")) && fallbackCalls.get()==before+1,"missing extra comment recovered by canonical fallback");
            reset();

            JSONObject sparseListing=listing(new JSONArray().put(comment("a","t3_post123")));
            before=fallbackCalls.get();
            fixed=things(load(sparseListing.toString(),"a")).getJSONObject(0).getJSONObject("data");
            check("cats".equals(fixed.getString("subreddit")) && fallbackCalls.get()==before+1,"standalone listing missing metadata recovered from full post response");
            JSONObject complete=comment("a","t3_post123");complete.getJSONObject("data").put("subreddit","cats").put("link_id","t3_post123");
            before=fallbackCalls.get();
            check(ids(things(load(listing(new JSONArray().put(complete)).toString(),"a")),"t1").equals(Collections.singletonList("a"))
                    && fallbackCalls.get()==before,"complete standalone comment listing accepted directly");

            JSONObject broken=comment("b","t3_post123",comment("nested","t1_b"));
            broken.getJSONObject("data").getJSONObject("replies").getJSONObject("data")
                    .getJSONArray("children").getJSONObject(0).getJSONObject("data").remove("body");
            bodies.put("b",thread(broken));
            all=things(load(thread(comment("a","t3_post123")),"a,b"));
            check(ids(all,"t1").equals(Collections.singletonList("a")) && ids(all,"more").equals(Collections.singletonList("b")),
                    "invalid extra branch committed atomically and retained for retry");
            reset();

            NetworkResponse original=response(thread(comment("a","t3_post123")));
            byte[] originalBytes=original.data.clone();Map<String,String> originalParams=params("t1_a,a,b");
            NetworkResponse converted=FixLoadMoreCommentsPatch.getMoreChildrenResponse(original,base,originalParams,headers);
            check(converted.headers==original.headers && converted.statusCode==200 && converted.notModified && converted.networkTimeMs==123,"network metadata retained");
            check(Arrays.equals(original.data,originalBytes),"original response bytes unmodified");
            check("t1_a,a,b".equals(originalParams.get("children")),"original parameter map unmodified");
            check(ids(things(converted),"t1").equals(Arrays.asList("a","b")),"duplicate requested IDs normalized");

            reset();bodies.put("fallback:a",thread(comment("unrelated","t3_post123")));before=calls.get();
            all=things(loadWithParent(thread(),"a,b","t3_post123"));
            check(ids(all,"t1").equals(Collections.singletonList("b")),"unavailable first ID no longer blocks available sibling");
            check(ids(all,"more").equals(Collections.singletonList("a")),"unavailable first ID remains available for retry");
            check("t3_post123".equals(all.getJSONObject(all.length()-1).getJSONObject("data").getString("parent_id")),"top-level retry keeps clicked row's parent");
            check(calls.get()-before==2,"only first fallback and available sibling requested");

            bodies.put("b",thread(comment("b","t1_otherparent")));
            all=things(loadWithParent(thread(),"a,b","t1_clickedparent"));
            check("t1_clickedparent".equals(all.getJSONObject(all.length()-1).getJSONObject("data").getString("parent_id")),"nested retry uses original parent instead of inferring it from sibling");
            bodies.put("b",thread(comment("b","t3_post123",comment("a","t1_b"))));
            all=things(loadWithParent(thread(),"a,b","t3_post123"));
            check(ids(all,"t1").equals(Arrays.asList("b","a")) && ids(all,"more").isEmpty(),"first missing ID loaded by later tree creates no extra retry");

            reset();statuses.put("fallback:a",503);
            all=things(loadWithParent(thread(),"a,b","t3_post123"));
            check(ids(all,"t1").equals(Collections.singletonList("b")) && ids(all,"more").equals(Collections.singletonList("a")),"first fallback HTTP failure permits remaining batch to load");
            reset();bodies.put("fallback:a",thread());bodies.put("b",thread());bodies.put("fallback:b",thread());
            check(things(loadWithParent(thread(),"a,b","t3_post123")).length()==0,"fully verified empty final batch uses native empty completion");
            before=calls.get();
            try { loadWithParent(thread(),"a,b","bad-parent");throw new AssertionError("invalid parent must not be inferred"); }
            catch (RuntimeException ex) { check("comment unavailable".equals(FixLoadMoreCommentsPatch.getFailureReason(ex)),"unavailable first comment without valid parent stays conservative"); }
            check(calls.get()-before==1,"invalid parent starts no sibling requests");

            reset();List<String> many=new ArrayList<>();for(int i=0;i<105;i++)many.add("c"+i);
            for(int i=0;i<100;i++) { bodies.put("c"+i,thread(comment("unrelated","t3_post123")));bodies.put("fallback:c"+i,thread(comment("unrelated","t3_post123"))); }
            all=things(loadWithParent(thread(),String.join(",",many),"t3_post123"));
            List<String> rotated=ids(all,"more");
            check(ids(all,"t1").isEmpty() && rotated.size()==105,"unavailable 100-ID batch preserves every pending ID");
            check(rotated.subList(0,5).equals(many.subList(100,105)),"untouched IDs advance ahead of failed IDs");
            check(new HashSet<>(rotated).equals(new HashSet<>(many)),"rotation neither drops nor duplicates IDs");
            all=things(loadWithParent(thread(comment("c100","t3_post123")),String.join(",",rotated),"t3_post123"));
            check(ids(all,"t1").equals(many.subList(100,105)),"next tap can reach available later batch");
            check(new HashSet<>(ids(all,"more")).equals(new HashSet<>(many.subList(0,100))),"failed IDs stay pending after later batch succeeds");

            reset();
            before=calls.get();all=things(load(thread(comment("c0","t3_post123")),String.join(",",many)));
            check(calls.get()-before==99,"100-ID batch limit includes first response");
            check(ids(all,"t1").size()==100,"100 requested comments returned");
            check(ids(all,"more").equals(many.subList(100,105)),"all IDs beyond batch remain available to retry");
            check(maximum.get()>1 && maximum.get()<=4,"parallel requests bounded at four");
            check(authorized.get()==calls.get(),"original authorization copied to every additional request");

            reset();all=things(load(thread(comment("a","t3_post123",more("_","t1_a"),comment("d","t1_a",more("_","t1_d")))),"a"));
            int continuations=0;for(int i=0;i<all.length();i++)if("more".equals(all.getJSONObject(i).getString("kind")))continuations++;
            check(continuations==2,"continue-thread rows with distinct parents retained");
            check(FixLoadMoreCommentsPatch.getMoreChildrenResponse(null,base,params("a"),headers)==null,"null response safe");


            reset();bodies.put("fallback:a",thread());
            try { load(thread(),"a,b");throw new AssertionError("expected missing-parent diagnostic"); }
            catch (RuntimeException ex) {
                String details=FixLoadMoreCommentsPatch.getDiagnosticDetails(ex);
                check(details.contains("stage=parent_missing") && details.contains("parent=missing"),"diagnostic distinguishes missing continuation metadata");
                check(details.contains("requested=2 attempted=1"),"diagnostic shows batch stopped before sibling requests");
                check(details.contains("first_comments=0 fallback_comments=0"),"diagnostic counts empty first and fallback listings");
            }
            String pendingA=thread(more("a","t3_post123","a"));
            bodies.put("fallback:a",pendingA);
            bodies.put("b",thread(more("b","t3_post123","b")));bodies.put("fallback:b",thread(more("b","t3_post123","b")));
            try { loadWithParent(pendingA,"a,b","t3_post123");throw new AssertionError("expected empty-batch diagnostic"); }
            catch (RuntimeException ex) {
                String details=FixLoadMoreCommentsPatch.getDiagnosticDetails(new com.android.volley.VolleyError(new RuntimeException("NEVER_EXPORT_WRAPPER",ex)));
                check(details.contains("stage=empty_batch") && details.contains("requested=2 attempted=2 parent=valid"),"diagnostic distinguishes fully attempted empty batch");
                check(details.contains("missing=2") && details.contains("http_failures={}"),"diagnostic counts unavailable IDs and remains isolated per request");
                check(details.contains("first_roots=1 fallback_roots=1") && details.contains("first_more=1 fallback_more=1"),"diagnostic distinguishes only-more responses from empty listings");
                check(!details.contains("NEVER_EXPORT_WRAPPER"),"wrapper exception messages excluded from report");
            }
            reset();statuses.put("fallback:a",503);statuses.put("b",403);
            try { loadWithParent(thread(),"a,b","t3_post123");throw new AssertionError("expected HTTP diagnostic"); }
            catch (RuntimeException ex) {
                String details=FixLoadMoreCommentsPatch.getDiagnosticDetails(ex);
                check(details.contains("http_failures={403=1, 503=1}"),"diagnostic retains HTTP statuses from fallback and worker failures");
            }
            reset();bodies.put("fallback:a",thread());
            JSONObject privateComment=comment("PRIVATE_COMMENT_ID","t3_post123");
            privateComment.getJSONObject("data").put("author","NEVER_EXPORT_USERNAME").put("body","NEVER_EXPORT_BODY");
            Map<String,String> privateParams=params("a");privateParams.put("access_token","NEVER_EXPORT_TOKEN");
            try { FixLoadMoreCommentsPatch.getMoreChildrenResponse(response(thread(privateComment)),base,privateParams,headers,"t3_post123");throw new AssertionError("expected privacy diagnostic"); }
            catch (RuntimeException ex) {
                String details=FixLoadMoreCommentsPatch.getDiagnosticDetails(ex);
                check(details.contains("first_comments=1 fallback_comments=0"),"diagnostic distinguishes unrelated returned comments from an empty listing");
                check(details.contains("first_post_match=1 fallback_post_match=1"),"diagnostic compares post identity without exporting it");
                check(!details.contains("NEVER_EXPORT") && !details.contains("PRIVATE_COMMENT_ID") && !details.contains("post123") && !details.contains("Bearer"),"diagnostic exports no account data, IDs, headers or comment bodies");
                check(details.contains("http_failures={}"),"previous request's HTTP failures do not leak into next diagnostic");
            }
            String wrongPost=thread().replace("post123","OTHER_PRIVATE_POST");
            try { loadWithParent(wrongPost,"a","t3_post123");throw new AssertionError("expected mismatched post diagnostic"); }
            catch (RuntimeException ex) {
                String details=FixLoadMoreCommentsPatch.getDiagnosticDetails(ex);
                check(details.contains("first_post_match=0") && !details.contains("OTHER_PRIVATE_POST"),"diagnostic detects wrong post while excluding its ID");
            }
            com.android.volley.VolleyError rawError=new com.android.volley.VolleyError(new NetworkResponse(403,"NEVER_EXPORT_RESPONSE".getBytes(StandardCharsets.UTF_8),Collections.emptyMap(),false,0));
            String outside=FixLoadMoreCommentsPatch.getDiagnosticDetails(rawError);
            check(outside.contains("stage=outside_adapter") && outside.contains("http=403") && !outside.contains("NEVER_EXPORT"),"pre-adapter errors report status without response content");

            reset();
            for(String id:Arrays.asList("a","b","c","d")) { bodies.put(id,thread());bodies.put("fallback:"+id,thread()); }
            NetworkResponse fourInput=response(thread());byte[] fourBytes=fourInput.data.clone();
            NetworkResponse four=FixLoadMoreCommentsPatch.getMoreChildrenResponse(fourInput,base,params("a,b,c,d"),headers,"t3_post123");
            check(things(four).length()==0,"four confirmed empty lookups complete without loading error");
            check(new JSONObject(new String(four.data,StandardCharsets.UTF_8)).getJSONObject("json").getJSONArray("errors").length()==0,"empty completion uses successful native morechildren envelope");
            check(four.headers==fourInput.headers && four.networkTimeMs==fourInput.networkTimeMs && Arrays.equals(fourInput.data,fourBytes),"empty completion preserves HTTP metadata and original input");

            reset();bodies.put("fallback:a",thread());statuses.put("b",503);
            all=things(loadWithParent(thread(),"a,b","t3_post123"));
            check(ids(all,"t1").isEmpty() && ids(all,"more").equals(Collections.singletonList("b")),"verified empty ID completes while failed HTTP ID stays pending");
            check("t3_post123".equals(all.getJSONObject(0).getJSONObject("data").getString("parent_id")),"remaining HTTP retry stays under original parent");

            reset();bodies.put("b",thread());bodies.put("fallback:b",thread());
            all=things(loadWithParent(thread(comment("a","t3_post123",more("b","t1_a","b"))),"a,b","t3_post123"));
            check(ids(all,"t1").equals(Collections.singletonList("a")) && ids(all,"more").isEmpty(),"confirmed empty ID removed from nested continuation without losing loaded comment");

            reset();List<String> hundredFive=new ArrayList<>();for(int i=0;i<105;i++)hundredFive.add("q"+i);
            for(int i=0;i<100;i++) { bodies.put("q"+i,thread());bodies.put("fallback:q"+i,thread()); }
            all=things(loadWithParent(thread(),String.join(",",hundredFive),"t3_post123"));
            check(ids(all,"more").equals(hundredFive.subList(100,105)),"100 verified empty IDs complete while untouched IDs stay pending");
            all=things(loadWithParent(thread(comment("q100","t3_post123")),String.join(",",hundredFive.subList(100,105)),"t3_post123"));
            check(ids(all,"t1").equals(hundredFive.subList(100,105)) && ids(all,"more").isEmpty(),"later available batch loads after empty batch completes");

            reset();bodies.put("fallback:a",thread());
            List<String> unverified=Arrays.asList(
                    thread().replace("post123","wrongpost"),
                    listing(new JSONArray()).toString(),
                    thread(more("a","t3_post123","a")),
                    thread(comment("unrelated","t3_post123")),
                    "malformed",
                    new JSONArray(thread()).put(new JSONObject()).toString());
            for(String first:unverified) {
                try { loadWithParent(first,"a","t3_post123");throw new AssertionError("unverified response must retain original continuation"); }
                catch(RuntimeException ex) { check(FixLoadMoreCommentsPatch.getFailureReason(ex)!=null,"nonempty, mismatched, incomplete or malformed response never becomes empty completion"); }
            }
            NetworkResponse cached=new NetworkResponse(304,thread().getBytes(StandardCharsets.UTF_8),Collections.emptyMap(),true,0);
            try { FixLoadMoreCommentsPatch.getMoreChildrenResponse(cached,base,params("a"),headers,"t3_post123");throw new AssertionError("304 cannot prove empty completion"); }
            catch(RuntimeException ex) { check(FixLoadMoreCommentsPatch.getDiagnosticDetails(ex).contains("first_http=304"),"empty completion requires HTTP 200 on both routes"); }
            System.out.println("PASS: "+checks+" checks with real Volley and loopback HTTP; peak parallel requests="+maximum.get());
        } finally {
            server.stop(0);serverThreads.shutdownNow();
            java.lang.reflect.Field field=transport.getDeclaredField("executor");field.setAccessible(true);
            ((ExecutorService)field.get(null)).shutdownNow();
        }
    }
}
