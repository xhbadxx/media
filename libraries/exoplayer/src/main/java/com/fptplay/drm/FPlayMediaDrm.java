/*
 * FPlayMediaDrm - ExoMediaDrm implementation that wraps android.media.MediaDrm
 * and routes key request/response through FPlayDrmPacker native methods.
 *
 * Based on SigmaMediaDrm (com.sigma.packer) with FPlay rebranding.
 */
package com.fptplay.drm;

import android.annotation.SuppressLint;
import android.media.MediaCrypto;
import android.media.MediaCryptoException;
import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;
import android.os.PersistableBundle;
import android.text.TextUtils;
import androidx.annotation.DoNotInline;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.drm.DummyExoMediaDrm;
import androidx.media3.exoplayer.drm.ExoMediaDrm;
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig;
import androidx.media3.exoplayer.drm.UnsupportedDrmException;
import androidx.media3.extractor.mp4.PsshAtomUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@UnstableApi
public class FPlayMediaDrm implements ExoMediaDrm {

  private static final String TAG = "FPlayMediaDrm";

  public static final ExoMediaDrm.Provider DEFAULT_PROVIDER;

  private static final String CENC_SCHEME_MIME_TYPE = "cenc";
  private static final String MOCK_LA_URL_VALUE = "https://x";
  private static final String MOCK_LA_URL = "<LA_URL>" + MOCK_LA_URL_VALUE + "</LA_URL>";
  private static final int UTF_16_BYTES_PER_CHARACTER = 2;

  private final UUID uuid;
  private final MediaDrm mediaDrm;
  private int referenceCount;

  static {
    System.loadLibrary("fplaydrm");
    DEFAULT_PROVIDER =
        uuid -> {
          try {
            return FPlayMediaDrm.newInstance(uuid);
          } catch (UnsupportedDrmException e) {
            Log.e(TAG, "Failed to instantiate a FPlayMediaDrm for uuid: " + uuid + ".");
            return new DummyExoMediaDrm();
          }
        };
  }

  public static boolean isCryptoSchemeSupported(UUID uuid) {
    return MediaDrm.isCryptoSchemeSupported(adjustUuid(uuid));
  }

  public static FPlayMediaDrm newInstance(UUID uuid) throws UnsupportedDrmException {
    try {
      return new FPlayMediaDrm(uuid);
    } catch (UnsupportedSchemeException e) {
      throw new UnsupportedDrmException(UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME, e);
    } catch (Exception e) {
      throw new UnsupportedDrmException(UnsupportedDrmException.REASON_INSTANTIATION_ERROR, e);
    }
  }

  private FPlayMediaDrm(UUID uuid) throws UnsupportedSchemeException {
    Assertions.checkNotNull(uuid);
    Assertions.checkArgument(
        !C.COMMON_PSSH_UUID.equals(uuid), "Use C.CLEARKEY_UUID instead");
    this.uuid = uuid;
    this.mediaDrm = new MediaDrm(adjustUuid(uuid));
    referenceCount = 1;
    if (C.WIDEVINE_UUID.equals(uuid) && needsForceWidevineL3Workaround()) {
      forceWidevineL3(mediaDrm);
    }
  }

  @Override
  public void setOnEventListener(@Nullable ExoMediaDrm.OnEventListener listener) {
    mediaDrm.setOnEventListener(
        listener == null
            ? null
            : (mediaDrm, sessionId, event, extra, data) ->
                listener.onEvent(FPlayMediaDrm.this, sessionId, event, extra, data));
  }

  @RequiresApi(23)
  @Override
  public void setOnKeyStatusChangeListener(
      @Nullable ExoMediaDrm.OnKeyStatusChangeListener listener) {
    if (Util.SDK_INT >= 23) {
      mediaDrm.setOnKeyStatusChangeListener(
          listener == null
              ? null
              : (mediaDrm, sessionId, keyInfo, hasNewUsableKey) -> {
                List<KeyStatus> exoKeyInfo = new ArrayList<>();
                for (MediaDrm.KeyStatus keyStatus : keyInfo) {
                  exoKeyInfo.add(
                      new KeyStatus(keyStatus.getStatusCode(), keyStatus.getKeyId()));
                }
                listener.onKeyStatusChange(
                    FPlayMediaDrm.this, sessionId, exoKeyInfo, hasNewUsableKey);
              },
          /* handler= */ null);
      return;
    }
    throw new UnsupportedOperationException();
  }

  @RequiresApi(23)
  @Override
  public void setOnExpirationUpdateListener(
      @Nullable ExoMediaDrm.OnExpirationUpdateListener listener) {
    if (Util.SDK_INT >= 23) {
      mediaDrm.setOnExpirationUpdateListener(
          listener == null
              ? null
              : (mediaDrm, sessionId, expirationTimeMs) ->
                  listener.onExpirationUpdate(
                      FPlayMediaDrm.this, sessionId, expirationTimeMs),
          /* handler= */ null);
      return;
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public byte[] openSession() throws android.media.MediaDrmException {
    return mediaDrm.openSession();
  }

  @Override
  public void closeSession(byte[] sessionId) {
    mediaDrm.closeSession(sessionId);
  }

  @SuppressLint("WrongConstant")
  @Override
  public ExoMediaDrm.KeyRequest getKeyRequest(
      byte[] scope,
      @Nullable List<DrmInitData.SchemeData> schemeDatas,
      int keyType,
      @Nullable HashMap<String, String> optionalParameters)
      throws android.media.NotProvisionedException {
    SchemeData schemeData = null;
    byte[] initData = null;
    String mimeType = null;
    if (schemeDatas != null) {
      schemeData = getSchemeData(uuid, schemeDatas);
      initData = adjustRequestInitData(uuid, Assertions.checkNotNull(schemeData.data));
      mimeType = adjustRequestMimeType(uuid, schemeData.mimeType);
    }
    MediaDrm.KeyRequest request =
        FPlayDrmPacker.getKeyRequest(mediaDrm, scope, initData, mimeType, keyType, optionalParameters);
    byte[] requestData = adjustRequestData(uuid, request.getData());
    String licenseServerUrl = request.getDefaultUrl();
    if (MOCK_LA_URL_VALUE.equals(licenseServerUrl)) {
      licenseServerUrl = "";
    }
    if (TextUtils.isEmpty(licenseServerUrl)
        && schemeData != null
        && !TextUtils.isEmpty(schemeData.licenseServerUrl)) {
      licenseServerUrl = schemeData.licenseServerUrl;
    }
    @KeyRequest.RequestType
    int requestType =
        Util.SDK_INT >= 23 ? request.getRequestType() : Integer.MIN_VALUE;
    return new KeyRequest(requestData, licenseServerUrl, requestType);
  }

  @Nullable
  @Override
  public byte[] provideKeyResponse(byte[] scope, byte[] response)
      throws android.media.NotProvisionedException, android.media.DeniedByServerException {
    // ClearKey response adjustment for API < 27
    if (C.CLEARKEY_UUID.equals(uuid) && Util.SDK_INT < 27) {
      try {
        JSONObject responseJson = new JSONObject(Util.fromUtf8Bytes(response));
        StringBuilder adjustedResponse = new StringBuilder("{\"keys\":[");
        JSONArray keys = responseJson.getJSONArray("keys");
        for (int i = 0; i < keys.length(); i++) {
          if (i != 0) {
            adjustedResponse.append(",");
          }
          JSONObject key = keys.getJSONObject(i);
          adjustedResponse.append("{\"k\":\"");
          adjustedResponse.append(key.getString("k").replace('-', '+').replace('_', '/'));
          adjustedResponse.append("\",\"kid\":\"");
          adjustedResponse.append(key.getString("kid").replace('-', '+').replace('_', '/'));
          adjustedResponse.append("\",\"kty\":\"");
          adjustedResponse.append(key.getString("kty"));
          adjustedResponse.append("\"}");
        }
        adjustedResponse.append("]}");
        response = Util.getUtf8Bytes(adjustedResponse.toString());
      } catch (JSONException e) {
        Log.e(
            "ClearKeyUtil",
            "Failed to adjust response data: " + Util.fromUtf8Bytes(response),
            e);
      }
    }

    return FPlayDrmPacker.provideKeyResponse(mediaDrm, scope, response);
  }

  @Override
  public ProvisionRequest getProvisionRequest() {
    MediaDrm.ProvisionRequest request = mediaDrm.getProvisionRequest();
    return new ProvisionRequest(request.getData(), request.getDefaultUrl());
  }

  @Override
  public void provideProvisionResponse(byte[] response)
      throws android.media.DeniedByServerException {
    mediaDrm.provideProvisionResponse(response);
  }

  @Override
  public Map<String, String> queryKeyStatus(byte[] sessionId) {
    return mediaDrm.queryKeyStatus(sessionId);
  }

  @Override
  public boolean requiresSecureDecoder(byte[] sessionId, String mimeType) {
    if (Util.SDK_INT >= 31) {
      return Api31.requiresSecureDecoder(mediaDrm, mimeType);
    }
    MediaCrypto mediaCrypto = null;
    try {
      mediaCrypto = new MediaCrypto(adjustUuid(uuid), sessionId);
      return mediaCrypto.requiresSecureDecoderComponent(mimeType);
    } catch (MediaCryptoException e) {
      return true;
    } finally {
      if (mediaCrypto != null) {
        mediaCrypto.release();
      }
    }
  }

  @Override
  public synchronized void acquire() {
    Assertions.checkState(referenceCount > 0);
    referenceCount++;
  }

  @Override
  public synchronized void release() {
    if (--referenceCount == 0) {
      mediaDrm.release();
    }
  }

  @Override
  public void restoreKeys(byte[] sessionId, byte[] keySetId) {
    mediaDrm.restoreKeys(sessionId, keySetId);
  }

  @Nullable
  @Override
  public PersistableBundle getMetrics() {
    if (Util.SDK_INT < 28) {
      return null;
    }
    return mediaDrm.getMetrics();
  }

  @Override
  public String getPropertyString(String propertyName) {
    return mediaDrm.getPropertyString(propertyName);
  }

  @Override
  public byte[] getPropertyByteArray(String propertyName) {
    return mediaDrm.getPropertyByteArray(propertyName);
  }

  @Override
  public void setPropertyString(String propertyName, String value) {
    mediaDrm.setPropertyString(propertyName, value);
  }

  @Override
  public void setPropertyByteArray(String propertyName, byte[] value) {
    mediaDrm.setPropertyByteArray(propertyName, value);
  }

  @Override
  public FrameworkCryptoConfig createCryptoConfig(byte[] sessionId)
      throws MediaCryptoException {
    boolean forceAllowInsecureDecoderComponents =
        Util.SDK_INT < 21
            && C.WIDEVINE_UUID.equals(uuid)
            && "L3".equals(getPropertyString("securityLevel"));
    return new FrameworkCryptoConfig(
        adjustUuid(uuid), sessionId, forceAllowInsecureDecoderComponents);
  }

  @Override
  public @C.CryptoType int getCryptoType() {
    return C.CRYPTO_TYPE_FRAMEWORK;
  }

  // --- Private helper methods ---

  private static SchemeData getSchemeData(UUID uuid, List<DrmInitData.SchemeData> schemeDatas) {
    if (!C.WIDEVINE_UUID.equals(uuid)) {
      return schemeDatas.get(0);
    }
    if (Util.SDK_INT >= 28 && schemeDatas.size() > 1) {
      SchemeData firstSchemeData = schemeDatas.get(0);
      int concatenatedDataLength = 0;
      boolean canConcatenateData = true;
      for (int i = 0; i < schemeDatas.size(); i++) {
        SchemeData schemeData = schemeDatas.get(i);
        byte[] data = Assertions.checkNotNull(schemeData.data);
        if (Util.areEqual(schemeData.mimeType, firstSchemeData.mimeType)
            && Util.areEqual(schemeData.licenseServerUrl, firstSchemeData.licenseServerUrl)
            && PsshAtomUtil.isPsshAtom(data)) {
          concatenatedDataLength += data.length;
        } else {
          canConcatenateData = false;
          break;
        }
      }
      if (canConcatenateData) {
        byte[] concatenatedData = new byte[concatenatedDataLength];
        int position = 0;
        for (int i = 0; i < schemeDatas.size(); i++) {
          byte[] data = Assertions.checkNotNull(schemeDatas.get(i).data);
          System.arraycopy(data, 0, concatenatedData, position, data.length);
          position += data.length;
        }
        return firstSchemeData.copyWithData(concatenatedData);
      }
    }
    // Prefer V1 PSSH for API 23+, V0 for below.
    for (int i = 0; i < schemeDatas.size(); i++) {
      SchemeData schemeData = schemeDatas.get(i);
      int version = PsshAtomUtil.parseVersion(Assertions.checkNotNull(schemeData.data));
      if (Util.SDK_INT < 23 && version == 0) {
        return schemeData;
      }
      if (Util.SDK_INT >= 23 && version == 1) {
        return schemeData;
      }
    }
    return schemeDatas.get(0);
  }

  private static UUID adjustUuid(UUID uuid) {
    if (Util.SDK_INT < 27 && C.CLEARKEY_UUID.equals(uuid)) {
      return C.COMMON_PSSH_UUID;
    }
    return uuid;
  }

  private static byte[] adjustRequestInitData(UUID uuid, byte[] initData) {
    if (C.PLAYREADY_UUID.equals(uuid)) {
      byte[] schemeSpecificData = PsshAtomUtil.parseSchemeSpecificData(initData, uuid);
      if (schemeSpecificData != null) {
        initData = schemeSpecificData;
      }
      initData =
          PsshAtomUtil.buildPsshAtom(C.PLAYREADY_UUID, addLaUrlAttributeIfMissing(initData));
    }
    // Strip PSSH wrapper for Widevine on API < 23, and for PlayReady on certain Amazon devices.
    if ((Util.SDK_INT < 23 && C.WIDEVINE_UUID.equals(uuid))
        || (C.PLAYREADY_UUID.equals(uuid)
            && "Amazon".equals(Util.MANUFACTURER)
            && ("AFTB".equals(Util.MODEL)
                || "AFTS".equals(Util.MODEL)
                || "AFTM".equals(Util.MODEL)
                || "AFTT".equals(Util.MODEL)))) {
      byte[] schemeSpecificData = PsshAtomUtil.parseSchemeSpecificData(initData, uuid);
      if (schemeSpecificData != null) {
        return schemeSpecificData;
      }
    }
    return initData;
  }

  private static String adjustRequestMimeType(UUID uuid, String mimeType) {
    if (Util.SDK_INT < 26
        && C.CLEARKEY_UUID.equals(uuid)
        && ("video/mp4".equals(mimeType) || "audio/mp4".equals(mimeType))) {
      return CENC_SCHEME_MIME_TYPE;
    }
    return mimeType;
  }

  private static byte[] adjustRequestData(UUID uuid, byte[] requestData) {
    if (C.CLEARKEY_UUID.equals(uuid)) {
      if (Util.SDK_INT < 27) {
        return Util.getUtf8Bytes(
            Util.fromUtf8Bytes(requestData).replace('+', '-').replace('/', '_'));
      }
    }
    return requestData;
  }

  @SuppressLint("WrongConstant")
  private static void forceWidevineL3(MediaDrm mediaDrm) {
    mediaDrm.setPropertyString("securityLevel", "L3");
  }

  private static boolean needsForceWidevineL3Workaround() {
    return "ASUS_Z00AD".equals(Util.MODEL);
  }

  private static byte[] addLaUrlAttributeIfMissing(byte[] data) {
    ParsableByteArray byteArray = new ParsableByteArray(data);
    int length = byteArray.readLittleEndianInt();
    int objectRecordCount = byteArray.readLittleEndianShort();
    int recordType = byteArray.readLittleEndianShort();
    if (objectRecordCount != 1 || recordType != 1) {
      Log.i(TAG, "Unexpected record count or type. Skipping LA_URL workaround.");
      return data;
    }
    int recordLength = byteArray.readLittleEndianShort();
    String xml = byteArray.readString(recordLength, StandardCharsets.UTF_16LE);
    if (xml.contains("<LA_URL>")) {
      return data;
    }
    int endOfDataTagIndex = xml.indexOf("</DATA>");
    if (endOfDataTagIndex == -1) {
      Log.w(TAG, "Could not find the </DATA> tag. Skipping LA_URL workaround.");
    }
    String xmlWithMockLaUrl =
        xml.substring(0, endOfDataTagIndex) + MOCK_LA_URL + xml.substring(endOfDataTagIndex);
    int extraBytes = MOCK_LA_URL.length() * UTF_16_BYTES_PER_CHARACTER;
    ByteBuffer newData = ByteBuffer.allocate(length + extraBytes);
    newData.order(ByteOrder.LITTLE_ENDIAN);
    newData.putInt(length + extraBytes);
    newData.putShort((short) objectRecordCount);
    newData.putShort((short) recordType);
    newData.putShort((short) (xmlWithMockLaUrl.length() * UTF_16_BYTES_PER_CHARACTER));
    newData.put(xmlWithMockLaUrl.getBytes(StandardCharsets.UTF_16LE));
    return newData.array();
  }

  // --- Inner class ---

  @RequiresApi(31)
  private static class Api31 {
    private Api31() {}

    @DoNotInline
    public static boolean requiresSecureDecoder(MediaDrm mediaDrm, String mimeType) {
      return mediaDrm.requiresSecureDecoder(mimeType);
    }
  }
}
