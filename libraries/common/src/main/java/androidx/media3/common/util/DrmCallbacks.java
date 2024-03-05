package androidx.media3.common.util;

import androidx.annotation.Nullable;

public interface DrmCallbacks {
  void onKeyLoaded(@Nullable byte[] offlineLicenseKeySetId);
}
