// S-remote Screen Pro agent - push-mode remote desktop.
// stdin : JSONL commands
//   config {w,q,fps} | mon {i} (-1 = all screens) | refresh | credit {n}
//   move {x,y} | mrel {dx,dy} | down/up {b,x?,y?} | click {b,x?,y?,dbl}
//   wheel {dy,dx,x?,y?} | scroll {x,y,d} (legacy) | kd/ku {vk} | combo {mods,vk}
//   type {text} | key {k|vk} | clip {text} | block {on} | ping | quit
//   x,y are fractions (0..1) of the captured monitor; dx,dy are captured pixels.
// stdout: [u32le len][u8 type][payload]   len = 1 + payload length
//   'J' = JSON text  (ready / info / cur / shape / clip / err / pong)
//   'F' = tile frame [u16 outW][u16 outH][u16 tile][u16 count]
//                    then per rect: [u16 x][u16 y][u16 w][u16 h][u32 jlen][jpeg bytes]
// Capture: DXGI Desktop Duplication on the chosen monitor (event-driven), GDI
// BitBlt fallback / "all screens". Only changed 64px tiles are sent, merged into
// row runs; tiles sent at low quality are re-sent sharp once the screen settles.
// Flow control: each frame costs one credit; the server grants credits as
// viewers ack, so a slow link never queues stale frames (changes coalesce).
// The cursor is not drawn into frames - position/shape go out as JSON at 60 Hz.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using System.Runtime.InteropServices;

// ---------- DXGI / D3D11 via raw vtable delegates (no COM interop interfaces) ----------
[StructLayout(LayoutKind.Sequential)]
public struct DXGI_OUTDUPL_FRAME_INFO { public long LastPresentTime; public long LastMouseUpdateTime; public uint AccumulatedFrames; public int RectsCoalesced; public int ProtectedContentMaskedOut; public POINT2 PointerPosition; public int TotalMetadataBufferSize; public int PointerShapeBufferSize; }
[StructLayout(LayoutKind.Sequential)] public struct POINT2 { public int x; public int y; }
[StructLayout(LayoutKind.Sequential)]
public struct D3D11_TEXTURE2D_DESC { public uint Width, Height, MipLevels, ArraySize; public int Format; public uint SampleCount, SampleQuality; public uint Usage, BindFlags, CPUAccessFlags, MiscFlags; }
[StructLayout(LayoutKind.Sequential)] public struct D3D11_MAPPED_SUBRESOURCE { public IntPtr pData; public uint RowPitch; public uint DepthPitch; }

class Dxgi : IDisposable {
  [DllImport("d3d11.dll")] static extern int D3D11CreateDevice(IntPtr adapter, int dt, IntPtr sw, uint flags, IntPtr levels, int n, uint ver, out IntPtr dev, out int lvl, out IntPtr ctx);
  [DllImport("dxgi.dll")] static extern int CreateDXGIFactory1(ref Guid iid, out IntPtr f);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int QID(IntPtr s, ref Guid iid, out IntPtr o);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int EnumAdaptD(IntPtr s, uint i, out IntPtr o);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetDescD(IntPtr s, IntPtr d);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int DupOutD(IntPtr s, IntPtr dev, out IntPtr o);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int AcquireD(IntPtr s, uint ms, out DXGI_OUTDUPL_FRAME_INFO fi, out IntPtr res);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int SimpleD(IntPtr s);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int CreateTexD(IntPtr s, ref D3D11_TEXTURE2D_DESC d, IntPtr data, out IntPtr t);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int MapD(IntPtr s, IntPtr res, uint sub, int map, uint flags, out D3D11_MAPPED_SUBRESOURCE m);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int UnmapD(IntPtr s, IntPtr res, uint sub);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int CopyResD(IntPtr s, IntPtr dst, IntPtr src);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate void OutduplDescD(IntPtr s, IntPtr d); // DXGI_OUTDUPL_DESC

  static T M<T>(IntPtr obj, int slot) {
    IntPtr fn = Marshal.ReadIntPtr(Marshal.ReadIntPtr(obj), slot * IntPtr.Size);
    return (T)(object)Marshal.GetDelegateForFunctionPointer(fn, typeof(T));
  }
  static int Rel(IntPtr p) { return p == IntPtr.Zero ? 0 : M<SimpleD>(p, 2)(p); }

  IntPtr dev, ctx, dup, staging;
  public int W, H, Fmt;
  public Rectangle Bounds;   // desktop coords of the duplicated output

  // deviceName = \\.\DISPLAYn of the wanted monitor, null = primary
  public Dxgi(string deviceName) {
    Guid f1 = new Guid("770aae78-f26f-4dba-a829-253c83d1b387");
    IntPtr fp; int hr = CreateDXGIFactory1(ref f1, out fp);
    if (hr != 0) throw new Exception("factory " + hr.ToString("X8"));
    IntPtr ap = IntPtr.Zero, o0 = IntPtr.Zero;
    IntPtr desc = Marshal.AllocHGlobal(256);
    try {
      for (uint ai = 0; o0 == IntPtr.Zero; ai++) {
        IntPtr a; if (M<EnumAdaptD>(fp, 12)(fp, ai, out a) != 0) break;            // IDXGIFactory1::EnumAdapters1
        for (uint oi = 0; ; oi++) {
          IntPtr o; if (M<EnumAdaptD>(a, 7)(a, oi, out o) != 0) break;              // IDXGIAdapter::EnumOutputs
          M<GetDescD>(o, 7)(o, desc);                                               // IDXGIOutput::GetDesc
          string name = Marshal.PtrToStringUni(desc, 32).TrimEnd('\0');
          int l = Marshal.ReadInt32(desc, 64), t = Marshal.ReadInt32(desc, 68);
          int r = Marshal.ReadInt32(desc, 72), b = Marshal.ReadInt32(desc, 76);
          bool attached = Marshal.ReadInt32(desc, 80) != 0;
          bool match = attached && (deviceName != null
            ? string.Equals(name, deviceName, StringComparison.OrdinalIgnoreCase)
            : (l == 0 && t == 0));
          if (match) { o0 = o; ap = a; Bounds = new Rectangle(l, t, r - l, b - t); break; }
          Rel(o);
        }
        if (o0 == IntPtr.Zero) Rel(a);
      }
    } finally { Marshal.FreeHGlobal(desc); Rel(fp); }
    if (o0 == IntPtr.Zero) throw new Exception("output not found");

    IntPtr d, c; int lvl;
    // device must live on the output's adapter (driver type UNKNOWN when an adapter is given)
    // 0x20 = D3D11_CREATE_DEVICE_BGRA_SUPPORT — required for BGRA dup surfaces
    hr = D3D11CreateDevice(ap, 0, IntPtr.Zero, 0x20, IntPtr.Zero, 0, 7, out d, out lvl, out c);
    Rel(ap);
    if (hr != 0) { Rel(o0); throw new Exception("D3D11CreateDevice " + hr.ToString("X8")); }
    dev = d; ctx = c;
    Guid o1iid = new Guid("00cddea8-939b-4b83-a340-a685226666cc"); // IID_IDXGIOutput1
    IntPtr o1; hr = M<QID>(o0, 0)(o0, ref o1iid, out o1);
    Rel(o0);
    if (hr != 0) throw new Exception("output1 " + hr.ToString("X8"));
    IntPtr dp; hr = M<DupOutD>(o1, 22)(o1, dev, out dp);           // IDXGIOutput1::DuplicateOutput
    Rel(o1);
    if (hr != 0) throw new Exception("dup " + hr.ToString("X8"));
    dup = dp;
    // DXGI_OUTDUPL_DESC: ModeDesc{W,H,refresh,format,...}@0 -> W@0 H@4 fmt@16
    IntPtr odd = Marshal.AllocHGlobal(64);
    M<OutduplDescD>(dup, 7)(dup, odd);
    W = Marshal.ReadInt32(odd); H = Marshal.ReadInt32(odd + 4); Fmt = Marshal.ReadInt32(odd + 16);
    Marshal.FreeHGlobal(odd);
    if (W <= 0 || H <= 0) throw new Exception("dup desc " + W + "x" + H);
    if (Fmt != 87) throw new Exception("dup fmt " + Fmt + " (need B8G8R8A8)");
    var sd = new D3D11_TEXTURE2D_DESC();
    sd.Width = (uint)W; sd.Height = (uint)H; sd.MipLevels = 1; sd.ArraySize = 1;
    sd.Format = 87; sd.SampleCount = 1; sd.Usage = 3; sd.BindFlags = 0;
    sd.CPUAccessFlags = 0x20000; sd.MiscFlags = 0;
    hr = M<CreateTexD>(dev, 5)(dev, ref sd, IntPtr.Zero, out staging);   // ID3D11Device::CreateTexture2D
    if (hr != 0) throw new Exception("staging " + hr.ToString("X8"));
  }
  // returns: true = fresh pixels in dst; false = nothing new (timeout or pointer-only update)
  public bool Acquire(uint timeoutMs, Bitmap dst) {
    DXGI_OUTDUPL_FRAME_INFO fi; IntPtr res;
    int hr = M<AcquireD>(dup, 8)(dup, timeoutMs, out fi, out res);       // AcquireNextFrame
    if (hr == unchecked((int)0x887A0027)) return false;                  // WAIT_TIMEOUT
    if (hr != 0) throw new Exception("acquire " + hr.ToString("X8"));    // e.g. ACCESS_LOST (UAC / lock / mode change)
    try {
      if (res == IntPtr.Zero || fi.AccumulatedFrames == 0) return false; // pointer/metadata only
      if (dst.Width != W || dst.Height != H) return true;                // caller re-allocs
      M<CopyResD>(ctx, 47)(ctx, staging, res);                           // CopyResource
      D3D11_MAPPED_SUBRESOURCE map;
      if (M<MapD>(ctx, 14)(ctx, staging, 0, 1, 0, out map) != 0) return false;
      var bd = dst.LockBits(new Rectangle(0, 0, W, H), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
      bool anyNz = false;
      unsafe {
        byte* s = (byte*)map.pData, d2 = (byte*)bd.Scan0;
        int rb = W * 4;
        for (int y = 0; y < H; y++) {
          Buffer.MemoryCopy(s + (long)y * map.RowPitch, d2 + (long)y * bd.Stride, rb, rb);
          if (!anyNz)
            for (int x = 0; x < rb; x += 4004)                       // 4-aligned: BGR bytes only, skip A
              if (s[y * map.RowPitch + x] != 0 || s[y * map.RowPitch + x + 1] != 0 || s[y * map.RowPitch + x + 2] != 0) { anyNz = true; break; }
        }
      }
      dst.UnlockBits(bd);
      M<UnmapD>(ctx, 15)(ctx, staging, 0);                               // Unmap
      // some drivers (hybrid GPUs, certain iGPU setups) duplicate an all-zero surface — use GDI then
      if (!anyNz) throw new Exception("dxgi frames all-black (driver)");
    } finally { M<SimpleD>(dup, 14)(dup); Rel(res); }                    // ReleaseFrame + release res
    return true;
  }
  public void Dispose() { try { Rel(staging); Rel(dup); Rel(ctx); Rel(dev); } catch { } }
}
// ---------- end DXGI ----------

class ScreenPro {
  [DllImport("user32.dll")] static extern bool GetCursorInfo(ref CURSORINFO pci);
  [DllImport("user32.dll")] static extern bool GetCursorPos(out POINT2 p);
  [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr hcur, int w, int h, int step, IntPtr brush, uint flags);
  [DllImport("user32.dll")] static extern bool GetIconInfo(IntPtr hIcon, ref ICONINFO ii);
  [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);
  [DllImport("user32.dll")] static extern uint SendInput(uint n, INPUT[] p, int cb);
  [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint type);
  [DllImport("user32.dll")] static extern bool BlockInput(bool f);
  [DllImport("user32.dll")] static extern uint GetClipboardSequenceNumber();
  [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  struct CURSORINFO { public int cbSize; public int flags; public IntPtr hCursor; public POINT2 ptScreenPos; }
  struct ICONINFO { public bool fIcon; public int xHotspot; public int yHotspot; public IntPtr hbmMask; public IntPtr hbmColor; }
  // SendInput structs — union layout works on x86+x64 (FieldOffset(0) handles pad)
  [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr ex; }
  [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT { public int dx, dy; public uint data, dwFlags, time; public IntPtr ex; }
  [StructLayout(LayoutKind.Explicit)] struct INPUT_U { [FieldOffset(0)] public KEYBDINPUT ki; [FieldOffset(0)] public MOUSEINPUT mi; }
  [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public INPUT_U u; }

  static readonly JavaScriptSerializer JS = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 };
  static Stream stdout;
  static readonly object wlock = new object();
  static volatile int outW = 1600, fps = 30, jpegQ = 70;
  static volatile bool running = true;
  // captured region (screen coords, physical px — the process is DPI aware)
  static volatile int capX, capY, capW = 1, capH = 1;
  static volatile int monSel = -2;          // -2 = primary (default), -1 = all screens, n = Screen.AllScreens[n]
  static volatile bool reinit = true, forceAll = true, resendShape = true;
  static int credit = 2;                    // frames we may still send (server grants more on viewer ack)
  static readonly AutoResetEvent creditEv = new AutoResetEvent(false);

  static void Send(byte type, byte[] payload) {
    byte[] head = BitConverter.GetBytes(payload.Length + 1);
    lock (wlock) { stdout.Write(head, 0, 4); stdout.WriteByte(type); stdout.Write(payload, 0, payload.Length); stdout.Flush(); }
  }
  static void J(object o) { Send((byte)'J', Encoding.UTF8.GetBytes(JS.Serialize(o))); }
  static Dictionary<string, object> D(params object[] kv) {
    var d = new Dictionary<string, object>();
    for (int i = 0; i + 1 < kv.Length; i += 2) d[(string)kv[i]] = kv[i + 1];
    return d;
  }
  static void InfoErr(string msg) { J(D("op", "err", "msg", msg)); }

  // ---------- mouse (SendInput, absolute over the virtual desktop) ----------
  static void SendMouse(uint flags, int dx, int dy, uint data) {
    var i = new INPUT(); i.type = 0;
    i.u.mi.dx = dx; i.u.mi.dy = dy; i.u.mi.data = data; i.u.mi.dwFlags = flags;
    SendInput(1, new[] { i }, Marshal.SizeOf(typeof(INPUT)));
  }
  // keep the pointer on the monitor being viewed — on a multi-monitor host a
  // trackpad drag must not wander onto a screen the viewer can't see
  static void ClampToCapture(ref int sx, ref int sy) {
    sx = Math.Max(capX, Math.Min(capX + capW - 1, sx)); sy = Math.Max(capY, Math.Min(capY + capH - 1, sy));
  }
  static void MoveTo(int sx, int sy) {
    ClampToCapture(ref sx, ref sy);
    // SetCursorPos takes exact physical pixels (process is per-monitor DPI aware);
    // SendInput's normalized VIRTUALDESK coords land off-target on mixed-DPI /
    // mixed-resolution monitor setups. SendInput only as a fallback.
    if (SetCursorPos(sx, sy)) {
      POINT2 p;
      if (GetCursorPos(out p) && Math.Abs(p.x - sx) <= 1 && Math.Abs(p.y - sy) <= 1) {
        SendMouse(0x0001, 0, 0, 0);                 // zero relative move: apps see a real mouse-move event
        return;
      }
    }
    var v = SystemInformation.VirtualScreen;
    int nx = (int)Math.Round((sx - v.Left) * 65535.0 / Math.Max(1, v.Width - 1));
    int ny = (int)Math.Round((sy - v.Top) * 65535.0 / Math.Max(1, v.Height - 1));
    SendMouse(0x0001 | 0x8000 | 0x4000, nx, ny, 0);   // MOVE | ABSOLUTE | VIRTUALDESK
  }
  static void MoveFrac(IDictionary<string, object> m) {
    if (!m.ContainsKey("x") || !m.ContainsKey("y") || m["x"] == null) return;   // no coords = at current cursor
    MoveTo(capX + (int)(Num(m, "x", 0) * capW), capY + (int)(Num(m, "y", 0) * capH));
  }
  static void Button(string b, bool down) {
    uint f, data = 0;
    if (b == "r") f = down ? 0x08u : 0x10u;
    else if (b == "m") f = down ? 0x20u : 0x40u;
    else if (b == "x1" || b == "x2") { f = down ? 0x80u : 0x100u; data = b == "x1" ? 1u : 2u; }
    else f = down ? 0x02u : 0x04u;
    SendMouse(f, 0, 0, data);
  }
  static string Btn(IDictionary<string, object> m) { return Str(m, "b", Str(m, "btn", "l")); }

  // ---------- keyboard (SendInput, vk + scan code so games / RDP-style apps see real keys) ----------
  static bool IsExt(int vk) {
    switch (vk) {
      case 33: case 34: case 35: case 36: case 37: case 38: case 39: case 40:   // pgup/dn end home arrows
      case 45: case 46:                                                        // ins del
      case 91: case 92: case 93:                                               // win menu
      case 111: case 144:                                                      // numpad / , numlock
      case 163: case 165:                                                      // rctrl ralt
        return true;
    }
    return false;
  }
  static readonly HashSet<int> heldVk = new HashSet<int>();
  static void KeyVk(int vk, bool up) {
    var i = new INPUT(); i.type = 1;
    i.u.ki.wVk = (ushort)vk;
    i.u.ki.wScan = (ushort)MapVirtualKey((uint)vk, 0);
    i.u.ki.dwFlags = (up ? 0x02u : 0) | (IsExt(vk) ? 0x01u : 0);               // KEYUP | EXTENDEDKEY
    SendInput(1, new[] { i }, Marshal.SizeOf(typeof(INPUT)));
    lock (heldVk) { if (up) heldVk.Remove(vk); else heldVk.Add(vk); }
  }
  static void ReleaseAllKeys() {
    int[] ks; lock (heldVk) { ks = new int[heldVk.Count]; heldVk.CopyTo(ks); }
    foreach (int vk in ks) try { KeyVk(vk, true); } catch { }
  }
  static void Combo(int[] mods, int vk) {
    foreach (int m2 in mods) KeyVk(m2, false);
    KeyVk(vk, false); KeyVk(vk, true);
    for (int i = mods.Length - 1; i >= 0; i--) KeyVk(mods[i], true);
  }
  static void SendText(string t) {
    // SendInput UNICODE — handles Vietnamese/emoji unlike SendKeys; \n → Enter
    var list = new List<INPUT>();
    foreach (char c in t.Replace("\r\n", "\n")) {
      if (c == '\n' || c == '\r') {
        var e1 = new INPUT(); e1.type = 1; e1.u.ki.wVk = 13;
        var e2 = new INPUT(); e2.type = 1; e2.u.ki.wVk = 13; e2.u.ki.dwFlags = 0x02;
        list.Add(e1); list.Add(e2); continue;
      }
      var dn = new INPUT(); dn.type = 1; dn.u.ki.wScan = (ushort)c; dn.u.ki.dwFlags = 0x04;
      var up = new INPUT(); up.type = 1; up.u.ki.wScan = (ushort)c; up.u.ki.dwFlags = 0x04 | 0x02;
      list.Add(dn); list.Add(up);
    }
    for (int o = 0; o < list.Count; o += 32) {
      int n = Math.Min(32, list.Count - o);
      SendInput((uint)n, list.GetRange(o, n).ToArray(), Marshal.SizeOf(typeof(INPUT)));
      Thread.Sleep(1);
    }
  }
  // ---------- clipboard sync (STA thread) ----------
  static readonly object clipLock = new object();
  static string clipPending;                          // remote -> host set request
  static void ClipThread() {
    uint lastSeq = GetClipboardSequenceNumber();
    string lastSent = null;
    while (running) {
      string set; lock (clipLock) { set = clipPending; clipPending = null; }
      if (set != null) {
        try { Clipboard.SetText(set); lastSent = set; lastSeq = GetClipboardSequenceNumber(); } catch { }
      }
      uint seq = GetClipboardSequenceNumber();
      if (seq != lastSeq) {
        lastSeq = seq;
        try {
          if (Clipboard.ContainsText()) {
            string t = Clipboard.GetText();
            if (t != null && t != lastSent && t.Length <= 262144) J(D("op", "clip", "text", t));
          }
        } catch { }
      }
      Thread.Sleep(300);
    }
  }
  static bool blocked;
  static void SetBlock(bool on) {
    bool ok = false;
    try { ok = BlockInput(on); } catch { }
    blocked = on && ok;
    J(D("op", "block", "on", blocked, "ok", ok));
  }

  // ---------- cursor: position + shape as JSON (viewer draws it locally) ----------
  static string ShapePng(IntPtr hc, out int w, out int h, out int hx, out int hy) {
    w = h = hx = hy = 0;
    var ii = new ICONINFO();
    if (!GetIconInfo(hc, ref ii)) return null;
    try {
      hx = ii.xHotspot; hy = ii.yHotspot;
      using (var mask = Image.FromHbitmap(ii.hbmMask)) {
        w = mask.Width; h = ii.hbmColor == IntPtr.Zero ? mask.Height / 2 : mask.Height;
      }
      if (w <= 0 || h <= 0 || w > 256 || h > 256) return null;
      // draw on black and on white; the difference gives alpha (handles mono + inverting cursors)
      using (var bk = new Bitmap(w, h, PixelFormat.Format32bppArgb))
      using (var wh = new Bitmap(w, h, PixelFormat.Format32bppArgb))
      using (var outp = new Bitmap(w, h, PixelFormat.Format32bppArgb)) {
        foreach (var pair in new[] { new { b = bk, c = Color.Black }, new { b = wh, c = Color.White } })
          using (var g = Graphics.FromImage(pair.b)) {
            g.Clear(pair.c);
            IntPtr hdc = g.GetHdc();
            DrawIconEx(hdc, 0, 0, hc, w, h, 0, IntPtr.Zero, 3);       // DI_NORMAL
            g.ReleaseHdc(hdc);
          }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
          Color a = bk.GetPixel(x, y), b = wh.GetPixel(x, y);
          int db = (b.R - a.R + b.G - a.G + b.B - a.B) / 3;           // 255 = transparent, 0 = opaque
          Color o;
          if (db < -32) o = Color.FromArgb(255, 0, 0, 0);              // inverting pixel (I-beam): draw black
          else {
            int al = Math.Max(0, Math.Min(255, 255 - db));
            o = al == 0 ? Color.FromArgb(0, 0, 0, 0)
              : Color.FromArgb(al, Math.Min(255, a.R * 255 / al), Math.Min(255, a.G * 255 / al), Math.Min(255, a.B * 255 / al));
          }
          outp.SetPixel(x, y, o);
        }
        using (var ms = new MemoryStream()) { outp.Save(ms, ImageFormat.Png); return Convert.ToBase64String(ms.ToArray()); }
      }
    } catch { return null; }
    finally { if (ii.hbmMask != IntPtr.Zero) DeleteObject(ii.hbmMask); if (ii.hbmColor != IntPtr.Zero) DeleteObject(ii.hbmColor); }
  }
  static void CursorThread() {
    IntPtr lastShape = IntPtr.Zero; int lx = int.MinValue, ly = 0; bool lvis = false;
    var shapeIds = new Dictionary<IntPtr, int>(); int nextId = 1;
    while (running) {
      try {
        var ci = new CURSORINFO(); ci.cbSize = Marshal.SizeOf(typeof(CURSORINFO));
        if (GetCursorInfo(ref ci)) {
          bool vis = ci.flags == 1 && ci.hCursor != IntPtr.Zero;
          if (vis && (ci.hCursor != lastShape || resendShape)) {
            resendShape = false; lastShape = ci.hCursor;
            int id;
            if (!shapeIds.TryGetValue(ci.hCursor, out id)) { id = nextId++; shapeIds[ci.hCursor] = id; }
            int w, h, hx, hy; string png = ShapePng(ci.hCursor, out w, out h, out hx, out hy);
            if (png != null) J(D("op", "shape", "id", id, "png", png, "w", w, "h", h, "hx", hx, "hy", hy));
            lx = int.MinValue;   // force a cur after a shape change
          }
          int px = ci.ptScreenPos.x, py = ci.ptScreenPos.y;
          if (capW > 1 && (px != lx || py != ly || vis != lvis)) {
            lx = px; ly = py; lvis = vis;
            int sid; shapeIds.TryGetValue(lastShape, out sid);
            J(D("op", "cur", "x", Math.Round((px - capX) / (double)capW, 5), "y", Math.Round((py - capY) / (double)capH, 5), "vis", vis, "id", sid));
          }
        }
      } catch { }
      Thread.Sleep(16);
    }
  }

  static double Num(IDictionary<string, object> m, string k, double d) { return m.ContainsKey(k) && m[k] != null ? Convert.ToDouble(m[k]) : d; }
  static string Str(IDictionary<string, object> m, string k, string d) { return m.ContainsKey(k) && m[k] != null ? Convert.ToString(m[k]) : d; }
  static bool Bool(IDictionary<string, object> m, string k) { return m.ContainsKey(k) && m[k] is bool && (bool)m[k]; }

  static void Reader() {
    string line;
    while ((line = Console.In.ReadLine()) != null) {
      try {
        var m = JS.Deserialize<Dictionary<string, object>>(line);
        switch (Str(m, "op", "")) {
          case "config":
            outW = (int)Num(m, "w", outW); fps = (int)Num(m, "fps", fps); jpegQ = (int)Num(m, "q", jpegQ);
            if (outW < 320) outW = 320; if (outW > 7680) outW = 7680;
            if (fps < 1) fps = 1; if (fps > 60) fps = 60;
            if (jpegQ < 10) jpegQ = 10; if (jpegQ > 95) jpegQ = 95;
            forceAll = true;
            break;
          case "mon": monSel = (int)Num(m, "i", -2); reinit = true; break;
          case "refresh": forceAll = true; resendShape = true; SendInfo(null); break;
          case "credit": lock (creditEv) credit = Math.Min(8, credit + (int)Num(m, "n", 1)); creditEv.Set(); break;
          case "move": MoveFrac(m); break;
          case "mrel": {
            POINT2 p; GetCursorPos(out p);
            MoveTo(p.x + (int)Math.Round(Num(m, "dx", 0)), p.y + (int)Math.Round(Num(m, "dy", 0)));
            break;
          }
          case "down": MoveFrac(m); Button(Btn(m), true); break;
          case "up": MoveFrac(m); Button(Btn(m), false); break;
          case "click": {
            MoveFrac(m); string b = Btn(m); int n = Bool(m, "dbl") ? 2 : 1;
            for (int i = 0; i < n; i++) { Button(b, true); Button(b, false); }
            break;
          }
          case "wheel": {
            MoveFrac(m);
            int dy = (int)Num(m, "dy", 0), dx = (int)Num(m, "dx", 0);
            if (dy != 0) SendMouse(0x0800, 0, 0, (uint)dy);   // WHEEL (+ = away from user)
            if (dx != 0) SendMouse(0x1000, 0, 0, (uint)dx);   // HWHEEL (+ = right)
            break;
          }
          case "scroll": MoveFrac(m); SendMouse(0x0800, 0, 0, (uint)(int)Num(m, "d", 0)); break;
          case "type": SendText(Str(m, "text", "")); break;
          case "key":
            if (m.ContainsKey("vk")) { int vk = (int)Num(m, "vk", 0); if (vk > 0) { KeyVk(vk, false); KeyVk(vk, true); } }
            else SendKeys.SendWait(Str(m, "k", ""));
            break;
          case "kd": { int vk = (int)Num(m, "vk", 0); if (vk > 0) KeyVk(vk, false); break; }
          case "ku": { int vk = (int)Num(m, "vk", 0); if (vk > 0) KeyVk(vk, true); break; }
          case "release": ReleaseAllKeys(); break;
          case "combo": {
            var mods = new List<int>();
            if (m.ContainsKey("mods") && m["mods"] is System.Collections.IEnumerable)
              foreach (object o in (System.Collections.IEnumerable)m["mods"]) mods.Add(Convert.ToInt32(o));
            Combo(mods.ToArray(), (int)Num(m, "vk", 0)); break;
          }
          case "clip": lock (clipLock) clipPending = Str(m, "text", ""); break;
          case "block": SetBlock(Bool(m, "on")); break;
          case "ping": J(D("op", "pong")); break;
          case "quit": running = false; creditEv.Set(); return;
        }
      } catch (Exception e) { InfoErr("cmd: " + e.Message); }
    }
    running = false; creditEv.Set();
  }

  static string capSrc = "bitblt";
  static void SendInfo(string src) {
    if (src != null) capSrc = src;
    var mons = new List<object>();
    var all = Screen.AllScreens;
    for (int i = 0; i < all.Length; i++) {
      var b = all[i].Bounds;
      mons.Add(D("i", i, "x", b.X, "y", b.Y, "w", b.Width, "h", b.Height, "primary", all[i].Primary, "name", all[i].DeviceName));
    }
    J(D("op", "info", "w", capW, "h", capH, "src", capSrc, "mon", monSel, "mons", mons));
  }

  static ImageCodecInfo JpegCodec() {
    foreach (var c in ImageCodecInfo.GetImageEncoders()) if (c.MimeType == "image/jpeg") return c;
    return null;
  }
  static EncoderParameters QParam(int q) {
    var ep = new EncoderParameters(1);
    ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)q);
    return ep;
  }

  static readonly HashSet<int> dxBlack = new HashSet<int>();   // monitors where dxgi gave black frames
  static Dxgi OpenBitblt() {
    var all = Screen.AllScreens; Screen scr = Screen.PrimaryScreen;
    if (monSel >= 0 && monSel < all.Length) scr = all[monSel];
    var b = monSel == -1 ? SystemInformation.VirtualScreen : scr.Bounds;
    capX = b.X; capY = b.Y; capW = b.Width; capH = b.Height;
    SendInfo("bitblt"); return null;
  }
  // (re)open capture for monSel: dxgi on one monitor, bitblt for "all screens" or on failure
  static Dxgi OpenCapture() {
    var all = Screen.AllScreens;
    int sel = monSel;
    if (sel >= all.Length) sel = -2;
    if (sel == -1) {
      var v = SystemInformation.VirtualScreen; capX = v.Left; capY = v.Top; capW = v.Width; capH = v.Height;
      SendInfo("bitblt"); return null;
    }
    Screen scr = Screen.PrimaryScreen;
    if (sel >= 0) scr = all[sel];
    try {
      var dx = new Dxgi(sel >= 0 ? scr.DeviceName : null);
      capX = dx.Bounds.X; capY = dx.Bounds.Y; capW = dx.W; capH = dx.H;
      SendInfo("dxgi"); return dx;
    } catch (Exception e) {
      var b = scr.Bounds; capX = b.X; capY = b.Y; capW = b.Width; capH = b.Height;
      InfoErr("dxgi off: " + e.Message);
      SendInfo("bitblt"); return null;
    }
  }

  static void CaptureLoop() {
    var jpg = JpegCodec();
    const int TS = 64, HQ = 90;
    Dxgi dx = null; long dxRetryAt = 0;
    int curW = 0, curH = 0;
    Bitmap outBmp = null, fullBmp = null;
    byte[] aBuf = null; uint[] aHash = null, bHash = null; bool[] lq = null;
    long lastKey = 0, lastChange = Environment.TickCount; bool anyLq = false, haveImg = false;

    while (running) {
      try {
        if (reinit) {
          reinit = false; dxRetryAt = 0;
          if (dx != null) { dx.Dispose(); dx = null; }
          dx = dxBlack.Contains(monSel) ? OpenBitblt() : OpenCapture(); forceAll = true; resendShape = true;
        } else if (dx == null && monSel != -1 && dxRetryAt != 0 && Environment.TickCount > dxRetryAt) {
          dxRetryAt = 0; dx = OpenCapture(); forceAll = true;          // dxgi back after UAC / lock screen?
          if (dx == null) dxRetryAt = Environment.TickCount + 3000;
        }
        // flow control: wait for a credit, DXGI keeps accumulating changes meanwhile
        bool haveCredit; lock (creditEv) haveCredit = credit > 0;
        if (!haveCredit) { creditEv.WaitOne(100); continue; }

        int interval = 1000 / Math.Max(1, fps);
        long t0 = Environment.TickCount;
        bool sizeChanged = (outBmp == null || fullBmp == null || fullBmp.Width != capW || fullBmp.Height != capH
                            || curW != Math.Min(outW, capW));
        if (fullBmp == null || fullBmp.Width != capW || fullBmp.Height != capH) {
          if (fullBmp != null) fullBmp.Dispose();
          fullBmp = new Bitmap(capW, capH, PixelFormat.Format32bppArgb);
          haveImg = false;
        }
        bool got;
        if (dx != null) {
          // event-driven: blocks until something changes (or short timeout so HQ refinement can run)
          got = dx.Acquire((uint)(!haveImg ? 200 : sizeChanged || forceAll ? 0 : (anyLq ? 120 : 250)), fullBmp);
          if (got) haveImg = true;
          if (!haveImg) continue;                                    // nothing captured yet — never send a blank frame
          if (sizeChanged || forceAll) got = true;
        } else {
          using (var g = Graphics.FromImage(fullBmp))
            g.CopyFromScreen(capX, capY, 0, 0, new Size(capW, capH));
          got = true; haveImg = true;
        }
        bool keyDue = (Environment.TickCount - lastKey) > 20000;

        if (sizeChanged) {
          curW = Math.Min(outW, capW); curH = Math.Max(1, (int)Math.Round(capH * (double)curW / capW));
          if (outBmp != null) outBmp.Dispose();
          outBmp = new Bitmap(curW, curH, PixelFormat.Format32bppArgb);
          int tiles = ((curW + TS - 1) / TS) * ((curH + TS - 1) / TS);
          aHash = new uint[tiles]; bHash = new uint[tiles]; lq = new bool[tiles];
          aBuf = new byte[curW * curH * 4];
          forceAll = true; anyLq = false;
        }

        int colsT = (curW + TS - 1) / TS, rowsT = (curH + TS - 1) / TS;
        bool[] dirty = new bool[colsT * rowsT]; int nDirty = 0; int q = jpegQ;
        bool all = forceAll || keyDue, computed = got || all;
        if (computed) {
          using (var g = Graphics.FromImage(outBmp)) {
            g.InterpolationMode = curW == capW ? System.Drawing.Drawing2D.InterpolationMode.NearestNeighbor
                                               : System.Drawing.Drawing2D.InterpolationMode.HighQualityBilinear;
            g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.Half;
            g.DrawImage(fullBmp, 0, 0, curW, curH);
          }
          var bd = outBmp.LockBits(new Rectangle(0, 0, curW, curH), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
          Marshal.Copy(bd.Scan0, aBuf, 0, curW * curH * 4);
          outBmp.UnlockBits(bd);
          for (int ty = 0; ty < rowsT; ty++) for (int tx = 0; tx < colsT; tx++) {
              int w = Math.Min(TS, curW - tx * TS), h = Math.Min(TS, curH - ty * TS);
              uint h1 = 2166136261u;
              for (int y = 0; y < h; y++) {
                int row = ((ty * TS + y) * curW + tx * TS) * 4;
                for (int x = 0; x < w * 4; x += 4) { h1 = (h1 ^ aBuf[row + x]) * 16777619; h1 = (h1 ^ aBuf[row + x + 1]) * 16777619; h1 = (h1 ^ aBuf[row + x + 2]) * 16777619; }
              }
              int idx = ty * colsT + tx; aHash[idx] = h1;
              if (all || h1 != bHash[idx]) { dirty[idx] = true; nDirty++; }
            }
          if (all) q = Math.Max(q, 80);
        }
        if (nDirty > 0) lastChange = Environment.TickCount;
        else if (anyLq && Environment.TickCount - lastChange > 350) {
          // screen settled: re-send the blurry tiles sharp
          for (int i = 0; i < lq.Length; i++) if (lq[i]) { dirty[i] = true; nDirty++; }
          q = HQ;
        }
        if (nDirty == 0) {
          if (computed) { var th0 = aHash; aHash = bHash; bHash = th0; }
          // gdi polling: drop to ~10 fps while the screen sits still (saves CPU)
          int idleIv = Environment.TickCount - lastChange > 1000 ? Math.Max(interval, 100) : interval;
          if (dx == null) Thread.Sleep(Math.Max(5, idleIv - (int)(Environment.TickCount - t0)));
          continue;
        }
        forceAll = false;
        if (keyDue) lastKey = Environment.TickCount;

        var ep = QParam(q);
        using (var ms = new MemoryStream()) {
          var bw = new BinaryWriter(ms);
          bw.Write((ushort)curW); bw.Write((ushort)curH); bw.Write((ushort)TS); bw.Write((ushort)0);
          int count = 0;
          for (int ty = 0; ty < rowsT; ty++) {
            for (int tx = 0; tx < colsT; ) {
              if (!dirty[ty * colsT + tx]) { tx++; continue; }
              int t1 = tx; while (t1 < colsT && dirty[ty * colsT + t1]) t1++;   // merge a run of dirty tiles
              int x = tx * TS, y = ty * TS, w = Math.Min(t1 * TS, curW) - x, h = Math.Min(TS, curH - y);
              using (var tile = outBmp.Clone(new Rectangle(x, y, w, h), PixelFormat.Format24bppRgb))
              using (var tms = new MemoryStream()) {
                tile.Save(tms, jpg, ep); var tb = tms.GetBuffer(); int tl = (int)tms.Length;
                bw.Write((ushort)x); bw.Write((ushort)y); bw.Write((ushort)w); bw.Write((ushort)h); bw.Write((uint)tl); bw.Write(tb, 0, tl);
              }
              count++;
              for (int k = tx; k < t1; k++) lq[ty * colsT + k] = q < 85;
              tx = t1;
            }
          }
          bw.Flush();
          byte[] payload = ms.ToArray();
          payload[6] = (byte)(count & 0xff); payload[7] = (byte)(count >> 8);
          lock (creditEv) credit--;
          Send((byte)'F', payload);
        }
        anyLq = false; foreach (bool b in lq) if (b) { anyLq = true; break; }
        if (computed) { var th = aHash; aHash = bHash; bHash = th; }
        if (dx == null) Thread.Sleep(Math.Max(5, interval - (int)(Environment.TickCount - t0)));
        else { int rest = interval - (int)(Environment.TickCount - t0); if (rest > 0) Thread.Sleep(rest); }
      } catch (Exception e) {
        InfoErr("cap: " + e.Message);
        // ACCESS_LOST (UAC prompt, lock screen, resolution change): bitblt meanwhile, retry dxgi soon
        if (dx != null) {
          try { dx.Dispose(); } catch { }
          dx = null;
          haveImg = false;
          if (e.Message.Contains("all-black")) { dxBlack.Add(monSel); dxRetryAt = 0; SendInfo("bitblt"); }
          else dxRetryAt = Environment.TickCount + 1000;
          var scr = Screen.PrimaryScreen; var all2 = Screen.AllScreens;
          if (monSel >= 0 && monSel < all2.Length) scr = all2[monSel];
          capX = scr.Bounds.X; capY = scr.Bounds.Y; capW = scr.Bounds.Width; capH = scr.Bounds.Height;
          forceAll = true;
        }
        Thread.Sleep(300);
      }
    }
    if (dx != null) dx.Dispose();
    if (outBmp != null) outBmp.Dispose();
    if (fullBmp != null) fullBmp.Dispose();
  }

  static int Main() {
    // physical pixels everywhere — otherwise scaled (125%/150%) displays get offset clicks + cropped captures
    try { if (!SetProcessDpiAwarenessContext(new IntPtr(-4))) SetProcessDPIAware(); }   // PER_MONITOR_AWARE_V2
    catch { try { SetProcessDPIAware(); } catch { } }
    stdout = Console.OpenStandardOutput();
    AppDomain.CurrentDomain.ProcessExit += (s, e) => { ReleaseAllKeys(); try { if (blocked) BlockInput(false); } catch { } };
    J(D("op", "ready", "v", 2));
    var clip = new Thread(ClipThread); clip.IsBackground = true;
    clip.SetApartmentState(ApartmentState.STA); clip.Start();           // Clipboard needs STA
    var cap = new Thread(CaptureLoop); cap.IsBackground = true; cap.Start();
    var cur = new Thread(CursorThread); cur.IsBackground = true; cur.Start();
    Reader();
    running = false;
    ReleaseAllKeys();
    if (blocked) { try { BlockInput(false); } catch { } }
    cap.Join(1500);
    return 0;
  }
}
