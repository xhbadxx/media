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
package androidx.media3.exoplayer.qos.hook;

import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Captures TTFB (time-to-first-byte) per network load. Keyed by composite
 * {@code uri@position:length} string for uniqueness across range requests on the
 * same URI. Designed to be queried from {@link QoSAnalyticsHook} at
 * {@code onLoadCompleted} time via {@link #takeTtfb(Uri, long, long)}.
 *
 * <p>Thread-safe: {@code onTransferInitializing}/{@code onTransferStart} fire on
 * the loader thread (background), {@link #takeTtfb} is called from the player
 * application thread. Uses {@link ConcurrentHashMap} for cross-thread access.
 *
 * <p>Local (non-network) loads are skipped — TTFB is meaningless for {@code file://}.
 *
 * <p>Usage (consumer side):
 * <pre>{@code
 *   QoSTransferListener qosTransfer = new QoSTransferListener();
 *
 *   DataSource.Factory http = new DefaultHttpDataSource.Factory();
 *   DataSource.Factory ds = new DefaultDataSource.Factory(context, http)
 *       .setTransferListener(qosTransfer);
 *
 *   ExoPlayer player = new ExoPlayer.Builder(context)
 *       .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
 *       .setBandwidthMeter(bandwidthMeter)
 *       .build();
 *
 *   player.addAnalyticsListener(
 *       new QoSAnalyticsHook(player, bandwidthMeter, qosTransfer));
 * }</pre>
 */
@UnstableApi
public final class QoSTransferListener implements TransferListener {

  /** Cap to avoid unbounded growth if onTransferStart never fires for some loads. */
  private static final int MAX_PENDING = 128;

  /** Sample rate window for transfer-phase stability measurement (ms). */
  private static final long RATE_SAMPLE_WINDOW_MS = 100L;

  /** Minimum sample count for a meaningful CV — too few = unreliable stat. */
  private static final int MIN_SAMPLES_FOR_CV = 5;

  /** Active loads: composite key → onTransferInitializing timestamp (elapsedRealtimeMs). */
  private final ConcurrentMap<String, Long> initStartMs = new ConcurrentHashMap<>();

  /** Completed TTFBs ready to be consumed by {@link QoSAnalyticsHook}. */
  private final ConcurrentMap<String, Integer> readyTtfbMs = new ConcurrentHashMap<>();

  /** Active transfers: composite key → in-progress rate accumulator. */
  private final ConcurrentMap<String, RateAccumulator> activeProfiles = new ConcurrentHashMap<>();

  /** Completed rate profiles ready to be consumed by {@link QoSAnalyticsHook}. */
  private final ConcurrentMap<String, RateProfile> readyProfiles = new ConcurrentHashMap<>();

  /**
   * Stats from per-100ms rate sampling during a single transfer's payload phase
   * (post-TTFB). Used to disambiguate steady CDN throttle (low CV) from unstable
   * client signal (high CV). See {@code research-bandwidth-vs-rate-stability.md}.
   */
  public static final class RateProfile {
    public final double cv;
    public final long minKbps;
    public final long maxKbps;
    public final int sampleCount;

    RateProfile(double cv, long minKbps, long maxKbps, int sampleCount) {
      this.cv = cv;
      this.minKbps = minKbps;
      this.maxKbps = maxKbps;
      this.sampleCount = sampleCount;
    }
  }

  /** Mutable per-transfer accumulator. NOT thread-safe — confined to loader thread. */
  private static final class RateAccumulator {
    long lastSampleMs;
    long bytesInWindow;
    final List<Double> rateKbpsSamples = new ArrayList<>();

    RateAccumulator(long startMs) {
      this.lastSampleMs = startMs;
    }
  }

  @Override
  public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (!isNetwork) return;
    if (initStartMs.size() >= MAX_PENDING) return;
    initStartMs.put(makeKey(dataSpec), SystemClock.elapsedRealtime());
  }

  @Override
  public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (!isNetwork) return;
    String key = makeKey(dataSpec);
    long now = SystemClock.elapsedRealtime();
    Long t0 = initStartMs.remove(key);
    if (t0 != null) {
      int ttfb = (int) (now - t0);
      readyTtfbMs.put(key, ttfb);
    }
    // Begin payload-phase rate sampling. Window starts at first-byte time (now).
    activeProfiles.put(key, new RateAccumulator(now));
  }

  @Override
  public void onBytesTransferred(
      DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
    if (!isNetwork) return;
    RateAccumulator acc = activeProfiles.get(makeKey(dataSpec));
    if (acc == null) return;
    acc.bytesInWindow += bytesTransferred;
    long now = SystemClock.elapsedRealtime();
    long elapsed = now - acc.lastSampleMs;
    if (elapsed >= RATE_SAMPLE_WINDOW_MS) {
      // bytes×8 / ms = kbps directly (1 kbps = 1 bit/ms).
      double rateKbps = (double) (acc.bytesInWindow * 8L) / elapsed;
      acc.rateKbpsSamples.add(rateKbps);
      acc.bytesInWindow = 0;
      acc.lastSampleMs = now;
    }
  }

  @Override
  public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (!isNetwork) return;
    String key = makeKey(dataSpec);
    initStartMs.remove(key);
    RateAccumulator acc = activeProfiles.remove(key);
    if (acc != null) {
      RateProfile profile = computeProfile(acc);
      if (profile != null) {
        readyProfiles.put(key, profile);
      }
    }
  }

  @Nullable
  private static RateProfile computeProfile(RateAccumulator acc) {
    int n = acc.rateKbpsSamples.size();
    if (n < MIN_SAMPLES_FOR_CV) return null;
    double sum = 0;
    double min = Double.MAX_VALUE;
    double max = 0;
    for (Double r : acc.rateKbpsSamples) {
      sum += r;
      if (r < min) min = r;
      if (r > max) max = r;
    }
    double mean = sum / n;
    if (mean <= 0) return null;
    double sumSq = 0;
    for (Double r : acc.rateKbpsSamples) {
      double d = r - mean;
      sumSq += d * d;
    }
    double stddev = Math.sqrt(sumSq / n);
    double cv = stddev / mean;
    return new RateProfile(cv, (long) min, (long) max, n);
  }

  /**
   * Returns the TTFB (ms) for the load identified by {@code uri+position+length} and
   * removes it from internal storage. Returns {@code null} if not captured (e.g.,
   * local load, or {@code onTransferStart} did not fire).
   */
  @Nullable
  public Integer takeTtfb(Uri uri, long position, long length) {
    return readyTtfbMs.remove(makeKey(uri, position, length));
  }

  /**
   * Returns the rate-stability profile for the load identified by
   * {@code uri+position+length} and removes it from internal storage. Returns
   * {@code null} when the transfer was too short for meaningful sampling
   * (fewer than {@link #MIN_SAMPLES_FOR_CV} 100ms windows) or did not produce
   * payload bytes after first-byte arrival.
   */
  @Nullable
  public RateProfile takeRateProfile(Uri uri, long position, long length) {
    return readyProfiles.remove(makeKey(uri, position, length));
  }

  /**
   * Clears all pending and ready entries. Used by {@link
   * androidx.media3.exoplayer.qos.FPlayQoSMonitor#detach()} so re-attach starts fresh,
   * preventing stale data from a previous player session leaking into the new one.
   */
  public void clear() {
    initStartMs.clear();
    readyTtfbMs.clear();
    activeProfiles.clear();
    readyProfiles.clear();
  }

  private static String makeKey(DataSpec dataSpec) {
    return makeKey(dataSpec.uri, dataSpec.position, dataSpec.length);
  }

  private static String makeKey(Uri uri, long position, long length) {
    return uri.toString() + "@" + position + ":" + length;
  }
}