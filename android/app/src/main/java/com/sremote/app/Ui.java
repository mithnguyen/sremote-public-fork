package com.sremote.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Material You palette (dynamic colors on Android 12+) + tiny code-built widget kit. */
final class Ui {
    final Context c;
    final boolean dark;
    // M3 color roles
    final int surface, onSurface, onSurfaceVariant, container, containerHigh, outline, outlineVariant;
    final int primary, onPrimary, primaryContainer, onPrimaryContainer;
    final int secondaryContainer, onSecondaryContainer, tertiaryContainer, onTertiaryContainer;
    // status roles
    final int ok, okContainer, warn, warnContainer, err, errContainer;

    Ui(Context ctx) {
        c = ctx;
        String mode = new HostStore(ctx).theme();
        dark = HostStore.THEME_DARK.equals(mode) || (!HostStore.THEME_LIGHT.equals(mode)
                && (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        // brand palette shared with the web UI's glass theme (ink + blue→violet
        // gradient) — fixed instead of dynamic so app and browser look the same
        if (dark) {
            surface = 0xFF070A10; container = 0xFF0F1521; containerHigh = 0xFF19202E;
            onSurface = 0xFFE6EDF3; onSurfaceVariant = 0xFF94A3B8; outline = 0xFF64748B; outlineVariant = 0xFF232C3D;
            primary = 0xFFA99CFF; onPrimary = 0xFFFFFFFF; primaryContainer = 0xFF252A52; onPrimaryContainer = 0xFFC7D2FE;
            secondaryContainer = 0xFF18233A; onSecondaryContainer = 0xFFBFD7FF;
            tertiaryContainer = 0xFF2A2149; onTertiaryContainer = 0xFFE0D4FF;
            ok = 0xFF34D399; okContainer = 0xFF0E2B23;
            warn = 0xFFFBBF24; warnContainer = 0xFF33270A;
            err = 0xFFF87171; errContainer = 0xFF3A1519;
        } else {
            surface = 0xFFF5F7FB; container = 0xFFFFFFFF; containerHigh = 0xFFEEF1F7;
            onSurface = 0xFF0F172A; onSurfaceVariant = 0xFF55627A; outline = 0xFF8792A8; outlineVariant = 0xFFDDE3EE;
            primary = 0xFF6D5EF5; onPrimary = 0xFFFFFFFF; primaryContainer = 0xFFE7E4FF; onPrimaryContainer = 0xFF2A1F8F;
            secondaryContainer = 0xFFE2EDFF; onSecondaryContainer = 0xFF0B3D91;
            tertiaryContainer = 0xFFF1E6FF; onTertiaryContainer = 0xFF4A1D80;
            ok = 0xFF1E7A3A; okContainer = 0xFFD5F2DC;
            warn = 0xFF8A5A00; warnContainer = 0xFFFFE9C2;
            err = 0xFFBA1A1A; errContainer = 0xFFFFDAD6;
        }
    }

    // brand gradient stops (web: --grad)
    static final int G1 = 0xFF5AA9FF, G2 = 0xFF8B7BFF, G3 = 0xFFB06BFF;

    int dp(float v) { return (int) (v * c.getResources().getDisplayMetrics().density + .5f); }

    int dialogTheme() {
        return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    /** status/nav bars match the surface; dark icons in light mode */
    void applyWindow(Activity a, int barColor) {
        Window w = a.getWindow();
        w.setStatusBarColor(barColor);
        w.setNavigationBarColor(barColor);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        if (!dark) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else f &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        d.setSystemUiVisibility(f);
    }

    // ---------- drawables ----------
    GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(r));
        return g;
    }

    GradientDrawable stroke(int fill, float r, int strokeColor) {
        GradientDrawable g = round(fill, r);
        g.setStroke(dp(1), strokeColor);
        return g;
    }

    GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL); g.setColor(color);
        return g;
    }

    /** blue → violet brand gradient (web --grad) */
    GradientDrawable grad(float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{G1, G2, G3});
        g.setCornerRadius(dp(r));
        return g;
    }

    /** soft tinted gradient for highlighted surfaces (web --grad-soft) */
    GradientDrawable gradSoft(float r) {
        int a = dark ? 0x29000000 : 0x1F000000;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{(G1 & 0xFFFFFF) | a, (G2 & 0xFFFFFF) | a});
        g.setCornerRadius(dp(r));
        g.setStroke(dp(1), (G2 & 0xFFFFFF) | (dark ? 0x40000000 : 0x33000000));
        return g;
    }

    /** glass card: surface container + hairline border, like .ws-card on the web */
    GradientDrawable glass(float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{dark ? 0xFF131A28 : container, container});
        g.setCornerRadius(dp(r));
        g.setStroke(dp(1), outlineVariant);
        return g;
    }

    /** page background: ink with the web's faint violet / blue / green glows */
    Drawable backdrop() {
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        android.graphics.drawable.ColorDrawable base = new android.graphics.drawable.ColorDrawable(surface);
        int k = dark ? 1 : 0;
        Drawable[] layers = {
                base,
                glow(0.92f, -0.06f, w * 1.0f, (G2 & 0xFFFFFF) | (k == 1 ? 0x2E000000 : 0x1C000000)),
                glow(-0.08f, 0.10f, w * 0.85f, (G1 & 0xFFFFFF) | (k == 1 ? 0x24000000 : 0x16000000)),
                glow(0.5f, 1.12f, Math.max(w, h * 0.6f), (k == 1 ? 0x1534D399 : 0x0F34D399)),
        };
        return new android.graphics.drawable.LayerDrawable(layers);
    }

    private static GradientDrawable glow(float cx, float cy, float radius, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(radius);
        return g;
    }

    /** paint a TextView's glyphs with the brand gradient (web .brand-txt i) */
    static TextView gradientText(TextView t) {
        t.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            float wpx = t.getPaint().measureText(t.getText().toString());
            if (wpx <= 0) return;
            t.getPaint().setShader(new android.graphics.LinearGradient(0, 0, wpx, 0,
                    new int[]{G1, G2, G3}, null, android.graphics.Shader.TileMode.CLAMP));
            t.invalidate();
        });
        return t;
    }

    Drawable ripple(Drawable content, float r) {
        int rc = (onSurface & 0x00FFFFFF) | 0x26000000;
        return new RippleDrawable(ColorStateList.valueOf(rc), content, round(0xFFFFFFFF, r));
    }

    // ---------- widgets ----------
    TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        return t;
    }

    TextView title(CharSequence s) {
        TextView t = text(s, 30, onSurface, false);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setLetterSpacing(-0.01f);
        return t;
    }

    TextView label(CharSequence s) {
        TextView t = text(s, 14, primary, true);
        t.setPadding(dp(4), dp(20), dp(4), dp(8));
        return t;
    }

    TextView oneLine(TextView t) {
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    TextView mono(CharSequence s, float sp, int color) {
        TextView t = oneLine(text(s, sp, color, false));
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    ImageView icon(int res, int color, float sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    /** 48dp round touch target with a 24dp icon */
    ImageView iconButton(int res, int color) {
        ImageView iv = icon(res, color, 48);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        iv.setBackground(ripple(oval(0x00000000), 24));
        return iv;
    }

    TextView filledButton(CharSequence s) {
        TextView b = text(s, 14, onPrimary, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(24), dp(10), dp(24), dp(10));
        b.setMinHeight(dp(40));
        b.setBackground(ripple(grad(20), 20));
        return b;
    }

    TextView tonalButton(CharSequence s) {
        TextView b = text(s, 14, onSecondaryContainer, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(9), dp(20), dp(9));
        b.setMinHeight(dp(40));
        b.setBackground(ripple(round(secondaryContainer, 20), 20));
        return b;
    }

    TextView textButton(CharSequence s) {
        TextView b = text(s, 14, primary, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        b.setBackground(ripple(round(0x00000000, 20), 20));
        return b;
    }

    /** filter chip */
    TextView chip(CharSequence s, boolean selected) {
        TextView t = text(s, 13, selected ? onPrimaryContainer : onSurfaceVariant, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(7), dp(16), dp(7));
        t.setBackground(ripple(selected ? gradSoft(10) : stroke(0x00000000, 10, outlineVariant), 10));
        return t;
    }

    /** small status pill */
    TextView pill(CharSequence s, int fg, int bg) {
        TextView t = text(s, 12, fg, true);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        t.setBackground(round(bg, 10));
        return t;
    }

    View dot(int color, float size) {
        View v = new View(c);
        v.setBackground(oval(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        v.setLayoutParams(lp);
        return v;
    }

    TextView avatar(String name, int bg, int fg) {
        String l = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        TextView t = text(l, 18, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(oval(bg));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return t;
    }

    /** rounded surface card */
    LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        l.setBackground(ripple(color == container ? glass(24) : round(color, 24), 24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        l.setLayoutParams(lp);
        return l;
    }

    LinearLayout hrow() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout vcol() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    Switch toggle(boolean on) {
        Switch s = new Switch(c);
        s.setChecked(on);
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(st, new int[]{onPrimary, outline}));
        s.setTrackTintList(new ColorStateList(st, new int[]{primary, containerHigh}));
        return s;
    }

    /** settings-style row: title + optional subtitle + trailing view */
    LinearLayout settingRow(String title, String sub, View trailing) {
        LinearLayout r = hrow();
        r.setPadding(dp(16), dp(12), dp(12), dp(12));
        r.setMinimumHeight(dp(56));
        LinearLayout tc = vcol();
        tc.addView(text(title, 16, onSurface, false));
        if (sub != null) {
            TextView s = text(sub, 13, onSurfaceVariant, false);
            s.setPadding(0, dp(2), 0, 0);
            tc.addView(s);
        }
        r.addView(tc, weight1());
        if (trailing != null) r.addView(trailing);
        return r;
    }

    // ---------- session state presentation ----------
    int stateColor(Detector.State s) {
        switch (s) {
            case RUNNING: return primary;
            case QUESTION: return warn;
            case DONE: return ok;
            case EXITED: return outline;
            default: return onSurfaceVariant;
        }
    }

    int stateContainer(Detector.State s) {
        switch (s) {
            case RUNNING: return primaryContainer;
            case QUESTION: return warnContainer;
            case DONE: return okContainer;
            default: return containerHigh;
        }
    }

    static String stateLabel(Detector.State s) {
        switch (s) {
            case RUNNING: return "Running";
            case QUESTION: return "Needs input";
            case DONE: return "Done";
            case EXITED: return "Exited";
            default: return "Idle";
        }
    }

    // ---------- host colour tags ----------
    /** slot 0 follows the dynamic accent; 1..6 fixed tones (blue, green, amber, pink, purple, teal) */
    static final int HOST_COLORS = 7;
    private static final int[] HC_LIGHT_BG = {0, 0xFFD6E3FF, 0xFFC8F0D0, 0xFFFFE0B2, 0xFFFFD8E8, 0xFFE8DDFF, 0xFFC4EFEA};
    private static final int[] HC_LIGHT_FG = {0, 0xFF0B3D91, 0xFF0F5223, 0xFF6B3E00, 0xFF7A1846, 0xFF3F1F8C, 0xFF00504A};
    private static final int[] HC_DARK_BG = {0, 0xFF1F3A66, 0xFF1C4428, 0xFF4E3410, 0xFF5A2140, 0xFF3A2C66, 0xFF0F443F};
    private static final int[] HC_DARK_FG = {0, 0xFFBFD3FF, 0xFFA8E6B6, 0xFFFFD08A, 0xFFFFB6D3, 0xFFD4C6FF, 0xFF9EE8DE};

    int hostBg(int i) { i = Math.floorMod(i, HOST_COLORS); return i == 0 ? primaryContainer : (dark ? HC_DARK_BG : HC_LIGHT_BG)[i]; }
    int hostFg(int i) { i = Math.floorMod(i, HOST_COLORS); return i == 0 ? onPrimaryContainer : (dark ? HC_DARK_FG : HC_LIGHT_FG)[i]; }

    TextView hostAvatar(Host h, float size) {
        TextView t = avatar(h.name, hostBg(h.colorIndex()), hostFg(h.colorIndex()));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        t.setTextSize(size * 0.42f);
        return t;
    }

    // ---------- 2.0 widgets ----------
    /** themed single-line input on a rounded container (explicit colours: survives theme override) */
    android.widget.EditText input(CharSequence hint) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setTextColor(onSurface);
        e.setHintTextColor(onSurfaceVariant);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        e.setBackground(stroke(containerHigh, 14, outlineVariant));
        return e;
    }

    /** search box: magnifier + input on a pill */
    LinearLayout searchField(CharSequence hint, android.widget.EditText[] out) {
        LinearLayout r = hrow();
        r.setPadding(dp(16), 0, dp(8), 0);
        r.setMinimumHeight(dp(52));
        r.setBackground(round(containerHigh, 26));
        r.addView(icon(R.drawable.ic_search, onSurfaceVariant, 22));
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setTextColor(onSurface);
        e.setHintTextColor(onSurfaceVariant);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(dp(12), dp(10), dp(8), dp(10));
        r.addView(e, weight1());
        out[0] = e;
        return r;
    }

    /** dashboard number tile */
    LinearLayout statTile(String num, String label, int fg, int bg) {
        LinearLayout t = vcol();
        t.setPadding(dp(14), dp(12), dp(10), dp(12));
        t.setBackground(round(bg, 20));
        TextView n = text(num, 24, fg, true);
        t.addView(n);
        TextView l = oneLine(text(label, 12, fg, false));
        l.setAlpha(0.85f);
        t.addView(l);
        return t;
    }

    /** section title with optional trailing action */
    LinearLayout section(CharSequence title, View trailing) {
        LinearLayout h = hrow();
        h.setPadding(dp(4), dp(20), 0, dp(8));
        View bar = new View(c);   // gradient tick before the label, like .home-label on the web
        bar.setBackground(grad(2));
        h.addView(bar, new LinearLayout.LayoutParams(dp(14), dp(3)));
        TextView t = text(title, 15, onSurface, true);
        t.setPadding(dp(10), 0, 0, 0);
        h.addView(t, weight1());
        if (trailing != null) h.addView(trailing);
        return h;
    }

    /** list item: leading icon in a tonal circle · title/subtitle · trailing */
    LinearLayout listRow(int iconRes, int fg, int bg, CharSequence title, CharSequence sub, View trailing) {
        LinearLayout r = hrow();
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setMinimumHeight(dp(60));
        r.setBackground(ripple(round(0x00000000, 16), 16));
        if (iconRes != 0) {
            android.widget.FrameLayout ic = new android.widget.FrameLayout(c);
            ic.setBackground(oval(bg));
            ic.addView(icon(iconRes, fg, 20), new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        LinearLayout tc = vcol();
        tc.setPadding(dp(iconRes != 0 ? 14 : 4), 0, dp(8), 0);
        tc.addView(oneLine(text(title, 15, onSurface, true)));
        if (sub != null && sub.length() > 0) tc.addView(oneLine(text(sub, 13, onSurfaceVariant, false)));
        r.addView(tc, weight1());
        if (trailing != null) r.addView(trailing);
        return r;
    }

    /** centred empty state */
    LinearLayout emptyState(int iconRes, CharSequence title, CharSequence sub) {
        LinearLayout e = vcol();
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        e.setPadding(dp(24), dp(32), dp(24), dp(32));
        e.setBackground(glass(24));
        e.addView(icon(iconRes, primary, 40));
        TextView t = text(title, 17, onSurface, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(4));
        e.addView(t);
        if (sub != null) {
            TextView s = text(sub, 14, onSurfaceVariant, false);
            s.setGravity(Gravity.CENTER);
            e.addView(s);
        }
        return e;
    }

    /** small counter badge (nav bar) */
    TextView badge(int n) {
        TextView t = text(n > 99 ? "99+" : String.valueOf(n), 10, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(16));
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setBackground(round(dark ? 0xFFE5534B : 0xFFBA1A1A, 8));
        return t;
    }

    /** square icon action tile (host detail) */
    LinearLayout actionTile(int iconRes, CharSequence label) {
        LinearLayout t = vcol();
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(14), dp(4), dp(12));
        t.setBackground(ripple(round(secondaryContainer, 20), 20));
        t.addView(icon(iconRes, onSecondaryContainer, 24));
        TextView l = oneLine(text(label, 12, onSecondaryContainer, true));
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(6), 0, 0);
        t.addView(l);
        return t;
    }

    /** small icon + label pill button (card quick actions); primary = gradient fill */
    LinearLayout actionChip(int iconRes, CharSequence label, boolean primaryStyle) {
        LinearLayout r = hrow();
        r.setGravity(Gravity.CENTER);
        r.setPadding(dp(10), dp(7), dp(12), dp(7));
        int fg = primaryStyle ? onPrimary : onSurface;
        r.setBackground(ripple(primaryStyle ? grad(12) : stroke(containerHigh, 12, outlineVariant), 12));
        r.addView(icon(iconRes, primaryStyle ? onPrimary : primary, 16));
        TextView t = oneLine(text(label, 13, fg, true));
        t.setPadding(dp(6), 0, 0, 0);
        r.addView(t);
        return r;
    }

    void haptic(View v) {
        try { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); } catch (Exception ignored) {}
    }

    /**
     * bottom sheet: a dialog pinned to the bottom with a drag handle and
     * rounded top corners; content is scrollable up to ~88% of the screen
     */
    android.app.Dialog sheet(Activity a, View content) {
        android.app.Dialog d = new android.app.Dialog(a, dark
                ? android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);
        LinearLayout box = vcol();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(container);
        float r = dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(0, dp(10), 0, dp(12));
        View handle = new View(c);
        handle.setBackground(round(outlineVariant, 2));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(32), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(10);
        box.addView(handle, hlp);
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.addView(content);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.88f);
        box.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (box.getHeight() > maxH && w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, maxH);
        });
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    /** simple action list inside a sheet */
    android.app.Dialog menuSheet(Activity a, CharSequence title, String[] labels, int[] icons, java.util.function.IntConsumer pick) {
        LinearLayout col = vcol();
        col.setPadding(dp(12), 0, dp(12), dp(4));
        if (title != null) {
            TextView t = oneLine(text(title, 16, onSurface, true));
            t.setPadding(dp(12), 0, dp(12), dp(8));
            col.addView(t);
        }
        android.app.Dialog[] ref = new android.app.Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout r = hrow();
            r.setPadding(dp(12), dp(14), dp(12), dp(14));
            r.setBackground(ripple(round(0x00000000, 16), 16));
            if (icons != null && icons[i] != 0) {
                r.addView(icon(icons[i], onSurfaceVariant, 22));
                r.addView(new View(c), new LinearLayout.LayoutParams(dp(16), 1));
            }
            r.addView(text(labels[i], 15, onSurface, false), weight1());
            r.setOnClickListener(v -> { ref[0].dismiss(); pick.accept(idx); });
            col.addView(r);
        }
        ref[0] = sheet(a, col);
        return ref[0];
    }

    static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 45) return "just now";
        if (s < 3600) return (s / 60 == 0 ? 1 : s / 60) + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        return s / 86400 + "d ago";
    }
}
