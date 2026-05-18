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
import androidx.media3.exoplayer.qos.model.Diagnosis;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function rebuffer diagnoser — 3-cause classifier (CDN / NETWORK / INCONCLUSIVE) với 33
 * evidence fields per event for UI/analyst manual reasoning.
 *
 * <p>Cascade (7 steps):
 *
 * <ol>
 *   <li>Filter V + A scored segs. V empty → INCONCLUSIVE(NO_COMPLETED_V_SEG).
 *   <li>TRACK_SWITCH guard: trigger là init.mp4 / index.mpd (chunkDurationMs ≤ 0 +
 *       bytesLoaded &gt; 0). INCONCLUSIVE(TRACK_SWITCH).
 *   <li>Sanity gate (V-only ΔSupply): demand_ratio ∈ [0.7, 1.3]. INCONCLUSIVE(SANITY_FAIL) khi
 *       fail, detail string carries reason.
 *   <li>Cold-start: getTtfbFence(vKey) == null → INCONCLUSIVE(COLD_START).
 *   <li>Per-V evidence: drained + ttfbExtreme(K=3) + bodyHealthy(≥0.90 × bitrate) +
 *       !mtpCollapsed → V CDN evidence.
 *   <li>Per-A evidence: drained + ttfbExtreme(K=3 on audioFence) + bodyHealthy(≥0.90 ×
 *       audioBitrate). No mtp guard (audio không có BandwidthMeter measurement).
 *   <li>Merge: nDrained = nVD + nAD; nCdnEv = nVCE + nACE; gate {@code nCdnEv × 2 ≥ nDrained}
 *       → CDN/NETWORK. nDrained = 0 → INCONCLUSIVE(NO_DRAIN).
 * </ol>
 *
 * <p>Constants: SLOW_RATIO=0.10, BODY_HEALTHY_RATIO=0.90, TUKEY_K=3, ABR_LAG_RATIO=0.80,
 * majority gate ≥50%.
 *
 * <p>Evidence fields ({@code nVAbrLag}, drain peak, per-condition counts, audio retries,
 * baseline quartiles) populated alongside verdict — không tham gia classification logic.
 */
@UnstableApi
public final class QoSDiagnoser {

  private QoSDiagnoser() {}

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

  public static Diagnosis diagnose(
      @Nullable RebufferGroup group, @Nullable SessionStatistics sessionStats) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return Diagnosis.inconclusive(Diagnosis.Reason.EMPTY_GROUP, new int[0]);
    }

    int[] httpErrorCodes = collectHttpErrorCodes(group.entries);

    // Step 1: filter V + A completed scored segments.
    List<QoSInfo> vSegs = new ArrayList<>();
    List<QoSInfo> aSegs = new ArrayList<>();
    for (QoSInfo s : group.entries) {
      if (QoSDiagnoserUtils.isCompletedScoredSegment(s)) {
        vSegs.add(s);
      } else if (QoSDiagnoserUtils.isCompletedAudioSegment(s)) {
        aSegs.add(s);
      }
    }
    if (vSegs.isEmpty()) {
      return Diagnosis.inconclusive(
          Diagnosis.Reason.NO_COMPLETED_V_SEG, /* detail= */ null, httpErrorCodes,
          /* nRetries= */ 0, countAudioRetries(aSegs));
    }

    // Step 1.4: track-switch / manifest-refresh guard (V-side trigger only).
    if (group.trigger != null
        && group.trigger.chunkDurationMs <= 0L
        && group.trigger.bytesLoaded > 0L) {
      return Diagnosis.inconclusive(
          Diagnosis.Reason.TRACK_SWITCH, /* detail= */ null, httpErrorCodes,
          /* nRetries= */ 0, countAudioRetries(aSegs));
    }

    // Step 1.5: sanity gate (V-only ΔSupply, V1 reuse).
    QoSDiagnoserUtils.WindowMetrics metrics = QoSDiagnoserUtils.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoserUtils.applySanityGate(metrics);
    if (sanityFail != null) {
      return Diagnosis.inconclusive(
          Diagnosis.Reason.SANITY_FAIL, sanityFail, httpErrorCodes,
          /* nRetries= */ 0, countAudioRetries(aSegs));
    }

    // Step 2: cold-start check via Tukey fence on V key.
    QoSInfo lastV = vSegs.get(vSegs.size() - 1);
    String vKey = QoSDiagnoserUtils.sessionKey(lastV);
    SessionStatistics.TukeyFence ttfbFence =
        sessionStats == null ? null : sessionStats.getTtfbFence(vKey);
    if (ttfbFence == null) {
      return Diagnosis.inconclusive(
          Diagnosis.Reason.COLD_START, /* detail= */ null, httpErrorCodes,
          /* nRetries= */ 0, countAudioRetries(aSegs));
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
    // B3: per-condition counters — observational only, do not affect verdict.
    int nVTtfbExtreme = 0;
    int nVBodyUnhealthy = 0;
    int nVMtpCollapsed = 0;
    // B4: drain peak — max excessRatio across V drained segs + its 0-based index in vSegs.
    // drainPeakSegIdxV is a 0-based index into vSegs (the scored+completed V segments list
    // passed into this method). The counter vSegIdx increments for every seg — drained or not —
    // so index 2 means the 3rd element of vSegs regardless of drain status. Tie-break: strict >
    // keeps the first occurrence (first drained seg wins if excessRatio is equal).
    double drainPeakRatioV = 0.0;
    int drainPeakSegIdxV = -1;
    int vSegIdx = -1;
    for (QoSInfo s : vSegs) {
      vSegIdx++;
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
      if (excessRatio > drainPeakRatioV) {
        drainPeakRatioV = excessRatio;
        drainPeakSegIdxV = vSegIdx;
      }
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
      // B3: increment independent per-condition counters (overlapping by design).
      if (isTtfbExtreme) nVTtfbExtreme++;
      if (!isBodyHealthy) nVBodyUnhealthy++;
      if (isMtpCollapsed) nVMtpCollapsed++;
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
    // B3: per-condition counters for A (no nAMtpCollapsed — audio has no mtp).
    int nATtfbExtreme = 0;
    int nABodyUnhealthy = 0;
    // B4: drain peak for A — same semantic as V: 0-based index into aSegs, advances for all segs.
    double drainPeakRatioA = 0.0;
    int drainPeakSegIdxA = -1;
    SessionStatistics.TukeyFence audioTtfbFence =
        aSegs.isEmpty() ? null : sessionStats.getAudioTtfbFence(vKey);
    if (audioTtfbFence != null) {
      strictTtfbUpperA =
          audioTtfbFence.q3 + (int) Math.round(TTFB_STRICT_TUKEY_K * audioTtfbFence.iqr);
      int aSegIdx = -1;
      for (QoSInfo s : aSegs) {
        aSegIdx++;
        double excessRatio =
            (double) Math.max(0L, s.loadDurationMs - s.chunkDurationMs) / s.chunkDurationMs;
        boolean isDrained = excessRatio > SLOW_RATIO;
        if (!isDrained) continue;
        nADrained++;
        if (excessRatio > drainPeakRatioA) {
          drainPeakRatioA = excessRatio;
          drainPeakSegIdxA = aSegIdx;
        }
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
        // B3: increment independent per-condition counters for A.
        if (isTtfbExtreme) nATtfbExtreme++;
        if (!isBodyHealthy) nABodyUnhealthy++;
        if (isTtfbExtreme && isBodyHealthy) {
          nACdnEvidence++;
        }
      }
    }

    // Step 4: classify on combined V+A counts.
    int nDrainedTotal = nVDrained + nADrained;
    int nCdnEvTotal = nVCdnEvidence + nACdnEvidence;
    if (nDrainedTotal == 0) {
      return Diagnosis.inconclusive(
          Diagnosis.Reason.NO_DRAIN, /* detail= */ null, httpErrorCodes,
          nRetries, countAudioRetries(aSegs));
    }
    Diagnosis.Cause cause =
        (nCdnEvTotal * CDN_MAJORITY_DENOM >= nDrainedTotal * CDN_MAJORITY_NUM)
            ? Diagnosis.Cause.CDN
            : Diagnosis.Cause.NETWORK;

    String cohortNet = QoSDiagnoserUtils.networkTypeStr(lastV.networkType);
    String cohortCdn =
        (lastV.cdnProvider != null && !lastV.cdnProvider.isEmpty()) ? lastV.cdnProvider : "UNKNOWN";

    // Step 5: attach metadata. V8 new fields — DEFAULTS for B1, populated in B2..B6.
    int nARetries = countAudioRetries(aSegs);
    return Diagnosis.classified(
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
        /* nVTtfbExtreme= */ nVTtfbExtreme,
        /* nATtfbExtreme= */ nATtfbExtreme,
        /* nVBodyUnhealthy= */ nVBodyUnhealthy,
        /* nABodyUnhealthy= */ nABodyUnhealthy,
        /* nVMtpCollapsed= */ nVMtpCollapsed,
        /* drainPeakRatioV= */ drainPeakRatioV,
        /* drainPeakSegIdxV= */ drainPeakSegIdxV,
        /* drainPeakRatioA= */ drainPeakRatioA,
        /* drainPeakSegIdxA= */ drainPeakSegIdxA,
        /* nARetries= */ nARetries,
        /* ttfbQ1V= */ ttfbFence.q1,
        /* ttfbMedianV= */ ttfbFence.median,
        /* ttfbQ3V= */ ttfbFence.q3,
        /* ttfbQ1A= */ audioTtfbFence != null ? audioTtfbFence.q1 : -1,
        /* ttfbMedianA= */ audioTtfbFence != null ? audioTtfbFence.median : -1,
        /* ttfbQ3A= */ audioTtfbFence != null ? audioTtfbFence.q3 : -1);
  }

  /** Count audio segments with at least one retry (retryCount &gt; 0). */
  private static int countAudioRetries(List<QoSInfo> aSegs) {
    int n = 0;
    for (QoSInfo a : aSegs) {
      if (a.retryCount > 0) n++;
    }
    return n;
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
