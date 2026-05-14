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
package androidx.media3.exoplayer.qos;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.exoplayer.qos.model.SessionStatistics;
import androidx.media3.exoplayer.qos.model.SessionStatistics.TukeyFence;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SessionStatisticsTest {

  @Test
  public void tukeyFence_below10Samples_returnsNull() {
    SessionStatistics s = new SessionStatistics();
    for (int i = 0; i < 9; i++) {
      s.addTtfbSample("WIFI_MISS_fpt", 200);
    }
    assertThat(s.getTtfbFence("WIFI_MISS_fpt")).isNull();
  }

  @Test
  public void tukeyFence_10Samples_computesQ1Q3IqrFence() {
    SessionStatistics s = new SessionStatistics();
    int[] samples = {180, 200, 200, 210, 280, 320, 350, 400, 400, 450};
    for (int v : samples) {
      s.addTtfbSample("WIFI_MISS_fpt", v);
    }
    TukeyFence f = s.getTtfbFence("WIFI_MISS_fpt");
    assertThat(f).isNotNull();
    assertThat(f.q1).isEqualTo(200);
    assertThat(f.q3).isEqualTo(400);
    assertThat(f.iqr).isEqualTo(200);
    // upper = Q3 + 1.5 * IQR = 400 + 300 = 700
    assertThat(f.upperFence).isEqualTo(700);
    // lower = max(0, Q1 - 1.5 * IQR) = max(0, -100) = 0
    assertThat(f.lowerFence).isEqualTo(0);
  }

  @Test
  public void rollingWindow_addingPastCapacity_evictsOldest() {
    SessionStatistics s = new SessionStatistics();
    // Fill to ROLLING_WINDOW_SIZE = 30 with value 100.
    for (int i = 0; i < SessionStatistics.ROLLING_WINDOW_SIZE; i++) {
      s.addTtfbSample("WIFI_MISS_fpt", 100);
    }
    // Add 1 outlier: oldest 100 evicted → buffer is 29 × 100 + 1 × 9999.
    s.addTtfbSample("WIFI_MISS_fpt", 9999);
    TukeyFence f = s.getTtfbFence("WIFI_MISS_fpt");
    // Q3 (upper-half median over 15 sorted samples [100×14, 9999]) = 100.
    assertThat(f.q3).isEqualTo(100);
    assertThat(f.q1).isEqualTo(100);
  }

  @Test
  public void multipleKeys_areIndependent() {
    SessionStatistics s = new SessionStatistics();
    for (int i = 0; i < 10; i++) {
      s.addTtfbSample("WIFI_HIT_akamai", 50);
      s.addTtfbSample("4G_MISS_fpt", 500);
    }
    assertThat(s.getTtfbFence("WIFI_HIT_akamai").q1).isEqualTo(50);
    assertThat(s.getTtfbFence("4G_MISS_fpt").q1).isEqualTo(500);
  }

  @Test
  public void deliveryRateFence_lowerFenceCalculated() {
    SessionStatistics s = new SessionStatistics();
    int[] samples = {700, 800, 850, 850, 950, 1000, 1050, 1100, 1100, 1200};
    for (int v : samples) {
      s.addDeliveryRateSample("WIFI_MISS_fpt", v);
    }
    TukeyFence f = s.getDeliveryRateFence("WIFI_MISS_fpt");
    assertThat(f.q1).isEqualTo(850);
    assertThat(f.q3).isEqualTo(1100);
    assertThat(f.iqr).isEqualTo(250);
    // lower = Q1 - 1.5 * IQR = 850 - 375 = 475
    assertThat(f.lowerFence).isEqualTo(475);
  }

  // ===== V7 audio fence tests =====

  @Test
  public void addAudioTtfb_storesAndComputesFence() {
    SessionStatistics s = new SessionStatistics();
    int[] samples = {90, 100, 100, 110, 140, 160, 175, 200, 200, 225};
    for (int v : samples) {
      s.addAudioTtfbSample("WIFI_MISS_fpt", v);
    }
    TukeyFence f = s.getAudioTtfbFence("WIFI_MISS_fpt");
    assertThat(f).isNotNull();
    assertThat(f.q1).isEqualTo(100);
    assertThat(f.q3).isEqualTo(200);
    assertThat(f.iqr).isEqualTo(100);
    assertThat(f.upperFence).isEqualTo(350); // 200 + 1.5 * 100
  }

  @Test
  public void addAudioDeliveryRate_storesAndComputesFence() {
    SessionStatistics s = new SessionStatistics();
    int[] samples = {180, 200, 200, 210, 220, 230, 240, 250, 250, 260};
    for (int v : samples) {
      s.addAudioDeliveryRateSample("WIFI_MISS_fpt", v);
    }
    TukeyFence f = s.getAudioDeliveryRateFence("WIFI_MISS_fpt");
    assertThat(f).isNotNull();
    assertThat(f.q1).isEqualTo(200);
    assertThat(f.q3).isEqualTo(250);
    assertThat(f.iqr).isEqualTo(50);
    // lower = max(0, 200 - 75) = 125
    assertThat(f.lowerFence).isEqualTo(125);
  }

  @Test
  public void audioMap_isolatedFromVideoMap() {
    SessionStatistics s = new SessionStatistics();
    // Fill audio with high TTFBs.
    for (int i = 0; i < 12; i++) {
      s.addAudioTtfbSample("WIFI_MISS_fpt", 1000 + i);
    }
    // V map should be untouched.
    assertThat(s.getTtfbFence("WIFI_MISS_fpt")).isNull();
    // Now fill V with low TTFBs.
    for (int i = 0; i < 12; i++) {
      s.addTtfbSample("WIFI_MISS_fpt", 100 + i);
    }
    // Both fences should exist with different values.
    assertThat(s.getTtfbFence("WIFI_MISS_fpt")).isNotNull();
    assertThat(s.getAudioTtfbFence("WIFI_MISS_fpt")).isNotNull();
    assertThat(s.getAudioTtfbFence("WIFI_MISS_fpt").q1)
        .isGreaterThan(s.getTtfbFence("WIFI_MISS_fpt").q3);
  }
}
