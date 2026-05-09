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

import androidx.media3.exoplayer.qos.model.DiagnosisV5;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiagnosisV5Test {

  @Test
  public void transientFromSanity_setsCauseAndReason() {
    DiagnosisV5 r = DiagnosisV5.transientFromSanity("ratio=2.5_>1.3");
    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.sanityFailReason).isEqualTo("ratio=2.5_>1.3");
    assertThat(r.cacheBranch).isEqualTo(DiagnosisV5.CacheBranch.UNKNOWN);
    assertThat(r.nVSegments).isEqualTo(0);
    assertThat(r.usedColdStartFallback).isFalse();
  }

  @Test
  public void cdnHttpError_carriesAllErrorCodes() {
    DiagnosisV5 r = DiagnosisV5.cdnHttpError(new int[] {503, 504, 401});
    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.CDN_HTTP_ERROR);
    assertThat(r.httpErrorCodes).asList().containsExactly(503, 504, 401).inOrder();
    assertThat(r.sanityFailReason).isNull();
  }

  @Test
  public void transientNoVData_carriesHttp4xxCodes() {
    DiagnosisV5 r = DiagnosisV5.transientNoVData(new int[] {404});
    assertThat(r.cause).isEqualTo(DiagnosisV5.Cause.TRANSIENT);
    assertThat(r.sanityFailReason).isEqualTo("no_v_data");
    assertThat(r.httpErrorCodes).asList().containsExactly(404);
  }
}
