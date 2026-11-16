package org.ztmetro.actioncentre;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

/**
 * Transparent helper for the unlock path. It has no UI; it exists because
 * {@link KeyguardManager#requestDismissKeyguard} and
 * {@link KeyguardManager#createConfirmDeviceCredentialIntent} need an Activity,
 * and the lock overlay itself is a window of the accessibility service.
 *
 * <p>Non-secure keyguard: request the dismissal and finish. The accessibility
 * overlay stays on screen until {@code ACTION_USER_PRESENT}, so the stock lock
 * screen is never revealed during the transition.
 *
 * <p>Secure keyguard: launch the system credential confirmation
 * ({@code ConfirmDeviceCredentialActivity}), which is a normal
 * {@code showWhenLocked} activity above the keyguard. The overlay watches for
 * that window and removes itself only then, so the system PIN/pattern/password
 * screen is what the user sees next - never the stock lock screen.
 *
 * <p>If either call is refused (SecurityException, null intent, background
 * activity restriction), the proxy asks {@link LockOverlayController} to reveal
 * the stock keyguard and finishes: the old fail-safe, logged with the reason.
 */
public class UnlockProxyActivity extends Activity {
    static final String TAG = "UnlockProxy";
    private static final int REQUEST_CREDENTIAL = 1001;

    private KeyguardManager keyguardManager;
    private boolean finishing;
    private boolean flowStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UnlockTimeline.mark("proxy_on_create");
        try {
            // No UI and no touch: the overlay's bottom strip owns the gesture.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setDimAmount(0f);
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(false);
            }
            setFinishOnTouchOutside(false);
        } catch (Throwable t) {
            Log.w(TAG, "window setup failed", t);
        }

        keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguardManager == null) {
            fallback("no KeyguardManager");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // requestDismissKeyguard/credential launch require a visible activity;
        // onCreate is too early. onResume runs after the showWhenLocked window
        // is in place. The credential activity returning resumes us again.
        if (flowStarted || finishing) {
            return;
        }
        flowStarted = true;
        UnlockTimeline.mark("proxy_on_resume");
        startKeyguardFlow();
    }

    private void startKeyguardFlow() {
        if (keyguardManager == null) {
            return;
        }
        try {
            if (!keyguardManager.isKeyguardLocked()) {
                UnlockTimeline.mark("keyguard_not_locked_proxy_finish");
                finishProxy();
                return;
            }
            if (keyguardManager.isDeviceSecure()) {
                startCredentialConfirmation();
            } else {
                dismissNonSecureKeyguard();
            }
        } catch (Throwable t) {
            fallback("keyguard query failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /** Swipe-only lock: dismiss the keyguard; the overlay covers until USER_PRESENT. */
    private void dismissNonSecureKeyguard() {
        UnlockTimeline.mark("dismiss_keyguard_requested");
        try {
            keyguardManager.requestDismissKeyguard(this,
                    new KeyguardManager.KeyguardDismissCallback() {
                        @Override
                        public void onDismissSucceeded() {
                            UnlockTimeline.mark("dismiss_keyguard_granted");
                            finishProxy();
                        }

                        @Override
                        public void onDismissCancelled() {
                            UnlockTimeline.mark("dismiss_keyguard_cancelled");
                            fallback("dismiss cancelled by the system");
                        }

                        @Override
                        public void onDismissError() {
                            UnlockTimeline.mark("dismiss_keyguard_error");
                            fallback("dismiss error from the system");
                        }
                    });
        } catch (Throwable t) {
            // Includes SecurityException on ROMs that do not permit the call.
            fallback("requestDismissKeyguard not permitted: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Secure lock: the system credential screen, not the stock lock screen. */
    private void startCredentialConfirmation() {
        Intent intent;
        try {
            intent = keyguardManager.createConfirmDeviceCredentialIntent(null, null);
        } catch (Throwable t) {
            fallback("createConfirmDeviceCredentialIntent threw: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        if (intent == null) {
            fallback("createConfirmDeviceCredentialIntent returned null");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        UnlockTimeline.mark("credential_intent_launch");
        try {
            startActivityForResult(intent, REQUEST_CREDENTIAL);
        } catch (Throwable t) {
            fallback("credential activity launch failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREDENTIAL) {
            UnlockTimeline.mark("credential_result resultCode=" + resultCode);
            finishProxy();
        }
    }

    /**
     * Fail-safe: ask the overlay controller to reveal the stock keyguard (the
     * pre-unlock behaviour), so a refused dismissal/credential launch never
     * leaves the user behind an inert black window.
     */
    private void fallback(String why) {
        UnlockTimeline.mark("fallback: " + why);
        try {
            LockOverlayController.revealStockKeyguard(why);
        } catch (Throwable t) {
            Log.w(TAG, "fallback reveal failed", t);
        }
        finishProxy();
    }

    private void finishProxy() {
        if (finishing) {
            return;
        }
        finishing = true;
        try {
            UnlockTimeline.mark("proxy_finish");
        } catch (Throwable ignored) {
        }
        try {
            finish();
            overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "finish failed", t);
        }
    }

    @Override
    public void onBackPressed() {
        // Transparent helper: back should simply close it, never leave a window.
        UnlockTimeline.mark("proxy_back");
        finishProxy();
    }
}
