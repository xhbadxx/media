/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.util;

import android.util.Log;
import androidx.media3.common.util.UnstableApi;

/**
 * Centralized debug logging for low-latency DASH playback investigation.
 *
 * <p>Every low-latency related log call inside the fork goes through this helper so they share the
 * "{@value #TAG}" tag and a single {@link #enabled} toggle. Filter with {@code adb logcat -s
 * LL-Core:D} while tuning, flip {@link #enabled} to {@code false} for production builds.
 *
 * <p>Instrumented sites:
 *
 * <ul>
 *   <li>{@code DashMediaSource.updateLiveConfiguration} — dumps MediaItem vs ServiceDescription vs
 *       merged result each manifest refresh so you can verify client overrides actually win.
 *   <li>{@code DefaultLivePlaybackSpeedControl.setLiveConfiguration} — logs the live config
 *       handed to the speed controller.
 *   <li>{@code DefaultLivePlaybackSpeedControl.getAdjustedPlaybackSpeed} — rate-limited tick log
 *       (every {@value #TICK_LOG_INTERVAL_MS}ms) with liveOffset, current/ideal target, speed,
 *       buffered duration and smoothed minPossible.
 *   <li>{@code DefaultLivePlaybackSpeedControl.notifyRebuffer} — emits a REBUFFER marker and shows
 *       how the target was shifted.
 *   <li>{@code DashManifestParser.parsePeriod} — logs Period peer-max state ("[PeerMax]") once per
 *       MPD refresh so you can verify whether tracks are in sync and what holder value was set.
 *   <li>{@code SegmentBase.SegmentTemplate.getAvailableSegmentCount} — logs "[PeerMax] BUMP" only
 *       when the track-aware bump actually applies (count &lt; peerMax), so log volume stays bounded
 *       — you'll only see it for the lagging track under CCU load.
 * </ul>
 */
@UnstableApi
public final class LowLatencyLog {

  /** Shared logcat tag for every low-latency related message. */
  public static final String TAG = "LL-Core";

  /** Minimum interval between repeated tick logs from tight loops, in milliseconds. */
  public static final long TICK_LOG_INTERVAL_MS = 500L;

  /**
   * Log mode controlling verbosity.
   *
   * <ul>
   *   <li>{@link #MODE_OFF} — all LL-Core logs disabled.
   *   <li>{@link #MODE_FULL} — log everything (tick every 500ms, DashMerge every MUP, all speed
   *       changes). Use for deep debugging.
   *   <li>{@link #MODE_REBUFFER_TEST} — only log events relevant to rebuffer analysis: REBUFFER
   *       markers, targetAdjust, speedChange, tick only when buffer is low (below {@link
   *       #rebufferTestBufferThresholdMs}), and DashMerge only once at startup.
   * </ul>
   */
  public static final int MODE_OFF = 0;
  public static final int MODE_FULL = 1;
  public static final int MODE_REBUFFER_TEST = 2;

  /** Current log mode. Default is {@link #MODE_REBUFFER_TEST} for lightweight rebuffer testing. */
  public static volatile int mode = MODE_REBUFFER_TEST;

  /**
   * Buffer threshold (ms) for {@link #MODE_REBUFFER_TEST}. Tick logs are only emitted when
   * buffered duration is below this value. Default 1500ms.
   */
  public static volatile long rebufferTestBufferThresholdMs = 1000L;

  /**
   * Master switch. Kept for backwards compatibility. Setting to {@code false} is equivalent to
   * {@code mode = MODE_OFF}. Setting to {@code true} restores {@link #MODE_REBUFFER_TEST}.
   */
  public static volatile boolean enabled = false;

  private LowLatencyLog() {}

  /** Returns {@code true} if any logging is active. */
  public static boolean isEnabled() {
    return enabled && mode != MODE_OFF;
  }

  /** Returns {@code true} if current mode is {@link #MODE_FULL}. */
  public static boolean isFull() {
    return enabled && mode == MODE_FULL;
  }

  /** Returns {@code true} if current mode is {@link #MODE_REBUFFER_TEST}. */
  public static boolean isRebufferTest() {
    return enabled && mode == MODE_REBUFFER_TEST;
  }

  /** Emits a DEBUG log if logging is enabled. */
  public static void d(String section, String msg) {
    if (isEnabled()) {
      Log.d(TAG, "[" + section + "] " + msg);
    }
  }

  /** Emits a WARN log if logging is enabled. */
  public static void w(String section, String msg) {
    if (isEnabled()) {
      Log.w(TAG, "[" + section + "] " + msg);
    }
  }

  /** Emits a WARN log with throwable if logging is enabled. */
  public static void w(String section, String msg, Throwable t) {
    if (isEnabled()) {
      Log.w(TAG, "[" + section + "] " + msg, t);
    }
  }
}
