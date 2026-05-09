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

    // TODO Tasks A4-A7: Step 2.5 retry, Step 3 evidence + cache branch, Step 4 cache-fork
    // CDN check, Step 5 bandwidth majority, Step 6 TRANSIENT default with cohort dims.
    // Until then, return TRANSIENT(no_v_data) carrying any 4xx codes for evidence.
    return DiagnosisV5.transientNoVData(toIntArray(http4xx));
  }

  // ===== Misc =====

  private static int[] toIntArray(List<Integer> list) {
    if (list == null || list.isEmpty()) return new int[0];
    int[] arr = new int[list.size()];
    for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
    return arr;
  }
}
