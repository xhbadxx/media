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
import java.util.Collections;
import java.util.List;

/**
 * Verdict produced by the QoS diagnoser for one rebuffer event. Composed onto every
 * captured {@link RebufferGroup} alongside the entry snapshot — UI layers should
 * render a {@code Diagnosis} card per rebuffer rather than dumping the raw entries.
 *
 * <p>Approach (Spec §IV — per-entry walk):
 * <ol>
 *   <li>Diagnoser walks {@code RebufferGroup.entries} chronologically.
 *   <li>Each entry is classified into a {@link Finding} with {@link Severity} +
 *       human-readable {@code issues} citing specific metric values.
 *   <li>{@link #pattern} is inferred from the observed problem types across findings,
 *       not from aggregate statistics.
 *   <li>{@link #abrLag} is an independent cross-cut flag computed on the trigger entry.
 *   <li>{@link #conclusion} is a 2–3 sentence narrative synthesizing the observation.
 * </ol>
 *
 * <p>Each finding's {@code issues} list quotes specific values (e.g.,
 * {@code "throughput 2750kbps < br 4800 × 0.7 (3360kbps)"}) so reports are
 * audit-friendly — every claim verifiable directly against the corresponding entry.
 */
@UnstableApi
public final class Diagnosis {

  /** Per-entry severity tag assigned during the diagnostic walk. */
  public enum Severity {
    /** Entry is healthy — no rule fired. */
    OK,
    /** Entry shows borderline issue (e.g., load slightly over chunk duration). */
    WARN,
    /** Entry shows critical issue (transfer way over duration, server very slow, etc.). */
    CRITICAL,
    /** The entry that fired the {@code bs} flag — last item in the walk by definition. */
    TRIGGER
  }

  /**
   * One {@link QoSInfo} entry analysed by the diagnoser, tagged with severity and
   * the list of issues observed (each issue citing the specific metric value that
   * triggered it).
   */
  public static final class Finding {
    public final QoSInfo entry;
    public final Severity severity;
    public final List<String> issues;

    public Finding(QoSInfo entry, Severity severity, List<String> issues) {
      this.entry = entry;
      this.severity = severity;
      this.issues = Collections.unmodifiableList(issues);
    }

    /**
     * Multi-line text for this finding ready to render in a single {@code TextView}.
     * Format:
     * <pre>
     *   {iconForSeverity} {entry.toUiText() — Player line}
     *      • {issue 1}
     *      • {issue 2}
     * </pre>
     * No issues → only the header line. CDN line dropped to keep finding rows compact.
     */
    public String toUiText() {
      StringBuilder sb = new StringBuilder(120);
      sb.append(severityIcon(severity)).append(' ').append(entry.toPlayerLogString());
      for (String issue : issues) {
        sb.append("\n   • ").append(issue);
      }
      return sb.toString();
    }
  }

  public final Pattern pattern;
  public final boolean abrLag;
  public final List<Finding> findings;
  public final String conclusion;

  public Diagnosis(
      Pattern pattern,
      boolean abrLag,
      List<Finding> findings,
      String conclusion) {
    this.pattern = pattern;
    this.abrLag = abrLag;
    this.findings = Collections.unmodifiableList(findings);
    this.conclusion = conclusion;
  }

  /**
   * Placeholder used when the diagnoser has not run yet (e.g., during initial
   * construction in {@code FPlayQoSMonitor.captureRebufferSnapshot} before the real
   * diagnoser call). UI layers should treat this the same as {@link Pattern#UNKNOWN}.
   */
  public static Diagnosis unknown() {
    return new Diagnosis(
        Pattern.UNKNOWN,
        /* abrLag= */ false,
        Collections.emptyList(),
        "Diagnoser has not run on this rebuffer yet.");
  }

  /**
   * One-line headline for HUD / logcat summary.
   *
   * <p>Examples:
   * <pre>
   *   USER_NETWORK +ABR_LAG
   *   CDN_ORIGIN_SLOW
   *   UNKNOWN
   * </pre>
   */
  public String summary() {
    return abrLag ? pattern.name() + " +ABR_LAG" : pattern.name();
  }

  /**
   * Returns only the findings tagged {@link Severity#WARN} or {@link Severity#CRITICAL}
   * (plus the {@link Severity#TRIGGER} entry) — the "interesting" subset to render
   * prominently on the diagnosis card. Healthy entries are kept in {@link #findings}
   * for full audit but typically rendered collapsed.
   */
  public List<Finding> problemFindings() {
    java.util.List<Finding> out = new java.util.ArrayList<>();
    for (Finding f : findings) {
      if (f.severity != Severity.OK) out.add(f);
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * Single multi-line string that renders the entire diagnosis card — header,
   * conclusion paragraph, every problem finding (with severity icon + issues),
   * and (if applicable) the trigger entry. Designed to drop straight into a single
   * {@code TextView} (set typeface to monospace for column alignment) or to copy
   * directly into a support ticket.
   *
   * <p>Healthy ({@link Severity#OK}) entries are intentionally omitted — see
   * {@link #findings} for the full audit list.
   *
   * <p>Example output:
   * <pre>
   * ═══ USER_NETWORK +ABR_LAG ═══
   *
   * CDN healthy throughout. Problem started at 14:32:31.723: throughput
   * 2400kbps &lt; br 4800kbps × 0.7 (3360kbps). User network insufficient for
   * selected bitrate. ABR_LAG: br 4800kbps / mtp 2200kbps = 2.18 (Media3
   * safety bound 0.8).
   *
   * 🔴 [V, br=4.8Mbps, res=1920x1080, cdur=1.9s] [bl=1.3s, mtp=2.2Mbps]
   *    • throughput 2400kbps &lt; br 4800kbps × 0.7 (3360kbps)
   *    • dur 3492ms &gt; cdur 1900ms × 1.5 (transfer too slow)
   *
   * 🔴 [V, br=4.8Mbps, ...] [bl=...]
   *    • throughput 2110kbps &lt; br 4800kbps × 0.7 (3360kbps)
   *
   * 🟣 [V, br=4.8Mbps, ...] [bl=1.3s, mtp=2.2Mbps, *bs*]
   * </pre>
   */
  public String toFullReport() {
    StringBuilder sb = new StringBuilder(512);
    sb.append("═══ ").append(summary()).append(" ═══\n\n");
    sb.append(conclusion);
    List<Finding> problems = problemFindings();
    if (!problems.isEmpty()) {
      sb.append("\n");
      for (Finding f : problems) {
        sb.append('\n').append(f.toUiText()).append('\n');
      }
    }
    return sb.toString();
  }

  /** Icon used by {@link Finding#toUiText()} and {@link #toFullReport()} per severity. */
  static String severityIcon(Severity severity) {
    switch (severity) {
      case CRITICAL: return "🔴";
      case WARN:     return "⚠";
      case TRIGGER:  return "🟣";
      case OK:
      default:       return "✓";
    }
  }
}