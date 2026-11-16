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
import android.os.Handler;
import android.os.Looper;
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
 * top-edge gesture or any other touch. The existing 32 dp transparent gesture
 * strip in {@link ShadeBridgeService} stays below it and still receives gestures.
 *
 * <p><b>Pass 2 (perf): this service is idle when nothing changed.</b>
 *
 * <ul>
 *   <li><b>Two receivers, not one.</b> {@link #screenReceiver} (screen on/off,
 *       user present) is registered for the whole life of the service because it
 *       is what brings the strip back. {@link #dataReceiver} (battery, clock,
 *       Wi-Fi state, airplane) is registered <em>only while the screen is on</em>
 *       and the strip exists, and the same applies to the network and telephony
 *       callbacks. While the screen is off the app is not woken by the
 *       {@code ACTION_TIME_TICK} minute broadcast or by capability callbacks.</li>
 *   <li><b>One coalesced network read.</b> {@code onCapabilitiesChanged} fires on
 *       bandwidth/strength updates; a burst now posts one 400 ms coalescing
 *       one-shot ({@link #NETWORK_COALESCE_MS}) that reads the active network
 *       once (two binder calls shared by Wi-Fi and cellular) and redraws only if
 *       the transport bits actually changed.</li>
 *   <li><b>No redraw without a change.</b> Every value is cached here and in
 *       {@link StatusStripOverlayView}, whose setters reject an identical state;
 *       a battery broadcast that does not move the rounded percentage, or a
 *       capability callback that does not change a transport, produces no frame
 *       and no view call.</li>
 *   <li><b>Clock once a minute.</b> {@code ACTION_TIME_TICK} only (plus explicit
 *       time/timezone changes), and only while the strip is on screen.</li>
 * </ul>
 *
 * <p>Fail-safe: every entry point is wrapped. If the window cannot be added the
 * service logs and does nothing, and the stock status bar is left untouched.
 */
public class StatusStripService extends AccessibilityService {
    static final String TAG = "StatusStrip";

    /**
     * A burst of {@code onCapabilitiesChanged} callbacks collapses into one read.
     * Not a poll: it is posted only by a callback, never by itself.
     */
    private static final long NETWORK_COALESCE_MS = 400L;

    /**
     * True while ActionCentreActivity is resumed. That screen hides the system
     * bars and draws its own strip, so the overlay must not add a second one.
     */
    private static volatile boolean panelVisible;
    /** The bound service, if any; same process as ActionCentreActivity. */
    private static volatile StatusStripService instance;

    private WindowManager windowManager;
    private StatusStripOverlayView stripView;
    private WindowManager.LayoutParams stripParams;
    private boolean overlayAdded;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Screen on/off + user present: lives for the whole service (see class doc). */
    private BroadcastReceiver screenReceiver;
    private boolean screenReceiverRegistered;
    /** Battery / clock / Wi-Fi / airplane: only while the screen is on. */
    private BroadcastReceiver dataReceiver;
    private boolean dataReceiverRegistered;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered;
    private boolean networkRefreshQueued;

    private TelephonyManager telephonyManager;
    /** Api31 callback, kept as Object so API 29/30 never resolve TelephonyCallback. */
    private Object telephonyCallback;
    private PhoneStateListener phoneStateListener;

    private KeyguardManager keyguardManager;
    /** Api33 listener, kept as Object so API 29-32 never resolve the new interface. */
    private Object keyguardLockedListener;

    /** Screen state, owned by {@link #screenReceiver}; no getter binder call per update. */
    private boolean interactive = true;

    /*
     * Last values actually pushed to the view. They let a capability callback
     * decide "nothing visible changed" without touching a system service.
     */
    private int batteryLevel = -1;
    private boolean charging;
    private boolean wifiEnabled;
    private boolean wifiTransport;
    private boolean cellularTransport;
    private boolean airplane;
    private boolean simReady;
    private int signalLevel = -1;
    /** False until the first active-network read of this overlay session. */
    private boolean networkKnown;

    @Override
    protected void onServiceConnected() {
        try {
            super.onServiceConnected();
            instance = this;
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            telephonyManager =
                    (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            keyguardManager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            // One binder read at connect; afterwards the screen receiver owns this.
            interactive = readInteractive();
            registerScreenReceiver();
            registerKeyguardListener();

            if (!isKeyguardLocked() && interactive) {
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
        if (panelVisible) {
            Log.i(TAG, "not adding strip: Action Centre panel owns the screen");
            return;
        }
        if (isKeyguardLocked() || !interactive) {
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
            // Sources are subscribed only while the strip is actually on screen.
            networkKnown = false;
            registerDataReceiver();
            registerConnectivityCallback();
            registerTelephonyCallback();
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
            unregisterDataSources();
        }
    }

    private void removeOverlay() {
        if (!overlayAdded || stripView == null || windowManager == null) {
            overlayAdded = false;
            unregisterDataSources();
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
        unregisterDataSources();
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

    private boolean readInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** True only when a redraw would actually reach the screen. */
    private boolean canDraw() {
        return overlayAdded && stripView != null && interactive && !panelVisible;
    }

    /**
     * Called by ActionCentreActivity in onCreate/onResume (true) and onStop
     * (false). Same process, so no broadcast or polling is needed.
     */
    static void setPanelVisible(Context context, boolean visible) {
        panelVisible = visible;
        StatusStripService service = instance;
        if (service != null) {
            service.applyPanelVisibility(visible);
        }
    }

    private void applyPanelVisibility(boolean visible) {
        try {
            if (visible) {
                removeOverlay();
            } else if (!isKeyguardLocked() && interactive) {
                addOverlay();
            }
        } catch (Throwable t) {
            Log.w(TAG, "panel visibility update failed", t);
        }
    }

    // ---- receivers --------------------------------------------------------

    /**
     * Screen + user-present only. This one is always registered: it is the only
     * thing that can bring the strip back after {@code ACTION_SCREEN_OFF}, and it
     * keeps the app out of the minute-tick path while the screen is off.
     */
    private void registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        onScreenOff();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        onScreenOn();
                    } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        interactive = true;
                        addOverlay();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "screen receiver failed", t);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
            screenReceiverRegistered = true;
        } catch (Throwable t) {
            screenReceiver = null;
            screenReceiverRegistered = false;
            Log.w(TAG, "screen receiver registration failed", t);
        }
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered || screenReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        screenReceiverRegistered = false;
        screenReceiver = null;
    }

    private void onScreenOff() {
        interactive = false;
        // removeOverlay() also drops the data receiver, the network callback and
        // the telephony callback: no minute tick and no capability wakeup while
        // the screen is off.
        removeOverlay();
    }

    private void onScreenOn() {
        interactive = true;
        if (!isKeyguardLocked()) {
            addOverlay();
        }
    }

    /** Battery / clock / Wi-Fi / airplane: screen-on only. */
    private void registerDataReceiver() {
        if (dataReceiverRegistered || dataReceiver != null) {
            return;
        }
        dataReceiver = new BroadcastReceiver() {
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
                        updateWifiState();
                    } else if (Intent.ACTION_TIME_TICK.equals(action)
                            || Intent.ACTION_TIME_CHANGED.equals(action)
                            || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                        // Clock: exactly once a minute (plus explicit changes).
                        if (canDraw()) {
                            stripView.refreshClock();
                        }
                    } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                        updateAirplane();
                        updateSimState();
                        scheduleNetworkRefresh();
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
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dataReceiver, filter);
            }
            dataReceiverRegistered = true;
        } catch (Throwable t) {
            dataReceiver = null;
            dataReceiverRegistered = false;
            Log.w(TAG, "data receiver registration failed", t);
        }
    }

    private void unregisterDataReceiver() {
        if (!dataReceiverRegistered || dataReceiver == null) {
            dataReceiverRegistered = false;
            dataReceiver = null;
            return;
        }
        try {
            unregisterReceiver(dataReceiver);
        } catch (Throwable ignored) {
        }
        dataReceiverRegistered = false;
        dataReceiver = null;
    }

    private void unregisterDataSources() {
        handler.removeCallbacks(networkRefresh);
        networkRefreshQueued = false;
        unregisterDataReceiver();
        unregisterConnectivityCallback();
        unregisterTelephonyCallback();
    }

    private void handleBattery(Intent intent) {
        if (intent == null || !canDraw()) {
            return;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        int percent = -1;
        if (level >= 0 && scale > 0) {
            percent = Math.min(100, Math.round(level * 100f / scale));
        }
        boolean nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (percent == batteryLevel && nowCharging == charging) {
            // Voltage/temperature updates also arrive as ACTION_BATTERY_CHANGED.
            return;
        }
        batteryLevel = percent;
        charging = nowCharging;
        stripView.setBattery(percent, nowCharging);
    }

    // ---- network ----------------------------------------------------------

    private void registerConnectivityCallback() {
        if (networkCallbackRegistered || connectivityManager == null
                || Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleNetworkRefresh();
                }

                @Override
                public void onLost(Network network) {
                    scheduleNetworkRefresh();
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                NetworkCapabilities capabilities) {
                    scheduleNetworkRefresh();
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "network callback not registered; Wi-Fi state stays at attach value", t);
        }
    }

    private void unregisterConnectivityCallback() {
        if (!networkCallbackRegistered || connectivityManager == null || networkCallback == null) {
            networkCallbackRegistered = false;
            networkCallback = null;
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Throwable ignored) {
        }
        networkCallbackRegistered = false;
        networkCallback = null;
    }

    /**
     * One coalesced read per burst. A capabilities storm (bandwidth, signal,
     * captive-portal checks) must not turn into a read per callback.
     */
    private void scheduleNetworkRefresh() {
        if (!canDraw() || networkRefreshQueued) {
            return;
        }
        networkRefreshQueued = true;
        handler.postDelayed(networkRefresh, NETWORK_COALESCE_MS);
    }

    private final Runnable networkRefresh = new Runnable() {
        @Override
        public void run() {
            networkRefreshQueued = false;
            if (!canDraw()) {
                return;
            }
            try {
                // Two binder calls, shared by the Wi-Fi and cellular glyphs.
                boolean wifiNow = false;
                boolean cellularNow = false;
                Network active = connectivityManager == null
                        ? null : connectivityManager.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps =
                            connectivityManager.getNetworkCapabilities(active);
                    if (caps != null) {
                        wifiNow = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                        cellularNow = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                    }
                }
                if (networkKnown && wifiNow == wifiTransport && cellularNow == cellularTransport) {
                    return;   // a capability change that is not on our two glyphs
                }
                networkKnown = true;
                wifiTransport = wifiNow;
                cellularTransport = cellularNow;
                stripView.setWifi(wifiEnabled, wifiTransport);
                stripView.setSignal(simReady, signalLevel, cellularTransport);
            } catch (Throwable t) {
                Log.w(TAG, "network refresh failed", t);
            }
        }
    };

    private void updateWifiState() {
        if (!canDraw()) {
            return;
        }
        boolean enabled = false;
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            enabled = wm != null && wm.isWifiEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "reading Wi-Fi state failed", t);
        }
        if (enabled == wifiEnabled) {
            return;
        }
        wifiEnabled = enabled;
        stripView.setWifi(wifiEnabled, wifiTransport);
    }

    private void updateAirplane() {
        if (!canDraw()) {
            return;
        }
        boolean on = false;
        try {
            on = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Throwable t) {
            Log.w(TAG, "reading airplane mode failed", t);
        }
        if (on == airplane) {
            return;
        }
        airplane = on;
        stripView.setAirplane(on);
    }

    /** SIM readiness only; the level arrives through the signal callback. */
    private void updateSimState() {
        if (!canDraw() || telephonyManager == null) {
            return;
        }
        boolean nowReady = false;
        try {
            nowReady = telephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY;
        } catch (Throwable ignored) {
        }
        if (nowReady == simReady) {
            return;
        }
        simReady = nowReady;
        stripView.setSignal(simReady, signalLevel, cellularTransport);
    }

    private void updateSignalLevel() {
        if (!canDraw() || telephonyManager == null) {
            return;
        }
        int level = signalLevel;
        try {
            SignalStrength strength = telephonyManager.getSignalStrength();
            if (strength != null) {
                level = strength.getLevel();
            }
        } catch (Throwable ignored) {
            // No READ_PHONE_STATE or no radio: fall back to the cached level.
        }
        if (level == signalLevel) {
            return;
        }
        signalLevel = level;
        stripView.setSignal(simReady, signalLevel, cellularTransport);
    }

    private void registerTelephonyCallback() {
        if (telephonyCallback != null || phoneStateListener != null
                || telephonyManager == null) {
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
                telephonyCallback = Api31.registerSignalCallback(telephonyManager, this);
            } else {
                PhoneStateListener listener = new PhoneStateListener() {
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

    private void unregisterTelephonyCallback() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && telephonyCallback != null
                    && telephonyManager != null) {
                Api31.unregisterSignalCallback(telephonyManager, telephonyCallback);
            } else if (phoneStateListener != null && telephonyManager != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        } catch (Throwable ignored) {
        }
        telephonyCallback = null;
        phoneStateListener = null;
    }

    /** API 31+ telephony callback; this class is only loaded on API 31+. */
    private static final class Api31 {
        static Object registerSignalCallback(TelephonyManager tm, StatusStripService service) {
            SignalCallback callback = new SignalCallback(service);
            tm.registerTelephonyCallback(service.getMainExecutor(), callback);
            return callback;
        }

        static void unregisterSignalCallback(TelephonyManager tm, Object callback) {
            if (callback instanceof TelephonyCallback) {
                tm.unregisterTelephonyCallback((TelephonyCallback) callback);
            }
        }

        private static final class SignalCallback extends TelephonyCallback
                implements TelephonyCallback.SignalStrengthsListener {
            private final StatusStripService service;

            SignalCallback(StatusStripService service) {
                this.service = service;
            }

            @Override
            public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                service.handleSignalStrength(signalStrength);
            }
        }
    }

    private void registerKeyguardListener() {
        if (Build.VERSION.SDK_INT < 33 || keyguardManager == null) {
            return;
        }
        try {
            keyguardLockedListener = Api33.registerKeyguardListener(keyguardManager, this);
        } catch (Throwable t) {
            Log.w(TAG, "keyguard listener not registered", t);
        }
    }

    /** API 33+ keyguard listener; this class is only loaded on API 33+. */
    private static final class Api33 {
        static Object registerKeyguardListener(KeyguardManager km, StatusStripService service) {
            KeyguardManager.KeyguardLockedStateListener listener =
                    new KeyguardManager.KeyguardLockedStateListener() {
                        @Override
                        public void onKeyguardLockedStateChanged(boolean isKeyguardLocked) {
                            try {
                                if (isKeyguardLocked) {
                                    service.removeOverlay();
                                } else if (service.interactive) {
                                    service.addOverlay();
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "keyguard listener failed", t);
                            }
                        }
                    };
            km.addKeyguardLockedStateListener(service.getMainExecutor(), listener);
            return listener;
        }

        static void unregisterKeyguardListener(KeyguardManager km, Object listener) {
            if (listener instanceof KeyguardManager.KeyguardLockedStateListener) {
                km.removeKeyguardLockedStateListener(
                        (KeyguardManager.KeyguardLockedStateListener) listener);
            }
        }
    }

    // ---- state ------------------------------------------------------------

    /**
     * First fill after the overlay appears. One read per source; afterwards each
     * value is refreshed only by the event that can change it.
     */
    private void updateAll() {
        try {
            Intent battery = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            handleBattery(battery);
        } catch (Throwable ignored) {
        }
        updateWifiState();
        updateAirplane();
        updateSimState();
        updateSignalLevel();
        scheduleNetworkRefresh();
        if (canDraw()) {
            stripView.refreshClock();
        }
    }

    private void handleSignalStrength(SignalStrength strength) {
        try {
            if (strength == null || !canDraw()) {
                return;
            }
            int level = strength.getLevel();
            if (level == signalLevel) {
                return;
            }
            signalLevel = level;
            stripView.setSignal(simReady, signalLevel, cellularTransport);
        } catch (Throwable t) {
            Log.w(TAG, "signal update failed", t);
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
        if (instance == this) {
            instance = null;
        }
        removeOverlay();
        unregisterScreenReceiver();
        if (keyguardLockedListener != null && keyguardManager != null
                && Build.VERSION.SDK_INT >= 33) {
            try {
                Api33.unregisterKeyguardListener(keyguardManager, keyguardLockedListener);
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
