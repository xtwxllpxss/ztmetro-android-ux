package com.android.systemui.qs.panels.ui.compose.infinitegrid;

import androidx.compose.animation.core.AnimateAsStateKt;
import androidx.compose.foundation.shape.RoundedCornerShape;
import androidx.compose.foundation.shape.RoundedCornerShapeKt;
import androidx.compose.material3.ColorScheme;
import androidx.compose.material3.ColorSchemeKt;
import androidx.compose.material3.MaterialTheme;
import androidx.compose.runtime.Composer;
import androidx.compose.runtime.ComposerKt;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import androidx.compose.runtime.State;
import androidx.compose.runtime.StaticProvidableCompositionLocal;
import androidx.compose.ui.graphics.Color;
import androidx.compose.ui.graphics.ColorKt;
import androidx.compose.ui.unit.Dp;
import com.android.compose.theme.AndroidColorScheme;
import com.android.compose.theme.AndroidColorSchemeKt;
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState;
import java.lang.invoke.VarHandle;

/* compiled from: go/retraceme 3f68c89d24d67735205e864b676482151f2126b7bc50ec77002e496c2ce25eed */
/* loaded from: classes2.dex */
public abstract class TileDefaults {
    public static final float ActiveIconCornerRadius = Dp.m739constructorimpl(16);
    public static final float ActiveTileCornerRadius = Dp.m739constructorimpl(24);

    /* renamed from: animateShapeAsState-rAjV9yQ, reason: not valid java name */
    public static MutableState m2529animateShapeAsStaterAjV9yQ(int i, float f, String str, Composer composer, int i2) {
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.animateShapeAsState (Tile.kt:591)");
        }
        if (i != 2) {
            f = CommonTileDefaults.InactiveCornerRadius;
        }
        State m12animateDpAsStateAjpBEmI = AnimateAsStateKt.m12animateDpAsStateAjpBEmI(f, null, str, composer, 384, 10);
        Object rememberedValue = composer.rememberedValue();
        if (rememberedValue == Composer.Companion.getEmpty()) {
            TileDefaults$animateShapeAsState$1$corner$1 tileDefaults$animateShapeAsState$1$corner$1 = new TileDefaults$animateShapeAsState$1$corner$1();
            tileDefaults$animateShapeAsState$1$corner$1.$animatedCornerRadius$delegate = m12animateDpAsStateAjpBEmI;
            VarHandle.storeStoreFence();
            RoundedCornerShape roundedCornerShape = RoundedCornerShapeKt.CircleShape;
            rememberedValue = SnapshotStateKt.mutableStateOf$default(new RoundedCornerShape(tileDefaults$animateShapeAsState$1$corner$1, tileDefaults$animateShapeAsState$1$corner$1, tileDefaults$animateShapeAsState$1$corner$1, tileDefaults$animateShapeAsState$1$corner$1));
            composer.updateRememberedValue(rememberedValue);
        }
        MutableState mutableState = (MutableState) rememberedValue;
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventEnd();
        }
        return mutableState;
    }

    public static MutableState animateTileShapeAsState(Composer composer, int i) {
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.animateTileShapeAsState (Tile.kt:578)");
        }
        MutableState m2529animateShapeAsStaterAjV9yQ = m2529animateShapeAsStaterAjV9yQ(i, ActiveTileCornerRadius, "QSTileIconCornerRadius", composer, 3456);
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventEnd();
        }
        return m2529animateShapeAsStaterAjV9yQ;
    }

    public static TileColors getColorForState(TileUiState tileUiState, boolean z, Composer composer, int i) {
        TileColors tileColors;
        TileColors tileColors2;
        TileColors tileColors3;
        long Color;
        long Color2;
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.getColorForState (Tile.kt:546)");
        }
        int i2 = tileUiState.state;
        boolean z2 = tileUiState.handlesSecondaryClick;
        if (i2 == 1) {
            composer.startReplaceGroup(1236335598);
            if (!z2 || z) {
                composer.startReplaceGroup(1236471626);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.inactiveTileColors (Tile.kt:522)");
                }
                tileColors = new TileColors(((AndroidColorScheme) composer.consume(AndroidColorSchemeKt.LocalAndroidColorScheme)).surfaceEffect1, Color.Transparent, MaterialTheme.getColorScheme(composer).onSurface, MaterialTheme.getColorScheme(composer).onSurface, MaterialTheme.getColorScheme(composer).onSurface);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventEnd();
                }
                composer.endReplaceGroup();
            } else {
                composer.startReplaceGroup(1236396544);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.inactiveDualTargetTileColors (Tile.kt:511)");
                }
                StaticProvidableCompositionLocal staticProvidableCompositionLocal = AndroidColorSchemeKt.LocalAndroidColorScheme;
                tileColors = new TileColors(((AndroidColorScheme) composer.consume(staticProvidableCompositionLocal)).surfaceEffect1, ((AndroidColorScheme) composer.consume(staticProvidableCompositionLocal)).surfaceEffect2, MaterialTheme.getColorScheme(composer).onSurface, MaterialTheme.getColorScheme(composer).onSurface, MaterialTheme.getColorScheme(composer).onSurface);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventEnd();
                }
                composer.endReplaceGroup();
            }
            tileColors2 = tileColors;
            composer.endReplaceGroup();
        } else if (i2 != 2) {
            composer.startReplaceGroup(-1484131437);
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.unavailableTileColors (Tile.kt:532)");
            }
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventStart("androidx.compose.material3.MaterialTheme.<get-colorScheme> (MaterialTheme.kt:122)");
            }
            StaticProvidableCompositionLocal staticProvidableCompositionLocal2 = ColorSchemeKt.LocalColorScheme;
            ColorScheme colorScheme = (ColorScheme) composer.consume(staticProvidableCompositionLocal2);
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventEnd();
            }
            Color = ColorKt.Color(Color.m441getRedimpl(r3), Color.m440getGreenimpl(r3), Color.m438getBlueimpl(r3), 0.18f, Color.m439getColorSpaceimpl(colorScheme.surface));
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventStart("androidx.compose.material3.MaterialTheme.<get-colorScheme> (MaterialTheme.kt:122)");
            }
            ColorScheme colorScheme2 = (ColorScheme) composer.consume(staticProvidableCompositionLocal2);
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventEnd();
            }
            Color2 = ColorKt.Color(Color.m441getRedimpl(r2), Color.m440getGreenimpl(r2), Color.m438getBlueimpl(r2), 0.38f, Color.m439getColorSpaceimpl(colorScheme2.onSurfaceVariant));
            tileColors2 = new TileColors(Color, Color, Color2, Color2, Color2);
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventEnd();
            }
            composer.endReplaceGroup();
        } else {
            composer.startReplaceGroup(1236093426);
            if (!z2 || z) {
                composer.startReplaceGroup(1236227532);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.activeTileColors (Tile.kt:488)");
                }
                tileColors3 = new TileColors(MaterialTheme.getColorScheme(composer).primary, MaterialTheme.getColorScheme(composer).primary, MaterialTheme.getColorScheme(composer).onPrimary, MaterialTheme.getColorScheme(composer).onPrimary, MaterialTheme.getColorScheme(composer).onPrimary);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventEnd();
                }
                composer.endReplaceGroup();
            } else {
                composer.startReplaceGroup(1236154434);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.activeDualTargetTileColors (Tile.kt:500)");
                }
                tileColors3 = new TileColors(((AndroidColorScheme) composer.consume(AndroidColorSchemeKt.LocalAndroidColorScheme)).surfaceEffect1, MaterialTheme.getColorScheme(composer).primary, MaterialTheme.getColorScheme(composer).onSurface, MaterialTheme.getColorScheme(composer).onSurface, MaterialTheme.getColorScheme(composer).onPrimary);
                if (ComposerKt.isTraceInProgress()) {
                    ComposerKt.traceEventEnd();
                }
                composer.endReplaceGroup();
            }
            tileColors2 = tileColors3;
            composer.endReplaceGroup();
        }
        if (ComposerKt.isTraceInProgress()) {
            ComposerKt.traceEventEnd();
        }
        return tileColors2;
    }
}
