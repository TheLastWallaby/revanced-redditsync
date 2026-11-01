import app.revanced.extension.syncforreddit.FixLoadMoreCommentsPatch;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Request bounds for "load more comments" (issue #3): budget, subtree reuse, shared
 * rate limits, 401/403/429, deadline, cancellation and Reddit's batch endpoint.
 * The batch attempt is off by default (issue #51); its scenarios turn it on by reflection.
 * Real Volley types and HTTP to a loopback server; no Reddit account or credentials.
 */
public final class BoundedCommentsTest {
    static int checks;
    static String base;
    static Class<?> transport;
    static final Map<String,String> bodies = new ConcurrentHashMap<>();
    static final Map<String,Integer> statuses = new ConcurrentHashMap<>();
    static final Map<String,Long> delays = new ConcurrentHashMap<>();
    static final Map<String,AtomicInteger> throttled = new ConcurrentHashMap<>();
    static final Map<String,AtomicInteger> perId = new ConcurrentHashMap<>();
    static final AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
    static final AtomicInteger batchCalls = new AtomicInteger(), batchActive = new AtomicInteger(), batchPeak = new AtomicInteger();
    /** /api/morechildren requests that arrived while the batch attempt was turned off. */
    static final AtomicInteger batchCallsWhileOff = new AtomicInteger();
    static final Set<String> batchOmit = ConcurrentHashMap.newKeySet();
    static volatile String retryAfter = "1", rateRemaining, rateReset, requiredAuth, batchBody, lastBatchQuery;
    static volatile int batchStatus = 403;
    static final Map<String,String> plain = Collections.singletonMap("Authorization", "Bearer TEST_ONLY");
    static final Map<String,String> stale = Collections.singletonMap("Authorization", "Bearer STALE_TEST_TOKEN");
    static final Map<String,String> fresh = Collections.singletonMap("Authorization", "Bearer FRESH_TEST_TOKEN");

    static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    /** Stands in for Sync's OAuthCommentsMoreRequest: cancellable, and supplies refreshed headers. */
    static final class SyncRequest extends Request<String> {
        final Map<String,String> refreshed;
        final AtomicInteger headerReads = new AtomicInteger();
        SyncRequest(Map<String,String> refreshed) { super(Method.GET, "", null); this.refreshed = refreshed; }
        @Override public Map<String,String> getHeaders() { headerReads.incrementAndGet(); return refreshed; }
        @Override protected Response<String> parseNetworkResponse(NetworkResponse response) { return null; }
        @Override protected void deliverResponse(String response) { }
    }

    static JSONObject comment(String id, String parent, JSONObject... replies) {
        JSONObject data = new JSONObject().put("id",id).put("name","t1_"+id)
                .put("parent_id",parent).put("body","body "+id);
        data.put("replies",replies.length==0?"":listing(new JSONArray(Arrays.asList(replies))));
        return new JSONObject().put("kind","t1").put("data",data);
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
    static List<String> range(String prefix, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(prefix + i);
        return out;
    }
    static Map<String,String> params(String children) {
        Map<String,String> out = new HashMap<>();
        out.put("children",children);out.put("link_id","t3_post123");out.put("sort","best");
        return out;
    }
    static NetworkResponse response(String body, Map<String,String> headers) {
        return new NetworkResponse(200, body.getBytes(StandardCharsets.UTF_8), headers, false, 0L);
    }
    static JSONArray things(NetworkResponse response) {
        return new JSONObject(new String(response.data,StandardCharsets.UTF_8))
                .getJSONObject("json").getJSONObject("data").getJSONArray("things");
    }
    static NetworkResponse raw(String first, String children, Map<String,String> headers, Request<?> request,
                               Map<String,String> responseHeaders) {
        return FixLoadMoreCommentsPatch.getMoreChildrenResponse(response(first, responseHeaders), base,
                params(children), headers, "t3_post123", request);
    }
    static JSONArray load(List<String> ids, Map<String,String> headers, Request<?> request) {
        return things(raw(thread(comment(ids.get(0),"t3_post123")), String.join(",",ids), headers, request,
                Collections.<String,String>emptyMap()));
    }
    static JSONArray load(List<String> ids) { return load(ids, plain, null); }
    /** Reason and diagnostic of a load that must fail with Sync's row kept in place. */
    static String[] failure(List<String> ids, Map<String,String> headers, Request<?> request) {
        try {
            raw(thread(), String.join(",",ids), headers, request, Collections.<String,String>emptyMap());
        } catch (RuntimeException ex) {
            return new String[]{FixLoadMoreCommentsPatch.getFailureReason(ex), FixLoadMoreCommentsPatch.getDiagnosticDetails(ex)};
        }
        throw new AssertionError("expected a controlled failure");
    }
    static List<String> ids(JSONArray things, String kind) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < things.length(); i++) {
            JSONObject thing = things.getJSONObject(i);
            if (!kind.equals(thing.getString("kind"))) continue;
            JSONObject data = thing.getJSONObject("data");
            if ("more".equals(kind)) {
                JSONArray children = data.optJSONArray("children");
                if (children != null) for (int j = 0; j < children.length(); j++) out.add(children.getString(j));
            } else out.add(data.getString("id"));
        }
        return out;
    }
    /** Every requested ID is either loaded or still pending, exactly once. */
    static boolean complete(JSONArray things, List<String> requested) {
        List<String> seen = new ArrayList<>(ids(things,"t1"));
        seen.addAll(ids(things,"more"));
        return seen.size() == requested.size() && new HashSet<>(seen).equals(new HashSet<>(requested));
    }
    static int perId(String key) { AtomicInteger count = perId.get(key); return count == null ? 0 : count.get(); }

    static void limit(String name, long value) throws Exception {
        Field field = transport.getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == int.class) field.setInt(null, (int) value); else field.setLong(null, value);
    }
    static Field batchSwitch() throws Exception {
        Field field = FixLoadMoreCommentsPatch.class.getDeclaredField("batchEndpoint");
        field.setAccessible(true);
        return field;
    }
    /** Turns the retained /api/morechildren attempt on or off (off by default since issue #51). */
    static void batch(boolean on) throws Exception { batchSwitch().setBoolean(null, on); }
    static boolean batchOn() {
        try { return batchSwitch().getBoolean(null); } catch (Exception unavailable) { throw new AssertionError(unavailable); }
    }
    static ThreadPoolExecutor executor() throws Exception {
        Field field = transport.getDeclaredField("executor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }
    static boolean workersIdleWithin(long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (executor().getActiveCount() == 0) return true;
            Thread.sleep(20);
        }
        return executor().getActiveCount() == 0;
    }
    static void reset() throws Exception {
        bodies.clear();statuses.clear();delays.clear();throttled.clear();perId.clear();batchOmit.clear();
        retryAfter = "1";rateRemaining = null;rateReset = null;requiredAuth = null;batchBody = null;lastBatchQuery = null;
        batchStatus = 403;peak.set(0);batchPeak.set(0);d9.d.reset();
        limit("maxRequests",32);limit("deadlineMs",20_000);limit("timeoutMs",15_000);limit("rateReserve",10);
        limit("defaultBackoffMs",5_000);limit("maxBackoffMs",60_000);limit("failureLimit",4);
        limit("batchRecheckMs",6L*60*60*1000);
        batch(false);
        Method shared = transport.getDeclaredMethod("resetSharedState");
        shared.setAccessible(true);
        shared.invoke(null);
        if (!workersIdleWithin(20_000)) throw new AssertionError("previous scenario left workers running");
    }
    static void send(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception clientGone) { /* The client closed the connection. */ }
    }
    static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
    static String query(String raw, String name) throws Exception {
        if (raw != null) for (String pair : raw.split("&")) {
            String[] kv = pair.split("=",2);
            if (name.equals(kv[0])) return URLDecoder.decode(kv.length > 1 ? kv[1] : "", "UTF-8");
        }
        return null;
    }
    static Thread background(Runnable task, List<Throwable> errors) {
        Thread thread = new Thread(() -> {
            try { task.run(); } catch (Throwable error) { errors.add(error); }
        });
        thread.start();
        return thread;
    }

    public static void main(String[] args) throws Exception {
        transport = Class.forName("app.revanced.extension.syncforreddit.CommentTransport");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/comments/", exchange -> {
            int running = active.incrementAndGet();peak.accumulateAndGet(running,Math::max);calls.incrementAndGet();
            try {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                String path = exchange.getRequestURI().getPath();
                String id = path.substring(path.lastIndexOf('/')+1);
                boolean fallback = path.endsWith(".json");
                if (fallback) id = query(exchange.getRequestURI().getRawQuery(),"comment");
                String key = (fallback?"fallback:":"")+id;
                perId.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                if (rateRemaining != null) {
                    exchange.getResponseHeaders().add("X-Ratelimit-Remaining",rateRemaining);
                    exchange.getResponseHeaders().add("X-Ratelimit-Reset",rateReset);
                    exchange.getResponseHeaders().set("Date",java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)));
                }
                if (requiredAuth != null && !requiredAuth.equals(auth)) { send(exchange,401,"{}");return; }
                AtomicInteger throttle = throttled.get(key);
                if (throttle != null && throttle.getAndDecrement() > 0) {
                    exchange.getResponseHeaders().add("Retry-After",retryAfter);
                    send(exchange,429,"{}");
                    return;
                }
                sleep(delays.getOrDefault(key,5L));
                send(exchange,statuses.getOrDefault(key,200),bodies.getOrDefault(key,thread(comment(id,"t3_post123"))));
            } catch (Exception ex) {
                send(exchange,500,"{}");
            } finally { exchange.close();active.decrementAndGet(); }
        });
        server.createContext("/api/morechildren", exchange -> {
            int running = batchActive.incrementAndGet();batchPeak.accumulateAndGet(running,Math::max);batchCalls.incrementAndGet();
            if (!batchOn()) batchCallsWhileOff.incrementAndGet();
            try {
                String raw = exchange.getRequestURI().getRawQuery();
                lastBatchQuery = URLDecoder.decode(raw,"UTF-8");
                sleep(40);
                if (batchStatus != 200) { send(exchange,batchStatus,"{}");return; }
                String body = batchBody;
                if (body == null) {
                    JSONArray found = new JSONArray();
                    for (String id : query(raw,"children").split(",")) if (!batchOmit.contains(id)) found.put(comment(id,"t3_post123"));
                    body = new JSONObject().put("json",new JSONObject().put("errors",new JSONArray())
                            .put("data",new JSONObject().put("things",found))).toString();
                }
                send(exchange,200,body);
            } catch (Exception ex) {
                send(exchange,500,"{}");
            } finally { exchange.close();batchActive.decrementAndGet(); }
        });
        server.start();
        base = "http://127.0.0.1:"+server.getAddress().getPort()+"/api/morechildren?always_show_media=1&profile_img=true";
        Map<Integer,Integer> requestCounts = new TreeMap<>();
        try {
            // Request counts with the batch attempt off (the default since issue #51). The loopback
            // batch endpoint would answer HTTP 200, so any request to it would change these counts.
            check(!batchOn(),"the /api/morechildren attempt is off by default");
            reset();
            batchStatus = 200;
            int before = calls.get(), batchBefore = batchCalls.get();
            List<String> one = range("one",1);
            JSONArray all = load(one);
            check(ids(all,"t1").equals(one) && ids(all,"more").isEmpty(),"1 ID loads from Sync's own response");
            check(calls.get()==before,"1 ID makes no extra request");
            requestCounts.put(1,calls.get()-before);

            List<String> twenty = range("tw",20);
            before = calls.get();
            all = load(twenty);
            check(ids(all,"t1").equals(twenty) && ids(all,"more").isEmpty(),"20 IDs all load in requested order");
            check(calls.get()-before==19,"20 IDs: one comment request per extra ID");
            requestCounts.put(20,calls.get()-before);

            List<String> hundred = range("h",100);
            before = calls.get();
            all = load(hundred);
            check(calls.get()-before==32,"100 IDs: requests stop at the 32-request budget");
            check(ids(all,"t1").equals(hundred.subList(0,33)),"budgeted IDs load in order");
            check(ids(all,"more").equals(hundred.subList(33,100)),"unrequested IDs stay pending in order");
            check(peak.get()<=4,"no more than four requests in flight");
            requestCounts.put(100,calls.get()-before);

            List<String> many = range("m",150);
            before = calls.get();
            all = load(many);
            check(calls.get()-before==32,"150 IDs: same 32-request budget");
            check(ids(all,"t1").equals(many.subList(0,33)) && ids(all,"more").equals(many.subList(33,150)),
                    "IDs beyond Reddit's 100-ID batch stay pending after the attempted ones");
            check(complete(all,many),"150 IDs: none dropped or duplicated");
            requestCounts.put(150,calls.get()-before);
            List<String> next = ids(all,"more");
            all = load(next);
            check(ids(all,"t1").equals(many.subList(33,66)) && complete(all,next),"next tap continues where the budget stopped");
            check(batchCalls.get()==batchBefore,"1, 20, 100 and 150 IDs send no /api/morechildren request");
            batchStatus = 403;
            for (String id : Arrays.asList("fallback:off0","off1","off2")) statuses.put(id,503);
            String[] off = failure(range("off",3),plain,null);
            check(off[1].contains(" batch=disabled"),"diagnostic reports the batch attempt as disabled");

            // The retained batch attempt, turned on by reflection: a refused endpoint (HTTP 403,
            // as Reddit has answered for Sync) is probed once, cached, and every ID still loads.
            reset();
            batch(true);
            before = calls.get();batchBefore = batchCalls.get();
            all = load(range("on",20));
            check(ids(all,"t1").equals(range("on",20)) && ids(all,"more").isEmpty(),"refused batch falls back for every ID");
            check(calls.get()-before==19 && batchCalls.get()-batchBefore==1,"batch endpoint probed once before the per-comment route");
            before = calls.get();batchBefore = batchCalls.get();
            all = load(range("oh",100));
            check(batchCalls.get()==batchBefore,"refused batch endpoint is not probed again");
            check(calls.get()-before==32 && ids(all,"t1").equals(range("oh",100).subList(0,33)),"budget unchanged after a refused batch");
            for (String id : Arrays.asList("fallback:om0","om1","om2")) statuses.put(id,503);
            off = failure(range("om",3),plain,null);
            check(off[1].contains(" batch=unavailable"),"diagnostic reports the cached refusal with the attempt on");

            // Duplicate IDs and IDs already inside a returned tree need no request.
            before = calls.get();
            JSONObject tree = comment("p0","t3_post123",comment("p1","t1_p0",comment("p2","t1_p1")),comment("p3","t1_p0"));
            all = things(raw(thread(tree),"p0,t1_p0,p1,p2,p3,p4,p4,t1_p4",plain,null,Collections.<String,String>emptyMap()));
            check(ids(all,"t1").equals(Arrays.asList("p0","p1","p2","p3","p4")) && ids(all,"more").isEmpty(),
                    "duplicates and first-tree replies load once");
            check(calls.get()-before==1,"only the ID outside the first tree is requested");

            reset();
            bodies.put("s1",thread(comment("s1","t3_post123",comment("s5","t1_s1"),comment("s6","t1_s1"))));
            for (String slow : Arrays.asList("s2","s3","s4")) delays.put(slow,300L);
            before = calls.get();
            all = load(range("s",7));
            check(calls.get()-before==4,"IDs inside an earlier sibling's returned tree are not requested");
            check(ids(all,"t1").equals(Arrays.asList("s0","s1","s5","s6","s2","s3","s4")) && ids(all,"more").isEmpty(),
                    "reused subtree keeps requested order and parent-first nesting");

            // Two taps at once share four workers.
            reset();
            for (String id : range("x",12)) delays.put(id,40L);
            for (String id : range("y",12)) delays.put(id,40L);
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            JSONArray[] pair = new JSONArray[2];
            Thread first = background(() -> pair[0] = load(range("x",12)), errors);
            Thread second = background(() -> pair[1] = load(range("y",12)), errors);
            first.join();second.join();
            check(errors.isEmpty(),"concurrent taps succeed");
            check(ids(pair[0],"t1").equals(range("x",12)) && ids(pair[1],"t1").equals(range("y",12)),"concurrent taps both load fully");
            check(peak.get()>1 && peak.get()<=4,"concurrent taps share the four-request limit");

            // Slow responses: an overall deadline, after which in-flight sockets are closed.
            reset();
            limit("deadlineMs",700);
            for (String slow : Arrays.asList("d2","d3","d4","d5")) delays.put(slow,5_000L);
            long start = System.currentTimeMillis();
            all = load(range("d",6));
            long elapsed = System.currentTimeMillis()-start;
            check(elapsed<2_500,"slow requests end at the deadline, not the 15 s socket timeout ("+elapsed+" ms)");
            check(ids(all,"t1").equals(Arrays.asList("d0","d1")) && complete(all,range("d",6)),"slow IDs stay pending for retry");
            check(workersIdleWithin(2_000),"timed-out requests are closed, freeing the workers");
            reset();
            limit("deadlineMs",700);
            delays.put("fallback:e0",5_000L);
            String[] failed = failure(range("e",3),plain,null);
            check("timed out".equals(failed[0]),"nothing loaded before the deadline gives a short timeout reason");
            check(failed[1].contains("stop=deadline"),"diagnostic records the deadline");
            check(workersIdleWithin(2_000),"timed-out fallback is closed");

            // Canceling the originating request.
            reset();
            for (String slow : range("k",6).subList(1,6)) delays.put(slow,5_000L);
            SyncRequest canceled = new SyncRequest(plain);
            before = calls.get();
            new Timer(true).schedule(new TimerTask() { public void run() { canceled.cancel(); } },300);
            start = System.currentTimeMillis();
            all = load(range("k",6),plain,canceled);
            elapsed = System.currentTimeMillis()-start;
            check(elapsed<1_500,"in-flight work stops soon after Sync cancels the request ("+elapsed+" ms)");
            check(calls.get()-before<=4,"no request starts after cancellation");
            check(ids(all,"t1").equals(Collections.singletonList("k0")) && complete(all,range("k",6)),"canceled IDs remain pending");
            check(workersIdleWithin(2_000),"canceled in-flight requests are closed");

            reset();
            SyncRequest early = new SyncRequest(plain);
            early.cancel();
            before = calls.get();batchBefore = batchCalls.get();
            all = load(range("g",10),plain,early);
            check(calls.get()==before && batchCalls.get()==batchBefore,"already canceled request starts no extra request");
            check(complete(all,range("g",10)),"already canceled request keeps every ID");

            reset();
            for (String slow : range("qa",5).subList(1,5)) delays.put(slow,1_500L);
            SyncRequest queued = new SyncRequest(plain);
            List<String> busyIds = range("qa",5), queuedIds = range("qb",5);
            JSONArray[] busyResult = new JSONArray[1], queuedResult = new JSONArray[1];
            errors.clear();
            Thread busy = background(() -> busyResult[0] = load(busyIds), errors);
            sleep(200);
            long[] queuedElapsed = new long[1];
            Thread waiting = background(() -> {
                long begin = System.currentTimeMillis();
                queuedResult[0] = load(queuedIds,plain,queued);
                queuedElapsed[0] = System.currentTimeMillis()-begin;
            }, errors);
            sleep(200);
            queued.cancel();
            waiting.join();busy.join();
            check(errors.isEmpty(),"queued and busy taps return normally");
            check(queuedElapsed[0]<1_200,"queued tap returns soon after cancellation ("+queuedElapsed[0]+" ms)");
            boolean untouched = true;
            for (String id : queuedIds.subList(1,5)) untouched &= perId(id)==0;
            check(untouched,"queued work canceled before reaching the network");
            check(complete(queuedResult[0],queuedIds),"canceled queued IDs remain pending");
            check(ids(busyResult[0],"t1").equals(busyIds),"other tap unaffected by the cancellation");

            // HTTP 429: wait for Retry-After and retry once.
            reset();
            throttled.put("r2",new AtomicInteger(1));
            start = System.currentTimeMillis();
            all = load(range("r",5));
            elapsed = System.currentTimeMillis()-start;
            check(ids(all,"t1").equals(range("r",5)),"throttled ID loads after Retry-After");
            check(perId("r2")==2 && elapsed>=900,"one retry after the requested wait");

            reset();
            retryAfter = "30";
            throttled.put("u1",new AtomicInteger(1));
            for (String id : Arrays.asList("u2","u3","u4")) delays.put(id,150L);
            start = System.currentTimeMillis();
            all = load(range("u",6));
            check(System.currentTimeMillis()-start<3_000,"a wait beyond the deadline is not slept through");
            check(perId("u1")==1 && perId("u5")==0,"no request starts during a long backoff");
            check(ids(all,"more").contains("u1") && complete(all,range("u",6)),"backed-off IDs stay pending");
            before = calls.get();
            all = load(range("v",4));
            check(calls.get()==before && ids(all,"t1").equals(Collections.singletonList("v0")) && complete(all,range("v",4)),
                    "the backoff also applies to the next tap");
            failed = failure(range("w",3),plain,null);
            check("rate limited, try again shortly".equals(failed[0]) && failed[1].contains("stop=rate_limited"),
                    "nothing loaded during backoff gives a short rate-limit reason");

            // Reddit's rate headers, from added requests and from Sync's own response.
            reset();
            rateRemaining = "12";rateReset = "300";
            before = calls.get();
            all = load(range("rl",20));
            check(calls.get()-before<=6,"requests stop at Reddit's remaining-quota reserve ("+(calls.get()-before)+")");
            check(complete(all,range("rl",20)),"quota-limited IDs stay pending");
            reset();
            Map<String,String> lowQuota = new HashMap<>();
            lowQuota.put("x-ratelimit-remaining","4.0");lowQuota.put("x-ratelimit-reset","120");
            before = calls.get();
            all = things(raw(thread(comment("z0","t3_post123")),"z0,z1,z2,z3",plain,null,lowQuota));
            check(calls.get()==before && ids(all,"t1").equals(Collections.singletonList("z0")) && complete(all,range("z",4)),
                    "Sync's own response headers seed the shared quota");

            // HTTP 401: one refresh through Sync's request, shared by later taps.
            reset();
            requiredAuth = fresh.get("Authorization");
            SyncRequest refreshing = new SyncRequest(fresh);
            all = load(range("t",10),stale,refreshing);
            check(ids(all,"t1").equals(range("t",10)),"rejected token refreshed and every ID loaded");
            check(refreshing.headerReads.get()==1,"Sync's headers read once per rejected token");
            SyncRequest later = new SyncRequest(fresh);
            all = load(range("tt",6),stale,later);
            check(ids(all,"t1").equals(range("tt",6)) && later.headerReads.get()==0,"later tap reuses the shared refresh");

            reset();
            requiredAuth = fresh.get("Authorization");
            SyncRequest unchanged = new SyncRequest(stale);
            before = calls.get();
            all = load(range("n",10),stale,unchanged);
            check(calls.get()-before<=4,"unrefreshable token stops after the requests already in flight");
            check(unchanged.headerReads.get()==1,"refresh attempted only once");
            check(ids(all,"t1").equals(Collections.singletonList("n0")) && complete(all,range("n",10)),"rejected IDs stay pending");
            reset();
            requiredAuth = fresh.get("Authorization");
            failed = failure(range("na",3),stale,new SyncRequest(stale));
            check("sign-in rejected".equals(failed[0]) && failed[1].contains("stop=auth") && failed[1].contains("401=1"),
                    "nothing loaded with a rejected token gives a short sign-in reason");
            check(!failed[1].contains("TOKEN") && !failed[1].contains("Bearer") && !failed[1].contains("post123") && !failed[1].contains("na0"),
                    "transport diagnostic has no tokens, headers or IDs");

            // HTTP 403 on every request: stop after a few instead of spending the budget.
            reset();
            for (String id : range("f",20).subList(1,20)) statuses.put(id,403);
            before = calls.get();
            all = load(range("f",20));
            check(calls.get()-before<=7,"repeated 403s stop early ("+(calls.get()-before)+" requests)");
            check(ids(all,"t1").equals(Collections.singletonList("f0")) && complete(all,range("f",20)),"forbidden IDs stay pending");
            reset();
            statuses.put("fallback:fa0",403);
            for (String id : range("fa",8).subList(1,8)) statuses.put(id,403);
            failed = failure(range("fa",8),plain,null);
            check("requests failed".equals(failed[0]) && failed[1].contains("stop=failing"),"nothing loaded after repeated failures gives a short reason");

            // Sync's own OAuthRateHelper (d9.d): extra requests report to it and respect it.
            reset();
            rateRemaining = "500";rateReset = "300";
            all = load(range("sr",5));
            check(ids(all,"t1").equals(range("sr",5)),"loads normally while reporting to Sync's rate helper");
            boolean reported = d9.d.recorded.size()==4;
            for (Map<String,String> seen : d9.d.recorded) {
                reported &= "500".equals(seen.get("x-ratelimit-remaining")) && "300".equals(seen.get("x-ratelimit-reset"))
                        && seen.get("date")!=null && seen.get("url").contains("/comments/post123/comment/sr");
            }
            check(reported,"each extra response reaches Sync's helper with the lower-case headers it reads");
            reset();
            load(range("sn",4));
            check(d9.d.recorded.isEmpty(),"responses without rate headers are not reported");

            reset();
            d9.d.remaining = 4;d9.d.resetAt = System.currentTimeMillis()+120_000;
            before = calls.get();
            all = load(range("sw",5));
            check(calls.get()==before && ids(all,"t1").equals(Collections.singletonList("sw0")) && complete(all,range("sw",5)),
                    "Sync's stored window at the reserve starts no extra request");
            failed = failure(range("sx",3),plain,null);
            check("rate limited, try again shortly".equals(failed[0]) && failed[1].contains("stop=rate_limited"),
                    "nothing loaded under Sync's exhausted window gives the rate-limit reason");
            reset();
            d9.d.remaining = 0;d9.d.resetAt = System.currentTimeMillis()-1_000;
            all = load(range("sp",5));
            check(ids(all,"t1").equals(range("sp",5)),"an expired stored window does not block requests");
            reset();
            d9.d.broken = true;rateRemaining = "500";rateReset = "300";
            all = load(range("sb",5));
            check(ids(all,"t1").equals(range("sb",5)),"an unavailable helper never fails a load");

            // Reddit's batch endpoint, when the configured client may use it (attempt turned on).
            reset();
            batch(true);
            batchStatus = 200;
            List<String> batched = range("b",30);
            before = calls.get();batchBefore = batchCalls.get();
            all = load(batched);
            check(batchCalls.get()-batchBefore==1 && calls.get()==before,"available batch endpoint loads 29 IDs in one request");
            check(ids(all,"t1").equals(batched) && ids(all,"more").isEmpty(),"batch results keep requested order");
            check(lastBatchQuery.contains("api_type=json") && lastBatchQuery.contains("link_id=t3_post123")
                    && lastBatchQuery.contains("sort=confidence") && lastBatchQuery.contains("children=b1,b2,"),
                    "batch request carries the post, sort and remaining IDs");
            check(!lastBatchQuery.contains("children=b0"),"batch skips the ID Sync already loaded");
            batchOmit.add("o3");
            before = calls.get();
            all = load(range("o",10));
            check(calls.get()-before==1 && perId("o3")==1,"an ID missing from the batch is requested on its own");
            check(complete(all,range("o",10)) && ids(all,"more").isEmpty(),"batch plus fallback loads every ID");
            errors.clear();
            Thread batchA = background(() -> load(range("ba",8)), errors);
            Thread batchB = background(() -> load(range("bb",8)), errors);
            batchA.join();batchB.join();
            check(errors.isEmpty() && batchPeak.get()==1,"batch requests are serial across taps");

            reset();
            batch(true);
            batchStatus = 200;
            batchBody = "{\"json\":{\"errors\":[[\"RATELIMIT\",\"busy\",\"ratelimit\"]]}}";
            before = calls.get();batchBefore = batchCalls.get();
            all = load(range("i",5));
            check(ids(all,"t1").equals(range("i",5)) && calls.get()-before==4,"batch error envelope falls back per comment");
            before = calls.get();batchBefore = batchCalls.get();
            load(range("j",5));
            check(batchCalls.get()==batchBefore && calls.get()-before==4,"unusable batch result is cached");

            reset();
            check(batchCallsWhileOff.get()==0,"no scenario with the batch attempt off sent /api/morechildren");
            System.out.println("PASS: "+checks+" checks; extra requests by requested IDs="+requestCounts
                    +" with no /api/morechildren request (subtree and duplicate IDs reuse loaded trees)");
        } finally {
            server.stop(0);serverThreads.shutdownNow();executor().shutdownNow();
        }
    }
}
