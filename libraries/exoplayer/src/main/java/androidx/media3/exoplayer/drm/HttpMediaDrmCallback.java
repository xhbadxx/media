/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.drm;


import static androidx.media3.exoplayer.drm.DrmUtil.executePost;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest;
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest;
import androidx.media3.exoplayer.util.Utils;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.primitives.Bytes;
import android.util.Log;
import com.fptplay.drm.FPlayDrmPacker;
import com.fptplay.drm.RequestInfo;
import com.sigma.packer.SigmaDrmPacker;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** A {@link MediaDrmCallback} that makes requests using {@link DataSource} instances. */
@UnstableApi
public final class HttpMediaDrmCallback implements MediaDrmCallback {
  private final DataSource.Factory dataSourceFactory;
  @Nullable private final String defaultLicenseUrl;
  private final boolean forceDefaultLicenseUrl;
  private final Map<String, String> keyRequestProperties;

  /**
   * Constructs an instance.
   *
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL. May be {@code null} if it's known that all key requests will specify
   *     their own URLs.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     usually be an HTTP-based {@link DataSource}.
   */
  public HttpMediaDrmCallback(
      @Nullable String defaultLicenseUrl, DataSource.Factory dataSourceFactory) {
    this(defaultLicenseUrl, /* forceDefaultLicenseUrl= */ false, dataSourceFactory);
  }

  /**
   * Constructs an instance.
   *
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL, or for all key requests if {@code forceDefaultLicenseUrl} is set to
   *     true. May be {@code null} if {@code forceDefaultLicenseUrl} is {@code false} and if it's
   *     known that all key requests will specify their own URLs.
   * @param forceDefaultLicenseUrl Whether to force use of {@code defaultLicenseUrl} for key
   *     requests that include their own license URL.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     * usually be an HTTP-based {@link DataSource}.
   */
  public HttpMediaDrmCallback(
      @Nullable String defaultLicenseUrl,
      boolean forceDefaultLicenseUrl,
      DataSource.Factory dataSourceFactory) {
    checkArgument(!(forceDefaultLicenseUrl && TextUtils.isEmpty(defaultLicenseUrl)));
    this.dataSourceFactory = dataSourceFactory;
    this.defaultLicenseUrl = defaultLicenseUrl;
    this.forceDefaultLicenseUrl = forceDefaultLicenseUrl;
    this.keyRequestProperties = new HashMap<>();
  }

  /**
   * Sets a header for key requests made by the callback.
   *
   * @param name The name of the header field.
   * @param value The value of the field.
   */
  public void setKeyRequestProperty(String name, String value) {
    checkNotNull(name);
    checkNotNull(value);
    synchronized (keyRequestProperties) {
      keyRequestProperties.put(name, value);
    }
  }

  /**
   * Clears a header for key requests made by the callback.
   *
   * @param name The name of the header field.
   */
  public void clearKeyRequestProperty(String name) {
    checkNotNull(name);
    synchronized (keyRequestProperties) {
      keyRequestProperties.remove(name);
    }
  }

  /** Clears all headers for key requests made by the callback. */
  public void clearAllKeyRequestProperties() {
    synchronized (keyRequestProperties) {
      keyRequestProperties.clear();
    }
  }

  // Wrapping into a RuntimeException is recommended by the JSONException docs:
  // https://developer.android.com/reference/org/json/JSONException
  @SuppressWarnings("ThrowSpecificExceptions")
  @Override
  public Response executeProvisionRequest(UUID uuid, ProvisionRequest request)
      throws MediaDrmCallbackException {
    byte[] httpBody =
        Bytes.concat(
            "{\"signedRequest\":\"".getBytes(UTF_8), request.getData(), "\"}".getBytes(UTF_8));
    return executePost(
        dataSourceFactory.createDataSource(),
        request.getDefaultUrl(),
        httpBody,
        ImmutableMap.of(
            HttpHeaders.CONTENT_TYPE,
            MediaType.JSON_UTF_8.toString(),
            HttpHeaders.CONTENT_LENGTH,
            String.valueOf(httpBody.length)));
  }

  @Override
  public Response executeKeyRequest(UUID uuid, KeyRequest request)
      throws MediaDrmCallbackException {
    String url = request.getLicenseServerUrl();
    if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
      url = defaultLicenseUrl;
    }
    if (TextUtils.isEmpty(url)) {
      throw new MediaDrmCallbackException(
          new DataSpec.Builder().setUri(Uri.EMPTY).build(),
          Uri.EMPTY,
          /* responseHeaders= */ ImmutableMap.of(),
          /* bytesLoaded= */ 0,
          /* cause= */ new IllegalStateException("No license URL"));
    }
    Map<String, String> requestProperties = new HashMap<>();
    // Add standard request properties for supported schemes.
    String contentType =
        C.PLAYREADY_UUID.equals(uuid)
            ? "text/xml"
            : (C.CLEARKEY_UUID.equals(uuid) ? "application/json" : "application/octet-stream");
    requestProperties.put("Content-Type", contentType);
    if (C.PLAYREADY_UUID.equals(uuid)) {
      requestProperties.put(
          "SOAPAction", "http://schemas.microsoft.com/DRM/2007/03/protocols/AcquireLicense");
    }
    // Add additional request properties.
    synchronized (keyRequestProperties) {
      // Checking and editing the KeyRequestProperties.
      if (keyRequestProperties.containsKey("sigma-custom-data")){
        String jsonObjectStr = keyRequestProperties.get("sigma-custom-data");
        if (jsonObjectStr != null) {
          JSONObject originalData;
          try {
            originalData = new JSONObject(jsonObjectStr);
          }catch (JSONException ex) {
            throw new RuntimeException("Error while creating object", ex);
          }
          try {
            // --- v15: COMPARE — Sigma gửi thật, FPlay log để tìm bug ---
            String useReqId;
            String useDeviceInfo;

            // Generate BOTH
            com.sigma.packer.RequestInfo sigmaInfo = SigmaDrmPacker.requestInfo(request.getData());
            RequestInfo fplayInfo = FPlayDrmPacker.requestInfo(request.getData());

            // Use FPlay for actual request
            useReqId = fplayInfo.requestId;
            useDeviceInfo = fplayInfo.deviceInfo;

            // === Compare requestId ===
            Log.d("DRM_COMPARE", "=== v16 COMPARE ===");
            Log.d("DRM_COMPARE", "Sigma reqId: " + sigmaInfo.requestId);
            Log.d("DRM_COMPARE", "FPlay reqId: " + fplayInfo.requestId);

            // === Compare deviceInfo ===
            boolean diMatch = sigmaInfo.deviceInfo.equals(fplayInfo.deviceInfo);
            Log.d("DRM_COMPARE", "deviceInfo match: " + diMatch);
            if (!diMatch) {
              // Find first diff position
              int diffPos = -1;
              int minLen = Math.min(sigmaInfo.deviceInfo.length(), fplayInfo.deviceInfo.length());
              for (int ci = 0; ci < minLen; ci++) {
                if (sigmaInfo.deviceInfo.charAt(ci) != fplayInfo.deviceInfo.charAt(ci)) {
                  diffPos = ci;
                  break;
                }
              }
              if (diffPos == -1 && sigmaInfo.deviceInfo.length() != fplayInfo.deviceInfo.length()) {
                diffPos = minLen;
              }
              Log.e("DRM_COMPARE", "deviceInfo DIFF at pos " + diffPos);
              int start = Math.max(0, diffPos - 20);
              int endS = Math.min(sigmaInfo.deviceInfo.length(), diffPos + 40);
              int endF = Math.min(fplayInfo.deviceInfo.length(), diffPos + 40);
              Log.e("DRM_COMPARE", "Sigma[" + start + ".." + endS + "]: " + sigmaInfo.deviceInfo.substring(start, endS));
              Log.e("DRM_COMPARE", "FPlay[" + start + ".." + endF + "]: " + fplayInfo.deviceInfo.substring(start, endF));
              Log.e("DRM_COMPARE", "Sigma len=" + sigmaInfo.deviceInfo.length() + " FPlay len=" + fplayInfo.deviceInfo.length());
            }
            Log.d("DRM_COMPARE", "USING: Sigma (safe)");

            // Verify BOTH requestIds against both reseed variants
            Log.d("DRM_COMPARE", "=== VERIFYING SIGMA's requestId ===");
            FPlayDrmPacker.verifyRequestId(
                sigmaInfo.requestId, request.getData(), sigmaInfo.deviceInfo);
            Log.d("DRM_COMPARE", "=== VERIFYING FPLAY's requestId ===");
            FPlayDrmPacker.verifyRequestId(
                fplayInfo.requestId, request.getData(), fplayInfo.deviceInfo);

            originalData.put("reqId", useReqId);
            originalData.put("deviceInfo", useDeviceInfo);

            String customDataJson = originalData.toString();
            Log.d("DRM_COMPARE", "v14 custom-data JSON: " + customDataJson);
            String customDataB64 = Base64.encodeToString(customDataJson.getBytes(), Base64.NO_WRAP);
            keyRequestProperties.put("custom-data", customDataB64);
          } catch (JSONException e) {
            throw new RuntimeException("Error while adding key properties", e);
          }
        }
      }
      // Adding the KeyRequestProperties to RequestProperties.
      requestProperties.putAll(keyRequestProperties);
      // v14: Remove internal keys that should NOT be sent as HTTP headers.
      // "sigma-custom-data" is an app-internal key used to pass original custom data.
      // When Sigma SDK is active, its interceptor would remove/transform this.
      // Without the interceptor, it gets sent as a raw HTTP header alongside "custom-data",
      // potentially confusing the server with conflicting/duplicate custom data.
      requestProperties.remove("sigma-custom-data");
    }

    // v14: Log all outgoing request headers for diagnosis
    Log.d("DRM_COMPARE", "v14 outgoing headers: " + requestProperties.keySet());
    for (Map.Entry<String, String> entry : requestProperties.entrySet()) {
      if (!"custom-data".equals(entry.getKey())) {  // custom-data already logged above
        Log.d("DRM_COMPARE", "  header: " + entry.getKey() + " = " + entry.getValue());
      }
    }
    Log.d("DRM_COMPARE", "v14 challenge size: " + request.getData().length);
    //
    Response response;
    try {
      response = executePost(dataSourceFactory.createDataSource(), url, request.getData(), requestProperties);
      Log.d("DRM_COMPARE", "Response data: " + new String(response.data, 0, Math.min(500, response.data.length)));
      // Log response headers for successful requests too (check Sigma-X-Clientinfo)
      if (response.loadEventInfo != null && response.loadEventInfo.responseHeaders != null) {
        Log.d("DRM_COMPARE", "Success headers: " + response.loadEventInfo.responseHeaders);
      }
    } catch (MediaDrmCallbackException e) {
      Log.e("DRM_COMPARE", "executePost FAILED: " + e.getMessage());
      // Extract 403 response body from the nested InvalidResponseCodeException
      Throwable cause = e.getCause();
      if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException httpEx =
            (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause;
        // Try to decompress gzip body
        String bodyStr;
        try {
          java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(httpEx.responseBody);
          java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bis);
          byte[] decompressed = com.google.common.io.ByteStreams.toByteArray(gis);
          bodyStr = new String(decompressed);
        } catch (Exception gzipEx) {
          bodyStr = new String(httpEx.responseBody, 0, Math.min(1000, httpEx.responseBody.length));
        }
        Log.e("DRM_COMPARE", "HTTP " + httpEx.responseCode + " body: " + bodyStr);
        Log.e("DRM_COMPARE", "HTTP " + httpEx.responseCode + " headers: " + httpEx.headerFields);
      }
      throw e;
    }
    if (Utils.IS_SIGMA_DRM) {
      try {
        JSONObject jsonObject = new JSONObject(new String(response.data));
        // If you don't use feature license encrypt, please comment 3 lines below
        String licenseEncrypted = jsonObject.getString("license");
        byte[] licenseBytes = Base64.decode(licenseEncrypted, Base64.DEFAULT);
        return withNewData(response, licenseBytes);
        // If you don't use feature license encrypt, please uncomment line below
        // return Base64.decode(jsonObject.getString("license"), Base64.DEFAULT);
      } catch (JSONException e) {
        throw new RuntimeException("Error while parsing response", e);
      }
    }else{
      return response;
    }
  }

  private Response withNewData(Response original, byte[] newData) {
    Response.Builder builder = new Response.Builder(newData);
    if (original.loadEventInfo != null) {
      builder.setLoadEventInfo(original.loadEventInfo);
    }
    return builder.build();
  }
}
