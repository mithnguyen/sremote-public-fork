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

    private static String get(String url) throws Exception {
        try (Response r = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
            return r.body().string();
        }
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

    /** POST /api/sessions → new session id */
    static void createSession(Host h, String workspace, String shell, String name, Cb<String> cb) {
        run(cb, () -> {
            JSONObject b = new JSONObject();
            if (workspace != null) b.put("workspace", workspace);
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
