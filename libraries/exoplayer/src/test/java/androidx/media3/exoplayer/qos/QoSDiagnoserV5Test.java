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
import androidx.media3.exoplayer.qos.model.DiagnosisV5;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.model.SessionStatistics;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class QoSDiagnoserV5Test {

  // ===== Defensive cases =====

  @Test
  public void diagnose_nullGroup_returnsTransient() {
    DiagnosisV5 r = QoSDiagnoserV5.diagnose(null, /* sessionStats= */ null);
    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.sanityFailReason).isNotNull();
  }

  @Test
  public void diagnose_emptyEntries_returnsTransient() {
    RebufferGroup group =
        new RebufferGroup(
            /* id= */ 1,
            /* triggerTimeMs= */ 0L,
            /* trigger= */ triggerSegment(0L, 0),
            /* entries= */ Arrays.asList(),
            /* diagnosis= */ null);
    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);
    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
  }

  // ===== HTTP 5xx fast-path =====

  @Test
  public void diagnose_single5xx_returnsCdnHttpError() {
    QoSInfo s1 = healthySegment(1_000L, 9_000);
    QoSInfo errSeg = errorSegment(3_000L, 503);
    QoSInfo s2 = healthySegment(5_000L, 8_000);
    QoSInfo trigger = triggerSegment(7_000L, 5_000);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, errSeg, s2, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_HTTP_ERROR);
    assertThat(r.httpErrorCodes).asList().containsExactly(503);
  }

  @Test
  public void diagnose_mix5xxAnd4xx_returnsCdnHttpErrorWithAllCodes() {
    QoSInfo errA = errorSegment(1_000L, 503);
    QoSInfo errB = errorSegment(3_000L, 404);
    QoSInfo errC = errorSegment(5_000L, 504);
    QoSInfo trigger = triggerSegment(7_000L, 5_000);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(errA, errB, errC, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_HTTP_ERROR);
    // 5xx first, 4xx after.
    assertThat(r.httpErrorCodes).asList().containsExactly(503, 504, 404).inOrder();
  }

  @Test
  public void diagnose_only4xx_doesNotFireCdnHttpError() {
    // 4xx with rest healthy → falls through past Step 2 (V5 placeholder ends at TRANSIENT
    // until Tasks A4-A7 fill remaining steps). 4xx codes still surface in evidence.
    // Window math: ts gap=2500, bl 1500→0 over 2 V-segs → wallTime=5000, supply=4000,
    //   Δbuffer=-1500, demand=5500, ratio=1.10 ⇒ pass sanity.
    QoSInfo s1 = healthySegment(1_000L, 1_500);
    QoSInfo err = errorSegment(3_500L, 401);
    QoSInfo s2 = healthySegment(6_000L, 600);
    QoSInfo trigger = triggerSegment(8_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, err, s2, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isNotEqualTo(DiagnosisV5.Cause.CDN_HTTP_ERROR);
    assertThat(r.httpErrorCodes).asList().containsExactly(401);
  }

  // ===== Sanity fail =====

  @Test
  public void diagnose_demandRatio_belowGate_returnsTransient() {
    QoSInfo s1 = scoredSegment(1_000L, 2_000L, 500L, 100, 5_000);
    QoSInfo s2 = scoredSegment(5_000L, 2_000L, 500L, 100, 5_000);
    QoSInfo trigger = triggerSegment(11_000L, 5_000);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.sanityFailReason).isNotNull();
  }

  // ===== Test fixture builders (mirror V4 patterns for consistency) =====

  /** V-segment with default mtp 5_000kbps, bitrate 2_000kbps (ABR-not-aware). */
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

  /** Healthy V-segment for noise (loadDur ≈ cdur, low TTFB). */
  private static QoSInfo healthySegment(long ts, int blMs) {
    return scoredSegment(ts, /* cdurMs= */ 2_000L, /* loadDurMs= */ 1_900L,
        /* ttfbMs= */ 200, blMs);
  }

  /** Trigger entry — chunkDurationMs=0 so it does not contribute. */
  private static QoSInfo triggerSegment(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBufferedDurationMs(blMs)
        .setBufferStarvationFlag(true)
        .build();
  }

  /** Errored segment with explicit HTTP code. */
  private static QoSInfo errorSegment(long ts, int httpCode) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setStatus(QoSInfo.LoadStatus.ERROR)
        .setHttpStatusCode(httpCode)
        .setErrorMessage("Response code: " + httpCode)
        .build();
  }
}
