package com.sremote.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class HostStore {
    private static final String PREFS = "sremote";
    private static final String KEY_HOSTS = "hosts";
    private static final String KEY_MONITOR = "monitor_enabled";

    private final SharedPreferences sp;
    private int seq = 0;

    public HostStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Host> list() {
        List<Host> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_HOSTS, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Host.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized Host add(String name, String url) {
        Host h = new Host("h" + Long.toString(System.currentTimeMillis(), 36) + (++seq), name, url, true);
        List<Host> l = list();
        l.add(h);
        saveAll(l);
        return h;
    }

    public synchronized void update(Host h) {
        List<Host> l = list();
        for (int i = 0; i < l.size(); i++)
            if (l.get(i).id.equals(h.id)) { l.set(i, h); break; }
        saveAll(l);
    }

    public synchronized void remove(String id) {
        List<Host> l = list();
        l.removeIf(h -> h.id.equals(id));
        saveAll(l);
    }

    public synchronized Host find(String id) {
        if (id == null) return null;
        for (Host h : list()) if (h.id.equals(id)) return h;
        return null;
    }

    private void saveAll(List<Host> l) {
        JSONArray a = new JSONArray();
        for (Host h : l) a.put(h.toJson());
        sp.edit().putString(KEY_HOSTS, a.toString()).apply();
    }

    public boolean monitorEnabled() { return sp.getBoolean(KEY_MONITOR, false); }
    public void setMonitorEnabled(boolean v) { sp.edit().putBoolean(KEY_MONITOR, v).apply(); }

    // per-kind notification toggles + app lock
    public static final String N_QUESTION = "n_question", N_DONE = "n_done", N_SESSION = "n_session";
    private static final String KEY_LOCK = "lock_enabled";

    public boolean notifyKind(String key) { return sp.getBoolean(key, true); }
    public void setNotifyKind(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    public boolean lockEnabled() { return sp.getBoolean(KEY_LOCK, false); }
    public void setLockEnabled(boolean v) { sp.edit().putBoolean(KEY_LOCK, v).apply(); }

    // ---- 2.0: appearance, quiet hours, terminal, snippets, onboarding ----
    public static final String THEME_SYSTEM = "system", THEME_LIGHT = "light", THEME_DARK = "dark";

    public String theme() { return sp.getString("theme", THEME_SYSTEM); }
    public void setTheme(String t) { sp.edit().putString("theme", t).apply(); }

    public boolean quietOn() { return sp.getBoolean("quiet_on", false); }
    public void setQuietOn(boolean v) { sp.edit().putBoolean("quiet_on", v).apply(); }
    public int quietFrom() { return sp.getInt("quiet_from", 22); }
    public int quietTo() { return sp.getInt("quiet_to", 7); }
    public void setQuietRange(int from, int to) { sp.edit().putInt("quiet_from", from).putInt("quiet_to", to).apply(); }
    /** questions still ring during quiet hours (they block work) */
    public boolean quietAllowQuestions() { return sp.getBoolean("quiet_allow_q", true); }
    public void setQuietAllowQuestions(boolean v) { sp.edit().putBoolean("quiet_allow_q", v).apply(); }

    public boolean inQuietHours() {
        if (!quietOn()) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY), f = quietFrom(), t = quietTo();
        if (f == t) return true;
        return f < t ? (h >= f && h < t) : (h >= f || h < t); // range may wrap midnight
    }

    public boolean keepScreenOn() { return sp.getBoolean("keep_screen_on", false); }
    public void setKeepScreenOn(boolean v) { sp.edit().putBoolean("keep_screen_on", v).apply(); }

    /** turn Tailscale on by itself when a host is opened while the tunnel is down */
    public boolean tsAutoConnect() { return sp.getBoolean("ts_auto", true); }
    public void setTsAutoConnect(boolean v) { sp.edit().putBoolean("ts_auto", v).apply(); }

    public boolean onboarded() { return sp.getBoolean("onboarded", false); }
    public void setOnboarded(boolean v) { sp.edit().putBoolean("onboarded", v).apply(); }

    private static final String[] DEFAULT_SNIPPETS = {"git status", "git pull", "npm test", "ls", "clear", "exit"};

    public List<String> snippets() {
        List<String> out = new ArrayList<>();
        String raw = sp.getString("snippets", null);
        if (raw == null) { for (String s : DEFAULT_SNIPPETS) out.add(s); return out; }
        try { JSONArray a = new JSONArray(raw); for (int i = 0; i < a.length(); i++) out.add(a.getString(i)); }
        catch (Exception ignored) {}
        return out;
    }

    public void setSnippets(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        sp.edit().putString("snippets", a.toString()).apply();
    }

    // ---- backup: hosts + snippets as one JSON blob ----
    public String exportJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "sremote").put("v", 1);
            o.put("hosts", new JSONArray(sp.getString(KEY_HOSTS, "[]")));
            JSONArray sn = new JSONArray();
            for (String s : snippets()) sn.put(s);
            o.put("snippets", sn);
            return o.toString(2);
        } catch (Exception e) { return "{}"; }
    }

    /** merge hosts by url (existing kept), replace snippets; returns hosts added, -1 on bad input */
    public synchronized int importJson(String raw) {
        try {
            JSONObject o = new JSONObject(raw.trim());
            JSONArray hs = o.optJSONArray("hosts");
            if (hs == null) return -1;
            List<Host> l = list();
            int added = 0;
            outer:
            for (int i = 0; i < hs.length(); i++) {
                Host h = Host.fromJson(hs.getJSONObject(i));
                if (h.url == null || h.url.isEmpty()) continue;
                for (Host e : l) if (e.url.equals(h.url)) continue outer;
                if (h.id == null || h.id.isEmpty()) h.id = "h" + Long.toString(System.currentTimeMillis(), 36) + (++seq);
                l.add(h); added++;
            }
            saveAll(l);
            JSONArray sn = o.optJSONArray("snippets");
            if (sn != null) {
                List<String> s = new ArrayList<>();
                for (int i = 0; i < sn.length(); i++) s.add(sn.getString(i));
                setSnippets(s);
            }
            return added;
        } catch (Exception e) { return -1; }
    }
}
