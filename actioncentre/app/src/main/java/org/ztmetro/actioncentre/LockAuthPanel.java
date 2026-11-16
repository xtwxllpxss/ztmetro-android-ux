package org.ztmetro.actioncentre;

import android.view.View;

/**
 * Seam for a custom lock-screen authentication panel. The lock overlay is
 * opaque and covers the system keyguard; this interface is how a panel (the
 * reference {@link LockPinPad}, or a future one) takes over the clock area.
 *
 * <p><b>The panel is UI only.</b> It collects keystrokes and calls
 * {@link OnSubmitListener#onSubmit(String)}; it must not decide whether the
 * entered credential is correct. Verification belongs to the system:
 * {@code KeyguardManager.createConfirmDeviceCredentialIntent(null, null)} (what
 * {@link UnlockProxyActivity} launches for a secure lock) or, in a future
 * custom-lock build, a {@code DevicePolicyManager} path. Nothing in this app
 * stores, compares or forwards the PIN.
 *
 * <p>Layout and touch: {@link LockOverlayView#setAuthPanel(View)} inserts the
 * view returned by {@link #getView()} into the clock slot. The overlay's main
 * surface stays {@code NOT_TOUCHABLE}; {@link LockOverlayController} adds a
 * second, touchable accessibility window sized exactly to the panel and
 * forwards its touches to the panel. That window exists only while the pad is
 * shown, and the bottom swipe-up strip keeps working outside it.
 *
 * <p>Fail-safe: a panel that throws, reports invalid bounds or fails to attach
 * is removed by the controller and the clock screen plus the normal swipe-up
 * unlock path stay in place.
 */
interface LockAuthPanel {

    /** Receives the committed PIN string; verification is not done here. */
    interface OnSubmitListener {
        /**
         * Called once when the user commits the entry. The receiver must not
         * verify the value; the current wiring discards it and asks the system
         * for the credential screen.
         */
        void onSubmit(String pin);
    }

    /** The view inserted into the lock overlay's clock slot; never null. */
    View getView();

    /** Installs the submit callback; the panel never verifies the value. */
    void setOnSubmitListener(OnSubmitListener listener);

    /** Clears entered digits, e.g. when the overlay is shown again. */
    void reset();
}
