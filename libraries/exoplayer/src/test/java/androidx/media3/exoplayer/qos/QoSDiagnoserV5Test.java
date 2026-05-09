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

import androidx.annotation.Nullable;
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

  // ===== Step 2.5 — Retry decisive (RFC 7230 anchor) =====

  @Test
  public void diagnose_majorityRetry_mtpHealthy_returnsCdnDeliverySlow() {
    // 4 V-segments, 2 with retryCount > 0, last seg mtp=1500 vs bitrate=1000
    // → mtp_ratio = 1.5 > 0.5 (MTP_NOT_COLLAPSED_RATIO) → CDN_DELIVERY_SLOW.
    // Window math: ts gap=2500, bl 1500→0 over 4 V → wallTime=10000, supply=8000,
    //   Δbuffer=-1500 ⇒ pass sanity gate.
    QoSInfo s1 = retrySegment(1_000L, /* retries= */ 0, /* mtp= */ 1_500, 1_500);
    QoSInfo s2 = retrySegment(3_500L, /* retries= */ 1, /* mtp= */ 1_500, 1_000);
    QoSInfo s3 = retrySegment(6_000L, /* retries= */ 1, /* mtp= */ 1_500, 500);
    QoSInfo s4 = retrySegment(8_500L, /* retries= */ 0, /* mtp= */ 1_500, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nRetrySegments).isEqualTo(2);
    assertThat(r.hadRetries).isTrue();
  }

  @Test
  public void diagnose_majorityRetry_mtpCollapsed_fallsThrough() {
    // 2 retries on V but mtp=100 vs bitrate=1000 → mtp_ratio=0.1 < 0.5 → client disconnect
    // → Step 2.5 does NOT fire CDN_DELIVERY_SLOW. Falls to placeholder TRANSIENT.
    QoSInfo s1 = retrySegment(1_000L, /* retries= */ 0, /* mtp= */ 100, 1_500);
    QoSInfo s2 = retrySegment(3_500L, /* retries= */ 1, /* mtp= */ 100, 1_000);
    QoSInfo s3 = retrySegment(6_000L, /* retries= */ 1, /* mtp= */ 100, 500);
    QoSInfo s4 = retrySegment(8_500L, /* retries= */ 0, /* mtp= */ 100, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isNotEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
  }

  @Test
  public void diagnose_singleRetry_doesNotFireStep25() {
    // Only 1/4 V-segments has retry → < RETRY_DECISIVE_COUNT (2) → Step 2.5 skips.
    QoSInfo s1 = retrySegment(1_000L, /* retries= */ 0, /* mtp= */ 1_500, 1_500);
    QoSInfo s2 = retrySegment(3_500L, /* retries= */ 1, /* mtp= */ 1_500, 1_000);
    QoSInfo s3 = retrySegment(6_000L, /* retries= */ 0, /* mtp= */ 1_500, 500);
    QoSInfo s4 = retrySegment(8_500L, /* retries= */ 0, /* mtp= */ 1_500, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isNotEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
  }

  // ===== Step 3 — Cache branch dispatch (AWS canonical anchor) =====

  @Test
  public void cacheBranch_allHit_isHitDominant() {
    QoSInfo s1 = vSegmentWithCache(1_000L, 1_500, "HIT");
    QoSInfo s2 = vSegmentWithCache(3_500L, 1_000, "HIT");
    QoSInfo s3 = vSegmentWithCache(6_000L, 500, "HIT");
    QoSInfo s4 = vSegmentWithCache(8_500L, 0, "HIT");
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.HIT_DOMINANT);
  }

  @Test
  public void cacheBranch_allMiss_isMissDominant() {
    QoSInfo s1 = vSegmentWithCache(1_000L, 1_500, "MISS");
    QoSInfo s2 = vSegmentWithCache(3_500L, 1_000, "MISS");
    QoSInfo s3 = vSegmentWithCache(6_000L, 500, "MISS");
    QoSInfo s4 = vSegmentWithCache(8_500L, 0, "MISS");
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.MISS_DOMINANT);
  }

  @Test
  public void cacheBranch_mixed_isMixed() {
    // 2/4 HIT + 2/4 MISS → 50% each → neither ≥ 80% → MIXED.
    QoSInfo s1 = vSegmentWithCache(1_000L, 1_500, "HIT");
    QoSInfo s2 = vSegmentWithCache(3_500L, 1_000, "MISS");
    QoSInfo s3 = vSegmentWithCache(6_000L, 500, "HIT");
    QoSInfo s4 = vSegmentWithCache(8_500L, 0, "MISS");
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.MIXED);
  }

  @Test
  public void cacheBranch_allNullStatus_isUnknown() {
    // No cacheStatus on any V-segment → branch UNKNOWN.
    QoSInfo s1 = vSegmentWithCache(1_000L, 1_500, /* cacheStatus= */ null);
    QoSInfo s2 = vSegmentWithCache(3_500L, 1_000, null);
    QoSInfo s3 = vSegmentWithCache(6_000L, 500, null);
    QoSInfo s4 = vSegmentWithCache(8_500L, 0, null);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.UNKNOWN);
  }

  // ===== Step 3 — Tukey fence applied (Tukey 1977 anchor) =====

  @Test
  public void tukey_coldStart_sessionStatsNull_usesAbsoluteFallback() {
    QoSInfo s1 = vSegmentWithCache(1_000L, 1_500, "MISS");
    QoSInfo s2 = vSegmentWithCache(3_500L, 1_000, "MISS");
    QoSInfo s3 = vSegmentWithCache(6_000L, 500, "MISS");
    QoSInfo s4 = vSegmentWithCache(8_500L, 0, "MISS");
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    // Cold start → absolute fallback 800ms applied.
    assertThat(r.usedColdStartFallback).isTrue();
    assertThat(r.ttfbUpperFenceApplied).isEqualTo(800);
  }

  @Test
  public void tukey_warmedUp_outlierTtfb_isDetected() {
    // Pre-populate session with 10 healthy TTFB samples → upper fence = 700ms.
    SessionStatistics sessionStats = new SessionStatistics();
    int[] healthyTtfb = {180, 200, 200, 210, 280, 320, 350, 400, 400, 450};
    for (int v : healthyTtfb) {
      sessionStats.addTtfbSample("WIFI_MISS_fpt", v);
    }
    // Current V-segments TTFB = 1500ms → > 700 fence → outliers.
    QoSInfo s1 = vSegmentWithTtfb(1_000L, 1_500, "MISS", /* ttfbMs= */ 1_500);
    QoSInfo s2 = vSegmentWithTtfb(3_500L, 1_000, "MISS", 1_500);
    QoSInfo s3 = vSegmentWithTtfb(6_000L, 500, "MISS", 1_500);
    QoSInfo s4 = vSegmentWithTtfb(8_500L, 0, "MISS", 1_500);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, sessionStats);

    assertThat(r.usedColdStartFallback).isFalse();
    assertThat(r.ttfbUpperFenceApplied).isEqualTo(700);
    assertThat(r.nTtfbOutlierSegments).isEqualTo(4);
  }

  @Test
  public void tukey_warmedUp_chronicHighBaseline_avoidsFalsePositive() {
    // BytePlus-like chronic high TTFB scenario. Session baseline ~1700-1900ms.
    // Q1=1700, Q3=1900, IQR=200, upper fence = 1900 + 300 = 2200ms.
    SessionStatistics sessionStats = new SessionStatistics();
    int[] chronicHigh = {1650, 1700, 1700, 1750, 1800, 1825, 1850, 1900, 1900, 1950};
    for (int v : chronicHigh) {
      sessionStats.addTtfbSample("WIFI_HIT_byteplus", v);
    }
    // Current TTFB 1900ms < 2200 fence → NOT outlier (chronic baseline absorbed).
    QoSInfo s1 = vSegmentWithCacheCdn(1_000L, 1_500, "HIT", "byteplus", 1_900);
    QoSInfo s2 = vSegmentWithCacheCdn(3_500L, 1_000, "HIT", "byteplus", 1_900);
    QoSInfo s3 = vSegmentWithCacheCdn(6_000L, 500, "HIT", "byteplus", 1_900);
    QoSInfo s4 = vSegmentWithCacheCdn(8_500L, 0, "HIT", "byteplus", 1_900);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, sessionStats);

    assertThat(r.usedColdStartFallback).isFalse();
    assertThat(r.ttfbUpperFenceApplied).isEqualTo(2200);
    assertThat(r.nTtfbOutlierSegments).isEqualTo(0);
  }

  @Test
  public void aggregates_v8FixtureLikeData_computesMedianMeanVariance() {
    // v8-like 4 V-segments, excess_ratios ≈ [0.31, 0.24, 0.31, 0.43].
    // Median = (0.31 + 0.31)/2 = 0.31, mean ≈ 0.32, variance low.
    QoSInfo s1 = vSegmentExcess(1_000L, /* loadDurMs= */ 2_483L, 1_500);
    QoSInfo s2 = vSegmentExcess(3_500L, /* loadDurMs= */ 2_365L, 1_000);
    QoSInfo s3 = vSegmentExcess(6_000L, /* loadDurMs= */ 2_481L, 500);
    QoSInfo s4 = vSegmentExcess(8_500L, /* loadDurMs= */ 2_726L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.nVSegments).isEqualTo(4);
    assertThat(r.nSlowSegments).isEqualTo(4); // all > 0.10
    // Median is in [0.31 - eps, 0.31 + eps] for our fixture.
    assertThat(r.medianExcessRatio).isWithin(0.02).of(0.31);
    assertThat(r.maxExcessRatio).isWithin(0.02).of(0.435);
    // Variance low (sustained pattern).
    assertThat(r.varianceExcess).isLessThan(0.05);
  }

  // ===== Step 4 — Cache-fork CDN check (AWS canonical anchor) =====

  @Test
  public void step4a_hitDominant_cacheHitSlowMajority_returnsCdnDeliverySlow() {
    // 4 V-segments all cache=HIT, low TTFB (not outlier), low bytes → delivery_health
    // deficit. is_cache_hit_slow fires on majority → CDN_DELIVERY_SLOW(HIT_DOMINANT).
    QoSInfo s1 = vSegmentHitDeficit(1_000L, 1_500);
    QoSInfo s2 = vSegmentHitDeficit(3_500L, 1_000);
    QoSInfo s3 = vSegmentHitDeficit(6_000L, 500);
    QoSInfo s4 = vSegmentHitDeficit(8_500L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.HIT_DOMINANT);
    assertThat(r.nCacheHitSlowSegments).isEqualTo(4);
  }

  @Test
  public void step4a_hitDominant_deliveryRateOutlierMajority_returnsCdnDeliverySlow() {
    // Pre-populate sessionStats with healthy delivery rates → lower fence = 475 kbps.
    SessionStatistics sessionStats = new SessionStatistics();
    int[] healthyRates = {700, 800, 850, 850, 950, 1000, 1050, 1100, 1100, 1200};
    for (int v : healthyRates) {
      sessionStats.addDeliveryRateSample("WIFI_HIT_fpt", v);
    }
    // Also seed TTFB so cold-start fallback doesn't fire (avoid TTFB-outlier path).
    int[] healthyTtfb = {30, 40, 40, 50, 60, 70, 75, 80, 85, 90};
    for (int v : healthyTtfb) {
      sessionStats.addTtfbSample("WIFI_HIT_fpt", v);
    }
    // Current V-segments: cache=HIT, low TTFB (not outlier), post_ttfb < 475 kbps.
    QoSInfo s1 = vSegmentHitLowDelivery(1_000L, 1_500);
    QoSInfo s2 = vSegmentHitLowDelivery(3_500L, 1_000);
    QoSInfo s3 = vSegmentHitLowDelivery(6_000L, 500);
    QoSInfo s4 = vSegmentHitLowDelivery(8_500L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, sessionStats);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.HIT_DOMINANT);
    assertThat(r.nDeliveryRateOutlierSegments).isAtLeast(3);
  }

  @Test
  public void step4a_hitDominant_minorityEvidence_fallsThrough() {
    // 4 cache=HIT but only 1 has delivery deficit → not majority → fall through.
    QoSInfo s1 = vSegmentHitDeficit(1_000L, 1_500); // 1 deficit
    QoSInfo s2 = vSegmentWithCache(3_500L, 1_000, "HIT"); // healthy
    QoSInfo s3 = vSegmentWithCache(6_000L, 500, "HIT");
    QoSInfo s4 = vSegmentWithCache(8_500L, 0, "HIT");
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isNotEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.HIT_DOMINANT);
  }

  @Test
  public void step4b_missDominant_ttfbOutlierWithHealthyDelivery_returnsCdnDeliverySlow() {
    // 4 V-segments cache=MISS, TTFB > 800ms (cold-start fallback) + delivery_health
    // ≥ 0.90 → MISS-side decisive → CDN_DELIVERY_SLOW(MISS_DOMINANT).
    QoSInfo s1 = vSegmentMissOutlierHealthy(1_000L, 1_500);
    QoSInfo s2 = vSegmentMissOutlierHealthy(3_500L, 1_000);
    QoSInfo s3 = vSegmentMissOutlierHealthy(6_000L, 500);
    QoSInfo s4 = vSegmentMissOutlierHealthy(8_500L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.MISS_DOMINANT);
    assertThat(r.nTtfbOutlierSegments).isAtLeast(3);
  }

  @Test
  public void step4c_mixedCache_combinedEvidenceMajority_returnsCdnDeliverySlow() {
    // 2 HIT + 2 MISS → MIXED branch. 2 HIT-deficit + 2 MISS-ttfb-outlier-healthy
    // = 4/4 cdn_evidence → fires CDN_DELIVERY_SLOW(MIXED).
    QoSInfo s1 = vSegmentHitDeficit(1_000L, 1_500);
    QoSInfo s2 = vSegmentMissOutlierHealthy(3_500L, 1_000);
    QoSInfo s3 = vSegmentHitDeficit(6_000L, 500);
    QoSInfo s4 = vSegmentMissOutlierHealthy(8_500L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.MIXED);
  }

  @Test
  public void step4_nVBelow3_skipsCheck() {
    // Only 2 V-segments — below MIN_SEGMENTS_FOR_CDN, skip Step 4 even if all evidence
    // would fire. Falls to placeholder TRANSIENT.
    // Window math: ts gap=2500, bl 1000→0 → wallTime=5000, supply=4000, Δbl=-1000
    //   demand=5000, ratio=0.80 ⇒ pass sanity.
    QoSInfo s1 = vSegmentMissOutlierHealthy(1_000L, 1_000);
    QoSInfo s2 = vSegmentMissOutlierHealthy(3_500L, 0);
    QoSInfo trigger = triggerSegment(6_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isNotEqualTo(DiagnosisV5.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.nVSegments).isEqualTo(2);
  }

  // ===== Step 5 — Bandwidth deficit majority + variance modifier (MDPI 2021) =====

  @Test
  public void step5_v8FixtureBandwidthMajority_returnsInsufficientBandwidth() {
    // v8 #1 reproduction: 4 V-segments cdur=1900, loadDur ≈ 2.4-2.7s, all slow,
    // median ≈ 0.31, variance low (sustained pattern).
    QoSInfo s1 = vSegmentExcess(1_000L, 2_483L, 1_500);
    QoSInfo s2 = vSegmentExcess(3_500L, 2_365L, 1_000);
    QoSInfo s3 = vSegmentExcess(6_000L, 2_481L, 500);
    QoSInfo s4 = vSegmentExcess(8_500L, 2_726L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.INSUFFICIENT_BANDWIDTH);
    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.MISS_DOMINANT);
    assertThat(r.nSlowSegments).isEqualTo(4);
    assertThat(r.medianExcessRatio).isGreaterThan(0.10);
    // Variance modifier: sustained (low variance).
    assertThat(r.varianceExcess).isLessThan(0.05);
    // Cohort dims emitted for backend reattribution.
    assertThat(r.cohortNetworkType).isEqualTo("WIFI");
    assertThat(r.cohortCacheBranch).isEqualTo("MISS_DOMINANT");
    assertThat(r.cohortCdnHostname).isEqualTo("fpt");
  }

  @Test
  public void step5_minoritySlow_returnsTransient() {
    // 1/4 V-segments slow → not majority → TRANSIENT.
    QoSInfo s1 = vSegmentExcess(1_000L, /* loadDurMs= */ 2_700L, 1_500); // slow
    // Healthy: loadDur ≈ cdur (1900) so excess_ratio = 0.
    QoSInfo s2 = vSegmentExcess(3_500L, /* loadDurMs= */ 1_900L, 1_000);
    QoSInfo s3 = vSegmentExcess(6_000L, /* loadDurMs= */ 1_900L, 500);
    QoSInfo s4 = vSegmentExcess(8_500L, /* loadDurMs= */ 1_900L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.nSlowSegments).isEqualTo(1);
  }

  @Test
  public void step5_allHealthy_returnsTransient() {
    // 4 healthy V-segments (loadDur ≈ cdur). nSlow=0 → TRANSIENT.
    QoSInfo s1 = vSegmentExcess(1_000L, 1_900L, 1_500);
    QoSInfo s2 = vSegmentExcess(3_500L, 1_900L, 1_000);
    QoSInfo s3 = vSegmentExcess(6_000L, 1_900L, 500);
    QoSInfo s4 = vSegmentExcess(8_500L, 1_900L, 0);
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.nSlowSegments).isEqualTo(0);
  }

  @Test
  public void step5_meanInflatedByOutlier_medianSaves_returnsTransient() {
    // 4 healthy + 1 huge outlier. Mean ≈ 0.32 (above threshold), median = 0
    // (filters outlier). nSlow = 1 → not majority → TRANSIENT.
    // Window math: bl 1500→0 over 5 V → wallTime 12500, supply 9500, demand=11000,
    //   ratio=0.86 → pass sanity.
    QoSInfo s1 = vSegmentExcess(1_000L, 1_900L, 1_500);
    QoSInfo s2 = vSegmentExcess(3_500L, 1_900L, 1_200);
    QoSInfo s3 = vSegmentExcess(6_000L, 1_900L, 900);
    QoSInfo s4 = vSegmentExcess(8_500L, 1_900L, 600);
    QoSInfo s5 = vSegmentExcess(11_000L, 5_500L, 300); // outlier
    QoSInfo trigger = triggerSegment(13_500L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, s5, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.nSlowSegments).isEqualTo(1);
    // Median = 0, mean ≈ 0.38 → big spread, spike pattern.
    assertThat(r.medianExcessRatio).isLessThan(0.10);
    assertThat(r.meanExcessRatio).isGreaterThan(0.20);
  }

  @Test
  public void step6_transientDefault_emitsCohortDimsAndEvidence() {
    // No cause fires → TRANSIENT default. Cohort dims still emitted from last seg.
    QoSInfo s1 = vSegmentWithCache(1_000L, 1_500, "MISS");
    QoSInfo s2 = vSegmentWithCache(3_500L, 1_000, "MISS");
    QoSInfo s3 = vSegmentWithCache(6_000L, 500, "MISS");
    QoSInfo s4 = vSegmentWithCache(8_500L, 0, "MISS");
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.cohortNetworkType).isEqualTo("WIFI");
    assertThat(r.cohortCacheBranch).isEqualTo("MISS_DOMINANT");
    assertThat(r.cohortCdnHostname).isEqualTo("fpt");
    assertThat(r.cohortRegion).isNull();
  }

  @Test
  public void bufferTrend_monotonicallyDecreasing_isMonotonicDrain() {
    // 4 V-segs with bl strictly decreasing → MONOTONIC_DRAIN. Bounds picked so
    // sanity gate passes: deltaBuffer -2500, demand=10500, wallTime=10000, ratio=1.05.
    QoSInfo s1 = vSegmentWithCache(1_000L, 2_500, "MISS");
    QoSInfo s2 = vSegmentWithCache(3_500L, 2_000, "MISS");
    QoSInfo s3 = vSegmentWithCache(6_000L, 1_500, "MISS");
    QoSInfo s4 = vSegmentWithCache(8_500L, 1_000, "MISS");
    QoSInfo trigger = triggerSegment(11_000L, 0);
    RebufferGroup group =
        new RebufferGroup(
            1, trigger.timestampMs, trigger,
            Arrays.asList(s1, s2, s3, s4, trigger), null);

    DiagnosisV5 r = QoSDiagnoserV5.diagnose(group, /* sessionStats= */ null);

    assertThat(r.bufferTrend).isEqualTo(DiagnosisV5.BufferTrend.MONOTONIC_DRAIN);
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

  /**
   * V-segment with explicit retry count + mtp + bitrate=1000 kbps. cdur=2000ms,
   * loadDur=1900ms (healthy load — Step 2.5 doesn't depend on excess_ratio).
   */
  private static QoSInfo retrySegment(long ts, int retries, int mtpKbps, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        .setLoadDurationMs(1_900L)
        .setTtfbMs(200)
        .setBytesLoaded(240_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(mtpKbps)
        .setBufferedDurationMs(blMs)
        .setRetryCount(retries)
        .build();
  }

  /**
   * V-segment với explicit cacheStatus, network=WIFI, cdn=fpt. Healthy load
   * (loadDur ≈ cdur), low TTFB (200), bitrate 1000 kbps.
   */
  private static QoSInfo vSegmentWithCache(long ts, int blMs, @Nullable String cacheStatus) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        .setLoadDurationMs(1_900L)
        .setTtfbMs(200)
        .setBytesLoaded(240_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_500)
        .setBufferedDurationMs(blMs)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus(cacheStatus)
        .setCdnProvider("fpt")
        .build();
  }

  /** V-segment with explicit cacheStatus + ttfb. Used for Tukey fence detection tests. */
  private static QoSInfo vSegmentWithTtfb(long ts, int blMs, String cacheStatus, int ttfbMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        .setLoadDurationMs(2_000L + ttfbMs / 2)
        .setTtfbMs(ttfbMs)
        .setBytesLoaded(240_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_500)
        .setBufferedDurationMs(blMs)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus(cacheStatus)
        .setCdnProvider("fpt")
        .build();
  }

  /** V-segment with explicit cdnProvider + ttfb (BytePlus chronic high TTFB scenario). */
  private static QoSInfo vSegmentWithCacheCdn(
      long ts, int blMs, String cacheStatus, String cdnProvider, int ttfbMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        // loadDur ≈ cdur + ttfb (healthy delivery from chronic-high-TTFB CDN).
        .setLoadDurationMs(2_000L + ttfbMs)
        .setTtfbMs(ttfbMs)
        .setBytesLoaded(240_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_500)
        .setBufferedDurationMs(blMs)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus(cacheStatus)
        .setCdnProvider(cdnProvider)
        .build();
  }

  /**
   * V-segment with explicit loadDur for excess_ratio aggregate tests. cdur=1900ms,
   * bitrate=1000 kbps, ttfb=329ms, bytes ≈ 220KB (v8 #1 fixture seg1 reference).
   */
  private static QoSInfo vSegmentExcess(long ts, long loadDurMs, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(1_900L)
        .setLoadDurationMs(loadDurMs)
        .setTtfbMs(329)
        .setBytesLoaded(220_160L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_200)
        .setBufferedDurationMs(blMs)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("MISS")
        .setCdnProvider("fpt")
        .build();
  }

  /**
   * V-segment cache=HIT with delivery deficit: bytes 100KB / 1700ms transfer = 470kbps
   * → delivery_health = 0.47 (< 0.70 deficit) AND ttfb=200 (not outlier).
   * Fires {@code is_cache_hit_slow}.
   */
  private static QoSInfo vSegmentHitDeficit(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        .setLoadDurationMs(1_900L)
        .setTtfbMs(200)
        .setBytesLoaded(100_000L) // 100KB → 470 kbps post-ttfb / 1000 bitrate = 0.47 deficit
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_500)
        .setBufferedDurationMs(blMs)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("HIT")
        .setCdnProvider("fpt")
        .build();
  }

  /**
   * V-segment cache=HIT with delivery rate outlier (post_ttfb low, but ≥ 0.70 so not deficit
   * triggering cache_hit_slow). bytes 80KB / 1700ms = 376 kbps. Below Tukey lower fence 475.
   */
  private static QoSInfo vSegmentHitLowDelivery(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        .setLoadDurationMs(1_900L)
        .setTtfbMs(50)
        .setBytesLoaded(80_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_500)
        .setBufferedDurationMs(blMs)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("HIT")
        .setCdnProvider("fpt")
        .build();
  }

  /**
   * V-segment cache=MISS with TTFB outlier + healthy delivery. ttfb=1500 (> 800 fallback),
   * bytes ≈ 180KB / 1500ms transfer = 960 kbps → delivery_health 0.96 ≥ 0.90 (healthy).
   * Fires MISS-side decisive evidence (origin pull slow start).
   */
  private static QoSInfo vSegmentMissOutlierHealthy(long ts, int blMs) {
    return new QoSInfo.Builder()
        .setTimestampMs(ts)
        .setTrackType(C.TRACK_TYPE_VIDEO)
        .setChunkDurationMs(2_000L)
        .setLoadDurationMs(3_000L)
        .setTtfbMs(1_500)
        .setBytesLoaded(180_000L)
        .setBitrateKbps(1_000)
        .setMeasuredThroughputKbps(1_500)
        .setBufferedDurationMs(blMs)
        .setNetworkType(C.NETWORK_TYPE_WIFI)
        .setCacheStatus("MISS")
        .setCdnProvider("fpt")
        .build();
  }
}
