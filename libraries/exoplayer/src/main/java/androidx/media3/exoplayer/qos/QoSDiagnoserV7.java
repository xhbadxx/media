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
import androidx.media3.exoplayer.qos.model.DiagnosisV7;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function rebuffer diagnoser V7 — V6 + audio-track evidence (additive V+A merge).
 *
 * <p>Algorithm:
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
 *   <li>Per-A evidence (NEW, only if audioFence non-null): drained + ttfbExtreme(K=3 on
 *       audioFence) + bodyHealthy(≥0.90 × audioBitrate). No mtp guard.
 *   <li>Merge: nDrained = nVD + nAD; nCdnEv = nVCE + nACE; gate {@code nCdnEv × 2 ≥
 *       nDrained} → CDN/NETWORK. nDrained = 0 → INCONCLUSIVE(NO_DRAIN).
 * </ol>
 *
 * <p>Audio fence cold-start fallback: audio evidence silently skipped, V drives verdict.
 * See {@code spec-qos-v7-audio.md} (TBD).
 */
@UnstableApi
public final class QoSDiagnoserV7 {

  private QoSDiagnoserV7() {}

  /** Per-segment slowness threshold (V and A): {@code excessRatio > 0.10} = drained. */
  static final double SLOW_RATIO = 0.10;

  /** Majority gate: {@code nCdnEvidence × DENOM ≥ nDrained × NUM} (≥50%). */
  static final int CDN_MAJORITY_NUM = 1;

  static final int CDN_MAJORITY_DENOM = 2;

  /** Strict Tukey multiplier (Tukey 1977 outer fence). Applied per-track on TTFB. */
  static final double TTFB_STRICT_TUKEY_K = 3.0;

  /** Body-delivery healthy threshold (90% of bitrate). Same for V and A. */
  static final double BODY_HEALTHY_RATIO = 0.90;

  public static DiagnosisV7 diagnose(
      @Nullable RebufferGroup group, @Nullable SessionStatistics sessionStats) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return DiagnosisV7.inconclusive(DiagnosisV7.Reason.EMPTY_GROUP, new int[0]);
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
      return DiagnosisV7.inconclusive(DiagnosisV7.Reason.NO_COMPLETED_V_SEG, httpErrorCodes);
    }

    // Step 1.4: track-switch / manifest-refresh guard (V-side trigger only).
    if (group.trigger != null
        && group.trigger.chunkDurationMs <= 0L
        && group.trigger.bytesLoaded > 0L) {
      return DiagnosisV7.inconclusive(DiagnosisV7.Reason.TRACK_SWITCH, httpErrorCodes);
    }

    // Step 1.5: sanity gate (V-only ΔSupply, V1 reuse).
    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoser.applySanityG1ate(metrics);
    if (sanityFail != null) {
      return DiagnosisV7.inconclusive(
          DiagnosisV7.Reason.SANITY_FAIL, sanityFail, httpErrorCodes, /* nRetries= */ 0);
    }

    // Step 2: cold-start check via Tukey fence on V key.
    QoSInfo lastV = vSegs.get(vSegs.size() - 1);
    String vKey = QoSDiagnoserV5.sessionKey(lastV);
    SessionStatistics.TukeyFence ttfbFence =
        sessionStats == null ? null : sessionStats.getTtfbFence(vKey);
    if (ttfbFence == null) {
      return DiagnosisV7.inconclusive(DiagnosisV7.Reason.COLD_START, httpErrorCodes);
    }
    SessionStatistics.TukeyFence mtpFence = sessionStats.getMtpFence(vKey);

    int strictTtfbUpperV =
        ttfbFence.q3 + (int) Math.round(TTFB_STRICT_TUKEY_K * ttfbFence.iqr);

    // Step 3: per-V evidence.
    int nV = vSegs.size();
    int nVDrained = 0;
    int nVCdnEvidence = 0;
    int nRetries = 0;
    for (QoSInfo s : vSegs) {
      if (s.retryCount > 0) nRetries++;
      // isTtfbExtreme
      double excessRatio =
          (double) Math.max(0L, s.loadDurationMs - s.chunkDurationMs) / s.chunkDurationMs;
      boolean isDrained = excessRatio > SLOW_RATIO;
      if (!isDrained) continue;
      nVDrained++; // -> Get buffer
      boolean isTtfbExtreme = s.ttfbMs > strictTtfbUpperV;
      // MtpCollapsed
      boolean isMtpCollapsed =
          mtpFence != null
              && s.measuredThroughputKbps > 0
              && s.measuredThroughputKbps < mtpFence.lowerFence;
      // BodyHealthy
      boolean isBodyHealthy = false;
      if (s.bitrateKbps > 0
          && s.ttfbMs >= 0
          && s.bytesLoaded > 0
          && s.loadDurationMs > s.ttfbMs) {
        long transferMs = s.loadDurationMs - s.ttfbMs;
        double postTtfbKbps = (s.bytesLoaded * 8.0) / transferMs;
        isBodyHealthy = postTtfbKbps >= s.bitrateKbps * BODY_HEALTHY_RATIO;
      }
      // Final
      if (isTtfbExtreme && !isMtpCollapsed && isBodyHealthy) {
        nVCdnEvidence++;
      }
    }

    // Step 3.5: per-A evidence. Audio fence cold-start → A side silently skipped
    // (V drives verdict). No mtp guard on audio side (mtp = V-side ABR measurement).
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
      return DiagnosisV7.inconclusive(
          DiagnosisV7.Reason.NO_DRAIN, httpErrorCodes, nRetries);
    }
    DiagnosisV7.Cause cause =
        (nCdnEvTotal * CDN_MAJORITY_DENOM >= nDrainedTotal * CDN_MAJORITY_NUM)
            ? DiagnosisV7.Cause.CDN
            : DiagnosisV7.Cause.NETWORK;

    // Cohort labels for cross-team debug + support ticket attachment.
    String cohortNet = QoSDiagnoserV5.networkTypeStr(lastV.networkType);
    String cohortCdn =
        (lastV.cdnProvider != null && !lastV.cdnProvider.isEmpty()) ? lastV.cdnProvider : "UNKNOWN";

    // Step 5: attach metadata.
    return DiagnosisV7.classified(
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
        cohortCdn);
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
