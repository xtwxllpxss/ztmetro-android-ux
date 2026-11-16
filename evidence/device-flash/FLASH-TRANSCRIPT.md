# Flash transcript — LineageOS-for-microG 23.2 on the phone (2026-11-16)

What was actually executed (the PLAN.md in this directory is the pre-flight plan; this file
is the record of the flash itself). Host: `hermes-ubuntu` over USB; device serial `4f2b50b2`.

## 1. Download and verify (host)

```
$ sha256sum -c lineage-23.2-20260812-microG-Mi8917-recovery.img.sha256sum
lineage-23.2-20260812-microG-Mi8917-recovery.img: OK
```
Build: `lineage-23.2-20260812-microG-Mi8917` (Android 16, SDK 36), zip 1 205 136 779 B,
recovery 32 790 528 B, boot 20 967 424 B. `super_empty.img` was deliberately NOT flashed
(super is retrofit over p24 system + p47 cust; the installer creates the metadata itself).

## 2. Recovery

```
$ fastboot devices
4f2b50b2    fastboot
$ fastboot getvar product
product: ugglite
$ fastboot flash recovery lineage-23.2-20260812-microG-Mi8917-recovery.img
Sending 'recovery' (32022 KB)  OKAY [ 1.009s]
Writing 'recovery'             OKAY [ 0.424s]
$ fastboot boot lineage-23.2-20260812-microG-Mi8917-recovery.img
```

Recovery UI: Advanced -> Enable ADB, then Factory reset -> Format data/factory reset and
Format cache partition (the ztOS ext4/swap superblocks made /data and /cache unmountable,
which aborted the first install with "failed to set up expected mounts for install").

```
Formatting /data...            (metadata-encrypted f2fs)
Formatting /cache...           (f2fs)
Formatting /metadata...        (ext4 on oem)
Data wipe complete.
```

## 3. Sideload

```
$ adb sideload lineage-23.2-20260812-microG-Mi8917.zip
serving: ... (~55%)   Total xfer: 1.19x
```
Recovery log:
```
Patching system image unconditionally...
Patching vendor image unconditionally...
Patching product image unconditionally...
Patching odm image unconditionally...
Patching system_ext image unconditionally...
Install completed with status 0.
```

## 4. First boot / identity

```
$ adb shell getprop ro.build.version.release   -> 16
$ adb shell getprop ro.build.version.sdk       -> 36
$ adb shell getprop ro.lineage.build.version   -> 23.2-20260812-microG-Mi8917
$ adb shell getprop ro.build.type              -> user
$ adb shell getprop ro.product.device          -> ugglite
$ adb shell getprop sys.boot_completed         -> 1
```

## 5. Root

Magisk v31.1: patch `boot.img` in the app, `fastboot flash boot magisk_patched.img`.
`adb root` (LineageOS "Rooted debugging") is used by the lead for system-level steps;
Zygisk enabled for LSPosed/Vector v2.2.

## 6. Notes / deviations from PLAN.md

- `super_empty.img` not used.
- The plan's battery gate (>50%) was waived by the owner; the flash ran at ~19% and the
  device later powered off during a boot at 18% (recovered by charging, not by reflashing).
- The first install attempt failed on unmountable /data and /cache until both were formatted.
