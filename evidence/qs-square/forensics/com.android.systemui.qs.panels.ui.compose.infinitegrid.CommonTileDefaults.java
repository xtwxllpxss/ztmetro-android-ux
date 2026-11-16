package com.android.systemui.qs.panels.ui.compose.infinitegrid;

import androidx.compose.runtime.Composer;
import androidx.compose.runtime.ComposerKt;
import androidx.compose.ui.res.StringResources_androidKt;
import androidx.compose.ui.unit.Dp;
import com.android.wm.shell.R;

/* compiled from: go/retraceme 3f68c89d24d67735205e864b676482151f2126b7bc50ec77002e496c2ce25eed */
/* loaded from: classes2.dex */
public abstract class CommonTileDefaults {
    public static final float IconSize;
    public static final float InactiveCornerRadius;
    public static final float LargeTileIconSize = Dp.m739constructorimpl(28);
    public static final float SideIconHeight = Dp.m739constructorimpl(20);
    public static final float SideIconWidth;
    public static final float TileArrangementPadding;
    public static final float TileDualTargetEndPadding;
    public static final float TileEndPadding;
    public static final float TileHeight;
    public static final float TileLabelBlurWidth;
    public static final float TileStartPadding;
    public static final float ToggleTargetSize;

    static {
        float f = 32;
        IconSize = Dp.m739constructorimpl(f);
        SideIconWidth = Dp.m739constructorimpl(f);
        Dp.m739constructorimpl(14);
        ToggleTargetSize = Dp.m739constructorimpl(56);
        TileHeight = Dp.m739constructorimpl(72);
        float f2 = 8;
        TileStartPadding = Dp.m739constructorimpl(f2);
        TileEndPadding = Dp.m739constructorimpl(12);
        TileDualTargetEndPadding = Dp.m739constructorimpl(f2);
        TileArrangementPadding = Dp.m739constructorimpl(6);
        InactiveCornerRadius = Dp.m739constructorimpl(50);
        TileLabelBlurWidth = Dp.m739constructorimpl(f);
    }

    public static String longPressLabelSettings(Composer composer) {
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings (CommonTile.kt:430)");
        }
        String stringResource = StringResources_androidKt.stringResource(composer, R.string.accessibility_long_click_tile);
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventEnd();
        }
        return stringResource;
    }
}
