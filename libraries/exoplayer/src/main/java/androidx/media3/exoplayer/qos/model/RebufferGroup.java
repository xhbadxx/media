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
import androidx.media3.exoplayer.qos.QoSDiagnoser;
import java.util.List;

/**
 * Frozen snapshot of QoS entries captured at the moment a rebuffer event was first
 * recorded (an entry with {@code bufferStarvationFlag=true}), bundled with the
 * diagnoser's verdict. Snapshot is immutable — does not update as new entries flow
 * into {@link androidx.media3.exoplayer.qos.QoSMonitor}.
 *
 * <p>Created by {@link androidx.media3.exoplayer.qos.FPlayQoSMonitor} on every
 * captured rebuffer (cooldown-debounced to suppress duplicate audio/video bs flags
 * from the same event). The {@link #diagnosis} is computed on the same thread as
 * the capture (player application thread) — see
 * {@code androidx.media3.exoplayer.qos.QoSDiagnoser}.
 */
@UnstableApi
public final class RebufferGroup {

  /** Sequential id within the current attach session, starting at 1. */
  public final int id;

  /** {@link System#currentTimeMillis()} of the bs-flagged entry that triggered capture. */
  public final long triggerTimeMs;

  /** The bs-flagged entry that triggered capture (last item in {@link #entries}). */
  public final QoSInfo trigger;

  /**
   * Frozen snapshot — last N entries (default 50) ending at and including
   * {@link #trigger}. Newest is {@code entries.get(entries.size()-1)}.
   */
  public final List<QoSInfo> entries;

  /**
   * Diagnoser verdict computed from {@link #entries} and {@link #trigger}. Per-entry
   * findings + inferred {@link Pattern} + cross-cut ABR flag + natural-language
   * conclusion. UI layers should render this card per rebuffer rather than dumping
   * raw {@link #entries}.
   */
  public final Diagnosis diagnosis;

  /**
   * Buffer-conservation pipeline verdict — richer than {@link #diagnosis}. Includes
   * {@link QoSDiagnoser.Cause}, {@link QoSDiagnoser.Mechanism}, smoking-gun list,
   * per-entry attribution, window metrics, and the ABR-lag cross-cut. {@code null}
   * for groups created before the buffer-conservation pipeline existed (legacy
   * 5-arg constructor); always non-null when constructed with the 6-arg ctor.
   */
  @Nullable public final QoSDiagnoser.FullDiagnosis fullDiagnosis;

  /** Legacy constructor — preserves binary compatibility for callers / tests pre-FullDiagnosis. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis) {
    this(id, triggerTimeMs, trigger, entries, diagnosis, null);
  }

  /** Full constructor — both legacy {@code Diagnosis} and the new {@code FullDiagnosis} attached. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis,
      @Nullable QoSDiagnoser.FullDiagnosis fullDiagnosis) {
    this.id = id;
    this.triggerTimeMs = triggerTimeMs;
    this.trigger = trigger;
    this.entries = entries;
    this.diagnosis = diagnosis;
    this.fullDiagnosis = fullDiagnosis;
  }

  public int size() {
    return entries.size();
  }
}