// S-remote — remote screen page. Own tab, own WebSocket.
// Pro agent (agent/screen-pro): binary tile frames, acked for flow control;
// cursor position/shape arrive as JSON and are drawn locally. Falls back to the
// PowerShell shot agent when the pro agent can't be built.
"use strict";
const $ = (s) => document.querySelector(s);
const LS = {
  get(k, d) { try { const v = localStorage.getItem("scr." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("scr." + k, JSON.stringify(v)); } catch {} },
};
const IN_APP = /SRemoteApp/.test(navigator.userAgent);
const TOUCH = matchMedia("(pointer: coarse)").matches || (navigator.maxTouchPoints > 0 && !matchMedia("(pointer: fine)").matches);
document.body.classList.toggle("touch", TOUCH);

const stage = $("#stage"), cv = $("#cv"), ctx = cv.getContext("2d"), curEl = $("#cur");
const bar = $("#bar"), statsEl = $("#stats");

// ---------- state ----------
let ws = null, wsUp = false, retry = 0, retryT = null;
let pro = true;                       // false = PowerShell fallback agent
let subbed = false, gotFrame = false;
let cap = { w: 1920, h: 1080 };       // host capture size (physical px)
let mons = [], monSel = LS.get("mon", -2);
let quality = LS.get("q", "auto");
let mode = LS.get("mode", "trackpad"); // touch: trackpad | direct
let view = { z: 1, px: 0, py: 0 };   // zoom (1 = fit) + pan in css px
let cur = { x: 0.5, y: 0.5, vis: false, id: 0 };
const shapes = new Map();             // id -> {url, w, h, hx, hy}
let mouseInside = false;
let rtt = 0, autoQ = 70;
let rotCss = false, rotLocked = false;  // forced landscape: CSS-rotated page / Orientation Lock

// ---------- small ui helpers ----------
const msgBox = $("#msg"), msgT = $("#msg-t"), msgAct = $("#msg-act");
function status(text, opts = {}) {
  if (!text) { msgBox.classList.add("hidden"); return; }
  msgBox.classList.remove("hidden");
  msgBox.classList.toggle("err", !!opts.err);
  msgT.textContent = text;
  msgAct.classList.toggle("hidden", !opts.act);
  if (opts.act) { msgAct.textContent = opts.act; msgAct.onclick = opts.onAct; }
}
let toastT = null;
function toast(text, act, onAct, ms = 3500) {
  const t = $("#toast");
  t.innerHTML = "";
  t.append(Object.assign(document.createElement("span"), { textContent: text }));
  if (act) { const b = Object.assign(document.createElement("button"), { textContent: act }); b.onclick = () => { onAct(); t.classList.add("hidden"); }; t.append(b); }
  t.classList.remove("hidden");
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.add("hidden"), ms);
}

// ---------- websocket ----------
function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
function scr(o) {
  if (!pro && o.b) { o = { ...o, btn: o.b }; delete o.b; }   // basic agent names it btn
  send({ type: "screen", pro, ...o });
}

function qParams() {
  const vw = Math.round(Math.max(stage.clientWidth, stage.clientHeight * cap.w / cap.h) * (devicePixelRatio || 1));
  switch (quality) {
    case "high": return { w: cap.w, q: 82 };
    case "balanced": return { w: Math.min(cap.w, 1600), q: 65 };
    case "low": return { w: Math.min(cap.w, 1100), q: 45 };
    default: return { w: Math.min(cap.w, TOUCH ? 1600 : Math.max(1280, Math.min(2560, vw))), q: autoQ };
  }
}
let watchT = null;
function subscribe() {
  if (!wsUp) return;
  subbed = true;
  clearTimeout(watchT);
  watchT = setTimeout(() => { if (subbed && !gotFrame && wsUp) { status("No picture yet — retrying…"); subscribe(); } }, 12000);
  const p = qParams();
  if (pro) scr({ op: "sub", flow: 1, w: p.w, q: p.q, fps: 30, mon: monSel });
  else { scr({ op: "sub", w: p.w, q: 55 }); }
}
function unsubscribe() { if (subbed) { scr({ op: "unsub" }); subbed = false; } }
function reconfig() {
  const p = qParams();
  if (pro) scr({ op: "config", w: p.w, q: p.q, fps: 30 });
}

function connect() {
  clearTimeout(retryT);
  try { ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws"); }
  catch { return schedule(); }
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {
    wsUp = true; retry = 0; gotFrame = false;
    if (!document.hidden) { status("Starting screen…"); subscribe(); }
  };
  ws.onclose = () => {
    wsUp = false; subbed = false;
    status("Connection lost — reconnecting…");
    schedule();
  };
  ws.onerror = () => {};
  ws.onmessage = (ev) => {
    if (typeof ev.data !== "string") { onFrame(ev.data); return; }
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === "pong") { rtt = Date.now() - m.t; adapt(); return; }
    if (m.type === "screen") onScreen(m);
  };
}
function schedule() { clearTimeout(retryT); retryT = setTimeout(connect, Math.min(5000, 500 * 2 ** retry++)); }

function onScreen(m) {
  if (m.pro === undefined && m.op === "err" && /windows-only/i.test(m.msg || "")) {
    status("Remote screen works on Windows hosts only.", { err: true });
    return;
  }
  if (!pro && !m.pro) return onBasic(m);
  if (!m.pro) return;
  switch (m.op) {
    case "status": status(m.msg); break;
    case "pro-needed":
      // native agent couldn't be built — fall back to the PowerShell agent
      toast("Native agent unavailable (" + (m.error || "build failed").slice(0, 80) + ") — using basic mode", null, null, 6000);
      pro = false; mode = "direct"; applyMode(); subscribe();
      break;
    case "info":
      cap = { w: m.w, h: m.h };
      mons = m.mons || [];
      renderMons(m.mon);
      layout();
      break;
    case "cur":
      // while the finger drives the pointer, keep the local prediction (server echoes lag behind)
      if (!predicting()) { cur.x = m.x; cur.y = m.y; }
      cur.vis = m.vis; if (m.id) setShape(m.id);
      placeCursor();
      follow();
      break;
    case "shape":
      shapes.set(m.id, { url: "data:image/png;base64," + m.png, w: m.w, h: m.h, hx: m.hx, hy: m.hy });
      if (m.id === cur.id || !cur.id) setShape(m.id, true);
      break;
    case "clip": onHostClip(m.text); break;
    case "block":
      blockOn = !!m.on;
      $("#block-st").textContent = blockOn ? "ON" : "off"; $("#block-st").classList.toggle("on", blockOn);
      if (!m.ok && m.on !== undefined && !blockOn) toast("Blocking input needs S-remote running as admin (elevate.ps1)");
      break;
    case "err":
      if (/dxgi off|cap:/.test(m.msg || "")) console.debug("[screen]", m.msg);
      else if (/exited/.test(m.msg || "")) { status("Screen agent restarted…"); setTimeout(subscribe, 600); }
      break;
  }
}

// ---------- frames ----------
let drawQ = Promise.resolve(), nFrames = 0, nBytes = 0;
function onFrame(ab) {
  if (!pro) return;
  nBytes += ab.byteLength;
  drawQ = drawQ.then(() => drawFrame(ab)).catch(() => {});
}
async function drawFrame(ab) {
  const dv = new DataView(ab);
  if (dv.getUint8(0) !== 0x46) return;            // 'F'
  const w = dv.getUint16(1, true), h = dv.getUint16(3, true), n = dv.getUint16(7, true);
  let off = 9;
  const rects = [];
  for (let i = 0; i < n; i++) {
    const x = dv.getUint16(off, true), y = dv.getUint16(off + 2, true);
    const tw = dv.getUint16(off + 4, true), th = dv.getUint16(off + 6, true), jl = dv.getUint32(off + 8, true);
    off += 12;
    rects.push({ x, y, tw, th, blob: new Blob([new Uint8Array(ab, off, jl)], { type: "image/jpeg" }) });
    off += jl;
  }
  try {
    const bms = await Promise.all(rects.map((r) => createImageBitmap(r.blob)));
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; layout(); }
    bms.forEach((b, i) => { const r = rects[i]; ctx.drawImage(b, r.x, r.y, r.tw, r.th); b.close(); });
  } finally {
    scr({ op: "ack" });                            // grants the agent its next frame
  }
  nFrames++;
  if (!gotFrame) { gotFrame = true; status(null); maybeHelp(); }
}

// ---------- basic (PowerShell) fallback ----------
let basicImg = new Image(), basicBusy = false;
function onBasic(m) {
  if (m.op === "info") { cap = { w: m.w, h: m.h }; layout(); return; }
  if (m.op === "frame") {
    nBytes += m.b64.length * 0.75;
    basicImg.onload = () => {
      if (cv.width !== m.w || cv.height !== m.h) { cv.width = m.w; cv.height = m.h; layout(); }
      ctx.drawImage(basicImg, 0, 0); nFrames++;
      if (!gotFrame) { gotFrame = true; status(null); }
      setTimeout(() => subbed && scr({ op: "shot", w: qParams().w, q: 55 }), 60);
    };
    basicImg.src = "data:image/jpeg;base64," + m.b64;
  } else if (m.op === "err") setTimeout(() => subbed && scr({ op: "shot", w: qParams().w, q: 55 }), 1000);
}

// ---------- stats + auto quality ----------
setInterval(() => {
  if (wsUp) send({ type: "ping", t: Date.now() });
}, 2000);
let statT0 = performance.now();
setInterval(() => {
  const dt = (performance.now() - statT0) / 1000; statT0 = performance.now();
  const fps = Math.round(nFrames / dt), kbs = Math.round(nBytes / 1024 / dt);
  nFrames = 0; nBytes = 0;
  if (!wsUp) { statsEl.textContent = "offline"; statsEl.classList.add("warn"); return; }
  statsEl.textContent = `${cv.width}×${cv.height} · ${fps} fps · ${kbs >= 1024 ? (kbs / 1024).toFixed(1) + " MB/s" : kbs + " KB/s"} · ${rtt} ms${pro ? "" : " · basic"}`;
  statsEl.classList.toggle("warn", rtt > 250);
}, 1000);
function adapt() {
  if (quality !== "auto" || !pro) return;
  const want = rtt > 400 ? 40 : rtt > 200 ? 55 : 70;
  if (want !== autoQ) { autoQ = want; reconfig(); }
}

// ---------- layout / view transform ----------
let fitS = 1, baseH = 0, kbWas = false, nativeKb = 0, vvKb = 0;
// soft keyboard up? (app: native shift; browsers: visual viewport or a shrunken stage while typing)
function kbActive(sh) {
  if (!TOUCH || rotCss) return false;
  return nativeKb > 0 || vvKb > 60 || (document.activeElement === kbd && baseH - sh > 100);
}
function layout() {
  const sw = stage.clientWidth, sh = stage.clientHeight;
  const fw = cv.width || cap.w, fh = cv.height || cap.h;
  const kbA = kbActive(sh);
  if (!kbA) baseH = sh;
  // with the keyboard up keep the picture's size (fit to the full height) instead of shrinking it
  fitS = Math.min(sw / fw, (kbA ? Math.max(sh, baseH) : sh) / fh) || 1;
  const s = fitS * view.z, dw = fw * s, dh = fh * s;
  if (kbA && !kbWas && dh > sh) view.py = sh * 0.42 - cur.y * dh;    // keyboard just opened: the input point (pointer) stays visible
  kbWas = kbA;
  // keep the picture covering the stage when zoomed in; centered otherwise
  const minX = Math.min(0, sw - dw), minY = Math.min(0, sh - dh);
  view.px = dw <= sw ? (sw - dw) / 2 : Math.max(minX, Math.min(0, view.px));
  view.py = dh <= sh ? (sh - dh) / 2 : Math.max(minY, Math.min(0, view.py));
  cv.style.transform = `translate(${view.px}px, ${view.py}px) scale(${s})`;
  cv.classList.toggle("crisp", s >= 1.5);
  $("#b-fit").classList.toggle("hidden", view.z <= 1.01);
  placeCursor();
}
function rect() {
  const fw = cv.width, fh = cv.height, s = fitS * view.z;
  return { left: view.px, top: view.py, width: fw * s, height: fh * s };
}
// event -> page-local coords (the CSS-rotated landscape turns the page 90° clockwise)
function loc(e) { return rotCss ? { x: e.clientY, y: innerWidth - e.clientX } : { x: e.clientX, y: e.clientY }; }
// page-local coords -> fraction of the remote screen
function frac(lx, ly) {
  const r = rect();
  return {
    x: Math.min(1, Math.max(0, (lx - stage.offsetLeft - r.left) / r.width)),
    y: Math.min(1, Math.max(0, (ly - stage.offsetTop - r.top) / r.height)),
  };
}
function zoomAt(z, lx, ly) {
  const ox = lx - stage.offsetLeft, oy = ly - stage.offsetTop;
  const nz = Math.max(1, Math.min(6, z));
  const k = nz / view.z;
  view.px = ox - (ox - view.px) * k; view.py = oy - (oy - view.py) * k;
  view.z = nz; layout();
}
function fit() { view.z = 1; layout(); }
$("#b-fit").onclick = fit;
let resizeT = null;
addEventListener("resize", () => { layout(); clearTimeout(resizeT); resizeT = setTimeout(() => { if (quality === "auto") reconfig(); }, 400); });

// keep the remote cursor in view while zoomed (trackpad drives the cursor, not the view)
function follow() {
  if (!TOUCH) return;
  const r = rect(), sw = stage.clientWidth, sh = stage.clientHeight, m = 60;
  if (r.width <= sw + 1 && r.height <= sh + 1) return;              // whole picture visible
  const x = r.left + cur.x * r.width, y = r.top + cur.y * r.height;
  let dx = 0, dy = 0;
  if (x < m) dx = m - x; else if (x > sw - m) dx = sw - m - x;
  if (y < m) dy = m - y; else if (y > sh - m) dy = sh - m - y;
  if (dx || dy) { view.px += dx; view.py += dy; layout(); }
}

// ---------- cursor ----------
let shapeNow = null, predictUntil = 0;
const predicting = () => performance.now() < predictUntil;
function setShape(id, force) {
  if (id === cur.id && !force) return;
  cur.id = id;
  const sh = shapes.get(id);
  if (!sh) return;
  shapeNow = sh;
  curEl.src = sh.url;
  // desktop: the local pointer becomes the remote cursor (zero-latency feel)
  if (!TOUCH) stage.style.cursor = `url(${sh.url}) ${sh.hx} ${sh.hy}, default`;
  placeCursor();
}
function placeCursor() {
  const show = gotFrame && cur.vis && shapeNow && (TOUCH || !mouseInside);
  curEl.classList.toggle("hidden", !show);
  if (!TOUCH) stage.style.cursor = mouseInside && !cur.vis ? "none" : (shapeNow ? `url(${shapeNow.url}) ${shapeNow.hx} ${shapeNow.hy}, default` : "default");
  if (!show) return;
  const r = rect();
  // scale the host cursor with the picture, but keep it usable on a phone
  const k = Math.max(0.6, Math.min(1.25, (r.width / cap.w) * (TOUCH ? 2 : 1)));
  const x = r.left + cur.x * r.width - shapeNow.hx * k, y = r.top + cur.y * r.height - shapeNow.hy * k;
  curEl.style.width = shapeNow.w * k + "px";
  curEl.style.transform = `translate(${x}px, ${y}px)`;
}

// ---------- modifiers (sticky keys panel) ----------
const sticky = new Map();             // vk -> "on" | "lock"
function stickyDown() { for (const vk of sticky.keys()) scr({ op: "kd", vk }); }
function stickyRelease() {
  let changed = false;
  for (const [vk, st] of [...sticky]) if (st === "on") { scr({ op: "ku", vk }); sticky.delete(vk); changed = true; }
  if (changed) renderSticky();
}
function renderSticky() {
  for (const b of document.querySelectorAll(".k.mod")) {
    const st = sticky.get(+b.dataset.mod);
    b.classList.toggle("on", !!st); b.classList.toggle("lock", st === "lock");
  }
}
// a key press from the panel / phone keyboard, honoring sticky modifiers
function tapKey(vk) {
  if (!pro) return basicKey(vk);
  scr({ op: "kd", vk }); scr({ op: "ku", vk });
  stickyRelease();
}
function typeText(t) {
  if (!t) return;
  if (sticky.size && pro) {                        // Ctrl+<letter> from the phone keyboard
    for (const ch of t) {
      const up = ch.toUpperCase(), vk = /[A-Z0-9]/.test(up) ? up.charCodeAt(0) : 0;
      if (vk) tapKey(vk); else scr({ op: "type", text: ch });
    }
    stickyRelease();
    return;
  }
  scr({ op: "type", text: t });
}

// ---------- mouse (desktop) ----------
const BTN = ["l", "m", "r", "x1", "x2"];
let movePending = null, moveRaf = 0;
function queueMove(p) {
  movePending = p;
  if (!moveRaf) moveRaf = requestAnimationFrame(() => { moveRaf = 0; if (movePending) scr({ op: "move", ...movePending }); movePending = null; });
}
function flushMove() { if (moveRaf) { cancelAnimationFrame(moveRaf); moveRaf = 0; } if (movePending) { scr({ op: "move", ...movePending }); movePending = null; } }
stage.addEventListener("pointerenter", (e) => { if (e.pointerType === "mouse") { mouseInside = true; placeCursor(); } });
stage.addEventListener("pointerleave", (e) => { if (e.pointerType === "mouse") { mouseInside = false; placeCursor(); } });

// ---------- pointer router ----------
const pts = new Map();                // pointerId -> {x,y,x0,y0,t0}
let g = null;                         // current touch gesture
stage.addEventListener("pointerdown", (e) => {
  if (!gotFrame) return;
  hidePops();
  stage.focus({ preventScroll: true });
  const L = loc(e);
  if (e.pointerType === "mouse" || e.pointerType === "pen" && !TOUCH) {
    e.preventDefault();
    stage.setPointerCapture(e.pointerId);
    const p = frac(L.x, L.y);
    flushMove();
    if (sticky.size) stickyDown();
    scr({ op: "down", b: BTN[e.button] || "l", ...p });
    return;
  }
  e.preventDefault();
  stage.setPointerCapture(e.pointerId);
  pts.set(e.pointerId, { x: L.x, y: L.y, x0: L.x, y0: L.y, t0: performance.now() });
  touchStart(L);
});
stage.addEventListener("pointermove", (e) => {
  if (e.pointerType === "mouse" || e.pointerType === "pen" && !TOUCH) {
    if (!gotFrame) return;
    const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    const last = loc(evs[evs.length - 1] || e);
    queueMove(frac(last.x, last.y));
    return;
  }
  const p = pts.get(e.pointerId);
  if (!p) return;
  const L = loc(e), px = p.x, py = p.y;
  p.x = L.x; p.y = L.y;
  touchMove(L, L.x - px, L.y - py);
});
function pointerEnd(e) {
  if (e.pointerType === "mouse" || e.pointerType === "pen" && !TOUCH) {
    if (!gotFrame) return;
    flushMove();
    const L = loc(e);
    scr({ op: "up", b: BTN[e.button] || "l", ...frac(L.x, L.y) });
    stickyRelease();
    return;
  }
  if (!pts.has(e.pointerId)) return;
  touchEnd(loc(e), e.type === "pointercancel");
  pts.delete(e.pointerId);
}
stage.addEventListener("pointerup", pointerEnd);
stage.addEventListener("pointercancel", pointerEnd);
stage.addEventListener("contextmenu", (e) => e.preventDefault());
stage.addEventListener("auxclick", (e) => e.preventDefault());

// wheel → host wheel (120 per notch); pixel deltas from touchpads pass through for smooth scrolling
let wheelAcc = { dx: 0, dy: 0, p: null }, wheelRaf = 0;
stage.addEventListener("wheel", (e) => {
  e.preventDefault();
  if (!gotFrame) return;
  const k = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 800 : 1;
  wheelAcc.dy += -e.deltaY * k * 1.2; wheelAcc.dx += e.deltaX * k * 1.2;
  const L = loc(e); wheelAcc.p = frac(L.x, L.y);
  if (!wheelRaf) wheelRaf = requestAnimationFrame(() => {
    wheelRaf = 0;
    const dy = Math.round(wheelAcc.dy), dx = Math.round(wheelAcc.dx);
    if (dy || dx) scr(pro ? { op: "wheel", dy, dx, ...wheelAcc.p } : { op: "scroll", d: dy, ...wheelAcc.p });
    wheelAcc = { dx: 0, dy: 0, p: null };
  });
}, { passive: false });

// ---------- touch gestures ----------
// trackpad: 1 finger = move cursor, tap = click, double-tap-hold = drag, hold = drag,
//           2 fingers: tap = right click, drag = scroll, pinch = zoom; 3-finger tap = keyboard
// direct:   tap = click there, hold = right click, drag = left drag,
//           2 fingers: pinch = zoom, drag = scroll (or pan while zoomed)
let lastTap = { t: 0, x: 0, y: 0 };
let relAcc = { x: 0, y: 0 };
const LONG = 450, TAPMS = 260, SLOP = 9;
function centroid() {
  let x = 0, y = 0; for (const p of pts.values()) { x += p.x; y += p.y; }
  return { x: x / pts.size, y: y / pts.size };
}
function spread() {
  const a = [...pts.values()]; if (a.length < 2) return 0;
  return Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y);
}
function vibrate(ms) { try { navigator.vibrate && navigator.vibrate(ms); } catch {} }
function touchStart(e) {
  const n = pts.size;
  if (n === 1) {
    const now = performance.now();
    const dbl = now - lastTap.t < 320 && Math.hypot(e.x - lastTap.x, e.y - lastTap.y) < 40;
    g = { kind: "one", t0: now, moved: false, drag: false, maxN: 1, x0: e.x, y0: e.y };
    if (mode === "trackpad" && dbl && pro) {         // double-tap-and-hold → drag
      g.drag = true; if (sticky.size) stickyDown(); scr({ op: "down", b: "l" });
    }
    clearTimeout(g.longT);
    g.longT = setTimeout(() => {
      if (!g || g.moved || pts.size !== 1 || g.drag) return;
      vibrate(12);
      if (mode === "trackpad") { g.drag = true; if (sticky.size) stickyDown(); scr({ op: "down", b: "l" }); }
      else { g.right = true; scr({ op: "click", b: "r", ...frac(g.x0, g.y0) }); }
    }, LONG);
  } else if (g) {
    clearTimeout(g.longT);
    if (g.drag && !g.dragging2) { /* keep drag on 1 finger only */ }
    g.maxN = Math.max(g.maxN, n);
    if (n === 2) { g.kind = "two"; g.c = centroid(); g.d0 = spread(); g.z0 = view.z; g.moved2 = false; g.pinch = false; g.t2 = performance.now(); }
  }
}
function touchMove(e, dx, dy) {
  if (!g) return;
  const n = pts.size;
  if (g.kind === "one" && n === 1) {
    if (!g.moved && Math.hypot(e.x - g.x0, e.y - g.y0) > SLOP) {
      g.moved = true; clearTimeout(g.longT);
      if (mode === "direct" && !g.right) {           // direct drag = left drag from the touch point
        g.drag = true; scr({ op: "move", ...frac(g.x0, g.y0) }); if (sticky.size) stickyDown(); scr({ op: "down", b: "l", ...frac(g.x0, g.y0) });
      }
    }
    if (!g.moved && !g.drag) return;
    if (mode === "trackpad" && pro) {
      // relative motion with acceleration; whole-stage swipe ≈ whole host width
      const sp = Math.hypot(dx, dy);
      const gain = (cap.w / Math.max(320, stage.clientWidth)) * (0.9 + Math.min(2.2, sp / 9)) / Math.sqrt(view.z);
      relAcc.x += dx * gain; relAcc.y += dy * gain;
      const ix = Math.trunc(relAcc.x), iy = Math.trunc(relAcc.y);
      if (ix || iy) {
        relAcc.x -= ix; relAcc.y -= iy;
        scr({ op: "mrel", dx: ix, dy: iy });
        cur.x = Math.min(1, Math.max(0, cur.x + ix / cap.w)); cur.y = Math.min(1, Math.max(0, cur.y + iy / cap.h));
        predictUntil = performance.now() + 250; placeCursor(); follow();
      }
    } else if (g.drag || mode === "direct") {
      queueMove(frac(e.x, e.y));
    }
    return;
  }
  if (g.kind === "two" && n >= 2) {
    const c = centroid(), d = spread();
    const cdx = c.x - g.c.x, cdy = c.y - g.c.y;
    if (!g.moved2 && (Math.hypot(cdx, cdy) > SLOP || Math.abs(d - g.d0) > 14)) g.moved2 = true;
    if (!g.moved2) return;
    if (!g.pinch && Math.abs(d - g.d0) > 28) g.pinch = true;
    if (g.pinch) zoomAt(g.z0 * (d / g.d0), c.x, c.y);
    if (view.z > 1.01 && mode === "direct") {        // pan the zoomed view
      view.px += cdx; view.py += cdy; layout();
    } else if (!g.pinch || Math.abs(cdy) > 2) {
      // two-finger drag scrolls the host (natural direction)
      g.wy = (g.wy || 0) + cdy * 4; g.wx = (g.wx || 0) - cdx * 4;
      const wy = Math.trunc(g.wy / 40) * 40, wx = Math.trunc(g.wx / 40) * 40;
      if (wy || wx) {
        g.wy -= wy; g.wx -= wx;
        const at = mode === "direct" ? frac(c.x, c.y) : {};
        scr(pro ? { op: "wheel", dy: wy, dx: wx, ...at } : { op: "scroll", d: wy, ...(at.x != null ? at : { x: cur.x, y: cur.y }) });
      }
    }
    g.c = c;
  }
}
function touchEnd(e, cancel) {
  if (!g) return;
  const left = pts.size - 1;                       // fingers still down after this one
  const now = performance.now();
  if (left > 0) {
    if (g.kind === "two" && left === 1 && g.maxN === 2 && !g.moved2 && now - g.t2 < 300 && !cancel) {
      g.twoTap = true;
    }
    if (g.maxN === 3 && left === 2 && now - g.t0 < 350 && !cancel) g.threeTap = true;
    return;
  }
  clearTimeout(g.longT);
  const dur = now - g.t0;
  if (cancel) { if (g.drag) scr({ op: "up", b: "l" }); g = null; return; }
  if (g.threeTap || (g.maxN === 3 && dur < 350)) { openKbd(); g = null; return; }
  if (g.maxN === 2 && (g.twoTap || (!g.moved2 && dur < 350))) {
    vibrate(8);
    scr(mode === "direct" || !pro ? { op: "click", b: "r", ...frac(g.c.x, g.c.y) } : { op: "click", b: "r" });
    stickyRelease(); g = null; return;
  }
  if (g.kind === "one") {
    flushMove();
    if (g.drag) {
      scr(mode === "direct" ? { op: "up", b: "l", ...frac(e.x, e.y) } : { op: "up", b: "l" });
      stickyRelease();
    } else if (!g.moved && !g.right && dur < 600) {
      if (sticky.size) stickyDown();
      if (mode === "trackpad" && pro) scr({ op: "click", b: "l" });
      else { const p = frac(e.x, e.y); scr(pro ? { op: "click", b: "l", ...p } : { op: "click", btn: "l", ...p }); }
      stickyRelease();
      lastTap = { t: now, x: e.x, y: e.y };
    }
  }
  g = null;
}

// ---------- keyboard (desktop / hardware keys) ----------
const VK = { Backspace: 8, Tab: 9, Enter: 13, NumpadEnter: 13, ShiftLeft: 160, ShiftRight: 161, ControlLeft: 162, ControlRight: 163,
  AltLeft: 164, AltRight: 165, Pause: 19, CapsLock: 20, Escape: 27, Space: 32, PageUp: 33, PageDown: 34, End: 35,
  Home: 36, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, PrintScreen: 44, Insert: 45, Delete: 46,
  MetaLeft: 91, MetaRight: 92, OSLeft: 91, OSRight: 92, ContextMenu: 93, NumLock: 144, ScrollLock: 145,
  Semicolon: 186, Equal: 187, Comma: 188, Minus: 189, Period: 190, Slash: 191, Backquote: 192,
  BracketLeft: 219, Backslash: 220, BracketRight: 221, Quote: 222, IntlBackslash: 226,
  NumpadMultiply: 106, NumpadAdd: 107, NumpadSubtract: 109, NumpadDecimal: 110, NumpadDivide: 111,
  AudioVolumeMute: 173, AudioVolumeDown: 174, AudioVolumeUp: 175, MediaTrackNext: 176, MediaTrackPrevious: 177, MediaPlayPause: 179 };
for (let i = 0; i < 10; i++) { VK["Digit" + i] = 48 + i; VK["Numpad" + i] = 96 + i; }
for (let i = 0; i < 26; i++) VK["Key" + String.fromCharCode(65 + i)] = 65 + i;
for (let i = 1; i <= 24; i++) VK["F" + i] = 111 + i;
const held = new Set();
let pasteWait = null;
function ourField(t) { return t && (t.closest && t.closest(".clip, .bar, .pop")) && /INPUT|TEXTAREA|SELECT/.test(t.tagName); }
addEventListener("keydown", (e) => {
  if (ourField(e.target) || e.target === kbd) return;
  if (!gotFrame) return;
  const vk = VK[e.code];
  if (!vk) return;
  if (!pro) { e.preventDefault(); return basicKeyEvent(e); }
  // Ctrl+V: let the browser fire "paste" so the local clipboard reaches the host first (works on http too)
  if (vk === 86 && (e.ctrlKey || e.metaKey) && !e.altKey) {
    clearTimeout(pasteWait);
    pasteWait = setTimeout(() => { pasteWait = null; scr({ op: "kd", vk: 86 }); scr({ op: "ku", vk: 86 }); }, 180);
    return;
  }
  e.preventDefault();
  held.add(vk);
  scr({ op: "kd", vk });
}, true);
addEventListener("keyup", (e) => {
  if (ourField(e.target) || e.target === kbd) return;
  const vk = VK[e.code];
  if (!vk || !pro) return;
  if (vk === 86 && pasteWait) return;
  e.preventDefault();
  if (vk === 44) { scr({ op: "kd", vk: 44 }); scr({ op: "ku", vk: 44 }); return; }   // PrtSc: browsers only report keyup
  if (held.delete(vk)) scr({ op: "ku", vk });
}, true);
addEventListener("paste", (e) => {
  if (ourField(e.target)) return;
  const t = e.clipboardData && e.clipboardData.getData("text/plain");
  if (pasteWait == null) return;
  clearTimeout(pasteWait); pasteWait = null;
  e.preventDefault();
  if (t) scr({ op: "clip", text: t });
  setTimeout(() => { scr({ op: "kd", vk: 86 }); scr({ op: "ku", vk: 86 }); }, t ? 140 : 0);
});
function releaseAll() {
  if (held.size || sticky.size) scr({ op: "release" });
  held.clear(); sticky.clear(); renderSticky();
}
addEventListener("blur", releaseAll);

// ---------- phone keyboard (hidden textarea, diffed so IME / autocorrect work) ----------
const kbd = $("#kbd");
const SENT = "  ";                                  // sentinel so Backspace always has something to delete
let kbdPrev = SENT, composing = false;
function kbdReset() { kbd.value = SENT; kbdPrev = SENT; try { kbd.setSelectionRange(SENT.length, SENT.length); } catch {} }
function openKbd() {
  kbdReset();
  kbd.focus({ preventScroll: true });
  $("#b-kbd").classList.add("on");
}
kbd.addEventListener("blur", () => $("#b-kbd").classList.remove("on"));
kbd.addEventListener("compositionstart", () => { composing = true; });
kbd.addEventListener("compositionend", () => { composing = false; setTimeout(() => { if (!composing && kbd.value.length > 40) kbdReset(); }, 0); });
kbd.addEventListener("input", () => {
  const v = kbd.value, p0 = kbdPrev;
  let i = 0; while (i < v.length && i < p0.length && v[i] === p0[i]) i++;
  const removed = p0.length - i, added = v.slice(i);
  for (let k = 0; k < removed; k++) tapKey(8);
  if (added) {
    const parts = added.split("\n");
    parts.forEach((part, j) => { if (part) typeText(part); if (j < parts.length - 1) tapKey(13); });
  }
  kbdPrev = v;
  if (!composing && (v.length < SENT.length || v.length > 40 || v.endsWith("\n"))) kbdReset();
});
kbd.addEventListener("keydown", (e) => {
  const map = { Enter: 13, Tab: 9, Escape: 27, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, Delete: 46, Home: 36, End: 35, PageUp: 33, PageDown: 34 };
  const vk = map[e.key];
  if (vk && !composing) { e.preventDefault(); tapKey(vk); return; }
  // hardware keyboard on a tablet: ctrl/alt combos go straight through
  if ((e.ctrlKey || e.altKey || e.metaKey) && VK[e.code] && pro) {
    e.preventDefault();
    const mods = []; if (e.ctrlKey) mods.push(162); if (e.altKey) mods.push(164); if (e.shiftKey) mods.push(160); if (e.metaKey) mods.push(91);
    scr({ op: "combo", mods, vk: VK[e.code] });
  }
});
$("#b-kbd").onclick = () => {
  if (document.activeElement === kbd) { kbd.blur(); return; }
  if (TOUCH) openKbd();
  else { toast("Just type — keys go straight to the host. Fullscreen also captures Win / Alt+Tab."); stage.focus(); }
};

// ---------- keys panel ----------
$("#b-keys").onclick = () => togglePanel("#keys");
$("#b-clip").onclick = () => togglePanel("#clip");
function togglePanel(id) {
  const el = $(id), show = el.classList.contains("hidden");
  for (const p of document.querySelectorAll(".panel")) p.classList.add("hidden");
  el.classList.toggle("hidden", !show);
  $("#b-keys").classList.toggle("on", id === "#keys" && show);
  $("#b-clip").classList.toggle("on", id === "#clip" && show);
}
for (const b of document.querySelectorAll(".k.mod")) {
  let lt = 0;
  b.addEventListener("pointerdown", (e) => e.preventDefault());        // keep the phone keyboard open
  b.onclick = () => {
    const vk = +b.dataset.mod, st = sticky.get(vk), now = performance.now();
    if (!st) { sticky.set(vk, "on"); if (pro) scr({ op: "kd", vk }); }
    else if (st === "on" && now - lt < 400) sticky.set(vk, "lock");    // double-tap = lock
    else { sticky.delete(vk); if (pro) scr({ op: "ku", vk }); }
    lt = now; renderSticky();
  };
}
for (const b of document.querySelectorAll(".k[data-vk]")) {
  b.addEventListener("pointerdown", (e) => e.preventDefault());
  b.onclick = () => tapKey(+b.dataset.vk);
}
for (const b of document.querySelectorAll(".k[data-combo]")) {
  b.addEventListener("pointerdown", (e) => e.preventDefault());
  b.onclick = () => {
    const ks = b.dataset.combo.split(",").map(Number), vk = ks.pop();
    if (pro) scr({ op: "combo", mods: ks, vk });
    else basicCombo(ks, vk);
  };
}

// ---------- clipboard ----------
const clipHost = $("#clip-host"), clipLocal = $("#clip-local");
let hostClip = "";
function copyLocal(text) {
  if (navigator.clipboard && window.isSecureContext)
    return navigator.clipboard.writeText(text).then(() => true, () => execCopy(text));
  return Promise.resolve(execCopy(text));
}
function execCopy(text) {
  const ta = document.createElement("textarea");
  ta.value = text; ta.style.cssText = "position:fixed;left:-9999px;top:0";
  document.body.append(ta); ta.select();
  let ok = false; try { ok = document.execCommand("copy"); } catch {}
  ta.remove(); return ok;
}
function onHostClip(t) {
  hostClip = t || ""; clipHost.value = hostClip;
  if (navigator.clipboard && window.isSecureContext && document.hasFocus())
    navigator.clipboard.writeText(hostClip).then(() => toast("Host clipboard copied to this device", null, null, 1800), () => offerCopy());
  else offerCopy();
}
function offerCopy() {
  const s = hostClip.length > 40 ? hostClip.slice(0, 40) + "…" : hostClip;
  toast("Host copied: " + s.replace(/\s+/g, " "), "Copy", () => copyLocal(hostClip).then((ok) => toast(ok ? "Copied" : "Copy blocked — use the Clipboard panel")), 6000);
}
$("#clip-copy").onclick = () => copyLocal(clipHost.value).then((ok) => toast(ok ? "Copied to this device" : "Copy blocked — select the text and copy manually"));
$("#clip-set").onclick = () => { if (clipLocal.value) { scr({ op: "clip", text: clipLocal.value }); toast("Host clipboard set — press Ctrl+V on the host"); } };
$("#clip-type").onclick = () => { if (clipLocal.value) { scr({ op: "type", text: clipLocal.value }); toast("Typed on host"); } };

// ---------- toolbar ----------
// opens/closes only on demand (tab / hide button) — it never pops up by itself,
// so it can't cover the host's own top edge (tabs, title bars) while you work
function showBar() { bar.classList.remove("off"); }
function hideBar() { bar.classList.add("off"); hidePops(); stage.focus({ preventScroll: true }); }
$("#bar-tab").onclick = showBar;
$("#b-hide").onclick = hideBar;

const selQ = $("#s-q");
selQ.value = quality;
selQ.onchange = () => { quality = selQ.value; LS.set("q", quality); reconfig(); stage.focus(); };
const selMon = $("#s-mon");
function renderMons(cur) {
  if (mons.length < 2) { selMon.classList.add("hidden"); return; }
  selMon.classList.remove("hidden");
  selMon.innerHTML = mons.map((m, i) => `<option value="${i}">Screen ${i + 1}${m.primary ? " ★" : ""} · ${m.w}×${m.h}</option>`).join("") + `<option value="-1">All screens</option>`;
  const prim = mons.findIndex((m) => m.primary);
  selMon.value = String(cur === -2 ? prim : cur);
}
selMon.onchange = () => { monSel = +selMon.value; LS.set("mon", monSel); scr({ op: "mon", i: monSel }); view.z = 1; stage.focus(); };

function applyMode() {
  if (!pro) mode = "direct";
  $("#b-mode-l").textContent = mode === "trackpad" ? "Trackpad" : "Touch";
  $("#b-mode").classList.toggle("on", mode === "trackpad");
  placeCursor();
}
$("#b-mode").onclick = () => {
  if (!pro) return toast("Trackpad mode needs the native agent");
  mode = mode === "trackpad" ? "direct" : "trackpad"; LS.set("mode", mode); applyMode();
  toast(mode === "trackpad" ? "Trackpad: drag to move the pointer, tap to click" : "Touch: tap where you want to click", null, null, 2200);
};
applyMode();

const fsOk = !!(document.documentElement.requestFullscreen) && !IN_APP;
$("#b-fs").classList.toggle("hidden", !fsOk);
$("#b-fs").onclick = async () => {
  if (document.fullscreenElement) { await document.exitFullscreen().catch(() => {}); return; }
  await document.documentElement.requestFullscreen({ navigationUI: "hide" }).catch(() => {});
  // Keyboard Lock (Chromium): Win, Alt+Tab, Esc reach the host while fullscreen
  try { if (navigator.keyboard && navigator.keyboard.lock) await navigator.keyboard.lock(); } catch {}
};
document.addEventListener("fullscreenchange", () => {
  $("#b-fs").classList.toggle("on", !!document.fullscreenElement);
  if (!document.fullscreenElement) try { navigator.keyboard && navigator.keyboard.unlock && navigator.keyboard.unlock(); } catch {}
  setTimeout(layout, 50);
});

// more menu
let blockOn = false;
const more = $("#more");
$("#b-more").onclick = (e) => {
  e.stopPropagation();
  if (!more.classList.contains("hidden")) return hidePops();
  more.classList.remove("hidden"); showBar();
};
function hidePops() { more.classList.add("hidden"); }
document.addEventListener("pointerdown", (e) => { if (!e.target.closest("#more, #b-more")) hidePops(); });
more.onclick = (e) => {
  e.stopPropagation();
  const b = e.target.closest("button"); if (!b) return;
  hidePops();
  switch (b.dataset.act) {
    case "block": if (!pro) return toast("Needs the native agent"); scr({ op: "block", on: !blockOn }); break;
    case "refresh": if (pro) scr({ op: "refresh" }); else scr({ op: "shot", w: qParams().w, q: 55 }); break;
    case "taskmgr": pro ? scr({ op: "combo", mods: [162, 160], vk: 27 }) : scr({ op: "key", k: "^+{ESC}" }); break;
    case "lock": if (pro) scr({ op: "combo", mods: [91], vk: 76 }); break;
    case "home": goHome(); break;
  }
};

function goHome() {
  releaseAll(); unsubscribe();
  if (blockOn) scr({ op: "block", on: false });
  // opened as its own tab → close it; opened in place (Android app) → go back
  if (!IN_APP && (window.opener || history.length <= 1)) { window.close(); setTimeout(() => { location.href = "/"; }, 150); }
  else if (history.length > 1) history.back();
  else location.href = "/";
}
$("#b-close").onclick = goHome;

// ---------- basic agent key mapping (SendKeys) ----------
const SK = { 8: "{BS}", 9: "{TAB}", 13: "{ENTER}", 27: "{ESC}", 33: "{PGUP}", 34: "{PGDN}", 35: "{END}", 36: "{HOME}", 37: "{LEFT}", 38: "{UP}", 39: "{RIGHT}", 40: "{DOWN}", 45: "{INS}", 46: "{DEL}", 44: "{PRTSC}" };
for (let i = 1; i <= 12; i++) SK[111 + i] = `{F${i}}`;
function basicKey(vk) { const k = SK[vk]; if (k) scr({ op: "key", k }); }
function basicCombo(mods, vk) {
  const pre = mods.map((m) => (m === 162 ? "^" : m === 164 ? "%" : m === 160 ? "+" : "")).join("");
  const k = SK[vk] || String.fromCharCode(vk).toLowerCase();
  scr({ op: "key", k: pre + k });
}
function basicKeyEvent(e) {
  const vk = VK[e.code];
  if (e.ctrlKey || e.altKey) { const m = []; if (e.ctrlKey) m.push(162); if (e.altKey) m.push(164); if (e.shiftKey) m.push(160); return basicCombo(m, vk); }
  if (SK[vk]) return basicKey(vk);
  if (e.key.length === 1) scr({ op: "type", text: e.key });
}

// ---------- first-run help ----------
function maybeHelp() {
  if (LS.get("help", 0) >= 2) return;
  const h = $("#help");
  h.innerHTML = TOUCH
    ? `<h3>Remote control</h3><ul>
        <li><b>Trackpad mode</b>: drag one finger to move the pointer, tap to click</li>
        <li>Double-tap and hold (or hold) then drag to drag/select</li>
        <li>Two-finger tap = right click · two-finger drag = scroll</li>
        <li>Pinch to zoom · three-finger tap = keyboard</li>
        <li>Switch to <b>Touch</b> mode in the toolbar to tap exactly where you point</li>
        <li>The ⌄ tab at the top opens the toolbar</li></ul>
        <button class="btn">Got it</button>`
    : `<h3>Remote control</h3><ul>
        <li>Your mouse and keyboard go straight to the host</li>
        <li><b>Fullscreen</b> also captures Win, Alt+Tab and Esc (hold Esc to leave)</li>
        <li>Ctrl+V pastes this computer's clipboard on the host; host copies show up as a prompt</li>
        <li>The ⌄ tab at the top opens the toolbar, ⌃ hides it again</li></ul>
        <button class="btn">Got it</button>`;
  h.classList.remove("hidden");
  h.querySelector("button").onclick = () => { h.classList.add("hidden"); LS.set("help", 2); stage.focus(); };
}

// ---------- lifecycle ----------
stage.tabIndex = 0;
document.addEventListener("visibilitychange", () => {
  if (document.hidden) { releaseAll(); unsubscribe(); }
  else if (wsUp) { gotFrame = false; status("Resuming…"); subscribe(); wake(); }
});
let wakeLock = null;
async function wake() { try { if (navigator.wakeLock && !wakeLock) { wakeLock = await navigator.wakeLock.request("screen"); wakeLock.addEventListener("release", () => { wakeLock = null; }); } } catch {} }
// soft keyboard. Android app: the native layer shifts the whole page up by the
// keyboard height and reports it in window.__nativeKb — the visible band is then
// [kb, innerHeight], so the stage starts at --kbtop. Browsers: --kb from visualViewport.
Object.defineProperty(window, "__nativeKb", {
  configurable: true,
  get() { return nativeKb; },
  set(v) {
    nativeKb = Math.max(0, +v || 0);
    document.documentElement.style.setProperty("--kbtop", nativeKb + "px");
    document.documentElement.style.setProperty("--nkb", nativeKb + "px");   // rotated layout
    requestAnimationFrame(layout);
  },
});
if (window.visualViewport) {
  const vv = window.visualViewport;
  const onVV = () => {
    const kb = Math.max(0, innerHeight - vv.height - vv.offsetTop);
    vvKb = kb > 60 && !(nativeKb > 0) ? kb : 0;
    document.documentElement.style.setProperty("--kb", vvKb + "px");
    layout();
  };
  vv.addEventListener("resize", onVV); vv.addEventListener("scroll", onVV);
}
kbd.addEventListener("focus", () => setTimeout(layout, 350));
kbd.addEventListener("blur", () => setTimeout(layout, 350));

// ---------- rotate (forced, not sensor based) ----------
// Browsers: real landscape via Orientation Lock (needs fullscreen). Where that's
// unavailable (Android app WebView, iOS) the page itself is laid out landscape
// and turned 90° with CSS.
function setRotCss(on) {
  rotCss = on; LS.set("rot", on);
  const root = document.documentElement;
  root.style.setProperty("--vw", innerWidth + "px"); root.style.setProperty("--vh", innerHeight + "px");
  root.classList.toggle("rot", on);
  $("#b-rot").classList.toggle("on", on || rotLocked);
  view.z = 1; requestAnimationFrame(layout);
}
addEventListener("resize", () => {
  if (!rotCss) return;
  const root = document.documentElement;
  root.style.setProperty("--vw", innerWidth + "px"); root.style.setProperty("--vh", innerHeight + "px");
});
$("#b-rot").onclick = async () => {
  if (rotCss) return setRotCss(false);
  const lock = screen.orientation && screen.orientation.lock;
  if (lock && !IN_APP) {
    try {
      if (rotLocked) {
        await screen.orientation.lock("portrait");
        rotLocked = false; $("#b-rot").classList.remove("on"); return;
      }
      if (!document.fullscreenElement) await document.documentElement.requestFullscreen({ navigationUI: "hide" });
      await screen.orientation.lock("landscape");
      rotLocked = true; $("#b-rot").classList.add("on"); return;
    } catch {}
  }
  setRotCss(true);
};
document.addEventListener("fullscreenchange", () => {
  if (!document.fullscreenElement && rotLocked) { rotLocked = false; $("#b-rot").classList.remove("on"); }
});
if (TOUCH && LS.get("rot", false) && innerHeight > innerWidth) setRotCss(true);
addEventListener("beforeunload", () => { releaseAll(); unsubscribe(); });

fetch("/api/info").then((r) => r.json()).then((i) => {
  $("#host").textContent = i.hostname || "Remote screen";
  document.title = (i.hostname || "Host") + " · Remote screen";
}).catch(() => {});
layout();
connect();
wake();
stage.focus();
