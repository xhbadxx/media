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

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link SegmentBase}. */
@RunWith(AndroidJUnit4.class)
public final class SegmentBaseTest {

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
        holder);
  }
}
