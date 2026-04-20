/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.dash.manifest;

import static androidx.media3.exoplayer.dash.DashSegmentIndex.INDEX_UNBOUNDED;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.dash.DashSegmentIndex;
import androidx.media3.exoplayer.util.LowLatencyLog;
import com.google.common.math.BigIntegerMath;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/** An approximate representation of a SegmentBase manifest element. */
@UnstableApi
public abstract class SegmentBase {

  @Nullable /* package */ final RangedUri initialization;
  /* package */ final long timescale;
  /* package */ final long presentationTimeOffset;

  /**
   * @param initialization A {@link RangedUri} corresponding to initialization data, if such data
   *     exists.
   * @param timescale The timescale in units per second.
   * @param presentationTimeOffset The presentation time offset. The value in seconds is the
   *     division of this value and {@code timescale}.
   */
  public SegmentBase(
      @Nullable RangedUri initialization, long timescale, long presentationTimeOffset) {
    this.initialization = initialization;
    this.timescale = timescale;
    this.presentationTimeOffset = presentationTimeOffset;
  }

  /**
   * Returns the {@link RangedUri} defining the location of initialization data for a given
   * representation, or null if no initialization data exists.
   *
   * @param representation The {@link Representation} for which initialization data is required.
   * @return A {@link RangedUri} defining the location of the initialization data, or null.
   */
  @Nullable
  public RangedUri getInitialization(
      @UnderInitialization(Representation.class) Representation representation) {
    return initialization;
  }

  /** Returns the presentation time offset, in microseconds. */
  public long getPresentationTimeOffsetUs() {
    return Util.scaleLargeTimestamp(presentationTimeOffset, C.MICROS_PER_SECOND, timescale);
  }

  /** A {@link SegmentBase} that defines a single segment. */
  public static class SingleSegmentBase extends SegmentBase {

    /* package */ final long indexStart;
    /* package */ final long indexLength;

    /**
     * @param initialization A {@link RangedUri} corresponding to initialization data, if such data
     *     exists.
     * @param timescale The timescale in units per second.
     * @param presentationTimeOffset The presentation time offset. The value in seconds is the
     *     division of this value and {@code timescale}.
     * @param indexStart The byte offset of the index data in the segment.
     * @param indexLength The length of the index data in bytes.
     */
    public SingleSegmentBase(
        @Nullable RangedUri initialization,
        long timescale,
        long presentationTimeOffset,
        long indexStart,
        long indexLength) {
      super(initialization, timescale, presentationTimeOffset);
      this.indexStart = indexStart;
      this.indexLength = indexLength;
    }

    public SingleSegmentBase() {
      this(
          /* initialization= */ null,
          /* timescale= */ 1,
          /* presentationTimeOffset= */ 0,
          /* indexStart= */ 0,
          /* indexLength= */ 0);
    }

    @Nullable
    public RangedUri getIndex() {
      return indexLength <= 0
          ? null
          : new RangedUri(/* referenceUri= */ null, indexStart, indexLength);
    }
  }

  /** A {@link SegmentBase} that consists of multiple segments. */
  public abstract static class MultiSegmentBase extends SegmentBase {

    /* package */ final long startNumber;
    /* package */ final long duration;
    @Nullable /* package */ final List<SegmentTimelineElement> segmentTimeline;
    private final long timeShiftBufferDepthUs;
    private final long periodStartUnixTimeUs;

    /**
     * Offset to the current realtime at which segments become available, in microseconds, or {@link
     * C#TIME_UNSET} if all segments are available immediately.
     *
     * <p>Segments will be available once their end time &le; currentRealTime +
     * availabilityTimeOffset.
     */
    @VisibleForTesting /* package */ final long availabilityTimeOffsetUs;

    /**
     * LL-Core: Holder shared with peer tracks in the same Period parse to enable track-aware
     * low-latency extrapolation.
     *
     * <p>Index 0 stores the max {@code segmentTimeline.size()} across all low-latency tracks in the
     * enclosing Period. Populated by {@link DashManifestParser} after all AdaptationSets are parsed
     * and before the resulting {@link DashManifest} is published.
     *
     * <p>A {@code null} value or value {@code 0} means the holder is unavailable (e.g. synthetic
     * construction from tests, non-LL manifests, or backward-compat callers using the ctor overload
     * without holder). In those cases, callers fall back to the original {@code count + 1}
     * behavior.
     */
    @Nullable /* package */ final long[] peerMaxCountHolder;

    /**
     * Backward-compatible constructor without peer max holder. Delegates to the primary constructor
     * with {@code peerMaxCountHolder = null}, preserving the pre-track-aware behavior (no peer
     * bump, {@code count + 1} fallback) for callers that don't participate in Period-scoped sync.
     *
     * @param initialization A {@link RangedUri} corresponding to initialization data, if such data
     *     exists.
     * @param timescale The timescale in units per second.
     * @param presentationTimeOffset The presentation time offset. The value in seconds is the
     *     division of this value and {@code timescale}.
     * @param startNumber The sequence number of the first segment.
     * @param duration The duration of each segment in the case of fixed duration segments. The
     *     value in seconds is the division of this value and {@code timescale}. If {@code
     *     segmentTimeline} is non-null then this parameter is ignored.
     * @param segmentTimeline A segment timeline corresponding to the segments. If null, then
     *     segments are assumed to be of fixed duration as specified by the {@code duration}
     *     parameter.
     * @param availabilityTimeOffsetUs The offset to the current realtime at which segments become
     *     available in microseconds, or {@link C#TIME_UNSET} if not applicable.
     * @param timeShiftBufferDepthUs The time shift buffer depth in microseconds.
     * @param periodStartUnixTimeUs The start of the enclosing period in microseconds since the Unix
     *     epoch.
     */
    public MultiSegmentBase(
        @Nullable RangedUri initialization,
        long timescale,
        long presentationTimeOffset,
        long startNumber,
        long duration,
        @Nullable List<SegmentTimelineElement> segmentTimeline,
        long availabilityTimeOffsetUs,
        long timeShiftBufferDepthUs,
        long periodStartUnixTimeUs) {
      this(
          initialization,
          timescale,
          presentationTimeOffset,
          startNumber,
          duration,
          segmentTimeline,
          availabilityTimeOffsetUs,
          timeShiftBufferDepthUs,
          periodStartUnixTimeUs,
          /* peerMaxCountHolder= */ null);
    }

    /**
     * Primary constructor with peer max holder for track-aware low-latency extrapolation.
     *
     * @param peerMaxCountHolder LL-Core: Period-scoped holder set by the parser after parsing all
     *     AdaptationSets. Index 0 holds the max {@code segmentTimeline.size()} across LL tracks in
     *     the enclosing Period, or 0 if the Period has no LL tracks. Pass {@code null} to opt out
     *     of track-aware sync (fallback to {@code count + 1}).
     */
    public MultiSegmentBase(
        @Nullable RangedUri initialization,
        long timescale,
        long presentationTimeOffset,
        long startNumber,
        long duration,
        @Nullable List<SegmentTimelineElement> segmentTimeline,
        long availabilityTimeOffsetUs,
        long timeShiftBufferDepthUs,
        long periodStartUnixTimeUs,
        @Nullable long[] peerMaxCountHolder) {
      super(initialization, timescale, presentationTimeOffset);
      this.startNumber = startNumber;
      this.duration = duration;
      this.segmentTimeline = segmentTimeline;
      this.availabilityTimeOffsetUs = availabilityTimeOffsetUs;
      this.timeShiftBufferDepthUs = timeShiftBufferDepthUs;
      this.periodStartUnixTimeUs = periodStartUnixTimeUs;
      this.peerMaxCountHolder = peerMaxCountHolder;
    }

    /**
     * Returns whether this segment base is configured for low-latency DASH streaming.
     *
     * <p>A stream is considered low-latency when {@code availabilityTimeOffsetUs} is set
     * (not {@link C#TIME_UNSET}), indicating the server supports early segment requests
     * via chunked transfer encoding.
     */
    public final boolean isLowLatency() {
      return availabilityTimeOffsetUs != C.TIME_UNSET;
    }

    /** See {@link DashSegmentIndex#getSegmentNum(long, long)}. */
    public long getSegmentNum(long timeUs, long periodDurationUs) {
      final long firstSegmentNum = getFirstSegmentNum();
      final long segmentCount = getSegmentCount(periodDurationUs);
      if (segmentCount == 0) {
        return firstSegmentNum;
      }
      if (segmentTimeline == null) {
        // All segments are of equal duration (with the possible exception of the last one).
        long durationUs = (duration * C.MICROS_PER_SECOND) / timescale;
        long segmentNum = startNumber + timeUs / durationUs;
        // Ensure we stay within bounds.
        return segmentNum < firstSegmentNum
            ? firstSegmentNum
            : segmentCount == INDEX_UNBOUNDED
                ? segmentNum
                : min(segmentNum, firstSegmentNum + segmentCount - 1);
      } else {
        // The index cannot be unbounded. Identify the segment using binary search.
        long lowIndex = firstSegmentNum;
        long highIndex = firstSegmentNum + segmentCount - 1;
        while (lowIndex <= highIndex) {
          long midIndex = lowIndex + (highIndex - lowIndex) / 2;
          long midTimeUs = getSegmentTimeUs(midIndex);
          if (midTimeUs < timeUs) {
            lowIndex = midIndex + 1;
          } else if (midTimeUs > timeUs) {
            highIndex = midIndex - 1;
          } else {
            return midIndex;
          }
        }
        return lowIndex == firstSegmentNum ? lowIndex : highIndex;
      }
    }

    /** See {@link DashSegmentIndex#getDurationUs(long, long)}. */
    public final long getSegmentDurationUs(long sequenceNumber, long periodDurationUs) {
      if (segmentTimeline != null) {
        int idx = (int) (sequenceNumber - startNumber);
        // LL-Core: For LL-DASH only, if idx is beyond the timeline (extrapolated segment +1),
        // use the last entry's duration. For non-LL streams, idx should always be in range.
        if (idx >= segmentTimeline.size() && isLowLatency()) {
          idx = segmentTimeline.size() - 1;
        }
        long duration = segmentTimeline.get(idx).duration;
        return (duration * C.MICROS_PER_SECOND) / timescale;
      } else {
        long segmentCount = getSegmentCount(periodDurationUs);
        return segmentCount != INDEX_UNBOUNDED
                && sequenceNumber == (getFirstSegmentNum() + segmentCount - 1)
            ? (periodDurationUs - getSegmentTimeUs(sequenceNumber))
            : ((duration * C.MICROS_PER_SECOND) / timescale);
      }
    }

    /** See {@link DashSegmentIndex#getTimeUs(long)}. */
    public final long getSegmentTimeUs(long sequenceNumber) {
      long unscaledSegmentTime;
      if (segmentTimeline != null) {
        int idx = (int) (sequenceNumber - startNumber);
        if (idx < segmentTimeline.size()) {
          unscaledSegmentTime = segmentTimeline.get(idx).startTime - presentationTimeOffset;
        } else if (isLowLatency()) {
          // LL-Core: For LL-DASH only, extrapolate time for the next incomplete segment
          // beyond the timeline. Non-LL streams should never reach here.
          SegmentTimelineElement last = segmentTimeline.get(segmentTimeline.size() - 1);
          long segmentsBeyond = idx - segmentTimeline.size() + 1;
          unscaledSegmentTime =
              (last.startTime + last.duration * segmentsBeyond) - presentationTimeOffset;
        } else {
          // Non-LL stream: fallback to last entry (should not happen in normal flow).
          unscaledSegmentTime =
              segmentTimeline.get(segmentTimeline.size() - 1).startTime - presentationTimeOffset;
        }
      } else {
        unscaledSegmentTime = (sequenceNumber - startNumber) * duration;
      }
      return Util.scaleLargeTimestamp(unscaledSegmentTime, C.MICROS_PER_SECOND, timescale);
    }

    /**
     * Returns a {@link RangedUri} defining the location of a segment for the given index in the
     * given representation.
     *
     * <p>See {@link DashSegmentIndex#getSegmentUrl(long)}.
     */
    public abstract RangedUri getSegmentUrl(Representation representation, long index);

    /** See {@link DashSegmentIndex#getFirstSegmentNum()}. */
    public long getFirstSegmentNum() {
      return startNumber;
    }

    /** See {@link DashSegmentIndex#getFirstAvailableSegmentNum(long, long)}. */
    public long getFirstAvailableSegmentNum(long periodDurationUs, long nowUnixTimeUs) {
      long segmentCount = getSegmentCount(periodDurationUs);
      if (segmentCount != INDEX_UNBOUNDED || timeShiftBufferDepthUs == C.TIME_UNSET) {
        return getFirstSegmentNum();
      }
      // The index is itself unbounded. We need to use the current time to calculate the range of
      // available segments.
      long liveEdgeTimeInPeriodUs = nowUnixTimeUs - periodStartUnixTimeUs;
      long timeShiftBufferStartInPeriodUs = liveEdgeTimeInPeriodUs - timeShiftBufferDepthUs;
      long timeShiftBufferStartSegmentNum =
          getSegmentNum(timeShiftBufferStartInPeriodUs, periodDurationUs);
      return max(getFirstSegmentNum(), timeShiftBufferStartSegmentNum);
    }

    /** See {@link DashSegmentIndex#getAvailableSegmentCount(long, long)}. */
    public long getAvailableSegmentCount(long periodDurationUs, long nowUnixTimeUs) {
      long segmentCount = getSegmentCount(periodDurationUs);
      if (segmentCount != INDEX_UNBOUNDED) {
        return segmentCount;
      }
      // The index is itself unbounded. We need to use the current time to calculate the range of
      // available segments.
      long liveEdgeTimeInPeriodUs = nowUnixTimeUs - periodStartUnixTimeUs;
      long availabilityTimeOffsetUs = liveEdgeTimeInPeriodUs + this.availabilityTimeOffsetUs;
      // getSegmentNum(availabilityTimeOffsetUs) will not be completed yet.
      long firstIncompleteSegmentNum = getSegmentNum(availabilityTimeOffsetUs, periodDurationUs);
      long firstAvailableSegmentNum = getFirstAvailableSegmentNum(periodDurationUs, nowUnixTimeUs);
      long availableCount = firstIncompleteSegmentNum - firstAvailableSegmentNum;
      // LL-Core: For LL-DASH (availabilityTimeOffsetUs != TIME_UNSET), include the incomplete
      // segment so the player can stream it chunk-by-chunk via chunked transfer.
      if (isLowLatency()) {
        availableCount++;
      }
      return (int) availableCount;
    }

    /** See {@link DashSegmentIndex#getNextSegmentAvailableTimeUs(long, long)}. */
    public long getNextSegmentAvailableTimeUs(long periodDurationUs, long nowUnixTimeUs) {
      if (segmentTimeline != null) {
        return C.TIME_UNSET;
      }
      long firstIncompleteSegmentNum =
          getFirstAvailableSegmentNum(periodDurationUs, nowUnixTimeUs)
              + getAvailableSegmentCount(periodDurationUs, nowUnixTimeUs);
      return getSegmentTimeUs(firstIncompleteSegmentNum)
          + getSegmentDurationUs(firstIncompleteSegmentNum, periodDurationUs)
          - availabilityTimeOffsetUs;
    }

    /** See {@link DashSegmentIndex#isExplicit()} */
    public boolean isExplicit() {
      return segmentTimeline != null;
    }

    /** See {@link DashSegmentIndex#getSegmentCount(long)}. */
    public abstract long getSegmentCount(long periodDurationUs);
  }

  /** A {@link MultiSegmentBase} that uses a SegmentList to define its segments. */
  public static final class SegmentList extends MultiSegmentBase {

    @Nullable /* package */ final List<RangedUri> mediaSegments;

    /**
     * @param initialization A {@link RangedUri} corresponding to initialization data, if such data
     *     exists.
     * @param timescale The timescale in units per second.
     * @param presentationTimeOffset The presentation time offset. The value in seconds is the
     *     division of this value and {@code timescale}.
     * @param startNumber The sequence number of the first segment.
     * @param duration The duration of each segment in the case of fixed duration segments. The
     *     value in seconds is the division of this value and {@code timescale}. If {@code
     *     segmentTimeline} is non-null then this parameter is ignored.
     * @param segmentTimeline A segment timeline corresponding to the segments. If null, then
     *     segments are assumed to be of fixed duration as specified by the {@code duration}
     *     parameter.
     * @param availabilityTimeOffsetUs The offset to the current realtime at which segments become
     *     available in microseconds, or {@link C#TIME_UNSET} if not applicable.
     * @param mediaSegments A list of {@link RangedUri}s indicating the locations of the segments.
     * @param timeShiftBufferDepthUs The time shift buffer depth in microseconds.
     * @param periodStartUnixTimeUs The start of the enclosing period in microseconds since the Unix
     *     epoch.
     */
    public SegmentList(
        RangedUri initialization,
        long timescale,
        long presentationTimeOffset,
        long startNumber,
        long duration,
        @Nullable List<SegmentTimelineElement> segmentTimeline,
        long availabilityTimeOffsetUs,
        @Nullable List<RangedUri> mediaSegments,
        long timeShiftBufferDepthUs,
        long periodStartUnixTimeUs) {
      super(
          initialization,
          timescale,
          presentationTimeOffset,
          startNumber,
          duration,
          segmentTimeline,
          availabilityTimeOffsetUs,
          timeShiftBufferDepthUs,
          periodStartUnixTimeUs);
      this.mediaSegments = mediaSegments;
    }

    @Override
    public RangedUri getSegmentUrl(Representation representation, long sequenceNumber) {
      return mediaSegments.get((int) (sequenceNumber - startNumber));
    }

    @Override
    public long getSegmentCount(long periodDurationUs) {
      return mediaSegments.size();
    }

    @Override
    public boolean isExplicit() {
      return true;
    }
  }

  /** A {@link MultiSegmentBase} that uses a SegmentTemplate to define its segments. */
  public static final class SegmentTemplate extends MultiSegmentBase {

    @Nullable /* package */ final UrlTemplate initializationTemplate;
    @Nullable /* package */ final UrlTemplate mediaTemplate;
    /* package */ final long endNumber;

    /**
     * Backward-compatible constructor without peer max holder. Delegates to the primary
     * constructor with {@code peerMaxCountHolder = null}, preserving pre-track-aware behavior.
     *
     * @param initialization A {@link RangedUri} corresponding to initialization data, if such data
     *     exists. The value of this parameter is ignored if {@code initializationTemplate} is
     *     non-null.
     * @param timescale The timescale in units per second.
     * @param presentationTimeOffset The presentation time offset. The value in seconds is the
     *     division of this value and {@code timescale}.
     * @param startNumber The sequence number of the first segment.
     * @param endNumber The sequence number of the last segment as specified by the
     *     SupplementalProperty with schemeIdUri="http://dashif.org/guidelines/last-segment-number",
     *     or {@link C#INDEX_UNSET}.
     * @param duration The duration of each segment in the case of fixed duration segments. The
     *     value in seconds is the division of this value and {@code timescale}. If {@code
     *     segmentTimeline} is non-null then this parameter is ignored.
     * @param segmentTimeline A segment timeline corresponding to the segments. If null, then
     *     segments are assumed to be of fixed duration as specified by the {@code duration}
     *     parameter.
     * @param availabilityTimeOffsetUs The offset to the current realtime at which segments become
     *     available in microseconds, or {@link C#TIME_UNSET} if not applicable.
     * @param initializationTemplate A template defining the location of initialization data, if
     *     such data exists. If non-null then the {@code initialization} parameter is ignored. If
     *     null then {@code initialization} will be used.
     * @param mediaTemplate A template defining the location of each media segment.
     * @param timeShiftBufferDepthUs The time shift buffer depth in microseconds.
     * @param periodStartUnixTimeUs The start of the enclosing period in microseconds since the Unix
     *     epoch.
     */
    public SegmentTemplate(
        RangedUri initialization,
        long timescale,
        long presentationTimeOffset,
        long startNumber,
        long endNumber,
        long duration,
        @Nullable List<SegmentTimelineElement> segmentTimeline,
        long availabilityTimeOffsetUs,
        @Nullable UrlTemplate initializationTemplate,
        @Nullable UrlTemplate mediaTemplate,
        long timeShiftBufferDepthUs,
        long periodStartUnixTimeUs) {
      this(
          initialization,
          timescale,
          presentationTimeOffset,
          startNumber,
          endNumber,
          duration,
          segmentTimeline,
          availabilityTimeOffsetUs,
          initializationTemplate,
          mediaTemplate,
          timeShiftBufferDepthUs,
          periodStartUnixTimeUs,
          /* peerMaxCountHolder= */ null);
    }

    /**
     * Primary constructor with peer max holder for track-aware low-latency extrapolation.
     *
     * @param peerMaxCountHolder LL-Core: Period-scoped holder set by the parser after parsing all
     *     AdaptationSets. Index 0 stores the max {@code segmentTimeline.size()} across LL tracks
     *     in the enclosing Period, or 0 if the Period has no LL tracks. Pass {@code null} to opt
     *     out of track-aware sync (fallback to {@code count + 1}).
     */
    public SegmentTemplate(
        RangedUri initialization,
        long timescale,
        long presentationTimeOffset,
        long startNumber,
        long endNumber,
        long duration,
        @Nullable List<SegmentTimelineElement> segmentTimeline,
        long availabilityTimeOffsetUs,
        @Nullable UrlTemplate initializationTemplate,
        @Nullable UrlTemplate mediaTemplate,
        long timeShiftBufferDepthUs,
        long periodStartUnixTimeUs,
        @Nullable long[] peerMaxCountHolder) {
      super(
          initialization,
          timescale,
          presentationTimeOffset,
          startNumber,
          duration,
          segmentTimeline,
          availabilityTimeOffsetUs,
          timeShiftBufferDepthUs,
          periodStartUnixTimeUs,
          peerMaxCountHolder);
      this.initializationTemplate = initializationTemplate;
      this.mediaTemplate = mediaTemplate;
      this.endNumber = endNumber;
    }

    @Override
    @Nullable
    public RangedUri getInitialization(Representation representation) {
      if (initializationTemplate != null) {
        String urlString =
            initializationTemplate.buildUri(
                representation.format.id, 0, representation.format.bitrate, 0);
        return new RangedUri(urlString, 0, C.LENGTH_UNSET);
      } else {
        return super.getInitialization(representation);
      }
    }

    @Override
    public RangedUri getSegmentUrl(Representation representation, long sequenceNumber) {
      long time;
      if (segmentTimeline != null) {
        int idx = (int) (sequenceNumber - startNumber);
        if (idx < segmentTimeline.size()) {
          time = segmentTimeline.get(idx).startTime;
        } else if (isLowLatency()) {
          // LL-Core: For LL-DASH only, extrapolate time for the next incomplete segment.
          SegmentTimelineElement last = segmentTimeline.get(segmentTimeline.size() - 1);
          long segmentsBeyond = idx - segmentTimeline.size() + 1;
          time = last.startTime + last.duration * segmentsBeyond;
        } else {
          // Non-LL stream: fallback to last entry (should not happen in normal flow).
          time = segmentTimeline.get(segmentTimeline.size() - 1).startTime;
        }
      } else {
        time = (sequenceNumber - startNumber) * duration;
      }
      String uriString =
          mediaTemplate.buildUri(
              representation.format.id, sequenceNumber, representation.format.bitrate, time);
      return new RangedUri(uriString, 0, C.LENGTH_UNSET);
    }

    @Override
    public long getAvailableSegmentCount(long periodDurationUs, long nowUnixTimeUs) {
      long count = super.getAvailableSegmentCount(periodDurationUs, nowUnixTimeUs);
      // LL-Core: Track-aware extrapolation. Gated by isLowLatency() so non-LL streams
      // (VOD, non-LL live, DVR) bypass entirely. When this track's timeline lags peers
      // (e.g. audio MPD trailing video MPD by 1 cycle under CCU load), Math.max(count,
      // peerMax) bumps the lagging track up to peer max before the +1 — the player
      // can fetch the next in-progress segment via chunked transfer instead of WAIT-ing
      // for the next MPD refresh cycle. In-sync tracks and the fallback path (null or
      // 0 holder) degrade to the original count + 1.
      if (segmentTimeline != null && isLowLatency()) {
        long peerMax =
            (peerMaxCountHolder != null && peerMaxCountHolder[0] > 0)
                ? peerMaxCountHolder[0]
                : 0L;
        long result = Math.max(count, peerMax) + 1;
        // LL-Core: Log only when the track-aware bump actually applies (count < peerMax),
        // so log volume stays bounded — only the lagging track emits under CCU load. In
        // steady state, all tracks are in sync so this branch is skipped.
        if (LowLatencyLog.isFull() && peerMax > 0 && count < peerMax) {
          LowLatencyLog.d(
              "PeerMax",
              "BUMP: count=" + count + " peerMax=" + peerMax + " → " + result);
        }
        return result;
      }
      return count;
    }

    @Override
    public long getSegmentCount(long periodDurationUs) {
      if (segmentTimeline != null) {
        return segmentTimeline.size();
      } else if (endNumber != C.INDEX_UNSET) {
        return endNumber - startNumber + 1;
      } else if (periodDurationUs != C.TIME_UNSET) {
        BigInteger numerator =
            BigInteger.valueOf(periodDurationUs).multiply(BigInteger.valueOf(timescale));
        BigInteger denominator =
            BigInteger.valueOf(duration).multiply(BigInteger.valueOf(C.MICROS_PER_SECOND));
        return BigIntegerMath.divide(numerator, denominator, RoundingMode.CEILING).longValue();
      } else {
        return INDEX_UNBOUNDED;
      }
    }
  }

  /** Represents a timeline segment from the MPD's SegmentTimeline list. */
  public static final class SegmentTimelineElement {

    /* package */ final long startTime;
    /* package */ final long duration;

    /**
     * @param startTime The start time of the element. The value in seconds is the division of this
     *     value and the {@code timescale} of the enclosing element.
     * @param duration The duration of the element. The value in seconds is the division of this
     *     value and the {@code timescale} of the enclosing element.
     */
    public SegmentTimelineElement(long startTime, long duration) {
      this.startTime = startTime;
      this.duration = duration;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SegmentTimelineElement that = (SegmentTimelineElement) o;
      return startTime == that.startTime && duration == that.duration;
    }

    @Override
    public int hashCode() {
      return 31 * (int) startTime + (int) duration;
    }
  }
}
