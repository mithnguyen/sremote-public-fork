# S-remote Android 2.0 — kế hoạch thiết kế lại

> Tài liệu bàn giao: model nào tiếp quản chỉ cần đọc file này + `AGENTS.md` ở root.
> Tick `[x]` từng mục khi xong. Mỗi phase build được độc lập — luôn giữ app compile được.

## 0. Bối cảnh (đọc trước)

- App hiện tại (1.1.0, versionCode 9): `MainActivity` 3 tab (Hosts / Activity / Settings),
  `HostActivity` = WebView mở web UI `http://<host>:2209`, `MonitorService` giữ 1 WebSocket/host,
  chạy `Detector` phân loại RUNNING / QUESTION / DONE → `StateBus` (state live) + `EventLog`
  (lịch sử) + `Notify` (thông báo có nút trả lời).
- **Ràng buộc kỹ thuật** (giữ nguyên, đừng phá):
  - Java 17, `minSdk 26`, `compileSdk/targetSdk 34`, **không AndroidX / Material lib** — chỉ
    framework + OkHttp 4.12. UI dựng bằng code qua bộ kit `Ui.java` (màu Material You động).
  - Không thêm dependency mới (máy build offline-ish, toolchain portable).
  - Ký bằng keystore cũ (`android/keystore/release.keystore`) để cài đè được.
  - `HostActivity` IME handling (translate root, `window.__nativeKb`) đang chạy tốt — giữ nguyên.
- **API server** (đã có, không cần sửa server.js):
  - `GET /api/info` → `{hostname, tailscaleIp, home, root, platform, shells[]}`
  - `GET /api/sessions` → `[{id,name,shell,cwd,workspace,createdAt,exited,clients}]`
  - `POST /api/sessions` body `{shell,cwd,workspace,name}` → `{id}`
  - `DELETE /api/sessions/:id`
  - `GET /api/workspaces` → `[{id,name,path}]`
  - `GET /api/peers` → `{peers:[{ip,hostname,dnsName,os,online,sremote}]}`
  - WS `/ws`: gửi `{type:list|attach|detach|in|resize|create|rename|kill|ping}`;
    nhận `{type:sessions|out|exit|attached|created|pong}`. Attach không kèm cols/rows thì
    không resize PTY (an toàn để "nghe lén").
- **Web UI chưa có deep-link** → phase B thêm hash route (thay đổi nhỏ trong `public/app.js`).

## 1. Mục tiêu

App chuyên nghiệp, dùng nhanh bằng 1 tay:
1. Nhìn 1 màn là biết **cái gì cần mình** (câu hỏi đang chờ, task vừa xong) và trả lời ngay.
2. Quản lý session **native** (xem trước output, gửi lệnh, tạo / đổi tên / kill) không cần mở
   terminal WebView.
3. Mở đúng **session / workspace / remote desktop** chỉ bằng 1 chạm (deep-link vào web UI).
4. Trải nghiệm hoàn chỉnh: onboarding lần đầu, chọn theme, giờ yên lặng, snippet lệnh,
   widget màn hình chính, sao lưu danh sách host.

## 2. Kiến trúc mới

```
MainActivity            4 tab: Home · Sessions · Activity · Settings
 ├─ Home                tổng quan + "Cần bạn" + danh sách host + tailnet discovery
 ├─ Sessions            mọi session mọi host, search + filter, nhóm theo host
 ├─ Activity            (giữ, thêm filter theo host)
 └─ Settings            thêm Theme, Quiet hours, Terminal, Snippets, Backup, About
HostDetailActivity      NEW  trang host native: info, actions, workspaces, sessions
SessionSheet            NEW  bottom sheet xem trước output live + gửi input + actions
OnboardingActivity      NEW  3 bước lần đầu: Tailscale → host đầu tiên → thông báo
StatusWidget            NEW  widget màn hình chính (đếm running / cần input / xong)
Api                     NEW  OkHttp dùng chung + REST helper + WS one-shot
Actions                 NEW  hành động dùng chung (mở terminal, new/rename/kill session,
                              add/edit host, shortcut) — gỡ khỏi MainActivity
HostActivity            + deep-link hash, menu tràn, giữ màn hình sáng, đổi host nhanh
Ui                      + sheet(), search field, stat tile, list row, color tag, theme mode
HostStore               + color host, theme, quiet hours, keep-screen-on, snippets, onboarded
Notify                  + tap mở đúng session, kênh "quiet", group summary
MonitorService          + quiet hours, refresh widget
Detector                + tail(n) để preview
```

## 3. Phase & checklist

> Tiến độ: A, B, D, E, F xong (compile OK). Còn C (MainActivity rewrite), G, H.

### Phase A — Nền tảng
- [x] A1 `Api.java`: `OkHttpClient` singleton (connect 4s, read 8s), helpers chạy trên pool,
      callback về main: `info`, `sessions`, `workspaces`, `createSession`, `killSession`,
      `peers`; `wsSend(host, JSONObject...)` mở WS, gửi, đóng sau ~600ms (rename / input khi
      monitor tắt). `MonitorService.sendInput` vẫn ưu tiên khi service chạy.
- [x] A2 `HostStore`: `Host.color` (index palette, mặc định theo hash tên); prefs `theme`
      (`system|light|dark`), `quiet_on/quiet_from/quiet_to/quiet_allow_q`, `keep_screen_on`,
      `snippets` (JSON array, có mặc định), `onboarded`; `exportJson()/importJson()`.
- [x] A3 `Ui`: theme override (đọc `HostStore.theme`), `hostColor(i)` (6 tông), `avatar(name,
      color)`, `sheet(Activity, View)` (Dialog đáy màn hình, bo góc 28dp, handle kéo, nền
      container), `searchField(hint)`, `statTile(num,label,fg,bg)`, `listRow(icon,title,sub,
      trailing)`, `sectionHeader(title, action)`, `emptyState(icon,title,sub)`, `haptic(v)`.
- [x] A4 `Detector.tail(n)`: n dòng màn hình cuối (đã strip ANSI) cho preview.

### Phase B — Deep-link web UI (`public/app.js`)
- [x] B1 Hash route, xử lý sau message `sessions` đầu tiên + `hashchange`:
      `#s=<sid>` → `enterWorkspace(s.workspace||null)` + `select(sid)`;
      `#ws=<id>` → `enterWorkspace(id)`; `#screen` (hoặc `&screen=1`) → mở remote desktop
      (`$("#screen-btn").click()`). Sau khi áp dụng: `history.replaceState` xoá hash (để lần
      gửi lại cùng hash vẫn bắn `hashchange`).
- [x] B2 Bump `app.js?v=` trong `public/index.html`. Server cũ bỏ qua hash → app vẫn mở Home
      (degrade an toàn).

### Phase C — Màn hình chính
- [x] C1 Shell mới: 4 tab + bottom nav có badge (số "cần input" trên tab Home/Sessions).
- [x] C2 **Home**: header lời chào theo giờ + nút refresh; hàng 4 stat tile (Hosts online /
      Running / Cần input / Xong hôm nay); banner Tailscale/monitor (giữ logic cũ);
      section **"Cần bạn"**: thẻ cho mỗi session QUESTION (câu hỏi mono, nút trả lời nhanh
      + Reply → SessionSheet) và DONE gần đây (≤10 phút, nút Mở / Bỏ qua);
      section **Hosts**: thẻ gọn (avatar màu, tên, latency, platform, chip trạng thái,
      tối đa 3 dòng session) — tap → `HostDetailActivity`, nút terminal nhanh ở góc;
      section **Trên tailnet** thu gọn được. FAB "Add host".
- [x] C3 **Sessions**: search (tên session / host / cwd / dòng cuối), chip filter
      (Tất cả / Cần input / Đang chạy / Xong / Rảnh / Đã thoát), nhóm theo host. Nguồn:
      StateBus khi monitor bật, else poll `/api/sessions` (hiện banner "bật monitor để thấy
      trạng thái live"). Tap → SessionSheet; long-press → menu (Mở terminal, Đổi tên, Kill).
- [x] C4 **Activity**: thêm chip lọc theo host; tap event → SessionSheet nếu session còn,
      else mở host.
- [x] C5 **Settings** nhóm: Monitoring (giữ) · Quiet hours (bật, từ/đến, cho phép câu hỏi) ·
      Giao diện (Theme system/light/dark) · Terminal (giữ màn hình sáng) · Snippets (quản lý) ·
      Bảo mật (app lock) · Tailscale · Sao lưu (xuất/nhập host qua clipboard) · Giới thiệu
      (version, GitHub, "Xem lại hướng dẫn").

### Phase D — Native session & host
- [x] D1 `SessionSheet`: header (tên, host, pill trạng thái, cwd); khung preview mono ~18 dòng
      nền tối, cập nhật live — WS riêng attach session, seed bằng scrollback rồi feed
      `Detector`; nút trả lời nhanh khi QUESTION; ô nhập + Send (toggle "Enter"); chip
      snippets; hàng phím Ctrl+C / Esc / Tab / ↑ / Enter; actions: Mở terminal, Đổi tên,
      Kill (confirm). Đóng sheet → đóng WS.
- [x] D2 `HostDetailActivity`: toolbar (back, tên, chấm trạng thái, menu: Edit / Mute /
      Pin shortcut / Remove); thẻ info (hostname, platform, Tailscale IP, latency, shells);
      lưới action (Terminal, Remote desktop, New session, Refresh); Workspaces (tên, path,
      số session live) tap → terminal `#ws=`; Sessions nhóm theo workspace tap → SessionSheet.
- [x] D3 `Actions.newSession(host, workspace?)`: dialog chọn workspace (spinner từ
      `/api/workspaces` + "Standalone"), shell (từ `info.shells`), tên → POST → mở terminal
      `#s=<id>` (hoặc sheet).
- [x] D4 Add/Edit host: nút "Kiểm tra" hiện hostname/platform; chọn màu; giữ "Save anyway".

### Phase E — HostActivity (terminal)
- [x] E1 Extra `EXTRA_HASH`: load `url + "/#" + hash`; `onNewIntent` cùng host →
      `evaluateJavascript("location.hash='…'")` không reload.
- [x] E2 Toolbar: tap tiêu đề → sheet đổi host nhanh; nút Remote desktop; menu tràn (New
      session, Host details, Giữ màn hình sáng, Mở bằng trình duyệt, Copy URL, Reload).
- [x] E3 `FLAG_KEEP_SCREEN_ON` theo setting.

### Phase F — Thông báo
- [x] F1 Tap thông báo câu hỏi/xong → `HostActivity` với hash `s=<sid>` (đúng session).
- [x] F2 Quiet hours: trong khung giờ → đăng lên kênh `CH_QUIET` (IMPORTANCE_LOW, không kêu);
      tuỳ chọn vẫn cho câu hỏi kêu.
- [x] F3 ~~Group summary~~ — bỏ: summary tự đăng dễ bị "mồ côi" khi con bị huỷ; Android tự gom nhóm (setGroup theo host đã có).

### Phase G — Widget & onboarding
- [x] G1 `StatusWidget` (AppWidgetProvider + `res/layout/widget_status.xml` +
      `res/xml/widget_status_info.xml`): tiêu đề, 3 số đếm, tối đa 3 dòng session cần chú ý;
      tap → MainActivity. `MonitorService` gọi `StatusWidget.refresh()` (throttle 2s) khi
      state đổi; monitor tắt → "Monitoring off".
- [x] G2 `OnboardingActivity`: hiện khi chưa có host & chưa `onboarded`. Bước 1 Tailscale
      (trạng thái + nút Get/Open), bước 2 thêm host (ô IP + "Kiểm tra" + list máy phát hiện
      nếu đã có host), bước 3 thông báo (bật monitor + xin quyền). Nút Bỏ qua.

### Phase H — Hoàn thiện & phát hành
- [x] H1 Manifest: `HostDetailActivity`, `OnboardingActivity`, receiver `StatusWidget`.
- [x] H2 Version `2.0.0`, versionCode `10`.
- [x] H3 Detector test (javac, xem AGENTS.md) vẫn pass; build `assembleRelease assembleDebug`.
- [x] H4 Kiểm tra APK ký `CN=S-remote`, manifest đủ component.
- [x] H5 Commit, release mới kèm `S-remote-v2.0.0.apk` + `sremote-server.zip` (vì app.js đổi).

## 4. Quy ước code

- Mỗi màn hình dựng bằng `Ui` kit; không XML layout trừ widget (RemoteViews bắt buộc XML).
- Mọi network qua `Api` (pool thread) → `runOnUiThread`/main handler; không network trên main.
- Màu chỉ lấy từ role trong `Ui` (surface / container / primary… / ok / warn / err).
- Không đổi format prefs cũ (`hosts` JSON, `monitor_enabled`, `n_*`, `lock_enabled`) — chỉ thêm
  key mới để nâng cấp từ 1.1.0 giữ nguyên dữ liệu.
- Giữ comment cũ; comment mới ngắn, giải thích "tại sao".

## 5. Rủi ro / ghi chú

- WS preview của SessionSheet attach thêm 1 client → server `clients` +1, không resize PTY
  (không gửi cols/rows). Detach khi đóng.
- Hash route chỉ có tác dụng khi server ≥ bản phát hành cùng đợt (cập nhật qua `irm`).
- Widget cập nhật phụ thuộc MonitorService; không có monitor → widget tĩnh.
- Không có emulator trên máy build → chỉ verify compile + javac test + kiểm APK; báo rõ cho user.

## 5b. Bản 2.1.0 — bắt kịp web UI + bật Tailscale trong app

- [x] Giao diện thương hiệu giống web (glass theme): bảng màu cố định ink + gradient xanh→tím trong
      `Ui` (bỏ dynamic color), `Ui.backdrop()` (nền có quầng sáng), `glass()` (thẻ viền mảnh),
      `grad()`/`gradSoft()` (nút chính, chip đang chọn, tab đang chọn), `gradientText()` (chữ
      "S-remote"), `section()` có vạch gradient, `actionChip()`. Icon launcher = logo web.
- [x] Tailscale điều khiển từ app: `Tailscale.connect/disconnect/connectThen` gửi broadcast
      `com.tailscale.ipn.CONNECT_VPN|DISCONNECT_VPN` tới `com.tailscale.ipn/.IPNReceiver` (exported,
      không cần quyền) rồi poll tới khi VPN lên. `Actions.ensureTailnet` tự bật khi mở host
      (pref `ts_auto`, mặc định bật), `Actions.tailnetChip` (header Home), Settings có switch VPN +
      "Connect automatically", banner / trang lỗi terminal / onboarding có nút "Connect".
      Yêu cầu: app Tailscale đã đăng nhập và cấp quyền VPN 1 lần.
- [x] `FilesActivity` (native explorer): base `ws:<id>` hoặc `@abs` (trang Locations = Quick access +
      Projects + Drives từ `/api/browse`), breadcrumb, lọc, xem trước (ảnh/video/audio/html qua
      WebView, text qua `?head=1`), tải về (DownloadManager), tải lên nhiều file (`/api/upload`, stream
      từ SAF), đổi tên / xoá / giải nén / nén zip / thư mục mới (`/api/fileop`), "Open terminal here"
      (POST `/api/sessions` với `cwd`), mở web explorer (`#fx=`).
- [x] `GitDiff`: diff tô màu (`/api/gitdiff`), stat + từng file, cắt ở 4000 dòng.
- [x] `HostDetailActivity` kiểu sidebar web: hero + 4 action (Terminal / Desktop / Files / New),
      thẻ Project (nhánh git + số file đổi → diff, pill trạng thái, bấm để mở rộng: session + Open /
      New / Files / Changes), Standalone sessions, Dev servers (`/api/devports`, mở trong trình duyệt,
      cổng loopback gắn "PC only").
- [x] Thẻ host trên Home: hàng nút Terminal / Desktop / Files / New. Session row dùng `lastOut` của
      server để hiện "active" / "output Xm ago" khi monitor tắt.
- [x] Remote desktop 1 chạm: nút trên widget (host đầu tiên), shortcut launcher "Desktop",
      `MainActivity.ACTION_OPEN_SCREEN`.
- [x] Version 2.1.0 (versionCode 11).
- [x] Build debug + smoke test trên emulator API 34 (máy ADMIN): Home, Host detail (projects/git/dev servers), Files (locations, duyệt, preview text), Git diff, Terminal WebView, Settings — 0 crash. Sửa khi test: nhãn bottom nav lệch trái; dev servers lọc cổng hệ thống + gộp trùng như web; chỉ hỏi bật Tailscale với host tailnet (100.64/10, *.ts.net, tên MagicDNS); dòng đầu git --stat bị lệch.
- [ ] Build release (ký bằng keystore trên máy User) + test máy thật, nhất là bật/tắt Tailscale (emulator không có app Tailscale).

## 6. Việc còn lại / ý tưởng tiếp theo

- Test trên máy thật: sheet (bàn phím đè ô nhập?), widget trên nhiều launcher, quiet hours qua nửa đêm,
  theme override (light khi hệ thống dark), deep-link khi server chưa cập nhật (phải degrade về Home).
- Pull-to-refresh trên Home/Sessions (hiện dùng nút refresh + poll 10s).
- Nhận file "Share to S-remote" từ app khác (ACTION_SEND) → chọn host/thư mục → upload.
- Copy / move file giữa thư mục trong FilesActivity (`fileop copy|move` đã có ở server).
