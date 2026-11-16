package org.ztmetro.actioncentre;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.net.wifi.WifiManager;

/** Best-effort read-only state where the platform exposes it without privilege. */
final class DeviceState {
    private DeviceState() {
    }

    static boolean wifiEnabled(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return false;
            }
            if (wm.isWifiEnabled()) {
                return true;
            }
            return wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean mobileDataEnabled(Context context) {
        try {
            int value = Settings.Global.getInt(context.getContentResolver(), "mobile_data", -1);
            if (value >= 0) {
                return value == 1;
            }
        } catch (Throwable ignored) {
        }
        // Fallback: an active cellular transport means data is on even when the
        // public setting is not readable on this build.
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean bluetoothEnabled(Context context) {
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter != null) {
                return adapter.isEnabled();
            }
        } catch (Throwable ignored) {
            // Android 12+ needs BLUETOOTH_CONNECT (dangerous) for isEnabled();
            // we intentionally do not request it.
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(), "bluetooth_on", 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean airplaneMode(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean rotationLocked(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 0..100, or -1 when the setting cannot be read. */
    static int brightnessPercent(Context context) {
        try {
            int value = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            if (value < 0) {
                return -1;
            }
            int percent = Math.round(value * 100f / 255f);
            return Math.max(0, Math.min(100, percent));
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
