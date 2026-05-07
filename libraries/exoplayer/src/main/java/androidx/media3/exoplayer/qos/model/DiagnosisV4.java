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
package androidx.media3.exoplayer.qos.model;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

/**
 * Output of {@link androidx.media3.exoplayer.qos.QoSDiagnoserV4#diagnose}.
 *
 * <p>Multi-evidence cascade verdict with rich evidence fields exposed for downstream
 * consumers (log/UI/backend aggregation). See {@code spec-qos-v4-algorithm.md} for
 * decision flow rationale.
 */
@UnstableApi
public final class DiagnosisV4 {

  /** Final classification. */
  public enum Cause {
    /** Sanity gate failed / no V-segment data / no significant rate anomaly. */
    TRANSIENT,
    /** Decisive: any segment returned an HTTP 5xx response. */
    CDN_HTTP_ERROR,
    /**
     * Decisive: majority of V-segments exhibit CDN edge-attributable pattern (TTFB
     * outlier with healthy delivery rate, or cache HIT with deficit delivery).
     */
    CDN_DELIVERY_SLOW,
    /**
     * Heuristic: majority of V-segments load slower than realtime with significant
     * median deficit. Source ambiguous (could be CDN, client throttle, ISP cap, or
     * mid-path congestion). Backend correlation needed for source attribution.
     */
    INSUFFICIENT_BANDWIDTH,
  }

  /** Buffer trajectory shape across V-segments in window. */
  public enum BufferTrend {
    /** {@code bufferedDurationMs} strictly decreasing across ≥ 80% of segment pairs. */
    MONOTONIC_DRAIN,
    /** Buffer dropped then recovered (transient hiccup). */
    SPIKE,
    /** Buffer roughly constant (range &lt; FLAT_RANGE_MS). */
    FLAT,
    /** Complex up-down pattern, no clear shape. */
    OSCILLATING,
    /** Insufficient buffer-state data points (&lt; 2 segments with valid bl). */
    UNAVAILABLE,
  }

  // === Primary verdict ===
  public final Cause cause;

  // === Common evidence (always populated) ===
  /** V-segments analysed in window after filter. */
  public final int nVSegments;
  /** A-segments analysed in window after filter. */
  public final int nASegments;
  /** All HTTP error codes seen in window (5xx + 4xx) for debug context. Never null. */
  public final int[] httpErrorCodes;

  // === Rate evidence (-1.0 / 0 sentinels when not computed) ===
  /** V-segments with {@code excess_ratio > SLOW_RATIO}. */
  public final int nSlowSegments;
  /** Median of V-segment {@code excess_ratio}. Primary decision metric. */
  public final double medianExcessRatio;
  /** Mean of V-segment {@code excess_ratio}. Sensitivity metric (compare with median for skew). */
  public final double meanExcessRatio;
  /** Max of V-segment {@code excess_ratio}. Outlier detection. */
  public final double maxExcessRatio;
  /** Variance of V-segment {@code excess_ratio}. High variance + high mean = spike pattern. */
  public final double varianceExcess;

  // === CDN evidence (sub-counts of nVSegments) ===
  /** V-segments matching CDN edge-slow pattern (TTFB outlier + healthy delivery, OR cache HIT slow). */
  public final int nCdnEvidenceSegments;
  /** Sub-count: V-segments with {@code ttfb > TTFB_OUTLIER_ABS_MS}. */
  public final int nTtfbOutlierSegments;
  /** Sub-count: V-segments with {@code cacheStatus="HIT"} but {@code delivery_health < DELIVERY_DEFICIT_RATIO}. */
  public final int nCacheHitSlowSegments;

  // === Cross-track evidence ===
  /** Audio segments also majority slow → systemic; false → V-only drain (V endpoint specific). */
  public final boolean crossTrackCorrelated;

  // === ABR evidence ===
  /** {@code mtp < bitrate} on the last V-segment in window — ABR had bandwidth warning. */
  public final boolean abrWasAware;

  // === Buffer trajectory ===
  public final BufferTrend bufferTrend;

  // === Misc ===
  /** Any segment in window had {@code retryCount > 0}. */
  public final boolean hadRetries;
  /** Non-null when sanity gate failed; carries reason text for banner/log. */
  @Nullable public final String sanityFailReason;

  public DiagnosisV4(
      Cause cause,
      int nVSegments,
      int nASegments,
      int[] httpErrorCodes,
      int nSlowSegments,
      double medianExcessRatio,
      double meanExcessRatio,
      double maxExcessRatio,
      double varianceExcess,
      int nCdnEvidenceSegments,
      int nTtfbOutlierSegments,
      int nCacheHitSlowSegments,
      boolean crossTrackCorrelated,
      boolean abrWasAware,
      BufferTrend bufferTrend,
      boolean hadRetries,
      @Nullable String sanityFailReason) {
    this.cause = cause;
    this.nVSegments = nVSegments;
    this.nASegments = nASegments;
    this.httpErrorCodes = httpErrorCodes;
    this.nSlowSegments = nSlowSegments;
    this.medianExcessRatio = medianExcessRatio;
    this.meanExcessRatio = meanExcessRatio;
    this.maxExcessRatio = maxExcessRatio;
    this.varianceExcess = varianceExcess;
    this.nCdnEvidenceSegments = nCdnEvidenceSegments;
    this.nTtfbOutlierSegments = nTtfbOutlierSegments;
    this.nCacheHitSlowSegments = nCacheHitSlowSegments;
    this.crossTrackCorrelated = crossTrackCorrelated;
    this.abrWasAware = abrWasAware;
    this.bufferTrend = bufferTrend;
    this.hadRetries = hadRetries;
    this.sanityFailReason = sanityFailReason;
  }

  private static final int[] EMPTY_CODES = new int[0];

  /** TRANSIENT factory for sanity-gate failure. */
  public static DiagnosisV4 transientFromSanity(String reason) {
    return new DiagnosisV4(
        Cause.TRANSIENT,
        /* nVSegments= */ 0,
        /* nASegments= */ 0,
        EMPTY_CODES,
        /* nSlowSegments= */ 0,
        /* medianExcessRatio= */ 0.0,
        /* meanExcessRatio= */ 0.0,
        /* maxExcessRatio= */ 0.0,
        /* varianceExcess= */ 0.0,
        /* nCdnEvidenceSegments= */ 0,
        /* nTtfbOutlierSegments= */ 0,
        /* nCacheHitSlowSegments= */ 0,
        /* crossTrackCorrelated= */ false,
        /* abrWasAware= */ false,
        BufferTrend.UNAVAILABLE,
        /* hadRetries= */ false,
        reason);
  }

  /** TRANSIENT factory for "no V-segment data after filter". */
  public static DiagnosisV4 transientNoVData(int[] httpErrorCodes) {
    return new DiagnosisV4(
        Cause.TRANSIENT,
        /* nVSegments= */ 0,
        /* nASegments= */ 0,
        httpErrorCodes,
        /* nSlowSegments= */ 0,
        /* medianExcessRatio= */ 0.0,
        /* meanExcessRatio= */ 0.0,
        /* maxExcessRatio= */ 0.0,
        /* varianceExcess= */ 0.0,
        /* nCdnEvidenceSegments= */ 0,
        /* nTtfbOutlierSegments= */ 0,
        /* nCacheHitSlowSegments= */ 0,
        /* crossTrackCorrelated= */ false,
        /* abrWasAware= */ false,
        BufferTrend.UNAVAILABLE,
        /* hadRetries= */ false,
        /* sanityFailReason= */ "no_v_data");
  }

  /** CDN_HTTP_ERROR factory — decisive 5xx fast-path, evidence fields not computed. */
  public static DiagnosisV4 cdnHttpError(int[] httpErrorCodes) {
    return new DiagnosisV4(
        Cause.CDN_HTTP_ERROR,
        /* nVSegments= */ 0,
        /* nASegments= */ 0,
        httpErrorCodes,
        /* nSlowSegments= */ 0,
        /* medianExcessRatio= */ 0.0,
        /* meanExcessRatio= */ 0.0,
        /* maxExcessRatio= */ 0.0,
        /* varianceExcess= */ 0.0,
        /* nCdnEvidenceSegments= */ 0,
        /* nTtfbOutlierSegments= */ 0,
        /* nCacheHitSlowSegments= */ 0,
        /* crossTrackCorrelated= */ false,
        /* abrWasAware= */ false,
        BufferTrend.UNAVAILABLE,
        /* hadRetries= */ false,
        /* sanityFailReason= */ null);
  }
}