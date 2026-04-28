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
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.qos.hook.QoSAnalyticsHook;
import androidx.media3.exoplayer.qos.hook.QoSPlayerHook;
import androidx.media3.exoplayer.qos.hook.QoSTransferListener;
import androidx.media3.exoplayer.qos.model.Diagnosis;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import androidx.media3.exoplayer.qos.observer.QoSObserver;
import androidx.media3.exoplayer.qos.observer.RebufferGroupObserver;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FPlay-facing entry point for QoS monitoring. Hooks into an existing player and the
 * {@link BandwidthMeter} the host app already uses; owns its internal
 * {@link QoSTransferListener} (QoS-only — host app has no other reason to create one).
 *
 * <p>Wiring split (because the player must already be built before listeners can register):
 * <ul>
 *   <li>App creates this instance early (before building the player).
 *   <li>App wraps every {@code DataSource.Factory} that the player will use via
 *       {@link #wrapDataSourceFactory(DataSource.Factory)} BEFORE passing the factory
 *       into {@code MediaSource.Factory} / {@code ExoPlayer.Builder}. The wrap adds
 *       the QoS {@link QoSTransferListener} to every {@code DataSource} created from
 *       it (additive — coexists with any other listener already attached).
 *   <li>After {@code player = builder.build()}, call
 *       {@link #attach(ExoPlayer, BandwidthMeter)} to register the analytics +
 *       state listeners on the built player.
 *   <li>Before {@code player.release()}, call {@link #detach()} to remove the
 *       listeners, reset {@link QoSMonitor} singleton state, and clear pending
 *       TTFB so a re-attach starts fresh.
 * </ul>
 *
 * <p>{@code bandwidthMeter} is optional. Pass the same instance the player is using
 * to enable accurate {@code mtp} (from load 1); {@code null} falls back to the cached
 * estimate from {@code onBandwidthEstimate} (unset until the first load completes).
 *
 * <p>A built-in debug observer logs every recorded entry to logcat under tag
 * {@code FPlayQoS} (filter with {@code adb logcat -s FPlayQoS}).
 *
 * <p>Single-player lifecycle assumed: instantiate → wrap factories → attach → use →
 * detach → release → (optional) re-attach with a new player. Concurrent multi-instance
 * use is not supported because {@link QoSMonitor} is a process-wide singleton —
 * multiple {@code FPlayQoSMonitor} instances would share entries/observers and collide.
 *
 * <p>Example:
 * <pre>{@code
 *   FPlayQoSMonitor qos = new FPlayQoSMonitor();
 *
 *   DataSource.Factory ds = qos.wrapDataSourceFactory(appExistingDsFactory);
 *
 *   BandwidthMeter meter = ...;                            // app's existing instance
 *   ExoPlayer player = new ExoPlayer.Builder(context)
 *       .setBandwidthMeter(meter)
 *       .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
 *       .build();
 *
 *   qos.attach(player, meter);
 *   // ... player.play() / use ...
 *   qos.detach();
 *   player.release();
 * }</pre>
 */
@UnstableApi
public final class FPlayQoSMonitor {

  private static final String TAG = "FPlayQoS";

  /** Latest N entries shown in the live "Current" group (HUD). */
  static final int CURRENT_WINDOW = 10;

  /** Number of entries snapshotted into a {@link RebufferGroup} at trigger time. */
  static final int PRE_TRIGGER_WINDOW = 50;

  /** Maximum number of {@link RebufferGroup}s retained; oldest evicted FIFO. */
  static final int MAX_REBUFFER_GROUPS = 20;

  /**
   * Cooldown to suppress duplicate captures from the same rebuffer event. A single
   * READY→BUFFERING transition flags BOTH audio and video tracks (see {@link
   * QoSMonitor#markPendingBufferStarvation()}), so two bs=true entries arrive within
   * milliseconds — only the first should create a group.
   */
  static final long REBUFFER_COOLDOWN_MS = 2_000L;

  private final QoSTransferListener transferListener = new QoSTransferListener();

  private final List<RebufferGroup> rebufferGroups = new CopyOnWriteArrayList<>();
  private final List<RebufferGroupObserver> groupObservers = new CopyOnWriteArrayList<>();
  private long lastCaptureMs = 0L;
  private int nextGroupId = 0;

  @Nullable private ExoPlayer activePlayer;
  @Nullable private QoSAnalyticsHook activeAnalyticsHook;
  @Nullable private QoSPlayerHook activePlayerHook;

  private final QoSObserver debugObserver =
      entries -> {
        if (entries.isEmpty()) return;
        Log.d(TAG, entries.get(entries.size() - 1).toLogString());
      };

  /**
   * Captures a {@link RebufferGroup} on every entry with {@code bs=true}, debounced
   * by {@link #REBUFFER_COOLDOWN_MS} so audio+video duplicates from the same rebuffer
   * event produce a single group. Runs on the same thread that called
   * {@link QoSMonitor#recordInfo(QoSInfo)} (player application thread) — no locking.
   */
  private final QoSObserver groupCaptureObserver =
      entries -> {
        if (entries.isEmpty()) return;
        QoSInfo last = entries.get(entries.size() - 1);
        if (!last.bufferStarvationFlag) return;
        if (last.timestampMs - lastCaptureMs < REBUFFER_COOLDOWN_MS) return;
        lastCaptureMs = last.timestampMs;
        captureRebufferSnapshot(last, entries);
      };

  private void captureRebufferSnapshot(QoSInfo trigger, List<QoSInfo> all) {
    int from = Math.max(0, all.size() - PRE_TRIGGER_WINDOW);
    // Deep copy of reference list — RebufferGroup is frozen, must not share with
    // the live ring buffer (which evicts oldest as new entries flow in).
    List<QoSInfo> snapshot = new ArrayList<>(all.subList(from, all.size()));
    // TODO(Task 3): replace Diagnosis.unknown() with QoSDiagnoser.diagnose(...) once
    // QoSDiagnoser is implemented (Plan Task 2). UI layers must already handle
    // UNKNOWN gracefully via Diagnosis.unknown() factory.
    RebufferGroup group =
        new RebufferGroup(
            ++nextGroupId,
            trigger.timestampMs,
            trigger,
            Collections.unmodifiableList(snapshot),
            Diagnosis.unknown());
    rebufferGroups.add(group);
    while (rebufferGroups.size() > MAX_REBUFFER_GROUPS) {
      rebufferGroups.remove(0);
    }
    List<RebufferGroup> groupSnapshot = Collections.unmodifiableList(new ArrayList<>(rebufferGroups));
    for (RebufferGroupObserver observer : groupObservers) {
      observer.onRebufferGroupsChanged(groupSnapshot);
    }
  }

  public FPlayQoSMonitor() {}

  /**
   * Returns a {@link DataSource.Factory} that adds the internal QoS
   * {@link QoSTransferListener} to every {@link DataSource} created by {@code inner}.
   *
   * <p>Implementation-agnostic decorator — works for any {@code DataSource.Factory}
   * (Default/Cronet/OkHttp/Cache/Quanteec/custom). Uses
   * {@link DataSource#addTransferListener(androidx.media3.datasource.TransferListener)}
   * which is additive: the QoS listener coexists with any other listener already
   * wired to those data sources.
   *
   * <p>Call once per factory the player will use, BEFORE building the player.
   * Caveat: if the underlying {@code DataSource} implementation does not call the
   * {@code transferInitializing/Started/Ended} dispatch methods (custom impls that
   * bypass {@code BaseDataSource}), TTFB will not populate for those loads.
   */
  public DataSource.Factory wrapDataSourceFactory(DataSource.Factory inner) {
    return () -> {
      DataSource ds = inner.createDataSource();
      ds.addTransferListener(transferListener);
      return ds;
    };
  }

  /**
   * Attaches QoS analytics + state listeners to {@code player} and registers the
   * built-in debug observer. Call when the player is built, before {@code player.play()}
   * so the very first rebuffer is captured.
   *
   * <p>If a previous attach was not detached, this defensively detaches it first.
   */
  public void attach(ExoPlayer player, @Nullable BandwidthMeter bandwidthMeter) {
    if (activePlayer != null) detach();
    QoSAnalyticsHook analyticsHook =
        new QoSAnalyticsHook(player, bandwidthMeter, transferListener);
    QoSPlayerHook playerHook = new QoSPlayerHook();
    player.addAnalyticsListener(analyticsHook);
    player.addListener(playerHook);
    QoSMonitor.getInstance().addObserver(debugObserver);
    QoSMonitor.getInstance().addObserver(groupCaptureObserver);
    activePlayer = player;
    activeAnalyticsHook = analyticsHook;
    activePlayerHook = playerHook;
  }

  /**
   * Removes hooks from the active player, fully resets {@link QoSMonitor} singleton
   * state (entries, observers, pending bs flags), and clears pending TTFB in the
   * internal {@link QoSTransferListener}. Call BEFORE {@code player.release()}.
   * Idempotent — safe to call when not attached.
   */
  public void detach() {
    QoSMonitor.getInstance().reset();
    transferListener.clear();
    rebufferGroups.clear();
    groupObservers.clear();
    lastCaptureMs = 0L;
    nextGroupId = 0;
    ExoPlayer player = activePlayer;
    if (player == null) return;
    if (activeAnalyticsHook != null) player.removeAnalyticsListener(activeAnalyticsHook);
    if (activePlayerHook != null) player.removeListener(activePlayerHook);
    activePlayer = null;
    activeAnalyticsHook = null;
    activePlayerHook = null;
  }

  /**
   * Returns the {@value #CURRENT_WINDOW}-tail of the live entry buffer for the
   * "Current" HUD group. Live view — reflects the latest entries on every call.
   * Empty list when no entries recorded yet. The returned list is an unmodifiable
   * sub-list view of {@link QoSMonitor#getEntries()}; safe to iterate read-only.
   */
  public List<QoSInfo> getCurrentGroupEntries() {
    List<QoSInfo> all = QoSMonitor.getInstance().getEntries();
    int from = Math.max(0, all.size() - CURRENT_WINDOW);
    return all.subList(from, all.size());
  }

  /**
   * Returns all currently-retained {@link RebufferGroup}s, oldest first, newest last.
   * Up to {@value #MAX_REBUFFER_GROUPS}; older groups have been evicted FIFO.
   * Empty list before the first rebuffer is captured. Returned list is unmodifiable.
   */
  public List<RebufferGroup> getRebufferGroups() {
    return Collections.unmodifiableList(rebufferGroups);
  }

  /**
   * Registers an observer notified each time a new {@link RebufferGroup} is captured.
   * Callback runs on the player application thread — dispatch to UI thread if needed.
   * Idempotent on duplicate registration is not enforced; callers should pair every
   * add with a corresponding remove. All registered observers are dropped on
   * {@link #detach()} — re-register after a re-attach.
   */
  public void addRebufferGroupObserver(RebufferGroupObserver observer) {
    groupObservers.add(observer);
  }

  /** Removes a previously-registered {@link RebufferGroupObserver}. */
  public void removeRebufferGroupObserver(RebufferGroupObserver observer) {
    groupObservers.remove(observer);
  }
}