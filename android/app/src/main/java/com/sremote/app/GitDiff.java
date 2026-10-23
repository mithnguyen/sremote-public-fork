package com.sremote.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Full-screen, colour-coded `git diff HEAD` for a project (GET /api/gitdiff). */
final class GitDiff {
    private GitDiff() {}

    /** keep the TextView responsive on huge diffs */
    private static final int MAX_LINES = 4000;

    static void show(Activity a, Host h, String base, String title) {
        Ui ui = new Ui(a);
        int dp4 = ui.dp(4);
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout col = ui.vcol();
        col.setBackgroundColor(ui.dark ? 0xFF0D1117 : 0xFFFAFBFD);

        LinearLayout bar = ui.hrow();
        bar.setPadding(ui.dp(2), dp4, ui.dp(2), dp4);
        bar.setBackgroundColor(ui.container);
        ImageView close = ui.iconButton(R.drawable.ic_close, ui.onSurface);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> d.dismiss());
        bar.addView(close);
        LinearLayout tc = ui.vcol();
        tc.addView(ui.oneLine(ui.text("Changes · " + title, 16, ui.onSurface, true)));
        TextView sub = ui.oneLine(ui.text("git diff HEAD on " + h.name, 12, ui.onSurfaceVariant, false));
        tc.addView(sub);
        bar.addView(tc, Ui.weight1());
        ImageView refresh = ui.iconButton(R.drawable.ic_refresh, ui.onSurfaceVariant);
        refresh.setContentDescription("Refresh");
        bar.addView(refresh);
        col.addView(bar);

        TextView body = ui.text("Loading…", 12, ui.onSurface, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(32));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.addView(body);
        ScrollView sv = new ScrollView(a);
        sv.addView(hs);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        d.setContentView(col);
        if (d.getWindow() != null) {
            d.getWindow().setStatusBarColor(ui.container);
            d.getWindow().setNavigationBarColor(ui.surface);
        }
        Runnable load = () -> {
            body.setText("Loading…");
            Api.gitDiff(h, base, (j, err) -> {
                if (!d.isShowing()) return;
                if (j == null) { body.setText("Couldn't load the diff: " + err); return; }
                // keep the leading space: `git diff --stat` aligns its first line with the rest
                String stat = j.optString("stat").replaceFirst("^\\n+", "").replaceFirst("\\s+$", ""), diff = j.optString("diff");
                if (stat.trim().isEmpty() && diff.trim().isEmpty()) { body.setText("Working tree clean — nothing changed since HEAD."); return; }
                body.setText(render(ui, stat, diff, j.optBoolean("truncated")));
                sv.scrollTo(0, 0);
            });
        };
        refresh.setOnClickListener(v -> { ui.haptic(v); load.run(); });
        d.show();
        load.run();
    }

    private static CharSequence render(Ui ui, String stat, String diff, boolean truncated) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        int add = ui.dark ? 0xFF7EE787 : 0xFF116329, addBg = ui.dark ? 0x2A2EA043 : 0x33ACEEBB;
        int del = ui.dark ? 0xFFFFA198 : 0xFF82071E, delBg = ui.dark ? 0x2AF85149 : 0x33FFCECB;
        int hunk = ui.dark ? 0xFF79C0FF : 0xFF0550AE, meta = ui.onSurfaceVariant, file = ui.dark ? 0xFFD2A8FF : 0xFF6639BA;
        if (!stat.trim().isEmpty()) {
            int s = b.length();
            b.append(stat).append("\n\n");
            b.setSpan(new ForegroundColorSpan(meta), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String[] lines = diff.split("\n", -1);
        int n = Math.min(lines.length, MAX_LINES);
        for (int i = 0; i < n; i++) {
            String ln = lines[i];
            int s = b.length();
            if (ln.startsWith("diff --git")) {
                if (s > 0) b.append("\n");
                s = b.length();
                String f = ln.replaceFirst("^diff --git a/(.*) b/.*$", "$1");
                b.append("▌ ").append(f).append('\n');
                b.setSpan(new ForegroundColorSpan(file), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new StyleSpan(Typeface.BOLD), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (ln.startsWith("index ") || ln.startsWith("--- ") || ln.startsWith("+++ ")
                    || ln.startsWith("new file") || ln.startsWith("deleted file") || ln.startsWith("similarity") || ln.startsWith("rename ")) {
                b.append(ln).append('\n');
                b.setSpan(new ForegroundColorSpan(meta), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            b.append(ln).append('\n');
            int e = b.length();
            if (ln.startsWith("@@")) b.setSpan(new ForegroundColorSpan(hunk), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if (ln.startsWith("+")) {
                b.setSpan(new ForegroundColorSpan(add), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new BackgroundColorSpan(addBg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (ln.startsWith("-")) {
                b.setSpan(new ForegroundColorSpan(del), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new BackgroundColorSpan(delBg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (truncated || lines.length > MAX_LINES) {
            int s = b.length();
            b.append("\n… diff truncated — open the project on the PC for the rest\n");
            b.setSpan(new ForegroundColorSpan(ui.warn), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }
}
