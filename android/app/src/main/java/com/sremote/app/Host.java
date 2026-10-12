package com.sremote.app;

import org.json.JSONException;
import org.json.JSONObject;

public class Host {
    public String id;
    public String name;
    public String url;   // http://100.x.x.x:2209
    public boolean notify;
    /** avatar tint index into Ui.HOST_COLORS; -1 = derive from name */
    public int color = -1;

    public Host(String id, String name, String url, boolean notify) {
        this.id = id; this.name = name; this.url = url; this.notify = notify;
    }

    public static Host fromJson(JSONObject o) {
        Host h = new Host(
                o.optString("id"),
                o.optString("name"),
                o.optString("url"),
                o.optBoolean("notify", true));
        h.color = o.optInt("color", -1);
        return h;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("name", name).put("url", url).put("notify", notify).put("color", color);
        } catch (JSONException ignored) {}
        return o;
    }

    /** normalize user input like "100.64.1.2" or "host:2209" into http url */
    public static String normalizeUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return null;
        if (!u.contains("://")) u = "http://" + u;
        u = u.replaceAll("/+$", "");
        if (!u.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+")) return null;
        if (u.matches("^http://[^/:]+$")) u = u + ":2209"; // bare IP/host → default port
        return u;
    }

    public String wsUrl() {
        String u = url;
        if (u.startsWith("https://")) u = "wss://" + u.substring(8);
        else if (u.startsWith("http://")) u = "ws://" + u.substring(7);
        else u = "ws://" + u;
        return u + "/ws";
    }

    public String hostPort() {
        String u = url.replaceFirst("^[a-zA-Z]+://", "");
        int s = u.indexOf('/');
        return s >= 0 ? u.substring(0, s) : u;
    }

    /** colour slot: explicit choice, else stable per name */
    public int colorIndex() {
        if (color >= 0) return color;
        return Math.abs((name == null ? id : name).hashCode()) % Ui.HOST_COLORS;
    }
}
