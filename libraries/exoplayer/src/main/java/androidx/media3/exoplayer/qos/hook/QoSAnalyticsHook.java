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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.util.NetworkTypeObserver;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.qos.QoSMonitor;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Listens to Media3 load events and records a {@link QoSInfo} per completed load.
 *
 * <p>Captures {@code bl} (buffer length) and {@code mtp} (measured throughput) at
 * LOAD START time — when the loader scheduled the request — so the values reflect
 * what the player saw when deciding to fetch, not what state was at completion.
 *
 * <p>Pass a {@link Player} reference to enable {@code bl} capture; otherwise that
 * field stays unknown ({@code -1}). Pass a {@link BandwidthMeter} (the same instance
 * the player uses) to read the most accurate {@code mtp} including the very first
 * load; otherwise {@code mtp} falls back to the cached value from
 * {@link #onBandwidthEstimate}, which is unset until the first load completes.
 *
 * <p>Usage (consumer side, recommended):
 * <pre>{@code
 *   BandwidthMeter bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context);
 *   ExoPlayer player = new ExoPlayer.Builder(context)
 *       .setBandwidthMeter(bandwidthMeter)
 *       .build();
 *   player.addAnalyticsListener(new QoSAnalyticsHook(player, bandwidthMeter));
 * }</pre>
 */
@UnstableApi
public final class QoSAnalyticsHook implements AnalyticsListener {

  @Nullable private final Player player;
  @Nullable private final BandwidthMeter bandwidthMeter;
  @Nullable private final QoSTransferListener transferListener;
  @Nullable private final ConnectivityManager connectivityManager;
  @Nullable private final NetworkTypeObserver networkTypeObserver;

  /**
   * Cached latest bandwidth estimate (bps) from {@link #onBandwidthEstimate}.
   * Used as fallback only when {@link #bandwidthMeter} is null.
   */
  private long latestThroughputBitsPerSec = -1L;

  /**
   * Per-load snapshot captured at {@link #onLoadStarted}, consumed at
   * {@link #onLoadCompleted}. Keyed by {@link LoadEventInfo#loadTaskId}.
   * Cleaned up on cancel/error to avoid leaks.
   */
  private final Map<Long, LoadStartSnapshot> startSnapshots = new HashMap<>();

  public QoSAnalyticsHook() {
    this(null, null, null, null);
  }

  public QoSAnalyticsHook(@Nullable Player player) {
    this(player, null, null, null);
  }

  public QoSAnalyticsHook(@Nullable Player player, @Nullable BandwidthMeter bandwidthMeter) {
    this(player, bandwidthMeter, null, null);
  }

  public QoSAnalyticsHook(
      @Nullable Player player,
      @Nullable BandwidthMeter bandwidthMeter,
      @Nullable QoSTransferListener transferListener) {
    this(player, bandwidthMeter, transferListener, null);
  }

  public QoSAnalyticsHook(
      @Nullable Player player,
      @Nullable BandwidthMeter bandwidthMeter,
      @Nullable QoSTransferListener transferListener,
      @Nullable Context context) {
    this.player = player;
    this.bandwidthMeter = bandwidthMeter;
    this.transferListener = transferListener;
    Context appCtx = (context != null) ? context.getApplicationContext() : null;
    this.connectivityManager =
        (appCtx != null && Build.VERSION.SDK_INT >= 23)
            ? (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE)
            : null;
    this.networkTypeObserver =
        (appCtx != null) ? NetworkTypeObserver.getInstance(appCtx) : null;
  }

  @Override
  public void onBandwidthEstimate(
      EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
    if (bandwidthMeter == null) {
      latestThroughputBitsPerSec = bitrateEstimate;
    }
  }

  @Override
  public void onLoadStarted(
      EventTime eventTime,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData,
      int retryCount) {
    int bl = (player != null) ? (int) player.getTotalBufferedDuration() : -1;
    long bps = (bandwidthMeter != null)
        ? bandwidthMeter.getBitrateEstimate()
        : latestThroughputBitsPerSec;
    int mtp = (bps > 0) ? (int) (bps / 1000) : -1;
    startSnapshots.put(
        loadEventInfo.loadTaskId, new LoadStartSnapshot(bl, mtp, retryCount));
  }

  @Override
  public void onLoadCompleted(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
    QoSMonitor monitor = QoSMonitor.getInstance();
    QoSInfo.Builder b = buildBaseInfo(loadEventInfo, mediaLoadData)
        .setBufferStarvationFlag(monitor.consumePendingBufferStarvation(mediaLoadData.trackType));
    monitor.recordInfo(b.build());
  }

  @Override
  public void onLoadCanceled(
      EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
    startSnapshots.remove(loadEventInfo.loadTaskId);
    discardTtfb(loadEventInfo);
  }

  @Override
  public void onLoadError(
      EventTime eventTime,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData,
      IOException error,
      boolean wasCanceled) {
    if (wasCanceled) {
      startSnapshots.remove(loadEventInfo.loadTaskId);
      discardTtfb(loadEventInfo);
      return;
    }
    int httpCode = -1;
    if (error instanceof HttpDataSource.InvalidResponseCodeException) {
      httpCode = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
    }
    QoSInfo info = buildBaseInfo(loadEventInfo, mediaLoadData)
        .setStatus(QoSInfo.LoadStatus.ERROR)
        .setErrorMessage(error.getMessage())
        .setHttpStatusCode(httpCode)
        .build();
    QoSMonitor.getInstance().recordInfo(info);
  }


  /**
   * Builds a {@link QoSInfo.Builder} with all fields common to completed/errored loads:
   * timestamp, url, bytes/duration, trackType, snapshot lookup (and removes from map),
   * format, chunk duration. Does NOT set {@code bufferStarvationFlag} (caller decides
   * whether to consume the pending bs flag) and does NOT set status (defaults to
   * {@link QoSInfo.LoadStatus#COMPLETED}).
   */
  private QoSInfo.Builder buildBaseInfo(
      LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
    QoSInfo.Builder b = new QoSInfo.Builder()
        .setTimestampMs(System.currentTimeMillis())
        .setUrl(loadEventInfo.uri.toString())
        .setBytesLoaded(loadEventInfo.bytesLoaded)
        .setLoadDurationMs(loadEventInfo.loadDurationMs)
        .setTrackType(mediaLoadData.trackType);

    LoadStartSnapshot snap = startSnapshots.remove(loadEventInfo.loadTaskId);
    if (snap != null) {
      if (snap.bufferedDurationMs >= 0) b.setBufferedDurationMs(snap.bufferedDurationMs);
      if (snap.measuredThroughputKbps > 0) b.setMeasuredThroughputKbps(snap.measuredThroughputKbps);
      if (snap.retryCount > 0) b.setRetryCount(snap.retryCount);
    }

    Format format = mediaLoadData.trackFormat;
    if (format != null) {
      if (format.bitrate > 0) b.setBitrateKbps(format.bitrate / 1000);
      b.setCodec(format.codecs);
      if (format.width > 0) b.setVideoWidth(format.width);
      if (format.height > 0) b.setVideoHeight(format.height);
    }

    String cacheStatus = parseCacheStatus(loadEventInfo.responseHeaders);
    if (cacheStatus != null) b.setCacheStatus(cacheStatus);

    String cdnProvider = parseCdnProvider(loadEventInfo.responseHeaders);
    if (cdnProvider == null) cdnProvider = parseCdnFromHost(loadEventInfo.uri);
    if (cdnProvider != null) b.setCdnProvider(cdnProvider);

    if (transferListener != null) {
      Integer ttfb = transferListener.takeTtfb(
          loadEventInfo.dataSpec.uri,
          loadEventInfo.dataSpec.position,
          loadEventInfo.dataSpec.length);
      if (ttfb != null) b.setTtfbMs(ttfb);

      QoSTransferListener.RateProfile rateProfile = transferListener.takeRateProfile(
          loadEventInfo.dataSpec.uri,
          loadEventInfo.dataSpec.position,
          loadEventInfo.dataSpec.length);
      if (rateProfile != null) {
        b.setTransferRateCv(rateProfile.cv);
        b.setTransferRateMinKbps(rateProfile.minKbps);
        b.setTransferRateMaxKbps(rateProfile.maxKbps);
        b.setTransferSampleCount(rateProfile.sampleCount);
      }
    }

    if (networkTypeObserver != null) {
      b.setNetworkType(networkTypeObserver.getNetworkType());
    }
    if (connectivityManager != null) {
      Network active = connectivityManager.getActiveNetwork();
      if (active != null) {
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
        if (caps != null) {
          int linkKbps = caps.getLinkDownstreamBandwidthKbps();
          if (linkKbps > 0) b.setLinkDownstreamKbps(linkKbps);
        }
      }
    }

    if (mediaLoadData.mediaStartTimeMs != C.TIME_UNSET
        && mediaLoadData.mediaEndTimeMs != C.TIME_UNSET) {
      b.setChunkDurationMs(mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs);
    }

    return b;
  }

  /**
   * Fallback CDN identification by URL hostname. Used when {@link #parseCdnProvider}
   * returns {@code null} (CDN not exposing recognised headers). Add hosts as needed.
   */
  @Nullable
  private static String parseCdnFromHost(@Nullable Uri uri) {
    if (uri == null) return null;
    String host = uri.getHost();
    if (host == null) return null;
    String lower = host.toLowerCase(Locale.US);
    if (lower.endsWith("fptplay.net") || lower.contains("fbox-livecdn")) return "fpt";
    if (lower.endsWith("byteoversea.com") || lower.endsWith("byteplus.com")) return "byteplus";
    return null;
  }

  /**
   * Identifies the CDN provider that served this response based on distinctive
   * headers (Cloudflare, AWS CloudFront, Akamai, Fastly). Returns {@code null} if
   * no signature matches. Add more as needed.
   */
  @Nullable
  private static String parseCdnProvider(@Nullable Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) return null;
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String name = entry.getKey();
      if (name == null) continue;
      String lower = name.toLowerCase(Locale.US);
      if ("cf-ray".equals(lower)) return "cloudflare";
      if ("x-amz-cf-id".equals(lower) || "x-amz-cf-pop".equals(lower)) return "cloudfront";
      if ("x-akamai-request-id".equals(lower)) return "akamai";
      if ("x-served-by".equals(lower)) {
        List<String> values = entry.getValue();
        if (values != null && !values.isEmpty() && values.get(0).startsWith("cache-")) {
          return "fastly";
        }
      }
    }
    return null;
  }

  /**
   * Returns the value of the first matching CDN cache status header, or {@code null}
   * if none present. Matches common CDN headers case-insensitively (Cloudflare,
   * Akamai, generic {@code X-Cache}/{@code X-Cache-Status}). Add more as needed.
   */
  @Nullable
  private static String parseCacheStatus(@Nullable Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) return null;
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String name = entry.getKey();
      if (name == null) continue;
      String lower = name.toLowerCase(Locale.US);
      if ("x-cache".equals(lower)
          || "x-cache-status".equals(lower)
          || "cf-cache-status".equals(lower)
          || "akamai-cache-status".equals(lower)) {
        List<String> values = entry.getValue();
        if (values != null && !values.isEmpty()) {
          return values.get(0);
        }
      }
    }
    return null;
  }

  /**
   * Discards any pending TTFB and rate-profile entries for this load (the load is being
   * dropped, not recorded). Prevents memory leak in {@link QoSTransferListener}'s storage.
   */
  private void discardTtfb(LoadEventInfo loadEventInfo) {
    if (transferListener != null) {
      transferListener.takeTtfb(
          loadEventInfo.dataSpec.uri,
          loadEventInfo.dataSpec.position,
          loadEventInfo.dataSpec.length);
      transferListener.takeRateProfile(
          loadEventInfo.dataSpec.uri,
          loadEventInfo.dataSpec.position,
          loadEventInfo.dataSpec.length);
    }
  }

  private static final class LoadStartSnapshot {
    final int bufferedDurationMs;
    final int measuredThroughputKbps;
    final int retryCount;

    LoadStartSnapshot(int bl, int mtp, int retryCount) {
      this.bufferedDurationMs = bl;
      this.measuredThroughputKbps = mtp;
      this.retryCount = retryCount;
    }
  }
}