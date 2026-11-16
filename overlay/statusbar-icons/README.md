# overlay/statusbar-icons — WP glyphs for the SystemUI status bar

Preinstalled RRO (`targetPackage="com.android.systemui"`) that replaces the
status-bar drawables SystemUI 23.2 actually reads with flat, white, 24-unit
viewport vectors in the ztmetro/Windows Phone language.

- shipped: 20 drawables — `stat_sys_alarm`, `stat_sys_alarm_dim`,
  `stat_sys_data_bluetooth_connected` + its 10 device-battery variants,
  `stat_sys_headset`, `stat_sys_headset_mic`, `stat_sys_airplane_mode`,
  `stat_sys_vpn_ic`, `stat_sys_no_internet_vpn_ic`, `stat_sys_branded_vpn`,
  `stat_sys_no_internet_branded_vpn`.
- not shipped: battery, wifi and mobile signal. On lineage-23.2 those are not
  SystemUI drawables (battery and signal are Canvas code in
  `AccessorizedBatteryDrawable` / `SignalDrawable`; wifi comes from framework
  `ic_wifi_signal_*` or SettingsLib `ic_wifi_*`). See
  `../../evidence/statusbar-icons/REPORT.md` for the fork/fallback.
- `fallback/drawable/` keeps the battery / wifi / signal geometry as reference
  only; it is deliberately outside `res/` and is not compiled into the APK.

Generate / build:

```sh
python3 repo/overlay/statusbar-icons/tools/gen_glyphs.py   # res/ + preview
repo/scripts/build-overlay-statusbar-icons.sh              # hermes-ubuntu build
```

The generator is the single source of truth: it emits the XML and the SVG
preview from the same path data, so `evidence/statusbar-icons/preview.png` is
the vector that ships.

Design rules: `#FFFFFFFF` only, no gradients, no shadows, no fill except the
airplane / nub / gauge, square caps, sizes on the stock 17 dp intrinsic
(insets of the stock icons folded into the path box). No cross-resource
references inside the overridden drawables (docs/10b §3a).
