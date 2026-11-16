# ztMetro IME (`org.ztmetro.ime`) — evidence

A self-contained `InputMethodService` APK that draws the ztMetro keyboard and
types into any app. Built with plain `aapt2`/`javac`/`d8`/`zipalign`/`apksigner`;
no Gradle, no AndroidX, no Material components, no permissions.

## Artifact

| | |
|---|---|
| APK | `repo/evidence/ime/ztmetro-ime.apk` (build output: `repo/ime/build/out/ztmetro-ime.apk`) |
| Size | 20,817 bytes (20.3 KiB) |
| SHA-256 | `38d8e3dcad4caf58529d6c81bb09ab7e5aac438d6a9bdd1b1a8670e99cc46196` |
| Package / version | `org.ztmetro.ime` / 2 (1.1) |
| min / target SDK | 29 / 36 |
| Label | `ztMetro keyboard` |
| Signature | v3 (minSdk 29); throwaway key under `ime/build/keystore` (gitignored) |

Build:

```sh
# on hermes-ubuntu
cd ~/ztux/ime2 && ./scripts/build-ime.sh
```

The script finds `~/android-sdk` (platform 36, build-tools 36.0.0) and
`~/android-jdk` (Temurin 17), and writes the APK plus the raw verification
outputs into `~/ztux/ime2/evidence/ime/`.

## Layout (KEYBOARD.md, followed literally)

360 dp portrait, 1 dp gutters, 56 dp key height, hard corners, black
background, white lowercase 20 sp letters. Geometrically at 360 dp:

| row | keys | x |
|---|---|---|
| 1 | `q w e r t y u i o p` (10) | 1 + i·35.9, width 34.9 |
| 2 | `a s d f g h j k l` (centred 9) | 18.95 + i·35.9, width 34.9 |
| 3 | shift, `z x c v b n m`, backspace | shift 0–53.85; z..m on the same 9-column grid (54.85 … 305.15); backspace 306.15–360 |
| 4 | `&123`, smiley, `,`, space, `.`, enter | `&123` 0–53.85; smiley at z 54.85; comma at x 90.75; space 126.65–269.25; dot at m 270.25; enter 306.15–360 |

`space` starts after comma and ends before dot; `&123` and `enter` absorb the
1 dp side margins. All touch targets reach half a gutter past the drawn key
and the outer keys reach the screen edge, so no pixel between keys is dead.

## Pages

- **letters** — EN `qwertyuiop / asdfghjkl / shift zxcvbnm backspace`;
  RU `йцукенгшщзх / фывапролджэ / shift ячсмитьбю backspace`. The Russian
  alphabet needs 33 letters, so RU uses 11/11/9 instead of 10/9/7; ё is a
  hold on е and ъ a hold on ь.
- **&123** — `1 2 3 4 5 6 7 8 9 0` /
  `. , ? ! ' " - / : ;` / `@ # $ % & * + = ( )`, plus `abc`.
- **emoji** — 24 emoji (3×8), `abc` returns to letters.

RU/EN is a visible key on the `&123` and emoji pages. On the letters page the
bottom row is exactly the six keys the spec names, so there is no seventh
slot for it.

## Implemented typing / interaction

- `commitText` for letters, digits, punctuation, space, emoji and Cyrillic;
  `deleteSurroundingText(1, 0)` for backspace with a raw `KEYCODE_DEL`
  fallback for password/custom editors; `sendDefaultEditorAction` or
  `KEYCODE_ENTER` for Enter.
- Shift is latched caps (tap again to release); RU/EN toggle is latched too.
- Hold the accent dot in the shift/&123 corner for 450 ms: keys disappear and
  the keyboard becomes a cursor trackpad. Horizontal drag sends unmodified
  `DPAD_LEFT/RIGHT` every 9 dp, vertical drag sends `DPAD_UP/DOWN` every
  18 dp, fractional steps are retained; lifting/cancelling restores the keys
  and types nothing; extra fingers are ignored.
- `onEvaluateFullscreenMode()` returns false; landscape uses the inline view.
- Page and caps reset when a new editor is focused.

## Deferred

- Key-preview popup above the finger.
- Prediction / autocorrect / suggestions (explicitly out of scope).
- A Settings UI for the 42–72 dp key height; the view uses the 56 dp default.
- Direct RU/EN key on the letters page (the spec’s six-key bottom row leaves
  no slot; the toggle lives on `&123`/emoji).
- The 28 dp dot target is implemented, but no on-device trackpad acceptance
  was possible here (no adb in this scope).

## Static verification (actual, on hermes-ubuntu)

- `aapt2 dump badging` → package `org.ztmetro.ime`, label
  `ztMetro keyboard`, minSdk 29 / targetSdk 36, version 2/1.1,
  `provides-component:'ime'`.
- `aapt2 dump xmltree AndroidManifest.xml` → `BIND_INPUT_METHOD`, exported
  service `.ZtMetroIme`, `android.view.InputMethod`, `android.view.im`,
  zero `uses-permission` elements.
- `aapt2 dump xmltree res/xml/method.xml` → `ru-RU` and `en-US` subtypes,
  both `imeSubtypeMode="keyboard"`.
- `dexdump -d classes.dex` → `Lorg/ztmetro/ime/ZtMetroIme;` with superclass
  `Landroid/inputmethodservice/InputMethodService;`, plus
  `Lorg/ztmetro/ime/ZtKeyboardView;`; the dex strings contain `&123`, `abc`,
  `space`, `RU`, `EN`.
- `apksigner verify` → Verifies, v3 signer, cert DN `CN=ztMetro keyboard`.
- No AndroidX strings in `classes.dex`; APK is 20.8 KB (< 2 MB guard).

Raw outputs: `aapt2-badging.txt`, `aapt2-manifest.xmltree.txt`,
`aapt2-method.xmltree.txt`, `dexdump-service.txt`, `dex-facts.txt`,
`apk-contents.txt`, `apksigner-verify.txt`, `artifact-facts.txt`,
`layout-facts.txt`, `ztmetro-ime.apk.sha256`.

Mockup: `mockups/screens/keyboard.html` now uses the same geometry and renders
to `mockups/out/keyboard.png` at 720×1280 (review sheet 1248×2778).

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

To return to stock: `adb shell ime list -a -s`, then
`adb shell ime set <stock id>`. The IME cannot enable or select itself; the
user (or the lead over adb) must do it.

## Manual test plan

1. Install, enable, set as above; `ime list -a -s` shows the service.
2. Open a text field: black keyboard, 56 dp hard-cornered keys, 1 dp gutters;
   top row 10, middle rows 9/9, bottom six keys; a press paints `#333333`.
3. Type `privet`, space, backspace, `mir`; shift → `MIR`; shift again → lower.
4. Tap `&123`: all digits and `. , ? ! ' " - / : ; @ # $ % & * + = ( )` commit;
   `abc` returns. Tap the smiley: 24 emoji commit; `abc` returns.
5. Tap `&123` → `RU/EN`; type `привет`, shift → `ПРИВЕТ`; hold `е` → `ё`,
   hold `ь` → `ъ`.
6. Hold the accent dot in the shift/&123 corner: keys disappear; drag right /
   left / up / down and verify the caret moves; release and verify nothing was
   typed and the keys return.
7. Enter: editor action in a single-line search, newline in a multiline field;
   password and browser address-bar fields do not crash.
8. `dumpsys package org.ztmetro.ime` shows no requested permission; rotate to
   landscape and back; confirm no fullscreen extract editor.

## Risks / acceptance notes

- Not device-tested here: no adb in this scope (lead owns the phone). Only the
  static build/badging/dexdump/signature checks above were performed.
- The keyboard is 229 dp tall at the 56 dp default: it covers about a third of
  a 640 dp portrait screen. That is the spec’s default; a Settings slider is
  deferred.
- Russian uses 11 columns and hold-alternates for ё/ъ; the 10/9/7 Latin shape
  cannot hold 33 Cyrillic letters.
- Throwaway signing key: a future build from a different key needs an
  uninstall before `adb install -r`.
