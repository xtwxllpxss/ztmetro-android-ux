# ztmetro Start v4 — live tiles (notification second face + WP flip)

**Status: built and statically verified on hermes-ubuntu; not installed or run on the
device** (adb is owned by the lead). One APK, same package `org.ztmetro.start`, same
v1/v2/v3 signing key, so the update path is `adb install -r`.

| | |
|---|---|
| APK | [`ztmetro-start.apk`](ztmetro-start.apk) — `launcher/build/out/ztmetro-start.apk` |
| size | **69 910 bytes** (68.3 KiB) < 3 MiB |
| sha256 | `8767ee7f2de894558796b14b65bde6ecd3f0ec5b42f3f232495df5509a605c6a` |
| package | `org.ztmetro.start` (versionCode **4**, versionName **4.0**) |
| min / target | 29 / **36** |
| signing | v3, cert SHA-256 `5f05e8e4…6457831` — the v1/v2/v3 key restored from `~/ztux/launcher-v3/launcher/build/keystore`, so `-r` updates in place |
| build | hermes-ubuntu, JDK 17.0.21, build-tools 36.0.0, platform android-36 |
| deps | Android SDK + JDK + Python 3 only; no Gradle, no AndroidX, no network |
| permissions | **0 `uses-permission`**; the listener service carries the system-enforced `BIND_NOTIFICATION_LISTENER_SERVICE` on the service element |

Static proof: [`aapt2-badging.txt`](aapt2-badging.txt),
[`aapt2-manifest.xmltree.txt`](aapt2-manifest.xmltree.txt),
[`aapt2-resources-live.txt`](aapt2-resources-live.txt),
[`apksigner-verify.txt`](apksigner-verify.txt),
[`dex-facts.txt`](dex-facts.txt), [`dexdump-classes.txt`](dexdump-classes.txt),
[`dexdump-service.txt`](dexdump-service.txt), [`live-facts.txt`](live-facts.txt),
[`apk-contents.txt`](apk-contents.txt), [`artifact-facts.txt`](artifact-facts.txt),
[`metro_tokens.generated.xml`](metro_tokens.generated.xml).

## What each tile shows

The second face is fed by one in-memory model, `LiveTiles`, filled by
`StartNotificationListener`. Mapping is deliberately narrow:

| tile | second face | when |
|---|---|---|
| messaging app | unread count in the upper-right corner (**if > 0**) + up to two 12 sp lines of the newest title/text | count > 0; kind from `Notification.CATEGORY_MESSAGE` or a package hint (`sms`, `mms`, `messag…`, `messenger`, `whatsapp`, `telegram`, `signal`, `viber`, `discord`, `slack`, `chat`, `wechat`, `talk`) |
| email app | same count + latest line | `CATEGORY_EMAIL` or package hint (`gmail`/`gm`, `mail`, `outlook`, `k9`) |
| calendar tile | **big day number**, short weekday above it, short month beside it at 12 sp; 1×1 shows the day number centred | package contains `calendar`; **no notification access needed** — derived from the local clock |
| music app | track title (EXTRA_TITLE), artist/second line (EXTRA_TEXT) at 12 sp, up to 2 lines | a media notification is present (`CATEGORY_TRANSPORT` or `EXTRA_MEDIA_SESSION`) |
| any other app | stays the static glyph-only face; a count is *not* fabricated | no matching content |
| 1×1 tiles | message/email: count centred (a 49 dp tile cannot fit 12 sp text); calendar: centred day number; music: static (no track title fits) | — |

The **label stays 13 sp at the bottom-left on both faces** for 2×2 / 4×2 tiles (the v3
rule that 1×1 tiles have no label is unchanged). The count is intentionally on the live
face only; the static face is glyph + label exactly as v3.

Persisted state: **none**. `LiveTiles` is a bounded in-process map — at most 24
packages, at most 48 notification keys per package (48 is the count bound); the listener
rebuilds it from `getActiveNotifications()` on connect and on every removal. No
SharedPreferences, no files, no database.

### What counts as unread

`count` is the number of active, non-summary, non-ongoing notifications of that package.
Group summaries are skipped (their children are the unread items), and ongoing /
foreground-service notifications are not unread — except media, whose whole purpose is
the track face. A media notification contributes a track title but no count.

## The WP flip

- **Trigger**: only `LiveTiles.Face.signature()` changing (kind, count, title, text, or
  the calendar day). No periodic timer exists anywhere in the tile path; unrelated
  notification traffic calls `refreshLive()`, the signature matches, and the tile does
  not move.
- **Motion**: `rotateY 0° → 180°` over **240 ms** (`metro_live_flip_ms`, inside the
  brief's 220–260 ms band), Log(10) ease-out (`Motion.WP_LOG`). The face is swapped
  exactly at **90°** (front for <90°, live for ≥90°), so the label and content turn with
  the tile.
- **Animator scale**: `Settings.Global.ANIMATOR_DURATION_SCALE` is read through
  `Motion.scale()` before each flip; **0 applies the new face instantly** with no
  animator. At scale 1 the flip is 240 ms; the framework scales the animator duration by
  the same system setting, as it does for the other launcher motion.
- **First bind**: no flip. `StartActivity` recreates the tile views on resume, so a
  return to Start shows the current content immediately instead of replaying a flip for
  every tile. Only a notification-driven change flips.
- **Implementation note**: the flip is a Canvas-level `Camera.rotateY` around the tile
  centre, so it composes with the press tilt, which owns the View's `rotationX/rotationY`.
  The press block (`highlightRunnable`, highlight/lean wash, `commitTilt`/`resetTilt`) is
  untouched from the motion handoff.

## Enable the listener (exact)

The service is never bound until the user grants notification access. The launcher
requests no permission of its own; the system enforces the bind permission on the
service.

```sh
# grant (this is the only enable step)
adb shell cmd notification allow_listener \
    org.ztmetro.start/org.ztmetro.start.StartNotificationListener

# verify
adb shell settings get secure enabled_notification_listeners
# expect ...org.ztmetro.start/org.ztmetro.start.StartNotificationListener...

# revoke (returns every tile to the static face; the calendar date face stays)
adb shell cmd notification disallow_listener \
    org.ztmetro.start/org.ztmetro.start.StartNotificationListener
```

Settings path (Android 11+/16): **Settings → Apps → Special app access →
Notification access → ztmetro start**. On older builds it is **Settings → Apps →
Special access → Notification access**. The launcher's own *appearance* sheet has a
"live tiles: on/off" row that opens this exact Settings screen, so no adb is needed.

## Expected on-screen behaviour

1. Install/update with the APK above and keep it as HOME (v3 report's install block is
   unchanged). Before the grant: Start is exactly the v3 glyph grid.
2. Grant access. The system binds `StartNotificationListener`; it rebuilds from the
   current shade (old notifications already there are included — see risks).
3. Send a messaging app **two** messages (or leave two already active). On the second
   content change the tile rotates around its vertical axis: the static glyph face
   compresses, at 90° it is edge-on, then the live face opens and shows **`2` in the
   upper-right corner** and the newest **sender + message text in up to two 12 sp
   lines**, with the app label still at the bottom-left.
4. A third message changes the count to 3 and flips again to the new line. Dismissing
   both notifications flips back to the glyph face. A media notification gives the
   music tile a track-title face; dismissing it flips back. A calendar tile shows
   today's big day number and flips when the date (signature) changes, not on a timer.

Because there is no device run in this task, the flip rendering and the exact count at
install time are the lead's device pass; the mapping, gating and static artifact are
verified below.

## Static verification performed

| check | result |
|---|---|
| build | `scripts/build-launcher.sh` exit 0 on hermes-ubuntu, JDK 17.0.21, build-tools 36.0.0 |
| badging | `org.ztmetro.start`, versionCode **4**, versionName **4.0**, label `ztmetro start`, `provides-component:'notification-listener'` |
| manifest | HOME activity; `StartNotificationListener` service + `BIND_NOTIFICATION_LISTENER_SERVICE`; **0 `uses-permission`** |
| dexdump | `Lorg/ztmetro/start/StartNotificationListener;` (superclass `NotificationListenerService`), `LiveTiles`, `MetroTileView` all listed |
| dex | 100 588 B; `LiveTiles`, `Motion`, `Turnstile` present; **0 androidx**; literals `animator_duration_scale`, `enabled_notification_listeners` present |
| resources | `metro_live_flip_ms` = **240**, `metro_live_date_fraction` = 34 %, `metro_live_count_fraction` = 15 %, `TextAppearance.Metro.LiveText` = caption 12 sp on `metro_on_accent` |
| size | **69 910 B** < 3 MiB |
| signature | `apksigner verify` passes, v3 scheme, cert `5f05e8e4…6457831` (same as v1/v2/v3) |
| sha256 | `8767ee7f2de894558796b14b65bde6ecd3f0ec5b42f3f232495df5509a605c6a` |

Not verified (no adb): install, listener grant/bind on the device, real notification
posts, flip timing/visuals, glyph/content overlap at font scale, RAM/PSS.

## What was touched (and what was not)

| file | change |
|---|---|
| `launcher/.../LiveTiles.java` | per-package `Entry` (kind/count/title/text/time), immutable `Face` + displayed-value signature, `calendarFace(now)`, calendar-package match, bounded publish; existing `Listener`/`isEnabled` API kept |
| `launcher/.../StartNotificationListener.java` | extended the v2 stub: kind mapping, media detection, ongoing/summary filtering, 24-package / 48-key bounds, rebuild on connect/removal; still no persistence |
| `launcher/.../MetroTileView.java` | second face (count corner, 12 sp lines, big calendar day, track title), 240 ms 0→180 flip with 90° face swap, signature gating, animator-scale handling; press/tilt block preserved |
| `launcher/.../MetroGridView.java` | `refreshLive()` feeds calendar faces or listener entries; no layout changes |
| `launcher/.../AndroidManifest.xml` | version 4.0 + access documentation; still no permission |
| `scripts/build-launcher.sh` | LiveText/caption style, 240 ms flip + two fractions, v4 checks, dexdump evidence, `evidence/live-tiles` output |

`StartActivity`, `MetroPager`, `MetroSheet`, `AppListView`, `TileStore` and the gesture
paths are unchanged from the motion handoff. Pivot swipe, swipe-down to Action Centre,
press tilt, pin/resize sheet and the app list are therefore not modified by live tiles;
the only new work on Start is the per-tile face feed.

APK and evidence rebuild:

```sh
# from the repo root on hermes-ubuntu
rsync -a --exclude .git --exclude evidence --exclude launcher/build ./ \
    hermes-ubuntu:~/ztux/livetiles/
ssh hermes-ubuntu 'cd ~/ztux/livetiles && ./scripts/build-launcher.sh'
rsync -a hermes-ubuntu:~/ztux/livetiles/evidence/live-tiles/ evidence/live-tiles/
```

## Risks and limits

- **Battery / process**: a granted notification listener keeps the launcher process
  alive in the background. There is no polling and no per-notification binder read
  except one `getActiveNotifications()` per removal/connect, plus a small map update
  per post. RAM/PSS was not measured on the device.
- **No notification access**: nothing live appears; the listener is not bound, the map
  is empty, and every tile is the static glyph face. The calendar date face still works
  because it needs no access. The appearance sheet points at the Settings screen.
- **Old notifications**: the count is the *active* notification count, not a curated
  "unread" count. A message left in the shade from yesterday counts and its text can sit
  on the tile until dismissed; there is no age expiry (deliberate: hiding active content
  would be worse than showing it). Persistent media notifications can keep a stale track
  title until the player updates/removes them.
- **Classification is heuristic**: apps that post neither a message/email category nor a
  matching package name, or a media notification without `EXTRA_MEDIA_SESSION` /
  `CATEGORY_TRANSPORT`, keep the glyph face. No settings UI was added for per-app
  mapping; it is a code list.
- **Calendar date at midnight**: no periodic timer by requirement, so the date rolls on
  the next resume or notification-driven refresh; it does not flip exactly at 00:00
  while Start is left open.
- **Flip rendering**: `Camera.rotateY` on a hardware-accelerated Canvas is statically
  compiled and the matrix math is the standard Android flip, but it was not seen on the
  device. If the GPU path shows clipping/perspective artifacts, the fallback is the 2D
  cosine projection or a View-level `rotationY` with a queued press interaction.
- **Animator scale**: values other than 0 scale the 240 ms (system-wide behaviour);
  scale 0 is the instant-swap path.
- **Process death**: the map is memory-only. After a process kill the listener rebuilds
  on reconnect; until that callback the tiles show the static face (no persisted copy to
  serve stale data).
