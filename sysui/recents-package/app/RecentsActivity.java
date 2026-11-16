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
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ztmetro recents surface, installed by the {@code ztmetro-recents} Magisk module.
 *
 * <p>This activity is the component named by
 * {@code android:string/config_recentsComponentName} after the module's static
 * framework RRO is applied and the phone reboots. Three separate platform reads
 * make it work, and the module covers all three:
 *
 * <ul>
 *   <li>system_server {@code RecentTasks.loadRecentsComponent()} reads the string
 *       at boot and records this package as the recents UID. From then on
 *       {@code ActivityTaskManagerService.isGetTasksAllowed()} returns true for
 *       this UID before REAL_GET_TASKS is even checked, so the deprecated public
 *       {@link ActivityManager#getRunningTasks(int)} returns every task;</li>
 *   <li>PackageManagerService reads the same string into {@code mRecentsPackage}
 *       (only if the APK is a factory/system package), and
 *       {@code PermissionManagerServiceImpl} grants the {@code signature|recents}
 *       permissions REMOVE_TASKS and MANAGE_ACTIVITY_TASKS to known package
 *       {@code PACKAGE_RECENTS}; that is what makes swipe-up dismissal work;</li>
 *   <li>the priv-app allowlist grants REAL_GET_TASKS and
 *       START_TASKS_FROM_RECENTS, which are {@code signature|privileged}.</li>
 * </ul>
 *
 * <p>What the module deliberately does not change: the recents key/gesture is
 * drawn by WMShell + Launcher3QuickStep, which launch their own overview from
 * their own intent (see {@code RecentsAnimation}: the target type is RECENTS only
 * when the client intent component equals {@code config_recentsComponentName}).
 * This activity therefore has to be opened explicitly for now; the gesture keeps
 * using QuickStep until WMShell is patched.
 *
 * <p>If the activity runs without the RRO/privilege (data install, module not
 * enabled, no reboot), it says so precisely and logs it once instead of faking a
 * task list.
 */
public final class RecentsActivity extends Activity {

    private static final String TAG = "ztmetro-recents";

    private static final String PERM_REMOVE_TASKS = "android.permission.REMOVE_TASKS";
    private static final String PERM_REAL_GET_TASKS = "android.permission.REAL_GET_TASKS";

    /** WP page title: lower case, Light, same 58 sp as the port's headers. */
    private static final float TITLE_SP = 58f;
    private static final int CARD_WIDTH_DP = 248;
    private static final int CARD_HEIGHT_DP = 320;
    private static final int CARD_GAP_DP = 8;
    private static final int DISMISS_DP = 56;

    /** One warning per process, not one per onResume(). */
    private static final AtomicBoolean ONLY_OWN_LOGGED = new AtomicBoolean(false);

    private ActivityManager activityManager;
    private LinearLayout body;
    private TextView status;
    private int touchSlop;
    private int dismissPx;
    private boolean crossAppTasks;
    private boolean removeTasksGranted;
    private boolean systemPackage;
    private String lastRemoveFailure;

    private final List<ActivityManager.RunningTaskInfo> tasks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        dismissPx = U.dp(this, DISMISS_DP);
        removeTasksGranted = checkSelfPermission(PERM_REMOVE_TASKS)
                == PackageManager.PERMISSION_GRANTED;
        systemPackage = isSystemPackage();
        setContentView(buildFrame());
        loadRecents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from an opened task must show the new order, not the old one.
        loadRecents();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
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
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        header.setPadding(page, U.dp(this, 18), page, U.dp(this, 10));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.recents_title);
        title.setTypeface(U.light(this));
        title.setTextSize(TITLE_SP);
        title.setTextColor(U.WHITE);
        title.setIncludeFontPadding(false);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView caps = new TextView(this);
        caps.setText(R.string.recents_caps);
        caps.setTypeface(U.semibold(this));
        caps.setTextSize(11f);
        caps.setLetterSpacing(0.08f);
        caps.setTextColor(U.WHITE_50);
        caps.setPadding(0, U.dp(this, 2), 0, 0);
        titles.addView(caps, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View rule = new View(this);
        rule.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 1));
        ruleParams.topMargin = U.dp(this, 12);
        titles.addView(rule, ruleParams);

        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titles, titlesParams);

        TextView close = new TextView(this);
        close.setText(R.string.recents_close);
        close.setTypeface(U.light(this));
        close.setTextSize(34f);
        close.setTextColor(U.WHITE_70);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(getString(R.string.recents_close_desc));
        close.setPadding(U.dp(this, 8), 0, 0, U.dp(this, 8));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        } catch (SecurityException denied) {
            Log.w(TAG, "getRunningTasks denied: " + denied);
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
            status.setText(getString(R.string.recents_status_tasks_v2, tasks.size(),
                    permissionWord(), packageWord()));
            return;
        }

        // No cross-app tasks. Either the list is genuinely empty (nothing else was
        // opened since boot) or system_server is not treating this UID as the
        // recents component. One warning per process names the likely cause, so a
        // logcat reader can tell the two cases apart from the commands in the
        // module README without guessing.
        if (ONLY_OWN_LOGGED.compareAndSet(false, true)) {
            Log.w(TAG, "getRunningTasks() returned no cross-app tasks (granted="
                    + checkSelfPermission(PERM_REAL_GET_TASKS) + ", system=" + systemPackage
                    + ", remove=" + removeTasksGranted + "); if apps have been used, "
                    + "system_server does not see org.ztmetro.actioncentre as the recents "
                    + "component. Install the ztmetro-recents module and reboot, then check "
                    + "'cmd overlay list' for org.ztmetro.overlay.recents.");
        }
        showNoAccessFrame();
        showUsageList();
        status.setText(getString(R.string.recents_status_empty, permissionWord(),
                packageWord()));
    }

    private String permissionWord() {
        return getString(removeTasksGranted ? R.string.recents_perm_granted
                : R.string.recents_perm_denied);
    }

    private String packageWord() {
        return getString(systemPackage ? R.string.recents_pkg_system
                : R.string.recents_pkg_data);
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
        // A flat WP card, not a Material elevated surface: no screenshot, no
        // elevation, no rounded corner. Icon + label + colour only.
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

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        top.addView(image, new LinearLayout.LayoutParams(
                U.dp(this, 52), U.dp(this, 52)));

        View gap = new View(this);
        top.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView dismiss = new TextView(this);
        dismiss.setText(R.string.recents_dismiss);
        dismiss.setTypeface(U.light(this));
        dismiss.setTextSize(26f);
        dismiss.setTextColor(U.WHITE_70);
        dismiss.setGravity(Gravity.CENTER);
        dismiss.setContentDescription(getString(R.string.recents_dismiss_desc));
        dismiss.setPadding(U.dp(this, 6), 0, U.dp(this, 6), 0);
        dismiss.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissCard(card, info.taskId);
            }
        });
        top.addView(dismiss, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        } catch (SecurityException denied) {
            Log.w(TAG, "moveTaskToFront(" + taskId + ") denied", denied);
            toast(R.string.recents_open_denied);
        } catch (Throwable other) {
            Log.w(TAG, "moveTaskToFront(" + taskId + ") failed", other);
            toast(R.string.recents_open_denied);
        }
    }

    /** Close button on a card: same path as the swipe, no gesture needed. */
    private void dismissCard(final View card, final int taskId) {
        card.animate().translationY(-card.getHeight()).alpha(0f)
                .setDuration(180)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finishDismiss(card, taskId);
                    }
                }).start();
    }

    private void finishDismiss(View card, int taskId) {
        if (removeTask(taskId)) {
            if (card.getParent() instanceof ViewGroup) {
                ((ViewGroup) card.getParent()).removeView(card);
            }
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).taskId == taskId) {
                    tasks.remove(i);
                    break;
                }
            }
            status.setText(getString(R.string.recents_status_tasks_v2, tasks.size(),
                    permissionWord(), packageWord()));
        } else {
            card.animate().translationY(0f).alpha(1f).setDuration(180).start();
            String reason = lastRemoveFailure == null ? "denied" : lastRemoveFailure;
            status.setText(getString(R.string.recents_remove_failed, reason));
            toast(R.string.recents_dismiss_denied);
        }
    }

    /**
     * Swipe-up dismissal. Removal is a privileged operation: the server
     * ({@code ActivityTaskManagerService.removeTask}) enforces REMOVE_TASKS.
     *
     * <p>The Java entry point moved: {@code ActivityManager.removeTask(int)} has
     * been removed from AOSP 16, and the live method is
     * {@code android.app.ActivityTaskManager#removeTask(int)} (a hidden @TestApi
     * class). Both are tried by reflection, the failure is logged with its real
     * exception class, and the card is restored instead of pretending success.
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
                        dismissCard(card, taskId);
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
    }

    private boolean removeTask(int taskId) {
        lastRemoveFailure = null;

        // Android 10..16: android.app.ActivityTaskManager#removeTask(int) is the
        // live entry point (ActivityManager#removeTask was removed from AOSP 16).
        // The class is a hidden @TestApi class, so both its singleton accessor
        // and the method are tried; each failure is logged with its real type
        // instead of being collapsed into "denied".
        try {
            Class<?> cls = Class.forName("android.app.ActivityTaskManager");
            Object instance = cls.getMethod("getInstance").invoke(null);
            Method remove = cls.getMethod("removeTask", int.class);
            Object result = remove.invoke(instance, taskId);
            boolean removed = !(result instanceof Boolean) || (Boolean) result;
            Log.i(TAG, "removeTask(" + taskId + ") via ActivityTaskManager -> " + removed);
            if (removed) {
                return true;
            }
            lastRemoveFailure = "ActivityTaskManager.removeTask returned false";
        } catch (Throwable failed) {
            lastRemoveFailure = failed.getClass().getSimpleName();
            Log.w(TAG, "ActivityTaskManager.removeTask(" + taskId + ") failed", failed);
        }

        // Second chance: call the binder proxy directly, in case only the
        // singleton accessor above is hidden-API restricted.
        try {
            Class<?> cls = Class.forName("android.app.ActivityTaskManager");
            Object service = cls.getMethod("getService").invoke(null);
            Method remove = service.getClass().getMethod("removeTask", int.class);
            Object result = remove.invoke(service, taskId);
            boolean removed = !(result instanceof Boolean) || (Boolean) result;
            Log.i(TAG, "removeTask(" + taskId + ") via IActivityTaskManager -> " + removed);
            if (removed) {
                return true;
            }
            lastRemoveFailure = "IActivityTaskManager.removeTask returned false";
        } catch (Throwable failed) {
            if (lastRemoveFailure == null) {
                lastRemoveFailure = failed.getClass().getSimpleName();
            }
            Log.w(TAG, "IActivityTaskManager.removeTask(" + taskId + ") failed", failed);
        }

        // Pre-Android-10 fallback: ActivityManager#removeTask(int).
        try {
            Method remove = ActivityManager.class.getMethod("removeTask", int.class);
            Object result = remove.invoke(activityManager, taskId);
            boolean removed = !(result instanceof Boolean) || (Boolean) result;
            Log.i(TAG, "removeTask(" + taskId + ") via ActivityManager -> " + removed);
            if (removed) {
                return true;
            }
            lastRemoveFailure = "ActivityManager.removeTask returned false";
        } catch (Throwable failed) {
            if (lastRemoveFailure == null) {
                lastRemoveFailure = failed.getClass().getSimpleName();
            }
            Log.w(TAG, "ActivityManager.removeTask(" + taskId + ") failed", failed);
        }
        return false;
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
        heading.setText(R.string.recents_no_access_title_v2);
        heading.setTypeface(U.semibold(this));
        heading.setTextSize(20f);
        heading.setTextColor(U.WHITE);
        heading.setIncludeFontPadding(false);
        column.addView(heading, fullWidth());

        TextView bodyText = new TextView(this);
        bodyText.setText(R.string.recents_no_access_body_v2);
        bodyText.setTypeface(U.regular(this));
        bodyText.setTextSize(13f);
        bodyText.setTextColor(U.WHITE_70);
        bodyText.setLineSpacing(U.dp(this, 2), 1f);
        LinearLayout.LayoutParams bodyParams = fullWidth();
        bodyParams.topMargin = U.dp(this, 10);
        column.addView(bodyText, bodyParams);

        TextView changeTitle = new TextView(this);
        changeTitle.setText(R.string.recents_change_title_v2);
        changeTitle.setTypeface(U.semibold(this));
        changeTitle.setTextSize(11f);
        changeTitle.setLetterSpacing(0.08f);
        changeTitle.setTextColor(U.ACCENT);
        LinearLayout.LayoutParams changeParams = fullWidth();
        changeParams.topMargin = U.dp(this, 18);
        column.addView(changeTitle, changeParams);

        TextView change = new TextView(this);
        change.setText(R.string.recents_change_body_v2);
        change.setTypeface(U.regular(this));
        change.setTextSize(12f);
        change.setTextColor(U.WHITE_70);
        change.setLineSpacing(U.dp(this, 2), 1f);
        change.setPadding(U.dp(this, 10), U.dp(this, 0), 0, 0);
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

    private boolean isSystemPackage() {
        try {
            ApplicationInfo info = getPackageManager()
                    .getApplicationInfo(getPackageName(), 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

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
