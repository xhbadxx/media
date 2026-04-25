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
package androidx.media3.exoplayer.qoe.model;

import androidx.annotation.Nullable;
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
public final class QoeInfo {

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

  private QoeInfo(Builder b) {
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

    public QoeInfo build() { return new QoeInfo(this); }
  }
}
