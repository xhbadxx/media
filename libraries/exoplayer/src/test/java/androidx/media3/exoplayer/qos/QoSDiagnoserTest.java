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
  // Test 9 — HLS muxed track type (TRACK_TYPE_DEFAULT) gets scored as segment
  // ============================================================================

  @Test
  public void diagnose_hlsMuxedTrackType_isScoredAsSegment() {
    // Mirrors Test 1 (userNetworkDegrading) but with TRACK_TYPE_DEFAULT muxed
    // entries instead of TRACK_TYPE_VIDEO. Verifies that HLS muxed segments
    // (the dominant track type on FPT-CDN HLS streams per qoe-analytic.txt 05-05)
    // are scored, not silently skipped.
    QoSInfo a1 = audio(1_000L, /* dur= */ 109);
    QoSInfo m1 = videoMuxed(2_000L, /* br= */ 1_800, /* dur= */ 535, /* sz= */ 463_700, /* ttfb= */ 36);
    QoSInfo a2 = audio(3_000L, /* dur= */ 543);
    QoSInfo a3 = audio(4_000L, /* dur= */ 368);
    QoSInfo a4 = audio(5_000L, /* dur= */ 200);
    // Two critical muxed loads at 4.8M with insufficient throughput
    QoSInfo m2 =
        videoMuxed(
            6_000L, /* br= */ 4_800, /* dur= */ 3_492, /* sz= */ 1_200_000, /* ttfb= */ 12);
    QoSInfo m3 =
        videoMuxed(
            7_000L, /* br= */ 4_800, /* dur= */ 3_786, /* sz= */ 1_000_000, /* ttfb= */ 18);
    QoSInfo trigger =
        videoTriggerWithMtp(8_000L, /* br= */ 4_800, /* mtp= */ 2_200);

    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(a1, m1, a2, a3, a4, m2, m3, trigger),
            Diagnosis.unknown());

    Diagnosis result = QoSDiagnoser.diagnose(group);

    // Before fix: muxed entries skipped (severity OK, no issues fired) →
    //             diagnose returns TRANSIENT.
    // After fix: muxed entries are scored like video → throughput < br × 0.7
    //             fires CRITICAL → pattern resolves to USER_NETWORK.
    assertThat(result.pattern).isEqualTo(Pattern.USER_NETWORK);
    // m2 was scored and contains a throughput issue (was previously skipped).
    Diagnosis.Finding m2Finding = null;
    for (Diagnosis.Finding f : result.findings) {
      if (f.entry == m2) {
        m2Finding = f;
        break;
      }
    }
    assertThat(m2Finding).isNotNull();
    assertThat(m2Finding.severity).isEqualTo(Diagnosis.Severity.CRITICAL);
    assertThat(joinIssues(m2Finding)).contains("throughput");
  }

  // ============================================================================
  // Test 10 — Window metrics: degenerate group (single entry → wall_time = 0)
  // ============================================================================

  @Test
  public void computeWindowMetrics_singleEntry_wallTimeAndRatioZero() {
    QoSInfo trigger = videoTriggerWithMtp(1_000L, /* br= */ 1_800, /* mtp= */ 5_000);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, Arrays.asList(trigger), Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics m = QoSDiagnoser.computeWindowMetrics(group);

    assertThat(m.wallTimeMs).isEqualTo(0L);
    assertThat(m.demandRatio).isEqualTo(0.0);
  }

  // ============================================================================
  // Test 11 — Window metrics: steady 1× playback (demand_ratio ≈ 1.0)
  // ============================================================================

  @Test
  public void computeWindowMetrics_steadyPlayback_demandRatioApproxOne() {
    // Five segments evenly spaced 4s apart, each carrying 4s of cdur.
    // bl steady at 8000ms throughout. Trigger has no media duration so it
    // does not contribute to ΔSupply.
    //   wall_time   = 20000ms
    //   ΔSupply     = 5 × 4000 = 20000ms
    //   Δbuffer     = 0
    //   ΔDemand     = 20000 − 0 = 20000ms
    //   ratio       = 20000 / 20000 = 1.0 (normal 1× playback)
    QoSInfo s1 = segment(0L, /* cdur= */ 4_000, /* bl= */ 8_000);
    QoSInfo s2 = segment(4_000L, 4_000, 8_000);
    QoSInfo s3 = segment(8_000L, 4_000, 8_000);
    QoSInfo s4 = segment(12_000L, 4_000, 8_000);
    QoSInfo s5 = segment(16_000L, 4_000, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(20_000L, /* bl= */ 8_000);

    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics m = QoSDiagnoser.computeWindowMetrics(group);

    assertThat(m.wallTimeMs).isEqualTo(20_000L);
    assertThat(m.deltaBufferMs).isEqualTo(0L);
    assertThat(m.deltaSupplyMs).isEqualTo(20_000L);
    assertThat(m.deltaDemandMs).isEqualTo(20_000L);
    assertThat(m.demandRatio).isWithin(0.01).of(1.0);
  }

  // ============================================================================
  // Test 12 — Window metrics: paused / abnormal playback (demand_ratio < 0.5)
  // ============================================================================

  @Test
  public void computeWindowMetrics_pausedHalfWindow_demandRatioBelowHalf() {
    // Player paused most of the 30s window: only 2 segments fetched (8000ms
    // supply), and bl dropped from 9000 to 5000 (Δbuffer = −4000) instead of
    // the full 30000 it would have at 1× speed.
    //   wall_time   = 30000ms
    //   ΔSupply     = 2 × 4000 = 8000ms
    //   Δbuffer     = 5000 − 9000 = −4000ms
    //   ΔDemand     = 8000 − (−4000) = 12000ms
    //   ratio       = 12000 / 30000 = 0.4 (well below 0.5 → abnormal)
    QoSInfo s1 = segment(0L, /* cdur= */ 4_000, /* bl= */ 9_000);
    QoSInfo s2 = segment(4_000L, 4_000, 9_000);
    QoSInfo trigger = triggerWithBlNoMedia(30_000L, /* bl= */ 5_000);

    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics m = QoSDiagnoser.computeWindowMetrics(group);

    assertThat(m.wallTimeMs).isEqualTo(30_000L);
    assertThat(m.deltaBufferMs).isEqualTo(-4_000L);
    assertThat(m.deltaSupplyMs).isEqualTo(8_000L);
    assertThat(m.deltaDemandMs).isEqualTo(12_000L);
    assertThat(m.demandRatio).isWithin(0.01).of(0.4);
  }

  // ============================================================================
  // Test 13 — Window metrics: manifest and init segments excluded from supply
  // ============================================================================

  @Test
  public void computeWindowMetrics_manifestAndInit_excludedFromSupply() {
    // Mix: 1 manifest (TRACK_TYPE_UNKNOWN, no cdur), 1 init segment
    // (TRACK_TYPE_VIDEO but cdur=0), 2 real segments cdur=4000, 1 trigger.
    // Only the 2 real segments should contribute to ΔSupply.
    QoSInfo manifest = manifestEntry(0L, /* bl= */ 8_000);
    QoSInfo init = initSegment(1_000L, /* bl= */ 8_000);
    QoSInfo s1 = segment(2_000L, /* cdur= */ 4_000, /* bl= */ 8_000);
    QoSInfo s2 = segment(6_000L, 4_000, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(10_000L, /* bl= */ 8_000);

    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(manifest, init, s1, s2, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics m = QoSDiagnoser.computeWindowMetrics(group);

    // Only s1 and s2 count → 2 × 4000 = 8000ms
    assertThat(m.deltaSupplyMs).isEqualTo(8_000L);
  }

  // ============================================================================
  // Test 14 — Sanity gate: pause / seek scenario fails (ratio < 0.7)
  // ============================================================================

  @Test
  public void applySanityGate_ratioBelowFloor_failsWithReason() {
    // Same paused fixture as Test 12: ratio = 12000/30000 = 0.4 < 0.7
    QoSInfo s1 = segment(0L, 4_000, 9_000);
    QoSInfo s2 = segment(4_000L, 4_000, 9_000);
    QoSInfo trigger = triggerWithBlNoMedia(30_000L, 5_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String reason = QoSDiagnoser.applySanityG1ate(metrics);

    assertThat(reason).isNotNull();
    assertThat(reason).contains("demand_ratio");
    assertThat(reason).contains("0.40");
  }

  // ============================================================================
  // Test 15 — Sanity gate: normal 1× playback passes
  // ============================================================================

  @Test
  public void applySanityGate_normalPlayback_passes() {
    // Same steady fixture as Test 11: ratio ≈ 1.0
    QoSInfo s1 = segment(0L, 4_000, 8_000);
    QoSInfo s2 = segment(4_000L, 4_000, 8_000);
    QoSInfo s3 = segment(8_000L, 4_000, 8_000);
    QoSInfo s4 = segment(12_000L, 4_000, 8_000);
    QoSInfo s5 = segment(16_000L, 4_000, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(20_000L, 8_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String reason = QoSDiagnoser.applySanityG1ate(metrics);

    assertThat(reason).isNull();
  }

  // ============================================================================
  // Test 16 — Sanity gate: trick play / speedup fails (ratio > 1.3)
  // ============================================================================

  @Test
  public void applySanityGate_ratioAboveCeiling_failsWithReason() {
    // Synthetic speedup: 5 segments cdur=4000 in only 12s wall time
    // (player consumed faster than wall clock).
    //   ΔSupply = 20000ms, Δbuffer = 0, ΔDemand = 20000ms, wall = 12000ms
    //   ratio = 20000 / 12000 ≈ 1.67 > 1.3 → fail
    QoSInfo s1 = segment(0L, 4_000, 8_000);
    QoSInfo s2 = segment(2_400L, 4_000, 8_000);
    QoSInfo s3 = segment(4_800L, 4_000, 8_000);
    QoSInfo s4 = segment(7_200L, 4_000, 8_000);
    QoSInfo s5 = segment(9_600L, 4_000, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(12_000L, 8_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String reason = QoSDiagnoser.applySanityG1ate(metrics);

    assertThat(reason).isNotNull();
    assertThat(reason).contains("demand_ratio");
  }

  // ============================================================================
  // Test 17 — Sanity gate: degenerate window (wall_time = 0) fails
  // ============================================================================

  @Test
  public void applySanityGate_zeroWallTime_failsWithDegenerateReason() {
    QoSInfo trigger = videoTriggerWithMtp(1_000L, 1_800, 5_000);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger, Arrays.asList(trigger), Diagnosis.unknown());

    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String reason = QoSDiagnoser.applySanityG1ate(metrics);

    assertThat(reason).isNotNull();
    assertThat(reason).contains("wall_time");
  }

  // ============================================================================
  // Test 18 — Mechanism: ORIGIN_ERROR detected (HTTP 5xx on segment)
  // ============================================================================

  @Test
  public void detectMechanism_segment5xx_returnsOriginErrorDetected() {
    QoSInfo s1 = segment(0L, 4_000, 8_000);
    QoSInfo s2 = segment(4_000L, 4_000, 8_000);
    QoSInfo errored = videoError(8_000L, /* br= */ 4_800, "HTTP 503 Service Unavailable");
    QoSInfo trigger = triggerWithBlNoMedia(12_000L, 4_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, errored, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.MechanismResult r = QoSDiagnoser.detectMechanism(group);

    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.ORIGIN_ERROR_DETECTED);
    assertThat(r.smokingGuns).contains(errored);
  }

  // ============================================================================
  // Test 19 — Mechanism: MANIFEST_FAILURE detected
  // ============================================================================

  @Test
  public void detectMechanism_manifestStatusError_returnsManifestFailureDetected() {
    QoSInfo manifest =
        new QoSInfo.Builder()
            .setTimestampMs(0L)
            .setTrackType(C.TRACK_TYPE_UNKNOWN)
            .setUrl("https://example/playlist.m3u8")
            .setStatus(QoSInfo.LoadStatus.ERROR)
            .setErrorMessage("connection timeout")
            .setBufferedDurationMs(8_000)
            .build();
    QoSInfo s1 = segment(2_000L, 4_000, 8_000);
    QoSInfo s2 = segment(6_000L, 4_000, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(10_000L, 4_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(manifest, s1, s2, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.MechanismResult r = QoSDiagnoser.detectMechanism(group);

    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.MANIFEST_FAILURE_DETECTED);
    assertThat(r.smokingGuns).contains(manifest);
  }

  // ============================================================================
  // Test 20 — Mechanism: SINGLE_SPIKE (one segment with deficit > 0.5 × initial_bl)
  // ============================================================================

  @Test
  public void detectMechanism_oneLargeDeficit_returnsSingleSpike() {
    // initial_bl = 9000. Segment s4 has loadDur=12000, cdur=4000 → deficit=8000.
    // 8000 / 9000 = 0.89 > 0.5 → SINGLE_SPIKE, smoking gun = s4.
    QoSInfo s1 = segmentWithLoadDur(0L, /* cdur= */ 4_000, /* loadDur= */ 100, /* bl= */ 9_000);
    QoSInfo s2 = segmentWithLoadDur(4_000L, 4_000, 100, 9_000);
    QoSInfo s3 = segmentWithLoadDur(8_000L, 4_000, 100, 9_000);
    QoSInfo s4 = segmentWithLoadDur(12_000L, /* cdur= */ 4_000, /* loadDur= */ 12_000, /* bl= */ 9_000);
    QoSInfo trigger = triggerWithBlNoMedia(24_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.MechanismResult r = QoSDiagnoser.detectMechanism(group);

    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.SINGLE_SPIKE);
    assertThat(r.smokingGuns).containsExactly(s4);
  }

  // ============================================================================
  // Test 21 — Mechanism: CONTINUOUS_DRAIN (majority of segments draining + bl decline)
  // ============================================================================

  @Test
  public void detectMechanism_majorityDrainPlusBlDecline_returnsContinuousDrain() {
    // 6 segments, each loadDur slightly > cdur (small positive deficit).
    // bl monotonically declining (each segment captures lower bl than the prior).
    // No single deficit dominates (max < 0.5 × bl[0]) → not SPIKE.
    // drain_count = 6 / 6 = 100% > 50% AND bl declining → CONTINUOUS_DRAIN.
    QoSInfo s1 = segmentWithLoadDur(0L, /* cdur= */ 4_000, /* loadDur= */ 4_500, /* bl= */ 9_000);
    QoSInfo s2 = segmentWithLoadDur(4_500L, 4_000, 4_500, 8_500);
    QoSInfo s3 = segmentWithLoadDur(9_000L, 4_000, 4_500, 8_000);
    QoSInfo s4 = segmentWithLoadDur(13_500L, 4_000, 4_500, 7_500);
    QoSInfo s5 = segmentWithLoadDur(18_000L, 4_000, 4_500, 7_000);
    QoSInfo s6 = segmentWithLoadDur(22_500L, 4_000, 4_500, 6_500);
    QoSInfo trigger = triggerWithBlNoMedia(27_000L, 6_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, s6, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.MechanismResult r = QoSDiagnoser.detectMechanism(group);

    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.CONTINUOUS_DRAIN);
    // All 6 segments contribute drain (each deficit = 500ms > 0)
    assertThat(r.smokingGuns).hasSize(6);
  }

  // ============================================================================
  // Test 22 — Mechanism: MANIFEST_SLOW (≥2 manifest entries with loadDur > 1000ms)
  // ============================================================================

  @Test
  public void detectMechanism_twoSlowManifests_returnsManifestSlow() {
    QoSInfo m1 = slowManifest(0L, /* loadDur= */ 1_500, /* bl= */ 8_000);
    QoSInfo m2 = slowManifest(4_000L, 1_800, 8_000);
    QoSInfo s1 = segment(2_000L, 4_000, 8_000);
    QoSInfo s2 = segment(6_000L, 4_000, 8_000);
    QoSInfo s3 = segment(10_000L, 4_000, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(14_000L, 8_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(m1, s1, m2, s2, s3, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.MechanismResult r = QoSDiagnoser.detectMechanism(group);

    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.MANIFEST_SLOW_DETECTED);
    assertThat(r.smokingGuns).containsAtLeast(m1, m2);
  }

  // ============================================================================
  // Test 23 — Mechanism: NONE (healthy session, no observable drain)
  // ============================================================================

  @Test
  public void detectMechanism_healthySession_returnsNone() {
    // Mirrors qoe-analytic.txt 05-05: bl steady around 8-12s, all loads
    // well under cdur (deficit negative — buffer filling).
    QoSInfo s1 = segmentWithLoadDur(0L, 4_000, 124, 8_800);
    QoSInfo s2 = segmentWithLoadDur(4_000L, 4_000, 37, 12_700);
    QoSInfo s3 = segmentWithLoadDur(8_000L, 4_000, 47, 8_800);
    QoSInfo s4 = segmentWithLoadDur(12_000L, 4_000, 35, 8_600);
    QoSInfo s5 = segmentWithLoadDur(16_000L, 4_000, 44, 8_800);
    QoSInfo trigger = triggerWithBlNoMedia(20_000L, 8_700);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.MechanismResult r = QoSDiagnoser.detectMechanism(group);

    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.NONE);
    assertThat(r.smokingGuns).isEmpty();
  }

  // ============================================================================
  // Test 24 — Mechanism: INSUFFICIENT_LOG (< 3 segments to verdict)
  // ============================================================================

  @Test
  public void detectMechanism_lessThanThreeSegments_returnsInsufficientLog() {
    QoSInfo s1 = segment(0L, 4_000, 8_000);
    QoSInfo s2 = segment(4_000L, 4_000, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(8_000L, 8_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.MechanismResult r = QoSDiagnoser.detectMechanism(group);

    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.INSUFFICIENT_LOG);
    assertThat(r.smokingGuns).isEmpty();
  }

  // ============================================================================
  // Test 25 — Attribute: Phase 2 deterministic (postTtfb < bitrate → CLIENT_BANDWIDTH)
  // ============================================================================

  @Test
  public void attribute_postTtfbBelowBitrate_returnsClientBandwidth() {
    // Smoking gun: bytes=450KB, ttfb=9ms, loadDur=12000ms → transferMs=11991ms
    // → postTtfb = 450000×8/11991 ≈ 300 kbps. bitrate = 850 → fires trigger 1.
    QoSInfo sg =
        new QoSInfo.Builder()
            .setTimestampMs(10_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(850)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(12_000)
            .setTtfbMs(9)
            .setBytesLoaded(450_000)
            .build();
    List<QoSInfo> prior = Arrays.asList(segment(0L, 4_000, 9_000), segment(4_000L, 4_000, 9_000));

    QoSDiagnoser.AttributionResult r =
        QoSDiagnoser.attribute(Arrays.asList(sg), prior);

    assertThat(r.perGun.get(sg)).isEqualTo(QoSDiagnoser.Attribution.CLIENT_BANDWIDTH);
  }

  // ============================================================================
  // Test 26 — Attribute: degradation vs prior baseline (postTtfb < median × 0.3)
  // ============================================================================

  @Test
  public void attribute_postTtfbDroppedFromBaseline_returnsClientBandwidthDegradation() {
    // Prior segments deliver ~12000 kbps each (300KB / 200ms × 8 = 12000).
    // → prior_median_postTtfb = 12000 kbps
    // Smoking gun: 337KB / 900ms × 8 = 3000 kbps
    //   • > bitrate 1800 → trigger 1 NO
    //   • < prior_median × 0.3 = 3600 → trigger 2 FIRES
    QoSInfo p1 = priorSegmentWithPostTtfb(0L, /* bytes= */ 300_000, /* loadDur= */ 200);
    QoSInfo p2 = priorSegmentWithPostTtfb(2_000L, 300_000, 200);
    QoSInfo p3 = priorSegmentWithPostTtfb(4_000L, 300_000, 200);
    QoSInfo sg =
        new QoSInfo.Builder()
            .setTimestampMs(10_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(1_800)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(1_000)
            .setTtfbMs(100)
            .setBytesLoaded(337_500)
            .build();

    QoSDiagnoser.AttributionResult r =
        QoSDiagnoser.attribute(Arrays.asList(sg), Arrays.asList(p1, p2, p3));

    assertThat(r.perGun.get(sg)).isEqualTo(QoSDiagnoser.Attribution.CLIENT_BANDWIDTH_DEGRADATION);
    assertThat(r.priorMedianPostTtfbKbps).isEqualTo(12_000L);
  }

  // ============================================================================
  // Test 27 — Attribute: TTFB dominant (ttfb/loadDur > 0.7 + postTtfb OK)
  // ============================================================================

  @Test
  public void attribute_ttfbDominantPhase2Healthy_returnsSlowResponseStart() {
    // Smoking gun: cdur=2000, loadDur=2500, ttfb=2000 → ttfb share 0.8 > 0.7
    // bytes=1MB, transferMs=500 → postTtfb = 16000 kbps > br 4800 → trigger 1 NO
    // prior_median modest (4000 kbps) → 16000 NOT below 4000×0.3=1200 → trigger 2 NO
    // → SLOW_RESPONSE_START
    QoSInfo p1 = priorSegmentWithPostTtfb(0L, /* bytes= */ 100_000, /* loadDur= */ 200); // 4000
    QoSInfo p2 = priorSegmentWithPostTtfb(2_000L, 100_000, 200);
    QoSInfo sg =
        new QoSInfo.Builder()
            .setTimestampMs(10_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(4_800)
            .setChunkDurationMs(2_000)
            .setLoadDurationMs(2_500)
            .setTtfbMs(2_000)
            .setBytesLoaded(1_000_000)
            .build();

    QoSDiagnoser.AttributionResult r =
        QoSDiagnoser.attribute(Arrays.asList(sg), Arrays.asList(p1, p2));

    assertThat(r.perGun.get(sg)).isEqualTo(QoSDiagnoser.Attribution.SLOW_RESPONSE_START);
  }

  // ============================================================================
  // Test 28 — Attribute: status ERROR → ORIGIN_ERROR (priority over Phase 2)
  // ============================================================================

  @Test
  public void attribute_statusError_returnsOriginErrorRegardlessOfPhase2() {
    QoSInfo sg =
        new QoSInfo.Builder()
            .setTimestampMs(10_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(4_800)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(500)
            .setStatus(QoSInfo.LoadStatus.ERROR)
            .setErrorMessage("HTTP 503 Service Unavailable")
            .setBytesLoaded(0)
            .build();
    List<QoSInfo> prior = Arrays.asList(segment(0L, 4_000, 9_000), segment(4_000L, 4_000, 9_000));

    QoSDiagnoser.AttributionResult r =
        QoSDiagnoser.attribute(Arrays.asList(sg), prior);

    assertThat(r.perGun.get(sg)).isEqualTo(QoSDiagnoser.Attribution.ORIGIN_ERROR);
  }

  // ============================================================================
  // Test 29 — Attribute: bytesLoaded = 0 → UNKNOWN (cannot derive postTtfb)
  // ============================================================================

  @Test
  public void attribute_zeroBytesLoaded_returnsUnknown() {
    QoSInfo sg =
        new QoSInfo.Builder()
            .setTimestampMs(10_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(4_800)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(5_000)
            .setTtfbMs(100)
            .setBytesLoaded(0)
            .build();
    List<QoSInfo> prior = Arrays.asList(segment(0L, 4_000, 9_000), segment(4_000L, 4_000, 9_000));

    QoSDiagnoser.AttributionResult r =
        QoSDiagnoser.attribute(Arrays.asList(sg), prior);

    assertThat(r.perGun.get(sg)).isEqualTo(QoSDiagnoser.Attribution.UNKNOWN);
  }

  // ============================================================================
  // Test 30 — Attribute: MIXED (no trigger fires, deficit caused by something else)
  // ============================================================================

  @Test
  public void attribute_noTriggerFires_returnsMixed() {
    // Smoking gun has small deficit (loadDur 5000 vs cdur 4000 → +1000) but:
    //   • postTtfb = 3500000×8/4500 ≈ 6222 kbps > bitrate 4800 → trigger 1 NO
    //   • > prior_median×0.3 → trigger 2 NO
    //   • ttfb 500/loadDur 5000 = 0.1 < 0.7 → SLOW_RESPONSE_START NO
    //   → MIXED
    QoSInfo sg =
        new QoSInfo.Builder()
            .setTimestampMs(10_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(4_800)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(5_000)
            .setTtfbMs(500)
            .setBytesLoaded(3_500_000)
            .build();
    List<QoSInfo> prior =
        Arrays.asList(
            priorSegmentWithPostTtfb(0L, 100_000, 200),
            priorSegmentWithPostTtfb(2_000L, 100_000, 200));

    QoSDiagnoser.AttributionResult r =
        QoSDiagnoser.attribute(Arrays.asList(sg), prior);

    assertThat(r.perGun.get(sg)).isEqualTo(QoSDiagnoser.Attribution.MIXED);
  }

  // ============================================================================
  // Test 31 — Cause: ORIGIN_ERROR
  // ============================================================================

  @Test
  public void diagnoseFully_segment5xx_returnsOriginErrorCause() {
    QoSInfo s1 = segmentWithLoadDur(0L, 4_000, 200, 9_000);
    QoSInfo s2 = segmentWithLoadDur(4_000L, 4_000, 200, 9_000);
    QoSInfo errored = videoError(8_000L, /* br= */ 4_800, "HTTP 503");
    QoSInfo trigger = triggerWithBlNoMedia(12_000L, 4_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, errored, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.ORIGIN_ERROR);
    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.ORIGIN_ERROR_DETECTED);
    assertThat(r.smokingGuns).contains(errored);
  }

  // ============================================================================
  // Test 32 — Cause: MANIFEST_FAILURE
  // ============================================================================

  @Test
  public void diagnoseFully_manifestStatusError_returnsManifestFailureCause() {
    QoSInfo manifest =
        new QoSInfo.Builder()
            .setTimestampMs(0L)
            .setTrackType(C.TRACK_TYPE_UNKNOWN)
            .setUrl("https://example/playlist.m3u8")
            .setStatus(QoSInfo.LoadStatus.ERROR)
            .setErrorMessage("connection timeout")
            .setBufferedDurationMs(8_000)
            .build();
    QoSInfo s1 = segmentWithLoadDur(2_000L, 4_000, 200, 8_000);
    QoSInfo s2 = segmentWithLoadDur(6_000L, 4_000, 200, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(10_000L, 4_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(manifest, s1, s2, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.MANIFEST_FAILURE);
  }

  // ============================================================================
  // Test 33 — Cause: MANIFEST_SLOW
  // ============================================================================

  @Test
  public void diagnoseFully_twoSlowManifests_returnsManifestSlowCause() {
    QoSInfo m1 = slowManifest(0L, 1_500, 8_000);
    QoSInfo m2 = slowManifest(4_000L, 1_800, 8_000);
    QoSInfo s1 = segmentWithLoadDur(2_000L, 4_000, 200, 8_000);
    QoSInfo s2 = segmentWithLoadDur(6_000L, 4_000, 200, 8_000);
    QoSInfo s3 = segmentWithLoadDur(10_000L, 4_000, 200, 8_000);
    QoSInfo trigger = triggerWithBlNoMedia(14_000L, 8_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(m1, s1, m2, s2, s3, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.MANIFEST_SLOW);
  }

  // ============================================================================
  // Test 34 — Cause: CLIENT_BANDWIDTH (SINGLE_SPIKE + Phase 2 deterministic)
  // ============================================================================

  @Test
  public void diagnoseFully_singleSpikeWithPhase2Deficit_returnsClientBandwidthCause() {
    // 5 healthy + 1 spike: s6 loadDur=12000, ttfb=9 → transfer phase 11991ms.
    // bytes=450KB → postTtfb ≈ 300 kbps < bitrate 850 → CLIENT_BANDWIDTH attribution.
    // SINGLE_SPIKE + CLIENT_BANDWIDTH → CLIENT_BANDWIDTH cause.
    QoSInfo s1 = priorSegmentWithPostTtfb(0L, /* bytes= */ 300_000, /* loadDur= */ 200);
    QoSInfo s2 = priorSegmentWithPostTtfb(4_000L, 300_000, 200);
    QoSInfo s3 = priorSegmentWithPostTtfb(8_000L, 300_000, 200);
    QoSInfo s4 = priorSegmentWithPostTtfb(12_000L, 300_000, 200);
    QoSInfo s5 = priorSegmentWithPostTtfb(16_000L, 300_000, 200);
    QoSInfo spike =
        new QoSInfo.Builder()
            .setTimestampMs(20_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(850)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(12_000)
            .setTtfbMs(9)
            .setBytesLoaded(450_000)
            .setBufferedDurationMs(8_000) // bl just before the spike
            .build();
    QoSInfo trigger = triggerWithBlNoMedia(32_000L, /* bl= */ 0);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, spike, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.CLIENT_BANDWIDTH);
    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.SINGLE_SPIKE);
    assertThat(r.smokingGuns).containsExactly(spike);
  }

  // ============================================================================
  // Test 35 — Cause: CDN_SLOW_DELIVERY (CONTINUOUS_DRAIN + ≥2 SLOW_RESPONSE_START)
  // ============================================================================

  @Test
  public void diagnoseFully_continuousDrainWithSlowResponseStart_returnsCdnSlowDelivery() {
    // 3 segments: cdur=2000, loadDur=2500 (deficit +500), ttfb=2000 (TTFB dominant 0.8 > 0.7)
    // bytes=1MB, transferMs=500 → postTtfb=16000 kbps > br 4800 → SLOW_RESPONSE_START
    // No CLIENT_BANDWIDTH attribution → CDN_SLOW_DELIVERY cause.
    QoSInfo s1 = ttfbDominantSegment(0L, /* bl= */ 8_000);
    QoSInfo s2 = ttfbDominantSegment(2_500L, 7_500);
    QoSInfo s3 = ttfbDominantSegment(5_000L, 7_000);
    QoSInfo trigger = triggerWithBlNoMedia(7_500L, 6_500);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.CDN_SLOW_DELIVERY);
    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.CONTINUOUS_DRAIN);
  }

  // ============================================================================
  // Test 36 — Cause: INSUFFICIENT_DATA (SINGLE_SPIKE + SLOW_RESPONSE_START)
  // ============================================================================

  @Test
  public void diagnoseFully_singleSpikeWithSlowResponseStart_returnsInsufficientData() {
    // 5 healthy + 1 spike with ttfb dominant + Phase 2 healthy.
    // SINGLE_SPIKE + SLOW_RESPONSE_START attribution → INSUFFICIENT_DATA cause.
    QoSInfo s1 = priorSegmentWithPostTtfb(0L, 300_000, 200);
    QoSInfo s2 = priorSegmentWithPostTtfb(4_000L, 300_000, 200);
    QoSInfo s3 = priorSegmentWithPostTtfb(8_000L, 300_000, 200);
    QoSInfo s4 = priorSegmentWithPostTtfb(12_000L, 300_000, 200);
    QoSInfo s5 = priorSegmentWithPostTtfb(16_000L, 300_000, 200);
    QoSInfo spike =
        new QoSInfo.Builder()
            .setTimestampMs(20_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(4_800)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(12_000)
            .setTtfbMs(10_000) // 10000/12000 = 0.83 > 0.7 → TTFB dominant
            .setBytesLoaded(2_000_000) // postTtfb = 8000 kbps > bitrate 4800
            .setBufferedDurationMs(8_000)
            .build();
    QoSInfo trigger = triggerWithBlNoMedia(32_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, spike, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.INSUFFICIENT_DATA);
    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.SINGLE_SPIKE);
  }

  // ============================================================================
  // Test 37 — Cause: TRANSIENT (no observable drain)
  // ============================================================================

  @Test
  public void diagnoseFully_healthySession_returnsTransient() {
    QoSInfo s1 = segmentWithLoadDur(0L, 4_000, 124, 8_800);
    QoSInfo s2 = segmentWithLoadDur(4_000L, 4_000, 37, 8_800);
    QoSInfo s3 = segmentWithLoadDur(8_000L, 4_000, 47, 8_800);
    QoSInfo s4 = segmentWithLoadDur(12_000L, 4_000, 35, 8_800);
    QoSInfo s5 = segmentWithLoadDur(16_000L, 4_000, 44, 8_800);
    QoSInfo trigger = triggerWithBlNoMedia(20_000L, 8_700);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.TRANSIENT);
  }

  // ============================================================================
  // Test 38 — ABR cross-cut: br/mtp > 0.8 → abrLag = true (independent of cause)
  // ============================================================================

  @Test
  public void diagnoseFully_triggerBrOverMtpHigh_setsAbrLagFlag() {
    // Healthy fixture so the sanity gate passes, but trigger has br/mtp = 4800/2000 = 2.4 > 0.8
    QoSInfo s1 = segmentWithLoadDur(0L, 4_000, 124, 8_800);
    QoSInfo s2 = segmentWithLoadDur(4_000L, 4_000, 37, 8_800);
    QoSInfo s3 = segmentWithLoadDur(8_000L, 4_000, 47, 8_800);
    QoSInfo s4 = segmentWithLoadDur(12_000L, 4_000, 35, 8_800);
    QoSInfo s5 = segmentWithLoadDur(16_000L, 4_000, 44, 8_800);
    QoSInfo trigger =
        new QoSInfo.Builder()
            .setTimestampMs(20_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(4_800)
            .setMeasuredThroughputKbps(2_000)
            .setBufferedDurationMs(8_700)
            .setBufferStarvationFlag(true)
            .build();
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.abrLag).isTrue();
  }

  // ============================================================================
  // Test 39 — Conclusion: CLIENT_BANDWIDTH cites postTtfb + bitrate + smoking gun
  // ============================================================================

  @Test
  public void buildConclusion_clientBandwidth_citesPostTtfbAndBitrate() {
    QoSInfo s1 = priorSegmentWithPostTtfb(0L, 300_000, 200);
    QoSInfo s2 = priorSegmentWithPostTtfb(4_000L, 300_000, 200);
    QoSInfo s3 = priorSegmentWithPostTtfb(8_000L, 300_000, 200);
    QoSInfo s4 = priorSegmentWithPostTtfb(12_000L, 300_000, 200);
    QoSInfo s5 = priorSegmentWithPostTtfb(16_000L, 300_000, 200);
    QoSInfo spike =
        new QoSInfo.Builder()
            .setTimestampMs(20_000L)
            .setTrackType(C.TRACK_TYPE_VIDEO)
            .setBitrateKbps(850)
            .setChunkDurationMs(4_000)
            .setLoadDurationMs(12_000)
            .setTtfbMs(9)
            .setBytesLoaded(450_000)
            .setBufferedDurationMs(8_000)
            .build();
    QoSInfo trigger = triggerWithBlNoMedia(32_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, spike, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis fd = QoSDiagnoser.diagnoseFully(group);
    String conclusion = QoSDiagnoser.buildConclusion(fd, trigger);

    assertThat(conclusion).contains("CLIENT_BANDWIDTH");
    assertThat(conclusion).contains("SINGLE_SPIKE");
    assertThat(conclusion).contains("postTtfb"); // derived metric label
    assertThat(conclusion).contains("300"); // postTtfb value
    assertThat(conclusion).contains("850"); // bitrate value
    assertThat(conclusion).contains("00:00:20.000"); // smoking gun timestamp (formatted)
  }

  // ============================================================================
  // Test 40 — Conclusion: CDN_SLOW_DELIVERY includes caveat about server timing
  // ============================================================================

  @Test
  public void buildConclusion_cdnSlowDelivery_includesUpstreamTimingCaveat() {
    QoSInfo s1 = ttfbDominantSegment(0L, 8_000);
    QoSInfo s2 = ttfbDominantSegment(2_500L, 7_500);
    QoSInfo s3 = ttfbDominantSegment(5_000L, 7_000);
    QoSInfo trigger = triggerWithBlNoMedia(7_500L, 6_500);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis fd = QoSDiagnoser.diagnoseFully(group);
    String conclusion = QoSDiagnoser.buildConclusion(fd, trigger);

    assertThat(conclusion).contains("CDN_SLOW_DELIVERY");
    // Caveat text must mention the missing server signal so support engineers
    // know what would resolve the ambiguity.
    assertThat(conclusion).contains("upstream_response_time");
  }

  // ============================================================================
  // Test 41 — Conclusion: ORIGIN_ERROR cites the error entry's status / url
  // ============================================================================

  @Test
  public void buildConclusion_originError_citesErrorEntry() {
    QoSInfo s1 = segmentWithLoadDur(0L, 4_000, 200, 9_000);
    QoSInfo s2 = segmentWithLoadDur(4_000L, 4_000, 200, 9_000);
    QoSInfo errored = videoError(8_000L, /* br= */ 4_800, "HTTP 503 Service Unavailable");
    QoSInfo trigger = triggerWithBlNoMedia(12_000L, 4_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, errored, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis fd = QoSDiagnoser.diagnoseFully(group);
    String conclusion = QoSDiagnoser.buildConclusion(fd, trigger);

    assertThat(conclusion).contains("ORIGIN_ERROR");
    assertThat(conclusion).contains("503");
    assertThat(conclusion).contains("00:00:08.000"); // errored entry timestamp
  }

  // ============================================================================
  // Test 42 — Conclusion: sanity gate failure surfaces the gate reason
  // ============================================================================

  @Test
  public void buildConclusion_sanityGateFailed_surfacesReason() {
    // Same paused fixture as Test 12: ratio = 0.4 < 0.7
    QoSInfo s1 = segment(0L, 4_000, 9_000);
    QoSInfo s2 = segment(4_000L, 4_000, 9_000);
    QoSInfo trigger = triggerWithBlNoMedia(30_000L, 5_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis fd = QoSDiagnoser.diagnoseFully(group);
    String conclusion = QoSDiagnoser.buildConclusion(fd, trigger);

    assertThat(conclusion).contains("TRANSIENT");
    assertThat(conclusion).contains("demand_ratio");
    assertThat(conclusion).contains("0.40");
  }

  // ============================================================================
  // Test 43 — Worked example §VII Ex 3: continuous drain + client throttle
  // ============================================================================

  @Test
  public void diagnoseFully_continuousDrainWithLowPostTtfb_returnsClientBandwidth() {
    // 6 segments cdur=4000, loadDur=4500 (deficit +500 each), bytes sized so
    // postTtfb is slightly below bitrate.
    //   bytes = 4500 × 1500 / 8 = 843_750 → postTtfb = 843_750 × 8 / 4490
    //                                              ≈ 1503 kbps < bitrate 1800
    // bl monotonically declining 9000 → 6500 → CONTINUOUS_DRAIN.
    // Every smoking-gun gets CLIENT_BANDWIDTH attribution → CLIENT_BANDWIDTH cause.
    QoSInfo s1 = throttledSegment(0L, /* bl= */ 9_000);
    QoSInfo s2 = throttledSegment(4_500L, 8_500);
    QoSInfo s3 = throttledSegment(9_000L, 8_000);
    QoSInfo s4 = throttledSegment(13_500L, 7_500);
    QoSInfo s5 = throttledSegment(18_000L, 7_000);
    QoSInfo s6 = throttledSegment(22_500L, 6_500);
    QoSInfo trigger = triggerWithBlNoMedia(27_000L, 6_000);
    RebufferGroup group =
        new RebufferGroup(
            1,
            trigger.timestampMs,
            trigger,
            Arrays.asList(s1, s2, s3, s4, s5, s6, trigger),
            Diagnosis.unknown());

    QoSDiagnoser.FullDiagnosis r = QoSDiagnoser.diagnoseFully(group);

    assertThat(r.cause).isEqualTo(QoSDiagnoser.Cause.CLIENT_BANDWIDTH);
    assertThat(r.mechanism).isEqualTo(QoSDiagnoser.Mechanism.CONTINUOUS_DRAIN);
    assertThat(r.smokingGuns).hasSize(6);
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

  /**
   * HLS muxed load (TRACK_TYPE_DEFAULT with combined V+A codec). Mirrors {@link
   * #video} but uses the muxed track type, which is how Media3 tags HLS .ts
   * segments containing both audio and video streams. Used to verify that the
   * diagnoser scores muxed loads instead of silently skipping them.
   */
  private static QoSInfo videoMuxed(
      long timestampMs, int brKbps, long durMs, long sizeBytes, int ttfbMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_DEFAULT)
        .setCodec("avc1.64001f, mp4a.40.2")
        .setBitrateKbps(brKbps)
        .setLoadDurationMs(durMs)
        .setChunkDurationMs(1_900)
        .setTtfbMs(ttfbMs)
        .setBytesLoaded(sizeBytes)
        .setMeasuredThroughputKbps(brKbps + 2_000)
        .setCacheStatus("HIT")
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

  /**
   * Generic V segment with explicit chunk duration and buffer length, used by
   * window-metric tests that need precise control of ΔSupply and Δbuffer.
   */
  private static QoSInfo segment(long timestampMs, long cdurMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(1_800)
        .setChunkDurationMs(cdurMs)
        .setBufferedDurationMs(blMs)
        .setLoadDurationMs(100)
        .setBytesLoaded(50_000)
        .setTtfbMs(10)
        .build();
  }

  /**
   * V segment with explicit {@code loadDur} so mechanism-detection tests can
   * control the per-segment deficit ({@code loadDur − cdur}).
   */
  private static QoSInfo segmentWithLoadDur(
      long timestampMs, long cdurMs, long loadDurMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(1_800)
        .setChunkDurationMs(cdurMs)
        .setBufferedDurationMs(blMs)
        .setLoadDurationMs(loadDurMs)
        .setBytesLoaded((long) (1_800 * cdurMs / 8))
        .setTtfbMs(10)
        .build();
  }

  /**
   * V segment with explicit bytes and loadDur so attribution tests can compute
   * a precise post-TTFB throughput. Sets {@code chunkDurationMs > 0} so the
   * entry passes {@code isCompletedSegment} and contributes to prior median.
   */
  private static QoSInfo priorSegmentWithPostTtfb(
      long timestampMs, long bytesLoaded, long loadDurMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(1_800)
        .setChunkDurationMs(4_000)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(0)
        .setBytesLoaded(bytesLoaded)
        .setBufferedDurationMs(8_000)
        .build();
  }

  /**
   * Throttled-pipe segment: small per-segment deficit (loadDur slightly > cdur),
   * Phase 2 throughput just below bitrate. Used by Spec §VII Ex 3 fixture to
   * demonstrate CONTINUOUS_DRAIN attributed to CLIENT_BANDWIDTH on every entry.
   */
  private static QoSInfo throttledSegment(long timestampMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(1_800)
        .setChunkDurationMs(4_000)
        .setLoadDurationMs(4_500) // deficit = +500
        .setTtfbMs(10)
        .setBytesLoaded(843_750) // postTtfb ≈ 1503 kbps < bitrate 1800
        .setBufferedDurationMs(blMs)
        .build();
  }

  /**
   * Segment where Phase 1 (TTFB) dominates the load: ttfb/loadDur > 0.7 with
   * Phase 2 throughput well above bitrate. Used by CDN_SLOW_DELIVERY tests
   * where the expected attribution is SLOW_RESPONSE_START.
   */
  private static QoSInfo ttfbDominantSegment(long timestampMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(4_800)
        .setChunkDurationMs(2_000)
        .setLoadDurationMs(2_500) // deficit = +500
        .setTtfbMs(2_000)         // 2000/2500 = 0.8 > 0.7
        .setBytesLoaded(1_000_000) // postTtfb = 16,000 kbps > br
        .setBufferedDurationMs(blMs)
        .build();
  }

  /** Manifest entry with elevated loadDur to trigger MANIFEST_SLOW detection. */
  private static QoSInfo slowManifest(long timestampMs, long loadDurMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_UNKNOWN)
        .setUrl("https://example/playlist.m3u8")
        .setLoadDurationMs(loadDurMs)
        .setBytesLoaded(2_000)
        .setTtfbMs(50)
        .setBufferedDurationMs(blMs)
        .build();
  }

  /**
   * Trigger entry that intentionally omits {@code chunkDurationMs} so it does
   * not contribute to ΔSupply. Used by window-metric tests where we control the
   * total supply via the prior segments.
   */
  private static QoSInfo triggerWithBlNoMedia(long timestampMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(1_800)
        .setBufferedDurationMs(blMs)
        .setBufferStarvationFlag(true)
        .build();
  }

  /**
   * Manifest entry — track type unknown, no chunk duration. Should be excluded
   * from supply because it carries no playable media.
   */
  private static QoSInfo manifestEntry(long timestampMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_UNKNOWN)
        .setUrl("https://example/playlist.m3u8")
        .setBufferedDurationMs(blMs)
        .setLoadDurationMs(20)
        .setBytesLoaded(2_000)
        .setTtfbMs(8)
        .build();
  }

  /**
   * Init segment — has a track type but no media duration ({@code cdur ≤ 0}).
   * Should be excluded from supply since it doesn't carry playable seconds.
   */
  private static QoSInfo initSegment(long timestampMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(timestampMs)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setBitrateKbps(1_800)
        .setBufferedDurationMs(blMs)
        .setLoadDurationMs(50)
        .setBytesLoaded(800)
        .setTtfbMs(12)
        // chunkDurationMs intentionally not set (defaults to -1) — init carries
        // codec headers, not media duration.
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
