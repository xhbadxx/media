/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.qos;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import java.util.Locale;

/**
 * Shared utilities used by {@link QoSDiagnoser}: segment filters, cohort key builders, and
 * window-level buffer conservation metrics + sanity gate. Pure static helpers; no state.
 */
@UnstableApi
public final class QoSDiagnoserUtils {

  // ===== Sanity gate constants =====

  /** Lower bound of {@code demand_ratio} accepted as normal 1× playback. */
  public static final double SANITY_GATE_RATIO_MIN = 0.7;

  /** Upper bound of {@code demand_ratio} accepted as normal 1× playback. */
  public static final double SANITY_GATE_RATIO_MAX = 1.3;

  private QoSDiagnoserUtils() {}

  // ===== Segment filters =====

  /**
   * V-scored segment: {@code V/DEFAULT} track types với {@code chunkDurationMs > 0} và status
   * {@code COMPLETED}.
   */
  public static boolean isCompletedScoredSegment(QoSInfo e) {
    if (e == null) return false;
    if (e.status != QoSInfo.LoadStatus.COMPLETED) return false;
    if (e.chunkDurationMs <= 0L) return false;
    return e.trackType == C.TRACK_TYPE_VIDEO || e.trackType == C.TRACK_TYPE_DEFAULT;
  }

  /** Audio-track scored segment với positive chunk duration. */
  public static boolean isCompletedAudioSegment(QoSInfo e) {
    if (e == null) return false;
    if (e.status != QoSInfo.LoadStatus.COMPLETED) return false;
    if (e.chunkDurationMs <= 0L) return false;
    return e.trackType == C.TRACK_TYPE_AUDIO;
  }

  // ===== Cohort key builders =====

  /**
   * Build session-rolling key for {@link androidx.media3.exoplayer.qos.model.SessionStatistics}:
   * combines networkType, cacheStatus, cdnProvider so each baseline is per-environment.
   */
  public static String sessionKey(QoSInfo s) {
    String net = networkTypeStr(s.networkType);
    String cache = s.cacheStatus != null ? s.cacheStatus : "UNKNOWN";
    String cdn = s.cdnProvider != null ? s.cdnProvider : "UNKNOWN";
    if (net.isEmpty()) net = "UNKNOWN";
    if (cache.isEmpty()) cache = "UNKNOWN";
    if (cdn.isEmpty()) cdn = "UNKNOWN";
    return net + "_" + cache + "_" + cdn;
  }

  /** Convert {@link C.NetworkType} int to short label for cohort emission. */
  public static String networkTypeStr(@C.NetworkType int t) {
    switch (t) {
      case C.NETWORK_TYPE_WIFI:
        return "WIFI";
      case C.NETWORK_TYPE_2G:
        return "2G";
      case C.NETWORK_TYPE_3G:
        return "3G";
      case C.NETWORK_TYPE_4G:
        return "4G";
      case C.NETWORK_TYPE_5G_NSA:
        return "5G_NSA";
      case C.NETWORK_TYPE_5G_SA:
        return "5G_SA";
      case C.NETWORK_TYPE_ETHERNET:
        return "ETH";
      case C.NETWORK_TYPE_OFFLINE:
        return "OFFLINE";
      case C.NETWORK_TYPE_CELLULAR_UNKNOWN:
        return "CELLULAR";
      case C.NETWORK_TYPE_OTHER:
        return "OTHER";
      case C.NETWORK_TYPE_UNKNOWN:
      default:
        return "UNKNOWN";
    }
  }

  // ===== Window-level conservation metrics =====

  /**
   * Window-level metrics derived from a {@link RebufferGroup} implementing the buffer-conservation
   * equation:
   * <pre>
   *   Δbuffer  = ΔSupply − ΔDemand
   *   ΔDemand  = ΔSupply − Δbuffer
   *   ratio    = ΔDemand / wall_time
   * </pre>
   *
   * <p>The ratio classifies playback regime:
   * <ul>
   *   <li>{@code ≈ 1.0} → normal 1× playback
   *   <li>{@code ≈ 0}   → player paused throughout the window
   *   <li>{@code > 1.5} → speed up (trick play / LL-DASH speed control)
   *   <li>{@code < 0.5} → seek refill / partial pause
   * </ul>
   */
  public static final class WindowMetrics {
    public final long wallTimeMs;
    public final long deltaBufferMs;
    public final long deltaSupplyMs;
    public final long deltaDemandMs;
    public final double demandRatio;

    WindowMetrics(
        long wallTimeMs,
        long deltaBufferMs,
        long deltaSupplyMs,
        long deltaDemandMs,
        double demandRatio) {
      this.wallTimeMs = wallTimeMs;
      this.deltaBufferMs = deltaBufferMs;
      this.deltaSupplyMs = deltaSupplyMs;
      this.deltaDemandMs = deltaDemandMs;
      this.demandRatio = demandRatio;
    }
  }

  /**
   * Computes the window-level conservation metrics for {@code group}. Returns a zero-valued
   * {@link WindowMetrics} when the group has fewer than 2 entries (degenerate window) or when the
   * window has zero wall-clock duration.
   *
   * <p>{@code ΔSupply} sums {@code chunkDurationMs} only over completed V-scored segments
   * (V/DEFAULT). Audio is excluded — DASH plays A+V parallel and counting both would double
   * {@code ΔSupply} relative to single-stream wall clock. Manifests, init segments, and failed
   * loads also excluded since they don't deliver playable media.
   */
  public static WindowMetrics computeWindowMetrics(RebufferGroup group) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return new WindowMetrics(0L, 0L, 0L, 0L, 0.0);
    }
    QoSInfo first = group.entries.get(0);
    QoSInfo last = group.trigger;

    long wallTimeMs = Math.max(0L, last.timestampMs - first.timestampMs);
    long deltaBufferMs =
        clampNonNegativeBl(last.bufferedDurationMs)
            - clampNonNegativeBl(first.bufferedDurationMs);

    long deltaSupplyMs = 0L;
    for (QoSInfo entry : group.entries) {
      if (isVideoLikeCompletedSegment(entry)) {
        deltaSupplyMs += entry.chunkDurationMs;
      }
    }

    long deltaDemandMs = deltaSupplyMs - deltaBufferMs;
    double demandRatio = wallTimeMs > 0L ? (double) deltaDemandMs / wallTimeMs : 0.0;

    return new WindowMetrics(
        wallTimeMs, deltaBufferMs, deltaSupplyMs, deltaDemandMs, demandRatio);
  }

  /**
   * Sanity gate. Returns {@code null} when the window represents normal 1× playback (suitable for
   * network diagnosis); otherwise returns a human-readable reason describing why the window is
   * abnormal (paused, sped up, seeked, or degenerate).
   *
   * <p>Bounds:
   * <ul>
   *   <li>{@code wall_time ≤ 0}            → degenerate window
   *   <li>{@code demand_ratio < 0.7}        → likely paused / partially paused
   *   <li>{@code demand_ratio > 1.3}        → likely speed up (trick play)
   *   <li>{@code 0.7 ≤ demand_ratio ≤ 1.3} → passes
   * </ul>
   */
  @androidx.annotation.Nullable
  public static String applySanityGate(WindowMetrics metrics) {
    if (metrics == null || metrics.wallTimeMs <= 0L) {
      return "wall_time = "
          + (metrics == null ? 0L : metrics.wallTimeMs)
          + "ms (window degenerate, cannot diagnose)";
    }
    double ratio = metrics.demandRatio;
    if (ratio < SANITY_GATE_RATIO_MIN || ratio > SANITY_GATE_RATIO_MAX) {
      return String.format(
          Locale.US,
          "demand_ratio %.2f ∉ [%.1f, %.1f] (abnormal playback: pause/seek/speed change)",
          ratio,
          SANITY_GATE_RATIO_MIN,
          SANITY_GATE_RATIO_MAX);
    }
    return null;
  }

  /**
   * Treats the {@code bufferedDurationMs = -1} sentinel as 0 for arithmetic purposes. Snapshots
   * may legitimately be missing if {@code QoSAnalyticsHook} couldn't capture player state at load
   * start; in that case we don't want the computation to flip sign.
   */
  static long clampNonNegativeBl(int bufferedDurationMs) {
    return bufferedDurationMs >= 0 ? bufferedDurationMs : 0L;
  }

  /**
   * Subset of {@link #isCompletedScoredSegment} restricted to V/DEFAULT — used by ΔSupply
   * accumulator to avoid double-counting parallel A track in DASH split-stream.
   */
  private static boolean isVideoLikeCompletedSegment(QoSInfo entry) {
    return entry != null
        && entry.status == QoSInfo.LoadStatus.COMPLETED
        && entry.chunkDurationMs > 0L
        && (entry.trackType == C.TRACK_TYPE_VIDEO || entry.trackType == C.TRACK_TYPE_DEFAULT);
  }
}
