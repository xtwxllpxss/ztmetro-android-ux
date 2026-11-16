# 13 — Root + injection setup on the real device (working document)

Status: working notes, filled in as the device work proceeds. Labels `[F]`/`[E]`/`[?]`.

## 0. Why this shape

The phone runs a **clean LineageOS 23.2 (microG)**, signed with the microG project's keys.
No platform key is available, and `packages/apps/Settings` is
`coreApp="true" android:sharedUserId="android.uid.system"` `[F: AndroidManifest.xml@lineage-23.2]`,
so no system APK can be re-signed and dropped in. Neither `SystemUI` nor `Settings` declares an
`<overlayable>` group, and idmap2 allows a *preinstalled* overlay to override any resource of a
target without overlayables `[F: frameworks/base/cmds/idmap2/libidmap2/ResourceMapping.cpp, CheckOverlayable]`.

Therefore the delivery stack is:

| Block | Mechanism | Needs |
|---|---|---|
| framework accent/typography/dimens/control styles | RRO targeting `android`, preinstalled in `/system/overlay` | Magisk |
| Metro Action Centre (shade/QS look) | RRO targeting `com.android.systemui`, preinstalled | Magisk |
| System font (sans-serif → Selawik) | Magisk module rewriting `fonts.xml` + font files | Magisk |
| Settings Metro header (new views) | LSPosed module hooking `SettingsBaseActivity` | Magisk + Zygisk + LSPosed |
| Start launcher | ordinary APK, default HOME | none |

Consequences to verify on device: resource overlays need a restart of the affected process
(SystemUI / Settings) before they are visible `[E]`; LSPosed support for Android 16 starts at
v1.10.2 (JingMatrix/Vector fork) `[F: release notes]`.

## 1. Magisk

- Patch `boot.img` (the one shipped inside the ROM zip) with the Magisk app on the device
  (`Install → Select and Patch a File`), pull the patched image, then
  `fastboot flash boot magisk_patched-*.img` `[E: standard flow]`.
- Magisk version: latest stable with Android 16 support (v29+; v30.x adds 16-QPR2 sepolicy)
  `[F: upstream changelog]`.
- Rollback: re-flash the clean `boot.img` from the ROM zip; modules are in `/data/adb/modules`.

## 2. LSPosed

- Install the LSPosed zygisk zip as a Magisk module, reboot, open the manager, enable the
  `ztmetro-settings-header` module and set its scope to `com.android.settings` `[E]`.
- Without LSPosed the header module is inert; the rest of the stack still works.

## 3. Overlays

- Module drops APKs into `system/overlay/` (scanned as preinstalled), and a late-start service
  enables them with `cmd overlay enable <pkg>` as root, idempotently `[E]`.
- Verify: `cmd overlay list`, then `dumpsys activity top`/screenshot after `am force-stop` of the
  target or a reboot.

## 4. Verification checklist (to be filled)

- [ ] `getprop ro.build.version.release` = 16, `ro.build.version.sdk` = 36
- [ ] `cmd overlay list` shows the three ztmetro overlays as enabled
- [ ] Accent visible in stock Settings (switches/sliders), stock dialog, and a plain-View APK
- [ ] System font is Selawik in Settings, launcher, and a third-party app; Telegram boundary documented
- [ ] Start tile launches a third-party APK
- [ ] Metro shade opens over the running app and closes
- [ ] Settings header on home, Wi-Fi, Display, App info; scroll + back behaviour
