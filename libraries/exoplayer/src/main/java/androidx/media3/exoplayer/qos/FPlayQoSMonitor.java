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

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.qos.hook.QoSAnalyticsHook;
import androidx.media3.exoplayer.qos.hook.QoSPlayerHook;
import androidx.media3.exoplayer.qos.hook.QoSTransferListener;
import androidx.media3.exoplayer.qos.observer.QoSObserver;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

/**
 * FPlay-facing entry point for QoS monitoring. Hooks into an existing player and the
 * pieces the host app already uses ({@link BandwidthMeter}, {@link QoSTransferListener})
 * — does not own or create any of those dependencies.
 *
 * <p>Wiring split (because the player must already be built before listeners can register):
 * <ul>
 *   <li>App creates its own {@link BandwidthMeter} and {@link QoSTransferListener},
 *       wires them into {@code ExoPlayer.Builder.setBandwidthMeter(...)} and
 *       {@code DataSource.Factory.setTransferListener(...)} respectively.
 *   <li>After {@code player = builder.build()}, instantiate this class and call
 *       {@link #attach(ExoPlayer, BandwidthMeter, QoSTransferListener)} to register
 *       the QoS listeners.
 *   <li>Before {@code player.release()}, call {@link #detach()} to remove the
 *       listeners and reset {@link QoSMonitor} singleton state.
 * </ul>
 *
 * <p>Both {@code bandwidthMeter} and {@code transferListener} are optional. Pass the
 * same instances the player is using to enable accurate {@code mtp} (from load 1) and
 * {@code ttfbMs} respectively; pass {@code null} to skip those fields.
 *
 * <p>A built-in debug observer logs every recorded entry to logcat under tag
 * {@code FPlayQoS} (filter with {@code adb logcat -s FPlayQoS}).
 *
 * <p>Single-player lifecycle assumed: instantiate → attach → use → detach → release →
 * (optional) re-attach with a new player. Concurrent multi-instance use is not
 * supported because {@link QoSMonitor} is a process-wide singleton — multiple
 * {@code FPlayQoSMonitor} instances would share entries/observers and collide.
 *
 * <p>Example:
 * <pre>{@code
 *   BandwidthMeter meter = ...;                            // app's existing instance
 *   QoSTransferListener qosTransfer = new QoSTransferListener();
 *
 *   DataSource.Factory ds = appExistingDsFactory.setTransferListener(qosTransfer);
 *
 *   ExoPlayer player = new ExoPlayer.Builder(context)
 *       .setBandwidthMeter(meter)
 *       .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
 *       .build();
 *
 *   FPlayQoSMonitor qos = new FPlayQoSMonitor();
 *   qos.attach(player, meter, qosTransfer);
 *   // ... player.play() / use ...
 *   qos.detach();
 *   player.release();
 * }</pre>
 */
@UnstableApi
public final class FPlayQoSMonitor {

  private static final String TAG = "FPlayQoS";

  @Nullable private ExoPlayer activePlayer;
  @Nullable private QoSAnalyticsHook activeAnalyticsHook;
  @Nullable private QoSPlayerHook activePlayerHook;

  private final QoSObserver debugObserver =
      entries -> {
        if (entries.isEmpty()) return;
        Log.d(TAG, entries.get(entries.size() - 1).toLogString());
      };

  public FPlayQoSMonitor() {}

  /**
   * Attaches QoS analytics + state listeners to {@code player} and registers the
   * built-in debug observer. Call when the player is built, before {@code player.play()}
   * so the very first rebuffer is captured. Pass {@code null} for any dependency the
   * host app is not using.
   *
   * <p>If a previous attach was not detached, this defensively detaches it first.
   */
  public void attach(
      ExoPlayer player,
      @Nullable BandwidthMeter bandwidthMeter,
      @Nullable QoSTransferListener transferListener) {
    if (activePlayer != null) detach();
    QoSAnalyticsHook analyticsHook =
        new QoSAnalyticsHook(player, bandwidthMeter, transferListener);
    QoSPlayerHook playerHook = new QoSPlayerHook();
    player.addAnalyticsListener(analyticsHook);
    player.addListener(playerHook);
    QoSMonitor.getInstance().addObserver(debugObserver);
    activePlayer = player;
    activeAnalyticsHook = analyticsHook;
    activePlayerHook = playerHook;
  }

  /**
   * Removes hooks from the active player and fully resets {@link QoSMonitor} singleton
   * state (entries, observers, pending bs flags). Call BEFORE {@code player.release()}.
   * Idempotent — no-op if not currently attached.
   */
  public void detach() {
    QoSMonitor.getInstance().reset();
    ExoPlayer player = activePlayer;
    if (player == null) return;
    if (activeAnalyticsHook != null) player.removeAnalyticsListener(activeAnalyticsHook);
    if (activePlayerHook != null) player.removeListener(activePlayerHook);
    activePlayer = null;
    activeAnalyticsHook = null;
    activePlayerHook = null;
  }
}