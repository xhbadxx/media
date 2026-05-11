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
import androidx.media3.exoplayer.qos.model.DiagnosisV6;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for V6 — simplified 3-cause cascade. Pre-populated SessionStatistics with 10
 * samples all = 100 → Tukey upper fence = 100 (Q1=Q3=100, IQR=0). So {@code ttfb > 100}
 * = outlier; otherwise = healthy. {@code chunkDur=2000ms}, so {@code loadDur > 2200} =
 * drained ({@code excessRatio > 0.10}).
 *
 * <p>Fixture timestamps + bl chosen so the V1 sanity gate passes (demand_ratio in
 * [0.7, 1.3]). Approx: bl=1500 on first V, trigger.bl=0, delta_buffer=-1500. With 3 V
 * cdur=2000 → supply=6000, demand=7500, wall ~6000 → ratio ~1.25 (just below 1.3).
 */
@RunWith(AndroidJUnit4.class)
public class QoSDiagnoserV6Test {

  private static final String KEY_WIFI_MISS_FPT = "WIFI_MISS_fpt";

  // ===== Defensive cases =====

  @Test
  public void diagnose_nullGroup_returnsUnknownNoVData() {
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(null, sessionStatsWarm(KEY_WIFI_MISS_FPT));
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("no_v_data");
  }

  @Test
  public void diagnose_emptyEntries_returnsUnknownNoVData() {
    QoSInfo trigger = triggerSeg(0L, 0);
    RebufferGroup g = new RebufferGroup(1, 0L, trigger, Arrays.asList(), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, sessionStatsWarm(KEY_WIFI_MISS_FPT));
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("no_v_data");
  }

  @Test
  public void diagnose_onlyErrorSegmentsNoCompleted_returnsUnknownNoVDataWithHttpCodes() {
    QoSInfo err = errorSeg(1_000L, 503);
    QoSInfo trigger = triggerSeg(2_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 2_000L, trigger, Arrays.asList(err, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, sessionStatsWarm(KEY_WIFI_MISS_FPT));
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("no_v_data");
    assertThat(r.httpErrorCodes).asList().containsExactly(503);
  }

  // ===== Sanity gate =====

  @Test
  public void diagnose_degenerateWindow_returnsUnknownSanity() {
    // wall_time = 0 → sanity gate fires "wall_time = 0ms (window degenerate...)"
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo trigger = triggerSeg(1_000L, 0); // SAME ts as s1 → wall=0
    RebufferGroup g =
        new RebufferGroup(1, 1_000L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, sessionStatsWarm(KEY_WIFI_MISS_FPT));
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).startsWith("sanity:");
  }

  // ===== Cold-start =====

  @Test
  public void diagnose_nullSessionStats_returnsUnknownColdStart() {
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo trigger = triggerSeg(4_500L, 0); // wall=3500, supply=2000, demand=3500, ratio=1.0
    RebufferGroup g =
        new RebufferGroup(1, 4_500L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, /* sessionStats= */ null);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("cold_start");
  }

  @Test
  public void diagnose_lessThanMinSamples_returnsUnknownColdStart() {
    SessionStatistics stats = new SessionStatistics();
    for (int i = 0; i < 5; i++) {
      stats.addTtfbSample(KEY_WIFI_MISS_FPT, 100);
    }
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo trigger = triggerSeg(4_500L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 4_500L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("cold_start");
  }

  // ===== No drain =====

  @Test
  public void diagnose_warmFenceZeroDrained_returnsUnknownNoDrain() {
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    // supply=6000, bl 1500→1500→1500→0 (delta=-1500), demand=7500, wall=6000 → ratio=1.25
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500); // excess=0, healthy
    QoSInfo s2 = vSeg(3_000L, 2_000L, 1_950L, 90, 1_500); // excess=0, healthy
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_050L, 95, 1_500); // excess=0.025, not drained
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("no_drain");
  }

  // ===== Classified =====

  @Test
  public void diagnose_warmFence_allDrainedAllTtfbOutlier_returnsCdn() {
    // CDN slow-start pattern: high TTFB (server delayed) + short loadDur (body fast).
    // bytes=240KB, bitrate=1000, ttfb=500 loadDur=2400 → transferMs=1900 → rate=1010kbps
    // → body ratio 1.01 ≥ 0.90 healthy ✓
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500); // drained + outlier + body healthy
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nV).isEqualTo(3);
    assertThat(r.nDrained).isEqualTo(3);
    assertThat(r.nCdnEvidence).isEqualTo(3);
    assertThat(r.ttfbFenceUpperMs).isEqualTo(100);
    assertThat(r.lastTtfbMs).isEqualTo(500);
    assertThat(r.lastBufferMs).isEqualTo(1_500);
  }

  @Test
  public void diagnose_warmFence_drainedNoTtfbOutlier_returnsClient() {
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 50, 1_500); // drained, healthy ttfb
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_500L, 60, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 4_000L, 70, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.nDrained).isEqualTo(3);
    assertThat(r.nCdnEvidence).isEqualTo(0);
  }

  @Test
  public void diagnose_warmFence_drainedTtfbOutlierButBodySlow_returnsClient() {
    // Real-world r9 pattern: ttfb high (server response slightly delayed) + body slow
    // (post-TTFB rate << bitrate) = network bandwidth shortage, not CDN. Body-healthy
    // veto kicks in → CLIENT.
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    // ttfb=200 (outlier > strict 100) + loadDur=4000 → transferMs=3800 → rate=505kbps
    // → body ratio 0.505 < 0.90 unhealthy → NOT counted as CDN evidence
    QoSInfo s1 = vSeg(1_000L, 2_000L, 4_000L, 200, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 4_000L, 200, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 4_000L, 200, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.nDrained).isEqualTo(3);
    assertThat(r.nCdnEvidence).isEqualTo(0); // all vetoed by body-slow check
  }

  @Test
  public void diagnose_warmFence_majorityCdnEvidence75pct_returnsCdn() {
    // 4 drained, 3 with TTFB outlier AND body healthy (75%). 3*2=6 ≥ 4 ✓
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500); // drained + outlier + healthy body
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_400L, 500, 1_500);
    QoSInfo s4 = vSeg(7_000L, 2_000L, 3_000L, 50, 1_500); // drained, no ttfb outlier
    QoSInfo trigger = triggerSeg(9_000L, 0);
    RebufferGroup g =
        new RebufferGroup(
            1, 9_000L, trigger, Arrays.asList(s1, s2, s3, s4, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nDrained).isEqualTo(4);
    assertThat(r.nCdnEvidence).isEqualTo(3);
  }

  @Test
  public void diagnose_warmFence_minorityCdnEvidence25pct_returnsClient() {
    // 4 drained, 1 with TTFB outlier + body healthy (25%). 1*2=2 < 4 → CLIENT
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 500, 1_500); // drained + outlier + healthy body
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_000L, 50, 1_500); // drained, no outlier
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo s4 = vSeg(7_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo trigger = triggerSeg(9_000L, 0);
    RebufferGroup g =
        new RebufferGroup(
            1, 9_000L, trigger, Arrays.asList(s1, s2, s3, s4, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.nDrained).isEqualTo(4);
    assertThat(r.nCdnEvidence).isEqualTo(1);
  }

  @Test
  public void diagnose_httpErrorCodesAttached_butDoNotDriveClassification() {
    // 503 error + 3 drained no outlier → CLIENT (not CDN_HTTP like V5).
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo err = errorSeg(2_000L, 503);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 50, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(
            1, 7_000L, trigger, Arrays.asList(s1, err, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.httpErrorCodes).asList().containsExactly(503);
  }

  @Test
  public void diagnose_singleVSeg_outlierAndBodyHealthy_returnsCdn() {
    // CDN slow-start single-spike: high TTFB but body delivered at full rate.
    // ttfb=600, loadDur=2400 → transferMs=1800 → rate=1067kbps → body ratio 1.07 ✓
    // supply=2000, bl 1500→0, demand=3500, wall=3500 → ratio=1.0 → sanity ok
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_400L, 600, 1_500);
    QoSInfo trigger = triggerSeg(4_500L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 4_500L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nDrained).isEqualTo(1);
    assertThat(r.nCdnEvidence).isEqualTo(1);
  }

  // ===== Strict TTFB outlier + mtp-collapse guard (K=3 enhancements) =====

  @Test
  public void diagnose_strictFence_marginalTtfbOutlier_notCountedAsCdn() {
    // TTFB samples spread: Q1=70, Q3=120, IQR=50. Upper(K=1.5)=195. Upper(K=3)=270.
    // V segs with ttfb=200 → above K=1.5 upper, below K=3 strict upper → NOT CDN.
    SessionStatistics stats = new SessionStatistics();
    int[] ttfbSamples = {50, 60, 70, 80, 90, 100, 110, 120, 130, 140};
    for (int v : ttfbSamples) stats.addTtfbSample(KEY_WIFI_MISS_FPT, v);

    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 200, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_000L, 200, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 200, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);

    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.nDrained).isEqualTo(3);
    assertThat(r.nCdnEvidence).isEqualTo(0); // marginal outliers not counted
    assertThat(r.ttfbFenceUpperMs).isEqualTo(270); // strict K=3 fence exposed
  }

  @Test
  public void diagnose_strictFence_extremeTtfbOutlier_andBodyHealthy_returnsCdn() {
    // Same TTFB baseline, ttfb=400 (above K=3 upper 270) + short loadDur (body healthy).
    // ttfb=400 + loadDur=2300 → transferMs=1900 → rate=1010kbps → ratio 1.01 ✓
    SessionStatistics stats = new SessionStatistics();
    int[] ttfbSamples = {50, 60, 70, 80, 90, 100, 110, 120, 130, 140};
    for (int v : ttfbSamples) stats.addTtfbSample(KEY_WIFI_MISS_FPT, v);

    QoSInfo s1 = vSeg(1_000L, 2_000L, 2_300L, 400, 1_500);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 2_300L, 400, 1_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_300L, 400, 1_500);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);

    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nCdnEvidence).isEqualTo(3);
  }

  @Test
  public void diagnose_mtpCollapsed_vetoesCdnEvidence_forcesClient() {
    // TTFB fence: extreme outlier path active (ttfb=400 > strict 100).
    // mtp baseline: 40-58 Mbps spread → lower fence around 29 Mbps. V mtp=10 Mbps =
    // collapsed → vetoes CDN evidence even though ttfb is extreme outlier.
    SessionStatistics stats = new SessionStatistics();
    for (int i = 0; i < 10; i++) stats.addTtfbSample(KEY_WIFI_MISS_FPT, 100);
    int[] mtpSamples = {40_000, 42_000, 44_000, 46_000, 48_000, 50_000, 52_000, 54_000,
        56_000, 58_000};
    for (int v : mtpSamples) stats.addMtpSample(KEY_WIFI_MISS_FPT, v);

    QoSInfo s1 = vSegWithMtp(1_000L, 2_000L, 3_000L, 400, 1_500, 10_000); // collapsed
    QoSInfo s2 = vSegWithMtp(3_000L, 2_000L, 3_000L, 400, 1_500, 10_000);
    QoSInfo s3 = vSegWithMtp(5_000L, 2_000L, 3_000L, 400, 1_500, 10_000);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);

    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.mtpCollapseDetected).isTrue();
    assertThat(r.nDrained).isEqualTo(3);
    assertThat(r.nCdnEvidence).isEqualTo(0); // all vetoed by mtp collapse
  }

  // ===== Helpers =====

  /** WIFI / cache=MISS / cdn=fpt — matches {@link #sessionStatsWarm} key. */
  private static QoSInfo vSeg(long ts, long cdurMs, long loadDurMs, int ttfbMs, int blMs) {
    return vSegWithMtp(ts, cdurMs, loadDurMs, ttfbMs, blMs, /* mtpKbps= */ 1_500);
  }

  /** vSeg overload with explicit measured throughput for mtp-collapse tests. */
  private static QoSInfo vSegWithMtp(
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

  private static QoSInfo errorSeg(long ts, int httpCode) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setStatus(QoSInfo.LoadStatus.ERROR)
        .setHttpStatusCode(httpCode)
        .setErrorMessage("Response code: " + httpCode)
        .build();
  }

  private static QoSInfo triggerSeg(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBufferedDurationMs(blMs)
        .setBufferStarvationFlag(true)
        .build();
  }

  /** 10 TTFB samples all = 100 → Tukey upper fence = 100. Outlier = ttfb > 100. */
  private static SessionStatistics sessionStatsWarm(String key) {
    SessionStatistics stats = new SessionStatistics();
    for (int i = 0; i < 10; i++) {
      stats.addTtfbSample(key, 100);
    }
    return stats;
  }
}
