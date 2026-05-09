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
 * Output of {@link androidx.media3.exoplayer.qos.QoSDiagnoserV5#diagnose}.
 *
 * <p>Client-only paper-anchored verdict with cohort dims for backend reattribution.
 * See {@code spec-qos-v5-client-only.md} for decision flow rationale + Tukey 1977
 * IQR fence anchoring of TTFB / delivery rate outlier detection.
 */
@UnstableApi
public final class DiagnosisV5 {

  /** Final classification (4-cause taxonomy inherited V4). */
  public enum Cause {
    /** Sanity gate failed / no V-segment data / no significant rate anomaly. */
    TRANSIENT,
    /** Decisive: any segment returned an HTTP 5xx response. */
    CDN_HTTP_ERROR,
    /**
     * Decisive: cache-fork branch detected CDN edge stress (HIT slow, delivery rate
     * outlier vs session-rolling Tukey fence) or origin pull latency (MISS-side TTFB
     * outlier with healthy delivery), or ≥2 retries with mtp not collapsed.
     */
    CDN_DELIVERY_SLOW,
    /**
     * Heuristic: majority of V-segments load slower than realtime with significant
     * median deficit. Source ambiguous per-event; cohort dims emitted for backend
     * reattribution.
     */
    INSUFFICIENT_BANDWIDTH,
  }

  /** Cache-status dominance pattern across V-segments (V5 primary fork signal). */
  public enum CacheBranch {
    /** ≥ 80% of known-cache V-segments are HIT. */
    HIT_DOMINANT,
    /** ≥ 80% of known-cache V-segments are MISS. */
    MISS_DOMINANT,
    /** Both HIT and MISS present, neither ≥ 80% — falls to combined evidence. */
    MIXED,
    /** No known cache state (all null/empty cacheStatus). */
    UNKNOWN,
  }

  /** Buffer trajectory shape across V-segments in window (V4 inherited). */
  public enum BufferTrend {
    MONOTONIC_DRAIN,
    SPIKE,
    FLAT,
    OSCILLATING,
    UNAVAILABLE,
  }

  // === Primary verdict ===
  public final Cause cause;
  public final CacheBranch cacheBranch;

  // === Common evidence (always populated) ===
  public final int nVSegments;
  public final int nASegments;
  /** All HTTP error codes seen in window (5xx + 4xx) for debug context. Never null. */
  public final int[] httpErrorCodes;

  // === Rate evidence ===
  public final int nSlowSegments;
  public final double medianExcessRatio;
  public final double meanExcessRatio;
  public final double maxExcessRatio;
  /** Variance of {@code excess_ratio}. MDPI 2021 "sustained vs spiky" modifier. */
  public final double varianceExcess;

  // === CDN evidence (sub-counts of nVSegments) ===
  public final int nCdnEvidenceSegments;
  /** Sub-count: V-segments với {@code ttfb > Tukey upper fence}. */
  public final int nTtfbOutlierSegments;
  /** Sub-count: V-segments với {@code cacheStatus="HIT"} but {@code delivery_health < 0.70}. */
  public final int nCacheHitSlowSegments;
  /** Sub-count: V-segments với {@code post_ttfb_kbps < Tukey lower fence}. */
  public final int nDeliveryRateOutlierSegments;
  /** Sub-count: V-segments với {@code retryCount > 0}. */
  public final int nRetrySegments;

  // === Cross-track + buffer ===
  public final boolean crossTrackCorrelated;
  public final boolean abrWasAware;
  public final BufferTrend bufferTrend;

  // === Tukey fence baseline context (-1 sentinels when not applied) ===
  public final int sessionTtfbQ1Ms;
  public final int sessionTtfbQ3Ms;
  public final int ttfbUpperFenceApplied;
  public final int sessionDeliveryRateQ1Kbps;
  public final int sessionDeliveryRateQ3Kbps;
  public final int deliveryRateLowerFenceApplied;
  /** True when SessionStatistics had &lt; MIN_SAMPLES_FOR_TUKEY → V4 fallback 800ms applied. */
  public final boolean usedColdStartFallback;

  // === Cohort dims for backend reattribution (CoNEXT '15 + Mux/Conviva industry) ===
  public final String cohortNetworkType;
  public final String cohortCacheBranch;
  public final String cohortCdnHostname;
  /** Optional region/country if app-provided. */
  @Nullable public final String cohortRegion;

  // === Misc ===
  public final boolean hadRetries;
  /** Non-null when sanity gate failed; carries reason text for banner/log. */
  @Nullable public final String sanityFailReason;

  public DiagnosisV5(
      Cause cause,
      CacheBranch cacheBranch,
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
      int nDeliveryRateOutlierSegments,
      int nRetrySegments,
      boolean crossTrackCorrelated,
      boolean abrWasAware,
      BufferTrend bufferTrend,
      int sessionTtfbQ1Ms,
      int sessionTtfbQ3Ms,
      int ttfbUpperFenceApplied,
      int sessionDeliveryRateQ1Kbps,
      int sessionDeliveryRateQ3Kbps,
      int deliveryRateLowerFenceApplied,
      boolean usedColdStartFallback,
      String cohortNetworkType,
      String cohortCacheBranch,
      String cohortCdnHostname,
      @Nullable String cohortRegion,
      boolean hadRetries,
      @Nullable String sanityFailReason) {
    this.cause = cause;
    this.cacheBranch = cacheBranch;
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
    this.nDeliveryRateOutlierSegments = nDeliveryRateOutlierSegments;
    this.nRetrySegments = nRetrySegments;
    this.crossTrackCorrelated = crossTrackCorrelated;
    this.abrWasAware = abrWasAware;
    this.bufferTrend = bufferTrend;
    this.sessionTtfbQ1Ms = sessionTtfbQ1Ms;
    this.sessionTtfbQ3Ms = sessionTtfbQ3Ms;
    this.ttfbUpperFenceApplied = ttfbUpperFenceApplied;
    this.sessionDeliveryRateQ1Kbps = sessionDeliveryRateQ1Kbps;
    this.sessionDeliveryRateQ3Kbps = sessionDeliveryRateQ3Kbps;
    this.deliveryRateLowerFenceApplied = deliveryRateLowerFenceApplied;
    this.usedColdStartFallback = usedColdStartFallback;
    this.cohortNetworkType = cohortNetworkType;
    this.cohortCacheBranch = cohortCacheBranch;
    this.cohortCdnHostname = cohortCdnHostname;
    this.cohortRegion = cohortRegion;
    this.hadRetries = hadRetries;
    this.sanityFailReason = sanityFailReason;
  }

  private static final int[] EMPTY_CODES = new int[0];
  private static final String UNKNOWN = "UNKNOWN";

  /** TRANSIENT factory for sanity-gate failure or empty group. */
  public static DiagnosisV5 transientFromSanity(String reason) {
    return new DiagnosisV5(
        Cause.TRANSIENT,
        CacheBranch.UNKNOWN,
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
        /* nDeliveryRateOutlierSegments= */ 0,
        /* nRetrySegments= */ 0,
        /* crossTrackCorrelated= */ false,
        /* abrWasAware= */ false,
        BufferTrend.UNAVAILABLE,
        /* sessionTtfbQ1Ms= */ -1,
        /* sessionTtfbQ3Ms= */ -1,
        /* ttfbUpperFenceApplied= */ -1,
        /* sessionDeliveryRateQ1Kbps= */ -1,
        /* sessionDeliveryRateQ3Kbps= */ -1,
        /* deliveryRateLowerFenceApplied= */ -1,
        /* usedColdStartFallback= */ false,
        /* cohortNetworkType= */ UNKNOWN,
        /* cohortCacheBranch= */ "UNKNOWN",
        /* cohortCdnHostname= */ UNKNOWN,
        /* cohortRegion= */ null,
        /* hadRetries= */ false,
        reason);
  }

  /** TRANSIENT factory for "no V-segment data after filter". */
  public static DiagnosisV5 transientNoVData(int[] httpErrorCodes) {
    return new DiagnosisV5(
        Cause.TRANSIENT,
        CacheBranch.UNKNOWN,
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
        /* nDeliveryRateOutlierSegments= */ 0,
        /* nRetrySegments= */ 0,
        /* crossTrackCorrelated= */ false,
        /* abrWasAware= */ false,
        BufferTrend.UNAVAILABLE,
        /* sessionTtfbQ1Ms= */ -1,
        /* sessionTtfbQ3Ms= */ -1,
        /* ttfbUpperFenceApplied= */ -1,
        /* sessionDeliveryRateQ1Kbps= */ -1,
        /* sessionDeliveryRateQ3Kbps= */ -1,
        /* deliveryRateLowerFenceApplied= */ -1,
        /* usedColdStartFallback= */ false,
        /* cohortNetworkType= */ UNKNOWN,
        /* cohortCacheBranch= */ "UNKNOWN",
        /* cohortCdnHostname= */ UNKNOWN,
        /* cohortRegion= */ null,
        /* hadRetries= */ false,
        /* sanityFailReason= */ "no_v_data");
  }

  /** CDN_HTTP_ERROR factory — decisive 5xx fast-path, evidence fields not computed. */
  public static DiagnosisV5 cdnHttpError(int[] httpErrorCodes) {
    return new DiagnosisV5(
        Cause.CDN_HTTP_ERROR,
        CacheBranch.UNKNOWN,
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
        /* nDeliveryRateOutlierSegments= */ 0,
        /* nRetrySegments= */ 0,
        /* crossTrackCorrelated= */ false,
        /* abrWasAware= */ false,
        BufferTrend.UNAVAILABLE,
        /* sessionTtfbQ1Ms= */ -1,
        /* sessionTtfbQ3Ms= */ -1,
        /* ttfbUpperFenceApplied= */ -1,
        /* sessionDeliveryRateQ1Kbps= */ -1,
        /* sessionDeliveryRateQ3Kbps= */ -1,
        /* deliveryRateLowerFenceApplied= */ -1,
        /* usedColdStartFallback= */ false,
        /* cohortNetworkType= */ UNKNOWN,
        /* cohortCacheBranch= */ "UNKNOWN",
        /* cohortCdnHostname= */ UNKNOWN,
        /* cohortRegion= */ null,
        /* hadRetries= */ false,
        /* sanityFailReason= */ null);
  }
}
