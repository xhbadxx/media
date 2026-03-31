# Media3 Short-Form Video: PlayerPool & Preload Mechanism

## Tổng quan kiến trúc / Architecture Overview

Short-form video (kiểu TikTok) trong Media3 demo sử dụng **4 thực thể độc lập** phối hợp với nhau:

```
┌─────────────────────────────────────────────────────────┐
│                    System has 4 things:                   │
│                                                          │
│  1. PreloadManager  ← runs on its OWN preload thread     │
│  2. Player A        ← runs on its OWN playback thread    │
│  3. Player B        ← runs on its OWN playback thread    │
│  4. Player C        ← runs on its OWN playback thread    │
│                                                          │
│  They are ALL independent. PreloadManager is NOT          │
│  "inside" any player.                                    │
└─────────────────────────────────────────────────────────┘
```

| Thành phần | Vai trò |
|---|---|
| **PlayerPool** | Quản lý pool ExoPlayer instances, tái sử dụng thay vì tạo/hủy |
| **PreloadManager** | Download data trước cho các video CHƯA có player nào nhận |
| **ViewPagerMediaHolder** | Gán player vào view, gọi `prepare()` và `play()` |
| **DefaultLoadControl** | Shared instance, quyết định loading cho cả PreloadManager và tất cả players |

---

## 1. PlayerPool - Quản lý Pool ExoPlayer

### Cấu trúc dữ liệu

```
PlayerPool(numberOfPlayers = 3)
│
├── playerMap: BiMap<Int, ExoPlayer>       // Tất cả player đã tạo
├── availablePlayerQueue: Queue<Int>        // Player rảnh, sẵn sàng dùng
├── playerRequestTokenSet: Set<Int>         // Token đang chờ player
└── playerFactory: DefaultPlayerFactory     // Tạo player mới
```

### Vòng đời Player trong Pool

```
┌──────────────┐     acquirePlayer()     ┌──────────────┐
│   Available   │ ─────────────────────► │   In Use      │
│   (in queue)  │                        │   (by holder) │
└──────────────┘ ◄───────────────────── └──────────────┘
                     releasePlayer()
                     - player.stop()
                     - player.clearMediaItems()
                     - KHÔNG gọi player.release()
```

### 3 trường hợp khi acquirePlayer()

| # | Điều kiện | Hành động |
|---|---|---|
| 1 | Pool có player rảnh (`availablePlayerQueue` không rỗng) | Lấy player ra, gán cho token ngay |
| 2 | Chưa tạo đủ player (`playerMap.size < numberOfPlayers`) | Tạo player mới, gán cho token |
| 3 | Hết player (pool đầy, tất cả đang dùng) | Retry sau 500ms, lặp lại cho đến khi có player |

### DefaultPlayerFactory - Cấu hình mỗi Player

```kotlin
override fun createPlayer(): ExoPlayer {
    val player = preloadManagerBuilder.buildExoPlayer()
    player.addAnalyticsListener(EventLogger("player-$playerCounter"))
    player.repeatMode = ExoPlayer.REPEAT_MODE_ONE  // Loop video
    return player
}
```

Mỗi player được tạo qua `preloadManagerBuilder.buildExoPlayer()` - đảm bảo chia sẻ cùng cấu hình LoadControl và Cache với PreloadManager.

### Số lượng Player tối ưu = (offscreenPageLimit x 2) + 1

`ViewPagerActivity.kt` set `offscreenPageLimit = 1`, nghĩa là ViewPager2 giữ:

```
1 offscreen left + 1 current + 1 offscreen right = 3 PlayerViews attached
```

| | 3 Players | 5 Players | Khác biệt |
|---|---|---|---|
| PlayerViews attached | 3 | 3 (vẫn chỉ 3) | Không đổi |
| Players used | 3 | 3 | 2 players dư, lãng phí |
| RAM | ~30-45MB | ~50-75MB | +20-30MB wasted |
| Swipe smoothness | Instant | Instant | Không cải thiện |

```
Rule: numberOfPlayers = (offscreenPageLimit × 2) + 1

offscreenPageLimit = 1 → 3 players optimal
offscreenPageLimit = 2 → 5 players optimal

More players than PlayerViews = wasted resources, zero UX benefit.
```

---

## 2. Shared Components - Tất cả chia sẻ cùng 1 instance

### Kiến trúc chia sẻ

`DefaultPreloadManager.Builder` tạo tất cả players và PreloadManager với **cùng** internal components:

```
preloadManagerBuilder
    │
    ├── buildExoPlayer() → Player A ──┐
    ├── buildExoPlayer() → Player B ──┤── All share:
    ├── buildExoPlayer() → Player C ──┘
    │       │
    │       ├── Same BandwidthMeter (1 instance)
    │       │     → All players share bandwidth estimation
    │       │     → If Player A measures 10Mbps, Player B knows too
    │       │
    │       ├── Same LoadControl config
    │       │     → minBuffer=5s, maxBuffer=20s, playbackBuffer=500ms
    │       │     → BUT each player has its own PlayerLoadingState
    │       │
    │       ├── Same Allocator (DefaultAllocator)
    │       │     → Shared memory pool for buffer allocation
    │       │     → Players compete for the same memory
    │       │
    │       └── Same Cache (SimpleCache)
    │             → Shared disk cache across all players
    │
    └── build() → PreloadManager
            → Uses same BandwidthMeter, Cache, Allocator
            → Coordinates preloading priority across all sources
```

### Shared BandwidthMeter - Quan trọng nhất

```
Without sharing:
  Player A estimates: 5 Mbps  (just started, inaccurate)
  Player B estimates: 5 Mbps  (also just started, inaccurate)
  → Each player starts with cold estimation → may pick wrong ABR quality

With sharing (current code):
  Player A measures: 10 Mbps after playing for 30s
  Player B gets created → immediately knows: 10 Mbps
  → Picks correct ABR quality from the first segment
```

### Shared Cache - Không download trùng lặp

```
PreloadManager downloads video 6 to cache
    ↓
Player C calls prepare() for video 6
    ↓
Reads from same cache → no network request needed
```

---

## 3. PreloadManager - Download Data Trước

### Cấu hình LoadControl

```kotlin
LoadControl:
  minBuffer      = 5,000ms    // Threshold để bắt đầu load lại
  maxBuffer      = 20,000ms   // Threshold để dừng load
  playbackBuffer = 500ms      // Chỉ cần 500ms là bắt đầu phát được
  prioritizeTimeOverSize = true
```

### Chiến lược Preload theo khoảng cách

Khi user đang xem video ở vị trí `currentPlayingIndex`, PreloadManager quyết định tải bao nhiêu cho mỗi video khác:

| Khoảng cách từ video hiện tại | Chiến lược | Giải thích |
|---|---|---|
| ±1 video (prev/next) | `specifiedRangeLoaded(1000ms)` | Load 1000ms vào RAM - sẵn sàng phát ngay |
| ±2-3 video | `specifiedRangeLoaded(1000ms)` | Load 1000ms vào RAM |
| >3 video | `specifiedRangeCached(5000ms)` | Cache 5000ms xuống disk - không giữ trong RAM |

**Sự khác biệt quan trọng:**
- `specifiedRangeLoaded` → Data nằm trong **RAM** (SampleQueue) → đọc cực nhanh
- `specifiedRangeCached` → Data nằm trên **disk** → cần đọc từ disk nhưng không cần network

### PreloadManager preload TUẦN TỰ, từng item một

PreloadManager **không** download tất cả cùng lúc. Nó download **ONE by ONE** theo thứ tự priority:

```
currentPlayingIndex = 6

Priority list (sorted by distance):
  1st: item 7 (distance=1) → download 1000ms → done → advance
  2nd: item 5 (distance=1) → download 1000ms → done → advance
  3rd: item 8 (distance=2) → download 1000ms → done → advance
  4th: item 4 (distance=2) → download 1000ms → done → advance
  5th: item 9 (distance=3) → download 1000ms → ...
  ...
```

### Khi chuyển video: invalidate() re-sort, KHÔNG download lại

Khi `setCurrentPlayingIndex` thay đổi (6→7):

```
invalidate()
    ↓
resetSourceHolderPriorityList()  ← RE-SORT by distance from 7
    ↓
New priority: item 8, item 6, item 9, item 5, item 10, item 4...
    ↓
Start from index 0: check item 8
    ↓
Item 8 already has 1000ms buffered
    ↓
onContinueLoadingRequested: target(1000ms) > buffered(1000ms)?
    1000 > 1000 = FALSE → SKIP (no re-download)
    ↓
Advance to next item...
```

PreloadManager kiểm tra `bufferedDurationUs >= targetDurationMs`. Nếu đủ rồi → skip, advance sang item tiếp theo. **Không bao giờ download lại data đã có.**

### Cửa sổ trượt (Sliding Window) - 20 Items

PreloadManager chỉ quản lý **20 items** tại một thời điểm, không phải toàn bộ danh sách:

```
Tổng 1000 video, user đang ở video 30:

PreloadManager quản lý: [video 21 ─────── video 40]  ← 20 items
                                    ↑
                              video 30 (đang phát)

Khi user tới gần cuối cửa sổ (video 38):
  → Thêm 4 items bên phải: [video 41..44]
  → Xóa 4 items bên trái:  [video 21..24]
  → Cửa sổ mới: [video 25 ─────── video 44]
```

Đảm bảo không tốn RAM cho hàng trăm videos, chỉ quản lý vùng lân cận.

### Cache Configuration

```kotlin
SimpleCache + NoOpCacheEvictor
// Cache KHÔNG BAO GIỜ bị xóa
// Video đã tải 1 lần → lần sau đọc từ cache, không cần network
```

---

## 4. PreloadMediaSource - Cầu nối giữa PreloadManager và Player

### Cùng 1 object, KHÔNG download lại

```
Step 1: PreloadManager tạo PreloadMediaSource cho video 6
─────────────────────────────────────────────────────────
  preloadManager.add(mediaItem6, rankingData=6)
  → Creates PreloadMediaSource (wraps real MediaSource)
  → preloadMediaSource.preload(startPositionUs)
  → setPlayerId(PlayerId.PRELOAD)
  → Downloads ~1000ms data into SampleQueue (RAM)

Step 2: ViewHolder gets the SAME object
─────────────────────────────────────────────────────────
  // ViewPagerMediaAdapter.kt line 99
  var currentMediaSource = preloadManager.getMediaSource(mediaItem6)
  // Returns the SAME PreloadMediaSource object
  holder.bindData(currentMediaSource)

Step 3: Player prepare() with the SAME PreloadMediaSource
─────────────────────────────────────────────────────────
  player.setMediaSource(mediaSource)  // same object
  player.prepare()
  → ExoPlayer calls: mediaSource.createPeriod(id, allocator, startPos)
```

### createPeriod() - Tái sử dụng data đã preload

```java
public PreloadMediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    MediaPeriodKey key = new MediaPeriodKey(id, startPositionUs);

    // KEY CHECK: Does a preloaded period already exist?
    if (preloadingMediaPeriodAndKey != null && key.equals(preloadingMediaPeriodAndKey.second)) {
        // YES! Reuse the preloaded MediaPeriod (with its SampleQueue data)
        PreloadMediaPeriod mediaPeriod = preloadingMediaPeriodAndKey.first;
        preloadingMediaPeriodAndKey = null;           // transfer ownership
        playingPreloadedMediaPeriodAndId = new Pair<>(mediaPeriod, id);
        return mediaPeriod;  // RETURNS EXISTING — no re-download!
    }

    // Only creates new period if no preloaded data exists
    return new PreloadMediaPeriod(mediaSource.createPeriod(id, allocator, startPositionUs));
}
```

### onUsedByPlayer() - PreloadManager nhường quyền

```java
public void onUsedByPlayer(PreloadMediaSource mediaSource) {
    // Tell PreloadManager: "skip this one, a real player owns it now"
    DefaultPreloadManager.this.onSkipped(mediaSource, ...);
}
```

### Handoff hoàn chỉnh - Không download trùng

```
PreloadManager downloads:     [0ms ──── 1000ms]  (PRELOAD rules, size-only)
                                        │
                              ┌─────────┘
                              ↓ HANDOFF (same SampleQueue, same object)
Player C continues from:     [1000ms ── 20000ms] (REAL player rules, time+size)

Total network usage: 0-20000ms downloaded ONCE
NO duplicate download of 0-1000ms
```

---

## 5. shouldContinueLoading - Cùng LoadControl, khác logic

### Hai path khác nhau trong cùng 1 method

```java
public boolean shouldContinueLoading(Parameters parameters) {
    boolean targetBufferSizeReached =
        getTotalBufferBytesAllocated(playerId) >= getTargetBufferBytes(playerId);

    // PATH 1: PRELOAD — simple check, size only
    if (playerId.equals(PlayerId.PRELOAD)) {
        return !targetBufferSizeReached;
    }

    // PATH 2: REAL player — complex check, time + size
    if (bufferedDurationUs < minBufferUs) {
        isLoading = prioritizeTimeOverSizeThresholds || !targetBufferSizeReached;
    } else if (bufferedDurationUs >= maxBufferUs || targetBufferSizeReached) {
        isLoading = false;
    }
    // Else: don't change isLoading (hysteresis)
    return isLoading;
}
```

### So sánh hai path

```
┌──────────────────────┬──────────────────────────────────────┐
│   PreloadManager     │   Player A, B, C (real players)      │
│   playerId = PRELOAD │   playerId = player-0, player-1...   │
├──────────────────────┼──────────────────────────────────────┤
│  Only checks:        │  Checks BOTH:                        │
│  ✅ targetBufferSize │  ✅ targetBufferSize                  │
│  ❌ bufferDuration   │  ✅ bufferDuration (min/max)          │
│  ❌ playbackSpeed    │  ✅ playbackSpeed scaling             │
│                      │                                      │
│  return:             │  3-way logic:                         │
│  !sizeReached        │  < minBuffer → load                  │
│                      │  >= maxBuffer → stop                 │
│                      │  between → don't change (hysteresis) │
└──────────────────────┴──────────────────────────────────────┘
```

### Hysteresis: Player paused (prev/next) load đến maxBuffer, KHÔNG PHẢI minBuffer

Logic `shouldContinueLoading` **KHÔNG phân biệt** playing vs paused. Nó chỉ check `bufferedDurationUs`:

```
Player A (paused, item 5):

  buffer: 1000ms (from preload)
  1000ms < 5000ms (minBuffer) → isLoading = TRUE → load

  buffer: 5000ms
  5000ms < 5000ms? NO
  5000ms >= 20000ms (maxBuffer)? NO
  → ELSE branch: isLoading STAYS TRUE → KEEP LOADING

  buffer: 10000ms
  Same ELSE → isLoading stays TRUE → KEEP LOADING

  buffer: 20000ms
  20000ms >= 20000ms → isLoading = FALSE → STOP
```

**Tất cả 3 players đều load đến maxBuffer (20s) hoặc hết video:**

```
Player A (paused, item 5): loads 1s → 20s (or end of video)
Player B (playing, item 6): loads 1s → 20s (or end of video)
Player C (paused, item 7): loads 1s → 20s (or end of video)
```

Với short video (15s): tất cả 3 player đều load **TOÀN BỘ video** vì video ngắn hơn maxBuffer.

**minBuffer (5s)** chỉ là threshold để **bắt đầu load lại** nếu đang KHÔNG load (isLoading=false). Nó KHÔNG phải threshold để dừng.

---

## 6. targetBufferBytes - Giới hạn theo bytes

### Tính 1 lần khi onTracksSelected

```java
public void onTracksSelected(Parameters params, ..., ExoTrackSelection[] trackSelections) {
    loadingStates.get(playerId).targetBufferBytes =
        calculateTargetBufferBytes(params, trackSelections);
    updateAllocator();
}
```

Gọi **1 lần** sau `prepare()` khi tracks được chọn. Không tính lại trừ khi track thay đổi.

### Công thức: Cố định theo track TYPE, KHÔNG theo bitrate

```java
private static int getDefaultBufferSize(int trackType, boolean isLocalPlayback) {
    switch (trackType) {
      case VIDEO:  return isLocalPlayback ? 300 * 64KB : 2000 * 64KB;
      case AUDIO:  return 200 * 64KB;
      case TEXT:   return 2 * 64KB;
      // ...
    }
}
```

`C.DEFAULT_BUFFER_SEGMENT_SIZE = 64KB` (1 block = 64KB)

Số 2000 và 200 là **số lượng block (segment) cố định**. Không liên quan gì đến bitrate hay chất lượng của track hiện tại:

```
Video 480p bitrate 1Mbps   → 2000 × 64KB = 125 MB
Video 4K  bitrate 20Mbps   → 2000 × 64KB = 125 MB   ← SAME

Audio 64kbps  → 200 × 64KB = 12.5 MB
Audio 320kbps → 200 × 64KB = 12.5 MB                 ← SAME
```

### Bảng tính đầy đủ

| Track Type | Blocks | × 64KB | = Size | Ghi chú |
|---|---|---|---|---|
| Video (streaming) | 2000 | × 64KB | **125 MB** | |
| Video (local) | 300 | × 64KB | 18.75 MB | |
| Audio | 200 | × 64KB | **12.5 MB** | |
| Text | 2 | × 64KB | 0.125 MB | |
| Metadata | 2 | × 64KB | 0.125 MB | |
| Image | 400 | × 64KB | 25 MB | |

### Constraint range

```java
DEFAULT_MIN_BUFFER_SIZE = 200 × 64KB = 12.5MB
DEFAULT_MAX_BUFFER_SIZE = VIDEO + 4×AUDIO + 4×TEXT + IMAGE = ~200.5MB
```

```
targetBufferBytes = constrainValue(calculated, 12.5MB, 200.5MB)
```

### Ví dụ cụ thể cho short-form video (streaming, video + audio)

```
Per player:
  targetBufferBytes = VIDEO + AUDIO
                    = (2000 × 64KB) + (200 × 64KB)
                    = 125MB + 12.5MB
                    = 137.5MB
  constrainValue(137.5MB, 12.5MB, 200.5MB) = 137.5MB

Total allocator target (3 players + PreloadManager):
  Player A: 137.5MB
  Player B: 137.5MB
  Player C: 137.5MB
  PreloadManager: 12.5MB (DEFAULT_MIN_BUFFER_SIZE, trước khi track selected)
  ──────────────────────
  Total = 425MB → allocator.setTargetBufferSize(425MB)
```

### Thực tế: targetBufferSizeReached gần như KHÔNG BAO GIỜ true cho short video

```
Video 15s, bitrate 5Mbps:
  Actual data = 15s × 5Mbps = ~9.4MB
  targetBufferBytes = 137.5MB

  9.4MB >= 137.5MB? → FALSE luôn

→ targetBufferSizeReached KHÔNG BAO GIỜ true cho short video
→ shouldContinueLoading chỉ dựa vào time check (minBuffer/maxBuffer)
```

Con số 2000/200 blocks được thiết kế là **upper bound cho worst case** (video dài, bitrate cao), không phải dự đoán chính xác. Với short-form video, giới hạn bytes không có ý nghĩa thực tế.

---

## 7. 4 Entities hoạt động đồng thời - Ví dụ hoàn chỉnh

### Phase 1: PreloadManager works ALONE (chưa có player)

```
PreloadManager (preload thread, playerId = PRELOAD):
  Download item 7: 0→1000ms ✅ done
  Download item 5: 0→1000ms ✅ done
  Download item 8: 0→1000ms ✅ done
  Download item 4: 0→1000ms ✅ done
  Download item 9: 0→1000ms...

  shouldContinueLoading(PRELOAD) = !targetBufferSizeReached
  Không cần player. Download bằng chính nó.
```

### Phase 2: ViewPager attaches → Players get assigned

```
ViewPager2 (offscreenPageLimit = 1) attaches 3 views:
  ViewHolder[5] → acquirePlayer → Player A
  ViewHolder[6] → acquirePlayer → Player B
  ViewHolder[7] → acquirePlayer → Player C
```

### Phase 3: Each player prepare() → takes over PreloadMediaSource

```
Player A (item 5):
  setMediaSource(preloadManager.getMediaSource(item5))  ← SAME object
  prepare() → createPeriod() → reuse preloaded SampleQueue
  onUsedByPlayer() → PreloadManager skips item 5
  playerId: PRELOAD → player-0
  shouldContinueLoading(player-0): 1s < 5s → load
  → Loads 1s → 20s (or end of video), playWhenReady=false

Player B (item 6, PLAYING):
  Same handoff process
  → Loads 1s → 20s, playWhenReady=true

Player C (item 7):
  Same handoff process
  → Loads 1s → 20s (or end of video), playWhenReady=false
```

### Phase 3 timeline view

```
Time ─────────────────────────────────────────────────►

PreloadManager:
  [item7: 0→1000ms] [item5: 0→1000ms] [item8: 0→1000ms] [item4]...
         │                  │
         │ HANDOFF          │ HANDOFF
         ↓                  ↓
Player C takes item7:       Player A takes item5:
  [reuse 0-1000ms][1000ms──────→20000ms]   [reuse 0-1000ms][1000ms──────→20000ms]

Player B takes item6:
  [reuse 0-1000ms][1000ms──────────────────────────────→20000ms]  PLAYING
```

### After all settle

```
PreloadManager: preloading item 9, 10, 11...
  (items that NO player owns)

Player A (item 5, paused):
  buffer: [0────20000ms] or [0────end] STOPPED

Player B (item 6, PLAYING):
  buffer: [0────20000ms] or [0────end] STOPPED (or looping)

Player C (item 7, paused):
  buffer: [0────20000ms] or [0────end] STOPPED
```

### Bandwidth competition

```
Thời điểm đầu (tất cả loading cùng lúc):
  PreloadManager: downloading item 8    ← bandwidth
  Player A: loading item 5 (1s→20s)    ← bandwidth
  Player B: loading item 6 (1s→20s)    ← bandwidth
  Player C: loading item 7 (1s→20s)    ← bandwidth
  → 4 entities chia sẻ bandwidth qua shared BandwidthMeter!

Thời điểm sau (short videos loaded hoàn toàn):
  Player A: hết video → STOPPED
  Player C: hết video → STOPPED
  Player B: hết video → STOPPED (loop)
  PreloadManager: loading item 9, 10... ← full bandwidth
```

---

## 8. Luồng chuyển video - Ví dụ vuốt 5 → 6 → 7

### Pool = 3 players

```
═══════════════════════════════════════════════════════════
Thời điểm 1: User đang xem video 5
═══════════════════════════════════════════════════════════
  Player 0 → video 4 (prepared, paused, first frame hiện)
  Player 1 → video 5 (PLAYING)
  Player 2 → video 6 (prepared, paused, first frame hiện)

  PreloadManager (items not owned by any player):
    video 7: 1000ms loaded (RAM)     ← distance = 2
    video 8: 1000ms loaded (RAM)     ← distance = 3
    video 9+: 5000ms cached (disk)   ← distance > 3

═══════════════════════════════════════════════════════════
Thời điểm 2: User vuốt sang video 6
═══════════════════════════════════════════════════════════
  1. onPageSelected(6) gọi:
     - holderMap[6].playIfPossible()
       → playerPool.play(Player 2)
       → pause Player 0, pause Player 1
       → Player 2.play() → INSTANT (đã prepare sẵn)

     - preloadManager.setCurrentPlayingIndex(6)
       → invalidate() → re-sort priority by distance from 6
       → Check each item: already loaded? → skip. Need more? → preload.

  2. Video 4 detach khỏi window:
     - onViewDetachedFromWindow()
     - releasePlayer(position=4, Player 0)
       → Player 0.stop(), clearMediaItems()
       → Player 0 trả về availablePlayerQueue

  3. Video 7 attach vào window:
     - onViewAttachedToWindow()
     - acquirePlayer(position=7, ::setupPlayer)
       → Lấy Player 0 từ queue
     - setupPlayer(Player 0):
       → Player 0.setMediaSource(video7_source)  // data đã preload
       → Player 0.prepare()  → createPeriod() → reuse preloaded data
       → playerView.player = Player 0  // hiện first frame

  Kết quả:
  Player 1 → video 5 (prepared, paused)
  Player 2 → video 6 (PLAYING)
  Player 0 → video 7 (prepared, paused, first frame hiện)

═══════════════════════════════════════════════════════════
Thời điểm 3: User vuốt sang video 7
═══════════════════════════════════════════════════════════
  Tương tự: Player 0 phát ngay video 7 (đã prepare)
  Player 1 (video 5) release → pool → acquire cho video 8

  Player 2 → video 6 (prepared, paused)
  Player 0 → video 7 (PLAYING)
  Player 1 → video 8 (prepared, paused, first frame hiện)
```

---

## 9. Điểm mấu chốt: prepare() là lý do dùng nhiều Player

### prepare() làm gì?

`player.prepare()` thực hiện 3 việc nặng mà preload (download) KHÔNG làm:

| Bước | Thời gian | Mô tả |
|---|---|---|
| 1. Khởi tạo MediaCodec | ~100-200ms | Chiếm 1 hardware decoder slot, cấu hình codec |
| 2. Decode frame đầu tiên | ~50-100ms | Đọc data → feed vào codec → decode ra frame |
| 3. Render lên Surface | ~10ms | Hiện first frame lên PlayerView (thumbnail) |

Với `playWhenReady = false` (mặc định sau `stop()`): codec decode xong frame đầu rồi **DỪNG**, không tiếp tục → tiết kiệm CPU.

### So sánh: 1 Player vs 3 Players

#### Trường hợp 1 Player

```
Video 5 đang phát ← Player duy nhất bận ở đây
Video 6 (preload) ← PreloadManager tải data xuống cache
                     NHƯNG không có player rảnh để prepare()
                     → Codec chưa khởi tạo
                     → Frame đầu chưa decode

User vuốt sang video 6:
  Step 1: player.stop()              // Dừng video 5
  Step 2: player.setMediaSource()    // Gán video 6 (data đã có trong cache)
  Step 3: player.prepare()           // Khởi tạo codec     ~100-300ms
  Step 4: Decode first frame         // Decode frame đầu   ~50-100ms
  Step 5: player.play()              // Phát
  ─────────────────────────────────
  Tổng delay: ~200-500ms → User thấy màn đen/loading
```

#### Trường hợp 3 Players

```
Player A → Video 5 (PLAYING)
Player B → Video 6:
  PreloadManager đã download 1000ms data vào RAM
  player.prepare() đã gọi → codec INITIALIZED
  Frame đầu tiên đã DECODE sẵn
  playerView.player đã gán → first frame hiện trên màn hình
  playWhenReady = false → chưa phát, chờ user vuốt

User vuốt sang video 6:
  Step 1: playerPool.play(playerB)   // Chỉ set playWhenReady = true
  ─────────────────────────────────
  Tổng delay: ~0ms → INSTANT, không loading
```

### Bảng tổng hợp

| Pipeline Stage | 1 Player | 3 Players (pool) |
|---|---|---|
| Download data (preload) | Có (PreloadManager) | Có (PreloadManager) |
| Khởi tạo MediaCodec | Không thể (player bận) | prepare() trên player rảnh |
| Decode frame đầu | Chưa | Đã decode sẵn |
| Gán PlayerView (hiện hình) | Chưa | Đã gán, first frame hiển thị |
| **Delay khi chuyển video** | **~200-500ms** | **~0ms** |

### Kết luận quan trọng

> **Chỉ preload (download) thôi thì nhiều player KHÔNG khác gì 1 player.**
> Sự khác biệt thực sự nằm ở `prepare()` - khởi tạo codec + decode first frame.
> **Nhiều player có giá trị khi và chỉ khi gọi `prepare()` cho video kế tiếp.**

---

## 10. Performance: Chi phí tài nguyên

### Chi phí mỗi ExoPlayer instance

| Tài nguyên | Chi phí mỗi Player |
|---|---|
| RAM | ~5-15MB (codec buffers, MediaCodec instance) |
| MediaCodec slots | 1 video decoder + 1 audio decoder |
| Threads | 1 playback thread + 1 loading thread |
| Network | Bandwidth cho buffering (dù paused vẫn load đến maxBuffer/end of video) |

### So sánh tổng thể

| Tiêu chí | 1 Player | 3 Players | 5+ Players |
|---|---|---|---|
| RAM | ~10MB | ~30-45MB | ~50-75MB+ |
| MediaCodec slots | 1 | 3 | Nguy cơ vượt giới hạn HW (~5-8) |
| Chuyển video delay | ~200-500ms | ~0ms | ~0ms |
| Battery | Tốt nhất | Chấp nhận được | Tốn pin |
| Risk OOM (thiết bị yếu) | Thấp | Thấp | Trung bình-Cao |

### Tại sao 3 là con số tối ưu?

```
[prev video] ← [current video] → [next video]
  Player A       Player B          Player C
  (prepared)     (playing)         (prepared)
```

- **Player B**: phát video hiện tại
- **Player A**: video trước đã prepare → vuốt ngược = instant
- **Player C**: video tiếp theo đã prepare → vuốt tới = instant
- Thêm player nữa → lãng phí tài nguyên, không cải thiện UX đáng kể

---

## 11. Điểm cần lưu ý trong code hiện tại

### Retry polling khi hết player (PlayerPool.kt:69)

```kotlin
Handler(Looper.getMainLooper()).postDelayed(
    { acquirePlayerInternal(token, callback) }, 500
)
```

Khi tất cả player đang bận, code retry mỗi 500ms trên main thread. Nếu không có player được release, vòng lặp chạy vô hạn → tiềm ẩn memory leak.

### Cache không bao giờ xóa (DemoUtil.kt)

```kotlin
SimpleCache(directory, NoOpCacheEvictor(), databaseProvider)
```

`NoOpCacheEvictor` nghĩa là cache tăng vô hạn. Phù hợp cho demo, nhưng production nên dùng `LeastRecentlyUsedCacheEvictor` với giới hạn dung lượng.

---

## Tóm tắt / Summary

### 4 thực thể độc lập

```
PreloadManager: Download data cho items CHƯA có player nhận
Player A (prev): Tự load thêm từ 1s→20s (hoặc hết video) rồi DỪNG
Player B (curr): Tự load thêm từ 1s→20s (hoặc hết video), đang phát
Player C (next): Tự load thêm từ 1s→20s (hoặc hết video) rồi DỪNG

4 thực thể ĐỘC LẬP, cùng chia sẻ BandwidthMeter/Allocator/Cache.
PreloadManager chỉ lo items chưa ai nhận.
Khi player nhận item → PreloadManager bỏ qua item đó (onUsedByPlayer).
```

### Luồng data: PreloadManager → Player (KHÔNG download lại)

```
PreloadManager downloads:     [0ms ──── 1000ms]  (PRELOAD rules)
                                        │
                              HANDOFF (same PreloadMediaSource object)
                                        │
Player continues from:       [1000ms ── 20000ms] (REAL player rules)
```

### Kết hợp hoàn chỉnh

1. PreloadManager download 1000ms data cho video kế tiếp (tuần tự, từng item)
2. PlayerPool cấp player rảnh cho video kế tiếp
3. Player gọi `prepare()` → reuse preloaded data → khởi tạo codec + decode first frame
4. Player tiếp tục load đến maxBuffer (20s) hoặc hết video
5. Khi user vuốt → chỉ cần `playWhenReady = true` → **phát ngay lập tức**
6. Khi chuyển video, PreloadManager invalidate + re-sort priority, skip items đã đủ data
