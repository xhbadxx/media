# Default Buffer Config Change History (ExoPlayer → Media3)

## Summary Table

| Parameter | ExoPlayer ≤2.7.3 | ExoPlayer 2.8.0 | ExoPlayer 2.10.1 (video) | Media3 ≥1.6.0 |
|---|---|---|---|---|
| **minBuffer** | 15,000 ms (15s) | 15,000 ms (15s) | **50,000 ms (50s)** | 50,000 ms (50s) |
| **maxBuffer** | 30,000 ms (30s) | **50,000 ms (50s)** | 50,000 ms (50s) | 50,000 ms (50s) |
| **bufferForPlayback** | 2,500 ms (2.5s) | 2,500 ms (2.5s) | 2,500 ms (2.5s) | **1,000 ms (1s)** |
| **bufferForPlaybackAfterRebuffer** | 5,000 ms (5s) | 5,000 ms (5s) | 5,000 ms (5s) | **2,000 ms (2s)** |

---

## Change Details

### 1. ExoPlayer ≤ 2.7.3 — Original Values

```
DEFAULT_MIN_BUFFER_MS                      = 15,000 ms (15s)
DEFAULT_MAX_BUFFER_MS                      = 30,000 ms (30s)
DEFAULT_BUFFER_FOR_PLAYBACK_MS             =  2,500 ms (2.5s)
DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER =  5,000 ms (5s)
```

### 2. ExoPlayer 2.8.0 (May 2018) — Increased maxBuffer to 50s

- `DEFAULT_MAX_BUFFER_MS`: 30,000 → **50,000**
- **Reason**: A larger buffer helps the player withstand short-term network weakness, reducing rebuffers. Memory usage doesn't increase because it's still limited by `DEFAULT_TARGET_BUFFER_BYTES`.
- Commit: https://github.com/google/ExoPlayer/commit/2a737eecc1ec1aee0240bf0dce9dee476f6fae70

### 3. ExoPlayer 2.10.0 (Apr 2019) — minBuffer (video) = maxBuffer

- minBuffer for **video**: 15,000 → **50,000** (equal to maxBuffer)
- minBuffer for **audio-only**: kept at 15,000 (later also increased to 50,000 in version 2.12.0)
- **Reason — Shift from Bursty to Drip-feeding**:
  - **Google's internal experiment results**: Significant reduction in rebuffers, negligible impact on battery.
  - **Third-party confirmation**: Luchianenco Filip reported a **4x reduction** in rebuffers with drip-feeding. With 1 million views/day → 40,000 fewer rebuffer events per day.
- Commit: https://github.com/google/ExoPlayer/commit/9725132e3c09280c2532f2de3077e9cd6ba28b1d
- Issue: https://github.com/google/ExoPlayer/issues/2083

---

## Bursty vs Drip-feeding Explained

### Terminology
- **Bursty** = loading in large bursts/batches (burst = a sudden spike of activity)
- **Drip-feeding** = loading continuously in small increments (drip = a small steady trickle)

### Bursty — Batch Loading (old: min=15s, max=50s)

```
Buffer level (s)
50s |████████                          ████████
    |        ██                      ██
    |          ██                  ██
    |            ██              ██
15s |              ██          ██          ← hits min → starts loading again
    |                ██      ██
    |                  ██  ██
 0s |                    ██               ← DANGEROUS if network is weak here
    |------------------------------------→ time
     [LOAD]  [STOP - no loading]  [LOAD]
```

**How it works**:
1. Player loads data **in one large burst** until buffer is full at 50s
2. **Stops completely**, network connection goes idle (radio sleeps)
3. Video plays, buffer gradually depletes
4. When buffer drops to 15s (minBuffer) → starts loading a new batch
5. **35s gap with no loading at all** — this is the biggest weakness:
   - During these 35s the player **loads no additional data**, only consuming existing buffer
   - Buffer is gradually dropping from 50s → 15s
   - When buffer hits 15s (minBuffer) → player starts loading again. At this point there's still 15s of reserve
   - **But if the network is weak** → download speed < video playback speed → buffer continues dropping despite loading: 15s → 10s → 5s → 0s → **rebuffer** (video stalls, loading spinner appears)
   - 15s of reserve is only enough if the network recovers within 15s. If the weakness lasts longer → not enough
   - **Compared to drip-feeding**: when the network is weak, buffer is at ~50s → **50s of reserve** instead of just 15s → can withstand weak network 3x longer
   - The problem: **no control over** when the network will weaken in the load cycle — if network weakens when buffer is at 50s it's fine, but if it happens right at 15s there's only 15s to survive

**Advantage (old theory)**: Radio sleeps between bursts → saves battery. This idea was based on mobile carrier information from ~2012-2013, suggesting that batch loading helps the radio enter power-saving mode.

### Drip-feeding — Continuous Small Loading (new: min=max=50s)

```
Buffer level (s)
50s |██████████████████████████████████████████
    |                                          ← always maintained near 50s
    |
    |
15s |
    |
    |
 0s |
    |------------------------------------→ time
     [Loading continuously, steadily]
```

**How it works**:
1. Player loads data **continuously, in small increments** to keep buffer always at 50s
2. Since min = max = 50s → player **never stops loading** (unless already full at 50s)
3. Network weak? Buffer is still near 50s → plenty of time to wait for network recovery
4. No dangerous gaps

**Downside**: Radio is active more continuously → slightly more battery usage. But according to Google's experiments, the additional drain is negligible compared to the benefit of reduced rebuffers.

### Comparison with a Concrete Example

Suppose you're watching a video and the network drops for 10 seconds:

| | Bursty (batch) | Drip-feeding (continuous) |
|---|---|---|
| Buffer when network drops (worst case) | ~15s (just hit min) | ~50s |
| After 10s of no network | **5s** → about to rebuffer! | **40s** → comfortable |
| Buffer when network drops (best case) | ~50s (just finished loading) | ~50s |

With bursty, **luck or bad luck** depends on when the network drops in the load cycle. With drip-feeding, buffer is **always near 50s** regardless of timing → much more stable.

### 4. Media3 1.6.0 (Mar 2025) — Reduced Playback Start Thresholds

- `DEFAULT_BUFFER_FOR_PLAYBACK_MS`: 2,500 → **1,000**
- `DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS`: 5,000 → **2,000**
- **Reason**: With drip-feeding (min=max=50s) keeping the buffer always full, the high "safety margin" from before is no longer needed. Lowering the thresholds means:
  - Video starts playing faster after seek (2.5s → 1s wait)
  - Resume after rebuffer is faster (5s → 2s wait)

> **Note**: Google has never published their internal experiment data. Third-party numbers confirm the direction is correct but are not Google's original data.

---

## Shortform Demo — Custom Config

File: `demos/shortform/.../viewpager/ViewPagerMediaAdapter.kt`

```kotlin
LOAD_CONTROL_MIN_BUFFER_MS             =  5,000 ms (5s)   // default: 50,000
LOAD_CONTROL_MAX_BUFFER_MS             = 20,000 ms (20s)   // default: 50,000
LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS    =    500 ms (0.5s)  // default:  1,000
bufferForPlaybackAfterRebuffer         =  2,000 ms (2s)    // default (not customized)
```

---

## References

### GitHub Issues & Commits
- [Issue #2083 — Re-evaluate + refine buffer policy](https://github.com/google/ExoPlayer/issues/2083) — Main discussion on buffer policy
- [Issue #3905 — After ExoPlayer updates can't increase buffer size](https://github.com/google/ExoPlayer/issues/3905)
- [Issue #6634 — minBufferVideo = maxBuffer is intentional](https://github.com/google/ExoPlayer/issues/6634)
- [PR #2113 — Allow shouldStartPlayback to have last rebuffer time](https://github.com/androidx/media/pull/2113)

### Source Code
- [DefaultLoadControl.java (ExoPlayer release-v2)](https://github.com/google/ExoPlayer/blob/release-v2/library/core/src/main/java/com/google/android/exoplayer2/DefaultLoadControl.java)
- Media3: `libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java`

### Blog Posts & Articles
- [Akamai: Enhancing Video Streaming Quality for ExoPlayer - Part 2 (Buffering Strategy)](https://www.akamai.com/blog/performance/enhancing-video-streaming-quality-for-exoplayer-part-2-exoplayers-buffering-strategy-how-to-lower) — Detailed analysis of bursty vs drip-feeding
- [Luchianenco Filip: How to get 4x better re-buffering with drip-feeding](https://medium.com/@filipluch/how-to-improve-buffering-by-4-times-with-drip-feeding-technique-in-exoplayer-on-android-b59eb0c4d9cc) — Real-world drip-feeding results
- [Google: ExoPlayer 2.10 — What's New](https://medium.com/google-exoplayer/exoplayer-2-10-whats-new-15d344eaa8b9)
- [Google: ExoPlayer 2.12 — What's New](https://medium.com/google-exoplayer/exoplayer-2-12-whats-new-e43ef8ff72e7)
- [Ian Bird: Controlling memory with ExoPlayer LoadControl](https://ianbird.dev/exoplayer-loadcontrol/)

### Academic Papers
- [Huang et al., "A Buffer-Based Approach to Rate Adaptation" (SIGCOMM 2014)](https://dl.acm.org/doi/10.1145/2619239.2626296) — Foundational BBA paper: buffer-only decisions reduce rebuffers by 10-20%
- [BOLA: Near-Optimal Bitrate Adaptation for Online Videos](https://arxiv.org/pdf/1601.06748) — Buffer-based ABR using Lyapunov optimization, achieves 84-95% of offline optimal
- [GreenABR: Energy-Aware Adaptive Bitrate Streaming (MMSys 2022)](https://dl.acm.org/doi/10.1145/3524273.3528188) — DRL-based ABR saving 57% energy, reducing 84% rebuffering
- [Candid with YouTube: Adaptive Streaming Behavior (NOSSDAV 2017)](https://satadalsengupta.github.io/docs/papers/2017_nossdav_youtubedash.pdf) — Analysis of YouTube DASH implementation