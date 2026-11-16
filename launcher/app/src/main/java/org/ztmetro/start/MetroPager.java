package org.ztmetro.start;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Hosts the two WP pages and owns the horizontal page swipe.
 *
 * A vertical drag is left to the ScrollView/ListView; only a clear horizontal
 * drag (beyond touch slop and 1.4x the vertical travel) is intercepted, so a
 * tile press or a list scroll never turns into a page change.
 */
final class MetroPager extends FrameLayout {

    interface Listener {
        void onSwipeLeft();

        void onSwipeRight();
    }

    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean enabled = true;
    private Listener listener;

    MetroPager(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setSwipeEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            dragging = false;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!enabled) {
                    return false;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop
                        && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    dragging = true;
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
                downX = event.getX();
                downY = event.getY();
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging && listener != null) {
                    float dx = event.getX() - downX;
                    if (dx < -touchSlop) {
                        listener.onSwipeLeft();
                    } else if (dx > touchSlop) {
                        listener.onSwipeRight();
                    }
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
