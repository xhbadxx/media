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

  /** Three mutually exclusive verdicts. */
  public enum Cause {
    CDN_DELIVERY_SLOW,
    CLIENT_INSUFFICIENT_BANDWIDTH,
    UNKNOWN
  }

  public final Cause cause;

  /** 4xx + 5xx HTTP status codes seen in window. Metadata only — never drives classification. */
  public final int[] httpErrorCodes;

  /** Number of completed V scored segments examined. {@code 0} for UNKNOWN(no_v_data). */
  public final int nV;

  /** Number of V segs where {@code excessRatio > SLOW_RATIO} (buffer drained during load). */
  public final int nDrained;

  /** Number of drained V segs that ALSO had {@code ttfb > tukey_upper_fence}. */
  public final int nCdnEvidence;

  /** Resolved Tukey upper fence (ms) used for TTFB outlier check. {@code -1} for UNKNOWN. */
  public final int ttfbFenceUpperMs;

  /** Last V seg TTFB (ms). {@code -1} for UNKNOWN. */
  public final int lastTtfbMs;

  /** Last V seg buffered duration (ms). {@code -1} for UNKNOWN. */
  public final int lastBufferMs;

  /**
   * Reason for UNKNOWN verdict: {@code "no_v_data"}, {@code "cold_start"}, or
   * {@code "no_drain"}. {@code null} for classified (CDN/CLIENT) verdicts.
   */
  @Nullable public final String unknownReason;

  private DiagnosisV6(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nDrained,
      int nCdnEvidence,
      int ttfbFenceUpperMs,
      int lastTtfbMs,
      int lastBufferMs,
      @Nullable String unknownReason) {
    this.cause = cause;
    this.httpErrorCodes = httpErrorCodes;
    this.nV = nV;
    this.nDrained = nDrained;
    this.nCdnEvidence = nCdnEvidence;
    this.ttfbFenceUpperMs = ttfbFenceUpperMs;
    this.lastTtfbMs = lastTtfbMs;
    this.lastBufferMs = lastBufferMs;
    this.unknownReason = unknownReason;
  }

  /** UNKNOWN verdict (cold_start / no_v_data / no_drain). */
  public static DiagnosisV6 unknown(String reason, int[] httpErrorCodes) {
    return new DiagnosisV6(
        Cause.UNKNOWN,
        httpErrorCodes,
        /* nV= */ 0,
        /* nDrained= */ 0,
        /* nCdnEvidence= */ 0,
        /* ttfbFenceUpperMs= */ -1,
        /* lastTtfbMs= */ -1,
        /* lastBufferMs= */ -1,
        reason);
  }

  /** CDN_DELIVERY_SLOW or CLIENT_INSUFFICIENT_BANDWIDTH verdict with full evidence fields. */
  public static DiagnosisV6 classified(
      Cause cause,
      int[] httpErrorCodes,
      int nV,
      int nDrained,
      int nCdnEvidence,
      int ttfbFenceUpperMs,
      int lastTtfbMs,
      int lastBufferMs) {
    return new DiagnosisV6(
        cause,
        httpErrorCodes,
        nV,
        nDrained,
        nCdnEvidence,
        ttfbFenceUpperMs,
        lastTtfbMs,
        lastBufferMs,
        /* unknownReason= */ null);
  }

  /**
   * Single-line summary for HUD / overlay / support tickets. Centralized so consumers
   * don't duplicate format logic.
   *
   * <p>Examples:
   * <pre>
   * 🔴 V6 · CDN_DELIVERY_SLOW · 3/4 drained · fence=200ms
   * 🟠 V6 · CLIENT_INSUFFICIENT_BANDWIDTH · 0/5 drained
   * ⚪ V6 · UNKNOWN · cold_start
   * </pre>
   */
  public String toDisplaySummary() {
    StringBuilder sb = new StringBuilder(128);
    sb.append(severityIcon()).append(" V6 · ").append(cause.name());
    if (cause == Cause.UNKNOWN) {
      if (unknownReason != null) {
        sb.append(" · ").append(unknownReason);
      }
    } else {
      sb.append(" · ").append(nCdnEvidence).append('/').append(nDrained).append(" drained");
      if (cause == Cause.CDN_DELIVERY_SLOW) {
        sb.append(" · fence=").append(ttfbFenceUpperMs).append("ms");
      }
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
    if (cause == Cause.UNKNOWN && unknownReason != null) {
      sb.append(" · ").append(unknownReason);
    }
    sb.append('\n');
    if (cause != Cause.UNKNOWN) {
      sb.append("Evidence: nV=").append(nV)
          .append(" drained=").append(nDrained)
          .append(" cdnEv=").append(nCdnEvidence)
          .append('\n');
      sb.append("Last seg: ttfb=").append(lastTtfbMs).append("ms")
          .append(" bl=").append(lastBufferMs).append("ms")
          .append('\n');
      sb.append("fence=").append(ttfbFenceUpperMs).append("ms");
    }
    if (httpErrorCodes.length > 0) {
      if (cause != Cause.UNKNOWN) sb.append('\n');
      sb.append("HTTP codes: ").append(java.util.Arrays.toString(httpErrorCodes));
    }
    return sb.toString();
  }

  private String severityIcon() {
    switch (cause) {
      case CDN_DELIVERY_SLOW:
        return "🔴";
      case CLIENT_INSUFFICIENT_BANDWIDTH:
        return "🟠";
      case UNKNOWN:
      default:
        return "⚪";
    }
  }
}
