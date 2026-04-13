banChecco vthi # Low-Latency DASH — Chi tiết các thay đổi trong Media3 Fork

**Branch:** `fplay_dev_low_latency`
**Base:** Media3 `release` (v1.9.2)
**Version:** `1.9.2.dev.v1`
**Mục tiêu:** Đạt được `targetLiveOffset = 2s` cho LL-DASH stream với SegmentTimeline + Chunked Transfer

---

## Tổng quan vấn đề

Media3 gốc (upstream) xử lý LL-DASH với SegmentTemplate (fixed duration) tốt, nhưng với **SegmentTimeline** (explicit segment list) thì có nhiều vấn đề:

1. Player không request được segment đang được sản xuất (incomplete segment)
2. Target offset bị "dính" ở ~3s do speed control quá bảo thủ
3. Khi target đặt bằng 2s thì rebuffer liên tục (~13 lần/phút)

CDN của FPT Play (`event-llc-wtm.fptplay53.net`) sử dụng SegmentTimeline + ATO (availabilityTimeOffset), và Akamai hỗ trợ chunked transfer (gửi từng chunk ~300ms thay vì đợi hết 1.92s mới gửi).

---

## Sơ đồ các file thay đổi

```
constants.gradle                                    # Version bump
demos/main/src/main/assets/media.exolist.json       # Thêm stream test LL-DASH
demos/.../lowlatency/LowLatencyProxy.java           # [MỚI] Helper DRM credentials cho testing
libraries/exoplayer/src/.../util/LowLatencyLog.java # [MỚI] Hệ thống logging tập trung
libraries/exoplayer/src/.../ExoPlayerImplInternal.java       # Logging vị trí player
libraries/exoplayer/src/.../DefaultLivePlaybackSpeedControl.java  # Logging speed control
libraries/exoplayer_dash/src/.../DashMediaSource.java              # Fix 1: Sticky target
libraries/exoplayer_dash/src/.../DefaultDashChunkSource.java       # Logging chunk scheduling
libraries/exoplayer_dash/src/.../manifest/SegmentBase.java         # Fix 2: +1 segment extrapolation
```

---

## Fix 1: DashMediaSource — Sticky Target Offset

**File:** `DashMediaSource.java` dòng ~1081
**Vấn đề gốc:**

Trong `updateLiveConfiguration()`, Media3 gốc ưu tiên `localLiveConfiguration.targetOffsetMs` (giá trị nội bộ) trước `mediaItemLiveConfiguration.targetOffsetMs` (giá trị từ app):

```java
// Media3 gốc:
if (localLiveConfiguration.targetOffsetMs != C.TIME_UNSET) {
    targetOffsetMs = localLiveConfiguration.targetOffsetMs;  // Luôn lấy giá trị cũ
}
```

**Vấn đề:**
- Khi manifest refresh, `minLiveOffsetMs` có thể tạm thời tăng lên (do `windowDuration + minBufferTime`)
- Điều này ép `targetOffsetMs` tăng lên (ví dụ: 2000 → 3200ms)
- Lần refresh tiếp theo, `localLiveConfiguration` đã lưu giá trị 3200ms
- Giá trị 3200ms KHÔNG BAO GIỜ giảm xuống lại vì nó luôn được ưu tiên

**Thay đổi:**

```java
// FPlay fork:
if (mediaItemLiveConfiguration.targetOffsetMs != C.TIME_UNSET) {
    // Luôn ưu tiên giá trị từ MediaItem (app set) để target có thể quay về 2s
    targetOffsetMs = mediaItemLiveConfiguration.targetOffsetMs;
} else if (localLiveConfiguration.targetOffsetMs != C.TIME_UNSET) {
    targetOffsetMs = localLiveConfiguration.targetOffsetMs;
}
```

**Kết quả:** Target luôn quay về giá trị app mong muốn (2000ms) sau mỗi manifest refresh, thay vì bị "dính" ở giá trị cao.

---

## Fix 2: SegmentBase — Extrapolation cho SegmentTimeline

**File:** `SegmentBase.java`
**Vấn đề gốc:**

Với SegmentTimeline, manifest chứa danh sách các segment đã hoàn thành, ví dụ:

```
SegmentTimeline: [seg#0, seg#1, seg#2, ..., seg#7]  (8 entries)
                  |<---- đã xong ---->|
```

Segment #8 đang được CDN sản xuất nhưng CHƯA có trong timeline. Media3 gốc chỉ cho phép download seg#0-7, rồi **đợi manifest refresh** (1s) để biết về seg#8. Trong thời gian đợi, buffer không tăng và live offset tăng lên.

Với SegmentTemplate (fixed duration), Media3 gốc đã có logic +1 cho unbounded segments vì URL được tính từ template. Nhưng với SegmentTimeline, `getSegmentCount()` trả về giá trị bounded (ví dụ: 8), nên logic +1 **không bao giờ được chạy**.

**Giải pháp: Helper `isLowLatency()` + 5 chỗ sửa trong SegmentBase.java**

### Helper method: `isLowLatency()` (dòng ~180)

**Class:** `MultiSegmentBase` — kế thừa bởi cả `SegmentTemplate` và `SegmentList`

```java
/**
 * Returns whether this segment base is configured for low-latency DASH streaming.
 */
public final boolean isLowLatency() {
  return availabilityTimeOffsetUs != C.TIME_UNSET;
}
```

Tất cả 5 chỗ sửa bên dưới đều dùng `isLowLatency()` để đảm bảo logic extrapolation **chỉ chạy với LL-DASH stream**. Stream thường (không có `availabilityTimeOffset` trong manifest) không bị ảnh hưởng.

### Tổng quan 5 chỗ sửa

| # | Method | Class | Guard | Vai trò |
|---|--------|-------|-------|---------|
| 2a | `getSegmentDurationUs()` | `MultiSegmentBase` | `isLowLatency()` | Guard: tránh crash khi truy cập segment +1 |
| 2b | `getSegmentTimeUs()` | `MultiSegmentBase` | `isLowLatency()` | Guard: extrapolate thời gian cho segment +1 |
| 2c | `getAvailableSegmentCount()` | `MultiSegmentBase` | `isLowLatency()` | +1 cho **unbounded** path (template không có timeline) |
| 2d | `getSegmentUrl()` | `SegmentTemplate` | `isLowLatency()` | Guard: extrapolate tham số `time` cho URL template |
| 2e | `getAvailableSegmentCount()` | `SegmentTemplate` | `isLowLatency()` | +1 cho **bounded** path (template CÓ timeline) ← **chỗ chính cho FPT Play** |

### Tại sao có 2 chỗ +1 (2c và 2e)?

`getAvailableSegmentCount()` trong `MultiSegmentBase` có 2 nhánh:

```java
// MultiSegmentBase.getAvailableSegmentCount():
long segmentCount = getSegmentCount(periodDurationUs);
if (segmentCount != INDEX_UNBOUNDED) {
    return segmentCount;        // ← BOUNDED path: SegmentTimeline → return sớm
}
// ← UNBOUNDED path: SegmentTemplate fixed duration (không có timeline)
long availableCount = ...;
availableCount++;               // ← Chỗ 2c: +1 ở đây
return availableCount;
```

- Stream dùng **SegmentTimeline** → `getSegmentCount()` trả về số lượng thực (ví dụ 8) → đi vào **bounded path** → return sớm → **KHÔNG BAO GIỜ** đến được chỗ 2c
- Nên cần chỗ 2e: override trong `SegmentTemplate` để thêm +1 **sau khi** bounded path đã return

```
Stream FPT Play (SegmentTimeline + ATO):
  getSegmentCount() = 8 (bounded) → bounded path → return 8
  → SegmentTemplate.getAvailableSegmentCount() override: 8 + 1 = 9  ← Chỗ 2e

Stream khác (SegmentTemplate fixed duration, không có timeline):
  getSegmentCount() = INDEX_UNBOUNDED → unbounded path → +1 ở chỗ 2c
```

### 2a. `getSegmentDurationUs()` — Guard cho segment ngoài timeline (dòng ~209)

**Class:** `MultiSegmentBase` (ảnh hưởng cả `SegmentTemplate` và `SegmentList`)

```java
if (segmentTimeline != null) {
    int idx = (int) (sequenceNumber - startNumber);
    // LL-Core: Chỉ với LL-DASH, nếu idx vượt quá timeline thì dùng duration segment cuối
    if (idx >= segmentTimeline.size() && isLowLatency()) {
        idx = segmentTimeline.size() - 1;
    }
    long duration = segmentTimeline.get(idx).duration;
    return (duration * C.MICROS_PER_SECOND) / timescale;
}
```

**Trước:** `segmentTimeline.get(8)` → crash `IndexOutOfBoundsException`
**Sau (LL-DASH):** `idx=8 >= size=8 && isLowLatency()` → `idx=7` → lấy duration của seg#7 (1.92s)
**Sau (non-LL):** `isLowLatency()=false` → không thay đổi idx → hành vi gốc

### 2b. `getSegmentTimeUs()` — Extrapolate thời gian (dòng ~227)

**Class:** `MultiSegmentBase`

```
Timeline:  [seg#0: t=0, d=1920000] [seg#1: t=1920000, d=1920000] ... [seg#7: t=13440000, d=1920000]
            idx=0                   idx=1                              idx=7

Yêu cầu seg#8 (idx=8):
  → idx >= timeline.size() (8 >= 8) → extrapolate
  → last = seg#7: startTime=13440000, duration=1920000
  → seg#8 time = 13440000 + 1920000 * 1 = 15360000
```

```java
public final long getSegmentTimeUs(long sequenceNumber) {
    if (segmentTimeline != null) {
        int idx = (int) (sequenceNumber - startNumber);
        if (idx < segmentTimeline.size()) {
            // Segment có trong timeline → lấy trực tiếp
            unscaledSegmentTime = segmentTimeline.get(idx).startTime - presentationTimeOffset;
        } else if (isLowLatency()) {
            // LL-Core: Chỉ LL-DASH — extrapolate thời gian cho segment ngoài timeline
            SegmentTimelineElement last = segmentTimeline.get(segmentTimeline.size() - 1);
            long segmentsBeyond = idx - segmentTimeline.size() + 1;
            unscaledSegmentTime = (last.startTime + last.duration * segmentsBeyond)
                                  - presentationTimeOffset;
        } else {
            // Non-LL: fallback sang segment cuối (không nên xảy ra trong flow bình thường)
            unscaledSegmentTime = segmentTimeline.get(segmentTimeline.size() - 1).startTime
                                  - presentationTimeOffset;
        }
    }
}
```

**Trước:** `segmentTimeline.get(8)` → `IndexOutOfBoundsException: Index: 8, Size: 8`
**Sau (LL-DASH):** `idx=8 >= size=8 && isLowLatency()` → extrapolate `time = 13440000 + 1920000 = 15360000`
**Sau (non-LL):** `isLowLatency()=false` → fallback lấy thời gian segment cuối → hành vi gốc

### 2c. `MultiSegmentBase.getAvailableSegmentCount()` — +1 cho unbounded path (dòng ~284)

**Class:** `MultiSegmentBase`
**Áp dụng cho:** SegmentTemplate **không có** timeline (fixed duration)

```java
// Unbounded path (SegmentTemplate fixed duration):
long availableCount = firstIncompleteSegmentNum - firstAvailableSegmentNum;
if (isLowLatency()) {
    availableCount++;  // +1 cho LL-DASH chunked transfer
}
return (int) availableCount;
```

An toàn vì `getSegmentTimeUs` tính bằng công thức `(sequenceNumber - startNumber) * duration`, không truy cập array.

**Lưu ý:** Stream FPT Play dùng SegmentTimeline → **không đi vào nhánh này**. Chỗ này phục vụ các stream LL-DASH khác dùng fixed duration template.

### 2d. `SegmentTemplate.getSegmentUrl()` — Extrapolate tham số `time` cho URL (dòng ~459)

**Class:** `SegmentTemplate`

```java
public RangedUri getSegmentUrl(Representation representation, long sequenceNumber) {
    long time;
    if (segmentTimeline != null) {
        int idx = (int) (sequenceNumber - startNumber);
        if (idx < segmentTimeline.size()) {
            time = segmentTimeline.get(idx).startTime;
        } else if (isLowLatency()) {
            // LL-Core: Chỉ LL-DASH — extrapolate time cho segment ngoài timeline
            SegmentTimelineElement last = segmentTimeline.get(segmentTimeline.size() - 1);
            long segmentsBeyond = idx - segmentTimeline.size() + 1;
            time = last.startTime + last.duration * segmentsBeyond;
        } else {
            // Non-LL: fallback sang segment cuối
            time = segmentTimeline.get(segmentTimeline.size() - 1).startTime;
        }
    }
    // Template sử dụng `time` và `sequenceNumber` để xây URL
    String uriString = mediaTemplate.buildUri(
        representation.format.id, sequenceNumber, representation.format.bitrate, time);
}
```

**Ví dụ URL được tạo:**
```
Template: media_$Number$.m4s?t=$Time$
Seg#7 (trong timeline): media_7.m4s?t=13440000     ← lấy từ timeline
Seg#8 (extrapolated):   media_8.m4s?t=15360000     ← tính: 13440000 + 1920000
```

CDN (Akamai) nhận request cho seg#8 → bắt đầu gửi chunks ngay khi có dữ liệu (~300ms/chunk).

**Tại sao chỉ cần guard trong `SegmentTemplate` mà không cần trong `SegmentList`?**
- `SegmentTemplate.getSegmentUrl()` xây URL từ template → có thể tính URL cho bất kỳ segment nào
- `SegmentList.getSegmentUrl()` dùng `mediaSegments.get(idx)` → KHÔNG THỂ tạo URL cho segment chưa có trong danh sách
- Chỗ 2e (override +1) chỉ áp dụng cho `SegmentTemplate`, nên `SegmentList` không bao giờ nhận được segment +1 → không cần guard

### 2e. `SegmentTemplate.getAvailableSegmentCount()` — Override +1 cho bounded path (dòng ~477)

**Class:** `SegmentTemplate` (override method từ `MultiSegmentBase`)
**Đây là chỗ chính hoạt động cho stream FPT Play.**

```java
@Override
public long getAvailableSegmentCount(long periodDurationUs, long nowUnixTimeUs) {
    long count = super.getAvailableSegmentCount(periodDurationUs, nowUnixTimeUs);
    // LL-Core: Chỉ cho SegmentTemplate với SegmentTimeline + LL-DASH
    if (segmentTimeline != null && isLowLatency()) {
        return count + 1;  // Báo cho player: "có thêm 1 segment nữa để download"
    }
    return count;
}
```

**Điều kiện kích hoạt:**
- `segmentTimeline != null`: Stream dùng SegmentTimeline (không phải fixed duration)
- `isLowLatency()`: Stream là LL-DASH (có ATO trong manifest)
- Cả 2 điều kiện đều đúng cho stream FPT Play

### Tổng kết Fix 2 — Luồng dữ liệu trước và sau

```
=== TRƯỚC FIX 2 (Media3 gốc) ===

Timeline có seg#0-7. Player download hết 7 segment:
  t=0.0s  LOAD seg#0  buf=0ms
  t=0.1s  LOAD seg#1  buf=1920ms
  ...
  t=0.5s  LOAD seg#7  buf=13440ms
  t=0.5s  WAIT — không còn segment nào để download
  t=1.5s  Manifest refresh → timeline có seg#1-8
  t=1.5s  LOAD seg#8  buf+=1920ms (nhảy 1 bước lớn)
  → Buffer tăng theo bước 1920ms, gây oscillation lớn

=== SAU FIX 2 (FPlay fork) ===

Timeline có seg#0-7, nhưng getAvailableSegmentCount trả về 9 (chỗ 2e):
  t=0.5s  LOAD seg#7  buf=13440ms
  t=0.5s  LOAD seg#8  ← request segment đang sản xuất
           → getSegmentTimeUs(8) extrapolate thời gian (chỗ 2b)
           → getSegmentDurationUs(8) dùng duration seg#7 (chỗ 2a)
           → getSegmentUrl(8) extrapolate time cho URL (chỗ 2d)
           CDN gửi chunk 1: buf+=240ms
           CDN gửi chunk 2: buf+=240ms
           ...
           CDN gửi chunk 8: buf+=240ms (tổng 1920ms)
  → Buffer tăng đều ~240ms/chunk, giảm oscillation
```

### Sơ đồ quan hệ 5 chỗ sửa

```
Player muốn download segment +1 (ngoài timeline):

  ① getAvailableSegmentCount() trả về count+1
     ├─ Bounded path (SegmentTimeline) → Chỗ 2e (SegmentTemplate override)  ← FPT Play
     └─ Unbounded path (fixed duration) → Chỗ 2c (MultiSegmentBase)

  ② Player tính thời gian segment +1:
     └─ getSegmentTimeUs() → Chỗ 2b (extrapolate từ segment cuối)

  ③ Player tính duration segment +1:
     └─ getSegmentDurationUs() → Chỗ 2a (dùng duration segment cuối)

  ④ Player xây URL để download:
     └─ getSegmentUrl() → Chỗ 2d (extrapolate tham số time cho template)

  ⑤ HTTP request → CDN → chunked transfer → buffer tăng đều
```

---

## Logging System — LowLatencyLog

**File mới:** `LowLatencyLog.java`
**Mục đích:** Tập trung mọi log LL-DASH vào 1 tag duy nhất `LL-Core`, dễ filter: `adb logcat -s LL-Core:D`

### 3 chế độ log:

| Mode | Giá trị | Mô tả |
|------|---------|-------|
| `MODE_OFF` | 0 | Tắt hết log |
| `MODE_FULL` | 1 | Log tất cả: tick mỗi 500ms, DashMerge mỗi MUP, mọi speed change |
| `MODE_REBUFFER_TEST` | 2 | Chỉ log khi liên quan rebuffer: REBUFFER marker, targetAdjust, tick khi buf thấp |

**Mặc định:** `MODE_REBUFFER_TEST` — đủ thông tin để debug rebuffer mà không spam logcat.

### Các điểm instrumentation:

#### a. `[DashMerge]` — DashMediaSource.updateLiveConfiguration()

Log mỗi manifest refresh (hoặc 1 lần đầu trong REBUFFER_TEST mode):
```
[DashMerge] manifest: mup=1000ms tsbd=15000ms minBuf=1000ms windowDur=17261ms nowInWindow=14967ms
[DashMerge] mediaItem in: target=2000ms min=1500ms max=5000ms speed=[0.950,1.080]
[DashMerge] serviceDesc  : target=5000ms min=4000ms max=7000ms speed=[0.930,1.070]
[DashMerge] merged   OUT: target=2000ms min=1500ms max=5000ms speed=[0.950,1.080]
```

**Tại sao cần:** Xác nhận giá trị app (target=2000ms) thắng giá trị manifest (target=5000ms).

#### b. `[SpeedCtrl]` — DefaultLivePlaybackSpeedControl

```
[SpeedCtrl] tick: liveOff=2018ms curTarget=2000ms idealTarget=2000ms err=+18ms speed=1.000 buf=999ms minPossible=1019ms
[SpeedCtrl] targetAdjust: 2000ms → 2017ms (ideal=2000ms liveOff=2017ms safe=2124ms sMinP=890ms sDev=411ms)
[SpeedCtrl] speedChange: 1.000 → 1.080 (err=+3099ms target=2000ms liveOff=5099ms bounds=[0.950,1.080])
[SpeedCtrl] REBUFFER: target 2018ms → 2518ms (increment=500ms, max=5000ms, clamped=false)
```

**Giải thích các giá trị:**
- `liveOff`: Khoảng cách từ vị trí phát đến live edge (mục tiêu: 2000ms)
- `curTarget`: Target hiện tại (có thể bị đẩy lên bởi rebuffer hoặc safe offset)
- `idealTarget`: Target lý tưởng từ app (luôn 2000ms)
- `err`: `liveOff - curTarget` (dương = đang xa hơn mục tiêu, âm = đang gần hơn)
- `speed`: Tốc độ phát hiện tại (1.000 = bình thường, >1 = tăng tốc để bắt kịp)
- `sMinP` (smoothedMinPossible): Ước lượng buffer tối thiểu có thể (dùng để tính safe offset)
- `sDev`: Độ lệch của sMinP
- `safe`: `sMinP + 3*sDev` — target không được thấp hơn giá trị này

#### c. `[ChunkSched]` — DefaultDashChunkSource

```
[ChunkSched] LOAD[V]: seg#42 [27179735ms..27181655ms] lastAvail#43 bufDur=1658ms
[ChunkSched] WAIT[A]: seg#43 > lastAvail#42, bufDur=1804ms nowPeriod=27181896ms
```

**Giải thích:**
- `LOAD[V]`: Đang download segment video, `LOAD[A]`: audio
- `seg#42 [start..end]`: Số thứ tự và khoảng thời gian của segment (ms)
- `lastAvail#43`: Segment cao nhất có thể download (bao gồm +1 extrapolated)
- `WAIT`: Player muốn download nhưng segment chưa available
- `bufDur`: Lượng buffer hiện tại (ms)

#### d. `[PlayerPos]` — ExoPlayerImplInternal

```
[PlayerPos] liveEdge=18299ms pos=16280ms bufEnd=17279ms buf=999ms liveOff=2018ms gap=1020ms
[PlayerPos] REBUFFER! liveEdge=15273ms pos=13239ms bufEnd=13421ms buf=182ms liveOff=2033ms gap=1852ms
```

**Giải thích:**
- `liveEdge`: Vị trí live edge trong window (ms)
- `pos`: Vị trí đang phát (ms)
- `bufEnd`: Điểm cuối của buffer (ms)
- `buf`: `bufEnd - pos` = lượng buffer khả dụng
- `liveOff`: Khoảng cách đến live edge = `liveEdge - pos`
- `gap`: `liveEdge - bufEnd` = dữ liệu chưa download giữa buffer và live edge

---

## Demo & Testing Setup

### media.exolist.json — Thêm stream test

```json
{
    "name": "EPL HCM 01 HD H265 (targetOffset=2s)",
    "uri": "https://live-wmk.fptplay53.net/.../index.mpd",
    "drm_scheme": "widevine",
    "drm_license_uri": "https://license.sigmadrm.com/license/verify/widevine"
}
```

### LowLatencyProxy.java — Helper cho SIGMA DRM

Chứa hardcoded credentials (userId, sessionId, merchantId) để test nhanh mà không cần auth flow:
- `createDrmConfiguration()`: Tạo DRM config cho Widevine + SIGMA
- `createHeader()`: Tạo HTTP headers cho license request
- `TARGET_OFFSET_MS = 2000L`: Giá trị target cho testing

**Lưu ý:** Không nên dùng packageId thực của fplay hoặc reuse sessionId trong production — đã gây block device ngày 2026-04-08.

---

## Kết quả test thực tế (2026-04-12)

### Cấu hình

```java
MediaItem.LiveConfiguration:
  targetOffset = 2000ms
  minOffset    = 1500ms
  maxOffset    = 5000ms
  minSpeed     = 0.950
  maxSpeed     = 1.080
```

### Log 7.5 phút (21:47:23 → 21:55:00)

| Metric | Giá trị |
|--------|---------|
| Target đạt được | **2000ms** (đạt sau ~11s từ khi bắt đầu) |
| Live offset ổn định | **~2017-2020ms** |
| Speed ổn định | **1.000** (không cần điều chỉnh) |
| Buffer ổn định | **~990-1000ms** (~0.5 segment) |
| Số rebuffer | **5 events / 3 incidents** trong 7.5 phút |
| Rebuffer rate | **~0.7 lần/phút** |

### So sánh trước/sau Fix 2

| | Trước Fix 2 | Sau Fix 2 |
|---|---|---|
| Target=2s, rebuffer rate | ~13 lần/phút | ~0.7 lần/phút |
| Target=3s, rebuffer rate | 0 lần/phút | — |
| Buffer growth | 1920ms/bước | ~240ms/chunk |
| Live offset ổn định | 3.0-3.5s | 2.0-2.02s |

### Chi tiết 5 rebuffer events

| Thời gian | Buffer trước | Nguyên nhân có thể |
|-----------|-------------|-------------------|
| 21:49:35.897 | 182ms | Buffer drain đột ngột (492ms→182ms) |
| 21:49:36.718 | 679ms | Double-rebuffer (cùng incident, 0.8s sau) |
| 21:51:00.522 | 46ms | Network jitter hoặc CDN BytePlus |
| 21:51:14.098 | 66ms | ~14s sau, tương tự |
| 21:54:20.201 | 46ms | Sau 3 phút ổn định, 1 lần nữa |

**Nhận xét:** Buffer giảm ĐỘT NGỘT (từ ~1000ms xuống ~46ms), không giảm TỪ TỪ. Đây có thể là do CDN BytePlus (chunk timing không ổn định) hoặc network jitter. Recovery nhanh (~15-25s để target quay về 2000ms) nhờ Fix 1.

---

## Ghi chú kỹ thuật quan trọng

### Tại sao +1 chỉ an toàn với SegmentTemplate, không phải SegmentList?

| Class | getSegmentUrl() | +1 an toàn? |
|-------|----------------|-------------|
| `SegmentTemplate` | `mediaTemplate.buildUri(id, number, bitrate, time)` — tính từ template | **CÓ** — URL được xây từ công thức |
| `SegmentList` | `mediaSegments.get(idx)` — lấy từ danh sách | **KHÔNG** — idx vượt quá list → crash |

### 3 điểm cần guard khi extrapolate — tất cả dùng `isLowLatency()`

| Method | Vị trí array access | Guard |
|--------|-------------------|-------|
| `getSegmentTimeUs()` | `segmentTimeline.get(idx).startTime` | `if (idx < size)` else `if (isLowLatency())` extrapolate |
| `getSegmentDurationUs()` | `segmentTimeline.get(idx).duration` | `if (idx >= size && isLowLatency())` → `idx = size-1` |
| `SegmentTemplate.getSegmentUrl()` | `segmentTimeline.get(idx).startTime` | `if (idx < size)` else `if (isLowLatency())` extrapolate |

**Non-LL streams:** Tất cả guard đều kiểm tra `isLowLatency()` → nếu `false`, code chạy theo hành vi gốc của Media3 upstream → **không bị ảnh hưởng**.

### Speed control — Tại sao target bị dính ở 3s (trước Fix 2)?

`DefaultLivePlaybackSpeedControl.adjustTargetLiveOffsetUs()` sử dụng:
```
safeOffset = smoothedMinPossible + 3 * deviation
```

- `smoothedMinPossible` dùng `max()` nên **tăng NGAY LẬP TỨC** khi buffer giảm
- Smoothing factor = 0.999 nên **cần ~7000 lần gọi để giảm 50%**
- Buffer oscillation lớn (1920ms/bước) → `smoothedMinPossible` luôn cao → `safeOffset` > 2s → target bị kéo lên

Sau Fix 2, buffer tăng đều 240ms/chunk → oscillation giảm → `smoothedMinPossible` thấp hơn → target đạt được 2s.

### CDN notes

- **Akamai** (70% traffic): Chunked transfer ổn định — gửi chunk đều đặn ~240ms/chunk, Fix 2 hoạt động tốt
- **BytePlus** (30% traffic): Cũng dùng chunked transfer nhưng **timing không ổn định** — có thể buffer/gom nhiều chunk trước khi flush, nên client nhận data không đều. Không phải "gửi whole segment" mà là chunk delivery không được optimize cho low-latency
- Phân biệt CDN bằng header `cdn53: byteplus`