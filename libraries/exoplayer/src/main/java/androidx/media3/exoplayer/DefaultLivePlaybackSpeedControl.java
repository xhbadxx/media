/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.primitives.Longs.max;
import static java.lang.Math.abs;
import static java.lang.Math.max;

import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem.LiveConfiguration;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.util.LowLatencyLog;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Locale;

/**
 * A {@link LivePlaybackSpeedControl} that adjusts the playback speed using a proportional
 * controller.
 *
 * <p>The control mechanism calculates the adjusted speed as {@code 1.0 + proportionalControlFactor
 * x (currentLiveOffsetSec - targetLiveOffsetSec)}. Unit speed (1.0f) is used, if the {@code
 * currentLiveOffsetSec} is closer to {@code targetLiveOffsetSec} than the value set with {@link
 * Builder#setMaxLiveOffsetErrorMsForUnitSpeed(long)}.
 *
 * <p>The resulting speed is clamped to a minimum and maximum speed defined by the media, the
 * fallback values set with {@link Builder#setFallbackMinPlaybackSpeed(float)} and {@link
 * Builder#setFallbackMaxPlaybackSpeed(float)} or the {@link #DEFAULT_FALLBACK_MIN_PLAYBACK_SPEED
 * minimum} and {@link #DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED maximum} fallback default values.
 *
 * <p>When the player rebuffers, the target live offset {@link
 * Builder#setTargetLiveOffsetIncrementOnRebufferMs(long) is increased} to adjust to the reduced
 * network capabilities. The live playback speed control also {@link
 * Builder#setMinPossibleLiveOffsetSmoothingFactor(float) keeps track} of the minimum possible live
 * offset to decrease the target live offset again if conditions improve. The minimum possible live
 * offset is derived from the current offset and the duration of buffered media.
 */
@UnstableApi
public final class DefaultLivePlaybackSpeedControl implements LivePlaybackSpeedControl {

  /**
   * The default minimum factor by which playback can be sped up that should be used if no minimum
   * playback speed is defined by the media.
   */
  public static final float DEFAULT_FALLBACK_MIN_PLAYBACK_SPEED = 0.97f;

  /**
   * The default maximum factor by which playback can be sped up that should be used if no maximum
   * playback speed is defined by the media.
   */
  public static final float DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED = 1.03f;

  /**
   * The default {@link Builder#setMinUpdateIntervalMs(long) minimum interval} between playback
   * speed changes, in milliseconds.
   */
  public static final long DEFAULT_MIN_UPDATE_INTERVAL_MS = 1_000;

  /**
   * The default {@link Builder#setProportionalControlFactor(float) proportional control factor}
   * used to adjust the playback speed.
   */
  public static final float DEFAULT_PROPORTIONAL_CONTROL_FACTOR = 0.1f;

  /**
   * The default increment applied to the target live offset each time the player is rebuffering, in
   * milliseconds
   */
  public static final long DEFAULT_TARGET_LIVE_OFFSET_INCREMENT_ON_REBUFFER_MS = 500;

  /**
   * The default smoothing factor when smoothing the minimum possible live offset that can be
   * achieved during playback.
   */
  public static final float DEFAULT_MIN_POSSIBLE_LIVE_OFFSET_SMOOTHING_FACTOR = 0.999f;

  /**
   * The default maximum difference between the current live offset and the target live offset, in
   * milliseconds, for which unit speed (1.0f) is used.
   */
  public static final long DEFAULT_MAX_LIVE_OFFSET_ERROR_MS_FOR_UNIT_SPEED = 20;

  /** Builder for a {@link DefaultLivePlaybackSpeedControl}. */
  public static final class Builder {

    private float fallbackMinPlaybackSpeed;
    private float fallbackMaxPlaybackSpeed;
    private long minUpdateIntervalMs;
    private float proportionalControlFactorUs;
    private long maxLiveOffsetErrorUsForUnitSpeed;
    private long targetLiveOffsetIncrementOnRebufferUs;
    private float minPossibleLiveOffsetSmoothingFactor;

    /** Creates a builder. */
    public Builder() {
      fallbackMinPlaybackSpeed = DEFAULT_FALLBACK_MIN_PLAYBACK_SPEED;
      fallbackMaxPlaybackSpeed = DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED;
      minUpdateIntervalMs = DEFAULT_MIN_UPDATE_INTERVAL_MS;
      proportionalControlFactorUs = DEFAULT_PROPORTIONAL_CONTROL_FACTOR / C.MICROS_PER_SECOND;
      maxLiveOffsetErrorUsForUnitSpeed =
          Util.msToUs(DEFAULT_MAX_LIVE_OFFSET_ERROR_MS_FOR_UNIT_SPEED);
      targetLiveOffsetIncrementOnRebufferUs =
          Util.msToUs(DEFAULT_TARGET_LIVE_OFFSET_INCREMENT_ON_REBUFFER_MS);
      minPossibleLiveOffsetSmoothingFactor = DEFAULT_MIN_POSSIBLE_LIVE_OFFSET_SMOOTHING_FACTOR;
    }

    /**
     * Sets the minimum playback speed that should be used if no minimum playback speed is defined
     * by the media.
     *
     * <p>The default is {@link #DEFAULT_FALLBACK_MIN_PLAYBACK_SPEED}.
     *
     * @param fallbackMinPlaybackSpeed The fallback minimum factor by which playback can be sped up.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setFallbackMinPlaybackSpeed(float fallbackMinPlaybackSpeed) {
      checkArgument(0 < fallbackMinPlaybackSpeed && fallbackMinPlaybackSpeed <= 1f);
      this.fallbackMinPlaybackSpeed = fallbackMinPlaybackSpeed;
      return this;
    }

    /**
     * Sets the maximum playback speed that should be used if no maximum playback speed is defined
     * by the media.
     *
     * <p>The default is {@link #DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED}.
     *
     * @param fallbackMaxPlaybackSpeed The fallback maximum factor by which playback can be sped up.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setFallbackMaxPlaybackSpeed(float fallbackMaxPlaybackSpeed) {
      checkArgument(fallbackMaxPlaybackSpeed >= 1f);
      this.fallbackMaxPlaybackSpeed = fallbackMaxPlaybackSpeed;
      return this;
    }

    /**
     * Sets the minimum interval between playback speed changes, in milliseconds.
     *
     * <p>The default is {@link #DEFAULT_MIN_UPDATE_INTERVAL_MS}.
     *
     * @param minUpdateIntervalMs The minimum interval between playback speed changes, in
     *     milliseconds.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setMinUpdateIntervalMs(long minUpdateIntervalMs) {
      checkArgument(minUpdateIntervalMs > 0);
      this.minUpdateIntervalMs = minUpdateIntervalMs;
      return this;
    }

    /**
     * Sets the proportional control factor used to adjust the playback speed.
     *
     * <p>The factor by which playback will be sped up is calculated as {@code 1.0 +
     * proportionalControlFactor x (currentLiveOffsetSec - targetLiveOffsetSec)}.
     *
     * <p>The default is {@link #DEFAULT_PROPORTIONAL_CONTROL_FACTOR}.
     *
     * @param proportionalControlFactor The proportional control factor used to adjust the playback
     *     speed.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setProportionalControlFactor(float proportionalControlFactor) {
      checkArgument(proportionalControlFactor > 0);
      this.proportionalControlFactorUs = proportionalControlFactor / C.MICROS_PER_SECOND;
      return this;
    }

    /**
     * Sets the maximum difference between the current live offset and the target live offset, in
     * milliseconds, for which unit speed (1.0f) is used.
     *
     * <p>The default is {@link #DEFAULT_MAX_LIVE_OFFSET_ERROR_MS_FOR_UNIT_SPEED}.
     *
     * @param maxLiveOffsetErrorMsForUnitSpeed The maximum live offset error for which unit speed is
     *     used, in milliseconds.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setMaxLiveOffsetErrorMsForUnitSpeed(long maxLiveOffsetErrorMsForUnitSpeed) {
      checkArgument(maxLiveOffsetErrorMsForUnitSpeed > 0);
      this.maxLiveOffsetErrorUsForUnitSpeed = Util.msToUs(maxLiveOffsetErrorMsForUnitSpeed);
      return this;
    }

    /**
     * Sets the increment applied to the target live offset each time the player is rebuffering, in
     * milliseconds.
     *
     * @param targetLiveOffsetIncrementOnRebufferMs The increment applied to the target live offset
     *     when the player is rebuffering, in milliseconds
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setTargetLiveOffsetIncrementOnRebufferMs(
        long targetLiveOffsetIncrementOnRebufferMs) {
      checkArgument(targetLiveOffsetIncrementOnRebufferMs >= 0);
      this.targetLiveOffsetIncrementOnRebufferUs =
          Util.msToUs(targetLiveOffsetIncrementOnRebufferMs);
      return this;
    }

    /**
     * Sets the smoothing factor when smoothing the minimum possible live offset that can be
     * achieved during playback.
     *
     * <p>The live playback speed control keeps track of the minimum possible live offset achievable
     * during playback to know whether it can reduce the current target live offset. The minimum
     * possible live offset is defined as {@code currentLiveOffset - bufferedDuration}. As the
     * minimum possible live offset is constantly changing, it is smoothed over recent samples by
     * applying exponential smoothing: {@code smoothedMinPossibleOffset = smoothingFactor x
     * smoothedMinPossibleOffset + (1-smoothingFactor) x currentMinPossibleOffset}.
     *
     * @param minPossibleLiveOffsetSmoothingFactor The smoothing factor. Must be &ge; 0 and &lt; 1.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setMinPossibleLiveOffsetSmoothingFactor(
        float minPossibleLiveOffsetSmoothingFactor) {
      checkArgument(
          minPossibleLiveOffsetSmoothingFactor >= 0 && minPossibleLiveOffsetSmoothingFactor < 1f);
      this.minPossibleLiveOffsetSmoothingFactor = minPossibleLiveOffsetSmoothingFactor;
      return this;
    }

    /** Builds an instance. */
    public DefaultLivePlaybackSpeedControl build() {
      return new DefaultLivePlaybackSpeedControl(
          fallbackMinPlaybackSpeed,
          fallbackMaxPlaybackSpeed,
          minUpdateIntervalMs,
          proportionalControlFactorUs,
          maxLiveOffsetErrorUsForUnitSpeed,
          targetLiveOffsetIncrementOnRebufferUs,
          minPossibleLiveOffsetSmoothingFactor);
    }
  }

  private final float fallbackMinPlaybackSpeed;
  private final float fallbackMaxPlaybackSpeed;
  private final long minUpdateIntervalMs;
  private final float proportionalControlFactor;
  private final long maxLiveOffsetErrorUsForUnitSpeed;
  private final long targetLiveOffsetRebufferDeltaUs;
  private final float minPossibleLiveOffsetSmoothingFactor;

  private long mediaConfigurationTargetLiveOffsetUs;
  private long targetLiveOffsetOverrideUs;
  private long idealTargetLiveOffsetUs;
  private long minTargetLiveOffsetUs;
  private long maxTargetLiveOffsetUs;
  private long currentTargetLiveOffsetUs;

  private float maxPlaybackSpeed;
  private float minPlaybackSpeed;
  private float adjustedPlaybackSpeed;
  private long lastPlaybackSpeedUpdateMs;

  private long smoothedMinPossibleLiveOffsetUs;
  private long smoothedMinPossibleLiveOffsetDeviationUs;

  // Rate-limited tick logging (every LowLatencyLog.TICK_LOG_INTERVAL_MS).
  private long lastLowLatencyLogMs;

  private DefaultLivePlaybackSpeedControl(
      float fallbackMinPlaybackSpeed,
      float fallbackMaxPlaybackSpeed,
      long minUpdateIntervalMs,
      float proportionalControlFactor,
      long maxLiveOffsetErrorUsForUnitSpeed,
      long targetLiveOffsetRebufferDeltaUs,
      float minPossibleLiveOffsetSmoothingFactor) {
    this.fallbackMinPlaybackSpeed = fallbackMinPlaybackSpeed;
    this.fallbackMaxPlaybackSpeed = fallbackMaxPlaybackSpeed;
    this.minUpdateIntervalMs = minUpdateIntervalMs;
    this.proportionalControlFactor = proportionalControlFactor;
    this.maxLiveOffsetErrorUsForUnitSpeed = maxLiveOffsetErrorUsForUnitSpeed;
    this.targetLiveOffsetRebufferDeltaUs = targetLiveOffsetRebufferDeltaUs;
    this.minPossibleLiveOffsetSmoothingFactor = minPossibleLiveOffsetSmoothingFactor;
    mediaConfigurationTargetLiveOffsetUs = C.TIME_UNSET;
    targetLiveOffsetOverrideUs = C.TIME_UNSET;
    minTargetLiveOffsetUs = C.TIME_UNSET;
    maxTargetLiveOffsetUs = C.TIME_UNSET;
    minPlaybackSpeed = fallbackMinPlaybackSpeed;
    maxPlaybackSpeed = fallbackMaxPlaybackSpeed;
    adjustedPlaybackSpeed = 1.0f;
    lastPlaybackSpeedUpdateMs = C.TIME_UNSET;
    idealTargetLiveOffsetUs = C.TIME_UNSET;
    currentTargetLiveOffsetUs = C.TIME_UNSET;
    smoothedMinPossibleLiveOffsetUs = C.TIME_UNSET;
    smoothedMinPossibleLiveOffsetDeviationUs = C.TIME_UNSET;
    lastLowLatencyLogMs = C.TIME_UNSET;
    if (LowLatencyLog.isFull()) {
      LowLatencyLog.d(
          "SpeedCtrl",
          String.format(
              Locale.US,
              "init: fallbackSpeed=[%.3f,%.3f] minUpdateInterval=%dms proportionalFactor=%.6f/us"
                  + " unitSpeedErrorWindow=%dms rebufferIncrement=%dms",
              fallbackMinPlaybackSpeed,
              fallbackMaxPlaybackSpeed,
              minUpdateIntervalMs,
              proportionalControlFactor,
              Util.usToMs(maxLiveOffsetErrorUsForUnitSpeed),
              Util.usToMs(targetLiveOffsetRebufferDeltaUs)));
    }
  }

  @Override
  public void setLiveConfiguration(LiveConfiguration liveConfiguration) {
    if (LowLatencyLog.isFull()) {
      LowLatencyLog.d(
          "SpeedCtrl",
          String.format(
              Locale.US,
              "setLiveConfiguration: target=%dms min=%dms max=%dms speed=[%.3f,%.3f]"
                  + " (fallbackSpeed=[%.3f,%.3f])",
              liveConfiguration.targetOffsetMs,
              liveConfiguration.minOffsetMs,
              liveConfiguration.maxOffsetMs,
              liveConfiguration.minPlaybackSpeed,
              liveConfiguration.maxPlaybackSpeed,
              fallbackMinPlaybackSpeed,
              fallbackMaxPlaybackSpeed));
    }
    mediaConfigurationTargetLiveOffsetUs = Util.msToUs(liveConfiguration.targetOffsetMs);
    minTargetLiveOffsetUs = Util.msToUs(liveConfiguration.minOffsetMs);
    maxTargetLiveOffsetUs = Util.msToUs(liveConfiguration.maxOffsetMs);
    minPlaybackSpeed =
        liveConfiguration.minPlaybackSpeed != C.RATE_UNSET
            ? liveConfiguration.minPlaybackSpeed
            : fallbackMinPlaybackSpeed;
    maxPlaybackSpeed =
        liveConfiguration.maxPlaybackSpeed != C.RATE_UNSET
            ? liveConfiguration.maxPlaybackSpeed
            : fallbackMaxPlaybackSpeed;
    if (minPlaybackSpeed == 1f && maxPlaybackSpeed == 1f) {
      // Don't bother calculating adjustments if it's not possible to change the speed.
      mediaConfigurationTargetLiveOffsetUs = C.TIME_UNSET;
    }
    maybeResetTargetLiveOffsetUs();
  }

  @Override
  public void setTargetLiveOffsetOverrideUs(long liveOffsetUs) {
    targetLiveOffsetOverrideUs = liveOffsetUs;
    maybeResetTargetLiveOffsetUs();
  }

  @Override
  public void notifyRebuffer() {
    if (currentTargetLiveOffsetUs == C.TIME_UNSET) {
      if (LowLatencyLog.isEnabled()) {
        LowLatencyLog.w("SpeedCtrl", "REBUFFER: ignored — no current target yet");
      }
      return;
    }
    long oldTargetUs = currentTargetLiveOffsetUs;
    currentTargetLiveOffsetUs += targetLiveOffsetRebufferDeltaUs;
    boolean clamped = false;
    if (maxTargetLiveOffsetUs != C.TIME_UNSET
        && currentTargetLiveOffsetUs > maxTargetLiveOffsetUs) {
      currentTargetLiveOffsetUs = maxTargetLiveOffsetUs;
      clamped = true;
    }
    lastPlaybackSpeedUpdateMs = C.TIME_UNSET;
    if (LowLatencyLog.isEnabled()) {
      LowLatencyLog.d(
          "SpeedCtrl",
          String.format(
              Locale.US,
              "REBUFFER: target %dms → %dms (increment=%dms, max=%dms, clamped=%s)",
              Util.usToMs(oldTargetUs),
              Util.usToMs(currentTargetLiveOffsetUs),
              Util.usToMs(targetLiveOffsetRebufferDeltaUs),
              maxTargetLiveOffsetUs == C.TIME_UNSET ? -1 : Util.usToMs(maxTargetLiveOffsetUs),
              clamped));
    }
  }

  @Override
  public float getAdjustedPlaybackSpeed(long liveOffsetUs, long bufferedDurationUs) {
    if (mediaConfigurationTargetLiveOffsetUs == C.TIME_UNSET) {
      return 1f;
    }

    updateSmoothedMinPossibleLiveOffsetUs(liveOffsetUs, bufferedDurationUs);

    if (lastPlaybackSpeedUpdateMs != C.TIME_UNSET
        && SystemClock.elapsedRealtime() - lastPlaybackSpeedUpdateMs < minUpdateIntervalMs) {
      maybeLogTick(liveOffsetUs, bufferedDurationUs, /* cached= */ true);
      return adjustedPlaybackSpeed;
    }
    lastPlaybackSpeedUpdateMs = SystemClock.elapsedRealtime();

    long targetBeforeAdjustUs = currentTargetLiveOffsetUs;
    adjustTargetLiveOffsetUs(liveOffsetUs);
    long liveOffsetErrorUs = liveOffsetUs - currentTargetLiveOffsetUs;
    float previousSpeed = adjustedPlaybackSpeed;
    if (Math.abs(liveOffsetErrorUs) < maxLiveOffsetErrorUsForUnitSpeed) {
      adjustedPlaybackSpeed = 1f;
    } else {
      float calculatedSpeed = 1f + proportionalControlFactor * liveOffsetErrorUs;
      adjustedPlaybackSpeed =
          Util.constrainValue(calculatedSpeed, minPlaybackSpeed, maxPlaybackSpeed);
    }
    if (LowLatencyLog.isEnabled() && targetBeforeAdjustUs != currentTargetLiveOffsetUs) {
      long safeUs = smoothedMinPossibleLiveOffsetUs
          + 3 * smoothedMinPossibleLiveOffsetDeviationUs;
      LowLatencyLog.d(
          "SpeedCtrl",
          String.format(
              Locale.US,
              "targetAdjust: %dms → %dms (ideal=%dms liveOff=%dms safe=%dms"
                  + " sMinP=%dms sDev=%dms)",
              Util.usToMs(targetBeforeAdjustUs),
              Util.usToMs(currentTargetLiveOffsetUs),
              Util.usToMs(idealTargetLiveOffsetUs),
              Util.usToMs(liveOffsetUs),
              Util.usToMs(safeUs),
              Util.usToMs(smoothedMinPossibleLiveOffsetUs),
              Util.usToMs(smoothedMinPossibleLiveOffsetDeviationUs)));
    }
    float speedChangeThreshold = LowLatencyLog.isRebufferTest() ? 0.01f : 0.001f;
    if (LowLatencyLog.isEnabled()
        && Math.abs(adjustedPlaybackSpeed - previousSpeed) > speedChangeThreshold) {
      LowLatencyLog.d(
          "SpeedCtrl",
          String.format(
              Locale.US,
              "speedChange: %.3f → %.3f (err=%+dms target=%dms liveOff=%dms bounds=[%.3f,%.3f])",
              previousSpeed,
              adjustedPlaybackSpeed,
              Util.usToMs(liveOffsetErrorUs),
              Util.usToMs(currentTargetLiveOffsetUs),
              Util.usToMs(liveOffsetUs),
              minPlaybackSpeed,
              maxPlaybackSpeed));
    }
    maybeLogTick(liveOffsetUs, bufferedDurationUs, /* cached= */ false);
    return adjustedPlaybackSpeed;
  }

  private void maybeLogTick(long liveOffsetUs, long bufferedDurationUs, boolean cached) {
    if (!LowLatencyLog.isEnabled()) {
      return;
    }
    // In REBUFFER_TEST mode, only log tick when buffer is below threshold.
    if (LowLatencyLog.isRebufferTest()
        && Util.usToMs(bufferedDurationUs) > LowLatencyLog.rebufferTestBufferThresholdMs) {
      return;
    }
    long nowMs = SystemClock.elapsedRealtime();
    if (lastLowLatencyLogMs != C.TIME_UNSET
        && nowMs - lastLowLatencyLogMs < LowLatencyLog.TICK_LOG_INTERVAL_MS) {
      return;
    }
    lastLowLatencyLogMs = nowMs;
    long errorUs = liveOffsetUs - currentTargetLiveOffsetUs;
    LowLatencyLog.d(
        "SpeedCtrl",
        String.format(
            Locale.US,
            "tick: liveOff=%5dms curTarget=%5dms idealTarget=%5dms err=%+5dms speed=%.3f"
                + " buf=%5dms minPossible=%5dms%s",
            Util.usToMs(liveOffsetUs),
            Util.usToMs(currentTargetLiveOffsetUs),
            idealTargetLiveOffsetUs == C.TIME_UNSET ? -1 : Util.usToMs(idealTargetLiveOffsetUs),
            Util.usToMs(errorUs),
            adjustedPlaybackSpeed,
            Util.usToMs(bufferedDurationUs),
            smoothedMinPossibleLiveOffsetUs == C.TIME_UNSET
                ? -1
                : Util.usToMs(smoothedMinPossibleLiveOffsetUs),
            cached ? " (cached)" : ""));
  }

  @Override
  public long getTargetLiveOffsetUs() {
    return currentTargetLiveOffsetUs;
  }

  private void maybeResetTargetLiveOffsetUs() {
    long idealOffsetUs = C.TIME_UNSET;
    if (mediaConfigurationTargetLiveOffsetUs != C.TIME_UNSET) {
      if (targetLiveOffsetOverrideUs != C.TIME_UNSET) {
        idealOffsetUs = targetLiveOffsetOverrideUs;
      } else {
        idealOffsetUs = mediaConfigurationTargetLiveOffsetUs;
        if (minTargetLiveOffsetUs != C.TIME_UNSET && idealOffsetUs < minTargetLiveOffsetUs) {
          idealOffsetUs = minTargetLiveOffsetUs;
        }
        if (maxTargetLiveOffsetUs != C.TIME_UNSET && idealOffsetUs > maxTargetLiveOffsetUs) {
          idealOffsetUs = maxTargetLiveOffsetUs;
        }
      }
    }
    if (idealTargetLiveOffsetUs == idealOffsetUs) {
      return;
    }
    idealTargetLiveOffsetUs = idealOffsetUs;
    currentTargetLiveOffsetUs = idealOffsetUs;
    smoothedMinPossibleLiveOffsetUs = C.TIME_UNSET;
    smoothedMinPossibleLiveOffsetDeviationUs = C.TIME_UNSET;
    lastPlaybackSpeedUpdateMs = C.TIME_UNSET;
  }

  private void updateSmoothedMinPossibleLiveOffsetUs(long liveOffsetUs, long bufferedDurationUs) {
    long minPossibleLiveOffsetUs = liveOffsetUs - bufferedDurationUs;
    if (smoothedMinPossibleLiveOffsetUs == C.TIME_UNSET) {
      smoothedMinPossibleLiveOffsetUs = minPossibleLiveOffsetUs;
      smoothedMinPossibleLiveOffsetDeviationUs = 0;
    } else {
      // Use the maximum here to ensure we keep track of the upper bound of what is safely possible,
      // not the average.
      smoothedMinPossibleLiveOffsetUs =
          max(
              minPossibleLiveOffsetUs,
              smooth(
                  smoothedMinPossibleLiveOffsetUs,
                  minPossibleLiveOffsetUs,
                  minPossibleLiveOffsetSmoothingFactor));
      long minPossibleLiveOffsetDeviationUs =
          abs(minPossibleLiveOffsetUs - smoothedMinPossibleLiveOffsetUs);
      smoothedMinPossibleLiveOffsetDeviationUs =
          smooth(
              smoothedMinPossibleLiveOffsetDeviationUs,
              minPossibleLiveOffsetDeviationUs,
              minPossibleLiveOffsetSmoothingFactor);
    }
  }

  private void adjustTargetLiveOffsetUs(long liveOffsetUs) {
    // Stay in a safe distance (3 standard deviations = >99%) to the minimum possible live offset.
    long safeOffsetUs =
        smoothedMinPossibleLiveOffsetUs + 3 * smoothedMinPossibleLiveOffsetDeviationUs;
    if (currentTargetLiveOffsetUs > safeOffsetUs) {
      // There is room for decreasing the target offset towards the ideal or safe offset (whichever
      // is larger). We want to limit the decrease so that the playback speed delta we achieve is
      // the same as the maximum delta when slowing down towards the target.
      long minUpdateIntervalUs = Util.msToUs(minUpdateIntervalMs);
      long decrementToOffsetCurrentSpeedUs =
          (long) ((adjustedPlaybackSpeed - 1f) * minUpdateIntervalUs);
      long decrementToIncreaseSpeedUs = (long) ((maxPlaybackSpeed - 1f) * minUpdateIntervalUs);
      long maxDecrementUs = decrementToOffsetCurrentSpeedUs + decrementToIncreaseSpeedUs;
      currentTargetLiveOffsetUs =
          max(safeOffsetUs, idealTargetLiveOffsetUs, currentTargetLiveOffsetUs - maxDecrementUs);
    } else {
      // We'd like to reach a stable condition where the current live offset stays just below the
      // safe offset. But don't increase the target offset to more than what would allow us to slow
      // down gradually from the current offset.
      long offsetWhenSlowingDownNowUs =
          liveOffsetUs - (long) (max(0f, adjustedPlaybackSpeed - 1f) / proportionalControlFactor);
      currentTargetLiveOffsetUs =
          Util.constrainValue(offsetWhenSlowingDownNowUs, currentTargetLiveOffsetUs, safeOffsetUs);
      if (maxTargetLiveOffsetUs != C.TIME_UNSET
          && currentTargetLiveOffsetUs > maxTargetLiveOffsetUs) {
        currentTargetLiveOffsetUs = maxTargetLiveOffsetUs;
      }
    }
  }

  private static long smooth(long smoothedValue, long newValue, float smoothingFactor) {
    return (long) (smoothingFactor * smoothedValue + (1f - smoothingFactor) * newValue);
  }
}
