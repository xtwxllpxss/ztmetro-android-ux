package org.ztmetro.start;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small bottom Metro sheet used for tile long-press (open / resize / unpin)
 * and app-list long-press (pin). White panel with black rows, 0.72 scrim,
 * both from the tokens; no Material components.
 */
final class MetroSheet {

    interface Listener {
        void onAction(int index);
    }

    private MetroSheet() {
    }

    static void show(final Activity activity, String title, String[] actions, final Listener listener) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int panelColor = activity.getResources().getColor(R.color.metro_chrome_menu_panel, activity.getTheme());
        int textColor = activity.getResources().getColor(R.color.metro_chrome_menu_text, activity.getTheme());
        int secondary = activity.getResources().getColor(R.color.metro_text_secondary, activity.getTheme());
        int rowHeight = activity.getResources().getDimensionPixelSize(R.dimen.metro_list_row_height);
        int pad = MetroUi.dimen(activity, R.dimen.metro_page_margin);
        float scrim = activity.getResources().getFraction(R.fraction.metro_scrim_alpha, 1, 1);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(panelColor);

        TextView titleView = new TextView(activity);
        titleView.setTextAppearance(R.style.TextAppearance_Metro_Caps);
        titleView.setTextColor(secondary);
        titleView.setPadding(pad, pad, pad, pad / 2);
        titleView.setText(title);
        panel.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < actions.length; i++) {
            final int index = i;
            TextView row = new TextView(activity);
            row.setTextAppearance(R.style.TextAppearance_Metro_BodyLarge);
            row.setTextColor(textColor);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, 0, pad, 0);
            row.setMinHeight(rowHeight);
            row.setBackground(pressedBackground(activity));
            row.setClickable(true);
            row.setText(actions[i]);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    listener.onAction(index);
                }
            });
            panel.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView cancel = new TextView(activity);
        cancel.setTextAppearance(R.style.TextAppearance_Metro_BodyLarge);
        cancel.setTextColor(secondary);
        cancel.setGravity(Gravity.CENTER_VERTICAL);
        cancel.setPadding(pad, 0, pad, 0);
        cancel.setMinHeight(rowHeight);
        cancel.setBackground(pressedBackground(activity));
        cancel.setClickable(true);
        cancel.setText(R.string.sheet_cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        panel.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(scrim);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static StateListDrawable pressedBackground(Activity activity) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(activity.getResources().getColor(
                        R.color.metro_surface_field, activity.getTheme())));
        drawable.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return drawable;
    }
}
