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
import androidx.media3.exoplayer.qos.model.Diagnosis;
import androidx.media3.exoplayer.qos.model.Pattern;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link QoSDiagnoser}. Each test builds a fixture {@link RebufferGroup}
 * representing one rebuffer scenario, runs the diagnoser, and asserts the verdict +
 * key findings. Fixtures intentionally use realistic numbers (e.g., the
 * {@code userNetworkDegrading} test mirrors a real production log at 14:32:38) so
 * regressions in threshold logic surface immediately.
 */
@RunWith(AndroidJUnit4.class)
public class QoSDiagnoserTest {

  // ============================================================================
  // Test 1 — User network degrading (real log @ 14:32:38)
  // ============================================================================

  @Test
  public void diagnose_userNetworkDegrading_returnsUserNetworkWithAbrLag() {
    // 5 healthy entries (audio + low-bitrate video) at start of stream
    QoSInfo a1 = audio(1_000L, /* dur= */ 109);
    QoSInfo v1 = video(2_000L, /* br= */ 1_800, /* dur= */ 535, /* sz= */ 463_700, /* ttfb= */ 36);
    QoSInfo a2 = audio(3_000L, /* dur= */ 543);
    QoSInfo a3 = audio(4_000L, /* dur= */ 368);
    QoSInfo a4 = audio(5_000L, /* dur= */ 200);
    // 2 critical V loads at 4.8M with insufficient throughput (~2.4Mbps actual)
    QoSInfo v2 =
        video(
            6_000L, /* br= */ 4_800, /* dur= */ 3_492, /* sz= */ 1_200_000, /* ttfb= */ 12);
    QoSInfo v3 =
        video(
            7_000L, /* br= */ 4_800, /* dur= */ 3_786, /* sz= */ 1_000_000, /* ttfb= */ 18);
    // Trigger: 4.8M video, mtp=2200kbps → br/mtp=2.18 → ABR_LAG
    QoSInfo trigger =
        videoTrigger(
            8_000L, /* br= */ 4_800, /* mtpKbps= */ 2_200);

    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(a1, v1, a2, a3, a4, v2, v3, trigger),
            Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    assertThat(result.pattern).isEqualTo(Pattern.USER_NETWORK);
    assertThat(result.abrLag).isTrue();
    // Two V loads + the trigger should be flagged
    assertThat(result.problemFindings()).hasSize(3);
    // First problem entry is v2 with throughput issue
    Diagnosis.Finding firstProblem = result.problemFindings().get(0);
    assertThat(firstProblem.entry).isSameInstanceAs(v2);
    assertThat(firstProblem.severity).isEqualTo(Diagnosis.Severity.CRITICAL);
    assertThat(joinIssues(firstProblem)).contains("throughput");
    assertThat(joinIssues(firstProblem)).contains("4800");
    // Conclusion mentions ABR_LAG
    assertThat(result.conclusion).contains("ABR_LAG");
  }

  // ============================================================================
  // Test 2 — Edge overload (HIT and MISS both have high TTFB)
  // ============================================================================

  @Test
  public void diagnose_edgeOverloadHitsAndTtfbHigh_returnsEdgeOverload() {
    // 5 healthy entries
    List<QoSInfo> entries = healthyPrefix();
    // High TTFB on HIT
    entries.add(videoSlowTtfb(1_000_000L, /* br= */ 4_800, /* ttfb= */ 700, /* cache= */ "HIT"));
    // High TTFB on MISS
    entries.add(videoSlowTtfb(1_001_000L, /* br= */ 4_800, /* ttfb= */ 800, /* cache= */ "MISS"));
    QoSInfo trigger = videoTriggerWithMtp(1_002_000L, /* br= */ 1_800, /* mtp= */ 5_000);
    entries.add(trigger);

    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, entries, Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    assertThat(result.pattern).isEqualTo(Pattern.CDN_EDGE_OVERLOAD);
  }

  // ============================================================================
  // Test 3 — Origin slow (only MISS has high TTFB; no HIT)
  // ============================================================================

  @Test
  public void diagnose_originSlowMissHighTtfb_returnsOriginSlow() {
    List<QoSInfo> entries = healthyPrefix();
    // MISS with very slow origin
    entries.add(videoSlowTtfb(1_000_000L, /* br= */ 4_800, /* ttfb= */ 1_500, /* cache= */ "MISS"));
    entries.add(videoSlowTtfb(1_001_000L, /* br= */ 4_800, /* ttfb= */ 1_200, /* cache= */ "MISS"));
    QoSInfo trigger = videoTriggerWithMtp(1_002_000L, /* br= */ 1_800, /* mtp= */ 5_000);
    entries.add(trigger);

    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, entries, Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    assertThat(result.pattern).isEqualTo(Pattern.CDN_ORIGIN_SLOW);
  }

  // ============================================================================
  // Test 4 — Origin error (HTTP 5xx)
  // ============================================================================

  @Test
  public void diagnose_5xxError_returnsOriginError() {
    List<QoSInfo> entries = healthyPrefix();
    // 503 Service Unavailable on a video load
    entries.add(videoError(1_000_000L, /* br= */ 4_800, /* errorMessage= */ "HTTP 503 Service Unavailable"));
    QoSInfo trigger = videoTriggerWithMtp(1_001_000L, /* br= */ 1_800, /* mtp= */ 5_000);
    entries.add(trigger);

    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, entries, Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    assertThat(result.pattern).isEqualTo(Pattern.ORIGIN_ERROR);
  }

  // ============================================================================
  // Test 5 — ABR lag only (entries OK, just br/mtp > 0.8 at trigger)
  // ============================================================================

  @Test
  public void diagnose_abrLagOnly_returnsTransientWithAbrFlag() {
    // All entries healthy
    List<QoSInfo> entries = healthyPrefix();
    // Trigger with br=4800, mtp=2000 → ratio 2.4 → abr lag, but no other problems
    QoSInfo trigger = videoTriggerWithMtp(1_000_000L, /* br= */ 4_800, /* mtp= */ 2_000);
    entries.add(trigger);

    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, entries, Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    // No entry-level problem → TRANSIENT, but ABR flag is independent
    assertThat(result.pattern).isEqualTo(Pattern.TRANSIENT);
    assertThat(result.abrLag).isTrue();
  }

  // ============================================================================
  // Test 6 — Insufficient data (< MIN_AV_ENTRIES audio+video entries)
  // ============================================================================

  @Test
  public void diagnose_tinyWindow_returnsTransient() {
    QoSInfo a1 = audio(1_000L, /* dur= */ 100);
    QoSInfo a2 = audio(2_000L, /* dur= */ 100);
    QoSInfo trigger = videoTriggerWithMtp(3_000L, /* br= */ 1_800, /* mtp= */ 5_000);

    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, Arrays.asList(a1, a2, trigger), Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    assertThat(result.pattern).isEqualTo(Pattern.TRANSIENT);
    assertThat(result.abrLag).isFalse();
  }

  // ============================================================================
  // Test 7 — All entries healthy but rebuffer fired (codec init / seek delay)
  // ============================================================================

  @Test
  public void diagnose_allOkButRebufferFired_returnsTransient() {
    List<QoSInfo> entries = healthyPrefix();
    QoSInfo trigger = videoTriggerWithMtp(1_000_000L, /* br= */ 1_800, /* mtp= */ 5_000);
    entries.add(trigger);

    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, entries, Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    assertThat(result.pattern).isEqualTo(Pattern.TRANSIENT);
    assertThat(result.abrLag).isFalse();
  }

  // ============================================================================
  // Test 8 — Trigger entry severity tagging
  // ============================================================================

  @Test
  public void diagnose_triggerEntry_taggedAsTrigger() {
    List<QoSInfo> entries = healthyPrefix();
    QoSInfo trigger = videoTriggerWithMtp(1_000_000L, /* br= */ 1_800, /* mtp= */ 5_000);
    entries.add(trigger);

    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, entries, Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    Diagnosis.Finding triggerFinding = result.findings.get(result.findings.size() - 1);
    assertThat(triggerFinding.entry).isSameInstanceAs(trigger);
    assertThat(triggerFinding.severity).isEqualTo(Diagnosis.Severity.TRIGGER);
  }

  // ============================================================================
  // Helpers
  // ============================================================================

  /** 5 healthy A/V entries at the start of a stream. */
  private static List<QoSInfo> healthyPrefix() {
    List<QoSInfo> e = new ArrayList<>();
    e.add(audio(1_000L, /* dur= */ 109));
    e.add(video(2_000L, /* br= */ 1_800, /* dur= */ 535, /* sz= */ 463_700, /* ttfb= */ 36));
    e.add(audio(3_000L, /* dur= */ 543));
    e.add(audio(4_000L, /* dur= */ 368));
    e.add(audio(5_000L, /* dur= */ 200));
    return e;
  }

  private static QoSInfo audio(long timestampMs, long durMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_AUDIO)
        .setBitrateKbps(206)
        .setLoadDurationMs(durMs)
        .setChunkDurationMs(1_900)
        .setTtfbMs(20)
        .setBytesLoaded(45_000)
        .setMeasuredThroughputKbps(4_000)
        .setCacheStatus("MISS")
        .build();
  }

  /** Healthy V load: dur < cdur, throughput >= br × 0.7. */
  private static QoSInfo video(long timestampMs, int brKbps, long durMs, long sizeBytes, int ttfbMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(brKbps)
        .setLoadDurationMs(durMs)
        .setChunkDurationMs(1_900)
        .setTtfbMs(ttfbMs)
        .setBytesLoaded(sizeBytes)
        .setMeasuredThroughputKbps(brKbps + 2_000)
        .setCacheStatus("MISS")
        .build();
  }

  /** V load with elevated TTFB. dur/cdur intentionally normal so TTFB is the only signal. */
  private static QoSInfo videoSlowTtfb(long timestampMs, int brKbps, int ttfbMs, String cacheStatus) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(brKbps)
        // Make total duration include the slow ttfb so dur/cdur ratio is ~1.0 (TTFB
        // dominates the slowness, transfer itself is fine — this isolates the TTFB
        // signal for testing).
        .setLoadDurationMs(ttfbMs + 800)
        .setChunkDurationMs(1_900)
        .setTtfbMs(ttfbMs)
        // Throughput stays > br × 0.7 so this rule doesn't fire.
        .setBytesLoaded((long) (brKbps * 1.0 * 1900 / 8))
        .setMeasuredThroughputKbps(brKbps + 2_000)
        .setCacheStatus(cacheStatus)
        .build();
  }

  /** V load that finished with a server-side error. */
  private static QoSInfo videoError(long timestampMs, int brKbps, String errorMessage) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(brKbps)
        .setLoadDurationMs(500)
        .setChunkDurationMs(1_900)
        .setStatus(QoSInfo.LoadStatus.ERROR)
        .setErrorMessage(errorMessage)
        .setMeasuredThroughputKbps(brKbps + 2_000)
        .setCacheStatus("MISS")
        .build();
  }

  /**
   * Trigger entry — V load at the moment of rebuffer. Per-entry checks should not
   * raise issues by themselves; the {@link Diagnosis.Severity#TRIGGER} tag is the
   * marker. mtp drives the ABR lag check.
   */
  private static QoSInfo videoTrigger(long timestampMs, int brKbps, int mtpKbps) {
    return videoTriggerWithMtp(timestampMs, brKbps, mtpKbps);
  }

  private static QoSInfo videoTriggerWithMtp(long timestampMs, int brKbps, int mtpKbps) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(brKbps)
        .setLoadDurationMs(1_500)
        .setChunkDurationMs(1_900)
        .setTtfbMs(57)
        .setBytesLoaded((long) (brKbps * 1.0 * 1900 / 8))
        .setMeasuredThroughputKbps(mtpKbps)
        .setBufferStarvationFlag(true)
        .setCacheStatus("MISS")
        .build();
  }

  private static String joinIssues(Diagnosis.Finding f) {
    StringBuilder sb = new StringBuilder();
    for (String issue : f.issues) {
      sb.append(issue).append('|');
    }
    return sb.toString();
  }
}
