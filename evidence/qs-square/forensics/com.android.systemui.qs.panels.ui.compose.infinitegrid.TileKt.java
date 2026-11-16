package com.android.systemui.qs.panels.ui.compose.infinitegrid;

import android.content.Context;
import androidx.compose.foundation.layout.Arrangement;
import androidx.compose.foundation.layout.PaddingValuesImpl;
import androidx.compose.foundation.lazy.grid.GridCells$Fixed;
import androidx.compose.foundation.lazy.grid.LazyGridDslKt;
import androidx.compose.foundation.lazy.grid.LazyGridState;
import androidx.compose.runtime.Composer;
import androidx.compose.runtime.ComposerKt;
import androidx.compose.runtime.ScopeUpdateScope;
import androidx.compose.runtime.internal.ComposableLambdaKt;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.ClipKt;
import androidx.compose.ui.graphics.Shape;
import androidx.compose.ui.layout.LookaheadScopeKt;
import com.android.compose.animation.ExpandableControllerImpl;
import com.android.compose.animation.ExpandableControllerKt;
import com.android.compose.animation.ExpandableKt;
import com.android.systemui.common.shared.model.ContentDescription;
import com.android.systemui.common.shared.model.Icon;
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider;
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.wm.shell.R;
import java.lang.invoke.VarHandle;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function3;
import lineageos.hardware.LineageHardwareManager;

/* compiled from: go/retraceme 3f68c89d24d67735205e864b676482151f2126b7bc50ec77002e496c2ce25eed */
/* loaded from: classes2.dex */
public abstract class TileKt {
    /* JADX WARN: Code restructure failed: missing block: B:33:0x00f6, code lost:
    
        if (r6 == androidx.compose.runtime.Composer.Companion.getEmpty()) goto L41;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static final void LargeStaticTile(com.android.systemui.qs.panels.ui.viewmodel.TileUiState r20, com.android.systemui.qs.panels.ui.viewmodel.IconProvider r21, androidx.compose.ui.Modifier r22, androidx.compose.runtime.Composer r23, int r24) {
        /*
            Method dump skipped, instructions count: 355
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt.LargeStaticTile(com.android.systemui.qs.panels.ui.viewmodel.TileUiState, com.android.systemui.qs.panels.ui.viewmodel.IconProvider, androidx.compose.ui.Modifier, androidx.compose.runtime.Composer, int):void");
    }

    /* JADX WARN: Code restructure failed: missing block: B:122:0x0224, code lost:
    
        if (r3 == androidx.compose.runtime.Composer.Companion.getEmpty()) goto L145;
     */
    /* JADX WARN: Code restructure failed: missing block: B:129:0x02c4, code lost:
    
        if (r5 == androidx.compose.runtime.Composer.Companion.getEmpty()) goto L154;
     */
    /* JADX WARN: Finally extract failed */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:115:0x020c  */
    /* JADX WARN: Removed duplicated region for block: B:118:0x0214  */
    /* JADX WARN: Removed duplicated region for block: B:121:0x021e A[Catch: all -> 0x03b4, TryCatch #0 {all -> 0x03b4, blocks: (B:88:0x0149, B:90:0x0153, B:91:0x0158, B:93:0x016b, B:94:0x016e, B:97:0x0184, B:99:0x0190, B:101:0x01a1, B:104:0x01bb, B:107:0x01ca, B:109:0x01d0, B:112:0x01dd, B:113:0x01e6, B:119:0x0217, B:121:0x021e, B:123:0x0236, B:125:0x0275, B:126:0x02b4, B:128:0x02be, B:130:0x02d3, B:132:0x02e8, B:133:0x02ed, B:135:0x02fb, B:136:0x02fe, B:138:0x031d, B:139:0x0355, B:151:0x02c6, B:152:0x02a5, B:153:0x0226, B:159:0x0198), top: B:87:0x0149 }] */
    /* JADX WARN: Removed duplicated region for block: B:125:0x0275 A[Catch: all -> 0x03b4, TryCatch #0 {all -> 0x03b4, blocks: (B:88:0x0149, B:90:0x0153, B:91:0x0158, B:93:0x016b, B:94:0x016e, B:97:0x0184, B:99:0x0190, B:101:0x01a1, B:104:0x01bb, B:107:0x01ca, B:109:0x01d0, B:112:0x01dd, B:113:0x01e6, B:119:0x0217, B:121:0x021e, B:123:0x0236, B:125:0x0275, B:126:0x02b4, B:128:0x02be, B:130:0x02d3, B:132:0x02e8, B:133:0x02ed, B:135:0x02fb, B:136:0x02fe, B:138:0x031d, B:139:0x0355, B:151:0x02c6, B:152:0x02a5, B:153:0x0226, B:159:0x0198), top: B:87:0x0149 }] */
    /* JADX WARN: Removed duplicated region for block: B:128:0x02be A[Catch: all -> 0x03b4, TryCatch #0 {all -> 0x03b4, blocks: (B:88:0x0149, B:90:0x0153, B:91:0x0158, B:93:0x016b, B:94:0x016e, B:97:0x0184, B:99:0x0190, B:101:0x01a1, B:104:0x01bb, B:107:0x01ca, B:109:0x01d0, B:112:0x01dd, B:113:0x01e6, B:119:0x0217, B:121:0x021e, B:123:0x0236, B:125:0x0275, B:126:0x02b4, B:128:0x02be, B:130:0x02d3, B:132:0x02e8, B:133:0x02ed, B:135:0x02fb, B:136:0x02fe, B:138:0x031d, B:139:0x0355, B:151:0x02c6, B:152:0x02a5, B:153:0x0226, B:159:0x0198), top: B:87:0x0149 }] */
    /* JADX WARN: Removed duplicated region for block: B:132:0x02e8 A[Catch: all -> 0x03b4, TryCatch #0 {all -> 0x03b4, blocks: (B:88:0x0149, B:90:0x0153, B:91:0x0158, B:93:0x016b, B:94:0x016e, B:97:0x0184, B:99:0x0190, B:101:0x01a1, B:104:0x01bb, B:107:0x01ca, B:109:0x01d0, B:112:0x01dd, B:113:0x01e6, B:119:0x0217, B:121:0x021e, B:123:0x0236, B:125:0x0275, B:126:0x02b4, B:128:0x02be, B:130:0x02d3, B:132:0x02e8, B:133:0x02ed, B:135:0x02fb, B:136:0x02fe, B:138:0x031d, B:139:0x0355, B:151:0x02c6, B:152:0x02a5, B:153:0x0226, B:159:0x0198), top: B:87:0x0149 }] */
    /* JADX WARN: Removed duplicated region for block: B:135:0x02fb A[Catch: all -> 0x03b4, TryCatch #0 {all -> 0x03b4, blocks: (B:88:0x0149, B:90:0x0153, B:91:0x0158, B:93:0x016b, B:94:0x016e, B:97:0x0184, B:99:0x0190, B:101:0x01a1, B:104:0x01bb, B:107:0x01ca, B:109:0x01d0, B:112:0x01dd, B:113:0x01e6, B:119:0x0217, B:121:0x021e, B:123:0x0236, B:125:0x0275, B:126:0x02b4, B:128:0x02be, B:130:0x02d3, B:132:0x02e8, B:133:0x02ed, B:135:0x02fb, B:136:0x02fe, B:138:0x031d, B:139:0x0355, B:151:0x02c6, B:152:0x02a5, B:153:0x0226, B:159:0x0198), top: B:87:0x0149 }] */
    /* JADX WARN: Removed duplicated region for block: B:138:0x031d A[Catch: all -> 0x03b4, TryCatch #0 {all -> 0x03b4, blocks: (B:88:0x0149, B:90:0x0153, B:91:0x0158, B:93:0x016b, B:94:0x016e, B:97:0x0184, B:99:0x0190, B:101:0x01a1, B:104:0x01bb, B:107:0x01ca, B:109:0x01d0, B:112:0x01dd, B:113:0x01e6, B:119:0x0217, B:121:0x021e, B:123:0x0236, B:125:0x0275, B:126:0x02b4, B:128:0x02be, B:130:0x02d3, B:132:0x02e8, B:133:0x02ed, B:135:0x02fb, B:136:0x02fe, B:138:0x031d, B:139:0x0355, B:151:0x02c6, B:152:0x02a5, B:153:0x0226, B:159:0x0198), top: B:87:0x0149 }] */
    /* JADX WARN: Removed duplicated region for block: B:142:0x03ab  */
    /* JADX WARN: Removed duplicated region for block: B:152:0x02a5 A[Catch: all -> 0x03b4, TryCatch #0 {all -> 0x03b4, blocks: (B:88:0x0149, B:90:0x0153, B:91:0x0158, B:93:0x016b, B:94:0x016e, B:97:0x0184, B:99:0x0190, B:101:0x01a1, B:104:0x01bb, B:107:0x01ca, B:109:0x01d0, B:112:0x01dd, B:113:0x01e6, B:119:0x0217, B:121:0x021e, B:123:0x0236, B:125:0x0275, B:126:0x02b4, B:128:0x02be, B:130:0x02d3, B:132:0x02e8, B:133:0x02ed, B:135:0x02fb, B:136:0x02fe, B:138:0x031d, B:139:0x0355, B:151:0x02c6, B:152:0x02a5, B:153:0x0226, B:159:0x0198), top: B:87:0x0149 }] */
    /* JADX WARN: Removed duplicated region for block: B:154:0x0216  */
    /* JADX WARN: Removed duplicated region for block: B:155:0x0210  */
    /* JADX WARN: Type inference failed for: r0v44, types: [androidx.compose.ui.Modifier] */
    /* JADX WARN: Type inference failed for: r5v33, types: [androidx.compose.ui.Modifier] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static final void Tile(com.android.compose.animation.scene.ContentScope r29, final com.android.systemui.qs.panels.ui.viewmodel.TileViewModel r30, final boolean r31, kotlin.jvm.functions.Function0 r32, final kotlinx.coroutines.CoroutineScope r33, com.android.systemui.qs.panels.ui.compose.BounceableInfo r34, com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider r35, final androidx.compose.foundation.interaction.MutableInteractionSource r36, androidx.compose.ui.Modifier r37, final kotlin.jvm.functions.Function0 r38, kotlin.jvm.functions.Function1 r39, final com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel r40, boolean r41, androidx.compose.runtime.Composer r42, int r43, int r44, int r45) {
        /*
            Method dump skipped, instructions count: 1029
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt.Tile(com.android.compose.animation.scene.ContentScope, com.android.systemui.qs.panels.ui.viewmodel.TileViewModel, boolean, kotlin.jvm.functions.Function0, kotlinx.coroutines.CoroutineScope, com.android.systemui.qs.panels.ui.compose.BounceableInfo, com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider, androidx.compose.foundation.interaction.MutableInteractionSource, androidx.compose.ui.Modifier, kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function1, com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel, boolean, androidx.compose.runtime.Composer, int, int, int):void");
    }

    /* JADX WARN: Code restructure failed: missing block: B:66:0x017e, code lost:
    
        if (r3 == androidx.compose.runtime.Composer.Companion.getEmpty()) goto L87;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static final void TileContainer(kotlin.jvm.functions.Function0 r21, kotlin.jvm.functions.Function0 r22, com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState r23, boolean r24, boolean r25, androidx.compose.foundation.interaction.MutableInteractionSource r26, androidx.compose.ui.Modifier r27, kotlin.jvm.functions.Function3 r28, androidx.compose.runtime.Composer r29, int r30) {
        /*
            Method dump skipped, instructions count: 611
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt.TileContainer(kotlin.jvm.functions.Function0, kotlin.jvm.functions.Function0, com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState, boolean, boolean, androidx.compose.foundation.interaction.MutableInteractionSource, androidx.compose.ui.Modifier, kotlin.jvm.functions.Function3, androidx.compose.runtime.Composer, int):void");
    }

    public static final void TileExpandable(Function0 function0, Shape shape, Function0 function02, TileHapticsViewModel tileHapticsViewModel, Modifier modifier, Function3 function3, Composer composer, int i) {
        int i2;
        Composer startRestartGroup = composer.startRestartGroup(222516676);
        if ((i & 6) == 0) {
            i2 = (startRestartGroup.changedInstance(function0) ? 4 : 2) | i;
        } else {
            i2 = i;
        }
        if ((i & 48) == 0) {
            i2 |= startRestartGroup.changed(shape) ? 32 : 16;
        }
        if ((i & 384) == 0) {
            i2 |= startRestartGroup.changedInstance(function02) ? LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT : 128;
        }
        if ((i & 3072) == 0) {
            i2 |= startRestartGroup.changedInstance(tileHapticsViewModel) ? LineageHardwareManager.FEATURE_TOUCH_HOVERING : LineageHardwareManager.FEATURE_VIBRATOR;
        }
        if ((i & 24576) == 0) {
            i2 |= startRestartGroup.changed(modifier) ? LineageHardwareManager.FEATURE_READING_ENHANCEMENT : LineageHardwareManager.FEATURE_DISPLAY_MODES;
        }
        if ((196608 & i) == 0) {
            i2 |= startRestartGroup.changedInstance(function3) ? LineageHardwareManager.FEATURE_COLOR_BALANCE : 65536;
        }
        int i3 = 0;
        if (startRestartGroup.shouldExecute((74899 & i2) != 74898, i2 & 1)) {
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileExpandable (Tile.kt:356)");
            }
            ExpandableControllerImpl m819rememberExpandableControllerT042LqI = ExpandableControllerKt.m819rememberExpandableControllerT042LqI(function0, shape, 0L, null, null, startRestartGroup, i2 & 126, 28);
            Modifier clip = ClipKt.clip(modifier, shape);
            SquishTileKt$$ExternalSyntheticLambda0 squishTileKt$$ExternalSyntheticLambda0 = new SquishTileKt$$ExternalSyntheticLambda0(i3);
            squishTileKt$$ExternalSyntheticLambda0.f$0 = function02;
            VarHandle.storeStoreFence();
            SquishTileKt$$ExternalSyntheticLambda1 squishTileKt$$ExternalSyntheticLambda1 = new SquishTileKt$$ExternalSyntheticLambda1();
            squishTileKt$$ExternalSyntheticLambda1.f$0 = function02;
            VarHandle.storeStoreFence();
            Modifier approachLayout$default = LookaheadScopeKt.approachLayout$default(clip, squishTileKt$$ExternalSyntheticLambda0, squishTileKt$$ExternalSyntheticLambda1);
            TileKt$$ExternalSyntheticLambda7 tileKt$$ExternalSyntheticLambda7 = new TileKt$$ExternalSyntheticLambda7();
            tileKt$$ExternalSyntheticLambda7.f$0 = function3;
            tileKt$$ExternalSyntheticLambda7.f$1 = tileHapticsViewModel;
            VarHandle.storeStoreFence();
            ExpandableKt.Expandable(m819rememberExpandableControllerT042LqI, approachLayout$default, null, null, null, true, false, ComposableLambdaKt.rememberComposableLambda(-427646088, true, tileKt$$ExternalSyntheticLambda7, startRestartGroup, 54), startRestartGroup, 12779520, 92);
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventEnd();
            }
        } else {
            startRestartGroup.skipToGroupEnd();
        }
        ScopeUpdateScope endRestartGroup = startRestartGroup.endRestartGroup();
        if (endRestartGroup != null) {
            TileKt$$ExternalSyntheticLambda8 tileKt$$ExternalSyntheticLambda8 = new TileKt$$ExternalSyntheticLambda8();
            tileKt$$ExternalSyntheticLambda8.f$0 = function0;
            tileKt$$ExternalSyntheticLambda8.f$1 = shape;
            tileKt$$ExternalSyntheticLambda8.f$2 = function02;
            tileKt$$ExternalSyntheticLambda8.f$3 = tileHapticsViewModel;
            tileKt$$ExternalSyntheticLambda8.f$4 = modifier;
            tileKt$$ExternalSyntheticLambda8.f$5 = function3;
            tileKt$$ExternalSyntheticLambda8.f$6 = i;
            VarHandle.storeStoreFence();
            endRestartGroup.updateScope(tileKt$$ExternalSyntheticLambda8);
        }
    }

    public static final void TileLazyGrid(GridCells$Fixed gridCells$Fixed, Modifier modifier, LazyGridState lazyGridState, PaddingValuesImpl paddingValuesImpl, Function1 function1, Composer composer, int i) {
        Composer startRestartGroup = composer.startRestartGroup(1231368701);
        int i2 = i | (startRestartGroup.changed(gridCells$Fixed) ? 4 : 2) | (startRestartGroup.changed(modifier) ? 32 : 16) | (startRestartGroup.changed(lazyGridState) ? LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT : 128) | (startRestartGroup.changedInstance(function1) ? LineageHardwareManager.FEATURE_READING_ENHANCEMENT : LineageHardwareManager.FEATURE_DISPLAY_MODES);
        if (startRestartGroup.shouldExecute((i2 & 9363) != 9362, i2 & 1)) {
            startRestartGroup.startDefaults();
            if ((i & 1) != 0 && !startRestartGroup.getDefaultsInvalid()) {
                startRestartGroup.skipToGroupEnd();
            }
            startRestartGroup.endDefaults();
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventStart("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileLazyGrid (Tile.kt:118)");
            }
            Arrangement arrangement = Arrangement.INSTANCE;
            float f = CommonTileDefaults.TileArrangementPadding;
            arrangement.getClass();
            LazyGridDslKt.LazyVerticalGrid(gridCells$Fixed, modifier, lazyGridState, paddingValuesImpl, Arrangement.m96spacedBy0680j_4(f), Arrangement.m96spacedBy0680j_4(f), null, false, null, function1, startRestartGroup, (i2 & 14) | 1769472 | (i2 & 112) | (i2 & 896) | 3072, (i2 >> 12) & 14, 912);
            if (ComposerKt.isTraceInProgress()) {
                ComposerKt.traceEventEnd();
            }
        } else {
            startRestartGroup.skipToGroupEnd();
        }
        ScopeUpdateScope endRestartGroup = startRestartGroup.endRestartGroup();
        if (endRestartGroup != null) {
            TileKt$$ExternalSyntheticLambda15 tileKt$$ExternalSyntheticLambda15 = new TileKt$$ExternalSyntheticLambda15();
            tileKt$$ExternalSyntheticLambda15.f$0 = gridCells$Fixed;
            tileKt$$ExternalSyntheticLambda15.f$1 = modifier;
            tileKt$$ExternalSyntheticLambda15.f$2 = lazyGridState;
            tileKt$$ExternalSyntheticLambda15.f$3 = paddingValuesImpl;
            tileKt$$ExternalSyntheticLambda15.f$4 = function1;
            VarHandle.storeStoreFence();
            endRestartGroup.updateScope(tileKt$$ExternalSyntheticLambda15);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public static final Icon getTileIcon(Context context, IconProvider iconProvider) {
        QSTile.Icon icon = iconProvider.getIcon();
        ContentDescription contentDescription = null;
        Object[] objArr = 0;
        if (icon == null) {
            return new Icon.Resource(R.drawable.ic_error_outline, null);
        }
        if (icon instanceof QSTileImpl.ResourceIcon) {
            return new Icon.Resource(((QSTileImpl.ResourceIcon) icon).mResId, null);
        }
        return new Icon.Loaded(icon.getDrawable(context), contentDescription, (Integer) (objArr == true ? 1 : 0), 12);
    }

    public static final String getTraceName(TileViewModel tileViewModel) {
        String obj = tileViewModel.spec.toString();
        int length = obj.length();
        return obj.substring(length - (127 > length ? length : 127));
    }
}
