package androidx.media3.exoplayer.util;

import androidx.annotation.Nullable;
import org.json.JSONObject;

public class Utils {
  public static boolean IS_SIGMA_DRM = false;
  public static boolean IS_LOW_LATENCY = false;

  /**
   * Latest license metadata from the most recent Sigma DRM key response.
   *
   * <p>Written by {@code HttpMediaDrmCallback} on the DRM request (background) thread; read by the
   * client on the main thread via {@link #getLicenseInfo()}. Declared {@code volatile} for
   * cross-thread visibility. The client calls {@link #clearLicenseInfo()} on stream switch to avoid
   * surfacing a previous stream's value.
   */
  @Nullable private static volatile LicenseInfo licenseInfo = null;

  /**
   * Publishes the granted license security info for the client to read via {@link
   * #getLicenseInfo()}.
   *
   * <p>Lenient by design: a missing {@code securityLevel}/{@code maxHDCPLevel} must not break
   * license acquisition, so {@link JSONObject#optString(String, String)} is used (never {@code
   * getString}). An explicit JSON {@code null} is normalized to a real {@code null} rather than the
   * literal string {@code "null"}. Runs on the DRM request (background) thread.
   */
  public static void publishLicenseInfo(JSONObject jsonObject) {
    String securityLevel =
        jsonObject.isNull("securityLevel") ? null : jsonObject.optString("securityLevel");
    String maxHdcpLevel =
        jsonObject.isNull("maxHDCPLevel") ? null : jsonObject.optString("maxHDCPLevel");
    licenseInfo = new LicenseInfo(securityLevel, maxHdcpLevel);
  }

  /** Returns the latest published license metadata, or {@code null} if none has been published or it was cleared. */
  @Nullable
  public static LicenseInfo getLicenseInfo() {
    return licenseInfo;
  }

  /** Clears the published license metadata. Call on stream switch before the next acquisition. */
  public static void clearLicenseInfo() {
    licenseInfo = null;
  }

  /** Immutable snapshot of the granted license security info. */
  public static final class LicenseInfo {
    @Nullable public final String securityLevel; // e.g. "L1"
    @Nullable public final String maxHdcpLevel; // e.g. "HDCP_V2_3"

    public LicenseInfo(@Nullable String securityLevel, @Nullable String maxHdcpLevel) {
      this.securityLevel = securityLevel;
      this.maxHdcpLevel = maxHdcpLevel;
    }
  }
}
