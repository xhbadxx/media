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

import androidx.media3.exoplayer.qos.model.DiagnosisV6;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiagnosisV6Test {

  @Test
  public void unknown_factory_attachesReasonAndDefaults() {
    DiagnosisV6 r = DiagnosisV6.unknown("cold_start", new int[0]);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("cold_start");
    assertThat(r.httpErrorCodes).isEmpty();
    // Numeric debug fields stay sentinel for UNKNOWN paths.
    assertThat(r.nV).isEqualTo(0);
    assertThat(r.nDrained).isEqualTo(0);
    assertThat(r.nCdnEvidence).isEqualTo(0);
    assertThat(r.ttfbFenceUpperMs).isEqualTo(-1);
    assertThat(r.lastTtfbMs).isEqualTo(-1);
    assertThat(r.lastBufferMs).isEqualTo(-1);
  }

  @Test
  public void unknown_factory_attachesHttpCodes() {
    DiagnosisV6 r = DiagnosisV6.unknown("no_v_data", new int[] {404, 503});
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.UNKNOWN);
    assertThat(r.unknownReason).isEqualTo("no_v_data");
    assertThat(r.httpErrorCodes).asList().containsExactly(404, 503).inOrder();
  }

  @Test
  public void classified_factory_setsAllFields() {
    DiagnosisV6 r =
        DiagnosisV6.classified(
            DiagnosisV6.Cause.CDN_DELIVERY_SLOW,
            new int[] {503},
            /* nV= */ 13,
            /* nDrained= */ 4,
            /* nCdnEvidence= */ 3,
            /* ttfbFenceUpperMs= */ 200,
            /* lastTtfbMs= */ 417,
            /* lastBufferMs= */ 4200);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CDN_DELIVERY_SLOW);
    assertThat(r.httpErrorCodes).asList().containsExactly(503);
    assertThat(r.nV).isEqualTo(13);
    assertThat(r.nDrained).isEqualTo(4);
    assertThat(r.nCdnEvidence).isEqualTo(3);
    assertThat(r.ttfbFenceUpperMs).isEqualTo(200);
    assertThat(r.lastTtfbMs).isEqualTo(417);
    assertThat(r.lastBufferMs).isEqualTo(4200);
    // Classified verdicts have no unknownReason.
    assertThat(r.unknownReason).isNull();
  }

  @Test
  public void classified_factory_emptyHttpCodesAllowed() {
    DiagnosisV6 r =
        DiagnosisV6.classified(
            DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH,
            new int[0],
            5,
            5,
            0,
            150,
            80,
            1500);
    assertThat(r.cause).isEqualTo(DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH);
    assertThat(r.httpErrorCodes).isEmpty();
    assertThat(r.unknownReason).isNull();
  }

  @Test
  public void cause_enum_has3ValuesExactly() {
    DiagnosisV6.Cause[] all = DiagnosisV6.Cause.values();
    assertThat(all).hasLength(3);
    assertThat(all)
        .asList()
        .containsExactly(
            DiagnosisV6.Cause.CDN_DELIVERY_SLOW,
            DiagnosisV6.Cause.CLIENT_INSUFFICIENT_BANDWIDTH,
            DiagnosisV6.Cause.UNKNOWN);
  }
}