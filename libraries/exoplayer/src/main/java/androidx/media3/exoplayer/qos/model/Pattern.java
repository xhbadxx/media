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
package androidx.media3.exoplayer.qos.model;

import androidx.media3.common.util.UnstableApi;

/**
 * Verdict produced by the QoS diagnoser for a single rebuffer event. Each value names
 * the most likely bottleneck identified from the captured {@link RebufferGroup#entries}
 * via composite scoring (see {@code QoSDiagnoser}).
 *
 * <p>One verdict per rebuffer; the cross-cut {@code abrLag} flag on
 * {@link Diagnosis} can co-occur with any pattern.
 *
 * <p>Mapping to the Spec document
 * (vault: {@code 1 🎯 Projects/FPlay/CMCD/Spec - QoS Diagnosis Method.md}):
 * <ul>
 *   <li>{@link #USER_NETWORK} — Pattern C (last-mile bandwidth insufficient)
 *   <li>{@link #CDN_EDGE_OVERLOAD} — Pattern A (HIT and MISS both slow)
 *   <li>{@link #CDN_ORIGIN_SLOW} — Pattern B (only MISS slow; edge fast)
 *   <li>{@link #ORIGIN_ERROR} — Pattern E (HTTP 5xx or retry storm)
 *   <li>{@link #TRANSIENT} — initial/brief stall, insufficient data to classify
 *   <li>{@link #UNKNOWN} — no scoring rule reached the verdict threshold
 * </ul>
 */
@UnstableApi
public enum Pattern {
  USER_NETWORK,
  CDN_EDGE_OVERLOAD,
  CDN_ORIGIN_SLOW,
  ORIGIN_ERROR,
  TRANSIENT,
  UNKNOWN
}