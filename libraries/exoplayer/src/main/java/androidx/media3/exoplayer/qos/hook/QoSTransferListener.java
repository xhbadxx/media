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

  /** Active loads: composite key → onTransferInitializing timestamp (elapsedRealtimeMs). */
  private final ConcurrentMap<String, Long> initStartMs = new ConcurrentHashMap<>();

  /** Completed TTFBs ready to be consumed by {@link QoSAnalyticsHook}. */
  private final ConcurrentMap<String, Integer> readyTtfbMs = new ConcurrentHashMap<>();

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
    Long t0 = initStartMs.remove(key);
    if (t0 != null) {
      int ttfb = (int) (SystemClock.elapsedRealtime() - t0);
      readyTtfbMs.put(key, ttfb);
    }
  }

  @Override
  public void onBytesTransferred(
      DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
    // No-op — TTFB already captured at onTransferStart.
  }

  @Override
  public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (isNetwork) initStartMs.remove(makeKey(dataSpec));
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

  private static String makeKey(DataSpec dataSpec) {
    return makeKey(dataSpec.uri, dataSpec.position, dataSpec.length);
  }

  private static String makeKey(Uri uri, long position, long length) {
    return uri.toString() + "@" + position + ":" + length;
  }
}