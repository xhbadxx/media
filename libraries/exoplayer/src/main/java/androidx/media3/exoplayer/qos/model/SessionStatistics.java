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
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Session-internal rolling baselines for V5 Tukey fence outlier detection.
 *
 * <p>Maintains rolling windows of TTFB and post-TTFB delivery rate samples per
 * {@code (networkType, cacheStatus, cdnHostname)} key. Computes Tukey 1977 IQR fence
 * (Q1, Q3, upper = Q3 + 1.5*IQR, lower = max(0, Q1 - 1.5*IQR)).
 *
 * <p>Lifecycle: 1 instance per playback session. Update post-segment-COMPLETED via
 * {@link #addTtfbSample} / {@link #addDeliveryRateSample}.
 *
 * <p>Anchor: Tukey, J. W. (1977). <em>Exploratory Data Analysis</em>. Addison-Wesley.
 */
@UnstableApi
public final class SessionStatistics {

  /** Window size for rolling percentile per key (~1 minute live segments). */
  public static final int ROLLING_WINDOW_SIZE = 30;

  /** Below this sample count → fence not computable, fallback to absolute threshold. */
  public static final int MIN_SAMPLES_FOR_TUKEY = 10;

  /** Tukey 1.5 × IQR multiplier (mild outlier per Tukey 1977). */
  public static final double TUKEY_K = 1.5;

  private final Map<String, Deque<Integer>> ttfbByKey = new HashMap<>();
  private final Map<String, Deque<Integer>> deliveryRateByKey = new HashMap<>();
  private final Map<String, Deque<Integer>> mtpByKey = new HashMap<>();

  /** Add a TTFB sample (ms) for the given key. Auto-evicts oldest when over capacity. */
  public void addTtfbSample(String key, int ttfbMs) {
    addSample(ttfbByKey, key, ttfbMs);
  }

  /** Add a post-TTFB delivery rate sample (kbps) for the given key. */
  public void addDeliveryRateSample(String key, int kbps) {
    addSample(deliveryRateByKey, key, kbps);
  }

  /** Add an ABR-measured throughput (mtp) sample (kbps). Used for V6 mtp-collapse guard. */
  public void addMtpSample(String key, int kbps) {
    addSample(mtpByKey, key, kbps);
  }

  /** Returns Tukey fence over current TTFB samples, or {@code null} if &lt; min samples. */
  @Nullable
  public TukeyFence getTtfbFence(String key) {
    return computeFence(ttfbByKey.get(key));
  }

  /** Returns Tukey fence over current delivery rate samples, or {@code null} if &lt; min. */
  @Nullable
  public TukeyFence getDeliveryRateFence(String key) {
    return computeFence(deliveryRateByKey.get(key));
  }

  /** Returns Tukey fence over current mtp samples, or {@code null} if &lt; min samples. */
  @Nullable
  public TukeyFence getMtpFence(String key) {
    return computeFence(mtpByKey.get(key));
  }

  private static void addSample(Map<String, Deque<Integer>> map, String key, int value) {
    Deque<Integer> q = map.get(key);
    if (q == null) {
      q = new ArrayDeque<>(ROLLING_WINDOW_SIZE + 1);
      map.put(key, q);
    }
    q.addLast(value);
    while (q.size() > ROLLING_WINDOW_SIZE) {
      q.removeFirst();
    }
  }

  @Nullable
  private static TukeyFence computeFence(@Nullable Deque<Integer> samples) {
    if (samples == null || samples.size() < MIN_SAMPLES_FOR_TUKEY) {
      return null;
    }
    int[] sorted = new int[samples.size()];
    int i = 0;
    for (int v : samples) {
      sorted[i++] = v;
    }
    Arrays.sort(sorted);
    int q1 = tukeyHingesQ1(sorted);
    int q3 = tukeyHingesQ3(sorted);
    int iqr = q3 - q1;
    int upper = q3 + (int) Math.round(TUKEY_K * iqr);
    int lower = Math.max(0, q1 - (int) Math.round(TUKEY_K * iqr));
    return new TukeyFence(q1, q3, iqr, upper, lower);
  }

  /** Q1 = median of lower half (Tukey hinges; for odd n, exclude middle). */
  static int tukeyHingesQ1(int[] sorted) {
    int n = sorted.length;
    int halfLen = n / 2; // floor — exclude median for odd n
    return medianOf(sorted, 0, halfLen);
  }

  /** Q3 = median of upper half (Tukey hinges). */
  static int tukeyHingesQ3(int[] sorted) {
    int n = sorted.length;
    int startUpper = (n % 2 == 0) ? n / 2 : (n / 2) + 1;
    return medianOf(sorted, startUpper, n);
  }

  /** Median of {@code sorted[from..to)}. */
  private static int medianOf(int[] sorted, int from, int to) {
    int len = to - from;
    if (len % 2 == 1) {
      return sorted[from + len / 2];
    }
    return (sorted[from + len / 2 - 1] + sorted[from + len / 2]) / 2;
  }

  /** Tukey IQR fence result. */
  public static final class TukeyFence {
    public final int q1;
    public final int q3;
    public final int iqr;
    public final int upperFence;
    public final int lowerFence;

    public TukeyFence(int q1, int q3, int iqr, int upperFence, int lowerFence) {
      this.q1 = q1;
      this.q3 = q3;
      this.iqr = iqr;
      this.upperFence = upperFence;
      this.lowerFence = lowerFence;
    }
  }
}
