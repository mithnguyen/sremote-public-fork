/* remote-direct client — terminal UI over direct WS */
"use strict";

const $ = (s) => document.querySelector(s);

// ---------- state ----------
let ws = null, wsAlive = false, reconnectTimer = null;
let onScreenMsg = () => {}, onScreenBin = () => {}, screenResub = () => {}; // wired inside the init IIFE below
let sessions = [];          // all server sessions
let workspaces = [];        // persisted workspace folders
let activeId = null;
let view = "home";          // 'home' | 'work'
let currentWs = undefined;  // workspace id | null (standalone) | undefined (home)
const panes = new Map();    // id -> {term, fit, el, attached, lastOutAt, idleNotified, title, search}
let hostInfo = {};
const unreadSess = new Set(); // sessions with unseen output (drives .tab.unread)
let splitId = null;         // session id in the unfocused split pane (null = no split)
let splitFocus = "left";    // which split side holds the focused (activeId) session
let fontSize = parseInt(localStorage.getItem("rd.font") || "14", 10) || 14;
const TERM_THEMES = {
  dark:  { background: "#0d1117", foreground: "#e6edf3" },
  light: { background: "#fbfcfe", foreground: "#1c2330" },
};
let themeName = localStorage.getItem("rd.theme") === "light" ? "light" : "dark";

const navTreeEl = $("#nav-tree"), tabsEl = $("#tabs"), termsEl = $("#terms");
const emptyEl = $("#empty"), sidebar = $("#sidebar"), filesPanel = $("#filespanel");
const homeProjEl = $("#home-projects"), homeRecentEl = $("#home-recent");

// ---------- toasts ----------
function toast(msg, kind = "ok") {
  const t = document.createElement("div");
  t.className = "toast" + (kind === "err" ? " err" : "");
  t.textContent = msg;
  $("#toasts").appendChild(t);
  setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 260); }, 3200);
}

// clipboard works over plain http too (tailscale isn't a secure context)
function copyToClipboard(t) {
  if (navigator.clipboard?.writeText) { navigator.clipboard.writeText(t).catch(() => fallbackCopy(t)); return; }
  fallbackCopy(t);
}
function fallbackCopy(t) {
  const ta = document.createElement("textarea");
  ta.value = t; ta.style.cssText = "position:fixed;top:0;left:0;opacity:0";
  document.body.appendChild(ta); ta.focus(); ta.select();
  try { document.execCommand("copy"); } catch {}
  ta.remove();
}
function copySel(term) {
  const t = term.getSelection && term.getSelection();
  if (t) { copyToClipboard(t); toast(`Copied ${t.length} chars`); }
}

// touch devices get the keybar; drawer/scrim layout follows viewport width —
// a desktop with a touchscreen is coarse but NOT narrow. Some mobile browsers
// misreport pointer:coarse, so the keybar also shows on narrow screens.
const isCoarse = matchMedia("(pointer: coarse)").matches;
const mqNarrow = matchMedia("(max-width: 720px)");
function syncTouchUI() { document.body.classList.toggle("touch-ui", isCoarse || mqNarrow.matches); }
function applyTypebar() {
  // rd.typebar: unset/"1" = always show, "0" = touch/narrow only
  document.body.classList.toggle("typebar", localStorage.getItem("rd.typebar") !== "0");
}
syncTouchUI();
applyTypebar();
const mqHandler = () => { syncTouchUI(); applyToggles(); };
if (mqNarrow.addEventListener) mqNarrow.addEventListener("change", mqHandler);
else if (mqNarrow.addListener) mqNarrow.addListener(mqHandler); // iOS < 14

// ---------- helpers ----------
function esc(s) { return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
function shortPath(p) {
  if (!p) return "";
  const parts = p.replace(/\\/g, "/").split("/").filter(Boolean);
  return parts.length > 2 ? "…/" + parts.slice(-2).join("/") : p;
}
function relTime(t) {
  if (!t) return "";
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 10) return "just now";
  if (s < 60) return s + "s";
  const m = Math.floor(s / 60);
  if (m < 60) return m + "m";
  const h = Math.floor(m / 60);
  if (h < 24) return h + "h";
  return Math.floor(h / 24) + "d";
}
function fmtSize(n) {
  if (n < 1024) return n + " B";
  if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
  if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
  return (n / 1073741824).toFixed(1) + " GB";
}
const wsById = (id) => workspaces.find((w) => w.id === id);
const lsGet = (k, d) => { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch { return d; } };
const lsSet = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} };

// ---------- recency + activity ----------
// mru: session id -> last time YOU opened it; seen: id -> output ts already looked at.
// Server pushes lastOut for every session, so "new output" works across all projects.
const mru = lsGet("rd.mru", {}), seen = lsGet("rd.seen", {});
let seenSaveT = null;
const saveSeen = () => { clearTimeout(seenSaveT); seenSaveT = setTimeout(() => lsSet("rd.seen", seen), 1500); };
function touchMru(id) {
  mru[id] = Date.now();
  const ids = Object.keys(mru);
  if (ids.length > 80) for (const k of ids.sort((a, b) => mru[a] - mru[b]).slice(0, ids.length - 80)) delete mru[k];
  lsSet("rd.mru", mru);
}
function markSeen(id) {
  const s = sessions.find((x) => x.id === id);
  seen[id] = Math.max(Date.now(), (s && s.lastOut) || 0);
  saveSeen();
}
const BUSY_MS = 6000;
function sessState(s) { // off | bell | busy | new | idle
  if (s.exited) return "off";
  if (notifiedSess.has(s.id)) return "bell";
  if (s.lastOut && Date.now() - s.lastOut < BUSY_MS) return "busy";
  if (s.lastOut && s.lastOut > (seen[s.id] || s.createdAt || 0) + 1500 && s.id !== activeId) return "new";
  return "idle";
}
const ST_TIP = { off: "exited", bell: "needs attention", busy: "running — output just now", new: "new output since you looked", idle: "idle" };
const sessUsed = (s) => Math.max(mru[s.id] || 0, s.createdAt || 0);
const sessWs = (s) => (s.workspace && wsById(s.workspace) ? s.workspace : null); // removed project → standalone
const wsSessions = (wsId) => sessions.filter((s) => sessWs(s) === wsId);
const wsUsed = (w) => Math.max(w.lastUsed || 0, ...wsSessions(w.id).map((s) => mru[s.id] || 0));
const sessTitle = (s) => { const p = panes.get(s.id); return (p && p.title) || ""; };
const pinsLoad = () => new Set(lsGet("rd.pins", []));
const pinsSave = (set) => lsSet("rd.pins", [...set]);

// ---------- fuzzy match ----------
// every query token must hit the haystack: substring (word-start bonus) or, weaker,
// an in-order subsequence ("sremsrv" → "sremote-server"). Returns -1 or a score.
function fzToken(t, h) {
  const i = h.indexOf(t);
  if (i >= 0) return 60 + (i === 0 ? 40 : /[\s\/_\-.:]/.test(h[i - 1]) ? 25 : 0) - Math.min(i, 30) / 3;
  let j = 0, gaps = 0, last = -1;
  for (let k = 0; k < h.length && j < t.length; k++) if (h[k] === t[j]) { if (last >= 0) gaps += k - last - 1; last = k; j++; }
  return j === t.length && t.length > 1 ? Math.max(1, 30 - gaps) : -1;
}
function fz(q, ...fields) {
  const toks = q.toLowerCase().split(/\s+/).filter(Boolean);
  if (!toks.length) return 0;
  const hs = fields.map((f) => String(f || "").toLowerCase());
  let total = 0;
  for (const t of toks) {
    let best = -1;
    hs.forEach((h, n) => { const sc = fzToken(t, h); if (sc >= 0) best = Math.max(best, sc - n * 8); }); // earlier field = stronger
    if (best < 0) return -1;
    total += best;
  }
  return total;
}
function hl(text, q) { // escape + <mark> substring hits of each token
  const src = String(text || "");
  const toks = q.toLowerCase().split(/\s+/).filter((t) => t.length > 0);
  if (!toks.length) return esc(src);
  const low = src.toLowerCase(), on = new Array(src.length).fill(false);
  for (const t of toks) { const i = low.indexOf(t); if (i >= 0) for (let k = i; k < i + t.length; k++) on[k] = true; }
  let out = "", open = false;
  for (let k = 0; k < src.length; k++) {
    if (on[k] !== open) { out += on[k] ? "<mark>" : "</mark>"; open = on[k]; }
    out += esc(src[k]);
  }
  return out + (open ? "</mark>" : "");
}
const sessInView = () => sessions.filter((s) => sessWs(s) === (currentWs ?? null));

// ---------- websocket ----------
function connect() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {
    wsAlive = true;
    setConn(true);
    send({ type: "list" });
    for (const [id, p] of panes) attach(id, p);
    pingLoop();
  };
  ws.onclose = () => {
    wsAlive = false;
    setConn(false);
    for (const p of panes.values()) p.attached = false;
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, 1500);
  };
  ws.onerror = () => {};
  ws.onmessage = (ev) => {
    if (typeof ev.data !== "string") { onScreenBin(ev.data); return; }
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    switch (m.type) {
      case "sessions":
        sessions = m.list;
        if (splitId && !inView(splitId)) { splitId = null; splitFocus = "left"; }
        renderAll();
        if (view === "work" && !inView(activeId)) {
          const vis = sessInView().filter((s) => !s.exited);
          const pick = vis[0] || sessInView()[0];
          if (pick) select(pick.id); else { activeId = null; updateEmpty(); renderTabs(); }
        }
        screenResub(); // re-subscribe screen after reconnect
        sessLoaded = true;
        applyRoute();
        break;
      case "out": {
        const p = panes.get(m.id);
        if (p) {
          p.term.write(m.data);
          p.lastOutAt = Date.now(); p.idleNotified = false; // reset busy→quiet edge
          if (m.id === activeId && document.visibilityState === "visible") { seen[m.id] = Date.now(); saveSeen(); }
          if (m.id !== activeId) {
            unreadSess.add(m.id);
            const t = tabsEl.querySelector(`[data-id="${m.id}"]`);
            if (t) t.classList.add("unread");
          }
          // scrollback replayed on attach is old news — its BELs don't count
          if (hasAttentionSeq(m.data) && !(p.replayUntil > Date.now())) notifySession(m.id, "bell");
        }
        break;
      }
      case "attached": {
        const p = panes.get(m.id);
        if (p) { p.attached = true; p.replayUntil = Date.now() + 1500; sendSize(m.id, p); }
        break;
      }
      case "exit": {
        const p = panes.get(m.id);
        if (p) p.term.write(`\r\n\x1b[90m[process exited ${m.code}]\x1b[0m\r\n`);
        break;
      }
      case "created": {
        // tag the new session into the current workspace client-side happens server-side
        select(m.id);
        break;
      }
      case "activity": { // {id: lastOut} for all sessions, coalesced server-side
        for (const s of sessions) if (m.map[s.id]) s.lastOut = m.map[s.id];
        if (activeId && m.map[activeId] && document.visibilityState === "visible") { seen[activeId] = m.map[activeId]; saveSeen(); }
        refreshStates();
        break;
      }
      case "pong":
        for (const el of [$("#latency"), $("#latency2")]) if (el) el.textContent = `${Date.now() - m.t}ms`;
        break;
      case "screen": onScreenMsg(m); break;
    }
  };
}
const inView = (id) => { const s = sessions.find((x) => x.id === id); return s && sessWs(s) === (currentWs ?? null); };
function send(o) { if (wsAlive) ws.send(JSON.stringify(o)); }
function setConn(up) {
  document.body.classList.toggle("disconnected", !up);
  for (const [d, t] of [[$("#conn-dot"), $("#conn-text")], [$("#conn-dot2"), $("#conn-text2")]]) {
    if (d) { d.className = "dot " + (up ? "up" : "down"); }
    if (t) t.textContent = up ? "direct" : "reconnecting…";
  }
  if (!up) for (const el of [$("#latency"), $("#latency2")]) if (el) el.textContent = "";
}
let pingTimer = null;
function pingLoop() {
  clearInterval(pingTimer);
  pingTimer = setInterval(() => send({ type: "ping", t: Date.now() }), 3000);
}

// ---------- views ----------
function showView(v) {
  view = v;
  $("#view-home").classList.toggle("hidden", v !== "home");
  $("#view-work").classList.toggle("hidden", v !== "work");
  if (v === "work") renderAll();
  else { renderHome(); renderSessions(); renderMachines(); renderDevports(); } // refresh tailnet + dev-server cards on each home show
}
function enterWorkspace(wsId, pickId) {
  hidePjPop();
  currentWs = wsId;            // id | null (standalone)
  splitId = null; splitFocus = "left";
  const w = wsId ? wsById(wsId) : null;
  if (w) w.lastUsed = Date.now();
  $("#nav-q").value = ""; navQ = "";
  homeQ.value = ""; // a search that got you here is done; home reopens unfiltered
  if (view !== "work") { try { history.pushState({ sr: "work" }, ""); } catch {} } // Back / edge swipe → home
  $("#new-btn-ws").textContent = w ? `in ${w.name}` : "";
  setTitle();
  showView("work");
  if (mqNarrow.matches) { sideVisible = false; fpVisible = false; applyToggles(); } // drawers start closed on narrow screens
  fpRoot = w ? `ws:${w.id}` : null;
  fpPath = "";
  // pick a session in this workspace: requested one, else the one you used last
  const inWs = sessInView();
  const pick = inWs.find((s) => s.id === pickId)
    || [...inWs].sort((a, b) => (a.exited - b.exited) || sessUsed(b) - sessUsed(a))[0];
  activeId = null;
  if (pick) select(pick.id); else updateEmpty();
  renderAll();
  if (fpVisible && fpRoot) fpNavigate("");
}
// open any session from anywhere (home, navigator, palette) — switches project if needed
function openSession(id) {
  const s = sessions.find((x) => x.id === id);
  if (!s) return;
  if (view === "work" && (currentWs ?? null) === sessWs(s)) select(id);
  else enterWorkspace(sessWs(s), id);
}
// new terminal straight in a project, default shell, no dialog
function quickNew(wsId) {
  if (view !== "work" || (currentWs ?? null) !== wsId) enterWorkspace(wsId);
  const shell = localStorage.getItem("rd.shell") || (hostInfo.shells || ["powershell"])[0];
  send({ type: "create", shell, cols: 120, rows: 32, ...(wsId ? { workspace: wsId } : { cwd: hostInfo.home }) });
}
function goHome() {
  if (history.state && history.state.sr === "work") { skipPop = true; history.back(); } // drop our entry
  view = "home";
  activeId = null;             // sessions keep running; panes stay attached
  splitId = null; splitFocus = "left";
  termsEl.classList.remove("split");
  for (const p of panes.values()) p.el.classList.add("hidden");
  setTitle();
  showView("home");
  if (!isCoarse) setTimeout(() => homeQ.focus(), 0);
}
$("#home-btn").onclick = goHome;

// Back button / edge swipe / Android back: close an overlay first, else work → home.
// Never lets the browser leave the page from the work view.
let skipPop = false;
window.addEventListener("popstate", () => {
  if (skipPop) { skipPop = false; return; }
  const open = [...document.querySelectorAll(".modal:not(.hidden), #palette:not(.hidden)")];
  if (open.length || !popMenu.classList.contains("hidden") || !pjPop.classList.contains("hidden")) {
    if (!$("#screen-modal").classList.contains("hidden")) $("#scr-close").click();
    for (const m of open) m.classList.add("hidden");
    hideMenu(); hidePjPop();
    if (view === "work") { try { history.pushState({ sr: "work" }, ""); } catch {} } // stay in the terminal
    return;
  }
  if (view === "work") goHome();
});

// ---------- deep links (Android app): #s=<sid> | #ws=<id> | #screen ----------
// applied once sessions/workspaces are known; the hash is cleared afterwards so
// the app can send the same link again and still get a hashchange
let wsLoaded = false, sessLoaded = false;
function applyRoute() {
  const raw = location.hash.replace(/^#/, "");
  if (!raw || !wsLoaded || !sessLoaded) return;  // retried when the missing list arrives
  const q = new URLSearchParams(raw);
  const sid = q.get("s"), wsId = q.get("ws");
  if (q.has("fx")) openExplorer(q.get("fx") || undefined);
  if (sid) {
    const s = sessions.find((x) => x.id === sid);
    if (s) {
      openSession(sid);
    }
  } else if (wsId && wsById(wsId)) {
    enterWorkspace(wsId);
  }
  if (q.has("screen")) $("#screen-btn").click();
  history.replaceState(history.state, "", location.pathname + location.search);
}
window.addEventListener("hashchange", applyRoute);

// ---------- sessions / panes ----------
function ensurePane(id) {
  let p = panes.get(id);
  if (p) return p;
  const el = document.createElement("div");
  el.className = "term-pane hidden";
  termsEl.appendChild(el);
  const term = new Terminal({
    fontFamily: "'Cascadia Code', 'Cascadia Mono', Consolas, monospace",
    fontSize, cursorBlink: true, scrollback: 5000,
    theme: { ...TERM_THEMES[themeName] },
    allowProposedApi: true,
  });
  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  p = { term, fit, el, attached: false, title: "", lastOutAt: 0, idleNotified: false, search: null };
  if (window.SearchAddon) { try { p.search = new SearchAddon.SearchAddon(); term.loadAddon(p.search); } catch {} }
  if (window.WebLinksAddon) { try { term.loadAddon(new WebLinksAddon.WebLinksAddon()); } catch {} }
  if (window.Unicode11Addon) { try { term.loadAddon(new Unicode11Addon.Unicode11Addon()); term.unicode.activeVersion = "11"; } catch {} }
  term.open(el);
  // mobile swipe-scroll: xterm v6 uses a virtual SmoothScrollableElement —
  // scrollTop hacks no longer work; feed finger deltas to term.scrollLines.
  // Long-press (~450ms, no move) switches to select mode: synthesized mouse
  // events drive xterm's own drag-select; on release the selection is copied.
  let tY = null, tAcc = 0, selMode = false, suppressCtx = false;
  let lpTimer = null, lpX = 0, lpY = 0;
  const linePx = () => {
    const d = term._core && term._core._renderService && term._core._renderService.dimensions;
    return (d && d.css && d.css.cell && d.css.cell.height) || 17;
  };
  const scrEl = () => el.querySelector(".xterm-screen") || el;
  const fireMouse = (type, x, y, tgt) =>
    (tgt || document).dispatchEvent(new MouseEvent(type, { clientX: x, clientY: y, button: 0, bubbles: true, cancelable: true }));
  const clearLp = () => { if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; } };
  el.addEventListener("touchstart", (e) => {
    tY = e.touches.length === 1 ? e.touches[0].clientY : null; tAcc = 0;
    if (e.touches.length === 1) {
      lpX = e.touches[0].clientX; lpY = e.touches[0].clientY;
      lpTimer = setTimeout(() => {
        lpTimer = null; selMode = true; tY = null;
        try { navigator.vibrate && navigator.vibrate(15); } catch {}
        fireMouse("mousedown", lpX, lpY, scrEl());
      }, 450);
    }
  }, { passive: true });
  el.addEventListener("touchmove", (e) => {
    if (e.touches.length !== 1) return;
    const t = e.touches[0];
    if (selMode) { fireMouse("mousemove", t.clientX, t.clientY); return; }
    const ddx = t.clientX - lpX, ddy = t.clientY - lpY;
    if (ddx * ddx + ddy * ddy > 120) clearLp();
    if (tY == null) return;
    const y = t.clientY, dy = tY - y;
    tY = y;
    if (!dy) return;
    tAcc += dy / linePx();
    const n = Math.trunc(tAcc);
    if (n) { tAcc -= n; term.scrollLines(n); }
  }, { passive: true });
  const endTouch = () => {
    tY = null; clearLp();
    if (selMode) {
      selMode = false; suppressCtx = true;
      fireMouse("mouseup", lpX, lpY);
      copySel(term);
    }
  };
  el.addEventListener("touchend", endTouch, { passive: true });
  el.addEventListener("touchcancel", endTouch, { passive: true });
  el.addEventListener("contextmenu", (e) => { if (suppressCtx) { suppressCtx = false; e.preventDefault(); } });
  el.addEventListener("mouseup", () => setTimeout(() => copySel(term), 0)); // desktop drag-select → auto-copy
  // jump-to-latest: floating button, visible only while scrolled away from the bottom
  const jumpBtn = document.createElement("button");
  jumpBtn.className = "jump-latest hidden";
  jumpBtn.textContent = "↓";
  jumpBtn.title = "Jump to latest";
  jumpBtn.onclick = () => { term.scrollToBottom(); term.focus(); };
  el.appendChild(jumpBtn);
  term.onScroll(() => {
    const b = term.buffer.active;
    jumpBtn.className = (b.viewportY < b.baseY) ? "jump-latest" : "jump-latest hidden";
  });
  term.onData((d) => send({ type: "in", id, data: d }));
  term.onResize(({ cols, rows }) => send({ type: "resize", id, cols, rows }));
  term.onTitleChange((t) => { p.title = t; renderTabs(); });
  term.attachCustomKeyEventHandler((e) => {
    if (e.type === "keydown" && e.shiftKey && e.key === "Enter") { send({ type: "in", id, data: "\x1b\r" }); return false; } // Shift+Enter → Alt+Enter (Devin newline)
    if (e.type === "keydown" && e.ctrlKey && e.shiftKey && e.key === "V") { navigator.clipboard.readText().then((t) => send({ type: "in", id, data: t })); return false; }
    if (e.type === "keydown" && e.ctrlKey && e.shiftKey && e.key === "C") { copySel(term); return false; }
    if (e.type === "keydown" && e.ctrlKey && !e.shiftKey && e.key === "f") { openTermSearch(); return false; } // Ctrl+F search
    if (e.type === "keydown" && e.ctrlKey && !e.shiftKey && !e.altKey && (e.key === "k" || e.key === "K")) { openPalette(); return false; } // Ctrl+K palette
    return true;
  });
  panes.set(id, p);
  el.addEventListener("touchstart", () => term.focus(), { passive: true }); // open keyboard on tap
  el.addEventListener("mousedown", () => focusSide(id)); // split: pane click claims focus
  return p;
}
function attach(id, p) {
  send({ type: "attach", id, cols: p.term.cols, rows: p.term.rows });
}
function sendSize(id, p) {
  try { p.fit.fit(); } catch {}
  // same size → don't send; a no-op PTY resize still repaints the whole viewport
  if (p.lastCols === p.term.cols && p.lastRows === p.term.rows) return;
  p.lastCols = p.term.cols; p.lastRows = p.term.rows;
  send({ type: "resize", id, cols: p.term.cols, rows: p.term.rows });
}
// split: activeId = focused session, splitId = the OTHER visible one.
// Focused side renders activeId; the other side renders splitId.
const leftPaneId  = () => (splitFocus === "left"  ? activeId : splitId);
const rightPaneId = () => (splitFocus === "right" ? activeId : splitId);
function layoutPanes() {
  const on = !!splitId && view === "work" && !!activeId;
  termsEl.classList.toggle("split", on);
  const sb = $("#split-btn");
  if (sb) sb.classList.toggle("on", on);
  const L = on ? leftPaneId() : null, R = on ? rightPaneId() : null;
  for (const [pid, pp] of panes) {
    pp.el.classList.remove("visible-side", "focused");
    pp.el.style.order = "";
    let vis = false;
    if (on) {
      vis = pid === L || pid === R;
      if (vis) {
        pp.el.classList.add("visible-side");
        pp.el.style.order = pid === L ? 0 : 1; // DOM order ≠ side order
      }
      if (pid === activeId) pp.el.classList.add("focused");
    } else vis = pid === activeId;
    pp.el.classList.toggle("hidden", !vis);
  }
}
function focusSide(id) { // mousedown inside a split pane moves focus (and activeId) to it
  if (!splitId || id === activeId) return;
  splitFocus = id === rightPaneId() ? "right" : "left";
  const t = activeId; activeId = splitId; splitId = t; // swap: focused ↔ unfocused
  renderTabs(); renderSessions(); layoutPanes();
}
function fitVisible() { // fit + resize-send every visible pane
  const ids = splitId ? [leftPaneId(), rightPaneId()] : [activeId];
  for (const vid of ids) {
    const p = vid && panes.get(vid);
    if (p && !p.el.classList.contains("hidden")) sendSize(vid, p);
  }
}
function toggleSplit() {
  if (splitId) {
    splitId = null; splitFocus = "left";
    layoutPanes();
    requestAnimationFrame(fitVisible);
    const p = panes.get(activeId); if (p) p.term.focus();
    return;
  }
  const next = sessInView().find((s) => s.id !== activeId);
  if (!activeId || !next) return toast("Need 2 sessions to split", "err");
  splitId = next.id; splitFocus = "left";
  const sp = ensurePane(splitId);
  if (wsAlive && !sp.attached) attach(splitId, sp);
  layoutPanes();
  requestAnimationFrame(fitVisible);
  const p = panes.get(activeId); if (p) p.term.focus();
}
function select(id) {
  const s = sessions.find((x) => x.id === id);
  if (!s) return;
  if (splitId && id === splitId) { // picked the session already on the other side → just move focus
    splitFocus = splitFocus === "left" ? "right" : "left";
    const t = activeId; activeId = splitId; splitId = t;
    clearNotify(id); unreadSess.delete(id); touchMru(id); markSeen(id);
    renderTabs(); renderSessions(); layoutPanes();
    const fp = panes.get(id); if (fp) fp.term.focus();
    return;
  }
  activeId = id;
  clearNotify(id);
  unreadSess.delete(id);
  touchMru(id); markSeen(id);
  if (mqNarrow.matches && sideVisible) { sideVisible = false; applyToggles(); } // auto-close drawer on narrow screens
  const p = ensurePane(id);
  layoutPanes();
  p.term.clear();
  if (wsAlive) attach(id, p);
  renderTabs(); renderSessions(); updateEmpty();
  requestAnimationFrame(() => { sendSize(id, p); p.term.focus(); });
  // standalone view: files follow session cwd
  if (!currentWs && s.cwd) { fpRoot = s.cwd; if (fpVisible) fpNavigate(""); }
}
function closeSession(id) {
  send({ type: "kill", id });
  const p = panes.get(id);
  if (p) { p.term.dispose(); p.el.remove(); panes.delete(id); }
  unreadSess.delete(id);
  if (splitId === id) { splitId = null; splitFocus = "left"; } // killed the split side → single view
  if (activeId === id) {
    activeId = null;
    const rest = sessInView().filter((s) => s.id !== id);
    if (rest.length) select(rest[0].id); else { updateEmpty(); layoutPanes(); }
  } else layoutPanes();
}
function renameSession(id) {
  const s = sessions.find((x) => x.id === id);
  const t = tabsEl.querySelector(`[data-id="${id}"]`);
  if (!s || !t) return;
  const inp = document.createElement("input");
  inp.className = "tab-rename"; inp.value = s.name;
  t.replaceWith(inp);
  inp.focus(); inp.select();
  const done = (commit) => {
    if (commit && inp.value.trim() && inp.value.trim() !== s.name)
      send({ type: "rename", id, name: inp.value.trim() });
    renderTabs();
  };
  inp.onkeydown = (e) => { if (e.key === "Enter") done(true); if (e.key === "Escape") done(false); };
  inp.onblur = () => done(true);
}

// ---------- rendering ----------
// git status cache (30s TTL) — shared by ws cards and the files-panel diff btn
const gitCache = new Map();
function gitInfo(base) {
  const c = gitCache.get(base);
  if (c && Date.now() - c.t < 30000) return Promise.resolve(c.v);
  return fetch(`/api/git?base=${encodeURIComponent(base)}`)
    .then((r) => r.json()).then((v) => { gitCache.set(base, { t: Date.now(), v }); return v; })
    .catch(() => null);
}
function renderAll() {
  renderSessions(); renderTabs(); renderHome(); updateEmpty(); layoutPanes();
}
const dotHtml = (s) => { const st = sessState(s); return `<i class="st st-${st}" data-st="${s.id}" title="${ST_TIP[st]}"></i>`; };
const timeHtml = (s) => `<span class="tm" data-tm="${s.id}">${s.lastOut ? relTime(s.lastOut) : ""}</span>`;
// cheap in-place refresh of every status dot / "3m" label (activity ticks, timers)
function refreshStates() {
  for (const el of document.querySelectorAll("[data-st]")) {
    const s = sessions.find((x) => x.id === el.dataset.st);
    if (!s) continue;
    const st = sessState(s), c = "st st-" + st;
    if (el.className !== c) { el.className = c; el.title = ST_TIP[st]; }
  }
  for (const el of document.querySelectorAll("[data-tm]")) {
    const s = sessions.find((x) => x.id === el.dataset.tm);
    if (s) el.textContent = s.lastOut ? relTime(s.lastOut) : "";
  }
  for (const el of document.querySelectorAll("[data-wsdot]")) { // project-level "something new inside"
    const id = el.dataset.wsdot === "@solo" ? null : el.dataset.wsdot;
    const st = wsSessions(id).map(sessState);
    el.className = "wsdot" + (st.includes("bell") ? " bell" : st.includes("new") ? " new" : st.includes("busy") ? " busy" : "");
  }
}

// ---------- navigator (sidebar tree: every project, its sessions) ----------
const navOpen = new Set(lsGet("rd.navopen", []));
let navQ = "";
function navToggle(key) {
  if (navOpen.has(key)) navOpen.delete(key); else navOpen.add(key);
  lsSet("rd.navopen", [...navOpen]);
  renderSessions();
}
function projectList() { // [{key, w|null, name, path, list}] incl. a pseudo "Standalone" bucket
  const out = workspaces.map((w) => ({ key: w.id, w, name: w.name, path: w.path, list: wsSessions(w.id) }));
  const solo = wsSessions(null);
  if (solo.length) out.push({ key: "@solo", w: null, name: "Standalone", path: "terminals outside any project", list: solo });
  return out;
}
const projUsed = (p) => p.w ? wsUsed(p.w) : Math.max(0, ...p.list.map(sessUsed));
function renderSessions() { // name kept: called all over — renders the navigator tree
  if (!navTreeEl) return;
  navTreeEl.innerHTML = "";
  const q = navQ, pins = pinsLoad(), cur = currentWs ?? null;
  let projs = projectList().map((p) => {
    const ps = q ? fz(q, p.name, p.path) : 0;
    const hits = q ? p.list.map((s) => ({ s, sc: fz(q, s.name, sessTitle(s), s.cwd) })).filter((x) => x.sc >= 0) : [];
    return { ...p, ps, hits, score: Math.max(ps, ...hits.map((x) => x.sc + 5)) };
  });
  if (q) projs = projs.filter((p) => p.score >= 0).sort((a, b) => b.score - a.score);
  else projs.sort((a, b) =>
    ((b.key === (cur || "@solo")) - (a.key === (cur || "@solo")))
    || (pins.has(b.key) - pins.has(a.key))
    || ((b.list.length > 0) - (a.list.length > 0))
    || projUsed(b) - projUsed(a));
  let restLabel = false;
  for (const p of projs) {
    const isCur = p.key === (cur || "@solo") && view === "work";
    if (!q && !restLabel && !p.list.length && !pins.has(p.key) && !isCur) { // quiet projects at the bottom
      restLabel = true;
      const l = document.createElement("div");
      l.className = "nav-label"; l.textContent = "NO SESSIONS";
      navTreeEl.appendChild(l);
    }
    const open = q ? true : isCur || navOpen.has(p.key);
    const g = document.createElement("div");
    g.className = "nav-grp" + (isCur ? " cur" : "") + (open ? " open" : "");
    const live = p.list.filter((s) => !s.exited).length;
    const h = document.createElement("div");
    h.className = "nav-proj";
    h.title = p.path;
    h.innerHTML = `<span class="nav-caret">›</span>${folderSvg(15)}
      <span class="nav-pname">${hl(p.name, q)}</span>
      <i class="wsdot" data-wsdot="${p.key}"></i>
      ${pins.has(p.key) ? `<span class="nav-pin" title="Pinned">★</span>` : ""}
      <span class="nav-n">${live || ""}</span>
      <button class="nav-add" title="New terminal in ${esc(p.name)}">+</button>`;
    h.onclick = (e) => {
      if (e.target.closest(".nav-add")) { quickNew(p.w ? p.w.id : null); return; }
      if (q) { if (p.list[0]) openSession((p.hits[0] || { s: p.list[0] }).s.id); else if (p.w) enterWorkspace(p.w.id); return; }
      if (!p.list.length) { if (p.w) enterWorkspace(p.w.id); return; }
      navToggle(p.key);
    };
    h.oncontextmenu = (e) => { e.preventDefault(); projMenu(e, p); };
    g.appendChild(h);
    if (open) {
      const kids = document.createElement("div");
      kids.className = "nav-kids";
      const list = q && p.ps < 0 ? p.hits.map((x) => x.s) : [...p.list].sort((a, b) => (a.exited - b.exited) || sessUsed(b) - sessUsed(a));
      for (const s of list) {
        const d = document.createElement("div");
        d.className = "nav-sess" + (s.id === activeId && view === "work" ? " active" : "") + (s.exited ? " exited" : "");
        d.dataset.sid = s.id;
        const ttl = sessTitle(s);
        d.innerHTML = `${dotHtml(s)}<span class="nav-stxt"><span class="nav-sname">${hl(s.name, q)}</span>${ttl ? `<span class="nav-ssub">${hl(ttl.slice(0, 60), q)}</span>` : ""}</span>${timeHtml(s)}`;
        d.onclick = () => openSession(s.id);
        d.oncontextmenu = (e) => { e.preventDefault(); sessMenu(e, s); };
        kids.appendChild(d);
      }
      if (!list.length) kids.innerHTML = `<div class="nav-empty">No terminals yet</div>`;
      g.appendChild(kids);
    }
    navTreeEl.appendChild(g);
  }
  if (!projs.length) navTreeEl.innerHTML = `<div class="nav-empty pad">No match for “${esc(q)}”</div>`;
  refreshStates();
}
const navQIn = $("#nav-q");
navQIn.addEventListener("input", () => { navQ = navQIn.value.trim(); renderSessions(); });
navQIn.addEventListener("keydown", (e) => {
  e.stopPropagation();
  if (e.key === "Escape") { navQIn.value = ""; navQ = ""; renderSessions(); const p = panes.get(activeId); if (p) p.term.focus(); }
  else if (e.key === "Enter") { const first = navTreeEl.querySelector(".nav-sess"); if (first) first.click(); }
});

function renderTabs() {
  tabsEl.innerHTML = "";
  for (const s of sessInView()) {
    if (!panes.has(s.id)) continue;
    const p = panes.get(s.id);
    const ttl = (p && p.title ? p.title : "").slice(0, 24);
    const t = document.createElement("div");
    t.className = "tab" + (s.id === activeId ? " active" : "") + (s.exited ? " exited" : "") + (notifiedSess.has(s.id) ? " bell" : "") + (unreadSess.has(s.id) ? " unread" : "");
    t.dataset.id = s.id;
    t.title = p && p.title ? p.title : s.name;
    t.innerHTML = `<span>${esc(s.name)}</span>${ttl ? `<span class="tab-ttl">${esc(ttl)}</span>` : ""}<span class="tab-x" title="kill">×</span>`;
    t.onclick = (e) => { if (e.target.classList.contains("tab-x")) closeSession(s.id); else select(s.id); };
    t.ondblclick = () => renameSession(s.id);
    tabsEl.appendChild(t);
  }
}

// ---------- home: search + continue + compact project list ----------
const homeQ = $("#home-q");
let homeFilter = localStorage.getItem("rd.hfilter") || "all";
let homeSort = localStorage.getItem("rd.hsort") || "recent";
let homeTargets = [], homeSel = -1; // keyboard-navigable rows/chips, in visual order
function syncSegs() {
  for (const b of document.querySelectorAll("#home-filter button")) b.classList.toggle("on", b.dataset.f === homeFilter);
  for (const b of document.querySelectorAll("#home-sort button")) b.classList.toggle("on", b.dataset.s === homeSort);
}
for (const b of document.querySelectorAll("#home-filter button"))
  b.onclick = () => { homeFilter = b.dataset.f; localStorage.setItem("rd.hfilter", homeFilter); renderHome(); };
for (const b of document.querySelectorAll("#home-sort button"))
  b.onclick = () => { homeSort = b.dataset.s; localStorage.setItem("rd.hsort", homeSort); renderHome(); };
function chipHtml(s, q) {
  const ttl = sessTitle(s);
  return `<button class="sc${s.exited ? " exited" : ""}" data-sid="${s.id}" title="${esc(ttl || s.cwd || s.name)}">${dotHtml(s)}<span class="sc-n">${hl(s.name, q)}</span>${timeHtml(s)}</button>`;
}
function renderHome() {
  syncSegs();
  const q = homeQ.value.trim(), pins = pinsLoad();
  // CONTINUE: the sessions you opened most recently (hidden while searching)
  const recent = sessions.filter((s) => mru[s.id] && !s.exited).sort((a, b) => mru[b.id] - mru[a.id]).slice(0, 6);
  $("#home-recent-sec").classList.toggle("hidden", !!q || !recent.length || homeFilter === "pinned");
  homeRecentEl.innerHTML = recent.map((s) => {
    const w = s.workspace ? wsById(s.workspace) : null;
    const ttl = sessTitle(s);
    return `<button class="rc" data-sid="${s.id}">${dotHtml(s)}<span class="rc-txt"><b>${esc(s.name)}</b><small>${esc(w ? w.name : "Standalone")}${ttl ? " · " + esc(ttl.slice(0, 40)) : ""}</small></span><span class="rc-t">${relTime(mru[s.id])}</span></button>`;
  }).join("");
  // PROJECTS
  let projs = projectList().map((p) => {
    const ps = q ? fz(q, p.name, p.path) : 0;
    const hits = q ? p.list.filter((s) => fz(q, s.name, sessTitle(s), s.cwd) >= 0) : p.list;
    const score = q ? Math.max(ps, ...p.list.map((s) => { const sc = fz(q, s.name, sessTitle(s), s.cwd); return sc < 0 ? -1 : sc + 5; })) : 0;
    return { ...p, ps, hits, score, live: p.list.filter((s) => !s.exited).length, pinned: pins.has(p.key) };
  });
  if (homeFilter === "active") projs = projs.filter((p) => p.live);
  if (homeFilter === "pinned") projs = projs.filter((p) => p.pinned);
  if (q) projs = projs.filter((p) => p.score >= 0);
  const byName = (a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" });
  projs.sort(q ? (a, b) => b.score - a.score
    : homeSort === "name" ? (a, b) => (b.pinned - a.pinned) || byName(a, b)
    : (a, b) => (b.pinned - a.pinned) || projUsed(b) - projUsed(a));
  $("#home-count").textContent = q ? `${projs.length} match${projs.length === 1 ? "" : "es"}`
    : `${workspaces.length} projects · ${sessions.filter((s) => !s.exited).length} live`;
  homeProjEl.innerHTML = "";
  let lastGroup = null;
  for (const p of projs) {
    const group = q ? "RESULTS" : p.pinned ? "PINNED" : "PROJECTS";
    if (group !== lastGroup) {
      lastGroup = group;
      const l = document.createElement("div");
      l.className = "home-label"; l.textContent = group;
      homeProjEl.appendChild(l);
      homeProjEl.appendChild(Object.assign(document.createElement("div"), { className: "pj-grid" }));
    }
    const grid = homeProjEl.lastElementChild;
    const d = document.createElement("div");
    d.className = "pj" + (p.live ? "" : " quiet");
    const list = q && p.ps < 0 ? p.hits : [...p.list].sort((a, b) => (a.exited - b.exited) || sessUsed(b) - sessUsed(a));
    d.innerHTML = `<div class="pj-head">
        <span class="pj-ico">${folderSvg(22)}</span>
        <span class="pj-txt"><span class="pj-name">${hl(p.name, q)}<i class="wsdot" data-wsdot="${p.key}"></i></span>${q && p.ps >= 0 && fz(q, p.name) < 0 ? `<span class="pj-path">${hl(p.path, q)}</span>` : ""}</span>
        <span class="pj-git"></span>
        ${p.w ? `<button class="pj-act pj-pin${p.pinned ? " on" : ""}" title="${p.pinned ? "Unpin" : "Pin to top"}">★</button>
        <button class="pj-act pj-more" title="More">⋯</button>` : ""}
        <button class="pj-info" title="Details" aria-label="Project details">i</button>
      </div>
      <div class="pj-sess">${list.map((s) => chipHtml(s, q)).join("")}<button class="sc sc-new" title="New terminal here">+<span class="lbl"> New</span></button></div>`;
    d.onclick = (e) => {
      const chip = e.target.closest("[data-sid]");
      if (chip) return openSession(chip.dataset.sid);
      if (e.target.closest(".sc-new")) return quickNew(p.w ? p.w.id : null);
      if (e.target.closest(".pj-pin")) {
        const s = pinsLoad(); if (s.has(p.key)) s.delete(p.key); else s.add(p.key);
        pinsSave(s); renderHome(); return;
      }
      if (e.target.closest(".pj-more")) return projMenu(e, p);
      if (e.target.closest(".pj-info")) return togglePjPop(e.target.closest(".pj-info"), p, true);
      if (e.target.closest(".pj-head")) { if (p.w) enterWorkspace(p.w.id); else if (p.list[0]) openSession(p.list[0].id); }
    };
    d.oncontextmenu = (e) => {
      const chip = e.target.closest("[data-sid]");
      e.preventDefault();
      if (chip) { const s = sessions.find((x) => x.id === chip.dataset.sid); if (s) sessMenu(e, s); }
      else projMenu(e, p);
    };
    const info = d.querySelector(".pj-info");
    info.onmouseenter = () => { if (!isCoarse) showPjPop(info, p, false); };
    info.onmouseleave = () => schedHidePjPop();
    grid.appendChild(d);
    if (p.w) gitInfo(`ws:${p.w.id}`).then((g) => { // lazily patch repo status
      const el = d.querySelector(".pj-git");
      if (!g || !g.isRepo || !el) return;
      el.textContent = `⎇ ${g.branch || "detached"}${g.changed ? ` · ${g.changed}±` : ""}`;
      el.title = g.changed ? `${g.changed} changed file(s)` : "clean";
      el.classList.toggle("dirty", !!g.changed);
    });
  }
  if (!projs.length) {
    homeProjEl.innerHTML = q
      ? `<div class="home-none">Nothing matches “${esc(q)}”. <button class="linkish" id="home-clear">Clear search</button></div>`
      : homeFilter !== "all"
        ? `<div class="home-none">No ${homeFilter} projects. <button class="linkish" id="home-clear">Show all</button></div>`
        : `<div class="home-none">No projects yet — add a folder to get started.</div>`;
    const c = $("#home-clear");
    if (c) c.onclick = () => { homeQ.value = ""; homeFilter = "all"; localStorage.setItem("rd.hfilter", "all"); renderHome(); };
  }
  for (const b of homeRecentEl.querySelectorAll("[data-sid]")) {
    b.onclick = () => openSession(b.dataset.sid);
    b.oncontextmenu = (e) => { e.preventDefault(); const s = sessions.find((x) => x.id === b.dataset.sid); if (s) sessMenu(e, s); };
  }
  // document order = visual order; selector-list querySelectorAll already returns it
  homeTargets = [...document.querySelectorAll("#home-recent [data-sid], #home-projects .pj-head, #home-projects [data-sid], #home-projects .sc-new")];
  homeSel = -1;
  if (q) { // preselect the best hit: its first matching chip if the project itself didn't match
    const first = projs[0];
    const chip = first && first.ps < 0 && homeProjEl.querySelector("[data-sid]");
    homeSel = chip ? homeTargets.indexOf(chip) : 0;
  }
  homeSync();
  refreshStates();
}
function homeSync() {
  homeTargets.forEach((el, i) => el.classList.toggle("kb-sel", i === homeSel));
  const el = homeTargets[homeSel];
  if (el && el.scrollIntoView && homeQ.value) el.scrollIntoView({ block: "nearest" });
}
homeQ.addEventListener("input", renderHome);
homeQ.addEventListener("keydown", (e) => {
  e.stopPropagation();
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    if (!homeTargets.length) return;
    homeSel = e.key === "ArrowDown" ? Math.min(homeSel + 1, homeTargets.length - 1) : Math.max(homeSel - 1, 0);
    homeSync();
  } else if (e.key === "Enter") {
    e.preventDefault();
    const el = homeTargets[Math.max(0, homeSel)];
    if (el) el.click();
  } else if (e.key === "Escape") { homeQ.value = ""; renderHome(); homeQ.blur(); }
  else if ((e.ctrlKey || e.metaKey) && (e.key === "k" || e.key === "K")) { e.preventDefault(); openPalette(); }
});
// typing anywhere on home goes to the search box; "/" focuses it
document.addEventListener("keydown", (e) => {
  if (view !== "home" || e.ctrlKey || e.metaKey || e.altKey) return;
  const t = e.target;
  if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable)) return;
  if (document.querySelector(".modal:not(.hidden)") || !$("#palette").classList.contains("hidden")) return;
  if (e.key === "/") { e.preventDefault(); homeQ.focus(); homeQ.select(); }
  else if (e.key.length === 1 && /\S/.test(e.key)) homeQ.focus(); // the char then lands in the input
});
$("#home-add").onclick = addProject;
$("#tool-screen").onclick = () => $("#screen-btn").click();
function addProject() {
  openPicker((p) => {
    fetch("/api/workspaces", { method: "POST", body: JSON.stringify({ path: p }) })
      .then((r) => r.json()).then((w) => {
        if (w.error) return toast(w.error, "err");
        toast(`Project “${w.name}” added`); loadWorkspaces();
      });
  });
}

// ---------- project details popover ("i" in the card corner): hover or tap ----------
const pjPop = $("#pj-pop");
let pjPopFor = null, pjPopSticky = false, pjPopT = null;
function showPjPop(anchor, p, sticky) {
  clearTimeout(pjPopT);
  pjPopFor = anchor; pjPopSticky = sticky;
  const live = p.list.filter((s) => !s.exited).length, ex = p.list.length - live;
  const pins = pinsLoad(), used = p.w ? wsUsed(p.w) : 0;
  pjPop.innerHTML = `<div class="pp-name">${esc(p.name)}</div>
    <div class="pp-k">Path</div><div class="pp-path">${esc(p.path)}</div>
    <div class="pp-row"><span class="pp-k">Sessions</span><span>${live} live${ex ? ` · ${ex} exited` : ""}</span></div>
    ${p.w ? `<div class="pp-row"><span class="pp-k">Last used</span><span>${!used ? "—" : relTime(used) === "just now" ? "just now" : relTime(used) + " ago"}</span></div>
    <div class="pp-row pp-git"><span class="pp-k">Git</span><span>…</span></div>
    <div class="pp-btns">
      <button class="mini-btn" data-a="copy">Copy path</button>
      <button class="mini-btn" data-a="files">Browse</button>
      <button class="mini-btn" data-a="pin">${pins.has(p.key) ? "Unpin" : "Pin"}</button>
      <button class="mini-btn" data-a="more">More…</button>
    </div>` : ""}`;
  pjPop.classList.remove("hidden");
  const r = anchor.getBoundingClientRect(), w = pjPop.offsetWidth, h = pjPop.offsetHeight;
  const x = Math.min(r.right - w, innerWidth - w - 8);
  let y = r.bottom + 6;
  if (y + h > innerHeight - 8) y = Math.max(8, r.top - h - 6);
  pjPop.style.left = Math.max(8, x) + "px"; pjPop.style.top = y + "px";
  if (p.w) gitInfo(`ws:${p.w.id}`).then((g) => {
    const el = pjPop.querySelector(".pp-git span:last-child");
    if (!el || pjPopFor !== anchor) return;
    el.textContent = !g || !g.isRepo ? "not a repo" : `${g.branch || "detached"} · ${g.changed ? g.changed + " changed" : "clean"}`;
  });
  for (const b of pjPop.querySelectorAll("[data-a]")) b.onclick = () => {
    const a = b.dataset.a;
    hidePjPop();
    if (a === "copy") { copyToClipboard(p.path); toast("Path copied"); }
    else if (a === "files") openExplorer(p.path);
    else if (a === "pin") togglePin(p.key);
    else if (a === "more") projMenu({ target: anchor, clientX: 0, clientY: 0 }, p);
  };
}
function togglePjPop(anchor, p, sticky) {
  if (pjPopFor === anchor && !pjPop.classList.contains("hidden") && pjPopSticky) return hidePjPop();
  showPjPop(anchor, p, sticky);
}
function hidePjPop() { clearTimeout(pjPopT); pjPop.classList.add("hidden"); pjPopFor = null; pjPopSticky = false; }
function schedHidePjPop() { if (!pjPopSticky) { clearTimeout(pjPopT); pjPopT = setTimeout(hidePjPop, 220); } }
pjPop.onmouseenter = () => clearTimeout(pjPopT);
pjPop.onmouseleave = () => schedHidePjPop();
const outsidePop = (e) => { if (!pjPop.contains(e.target) && !(e.target.closest && e.target.closest(".pj-info"))) hidePjPop(); };
document.addEventListener("mousedown", outsidePop);
document.addEventListener("touchstart", outsidePop, { passive: true });
$("#view-home").addEventListener("scroll", () => { if (!pjPopSticky) hidePjPop(); }, { passive: true });

// ---------- small popup menu + text prompt (no window.prompt: Android WebView drops it) ----------
const popMenu = $("#pop-menu");
function showMenu(e, items) {
  popMenu.innerHTML = "";
  for (const it of items) {
    if (it === "-") { popMenu.appendChild(Object.assign(document.createElement("div"), { className: "ctx-sep" })); continue; }
    const b = document.createElement("button");
    b.className = "ctx-item" + (it.danger ? " danger" : "");
    b.textContent = it.label;
    b.onclick = (ev) => { ev.stopPropagation(); hideMenu(); it.run(); };
    popMenu.appendChild(b);
  }
  popMenu.classList.remove("hidden");
  const r = e.target.closest && e.target.closest("button,.pj-head,.nav-proj,.nav-sess");
  const rb = r ? r.getBoundingClientRect() : null;
  let x = e.clientX || (rb ? rb.right - 8 : 20), y = e.clientY || (rb ? rb.bottom : 20);
  const mw = popMenu.offsetWidth, mh = popMenu.offsetHeight;
  x = Math.max(8, Math.min(x, innerWidth - mw - 8)); y = Math.max(8, Math.min(y, innerHeight - mh - 8));
  popMenu.style.left = x + "px"; popMenu.style.top = y + "px";
}
function hideMenu() { popMenu.classList.add("hidden"); }
document.addEventListener("mousedown", (e) => { if (!popMenu.contains(e.target)) hideMenu(); });
document.addEventListener("touchstart", (e) => { if (!popMenu.contains(e.target)) hideMenu(); }, { passive: true });
function askText(title, value, okLabel = "Save") {
  return new Promise((res) => {
    const m = $("#ask-modal"), inp = $("#ask-in");
    $("#ask-title").textContent = title; $("#ask-ok").textContent = okLabel;
    inp.value = value || "";
    m.classList.remove("hidden");
    setTimeout(() => { inp.focus(); inp.select(); }, 0);
    const done = (v) => { m.classList.add("hidden"); inp.onkeydown = null; res(v); };
    $("#ask-ok").onclick = () => done(inp.value.trim() || null);
    $("#ask-cancel").onclick = () => done(null);
    m.onclick = (e) => { if (e.target === m) done(null); };
    inp.onkeydown = (e) => { e.stopPropagation(); if (e.key === "Enter") done(inp.value.trim() || null); if (e.key === "Escape") done(null); };
  });
}
function togglePin(key) {
  const s = pinsLoad(); if (s.has(key)) s.delete(key); else s.add(key);
  pinsSave(s); renderAll();
}
function projMenu(e, p) {
  const pins = pinsLoad();
  const items = [{ label: "New terminal here", run: () => quickNew(p.w ? p.w.id : null) }];
  if (p.w) items.push(
    { label: "Open project", run: () => enterWorkspace(p.w.id) },
    { label: "Browse files", run: () => openExplorer(p.w.path) },
    { label: pins.has(p.key) ? "Unpin" : "Pin to top", run: () => togglePin(p.key) },
    { label: "Rename…", run: async () => {
      const v = await askText("Rename project", p.w.name);
      if (!v || v === p.w.name) return;
      const r = await fetch(`/api/workspaces/${p.w.id}`, { method: "PATCH", body: JSON.stringify({ name: v }) }).catch(() => null);
      if (r && r.ok) { toast(`Renamed to “${v}”`); loadWorkspaces(); } else toast("Rename failed", "err");
    } },
    { label: "Copy path", run: () => { copyToClipboard(p.w.path); toast("Path copied"); } });
  const exited = p.list.filter((s) => s.exited);
  if (exited.length) items.push("-", { label: `Close ${exited.length} exited session(s)`, run: () => exited.forEach((s) => send({ type: "kill", id: s.id })) });
  if (p.w) items.push("-", { label: "Remove from list", danger: true, run: () => {
    if (p.list.length && !confirm(`Remove “${p.w.name}”? Its ${p.list.length} session(s) keep running as standalone.`)) return;
    fetch(`/api/workspaces/${p.w.id}`, { method: "DELETE" })
      .then((r) => { if (r.ok) { toast(`Removed “${p.w.name}”`); loadWorkspaces(); } });
  } });
  showMenu(e, items);
}
function sessMenu(e, s) {
  showMenu(e, [
    { label: "Open", run: () => openSession(s.id) },
    { label: "Rename…", run: async () => {
      const v = await askText("Rename session", s.name);
      if (v && v !== s.name) send({ type: "rename", id: s.id, name: v });
    } },
    { label: "Copy working dir", run: () => { copyToClipboard(s.cwd || ""); toast("Path copied"); } },
    "-",
    { label: "Kill session", danger: true, run: () => {
      if (view === "work" && inView(s.id)) closeSession(s.id); else send({ type: "kill", id: s.id });
      toast(`Killed “${s.name}”`);
    } },
  ]);
}

// ---------- home: machines (tailnet peers) ----------
async function renderMachines() {
  const sec = $("#peers-sec"), box = $("#home-peers");
  const j = await fetch("/api/peers").then((r) => r.json()).catch(() => null);
  const peers = (j && j.peers) || [];
  if (!peers.length) { sec.classList.add("hidden"); return; }
  sec.classList.remove("hidden");
  $("#peers-n").textContent = `${peers.filter((p) => p.online).length}/${peers.length} online`;
  box.innerHTML = "";
  for (const p of peers) {
    const d = document.createElement("div");
    d.className = "peer-row" + (p.online ? "" : " off");
    const tag = !p.online ? "offline" : p.direct ? "direct" : p.relay ? `relay ${p.relay}` : "—";
    d.innerHTML = `<span class="peer-dot${p.online ? " on" : ""}"></span>
      <span class="peer-name">${esc(p.hostname || "?")}${p.self ? ` <span class="peer-self">(this machine)</span>` : ""}</span>
      <span class="peer-os">${esc(p.os || "")}</span>
      <span class="peer-tag">${esc(tag)}</span>
      ${p.sremote ? `<span class="peer-sr">S-remote ✓</span>` : ""}`;
    if (!p.self && p.sremote && p.ip) { // peer runs S-remote → click opens its UI
      d.classList.add("link");
      d.title = `http://${p.ip}:2209`;
      d.onclick = () => window.open(`http://${p.ip}:2209`, "_blank");
    }
    box.appendChild(d);
  }
}

// ---------- home: dev servers (listening ports) ----------
const DEV_SKIP_PROC = new Set(["System", "svchost.exe", "services.exe", "lsass.exe", "spoolsv.exe", "wininit.exe", "Idle", "Registry"]);
const DEV_SKIP_PORT = new Set([135, 139, 445, 3389]);
async function renderDevports() {
  const sec = $("#dev-sec"), box = $("#home-dev");
  const j = await fetch("/api/devports").then((r) => r.json()).catch(() => null);
  const ports = ((j && j.ports) || []).filter((p) =>
    !DEV_SKIP_PORT.has(p.port) && !(p.proc && DEV_SKIP_PROC.has(p.proc)));
  if (!ports.length) { sec.classList.add("hidden"); return; }
  sec.classList.remove("hidden");
  $("#dev-n").textContent = ports.length;
  box.innerHTML = "";
  const tip = hostInfo.tailscaleIp || location.hostname;
  for (const p of ports) {
    const d = document.createElement("div");
    d.className = "dev-row" + (p.loopback ? " loop" : "");
    d.innerHTML = `<span class="dev-proc">${esc(p.proc || "?")}</span>
      <span class="dev-port">:${p.port}</span>
      <span class="dev-addr">${esc(p.addr)}</span>
      ${p.loopback
        ? `<span class="dev-warn" title="localhost-only — restart with --host to reach via Tailscale">⚠ localhost-only</span>`
        : `<span class="dev-go">↗</span>`}`;
    if (!p.loopback) {
      d.classList.add("link");
      d.title = `http://${tip}:${p.port}`;
      d.onclick = () => window.open(`http://${tip}:${p.port}`, "_blank");
    }
    box.appendChild(d);
  }
}
function updateEmpty() {
  emptyEl.classList.toggle("hidden", !!activeId && panes.has(activeId));
}

// ---------- workspaces ----------
async function loadWorkspaces() {
  workspaces = await fetch("/api/workspaces").then((r) => r.json()).catch(() => []);
  wsLoaded = true;
  renderAll();
  setTitle();
  applyRoute();
}

// ---------- sidebar / files panel toggles ----------
let fpVisible = localStorage.getItem("rd.files") !== "0";
let sideVisible = localStorage.getItem("rd.sidebar") !== "0";
function applyToggles() {
  sidebar.classList.toggle("hidden", !sideVisible);
  $("#rail-open").classList.toggle("hidden", sideVisible);
  filesPanel.classList.toggle("hidden", !fpVisible);
  $("#files-toggle").classList.toggle("on", fpVisible);
  $("#scrim").classList.toggle("show", mqNarrow.matches && (sideVisible || fpVisible));
  applyWidths();
  if (fpVisible && fpRoot) fpNavigate(fpPath);
  requestAnimationFrame(fitVisible);
}
$("#side-collapse").onclick = () => { sideVisible = false; localStorage.setItem("rd.sidebar", "0"); applyToggles(); };
$("#rail-open").onclick = () => { sideVisible = true; localStorage.setItem("rd.sidebar", "1"); applyToggles(); };
$("#files-toggle").onclick = () => { fpVisible = !fpVisible; localStorage.setItem("rd.files", fpVisible ? "1" : "0"); applyToggles(); };
$("#fp-close").onclick = () => { fpVisible = false; localStorage.setItem("rd.files", "0"); applyToggles(); };

// ---------- drag resizers ----------
function applyWidths() {
  const sw = localStorage.getItem("rd.w.side"), fw = localStorage.getItem("rd.w.files");
  if (sw) sidebar.style.width = sw + "px";
  if (fw) filesPanel.style.width = fw + "px";
}
function makeResizable(handle, panel, min, max, key, fromRight) {
  handle.addEventListener("mousedown", (e) => {
    e.preventDefault();
    handle.classList.add("drag");
    document.body.classList.add("dragging");
    const startX = e.clientX, startW = panel.offsetWidth;
    const move = (ev) => {
      const dx = fromRight ? startX - ev.clientX : ev.clientX - startX;
      const w = Math.min(max, Math.max(min, startW + dx));
      panel.style.width = w + "px";
      localStorage.setItem(key, w);
    };
    const up = () => {
      handle.classList.remove("drag");
      document.body.classList.remove("dragging");
      document.removeEventListener("mousemove", move);
      document.removeEventListener("mouseup", up);
      fitVisible();
    };
    document.addEventListener("mousemove", move);
    document.addEventListener("mouseup", up);
  });
}
makeResizable($("#rs-side"), sidebar, 170, 400, "rd.w.side", false);
makeResizable($("#rs-files"), filesPanel, 190, 480, "rd.w.files", true);

// ---------- mobile keybar (Esc/Tab/Ctrl/arrows…) ----------
let ctrlLatch = false, altLatch = false;
// key catalog — config = array of ids in localStorage["rd.keybar.v1"]
const KB_KEYS = [
  { id: "esc", l: "Esc", d: "\x1b" }, { id: "tab", l: "Tab", d: "\t" },
  { id: "ctrl", l: "Ctrl", mod: "ctrl" }, { id: "alt", l: "Alt", mod: "alt" },
  { id: "left", l: "←", d: "\x1b[D" }, { id: "down", l: "↓", d: "\x1b[B" },
  { id: "up", l: "↑", d: "\x1b[A" }, { id: "right", l: "→", d: "\x1b[C" },
  { id: "pipe", l: "|", d: "|" }, { id: "tilde", l: "~", d: "~" },
  { id: "dash", l: "-", d: "-" }, { id: "slash", l: "/", d: "/" },
  { id: "space", l: "Space", d: " " },
  { id: "c-c", l: "C-c", d: "\x03" }, { id: "c-z", l: "C-z", d: "\x1a" },
  { id: "c-d", l: "C-d", d: "\x04" }, { id: "c-l", l: "C-l", d: "\x0c" },
  { id: "pgup", l: "PgUp", d: "\x1b[5~" }, { id: "pgdn", l: "PgDn", d: "\x1b[6~" },
  { id: "home", l: "Home", d: "\x1b[H" }, { id: "end", l: "End", d: "\x1b[F" },
  { id: "del", l: "Del", d: "\x1b[3~" }, { id: "ins", l: "Ins", d: "\x1b[2~" },
  { id: "c-a", l: "C-a", d: "\x01" }, { id: "c-e", l: "C-e", d: "\x05" },
  { id: "c-u", l: "C-u", d: "\x15" }, { id: "c-k", l: "C-k", d: "\x0b" },
  { id: "c-w", l: "C-w", d: "\x17" },
  { id: "f1", l: "F1", d: "\x1bOP" }, { id: "f2", l: "F2", d: "\x1bOQ" },
  { id: "f3", l: "F3", d: "\x1bOR" }, { id: "f4", l: "F4", d: "\x1bOS" },
];
const KB_DEFAULT = ["esc", "tab", "ctrl", "alt", "left", "down", "up", "right", "pipe", "tilde", "dash", "slash", "space", "c-c", "c-z", "c-d", "c-l"];
function kbConfig() {
  try {
    const a = JSON.parse(localStorage.getItem("rd.keybar.v1") || "null");
    if (Array.isArray(a)) return a;
  } catch {}
  return KB_DEFAULT;
}
function sendKey(def) {
  if (!activeId) return;
  let data = def.d;
  if (ctrlLatch) {
    if (/^[a-z]$/i.test(def.d)) data = String.fromCharCode(def.d.toUpperCase().charCodeAt(0) & 0x1f);
    ctrlLatch = false; syncLatch();
  }
  if (altLatch) { data = "\x1b" + data; altLatch = false; syncLatch(); }
  send({ type: "in", id: activeId, data });
}
function syncLatch() {
  document.querySelectorAll("#keybar .kb-mod").forEach((b) => {
    b.classList.toggle("latched", (b.dataset.mod === "ctrl" && ctrlLatch) || (b.dataset.mod === "alt" && altLatch));
  });
}
function rebuildKeybar() { // global — settings UI calls this after editing rd.keybar.v1
  const kb = $("#keybar");
  kb.innerHTML = "";
  const byId = {};
  for (const k of KB_KEYS) byId[k.id] = k;
  for (const k of kbConfig().map((id) => byId[id]).filter(Boolean)) {
    const b = document.createElement("button");
    b.className = "kb-key" + (k.mod ? " kb-mod" : "");
    b.textContent = k.l;
    if (k.mod) b.dataset.mod = k.mod;
    b.addEventListener("click", () => {
      reqNotifPerm();
      const ae = document.activeElement;
      if (ae && ae.blur) ae.blur(); // hide soft keyboard — keybar replaces it
      if (k.mod === "ctrl") { ctrlLatch = !ctrlLatch; syncLatch(); return; }
      if (k.mod === "alt") { altLatch = !altLatch; syncLatch(); return; }
      sendKey(k);
    });
    kb.appendChild(b);
  }
  const cp = document.createElement("button"); // copy-buffer key — always last, not configurable
  cp.className = "kb-key"; cp.textContent = "⧉"; cp.title = "Copy buffer";
  cp.addEventListener("click", () => { reqNotifPerm(); openCopy(); });
  kb.appendChild(cp);
  syncLatch();
}
rebuildKeybar();

// ---------- type row (buffered input → terminal) ----------
const typer = $("#typer"), typerProgEl = $("#typer-prog");
const typerHist = { list: [], i: -1, draft: "" }; // i=-1 → not navigating
try { typerHist.list = JSON.parse(localStorage.getItem("rd.typer.hist") || "[]"); } catch {}
function histPush(v) {
  if (!v) return;
  if (typerHist.list[typerHist.list.length - 1] !== v) typerHist.list.push(v); // dedupe consecutive
  if (typerHist.list.length > 100) typerHist.list.splice(0, typerHist.list.length - 100);
  try { localStorage.setItem("rd.typer.hist", JSON.stringify(typerHist.list)); } catch {}
  typerHist.i = -1; typerHist.draft = "";
}
let typerDelta = 0;       // pending #terms height change caused by typer growth
function typerGrow() { // auto-grow up to ~40% of viewport
  const prev = typer.offsetHeight;
  typer.style.height = "auto";
  typer.style.height = Math.min(typer.scrollHeight, Math.round(innerHeight * 0.4)) + "px";
  const d = typer.offsetHeight - prev;
  if (d) typerDelta += d;   // RO on #terms consumes this and skips the refit
}
function histRecall(dir) { // dir -1 = older (ArrowUp), +1 = newer/draft (ArrowDown)
  const n = typerHist.list.length;
  if (!n) return;
  if (typerHist.i === -1) {
    if (dir === 1) return;
    typerHist.draft = typer.value; typerHist.i = n - 1;
  } else typerHist.i = Math.max(0, typerHist.i + dir);
  if (typerHist.i >= n) { typerHist.i = -1; typer.value = typerHist.draft; }
  else typer.value = typerHist.list[typerHist.i];
  typerGrow();
  const c = dir === -1 ? 0 : typer.value.length; // park caret on the recall edge so nav continues on multi-line entries
  typer.selectionStart = typer.selectionEnd = c;
}
function typerSend(withEnter) {
  const v = typer.value;
  if (!activeId) return;
  const p = panes.get(activeId);
  if (v.includes("\n") && p) {
    p.term.paste(v); // bracketed-paste aware
    if (withEnter) send({ type: "in", id: activeId, data: "\r" });
  } else if (v) send({ type: "in", id: activeId, data: v + (withEnter ? "\r" : "") });
  else if (withEnter) send({ type: "in", id: activeId, data: "\r" });
  histPush(v);
  typer.value = "";
  typerGrow();
  typer.focus();
}
$("#typer-send").onclick = () => typerSend(true);
typer.addEventListener("input", typerGrow);
typer.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); typerSend(true); } // Shift+Enter = literal newline
  else if (e.key === "ArrowUp" && typer.value.slice(0, typer.selectionStart).indexOf("\n") === -1) { e.preventDefault(); histRecall(-1); }
  else if (e.key === "ArrowDown" && typer.value.slice(typer.selectionEnd).indexOf("\n") === -1) { e.preventDefault(); histRecall(1); }
  e.stopPropagation(); // don't hit global shortcuts while typing
});
$("#typerow").addEventListener("click", reqNotifPerm); // lazy Notification permission ask on user tap

// ---------- upload (attach / camera / paste / drop → temp-upload) ----------
function typerProg(frac) {
  if (!typerProgEl) return;
  typerProgEl.classList.toggle("on", frac > 0);
  typerProgEl.firstElementChild.style.width = Math.round(frac * 100) + "%";
}
function uploadFile(f, putPath, dir = "temp-upload") {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `/api/upload?base=${encodeURIComponent(fpRoot)}&name=${encodeURIComponent(f.name || "pasted-" + Date.now() + ".png")}&dir=${encodeURIComponent(dir)}`);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) typerProg(e.loaded / e.total); };
    xhr.onload = () => {
      typerProg(0);
      let j = null; try { j = JSON.parse(xhr.responseText); } catch {}
      if (xhr.status >= 200 && xhr.status < 300 && j) {
        if (putPath) { // absolute path into typer for the LAST file only
          const p = /\s/.test(j.path) ? `"${j.path}"` : j.path;
          typer.value = typer.value && !typer.value.endsWith(" ") ? `${typer.value} ${p}` : typer.value + p;
          typerGrow(); typer.focus();
        }
        toast(`Attached → ${j.rel}`);
        if (fpVisible && fpRoot) fpNavigate(fpPath);
      } else toast("Upload failed: " + (j?.error || xhr.status), "err");
      resolve();
    };
    xhr.onerror = () => { typerProg(0); toast("Upload failed", "err"); resolve(); };
    typerProg(0.01);
    xhr.send(f);
  });
}
let attachDir = "temp-upload"; // fp-upload retargets the picker to fpPath; consumed on change
async function uploadFiles(files, dir = "temp-upload") { // sequential
  if (!files.length) return;
  if (!fpRoot) return toast("Open a workspace or session first", "err");
  for (let i = 0; i < files.length; i++) await uploadFile(files[i], i === files.length - 1, dir);
}
// typerow "＋" menu → attach / camera / snippets / raw-insert
const typerMenu = $("#typer-menu");
$("#typer-more").onclick = (e) => { e.stopPropagation(); typerMenu.classList.toggle("hidden"); };
document.addEventListener("click", (e) => {
  if (!typerMenu.classList.contains("hidden") && !typerMenu.contains(e.target) && e.target.id !== "typer-more" && !e.target.closest("#typer-more"))
    typerMenu.classList.add("hidden");
});
typerMenu.addEventListener("click", (e) => {
  const b = e.target.closest("[data-a]");
  if (!b) return;
  typerMenu.classList.add("hidden");
  if (b.dataset.a === "attach") { attachDir = "temp-upload"; $("#attach-file").click(); }
  else if (b.dataset.a === "cam") $("#attach-cam").click();
  else if (b.dataset.a === "snip") { renderSnips(); snipModal.classList.remove("hidden"); }
  else if (b.dataset.a === "raw") typerSend(false);
});
$("#attach-file").addEventListener("change", (e) => {
  const fs = [...e.target.files]; e.target.value = "";
  const dir = attachDir; attachDir = "temp-upload";
  uploadFiles(fs, dir);
});
$("#attach-cam").addEventListener("change", (e) => { const fs = [...e.target.files]; e.target.value = ""; uploadFiles(fs); });
document.addEventListener("paste", (e) => { // screenshot paste → upload
  const fs = [...(e.clipboardData?.files || [])];
  if (fs.length) { e.preventDefault(); uploadFiles(fs); }
});

// ---------- snippets ----------
const snipModal = $("#snip-modal");
const snipKey = () => `rd.snip.${currentWs || "standalone"}`;
const snipLoad = () => { try { return JSON.parse(localStorage.getItem(snipKey()) || "[]"); } catch { return []; } };
const snipSave = (l) => { try { localStorage.setItem(snipKey(), JSON.stringify(l)); } catch {} };
function renderSnips() {
  const el = $("#snip-list");
  el.innerHTML = "";
  const list = snipLoad();
  if (!list.length) el.innerHTML = `<div class="snip-none">No snippets yet — type something, then “Save current”.</div>`;
  list.forEach((s, i) => {
    const d = document.createElement("div");
    d.className = "snip-item";
    d.innerHTML = `<span class="snip-txt">${esc(s)}</span><span class="snip-x" title="Delete">×</span>`;
    d.onclick = (e) => {
      if (e.target.classList.contains("snip-x")) {
        const l = snipLoad(); l.splice(i, 1); snipSave(l); renderSnips();
      } else {
        typer.value = typer.value && !typer.value.endsWith(" ") ? `${typer.value} ${s}` : typer.value + s;
        snipModal.classList.add("hidden");
        typerGrow(); typer.focus();
      }
    };
    el.appendChild(d);
  });
}

$("#snip-save").onclick = () => {
  const v = typer.value.trim();
  if (!v) return toast("Type something first", "err");
  const l = snipLoad(); l.push(v); snipSave(l);
  renderSnips(); toast("Snippet saved");
};
$("#snip-close").onclick = () => snipModal.classList.add("hidden");
snipModal.onclick = (e) => { if (e.target === snipModal) snipModal.classList.add("hidden"); };

// ---------- notifications (bell / idle) ----------
const notifiedSess = new Set(); // sessions with an unread notification (drives .tab.bell)
const BASE_TITLE = document.title;
const titleFor = () => view === "work"
  ? `${(currentWs && wsById(currentWs) ? wsById(currentWs).name : "Standalone")} — ${BASE_TITLE}`
  : BASE_TITLE;
const setTitle = () => { document.title = (notifiedSess.size ? "● " : "") + titleFor(); };
let notifAsked = false;
function reqNotifPerm() { // one-time, lazily from a user gesture
  if (notifAsked || !("Notification" in window) || Notification.permission !== "default") return;
  notifAsked = true;
  try { Notification.requestPermission(); } catch {}
}
// BEL, OSC 777, or OSC 9 — but NOT OSC 9;4 (taskbar progress, fires constantly)
function hasAttentionSeq(d) {
  if (d.includes("\x07") || d.includes("\x1b]777;")) return true;
  let i = 0;
  while ((i = d.indexOf("\x1b]9;", i)) !== -1) {
    if (d.substr(i + 5, 2) !== "4;") return true;
    i += 5;
  }
  return false;
}
const notifyAt = new Map(); // "id:reason" -> last toast/Notification ts
function notifySession(id, reason) {
  if (id === activeId && view === "work" && document.visibilityState === "visible") return; // you're looking at it
  notifiedSess.add(id);
  const t = tabsEl.querySelector(`[data-id="${id}"]`);
  if (t) t.classList.add("bell");
  setTitle(); refreshStates();
  const sess = sessions.find((s) => s.id === id);
  const body = reason === "bell" ? "Process signaled attention" : "Session idle after output";
  const visible = document.visibilityState === "visible";
  if (id === activeId && visible) return; // you're looking at it — the tab dot suffices
  const key = id + ":" + reason, now = Date.now();
  if (now - (notifyAt.get(key) || 0) < 60000) return; // max 1/min per session per reason
  notifyAt.set(key, now);
  try { navigator.vibrate?.(150); } catch {}
  if (visible) toast(`${sess ? sess.name : id}: ${body}`);
  if ("Notification" in window && Notification.permission === "granted") {
    try { new Notification(`S-remote — ${sess ? sess.name : id}`, { body, icon: "/logo.svg" }); } catch {}
  }
}
function clearNotify(id) {
  notifiedSess.delete(id);
  const t = tabsEl.querySelector(`[data-id="${id}"]`);
  if (t) t.classList.remove("bell");
  setTitle(); refreshStates();
}
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible") return;
  document.title = titleFor();
  if (view === "work" && activeId) { markSeen(activeId); refreshStates(); }
});
window.addEventListener("focus", () => { document.title = titleFor(); });
setInterval(() => { // idle detect: produced output then quiet ≥15s → notify once per busy→quiet edge
  const now = Date.now();
  for (const [id, p] of panes) {
    if (!p.lastOutAt || p.idleNotified || now - p.lastOutAt < 15000) continue;
    const s = sessions.find((x) => x.id === id);
    if (!s || s.exited) continue;
    if (id === activeId && document.visibilityState === "visible") continue;
    p.idleNotified = true;
    notifySession(id, "idle");
  }
}, 5000);

// ---------- copy mode (mobile can't select xterm text) ----------
const copyModal = $("#copy-modal"), copyText = $("#copy-text");
function openCopy() {
  const p = panes.get(activeId);
  if (!p) return toast("No active session", "err");
  const buf = p.term.buffer.active, lines = [];
  for (let i = 0; i < buf.length; i++) {
    lines.push(typeof buf.translateBufferLineToString === "function"
      ? buf.translateBufferLineToString(i, true)
      : (buf.getLine(i) ? buf.getLine(i).translateToString(true) : ""));
  }
  while (lines.length && !lines[lines.length - 1].trim()) lines.pop(); // trim trailing blanks
  copyText.value = lines.join("\n");
  copyModal.classList.remove("hidden");
}
$("#copy-ok").onclick = async () => {
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(copyText.value);
    else { copyText.select(); document.execCommand("copy"); }
    toast("Buffer copied");
    copyModal.classList.add("hidden");
  } catch { toast("Copy failed", "err"); }
};
$("#copy-cancel").onclick = () => copyModal.classList.add("hidden");
copyModal.onclick = (e) => { if (e.target === copyModal) copyModal.classList.add("hidden"); };

// ---------- drag & drop upload ----------
const dropzone = $("#dropzone"), mainEl = $("#main");
let dragDepth = 0;
const canDrop = () => view === "work" && !!fpRoot;
const fileDrag = (e) => { const t = e.dataTransfer && e.dataTransfer.types; return t && (t.includes ? t.includes("Files") : t.contains("Files")); };
mainEl.addEventListener("dragenter", (e) => {
  if (!fileDrag(e)) return;
  e.preventDefault();
  if (canDrop()) { dragDepth++; dropzone.classList.add("show"); }
});
mainEl.addEventListener("dragover", (e) => {
  if (!fileDrag(e)) return;
  e.preventDefault(); // required to allow drop
  if (canDrop()) { e.dataTransfer.dropEffect = "copy"; dropzone.classList.add("show"); }
});
mainEl.addEventListener("dragleave", () => {
  if (--dragDepth <= 0) { dragDepth = 0; dropzone.classList.remove("show"); }
});
mainEl.addEventListener("drop", (e) => {
  e.preventDefault(); // never navigate the app to a dropped file
  dragDepth = 0; dropzone.classList.remove("show");
  if (!canDrop()) return;
  const fs = [...(e.dataTransfer?.files || [])];
  if (fs.length) uploadFiles(fs);
});

// ---------- swipe to switch session ----------
let swipeX = null, swipeY = 0, swipeT = 0;
termsEl.addEventListener("touchstart", (e) => {
  if (e.touches.length !== 1) { swipeX = null; return; }
  const t = e.touches[0];
  swipeX = t.clientX; swipeY = t.clientY; swipeT = Date.now();
}, { passive: true });
termsEl.addEventListener("touchend", (e) => {
  if (swipeX === null) return;
  const t = e.changedTouches[0];
  const dx = t.clientX - swipeX, dy = t.clientY - swipeY, dt = Date.now() - swipeT;
  swipeX = null;
  if (Math.abs(dx) <= 70 || Math.abs(dy) >= 45 || dt >= 600) return;
  const list = sessInView();
  if (list.length < 2) return;
  const i = Math.max(0, list.findIndex((s) => s.id === activeId));
  const nxt = list[(i + (dx < 0 ? 1 : list.length - 1)) % list.length]; // left → next, right → prev
  if (nxt) select(nxt.id);
}, { passive: true });

// ---------- scrim (mobile drawers) ----------
$("#scrim").onclick = () => {
  if (sideVisible) { sideVisible = false; }
  if (fpVisible) { fpVisible = false; }
  applyToggles();
};

// ---------- keyboard shortcuts ----------
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    for (const m of ["#new-modal", "#picker", "#file-modal", "#snip-modal", "#copy-modal", "#settings-modal", "#palette", "#diff-modal", "#fx-modal"]) $(m).classList.add("hidden");
    $("#fx-ctx")?.classList.add("hidden");
    hideMenu(); hidePjPop();
    closeTermSearch();
    return;
  }
  if (!e.ctrlKey || e.shiftKey || e.altKey) return;
  if (e.key === "b" || e.key === "B") { // Ctrl+B sidebar
    if (view !== "work") return;
    sideVisible = !sideVisible; localStorage.setItem("rd.sidebar", sideVisible ? "1" : "0"); applyToggles();
    e.preventDefault();
  } else if (e.key === "j" || e.key === "J") { // Ctrl+J files
    if (view !== "work") return;
    fpVisible = !fpVisible; localStorage.setItem("rd.files", fpVisible ? "1" : "0"); applyToggles();
    e.preventDefault();
  } else if (e.key === "`") { // Ctrl+` new terminal
    if (view !== "work") return;
    openModal();
    e.preventDefault();
  } else if (e.key === "k" || e.key === "K") { // Ctrl+K command palette (home + work)
    e.preventDefault();
    if ($("#palette").classList.contains("hidden")) openPalette(); else closePalette();
  } else if (e.key === "f" || e.key === "F") { // Ctrl+F terminal search
    if (view !== "work") return;
    e.preventDefault();
    openTermSearch();
  }
});

// ---------- command palette (Ctrl+K) ----------
const palette = $("#palette"), palIn = $("#pal-in"), palList = $("#pal-list");
let palAll = [], palIdx = 0, palShown = [];
function palItems() {
  const items = [];
  // sessions in MRU order — the one you were on before sits first, so Ctrl+K, Enter flips back
  const ss = [...sessions].sort((a, b) => (a.id === activeId) - (b.id === activeId) || (a.exited - b.exited) || sessUsed(b) - sessUsed(a));
  for (const s of ss) {
    const w = sessWs(s) ? wsById(sessWs(s)) : null, ttl = sessTitle(s);
    items.push({ kind: "TERM", s, label: s.name + (s.exited ? " (exited)" : "") + (s.id === activeId ? " (current)" : ""),
      sub: `${w ? w.name : "Standalone"} · ${ttl || shortPath(s.cwd)}`, hay: [s.name, w ? w.name : "", ttl, s.cwd],
      run: () => openSession(s.id) });
  }
  for (const w of [...workspaces].sort((a, b) => wsUsed(b) - wsUsed(a))) {
    const n = wsSessions(w.id).filter((s) => !s.exited).length;
    items.push({ kind: "PROJ", label: w.name, sub: `${n ? n + " live · " : ""}${w.path}`, hay: [w.name, w.path],
      run: () => enterWorkspace(w.id) });
  }
  items.push(
    { kind: "ACT", label: "New terminal", sub: "open the create dialog", run: () => openModal() },
    { kind: "ACT", label: "Add project", sub: "pick a folder", run: () => addProject() },
    { kind: "ACT", label: "Toggle files panel", sub: "Ctrl+J", run: () => $("#files-toggle").click() },
    { kind: "ACT", label: "Toggle sidebar", sub: "Ctrl+B", run: () => { sideVisible = !sideVisible; localStorage.setItem("rd.sidebar", sideVisible ? "1" : "0"); applyToggles(); } },
    { kind: "ACT", label: "Go home", sub: "all projects", run: () => goHome() },
    { kind: "ACT", label: "File transfer", sub: "full file explorer", run: () => openExplorer() },
    { kind: "ACT", label: "Remote screen", sub: "view & control desktop", run: () => $("#screen-btn").click() },
    { kind: "ACT", label: "Copy mode", sub: "copy terminal buffer", run: () => openCopy() },
    { kind: "ACT", label: "Search in terminal", sub: "Ctrl+F", run: () => setTimeout(openTermSearch, 0) },
    { kind: "ACT", label: "Split terminal", sub: "two sessions side by side", run: () => toggleSplit() },
    { kind: "ACT", label: "Settings", sub: "font, theme, shell, keybar", run: () => openSettings() },
  );
  for (const it of items) if (!it.hay) it.hay = [it.label, it.sub];
  return items;
}
function palRender() {
  const q = palIn.value.trim();
  palShown = palAll.map((it, i) => ({ it, s: q ? fz(q, ...it.hay) + (it.kind === "ACT" ? -10 : 0) : -i }))
    .filter((x) => !q || x.s >= 0)
    .sort((a, b) => b.s - a.s)
    .map((x) => x.it).slice(0, 80);
  palIdx = Math.min(palIdx, Math.max(0, palShown.length - 1));
  palList.innerHTML = "";
  palShown.forEach((it, i) => {
    const d = document.createElement("div");
    d.className = "pal-item" + (i === palIdx ? " sel" : "");
    d.innerHTML = `<span class="pal-kind k-${it.kind.toLowerCase()}">${it.kind}</span>${it.s ? dotHtml(it.s) : ""}<span class="pal-txt"><span class="pal-name">${hl(it.label, q)}</span><span class="pal-sub">${hl(it.sub, q)}</span></span>`;
    d.onclick = () => { closePalette(); it.run(); };
    d.onmousemove = () => { if (palIdx !== i) { palIdx = i; palSync(); } };
    palList.appendChild(d);
  });
  if (!palShown.length) palList.innerHTML = `<div class="pal-none">No matches</div>`;
}
function palSync() {
  palList.querySelectorAll(".pal-item").forEach((d, i) => d.classList.toggle("sel", i === palIdx));
  const sel = palList.children[palIdx];
  if (sel && sel.scrollIntoView) sel.scrollIntoView({ block: "nearest" });
}
function openPalette() {
  palAll = palItems(); palIdx = 0; palIn.value = "";
  palRender();
  palette.classList.remove("hidden");
  palIn.focus();
}
function closePalette() { palette.classList.add("hidden"); }
palIn.addEventListener("input", () => { palIdx = 0; palRender(); });
palIn.addEventListener("keydown", (e) => {
  if (e.key === "ArrowDown") { e.preventDefault(); palIdx = Math.min(palIdx + 1, palShown.length - 1); palSync(); }
  else if (e.key === "ArrowUp") { e.preventDefault(); palIdx = Math.max(palIdx - 1, 0); palSync(); }
  else if (e.key === "Enter") { e.preventDefault(); const it = palShown[palIdx]; if (it) { closePalette(); it.run(); } }
  else if (e.key === "Escape") { e.preventDefault(); closePalette(); }
  e.stopPropagation();
});
palette.onclick = (e) => { if (e.target === palette) closePalette(); };
$("#pal-btn").onclick = openPalette;

// ---------- terminal search (Ctrl+F) ----------
const termSearch = $("#term-search"), tsIn = $("#ts-in");
function openTermSearch() {
  if (view !== "work" || !activeId) return;
  termSearch.classList.remove("hidden");
  tsIn.focus(); tsIn.select();
}
function closeTermSearch() {
  if (termSearch.classList.contains("hidden")) return;
  termSearch.classList.add("hidden");
  for (const p of panes.values()) if (p.search) { try { p.search.clearDecorations(); } catch {} }
  const p = panes.get(activeId); if (p) p.term.focus();
}
function tsFind(next) {
  const p = panes.get(activeId), q = tsIn.value;
  if (!p || !p.search || !q) return;
  const opts = { decorations: { matchBackground: "#3a5a80", matchOverviewRuler: "#5aa9ff", activeMatchBackground: "#5aa9ff", activeMatchColorOverviewRuler: "#ffffff" } };
  try { next ? p.search.findNext(q, opts) : p.search.findPrevious(q, opts); } catch {}
}
tsIn.addEventListener("input", () => tsFind(true));
tsIn.addEventListener("keydown", (e) => {
  if (e.key === "Enter") { e.preventDefault(); tsFind(!e.shiftKey); }
  else if (e.key === "Escape") { e.preventDefault(); closeTermSearch(); }
  e.stopPropagation();
});
$("#ts-next").onclick = () => tsFind(true);
$("#ts-prev").onclick = () => tsFind(false);
$("#ts-x").onclick = closeTermSearch;

// ---------- settings modal ----------
const setModal = $("#settings-modal");
function applyTermTheme() { for (const p of panes.values()) { try { p.term.options.theme = { ...TERM_THEMES[themeName] }; } catch {} } }
function applyTheme() {
  document.body.classList.toggle("light", themeName === "light");
  applyTermTheme();
}
function setFont(n) {
  fontSize = Math.min(22, Math.max(10, n));
  localStorage.setItem("rd.font", String(fontSize));
  $("#set-font-n").textContent = fontSize;
  for (const p of panes.values()) { try { p.term.options.fontSize = fontSize; } catch {} }
  requestAnimationFrame(fitVisible);
}
function renderSetKeys() { // checkbox per KB_KEYS entry → rd.keybar.v1 (KB_KEYS order) → rebuildKeybar()
  const el = $("#set-keys");
  el.innerHTML = "";
  const cfg = new Set(kbConfig());
  for (const k of KB_KEYS) {
    const lab = document.createElement("label");
    lab.className = "set-key";
    const cb = document.createElement("input");
    cb.type = "checkbox"; cb.checked = cfg.has(k.id);
    cb.onchange = () => {
      const cur = new Set(kbConfig());
      if (cb.checked) cur.add(k.id); else cur.delete(k.id);
      localStorage.setItem("rd.keybar.v1", JSON.stringify(KB_KEYS.filter((x) => cur.has(x.id)).map((x) => x.id)));
      rebuildKeybar();
    };
    lab.appendChild(cb);
    lab.appendChild(document.createTextNode(k.l));
    el.appendChild(lab);
  }
}
function openSettings() {
  $("#set-font-n").textContent = fontSize;
  for (const b of setModal.querySelectorAll("#set-theme button")) b.classList.toggle("on", b.dataset.v === themeName);
  const ss = $("#set-shell"), saved = localStorage.getItem("rd.shell") || "";
  ss.value = [...ss.options].some((o) => o.value === saved) ? saved : (ss.options[0] ? ss.options[0].value : "");
  $("#set-typebar").checked = localStorage.getItem("rd.typebar") !== "0";
  renderSetKeys();
  setModal.classList.remove("hidden");
}
$("#set-btn").onclick = openSettings;
$("#side-set").onclick = openSettings;
$("#set-close").onclick = () => setModal.classList.add("hidden");
setModal.onclick = (e) => { if (e.target === setModal) setModal.classList.add("hidden"); };
$("#set-font-m").onclick = () => setFont(fontSize - 1);
$("#set-font-p").onclick = () => setFont(fontSize + 1);
for (const b of document.querySelectorAll("#set-theme button")) {
  b.onclick = () => {
    themeName = b.dataset.v;
    localStorage.setItem("rd.theme", themeName);
    applyTheme();
    for (const x of document.querySelectorAll("#set-theme button")) x.classList.toggle("on", x === b);
  };
}
$("#set-shell").onchange = (e) => localStorage.setItem("rd.shell", e.target.value);
$("#set-typebar").onchange = (e) => { localStorage.setItem("rd.typebar", e.target.checked ? "1" : "0"); applyTypebar(); };
$("#set-reset").onclick = () => {
  for (const k of ["rd.w.side", "rd.w.files", "rd.sidebar", "rd.files"]) localStorage.removeItem(k);
  sidebar.style.width = ""; filesPanel.style.width = "";
  sideVisible = true; fpVisible = true;
  applyToggles();
  toast("Layout reset");
};
$("#split-btn").onclick = toggleSplit;

// ---------- files panel ----------
let fpRoot = null, fpPath = "";
const fpList = $("#fp-list"), fpCrumbs = $("#fp-crumbs");
function fileSvg() {
  return `<svg width="15" height="15" viewBox="0 0 20 20" fill="none"><path d="M5 2h6l4 4v12a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 4 18V3.5A1.5 1.5 0 0 1 5.5 2z" fill="#7d8fa3" opacity=".85"/><path d="M11 2l4 4h-4V2z" fill="#aeb9c6"/></svg>`;
}
async function fpNavigate(rel) {
  if (!fpRoot) return;
  const r = await fetch(`/api/files?base=${encodeURIComponent(fpRoot)}&path=${encodeURIComponent(rel)}`);
  const j = await r.json().catch(() => null);
  if (!r.ok || !j) { fpList.innerHTML = `<div class="fp-err">${esc(j?.error || "unreadable")}</div>`; return; }
  fpPath = j.path;
  // breadcrumbs
  fpCrumbs.innerHTML = "";
  const root = document.createElement("span");
  root.className = "pk-crumb"; root.textContent = "root";
  root.onclick = () => fpNavigate("");
  fpCrumbs.appendChild(root);
  if (j.path) {
    let acc = "";
    for (const seg of j.path.split(/[\\/]/)) {
      acc = acc ? acc + "/" + seg : seg;
      const sep = document.createElement("span"); sep.className = "pk-sep"; sep.textContent = "›";
      const c = document.createElement("span"); c.className = "pk-crumb"; c.textContent = seg;
      const t = acc; c.onclick = () => fpNavigate(t);
      fpCrumbs.appendChild(sep); fpCrumbs.appendChild(c);
    }
  }
  // items
  fpList.innerHTML = "";
  if (j.parent !== null) {
    const up = document.createElement("div");
    up.className = "fp-item";
    up.innerHTML = `<span class="fp-ico">${folderSvg(15)}</span><span class="fp-name">..</span>`;
    up.onclick = () => fpNavigate(j.parent);
    fpList.appendChild(up);
  }
  for (const it of j.items) {
    const d = document.createElement("div");
    d.className = "fp-item" + (it.dir ? " dir" : "");
    d.innerHTML = `<span class="fp-ico">${it.dir ? folderSvg(15) : fileSvg()}</span>
      <span class="fp-name" title="${esc(it.name)}">${esc(it.name)}</span>
      <span class="fp-acts">${it.dir ? "" : `<span class="fp-ins" title="Insert path into prompt">⎘</span>`}<span class="fp-act fp-ren" title="Rename">✎</span><span class="fp-act fp-del" title="Delete">🗑</span></span>
      <span class="fp-size">${it.dir ? "" : fmtSize(it.size)}</span>`;
    const rel = j.path ? j.path + "/" + it.name : it.name;
    const ins = d.querySelector(".fp-ins");
    if (ins) ins.onclick = (e) => { e.stopPropagation(); insertPath(rel); };
    d.querySelector(".fp-ren").onclick = (e) => { e.stopPropagation(); fpRename(rel, it); };
    d.querySelector(".fp-del").onclick = (e) => { e.stopPropagation(); fpDelete(rel, it); };
    if (it.dir) d.onclick = () => fpNavigate(rel);
    else {
      // long-press (~500ms) on touch = insert path; normal tap = preview
      let lp = false, lpT = null;
      d.addEventListener("touchstart", () => {
        lp = false; clearTimeout(lpT);
        lpT = setTimeout(() => { lp = true; lpT = null; try { navigator.vibrate?.(20); } catch {} insertPath(rel); }, 500);
      }, { passive: true });
      for (const ev of ["touchend", "touchmove", "touchcancel"])
        d.addEventListener(ev, () => { if (lpT) { clearTimeout(lpT); lpT = null; } }, { passive: true });
      d.onclick = () => { if (lp) { lp = false; return; } openFile(rel, it); };
    }
    fpList.appendChild(d);
  }
  if (!j.items.length && j.parent === null) fpList.innerHTML = `<div class="fp-err">Empty folder</div>`;
  const dBtn = $("#fp-diff"); // show only when the current root is a git repo
  gitInfo(fpRoot).then((g) => dBtn.classList.toggle("hidden", !(g && g.isRepo)));
}
function fpAbsPath(rel) { // absolute host path for a path inside fpRoot
  let base = fpRoot;
  if (base && base.startsWith("ws:")) { const w = wsById(base.slice(3)); base = w ? w.path : null; }
  if (!base) return null;
  base = base.replace(/[\\/]+$/, "");
  let p = rel ? base + "/" + rel.replace(/\\/g, "/") : base;
  if (/[:\\]/.test(base)) p = p.replace(/\//g, "\\"); // Windows-style root → backslashes
  return p;
}
function insertPath(rel) {
  const abs = fpAbsPath(rel);
  if (!abs) return toast("No workspace path", "err");
  const p = /\s/.test(abs) ? `"${abs}"` : abs;
  typer.value = typer.value && !typer.value.endsWith(" ") ? `${typer.value} ${p}` : typer.value + p;
  typerGrow(); // files panel stays open; don't steal focus
  toast("Path inserted");
}
async function fpOp(op, rel, name) {
  const r = await fetch("/api/fileop", { method: "POST", body: JSON.stringify({ base: fpRoot, op, path: rel, name }) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) { toast(j.error || `${op} failed`, "err"); return false; }
  return true;
}
async function fpRename(rel, it) {
  const nn = prompt("Rename to:", it.name);
  if (nn && nn.trim() && nn.trim() !== it.name && await fpOp("rename", rel, nn.trim())) {
    toast("Renamed"); fpNavigate(fpPath);
  }
}
async function fpDelete(rel, it) {
  if (!confirm(`Delete “${it.name}”${it.dir ? " and everything inside" : ""}?`)) return;
  if (await fpOp("delete", rel)) { toast("Deleted"); fpNavigate(fpPath); }
}
$("#fp-refresh").onclick = () => fpNavigate(fpPath);
$("#fp-newfile").onclick = async () => { // empty file in the current folder via /api/upload
  if (!fpRoot) return toast("No base folder", "err");
  const name = prompt("New file name:");
  if (!name || !name.trim()) return;
  const r = await fetch(`/api/upload?base=${encodeURIComponent(fpRoot)}&dir=${encodeURIComponent(fpPath || ".")}&name=${encodeURIComponent(name.trim())}`, { method: "POST" });
  const j = await r.json().catch(() => ({}));
  if (r.ok) { toast(`Created ${name.trim()}`); fpNavigate(fpPath); }
  else toast(j.error || "create failed", "err");
};
$("#fp-newdir").onclick = async () => {
  const parent = fpAbsPath(fpPath);
  if (!parent) return toast("No base folder", "err");
  const name = prompt("New folder name:");
  if (!name || !name.trim()) return;
  const r = await fetch("/api/mkdir", { method: "POST", body: JSON.stringify({ parent, name: name.trim() }) });
  const j = await r.json().catch(() => ({}));
  if (r.ok) { toast(`Created ${name.trim()}/`); fpNavigate(fpPath); }
  else toast(j.error || "mkdir failed", "err");
};
$("#fp-upload").onclick = () => { attachDir = fpPath || "."; $("#attach-file").click(); };

// ---------- file preview ----------
const fileModal = $("#file-modal");
const IMG_EXT = new Set(["png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif"]);
const VIDEO_EXT = new Set(["mp4", "m4v", "webm", "mov", "mkv", "ogv"]);
const AUDIO_EXT = new Set(["mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac"]);
const FRAME_EXT = new Set(["html", "htm", "pdf"]);
// /raw/<base>/<path> — path-style so html previews resolve relative assets
function rawUrl(base, rel) {
  const p = String(rel).replace(/\\/g, "/");
  return `/raw/${encodeURIComponent(base)}/` + p.split("/").filter(Boolean).map(encodeURIComponent).join("/");
}
const fileVideo = $("#file-video"), fileFrame = $("#file-frame"), fileSrcBtn = $("#file-src"), fileOpen = $("#file-open");
let fileSrcShow = null; // () => shows source of the current html file, set by openFile
function stopFileMedia() {
  fileVideo.pause(); fileVideo.removeAttribute("src"); fileVideo.load();
  if (fileFrame.getAttribute("src")) fileFrame.src = "about:blank";
}
// tiny markdown renderer — escape first, then fences/inline/headers/lists
function mdLite(src) {
  const inline = (t) => esc(t)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>")
    .replace(/\*([^*]+)\*/g, "<i>$1</i>")
    .replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
  let html = "", inCode = false, inList = false, para = [];
  const flushPara = () => { if (para.length) { html += "<p>" + para.map(inline).join("<br>") + "</p>"; para = []; } };
  const flushList = () => { if (inList) { html += "</ul>"; inList = false; } };
  for (const ln of src.split(/\r?\n/)) {
    if (/^```/.test(ln)) {
      if (inCode) { html += "</code></pre>"; inCode = false; }
      else { flushPara(); flushList(); html += "<pre><code>"; inCode = true; }
      continue;
    }
    if (inCode) { html += esc(ln) + "\n"; continue; }
    const h = /^(#{1,4})\s+(.*)$/.exec(ln);
    if (h) { flushPara(); flushList(); html += `<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`; continue; }
    const li = /^\s*[-*]\s+(.*)$/.exec(ln);
    if (li) { flushPara(); if (!inList) { html += "<ul>"; inList = true; } html += "<li>" + inline(li[1]) + "</li>"; continue; }
    if (!ln.trim()) { flushPara(); flushList(); continue; }
    para.push(ln);
  }
  flushPara(); flushList();
  if (inCode) html += "</code></pre>";
  return html || '<p class="md-dim">(empty file)</p>';
}
async function openFile(rel, it, base = fpRoot) {
  const url = `/api/file?base=${encodeURIComponent(base)}&path=${encodeURIComponent(rel)}`;
  $("#file-name").textContent = it.name;
  const dl = $("#file-dl");
  dl.href = url + "&dl=1";
  dl.download = it.name;
  const preEl = $("#file-body"), imgEl = $("#file-img"), mdEl = $("#file-body-html");
  stopFileMedia();
  for (const el of [preEl, imgEl, mdEl, fileVideo, fileFrame, fileSrcBtn, fileOpen]) el.classList.add("hidden");
  fileModal.classList.remove("hidden");
  const ext = (it.name.includes(".") ? it.name.split(".").pop() : "").toLowerCase();
  if (IMG_EXT.has(ext)) { imgEl.src = url; imgEl.alt = it.name; imgEl.classList.remove("hidden"); return; }
  if (VIDEO_EXT.has(ext) || AUDIO_EXT.has(ext)) {
    fileVideo.classList.toggle("audio", AUDIO_EXT.has(ext));
    fileVideo.src = url; fileVideo.classList.remove("hidden");
    fileOpen.href = url; fileOpen.classList.remove("hidden");
    fileVideo.onerror = () => {
      if (fileModal.classList.contains("hidden") || !fileVideo.getAttribute("src")) return;
      fileVideo.classList.add("hidden");
      preEl.textContent = `This browser can't play .${ext} files — use Download.`; preEl.classList.remove("hidden");
    };
    fileVideo.play().catch(() => {}); // autoplay may be blocked; controls stay
    return;
  }
  if (FRAME_EXT.has(ext)) {
    const raw = rawUrl(base, rel);
    fileFrame.src = raw; fileFrame.classList.remove("hidden");
    fileOpen.href = raw; fileOpen.classList.remove("hidden");
    if (ext !== "pdf") {
      fileSrcBtn.textContent = "Source"; fileSrcBtn.classList.remove("hidden");
      fileSrcShow = async () => {
        const src = fileFrame.classList.toggle("hidden");
        fileSrcBtn.textContent = src ? "Preview" : "Source";
        preEl.classList.toggle("hidden", !src);
        if (src && !preEl.dataset.loaded) {
          preEl.textContent = "loading…";
          try { const r = await fetch(url + "&head=1"); preEl.textContent = await r.text(); preEl.dataset.loaded = "1"; }
          catch (e) { preEl.textContent = "Failed to load: " + e.message; }
        }
      };
      delete preEl.dataset.loaded;
    }
    return;
  }
  if (ext === "md" || ext === "markdown") {
    mdEl.innerHTML = "loading…"; mdEl.classList.remove("hidden");
    try {
      const r = await fetch(url + "&head=1");
      mdEl.innerHTML = r.ok ? mdLite(await r.text()) : "Failed to load";
    } catch (e) { mdEl.textContent = "Failed to load: " + e.message; }
    return;
  }
  preEl.textContent = "loading…"; preEl.classList.remove("hidden");
  try {
    const r = await fetch(url + "&head=1");
    const size = Number(r.headers.get("X-File-Size") || it.size);
    const buf = await r.arrayBuffer();
    const text = new TextDecoder("utf-8", { fatal: false }).decode(buf);
    const printable = !/[\x00-\x08\x0E-\x1F]/.test(text.slice(0, 4000));
    preEl.textContent = printable
      ? text + (size > buf.byteLength ? `\n\n… truncated (${fmtSize(size)} total — use Download)` : "")
      : `Binary file — ${fmtSize(size)}. Use Download.`;
  } catch (e) { preEl.textContent = "Failed to load: " + e.message; }
}
$("#file-close").onclick = () => fileModal.classList.add("hidden");
fileSrcBtn.onclick = () => fileSrcShow && fileSrcShow();
// every close path (×, backdrop, Esc, Back) just hides the modal — stop playback there
new MutationObserver(() => { if (fileModal.classList.contains("hidden")) stopFileMedia(); })
  .observe(fileModal, { attributes: true, attributeFilter: ["class"] });
fileModal.onclick = (e) => { if (e.target === fileModal) fileModal.classList.add("hidden"); };

// ---------- git diff viewer ----------
const diffModal = $("#diff-modal");
async function openDiff() {
  if (!fpRoot) return;
  diffModal.classList.remove("hidden");
  $("#diff-title").textContent = `git diff — ${shortPath(fpAbsPath("") || fpRoot)}`;
  $("#diff-stat").textContent = "loading…";
  $("#diff-body").innerHTML = "";
  const r = await fetch(`/api/gitdiff?base=${encodeURIComponent(fpRoot)}`).catch(() => null);
  const j = r ? await r.json().catch(() => null) : null;
  if (!r || !r.ok || !j) { $("#diff-stat").textContent = (j && j.error) || "failed"; return; }
  $("#diff-stat").textContent =
    ((j.stat || "").trim() || "(clean working tree)") + (j.truncated ? "\n… diff truncated at 256 KB" : "");
  $("#diff-body").innerHTML = j.diff ? j.diff.split("\n").map((ln) => {
    let c = "";
    if (ln.startsWith("+++") || ln.startsWith("---") || ln.startsWith("diff ") || ln.startsWith("index ")) c = "dl-meta";
    else if (ln.startsWith("+")) c = "dl-add";
    else if (ln.startsWith("-")) c = "dl-del";
    else if (ln.startsWith("@@")) c = "dl-hunk";
    return `<span${c ? ` class="${c}"` : ""}>${esc(ln) || " "}</span>`;
  }).join("\n") : `<span class="dl-meta">no changes</span>`;
}
$("#fp-diff").onclick = openDiff;
$("#diff-refresh").onclick = openDiff;
$("#diff-close").onclick = () => diffModal.classList.add("hidden");
diffModal.onclick = (e) => { if (e.target === diffModal) diffModal.classList.add("hidden"); };

// ---------- folder picker (Win11 style) ----------
const picker = $("#picker"), pkMain = $("#pk-main"), pkCrumbs = $("#pk-crumbs"),
      pkQuick = $("#pk-quick"), pkSel = $("#pk-selected"), pkOk = $("#pk-ok");
let pkPath = "", pkHist = [""], pkHi = 0, pkItems = [], pkCb = null, pkSelectedPath = null;

function folderSvg(sz = 20) {
  return `<svg width="${sz}" height="${sz}" viewBox="0 0 20 20" fill="none"><path d="M2 5a2 2 0 0 1 2-2h3.2a2 2 0 0 1 1.6.8L10 5h6a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5z" fill="#f7d774"/><path d="M2 7h16v7a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7z" fill="#fcd669"/></svg>`;
}
function driveSvg() {
  return `<svg width="20" height="20" viewBox="0 0 20 20" fill="none"><rect x="2" y="6" width="16" height="8" rx="1.5" fill="#4a6fa5"/><rect x="3.5" y="7.5" width="13" height="5" rx="1" fill="#6e93c9"/><circle cx="15.5" cy="10" r="1" fill="#0d1117"/></svg>`;
}
function openPicker(cb) {
  pkCb = cb; pkSelectedPath = null; pkSel.value = ""; pkOk.disabled = true;
  pkHist = [""]; pkHi = 0;
  picker.classList.remove("hidden");
  pkNavigate("");
}
function pkNavigate(p, push = true) {
  fetch(`/api/browse?path=${encodeURIComponent(p)}`)
    .then((r) => r.json().then((j) => ({ ok: r.ok, j })))
    .then(({ ok, j }) => {
      if (!ok) { pkMain.innerHTML = `<div class="pk-err">Can't open: ${esc(j.error || "?")}</div>`; return; }
      pkPath = j.path; pkItems = j.items;
      if (push) { pkHist = pkHist.slice(0, pkHi + 1); pkHist.push(pkPath); pkHi++; }
      renderPicker(j);
    });
}
function renderPicker(j) {
  pkCrumbs.innerHTML = "";
  const root = document.createElement("span");
  root.className = "pk-crumb"; root.textContent = "This PC";
  root.onclick = () => pkNavigate("");
  pkCrumbs.appendChild(root);
  if (j.path) {
    const parts = j.path.replace(/[\\/]+$/, "").split(/[\\/]+/);
    let acc = j.path.startsWith("\\\\") ? "\\\\" : "";
    parts.forEach((seg, i) => {
      acc = i === 0 ? seg + "\\" : acc.replace(/\\$/, "") + "\\" + seg;
      const sep = document.createElement("span"); sep.className = "pk-sep"; sep.textContent = "›";
      const c = document.createElement("span"); c.className = "pk-crumb"; c.textContent = seg;
      const target = i === 0 && /^[A-Z]:$/i.test(seg) ? seg + "\\" : acc;
      c.onclick = () => pkNavigate(target);
      pkCrumbs.appendChild(sep); pkCrumbs.appendChild(c);
    });
  }
  pkQuick.innerHTML = "";
  for (const q of j.quickAccess || []) {
    const d = document.createElement("div");
    d.className = "pk-quick-item" + (q.path === pkPath ? " sel" : "");
    d.innerHTML = `${folderSvg(16)} ${esc(q.name)}`;
    d.onclick = () => pkNavigate(q.path);
    pkQuick.appendChild(d);
  }
  const pc = document.createElement("div");
  pc.className = "pk-quick-item" + (pkPath === "" ? " sel" : "");
  pc.innerHTML = `${driveSvg()} This PC`;
  pc.onclick = () => pkNavigate("");
  pkQuick.appendChild(pc);
  pkMain.innerHTML = "";
  if (!j.items.length) pkMain.innerHTML = `<div class="pk-err">This folder is empty</div>`;
  for (const it of j.items) {
    const d = document.createElement("div");
    d.className = "pk-item" + (it.path === pkSelectedPath ? " sel" : "");
    d.innerHTML = `<span class="pk-ico">${it.drive ? driveSvg() : folderSvg()}</span><span class="pk-name">${esc(it.name)}</span>`;
    d.onclick = () => {
      pkSelectedPath = it.path; pkSel.value = it.path; pkOk.disabled = false;
      pkMain.querySelectorAll(".pk-item.sel").forEach((x) => x.classList.remove("sel"));
      d.classList.add("sel");
    };
    d.ondblclick = () => pkNavigate(it.path);
    pkMain.appendChild(d);
  }
}
$("#pk-back").onclick = () => { if (pkHi > 0) { pkHi--; pkNavigate(pkHist[pkHi], false); } };
$("#pk-fwd").onclick = () => { if (pkHi < pkHist.length - 1) { pkHi++; pkNavigate(pkHist[pkHi], false); } };
$("#pk-up").onclick = () => {
  fetch(`/api/browse?path=${encodeURIComponent(pkPath)}`).then((r) => r.json()).then((j) => pkNavigate(j.parent ?? ""));
};
$("#pk-refresh").onclick = () => pkNavigate(pkPath, false);
$("#pk-cancel").onclick = () => picker.classList.add("hidden");
picker.onclick = (e) => { if (e.target === picker) picker.classList.add("hidden"); };
$("#pk-ok").onclick = () => {
  if (!pkSelectedPath || !pkCb) return;
  const cb = pkCb; pkCb = null;
  picker.classList.add("hidden");
  cb(pkSelectedPath);
};
$("#pk-newfolder").onclick = () => {
  if (!pkPath) return;
  const row = document.createElement("div");
  row.className = "pk-item pk-newfold";
  row.innerHTML = `<span class="pk-ico">${folderSvg()}</span>`;
  const inp = document.createElement("input");
  inp.placeholder = "New folder"; inp.autofocus = true;
  row.appendChild(inp);
  pkMain.prepend(row);
  inp.focus();
  inp.onkeydown = (e) => {
    if (e.key === "Escape") row.remove();
    if (e.key === "Enter" && inp.value.trim()) {
      fetch("/api/mkdir", { method: "POST", body: JSON.stringify({ parent: pkPath, name: inp.value.trim() }) })
        .then((r) => { if (r.ok) pkNavigate(pkPath, false); else r.json().then((j) => (inp.placeholder = j.error || "failed", inp.value = "")); });
    }
  };
  inp.onblur = () => row.remove();
};

// ---------- file explorer (home "File transfer") ----------
// Full-filesystem explorer over base "@abs": browse drives, preview, download,
// upload (button / drag-drop / paste), extract & create archives, rename,
// delete, copy/cut/paste, open-terminal-here. All paths are absolute.
const fxModal = $("#fx-modal"), fxMain = $("#fx-main"), fxCrumbs = $("#fx-crumbs"),
      fxQuick = $("#fx-quick"), fxStatus = $("#fx-status"), fxSelbar = $("#fx-selbar"),
      fxCtx = $("#fx-ctx"), fxDropEl = $("#fx-drop"), fxFilter = $("#fx-filter"),
      fxProg = $("#fx-prog"), fxPasteBtn = $("#fx-paste");
let fxPath = "", fxParent = null, fxHist = [""], fxHi = 0, fxItems = [], fxQuickItems = [];
let fxSel = new Set(), fxSortKey = "", fxSortAsc = true, fxAnchor = -1;
let fxClip = null, fxSuppressTap = false; // {op:"copy"|"move", paths:[abs...]}

const ARCH_RE = /\.(zip|7z|rar|jar|war|tar|tgz|tbz2|txz|tar\.gz|tar\.bz2|tar\.xz|gz|bz2|xz|cab)$/i;
const MEDIA_EXT = new Set(["mp3","wav","ogg","flac","mp4","mkv","avi","mov","webm","m4v","m4a","aac","opus","oga","ogv"]);
const EXEC_EXT = new Set(["exe","msi","bat","cmd","ps1","com","appx","msix"]);
function fxJoin(dir, name) {
  const sep = /[\\:]/.test(dir) ? "\\" : "/";
  return dir.replace(/[\\/]+$/, "") + sep + name;
}
function fxIcon(it) {
  if (it.drive) return driveSvg();
  if (it.dir) return folderSvg(18);
  const ext = (it.name.split(".").pop() || "").toLowerCase();
  if (ARCH_RE.test(it.name))
    return `<svg width="15" height="15" viewBox="0 0 20 20" fill="none"><rect x="3" y="4" width="14" height="13" rx="1.5" fill="#b48ad6"/><path d="M10 4v2m0 0v2h-1.5V6M10 8v2h1.5V8M10 10v1.5h-1.5V10M10 11.5V13h1.5v-1.5" stroke="#241733" stroke-width="1"/><rect x="8.5" y="13.5" width="3" height="2.5" rx=".5" fill="#241733"/></svg>`;
  if (IMG_EXT.has(ext))
    return `<svg width="15" height="15" viewBox="0 0 20 20" fill="none"><rect x="2.5" y="4" width="15" height="12" rx="1.5" fill="#4f8f6f"/><circle cx="6.8" cy="8" r="1.5" fill="#d9f2e4"/><path d="M4.5 15l4-4.2 3 3.1 2.4-2.4 2.6 2.6v1.4H4.5z" fill="#d9f2e4"/></svg>`;
  if (MEDIA_EXT.has(ext))
    return `<svg width="15" height="15" viewBox="0 0 20 20" fill="none"><rect x="2.5" y="4" width="15" height="12" rx="1.5" fill="#7a6fd0"/><path d="M8 7.5v5l4.5-2.5z" fill="#e7e5ff"/></svg>`;
  if (EXEC_EXT.has(ext))
    return `<svg width="15" height="15" viewBox="0 0 20 20" fill="none"><rect x="3" y="3" width="14" height="14" rx="2" fill="#5f7a94"/><path d="M6.5 6.5l3 3.5-3 3.5M10.5 13.5H14" stroke="#e3edf5" stroke-width="1.4" stroke-linecap="round"/></svg>`;
  return fileSvg();
}
const fmtMtime = (t) => !t ? "" : new Date(t).toLocaleString(undefined,
  { year: "2-digit", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });

async function fxOp(op, p, extra = {}) {
  const r = await fetch("/api/fileop", {
    method: "POST", body: JSON.stringify({ base: "@abs", op, path: p, ...extra }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) { toast(j.error || `${op} failed`, "err"); return null; }
  return j;
}
function openExplorer(startPath) {
  fxModal.classList.remove("hidden");
  fxSel.clear(); fxFilter.value = "";
  fetch("/api/browse?path=").then((r) => r.json()).then((j) => {
    fxQuickItems = j.quickAccess || [];
    renderFxQuick((j.items || []).filter((i) => i.drive));
  }).catch(() => renderFxQuick([]));
  fxHist = []; fxHi = -1;
  fxGo(startPath ?? fxPath, true); // settle() pushes the resolved path into history
}
function closeExplorer() { fxModal.classList.add("hidden"); fxHideCtx(); }

function fxGo(p, push = true) {
  const settle = (path, items, parent) => {
    fxPath = path; fxParent = parent; fxItems = items;
    if (push) { fxHist = fxHist.slice(0, fxHi + 1); fxHist.push(path); fxHi++; }
    fxSel.clear();
    renderFx();
  };
  if (!p) { // "This PC": drives come from /api/browse (dirs-only endpoint)
    fetch("/api/browse?path=").then((r) => r.json()).then((j) => {
      fxQuickItems = j.quickAccess || fxQuickItems;
      renderFxQuick((j.items || []).filter((i) => i.drive));
      settle("", (j.items || []).filter((i) => i.drive)
        .map((d) => ({ name: d.name, dir: true, drive: true, path: d.path, size: 0, mtime: 0 })), null);
    }).catch(() => { fxMain.innerHTML = `<div class="pk-err">Can't list drives</div>`; });
    return;
  }
  fetch(`/api/files?base=@abs&path=${encodeURIComponent(p)}`)
    .then((r) => r.json().then((j) => ({ ok: r.ok, j })))
    .then(({ ok, j }) => {
      if (!ok) { fxMain.innerHTML = `<div class="pk-err">Can't open: ${esc(j?.error || "?")}</div>`; return; }
      settle(j.path, j.items, j.parent);
    })
    .catch(() => { fxMain.innerHTML = `<div class="pk-err">Can't open ${esc(p)}</div>`; });
}
function renderFxQuick(drives) {
  fxQuick.innerHTML = "";
  const mk = (label, path, ico, sel) => {
    const d = document.createElement("div");
    d.className = "pk-quick-item" + (sel ? " sel" : "");
    d.innerHTML = `${ico} ${esc(label)}`;
    d.onclick = () => fxGo(path);
    fxQuick.appendChild(d);
  };
  mk("This PC", "", driveSvg(), fxPath === "");
  for (const q of fxQuickItems) mk(q.name, q.path, folderSvg(16), q.path === fxPath);
  for (const w of workspaces) mk(w.name, w.path, folderSvg(16), w.path === fxPath);
  for (const d of drives || []) mk(d.name, d.path, driveSvg(), d.path === fxPath);
}
function renderFx() {
  // crumbs
  fxCrumbs.innerHTML = "";
  const pcCrumb = document.createElement("span");
  pcCrumb.className = "pk-crumb"; pcCrumb.textContent = "This PC";
  pcCrumb.onclick = () => fxGo("");
  fxCrumbs.appendChild(pcCrumb);
  if (fxPath) {
    const parts = fxPath.replace(/[\\/]+$/, "").split(/[\\/]+/);
    let acc = fxPath.startsWith("\\\\") ? "\\\\" : "";
    parts.forEach((seg, i) => {
      acc = i === 0 ? (/^[A-Z]:$/i.test(seg) ? seg + "\\" : acc + seg) : acc.replace(/\\$/, "") + "\\" + seg;
      const sep = document.createElement("span"); sep.className = "pk-sep"; sep.textContent = "›";
      const c = document.createElement("span"); c.className = "pk-crumb"; c.textContent = seg;
      const t = acc; c.onclick = () => fxGo(t);
      fxCrumbs.appendChild(sep); fxCrumbs.appendChild(c);
    });
  }
  // sort header indicators
  const FX_COL = { name: "Name", size: "Size", mtime: "Modified" };
  for (const h of document.querySelectorAll("#fx-modal .fx-h"))
    h.textContent = (FX_COL[h.dataset.s] || h.dataset.s) +
      (fxSortKey === h.dataset.s ? (fxSortAsc ? " ▲" : " ▼") : "");
  // list
  fxMain.innerHTML = "";
  const frag = document.createDocumentFragment();
  if (fxParent !== null && fxParent !== undefined) {
    const up = document.createElement("div");
    up.className = "fx-item";
    up.innerHTML = `<span class="fx-ico">${folderSvg(18)}</span><span class="fx-name">..</span>`;
    up.onclick = () => fxGo(fxParent);
    frag.appendChild(up);
  }
  const q = fxFilter.value.trim().toLowerCase();
  let items = fxItems.filter((it) => !q || it.name.toLowerCase().includes(q));
  if (fxSortKey) {
    const f = { name: (a) => a.name.toLowerCase(), size: (a) => a.size, mtime: (a) => a.mtime }[fxSortKey];
    items = [...items].sort((a, b) => (b.dir - a.dir) || (fxSortAsc ? 1 : -1) * (f(a) > f(b) ? 1 : f(a) < f(b) ? -1 : 0));
  }
  items.forEach((it, idx) => {
    const d = document.createElement("div");
    d.className = "fx-item" + (it.dir ? " dir" : "") + (fxSel.has(it.name) ? " sel" : "");
    d.innerHTML = `<span class="fx-ico">${fxIcon(it)}</span>
      <span class="fx-name" title="${esc(it.name)}">${esc(it.name)}</span>
      <span class="fx-size">${it.dir ? "" : fmtSize(it.size)}</span>
      <span class="fx-mtime">${fmtMtime(it.mtime)}</span>`;
    const abs = it.drive ? it.path : (fxPath ? fxJoin(fxPath, it.name) : it.name);
    d.dataset.abs = abs; d.dataset.name = it.name; d.dataset.idx = idx;
    d.onclick = (e) => fxClickItem(e, d, it, abs, idx, items);
    d.ondblclick = () => fxOpenItem(it, abs);
    d.oncontextmenu = (e) => { e.preventDefault(); fxClickItem(e, d, it, abs, idx, items, true); fxShowCtx(e, it, abs); };
    let lpT = null; // long-press = context menu on touch
    d.addEventListener("touchstart", (e) => {
      clearTimeout(lpT);
      const t = e.touches[0];
      lpT = setTimeout(() => {
        lpT = null; fxSuppressTap = true;
        try { navigator.vibrate?.(20); } catch {}
        fxSel.add(it.name); renderFxSel();
        fxShowCtx({ clientX: t.clientX, clientY: t.clientY }, it, abs);
      }, 500);
    }, { passive: true });
    for (const ev of ["touchend", "touchmove", "touchcancel"])
      d.addEventListener(ev, () => { if (lpT) { clearTimeout(lpT); lpT = null; } }, { passive: true });
    frag.appendChild(d);
  });
  if (!items.length && fxPath) frag.appendChild(Object.assign(document.createElement("div"),
    { className: "pk-err", textContent: q ? "No matches" : "Empty folder" }));
  fxMain.appendChild(frag);
  fxStatusUpdate();
}
function fxClickItem(e, d, it, abs, idx, items, viaCtx = false) {
  if (fxSuppressTap && !viaCtx) { fxSuppressTap = false; return; } // long-press ate this tap
  fxHideCtx();
  if (isCoarse && !viaCtx) { fxOpenItem(it, abs); return; } // touch: tap = open
  if (e.ctrlKey || e.metaKey) {
    fxSel.has(it.name) ? fxSel.delete(it.name) : fxSel.add(it.name);
    fxAnchor = idx;
  } else if (e.shiftKey && fxAnchor >= 0) {
    const [a, b2] = [Math.min(fxAnchor, idx), Math.max(fxAnchor, idx)];
    fxSel = new Set(items.slice(a, b2 + 1).map((x) => x.name));
  } else {
    if (viaCtx && fxSel.has(it.name)) { /* right-click on selection keeps it */ }
    else { fxSel = new Set([it.name]); fxAnchor = idx; }
  }
  renderFxSel();
}
function renderFxSel() {
  fxMain.querySelectorAll(".fx-item[data-name]").forEach((el) =>
    el.classList.toggle("sel", fxSel.has(el.dataset.name)));
  fxStatusUpdate(); fxRenderSelbar();
}
function fxSelPaths() { return [...fxSel].map((n) => fxJoin(fxPath, n)); }
function fxSelItems() { return [...fxSel].map((n) => fxItems.find((i) => i.name === n)).filter(Boolean); }
function fxStatusUpdate() {
  fxStatus.textContent = fxPath
    ? `${fxItems.length} item${fxItems.length === 1 ? "" : "s"}${fxSel.size ? ` · ${fxSel.size} selected` : ""}`
    : `${fxItems.length} drive${fxItems.length === 1 ? "" : "s"}`;
  fxPasteBtn.disabled = !fxClip || !fxPath;
  fxPasteBtn.textContent = fxClip ? `Paste (${fxClip.paths.length})` : "Paste";
}
function fxRenderSelbar() {
  if (!fxSel.size) { fxSelbar.classList.add("hidden"); return; }
  fxSelbar.classList.remove("hidden");
  const one = fxSel.size === 1 ? fxSelItems()[0] : null;
  const anyDir = fxSelItems().some((i) => i.dir);
  const btn = (l, fn, title) => {
    const b = document.createElement("button");
    b.className = "mini-btn"; b.textContent = l; if (title) b.title = title;
    b.onclick = fn; fxSelbar.appendChild(b); return b;
  };
  fxSelbar.innerHTML = "";
  btn(one && one.dir ? "Open" : (one ? "Open / Preview" : "Open"), fxOpenSel);
  btn(anyDir || fxSel.size > 1 ? "Download as zip" : "Download", fxDownloadSel);
  if (one && !one.dir && ARCH_RE.test(one.name)) btn("Extract here", () => fxExtract(one));
  btn("Compress → zip", fxZipSel);
  btn("Copy", () => fxSetClip("copy")); btn("Cut", () => fxSetClip("move"));
  if (one) btn("Rename", () => fxRename(one));
  btn("Delete", fxDeleteSel);
  if (one) btn("Copy path", () => { copyToClipboard(fxJoin(fxPath, one.name)); toast("Path copied"); });
  btn("✕", () => { fxSel.clear(); renderFxSel(); }, "Clear selection");
}
function fxOpenSel() { const i = fxSelItems()[0]; if (i) fxOpenItem(i, fxJoin(fxPath, i.name)); }
function fxOpenItem(it, abs) {
  if (it.drive || it.dir) return fxGo(abs);
  openFile(abs, it, "@abs"); // preview modal; Download button inside it too
}
// ---- selection ops ----
function fxDl(href, name) { const a = document.createElement("a"); a.href = href; a.download = name || ""; a.click(); }
async function fxDownloadSel() {
  const sel = fxSelItems(); if (!sel.length) return;
  const one = sel.length === 1 ? sel[0] : null;
  if (one && !one.dir) {
    fxDl(`/api/file?base=@abs&path=${encodeURIComponent(fxJoin(fxPath, one.name))}&dl=1`, one.name);
    return;
  }
  toast("Creating archive…");
  const paths = sel.map((i) => fxJoin(fxPath, i.name));
  const j = sel.length > 1
    ? await fxOp("zip", paths[0], { paths, dest: fxPath })
    : await fxOp("zip", paths[0]);
  if (j && j.zip) { fxDl(`/api/file?base=@abs&path=${encodeURIComponent(j.zip)}&dl=1`, j.zip.split(/[\\/]/).pop()); fxGo(fxPath, false); }
}
async function fxZipSel() {
  const sel = fxSelItems(); if (!sel.length) return;
  const paths = sel.map((i) => fxJoin(fxPath, i.name));
  toast("Compressing…");
  const j = sel.length > 1
    ? await fxOp("zip", paths[0], { paths, dest: fxPath })
    : await fxOp("zip", paths[0]);
  if (j) { toast(`Created ${j.zip.split(/[\\/]/).pop()}`); fxGo(fxPath, false); }
}
async function fxExtract(it) {
  toast("Extracting…");
  const j = await fxOp("extract", fxJoin(fxPath, it.name));
  if (j) { toast(`Extracted → ${j.dir.split(/[\\/]/).pop()}/`); fxGo(j.dir || fxPath); }
}
function fxSetClip(op) { fxClip = { op, paths: fxSelPaths() }; fxStatusUpdate(); toast(`${op === "copy" ? "Copied" : "Cut"} ${fxClip.paths.length} item(s)`); }
async function fxPaste() {
  if (!fxClip || !fxPath) return;
  for (const p of fxClip.paths) {
    const j = await fxOp(fxClip.op, p, { dest: fxPath });
    if (!j) break;
  }
  if (fxClip.op === "move") fxClip = null;
  fxGo(fxPath, false);
}
async function fxRename(it) {
  const nn = prompt("Rename to:", it.name);
  if (nn && nn.trim() && nn.trim() !== it.name &&
      await fxOp("rename", fxJoin(fxPath, it.name), { name: nn.trim() })) {
    toast("Renamed"); fxGo(fxPath, false);
  }
}
async function fxDeleteSel() {
  const sel = fxSelItems(); if (!sel.length) return;
  if (!confirm(`Delete ${sel.length} item(s)${sel.some((i) => i.dir) ? " — folders are removed recursively" : ""}?`)) return;
  for (const it of sel) { const j = await fxOp("delete", fxJoin(fxPath, it.name)); if (!j) break; }
  fxSel.clear(); toast("Deleted"); fxGo(fxPath, false);
}
function fxTermHere(dir) {
  closeExplorer();
  enterWorkspace(null);
  send({ type: "create", cwd: dir, name: dir.split(/[\\/]/).filter(Boolean).pop() || "terminal" });
}
// ---- context menu ----
function fxHideCtx() { fxCtx.classList.add("hidden"); }
function fxShowCtx(e, it, abs) {
  const entries = [];
  if (it) {
    entries.push([it.dir || it.drive ? "Open" : "Open / Preview", () => fxOpenItem(it, abs)]);
    if (!it.dir && !it.drive) entries.push(["Download", () => fxDl(`/api/file?base=@abs&path=${encodeURIComponent(abs)}&dl=1`, it.name)]);
    if (it.dir) entries.push(["Open terminal here", () => fxTermHere(abs)]);
    if (!it.dir && ARCH_RE.test(it.name)) entries.push(["Extract here", () => fxExtract(it)]);
    entries.push(["Compress → zip", async () => {
      toast("Compressing…");
      const j = await fxOp("zip", abs);
      if (j) { toast(`Created ${j.zip.split(/[\\/]/).pop()}`); fxGo(fxPath, false); }
    }]);
    entries.push(null);
    entries.push(["Copy", () => { if (!fxSel.size) fxSel.add(it.name); fxSetClip("copy"); }]);
    entries.push(["Cut", () => { if (!fxSel.size) fxSel.add(it.name); fxSetClip("move"); }]);
    entries.push(["Rename", () => fxRename(it)]);
    entries.push(["Copy path", () => { copyToClipboard(abs); toast("Path copied"); }]);
    entries.push(null);
    entries.push(["Delete", () => { if (!fxSel.has(it.name)) { fxSel = new Set([it.name]); } fxDeleteSel(); }]);
  } else {
    entries.push(["Paste" + (fxClip ? ` (${fxClip.paths.length})` : ""), () => fxClip && fxPaste()]);
    entries.push(["Upload files…", () => $("#fx-file").click()]);
    entries.push(["New folder", fxNewDir]);
    entries.push(["New file", fxNewFile]);
    entries.push(null);
    entries.push(["Open terminal here", () => fxTermHere(fxPath)]);
    entries.push(["Refresh", () => fxGo(fxPath, false)]);
    entries.push(["Copy path", () => { copyToClipboard(fxPath); toast("Path copied"); }]);
  }
  fxCtx.innerHTML = "";
  for (const en of entries) {
    if (!en) { const s = document.createElement("div"); s.className = "ctx-sep"; fxCtx.appendChild(s); continue; }
    const b = document.createElement("button");
    b.className = "ctx-item"; b.textContent = en[0];
    if (!it && en[0].startsWith("Paste") && !fxClip) b.disabled = true;
    b.onclick = () => { fxHideCtx(); en[1](); };
    fxCtx.appendChild(b);
  }
  fxCtx.classList.remove("hidden");
  const r = fxCtx.getBoundingClientRect();
  fxCtx.style.left = Math.min(e.clientX, innerWidth - r.width - 6) + "px";
  fxCtx.style.top = Math.min(e.clientY, innerHeight - r.height - 6) + "px";
}
document.addEventListener("click", (e) => { if (!fxCtx.contains(e.target)) fxHideCtx(); });
document.addEventListener("scroll", fxHideCtx, true);
// background: click clears selection, right-click opens folder menu
fxMain.onclick = (e) => { if (!e.target.closest(".fx-item")) { fxSel.clear(); renderFxSel(); } };
fxMain.oncontextmenu = (e) => {
  if (e.target.closest(".fx-item")) return; // item menu handled per-row
  e.preventDefault(); fxHideCtx();
  fxSel.clear(); renderFxSel();
  fxShowCtx(e, null, null);
};
// ---- toolbar / footer wiring ----
$("#fx-open").onclick = () => openExplorer();
$("#fx-open").onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openExplorer(); } };
$("#fx-close").onclick = closeExplorer;
fxModal.onclick = (e) => { if (e.target === fxModal) closeExplorer(); };
$("#fx-back").onclick = () => { if (fxHi > 0) { fxHi--; fxGo(fxHist[fxHi], false); } };
$("#fx-fwd").onclick = () => { if (fxHi < fxHist.length - 1) { fxHi++; fxGo(fxHist[fxHi], false); } };
$("#fx-up").onclick = () => fxGo(fxParent ?? "");
$("#fx-home").onclick = () => fxGo("");
$("#fx-refresh").onclick = () => fxGo(fxPath, false);
fxFilter.oninput = () => renderFx();
for (const h of document.querySelectorAll("#fx-modal .fx-h"))
  h.onclick = () => { if (fxSortKey === h.dataset.s) fxSortAsc = !fxSortAsc; else { fxSortKey = h.dataset.s; fxSortAsc = true; } renderFx(); };
$("#fx-upload").onclick = () => $("#fx-file").click();
fxPasteBtn.onclick = fxPaste;
async function fxNewDir() {
  if (!fxPath) return;
  const name = prompt("New folder name:"); if (!name || !name.trim()) return;
  const j = await fxOp("mkdir", fxPath, { name: name.trim() });
  if (j) { toast(`Created ${name.trim()}/`); fxGo(fxPath, false); }
}
async function fxNewFile() {
  if (!fxPath) return;
  const name = prompt("New file name:"); if (!name || !name.trim()) return;
  const r = await fetch(`/api/upload?base=@abs&dir=${encodeURIComponent(fxPath)}&name=${encodeURIComponent(name.trim())}`, { method: "POST" });
  const j = await r.json().catch(() => ({}));
  if (r.ok) { toast(`Created ${name.trim()}`); fxGo(fxPath, false); } else toast(j.error || "create failed", "err");
}
$("#fx-newdir").onclick = fxNewDir;
$("#fx-newfile").onclick = fxNewFile;
// ---- upload (picker + drag-drop) with progress ----
function fxProgSet(f) {
  fxProg.classList.toggle("on", f > 0);
  fxProg.firstElementChild.style.width = Math.round(f * 100) + "%";
}
function fxUploadOne(f) {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `/api/upload?base=@abs&dir=${encodeURIComponent(fxPath)}&name=${encodeURIComponent(f.name)}`);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) fxProgSet(e.loaded / e.total); };
    xhr.onload = () => {
      fxProgSet(0);
      const j = JSON.parse(xhr.responseText || "{}");
      if (xhr.status < 300) toast(`Uploaded ${f.name}`); else toast(`Upload failed: ${j.error || xhr.status}`, "err");
      resolve();
    };
    xhr.onerror = () => { fxProgSet(0); toast("Upload failed", "err"); resolve(); };
    fxProgSet(0.01); xhr.send(f);
  });
}
async function fxUploadFiles(fs) {
  if (!fs.length) return;
  if (!fxPath) return toast("Open a folder first", "err");
  for (const f of fs) await fxUploadOne(f);
  fxGo(fxPath, false);
}
$("#fx-file").addEventListener("change", (e) => { const fs = [...e.target.files]; e.target.value = ""; fxUploadFiles(fs); });
const fxCard = document.querySelector(".fx-card");
fxCard.addEventListener("dragover", (e) => {
  if (!fxPath || ![...e.dataTransfer.types].includes("Files")) return;
  e.preventDefault(); fxDropEl.classList.remove("hidden");
});
fxDropEl.addEventListener("dragleave", () => fxDropEl.classList.add("hidden"));
fxDropEl.addEventListener("drop", (e) => {
  e.preventDefault(); fxDropEl.classList.add("hidden");
  fxUploadFiles([...e.dataTransfer.files]);
});
// keyboard inside explorer
document.addEventListener("keydown", (e) => {
  if (fxModal.classList.contains("hidden")) return;
  const tag = (e.target.tagName || "").toLowerCase();
  if (tag === "input" || tag === "textarea" || tag === "select") return;
  if (e.key === "Delete") fxDeleteSel();
  else if (e.key === "F2") { const i = fxSelItems()[0]; if (i) fxRename(i); }
  else if (e.key === "Enter") fxOpenSel();
  else if (e.key === "Backspace") { e.preventDefault(); fxGo(fxParent ?? ""); }
  else if (e.ctrlKey && !e.shiftKey && !e.altKey) {
    if (e.key === "a" || e.key === "A") { e.preventDefault(); fxSel = new Set(fxItems.map((i) => i.name)); renderFxSel(); }
    else if (e.key === "c" || e.key === "C") { if (fxSel.size) fxSetClip("copy"); }
    else if (e.key === "x" || e.key === "X") { if (fxSel.size) fxSetClip("move"); }
    else if (e.key === "v" || e.key === "V") fxPaste();
  }
});

// ---------- new session modal ----------
const modal = $("#new-modal"), shellSel = $("#new-shell"), cwdIn = $("#new-cwd"), nameIn = $("#new-name");
function openModal() {
  const w = currentWs ? wsById(currentWs) : null;
  modal.classList.remove("hidden");
  cwdIn.value = w ? w.path : (hostInfo.home || "");
  cwdIn.disabled = !!w;
  const ds = localStorage.getItem("rd.shell");
  if (ds && [...shellSel.options].some((o) => o.value === ds)) shellSel.value = ds;
  nameIn.value = "";
  nameIn.placeholder = w ? w.name : "(optional)";
  nameIn.focus();
}
function closeModal() { modal.classList.add("hidden"); cwdIn.disabled = false; }
$("#new-btn").onclick = openModal;
$("#empty-new").onclick = openModal;
$("#new-cancel").onclick = closeModal;
modal.onclick = (e) => { if (e.target === modal) closeModal(); };
$("#new-browse").onclick = () => openPicker((p) => { cwdIn.value = p; });
$("#new-ok").onclick = () => {
  send({
    type: "create", shell: shellSel.value, cols: 120, rows: 32,
    name: nameIn.value || undefined,
    ...(currentWs ? { workspace: currentWs } : { cwd: cwdIn.value || undefined }),
  });
  closeModal();
};

// ---------- boot ----------
(async () => {
  const info = await fetch("/api/info").then((r) => r.json()).catch(() => ({}));
  hostInfo = info;
  $("#host-info").textContent = `${info.hostname || "?"} · ${info.tailscaleIp || location.hostname}`;
  for (const sh of info.shells || ["powershell"]) {
    for (const sel of [shellSel, $("#set-shell")]) {
      const o = document.createElement("option");
      o.value = o.textContent = sh;
      sel.appendChild(o);
    }
  }
  applyTheme();
  loadWorkspaces();
  renderMachines(); renderDevports(); // initial home view — showView() isn't called at boot
  applyToggles();
  connect();
  setInterval(refreshStates, 3000); // busy → idle edges + "3m" labels (no server tick when output stops)
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
  let lastTermsH = termsEl.clientHeight;
  new ResizeObserver(() => {
    const h = termsEl.clientHeight, d = h - lastTermsH;
    lastTermsH = h;
    // typer wrapping a line shrinks #terms by exactly its growth — that isn't a
    // real resize, so skip the xterm refit/redraw for it
    if (d !== 0 && typerDelta && Math.abs(d + typerDelta) <= 2) { typerDelta = 0; return; }
    typerDelta = 0;
    fitVisible();
  }).observe(termsEl);
  // ---------- remote screen ----------
  const scrModal = $("#screen-modal"), scrImg = $("#scr-img"), scrStat = $("#scr-stat");
  const scrStage = $("#scr-stage"), scrCanvas = $("#scr-canvas"), scrCtx = scrCanvas.getContext("2d");
  let scrOpen = false, scrPoll = null, scrArmR = false, scrPt = null, scrT2 = null;
  let scrPro = false;                 // pro mode: native dxgi push agent
  const scrSend = (o, pro) => send({ type: "screen", pro: pro === undefined ? scrPro : pro, ...o });
  const scrReqW = () => {
    return Math.max(480, Math.min(1920, Math.round((scrStage ? scrStage.clientWidth : innerWidth) * devicePixelRatio)));
  };
  const scrView = () => (scrPro ? scrCanvas : scrImg);
  const scrFrac = (cx, cy) => {
    const r = scrView().getBoundingClientRect();
    return { x: Math.min(1, Math.max(0, (cx - r.left) / r.width)), y: Math.min(1, Math.max(0, (cy - r.top) / r.height)) };
  };
  const scrAsk = (delay) => {
    clearTimeout(scrPoll);
    if (scrOpen && !scrPro) scrPoll = setTimeout(() => scrSend({ op: "shot", w: scrReqW(), q: 55 }), delay);
  };
  const scrSub = () => scrSend({ op: "sub", w: scrReqW(), fps: 15, q: 70 });
  const scrShow = () => {
    scrImg.classList.toggle("hidden", scrPro);
    scrCanvas.classList.toggle("hidden", !scrPro);
  };
  let scrWatch = null, scrGot = false;
  const scrWatchdog = () => {
    clearInterval(scrWatch);
    scrGot = false;
    scrWatch = setInterval(() => {
      if (!scrOpen || scrGot) { clearInterval(scrWatch); return; }
      if (!wsAlive) {
        scrStat.textContent = "socket down — reconnecting…";
        clearTimeout(reconnectTimer);
        try { connect(); } catch {}
        return;
      }
      scrStat.textContent = "waiting for host… (old server? reload/upgrade)";
      scrSub();
    }, 4000);
  };
  screenResub = () => { if (scrOpen) scrSub(); };
  onScreenMsg = (m) => {
    if (m.pro) {
      if (m.op === "clip") {                                       // host clipboard -> local
        if (navigator.clipboard && m.text != null)
          navigator.clipboard.writeText(m.text)
            .then(() => { scrStat.textContent = "⧉ host clipboard synced"; })
            .catch(() => {});
        return;
      }
      if (m.op === "pro-needed") scrStat.textContent = "pro: extension not installed — tap PRO again to install";
      else if (m.op === "err") scrStat.textContent = m.msg || "pro error";
      else if (m.op === "info" && !scrGot) scrStat.textContent = `pro ${m.w}x${m.h} [${m.src || "?"}], waiting frames…`;
      else if (!scrGot) scrStat.textContent = "pro: " + m.op;
      return;
    }
    if (m.op === "frame") {
      if (!scrGot) { scrGot = true; clearInterval(scrWatch); }
      scrImg.src = "data:image/jpeg;base64," + m.b64; scrStat.textContent = m.w + "x" + m.h; scrAsk(120);
    } else if (m.op === "err") { scrStat.textContent = m.msg || "error"; scrAsk(1000); }
    else if (!scrGot) scrStat.textContent = (m.op === "info" ? `host ${m.w}x${m.h}, waiting frames…` : "host: " + m.op);
  };
  // binary pro tile frame: [u8 'F'][u16 w][u16 h][u16 tile][u16 count] then x,y,w,h,jlen,jpeg
  onScreenBin = (ab) => {
    if (!scrOpen || !scrPro) return;
    const dv = new DataView(ab);
    if (dv.getUint8(0) !== 0x46) return;              // 'F'
    const w = dv.getUint16(1, true), h = dv.getUint16(3, true), n = dv.getUint16(7, true);
    if (scrCanvas.width !== w || scrCanvas.height !== h) { scrCanvas.width = w; scrCanvas.height = h; }
    let off = 9;
    const jobs = [];
    for (let i = 0; i < n; i++) {
      const x = dv.getUint16(off, true), y = dv.getUint16(off + 2, true);
      const tw = dv.getUint16(off + 4, true), th = dv.getUint16(off + 6, true);
      const jl = dv.getUint32(off + 8, true); off += 12;
      const jpg = new Blob([ab.slice(off, off + jl)], { type: "image/jpeg" }); off += jl;
      jobs.push(createImageBitmap(jpg).then((b) => { scrCtx.drawImage(b, x, y, tw, th); b.close(); }));
    }
    Promise.all(jobs).then(() => {
      if (!scrGot) { scrGot = true; clearInterval(scrWatch); }
      scrStat.textContent = `${w}x${h} pro`;
    }).catch(() => {});
  };
  const setPro = async (on) => {
    const btn = $("#scr-pro");
    if (on) {
      scrStat.textContent = "checking pro extension…";
      let st = await fetch("/api/screen-pro").then((r) => r.json()).catch(() => null);
      if (!st) { scrStat.textContent = "host unreachable"; return; }
      if (!st.installed) {
        if (!confirm("Remote Pro needs a small native agent on the host (~20KB, compiled locally via .NET — no download). Install it now?")) {
          scrStat.textContent = "";
          return;
        }
        scrStat.textContent = "installing pro extension…";
        st = await fetch("/api/screen-pro", { method: "POST" }).then((r) => r.json()).catch(() => null);
        if (!st || !st.ok) { scrStat.textContent = "install failed: " + ((st && st.error) || "see host console"); return; }
      }
    }
    scrSend({ op: "unsub" }, scrPro);                  // leave current mode
    scrPro = on;
    btn.classList.toggle("on", scrPro);
    scrShow();
    if (scrOpen) { scrGot = false; scrSub(); scrWatchdog(); }
  };
  $("#scr-pro").onclick = () => setPro(!scrPro);
  $("#screen-btn").onclick = () => {
    scrModal.classList.remove("hidden"); scrOpen = true;
    scrImg.removeAttribute("src"); scrShow(); scrStat.textContent = "connecting…";
    scrSub();
    scrWatchdog();
  };
  $("#scr-close").onclick = () => {
    scrOpen = false; clearTimeout(scrPoll); clearInterval(scrWatch);
    scrModal.classList.add("hidden");
    if (held.size) { releaseAll(); }                                // don't leave keys stuck on host
    if ($("#scr-block").classList.contains("on")) { scrSend({ op: "block", on: false }, true); $("#scr-block").classList.remove("on"); }
    scrSend({ op: "unsub" }, false); scrSend({ op: "unsub" }, true);
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  };
  $("#scr-rmb").onclick = (e) => { scrArmR = !scrArmR; e.target.classList.toggle("on", scrArmR); };
  $("#scr-kb").onclick = () => $("#scr-keys").classList.toggle("hidden");
  $("#scr-send").onclick = () => {
    const t = $("#scr-type").value;
    if (t) scrSend({ op: "type", text: t });
    scrSend({ op: "key", k: "{ENTER}" });
    $("#scr-type").value = "";
  };
  $("#scr-type").addEventListener("keydown", (e) => { if (e.key === "Enter") $("#scr-send").click(); });
  for (const b of document.querySelectorAll(".scr-k"))
    b.onclick = () => scrSend({ op: "key", k: b.dataset.k });
  // ---------- pro control: real keyboard + clipboard + shortcuts ----------
  const VK = { Backspace: 8, Tab: 9, Enter: 13, ShiftLeft: 160, ShiftRight: 161, ControlLeft: 162, ControlRight: 163,
    AltLeft: 164, AltRight: 165, Pause: 19, CapsLock: 20, Escape: 27, Space: 32, PageUp: 33, PageDown: 34, End: 35,
    Home: 36, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, PrintScreen: 44, Insert: 45, Delete: 46,
    MetaLeft: 91, MetaRight: 92, ContextMenu: 93, NumLock: 144, ScrollLock: 145,
    Semicolon: 186, Equal: 187, Comma: 188, Minus: 189, Period: 190, Slash: 191, Backquote: 192,
    BracketLeft: 219, Backslash: 220, BracketRight: 221, Quote: 222,
    NumpadMultiply: 106, NumpadAdd: 107, NumpadSubtract: 109, NumpadDecimal: 110, NumpadDivide: 111 };
  for (let i = 0; i < 10; i++) { VK["Digit" + i] = 48 + i; VK["Numpad" + i] = 96 + i; }
  for (let i = 0; i < 26; i++) VK["Key" + String.fromCharCode(65 + i)] = 65 + i;
  for (let i = 1; i <= 12; i++) VK["F" + i] = 111 + i;
  const held = new Set();
  const releaseAll = () => { for (const vk of held) scrSend({ op: "ku", vk }); held.clear(); };
  const COMBOS = { "alt-tab": [[164], 9], "alt-f4": [[164], 115], taskmgr: [[162, 160], 27], win: [[], 91],
    "win-d": [[91], 68], "win-e": [[91], 69], "win-r": [[91], 82], prtsc: [[], 44] };
  scrCanvas.tabIndex = 0;
  scrCanvas.addEventListener("keydown", (e) => {
    if (!scrPro) return;
    e.preventDefault(); e.stopPropagation();
    const vk = VK[e.code];
    if (e.ctrlKey && e.code === "KeyV") {                          // ordered: clip first, then host paste
      held.delete(86);
      if (navigator.clipboard)
        navigator.clipboard.readText()
          .then((t) => { if (t) scrSend({ op: "clip", text: t }); setTimeout(() => scrSend({ op: "combo", mods: [162], vk: 86 }), 120); })
          .catch(() => scrSend({ op: "combo", mods: [162], vk: 86 }));
      else scrSend({ op: "combo", mods: [162], vk: 86 });
      return;
    }
    if (!vk || held.has(vk)) return;
    held.add(vk); scrSend({ op: "kd", vk });
  });
  scrCanvas.addEventListener("keyup", (e) => {
    if (!scrPro) return;
    e.preventDefault();
    const vk = VK[e.code];
    if (vk && held.delete(vk)) scrSend({ op: "ku", vk });
  });
  scrCanvas.addEventListener("blur", releaseAll);
  scrCanvas.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    if (scrOpen) scrSend({ op: "click", btn: "r", ...scrFrac(e.clientX, e.clientY) });
  });
  scrCanvas.addEventListener("auxclick", (e) => {
    e.preventDefault();
    if (!scrOpen) return;
    const btn = e.button === 1 ? "m" : e.button === 3 ? "x1" : e.button === 4 ? "x2" : null;
    if (btn) scrSend({ op: "click", btn, ...scrFrac(e.clientX, e.clientY) });
  });
  $("#scr-paste").onclick = () => {                                // mobile paste: clip -> ctrl+v
    if (!navigator.clipboard) return;
    navigator.clipboard.readText()
      .then((t) => { if (t) { scrSend({ op: "clip", text: t }); setTimeout(() => scrSend({ op: "combo", mods: [162], vk: 86 }), 150); } })
      .catch(() => { scrStat.textContent = "clipboard blocked — long-press and use browser paste"; });
  };
  for (const b of document.querySelectorAll(".scr-c"))
    b.onclick = () => { const c = COMBOS[b.dataset.c]; if (c) scrSend({ op: "combo", mods: c[0], vk: c[1] }); };
  $("#scr-fs").onclick = () => {
    const card = document.querySelector(".screen-card");
    if (document.fullscreenElement) document.exitFullscreen();
    else if (card.requestFullscreen) card.requestFullscreen().catch(() => {});
  };
  $("#scr-block").onclick = (e) => {
    const on = !e.target.classList.contains("on");
    e.target.classList.toggle("on", on);
    scrSend({ op: "block", on });
    scrStat.textContent = on ? "host input BLOCKED" : "";
  };
  // pointer: tap = click, press-and-drag = drag, long-press or armed = right click
  scrStage.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    if (scrPro) scrCanvas.focus({ preventScroll: true });           // keyboard capture for pro
    if (scrPt) { scrPt = null; return; }          // second finger cancels
    scrStage.setPointerCapture(e.pointerId);
    scrPt = { x: e.clientX, y: e.clientY, t: Date.now(), drag: false, pid: e.pointerId };
  });
  scrStage.addEventListener("pointermove", (e) => {
    if (!scrPt || e.pointerId !== scrPt.pid) return;
    if (!scrPt.drag && Math.hypot(e.clientX - scrPt.x, e.clientY - scrPt.y) > 8) {
      scrPt.drag = true;
      scrSend({ op: "down", btn: "l", ...scrFrac(scrPt.x, scrPt.y) });
    }
    if (scrPt.drag) scrSend({ op: "move", ...scrFrac(e.clientX, e.clientY) });
  });
  const scrUp = (e) => {
    if (!scrPt || e.pointerId !== scrPt.pid) return;
    if (scrPt.drag) scrSend({ op: "up", btn: "l", ...scrFrac(e.clientX, e.clientY) });
    else {
      const right = scrArmR || Date.now() - scrPt.t > 550;
      scrSend({ op: "click", btn: right ? "r" : "l", ...scrFrac(e.clientX, e.clientY) });
    }
    scrArmR = false; $("#scr-rmb").classList.remove("on"); scrPt = null;
  };
  scrStage.addEventListener("pointerup", scrUp);
  scrStage.addEventListener("pointercancel", () => { scrPt = null; });
  scrStage.addEventListener("dblclick", (e) => scrSend({ op: "click", dbl: true, ...scrFrac(e.clientX, e.clientY) }));
  scrStage.addEventListener("wheel", (e) => {
    e.preventDefault();
    scrSend({ op: "scroll", d: Math.sign(e.deltaY) * 360, ...scrFrac(e.clientX, e.clientY) });
  }, { passive: false });
  scrStage.addEventListener("touchmove", (e) => {           // two-finger scroll
    if (e.touches.length !== 2) { scrT2 = null; return; }
    const y = (e.touches[0].clientY + e.touches[1].clientY) / 2;
    if (scrT2 != null && Math.abs(y - scrT2) > 6) { scrSend({ op: "scroll", x: 0.5, y: 0.5, d: (y - scrT2) * 2 }); scrT2 = y; }
    else scrT2 = y;
  }, { passive: true });

  // soft keyboard: slide the whole frame up instead of refitting terminals
  // (no repaint). Sources, whichever reports more: virtualKeyboard API
  // (Chromium 94+) and visualViewport (Safari/Firefox/older). In the APK the
  // native layer shifts the frame and sets window.__nativeKb — then we yield.
  if (window.visualViewport || navigator.virtualKeyboard) {
    const covered = () => {
      let c = 0;
      if (navigator.virtualKeyboard)
        c = Math.max(c, navigator.virtualKeyboard.boundingRect.height || 0);
      if (window.visualViewport)
        c = Math.max(c, innerHeight - visualViewport.height - visualViewport.offsetTop);
      return c;
    };
    const apply = () => {
      const px = covered();
      const kb = px > 60;
      document.body.style.transform = !(window.__nativeKb > 0) && kb
          ? `translateY(${-px}px)` : "";
      if (!kb) requestAnimationFrame(fitVisible); // genuine resizes only
    };
    if (navigator.virtualKeyboard) {
      try { navigator.virtualKeyboard.overlaysContent = true; } catch (e) {}
      navigator.virtualKeyboard.addEventListener("geometrychange", apply);
    }
    if (window.visualViewport) {
      visualViewport.addEventListener("resize", apply);
      visualViewport.addEventListener("scroll", apply);
    }
    addEventListener("focusin", () => setTimeout(apply, 300));
    addEventListener("focusout", () => setTimeout(apply, 150));
  }
})();
