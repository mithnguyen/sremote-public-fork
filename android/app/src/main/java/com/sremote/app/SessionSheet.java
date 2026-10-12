package com.sremote.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Live session preview + quick reply, without opening the web terminal.
 * Owns a websocket that attaches to the session (no cols/rows → the PTY is
 * never resized), feeds a private Detector, and renders its screen tail.
 */
final class SessionSheet {
    private static final int LINES = 16;
    private static final int TERM_BG = 0xFF0E1116, TERM_FG = 0xFFD5DAE3, TERM_DIM = 0xFF7D8590;

    private final Activity a;
    private final Ui ui;
    private final SessionItem s;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Detector det = new Detector();
    private Dialog dialog;
    private WebSocket ws;
    private boolean open, seeded, closed, exited, renderQueued;
    private long lastOut;

    private TextView screen, statusText, statePill;
    private View stateDot;
    private LinearLayout answers;
    private EditText input;

    static void show(Activity a, SessionItem s) { new SessionSheet(a, s).build(); }

    private SessionSheet(Activity a, SessionItem s) {
        this.a = a; this.s = s; this.ui = new Ui(a);
        exited = s.exited;
    }

    private int dp(float v) { return ui.dp(v); }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            det.tick(System.currentTimeMillis());
            render();
            main.postDelayed(this, 1000);
        }
    };

    private void build() {
        LinearLayout col = ui.vcol();
        col.setPadding(dp(20), 0, dp(20), dp(8));

        // header: dot · name · state pill
        LinearLayout head = ui.hrow();
        stateDot = ui.dot(ui.stateColor(s.state), 10);
        head.addView(stateDot);
        LinearLayout tc = ui.vcol();
        tc.setPadding(dp(12), 0, dp(8), 0);
        tc.addView(ui.oneLine(ui.text(s.name, 19, ui.onSurface, true)));
        String sub = s.host.name + (s.cwd != null && !s.cwd.isEmpty() ? " · " + s.cwd : "");
        TextView st = ui.text(sub, 12, ui.onSurfaceVariant, false);
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.START);
        tc.addView(st);
        head.addView(tc, Ui.weight1());
        statePill = ui.pill(Ui.stateLabel(s.state), ui.stateColor(s.state), ui.stateContainer(s.state));
        head.addView(statePill);
        col.addView(head);

        // terminal preview
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackground(ui.round(TERM_BG, 16));
        hs.setPadding(dp(12), dp(10), dp(12), dp(10));
        screen = new TextView(a);
        screen.setTypeface(Typeface.MONOSPACE);
        screen.setTextSize(11.5f);
        screen.setTextColor(TERM_FG);
        screen.setLines(LINES);
        screen.setHorizontallyScrolling(true);
        screen.setGravity(Gravity.BOTTOM);
        screen.setTextIsSelectable(true);
        screen.setText(exited ? "[session exited — open it in the terminal to restart]" : "connecting…");
        hs.addView(screen);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(14), 0, 0);
        col.addView(hs, slp);

        statusText = ui.text("", 12, ui.onSurfaceVariant, false);
        statusText.setPadding(dp(4), dp(6), 0, 0);
        col.addView(statusText);

        // quick answers (filled when the session asks something)
        answers = ui.hrow();
        answers.setPadding(0, dp(8), 0, 0);
        col.addView(answers);

        if (!exited) {
            // special keys
            HorizontalScrollView ks = new HorizontalScrollView(a);
            ks.setHorizontalScrollBarEnabled(false);
            LinearLayout keys = ui.hrow();
            keys.setPadding(0, dp(10), 0, 0);
            String[][] k = {{"Ctrl+C", "\u0003"}, {"Enter", "\r"}, {"Esc", "\u001b"}, {"Tab", "\t"},
                    {"↑", "\u001b[A"}, {"↓", "\u001b[B"}, {"y", "y\r"}, {"n", "n\r"}, {"Ctrl+D", "\u0004"}};
            for (String[] kk : k) {
                TextView c = ui.chip(kk[0], false);
                c.setTypeface(Typeface.MONOSPACE);
                c.setOnClickListener(v -> { ui.haptic(v); send(kk[1]); });
                keys.addView(c, ui.margins(0, 0, 6, 0));
            }
            ks.addView(keys);
            col.addView(ks);

            // snippets
            List<String> snips = new HostStore(a).snippets();
            if (!snips.isEmpty()) {
                HorizontalScrollView ss = new HorizontalScrollView(a);
                ss.setHorizontalScrollBarEnabled(false);
                LinearLayout row = ui.hrow();
                row.setPadding(0, dp(8), 0, 0);
                for (String sn : snips) {
                    TextView c = ui.chip("⚡ " + sn, false);
                    c.setOnClickListener(v -> { ui.haptic(v); send(sn + "\r"); });
                    row.addView(c, ui.margins(0, 0, 6, 0));
                }
                ss.addView(row);
                col.addView(ss);
            }

            // input row
            LinearLayout ir = ui.hrow();
            ir.setPadding(0, dp(12), 0, 0);
            input = ui.input("Type a command or reply…");
            input.setTypeface(Typeface.MONOSPACE);
            input.setImeOptions(EditorInfo.IME_ACTION_SEND);
            input.setOnEditorActionListener((v, id, ev) -> {
                if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { sendInput(); return true; }
                return false;
            });
            ir.addView(input, Ui.weight1());
            ImageView sendBtn = ui.iconButton(R.drawable.ic_send, ui.onPrimary);
            sendBtn.setBackground(ui.ripple(ui.oval(ui.primary), 24));
            sendBtn.setContentDescription("Send");
            sendBtn.setOnClickListener(v -> sendInput());
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(48), dp(48));
            blp.setMargins(dp(8), 0, 0, 0);
            ir.addView(sendBtn, blp);
            col.addView(ir);
        }

        // actions
        LinearLayout act = ui.hrow();
        act.setPadding(0, dp(14), 0, 0);
        TextView openT = ui.filledButton("Open terminal");
        openT.setOnClickListener(v -> { dismiss(); Actions.openTerminal(a, s.host, "s=" + s.id); });
        act.addView(openT, ui.margins(0, 0, 8, 0));
        TextView ren = ui.tonalButton("Rename");
        ren.setOnClickListener(v -> Actions.rename(a, s.host, s.id, s.name, null));
        act.addView(ren, ui.margins(0, 0, 8, 0));
        act.addView(new View(a), Ui.weight1());
        TextView kill = ui.textButton("Kill");
        kill.setTextColor(ui.err);
        kill.setOnClickListener(v -> Actions.kill(a, s.host, s.id, s.name, this::dismiss));
        act.addView(kill);
        col.addView(act);

        dialog = ui.sheet(a, col);
        dialog.setOnDismissListener(d -> close());
        dialog.show();
        if (s.state == Detector.State.QUESTION && s.question != null) renderAnswers(s.question);
        if (!exited) connect();
        else statusText.setText("Exited");
    }

    // ---------------- socket ----------------
    private void connect() {
        statusText.setText("Connecting to " + s.host.name + "…");
        ws = Api.ws.newWebSocket(new Request.Builder().url(s.host.wsUrl()).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                main.post(() -> {
                    open = true;
                    try { w.send(new JSONObject().put("type", "attach").put("id", s.id).toString()); } catch (Exception ignored) {}
                    main.postDelayed(ticker, 1000);
                });
            }
            @Override public void onMessage(WebSocket w, String text) { main.post(() -> handle(text)); }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                main.post(() -> { open = false; if (!closed) statusText.setText("Can't reach " + s.host.name); });
            }
            @Override public void onClosed(WebSocket w, int code, String reason) { main.post(() -> open = false); }
        });
    }

    private void handle(String text) {
        if (closed) return;
        JSONObject m;
        try { m = new JSONObject(text); } catch (Exception e) { return; }
        String type = m.optString("type");
        if (!s.id.equals(m.optString("id")) && !type.equals("sessions")) return;
        switch (type) {
            case "attached": seeded = false; break;
            case "out": {
                String d = m.optString("data");
                if (!seeded) { det.seed(d); seeded = true; }
                else det.feed(d, System.currentTimeMillis());
                lastOut = System.currentTimeMillis();
                queueRender();
                break;
            }
            case "exit":
                exited = true; det.exited();
                queueRender();
                break;
        }
    }

    private void queueRender() {
        if (renderQueued) return;
        renderQueued = true;
        main.postDelayed(() -> { renderQueued = false; render(); }, 120);
    }

    private String shownQuestion;

    private void render() {
        if (closed || screen == null) return;
        if (seeded) {
            List<String> ls = det.tail(LINES);
            screen.setText(ls.isEmpty() ? "" : TextUtils.join("\n", ls));
        }
        Detector.State st = exited ? Detector.State.EXITED : seeded ? det.state() : s.state;
        stateDot.setBackground(ui.oval(ui.stateColor(st)));
        statePill.setText(Ui.stateLabel(st));
        statePill.setTextColor(ui.stateColor(st));
        statePill.setBackground(ui.round(ui.stateContainer(st), 10));
        if (exited) statusText.setText("Session exited");
        else if (!open) { /* keep connection message */ }
        else if (lastOut == 0) statusText.setText("Live · waiting for output");
        else statusText.setText("Live · last output " + Ui.ago(lastOut));
        String q = seeded ? det.question() : s.question;
        if (q == null ? shownQuestion != null : !q.equals(shownQuestion)) renderAnswers(q);
    }

    private void renderAnswers(String q) {
        shownQuestion = q;
        answers.removeAllViews();
        if (q == null || exited) return;
        for (Detector.Answer an : Detector.answersFor(q)) {
            TextView b = ui.tonalButton(an.label);
            b.setOnClickListener(v -> { ui.haptic(v); send(an.data); });
            answers.addView(b, ui.margins(0, 0, 8, 0));
        }
    }

    // ---------------- input ----------------
    private void sendInput() {
        if (input == null) return;
        String t = input.getText().toString();
        send(t + "\r");
        input.setText("");
    }

    private void send(String data) {
        if (exited) return;
        Notify.cancel(a, s.host, "q:" + s.id);
        if (open && ws != null) {
            try { ws.send(new JSONObject().put("type", "in").put("id", s.id).put("data", data).toString()); }
            catch (Exception ignored) {}
        } else Api.input(a, s.host, s.id, data);
    }

    private void dismiss() { if (dialog != null) dialog.dismiss(); }

    private void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (ws != null) {
            try { ws.send(new JSONObject().put("type", "detach").put("id", s.id).toString()); } catch (Exception ignored) {}
            try { ws.close(1000, "bye"); } catch (Exception ignored) {}
        }
    }
}
