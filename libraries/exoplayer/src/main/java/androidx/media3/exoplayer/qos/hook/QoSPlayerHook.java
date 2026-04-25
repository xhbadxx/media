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

import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qos.QoSMonitor;

/**
 * Listens to {@link Player} state changes and signals {@link QoSMonitor} on every
 * rebuffer event. Rebuffer is detected as a {@link Player#STATE_READY} →
 * {@link Player#STATE_BUFFERING} transition.
 *
 * <p>Initial buffering ({@link Player#STATE_IDLE} → {@link Player#STATE_BUFFERING})
 * and re-buffering after {@link Player#STATE_ENDED} are NOT counted, matching CMCD
 * {@code bs} semantics ({@code bs} marks stalls during active playback only).
 *
 * <p>Attach BEFORE {@code player.play()} so the very first rebuffer is captured.
 *
 * <p>Usage (consumer side):
 * <pre>{@code
 *   exoPlayer.addListener(new QoSPlayerHook());
 * }</pre>
 */
@UnstableApi
public final class QoSPlayerHook implements Player.Listener {

  private int previousState = Player.STATE_IDLE;

  @Override
  public void onPlaybackStateChanged(int playbackState) {
    if (playbackState == Player.STATE_BUFFERING && previousState == Player.STATE_READY) {
      QoSMonitor.getInstance().markPendingBufferStarvation();
    }
    previousState = playbackState;
  }
}