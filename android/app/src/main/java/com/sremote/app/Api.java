package com.sremote.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Shared HTTP/WS access to S-remote servers. Callbacks always run on the main thread. */
final class Api {
    interface Cb<T> { void done(T result, String error); }

    static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();
    /** long-lived sockets (session preview): no read timeout, keepalive pings */
    static final OkHttpClient ws = http.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private Api() {}

    /** result of a liveness probe */
    static final class Info {
        boolean online;
        long ms;
        JSONObject info;  // /api/info body
    }

    /** archive / diff / upload work can take minutes */
    static final OkHttpClient slow = http.newBuilder()
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    private static String get(String url) throws Exception { return get(http, url); }

    private static String get(OkHttpClient cl, String url) throws Exception {
        try (Response r = cl.newCall(new Request.Builder().url(url).build()).execute()) {
            return body(r);
        }
    }

    /** body on success; on failure the server's {error} message when it sent one */
    private static String body(Response r) throws Exception {
        String b = r.body() != null ? r.body().string() : "";
        if (r.isSuccessful()) return b;
        String msg = null;
        try { msg = new JSONObject(b).optString("error", null); } catch (Exception ignored) {}
        throw new Exception(msg != null && !msg.isEmpty() ? msg : "HTTP " + r.code());
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return ""; }
    }

    private static <T> void run(Cb<T> cb, Task<T> t) {
        pool.execute(() -> {
            T v = null; String err = null;
            try { v = t.call(); } catch (Exception e) { err = e.getMessage() != null ? e.getMessage() : e.toString(); }
            final T fv = v; final String fe = err;
            if (cb != null) main.post(() -> cb.done(fv, fe));
        });
    }

    private interface Task<T> { T call() throws Exception; }

    // ---------- blocking variants (call from a worker) ----------
    static Info infoSync(String base) {
        Info i = new Info();
        long t0 = System.nanoTime();
        try {
            i.info = new JSONObject(get(base + "/api/info"));
            i.online = true;
            i.ms = (System.nanoTime() - t0) / 1_000_000;
        } catch (Exception ignored) {}
        return i;
    }

    // ---------- async ----------
    static void info(Host h, Cb<Info> cb) { run(cb, () -> infoSync(h.url)); }
    static void info(String base, Cb<Info> cb) { run(cb, () -> infoSync(base)); }

    static void sessions(Host h, Cb<JSONArray> cb) { run(cb, () -> new JSONArray(get(h.url + "/api/sessions"))); }

    static void workspaces(Host h, Cb<JSONArray> cb) { run(cb, () -> new JSONArray(get(h.url + "/api/workspaces"))); }

    static void peers(Host h, Cb<JSONArray> cb) {
        run(cb, () -> {
            JSONArray a = new JSONObject(get(h.url + "/api/peers")).optJSONArray("peers");
            return a != null ? a : new JSONArray();
        });
    }

    // ---------- files / git / dev servers ----------
    /** base: "ws:<id>" (workspace-jailed) | "@abs" (whole filesystem, absolute paths) */
    static void files(Host h, String base, String path, Cb<JSONObject> cb) {
        run(cb, () -> new JSONObject(get(h.url + "/api/files?base=" + enc(base) + "&path=" + enc(path))));
    }

    /** drives + quick access folders (the explorer's "Locations" page) */
    static void browse(Host h, String path, Cb<JSONObject> cb) {
        run(cb, () -> new JSONObject(get(h.url + "/api/browse?path=" + enc(path))));
    }

    static String fileUrl(Host h, String base, String path, boolean download) {
        return h.url + "/api/file?base=" + enc(base) + "&path=" + enc(path) + (download ? "&dl=1" : "");
    }

    /** path-style url so html previews resolve their relative assets */
    static String rawUrl(Host h, String base, String path) {
        StringBuilder b = new StringBuilder(h.url).append("/raw/").append(enc(base));
        if (path.startsWith("/")) b.append('/'); // unix absolute: empty first segment keeps the leading slash
        for (String seg : path.replace('\\', '/').split("/")) if (!seg.isEmpty()) b.append('/').append(enc(seg));
        return b.toString();
    }

    /** first 512 KB as text, for previews */
    static void textHead(Host h, String base, String path, Cb<String> cb) {
        run(cb, () -> get(fileUrl(h, base, path, false) + "&head=1"));
    }

    /** POST /api/fileop {base, op, path, name?, dest?} → server reply ({} | {dir} | {zip}) */
    static void fileop(Host h, JSONObject b, Cb<JSONObject> cb) {
        run(cb, () -> {
            Request r = new Request.Builder().url(h.url + "/api/fileop")
                    .post(RequestBody.create(b.toString(), JSON)).build();
            try (Response res = slow.newCall(r).execute()) { return new JSONObject(body(res)); }
        });
    }

    /** stream a picked document into <base>/<dir>/name */
    static void upload(android.content.Context c, Host h, String base, String dir, android.net.Uri uri, String name, Cb<JSONObject> cb) {
        run(cb, () -> {
            android.content.ContentResolver cr = c.getContentResolver();
            String mime = cr.getType(uri);
            RequestBody rb = new RequestBody() {
                @Override public MediaType contentType() {
                    return MediaType.parse(mime != null ? mime : "application/octet-stream");
                }
                @Override public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
                    try (java.io.InputStream in = cr.openInputStream(uri)) {
                        if (in == null) throw new java.io.IOException("can't read " + name);
                        sink.writeAll(okio.Okio.source(in));
                    }
                }
            };
            String url = h.url + "/api/upload?base=" + enc(base) + "&name=" + enc(name)
                    + (dir != null ? "&dir=" + enc(dir) : "");
            try (Response res = slow.newCall(new Request.Builder().url(url).post(rb).build()).execute()) {
                return new JSONObject(body(res));
            }
        });
    }

    static void git(Host h, String base, Cb<JSONObject> cb) {
        run(cb, () -> new JSONObject(get(h.url + "/api/git?base=" + enc(base))));
    }

    static void gitDiff(Host h, String base, Cb<JSONObject> cb) {
        run(cb, () -> new JSONObject(get(slow, h.url + "/api/gitdiff?base=" + enc(base))));
    }

    /** listening TCP ports on the host: [{port, addr, pid, loopback, proc}] */
    static void devports(Host h, Cb<JSONArray> cb) {
        run(cb, () -> {
            JSONArray a = new JSONObject(get(h.url + "/api/devports")).optJSONArray("ports");
            return a != null ? a : new JSONArray();
        });
    }

    /** POST /api/sessions → new session id */
    static void createSession(Host h, String workspace, String shell, String name, Cb<String> cb) {
        createSession(h, workspace, shell, name, null, cb);
    }

    /** cwd: absolute start dir for a standalone session (ignored when workspace is set) */
    static void createSession(Host h, String workspace, String shell, String name, String cwd, Cb<String> cb) {
        run(cb, () -> {
            JSONObject b = new JSONObject();
            if (workspace != null) b.put("workspace", workspace);
            if (cwd != null && !cwd.isEmpty()) b.put("cwd", cwd);
            if (shell != null) b.put("shell", shell);
            if (name != null && !name.isEmpty()) b.put("name", name);
            Request r = new Request.Builder().url(h.url + "/api/sessions")
                    .post(RequestBody.create(b.toString(), JSON)).build();
            try (Response res = http.newCall(r).execute()) {
                if (!res.isSuccessful() || res.body() == null) throw new Exception("HTTP " + res.code());
                return new JSONObject(res.body().string()).optString("id");
            }
        });
    }

    static void killSession(Host h, String sid, Cb<Boolean> cb) {
        run(cb, () -> {
            Request r = new Request.Builder().url(h.url + "/api/sessions/" + sid).delete().build();
            try (Response res = http.newCall(r).execute()) { return res.isSuccessful(); }
        });
    }

    /**
     * fire-and-forget WS messages (rename, input) for when the monitor isn't
     * running — opens a socket, sends everything, closes shortly after
     */
    static void wsSend(Host h, JSONObject... msgs) {
        ws.newWebSocket(new Request.Builder().url(h.wsUrl()).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) {
                for (JSONObject m : msgs) s.send(m.toString());
                main.postDelayed(() -> s.close(1000, "done"), 600);
            }
        });
    }

    /** session input: through the monitor when it runs, else a one-shot socket */
    static void input(android.content.Context c, Host h, String sid, String data) {
        if (StateBus.monitorRunning) { MonitorService.sendInput(c, h.id, sid, data); return; }
        try { wsSend(h, new JSONObject().put("type", "in").put("id", sid).put("data", data)); }
        catch (Exception ignored) {}
    }

    static void rename(Host h, String sid, String name) {
        try { wsSend(h, new JSONObject().put("type", "rename").put("id", sid).put("name", name)); }
        catch (Exception ignored) {}
    }
}
