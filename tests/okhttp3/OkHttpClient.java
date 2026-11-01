package okhttp3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test stand-in for OkHttp's client. It models the behavior the RedGifs interceptors rely on:
 * application interceptors run in the order added (first added is outermost), each may call
 * {@code proceed} more than once, and, as OkHttp 4/5 enforce, a call cannot start another network
 * exchange while an earlier response body on that call is still open. {@code newBuilder()} copies
 * the configuration and interceptor list. Connect/read timeouts, a whole-call timeout and redirect
 * following are honored.
 *
 * <p>The network is real HTTP to loopback servers: {@link #ROUTES} maps a request host (for example
 * {@code api.redgifs.com}) to a local {@code http://127.0.0.1:port}. An unmapped host fails with
 * UnknownHostException, so a test can never reach the internet.
 */
public class OkHttpClient {
    /** Request host to loopback origin, set by tests. */
    public static final Map<String, String> ROUTES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "okhttp-standin-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final List<Interceptor> interceptors;
    private final List<Interceptor> networkInterceptors;
    private final long connectTimeoutMs, readTimeoutMs, writeTimeoutMs, callTimeoutMs;
    private final boolean followRedirects, followSslRedirects;

    public OkHttpClient() { this(new Builder()); }

    private OkHttpClient(Builder builder) {
        interceptors = Collections.unmodifiableList(new ArrayList<>(builder.interceptors));
        networkInterceptors = Collections.unmodifiableList(new ArrayList<>(builder.networkInterceptors));
        connectTimeoutMs = builder.connectTimeoutMs;
        readTimeoutMs = builder.readTimeoutMs;
        writeTimeoutMs = builder.writeTimeoutMs;
        callTimeoutMs = builder.callTimeoutMs;
        followRedirects = builder.followRedirects;
        followSslRedirects = builder.followSslRedirects;
    }

    public Builder newBuilder() { return new Builder(this); }
    public Call newCall(Request request) { return new RealCall(this, request); }
    public final List<Interceptor> interceptors() { return interceptors; }
    public final List<Interceptor> networkInterceptors() { return networkInterceptors; }
    public final boolean followRedirects() { return followRedirects; }
    public final boolean followSslRedirects() { return followSslRedirects; }
    public final int connectTimeoutMillis() { return (int) connectTimeoutMs; }
    public final int readTimeoutMillis() { return (int) readTimeoutMs; }
    public final int callTimeoutMillis() { return (int) callTimeoutMs; }

    public static final class Builder {
        private final List<Interceptor> interceptors = new ArrayList<>();
        private final List<Interceptor> networkInterceptors = new ArrayList<>();
        private long connectTimeoutMs = 10_000, readTimeoutMs = 10_000, writeTimeoutMs = 10_000, callTimeoutMs;
        private boolean followRedirects = true, followSslRedirects = true;

        public Builder() { }

        Builder(OkHttpClient client) {
            interceptors.addAll(client.interceptors);
            networkInterceptors.addAll(client.networkInterceptors);
            connectTimeoutMs = client.connectTimeoutMs;
            readTimeoutMs = client.readTimeoutMs;
            writeTimeoutMs = client.writeTimeoutMs;
            callTimeoutMs = client.callTimeoutMs;
            followRedirects = client.followRedirects;
            followSslRedirects = client.followSslRedirects;
        }

        public List<Interceptor> interceptors() { return interceptors; }
        public List<Interceptor> networkInterceptors() { return networkInterceptors; }
        public Builder addInterceptor(Interceptor interceptor) { interceptors.add(interceptor); return this; }
        public Builder followRedirects(boolean follow) { followRedirects = follow; return this; }
        public Builder followSslRedirects(boolean follow) { followSslRedirects = follow; return this; }
        public Builder connectTimeout(long timeout, TimeUnit unit) { connectTimeoutMs = unit.toMillis(timeout); return this; }
        public Builder readTimeout(long timeout, TimeUnit unit) { readTimeoutMs = unit.toMillis(timeout); return this; }
        public Builder writeTimeout(long timeout, TimeUnit unit) { writeTimeoutMs = unit.toMillis(timeout); return this; }
        public Builder callTimeout(long timeout, TimeUnit unit) { callTimeoutMs = unit.toMillis(timeout); return this; }
        public OkHttpClient build() {
            if (!networkInterceptors.isEmpty()) throw new UnsupportedOperationException("network interceptors not modeled");
            return new OkHttpClient(this);
        }
    }

    private static final class RealCall implements Call {
        private final OkHttpClient client;
        private final Request request;
        private final AtomicBoolean executed = new AtomicBoolean();
        private volatile boolean canceled;
        private int openBodies;
        private long deadline;

        RealCall(OkHttpClient client, Request request) {
            this.client = client;
            this.request = request;
        }

        @Override public Request request() { return request; }
        @Override public void cancel() { canceled = true; }

        @Override public Response execute() throws IOException {
            if (!executed.compareAndSet(false, true)) throw new IllegalStateException("Already Executed");
            deadline = client.callTimeoutMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(client.callTimeoutMs) : 0;
            return new Chain(this, 0, request, client.connectTimeoutMs, client.readTimeoutMs).proceed(request);
        }

        synchronized void beginExchange() {
            if (openBodies > 0) {
                throw new IllegalStateException("cannot make a new request because the previous response is still open: "
                        + "please call response.close()");
            }
            openBodies++;
        }

        synchronized void bodyClosed() { openBodies--; }
    }

    private static final class Chain implements Interceptor.Chain {
        private final RealCall call;
        private final int index;
        private final Request request;
        private final long connectMs, readMs;

        Chain(RealCall call, int index, Request request, long connectMs, long readMs) {
            this.call = call;
            this.index = index;
            this.request = request;
            this.connectMs = connectMs;
            this.readMs = readMs;
        }

        @Override public Request request() { return request; }
        @Override public Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) {
            return new Chain(call, index, request, unit.toMillis(timeout), readMs);
        }
        @Override public Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) {
            return new Chain(call, index, request, connectMs, unit.toMillis(timeout));
        }
        @Override public Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) { return this; }

        @Override public Response proceed(Request next) throws IOException {
            if (call.canceled) throw new IOException("Canceled");
            List<Interceptor> interceptors = call.client.interceptors;
            if (index < interceptors.size()) {
                Response response = interceptors.get(index).intercept(new Chain(call, index + 1, next, connectMs, readMs));
                if (response == null) throw new NullPointerException("interceptor returned null");
                return response;
            }
            return network(next);
        }

        private Response network(Request first) throws IOException {
            Request current = first;
            for (int redirects = 0; ; redirects++) {
                Response response = exchange(current);
                String location = response.header("Location");
                int code = response.code();
                boolean redirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
                if (!redirect || location == null || !call.client.followRedirects || redirects >= 5) return response;
                Request target = current.newBuilder().url(URI.create(current.url().toString()).resolve(location).toString()).build();
                if (target.isHttps() != current.isHttps() && !call.client.followSslRedirects) return response;
                response.close();
                current = target;
            }
        }

        private Response exchange(Request request) throws IOException {
            call.beginExchange();
            boolean handedOut = false;
            try {
                String origin = ROUTES.get(request.url().host());
                if (origin == null) throw new UnknownHostException(request.url().host() + " is not routed in this test");
                String query = request.url().encodedQuery();
                URL url = new URL(origin + request.url().encodedPath() + (query == null ? "" : "?" + query));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, connectMs));
                connection.setReadTimeout((int) Math.min(Integer.MAX_VALUE, readMs));
                connection.setRequestMethod(request.method());
                for (String[] header : request.headerList()) connection.addRequestProperty(header[0], header[1]);
                AtomicBoolean timedOut = new AtomicBoolean();
                ScheduledFuture<?> watchdog = null;
                if (call.deadline != 0) {
                    long remaining = call.deadline - System.nanoTime();
                    if (remaining <= 0) throw new InterruptedIOException("timeout");
                    watchdog = WATCHDOG.schedule(() -> { timedOut.set(true); connection.disconnect(); },
                            remaining, TimeUnit.NANOSECONDS);
                }
                try {
                    RequestBody body = request.body();
                    if (body != null) {
                        connection.setDoOutput(true);
                        try (OutputStream out = connection.getOutputStream()) { out.write(body.bytes()); }
                    }
                    int code = connection.getResponseCode();
                    InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    if (stream != null) {
                        try (InputStream in = stream) { in.transferTo(data); }
                    }
                    if (timedOut.get()) throw new InterruptedIOException("timeout");
                    Response.Builder builder = new Response.Builder().request(request).code(code)
                            .message(connection.getResponseMessage() == null ? "" : connection.getResponseMessage());
                    for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                        if (header.getKey() == null) continue;
                        for (String value : header.getValue()) builder.header(header.getKey(), value);
                    }
                    ResponseBody responseBody = ResponseBody.of(data.toByteArray(), connection.getContentLengthLong());
                    responseBody.onClose(call::bodyClosed);
                    handedOut = true;
                    return builder.body(responseBody).build();
                } catch (IOException e) {
                    if (timedOut.get()) throw new InterruptedIOException("timeout");
                    throw e;
                } finally {
                    if (watchdog != null) watchdog.cancel(false);
                    connection.disconnect();
                }
            } finally {
                if (!handedOut) call.bodyClosed();
            }
        }
    }
}
