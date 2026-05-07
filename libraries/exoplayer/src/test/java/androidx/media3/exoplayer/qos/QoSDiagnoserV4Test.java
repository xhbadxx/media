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
import androidx.media3.exoplayer.qos.model.DiagnosisV4;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class QoSDiagnoserV4Test {

  // ===== Defensive cases =====

  @Test
  public void diagnose_nullGroup_returnsTransient() {
    DiagnosisV4 r = QoSDiagnoserV4.diagnose(null);
    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.TRANSIENT);
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
    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);
    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.TRANSIENT);
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

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.CDN_HTTP_ERROR);
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

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.CDN_HTTP_ERROR);
    // 5xx first, 4xx after.
    assertThat(r.httpErrorCodes).asList().containsExactly(503, 504, 404).inOrder();
  }

  @Test
  public void diagnose_only4xx_doesNotFireCdnHttpError() {
    // 4xx with rest healthy → should fall to TRANSIENT (no rate anomaly), but 4xx
    // codes still surface in evidence for debug context.
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

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isNotEqualTo(DiagnosisV4.Cause.CDN_HTTP_ERROR);
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

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.TRANSIENT);
    assertThat(r.sanityFailReason).isNotNull();
  }

  // ===== CDN_DELIVERY_SLOW: TTFB outlier + healthy delivery =====

  @Test
  public void diagnose_majorityTtfbOutlierWithHealthyDelivery_returnsCdnDeliverySlow() {
    // 4 V-segments cdur=2000, 3 with TTFB > 800ms but post-TTFB rate ≥ 90% bitrate.
    // Window math: ts gap=2500, bl drops 1500→0 → wallTime=10000, supply=8000,
    //   Δbuffer=-1500, demand=9500, ratio=0.95 ⇒ pass sanity gate.
    QoSInfo s1 =
        cdnEdgeSlowSegment(/* ts= */ 1_000L, /* loadDurMs= */ 2_700L,
            /* ttfbMs= */ 1_500, /* bytes= */ 200_000L, /* blMs= */ 1_500);
    QoSInfo s2 =
        cdnEdgeSlowSegment(/* ts= */ 3_500L, /* loadDurMs= */ 2_900L,
            /* ttfbMs= */ 1_700, /* bytes= */ 200_000L, /* blMs= */ 1_000);
    QoSInfo s3 =
        cdnEdgeSlowSegment(/* ts= */ 6_000L, /* loadDurMs= */ 2_200L,
            /* ttfbMs= */ 1_100, /* bytes= */ 200_000L, /* blMs= */ 500);
    // Healthy seg (low TTFB).
    QoSInfo s4 =
        cdnEdgeSlowSegment(/* ts= */ 8_500L, /* loadDurMs= */ 1_900L,
            /* ttfbMs= */ 350, /* bytes= */ 250_000L, /* blMs= */ 200);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nVSegments).isEqualTo(4);
    assertThat(r.nCdnEvidenceSegments).isAtLeast(3);
    assertThat(r.nTtfbOutlierSegments).isAtLeast(3);
  }

  @Test
  public void diagnose_singleTtfbOutlier_doesNotFireCdnDeliverySlow() {
    // Only 1/4 segments with TTFB outlier → not majority, falls through to bandwidth check.
    QoSInfo s1 =
        cdnEdgeSlowSegment(1_000L, 2_700L, /* ttfbMs= */ 1_500, 200_000L, 1_500);
    // Rest healthy load with normal TTFB.
    QoSInfo s2 = scoredSegment(3_500L, 2_000L, 1_700L, 200, 1_000);
    QoSInfo s3 = scoredSegment(6_000L, 2_000L, 1_700L, 200, 500);
    QoSInfo s4 = scoredSegment(8_500L, 2_000L, 1_700L, 200, 200);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isNotEqualTo(DiagnosisV4.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nTtfbOutlierSegments).isEqualTo(1);
  }

  // ===== INSUFFICIENT_BANDWIDTH =====

  @Test
  public void diagnose_v8RebufferOne_returnsInsufficientBandwidth() {
    // Faithful reproduction of v8 dataset rebuffer #1 V-segments.
    // cdur=1900ms, normal TTFB (< 800), bytes/dur ratios produce ~80-95% delivery health.
    // Window math: ts gap=2500, bl 2000→0 over 4 → wallTime=10000, supply=7600,
    //   Δbuffer=-2000, demand=9600, ratio=0.96 ⇒ pass sanity.
    QoSInfo s1 = vodSegment(/* ts= */ 1_000L, /* cdurMs= */ 1_900L,
        /* loadDurMs= */ 2_483L, /* ttfbMs= */ 329, /* bytes= */ 220_160L, /* blMs= */ 2_000);
    QoSInfo s2 = vodSegment(3_500L, 1_900L, 2_365L, 257, 243_712L, 1_500);
    QoSInfo s3 = vodSegment(6_000L, 1_900L, 2_481L, 342, 243_814L, 1_000);
    QoSInfo s4 = vodSegment(8_500L, 1_900L, 2_726L, 411, 273_408L, 500);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.INSUFFICIENT_BANDWIDTH);
    assertThat(r.nVSegments).isEqualTo(4);
    assertThat(r.nSlowSegments).isEqualTo(4);
    assertThat(r.medianExcessRatio).isGreaterThan(0.10);
    assertThat(r.nCdnEvidenceSegments).isEqualTo(0); // no TTFB > 800
  }

  @Test
  public void diagnose_minoritySlow_returnsTransient() {
    // 4 healthy V-segments + 1 outlier slow.
    // Window math: ts gap=2500, bl 1500→0 over 5 → wallTime=12500, supply=10000,
    //   Δbuffer=-1500, demand=11500, ratio=0.92 ⇒ pass sanity.
    QoSInfo s1 = scoredSegment(1_000L, 2_000L, 1_900L, 200, 1_500);
    QoSInfo s2 = scoredSegment(3_500L, 2_000L, 1_950L, 200, 1_200);
    QoSInfo s3 = scoredSegment(6_000L, 2_000L, 1_900L, 200, 900);
    QoSInfo s4 = scoredSegment(8_500L, 2_000L, 1_950L, 200, 600);
    QoSInfo s5 = scoredSegment(11_000L, 2_000L, 5_500L, 200, 300); // outlier
    QoSInfo trigger = triggerSegment(13_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.TRANSIENT);
    assertThat(r.nSlowSegments).isEqualTo(1);
    // Median ignores outlier; mean inflated by it.
    assertThat(r.medianExcessRatio).isLessThan(0.10);
    assertThat(r.maxExcessRatio).isGreaterThan(1.0);
  }

  @Test
  public void diagnose_allHealthy_returnsTransient() {
    // Window math: ts gap=2500, bl 1200→0 → wallTime=7500, supply=6000,
    //   Δbuffer=-1200, demand=7200, ratio=0.96 ⇒ pass sanity.
    QoSInfo s1 = scoredSegment(1_000L, 2_000L, 1_900L, 200, 1_200);
    QoSInfo s2 = scoredSegment(3_500L, 2_000L, 1_950L, 200, 800);
    QoSInfo s3 = scoredSegment(6_000L, 2_000L, 1_900L, 200, 400);
    QoSInfo trigger = triggerSegment(8_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.TRANSIENT);
    assertThat(r.nSlowSegments).isEqualTo(0);
  }

  // ===== Median vs mean robustness =====

  @Test
  public void diagnose_meanInflatedByOutlier_medianFiltersIt_returnsTransient() {
    // 4 healthy + 1 slow segment that meets is_slow but minority.
    // mean would be high (skewed) but median stays low.
    // Window math: ts gap=2500, bl 1500→0 over 5 → wallTime=12500, supply=10000,
    //   Δbuffer=-1500, demand=11500, ratio=0.92 ⇒ pass sanity.
    QoSInfo s1 = scoredSegment(1_000L, 2_000L, 2_010L, 200, 1_500);
    QoSInfo s2 = scoredSegment(3_500L, 2_000L, 2_020L, 200, 1_200);
    QoSInfo s3 = scoredSegment(6_000L, 2_000L, 2_010L, 200, 900);
    QoSInfo s4 = scoredSegment(8_500L, 2_000L, 2_020L, 200, 600);
    QoSInfo s5 = scoredSegment(11_000L, 2_000L, 6_000L, 200, 300); // 1 big outlier
    QoSInfo trigger = triggerSegment(13_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.TRANSIENT);
    assertThat(r.meanExcessRatio).isGreaterThan(r.medianExcessRatio); // mean inflated by outlier
  }

  // ===== ABR awareness =====

  @Test
  public void diagnose_abrAware_setOnEvidenceField() {
    // Window math: ts gap=2500, bl 1200→0 → wallTime=7500, supply=6000,
    //   Δbuffer=-1200, demand=7200, ratio=0.96 ⇒ pass sanity.
    QoSInfo s1 = clientSegmentV4(1_000L, 2_500L, 200, 4_000, 2_000, 240_000L, 1_200);
    QoSInfo s2 = clientSegmentV4(3_500L, 2_500L, 200, 4_000, 2_000, 240_000L, 800);
    QoSInfo s3 = clientSegmentV4(6_000L, 2_500L, 200, 4_000, 2_000, 240_000L, 400);
    QoSInfo trigger = triggerSegment(8_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.INSUFFICIENT_BANDWIDTH);
    assertThat(r.abrWasAware).isTrue();   // mtp 2000 < bitrate 4000 on last seg
  }

  @Test
  public void diagnose_abrNotAware_setFalse() {
    QoSInfo s1 = clientSegmentV4(1_000L, 2_500L, 200, 1_000, 1_200, 240_000L, 1_200);
    QoSInfo s2 = clientSegmentV4(3_500L, 2_500L, 200, 1_000, 1_200, 240_000L, 800);
    QoSInfo s3 = clientSegmentV4(6_000L, 2_500L, 200, 1_000, 1_200, 240_000L, 400);
    QoSInfo trigger = triggerSegment(8_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.INSUFFICIENT_BANDWIDTH);
    assertThat(r.abrWasAware).isFalse();  // mtp 1200 >= bitrate 1000
  }

  // ===== Cross-track =====

  @Test
  public void diagnose_audioAlsoSlow_crossTrackCorrelatedTrue() {
    // Window math: ts gap=2500, bl 1200→0 over 3 V-segs → wallTime=7500, V-supply=6000,
    //   Δbuffer=-1200, demand=7200, ratio=0.96 ⇒ pass sanity (audio supply also counts).
    QoSInfo v1 = scoredSegment(1_000L, 2_000L, 2_500L, 300, 1_200);
    QoSInfo v2 = scoredSegment(3_500L, 2_000L, 2_500L, 300, 800);
    QoSInfo v3 = scoredSegment(6_000L, 2_000L, 2_500L, 300, 400);
    QoSInfo a1 = audioSegment(1_500L, 2_000L, 2_500L);   // slow
    QoSInfo a2 = audioSegment(4_000L, 2_000L, 2_500L);   // slow
    QoSInfo trigger = triggerSegment(8_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(v1, v2, v3, a1, a2, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.INSUFFICIENT_BANDWIDTH);
    assertThat(r.nASegments).isEqualTo(2);
    assertThat(r.crossTrackCorrelated).isTrue();
  }

  @Test
  public void diagnose_audioHealthy_crossTrackCorrelatedFalse() {
    QoSInfo v1 = scoredSegment(1_000L, 2_000L, 2_500L, 300, 1_200);
    QoSInfo v2 = scoredSegment(3_500L, 2_000L, 2_500L, 300, 800);
    QoSInfo v3 = scoredSegment(6_000L, 2_000L, 2_500L, 300, 400);
    QoSInfo a1 = audioSegment(1_500L, 2_000L, 1_900L);   // healthy
    QoSInfo a2 = audioSegment(4_000L, 2_000L, 1_950L);   // healthy
    QoSInfo trigger = triggerSegment(8_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(v1, v2, v3, a1, a2, trigger), null);

    DiagnosisV4 r = QoSDiagnoserV4.diagnose(group);

    assertThat(r.cause).isEqualTo(DiagnosisV4.Cause.INSUFFICIENT_BANDWIDTH);
    assertThat(r.crossTrackCorrelated).isFalse();
  }

  // ===== Buffer trend =====

  @Test
  public void analyzeBufferTrend_monotonicallyDecreasing_isMonotonicDrain() {
    int[] bl = {9000, 7000, 5000, 3000, 1000};
    assertThat(QoSDiagnoserV4.analyzeBufferTrend(bl))
        .isEqualTo(DiagnosisV4.BufferTrend.MONOTONIC_DRAIN);
  }

  @Test
  public void analyzeBufferTrend_dropThenRecover_isSpike() {
    int[] bl = {9000, 5000, 3000, 7000, 8500};
    assertThat(QoSDiagnoserV4.analyzeBufferTrend(bl))
        .isEqualTo(DiagnosisV4.BufferTrend.SPIKE);
  }

  @Test
  public void analyzeBufferTrend_flat_isFlat() {
    int[] bl = {3000, 3100, 2900, 3050, 3000};
    assertThat(QoSDiagnoserV4.analyzeBufferTrend(bl))
        .isEqualTo(DiagnosisV4.BufferTrend.FLAT);
  }

  @Test
  public void analyzeBufferTrend_singlePoint_isUnavailable() {
    int[] bl = {3000};
    assertThat(QoSDiagnoserV4.analyzeBufferTrend(bl))
        .isEqualTo(DiagnosisV4.BufferTrend.UNAVAILABLE);
  }

  // ===== Statistics =====

  @Test
  public void median_oddCount_isMiddle() {
    assertThat(QoSDiagnoserV4.median(new double[]{0.1, 0.2, 0.3})).isEqualTo(0.2);
  }

  @Test
  public void median_evenCount_isAverageOfTwoMiddles() {
    assertThat(QoSDiagnoserV4.median(new double[]{0.1, 0.2, 0.3, 0.4})).isEqualTo(0.25);
  }

  @Test
  public void median_robustToOutlier() {
    // [0.05, 0.05, 0.05, 0.05, 1.50]
    double m = QoSDiagnoserV4.median(new double[]{0.05, 0.05, 0.05, 0.05, 1.50});
    assertThat(m).isEqualTo(0.05);
    double mean = QoSDiagnoserV4.mean(new double[]{0.05, 0.05, 0.05, 0.05, 1.50});
    assertThat(mean).isWithin(0.01).of(0.34);
  }

  // ===== Test fixture builders =====

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

  /** V-segment with explicit bytes for delivery_health calculation. bitrate=1000 kbps. */
  private static QoSInfo cdnEdgeSlowSegment(
      long ts, long loadDurMs, int ttfbMs, long bytes, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(/* cdurMs= */ 2_000L)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBytesLoaded(bytes)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(5_000)
        .setBufferedDurationMs(blMs)
        .build();
  }

  /** v8/v9-style VOD-DASH V-segment with explicit bytes (bitrate=1000kbps). */
  private static QoSInfo vodSegment(
      long ts, long cdurMs, long loadDurMs, int ttfbMs, long bytes, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(cdurMs)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBytesLoaded(bytes)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_200)  // mtp ≥ bitrate; ABR aware = false
        .setBufferedDurationMs(blMs)
        .build();
  }

  /** Client-bound V4 fixture with explicit bytes for full delivery_health math. */
  private static QoSInfo clientSegmentV4(
      long ts, long loadDurMs, int ttfbMs, int bitrateKbps, int mtpKbps,
      long bytes, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(/* cdurMs= */ 2_000L)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(ttfbMs)
        .setBufferedDurationMs(blMs)
        .setBytesLoaded(bytes)
        .setBitrateKbps(bitrateKbps)
        .setMeasuredThroughputKbps(mtpKbps)
        .build();
  }

  /** Audio segment for cross-track tests. */
  private static QoSInfo audioSegment(long ts, long cdurMs, long loadDurMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_AUDIO)
        .setChunkDurationMs(cdurMs)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(200)
        .setBytesLoaded(48_000L)
        .setBitrateKbps(200)
        .build();
  }
}