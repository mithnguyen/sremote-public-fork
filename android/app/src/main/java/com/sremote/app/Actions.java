package com.sremote.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Host / session actions shared by every screen. */
final class Actions {
    private Actions() {}

    static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    // ---------------- navigation ----------------
    /** open the web terminal; hash = "s=<sid>" | "ws=<id>" | "screen" | null */
    static void openTerminal(Activity a, Host h, String hash) {
        Runnable go = () -> {
            Intent i = new Intent(a, HostActivity.class);
            i.putExtra(HostActivity.EXTRA_HOST_ID, h.id);
            i.putExtra(HostActivity.EXTRA_NAME, h.name);
            i.putExtra(HostActivity.EXTRA_URL, h.url);
            if (hash != null) i.putExtra(HostActivity.EXTRA_HASH, hash);
            a.startActivity(i);
        };
        if (Tailscale.tailnetIp(a) == null && !Tailscale.vpnUp(a)) {
            new AlertDialog.Builder(a, new Ui(a).dialogTheme())
                    .setTitle("Tailscale seems off")
                    .setMessage("No tailnet connection detected. Open " + h.name + " anyway?")
                    .setPositiveButton("Open", (d, w) -> go.run())
                    .setNeutralButton("Open Tailscale", (d, w) -> { if (!Tailscale.openApp(a)) Tailscale.openPlayStore(a); })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else go.run();
    }

    static void openDetail(Activity a, Host h) {
        a.startActivity(new Intent(a, HostDetailActivity.class).putExtra(HostActivity.EXTRA_HOST_ID, h.id));
    }

    static void resyncMonitor(Context c) {
        if (new HostStore(c).monitorEnabled()) MonitorService.start(c); // onStartCommand re-syncs
    }

    // ---------------- sessions ----------------
    /** pick workspace + shell + name, create, then open it in the terminal */
    static void newSession(Activity a, Host h, String presetWs) {
        Ui ui = new Ui(a);
        AlertDialog loading = new AlertDialog.Builder(a, ui.dialogTheme())
                .setTitle("New session").setMessage("Loading " + h.name + "…").show();
        Api.info(h, (info, e1) -> Api.workspaces(h, (wss, e2) -> {
            loading.dismiss();
            if (info == null || !info.online) { toast(a, h.name + " is unreachable"); return; }
            JSONArray shellsJ = info.info.optJSONArray("shells");
            List<String> shells = new ArrayList<>();
            if (shellsJ != null) for (int i = 0; i < shellsJ.length(); i++) shells.add(shellsJ.optString(i));
            List<String[]> ws = new ArrayList<>(); // {id|null, name}
            ws.add(new String[]{null, "Standalone"});
            if (wss != null) for (int i = 0; i < wss.length(); i++) {
                JSONObject w = wss.optJSONObject(i);
                if (w != null) ws.add(new String[]{w.optString("id"), w.optString("name")});
            }
            String[] selWs = {presetWs};
            if (presetWs == null && ws.size() > 1) selWs[0] = ws.get(1)[0]; // default: first workspace
            String[] selShell = {shells.isEmpty() ? null : shells.get(0)};

            LinearLayout box = ui.vcol();
            box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
            box.addView(ui.label("Workspace"));
            LinearLayout wsRow = ui.hrow();
            HorizontalScrollView wsScroll = new HorizontalScrollView(a);
            wsScroll.setHorizontalScrollBarEnabled(false);
            wsScroll.addView(wsRow);
            box.addView(wsScroll);
            Runnable[] renderWs = new Runnable[1];
            renderWs[0] = () -> {
                wsRow.removeAllViews();
                for (String[] w : ws) {
                    boolean on = w[0] == null ? selWs[0] == null : w[0].equals(selWs[0]);
                    TextView c = ui.chip(w[1], on);
                    c.setOnClickListener(v -> { selWs[0] = w[0]; renderWs[0].run(); });
                    wsRow.addView(c, ui.margins(0, 0, 8, 0));
                }
            };
            renderWs[0].run();
            if (shells.size() > 1) {
                box.addView(ui.label("Shell"));
                LinearLayout shRow = ui.hrow();
                Runnable[] renderSh = new Runnable[1];
                renderSh[0] = () -> {
                    shRow.removeAllViews();
                    for (String s : shells) {
                        TextView c = ui.chip(s, s.equals(selShell[0]));
                        c.setOnClickListener(v -> { selShell[0] = s; renderSh[0].run(); });
                        shRow.addView(c, ui.margins(0, 0, 8, 0));
                    }
                };
                renderSh[0].run();
                box.addView(shRow);
            }
            box.addView(ui.label("Name"));
            EditText name = ui.input("Optional, e.g. build, agent, logs");
            box.addView(name);
            new AlertDialog.Builder(a, ui.dialogTheme())
                    .setTitle("New session on " + h.name)
                    .setView(box)
                    .setPositiveButton("Create & open", (d, w) -> create(a, h, selWs[0], selShell[0], name.getText().toString().trim(), true))
                    .setNeutralButton("Create", (d, w) -> create(a, h, selWs[0], selShell[0], name.getText().toString().trim(), false))
                    .setNegativeButton("Cancel", null)
                    .show();
        }));
    }

    private static void create(Activity a, Host h, String ws, String shell, String name, boolean open) {
        Api.createSession(h, ws, shell, name, (id, err) -> {
            if (id == null || id.isEmpty()) { toast(a, "Couldn't create session" + (err != null ? ": " + err : "")); return; }
            toast(a, "Session created");
            StateBus.changed();
            if (open) openTerminal(a, h, "s=" + id);
        });
    }

    static void rename(Activity a, Host h, String sid, String current, Runnable after) {
        Ui ui = new Ui(a);
        EditText in = ui.input("Session name");
        in.setText(current);
        in.setSelectAllOnFocus(true);
        LinearLayout box = ui.vcol();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(in);
        new AlertDialog.Builder(a, ui.dialogTheme())
                .setTitle("Rename session")
                .setView(box)
                .setPositiveButton("Rename", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty()) return;
                    Api.rename(h, sid, n);
                    toast(a, "Renamed to " + n);
                    if (after != null) in.postDelayed(after, 700);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static void kill(Activity a, Host h, String sid, String name, Runnable after) {
        new AlertDialog.Builder(a, new Ui(a).dialogTheme())
                .setTitle("Kill “" + name + "”?")
                .setMessage("The process in this session is terminated. This can't be undone.")
                .setPositiveButton("Kill", (d, w) -> Api.killSession(h, sid, (ok, err) -> {
                    toast(a, Boolean.TRUE.equals(ok) ? "Killed " + name : "Couldn't kill " + name);
                    StateBus.changed();
                    if (after != null) after.run();
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** long-press menu for a session row */
    static void sessionMenu(Activity a, SessionItem s, Runnable changed) {
        Ui ui = new Ui(a);
        ui.menuSheet(a, s.name + " · " + s.host.name,
                new String[]{"Preview & reply", "Open in terminal", "Rename", "Kill session"},
                new int[]{R.drawable.ic_reply, R.drawable.ic_terminal, R.drawable.ic_edit, R.drawable.ic_stop},
                i -> {
                    switch (i) {
                        case 0: SessionSheet.show(a, s); break;
                        case 1: openTerminal(a, s.host, "s=" + s.id); break;
                        case 2: rename(a, s.host, s.id, s.name, changed); break;
                        case 3: kill(a, s.host, s.id, s.name, changed); break;
                    }
                }).show();
    }

    // ---------------- hosts ----------------
    /** add / edit a host with a reachability check and a colour tag */
    static void hostEditor(Activity a, Host edit, Consumer<Host> saved) {
        Ui ui = new Ui(a);
        HostStore store = new HostStore(a);
        LinearLayout box = ui.vcol();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);

        box.addView(ui.label("Address"));
        EditText addr = ui.input("Tailscale IP, e.g. 100.64.1.2");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(addr);
        box.addView(ui.label("Name"));
        EditText name = ui.input("Optional — defaults to the machine name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(name);

        box.addView(ui.label("Colour"));
        int[] color = {edit != null ? edit.colorIndex() : 0};
        LinearLayout colors = ui.hrow();
        Runnable[] renderColors = new Runnable[1];
        renderColors[0] = () -> {
            colors.removeAllViews();
            for (int i = 0; i < Ui.HOST_COLORS; i++) {
                final int idx = i;
                View dot = new View(a);
                android.graphics.drawable.GradientDrawable g = ui.oval(ui.hostBg(i));
                if (i == color[0]) g.setStroke(ui.dp(3), ui.hostFg(i));
                dot.setBackground(g);
                dot.setOnClickListener(v -> { color[0] = idx; renderColors[0].run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34));
                lp.setMargins(0, 0, ui.dp(10), 0);
                colors.addView(dot, lp);
            }
        };
        renderColors[0].run();
        box.addView(colors);

        TextView status = ui.text("", 13, ui.onSurfaceVariant, false);
        status.setPadding(0, ui.dp(12), 0, 0);
        box.addView(status);

        if (edit != null) { name.setText(edit.name); addr.setText(edit.url); }

        AlertDialog d = new AlertDialog.Builder(a, ui.dialogTheme())
                .setTitle(edit == null ? "Add host" : "Edit host")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNeutralButton("Test", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
        boolean[] forced = {false};
        Runnable save = () -> {
            String url = Host.normalizeUrl(addr.getText().toString());
            if (url == null) { status.setTextColor(ui.err); status.setText("Enter a valid address"); return; }
            status.setTextColor(ui.onSurfaceVariant);
            status.setText("Checking " + url + " …");
            Api.info(url, (info, err) -> {
                if (!d.isShowing()) return;
                boolean ok = info != null && info.online;
                if (!ok && !forced[0]) {
                    status.setTextColor(ui.warn);
                    status.setText("Unreachable — wrong address, or the host / Tailscale is off. Tap Save again to keep it anyway.");
                    forced[0] = true;
                    return;
                }
                String nm = name.getText().toString().trim();
                if (nm.isEmpty()) nm = ok ? info.info.optString("hostname", "host")
                        : url.replaceFirst("^[a-z]+://", "").split("[/:]")[0];
                Host h;
                if (edit != null) { edit.name = nm; edit.url = url; edit.color = color[0]; store.update(edit); h = edit; }
                else { h = store.add(nm, url); h.color = color[0]; store.update(h); }
                resyncMonitor(a);
                toast(a, "Saved " + nm);
                d.dismiss();
                if (saved != null) saved.accept(h);
            });
        };
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save.run());
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String url = Host.normalizeUrl(addr.getText().toString());
            if (url == null) { status.setTextColor(ui.err); status.setText("Enter a valid address"); return; }
            status.setTextColor(ui.onSurfaceVariant);
            status.setText("Testing " + url + " …");
            Api.info(url, (info, err) -> {
                if (info != null && info.online) {
                    status.setTextColor(ui.ok);
                    status.setText("✓ " + info.info.optString("hostname") + " · " + info.info.optString("platform")
                            + " · " + info.ms + " ms");
                    if (name.getText().toString().trim().isEmpty()) name.setHint(info.info.optString("hostname"));
                } else {
                    status.setTextColor(ui.err);
                    status.setText("No S-remote server answers at " + url);
                }
            });
        });
        addr.setOnFocusChangeListener((v, f) -> forced[0] = false);
    }

    /** host overflow menu */
    static void hostMenu(Activity a, Host h, Runnable changed) {
        Ui ui = new Ui(a);
        HostStore store = new HostStore(a);
        ui.menuSheet(a, h.name,
                new String[]{"Open terminal", "Remote desktop", "New session", "Host details",
                        h.notify ? "Mute notifications" : "Unmute notifications", "Add to home screen", "Edit", "Remove"},
                new int[]{R.drawable.ic_terminal, R.drawable.ic_desktop, R.drawable.ic_add, R.drawable.ic_info,
                        R.drawable.ic_bell, R.drawable.ic_home, R.drawable.ic_edit, R.drawable.ic_delete},
                i -> {
                    switch (i) {
                        case 0: openTerminal(a, h, null); break;
                        case 1: openTerminal(a, h, "screen"); break;
                        case 2: newSession(a, h, null); break;
                        case 3: openDetail(a, h); break;
                        case 4: h.notify = !h.notify; store.update(h); resyncMonitor(a); if (changed != null) changed.run(); break;
                        case 5: pinShortcut(a, h); break;
                        case 6: hostEditor(a, h, x -> { if (changed != null) changed.run(); }); break;
                        case 7: confirmRemove(a, h, changed); break;
                    }
                }).show();
    }

    static void confirmRemove(Activity a, Host h, Runnable changed) {
        new AlertDialog.Builder(a, new Ui(a).dialogTheme())
                .setTitle("Remove " + h.name + "?")
                .setMessage("Only removes it from this phone — nothing changes on the machine.")
                .setPositiveButton("Remove", (d, w) -> {
                    new HostStore(a).remove(h.id);
                    StateBus.clearHost(h.id);
                    resyncMonitor(a);
                    updateShortcuts(a);
                    if (changed != null) changed.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------- launcher shortcuts ----------------
    static ShortcutInfo shortcutFor(Context c, Host h) {
        Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN_HOST)
                .putExtra(HostActivity.EXTRA_HOST_ID, h.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new ShortcutInfo.Builder(c, "host-" + h.id)
                .setShortLabel(h.name.length() > 20 ? h.name.substring(0, 20) : h.name)
                .setLongLabel("Open " + h.name)
                .setIcon(Icon.createWithResource(c, R.mipmap.ic_launcher))
                .setIntent(i).build();
    }

    static void updateShortcuts(Context c) {
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<ShortcutInfo> list = new ArrayList<>();
            int max = Math.min(4, sm.getMaxShortcutCountPerActivity());
            for (Host h : new HostStore(c).list()) { if (list.size() >= max) break; list.add(shortcutFor(c, h)); }
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) {}
    }

    static void pinShortcut(Context c, Host h) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm != null && sm.isRequestPinShortcutSupported()) sm.requestPinShortcut(shortcutFor(c, h), null);
        else toast(c, "Your launcher doesn't support pinned shortcuts");
    }

    // ---------------- small shared views ----------------
    /** one session row (dot · name · state · subtitle), used by Home, Sessions and Host details */
    static LinearLayout sessionRow(Activity a, Ui ui, SessionItem s, boolean showHost, Runnable changed) {
        LinearLayout r = ui.hrow();
        r.setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(10));
        r.setBackground(ui.ripple(ui.round(ui.surface, 16), 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, ui.dp(6), 0, 0);
        r.setLayoutParams(lp);
        r.addView(ui.dot(s.live || s.exited ? ui.stateColor(s.state) : ui.outlineVariant, 10));
        LinearLayout tc = ui.vcol();
        tc.setPadding(ui.dp(12), 0, ui.dp(6), 0);
        LinearLayout nl = ui.hrow();
        nl.addView(ui.oneLine(ui.text(s.name, 14, ui.onSurface, true)), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        String meta = (showHost ? "  ·  " + s.host.name : "")
                + (s.live || s.exited ? "  ·  " + Ui.stateLabel(s.state) + " · " + Ui.ago(s.since) : "");
        if (!meta.isEmpty()) nl.addView(ui.oneLine(ui.text(meta, 12, s.live ? ui.stateColor(s.state) : ui.onSurfaceVariant, false)));
        tc.addView(nl);
        String sub = s.subtitle();
        if (sub != null && !sub.isEmpty()) tc.addView(ui.mono(sub, 12, ui.onSurfaceVariant));
        r.addView(tc, Ui.weight1());
        if (s.state == Detector.State.QUESTION) {
            TextView ans = ui.tonalButton("Answer");
            ans.setOnClickListener(v -> SessionSheet.show(a, s));
            r.addView(ans);
        }
        r.setOnClickListener(v -> SessionSheet.show(a, s));
        r.setOnLongClickListener(v -> { ui.haptic(v); sessionMenu(a, s, changed); return true; });
        return r;
    }

    /** centred hint used under section headers */
    static TextView hint(Ui ui, String s) {
        TextView t = ui.text(s, 13, ui.onSurfaceVariant, false);
        t.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4));
        t.setGravity(Gravity.START);
        return t;
    }
}
