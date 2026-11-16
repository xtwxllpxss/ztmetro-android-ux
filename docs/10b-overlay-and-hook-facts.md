# 10b — Overlay + hook facts for a Metro skin of stock Settings & SystemUI

Scope: what can be injected into **stock** `packages/SystemUI` and
`packages/apps/Settings` on LineageOS **`lineage-23.2`** without rebuilding the
platform. Snapshot 2026-11-16.

- `frameworks_base` `lineage-23.2` = `797e43244b96c6a0dff5c81b21c4d4be665b082e`
- `packages_apps_Settings` `lineage-23.2` = `e1b8cd2dde2edd8c513362efc55a770270dce899`
- SystemUI subtree sha `c8a86627d717c4219db76b61444e42a2a27da243` (18 278 blobs)
- AOSP reference tag `android-16.0.0_r3`

Labels: `[F]` verified in fetched source, `[E]` estimate, `[?]` needs an
on-device test. Every claim carries path + line.

---

## 1. Is there an `<overlayable>` in SystemUI or Settings?

**No. Neither app declares any overlayable group at `lineage-23.2`.** `[F]`

Method: full recursive git-tree listing of each tree, filtered for `overlayable`
(`gh api repos/<repo>/git/trees/<sha>?recursive=1`).

| target | tree | `truncated` | paths | `overlayable` matches |
|---|---|---|---|---|
| `packages/SystemUI` | sha `c8a86627…` | `false` | 18 278 | **0** |
| `packages/apps/Settings` | `lineage-23.2` | `false` | 10 723 | **0** |

Searched paths / non-hits `[F]`:

- SystemUI: `res/values/{attrs,bools,colors,config,dimens,drawables,flags,ids,integers,internal,lineage_config,lineage_dimens,mland_config,paths,shade_config,shade_dimens,strings,styles,tiles_states_strings}.xml`,
  `res-keyguard/values/*`, `res-product/values/*` — **no `overlayable.xml`** in either source set.
- Settings: the tree has **no** `res/values/overlayable.xml` and no `src/com/android/settings/*/overlayable*`;
  the only `overlay`-named matches are unrelated
  (`src/com/android/settings/overlay/FeatureFactory.kt`, `…/development/OverlaySettingsPreferenceController.java`,
  `res/drawable-nodpi/fingerprint_sensor_location_front_overlay.png`).
- The only `overlayable.xml` files in the whole `frameworks_base` tree are test data
  (`libs/androidfw/tests/data/overlayable/res/values/overlayable.xml`,
  `core/tests/overlaytests/**`, `cmds/idmap2/tests/data/target/res/values/overlayable.xml`).

Consequence: **there is no group name to pass as `android:targetName`**, and no
`public`/`system` policy to satisfy. The overlay must instead satisfy the
"no overlayable declared" branch (section 4).

Also note `lineage-23.2` SystemUI is the **2025 shade** design: a separate
`res/values/shade_dimens.xml`, a `Theme.SystemUI.QuickSettings` theme carrying the
`shadeActive`/`shadeInactive` palette, and `res-keyguard/` as a second resource
source set. Old CM/Lineage 14–18 QS guides do not map 1:1.

---

## 2. SystemUI resource inventory (Metro-able parts)

Full machine-readable table: **`repo/design/systemui-resource-map.json`** (23 entries,
`{resource,type,file,styles_what,metro_target}`). Highest-value facts, all `[F]`:

| what | resource | file:line |
|---|---|---|
| Tile shape (single best hook) | `qs_tile_background_shape` — `<corners radius="@dimen/qs_corner_radius"/>` | `res/drawable/qs_tile_background_shape.xml` |
| Tile corner radius | `qs_corner_radius` = 28dp | `res/values/dimens.xml:726` |
| Tile chrome/ripple | `qs_tile_background` (ripple → layer-list → shape) | `res/drawable/qs_tile_background.xml` |
| Tile active/inactive fill | attrs `shadeActive` / `shadeInactive`, set in `Theme.SystemUI.QuickSettings` | `res/values/attrs.xml:125-131`, `res/values/styles.xml:553-554` |
| Tile label | `TextAppearance.QS.TileLabel`, 14sp, colour `?attr/onShadeInactive` | `res/values/styles.xml:164-169`, `res/layout/qs_tile_label.xml:30,56` |
| Tile metrics | `qs_tile_height` 80dp, padding 12/16dp — read in `QSTileViewImpl.kt:238-242,279-292` | `res/values/dimens.xml:727,758,759` |
| Notification card corner | `notification_corner_radius` = 28dp | `res/values/dimens.xml:348` |
| Notification card fill | `notification_legacy_background_color` | `res/values/colors.xml:84` |
| **Shade/panel background** | **no drawable** — painted by `ScrimView`s from `notification_scrim_base`, `shade_panel_fallback`, `notification_scrim_fallback`; `notification_panel` itself is `@android:color/transparent` | `res/values/colors.xml:40,43,44`; `res/layout/status_bar_expanded.xml:26`; `res/layout/super_notification_shade.xml` |
| Shade panel radius (2025 design) | `overlay_shade_panel_shape_radius` 46dp, `shade_panel_width` 600dp | `res/values/shade_dimens.xml:24-36` |
| Shade clock/date type | `TextAppearance.StatusBar.Expanded.Clock` / `.Date` | `res/values/styles.xml:37-50` |
| Shade header type | `TextAppearance.QS.Status`, `.Clock`, `.Build` (feature-flagged fonts) | `res/values/styles.xml:180-196` |
| Status bar height | `status_bar_height` = 24dp in **framework-res**; SystemUI only adds `status_bar_padding_start/top` | `core/res/res/values/dimens.xml:44-60`; `res/values/dimens.xml:463,469` |
| Keyguard layout names | `res/layout/keyguard_status_bar.xml`, `keyguard_bottom_area.xml`, `multi_shade.xml`, `super_notification_shade.xml`; `res-keyguard/layout/` (26 files incl. `keyguard_pin_view.xml`, `alternate_bouncer.xml`, `bubble_clock.xml`) | tree listing |

Caveat: **there is no "shade background" resource to overlay.** Anyone writing an
overlay that targets `@drawable/shade_background` will get a build-time
`sdk_version`/missing-resource failure — it does not exist (`404` on the raw path).
The panel look is colours + scrim + corner dimen.

---

## 3. Can an RRO replace a *layout* resource? Yes — with two hard constraints

**Yes.** `layout` is an ordinary resource type; the overlay simply provides a file
with the same name, and the idmap maps the target's `0x7f…` id to the overlay's
entry. Nothing in idmap2 restricts by type — the only filter is the overlayable /
policy check (`cmds/idmap2/libidmap2/ResourceMapping.cpp:51-100`) plus the
"resource must exist in both packages" rule `[F]`:

```
// frameworks/base/libs/androidfw/ResourceTypes.cpp:7489 (legacy idmap path)
// find the resources that exist in both packages
```

Two constraints that decide the Metro strategy:

**(a) The overlaid layout may only reference resources that already exist in the
target (or in another overlay applied to it).** `[F]` mechanism, `[E]` failure mode.
Inflation of an overlaid layout goes through the *target's* `AssetManager`; the
ids baked into the overlay's compiled XML are rewritten by idmap **only for
resources present in both packages**. Any `@drawable/@dimen/@style/@id` that exists
only in the overlay has no target-side id, so it resolves in the target package
namespace and throws `android.content.res.Resources$NotFoundException` at inflate
time (or, for `@+id`, silently creates an id that target code's `findViewById`
never looks for). Practical rules:

- Referencing target resources (`@*android:dimen/…`, `@dimen/qs_corner_radius`,
  `?attr/shadeActive`) — fine, that is exactly what overlays are for.
- Bringing a *new* drawable (Metro font, gradient, 9-patch) — must be shipped in a
  **second overlay applied to the same target**, or in the same overlay **only if
  the name also exists in the target**. Safer: express Metro with
  `<shape>` + literal colours inline in the layout/drawable XML (inline values
  need no cross-package reference), or restyle a target drawable rather than add one.
- Class names inside the layout are resolved by the **target app's** ClassLoader,
  so `com.android.systemui.*` and `android.widget.*` views work unchanged;
  a Metro custom view class does **not** (no code in an RRO).

**(b) Adding a NEW view to an existing layout is therefore the hard case.** You may
not append `<com.metro.HeaderView/>` + `@drawable/metro_*` + `@style/MetroHeader`
to a copy of `settings_main_prefs.xml` and have it inflate. `[F]` for the reference
rule, `[E]` for the exact exception thrown. Options, best first:

1. **Restyle in place** — reuse the existing views and ids; change only dimens,
   colours, styles, and the drawing of existing drawables. Zero new references.
2. **Layout splice with target-only resources** — overlay the layout using ids and
   drawables that already exist in the target, i.e. re-arrange existing views, add
   plain `<View>`/`<TextView>` with inline `android:background="#0078D7"`, no new
   named resources. Works, but every literal is duplicated and cannot be themed.
3. **Two overlays on the same target** — overlay A ships the new resources only,
   overlay B ships the layout that references them. Only viable if idmap lets B see
   A's ids; **untested on `lineage-23.2`** `[?]` — the safe assumption is no.
4. **New view via code hook** (section 5) — a runtime injection (LSPosed/Xposed-style
   or a patched APK) inflates the header from the *mod's own* APK, where all its
   resources resolve normally. This is the only route that gets a genuinely new
   component with its own drawables/fonts.

**Verdict for the Metro header:** a new header view is a *code/APK* deliverable,
not an RRO deliverable. Use RRO for tile shape/colour/typography; use a hook (or a
patched Settings APK) for the header.

---

## 4. OMS policy path for a target with **no** overlayable

The decisive code is not in `OverlayManagerServiceImpl` — it is in idmap2's
resource filter, `frameworks/base/cmds/idmap2/libidmap2/ResourceMapping.cpp:51-86`
(`android-16.0.0_r3`). Exact condition `[F]`:

```cpp
Result<Unit> CheckOverlayable(const TargetResourceContainer& target,
                              const OverlayManifestInfo& overlay_info,
                              const PolicyBitmask& fulfilled_policies,
                              const ResourceId& target_resource) {
  constexpr const PolicyBitmask kDefaultPolicies =
      PolicyFlags::ODM_PARTITION | PolicyFlags::OEM_PARTITION | PolicyFlags::SYSTEM_PARTITION |
      PolicyFlags::VENDOR_PARTITION | PolicyFlags::PRODUCT_PARTITION | PolicyFlags::SIGNATURE |
      PolicyFlags::CONFIG_SIGNATURE;

  // If the resource does not have an overlayable definition, allow the resource to be overlaid if
  // the overlay is preinstalled, signed with the same signature as the target or signed with the
  // same signature as reference package defined in SystemConfig under 'overlay-config-signature'
  // tag.
  const Result<bool> defines_overlayable = target.DefinesOverlayable();
  if (!defines_overlayable) { return Error(..., "unable to retrieve overlayable info"); }

  if (!*defines_overlayable) {
    return (kDefaultPolicies & fulfilled_policies) != 0
               ? Result<Unit>({})
               : Error("overlay must be preinstalled, signed with the same signature as the target,"
                       " or signed with the same signature as the package referenced through"
                       " <overlay-config-signature>.");
  }
  …
  if (*overlayable_info == nullptr) {
    // Do not allow non-overlayable resources to be overlaid.
    return Error("target resource has no overlayable declaration");
  }
```

Read it as three branches:

1. Target defines **no** overlayable (our case: SystemUI, Settings) → **every**
   resource is overlaid, subject only to the overlay being preinstalled
   (`SYSTEM/VENDOR/PRODUCT/ODM/OEM_PARTITION`), platform-signed, or signed like the
   `overlay-config-signature` package. `[F]`
2. Target defines overlayables and the resource is in none of them → hard fail
   "target resource has no overlayable declaration". `[F]`
3. Resource is in a group → the group's `policy_flags` must intersect
   `fulfilled_policies`, and `android:targetName` must match the group name. `[F]`

`fulfilled_policies` is computed in
`services/core/java/com/android/server/om/IdmapManager.java:203-256`
(`calculateFulfilledPolicies`): starts at `PUBLIC`, then adds `SIGNATURE` (same
signer as target), `ACTOR_SIGNATURE`, `CONFIG_SIGNATURE`, and the partition flag
matching the overlay's install location (`:233-254`). The companion switch
`enforceOverlayable()` at `IdmapManager.java:183-201` says overlayable policy
enforcement is **always** on for `targetSdkVersion >= Q`; only pre-Q overlays that
are `isSystem()` or platform-signed are exempt. So on `lineage-23.2` the
partition flag is what saves us, and installing the overlay as a normal user app
will fail idmap creation.

**What "preinstalled" concretely means** `[F]` for the flag source, `[E]` for the
operational recipe:

- The overlay APK must live on a read-only system partition and be scanned as
  `PackageState.isSystem()`/`isVendor()` etc. — i.e. `/system/overlay/`,
  `/product/overlay/`, `/vendor/overlay/` (or a SystemConfig-declared path).
- `fulfilled_policies` then includes `SYSTEM_PARTITION` (or `PRODUCT_PARTITION`),
  which is inside `kDefaultPolicies` → every resource in SystemUI/Settings becomes
  overlayable. No `overlayable.xml`, no `targetName`, no signature needed.

**`cmd overlay enable` on a user build.** `[F]` for the permission, `[?]` for the
shell's exact uid path on this ROM. `OverlayManagerService.java:150-161` (class
javadoc):

> "Read-access is granted if the request targets the same Android user as the
> caller runs as, or if the caller holds the INTERACT_ACROSS_USERS_FULL permission.
> Write-access is granted if the caller is granted read-access and additionally
> holds the CHANGE_OVERLAY_PACKAGES permission."

`CHANGE_OVERLAY_PACKAGES` is a `signature|privileged` permission — the `shell` uid
does **not** hold it on a stock user build, so `adb shell cmd overlay enable …`
fails with `SecurityException` unless the shell is specially allowlisted (`[?]`
whether Lineage's `privapp-permissions` allowlists shell). With **root**
(`su 0 cmd overlay enable …`, or the overlay enabled by SystemUI/ThemePicker which
do hold the permission) the call succeeds; Magisk does not grant a permission, it
just gives you a root shell that bypasses the check. Second, lighter path:
`android:isStatic="true"` + `OverlayConfig` (`/product/overlay/config/config.xml`)
makes the overlay enabled at boot with no command at all — but a static overlay is
immutable and cannot be toggled at runtime. `[F]` for static semantics (AOSP RRO
docs), `[?]` for Lineage's `config.xml` contents on this device.

**Blocking fact:** idmap creation happens when the overlay is installed and the
target's resources are re-read; a *running* SystemUI must be restarted (or the
whole `system_server`/user session) before the new values are visible. `[E]`

---

## 5. Settings common container — see `repo/design/settings-container-map.md`

Summary: `SettingsActivity extends SettingsBaseActivity` (`SettingsActivity.java:84`),
`SettingsBaseActivity.onCreate` installs the toolbar shell
(`SettingsBaseActivity.java:140-152`, layout `res/layout/settings_base_layout.xml`
with ids `content_parent`/`action_bar`/`content_frame`), and
`SettingsBaseActivity.setContentView(int)` (line 220) inflates the screen layout
**into `R.id.content_frame`**. Sub-screens are `SubSettings extends SettingsActivity`
(`SubSettings.java:22`) and therefore ride the same path; the settings home is
`SettingsActivity` + `TopLevelSettings` fragment (`R.layout.settings_main_prefs`,
`SettingsActivity.java:313`), while the launcher-facing home is a *different*
activity, `SettingsHomepageActivity extends FragmentActivity`
(`SettingsHomepageActivity.java:89`) with `R.layout.settings_homepage_container`
(line 252).

---

## 6. Supported extension points in Settings (reuse instead of injecting)

`[F]` unless marked.

- **`FeatureFactory`** (`src/com/android/settings/overlay/FeatureFactory.kt`,
  `FeatureFactoryImpl.kt`) — the only real plugin seam. It is a *compile-time*
  singleton (`FeatureFactory.getFeatureFactory()`), selected by
  `config_featureFactory`; you cannot add a provider from an RRO. A hook that
  replaces the factory can inject dashboard/search behaviour, not a header view.
- **`TopLevelSettings` dashboard tiles** (`DashboardFragment` +
  `TopLevelSettingsPreferenceController` / `DashboardFeatureProvider`) — adding a
  *tile* is supported; it appears in the scrollable `main_content`, not as a header.
- **`FooterPreference`** (`src/com/android/settings/widget/FooterPreference.kt`) —
  supported per-screen footer text; bottom placement, no title/subtitle role.
- **Collapsing toolbar** — SettingsLib
  `com.android.settingslib.collapsingtoolbar.*` (`collapsing_toolbar_base_layout`,
  ids `collapsing_toolbar`, `app_bar`, `primary_button`, `secondary_button`,
  `action_button`, `floating_toolbar`), driven by `SettingsBaseActivity`
  `getToolbarDelegate()` / `ToolbarDelegate` — the *supported* place to change
  toolbar title/subtitle/buttons globally, via `setTitle`/`setSubtitle`.
- **Search bar** — homepage only: `R.id.search_action_bar`, `R.id.search_bar_title`
  (`SettingsHomepageActivity.java:370,410`).
- **No hook for a custom header.** There is no `<header>` provider, no
  `SettingsBaseActivity` extension point, and `SettingsHomepageActivity` does not
  even extend `SettingsBaseActivity` — so a header must be *injected* (code hook or
  patched APK), not configured. Use `setSubtitle` on the existing toolbar as the
  zero-injection fallback for the subtitle half of the Metro header.

---

## 7. Bottom line

1. Overlayable: **none** in SystemUI, **none** in Settings → every resource is
   overlayable *iff* the overlay is preinstalled (system/product/vendor partition),
   per `ResourceMapping.cpp:69-75`.
2. Best three Metro RRO hooks (all `[F]`, all pure resource replacement, no new
   references): `qs_tile_background_shape` (+ `qs_corner_radius`),
   the `shadeActive`/`shadeInactive`/`onShade*` attrs, and
   `TextAppearance.QS.TileLabel` / `notification_corner_radius`.
3. Settings header: **not** an RRO deliverable — needs
   `SettingsBaseActivity.setContentView(int)` (line 220) / `onCreate` hook plus
   `SettingsHomepageActivity.onCreate:252` for the launcher home.
4. Blocking facts: (a) no `CHANGE_OVERLAY_PACKAGES` for `shell` on a user build →
   enable the overlay as preinstalled + SystemConfig/root, not via `adb shell`;
   (b) a new view with its own resources cannot be added by an overlay at all.
