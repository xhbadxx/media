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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.exoplayer.qos.model.DiagnosisV2;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class QoSDiagnoserV2Test {

  @Test
  public void diagnose_nullGroup_returnsTransient() {
    DiagnosisV2 result = QoSDiagnoserV2.diagnose(null);
    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.TRANSIENT);
  }

  @Test
  public void diagnose_demandRatio_belowGate_returnsTransientWithReason() {
    // Build a window where demand_ratio = 0.4 (player paused most of the time).
    // wallTime = 10000ms, ΣcDur = 4000ms, Δbuffer = 0ms → ΔDemand = 4000ms → ratio = 0.4.
    QoSInfo s1 =
        scoredSegment(
            /* ts= */ 1_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 500L,
            /* ttfbMs= */ 100,
            /* blMs= */ 5_000);
    QoSInfo s2 =
        scoredSegment(
            /* ts= */ 5_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 500L,
            /* ttfbMs= */ 100,
            /* blMs= */ 5_000);
    QoSInfo trigger = triggerSegment(/* ts= */ 11_000L, /* blMs= */ 5_000);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(s1, s2, trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.TRANSIENT);
    assertThat(result.sanityFailReason).contains("demand_ratio");
  }

  @Test
  public void diagnose_demandRatio_aboveGate_returnsTransientWithReason() {
    // demand_ratio = 2.0 (player at 2× speed): wallTime = 5000ms, ΣcDur = 2000ms,
    // Δbuffer = -8000ms → ΔDemand = 10000ms → ratio = 2.0.
    QoSInfo s1 =
        scoredSegment(
            /* ts= */ 1_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 500L,
            /* ttfbMs= */ 100,
            /* blMs= */ 9_000);
    QoSInfo trigger = triggerSegment(/* ts= */ 6_000L, /* blMs= */ 1_000);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(s1, trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.TRANSIENT);
    assertThat(result.sanityFailReason).contains("demand_ratio");
  }

  @Test
  public void diagnose_degenerateWindow_returnsTransientWithReason() {
    QoSInfo trigger = triggerSegment(/* ts= */ 1_000L, /* blMs= */ 1_000);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.TRANSIENT);
    assertThat(result.sanityFailReason).contains("wall_time");
  }

  // ===== decompose helper =====

  @Test
  public void decompose_healthySegment_allZeros() {
    QoSInfo seg =
        scoredSegment(
            /* ts= */ 0L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 1_500L,
            /* ttfbMs= */ 200,
            /* blMs= */ 5_000);
    QoSDiagnoserV2.SegmentShare s = QoSDiagnoserV2.decompose(seg);
    assertThat(s.excessMs).isEqualTo(0L);
    assertThat(s.serverShareMs).isEqualTo(0L);
    assertThat(s.clientShareMs).isEqualTo(0L);
  }

  @Test
  public void decompose_ttfbDominantExcess_serverGetsAll() {
    // loadDur=4000, cdur=2000 → excess=2000. ttfb=3500 ≥ excess → server_share=2000, client=0.
    QoSInfo seg =
        scoredSegment(
            /* ts= */ 0L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 4_000L,
            /* ttfbMs= */ 3_500,
            /* blMs= */ 5_000);
    QoSDiagnoserV2.SegmentShare s = QoSDiagnoserV2.decompose(seg);
    assertThat(s.excessMs).isEqualTo(2_000L);
    assertThat(s.serverShareMs).isEqualTo(2_000L);
    assertThat(s.clientShareMs).isEqualTo(0L);
  }

  @Test
  public void decompose_transferDominantExcess_clientGetsRemainder() {
    // loadDur=5000, cdur=2000 → excess=3000. ttfb=1000 < excess → server_share=1000, client=2000.
    QoSInfo seg =
        scoredSegment(
            /* ts= */ 0L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 5_000L,
            /* ttfbMs= */ 1_000,
            /* blMs= */ 5_000);
    QoSDiagnoserV2.SegmentShare s = QoSDiagnoserV2.decompose(seg);
    assertThat(s.excessMs).isEqualTo(3_000L);
    assertThat(s.serverShareMs).isEqualTo(1_000L);
    assertThat(s.clientShareMs).isEqualTo(2_000L);
  }

  @Test
  public void decompose_zeroTtfb_allClient() {
    // ttfb=0 → server_share=0, client=excess.
    QoSInfo seg =
        scoredSegment(
            /* ts= */ 0L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 3_500L,
            /* ttfbMs= */ 0,
            /* blMs= */ 5_000);
    QoSDiagnoserV2.SegmentShare s = QoSDiagnoserV2.decompose(seg);
    assertThat(s.excessMs).isEqualTo(1_500L);
    assertThat(s.serverShareMs).isEqualTo(0L);
    assertThat(s.clientShareMs).isEqualTo(1_500L);
  }

  @Test
  public void decompose_negativeTtfbSentinel_treatedAsZero() {
    // QoSInfo.ttfbMs=-1 sentinel (capture missed). Treat as 0 → all excess goes to client.
    QoSInfo seg =
        scoredSegment(
            /* ts= */ 0L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 3_000L,
            /* ttfbMs= */ -1,
            /* blMs= */ 5_000);
    QoSDiagnoserV2.SegmentShare s = QoSDiagnoserV2.decompose(seg);
    assertThat(s.excessMs).isEqualTo(1_000L);
    assertThat(s.serverShareMs).isEqualTo(0L);
    assertThat(s.clientShareMs).isEqualTo(1_000L);
  }

  // ===== significance gate (TRANSIENT no drain) =====

  @Test
  public void diagnose_allHealthySegments_returnsTransientNoDrain() {
    // 3 segments all with loadDur ≤ cdur → no excess → TRANSIENT (no drain) even though gate passes.
    // Per Task 1 sanity-gate math: wallTime=6000ms, ΣcDur=6000ms, Δbuffer=500ms → ΔDemand=5500ms,
    // ratio=0.92 → PASSES sanity gate (within [0.7, 1.3]).
    QoSInfo s1 =
        scoredSegment(
            /* ts= */ 1_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 1_500L,
            /* ttfbMs= */ 200,
            /* blMs= */ 6_000);
    QoSInfo s2 =
        scoredSegment(
            /* ts= */ 3_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 1_800L,
            /* ttfbMs= */ 200,
            /* blMs= */ 6_200);
    QoSInfo s3 =
        scoredSegment(
            /* ts= */ 5_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 1_700L,
            /* ttfbMs= */ 200,
            /* blMs= */ 6_500);
    QoSInfo trigger = triggerSegment(/* ts= */ 7_000L, /* blMs= */ 6_500);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(s1, s2, s3, trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.TRANSIENT);
    // Gate passed (no sanityFailReason), TRANSIENT came from "no observable drain" path.
    assertThat(result.sanityFailReason).isNull();
    assertThat(result.totalDrainMs).isEqualTo(0L);
  }

  // ===== Server vs Client classification =====

  @Test
  public void diagnose_continuousTtfbDominant_returnsCdnSlow() {
    // 4 segments, each loadDur > cdur with TTFB eating the entire excess.
    // server_share dominates → CDN_SLOW.
    QoSInfo s1 =
        scoredSegment(
            /* ts= */ 1_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 2_800L,
            /* ttfbMs= */ 2_100,
            /* blMs= */ 9_000);
    QoSInfo s2 =
        scoredSegment(
            /* ts= */ 3_800L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 3_200L,
            /* ttfbMs= */ 2_400,
            /* blMs= */ 6_000);
    QoSInfo s3 =
        scoredSegment(
            /* ts= */ 7_000L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 3_500L,
            /* ttfbMs= */ 2_800,
            /* blMs= */ 3_500);
    QoSInfo s4 =
        scoredSegment(
            /* ts= */ 10_500L,
            /* cdurMs= */ 2_000L,
            /* loadDurMs= */ 4_500L,
            /* ttfbMs= */ 3_500,
            /* blMs= */ 1_000);
    QoSInfo trigger = triggerSegment(/* ts= */ 15_000L, /* blMs= */ 0);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(s1, s2, s3, s4, trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.CDN_SLOW);
    assertThat(result.serverDrainMs).isGreaterThan(result.clientDrainMs);
    // Decomposition: each segment excess = loadDur−cdur ∈ {800, 1200, 1500, 2500} = 6000 total.
    // ttfb ≥ excess on all 4 → server gets all 6000.
    assertThat(result.totalDrainMs).isEqualTo(6_000L);
    assertThat(result.serverDrainMs).isEqualTo(6_000L);
    assertThat(result.clientDrainMs).isEqualTo(0L);
  }

  // ===== Client sub-classify (ABR vs Weak Net) =====

  @Test
  public void diagnose_clientDrainAbrAggressive_returnsClientAbr() {
    // 3 segments, transfer-dominant (low ttfb), bitrate=4000Kbps but mtp=2000Kbps each.
    // ABR chose above measured throughput → CLIENT_ABR.
    QoSInfo s1 =
        clientSegment(
            /* ts= */ 1_000L,
            /* loadDurMs= */ 5_000L,
            /* ttfbMs= */ 500,
            /* bitrateKbps= */ 4_000,
            /* mtpKbps= */ 2_000,
            /* blMs= */ 9_000);
    QoSInfo s2 =
        clientSegment(
            /* ts= */ 6_000L,
            /* loadDurMs= */ 5_500L,
            /* ttfbMs= */ 600,
            /* bitrateKbps= */ 4_000,
            /* mtpKbps= */ 2_000,
            /* blMs= */ 5_500);
    QoSInfo s3 =
        clientSegment(
            /* ts= */ 11_500L,
            /* loadDurMs= */ 6_000L,
            /* ttfbMs= */ 700,
            /* bitrateKbps= */ 4_000,
            /* mtpKbps= */ 2_000,
            /* blMs= */ 2_000);
    QoSInfo trigger = triggerSegment(/* ts= */ 17_500L, /* blMs= */ 0);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(s1, s2, s3, trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.CLIENT_ABR);
    assertThat(result.countAbrAggressive).isEqualTo(3);
    assertThat(result.countClientDrain).isEqualTo(3);
  }

  @Test
  public void diagnose_clientDrainPipeWithinMtp_returnsClientWeakNet() {
    // 3 segments transfer-dominant, but bitrate=2000 ≤ mtp=4000 each.
    // ABR was conservative — pipe degraded after decision → CLIENT_WEAK_NET.
    QoSInfo s1 =
        clientSegment(
            /* ts= */ 1_000L,
            /* loadDurMs= */ 5_000L,
            /* ttfbMs= */ 500,
            /* bitrateKbps= */ 2_000,
            /* mtpKbps= */ 4_000,
            /* blMs= */ 9_000);
    QoSInfo s2 =
        clientSegment(
            /* ts= */ 6_000L,
            /* loadDurMs= */ 5_500L,
            /* ttfbMs= */ 600,
            /* bitrateKbps= */ 2_000,
            /* mtpKbps= */ 4_000,
            /* blMs= */ 5_500);
    QoSInfo s3 =
        clientSegment(
            /* ts= */ 11_500L,
            /* loadDurMs= */ 6_000L,
            /* ttfbMs= */ 700,
            /* bitrateKbps= */ 2_000,
            /* mtpKbps= */ 4_000,
            /* blMs= */ 2_000);
    QoSInfo trigger = triggerSegment(/* ts= */ 17_500L, /* blMs= */ 0);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(s1, s2, s3, trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.CLIENT_WEAK_NET);
    assertThat(result.countAbrAggressive).isEqualTo(0);
    assertThat(result.countClientDrain).isEqualTo(3);
  }

  @Test
  public void diagnose_mixedClientDrainMajorityAbr_returnsClientAbr() {
    // 3 client-drain segments: 2 with bitrate>mtp, 1 with bitrate<=mtp.
    // 2 × count_aggressive (4) ≥ count_total (3) → CLIENT_ABR.
    QoSInfo s1 =
        clientSegment(
            /* ts= */ 1_000L,
            /* loadDurMs= */ 5_000L,
            /* ttfbMs= */ 500,
            /* bitrateKbps= */ 4_000,
            /* mtpKbps= */ 2_000,
            /* blMs= */ 9_000);  // aggressive
    QoSInfo s2 =
        clientSegment(
            /* ts= */ 6_000L,
            /* loadDurMs= */ 5_500L,
            /* ttfbMs= */ 600,
            /* bitrateKbps= */ 4_000,
            /* mtpKbps= */ 2_000,
            /* blMs= */ 5_500);  // aggressive
    QoSInfo s3 =
        clientSegment(
            /* ts= */ 11_500L,
            /* loadDurMs= */ 6_000L,
            /* ttfbMs= */ 700,
            /* bitrateKbps= */ 2_000,
            /* mtpKbps= */ 4_000,
            /* blMs= */ 2_000);  // not aggressive
    QoSInfo trigger = triggerSegment(/* ts= */ 17_500L, /* blMs= */ 0);
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ trigger.timestampMs,
            /* trigger= */ trigger,
            /* entries= */ Arrays.asList(s1, s2, s3, trigger),
            /* diagnosis= */ null);

    DiagnosisV2 result = QoSDiagnoserV2.diagnose(group);

    assertThat(result.cause).isEqualTo(DiagnosisV2.Cause.CLIENT_ABR);
    assertThat(result.countAbrAggressive).isEqualTo(2);
    assertThat(result.countClientDrain).isEqualTo(3);
  }

  // Test fixture builders — keep flat & explicit per V1 test convention.
  private static QoSInfo scoredSegment(
      long ts, long cdurMs, long loadDurMs, int ttfbMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(cdurMs)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBufferedDurationMs(blMs)
        .setBytesLoaded(500_000L)
        .setBitrateKbps(2_000)
        .setMeasuredThroughputKbps(5_000)
        .build();
  }

  /** Trigger entry — no chunkDurationMs by design so it does not contribute to ΔSupply. */
  private static QoSInfo triggerSegment(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBufferedDurationMs(blMs)
        .setBufferStarvationFlag(true)
        .build();
  }

  /** Client-bound test fixture: explicit bitrate + mtp control for client tests. */
  private static QoSInfo clientSegment(
      long ts,
      long loadDurMs,
      int ttfbMs,
      int bitrateKbps,
      int mtpKbps,
      int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBufferedDurationMs(blMs)
        .setBytesLoaded(loadDurMs * bitrateKbps / 8L)   // approx for postTtfb math; not asserted
        .setBitrateKbps(bitrateKbps)
        .setMeasuredThroughputKbps(mtpKbps)
        .build();
  }
}
