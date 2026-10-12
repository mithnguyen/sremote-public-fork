package com.sremote.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One session as the UI sees it: REST metadata (/api/sessions) overlaid with live monitor state. */
final class SessionItem {
    final Host host;
    final String id;
    String name, cwd = "", workspace, shell = "", line = "", question;
    boolean exited, live;   // live = state comes from the monitor
    long createdAt, since;
    Detector.State state = Detector.State.IDLE;

    SessionItem(Host h, String id) { host = h; this.id = id; }

    /** rest may be null (host unreachable / not fetched) → monitor state only */
    static List<SessionItem> merge(Host h, JSONArray rest) {
        Map<String, StateBus.Sess> liveById = new HashMap<>();
        for (StateBus.Sess s : StateBus.sessions(h.id)) liveById.put(s.id, s);
        List<SessionItem> out = new ArrayList<>();
        if (rest != null) {
            for (int i = 0; i < rest.length(); i++) {
                JSONObject o = rest.optJSONObject(i);
                if (o == null) continue;
                SessionItem it = new SessionItem(h, o.optString("id"));
                it.name = o.optString("name", it.id);
                it.cwd = o.optString("cwd", "");
                it.workspace = o.isNull("workspace") ? null : o.optString("workspace", null);
                it.shell = o.optString("shell", "");
                it.exited = o.optBoolean("exited");
                it.createdAt = o.optLong("createdAt");
                it.since = it.createdAt;
                if (it.exited) it.state = Detector.State.EXITED;
                overlay(it, liveById.remove(it.id));
                out.add(it);
            }
        }
        for (StateBus.Sess s : liveById.values()) { // monitor knows it, REST didn't answer
            SessionItem it = new SessionItem(h, s.id);
            it.name = s.name;
            overlay(it, s);
            it.exited = it.state == Detector.State.EXITED;
            out.add(it);
        }
        return out;
    }

    private static void overlay(SessionItem it, StateBus.Sess s) {
        if (s == null) return;
        it.live = true;
        if (!it.exited) it.state = s.state;
        it.line = s.line == null ? "" : s.line;
        it.question = s.question;
        it.since = s.since;
        if (s.name != null) it.name = s.name;
    }

    /** sort key: needs input → running → done → idle → exited */
    int rank() {
        switch (state) {
            case QUESTION: return 0;
            case RUNNING: return 1;
            case DONE: return 2;
            case EXITED: return 4;
            default: return 3;
        }
    }

    String subtitle() {
        if (state == Detector.State.QUESTION && question != null) return question.replace('\n', ' ');
        if (line != null && !line.isEmpty()) return line;
        return cwd;
    }

    boolean matches(String q) {
        if (q == null || q.isEmpty()) return true;
        q = q.toLowerCase();
        return contains(name, q) || contains(host.name, q) || contains(cwd, q) || contains(line, q);
    }

    private static boolean contains(String s, String q) { return s != null && s.toLowerCase().contains(q); }
}
