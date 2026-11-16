package org.ztmetro.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ztMetro keyboard surface.
 *
 * Four rows of square keys, 1dp black gutters, black window, lowercase 20sp
 * letters. The bottom row is the function row: RU/EN, shift, wide space,
 * backspace, enter. No drawable backgrounds, no StateListAnimator, no
 * ripple: the only feedback is the pressed fill #333333.
 *
 * The Latin layout uses 10 columns. Russian needs 33 letters plus the shared
 * function row, so it uses 11 columns (11/11/11) while staying 1:1; that is
 * the one pace deviation from the 10-column brief, forced by the alphabet.
 *
 * Unit keys are square: width == height, computed from the incoming width as
 * (width - (columns + 1) * 1dp) / columns, so the grid always fits the
 * available width. In landscape the IME window is as wide as the screen, so
 * the same formula scales the keys up while keeping 1:1 (>= 32dp on the
 * 640dp-wide target).
 */
public final class ZtKeyboardView extends View {

    /** Callback surface implemented by the InputMethodService. */
    public interface Listener {
        void onText(String text);

        void onBackspace();

        void onEnter();

        void onLanguageChanged(boolean russian);
    }

    private static final int COLOR_BACKGROUND = 0xFF000000;
    private static final int COLOR_KEY = 0xFF1F1F1F;       // ztmetro letter-key dark
    private static final int COLOR_KEY_FN = 0xFF121212;    // function keys sit darker
    private static final int COLOR_PRESSED = 0xFF333333;   // ztmetro latched key fill
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SPACE_TEXT = 0xCCFFFFFF;

    private static final int ACTION_TEXT = 1;
    private static final int ACTION_SPACE = 2;
    private static final int ACTION_BACKSPACE = 3;
    private static final int ACTION_ENTER = 4;
    private static final int ACTION_SHIFT = 5;
    private static final int ACTION_LANGUAGE = 6;

    private static final int ICON_NONE = 0;
    private static final int ICON_SHIFT = 1;
    private static final int ICON_BACKSPACE = 2;

    private static final String[] EN_ROW_1 = {
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p"
    };
    private static final String[] EN_ROW_2 = {
            "a", "s", "d", "f", "g", "h", "j", "k", "l"
    };
    private static final String[] EN_ROW_3 = {
            "z", "x", "c", "v", "b", "n", "m", ",", ".", "'"
    };
    private static final String[][] EN_ROWS = {EN_ROW_1, EN_ROW_2, EN_ROW_3};

    // 11/11/11: every Russian letter is reachable by one tap.
    private static final String[] RU_ROW_1 = {
            "й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"
    };
    private static final String[] RU_ROW_2 = {
            "ъ", "ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж"
    };
    private static final String[] RU_ROW_3 = {
            "э", "я", "ч", "с", "м", "и", "т", "ь", "б", "ю", "ё"
    };
    private static final String[][] RU_ROWS = {RU_ROW_1, RU_ROW_2, RU_ROW_3};

    private static final class Key {
        final String label;
        final int action;
        final float units;
        final boolean function;
        final int icon;

        float left;
        float top;
        float right;
        float bottom;

        Key(String label, int action, float units, boolean function, int icon) {
            this.label = label;
            this.action = action;
            this.units = units;
            this.function = function;
            this.icon = icon;
        }
    }

    private static final class Row {
        final List<Key> keys = new ArrayList<>();
        final boolean function;

        Row(boolean function) {
            this.function = function;
        }
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint functionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final Path iconPath = new Path();

    private final List<Row> rows = new ArrayList<>();
    private Listener listener;

    private float density;
    private float gap;
    private float unit;
    private int columns = 10;

    private boolean russian;
    private boolean caps;
    private Key pressed;

    public ZtKeyboardView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        setBackgroundColor(COLOR_BACKGROUND);
        setFocusable(true);
        setFocusableInTouchMode(true);

        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextSize(sp(20));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        functionPaint.setColor(COLOR_TEXT);
        functionPaint.setTextSize(sp(13));
        functionPaint.setTextAlign(Paint.Align.CENTER);
        functionPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

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
        pressed = null;
        buildRows();
        layoutKeys(getWidth());
        invalidate();
    }

    public void setCaps(boolean caps) {
        if (this.caps != caps) {
            this.caps = caps;
            invalidate();
        }
    }

    private void buildRows() {
        rows.clear();
        String[][] layout = russian ? RU_ROWS : EN_ROWS;
        columns = russian ? 11 : 10;

        for (String[] labels : layout) {
            Row row = new Row(false);
            for (String label : labels) {
                row.keys.add(new Key(label, ACTION_TEXT, 1f, false, ICON_NONE));
            }
            rows.add(row);
        }

        Row functionRow = new Row(true);
        functionRow.keys.add(new Key(null, ACTION_LANGUAGE, 1.5f, true, ICON_NONE));
        functionRow.keys.add(new Key(null, ACTION_SHIFT, 1.5f, true, ICON_SHIFT));
        functionRow.keys.add(new Key(null, ACTION_SPACE, 1f, true, ICON_NONE));
        functionRow.keys.add(new Key(null, ACTION_BACKSPACE, 1.5f, true, ICON_BACKSPACE));
        functionRow.keys.add(new Key(null, ACTION_ENTER, 1.5f, true, ICON_NONE));
        rows.add(functionRow);
    }

    private void layoutKeys(int width) {
        if (width <= 0) {
            return;
        }
        gap = dp(1);
        unit = (width - (columns + 1) * gap) / columns;

        float y = gap;
        for (Row row : rows) {
            if (!row.function) {
                int count = row.keys.size();
                float rowWidth = count * unit + (count - 1) * gap;
                float x = (width - rowWidth) / 2f;
                for (Key key : row.keys) {
                    key.left = x;
                    key.top = y;
                    key.right = x + unit;
                    key.bottom = y + unit;
                    x += unit + gap;
                }
            } else {
                // Fixed part: language 1.5u + shift 1.5u + backspace 1.5u +
                // enter 1.5u = 6u. Space absorbs the rest so the row ends
                // exactly at the right 1dp gutter.
                float inner = width - 2 * gap;
                float spaceWidth = inner - 4 * gap - 6 * unit;
                float x = gap;
                for (Key key : row.keys) {
                    float keyWidth = key.action == ACTION_SPACE ? spaceWidth : key.units * unit;
                    key.left = x;
                    key.top = y;
                    key.right = x + keyWidth;
                    key.bottom = y + unit;
                    x += keyWidth + gap;
                }
            }
            y += unit + gap;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }

        int cols = russian ? 11 : 10;
        float g = dp(1);
        float u = (width - (cols + 1) * g) / cols;
        int height = (int) Math.ceil(4 * u + 5 * g);

        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            int available = MeasureSpec.getSize(heightMeasureSpec);
            if (available > 0 && available < height) {
                float fit = (available - 5 * g) / 4f;
                // Landscape 640dp gives ~57dp (RU) / ~63dp (EN), so the fit
                // only matters if the IME window itself is short.
                if (fit >= dp(32)) {
                    u = fit;
                }
                height = available;
            }
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(COLOR_BACKGROUND);
        for (Row row : rows) {
            for (Key key : row.keys) {
                drawKey(canvas, key);
            }
        }
    }

    private void drawKey(Canvas canvas, Key key) {
        boolean isPressed = key == pressed;
        boolean activeShift = key.action == ACTION_SHIFT && caps;

        fillPaint.setColor(isPressed || activeShift
                ? COLOR_PRESSED
                : (key.function ? COLOR_KEY_FN : COLOR_KEY));
        // Deliberately a hard rectangle: no rounded corners, no ripple.
        canvas.drawRect(key.left, key.top, key.right, key.bottom, fillPaint);

        float cx = (key.left + key.right) / 2f;
        float cy = (key.top + key.bottom) / 2f;

        switch (key.action) {
            case ACTION_TEXT: {
                String label = caps ? key.label.toUpperCase(Locale.ROOT) : key.label;
                drawCentered(canvas, label, cx, cy, textPaint);
                break;
            }
            case ACTION_LANGUAGE:
                drawCentered(canvas, russian ? "RU" : "EN", cx, cy, functionPaint);
                break;
            case ACTION_SPACE:
                functionPaint.setColor(COLOR_SPACE_TEXT);
                drawCentered(canvas, "space", cx, cy, functionPaint);
                functionPaint.setColor(COLOR_TEXT);
                break;
            case ACTION_ENTER:
                drawCentered(canvas, "enter", cx, cy, functionPaint);
                break;
            case ACTION_SHIFT:
                drawShiftIcon(canvas, cx, cy);
                break;
            case ACTION_BACKSPACE:
                drawBackspaceIcon(canvas, cx, cy);
                break;
            default:
                break;
        }
    }

    private void drawCentered(Canvas canvas, String text, float cx, float cy, Paint paint) {
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressed = keyAt(event.getX(), event.getY());
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                Key key = keyAt(event.getX(), event.getY());
                if (key != pressed) {
                    pressed = key;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                Key key = pressed;
                pressed = null;
                if (key != null && key == keyAt(event.getX(), event.getY())) {
                    perform(key);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                pressed = null;
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    private Key keyAt(float x, float y) {
        for (Row row : rows) {
            for (Key key : row.keys) {
                if (x >= key.left && x < key.right && y >= key.top && y < key.bottom) {
                    return key;
                }
            }
        }
        return null;
    }

    private void perform(Key key) {
        switch (key.action) {
            case ACTION_TEXT: {
                String text = caps ? key.label.toUpperCase(Locale.ROOT) : key.label;
                if (listener != null) {
                    listener.onText(text);
                }
                break;
            }
            case ACTION_SPACE:
                if (listener != null) {
                    listener.onText(" ");
                }
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
                // "shift (caps)": latched caps, not a one-shot shift. Tap again
                // to return to lowercase.
                caps = !caps;
                invalidate();
                break;
            case ACTION_LANGUAGE:
                russian = !russian;
                caps = false;
                pressed = null;
                buildRows();
                layoutKeys(getWidth());
                invalidate();
                if (listener != null) {
                    listener.onLanguageChanged(russian);
                }
                break;
            default:
                break;
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
