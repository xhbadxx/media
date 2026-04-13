/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.demo.main.lowlatency;

import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.util.Utils;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Helper for low-latency DASH playback testing (target live offset = 2s).
 *
 * <p>Currently exposes a single hardcoded {@link #createCustomData()} for SIGMA DRM so we can
 * quickly reproduce a low-latency session without wiring a real auth flow. More tuning knobs
 * (LiveConfiguration, playback speed bounds, LoadControl) will be added here as testing expands.
 */
public final class LowLatencyProxy {

  private static final String TAG = "LL";

  // Target live offset we are aiming to achieve during testing.
  public static final long TARGET_OFFSET_MS = 2000L;

  // Hardcoded SIGMA DRM values captured from a known-good session.
  private static final String USER_ID = "8041418";
  private static final String SESSION_ID =
      "ZG2n6d1z+1n7+3fCvZJ4Uip59a/HfusI9vkjxerGLwQrf6KoxnvgGuO9NIK81wJSPXXmos996Az+p2Pd+9EiW3o8sPuSOfsC/qh2xO+WcgUpefSvyGX7Ub+9e9PolHMYKXbqrc1n6An/vW3TqdckUHYjobjFa7xIo7IpkrSIewcyKbH2kxa4XK7vNZivwBQBLSz3qsh7vACrp3fF6sEpAyd+8vvHKrtb9/5xxOvAKBQzba3poD2rUa7zY8vpiWlGcy6qxYswqV3tpWPT9YciRUA5rerdc+lF";
  private static final String MERCHANT_ID = "fptplay";
  private static final String APP_ID = "sigma";

  private static final String SIGMA_LICENSE_URI =
      "https://license.sigmadrm.com/license/verify/widevine";

  private LowLatencyProxy() {}

  /**
   * Builds the SIGMA DRM {@code customData} JSON string using hardcoded credentials. Returns an
   * empty string if JSON construction fails.
   */
  public static String createCustomData() {
    try {
      JSONObject data = new JSONObject();
      data.put("userId", USER_ID);
      data.put("sessionId", SESSION_ID);
      data.put("merchantId", MERCHANT_ID);
      data.put("appId", APP_ID);
      data.put("extraData", "");
      Log.d(TAG, USER_ID + ", " + SESSION_ID + ", " + MERCHANT_ID + ", " + APP_ID);
      return data.toString();
    } catch (Exception e) {
      Log.e(TAG, "createCustomData failed", e);
      return "";
    }
  }

  /**
   * Builds the HTTP headers used for the SIGMA DRM license request. Matches the fplay app's
   * {@code createHeader()} behavior for the SIGMA branch.
   */
  public static Map<String, String> createHeader() {
    return ImmutableMap.of(
        "Content-Type", "application/octet-stream",
        "sigma-custom-data", createCustomData());
  }

  /**
   * Builds a {@link MediaItem.DrmConfiguration} for SIGMA Widevine DRM pointing at the production
   * SIGMA license server, with the hardcoded {@link #createHeader() headers} attached. Also flips
   * the global {@link Utils#IS_SIGMA_DRM} flag the fork relies on. Offline DRM is intentionally not
   * wired up here — this is the minimal online-license path for low-latency testing.
   */
  public static MediaItem.DrmConfiguration createDrmConfiguration() {
    Utils.IS_SIGMA_DRM = true;
    return new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
        .setLicenseUri(SIGMA_LICENSE_URI)
        .setMultiSession(true)
        .setIsSigmaDrm(true)
        .setLicenseRequestHeaders(createHeader())
        .build();
  }
}