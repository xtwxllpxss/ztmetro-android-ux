# 10a — Systemless injection into an installed, officially signed LineageOS 23.2 (Mi8937 / ugglite)

Scope: what can be injected into an **already installed** LineageOS-for-microG 23.2
(Android 16) phone with **Magisk only**, no ROM build, no platform key. Companion to
[`03-theming-rro.md`](03-theming-rro.md), [`04-replacement-layers.md`](04-replacement-layers.md),
[`06-systemui-standalone.md`](06-systemui-standalone.md), [`07-ztmetro-mapping.md`](07-ztmetro-mapping.md);
the audit of those documents is §8.

Method: AOSP `android16-release` / LineageOS `lineage-23.2` source, official docs and
release pages (fetched 2026-11-16), plus local probes of a real Magisk v31.1 release APK
and a real Zygisk module (Vector v2.2). Raw command output is in
[`../evidence/injection-research/`](../evidence/injection-research/).
Labels: `[F]` verified (URL or `file:line`), `[E]` estimate/engineering judgement,
`[?]` unverified, needs the device.

---

## 0. Device and build reality check (corrects the project premise slightly)

| Claim | Verdict | Evidence |
|---|---|---|
| LineageOS codename for the Redmi Note 5A is `ugglite` | **Partly wrong**: the official LineageOS device is the unified **`Mi8937`**; `ugglite` is one asserted OTA variant (`TARGET_OTA_ASSERT_DEVICE := ugglite,rolex,riva,Mi8917` for `PRODUCT_HARDWARE=Mi8917`) | [F] [`BoardConfig.mk:27`](https://raw.githubusercontent.com/LineageOS/android_device_xiaomi_Mi8937/lineage-23.2/BoardConfig.mk); there is no `android_device_xiaomi_ugglite` in LineageOS org |
| LineageOS-for-microG 23.2 builds exist for this phone | **Confirmed**, via `Mi8937`: `lineage-23.2-20260516/20260616/20260812-microG-Mi8937.zip`, plus matching `boot.img`, `recovery.img`, `super_empty.img` | [F] <https://download.lineage.microg.org/Mi8937/> (listing in `05-verified-boot-ota.log`) |
| The build is AVB-verified | **No**: `BOARD_AVB_ENABLE := false` for Mi8937 | [F] [`BoardConfig.mk:23`](https://raw.githubusercontent.com/LineageOS/android_device_xiaomi_Mi8937/lineage-23.2/BoardConfig.mk) |
| Partition layout | Dynamic "super" partitions: `SSI_PARTITIONS := product system system_ext` (logical partitions), so `/system`, `/system_ext`, `/product` are all in `super` | [F] `BoardConfig.mk:8`; `super_empty.img` published |
| Build type `user`, signed with microG project keys | **[?]** Not verifiable from the download listing; the project runs its own signing setup and published a Dec-2025 notice that some project private keys had been exposed in a public repo; it publishes its own images | [F] <https://lineage.microg.org/>; build type still `[?]` — check `ro.build.type` on device |

Consequences: the phone is an **A-only, AVB-disabled, dynamic-partition, unlocked-bootloader**
device running images signed with keys we do not have. That makes boot.img patching easy
and APK re-signing impossible.

---

## 1. Magisk mechanics

### 1.1 Module layout

`[F]` Official developer guide (<https://topjohnwu.github.io/Magisk/guides.html>): a module
is a directory under `/data/adb/modules/$MODID` containing:

```
module.prop          id, name, version, versionCode, author, description, updateJson
system/              files merged into the real /system (and /vendor, /product, /system_ext
                     via system/vendor, system/product, system/system_ext)
zygisk/              native libs injected into app processes (arm64-v8a.so, ...)
post-fs-data.sh      runs in post-fs-data (blocking, before system_server) 
service.sh           runs in late_start service (after boot)
uninstall.sh, action.sh, system.prop, sepolicy.rule
skip_mount, disable, remove, .replace
```

`[F]` The `system/` tree is *recursively merged*: existing files are replaced, new files
are added; a `.replace` file in a folder makes that folder replace the real one wholesale;
a dummy char device (`mknod … c 0 0`) deletes a file (<https://topjohnwu.github.io/Magisk/guides.html#the-system-folder>).
`customize.sh` runs **at install time**, not boot; it can `abort`, set permissions/contexts,
and use `$MAGISK_VER`, `$API`, `$ARCH`, `$MODPATH` (`guides.html#customization`).

### 1.2 Magic mount, overlayfs, overlay.d — three different things

- **Magic mount** = Magisk builds a mount tree and bind-mounts module files over the
  read-only partitions; it does not modify the underlying blocks, so dm-verity/OTA block
  checks are untouched `[F]` (<https://topjohnwu.github.io/Magisk/details.html>).
- **"overlayfs"** in Magisk means the *internal mount backend*: "New magic mount backend.
  It supports loading modules into system with overlayfs files injected" — introduced in
  **v26.0** (2023-04-05), bug-fixed in v30.2 `[F] <https://topjohnwu.github.io/Magisk/changes.html>;
  `01-magisk-mechanics.log`). It is **not** a module-facing way to overlay `/system` with a
  custom overlayfs upper dir, and it is unrelated to Android RRO overlays.
- **`overlay.d`** is a *boot-ramdisk rootdir* overlay for custom kernels: files in
  `overlay.d` live in the boot image ramdisk, `*.rc` files are concatenated after/replace
  `init.rc`, and `overlay.d/sbin` goes to Magisk's tmpfs `[F] `guides.html#root-directory-overlay-system`).
  A `/data/adb/modules` module **cannot** use `overlay.d`; it is a ramdisk developer feature.

Mount timing is the critical property for everything below: modules are magic-mounted on
`post-fs-data` `[F]` (`details.html#post-fs-data`), and `post-fs-data` runs **before**
`zygote-start` in AOSP init `[F]` (`system/core/rootdir/init.rc:520` then `:527`,
android16-release). So system_server — and therefore PackageManager's boot scan — sees the
mounted files.

### 1.3 Which version introduced what (official changelog)

| Version | Feature relevant here | Evidence |
|---|---|---|
| v19.4 (2019-09-19) | `overlay.d` rootdir overlay; magic mount can replace `/product` files | [F] changes.html |
| v26.0 (2023-04-05) | new magic-mount backend ("overlayfs files injected"); `*.rc` replacement in `overlay.d`; Zygisk API 4 | [F] changes.html |
| v28.0 (2024-10-10) | magic-mount mirror removed; Zygisk JNI hook rewrite | [F] changes.html |
| v28.1 (2024-12-06) | fix for `overlay.d`-replaced files accessibility | [F] changes.html |
| v29.0 (2025-05-14) | systemless file **deletion** via blank file nodes; module zip XZ support | [F] changes.html |
| v30.2 (2025-08-06) | fix breaking modules "when overlayfs is involved" | [F] changes.html |
| v30.3 (2025-09-29) | sepolicy parser + **Zygisk support for Android 16 QPR2 and higher** | [F] changes.html |
| v31.1 (2026-10-25) | HSUM support; min Android 7.0; removes interactive `su` prompt and app hiding/repackaging | [F] <https://github.com/topjohnwu/Magisk/releases/tag/v31.1> |

### 1.4 Current Magisk and Android 16 status

**Magisk v31.1 (2026-10-25) is current** `[F]` (GitHub releases API). Android 16 is
supported: v30.3 added Android 16 QPR2 sepolicy and Zygisk support `[F]`; the v31.1 APK
contains `addon.d.sh`, `module_installer.sh`, `libmagiskboot.so`, etc. `[F]` local
`unzip -l` and `APK Sig Block 42` probe (`01-magisk-mechanics.log`). Note v31.1's
behaviour changes: root is granted ahead of time in the Superuser tab (no prompt), and
there is **no app hiding** any more `[F]` release notes — plan the workflow accordingly
(uninstall the Magisk app after configuring, or keep it and accept detection).

### 1.5 KernelSU / APatch — same job, worse fit here

| | Magisk v31.1 | KernelSU v3.3.1 (2026-10-12) | APatch 11282 (2026-10-15) |
|---|---|---|---|
| Mechanism | patches boot ramdisk (`boot.img`/`init_boot`/`vendor_boot`) `[F]` | kernel-mode, **GKI** kernel integration; non-GKI needs custom kernel build `[F]` <https://kernelsu.org/guide/what-is-kernelsu.html> | kernel patch via KernelPatch; **ARM64, kernels 3.18–6.12**, needs `CONFIG_KALLSYMS` `[F]` APatch README |
| Module support | native Magisk modules `[F]` | Magisk-like modules `[F]` | APM modules ("similar to Magisk") `[F]` |
| Fit for Mi8937 (msm8937, non-GKI, no kernel build allowed) | **yes** | no (would require building/patching the device kernel) `[E]` | technically in range, but rewrites the kernel image and has its own SELinux/KPM issues `[E]` |

Conclusion: **Magisk is the only low-risk root for this device**; KernelSU/APatch buy
nothing for UX injection and add kernel-level risk `[E]`.

Recovery is a first-class safety feature: Magisk Safe Mode disables **all modules and
Zygisk** if boot does not complete twice, and `magisk --remove-modules` from `adb shell`
does the same `[F]` <https://topjohnwu.github.io/Magisk/faq.html>. Caveat: Safe Mode keys
off `boot_completed`; a crashing **SystemUI** does not necessarily prevent boot completion
(§6.3).

---

## 2. Preinstalled RRO — the key that unlocks SystemUI/Settings without a platform key

### 2.1 Where the policy is actually enforced

`OverlayManagerServiceImpl` hands each overlay to `IdmapManager.createIdmap()`, which
computes two things `[F]`
(`services/core/java/com/android/server/om/IdmapManager.java:112-121`, android16-release):

1. `calculateFulfilledPolicies()` — a bitmask (`:203-260`):
   - `PUBLIC` always;
   - `SIGNATURE` if the overlay is signed like the target;
   - `ACTOR_SIGNATURE`, `CONFIG_SIGNATURE`;
   - **plus the overlay package's own partition**: `VENDOR_PARTITION`, `PRODUCT_PARTITION`,
     `ODM_PARTITION`, `OEM_PARTITION`, `SYSTEM_PARTITION`; `/system_ext` counts as system
     (`isSystem() || isSystemExt()` at `:253-256`).
2. `enforceOverlayable()` — true for any overlay with `targetSdk >= Q` (`:183-190`).

`idmap2` then filters every resource through `CheckOverlayable()`
`[F]` (`frameworks/base/cmds/idmap2/libidmap2/ResourceMapping.cpp:51-99`):

```cpp
constexpr PolicyBitmask kDefaultPolicies =
    ODM_PARTITION | OEM_PARTITION | SYSTEM_PARTITION | VENDOR_PARTITION |
    PRODUCT_PARTITION | SIGNATURE | CONFIG_SIGNATURE;
if (!*defines_overlayable) {
  return (kDefaultPolicies & fulfilled_policies) != 0
    ? Ok : Error("overlay must be preinstalled, signed with the same signature ...");
}
// target defines overlayables: resource must be listed and policy must intersect
if (((*overlayable_info)->policy_flags & fulfilled_policies) == 0) Error(...)
```

That is the code behind the documentation sentence: *"If the target package doesn't have a
defined overlayable, the overlay must be preinstalled on the system image or signed with
the same signature as the target"* `[F]`
(<https://source.android.com/docs/core/runtime/rros#restrict-overlayable-resources>).

### 2.2 What "preinstalled" means operationally

There is no `isPreinstalled` flag; "preinstalled" is the overlay's **partition** as
PackageManager sees it. The overlay APK must (a) live in `<partition>/overlay/`, and (b) be
scanned as part of a system partition `[F]`:

- RRO docs: "To be configured, an overlay must reside in the `overlay/` directory of the
  partition" and policy `system` = "Any overlay on the system partition can override the
  resources"; `product` likewise `[F]` (RRO docs, "Use OverlayConfig"/"Restrict policies").
- `IdmapManager` maps the scan path to `isSystem()/isProduct()/...` — the partition, not
  the signer, is what satisfies `SYSTEM_PARTITION`/`PRODUCT_PARTITION` `[F]` (`:232-256`).

So a self-signed overlay APK placed in **`/product/overlay/`** is "preinstalled" for policy
purposes, and can target a package with **no overlayable at all** — the case that matters
below. **No target/platform signature is required for this route** `[F]` (nothing in
`calculateFulfilledPolicies`/`CheckOverlayable` asks for the platform key; only the
`signature` policy does). A platform signature is needed only when the target *does*
declare overlayables and the group's policy is `signature`/`actor`, or when the overlay is
**not** preinstalled `[F]`.

### 2.3 OverlayConfig, immutability, `Resources.getSystem()`

- Android 11+ configures overlays via **`<partition>/overlay/config/config.xml`** `[F]`
  (`core/java/com/android/internal/content/om/OverlayConfigParser.java:54,112`;
  docs "Use OverlayConfig"). Entries set `mutable`, `enabled`, `priority`; partition
  precedence comes from `SYSTEM_PARTITIONS` order (§2.2) and can be reordered with
  `/product/overlay/partition_order.xml`; `<merge path=…>` includes other config files `[F]`.
- If a partition has **no** `config.xml`, `android:isStatic="true"` overlays are converted
  to **immutable, default-enabled** overlays carrying that partition's policy `[F]`
  (`OverlayConfig.java:182-204`). If a `config.xml` *does* exist, `isStatic`/`priority`
  are ignored for that partition `[F]` (RRO docs "Changes in Android 11").
- **Only immutable overlays targeting `android` affect `Resources.getSystem()`** `[F]`
  (RRO docs note). Implementation: at zygote start, `OverlayConfig` collects immutable,
  enabled, `targetPackage == "android"` overlays and builds their idmaps via
  `idmap2 create-multiple` **before** any app is forked `[F]`
  (`OverlayConfig.java:488-560`, `createImmutableFrameworkIdmapsInZygote()`).

### 2.4 The decisive 2026-11 finding: SystemUI, Settings and framework-res define **no overlayables**

Probes against the exact `lineage-23.2` tree and AOSP releases (in `02-rro-preinstall-policy.log`):

| Target | `overlayable.xml` | `res/values` listing |
|---|---|---|
| `android` (framework-res, AOSP 16) | 404 (also 404 in android13/14/15-release and `main`) | no `overlayable*` file |
| `com.android.systemui` (`res`, `res-keyguard`, `res-product`) | 404 | 22 files, none overlayable |
| `com.android.settings` (`res/values`) | 404 | 20 files, none overlayable |

Therefore:

- A **user-installed** RRO (normal APK + `cmd overlay enable`) **cannot** theme these
  packages: `defines_overlayable == false` and its policy mask is only `PUBLIC`, so
  `idmap2` refuses `[F]` (code above).
- A **preinstalled** RRO in `/product/overlay` (or `/system_ext/overlay`, `/vendor/overlay`)
  satisfies `PRODUCT_PARTITION`/`SYSTEM_PARTITION` and is allowed to override **every resource of
  the target**, including `color`, `dimen`, `bool`, `style`, `drawable` **and `layout`** —
  no overlayable declaration and no target signature needed `[F]` (idmap2 code path).
- The usual Substratum-style limitation ("SystemUI has no overlayables, so a third-party
  RRO cannot theme it") applies **only to the non-preinstalled case**. This is the
  correction to docs 03/06.

Practical caveats, in order of importance:

1. **Resources only, never code.** An overlay can replace the value of an existing
   resource; it cannot add classes, change logic, or add resources *to the target* `[F]`
   (RRO docs).
2. **Layout swaps are mechanically allowed but fragile** `[E]`: an overlaid layout is
   compiled in the overlay's package, so `android:id="@+id/foo"` inside it yields overlay
   IDs; SystemUI/Settings code doing `findViewById(R.id.foo)` against the *target's* IDs
   will get `null` or crash. Keep target IDs only if you can express them as references to
   the target package (not generally possible without the target's R). Restyling via
   colors/dimens/styles/drawables is safe; structural replacement belongs to hooks (§6).
3. A bad **framework** immutable overlay is applied in zygote to every process — the
   highest-blast-radius change in this whole document `[F]` (zygote idmaps) / `[E]` (risk).
   A bad **SystemUI** overlay is scoped to SystemUI and is reported by OMS as
   `STATE_NO_IDMAP` instead of boot-breaking `[E]`.

### 2.5 Shipping recipe (verified against docs/code, device test still required)

```
ztmetro-framework/                     # one Magisk module per concern
├── module.prop
└── system/product/overlay/
    ├── ZtMetroFramework.apk           # targetPackage="android", hasCode=false
    └── config/config.xml              # optional; see note
```

```xml
<!-- product/overlay/config/config.xml -->
<config>
  <overlay package="org.ztmetro.overlay.framework" mutable="false" enabled="true"/>
</config>
```

- Omit `config.xml` and rely on `android:isStatic="true"` in the manifest for a minimal
  immutable+enabled overlay `[F]` (`OverlayConfig.java:198`); ship `config.xml` if you need
  priority/precedence, but then **every** overlay in `/product/overlay` you want enabled
  must be listed (and `isStatic` is ignored) `[F]`.
- `/system/overlay` does **not** exist in AOSP 16: the `system` partition is constructed with
  `containsOverlay=false`; overlay dirs are scanned only in `vendor`, `odm`, `oem`, `product`,
  `system_ext` `[F]` (`PackagePartitions.java:68-88`). Use `/product/overlay` (policy `product`)
  or `/system_ext/overlay` (policy `system`, since `/system_ext` counts as system `[F]`).
- The APK is a normal, self-signed APK with `<overlay android:targetPackage="…"/>` and
  `android:hasCode="false"`; **omit `android:targetName`** when the target has no
  overlayable `[F]` (RRO docs).
- Set SELinux context/permissions for the added APK (Magisk copies attributes for replaced
  files, but new files need them): `set_perm_recursive $MODPATH/system/product/overlay 0 0 0755 0644 u:object_r:system_file:s0`
  in `customize.sh` `[E]` (Magisk install API).
- Verify on device: `adb shell cmd overlay list`, `adb shell cmd overlay dump`,
  `adb shell idmap2 dump --idmap-path …`, and logcat for `idmap2` "not allowed to overlay
  resource" warnings `[E]`. First test: change one framework color and one SystemUI color,
  reboot, confirm; only then add more.
- Rollback: `magisk --remove-modules` (all) or delete the module dir from `/data/adb/modules`
  in recovery; because overlays are immutable, there is no runtime "disable" for them `[F]`.

### 2.6 Does the overlay survive/port?

- OTA: the module directory survives in `/data`; the **mounts** do after re-root. If the
  OTA changes resource names, orphan mappings are skipped by idmap with a warning `[F]`
  (ResourceMapping logs "not allowed to overlay resource" / "target resource not found").
- Portability: resource **names** are stable across Android releases, so a framework
  palette overlay ports well; SystemUI layout/dimen names change often, so those overlays
  are device/release-specific `[E]`.

---

## 3. Replacing Settings.apk / SystemUI.apk with a modified APK — why there is no supported route

The chain that kills it:

1. **These are platform-signed, shared-UID apps.** SystemUI: `android:sharedUserId="android.uid.systemui"`
   `[F]` (`packages/SystemUI/AndroidManifest.xml:22`); Settings: `android:sharedUserId="android.uid.system"`
   `[F]` (`packages/apps/Settings/AndroidManifest.xml:5`). They hold signature-level
   permissions, e.g. `STATUS_BAR_SERVICE` (`signature|recents`),
   `INJECT_EVENTS` (`signature`), `INTERNAL_SYSTEM_WINDOW` (`signature|module|recents`),
   `CHANGE_OVERLAY_PACKAGES` (`signature|privileged`) `[F]` (`core/res/AndroidManifest.xml:5458,5567,5522,5055`).
2. **The APK signature covers the whole file.** "APK signature scheme v2 is a whole-file
   signature scheme … detecting any changes to the protected parts of the APK" `[F]`
   (<https://source.android.com/docs/security/features/apksigning/v2>); v3 adds key
   rotation but also signs the file contents `[F]`
   (<https://source.android.com/docs/security/features/apksigning/v3>). So:
   - **modified-but-not-re-signed APK** → signature verification fails at scan; the package
     is rejected (`INSTALL_PARSE_FAILED_…`/scan failure) `[F]` (scheme definition) `[E]`
     (exact exception path).
   - **re-signed with a self-generated key** → parses, then fails the identity checks below.
3. **PackageManager refuses the identity change.** On scan/update of an existing package
   (`PackageManagerServiceUtils.java:578-582`):
   `throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE, "Existing package
   … signatures do not match newer version; ignoring!")` `[F]`. This is not only the
   `adb install` path: at boot, PMS reconciles every scanned system app against the
   `PackageSetting` persisted in `/data/system/packages.xml`, so a swapped
   `/system_ext/priv-app/SystemUI/SystemUI.apk` is rejected during the boot scan with the
   same exception and the package is left uninstalled `[F]` (same code path) / `[E]`
   (boot-scan call site). For a shared UID: `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`,
   "has no signatures that match those in shared user …" `[F]` (`:584-617`).
4. **What breaks:** `com.android.systemui` is not installed/updated, so there is no status
   bar, shade, keyguard, nav bar, recents UI; `com.android.settings` is missing or
   orphaned. Recovery is not automatic in the usual way: system_server can still finish
   booting without SystemUI, so `boot_completed` may become true and Magisk's
   double-failure Safe Mode may never trigger `[E]` (Safe Mode condition documented `[F]`
   FAQ). Plan for `adb shell magisk --remove-modules` or recovery-side removal of
   `/data/adb/modules/*` `[E]`.
5. **No bypass exists for our build.**
   - `adb install` cannot create a privileged app: needs system-partition path + privapp
     allowlist + matching signature `[F]` (documented in doc 06; priv-app rules) — and the
     signature is the blocker.
   - microG "signature spoofing" is a **client-facing** patch: it lets an app holding
     `FAKE_PACKAGE_SIGNATURE` (GmsCore) report Play-Services signatures to *client apps*
     (`mayFakeSignature` in PackageManager, microG PR #1586 / e-foundation commit
     `5c3d737f`). It does not give a package the platform certificate, does not satisfy
     `sharedUserId` checks for SystemUI, and cannot make a re-signed SystemUI "be" the
     platform app `[F]` (patch scope) / `[E]` (conclusion).
   - The only real route is a ROM built with keys you control, or a patched PackageManager
     (an unsupported, security-destroying modification) `[E]`.

So: **for an officially signed microG build, "Magisk SystemUI/Settings swap" is not a
fragile option — it is a non-option.** The signature/AVB part of the old claim can now be
stated precisely: AVB is *not* the blocker on this device (`BOARD_AVB_ENABLE := false`);
**PackageManager signature identity is**, and it is independent of AVB.

---

## 4. Fonts on Android 16

### 4.1 The config file changed in Android 15 — this is the biggest practical trap

`[F]` `SystemFonts` (android16-release) defines:

```java
private static final String FONTS_XML = getFontsXmlDir() + "font_fallback.xml"; // /system/etc/font_fallback.xml
public  static final String LEGACY_FONTS_XML = getFontsXmlDir() + "fonts.xml";  // /system/etc/fonts.xml
private static final String OEM_XML = "/product/etc/fonts_customization.xml";
public  static final String OEM_FONT_DIR = "/product/fonts/";
```
(`graphics/java/android/graphics/fonts/SystemFonts.java:61-70`)

`getSystemFontConfig()` parses **`font_fallback.xml`** (`:332-334`); `fonts.xml` is only
used by `getSystemPreinstalledFontConfigFromLegacyXml()` (`:367`) and by apps that read it
directly. Official docs: *"Starting in Android 15 … vendors must add new variable font
configurations to `font_fallback.xml` instead of `fonts.xml`, as `fonts.xml` is being
deprecated"* `[F]` (<https://source.android.com/docs/core/fonts/custom-font-fallback>).

In LineageOS 23.2, `font_fallback.xml` is **generated at build time** from
`font_config.json` + `alias.json` + `fallback_order.json` by `generate_fonts_xml`, while
`fonts.xml` is still installed as `prebuilt_etc` `[F]`
(`data/fonts/Android.bp:56-114`, lineage-23.2). And `sans-serif` is a **variable font**:
`Roboto-Regular.ttf` with `<axis tag="wght" …>` / `ital` / `wdth` entries for every weight
`[F]` (`data/fonts/fonts.xml:38-131`, lineage-23.2).

### 4.2 What a Magisk font module must do

| Option | Mechanism | Verdict |
|---|---|---|
| A. Replace the **font files only** (`/system/fonts/Roboto-Regular.ttf` etc.) with a variable font carrying the same axes | no XML edit; `font_fallback.xml` keeps pointing at the same names | **best first try** `[E]`: lowest blast radius; requires a VF with `wght`/`ital`/`wdth` or all weights collapse |
| B. Replace `/system/etc/font_fallback.xml` **and** `/system/etc/fonts.xml` plus add TTFs | full control (static per-weight families); both files needed for legacy readers `[F]` docs | works, but must reproduce valid syntax incl. `<axis>` if the source font is variable `[F]`; higher risk |
| C. `/product/fonts` + `/product/etc/fonts_customization.xml` | OEM customization path read by `SystemFonts` `[F]` | additive/customization; overriding `sans-serif` itself is unproven `[?]` |
| D. FontManager updatable fonts (`/data/fonts/files`, `/data/fonts/config/config.xml`) | requires `UPDATE_FONTS` (signature) + fs-verity certs `[F]` (`FontManagerService.java:72-73`) | not available to us |

### 4.3 Do apps ignore the system font? (Telegram reference case)

Yes, partially — and deliberately so. Telegram for Android bundles fonts in
`assets/fonts/`: `rmedium.ttf`, `rextrabold.ttf`, `rmediumitalic.ttf`, `rmono.ttf`,
`rmono_var.ttf`, `mw_bold.ttf` (Merriweather), … `[F]`
(API listing in `04-fonts.log`; the telegram.org APK download was blocked from this host,
so the asset list and source were read from the upstream repository), and loads them with
`Typeface.createFromAsset` via
`AndroidUtilities.getTypeface("fonts/…")` `[F]`
(`TMessagesProj/.../AndroidUtilities.java:253-257,2501`). Those specific styles (medium
headers, bold titles, mono) ignore the system font. Paints that are not explicitly set to
a bundled font use the platform default typeface (e.g. `Theme.java:8186` sets
`Typeface.DEFAULT_BOLD` on a default paint), so ordinary text follows the system
sans-serif `[E]`.
Material-You/dynamic colour does not change typefaces; app-bundled fonts and app-level
themes always win over system fonts `[F]` (per-app resources) `[E]` (Telegram behaviour
detail).

Implication: replacing the system `sans-serif` visibly changes Telegram's message list,
Settings and most apps, but *not* Telegram's branded headers. That is acceptable for
ztmetro (the shell/launcher should bundle Segoe-like fonts anyway).

---

## 5. Verified boot, OTA, and what Magisk actually touches

- **AVB is disabled** in the Mi8937 build `[F]` (`BOARD_AVB_ENABLE := false`), and the
  bootloader is unlocked (project premise). Magisk patches the image that carries the ramdisk —
  `boot.img` on this device (no `init_boot`/`vendor_boot` file is published for Mi8937) `[E]`;
  install guide `[F]`: patch `boot.img`/`init_boot.img`/`vendor_boot.img`, optional
  `fastboot flash vbmeta --disable-verity --disable-verification` only if a separate
  vbmeta exists `[F]` (<https://topjohnwu.github.io/Magisk/install.html>).
- **dm-verity**: Magisk does not write to `/system`, `/system_ext` or `/product`; the module
  tree is bind-mounted at `post-fs-data` `[F]` (details/init.rc), so block-level
  verification of the real partitions is unaffected. (Even so: never remount read-only
  partitions rw — that *does* tamper block verification and breaks OTA `[F]` ota.md.)
- **OTA on this device is A-only**: `AB_OTA_UPDATER := false` in the common device config `[F]`
  ([`BoardConfigCommon.mk:10`](https://raw.githubusercontent.com/LineageOS/android_device_xiaomi_mithorium-common/lineage-23.2/BoardConfigCommon.mk)),
  and the published artifacts pair `…-Mi8937.zip` with `boot.img`/`recovery.img` and a
  `super_empty.img` `[F]` (download listing). Magisk's official OTA preservation
  workflow ("install to inactive slot") is **A/B only** `[F]` (<https://topjohnwu.github.io/Magisk/ota.html>).
- **Expected OTA outcome**: the LineageOS updater boots recovery, installs the zip (system
  + boot images replaced), and root is gone; `/data/adb/modules` (and the launcher APK)
  survive; after reboot the phone boots unrooted but otherwise functional. Re-root by
  patching the **matching** published `lineage-23.2-<date>-microG-Mi8937-boot.img` with the
  Magisk app and `fastboot flash boot …` (the exact matching boot image is published, so
  no need to extract payloads) `[F]` (download listing) / `[E]` (workflow).
- **addon.d survival is conditional, not assumed.** Magisk ships `assets/addon.d.sh`
  (`/data/adb/magisk/addon.d.sh`) that re-patches the boot image in `post-restore` `[F]`
  (local APK asset, `05-…log`), and LineageOS A-only OTA runs addon.d/backuptool `[E]` (inferred from the wiki's A/B note and the presence of
  `vendor/lineage/prebuilt/common/bin/backuptool*`), but the script must exist in the
  **real** `/system/addon.d`, which Magisk only creates when it is installed *through
  recovery* `[E]` (mSurvival README). A boot.img-patched install does not put it there, and
  a Magisk module cannot (its mounts are invisible to recovery). Treat "root survives OTA"
  as false by default for our install method.
- Modules *do* survive OTAs in `/data`, and their mounts return once root is restored; the
  "lost on update" wording in docs 03/04 should be read as "no effect until re-rooted" `[E]`.

---

## 6. Zygisk / LSPosed on Android 16 (API 36)

### 6.1 Status

- Official **LSPosed last release is v1.9.2 (2023-10-11)** — pre-Android-15, no A16 claim
  `[F]` (GitHub releases API, `06-lsposed-zygisk.log`).
- The maintained fork is **JingMatrix/Vector** (formerly JingMatrix/LSPosed): release
  **v2.2 (2026-08-04)**, module id `zygisk_vector`, "supports devices running Android 8.1
  through Android 17 Beta", requires "a recent installation of Magisk or KernelSU with
  Zygisk enabled", hooks in memory with no APK modification `[F]`
  (<https://github.com/JingMatrix/Vector>; `module.prop` in the release zip).
- Zygisk itself is provided by Magisk (introduced in v24.0, working on Android 16 QPR2+ since
  v30.3) `[F]` changes.html; or by NeoZygisk/Zygisk Next `[E]`.
- KernelSU/APatch on this device: see §1.5 — not worth it.

### 6.2 What runtime injection buys us

- **Settings Metro header**: hook the Settings activity's `onCreate`/view-model load and
  insert a header view above the list (or wrap the list in a Metro title block) — no APK
  replacement, no platform key, reversible by disabling the module `[F]` (framework
  capability) / `[E]` (specific hook points: `SettingsHomepageActivity`,
  `SettingsActivity`, or the `RecyclerView` adapter).
- **SystemUI Action Centre**: hook `CentralSurfacesImpl`/`NotificationShadeWindowView` /
  shade layout controller to restyle the shade, or use per-process resource replacement
  (XResources) for colors/dimens that the RRO cannot reach at runtime; structural changes
  (2-column tile grid, Metro header strip) are code work, not resources `[E]`.
- Alternatively, **preinstalled RRO does the resource-level 80%** (palette, dims, drawable
  shapes) with no hooks; use hooks only for structure. This split keeps failure domains
  small.

### 6.3 Security and stability (the real cost)

- Hooks run inside `system_server`/SystemUI with root privileges; a bug means UI crashes,
  boot loops or silent misbehaviour, and the attack surface is the whole system `[E]`.
- Magisk Safe Mode keys off `boot_completed`; a SystemUI-only crash loop can leave the
  device "booted" but unusable, so automatic module disabling may not fire `[E]` (condition
  `[F]` FAQ). Keep a recovery path: `adb shell magisk --remove-modules`, or mount `/data`
  in recovery and delete the module dir `[E]`.
- Hooks are tied to internal class/method names: LineageOS security updates and Android 16
  QPRs will break them `[E]`; pin the module to the exact build and re-test after OTA.
- Root/hook detection and Play Integrity failures are unavoidable; v31.1 removed Magisk's
  hiding features `[F]` release notes.
- 2 GB RAM: Zygisk/LSPosed add per-process overhead; scope hooks to `com.android.systemui`
  and `com.android.settings` only, never "all apps" `[E]`.

---

## 7. Decision table for the five ztmetro blocks

Legend: root = Magisk root required; key = platform/target signature required; OTA =
survives a LineageOS OTA *as installed* (root re-patch assumed separately).

| Block | Recommended mechanism | root | key | OTA | ROM portability | Risk | Evidence |
|---|---|---|---|---|---|---|---|
| **1. Framework accent + typography** | Preinstalled RRO (`targetPackage="android"`, immutable, `/product/overlay` + optional `config.xml`) in its own Magisk module. Override `system_accent*`, `colorAccent*`, dims, `textAppearance*` styles. | yes | **no** | module survives; overlay re-applies after re-root | high (resource names stable) | medium (zygote-wide idmap; a bad value can affect every app) | [F] idmap2/IdmapManager; [F] OverlayConfig zygote; [E] risk |
| **2. System font (Segoe-like)** | Magisk module: replace `/system/fonts/Roboto-Regular.ttf` with a VF (axes `wght`/`ital`/`wdth`); fall back to replacing `font_fallback.xml` + `fonts.xml` + TTFs. | yes | no | module survives; re-mount after re-root | medium (names stable, metrics differ) | medium (variable-font mismatch → wrong weights; boot-time font parse failure) | [F] SystemFonts paths; [F] Lineage fonts.xml variable; [F] docs deprecation |
| **3. Settings Metro header** | LSPosed (Vector) module adding the header view; resource-level restyle via preinstalled RRO (colors/dimens) as the cheap part. **No APK swap.** | yes | **no** | no (re-hook after OTA; re-test) | low–medium (hook points change) | medium (Settings crash recoverable; hook fragility) | [F] Vector compat; [F] no-overlayable policy; [E] hook points |
| **4. SystemUI Metro Action Centre** | Split: preinstalled RRO for palette/dimens/drawables/styles; LSPosed for structure (shade header, tile grid). **No APK swap.** | yes | **no** | no (re-hook after OTA) | low (SystemUI internals churn) | high (UI crash loops; Safe Mode may not trigger) | [F] policy allows all resources; [F] manifest shared UID/signature; [E] runtime risk |
| **5. Start launcher** | Normal APK, installed as user app, set as HOME; optionally preinstall via Magisk only for convenience (not needed). | no | no | **yes** (data app survives) | high (own APK) | low | [F] Android app model |

**Recommended delivery stack (v1, installable on the existing phone):**

1. **Magisk v31.1**, boot.img patched from the *matching* microG `boot.img`; grant root
   ahead of time (no prompt in v31.1).
2. **Module A `ztmetro-framework-overlay`** — one immutable RRO for accent + typography
   (framework resources). Test with a single color first.
3. **Module B `ztmetro-font`** — variable Roboto replacement (option 4.2-A), then the XML
   route only if needed.
4. **`ZtMetroLauncher.apk`** — plain APK, default HOME. This is the highest-value,
   zero-risk block and should ship first.
5. **Module C `ztmetro-settings-header`** (Vector + hooks) — optional, separate module.
6. **Module D `ztmetro-sysui`** — RRO part first; add Vector hooks only after a stable
   baseline; keep it independently disableable and keep a recovery runbook.
7. **Do not** ship any APK-replacement module. **Do not** bundle all five blocks into one
   module: separate modules are the rollback unit (Safe Mode disables all, but a single
   bad module is easier to bisect).

---

## 8. Docs audit (claim by claim)

Verdicts: **confirmed** (accurate), **corrected** (materially wrong/incomplete),
**extended** (true but missing the mechanism that matters), **unverified** (no evidence
found in this pass).

| # | Doc / claim | Verdict | Evidence |
|---|---|---|---|
| 1 | 03 §1: "RRO only changes the value of an existing resource; cannot add resources or execute code" | confirmed (for target resources); extended: an overlay APK may define its *own* new resources used by an overlaid layout | [F] RRO docs; [E] overlay packaging |
| 2 | 03 §1: "`android:targetName` must be present iff the target defines overlayables" | confirmed | [F] RRO docs |
| 3 | 03 §1: "If the target has no overlayable, the overlay must be preinstalled or signed with the target signature" | confirmed, and now backed by code: `kDefaultPolicies` includes SYSTEM/PRODUCT/VENDOR/ODM/OEM/SIGNATURE | [F] RRO docs; [F] `ResourceMapping.cpp:55-77` |
| 3b | 03 §4 table: "`android` (framework-res) has overlayable theme groups (`ThemeResources`)" | **corrected**: AOSP 13/14/15/16 `core/res/res/values/overlayable.xml` is absent (404 in every release and `main`); the RRO-docs `android`/`ThemeResources` example is illustrative. Consequence: a preinstalled overlay may override any framework resource, and a *user-installed* self-signed overlay cannot target framework-res at all | [F] 404 probes (`02-rro-preinstall-policy.log`); [F] `ResourceMapping.cpp:60-77` |
| 4 | 03 §1: "Android 11+ prefers OverlayConfig; static RROs are enabled by default and immutable" | **corrected**: only when that partition has **no** `config.xml`; with `config.xml`, `isStatic`/`priority` are ignored | [F] `OverlayConfig.java:182-204`; RRO docs "Changes in Android 11" |
| 5 | 03 §1: precedence `system < vendor < odm < oem < product < system_ext` | confirmed: `SYSTEM_PARTITIONS` is built in exactly that ascending-specificity order | [F] `core/java/android/content/pm/PackagePartitions.java:68-88` (android16-release) |
| 6 | 03 §1: "Only immutable overlays targeting `android` affect `Resources.getSystem()`" | confirmed, mechanism = zygote `createImmutableFrameworkIdmapsInZygote()` | [F] RRO docs; [F] `OverlayConfig.java:488-560` |
| 7 | 03 §2: `fabricate` requires root; `enable/disable` need `CHANGE_OVERLAY_PACKAGES` | confirmed (permission is `signature|privileged`) | [F] framework manifest `:5055`; shell command source |
| 8 | 03 §4: "SystemUI has no `overlayable.xml` → third-party RRO cannot legally theme SystemUI" | **corrected**: true only for *user-installed* RROs. A **preinstalled** overlay (Magisk mount into `/product/overlay`) may override every SystemUI resource without overlayables or target signature | [F] `ResourceMapping.cpp:60-77`; [F] 404 probes; [F] `IdmapManager.java:232-256` |
| 9 | 03 §4: "SystemUI palette changes must go through `android` resources or a SystemUI fork" | **corrected**: a preinstalled RRO can target SystemUI resources directly (colors/dimens/drawables/styles) | same as #8 |
| 10 | 03 §8: "Change layouts of SystemUI — layouts are not overlayable in practice" | **corrected**: policy-wise layouts *are* overridable on a no-overlayable target; they are practically fragile because target-view IDs are compiled into target code | [F] code allows any resource; [E] `findViewById` ID problem |
| 11 | 03 §8: "Add new UI elements … overlays only replace existing values" | confirmed (structure needs code/hooks/fork) | [F] RRO docs; [E] |
| 12 | 03 §5: "`fonts_customization.xml` in `/system/etc` enumerates updatable font families" | **corrected**: OEM customization is `/product/etc/fonts_customization.xml`; system font map is `/system/etc/font_fallback.xml` (fonts.xml legacy/deprecated); updatable fonts use `/data/fonts/config/config.xml` + fs-verity | [F] `SystemFonts.java:61-70`; [F] `FontManagerService.java:72-73`; [F] AOSP docs |
| 13 | 03 §5: "An RRO can map a target `font/...` resource to its own font file" | unverified/ineffective: system fonts are files referenced by XML, not overlayable resources; framework-res has no overlayable anyway | [F] SystemFonts; [?] |
| 14 | 03 §7 Option C: "Magisk static overlay … AVB/OTA fragility" | **corrected** for this device: AVB is disabled; the real fragility is OTA replacing `/boot` (root loss) and resource-name drift, not AVB | [F] `BoardConfig.mk:23`; [F] Magisk ota.md |
| 15 | 03 §7: overlays "lost on system update" | extended: the module stays in `/data` and re-mounts after re-root; overlays only disappear while root is absent | [F] module layout; [F] boot stages; [E] workflow |
| 16 | 04 §L3: "root/Magisk module … survives OTA usually yes" | **corrected** for A-only boot-patched Magisk: root is lost on OTA; module files survive; re-patch `boot.img` | [F] ota.md (A/B only); [F] A-only artifacts |
| 17 | 04 §3.2: "Systemless replacement of a platform-signed APK is a signature hazard, not supported" | confirmed and sharpened: scan rejects with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`; no supported bypass | [F] `PackageManagerServiceUtils.java:578-617` |
| 18 | 04 §3.3: "Signature spoofing is not a general platform-key bypass" | confirmed: gated by `FAKE_PACKAGE_SIGNATURE` / `mayFakeSignature`, aimed at Play-Services identity reporting | [F] microG PR #1586; e-foundation commit |
| 19 | 04 §4: "Magisk does not re-sign APKs and does not disable signature checks" | confirmed | [F] v2 scheme; [F] PMS code |
| 20 | 04 §4: "Static RROs in `/product/overlay` need `OverlayConfig` or `isStatic`; preinstalled overlay may target non-overlayable resources" | confirmed | [F] `OverlayConfig.java:198`; [F] idmap2 |
| 21 | 06 short answer: "Not into an officially-signed ROM … only clean path is build the whole ROM" | **corrected/extended**: true for **APK replacement/code**; false for **resource-level** SystemUI/Settings theming, which preinstalled RROs permit without platform keys; runtime hooks are a third route | [F] idmap2 policy; [F] Vector |
| 22 | 06 §1: "SystemUI: `platform_apis`, `system_ext_specific`, `certificate: platform`, `privileged`, privapp allowlist" | confirmed | [F] `packages/SystemUI/Android.bp` (audited in doc 06) |
| 23 | 06 §4C: "Magisk does not re-sign APKs … replacement is a boot-risk, not a supported feature" | confirmed; add: on this device a broken SystemUI may **not** be auto-recovered by Magisk Safe Mode | [F] PMS; [F] FAQ; [E] boot-completed nuance |
| 24 | 06 §4D: "If SystemUI exposes no overlayables, a third-party overlay cannot legally change them" | **corrected**: only non-preinstalled overlays are limited; preinstall is the documented escape hatch | [F] RRO docs; [F] idmap2 |
| 25 | 06 §7: "Never base the project on swapping a platform-signed SystemUI into an officially-signed ROM" | confirmed (and now proven mechanically, not just warned) | [F] §3 above |
| 26 | 07 §2: "System font: RRO font / `fonts_customization.xml` / launcher-bundled font" | **corrected**: RRO cannot replace font files; on A16 use `font_fallback.xml` (+ legacy `fonts.xml`) or OEM `/product` route | [F] SystemFonts; [F] Android.bp |
| 27 | 07 §2: "Status bar/shade: low at stage 1, high with fork" | **extended**: resource-level theming via preinstalled RRO raises stage-1 fidelity substantially; only structure/animation needs fork or hooks | [F] idmap2; [E] fidelity |
| 28 | 07 §2: "Third-party apps that aren't Material-You-aware ignore the accent" | confirmed (apps with own palettes ignore it) | [E] (Android resource semantics) |
| 29 | 07 §2: "APK cannot make a system nav bar" | confirmed | [F] WindowManager/SystemUI ownership |

**The two headline corrections** (requested explicitly):

- *"Magisk SystemUI swap is fragile and its signature/AVB implications are not verified"* —
  now verified: **AVB is disabled on Mi8937**, so AVB is irrelevant; the blocker is
  PackageManager **signature identity** (platform key + `android.uid.systemui` shared UID),
  which makes the swap impossible, not merely fragile. It is a **non-option** on the
  official microG build `[F]` (`PackageManagerServiceUtils.java:578-617`).
- *"SystemUI has no overlayables so a third-party RRO cannot theme it"* — **wrong as
  stated**. No overlayables means a *user-installed* RRO is rejected, but a **preinstalled**
  RRO (Magisk mount into `/product/overlay`) satisfies the `product`/`system` policy and
  may override **any** SystemUI resource, including layouts, without any signature `[F]`
  (`ResourceMapping.cpp:60-77`, `IdmapManager.java:232-256`). This is the single most
  important unlock for the project.

---

## 9. Open questions to settle on the device (short list)

1. Does PackageManager scan a Magisk-mounted `/product/overlay` as `isProduct` and produce
   a working idmap? Check `dumpsys package <overlay>` sourceDir and `cmd overlay list`
   `[E]`/`[?]`.
2. Does the first framework overlay apply cleanly (immutable, zygote idmap) and what is its
   RAM cost on 2 GB? `[E]`.
3. Does a static (no config.xml) overlay survive a `product` OTA path, and does the
   `config.xml` route change precedence as expected? `[F]` code / `[?]` device.
4. Does replacing `Roboto-Regular.ttf` with a static font (no axes) degrade weights, and
   does `font_fallback.xml` keep working? `[E]`.
5. Can SystemUI crash-loop be recovered without Safe Mode (adb `magisk --remove-modules`)?
   `[E]`.
6. Is the installed build `user` or `userdebug` (`ro.build.type`) — affects `adb root`
   availability, not signatures? `[?]`.
7. Exact `build.prop` signing identity: is the microG build signed with the project's
   release keys and does it carry the APEX test-key issue? `[?]`/`[F]` site post.

---

## 10. Sources

- Magisk: <https://topjohnwu.github.io/Magisk/guides.html>, <https://topjohnwu.github.io/Magisk/details.html>,
  <https://topjohnwu.github.io/Magisk/changes.html>, <https://topjohnwu.github.io/Magisk/ota.html>,
  <https://topjohnwu.github.io/Magisk/faq.html>, <https://topjohnwu.github.io/Magisk/install.html>,
  releases <https://github.com/topjohnwu/Magisk/releases>.
- AOSP RRO: <https://source.android.com/docs/core/runtime/rros>; fonts:
  <https://source.android.com/docs/core/fonts/custom-font-fallback>; signatures:
  <https://source.android.com/docs/security/features/apksigning/v2>, `…/v3`.
- AOSP 16 source: `frameworks/base` `services/core/java/com/android/server/om/IdmapManager.java`,
  `core/java/com/android/internal/content/om/OverlayConfig{,Parser}.java`,
  `cmds/idmap2/libidmap2/ResourceMapping.cpp`, `graphics/java/android/graphics/fonts/SystemFonts.java`,
  `services/core/java/com/android/server/graphics/fonts/FontManagerService.java`,
  `services/core/java/com/android/server/pm/PackageManagerServiceUtils.java`,
  `system/core/rootdir/init.rc`, `packages/SystemUI/AndroidManifest.xml`,
  `packages/apps/Settings/AndroidManifest.xml`, `core/res/AndroidManifest.xml`.
- LineageOS 23.2: `android_frameworks_base` `data/fonts/{fonts.xml,Android.bp,font_config.json}`,
  `packages/SystemUI/res*`; `android_device_xiaomi_Mi8937/BoardConfig.mk`;
  `android_vendor_lineage/prebuilt/common/bin/backuptool*`.
- Releases/community: <https://github.com/JingMatrix/Vector>, <https://github.com/LSPosed/LSPosed/releases>,
  <https://github.com/tiann/KernelSU>, <https://kernelsu.org/guide/what-is-kernelsu.html>,
  <https://github.com/bmax121/APatch>, <https://github.com/microg/GmsCore/pull/1586>,
  <https://github.com/lineageos4microg/android_vendor_partner_gms/issues/42>,
  <https://download.lineage.microg.org/Mi8937/>, <https://lineage.microg.org/>.
- Local evidence: [`../evidence/injection-research/01-magisk-mechanics.log`](../evidence/injection-research/01-magisk-mechanics.log)
  … `06-lsposed-zygisk.log` (raw command output, Magisk v31.1 APK, Vector v2.2 zip).

Continue with [`08-decision.md`](08-decision.md) for the delivery decision.
