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
import androidx.media3.exoplayer.qos.model.DiagnosisV8;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function rebuffer diagnoser V8 — V7 cascade verbatim, new V8 fields default.
 *
 * <p>Algorithm identical to V7 (same constants, same step order, same evidence rules):
 *
 * <ol>
 *   <li>Filter V + A scored segs. V empty → INCONCLUSIVE(NO_COMPLETED_V_SEG).
 *   <li>TRACK_SWITCH guard: trigger is init.mp4 / index.mpd (chunkDurationMs ≤ 0 +
 *       bytesLoaded &gt; 0). INCONCLUSIVE(TRACK_SWITCH).
 *   <li>Sanity gate (V1 reuse, V-only ΔSupply): demand_ratio in [0.7, 1.3].
 *       INCONCLUSIVE(SANITY_FAIL) on failure.
 *   <li>Cold-start: getTtfbFence(vKey) == null → INCONCLUSIVE(COLD_START).
 *   <li>Per-V evidence (V6 unchanged): drained + ttfbExtreme(K=3) + bodyHealthy(≥0.90 ×
 *       bitrate) + !mtpCollapsed → V CDN evidence.
 *   <li>Per-A evidence (V7 unchanged): drained + ttfbExtreme(K=3 on audioFence) +
 *       bodyHealthy(≥0.90 × audioBitrate). No mtp guard.
 *   <li>Merge: nDrained = nVD + nAD; nCdnEv = nVCE + nACE; gate {@code nCdnEv × 2 ≥
 *       nDrained} → CDN/NETWORK. nDrained = 0 → INCONCLUSIVE(NO_DRAIN).
 * </ol>
 *
 * <p>New V8 fields ({@code nVAbrLag}, drain peak, per-condition counts, audio retries,
 * baseline quartiles) are set to defaults (0 / -1 / 0.0) in this B1 scaffold. Subsequent
 * tasks B2–B6 populate them.
 *
 * <p>Cross-validation: for any input, V8 must produce the same verdict (cause/reason) as V7.
 */
@UnstableApi
public final class QoSDiagnoserV8 {

  private QoSDiagnoserV8() {}

  // Constants copied EXACTLY from V7.
  /** Per-segment slowness threshold: {@code excessRatio > 0.10} = drained. */
  static final double SLOW_RATIO = 0.10;

  /** Majority gate: {@code nCdnEvidence × DENOM ≥ nDrained × NUM} (≥50%). */
  static final int CDN_MAJORITY_NUM = 1;

  static final int CDN_MAJORITY_DENOM = 2;

  /** Strict Tukey multiplier (Tukey 1977 outer fence). Applied per-track on TTFB. */
  static final double TTFB_STRICT_TUKEY_K = 3.0;

  /** Body-delivery healthy threshold (90% of bitrate). Same for V and A. */
  static final double BODY_HEALTHY_RATIO = 0.90;

  /** Bitrate-to-mtp ratio threshold for ABR (Adaptive Bitrate) over-estimate detection (CMCD-informed). */
  static final double ABR_LAG_RATIO = 0.8;

  public static DiagnosisV8 diagnose(
      @Nullable RebufferGroup group, @Nullable SessionStatistics sessionStats) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return DiagnosisV8.inconclusive(DiagnosisV8.Reason.EMPTY_GROUP, new int[0]);
    }

    int[] httpErrorCodes = collectHttpErrorCodes(group.entries);

    // Step 1: filter V + A completed scored segments.
    List<QoSInfo> vSegs = new ArrayList<>();
    List<QoSInfo> aSegs = new ArrayList<>();
    for (QoSInfo s : group.entries) {
      if (QoSDiagnoserV5.isCompletedScoredSegment(s)) {
        vSegs.add(s);
      } else if (QoSDiagnoserV5.isCompletedAudioSegment(s)) {
        aSegs.add(s);
      }
    }
    if (vSegs.isEmpty()) {
      return DiagnosisV8.inconclusive(DiagnosisV8.Reason.NO_COMPLETED_V_SEG, httpErrorCodes);
    }

    // Step 1.4: track-switch / manifest-refresh guard (V-side trigger only).
    if (group.trigger != null
        && group.trigger.chunkDurationMs <= 0L
        && group.trigger.bytesLoaded > 0L) {
      return DiagnosisV8.inconclusive(DiagnosisV8.Reason.TRACK_SWITCH, httpErrorCodes);
    }

    // Step 1.5: sanity gate (V-only ΔSupply, V1 reuse).
    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoser.applySanityG1ate(metrics);
    if (sanityFail != null) {
      return DiagnosisV8.inconclusive(
          DiagnosisV8.Reason.SANITY_FAIL, sanityFail, httpErrorCodes, /* nRetries= */ 0);
    }

    // Step 2: cold-start check via Tukey fence on V key.
    QoSInfo lastV = vSegs.get(vSegs.size() - 1);
    String vKey = QoSDiagnoserV5.sessionKey(lastV);
    SessionStatistics.TukeyFence ttfbFence =
        sessionStats == null ? null : sessionStats.getTtfbFence(vKey);
    if (ttfbFence == null) {
      return DiagnosisV8.inconclusive(DiagnosisV8.Reason.COLD_START, httpErrorCodes);
    }
    SessionStatistics.TukeyFence mtpFence = sessionStats.getMtpFence(vKey);

    int strictTtfbUpperV =
        ttfbFence.q3 + (int) Math.round(TTFB_STRICT_TUKEY_K * ttfbFence.iqr);

    // Step 3: per-V evidence.
    int nV = vSegs.size();
    int nVDrained = 0;
    int nVCdnEvidence = 0;
    int nRetries = 0;
    int nVAbrLag = 0;
    int nVAbrLagInDrain = 0;
    int nVAbrLagInCdn = 0;
    for (QoSInfo s : vSegs) {
      if (s.retryCount > 0) nRetries++;
      // abr-lag = segment requested at a bitrate exceeding 80% of measured throughput.
      // Applies to ALL V scored segs regardless of drain status.
      boolean isAbrLag =
          s.measuredThroughputKbps > 0 && s.bitrateKbps > s.measuredThroughputKbps * ABR_LAG_RATIO;
      if (isAbrLag) nVAbrLag++;
      double excessRatio =
          (double) Math.max(0L, s.loadDurationMs - s.chunkDurationMs) / s.chunkDurationMs;
      boolean isDrained = excessRatio > SLOW_RATIO;
      if (!isDrained) continue;
      nVDrained++;
      if (isAbrLag) nVAbrLagInDrain++;
      boolean isTtfbExtreme = s.ttfbMs > strictTtfbUpperV;
      boolean isMtpCollapsed =
          mtpFence != null
              && s.measuredThroughputKbps > 0
              && s.measuredThroughputKbps < mtpFence.lowerFence;
      boolean isBodyHealthy = false;
      if (s.bitrateKbps > 0
          && s.ttfbMs >= 0
          && s.bytesLoaded > 0
          && s.loadDurationMs > s.ttfbMs) {
        long transferMs = s.loadDurationMs - s.ttfbMs;
        double postTtfbKbps = (s.bytesLoaded * 8.0) / transferMs;
        isBodyHealthy = postTtfbKbps >= s.bitrateKbps * BODY_HEALTHY_RATIO;
      }
      if (isTtfbExtreme && !isMtpCollapsed && isBodyHealthy) {
        nVCdnEvidence++;
        if (isAbrLag) nVAbrLagInCdn++;
      }
    }

    // Step 3.5: per-A evidence. Audio fence cold-start → A side silently skipped.
    int nA = aSegs.size();
    int nADrained = 0;
    int nACdnEvidence = 0;
    int strictTtfbUpperA = -1;
    SessionStatistics.TukeyFence audioTtfbFence =
        aSegs.isEmpty() ? null : sessionStats.getAudioTtfbFence(vKey);
    if (audioTtfbFence != null) {
      strictTtfbUpperA =
          audioTtfbFence.q3 + (int) Math.round(TTFB_STRICT_TUKEY_K * audioTtfbFence.iqr);
      for (QoSInfo s : aSegs) {
        double excessRatio =
            (double) Math.max(0L, s.loadDurationMs - s.chunkDurationMs) / s.chunkDurationMs;
        boolean isDrained = excessRatio > SLOW_RATIO;
        if (!isDrained) continue;
        nADrained++;
        boolean isTtfbExtreme = s.ttfbMs > strictTtfbUpperA;
        boolean isBodyHealthy = false;
        if (s.bitrateKbps > 0
            && s.ttfbMs >= 0
            && s.bytesLoaded > 0
            && s.loadDurationMs > s.ttfbMs) {
          long transferMs = s.loadDurationMs - s.ttfbMs;
          double postTtfbKbps = (s.bytesLoaded * 8.0) / transferMs;
          isBodyHealthy = postTtfbKbps >= s.bitrateKbps * BODY_HEALTHY_RATIO;
        }
        if (isTtfbExtreme && isBodyHealthy) {
          nACdnEvidence++;
        }
      }
    }

    // Step 4: classify on combined V+A counts.
    int nDrainedTotal = nVDrained + nADrained;
    int nCdnEvTotal = nVCdnEvidence + nACdnEvidence;
    if (nDrainedTotal == 0) {
      return DiagnosisV8.inconclusive(
          DiagnosisV8.Reason.NO_DRAIN, httpErrorCodes, nRetries);
    }
    DiagnosisV8.Cause cause =
        (nCdnEvTotal * CDN_MAJORITY_DENOM >= nDrainedTotal * CDN_MAJORITY_NUM)
            ? DiagnosisV8.Cause.CDN
            : DiagnosisV8.Cause.NETWORK;

    String cohortNet = QoSDiagnoserV5.networkTypeStr(lastV.networkType);
    String cohortCdn =
        (lastV.cdnProvider != null && !lastV.cdnProvider.isEmpty()) ? lastV.cdnProvider : "UNKNOWN";

    // Step 5: attach metadata. V8 new fields — DEFAULTS for B1, populated in B2..B6.
    return DiagnosisV8.classified(
        cause,
        httpErrorCodes,
        nV,
        nA,
        nVDrained,
        nADrained,
        nVCdnEvidence,
        nACdnEvidence,
        strictTtfbUpperV,
        strictTtfbUpperA,
        nRetries,
        cohortNet,
        cohortCdn,
        /* nVAbrLag= */ nVAbrLag,
        /* nVAbrLagInCdn= */ nVAbrLagInCdn,
        /* nVAbrLagInDrain= */ nVAbrLagInDrain,
        /* nVTtfbExtreme= */ 0,
        /* nATtfbExtreme= */ 0,
        /* nVBodyUnhealthy= */ 0,
        /* nABodyUnhealthy= */ 0,
        /* nVMtpCollapsed= */ 0,
        /* drainPeakRatioV= */ 0.0,
        /* drainPeakSegIdxV= */ -1,
        /* drainPeakRatioA= */ 0.0,
        /* drainPeakSegIdxA= */ -1,
        /* nARetries= */ 0,
        /* ttfbQ1V= */ -1,
        /* ttfbMedianV= */ -1,
        /* ttfbQ3V= */ -1,
        /* ttfbQ1A= */ -1,
        /* ttfbMedianA= */ -1,
        /* ttfbQ3A= */ -1);
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
