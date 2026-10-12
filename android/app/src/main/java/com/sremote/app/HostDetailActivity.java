package com.sremote.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native host page: machine info, quick actions, workspaces and sessions. */
public class HostDetailActivity extends Activity {
    private static final long POLL_MS = 5_000;

    private Ui ui;
    private HostStore store;
    private Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;

    private Api.Info info;
    private JSONArray rest, workspaces;
    private TextView titleView, statusView;
    private View statusDot;
    private LinearLayout infoBox, wsBox, sessBox;

    private final Runnable onBus = this::render;
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            load();
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

    private void build() {
        LinearLayout root = ui.vcol();
        root.setBackgroundColor(ui.surface);

        // toolbar
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
        body.setPadding(dp(16), dp(4), dp(16), dp(32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // hero: avatar + machine info
        infoBox = ui.card(ui.container);
        infoBox.setBackground(ui.round(ui.container, 28));
        body.addView(infoBox);

        // quick actions
        LinearLayout acts = ui.hrow();
        acts.setPadding(0, dp(4), 0, 0);
        addAction(acts, R.drawable.ic_terminal, "Terminal", v -> Actions.openTerminal(this, host, null));
        addAction(acts, R.drawable.ic_desktop, "Desktop", v -> Actions.openTerminal(this, host, "screen"));
        addAction(acts, R.drawable.ic_add, "New session", v -> Actions.newSession(this, host, null));
        addAction(acts, R.drawable.ic_refresh, "Refresh", v -> { ui.haptic(v); load(); });
        body.addView(acts);

        body.addView(ui.section("Workspaces", null));
        wsBox = ui.vcol();
        body.addView(wsBox);

        TextView newS = ui.textButton("New");
        newS.setOnClickListener(v -> Actions.newSession(this, host, null));
        body.addView(ui.section("Sessions", newS));
        sessBox = ui.vcol();
        body.addView(sessBox);

        setContentView(root);
        render();
    }

    private void addAction(LinearLayout row, int icon, String label, View.OnClickListener l) {
        LinearLayout t = ui.actionTile(icon, label);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(row.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
        row.addView(t, lp);
    }

    private void load() {
        Api.info(host, (i, e) -> {
            info = i;
            if (i != null && i.online) {
                Api.sessions(host, (a, e2) -> { rest = a; render(); });
                Api.workspaces(host, (w, e3) -> { workspaces = w; render(); });
            }
            render();
        });
    }

    private void render() {
        if (infoBox == null) return;
        boolean online = info != null && info.online;
        Boolean wsUp = StateBus.isConnected(host.id);

        // toolbar status
        int dot; String txt;
        if (info == null && wsUp == null) { dot = ui.outline; txt = "Checking…"; }
        else if (online || Boolean.TRUE.equals(wsUp)) { dot = ui.ok; txt = "Online" + (online ? " · " + info.ms + " ms" : ""); }
        else { dot = ui.err; txt = "Offline · " + host.hostPort(); }
        statusDot.setBackground(ui.oval(dot));
        statusView.setText(txt);

        // hero card
        infoBox.removeAllViews();
        LinearLayout top = ui.hrow();
        top.addView(ui.hostAvatar(host, 56));
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(16), 0, 0, 0);
        JSONObject in = online ? info.info : null;
        tc.addView(ui.oneLine(ui.text(in != null ? in.optString("hostname", host.name) : host.name, 20, ui.onSurface, false)));
        tc.addView(ui.oneLine(ui.text(host.hostPort(), 13, ui.onSurfaceVariant, false)));
        top.addView(tc, Ui.weight1());
        if (info != null) top.addView(online ? ui.pill(info.ms + " ms", ui.ok, ui.okContainer) : ui.pill("Offline", ui.err, ui.errContainer));
        infoBox.addView(top);
        if (in != null) {
            LinearLayout chips = ui.hrow();
            chips.setPadding(0, dp(14), 0, 0);
            String plat = in.optString("platform");
            if (!plat.isEmpty()) chips.addView(ui.pill(platformLabel(plat), ui.onSecondaryContainer, ui.secondaryContainer), ui.margins(0, 0, 6, 0));
            String ts = in.optString("tailscaleIp");
            if (!ts.isEmpty()) chips.addView(ui.pill(ts, ui.onSurfaceVariant, ui.containerHigh), ui.margins(0, 0, 6, 0));
            JSONArray sh = in.optJSONArray("shells");
            if (sh != null && sh.length() > 0) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < sh.length(); i++) l.add(sh.optString(i));
                chips.addView(ui.oneLine(ui.pill(String.join(" · ", l), ui.onSurfaceVariant, ui.containerHigh)));
            }
            infoBox.addView(chips);
        } else if (info != null) {
            TextView t = ui.text("Can't reach the S-remote server. Check that Tailscale is connected and the server is running on the machine.",
                    13, ui.onSurfaceVariant, false);
            t.setPadding(0, dp(12), 0, 0);
            infoBox.addView(t);
        }
        if (!host.notify) {
            TextView m = ui.text("Notifications muted for this host", 12, ui.warn, true);
            m.setPadding(0, dp(10), 0, 0);
            infoBox.addView(m);
        }

        // sessions grouped by workspace
        List<SessionItem> items = SessionItem.merge(host, rest);
        items.sort((x, y) -> x.rank() != y.rank() ? x.rank() - y.rank() : Long.compare(y.since, x.since));
        Map<String, String> wsNames = new LinkedHashMap<>();
        if (workspaces != null) for (int i = 0; i < workspaces.length(); i++) {
            JSONObject w = workspaces.optJSONObject(i);
            if (w != null) wsNames.put(w.optString("id"), w.optString("name"));
        }

        // workspaces
        wsBox.removeAllViews();
        if (workspaces == null) wsBox.addView(Actions.hint(ui, online || info == null ? "Loading…" : "Unavailable while offline"));
        else if (workspaces.length() == 0) wsBox.addView(Actions.hint(ui, "No workspaces yet — add one from the terminal's Home screen."));
        else {
            LinearLayout g = group();
            for (int i = 0; i < workspaces.length(); i++) {
                JSONObject w = workspaces.optJSONObject(i);
                if (w == null) continue;
                String id = w.optString("id");
                int live = 0, ask = 0;
                for (SessionItem s : items) if (id.equals(s.workspace) && !s.exited) {
                    live++;
                    if (s.state == Detector.State.QUESTION) ask++;
                }
                View trail = ask > 0 ? ui.pill(ask + " waiting", ui.warn, ui.warnContainer)
                        : live > 0 ? ui.pill(live + " live", ui.onSurfaceVariant, ui.containerHigh) : null;
                LinearLayout r = ui.listRow(R.drawable.ic_folder, ui.onTertiaryContainer, ui.tertiaryContainer,
                        w.optString("name"), w.optString("path"), trail);
                r.setOnClickListener(v -> Actions.openTerminal(this, host, "ws=" + id));
                r.setOnLongClickListener(v -> {
                    ui.haptic(v);
                    ui.menuSheet(this, w.optString("name"), new String[]{"Open in terminal", "New session here"},
                            new int[]{R.drawable.ic_terminal, R.drawable.ic_add},
                            k -> { if (k == 0) Actions.openTerminal(this, host, "ws=" + id); else Actions.newSession(this, host, id); }).show();
                    return true;
                });
                g.addView(r);
            }
            wsBox.addView(g);
        }

        sessBox.removeAllViews();
        if (items.isEmpty()) {
            sessBox.addView(ui.emptyState(R.drawable.ic_terminal, rest == null && info == null ? "Loading sessions…" : "No sessions",
                    online ? "Start one with “New session”." : null));
        } else {
            Map<String, List<SessionItem>> groups = new LinkedHashMap<>();
            for (SessionItem s : items) {
                String key = s.workspace != null && wsNames.containsKey(s.workspace) ? wsNames.get(s.workspace)
                        : s.workspace != null ? "Workspace" : "Standalone";
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
            for (Map.Entry<String, List<SessionItem>> e : groups.entrySet()) {
                if (groups.size() > 1) {
                    TextView gl = ui.text(e.getKey(), 12, ui.onSurfaceVariant, true);
                    gl.setPadding(dp(4), dp(10), 0, 0);
                    sessBox.addView(gl);
                }
                for (SessionItem s : e.getValue()) sessBox.addView(Actions.sessionRow(this, ui, s, false, this::load));
            }
            if (!StateBus.monitorRunning) {
                TextView h = Actions.hint(ui, "Turn on background monitoring to see live states (running, needs input, done).");
                h.setPadding(dp(4), dp(10), dp(4), 0);
                sessBox.addView(h);
            }
        }
    }

    private LinearLayout group() {
        LinearLayout g = ui.vcol();
        g.setBackground(ui.round(ui.container, 24));
        g.setPadding(dp(4), dp(4), dp(4), dp(4));
        return g;
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
