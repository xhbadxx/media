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
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;

/**
 * One record per HTTP resource loaded by ExoPlayer.
 *
 * <p>Sentinel conventions: {@link #trackType} uses {@code C.TRACK_TYPE_*} constants
 * ({@code -1} for unknown). Int/long fields use {@code -1} for unknown, except
 * {@link #retryCount} which uses {@code 0} for "no retries". {@link #codec} uses
 * {@code null}. {@link #bufferStarvationFlag} aligns with CMCD {@code bs} key —
 * {@code true} when this request is the first after a rebuffer.
 *
 * <p>{@link #bufferedDurationMs} ({@code bl}) and {@link #measuredThroughputKbps}
 * ({@code mtp}) are captured at LOAD START time (when the loader scheduled this
 * request) for diagnostic accuracy.
 */
@UnstableApi
public final class QoSInfo {

  /** Outcome of the load — {@link #COMPLETED} for success, {@link #ERROR} for failures. */
  public enum LoadStatus {
    COMPLETED,
    ERROR
  }

  public final long timestampMs;
  public final String url;
  public final long bytesLoaded;
  public final long loadDurationMs;
  public final int trackType;
  public final int bitrateKbps;
  @Nullable public final String codec;
  public final int videoWidth;
  public final int videoHeight;
  public final long chunkDurationMs;
  public final int bufferedDurationMs;
  public final int measuredThroughputKbps;
  public final int retryCount;
  public final boolean bufferStarvationFlag;
  public final int ttfbMs;
  public final LoadStatus status;
  @Nullable public final String errorMessage;
  @Nullable public final String cacheStatus;
  @Nullable public final String cdnProvider;
  /**
   * Coefficient of variation (StdDev/Mean) of per-100ms rate samples during transfer
   * phase. {@code -1.0} sentinel when not measured (local load, transfer too short for
   * meaningful sampling, or hook unavailable). Low CV (&lt; 0.15) hints at steady
   * server-side throttling; high CV (&gt; 0.3) hints at unstable client signal.
   * Interpretation requires also looking at {@link #measuredThroughputKbps} and link
   * capacity — see {@code research-bandwidth-vs-rate-stability.md}.
   */
  public final double transferRateCv;
  /** Minimum per-100ms rate sample observed during transfer phase. {@code -1} = unset. */
  public final long transferRateMinKbps;
  /** Maximum per-100ms rate sample observed during transfer phase. {@code -1} = unset. */
  public final long transferRateMaxKbps;
  /** Number of 100ms windows sampled during transfer. {@code 0} = unset/no samples. */
  public final int transferSampleCount;
  /**
   * HTTP response code when the load failed with an HTTP status error (e.g. 503).
   * Sentinel {@code -1} for non-HTTP failures (timeout/reset/refused) or successful
   * loads. Captured from {@code InvalidResponseCodeException.responseCode} on
   * {@code onLoadError}.
   */
  public final int httpStatusCode;
  /**
   * Client-side network connection type at load time (Wi-Fi/4G/5G NSA/etc.). Sourced
   * from {@link androidx.media3.common.util.NetworkTypeObserver}. Sentinel
   * {@link C#NETWORK_TYPE_UNKNOWN} when unavailable (no Context wired).
   */
  public final @C.NetworkType int networkType;
  /**
   * Client-side link downstream bandwidth (kbps) reported by
   * {@code NetworkCapabilities.getLinkDownstreamBandwidthKbps()}. Sentinel {@code -1}
   * when unavailable. Note: driver/system estimate, not a real-time probe.
   */
  public final int linkDownstreamKbps;

  /**
   * Compact one-line representation suitable for logcat — skips fields holding
   * sentinel/unknown values, auto-scales numeric units, and truncates URL to last
   * path segment for readability. Shared across all {@code xxxQoSMonitor} wrappers.
   *
   * <p>Track type label: {@code V}=video, {@code A}=audio, {@code T}=text,
   * {@code M}=muxed/default (HLS .ts), {@code I}=image, {@code X}=metadata,
   * {@code P}=playlist/manifest (URL-derived for {@code .m3u8}/{@code .mpd} when
   * trackType is unknown), {@code ?}=unknown.
   *
   * <p>Two-section layout: {@code Player [...] [...]  ->  CDN [...] [...] [...]}.
   * Each section prefixes its inner per-source groups with the subsystem name so
   * the reader knows where each metric originated.
   * <pre>{@code
   *   Player [type, br=, res=, cdur=] [bl=, mtp=, retry=, *bs*]  ->  CDN [cdn=, cache=] [ttfb=, dur=, sz=] [ERROR] [<filename>] [url=]
   *          ↑ track descriptor       ↑ runtime state at load     ↑ delivery        ↑ network result   ↑ err   ↑ id       ↑ full
   * }</pre>
   * Within a group, fields are comma-separated; groups separated by space; sections
   * by {@code -> }. Empty groups are dropped.
   *
   * <p>Each group is built by a dedicated method ({@code trackInfoGroup()},
   * {@code playerStateGroup()}, etc.) — easy field add/remove without affecting others.
   * {@code bs=true} renders as {@code *bs*} (asterisks) — most critical field.
   * {@link #timestampMs} is intentionally skipped (logcat already prefixes its own).
   */
  public String toLogString() {
    StringBuilder sb = new StringBuilder(220);

    // Player section: groups derived from player internals (Format + runtime state)
    sb.append("Player");
    appendGroup(sb, trackInfoGroup());
    appendGroup(sb, playerStateGroup());
    appendGroup(sb, linkProfileGroup());

    // CDN section: groups derived from network response
    sb.append(" -> CDN");
    appendGroup(sb, deliveryGroup());
    appendGroup(sb, netResultGroup());
    appendGroup(sb, rateProfileGroup());
    appendGroup(sb, errorGroup());
    appendGroup(sb, shortUrlGroup());
    appendGroup(sb, fullUrlGroup());
    return sb.toString();
  }

  /** Player-side portion only — track + runtime state. For UI rendering as line 1. */
  public String toPlayerLogString() {
    StringBuilder sb = new StringBuilder(80);
    appendGroup(sb, trackInfoGroup());
    appendGroup(sb, playerStateGroup());
    appendGroup(sb, linkProfileGroup());
    return sb.toString();
  }

  /** CDN-side portion only — delivery + network result + url. For UI rendering as line 2. */
  public String toCdnLogString() {
    StringBuilder sb = new StringBuilder(140);
    appendGroup(sb, deliveryGroup());
    appendGroup(sb, netResultGroup());
    appendGroup(sb, rateProfileGroup());
    appendGroup(sb, errorGroup());
    appendGroup(sb, shortUrlGroup());
    appendGroup(sb, fullUrlGroup());
    return sb.toString();
  }

  /**
   * UI-ready 2-line text: Player groups on line 1, CDN groups on line 2 (with leading
   * space as visual indent). Drops the full-URL group — its DRM token bloats width
   * far beyond any HUD; the short-filename group on line 2 is enough to identify
   * the segment, and the full URL stays available via {@link #toLogString()} (logcat).
   */
  public String toUiText() {
    StringBuilder sb = new StringBuilder(180);
    appendGroup(sb, trackInfoGroup());
    appendGroup(sb, playerStateGroup());
    appendGroup(sb, linkProfileGroup());
    sb.append('\n');
    appendGroup(sb, deliveryGroup());
    appendGroup(sb, netResultGroup());
    appendGroup(sb, rateProfileGroup());
    appendGroup(sb, errorGroup());
    appendGroup(sb, shortUrlGroup());
    return sb.toString();
  }

  /** Player-side track descriptor: type, bitrate, resolution, chunk media duration. */
  private StringBuilder trackInfoGroup() {
    StringBuilder g = new StringBuilder();
    g.append(displayType());
    if (bitrateKbps > 0) addField(g, "br=" + formatKbps(bitrateKbps));
    if (videoWidth > 0 && videoHeight > 0) addField(g, "res=" + videoWidth + "x" + videoHeight);
    if (chunkDurationMs > 0) addField(g, "cdur=" + formatMs(chunkDurationMs));
    return g;
  }

  /** Player runtime state at load start: buffer length, bandwidth estimate, retry, bs flag. */
  private StringBuilder playerStateGroup() {
    StringBuilder g = new StringBuilder();
    if (bufferedDurationMs >= 0) addField(g, "bl=" + formatMs(bufferedDurationMs));
    if (measuredThroughputKbps > 0) addField(g, "mtp=" + formatKbps(measuredThroughputKbps));
    if (retryCount > 0) addField(g, "retry=" + retryCount);
    if (bufferStarvationFlag) addField(g, "*bs*");
    return g;
  }

  /** Client-side link profile: connection type + downstream link bandwidth. */
  private StringBuilder linkProfileGroup() {
    StringBuilder g = new StringBuilder();
    if (networkType != C.NETWORK_TYPE_UNKNOWN) {
      addField(g, "net=" + networkTypeName(networkType));
    }
    if (linkDownstreamKbps > 0) {
      addField(g, "link=" + formatKbps(linkDownstreamKbps));
    }
    return g;
  }

  private static String networkTypeName(@C.NetworkType int t) {
    switch (t) {
      case C.NETWORK_TYPE_WIFI: return "WIFI";
      case C.NETWORK_TYPE_2G: return "2G";
      case C.NETWORK_TYPE_3G: return "3G";
      case C.NETWORK_TYPE_4G: return "4G";
      case C.NETWORK_TYPE_5G_SA: return "5G_SA";
      case C.NETWORK_TYPE_5G_NSA: return "5G_NSA";
      case C.NETWORK_TYPE_CELLULAR_UNKNOWN: return "CELL";
      case C.NETWORK_TYPE_ETHERNET: return "ETH";
      case C.NETWORK_TYPE_OFFLINE: return "OFFLINE";
      case C.NETWORK_TYPE_OTHER: return "OTHER";
      default: return "?";
    }
  }

  /** CDN-observed network result: TTFB, total load duration, bytes received. */
  private StringBuilder netResultGroup() {
    StringBuilder g = new StringBuilder();
    if (ttfbMs >= 0) addField(g, "ttfb=" + ttfbMs + "ms");
    if (loadDurationMs > 0) addField(g, "dur=" + loadDurationMs + "ms");
    if (bytesLoaded > 0) addField(g, "sz=" + formatBytes(bytesLoaded));
    return g;
  }

  /** Per-100ms transfer rate profile: CV, min-max kbps, sample count. */
  private StringBuilder rateProfileGroup() {
    StringBuilder g = new StringBuilder();
    if (transferSampleCount <= 0) return g;
    addField(g, "cv=" + String.format(java.util.Locale.US, "%.2f", transferRateCv));
    if (transferRateMinKbps >= 0 && transferRateMaxKbps >= 0) {
      addField(g, "rate=" + transferRateMinKbps + "-" + transferRateMaxKbps + "kbps");
    }
    addField(g, "n=" + transferSampleCount);
    return g;
  }

  /** CDN response delivery info: CDN provider, cache status (from response headers/host). */
  private StringBuilder deliveryGroup() {
    StringBuilder g = new StringBuilder();
    if (cdnProvider != null) addField(g, "cdn=" + cdnProvider);
    if (cacheStatus != null) addField(g, "cache=" + cacheStatus);
    return g;
  }

  /** Error info — present only when load failed (status != COMPLETED). */
  private StringBuilder errorGroup() {
    StringBuilder g = new StringBuilder();
    if (status != LoadStatus.ERROR) return g;
    addField(g, "ERROR");
    if (httpStatusCode > 0) addField(g, "code=" + httpStatusCode);
    if (errorMessage != null) addField(g, "msg=" + errorMessage);
    return g;
  }

  /** Short filename for quick scan: last path segment or {@code seq=N} token. */
  private StringBuilder shortUrlGroup() {
    StringBuilder g = new StringBuilder();
    addField(g, shortUrl(url));
    return g;
  }

  /** Full URL — server endpoint inspection. */
  private StringBuilder fullUrlGroup() {
    StringBuilder g = new StringBuilder();
    if (url != null && !url.isEmpty()) addField(g, "url=" + url);
    return g;
  }

  private static void addField(StringBuilder group, String field) {
    if (group.length() > 0) group.append(", ");
    group.append(field);
  }

  private static void appendGroup(StringBuilder out, StringBuilder group) {
    if (group.length() == 0) return;
    if (out.length() > 0) out.append(' ');
    out.append('[').append(group).append(']');
  }

  private static String trackTypeLabel(int trackType) {
    if (trackType == C.TRACK_TYPE_VIDEO) return "V";
    if (trackType == C.TRACK_TYPE_AUDIO) return "A";
    if (trackType == C.TRACK_TYPE_TEXT) return "T";
    if (trackType == C.TRACK_TYPE_DEFAULT) return "M";
    if (trackType == C.TRACK_TYPE_IMAGE) return "I";
    if (trackType == C.TRACK_TYPE_METADATA) return "X";
    return "?";
  }

  /**
   * URL- and codec-aware override of {@link #trackTypeLabel(int)}:
   * <ul>
   *   <li>{@link C#TRACK_TYPE_UNKNOWN} + URL ends {@code .m3u8}/{@code .mpd} →
   *       {@code P} (playlist/manifest).
   *   <li>{@link C#TRACK_TYPE_DEFAULT} (HLS muxed segment) refined via codec string:
   *       {@code AV} if both video+audio codecs present, {@code V} if only video,
   *       {@code A} if only audio, {@code M} if codec unknown.
   *   <li>Otherwise track-type-derived label (V/A/T/I/X/?).
   * </ul>
   */
  private String displayType() {
    if (trackType == C.TRACK_TYPE_UNKNOWN && url != null) {
      String lower = url.toLowerCase(java.util.Locale.US);
      int q = lower.indexOf('?');
      if (q > 0) lower = lower.substring(0, q);
      if (lower.endsWith(".m3u8") || lower.endsWith(".mpd")) return "P";
    }
    if (trackType == C.TRACK_TYPE_DEFAULT && codec != null) {
      boolean hasVideo = containsVideoCodec(codec);
      boolean hasAudio = containsAudioCodec(codec);
      if (hasVideo && hasAudio) return "AV";
      if (hasVideo) return "V";
      if (hasAudio) return "A";
    }
    return trackTypeLabel(trackType);
  }

  private static boolean containsVideoCodec(String codec) {
    String lower = codec.toLowerCase(java.util.Locale.US);
    return lower.contains("avc1") || lower.contains("hvc1") || lower.contains("hev1")
        || lower.contains("vp09") || lower.contains("vp9") || lower.contains("av01");
  }

  private static boolean containsAudioCodec(String codec) {
    String lower = codec.toLowerCase(java.util.Locale.US);
    return lower.contains("mp4a") || lower.contains("opus") || lower.contains("aac")
        || lower.contains("ac-3") || lower.contains("ec-3") || lower.contains("mp3");
  }

  /** {@code 4577} → {@code "4.6Mbps"}, {@code 850} → {@code "850kbps"}. Input in kbps. */
  private static String formatKbps(int kbps) {
    if (kbps < 1000) return kbps + "kbps";
    return String.format(java.util.Locale.US, "%.1fMbps", kbps / 1000.0);
  }

  /** {@code 500} → {@code "500ms"}, {@code 16659} → {@code "16.7s"}. Input in ms. */
  private static String formatMs(long ms) {
    if (ms < 1000) return ms + "ms";
    return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
  }

  /** {@code 951} → {@code "951B"}, {@code 1804} → {@code "1.8KB"}, {@code 2066872} → {@code "2.0MB"}. */
  private static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
    return String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
  }

  /** Maximum filename length before fallback truncate kicks in. */
  private static final int MAX_FILENAME = 60;

  /**
   * Truncates URL to its most identifying token for log readability:
   * <ol>
   *   <li>Strip query string and host/path prefix → keep last path segment.
   *   <li>If filename matches HLS-style {@code seq=N.ext}, keep just {@code seq=N.ext}
   *       (drops the long bitrate/timing prefix shared by every segment of the stream).
   *   <li>Otherwise, if remaining filename exceeds {@link #MAX_FILENAME} chars,
   *       truncate from the head with {@code ...} prefix (suffix carries the unique part).
   * </ol>
   */
  private static String shortUrl(@Nullable String url) {
    if (url == null || url.isEmpty()) return "";
    int qmark = url.indexOf('?');
    String noQuery = qmark > 0 ? url.substring(0, qmark) : url;
    int lastSlash = noQuery.lastIndexOf('/');
    String filename = lastSlash >= 0 && lastSlash < noQuery.length() - 1
        ? noQuery.substring(lastSlash + 1)
        : noQuery;

    int seqIdx = filename.indexOf("seq=");
    if (seqIdx >= 0) return filename.substring(seqIdx);

    if (filename.length() > MAX_FILENAME) {
      return "..." + filename.substring(filename.length() - (MAX_FILENAME - 3));
    }
    return filename;
  }

  private QoSInfo(Builder b) {
    this.timestampMs = b.timestampMs;
    this.url = b.url;
    this.bytesLoaded = b.bytesLoaded;
    this.loadDurationMs = b.loadDurationMs;
    this.trackType = b.trackType;
    this.bitrateKbps = b.bitrateKbps;
    this.codec = b.codec;
    this.videoWidth = b.videoWidth;
    this.videoHeight = b.videoHeight;
    this.chunkDurationMs = b.chunkDurationMs;
    this.bufferedDurationMs = b.bufferedDurationMs;
    this.measuredThroughputKbps = b.measuredThroughputKbps;
    this.retryCount = b.retryCount;
    this.bufferStarvationFlag = b.bufferStarvationFlag;
    this.ttfbMs = b.ttfbMs;
    this.status = b.status;
    this.errorMessage = b.errorMessage;
    this.cacheStatus = b.cacheStatus;
    this.cdnProvider = b.cdnProvider;
    this.transferRateCv = b.transferRateCv;
    this.transferRateMinKbps = b.transferRateMinKbps;
    this.transferRateMaxKbps = b.transferRateMaxKbps;
    this.transferSampleCount = b.transferSampleCount;
    this.networkType = b.networkType;
    this.linkDownstreamKbps = b.linkDownstreamKbps;
    this.httpStatusCode = b.httpStatusCode;
  }

  /** Builder — all fields optional; sensible defaults for unset values. */
  public static final class Builder {
    private long timestampMs;
    private String url = "";
    private long bytesLoaded;
    private long loadDurationMs;
    private int trackType = -1;
    private int bitrateKbps = -1;
    @Nullable private String codec;
    private int videoWidth = -1;
    private int videoHeight = -1;
    private long chunkDurationMs = -1L;
    private int bufferedDurationMs = -1;
    private int measuredThroughputKbps = -1;
    private int retryCount = 0;
    private boolean bufferStarvationFlag = false;
    private int ttfbMs = -1;
    private LoadStatus status = LoadStatus.COMPLETED;
    @Nullable private String errorMessage;
    @Nullable private String cacheStatus;
    @Nullable private String cdnProvider;
    private double transferRateCv = -1.0;
    private long transferRateMinKbps = -1L;
    private long transferRateMaxKbps = -1L;
    private int transferSampleCount = 0;
    private @C.NetworkType int networkType = C.NETWORK_TYPE_UNKNOWN;
    private int linkDownstreamKbps = -1;
    private int httpStatusCode = -1;

    public Builder setTimestampMs(long v) { timestampMs = v; return this; }
    public Builder setUrl(String v) { url = v; return this; }
    public Builder setBytesLoaded(long v) { bytesLoaded = v; return this; }
    public Builder setLoadDurationMs(long v) { loadDurationMs = v; return this; }
    public Builder setTrackType(int v) { trackType = v; return this; }
    public Builder setBitrateKbps(int v) { bitrateKbps = v; return this; }
    public Builder setCodec(@Nullable String v) { codec = v; return this; }
    public Builder setVideoWidth(int v) { videoWidth = v; return this; }
    public Builder setVideoHeight(int v) { videoHeight = v; return this; }
    public Builder setChunkDurationMs(long v) { chunkDurationMs = v; return this; }
    public Builder setBufferedDurationMs(int v) { bufferedDurationMs = v; return this; }
    public Builder setMeasuredThroughputKbps(int v) { measuredThroughputKbps = v; return this; }
    public Builder setRetryCount(int v) { retryCount = v; return this; }
    public Builder setBufferStarvationFlag(boolean v) { bufferStarvationFlag = v; return this; }
    public Builder setTtfbMs(int v) { ttfbMs = v; return this; }
    public Builder setStatus(LoadStatus v) { status = v; return this; }
    public Builder setErrorMessage(@Nullable String v) { errorMessage = v; return this; }
    public Builder setCacheStatus(@Nullable String v) { cacheStatus = v; return this; }
    public Builder setCdnProvider(@Nullable String v) { cdnProvider = v; return this; }
    public Builder setTransferRateCv(double v) { transferRateCv = v; return this; }
    public Builder setTransferRateMinKbps(long v) { transferRateMinKbps = v; return this; }
    public Builder setTransferRateMaxKbps(long v) { transferRateMaxKbps = v; return this; }
    public Builder setTransferSampleCount(int v) { transferSampleCount = v; return this; }
    public Builder setNetworkType(@C.NetworkType int v) { networkType = v; return this; }
    public Builder setLinkDownstreamKbps(int v) { linkDownstreamKbps = v; return this; }
    public Builder setHttpStatusCode(int v) { httpStatusCode = v; return this; }

    public QoSInfo build() { return new QoSInfo(this); }
  }
}
