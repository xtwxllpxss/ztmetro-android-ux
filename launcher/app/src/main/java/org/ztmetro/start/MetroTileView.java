package org.ztmetro.start;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/**
 * One WP tile, drawn by us — not an Android widget.
 *
 * Flat accent rectangle, no corner radius, no elevation, no ripple. A white
 * alpha silhouette sits in the upper part, horizontally centred, at 44% of the
 * tile's short side; the app label sits in the lower-left at 13sp white. When
 * there is no silhouette the first letter is drawn white at 40% of the tile
 * height. The 1x1 tile is glyph-only, like the ztmetro reference.
 *
 * Press is the ztmetro tilt: wait 80ms (tokens motion.press_delay_ms), then
 * lean up to 14 degrees toward the finger and scale to 0.97 over 90ms; release
 * returns over 200ms. A 450ms hold opens the tile menu and swallows the launch.
 */
final class MetroTileView extends View {

    interface Listener {
        void onTileTap(TileStore.Tile tile);

        void onTileLongPress(TileStore.Tile tile);
    }

    private static final float PRESS_SCALE = 0.97f;

    private final TileArt art;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF glyphRect = new RectF();

    private final int padPx;
    private final int pressDelayMs;
    private final int pressInMs;
    private final int pressOutMs;
    private final int longPressMs;
    private final float tiltDeg;
    private final float iconFraction;
    private final int onAccent;
    private final int touchSlop;

    private TileStore.Tile tile;
    private Listener listener;
    private Bitmap glyph;
    private StaticLayout labelLayout;
    private String letter = "?";
    private int baseColor;
    private int pressedColor;
    private int liveCount;
    private int iconPx;
    private int labelTop;
    private int pad;

    private boolean down;
    private boolean leaning;
    private boolean longFired;
    private float downX;
    private float downY;

    private final Runnable tiltRunnable = new Runnable() {
        @Override
        public void run() {
            if (!down || longFired) {
                return;
            }
            commitTilt();
        }
    };

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!down || longFired) {
                return;
            }
            longFired = true;
            down = false;
            leaning = false;
            resetTilt(true);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null && tile != null) {
                listener.onTileLongPress(tile);
            }
        }
    };

    MetroTileView(Context context, TileArt art) {
        super(context);
        this.art = art;
        setClickable(true);
        setFocusable(true);
        setCameraDistance(1600f * context.getResources().getDisplayMetrics().density);
        padPx = MetroUi.dimen(context, R.dimen.metro_v3_tile_pad);
        pad = padPx;
        pressDelayMs = context.getResources().getInteger(
                R.integer.metro_motion_press_delay_ms);
        pressInMs = context.getResources().getInteger(
                R.integer.metro_motion_press_in_ms);
        pressOutMs = context.getResources().getInteger(
                R.integer.metro_motion_press_out_ms);
        longPressMs = context.getResources().getInteger(
                R.integer.metro_motion_long_press_ms);
        tiltDeg = context.getResources().getInteger(
                R.integer.metro_motion_tilt_deg);
        iconFraction = context.getResources().getFraction(
                R.fraction.metro_v3_icon_fraction, 1, 1);
        onAccent = context.getResources().getColor(R.color.metro_on_accent, null);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        MetroUi.Text body = MetroUi.text(context, R.style.TextAppearance_Metro_Body);
        float textSize = body.sizePx;
        labelPaint.setTextSize(textSize);
        labelPaint.setTypeface(body.typeface == null ? Typeface.SANS_SERIF : body.typeface);
        labelPaint.setColor(onAccent);

        letterPaint.setTextSize(textSize);
        letterPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        letterPaint.setColor(onAccent);
        letterPaint.setTextAlign(Paint.Align.LEFT);

        countPaint.setTextSize(textSize);
        countPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        countPaint.setColor(onAccent);
        countPaint.setTextAlign(Paint.Align.RIGHT);

        glyphPaint.setColor(onAccent);
        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null && tile != null) {
                    listener.onTileTap(tile);
                }
            }
        });
    }

    void setTile(TileStore.Tile tile) {
        this.tile = tile;
        this.liveCount = 0;
        if (tile != null) {
            baseColor = art.colorFor(tile.key());
            pressedColor = TileArt.blend(baseColor, onAccent, 0.12f);
            letter = TileArt.firstLetter(tile.label);
            setContentDescription(tile.label);
        } else {
            baseColor = getResources().getColor(R.color.metro_accent, null);
            pressedColor = TileArt.blend(baseColor, onAccent, 0.12f);
            setContentDescription(null);
        }
        glyph = null;
        labelLayout = null;
        if (getWidth() > 0) {
            buildArt();
        }
        invalidate();
    }

    void setLiveCount(int count) {
        if (liveCount != count) {
            liveCount = count;
            invalidate();
        }
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private void buildArt() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || tile == null) {
            return;
        }
        pad = tile.size == TileStore.SIZE_SMALL
                ? Math.min(padPx, Math.max(3, Math.round(height * 0.09f)))
                : padPx;
        iconPx = Math.max(1, Math.round(Math.min(width, height) * iconFraction));
        glyph = art.glyph(tile.component, iconPx);
        // ztmetro centres the glyph horizontally in the upper part of the tile
        // and keeps the label bottom-left; small tiles carry the glyph only.
        int glyphLeft = (width - iconPx) / 2;
        int glyphTop = Math.round(height * 0.10f);
        glyphRect.set(glyphLeft, glyphTop, glyphLeft + iconPx, glyphTop + iconPx);

        if (tile.size == TileStore.SIZE_SMALL) {
            labelLayout = null;
        } else {
            int labelWidth = Math.max(1, width - 2 * pad);
            labelLayout = StaticLayout.Builder.obtain(tile.label, 0, tile.label.length(),
                    labelPaint, labelWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            labelTop = height - pad - labelLayout.getHeight();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildArt();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            width = Math.max(1, Math.round(52f * getResources().getDisplayMetrics().density));
        }
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            height = width;
        }
        setMeasuredDimension(width, height);
    }


    /** A white WP gear for the appearance/settings tile; no Android icon asset. */
    private void drawAppearanceGlyph(Canvas canvas) {
        float cx = glyphRect.centerX();
        float cy = glyphRect.centerY();
        float radius = glyphRect.width() * 0.26f;
        gearPaint.setStyle(Paint.Style.STROKE);
        gearPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        gearPaint.setStrokeCap(Paint.Cap.ROUND);
        gearPaint.setColor(onAccent);
        canvas.drawCircle(cx, cy, radius, gearPaint);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0 * i / 8.0;
            float inner = radius * 1.18f;
            float outer = radius * 1.55f;
            canvas.drawLine(cx + (float) Math.cos(angle) * inner,
                    cy + (float) Math.sin(angle) * inner,
                    cx + (float) Math.cos(angle) * outer,
                    cy + (float) Math.sin(angle) * outer, gearPaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (tile == null) {
            return;
        }
        fillPaint.setColor(isPressedVisual() ? pressedColor : baseColor);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), fillPaint);

        if (tile.appearance) {
            drawAppearanceGlyph(canvas);
        } else if (glyph != null) {
            canvas.drawBitmap(glyph, null, glyphRect, glyphPaint);
        } else {
            letterPaint.setTextSize(Math.max(1f, getHeight() * 0.40f));
            Paint.FontMetrics metrics = letterPaint.getFontMetrics();
            float textWidth = letterPaint.measureText(letter);
            float left = glyphRect.centerX() - textWidth / 2f;
            float baseline = glyphRect.top - metrics.ascent;
            canvas.drawText(letter, left, baseline, letterPaint);
        }

        if (labelLayout != null) {
            canvas.save();
            canvas.translate(pad, labelTop);
            labelLayout.draw(canvas);
            canvas.restore();
        }

        if (liveCount > 0 && tile.size != TileStore.SIZE_SMALL) {
            Paint.FontMetrics metrics = countPaint.getFontMetrics();
            canvas.drawText(liveCount > 99 ? "99+" : Integer.toString(liveCount),
                    getWidth() - pad, pad - metrics.ascent, countPaint);
        }
    }

    private boolean isPressedVisual() {
        return down || leaning;
    }

    private void commitTilt() {
        leaning = true;
        float halfW = Math.max(1f, getWidth() / 2f);
        float halfH = Math.max(1f, getHeight() / 2f);
        float nx = (downX - halfW) / halfW;
        float ny = (downY - halfH) / halfH;
        float length = (float) Math.sqrt(nx * nx + ny * ny);
        if (length < 0.18f) {
            // A dead-centre press still gets a readable WP lean toward the
            // lower-right, instead of a no-op at exactly 0 degrees.
            nx = 0.30f;
            ny = 1f;
            length = (float) Math.sqrt(nx * nx + ny * ny);
        }
        if (length > 1f) {
            nx /= length;
            ny /= length;
            length = 1f;
        }
        float rotationX = -tiltDeg * ny;
        float rotationY = tiltDeg * nx;
        animate().cancel();
        animate()
                .rotationX(rotationX)
                .rotationY(rotationY)
                .scaleX(PRESS_SCALE)
                .scaleY(PRESS_SCALE)
                .setDuration(pressInMs)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        invalidate();
    }

    private void resetTilt(boolean animate) {
        animate().cancel();
        if (animate) {
            animate()
                    .rotationX(0f)
                    .rotationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(pressOutMs)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            setRotationX(0f);
            setRotationY(0f);
            setScaleX(1f);
            setScaleY(1f);
        }
        leaning = false;
        invalidate();
    }

    private void cancelPress() {
        handler.removeCallbacks(tiltRunnable);
        handler.removeCallbacks(longPressRunnable);
        down = false;
        longFired = false;
        resetTilt(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                down = true;
                leaning = false;
                longFired = false;
                downX = event.getX();
                downY = event.getY();
                handler.removeCallbacks(tiltRunnable);
                handler.removeCallbacks(longPressRunnable);
                handler.postDelayed(tiltRunnable, pressDelayMs);
                handler.postDelayed(longPressRunnable, longPressMs);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (down && (Math.abs(event.getX() - downX) > touchSlop
                        || Math.abs(event.getY() - downY) > touchSlop)) {
                    cancelPress();
                }
                return down;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(tiltRunnable);
                handler.removeCallbacks(longPressRunnable);
                boolean fired = longFired;
                boolean wasDown = down;
                down = false;
                longFired = false;
                resetTilt(true);
                if (!fired && wasDown && tile != null) {
                    performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelPress();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tiltRunnable);
        handler.removeCallbacks(longPressRunnable);
        animate().cancel();
        super.onDetachedFromWindow();
    }
}
