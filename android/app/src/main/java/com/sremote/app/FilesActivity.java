package com.sremote.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native file browser over /api/files: a workspace (base "ws:<id>", relative
 * paths) or the whole machine (base "@abs", absolute paths, starting from a
 * Locations page of quick-access folders, projects and drives).
 */
public class FilesActivity extends Activity {
    static final String EXTRA_BASE = "base", EXTRA_PATH = "path", EXTRA_TITLE = "title";
    private static final String ABS = "@abs";
    private static final int REQ_UPLOAD = 91;

    private Ui ui;
    private Host host;
    private String base, title;
    /** current dir: relative ("" = root) for ws bases; absolute for @abs; null = Locations */
    private String path;
    private JSONObject listing;
    private String query = "";
    private int loadSeq;

    private TextView titleView, subView;
    private LinearLayout crumbs, list;
    private HorizontalScrollView crumbScroll;
    private View uploadBtn;

    private int dp(float v) { return ui.dp(v); }
    private void toast(String s) { Actions.toast(this, s); }
    private boolean abs() { return ABS.equals(base); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        host = new HostStore(this).find(getIntent().getStringExtra(HostActivity.EXTRA_HOST_ID));
        if (host == null) { finish(); return; }
        base = getIntent().getStringExtra(EXTRA_BASE);
        if (base == null) base = ABS;
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null && !abs()) path = "";
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = abs() ? host.name : "Files";
        ui = new Ui(this);
        ui.applyWindow(this, ui.surface);
        build();
        load();
    }

    @Override protected void onStart() { super.onStart(); LockGate.onStart(); }
    @Override protected void onStop() { LockGate.onStop(); super.onStop(); }
    @Override protected void onResume() { super.onResume(); LockGate.check(this); }

    @Override public void onBackPressed() {
        String up = parentPath();
        if (up != null || (abs() && path != null)) { go(up); return; }
        super.onBackPressed();
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
        titleView = ui.oneLine(ui.text(title, 18, ui.onSurface, true));
        tc.addView(titleView);
        subView = ui.oneLine(ui.text(host.name + " · Files", 12, ui.onSurfaceVariant, false));
        tc.addView(subView);
        LinearLayout.LayoutParams tlp = Ui.weight1();
        tlp.setMargins(dp(4), 0, 0, 0);
        bar.addView(tc, tlp);
        ImageView up = ui.iconButton(R.drawable.ic_upload, ui.primary);
        up.setContentDescription("Upload from phone");
        up.setOnClickListener(v -> pickUpload());
        bar.addView(up);
        uploadBtn = up;
        ImageView more = ui.iconButton(R.drawable.ic_more, ui.onSurfaceVariant);
        more.setContentDescription("More");
        more.setOnClickListener(v -> dirMenu());
        bar.addView(more);
        root.addView(bar);

        LinearLayout body = ui.vcol();
        body.setPadding(dp(16), 0, dp(16), 0);
        EditText[] ref = new EditText[1];
        body.addView(ui.searchField("Filter this folder", ref));
        ref[0].addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(Locale.ROOT); render(); }
        });
        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = ui.hrow();
        crumbs.setPadding(0, dp(10), 0, dp(6));
        crumbScroll.addView(crumbs);
        body.addView(crumbScroll);
        root.addView(body);

        ScrollView sv = new ScrollView(this);
        list = ui.vcol();
        list.setPadding(dp(16), 0, dp(16), dp(32));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    // ---------------- navigation ----------------
    private void go(String p) {
        path = p;
        listing = null;
        load();
    }

    private String sep() {
        String p = path != null ? path : "";
        return p.contains("\\") || p.matches("^[A-Za-z]:.*") ? "\\" : "/";
    }

    private String child(String name) {
        if (!abs()) return path == null || path.isEmpty() ? name : path + "/" + name;
        String s = sep();
        return path.endsWith(s) ? path + name : path + s + name;
    }

    /** parent of the current dir, null at the top (ws root, or a drive root → Locations) */
    private String parentPath() {
        if (listing == null) return null;
        return listing.isNull("parent") ? null : listing.optString("parent", null);
    }

    /** absolute path of an entry (for "Open terminal here" / "Copy path") */
    private String absOf(String p) {
        if (abs()) return p;
        String root = listing != null ? listing.optString("root") : "";
        if (p == null || p.isEmpty()) return root;
        String s = root.contains("\\") ? "\\" : "/";
        return root + s + p.replace("/", s);
    }

    private void load() {
        int seq = ++loadSeq;
        list.removeAllViews();
        list.addView(Actions.hint(ui, "Loading…"));
        renderCrumbs();
        uploadBtn.setAlpha(abs() && path == null ? 0.35f : 1f);
        if (abs() && path == null) { loadLocations(seq); return; }
        Api.files(host, base, path, (j, err) -> {
            if (seq != loadSeq) return;
            if (j == null) { showError(err); return; }
            listing = j;
            path = j.optString("path", path);
            renderCrumbs();
            render();
        });
    }

    private void showError(String err) {
        list.removeAllViews();
        LinearLayout e = ui.emptyState(R.drawable.ic_shield, "Can't open this folder",
                (err != null ? err : "offline") + "\nCheck that " + host.name + " is online and Tailscale is on.");
        list.addView(e);
        TextView retry = ui.tonalButton("Retry");
        retry.setOnClickListener(v -> load());
        list.addView(retry, ui.margins(0, 12, 0, 0));
    }

    // ---------------- Locations (@abs root) ----------------
    private void loadLocations(int seq) {
        listing = null;
        Api.browse(host, "", (j, err) -> Api.workspaces(host, (wss, e2) -> {
            if (seq != loadSeq) return;
            if (j == null) { showError(err); return; }
            list.removeAllViews();
            JSONArray qa = j.optJSONArray("quickAccess");
            if (qa != null && qa.length() > 0) {
                list.addView(ui.section("Quick access", null));
                LinearLayout g = group();
                for (int i = 0; i < qa.length(); i++) {
                    JSONObject q = qa.optJSONObject(i);
                    if (q == null || !matches(q.optString("name"))) continue;
                    g.addView(locRow(R.drawable.ic_folder, ui.onTertiaryContainer, ui.tertiaryContainer, q.optString("name"), q.optString("path")));
                }
                list.addView(g);
            }
            if (wss != null && wss.length() > 0) {
                list.addView(ui.section("Projects", null));
                LinearLayout g = group();
                for (int i = 0; i < wss.length(); i++) {
                    JSONObject w = wss.optJSONObject(i);
                    if (w == null || !matches(w.optString("name"))) continue;
                    g.addView(locRow(R.drawable.ic_terminal, ui.onPrimaryContainer, ui.primaryContainer, w.optString("name"), w.optString("path")));
                }
                list.addView(g);
            }
            JSONArray dr = j.optJSONArray("items");
            if (dr != null && dr.length() > 0) {
                list.addView(ui.section("Drives", null));
                LinearLayout g = group();
                for (int i = 0; i < dr.length(); i++) {
                    JSONObject d = dr.optJSONObject(i);
                    if (d == null || !matches(d.optString("name"))) continue;
                    g.addView(locRow(R.drawable.ic_hosts, ui.onSecondaryContainer, ui.secondaryContainer, d.optString("name"), d.optString("path")));
                }
                list.addView(g);
            }
        }));
    }

    private View locRow(int icon, int fg, int bg, String name, String p) {
        LinearLayout r = ui.listRow(icon, fg, bg, name, p, null);
        r.setOnClickListener(v -> go(p));
        return r;
    }

    private LinearLayout group() {
        LinearLayout g = ui.vcol();
        g.setBackground(ui.glass(22));
        g.setPadding(dp(4), dp(4), dp(4), dp(4));
        return g;
    }

    private boolean matches(String name) {
        return query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query);
    }

    // ---------------- breadcrumbs ----------------
    private void renderCrumbs() {
        crumbs.removeAllViews();
        List<String[]> cs = new ArrayList<>(); // {label, path|null}
        if (abs()) {
            cs.add(new String[]{"Locations", null});
            if (path != null && !path.isEmpty()) {
                String s = sep();
                String[] parts = path.split(s.equals("\\") ? "\\\\" : "/");
                StringBuilder acc = new StringBuilder(s.equals("/") ? "/" : "");
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    if (acc.length() > 0 && !acc.toString().endsWith(s)) acc.append(s);
                    acc.append(part);
                    String at = acc.toString();
                    if (at.matches("^[A-Za-z]:$")) at += "\\"; // drive root
                    cs.add(new String[]{part, at});
                }
            }
        } else {
            cs.add(new String[]{title, ""});
            if (path != null && !path.isEmpty()) {
                StringBuilder acc = new StringBuilder();
                for (String part : path.split("[\\\\/]")) {
                    if (part.isEmpty()) continue;
                    if (acc.length() > 0) acc.append('/');
                    acc.append(part);
                    cs.add(new String[]{part, acc.toString()});
                }
            }
        }
        for (int i = 0; i < cs.size(); i++) {
            String[] c = cs.get(i);
            boolean last = i == cs.size() - 1;
            if (i > 0) {
                TextView sep = ui.text("›", 15, ui.outline, false);
                sep.setPadding(dp(4), 0, dp(4), 0);
                crumbs.addView(sep);
            }
            TextView t = ui.chip(c[0], last);
            if (!last) t.setOnClickListener(v -> go(c[1]));
            crumbs.addView(t);
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
        String where = abs() ? (path == null ? "Locations" : path) : (path == null || path.isEmpty() ? title : path);
        subView.setText(host.name + " · " + where);
    }

    // ---------------- listing ----------------
    private void render() {
        if (listing == null) { if (abs() && path == null) load(); return; }
        list.removeAllViews();
        JSONArray items = listing.optJSONArray("items");
        int dirs = 0, files = 0;
        LinearLayout g = group();
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || !matches(it.optString("name"))) continue;
            if (it.optBoolean("dir")) dirs++; else files++;
            g.addView(itemRow(it));
        }
        if (dirs + files == 0) {
            list.addView(ui.emptyState(R.drawable.ic_folder, query.isEmpty() ? "Empty folder" : "Nothing matches",
                    query.isEmpty() ? "Upload something with the ↑ button." : null));
            return;
        }
        TextView meta = ui.text((dirs > 0 ? dirs + " folder" + (dirs == 1 ? "" : "s") : "")
                + (dirs > 0 && files > 0 ? " · " : "") + (files > 0 ? files + " file" + (files == 1 ? "" : "s") : ""),
                12, ui.onSurfaceVariant, false);
        meta.setPadding(dp(4), dp(4), 0, dp(8));
        list.addView(meta);
        list.addView(g);
    }

    private View itemRow(JSONObject it) {
        String name = it.optString("name");
        boolean dir = it.optBoolean("dir");
        Kind k = dir ? Kind.DIR : kindOf(name);
        int icon, fg, bg;
        switch (k) {
            case DIR: icon = R.drawable.ic_folder; fg = ui.onTertiaryContainer; bg = ui.tertiaryContainer; break;
            case IMAGE: icon = R.drawable.ic_image; fg = ui.onSecondaryContainer; bg = ui.secondaryContainer; break;
            case MEDIA: icon = R.drawable.ic_play; fg = ui.onSecondaryContainer; bg = ui.secondaryContainer; break;
            case ARCHIVE: icon = R.drawable.ic_archive; fg = ui.warn; bg = ui.warnContainer; break;
            case WEB: icon = R.drawable.ic_globe; fg = ui.onPrimaryContainer; bg = ui.primaryContainer; break;
            default: icon = R.drawable.ic_file; fg = ui.onSurfaceVariant; bg = ui.containerHigh;
        }
        long mt = it.optLong("mtime");
        String sub = (dir ? "Folder" : size(it.optLong("size")))
                + (mt > 0 ? " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(mt)) : "");
        ImageView more = ui.iconButton(R.drawable.ic_more, ui.onSurfaceVariant);
        more.setContentDescription("Actions for " + name);
        LinearLayout r = ui.listRow(icon, fg, bg, name, sub, more);
        String p = child(name);
        more.setOnClickListener(v -> itemMenu(it, p, k));
        r.setOnClickListener(v -> { if (dir) go(p); else preview(it, p, k); });
        r.setOnLongClickListener(v -> { ui.haptic(v); itemMenu(it, p, k); return true; });
        return r;
    }

    enum Kind { DIR, IMAGE, MEDIA, WEB, TEXT, PDF, ARCHIVE, OTHER }

    static Kind kindOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        if (n.matches(".*\\.(tar\\.(gz|bz2|xz|zst))$")) return Kind.ARCHIVE;
        switch (ext) {
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "bmp": case "svg": case "ico": case "avif":
                return Kind.IMAGE;
            case "mp4": case "webm": case "mov": case "m4v": case "mkv": case "mp3": case "wav": case "ogg": case "m4a": case "flac": case "aac":
                return Kind.MEDIA;
            case "html": case "htm":
                return Kind.WEB;
            case "pdf":
                return Kind.PDF;
            case "zip": case "7z": case "rar": case "tar": case "gz": case "tgz": case "bz2": case "xz": case "txz": case "tbz2":
                return Kind.ARCHIVE;
            case "": case "txt": case "md": case "markdown": case "json": case "jsonl": case "js": case "mjs": case "cjs": case "ts": case "tsx":
            case "jsx": case "css": case "scss": case "less": case "log": case "csv": case "tsv": case "xml": case "yml": case "yaml": case "ini":
            case "cfg": case "conf": case "toml": case "env": case "py": case "java": case "kt": case "kts": case "gradle": case "c": case "h":
            case "cpp": case "hpp": case "cc": case "cs": case "go": case "rs": case "rb": case "php": case "sh": case "bash": case "zsh":
            case "ps1": case "psm1": case "bat": case "cmd": case "sql": case "properties": case "gitignore": case "dockerfile": case "lock":
            case "vue": case "svelte": case "swift": case "dart": case "lua": case "r": case "srt": case "vtt": case "diff": case "patch":
                return Kind.TEXT;
            default:
                return Kind.OTHER;
        }
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    // ---------------- actions ----------------
    private void itemMenu(JSONObject it, String p, Kind k) {
        String name = it.optString("name");
        boolean dir = k == Kind.DIR;
        List<String> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (dir) {
            labels.add("Open"); icons.add(R.drawable.ic_folder); acts.add(() -> go(p));
            labels.add("Open terminal here"); icons.add(R.drawable.ic_terminal); acts.add(() -> terminalHere(absOf(p), name));
        } else {
            labels.add("Preview"); icons.add(R.drawable.ic_open); acts.add(() -> preview(it, p, k));
            labels.add("Download to phone"); icons.add(R.drawable.ic_download); acts.add(() -> download(p, name));
        }
        if (k == Kind.ARCHIVE) { labels.add("Extract here"); icons.add(R.drawable.ic_archive); acts.add(() -> op("extract", p, null, "Extracting " + name + "…")); }
        else { labels.add("Compress to .zip"); icons.add(R.drawable.ic_archive); acts.add(() -> op("zip", p, null, "Compressing " + name + "…")); }
        labels.add("Rename"); icons.add(R.drawable.ic_edit); acts.add(() -> rename(p, name));
        labels.add("Copy path"); icons.add(R.drawable.ic_copy); acts.add(() -> copy(absOf(p)));
        labels.add("Delete"); icons.add(R.drawable.ic_delete); acts.add(() -> confirmDelete(p, name, dir));
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        ui.menuSheet(this, name, labels.toArray(new String[0]), ic, i -> acts.get(i).run()).show();
    }

    private void dirMenu() {
        boolean loc = abs() && path == null;
        String here = loc ? null : absOf(path);
        List<String> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("Refresh"); icons.add(R.drawable.ic_refresh); acts.add(this::load);
        if (!loc) {
            labels.add("Upload from phone"); icons.add(R.drawable.ic_upload); acts.add(this::pickUpload);
            labels.add("New folder"); icons.add(R.drawable.ic_folder_add); acts.add(this::newFolder);
            labels.add("Open terminal here"); icons.add(R.drawable.ic_terminal); acts.add(() -> terminalHere(here, null));
            labels.add("Copy path"); icons.add(R.drawable.ic_copy); acts.add(() -> copy(here));
        }
        labels.add("Open web explorer"); icons.add(R.drawable.ic_open);
        acts.add(() -> Actions.openTerminal(this, host, "fx=" + (here != null ? here : "")));
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        ui.menuSheet(this, loc ? "Locations" : here, labels.toArray(new String[0]), ic, i -> acts.get(i).run()).show();
    }

    private void terminalHere(String cwd, String name) {
        Api.createSession(host, null, null, name, cwd, (id, err) -> {
            if (id == null || id.isEmpty()) { toast("Couldn't start a terminal" + (err != null ? ": " + err : "")); return; }
            StateBus.changed();
            Actions.openTerminal(this, host, "s=" + id);
        });
    }

    private void op(String op, String p, String name, String busy) {
        AlertDialog wait = new AlertDialog.Builder(this, ui.dialogTheme()).setMessage(busy).setCancelable(false).show();
        try {
            JSONObject b = new JSONObject().put("base", base).put("op", op).put("path", p);
            if (name != null) b.put("name", name);
            Api.fileop(host, b, (j, err) -> {
                wait.dismiss();
                if (j == null) { toast("Failed: " + err); return; }
                if (j.has("dir")) toast("Extracted to " + leaf(j.optString("dir")));
                else if (j.has("zip")) toast("Created " + leaf(j.optString("zip")));
                load();
            });
        } catch (Exception e) { wait.dismiss(); }
    }

    private static String leaf(String p) {
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private void rename(String p, String current) {
        EditText in = ui.input("New name");
        in.setText(current);
        int dot = current.lastIndexOf('.');
        in.setSelection(0, dot > 0 ? dot : current.length());
        prompt("Rename", in, "Rename", () -> {
            String n = in.getText().toString().trim();
            if (!n.isEmpty() && !n.equals(current)) op("rename", p, n, "Renaming…");
        });
    }

    private void newFolder() {
        EditText in = ui.input("Folder name");
        prompt("New folder", in, "Create", () -> {
            String n = in.getText().toString().trim();
            if (!n.isEmpty()) op("mkdir", abs() ? path : (path.isEmpty() ? "." : path), n, "Creating…");
        });
    }

    private void prompt(String t, EditText in, String ok, Runnable done) {
        LinearLayout box = ui.vcol();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(in);
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(t).setView(box)
                .setPositiveButton(ok, (d, w) -> done.run())
                .setNegativeButton("Cancel", null).show();
        in.requestFocus();
    }

    private void confirmDelete(String p, String name, boolean dir) {
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle("Delete “" + name + "”?")
                .setMessage((dir ? "The folder and everything in it is" : "The file is") + " deleted on " + host.name + ". This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> op("delete", p, null, "Deleting…"))
                .setNegativeButton("Cancel", null).show();
    }

    private void copy(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("path", text));
        toast("Copied");
    }

    private void download(String p, String name) {
        Actions.download(this, Api.fileUrl(host, base, p, true), name);
    }

    // ---------------- preview ----------------
    private void preview(JSONObject it, String p, Kind k) {
        String name = it.optString("name");
        long sz = it.optLong("size");
        switch (k) {
            case IMAGE: case MEDIA: case WEB:
                webPreview(name, p, k);
                break;
            case TEXT:
                textPreview(name, p, sz);
                break;
            case PDF: case ARCHIVE:
                download(p, name);
                break;
            default:
                if (sz <= 256 * 1024) textPreview(name, p, sz);
                else new AlertDialog.Builder(this, ui.dialogTheme())
                        .setTitle(name).setMessage(size(sz) + " — no preview for this type.")
                        .setPositiveButton("Download", (d, w) -> download(p, name))
                        .setNegativeButton("Cancel", null).show();
        }
    }

    /** full-screen dialog with a slim toolbar (close · title · download) */
    private Dialog viewer(String name, String p, View content) {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout col = ui.vcol();
        col.setBackgroundColor(ui.surface);
        LinearLayout bar = ui.hrow();
        bar.setPadding(dp(2), dp(4), dp(2), dp(4));
        bar.setBackgroundColor(ui.container);
        ImageView close = ui.iconButton(R.drawable.ic_close, ui.onSurface);
        close.setOnClickListener(v -> d.dismiss());
        bar.addView(close);
        TextView t = ui.oneLine(ui.text(name, 16, ui.onSurface, true));
        t.setPadding(dp(4), 0, dp(4), 0);
        bar.addView(t, Ui.weight1());
        ImageView dl = ui.iconButton(R.drawable.ic_download, ui.primary);
        dl.setContentDescription("Download");
        dl.setOnClickListener(v -> download(p, name));
        bar.addView(dl);
        col.addView(bar);
        col.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        d.setContentView(col);
        if (d.getWindow() != null) {
            d.getWindow().setStatusBarColor(ui.container);
            d.getWindow().setNavigationBarColor(ui.surface);
        }
        return d;
    }

    private void webPreview(String name, String p, Kind k) {
        WebView w = new WebView(this);
        w.getSettings().setJavaScriptEnabled(k == Kind.WEB);
        w.getSettings().setMediaPlaybackRequiresUserGesture(false);
        w.getSettings().setBuiltInZoomControls(true);
        w.getSettings().setDisplayZoomControls(false);
        w.setBackgroundColor(k == Kind.WEB ? 0xFFFFFFFF : 0xFF000000);
        Dialog d = viewer(name, p, w);
        d.setOnDismissListener(x -> w.destroy());
        if (k == Kind.WEB) w.loadUrl(Api.rawUrl(host, base, p));
        else {
            String src = Api.fileUrl(host, base, p, false).replace("&", "&amp;").replace("\"", "&quot;");
            String n = name.toLowerCase(Locale.ROOT);
            boolean audio = n.matches(".*\\.(mp3|wav|ogg|m4a|flac|aac)$");
            String tag = k == Kind.IMAGE ? "<img src=\"" + src + "\">"
                    : audio ? "<audio controls autoplay src=\"" + src + "\"></audio>"
                    : "<video controls autoplay playsinline src=\"" + src + "\"></video>";
            String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<style>html,body{margin:0;height:100%;background:#000;display:flex;align-items:center;justify-content:center}"
                    + "img,video{max-width:100%;max-height:100vh}audio{width:90%}</style>" + tag;
            w.loadDataWithBaseURL(host.url + "/", html, "text/html", "utf-8", null);
        }
        d.show();
    }

    private void textPreview(String name, String p, long sz) {
        TextView tv = ui.text("Loading…", 12, ui.onSurface, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(12), dp(14), dp(24));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tv);
        ScrollView sv = new ScrollView(this);
        sv.addView(hs);
        FrameLayout fr = new FrameLayout(this);
        fr.setBackgroundColor(ui.dark ? 0xFF0D1117 : 0xFFFAFBFD);
        fr.addView(sv);
        Dialog d = viewer(name, p, fr);
        d.show();
        Api.textHead(host, base, p, (txt, err) -> {
            if (txt == null) { tv.setText("Couldn't load: " + err); return; }
            String s = txt.length() > 200_000 ? txt.substring(0, 200_000) : txt;
            if (sz > 512 * 1024 || txt.length() > 200_000) s += "\n\n… preview truncated (" + size(sz) + ") — download for the full file";
            tv.setText(s);
        });
    }

    // ---------------- upload ----------------
    private void pickUpload() {
        if (abs() && path == null) { toast("Open a folder first"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_UPLOAD); } catch (Exception e) { toast("No file picker on this phone"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_UPLOAD || res != RESULT_OK || data == null) { super.onActivityResult(req, res, data); return; }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (!uris.isEmpty()) uploadAll(uris);
    }

    private void uploadAll(List<Uri> uris) {
        // ws root: "" would make the server fall back to temp-upload, so say "." explicitly
        String dir = abs() ? path : (path == null || path.isEmpty() ? "." : path);
        AlertDialog wait = new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle("Uploading").setMessage("Starting…").setCancelable(false).show();
        int[] idx = {0, 0}; // next, failed
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (idx[0] >= uris.size()) {
                wait.dismiss();
                int ok = uris.size() - idx[1];
                toast(idx[1] == 0 ? "Uploaded " + ok + " file" + (ok == 1 ? "" : "s") : ok + " uploaded, " + idx[1] + " failed");
                load();
                return;
            }
            Uri u = uris.get(idx[0]);
            String n = displayName(u);
            wait.setMessage((idx[0] + 1) + " / " + uris.size() + "  ·  " + n);
            Api.upload(this, host, base, dir, u, n, (j, err) -> {
                if (j == null) idx[1]++;
                idx[0]++;
                next[0].run();
            });
        };
        next[0].run();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        String last = u.getLastPathSegment();
        return last != null ? leaf(last) : "upload.bin";
    }

    @Override protected void onDestroy() {
        loadSeq++;
        super.onDestroy();
    }
}
