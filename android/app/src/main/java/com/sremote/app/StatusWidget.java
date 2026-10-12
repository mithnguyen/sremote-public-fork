package com.sremote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/** Home screen widget: counts of waiting / running / done sessions + the top three. */
public class StatusWidget extends AppWidgetProvider {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long last;
    private static boolean queued;

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { push(c); }

    /** throttled refresh (≤ 1 per 2 s) — called from the monitor on every state change */
    static void refresh(Context c) {
        Context app = c.getApplicationContext();
        if (queued) return;
        queued = true;
        long wait = Math.max(0, 2_000 - (System.currentTimeMillis() - last));
        main.postDelayed(() -> { queued = false; last = System.currentTimeMillis(); push(app); }, wait);
    }

    private static void push(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, StatusWidget.class));
        if (ids == null || ids.length == 0) return;

        HostStore store = new HostStore(c);
        int ask = 0, run = 0, done = 0, up = 0;
        List<String[]> top = new ArrayList<>(); // {rank, text}
        List<Host> hosts = store.list();
        for (Host h : hosts) {
            if (Boolean.TRUE.equals(StateBus.isConnected(h.id))) up++;
            for (StateBus.Sess s : StateBus.sessions(h.id)) {
                String mark;
                int rank;
                switch (s.state) {
                    case QUESTION: ask++; mark = "● Needs input"; rank = 0; break;
                    case RUNNING: run++; mark = "◐ Running"; rank = 1; break;
                    case DONE: done++; mark = "✓ Done"; rank = 2; break;
                    default: continue;
                }
                top.add(new String[]{String.valueOf(rank), s.name + " · " + h.name + "  —  " + mark});
            }
        }
        top.sort((a, b) -> a[0].compareTo(b[0]));

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_status);
        boolean mon = store.monitorEnabled() && StateBus.monitorRunning;
        v.setTextViewText(R.id.w_status, !mon ? "Monitoring off" : up + "/" + hosts.size() + " online");
        v.setTextViewText(R.id.w_ask, (mon ? ask : 0) + " waiting");
        v.setTextViewText(R.id.w_run, (mon ? run : 0) + " running");
        v.setTextViewText(R.id.w_done, (mon ? done : 0) + " done");
        int[] lines = {R.id.w_line1, R.id.w_line2, R.id.w_line3};
        for (int i = 0; i < lines.length; i++) {
            String t = i < top.size() ? top.get(i)[1]
                    : i == 0 ? (mon ? (hosts.isEmpty() ? "Add a host in the app" : "All quiet") : "Open S-remote to turn on monitoring")
                    : "";
            v.setTextViewText(lines[i], t);
        }
        PendingIntent pi = PendingIntent.getActivity(c, 0x3d,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_root, pi);
        m.updateAppWidget(ids, v);
    }
}
