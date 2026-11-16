# Component — Lock screen

Owner: **D** (own ROM build). **B** can tint; nothing else is honest.
Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P8, Wave 2.6 / 3.1.
Tokens: `type.ramp.display_large`, `color.accent.dark1`, `motion.*`.
Citations: `upstream/ztOS/ztmetro/ztmetro.c`.

## Original behaviour

```c
#define LOCK_CLOCK_Y(server) ((double)(server)->height - 280)
#define LOCK_NOTE_Y(server)  ((double)(server)->height - 100)
```

(`:5921-5926`). The clock is 92 pt Light at `height - 268`, then two 30 pt Semilight
lines — `%A` at `-156` and `%B %-d` at `-120` (`:5930-5945`). It is anchored to the
bottom, not centred: the clock sits 268 pt above the bottom edge, so it rises with the
notification row when the phone wakes.

The background is a vertical fade, black until 25 %, then to **accent × 0.5** at the
bottom edge (`:6029-6035`):

```c
cairo_pattern_add_color_stop_rgb(fade, 0.25, 0, 0, 0);
cairo_pattern_add_color_stop_rgb(fade, 1.0, r * 0.5, g * 0.5, b * 0.5);
```

over the lock wallpaper if one is set (`:6036-6040`). Below that, one row of per-app
glyphs with counts (`:5954-5975`, 5 max), fading out when there are none. Unlock
feedback is a status wash — green `.4,1,.5` on success, red `1,.5,.5` on failure
(`:10162-10163`).

## Android mechanism

The keyguard on `lineage-23.2` is SystemUI's, and in Android 16 it is the
**Compose/flexiglass** keyguard. Three doors, all measured in this repo:

1. **B — RRO on `com.android.systemui`.** Only typography and colour of resources that
   still exist: the keyguard clock text appearance, the date line, notification card
   radius/colour (shared with the shade). No layout, no fade, no glyph row. Cheap and
   safe; it will not look like ztmetro.
2. **C — the clock plugin.** There *is* a supported seam:
   `com.android.systemui.plugins.keyguard.ui.clocks.ClockProviderPlugin`
   (`ACTION = com.android.systemui.action.PLUGIN_CLOCK_PROVIDER`), and its
   `ClockFaceLayout` could in principle carry our clock. Two blockers, both `[F]`:
   loading a plugin requires `com.android.systemui.permission.PLUGIN`,
   `protectionLevel="signature"` (`packages/SystemUI/AndroidManifest.xml:205-206`) — we do
   not have Lineage's key; and `ClockFaceLayout`'s View API is annotated
   *"Unsupported with flexiglass. Move to composables."* — so the seam exists precisely
   where it no longer works.
3. **D — fork the keyguard** in SystemUI: our clock composition, the accent fade, the
   glyph row, and the unlock wash.

A `showWhenLocked` activity is **not** a door: it draws on top of a security surface, it
cannot show notification content safely, and the phone's "swipe to unlock" setting
decides whether it is even reachable.

## Build order (Wave 3)

1. Fork the keyguard clock composition; keep the stock bouncer and biometric path
   untouched.
2. The accent fade as a drawable behind the clock layer (accent from the same token as
   the launcher; `dark1 = accent · 0.5`).
3. Notification glyph row from the same data source the shade uses.
4. Unlock wash on `KEYGUARD_DONE` / failure callback.

## Acceptance test

> Lock the phone: the time is 92 sp Light, 268 dp above the bottom edge, weekday and date
> under it in 30 sp Semilight, over a fade that is black for the top quarter and accent
> at the bottom edge. A wrong PIN flashes red, a correct one green.

## Risks

- **Security.** Anything that disables the keyguard to make our screen reachable is out
  of scope. This component does not touch authentication.
- **A half-done lock screen** is worse than Android's: an insecure `showWhenLocked`
  activity leaks notification text on the lock screen. If Wave 3 slips, stay on 2.6.
- **Update break:** total. The keyguard is rewritten between Android releases; a fork is
  a rebase, not a patch. Budget it per LineageOS major version.
- **Feature flag risk `[?]`:** flexiglass is a flag in some builds; if the flag is off,
  the View-based keyguard (and possibly the clock plugin API) is what runs. A fork must
  cover both paths or the ROM must pin the flag.
