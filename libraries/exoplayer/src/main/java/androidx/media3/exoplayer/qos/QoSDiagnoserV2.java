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

import androidx.media3.exoplayer.qos.model.DiagnosisV2;
import androidx.media3.exoplayer.qos.model.RebufferGroup;

/**
 * Pure-function rebuffer diagnoser implementing the buffer-conservation
 * algorithm V2. Outputs one of 4 {@link DiagnosisV2.Cause} values via
 * per-segment phase decomposition and majority share comparison — no magic
 * thresholds outside the sanity gate.
 *
 * <p>Sibling to {@link QoSDiagnoser} (V1). V1 stays untouched; both run in
 * parallel via {@code FPlayQoSMonitor} for cross-validation.
 */
public final class QoSDiagnoserV2 {

  private QoSDiagnoserV2() {}

  /**
   * Diagnoses {@code group} and returns a single {@link DiagnosisV2}. Returns
   * {@link DiagnosisV2.Cause#TRANSIENT} for null/empty groups (defensive).
   */
  public static DiagnosisV2 diagnose(RebufferGroup group) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return DiagnosisV2.transientNoDrain();
    }

    // Step 1 — Sanity gate (reuse V1's pure functions).
    QoSDiagnoser.WindowMetrics metrics = QoSDiagnoser.computeWindowMetrics(group);
    String sanityFail = QoSDiagnoser.applySanityG1ate(metrics);
    if (sanityFail != null) {
      return DiagnosisV2.transientFromSanity(sanityFail);
    }

    // TODO(Tasks 2-5): replace placeholder with buffer-conservation pipeline.
    return DiagnosisV2.transientNoDrain();
  }
}
