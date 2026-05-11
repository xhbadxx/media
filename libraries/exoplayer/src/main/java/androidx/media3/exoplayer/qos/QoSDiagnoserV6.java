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
import androidx.media3.exoplayer.qos.model.DiagnosisV6;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function rebuffer diagnoser V6 — deliberately simplified 3-cause classifier.
 *
 * <p>Algorithm (5 steps, see {@code spec-qos-v6-algorithm.md}):
 * <ol>
 *   <li>Filter completed V scored segments. Empty → UNKNOWN(no_v_data).
 *   <li>Cold-start check: {@link SessionStatistics#getTtfbFence} on
 *       {@code (network, cache, cdn)} key. Null → UNKNOWN(cold_start).
 *   <li>Per-V evidence: {@code isDrained = excessRatio > 0.10},
 *       {@code isTtfbOutlier = ttfb > tukey_upper_fence}.
 *   <li>Classify drained segs. Zero drained → UNKNOWN(no_drain). Else CDN if
 *       {@code nCdnEvidence × 2 ≥ nDrained}; else CLIENT.
 *   <li>Attach metadata (HTTP error codes, debug fields). HTTP codes never drive cause.
 * </ol>
 */
@UnstableApi
public final class QoSDiagnoserV6 {

  private QoSDiagnoserV6() {}

  /** Per-segment slowness threshold: {@code excessRatio > 0.10} = buffer drained. */
  static final double SLOW_RATIO = 0.10;

  /** Majority gate: {@code nCdnEvidence × DENOM ≥ nDrained × NUM} — i.e., ≥50%. */
  static final int CDN_MAJORITY_NUM = 1;

  static final int CDN_MAJORITY_DENOM = 2;

  /**
   * Strict Tukey multiplier (extreme outlier per Tukey 1977) used by V6 for TTFB. Tighter
   * than {@link SessionStatistics#TUKEY_K}=1.5 — only segments with truly extreme TTFB
   * count as CDN evidence, reducing false positives from network-path RTT fluctuations.
   */
  static final double TTFB_STRICT_TUKEY_K = 3.0;

  public static DiagnosisV6 diagnose(
      @Nullable RebufferGroup group, @Nullable SessionStatistics sessionStats) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return DiagnosisV6.unknown("no_v_data", new int[0]);
    }

    int[] httpErrorCodes = collectHttpErrorCodes(group.entries);

    // Step 1: filter completed V scored segments (reuse V5 helper).
    List<QoSInfo> vSegs = new ArrayList<>();
    for (QoSInfo s : group.entries) {
      if (QoSDiagnoserV5.isCompletedScoredSegment(s)) {
        vSegs.add(s);
      }
    }
    if (vSegs.isEmpty()) {
      return DiagnosisV6.unknown("no_v_data", httpErrorCodes);
    }

    // Step 1.5: sanity gate (reuse V1). Drain proxy excessRatio and Tukey TTFB
    // checks both assume 1× playback. Filter non-1× windows (paused / sped /
    // seeked / degenerate) so they don't yield bogus CDN vs CLIENT verdicts.
    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoser.applySanityG1ate(metrics);
    if (sanityFail != null) {
      return DiagnosisV6.unknown("sanity:" + sanityFail, httpErrorCodes);
    }

    // Step 2: cold-start check via Tukey fence on (network, cache, cdn) key.
    QoSInfo lastV = vSegs.get(vSegs.size() - 1);
    String key = QoSDiagnoserV5.sessionKey(lastV);
    SessionStatistics.TukeyFence ttfbFence =
        sessionStats == null ? null : sessionStats.getTtfbFence(key);
    if (ttfbFence == null) {
      return DiagnosisV6.unknown("cold_start", httpErrorCodes);
    }
    // mtp fence is optional — null when mtp samples haven't warmed yet. In that case
    // the mtp-collapse guard is silently skipped (degrades to V6 without guard).
    // (sessionStats != null guaranteed since ttfbFence != null above implies it.)
    SessionStatistics.TukeyFence mtpFence = sessionStats.getMtpFence(key);

    // Strict TTFB outlier threshold: Q3 + 3·IQR (K=3 extreme outlier instead of
    // SessionStatistics' default K=1.5). Reduces false-positive CDN claims from mild
    // network-path RTT fluctuations.
    int strictTtfbUpper =
        ttfbFence.q3 + (int) Math.round(TTFB_STRICT_TUKEY_K * ttfbFence.iqr);

    // Step 3: per-V evidence — count drained segs, TTFB extreme outliers, and how many
    // have BOTH TTFB extreme outlier AND mtp NOT collapsed (= true CDN evidence).
    int nV = vSegs.size();
    int nDrained = 0;
    int nCdnEvidence = 0;
    boolean mtpCollapseDetected = false;
    for (QoSInfo s : vSegs) {
      double excessRatio =
          (double) Math.max(0L, s.loadDurationMs - s.chunkDurationMs) / s.chunkDurationMs;
      boolean isDrained = excessRatio > SLOW_RATIO;
      if (!isDrained) continue;
      nDrained++;
      boolean isTtfbExtreme = s.ttfbMs > strictTtfbUpper;
      // mtp-collapse guard: if ABR's measured throughput dropped below per-key Tukey
      // lower fence, the bottleneck is network/Wi-Fi (CLIENT) — even if TTFB is high,
      // it's a network-path symptom not server-side. Don't credit this as CDN evidence.
      boolean isMtpCollapsed =
          mtpFence != null
              && s.measuredThroughputKbps > 0
              && s.measuredThroughputKbps < mtpFence.lowerFence;
      if (isMtpCollapsed) mtpCollapseDetected = true;
      if (isTtfbExtreme && !isMtpCollapsed) {
        nCdnEvidence++;
      }
    }

    // Step 4: classify.
    if (nDrained == 0) {
      return DiagnosisV6.unknown("no_drain", httpErrorCodes);
    }
    DiagnosisV6.Cause cause =
        (nCdnEvidence * CDN_MAJORITY_DENOM >= nDrained * CDN_MAJORITY_NUM)
            ? DiagnosisV6.Cause.CDN_DELIVERY_SLOW
            : DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH;

    // Step 5: attach metadata.
    return DiagnosisV6.classified(
        cause,
        httpErrorCodes,
        nV,
        nDrained,
        nCdnEvidence,
        strictTtfbUpper,
        lastV.ttfbMs,
        lastV.bufferedDurationMs,
        mtpCollapseDetected);
  }

  /** Collect 4xx + 5xx HTTP status codes from any errored entry — metadata only. */
  private static int[] collectHttpErrorCodes(List<QoSInfo> entries) {
    List<Integer> codes = new ArrayList<>();
    for (QoSInfo s : entries) {
      if (s == null) continue;
      if (s.status != QoSInfo.LoadStatus.ERROR) continue;
      if (s.httpStatusCode >= 400 && s.httpStatusCode <= 599) {
        codes.add(s.httpStatusCode);
      }
    }
    if (codes.isEmpty()) return new int[0];
    int[] arr = new int[codes.size()];
    for (int i = 0; i < codes.size(); i++) arr[i] = codes.get(i);
    return arr;
  }
}
