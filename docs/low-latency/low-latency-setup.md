bay gban lYo You vay llog # Low-Latency DASH Setup — 2s Target Offset

Tài liệu tổng hợp quá trình phân tích + tune Media3 player để đạt **~2s live offset** trên stream CMAF chunked của FPT Play.

- **Test stream:** `https://live-llc-wmk.fptplay53.net/.../EPL_HCM_01_HD_H265_Cmaf/cmaf-hvc-drm/index.mpd`
- **Mục tiêu:** target live offset = 2000ms, ổn định, không rebuffer lặp.
- **Ngày tạo:** 2026-04-10

---

## 1. Phân tích manifest của stream test

### 1.1. Các giá trị thô trích từ MPD

| Field | Giá trị | Ý nghĩa |
|---|---|---|
| `minimumUpdatePeriod` (MUP) | `PT1S` | Client refresh manifest mỗi 1s |
| `minBufferTime` | `PT1S` | Player tối thiểu cần 1s dữ liệu trước khi play |
| `availabilityStartTime` | `2015-01-01T00:00:00Z` | Epoch tham chiếu cho segment index |
| `timeShiftBufferDepth` (TSBD) | `PT15S` | Server giữ 15s quá khứ |
| `publishTime` | `2026-04-09T03:28:09.575787Z` | Lần manifest mới nhất |
| `ServiceDescription/Latency@target` | `5000ms` | Gợi ý của server |
| `ServiceDescription/Latency@min` | `4000ms` | Gợi ý min của server |
| `ServiceDescription/Latency@max` | `7000ms` | Gợi ý max của server |
| `PlaybackRate@min / @max` | `0.93 / 1.07` | Gợi ý speed range của server |
| `SegmentTemplate@timescale` | `10_000_000` | 10M tick/giây |
| `SegmentTemplate@duration` (d) | `19_200_000` → **1.92s** | Thời lượng 1 segment |
| `SegmentTemplate@availabilityTimeOffset` | `1.68` | Segment available sớm 1.68s trước khi hoàn thành |
| `SegmentTemplate@availabilityTimeComplete` | `false` | Segment stream qua HTTP chunked transfer |

### 1.2. Các giá trị này nói gì?

- **Chunked CMAF:** `ATO=1.68` + `ATC=false` ⇒ encoder đẩy chunk nhỏ (~240ms) qua HTTP chunked, client có thể fetch **ngay khi segment mới bắt đầu** (chỉ chậm 0.24s so với wall-clock).
- **Latency vật lý tối thiểu ≈ 500–800ms** với stream này (1 chunk + network RTT). Target 2s hoàn toàn khả thi.
- **Manifest gợi ý 4–7s** nhưng chỉ là gợi ý — có thể override bằng `MediaItem.LiveConfiguration`.
- **TSBD = 15s** → `maxOffsetMs` phải < 15000, và không cần back buffer lớn vì server đã giữ hộ.

### 1.3. Sơ đồ timeline

```
Wall clock T = "bây giờ"

 T−15s                 T−5s    T−2s  T−1.5s        T
  │                     │       │      │            │
  ├─ TSBD window ───────┼───────┼──────┼────────────┤
  │                     │       │      │            │
  │                     │       │      │            └─ live edge vật lý
  │                     │       │      └─ MIN offset (1.5s)
  │                     │       └─ TARGET offset (2s) ★
  │                     └─ MAX offset (5s)
  │
  └─ segment cũ nhất còn trên server (TSBD biên)

Segment grid (1.92s/segment):
  │◄─1.92─►│◄─1.92─►│◄─1.92─►│◄─1.92─►│◄─1.92─►│
           ↑                                    ↑
           segment cũ                           segment đang ghi
                                                (ATO=1.68 → fetch được sau 0.24s)

Chunk bên trong segment đang ghi (~240ms/chunk):
           T−0.24  T   ← encoder tạo tới đây
           [▓][▓][▓][▓][░][░][░][░]
            ↑       ↑
            đã push đang push qua HTTP
```

---

## 2. Best setup đề xuất

### 2.1. `MediaItem.LiveConfiguration`

```java
MediaItem.LiveConfiguration liveConfig =
    new MediaItem.LiveConfiguration.Builder()
        .setTargetOffsetMs(2000)     // bám 2s
        .setMinOffsetMs(1500)        // không bao giờ dưới 1.5s (< 1 segment)
        .setMaxOffsetMs(5000)        // trước khi speed-up kéo về
        .setMinPlaybackSpeed(0.95f)  // rộng hơn để chịu jitter
        .setMaxPlaybackSpeed(1.08f)  // đủ lực kéo về 2s khi tụt
        .build();

mediaItem = mediaItem.buildUpon().setLiveConfiguration(liveConfig).build();
```

**Quan trọng:** các giá trị trong `MediaItem.LiveConfiguration` có độ ưu tiên **cao hơn** `ServiceDescription` của manifest. Giá trị `min=4000` của server bị override về `1500`.

### 2.2. `LoadControl` (tight buffer)

```java
LoadControl loadControl =
    new DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs= */               2_000,
            /* maxBufferMs= */               8_000,
            /* bufferForPlaybackMs= */       500,
            /* bufferForPlaybackAfterRebufferMs= */ 1_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(/* backBufferDurationMs= */ 0,
                       /* retainBackBufferFromKeyframe= */ false)
        .build();

ExoPlayer player =
    new ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(new DefaultMediaSourceFactory(context))
        .build();
```

### 2.3. `LivePlaybackSpeedControl` — **optional**, chỉ thêm khi cần

```java
// CHỈ thêm nếu sau khi chạy baseline thấy offset lười về target.
LivePlaybackSpeedControl speedControl =
    new DefaultLivePlaybackSpeedControl.Builder()
        .setMinUpdateIntervalMs(200)         // default 1000 → phản ứng nhanh hơn
        .setProportionalControlFactor(1.0f)  // default 0.1 → kéo mạnh hơn
        .build();

ExoPlayer player =
    new ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setLivePlaybackSpeedControl(speedControl)
        .build();
```

---

## 3. Giải thích từng tham số

### 3.1. `LiveConfiguration`

#### `targetOffsetMs = 2000`
Khoảng cách lý tưởng đến live edge. Player sẽ điều chỉnh tốc độ phát để bám quanh giá trị này.

#### `minOffsetMs = 1500`
Giới hạn gần nhất với live edge. Dưới ngưỡng này, `LivePlaybackSpeedControl` sẽ **giảm speed** (`→ minPlaybackSpeed`) để tránh underrun. Chọn 1500ms vì gần bằng 1 segment (1.92s) — dưới đó rủi ro chunk kế chưa sẵn.

#### `maxOffsetMs = 5000`
Giới hạn xa nhất. Vượt ngưỡng này, player **tăng speed** (`→ maxPlaybackSpeed`) để kéo về. Phải nhỏ hơn `timeShiftBufferDepth = 15000` rất nhiều để an toàn.

#### `minPlaybackSpeed = 0.95` / `maxPlaybackSpeed = 1.08`

Hai giá trị này là **tốc độ phát**, không phải tốc độ tải:
- `1.0` = bình thường.
- `1.08` = nhanh hơn 8% → khoảng cách đến live edge co lại 8%/giây.
- `0.95` = chậm hơn 5% → khoảng cách nới ra 5%/giây.

**Công thức xấp xỉ (DefaultLivePlaybackSpeedControl):**
```
errorMs         = currentLiveOffset − targetOffsetMs
speedAdjustment = errorMs × proportionalControlFactor / 1000
speed           = clamp(1.0 + speedAdjustment, minSpeed, maxSpeed)
```

**Vì sao không đối xứng?** Target 2s → "room" hai phía rất hẹp (0.5s mỗi chiều). Khi bị jitter đẩy ra, cần **lực kéo mạnh hơn** (→ max 1.08) để hồi nhanh. Còn khi quá gần live, chỉ cần nới ra (→ min 0.95 là đủ). Vượt quá 1.08 hoặc dưới 0.95 thì tai người bắt đầu nghe rõ pitch shift.

**So với setup cũ (4s target, speed 0.96–1.04):** vì target 4s có room ±2s, jitter nhẹ không cần chỉnh, biên hẹp vẫn đủ. Target 2s thì không — phải nới biên.

### 3.2. `LoadControl`

#### `setBufferDurationsMs(2000, 8000, 500, 1000)`

| Tham số | Giá trị | Ý nghĩa |
|---|---|---|
| `minBufferMs` | 2000 | Buffer tối thiểu giữ lại sau khi đã play |
| `maxBufferMs` | 8000 | Trần buffer — không tải thêm nếu đã đầy |
| `bufferForPlaybackMs` | 500 | Cần 0.5s để bắt đầu play lần đầu |
| `bufferForPlaybackAfterRebufferMs` | 1000 | Cần 1s để phục hồi sau rebuffer |

Default của `DefaultLoadControl` là (50000, 50000, 2500, 5000) — tức **50 giây**, hoàn toàn không phù hợp cho LL vì player sẽ bám buffer lớn và tụt khỏi live edge.

#### `setPrioritizeTimeOverSizeThresholds(true)`

`DefaultLoadControl` có **2 ngưỡng** quyết định khi nào dừng tải:

1. **Time threshold** (giây): `minBufferMs` / `maxBufferMs`.
2. **Size threshold** (byte): `targetBufferBytes` — mặc định auto theo codec, thường ~40–130 MB.

**Mặc định `false`:** player dừng tải khi **một trong hai** chạm ngưỡng. Với stream bitrate cao (8 Mbps) và `maxBufferMs = 8000`, size threshold có thể âm thầm chặn tải trước khi đạt 8s buffer → khó debug.

**Khi `true`:** bỏ qua size threshold, **thời gian là nguồn sự thật duy nhất**. Quan trọng cho LL vì:
- Ngăn size threshold chặn việc nạp bù khi `LivePlaybackSpeedControl` đang speed-up 1.08x.
- Buffer LL luôn nhỏ (8s × 10Mbps = 10MB) nên bỏ size cũng không lo OOM.

#### `setBackBuffer(0, false)`

**Back buffer** = dữ liệu **đã phát xong** nhưng vẫn giữ trong RAM phòng user seek lùi.

```
   quá khứ                hiện tại              tương lai
 ───┬────────────────────────┬────────────────────┬────
    P−backBuffer             P                    P+forwardBuffer
    ←──── back buffer ───────┤
                             ├──── forward buffer ────→
                             (minBufferMs..maxBufferMs)
```

**Set 0 cho LL vì:**
1. **Live TV ít khi seek lùi** — đa phần xem realtime. Giữ back buffer là lãng phí RAM.
2. **TSBD = 15s** — server đã giữ quá khứ, user seek lùi xa thì client re-fetch từ server, không cần cache cục bộ.
3. **RAM minh bạch:** với 8Mbps, 30s back buffer = 30MB. Set 0 → tổng buffer = forward (8s = 10MB) gọn gàng.
4. `retainBackBufferFromKeyframe = false` là lựa chọn an toàn phòng khi sau này đổi back buffer > 0.

### 3.3. `LivePlaybackSpeedControl` — khi nào cần custom?

Mặc định `DefaultLivePlaybackSpeedControl` đã được áp dụng. Các tham số của nó:

| Param | Default | Cần custom cho LL? |
|---|---|---|
| `fallbackMinPlaybackSpeed` | 0.97 | ❌ Không — bị ghi đè bởi `MediaItem.LiveConfiguration.minPlaybackSpeed` |
| `fallbackMaxPlaybackSpeed` | 1.03 | ❌ Không — bị ghi đè bởi `MediaItem.LiveConfiguration.maxPlaybackSpeed` |
| `minUpdateIntervalMs` | **1000** | ✅ Nên hạ xuống **200** cho target 2s |
| `proportionalControlFactor` | **0.1** | ✅ Nên tăng lên **1.0** cho target 2s |
| `maxLiveOffsetErrorMsForUnitSpeed` | 20 | ❌ Default OK |
| `targetLiveOffsetIncrementOnRebufferMs` | 500 | ❌ Default OK |

**Chữ "fallback" quan trọng:** hai tham số đó **chỉ được dùng khi `MediaItem.LiveConfiguration` không set speed**. Vì ta đã set trong LiveConfiguration → fallback luôn bị bỏ qua → không cần custom 2 dòng đó.

**Khi nào cần `minUpdateIntervalMs=200` và `proportionalControlFactor=1.0`:**

- **Default `minUpdateIntervalMs=1000`:** bộ điều khiển 1 giây mới tính lại speed. Jitter trong vòng 1s không được xử lý → với target 2s (room hẹp 0.5s), 1s trễ là quá nhiều.
- **Default `proportionalControlFactor=0.1`:** error 300ms chỉ tạo speed adjust 0.03 (→ 1.03x) — quá yếu. Với factor 1.0, error 300ms tạo adjust 0.3 → đạt max speed 1.08 ngay → hồi về target nhanh hơn nhiều.

**Khuyến nghị:**
1. **Chạy baseline trước** chỉ với `LiveConfiguration` + `LoadControl`, không custom speedControl.
2. Nếu thấy `currentLiveOffset` trôi > 2.5s kéo dài hoặc ít khi về đúng 2s → mới thêm custom speedControl với 2 dòng trên.
3. Vẫn chưa đạt? Nâng `proportionalControlFactor = 2.0` hoặc hạ `minUpdateIntervalMs = 100`.

---

## 4. Ma trận kỳ vọng & giới hạn vật lý

| Metric | Giá trị kỳ vọng |
|---|---|
| Live offset ổn định | 2.0 – 2.5s |
| Rebuffer khi mạng tốt | 0 |
| Speed điều chỉnh | 0.98 – 1.03 phần lớn, bursty 1.08 khi jitter |
| Start-up delay | 0.5 – 1s |

### Ràng buộc từ manifest

| Ràng buộc | Lý do |
|---|---|
| `minOffsetMs ≥ ~1500` | Segment = 1.92s, dưới ngưỡng này rủi ro thiếu chunk |
| `maxOffsetMs < 15000` | TSBD = 15s, vượt là 404 |
| Target khả thi ≥ ~1s | `ATO=1.68` cho phép, + network RTT |
| DataSource hỗ trợ HTTP chunked | `availabilityTimeComplete=false` bắt buộc — OkHttp / Cronet OK |

---

## 5. Logging & phân tích kết quả

Các log được tích hợp **trực tiếp vào core libs** (không cần chỉnh app) nên khi build AAR ra bất kỳ app nào cũng ra log. Tất cả dùng tag chung `LL-Core`.

### 5.1. Các điểm log đã instrument

| File | Hook | Section tag |
|---|---|---|
| `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/util/LowLatencyLog.java` | Util trung tâm (toggle `enabled`, tag `LL-Core`) | — |
| `DashMediaSource.updateLiveConfiguration()` | MediaItem in / ServiceDescription / merged OUT + manifest summary | `DashMerge` |
| `DefaultLivePlaybackSpeedControl` (ctor) | Các tham số init của speed controller | `SpeedCtrl` |
| `DefaultLivePlaybackSpeedControl.setLiveConfiguration()` | LiveConfig mà speed controller nhận từ DashMediaSource | `SpeedCtrl` |
| `DefaultLivePlaybackSpeedControl.notifyRebuffer()` | REBUFFER event + dịch target | `SpeedCtrl` |
| `DefaultLivePlaybackSpeedControl.getAdjustedPlaybackSpeed()` | Tick mỗi 500ms: liveOff, target, err, speed, buf, minPossible | `SpeedCtrl` |
| `DefaultLivePlaybackSpeedControl` (speed change) | Speed chuyển ngưỡng (0.95 ↔ 1.0 ↔ 1.08) | `SpeedCtrl` |
| `DefaultLivePlaybackSpeedControl` (target adjust) | `adjustTargetLiveOffsetUs` dịch `currentTargetLiveOffsetUs` | `SpeedCtrl` |

Toggle bật/tắt: `LowLatencyLog.enabled = false;` (volatile, runtime safe).

### 5.2. Cách lọc log trên thiết bị

```bash
# Chỉ xem core logs
adb logcat -s LL-Core:D

# Kèm app logs (nếu app cũng dùng tag "LL")
adb logcat -s LL-Core:D LL:D

# Lưu file để gửi phân tích
adb logcat -s LL-Core:D -v time > ll-log.txt
```

### 5.3. Format log thực tế

Khi MPD mới refresh (mỗi MUP = 1s với stream test):

```
[DashMerge] manifest: mup=1000ms tsbd=15000ms minBuf=1000ms windowDur=14890ms nowInWindow=14890ms
[DashMerge] mediaItem in: target=2000ms min=1500ms max=5000ms speed=[0.950,1.080]
[DashMerge] serviceDesc  : target=5000ms min=4000ms max=7000ms speed=[0.930,1.070]
[DashMerge] merged   OUT: target=2000ms min=1500ms max=5000ms speed=[0.950,1.080]
[SpeedCtrl] setLiveConfiguration: target=2000ms min=1500ms max=5000ms speed=[0.950,1.080] (fallbackSpeed=[0.970,1.030])
```

Khi chạy (mỗi 500ms):

```
[SpeedCtrl] tick: liveOff= 2180ms curTarget= 2000ms idealTarget= 2000ms err= +180ms speed=1.018 buf= 6500ms minPossible= 1240ms
[SpeedCtrl] tick: liveOff= 2310ms curTarget= 2000ms idealTarget= 2000ms err= +310ms speed=1.031 buf= 6800ms minPossible= 1210ms
[SpeedCtrl] speedChange: 1.031 → 1.080 (err=+800ms target=2000ms liveOff=2800ms bounds=[0.950,1.080])
[SpeedCtrl] tick: liveOff= 2850ms curTarget= 2000ms idealTarget= 2000ms err= +850ms speed=1.080 buf= 7100ms minPossible= 1180ms
```

Khi rebuffer:

```
[SpeedCtrl] REBUFFER: target 2000ms → 2500ms (increment=500ms, max=5000ms, clamped=false)
```

Khi target bị tự động dịch (safety distance):

```
[SpeedCtrl] targetAdjust: 2000ms → 2340ms (ideal=2000ms liveOff=2800ms)
```

### 5.4. Log nào sẽ lộ nguyên nhân không đạt 2s?

| Triệu chứng trong log | Ý nghĩa | Hành động |
|---|---|---|
| `[DashMerge] merged OUT: target=5000ms` (không phải 2000) | Client **chưa** set LiveConfiguration → manifest thắng | Set `MediaItem.LiveConfiguration` trong app |
| `[DashMerge] mediaItem in: target=UNSET` | App truyền MediaItem không có LiveConfig | Gọi `.setLiveConfiguration(...)` khi build MediaItem |
| `merged OUT: min=4000ms` dù `mediaItem in: min=1500ms` | Lỗi hiếm — check logic merge | Đọc code quanh line 1062 của DashMediaSource |
| `tick: curTarget > idealTarget` kéo dài | `adjustTargetLiveOffsetUs` nâng target vì buffer "unsafe" | Buffer quá nhỏ / jitter — nới `minOffsetMs` hoặc check LoadControl |
| `tick: speed=1.080` dính liên tục, err lớn | Mạng không gánh nổi 2s | Nâng target → 2500 hoặc nâng `maxPlaybackSpeed` → 1.10 |
| `tick: speed=1.000` với err > 100ms | Proportional factor quá nhỏ (default 0.1) | Tạo custom `DefaultLivePlaybackSpeedControl` với `proportionalControlFactor=1.0` |
| Khoảng cách giữa 2 tick > 1s | `minUpdateIntervalMs` quá cao (default 1000) | Hạ xuống 200 với `.setMinUpdateIntervalMs(200)` |
| `REBUFFER` lặp lại | Underrun lặp — mạng / config không kham nổi | Nâng `minOffsetMs` → 1800, `bufferForPlaybackAfterRebuffer` → 1500 |
| `tick: minPossible > 1500` luôn cao | Chunked transfer không hoạt động — server/DataSource | Check `ATO/ATC` của MPD, đảm bảo dùng OkHttp/Cronet |
| `targetAdjust` dịch lên liên tục | Speed controller quyết định 2s không an toàn | Nới `minOffsetMs` hoặc giảm mục tiêu |

### 5.5. Checklist phân tích log gửi về

Khi gửi log cho mình phân tích, cần:

1. **Đoạn `DashMerge` đầu tiên** (sau khi play) — xác minh merged config đúng.
2. **2–3 phút log `SpeedCtrl tick`** ở trạng thái ổn định (không rebuffer).
3. **Mọi dòng `REBUFFER` / `targetAdjust` / `speedChange`** (nếu có).
4. **Metadata mạng:** Wi-Fi hay cellular, bandwidth ước lượng.

Lệnh lấy log gọn:

```bash
adb logcat -c                           # clear buffer
# ... chạy player 2-3 phút ...
adb logcat -d -s LL-Core:D -v time > ll-log.txt
```

### 5.3. Diễn giải log

| Pattern trong log | Ý nghĩa | Hành động |
|---|---|---|
| `avg` gần 2000, `min/max` trong [1600, 2800] | Setup hoàn hảo | Giữ nguyên; có thể thử siết `max` về 1.06 cho audio đẹp |
| `avg` > 3000, speed liên tục dính 1.08 | Mạng không gánh nổi, player thua cuộc | Nâng target lên 2500 hoặc nâng `maxPlaybackSpeed` lên 1.10 |
| `avg` ~ 2000 nhưng `max` > 4000 thỉnh thoảng | Jitter lớn, phục hồi chậm | Thêm custom speedControl (`factor=1.0`, `updateInterval=200`) |
| `min` < 1500 rồi rebuffer (`BUF` state) | Quá gần edge | Nâng `minOffsetMs` lên 1800, `bufferForPlaybackAfterRebufferMs` lên 1500 |
| Speed dao động liên tục (`speedChanges` rất cao) | Bộ điều khiển quá nhạy | Tăng `minUpdateIntervalMs` lên 500, hoặc hạ factor về 0.5 |
| `BUF` state lặp đều đặn | Rebuffer lặp — mạng/config không kham nổi | Lùi target về 2500–3000, kiểm tra bandwidth thực |
| Pitch nghe rõ khi trận bóng căng | `maxPlaybackSpeed=1.08` gây audible | Hạ về 1.05, chấp nhận offset trôi lên ~2.5s khi jitter |

### 5.4. Wall-clock check

Ngoài log, dùng đồng hồ vật lý cạnh TV để so sánh với TV broadcast hoặc nguồn khác cùng trận → xác nhận offset đo được từ `getCurrentLiveOffset()` khớp với cảm nhận thực tế.

---

## 6. Checklist chạy thử

- [ ] Đã `setLiveConfiguration` với target=2000 / min=1500 / max=5000 / speed=[0.95, 1.08]
- [ ] Đã `setLoadControl` với 2000/8000/500/1000 + `prioritizeTimeOverSize=true` + `backBuffer=0`
- [ ] Đã bật logger 500ms ghi `offset / speed / buf / state`
- [ ] DataSource là OkHttp hoặc Cronet (hỗ trợ HTTP chunked)
- [ ] Đã chạy baseline ≥ 2 phút, lấy log
- [ ] Đối chiếu log với bảng diễn giải ở §5.3
- [ ] Nếu cần, thêm custom `DefaultLivePlaybackSpeedControl` với `updateInterval=200` + `factor=1.0`, chạy lại

---

## 7. Workflow toàn bộ quá trình play LL-DASH target 2s

Dùng đúng số liệu stream thực tế: segment=1.92s, CMAF chunk ~320ms, target=2000ms, min=1500ms, max=5000ms, speed=[0.950, 1.080].

### Phase 1: Khởi tạo & Manifest Load

```
[App]
  MediaItem.LiveConfiguration:
    target = 2000ms
    min    = 1500ms
    max    = 5000ms
    speed  = [0.950, 1.080]

[ExoPlayer.Builder]
  LoadControl: bufferForPlayback=500ms, afterRebuffer=1500ms
  LivePlaybackSpeedControl: increment=200ms (hoặc default 500ms)

[DashMediaSource]
  Download index.mpd
  Parse: segment duration=1.92s, ATO=1.68s, ATC=false
  Parse: ServiceDescription target=5000ms, min=4000ms, max=7000ms
```

### Phase 2: Merge Configuration (mỗi manifest refresh ~1s)

```
┌─────────────────────────────────────────────────────┐
│  DashMediaSource.updateLiveConfiguration()          │
│                                                     │
│  MediaItem (app):      target=2000 min=1500 max=5000│
│  ServiceDesc (server): target=5000 min=4000 max=7000│
│                                                     │
│  Quy tắc: MediaItem ưu tiên hơn ServiceDesc        │
│                                                     │
│  merged OUT: target=2000 min=1500 max=5000          │
│  (Fix 1 đảm bảo luôn lấy từ MediaItem)             │
└─────────────────────────────────────────────────────┘
```

### Phase 3: Xác định vị trí bắt đầu play

```
Server timeline (mỗi ô = 1 CMAF chunk ~320ms):

Segment N-2 (1.92s)     Segment N-1 (1.92s)     Segment N (đang encode)
[c1][c2][c3][c4][c5][c6][c1][c2][c3][c4][c5][c6][c1][c2][c3]...
                                                              ↑
                                                          Live Edge

Player chọn playback position = Live Edge - targetOffset:

                                                              ↑ Live Edge
                                          ↑ Playback Position
                                          │◄──── 2000ms ────►│
```

### Phase 4: Download & Buffer

```
Live Edge
    ↓
────┼─────────────────────────────────────────
    │◄── chưa có data (server đang encode) ──►
    │
    │◄── data đã có trên CDN ──►│
    │   (khoảng 2s = ~6 chunks)  │
    │                            │
    │                            ↑ Playback position
    │                            │
    │◄── forward buffer ────────►│
         (data đã download
          nhưng chưa phát)

Download flow:
  1. Player request chunk → CDN trả về ~320ms data
  2. Buffer += 320ms
  3. Nếu buffer >= bufferForPlayback (500ms) → BẮT ĐẦU PLAY
  4. Tiếp tục download chunk tiếp theo
  5. Lặp lại...

Lưu ý: Player phát 1x speed, chunk mới xuất hiện mỗi ~320ms.
  → Download speed ≈ playback speed → buffer KHÔNG TĂNG, chỉ giữ ~2s
```

### Phase 5: Trạng thái ổn định (happy path)

```
Mỗi 500ms, SpeedCtrl tính:

  liveOffset = NOW - playbackPosition = 2015ms
  target     = 2000ms
  error      = liveOffset - target = +15ms  (rất nhỏ)
  speed      = 1.0 + 0.1 × (0.015) = 1.0015 ≈ 1.000

                              Live Edge (di chuyển →)
                                  ↓
  ──────────────────────────────┼────
                 ↑              ↑
            Playback pos    Last downloaded chunk
            (di chuyển →)
                 │◄── buf ~1.5s ►│
                 │◄──── 2.0s ───►↑ Live Edge

  Log: tick: liveOff=2015ms curTarget=2000ms err=+15ms speed=1.000 buf=1610ms
```

### Phase 6: LiveOffset bị trôi xa (network jitter)

```
CDN bị delay 500ms khi trả chunk:

  Trước: liveOff = 2015ms, buf = 1610ms
  Sau:   liveOff = 2515ms, buf = 1110ms  (player vẫn phát, buffer giảm)

SpeedCtrl phản ứng:
  error = 2515 - 2000 = +515ms = 0.515s
  speed = 1.0 + 0.1 × 0.515 = 1.052

  → Player phát NHANH hơn bình thường (1.052x)
  → Playback position tiến nhanh → liveOffset GIẢM dần → kéo về target 2s

  Log: tick: liveOff=2515ms curTarget=2000ms err=+515ms speed=1.052 buf=1110ms
```

### Phase 7: LiveOffset quá sát (buffer dày)

```
Network burst → download nhanh → liveOff giảm xuống 1800ms:

  error = 1800 - 2000 = -200ms = -0.2s
  speed = 1.0 + 0.1 × (-0.2) = 0.98

  → Player phát CHẬM hơn bình thường (0.98x)
  → Playback position tiến chậm → liveOffset TĂNG dần → đẩy về target 2s

  Log: tick: liveOff=1800ms curTarget=2000ms err=-200ms speed=0.980 buf=1800ms
```

### Phase 8: REBUFFER (buffer cạn kiệt)

```
Player đang phát, chunk tiếp theo CHƯA sẵn sàng trên CDN:

  T=0:    buf = 1500ms  ← đang play bình thường
  T=0.5s: buf = 1000ms  ← vẫn play, chưa có chunk mới
  T=1.0s: buf =  500ms  ← vẫn chưa có!
  T=1.3s: buf =  200ms  ← nguy hiểm
  T=1.5s: buf =    0ms  ← HẾT BUFFER!

  ╔═══════════════════════════════════════════════════╗
  ║  REBUFFER! Player dừng video, hiện loading icon   ║
  ║                                                   ║
  ║  increment=200ms:                                 ║
  ║  target 2000ms → 2200ms                           ║
  ║  (nhích xa live edge thêm 200ms cho an toàn)      ║
  ╚═══════════════════════════════════════════════════╝

  Player chờ download... chunk đến... buffer tăng dần:
  buf = 320ms → 640ms → 960ms → 1280ms → 1500ms

  if (buf >= bufferForPlaybackAfterRebuffer = 1500ms) → RESUME PLAY!

  Playback position lúc này đã lùi xa live edge hơn:
  liveOff = 2700ms (vì player dừng nhưng live edge vẫn tiến)

  Log: REBUFFER: target 2000ms → 2200ms (increment=200ms)
       tick: liveOff=2700ms curTarget=2200ms err=+500ms speed=1.050

  LƯU Ý: Rebuffer ở live KHÔNG phải do bandwidth thấp.
  Dù mạng 100Mbps, data CHƯA TỒN TẠI trên server (đang encode)
  → không có gì để download → buffer cạn.
```

### Phase 9: Recovery sau rebuffer

```
Sau rebuffer, target mới = 2200ms:

  T+0s:  liveOff=2700ms, target=2200ms → speed=1.050 (kéo về)
  T+5s:  liveOff=2500ms, target=2200ms → speed=1.030
  T+10s: liveOff=2300ms, target=2200ms → speed=1.010
  T+15s: liveOff=2210ms, target=2200ms → speed=1.001 ← ổn định!

  Nhờ Fix 1 (DashMediaSource patch), mỗi manifest refresh:
    mediaItemLiveConfiguration.targetOffsetMs = 2000ms  ← luôn re-anchor
    → idealTarget = 2000ms

  adjustTargetLiveOffsetUs() thấy idealTarget < curTarget:
    curTarget 2200ms → 2100ms → 2050ms → 2000ms (từ từ giảm)
    (tốc độ giảm phụ thuộc minPossibleLiveOffset smoothing)

  Cuối cùng: liveOff ≈ 2000ms, target = 2000ms ← VỀ ĐÚNG MỤC TIÊU
```

### Phase 10: Chu kỳ lặp lại

```
┌──────────────────────────────────────────────────────┐
│                                                      │
│   ┌─────────┐    ┌──────────┐    ┌────────────┐     │
│   │ Ổn định │───►│ Buffer   │───►│  REBUFFER   │     │
│   │ ~2.0s   │    │ cạn dần  │    │  target+200 │     │
│   └────▲────┘    └──────────┘    └──────┬─────┘     │
│        │                                │           │
│        │         ┌──────────┐           │           │
│        └─────────│ Recovery │◄──────────┘           │
│                  │ speed>1.0│                        │
│                  │ kéo về 2s│                        │
│                  └──────────┘                        │
│                                                      │
│   Chu kỳ lặp lại mỗi ~20-30s tùy network            │
│   liveOff dao động: 2.0s ↔ 2.8s                     │
└──────────────────────────────────────────────────────┘
```

### Timeline thực tế (từ log test)

```
14:51:10  ── Play bắt đầu ──
          liveOff=4.9s, speed=1.080 (kéo mạnh về 2s)

14:51:25  ── Đạt target ──
          liveOff=2.6s, speed=1.005, buf=318ms
          → GẦN mục tiêu, nhưng buffer mỏng

14:51:28  ── REBUFFER #1 ──
          target 2583→2683 (+100ms)

14:51:30  ── REBUFFER #2 ──
          target 2653→2753 (+100ms)

14:51:48  ── Ổn định ──
          liveOff=2850ms, target=2818ms, err=+32ms, speed=1.004
          buf=2320ms ← buffer dày, play mượt (~20s)

14:52:08  ── REBUFFER #3 ──
          Chunk delay → buffer cạn → rebuffer
          Chu kỳ lặp lại...
```

---

## 8. Chọn targetOffset tối ưu — Kết quả test thực tế

### 8.0. Tại sao LL-DASH rebuffer? (Root cause)

Trong LL-DASH với CMAF chunked transfer, **buffer không thể tự hồi phục** — đây là root cause:

```
Steady state (1x playback speed):

  Mỗi 320ms:
    - Player TIÊU THỤ 320ms buffer (phát video)
    - Server PRODUCE 1 chunk 320ms → push CDN → player download

  → Buffer giữ nguyên (không tăng, không giảm)
  → Khác VOD: ở VOD bandwidth >> bitrate → buffer tăng nhanh
  → Ở LL-DASH: dù bandwidth 1Gbps, buffer vẫn KHÔNG TĂNG vì data chưa tồn tại
```

Rebuffer xảy ra khi bất kỳ event nào làm buffer giảm — và **không có cơ chế hồi phục**:

```
Các trigger làm buffer giảm:

  1. CDN delay spike — chunk đến chậm 500ms → buffer mất 500ms, VĨNH VIỄN không lấy lại
  2. Network jitter — download chunk chậm hơn bình thường → mất thêm vài trăm ms
  3. Speed control > 1x — player tiêu thụ buffer NHANH hơn chunk được sản xuất

Timeline ví dụ (target=2s, buffer ban đầu ~1.6s = ~5 chunk headroom):

  T=0.0s: buf=1600ms ✓  chunk đến đúng giờ
  T=0.3s: buf=1600ms ✓  chunk đến đúng giờ
  T=0.6s: buf=1600ms ✓  chunk đến đúng giờ
  T=0.9s: buf=1280ms ⚠  CDN delay — chunk đến chậm 320ms
  T=1.2s: buf= 960ms ⚠  CDN vẫn delay
  T=1.5s: buf= 640ms ⚠
  T=1.8s: buf= 320ms ⚠
  T=2.1s: buf=   0ms ❌  REBUFFER! — chỉ 5 lần CDN delay liên tiếp = hết buffer
```

**Kết luận: Root cause KHÔNG phải bandwidth, KHÔNG phải segment size — mà là chunk chưa tồn tại trên CDN + buffer không có cơ chế hồi phục ở real-time streaming.**

### 8.1. Công thức chọn targetOffset

**⚠ Lưu ý quan trọng:** Stream dùng CMAF chunked transfer — player download **từng chunk ~320ms**, KHÔNG đợi hết segment 1.92s. Vì vậy targetOffset phụ thuộc vào **CDN delivery latency**, không phải segment duration.

```
targetOffset >= CDN_propagation_delay + jitter_margin
```

Với CDN `live-llc-wmk` (đo từ test thực tế):
- CDN propagation delay trung bình: ~500ms–1s
- Jitter margin cần: ~500ms–1s
- → targetOffset >= ~2s–3s

Kết quả test:
- `targetOffset = 2.0s` → buffer ~1.6s → CDN delay 500ms ăn gần hết → rebuffer liên tục
- `targetOffset = 2.5s` → buffer ~2.1s → CDN delay 500ms chịu được, nhưng spike 1s = cháy
- `targetOffset = 3.0s` → buffer ~2.6s → đủ absorb CDN delay + jitter → stable

**Lưu ý:** Formula cũ `targetOffset >= segment_duration + 1s` chỉ là kết quả thực nghiệm trùng hợp cho CDN này (segment 1.92s ≈ CDN delay ~1-2s). CDN khác (ví dụ `event-llc-wtm` với delay thấp hơn) cho kết quả khác — target 2s đã gần stable trên CDN đó.

### 8.2. Kết quả test thực tế (stream FPT Play, segment=1.92s)

| Target | Rebuffers (loading) | Thời gian test | Freq | liveOff ổn định | Đánh giá |
|---|---|---|---|---|---|
| 2.0s | 7–67 lần | 2–5 phút | ~13/phút | 2.0–3.5s (dao động) | Loading liên tục |
| 2.5s | 24 lần | ~5 phút | ~5/phút | 2.5–3.7s (dao động) | Vẫn loading nhiều |
| **3.0s** | **0 lần** | **~3 phút** | **0/phút** | **~3.2s (ổn định)** | **Không loading** |

### 8.3. Tại sao target 2s loading liên tục?

Stream dùng CMAF chunked transfer: mỗi segment 1.92s được chia thành ~6 CMAF chunk (~320ms).
Player download **từng chunk**, không đợi hết segment.

```
CMAF chunk flow (segment 1.92s = 6 chunks × 320ms):

Server encode real-time:
  T=0.000s  chunk 1 encode xong → push CDN
  T=0.320s  chunk 2 encode xong → push CDN
  T=0.640s  chunk 3 encode xong → push CDN
  T=0.960s  chunk 4 encode xong → push CDN
  T=1.280s  chunk 5 encode xong → push CDN
  T=1.600s  chunk 6 encode xong → push CDN → segment hoàn tất
  T=1.920s  chunk 1 (segment mới) encode xong → push CDN
  ...

Server tạo 1 chunk mới mỗi 320ms. Tốc độ này KHÔNG ĐỔI dù bandwidth bao nhiêu.
```

```
Target 2s — Phân tích buffer chi tiết:

Live Edge (server đang encode frame này)
    ↓
────┼────────────────────────────────────────────
    │ chunk đang encode (chưa có trên CDN)
    │
    │◄── data đã có trên CDN ──────────────────►│
    │  c6  c5  c4  c3  c2  c1  c6  c5  c4 ... │
    │  ↑                                        │
    │  chunk mới nhất trên CDN                  ↑ Playback position
    │                                           │   (target 2s)
    │◄───────── forward buffer ────────────────►│
    │  (data đã download, chưa phát)            │

Forward buffer = khoảng cách từ playback position đến chunk mới nhất trên CDN
               = targetOffset - thời gian server encode chunk tiếp theo
               ≈ 2000ms - 1920ms = 80ms (chỉ còn 0.08s!)
```

```
Diễn biến buffer theo thời gian (target 2s):

  T=0.000s: Chunk mới lên CDN → player download (320ms data) → buf = 1600ms
  T=0.320s: Player đã phát 320ms + chunk mới lên CDN           → buf = 1600ms
  T=0.640s: Player đã phát 320ms + chunk mới lên CDN           → buf = 1600ms
  ...
  (Trạng thái ổn định: buf dao động ~1200–1800ms, download speed ≈ playback speed)

  Nhưng khi CDN delay xảy ra:
  T=5.000s: buf = 1200ms, chunk tiếp theo đang download...
  T=5.320s: buf =  880ms, CDN delay! chunk chưa đến...
  T=5.640s: buf =  560ms, vẫn chưa đến...
  T=5.960s: buf =  240ms, vẫn chưa!
  T=6.080s: buf =    0ms → LOADING! ← chỉ chịu được ~880ms delay

  Với target 2s, buffer chỉ có ~1200–1800ms data phía trước.
  CDN delay > 1s = LOADING chắc chắn.
  CDN delay 500ms xảy ra thường xuyên = LOADING thường xuyên.
```

```
Tại sao bandwidth 100Mbps không giúp?

  Player download chunk 320ms (video ~200KB) trong ~2ms (ở 100Mbps).
  → Tốc độ download KHÔNG phải bottleneck.

  Bottleneck là: CHUNK CHƯA TỒN TẠI TRÊN CDN.
  Server encode 1 chunk mỗi 320ms (real-time). Dù mạng 1Gbps,
  player không thể download cái chưa được tạo ra.

  Ví dụ:
    14:00:00.000  Player cần chunk cho video 13:59:58.000–13:59:58.320
                  → Chunk này đã có trên CDN → download ngay (2ms)
    14:00:00.002  Player cần chunk cho video 13:59:58.320–13:59:58.640
                  → Chunk này CŨNG đã có → download ngay
    ...
    14:00:01.500  Player cần chunk cho video 13:59:59.680–14:00:00.000
                  → Chunk này server ĐANG encode → CHƯA CÓ → CHỜ!
                  → Nếu buffer đã cạn trong lúc chờ → LOADING!
```

### 8.4. Tại sao target 3s không loading?

```
Target 3s, segment 1.92s:

Buffer chứa ~1.5 segment phía trước (cushion 1.08s):
  → CDN delay 100ms  → vẫn còn 0.98s dư → OK
  → CDN delay 500ms  → vẫn còn 0.58s dư → OK
  → CDN delay 1000ms → vẫn còn 0.08s dư → vừa đủ

Log thực tế:
  liveOff=3180ms, target=3148ms, err=+30ms, speed=1.003, buf=2500ms
  → Buffer luôn dày ~2.5s, không bao giờ cạn
```

### 8.5. Target phụ thuộc CDN, không phải segment duration

Vì player download **từng chunk** (không đợi hết segment), segment duration không quyết định target tối thiểu. Yếu tố quyết định là **CDN delivery latency**:

| CDN | Đặc điểm | Target tối thiểu (test) | Lý do |
|---|---|---|---|
| `live-llc-wmk` | tsbd=15s, delay cao | **3.0s** | CDN delay ~500ms–1s, cần buffer dày |
| `event-llc-wtm` | tsbd=17s, delay thấp | **~2.0s** | CDN nhanh hơn, target 2s gần stable (chỉ 2 rebuffer ban đầu) |

**Muốn target 2s stable** → cần CDN có propagation delay < 500ms, không cần giảm segment duration.

Segment duration chỉ ảnh hưởng gián tiếp: segment lớn → chunk size lớn → download mỗi chunk lâu hơn → thêm jitter. Nhưng với CMAF chunk ~320ms ở bitrate 10Mbps, download chỉ mất ~2ms — không đáng kể.

### 8.6. Kết luận

- **Khuyến nghị: target = 3.0s** cho stream segment 1.92s
- User không nhận ra khác biệt 1s khi xem live
- Config khuyến nghị:

```java
MediaItem.LiveConfiguration liveConfig = new MediaItem.LiveConfiguration.Builder()
    .setTargetOffsetMs(3000)
    .setMinOffsetMs(2000)
    .setMaxOffsetMs(6000)
    .setMinPlaybackSpeed(0.95f)
    .setMaxPlaybackSpeed(1.08f)
    .build();
```

---

## 9. Tham chiếu

- Test manifest: `https://live-llc-wmk.fptplay53.net/.../EPL_HCM_01_HD_H265_Cmaf/cmaf-hvc-drm/index.mpd`
- DASH-IF low-latency: https://dashif.org/docs/CR-Low-Latency-Live-r8.pdf
- Media3 sources:
  - `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLivePlaybackSpeedControl.java`
  - `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java`
  - `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/dash/DashMediaSource.java` (hàm `updateLiveConfiguration()` — merge LiveConfiguration với ServiceDescription)
- Mã demo fork: `demos/main/src/main/java/androidx/media3/demo/main/lowlatency/LowLatencyProxy.java`