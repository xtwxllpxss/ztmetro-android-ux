package org.ztmetro.ime;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.LocaleList;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.Locale;

/**
 * Minimal ztMetro IME.
 *
 * All key drawing is done by ZtKeyboardView; this service only translates
 * key events into InputConnection edits and chooses the active alphabet.
 * There is no candidates view, no prediction, no fullscreen extract mode.
 */
public final class ZtMetroIme extends InputMethodService implements ZtKeyboardView.Listener {

    private ZtKeyboardView keyboard;
    private boolean russian;
    private String lastSubtypeTag;

    @Override
    public View onCreateInputView() {
        keyboard = new ZtKeyboardView(this);
        keyboard.setListener(this);

        russian = detectRussianLayout();
        lastSubtypeTag = currentSubtypeTag();
        keyboard.setRussian(russian);

        if (getWindow() != null && getWindow().getWindow() != null) {
            getWindow().getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }
        return keyboard;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        setCandidatesViewShown(false);
        setExtractViewShown(false);

        if (keyboard != null) {
            keyboard.resetPage();

            // Follow a subtype change made in the system IME picker, but do
            // not overwrite the in-keyboard RU/EN toggle on every focus change.
            String tag = currentSubtypeTag();
            if (tag != null && !tag.equals(lastSubtypeTag)) {
                lastSubtypeTag = tag;
                russian = startsWithRussian(tag);
                keyboard.setRussian(russian);
            }
        }
    }

    @Override
    public void onFinishInput() {
        if (keyboard != null) {
            keyboard.resetPage();
        }
        super.onFinishInput();
    }

    /** A landscape text field must not force the fullscreen extract view. */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || text == null || text.isEmpty()) {
            return;
        }
        ic.commitText(text, 1);
    }

    @Override
    public void onBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            return;
        }

        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
            return;
        }

        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before != null && before.length() > 0) {
            ic.deleteSurroundingText(1, 0);
        } else {
            // Password fields and some custom editors hide the surrounding
            // text from the IME; a raw DEL still reaches their key handler.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    @Override
    public void onArrow(int keycode) {
        sendDownUpKeyEvents(keycode);
    }

    @Override
    public void onEnter() {
        EditorInfo info = getCurrentInputEditorInfo();
        boolean action = info != null
                && info.actionId != 0
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0;
        if (action && sendDefaultEditorAction(true)) {
            return;
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    }

    @Override
    public void onLanguageChanged(boolean russian) {
        this.russian = russian;
    }

    private boolean detectRussianLayout() {
        String tag = currentSubtypeTag();
        if (tag != null && !tag.isEmpty()) {
            return startsWithRussian(tag);
        }
        LocaleList locales = getResources().getConfiguration().getLocales();
        return locales.size() > 0 && "ru".equals(locales.get(0).getLanguage());
    }

    private boolean startsWithRussian(String tag) {
        return tag.toLowerCase(Locale.ROOT).startsWith("ru");
    }

    private String currentSubtypeTag() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) {
                return null;
            }
            InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
            if (subtype == null) {
                return null;
            }
            String tag = subtype.getLanguageTag();
            if (tag == null || tag.isEmpty()) {
                tag = subtype.getLocale();
            }
            return tag;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
