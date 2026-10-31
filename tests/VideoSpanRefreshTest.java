package app.revanced.extension.synccompat;

import java.lang.ref.WeakReference;

/** Reproduces Sync nb.c's loss of its weak TextView during Glide target replacement. */
public final class VideoSpanRefreshTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static final class View {
        int callbacks;
    }

    /**
     * Minimal target lifecycle, verified against the shipped 23033 nb.c: replacing a request
     * invokes b(null), which clears c; resource-ready can only invalidate via c.get().
     */
    public static final class Span {
        private WeakReference<View> c;
        boolean cached;
        boolean throwAfterClear;
        int loads;
        String resource;
        String pending;

        Span(View view) { c = view == null ? null : new WeakReference<>(view); }

        public void syncCompatLoad(String path) {
            loads++;
            if (c != null) c.clear();
            c = null; // Sync clears both the WeakReference's referent and the field itself.
            if (throwAfterClear) throw new IllegalStateException("fixture loader failure");
            pending = path;
            if (cached) resourceReady();
        }

        void resourceReady() {
            resource = pending;
            View view = c == null ? null : c.get();
            if (view != null) view.callbacks++;
        }
    }

    public static void main(String[] args) {
        CommentVideoTest.FakePlatform platform = new CommentVideoTest.FakePlatform();
        CommentVideo.platform = platform;

        // This is the rc.2 failure: the image is available, but no view can be notified.
        View original = new View();
        Span broken = new Span(original);
        broken.syncCompatLoad("poster.jpg");
        broken.resourceReady();
        check("poster.jpg".equals(broken.resource) && original.callbacks == 0,
                "unprotected target replacement loads a frame without repainting the comment");

        // Asynchronous decode: restore a fresh weak reference before resource-ready arrives.
        View visible = new View();
        Span asynchronous = new Span(visible);
        WeakReference<View> binding = asynchronous.c;
        CommentVideo.load(asynchronous, "poster.jpg");
        check(asynchronous.c != binding && binding.get() == null,
                "cleared original reference is replaced, not restored with a null referent");
        check(asynchronous.c.get() == visible && asynchronous.loads == 1, "same view remains bound");
        check(platform.invalidated.size() == 1 && platform.invalidated.get(0) == visible,
                "replacement schedules a repaint on the currently bound view");
        asynchronous.resourceReady();
        check(visible.callbacks == 1 && "poster.jpg".equals(asynchronous.resource),
                "late decode callback can repaint immediately without scroll or tap");

        // Cached decode can complete inside into(target), before the binding has been restored.
        View cachedView = new View();
        Span cached = new Span(cachedView);
        cached.cached = true;
        CommentVideo.load(cached, "cached.jpg");
        check("cached.jpg".equals(cached.resource), "cached frame completed synchronously");
        check(cached.c.get() == cachedView, "binding restored after synchronous completion");
        check(platform.invalidated.size() == 2 && platform.invalidated.get(1) == cachedView,
                "post-replacement repaint covers the synchronous callback race");

        CommentVideo.load(asynchronous, "second.jpg");
        asynchronous.resourceReady();
        check(asynchronous.c.get() == visible && visible.callbacks == 2, "successive image swaps keep their owner");

        View recycledRow = new View();
        asynchronous.c = new WeakReference<>(recycledRow);
        CommentVideo.load(asynchronous, "rebound.jpg");
        asynchronous.resourceReady();
        check(recycledRow.callbacks == 1 && visible.callbacks == 2, "rebound span repaints its current row only");
        check(platform.invalidated.get(platform.invalidated.size() - 1) == recycledRow,
                "old row is not retained or invalidated by a later replacement");

        Span failed = new Span(visible);
        failed.throwAfterClear = true;
        CommentVideo.load(failed, "failed.jpg");
        check(failed.c != null && failed.c.get() == visible, "throwing loader still restores its weak owner");

        int invalidations = platform.invalidated.size();
        Span detached = new Span(null);
        CommentVideo.load(detached, "detached.jpg");
        check(detached.loads == 1 && detached.c == null, "detached span still loads safely");
        Span collected = new Span(visible);
        collected.c.clear();
        WeakReference<View> cleared = collected.c;
        CommentVideo.load(collected, "collected.jpg");
        check(collected.c == cleared && collected.c.get() == null, "cleared weak reference stays weak");
        check(platform.invalidated.size() == invalidations, "no redraw for a missing or collected view");

        CommentVideoTest.FakeSpan noOwnerField = new CommentVideoTest.FakeSpan();
        CommentVideo.load(noOwnerField, "generic.jpg");
        check("generic.jpg".equals(noOwnerField.last()), "unknown target layout retains its existing load path");
        CommentVideo.load(new Object(), "unknown.jpg");
        CommentVideo.load(null, "null.jpg");
        check(platform.invalidated.size() == invalidations, "unknown targets never invalidate unrelated rows");
        System.out.println("VideoSpanRefreshTest passed " + checks + " checks");
    }
}
