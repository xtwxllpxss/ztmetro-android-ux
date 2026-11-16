package org.ztmetro.start;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-edge A-Z jump strip. Tap or drag to jump to a section; while dragging
 * the letters stretch vertically (the WP semantic-zoom cue) and the active
 * letter grows the most. The list's sticky section letter stretches with it.
 * Paints are precomputed, so onDraw allocates nothing.
 */
public final class AlphabetIndexView extends View {

    public interface Listener {
        void onLetter(String letter);
    }

    private final ArrayList<String> letters = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float textSize;
    private final int activeColor;
    private final int idleColor;
    private final int sidePad;
    private int active = -1;
    private boolean dragging;
    private Listener listener;

    public AlphabetIndexView(Context context) {
        super(context);
        activeColor = context.getResources().getColor(R.color.metro_accent, null);
        idleColor = context.getResources().getColor(R.color.metro_text_muted, null);
        sidePad = MetroUi.dimen(context, R.dimen.metro_start_top_pad);
        MetroUi.Text section = MetroUi.text(context, R.style.TextAppearance_Metro_SectionHeader);
        textSize = section.sizePx;
        paint.setTextSize(textSize);
        paint.setTypeface(section.typeface == null ? Typeface.SANS_SERIF : section.typeface);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setLetters(List<String> newLetters) {
        letters.clear();
        if (newLetters != null) {
            letters.addAll(newLetters);
        }
        active = -1;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MetroUi.dimen(getContext(), R.dimen.metro_round_button)
                + 2 * MetroUi.dimen(getContext(), R.dimen.metro_tile_gap);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (height <= 0) {
            height = 480;
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = letters.size();
        if (count == 0) {
            return;
        }
        float usable = getHeight() - 2f * sidePad;
        float slot = usable / count;
        float centerX = getWidth() / 2f;
        float baselineOffset = -(paint.ascent() + paint.descent()) / 2f;
        for (int i = 0; i < count; i++) {
            float centerY = sidePad + slot * (i + 0.5f);
            float stretch = dragging ? (i == active ? 1.55f : 1.15f) : 1f;
            paint.setColor(i == active && dragging ? activeColor : idleColor);
            canvas.save();
            canvas.translate(centerX, centerY);
            canvas.scale(1f, stretch);
            canvas.drawText(letters.get(i), 0f, baselineOffset, paint);
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = true;
                select(event.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    select(event.getY());
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                active = -1;
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private void select(float y) {
        int count = letters.size();
        if (count == 0) {
            return;
        }
        float usable = Math.max(1f, getHeight() - 2f * sidePad);
        float slot = usable / count;
        int index = (int) ((y - sidePad) / slot);
        if (index < 0) {
            index = 0;
        } else if (index >= count) {
            index = count - 1;
        }
        if (index == active) {
            return;
        }
        active = index;
        invalidate();
        if (dragging) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (listener != null) {
            listener.onLetter(letters.get(index));
        }
    }
}
