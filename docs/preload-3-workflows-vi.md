# 3 Workflow của Preload trong Media3

## Tổng quan: Preload State Machine

```
STAGE_NOT_PRELOADED (MIN_VALUE)     ← Chưa preload gì
    ↓
STAGE_SPECIFIED_RANGE_CACHED (-1)   ← Cached xuống disk
    ↓
STAGE_SOURCE_PREPARED (0)           ← Source đã prepare xong
    ↓
STAGE_TRACKS_SELECTED (1)          ← Tracks đã chọn, bắt đầu load data
    ↓
STAGE_SPECIFIED_RANGE_LOADED (2)   ← Data đã load đủ vào RAM
```

---

## WORKFLOW 1: CÓ PRELOAD, ĐÃ LOAD ĐỦ (Full Preload)

Trường hợp lý tưởng nhất - PreloadManager đã download đủ data (vd: 1000ms) trước khi player cần.

### Timeline:

```
PreloadManager thread                          Player thread
─────────────────────                          ─────────────────
preload(startPositionUs)
  ↓
setPlayerId(PRELOAD)
  ↓
prepareSourceInternal()
  ↓
onChildSourceInfoRefreshed()
  ↓
preloadControl.onSourcePrepared() → true
  ↓
createPeriod() → PreloadMediaPeriod
  → preloadingMediaPeriodAndKey = (period, key)
  ↓
selectTracksForPreloading()
  → Tạo PreloadTrackSelectionHolder:
    - selections (track đã chọn)
    - streams (SampleStream[])
    - mayRetainStreamFlags = all true
  ↓
maybeContinueLoading() loop:
  loadControl.shouldContinueLoading(PRELOAD)
  mediaPeriod.continueLoading()
  ... lặp lại ...
  ↓
✅ bufferedDuration >= targetDuration
  → preloadControl.onLoadedToTarget()              player.setMediaSource(SAME object)
  → STAGE = SPECIFIED_RANGE_LOADED                  player.prepare()
                                                      ↓
                                                    prepareSourceCalled = true
                                                    → isUsedByPlayer() = TRUE
                                                      ↓
                                                    onUsedByPlayer():
                                                      preloadControl.onUsedByPlayer(this)
                                                      stopPreloading() ← dừng handler
                                                      ↓
                                                    createPeriod(id, allocator, startPos)
                                                      ↓
                                                    key matches preloadingMediaPeriodAndKey?
                                                    → YES! ✅
                                                      ↓
                                                    REUSE preloaded period!
                                                    playingPreloadedMediaPeriodAndId = period
                                                    preloadingMediaPeriodAndKey = null
                                                      ↓
                                                    selectTracks():
                                                      preloadTrackSelectionHolder != null
                                                      position matches
                                                      → System.arraycopy(holder.streams)
                                                      → REUSE tất cả SampleStream!
                                                      → preloadTrackSelectionHolder = null
                                                      ↓
                                                    Player đọc data từ SampleQueue
                                                    → Data sẵn sàng ngay → PHÁT NGAY!
```

### Kết quả:

```
Preload đã download:     [0ms ──── 1000ms]  ← data nằm trong RAM (SampleQueue)
                                    │
                         HANDOFF (cùng object, cùng SampleQueue)
                                    │
Player tiếp tục từ:     [1000ms ── 20000ms] ← load thêm, KHÔNG download lại 0-1000ms

Delay khi chuyển video:
  - 1 Player: ~50-150ms (vẫn phải init codec + decode first frame)
  - 3 Players (prepare sẵn): ~0ms (đã trả trước tất cả chi phí)
```

---

## WORKFLOW 2: CÓ PRELOAD, CHƯA LOAD ĐỦ (Partial Preload)

Trường hợp này xảy ra khi user vuốt nhanh - PreloadManager mới download được một phần, player đã cần dùng.

### Tại sao xảy ra?

```
Ví dụ: target = 1000ms, nhưng mới load được 400ms
  - Mạng chậm
  - Player đang loading → preload bị block (shouldContinuePreloading = false)
  - User vuốt nhanh trước khi preload xong
```

### Timeline:

```
PreloadManager thread                          Player thread
─────────────────────                          ─────────────────
preload(startPositionUs)
  ↓
createPeriod() → PreloadMediaPeriod
  ↓
selectTracksForPreloading()
  → PreloadTrackSelectionHolder created
  ↓
maybeContinueLoading() loop:
  ... loading ... buffered = 400ms
  ... CHƯA ĐỦ 1000ms ...
                                                player.setMediaSource(SAME object)
  ← BỊ NGẮT! ←                                 player.prepare()
                                                  ↓
                                                isUsedByPlayer() = TRUE
                                                  ↓
                                                onUsedByPlayer():
                                                  stopPreloading() ← DỪNG preload ngay!
                                                  ↓
                                                createPeriod(id, allocator, startPos)
                                                  ↓
                                                key matches? → YES ✅
                                                  ↓
                                                REUSE preloaded period (với 400ms data)
                                                  ↓
                                                selectTracks():
                                                  preloadTrackSelectionHolder != null
                                                  ↓
                                                  Kiểm tra selections có compatible?
                                                  ↓
                                                  CÓ: mayRetainStreamFlags = true
                                                      → REUSE streams (giữ 400ms data)
                                                  ↓
                                                  KHÔNG compatible (ABR chọn quality khác):
                                                      → Re-select tracks
                                                      → Giữ streams tương thích
                                                      → Tạo mới streams không tương thích
                                                  ↓
                                                Player continueLoading() tiếp tục:
                                                  → Load từ 400ms → 20000ms
                                                  → KHÔNG download lại 0-400ms
```

### Kết quả:

```
Preload đã download:     [0ms ──── 400ms]  ← partial, chưa đủ 1000ms
                                    │
                         HANDOFF (cùng object)
                                    │
Player tiếp tục từ:     [400ms ── 20000ms] ← load tiếp từ chỗ preload dừng

Delay khi chuyển video:
  - 1 Player:
    - Nếu 400ms >= bufferForPlayback (500ms): ~50-150ms (codec + decode)
    - Nếu 400ms < bufferForPlayback: ~50-150ms + chờ download thêm
  - 3 Players (prepare sẵn): ~0ms
```

### Trường hợp đặc biệt - Preload mới ở STAGE_SOURCE_PREPARED:

```
Preload mới prepare source xong, CHƯA select tracks:
  → preloadTrackSelectionHolder = null
  → Player vẫn reuse PreloadMediaPeriod
  → Nhưng phải tự selectTracks() từ đầu
  → SampleQueue rỗng, load từ 0ms
  → Lợi ích: chỉ tiết kiệm thời gian prepare source (~manifest download)
```

---

## WORKFLOW 3: KHÔNG CÓ PRELOAD (No Preload)

Trường hợp này xảy ra khi:
- PreloadManager chưa đến lượt preload item này
- App không dùng PreloadManager
- User vuốt rất nhanh qua nhiều video

### Timeline:

```
                                                Player thread
                                                ─────────────────
(Không có preload nào xảy ra)
                                                player.setMediaSource(mediaSource)
                                                player.prepare()
                                                  ↓
                                                prepareSourceCalled = true
                                                → isUsedByPlayer() = TRUE ngay lập tức
                                                  ↓
                                                onUsedByPlayer():
                                                  stopPreloading() ← no-op (chưa preload)
                                                  ↓
                                                prepareChildSource()
                                                  ← PHẢI download manifest/init segment
                                                  ← ~100-500ms tùy mạng
                                                  ↓
                                                onChildSourceInfoRefreshed()
                                                  → isUsedByPlayer() = true → SKIP preload logic
                                                  ↓
                                                createPeriod(id, allocator, startPos)
                                                  ↓
                                                preloadingMediaPeriodAndKey == null? → YES
                                                  ↓
                                                TẠO MỚI PreloadMediaPeriod:
                                                  mediaPeriod = new PreloadMediaPeriod(
                                                      mediaSource.createPeriod(...))
                                                  → Period hoàn toàn mới, KHÔNG có data
                                                  ↓
                                                selectTracks():
                                                  preloadTrackSelectionHolder == null
                                                  → Gọi mediaPeriod.selectTracks() bình thường
                                                  → Tạo SampleStream mới hoàn toàn
                                                  → mayRetainStreamFlags = ALL FALSE
                                                  → streams = ALL NULL → tạo mới
                                                  ↓
                                                continueLoading():
                                                  → Download từ 0ms, cold start
                                                  → Phải đợi đủ bufferForPlayback (500ms)
                                                  ← ~200-2000ms tùy mạng
                                                  ↓
                                                Phát video
```

### Kết quả:

```
Không có preload:        [TRỐNG]
                            │
                     Player phải tự làm TẤT CẢ:
                            │
Player tự load:          [0ms ──────────── 20000ms]
                          ↑
                     Cold start: prepare source + select tracks + download

Delay khi chuyển video: ~300-1300ms → User thấy MÀN ĐEN hoặc loading spinner
```

---

## Pipeline đầy đủ: 6 bước LUÔN PHẢI LÀM

Dù preload hay không, dù 1 player hay 3 players, pipeline video LUÔN gồm 6 bước:

```
Bước    Công việc                    Chi phí         Mô tả
────    ─────────                    ───────         ─────
1       Parse manifest               ~10-30ms        Download + parse MPD/M3U8/moov
2       Track selection              ~5-10ms         Chọn video/audio tracks
3       Init segment (download/read) ~10-30ms        ftyp + moov, SPS/PPS, codec config
4       Init decoder (MediaCodec)    ~20-60ms        Cold: create+configure / Warm: flush+reconfig
5       First media segment          ~20-100ms       Download/read segment chứa keyframe (100KB-2MB)
6       Decode keyframe              ~20-60ms        Feed keyframe vào codec → hardware decode → render
────────────────────────────────────────────────────
Tổng:                                ~85-290ms       Không thể skip bất kỳ bước nào
```

### Phân biệt Init Segment vs First Media Segment

```
MP4/DASH file structure:

[Init Segment]              [First Media Segment]         [Segment 2] ...
├── ftyp box                ├── moof box                  ├── moof
├── moov box                │   └── traf (track fragment)  │   └── traf
│   ├── mvhd (duration)     └── mdat box                  └── mdat
│   ├── trak (video)            ├── Keyframe (I-frame)        ├── P-frame
│   │   └── stsd (SPS/PPS)     ├── P-frame                   ├── P-frame
│   └── trak (audio)           └── Audio samples              ...
│       └── stsd (codec info)
└── sidx (segment index)

Init Segment: METADATA cho codec (SPS/PPS, sample rate...)
              → Cần để configure MediaCodec
              → Nhỏ: ~1-10KB

First Media Segment: DATA thực tế (video frames + audio)
                     → Cần để decode first frame
                     → Lớn: ~100KB-2MB (chứa keyframe)
```

### Preload ảnh hưởng ở bước nào?

```
                        No Preload      Partial Preload   Full Preload
                        ──────────      ───────────────   ────────────
1. Parse manifest       DOWNLOAD ❌     Đã làm ✅         Đã làm ✅
2. Track selection      Phải làm ❌     Đã làm ✅         Đã làm ✅
3. Init segment         DOWNLOAD ❌     Đã có ✅           Đã có ✅
4. Init decoder         Phải làm ❌     Phải làm ❌       Phải làm ❌
5. First media segment  DOWNLOAD ❌     Có một phần ⚠️    Đã có ✅
6. Decode keyframe      Phải làm ❌     Phải làm ❌       Phải làm ❌

Bước 4, 6: LUÔN phải làm (hardware operation)
Bước 5: preload giúp SKIP download, nhưng feed + decode vẫn tốn thời gian
```

---

## So sánh 3 Workflows

```
                    Full Preload         Partial Preload       No Preload
                    ────────────         ───────────────       ──────────
Preload state:      RANGE_LOADED         TRACKS_SELECTED       NOT_PRELOADED
                                         (hoặc SOURCE_PREPARED)

Data sẵn sàng:      1000ms ✅            0-999ms (partial)     0ms ❌

createPeriod():     REUSE period ✅      REUSE period ✅       TẠO MỚI ❌

selectTracks():     REUSE streams ✅     REUSE nếu compatible  TẠO MỚI ❌

SampleQueue:        Có data sẵn ✅       Có một phần data      Rỗng ❌

Player load từ:     1000ms               400ms (ví dụ)         0ms

Manifest download:  Đã xong ✅           Đã xong ✅            PHẢI download ❌

Codec init:         Phải làm ❌          Phải làm ❌           Phải làm ❌
                    (trừ khi 3 players
                     đã prepare sẵn)

First frame:        Phải decode ❌       Phải decode ❌        Phải decode ❌
                    (trừ khi 3 players
                     đã prepare sẵn)
```

---

## 1 Player vs 3 Players: Chi phí GIỐNG NHAU, thời điểm KHÁC NHAU

### Codec init: 3 trạng thái

```
                     Cold (tạo mới)    Warm (reuse)     Hot (đã prepare sẵn)
                     ──────────────    ────────────     ────────────────────
createDecoder()      ~30-50ms          SKIP ✅          SKIP ✅
configure()          ~10-20ms          SKIP/redo        SKIP ✅
flush()              N/A               ~5-10ms          SKIP ✅
Feed keyframe        ~20-50ms          ~20-50ms         ĐÃ LÀM ✅
Decode keyframe      ~20-60ms          ~20-60ms         ĐÃ LÀM ✅
Render first frame   ~5-10ms           ~5-10ms          ĐÃ LÀM ✅
─────────────────    ─────────         ─────────        ─────────
Tổng delay:          ~90-190ms         ~60-150ms        ~0ms
```

**Warm state (reuse decoder) vẫn tốn 50-150ms** vì:

```
Dù reuse codec (không tạo mới), vẫn PHẢI:
  1. codec.flush()                    ~5-10ms    ← Clear internal buffers
  2. (có thể) codec.reconfigure()     ~10-20ms   ← Nếu resolution/profile khác
  3. Feed first sample (keyframe)     ~20-50ms   ← Keyframe thường lớn (50-500KB)
  4. Decode keyframe                  ~20-60ms   ← Hardware decode 1 frame
  5. Output buffer → render           ~5-10ms    ← Hiện lên Surface
  ──────────────────────────────────
  Tổng: ~60-150ms — KHÔNG BAO GIỜ 0ms với 1 player
```

### Bản chất: 3 Players KHÔNG nhanh hơn, chỉ TRƯỚC hơn

```
Chi phí pipeline GIỐNG NHAU:

1 Player:   [1][2][3][4][5][6] = ~50-150ms  ← user CHỜ
3 Players:  [1][2][3][4][5][6] = ~50-150ms  ← chạy NGẦM, user KHÔNG chờ

Cùng 1 công việc, cùng 1 thời gian, chỉ khác THỜI ĐIỂM thực hiện.
```

### Timeline thực tế

```
3 Players:
                    User đang xem video A
                    ├──────────────────────────────────►
                    │
  Player B (ngầm):  [manifest][tracks][init seg][codec][1st seg][decode]
                    |◄─────────────── 50-150ms ───────────────►|
                    │                                          │
                    │              prepare() XONG              │
                    │              first frame HIỆN             │
                    │                                          │
                    │                        User vuốt ────────┤
                    │                                          ↓
                    │                              playWhenReady = true
                    │                              → 0ms (đã làm hết rồi)


1 Player + Preload:
                    User đang xem video A
                    ├──────────────────────────────────►
                    │                                          │
                    │  Preload: [manifest][tracks][init seg][1st seg] ✅
                    │           (bước 1,2,3,5 làm ngầm)
                    │                                          │
                    │                        User vuốt ────────┤
                    │                                          ↓
                    │         [codec][decode] ← PHẢI LÀM LÚC NÀY
                    │         |◄── 50-150ms ──►|
                    │                          → user chờ
```

### 3 Players cũng KHÔNG PHẢI lúc nào cũng 0ms

```
User vuốt RẤT NHANH (trước khi prepare() xong):

  t=0ms:     Player B bắt đầu prepare() cho video kế
  t=30ms:    User vuốt! ← prepare() mới chạy được 30ms
             → prepare() CHƯA XONG
             → User vẫn phải chờ ~70-120ms còn lại
             → KHÔNG instant

  Timeline:
  Player B: [manifest][tracks][init seg][codec][1st seg][decode]
                   ↑ user vuốt ở đây
                   |◄──── user vẫn chờ ────►|
```

---

## 1 Player: Chi phí thực tế đầy đủ

### Có Full Preload (data sẵn sàng, codec warm):

```
player.stop()
player.setMediaSource(preloadedSource)
player.prepare()
  ↓
1. Parse manifest:       SKIP (reuse từ preload)      ~0ms
2. Track selection:      Phải làm lại (player rules)  ~5-10ms
3. Init segment:         Đã có trong SampleQueue      ~0ms
4. Init decoder (warm):  flush + reconfigure           ~20-40ms
5. First media segment:  Đã có trong SampleQueue       ~5-10ms (read từ RAM)
6. Decode keyframe:      Feed + decode                 ~20-60ms
───────────────────────────────────────────────────
Tổng: ~50-120ms ← user chờ
```

### Không có Preload (cold start):

```
1. Parse manifest:       DOWNLOAD từ network           ~50-200ms
2. Track selection:      Phải làm                      ~5-10ms
3. Init segment:         DOWNLOAD từ network           ~20-50ms
4. Init decoder (cold):  create + configure            ~50-100ms
5. First media segment:  DOWNLOAD từ network           ~50-500ms
6. Decode keyframe:      Feed + decode                 ~20-60ms
───────────────────────────────────────────────────
Tổng: ~200-900ms+ ← user chờ rất lâu
```

---

## Tổng kết

```
Pipeline steps:
  [manifest] → [tracks] → [init seg] → [init codec] → [1st media seg] → [decode]
      1            2           3             4               5               6

Preload giúp skip:     ✅1  ✅2  ✅3          ❌4           ✅5             ❌6
Multi-player trả trước: ✅1  ✅2  ✅3          ✅4           ✅5             ✅6

                        Tổng công việc     User chờ       Ai trả chi phí
                        ──────────────     ──────────     ──────────────
1 Player + no preload:  ~200-900ms         ~200-900ms     User chờ TẤT CẢ
1 Player + preload:     ~50-150ms          ~50-150ms      User chờ codec+decode
3 Players + preload:    ~50-150ms          ~0ms           Background trả hết

Pipeline cost = GIỐNG NHAU cho mọi trường hợp
Khác biệt duy nhất = THỜI ĐIỂM trả chi phí (trước hay sau khi user vuốt)
```

### Kết luận quan trọng

> **Preload giúp skip download time (bước 1, 2, 3, 5).**
> **Multi-player giúp trả trước codec + decode time (bước 4, 6).**
> **Không có cách nào 1 player đạt 0ms — decode keyframe là hardware operation bắt buộc.**
> **3 Players không nhanh hơn, chỉ dời chi phí vào background khi user đang xem video hiện tại.**
