package org.ztmetro.actioncentre;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen Metro Action Centre: our status strip, 4x2 square quick tiles,
 * CLEAR ALL / ALL SETTINGS links and the notification list.
 */
public class ActionCentreActivity extends Activity implements NotificationRowView.Host {
    /**
     * Set by ShadeBridgeService: the panel is already in place behind the
     * closing shade, so the 260 ms slide-up must not run under it.
     */
    public static final String EXTRA_INSTANT = "extra_instant";

    private static final int REQ_CAMERA = 41;

    private static final int TILE_WIFI = 0;
    private static final int TILE_DATA = 1;
    private static final int TILE_BLUETOOTH = 2;
    private static final int TILE_AIRPLANE = 3;
    private static final int TILE_ROTATION = 4;
    private static final int TILE_FLASHLIGHT = 5;
    private static final int TILE_BRIGHTNESS = 6;
    private static final int TILE_SETTINGS = 7;

    private final QuickTile[] tiles = new QuickTile[8];
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private QuickGrid grid;
    private ScrollView scroll;
    private LinearLayout notificationList;
    private TextView gestureLink;
    private CameraManager cameraManager;
    private CameraManager.TorchCallback torchCallback;
    private boolean torchCallbackRegistered;
    private String cameraId;
    private boolean torchOn;

    /** The shade's own travel, in px; 0 = fully open, the view height = gone. */
    private boolean closing;
    private float dragStartY;
    private float dragStartTranslation;
    private boolean dragging;
    private int touchSlop;
    private boolean launchedFromBridge;
    private boolean panelVisibleReported;

    private final NotificationRepository.Listener repoListener =
            new NotificationRepository.Listener() {
                @Override
                public void onNotificationsChanged() {
                    rebuildNotifications();
                }
            };

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            rebuildNotifications();
            refreshTiles();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchedFromBridge = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_INSTANT, false);
        root = buildUi();
        setContentView(root);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        // Our own 260 ms travel replaces the window animation, so it can follow
        // Settings.Global.ANIMATOR_DURATION_SCALE and the handle drag.
        suppressWindowAnimation();
        // The decor view does not exist before setContentView; hiding the bars earlier
        // throws NPE on this build (observed on LineageOS 23.2).
        hideSystemBars();
        if (!launchedFromBridge) {
            // Offscreen from the first frame: no flash of a fully open panel
            // before the runnable gets its height.
            root.setTranslationY(getResources().getDisplayMetrics().heightPixels);
            root.post(openRunnable);
        }
        findFlash();
        registerTorchCallback();
        ActionCentreListener.ensureRebind(this);
        rebuildNotifications();
        refreshTiles();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            setIntent(intent);
            launchedFromBridge = intent.getBooleanExtra(EXTRA_INSTANT, false);
            panelVisibleReported = false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        NotificationRepository.addListener(repoListener);
        rebuildNotifications();
        ActionCentreListener.ensureRebind(this);
        handler.postDelayed(periodicRefresh, 500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        refreshTiles();
        refreshGestureRow();
        ActionCentreListener.ensureRebind(this);
    }

    @Override
    protected void onStop() {
        NotificationRepository.removeListener(repoListener);
        handler.removeCallbacks(periodicRefresh);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (torchCallbackRegistered && cameraManager != null && torchCallback != null) {
            try {
                cameraManager.unregisterTorchCallback(torchCallback);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            if (launchedFromBridge && !panelVisibleReported) {
                panelVisibleReported = true;
                ShadeBridgeService.notifyPanelVisible();
            }
        }
    }

    private void hideSystemBars() {
        if (getWindow() == null || getWindow().peekDecorView() == null) return;
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    }

    /** No window animation: the content slides itself (see {@link #openRunnable}). */
    private void suppressWindowAnimation() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            } else {
                overridePendingTransition(0, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 260 ms up ({@code ztmetro.c:138} SHADE_MS), scaled by the animator scale. */
    private final Runnable openRunnable = new Runnable() {
        @Override
        public void run() {
            if (root == null || closing) {
                return;
            }
            int height = panelHeight();
            int duration = Motion.ms(ActionCentreActivity.this, Motion.SHADE_OPEN_MS);
            root.animate().cancel();
            if (duration <= 0) {
                root.setTranslationY(0f);
                return;
            }
            root.setTranslationY(height);
            root.animate().translationY(0f)
                    .setDuration(duration)
                    .setInterpolator(Motion.WP_LOG)
                    .start();
        }
    };

    private final Runnable finishNowRunnable = new Runnable() {
        @Override
        public void run() {
            finishNow();
        }
    };

    @Override
    public void finish() {
        if (closing) {
            return;
        }
        closing = true;
        int duration = Motion.ms(this, Motion.SHADE_CLOSE_MS);
        if (root == null || duration <= 0) {
            finishNow();
            return;
        }
        root.animate().cancel();
        root.animate().translationY(panelHeight())
                .setDuration(duration)
                .setInterpolator(Motion.WP_LOG)
                .withEndAction(finishNowRunnable)
                .start();
    }

    private void finishNow() {
        super.finish();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            } else {
                overridePendingTransition(0, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private int panelHeight() {
        int height = root != null ? root.getHeight() : 0;
        return height > 0 ? height : getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * 1:1 with the finger, then a 220 ms close or 260 ms snap back. The C
     * commits the shade on a 40 pt drag ({@code ztmetro.c:11761}); our panel is
     * a bottom sheet, so a downward drag is what closes it.
     */
    private void snapPanel(float translation) {
        int height = panelHeight();
        boolean close = translation > Motion.DRAG_COMMIT_DP
                * getResources().getDisplayMetrics().density;
        root.animate().cancel();
        if (!close) {
            int duration = Motion.ms(this, Motion.SHADE_OPEN_MS);
            if (duration <= 0) {
                root.setTranslationY(0f);
            } else {
                root.animate().translationY(0f)
                        .setDuration(duration)
                        .setInterpolator(Motion.WP_LOG)
                        .start();
            }
            return;
        }
        closing = true;
        int duration = Motion.ms(this, Motion.SHADE_CLOSE_MS);
        if (duration <= 0) {
            root.setTranslationY(height);
            finishNow();
            return;
        }
        root.animate().translationY(height)
                .setDuration(duration)
                .setInterpolator(Motion.WP_LOG)
                .withEndAction(finishNowRunnable)
                .start();
    }

    private LinearLayout buildUi() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(U.BLACK);

        column.addView(new StatusStripView(this),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        grid = new QuickGrid(this);
        content.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        buildTiles();

        content.addView(buildLinksRow(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, U.dp(this, 36)));

        // One tap to the system Accessibility page where the shade bridge is
        // switched on. With the service off this row is the only change and the
        // stock shade keeps working untouched.
        content.addView(buildGestureRow(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, U.dp(this, 36)));

        notificationList = new LinearLayout(this);
        notificationList.setOrientation(LinearLayout.VERTICAL);
        notificationList.setPadding(0, 0, 0, U.dp(this, 8));
        content.addView(notificationList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        HandleView handle = new HandleView(this);
        handle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragStartY = event.getRawY();
                        dragStartTranslation = root.getTranslationY();
                        dragging = false;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - dragStartY;
                        if (!dragging && Math.abs(dy) > touchSlop) {
                            dragging = true;
                        }
                        if (dragging) {
                            float target = dragStartTranslation + dy;
                            if (target < 0f) {
                                target = 0f;
                            }
                            int height = root.getHeight();
                            if (height > 0 && target > height) {
                                target = height;
                            }
                            root.animate().cancel();
                            root.setTranslationY(target);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (!dragging) {
                            v.performClick();
                        } else {
                            snapPanel(root.getTranslationY());
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        snapPanel(root.getTranslationY());
                        return true;
                    default:
                        return false;
                }
            }
        });
        column.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return column;
    }

    private void buildTiles() {
        grid.removeAllViews();
        addTile(TILE_WIFI, R.drawable.ic_wifi, R.string.tile_wifi);
        addTile(TILE_DATA, R.drawable.ic_data, R.string.tile_data);
        addTile(TILE_BLUETOOTH, R.drawable.ic_bluetooth, R.string.tile_bluetooth);
        addTile(TILE_AIRPLANE, R.drawable.ic_airplane, R.string.tile_airplane);
        addTile(TILE_ROTATION, R.drawable.ic_rotation, R.string.tile_rotation);
        addTile(TILE_FLASHLIGHT, R.drawable.ic_flashlight, R.string.tile_flashlight);
        tiles[TILE_BRIGHTNESS] = newTile(R.drawable.ic_brightness,
                getString(R.string.tile_brightness_plain));
        addTile(TILE_SETTINGS, R.drawable.ic_settings, R.string.tile_settings);
        tiles[TILE_SETTINGS].setActionOnly(true);
        tiles[TILE_BRIGHTNESS].setActionOnly(true);
    }

    private void addTile(int index, int iconRes, int labelRes) {
        tiles[index] = newTile(iconRes, getString(labelRes));
    }

    private QuickTile newTile(int iconRes, String label) {
        final QuickTile tile = new QuickTile(this);
        tile.configure(iconRes, label);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTileClicked(tile);
            }
        });
        grid.addView(tile);
        return tile;
    }

    private void onTileClicked(QuickTile tile) {
        if (tile == tiles[TILE_WIFI]) {
            openPanel(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS);
        } else if (tile == tiles[TILE_DATA]) {
            openPanel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                    Settings.ACTION_WIRELESS_SETTINGS);
        } else if (tile == tiles[TILE_BLUETOOTH]) {
            // Android 16 exposes no public Bluetooth panel action; Settings page
            // is the honest non-privileged trampoline.
            startActivitySafely(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } else if (tile == tiles[TILE_AIRPLANE]) {
            startActivitySafely(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        } else if (tile == tiles[TILE_ROTATION]) {
            toggleRotation();
        } else if (tile == tiles[TILE_FLASHLIGHT]) {
            toggleTorch();
        } else if (tile == tiles[TILE_BRIGHTNESS]) {
            cycleBrightness();
        } else if (tile == tiles[TILE_SETTINGS]) {
            openSettings();
        }
    }

    private View buildLinksRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(U.dp(this, 12), 0, U.dp(this, 12), 0);

        TextView clear = link(getString(R.string.link_clear_all));
        clear.setText("\u00d7  " + clear.getText());
        clear.setContentDescription(getString(R.string.link_clear_all));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAllNotifications();
            }
        });
        row.addView(clear, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView all = link(getString(R.string.link_all_settings));
        all.setGravity(Gravity.END);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        row.addView(all, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * One-tap entry to Settings > Accessibility, where the user switches on
     * "ztMetro Action Centre". No permission is requested here; opening the
     * page is all a non-privileged app can do.
     */
    private View buildGestureRow() {
        gestureLink = new TextView(this);
        gestureLink.setTextSize(10);
        gestureLink.setTypeface(U.semibold(this));
        gestureLink.setLetterSpacing(0.08f);
        gestureLink.setGravity(Gravity.CENTER_VERTICAL);
        gestureLink.setPadding(U.dp(this, 12), 0, U.dp(this, 12), 0);
        gestureLink.setSingleLine(true);
        gestureLink.setEllipsize(TextUtils.TruncateAt.END);
        gestureLink.setClickable(true);
        gestureLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        refreshGestureRow();
        return gestureLink;
    }

    /** Label follows the live switch state; refreshed on every resume. */
    private void refreshGestureRow() {
        if (gestureLink == null) {
            return;
        }
        boolean enabled;
        try {
            enabled = ShadeBridgeService.isEnabled(this);
        } catch (Throwable ignored) {
            enabled = false;
        }
        gestureLink.setText(enabled ? R.string.gesture_enabled : R.string.gesture_enable);
        gestureLink.setContentDescription(getString(
                enabled ? R.string.gesture_enabled : R.string.gesture_enable)
                + ". " + getString(R.string.gesture_enable_hint));
        gestureLink.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                enabled ? new int[]{U.WHITE_70, U.WHITE_50}
                        : new int[]{U.WHITE, U.ACCENT}));
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivitySafely(new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable ignored) {
            startActivitySafely(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private TextView link(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTypeface(U.semibold(this));
        view.setLetterSpacing(0.08f);
        view.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{U.ACCENT, U.WHITE_70}));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(0, U.dp(this, 8), 0, U.dp(this, 8));
        view.setClickable(true);
        return view;
    }

    private void clearAllNotifications() {
        if (!ActionCentreListener.isAccessGranted(this)) {
            toast(R.string.toast_listener_missing);
            openListenerSettings();
            return;
        }
        if (!ActionCentreListener.cancelAll()) {
            toast(R.string.toast_listener_missing);
        }
    }

    private void openSettings() {
        startActivitySafely(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openListenerSettings() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        }
        startActivitySafely(intent);
    }

    private void openPanel(String action, String fallback) {
        // Try the panel action first; some builds hide it from package queries
        // but still resolve it at start time, so do not decide via resolveActivity.
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            startActivitySafely(new Intent(fallback));
        }
    }

    private void startActivitySafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.toast_settings_missing);
        } catch (Throwable ignored) {
            toast(R.string.toast_settings_missing);
        }
    }

    private void toggleRotation() {
        if (!Settings.System.canWrite(this)) {
            requestWriteSettings();
            return;
        }
        try {
            int current = Settings.System.getInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, current == 1 ? 0 : 1);
        } catch (Throwable ignored) {
            requestWriteSettings();
            return;
        }
        refreshTiles();
    }

    private void cycleBrightness() {
        if (!Settings.System.canWrite(this)) {
            // Spec: without the WRITE_SETTINGS appop fall back to the system
            // display page instead of pretending to change anything.
            startActivitySafely(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            return;
        }
        try {
            int current = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            int percent = Math.round(current * 100f / 255f);
            int next = percent <= 25 ? 50 : percent <= 50 ? 75 : percent <= 75 ? 100 : 25;
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, Math.round(next * 255f / 100f));
            Toast.makeText(this, getString(R.string.toast_brightness, next),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            requestWriteSettings();
            return;
        }
        refreshTiles();
    }

    private void requestWriteSettings() {
        toast(R.string.toast_write_settings);
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
        }
    }

    private void findFlash() {
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cameraManager == null) {
                return;
            }
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(id);
                Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cameraId = id;
                    break;
                }
            }
        } catch (Throwable ignored) {
            cameraId = null;
        }
    }

    private void registerTorchCallback() {
        if (cameraManager == null) {
            return;
        }
        torchCallback = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String id, boolean enabled) {
                if (id != null && id.equals(cameraId)) {
                    torchOn = enabled;
                    refreshTiles();
                }
            }

            @Override
            public void onTorchModeUnavailable(String id) {
                if (id != null && id.equals(cameraId)) {
                    torchOn = false;
                    refreshTiles();
                }
            }
        };
        try {
            cameraManager.registerTorchCallback(torchCallback, handler);
            torchCallbackRegistered = true;
        } catch (Throwable ignored) {
            torchCallbackRegistered = false;
        }
    }

    private void toggleTorch() {
        if (cameraId == null || cameraManager == null) {
            toast(R.string.toast_no_flash);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        try {
            cameraManager.setTorchMode(cameraId, !torchOn);
            torchOn = !torchOn;
            refreshTiles();
        } catch (CameraAccessException e) {
            toast(R.string.toast_camera_busy);
        } catch (Throwable ignored) {
            toast(R.string.toast_camera_busy);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleTorch();
            } else {
                toast(R.string.toast_camera_denied);
            }
        }
    }

    private void refreshTiles() {
        if (tiles[TILE_WIFI] == null) {
            return;
        }
        tiles[TILE_WIFI].setTileState(DeviceState.wifiEnabled(this), true);
        tiles[TILE_DATA].setTileState(DeviceState.mobileDataEnabled(this), true);
        tiles[TILE_BLUETOOTH].setTileState(DeviceState.bluetoothEnabled(this), true);
        tiles[TILE_AIRPLANE].setTileState(DeviceState.airplaneMode(this), true);
        tiles[TILE_ROTATION].setTileState(DeviceState.rotationLocked(this), true);
        tiles[TILE_FLASHLIGHT].setTileState(torchOn, cameraId != null);

        int brightness = DeviceState.brightnessPercent(this);
        if (brightness >= 0) {
            tiles[TILE_BRIGHTNESS].setLabel(
                    getString(R.string.tile_brightness, brightness));
            tiles[TILE_BRIGHTNESS].setTileState(brightness >= 50, true);
        } else {
            tiles[TILE_BRIGHTNESS].setLabel(getString(R.string.tile_brightness_plain));
            tiles[TILE_BRIGHTNESS].setTileState(false, true);
        }
        tiles[TILE_SETTINGS].setTileState(false, true);
    }

    private void rebuildNotifications() {
        if (notificationList == null) {
            return;
        }
        final int previousScroll = scroll == null ? 0 : scroll.getScrollY();
        notificationList.removeAllViews();
        List<Notif> all = NotificationRepository.snapshot();
        boolean granted = ActionCentreListener.isAccessGranted(this);
        boolean connected = NotificationRepository.isConnected();

        if (all.isEmpty()) {
            addEmptyState(granted, connected);
        } else {
            Map<String, List<Notif>> groups = new LinkedHashMap<>();
            for (Notif notif : all) {
                List<Notif> group = groups.get(notif.packageName);
                if (group == null) {
                    group = new ArrayList<>();
                    groups.put(notif.packageName, group);
                }
                group.add(notif);
            }
            for (List<Notif> group : groups.values()) {
                if (group.isEmpty()) {
                    continue;
                }
                addAppLabel(group.get(0).appLabel);
                for (Notif notif : group) {
                    NotificationRowView row = new NotificationRowView(this, notif, this);
                    notificationList.addView(row, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                }
            }
        }
        if (scroll != null) {
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.scrollTo(0, previousScroll);
                }
            });
        }
    }

    private void addAppLabel(String label) {
        TextView header = new TextView(this);
        header.setText(label.toUpperCase(Locale.getDefault()));
        header.setTextSize(10);
        header.setTypeface(U.semibold(this));
        header.setLetterSpacing(0.08f);
        header.setTextColor(U.ACCENT);
        header.setPadding(U.dp(this, 12), U.dp(this, 12), U.dp(this, 12), U.dp(this, 4));
        notificationList.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addEmptyState(boolean granted, boolean connected) {
        TextView empty = new TextView(this);
        empty.setText(R.string.empty_notifications);
        empty.setTextSize(17);
        empty.setTypeface(U.light(this));
        empty.setTextColor(U.WHITE_70);
        empty.setPadding(U.dp(this, 16), U.dp(this, 22), U.dp(this, 16), U.dp(this, 4));
        notificationList.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setTypeface(U.regular(this));
        hint.setTextColor(U.WHITE_50);
        hint.setPadding(U.dp(this, 16), U.dp(this, 2), U.dp(this, 16), U.dp(this, 8));
        if (!granted) {
            hint.setText(R.string.listener_missing);
            notificationList.addView(hint, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView action = new TextView(this);
            action.setText(R.string.listener_missing_action);
            action.setTextSize(11);
            action.setTypeface(U.regular(this));
            action.setTextColor(U.ACCENT);
            action.setPadding(U.dp(this, 16), U.dp(this, 2), U.dp(this, 16), U.dp(this, 8));
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openListenerSettings();
                }
            });
            notificationList.addView(action, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else if (!connected) {
            hint.setText(R.string.listener_connecting);
            notificationList.addView(hint, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    @Override
    public void onOpen(Notif notif) {
        if (notif.contentIntent != null) {
            try {
                notif.contentIntent.send();
            } catch (Throwable ignored) {
            }
        }
        finish();
    }

    @Override
    public void onDismiss(Notif notif) {
        ActionCentreListener.cancelKey(notif.key);
        NotificationRepository.removeKey(notif.key);
    }

    private void toast(int stringRes) {
        Toast.makeText(this, stringRes, Toast.LENGTH_SHORT).show();
    }
}
