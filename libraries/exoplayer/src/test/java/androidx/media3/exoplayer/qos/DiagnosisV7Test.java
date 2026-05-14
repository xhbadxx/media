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

import androidx.media3.exoplayer.qos.model.DiagnosisV7;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiagnosisV7Test {

  @Test
  public void inconclusive_factory_setsReasonAndDefaults() {
    DiagnosisV7 d = DiagnosisV7.inconclusive(DiagnosisV7.Reason.COLD_START, new int[0]);
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
    assertThat(d.reason).isEqualTo(DiagnosisV7.Reason.COLD_START);
    assertThat(d.reasonDetail).isNull();
    assertThat(d.httpErrorCodes).isEmpty();
    assertThat(d.nV).isEqualTo(0);
    assertThat(d.nA).isEqualTo(0);
    assertThat(d.nVDrained).isEqualTo(0);
    assertThat(d.nADrained).isEqualTo(0);
    assertThat(d.nVCdnEvidence).isEqualTo(0);
    assertThat(d.nACdnEvidence).isEqualTo(0);
    assertThat(d.ttfbFenceUpperVMs).isEqualTo(-1);
    assertThat(d.ttfbFenceUpperAMs).isEqualTo(-1);
    assertThat(d.lastTtfbMs).isEqualTo(-1);
    assertThat(d.lastBufferMs).isEqualTo(-1);
    assertThat(d.mtpCollapseDetected).isFalse();
    assertThat(d.nRetries).isEqualTo(0);
  }

  @Test
  public void inconclusive_withDetail_capturesDetailString() {
    DiagnosisV7 d =
        DiagnosisV7.inconclusive(
            DiagnosisV7.Reason.SANITY_FAIL,
            /* detail= */ "demand_ratio=1.85",
            new int[0],
            /* nRetries= */ 0);
    assertThat(d.reasonDetail).isEqualTo("demand_ratio=1.85");
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.INCONCLUSIVE);
  }

  @Test
  public void inconclusive_withRetries_capturesNRetries() {
    DiagnosisV7 d =
        DiagnosisV7.inconclusive(DiagnosisV7.Reason.NO_DRAIN, new int[0], /* nRetries= */ 2);
    assertThat(d.nRetries).isEqualTo(2);
  }

  @Test
  public void classified_cdn_capturesVABreakdown() {
    DiagnosisV7 d =
        DiagnosisV7.classified(
            DiagnosisV7.Cause.CDN,
            new int[] {503},
            /* nV= */ 4,
            /* nA= */ 3,
            /* nVDrained= */ 3,
            /* nADrained= */ 2,
            /* nVCdnEvidence= */ 3,
            /* nACdnEvidence= */ 2,
            /* ttfbFenceUpperVMs= */ 500,
            /* ttfbFenceUpperAMs= */ 200,
            /* lastTtfbMs= */ 800,
            /* lastBufferMs= */ 1500,
            /* mtpCollapseDetected= */ false,
            /* nRetries= */ 1);
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.CDN);
    assertThat(d.nVDrained).isEqualTo(3);
    assertThat(d.nADrained).isEqualTo(2);
    assertThat(d.nVCdnEvidence).isEqualTo(3);
    assertThat(d.nACdnEvidence).isEqualTo(2);
    assertThat(d.nDrainedTotal()).isEqualTo(5);
    assertThat(d.nCdnEvidenceTotal()).isEqualTo(5);
    assertThat(d.httpErrorCodes).asList().containsExactly(503);
  }

  @Test
  public void classified_client_belowGate() {
    // nDrained=5 (4V+1A), nCdnEv=2 (V only) → 2×2=4 < 5 → CLIENT.
    DiagnosisV7 d =
        DiagnosisV7.classified(
            DiagnosisV7.Cause.CLIENT,
            new int[0],
            /* nV= */ 4,
            /* nA= */ 2,
            /* nVDrained= */ 4,
            /* nADrained= */ 1,
            /* nVCdnEvidence= */ 2,
            /* nACdnEvidence= */ 0,
            /* ttfbFenceUpperVMs= */ 600,
            /* ttfbFenceUpperAMs= */ 250,
            /* lastTtfbMs= */ 400,
            /* lastBufferMs= */ 3000,
            /* mtpCollapseDetected= */ true,
            /* nRetries= */ 0);
    assertThat(d.cause).isEqualTo(DiagnosisV7.Cause.CLIENT);
    assertThat(d.nDrainedTotal()).isEqualTo(5);
    assertThat(d.nCdnEvidenceTotal()).isEqualTo(2);
    assertThat(d.mtpCollapseDetected).isTrue();
  }

  @Test
  public void reason_enum_hasSixValues() {
    assertThat(DiagnosisV7.Reason.values()).hasLength(6);
  }

  @Test
  public void cause_enum_hasThreeValues() {
    assertThat(DiagnosisV7.Cause.values()).hasLength(3);
  }
}
