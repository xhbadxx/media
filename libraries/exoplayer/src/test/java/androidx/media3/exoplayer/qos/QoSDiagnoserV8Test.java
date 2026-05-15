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
import androidx.media3.exoplayer.qos.model.DiagnosisV8;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for V8 — V7 cascade verbatim, new V8 fields default to 0/-1/0.0 in B1.
 *
 * <p>Session statistics fixtures identical to V7: 10 V TTFB samples all = 100 → strict K=3
 * upper = 100. V {@code chunkDur=2000ms}, so {@code loadDur > 2200} = drained.
 *
 * <p>Audio fixture: cdur=1800ms, bitrate=128kbps, bytes=28800B. Audio TTFB samples = 50
 * → strict K=3 upper = 50.
 */
@RunWith(AndroidJUnit4.class)
public class QoSDiagnoserV8Test {

  private static final String KEY_WIFI_MISS_FPT = "WIFI_MISS_fpt";

  // ===== Defensive cases =====

  @Test
  public void diagnose_nullGroup_returnsInconclusiveEmptyGroup() {
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(null, sessionStatsWarmV(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.EMPTY_GROUP);
  }

  @Test
  public void diagnose_emptyEntries_returnsInconclusiveEmptyGroup() {
    QoSInfo trigger = triggerSeg(0L, 0);
    RebufferGroup g = new RebufferGroup(1, 0L, trigger, Arrays.asList(), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, sessionStatsWarmV(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.EMPTY_GROUP);
  }

  @Test
  public void diagnose_audioOnly_returnsInconclusiveNoCompletedVSeg() {
    QoSInfo a1 = aSeg(1_000L, 1_800L, 1_800L, 50);
    QoSInfo trigger = triggerSeg(3_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 3_000L, trigger, Arrays.asList(a1, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, sessionStatsWarmFull(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.NO_COMPLETED_V_SEG);
  }

  @Test
  public void diagnose_onlyErrorSegments_returnsNoCompletedVSegWithHttpCodes() {
    QoSInfo err = errorSeg(1_000L, 503);
    QoSInfo trigger = triggerSeg(2_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 2_000L, trigger, Arrays.asList(err, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, sessionStatsWarmV(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.NO_COMPLETED_V_SEG);
    assertThat(d.httpErrorCodes).asList().containsExactly(503);
  }

  // ===== Track switch =====

  @Test
  public void diagnose_initSegmentTrigger_returnsInconclusiveTrackSwitch() {
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 80, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 80, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 80, 1_500);
    QoSInfo trigger = initTriggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.TRACK_SWITCH);
  }

  // ===== Cold-start =====

  @Test
  public void diagnose_nullSessionStats_returnsInconclusiveColdStart() {
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo trigger = triggerSeg(4_500L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 4_500L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, /* sessionStats= */ null);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.COLD_START);
  }

  @Test
  public void diagnose_lessThanMinSamples_returnsInconclusiveColdStart() {
    SessionStatistics stats = new SessionStatistics();
    for (int i = 0; i < 5; i++) {
      stats.addTtfbSample(KEY_WIFI_MISS_FPT, 100);
    }
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo trigger = triggerSeg(4_500L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 4_500L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.COLD_START);
  }

  // ===== No drain =====

  @Test
  public void diagnose_warmFenceZeroDrained_returnsInconclusiveNoDrain() {
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 1_950L, 90, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_050L, 95, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.NO_DRAIN);
  }

  // ===== Sanity gate =====

  @Test
  public void diagnose_degenerateWindow_returnsInconclusiveSanityFail() {
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo trigger = triggerSeg(1_000L, 0); // wall=0 → sanity fail
    RebufferGroup g =
        new RebufferGroup(1, 1_000L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, sessionStatsWarmFull(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.SANITY_FAIL);
    assertThat(d.reasonDetail).isNotEmpty();
  }

  // ===== V-side classification (V7 parity) =====

  @Test
  public void diagnose_vOnly_allDrainedAllTtfbOutlier_returnsCdn() {
    // V CDN: ttfb=500 (> strict K=3 fence=100) + body healthy (~1010kbps ≥ 900).
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.CDN);
    assertThat(d.nV).isEqualTo(3);
    assertThat(d.nVDrained).isEqualTo(3);
    assertThat(d.nVCdnEvidence).isEqualTo(3);
    assertThat(d.nADrained).isEqualTo(0);
    assertThat(d.nACdnEvidence).isEqualTo(0);
    assertThat(d.ttfbFenceUpperVMs).isEqualTo(100);
    assertThat(d.cohortNetworkType).isEqualTo("WIFI");
    assertThat(d.cohortCdnProvider).isEqualTo("fpt");
    // V8 new fields default in B1
    assertThat(d.nVAbrLag).isEqualTo(0);
    assertThat(d.drainPeakSegIdxV).isEqualTo(-1);
    assertThat(d.ttfbQ1V).isEqualTo(-1);
  }

  @Test
  public void diagnose_vOnly_drainedNoTtfbOutlier_returnsNetwork() {
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_500L, 60, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 4_000L, 70, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.NETWORK);
    assertThat(d.nVDrained).isEqualTo(3);
    assertThat(d.nVCdnEvidence).isEqualTo(0);
  }

  @Test
  public void diagnose_vOnly_mtpCollapsed_vetoesCdnEvidence_forcesNetwork() {
    SessionStatistics stats = new SessionStatistics();
    for (int i = 0; i < 10; i++) stats.addTtfbSample(KEY_WIFI_MISS_FPT, 100);
    int[] mtpSamples = {
        40_000, 42_000, 44_000, 46_000, 48_000, 50_000, 52_000, 54_000, 56_000, 58_000};
    for (int v : mtpSamples) stats.addMtpSample(KEY_WIFI_MISS_FPT, v);

    QoSInfo s1 = vSegWithMtp(1_000L, 2_000L, 3_000L, 400, 1_500, 10_000);
    QoSInfo s2 = vSegWithMtp(3_000L, 2_000L, 3_000L, 400, 1_500, 10_000);
    QoSInfo s3 = vSegWithMtp(5_000L, 2_000L, 3_000L, 400, 1_500, 10_000);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.NETWORK);
    assertThat(d.nVDrained).isEqualTo(3);
    assertThat(d.nVCdnEvidence).isEqualTo(0);
  }

  @Test
  public void diagnose_vOnly_segmentsWithRetries_nRetriesCounted() {
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSegWithRetries(1_000L, 2_000L, 4_000L, 200, 1_500, /* retries= */ 1);
    QoSInfo s2 = vSegWithRetries(3_000L, 2_000L, 4_000L, 200, 1_500, /* retries= */ 1);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 4_000L, 200, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.NETWORK);
    assertThat(d.nRetries).isEqualTo(2);
  }

  // ===== Audio classification (V7 parity) =====

  @Test
  public void diagnose_audioFenceColdStart_skipsAudioEvidence_vDrives() {
    SessionStatistics stats = sessionStatsWarmV(KEY_WIFI_MISS_FPT); // V only
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo a1 = aSeg(6_000L, 1_800L, 2_400L, 300);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, a1, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.CDN);
    assertThat(d.nVDrained).isEqualTo(3);
    assertThat(d.nVCdnEvidence).isEqualTo(3);
    assertThat(d.nADrained).isEqualTo(0); // audioFence null → A skipped
    assertThat(d.nACdnEvidence).isEqualTo(0);
    assertThat(d.ttfbFenceUpperAMs).isEqualTo(-1);
  }

  @Test
  public void diagnose_vAndACdn_audioCorroborates_strongerCdn() {
    // V 3 CDN + A 2 CDN → nDrained=5, nCdnEv=5, gate pass → CDN.
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo a1 = aSeg(2_000L, 1_800L, 2_000L, 300);
    QoSInfo a2 = aSeg(4_000L, 1_800L, 2_000L, 300);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, a1, a2, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.CDN);
    assertThat(d.nVDrained).isEqualTo(3);
    assertThat(d.nVCdnEvidence).isEqualTo(3);
    assertThat(d.nADrained).isEqualTo(2);
    assertThat(d.nACdnEvidence).isEqualTo(2);
    assertThat(d.nDrainedTotal()).isEqualTo(5);
    assertThat(d.nCdnEvidenceTotal()).isEqualTo(5);
  }

  @Test
  public void diagnose_vCdnAudioBodySlow_dropsBelowGate_returnsNetwork() {
    // V 4 CDN + A 5 drained body-slow → nDrained=9, nCdnEv=4, 4×2=8 < 9 → NETWORK.
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s4 = vSeg(7_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo a1 = aSeg(2_000L, 1_800L, 5_000L, 300);
    QoSInfo a2 = aSeg(4_000L, 1_800L, 5_000L, 300);
    QoSInfo a3 = aSeg(6_000L, 1_800L, 5_000L, 300);
    QoSInfo a4 = aSeg(8_000L, 1_800L, 5_000L, 300);
    QoSInfo a5 = aSeg(9_000L, 1_800L, 5_000L, 300);
    QoSInfo trigger = triggerSeg(11_000L, 0);
    RebufferGroup g =
        new RebufferGroup(
            1,
            11_000L,
            trigger,
            Arrays.asList(s1, s2, s3, s4, a1, a2, a3, a4, a5, trigger),
            null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.NETWORK);
    assertThat(d.nDrainedTotal()).isEqualTo(9);
    assertThat(d.nCdnEvidenceTotal()).isEqualTo(4);
    assertThat(d.nVDrained).isEqualTo(4);
    assertThat(d.nADrained).isEqualTo(5);
    assertThat(d.nACdnEvidence).isEqualTo(0);
  }

  @Test
  public void diagnose_audioOnlyDrained_vHealthy_returnsNetwork() {
    // V healthy (no drain) + A 3 drained body-slow → nDrained=3, nCdnEv=0 → NETWORK.
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 1_950L, 90, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_050L, 95, 1_500); // excess=0.025 not drained
    QoSInfo a1 = aSeg(2_000L, 1_800L, 5_000L, 300);
    QoSInfo a2 = aSeg(4_000L, 1_800L, 5_000L, 300);
    QoSInfo a3 = aSeg(6_000L, 1_800L, 5_000L, 300);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(
            1, 7_000L, trigger, Arrays.asList(s1, s2, s3, a1, a2, a3, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.NETWORK);
    assertThat(d.nVDrained).isEqualTo(0);
    assertThat(d.nADrained).isEqualTo(3);
    assertThat(d.nACdnEvidence).isEqualTo(0);
    assertThat(d.nVAbrLag).isEqualTo(0);
  }

  @Test
  public void diagnose_audioFenceExposed_strictTtfbUpperA_setOnClassified() {
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo a1 = aSeg(2_000L, 1_800L, 2_000L, 300);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, a1, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.ttfbFenceUpperAMs).isEqualTo(50);
    assertThat(d.ttfbFenceUpperVMs).isEqualTo(100);
  }

  // ===== V8 B2: abr-lag counters =====

  @Test
  public void diagnose_segsWithBitrateAboveMtp_incrementAbrLag() {
    // 4 V segs: 2 abr-lag + 2 no abr-lag. All 4 drained + CDN-evidence.
    // abr-lag seg: bitrateKbps=1100, mtp=1200 → 1100 > 960 (=1200×0.8) ✓
    //   bytesLoaded=240_000B, loadDur=2400, ttfb=500 → postTtfbKbps=(240000×8)/1900≈1010
    //   bodyHealthy: 1010 >= 1100×0.9=990 ✓; ttfb=500>fence=100 ✓ → CDN-evidence ✓
    // no-abr-lag seg (default): bitrateKbps=1000, mtp=1500 → 1000 > 1200 ✗
    // Expected: nVAbrLag=2, nVAbrLagInDrain=2, nVAbrLagInCdn=2.
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSegWithBitrateAndMtp(1_000L, 2_000L, 2_400L, 500, 1_500, 1_100, 1_200);
    QoSInfo s2 = vSegWithBitrateAndMtp(3_000L, 2_000L, 2_400L, 500, 1_500, 1_100, 1_200);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500); // bitrate=1000, mtp=1500 → no abr-lag
    QoSInfo s4 = vSeg(7_000L, 2_000L, 2_400L, 500, 1_500); // bitrate=1000, mtp=1500 → no abr-lag
    QoSInfo trigger = triggerSeg(9_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 9_000L, trigger, Arrays.asList(s1, s2, s3, s4, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.CDN);
    assertThat(d.nV).isEqualTo(4);
    assertThat(d.nVDrained).isEqualTo(4);
    assertThat(d.nVCdnEvidence).isEqualTo(4);
    assertThat(d.nVAbrLag).isEqualTo(2);
    assertThat(d.nVAbrLagInDrain).isEqualTo(2);
    assertThat(d.nVAbrLagInCdn).isEqualTo(2);
  }

  @Test
  public void diagnose_nonDrainedAbrLag_countsTotalNotDrain() {
    // 2 abr-lag segs but NOT drained (loadDur ≤ cdur × 1.10 → excessRatio ≤ 0.10).
    // cdur=2000, loadDur=2100 → excessRatio=0.05 < SLOW_RATIO=0.10 → NOT drained.
    // bitrateKbps=5000, mtp=4000 → 5000 > 3200 ✓ abr-lag but no drain.
    // Expected: nVAbrLag=2, nVAbrLagInDrain=0, nVAbrLagInCdn=0.
    // But nVDrained=0 → INCONCLUSIVE(NO_DRAIN), still nVAbrLag should be 2.
    // However DiagnosisV8.inconclusive path doesn't go through classified().
    // So test a mixed scenario: 2 non-drained abr-lag + 2 drained non-abr-lag (for a verdict).
    // 2 drained non-abr-lag: ttfb=50 (NOT extreme, fence=100) → NETWORK verdict.
    // nVAbrLag=2 (all abr-lag segs regardless of drain), nVAbrLagInDrain=0, nVAbrLagInCdn=0.
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    // 2 abr-lag segs: not drained (loadDur=2100, cdur=2000 → excess=0.05 < 0.10)
    // bitrateKbps=1100, mtp=1200 → 1100 > 960 ✓ abr-lag
    QoSInfo s1 = vSegWithBitrateAndMtp(1_000L, 2_000L, 2_100L, 80, 1_500, 1_100, 1_200);
    QoSInfo s2 = vSegWithBitrateAndMtp(3_000L, 2_000L, 2_100L, 80, 1_500, 1_100, 1_200);
    // 2 non-abr-lag segs: drained (loadDur=3_000, cdur=2000 → excess=0.5 > 0.10), ttfb=50 not extreme
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo s4 = vSeg(7_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo trigger = triggerSeg(9_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 9_000L, trigger, Arrays.asList(s1, s2, s3, s4, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.NETWORK);
    assertThat(d.nVDrained).isEqualTo(2);
    assertThat(d.nVAbrLag).isEqualTo(2);
    assertThat(d.nVAbrLagInDrain).isEqualTo(0);
    assertThat(d.nVAbrLagInCdn).isEqualTo(0);
  }

  @Test
  public void diagnose_audioNoDrain_vNoDrain_returnsNoDrain() {
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 1_950L, 90, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_050L, 95, 1_500);
    QoSInfo a1 = aSeg(2_000L, 1_800L, 1_800L, 40);
    QoSInfo a2 = aSeg(4_000L, 1_800L, 1_850L, 45);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, a1, a2, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV8.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV8.Reason.NO_DRAIN);
  }

  @Test
  public void diagnose_mtpZero_doesNotCountAbrLag() {
    // V segs with measuredThroughputKbps=0 must NOT count as abr-lag regardless of bitrate,
    // because CMCD (Common Media Client Data) throughput is unavailable → cannot infer
    // ABR (Adaptive Bitrate) over-estimate.
    // Setup: 4 drained V segs (loadDur=3000 > cdur=2000×1.10=2200):
    //   - 2 with mtp=0, bitrateKbps=1000 (mtp unavailable → guard blocks abr-lag=false)
    //   - 2 with mtp=1500, bitrateKbps=1000 (1000 ≤ 1500×0.8=1200 → abr-lag=false)
    // Expected: nVAbrLag=0, nVDrained=4.
    SessionStatistics stats = sessionStatsWarmFull(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSegWithMtp(1_000L, 2_000L, 3_000L, 50, 1_500, /* mtpKbps= */ 0);
    QoSInfo s2 = vSegWithMtp(3_000L, 2_000L, 3_000L, 50, 1_500, /* mtpKbps= */ 0);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 50, 1_500); // mtp=1500, bitrate=1000 → no abr-lag
    QoSInfo s4 = vSeg(7_000L, 2_000L, 3_000L, 50, 1_500); // mtp=1500, bitrate=1000 → no abr-lag
    QoSInfo trigger = triggerSeg(9_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 9_000L, trigger, Arrays.asList(s1, s2, s3, s4, trigger), null);
    DiagnosisV8 d = QoSDiagnoserV8.diagnose(g, stats);
    assertThat(d.nVDrained).isEqualTo(4);
    assertThat(d.nVAbrLag).isEqualTo(0);
    assertThat(d.nVAbrLagInDrain).isEqualTo(0);
  }

  // ===== Helpers (mirrors QoSDiagnoserV7Test helpers) =====

  static QoSInfo vSeg(long ts, long cdurMs, long loadDurMs, int ttfbMs, int blMs) {
    return vSegWithMtp(ts, cdurMs, loadDurMs, ttfbMs, blMs, /* mtpKbps= */ 1_500);
  }

  static QoSInfo vSegWithMtp(
      long ts, long cdurMs, long loadDurMs, int ttfbMs, int blMs, int mtpKbps) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setStatus(QoSInfo.LoadStatus.COMPLETED)
        .setChunkDurationMs(cdurMs)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBufferedDurationMs(blMs)
        .setBytesLoaded(240_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(mtpKbps)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("MISS")
        .setCdnProvider("fpt")
        .build();
  }

  static QoSInfo vSegWithRetries(
      long ts, long cdurMs, long loadDurMs, int ttfbMs, int blMs, int retries) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setStatus(QoSInfo.LoadStatus.COMPLETED)
        .setChunkDurationMs(cdurMs)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBufferedDurationMs(blMs)
        .setBytesLoaded(240_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_500)
        .setRetryCount(retries)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("MISS")
        .setCdnProvider("fpt")
        .build();
  }

  static QoSInfo aSeg(long ts, long cdurMs, long loadDurMs, int ttfbMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_AUDIO)
        .setStatus(QoSInfo.LoadStatus.COMPLETED)
        .setChunkDurationMs(cdurMs)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBytesLoaded(28_800L)
        .setBitrateKbps(128)
        .setMeasuredThroughputKbps(1_500)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("MISS")
        .setCdnProvider("fpt")
        .build();
  }

  static QoSInfo errorSeg(long ts, int httpCode) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setStatus(QoSInfo.LoadStatus.ERROR)
        .setHttpStatusCode(httpCode)
        .setErrorMessage("Response code: " + httpCode)
        .build();
  }

  static QoSInfo triggerSeg(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBufferedDurationMs(blMs)
        .setBufferStarvationFlag(true)
        .build();
  }

  static QoSInfo initTriggerSeg(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBufferedDurationMs(blMs)
        .setStatus(QoSInfo.LoadStatus.COMPLETED)
        .setBytesLoaded(1_700L)
        .setLoadDurationMs(46L)
        .setBufferStarvationFlag(true)
        .build();
  }

  static SessionStatistics sessionStatsWarmV(String key) {
    SessionStatistics stats = new SessionStatistics();
    for (int i = 0; i < 10; i++) {
      stats.addTtfbSample(key, 100);
    }
    return stats;
  }

  static SessionStatistics sessionStatsWarmFull(String key) {
    SessionStatistics stats = sessionStatsWarmV(key);
    for (int i = 0; i < 10; i++) {
      stats.addAudioTtfbSample(key, 50);
    }
    return stats;
  }

  static QoSInfo vSegWithBitrateAndMtp(
      long ts,
      long cdurMs,
      long loadDurMs,
      int ttfbMs,
      int blMs,
      int bitrateKbps,
      int mtpKbps) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setStatus(QoSInfo.LoadStatus.COMPLETED)
        .setChunkDurationMs(cdurMs)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBufferedDurationMs(blMs)
        .setBytesLoaded(240_000L)
        .setBitrateKbps(bitrateKbps)
        .setMeasuredThroughputKbps(mtpKbps)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("MISS")
        .setCdnProvider("fpt")
        .build();
  }
}
