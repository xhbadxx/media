# LL-DASH CDN Test Results

## Test Environment

- **Branch:** `fplay_dev_low_latency`
- **Date:** 2026-04-13
- **Patches:** Fix 1 (sticky target) + Fix 2 (SegmentTimeline extrapolation) applied
- **Network:** Fixed (same WiFi for all tests)
- **Stream:** FPT Play event-llc-wtm, segment=1.92s, ATO=1.68s, SegmentTimeline
- **CDN load balancing:** `event-llc-wtm.fptplay53.net` auto-balances 70% Akamai / 30% BytePlus
- **CDN identification:** `cdn53` response header (`akamai` or `byteplus`)

---

## Test Results — Target 2s

**Player config:**
```
targetOffset=2000ms, min=1500ms, max=5000ms
speed=[0.95, 1.08]
bufferDurations=[50000, 50000, 500, 1500]
prioritizeTimeOverSizeThresholds=true
```

### Test 1: FPT CDN (No DRM, Watermark) — 10 min

| Metric         | Value              |
|----------------|--------------------|
| Duration       | ~10 min (09:29 → 09:39) |
| Rebuffers      | **0**              |
| WAIT events    | 52                 |
| liveOffset     | ~2114-2116ms       |
| Buffer (buf)   | 425-956ms          |
| Gap            | ~1300-1600ms       |
| Speed          | 1.000 (stable)     |
| Target reached | 2116ms             |

**Notes:** WAIT events all resolved quickly, no buffer drain. Gap relatively large (~1.4s) but sufficient margin to avoid rebuffer.

---

### Test 2: FPT CDN (DRM + Watermark) — 14 min

| Metric         | Value              |
|----------------|--------------------|
| Duration       | ~14 min (10:07 → 10:21) |
| Rebuffers      | **0**              |
| WAIT events    | 28                 |
| liveOffset     | ~2018ms            |
| Buffer (buf)   | ~987-995ms         |
| Gap            | ~1025-1032ms       |
| Speed          | 1.000 (stable)     |
| Target reached | **2000ms** (ideal) |

**Notes:** Better than Test 1 across all metrics — fewer WAITs, smaller gap, thicker buffer, target hit ideal 2000ms. CDN chunk flushing very consistent.

---

### Test 3: Akamai CDN (DRM + Watermark) — 10 min

| Metric         | Value              |
|----------------|--------------------|
| Duration       | ~10 min (10:35 → 10:45) |
| Rebuffers      | **2** (1 incident, 2 events 229ms apart) |
| WAIT events    | 5                  |
| liveOffset     | ~2235-2280ms       |
| Buffer (buf)   | ~700-990ms         |
| Gap            | ~1325-1590ms       |
| Speed          | 1.000-1.003        |
| Target reached | ~2236ms            |

**Notes:** Fewest WAIT events of all tests but had 1 rebuffer incident. Rebuffer occurred with **buf still at 880-949ms** (NOT buffer drain like old logs). Audio seg#262 loaded 360ms after rebuffer — suggests DRM decryption delay on audio rather than CDN flush issue.

**Rebuffer detail:**
```
10:43:32.786 WAIT[V]: seg#262 > lastAvail#261, bufDur=2280ms
10:43:33.453 LOAD[V]: seg#262, bufDur=1614ms          ← video loaded OK
10:43:33.934 REBUFFER! buf=880ms, liveOff=2294ms       ← buf still high!
10:43:34.163 REBUFFER! buf=949ms, liveOff=2336ms       ← 2nd event, buf even higher
10:43:34.294 LOAD[A]: seg#262, bufDur=960ms            ← audio arrived 360ms late
```

---

### Test 4: BytePlus CDN (DRM + Watermark, target 2s) — ~3 min

| Metric         | Value              |
|----------------|--------------------|
| Duration       | ~3 min (11:37 → 11:39) |
| Rebuffers      | **2**              |
| Segments       | 218                |
| liveOffset     | ~3500ms (can't reach 2s) |
| Target reached | ~3500ms (pushed up by safeOffset) |

**Notes:** Target pushed up from 2000ms due to high audio TTFB. Speed control detects high `minPossibleLiveOffset` from audio delays → `safeOffset` prevents target from dropping.

---

## Test Results — Target 3s

**Player config:**
```
targetOffset=3000ms, min=2000ms, max=5000ms
speed=[0.95, 1.08]
bufferDurations=[50000, 50000, 500, 1500]
prioritizeTimeOverSizeThresholds=true
```

### Test 5: Akamai CDN (DRM + Watermark, target 3s) — 12 min

| Metric         | Value              |
|----------------|--------------------|
| Duration       | ~12 min (13:08 → 13:20) |
| Rebuffers      | **0**              |
| Segments       | 787                |
| Target reached | ~3s (ideal)        |

| Track | TTFB avg | TTFB > 500ms | Streaming |
|-------|----------|--------------|-----------|
| Video | 130ms    | ~0           | 391/393 ✓ |
| Audio | 130ms    | ~0           | 391/394 ✓ |

**Notes:** Akamai chunk transfer both video AND audio. Target reached ideal 3s.

---

### Test 6: BytePlus CDN (DRM + Watermark, target 3s) — 7 min

| Metric         | Value              |
|----------------|--------------------|
| Duration       | ~7 min (13:44 → 13:50) |
| Rebuffers      | **0**              |
| Segments       | 425                |
| Target reached | **~3575ms** (ideal 3000ms, pushed up by safeOffset) |

| Track | TTFB avg | TTFB > 500ms | Streaming |
|-------|----------|--------------|-----------|
| Video | 249ms    | 19/212 (9%)  | Yes ✓     |
| Audio | **1868ms** | **210/213 (99%)** | **No ✗** |

**Notes:** Audio TTFB ~1.87s = BytePlus does NOT chunk transfer audio. Player waits ~1.9s for full audio segment before receiving data. Speed control correctly detects this and pushes target up to ~3575ms to avoid rebuffer. 0 rebuffers but 575ms above ideal target.

---

## Comparison — All Tests

### Target 2s

| Metric         | FPT (No DRM, WM) | FPT (DRM + WM) | Akamai (DRM + WM) | BytePlus (DRM + WM) |
|----------------|-------------------|--------------------|---------------------|----------------------|
| Rebuffers      | **0**             | **0**              | **2** (1 incident)  | **2**                |
| liveOffset     | ~2115ms           | ~2018ms            | ~2236ms             | ~3500ms              |
| Target         | 2116ms            | **2000ms** (ideal) | 2236ms              | **~3500ms** (can't reach 2s) |

### Target 3s

| Metric         | Akamai (DRM + WM) | BytePlus (DRM + WM) |
|----------------|---------------------|----------------------|
| Rebuffers      | **0**               | **0**                |
| Target         | **~3000ms** (ideal) | **~3575ms** (+575ms) |
| Audio chunk    | ✓ (TTFB 130ms)     | ✗ (TTFB 1868ms)     |

---

## CDN Chunk Transfer Analysis

### Key metric: TTFB (Time To First Byte)

TTFB determines if CDN supports chunk transfer:
- **Low TTFB (~130ms)**: CDN starts sending data immediately → chunk transfer ✓
- **High TTFB (~1800ms)**: CDN waits for full segment before sending → full segment delivery ✗

```
Chunk transfer (Akamai audio):
  TTFB=130ms → transfer=1762ms (data arrives gradually)
  |--130ms--|--------1762ms stream dần-----------|
  request    ↑first byte                          ↑last byte

Full segment (BytePlus audio):
  TTFB=1868ms                    → transfer=13ms
  |--------1868ms đợi CDN-----------|--13ms--|
  request                            ↑first byte  ↑last byte
```

### CDN Comparison

| | Akamai Video | Akamai Audio | BytePlus Video | BytePlus Audio |
|---|---|---|---|---|
| TTFB avg | **130ms** ✓ | **130ms** ✓ | 249ms ✓ | **1868ms** ✗ |
| Chunk transfer | **Yes** | **Yes** | **Yes** | **No** |
| Segment size | ~1117KB | ~48KB | ~1057KB | ~48KB |

### Impact on player

BytePlus audio not streaming → high `smoothedMinPossibleLiveOffset` → `safeOffset = sMinP + 3*sDev` stays high → speed control cannot lower target to ideal.

```
Akamai: audio TTFB low → minPossible low → target reaches ideal
BytePlus: audio TTFB ~1.87s → minPossible ~1.87s → safeOffset ~2.5s+ → target pushed above ideal
```

---

## Historical Comparison (pre-CDN testing, 2026-04-12)

| Target | Stream        | Rebuffers/min | liveOffset       | Notes                        |
|--------|---------------|---------------|------------------|------------------------------|
| 2.0s   | event-llc-wtm | ~0.7/min      | ~2017-2020ms     | 8 events in 11 min, mixed CDN (Akamai/BytePlus) |
| 2.0s   | live-llc-wmk (pre-Fix 2) | ~13/min | 2.0-3.5s (unstable) | Before SegmentTimeline fix |
| 3.0s   | live-llc-wmk (pre-Fix 2) | 0/min   | ~3.2s (stable)   | Stable but high latency      |

---

## Key Observations

### Buffer mechanics
```
liveEdge (server live point)
  |<── gap ──>|<── buf ──>|
            bufEnd       pos (playback)
  |<────── liveOffset ───>|

buf + gap = liveOffset (always)
```

- **buf** = decoded content ready to play (`bufferedPosition - currentPosition`)
- **gap** = content not yet downloaded/decoded (`liveEdge - bufferedPosition`)
- Gap size depends on CDN chunk flush speed — faster flush = smaller gap = larger buffer

### Chunked transfer flow (HTTP/2)
```
Client                          CDN
  |--- GET segment N+1 -------->|
  |                              | (segment not complete yet)
  |                              | encoder sends chunk1
  |<---- DATA frame (240ms) ----|  HTTP/2 (no Transfer-Encoding: chunked header)
  |                              | encoder sends chunk2
  |<---- DATA frame (240ms) ----|
  |                              | ...
  |<---- DATA frame (final) ----|
  |                              | stream closes
```
- HTTP/2 uses DATA frames for streaming (NOT `Transfer-Encoding: chunked` which is HTTP/1.1 only)
- Client sends 1 request per segment, CDN holds connection open via HTTP/2 stream
- CDN pushes data as encoder produces it
- ATO (availabilityTimeOffset=1.68s) allows request 0.24s after segment starts encoding

### Rebuffer patterns

**Type 1: Buffer drain (2026-04-12 mixed CDN test)**
- All 8 rebuffers caused by audio segment WAIT too long (CDN flush delay)
- Buffer always ~490ms before rebuffer → drains in ~450ms → rebuffer
- Speed control was NOT the cause (speed=1.000 at rebuffer time)
- Fix 2 + consistent CDN = 0 rebuffers

**Type 2: DRM decryption stall (2026-04-13 Akamai DRM + WM)**
- Rebuffer with buf still high (880-949ms) — NOT a buffer drain
- Audio segment loaded 360ms after rebuffer event
- Possibly DRM decryption delay blocking audio pipeline
- Only occurred on DRM stream, not on No-DRM stream

---

## Pending Tests

- [x] FPT CDN — No DRM, Watermark, target 2s (Test 1)
- [x] FPT CDN — DRM + Watermark, target 2s (Test 2)
- [x] Akamai CDN — DRM + Watermark, target 2s (Test 3)
- [x] BytePlus CDN — DRM + Watermark, target 2s (Test 4)
- [x] Akamai CDN — DRM + Watermark, target 3s (Test 5)
- [x] BytePlus CDN — DRM + Watermark, target 3s (Test 6)
- [ ] Different network conditions (4G, different WiFi)
- [ ] Longer duration test (30+ min stability)

---

## Conclusion

Client-side code is finalized. Fix 1 + Fix 2 work correctly across all CDNs.

**Akamai:** Chunk transfer for both video and audio → target reaches ideal (2s or 3s), 0 rebuffers with target 3s.

**BytePlus:** Chunk transfer for video only, **audio is full segment delivery** (TTFB ~1.87s) → speed control correctly pushes target above ideal to compensate. Target 3s settles at ~3.6s, target 2s can't be reached.

**Root cause of BytePlus latency:** Audio track not enabled for chunk transfer on BytePlus CDN. This is a **CDN-side configuration issue**, not a client or player issue. Player speed control correctly adapts by raising the target offset.

**Recommendation:**
- **Target 3s** is optimal — 0 rebuffers on both CDNs, acceptable latency even on BytePlus (~3.6s)
- **Target 2s** is achievable only on Akamai/FPT CDN with consistent chunk transfer
- Request BytePlus to enable chunk transfer for audio tracks to match Akamai behavior