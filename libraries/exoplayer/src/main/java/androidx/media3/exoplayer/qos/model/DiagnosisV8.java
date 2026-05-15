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
 * Output verdict of {@code QoSDiagnoserV8} — V7 algorithm cascade with extended per-event
 * evidence fields (abr-lag counts, drain peak, per-condition counts, baseline quartiles,
 * audio retries).
 *
 * <p>V8 algorithm cascade unchanged from V7 (same SLOW_RATIO, BODY_HEALTHY_RATIO,
 * TUKEY_K=3, majority gate ≥50%). New fields are observational evidence for FPT_Core UI
 * rendering — không tham gia classification logic.
 */
@UnstableApi
public final class DiagnosisV8 {

  /** Same 3-cause set as V7. */
  public enum Cause {
    CDN,
    NETWORK,
    INCONCLUSIVE
  }

  /** Same 6 inconclusive reasons as V7. */
  public enum Reason {
    EMPTY_GROUP("empty_group"),
    NO_COMPLETED_V_SEG("no_completed_v_seg"),
    SANITY_FAIL("sanity_fail"),
    COLD_START("cold_start"),
    TRACK_SWITCH("track_switch"),
    NO_DRAIN("no_drain");

    public final String code;

    Reason(String code) {
      this.code = code;
    }

    @Override
    public String toString() {
      return code;
    }
  }

  // === V7 fields preserved verbatim ===

  public final Cause cause;
  /** 4xx + 5xx HTTP status codes seen in window. Metadata only. */
  public final int[] httpErrorCodes;
  /** Number of completed V scored segments examined. {@code 0} for INCONCLUSIVE paths. */
  public final int nV;
  /** Number of completed audio segments examined. */
  public final int nA;
  /** Number of V segs with {@code excessRatio > SLOW_RATIO_V}. */
  public final int nVDrained;
  /** Number of A segs with {@code excessRatio > SLOW_RATIO_A}. */
  public final int nADrained;
  /** Drained V segs qualifying CDN evidence (ttfb-extreme AND body-healthy AND !mtp-collapsed). */
  public final int nVCdnEvidence;
  /** Drained A segs qualifying CDN evidence (ttfb-extreme AND body-healthy). */
  public final int nACdnEvidence;
  /** Resolved V Tukey upper fence (ms). {@code -1} for INCONCLUSIVE. */
  public final int ttfbFenceUpperVMs;
  /** Resolved audio Tukey upper fence (ms). {@code -1} when audio cold-start. */
  public final int ttfbFenceUpperAMs;
  /** Number of V segs with {@code retryCount > 0}. */
  public final int nRetries;
  /** Cohort label: network type của lastV (WIFI/4G/...). */
  public final String cohortNetworkType;
  /** Cohort label: CDN provider của lastV (fpt/akamai/...). */
  public final String cohortCdnProvider;
  /** Why an INCONCLUSIVE verdict was emitted. {@code null} for CDN/NETWORK. */
  @Nullable public final Reason reason;
  /** Sub-reason detail (e.g. sanity-gate sub-fail). */
  @Nullable public final String reasonDetail;

  // === NEW V8 fields ===

  /** V scored segs với {@code bitrate > mtp × 0.8} (ABR over-estimate). */
  public final int nVAbrLag;
  /** Subset of {@link #nVCdnEvidence} cũng có abr-lag. */
  public final int nVAbrLagInCdn;
  /** Subset of {@link #nVDrained} có abr-lag. */
  public final int nVAbrLagInDrain;

  /** Drained V segs với {@code ttfb > fence} (no body/mtp guards). */
  public final int nVTtfbExtreme;
  /** Drained A segs với {@code ttfb > audioFence}. */
  public final int nATtfbExtreme;

  /** Drained V segs với {@code postKbps < bitrate × 0.90}. */
  public final int nVBodyUnhealthy;
  /** Drained A segs với {@code postKbps < bitrate × 0.90}. */
  public final int nABodyUnhealthy;

  /** Drained V segs với {@code mtp < mtpFence.lowerFence}. Audio không có mtp → no A equivalent. */
  public final int nVMtpCollapsed;

  /** Max excessRatio across V drained segs. {@code 0.0} nếu nVDrained=0. */
  public final double drainPeakRatioV;
  /** Seg index của V drain peak. {@code -1} nếu nVDrained=0. */
  public final int drainPeakSegIdxV;
  /** Max excessRatio across A drained segs. */
  public final double drainPeakRatioA;
  /** Seg index của A drain peak. */
  public final int drainPeakSegIdxA;

  /** Audio segs với {@code retryCount>0} (nRetries chỉ count V). */
  public final int nARetries;

  /** V TTFB baseline Q1 (Tukey hinge). {@code -1} khi fence null (cold-start). */
  public final int ttfbQ1V;
  /** V TTFB baseline median. {@code -1} khi fence null. */
  public final int ttfbMedianV;
  /** V TTFB baseline Q3 (Tukey hinge). {@code -1} khi fence null. */
  public final int ttfbQ3V;
  /** A baseline Q1. {@code -1} khi audioFence null. */
  public final int ttfbQ1A;
  /** A baseline median. */
  public final int ttfbMedianA;
  /** A baseline Q3. */
  public final int ttfbQ3A;

  private DiagnosisV8(
      Cause cause,
      int[] httpErrorCodes,
      int nV, int nA,
      int nVDrained, int nADrained,
      int nVCdnEvidence, int nACdnEvidence,
      int ttfbFenceUpperVMs, int ttfbFenceUpperAMs,
      int nRetries,
      String cohortNetworkType, String cohortCdnProvider,
      int nVAbrLag, int nVAbrLagInCdn, int nVAbrLagInDrain,
      int nVTtfbExtreme, int nATtfbExtreme,
      int nVBodyUnhealthy, int nABodyUnhealthy,
      int nVMtpCollapsed,
      double drainPeakRatioV, int drainPeakSegIdxV,
      double drainPeakRatioA, int drainPeakSegIdxA,
      int nARetries,
      int ttfbQ1V, int ttfbMedianV, int ttfbQ3V,
      int ttfbQ1A, int ttfbMedianA, int ttfbQ3A,
      @Nullable Reason reason, @Nullable String reasonDetail) {
    this.cause = cause;
    this.httpErrorCodes = httpErrorCodes;
    this.nV = nV;
    this.nA = nA;
    this.nVDrained = nVDrained;
    this.nADrained = nADrained;
    this.nVCdnEvidence = nVCdnEvidence;
    this.nACdnEvidence = nACdnEvidence;
    this.ttfbFenceUpperVMs = ttfbFenceUpperVMs;
    this.ttfbFenceUpperAMs = ttfbFenceUpperAMs;
    this.nRetries = nRetries;
    this.cohortNetworkType = cohortNetworkType;
    this.cohortCdnProvider = cohortCdnProvider;
    this.nVAbrLag = nVAbrLag;
    this.nVAbrLagInCdn = nVAbrLagInCdn;
    this.nVAbrLagInDrain = nVAbrLagInDrain;
    this.nVTtfbExtreme = nVTtfbExtreme;
    this.nATtfbExtreme = nATtfbExtreme;
    this.nVBodyUnhealthy = nVBodyUnhealthy;
    this.nABodyUnhealthy = nABodyUnhealthy;
    this.nVMtpCollapsed = nVMtpCollapsed;
    this.drainPeakRatioV = drainPeakRatioV;
    this.drainPeakSegIdxV = drainPeakSegIdxV;
    this.drainPeakRatioA = drainPeakRatioA;
    this.drainPeakSegIdxA = drainPeakSegIdxA;
    this.nARetries = nARetries;
    this.ttfbQ1V = ttfbQ1V;
    this.ttfbMedianV = ttfbMedianV;
    this.ttfbQ3V = ttfbQ3V;
    this.ttfbQ1A = ttfbQ1A;
    this.ttfbMedianA = ttfbMedianA;
    this.ttfbQ3A = ttfbQ3A;
    this.reason = reason;
    this.reasonDetail = reasonDetail;
  }

  /** Total drained segments across V and A tracks. */
  public int nDrainedTotal() { return nVDrained + nADrained; }
  /** Total CDN-evidence segments across V and A tracks. */
  public int nCdnEvidenceTotal() { return nVCdnEvidence + nACdnEvidence; }

  /**
   * Single-line summary for HUD / overlay / support tickets. Format extends V7 with drain
   * peak (V/A) and audio retries.
   *
   * <p>Examples:
   * <pre>
   * 🔴 V8 · CDN · vSlow=34 aSlow=12 vSvrLag=18 aSvrLag=3 peakV=1.85 peakA=1.42 · fenceV=1140ms fenceA=1080ms
   * 🟠 V8 · NETWORK · vSlow=4 aSlow=0 vSvrLag=0 aSvrLag=0 · fenceV=100ms fenceA=-1ms
   * ⚪ V8 · INCONCLUSIVE · cold_start
   * </pre>
   */
  public String toDisplaySummary() {
    StringBuilder sb = new StringBuilder(192);
    sb.append(severityIcon()).append(" V8 · ").append(cause.name());
    if (cause == Cause.INCONCLUSIVE) {
      if (reason != null) {
        sb.append(" · ").append(reason.code);
        if (reasonDetail != null) {
          sb.append(":").append(reasonDetail);
        }
      }
    } else {
      sb.append(" · vSlow=").append(nVDrained)
          .append(" aSlow=").append(nADrained)
          .append(" vSvrLag=").append(nVCdnEvidence)
          .append(" aSvrLag=").append(nACdnEvidence);
      if (drainPeakRatioV > 0) {
        sb.append(" peakV=").append(String.format(java.util.Locale.US, "%.2f", drainPeakRatioV));
      }
      if (drainPeakRatioA > 0) {
        sb.append(" peakA=").append(String.format(java.util.Locale.US, "%.2f", drainPeakRatioA));
      }
      sb.append(" · fenceV=").append(ttfbFenceUpperVMs).append("ms");
      sb.append(" fenceA=").append(ttfbFenceUpperAMs).append("ms");
    }
    if (nRetries > 0 || nARetries > 0) {
      if (nARetries > 0) {
        // Audio retries present — show both explicitly to avoid ambiguous "retry=0(A1)"
        sb.append(" · retry=V").append(nRetries).append("/A").append(nARetries);
      } else {
        // V-only — preserve V7-compatible "retry=N" format
        sb.append(" · retry=").append(nRetries);
      }
    }
    if (httpErrorCodes.length > 0) {
      sb.append(" · codes=").append(java.util.Arrays.toString(httpErrorCodes));
    }
    return sb.toString();
  }

  private String severityIcon() {
    switch (cause) {
      case CDN: return "🔴";
      case NETWORK: return "🟠";
      case INCONCLUSIVE:
      default: return "⚪";
    }
  }

  public static DiagnosisV8 inconclusive(Reason reason, int[] httpErrorCodes) {
    return inconclusive(reason, /* detail= */ null, httpErrorCodes, /* nRetries= */ 0, /* nARetries= */ 0);
  }

  public static DiagnosisV8 inconclusive(Reason reason, int[] httpErrorCodes, int nRetries) {
    return inconclusive(reason, /* detail= */ null, httpErrorCodes, nRetries, /* nARetries= */ 0);
  }

  public static DiagnosisV8 inconclusive(
      Reason reason, @Nullable String detail, int[] httpErrorCodes, int nRetries) {
    return inconclusive(reason, detail, httpErrorCodes, nRetries, /* nARetries= */ 0);
  }

  public static DiagnosisV8 inconclusive(
      Reason reason, @Nullable String detail, int[] httpErrorCodes, int nRetries, int nARetries) {
    return new DiagnosisV8(
        Cause.INCONCLUSIVE,
        httpErrorCodes,
        /* nV= */ 0, /* nA= */ 0,
        /* nVDrained= */ 0, /* nADrained= */ 0,
        /* nVCdnEvidence= */ 0, /* nACdnEvidence= */ 0,
        /* ttfbFenceUpperVMs= */ -1, /* ttfbFenceUpperAMs= */ -1,
        nRetries,
        /* cohortNetworkType= */ "UNKNOWN", /* cohortCdnProvider= */ "UNKNOWN",
        /* nVAbrLag= */ 0, /* nVAbrLagInCdn= */ 0, /* nVAbrLagInDrain= */ 0,
        /* nVTtfbExtreme= */ 0, /* nATtfbExtreme= */ 0,
        /* nVBodyUnhealthy= */ 0, /* nABodyUnhealthy= */ 0,
        /* nVMtpCollapsed= */ 0,
        /* drainPeakRatioV= */ 0.0, /* drainPeakSegIdxV= */ -1,
        /* drainPeakRatioA= */ 0.0, /* drainPeakSegIdxA= */ -1,
        nARetries,
        /* ttfbQ1V= */ -1, /* ttfbMedianV= */ -1, /* ttfbQ3V= */ -1,
        /* ttfbQ1A= */ -1, /* ttfbMedianA= */ -1, /* ttfbQ3A= */ -1,
        reason, detail);
  }

  public static DiagnosisV8 classified(
      Cause cause,
      int[] httpErrorCodes,
      int nV, int nA,
      int nVDrained, int nADrained,
      int nVCdnEvidence, int nACdnEvidence,
      int ttfbFenceUpperVMs, int ttfbFenceUpperAMs,
      int nRetries,
      String cohortNetworkType, String cohortCdnProvider,
      int nVAbrLag, int nVAbrLagInCdn, int nVAbrLagInDrain,
      int nVTtfbExtreme, int nATtfbExtreme,
      int nVBodyUnhealthy, int nABodyUnhealthy,
      int nVMtpCollapsed,
      double drainPeakRatioV, int drainPeakSegIdxV,
      double drainPeakRatioA, int drainPeakSegIdxA,
      int nARetries,
      int ttfbQ1V, int ttfbMedianV, int ttfbQ3V,
      int ttfbQ1A, int ttfbMedianA, int ttfbQ3A) {
    return new DiagnosisV8(
        cause, httpErrorCodes,
        nV, nA,
        nVDrained, nADrained,
        nVCdnEvidence, nACdnEvidence,
        ttfbFenceUpperVMs, ttfbFenceUpperAMs,
        nRetries,
        cohortNetworkType, cohortCdnProvider,
        nVAbrLag, nVAbrLagInCdn, nVAbrLagInDrain,
        nVTtfbExtreme, nATtfbExtreme,
        nVBodyUnhealthy, nABodyUnhealthy,
        nVMtpCollapsed,
        drainPeakRatioV, drainPeakSegIdxV,
        drainPeakRatioA, drainPeakSegIdxA,
        nARetries,
        ttfbQ1V, ttfbMedianV, ttfbQ3V,
        ttfbQ1A, ttfbMedianA, ttfbQ3A,
        /* reason= */ null, /* reasonDetail= */ null);
  }
}
