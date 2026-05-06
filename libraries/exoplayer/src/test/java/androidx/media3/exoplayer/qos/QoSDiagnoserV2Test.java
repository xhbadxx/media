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

  private static QoSInfo triggerSegment(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBufferedDurationMs(blMs)
        .setBufferStarvationFlag(true)
        .build();
  }
}
