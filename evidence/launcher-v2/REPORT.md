# ztmetro Start launcher v2 — build report

**Status: built and statically verified on hermes-ubuntu; not installed or run on the
device** (device offline, adb owned by the lead). Same package as v1 and signed with the
**same v1 throwaway key**, so the normal update path is `adb install -r`.

| | |
|---|---|
| APK | [`ztmetro-start.apk`](ztmetro-start.apk) |
| size | **49 430 bytes** (48.3 KiB) |
| sha256 | `5a6cdc3af7b976646e27a3c1003754c1d528010b3f7739671d234a26791ab0ee` |
| package | `org.ztmetro.start` (versionCode 2, versionName 2.0) |
| min / target | 29 / **36** |
| signing | v3, cert SHA-256 `5f05e8e4…6457831` — the same key that signed the v1 evidence APK |
| build | hermes-ubuntu, JDK 17.0.21, build-tools 36.0.0, platform android-36 |
| rebuild | a clean rebuild generates a new throwaway key; install the evidence APK for the in-place v1→v2 update |
| deps | Android SDK + JDK + Python 3 only; no Gradle, no AndroidX, no network |

Static proof: [`aapt2-badging.txt`](aapt2-badging.txt) (HOME + `notification-listener`
component), [`aapt2-manifest.xmltree.txt`](aapt2-manifest.xmltree.txt) (HOME activity,
listener service + `BIND_NOTIFICATION_LISTENER_SERVICE`, **no** `uses-permission`),
[`apksigner-verify.txt`](apksigner-verify.txt), [`artifact-facts.txt`](artifact-facts.txt).
The build script fails if `androidx` appears in `classes.dex`, if any permission is
requested, if the listener service/manifest entries are missing, or if the APK is ≥2 MB.

## Features implemented

1. **Live tiles (off by default).** `StartNotificationListener` maps active notifications
   to packages: count of non-summary notifications plus the newest title/summary. It is
   declared with the system binding permission, is never bound until notification access
   is granted, and `LiveTiles.isEnabled()` degrades the UI to the v1 static tiles when the
   grant is absent. The snapshot lives in-process (`LiveTiles`), so the tile grid refreshes
   on every post/remove without polling. Start shows a subtle badge; wide tiles show title
   + summary, medium tiles the newest title.
2. **Tile flip.** A changed notification triggers a one-shot 180° `Camera.rotateY` flip
   (250 ms token `metro_motion_flip_ms`), front face → dark-accent live face. No repeating
   timer: the face stays until the notification is removed. When notification access is
   off or the Start page is hidden, tiles update without animating.
3. **Pivot motion.** Header and pivot slide in from the travel direction and fade while
   the pages slide/fade (page 300 ms, out-gap 120 ms, decelerate) and the accent pivot
   underline moves; the transition is interruptible (animations are cancelled and retargeted
   from their current values). Tile drawing keeps all paints, matrices, icon rects and text
   layouts precomputed; `onDraw` allocates nothing.
4. **App-list semantic zoom.** Right-edge A–Z index (tap or drag) jumps to a section; the
   sticky section letter stretches while the index is dragged and as the next section
   pushes it out. A token-coloured search field at the top filters labels as you type;
   letters and the empty state update with the filter. Back clears the query before
   returning to Start.
5. **Appearance tile.** Always the last Start tile: four accent swatches from the generated
   colours, the word `appearance`, and a `live tiles on/off` line. Tap or long-press opens
   the existing Metro bottom sheet with **notification access** and **system settings**
   shortcuts — no separate settings activity or extra process.
6. **v1 kept working:** pin/unpin, medium/wide resize, app launching, predictive back,
   seeded tiles, SharedPreferences tile store, edge-to-edge black canvas.

**Deferred / deliberately not done:** no accent picker (the token accent is shown, not
changed); no queued flips (one tile flips at a time, latest change wins); removing a
notification snaps to the front face instead of animating a reverse flip; no onboarding
prompt for notification access (the appearance sheet links to it). None of these block the
shipped behaviours.

## Notification listener

Grant (exact):

```
adb shell cmd notification allow_listener org.ztmetro.start/org.ztmetro.start.StartNotificationListener
adb shell settings get secure enabled_notification_listeners   # must now contain the component
```

Revoke with `adb shell cmd notification disallow_listener org.ztmetro.start/org.ztmetro.start.StartNotificationListener`.
The APK itself requests **no** `uses-permission`; the system-only
`BIND_NOTIFICATION_LISTENER_SERVICE` is an attribute of the service. With the grant
absent or later revoked, `LiveTiles` stays empty and every tile is the static v1 tile.

## Token pipeline

Every colour, size and text appearance still comes from the generated
`res/values/metro_tokens.xml` (copy in
[`metro_tokens.generated.xml`](metro_tokens.generated.xml)). This build adds three
launcher-only generated entries, marked in the file as not ztmetro C facts:
`metro_pivot_indicator_width` 24 dp, `metro_pivot_indicator_height` 2 dp, and
`metro_motion_flip_ms` 250 ms (the WP half-turn requested for v2). Live text uses the
existing Body/Caption/Caps ramp; badge size is derived from the tile unit at runtime.

## Memory budget (estimate, to confirm with `dumpsys meminfo`)

Expected PSS on the 2 GB ugglite: **≈28–42 MB** with the listener enabled, **≈26–38 MB**
without, of which the deltas are ~8 tile icon bitmaps (≈0.3 MB), the app-list drawable
cache (1–3 MB), and the bound listener plus its small key/title map (<1 MB).
No foreground service, no continuous animation, no per-frame allocation in any `onDraw`.

## Top risks on a 2 GB device

1. **Listener residency.** Once granted, the system keeps this process bound; under memory
   pressure it can be unbound/killed and tiles go static until it reconnects. Notification
   access is also a high-trust grant, and `cmd notification allow_listener` is ROM-sensitive.
2. **Live-face cost.** A wide live tile holds two extra `StaticLayout`s; many pinned live
   tiles plus large font scale can add ~1–2 MB and risk text overlap/jank. The one-flip
   limit and fixed front/back layout widths are the mitigations; verify at font scale 1.3.
3. **Transition/IME races.** Interruptible page animations and edge-to-edge IME insets are
   guarded in code but were not device-tested; rapid pivot toggling and search on a 2 GB
   device are the first places to look for a stuck page or a covered list.

See [`MANUAL-TEST.md`](MANUAL-TEST.md) for the lead's device plan.
