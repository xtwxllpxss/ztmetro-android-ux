package org.ztmetro.actioncentre;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ztmetro recents prototype.
 *
 * <p>This is the honest half of the recents question. Android 16 does not give
 * an ordinary app the task list: {@code ActivityManager.getRunningTasks()}
 * returns only the caller's own tasks plus HOME unless the caller holds
 * {@code REAL_GET_TASKS}, or the caller's UID is the recents component named by
 * {@code android:string/config_recentsComponentName} (system_server
 * {@code RecentTasks.isCallerRecents()} bypass, AOSP 16). An activity in this
 * APK can therefore render only one of two states:
 *
 * <ul>
 *   <li>the full WP card strip, when this package has been made the recents
 *       component by an RRO + reboot (and it is a factory/privileged package
 *       for the {@code signature|recents} permissions);</li>
 *   <li>the WP frame with a precise statement of what is missing, plus an
 *       optional Usage Access app list, on stock LineageOS 23.2.</li>
 * </ul>
 *
 * <p>The RRO used for the experiment is in
 * {@code evidence/transitions-recents/rro/} and is intentionally not part of
 * either shipped APK.
 */
public final class RecentsActivity extends Activity {

    /** WP page title: lower case, Light, same 58 sp as the port's headers. */
    private static final float TITLE_SP = 58f;
    private static final int CARD_WIDTH_DP = 248;
    private static final int CARD_HEIGHT_DP = 320;
    private static final int CARD_GAP_DP = 8;
    private static final int DISMISS_DP = 56;

    private ActivityManager activityManager;
    private LinearLayout body;
    private TextView status;
    private int touchSlop;
    private int dismissPx;
    private boolean crossAppTasks;

    private final List<ActivityManager.RunningTaskInfo> tasks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        dismissPx = U.dp(this, DISMISS_DP);
        setContentView(buildFrame());
        loadRecents();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @SuppressWarnings("deprecation")
    private View buildFrame() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(U.BLACK);

        final int page = U.dp(this, 24);
        column.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(0, top, 0, bottom);
                return insets;
            }
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(page, U.dp(this, 18), page, U.dp(this, 10));

        TextView title = new TextView(this);
        title.setText(R.string.recents_title);
        title.setTypeface(U.light(this));
        title.setTextSize(TITLE_SP);
        title.setTextColor(U.WHITE);
        title.setIncludeFontPadding(false);
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView caps = new TextView(this);
        caps.setText(R.string.recents_caps);
        caps.setTypeface(U.semibold(this));
        caps.setTextSize(11f);
        caps.setLetterSpacing(0.08f);
        caps.setTextColor(U.WHITE_50);
        caps.setPadding(0, U.dp(this, 2), 0, 0);
        header.addView(caps, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View rule = new View(this);
        rule.setBackgroundColor(0x33FFFFFF);
        header.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 1)));

        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        column.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTypeface(U.regular(this));
        status.setTextSize(11f);
        status.setTextColor(U.WHITE_40);
        status.setPadding(page, U.dp(this, 6), page, U.dp(this, 8));
        column.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return column;
    }

    // ---------------------------------------------------------------- data --

    private void loadRecents() {
        body.removeAllViews();
        tasks.clear();
        crossAppTasks = false;

        List<ActivityManager.RunningTaskInfo> running = null;
        try {
            running = activityManager.getRunningTasks(30);
        } catch (SecurityException ignored) {
            // No GET_TASKS / REAL_GET_TASKS: the list stays empty.
        }
        if (running != null) {
            for (ActivityManager.RunningTaskInfo info : running) {
                String pkg = packageOf(info);
                if (TextUtils.isEmpty(pkg) || getPackageName().equals(pkg)
                        || "org.ztmetro.start".equals(pkg)) {
                    continue;
                }
                tasks.add(info);
                if (!"com.android.launcher3".equals(pkg)) {
                    crossAppTasks = true;
                }
            }
        }

        if (crossAppTasks) {
            showTaskStrip();
            status.setText(getString(R.string.recents_status_tasks, tasks.size()));
            return;
        }

        showNoAccessFrame();
        showUsageList();
        status.setText(isUsageAccessGranted()
                ? getString(R.string.recents_status_usage_only)
                : getString(R.string.recents_status_no_access));
    }

    private void showTaskStrip() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(U.dp(this, 18), U.dp(this, 4), U.dp(this, 18), U.dp(this, 8));
        scroll.setClipToPadding(false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (ActivityManager.RunningTaskInfo info : tasks) {
            row.addView(taskCard(info), cardParams());
        }
        scroll.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                U.dp(this, CARD_WIDTH_DP), U.dp(this, CARD_HEIGHT_DP));
        params.rightMargin = U.dp(this, CARD_GAP_DP);
        params.topMargin = U.dp(this, 4);
        return params;
    }

    private View taskCard(final ActivityManager.RunningTaskInfo info) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(U.dp(this, 16), U.dp(this, 16), U.dp(this, 16), U.dp(this, 16));

        String pkg = packageOf(info);
        int color = U.OFF;
        ActivityManager.TaskDescription description = info.taskDescription;
        if (description != null && description.getPrimaryColor() != 0) {
            color = cardColor(description.getPrimaryColor());
        }
        // A flat WP card, not a Material elevated surface.
        card.setBackgroundColor(color);

        Drawable icon = null;
        String label = null;
        if (description != null) {
            label = description.getLabel();
            if (description.getIcon() != null) {
                icon = new BitmapDrawable(getResources(), description.getIcon());
            }
        }
        if (label == null) {
            label = appLabel(pkg);
        }
        if (icon == null) {
            icon = appIcon(pkg);
        }

        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(image, new LinearLayout.LayoutParams(
                U.dp(this, 52), U.dp(this, 52)));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTypeface(U.light(this));
        name.setTextSize(24f);
        name.setTextColor(U.WHITE);
        name.setMaxLines(2);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = U.dp(this, 14);
        card.addView(name, nameParams);

        TextView detail = new TextView(this);
        detail.setText(getString(R.string.recents_task_detail, info.taskId, pkg));
        detail.setTypeface(U.regular(this));
        detail.setTextSize(11f);
        detail.setTextColor(U.WHITE_70);
        detail.setMaxLines(2);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = U.dp(this, 6);
        card.addView(detail, detailParams);

        View spacer = new View(this);
        card.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText(R.string.recents_swipe_hint);
        hint.setTypeface(U.semibold(this));
        hint.setTextSize(10f);
        hint.setLetterSpacing(0.06f);
        hint.setTextColor(U.WHITE_50);
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTask(info.taskId);
            }
        });
        card.setOnTouchListener(new SwipeUpDismiss(card, info.taskId));
        return card;
    }

    private void openTask(int taskId) {
        try {
            activityManager.moveTaskToFront(taskId, 0);
            finish();
        } catch (SecurityException e) {
            toast(R.string.recents_open_denied);
        } catch (Throwable ignored) {
            toast(R.string.recents_open_denied);
        }
    }

    /**
     * Swipe-up dismissal. The public task API cannot remove another app's task:
     * {@code ActivityManager.removeTask()} is hidden and needs
     * {@code REMOVE_TASKS} (signature|recents|role). We attempt it anyway so
     * the permission boundary is visible, and restore the card when the
     * platform refuses instead of pretending it worked.
     */
    private final class SwipeUpDismiss implements View.OnTouchListener {
        private final View card;
        private final int taskId;
        private float downY;
        private boolean dragging;

        SwipeUpDismiss(View card, int taskId) {
            this.card = card;
            this.taskId = taskId;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = event.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - downY;
                    if (!dragging && dy < -touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        float target = Math.min(0f, dy);
                        card.setTranslationY(target);
                        float fade = 1f + target / Math.max(1f, card.getHeight());
                        card.setAlpha(Math.max(0.2f, fade));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float up = event.getRawY() - downY;
                    if (dragging && up < -dismissPx) {
                        dismiss();
                    } else {
                        card.animate().translationY(0f).alpha(1f).setDuration(140).start();
                        if (!dragging) {
                            card.performClick();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    card.animate().translationY(0f).alpha(1f).setDuration(140).start();
                    return true;
                default:
                    return false;
            }
        }

        private void dismiss() {
            card.animate().translationY(-card.getHeight()).alpha(0f)
                    .setDuration(180)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (removeTask(taskId)) {
                                if (card.getParent() instanceof ViewGroup) {
                                    ((ViewGroup) card.getParent()).removeView(card);
                                }
                            } else {
                                card.animate().translationY(0f).alpha(1f)
                                        .setDuration(180).start();
                                toast(R.string.recents_dismiss_denied);
                            }
                        }
                    }).start();
        }
    }

    private boolean removeTask(int taskId) {
        try {
            Method remove = ActivityManager.class.getMethod("removeTask", int.class);
            Object result = remove.invoke(activityManager, taskId);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable denied) {
            return false;
        }
    }

    // --------------------------------------------------- no-access frame --

    private void showNoAccessFrame() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(U.dp(this, 24), U.dp(this, 10), U.dp(this, 24), U.dp(this, 10));

        TextView heading = new TextView(this);
        heading.setText(R.string.recents_no_access_title);
        heading.setTypeface(U.semibold(this));
        heading.setTextSize(20f);
        heading.setTextColor(U.WHITE);
        heading.setIncludeFontPadding(false);
        column.addView(heading, fullWidth());

        TextView bodyText = new TextView(this);
        bodyText.setText(R.string.recents_no_access_body);
        bodyText.setTypeface(U.regular(this));
        bodyText.setTextSize(13f);
        bodyText.setTextColor(U.WHITE_70);
        bodyText.setLineSpacing(U.dp(this, 2), 1f);
        LinearLayout.LayoutParams bodyParams = fullWidth();
        bodyParams.topMargin = U.dp(this, 10);
        column.addView(bodyText, bodyParams);

        TextView changeTitle = new TextView(this);
        changeTitle.setText(R.string.recents_change_title);
        changeTitle.setTypeface(U.semibold(this));
        changeTitle.setTextSize(11f);
        changeTitle.setLetterSpacing(0.08f);
        changeTitle.setTextColor(U.ACCENT);
        LinearLayout.LayoutParams changeParams = fullWidth();
        changeParams.topMargin = U.dp(this, 18);
        column.addView(changeTitle, changeParams);

        TextView change = new TextView(this);
        change.setText(R.string.recents_change_body);
        change.setTypeface(U.regular(this));
        change.setTextSize(12f);
        change.setTextColor(U.WHITE_70);
        change.setLineSpacing(U.dp(this, 2), 1f);
        change.setPadding(U.dp(this, 10), U.dp(this, 0), 0, 0);
        // Accent rule on the left of the system-change block.
        change.setBackgroundColor(0x00000000);
        LinearLayout.LayoutParams changeBodyParams = fullWidth();
        changeBodyParams.topMargin = U.dp(this, 6);
        column.addView(change, changeBodyParams);

        scroll.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void showUsageList() {
        if (!isUsageAccessGranted()) {
            return;
        }
        List<UsageStats> stats;
        try {
            UsageStatsManager manager =
                    (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    now - 3L * 24L * 60L * 60L * 1000L, now);
        } catch (Throwable denied) {
            return;
        }
        if (stats == null) {
            return;
        }
        stats = newestPerPackage(stats);
        if (stats.isEmpty()) {
            return;
        }

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(U.dp(this, 24), U.dp(this, 8), U.dp(this, 24), U.dp(this, 8));

        TextView caps = new TextView(this);
        caps.setText(R.string.recents_usage_title);
        caps.setTypeface(U.semibold(this));
        caps.setTextSize(11f);
        caps.setLetterSpacing(0.08f);
        caps.setTextColor(U.WHITE_50);
        section.addView(caps, fullWidth());

        int shown = 0;
        for (final UsageStats stat : stats) {
            final String pkg = stat.getPackageName();
            if (pkg == null || getPackageName().equals(pkg)
                    || "com.android.systemui".equals(pkg)
                    || "com.android.launcher3".equals(pkg)
                    || "org.ztmetro.start".equals(pkg)) {
                continue;
            }
            if (shown++ >= 8) {
                break;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, U.dp(this, 6), 0, U.dp(this, 6));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(appIcon(pkg));
            row.addView(icon, new LinearLayout.LayoutParams(
                    U.dp(this, 32), U.dp(this, 32)));

            TextView label = new TextView(this);
            label.setText(appLabel(pkg));
            label.setTypeface(U.regular(this));
            label.setTextSize(15f);
            label.setTextColor(U.WHITE);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.leftMargin = U.dp(this, 12);
            row.addView(label, labelParams);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchPackage(pkg);
                }
            });
            section.addView(row, fullWidth());
        }
        body.addView(section, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void launchPackage(String pkg) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            toast(R.string.recents_open_denied);
            return;
        }
        try {
            startActivity(launch);
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(R.string.recents_open_denied);
        }
    }

    // ------------------------------------------------------------ helpers --

    private boolean isUsageAccessGranted() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<UsageStats> newestPerPackage(List<UsageStats> stats) {
        List<UsageStats> sorted = new ArrayList<>(stats);
        Collections.sort(sorted, new Comparator<UsageStats>() {
            @Override
            public int compare(UsageStats a, UsageStats b) {
                return Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed());
            }
        });
        Set<String> seen = new HashSet<>();
        List<UsageStats> out = new ArrayList<>();
        for (UsageStats stat : sorted) {
            if (seen.add(stat.getPackageName())) {
                out.add(stat);
            }
        }
        return out;
    }

    private static int cardColor(int color) {
        // Darken the app's primary colour so the white WP label stays legible
        // on light-themed apps; there is no screenshot to put behind it.
        return Color.rgb((int) (Color.red(color) * 0.58f),
                (int) (Color.green(color) * 0.58f),
                (int) (Color.blue(color) * 0.58f));
    }

    private String packageOf(ActivityManager.RunningTaskInfo info) {
        if (info.topActivity != null) {
            return info.topActivity.getPackageName();
        }
        if (info.baseActivity != null) {
            return info.baseActivity.getPackageName();
        }
        return null;
    }

    private String appLabel(String pkg) {
        if (pkg == null) {
            return "";
        }
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    private Drawable appIcon(String pkg) {
        if (pkg == null) {
            return null;
        }
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
