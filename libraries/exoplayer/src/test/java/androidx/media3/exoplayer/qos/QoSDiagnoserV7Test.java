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
import androidx.media3.exoplayer.qos.model.DiagnosisV7;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for V7 — V6 + audio additive merge. SessionStatistics pre-warmed with 10 V
 * TTFB samples all = 100 → V Tukey upper fence = 100 (K=1.5) → strict V upper (K=3) = 100.
 * V {@code chunkDur=2000ms}, so {@code loadDur > 2200} = drained.
 *
 * <p>Audio fixture: {@code aSeg} cdur=1800ms (typical FPlay audio), bitrate=128kbps,
 * bytes=28800 (~128kbps × 1.8s). Audio Tukey upper warmed at audioTtfb=50 → strict K=3
 * upper=50.
 */
@RunWith(AndroidJUnit4.class)
public class QoSDiagnoserV7Test {

  private static final String KEY_WIFI_MISS_FPT = "WIFI_MISS_fpt";

  // ===== Defensive cases =====

  @Test
  public void diagnose_nullGroup_returnsInconclusiveEmptyGroup() {
    DiagnosisV7 d = QoSDiagnoserV7.diagnose(null, sessionStatsWarmV(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV7.Reason.EMPTY_GROUP);
  }

  @Test
  public void diagnose_emptyEntries_returnsInconclusiveEmptyGroup() {
    QoSInfo trigger = triggerSeg(0L, 0);
    RebufferGroup g = new RebufferGroup(1, 0L, trigger, Arrays.asList(), null);
    DiagnosisV7 d = QoSDiagnoserV7.diagnose(g, sessionStatsWarmV(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV7.Reason.EMPTY_GROUP);
  }

  @Test
  public void diagnose_audioOnly_returnsInconclusiveNoCompletedVSeg() {
    // V7-specific: only audio segs filtered as A, but V list still empty → NO_COMPLETED_V_SEG.
    QoSInfo a1 = aSeg(1_000L, 1_800L, 1_800L, 50);
    QoSInfo trigger = triggerSeg(3_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 3_000L, trigger, Arrays.asList(a1, trigger), null);
    DiagnosisV7 d = QoSDiagnoserV7.diagnose(g, sessionStatsWarmFull(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV7.Reason.NO_COMPLETED_V_SEG);
  }

  @Test
  public void diagnose_onlyErrorSegments_returnsNoCompletedVSegWithHttpCodes() {
    QoSInfo err = errorSeg(1_000L, 503);
    QoSInfo trigger = triggerSeg(2_000L, 0);
    RebufferGroup g =
        new RebufferGroup(1, 2_000L, trigger, Arrays.asList(err, trigger), null);
    DiagnosisV7 d = QoSDiagnoserV7.diagnose(g, sessionStatsWarmV(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV7.Reason.NO_COMPLETED_V_SEG);
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
    DiagnosisV7 d = QoSDiagnoserV7.diagnose(g, stats);
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV7.Reason.TRACK_SWITCH);
  }

  // ===== Sanity gate =====

  @Test
  public void diagnose_degenerateWindow_returnsInconclusiveSanityFail() {
    QoSInfo s1 = vSeg(1_000L, 2_000L, 1_900L, 80, 1_500);
    QoSInfo trigger = triggerSeg(1_000L, 0); // wall=0 → sanity fail
    RebufferGroup g =
        new RebufferGroup(1, 1_000L, trigger, Arrays.asList(s1, trigger), null);
    DiagnosisV7 d = QoSDiagnoserV7.diagnose(g, sessionStatsWarmFull(KEY_WIFI_MISS_FPT));
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV7.Reason.SANITY_FAIL);
    assertThat(d.reasonDetail).isNotEmpty();
  }

  // ===== Helpers (shared with A2b/A2c tests) =====

  /** Video seg: WIFI / MISS / fpt CDN, cdur=2000, bytesLoaded=240KB, bitrate=1000kbps. */
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

  /**
   * Audio seg: cdur=1800ms, bitrate=128kbps, bytes=28800B (~128kbps × 1.8s). WIFI/MISS/fpt
   * to match the same session key as V.
   */
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

  /** V-only fence warm: 10 V TTFB samples all = 100. Audio Maps untouched. */
  static SessionStatistics sessionStatsWarmV(String key) {
    SessionStatistics stats = new SessionStatistics();
    for (int i = 0; i < 10; i++) {
      stats.addTtfbSample(key, 100);
    }
    return stats;
  }

  /** V + A fences warmed: 10 V TTFB at 100, 10 A TTFB at 50. */
  static SessionStatistics sessionStatsWarmFull(String key) {
    SessionStatistics stats = sessionStatsWarmV(key);
    for (int i = 0; i < 10; i++) {
      stats.addAudioTtfbSample(key, 50);
    }
    return stats;
  }
}
