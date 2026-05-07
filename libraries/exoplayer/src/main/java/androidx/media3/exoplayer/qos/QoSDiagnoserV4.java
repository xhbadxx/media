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
import androidx.media3.exoplayer.qos.model.DiagnosisV4;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-function rebuffer diagnoser V4 — multi-evidence cascade algorithm.
 *
 * <p>See {@code spec-qos-v4-algorithm.md} for the decision flow rationale, threshold
 * justification, and walk-through examples on real datasets (v8/v9).
 *
 * <p>Cascade order (each step's positive match short-circuits):
 * <ol>
 *   <li>Sanity gate (reuses V1 helpers) — filters non-playback windows.
 *   <li>HTTP 5xx fast-path — decisive CDN evidence.
 *   <li>Per-segment evidence compute (V-segments) + audio cross-track + buffer trend.
 *   <li>CDN delivery evidence majority — decisive CDN edge attribution
 *       (TTFB outlier with healthy delivery, or cache HIT with deficit delivery).
 *   <li>Bandwidth deficit majority — heuristic, source-ambiguous.
 *   <li>TRANSIENT default.
 * </ol>
 */
@UnstableApi
public final class QoSDiagnoserV4 {

  private QoSDiagnoserV4() {}

  // ===== Decision constants (see spec §8 for rationale) =====

  /** Per-segment slowness threshold: {@code excess_ratio > 0.10} flags slow segment. */
  static final double SLOW_RATIO = 0.10;
  /** Group decision threshold for INSUFFICIENT_BANDWIDTH: {@code median_excess > 0.10}. */
  static final double MEDIAN_DEFICIT_THRESHOLD = 0.10;
  /** Absolute TTFB outlier threshold (ms). Survives ~50ms client-throttle inflation budget. */
  static final int TTFB_OUTLIER_ABS_MS = 800;
  /** Post-TTFB rate ≥ 90% of bitrate ⇒ delivery basically healthy. */
  static final double DELIVERY_HEALTHY_RATIO = 0.90;
  /** Post-TTFB rate &lt; 70% of bitrate ⇒ delivery significantly impaired. */
  static final double DELIVERY_DEFICIT_RATIO = 0.70;
  /** Minimum V-segments required before CDN_DELIVERY_SLOW can fire. */
  static final int MIN_SEGMENTS_FOR_CDN = 3;
  /** Buffer-trajectory range below this is considered FLAT (ms). */
  static final int FLAT_RANGE_MS = 1000;

  // ===== Public entry =====

  /** Diagnoses {@code group} and returns a single {@link DiagnosisV4}. */
  public static DiagnosisV4 diagnose(RebufferGroup group) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return DiagnosisV4.transientFromSanity("empty_group");
    }

    // Step 2 (HTTP 5xx fast-path) is checked BEFORE Step 1 (sanity gate): 5xx is
    // decisive evidence regardless of whether the window represents normal playback —
    // a server-side error is still a server-side error during seek/pause/codec-init.
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
      return DiagnosisV4.cdnHttpError(toIntArray(all));
    }

    // Step 1 — Sanity gate (reuse V1 pure helpers). Filters non-playback windows
    // (seek, pause, codec init, abnormal speed) so rate-evidence steps below operate
    // on representative data only.
    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoser.applySanityG1ate(metrics);
    if (sanityFail != null) {
      return DiagnosisV4.transientFromSanity(sanityFail);
    }

    // Step 3 — Per-segment evidence (V-only). Audio cross-track + buffer trend.
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
      return DiagnosisV4.transientNoVData(http4xxArr);
    }

    PerSegmentEvidence[] perSeg = new PerSegmentEvidence[nV];
    double[] excessRatios = new double[nV];
    int nSlow = 0;
    int nCdnEvidence = 0;
    int nTtfbOutlier = 0;
    int nCacheHitSlow = 0;
    for (int i = 0; i < nV; i++) {
      perSeg[i] = computePerSegmentEvidence(vSegments.get(i));
      excessRatios[i] = perSeg[i].excessRatio;
      if (perSeg[i].isSlow) nSlow++;
      if (perSeg[i].isCdnEvidence) nCdnEvidence++;
      if (perSeg[i].isTtfbOutlier) nTtfbOutlier++;
      if (perSeg[i].isCacheHitSlow) nCacheHitSlow++;
    }

    double medianExcess = median(excessRatios);
    double meanExcess = mean(excessRatios);
    double maxExcess = max(excessRatios);
    double varianceExcess = variance(excessRatios, meanExcess);

    // Audio cross-track: majority slow ⇒ correlated.
    boolean crossTrackCorrelated = false;
    if (nA > 0) {
      int aSlow = 0;
      for (QoSInfo a : aSegments) {
        long excessMs = Math.max(0L, a.loadDurationMs - a.chunkDurationMs);
        double r = (double) excessMs / a.chunkDurationMs;
        if (r > SLOW_RATIO) aSlow++;
      }
      crossTrackCorrelated = (aSlow * 2 >= nA);
    }

    // Buffer trajectory.
    int[] blValues = new int[nV];
    int validBl = 0;
    for (int i = 0; i < nV; i++) {
      if (vSegments.get(i).bufferedDurationMs >= 0) {
        blValues[validBl++] = vSegments.get(i).bufferedDurationMs;
      }
    }
    DiagnosisV4.BufferTrend bufferTrend =
        analyzeBufferTrend(Arrays.copyOf(blValues, validBl));

    // ABR awareness — only last V-segment.
    QoSInfo lastSeg = vSegments.get(nV - 1);
    boolean abrWasAware =
        lastSeg.measuredThroughputKbps > 0
            && lastSeg.measuredThroughputKbps < lastSeg.bitrateKbps;

    // Step 4 — CDN delivery evidence majority?
    if (nV >= MIN_SEGMENTS_FOR_CDN && nCdnEvidence * 2 >= nV) {
      return new DiagnosisV4(
          DiagnosisV4.Cause.CDN_DELIVERY_SLOW,
          nV, nA, http4xxArr,
          nSlow, medianExcess, meanExcess, maxExcess, varianceExcess,
          nCdnEvidence, nTtfbOutlier, nCacheHitSlow,
          crossTrackCorrelated, abrWasAware, bufferTrend, hadRetries,
          /* sanityFailReason= */ null);
    }

    // Step 5 — Bandwidth deficit majority?
    if (nSlow * 2 >= nV && medianExcess > MEDIAN_DEFICIT_THRESHOLD) {
      return new DiagnosisV4(
          DiagnosisV4.Cause.INSUFFICIENT_BANDWIDTH,
          nV, nA, http4xxArr,
          nSlow, medianExcess, meanExcess, maxExcess, varianceExcess,
          nCdnEvidence, nTtfbOutlier, nCacheHitSlow,
          crossTrackCorrelated, abrWasAware, bufferTrend, hadRetries,
          /* sanityFailReason= */ null);
    }

    // Step 6 — TRANSIENT default.
    return new DiagnosisV4(
        DiagnosisV4.Cause.TRANSIENT,
        nV, nA, http4xxArr,
        nSlow, medianExcess, meanExcess, maxExcess, varianceExcess,
        nCdnEvidence, nTtfbOutlier, nCacheHitSlow,
        crossTrackCorrelated, abrWasAware, bufferTrend, hadRetries,
        /* sanityFailReason= */ null);
  }

  // ===== Per-segment evidence =====

  /** Per-V-segment evidence flags + computed deltas. */
  static final class PerSegmentEvidence {
    final double excessRatio;
    final boolean isSlow;
    /** {@code post_ttfb_rate / bitrate}, or {@code -1.0} when not computable. */
    final double deliveryHealth;
    final boolean isTtfbOutlier;
    final boolean isCacheHitSlow;
    final boolean isCdnEvidence;

    PerSegmentEvidence(
        double excessRatio,
        boolean isSlow,
        double deliveryHealth,
        boolean isTtfbOutlier,
        boolean isCacheHitSlow,
        boolean isCdnEvidence) {
      this.excessRatio = excessRatio;
      this.isSlow = isSlow;
      this.deliveryHealth = deliveryHealth;
      this.isTtfbOutlier = isTtfbOutlier;
      this.isCacheHitSlow = isCacheHitSlow;
      this.isCdnEvidence = isCdnEvidence;
    }
  }

  static PerSegmentEvidence computePerSegmentEvidence(QoSInfo s) {
    long excessMs = Math.max(0L, s.loadDurationMs - s.chunkDurationMs);
    double excessRatio = (double) excessMs / s.chunkDurationMs;
    boolean isSlow = excessRatio > SLOW_RATIO;

    double deliveryHealth = -1.0;
    boolean isDeliveryHealthy = false;
    boolean isDeliveryDeficit = false;
    if (s.bitrateKbps > 0 && s.ttfbMs >= 0 && s.bytesLoaded > 0) {
      long transferMs = Math.max(1L, s.loadDurationMs - s.ttfbMs);
      // post_ttfb_rate (kbps) = bytes * 8 / transfer_ms (since 1 kbps = 1 bit/ms).
      double postTtfbKbps = (s.bytesLoaded * 8.0) / transferMs;
      deliveryHealth = postTtfbKbps / s.bitrateKbps;
      isDeliveryHealthy = deliveryHealth >= DELIVERY_HEALTHY_RATIO;
      isDeliveryDeficit = deliveryHealth < DELIVERY_DEFICIT_RATIO;
    }

    boolean isTtfbOutlier = (s.ttfbMs > TTFB_OUTLIER_ABS_MS);

    boolean isCacheHitSlow =
        "HIT".equalsIgnoreCase(s.cacheStatus) && isDeliveryDeficit && !isTtfbOutlier;

    boolean isCdnEvidence = (isTtfbOutlier && isDeliveryHealthy) || isCacheHitSlow;

    return new PerSegmentEvidence(
        excessRatio, isSlow, deliveryHealth, isTtfbOutlier, isCacheHitSlow, isCdnEvidence);
  }

  // ===== Buffer trend =====

  static DiagnosisV4.BufferTrend analyzeBufferTrend(int[] bl) {
    if (bl == null || bl.length < 2) {
      return DiagnosisV4.BufferTrend.UNAVAILABLE;
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

    // ≥ 80% pairs decreasing → MONOTONIC_DRAIN.
    if (decreasing * 5 >= pairs * 4) {
      return DiagnosisV4.BufferTrend.MONOTONIC_DRAIN;
    }

    // Range tight → FLAT.
    if (range < FLAT_RANGE_MS) {
      return DiagnosisV4.BufferTrend.FLAT;
    }

    // Drop-then-recover (last close to first, large range traversed) → SPIKE.
    if (increasing > 0 && decreasing > 0 && bl[n - 1] >= bl[0] * 0.9) {
      return DiagnosisV4.BufferTrend.SPIKE;
    }

    return DiagnosisV4.BufferTrend.OSCILLATING;
  }

  // ===== Statistics helpers =====

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
    for (int i = 1; i < values.length; i++) {
      if (values[i] > m) m = values[i];
    }
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

  // ===== Filters =====

  /** Same predicate as V2: V/DEFAULT scored segments with {@code chunkDurationMs > 0}. */
  static boolean isCompletedScoredSegment(QoSInfo e) {
    if (e == null) return false;
    if (e.status != QoSInfo.LoadStatus.COMPLETED) return false;
    if (e.chunkDurationMs <= 0L) return false;
    return e.trackType == C.TRACK_TYPE_VIDEO || e.trackType == C.TRACK_TYPE_DEFAULT;
  }

  /** Audio-track scored segment with positive chunk duration. */
  static boolean isCompletedAudioSegment(QoSInfo e) {
    if (e == null) return false;
    if (e.status != QoSInfo.LoadStatus.COMPLETED) return false;
    if (e.chunkDurationMs <= 0L) return false;
    return e.trackType == C.TRACK_TYPE_AUDIO;
  }

  // ===== Misc =====

  private static int[] toIntArray(List<Integer> list) {
    if (list == null || list.isEmpty()) return new int[0];
    int[] arr = new int[list.size()];
    for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
    return arr;
  }
}