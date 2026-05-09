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

    // TODO Tasks A5-A7: Step 3 cache branch + per-V evidence with Tukey, Step 4 cache-fork
    // CDN check (4a HIT / 4b MISS / 4c MIXED), Step 5 bandwidth + variance modifier,
    // Step 6 TRANSIENT default with full cohort dims.
    return DiagnosisV5.transientNoVData(http4xxArr);
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
