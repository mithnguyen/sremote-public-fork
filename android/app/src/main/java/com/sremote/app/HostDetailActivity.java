package com.sremote.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native host page, laid out like the web UI's sidebar: machine info + quick
 * actions, then every project (git branch, changes, its sessions, terminal /
 * files / diff), standalone sessions and the dev servers listening on the box.
 */
public class HostDetailActivity extends Activity {
    private static final long POLL_MS = 5_000, SLOW_POLL_MS = 30_000;

    private Ui ui;
    private HostStore store;
    private Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;

    private Api.Info info;
    private JSONArray rest, workspaces, ports;
    private final Map<String, JSONObject> git = new HashMap<>();   // ws id → /api/git
    private final Set<String> expanded = new HashSet<>();
    private long lastSlow;

    private TextView titleView, statusView;
    private View statusDot;
    private LinearLayout infoBox, wsBox, soloBox, portBox;
    private TextView wsCount;

    private final Runnable onBus = this::render;
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            load(false);
            main.postDelayed(this, POLL_MS);
        }
    };

    private int dp(float v) { return ui.dp(v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new HostStore(this);
        host = store.find(getIntent().getStringExtra(HostActivity.EXTRA_HOST_ID));
        if (host == null) { finish(); return; }
        ui = new Ui(this);
        ui.applyWindow(this, ui.surface);
        build();
    }

    @Override protected void onStart() { super.onStart(); LockGate.onStart(); }
    @Override protected void onStop() { LockGate.onStop(); super.onStop(); }

    @Override protected void onResume() {
        super.onResume();
        LockGate.check(this);
        Host fresh = store.find(host.id);
        if (fresh == null) { finish(); return; }
        host = fresh;
        titleView.setText(host.name);
        resumed = true;
        StateBus.listen(onBus);
        main.removeCallbacks(poller);
        main.post(poller);
    }

    @Override protected void onPause() {
        resumed = false;
        StateBus.unlisten(onBus);
        main.removeCallbacks(poller);
        super.onPause();
    }

    // ---------------- layout ----------------
    private void build() {
        LinearLayout root = ui.vcol();
        root.setBackground(ui.backdrop());

        LinearLayout bar = ui.hrow();
        bar.setPadding(dp(2), dp(4), dp(2), dp(4));
        ImageView back = ui.iconButton(R.drawable.ic_back, ui.onSurface);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        LinearLayout tc = ui.vcol();
        titleView = ui.oneLine(ui.text(host.name, 18, ui.onSurface, true));
        tc.addView(titleView);
        LinearLayout st = ui.hrow();
        statusDot = ui.dot(ui.outline, 7);
        st.addView(statusDot);
        statusView = ui.oneLine(ui.text(host.hostPort(), 12, ui.onSurfaceVariant, false));
        statusView.setPadding(dp(6), 0, 0, 0);
        st.addView(statusView);
        tc.addView(st);
        LinearLayout.LayoutParams tlp = Ui.weight1();
        tlp.setMargins(dp(4), 0, 0, 0);
        bar.addView(tc, tlp);
        ImageView refresh = ui.iconButton(R.drawable.ic_refresh, ui.onSurfaceVariant);
        refresh.setContentDescription("Refresh");
        refresh.setOnClickListener(v -> { ui.haptic(v); load(true); });
        bar.addView(refresh);
        ImageView more = ui.iconButton(R.drawable.ic_more, ui.onSurfaceVariant);
        more.setContentDescription("More");
        more.setOnClickListener(v -> Actions.hostMenu(this, host, () -> {
            Host f = store.find(host.id);
            if (f == null) finish(); else { host = f; titleView.setText(host.name); render(); }
        }));
        bar.addView(more);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = ui.vcol();
        body.setPadding(dp(16), dp(4), dp(16), dp(40));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        infoBox = ui.card(ui.container);
        infoBox.setPadding(dp(18), dp(18), dp(18), dp(18));
        infoBox.setBackground(ui.glass(28));
        body.addView(infoBox);

        // quick actions: the four things people open a host for
        LinearLayout acts = ui.hrow();
        acts.setPadding(0, dp(2), 0, 0);
        addAction(acts, R.drawable.ic_terminal, "Terminal", true, v -> Actions.openTerminal(this, host, null));
        addAction(acts, R.drawable.ic_desktop, "Desktop", false, v -> Actions.openScreen(this, host));
        addAction(acts, R.drawable.ic_folder, "Files", false, v -> Actions.openFiles(this, host, null, null, null));
        addAction(acts, R.drawable.ic_add, "New", false, v -> Actions.newSession(this, host, null));
        body.addView(acts);

        wsCount = ui.text("", 12, ui.onSurfaceVariant, false);
        body.addView(ui.section("Projects", wsCount));
        wsBox = ui.vcol();
        body.addView(wsBox);

        TextView newS = ui.textButton("New");
        newS.setOnClickListener(v -> Actions.newSession(this, host, null));
        body.addView(ui.section("Standalone sessions", newS));
        soloBox = ui.vcol();
        body.addView(soloBox);

        TextView portsRefresh = ui.textButton("Scan");
        portsRefresh.setOnClickListener(v -> loadSlow());
        body.addView(ui.section("Dev servers", portsRefresh));
        portBox = ui.vcol();
        body.addView(portBox);

        setContentView(root);
        render();
    }

    private void addAction(LinearLayout row, int icon, String label, boolean primary, View.OnClickListener l) {
        LinearLayout t = ui.vcol();
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(14), dp(4), dp(12));
        t.setBackground(ui.ripple(primary ? ui.grad(20) : ui.glass(20), 20));
        t.addView(ui.icon(icon, primary ? ui.onPrimary : ui.primary, 24));
        TextView lb = ui.oneLine(ui.text(label, 12, primary ? ui.onPrimary : ui.onSurface, true));
        lb.setGravity(Gravity.CENTER);
        lb.setPadding(0, dp(6), 0, 0);
        t.addView(lb);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(row.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
        row.addView(t, lp);
    }

    // ---------------- data ----------------
    private void load(boolean force) {
        Api.info(host, (i, e) -> {
            info = i;
            if (i != null && i.online) {
                Api.sessions(host, (a, e2) -> { if (a != null) rest = a; render(); });
                Api.workspaces(host, (w, e3) -> {
                    if (w != null) workspaces = w;
                    if (force || System.currentTimeMillis() - lastSlow > SLOW_POLL_MS) loadSlow();
                    render();
                });
            }
            render();
        });
    }

    /** git status per project + listening ports: shell-outs on the host, so polled rarely */
    private void loadSlow() {
        lastSlow = System.currentTimeMillis();
        Api.devports(host, (p, e) -> { if (p != null) ports = p; render(); });
        if (workspaces == null) return;
        for (int i = 0; i < workspaces.length(); i++) {
            JSONObject w = workspaces.optJSONObject(i);
            if (w == null) continue;
            String id = w.optString("id");
            Api.git(host, "ws:" + id, (g, e) -> { if (g != null) { git.put(id, g); render(); } });
        }
    }

    // ---------------- render ----------------
    private void render() {
        if (infoBox == null) return;
        boolean online = info != null && info.online;
        Boolean wsUp = StateBus.isConnected(host.id);

        int dot; String txt;
        if (info == null && wsUp == null) { dot = ui.outline; txt = "Checking…"; }
        else if (online || Boolean.TRUE.equals(wsUp)) { dot = ui.ok; txt = "Online" + (online ? " · " + info.ms + " ms" : ""); }
        else { dot = ui.err; txt = "Offline · " + host.hostPort(); }
        statusDot.setBackground(ui.oval(dot));
        statusView.setText(txt);

        renderInfo(online);

        List<SessionItem> items = SessionItem.merge(host, rest);
        items.sort((x, y) -> x.rank() != y.rank() ? x.rank() - y.rank() : Long.compare(y.since, x.since));
        Set<String> wsIds = new HashSet<>();
        if (workspaces != null) for (int i = 0; i < workspaces.length(); i++) {
            JSONObject w = workspaces.optJSONObject(i);
            if (w != null) wsIds.add(w.optString("id"));
        }

        // projects
        wsBox.removeAllViews();
        wsCount.setText(workspaces != null && workspaces.length() > 0 ? String.valueOf(workspaces.length()) : "");
        if (workspaces == null) wsBox.addView(Actions.hint(ui, online || info == null ? "Loading…" : "Unavailable while offline"));
        else if (workspaces.length() == 0) wsBox.addView(Actions.hint(ui, "No projects yet — add a folder from the terminal's Home screen."));
        else for (int i = 0; i < workspaces.length(); i++) {
            JSONObject w = workspaces.optJSONObject(i);
            if (w == null) continue;
            List<SessionItem> mine = new ArrayList<>();
            for (SessionItem s : items) if (w.optString("id").equals(s.workspace)) mine.add(s);
            wsBox.addView(projectCard(w, mine));
        }

        // sessions with no (or a deleted) project
        soloBox.removeAllViews();
        int solo = 0;
        for (SessionItem s : items) {
            if (s.workspace != null && wsIds.contains(s.workspace)) continue;
            soloBox.addView(Actions.sessionRow(this, ui, s, false, () -> load(false)));
            solo++;
        }
        if (solo == 0) soloBox.addView(Actions.hint(ui, rest == null && info == null ? "Loading…" : "None — sessions started outside a project show up here."));
        if (!StateBus.monitorRunning && !items.isEmpty()) {
            TextView h = Actions.hint(ui, "Turn on background monitoring for live states (running, needs input, done).");
            h.setPadding(dp(4), dp(10), dp(4), 0);
            soloBox.addView(h);
        }

        renderPorts(online);
    }

    private void renderInfo(boolean online) {
        infoBox.removeAllViews();
        LinearLayout top = ui.hrow();
        top.addView(ui.hostAvatar(host, 56));
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(16), 0, 0, 0);
        JSONObject in = online ? info.info : null;
        tc.addView(ui.oneLine(ui.text(in != null ? in.optString("hostname", host.name) : host.name, 20, ui.onSurface, true)));
        tc.addView(ui.oneLine(ui.mono(host.hostPort(), 12, ui.onSurfaceVariant)));
        top.addView(tc, Ui.weight1());
        if (info != null) top.addView(online ? ui.pill(info.ms + " ms", ui.ok, ui.okContainer) : ui.pill("Offline", ui.err, ui.errContainer));
        infoBox.addView(top);
        if (in != null) {
            LinearLayout chips = ui.hrow();
            chips.setPadding(0, dp(14), 0, 0);
            String plat = in.optString("platform");
            if (!plat.isEmpty()) chips.addView(ui.pill(platformLabel(plat), ui.onPrimaryContainer, ui.primaryContainer), ui.margins(0, 0, 6, 0));
            String ts = in.optString("tailscaleIp");
            if (!ts.isEmpty()) chips.addView(ui.pill(ts, ui.onSurfaceVariant, ui.containerHigh), ui.margins(0, 0, 6, 0));
            JSONArray sh = in.optJSONArray("shells");
            if (sh != null && sh.length() > 0) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < sh.length(); i++) l.add(sh.optString(i));
                chips.addView(ui.pill(String.join(" · ", l), ui.onSurfaceVariant, ui.containerHigh));
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(chips);
            infoBox.addView(hs);
        } else if (info != null) {
            TextView t = ui.text(Tailscale.up(this)
                            ? "Can't reach the S-remote server. Check that the PC is awake and the server is running."
                            : "Tailscale is off on this phone — turn it on to reach " + host.name + ".",
                    13, ui.onSurfaceVariant, false);
            t.setPadding(0, dp(12), 0, 0);
            infoBox.addView(t);
            if (!Tailscale.up(this) && Tailscale.installed(this)) {
                TextView c = ui.filledButton("Connect Tailscale");
                c.setOnClickListener(v -> Actions.connectTailscale(this, ok -> load(true)));
                infoBox.addView(c, ui.margins(0, 12, 0, 0));
            }
        }
        if (!host.notify) {
            TextView m = ui.text("Notifications muted for this host", 12, ui.warn, true);
            m.setPadding(0, dp(10), 0, 0);
            infoBox.addView(m);
        }
    }

    /** one project: header, git + state chips, and (expanded) its sessions + actions */
    private View projectCard(JSONObject w, List<SessionItem> mine) {
        String id = w.optString("id"), name = w.optString("name"), path = w.optString("path");
        boolean open = expanded.contains(id);
        LinearLayout card = ui.card(ui.container);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));

        int live = 0, ask = 0, run = 0, busy = 0;
        for (SessionItem s : mine) {
            if (s.exited) continue;
            live++;
            if (s.state == Detector.State.QUESTION) ask++;
            else if (s.state == Detector.State.RUNNING) run++;
            if (!s.live && s.busy()) busy++;
        }

        LinearLayout top = ui.hrow();
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(ui.gradSoft(14));
        ic.addView(ui.icon(R.drawable.ic_folder, ui.primary, 20), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        top.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(12), 0, dp(6), 0);
        tc.addView(ui.oneLine(ui.text(name, 15, ui.onSurface, true)));
        TextView p = ui.mono(path, 11, ui.onSurfaceVariant);
        p.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tc.addView(p);
        top.addView(tc, Ui.weight1());
        if (ask > 0) top.addView(ui.pill(ask + " waiting", ui.warn, ui.warnContainer));
        else if (run > 0) top.addView(ui.pill(run + " running", ui.onPrimaryContainer, ui.primaryContainer));
        else if (live > 0) top.addView(ui.pill(live + " live", busy > 0 ? ui.ok : ui.onSurfaceVariant, busy > 0 ? ui.okContainer : ui.containerHigh));
        ImageView chev = ui.icon(R.drawable.ic_expand, ui.onSurfaceVariant, 20);
        chev.setRotation(open ? 180 : 0);
        top.addView(chev, ui.margins(6, 0, 0, 0));
        card.addView(top);

        // git chip
        JSONObject g = git.get(id);
        if (g != null && g.optBoolean("isRepo")) {
            LinearLayout gr = ui.hrow();
            gr.setPadding(dp(52), dp(8), 0, 0);
            int changed = g.optInt("changed");
            String br = g.isNull("branch") ? "no commits" : g.optString("branch");
            LinearLayout chip = ui.hrow();
            chip.setPadding(dp(8), dp(4), dp(10), dp(4));
            chip.setBackground(ui.ripple(ui.round(changed > 0 ? ui.warnContainer : ui.containerHigh, 10), 10));
            chip.addView(ui.icon(R.drawable.ic_git, changed > 0 ? ui.warn : ui.onSurfaceVariant, 14));
            TextView gt = ui.oneLine(ui.text(br + (changed > 0 ? "  ·  " + changed + " changed" : "  ·  clean"), 12,
                    changed > 0 ? ui.warn : ui.onSurfaceVariant, true));
            gt.setPadding(dp(6), 0, 0, 0);
            chip.addView(gt);
            chip.setOnClickListener(v -> GitDiff.show(this, host, "ws:" + id, name));
            gr.addView(chip);
            card.addView(gr);
        }

        if (open) {
            for (SessionItem s : mine) card.addView(Actions.sessionRow(this, ui, s, false, () -> load(false)));
            if (mine.isEmpty()) {
                TextView h = Actions.hint(ui, "No sessions in this project.");
                h.setPadding(dp(4), dp(10), 0, 0);
                card.addView(h);
            }
            LinearLayout acts = ui.hrow();
            acts.setPadding(0, dp(12), 0, 0);
            View o = ui.actionChip(R.drawable.ic_terminal, "Open", true);
            o.setOnClickListener(v -> Actions.openTerminal(this, host, "ws=" + id));
            acts.addView(o, ui.margins(0, 0, 8, 0));
            View n = ui.actionChip(R.drawable.ic_add, "New", false);
            n.setOnClickListener(v -> Actions.newSession(this, host, id));
            acts.addView(n, ui.margins(0, 0, 8, 0));
            View f = ui.actionChip(R.drawable.ic_folder, "Files", false);
            f.setOnClickListener(v -> Actions.openFiles(this, host, "ws:" + id, "", name));
            acts.addView(f, ui.margins(0, 0, 8, 0));
            if (g != null && g.optBoolean("isRepo")) {
                View d = ui.actionChip(R.drawable.ic_git, "Changes", false);
                d.setOnClickListener(v -> GitDiff.show(this, host, "ws:" + id, name));
                acts.addView(d);
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(acts);
            card.addView(hs);
        }

        card.setOnClickListener(v -> {
            if (!expanded.remove(id)) expanded.add(id);
            render();
        });
        card.setOnLongClickListener(v -> {
            ui.haptic(v);
            ui.menuSheet(this, name, new String[]{"Open in terminal", "New session here", "Files", "Changes (git diff)"},
                    new int[]{R.drawable.ic_terminal, R.drawable.ic_add, R.drawable.ic_folder, R.drawable.ic_git},
                    k -> {
                        switch (k) {
                            case 0: Actions.openTerminal(this, host, "ws=" + id); break;
                            case 1: Actions.newSession(this, host, id); break;
                            case 2: Actions.openFiles(this, host, "ws:" + id, "", name); break;
                            default: GitDiff.show(this, host, "ws:" + id, name);
                        }
                    }).show();
            return true;
        });
        return card;
    }

    /** same skip lists as the web's dev-server panel: Windows services aren't dev servers */
    private static final Set<String> SKIP_PROC = new HashSet<>(java.util.Arrays.asList(
            "System", "svchost.exe", "services.exe", "lsass.exe", "spoolsv.exe", "wininit.exe", "Idle", "Registry"));
    private static final Set<Integer> SKIP_PORT = new HashSet<>(java.util.Arrays.asList(135, 139, 445, 3389));

    /** filtered, one entry per port (a port bound on several addresses → prefer the reachable one) */
    private List<JSONObject> devPorts() {
        Map<Integer, JSONObject> by = new java.util.TreeMap<>();
        for (int i = 0; i < ports.length(); i++) {
            JSONObject p = ports.optJSONObject(i);
            if (p == null) continue;
            int port = p.optInt("port");
            String proc = p.isNull("proc") ? "" : p.optString("proc");
            if (SKIP_PORT.contains(port) || SKIP_PROC.contains(proc)) continue;
            JSONObject old = by.get(port);
            if (old == null || (old.optBoolean("loopback") && !p.optBoolean("loopback"))) by.put(port, p);
        }
        return new ArrayList<>(by.values());
    }

    private void renderPorts(boolean online) {
        portBox.removeAllViews();
        if (ports == null) { portBox.addView(Actions.hint(ui, online ? "Scanning…" : "Unavailable while offline")); return; }
        List<JSONObject> list = devPorts();
        if (list.isEmpty()) { portBox.addView(Actions.hint(ui, "Nothing listening — start a dev server and tap Scan.")); return; }
        String ip = info != null && info.online ? info.info.optString("tailscaleIp") : "";
        if (ip.isEmpty()) ip = host.hostPort().replaceFirst(":\\d+$", "");
        LinearLayout g = ui.vcol();
        g.setBackground(ui.glass(22));
        g.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (JSONObject p : list) {
            int port = p.optInt("port");
            boolean local = p.optBoolean("loopback");
            String proc = p.isNull("proc") ? "" : p.optString("proc");
            View trail = local ? ui.pill("PC only", ui.onSurfaceVariant, ui.containerHigh)
                    : ui.icon(R.drawable.ic_open, ui.primary, 20);
            LinearLayout r = ui.listRow(R.drawable.ic_globe, local ? ui.onSurfaceVariant : ui.ok, local ? ui.containerHigh : ui.okContainer,
                    ":" + port + (proc.isEmpty() ? "" : "  " + proc.replaceFirst("(?i)\\.exe$", "")),
                    p.optString("addr") + (p.optInt("pid") > 0 ? " · pid " + p.optInt("pid") : ""), trail);
            String url = "http://" + ip + ":" + port;
            if (local) r.setOnClickListener(v -> Actions.toast(this, "Bound to " + p.optString("addr") + " — only reachable on the PC itself"));
            else r.setOnClickListener(v -> Actions.openUrl(this, url));
            g.addView(r);
        }
        portBox.addView(g);
    }

    private static String platformLabel(String p) {
        switch (p) {
            case "win32": return "Windows";
            case "darwin": return "macOS";
            case "linux": return "Linux";
            default: return p;
        }
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
