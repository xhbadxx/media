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
package androidx.media3.exoplayer.qoe.observer;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.qoe.model.QoeInfo;
import java.util.List;

/**
 * Observer for {@link androidx.media3.exoplayer.qoe.QoeMonitor} state changes.
 * Called after each new {@link QoeInfo} is recorded. Runs on the thread that
 * invoked {@code recordInfo} — dispatch to UI thread if needed.
 */
@UnstableApi
public interface QoeObserver {
  /** Called after a new entry is recorded. Snapshot contains all current entries. */
  void onEntriesChanged(List<QoeInfo> entries);
}