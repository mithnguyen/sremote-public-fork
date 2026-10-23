package com.sremote.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** First-run tour: Tailscale → first host → notifications. */
public class OnboardingActivity extends Activity {
    private static final int STEPS = 3;

    private Ui ui;
    private HostStore store;
    private int step = 0;
    private LinearLayout body, dots;
    private TextView next, back;

    private int dp(float v) { return ui.dp(v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        store = new HostStore(this);
        ui.applyWindow(this, ui.surface);

        LinearLayout root = ui.vcol();
        root.setBackground(ui.backdrop());
        root.setPadding(dp(24), dp(16), dp(24), dp(20));

        LinearLayout top = ui.hrow();
        dots = ui.hrow();
        top.addView(dots, Ui.weight1());
        TextView skip = ui.textButton("Skip");
        skip.setOnClickListener(v -> finishTour());
        top.addView(skip);
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        body = ui.vcol();
        body.setGravity(Gravity.CENTER_VERTICAL);
        sv.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = ui.hrow();
        back = ui.textButton("Back");
        back.setOnClickListener(v -> { if (step > 0) { step--; render(); } });
        nav.addView(back);
        nav.addView(new View(this), Ui.weight1());
        next = ui.filledButton("Next");
        next.setOnClickListener(v -> { if (step < STEPS - 1) { step++; render(); } else finishTour(); });
        nav.addView(next);
        root.addView(nav);

        setContentView(root);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        render(); // Tailscale / permission state may have changed while away
    }

    @Override public void onBackPressed() {
        if (step > 0) { step--; render(); } else finishTour();
    }

    private void finishTour() {
        store.setOnboarded(true);
        finish();
    }

    private void render() {
        if (body == null) return;
        dots.removeAllViews();
        for (int i = 0; i < STEPS; i++) {
            View d = new View(this);
            d.setBackground(i == step ? ui.grad(4) : ui.round(ui.outlineVariant, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == step ? 24 : 8), dp(8));
            lp.setMargins(0, 0, dp(6), 0);
            dots.addView(d, lp);
        }
        back.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(step == STEPS - 1 ? "Done" : "Next");
        body.removeAllViews();
        switch (step) {
            case 0: stepTailscale(); break;
            case 1: stepHost(); break;
            default: stepNotify();
        }
    }

    private void hero(int icon, int fg, int bg, String title, String text) {
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(ui.gradSoft(44));
        ic.addView(ui.icon(icon, ui.primary, 40), new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(88), dp(88));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(ic, ilp);
        TextView t = ui.text(title, 26, ui.onSurface, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(24), 0, dp(8));
        body.addView(t);
        TextView s = ui.text(text, 15, ui.onSurfaceVariant, false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, 0, 0, dp(24));
        body.addView(s);
    }

    private View centered(View v) {
        LinearLayout r = ui.hrow();
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.addView(v);
        return r;
    }

    // ---------- 1. Tailscale ----------
    private void stepTailscale() {
        hero(R.drawable.ic_shield, ui.onPrimaryContainer, ui.primaryContainer, "Private by design",
                "S-remote talks to your computers directly over Tailscale — no ports opened to the internet, no cloud relay.");
        boolean inst = Tailscale.installed(this);
        String ip = inst ? Tailscale.tailnetIp(this) : null;
        boolean up = ip != null || (inst && Tailscale.vpnUp(this));
        TextView pill = inst
                ? (up ? ui.pill("Connected" + (ip != null ? " · " + ip : ""), ui.ok, ui.okContainer)
                      : ui.pill("Installed · not connected", ui.warn, ui.warnContainer))
                : ui.pill("Not installed", ui.err, ui.errContainer);
        body.addView(centered(pill));
        if (!up) {
            TextView b = inst ? ui.filledButton("Connect Tailscale") : ui.tonalButton("Get Tailscale on Google Play");
            b.setOnClickListener(v -> {
                if (inst) Actions.connectTailscale(this, ok -> render());
                else Tailscale.openPlayStore(this);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(16);
            body.addView(b, lp);
        }
    }

    // ---------- 2. first host ----------
    private void stepHost() {
        hero(R.drawable.ic_hosts, ui.onSecondaryContainer, ui.secondaryContainer, "Add your computer",
                "Install S-remote on a Windows PC (one PowerShell line, see the GitHub page), then enter its Tailscale IP here.");
        int n = store.list().size();
        if (n > 0) {
            body.addView(centered(ui.pill(n + " host" + (n == 1 ? "" : "s") + " added", ui.ok, ui.okContainer)));
            TextView more = ui.textButton("Add another");
            more.setOnClickListener(v -> Actions.hostEditor(this, null, h -> render()));
            body.addView(centered(more));
            return;
        }
        EditText addr = ui.input("Tailscale IP, e.g. 100.64.1.2");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        body.addView(addr);
        TextView status = ui.text("", 13, ui.onSurfaceVariant, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, dp(10));
        body.addView(status);
        TextView add = ui.filledButton("Connect");
        add.setOnClickListener(v -> {
            String url = Host.normalizeUrl(addr.getText().toString());
            if (url == null) { status.setTextColor(ui.err); status.setText("Enter the machine's Tailscale IP"); return; }
            status.setTextColor(ui.onSurfaceVariant);
            status.setText("Connecting to " + url + " …");
            Api.info(url, (info, err) -> {
                if (info != null && info.online) {
                    store.add(info.info.optString("hostname", "host"), url);
                    Actions.resyncMonitor(this);
                    Actions.updateShortcuts(this);
                    step = 2;
                    render();
                } else {
                    status.setTextColor(ui.warn);
                    status.setText("No S-remote server answers at " + url + ". Check the IP, that Tailscale is on, and that the server runs on that PC.");
                }
            });
        });
        body.addView(centered(add));
    }

    // ---------- 3. notifications ----------
    private void stepNotify() {
        hero(R.drawable.ic_bell, ui.onTertiaryContainer, ui.tertiaryContainer, "Know when it's your turn",
                "Get a notification when a build finishes or an agent asks a question — and answer it right from the notification.");
        boolean on = store.monitorEnabled();
        if (on) body.addView(centered(ui.pill("Monitoring is on", ui.ok, ui.okContainer)));
        else {
            TextView b = ui.filledButton("Turn on notifications");
            b.setOnClickListener(v -> {
                store.setMonitorEnabled(true);
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
                MonitorService.start(this);
                render();
            });
            body.addView(centered(b));
            TextView later = ui.text("You can change this any time in Settings.", 13, ui.onSurfaceVariant, false);
            later.setGravity(Gravity.CENTER);
            later.setPadding(0, dp(12), 0, 0);
            body.addView(later);
        }
    }
}
