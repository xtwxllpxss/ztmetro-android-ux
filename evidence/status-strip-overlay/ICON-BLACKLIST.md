# icon_blacklist — exact slots honoured by LineageOS 23.2 SystemUI

Source read: `LineageOS/android_frameworks_base`, branch `lineage-23.2`, commit
`797e43244b96c6a0dff5c81b21c4d4be665b082e`, sparse checkout of `core/res/res/values` and
`packages/SystemUI`. No device or adb was used.

## What reads the key

- `StatusBarIconController.ICON_HIDE_LIST = "icon_blacklist"` — `.../statusbar/phone/ui/StatusBarIconController.java:101`.
- `StatusBarIconControllerImpl` registers the tunable and applies it to every icon slot —
  `.../statusbar/phone/ui/StatusBarIconControllerImpl.java:109, 199-204`.
- `StatusBarSignalPolicy` applies it to airplane/mobile/ethernet —
  `.../statusbar/phone/StatusBarSignalPolicy.java:131-139`.
- `Clock` applies it to the clock in the legacy path (`contains("clock")`) —
  `.../statusbar/policy/Clock.java:366-372`.
- The modern path applies it to the clock and battery —
  `.../statusbar/pipeline/shared/ui/binder/HomeStatusBarViewBinder.kt:184-192 (battery), 217-227 (clock)`.
- Slot names are defined in `core/res/res/values/config.xml:70-103`; the right-side slot
  array is `:29-67`. The resource name is not the slot: e.g. `status_bar_clock` = `clock`,
  `status_bar_battery` = `battery`.

## Slots that matter for this strip

| value | source line | what it hides |
|---|---|---|
| `clock` | `config.xml:96` | SystemUI clock (left on Lineage default) |
| `battery` | `config.xml:93` | stock battery / green battery |
| `wifi` | `config.xml:88` | stock Wi-Fi glyph |
| `mobile` | `config.xml:97` | stock cellular signal |
| `stacked_mobile` | `config.xml:98` | modern stacked mobile slot |
| `airplane` | `config.xml:103` | airplane-mode icon (optional; not duplicated by the strip) |
| `rotate` | `config.xml:70` | rotation-lock icon (was default-hidden) |
| `headset` | `config.xml:71` | headset icon (was default-hidden) |

Other verified values, if a broader clean strip is wanted:
`data_saver, managed_profile, ime, sync_failing, sync_active, cast, connected_display,
hotspot, location, bluetooth, nfc, tty, speakerphone, zen, mute, volume, cdma_eri,
data_connection, phone_evdo_signal, phone_signal, secure, vpn, ethernet, microphone,
camera, no_calling, call_strength, sensors_off, screen_record, satellite`.
Do **not** hide `microphone`, `camera`, `location`, `secure`, `vpn` without a deliberate
privacy decision; they are indicators, not decoration.

## Recommended command (root shell on the phone)

```sh
su 0 settings put secure icon_blacklist "clock,battery,wifi,mobile,stacked_mobile,rotate,headset"
```

Verify:

```sh
su 0 settings get secure icon_blacklist
# clock,battery,wifi,mobile,stacked_mobile,rotate,headset
```

`settings put secure` **replaces** the whole list. If the phone already has entries,
append them to the quoted value. To restore the LineageOS default:

```sh
su 0 settings put secure icon_blacklist "rotate,headset"
```

The SystemUI tunable observes the setting live (`TunerService` + content observer), so no
restart is normally needed. If a slot sticks, restart only SystemUI (`su 0 killall
com.android.systemui`) or reboot; a full reboot is not required.

## Limits

- Notification icons are not SystemUI slots; `icon_blacklist` cannot remove them. The
  opaque 28 dp strip covers the notification row while it is up.
- Airplane/alarm/Bluetooth/VPN are deliberately not in the recommended list: the strip
  does not duplicate them, and they stay useful if the overlay is ever absent. Add them
  to the quoted value if a completely empty stock bar is wanted.
- `clock`/`battery` also hide those items on the keyguard status bar. That is consistent
  with the ztMetro lock overlay owning the lock screen, but it is a deliberate global
  change.
- The app itself cannot write `Settings.Secure` (`WRITE_SECURE_SETTINGS` is not grantable
  to a normal app). The command must come from root/adb.
