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
