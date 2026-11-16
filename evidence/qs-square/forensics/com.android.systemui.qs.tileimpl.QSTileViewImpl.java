package com.android.systemui.qs.tileimpl;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Trace;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.compose.runtime.collection.MutableVectorKt$$ExternalSyntheticOutline0;
import androidx.compose.ui.platform.AndroidCompositionLocals_androidKt$$ExternalSyntheticOutline0;
import com.android.settingslib.Utils;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.animation.Expandable$Companion$fromView$1;
import com.android.systemui.animation.LaunchableView;
import com.android.systemui.animation.LaunchableViewDelegate;
import com.android.systemui.assist.ui.InvocationLightsView$$ExternalSyntheticOutline0;
import com.android.systemui.haptics.qs.QSLongPressEffect;
import com.android.systemui.haptics.qs.QSLongPressEffect$createExpandableFromView$1;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.logging.QSLogger;
import com.android.wm.shell.R;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.List;
import kotlin.jvm.internal.Reflection;
import kotlin.text.StringsKt;

/* compiled from: go/retraceme 3f68c89d24d67735205e864b676482151f2126b7bc50ec77002e496c2ce25eed */
/* loaded from: classes2.dex */
public class QSTileViewImpl extends QSTileView implements LaunchableView {
    public static final Rect EMPTY_RECT = new Rect();
    public String accessibilityClass;
    public Drawable backgroundBaseDrawable;
    public int backgroundColor;
    public LayerDrawable backgroundDrawable;
    public int backgroundOverlayColor;
    public Drawable backgroundOverlayDrawable;
    public final ImageView chevronView;
    public final int colorActive;
    public final ArgbEvaluator colorEvaluator;
    public final int colorInactive;
    public final int colorLabelActive;
    public final int colorLabelInactive;
    public final int colorLabelUnavailable;
    public final int colorSecondaryLabelActive;
    public final int colorSecondaryLabelInactive;
    public final int colorSecondaryLabelUnavailable;
    public final int colorUnavailable;
    public final ImageView customDrawableView;
    public QSLongPressProperties finalLongPressProperties;
    public boolean hasLongClickEffect;
    public boolean haveLongPressPropertiesBeenReset;
    public int heightOverride;
    public final QSIconViewImpl icon;
    public QSLongPressProperties initialLongPressProperties;
    public final TextView label;
    public final IgnorableChildLinearLayout labelContainer;
    public boolean lastDisabledByPolicy;
    public int lastIconTint;
    public int lastState;
    public CharSequence lastStateDescription;
    public final LaunchableViewDelegate launchableViewDelegate;
    public final int[] locInScreen;
    public final QSLongPressEffect longPressEffect;
    public ValueAnimator longPressEffectAnimator;
    public QSLogger mQsLogger;
    public final int overlayColorActive;
    public final int overlayColorInactive;
    public final Rect paddingForLaunch;
    public int position;
    public RippleDrawable qsTileBackground;
    public Drawable qsTileFocusBackground;
    public final TextView secondaryLabel;
    public final ViewGroup sideView;
    public final ValueAnimator singleAnimator;
    public float squishinessFraction;
    public CharSequence stateDescriptionDeltas;
    public final long tapTimeoutMillis;
    public boolean tileState;

    /* compiled from: go/retraceme 3f68c89d24d67735205e864b676482151f2126b7bc50ec77002e496c2ce25eed */
    public final class StateChangeRunnable implements Runnable {
        public QSTile.State state;
        public /* synthetic */ QSTileViewImpl this$0;

        public final boolean equals(Object obj) {
            return obj instanceof StateChangeRunnable;
        }

        public final int hashCode() {
            return Reflection.getOrCreateKotlinClass(StateChangeRunnable.class).hashCode();
        }

        @Override // java.lang.Runnable
        public final void run() {
            String str = this.state.spec;
            String take = StringsKt.take(127, (str == null || str.length() == 0) ? "QSTileViewImpl#handleStateChanged" : AndroidCompositionLocals_androidKt$$ExternalSyntheticOutline0.m("QSTileViewImpl#handleStateChanged:", this.state.spec));
            QSTileViewImpl qSTileViewImpl = this.this$0;
            boolean isTagEnabled = Trace.isTagEnabled(4096L);
            if (isTagEnabled) {
                Trace.traceBegin(4096L, take);
            }
            try {
                qSTileViewImpl.handleStateChanged(this.state);
            } finally {
                if (isTagEnabled) {
                    Trace.traceEnd(4096L);
                }
            }
        }
    }

    public QSTileViewImpl(Context context, boolean z, QSLongPressEffect qSLongPressEffect) {
        super(context);
        this.longPressEffect = qSLongPressEffect;
        QSIconViewImpl qSIconViewImpl = new QSIconViewImpl(context);
        this.icon = qSIconViewImpl;
        this.position = -1;
        this.hasLongClickEffect = true;
        this.heightOverride = -1;
        this.squishinessFraction = 1.0f;
        this.colorActive = Utils.getColorAttrDefaultColor(R.attr.shadeActive, 0, context);
        this.colorInactive = Utils.getColorAttrDefaultColor(R.attr.shadeInactive, 0, context);
        this.colorUnavailable = Utils.getColorAttrDefaultColor(R.attr.shadeDisabled, 0, context);
        int colorAttrDefaultColor = Utils.getColorAttrDefaultColor(R.attr.onShadeActive, 0, context);
        this.overlayColorActive = Color.argb((int) (0.11f * Color.alpha(colorAttrDefaultColor)), Color.red(colorAttrDefaultColor), Color.green(colorAttrDefaultColor), Color.blue(colorAttrDefaultColor));
        int colorAttrDefaultColor2 = Utils.getColorAttrDefaultColor(R.attr.onShadeInactive, 0, context);
        this.overlayColorInactive = Color.argb((int) (0.08f * Color.alpha(colorAttrDefaultColor2)), Color.red(colorAttrDefaultColor2), Color.green(colorAttrDefaultColor2), Color.blue(colorAttrDefaultColor2));
        this.colorLabelActive = Utils.getColorAttrDefaultColor(R.attr.onShadeActive, 0, context);
        this.colorLabelInactive = Utils.getColorAttrDefaultColor(R.attr.onShadeInactive, 0, context);
        this.colorLabelUnavailable = Utils.getColorAttrDefaultColor(R.attr.outline, 0, context);
        this.colorSecondaryLabelActive = Utils.getColorAttrDefaultColor(R.attr.onShadeActiveVariant, 0, context);
        this.colorSecondaryLabelInactive = Utils.getColorAttrDefaultColor(R.attr.onShadeInactiveVariant, 0, context);
        this.colorSecondaryLabelUnavailable = Utils.getColorAttrDefaultColor(R.attr.outline, 0, context);
        ValueAnimator valueAnimator = new ValueAnimator();
        valueAnimator.setDuration(350L);
        QSTileViewImpl$singleAnimator$1$1 qSTileViewImpl$singleAnimator$1$1 = new QSTileViewImpl$singleAnimator$1$1();
        qSTileViewImpl$singleAnimator$1$1.this$0 = this;
        VarHandle.storeStoreFence();
        valueAnimator.addUpdateListener(qSTileViewImpl$singleAnimator$1$1);
        this.singleAnimator = valueAnimator;
        this.lastState = -1;
        QSTileViewImpl$$ExternalSyntheticLambda0 qSTileViewImpl$$ExternalSyntheticLambda0 = new QSTileViewImpl$$ExternalSyntheticLambda0();
        qSTileViewImpl$$ExternalSyntheticLambda0.f$0 = this;
        VarHandle.storeStoreFence();
        this.launchableViewDelegate = new LaunchableViewDelegate(this, qSTileViewImpl$$ExternalSyntheticLambda0);
        this.locInScreen = new int[2];
        this.tapTimeoutMillis = ViewConfiguration.get(context).getTapTimeoutMillis();
        this.haveLongPressPropertiesBeenReset = true;
        this.paddingForLaunch = new Rect();
        this.colorEvaluator = ArgbEvaluator.getInstance();
        if (!getContext().getTheme().resolveAttribute(R.attr.isQsTheme, new TypedValue(), true)) {
            throw new IllegalStateException("QSViewImpl must be inflated with a theme that contains Theme.SystemUI.QuickSettings");
        }
        setId(LinearLayout.generateViewId());
        setOrientation(0);
        setGravity(8388627);
        setImportantForAccessibility(1);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setBackground(createTileBackground());
        int backgroundColorForState = getBackgroundColorForState(2, false);
        Drawable drawable = this.backgroundBaseDrawable;
        (drawable == null ? null : drawable).mutate().setTint(backgroundColorForState);
        this.backgroundColor = backgroundColorForState;
        int dimensionPixelSize = getResources().getDimensionPixelSize(R.dimen.qs_tile_padding);
        setPaddingRelative(getResources().getDimensionPixelSize(R.dimen.qs_tile_start_padding), dimensionPixelSize, dimensionPixelSize, dimensionPixelSize);
        int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.qs_icon_size);
        addView(qSIconViewImpl, new LinearLayout.LayoutParams(dimensionPixelSize2, dimensionPixelSize2));
        IgnorableChildLinearLayout ignorableChildLinearLayout = (IgnorableChildLinearLayout) LayoutInflater.from(getContext()).inflate(R.layout.qs_tile_label, (ViewGroup) this, false);
        this.labelContainer = ignorableChildLinearLayout;
        this.label = (TextView) (ignorableChildLinearLayout == null ? null : ignorableChildLinearLayout).requireViewById(R.id.tile_label);
        IgnorableChildLinearLayout ignorableChildLinearLayout2 = this.labelContainer;
        TextView textView = (TextView) (ignorableChildLinearLayout2 == null ? null : ignorableChildLinearLayout2).requireViewById(R.id.app_label);
        this.secondaryLabel = textView;
        if (z) {
            IgnorableChildLinearLayout ignorableChildLinearLayout3 = this.labelContainer;
            (ignorableChildLinearLayout3 == null ? null : ignorableChildLinearLayout3).ignoreLastView = true;
            (ignorableChildLinearLayout3 == null ? null : ignorableChildLinearLayout3).forceUnspecifiedMeasure = true;
            (textView == null ? null : textView).setAlpha(0.0f);
        }
        int labelColorForState = getLabelColorForState(2, false);
        TextView textView2 = this.label;
        (textView2 == null ? null : textView2).setTextColor(labelColorForState);
        int secondaryLabelColorForState = getSecondaryLabelColorForState(2, false);
        TextView textView3 = this.secondaryLabel;
        (textView3 == null ? null : textView3).setTextColor(secondaryLabelColorForState);
        View view = this.labelContainer;
        addView(view == null ? null : view);
        ViewGroup viewGroup = (ViewGroup) LayoutInflater.from(getContext()).inflate(R.layout.qs_tile_side_icon, (ViewGroup) this, false);
        this.sideView = viewGroup;
        this.customDrawableView = (ImageView) (viewGroup == null ? null : viewGroup).requireViewById(R.id.customDrawable);
        ViewGroup viewGroup2 = this.sideView;
        this.chevronView = (ImageView) (viewGroup2 == null ? null : viewGroup2).requireViewById(R.id.chevron);
        int secondaryLabelColorForState2 = getSecondaryLabelColorForState(2, false);
        ImageView imageView = this.chevronView;
        (imageView == null ? null : imageView).setImageTintList(ColorStateList.valueOf(secondaryLabelColorForState2));
        View view2 = this.sideView;
        addView(view2 != null ? view2 : null);
    }

    public boolean animationsEnabled() {
        if (isShown() && getAlpha() == 1.0f) {
            getLocationOnScreen(this.locInScreen);
            if (this.locInScreen[1] >= (-getHeight())) {
                return true;
            }
        }
        return false;
    }

    public final void changeCornerRadius(float f) {
        LayerDrawable layerDrawable = this.backgroundDrawable;
        if (layerDrawable == null) {
            layerDrawable = null;
        }
        int numberOfLayers = layerDrawable.getNumberOfLayers();
        for (int i = 0; i < numberOfLayers; i++) {
            LayerDrawable layerDrawable2 = this.backgroundDrawable;
            if (layerDrawable2 == null) {
                layerDrawable2 = null;
            }
            Drawable drawable = layerDrawable2.getDrawable(i);
            if (drawable instanceof GradientDrawable) {
                ((GradientDrawable) drawable).setCornerRadius(f);
            }
        }
    }

    public final Drawable createTileBackground() {
        this.qsTileBackground = (RippleDrawable) ((LinearLayout) this).mContext.getDrawable(R.drawable.qs_tile_background_flagged);
        Drawable drawable = ((LinearLayout) this).mContext.getDrawable(R.drawable.qs_tile_focused_background);
        drawable.getClass();
        this.qsTileFocusBackground = drawable;
        RippleDrawable rippleDrawable = this.qsTileBackground;
        if (rippleDrawable == null) {
            rippleDrawable = null;
        }
        LayerDrawable layerDrawable = (LayerDrawable) rippleDrawable.findDrawableByLayerId(R.id.background);
        this.backgroundDrawable = layerDrawable;
        if (layerDrawable == null) {
            layerDrawable = null;
        }
        this.backgroundBaseDrawable = layerDrawable.findDrawableByLayerId(R.id.qs_tile_background_base);
        LayerDrawable layerDrawable2 = this.backgroundDrawable;
        if (layerDrawable2 == null) {
            layerDrawable2 = null;
        }
        Drawable findDrawableByLayerId = layerDrawable2.findDrawableByLayerId(R.id.qs_tile_background_overlay);
        this.backgroundOverlayDrawable = findDrawableByLayerId;
        if (findDrawableByLayerId == null) {
            findDrawableByLayerId = null;
        }
        findDrawableByLayerId.mutate().setTintMode(PorterDuff.Mode.SRC);
        RippleDrawable rippleDrawable2 = this.qsTileBackground;
        if (rippleDrawable2 == null) {
            return null;
        }
        return rippleDrawable2;
    }

    public final int getBackgroundColorForState(int i, boolean z) {
        if (i == 0 || z) {
            return this.colorUnavailable;
        }
        if (i == 2) {
            return this.colorActive;
        }
        if (i == 1) {
            return this.colorInactive;
        }
        InvocationLightsView$$ExternalSyntheticOutline0.m(i, "Invalid state ", "QSTileViewImpl");
        return 0;
    }

    public final List getCurrentColors$frameworks__base__packages__SystemUI__android_common__SystemUI_core() {
        Integer valueOf = Integer.valueOf(this.backgroundColor);
        TextView textView = this.label;
        if (textView == null) {
            textView = null;
        }
        Integer valueOf2 = Integer.valueOf(textView.getCurrentTextColor());
        TextView textView2 = this.secondaryLabel;
        if (textView2 == null) {
            textView2 = null;
        }
        Integer valueOf3 = Integer.valueOf(textView2.getCurrentTextColor());
        ImageView imageView = this.chevronView;
        ColorStateList imageTintList = (imageView != null ? imageView : null).getImageTintList();
        return Arrays.asList(valueOf, valueOf2, valueOf3, Integer.valueOf(imageTintList != null ? imageTintList.getDefaultColor() : 0));
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final int getDetailY() {
        return (getHeight() / 2) + getTop();
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final QSIconView getIcon() {
        return this.icon;
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final View getIconWithBackground() {
        return this.icon;
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final View getLabel() {
        TextView textView = this.label;
        if (textView == null) {
            return null;
        }
        return textView;
    }

    public final int getLabelColorForState(int i, boolean z) {
        if (i == 0 || z) {
            return this.colorLabelUnavailable;
        }
        if (i == 2) {
            return this.colorLabelActive;
        }
        if (i == 1) {
            return this.colorLabelInactive;
        }
        InvocationLightsView$$ExternalSyntheticOutline0.m(i, "Invalid state ", "QSTileViewImpl");
        return 0;
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final View getLabelContainer() {
        IgnorableChildLinearLayout ignorableChildLinearLayout = this.labelContainer;
        if (ignorableChildLinearLayout == null) {
            return null;
        }
        return ignorableChildLinearLayout;
    }

    public final int getOverlayColorForState(int i) {
        if (i == 1) {
            return this.overlayColorInactive;
        }
        if (i != 2) {
            return 0;
        }
        return this.overlayColorActive;
    }

    @Override // com.android.systemui.animation.LaunchableView
    public final Rect getPaddingForLaunchAnimation() {
        QSLongPressEffect qSLongPressEffect = this.longPressEffect;
        return (qSLongPressEffect != null ? qSLongPressEffect.state : null) == QSLongPressEffect.State.LONG_CLICKED ? this.paddingForLaunch : EMPTY_RECT;
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final View getSecondaryIcon() {
        ViewGroup viewGroup = this.sideView;
        if (viewGroup != null) {
            return viewGroup;
        }
        return null;
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final View getSecondaryLabel() {
        TextView textView = this.secondaryLabel;
        if (textView != null) {
            return textView;
        }
        return null;
    }

    public final int getSecondaryLabelColorForState(int i, boolean z) {
        if (i == 0 || z) {
            return this.colorSecondaryLabelUnavailable;
        }
        if (i == 2) {
            return this.colorSecondaryLabelActive;
        }
        if (i == 1) {
            return this.colorSecondaryLabelInactive;
        }
        InvocationLightsView$$ExternalSyntheticOutline0.m(i, "Invalid state ", "QSTileViewImpl");
        return 0;
    }

    /* JADX WARN: Removed duplicated region for block: B:168:0x015f  */
    /* JADX WARN: Removed duplicated region for block: B:182:0x01fa  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void handleStateChanged(com.android.systemui.plugins.qs.QSTile.State r18) {
        /*
            Method dump skipped, instructions count: 860
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.systemui.qs.tileimpl.QSTileViewImpl.handleStateChanged(com.android.systemui.plugins.qs.QSTile$State):void");
    }

    @Override // android.view.View
    public final boolean hasOverlappingRendering() {
        return false;
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final void init(QSTile qSTile) {
        if (this.longPressEffect == null) {
            Expandable$Companion$fromView$1 expandable$Companion$fromView$1 = new Expandable$Companion$fromView$1();
            expandable$Companion$fromView$1.$view = this;
            VarHandle.storeStoreFence();
            QSTileViewImpl$init$3 qSTileViewImpl$init$3 = new QSTileViewImpl$init$3();
            qSTileViewImpl$init$3.$tile = qSTile;
            qSTileViewImpl$init$3.$expandable = expandable$Companion$fromView$1;
            VarHandle.storeStoreFence();
            QSTileViewImpl$init$4 qSTileViewImpl$init$4 = new QSTileViewImpl$init$4();
            qSTileViewImpl$init$4.$tile = qSTile;
            qSTileViewImpl$init$4.$expandable = expandable$Companion$fromView$1;
            VarHandle.storeStoreFence();
            setOnClickListener(qSTileViewImpl$init$3);
            setOnLongClickListener(qSTileViewImpl$init$4);
            return;
        }
        setHapticFeedbackEnabled(false);
        QSLongPressEffect qSLongPressEffect = this.longPressEffect;
        qSLongPressEffect.qsTile = qSTile;
        QSLongPressEffect$createExpandableFromView$1 qSLongPressEffect$createExpandableFromView$1 = new QSLongPressEffect$createExpandableFromView$1();
        qSLongPressEffect$createExpandableFromView$1.$view = this;
        qSLongPressEffect$createExpandableFromView$1.this$0 = qSLongPressEffect;
        VarHandle.storeStoreFence();
        qSLongPressEffect.expandable = qSLongPressEffect$createExpandableFromView$1;
        if (qSLongPressEffect != null) {
            QSTileViewImpl$initLongPressEffectCallback$1 qSTileViewImpl$initLongPressEffectCallback$1 = new QSTileViewImpl$initLongPressEffectCallback$1();
            qSTileViewImpl$initLongPressEffectCallback$1.this$0 = this;
            VarHandle.storeStoreFence();
            qSLongPressEffect.callback = qSTileViewImpl$initLongPressEffectCallback$1;
        }
        QSTileViewImpl$init$1 qSTileViewImpl$init$1 = new QSTileViewImpl$init$1();
        qSTileViewImpl$init$1.this$0 = this;
        VarHandle.storeStoreFence();
        QSTileViewImpl$init$2 qSTileViewImpl$init$2 = new QSTileViewImpl$init$2();
        qSTileViewImpl$init$2.this$0 = this;
        VarHandle.storeStoreFence();
        setOnClickListener(qSTileViewImpl$init$1);
        setOnLongClickListener(qSTileViewImpl$init$2);
    }

    public final void initializeLongPressProperties(int i, int i2) {
        float f = i;
        float f2 = i2;
        this.initialLongPressProperties = new QSLongPressProperties(f, f2, getResources().getDimensionPixelSize(R.dimen.qs_corner_radius), getBackgroundColorForState(this.lastState, false), getLabelColorForState(this.lastState, false), getSecondaryLabelColorForState(this.lastState, false), getSecondaryLabelColorForState(this.lastState, false), getOverlayColorForState(this.lastState), this.lastIconTint);
        float f3 = f * 1.2f;
        float f4 = f2 * 1.1f;
        this.finalLongPressProperties = new QSLongPressProperties(f3, f4, getResources().getDimensionPixelSize(R.dimen.qs_corner_radius) - 20, getBackgroundColorForState(2, false), getLabelColorForState(2, false), getSecondaryLabelColorForState(2, false), getSecondaryLabelColorForState(2, false), this.overlayColorActive, Utils.getColorAttrDefaultColor(R.attr.onShadeActive, 0, getContext()));
        QSLongPressProperties qSLongPressProperties = this.initialLongPressProperties;
        int i3 = qSLongPressProperties != null ? (int) qSLongPressProperties.height : 0;
        int i4 = qSLongPressProperties != null ? (int) qSLongPressProperties.width : 0;
        int i5 = (int) (f3 - i3);
        int i6 = (int) (f4 - i4);
        Rect rect = this.paddingForLaunch;
        rect.left = (-i6) / 2;
        rect.top = (-i5) / 2;
        rect.right = i6 / 2;
        rect.bottom = i5 / 2;
    }

    @Override // com.android.systemui.animation.LaunchableView
    public final void onActivityLaunchAnimationEnd() {
        QSLongPressEffect qSLongPressEffect = this.longPressEffect;
        if (qSLongPressEffect != null) {
            qSLongPressEffect.setState(QSLongPressEffect.State.IDLE);
        }
        if (this.longPressEffect == null || this.haveLongPressPropertiesBeenReset) {
            return;
        }
        resetLongPressEffectProperties();
    }

    @Override // android.view.View
    public final void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        TextView textView = this.label;
        if (textView == null) {
            textView = null;
        }
        FontSizeUtils.updateFontSize(textView, R.dimen.qs_tile_text_size);
        TextView textView2 = this.secondaryLabel;
        if (textView2 == null) {
            textView2 = null;
        }
        FontSizeUtils.updateFontSize(textView2, R.dimen.qs_tile_text_size);
        int dimensionPixelSize = getContext().getResources().getDimensionPixelSize(R.dimen.qs_icon_size);
        ViewGroup.LayoutParams layoutParams = this.icon.getLayoutParams();
        layoutParams.height = dimensionPixelSize;
        layoutParams.width = dimensionPixelSize;
        int dimensionPixelSize2 = getResources().getDimensionPixelSize(R.dimen.qs_tile_padding);
        setPaddingRelative(getResources().getDimensionPixelSize(R.dimen.qs_tile_start_padding), dimensionPixelSize2, dimensionPixelSize2, dimensionPixelSize2);
        int dimensionPixelSize3 = getResources().getDimensionPixelSize(R.dimen.qs_label_container_margin);
        IgnorableChildLinearLayout ignorableChildLinearLayout = this.labelContainer;
        if (ignorableChildLinearLayout == null) {
            ignorableChildLinearLayout = null;
        }
        ((ViewGroup.MarginLayoutParams) ignorableChildLinearLayout.getLayoutParams()).setMarginStart(dimensionPixelSize3);
        ViewGroup viewGroup = this.sideView;
        if (viewGroup == null) {
            viewGroup = null;
        }
        ((ViewGroup.MarginLayoutParams) viewGroup.getLayoutParams()).setMarginStart(dimensionPixelSize3);
        ImageView imageView = this.chevronView;
        if (imageView == null) {
            imageView = null;
        }
        ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
        marginLayoutParams.height = dimensionPixelSize;
        marginLayoutParams.width = dimensionPixelSize;
        int dimensionPixelSize4 = getResources().getDimensionPixelSize(R.dimen.qs_drawable_end_margin);
        ImageView imageView2 = this.customDrawableView;
        if (imageView2 == null) {
            imageView2 = null;
        }
        ViewGroup.MarginLayoutParams marginLayoutParams2 = (ViewGroup.MarginLayoutParams) imageView2.getLayoutParams();
        marginLayoutParams2.height = dimensionPixelSize;
        marginLayoutParams2.setMarginEnd(dimensionPixelSize4);
        setBackground(createTileBackground());
        int i = this.backgroundColor;
        Drawable drawable = this.backgroundBaseDrawable;
        if (drawable == null) {
            drawable = null;
        }
        drawable.mutate().setTint(i);
        this.backgroundColor = i;
        int i2 = this.backgroundOverlayColor;
        Drawable drawable2 = this.backgroundOverlayDrawable;
        (drawable2 != null ? drawable2 : null).setTint(i2);
        this.backgroundOverlayColor = i2;
    }

    @Override // android.view.View
    public final void onFocusChanged(boolean z, int i, Rect rect) {
        super.onFocusChanged(z, i, rect);
        if (!z) {
            getOverlay().clear();
            return;
        }
        Drawable drawable = this.qsTileFocusBackground;
        if (drawable == null) {
            drawable = null;
        }
        drawable.setBounds(0, 0, getWidth(), getHeight());
        ViewGroupOverlay overlay = getOverlay();
        Drawable drawable2 = this.qsTileFocusBackground;
        overlay.add(drawable2 != null ? drawable2 : null);
    }

    @Override // android.view.View
    public final void onInitializeAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        super.onInitializeAccessibilityEvent(accessibilityEvent);
        if (!TextUtils.isEmpty(this.accessibilityClass)) {
            accessibilityEvent.setClassName(this.accessibilityClass);
        }
        if (accessibilityEvent.getContentChangeTypes() != 64 || this.stateDescriptionDeltas == null) {
            return;
        }
        accessibilityEvent.getText().add(this.stateDescriptionDeltas);
        this.stateDescriptionDeltas = null;
    }

    @Override // android.view.View
    public final void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo accessibilityNodeInfo) {
        String str;
        super.onInitializeAccessibilityNodeInfo(accessibilityNodeInfo);
        accessibilityNodeInfo.setSelected(false);
        TextView textView = this.secondaryLabel;
        if (textView == null) {
            textView = null;
        }
        if (TextUtils.isEmpty(textView.getText())) {
            TextView textView2 = this.label;
            str = String.valueOf((textView2 != null ? textView2 : null).getText());
        } else {
            TextView textView3 = this.label;
            if (textView3 == null) {
                textView3 = null;
            }
            CharSequence text = textView3.getText();
            TextView textView4 = this.secondaryLabel;
            str = ((Object) text) + ", " + ((Object) (textView4 != null ? textView4 : null).getText());
        }
        accessibilityNodeInfo.setText(str);
        if (this.lastDisabledByPolicy) {
            accessibilityNodeInfo.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.getId(), getResources().getString(R.string.accessibility_tile_disabled_by_policy_action_description)));
        } else if (isLongClickable()) {
            accessibilityNodeInfo.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.getId(), getResources().getString(R.string.accessibility_long_click_tile)));
        }
        if (!TextUtils.isEmpty(this.accessibilityClass)) {
            accessibilityNodeInfo.setClassName(this.lastDisabledByPolicy ? "android.widget.Button" : this.accessibilityClass);
            if (Switch.class.getName().equals(this.accessibilityClass)) {
                accessibilityNodeInfo.setChecked(this.tileState);
                accessibilityNodeInfo.setCheckable(true);
            }
        }
        if (this.position != -1) {
            accessibilityNodeInfo.setCollectionItemInfo(new AccessibilityNodeInfo.CollectionItemInfo(this.position, 1, 0, 1, false));
        }
    }

    @Override // android.widget.LinearLayout, android.view.ViewGroup, android.view.View
    public final void onLayout(boolean z, int i, int i2, int i3, int i4) {
        super.onLayout(z, i, i2, i3, i4);
        updateHeight();
        float measuredWidth = getMeasuredWidth();
        if (isLongClickable() && this.longPressEffect != null && this.hasLongClickEffect) {
            QSLongPressProperties qSLongPressProperties = this.initialLongPressProperties;
            if (qSLongPressProperties != null) {
                qSLongPressProperties.width = measuredWidth;
            }
            QSLongPressProperties qSLongPressProperties2 = this.finalLongPressProperties;
            if (qSLongPressProperties2 != null) {
                qSLongPressProperties2.width = 1.1f * measuredWidth;
            }
            Rect rect = this.paddingForLaunch;
            int i5 = (int) (measuredWidth * 0.100000024f);
            rect.left = (-i5) / 2;
            rect.right = i5 / 2;
        }
    }

    @Override // android.widget.LinearLayout, android.view.View
    public final void onMeasure(int i, int i2) {
        Trace.traceBegin(4096L, "QSTileViewImpl#onMeasure");
        super.onMeasure(i, i2);
        Trace.endSection();
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final void onStateChanged(QSTile.State state) {
        QSTile.State copy = state.copy();
        StateChangeRunnable stateChangeRunnable = new StateChangeRunnable();
        stateChangeRunnable.this$0 = this;
        stateChangeRunnable.state = copy;
        VarHandle.storeStoreFence();
        removeCallbacks(stateChangeRunnable);
        post(stateChangeRunnable);
    }

    /* JADX WARN: Code restructure failed: missing block: B:21:0x0041, code lost:
    
        if (r1 != 6) goto L32;
     */
    /* JADX WARN: Removed duplicated region for block: B:24:0x0060  */
    @Override // android.view.View
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final boolean onTouchEvent(android.view.MotionEvent r6) {
        /*
            r5 = this;
            boolean r0 = super.onTouchEvent(r6)
            com.android.systemui.haptics.qs.QSLongPressEffect r1 = r5.longPressEffect
            if (r1 == 0) goto Lc7
            r1 = 0
            if (r6 == 0) goto L14
            int r6 = r6.getActionMasked()
            java.lang.Integer r6 = java.lang.Integer.valueOf(r6)
            goto L15
        L14:
            r6 = r1
        L15:
            r2 = 3
            if (r6 != 0) goto L19
            goto L70
        L19:
            int r3 = r6.intValue()
            if (r3 != 0) goto L70
            com.android.systemui.haptics.qs.QSLongPressEffect r6 = r5.longPressEffect
            com.android.systemui.plugins.qs.QSTile r3 = r6.qsTile
            if (r3 == 0) goto L29
            java.lang.String r1 = r3.getTileSpec()
        L29:
            com.android.systemui.haptics.qs.QSLongPressEffect$State r3 = r6.state
            java.lang.String r4 = "action down received"
            r6.logEvent(r1, r3, r4)
            com.android.systemui.haptics.qs.QSLongPressEffect$State r1 = r6.state
            int r1 = r1.ordinal()
            if (r1 == 0) goto L55
            if (r1 == r2) goto L44
            r2 = 4
            if (r1 == r2) goto L44
            r2 = 5
            if (r1 == r2) goto L55
            r2 = 6
            if (r1 == r2) goto L55
            goto L5a
        L44:
            com.android.systemui.qs.tileimpl.QSTileViewImpl$initLongPressEffectCallback$1 r6 = r6.callback
            if (r6 == 0) goto L5a
            com.android.systemui.qs.tileimpl.QSTileViewImpl r6 = r6.this$0
            r6.resetLongPressEffectProperties()
            android.animation.ValueAnimator r6 = r6.longPressEffectAnimator
            if (r6 == 0) goto L5a
            r6.cancel()
            goto L5a
        L55:
            com.android.systemui.haptics.qs.QSLongPressEffect$State r1 = com.android.systemui.haptics.qs.QSLongPressEffect.State.TIMEOUT_WAIT
            r6.setState(r1)
        L5a:
            boolean r6 = r5.isLongClickable()
            if (r6 == 0) goto Lc7
            com.android.systemui.qs.tileimpl.QSTileViewImpl$onTouchEvent$1 r6 = new com.android.systemui.qs.tileimpl.QSTileViewImpl$onTouchEvent$1
            r6.<init>()
            r6.this$0 = r5
            java.lang.invoke.VarHandle.storeStoreFence()
            long r1 = r5.tapTimeoutMillis
            r5.postDelayed(r6, r1)
            return r0
        L70:
            r3 = 1
            if (r6 != 0) goto L74
            goto L9e
        L74:
            int r4 = r6.intValue()
            if (r4 != r3) goto L9e
            com.android.systemui.haptics.qs.QSLongPressEffect r5 = r5.longPressEffect
            com.android.systemui.plugins.qs.QSTile r6 = r5.qsTile
            if (r6 == 0) goto L84
            java.lang.String r1 = r6.getTileSpec()
        L84:
            com.android.systemui.haptics.qs.QSLongPressEffect$State r6 = r5.state
            java.lang.String r2 = "action up received"
            r5.logEvent(r1, r6, r2)
            com.android.systemui.haptics.qs.QSLongPressEffect$State r6 = r5.state
            com.android.systemui.haptics.qs.QSLongPressEffect$State r1 = com.android.systemui.haptics.qs.QSLongPressEffect.State.RUNNING_FORWARD
            if (r6 != r1) goto Lc7
            com.android.systemui.haptics.qs.QSLongPressEffect$State r6 = com.android.systemui.haptics.qs.QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_UP
            r5.setState(r6)
            com.android.systemui.qs.tileimpl.QSTileViewImpl$initLongPressEffectCallback$1 r5 = r5.callback
            if (r5 == 0) goto Lc7
            r5.onReverseAnimator(r3)
            return r0
        L9e:
            if (r6 != 0) goto La1
            goto Lc7
        La1:
            int r6 = r6.intValue()
            if (r6 != r2) goto Lc7
            com.android.systemui.haptics.qs.QSLongPressEffect r5 = r5.longPressEffect
            com.android.systemui.haptics.qs.QSLongPressEffect$State r6 = r5.state
            int r6 = r6.ordinal()
            if (r6 == r3) goto Lc2
            r1 = 2
            if (r6 == r1) goto Lb5
            goto Lc7
        Lb5:
            com.android.systemui.haptics.qs.QSLongPressEffect$State r6 = com.android.systemui.haptics.qs.QSLongPressEffect.State.RUNNING_BACKWARDS_FROM_CANCEL
            r5.setState(r6)
            com.android.systemui.qs.tileimpl.QSTileViewImpl$initLongPressEffectCallback$1 r5 = r5.callback
            if (r5 == 0) goto Lc7
            r5.onReverseAnimator(r3)
            return r0
        Lc2:
            com.android.systemui.haptics.qs.QSLongPressEffect$State r6 = com.android.systemui.haptics.qs.QSLongPressEffect.State.IDLE
            r5.setState(r6)
        Lc7:
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.systemui.qs.tileimpl.QSTileViewImpl.onTouchEvent(android.view.MotionEvent):boolean");
    }

    public final void resetLongPressEffectProperties() {
        Drawable background = getBackground();
        QSLongPressProperties qSLongPressProperties = this.initialLongPressProperties;
        int measuredWidth = qSLongPressProperties != null ? (int) qSLongPressProperties.width : getMeasuredWidth();
        QSLongPressProperties qSLongPressProperties2 = this.initialLongPressProperties;
        background.setBounds(0, 0, measuredWidth, qSLongPressProperties2 != null ? (int) qSLongPressProperties2.height : getMeasuredHeight());
        changeCornerRadius(getResources().getDimensionPixelSize(R.dimen.qs_corner_radius));
        setAllColors(getBackgroundColorForState(this.lastState, this.lastDisabledByPolicy), getLabelColorForState(this.lastState, this.lastDisabledByPolicy), getSecondaryLabelColorForState(this.lastState, this.lastDisabledByPolicy), getSecondaryLabelColorForState(this.lastState, this.lastDisabledByPolicy), getOverlayColorForState(this.lastState));
        QSIconViewImpl qSIconViewImpl = this.icon;
        ImageView imageView = (ImageView) qSIconViewImpl.mIcon;
        int i = this.lastIconTint;
        imageView.setImageTintList(ColorStateList.valueOf(i));
        qSIconViewImpl.mTint = i;
        this.haveLongPressPropertiesBeenReset = true;
    }

    public final void setAllColors(int i, int i2, int i3, int i4, int i5) {
        Drawable drawable = this.backgroundBaseDrawable;
        if (drawable == null) {
            drawable = null;
        }
        drawable.mutate().setTint(i);
        this.backgroundColor = i;
        TextView textView = this.label;
        if (textView == null) {
            textView = null;
        }
        textView.setTextColor(i2);
        TextView textView2 = this.secondaryLabel;
        if (textView2 == null) {
            textView2 = null;
        }
        textView2.setTextColor(i3);
        ImageView imageView = this.chevronView;
        if (imageView == null) {
            imageView = null;
        }
        imageView.setImageTintList(ColorStateList.valueOf(i4));
        Drawable drawable2 = this.backgroundOverlayDrawable;
        (drawable2 != null ? drawable2 : null).setTint(i5);
        this.backgroundOverlayColor = i5;
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final void setPosition(int i) {
        this.position = i;
    }

    @Override // com.android.systemui.animation.LaunchableView
    public final void setShouldBlockVisibilityChanges(boolean z) {
        this.launchableViewDelegate.setShouldBlockVisibilityChanges(z);
    }

    @Override // android.view.View
    public final void setVisibility(int i) {
        this.launchableViewDelegate.setVisibility(i);
    }

    @Override // android.view.View
    public final String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        sb.append('[');
        int[] iArr = this.locInScreen;
        sb.append(MutableVectorKt$$ExternalSyntheticOutline0.m(iArr[0], iArr[1], "locInScreen=(", ", ", ")"));
        sb.append(", iconView=" + this.icon);
        sb.append(", tileState=" + this.tileState);
        sb.append("]");
        return sb.toString();
    }

    @Override // com.android.systemui.plugins.qs.QSTileView
    public final View updateAccessibilityOrder(View view) {
        setAccessibilityTraversalAfter(view != null ? view.getId() : 0);
        return this;
    }

    public final void updateHeight() {
        int i = this.heightOverride;
        if (i == -1) {
            i = getMeasuredHeight();
        }
        float f = i;
        setBottom(getTop() + ((int) (((this.squishinessFraction * 0.9f) + 0.1f) * f)));
        setScrollY((i - getHeight()) / 2);
        if (isLongClickable() && this.longPressEffect != null && this.hasLongClickEffect) {
            QSLongPressProperties qSLongPressProperties = this.initialLongPressProperties;
            if (qSLongPressProperties != null) {
                qSLongPressProperties.height = f;
            }
            QSLongPressProperties qSLongPressProperties2 = this.finalLongPressProperties;
            if (qSLongPressProperties2 != null) {
                qSLongPressProperties2.height = 1.2f * f;
            }
            Rect rect = this.paddingForLaunch;
            int i2 = (int) (f * 0.20000005f);
            rect.top = (-i2) / 2;
            rect.bottom = i2 / 2;
        }
    }
}
