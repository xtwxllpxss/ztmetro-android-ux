package org.ztmetro.start;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Hosts the two WP pages and owns the gestures:
 *
 * - horizontal swipe: Start <-> app list (the pivot gesture);
 * - vertical pull-down that starts in the top 25% of the content area and
 *   travels at least 80dp: opens the Metro Action Centre.
 *
 * Vertical drags elsewhere stay with the ScrollView/ListView. Horizontal wins
 * when the drag is ambiguous, so the pivot swipe and the Action Centre pull do
 * not fight each other.
 */
final class MetroPager extends FrameLayout {

    interface Listener {
        void onSwipeLeft();

        void onSwipeRight();

        void onSwipeDown();
    }

    private final int touchSlop;
    private final float swipeDownPx;
    private float downX;
    private float downY;
    private boolean horizontalDragging;
    private boolean verticalDragging;
    private boolean startedTopQuarter;
    private boolean enabled = true;
    private Listener listener;

    MetroPager(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeDownPx = 80f * context.getResources().getDisplayMetrics().density;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setSwipeEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            horizontalDragging = false;
            verticalDragging = false;
        }
    }

    private void begin(MotionEvent event) {
        downX = event.getX();
        downY = event.getY();
        horizontalDragging = false;
        verticalDragging = false;
        int contentTop = getPaddingTop();
        int contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentHeight <= 0) {
            contentHeight = getHeight();
        }
        startedTopQuarter = downY >= contentTop
                && downY <= contentTop + contentHeight * 0.25f;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!enabled) {
                    return false;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    horizontalDragging = true;
                    return true;
                }
                if (startedTopQuarter && dy > touchSlop
                        && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                    verticalDragging = true;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (enabled && !horizontalDragging && !verticalDragging) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        horizontalDragging = true;
                    } else if (startedTopQuarter && dy > touchSlop
                            && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                        verticalDragging = true;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (horizontalDragging && listener != null) {
                    if (dx < -touchSlop) {
                        listener.onSwipeLeft();
                    } else if (dx > touchSlop) {
                        listener.onSwipeRight();
                    }
                } else if (verticalDragging && dy >= swipeDownPx && listener != null) {
                    listener.onSwipeDown();
                }
                horizontalDragging = false;
                verticalDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                horizontalDragging = false;
                verticalDragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
