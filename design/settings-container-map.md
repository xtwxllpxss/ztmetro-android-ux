# Settings container map (`lineage-23.2`) + Metro header hook proposal

Target: `LineageOS/android_packages_apps_Settings` @ `lineage-23.2`
(`e1b8cd2dde2edd8c513362efc55a770270dce899`). All line numbers are from the raw
files at that commit (fetched 2026-11-16). `[F]` = verified line, `[E]` = estimate,
`[?]` = needs a device test.

---

## 1. Who owns the screen chrome

```
FragmentActivity (androidx)
└── SettingsBaseActivity            src/com/android/settings/core/SettingsBaseActivity.java:63
    └── SettingsActivity            src/com/android/settings/SettingsActivity.java:84
        └── SubSettings             src/com/android/settings/SubSettings.java:22
SettingsHomepageActivity            src/com/android/settings/homepage/SettingsHomepageActivity.java:89
                                    (extends FragmentActivity directly — NOT SettingsBaseActivity)
```

`[F]` for all four declarations.

**`SettingsBaseActivity.onCreate(Bundle)` — line 97 — builds the shared chrome.**
Lines 137-152 `[F]`:

```java
final TypedArray theme = getTheme().obtainStyledAttributes(android.R.styleable.Theme);
if (!theme.getBoolean(android.R.styleable.Theme_windowNoTitle, false)) {
    requestWindowFeature(Window.FEATURE_NO_TITLE);
}
if (isToolbarEnabled() && !isAnySetupWizard) {
    int resId = SettingsThemeHelper.isExpressiveTheme(getApplicationContext())
            ? EXPRESSIVE_LAYOUT_ID : COLLAPSING_LAYOUT_ID;
    super.setContentView(resId);                       // SettingsLib collapsing-toolbar shell
    mCollapsingToolbarLayout = findViewById(
            com.android.settingslib.collapsingtoolbar.R.id.collapsing_toolbar);
    mAppBarLayout = findViewById(R.id.app_bar);
    getToolbarDelegate().initCollapsingToolbar(mCollapsingToolbarLayout, mAppBarLayout);
} else {
    super.setContentView(R.layout.settings_base_layout);  // plain shell
}
```

- `EXPRESSIVE_LAYOUT_ID` = `com.android.settingslib.collapsingtoolbar.R.layout.settingslib_expressive_collapsing_toolbar_base_layout` (line 75-77)
- `COLLAPSING_LAYOUT_ID` = `…R.layout.collapsing_toolbar_base_layout` (line 78-79)
- Toolbar lookup + ActionBar install: lines 155-161 (`findViewById(R.id.action_bar)`, `setActionBar(toolbar)`).
- Toolbar buttons: `primary_button`, `secondary_button`, `action_button`, `floating_toolbar` (lines 163-172).

**`R.layout.settings_base_layout`** (`res/layout/settings_base_layout.xml`) `[F]` is a
vertical `LinearLayout`:

| index | id | view |
|---|---|---|
| 0 | `@+id/content_parent` (the LinearLayout itself, line 18) | root, `fitsSystemWindows=true` |
| 0 | `@+id/action_bar` (line 26) | `Toolbar` |
| 1 | `@+id/content_frame` (line 32) | `FrameLayout`, the per-screen host |

**`SettingsBaseActivity.setContentView` — line 220-238** `[F]` redirects *every*
screen layout into that host:

```java
@Override
public void setContentView(@LayoutRes int layoutResID) {
    final ViewGroup parent = findViewById(R.id.content_frame);
    if (parent != null) { parent.removeAllViews(); }
    LayoutInflater.from(this).inflate(layoutResID, parent);
}

@Override public void setContentView(View view) {
    ((ViewGroup) findViewById(R.id.content_frame)).addView(view);
}
@Override public void setContentView(View view, ViewGroup.LayoutParams params) {
    ((ViewGroup) findViewById(R.id.content_frame)).addView(view, params);
}
```

This is the single choke point: **every** Settings screen — home and sub-screen —
ends up as child #0 of `R.id.content_frame`.

---

## 2. Home vs. sub-screen: what actually differs

### Sub-screen path (Wi-Fi, Display, App info, …)

`SubSettings extends SettingsActivity` with no layout of its own — it only overrides
`onNavigateUp`/`isValidFragment` (`SubSettings.java:22-38`) `[F]`. So:

`SettingsActivity.onCreate` (line 273) → `createUiFromIntent(savedState, intent)` (line 289)
→ `setContentView(R.layout.settings_main_prefs)` (line 313) `[F]`.

`res/layout/settings_main_prefs.xml` `[F]` — vertical `LinearLayout`:

| index | id | view | notes |
|---|---|---|---|
| 0 | `@+id/switch_bar` | `com.android.settings.widget.SettingsMainSwitchBar` | `visibility=gone` by default |
| 1 | `@+id/main_content` | `FrameLayout`, `layout_weight=1` | the fragment container |
| 2 | `@+id/button_bar` | `RelativeLayout` | `visibility=gone`, SUW buttons |

`SettingsActivity` reads `R.id.switch_bar` (line 314) and sets its Z relative to
`findViewById(R.id.main_content)` (line 317) `[F]`.

### Settings home

Two different hosts exist `[F]`:

1. **Launcher entry / real homepage** — `SettingsHomepageActivity`
   (`extends FragmentActivity`, line 89): `onCreate` line 186,
   `setContentView(R.layout.settings_homepage_container)` line 252.
   `res/layout/settings_homepage_container.xml` is a `CoordinatorLayout`
   (`@+id/settings_homepage_container`) containing
   `NestedScrollView(@+id/main_content_scrollable_container)` →
   `LinearLayout(@+id/homepage_container)` → `suggestion_content`,
   `contextual_cards_content`, `main_content`; plus `AppBarLayout(@+id/app_bar)` →
   `app_bar_container` → search bar (`@+id/search_action_bar`,
   `@+id/search_bar_title`, used at lines 370/410/440) `[F]`.
2. **Fallback home inside `SettingsActivity`** — `TopLevelSettings` fragment pushed
   into `main_content` with title `R.string.dashboard_title`
   (`SettingsActivity.java:~548-552`) `[F]`.

### Where the title comes from

- Sub-screen title: `setTitleFromIntent(Intent)` — `SettingsActivity.java:557-593` `[F]`.
  Priority: `EXTRA_SHOW_FRAGMENT_TITLE_RESID` (+ optional
  `EXTRA_SHOW_FRAGMENT_TITLE_RES_PACKAGE_NAME`, resolved via
  `createPackageContextAsUser`) → `EXTRA_SHOW_FRAGMENT_TITLE` →
  `getTitle()`. Constants at lines 135-141.
- Back-stack title (changes as you drill in): `setTitleFromBackStack()` /
  `setTitleFromBackStackEntry(FragmentManager.BackStackEntry)` —
  `SettingsActivity.java:597-624` `[F]`, driven by
  `FragmentManager.BackStackEntry.getBreadCrumbTitleRes()`/`getBreadCrumbTitle()`.
- The Activity title is rendered by the **ActionBar/Toolbar** installed in
  `SettingsBaseActivity` (line 161) — and for the homepage by the
  `app_bar_container` toolbar `[F for ids]`.
- **There is no subtitle anywhere.** `getActionBar().getSubtitle()` is never set by
  Settings `[F: no `setSubtitle` call in `SettingsActivity`/`SettingsBaseActivity`],
  so a Metro subtitle is new information, not a restyle.

---

## 3. Hook-point proposal — one reusable header on home + sub-screens

### 3.1 Recommended: patch `SettingsBaseActivity.setContentView(int)`

Why this method and not `SettingsActivity.onCreate`:

- It is the **only** method every Settings screen passes through
  (`SettingsBaseActivity.java:220-226`) — `SettingsActivity.createUiFromIntent`
  calls it at line 313, and sub-fragments that call `setContentView` themselves also
  land here `[F]`.
- It runs *after* the toolbar shell exists, so the header sits under the toolbar and
  above the fragment.
- One method → one patch for home+sub-screens. `SettingsActivity` and `SubSettings`
  need no change at all.

Insertion (concrete):

```java
// patch target: com.android.settings.core.SettingsBaseActivity#setContentView(int)  [line 220]
public void setContentView(@LayoutRes int layoutResID) {
    final ViewGroup parent = findViewById(R.id.content_frame);
    if (parent != null) { parent.removeAllViews(); }
    LayoutInflater.from(this).inflate(layoutResID, parent);
    MetroHeader.attach(this, parent);          // <-- ADD: idempotent, guarded by tag
}
```

`MetroHeader.attach(Activity a, ViewGroup contentFrame)`:

1. Bail if `contentFrame == null` or `contentFrame.findViewWithTag("metro_header") != null`.
2. Inflate the header **from the mod's own APK** (`createPackageContext` of the mod
   package, or a `LayoutInflater.cloneInContext` bound to the mod's resources) —
   this is what makes new drawables/fonts legal (see `10b` §3b).
3. `contentFrame.addView(header, 0)` — **index 0 of `R.id.content_frame`**, i.e.
   *above* `settings_main_prefs`, *below* `action_bar`.
   Do **not** insert into `settings_main_prefs` itself: its index 1 is
   `main_content` with `layout_weight=1`, and a fixed-height sibling there steals
   space from the scrolling fragment; index 0 of the host keeps the fragment's
   `match_parent` behaviour intact.
4. Height: 56dp (Metro "page header"), background `#0078D7`/`#F2F2F2`,
   title 24sp light, subtitle 12sp 70% opacity.
5. Re-attach on `onResume` (title can change via back stack) — or observe
   `Activity.getTitle()`; simplest is to refresh text in `attach()` and again from a
   patched `setTitle(CharSequence)` override on `SettingsBaseActivity`.

### 3.2 Title + subtitle sources

| field | source (exact) |
|---|---|
| title | `activity.getTitle()` — already the per-screen breadcrumb after `setTitleFromIntent`/`setTitleFromBackStack` (`SettingsActivity.java:557-624`) `[F]` |
| subtitle (sub-screen) | the fragment's breadcrumb: `getSupportFragmentManager().getBackStackEntryAt(count-1).getBreadCrumbTitle()`; fall back to package label of `intent.getStringExtra(EXTRA_SHOW_FRAGMENT)` `[E]` |
| subtitle (home) | static: "Settings" / device model — no API exists, must be supplied by the mod `[F: absence]` |
| optional right-side text | `SettingsActivity.mInitialTitle` is private `[F]`; use `getTitle()` only, do not reflect into it |

Practical: patch `SettingsBaseActivity.setTitle(CharSequence)` too (it is public,
inherited from `Activity`) and mirror into the header's title `TextView`; that keeps
the header correct when `setTitleFromBackStack` fires on drill-down.

### 3.3 Homepage activity (second, smaller patch)

`SettingsHomepageActivity` does **not** extend `SettingsBaseActivity` `[F]`, so the
hook above misses it. Add one call after line 252:

```java
setContentView(R.layout.settings_homepage_container);
MetroHeader.attachHomepage(this);   // <-- ADD
```

Insertion in `res/layout/settings_homepage_container.xml` `[F]`:

- Option A (scrolls with content, Metro-correct): insert at **index 0 of
  `@+id/homepage_container`** (the vertical `LinearLayout`), i.e. before
  `suggestion_content`. The whole page then scrolls under the Metro header.
- Option B (pinned): insert at index 0 of `@id/app_bar_container` — riskier, that
  container is the AppBar and owns the search bar's behaviour.

Recommend A. Subtitle here: static string, or the "Settings" label.

### 3.4 What NOT to patch

- `SettingsActivity.createUiFromIntent` (line 289) — misses `SettingsHomepageActivity`
  and misses screens that call `setContentView` from a fragment.
- `res/layout/settings_main_prefs.xml` via RRO — cannot add the header: new ids and
  new drawables do not exist in the target (`10b` §3). Restyling that file through an
  overlay *is* legal as long as you only reference existing target resources.
- `CollapsingToolbarLayout`/`ToolbarDelegate` — supported but only yields a
  title/subtitle in the toolbar; no room for a Metro block. Use
  `getToolbarDelegate().initCollapsingToolbar(...)` (`SettingsBaseActivity.java:146`)
  as the **zero-injection fallback** if patching is off the table: call
  `setSubtitle` on the toolbar from a hooked `setTitle`.

---

## 4. Extension points that exist (and why none of them is a header)

| seam | file | usable from an RRO? | gives a header? |
|---|---|---|---|
| `FeatureFactory` / `FeatureFactoryImpl` | `src/com/android/settings/overlay/FeatureFactory.kt`, `FeatureFactoryImpl.kt` | No — compile-time singleton via `config_featureFactory` `[F]` | No (dashboard/search providers only) |
| `TopLevelSettings` dashboard tiles | `res/layout/settings_homepage_container.xml` `@id/main_content`, `DashboardFragment` | No (code) | No — adds a scrollable tile |
| `FooterPreference` | `src/com/android/settings/widget/FooterPreference.kt` | Partly (style/strings) | No — bottom of screen |
| Collapsing toolbar + `ToolbarDelegate` | SettingsLib `com.android.settingslib.collapsingtoolbar.*`; `SettingsBaseActivity.java:140-172` | No (code) | Title/subtitle only |
| Search bar container | `SettingsHomepageActivity.java:370,410` (`search_action_bar`, `search_bar_title`) | No (code) | No |
| `<overlayable>` | — | **absent** in Settings `[F]` | — |

Conclusion: Settings has **no supported custom-header extension point**. The header
is a code hook (patched APK / LSPosed module) and must inflate its own resources
from the mod package. The RRO side of the Metro skin is limited to
dimens/colors/styles/drawables that already exist in the target.

---

## 5. Patch list (minimal, ordered)

1. `com.android.settings.core.SettingsBaseActivity#setContentView(int)` @ line 220 —
   add `MetroHeader.attach(this, parent)` after the inflate.
   → covers Settings home (`TopLevelSettings`) + **all** sub-screens (`SubSettings`).
2. `com.android.settings.core.SettingsBaseActivity#setTitle(CharSequence)` — mirror
   into the header (keeps breadcrumb updates in sync).
3. `com.android.settings.homepage.SettingsHomepageActivity#onCreate` @ line 252 —
   add `MetroHeader.attachHomepage(this)` after `setContentView`.
4. `res/layout/settings_homepage_container.xml` insertion index 0 of
   `@+id/homepage_container`; `R.id.content_frame` insertion index 0 for the rest.
5. Optional RRO companion: `qs_tile_background_shape`, `qs_corner_radius`,
   `shadeActive`/`shadeInactive`, `TextAppearance.QS.TileLabel`,
   `notification_corner_radius` (see `systemui-resource-map.json`).
