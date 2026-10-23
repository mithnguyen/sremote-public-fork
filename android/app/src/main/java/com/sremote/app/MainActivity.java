package com.sremote.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    static final String ACTION_OPEN_HOST = "com.sremote.app.OPEN_HOST";
    /** widget / shortcut → straight into a host's remote desktop */
    static final String ACTION_OPEN_SCREEN = "com.sremote.app.OPEN_SCREEN";
    private static final int REQ_NOTIF = 42;
    private static final long RESCAN_MS = 30_000, POLL_MS = 10_000, RECENT_DONE_MS = 10 * 60_000;
    private static final int TAB_HOME = 0, TAB_SESSIONS = 1, TAB_ACTIVITY = 2, TAB_SETTINGS = 3;

    private HostStore store;
    private Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<Host> hosts = new ArrayList<>();
    private final List<JSONObject> machines = new ArrayList<>();
    private final Map<String, HStat> stats = new HashMap<>();
    private long lastScan = 0;
    private boolean scanning = false, resumed = false, machinesOpen = true;
    private int tab = TAB_HOME;
    private String activityFilter = "all", activityHost = null, sessFilter = "all", sessQuery = "";

    // views
    private FrameLayout content;
    private View fab;
    private final View[] pages = new View[4];
    private final LinearLayout[] navItems = new LinearLayout[4];
    private final FrameLayout[] navBadges = new FrameLayout[4];
    private LinearLayout homeStats, banners, attention, hostList, machineList, sessChips, sessList,
            activityList, activityChips, settingsBody;
    private TextView greeting, machinesStatus;
    private FrameLayout tsSlot;

    /** polled liveness + session list per host */
    private static final class HStat { boolean online; long ms; String platform = ""; JSONArray sessions; }

    /** live state churns while output flows — redraw at most ~1/s, and never the settings form */
    private long lastBusRender;
    private boolean busQueued;
    private final Runnable busRender = () -> {
        busQueued = false;
        lastBusRender = System.currentTimeMillis();
        if (tab != TAB_SETTINGS) renderCurrent(); else renderBadges();
    };
    private final Runnable onBus = () -> {
        if (busQueued) return;
        busQueued = true;
        main.postDelayed(busRender, Math.max(0, 800 - (System.currentTimeMillis() - lastBusRender)));
    };
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            pollHosts();
            main.postDelayed(this, POLL_MS);
        }
    };

    private int dp(float v) { return ui.dp(v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------- lifecycle ----------------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new HostStore(this);
        ui = new Ui(this);
        ui.applyWindow(this, ui.surface);
        getWindow().setNavigationBarColor(navColor());
        buildShell();
        reload();
        selectTab(TAB_HOME);
        handleIntent(getIntent());
        if (hosts.isEmpty() && !store.onboarded()) startActivity(new Intent(this, OnboardingActivity.class));
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    /** launcher shortcut → open that host directly */
    private void handleIntent(Intent i) {
        if (i == null) return;
        boolean screen = ACTION_OPEN_SCREEN.equals(i.getAction());
        if (!screen && !ACTION_OPEN_HOST.equals(i.getAction())) return;
        Host h = store.find(i.getStringExtra(HostActivity.EXTRA_HOST_ID));
        if (h != null) Actions.openTerminal(this, h, screen ? "screen" : null);
    }

    private int navColor() { return ui.dark ? 0xFF0A0E16 : 0xFFFFFFFF; }

    @Override protected void onStart() { super.onStart(); LockGate.onStart(); }
    @Override protected void onStop() { LockGate.onStop(); super.onStop(); }

    @Override protected void onResume() {
        super.onResume();
        LockGate.check(this);
        resumed = true;
        reload();
        if (store.monitorEnabled()) { ensureNotifPermission(); MonitorService.start(this); }
        StateBus.listen(onBus);
        main.removeCallbacks(poller);
        main.post(poller);
        if (tab == TAB_HOME) refreshBanners();
        renderCurrent();
    }

    @Override protected void onPause() {
        resumed = false;
        StateBus.unlisten(onBus);
        main.removeCallbacks(poller);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (tab != TAB_HOME) selectTab(TAB_HOME);
        else super.onBackPressed();
    }

    private void renderCurrent() {
        switch (tab) {
            case TAB_HOME: renderHome(); break;
            case TAB_SESSIONS: renderSessions(); break;
            case TAB_ACTIVITY: renderActivity(); break;
            default: buildSettings();
        }
        renderBadges();
    }

    // ---------------- shell: pages + bottom navigation + FAB ----------------
    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(ui.backdrop());

        LinearLayout col = ui.vcol();
        content = new FrameLayout(this);
        col.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View hair = new View(this); // glass edge above the nav, like the web's borders
        hair.setBackgroundColor(ui.outlineVariant);
        col.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout nav = ui.hrow();
        nav.setBackgroundColor(navColor());
        nav.setPadding(0, dp(10), 0, dp(14));
        String[] labels = {"Home", "Sessions", "Activity", "Settings"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_list, R.drawable.ic_bell, R.drawable.ic_tune};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout it = ui.vcol();
            it.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout pill = new FrameLayout(this);
            ImageView ic = ui.icon(icons[i], ui.onSurfaceVariant, 24);
            pill.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            FrameLayout badge = new FrameLayout(this);
            pill.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP | Gravity.END));
            navBadges[i] = badge;
            it.addView(pill, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView lb = ui.text(labels[i], 12, ui.onSurfaceVariant, true);
            lb.setPadding(0, dp(4), 0, 0);
            lb.setGravity(Gravity.CENTER_HORIZONTAL);
            it.addView(lb);
            it.setOnClickListener(v -> selectTab(idx));
            nav.addView(it, Ui.weight1());
            navItems[i] = it;
        }
        col.addView(nav);
        root.addView(col);

        pages[TAB_HOME] = buildHomePage();
        pages[TAB_SESSIONS] = buildSessionsPage();
        pages[TAB_ACTIVITY] = buildActivityPage();
        pages[TAB_SETTINGS] = buildSettingsPage();
        for (View p : pages) content.addView(p);

        // extended FAB: Add host
        LinearLayout f = ui.hrow();
        f.setPadding(dp(16), dp(14), dp(20), dp(14));
        f.setBackground(ui.ripple(ui.grad(18), 18));
        f.setElevation(dp(8));
        f.addView(ui.icon(R.drawable.ic_add, ui.onPrimary, 24));
        TextView fl = ui.text("Add host", 15, ui.onPrimary, true);
        fl.setPadding(dp(10), 0, 0, 0);
        f.addView(fl);
        f.setOnClickListener(v -> Actions.hostEditor(this, null, h -> { reload(); pollHosts(); }));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(16), dp(96));
        root.addView(f, flp);
        fab = f;

        setContentView(root);
    }

    private void selectTab(int t) {
        tab = t;
        for (int i = 0; i < 4; i++) {
            boolean on = i == t;
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            LinearLayout it = navItems[i];
            FrameLayout pill = (FrameLayout) it.getChildAt(0);
            ImageView ic = (ImageView) pill.getChildAt(0);
            TextView lb = (TextView) it.getChildAt(1);
            pill.setBackground(on ? ui.gradSoft(16) : null);
            ic.setImageTintList(android.content.res.ColorStateList.valueOf(on ? ui.primary : ui.onSurfaceVariant));
            lb.setTextColor(on ? ui.onSurface : ui.onSurfaceVariant);
        }
        fab.setVisibility(t == TAB_HOME ? View.VISIBLE : View.GONE);
        if (t == TAB_HOME) refreshBanners();
        renderCurrent();
    }

    /** red counters on the nav: sessions waiting for input */
    private void renderBadges() {
        int ask = 0;
        for (Host h : hosts) for (StateBus.Sess s : StateBus.sessions(h.id)) if (s.state == Detector.State.QUESTION) ask++;
        for (int i = 0; i < 4; i++) {
            navBadges[i].removeAllViews();
            if (i == TAB_SESSIONS && ask > 0) navBadges[i].addView(ui.badge(ask));
        }
    }

    private ScrollView page(LinearLayout body) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        body.setPadding(dp(16), dp(12), dp(16), dp(120));
        sv.addView(body);
        return sv;
    }

    private LinearLayout header(String title, View... trailing) {
        LinearLayout h = ui.hrow();
        h.setPadding(dp(4), dp(16), 0, dp(4));
        h.addView(ui.title(title), Ui.weight1());
        for (View t : trailing) if (t != null) h.addView(t);
        return h;
    }

    private List<SessionItem> allSessions() {
        List<SessionItem> out = new ArrayList<>();
        for (Host h : hosts) {
            HStat st = stats.get(h.id);
            out.addAll(SessionItem.merge(h, st != null ? st.sessions : null));
        }
        out.sort((a, b) -> a.rank() != b.rank() ? a.rank() - b.rank() : Long.compare(b.since, a.since));
        return out;
    }

    // ================= HOME =================
    private View buildHomePage() {
        LinearLayout body = ui.vcol();
        ImageView refresh = ui.iconButton(R.drawable.ic_refresh, ui.onSurfaceVariant);
        refresh.setContentDescription("Refresh");
        refresh.setOnClickListener(v -> { ui.haptic(v); pollHosts(); scanTailnet(); refreshBanners(); });
        // brand header: gradient "S-remote" like the web, tailnet toggle on the right
        LinearLayout h = ui.hrow();
        h.setPadding(dp(4), dp(16), 0, dp(10));
        FrameLayout logo = new FrameLayout(this);
        logo.setBackground(ui.stroke(0xFF0B0F14, 12, ui.dark ? 0x668B7BFF : 0x998B7BFF));
        logo.setElevation(dp(3));
        logo.addView(ui.icon(R.drawable.ic_terminal, Ui.G1, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        h.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(12), 0, dp(8), 0);
        TextView brand = Ui.gradientText(ui.text("S-remote", 24, ui.onSurface, true));
        tc.addView(brand);
        greeting = ui.oneLine(ui.text("", 13, ui.onSurfaceVariant, false));
        tc.addView(greeting);
        h.addView(tc, Ui.weight1());
        tsSlot = new FrameLayout(this);
        h.addView(tsSlot);
        h.addView(refresh);
        body.addView(h);

        homeStats = ui.hrow();
        body.addView(homeStats);

        banners = ui.vcol();
        banners.setPadding(0, dp(12), 0, 0);
        body.addView(banners);

        attention = ui.vcol();
        body.addView(attention);

        TextView addBtn = ui.textButton("Add");
        addBtn.setOnClickListener(v -> Actions.hostEditor(this, null, x -> { reload(); pollHosts(); }));
        body.addView(ui.section("Hosts", addBtn));
        hostList = ui.vcol();
        body.addView(hostList);

        LinearLayout ml = ui.hrow();
        ml.setPadding(dp(4), dp(20), 0, dp(8));
        ml.addView(ui.text("On your tailnet", 15, ui.onSurface, true), Ui.weight1());
        machinesStatus = ui.text("", 12, ui.onSurfaceVariant, false);
        machinesStatus.setPadding(0, 0, dp(4), 0);
        ml.addView(machinesStatus);
        ImageView exp = ui.icon(R.drawable.ic_expand, ui.onSurfaceVariant, 22);
        ml.addView(exp);
        ml.setOnClickListener(v -> { machinesOpen = !machinesOpen; exp.setRotation(machinesOpen ? 0 : -90); renderMachines(); });
        body.addView(ml);
        machineList = ui.vcol();
        body.addView(machineList);
        return page(body);
    }

    private static String greetingText() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "Working late";
        if (h < 12) return "Good morning";
        if (h < 18) return "Good afternoon";
        return "Good evening";
    }

    private void renderHome() {
        if (hostList == null) return;
        greeting.setText(greetingText());
        List<SessionItem> all = allSessions();

        // stat tiles
        int online = 0, run = 0, ask = 0, doneToday = 0;
        for (Host h : hosts) {
            HStat st = stats.get(h.id);
            if ((st != null && st.online) || Boolean.TRUE.equals(StateBus.isConnected(h.id))) online++;
        }
        for (SessionItem s : all) {
            if (s.state == Detector.State.RUNNING) run++;
            else if (s.state == Detector.State.QUESTION) ask++;
        }
        String today = dayLabel(System.currentTimeMillis());
        for (JSONObject e : EventLog.list(this))
            if (EventLog.K_DONE.equals(e.optString("kind")) && today.equals(dayLabel(e.optLong("t")))) doneToday++;
        homeStats.removeAllViews();
        boolean mon = StateBus.monitorRunning;
        addStat(online + "/" + hosts.size(), "Online", ui.onPrimaryContainer, ui.primaryContainer, v -> {});
        addStat(mon ? String.valueOf(run) : "–", "Running", ui.onSecondaryContainer, ui.secondaryContainer, v -> { sessFilter = "running"; selectTab(TAB_SESSIONS); });
        addStat(mon ? String.valueOf(ask) : "–", "Need you", ask > 0 ? ui.warn : ui.onSurfaceVariant, ask > 0 ? ui.warnContainer : ui.containerHigh,
                v -> { sessFilter = "question"; selectTab(TAB_SESSIONS); });
        addStat(String.valueOf(doneToday), "Done today", ui.ok, ui.okContainer, v -> { activityFilter = EventLog.K_DONE; selectTab(TAB_ACTIVITY); });

        // needs you: questions first, then recently finished
        attention.removeAllViews();
        List<SessionItem> asks = new ArrayList<>(), dones = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (SessionItem s : all) {
            if (s.state == Detector.State.QUESTION) asks.add(s);
            else if (s.state == Detector.State.DONE && now - s.since < RECENT_DONE_MS) dones.add(s);
        }
        if (!asks.isEmpty() || !dones.isEmpty()) {
            attention.addView(ui.section("Needs you", null));
            for (SessionItem s : asks) attention.addView(questionCard(s));
            for (SessionItem s : dones) attention.addView(doneRow(s));
        }

        // hosts
        hostList.removeAllViews();
        if (hosts.isEmpty()) {
            LinearLayout e = ui.emptyState(R.drawable.ic_hosts, "No hosts yet",
                    "Tap “Add host” and enter a machine's Tailscale IP, e.g. 100.64.1.2 — or pick one from your tailnet below.");
            hostList.addView(e);
        }
        Map<String, List<SessionItem>> byHost = new HashMap<>();
        for (SessionItem s : all) byHost.computeIfAbsent(s.host.id, k -> new ArrayList<>()).add(s);
        for (Host h : hosts) hostList.addView(hostCard(h, byHost.getOrDefault(h.id, new ArrayList<>())));
        renderMachines();
    }

    private void addStat(String num, String label, int fg, int bg, View.OnClickListener l) {
        LinearLayout t = ui.statTile(num, label, fg, bg);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(homeStats.getChildCount() == 0 ? 0 : dp(8), 0, 0, 0);
        homeStats.addView(t, lp);
    }

    /** a waiting session: the question + one-tap answers */
    private View questionCard(SessionItem s) {
        LinearLayout c = ui.card(ui.warnContainer);
        c.setBackground(ui.ripple(ui.round(ui.warnContainer, 24), 24));
        LinearLayout top = ui.hrow();
        top.addView(ui.icon(R.drawable.ic_help, ui.warn, 22));
        TextView t = ui.oneLine(ui.text(s.name + "  ·  " + s.host.name, 15, ui.onSurface, true));
        t.setPadding(dp(10), 0, 0, 0);
        top.addView(t, Ui.weight1());
        top.addView(ui.text(Ui.ago(s.since), 12, ui.onSurfaceVariant, false));
        c.addView(top);
        if (s.question != null) {
            TextView q = ui.text(s.question, 13, ui.onSurface, false);
            q.setTypeface(android.graphics.Typeface.MONOSPACE);
            q.setMaxLines(4);
            q.setEllipsize(android.text.TextUtils.TruncateAt.END);
            q.setPadding(dp(12), dp(10), dp(12), dp(10));
            q.setBackground(ui.round(ui.surface, 14));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.setMargins(0, dp(10), 0, 0);
            c.addView(q, qlp);
        }
        LinearLayout row = ui.hrow();
        row.setPadding(0, dp(10), 0, 0);
        for (Detector.Answer a : Detector.answersFor(s.question)) {
            TextView b = ui.filledButton(a.label);
            b.setOnClickListener(v -> {
                ui.haptic(v);
                Api.input(this, s.host, s.id, a.data);
                Notify.cancel(this, s.host, "q:" + s.id);
                toast("Sent to " + s.name);
            });
            row.addView(b, ui.margins(0, 0, 8, 0));
        }
        TextView reply = ui.tonalButton("Reply…");
        reply.setOnClickListener(v -> SessionSheet.show(this, s));
        row.addView(reply);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        c.addView(hs);
        c.setOnClickListener(v -> SessionSheet.show(this, s));
        return c;
    }

    private View doneRow(SessionItem s) {
        TextView open = ui.textButton("View");
        open.setOnClickListener(v -> SessionSheet.show(this, s));
        LinearLayout r = ui.listRow(R.drawable.ic_check, ui.ok, ui.okContainer,
                s.name + " finished", s.host.name + " · " + Ui.ago(s.since) + (s.line.isEmpty() ? "" : " · " + s.line), open);
        r.setBackground(ui.ripple(ui.round(ui.container, 20), 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        r.setLayoutParams(lp);
        r.setOnClickListener(v -> SessionSheet.show(this, s));
        return r;
    }

    private View hostCard(Host h, List<SessionItem> ss) {
        LinearLayout card = ui.card(ui.container);
        card.setOnClickListener(v -> Actions.openDetail(this, h));
        card.setOnLongClickListener(v -> { ui.haptic(v); Actions.hostMenu(this, h, this::reload); return true; });

        HStat st = stats.get(h.id);
        Boolean wsUp = StateBus.isConnected(h.id);
        boolean online = st != null ? st.online : Boolean.TRUE.equals(wsUp);

        LinearLayout top = ui.hrow();
        top.addView(ui.hostAvatar(h, 44));
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(14), 0, dp(8), 0);
        tc.addView(ui.oneLine(ui.text(h.name, 17, ui.onSurface, true)));
        String sub = h.hostPort() + (st != null && !st.platform.isEmpty() ? " · " + st.platform : "");
        tc.addView(ui.oneLine(ui.text(sub, 13, ui.onSurfaceVariant, false)));
        top.addView(tc, Ui.weight1());
        if (st == null && wsUp == null) top.addView(ui.pill("Checking…", ui.onSurfaceVariant, ui.containerHigh));
        else if (online) top.addView(ui.pill(st != null && st.ms > 0 ? st.ms + " ms" : "Online", ui.ok, ui.okContainer));
        else top.addView(ui.pill("Offline", ui.err, ui.errContainer));
        card.addView(top);

        List<SessionItem> live = new ArrayList<>();
        for (SessionItem s : ss) if (!s.exited) live.add(s);
        LinearLayout chips = ui.hrow();
        chips.setPadding(0, dp(10), 0, 0);
        int run = 0, ask = 0, done = 0, idle = 0;
        boolean anyLive = false;
        for (SessionItem s : live) {
            anyLive |= s.live;
            switch (s.state) {
                case RUNNING: run++; break;
                case QUESTION: ask++; break;
                case DONE: done++; break;
                default: idle++;
            }
        }
        if (anyLive) {
            if (ask > 0) chips.addView(ui.pill(ask + " needs input", ui.warn, ui.warnContainer), ui.margins(0, 0, 6, 0));
            if (run > 0) chips.addView(ui.pill(run + " running", ui.onPrimaryContainer, ui.primaryContainer), ui.margins(0, 0, 6, 0));
            if (done > 0) chips.addView(ui.pill(done + " done", ui.ok, ui.okContainer), ui.margins(0, 0, 6, 0));
            if (idle > 0) chips.addView(ui.pill(idle + " idle", ui.onSurfaceVariant, ui.containerHigh), ui.margins(0, 0, 6, 0));
        } else if (!live.isEmpty()) {
            chips.addView(ui.pill(live.size() + " live session" + (live.size() == 1 ? "" : "s"), ui.onSurfaceVariant, ui.containerHigh), ui.margins(0, 0, 6, 0));
        }
        if (!h.notify) chips.addView(ui.pill("Muted", ui.onSurfaceVariant, ui.containerHigh));
        if (chips.getChildCount() > 0) card.addView(chips);

        int shown = 0;
        for (SessionItem s : live) {
            if (shown++ >= 3) break;
            card.addView(Actions.sessionRow(this, ui, s, false, this::pollHosts));
        }
        if (live.size() > 3) {
            TextView more = ui.text("+" + (live.size() - 3) + " more · open host", 13, ui.primary, true);
            more.setPadding(dp(14), dp(8), 0, 0);
            card.addView(more);
        }

        // one-tap actions: what the web UI offers from its home screen
        LinearLayout acts = ui.hrow();
        acts.setPadding(0, dp(12), 0, 0);
        View t = ui.actionChip(R.drawable.ic_terminal, "Terminal", true);
        t.setOnClickListener(v -> Actions.openTerminal(this, h, null));
        acts.addView(t, ui.margins(0, 0, 8, 0));
        View d = ui.actionChip(R.drawable.ic_desktop, "Desktop", false);
        d.setOnClickListener(v -> Actions.openScreen(this, h));
        acts.addView(d, ui.margins(0, 0, 8, 0));
        View f = ui.actionChip(R.drawable.ic_folder, "Files", false);
        f.setOnClickListener(v -> Actions.openFiles(this, h, null, null, null));
        acts.addView(f, ui.margins(0, 0, 8, 0));
        View n = ui.actionChip(R.drawable.ic_add, "New", false);
        n.setOnClickListener(v -> Actions.newSession(this, h, null));
        acts.addView(n);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(acts);
        card.addView(hs);
        return card;
    }

    /** Tailscale + monitoring banners */
    private void refreshBanners() {
        if (banners == null) return;
        tsSlot.removeAllViews();
        tsSlot.addView(Actions.tailnetChip(this, ui, () -> { refreshBanners(); pollHosts(); }));
        banners.removeAllViews();
        if (!Tailscale.installed(this)) {
            banners.addView(banner(R.drawable.ic_shield, ui.err, ui.errContainer, "Tailscale not installed",
                    "S-remote connects to your machines through your tailnet.", "Get Tailscale",
                    v -> Tailscale.openPlayStore(this)));
        } else if (Tailscale.tailnetIp(this) == null && !Tailscale.vpnUp(this)) {
            banners.addView(banner(R.drawable.ic_shield, ui.warn, ui.warnContainer, "Tailscale is off",
                    "Your hosts are only reachable over your tailnet. Switch it on from here.", "Connect",
                    v -> Actions.connectTailscale(this, ok -> { refreshBanners(); pollHosts(); })));
        } else {
            if (System.currentTimeMillis() - lastScan > RESCAN_MS) scanTailnet();
        }
        if (!store.monitorEnabled() && !hosts.isEmpty()) {
            banners.addView(banner(R.drawable.ic_bell, ui.onTertiaryContainer, ui.tertiaryContainer,
                    "Get alerted when work is done",
                    "Turn on monitoring to see live session states and get notified when a task finishes or needs your input.",
                    "Turn on", v -> { setMonitor(true); refreshBanners(); renderHome(); }));
        }
    }

    private View banner(int icon, int fg, int bg, String title, String text, String action, View.OnClickListener l) {
        LinearLayout c = ui.card(bg);
        c.setBackground(ui.round(bg, 24));
        LinearLayout r = ui.hrow();
        r.setGravity(Gravity.TOP);
        r.addView(ui.icon(icon, fg, 24));
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(14), 0, 0, 0);
        tc.addView(ui.text(title, 16, ui.onSurface, true));
        TextView t = ui.text(text, 14, ui.onSurfaceVariant, false);
        t.setPadding(0, dp(4), 0, dp(10));
        tc.addView(t);
        LinearLayout br = ui.hrow();
        TextView b = ui.filledButton(action);
        b.setOnClickListener(l);
        br.addView(b);
        tc.addView(br);
        r.addView(tc, Ui.weight1());
        c.addView(r);
        return c;
    }

    private void reload() {
        hosts.clear();
        hosts.addAll(store.list());
        stats.keySet().retainAll(idsOf(hosts));
        renderCurrent();
        Actions.updateShortcuts(this);
    }

    private static List<String> idsOf(List<Host> hs) {
        List<String> l = new ArrayList<>();
        for (Host h : hs) l.add(h.id);
        return l;
    }

    // ---------- liveness polling ----------
    private void pollHosts() {
        for (Host h : new ArrayList<>(hosts)) {
            Api.info(h, (info, e) -> {
                HStat st = new HStat();
                st.online = info != null && info.online;
                if (st.online) {
                    st.ms = info.ms;
                    st.platform = platformLabel(info.info.optString("platform"));
                    HStat prev = stats.get(h.id);
                    st.sessions = prev != null ? prev.sessions : null;
                    Api.sessions(h, (a, e2) -> {
                        st.sessions = a;
                        if (resumed) onBus.run();
                    });
                }
                stats.put(h.id, st);
                if (resumed) onBus.run();
            });
        }
    }

    private static String platformLabel(String p) {
        switch (p) {
            case "win32": return "Windows";
            case "darwin": return "macOS";
            case "linux": return "Linux";
            default: return p;
        }
    }

    // ---------- tailnet discovery ----------
    private void renderMachines() {
        if (machineList == null) return;
        machineList.removeAllViews();
        if (!machinesOpen) return;
        if (machines.isEmpty()) {
            machineList.addView(Actions.hint(ui, hosts.isEmpty() ? "Add a host first — machines are discovered through it."
                    : scanning ? "Scanning…" : "No other machines online."));
            return;
        }
        for (JSONObject p : machines) {
            String ip = p.optString("ip");
            String nm = p.optString("hostname");
            if (nm.isEmpty()) nm = p.optString("dnsName", ip);
            String os = p.optString("os");
            boolean agent = !p.isNull("sremote");
            Host known = findHostByIp(ip);
            View pill = known != null ? ui.pill("Saved", ui.onSurfaceVariant, ui.containerHigh)
                    : agent ? ui.pill("S-remote", ui.onPrimaryContainer, ui.primaryContainer)
                    : ui.pill("No agent", ui.onSurfaceVariant, ui.containerHigh);
            LinearLayout r = ui.listRow(R.drawable.ic_hosts, agent ? ui.onPrimaryContainer : ui.onSurfaceVariant,
                    agent ? ui.primaryContainer : ui.containerHigh, nm, ip + (os.isEmpty() ? "" : " · " + os), pill);
            r.setBackground(ui.ripple(ui.round(ui.container, 20), 20));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(8));
            r.setLayoutParams(lp);
            if (!agent && known == null) r.setAlpha(0.7f);
            final String fnm = nm;
            r.setOnClickListener(v -> machineTap(p, fnm, known));
            machineList.addView(r);
        }
    }

    private Host findHostByIp(String ip) {
        for (Host h : hosts) if (h.url.contains("//" + ip + ":") || h.url.endsWith("//" + ip)) return h;
        return null;
    }

    private void machineTap(JSONObject p, String nm, Host known) {
        if (known != null) { Actions.openDetail(this, known); return; }
        String ip = p.optString("ip");
        if (p.isNull("sremote")) {
            new AlertDialog.Builder(this, ui.dialogTheme())
                    .setTitle(nm)
                    .setMessage("Online, but no S-remote server answers on :2209.\n\nInstall it on that machine with:\nirm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/install.ps1 | iex\n\nAdd it anyway?")
                    .setPositiveButton("Add anyway", (d, w) -> { store.add(nm, "http://" + ip + ":2209"); reload(); Actions.resyncMonitor(this); })
                    .setNeutralButton("Copy command", (d, w) -> copy("irm https://raw.githubusercontent.com/thsangyk-oss/sremote/main/install.ps1 | iex"))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        Host h = store.add(nm, "http://" + ip + ":2209");
        reload(); Actions.resyncMonitor(this); pollHosts();
        toast("Added " + nm);
        Actions.openDetail(this, h);
    }

    private void scanTailnet() {
        if (scanning) return;
        if (hosts.isEmpty()) { machines.clear(); machinesStatus.setText(""); renderMachines(); return; }
        scanning = true;
        lastScan = System.currentTimeMillis();
        machinesStatus.setText("scanning…");
        Map<String, JSONObject> found = new LinkedHashMap<>();
        int[] left = {hosts.size()};
        for (Host h : new ArrayList<>(hosts)) {
            Api.peers(h, (peers, e) -> {
                if (peers != null) for (int i = 0; i < peers.length(); i++) {
                    JSONObject p = peers.optJSONObject(i);
                    if (p == null || !p.optBoolean("online")) continue;
                    String ip = p.optString("ip");
                    if (ip.isEmpty()) continue;
                    JSONObject prev = found.get(ip);
                    if (prev == null || (prev.isNull("sremote") && !p.isNull("sremote")))
                        found.put(ip, p); // prefer the entry that saw an agent
                }
                if (--left[0] > 0) return;
                machines.clear(); machines.addAll(found.values());
                scanning = false;
                machinesStatus.setText(machines.isEmpty() ? "" : machines.size() + " online");
                renderMachines();
            });
        }
    }

    // ================= SESSIONS =================
    private View buildSessionsPage() {
        LinearLayout body = ui.vcol();
        TextView newS = ui.textButton("New");
        newS.setOnClickListener(v -> pickHost("New session on…", h -> Actions.newSession(this, h, null)));
        body.addView(header("Sessions", newS));
        TextView sub = ui.text("Every terminal on every host", 14, ui.onSurfaceVariant, false);
        sub.setPadding(dp(4), 0, 0, dp(12));
        body.addView(sub);

        EditText[] ref = new EditText[1];
        body.addView(ui.searchField("Search sessions, hosts, output…", ref));
        ref[0].addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { sessQuery = e.toString().trim(); renderSessions(); }
        });

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        sessChips = ui.hrow();
        sessChips.setPadding(0, dp(12), 0, 0);
        hs.addView(sessChips);
        body.addView(hs);
        sessList = ui.vcol();
        sessList.setPadding(0, dp(8), 0, 0);
        body.addView(sessList);
        return page(body);
    }

    private boolean sessMatch(SessionItem s) {
        switch (sessFilter) {
            case "question": return s.state == Detector.State.QUESTION;
            case "running": return s.state == Detector.State.RUNNING;
            case "done": return s.state == Detector.State.DONE;
            case "idle": return s.state == Detector.State.IDLE && !s.exited;
            case "exited": return s.exited;
            default: return true;
        }
    }

    private void renderSessions() {
        if (sessList == null) return;
        List<SessionItem> all = allSessions();
        String[][] filters = {{"all", "All"}, {"question", "Needs input"}, {"running", "Running"},
                {"done", "Done"}, {"idle", "Idle"}, {"exited", "Exited"}};
        sessChips.removeAllViews();
        String saved = sessFilter;
        for (String[] f : filters) {
            sessFilter = f[0];
            int n = 0;
            for (SessionItem s : all) if (sessMatch(s)) n++;
            sessFilter = saved;
            if (n == 0 && !f[0].equals("all") && !f[0].equals(saved)) continue;
            TextView c = ui.chip(f[1] + (n > 0 ? "  " + n : ""), f[0].equals(saved));
            c.setOnClickListener(v -> { sessFilter = f[0]; renderSessions(); });
            sessChips.addView(c, ui.margins(0, 0, 8, 0));
        }
        sessList.removeAllViews();
        if (!StateBus.monitorRunning && !hosts.isEmpty()) {
            LinearLayout b = ui.listRow(R.drawable.ic_bell, ui.onTertiaryContainer, ui.tertiaryContainer,
                    "Live states are off", "Turn on monitoring to see running / needs input / done", null);
            b.setBackground(ui.ripple(ui.round(ui.tertiaryContainer, 20), 20));
            b.setOnClickListener(v -> { setMonitor(true); renderSessions(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(8));
            sessList.addView(b, lp);
        }
        int n = 0;
        for (Host h : hosts) {
            List<SessionItem> mine = new ArrayList<>();
            for (SessionItem s : all) if (s.host.id.equals(h.id) && sessMatch(s) && s.matches(sessQuery)) mine.add(s);
            if (mine.isEmpty()) continue;
            LinearLayout hh = ui.hrow();
            hh.setPadding(dp(4), dp(n == 0 ? 6 : 18), 0, dp(2));
            hh.addView(ui.hostAvatar(h, 24));
            TextView t = ui.oneLine(ui.text(h.name, 14, ui.onSurface, true));
            t.setPadding(dp(10), 0, 0, 0);
            hh.addView(t, Ui.weight1());
            hh.addView(ui.text(mine.size() + "", 13, ui.onSurfaceVariant, false));
            hh.setOnClickListener(v -> Actions.openDetail(this, h));
            sessList.addView(hh);
            for (SessionItem s : mine) sessList.addView(Actions.sessionRow(this, ui, s, false, this::pollHosts));
            n += mine.size();
        }
        if (n == 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(8), 0, 0);
            sessList.addView(ui.emptyState(R.drawable.ic_terminal,
                    hosts.isEmpty() ? "No hosts yet" : !sessQuery.isEmpty() || !sessFilter.equals("all") ? "Nothing matches" : "No sessions",
                    hosts.isEmpty() ? "Add a host on the Home tab." : !sessQuery.isEmpty() || !sessFilter.equals("all")
                            ? "Try another filter or search." : "Tap “New” to start one."), lp);
        }
    }

    private void pickHost(String title, java.util.function.Consumer<Host> pick) {
        if (hosts.isEmpty()) { toast("Add a host first"); return; }
        if (hosts.size() == 1) { pick.accept(hosts.get(0)); return; }
        String[] labels = new String[hosts.size()];
        int[] icons = new int[hosts.size()];
        for (int i = 0; i < hosts.size(); i++) { labels[i] = hosts.get(i).name; icons[i] = R.drawable.ic_hosts; }
        ui.menuSheet(this, title, labels, icons, i -> pick.accept(hosts.get(i))).show();
    }

    // ================= ACTIVITY =================
    private View buildActivityPage() {
        LinearLayout body = ui.vcol();
        TextView clear = ui.textButton("Clear");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle("Clear activity history?")
                .setPositiveButton("Clear", (d, w) -> EventLog.clear(this))
                .setNegativeButton("Cancel", null).show());
        body.addView(header("Activity", clear));
        TextView sub = ui.text("Everything the monitor noticed, newest first", 14, ui.onSurfaceVariant, false);
        sub.setPadding(dp(4), 0, 0, dp(12));
        body.addView(sub);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        activityChips = ui.hrow();
        hs.addView(activityChips);
        body.addView(hs);
        activityList = ui.vcol();
        activityList.setPadding(0, dp(8), 0, 0);
        body.addView(activityList);
        return page(body);
    }

    private void renderActivity() {
        if (activityList == null) return;
        String[][] filters = {{"all", "All"}, {EventLog.K_QUESTION, "Needs input"}, {EventLog.K_DONE, "Finished"}, {"sessions", "Sessions"}};
        activityChips.removeAllViews();
        for (String[] f : filters) {
            TextView c = ui.chip(f[1], f[0].equals(activityFilter));
            c.setOnClickListener(v -> { activityFilter = f[0]; renderActivity(); });
            activityChips.addView(c, ui.margins(0, 0, 8, 0));
        }
        if (hosts.size() > 1) {
            View sep = new View(this);
            sep.setBackgroundColor(ui.outlineVariant);
            activityChips.addView(sep, new LinearLayout.LayoutParams(dp(1), dp(24)));
            activityChips.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
            for (Host h : hosts) {
                boolean on = h.id.equals(activityHost);
                TextView c = ui.chip(h.name, on);
                c.setOnClickListener(v -> { activityHost = on ? null : h.id; renderActivity(); });
                activityChips.addView(c, ui.margins(0, 0, 8, 0));
            }
        }
        activityList.removeAllViews();
        String lastDay = null;
        int n = 0;
        for (JSONObject e : EventLog.list(this)) {
            String kind = e.optString("kind");
            boolean match = activityFilter.equals("all") || activityFilter.equals(kind)
                    || (activityFilter.equals("sessions") && (kind.equals(EventLog.K_NEW) || kind.equals(EventLog.K_EXIT)));
            if (!match) continue;
            if (activityHost != null && !activityHost.equals(e.optString("host"))) continue;
            long t = e.optLong("t");
            String day = dayLabel(t);
            if (!day.equals(lastDay)) {
                TextView dl = ui.text(day, 13, ui.onSurfaceVariant, true);
                dl.setPadding(dp(4), dp(n == 0 ? 4 : 16), 0, dp(8));
                activityList.addView(dl);
                lastDay = day;
            }
            activityList.addView(eventRow(e, kind, t));
            if (++n >= 150) break;
        }
        if (n == 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(8), 0, 0);
            activityList.addView(ui.emptyState(R.drawable.ic_bell, "Nothing here yet", store.monitorEnabled()
                    ? "Finished tasks, questions and session changes will show up here."
                    : "Turn on monitoring in Settings to start collecting activity."), lp);
        }
    }

    private View eventRow(JSONObject e, String kind, long t) {
        int icon, fg, bg;
        switch (kind) {
            case EventLog.K_QUESTION: icon = R.drawable.ic_help; fg = ui.warn; bg = ui.warnContainer; break;
            case EventLog.K_DONE: icon = R.drawable.ic_check; fg = ui.ok; bg = ui.okContainer; break;
            case EventLog.K_NEW: icon = R.drawable.ic_play; fg = ui.onPrimaryContainer; bg = ui.primaryContainer; break;
            default: icon = R.drawable.ic_close; fg = ui.onSurfaceVariant; bg = ui.containerHigh;
        }
        LinearLayout r = ui.hrow();
        r.setGravity(Gravity.TOP);
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        r.setBackground(ui.ripple(ui.round(ui.container, 20), 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        r.setLayoutParams(lp);
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(ui.oval(bg));
        ic.addView(ui.icon(icon, fg, 20), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        r.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(14), 0, 0, 0);
        tc.addView(ui.oneLine(ui.text(e.optString("title"), 15, ui.onSurface, true)));
        String text = e.optString("text");
        if (!text.isEmpty()) {
            TextView tt = ui.text(text, 13, ui.onSurfaceVariant, false);
            tt.setMaxLines(3);
            tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tt.setPadding(0, dp(2), 0, 0);
            tc.addView(tt);
        }
        TextView meta = ui.text(e.optString("hostName") + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(t))
                + " · " + Ui.ago(t), 12, ui.outline, false);
        meta.setPadding(0, dp(4), 0, 0);
        tc.addView(meta);
        r.addView(tc, Ui.weight1());
        Host h = store.find(e.optString("host"));
        String sid = e.optString("sid");
        if (h != null) r.setOnClickListener(v -> {
            SessionItem s = findSession(h, sid);
            if (s != null && !s.exited) SessionSheet.show(this, s);
            else Actions.openDetail(this, h);
        });
        return r;
    }

    private SessionItem findSession(Host h, String sid) {
        if (sid == null || sid.isEmpty()) return null;
        HStat st = stats.get(h.id);
        for (SessionItem s : SessionItem.merge(h, st != null ? st.sessions : null)) if (s.id.equals(sid)) return s;
        return null;
    }

    private static String dayLabel(long t) {
        Calendar c = Calendar.getInstance(), now = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            int d = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR);
            if (d == 0) return "Today";
            if (d == 1) return "Yesterday";
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(t));
    }

    // ================= SETTINGS =================
    private View buildSettingsPage() {
        LinearLayout body = ui.vcol();
        body.addView(header("Settings"));
        settingsBody = ui.vcol();
        body.addView(settingsBody);
        return page(body);
    }

    private LinearLayout group() {
        LinearLayout g = ui.vcol();
        g.setBackground(ui.round(ui.container, 24));
        g.setPadding(0, dp(4), 0, dp(4));
        return g;
    }

    private View clickRow(String title, String sub, View.OnClickListener l) {
        LinearLayout r = ui.settingRow(title, sub, null);
        r.setBackground(ui.ripple(ui.round(0x00000000, 24), 24));
        r.setOnClickListener(l);
        return r;
    }

    private View switchRow(String title, String sub, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
        Switch sw = ui.toggle(on);
        sw.setOnCheckedChangeListener(l);
        LinearLayout r = ui.settingRow(title, sub, sw);
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    private static String hourLabel(int h) { return String.format(Locale.US, "%02d:00", h); }

    private void buildSettings() {
        if (settingsBody == null) return;
        settingsBody.removeAllViews();

        settingsBody.addView(ui.label("Monitoring"));
        LinearLayout g1 = group();
        g1.addView(switchRow("Background monitoring", "Stay connected to your hosts to track session states and alert you",
                store.monitorEnabled(), (v, on) -> { setMonitor(on); buildSettings(); }));
        boolean mon = store.monitorEnabled();
        View q = switchRow("Needs input", "y/n prompts, menus, passwords — with reply buttons",
                store.notifyKind(HostStore.N_QUESTION), (v, on) -> store.setNotifyKind(HostStore.N_QUESTION, on));
        View d = switchRow("Task finished", "Back at the prompt, exit codes, bell, agent stopped",
                store.notifyKind(HostStore.N_DONE), (v, on) -> store.setNotifyKind(HostStore.N_DONE, on));
        View s = switchRow("Sessions started / ended", null,
                store.notifyKind(HostStore.N_SESSION), (v, on) -> store.setNotifyKind(HostStore.N_SESSION, on));
        for (View v : new View[]{q, d, s}) { v.setAlpha(mon ? 1f : 0.5f); g1.addView(v); }
        g1.addView(clickRow("Send test notification", "Check that alerts reach this phone", v -> { ensureNotifPermission(); Notify.test(this); }));
        g1.addView(clickRow("System notification settings", "Sound, vibration and pop-up per category", v -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        }));
        if (Build.VERSION.SDK_INT >= 33) g1.addView(clickRow("Add Quick Settings tile", "Toggle monitoring from the notification shade", v -> requestTile()));
        settingsBody.addView(g1);

        settingsBody.addView(ui.label("Quiet hours"));
        LinearLayout gq = group();
        gq.addView(switchRow("Quiet hours", "Alerts arrive silently during this time",
                store.quietOn(), (v, on) -> { store.setQuietOn(on); buildSettings(); }));
        View range = clickRow("From " + hourLabel(store.quietFrom()) + " to " + hourLabel(store.quietTo()),
                "Tap to change", v -> pickQuietRange());
        View allowQ = switchRow("Questions still alert", "A waiting session blocks work — let those through",
                store.quietAllowQuestions(), (v, on) -> store.setQuietAllowQuestions(on));
        for (View v : new View[]{range, allowQ}) { v.setAlpha(store.quietOn() ? 1f : 0.5f); gq.addView(v); }
        settingsBody.addView(gq);

        settingsBody.addView(ui.label("Appearance & terminal"));
        LinearLayout ga = group();
        String th = store.theme();
        ga.addView(clickRow("Theme", th.equals(HostStore.THEME_DARK) ? "Dark" : th.equals(HostStore.THEME_LIGHT) ? "Light" : "Follow system",
                v -> ui.menuSheet(this, "Theme", new String[]{"Follow system", "Light", "Dark"},
                        new int[]{R.drawable.ic_palette, R.drawable.ic_palette, R.drawable.ic_moon}, i -> {
                            store.setTheme(i == 0 ? HostStore.THEME_SYSTEM : i == 1 ? HostStore.THEME_LIGHT : HostStore.THEME_DARK);
                            recreate();
                        }).show()));
        ga.addView(switchRow("Keep screen on in terminal", "Handy while watching a long build",
                store.keepScreenOn(), (v, on) -> store.setKeepScreenOn(on)));
        ga.addView(clickRow("Quick commands", store.snippets().size() + " saved · shown in session previews", v -> editSnippets()));
        settingsBody.addView(ga);

        settingsBody.addView(ui.label("Security"));
        LinearLayout g2 = group();
        if (LockGate.available(this)) {
            g2.addView(switchRow("App lock", "Require fingerprint, face or screen lock to open S-remote",
                    store.lockEnabled(), (v, on) -> store.setLockEnabled(on)));
        } else {
            View r = ui.settingRow("App lock", "Not available — needs Android 11+ with a fingerprint or screen lock set up", null);
            r.setAlpha(0.6f);
            g2.addView(r);
        }
        settingsBody.addView(g2);

        settingsBody.addView(ui.label("Tailscale"));
        LinearLayout g3 = group();
        if (!Tailscale.installed(this)) {
            TextView get = ui.tonalButton("Get");
            get.setOnClickListener(v -> Tailscale.openPlayStore(this));
            g3.addView(ui.settingRow("Tailscale", "Not installed — S-remote reaches your PCs through it", get));
        } else {
            String ip = Tailscale.tailnetIp(this);
            boolean up = ip != null || Tailscale.vpnUp(this);
            g3.addView(switchRow("Tailscale VPN", up ? "Connected" + (ip != null ? " · " + ip : "") : "Off — hosts are unreachable",
                    up, (v, on) -> {
                        if (on) Actions.connectTailscale(this, ok -> buildSettings());
                        else Actions.disconnectTailscale(this, this::buildSettings);
                    }));
            g3.addView(switchRow("Connect automatically", "Switch Tailscale on when you open a host while it's off",
                    store.tsAutoConnect(), (v, on) -> store.setTsAutoConnect(on)));
            g3.addView(clickRow("Open Tailscale app", "Sign in, pick an exit node, see your devices", v -> Tailscale.openApp(this)));
        }
        settingsBody.addView(g3);

        settingsBody.addView(ui.label("Backup"));
        LinearLayout gb = group();
        gb.addView(clickRow("Export hosts", "Copy hosts + quick commands as JSON (share or paste on another phone)", v -> exportHosts()));
        gb.addView(clickRow("Import hosts", "Paste a JSON export — existing hosts are kept", v -> importHosts()));
        settingsBody.addView(gb);

        settingsBody.addView(ui.label("About"));
        LinearLayout g4 = group();
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        g4.addView(ui.settingRow("S-remote for Android", "Version " + ver, null));
        g4.addView(clickRow("Welcome tour", "Tailscale, first host and notifications — step by step",
                v -> startActivity(new Intent(this, OnboardingActivity.class))));
        g4.addView(clickRow("Project on GitHub", "thsangyk-oss/sremote", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/thsangyk-oss/sremote"))); }
            catch (Exception ignored) {}
        }));
        settingsBody.addView(g4);
    }

    private void pickQuietRange() {
        new TimePickerDialog(this, ui.dialogTheme(), (v1, h1, m1) ->
                new TimePickerDialog(this, ui.dialogTheme(), (v2, h2, m2) -> {
                    store.setQuietRange(h1, h2);
                    buildSettings();
                }, store.quietTo(), 0, true) {{ setTitle("Quiet hours end"); }}.show(),
                store.quietFrom(), 0, true) {{ setTitle("Quiet hours start"); }}.show();
    }

    private void editSnippets() {
        List<String> list = new ArrayList<>(store.snippets());
        LinearLayout box = ui.vcol();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        LinearLayout rows = ui.vcol();
        box.addView(rows);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            if (list.isEmpty()) rows.addView(Actions.hint(ui, "No quick commands yet."));
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                LinearLayout r = ui.hrow();
                r.setPadding(dp(4), dp(2), 0, dp(2));
                r.addView(ui.mono(list.get(i), 14, ui.onSurface), Ui.weight1());
                ImageView del = ui.iconButton(R.drawable.ic_delete, ui.onSurfaceVariant);
                del.setOnClickListener(v -> { list.remove(idx); render[0].run(); });
                r.addView(del);
                rows.addView(r);
            }
        };
        render[0].run();
        LinearLayout add = ui.hrow();
        add.setPadding(0, dp(8), 0, 0);
        EditText in = ui.input("e.g. npm run build");
        in.setTypeface(android.graphics.Typeface.MONOSPACE);
        add.addView(in, Ui.weight1());
        ImageView plus = ui.iconButton(R.drawable.ic_add, ui.primary);
        plus.setOnClickListener(v -> {
            String t = in.getText().toString().trim();
            if (t.isEmpty()) return;
            list.add(t); in.setText(""); render[0].run();
        });
        add.addView(plus);
        box.addView(add);
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle("Quick commands")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String pending = in.getText().toString().trim();
                    if (!pending.isEmpty()) list.add(pending);
                    store.setSnippets(list);
                    buildSettings();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("S-remote", text));
        toast("Copied");
    }

    private void exportHosts() {
        String json = store.exportJson();
        copy(json);
        Intent share = new Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_TEXT, json).putExtra(Intent.EXTRA_SUBJECT, "S-remote hosts");
        try { startActivity(Intent.createChooser(share, "Share S-remote hosts")); } catch (Exception ignored) {}
    }

    private void importHosts() {
        EditText in = ui.input("Paste the exported JSON");
        in.setSingleLine(false);
        in.setMinLines(4);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setTypeface(android.graphics.Typeface.MONOSPACE);
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        try {
            CharSequence clip = cm.hasPrimaryClip() ? cm.getPrimaryClip().getItemAt(0).getText() : null;
            if (clip != null && clip.toString().contains("\"hosts\"")) in.setText(clip);
        } catch (Exception ignored) {}
        LinearLayout box = ui.vcol();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(in);
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle("Import hosts")
                .setView(box)
                .setPositiveButton("Import", (d, w) -> {
                    int n = store.importJson(in.getText().toString());
                    if (n < 0) toast("That doesn't look like an S-remote export");
                    else { toast(n == 0 ? "Nothing new to import" : "Imported " + n + " host" + (n == 1 ? "" : "s")); reload(); Actions.resyncMonitor(this); pollHosts(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(new ComponentName(this, MonitorTile.class), "S-remote",
                Icon.createWithResource(this, R.drawable.ic_notify), getMainExecutor(), r -> {});
    }

    private void setMonitor(boolean on) {
        store.setMonitorEnabled(on);
        if (on) { ensureNotifPermission(); MonitorService.start(this); }
        else MonitorService.stop(this);
        StatusWidget.refresh(this);
    }

    private void ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }
}
