package org.ztmetro.start;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
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

import java.util.Calendar;
import java.util.Locale;

/**
 * One WP tile, drawn by us — not an Android widget.
 *
 * Flat accent rectangle, no corner radius, no elevation, no ripple. A white
 * alpha silhouette sits in the upper part, horizontally centred, at 44% of the
 * tile's short side; the app label sits in the lower-left at 13sp white. When
 * there is no silhouette the first letter is drawn white at 40% of the tile
 * height. The 1x1 tile is glyph-only, like the ztmetro reference.
 *
 * <p>Live second face: when {@link LiveTiles} has content for the package (or
 * the package is the calendar), the tile keeps a second face with the unread
 * count in the corner and up to two 12sp lines of the latest text, the big
 * calendar day, or the media track title. A content change flips the tile
 * 0 -> 180 degrees around Y in {@code metro_live_flip_ms} (240 ms); the face is
 * swapped exactly at 90 degrees. There is no periodic timer: the flip runs only
 * when the face signature changes. Animator scale 0 (Settings.Global
 * ANIMATOR_DURATION_SCALE) applies the new face instantly.
 *
 * Press is the ztmetro tilt: wait 80ms (tokens motion.press_delay_ms, the C's
 * PRESS_DELAY_MS at {@code ztmetro.c:148}), then lean up to 14 degrees toward
 * the finger and scale to 0.97 over 90ms ({@code ZMC_TILT_IN_MS},
 * {@code core/zrnmetrocore.h:35}); release returns over 200ms
 * ({@code ZMC_TILT_OUT_MS}, {@code core/zrnmetrocore.h:36}). One 60 Hz frame
 * before the lean the tile takes a faint white press wash, WP's early cue.
 * A 450ms hold ({@code LONG_PRESS_MS}, {@code ztmetro.c:145}) opens the tile
 * menu, cancels the lean and swallows the launch.
 */
final class MetroTileView extends View {

    interface Listener {
        void onTileTap(TileStore.Tile tile);

        void onTileLongPress(TileStore.Tile tile);
    }

    private static final float PRESS_SCALE = 0.97f;
    private static final float FLIP_SWAP_DEGREES = 90f;

    private final TileArt art;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint colorPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint livePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF glyphRect = new RectF();
    private final Camera flipCamera = new Camera();
    private final Matrix flipMatrix = new Matrix();

    private final int padPx;
    private final int pressDelayMs;
    private final int pressInMs;
    private final int pressOutMs;
    private final int longPressMs;
    private final float tiltDeg;
    private final int onAccent;
    private final int touchSlop;
    private final float density;
    private final int flipMs;
    private final float dateFraction;
    private final float countFraction;

    private TileStore.Tile tile;
    private Listener listener;
    private TileArt.Art glyphArt;
    private StaticLayout labelLayout;
    private String letter = "?";
    private int baseColor;
    private int iconPx;
    private int labelTop;
    private int pad;

    private boolean down;
    private boolean leaning;
    private boolean highlight;
    private boolean longFired;
    private boolean wasLaidOut;
    private float downX;
    private float downY;

    /* ---- live second face -------------------------------------------------- */
    private LiveTiles.Face liveFace;
    private LiveTiles.Face flipFromFace;
    private LiveTiles.Face flipToFace;
    private boolean showingLive;
    private boolean flipFromLive;
    private boolean flipToLive;
    private boolean flipping;
    private float flipAngle;
    private String appliedSignature = "";
    private String pendingSignature = "";
    private ValueAnimator flipAnimator;
    private StaticLayout liveLayout;
    private String liveLayoutKey;

    private final Runnable highlightRunnable = new Runnable() {
        @Override
        public void run() {
            if (!down || longFired) {
                return;
            }
            highlight = true;
            invalidate();
        }
    };

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
            highlight = false;
            leaning = false;
            resetTilt(false);   /* the menu takes over: no lean underneath it */
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
        density = context.getResources().getDisplayMetrics().density;
        padPx = MetroUi.dimen(context, R.dimen.metro_v3_tile_pad);
        pad = padPx;
        pressDelayMs = context.getResources().getInteger(
                R.integer.metro_motion_press_delay_ms);
        pressInMs = Motion.ms(context, context.getResources().getInteger(
                R.integer.metro_motion_press_in_ms));
        pressOutMs = Motion.ms(context, context.getResources().getInteger(
                R.integer.metro_motion_press_out_ms));
        longPressMs = context.getResources().getInteger(
                R.integer.metro_motion_long_press_ms);
        tiltDeg = context.getResources().getInteger(
                R.integer.metro_motion_tilt_deg);
        flipMs = context.getResources().getInteger(R.integer.metro_live_flip_ms);
        dateFraction = context.getResources().getFraction(
                R.fraction.metro_live_date_fraction, 1, 1);
        countFraction = context.getResources().getFraction(
                R.fraction.metro_live_count_fraction, 1, 1);
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

        countPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        countPaint.setColor(onAccent);
        countPaint.setTextAlign(Paint.Align.RIGHT);

        MetroUi.Text live = MetroUi.text(context, R.style.TextAppearance_Metro_LiveText);
        livePaint.setTextSize(live.sizePx);
        livePaint.setTypeface(live.typeface == null
                ? Typeface.SANS_SERIF : live.typeface);
        livePaint.setColor(onAccent);
        livePaint.setTextAlign(Paint.Align.LEFT);

        datePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        datePaint.setColor(onAccent);
        datePaint.setTextAlign(Paint.Align.LEFT);

        glyphPaint.setColor(onAccent);
        flipCamera.setLocation(0f, 0f, -8f * density);
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
        cancelFlip();
        showingLive = false;
        liveFace = null;
        appliedSignature = "";
        pendingSignature = "";
        liveLayout = null;
        liveLayoutKey = null;
        if (tile != null) {
            baseColor = art.colorFor(tile.key());
            letter = TileArt.firstLetter(tile.label);
            setContentDescription(tile.label);
        } else {
            baseColor = getResources().getColor(R.color.metro_accent, null);
            setContentDescription(null);
        }
        glyphArt = null;
        labelLayout = null;
        if (getWidth() > 0) {
            buildArt();
        }
        invalidate();
    }

    /**
     * The live face for this tile. {@code null} means "static glyph face".
     *
     * <p>The view compares {@link LiveTiles.Face#signature()} with what is on
     * screen, so unrelated notification traffic never flips the tile. The flip
     * is started here, not by a timer: 0 -> 180 degrees over
     * {@code metro_live_flip_ms}, face swap at 90. An initial bind (not laid
     * out yet) applies without animation; pass 2 keeps the tile views across a
     * resume (the grid only re-creates them when the tile signature changed),
     * so a return to Start no longer rebuilds every face.
     */
    void setLiveFace(LiveTiles.Face face, boolean animate) {
        if (tile != null && tile.size == TileStore.SIZE_SMALL && face != null
                && face.kind == LiveTiles.KIND_MUSIC) {
            // A 49 dp tile cannot show a track title; keep the glyph-only face.
            face = null;
        }
        boolean targetLive = face != null && face.hasContent();
        LiveTiles.Face target = targetLive ? face : null;
        String signature = target == null ? "" : target.signature();

        if (flipping) {
            if (!signature.equals(pendingSignature)) {
                flipToFace = target;
                flipToLive = targetLive;
                pendingSignature = signature;
            }
            return;
        }
        if (signature.equals(appliedSignature)) {
            return;
        }
        if (!animate || !wasLaidOut || getWidth() <= 0 || flipMs <= 0
                || Motion.scale(getContext()) <= 0f) {
            // System animations off (ANIMATOR_DURATION_SCALE = 0) is a direct
            // swap, as on Windows: the content changes, the motion does not run.
            applyFace(target, targetLive);
            return;
        }
        startFlip(target, targetLive, signature);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private void applyFace(LiveTiles.Face face, boolean live) {
        liveFace = face;
        showingLive = live && face != null;
        appliedSignature = showingLive ? face.signature() : "";
        pendingSignature = appliedSignature;
        flipping = false;
        flipAngle = 0f;
        liveLayout = null;
        liveLayoutKey = null;
        updateContentDescription();
        invalidate();
    }

    private void startFlip(final LiveTiles.Face target, final boolean targetLive,
                           final String signature) {
        flipFromFace = showingLive ? liveFace : null;
        flipFromLive = showingLive;
        flipToFace = target;
        flipToLive = targetLive;
        pendingSignature = signature;
        flipping = true;
        flipAngle = 0f;
        cancelFlipAnimator();

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 180f);
        animator.setDuration(flipMs);
        // The framework scales animator durations by the same Settings.Global
        // value; the explicit Motion.scale() check above is the 0 = instant
        // case, which must not start a zero-length animator.
        animator.setInterpolator(Motion.WP_LOG);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                flipAngle = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation != flipAnimator || !flipping) {
                    return;
                }
                applyFace(flipToFace, flipToLive);
                flipAnimator = null;
            }
        });
        flipAnimator = animator;
        animator.start();
    }

    private void cancelFlip() {
        cancelFlipAnimator();
        flipping = false;
        flipAngle = 0f;
    }

    private void cancelFlipAnimator() {
        if (flipAnimator != null) {
            ValueAnimator animator = flipAnimator;
            flipAnimator = null;
            animator.removeAllListeners();
            animator.cancel();
        }
    }

    private void buildArt() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || tile == null) {
            return;
        }
        wasLaidOut = true;
        pad = tile.size == TileStore.SIZE_SMALL
                ? Math.min(padPx, Math.max(3, Math.round(height * 0.09f)))
                : padPx;
        // mockups/screens/start.html + ztmetro.c:3675-3677: the medium/wide
        // glyph is centred at (w/2, 0.44 h) at 0.46 h, so its box starts at
        // 0.21 h; the small tile's glyph is the centre of the tile at 0.56 h,
        // box starting at 0.22 h. v3 shipped 0.10 h / 44 %, which left every
        // glyph ~11 dp too high.
        boolean small = tile.size == TileStore.SIZE_SMALL;
        iconPx = Math.max(1, Math.round(Math.min(width, height)
                * (small ? 0.56f : 0.46f)));
        glyphArt = art.art(tile.component, iconPx);
        int glyphLeft = (width - iconPx) / 2;
        int glyphTop = Math.round(height * (small ? 0.22f : 0.21f));
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
        liveLayout = null;
        liveLayoutKey = null;
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

    /* ---- drawing ----------------------------------------------------------- */

    private void drawFill(Canvas canvas) {
        fillPaint.setColor(baseColor);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), fillPaint);
        if (highlight || leaning) {
            /* One frame ahead of the lean, and kept while it leans: a 6 % white
             * wash, not a colour change and not a ripple. */
            fillPaint.setColor(0x10FFFFFF);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), fillPaint);
        }
    }

    private void drawLetter(Canvas canvas) {
        letterPaint.setTextSize(Math.max(1f, getHeight() * 0.40f));
        Paint.FontMetrics metrics = letterPaint.getFontMetrics();
        float textWidth = letterPaint.measureText(letter);
        float left = glyphRect.centerX() - textWidth / 2f;
        float baseline = glyphRect.top - metrics.ascent;
        canvas.drawText(letter, left, baseline, letterPaint);
    }

    private void drawStaticFace(Canvas canvas) {
        drawFill(canvas);
        if (tile.appearance) {
            SystemGlyphs.draw(canvas, SystemGlyphs.SETTINGS, glyphRect);
        } else if (glyphArt != null && glyphArt.type == TileArt.TYPE_WHITE) {
            canvas.drawBitmap(glyphArt.bitmap, null, glyphRect, glyphPaint);
        } else if (glyphArt != null && glyphArt.type == TileArt.TYPE_COLOR) {
            canvas.drawBitmap(glyphArt.bitmap, null, glyphRect, colorPaint);
        } else if (glyphArt != null && glyphArt.type == TileArt.TYPE_SYSTEM) {
            SystemGlyphs.draw(canvas, glyphArt.systemKind, glyphRect);
        } else {
            drawLetter(canvas);
        }
        drawLabel(canvas);
    }

    private void drawLiveFace(Canvas canvas, LiveTiles.Face face) {
        drawFill(canvas);
        if (face.kind == LiveTiles.KIND_CALENDAR) {
            drawCalendarFace(canvas, face);
        } else {
            drawLiveTextFace(canvas, face);
        }
        drawLabel(canvas);
    }

    private void drawLabel(Canvas canvas) {
        if (labelLayout == null) {
            return;
        }
        canvas.save();
        canvas.translate(pad, labelTop);
        labelLayout.draw(canvas);
        canvas.restore();
    }

    /** Unread count in the corner + 1-2 lines of the latest text at 12sp. */
    private void drawLiveTextFace(Canvas canvas, LiveTiles.Face face) {
        int width = getWidth();
        int height = getHeight();
        boolean small = tile.size == TileStore.SIZE_SMALL;
        int count = face.kind == LiveTiles.KIND_MUSIC ? 0 : face.count;
        String countText = count > 99 ? "99+" : Integer.toString(count);
        float shortSide = Math.min(width, height);
        countPaint.setTextSize(Math.max(livePaint.getTextSize(),
                shortSide * countFraction));

        if (small) {
            if (count > 0) {
                // 49 dp: the number is the whole second face.
                countPaint.setTextSize(shortSide * 0.56f);
                countPaint.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics metrics = countPaint.getFontMetrics();
                float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(countText, width / 2f, baseline, countPaint);
                countPaint.setTextAlign(Paint.Align.RIGHT);
            }
            return;
        }

        int reserved = 0;
        if (count > 0) {
            reserved = Math.round(countPaint.measureText(countText)) + pad;
        }
        StaticLayout layout = liveLayout(face, Math.max(1, width - 2 * pad - reserved));
        if (layout != null) {
            canvas.save();
            canvas.translate(pad, pad);
            layout.draw(canvas);
            canvas.restore();
        }
        if (count > 0) {
            Paint.FontMetrics metrics = countPaint.getFontMetrics();
            canvas.drawText(countText, width - pad, pad - metrics.ascent, countPaint);
        }
    }

    /** WP calendar tile: big day number, short weekday/month, 12sp caps. */
    private void drawCalendarFace(Canvas canvas, LiveTiles.Face face) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(face.date);
        String dayText = Integer.toString(day.get(Calendar.DAY_OF_MONTH));
        int width = getWidth();
        int height = getHeight();
        float shortSide = Math.min(width, height);

        if (tile.size == TileStore.SIZE_SMALL) {
            datePaint.setTextSize(shortSide * dateFraction * 1.4f);
            datePaint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = datePaint.getFontMetrics();
            float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(dayText, width / 2f, baseline, datePaint);
            datePaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        Locale locale = Locale.getDefault();
        String weekday = day.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale);
        String month = day.getDisplayName(Calendar.MONTH, Calendar.SHORT, locale);
        weekday = weekday == null ? "" : weekday.toUpperCase(locale);
        month = month == null ? "" : month.toUpperCase(locale);

        Paint.FontMetrics caption = livePaint.getFontMetrics();
        float captionBaseline = pad - caption.ascent;
        canvas.drawText(weekday, pad, captionBaseline, livePaint);

        datePaint.setTextSize(shortSide * dateFraction);
        Paint.FontMetrics big = datePaint.getFontMetrics();
        float dayBaseline = captionBaseline + caption.descent
                + Math.max(0f, pad * 0.25f) - big.ascent;
        float dayWidth = datePaint.measureText(dayText);
        canvas.drawText(dayText, pad, dayBaseline, datePaint);
        canvas.drawText(month, pad + dayWidth + pad * 0.4f, dayBaseline, livePaint);
    }

    /** 1-2 lines of title/text at the generated 12sp caption size. */
    private StaticLayout liveLayout(LiveTiles.Face face, int width) {
        String key = face.signature() + "|" + width;
        if (key.equals(liveLayoutKey)) {
            return liveLayout;
        }
        String text = joinLines(face.title, face.text);
        if (text.length() == 0) {
            liveLayout = null;
        } else {
            liveLayout = StaticLayout.Builder.obtain(text, 0, text.length(),
                    livePaint, Math.max(1, width))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
        }
        liveLayoutKey = key;
        return liveLayout;
    }

    private static String joinLines(String title, String text) {
        String head = title == null ? "" : title.trim();
        String body = text == null ? "" : text.trim();
        if (head.length() == 0) {
            return body;
        }
        if (body.length() == 0 || body.equals(head)) {
            return head;
        }
        return head + "\n" + body;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (tile == null) {
            return;
        }
        if (!flipping) {
            if (showingLive && liveFace != null) {
                drawLiveFace(canvas, liveFace);
            } else {
                drawStaticFace(canvas);
            }
            return;
        }

        boolean secondHalf = flipAngle >= FLIP_SWAP_DEGREES;
        float angle = secondHalf ? flipAngle + 180f : flipAngle;
        canvas.save();
        applyFlip(canvas, angle);
        if (secondHalf) {
            if (flipToLive && flipToFace != null) {
                drawLiveFace(canvas, flipToFace);
            } else {
                drawStaticFace(canvas);
            }
        } else if (flipFromLive && flipFromFace != null) {
            drawLiveFace(canvas, flipFromFace);
        } else {
            drawStaticFace(canvas);
        }
        canvas.restore();
    }

    /**
     * Canvas-level rotateY so the flip composes with the press tilt (which owns
     * the View rotation properties). The face swap is the 90-degree branch in
     * onDraw(); the back half carries an extra 180 so its text reads upright.
     */
    private void applyFlip(Canvas canvas, float degrees) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        flipCamera.save();
        flipCamera.rotateY(degrees);
        flipCamera.getMatrix(flipMatrix);
        flipCamera.restore();
        flipMatrix.preTranslate(-cx, -cy);
        flipMatrix.postTranslate(cx, cy);
        canvas.concat(flipMatrix);
    }

    private void updateContentDescription() {
        if (tile == null) {
            setContentDescription(null);
            return;
        }
        if (!showingLive || liveFace == null) {
            setContentDescription(tile.label);
            return;
        }
        StringBuilder description = new StringBuilder(tile.label);
        if (liveFace.kind == LiveTiles.KIND_CALENDAR) {
            Calendar day = Calendar.getInstance();
            day.setTimeInMillis(liveFace.date);
            description.append(", ")
                    .append(day.getDisplayName(Calendar.DAY_OF_MONTH,
                            Calendar.LONG, Locale.getDefault()));
            description.append(' ').append(day.getDisplayName(Calendar.MONTH,
                    Calendar.LONG, Locale.getDefault()));
        } else if (liveFace.kind == LiveTiles.KIND_MUSIC) {
            description.append(", ").append(joinLines(liveFace.title, liveFace.text)
                    .replace('\n', ' '));
        } else {
            if (liveFace.count > 0) {
                description.append(", ").append(liveFace.count).append(" unread");
            }
            String line = joinLines(liveFace.title, liveFace.text).replace('\n', ' ');
            if (line.length() > 0) {
                description.append(", ").append(line);
            }
        }
        setContentDescription(description.toString());
    }

    /* ---- press / motion ---------------------------------------------------- */

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
        if (pressInMs <= 0) {
            setRotationX(rotationX);
            setRotationY(rotationY);
            setScaleX(PRESS_SCALE);
            setScaleY(PRESS_SCALE);
        } else {
            animate()
                    .rotationX(rotationX)
                    .rotationY(rotationY)
                    .scaleX(PRESS_SCALE)
                    .scaleY(PRESS_SCALE)
                    .setDuration(pressInMs)
                    .setInterpolator(Motion.WP_LOG)
                    .start();
        }
        invalidate();
    }

    private void resetTilt(boolean animate) {
        animate().cancel();
        if (animate && pressOutMs > 0) {
            animate()
                    .rotationX(0f)
                    .rotationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(pressOutMs)
                    .setInterpolator(Motion.WP_LOG)
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
        handler.removeCallbacks(highlightRunnable);
        handler.removeCallbacks(tiltRunnable);
        handler.removeCallbacks(longPressRunnable);
        down = false;
        highlight = false;
        longFired = false;
        resetTilt(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                down = true;
                leaning = false;
                highlight = false;
                longFired = false;
                downX = event.getX();
                downY = event.getY();
                handler.removeCallbacks(highlightRunnable);
                handler.removeCallbacks(tiltRunnable);
                handler.removeCallbacks(longPressRunnable);
                handler.postDelayed(highlightRunnable,
                        Math.max(0, pressDelayMs - Motion.HIGHLIGHT_LEAD_MS));
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
                handler.removeCallbacks(highlightRunnable);
                handler.removeCallbacks(tiltRunnable);
                handler.removeCallbacks(longPressRunnable);
                boolean fired = longFired;
                boolean wasDown = down;
                down = false;
                highlight = false;
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
        handler.removeCallbacks(highlightRunnable);
        handler.removeCallbacks(tiltRunnable);
        handler.removeCallbacks(longPressRunnable);
        cancelFlipAnimator();
        flipping = false;
        animate().cancel();
        super.onDetachedFromWindow();
    }
}
