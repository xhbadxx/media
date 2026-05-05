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

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qos.model.Diagnosis;
import androidx.media3.exoplayer.qos.model.Pattern;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
// java.util.regex.Pattern is fully-qualified at usage sites to disambiguate from
// androidx.media3.exoplayer.qos.model.Pattern.

/**
 * Pure-function diagnoser for rebuffer events. Walks {@link RebufferGroup#entries}
 * chronologically, tags each A/V entry with a {@link Diagnosis.Severity} and a list of
 * specific issues, infers the {@link Pattern} from observations, and emits an
 * audit-friendly {@link Diagnosis}.
 *
 * <p>Methodology — per-entry walk (Spec §IV revised). Every threshold cited in
 * {@code Spec - QoS Diagnosis Method.md §III} maps to a specific check below; the
 * issue strings quote the exact metric value so reports remain verifiable directly
 * against the underlying log entry.
 *
 * <p>Stateless static API: {@link #diagnose(RebufferGroup)} is thread-safe and
 * allocation-light (one {@code List<Finding>} + a few short strings per call).
 * Designed to run on the player application thread inside
 * {@code FPlayQoSMonitor.captureRebufferSnapshot}.
 */
@UnstableApi
public final class QoSDiagnoser {

  // ===== Thresholds (Spec §III) =====

  /** TTFB warn — server response slow (web.dev tighten for video segments). */
  static final int TTFB_WARN_MS = 500;

  /** TTFB severe — origin definitively slow. */
  static final int TTFB_SEVERE_MS = 1000;

  /** Load duration / chunk duration ratio that signals buffer-drain start. */
  static final double DUR_CDUR_WARN_RATIO = 1.0;

  /** Load duration / chunk duration ratio that signals critical drain. */
  static final double DUR_CDUR_CRITICAL_RATIO = 1.5;

  /** Per-load throughput must be at least {@code br × this} (Media3 ABR safety). */
  static final double THROUGHPUT_BR_FRACTION = 0.7;

  /** ABR lag threshold — selected bitrate / mtp at trigger. */
  static final double ABR_LAG_RATIO = 0.8;

  /** Minimum A/V entries needed to issue a non-TRANSIENT verdict. */
  static final int MIN_AV_ENTRIES = 5;

  /** Lower bound of {@code demand_ratio} accepted as normal 1× playback. */
  static final double SANITY_GATE_RATIO_MIN = 0.7;

  /** Upper bound of {@code demand_ratio} accepted as normal 1× playback. */
  static final double SANITY_GATE_RATIO_MAX = 1.3;

  /** A single segment's deficit must exceed this fraction of initial buffer to fire SINGLE_SPIKE. */
  static final double MECHANISM_SPIKE_RATIO = 0.5;

  /** Fraction of segments draining required to fire CONTINUOUS_DRAIN. */
  static final double MECHANISM_DRAIN_RATIO = 0.5;

  /** Minimum scored segments needed before mechanism detection issues anything other than INSUFFICIENT_LOG. */
  static final int MIN_SEGMENTS_FOR_MECHANISM = 3;

  /** Manifest load duration above this is treated as slow (baseline ≤ 200ms × 5). */
  static final long MANIFEST_SLOW_LOAD_MS = 1_000L;

  // ===== HTTP 5xx detection =====

  private static final java.util.regex.Pattern HTTP_5XX =
      java.util.regex.Pattern.compile("\\b5\\d{2}\\b");

  private QoSDiagnoser() {}

  /**
   * Runs the per-entry walk, infers verdict, and returns a {@link Diagnosis}.
   * Never returns {@code null}.
   *
   * <p>Takes {@code (trigger, entries)} directly rather than a {@link RebufferGroup}
   * because the caller (e.g., {@code FPlayQoSMonitor.captureRebufferSnapshot})
   * needs the diagnosis BEFORE constructing the group — the group's
   * {@link RebufferGroup#diagnosis} field is final, so we can't mutate it after
   * construction. The group's id and triggerTimeMs are not consumed by the
   * diagnoser anyway.
   */
  public static Diagnosis diagnose(QoSInfo trigger, List<QoSInfo> entries) {
    List<Diagnosis.Finding> findings = new ArrayList<>(entries.size());
    for (QoSInfo entry : entries) {
      findings.add(analyzeEntry(entry, trigger));
    }

    int avEntries = 0;
    for (Diagnosis.Finding f : findings) {
      if (f.entry.trackType == C.TRACK_TYPE_VIDEO || f.entry.trackType == C.TRACK_TYPE_AUDIO) {
        avEntries++;
      }
    }

    Pattern pattern;
    if (avEntries < MIN_AV_ENTRIES) {
      pattern = Pattern.TRANSIENT;
    } else {
      pattern = inferPattern(findings);
    }

    boolean abrLag = checkAbrLag(trigger);
    String conclusion = buildConclusion(findings, pattern, abrLag, trigger);

    return new Diagnosis(pattern, abrLag, findings, conclusion);
  }

  /** Convenience overload — diagnose a {@link RebufferGroup} (used by tests / external callers). */
  public static Diagnosis diagnose(RebufferGroup group) {
    return diagnose(group.trigger, group.entries);
  }

  // ===== Window-level conservation metrics =====

  /**
   * Window-level metrics derived from a {@link RebufferGroup}, implementing the
   * core buffer-conservation equation from {@code Spec - QoS Buffer Conservation
   * Diagnosis §I}:
   * <pre>
   *   Δbuffer  = ΔSupply − ΔDemand
   *   ΔDemand  = ΔSupply − Δbuffer
   *   ratio    = ΔDemand / wall_time
   * </pre>
   *
   * <p>The ratio classifies playback regime:
   * <ul>
   *   <li>{@code ≈ 1.0} → normal 1× playback
   *   <li>{@code ≈ 0}   → player paused throughout the window
   *   <li>{@code > 1.5} → speed up (trick play / LL-DASH speed control)
   *   <li>{@code < 0.5} → seek refill / partial pause
   * </ul>
   * Downstream the sanity gate uses this ratio to distinguish real network
   * rebuffer from spurious bs flagged during pause/seek.
   */
  public static final class WindowMetrics {
    public final long wallTimeMs;
    public final long deltaBufferMs;
    public final long deltaSupplyMs;
    public final long deltaDemandMs;
    public final double demandRatio;

    WindowMetrics(
        long wallTimeMs,
        long deltaBufferMs,
        long deltaSupplyMs,
        long deltaDemandMs,
        double demandRatio) {
      this.wallTimeMs = wallTimeMs;
      this.deltaBufferMs = deltaBufferMs;
      this.deltaSupplyMs = deltaSupplyMs;
      this.deltaDemandMs = deltaDemandMs;
      this.demandRatio = demandRatio;
    }
  }

  /**
   * Computes the window-level conservation metrics for {@code group}. Returns a
   * zero-valued {@link WindowMetrics} when the group has fewer than 2 entries
   * (degenerate window) or when the window has zero wall-clock duration.
   *
   * <p>{@code ΔSupply} sums {@code chunkDurationMs} only over completed
   * scored-track segments (V/A/HLS muxed). Manifests, init segments, and failed
   * loads do not contribute supply because they don't deliver playable media.
   */
  public static WindowMetrics computeWindowMetrics(RebufferGroup group) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return new WindowMetrics(0L, 0L, 0L, 0L, 0.0);
    }
    QoSInfo first = group.entries.get(0);
    QoSInfo last = group.trigger;

    long wallTimeMs = Math.max(0L, last.timestampMs - first.timestampMs);
    long deltaBufferMs =
        clampNonNegativeBl(last.bufferedDurationMs)
            - clampNonNegativeBl(first.bufferedDurationMs);

    long deltaSupplyMs = 0L;
    for (QoSInfo entry : group.entries) {
      if (isCompletedSegment(entry)) {
        deltaSupplyMs += entry.chunkDurationMs;
      }
    }

    long deltaDemandMs = deltaSupplyMs - deltaBufferMs;
    double demandRatio = wallTimeMs > 0L ? (double) deltaDemandMs / wallTimeMs : 0.0;

    return new WindowMetrics(
        wallTimeMs, deltaBufferMs, deltaSupplyMs, deltaDemandMs, demandRatio);
  }

  /**
   * Treats the {@code bufferedDurationMs = -1} sentinel as 0 for arithmetic
   * purposes. Snapshots may legitimately be missing if {@code QoSAnalyticsHook}
   * couldn't capture the player state at load start; in that case we don't want
   * the computation to flip sign.
   */
  private static long clampNonNegativeBl(int bufferedDurationMs) {
    return bufferedDurationMs >= 0 ? bufferedDurationMs : 0L;
  }

  /**
   * Sanity gate from {@code Spec - QoS Buffer Conservation Diagnosis §IV.1}.
   * Returns {@code null} when the window represents normal 1× playback (and
   * thus is suitable for network diagnosis); otherwise returns a human-readable
   * reason describing why the window is abnormal (paused, sped up, seeked, or
   * degenerate).
   *
   * <p>This is a pure check — it does not mutate the diagnosis. The eventual
   * pipeline rewrite (see plan Task 6) will short-circuit to {@code TRANSIENT}
   * when this gate returns non-null.
   *
   * <p>Bounds:
   * <ul>
   *   <li>{@code wall_time ≤ 0}                 → degenerate window
   *   <li>{@code demand_ratio < 0.7}            → likely paused / partially paused
   *   <li>{@code demand_ratio > 1.3}            → likely speed up (trick play)
   *   <li>{@code 0.7 ≤ demand_ratio ≤ 1.3}     → passes
   * </ul>
   */
  @androidx.annotation.Nullable
  public static String applySanityGate(WindowMetrics metrics) {
    if (metrics == null || metrics.wallTimeMs <= 0L) {
      return "wall_time = "
          + (metrics == null ? 0L : metrics.wallTimeMs)
          + "ms (window degenerate, cannot diagnose)";
    }
    double ratio = metrics.demandRatio;
    if (ratio < SANITY_GATE_RATIO_MIN || ratio > SANITY_GATE_RATIO_MAX) {
      return String.format(
          Locale.US,
          "demand_ratio %.2f ∉ [%.1f, %.1f] (abnormal playback: pause/seek/speed change)",
          ratio,
          SANITY_GATE_RATIO_MIN,
          SANITY_GATE_RATIO_MAX);
    }
    return null;
  }

  // ===== Mechanism detection =====

  /**
   * Shape of how the rebuffer happened, derived from the buffer-trajectory and
   * error/manifest signals per {@code Spec - QoS Buffer Conservation Diagnosis §IV.2}.
   * Mechanism is independent of attribution (the per-load reason for the deficit);
   * a {@code SINGLE_SPIKE} mechanism could have a {@code CLIENT_BANDWIDTH},
   * {@code SLOW_RESPONSE_START}, or {@code ORIGIN_ERROR} attribution.
   */
  public enum Mechanism {
    /** ≥1 scored segment with HTTP 5xx or {@code retryCount > 0}. Highest priority. */
    ORIGIN_ERROR_DETECTED,
    /** ≥1 manifest entry with {@code status == ERROR}. */
    MANIFEST_FAILURE_DETECTED,
    /** Fewer than {@link #MIN_SEGMENTS_FOR_MECHANISM} scored segments — not enough data. */
    INSUFFICIENT_LOG,
    /** One segment's deficit consumed > 50% of initial buffer alone. */
    SINGLE_SPIKE,
    /** Majority of segments draining + bl declining head→tail. */
    CONTINUOUS_DRAIN,
    /** ≥2 manifests took > {@link #MANIFEST_SLOW_LOAD_MS}. */
    MANIFEST_SLOW_DETECTED,
    /** No observable drain pattern — likely TRANSIENT (codec init, startup, etc.). */
    NONE,
  }

  /** Mechanism plus the entries identified as causing the rebuffer. */
  public static final class MechanismResult {
    public final Mechanism mechanism;
    public final List<QoSInfo> smokingGuns;

    MechanismResult(Mechanism mechanism, List<QoSInfo> smokingGuns) {
      this.mechanism = mechanism;
      this.smokingGuns = Collections.unmodifiableList(smokingGuns);
    }
  }

  /**
   * Detects which {@link Mechanism} caused the rebuffer in {@code group} and
   * returns the responsible smoking-gun entries. Pure function — does not
   * mutate the group.
   *
   * <p>Priority order (early exit at first match):
   * <ol>
   *   <li>{@code ORIGIN_ERROR_DETECTED} — segment with HTTP 5xx or retry
   *   <li>{@code MANIFEST_FAILURE_DETECTED} — manifest with status=ERROR
   *   <li>{@code INSUFFICIENT_LOG} — fewer than 3 scored segments
   *   <li>{@code SINGLE_SPIKE} — one segment's deficit > 50% × initial bl
   *   <li>{@code CONTINUOUS_DRAIN} — majority drain + bl monotonic decline
   *   <li>{@code MANIFEST_SLOW_DETECTED} — ≥2 manifests with loadDur > 1000ms
   *   <li>{@code NONE} — no observable drain
   * </ol>
   */
  public static MechanismResult detectMechanism(RebufferGroup group) {
    if (group == null || group.entries == null || group.entries.isEmpty()) {
      return new MechanismResult(Mechanism.NONE, Collections.<QoSInfo>emptyList());
    }

    // 1. Origin error (segment with 5xx or retry)
    List<QoSInfo> originErrors = new ArrayList<>();
    for (QoSInfo e : group.entries) {
      if (hasOriginError(e)) {
        originErrors.add(e);
      }
    }
    if (!originErrors.isEmpty()) {
      return new MechanismResult(Mechanism.ORIGIN_ERROR_DETECTED, originErrors);
    }

    // 2. Manifest failure (manifest with status=ERROR)
    List<QoSInfo> manifestFails = new ArrayList<>();
    for (QoSInfo e : group.entries) {
      if (hasManifestFailure(e)) {
        manifestFails.add(e);
      }
    }
    if (!manifestFails.isEmpty()) {
      return new MechanismResult(Mechanism.MANIFEST_FAILURE_DETECTED, manifestFails);
    }

    // 3. Collect scored segments for buffer-trajectory analysis
    List<QoSInfo> segments = new ArrayList<>();
    for (QoSInfo e : group.entries) {
      if (isCompletedSegment(e)) {
        segments.add(e);
      }
    }
    if (segments.size() < MIN_SEGMENTS_FOR_MECHANISM) {
      return new MechanismResult(Mechanism.INSUFFICIENT_LOG, Collections.<QoSInfo>emptyList());
    }

    // 4. Compute per-segment deficits + find max + count drains
    long initialBl = clampNonNegativeBl(segments.get(0).bufferedDurationMs);
    long maxDeficit = 0L;
    int spikeIndex = -1;
    int drainCount = 0;
    for (int i = 0; i < segments.size(); i++) {
      QoSInfo s = segments.get(i);
      long deficit = s.loadDurationMs - s.chunkDurationMs;
      if (deficit > 0L) {
        drainCount++;
      }
      if (deficit > maxDeficit) {
        maxDeficit = deficit;
        spikeIndex = i;
      }
    }

    // 5. SINGLE_SPIKE — one deficit dominates initial buffer
    if (initialBl > 0L && (double) maxDeficit / initialBl > MECHANISM_SPIKE_RATIO) {
      List<QoSInfo> guns = new ArrayList<>(1);
      guns.add(segments.get(spikeIndex));
      return new MechanismResult(Mechanism.SINGLE_SPIKE, guns);
    }

    // 6. CONTINUOUS_DRAIN — majority drain + bl monotonic decline
    if (drainCount > segments.size() * MECHANISM_DRAIN_RATIO && isBlDeclining(segments)) {
      List<QoSInfo> guns = new ArrayList<>();
      for (QoSInfo s : segments) {
        if (s.loadDurationMs - s.chunkDurationMs > 0L) {
          guns.add(s);
        }
      }
      return new MechanismResult(Mechanism.CONTINUOUS_DRAIN, guns);
    }

    // 7. MANIFEST_SLOW — ≥2 manifests with loadDur > threshold
    List<QoSInfo> manifestSlows = new ArrayList<>();
    for (QoSInfo e : group.entries) {
      if (isManifest(e) && e.loadDurationMs > MANIFEST_SLOW_LOAD_MS) {
        manifestSlows.add(e);
      }
    }
    if (manifestSlows.size() >= 2) {
      return new MechanismResult(Mechanism.MANIFEST_SLOW_DETECTED, manifestSlows);
    }

    // 8. No observable drain
    return new MechanismResult(Mechanism.NONE, Collections.<QoSInfo>emptyList());
  }

  /**
   * Buffer length is monotonically declining when the tail bl is below both the
   * head and the midpoint — confirms a sustained downward trend rather than two
   * cherry-picked endpoints.
   */
  private static boolean isBlDeclining(List<QoSInfo> segments) {
    if (segments.size() < 3) {
      return false;
    }
    int n = segments.size();
    long head = clampNonNegativeBl(segments.get(0).bufferedDurationMs);
    long mid = clampNonNegativeBl(segments.get(n / 2).bufferedDurationMs);
    long tail = clampNonNegativeBl(segments.get(n - 1).bufferedDurationMs);
    return tail < head && tail < mid;
  }

  private static boolean hasOriginError(QoSInfo e) {
    if (e == null || !isScoredTrackType(e.trackType)) {
      return false;
    }
    if (e.retryCount > 0) {
      return true;
    }
    return e.status == QoSInfo.LoadStatus.ERROR && containsHttp5xx(e.errorMessage);
  }

  private static boolean hasManifestFailure(QoSInfo e) {
    return isManifest(e) && e.status == QoSInfo.LoadStatus.ERROR;
  }

  private static boolean isManifest(QoSInfo e) {
    if (e == null || e.trackType != C.TRACK_TYPE_UNKNOWN) {
      return false;
    }
    if (e.url == null || e.url.isEmpty()) {
      return true;
    }
    String lower = e.url.toLowerCase(Locale.US);
    int q = lower.indexOf('?');
    if (q > 0) {
      lower = lower.substring(0, q);
    }
    return lower.endsWith(".m3u8") || lower.endsWith(".mpd");
  }

  /**
   * A scored-track entry that completed loading and carries a positive media
   * duration. Manifests ({@code TRACK_TYPE_UNKNOWN}), init segments
   * ({@code chunkDurationMs ≤ 0}), text/image/metadata, and failed loads are
   * excluded — they don't deliver playable media into the buffer.
   */
  private static boolean isCompletedSegment(QoSInfo entry) {
    return entry != null
        && entry.status == QoSInfo.LoadStatus.COMPLETED
        && entry.chunkDurationMs > 0L
        && isScoredTrackType(entry.trackType);
  }

  /**
   * Classifies a single entry. Manifest (P) entries are tagged {@code OK} with no
   * issues — they don't reflect playback quality directly. The trigger entry is
   * tagged {@code TRIGGER} regardless of severity from rule firings (the trigger
   * tag is what the UI uses to show the {@code *bs*} icon).
   */
  static Diagnosis.Finding analyzeEntry(QoSInfo entry, QoSInfo trigger) {
    boolean isTrigger = entry == trigger;
    if (!isScoredTrackType(entry.trackType)) {
      // Manifest / unknown / text / image / metadata entries don't reflect playback
      // quality directly. If this entry is the trigger we still tag it so the UI
      // can render the *bs* icon, but we don't compute issues against it.
      return new Diagnosis.Finding(
          entry,
          isTrigger ? Diagnosis.Severity.TRIGGER : Diagnosis.Severity.OK,
          Collections.<String>emptyList());
    }

    List<String> issues = new ArrayList<>();
    Diagnosis.Severity severity = Diagnosis.Severity.OK;

    // Check 1: HTTP 5xx error (CRITICAL)
    if (entry.status == QoSInfo.LoadStatus.ERROR && containsHttp5xx(entry.errorMessage)) {
      severity = raise(severity, Diagnosis.Severity.CRITICAL);
      issues.add("HTTP 5xx error: " + safe(entry.errorMessage));
    }

    // Check 2: retry > 0 (WARN — Media3 had to retry)
    if (entry.retryCount > 0) {
      severity = raise(severity, Diagnosis.Severity.WARN);
      issues.add("retry=" + entry.retryCount + " (Media3 had to retry)");
    }

    // Check 3: TTFB
    if (entry.ttfbMs >= 0) {
      if (entry.ttfbMs > TTFB_SEVERE_MS) {
        severity = raise(severity, Diagnosis.Severity.CRITICAL);
        issues.add("TTFB " + entry.ttfbMs + "ms > " + TTFB_SEVERE_MS + "ms (origin slow)");
      } else if (entry.ttfbMs > TTFB_WARN_MS) {
        severity = raise(severity, Diagnosis.Severity.WARN);
        issues.add("TTFB " + entry.ttfbMs + "ms > " + TTFB_WARN_MS + "ms");
      }
    }

    // Check 4: dur/cdur ratio
    if (entry.chunkDurationMs > 0 && entry.loadDurationMs > 0) {
      double ratio = (double) entry.loadDurationMs / entry.chunkDurationMs;
      if (ratio > DUR_CDUR_CRITICAL_RATIO) {
        severity = raise(severity, Diagnosis.Severity.CRITICAL);
        issues.add(
            String.format(
                Locale.US,
                "dur %dms > cdur %dms × %.1f (transfer too slow, buffer drain)",
                entry.loadDurationMs,
                entry.chunkDurationMs,
                DUR_CDUR_CRITICAL_RATIO));
      } else if (ratio > DUR_CDUR_WARN_RATIO) {
        severity = raise(severity, Diagnosis.Severity.WARN);
        issues.add(
            String.format(
                Locale.US,
                "dur %dms > cdur %dms (buffer drain start)",
                entry.loadDurationMs,
                entry.chunkDurationMs));
      }
    }

    // Check 5: per-load throughput < br × 0.7 (video-like only — audio loads are too
    // small to measure reliably). HLS muxed (TRACK_TYPE_DEFAULT) carries video data
    // alongside audio in a single segment, so its throughput is meaningful for the
    // bitrate-vs-pipe comparison.
    //
    // Skip init segments (chunkDurationMs <= 0): init payloads are tiny header/codec data
    // (often < 1 KB), so the throughput formula yields a misleading low value even on fast
    // networks (e.g., 767-byte init in 21ms → 292kbps, would falsely trigger CRITICAL).
    // Only media segments — those carrying actual playback duration — can attest to whether
    // the network is sustaining the chosen bitrate.
    if (isVideoLikeTrackType(entry.trackType)
        && entry.bitrateKbps > 0
        && entry.bytesLoaded > 0
        && entry.loadDurationMs > 0
        && entry.chunkDurationMs > 0) {
      long throughputKbps = entry.bytesLoaded * 8 / entry.loadDurationMs;
      long threshold = (long) (entry.bitrateKbps * THROUGHPUT_BR_FRACTION);
      if (throughputKbps < threshold) {
        severity = raise(severity, Diagnosis.Severity.CRITICAL);
        issues.add(
            String.format(
                Locale.US,
                "throughput %dkbps < br %dkbps × %.1f (%dkbps)",
                throughputKbps,
                entry.bitrateKbps,
                THROUGHPUT_BR_FRACTION,
                threshold));
      }
    }

    // The trigger entry gets a distinct tag for UI rendering, regardless of issues.
    if (isTrigger) {
      severity = Diagnosis.Severity.TRIGGER;
    }

    return new Diagnosis.Finding(entry, severity, issues);
  }

  /**
   * Walks findings and returns the inferred {@link Pattern} per Spec §IV decision tree.
   * Ordered priority — early exit on first match.
   */
  static Pattern inferPattern(List<Diagnosis.Finding> findings) {
    // Priority 1: HTTP 5xx or retry
    if (anyIssueContains(findings, "HTTP 5xx") || anyIssueContains(findings, "retry=")) {
      return Pattern.ORIGIN_ERROR;
    }

    // Priority 2: TTFB > 1000ms severe
    if (anyIssueContains(findings, "(origin slow)")) {
      // Differentiate edge vs origin: if HIT entries also slow → edge overload
      if (hasSlowHit(findings)) return Pattern.CDN_EDGE_OVERLOAD;
      return Pattern.CDN_ORIGIN_SLOW;
    }

    // Priority 3: TTFB > 500ms on both HIT and MISS → edge overload
    if (hasWarnTtfbOnHitAndMiss(findings)) {
      return Pattern.CDN_EDGE_OVERLOAD;
    }

    // Priority 4: throughput < br × 0.7 (V loads insufficient)
    if (anyIssueContains(findings, "throughput")) {
      return Pattern.USER_NETWORK;
    }

    // Priority 5: transfer too slow with low TTFB → user network
    if (anyIssueContains(findings, "transfer too slow")) {
      return Pattern.USER_NETWORK;
    }

    // No problem entries — rebuffer fired but no metric anomaly (codec init, seek, etc.)
    return Pattern.TRANSIENT;
  }

  /** Cross-cut ABR check on the trigger entry. Returns true when br/mtp > 0.8. */
  static boolean checkAbrLag(QoSInfo trigger) {
    if (trigger.bitrateKbps <= 0 || trigger.measuredThroughputKbps <= 0) return false;
    double ratio = (double) trigger.bitrateKbps / trigger.measuredThroughputKbps;
    return ratio > ABR_LAG_RATIO;
  }

  /**
   * Builds 2–3 sentence natural-language summary referencing the first-problem entry
   * (its timestamp + the metric values that fired the rule). Designed to be copied
   * directly into a support ticket.
   */
  static String buildConclusion(
      List<Diagnosis.Finding> findings, Pattern pattern, boolean abrLag, QoSInfo trigger) {
    Diagnosis.Finding firstProblem = null;
    for (Diagnosis.Finding f : findings) {
      if (f.severity == Diagnosis.Severity.CRITICAL || f.severity == Diagnosis.Severity.WARN) {
        firstProblem = f;
        break;
      }
    }

    StringBuilder sb = new StringBuilder(240);
    switch (pattern) {
      case USER_NETWORK:
        sb.append("CDN healthy throughout (TTFB low, no errors). ");
        if (firstProblem != null) {
          sb.append("Problem started at ")
              .append(formatTime(firstProblem.entry.timestampMs))
              .append(": ");
          if (!firstProblem.issues.isEmpty()) {
            sb.append(firstProblem.issues.get(0)).append(". ");
          }
        }
        sb.append("User network insufficient for selected bitrate.");
        break;
      case CDN_EDGE_OVERLOAD:
        sb.append("CDN edge slow on both HIT and MISS responses. ");
        if (firstProblem != null) {
          sb.append("First flagged at ")
              .append(formatTime(firstProblem.entry.timestampMs))
              .append(": ");
          if (!firstProblem.issues.isEmpty()) {
            sb.append(firstProblem.issues.get(0)).append(". ");
          }
        }
        sb.append("Edge node likely overloaded.");
        break;
      case CDN_ORIGIN_SLOW:
        sb.append("Origin response slow on cache MISS. ");
        if (firstProblem != null) {
          sb.append("First flagged at ")
              .append(formatTime(firstProblem.entry.timestampMs))
              .append(": ");
          if (!firstProblem.issues.isEmpty()) {
            sb.append(firstProblem.issues.get(0)).append(". ");
          }
        }
        break;
      case ORIGIN_ERROR:
        sb.append("Server error / retry detected. ");
        if (firstProblem != null) {
          sb.append("At ")
              .append(formatTime(firstProblem.entry.timestampMs))
              .append(": ");
          if (!firstProblem.issues.isEmpty()) {
            sb.append(firstProblem.issues.get(0)).append(". ");
          }
        }
        break;
      case TRANSIENT:
        sb.append(
            "Rebuffer fired but no entry showed a clear metric anomaly. Possible causes: "
                + "codec init delay, seek, or initial buffer fill.");
        break;
      case UNKNOWN:
      default:
        sb.append("Insufficient evidence for verdict.");
        break;
    }

    if (abrLag) {
      sb.append(
          String.format(
              Locale.US,
              " ABR_LAG: br %dkbps / mtp %dkbps = %.2f (Media3 safety bound %.1f).",
              trigger.bitrateKbps,
              trigger.measuredThroughputKbps,
              (double) trigger.bitrateKbps / trigger.measuredThroughputKbps,
              ABR_LAG_RATIO));
    }

    return sb.toString();
  }

  // ===== Helpers =====

  /**
   * Track types whose loads attest to playback quality and therefore enter the
   * per-entry checks: video, audio, or HLS muxed ({@code TRACK_TYPE_DEFAULT}).
   * Manifest, text, image, metadata, and unknown loads are excluded — they don't
   * carry buffered media, so anomalies on them don't directly indicate rebuffer
   * cause.
   */
  private static boolean isScoredTrackType(int trackType) {
    return trackType == C.TRACK_TYPE_VIDEO
        || trackType == C.TRACK_TYPE_AUDIO
        || trackType == C.TRACK_TYPE_DEFAULT;
  }

  /**
   * Subset of {@link #isScoredTrackType} that carries video bytes (sufficient
   * payload size for reliable throughput measurement). Used to gate the
   * throughput-vs-bitrate check, which is unreliable on small audio payloads.
   */
  private static boolean isVideoLikeTrackType(int trackType) {
    return trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_DEFAULT;
  }

  private static boolean containsHttp5xx(String message) {
    if (message == null) return false;
    Matcher m = HTTP_5XX.matcher(message);
    return m.find();
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static Diagnosis.Severity raise(Diagnosis.Severity current, Diagnosis.Severity candidate) {
    // OK < WARN < CRITICAL (TRIGGER applied separately at the end)
    return current.ordinal() < candidate.ordinal() ? candidate : current;
  }

  private static boolean anyIssueContains(List<Diagnosis.Finding> findings, String needle) {
    for (Diagnosis.Finding f : findings) {
      for (String issue : f.issues) {
        if (issue.contains(needle)) return true;
      }
    }
    return false;
  }

  private static boolean hasSlowHit(List<Diagnosis.Finding> findings) {
    for (Diagnosis.Finding f : findings) {
      if (!"HIT".equalsIgnoreCase(f.entry.cacheStatus)) continue;
      for (String issue : f.issues) {
        if (issue.contains("TTFB") && (issue.contains("> 1000ms") || issue.contains("> 500ms"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasWarnTtfbOnHitAndMiss(List<Diagnosis.Finding> findings) {
    boolean hitSlow = false;
    boolean missSlow = false;
    for (Diagnosis.Finding f : findings) {
      String cache = f.entry.cacheStatus;
      if (cache == null) continue;
      boolean ttfbWarn = false;
      for (String issue : f.issues) {
        if (issue.contains("TTFB") && issue.contains("> " + TTFB_WARN_MS + "ms")) {
          ttfbWarn = true;
          break;
        }
      }
      if (!ttfbWarn) continue;
      if ("HIT".equalsIgnoreCase(cache)) hitSlow = true;
      else if ("MISS".equalsIgnoreCase(cache)) missSlow = true;
    }
    return hitSlow && missSlow;
  }

  private static String formatTime(long timestampMs) {
    long s = timestampMs / 1000;
    long ms = timestampMs % 1000;
    long sec = s % 60;
    long min = (s / 60) % 60;
    long hr = (s / 3600) % 24;
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", hr, min, sec, ms);
  }
}
