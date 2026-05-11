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

  // ===== Cold-start =====

  @Test
  public void diagnose_nullSessionStats_returnsUnknownColdStart() {
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 4_000);
    QoSInfo trigger = triggerSeg(2_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 2_000L, trigger, Arrays.asList(s1, trigger), null);
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
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 4_000);
    QoSInfo trigger = triggerSeg(2_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 2_000L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("cold_start");
  }

  // ===== No drain =====

  @Test
  public void diagnose_warmFenceZeroDrained_returnsUnknownNoDrain() {
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 4_000); // excess=0, healthy
    QoSInfo s2 = vSeg(3_000L, 2_000L, 1_950L, 90, 4_000); // excess=0, healthy
    QoSInfo s3 = vSeg(5_000L, 2_000L, 2_050L, 95, 4_000); // excess=0.025, not drained
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
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 200, 3_000); // drained + outlier
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_500L, 250, 2_500); // drained + outlier
    QoSInfo s3 = vSeg(5_000L, 2_000L, 4_000L, 300, 2_000); // drained + outlier
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nV).isEqualTo(3);
    assertThat(r.nDrained).isEqualTo(3);
    assertThat(r.nCdnEvidence).isEqualTo(3);
    assertThat(r.ttfbFenceUpperMs).isEqualTo(100);
    assertThat(r.lastTtfbMs).isEqualTo(300);
    assertThat(r.lastBufferMs).isEqualTo(2_000);
  }

  @Test
  public void diagnose_warmFence_drainedNoTtfbOutlier_returnsClient() {
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 50, 3_000); // drained, healthy ttfb
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_500L, 60, 2_500); // drained, healthy ttfb
    QoSInfo s3 = vSeg(5_000L, 2_000L, 4_000L, 70, 2_000); // drained, healthy ttfb
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 7_000L, trigger, Arrays.asList(s1, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.nDrained).isEqualTo(3);
    assertThat(r.nCdnEvidence).isEqualTo(0);
  }

  @Test
  public void diagnose_warmFence_majorityCdnEvidence75pct_returnsCdn() {
    // 4 drained, 3 with TTFB outlier (75%). 3*2=6 ≥ 4 ✓
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 200, 3_000); // drained + outlier
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_000L, 200, 2_500); // drained + outlier
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 200, 2_000); // drained + outlier
    QoSInfo s4 = vSeg(7_000L, 2_000L, 3_000L, 50, 1_500); // drained, no outlier
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
    // 4 drained, 1 with TTFB outlier (25%). 1*2=2 < 4
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 200, 3_000); // drained + outlier
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_000L, 50, 2_500); // drained, no outlier
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 50, 2_000); // drained, no outlier
    QoSInfo s4 = vSeg(7_000L, 2_000L, 3_000L, 50, 1_500); // drained, no outlier
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
    QoSInfo s1 = vSeg(1_000L, 2_000L, 3_000L, 50, 3_000);
    QoSInfo err = errorSeg(2_000L, 503);
    QoSInfo s2 = vSeg(3_000L, 2_000L, 3_000L, 50, 2_500);
    QoSInfo s3 = vSeg(5_000L, 2_000L, 3_000L, 50, 2_000);
    QoSInfo trigger = triggerSeg(7_000L, 0);
    RebufferGroup g =
        new RebufferGroup(
            1, 7_000L, trigger, Arrays.asList(s1, err, s2, s3, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.httpErrorCodes).asList().containsExactly(503);
  }

  @Test
  public void diagnose_singleVSeg_extremelySlowAndOutlier_returnsCdn() {
    // nDrained=1, nCdnEvidence=1. 1*2=2 ≥ 1 ✓
    SessionStatistics stats = sessionStatsWarm(KEY_WIFI_MISS_FPT);
    QoSInfo s1 = vSeg(1_000L, 2_000L, 6_000L, 500, 1_000); // huge drain + outlier
    QoSInfo trigger = triggerSeg(2_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 2_000L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV6 r = QoSDiagnoserV6.diagnose(g, stats);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nDrained).isEqualTo(1);
    assertThat(r.nCdnEvidence).isEqualTo(1);
  }

  // ===== Helpers =====

  /** WIFI / cache=MISS / cdn=fpt — matches {@link #sessionStatsWarm} key. */
  private static QoSInfo vSeg(long ts, long cdurMs, long loadDurMs, int ttfbMs, int blMs) {
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
