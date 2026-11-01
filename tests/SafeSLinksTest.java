package app.revanced.extension.synccompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records every request/header without DNS, a Reddit account, or a real access token. */
public final class SafeSLinksTest {
    private static final String START = "https://www.reddit.com/r/test/s/AbCd12";
    private static final String POST = "https://www.reddit.com/r/test/comments/abc123/a_title/";
    private static final String TOKEN = "TEST_ONLY_ACCOUNT_A";
    private static int checks;

    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }

    private interface Attempt { void run() throws IOException; }

    private static void rejected(Attempt attempt) throws Exception {
        try {
            attempt.run();
            throw new AssertionError("Expected safe rejection");
        } catch (IOException expected) {
            check("Reddit share link could not be resolved safely".equals(expected.getMessage()),
                    "fixed diagnostic contains no input or credentials");
            check(expected.getCause() == null, "diagnostic has no sensitive transport cause");
        }
    }

    private static final class Response {
        final int status;
        final String location;
        Response(int status, String location) { this.status = status; this.location = location; }
    }

    private static final class Transport implements SafeSLinks.ConnectionFactory {
        final Map<String, Response> routes = new HashMap<>();
        final List<Connection> requests = new ArrayList<>();
        boolean timeout;
        boolean failOpen;
        boolean failHeader;

        Transport route(String url, int status, String location) {
            routes.put(url, new Response(status, location));
            return this;
        }

        @Override public HttpURLConnection open(URL url) throws IOException {
            if (failOpen) throw new IOException("PRIVATE_TRANSPORT_URL_AND_TOKEN");
            Response response = routes.get(url.toExternalForm());
            if (response == null) throw new AssertionError("Unexpected destination was requested");
            Connection connection = new Connection(url, response, this);
            requests.add(connection);
            return connection;
        }
    }

    private static final class Connection extends HttpURLConnection {
        final Response response;
        final Transport transport;
        boolean disconnected;
        int sends;

        Connection(URL url, Response response, Transport transport) {
            super(url);
            this.response = response;
            this.transport = transport;
        }

        @Override public void setRequestProperty(String name, String value) {
            if (transport.failHeader) throw new IllegalArgumentException("PRIVATE_HEADER_AND_TOKEN");
            super.setRequestProperty(name, value);
        }
        @Override public void connect() { connected = true; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() throws IOException {
            sends++;
            if (transport.timeout) throw new SocketTimeoutException("PRIVATE_TIMEOUT_URL");
            return response.status;
        }
        @Override public String getHeaderField(String name) {
            if (!"Location".equalsIgnoreCase(name)) throw new AssertionError("Unexpected header read");
            return response.location;
        }
    }

    private static void configured(Connection request, String token) {
        String authorization = token == null ? null : "Bearer " + token;
        check("HEAD".equals(request.getRequestMethod()), "share requests use HEAD");
        check(!request.getInstanceFollowRedirects(), "automatic redirects disabled");
        check(request.getConnectTimeout() > 0 && request.getConnectTimeout() <= 4000
                && request.getReadTimeout() == request.getConnectTimeout(), "bounded socket timeouts");
        check(authorization == null
                ? request.getRequestProperty("Authorization") == null
                : authorization.equals(request.getRequestProperty("Authorization")), "authorization scope");
        check(request.disconnected, "connection released");
        check(request.sends == 1, "one HEAD per connection");
        check(request.getURL().getRef() == null, "fragment never sent in request URL");
    }

    public static void main(String[] args) throws Exception {
        String[] invalid = {
            null, "", "not a URL", "/r/test/s/AbCd12", "//www.reddit.com/r/test/s/AbCd12",
            "http://www.reddit.com/r/test/s/AbCd12", "ftp://reddit.com/r/test/s/AbCd12",
            "https://example.invalid/?next=reddit.com/r/test/s/AbCd12",
            "https://example.invalid/reddit.com/r/test/s/AbCd12",
            "https://notreddit.com/r/test/s/AbCd12", "https://reddit.com.evil.invalid/r/test/s/AbCd12",
            "https://reddit.com@evil.invalid/r/test/s/AbCd12", "https://user@www.reddit.com/r/test/s/AbCd12",
            "https://user:password@reddit.com/r/test/s/AbCd12", "https://www.reddit.com:444/r/test/s/AbCd12",
            "https://www.reddit.com:80/r/test/s/AbCd12", "https://www.reddit.com:/r/test/s/AbCd12",
            "https://www.reddit.com:0443/r/test/s/AbCd12", "https://www.reddit.com./r/test/s/AbCd12",
            "https://%72eddit.com/r/test/s/AbCd12", "https://www.redd\u0131t.com/r/test/s/AbCd12",
            "https://www.reddit.com\\@evil.invalid/r/test/s/AbCd12",
            "https://oauth.reddit.com/r/test/s/AbCd12", "https://i.redd.it/r/test/s/AbCd12",
            "https://www.reddit.com/?next=reddit.com/r/test/s/AbCd12",
            "https://www.reddit.com/redirect/reddit.com/r/test/s/AbCd12",
            "https://www.reddit.com/r/test/s/", "https://www.reddit.com/r/test/s/AbCd12/extra",
            "https://www.reddit.com/r/test%2fevil/s/AbCd12", "https://www.reddit.com/r/test/s/AbCd12%2fextra",
            "https://www.reddit.com/r/test/s/AbCd12//", "https://www.reddit.com/r/test/s/../AbCd12",
            "https://www.reddit.com/r/test/s/AbCd12\r\nPRIVATE_ACCOUNT_DATA",
            "https://www.reddit.com/u/s/AbCd12", "https://www.reddit.com/user//s/AbCd12",
            "https://www.reddit.com/u/te.st/s/AbCd12", "https://www.reddit.com/users/test/s/AbCd12",
            "https://www.reddit.com/m/test/s/AbCd12", "https://www.reddit.com/u/test/comments/s/AbCd12",
            "http://www.reddit.com/u/test/s/AbCd12", "https://evil.invalid/u/test/s/AbCd12"
        };
        for (String value : invalid) {
            Transport transport = new Transport();
            check(!SafeSLinks.isShareLink(value), "untrusted input rejected by classifier");
            rejected(() -> SafeSLinks.openConnection(value, TOKEN, transport));
            rejected(() -> SafeSLinks.resolve(value, TOKEN, transport));
            check(transport.requests.isEmpty(), "untrusted input never reaches transport");
            check(SafeSLinks.resolveOrBypass(value, TOKEN) == null, "untrusted input has no resolver fallback");
        }

        String[] valid = {
            START, START + "/", START + "?utm_source=share", START + "/?context=3#comment",
            "HTTPS://WWW.REDDIT.COM/r/test/s/AbCd12", "https://www.reddit.com:443/r/test/s/AbCd12",
            "https://reddit.com/r/test/s/AbCd12", "https://old.reddit.com/r/test/s/AbCd12",
            "https://new.reddit.com/r/test/s/AbCd12", "https://sh.reddit.com/r/test/s/AbCd12",
            "https://m.reddit.com/r/test/s/AbCd12", "https://np.reddit.com/r/test/s/AbCd12",
            "https://www.reddit.com/u/test-user_1/s/AbCd12", "https://www.reddit.com/user/test/s/AbCd12/"
        };
        for (String value : valid) {
            String requested = value.split("#", 2)[0];
            Transport transport = new Transport().route(new URL(requested).toExternalForm(), 302, POST);
            check(SafeSLinks.isShareLink(value), "valid share input accepted");
            check(POST.equals(SafeSLinks.resolve(value, TOKEN, transport)), "valid share resolves to intended post");
            check(transport.requests.size() == 1, "terminal post not requested by resolver");
            configured(transport.requests.get(0), TOKEN);
        }

        for (int status : new int[] {301, 302, 303, 307, 308}) {
            Transport transport = new Transport().route(START, status, "/r/test/comments/abc123/a_title/?context=3#comment");
            check((POST + "?context=3#comment").equals(SafeSLinks.resolve(START, TOKEN, transport)), "relative redirect resolves with context");
            configured(transport.requests.get(0), TOKEN);
        }

        String[] badLocations = {
            "https://evil.invalid/r/test/s/Next1", "//evil.invalid/r/test/s/Next1",
            "http://www.reddit.com/r/test/s/Next1", "https://www.reddit.com:444/r/test/s/Next1",
            "https://user@www.reddit.com/r/test/comments/abc123", "https://reddit.com.evil.invalid/comments/abc123",
            "javascript:PRIVATE_TOKEN", "data:text/plain,PRIVATE_ACCOUNT", "file:///PRIVATE_ACCOUNT",
            "https://%72eddit.com/comments/abc123", "https://www.reddit.com\\@evil.invalid/",
            "https://www.reddit.com/r/test/s/Next1\r\nPRIVATE_LOCATION", "", null
        };
        for (String location : badLocations) {
            Transport transport = new Transport().route(START, 302, location);
            rejected(() -> SafeSLinks.resolve(START, TOKEN, transport));
            check(transport.requests.size() == 1, "unsafe redirect not requested");
            configured(transport.requests.get(0), TOKEN);
        }

        String next = "https://www.reddit.com:443/r/test/s/Next1";
        Transport sameOrigin = new Transport().route(START, 307, next).route(next, 308, POST);
        check(POST.equals(SafeSLinks.resolve(START, TOKEN, sameOrigin)), "same-origin share chain resolves");
        for (Connection request : sameOrigin.requests) configured(request, TOKEN);

        String other = "https://old.reddit.com/r/test/s/Next1";
        String back = "https://www.reddit.com/r/test/s/Back1";
        Transport crossOrigin = new Transport().route(START, 302, other).route(other, 302, back).route(back, 302, POST);
        check(POST.equals(SafeSLinks.resolve(START, TOKEN, crossOrigin)), "allowed cross-origin chain resolves");
        configured(crossOrigin.requests.get(0), TOKEN);
        configured(crossOrigin.requests.get(1), null);
        configured(crossOrigin.requests.get(2), null);

        for (String token : new String[] {TOKEN, "TEST_ONLY_ACCOUNT_B", null, ""}) {
            Transport transport = new Transport().route(START, 302, POST);
            SafeSLinks.resolve(START, token, transport);
            configured(transport.requests.get(0), token == null || token.isEmpty() ? null : token);
        }

        Transport loop = new Transport().route(START, 302, START);
        rejected(() -> SafeSLinks.resolve(START, TOKEN, loop));
        check(loop.requests.size() == 1, "redirect cycle detected");
        configured(loop.requests.get(0), TOKEN);
        Transport longChain = new Transport();
        for (int hop = 0; hop < 6; hop++) {
            longChain.route(hop == 0 ? START : "https://www.reddit.com/r/test/s/Next" + hop, 302,
                    "https://www.reddit.com/r/test/s/Next" + (hop + 1));
        }
        rejected(() -> SafeSLinks.resolve(START, TOKEN, longChain));
        check(longChain.requests.size() == 5, "redirect requests bounded");
        for (Connection request : longChain.requests) configured(request, TOKEN);

        for (int status : new int[] {200, 204, 300, 304, 400, 401, 403, 429, 500}) {
            Transport transport = new Transport().route(START, status, POST);
            rejected(() -> SafeSLinks.resolve(START, TOKEN, transport));
            configured(transport.requests.get(0), TOKEN);
        }
        Transport timeout = new Transport().route(START, 302, POST);
        timeout.timeout = true;
        rejected(() -> SafeSLinks.resolve(START, TOKEN, timeout));
        configured(timeout.requests.get(0), TOKEN);
        Transport failOpen = new Transport();
        failOpen.failOpen = true;
        rejected(() -> SafeSLinks.resolve(START, TOKEN, failOpen));
        Transport failHeader = new Transport().route(START, 302, POST);
        failHeader.failHeader = true;
        rejected(() -> SafeSLinks.resolve(START, TOKEN, failHeader));
        check(failHeader.requests.get(0).disconnected, "setup failure releases connection");

        // Each request gets at most the per-request timeout and never more than the chain deadline.
        java.lang.reflect.Field deadline = SafeSLinks.class.getDeclaredField("DEADLINE_MS");
        deadline.setAccessible(true);
        long defaultDeadline = deadline.getLong(null);
        check(defaultDeadline >= 4000 && defaultDeadline <= 10000, "chain deadline is bounded");
        Transport fresh = new Transport().route(START, 302, POST);
        SafeSLinks.resolve(START, TOKEN, fresh);
        check(fresh.requests.get(0).getConnectTimeout() == 4000, "first request has the full timeout");
        try {
            deadline.setLong(null, 1500);
            Transport clamped = new Transport().route(START, 302, POST);
            check(POST.equals(SafeSLinks.resolve(START, TOKEN, clamped)), "short deadline still resolves");
            int clampedTimeout = clamped.requests.get(0).getConnectTimeout();
            check(clampedTimeout > 0 && clampedTimeout <= 1500, "timeout clamped to remaining deadline");
            deadline.setLong(null, 0);
            Transport expired = new Transport().route(START, 302, POST);
            rejected(() -> SafeSLinks.resolve(START, TOKEN, expired));
            check(expired.requests.isEmpty(), "expired deadline sends no request");
        } finally {
            deadline.setLong(null, defaultDeadline);
        }

        Transport bypass = new Transport();
        check(SafeSLinks.isShareLink(START + "#bypass") && SafeSLinks.isBypass(START + "#bypass"), "browser bypass retained");
        rejected(() -> SafeSLinks.resolve(START + "#bypass", TOKEN, bypass));
        rejected(() -> SafeSLinks.openConnection(START + "#bypass", TOKEN, bypass));
        check(bypass.requests.isEmpty(), "bypass never starts authenticated request");
        check((START + "#bypass").equals(SafeSLinks.resolveOrBypass(START + "#bypass", TOKEN)), "bypass does not accumulate fragments");
        System.out.println("PASS: " + checks + " share-link security checks (stub transport; no live Reddit requests)");
    }
}
