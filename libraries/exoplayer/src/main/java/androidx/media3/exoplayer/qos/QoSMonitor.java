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

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qos.model.QoSInfo;
import androidx.media3.exoplayer.qos.observer.QoSObserver;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton collector. Thread-safe via {@link CopyOnWriteArrayList} for both the entry buffer
 * and the observer list — lock-free reads, atomic writes, snapshot-style iteration.
 *
 * <p>Entry buffer is capped at {@link #MAX_ENTRIES}; oldest entries are dropped (FIFO) when
 * the cap is exceeded. See sizing rationale in the companion vault note
 * {@code 1 🎯 Projects/FPlay/CMCD/Decision - QoE Ring Buffer Size.md}.
 */
@UnstableApi
public final class QoSMonitor {

  private static final int MAX_ENTRIES = 200;

  private static volatile QoSMonitor instance;

  public static QoSMonitor getInstance() {
    QoSMonitor r = instance;
    if (r == null) {
      synchronized (QoSMonitor.class) {
        r = instance;
        if (r == null) instance = r = new QoSMonitor();
      }
    }
    return r;
  }

  private final List<QoSInfo> entries = new CopyOnWriteArrayList<>();
  private final List<QoSObserver> observers = new CopyOnWriteArrayList<>();

  /**
   * Pending CMCD {@code bs} flags — separate per track type so that audio and video
   * each fire {@code bs=true} on their first load after a rebuffer event, regardless
   * of which load completes first. {@link #markPendingBufferStarvation()} sets both;
   * {@link #consumePendingBufferStarvation(int)} reads-and-clears the per-track flag
   * atomically so a single rebuffer event produces exactly one {@code bs=true} per track.
   */
  private final AtomicBoolean pendingBsAudio = new AtomicBoolean(false);

  private final AtomicBoolean pendingBsVideo = new AtomicBoolean(false);

  private QoSMonitor() {}

  public void recordInfo(QoSInfo info) {
    entries.add(info);
    while (entries.size() > MAX_ENTRIES) {
      entries.remove(0);
    }
    List<QoSInfo> snapshot = Collections.unmodifiableList(entries);
    for (QoSObserver o : observers) {
      o.onEntriesChanged(snapshot);
    }
  }

  /** Resets the entry buffer. Observers are notified with an empty snapshot. */
  public void clear() {
    entries.clear();
    List<QoSInfo> snapshot = Collections.unmodifiableList(entries);
    for (QoSObserver o : observers) {
      o.onEntriesChanged(snapshot);
    }
  }

  public List<QoSInfo> getEntries() {
    return Collections.unmodifiableList(entries);
  }

  public void addObserver(QoSObserver observer) {
    observers.add(observer);
  }

  public void removeObserver(QoSObserver observer) {
    observers.remove(observer);
  }

  /**
   * Marks that a buffer starvation just occurred. Sets the pending {@code bs} flag for
   * both audio and video so the first load completed on each track will carry
   * {@code bs=true}. Idempotent — repeated marks before the next consume are no-ops.
   */
  public void markPendingBufferStarvation() {
    pendingBsAudio.set(true);
    pendingBsVideo.set(true);
  }

  /**
   * Atomically reads and clears the pending {@code bs} flag for the given track type.
   * Returns {@code true} exactly once per track per {@link #markPendingBufferStarvation()}
   * call. Returns {@code false} for track types other than {@link C#TRACK_TYPE_AUDIO}
   * and {@link C#TRACK_TYPE_VIDEO}.
   */
  public boolean consumePendingBufferStarvation(int trackType) {
    if (trackType == C.TRACK_TYPE_AUDIO) return pendingBsAudio.getAndSet(false);
    if (trackType == C.TRACK_TYPE_VIDEO) return pendingBsVideo.getAndSet(false);
    return false;
  }

  /**
   * Full singleton wipe — clears entries, removes all observers, resets pending bs flags.
   */
  public void reset() {
    entries.clear();
    observers.clear();
    pendingBsAudio.set(false);
    pendingBsVideo.set(false);
  }
}