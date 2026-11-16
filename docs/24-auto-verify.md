# 24 — Automated UX verification (`ux-verify.py`)

Written 2026-11-16. Replaces eyeballing screenshots with one command that
captures the live phone, measures every surface against
[`ztmetro.tokens.json`](../design/tokens/ztmetro.tokens.json) and the reference
frames, prints `PASS` / `FAIL` / `SKIP` with numbers, and writes annotated
evidence.

## Run

From `build/android-ux-research`:

```sh
/Users/meow/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3 \
  repo/scripts/ux-verify.py

# fast loop: Start + Settings home + Action Centre only
… repo/scripts/ux-verify.py --quick

# also 50 % blend vs the closest mockup + structural edge mismatch
… repo/scripts/ux-verify.py --diff-reference
```

Output: [`../evidence/verify/<timestamp>/`](../evidence/verify/20261116-145225/)
with `report.json`, `report.md`, `raw/<surface>.png`, `annotated/<surface>.png`,
and (with `--diff-reference`) `diff/<surface>-{blend,edges}.png`. Exit code is
`0` only when no check fails. The device is reached only through
`ssh hermes-ubuntu '~/android-sdk/platform-tools/adb …'`.

## What it captures

| surface | sequence | wait |
|---|---|---|
| start | wake/unlock, collapse shade, `KEYCODE_HOME`, `KEYCODE_BACK`, scroll grid to top | 3 s + 0.5 + 0.6 |
| app-list | `input swipe 650 700 120 700 300` (left pivot) | 2 s |
| settings-home | `am start -a android.settings.SETTINGS` | 4 s |
| settings-wifi | `am start -a android.settings.WIFI_SETTINGS` | 3 s |
| action-centre | Calendar intent, then `input swipe 360 6 360 400 250` (top-edge strip) | 2 s + 2 s |
| lock | `KEYCODE_SLEEP`, `KEYCODE_WAKEUP` | 1.5 s + 2 s |
| volume | wake/unlock, Settings home, `KEYCODE_VOLUME_UP`, then `BACK` | 4 s + 2 s |

Every capture also records `ro.build.version.sdk`, the enabled
`ztmetro|ztos` overlays, `pidof com.android.systemui`, window focus and keyguard
state. The screen is held awake during the run and the original stay-on setting
is restored at the end.

## Checks (28 in the full run)

| surface | checks |
|---|---|
| precondition | `pre.density_factor`, `pre.display_size` |
| start | no header text top 100 dp; ≥3 palette fills + cobalt; gutter 7 dp / margins 14 dp (±2); white glyphs in ≥3 tiles; no rounded tile corners |
| app-list | icon x 80 dp (±3), size 45 dp (±3), label x 138 dp (±4); ≥3 rows; contrast ≥4.5:1 |
| settings-home | black ≥70 %; no cobalt banner; title cap ≥60 px (34 dp); rows #E6E6E6..FFFFFF; exactly one large title block |
| settings-wifi | ON switch track/thumb #0050EF (±20); no >90 %-wide card with radius >8 dp |
| action-centre | focus is `org.ztmetro.actioncentre`; status row non-black; 8 tiles in 4×2; square ±3 dp; active fill #0050EF |
| lock | one large clock block; no status text top 40 dp |
| volume | panel in the lower third; panel background #000000 ≥50 % |

## Result of the 2026-11-16 14:52 run

**24 PASS · 4 FAIL · 0 SKIP.** Report:
[`report.md`](../evidence/verify/20261116-145225/report.md).

Failing checks:

1. **`pre.density_factor`** — `wm density` says **260 dpi**, so `px/dp` is
   **1.625**, not the brief's 1.75 (280 dpi). The script uses the measured
   factor everywhere; the check fails because the premise changed. At 1.75 every
   dp number would be 7.7 % off (app-list icon would read 41.7 dp instead of
   44.9 dp, and the x=80 dp check would false-fail).
2. **`settings.title_cap`** — measured cap **52 px = 32 dp** (implied ≈44.8 sp),
   below the required 60 px / 34 dp. The token is 58 sp; at 260 dpi its cap
   should be ≈67 px. The installed header is drawing a smaller title (or an
   older module) than the token.
3. **`wifi.switch_accent`** — ON switch track is **#002878** (the token's
   `accent.dark1`, i.e. 50 % accent over black) and the thumb is white. The
   required base **#0050EF** is not used; the switch is still stock Material.
4. **`volume.panel_visible`** — the stock right-side panel starts at **211 dp**
   (centre 386 dp); the lower third starts at 525 dp. Its background is black
   (64 %, `volume.panel_black` PASS), but the panel is not a bottom/lower-third
   panel.

Everything else passed, including the whole Start grid (gutter 6.77 dp,
margins 14.15 dp, 6 palette fills, 11 tiles with white glyphs), the app list
(icon x 80.0 dp, 44.9 dp square, label 139.7 dp, contrast 18.1:1), Action Centre
(8 tiles, 105.2×105.2 dp, four active cobalt fills), the keyguard (one 109 px
clock block, no status row), Settings rows and the black background.

## `--diff-reference`

Blends the capture 50/50 with the closest mockup (`start`, `app-list`,
`settings`, `action-centre`, `lock`) and reports the **structural** mismatch:
FIND_EDGES masks dilated by ±2 px, symmetric difference / union. This run:
start 87.2 %, app-list 91.8 %, settings-home 89.6 %, settings-wifi 91.9 %,
action-centre 80.1 %, lock 86.8 %, volume 88.3 %.

The mockups are 360 dp proposals rendered at 720×1280; the device is 443 dp wide
at 260 dpi, and the tile layout/state differs. The percentage is therefore a
coarse trend metric, not a pixel-pass gate. The blends show *where* the
structures diverge.

## Robustness / limits

- The sequence is interactive: it wakes, dismisses the shade and unlocks before
  each scenario; `KEYCODE_BACK` makes Start deterministic across runs; Settings,
  Action Centre, lock and volume retry once if the focus is wrong (one run was
  interrupted by a sleep-button event in logcat that put the keyguard up). A reboot mid-run aborts with the partial report already written.
- `adb` server death is retried once; boot is awaited up to 120 s.
- Detectors are colour/scanline heuristics, not OCR: a radically different
  future layout can require new thresholds.
- The one-shot Calendar intent is a no-op on this build; the top-edge strip is
  what actually opens the panel. The volume scenario raises the level by one
  step (no other persistent state is changed).
