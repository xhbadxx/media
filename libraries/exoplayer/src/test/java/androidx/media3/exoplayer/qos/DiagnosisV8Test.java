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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.exoplayer.qos.model.DiagnosisV8;
import org.junit.Test;

public class DiagnosisV8Test {

  @Test
  public void inconclusive_setsAllEvidenceFieldsToZeroOrSentinel() {
    DiagnosisV8 d = DiagnosisV8.inconclusive(
        DiagnosisV8.Reason.COLD_START, /* httpErrorCodes= */ new int[0]);

    assertEquals(DiagnosisV8.Cause.INCONCLUSIVE, d.cause);
    assertEquals(DiagnosisV8.Reason.COLD_START, d.reason);
    assertNull(d.reasonDetail);
    assertEquals(0, d.nV);
    assertEquals(0, d.nA);
    assertEquals(0, d.nVDrained);
    assertEquals(0, d.nADrained);
    assertEquals(0, d.nVCdnEvidence);
    assertEquals(0, d.nACdnEvidence);
    assertEquals(-1, d.ttfbFenceUpperVMs);
    assertEquals(-1, d.ttfbFenceUpperAMs);
    assertEquals(0, d.nRetries);
    // New V8 fields all default
    assertEquals(0, d.nVAbrLag);
    assertEquals(0, d.nVAbrLagInCdn);
    assertEquals(0, d.nVAbrLagInDrain);
    assertEquals(0, d.nVTtfbExtreme);
    assertEquals(0, d.nATtfbExtreme);
    assertEquals(0, d.nVBodyUnhealthy);
    assertEquals(0, d.nABodyUnhealthy);
    assertEquals(0, d.nVMtpCollapsed);
    assertEquals(0.0, d.drainPeakRatioV, 0.0001);
    assertEquals(-1, d.drainPeakSegIdxV);
    assertEquals(0.0, d.drainPeakRatioA, 0.0001);
    assertEquals(-1, d.drainPeakSegIdxA);
    assertEquals(0, d.nARetries);
    assertEquals(-1, d.ttfbQ1V);
    assertEquals(-1, d.ttfbMedianV);
    assertEquals(-1, d.ttfbQ3V);
    assertEquals(-1, d.ttfbQ1A);
    assertEquals(-1, d.ttfbMedianA);
    assertEquals(-1, d.ttfbQ3A);
    assertEquals("UNKNOWN", d.cohortNetworkType);
    assertEquals("UNKNOWN", d.cohortCdnProvider);
  }

  @Test
  public void classified_acceptsAllFields() {
    int[] codes = {503};
    DiagnosisV8 d = DiagnosisV8.classified(
        DiagnosisV8.Cause.CDN,
        codes,
        /* nV= */ 52, /* nA= */ 45,
        /* nVDrained= */ 34, /* nADrained= */ 12,
        /* nVCdnEvidence= */ 18, /* nACdnEvidence= */ 3,
        /* ttfbFenceUpperVMs= */ 1140, /* ttfbFenceUpperAMs= */ 1080,
        /* nRetries= */ 3,
        /* cohortNetworkType= */ "ETH", /* cohortCdnProvider= */ "fpt",
        /* nVAbrLag= */ 8, /* nVAbrLagInCdn= */ 3, /* nVAbrLagInDrain= */ 8,
        /* nVTtfbExtreme= */ 18, /* nATtfbExtreme= */ 3,
        /* nVBodyUnhealthy= */ 5, /* nABodyUnhealthy= */ 1,
        /* nVMtpCollapsed= */ 2,
        /* drainPeakRatioV= */ 1.85, /* drainPeakSegIdxV= */ 7,
        /* drainPeakRatioA= */ 1.42, /* drainPeakSegIdxA= */ 3,
        /* nARetries= */ 1,
        /* ttfbQ1V= */ 120, /* ttfbMedianV= */ 180, /* ttfbQ3V= */ 280,
        /* ttfbQ1A= */ 200, /* ttfbMedianA= */ 250, /* ttfbQ3A= */ 320);

    assertEquals(DiagnosisV8.Cause.CDN, d.cause);
    assertArrayEquals(codes, d.httpErrorCodes);
    assertEquals(34, d.nVDrained);
    assertEquals(8, d.nVAbrLag);
    assertEquals(3, d.nVAbrLagInCdn);
    assertEquals(1.85, d.drainPeakRatioV, 0.0001);
    assertEquals(7, d.drainPeakSegIdxV);
    assertEquals(180, d.ttfbMedianV);
    assertEquals(1, d.nARetries);
  }

  @Test
  public void inconclusive_withDetail_storesSubReason() {
    DiagnosisV8 d = DiagnosisV8.inconclusive(
        DiagnosisV8.Reason.SANITY_FAIL,
        /* detail= */ "demand>1.3",
        /* httpErrorCodes= */ new int[]{503},
        /* nRetries= */ 1);

    assertEquals("demand>1.3", d.reasonDetail);
    assertArrayEquals(new int[]{503}, d.httpErrorCodes);
    assertEquals(1, d.nRetries);
  }

  @Test
  public void toDisplaySummary_cdnVerdict_includesDrainPeak() {
    DiagnosisV8 d = DiagnosisV8.classified(
        DiagnosisV8.Cause.CDN, new int[0],
        52, 45, 34, 12, 18, 3, 1140, 1080, 0,
        "ETH", "fpt",
        8, 3, 8, 18, 3, 5, 1, 2,
        1.85, 7, 1.42, 3,
        0, 120, 180, 280, 200, 250, 320);

    String s = d.toDisplaySummary();
    assertTrue(s.contains("V8 · CDN"));
    assertTrue(s.contains("vSlow=34"));
    assertTrue(s.contains("aSlow=12"));
    assertTrue(s.contains("vSvrLag=18"));
    assertTrue(s.contains("peakV=1.85"));
  }

  @Test
  public void toDisplaySummary_inconclusive_showsReason() {
    DiagnosisV8 d = DiagnosisV8.inconclusive(
        DiagnosisV8.Reason.SANITY_FAIL, "demand>1.3", new int[]{503}, 1);

    String s = d.toDisplaySummary();
    assertTrue(s.contains("INCONCLUSIVE"));
    assertTrue(s.contains("sanity_fail:demand>1.3"));
    assertTrue(s.contains("retry=1"));
    assertTrue(s.contains("[503]"));
  }

  @Test
  public void toDisplaySummary_audioOnlyRetries_usesSplitFormat() {
    // NETWORK classified with nRetries=0, nARetries=1 — previously produced misleading "retry=0(A1)"
    DiagnosisV8 classified = DiagnosisV8.classified(
        DiagnosisV8.Cause.NETWORK, new int[0],
        4, 3, 2, 1, 0, 0, 800, 600, 0,
        "WIFI", "fpt",
        0, 0, 0, 0, 0, 1, 0, 0,
        1.20, 1, 0.0, -1,
        /* nARetries= */ 1,
        120, 180, 240, 200, 250, 300);

    String s = classified.toDisplaySummary();
    // V-only retry=0 alone would be ambiguous; expect split format
    org.junit.Assert.assertTrue("Expected V/A split format when A-retries present, got: " + s,
        s.contains("retry=V0/A1"));
    org.junit.Assert.assertFalse("Should not produce ambiguous 'retry=0(A1)': " + s,
        s.contains("retry=0(A1)"));
  }
}
