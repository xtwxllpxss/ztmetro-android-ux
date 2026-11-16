package org.ztmetro.start;

import android.content.Context;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;

/**
 * The ztmetro turnstile page change: the old page's blocks turn away round
 * their left edge and fade, the new page's blocks arrive from behind the
 * screen and settle, both staggered.
 *
 * <p>Numbers are the C's, read from the compiled tokens:
 * <ul>
 *   <li>one block leaving: 128 ms ({@code core/zrnmetrocore.c:50}, UIXMobile
 *       TurnstileForwardOut 0.128 s);</li>
 *   <li>one block arriving: 180 ms ({@code core/zrnmetrocore.c:51});</li>
 *   <li>33 ms between neighbouring blocks ({@code core/zrnmetrocore.c:52});</li>
 *   <li>out turn 50 degrees, in turn 80 degrees
 *       ({@code core/zrnmetrocore.c:66-67});</li>
 *   <li>the exact curves of {@code draw_turnstile}
 *       ({@code core/zrnmetrocore.c:1424-1475}): out {@code e = t*t} with the
 *       alpha held to e=0.7 then faded over the last 0.3; in
 *       {@code e = 1-(1-t)^3} with alpha solid after e=0.3;</li>
 *   <li>the 9.8 % sideways sweep of a leaving tile
 *       ({@code ztmetro.c:228}, TILE_OUT_SWEEP_R).</li>
 * </ul>
 *
 * <p>The timeline is driven by {@link Choreographer} and a wall clock, as
 * ztmetro's {@code anims_step} is, so there is no per-frame allocation and a
 * new swipe can retarget the scene mid-flight: every block is a pure function
 * of the scene position, so reversing it retraces the same curve.
 */
final class Turnstile {

    interface Collector {
        /** Add this page's animated blocks with their stagger index (0 = first). */
        void collect(View page, ArrayList<View> blocks, ArrayList<Integer> stagger);
    }

    interface Listener {
        void onTurnstileSettled(int shownPage);
    }

    /** {@code core/zrnmetrocore.c:66}, TurnstileForwardOut ends at 50 degrees. */
    private static final float TURN_OUT_DEG = 50f;
    /** {@code core/zrnmetrocore.c:67}. */
    private static final float TURN_IN_DEG = 80f;
    /** {@code ztmetro.c:228}, TILE_OUT_SWEEP_R: 9.8 % of the screen. */
    private static final float SWEEP = 0.098f;

    private final int outMs;
    private final int inMs;
    private final int staggerMs;
    private final float density;
    private final ArrayList<View> outList = new ArrayList<>();
    private final ArrayList<Integer> outStagger = new ArrayList<>();
    private final ArrayList<View> inList = new ArrayList<>();
    private final ArrayList<Integer> inStagger = new ArrayList<>();

    private View[] outViews = new View[0];
    private int[] outDelay = new int[0];
    private View[] inViews = new View[0];
    private int[] inDelay = new int[0];

    private View fromPage;
    private View toPage;
    private int fromPageId;
    private int toPageId;
    private int dir;
    private int totalMs;
    private int inStartMs;
    private int scaledTotalMs;
    private float pageWidth;

    private float p;
    private boolean running;
    private long runStartNanos;
    private int runDurationMs;
    private float runFromP;
    private float runToP;
    private Listener listener;

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!running) {
                        return;
                    }
                    long elapsed = (frameTimeNanos - runStartNanos) / 1000000L;
                    float f = runDurationMs <= 0
                            ? 1f
                            : Math.min(1f, (float) elapsed / runDurationMs);
                    p = runFromP + (runToP - runFromP) * f;
                    apply(p);
                    if (f < 1f) {
                        Choreographer.getInstance().postFrameCallback(this);
                    } else {
                        settle();
                    }
                }
            };

    Turnstile(Context context) {
        outMs = context.getResources().getInteger(R.integer.metro_motion_row_out_ms);
        inMs = context.getResources().getInteger(R.integer.metro_motion_row_in_ms);
        staggerMs = context.getResources().getInteger(R.integer.metro_motion_row_stagger_ms);
        density = context.getResources().getDisplayMetrics().density;
    }

    boolean isRunning() {
        return running;
    }

    int fromPage() {
        return fromPageId;
    }

    int toPage() {
        return toPageId;
    }

    /**
     * Start a page change. {@code forward} is +1 when the content travels
     * left (Start to app list) and -1 the other way.
     */
    void start(View from, int fromId, View to, int toId, int direction,
               Collector collector, Listener listener) {
        cancel();
        this.fromPage = from;
        this.toPage = to;
        this.fromPageId = fromId;
        this.toPageId = toId;
        this.dir = direction >= 0 ? 1 : -1;
        this.listener = listener;
        this.p = 0f;

        outList.clear();
        outStagger.clear();
        inList.clear();
        inStagger.clear();
        collector.collect(from, outList, outStagger);
        collector.collect(to, inList, inStagger);
        outViews = outList.toArray(new View[0]);
        inViews = inList.toArray(new View[0]);
        outDelay = toArray(outStagger);
        inDelay = toArray(inStagger);

        int outLast = lastStagger(outDelay);
        int inLast = lastStagger(inDelay);
        inStartMs = outMs + staggerMs * outLast;
        totalMs = inStartMs + inMs + staggerMs * inLast;
        scaledTotalMs = Motion.ms(from.getContext(), totalMs);
        pageWidth = from.getWidth() > 0
                ? from.getWidth()
                : from.getResources().getDisplayMetrics().widthPixels;

        from.setVisibility(View.VISIBLE);
        to.setVisibility(View.VISIBLE);
        prepare(outViews);
        prepare(inViews);
        apply(0f);
        running = true;
        if (scaledTotalMs <= 0) {
            p = 1f;
            apply(1f);
            settle();
            return;
        }
        beginRun(0f, 1f, scaledTotalMs);
    }

    /**
     * A new swipe during the change: keep the same pair of pages and animate
     * the scene to the other end from wherever it is now.
     */
    void retarget(int targetPage) {
        if (!running) {
            return;
        }
        float target = targetPage == toPageId ? 1f : targetPage == fromPageId ? 0f : p;
        if (Math.abs(target - p) < 0.001f) {
            return;
        }
        int duration = Math.max(1, Math.round(scaledTotalMs * Math.abs(target - p)));
        beginRun(p, target, duration);
    }

    /** Drop the change and put both pages back at rest (used for a data refresh). */
    void settleNow() {
        if (!running) {
            return;
        }
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        p = runToP;
        apply(p);
        settle();
    }

    private void beginRun(float from, float to, int durationMs) {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        runStartNanos = System.nanoTime();
        runDurationMs = durationMs;
        runFromP = from;
        runToP = to;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void cancel() {
        if (running) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            running = false;
        }
    }

    private void apply(float scene) {
        long now = (long) (scene * totalMs);
        float width = pageWidth;
        for (int i = 0; i < outViews.length; i++) {
            long local = now - (long) outDelay[i] * staggerMs;
            float t = local <= 0 ? 0f : local >= outMs ? 1f : (float) local / outMs;
            float e = t * t;
            float alpha = e < 0.7f ? 1f : (1f - e) / 0.3f;
            View v = outViews[i];
            v.setTranslationX(-dir * SWEEP * width * e);
            v.setRotationY(-dir * TURN_OUT_DEG * e);
            v.setAlpha(alpha);
        }
        for (int i = 0; i < inViews.length; i++) {
            long local = now - inStartMs - (long) inDelay[i] * staggerMs;
            float t = local <= 0 ? 0f : local >= inMs ? 1f : (float) local / inMs;
            float u = 1f - t;
            float e = 1f - u * u * u;
            float alpha = e > 0.3f ? 1f : e / 0.3f;
            View v = inViews[i];
            v.setTranslationX(dir * SWEEP * width * (1f - e));
            v.setRotationY(TURN_IN_DEG * (1f - e));
            v.setAlpha(alpha);
        }
    }

    private void prepare(View[] views) {
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            v.setPivotX(0f);
            v.setPivotY(v.getHeight() > 0 ? v.getHeight() / 2f : 0f);
        }
    }

    private void settle() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        boolean forward = p >= 0.5f;
        View shown = forward ? toPage : fromPage;
        View hidden = forward ? fromPage : toPage;
        reset(shown);
        reset(hidden);
        hidden.setVisibility(View.GONE);
        p = forward ? 1f : 0f;
        int shownPage = forward ? toPageId : fromPageId;
        fromPage = null;
        toPage = null;
        outViews = new View[0];
        inViews = new View[0];
        if (listener != null) {
            listener.onTurnstileSettled(shownPage);
        }
    }

    private void reset(View page) {
        ArrayList<View> blocks = page == toPage ? inList : outList;
        for (int i = 0; i < blocks.size(); i++) {
            View v = blocks.get(i);
            v.setAlpha(1f);
            v.setRotationY(0f);
            v.setTranslationX(0f);
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
        }
        page.setAlpha(1f);
        page.setVisibility(View.VISIBLE);
    }

    private static int[] toArray(ArrayList<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int lastStagger(int[] delays) {
        int last = 0;
        for (int i = 0; i < delays.length; i++) {
            if (delays[i] > last) {
                last = delays[i];
            }
        }
        return last;
    }
}
