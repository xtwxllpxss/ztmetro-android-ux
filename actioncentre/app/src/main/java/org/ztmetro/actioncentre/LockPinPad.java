package org.ztmetro.actioncentre;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Reference ztMetro PIN pad for the lock overlay: 0-9, backspace, enter and
 * four entry dots. Pure {@link Canvas} drawing, no Material widgets, no ripple,
 * no elevation, square keys on black, white Open Sans Light digits and accent
 * dots, per {@code design/tokens/ztmetro.tokens.json}
 * ({@code color.accent.base}, {@code color.neutrals.surface_off},
 * {@code color.neutrals.text_primary}).
 *
 * <p><b>The pad is UI only and never verifies anything.</b> It collects four
 * digits and calls {@link LockAuthPanel.OnSubmitListener#onSubmit(String)}.
 * Verification is the system's job: for a secure device the wiring starts
 * {@link UnlockProxyActivity}, which launches
 * {@code KeyguardManager.createConfirmDeviceCredentialIntent(null, null)}; a
 * future custom-lock implementation can route the same callback to a
 * {@code DevicePolicyManager} check instead. This class must not pass the PIN to
 * an activity, log it, store it, or compare it - the current receiver discards
 * it and only its length is logged.
 *
 * <p>Touch: the pad lives in the overlay's {@code NOT_TOUCHABLE} main surface,
 * so it is not hit-tested by the window manager. {@link LockOverlayController}
 * adds a separate touchable window exactly over this view and forwards the
 * {@link MotionEvent}s here; coordinates match 1:1 because that window's origin
 * is this view's screen origin.
 */
final class LockPinPad extends View implements LockAuthPanel {

    /** The reference PIN length drawn as four dots. */
    static final int PIN_LENGTH = 4;

    /** Key indices: 0-9 are the digits, then backspace and enter. */
    private static final int KEY_BACKSPACE = 10;
    private static final int KEY_ENTER = 11;
    private static final int KEY_COUNT = 12;

    private static final float KEY_MAX_DP = 56f;
    private static final float KEY_GAP_DP = 8f;
    private static final float DOTS_BAND_DP = 36f;
    private static final float DOTS_GAP_DP = 12f;
    private static final float DOT_DP = 10f;
    private static final float DOT_SPACING_DP = 16f;
    private static final float DIGIT_SP = 30f;
    private static final float GLYPH_SP = 26f;

    private final Paint keyPaint = new Paint();
    private final Paint pressedPaint = new Paint();
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint filledDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyphPath = new Path();
    private final RectF keyRect = new RectF();
    private final StringBuilder pin = new StringBuilder(PIN_LENGTH);

    private final float keyMax;
    private final float gap;
    private final float dotsBand;
    private final float dotsGap;
    private final float dotSize;
    private final float dotSpacing;
    private final float digitSize;
    private final float glyphSize;
    private final float glyphStroke;

    private LockAuthPanel.OnSubmitListener listener;
    private int pressedKey = -1;
    private float keySize;
    private float gridLeft;
    private float gridTop;

    LockPinPad(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        keyMax = KEY_MAX_DP * density;
        gap = KEY_GAP_DP * density;
        dotsBand = DOTS_BAND_DP * density;
        dotsGap = DOTS_GAP_DP * density;
        dotSize = DOT_DP * density;
        dotSpacing = DOT_SPACING_DP * density;
        digitSize = U.sp(context, DIGIT_SP);
        glyphSize = U.sp(context, GLYPH_SP);
        glyphStroke = 2.2f * density;

        keyPaint.setStyle(Paint.Style.FILL);
        keyPaint.setColor(U.OFF);
        pressedPaint.setStyle(Paint.Style.FILL);
        pressedPaint.setColor(U.OFF_PRESSED);

        digitPaint.setColor(U.WHITE);
        digitPaint.setTextAlign(Paint.Align.CENTER);
        digitPaint.setTypeface(U.light(context));
        digitPaint.setTextSize(digitSize);

        filledDotPaint.setStyle(Paint.Style.FILL);
        filledDotPaint.setColor(U.ACCENT);
        emptyDotPaint.setStyle(Paint.Style.FILL);
        emptyDotPaint.setColor(U.OFF_PRESSED);

        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(glyphStroke);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);

        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    // ------------------------------------------------------------- LockAuthPanel

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void setOnSubmitListener(LockAuthPanel.OnSubmitListener listener) {
        this.listener = listener;
    }

    @Override
    public void reset() {
        pin.setLength(0);
        pressedKey = -1;
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = U.dp(getContext(), 320);
        }
        float size = keySizeFor(width);
        int height = Math.round(dotsBand + dotsGap + 4f * size + 3f * gap);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        keySize = keySizeFor(width);
        gridLeft = (width - (3f * keySize + 2f * gap)) / 2f;
        gridTop = dotsBand + dotsGap;
    }

    private float keySizeFor(int width) {
        return Math.min(keyMax, Math.max(0f, (width - 2f * gap) / 3f));
    }

    /** Column/row of a key index: 3 columns, 4 rows, digits 1-9 then 0. */
    private int keyColumn(int key) {
        return key % 3;
    }

    private int keyRow(int key) {
        return key / 3;
    }

    private void boundsFor(int key, RectF out) {
        float left = gridLeft + keyColumn(key) * (keySize + gap);
        float top = gridTop + keyRow(key) * (keySize + gap);
        out.set(left, top, left + keySize, top + keySize);
    }

    // ------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (canvas == null || keySize <= 0f) {
            return;
        }
        drawDots(canvas);
        for (int key = 0; key < KEY_COUNT; key++) {
            boundsFor(key, keyRect);
            canvas.drawRect(keyRect, key == pressedKey ? pressedPaint : keyPaint);
            float cx = keyRect.centerX();
            float cy = keyRect.centerY();
            if (key < 10) {
                drawDigit(canvas, key, cx, cy);
            } else if (key == KEY_BACKSPACE) {
                drawBackspace(canvas, cx, cy, pin.length() > 0 ? U.WHITE : U.WHITE_30);
            } else {
                drawEnter(canvas, cx, cy,
                        pin.length() == PIN_LENGTH ? U.WHITE : U.WHITE_30);
            }
        }
    }

    private void drawDots(Canvas canvas) {
        float total = PIN_LENGTH * dotSize + (PIN_LENGTH - 1) * (dotSpacing - dotSize);
        float left = (getWidth() - total) / 2f;
        float cy = dotsBand / 2f;
        int entered = pin.length();
        for (int i = 0; i < PIN_LENGTH; i++) {
            float cx = left + dotSize / 2f + i * dotSpacing;
            canvas.drawCircle(cx, cy, dotSize / 2f,
                    i < entered ? filledDotPaint : emptyDotPaint);
        }
    }

    private void drawDigit(Canvas canvas, int digit, float cx, float cy) {
        Paint.FontMetrics metrics = digitPaint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(Integer.toString(digit), cx, baseline, digitPaint);
    }

    /**
     * WP-style backspace glyph: an outlined arrow head with an x, drawn as two
     * strokes. No icon font, no vector drawable, so the pad has no resources.
     */
    private void drawBackspace(Canvas canvas, float cx, float cy, int color) {
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setColor(color);
        float halfHeight = keySize * 0.16f;
        float halfWidth = keySize * 0.24f;
        glyphPath.reset();
        glyphPath.moveTo(cx + halfWidth, cy - halfHeight);
        glyphPath.lineTo(cx - halfWidth * 0.25f, cy - halfHeight);
        glyphPath.lineTo(cx - halfWidth, cy);
        glyphPath.lineTo(cx - halfWidth * 0.25f, cy + halfHeight);
        glyphPath.lineTo(cx + halfWidth, cy + halfHeight);
        glyphPath.close();
        canvas.drawPath(glyphPath, glyphPaint);
        float arm = keySize * 0.055f;
        float innerX = cx + halfWidth * 0.18f;
        canvas.drawLine(innerX - arm, cy - arm, innerX + arm, cy + arm, glyphPaint);
        canvas.drawLine(innerX - arm, cy + arm, innerX + arm, cy - arm, glyphPaint);
    }

    /** Enter glyph: a check mark, white only when four digits are entered. */
    private void drawEnter(Canvas canvas, float cx, float cy, int color) {
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setColor(color);
        glyphPath.reset();
        glyphPath.moveTo(cx - keySize * 0.16f, cy);
        glyphPath.lineTo(cx - keySize * 0.03f, cy + keySize * 0.12f);
        glyphPath.lineTo(cx + keySize * 0.17f, cy - keySize * 0.13f);
        canvas.drawPath(glyphPath, glyphPaint);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }
        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressedKey = hitTest(event.getX(), event.getY());
                    invalidate();
                    // Own the whole gesture: this window is exactly the pad, and
                    // returning false would hand the touch to the swipe strip.
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int key = hitTest(event.getX(), event.getY());
                    if (key != pressedKey) {
                        pressedKey = key;
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    int key = hitTest(event.getX(), event.getY());
                    if (key >= 0 && key == pressedKey) {
                        activate(key);
                    }
                    pressedKey = -1;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    pressedKey = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        } catch (Throwable t) {
            pressedKey = -1;
            invalidate();
            return false;
        }
    }

    /** -1 when the touch is outside every key. */
    private int hitTest(float x, float y) {
        if (keySize <= 0f || x < gridLeft || y < gridTop) {
            return -1;
        }
        int column = (int) Math.floor((x - gridLeft) / (keySize + gap));
        int row = (int) Math.floor((y - gridTop) / (keySize + gap));
        if (column < 0 || column > 2 || row < 0 || row > 3) {
            return -1;
        }
        boundsFor(row * 3 + column, keyRect);
        return keyRect.contains(x, y) ? row * 3 + column : -1;
    }

    private void activate(int key) {
        if (key < 10) {
            if (pin.length() < PIN_LENGTH) {
                pin.append((char) ('0' + key));
                invalidate();
            }
            return;
        }
        if (key == KEY_BACKSPACE) {
            if (pin.length() > 0) {
                pin.setLength(pin.length() - 1);
                invalidate();
            }
            return;
        }
        if (key == KEY_ENTER && pin.length() == PIN_LENGTH) {
            submit();
        }
    }

    /**
     * Hand the digits to the wiring; verification happens outside this class
     * (system credential screen / future DevicePolicyManager). The pad keeps
     * the dots filled until {@link #reset()} so a refused launch can be
     * retried; it never stores the PIN anywhere else.
     */
    private void submit() {
        LockAuthPanel.OnSubmitListener target = listener;
        if (target == null) {
            return;
        }
        try {
            target.onSubmit(pin.toString());
        } catch (Throwable ignored) {
            // A failing receiver must not crash the accessibility service.
        }
    }
}
