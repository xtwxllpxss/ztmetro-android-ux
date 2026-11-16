# ztMetro IME (`org.ztmetro.ime`) — evidence

A self-contained `InputMethodService` APK that draws the ztMetro keyboard and
types into any app. Built with plain `aapt2`/`javac`/`d8`/`zipalign`/`apksigner`;
no Gradle, no AndroidX, no Material components, no permissions.

## Artifact

| | |
|---|---|
| APK | `repo/evidence/ime/ztmetro-ime.apk` (build output: `repo/ime/build/out/ztmetro-ime.apk`) |
| Size | 16,721 bytes (16.3 KiB) |
| SHA-256 | `5bf853b49a92994eaea58c86541c23d1d1fb53fecf7921516a90e3b9740d729f` |
| Package / version | `org.ztmetro.ime` / 1 (1.0) |
| min / target SDK | 29 / 36 (Android 10–16; LineageOS 23.2 = 36) |
| Label | `ztMetro keyboard` |
| Signature | v3 (minSdk 29); throwaway key under `ime/build/keystore` (gitignored) |

Build:

```sh
# on hermes-ubuntu, workdir ~/ztux/ime
cd ~/ztux/ime/repo && ./scripts/build-ime.sh
# locally the script also finds ~/android-sdk and ~/android-jdk:
repo/scripts/build-ime.sh
```

## What is implemented

- `ZtMetroIme.java` — `InputMethodService`, commits letters/space through
  `InputConnection.commitText`, deletes through `deleteSurroundingText` (raw
  `KEYCODE_DEL` fallback for password/custom editors), Enter through
  `sendDefaultEditorAction` or `KEYCODE_ENTER`. `onEvaluateFullscreenMode()`
  returns false so landscape uses the inline keyboard, not the extract view.
- `ZtKeyboardView.java` — custom `View`, no XML layout, no Material widgets,
  no ripple/StateListAnimator. Four rows on `#000000`; square unit keys with
  hard corners and 1 dp gutters; white 20 sp lowercase letters; pressed fill
  exactly `#333333`; dark letter keys `#1F1F1F`, darker function keys
  `#121212`.
  - Latin: 10 columns (qwertyuiop / asdfghjkl / zxcvbnm,.').
  - Russian: 11 columns, 11+11+11 = all 33 letters (йцукенгшщзх /
    ъфывапролдж / эячсмитьбюё). The 10-column brief is impossible for 33
    letters in three letter rows; 1:1 keys and four total rows are preserved.
  - Bottom row: RU/EN, shift, wide space, backspace, enter.
  - Shift is latched caps (tap again to release); letters and Cyrillic switch
    to uppercase at 20 sp.
  - Key size is derived from the incoming width as
    `(width - (columns + 1) * 1dp) / columns`, so the grid always fills the
    width and keys stay 1:1: ~34.9 dp (EN) / ~31.6 dp (RU) in 360 dp portrait,
    ~62.9 dp / ~57.1 dp at 640 dp landscape (>= 32 dp).
- `method.xml` — `ru-RU` (`Русский`) and `en-US` (`English (US)`) keyboard
  subtypes; RU listed first because the device UI is Russian, with a
  system-locale fallback. `AndroidManifest.xml` — exported service guarded by
  `android.permission.BIND_INPUT_METHOD`, `android.view.InputMethod` filter,
  `android.view.im` meta-data; no `<uses-permission>` at all.

Known limitation: Russian punctuation and digits require switching to EN (the
brief did not ask for a symbols layer); there is no prediction/autocorrect.

## Static verification (actual, on hermes-ubuntu)

- `aapt2 dump badging` → package `org.ztmetro.ime`, label
  `ztMetro keyboard`, minSdk 29 / targetSdk 36, version 1/1.0,
  `provides-component:'ime'`.
- `aapt2 dump xmltree AndroidManifest.xml` → `BIND_INPUT_METHOD`, exported
  service `.ZtMetroIme`, `android.view.InputMethod`, `android.view.im`,
  zero `uses-permission` elements.
- `aapt2 dump xmltree res/xml/method.xml` → `ru-RU` and `en-US` subtypes,
  both `imeSubtypeMode="keyboard"`.
- `dexdump -d classes.dex` → `Lorg/ztmetro/ime/ZtMetroIme;` with superclass
  `Landroid/inputmethodservice/InputMethodService;`, plus
  `Lorg/ztmetro/ime/ZtKeyboardView;`.
- `apksigner verify` → Verifies, v3 signer, cert DN `CN=ztMetro keyboard`.
- No AndroidX strings in `classes.dex`; APK is 16.7 KB (< 2 MB guard).

Raw outputs: `aapt2-badging.txt`, `aapt2-manifest.xmltree.txt`,
`aapt2-method.xmltree.txt`, `dexdump-service.txt`, `dex-facts.txt`,
`apk-contents.txt`, `apksigner-verify.txt`, `artifact-facts.txt`,
`ztmetro-ime.apk.sha256`.

## Enable and switch (lead / device owner)

```sh
adb install -r repo/evidence/ime/ztmetro-ime.apk
adb shell ime list -a -s                       # expect org.ztmetro.ime/.ZtMetroIme
adb shell ime enable org.ztmetro.ime/.ZtMetroIme
adb shell ime set org.ztmetro.ime/.ZtMetroIme

# fallback if the ime command is unavailable (secure settings)
adb shell settings put secure enabled_input_methods org.ztmetro.ime/.ZtMetroIme
adb shell settings put secure default_input_method org.ztmetro.ime/.ZtMetroIme
```

Switch on the device: the keyboard/IME switcher in the navigation bar (when
more than one IME is enabled), or Settings → System → Languages & input →
On-screen keyboard → `ztMetro keyboard`; `adb shell ime set ...` also switches
directly. To return to stock, find its ID via `ime list -a -s` and
`ime set <id>`. The IME cannot enable or select itself; the user (or the lead
over adb) must do it.

## Manual test plan

1. Install, enable, set as above; confirm `ime list -a -s` shows the service.
2. Open a text field (Messages, Notes, Settings search). The black 4-row
   keyboard must appear with no rounded keys, no ripple; a press paints the
   key `#333333`.
3. Type `privet`, space, backspace, `mir`; tap shift once and confirm latched
   uppercase (`MIR`); tap again and confirm lowercase. Enter: `⏎` action in a
   single-line search, newline in a multiline field.
4. Tap `EN` → `RU`; type `привет`; shift → `ПРИВЕТ`; shift back. Tap `RU` →
   `EN`; confirm Latin returns.
5. Rotate to landscape: keys must fill the width and stay square, at least
   32 dp; no fullscreen extract editor. Rotate back.
6. Focus a password field and a custom editor (e.g. browser address bar):
   backspace/enter must not crash; text must commit.
7. `adb shell dumpsys package org.ztmetro.ime | grep -i permission` — no
   requested/dangerous permission. `adb shell settings get secure
   enabled_input_methods` — contains `org.ztmetro.ime/.ZtMetroIme`.
8. Switch back to the stock IME and confirm the device remains usable.

## Risks / acceptance notes

- Not device-tested here: no adb in this scope (lead owns the phone). Only the
  static build/badging/dexdump/signature checks above are performed.
- The IME must be enabled and selected by the user; Android will not silently
  replace the stock keyboard.
- Landscape is width-derived and 1:1, so the keyboard is ~233–257 dp tall at
  640 dp width; on a 360 dp-high landscape app it covers most of the screen.
  This follows requirement 3 literally; a height cap can be added if the lead
  prefers more app area.
- Russian uses 11 columns (33 letters) instead of 10; portrait Russian keys are
  ~31.6 dp on a 360 dp-wide screen (the 32 dp minimum applies to landscape,
  where Russian keys are ~57 dp).
- Caps is latched, not one-shot; no punctuation/symbols layer, no autocorrect,
  no key previews/popups.
- Throwaway signing key: a future build from a different key needs an
  uninstall before `adb install -r`.
