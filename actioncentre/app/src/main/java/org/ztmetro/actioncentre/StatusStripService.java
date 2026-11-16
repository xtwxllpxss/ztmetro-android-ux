package org.ztmetro.actioncentre;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Draws the ztMetro status strip over the stock Android status bar.
 *
 * <p>Accessibility is the only app-level privilege that can put a window above
 * {@code TYPE_STATUS_BAR}: a {@code TYPE_ACCESSIBILITY_OVERLAY} (2032) is
 * stacked above SystemUI, while {@code TYPE_APPLICATION_OVERLAY} would sit
 * below it. The window is 28 dp tall (ztmetro.tokens.json
 * {@code metrics.status_strip_height_dp}), full width, opaque black, and
 * {@code FLAG_NOT_TOUCHABLE}, so it hides the system icons without stealing the
 * top-edge gesture or any other touch. The existing 24 dp transparent gesture
 * strip in {@link ShadeBridgeService} stays below it and still receives gestures.
 *
 * <p>Data comes from the real system APIs, event-driven only: sticky
 * {@code ACTION_BATTERY_CHANGED} for level/charging, {@link WifiManager} plus a
 * default-network callback for Wi-Fi, {@link TelephonyManager#getSignalStrength()}
 * / a telephony signal callback for the bars (four static bars when
 * {@code READ_PHONE_STATE} is not granted), and {@code ACTION_TIME_TICK} for the
 * clock. There is no 1 Hz timer and no wakelock.
 *
 * <p>Fail-safe: every entry point is wrapped. If the window cannot be added the
 * service logs and does nothing, and the stock status bar is left untouched. The
 * strip is removed whenever the keyguard is locked or the screen is off, so the
 * separate ztMetro lock overlay owns the screen, and it is re-added on
 * {@code ACTION_USER_PRESENT} / screen-on-without-keyguard.
 */
public class StatusStripService extends AccessibilityService {
    static final String TAG = "StatusStrip";

    private WindowManager windowManager;
    private StatusStripOverlayView stripView;
    private WindowManager.LayoutParams stripParams;
    private boolean overlayAdded;

    private BroadcastReceiver receiver;
    private boolean receiverRegistered;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered;

    private TelephonyManager telephonyManager;
    private TelephonyCallback telephonyCallback;
    private PhoneStateListener phoneStateListener;
    private int lastSignalLevel = -1;

    private KeyguardManager keyguardManager;
    private KeyguardManager.KeyguardLockedStateListener keyguardLockedListener;

    @Override
    protected void onServiceConnected() {
        try {
            super.onServiceConnected();
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            telephonyManager =
                    (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            keyguardManager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            registerReceivers();
            registerConnectivityCallback();
            registerTelephonyCallback();
            registerKeyguardListener();

            if (!isKeyguardLocked() && isInteractive()) {
                addOverlay();
            } else {
                Log.i(TAG, "status strip held back: keyguard/screen not ready");
            }
            logBlacklistState();
        } catch (Throwable t) {
            Log.w(TAG, "status strip service setup failed", t);
        }
    }

    // ---- window -----------------------------------------------------------

    private void addOverlay() {
        if (overlayAdded) {
            return;
        }
        if (isKeyguardLocked() || !isInteractive()) {
            Log.i(TAG, "not adding strip: keyguard locked or screen off");
            return;
        }
        try {
            if (windowManager == null) {
                windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            }
            if (windowManager == null) {
                Log.w(TAG, "no WindowManager; strip not added");
                return;
            }
            if (stripView == null) {
                stripView = new StatusStripOverlayView(this);
            }
            int height = dp(StatusStripOverlayView.HEIGHT_DP);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                // Full width even across a cutout, but the window is still only 28 dp.
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            params.setTitle("ztMetro status strip");
            windowManager.addView(stripView, params);
            stripParams = params;
            overlayAdded = true;
            updateAll();
            Log.i(TAG, "status strip added: " + height + " px / "
                    + StatusStripOverlayView.HEIGHT_DP
                    + " dp, TYPE_ACCESSIBILITY_OVERLAY, NOT_TOUCHABLE");
        } catch (Throwable t) {
            // A denied/broken overlay must never take the app or the stock bar down.
            Log.w(TAG, "status strip not added; leaving the stock bar as is", t);
            overlayAdded = false;
            stripView = null;
            stripParams = null;
        }
    }

    private void removeOverlay() {
        if (!overlayAdded || stripView == null || windowManager == null) {
            overlayAdded = false;
            return;
        }
        try {
            windowManager.removeViewImmediate(stripView);
            Log.i(TAG, "status strip removed");
        } catch (Throwable t) {
            Log.w(TAG, "removing status strip failed", t);
        }
        overlayAdded = false;
        stripView = null;
        stripParams = null;
    }

    private boolean isKeyguardLocked() {
        try {
            KeyguardManager km = keyguardManager;
            if (km == null) {
                km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            }
            return km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) {
            // If the lock state cannot be read, stay conservative and do not cover it.
            return true;
        }
    }

    private boolean isInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ---- receivers --------------------------------------------------------

    private void registerReceivers() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                        handleBattery(intent);
                    } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                        updateWifi();
                    } else if (Intent.ACTION_TIME_TICK.equals(action)
                            || Intent.ACTION_TIME_CHANGED.equals(action)
                            || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                        if (stripView != null) {
                            stripView.refreshClock();
                        }
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        removeOverlay();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        if (!isKeyguardLocked()) {
                            addOverlay();
                        }
                    } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        addOverlay();
                    } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                        updateAirplane();
                        updateWifi();
                        updateSignal();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "broadcast ignored after error", t);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void handleBattery(Intent intent) {
        if (intent == null || stripView == null) {
            return;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        int percent = -1;
        if (level >= 0 && scale > 0) {
            percent = Math.min(100, Math.round(level * 100f / scale));
        }
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        stripView.setBattery(percent, charging);
    }

    private void registerConnectivityCallback() {
        if (connectivityManager == null || Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateWifi();
                    updateSignal();
                }

                @Override
                public void onLost(Network network) {
                    updateWifi();
                    updateSignal();
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                NetworkCapabilities capabilities) {
                    updateWifi();
                    updateSignal();
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "network callback not registered; Wi-Fi state stays at attach value", t);
        }
    }

    private void registerTelephonyCallback() {
        if (telephonyManager == null) {
            return;
        }
        boolean granted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Log.i(TAG, "READ_PHONE_STATE not granted; drawing four static signal bars");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SignalCallback callback = new SignalCallback();
                telephonyManager.registerTelephonyCallback(getMainExecutor(), callback);
                telephonyCallback = callback;
            } else {
                PhoneStateListener listener = new PhoneStateListener(getMainLooper()) {
                    @Override
                    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                        handleSignalStrength(signalStrength);
                    }
                };
                telephonyManager.listen(listener,
                        PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
                phoneStateListener = listener;
            }
        } catch (Throwable t) {
            Log.w(TAG, "telephony signal callback not registered; static bars", t);
        }
    }

    /** API 31+ signal callback. Separate class so it is never loaded on API 29/30. */
    private final class SignalCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            handleSignalStrength(signalStrength);
        }
    }

    private void registerKeyguardListener() {
        if (Build.VERSION.SDK_INT < 33 || keyguardManager == null) {
            return;
        }
        try {
            keyguardLockedListener = new KeyguardManager.KeyguardLockedStateListener() {
                @Override
                public void onKeyguardLockedStateChanged(boolean isKeyguardLocked) {
                    try {
                        if (isKeyguardLocked) {
                            removeOverlay();
                        } else if (isInteractive()) {
                            addOverlay();
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "keyguard listener failed", t);
                    }
                }
            };
            keyguardManager.addKeyguardLockedStateListener(
                    getMainExecutor(), keyguardLockedListener);
        } catch (Throwable t) {
            Log.w(TAG, "keyguard listener not registered", t);
        }
    }

    // ---- state ------------------------------------------------------------

    private void updateAll() {
        try {
            Intent battery = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            handleBattery(battery);
        } catch (Throwable ignored) {
        }
        updateWifi();
        updateAirplane();
        updateSignal();
        if (stripView != null) {
            stripView.refreshClock();
        }
    }

    private void updateWifi() {
        if (stripView == null) {
            return;
        }
        boolean enabled = false;
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            enabled = wm != null && wm.isWifiEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "reading Wi-Fi state failed", t);
        }
        boolean connected = hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        stripView.setWifi(enabled, connected);
    }

    private void updateAirplane() {
        if (stripView == null) {
            return;
        }
        boolean on = false;
        try {
            on = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Throwable t) {
            Log.w(TAG, "reading airplane mode failed", t);
        }
        stripView.setAirplane(on);
    }

    private void updateSignal() {
        if (stripView == null || telephonyManager == null) {
            return;
        }
        boolean simReady = false;
        try {
            simReady = telephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY;
        } catch (Throwable ignored) {
        }
        int level = lastSignalLevel;
        try {
            SignalStrength strength = telephonyManager.getSignalStrength();
            if (strength != null) {
                level = strength.getLevel();
                lastSignalLevel = level;
            }
        } catch (Throwable ignored) {
            // No READ_PHONE_STATE or no radio: fall back to the cached/unknown level.
        }
        boolean cellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        stripView.setSignal(simReady, level, cellular);
    }

    private void handleSignalStrength(SignalStrength strength) {
        try {
            if (strength == null) {
                return;
            }
            lastSignalLevel = strength.getLevel();
            if (stripView != null) {
                boolean simReady = telephonyManager != null
                        && telephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY;
                stripView.setSignal(simReady, lastSignalLevel,
                        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            }
        } catch (Throwable t) {
            Log.w(TAG, "signal update failed", t);
        }
    }

    private boolean hasTransport(int transport) {
        if (connectivityManager == null) {
            return false;
        }
        try {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(transport);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The blacklist itself must be written by a privileged shell (WRITE_SECURE_SETTINGS
     * is not grantable to this app). Log what SystemUI will read so a failed step is
     * visible in logcat instead of only on screen.
     */
    private void logBlacklistState() {
        try {
            String list = Settings.Secure.getString(getContentResolver(),
                    "icon_blacklist");
            Log.i(TAG, "icon_blacklist=" + list
                    + " (needs clock,battery,wifi,mobile to avoid stock duplicates)");
        } catch (Throwable ignored) {
        }
    }

    // ---- lifecycle --------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The strip is window-only; window-state events are not needed.
    }

    @Override
    public void onInterrupt() {
        try {
            removeOverlay();
        } catch (Throwable ignored) {
        }
    }

    private void cleanup() {
        removeOverlay();
        if (receiverRegistered && receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
        }
        receiverRegistered = false;
        if (networkCallbackRegistered && connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {
            }
        }
        networkCallbackRegistered = false;
        try {
            if (Build.VERSION.SDK_INT >= 31 && telephonyCallback != null
                    && telephonyManager != null) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback);
            } else if (phoneStateListener != null && telephonyManager != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        } catch (Throwable ignored) {
        }
        telephonyCallback = null;
        phoneStateListener = null;
        if (keyguardLockedListener != null && keyguardManager != null
                && Build.VERSION.SDK_INT >= 33) {
            try {
                keyguardManager.removeKeyguardLockedStateListener(keyguardLockedListener);
            } catch (Throwable ignored) {
            }
        }
        keyguardLockedListener = null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            cleanup();
            Log.i(TAG, "service unbound; strip removed");
        } catch (Throwable ignored) {
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        try {
            cleanup();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }
}
