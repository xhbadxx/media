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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

/** Output of {@link androidx.media3.exoplayer.qos.QoSDiagnoserV2#diagnose}. */
@UnstableApi
public final class DiagnosisV2 {

  /** Final classification. Single source of truth for downstream consumers. */
  public enum Cause {
    /** Sanity gate failed, or no observable network drain in window. */
    TRANSIENT,
    /** Server-side drain dominant (TTFB phase ate more excess than transfer phase). */
    CDN_SLOW,
    /** Client-side drain dominant + ABR chose bitrate above measured throughput at decision time. */
    CLIENT_ABR,
    /** Client-side drain dominant but ABR was within measured throughput — pipe degraded after decision. */
    CLIENT_WEAK_NET,
  }

  public final Cause cause;
  public final long totalDrainMs;
  public final long serverDrainMs;
  public final long clientDrainMs;
  /** Number of client-drain segments where {@code bitrate > measuredThroughput}. -1 when not applicable. */
  public final int countAbrAggressive;
  /** Number of segments with {@code client_share > 0}. -1 when not applicable. */
  public final int countClientDrain;
  /** Non-null when sanity gate failed; carries the reason text for the banner. */
  @Nullable public final String sanityFailReason;

  public DiagnosisV2(
      Cause cause,
      long totalDrainMs,
      long serverDrainMs,
      long clientDrainMs,
      int countAbrAggressive,
      int countClientDrain,
      @Nullable String sanityFailReason) {
    this.cause = cause;
    this.totalDrainMs = totalDrainMs;
    this.serverDrainMs = serverDrainMs;
    this.clientDrainMs = clientDrainMs;
    this.countAbrAggressive = countAbrAggressive;
    this.countClientDrain = countClientDrain;
    this.sanityFailReason = sanityFailReason;
  }

  /** TRANSIENT factory with sanity-fail reason. */
  public static DiagnosisV2 transientFromSanity(String reason) {
    return new DiagnosisV2(Cause.TRANSIENT, 0L, 0L, 0L, -1, -1, reason);
  }

  /** TRANSIENT factory for "no observable drain". */
  public static DiagnosisV2 transientNoDrain() {
    return new DiagnosisV2(Cause.TRANSIENT, 0L, 0L, 0L, -1, -1, null);
  }
}
