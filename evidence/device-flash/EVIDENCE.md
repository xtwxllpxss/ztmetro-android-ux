# Device-flash evidence — readbacks taken 2026-11-16 ~10:00–10:25 MSK

Read-only. Nothing was flashed, rebooted or written on the phone. One file-operation only on the Ubuntu host:
a new directory `~/lineage-flash/` with the four build artifacts + their `.sha256sum` files, and three SSH files in
`~/.ssh/` on that host. No absolute host paths, no usernames, no MAC addresses recorded here.

## 1. Phone state over SSH (Mac side)

```sh
ssh -F build/ugglite-direct-root/ssh_config ugglite-installed '…'
```
```
boot_id          06f74b4e-77e5-4c4b-bf25-687e1bf7fa10
uptime           11:04, 2 users, load 0.83 0.77 0.70
kernel           7.1.3-msm89x7
systemd          running        failed units: 0
model            Xiaomi Redmi Note 5A (ugglite)
compatible       xiaomi,ugglite qcom,msm8917
df /             9.5G  6.9G  2.6G  73%  (/dev/mmcblk0p48)
df /home         488M  375M  103M  79%  (/dev/mmcblk0p47)
df /var          2.9G  985M  2.0G  34%  (/dev/mmcblk0p24)
memory           total 1831 MB, used 670 MB, available 1161 MB, swap 1155 MB
battery          18 %  Charging
usb0             169.254.42.1/16, fe80::…/64
sshd             `ztos-ssh.service` active, listens 0.0.0.0:2222
modem revision   MPSS.JO.3.0-00427-8937_GENNS_PACK-1.136351.1.180278.1  1  [Dec 05 2018 04:00:00]
taint            12288
```

Venus / media after the reboot that followed the oops:
```
/dev/video0 … /dev/video7 present
venus_core 143360 | venus_dec 32768 | venus_enc 32768   (loaded)
journalctl -b -p err → no venus oops; residual: drm dpu "no encoder found for crtc 0",
                        one polkit "a password is required" from ztos-waydroid-recover
```
Interpretation: `systemctl` reports **running, 0 failed**; the Venus modules and `/dev/video6,7` are back, so the
earlier oops did not persist across the reboot. `ztmetro` (compositor) was `inactive` at sample time, `greetd` active —
i.e. no graphical session running, not a failure.

## 2. Partition map (`lsblk` on the phone)

`lsblk -o NAME,SIZE,FSTYPE,LABEL,PARTLABEL` — 48 partitions on `mmcblk0` (14.7 G): p1 `modem` 84 M vfat,
p2 `fsc`, p3 `ssd`, p4/5 `sbl1(+bak)`, p6/7 `rpm(+bak)`, p8/9 `tz(+bak)`, p10/11 `devcfg(+bak)`,
p12 `dsp` 16 M ext4, p13/14 `modemst1/2`, p15 `DDR`, p16 `fsg`, p17 `sec`, p18 `splash` 11 M, p19/20 `aboot(+bak)`,
p21 `boot` 64 M, p22 `recovery` 64 M, p23 `devinfo`, **p24 `system` 3 G ext4 `ztos-var`**, **p25 `cache` 256 M swap
`ztos-swap`**, p26 `persist` 32 M, p27 `misc`, p28 `keystore`, p29 `config`, **p30 `oem` 256 M ext4 `ztos-cache`**,
p31 `limits`, p32 `mota`, p33 `dip`, p34 `mdtp` 32 M, p35 `syscfg`, p36 `mcfg` 4 M, p37–p40 `cmnlib/cmnlib64(+bak)`,
p41/42 `keymaster(+bak)`, p43 `apdp`, p44 `msadp`, p45 `dpo`, p46 `logdump` 64 M, **p47 `cust` 512 M ext4
`ztos-home`**, **p48 `userdata` 9.7 G ext4 `ztosdata`**. Plus `mmcblk0boot0/1` 4 M, `zram0` 900 M swap.

Boot slot: p21 holds lk2nd@0 + ztOS payload@0x80000 (`build/install-plan-2026-11-09/work/combined-front-torch-boot.img`,
40 534 016 B, `c92f2e63…`); stock Android boot backup 67 076 096 B `b4e348d5…` in the same tree.

## 3. Build artifacts — download and verification on the Ubuntu host

```sh
mkdir -p ~/lineage-flash && cd ~/lineage-flash
curl -O https://download.lineage.microg.org/Mi8917/lineage-23.2-20260812-microG-Mi8917{,-boot.img,-recovery.img,-super_empty.img}{,.sha256sum}
sha256sum -c *.sha256sum
```
```
df before   5 989 982 208 B free (5.6 G, 94 %) on /
zip         1 205 136 779 B  in 90.2 s  (13.4 MB/s)
boot.img       20 967 424 B  in 1.4 s
recovery.img   32 790 528 B  in 1.8 s
super_empty     4 708 B
df after    4 731 064 320 B free (4.5 G, 95 %) on /   → stop line (1 G) never approached
sha256sum -c: all four "OK"
```
Hashes are listed in `PLAN.md` §2. Upstream directory listing (fetched separately) reports the same byte sizes and a
`20260812` mtime for all five files; the 20260516 and 20260615 builds are older and were ignored.

## 4. Host tooling and USB access

```
~/android-sdk/platform-tools/fastboot   fastboot version 37.0.1-15733141   (not on $PATH)
adb                                     1.0.41 / 34.0.4-debian
/lib/udev/rules.d/51-android.rules       2717 (Xiaomi) and 18d1 → ENV{adb_user}=yes,
                                         and ENV{adb_user}=yes → MODE=0660 GROUP=plugdev TAG+=uaccess
groups                                   includes plugdev  → no sudo needed for fastboot
lsusb                                    1d6b:0104 "Multifunction Composite Gadget" = phone in Linux gadget mode
                                         (no 2717/18d1 device present → phone is not in fastboot right now)
```

## 5. Phone ↔ Ubuntu host network (current, broken for SSH)

```
ip -br addr   <USB_IF>  UP  fe80::…/64            (no IPv4)
ip route      default via … dev wlo1              (no route into 169.254.0.0/16)
ping  -I <USB_IF> 169.254.42.1 → 100 % loss
ip neigh      169.254.42.1 lladdr … REACHABLE     (L2 is fine)
ssh -F ~/.ssh/ugglite_ssh_config ugglite-installed → "Connection timed out"
sudo -n true  → "sudo: a password is required"
nmcli device   <USB_IF> ethernet connecting (getting IP configuration)  ipv4.method=auto
journal       NetworkManager: "ip-config -> failed (reason 'ip-config-unavailable')", dhcp4 "no lease"
```
Phone side confirms the peer: `ip neigh show dev usb0` → the host's USB-NIC MAC; `ip route` on the phone has
`169.254.0.0/16 dev usb0 src 169.254.42.1` and a Wi-Fi default route. Conclusion: the link is up and the phone is
configured, only the host address is missing; fixing it needs root (§10 of `PLAN.md`).

Files prepared on that host (originals on the Mac untouched): `~/.ssh/ugglite-maintenance` (600),
`~/.ssh/ugglite_known_hosts` (600, copied from the project `known_hosts`), `~/.ssh/ugglite_ssh_config` (600, only the
key/known_hosts paths rewritten; `HostName 169.254.42.1`, `Port 2222`, `User root`, `HostKeyAlias ugglite-installed`).

## 6. Upstream sources consulted

- `https://download.lineage.microg.org/Mi8917/` — build list, sizes, `.sha256sum` files.
- `https://wiki.lineageos.org/devices/Mi8917/` and `…/variant3/` — shared build for 4 devices, models MDE6/MDG6/MDT6,
  bootloader = Volume-Down + Power, recovery = Volume-Up + Power, kernel 4.19, supported versions up to 23.2.
- `https://wiki.lineageos.org/devices/Mi8917/install/variant3` — official order: flash recovery → boot recovery →
  factory reset → `adb -d sideload` the zip → add-ons → reboot; no `super_empty.img` step, no firmware step.
- `LineageOS/android_device_xiaomi_Mi8917@lineage-23.2/lineage.dependencies` — all files live in
  `device/xiaomi/Mi8937`; `README` = "Nothing is really here".
- `LineageOS/android_device_xiaomi_Mi8937@lineage-23.2` — `BoardConfig.mk` (retrofit super = p24 `system` 3 GiB +
  p47 `cust` 512 MiB, `BOARD_AVB_ENABLE := false`, `TARGET_OTA_ASSERT_DEVICE := ugglite,rolex,riva,Mi8917`),
  `fastboot-info.txt` (`update-super`, flash odm/vendor/system/system_ext/product/boot, `if-wipe erase
  cache/userdata/oem`), `board-info.txt`, `device.mk` (`PRODUCT_USE_DYNAMIC_PARTITIONS := true`).
- `LineageOS/android_device_xiaomi_mithorium-common@lineage-23.2/BoardConfigCommon.mk` — no firmware pin
  (`/vendor/firmware_mnt:/firmware` mount only).
- `https://github.com/lineageos4microg/l4m-wiki/wiki/Installation` — "follow the LineageOS instructions for your
  device; the `.img` files sit next to the ROM zip; verify the signature and the sha256".
