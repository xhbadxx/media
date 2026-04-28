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
package androidx.media3.exoplayer.qos.observer;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qos.model.RebufferGroup;
import java.util.List;

/**
 * Observer for {@link RebufferGroup} retention changes in
 * {@link androidx.media3.exoplayer.qos.FPlayQoSMonitor}. Called once per debounced
 * rebuffer event, immediately after the group list is mutated (new group added,
 * oldest possibly evicted by FIFO cap).
 *
 * <p>Snapshot contains all currently-retained groups, oldest first, newest last —
 * suitable for {@code adapter.submitList(groups)} with DiffUtil. Runs on the thread
 * that invoked {@code recordInfo} (player application thread) — dispatch to UI thread
 * if needed.
 */
@UnstableApi
public interface RebufferGroupObserver {
  /** Called after the retained {@link RebufferGroup} list changes. */
  void onRebufferGroupsChanged(List<RebufferGroup> groups);
}