package app.revanced.extension.syncforreddit;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP for "load more comments" expansions.
 * <p>
 * Every expansion gets a request budget and an overall deadline, and stops when Sync
 * cancels the originating request. All expansions share one small worker pool,
 * Reddit's rate-limit headers, 429 backoff and a single token refresh, so several
 * taps cannot multiply the load. Only counters leave this class; URLs, IDs, bodies
 * and headers are never recorded.
 */
final class CommentTransport {
    // Limits are non-final so the tests can shorten them.
    /** Extra HTTP requests one expansion may make, on top of Sync's own first request. */
    static int maxRequests = 32;
    static long deadlineMs = 20_000;
    static int timeoutMs = 15_000;
    /** Requests left in Reddit's window for Sync's own traffic. */
    static int rateReserve = 10;
    static long defaultBackoffMs = 5_000;
    static long maxBackoffMs = 60_000;
    /** Consecutive failed requests, with nothing loaded, before an expansion gives up. */
    static int failureLimit = 4;
    static long batchRecheckMs = 6 * 60 * 60 * 1000L;

    /** Shared by every expansion; queued work is bounded too. */
    static final int THREADS = 4;
    static final ThreadPoolExecutor executor = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4 * THREADS), runnable -> {
                Thread thread = new Thread(runnable, "SyncCompat comments");
                thread.setDaemon(true);
                return thread;
            });
    static { executor.allowCoreThreadTimeOut(true); }

    static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status) { super("HTTP " + status); this.status = status; }
    }

    /** No request was sent: the expansion stopped, ran out of budget or must wait too long. */
    static final class Deferred extends IOException {
        Deferred() { super("deferred"); }
    }

    // Shared Reddit rate state. Times come from a monotonic clock.
    private static final Object rateLock = new Object();
    private static long blockedUntil;
    private static double quota = -1;
    private static long quotaResetAt;

    private static final Object authLock = new Object();
    private static int staleAuth;
    private static Map<String, String> refreshedHeaders;

    private static final int BATCH_UNKNOWN = 0, BATCH_AVAILABLE = 1, BATCH_UNAVAILABLE = 2;
    private static volatile int batchState = BATCH_UNKNOWN;
    private static volatile long batchCheckedAt;
    /** Reddit asks clients to make one /api/morechildren request at a time. */
    private static final ReentrantLock batchLock = new ReentrantLock(true);

    static long now() { return System.nanoTime() / 1_000_000L; }

    static void resetSharedState() {
        synchronized (rateLock) { blockedUntil = 0; quota = -1; quotaResetAt = 0; }
        synchronized (authLock) { staleAuth = 0; refreshedHeaders = null; }
        batchState = BATCH_UNKNOWN;
        batchCheckedAt = 0;
    }

    static boolean batchMayWork() {
        return batchState != BATCH_UNAVAILABLE || now() - batchCheckedAt >= batchRecheckMs;
    }

    static boolean batchKnownAvailable() { return batchState == BATCH_AVAILABLE; }

    static void recordBatch(boolean available) {
        batchState = available ? BATCH_AVAILABLE : BATCH_UNAVAILABLE;
        batchCheckedAt = now();
    }

    static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    /** Reddit reports the requests left and the seconds until its window resets. */
    static void observe(String remaining, String reset) {
        if (remaining == null || reset == null) return;
        try {
            double left = Double.parseDouble(remaining.trim());
            long seconds = Math.max(0, Math.min(600, (long) Math.ceil(Double.parseDouble(reset.trim()))));
            long resetAt = now() + seconds * 1000;
            synchronized (rateLock) {
                // Responses arrive out of order; within one window keep the lowest count.
                if (quota >= 0 && Math.abs(resetAt - quotaResetAt) < 2000) left = Math.min(left, quota);
                quota = left;
                quotaResetAt = resetAt;
            }
        } catch (NumberFormatException ignored) { /* Unknown header format: keep the previous state. */ }
    }

    static void observe(Map<String, String> headers) {
        observe(header(headers, "X-Ratelimit-Remaining"), header(headers, "X-Ratelimit-Reset"));
    }

    static void backoff(String retryAfter) {
        long wait = defaultBackoffMs;
        if (retryAfter != null) {
            try { wait = (long) (Double.parseDouble(retryAfter.trim()) * 1000); }
            catch (NumberFormatException httpDate) { /* Use the default wait. */ }
        }
        wait = Math.max(1000, Math.min(maxBackoffMs, wait));
        synchronized (rateLock) { blockedUntil = Math.max(blockedUntil, now() + wait); }
    }

    /**
     * Sync's OAuthRateHelper (d9.d) keeps the rate window seen by all of Sync's own requests,
     * and Sync's Volley refuses to send once it is exhausted. Waits while that stored window is
     * at the reserve; reset times are wall-clock milliseconds.
     */
    static long syncWait() {
        try {
            long wallNow = System.currentTimeMillis();
            long reset = d9.d.c();
            if (reset > wallNow && d9.d.b() <= rateReserve) return Math.min(reset - wallNow, 600_000L);
        } catch (Throwable unavailable) { /* The helper only adds information; never fail a load on it. */ }
        return 0;
    }

    /** Hands a response's rate headers to Sync's helper, as Sync's Volley does for its own requests. */
    static void recordWithSync(HttpURLConnection connection, int status, String url) {
        try {
            Map<String, String> headers = new HashMap<>();
            // The helper looks these up with exact lower-case keys.
            for (String name : new String[]{"date", "x-ratelimit-reset", "x-ratelimit-remaining"}) {
                String value = connection.getHeaderField(name);
                if (value != null) headers.put(name, value);
            }
            if (headers.containsKey("x-ratelimit-remaining")) {
                d9.d.e(new NetworkResponse(status, new byte[0], headers, false, 0), url);
            }
        } catch (Throwable unavailable) { /* The helper only adds information; never fail a load on it. */ }
    }

    /** How long until a new request may start, reserving one request of quota when it can. */
    private static long takeTurn() {
        long sync = syncWait();
        synchronized (rateLock) {
            long current = now();
            long until = Math.max(blockedUntil, sync > 0 ? current + sync : 0);
            if (quota >= 0 && quotaResetAt > current && quota <= rateReserve) until = Math.max(until, quotaResetAt);
            if (until > current) return until - current;
            if (quota >= 0) quota--;
            return 0;
        }
    }

    private static int authKey(Map<String, String> headers) {
        String value = header(headers, "Authorization");
        return value == null ? 0 : value.hashCode();
    }

    /** One "load more" tap. */
    static final class Expansion {
        final Request<?> request;
        final long deadline;
        final int budget;
        final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> stop = new AtomicReference<>();
        private final Set<HttpURLConnection> open =
                Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
        private volatile Map<String, String> headers;
        private boolean refreshTried;

        Expansion(Request<?> request, Map<String, String> headers) {
            this.request = request;
            this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
            this.deadline = now() + deadlineMs;
            this.budget = Math.max(0, maxRequests);
        }

        private volatile boolean aborted;

        String stopReason() { return stop.get(); }

        /** No new requests start; requests already sent may finish. */
        void stop(String reason) { stop.compareAndSet(null, reason); }

        /** Canceled or past the deadline: in-flight requests are closed as well. */
        private void abort(String reason) {
            stop.set(reason);
            if (aborted) return;
            aborted = true;
            // Closing the socket is what unblocks a read in progress.
            for (HttpURLConnection connection : new ArrayList<>(open)) {
                try { connection.disconnect(); } catch (RuntimeException ignored) { }
            }
        }

        boolean aborted() {
            if (!aborted) {
                if (request != null && request.isCanceled()) abort("canceled");
                else if (now() >= deadline) abort("deadline");
            }
            return aborted;
        }

        /** False once canceled, past the deadline or stopped for another reason. */
        boolean active() { return !aborted() && stop.get() == null; }

        boolean canStart() { return active() && requests.get() < budget; }

        /** Claims one request of the budget for a later {@link #fetchReserved}. */
        boolean reserve() {
            if (!active()) return false;
            if (requests.incrementAndGet() > budget) {
                requests.decrementAndGet();
                stop("budget");
                return false;
            }
            return true;
        }

        void release() { requests.decrementAndGet(); }

        long remainingMs() { return Math.max(0, deadline - now()); }

        /** Serial /api/morechildren request, shared with every other expansion. */
        String fetchBatch(String url) throws IOException {
            boolean locked;
            try { locked = batchLock.tryLock(remainingMs(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Deferred(); }
            if (!locked) { active(); throw new Deferred(); }
            try { return fetch(url); } finally { batchLock.unlock(); }
        }

        /** Body of a 200 response. Deferred means no request was sent for this call. */
        String fetch(String url) throws IOException { return fetch(url, false); }

        /** Like {@link #fetch(String)}, using a request already claimed with {@link #reserve}. */
        String fetchReserved(String url) throws IOException { return fetch(url, true); }

        private String fetch(String url, boolean reserved) throws IOException {
            int lastStatus = 0;
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean claimed = reserved && attempt == 0;
                try {
                    awaitTurn(claimed);
                } catch (Deferred deferred) {
                    if (claimed) release();
                    if (attempt > 0) throw new HttpStatusException(lastStatus);
                    throw deferred;
                }
                Map<String, String> sent = headers;
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                open.add(connection);
                try {
                    // Aborted after this connection was created but before abort() could close it.
                    if (aborted()) throw new IOException("aborted");
                    int timeout = (int) Math.max(1, Math.min(timeoutMs, remainingMs()));
                    connection.setConnectTimeout(timeout);
                    connection.setReadTimeout(timeout);
                    for (Map.Entry<String, String> header : sent.entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                    int status = connection.getResponseCode();
                    observe(connection.getHeaderField("X-Ratelimit-Remaining"), connection.getHeaderField("X-Ratelimit-Reset"));
                    recordWithSync(connection, status, url);
                    lastStatus = status;
                    if (status == 429) {
                        backoff(connection.getHeaderField("Retry-After"));
                        continue;
                    }
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        if (refreshAuth(sent)) continue;
                        throw new HttpStatusException(status);
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw new HttpStatusException(status);
                    try (InputStream input = connection.getInputStream()) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (aborted()) throw new IOException("aborted");
                            output.write(buffer, 0, read);
                        }
                        return output.toString("UTF-8");
                    }
                } finally {
                    open.remove(connection);
                    connection.disconnect();
                }
            }
            throw new HttpStatusException(lastStatus);
        }

        private void awaitTurn(boolean claimed) throws Deferred {
            // A claimed request still starts after the budget closes, but not after
            // a rejected token, rate limiting or repeated failures.
            while (true) {
                String reason = stop.get();
                boolean halted = aborted() || (reason != null && !"budget".equals(reason) && !"busy".equals(reason));
                if (claimed ? halted : !active()) throw new Deferred();
                long wait = takeTurn();
                if (wait == 0) break;
                if (wait >= remainingMs()) { stop("rate_limited"); throw new Deferred(); }
                try { Thread.sleep(Math.min(wait, 100)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Deferred(); }
            }
            if (!claimed && !reserve()) throw new Deferred();
        }

        /**
         * After a 401, ask Sync once for its current headers. Sync refreshes its own token;
         * expansions holding the same rejected token reuse the first refresh.
         */
        private boolean refreshAuth(Map<String, String> rejected) {
            synchronized (authLock) {
                if (headers != rejected) return true;
                int key = authKey(rejected);
                Map<String, String> fresh = null;
                if (key != 0 && key == staleAuth && refreshedHeaders != null) {
                    fresh = refreshedHeaders;
                } else if (!refreshTried && request != null) {
                    refreshTried = true;
                    try { fresh = request.getHeaders(); } catch (Throwable unavailable) { fresh = null; }
                }
                if (fresh == null || authKey(fresh) == key) {
                    stop("auth");
                    return false;
                }
                staleAuth = key;
                refreshedHeaders = fresh;
                headers = fresh;
                return true;
            }
        }
    }

    private CommentTransport() { }
}
