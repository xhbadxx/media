/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.exoplayer.util.Utils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link SegmentBase}. */
@RunWith(AndroidJUnit4.class)
public final class SegmentBaseTest {
  /**
   * LL-Core: {@code Utils.IS_LOW_LATENCY} is a static, mutable, app-level switch that ships {@code
   * false}. The low-latency tests below turn it on for themselves — class-wide would be wrong, since
   * the non-LL tests in this same file assert the vanilla counts and start failing the moment it is
   * on. Captured and restored around every test so nothing leaks into later tests in the same JVM.
   */
  @Before
  public void captureLowLatencyFlag() {
    wasLowLatency = Utils.IS_LOW_LATENCY;
  }

  @After
  public void restoreLowLatencyFlag() {
    Utils.IS_LOW_LATENCY = wasLowLatency;
  }

  private boolean wasLowLatency;


  @Test
  public void getFirstAvailableSegmentNum_unboundedSegmentTemplate() {
    long periodStartUnixTimeUs = 123_000_000_000_000L;
    SegmentBase.SegmentTemplate segmentTemplate =
        new SegmentBase.SegmentTemplate(
            /* initialization= */ null,
            /* timescale= */ 1000,
            /* presentationTimeOffset= */ 0,
            /* startNumber= */ 42,
            /* endNumber= */ C.INDEX_UNSET,
            /* duration= */ 2000,
            /* segmentTimeline= */ null,
            /* availabilityTimeOffsetUs= */ 500_000,
            /* initializationTemplate= */ null,
            /* mediaTemplate= */ null,
            /* timeShiftBufferDepthUs= */ 6_000_000,
            /* periodStartUnixTimeUs= */ periodStartUnixTimeUs);

    assertThat(
            segmentTemplate.getFirstAvailableSegmentNum(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs - 10_000_000))
        .isEqualTo(42);
    assertThat(
            segmentTemplate.getFirstAvailableSegmentNum(
                /* periodDurationUs= */ C.TIME_UNSET, /* nowUnixTimeUs= */ periodStartUnixTimeUs))
        .isEqualTo(42);
    assertThat(
            segmentTemplate.getFirstAvailableSegmentNum(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 7_999_999))
        .isEqualTo(42);
    assertThat(
            segmentTemplate.getFirstAvailableSegmentNum(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 8_000_000))
        .isEqualTo(43);
    assertThat(
            segmentTemplate.getFirstAvailableSegmentNum(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 9_999_999))
        .isEqualTo(43);
    assertThat(
            segmentTemplate.getFirstAvailableSegmentNum(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 10_000_000))
        .isEqualTo(44);
  }

  @Test
  public void getAvailableSegmentCount_unboundedSegmentTemplate() {
    long periodStartUnixTimeUs = 123_000_000_000_000L;
    SegmentBase.SegmentTemplate segmentTemplate =
        new SegmentBase.SegmentTemplate(
            /* initialization= */ null,
            /* timescale= */ 1000,
            /* presentationTimeOffset= */ 0,
            /* startNumber= */ 42,
            /* endNumber= */ C.INDEX_UNSET,
            /* duration= */ 2000,
            /* segmentTimeline= */ null,
            /* availabilityTimeOffsetUs= */ 500_000,
            /* initializationTemplate= */ null,
            /* mediaTemplate= */ null,
            /* timeShiftBufferDepthUs= */ 6_000_000,
            /* periodStartUnixTimeUs= */ periodStartUnixTimeUs);

    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs - 10_000_000))
        .isEqualTo(0);
    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET, /* nowUnixTimeUs= */ periodStartUnixTimeUs))
        .isEqualTo(0);
    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 1_499_999))
        .isEqualTo(0);
    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 1_500_000))
        .isEqualTo(1);
    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 7_499_999))
        .isEqualTo(3);
    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 7_500_000))
        .isEqualTo(4);
    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 7_999_999))
        .isEqualTo(4);
    assertThat(
            segmentTemplate.getAvailableSegmentCount(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 8_000_000))
        .isEqualTo(3);
  }

  @Test
  public void getNextSegmentShiftTimeUse_unboundedSegmentTemplate() {
    long periodStartUnixTimeUs = 123_000_000_000_000L;
    SegmentBase.SegmentTemplate segmentTemplate =
        new SegmentBase.SegmentTemplate(
            /* initialization= */ null,
            /* timescale= */ 1000,
            /* presentationTimeOffset= */ 0,
            /* startNumber= */ 42,
            /* endNumber= */ C.INDEX_UNSET,
            /* duration= */ 2000,
            /* segmentTimeline= */ null,
            /* availabilityTimeOffsetUs= */ 500_000,
            /* initializationTemplate= */ null,
            /* mediaTemplate= */ null,
            /* timeShiftBufferDepthUs= */ 6_000_000,
            /* periodStartUnixTimeUs= */ periodStartUnixTimeUs);

    assertThat(
            segmentTemplate.getNextSegmentAvailableTimeUs(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs - 10_000_000))
        .isEqualTo(1_500_000);
    assertThat(
            segmentTemplate.getNextSegmentAvailableTimeUs(
                /* periodDurationUs= */ C.TIME_UNSET, /* nowUnixTimeUs= */ periodStartUnixTimeUs))
        .isEqualTo(1_500_000);
    assertThat(
            segmentTemplate.getNextSegmentAvailableTimeUs(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 1_499_999))
        .isEqualTo(1_500_000);
    assertThat(
            segmentTemplate.getNextSegmentAvailableTimeUs(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 1_500_000))
        .isEqualTo(3_500_000);
    assertThat(
            segmentTemplate.getNextSegmentAvailableTimeUs(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 17_499_999))
        .isEqualTo(17_500_000);
    assertThat(
            segmentTemplate.getNextSegmentAvailableTimeUs(
                /* periodDurationUs= */ C.TIME_UNSET,
                /* nowUnixTimeUs= */ periodStartUnixTimeUs + 17_500_000))
        .isEqualTo(19_500_000);
  }

  /** Regression test for https://github.com/google/ExoPlayer/issues/8804. */
  @Test
  public void getSegmentCount_withSegmentTemplate_avoidsIncorrectRounding() {
    SegmentBase.SegmentTemplate segmentTemplate =
        new SegmentBase.SegmentTemplate(
            /* initialization= */ null,
            /* timescale= */ 90000,
            /* presentationTimeOffset= */ 0,
            /* startNumber= */ 0,
            /* endNumber= */ C.INDEX_UNSET,
            /* duration= */ 179989,
            /* segmentTimeline= */ null,
            /* availabilityTimeOffsetUs= */ C.TIME_UNSET,
            /* initializationTemplate= */ null,
            /* mediaTemplate= */ null,
            /* timeShiftBufferDepthUs= */ C.TIME_UNSET,
            /* periodStartUnixTimeUs= */ C.TIME_UNSET);
    assertThat(segmentTemplate.getSegmentCount(2931820000L)).isEqualTo(1466);
  }

  @Test
  public void getSegmentCount_withSegmentTemplate_avoidsOverflow() {
    SegmentBase.SegmentTemplate segmentTemplate =
        new SegmentBase.SegmentTemplate(
            /* initialization= */ null,
            /* timescale= */ 1000000,
            /* presentationTimeOffset= */ 0,
            /* startNumber= */ 0,
            /* endNumber= */ C.INDEX_UNSET,
            /* duration= */ 179989,
            /* segmentTimeline= */ null,
            /* availabilityTimeOffsetUs= */ C.TIME_UNSET,
            /* initializationTemplate= */ null,
            /* mediaTemplate= */ null,
            /* timeShiftBufferDepthUs= */ C.TIME_UNSET,
            /* periodStartUnixTimeUs= */ C.TIME_UNSET);
    assertThat(segmentTemplate.getSegmentCount(1618875028000000L)).isEqualTo(8994299808L);
  }

  // LL-Core: Track-aware Fix 2 tests.

  @Test
  public void getAvailableSegmentCount_llAudioLagsVideo_bumpsAudioToMatchVideoPlusOne() {
    Utils.IS_LOW_LATENCY = true;
    long[] holder = new long[] {0L};
    SegmentBase.SegmentTemplate video = createLLTemplateWithTimelineSize(9, holder);
    SegmentBase.SegmentTemplate audio = createLLTemplateWithTimelineSize(8, holder);
    holder[0] = 9L;

    assertThat(video.getAvailableSegmentCount(C.TIME_UNSET, /* nowUnixTimeUs= */ 0))
        .isEqualTo(10L);
    assertThat(audio.getAvailableSegmentCount(C.TIME_UNSET, /* nowUnixTimeUs= */ 0))
        .isEqualTo(10L);
  }

  @Test
  public void getAvailableSegmentCount_llTracksInSync_behavesAsPlusOne() {
    Utils.IS_LOW_LATENCY = true;
    long[] holder = new long[] {0L};
    SegmentBase.SegmentTemplate video = createLLTemplateWithTimelineSize(9, holder);
    SegmentBase.SegmentTemplate audio = createLLTemplateWithTimelineSize(9, holder);
    holder[0] = 9L;

    assertThat(video.getAvailableSegmentCount(C.TIME_UNSET, /* nowUnixTimeUs= */ 0))
        .isEqualTo(10L);
    assertThat(audio.getAvailableSegmentCount(C.TIME_UNSET, /* nowUnixTimeUs= */ 0))
        .isEqualTo(10L);
  }

  @Test
  public void getAvailableSegmentCount_llHolderUnset_fallsBackToPlusOne() {
    Utils.IS_LOW_LATENCY = true;
    long[] holder = new long[] {0L};
    SegmentBase.SegmentTemplate video = createLLTemplateWithTimelineSize(9, holder);

    assertThat(video.getAvailableSegmentCount(C.TIME_UNSET, /* nowUnixTimeUs= */ 0))
        .isEqualTo(10L);
  }

  @Test
  public void getAvailableSegmentCount_nonLL_ignoresHolder() {
    long[] holder = new long[] {20L};
    SegmentBase.SegmentTemplate nonLL = createNonLLTemplateWithTimelineSize(9, holder);

    assertThat(nonLL.getAvailableSegmentCount(C.TIME_UNSET, /* nowUnixTimeUs= */ 0))
        .isEqualTo(9L);
  }

  private static SegmentBase.SegmentTemplate createLLTemplateWithTimelineSize(
      int size, long[] holder) {
    return createTemplate(size, /* availabilityTimeOffsetUs= */ 1_680_000L, holder);
  }

  private static SegmentBase.SegmentTemplate createNonLLTemplateWithTimelineSize(
      int size, long[] holder) {
    return createTemplate(size, /* availabilityTimeOffsetUs= */ C.TIME_UNSET, holder);
  }


  // ---------------------------------------------------------------------------------------------
  // getSegmentRequestableTimeUs — the safe-request gate's clock. Every value below is chosen so the
  // arithmetic can be checked by hand: timescale 1e6 makes one tick one microsecond, segments are
  // 2s, and the anchor puts the producer exactly 2s behind the nominal availabilityStartTime
  // timeline. requestable(seg) = segStart + producerLag + segDuration − availabilityTimeOffset.
  // ---------------------------------------------------------------------------------------------

  /** A stream is not low-latency, so the gate must not have an opinion about it at all. */
  @Test
  public void getSegmentRequestableTimeUs_lowLatencyFlagOff_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = false;
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(anchorHolder(10_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /**
   * {@code availabilityTimeOffset} of zero means the manifest makes no early-availability promise,
   * which is what {@link SegmentBase.MultiSegmentBase#isLowLatency()} treats as not-low-latency.
   */
  @Test
  public void getSegmentRequestableTimeUs_zeroAvailabilityTimeOffset_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template =
        createAnchoredTemplate(
            /* timescale= */ 1_000_000L,
            /* presentationTimeOffset= */ 0L,
            /* availabilityTimeOffsetUs= */ 0L,
            /* periodStartUnixTimeUs= */ 0L,
            anchorHolder(10_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /** No {@code <ProducerReferenceTime>} in this AdaptationSet: nothing to anchor on. */
  @Test
  public void getSegmentRequestableTimeUs_nullAnchorHolder_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(/* anchorHolder= */ null);

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /**
   * The holder exists but the tag has not been parsed yet — the window between constructing the
   * template and reaching {@code <ProducerReferenceTime>}, which may appear after SegmentTemplate.
   */
  @Test
  public void getSegmentRequestableTimeUs_anchorNotYetParsed_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(new AtomicReference<>());

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /** A malformed pair must bail out rather than read past the end of the array. */
  @Test
  public void getSegmentRequestableTimeUs_anchorArrayTooShort_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    AtomicReference<long[]> holder = new AtomicReference<>(new long[] {10_000L});
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(holder);

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /** Below {@code startNumber} the timeline lookup would be out of range. */
  @Test
  public void getSegmentRequestableTimeUs_sequenceNumberBelowStartNumber_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(anchorHolder(10_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 0, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /** Without a period start the anchor's wall clock cannot be put on the period timeline. */
  @Test
  public void getSegmentRequestableTimeUs_unsetPeriodStart_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template =
        createAnchoredTemplate(
            /* timescale= */ 1_000_000L,
            /* presentationTimeOffset= */ 0L,
            /* availabilityTimeOffsetUs= */ 1_500_000L,
            /* periodStartUnixTimeUs= */ C.TIME_UNSET,
            anchorHolder(10_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /**
   * The formula itself. Anchor says media time 8s was produced at wall clock 10s, so the producer
   * runs 2s behind the period timeline. Segment 1 spans [0s, 2s), and the manifest promises it 1.5s
   * before it ends — so it is genuinely requestable at 0 + 2 + 2 − 1.5 = 2.5s.
   */
  @Test
  public void getSegmentRequestableTimeUs_returnsSegmentEndPlusProducerLagMinusAvailabilityOffset() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(anchorHolder(10_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(2_500_000L);
  }

  /** Consecutive segments must be exactly one segment duration apart, with no drift. */
  @Test
  public void getSegmentRequestableTimeUs_consecutiveSegments_advanceByOneSegmentDuration() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(anchorHolder(10_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 2, C.TIME_UNSET))
        .isEqualTo(4_500_000L);
    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 3, C.TIME_UNSET))
        .isEqualTo(6_500_000L);
  }

  /**
   * {@code presentationTimeOffset} must come off both the anchor's {@code presentationTime} and the
   * segment's {@code $Time$}, because the manifest states both on the same absolute media timeline.
   * Applied to both, it cancels and cannot shift the gate; applied to one side only it would move
   * every decision by the offset — on a real stream, by whole segments. So the two templates below
   * describe the same real content, one with the media timeline renumbered by 1s.
   */
  @Test
  public void getSegmentRequestableTimeUs_presentationTimeOffsetCancelsOut() {
    Utils.IS_LOW_LATENCY = true;
    long offset = 1_000_000L;
    SegmentBase.SegmentTemplate withoutOffset =
        createAnchoredTemplate(
            /* timescale= */ 1_000_000L,
            /* presentationTimeOffset= */ 0L,
            /* availabilityTimeOffsetUs= */ 1_500_000L,
            /* periodStartUnixTimeUs= */ 0L,
            anchorHolder(/* wallClockMs= */ 10_000L, /* presentationTimeTicks= */ 8_000_000L));
    SegmentBase.SegmentTemplate withOffset =
        createAnchoredTemplate(
            /* timescale= */ 1_000_000L,
            /* presentationTimeOffset= */ offset,
            /* availabilityTimeOffsetUs= */ 1_500_000L,
            /* periodStartUnixTimeUs= */ 0L,
            // Same real moment, renumbered onto the shifted media timeline.
            anchorHolder(/* wallClockMs= */ 10_000L, /* presentationTimeTicks= */ 8_000_000L + offset));

    assertThat(withOffset.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(withoutOffset.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET));
  }

  /** A different timescale must produce the same microsecond answer for the same real times. */
  @Test
  public void getSegmentRequestableTimeUs_differentTimescale_yieldsSameMicroseconds() {
    Utils.IS_LOW_LATENCY = true;
    // timescale 2e6: every tick count doubles, so the same wall-clock stream is described.
    SegmentBase.SegmentTemplate template =
        createAnchoredTemplate(
            /* timescale= */ 2_000_000L,
            /* presentationTimeOffset= */ 0L,
            /* availabilityTimeOffsetUs= */ 1_500_000L,
            /* periodStartUnixTimeUs= */ 0L,
            anchorHolder(10_000L, 16_000_000L),
            /* segmentDurationTicks= */ 4_000_000L);

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(2_500_000L);
  }

  /**
   * The anchor is re-read on every call, not captured once: the parser replaces the pair on each
   * manifest refresh and the gate has to follow it. A stale read would keep gating against a
   * producer lag from minutes ago.
   */
  @Test
  public void getSegmentRequestableTimeUs_anchorReplaced_usesNewAnchor() {
    Utils.IS_LOW_LATENCY = true;
    AtomicReference<long[]> holder = anchorHolder(10_000L, 8_000_000L);
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(holder);
    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(2_500_000L);

    // Producer now 3s behind instead of 2s.
    holder.set(new long[] {11_000L, 8_000_000L});

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(3_500_000L);
  }

  /** A later period start means less producer lag, so the segment becomes requestable earlier. */
  @Test
  public void getSegmentRequestableTimeUs_laterPeriodStart_reducesProducerLag() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template =
        createAnchoredTemplate(
            /* timescale= */ 1_000_000L,
            /* presentationTimeOffset= */ 0L,
            /* availabilityTimeOffsetUs= */ 1_500_000L,
            /* periodStartUnixTimeUs= */ 1_000_000L,
            anchorHolder(10_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(1_500_000L);
  }

  /**
   * An empty timeline has no last element to extrapolate from, so the LL +1 must not apply: with a
   * count of 1 the player would request a segment whose URL and timing both come from {@code
   * get(-1)} and crash. A server briefly publishing an empty {@code <SegmentTimeline>} (channel
   * bring-up, packager restart) has to read as "nothing available yet", not as an exception.
   */
  @Test
  public void getAvailableSegmentCount_llEmptyTimeline_returnsZeroWithoutExtrapolating() {
    Utils.IS_LOW_LATENCY = true;
    long[] holder = new long[] {5L};
    SegmentBase.SegmentTemplate template = createLLTemplateWithTimelineSize(0, holder);

    assertThat(template.getAvailableSegmentCount(C.TIME_UNSET, /* nowUnixTimeUs= */ 0)).isEqualTo(0);
  }

  /** Same reason as above, from the gate's side. */
  @Test
  public void getSegmentRequestableTimeUs_emptyTimeline_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    SegmentBase.SegmentTemplate template =
        createAnchoredTemplate(
            /* timescale= */ 1_000_000L,
            /* presentationTimeOffset= */ 0L,
            /* availabilityTimeOffsetUs= */ 1_500_000L,
            /* periodStartUnixTimeUs= */ 0L,
            anchorHolder(10_000L, 8_000_000L),
            /* segmentDurationTicks= */ 2_000_000L,
            /* timelineSize= */ 0);

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /**
   * A wallClockTime typo'd into the far future would put every requestable time in the future and
   * hold every request — a silent stall. A corrupt anchor must disable the gate, not weaponise it.
   */
  @Test
  public void getSegmentRequestableTimeUs_implausiblyLargeProducerLag_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    // Anchor claims media time 8s was produced 10 minutes into the period: lag of ~592s.
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(anchorHolder(600_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  /**
   * Negative lag says production is not behind the nominal timeline at all, so there is nothing to
   * gate — and a wrong-timezone anchor (hours negative) must not gate on garbage either.
   */
  @Test
  public void getSegmentRequestableTimeUs_negativeProducerLag_returnsTimeUnset() {
    Utils.IS_LOW_LATENCY = true;
    // Anchor claims media time 8s was produced at wall clock 5s: lag of -3s.
    SegmentBase.SegmentTemplate template = createAnchoredTemplate(anchorHolder(5_000L, 8_000_000L));

    assertThat(template.getSegmentRequestableTimeUs(/* sequenceNumber= */ 1, C.TIME_UNSET))
        .isEqualTo(C.TIME_UNSET);
  }

  private static AtomicReference<long[]> anchorHolder(
      long wallClockMs, long presentationTimeTicks) {
    return new AtomicReference<>(new long[] {wallClockMs, presentationTimeTicks});
  }

  private static SegmentBase.SegmentTemplate createAnchoredTemplate(
      @Nullable AtomicReference<long[]> anchorHolder) {
    return createAnchoredTemplate(
        /* timescale= */ 1_000_000L,
        /* presentationTimeOffset= */ 0L,
        /* availabilityTimeOffsetUs= */ 1_500_000L,
        /* periodStartUnixTimeUs= */ 0L,
        anchorHolder);
  }

  private static SegmentBase.SegmentTemplate createAnchoredTemplate(
      long timescale,
      long presentationTimeOffset,
      long availabilityTimeOffsetUs,
      long periodStartUnixTimeUs,
      @Nullable AtomicReference<long[]> anchorHolder) {
    return createAnchoredTemplate(
        timescale,
        presentationTimeOffset,
        availabilityTimeOffsetUs,
        periodStartUnixTimeUs,
        anchorHolder,
        /* segmentDurationTicks= */ 2_000_000L);
  }

  private static SegmentBase.SegmentTemplate createAnchoredTemplate(
      long timescale,
      long presentationTimeOffset,
      long availabilityTimeOffsetUs,
      long periodStartUnixTimeUs,
      @Nullable AtomicReference<long[]> anchorHolder,
      long segmentDurationTicks) {
    return createAnchoredTemplate(
        timescale,
        presentationTimeOffset,
        availabilityTimeOffsetUs,
        periodStartUnixTimeUs,
        anchorHolder,
        segmentDurationTicks,
        /* timelineSize= */ 5);
  }

  private static SegmentBase.SegmentTemplate createAnchoredTemplate(
      long timescale,
      long presentationTimeOffset,
      long availabilityTimeOffsetUs,
      long periodStartUnixTimeUs,
      @Nullable AtomicReference<long[]> anchorHolder,
      long segmentDurationTicks,
      int timelineSize) {
    List<SegmentBase.SegmentTimelineElement> timeline = new ArrayList<>();
    for (int i = 0; i < timelineSize; i++) {
      timeline.add(
          new SegmentBase.SegmentTimelineElement(
              i * segmentDurationTicks + presentationTimeOffset, segmentDurationTicks));
    }
    return new SegmentBase.SegmentTemplate(
        /* initialization= */ null,
        timescale,
        presentationTimeOffset,
        /* startNumber= */ 1L,
        /* endNumber= */ C.INDEX_UNSET,
        /* duration= */ segmentDurationTicks,
        timeline,
        availabilityTimeOffsetUs,
        /* initializationTemplate= */ null,
        /* mediaTemplate= */ UrlTemplate.compile("segment-$Number$.mp4"),
        /* timeShiftBufferDepthUs= */ 16_000_000L,
        periodStartUnixTimeUs,
        /* peerMaxCountHolder= */ null,
        anchorHolder);
  }

  private static SegmentBase.SegmentTemplate createTemplate(
      int timelineSize, long availabilityTimeOffsetUs, long[] holder) {
    List<SegmentBase.SegmentTimelineElement> timeline = new ArrayList<>();
    for (int i = 0; i < timelineSize; i++) {
      timeline.add(new SegmentBase.SegmentTimelineElement(i * 1_920_000L, 1_920_000L));
    }
    return new SegmentBase.SegmentTemplate(
        /* initialization= */ null,
        /* timescale= */ 10_000_000L,
        /* presentationTimeOffset= */ 0L,
        /* startNumber= */ 1L,
        /* endNumber= */ C.INDEX_UNSET,
        /* duration= */ 1_920_000L,
        timeline,
        availabilityTimeOffsetUs,
        /* initializationTemplate= */ null,
        /* mediaTemplate= */ UrlTemplate.compile("segment-$Number$.mp4"),
        /* timeShiftBufferDepthUs= */ 16_000_000L,
        /* periodStartUnixTimeUs= */ 0L,
        holder,
        // These tests cover peer-max extrapolation, which does not consult the producer anchor.
        /* producerAnchorHolder= */ null);
  }
}
