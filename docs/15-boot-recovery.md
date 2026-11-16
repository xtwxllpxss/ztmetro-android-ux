# 15 — Recovering the phone when a module/overlay breaks boot

Working notes. Facts marked `[F]` (verified during the 2026-11-16 port) / `[E]` / `[?]`.

## Observed symptom (2026-11-16)

After installing and enabling the ztMetro stack the phone showed the boot logo, then a
black screen for ~30 s and powered itself off. Two candidate causes:

1. **Battery** — the device was at 18–19 % and charging from a USB 2.0 host port at
   ~80 mA (`current_now=80000`), while boot draws several hundred mA. Android's
   low-battery shutdown during boot matches the symptom exactly `[E]`.
2. **Overlay/module** — a broken framework-res overlay can make zygote/system_server
   crash-loop. That normally *reboots* (logo again) rather than powering off `[E]`.

Discriminator: charge the phone from a wall adapter (powered off) to ≥ 40 %, then boot.
- boots normally → cause 1; keep the stack, always flash/experiment above ~40 %.
- logo → black → reboot loop again → cause 2; use the isolation steps below.

## Isolation steps (in increasing order of destruction)

### 1. Magisk safe mode (no module mounted for one boot)
Hold **Volume Down** while the boot logo appears; Magisk skips modules for that boot `[F: Magisk docs]`.
The system then starts without the font module and without the overlay APKs. If it boots,
the culprit is one of our modules.

### 2. Disable the overlays from a booted (safe-mode) system
```sh
adb root
adb shell cmd overlay disable --user 0 org.ztmetro.overlay.framework
adb shell cmd overlay disable --user 0 org.ztos.overlay.systemui
```
Then reboot without safe mode (modules mount again, overlays stay disabled) — the
enable state is persisted by OverlayManager `[E]`. Re-enable one overlay at a time.

### 3. Remove the modules from recovery
Boot Lineage recovery (`fastboot boot <recovery>.img`), Advanced → Enable ADB, then:
```sh
adb shell 'mount /data 2>/dev/null; rm -rf /data/adb/modules/ztmetro-overlays /data/adb/modules_update/ztmetro-overlays'
```
(Only the overlay module; the font module is independent. `/data` is metadata-encrypted;
recovery mounts it with the same keys, so this works without wiping `[E]`.)

### 4. Font module only
Delete `/data/adb/modules/ztmetro-font` the same way, or `magisk --remove-modules` from
a root shell in recovery (`/debug_ramdisk/magisk`).

### 5. Last resort
Re-flash the stock `boot.img` from the ROM zip (removes Magisk root, keeps data) and
then re-root; or re-sideload the ROM (keeps the install, wipes nothing but system).
