# `repo/overlay/settings/` — ztmetro Settings RRO

Preinstalled runtime resource overlay for the **stock** `com.android.settings`
(LineageOS 23.2 / Android 16, ugglite 720×1280). Resources only: no code, no new
views, no resource that the target does not already define.

| | |
|---|---|
| overlay package | `org.ztos.overlay.settings` |
| target | `com.android.settings` (declares no `<overlayable>` → must be preinstalled) |
| build | `repo/scripts/build-overlay-settings.sh` |
| evidence / report | `repo/evidence/settings-rro/REPORT.md` |
| name verification | `python3 tools/check_names.py --tree <Settings checkout> --dump <aapt2 dump>` |

Layout:

```
AndroidManifest.xml            hasCode=false, targetPackage=com.android.settings, priority 10
res/values/dimens.xml          square geometry (all *_radius -> 0dp) + row metrics
res/values/colors.xml          dashboard tiles -> one accent (#0050EF) + white glyph
res/values-night/colors.xml    night twins (required: the target has night variants)
res/values/styles.xml          Metro type ramp + square search bar (SearchBarStyle_v2)
res/values/attrs.xml           cardCornerRadius/cardElevation keys for SearchBarStyle_v2
res/drawable[-night]/          preference row background, search field fill
tools/check_names.py           fails if a name is not defined *and* consumed in the target
```

Rules that keep the overlay legal (see `docs/10b-overlay-and-hook-facts.md`):

1. never reference a resource that exists only in this package — literals, public
   framework attrs (`?android:attr/…`) and public framework resources only;
2. a style replaces the target's whole bag, so re-declare the items that matter;
3. ship `values-night`/`drawable-night` twins whenever the target has night variants,
   otherwise the target's night entry outranks the overlay in dark mode.

Enable (device, root):

```sh
su 0 cmd overlay enable org.ztos.overlay.settings
su 0 am force-stop com.android.settings   # Settings re-reads its resources on start
```

Rollback:

```sh
su 0 cmd overlay disable org.ztos.overlay.settings
su 0 am force-stop com.android.settings
```
