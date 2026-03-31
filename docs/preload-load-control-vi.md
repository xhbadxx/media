# DefaultLoadControl & Preload Logic

## Tong quan

Tai lieu nay giai thich cach `DefaultLoadControl` quyet dinh khi nao cho phep preload chay trong khi player dang play. Logic chinh nam trong `DefaultLoadControl.java`.

**File chinh:**
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java`
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/PreloadMediaSource.java`
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/BasePreloadManager.java`
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.java`

---

## 1. Hai "cong" de preload duoc chay

Preload phai vuot qua **2 kiem tra** truoc khi load data:

### Cong 1: `shouldContinuePreloading()` — Player co dang ranh khong?

```java
public boolean shouldContinuePreloading(...) {
    for (PlayerLoadingState playerLoadingState : loadingStates.values()) {
        if (playerLoadingState.isLoading) {
            return false;  // Co player dang load → chan preload
        }
    }
    return true;
}
```

Neu **bat ky** player nao co `isLoading = true`, preload bi **chan hoan toan**.

### Cong 2: `shouldContinueLoading()` cho PRELOAD — Buffer preload da day chua?

```java
if (playerId.equals(PlayerId.PRELOAD)) {
    return !targetBufferSizeReached;  // Chi check bytes, khong check thoi gian
}
```

Voi preload, chi kiem tra don gian: buffer cua preload (rieng biet voi player) da dat gioi han bytes chua?

---

## 2. Khi nao `isLoading` thanh `false`? (Logic cot loi)

Day la chia khoa de hieu khi nao preload duoc chay. Nam o dong 782-793:

```java
minBufferUs = max(minBufferUs, 500_000);  // Toi thieu 500ms

if (bufferedDurationUs < minBufferUs) {                              // CASE 1
    boolean prioritizeTime = prioritizeTimeOverSizeThresholds(isLocalPlayback);
    playerLoadingState.isLoading = prioritizeTime || !targetBufferSizeReached;

} else if (bufferedDurationUs >= maxBufferUs || targetBufferSizeReached) {  // CASE 2
    playerLoadingState.isLoading = false;

}  // CASE 3: else → khong thay doi isLoading (hysteresis)
```

### CASE 1: `bufferedDuration < minBuffer` (mac dinh < 10s)

Player chua buffer du. Viec co tiep tuc load khong phu thuoc vao `prioritizeTimeOverSizeThresholds`:

| `prioritizeTime` | `targetBufferSizeReached` | `isLoading` | Preload chay? |
|---|---|---|---|
| `true` (local) | khong quan trong | `true` | Khong — player phai dat minBuffer bat ke bytes |
| `false` (streaming) | `false` (bytes chua day) | `true` | Khong — player tiep tuc load, van con ngan sach memory |
| `false` (streaming) | `true` (bytes day) | `false` | **Co** — player buoc dung vi het ngan sach memory, du chua du 10s buffer |

**Diem quan trong:** Voi streaming, player se tiep tuc load cho den khi dat minBuffer (10s) hoac het ngan sach bytes (targetBufferSize) — cai nao den truoc. Voi video bitrate cao (vd: 4K 50Mbps), ngan sach bytes co the het chi sau vai giay. Player buoc phai dung load (het memory), va preload duoc chay — du player chua buffer du 10s. Day la su danh doi: an toan memory hon thoi luong buffer.

### CASE 2: `bufferedDuration >= maxBuffer (50s)` HOAC `targetBufferSizeReached`

```java
playerLoadingState.isLoading = false;
```

Player da buffer du (theo thoi gian hoac theo bytes). Day la truong hop **binh thuong** de preload bat dau.

- Video bitrate thap: dat `maxBuffer (50s)` truoc
- Video bitrate cao: dat `targetBufferSize` truoc

### CASE 3: `minBuffer <= bufferedDuration < maxBuffer` VA bytes chua day

```java
// Else don't change the loading state.
```

Khong thay doi — **co che hysteresis** (chong giat). Ngan viec bat/tat lien tuc:
- Neu player dang load → tiep tuc load (preload cho)
- Neu player da dung → van dung (preload tiep tuc)

Player load len den maxBuffer (50s), roi dung. No tieu thu buffer trong khi play. Chi khi buffer giam xuong duoi minBuffer (10s) moi bat dau load lai.

---

## 3. Local vs Streaming

Xac dinh dua tren URI scheme (`isLocalPlayback()` dong 938):

**Local:** `file://`, `content://`, `data:`, `android.resource://`, `rawresource://`, `asset://`
**Streaming:** Moi thu khac (`http://`, `https://`, `rtsp://`, v.v.)

### Khac biet tham so mac dinh

| Tham so | Streaming | Local |
|---|---|---|
| `minBuffer` | 10s | 10s |
| `maxBuffer` | 50s | 50s |
| `bufferForPlayback` | 2.5s | 500ms |
| `prioritizeTimeOverSize` | **`false`** | **`true`** |
| Video buffer size | ~125 MB (2000 x 64KB) | ~19 MB (300 x 64KB) |

### Tai sao khac nhau?

- **Local:** Doc tu o dia rat nhanh va "mien phi". Luon dam bao buffer du thoi gian (10s). Memory nho vi doc lai local rat de.
- **Streaming:** Bandwidth mang co han. Neu gioi han bytes dat, dung load du chua du 10s — bao ve memory quan trong hon. Buffer memory lon hon de giam so lan request qua mang.

---

## 4. `targetBufferSizeReached` giai thich

```java
boolean targetBufferSizeReached =
    getTotalBufferBytesAllocated(playerId) >= getTargetBufferBytes(playerId);
```

- **`getTotalBufferBytesAllocated`**: So segment da cap phat x 64KB (kich thuoc segment). Tong bytes thuc te dang chiem trong memory cua player nay.
- **`getTargetBufferBytes`**: Gioi han bytes toi da cho player nay. Duoc set luc `onTracksSelected()` dua tren track da chon, hoac bang gia tri override.

### Gia tri mac dinh target buffer bytes

| PlayerId | Target | Cach tinh |
|---|---|---|
| PRELOAD | ~137.5 MB | Co dinh: `VIDEO(125MB) + AUDIO(12.5MB)` |
| Streaming player | ~137.5 MB | Theo track: vd video(125MB) + audio(12.5MB) |
| Local player | ~31.5 MB | Theo track: vd video(19MB) + audio(12.5MB) |

### Quan trong: Moi PlayerId co buffer rieng

Player va Preload co allocator va bo dem bytes **doc lap**. Khi buffer cua player day, khong co nghia buffer cua preload day.

```
Player:  allocated = 137MB / target = 137MB → day → dung load
Preload: allocated = 0MB   / target = 137MB → trong → load duoc
```

---

## 5. Quan he giua Buffer Duration va Buffer Size

Buffer duration (giay) va buffer size (bytes) do cung 1 du lieu nhung bang don vi khac nhau, ket noi qua **bitrate**:

```
bufferSize (bytes) = bitrate (bytes/giay) x bufferDuration (giay)
```

### Vi du: Video 10 Mbps

```
Buffer 3s  → 3.75 MB
Buffer 10s → 12.5 MB
Buffer 50s → 62.5 MB
Target (137.5 MB) dat sau ~110s
→ Se dat maxBuffer (50s) TRUOC targetBufferSize
→ Player dung load o 50s, preload chay
```

### Vi du: Video 50 Mbps (4K)

```
Buffer 3s  → 18.75 MB
Buffer 10s → 62.5 MB
Buffer 22s → 137.5 MB
Target (137.5 MB) dat sau ~22s
→ Se dat targetBufferSize TRUOC maxBuffer (50s)
→ Player dung load o 22s, preload chay
```

Code kiem tra **ca hai** thoi gian va bytes — gioi han nao dat truoc thi player dung load truoc.

---

## 6. Vi du thuc te va Timeline

### Buffer = Bon nuoc

```
Buffer (RAM) giong nhu bon nuoc:
  - Voi VAO (download)   = data tu mang do vao buffer
  - Voi RA (tieu thu)    = player lay data tu buffer de play
  - Muc nuoc tang (lap day) = voi VAO - voi RA

Toc do lap day = toc do download - bitrate video
```

### targetBufferBytes vs Toc do Download — Hai thu khac nhau

```
targetBufferBytes (137.5 MB) = SUC CHUA BON (gioi han RAM toi da)
  - Video:  2000 × 64KB = 125 MB
  - Audio:   200 × 64KB = 12.5 MB
  - Tong:                ~137.5 MB

Toc do download (12.5 MB/s) = TOC DO VOI NUOC (bandwidth mang)

Thoi gian do day bon = 137.5 MB / 12.5 MB/s ≈ 11 giay (neu khong tieu thu)
```

Hai con so hoan toan khac nhau — mot la gioi han memory, mot la toc do mang.

### Vi du 1: Video 10 Mbps, Mang 100 Mbps (WiFi nhanh)

```
Toc do download:  100 Mbps = 12.5 MB/s
Toc do tieu thu:   10 Mbps = 1.25 MB/s (bitrate video)
Toc do lap day:    12.5 - 1.25 = 11.25 MB/s

Timeline:
  t=0.0s:  Bat dau load
  t=0.2s:  Buffer = 2.5s noi dung (2.5 MB) → playback bat dau
           Player vua load vua play cung luc

  maxBuffer check: 50s noi dung = 62.5 MB < 137.5 MB target
  → maxBuffer (50s) se dat TRUOC

  t=5.7s:  bufferedDuration = 50s → CASE 2: isLoading = false
           → PRELOAD CHAY (sau ~6 giay thuc)
```

### Vi du 2: Video 50 Mbps (4K), Mang 100 Mbps

```
Toc do download:  100 Mbps = 12.5 MB/s
Toc do tieu thu:   50 Mbps = 6.25 MB/s
Toc do lap day:    12.5 - 6.25 = 6.25 MB/s

Timeline:
  t=0.0s:  Bat dau load
  t=0.4s:  Buffer = 2.5s noi dung → playback bat dau

  maxBuffer check: 50s noi dung = 312.5 MB > 137.5 MB target
  → targetBufferSize se dat TRUOC

  t=22.4s: allocated = 137.5 MB → targetBufferSizeReached = true
           bufferedDuration ≈ 22s (chua den maxBuffer)
           → CASE 2: isLoading = false
           → PRELOAD CHAY (sau ~22 giay thuc)
```

### Vi du 3: Video 10 Mbps, Mang cham 15 Mbps

```
Toc do download:  15 Mbps = 1.875 MB/s
Toc do tieu thu:  10 Mbps = 1.25 MB/s
Toc do lap day:   1.875 - 1.25 = 0.625 MB/s (rat cham!)

Timeline:
  t=0.0s:  Bat dau load
  t=1.7s:  Buffer = 2.5s → playback bat dau

  50s noi dung = 62.5 MB
  62.5 MB / 0.625 MB/s = 100 giay de lap day

  t=101.7s: bufferedDuration = 50s → isLoading = false
            → PRELOAD CHAY (sau ~102 giay!)
```

### Vi du 4: Video 10 Mbps, Mang cham hon bitrate 8 Mbps

```
Toc do download:  8 Mbps = 1 MB/s
Toc do tieu thu:  10 Mbps = 1.25 MB/s
Toc do lap day:   1 - 1.25 = -0.25 MB/s (AM!)

→ Buffer GIAM lien tuc → player se bi buffering/lag
→ isLoading = true MAI → preload KHONG BAO GIO chay
```

### Tom tat: Khi nao Preload bat dau? (Thoi gian thuc te)

| Truong hop | Toc do lap day | Preload chay sau |
|---|---|---|
| 10 Mbps video, mang 100 Mbps | 11.25 MB/s | ~6 giay |
| 50 Mbps 4K video, mang 100 Mbps | 6.25 MB/s | ~22 giay |
| 10 Mbps video, mang cham 15 Mbps | 0.625 MB/s | ~102 giay |
| 10 Mbps video, mang cham 8 Mbps | am | Khong bao gio |

---

## 7. Khi LoadControl tu choi Preload

Khi `shouldContinueLoading()` tra ve `false` cho preload (het ngan sach bytes), `PreloadMediaSource` xu ly bang retry (dong 620-643):

```
LoadControl tu choi
  → Retry toi da 10 lan, moi 100ms
  → Neu van bi tu choi → goi preloadControl.onLoadingUnableToContinue()
      → DefaultPreloadManager xoa source uu tien thap nhat de giai phong memory
      → Reset retry counter, thu lai
  → Neu khong xoa duoc nua → retry vo han (100ms interval)
      cho player giai phong buffer
```

---

## 7. Flow day du cua Preload khi Player dang Play

```
Player bat dau play item A
  |-- buffer = 0s, isLoading = true
  |-- Player load... buffer dat 2.5s → playback bat dau
  |-- Player load... buffer qua 10s → CASE 3: isLoading giu true
  |-- Player load... buffer dat 50s → CASE 2: isLoading = false
  |
  |-- shouldContinuePreloading() → tat ca isLoading = false → return true
  |-- PRELOAD BAT DAU cho item B (ke tiep theo uu tien)
  |
  |-- Player tieu thu buffer... giam con 30s → CASE 3: khong doi, isLoading van false
  |-- Preload tiep tuc chay
  |
  |-- Player tieu thu buffer... giam duoi 10s → CASE 1: isLoading = true
  |-- shouldContinuePreloading() → isLoading = true → return false
  |-- PRELOAD TAM DUNG
  |
  |-- Player load lai... buffer dat 50s → isLoading = false
  |-- PRELOAD TIEP TUC
  |
  +-- Player chuyen sang item B → isUsedByPlayer() = true → preload ban giao data
```

---

## 8. Bang tom tat

| Dieu kien | Player `isLoading` | Preload chay duoc? |
|---|---|---|
| buffer < minBuffer (10s), streaming, bytes chua day | `true` | Khong |
| buffer < minBuffer (10s), streaming, **bytes day** | `false` | **Co** (rui ro) |
| buffer < minBuffer (10s), local | `true` | Khong |
| minBuffer <= buffer < maxBuffer (khong doi) | gia tri truoc do | Tuy truong hop |
| buffer >= maxBuffer (50s) | `false` | **Co** |
| targetBufferSizeReached (bat ky luc nao) | `false` | **Co** |