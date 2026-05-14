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
 * Output verdict of {@code QoSDiagnoserV7} — V6 schema extended with audio-track evidence.
 *
 * <p>V7 = V6 + audio. Per-audio-segment evidence (drained + ttfbExtreme(audioFence) +
 * bodyHealthy(audioBitrate)) feeds the same additive majority gate as V:
 * {@code nDrainedTotal = nVDrained + nADrained}, {@code nCdnEvidenceTotal = nVCdnEvidence +
 * nACdnEvidence}, classify CDN when {@code nCdnEvidenceTotal × 2 ≥ nDrainedTotal}.
 *
 * <p>No mtp guard on audio side (mtp = V-side ABR measurement). Audio fence cold-start
 * → audio evidence silently skipped (V drives verdict).
 */
@UnstableApi
public final class DiagnosisV7 {

  /** Same 3-cause set as V6. */
  public enum Cause {
    CDN,
    CLIENT,
    INCONCLUSIVE
  }

  /** Same 6 inconclusive reasons as V6 ({@code code} suffix is the stable log/UI string). */
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

  public final Cause cause;

  /** 4xx + 5xx HTTP status codes seen in window. Metadata only. */
  public final int[] httpErrorCodes;

  /** Number of completed V scored segments examined. {@code 0} for INCONCLUSIVE paths. */
  public final int nV;

  /** Number of completed audio segments examined. {@code 0} for INCONCLUSIVE paths or no A. */
  public final int nA;

  /** Number of V segs with {@code excessRatio > SLOW_RATIO} (buffer drained during load). */
  public final int nVDrained;

  /** Number of A segs with {@code excessRatio > SLOW_RATIO}. */
  public final int nADrained;

  /**
   * Number of drained V segs that qualified as CDN evidence: TTFB extreme outlier AND
   * body delivery healthy AND mtp NOT collapsed.
   */
  public final int nVCdnEvidence;

  /**
   * Number of drained A segs that qualified as CDN evidence: TTFB extreme outlier AND
   * body delivery healthy. No mtp guard on audio side (mtp = V-side ABR measurement).
   */
  public final int nACdnEvidence;

  /** Resolved V Tukey upper fence (ms). {@code -1} for INCONCLUSIVE. */
  public final int ttfbFenceUpperVMs;

  /**
   * Resolved audio Tukey upper fence (ms). {@code -1} when audio cold-start (audioTtfbFence
   * was null — A-side evidence skipped, V drives verdict).
   */
  public final int ttfbFenceUpperAMs;

  /** Last V seg TTFB (ms). {@code -1} for INCONCLUSIVE. */
  public final int lastTtfbMs;

  /** Last V seg buffered duration (ms). {@code -1} for INCONCLUSIVE. */
  public final int lastBufferMs;

  /** True if at least one drained V seg had {@code mtp < tukey_lower_fence(mtp)}. */
  public final boolean mtpCollapseDetected;

  /** Number of V segs with {@code retryCount > 0}. Metadata only. */
  public final int nRetries;

  /** Why an INCONCLUSIVE verdict was emitted. {@code null} for CDN/CLIENT. */
  @Nullable public final Reason reason;

  /** Sub-reason detail (e.g. sanity-gate sub-fail). {@code null} otherwise. */
  @Nullable public final String reasonDetail;

  private DiagnosisV7(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nA,
      int nVDrained,
      int nADrained,
      int nVCdnEvidence,
      int nACdnEvidence,
      int ttfbFenceUpperVMs,
      int ttfbFenceUpperAMs,
      int lastTtfbMs,
      int lastBufferMs,
      boolean mtpCollapseDetected,
      int nRetries,
      @Nullable Reason reason,
      @Nullable String reasonDetail) {
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
    this.lastTtfbMs = lastTtfbMs;
    this.lastBufferMs = lastBufferMs;
    this.mtpCollapseDetected = mtpCollapseDetected;
    this.nRetries = nRetries;
    this.reason = reason;
    this.reasonDetail = reasonDetail;
  }

  /** Total drained segments across V and A tracks. */
  public int nDrainedTotal() {
    return nVDrained + nADrained;
  }

  /** Total CDN-evidence segments across V and A tracks. */
  public int nCdnEvidenceTotal() {
    return nVCdnEvidence + nACdnEvidence;
  }

  /** INCONCLUSIVE verdict — no detail, no retries. */
  public static DiagnosisV7 inconclusive(Reason reason, int[] httpErrorCodes) {
    return inconclusive(reason, /* detail= */ null, httpErrorCodes, /* nRetries= */ 0);
  }

  /** INCONCLUSIVE verdict with retry-count metadata. */
  public static DiagnosisV7 inconclusive(Reason reason, int[] httpErrorCodes, int nRetries) {
    return inconclusive(reason, /* detail= */ null, httpErrorCodes, nRetries);
  }

  /** INCONCLUSIVE verdict with sub-reason detail (used for {@link Reason#SANITY_FAIL}). */
  public static DiagnosisV7 inconclusive(
      Reason reason, @Nullable String detail, int[] httpErrorCodes, int nRetries) {
    return new DiagnosisV7(
        Cause.INCONCLUSIVE,
        httpErrorCodes,
        /* nV= */ 0,
        /* nA= */ 0,
        /* nVDrained= */ 0,
        /* nADrained= */ 0,
        /* nVCdnEvidence= */ 0,
        /* nACdnEvidence= */ 0,
        /* ttfbFenceUpperVMs= */ -1,
        /* ttfbFenceUpperAMs= */ -1,
        /* lastTtfbMs= */ -1,
        /* lastBufferMs= */ -1,
        /* mtpCollapseDetected= */ false,
        nRetries,
        reason,
        detail);
  }

  /** CDN or CLIENT verdict with full V+A evidence fields. */
  public static DiagnosisV7 classified(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nA,
      int nVDrained,
      int nADrained,
      int nVCdnEvidence,
      int nACdnEvidence,
      int ttfbFenceUpperVMs,
      int ttfbFenceUpperAMs,
      int lastTtfbMs,
      int lastBufferMs,
      boolean mtpCollapseDetected,
      int nRetries) {
    return new DiagnosisV7(
        cause,
        httpErrorCodes,
        nV,
        nA,
        nVDrained,
        nADrained,
        nVCdnEvidence,
        nACdnEvidence,
        ttfbFenceUpperVMs,
        ttfbFenceUpperAMs,
        lastTtfbMs,
        lastBufferMs,
        mtpCollapseDetected,
        nRetries,
        /* reason= */ null,
        /* reasonDetail= */ null);
  }
}
