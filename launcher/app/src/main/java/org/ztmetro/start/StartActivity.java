package org.ztmetro.start;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * ztmetro Start v3: the HOME activity.
 *
 * Start has no page header and no pivot row. It is the WP tile field, edge to
 * edge at the token 14dp margin / 7dp gutter, with a right-edge circled arrow
 * and a left swipe into the app list. The app list is the only page with the
 * two-line header (caps + 48sp light "applications"), a circled search glyph
 * with a flat accent-underlined field, ztmetro hollow-square section heads,
 * the A-Z strip and a 56dp back/ZRN/search strip. Page changes are a horizontal
 * slide+fade of the whole page, not a Material cross-fade.
 */
public final class StartActivity extends Activity
        implements MetroGridView.Listener, AppListView.Listener, LiveTiles.Listener {

    private static final int PAGE_START = 0;
    private static final int PAGE_APPS = 1;

    private static final int ACTION_OPEN = -2;
    private static final int ACTION_UNPIN = -1;

    private final DecelerateInterpolator decelerate = new DecelerateInterpolator();

    private TileStore store;
    private List<TileStore.App> apps;
    private List<TileStore.Tile> tiles;
    private TileArt art;

    private int page = PAGE_START;

    private MetroPager pager;
    private FrameLayout startPage;
    private ScrollView startScroll;
    private MetroGridView grid;
    private LinearLayout appsPage;
    private EditText search;
    private TextView emptyView;
    private AppListView appList;
    private AlphabetIndexView alphabet;

    private int sidePad;
    private int pageGapMs;
    private int pageMs;

    private Object backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pageGapMs = getResources().getInteger(R.integer.metro_motion_page_gap_ms);
        pageMs = getResources().getInteger(R.integer.metro_motion_page_ms);
        sidePad = MetroUi.dimen(this, R.dimen.metro_page_margin);
        art = new TileArt(this);
        setupWindow();
        buildUi();

        store = new TileStore(this);
        refreshData();

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = Back33.register(this, new Runnable() {
                @Override
                public void run() {
                    handleBack();
                }
            });
        }
    }

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
    }

    private void buildUi() {
        pager = new MetroPager(this);
        pager.setBackgroundColor(Color.BLACK);
        pager.setListener(new MetroPager.Listener() {
            @Override
            public void onSwipeLeft() {
                if (page == PAGE_START) {
                    selectPage(PAGE_APPS, true);
                }
            }

            @Override
            public void onSwipeRight() {
                if (page == PAGE_APPS && !search.hasFocus()) {
                    selectPage(PAGE_START, true);
                }
            }
        });

        startPage = new FrameLayout(this);
        startPage.setBackgroundColor(Color.BLACK);
        startScroll = new ScrollView(this);
        startScroll.setFillViewport(true);
        startScroll.setVerticalScrollBarEnabled(false);
        startScroll.setClipToPadding(false);
        grid = new MetroGridView(this, art);
        grid.setListener(this);
        startScroll.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        startPage.addView(startScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int arrowPx = MetroUi.dimen(this, R.dimen.metro_v3_arrow);
        MetroCircleGlyphView arrow = new MetroCircleGlyphView(
                this, MetroCircleGlyphView.ARROW_RIGHT, true);
        arrow.setContentDescription(getString(R.string.cd_all_apps));
        arrow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPage(PAGE_APPS, true);
            }
        });
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
                arrowPx, arrowPx, Gravity.END | Gravity.CENTER_VERTICAL);
        arrowParams.rightMargin = sidePad;
        startPage.addView(arrow, arrowParams);

        buildAppsPage();

        pager.addView(startPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.addView(appsPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appsPage.setVisibility(View.GONE);

        setContentView(pager);
        pager.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                @SuppressWarnings("deprecation")
                int top = insets.getSystemWindowInsetTop();
                @SuppressWarnings("deprecation")
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 30) {
                    bottom = Math.max(bottom, Insets30.imeBottom(insets));
                }
                pager.setPadding(0, top, 0, bottom);
                return insets;
            }
        });
    }

    private void buildAppsPage() {
        appsPage = new LinearLayout(this);
        appsPage.setOrientation(LinearLayout.VERTICAL);
        appsPage.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(sidePad, MetroUi.dimen(this, R.dimen.metro_start_top_pad),
                sidePad, MetroUi.dimen(this, R.dimen.metro_v3_search_pad));

        TextView caps = new TextView(this);
        caps.setTextAppearance(R.style.TextAppearance_Metro_Caps);
        caps.setText(R.string.header_caps);
        caps.setLetterSpacing(0.08f);
        caps.setIncludeFontPadding(false);
        LinearLayout.LayoutParams capsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capsParams.bottomMargin = MetroUi.dimen(this, R.dimen.metro_header_gap);
        header.addView(caps, capsParams);

        TextView title = new TextView(this);
        title.setTextAppearance(R.style.TextAppearance_Metro_PageTitle);
        title.setText(R.string.page_apps);
        title.setIncludeFontPadding(false);
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appsPage.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int glyphPx = MetroUi.dimen(this, R.dimen.metro_v3_arrow);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(sidePad, 0, sidePad,
                MetroUi.dimen(this, R.dimen.metro_v3_search_pad));

        MetroCircleGlyphView searchGlyph = new MetroCircleGlyphView(
                this, MetroCircleGlyphView.SEARCH, true);
        searchGlyph.setContentDescription(getString(R.string.cd_search));
        searchGlyph.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                focusSearch();
            }
        });
        searchRow.addView(searchGlyph, new LinearLayout.LayoutParams(glyphPx, glyphPx));

        LinearLayout fieldWrap = new LinearLayout(this);
        fieldWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fieldWrapParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fieldWrapParams.leftMargin = MetroUi.dimen(this, R.dimen.metro_v3_search_pad);

        search = new EditText(this);
        search.setTextAppearance(R.style.TextAppearance_Metro_BodyLarge);
        search.setTextColor(getResources().getColor(R.color.metro_text_primary, getTheme()));
        search.setHintTextColor(getResources().getColor(R.color.metro_text_tertiary, getTheme()));
        search.setHint(R.string.search_hint);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setMinHeight(MetroUi.dimen(this, R.dimen.metro_min_touch_target));
        search.setBackground(null);
        search.setPadding(0, MetroUi.dimen(this, R.dimen.metro_v3_search_pad),
                0, MetroUi.dimen(this, R.dimen.metro_v3_search_pad));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                appList.setFilter(s == null ? "" : s.toString());
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard();
                    search.clearFocus();
                    return true;
                }
                return false;
            }
        });
        search.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (pager != null) {
                    pager.setSwipeEnabled(page == PAGE_START || !hasFocus);
                }
            }
        });
        fieldWrap.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View underline = new View(this);
        underline.setBackgroundColor(getResources().getColor(R.color.metro_accent, getTheme()));
        fieldWrap.addView(underline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                MetroUi.dimen(this, R.dimen.metro_v3_search_underline)));
        searchRow.addView(fieldWrap, fieldWrapParams);
        appsPage.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout listFrame = new FrameLayout(this);
        appList = new AppListView(this, art);
        appList.setListener(this);
        listFrame.addView(appList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        alphabet = new AlphabetIndexView(this);
        alphabet.setListener(new AlphabetIndexView.Listener() {
            @Override
            public void onLetter(String letter) {
                appList.jumpToLetter(letter);
            }
        });
        FrameLayout.LayoutParams indexParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END);
        listFrame.addView(alphabet, indexParams);

        emptyView = new TextView(this);
        emptyView.setTextAppearance(R.style.TextAppearance_Metro_Body);
        emptyView.setTextColor(getResources().getColor(R.color.metro_text_secondary, getTheme()));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText(R.string.no_results);
        emptyView.setVisibility(View.GONE);
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        appsPage.addView(listFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(sidePad, 0, sidePad, 0);
        bottom.setBackgroundColor(Color.BLACK);

        MetroCircleGlyphView back = new MetroCircleGlyphView(
                this, MetroCircleGlyphView.ARROW_LEFT, false);
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPage(PAGE_START, true);
            }
        });
        bottom.addView(back, new LinearLayout.LayoutParams(glyphPx, glyphPx));

        TextView brand = new TextView(this);
        brand.setTextAppearance(R.style.TextAppearance_Metro_Subtitle);
        brand.setTextColor(getResources().getColor(R.color.metro_text_primary, getTheme()));
        brand.setText(R.string.brand_short);
        brand.setGravity(Gravity.CENTER);
        brand.setIncludeFontPadding(false);
        bottom.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        MetroCircleGlyphView bottomSearch = new MetroCircleGlyphView(
                this, MetroCircleGlyphView.SEARCH, false);
        bottomSearch.setContentDescription(getString(R.string.cd_search));
        bottomSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                focusSearch();
            }
        });
        bottom.addView(bottomSearch, new LinearLayout.LayoutParams(glyphPx, glyphPx));
        appsPage.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                MetroUi.dimen(this, R.dimen.metro_v3_bottom_bar)));
    }

    private void refreshData() {
        apps = store.loadApps();
        store.seedIfNeeded(apps);
        tiles = store.loadTiles(apps);
        tiles.add(TileStore.appearanceTile(getString(R.string.appearance)));
        grid.setTiles(tiles);
        grid.refreshLive();
        if (appList != null) {
            appList.setApps(apps);
        }
    }

    private void refreshTiles() {
        tiles = store.loadTiles(apps);
        tiles.add(TileStore.appearanceTile(getString(R.string.appearance)));
        grid.setTiles(tiles);
        grid.refreshLive();
    }

    private View pageView(int which) {
        return which == PAGE_START ? startPage : appsPage;
    }

    private void selectPage(final int target, boolean animate) {
        if (target == page) {
            return;
        }
        final int from = page;
        page = target;
        pager.setSwipeEnabled(target == PAGE_START || search == null || !search.hasFocus());

        final View out = pageView(from);
        final View in = pageView(target);
        out.animate().cancel();
        in.animate().cancel();
        in.setVisibility(View.VISIBLE);

        if (target == PAGE_START) {
            hideKeyboard();
            if (search != null) {
                search.clearFocus();
                if (search.getText().length() > 0) {
                    search.setText("");
                }
            }
        }

        if (!animate) {
            out.setVisibility(View.GONE);
            out.setAlpha(1f);
            out.setTranslationX(0f);
            in.setTranslationX(0f);
            in.setAlpha(1f);
            return;
        }

        int width = pager.getWidth() > 0
                ? pager.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        final float direction = target > from ? 1f : -1f;
        in.setTranslationX(direction * width * 0.55f);
        in.setAlpha(0.08f);
        in.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(pageMs)
                .setInterpolator(decelerate)
                .start();
        out.animate()
                .translationX(-direction * width * 0.25f)
                .alpha(0f)
                .setDuration(pageGapMs)
                .setInterpolator(decelerate)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (page != from) {
                            out.setVisibility(View.GONE);
                        }
                        out.setAlpha(1f);
                        out.setTranslationX(0f);
                    }
                })
                .start();
    }

    private void hideKeyboard() {
        if (search == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void focusSearch() {
        selectPage(PAGE_APPS, true);
        search.post(new Runnable() {
            @Override
            public void run() {
                search.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    private void handleBack() {
        if (page == PAGE_APPS) {
            if (search != null && search.getText().length() > 0) {
                search.setText("");
                return;
            }
            if (search != null && search.hasFocus()) {
                hideKeyboard();
                search.clearFocus();
                return;
            }
            selectPage(PAGE_START, true);
        }
        // Back on Start does nothing: this is the home screen.
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (store != null) {
            refreshData();
        }
        selectPage(PAGE_START, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) {
            refreshData();
        }
        LiveTiles.register(this);
        grid.refreshLive();
        if (pager != null) {
            pager.setSwipeEnabled(page == PAGE_START || search == null || !search.hasFocus());
        }
    }

    @Override
    protected void onPause() {
        LiveTiles.unregister(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            Back33.unregister(this, backCallback);
            backCallback = null;
        }
        super.onDestroy();
    }

    private void launchApp(ComponentName component) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.app_missing, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            // Component became unexported/disabled between query and tap.
            Toast.makeText(this, R.string.app_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onTileTap(TileStore.Tile tile) {
        if (tile.appearance) {
            showAppearanceSheet();
        } else {
            launchApp(tile.component);
        }
    }

    @Override
    public void onTileLongPress(TileStore.Tile tile) {
        if (tile.appearance) {
            showAppearanceSheet();
            return;
        }
        final TileStore.Tile pressed = tile;
        ArrayList<String> actions = new ArrayList<>();
        final ArrayList<Integer> targets = new ArrayList<>();
        actions.add(getString(R.string.sheet_open));
        targets.add(ACTION_OPEN);
        if (pressed.size != TileStore.SIZE_SMALL) {
            actions.add(getString(R.string.sheet_make_small));
            targets.add(TileStore.SIZE_SMALL);
        }
        if (pressed.size != TileStore.SIZE_MEDIUM) {
            actions.add(getString(R.string.sheet_make_medium));
            targets.add(TileStore.SIZE_MEDIUM);
        }
        if (pressed.size != TileStore.SIZE_WIDE) {
            actions.add(getString(R.string.sheet_make_wide));
            targets.add(TileStore.SIZE_WIDE);
        }
        actions.add(getString(R.string.sheet_unpin));
        targets.add(ACTION_UNPIN);

        MetroSheet.show(this, pressed.label, actions.toArray(new String[0]),
                new MetroSheet.Listener() {
                    @Override
                    public void onAction(int index) {
                        int target = targets.get(index);
                        if (target == ACTION_OPEN) {
                            launchApp(pressed.component);
                        } else if (target == ACTION_UNPIN) {
                            store.unpin(pressed.component);
                            refreshTiles();
                            toast(R.string.unpinned);
                        } else {
                            store.setTileSize(pressed.component, target);
                            refreshTiles();
                        }
                    }
                });
    }

    /** Accent, live-tile state and a small Settings shortcut; no settings activity. */
    private void showAppearanceSheet() {
        boolean live = LiveTiles.isEnabled(this);
        String liveRow = getString(R.string.appearance_live_row,
                getString(live ? R.string.on : R.string.off));
        MetroSheet.show(this, getString(R.string.appearance),
                new String[]{liveRow, getString(R.string.appearance_system_settings)},
                new MetroSheet.Listener() {
                    @Override
                    public void onAction(int index) {
                        if (index == 0) {
                            openSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                        } else {
                            openSettings(new Intent(Settings.ACTION_SETTINGS));
                        }
                    }
                });
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.settings_missing);
        }
    }

    @Override
    public void onAppTap(TileStore.App app) {
        launchApp(app.component);
    }

    @Override
    public void onAppLongPress(TileStore.App app) {
        final TileStore.App pressed = app;
        ArrayList<String> actions = new ArrayList<>();
        final ArrayList<Integer> targets = new ArrayList<>();
        actions.add(getString(R.string.sheet_open));
        targets.add(ACTION_OPEN);
        if (store.isPinned(pressed.component)) {
            actions.add(getString(R.string.sheet_unpin));
            targets.add(ACTION_UNPIN);
        } else {
            actions.add(getString(R.string.sheet_pin_small));
            targets.add(TileStore.SIZE_SMALL);
            actions.add(getString(R.string.sheet_pin_medium));
            targets.add(TileStore.SIZE_MEDIUM);
            actions.add(getString(R.string.sheet_pin_wide));
            targets.add(TileStore.SIZE_WIDE);
        }
        MetroSheet.show(this, pressed.label, actions.toArray(new String[0]),
                new MetroSheet.Listener() {
                    @Override
                    public void onAction(int index) {
                        int target = targets.get(index);
                        if (target == ACTION_OPEN) {
                            launchApp(pressed.component);
                        } else if (target == ACTION_UNPIN) {
                            store.unpin(pressed.component);
                            refreshTiles();
                            toast(R.string.unpinned);
                        } else {
                            store.pin(pressed.component, target);
                            refreshTiles();
                            toast(R.string.pinned);
                        }
                    }
                });
    }

    @Override
    public void onSectionsChanged(List<String> letters) {
        if (alphabet != null) {
            alphabet.setLetters(letters);
        }
        if (emptyView != null) {
            emptyView.setVisibility(letters.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onLiveTilesChanged() {
        if (grid != null) {
            grid.refreshLive();
        }
    }

    /** API 30+ IME inset, isolated so API 29 never resolves WindowInsets.Type. */
    private static final class Insets30 {
        static int imeBottom(WindowInsets insets) {
            return insets.getInsets(WindowInsets.Type.ime()).bottom;
        }
    }

    /** API 33+ back handling, isolated so API 29-32 never resolve these classes. */
    private static final class Back33 {
        static Object register(Activity activity, final Runnable onBack) {
            android.window.OnBackInvokedCallback callback =
                    new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            onBack.run();
                        }
                    };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(Activity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) callback);
        }
    }
}
