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

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qos.model.DiagnosisV2;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;

/**
 * Pure-function rebuffer diagnoser implementing the buffer-conservation
 * algorithm V2. Outputs one of 4 {@link DiagnosisV2.Cause} values via
 * per-segment phase decomposition and majority share comparison — no magic
 * thresholds outside the sanity gate.
 *
 * <p>Sibling to {@link QoSDiagnoser} (V1). V1 stays untouched; both run in
 * parallel via {@code FPlayQoSMonitor} for cross-validation.
 */
@UnstableApi
public final class QoSDiagnoserV2 {

  private QoSDiagnoserV2() {}

  /** Per-segment phase decomposition result. */
  public static final class SegmentShare {
    public final long excessMs;
    public final long serverShareMs;
    public final long clientShareMs;

    SegmentShare(long excessMs, long serverShareMs, long clientShareMs) {
      this.excessMs = excessMs;
      this.serverShareMs = serverShareMs;
      this.clientShareMs = clientShareMs;
    }
  }

  /**
   * Decomposes a single segment's excess load time into server (TTFB) and
   * client (transfer) shares per Step 2 of the algorithm (Spec §IV.2).
   *
   * <p>{@code excess = max(0, loadDur − cdur)}. Server gets credit up to
   * {@code ttfb} (treating the {@code -1} sentinel as 0); the rest goes to
   * the client transfer phase.
   */
  public static SegmentShare decompose(QoSInfo seg) {
    if (seg == null) {
      return new SegmentShare(0L, 0L, 0L);
    }
    long loadDur = seg.loadDurationMs;
    long cdur = seg.chunkDurationMs;
    if (loadDur <= 0L || cdur <= 0L) {
      return new SegmentShare(0L, 0L, 0L);
    }
    long excess = Math.max(0L, loadDur - cdur);
    long ttfb = Math.max(0L, (long) seg.ttfbMs);
    long serverShare = Math.min(excess, ttfb);
    long clientShare = excess - serverShare;
    return new SegmentShare(excess, serverShare, clientShare);
  }

  /**
   * Diagnoses {@code group} and returns a single {@link DiagnosisV2}. Returns
   * {@link DiagnosisV2.Cause#TRANSIENT} for null/empty groups (defensive).
   */
  public static DiagnosisV2 diagnose(RebufferGroup group) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return DiagnosisV2.transientNoDrain();
    }

    // Step 1 — Sanity gate (Spec §IV.1, reuses V1's pure functions).
    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoser.applySanityG1ate(metrics);
    if (sanityFail != null) {
      return DiagnosisV2.transientFromSanity(sanityFail);
    }

    // Step 2 + Step 3 — Phase decomposition (Spec §IV.2) + significance check.
    long totalDrain = 0L;
    long serverDrain = 0L;
    long clientDrain = 0L;
    for (QoSInfo entry : group.entries) {
      if (!isCompletedScoredSegment(entry)) {
        continue;
      }
      SegmentShare share = decompose(entry);
      totalDrain += share.excessMs;
      serverDrain += share.serverShareMs;
      clientDrain += share.clientShareMs;
    }

    if (totalDrain == 0L) {
      // No observable drain — gate passed but every segment was healthy.
      return DiagnosisV2.transientNoDrain();
    }

    // Step 4 — Server vs Client by drain magnitude majority (Spec §IV.4).
    // Tie favors server: when shares are equal, treat as CDN_SLOW (client can self-recover via ABR; server can't).
    if (serverDrain >= clientDrain) {
      return new DiagnosisV2(
          DiagnosisV2.Cause.CDN_SLOW,
          totalDrain,
          serverDrain,
          clientDrain,
          -1,
          -1,
          /* sanityFailReason= */ null);
    }

    // Step 5 — Client sub-classify (Spec §IV.4.2: ABR vs Weak Net) by ABR-aggressive count.
    int countAbrAggressive = 0;
    int countClientDrain = 0;
    for (QoSInfo entry : group.entries) {
      if (!isCompletedScoredSegment(entry)) {
        continue;
      }
      SegmentShare share = decompose(entry);
      if (share.clientShareMs > 0L) {
        countClientDrain++;
        if (entry.measuredThroughputKbps > 0
            && entry.bitrateKbps > entry.measuredThroughputKbps) {
          countAbrAggressive++;
        }
      }
    }

    // Tie favors ABR: actionable verdict (lower bitrate ladder) beats "weak net" which is unactionable from the player.
    DiagnosisV2.Cause clientCause =
        (2 * countAbrAggressive >= countClientDrain)
            ? DiagnosisV2.Cause.CLIENT_ABR
            : DiagnosisV2.Cause.CLIENT_WEAK_NET;

    return new DiagnosisV2(
        clientCause,
        totalDrain,
        serverDrain,
        clientDrain,
        countAbrAggressive,
        countClientDrain,
        /* sanityFailReason= */ null);
  }

  /**
   * Same predicate as V1's window-metrics supply filter: V/DEFAULT scored segments
   * with status COMPLETED and a positive chunk duration. Excludes manifests, init
   * segments, audio (DASH split-track), failed loads, and the trigger entry
   * (which has no chunkDurationMs by construction).
   */
  private static boolean isCompletedScoredSegment(QoSInfo e) {
    if (e == null) {
      return false;
    }
    if (e.status != QoSInfo.LoadStatus.COMPLETED) {
      return false;
    }
    if (e.chunkDurationMs <= 0L) {
      return false;
    }
    return e.trackType == C.TRACK_TYPE_VIDEO || e.trackType == C.TRACK_TYPE_DEFAULT;
  }
}
