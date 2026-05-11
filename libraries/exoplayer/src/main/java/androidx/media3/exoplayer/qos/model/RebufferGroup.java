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

  /**
   * V2 diagnoser verdict (parallel to {@link #fullDiagnosis} during the dual-write
   * phase). UI layers may render V1 and V2 side-by-side so disagreements surface for
   * cross-validation. {@code null} for groups created before V2 wiring (legacy 5/6-arg
   * constructors); always non-null when constructed with the 7-arg ctor.
   */
  @Nullable public final DiagnosisV2 diagnosisV2;

  /**
   * V4 diagnoser verdict (parallel to {@link #diagnosisV2} during V4 dual-write phase).
   * Multi-evidence cascade replaces V2's TTFB-decomposition once validated. {@code null}
   * for groups created before V4 wiring (5/6/7-arg constructors); always non-null when
   * constructed with the 8-arg ctor.
   */
  @Nullable public final DiagnosisV4 diagnosisV4;

  /**
   * V5 diagnoser verdict (parallel to {@link #diagnosisV4} during V5 dual-write phase).
   * Client-only paper-anchored cascade with cohort dims for backend reattribution.
   * {@code null} for groups created before V5 wiring (5/6/7/8-arg constructors); always
   * non-null when constructed with the 9- or 10-arg ctor.
   */
  @Nullable public final DiagnosisV5 diagnosisV5;

  /**
   * V6 diagnoser verdict (parallel to {@link #diagnosisV5} during V6 dual-write phase).
   * Simplified 3-cause classifier — drain proxy + Tukey TTFB outlier majority gate. HTTP
   * codes attached as metadata only. {@code null} for groups created before V6 wiring;
   * always non-null when constructed with the 10-arg ctor.
   */
  @Nullable public final DiagnosisV6 diagnosisV6;

  /** Legacy constructor — preserves binary compatibility for callers / tests pre-FullDiagnosis. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis) {
    this(id, triggerTimeMs, trigger, entries, diagnosis, null, null, null, null, null);
  }

  /** Pre-V2 constructor — both legacy {@code Diagnosis} and the new {@code FullDiagnosis} attached. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis,
      @Nullable QoSDiagnoser.FullDiagnosis fullDiagnosis) {
    this(id, triggerTimeMs, trigger, entries, diagnosis, fullDiagnosis, null, null, null, null);
  }

  /** Pre-V4 constructor — V1 + V2 attached, V4+V5+V6 missing. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis,
      @Nullable QoSDiagnoser.FullDiagnosis fullDiagnosis,
      @Nullable DiagnosisV2 diagnosisV2) {
    this(id, triggerTimeMs, trigger, entries, diagnosis, fullDiagnosis, diagnosisV2, null, null, null);
  }

  /** Pre-V5 constructor — V1, V2, V4 verdicts attached, V5+V6 missing. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis,
      @Nullable QoSDiagnoser.FullDiagnosis fullDiagnosis,
      @Nullable DiagnosisV2 diagnosisV2,
      @Nullable DiagnosisV4 diagnosisV4) {
    this(id, triggerTimeMs, trigger, entries, diagnosis, fullDiagnosis, diagnosisV2, diagnosisV4, null, null);
  }

  /** Pre-V6 constructor — V1, V2, V4, V5 verdicts attached, V6 missing. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis,
      @Nullable QoSDiagnoser.FullDiagnosis fullDiagnosis,
      @Nullable DiagnosisV2 diagnosisV2,
      @Nullable DiagnosisV4 diagnosisV4,
      @Nullable DiagnosisV5 diagnosisV5) {
    this(id, triggerTimeMs, trigger, entries, diagnosis, fullDiagnosis, diagnosisV2, diagnosisV4, diagnosisV5, null);
  }

  /** Full constructor — V1, V2, V4, V5, and V6 verdicts all attached. */
  public RebufferGroup(
      int id,
      long triggerTimeMs,
      QoSInfo trigger,
      List<QoSInfo> entries,
      Diagnosis diagnosis,
      @Nullable QoSDiagnoser.FullDiagnosis fullDiagnosis,
      @Nullable DiagnosisV2 diagnosisV2,
      @Nullable DiagnosisV4 diagnosisV4,
      @Nullable DiagnosisV5 diagnosisV5,
      @Nullable DiagnosisV6 diagnosisV6) {
    this.id = id;
    this.triggerTimeMs = triggerTimeMs;
    this.trigger = trigger;
    this.entries = entries;
    this.diagnosis = diagnosis;
    this.fullDiagnosis = fullDiagnosis;
    this.diagnosisV2 = diagnosisV2;
    this.diagnosisV4 = diagnosisV4;
    this.diagnosisV5 = diagnosisV5;
    this.diagnosisV6 = diagnosisV6;
  }

  public int size() {
    return entries.size();
  }
}