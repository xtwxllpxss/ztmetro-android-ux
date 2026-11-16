# ztMetro theme factory

A resource-level restyler for **stock APKs we did not build**: it reads an APK's
resource table, maps the resources to ztMetro tokens, emits a signed RRO overlay
that overrides only those names, and prints the exact device commands to apply
and roll back. No source, no rebuild, no fork of the app.

```
APK ──analyse──▶ app-profile.json ──generate──▶ ztmetro-<app>-rro.apk
                     │                                   │
                     │  roles + confidence + token        │  verify.sh (aapt2 diff, PIL screenshot diff)
                     ▼                                   ▼
              mapping-table.md                     repo/scripts/theme-factory.sh apply
```

## Commands

```sh
# 1. pull the APK (lead owns the device)
adb shell pm path ws.xsoh.etar
adb pull /product/app/Etar/Etar.apk Etar.apk

# 2. analyse: writes a JSON profile, ~9 s for Etar
repo/scripts/theme-factory.sh analyse --apk Etar.apk \
    --out repo/evidence/theme-factory/app-profile.json --print-table

# 3. look before you build
repo/scripts/theme-factory.sh generate --profile repo/evidence/theme-factory/app-profile.json --dry-run

# 4. build the overlay (aapt2 compile/link + zipalign + apksigner, throwaway key)
repo/scripts/theme-factory.sh generate --profile repo/evidence/theme-factory/app-profile.json \
    --out repo/evidence/theme-factory/overlay

# 5. prove the overlay says what it should
repo/evidence/theme-factory/overlay/verify.sh Etar.apk

# 6. print the device recipe (never runs adb)
repo/scripts/theme-factory.sh apply --profile repo/evidence/theme-factory/app-profile.json \
    --out repo/evidence/theme-factory/overlay --serial <serial>
```

`apply` also writes `rollback-watchdog.sh`: enable → restart the app → wait 30 s →
if `logcat -b crash` mentions the package, disable the overlay, save the crash log
and relaunch the unthemed app.

## What analyse classifies

| role | evidence | token |
|---|---|---|
| `surface` | low-chroma colour used as `background`/`windowBackground`/M3 `surface` | `color.neutrals.black`, `surface_off` (#1F1F1F) for cards/dialogs/rows, `surface_key` (#333333) for outlines |
| `text` | used in `textColor`/`colorOn*`, or a text-ish name | `text_primary` (#FFFFFF), `text_secondary` (#999999), `text_muted`, `text_disabled` |
| `accent` | high-chroma colour used for `colorPrimary`/`colorAccent`/tint | `color.accent.base` (#0050EF), `light1`/`dark1` for variants |
| `destructive` / `success` | saturated error/approve colours | `color.status.*` |
| `radius` | dimen whose name says corner/radius (widget sizes excluded) | `metrics.corner_radius_dp` = 0dp |
| `type` | app-owned `sp` dimension within 4sp of the ramp | nearest `type.ramp` step |

Material semantic names (`m3_sys_color_*`) short-circuit the statistics: their
role is fixed by the Material spec, so they get confidence 0.95 instead of 0.7-0.9.

Every candidate is written to the profile with `confidence`, `evidence`,
`values_before` and `configs_after`. Resources the factory cannot honestly touch
(hardcoded layout colours, colour state lists, styles, palette shades, widget
radii, type sizes far from the ramp) land in `unmanaged` with a reason.

## Safety

* **registry.json** — package allow list, default deny; `--force-package` is a
  one-off escape hatch that is recorded in `build.json`.
* **`<overlayable>` check** — if the target declares an overlayable policy the
  generator stops and demands `--target-name <group>`, because idmap2 would
  silently refuse the overlay otherwise.
* **config parity** — a target entry under `(v31)`/`(night)`/`(v34)` gets an
  overlay entry under the same qualifier; otherwise the target's more specific
  value outranks the overlay (the classic "my overlay does nothing on Android 12+"
  bug with Material dynamic colour).
* **contrast** — text and text-used accents are checked against #000000; below
  4.5:1 the mapping is escalated to a lighter token and the check is recorded in
  `safety.checks`. `text_disabled`/`text_muted` are exempt on purpose.
* **resource-only output** — the builder refuses an APK with `classes.dex` and
  verifies `android:hasCode="false"` plus `targetPackage` on the finished APK.
* **rollback** — `cmd overlay disable` is always the inverse; the watchdog does
  it automatically on a crash.

## Layout

```
theme_factory/aapt.py        locate aapt2/zipalign/apksigner/keytool, run them safely
theme_factory/restable.py    aapt2 dump resources -> resource table
theme_factory/analyze.py     heuristics -> app-profile JSON
theme_factory/generate.py    profile -> res/ tree -> signed RRO + verify.sh + mapping table
theme_factory/apply.py       printed adb recipe + rollback watchdog
theme_factory/verify_template.sh  generated verify.sh (aapt2 compare + PIL screenshot diff)
theme_factory/tokens.py      ztmetro.tokens.json -> role vocabulary
registry.json                per-app allow list (default deny)
```

Python 3.10+, standard library only. The generated `verify.sh --screenshots`
needs Pillow on the machine that holds the two PNGs.

Docs: `repo/docs/23-theme-factory.md` (architecture, limits, runtime half).
