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
 * Output verdict of {@code QoSDiagnoserV6} — a deliberately simplified 3-cause classifier
 * that uses only buffer-drain proxy ({@code excessRatio > 0.10}) + Tukey TTFB outlier flag.
 *
 * <p>HTTP error codes are attached as <em>metadata</em> for debugging — never used to
 * drive classification (unlike V5 Step 2 fast-path).
 *
 * <p>See {@code spec-qos-v6-algorithm.md} for full rationale + walk-throughs.
 */
@UnstableApi
public final class DiagnosisV6 {

  /**
   * Three mutually exclusive verdicts.
   *
   * <p>Names deliberately broad: V6 attributes only at top-level (server-side vs client-side
   * vs couldn't decide). Sub-causes (e.g. CDN cache-miss vs origin overload, or CLIENT
   * network vs ABR) are NOT distinguished — evidence fields on the diagnosis convey detail.
   */
  public enum Cause {
    /** Server-side responsible (TTFB outlier + body healthy + mtp not collapsed). */
    CDN,
    /** Not-CDN drained the buffer (network/ABR/throttle — V6 does not differentiate). */
    CLIENT,
    /** Couldn't reach a CDN/CLIENT verdict. See {@link Reason} for why. */
    INCONCLUSIVE
  }

  /**
   * Why an INCONCLUSIVE verdict was emitted. {@code null} when {@link #cause} is CDN/CLIENT.
   * The {@code code} suffix is the stable string used in logs and on-screen displays.
   */
  public enum Reason {
    /** Defensive input guard: rebuffer group is null or has no entries at all. */
    EMPTY_GROUP("empty_group"),
    /** Had entries but none qualified as completed V scored segments after filter. */
    NO_COMPLETED_V_SEG("no_completed_v_seg"),
    /** Sanity gate vetoed the window (non-1× playback rate). Detail carries sub-reason. */
    SANITY_FAIL("sanity_fail"),
    /** TTFB baseline not yet warm (< {@code MIN_SAMPLES_FOR_TUKEY} samples for the key). */
    COLD_START("cold_start"),
    /**
     * Trigger was a non-media segment (init.mp4 / index.mpd / manifest refresh) — bs=true
     * fired during a track switch or manifest refresh, not a real playback stall. Classifies
     * as INCONCLUSIVE so the verdict doesn't pin the cause on CDN or CLIENT when the
     * "rebuffer" is a transient transition artifact.
     */
    TRACK_SWITCH("track_switch"),
    /** All V segments were healthy ({@code excessRatio ≤ SLOW_RATIO}); nothing to classify. */
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

  /** 4xx + 5xx HTTP status codes seen in window. Metadata only — never drives classification. */
  public final int[] httpErrorCodes;

  /** Number of completed V scored segments examined. {@code 0} for all INCONCLUSIVE paths. */
  public final int nV;

  /** Number of V segs where {@code excessRatio > SLOW_RATIO} (buffer drained during load). */
  public final int nDrained;

  /** Number of drained V segs that ALSO had {@code ttfb > tukey_upper_fence}. */
  public final int nCdnEvidence;

  /** Resolved Tukey upper fence (ms) used for TTFB outlier check. {@code -1} for INCONCLUSIVE. */
  public final int ttfbFenceUpperMs;

  /** Last V seg TTFB (ms). {@code -1} for INCONCLUSIVE. */
  public final int lastTtfbMs;

  /** Last V seg buffered duration (ms). {@code -1} for INCONCLUSIVE. */
  public final int lastBufferMs;

  /**
   * True if at least one drained V seg had {@code mtp < tukey_lower_fence(mtp)} — i.e.,
   * ABR's measured throughput collapsed below the per-key baseline. Strong signal that
   * the network path itself degraded (Wi-Fi/cellular), not just the CDN. Displayed as
   * "(mtp-collapsed)" suffix in {@link #toDisplaySummary}.
   */
  public final boolean mtpCollapseDetected;

  /**
   * Number of V segs in the rebuffer window that had {@code retryCount > 0} (i.e.,
   * required ≥1 reload due to transient failures). Metadata only — does not drive
   * classification (V6 leaves retry-decisive logic to V5 / aggregated dashboards).
   * Displayed as "retry=N" in {@link #toDisplaySummary} when > 0.
   */
  public final int nRetries;

  /** Why an INCONCLUSIVE verdict was emitted. {@code null} for classified (CDN/CLIENT) verdicts. */
  @Nullable public final Reason reason;

  /**
   * Sub-reason detail (e.g. which sanity-gate condition failed). Only populated when
   * {@link #reason} is {@link Reason#SANITY_FAIL}; {@code null} otherwise.
   */
  @Nullable public final String reasonDetail;

  private DiagnosisV6(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nDrained,
      int nCdnEvidence,
      int ttfbFenceUpperMs,
      int lastTtfbMs,
      int lastBufferMs,
      boolean mtpCollapseDetected,
      int nRetries,
      @Nullable Reason reason,
      @Nullable String reasonDetail) {
    this.cause = cause;
    this.httpErrorCodes = httpErrorCodes;
    this.nV = nV;
    this.nDrained = nDrained;
    this.nCdnEvidence = nCdnEvidence;
    this.ttfbFenceUpperMs = ttfbFenceUpperMs;
    this.lastTtfbMs = lastTtfbMs;
    this.lastBufferMs = lastBufferMs;
    this.mtpCollapseDetected = mtpCollapseDetected;
    this.nRetries = nRetries;
    this.reason = reason;
    this.reasonDetail = reasonDetail;
  }

  /** INCONCLUSIVE verdict (cold_start / empty_group / no_completed_v_seg / no_drain). */
  public static DiagnosisV6 inconclusive(Reason reason, int[] httpErrorCodes) {
    return inconclusive(reason, /* detail= */ null, httpErrorCodes, /* nRetries= */ 0);
  }

  /** INCONCLUSIVE verdict with retry count metadata. */
  public static DiagnosisV6 inconclusive(Reason reason, int[] httpErrorCodes, int nRetries) {
    return inconclusive(reason, /* detail= */ null, httpErrorCodes, nRetries);
  }

  /** INCONCLUSIVE verdict with sub-reason detail (used for {@link Reason#SANITY_FAIL}). */
  public static DiagnosisV6 inconclusive(
      Reason reason, @Nullable String detail, int[] httpErrorCodes, int nRetries) {
    return new DiagnosisV6(
        Cause.INCONCLUSIVE,
        httpErrorCodes,
        /* nV= */ 0,
        /* nDrained= */ 0,
        /* nCdnEvidence= */ 0,
        /* ttfbFenceUpperMs= */ -1,
        /* lastTtfbMs= */ -1,
        /* lastBufferMs= */ -1,
        /* mtpCollapseDetected= */ false,
        nRetries,
        reason,
        detail);
  }

  /** CDN or CLIENT verdict with full evidence fields. */
  public static DiagnosisV6 classified(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nDrained,
      int nCdnEvidence,
      int ttfbFenceUpperMs,
      int lastTtfbMs,
      int lastBufferMs,
      boolean mtpCollapseDetected,
      int nRetries) {
    return new DiagnosisV6(
        cause,
        httpErrorCodes,
        nV,
        nDrained,
        nCdnEvidence,
        ttfbFenceUpperMs,
        lastTtfbMs,
        lastBufferMs,
        mtpCollapseDetected,
        nRetries,
        /* reason= */ null,
        /* reasonDetail= */ null);
  }

  /** Backward-compat overload — defaults {@code mtpCollapseDetected=false} and {@code nRetries=0}. */
  public static DiagnosisV6 classified(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nDrained,
      int nCdnEvidence,
      int ttfbFenceUpperMs,
      int lastTtfbMs,
      int lastBufferMs) {
    return classified(
        cause, httpErrorCodes, nV, nDrained, nCdnEvidence,
        ttfbFenceUpperMs, lastTtfbMs, lastBufferMs,
        /* mtpCollapseDetected= */ false, /* nRetries= */ 0);
  }

  /** Backward-compat overload — defaults {@code nRetries=0}. */
  public static DiagnosisV6 classified(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nDrained,
      int nCdnEvidence,
      int ttfbFenceUpperMs,
      int lastTtfbMs,
      int lastBufferMs,
      boolean mtpCollapseDetected) {
    return classified(
        cause, httpErrorCodes, nV, nDrained, nCdnEvidence,
        ttfbFenceUpperMs, lastTtfbMs, lastBufferMs, mtpCollapseDetected, /* nRetries= */ 0);
  }

  /**
   * Single-line summary for HUD / overlay / support tickets. Centralized so consumers
   * don't duplicate format logic.
   *
   * <p>Field meanings: {@code slow} = number of V segs whose load drained the buffer
   * ({@code excessRatio > 0.10}); {@code server-lag} = of those, how many also had TTFB
   * outlier ({@code ttfb > tukey_upper_fence}). Cause = CDN when ≥50% slow segs are
   * server-lag; else CLIENT.
   *
   * <p>Examples:
   * <pre>
   * 🔴 V6 · CDN · slow=2 server-lag=2 · fence=93ms
   * 🟠 V6 · CLIENT · slow=1 server-lag=0 · fence=150ms
   * ⚪ V6 · INCONCLUSIVE · cold_start
   * ⚪ V6 · INCONCLUSIVE · sanity_fail:demand_low
   * </pre>
   */
  public String toDisplaySummary() {
    StringBuilder sb = new StringBuilder(128);
    sb.append(severityIcon()).append(" V6 · ").append(cause.name());
    if (cause == Cause.INCONCLUSIVE) {
      if (reason != null) {
        sb.append(" · ").append(reason.code);
        if (reasonDetail != null) {
          sb.append(":").append(reasonDetail);
        }
      }
    } else {
      sb.append(" · slow=").append(nDrained)
          .append(" server-lag=").append(nCdnEvidence);
      if (mtpCollapseDetected) {
        sb.append(" (mtp-collapsed)");
      }
      sb.append(" · fence=").append(ttfbFenceUpperMs).append("ms");
    }
    if (nRetries > 0) {
      sb.append(" · retry=").append(nRetries);
    }
    if (httpErrorCodes.length > 0) {
      sb.append(" · codes=").append(java.util.Arrays.toString(httpErrorCodes));
    }
    return sb.toString();
  }

  /**
   * Multi-line full breakdown for expanded UI / debug HUD / support tickets. Each
   * evidence section on its own line.
   */
  public String toFullDetail() {
    StringBuilder sb = new StringBuilder(256);
    sb.append("Cause: ").append(cause.name());
    if (cause == Cause.INCONCLUSIVE && reason != null) {
      sb.append(" · ").append(reason.code);
      if (reasonDetail != null) {
        sb.append(":").append(reasonDetail);
      }
    }
    if (cause != Cause.INCONCLUSIVE) {
      sb.append('\n').append("V segments: ").append(nV);
      sb.append('\n').append("Buffer drained: ").append(nDrained);
      sb.append('\n').append("Server-lag (CDN): ").append(nCdnEvidence);
      sb.append('\n').append("TTFB fence: ").append(ttfbFenceUpperMs).append("ms");
      sb.append('\n').append("Last TTFB: ").append(lastTtfbMs).append("ms");
      sb.append('\n').append("Last buffer: ").append(lastBufferMs).append("ms");
      sb.append('\n').append("Network drop: ").append(mtpCollapseDetected ? "yes" : "no");
    }
    if (nRetries > 0) {
      sb.append('\n').append("Retries: ").append(nRetries);
    }
    if (httpErrorCodes.length > 0) {
      sb.append('\n').append("HTTP codes: ").append(java.util.Arrays.toString(httpErrorCodes));
    }
    return sb.toString();
  }

  private String severityIcon() {
    switch (cause) {
      case CDN:
        return "🔴";
      case CLIENT:
        return "🟠";
      case INCONCLUSIVE:
      default:
        return "⚪";
    }
  }
}
