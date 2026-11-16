package org.ztmetro.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ztMetro keyboard surface.
 *
 * The layout is the one in upstream/ztOS/ztmetro/KEYBOARD.md:
 *
 *   row 1   ten keys, full width with a 1 dp side gutter
 *   row 2   a centred nine-column grid at the top row's key width
 *   row 3   shift + seven letters + backspace, the edge keys reaching the
 *           screen edges; the letters keep the nine-column grid
 *   row 4   &123 | smiley (under z) | comma (under x) | space | dot (under m)
 *           | enter, the outer keys absorbing the side margins
 *
 * Keys are 56 dp high (the KEYBOARD.md default), one dp apart, hard-cornered
 * rectangles on black.  There is no ripple, no StateListAnimator, no rounded
 * corner: a press flashes the fill for 80 ms (see {@link #PRESS_FLASH_MS}), the
 * only feedback the mockup asks for.  A key-pop preview is deliberately absent:
 * the 56 dp rows leave no room for one above the pressed key, so it would have
 * to overlay the row above; deferred rather than drawn badly.
 *
 * Three pages: letters (EN / RU), &123 (digits + the common punctuation) and
 * emoji (24).  Shift is latched caps.  Holding the accent dot in the
 * shift/symbol corner for 450 ms turns the keyboard into a cursor trackpad.
 */
public final class ZtKeyboardView extends View {

    /** Callback surface implemented by the InputMethodService. */
    public interface Listener {
        void onText(String text);

        void onBackspace();

        void onEnter();

        void onLanguageChanged(boolean russian);

        void onArrow(int keycode);
    }

    private static final int PAGE_LETTERS = 0;
    private static final int PAGE_SYMBOLS = 1;
    private static final int PAGE_EMOJI = 2;

    private static final int ACTION_TEXT = 1;
    private static final int ACTION_SPACE = 2;
    private static final int ACTION_BACKSPACE = 3;
    private static final int ACTION_ENTER = 4;
    private static final int ACTION_SHIFT = 5;
    private static final int ACTION_LANGUAGE = 6;
    private static final int ACTION_SYMBOLS = 7;
    private static final int ACTION_LETTERS = 8;
    private static final int ACTION_EMOJI = 9;

    private static final int ICON_NONE = 0;
    private static final int ICON_SHIFT = 1;
    private static final int ICON_BACKSPACE = 2;
    private static final int ICON_SMILEY = 3;
    private static final int ICON_ENTER = 4;

    private static final int LAYOUT_TOP = 0;       // ten-column top row
    private static final int LAYOUT_NINE = 1;      // centred nine-column grid
    private static final int LAYOUT_NINE_EXT = 2;  // nine columns, edge shift/backspace
    private static final int LAYOUT_ELEVEN = 3;    // Russian full rows
    private static final int LAYOUT_ELEVEN_EXT = 4;
    private static final int LAYOUT_BOTTOM = 5;    // function row(s)
    private static final int LAYOUT_GRID8 = 6;     // emoji, eight per row

    private static final int COLOR_BACKGROUND = 0xFF000000;
    // mockups/out/keyboard.png: letter key #333333, function key #1F1F1F.
    private static final int COLOR_KEY = 0xFF333333;       // letter key
    private static final int COLOR_KEY_FN = 0xFF1F1F1F;    // function key
    private static final int COLOR_PRESSED = 0xFF4D4D4D;   // press flash / latched fill
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SPACE_TEXT = 0xCCFFFFFF;
    private static final int COLOR_TEXT_DIM = 0x66FFFFFF;
    private static final int COLOR_ACCENT = 0xFF0050EF;

    private static final float KEY_HEIGHT_DP = 56f;
    /** Key press: the fill flashes for 80 ms (the only feedback; no ripple). */
    private static final int PRESS_FLASH_MS = 80;
    private static final long TRACKPAD_HOLD_MS = 450L;
    private static final long ALT_HOLD_MS = 500L;

    private static final String[] EN_ROW_1 = {
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p"
    };
    private static final String[] EN_ROW_2 = {
            "a", "s", "d", "f", "g", "h", "j", "k", "l"
    };
    private static final String[] EN_ROW_3 = {
            "z", "x", "c", "v", "b", "n", "m"
    };

    // There are 33 Cyrillic letters: 11 + 11 + 9 with shift/backspace as the
    // edge keys of the third row.  The two left over (ё, ъ) are alternates on
    // е and ь, as on the stock Russian keyboard.
    private static final String[] RU_ROW_1 = {
            "й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"
    };
    private static final String[] RU_ROW_2 = {
            "ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"
    };
    private static final String[] RU_ROW_3 = {
            "я", "ч", "с", "м", "и", "т", "ь", "б", "ю"
    };

    private static final String[] SYMBOL_ROW_1 = {
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0"
    };
    private static final String[] SYMBOL_ROW_2 = {
            ".", ",", "?", "!", "'", "\"", "-", "/", ":", ";"
    };
    private static final String[] SYMBOL_ROW_3 = {
            "@", "#", "$", "%", "&", "*", "+", "=", "(", ")"
    };

    private static final String[] EMOJI = {
            "\uD83D\uDE00", "\uD83D\uDE02", "\uD83D\uDE0D", "\uD83D\uDE0A",
            "\uD83D\uDE2D", "\uD83D\uDE0E", "\uD83E\uDD14", "\uD83D\uDE05",
            "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDE4F", "\uD83D\uDC4F",
            "\u2764", "\uD83D\uDD25", "\uD83C\uDF89", "\u2728",
            "\uD83D\uDE21", "\uD83D\uDE22", "\uD83D\uDE34", "\uD83D\uDE18",
            "\uD83D\uDE01", "\uD83D\uDCAF", "\u2705", "\uD83D\uDC4B"
    };

    private static final class Key {
        final String label;
        final String alt;
        final int action;
        final int icon;
        final int textSp;
        final boolean function;

        float left;
        float top;
        float right;
        float bottom;

        Key(String label, String alt, int action, int icon, int textSp, boolean function) {
            this.label = label;
            this.alt = alt;
            this.action = action;
            this.icon = icon;
            this.textSp = textSp;
            this.function = function;
        }
    }

    private static final class Row {
        final List<Key> keys = new ArrayList<>();
        final int layout;

        Row(int layout) {
            this.layout = layout;
        }
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final Path iconPath = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Row> rows = new ArrayList<>();
    private Listener listener;

    private float density;
    private float gap;
    private float unit;
    private float pitch;
    private float nineStart;
    private float zx;
    private float rowHeight;
    private float viewWidth;
    private float viewHeight;
    private float dotX;
    private float dotY;

    private int page = PAGE_LETTERS;
    private boolean russian;
    private boolean caps;

    private Key pressedKey;
    private Key flashKey;
    private long flashStart;
    private int flashDurationMs;
    private Key altKey;
    private boolean altFired;

    private boolean dotPressed;
    private boolean trackpad;
    private boolean ignoreTouch;
    private int trackPointerId = -1;
    private float trackLastX;
    private float trackLastY;
    private float trackX;
    private float trackY;
    private float trackAccX;
    private float trackAccY;

    private final Runnable altRunnable = new Runnable() {
        @Override
        public void run() {
            altFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

    private final Runnable trackpadRunnable = new Runnable() {
        @Override
        public void run() {
            if (!dotPressed) {
                return;
            }
            trackpad = true;
            trackX = trackLastX;
            trackY = trackLastY;
            trackAccX = 0f;
            trackAccY = 0f;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

    public ZtKeyboardView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        setBackgroundColor(COLOR_BACKGROUND);
        setFocusable(true);
        setFocusableInTouchMode(true);

        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        iconPaint.setColor(COLOR_TEXT);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(dp(1.4f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        buildRows();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRussian() {
        return russian;
    }

    /** Called by the service when the IME subtype or system locale is known. */
    public void setRussian(boolean russian) {
        if (this.russian == russian) {
            return;
        }
        this.russian = russian;
        caps = false;
        pressedKey = null;
        buildRows();
        requestLayout();
        layoutRows(getWidth(), getHeight());
        invalidate();
    }

    public void setCaps(boolean caps) {
        if (this.caps != caps) {
            this.caps = caps;
            invalidate();
        }
    }

    /** A new editor starts on the letters page, lowercase. */
    public void resetPage() {
        pressedKey = null;
        caps = false;
        if (page != PAGE_LETTERS) {
            page = PAGE_LETTERS;
            buildRows();
            requestLayout();
            layoutRows(getWidth(), getHeight());
        }
        invalidate();
    }

    private void buildRows() {
        rows.clear();
        if (page == PAGE_LETTERS) {
            if (russian) {
                addTextRow(RU_ROW_1, LAYOUT_ELEVEN);
                addTextRow(RU_ROW_2, LAYOUT_ELEVEN);
                addShiftRow(RU_ROW_3, LAYOUT_ELEVEN_EXT);
            } else {
                addTextRow(EN_ROW_1, LAYOUT_TOP);
                addTextRow(EN_ROW_2, LAYOUT_NINE);
                addShiftRow(EN_ROW_3, LAYOUT_NINE_EXT);
            }
            addBottomRow();
        } else if (page == PAGE_SYMBOLS) {
            addTextRow(SYMBOL_ROW_1, LAYOUT_TOP);
            addTextRow(SYMBOL_ROW_2, LAYOUT_TOP);
            addTextRow(SYMBOL_ROW_3, LAYOUT_TOP);
            addBottomRow();
        } else {
            for (int r = 0; r < 3; r++) {
                Row row = new Row(LAYOUT_GRID8);
                for (int i = 0; i < 8; i++) {
                    row.keys.add(new Key(EMOJI[r * 8 + i], null, ACTION_TEXT,
                            ICON_NONE, 24, false));
                }
                rows.add(row);
            }
            addBottomRow();
        }
    }

    private void addTextRow(String[] labels, int layout) {
        Row row = new Row(layout);
        for (String label : labels) {
            row.keys.add(new Key(label, null, ACTION_TEXT, ICON_NONE, 20, false));
        }
        rows.add(row);
    }

    private void addShiftRow(String[] letters, int layout) {
        Row row = new Row(layout);
        row.keys.add(new Key(null, null, ACTION_SHIFT, ICON_SHIFT, 0, true));
        for (String letter : letters) {
            String alt = null;
            if ("е".equals(letter)) {
                alt = "ё";
            } else if ("ь".equals(letter)) {
                alt = "ъ";
            }
            row.keys.add(new Key(letter, alt, ACTION_TEXT, ICON_NONE, 20, false));
        }
        row.keys.add(new Key(null, null, ACTION_BACKSPACE, ICON_BACKSPACE, 0, true));
        rows.add(row);
    }

    private void addBottomRow() {
        Row row = new Row(LAYOUT_BOTTOM);
        if (page == PAGE_LETTERS) {
            row.keys.add(new Key("&123", null, ACTION_SYMBOLS, ICON_NONE, 13, true));
            row.keys.add(new Key(null, null, ACTION_EMOJI, ICON_SMILEY, 0, true));
            row.keys.add(new Key(",", null, ACTION_TEXT, ICON_NONE, 20, true));
            row.keys.add(spaceKey());
            row.keys.add(new Key(".", null, ACTION_TEXT, ICON_NONE, 20, true));
            row.keys.add(new Key(null, null, ACTION_ENTER, ICON_ENTER, 0, true));
        } else if (page == PAGE_SYMBOLS) {
            row.keys.add(new Key("abc", null, ACTION_LETTERS, ICON_NONE, 13, true));
            row.keys.add(languageKey());
            row.keys.add(new Key(null, null, ACTION_EMOJI, ICON_SMILEY, 0, true));
            row.keys.add(spaceKey());
            row.keys.add(new Key(null, null, ACTION_ENTER, ICON_ENTER, 0, true));
        } else {
            row.keys.add(new Key("abc", null, ACTION_LETTERS, ICON_NONE, 13, true));
            row.keys.add(languageKey());
            row.keys.add(spaceKey());
            row.keys.add(new Key(null, null, ACTION_BACKSPACE, ICON_BACKSPACE, 0, true));
        }
        rows.add(row);
    }

    private Key spaceKey() {
        // mockup: .zt-key.bar keeps the letter fill (#333333), not the function
        // fill; only its label is 13 sp at 80 % white.
        return new Key(null, null, ACTION_SPACE, ICON_NONE, 13, false);
    }

    private Key languageKey() {
        return new Key(null, null, ACTION_LANGUAGE, ICON_NONE, 13, true);
    }

    private void layoutRows(int width, int height) {
        if (width <= 0 || height <= 0 || rows.isEmpty()) {
            return;
        }
        viewWidth = width;
        viewHeight = height;

        gap = dp(1);
        unit = (width - 11 * gap) / 10f;      // the top row's key width
        pitch = unit + gap;
        nineStart = gap + pitch / 2f;         // centred nine-column grid
        zx = nineStart + pitch;               // z / smiley left edge
        rowHeight = (height - (rows.size() + 1) * gap) / rows.size();

        float y = gap;
        for (Row row : rows) {
            placeRow(row, y);
            y += rowHeight + gap;
        }

        dotX = dp(6);
        dotY = rows.get(Math.min(2, rows.size() - 1)).keys.get(0).bottom;
    }

    private void placeRow(Row row, float y) {
        int n = row.keys.size();
        float h = rowHeight;

        for (int i = 0; i < n; i++) {
            Key key = row.keys.get(i);
            key.top = y;
            key.bottom = y + h;
        }

        switch (row.layout) {
            case LAYOUT_TOP: {
                for (int i = 0; i < n; i++) {
                    Key key = row.keys.get(i);
                    key.left = gap + i * pitch;
                    key.right = key.left + unit;
                }
                break;
            }
            case LAYOUT_NINE: {
                for (int i = 0; i < n; i++) {
                    Key key = row.keys.get(i);
                    key.left = nineStart + i * pitch;
                    key.right = key.left + unit;
                }
                break;
            }
            case LAYOUT_NINE_EXT: {
                for (int i = 0; i < n; i++) {
                    Key key = row.keys.get(i);
                    if (i == 0) {
                        key.left = 0f;
                        key.right = nineStart + unit;
                    } else if (i == n - 1) {
                        key.left = nineStart + i * pitch;
                        key.right = viewWidth;
                    } else {
                        key.left = nineStart + i * pitch;
                        key.right = key.left + unit;
                    }
                }
                break;
            }
            case LAYOUT_ELEVEN: {
                float u = (viewWidth - (n + 1) * gap) / n;
                float p = u + gap;
                for (int i = 0; i < n; i++) {
                    Key key = row.keys.get(i);
                    key.left = gap + i * p;
                    key.right = key.left + u;
                }
                break;
            }
            case LAYOUT_ELEVEN_EXT: {
                float u = (viewWidth - (n + 1) * gap) / n;
                float p = u + gap;
                for (int i = 0; i < n; i++) {
                    Key key = row.keys.get(i);
                    if (i == 0) {
                        key.left = 0f;
                        key.right = gap + u;
                    } else if (i == n - 1) {
                        key.left = gap + i * p;
                        key.right = viewWidth;
                    } else {
                        key.left = gap + i * p;
                        key.right = key.left + u;
                    }
                }
                break;
            }
            case LAYOUT_GRID8: {
                float u = (viewWidth - 9 * gap) / 8f;
                float p = u + gap;
                for (int i = 0; i < n; i++) {
                    Key key = row.keys.get(i);
                    key.left = gap + i * p;
                    key.right = key.left + u;
                }
                break;
            }
            case LAYOUT_BOTTOM:
            default:
                placeBottom(row);
                break;
        }
    }

    private void placeBottom(Row row) {
        int n = row.keys.size();
        if (n == 6) {
            // &123 | smiley (z) | comma (x) | space | dot (m) | enter
            setKey(row, 0, 0f, zx - gap);
            setKey(row, 1, zx, zx + unit);
            setKey(row, 2, zx + pitch, zx + pitch + unit);
            setKey(row, 3, zx + 2 * pitch, zx + 6 * pitch - gap);
            setKey(row, 4, zx + 6 * pitch, zx + 6 * pitch + unit);
            setKey(row, 5, zx + 7 * pitch, viewWidth);
        } else if (n == 5) {
            // abc | RU/EN | smiley | space | enter
            setKey(row, 0, 0f, zx - gap);
            setKey(row, 1, zx, zx + unit);
            setKey(row, 2, zx + pitch, zx + pitch + unit);
            setKey(row, 3, zx + 2 * pitch, zx + 7 * pitch - gap);
            setKey(row, 4, zx + 7 * pitch, viewWidth);
        } else {
            // abc | RU/EN | space | backspace
            setKey(row, 0, 0f, zx - gap);
            setKey(row, 1, zx, zx + unit);
            setKey(row, 2, zx + pitch, zx + 7 * pitch - gap);
            setKey(row, 3, zx + 7 * pitch, viewWidth);
        }
    }

    private void setKey(Row row, int index, float left, float right) {
        Key key = row.keys.get(index);
        key.left = left;
        key.right = right;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }

        float g = dp(1);
        int height = (int) Math.ceil(4 * dp(KEY_HEIGHT_DP) + 5 * g);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int available = MeasureSpec.getSize(heightMeasureSpec);
            if (available > 0 && available < height) {
                // Landscape / split-screen: keep four rows, shrink evenly.
                height = available;
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutRows(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(COLOR_BACKGROUND);

        if (trackpad) {
            // Keys hidden; the accent dot shows that the trackpad is live.
            fillPaint.setColor(COLOR_ACCENT);
            canvas.drawCircle(dotX, dotY, dp(3), fillPaint);
            return;
        }

        for (Row row : rows) {
            for (Key key : row.keys) {
                drawKey(canvas, key);
            }
        }

        fillPaint.setColor(COLOR_ACCENT);
        canvas.drawCircle(dotX, dotY, dotPressed ? dp(3) : dp(2), fillPaint);

        if (flashKey != null && flashDurationMs > 0
                && SystemClock.uptimeMillis() - flashStart < flashDurationMs) {
            postInvalidateOnAnimation();
        }
    }

    private void drawKey(Canvas canvas, Key key) {
        boolean active = key == pressedKey || (key.action == ACTION_SHIFT && caps);
        int base = active ? COLOR_PRESSED
                : (key.function ? COLOR_KEY_FN : COLOR_KEY);
        fillPaint.setColor(active ? base : flashFill(key, base));
        // Hard rectangle: no rounded corners, no ripple.
        canvas.drawRect(key.left, key.top, key.right, key.bottom, fillPaint);

        float cx = (key.left + key.right) / 2f;
        float cy = (key.top + key.bottom) / 2f;

        switch (key.action) {
            case ACTION_TEXT: {
                String label = key.label;
                if (caps && label != null && label.length() == 1
                        && Character.isLetter(label.charAt(0))) {
                    label = label.toUpperCase(Locale.ROOT);
                }
                textPaint.setColor(COLOR_TEXT);
                textPaint.setTextSize(sp(key.textSp));
                drawCentered(canvas, label, cx, cy, textPaint);
                break;
            }
            case ACTION_LANGUAGE:
                // Both codes stay visible; the active one is white, the other
                // dim, so the key reads as the RU/EN toggle the spec asks for.
                textPaint.setTextSize(sp(12));
                textPaint.setColor(russian ? COLOR_TEXT : COLOR_TEXT_DIM);
                drawCentered(canvas, "RU", cx, cy - dp(10), textPaint);
                textPaint.setColor(russian ? COLOR_TEXT_DIM : COLOR_TEXT);
                drawCentered(canvas, "EN", cx, cy + dp(10), textPaint);
                break;
            case ACTION_SPACE:
                textPaint.setColor(COLOR_SPACE_TEXT);
                textPaint.setTextSize(sp(13));
                drawCentered(canvas, "space", cx, cy, textPaint);
                break;
            case ACTION_SHIFT:
                drawShiftIcon(canvas, cx, cy);
                break;
            case ACTION_BACKSPACE:
                drawBackspaceIcon(canvas, cx, cy);
                break;
            case ACTION_EMOJI:
                drawSmileyIcon(canvas, cx, cy);
                break;
            case ACTION_ENTER:
                drawEnterIcon(canvas, cx, cy);
                break;
            default:
                break;
        }
    }

    private void drawCentered(Canvas canvas, String text, float cx, float cy, Paint paint) {
        if (text == null) {
            return;
        }
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(text, cx, baseline, paint);
    }

    private void drawShiftIcon(Canvas canvas, float cx, float cy) {
        float scale = dp(15) / 16f;
        float left = cx - 8f * scale;
        float top = cy - 8f * scale;

        iconPath.reset();
        iconPath.moveTo(left + 8f * scale, top + 2.5f * scale);
        iconPath.lineTo(left + 14f * scale, top + 9f * scale);
        iconPath.lineTo(left + 10f * scale, top + 9f * scale);
        iconPath.lineTo(left + 10f * scale, top + 13.5f * scale);
        iconPath.lineTo(left + 6f * scale, top + 13.5f * scale);
        iconPath.lineTo(left + 6f * scale, top + 9f * scale);
        iconPath.lineTo(left + 2f * scale, top + 9f * scale);
        iconPath.close();
        canvas.drawPath(iconPath, iconPaint);
    }

    private void drawBackspaceIcon(Canvas canvas, float cx, float cy) {
        float scale = dp(17) / 18f;
        float left = cx - 9f * scale;
        float top = cy - 8f * scale;

        iconPath.reset();
        iconPath.moveTo(left + 6f * scale, top + 4f * scale);
        iconPath.lineTo(left + 16f * scale, top + 4f * scale);
        iconPath.lineTo(left + 16f * scale, top + 12f * scale);
        iconPath.lineTo(left + 6f * scale, top + 12f * scale);
        iconPath.lineTo(left + 2f * scale, top + 8f * scale);
        iconPath.close();
        iconPath.moveTo(left + 9f * scale, top + 6.5f * scale);
        iconPath.lineTo(left + 12.5f * scale, top + 9.5f * scale);
        iconPath.moveTo(left + 12.5f * scale, top + 6.5f * scale);
        iconPath.lineTo(left + 9f * scale, top + 9.5f * scale);
        canvas.drawPath(iconPath, iconPaint);
    }

    private void drawSmileyIcon(Canvas canvas, float cx, float cy) {
        float r = dp(8);
        canvas.drawCircle(cx, cy, r, iconPaint);

        fillPaint.setColor(COLOR_TEXT);
        canvas.drawCircle(cx - dp(2.8f), cy - dp(2.2f), dp(0.9f), fillPaint);
        canvas.drawCircle(cx + dp(2.8f), cy - dp(2.2f), dp(0.9f), fillPaint);

        iconPath.reset();
        iconPath.moveTo(cx - dp(4f), cy + dp(1.6f));
        iconPath.quadTo(cx, cy + dp(5f), cx + dp(4f), cy + dp(1.6f));
        canvas.drawPath(iconPath, iconPaint);
    }

    private void drawEnterIcon(Canvas canvas, float cx, float cy) {
        float w = dp(7);
        float h = dp(4);
        iconPath.reset();
        iconPath.moveTo(cx - w, cy - h);
        iconPath.lineTo(cx - w, cy + h);
        iconPath.lineTo(cx + w, cy + h);
        iconPath.moveTo(cx + w - dp(4), cy + h - dp(4));
        iconPath.lineTo(cx + w, cy + h);
        iconPath.lineTo(cx + w - dp(4), cy + h + dp(4));
        canvas.drawPath(iconPath, iconPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                ignoreTouch = false;
                if (inDot(x, y)) {
                    dotPressed = true;
                    trackPointerId = event.getPointerId(0);
                    trackLastX = x;
                    trackLastY = y;
                    handler.postDelayed(trackpadRunnable, TRACKPAD_HOLD_MS);
                    invalidate();
                    return true;
                }
                pressedKey = keyAt(x, y);
                flash(pressedKey);
                scheduleAlt(pressedKey);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                // Extra fingers are ignored, including while the trackpad runs.
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (ignoreTouch) {
                    return true;
                }
                if (dotPressed || trackpad) {
                    int index = event.findPointerIndex(trackPointerId);
                    if (index < 0) {
                        return true;
                    }
                    float mx = event.getX(index);
                    float my = event.getY(index);
                    if (!trackpad) {
                        trackLastX = mx;
                        trackLastY = my;
                        return true;
                    }
                    float dx = mx - trackX;
                    float dy = my - trackY;
                    trackX = mx;
                    trackY = my;
                    stepTrackpad(dx, dy);
                    return true;
                }

                Key key = keyAt(x, y);
                if (key != pressedKey) {
                    pressedKey = key;
                    flash(key);
                    scheduleAlt(key);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                if ((dotPressed || trackpad)
                        && event.getPointerId(index) == trackPointerId) {
                    finishTrackpad();
                    // Fingers still down are not a new typing gesture.
                    ignoreTouch = event.getPointerCount() > 1;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (dotPressed || trackpad) {
                    finishTrackpad();
                    ignoreTouch = false;
                    return true;
                }
                if (ignoreTouch) {
                    ignoreTouch = false;
                    return true;
                }
                Key key = pressedKey;
                boolean alt = altFired;
                cancelAlt();
                pressedKey = null;
                if (key != null && key == keyAt(x, y)) {
                    if (alt && key.alt != null) {
                        commit(key.alt);
                    } else {
                        perform(key);
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                ignoreTouch = false;
                if (dotPressed || trackpad) {
                    finishTrackpad();
                    return true;
                }
                cancelAlt();
                pressedKey = null;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    /** Start the 80 ms fill flash on a key; scale 0 means no flash at all. */
    private void flash(Key key) {
        if (key == null) {
            flashKey = null;
            return;
        }
        float scale = 1f;
        try {
            scale = Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {
        }
        if (Float.isNaN(scale) || scale < 0f) {
            scale = 1f;
        }
        flashKey = key;
        flashStart = SystemClock.uptimeMillis();
        flashDurationMs = Math.round(PRESS_FLASH_MS * Math.min(scale, 10f));
        if (flashDurationMs > 0) {
            postInvalidateOnAnimation();
        }
    }

    /** Colour between the key's resting fill and the pressed flash. */
    private int flashFill(Key key, int base) {
        if (key != flashKey || flashDurationMs <= 0) {
            return base;
        }
        long elapsed = SystemClock.uptimeMillis() - flashStart;
        if (elapsed >= flashDurationMs) {
            flashKey = null;
            return base;
        }
        float t = 1f - (float) elapsed / flashDurationMs;
        int r = (int) (((base >> 16) & 0xFF) + (((COLOR_PRESSED >> 16) & 0xFF)
                - ((base >> 16) & 0xFF)) * t);
        int g = (int) (((base >> 8) & 0xFF) + (((COLOR_PRESSED >> 8) & 0xFF)
                - ((base >> 8) & 0xFF)) * t);
        int b = (int) ((base & 0xFF) + ((COLOR_PRESSED & 0xFF) - (base & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void stepTrackpad(float dx, float dy) {
        float stepX = dp(9);
        float stepY = dp(18);
        trackAccX += dx;
        trackAccY += dy;

        while (trackAccX >= stepX) {
            sendArrow(KeyEvent.KEYCODE_DPAD_RIGHT);
            trackAccX -= stepX;
        }
        while (trackAccX <= -stepX) {
            sendArrow(KeyEvent.KEYCODE_DPAD_LEFT);
            trackAccX += stepX;
        }
        while (trackAccY >= stepY) {
            sendArrow(KeyEvent.KEYCODE_DPAD_DOWN);
            trackAccY -= stepY;
        }
        while (trackAccY <= -stepY) {
            sendArrow(KeyEvent.KEYCODE_DPAD_UP);
            trackAccY += stepY;
        }
    }

    private void sendArrow(int keycode) {
        if (listener != null) {
            listener.onArrow(keycode);
        }
    }

    private void finishTrackpad() {
        handler.removeCallbacks(trackpadRunnable);
        dotPressed = false;
        trackpad = false;
        trackPointerId = -1;
        trackAccX = 0f;
        trackAccY = 0f;
        invalidate();
    }

    private void scheduleAlt(Key key) {
        cancelAlt();
        if (key != null && key.alt != null) {
            altKey = key;
            handler.postDelayed(altRunnable, ALT_HOLD_MS);
        }
    }

    private void cancelAlt() {
        handler.removeCallbacks(altRunnable);
        altKey = null;
        altFired = false;
    }

    private boolean inDot(float x, float y) {
        float half = dp(14);
        return x >= dotX - half && x <= dotX + half
                && y >= dotY - half && y <= dotY + half;
    }

    private Key keyAt(float x, float y) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            int n = row.keys.size();
            for (int i = 0; i < n; i++) {
                Key key = row.keys.get(i);
                // Touch targets cover the 1 dp gutters: each key reaches half
                // a gutter past its drawn edge, and the outer keys reach the
                // screen edge, so no pixel is dead.
                float left = i == 0 ? 0f : key.left - gap / 2f;
                float right = i == n - 1 ? viewWidth : key.right + gap / 2f;
                float top = r == 0 ? 0f : key.top - gap / 2f;
                float bottom = r == rows.size() - 1 ? viewHeight : key.bottom + gap / 2f;
                if (x >= left && x < right && y >= top && y < bottom) {
                    return key;
                }
            }
        }
        return null;
    }

    private void perform(Key key) {
        switch (key.action) {
            case ACTION_TEXT:
                commit(caps && key.label.length() == 1
                        && Character.isLetter(key.label.charAt(0))
                        ? key.label.toUpperCase(Locale.ROOT)
                        : key.label);
                break;
            case ACTION_SPACE:
                commit(" ");
                break;
            case ACTION_BACKSPACE:
                if (listener != null) {
                    listener.onBackspace();
                }
                break;
            case ACTION_ENTER:
                if (listener != null) {
                    listener.onEnter();
                }
                break;
            case ACTION_SHIFT:
                // Latched caps, not one-shot shift. Tap again to release.
                caps = !caps;
                invalidate();
                break;
            case ACTION_LANGUAGE:
                russian = !russian;
                caps = false;
                buildRows();
                requestLayout();
                layoutRows(getWidth(), getHeight());
                invalidate();
                if (listener != null) {
                    listener.onLanguageChanged(russian);
                }
                break;
            case ACTION_SYMBOLS:
                page = PAGE_SYMBOLS;
                caps = false;
                buildRows();
                requestLayout();
                layoutRows(getWidth(), getHeight());
                invalidate();
                break;
            case ACTION_LETTERS:
                page = PAGE_LETTERS;
                caps = false;
                buildRows();
                requestLayout();
                layoutRows(getWidth(), getHeight());
                invalidate();
                break;
            case ACTION_EMOJI:
                page = PAGE_EMOJI;
                caps = false;
                buildRows();
                requestLayout();
                layoutRows(getWidth(), getHeight());
                invalidate();
                break;
            default:
                break;
        }
    }

    private void commit(String text) {
        if (listener != null) {
            listener.onText(text);
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
