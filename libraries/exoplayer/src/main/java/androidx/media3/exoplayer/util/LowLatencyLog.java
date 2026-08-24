/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.util;

import android.util.Log;
import androidx.media3.common.util.UnstableApi;

/**
 * Centralized debug logging for low-latency DASH playback investigation.
 *
 * <p>Every low-latency related log call inside the fork goes through this helper so they share the
 * "{@value #TAG}" tag and a single {@link #enabled} toggle. Filter with {@code adb logcat -s
 * LL-Core:D} while tuning, flip {@link #enabled} to {@code false} for production builds.
 *
 * <p>Instrumented sites:
 *
 * <ul>
 *   <li>{@code DashMediaSource.updateLiveConfiguration} — dumps MediaItem vs ServiceDescription vs
 *       merged result each manifest refresh so you can verify client overrides actually win.
 *   <li>{@code DefaultLivePlaybackSpeedControl.setLiveConfiguration} — logs the live config
 *       handed to the speed controller.
 *   <li>{@code DefaultLivePlaybackSpeedControl.getAdjustedPlaybackSpeed} — rate-limited tick log
 *       (every {@value #TICK_LOG_INTERVAL_MS}ms) with liveOffset, current/ideal target, speed,
 *       buffered duration and smoothed minPossible.
 *   <li>{@code DefaultLivePlaybackSpeedControl.notifyRebuffer} — emits a REBUFFER marker and shows
 *       how the target was shifted.
 *   <li>{@code DashManifestParser.parsePeriod} — logs Period peer-max state ("[PeerMax]") once per
 *       MPD refresh so you can verify whether tracks are in sync and what holder value was set.
 *   <li>{@code SegmentBase.SegmentTemplate.getAvailableSegmentCount} — logs "[PeerMax] BUMP" only
 *       when the track-aware bump actually applies (count &lt; peerMax), so log volume stays bounded
 *       — you'll only see it for the lagging track under CCU load.
 * </ul>
 */
@UnstableApi
public final class LowLatencyLog {

  /** Shared logcat tag for every low-latency related message. */
  public static final String TAG = "LL-Core";

  /** Minimum interval between repeated tick logs from tight loops, in milliseconds. */
  public static final long TICK_LOG_INTERVAL_MS = 500L;

  /**
   * Log mode controlling verbosity.
   *
   * <ul>
   *   <li>{@link #MODE_OFF} — all LL-Core logs disabled.
   *   <li>{@link #MODE_FULL} — log everything (tick every 500ms, DashMerge every MUP, all speed
   *       changes). Use for deep debugging.
   *   <li>{@link #MODE_REBUFFER_TEST} — only log events relevant to rebuffer analysis: REBUFFER
   *       markers, targetAdjust, speedChange, tick only when buffer is low (below {@link
   *       #rebufferTestBufferThresholdMs}), and DashMerge only once at startup.
   * </ul>
   */
  public static final int MODE_OFF = 0;
  public static final int MODE_FULL = 1;
  public static final int MODE_REBUFFER_TEST = 2;

  /**
   * Current log mode. Normally {@link #MODE_REBUFFER_TEST} for lightweight rebuffer testing; bumped
   * to {@link #MODE_FULL} on 2026-07-29 to capture the full tick/speed/DashMerge stream while testing
   * the new API link — so any latency/buffer number we need is already in the log. Revert to {@link
   * #MODE_REBUFFER_TEST} for lightweight runs.
   */
  public static volatile int mode = MODE_REBUFFER_TEST;

  /**
   * Buffer threshold (ms) for {@link #MODE_REBUFFER_TEST}. Tick logs are only emitted when
   * buffered duration is below this value. Default 1500ms.
   */
  public static volatile long rebufferTestBufferThresholdMs = 1000L;

  /**
   * Master switch. Kept for backwards compatibility. Setting to {@code false} is equivalent to
   * {@code mode = MODE_OFF}. Setting to {@code true} restores {@link #MODE_REBUFFER_TEST}.
   */
  public static volatile boolean enabled = false;

  /**
   * LL-Core: holds a speculative fetch until the encoder has actually reached the segment, checked
   * where the request would be issued rather than by shrinking the available segment count.
   *
   * <p>Decides only <em>when</em> to issue a fetch. Which segment is a candidate stays with the
   * peer-max balancing and the +1 extrapolation in {@code getAvailableSegmentCount}; a held request
   * is retried on the next cycle, never dropped.
   *
   * <p>The window comes from the manifest's {@code ProducerReferenceTime}, not from {@code
   * availabilityStartTime}. Those two disagree by about 1.48s on this stream — the nominal timeline
   * runs that far ahead of real production — so anchoring on the latter left this gate inert for an
   * entire 74-minute capture: it held nothing, once, out of 3948 requests.
   *
   * <p>The {@code prft} boxes in the media bytes carry the same anchor and an earlier gate read them
   * directly. That path was removed once the two were measured side by side across seven hours: they
   * disagree by only about 123ms, with the manifest the more conservative of the two, so parsing
   * media bytes bought nothing the manifest did not already give.
   *
   * <p>Checking at the request site rather than in {@code getAvailableSegmentCount} matters because
   * that count also feeds the live window, the latency target and the seek range; shrinking it to
   * delay one fetch quietly distorts all three.
   *
   * <p>Inert unless {@code elapsedRealtimeOffsetMs} is set, i.e. the manifest's {@code <UTCTiming>}
   * resolved. This fails OPEN: without a server clock the gate stops gating rather than guessing. A
   * device clock behind the server would hold back segments that are genuinely available and drain
   * the buffer, and an unbounded error would stall playback outright; letting a request through early
   * only risks a 404 that self-heals in about a second. An optimisation must not be able to break
   * playback.
   */
  public static volatile boolean safeRequestGateEnabled = true;

  /**
   * Extra margin (ms) beyond the producer window before a fetch is allowed. {@code 0} follows
   * {@code ProducerReferenceTime} exactly.
   *
   * <p>Zero is not enough, and the shortfall is measurable. Bucketing 11348 top-rendition requests
   * by how far past the window they went out gives a clean dose-response: 5.55 failures per
   * thousand below 100ms, 2.78 from 100 to 300, 1.38 from 300 to 500, then nothing at all — zero
   * across the 5405 requests that went out 500ms or later. Every one of the 15 failures in that
   * capture sat below 385ms. The same curve holds for segments the manifest had already listed, not
   * just speculative ones, so this is not about speculation being reckless.
   *
   * <p>What the margin covers is <em>not</em> network transit, though the number is close enough to
   * invite that reading. Transit cannot tell two renditions apart, yet in all 15 failures the 5.6M
   * rendition missed while 2.8M returned the same {@code $Time$} milliseconds later over the same
   * connection. The gap is packaging: {@code ProducerReferenceTime} is declared once per
   * AdaptationSet while each Representation finishes on its own schedule, and the largest one
   * finishes last. Audio shows the same shape — it completes about 403ms after video for the same
   * segment despite sharing a timeline.
   *
   * <p>So treat any value here as a per-channel calibration, not a constant of nature: a ladder with
   * a heavier top rendition needs more, a lighter one needs less. Re-measure with the same bucketing
   * before trusting a number on another channel. The real fix is server-side —
   * {@code ProducerReferenceTime} per Representation, which DASH already allows — and would remove
   * the need to guess at all.
   *
   * <p>Left at {@code 0} regardless, because 500 was tried and the buffer cost is not worth it. The
   * margin is free only while the request still lands before the segment finishes being produced:
   * up to that point the response is paced by production and arrives at the same wall-clock moment
   * however early you asked. Past it the response becomes an ordinary download and every extra
   * millisecond of margin is a millisecond of buffer. That boundary sits around 470ms here, so 500
   * bought almost nothing free — media arrival moved 481ms later and the buffer floor moved with it:
   * the 1st percentile fell from 2482ms to 1984ms, and time spent under 2.5s went from 1.14% to
   * 12.22%.
   *
   * <p>Buffer is the only defence against a network stall and cannot be recovered once spent, while
   * the failures this would have prevented heal on their own in a second or two. Their real cost is
   * the 60s track exclusion that follows, which is worth about 4.3% of watch time at half bitrate —
   * and that is recoverable through {@code LoadErrorHandlingPolicy} at no buffer cost at all. Trade
   * buffer for fewer 404s only when the goal is to stop sending them to the CDN, not when the goal
   * is the viewer's experience.
   */
  public static volatile int safeRequestGateMarginMs = 0;


  private LowLatencyLog() {}

  /** Returns {@code true} if any logging is active. */
  public static boolean isEnabled() {
    return enabled && mode != MODE_OFF;
  }

  /** Returns {@code true} if current mode is {@link #MODE_FULL}. */
  public static boolean isFull() {
    return enabled && mode == MODE_FULL;
  }

  /** Returns {@code true} if current mode is {@link #MODE_REBUFFER_TEST}. */
  public static boolean isRebufferTest() {
    return enabled && mode == MODE_REBUFFER_TEST;
  }

  /** Emits a DEBUG log if logging is enabled. */
  public static void d(String section, String msg) {
    if (isEnabled()) {
      Log.d(TAG, "[" + section + "] " + msg);
    }
  }

  /** Emits a WARN log if logging is enabled. */
  public static void w(String section, String msg) {
    if (isEnabled()) {
      Log.w(TAG, "[" + section + "] " + msg);
    }
  }

  /** Emits a WARN log with throwable if logging is enabled. */
  public static void w(String section, String msg, Throwable t) {
    if (isEnabled()) {
      Log.w(TAG, "[" + section + "] " + msg, t);
    }
  }
}
