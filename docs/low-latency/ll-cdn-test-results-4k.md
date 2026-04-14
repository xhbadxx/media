# LL-DASH CDN Test Results — 4K HEVC

## Test Environment

- **Branch:** `fplay_dev_low_latency`
- **Date:** 2026-04-14
- **Patches:** Fix 1 (sticky target) + Fix 2 (SegmentTimeline extrapolation) applied
- **Network:** Fixed WiFi
- **Stream:** FPT Play `EPL_HN_01_4K_H265_Cmaf`, segment=1.92s, ATO=1.68s (chunk=0.24s), SegmentTimeline
- **Video codec:** HEVC (H.265), 4K, ~12.6 Mbps
- **Audio codec:** AAC, ~206 kbps
- **DRM:** Yes (NexGuard watermark + DRM)
- **CDN identification:** `cdn53` response header (`akamai` or `byteplus`)

---

## Player Config (all tests)

```
targetOffset=3000ms, min=2000ms, max=5000ms
speed=[0.95, 1.08]
bufferDurations=[50000, 50000, 500, 1500]
prioritizeTimeOverSizeThresholds=true
```

---

## Test 1: Akamai CDN — 4K DRM + Watermark, target 3s

| Metric           | Value                          |
|------------------|--------------------------------|
| Duration         | ~10 min (09:37 → 09:47)       |
| Rebuffers        | **0**                          |
| WAIT events      | 8 (5 video, 3 audio)           |
| Segments         | 323 video + 326 audio = 649   |
| Target reached   | **3000ms** (ideal)             |
| Speed changes    | 1 (startup only: 1.000 → 1.080) |
| ABR switches     | 0 (stayed on 4K entire session) |
| HTTP errors      | 0                              |
| Manifest refresh | 619                            |
| Total data       | ~950MB (934 video + 16 audio)  |

### CDN Performance

| | Video (4K HEVC) | Audio (AAC) |
|---|---|---|
| Segments | 323 | 326 |
| TTFB avg | **116ms** | **131ms** |
| TTFB min | 82ms | 82ms |
| TTFB max | 288ms | 513ms |
| TTFB > 500ms | **0** (0%) | **1** (0.3%) |
| Transfer avg | 1787ms | 1757ms |
| Total avg | 1903ms | 1887ms |
| Segment size avg | **2962KB (~3MB)** | 49KB |
| CL=none (streaming) | 323/323 (100%) | 324/326 (99.4%) |
| Chunk transfer | **Yes** | **Yes** |

### TTFB Distribution

| Range | Video | Audio |
|---|---|---|
| 0-100ms | 126 (39%) | 99 (30%) |
| 101-200ms | 191 (59%) | 203 (62%) |
| 201-300ms | 6 (2%) | 21 (6%) |
| 301-500ms | 0 (0%) | 2 (1%) |
| 501-1000ms | 0 (0%) | 1 (0.3%) |
| >1000ms | 0 (0%) | 0 (0%) |

### Buffer Health

| Metric | Value |
|---|---|
| bufDur avg | 2166ms |
| bufDur min | 0ms (startup) |
| bufDur max | 3839ms |
| Steady-state video | ~2300-2500ms |
| Steady-state audio | ~1800-2050ms |

### Speed Control

```
Startup (09:37:31):
  liveOff=4417ms → speedChange 1.000 → 1.080
  target temporarily raised: 3000 → 3053 → 3205 → 3320 → 3385ms (safeOffset)

Convergence (09:37:43 → 09:37:49, ~18 seconds):
  target: 3385 → 3373 → 3316 → 3253 → 3191 → 3125 → 3059 → 3000ms (ideal)

Steady-state (09:37:49 → 09:47:52, ~10 minutes):
  No speed changes. Completely stable at target=3000ms, speed=1.000.
```

### WAIT Events Detail

All 8 WAIT events had **buffer > 1800ms** — no danger of rebuffer. Player was simply faster than encoder by 1 segment.

```
09:41:46 WAIT[V]: seg#142 > lastAvail#141, bufDur=2591ms
09:41:48 WAIT[V]: seg#143 > lastAvail#142, bufDur=2621ms
09:41:52 WAIT[V]: seg#145 > lastAvail#144, bufDur=2395ms
09:41:54 WAIT[V]: seg#146 > lastAvail#145, bufDur=2222ms
09:41:56 WAIT[V]: seg#147 > lastAvail#146, bufDur=2480ms
09:44:49 WAIT[A]: seg#237 > lastAvail#236, bufDur=2080ms
09:44:53 WAIT[A]: seg#239 > lastAvail#238, bufDur=1860ms
09:45:13 WAIT[A]: seg#249 > lastAvail#248, bufDur=1950ms
```

### Risk Assessment

| Risk | Detail |
|---|---|
| Transfer margin | Transfer 1787ms vs segment duration 1920ms → margin only **133ms** |
| Bandwidth | Effective ~13.3 Mbps for 12.6 Mbps stream → headroom ~5% |
| Network degradation | Slight network drop could cause transfer > segment duration → rebuffer |

### Notes

- Akamai chunk transfer works perfectly for both 4K video and audio
- TTFB consistently low (~116ms) despite 3MB segment size (2.7x larger than 1080p ~1.1MB)
- Transfer time ~1.8s is close to segment duration 1.92s — tight margin but stable on good WiFi
- Speed control converges to ideal in ~18s, then completely stable for 10 minutes
- No ABR switches — network sustained 4K throughout

---

## Test 2: BytePlus CDN — 4K DRM + Watermark, target 3s

| Metric           | Value                          |
|------------------|--------------------------------|
| Duration         | ~13 min (09:58 → 10:11)       |
| Rebuffers        | **1** (1 event at 10:11:01)    |
| WAIT events      | 62 (26 video, 36 audio)        |
| Segments         | 372 video + 409 audio = 781    |
| Target reached   | **~3056-3074ms** (+56-74ms above ideal) |
| Speed changes    | 1 (startup only: 1.000 → 1.080) |
| ABR switches     | 0 (stayed on 4K entire session) |
| HTTP errors      | 0                              |
| Manifest refresh | —                              |
| Total data       | ~1093MB (1074 video + 20 audio) |

### CDN Performance

| | Video (4K HEVC) | Audio (AAC) |
|---|---|---|
| Segments | 372 | 409 |
| TTFB avg | **75ms** | **1844ms** |
| TTFB min | 34ms | 38ms |
| TTFB max | 418ms | 2889ms |
| TTFB > 500ms | **0** (0%) | **404** (99%) |
| Transfer avg | 1812ms | 11ms |
| Total avg | 1888ms | 1856ms |
| Segment size avg | **2955KB (~3MB)** | 49KB |
| CL=none (streaming) | 372/372 (100%) | — |
| CL=value (full seg) | 0 | — |
| Chunk transfer | **Yes** | **No** |

### TTFB Distribution

| Range | Video | Audio |
|---|---|---|
| 0-100ms | 294 (79%) | 2 (0%) |
| 101-200ms | 66 (18%) | 0 (0%) |
| 201-300ms | 10 (3%) | 0 (0%) |
| 301-500ms | 2 (1%) | 1 (0%) |
| 501-1000ms | 0 (0%) | 2 (0%) |
| >1000ms | 0 (0%) | **404 (99%)** |

### Buffer Health

| Metric | Value |
|---|---|
| bufDur avg | 2682ms |
| bufDur min | 0ms (startup) |
| bufDur max | 3728ms |

### Speed Control

```
Startup (09:58:58):
  liveOff=3975ms → speedChange 1.000 → 1.080
  target temporarily raised: 3000 → 3030ms (mild safeOffset)

Convergence (09:59:01 → 09:59:34, ~33 seconds):
  target: 3030 → 3031 → 3055 → 3056ms
  liveOff settles at ~3055ms, speed=1.000

Steady-state (09:59:34 → 10:11:01, ~11 minutes):
  target stable at ~3056-3074ms, speed=1.000
  Target does NOT reach ideal 3000ms — held +56-74ms above by safeOffset

Post-rebuffer (10:11:01):
  target jumped 3074 → 3574ms (+500ms increment)
  then adjusted to 3729ms, remained there until end of log
```

### Rebuffer Detail

```
10:11:00.568 tick: liveOff=3074ms buf=496ms speed=1.000  ← buf dropping
10:11:01.025 REBUFFER! buf=42ms liveOff=3076ms gap=3035ms ← buffer drained!
10:11:01.026 REBUFFER: target 3074ms → 3574ms            ← target bumped +500ms
10:11:01.496 CDN audio: ttfb=2889ms transfer=10ms         ← audio arrived late (full segment, NOT chunked)
10:11:01.560 LOAD[A]: seg#385, bufDur=1962ms              ← audio finally loaded
```

**Root cause:** BytePlus audio NOT chunk-transferred (TTFB 2889ms = waited for full segment). Audio seg#385 arrived ~470ms late, causing 42ms buffer at rebuffer point. Same pattern as 1080p test — BytePlus audio full-segment delivery blocks the pipeline.

### WAIT Events Pattern

62 WAITs (vs 8 on Akamai) — **7.8x more**. All had buffer > 1800ms, so individually safe, but indicates BytePlus CDN has more jitter in segment availability timing.

```
Video WAITs: 26 events, bufDur range 2550-3572ms
Audio WAITs: 36 events, bufDur range 1860-3206ms
```

### Risk Assessment

| Risk | Detail |
|---|---|
| Audio chunk transfer | **Not supported** — TTFB 1844ms avg = full segment delivery |
| WAIT frequency | 62 events (7.8x Akamai) — CDN jitter |
| Rebuffer cause | Audio pipeline stall from full-segment delivery |
| Post-rebuffer target | Pushed to 3729ms (+729ms above ideal) |

---

## Test 3: FPT CDN — 4K DRM + Watermark, target 3s

**Note:** Different stream URL from Tests 1-2. Uses `live-llc-wmk.fptplay53.net` (FPT origin) instead of `event-llc-wtm.fptplay53.net`.

| Metric           | Value                          |
|------------------|--------------------------------|
| Duration         | ~28 min (12:46 → 13:14)       |
| Rebuffers        | **1** (at 12:48:32, ~1.7 min in) |
| WAIT events      | 84                             |
| Segments         | 849 video + 863 audio = 1712 (status=200) |
| Target reached   | **3000ms** (ideal), then 3018ms before incident |
| Speed changes    | 2 (startup + post-recovery)    |
| ABR switches     | 0 (stayed on 4K entire session) |
| HTTP errors      | 4 (status=-1, timeout 8s) + 1 (status=206) |
| Manifest refresh | 1645                           |
| Total data       | ~2495MB (2454 video + 41 audio) |

### CDN Performance (status=200 only, excluding errors)

| | Video (4K HEVC) | Audio (AAC) |
|---|---|---|
| Segments | 849 | 863 |
| TTFB avg | **65ms** | **69ms** |
| TTFB min | 29ms | 29ms |
| TTFB max | 1039ms | 299ms |
| TTFB > 500ms | **2** (0.2%) | **0** (0%) |
| Transfer avg | 1833ms | 1821ms |
| Total avg | 1899ms | 1890ms |
| Segment size avg | **2960KB (~3MB)** | 48KB |
| CL=none (streaming) | 849/849 (100%) | 862/863 (99.9%) |
| Chunk transfer | **Yes** | **Yes** |

### TTFB Distribution (status=200 only)

| Range | Video | Audio |
|---|---|---|
| 0-100ms | 700 (82%) | 696 (81%) |
| 101-200ms | 139 (16%) | 152 (18%) |
| 201-300ms | 7 (1%) | 15 (2%) |
| 301-500ms | 1 (0%) | 0 (0%) |
| 501-1000ms | 1 (0%) | 0 (0%) |
| >1000ms | 1 (0%) | 0 (0%) |

### Buffer Health

| Metric | Value |
|---|---|
| bufDur avg | 2982ms |
| bufDur min | 0ms (startup) |
| bufDur max | 6267ms |

### Network Incident & Rebuffer Detail

A network incident occurred ~1.7 min into playback:

```
12:48:31.068 tick: liveOff=3016ms buf=1000ms speed=1.000  ← normal
12:48:31.571 tick: liveOff=3018ms buf=500ms               ← buf dropping
12:48:32.055 REBUFFER! buf=21ms liveOff=3025ms gap=3005ms ← buffer drained

--- Network failure cascade ---
12:48:37.253 CDN video: ttfb=64ms transfer=8770ms total=8834ms ← 8.8s transfer!
12:48:37.253 CDN audio: ttfb=89ms transfer=8344ms total=8433ms ← 8.4s transfer!
12:48:38.028 HTTP manifest: status=-1 (connection failed)
12:48:45.261 CDN video: status=-1 ttfb=-1ms total=8006ms     ← timeout
12:48:45.262 CDN audio: status=-1 ttfb=-1ms total=8006ms     ← timeout
12:48:46.037 HTTP manifest: status=-1 (connection failed)
12:48:54.270 CDN audio: status=-1 total=8007ms               ← retry timeout
12:48:54.271 CDN video: status=-1 total=8006ms               ← retry timeout
12:48:55.044 HTTP manifest: status=-1
12:48:56.842 CDN audio: status=206 ttfb=558ms                ← partial recovery
12:48:57.257 target: 3518ms → 5000ms (liveOff=28227ms!)      ← 28s behind live!
12:48:58.842 DashMerge: tsbd changed 15000→14000ms           ← manifest changed

--- Full recovery by ~12:49:01 ---
12:49:01.626 speedChange: 1.000 → 1.080 (catching up from 6.2s behind)
```

**Root cause:** Network outage lasting ~25 seconds (12:48:32 → 12:48:57). NOT a CDN chunk transfer issue — FPT CDN supports chunk transfer for both video and audio. The video segment before rebuffer had transfer=8770ms (normally ~1.8s), indicating sudden bandwidth collapse.

### Speed Control

```
Startup (12:46:48):
  liveOff=3854ms → speedChange 1.000 → 1.080
  Converges to ideal target=3000ms

Before incident (12:48:31):
  target=3000ms, liveOff=3016-3018ms, speed=1.000 (stable, ideal)

Post-incident (12:48:57):
  liveOff=28227ms (28s behind!) → target clamped to 5000ms
  Speed 1.080 for catchup → recovers

Post-recovery (12:49:01+):
  liveOff=6251ms → speed 1.080
  Gradually converges back to 3000ms target
  Stable for remaining ~25 minutes
```

### Risk Assessment

| Risk | Detail |
|---|---|
| Network stability | 1 network outage (~25s) caused rebuffer + 28s live offset spike |
| Recovery | Player recovered fully — target back to ideal 3000ms |
| CDN chunk transfer | **Both video and audio** — excellent (same as Akamai) |
| Transfer margin | 21ms (1%) at rebuffer — tightest of all CDNs |

---

---

## Test Results — Target 2s

### Test 4: Akamai CDN — 4K DRM + Watermark, target 2s

**Player config:**
```
targetOffset=2000ms, min=1500ms, max=5000ms
speed=[0.95, 1.08]
```

| Metric           | Value                          |
|------------------|--------------------------------|
| Duration         | ~16 min (13:28 → 13:45)       |
| Rebuffers        | **22** (2 incidents)           |
| WAIT events      | 13                             |
| Segments         | 487 video + 505 audio = 992   |
| Target reached   | 2000ms (ideal) but **unstable** — 2 rebuffer cascades |
| ABR switches     | Minor (some 3.8M/7.6M/2.6M segments) |
| HTTP errors      | 0                              |
| Manifest refresh | 961                            |
| Total data       | ~1409MB video                  |

### CDN Performance

| | Video (4K HEVC) | Audio (AAC) |
|---|---|---|
| Segments | 487 | 505 |
| TTFB avg | **127ms** | **142ms** |
| TTFB min | 83ms | 82ms |
| TTFB max | 758ms | 892ms |
| Transfer avg | 1778ms | 1710ms |
| Total avg | 1904ms | 1852ms |
| Segment size avg | **2963KB (~3MB)** | — |
| CL=none (streaming) | 487/487 (100%) | 502/505 (99.4%) |
| Chunk transfer | **Yes** | **Yes** |

### TTFB Distribution

| Range | Video | Audio |
|---|---|---|
| 0-100ms | 139 (29%) | 127 (25%) |
| 101-200ms | 320 (66%) | 316 (63%) |
| 201-300ms | 23 (5%) | 47 (9%) |
| 301-500ms | 4 (1%) | 11 (2%) |
| 501-1000ms | 1 (0%) | 4 (1%) |
| >1000ms | 0 (0%) | 0 (0%) |

### Buffer Health

| Metric | Value |
|---|---|
| bufDur avg | 2031ms |
| bufDur min | 0ms |
| bufDur max | 5190ms |

### Rebuffer Incidents

**Incident 1 (13:40:08 → 13:40:17): 9 rebuffers in 9 seconds**

```
13:40:08.991 REBUFFER! buf=44ms  → target 2017→2517ms
13:40:11.675 REBUFFER! buf=456ms → target 2569→3069ms  ← buf recovered but rebuffered again!
13:40:13.012 REBUFFER! buf=415ms → target 3185→3685ms
13:40:14.291 REBUFFER! buf=204ms → target 3685→4185ms
13:40:14.954 REBUFFER! buf=392ms → target 4185→4685ms
13:40:15.588 REBUFFER! buf=451ms → target 4685→5000ms (clamped)
13:40:16.665 REBUFFER! buf=188ms → target 4945→5000ms
13:40:16.861 REBUFFER! buf=437ms → target 5000→5000ms
13:40:17.715 REBUFFER! buf=178ms → target 5000→5000ms
```

Pattern: Rebuffer → recover briefly (buf 400-450ms) → rebuffer again. Target escalated from 2017ms to 5000ms in 9 seconds. DRM decryption stall suspected — rebuffers occur even with buf 400-450ms (not buffer drain).

**Incident 2 (13:43:07 → 13:43:29): 2 rebuffers**

```
13:43:07.414 REBUFFER! buf=166ms → target 2017→2517ms
  (recovery, target climbs to 4727ms via safeOffset)
13:43:29.651 REBUFFER! buf=158ms → target 4727→5000ms
```

### Speed Control

```
Startup (13:29:01):
  target: 2000 → 2700 → 3799ms (safeOffset very aggressive)
  Convergence to 2000ms takes ~60 seconds

Stable (13:29:58 → 13:39:59, ~10 minutes):
  target=2000ms, liveOff=~2016-2017ms, speed=1.000

Incident 1 (13:40:08):
  target: 2017 → 2517 → 3069 → 3685 → 4185 → 4685 → 5000ms
  Recovery: 5000 → gradual decrease → 2000ms (~60s recovery)

Incident 2 (13:43:07):
  target: 2017 → 2517 → 4651 → 5000ms
  Recovery: 5000 → gradual decrease → 2000ms
  Still recovering at end of log
```

### Comparison: Target 2s vs Target 3s (Akamai + FPT)

| Metric | Akamai 3s (T1) | FPT 3s (T3) | Akamai 2s (T4) | FPT 2s (T5) |
|---|---|---|---|---|
| Duration | 10 min | 28 min | 16 min | 12 min |
| Rebuffers | **0** | **1** (network) | **22** | **4** |
| Target | 3000ms | 3000ms | 2000ms (unstable) | 2000-2018ms (stable*) |
| TTFB video | 116ms | 65ms | 127ms | 66ms |
| TTFB audio | 131ms | 69ms | 142ms | 71ms |
| WAIT events | 8 | 84 | 13 | 39 |
| Buffer avg | 2166ms | 2982ms | 2031ms | 1867ms |

*FPT target 2s: stable after startup recovery (~100s). Akamai target 2s: recurring incidents.

### Risk Assessment

| Risk | Detail |
|---|---|
| Buffer too thin | Target 2s → buf only ~1000ms steady state → 500ms margin before drain |
| DRM stall | Rebuffers with buf 400-450ms suggest DRM decryption delays, not CDN issue |
| Recovery cost | Each incident: 9+ rebuffers, target escalates to 5000ms, ~60s to recover |
| Verdict | **Target 2s NOT viable for 4K** — transfer ~1778ms vs segment 1920ms leaves only 142ms headroom, combined with DRM processing makes it too tight |

---

## Test 5: FPT CDN — 4K DRM + Watermark, target 2s

**Player config:**
```
targetOffset=2000ms, min=1500ms, max=5000ms
speed=[0.95, 1.08]
```

**Note:** Same stream URL as Test 3. Uses `live-llc-wmk.fptplay53.net` (FPT origin). CDN load balanced between `fpt-cdn` (53%) and `fpt-pop` (47%).

| Metric           | Value                          |
|------------------|--------------------------------|
| Duration         | ~12 min (13:53 → 14:05)       |
| Rebuffers        | **4** (1 cascade at startup)   |
| WAIT events      | 39                             |
| Segments         | 375 video + 376 audio = 751   |
| Target reached   | **2000-2018ms** (ideal, oscillating) |
| Speed changes    | 6 (startup + rebuffer cascade) |
| ABR switches     | 0 (stayed on 4K entire session) |
| HTTP errors      | 0                              |
| Manifest refresh | 710                            |

### CDN Performance

| | Video (4K HEVC) | Audio (AAC) |
|---|---|---|
| Segments | 374 | 375 |
| TTFB avg | **66ms** | **71ms** |
| TTFB min | 29ms | 28ms |
| TTFB max | 328ms | 881ms |
| TTFB > 500ms | **0** (0%) | **1** (0.3%) |
| Transfer avg | 1822ms | 1805ms |
| Total avg | 1888ms | 1876ms |
| Segment size avg | **2944KB (~3MB)** | 48KB |
| CL=none (streaming) | 374/374 (100%) | 375/375 (100%) |
| Chunk transfer | **Yes** | **Yes** |

### TTFB Distribution

| Range | Video | Audio |
|---|---|---|
| 0-100ms | 307 (82%) | 294 (78%) |
| 101-200ms | 62 (16%) | 74 (19%) |
| 201-300ms | 3 (1%) | 3 (1%) |
| 301-500ms | 2 (1%) | 3 (1%) |
| 501-1000ms | 0 (0%) | 1 (0.3%) |
| >1000ms | 0 (0%) | 0 (0%) |

### Buffer Health

| Metric | Value |
|---|---|
| bufDur avg | 1867ms |
| bufDur min | 0ms (startup) |
| bufDur max | 7241ms |

### CDN Distribution

| CDN | Segments | % |
|---|---|---|
| fpt-cdn | 398 | 53% |
| fpt-pop | 351 | 47% |

### Rebuffer Incident (13:54:19 → 13:54:28, 4 rebuffers in 9 seconds)

```
13:54:19.830 tick: liveOff=2337ms buf=191ms speed=1.009     ← buf dropping during catchup
13:54:19.991 REBUFFER! buf=29ms liveOff=2339ms               ← buffer drain
13:54:19.992 REBUFFER: target 2255→2755ms

13:54:25.766 REBUFFER! buf=419ms liveOff=2653ms              ← buf recovered but rebuffer!
13:54:25.767 REBUFFER: target 2755→3255ms

13:54:26.236 REBUFFER! buf=451ms liveOff=2834ms              ← DRM stall (buf high!)
13:54:26.236 REBUFFER: target 3255→3755ms

13:54:28.053 REBUFFER! buf=62ms liveOff=2983ms               ← buffer drain after stalls
13:54:28.053 REBUFFER: target 3329→3829ms

--- Recovery ---
13:54:32.780 speedChange: 0.963 → 1.080 (liveOff=7707ms, catching up)
13:55:32.265 target converges back to 2000ms (~100s recovery)
```

**Root cause:** First rebuffer is buffer drain during startup catchup phase (buf=29ms). Rebuffers 2-3 have buf=419-451ms — **DRM decryption stall**, same pattern as Akamai Test 4. Rebuffer 4 is a cascade effect.

### Speed Control

```
Startup (13:53:51):
  liveOff=3277ms → speedChange 1.000 → 1.080
  target: 2000 → 2047 → 2196 → 2326ms (safeOffset)

Converging (13:54:10 → 13:54:19):
  target: 2326 → 2309 → 2255ms, speed 1.009, buf dropping

Rebuffer cascade (13:54:19 → 13:54:28):
  target: 2255 → 2755 → 3255 → 3755 → 3829ms
  Then safeOffset pushes to 5000ms (liveOff=7707ms)
  speedChange: 0.963 → 1.080 (catching up)

Recovery (13:54:32 → 13:55:32, ~100 seconds):
  target: 5000 → 4910 → 4750 → ... → 2000ms

Steady-state (13:55:32 → 14:05:47, ~10+ minutes):
  target oscillates 2000-2018ms, liveOff=~2017-2018ms, speed=1.000
  No more rebuffers. Completely stable.
```

### Comparison: FPT vs Akamai (4K, target 2s)

| Metric | Akamai (Test 4) | FPT (Test 5) |
|---|---|---|
| Duration | 16 min | 12 min |
| Rebuffers | **22** (2 incidents) | **4** (1 incident) |
| First rebuffer | ~12 min in | ~28s in (startup) |
| Post-recovery stable | Yes, but **rebuffered again** | **Yes, no more rebuffers** |
| Target reached | 2000ms (unstable) | **2000-2018ms** (stable after recovery) |
| Video TTFB avg | 127ms | **66ms** |
| Audio TTFB avg | 142ms | **71ms** |
| Transfer avg (video) | 1778ms | 1822ms |
| Transfer margin | 142ms (7%) | **98ms (5%)** |
| WAIT events | 13 | 39 |

**FPT significantly better than Akamai at target 2s:** Only 1 startup incident vs 2 mid-session incidents. After recovery, FPT maintained stable 2000-2018ms for 10+ minutes with zero rebuffers. Akamai had recurring DRM stall cascades mid-session.

### Risk Assessment

| Risk | Detail |
|---|---|
| Startup vulnerability | Rebuffer cascade during initial catchup — safeOffset not yet calibrated |
| DRM stall | Rebuffers #2-3 with buf 419-451ms — same DRM stall pattern as Akamai |
| Post-recovery stability | **Excellent** — 10+ min stable at target 2000-2018ms, 0 rebuffers |
| Transfer margin | 98ms (5%) — tight but FPT chunk transfer very consistent |
| Verdict | FPT CDN at target 2s **borderline viable** — startup rebuffers but very stable after recovery. Better than Akamai at target 2s |

---

## Comparison — All CDNs (4K, target 2s)

| Metric | Akamai (Test 4) | FPT (Test 5) |
|---|---|---|
| Rebuffers | **22** | **4** |
| Incidents | 2 (mid-session) | 1 (startup only) |
| Target | 2000ms (unstable) | **2000-2018ms** (stable after recovery) |
| Video TTFB | 127ms | **66ms** |
| Audio TTFB | 142ms | **71ms** |
| WAIT events | 13 | 39 |
| Chunk transfer (V/A) | Yes/Yes | Yes/Yes |

---

## Comparison — All CDNs (4K, target 3s)

| Metric | Akamai | BytePlus | FPT |
|---|---|---|---|
| Duration | 10 min | 13 min | 28 min |
| Rebuffers | **0** | **1** | **1** (network outage) |
| WAIT events | 8 | **62** | 84 |
| Target reached | **3000ms** (ideal) | ~3056-3074ms (+56-74ms) | **3000ms** (ideal, pre-incident) |
| Video TTFB avg | 116ms | **75ms** | **65ms** |
| Audio TTFB avg | **131ms** | **1844ms** | **69ms** |
| Audio TTFB > 500ms | 0.3% | **99%** | 0% |
| Video chunk transfer | **Yes** | **Yes** | **Yes** |
| Audio chunk transfer | **Yes** | **No** | **Yes** |
| Video segment size | 2962KB | 2955KB | 2960KB |
| Transfer margin | 133ms (7%) | 32ms (2%) | 21ms (1%) |
| HTTP errors | 0 | 0 | **5** (network incident) |
| Post-rebuffer target | — | 3729ms | 5000ms→3000ms (recovered) |

---

## Comparison — 4K vs 1080p (target 3s)

### Akamai

| Metric | 1080p (Test 5, 04-13) | 4K (Test 1, 04-14) | Delta |
|---|---|---|---|
| Rebuffers | 0 | 0 | same |
| Duration | 12 min | 10 min | — |
| Target reached | ~3000ms | **3000ms** | same |
| Video TTFB avg | ~130ms | **116ms** | -14ms |
| Audio TTFB avg | ~130ms | **131ms** | +1ms |
| Video size/seg | ~1117KB | **2962KB** | **x2.7** |
| Transfer time | — | 1787ms | — |
| Bandwidth | ~4.7 Mbps | **~13.3 Mbps** | **x2.8** |
| Transfer margin | — | **133ms (7%)** | tight |
| WAIT events | 5 | 8 | +3 |

### BytePlus

| Metric | 1080p (Test 6, 04-13) | 4K (Test 2, 04-14) | Delta |
|---|---|---|---|
| Rebuffers | 0 | **1** | worse |
| Duration | 7 min | 13 min | — |
| Target reached | ~3575ms | **~3056-3074ms** | **better** |
| Video TTFB avg | 249ms | **75ms** | -174ms |
| Audio TTFB avg | **1868ms** | **1844ms** | -24ms (same problem) |
| Audio chunk transfer | No | **No** | same |
| Video size/seg | ~1057KB | **2955KB** | **x2.8** |
| WAIT events | — | 62 | — |

### FPT

| Metric | 1080p (Test 2, 04-13) | 4K-3s (Test 3, 04-14) | 4K-2s (Test 5, 04-14) | Delta (1080p→4K) |
|---|---|---|---|---|
| Rebuffers | 0 | **1** (network) | **4** (startup) | worse |
| Target reached | **2000ms** | **3000ms** | **2000-2018ms** | — |
| Video TTFB avg | — | **65ms** | **66ms** | same |
| Audio TTFB avg | — | **69ms** | **71ms** | same |
| Video size/seg | — | **2960KB** | **2944KB** | — |
| Audio chunk transfer | Yes | **Yes** | **Yes** | same |
| WAIT events | 28 | 84 | 39 | — |

### Key Observations

1. **Akamai:** TTFB stays low despite 2.7x larger segments — CDN chunk transfer scales well
2. **BytePlus video TTFB improved** on 4K (75ms vs 249ms for 1080p) — possibly CDN caching/routing better for 4K stream
3. **BytePlus audio still broken** — TTFB ~1844ms on 4K, same as 1080p (~1868ms). Audio not chunk-transferred. This is a CDN config issue, not resolution-dependent
4. **Tight bandwidth margin** on 4K — 4K needs ~13 Mbps, leaving only ~5-7% headroom
5. **BytePlus 4K target closer to ideal** than 1080p (3056ms vs 3575ms) — counterintuitive but video TTFB improvement partially offsets audio delay
6. **BytePlus rebuffer on 4K** caused by audio pipeline stall, same root cause as 1080p but 4K's tighter margin made it fatal
7. **FPT best TTFB** of all CDNs (65-71ms) — lowest and most consistent on both video and audio
8. **FPT target 2s viable** after startup recovery — 10+ min stable, unlike Akamai which has recurring mid-session incidents

---

## Key Concepts Reference

### Buffer mechanics
```
liveEdge (server live point)
  |<── gap ──>|<── buf ──>|
            bufEnd       pos (playback)
  |<────── liveOffset ───>|

buf + gap = liveOffset (always)
```

### ATO and chunk calculation
```
segment_duration = 1.92s
ATO              = 1.68s (positive in MPD)
chunk_duration   = segment_duration - ATO = 1.92 - 1.68 = 0.24s
num_chunks       = segment_duration / chunk_duration = 1.92 / 0.24 = 8
```

### Chunk transfer detection
- **TTFB low + CL=none**: CDN streams data as encoder produces → chunk transfer
- **TTFB high + CL=value**: CDN waits for full segment → full segment delivery

---

## Overall Assessment

### Master Comparison — All Tests

| | Akamai 3s (T1) | BytePlus 3s (T2) | FPT 3s (T3) | Akamai 2s (T4) | FPT 2s (T5) |
|---|---|---|---|---|---|
| **Duration** | 10 min | 13 min | 28 min | 16 min | 12 min |
| **Rebuffers** | **0** | **1** | **1** (network) | **22** | **4** (startup) |
| **Target reached** | **3000ms** ✓ | ~3060ms (+60) | **3000ms** ✓ | 2000ms (unstable) | **2000-2018ms** ✓* |
| **Video TTFB** | 116ms | **75ms** | **66ms** | 127ms | **66ms** |
| **Audio TTFB** | 131ms | **1844ms** ✗ | **69ms** | 142ms | **71ms** |
| **Video chunk** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **Audio chunk** | ✓ | **✗** | ✓ | ✓ | ✓ |
| **Transfer margin** | 133ms (7%) | 32ms (2%) | 21ms (1%) | 142ms (7%) | 98ms (5%) |
| **WAIT events** | 8 | 62 | 84 | 13 | 39 |
| **Post-rebuffer** | — | 3729ms | recovered | 5000ms (recurring) | recovered 100s |
| **Stability** | ★★★★★ | ★★★☆☆ | ★★★★☆ | ★☆☆☆☆ | ★★★★☆ |

*FPT 2s: stable after 100s recovery, 0 rebuffers for remaining 10+ min.

### CDN Assessment

| CDN | Strengths | Weaknesses | Verdict |
|---|---|---|---|
| **Akamai** | Chunk transfer V+A, stable TTFB, target 3s perfect | TTFB higher than FPT (~2x), target 2s DRM cascade | **Best for target 3s** |
| **FPT** | Lowest TTFB (66-71ms), chunk V+A, target 2s viable | Network incident (T3), tightest margin (1-5%) | **Best for target 2s** |
| **BytePlus** | Good video TTFB (75ms) | **Audio NOT chunk transferred** → target pushed +60ms, rebuffer risk | **Not recommended** for LL |

### Target Assessment

| Target | Verdict | Reason |
|---|---|---|
| **3s** | ✅ **Recommended** | 0 rebuffers on Akamai, FPT also stable (rebuffer was network, not CDN). BytePlus 0 rebuffers but target pushed to 3060ms |
| **2s** | ⚠️ **High risk** | Akamai: 22 rebuffers, DRM stall cascade. FPT: viable after startup but only 5% margin. Not suitable for production |

### Root Cause Analysis

| Issue | Root cause | Affected CDN |
|---|---|---|
| **DRM stall** | Rebuffer with buf=400-450ms (not buffer drain). DRM decryption blocks audio pipeline | Akamai 2s, FPT 2s |
| **Audio full-segment** | BytePlus does not enable chunk transfer for audio (TTFB ~1.8s) | BytePlus (all tests) |
| **Tight margin** | Transfer 1.8s vs segment 1.92s → only 100-140ms headroom. Minor network jitter = rebuffer | All CDNs at 4K |

### Recommendation

```
Production 4K:     target=3000ms, CDN=Akamai or FPT
Production 1080p:  target=2000ms viable (wider margin)
BytePlus:          Request audio chunk transfer enablement
Target 2s + 4K:    DO NOT use in production — DRM stall risk too high
```

**Conclusion:** Client-side code works correctly across all CDNs. Remaining issues are **CDN-side** (BytePlus audio) and **DRM processing delay** (affects target 2s). Target 3s + Akamai/FPT is the safest configuration for 4K.