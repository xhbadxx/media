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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qos.model.DiagnosisV5;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-function rebuffer diagnoser V5 — client-only paper-anchored cascade.
 *
 * <p>See {@code spec-qos-v5-client-only.md} for full algorithm rationale, paper
 * anchoring (Tukey 1977 IQR fence + MDPI 2021 variance modifier + RFC 7230 retry
 * + AWS canonical cache fork), and walk-through examples.
 *
 * <p>Cascade order (each step's positive match short-circuits):
 * <ol>
 *   <li>Step 2 — HTTP 5xx fast-path (decisive, before sanity).
 *   <li>Step 1 — Sanity gate (V1 reuse).
 *   <li>Step 2.5 — Retry decisive (≥2 V-retries + mtp not collapsed).
 *   <li>Step 3 — Per-V evidence + cache branch dispatch (Tukey applied).
 *   <li>Step 4a/4b/4c — Cache-fork CDN check (HIT-side / MISS-side / MIXED fallback).
 *   <li>Step 5 — Bandwidth deficit majority + variance modifier.
 *   <li>Step 6 — TRANSIENT default (with cohort dims).
 * </ol>
 *
 * <p>Paper-anchored thresholds: ~70% of constants derive from peer-reviewed sources
 * (Tukey 1977, MDPI 2021) or RFC standards (RFC 7230). Multi-CDN robust via
 * session-internal Tukey fence baselines (no CDN config dependency).
 */
@UnstableApi
public final class QoSDiagnoserV5 {

  private QoSDiagnoserV5() {}

  // ===== Decision constants (see spec §7 for paper anchoring) =====

  /** Per-segment slowness threshold: {@code excess_ratio > 0.10} flags slow segment. */
  static final double SLOW_RATIO = 0.10;

  /** Group decision threshold for INSUFFICIENT_BANDWIDTH: {@code median_excess > 0.10}. */
  static final double MEDIAN_DEFICIT_THRESHOLD = 0.10;

  /** Cold-start fallback when SessionStatistics has &lt; MIN_SAMPLES_FOR_TUKEY samples. */
  static final int TTFB_OUTLIER_FALLBACK_MS = 800;

  /** Post-TTFB rate ≥ 90% of bitrate ⇒ delivery basically healthy. */
  static final double DELIVERY_HEALTHY_RATIO = 0.90;

  /** Post-TTFB rate &lt; 70% of bitrate ⇒ delivery significantly impaired. */
  static final double DELIVERY_DEFICIT_RATIO = 0.70;

  /** Minimum V-segments required before CDN_DELIVERY_SLOW can fire. */
  static final int MIN_SEGMENTS_FOR_CDN = 3;

  /** RFC 7230 retransmission anchor: ≥2 V-retries = pattern (not single transient). */
  static final int RETRY_DECISIVE_COUNT = 2;

  /** mtp / bitrate ≥ 0.5 = bandwidth still healthy → retry attributable to CDN. */
  static final double MTP_NOT_COLLAPSED_RATIO = 0.5;

  /** Cache branch dominance: ≥ {@code 4/5 = 80%} of known-cache V-segments same state. */
  static final int CACHE_DOMINANT_NUM = 4;
  static final int CACHE_DOMINANT_DENOM = 5;

  /** MDPI 2021 anchor: variance &gt; this = "spiky" pattern (not sustained). */
  static final double HIGH_VARIANCE_THRESHOLD = 0.05;

  /** Buffer-trajectory range below this is considered FLAT (ms). */
  static final int FLAT_RANGE_MS = 1000;

  // ===== Public entry =====

  /**
   * Diagnoses {@code group} against optional {@code sessionStats} rolling baselines.
   *
   * <p>{@code sessionStats} may be {@code null} or have insufficient samples; in either
   * case the algorithm falls back to absolute thresholds (V4-equivalent behavior).
   */
  public static DiagnosisV5 diagnose(
      @Nullable RebufferGroup group, @Nullable SessionStatistics sessionStats) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return DiagnosisV5.transientFromSanity("empty_group");
    }

    // Step 2 (HTTP 5xx fast-path) is checked BEFORE Step 1 (sanity gate): 5xx is
    // decisive evidence regardless of whether the window represents normal playback.
    List<Integer> http5xx = new ArrayList<>();
    List<Integer> http4xx = new ArrayList<>();
    boolean hadRetries = false;
    for (QoSInfo s : group.entries) {
      if (s == null) continue;
      if (s.retryCount > 0) hadRetries = true;
      if (s.status == QoSInfo.LoadStatus.ERROR) {
        if (s.httpStatusCode >= 500 && s.httpStatusCode <= 599) {
          http5xx.add(s.httpStatusCode);
        } else if (s.httpStatusCode >= 400 && s.httpStatusCode <= 499) {
          http4xx.add(s.httpStatusCode);
        }
      }
    }
    if (!http5xx.isEmpty()) {
      List<Integer> all = new ArrayList<>(http5xx.size() + http4xx.size());
      all.addAll(http5xx);
      all.addAll(http4xx);
      return DiagnosisV5.cdnHttpError(toIntArray(all));
    }

    // Step 1 — Sanity gate (reuse V1 pure helpers). Filters non-playback windows.
    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoser.applySanityG1ate(metrics);
    if (sanityFail != null) {
      return DiagnosisV5.transientFromSanity(sanityFail);
    }

    // Filter V-segments + A-segments (needed for Step 2.5 retry count + last-seg mtp).
    List<QoSInfo> vSegments = new ArrayList<>();
    List<QoSInfo> aSegments = new ArrayList<>();
    for (QoSInfo s : group.entries) {
      if (isCompletedScoredSegment(s)) {
        vSegments.add(s);
      } else if (isCompletedAudioSegment(s)) {
        aSegments.add(s);
      }
    }
    int nV = vSegments.size();
    int nA = aSegments.size();
    int[] http4xxArr = toIntArray(http4xx);

    if (nV == 0) {
      return DiagnosisV5.transientNoVData(http4xxArr);
    }

    // Step 2.5 — Retry decisive (RFC 7230 anchor + AWS canonical "non-2xx prior to bs").
    int nRetryV = 0;
    for (QoSInfo s : vSegments) {
      if (s.retryCount > 0) nRetryV++;
    }
    if (nRetryV >= RETRY_DECISIVE_COUNT) {
      QoSInfo lastSeg = vSegments.get(nV - 1);
      // mtp_ratio guard — if mtp collapsed (< 50% bitrate), retry is client-side disconnect,
      // not CDN. Token bucket math: client throttle leaves mtp ≈ throttle floor; full
      // disconnect drops mtp to ~0. mtp ≥ 0.5 × bitrate → bandwidth still healthy.
      if (lastSeg.bitrateKbps > 0
          && lastSeg.measuredThroughputKbps > 0
          && lastSeg.measuredThroughputKbps
              >= (int) Math.round(MTP_NOT_COLLAPSED_RATIO * lastSeg.bitrateKbps)) {
        return new DiagnosisV5(
            DiagnosisV5.Cause.CDN_DELIVERY_SLOW,
            DiagnosisV5.CacheBranch.UNKNOWN, // Task A5 will compute cache branch dispatch.
            nV,
            nA,
            http4xxArr,
            /* nSlowSegments= */ 0,
            /* medianExcessRatio= */ 0.0,
            /* meanExcessRatio= */ 0.0,
            /* maxExcessRatio= */ 0.0,
            /* varianceExcess= */ 0.0,
            /* nCdnEvidenceSegments= */ 0,
            /* nTtfbOutlierSegments= */ 0,
            /* nCacheHitSlowSegments= */ 0,
            /* nDeliveryRateOutlierSegments= */ 0,
            nRetryV,
            /* crossTrackCorrelated= */ false,
            /* abrWasAware= */ false,
            DiagnosisV5.BufferTrend.UNAVAILABLE,
            /* sessionTtfbQ1Ms= */ -1,
            /* sessionTtfbQ3Ms= */ -1,
            /* ttfbUpperFenceApplied= */ -1,
            /* sessionDeliveryRateQ1Kbps= */ -1,
            /* sessionDeliveryRateQ3Kbps= */ -1,
            /* deliveryRateLowerFenceApplied= */ -1,
            /* usedColdStartFallback= */ false,
            networkTypeStr(lastSeg.networkType),
            /* cohortCacheBranch= */ "UNKNOWN",
            lastSeg.cdnProvider != null ? lastSeg.cdnProvider : "UNKNOWN",
            /* cohortRegion= */ null,
            hadRetries,
            /* sanityFailReason= */ null);
      }
      // mtp collapsed → fall through (likely client disconnect, not CDN).
    }

    // Step 3 — Cache branch dispatch (AWS canonical: HIT slow → CDN edge; MISS slow → origin).
    DiagnosisV5.CacheBranch cacheBranch = computeCacheBranch(vSegments);

    // Resolve session-rolling Tukey fences from last V-segment's primary key.
    QoSInfo lastSeg = vSegments.get(nV - 1);
    String primaryKey = sessionKey(lastSeg);
    SessionStatistics.TukeyFence ttfbFence =
        sessionStats == null ? null : sessionStats.getTtfbFence(primaryKey);
    SessionStatistics.TukeyFence deliveryFence =
        sessionStats == null ? null : sessionStats.getDeliveryRateFence(primaryKey);

    boolean usedColdStart = (ttfbFence == null);
    int ttfbThreshold = ttfbFence != null ? ttfbFence.upperFence : TTFB_OUTLIER_FALLBACK_MS;
    int deliveryLowerFence = deliveryFence != null ? deliveryFence.lowerFence : -1;

    // Per-segment evidence (Tukey applied where fence available; absolute otherwise).
    PerSegmentEvidence[] perSeg = new PerSegmentEvidence[nV];
    double[] excessRatios = new double[nV];
    int nSlow = 0;
    int nCdnEvidence = 0;
    int nTtfbOutlier = 0;
    int nCacheHitSlow = 0;
    int nDeliveryRateOutlier = 0;
    for (int i = 0; i < nV; i++) {
      perSeg[i] = computePerSegmentEvidence(vSegments.get(i), ttfbThreshold, deliveryLowerFence);
      excessRatios[i] = perSeg[i].excessRatio;
      if (perSeg[i].isSlow) nSlow++;
      if (perSeg[i].isCdnEvidence) nCdnEvidence++;
      if (perSeg[i].isTtfbOutlier) nTtfbOutlier++;
      if (perSeg[i].isCacheHitSlow) nCacheHitSlow++;
      if (perSeg[i].isDeliveryRateOutlier) nDeliveryRateOutlier++;
    }

    double medianExcess = median(excessRatios);
    double meanExcess = mean(excessRatios);
    double maxExcess = max(excessRatios);
    double varianceExcess = variance(excessRatios, meanExcess);

    // Audio cross-track correlation.
    boolean crossTrackCorrelated = computeAudioCrossTrack(aSegments);

    // Buffer trajectory (BOLA anchor — V4 algorithm reused).
    DiagnosisV5.BufferTrend bufferTrend = analyzeBufferTrend(extractBlValues(vSegments));

    // ABR awareness — only last V-segment.
    boolean abrWasAware =
        lastSeg.bitrateKbps > 0
            && lastSeg.measuredThroughputKbps > 0
            && lastSeg.measuredThroughputKbps < lastSeg.bitrateKbps;

    // diagnose() is read-only against sessionStats. The host (FPlayQoSMonitor's
    // sessionStatsObserver) feeds every COMPLETED V scored segment into sessionStats
    // independently — that way the rolling Tukey fence warms up from healthy traffic
    // throughout the session, not only from V-segments captured inside rebuffer windows
    // (which would both starve the fence and bias it toward elevated TTFB).

    // Step 4 — Cache-fork CDN check (AWS canonical pattern). Each branch counts evidence
    // attributable to that cache state and fires CDN_DELIVERY_SLOW on majority.
    DiagnosisV5.Cause finalCause = DiagnosisV5.Cause.TRANSIENT;
    if (nV >= MIN_SEGMENTS_FOR_CDN) {
      if (cacheBranch == DiagnosisV5.CacheBranch.HIT_DOMINANT) {
        // Step 4a — HIT side: cache_hit_slow OR delivery_rate_outlier (Tukey).
        int nHitEvidence = 0;
        for (PerSegmentEvidence p : perSeg) {
          if (p.isCacheHitSlow || p.isDeliveryRateOutlier) nHitEvidence++;
        }
        if (nHitEvidence * 2 >= nV) {
          finalCause = DiagnosisV5.Cause.CDN_DELIVERY_SLOW;
        }
      } else if (cacheBranch == DiagnosisV5.CacheBranch.MISS_DOMINANT) {
        // Step 4b — MISS side: TTFB outlier + delivery healthy (origin pull slow start).
        int nMissEvidence = 0;
        for (PerSegmentEvidence p : perSeg) {
          if (p.isTtfbOutlier && p.deliveryHealth >= DELIVERY_HEALTHY_RATIO) nMissEvidence++;
        }
        if (nMissEvidence * 2 >= nV) {
          finalCause = DiagnosisV5.Cause.CDN_DELIVERY_SLOW;
        }
      } else {
        // Step 4c — MIXED/UNKNOWN fallback: V4-style combined CDN evidence.
        if (nCdnEvidence * 2 >= nV) {
          finalCause = DiagnosisV5.Cause.CDN_DELIVERY_SLOW;
        }
      }
    }

    // Step 5 — Bandwidth deficit majority (V4 inherited rule). Variance is reported as
    // observability evidence (MDPI 2021 anchor: low variance = sustained, high = spiky)
    // but does NOT change cause classification.
    if (finalCause == DiagnosisV5.Cause.TRANSIENT
        && nSlow * 2 >= nV
        && medianExcess > MEDIAN_DEFICIT_THRESHOLD) {
      finalCause = DiagnosisV5.Cause.INSUFFICIENT_BANDWIDTH;
    }

    // Step 6 — TRANSIENT default (already the initial value of finalCause). All paths
    // emit cohort dims for backend reattribution.
    return new DiagnosisV5(
        finalCause,
        cacheBranch,
        nV,
        nA,
        http4xxArr,
        nSlow,
        medianExcess,
        meanExcess,
        maxExcess,
        varianceExcess,
        nCdnEvidence,
        nTtfbOutlier,
        nCacheHitSlow,
        nDeliveryRateOutlier,
        nRetryV,
        crossTrackCorrelated,
        abrWasAware,
        bufferTrend,
        ttfbFence != null ? ttfbFence.q1 : -1,
        ttfbFence != null ? ttfbFence.q3 : -1,
        ttfbThreshold,
        deliveryFence != null ? deliveryFence.q1 : -1,
        deliveryFence != null ? deliveryFence.q3 : -1,
        deliveryLowerFence,
        usedColdStart,
        networkTypeStr(lastSeg.networkType),
        cacheBranch.name(),
        lastSeg.cdnProvider != null ? lastSeg.cdnProvider : "UNKNOWN",
        /* cohortRegion= */ null,
        hadRetries,
        /* sanityFailReason= */ null);
  }

  // ===== Step 3 helpers =====

  /**
   * Cache-branch dispatch over V-segments (AWS canonical pattern).
   * ≥{@link #CACHE_DOMINANT_NUM}/{@link #CACHE_DOMINANT_DENOM} = 80% same state ⇒ dominant.
   */
  static DiagnosisV5.CacheBranch computeCacheBranch(List<QoSInfo> vSegments) {
    int nHit = 0;
    int nMiss = 0;
    int nKnown = 0;
    for (QoSInfo s : vSegments) {
      if ("HIT".equalsIgnoreCase(s.cacheStatus)) {
        nHit++;
        nKnown++;
      } else if ("MISS".equalsIgnoreCase(s.cacheStatus)) {
        nMiss++;
        nKnown++;
      }
    }
    if (nKnown == 0) return DiagnosisV5.CacheBranch.UNKNOWN;
    if (nHit * CACHE_DOMINANT_DENOM >= nKnown * CACHE_DOMINANT_NUM) {
      return DiagnosisV5.CacheBranch.HIT_DOMINANT;
    }
    if (nMiss * CACHE_DOMINANT_DENOM >= nKnown * CACHE_DOMINANT_NUM) {
      return DiagnosisV5.CacheBranch.MISS_DOMINANT;
    }
    return DiagnosisV5.CacheBranch.MIXED;
  }

  /**
   * Build session-rolling key for {@link SessionStatistics}: combines networkType,
   * cacheStatus, cdnHostname so each baseline is per-environment.
   */
  static String sessionKey(QoSInfo s) {
    String net = networkTypeStr(s.networkType);
    String cache = s.cacheStatus != null ? s.cacheStatus : "UNKNOWN";
    String cdn = s.cdnProvider != null ? s.cdnProvider : "UNKNOWN";
    return net + "_" + cache + "_" + cdn;
  }

  /** Per-V-segment evidence flags + computed deltas. */
  static final class PerSegmentEvidence {
    final double excessRatio;
    final boolean isSlow;
    final double deliveryHealth;
    final boolean isTtfbOutlier;
    final boolean isDeliveryRateOutlier;
    final boolean isCacheHitSlow;
    final boolean isCdnEvidence;

    PerSegmentEvidence(
        double excessRatio,
        boolean isSlow,
        double deliveryHealth,
        boolean isTtfbOutlier,
        boolean isDeliveryRateOutlier,
        boolean isCacheHitSlow,
        boolean isCdnEvidence) {
      this.excessRatio = excessRatio;
      this.isSlow = isSlow;
      this.deliveryHealth = deliveryHealth;
      this.isTtfbOutlier = isTtfbOutlier;
      this.isDeliveryRateOutlier = isDeliveryRateOutlier;
      this.isCacheHitSlow = isCacheHitSlow;
      this.isCdnEvidence = isCdnEvidence;
    }
  }

  /**
   * Compute evidence for one V-segment using the resolved Tukey thresholds. Falls back
   * to V4-equivalent absolute checks when fences not available (cold start).
   */
  static PerSegmentEvidence computePerSegmentEvidence(
      QoSInfo s, int ttfbThresholdMs, int deliveryLowerFenceKbps) {
    long excessMs = Math.max(0L, s.loadDurationMs - s.chunkDurationMs);
    double excessRatio = (double) excessMs / s.chunkDurationMs;
    boolean isSlow = excessRatio > SLOW_RATIO;

    double deliveryHealth = -1.0;
    boolean isDeliveryHealthy = false;
    boolean isDeliveryDeficit = false;
    boolean isDeliveryRateOutlier = false;
    if (s.bitrateKbps > 0 && s.ttfbMs >= 0 && s.bytesLoaded > 0) {
      long transferMs = Math.max(1L, s.loadDurationMs - s.ttfbMs);
      double postTtfbKbps = (s.bytesLoaded * 8.0) / transferMs;
      deliveryHealth = postTtfbKbps / s.bitrateKbps;
      isDeliveryHealthy = deliveryHealth >= DELIVERY_HEALTHY_RATIO;
      isDeliveryDeficit = deliveryHealth < DELIVERY_DEFICIT_RATIO;
      // Tukey lower-fence outlier: only meaningful when fence available (warm session).
      if (deliveryLowerFenceKbps > 0 && postTtfbKbps < deliveryLowerFenceKbps) {
        isDeliveryRateOutlier = true;
      }
    }

    // Tukey upper-fence outlier on TTFB (or absolute fallback when cold-start).
    boolean isTtfbOutlier = (s.ttfbMs > ttfbThresholdMs);

    // V4 inherited rule: cache=HIT + delivery deficit + NOT ttfb outlier.
    boolean isCacheHitSlow =
        "HIT".equalsIgnoreCase(s.cacheStatus) && isDeliveryDeficit && !isTtfbOutlier;

    // Combined CDN evidence — V5 adds delivery rate outlier branch.
    boolean isCdnEvidence =
        (isTtfbOutlier && isDeliveryHealthy) || isCacheHitSlow || isDeliveryRateOutlier;

    return new PerSegmentEvidence(
        excessRatio, isSlow, deliveryHealth,
        isTtfbOutlier, isDeliveryRateOutlier, isCacheHitSlow, isCdnEvidence);
  }

  /** Audio cross-track correlation: ≥50% A-segments slow ⇒ correlated drain. */
  static boolean computeAudioCrossTrack(List<QoSInfo> aSegments) {
    int nA = aSegments.size();
    if (nA == 0) return false;
    int aSlow = 0;
    for (QoSInfo a : aSegments) {
      if (a.chunkDurationMs <= 0) continue;
      long excessMs = Math.max(0L, a.loadDurationMs - a.chunkDurationMs);
      double r = (double) excessMs / a.chunkDurationMs;
      if (r > SLOW_RATIO) aSlow++;
    }
    return aSlow * 2 >= nA;
  }

  /** Extract bufferedDurationMs values from V-segments where available (≥0). */
  static int[] extractBlValues(List<QoSInfo> vSegments) {
    int[] tmp = new int[vSegments.size()];
    int n = 0;
    for (QoSInfo s : vSegments) {
      if (s.bufferedDurationMs >= 0) tmp[n++] = s.bufferedDurationMs;
    }
    return Arrays.copyOf(tmp, n);
  }

  // ===== Buffer trend (BOLA anchor — V4 algorithm reused) =====

  static DiagnosisV5.BufferTrend analyzeBufferTrend(int[] bl) {
    if (bl == null || bl.length < 2) {
      return DiagnosisV5.BufferTrend.UNAVAILABLE;
    }
    int n = bl.length;
    int decreasing = 0;
    int increasing = 0;
    int min = bl[0];
    int max = bl[0];
    for (int i = 1; i < n; i++) {
      if (bl[i] < bl[i - 1]) decreasing++;
      else if (bl[i] > bl[i - 1]) increasing++;
      if (bl[i] < min) min = bl[i];
      if (bl[i] > max) max = bl[i];
    }
    int range = max - min;
    int pairs = n - 1;
    if (decreasing * 5 >= pairs * 4) return DiagnosisV5.BufferTrend.MONOTONIC_DRAIN;
    if (range < FLAT_RANGE_MS) return DiagnosisV5.BufferTrend.FLAT;
    if (increasing > 0 && decreasing > 0 && bl[n - 1] >= bl[0] * 0.9) {
      return DiagnosisV5.BufferTrend.SPIKE;
    }
    return DiagnosisV5.BufferTrend.OSCILLATING;
  }

  // ===== Statistics helpers (median/mean/max/variance) =====

  static double median(double[] values) {
    if (values == null || values.length == 0) return 0.0;
    double[] copy = Arrays.copyOf(values, values.length);
    Arrays.sort(copy);
    int n = copy.length;
    if (n % 2 == 1) return copy[n / 2];
    return (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
  }

  static double mean(double[] values) {
    if (values == null || values.length == 0) return 0.0;
    double sum = 0.0;
    for (double v : values) sum += v;
    return sum / values.length;
  }

  static double max(double[] values) {
    if (values == null || values.length == 0) return 0.0;
    double m = values[0];
    for (int i = 1; i < values.length; i++) if (values[i] > m) m = values[i];
    return m;
  }

  static double variance(double[] values, double mean) {
    if (values == null || values.length == 0) return 0.0;
    double sum = 0.0;
    for (double v : values) {
      double d = v - mean;
      sum += d * d;
    }
    return sum / values.length;
  }

  // ===== Filters (mirror V4 patterns) =====

  /** Same predicate as V4: V/DEFAULT scored segments với {@code chunkDurationMs > 0}. */
  static boolean isCompletedScoredSegment(QoSInfo e) {
    if (e == null) return false;
    if (e.status != QoSInfo.LoadStatus.COMPLETED) return false;
    if (e.chunkDurationMs <= 0L) return false;
    return e.trackType == C.TRACK_TYPE_VIDEO || e.trackType == C.TRACK_TYPE_DEFAULT;
  }

  /** Audio-track scored segment với positive chunk duration. */
  static boolean isCompletedAudioSegment(QoSInfo e) {
    if (e == null) return false;
    if (e.status != QoSInfo.LoadStatus.COMPLETED) return false;
    if (e.chunkDurationMs <= 0L) return false;
    return e.trackType == C.TRACK_TYPE_AUDIO;
  }

  // ===== Cohort dim helpers =====

  /** Convert {@link C.NetworkType} int to short label for cohort emission. */
  static String networkTypeStr(@C.NetworkType int t) {
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

  // ===== Misc =====

  private static int[] toIntArray(List<Integer> list) {
    if (list == null || list.isEmpty()) return new int[0];
    int[] arr = new int[list.size()];
    for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
    return arr;
  }
}
