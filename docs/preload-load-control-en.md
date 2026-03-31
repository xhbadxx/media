# DefaultLoadControl & Preload Logic

## Overview

This document explains how `DefaultLoadControl` decides when to allow preloading while the player is actively playing. The core logic lives in `DefaultLoadControl.java`.

**Key files:**
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java`
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/PreloadMediaSource.java`
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/BasePreloadManager.java`
- `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.java`

---

## 1. Two Gates for Preload to Run

Preload must pass **two checks** before it can load data:

### Gate 1: `shouldContinuePreloading()` — Is the player idle?

```java
public boolean shouldContinuePreloading(...) {
    for (PlayerLoadingState playerLoadingState : loadingStates.values()) {
        if (playerLoadingState.isLoading) {
            return false;  // A player is loading → block preload
        }
    }
    return true;
}
```

If **any** player has `isLoading = true`, preload is **completely blocked**.

### Gate 2: `shouldContinueLoading()` for PRELOAD — Is the preload buffer full?

```java
if (playerId.equals(PlayerId.PRELOAD)) {
    return !targetBufferSizeReached;  // Only checks bytes, not time
}
```

For preload, the check is simple: has the preload buffer (separate from the player buffer) reached its target bytes?

---

## 2. When Does `isLoading` Become `false`? (Core Logic)

This is the key to understanding when preload gets to run. Located at lines 782-793:

```java
minBufferUs = max(minBufferUs, 500_000);  // At least 500ms

if (bufferedDurationUs < minBufferUs) {                              // CASE 1
    boolean prioritizeTime = prioritizeTimeOverSizeThresholds(isLocalPlayback);
    playerLoadingState.isLoading = prioritizeTime || !targetBufferSizeReached;

} else if (bufferedDurationUs >= maxBufferUs || targetBufferSizeReached) {  // CASE 2
    playerLoadingState.isLoading = false;

}  // CASE 3: else → no change to isLoading (hysteresis)
```

### CASE 1: `bufferedDuration < minBuffer` (default < 10s)

The player hasn't buffered enough yet. Whether it continues loading depends on `prioritizeTimeOverSizeThresholds`:

| `prioritizeTime` | `targetBufferSizeReached` | `isLoading` | Preload runs? |
|---|---|---|---|
| `true` (local) | doesn't matter | `true` | No — player must reach minBuffer regardless of bytes |
| `false` (streaming) | `false` (bytes not full) | `true` | No — player keeps loading, still has memory budget |
| `false` (streaming) | `true` (bytes full) | `false` | **Yes** — player forced to stop because memory budget is exhausted, even though < 10s buffered |

**Key insight:** For streaming, the player will keep loading until either it reaches minBuffer (10s) or its byte budget (targetBufferSize) is exhausted — whichever comes first. With high-bitrate video (e.g. 4K 50Mbps), the byte budget can be exhausted after only a few seconds. The player is forced to stop loading (out of memory budget), and preload gets to run — even though the player has < 10s buffered. This is a trade-off: memory safety over buffer duration.

### CASE 2: `bufferedDuration >= maxBuffer (50s)` OR `targetBufferSizeReached`

```java
playerLoadingState.isLoading = false;
```

The player has buffered enough (either by time or by bytes). This is the **normal** case where preload starts.

- Low bitrate video: hits `maxBuffer (50s)` first
- High bitrate video: hits `targetBufferSize` first

### CASE 3: `minBuffer <= bufferedDuration < maxBuffer` AND bytes not full

```java
// Else don't change the loading state.
```

No change — **hysteresis mechanism**. This prevents rapid on/off toggling:
- If player was loading → continues loading (preload waits)
- If player had stopped → stays stopped (preload continues)

The player loads up to maxBuffer (50s), then stops. It consumes buffer during playback. Only when buffer drops below minBuffer (10s) does it start loading again.

---

## 3. Local vs Streaming Playback

Determined by URI scheme (`isLocalPlayback()` at line 938):

**Local:** `file://`, `content://`, `data:`, `android.resource://`, `rawresource://`, `asset://`
**Streaming:** Everything else (`http://`, `https://`, `rtsp://`, etc.)

### Default parameter differences

| Parameter | Streaming | Local |
|---|---|---|
| `minBuffer` | 10s | 10s |
| `maxBuffer` | 50s | 50s |
| `bufferForPlayback` | 2.5s | 500ms |
| `prioritizeTimeOverSize` | **`false`** | **`true`** |
| Video buffer size | ~125 MB (2000 x 64KB) | ~19 MB (300 x 64KB) |

### Why different?

- **Local:** Reading from disk is fast and free. Always ensure time-based buffer is met (10s). Memory is small because local reads are cheap to re-do.
- **Streaming:** Network bandwidth is limited. If byte limit is hit, stop loading even if < 10s buffered — protecting memory is more important. Larger memory buffer to reduce network round-trips.

---

## 4. `targetBufferSizeReached` Explained

```java
boolean targetBufferSizeReached =
    getTotalBufferBytesAllocated(playerId) >= getTargetBufferBytes(playerId);
```

- **`getTotalBufferBytesAllocated`**: Number of allocated segments x 64KB (segment size). The actual bytes used in memory by this player.
- **`getTargetBufferBytes`**: Maximum bytes allowed for this player. Set during `onTracksSelected()` based on selected tracks, or by override.

### Default target buffer bytes

| PlayerId | Target | How calculated |
|---|---|---|
| PRELOAD | ~137.5 MB | Fixed: `VIDEO(125MB) + AUDIO(12.5MB)` |
| Streaming player | ~137.5 MB | By tracks: e.g. video(125MB) + audio(12.5MB) |
| Local player | ~31.5 MB | By tracks: e.g. video(19MB) + audio(12.5MB) |

### Important: Each PlayerId has its own buffer

Player and Preload have **independent** allocators and byte counters. When the player's buffer is full, it doesn't mean the preload's buffer is full.

```
Player:  allocated = 137MB / target = 137MB → full → stops loading
Preload: allocated = 0MB   / target = 137MB → empty → can load
```

---

## 5. Relationship Between Buffer Duration and Buffer Size

Buffer duration (seconds) and buffer size (bytes) measure the same data in different units, connected by **bitrate**:

```
bufferSize (bytes) = bitrate (bytes/sec) x bufferDuration (sec)
```

### Example: 10 Mbps video

```
3s buffered  → 3.75 MB
10s buffered → 12.5 MB
50s buffered → 62.5 MB
Target (137.5 MB) reached after ~110s
→ Will hit maxBuffer (50s) BEFORE targetBufferSize
```

### Example: 50 Mbps video (4K)

```
3s buffered  → 18.75 MB
10s buffered → 62.5 MB
22s buffered → 137.5 MB
Target (137.5 MB) reached after ~22s
→ Will hit targetBufferSize BEFORE maxBuffer (50s)
```

The code checks **both** time and bytes — whichever limit is hit first causes the player to stop loading.

---

## 6. Buffer Analogy and Practical Timeline Examples

### Buffer = A Water Tank

```
Buffer (RAM) is like a water tank:
  - Tap IN (download)  = data flows from network into buffer
  - Tap OUT (consume)   = player takes data from buffer to play
  - Water level rising (fill rate) = tap IN - tap OUT

Fill rate = download speed - playback bitrate
```

### targetBufferBytes vs Download Speed — They Are Different Things

```
targetBufferBytes (137.5 MB) = TANK CAPACITY (max RAM allowed)
  - Video:  2000 × 64KB = 125 MB
  - Audio:   200 × 64KB = 12.5 MB
  - Total:               ~137.5 MB

Download speed (12.5 MB/s) = TAP FLOW RATE (network bandwidth)

Time to fill tank = 137.5 MB / 12.5 MB/s ≈ 11 seconds (without consumption)
```

These are completely unrelated numbers — one is a memory limit, the other is network speed.

### Example 1: Video 10 Mbps, Network 100 Mbps (fast WiFi)

```
Download speed:  100 Mbps = 12.5 MB/s
Consume speed:   10 Mbps  = 1.25 MB/s (video bitrate)
Fill rate:       12.5 - 1.25 = 11.25 MB/s

Timeline:
  t=0.0s:  Start loading
  t=0.2s:  Buffer = 2.5s content (2.5 MB) → playback starts
           Player is now loading AND playing simultaneously

  maxBuffer check: 50s content = 62.5 MB < 137.5 MB target
  → maxBuffer (50s) will be hit FIRST

  t=5.7s:  bufferedDuration = 50s → CASE 2: isLoading = false
           → PRELOAD STARTS (after ~6 seconds real time)
```

### Example 2: Video 50 Mbps (4K), Network 100 Mbps

```
Download speed:  100 Mbps = 12.5 MB/s
Consume speed:   50 Mbps  = 6.25 MB/s
Fill rate:       12.5 - 6.25 = 6.25 MB/s

Timeline:
  t=0.0s:  Start loading
  t=0.4s:  Buffer = 2.5s content → playback starts

  maxBuffer check: 50s content = 312.5 MB > 137.5 MB target
  → targetBufferSize will be hit FIRST

  t=22.4s: allocated = 137.5 MB → targetBufferSizeReached = true
           bufferedDuration ≈ 22s (not yet maxBuffer)
           → CASE 2: isLoading = false
           → PRELOAD STARTS (after ~22 seconds real time)
```

### Example 3: Video 10 Mbps, Slow Network 15 Mbps

```
Download speed:  15 Mbps = 1.875 MB/s
Consume speed:   10 Mbps = 1.25 MB/s
Fill rate:       1.875 - 1.25 = 0.625 MB/s (very slow!)

Timeline:
  t=0.0s:  Start loading
  t=1.7s:  Buffer = 2.5s → playback starts

  50s content = 62.5 MB
  62.5 MB / 0.625 MB/s = 100 seconds to fill

  t=101.7s: bufferedDuration = 50s → isLoading = false
            → PRELOAD STARTS (after ~102 seconds!)
```

### Example 4: Video 10 Mbps, Network Slower Than Bitrate 8 Mbps

```
Download speed:  8 Mbps = 1 MB/s
Consume speed:   10 Mbps = 1.25 MB/s
Fill rate:       1 - 1.25 = -0.25 MB/s (NEGATIVE!)

→ Buffer DECREASES over time → player will buffer/lag
→ isLoading = true FOREVER → preload NEVER runs
```

### Summary: When Does Preload Start? (Real-World Timing)

| Scenario | Fill Rate | Preload starts after |
|---|---|---|
| 10 Mbps video, 100 Mbps network | 11.25 MB/s | ~6 seconds |
| 50 Mbps 4K video, 100 Mbps network | 6.25 MB/s | ~22 seconds |
| 10 Mbps video, 15 Mbps slow network | 0.625 MB/s | ~102 seconds |
| 10 Mbps video, 8 Mbps very slow network | negative | Never |

---

## 7. When LoadControl Refuses Preload Loading

When `shouldContinueLoading()` returns `false` for preload (preload byte budget full), `PreloadMediaSource` handles it with retry logic (lines 620-643):

```
LoadControl refuses
  → Retry up to 10 times, every 100ms
  → If still refused → call preloadControl.onLoadingUnableToContinue()
      → DefaultPreloadManager clears lowest-priority source to free memory
      → Reset retry counter, try again
  → If nothing can be cleared → retry indefinitely (100ms interval)
      waiting for player to release buffer
```

---

## 7. Complete Preload Flow While Player is Playing

```
Player starts playing item A
  ├── buffer = 0s, isLoading = true
  ├── Player loads... buffer reaches 2.5s → playback starts
  ├── Player loads... buffer passes 10s → CASE 3: isLoading stays true
  ├── Player loads... buffer reaches 50s → CASE 2: isLoading = false
  │
  ├── shouldContinuePreloading() → all isLoading = false → return true
  ├── PRELOAD STARTS for item B (next in priority)
  │
  ├── Player consumes buffer... drops to 30s → CASE 3: no change, isLoading stays false
  ├── Preload continues running
  │
  ├── Player consumes buffer... drops below 10s → CASE 1: isLoading = true
  ├── shouldContinuePreloading() → isLoading = true → return false
  ├── PRELOAD PAUSES
  │
  ├── Player loads again... buffer reaches 50s → isLoading = false
  ├── PRELOAD RESUMES
  │
  └── Player transitions to item B → isUsedByPlayer() = true → preload hands off data
```

---

## 8. Summary Table

| Condition | Player `isLoading` | Preload can run? |
|---|---|---|
| buffer < minBuffer (10s), streaming, bytes not full | `true` | No |
| buffer < minBuffer (10s), streaming, **bytes full** | `false` | **Yes** (risky) |
| buffer < minBuffer (10s), local | `true` | No |
| minBuffer <= buffer < maxBuffer (unchanged) | previous value | Depends |
| buffer >= maxBuffer (50s) | `false` | **Yes** |
| targetBufferSizeReached (any time) | `false` | **Yes** |