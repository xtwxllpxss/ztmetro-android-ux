# 03 — Theming without rebuilding: RRO, overlayable, Material You, fonts, icons

Snapshot: AOSP `android-16.0.0_r3`; AOSP RRO docs (fetched 2026-11); Android 12+
dynamic colour docs.
Labels: `[F]` fact, `[E]` estimate, `[?]` unverified / needs an on-device test.

## 1. RRO in one page

A Runtime Resource Overlay is a tiny APK with `android:hasCode="false"` and an
`<overlay>` element in the manifest `[F]` (AOSP RRO docs):

```xml
<manifest package="com.example.overlay">
  <application android:hasCode="false" />
  <overlay android:targetPackage="android"
           android:targetName="ThemeResources"
           android:resourcesMap="@xml/overlays" />
</manifest>
```

Key rules from the same document `[F]`:

- An RRO only changes the **value of an existing resource**; it cannot add resources,
  execute code or change layouts that are not resources.
- `android:targetPackage` is mandatory. `android:targetName` selects an
  `<overlayable>` group; it must be present **iff** the target defines overlayables.
- **Critical restriction:** *"If the target package doesn't have a defined
  overlayable, the overlay must be preinstalled on the system image or signed with
  the same signature as the target to overlay the target package's resources."*
- Android 10+ `<overlayable>` groups have policies: `public`, `system`, `vendor`,
  `product`, `oem`, `odm`, `signature`, `actor`, `config_signature`. An overlay needs
  to satisfy at least one policy of the group it targets.
- Android 11+ prefers `OverlayConfig` (`partition/overlay/config/config.xml`) over
  manifest attributes; static RROs (`android:isStatic="true"`) are enabled by default
  and immutable. Overlay precedence (low → high):
  `system < vendor < odm < oem < product < system_ext` `[F]`.
- Only immutable overlays targeting the `android` package affect
  `Resources.getSystem()` `[F]`.

## 2. `cmd overlay` — the only real control surface

AOSP `OverlayManagerShellCommand` (Android 16) implements `dump`, `enable`,
`disable`, `enable-exclusive`, `set-priority`, `fabricate`, `mimic` `[F]`:

```sh
adb shell cmd overlay list --user current
adb shell cmd overlay enable  --user current com.example.overlay
adb shell cmd overlay disable --user current com.example.overlay
adb shell cmd overlay fabricate --target android --name LighterGray \
    color/system_accent1_500 0xFFCCCCCC
```

- `fabricate` creates an overlay from a `target|name|value` list at runtime — the
  source explicitly errors with **"must be root to fabricate overlays through the
  shell"** when uid != root `[F]`. This is how tools like Repainter-style apps work
  on rooted/AOSP devices.
- `enable`/`disable` require the `CHANGE_OVERLAY_PACKAGES` signature permission
  (held by SystemUI and system components; shell has special handling, exact
  behaviour on `lineage-23.2` `[?]`).
- SystemUI itself holds `CHANGE_OVERLAY_PACKAGES` via the priv-app allowlist `[F]` —
  that is how dynamic colour applies overlays at runtime.

## 3. Material You / Monet: how the palette is produced

Android 12+ "Dynamic Color" is documented as a pipeline `[F]`:

1. The system extracts a **source colour** from the wallpaper.
2. It extrapolates **five key colours**: Primary, Secondary, Tertiary, Neutral,
   Neutral variant.
3. Each key colour becomes a **tonal palette of 13 tones**.
4. Five colour schemes (light/dark for each variant) are derived. Variants added over
   time: Tonal Spot (12), Neutral, Vibrant Tonal, Expressive (13) `[F]`.

In AOSP SystemUI this is implemented by `com.android.systemui.monet` +
`libmonet` and applied by `ThemeOverlayController`
(`packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java`) `[F]`:

- observes `Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES`;
- applies overlays in categories `OVERLAY_CATEGORY_ACCENT_COLOR`,
  `OVERLAY_CATEGORY_SYSTEM_PALETTE`, `OVERLAY_CATEGORY_DYNAMIC_COLOR`,
  `OVERLAY_CATEGORY_THEME_STYLE`;
- can create **fabricated overlays** for custom colours (the same root-only
  `fabricate` mechanism);
- reacts to user switches/work profiles by re-applying overlays to user 0.

Practical reading: the "theme" is a set of overlays of the **`android`**
(framework-res) resources that SystemUI and apps read (`system_accent1_*`,
`system_neutral*`, etc.), plus an overlay of SystemUI's own resources where defined.
Changing the palette globally = changing those overlay packages, not repainting
SystemUI code.

## 4. What is actually overlayable (verified vs unknown)

| Target | Overlayable definitions | What can be changed without a ROM build |
|---|---|---|
| `android` (framework-res) | Yes — overlayable theme groups (the RRO docs use `android`/`ThemeResources` as the canonical example) `[F]`; Android 12+ adds dynamic-colour resource groups | Accent/neutral colours, some dimensions, booleans, strings — subject to policies (`public`/`system`/`signature`). Root or system-image preinstall needed for non-`public` groups. |
| `com.android.systemui` | **No `overlayable.xml` found in `packages/SystemUI/res/values` in this pass** (values listing contains `colors.xml`/`dimens.xml`/`colors_tv.xml` only) `[?]` | If confirmed, third-party RROs cannot legally theme SystemUI resources; SystemUI palette changes must go through the `android` resources or a SystemUI fork. Verify by grepping the actual `lineage-23.2` tree for `<overlayable`. |
| Launcher3 | Launcher has its own resources; third-party launchers simply ship their own theme | Our launcher can paint anything it wants |
| Apps | Whatever each app exposes | Usually nothing meaningful |

`[E]` For our case that means: a global WP-ish palette + typography is realistic via
`android` overlays (root/Magisk, or a self-built ROM with a preinstalled overlay);
the SystemUI-specific widgets (shade corners, QS tile shapes) are **not** reachable
by a third-party RRO if SystemUI exposes no overlayables.

## 5. Fonts

- AOSP has an updatable-font mechanism (`FontManager`, Android 12+): a
  `fonts_customization.xml` in `/system/etc` enumerates font families/fallbacks that
  can be updated or overridden per-family (AOSP "Updatable fonts" docs) `[F]`.
- Font **files** are resources; an RRO can map a target `font/...` resource to its own
  font file, but whether the specific family is overlayable depends on the target's
  overlayable policy `[?]` (test on the exact LineageOS tree).
- Practical low-risk routes: (a) include the WP-like typeface inside our launcher APK
  (full control, zero system risk); (b) RRO the system font family if overlayable;
  (c) a self-built ROM preinstalling the font in `fonts_customization.xml`.
- Typography is a *big* part of the Metro look; the launcher must not rely on the
  system font being replaced.

## 6. Icons

- **Themed icons (Material You)**: apps can ship a monochrome icon layer; the system
  recolours it. That is a *per-app* opt-in, not a system-wide icon-pack engine.
- **AOSP Launcher3 does not have a user icon-pack API.** Third-party launchers
  (Niagara and most WP-style launchers) implement their own icon-pack reader; a custom
  launcher can therefore draw Metro-style tiles with the app's icon, tinted/treated as
  it likes.
- Emulating WP "live tiles" is a launcher feature: it needs a
  `NotificationListenerService` (user-granted access) to show unread counts/content,
  and the launcher can generate the tile artwork itself.

## 7. Concrete options for LineageOS 23.2 + microG on `Mi8917`

| Option | Mechanism | Depth | Risk |
|---|---|---|---|
| A. Launcher-only theme | our APK paints Start/tiles/app list; system UI untouched | launcher look | none (uninstallable) |
| B. Root RRO / fabricated overlays | Magisk + `cmd overlay fabricate` or preinstalled overlay APK; recolour framework + fonts | system palette, some dims/booleans | overlay bootloop; lost on system update; needs root |
| C. Magisk static overlay in `/product/overlay` with `config.xml` | overlay package signed as needed; can target non-overlayable targets only if same signature / system-image preinstall `[F]` | broader | AVB/OTA fragility |
| D. Self-built ROM with preinstalled overlays | overlay compiled into `product/overlay`, `system_ext/overlay` | full, clean | requires ROM build (see 06) |
| E. SystemUI fork | edit `SystemUI` resources/code | deepest | ROM build only |

Recommendation for Stage 1 (no ROM build): **A + (optional B)**. Keep an overlay
disable path (`adb shell cmd overlay disable …`, recovery) and test on a sacrificial
device first — a bad overlay can bootloop Android.

## 8. What theming cannot do

- Change layouts of SystemUI (shade shape/order, keyguard structure) — resource
  layouts are not overlayable in practice, and code is not overlayable at all.
  `[E]`
- Remove app-drawn toolbars/headers (see 02).
- Add new UI elements (a custom status bar clock layout, a WP "action centre"
  structure) — overlays only replace existing values.
- Survive a LineageOS OTA: system-image overlays/vendor partitions get replaced; a
  self-built ROM is a fork you must rebuild per release.

Continue with [`04-replacement-layers.md`](04-replacement-layers.md).
