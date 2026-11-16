# Device flash plan — LineageOS 23.2 (microG) on Xiaomi Redmi Note 5A (ugglite)

Preparation only. No flash, no reboot, no write to the phone was performed while writing this.
Labels: **[F]** read on the live phone/host or from the named source, **[E]** derived, **[?]** unverified.

---

## 1. Current state (2026-11-16, live readback)

**Device** [F]: `/proc/device-tree/model` = `Xiaomi Redmi Note 5A (ugglite)`, `compatible` =
`xiaomi,ugglite qcom,msm8917`. 2 GB RAM (1831 MB visible), 16 GB eMMC (`mmcblk0` 14.7 GiB, 48 partitions, GPT original).
Baseband [F]: `MPSS.JO.3.0-00427-8937_GENNS_PACK-1.136351.1.180278.1` (2018-12-05, MIUI-11 era).

**Boot chain** [F] (from project docs + boot slot readback on 2026-11-09): stock Xiaomi `aboot` (p19) →
`boot` p21 (64 MiB) carries **lk2nd @0 + ztOS payload @0x80000** → installed Arch Linux rootfs.
p21 content = `combined-front-torch-boot.img`, 40 534 016 B, sha256 `c92f2e63…`. Stock Android boot backup
exists (`boot-stock.img`, 67 076 096 B, sha256 `b4e348d5…`). No A/B slots.

**Partitions actually touched so far** [F]: p21 `boot` (non-stock) and p48 `userdata` (formatted ext4 `ztosdata`).
p24 `system`, p25 `cache`, p30 `oem`, p47 `cust` were re-formatted as ztOS filesystems (see below).
`recovery` p22, `persist`, `modem`, `keystore`, `fsg`, `modemst1/2`, `mcfg`, `cmnlib*`, `keymaster*`, `aboot`, `sbl1`,
`tz`, `rpm` are **stock and untouched** [F].

| Part | GPT name | Size | Current content [F] |
|---|---|---|---|
| p1 | modem | 84 M | stock firmware (vfat mount read-only in ztOS) |
| p12 | dsp | 16 M | stock ext4 `dsp` |
| p19/p20 | aboot/abootbak | 1 M | stock bootloader (unlocked) |
| **p21** | **boot** | **64 M** | **lk2nd + ztOS payload (modified)** |
| p22 | recovery | 64 M | stock MIUI recovery (untouched) |
| p24 | system | 3 G | ext4 `ztos-var` → mounted `/var` |
| p25 | cache | 256 M | swap `ztos-swap` |
| p26 | persist | 32 M | stock |
| p27/p28 | misc/keystore | 1 M / 512 K | stock |
| p30 | oem | 256 M | ext4 `ztos-cache` → `/var/cache` |
| p36 | mcfg | 4 M | stock (modem) |
| p37–p44 | cmnlib…msadp | 256 K each | stock |
| p47 | cust | 512 M | ext4 `ztos-home` → `/home` |
| **p48** | **userdata** | **9.7 G** | ext4 `ztosdata`, ztOS `/` = `/ztos-root` |

**OS state** [F]: kernel `7.1.3-msm89x7`, boot_id `06f74b4e…`, uptime 11 h, `systemctl is-system-running` = **running**,
0 failed units, taint `12288` (out-of-tree + unsigned module only — the earlier Venus oops was cleared by a reboot:
`/dev/video0..7` present, `venus_core/dec/enc` loaded). Free space: `/` 2.6 G, `/var` 2.0 G, `/home` 103 M.
Battery **18 %, charging** — too low to flash (see GATE-0). SSH `ztos-ssh.service` on `0.0.0.0:2222`.

**Fastboot entry** [F]: from power-off hold **Volume-Down + Power** → stock aboot fastboot
(`fastboot getvar product` = `ugglite`). This is the same path used on 2026-11-09 to `fastboot flash boot`
manually, so it works with a modified boot slot. Recovery combo (if needed): Volume-Up + Power.
`systemctl --reboot-argument=bootloader reboot` from ztOS is an option but unverified on this chain **[?]**.

**Host tooling (Ubuntu host "hermes", USB-attached)** [F]: `fastboot` v37.0.1 at `~/android-sdk/platform-tools/fastboot`
(not on `$PATH`), system `adb` 1.0.41 (34.0.4). udev `51-android.rules` covers vendor `2717` (Xiaomi) and `18d1`
with `MODE=0660 GROUP=plugdev TAG+=uaccess`; the login user is in `plugdev` → **no sudo needed for fastboot**
(only for the USB-network address below).

---

## 2. Chosen build

LineageOS-for-microG **23.2** (Android 16), `Mi8917` — the shared build for Redmi 4A / 5A / Note 5A Lite / Y1 Lite;
the tree's OTA assert is `TARGET_OTA_ASSERT_DEVICE := ugglite,rolex,riva,Mi8917` [F], so `ugglite` is covered.
Latest build = **20260812**. Downloaded to `~/lineage-flash/` on the Ubuntu host, all four files `sha256sum -c` **OK** [F]:

| File | Bytes | sha256 |
|---|---|---|
| `lineage-23.2-20260812-microG-Mi8917.zip` | 1 205 136 779 | `e7d1ad9600c99b664d068c3e649f1456feceb9b59a3e0d3fc105d224f0dbd31e` |
| `…-boot.img` | 20 967 424 | `792216e65e7224fed85e08b7066ad607aed098caba716f7b387f60bbbb8070aa` |
| `…-recovery.img` | 32 790 528 | `790382a4e378b29bd67889beb6bdbc61235b2c7d016d27523a4682a34cb0ff30` |
| `…-super_empty.img` | 4 708 | `576241eb2eefcf0c27be2755267ff517698eec1f3e5c07391bdb334a9a3052a3` |

Base URL: `https://download.lineage.microg.org/Mi8917/` (the four `*.sha256sum` files are next to them and are the
authority for the table above). Download: 90 s at 13.4 MB/s.

Device-side preconditions from the upstream instructions [F]: model must be MDE6 / MDG6 / MDT6, bootloader unlocked
(it is — a custom payload has been flashed before), stock OS booted at least once, USB debugging enabled in the *stock* OS.
The last two are impossible here (no Android installed) and are not needed for the fastboot+recovery path [E].
`BOARD_AVB_ENABLE := false`, so no AVB/anti-rollback step [F].

---

## 3. Exact ordered command sequence

`$FB` below = `~/android-sdk/platform-tools/fastboot` on the Ubuntu host. `<USB_IF>` = the host's USB-network
interface for the phone (`ip -br link` → the `enx…` device that is `UP` without an IPv4 address).

### GATE-0 — preconditions (nothing written yet)
- Battery **≥ 50 %** and `Charging`; 18 % right now **blocks** the destructive part [F].
- Phone data that matters is exported (see §7); the plan below destroys the whole ztOS install.
- USB cable direct to the host (no hub), host on AC.

### A. Backup / last read-only pass (phone still running ztOS)
```sh
ssh -F build/ugglite-direct-root/ssh_config ugglite-installed 'cat /proc/sys/kernel/random/boot_id; cat /sys/class/power_supply/*/capacity'
# optional, recommended before GATE-E (sizes: /home 375M, /var 985M used):
ssh -F build/ugglite-direct-root/ssh_config ugglite-installed 'tar -C / -c --exclude=/proc --exclude=/sys --exclude=/dev \
  etc root home var/lib/ztos-data usr/local 2>/dev/null | zstd -3' > ~/ztos-backup-$(date +%F).tar.zst
ssh -F build/ugglite-direct-root/ssh_config ugglite-installed 'pacman -Qqe' > ~/ztos-pkgs-$(date +%F).txt
```
**GATE-A:** backup file size plausible and `tar -t` lists `/etc`, `/home`; `df -h ~` on the host still ≥ 1 G free.

### B. Enter fastboot
```sh
ssh … ugglite-installed 'sync; systemctl poweroff'      # phone off, then user holds the buttons
# power-off → hold Volume-Down + Power
$FB devices
$FB getvar product          # MUST print: ugglite      (lk2nd-msm8952 = wrong mode, stop)
$FB getvar all 2>&1 | grep -E 'version-baseband|version-bootloader|battery-voltage|unlocked'
```
**GATE-B:** `product: ugglite`, `unlocked: yes`, `battery-voltage` ≥ ~3700 mV. Anyone in lk2nd fastboot must stop and
re-enter via the key combo — the documented, previously used flasher is the stock aboot one.

### C. Flash the matching recovery (destructive: p22 only)
```sh
$FB flash recovery ~/lineage-flash/lineage-23.2-20260812-microG-Mi8917-recovery.img
```
**GATE-C:** the same file was `sha256sum -c`-verified minutes before; `$FB getvar partition-size:recovery` ≥ 0x2000000
(32 MiB needed, partition is 64 MiB [F]); output `OKAY`/`Finished`.
This is the **first** write; rollback from here is still trivial (p22 is stock-only and not used by ztOS).

### D. Boot the recovery
```sh
$FB boot ~/lineage-flash/lineage-23.2-20260812-microG-Mi8917-recovery.img
adb devices         # expect: <serial>  recovery (or sideload later)
```
**GATE-D:** LineageOS recovery screen appears; if the logo is Xiaomi/MIUI, stop — wrong recovery.
This also proves the *microG* build's recovery accepts its own signed zip (signature keys differ from official
LineageOS, so the `.zip` from this directory must be installed with *this* recovery) [E].

### E. Wipe userdata (destructive) — the point of no return for ztOS data
Recovery UI: **Factory reset → Format data / factory reset** (this removes encryption and formats p48).
**GATE-E:** the host still has the backup from A; the user has explicitly accepted the loss of the ztOS install;
no `fastboot -w` / `-i` / `erase` variants are used (they would take the same data plus p30).

### F. Sideload the OS (destructive: writes super = p24+p47, boot p21, and the logical partitions)
Recovery UI: **Apply update → Apply from ADB**, then on the host:
```sh
adb -d sideload ~/lineage-flash/lineage-23.2-20260812-microG-Mi8917.zip
```
**GATE-F:** zip sha256 matches §2; `adb devices` shows `sideload`; expected runtime 2–5 min for 1.2 GB; `Total xfer: 1.00x`
(or the known `adb: failed to read command: Success` at 47 % — harmless [F]).
Success here means: super metadata created on p24 (3 GiB) + p47 (512 MiB) exactly matching
`BOARD_SUPER_PARTITION_{SYSTEM,CUST}_DEVICE_SIZE` [F], logical `system/product/system_ext/vendor/odm` written,
`boot` p21 replaced with the LineageOS kernel (lk2nd is gone from here on).

### G. First boot (do not interrupt)
**Reboot system now**. Expect 5–15 min of boot animation; then the LineageOS setup wizard.
**GATE-G:** after boot, on the host `adb devices` → `device`, and
`adb shell getprop ro.lineage.version` / `ro.build.version.release` (expect 23.2 / 16). Then: SIM recognised, Wi-Fi,
touch, camera, sensors — the acceptance list for the ztmetro work happens **after** this plan.

**Total expected window:** 30–45 min (USB 2.0): flash 10 s, recovery boot 15 s, format 10 s, sideload 2–5 min,
first boot 5–15 min, checks 5 min.

---

## 4. Is `super_empty.img` required? — **No. Do not flash it.**

- `super` here is a **retrofit** dynamic partition over two existing GPT partitions: `system` p24 (3 GiB) and
  `cust` p47 (512 MiB), metadata device = `system` [F, `BoardConfig.mk` of `device/xiaomi/Mi8937@lineage-23.2`].
- The recovery/sideload path in §3F creates that super metadata itself; `super_empty.img` exists only for the
  *fastboot/fastbootd* install flavour (`fastboot flash super super_empty.img` / `fastboot wipe-super` /
  `fastboot update`) [F, `fastboot-info.txt` contains `update-super`, i.e. it is an `flashall`-time action].
- Flashing it by hand would initialise an **empty** super, i.e. delete all logical partitions, and in the current
  state it would write into the partitions that today hold ztOS `/var` (p24) and `/home` (p47) — destructive with
  zero benefit. Keep the file as a fallback only if a future install has to go through fastbootd.

---

## 5. Firmware requirement

- No firmware/baseband version assertion exists in the `lineage-23.2` device tree: `BoardConfig.mk` only sets
  `TARGET_OTA_ASSERT_DEVICE` and `board-info.txt` says `require board=land|santoni|ugg` (Mi8937 family); no
  `TARGET_BOARD_INFO_FILE`/firmware package pin was found [F, tree read 2026-11-16]. The upstream wiki page for
  `Mi8917` variant 3 states only the model numbers MDE6/MDG6/MDT6 and "boot the stock OS once" [F].
- Practical requirement [E]: the stock Qualcomm/TZ/modem partitions must still be the vendor ones — they are, untouched
  since MIUI (baseband `MPSS.JO.3.0-00427-…`, Dec 2018) [F]. Nothing on `modem`, `persist`, `fsg`, `mcfg`, `tz`,
  `cmnlib` is written by this install.
- How to check the current firmware:
  - from fastboot: `fastboot getvar all 2>&1 | grep -E 'version-baseband|version-bootloader'` (works in the mi8917 aboot);
  - from the running OS: `mmcli -m any --output-keyvalue | grep -i revision` →
    `modem.generic.revision : MPSS.JO.3.0-00427-8937_GENNS_PACK-1.136351.1.180278.1` (measured) [F].

---

## 6. What is destroyed, what is not

**Destroyed** [F, from the partition map + the install flow]:
- p48 `userdata` (9.7 G, ztOS `/` incl. `/ztos-root`, all apps/data) — "Format data".
- p24 `system` and p47 `cust` — become super members (ztOS `/var`, `/home`).
- p25 `cache` (ztOS swap), p30 `oem` (ztOS `/var/cache`) — reformatted/reused by Android.
- p21 `boot` — overwritten by the OS package: **lk2nd + ztOS payload gone**; ztOS is not bootable afterwards.
- p22 `recovery` — replaced by LineageOS recovery.

**Not touched** [F]: p1 `modem`, p26 `persist`, p27 `misc`, p28 `keystore`, p36 `mcfg`, p37–p44
(`cmnlib`, `cmnlib64`, `keymaster`, `apdp`, `msadp`), p13/p14 `modemst1/2`, p16 `fsg`, p2–p11 bootloader
(`sbl1`, `rpm`, `tz`, `devcfg`), p19/p20 `aboot*`, p12 `dsp`, p46 `logdump`, p34 `mdtp`, GPT itself.
IMEI/NV/modem calibration and the unlock state therefore survive.

---

## 7. Rollback

| Target | Possible? | What it takes |
|---|---|---|
| Stock Android (boot only) | yes, has been done before | `$FB flash boot build/install-plan-2026-11-09/backups/manual-20261109T1620Z/boot-stock.img` (67 076 096 B, `b4e348d5…`) — **not** recommended: Android would mount p48 with its own vold [F, runbook (в)] |
| ztOS boot payload (lk2nd+payload) | yes, byte-identical | `$FB flash boot build/install-plan-2026-11-09/work/combined-front-torch-boot.img` (40 534 016 B, `c92f2e63…`) |
| ztOS **rootfs** | **no local artifact** | The installed rootfs was hand-built (`build/ugglite-adaptation/rootfs` is a 472 K overlay skeleton, not a 8.5 G image; the old 2.96 G loop image on p48 was deleted). Returning to ztOS = re-run the full install pipeline (image + `userdata` ext4 `ztosdata` at 20 422 623 sectors + fstab/bind layout), plus restoring `/var` (p24) and `/home` (p47) filesystems. That is a multi-hour rebuild, not a restore. |
| ztOS user data | only from the §3A backup | `/etc`, `/home`, `/var/lib/ztos-data`, `pacman -Qqe` list |

Consequence: the flash is **reversible for the boot chain and for the partitions' geometry, but not for the ztOS
installation itself**. Treat GATE-E as the real decision point.

---

## 8. Disk / time budget

| Item | Value |
|---|---|
| Host free before download [F] | 5 989 982 208 B (5.6 GiB) |
| Host free after download [F] | 4 731 064 320 B (4.5 GiB) — never approached the 1 GiB stop line |
| On-device payload | 1 205.1 MB zip; sideloaded, nothing stored on the phone |
| Extra host cost if a ztOS backup is taken | ≈1–2 GiB (compressed `/etc`+`/home`+`/var/lib/ztos-data`) |

---

## 9. Risks

1. **Battery 18 %** — highest near-term risk. Charge to ≥ 50 % before GATE-B.
2. **Wrong fastboot**: if `$FB getvar product` returns `lk2nd-msm8952` instead of `ugglite`, the flashing semantics are
   lk2nd's, not the stock aboot's — stop and re-enter with the key combo **[?]** (never tried on this chain).
3. **Loss of the only working phone** — ztOS is the daily driver; after GATE-E the phone is an Android device until a
   full ztOS rebuild. Nothing on the host can restore it in minutes (§7).
4. **`${FB}` USB flakiness in bootloader mode** is a documented ugglite trap; use a USB 2.0 port/hub, retry `getvar`.
5. **microG zip signature** differs from official LineageOS keys; recovery may ask "Install anyway?" — expected, accept;
   do not substitute an official LineageOS zip with a microG recovery (or vice versa).
6. **super retrofit sizing**: the plan depends on p24 = 3 GiB and p47 = 512 MiB, which is what the GPT says today [F].
   If a future repartition changes either, the package will refuse to install.
7. Network/USB access from the Ubuntu host to the *installed ztOS* is currently broken (§10) — the plan therefore does
   not rely on it, only on the Mac-side SSH for step A. Fixing it is required before any future ztOS work, not for this flash.

---

## 10. Host connectivity (blocker, preparation-level)

- Phone ↔ Mac: `ssh -F build/ugglite-direct-root/ssh_config ugglite-installed` works **[F]** (state read in §1).
- Phone ↔ Ubuntu host: **does not work yet** [F]. The phone's USB net is `usb0` = `169.254.42.1/16`; the host interface
  `<USB_IF>` is `UP` with **IPv6 link-local only**, NetworkManager profile is `ipv4.method=auto` (DHCP) and fails
  (`ip-config-unavailable`, no lease). ARP for `169.254.42.1` resolves, ICMP/TCP time out — i.e. the host has no
  address in `169.254.0.0/16` to source from. One root action fixes it (needs the user's password / a NOPASSWD rule):
  ```sh
  sudo ip addr add 169.254.42.2/16 dev <USB_IF> && sudo ip link set <USB_IF> up
  # or persistently:  sudo nmcli connection modify "<USB profile>" ipv4.method link-local && sudo nmcli connection up "<USB profile>"
  ```
  After that the same key works from the host:
  ```sh
  ssh -i ~/.ssh/ugglite-maintenance -o UserKnownHostsFile=~/.ssh/ugglite_known_hosts \
      -o HostKeyAlias=ugglite-installed -p 2222 root@169.254.42.1 'uname -r'
  ```
  The key, `known_hosts` and a rewritten config (`~/.ssh/ugglite_ssh_config`) are already in place on the host [F];
  the Mac originals are untouched.
- Not needed for the flash itself: fastboot/adb work over the USB cable without any host IPv4 address [F].

---

## 11. Open questions

1. Does the stock aboot's `fastboot boot recovery.img` accept a 32 MiB recovery on this unit? (It accepted a 40 MB
   lk2nd payload in RAM before [F], so very likely yes **[?]**.)
2. Is `systemctl --reboot-argument=bootloader reboot` from ztOS usable to reach fastboot without the key chord?
   Unverified on an lk2nd-in-boot chain **[?]** — the key chord stays the documented route.
3. Will the microG-recovery hold its own signature policy for the zip without asking for confirmation? **[?]**
4. After LineageOS is up, does the device keep the `ugglite` fastboot identity for future updates, and does an OTA
   `boot` write re-introduce a recovery-less A/B assumption? (No A/B on this device, so the built-in Updater is
   expected to work through recovery [E].)
5. ztmetro/UX acceptance needs a second device state (Android) — how to preserve the ztOS evidence set (screenshots,
   logs) collected on the phone: decide before GATE-E.
