# 23 — The theme factory: restyling stock APKs at the resource level

**Question.** Can we read a stock app's components, map them onto the ztMetro
design system and generate a restyled version *without rebuilding the APK*?

**Answer.** For a View-based app that draws with its own resources and attrs —
mostly yes, and the prototype in `tools/theme-factory/` does it end to end on the
real Etar Calendar APK: 423 resources mapped, a 49 KB signed RRO, 522 values
verified with `aapt2`, no code in the overlay. For everything the app draws with
code (hardcoded colours, Compose, WebView, Canvas, games) — no; that is the
runtime half in §8, estimated per app.

This document is the honest architecture: what the factory does, what it cannot
do, why the Calendar result is only *partially* visible on a Monet device, and
how the existing token→RRO generator generalises into it.

---

## 1. What the factory is

```
   pulled APK ─┬─ aapt2 dump resources ──▶ resource table (types, names, configs)
               ├─ aapt2 dump badging    ──▶ package, versions, launchable activity
               ├─ aapt2 dump xmltree    ──▶ AndroidManifest.xml + 193 layouts
               └─ android.jar           ──▶ framework attr/colour id → name
                                                │
                     role heuristics (deterministic, no ML)
                                                ▼
                                   app-profile.json
                     mappings[]: resource → token, confidence, evidence
                     unmanaged[]: what the factory refuses to touch, and why
                                                │
                          generate (filter by confidence/role)
                                                ▼
        res/values[-<qualifier>]/colors.xml + dimens.xml + AndroidManifest.xml
                                                │
                       aapt2 compile → aapt2 link → zipalign → apksigner
                                                ▼
                     ztmetro-<pkg>-rro.apk  (hasCode=false, resources only)
                                                │
                      verify.sh (aapt2 value diff + PIL screenshot diff)
                                                │
                            apply: the adb recipe the lead runs
                      push to Magisk /product/overlay → cmd overlay enable →
                      restart app → capture → crash watchdog → rollback
```

Three subcommands, one entry point: `repo/scripts/theme-factory.sh analyse |
generate | apply`.

The factory never talks to the phone. It produces an APK and a script; the lead
owns the device.

## 2. What it can actually do

Implemented and verified on `ws.xsoh.etar` 1.0.57 (the current LineageOS/F-Droid
Etar; the historical `org.lineageos.etar` id was renamed, so the tool reads the
package from the APK instead of trusting a constant):

| capability | evidence in `evidence/theme-factory/` |
|---|---|
| enumerate colours, dimens, styles, drawables, layouts | 7865 resources / 1231 colours parsed from the dump |
| classify surfaces / accents / text / status colours | 423 mappings, 74 % of the colour resources the app actually uses |
| radius + type ramp substitution | 29 corner dimens → 0dp, 13 app sp sizes → nearest ramp step |
| emit an RRO that only touches those names | 522 values, 15 config dirs, 0 missing entries |
| config-qualified overrides | `(v31)`, `(night)`, `(v34)`, `(v35)`, `land`, `sw600dp`, `w600dp-h879dp`… all mirrored |
| signed, inspectable artefact | `ztmetro-ws_xsoh_etar-rro.apk`, sha256 in §10 |
| value-level verification | `verify.sh` → “522 values OK, 0 mismatched, 0 missing” |
| screenshot regression recipe | `verify.sh --screenshots a.png b.png` → changed-pixel % + diff PNG |
| device recipe + rollback | `apply` output: push, enable, restart, capture, watchdog, disable |

What a mapping means concretely: Etar's `color/background_color` `#fffafafa` →
`#000000`; `color/colorPrimary` `#ff41c3b1` → `#0050EF`; `color/colorAccent`
(used as text somewhere, so contrast-checked) → `#3D7BFF`; the page surfaces of
the Material 3 palette `m3_sys_color_{light,dark}_surface*` → `#000000` /
`#1F1F1F`; every `m3_sys_*_outline*` → `#333333`; `m3_sys_*_on_surface*` →
`#FFFFFF`/`#999999`; `mtrl_card_corner_radius` 4dp → 0dp.

Because those are the resources Material components resolve their theme attrs
from, a successful overlay restyles AppCompat/Material widgets *without touching
their layouts*: buttons, cards, dialogs, chips, the calendar grid's paints, the
app bar, the agenda list.

## 3. What it cannot do

1. **Layouts and behaviour.** An RRO has no views and no code. Padding, ordering,
   tile size, the bottom bar, gestures — unchanged. If Etar draws a rounded
   floating action button *in code*, the factory does not touch it.
2. **Colours written in code.** `setBackgroundColor(0xFF33B5E5)` in a Java file is
   not a resource. The analyser can see such colours only when they appear in
   layout XML (it reports those: `unmanaged[].kind = layout-hardcoded-color`).
3. **Colours baked into drawables.** Shape/vector XML with inline `<solid>` or
   `<corners>`, nine-patches, PNG assets. The factory can replace those files in
   principle; v1 does not, and says so (`unmanaged: drawables`).
4. **Theme attrs set inside a style bag.** An overlay style replaces the *whole*
   bag, including its parent. Replacing `Theme.Material3.DayNight` to change six
   colour attrs would delete the other 300 and break the app. So v1 does not
   override styles at all. This is exactly why Etar's Monet themes are a partial
   miss (§5).
5. **Material dynamic colour (Monet).** If the app applies a `ThemeOverlay.*DynamicColors*`
   or sets `colorPrimary=@android:color/system_accent1_200` in its own theme bag,
   the theme resolves at window creation from system resources, not from the
   colour resources we override. We can override the *colour resource*
   `color/colorAccent` (including its `(v31)` variant) — and we do — but a theme
   bag that points straight at `@android:color/system_*` bypasses that.
6. **Compose.** `MaterialTheme(colorScheme = …)` / `Color(0xFF…)` live in Kotlin
   code. Resources may exist, but a Compose app usually ignores them. Needs the
   runtime half.
7. **WebView / HTML.** Needs injected CSS (`WebViewClient` hook or `evaluateJavascript`),
   i.e. code.
8. **Games / SurfaceView / GL.** Nothing resource-shaped to override.
9. **App-private widgets with hardcoded styles** (Canvas-drawn charts, camera
   overlays, calendar's own `EventView` paint list). Java paints → runtime half.
10. **Icons and fonts.** Icon geometry is drawable XML; the factory does not
    redraw glyphs. Typography is limited to *sizes* (and only when a `sp` dimen
    drives them); families need a font module / runtime hook.
11. **Signature-protected behaviour and permissions** — irrelevant to theming but
    worth saying: the overlay cannot grant anything the app does not have.

The rule of thumb the prototype confirms: **resource-level restyle gets you the
chrome and the component colours; the app's identity survives** (its custom
drawing, its spacing, its animations). That is the intended ztMetro outcome —
recognisably Metro, still the same app.

## 4. How the mapping decides (and why you can audit it)

The profile is the contract between analyse and generate, and every entry carries
its evidence:

```json
{"resource": "color/calendar_date_banner_background",
 "role": "surface", "token": "surface",
 "value_before": "#ffeeeeee", "value_after": "#000000",
 "configs_after": {"": "#000000", "night": "#000000"},
 "confidence": 0.8,
 "evidence": ["name contains a surface word", "chroma 0.00, luminance 0.85",
              "page surface -> pure black"]}
```

Signals, in decreasing strength:

1. **Material semantic names** (`m3_sys_color_light_surface`, …): role fixed by the
   spec, confidence 0.95, statistics not consulted.
2. **Usage**: which attributes the app itself puts the resource into
   (`windowBackground`, `textColor`, `colorAccent`, `tint`). Collected from every
   style bag and from `aapt2 dump xmltree` of all 193 layouts.
3. **Name**: `background|surface|card|dialog` → surface, `text|foreground|fg_` →
   text, `accent|primary|highlight` → accent (accent-by-name only counts if the
   colour actually has chroma, otherwise “selected” black would become the brand
   blue).
4. **Colour statistics**: chroma (saturation) for accent, luminance for the
   primary/secondary text split, alpha for “translucent → leave alone”.

Guards that keep the output sane — each one exists because the first run got it
wrong on the real APK:

* saturated colours never become surfaces (`day_clicked_background_color` #33b5e5
  is a selection accent, not a page);
* greys named “background” stay surfaces, not text (`event_info_label_background_color`);
* low-contrast roles do not get “fixed” (`text_disabled` stays 2.5:1 on purpose);
* `*_thumb_radius`, `*_halo_radius`, progress radii are widget sizes, not corners;
* an `sp` dimension more than 4sp from the nearest ramp step is left alone
  (a 36sp widget number mapped to 25.5sp would change layout);
* shadows, scrims, ripple masks and translucent colours are not theme slots.

## 5. What the real Calendar APK taught us

These are findings from the actual APK, not hypotheticals:

1. **The package id is not what it used to be.** Current Etar is `ws.xsoh.etar`,
   not `org.lineageos.etar` (the Java namespace `com.android.calendar` survives in
   the activity name). The tool reads `aapt2 dump badging` and the registry lists
   both ids, so an old dump is not silently refused.
2. **No `<overlayable>` policy.** `aapt2 dump xmltree AndroidManifest.xml` shows no
   `<overlayable>` tag, so a *preinstalled* overlay may map any resource
   (`docs/10b-overlay-and-hook-facts.md` §1). This is why the factory can emit 423
   names; on an app with a policy it must stop and take `--target-name`.
3. **Config-specific values are the whole ball game.** Etar declares colour
   entries under `(v31)`, `(v34)`, `(v35)`, `(night)`, `land`, `sw600dp`,
   `w600dp-h879dp`, `w960dp-h527dp`. A default-only overlay is outranked by the
   target's `(v31)` entry on Android 12+; the factory mirrors the target's
   qualifier set (15 `values-*` directories) and the verifier checks each config.
4. **Monet is the fly in the ointment.** `style/CalendarAppThemeDarkMonet` has only
   a `(v31)` bag whose items are `colorPrimary=@android:color/system_accent1_200`
   etc. On a device where the user enabled Monet and the app picked that theme,
   the theme attrs come from the system dynamic palette; overriding colour
   resources cannot re-point them. The factory detects this: the profile's
   `theme.dynamic_color_styles` lists the affected styles, and the honest status is
   **partially themed** for those users. Two ways out, both requiring more than an
   RRO: (a) a conservative style-bag override that re-declares the full bag with
   the colours swapped (doable — the dump gives the whole bag — but it is an
   all-or-nothing replacement and needs its own verifier), or (b) the runtime half
   (§8), hooking `Resources`/`Theme` before the window is created.
5. **Type ramp substitution is the least safe part.** 13 `sp` dimens were in range;
   the 36sp widget day number was refused. Even the accepted ones (10sp→11.5sp,
   22sp→22sp) change line metrics inside fixed-height rows; they deserve a
   screenshot diff per screen before shipping.

## 6. Safety model

Implemented in the prototype:

| guard | where | what it does |
|---|---|---|
| per-app registry, default deny | `tools/theme-factory/registry.json` | only listed packages build; `--force-package` is a recorded one-off |
| whitelist of emitted roles | `generate --min-confidence`, `--no-radii`, `--no-type` | nothing but the mapped roles is written |
| `<overlayable>` refusal | `generate.check_policy` | stops unless `--target-name <group>` is given |
| contrast ≥ 4.5:1 | `analyze._contrast_checks` | text and text-used accents are measured on #000000; failures escalate to the lighter token and are recorded in `safety.checks` |
| config parity | `generate.build_tree` | one overlay entry per target config, no more, no less |
| resource-only proof | `generate.verify_apk` | no `classes*.dex`, `hasCode=false`, `targetPackage` present |
| value proof | generated `verify.sh` | aapt2 compares every emitted value per config against the profile |
| visual proof | generated `verify.sh --screenshots` | changed-pixel percentage + diff PNG; a size change is reported as “layout/behaviour changed” |
| crash watchdog | generated `rollback-watchdog.sh` | enable → restart → 30 s → if `logcat -b crash` mentions the package: disable, force-stop, save log, relaunch unthemed |
| rollback | `apply` §7 | `cmd overlay disable` + force-stop; removing the APK from the module is the last resort |
| reboot discipline | `apply` §2 | a *new* preinstalled overlay is only scanned at boot; the recipe says so instead of pretending `cmd overlay` will see it |

Not implemented yet, listed so nobody assumes it:

* a device-side health check (`dumpsys` frame stats / ANR watch) beyond the crash buffer;
* automatic per-screen screenshot capture;
* a registry entry with a real sha256 pin (the field exists, empty today);
* an “overlay budget” guard (523 entries is fine; 5000 would be slow to idmap).

## 7. Generalising the existing token→RRO generator

`design/tokens/gen_android.py` already does *tokens → Android resources* for the
hand-written overlays (`settings`, `systemui`, `framework`). The factory is the
same arrow with an app in the middle:

```
before:  tokens ──gen_android.py──▶ res/values/*.xml ──build-overlay-*.sh──▶ RRO
                                  (resource names chosen by a human)

after:   tokens ──┐
                  ├── theme-factory analyse ──▶ app-profile ──▶ generate ──▶ RRO
        app APK ──┘        (names chosen by the app's own table)
```

There is one duplicated concern: both emit `colors.xml`/`dimens.xml` from the
token file. The factory currently formats its own XML. The natural refactor is to
make `gen_android.py` the **emitter backend**:

1. give `gen_android.py` a `--map profile.json` mode that writes the values tree
   from a profile instead of its built-in role names;
2. keep the factory as the *selector* (which resource names, which configs,
   confidence) and the *builder* (link/sign/verify);
3. hand-written overlays keep working unchanged, and their name choices become
   the seed of a per-app “adapter hint” layer (`registry.json` already has room).

That keeps one token parser, one XML formatting path, one set of naming rules
(`corner_radius` → 0dp, no letter-spacing, `sans-serif-light`), and makes the
factory's output stylistically identical to the overlays we ship today.

The same generalisation applies to the build step: `scripts/build-overlay-*.sh`
share a compile/link/zipalign/sign recipe; the factory's `Generator.build`
should eventually call that script (or the script should call the factory's
builder) so there is one RRO build path, not two.

## 8. The runtime half: per-app adapters for code-drawn widgets

Resource overlays stop where the app draws with code. The pieces we already
have — an LSPosed module for Settings and an accessibility overlay engine — are
the other half of the same factory. Design:

```
        app-profile.json
               │  (roles, tokens, and the resources it could NOT reach)
               ▼
   ┌──────────────────────────────────────────────────────────┐
   │ adapter = f(app)   (hand-written, reviewed, ≤300 lines)   │
   ├───────────────────────────────┬──────────────────────────┤
   │ LSPosed hook (in-process)     │ accessibility overlay     │
   │ • Resources/Theme colour      │ • draws over views it     │
   │   rewrite before inflate      │   cannot recolour         │
   │ • Compose MaterialTheme       │ • focus/reading order     │
   │ • Canvas paint interception   │ • no root, no hook        │
   │ • WebView CSS injection       │ • cannot change layout     │
   └───────────────────────────────┴──────────────────────────┘
               │                                 │
        crash-loop guard: disable on 2 crashes in 60 s, keep last-good config
```

Adapter contract (same as the resource half, so one registry can hold both):

* input: `app-profile.json` + a small adapter manifest (class names, hook points,
  fallback behaviour);
* output: a JSON “applied mapping” log, plus a `disable` path that is always
  callable (LSPosed: module scope off; accessibility: stop service);
* verification: resource half’s `verify.sh` screenshot diff *plus* a per-screen
  capture list, because code-drawn changes cannot be checked with aapt2.

Effort estimates (one engineer, this device, existing hooks as the base):

| piece | days | notes |
|---|---|---|
| generic `Resources`/`Theme` colour-rewrite hook (all apps, config-aware) | 3–4 | one Xposed class; the hard part is doing it before the first inflate and not leaking into other apps |
| Compose `MaterialTheme` recolour (per app) | 2–3 | needs the app's own theme composition point; version-fragile |
| Canvas/`Paint` colour interception (calendar grid, charts) | 2–4 per app | paint call sites are app-specific |
| WebView theme injection | 1–2 per app | CSS variables; depends on the app's HTML |
| accessibility overlay theming engine (no root path) | 5–7 | layout-agnostic tinting is limited; good for status/nav scrims, bad for in-app detail |
| per-app adapter + review + screenshots (steady state) | 1–2 per app | this is the recurring cost, not the engine |
| crash-loop guard + last-good config store | 2 | shared by both halves |

So: the **engine** is roughly a 3-week job; each further app is 1–4 days. The
resource factory costs minutes per app today, which is exactly why it should
front the pipeline and the runtime half should only be written for the screens
that the factory provably cannot reach.

Failure modes of the runtime half, in order of likelihood:

1. **Hook-point drift.** Obfuscated/minified app updates rename the class or move
   the method; the hook silently no-ops (best case) or crashes (worst).
2. **Crash loops** from hooking too early (before `Application.onCreate`) or from
   a `Theme` mutation inside a recycled view.
3. **SELinux / Zygisk / LSPosed version churn** — the hook works on today's ROM and
   not after the next OTA; this is the same maintenance class as the Settings styler.
4. **Compose internals** are not stable API: `MaterialTheme` composition locals
   can change between Compose versions.
5. **Performance**: per-draw `Paint` interception in a scrolling calendar; needs a
   cache and a fast path.
6. **Detection**: banking/DRM apps refuse to run with hooks; not our stock-app
   targets, but it constrains “theme every app”.
7. **Accessibility overlay z-order**: overlays sit above the app, so anything that
   must remain interactive underneath needs `FLAG_NOT_TOUCHABLE` and careful
   hit-testing; they cannot restyle what they cover without covering its content.

## 9. Decision

* Ship the resource factory for View apps that use their own resources: it is
  cheap, verifiable, reversible, and it already produces a usable Calendar overlay.
* Do **not** claim it themes Monet-calendar on a Monet-enabled device; the profile
  reports `theme.dynamic_color_styles`, and the runtime half (or a full theme-bag
  override with its own verifier) is the follow-up.
* Write runtime adapters only where the profile lists unmanaged surfaces *and* a
  screenshot shows it matters. The effort table above is the budget.
* Next concrete steps: (a) `--emit` backend shared with `gen_android.py`;
  (b) style-bag override mode with bag-completeness verification; (c) screenshot
  capture list per app in the registry.

## 10. Reproduce

```sh
repo/scripts/theme-factory.sh analyse --apk etar-fdroid.apk \
  --out repo/evidence/theme-factory/app-profile.json --print-table
repo/scripts/theme-factory.sh generate --profile repo/evidence/theme-factory/app-profile.json \
  --dry-run
repo/scripts/theme-factory.sh generate --profile repo/evidence/theme-factory/app-profile.json \
  --out repo/evidence/theme-factory/overlay
repo/evidence/theme-factory/overlay/verify.sh etar-fdroid.apk
repo/scripts/theme-factory.sh apply --profile repo/evidence/theme-factory/app-profile.json \
  --out repo/evidence/theme-factory/overlay --serial <device>
```

Artefacts (all in `repo/evidence/theme-factory/`):

| file | what |
|---|---|
| `app-profile.json` | the profile: 423 mappings, coverage, contrast checks, unmanaged list |
| `overlay/ztmetro-ws_xsoh_etar-rro.apk` | the signed overlay (sha256 in `overlay/build.json`) |
| `overlay/mapping-table.md` | the before/after table |
| `overlay/verify.sh` | aapt2 value diff + screenshot diff recipe |
| `overlay/rollback-watchdog.sh` | crash watchdog + automatic disable |
| `overlay/expected-mappings.json` | machine-readable expectations used by verify.sh |
| `REPORT.md` | run log, numbers, what was and was not verified on hardware |
