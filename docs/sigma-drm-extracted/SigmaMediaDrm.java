/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.annotation.SuppressLint
 *  android.media.MediaCrypto
 *  android.media.MediaCryptoException
 *  android.media.MediaDrm
 *  android.media.MediaDrm$KeyRequest
 *  android.media.MediaDrm$KeyStatus
 *  android.media.MediaDrm$OnEventListener
 *  android.media.MediaDrm$OnExpirationUpdateListener
 *  android.media.MediaDrm$OnKeyStatusChangeListener
 *  android.media.UnsupportedSchemeException
 *  android.os.PersistableBundle
 *  android.text.TextUtils
 *  androidx.annotation.DoNotInline
 *  androidx.annotation.Nullable
 *  androidx.annotation.RequiresApi
 *  androidx.media3.common.C
 *  androidx.media3.common.DrmInitData$SchemeData
 *  androidx.media3.common.util.Assertions
 *  androidx.media3.common.util.Log
 *  androidx.media3.common.util.ParsableByteArray
 *  androidx.media3.common.util.UnstableApi
 *  androidx.media3.common.util.Util
 *  androidx.media3.exoplayer.drm.DummyExoMediaDrm
 *  androidx.media3.exoplayer.drm.ExoMediaDrm
 *  androidx.media3.exoplayer.drm.ExoMediaDrm$KeyRequest
 *  androidx.media3.exoplayer.drm.ExoMediaDrm$KeyStatus
 *  androidx.media3.exoplayer.drm.ExoMediaDrm$OnEventListener
 *  androidx.media3.exoplayer.drm.ExoMediaDrm$OnExpirationUpdateListener
 *  androidx.media3.exoplayer.drm.ExoMediaDrm$OnKeyStatusChangeListener
 *  androidx.media3.exoplayer.drm.ExoMediaDrm$Provider
 *  androidx.media3.exoplayer.drm.ExoMediaDrm$ProvisionRequest
 *  androidx.media3.exoplayer.drm.FrameworkCryptoConfig
 *  androidx.media3.exoplayer.drm.UnsupportedDrmException
 *  androidx.media3.extractor.mp4.PsshAtomUtil
 *  kotlin.text.Charsets
 *  org.json.JSONArray
 *  org.json.JSONException
 *  org.json.JSONObject
 */
package com.sigma.packer;

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
import com.sigma.packer.SigmaDrmPacker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kotlin.text.Charsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@UnstableApi
@RequiresApi(value=18)
public class SigmaMediaDrm
implements ExoMediaDrm {
    private static final String TAG = "SigmaMediaDrm";
    public static final ExoMediaDrm.Provider DEFAULT_PROVIDER;
    private static final String CENC_SCHEME_MIME_TYPE = "cenc";
    private static final String MOCK_LA_URL_VALUE = "https://x";
    private static final String MOCK_LA_URL = "<LA_URL>https://x</LA_URL>";
    private static final int UTF_16_BYTES_PER_CHARACTER = 2;
    private final UUID uuid;
    private final MediaDrm mediaDrm;
    private int referenceCount;

    public static boolean isCryptoSchemeSupported(UUID uUID) {
        return MediaDrm.isCryptoSchemeSupported((UUID)SigmaMediaDrm.adjustUuid(uUID));
    }

    public static SigmaMediaDrm newInstance(UUID uUID) {
        UnsupportedSchemeException unsupportedSchemeException2;
        block3: {
            try {
                return new SigmaMediaDrm(uUID);
            }
            catch (Exception exception) {
            }
            catch (UnsupportedSchemeException unsupportedSchemeException2) {
                break block3;
            }
            throw new UnsupportedDrmException(2, exception);
        }
        throw new UnsupportedDrmException(1, (Exception)((Object)unsupportedSchemeException2));
    }

    private SigmaMediaDrm(UUID uUID) {
        MediaDrm mediaDrm;
        SigmaMediaDrm sigmaMediaDrm = sigmaMediaDrm2;
        UUID uUID2 = uUID;
        Assertions.checkNotNull((Object)uUID2);
        Assertions.checkArgument((boolean)(C.COMMON_PSSH_UUID.equals(uUID) ^ true), (Object)"Use C.CLEARKEY_UUID instead");
        sigmaMediaDrm.uuid = uUID2;
        SigmaMediaDrm sigmaMediaDrm2 = mediaDrm;
        sigmaMediaDrm.mediaDrm = new MediaDrm(SigmaMediaDrm.adjustUuid(uUID));
        sigmaMediaDrm.referenceCount = 1;
        if (C.WIDEVINE_UUID.equals(uUID) && SigmaMediaDrm.needsForceWidevineL3Workaround()) {
            SigmaMediaDrm.forceWidevineL3((MediaDrm)sigmaMediaDrm2);
        }
    }

    private static DrmInitData.SchemeData getSchemeData(UUID uUID, List<DrmInitData.SchemeData> list) {
        int n;
        block7: {
            if (!C.WIDEVINE_UUID.equals(uUID)) {
                return list.get(0);
            }
            if (Util.SDK_INT >= 28 && list.size() > 1) {
                uUID = list.get(0);
                int n2 = 0;
                for (n = 0; n < list.size(); ++n) {
                    DrmInitData.SchemeData schemeData = list.get(n);
                    byte[] byArray = (byte[])Assertions.checkNotNull((Object)schemeData.data);
                    if (Util.areEqual((Object)schemeData.mimeType, (Object)((DrmInitData.SchemeData)uUID).mimeType) && Util.areEqual((Object)schemeData.licenseServerUrl, (Object)((DrmInitData.SchemeData)uUID).licenseServerUrl) && PsshAtomUtil.isPsshAtom((byte[])byArray)) {
                        n2 += byArray.length;
                        continue;
                    }
                    break block7;
                }
                byte[] byArray = new byte[n2];
                n = 0;
                for (int i = 0; i < list.size(); ++i) {
                    byte[] byArray2 = (byte[])Assertions.checkNotNull((Object)list.get((int)i).data);
                    int n3 = byArray2.length;
                    System.arraycopy(byArray2, 0, byArray, n, n3);
                    n += n3;
                }
                return uUID.copyWithData(byArray);
            }
        }
        for (int i = 0; i < list.size(); ++i) {
            DrmInitData.SchemeData schemeData = list.get(i);
            n = PsshAtomUtil.parseVersion((byte[])((byte[])Assertions.checkNotNull((Object)schemeData.data)));
            int n4 = Util.SDK_INT;
            if (n4 < 23 && n == 0) {
                return schemeData;
            }
            if (n4 < 23 || n != 1) continue;
            return schemeData;
        }
        return list.get(0);
    }

    private static UUID adjustUuid(UUID uUID) {
        if (Util.SDK_INT < 27 && C.CLEARKEY_UUID.equals(uUID)) {
            uUID = C.COMMON_PSSH_UUID;
        }
        return uUID;
    }

    private static byte[] adjustRequestInitData(UUID object, byte[] byArray) {
        Object object2 = C.PLAYREADY_UUID;
        if (((UUID)object2).equals(object)) {
            byte[] byArray2 = PsshAtomUtil.parseSchemeSpecificData((byte[])byArray, (UUID)object);
            if (byArray2 != null) {
                byArray = byArray2;
            }
            byArray = PsshAtomUtil.buildPsshAtom((UUID)object2, (byte[])SigmaMediaDrm.addLaUrlAttributeIfMissing(byArray));
        }
        if (Util.SDK_INT < 23 && C.WIDEVINE_UUID.equals(object) || ((UUID)object2).equals(object) && "Amazon".equals(Util.MANUFACTURER) && ("AFTB".equals(object2 = Util.MODEL) || "AFTS".equals(object2) || "AFTM".equals(object2) || "AFTT".equals(object2))) {
            byte[] byArray3 = PsshAtomUtil.parseSchemeSpecificData((byte[])byArray, (UUID)object);
            object = byArray3;
            if (byArray3 != null) {
                return object;
            }
        }
        return byArray;
    }

    private static String adjustRequestMimeType(UUID uUID, String string) {
        if (Util.SDK_INT < 26 && C.CLEARKEY_UUID.equals(uUID) && ("video/mp4".equals(string) || "audio/mp4".equals(string))) {
            return CENC_SCHEME_MIME_TYPE;
        }
        return string;
    }

    private static byte[] adjustRequestData(UUID uUID, byte[] byArray) {
        if (C.CLEARKEY_UUID.equals(uUID)) {
            if (Util.SDK_INT < 27) {
                byArray = Util.getUtf8Bytes((String)Util.fromUtf8Bytes((byte[])byArray).replace('+', '-').replace('/', '_'));
            }
            return byArray;
        }
        return byArray;
    }

    @SuppressLint(value={"WrongConstant"})
    private static void forceWidevineL3(MediaDrm mediaDrm) {
        mediaDrm.setPropertyString("securityLevel", "L3");
    }

    private static boolean needsForceWidevineL3Workaround() {
        return "ASUS_Z00AD".equals(Util.MODEL);
    }

    private static byte[] addLaUrlAttributeIfMissing(byte[] byArray) {
        ParsableByteArray parsableByteArray;
        Object object = parsableByteArray;
        int n = new ParsableByteArray(byArray).readLittleEndianInt();
        short s = object.readLittleEndianShort();
        short s2 = object.readLittleEndianShort();
        if (s == 1 && s2 == 1) {
            ParsableByteArray parsableByteArray2 = object;
            object = Charsets.UTF_16LE;
            Object object2 = parsableByteArray2.readString((int)parsableByteArray2.readLittleEndianShort(), (Charset)object);
            if (((String)object2).contains("<LA_URL>")) {
                return byArray;
            }
            int n2 = ((String)object2).indexOf("</DATA>");
            if (n2 == -1) {
                Log.w((String)TAG, (String)"Could not find the </DATA> tag. Skipping LA_URL workaround.");
            }
            String string = ((String)object2).substring(0, n2) + MOCK_LA_URL + ((String)object2).substring(n2);
            object2 = ByteBuffer.allocate(n += 52);
            ((ByteBuffer)object2).order(ByteOrder.LITTLE_ENDIAN);
            ((ByteBuffer)object2).putInt(n);
            ((ByteBuffer)object2).putShort(s);
            ((ByteBuffer)object2).putShort(s2);
            ((ByteBuffer)object2).putShort((short)(string.length() * 2));
            ((ByteBuffer)object2).put(string.getBytes((Charset)object));
            return ((ByteBuffer)object2).array();
        }
        Log.i((String)TAG, (String)"Unexpected record count or type. Skipping LA_URL workaround.");
        return byArray;
    }

    static {
        System.loadLibrary("drmpacker");
        DEFAULT_PROVIDER = uUID -> {
            try {
                return SigmaMediaDrm.newInstance(uUID);
            }
            catch (UnsupportedDrmException unsupportedDrmException) {
                Log.e((String)TAG, (String)("Failed to instantiate a SigmaMediaDrm for uuid: " + uUID + "."));
                return new DummyExoMediaDrm();
            }
        };
    }

    public void setOnEventListener(@Nullable ExoMediaDrm.OnEventListener onEventListener) {
        MediaDrm mediaDrm2 = sigmaMediaDrm.mediaDrm;
        SigmaMediaDrm sigmaMediaDrm = onEventListener == null ? null : (mediaDrm, byArray, n, n2, byArray2) -> onEventListener.onEvent((ExoMediaDrm)this, byArray, n, n2, byArray2);
        mediaDrm2.setOnEventListener((MediaDrm.OnEventListener)sigmaMediaDrm);
    }

    @RequiresApi(value=23)
    public void setOnKeyStatusChangeListener(@Nullable ExoMediaDrm.OnKeyStatusChangeListener onKeyStatusChangeListener) {
        if (Util.SDK_INT >= 23) {
            MediaDrm mediaDrm = sigmaMediaDrm.mediaDrm;
            SigmaMediaDrm sigmaMediaDrm = onKeyStatusChangeListener == null ? null : (object, byArray, object2, bl) -> {
                ArrayList arrayList;
                object = arrayList;
                arrayList = new ArrayList();
                object2 = object2.iterator();
                while (object2.hasNext()) {
                    MediaDrm.KeyStatus keyStatus;
                    MediaDrm.KeyStatus keyStatus2 = keyStatus = (MediaDrm.KeyStatus)object2.next();
                    int n = keyStatus2.getStatusCode();
                    ((ArrayList)object).add(new ExoMediaDrm.KeyStatus(n, keyStatus2.getKeyId()));
                }
                onKeyStatusChangeListener.onKeyStatusChange((ExoMediaDrm)this, byArray, (List)object, bl);
            };
            mediaDrm.setOnKeyStatusChangeListener((MediaDrm.OnKeyStatusChangeListener)sigmaMediaDrm, null);
            return;
        }
        throw new UnsupportedOperationException();
    }

    @RequiresApi(value=23)
    public void setOnExpirationUpdateListener(@Nullable ExoMediaDrm.OnExpirationUpdateListener onExpirationUpdateListener) {
        if (Util.SDK_INT >= 23) {
            MediaDrm mediaDrm2 = sigmaMediaDrm.mediaDrm;
            SigmaMediaDrm sigmaMediaDrm = onExpirationUpdateListener == null ? null : (mediaDrm, byArray, l) -> onExpirationUpdateListener.onExpirationUpdate((ExoMediaDrm)this, byArray, l);
            mediaDrm2.setOnExpirationUpdateListener((MediaDrm.OnExpirationUpdateListener)sigmaMediaDrm, null);
            return;
        }
        throw new UnsupportedOperationException();
    }

    public byte[] openSession() {
        return this.mediaDrm.openSession();
    }

    public void closeSession(byte[] byArray) {
        this.mediaDrm.closeSession(byArray);
    }

    @SuppressLint(value={"WrongConstant"})
    public ExoMediaDrm.KeyRequest getKeyRequest(byte[] object, @Nullable List<DrmInitData.SchemeData> object2, int n, @Nullable HashMap<String, String> hashMap) {
        DrmInitData.SchemeData schemeData = null;
        byte[] byArray = null;
        String string = null;
        if (object2 != null) {
            SigmaMediaDrm sigmaMediaDrm = object3;
            schemeData = SigmaMediaDrm.getSchemeData(sigmaMediaDrm.uuid, object2);
            byArray = SigmaMediaDrm.adjustRequestInitData(sigmaMediaDrm.uuid, (byte[])Assertions.checkNotNull((Object)schemeData.data));
            string = SigmaMediaDrm.adjustRequestMimeType(sigmaMediaDrm.uuid, schemeData.mimeType);
        }
        MediaDrm.KeyRequest keyRequest = SigmaDrmPacker.getKeyRequest(((SigmaMediaDrm)object3).mediaDrm, object, byArray, string, n, hashMap);
        object = keyRequest;
        Object object3 = SigmaMediaDrm.adjustRequestData(((SigmaMediaDrm)object3).uuid, object.getData());
        object2 = keyRequest.getDefaultUrl();
        if (MOCK_LA_URL_VALUE.equals(object2)) {
            object2 = "";
        }
        if (TextUtils.isEmpty((CharSequence)object2) && schemeData != null && !TextUtils.isEmpty((CharSequence)schemeData.licenseServerUrl)) {
            object2 = schemeData.licenseServerUrl;
        }
        int n2 = Util.SDK_INT >= 23 ? object.getRequestType() : Integer.MIN_VALUE;
        return new ExoMediaDrm.KeyRequest((byte[])object3, (String)object2, n2);
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    @Nullable
    public byte[] provideKeyResponse(byte[] byArray, byte[] byArray2) {
        int n;
        JSONArray jSONArray;
        StringBuilder stringBuilder;
        if (!C.CLEARKEY_UUID.equals(this.uuid)) return SigmaDrmPacker.provideKeyResponse(this.mediaDrm, byArray, byArray2);
        if (Util.SDK_INT >= 27) {
            return SigmaDrmPacker.provideKeyResponse(this.mediaDrm, byArray, byArray2);
        }
        JSONObject jSONObject = new JSONObject(Util.fromUtf8Bytes((byte[])byArray2));
        StringBuilder stringBuilder2 = stringBuilder;
        try {
            stringBuilder = new StringBuilder("{\"keys\":[");
            jSONArray = jSONObject.getJSONArray("keys");
            for (n = 0; n < jSONArray.length(); ++n) {
                if (n == 0) break block15;
                stringBuilder2.append(",");
            }
        }
        catch (JSONException jSONException) {}
        {
            block15: {
            }
            StringBuilder stringBuilder3 = stringBuilder2;
            StringBuilder stringBuilder4 = stringBuilder3;
            StringBuilder stringBuilder5 = stringBuilder3;
            JSONObject jSONObject2 = jSONArray.getJSONObject(n);
            StringBuilder stringBuilder6 = stringBuilder2;
            StringBuilder stringBuilder7 = stringBuilder6;
            StringBuilder stringBuilder8 = stringBuilder6;
            JSONObject jSONObject3 = jSONObject2;
            StringBuilder stringBuilder9 = stringBuilder2;
            StringBuilder stringBuilder10 = stringBuilder9;
            StringBuilder stringBuilder11 = stringBuilder9;
            JSONObject jSONObject4 = jSONObject2;
            stringBuilder2.append("{\"k\":\"");
            stringBuilder10.append(jSONObject4.getString("k").replace('-', '+').replace('_', '/'));
            stringBuilder11.append("\",\"kid\":\"");
            stringBuilder7.append(jSONObject3.getString("kid").replace('-', '+').replace('_', '/'));
            stringBuilder8.append("\",\"kty\":\"");
            stringBuilder4.append(jSONObject2.getString("kty"));
            stringBuilder5.append("\"}");
            continue;
        }
        StringBuilder stringBuilder12 = stringBuilder2;
        StringBuilder stringBuilder13 = stringBuilder12;
        stringBuilder12.append("]}");
        byArray2 = Util.getUtf8Bytes((String)stringBuilder13.toString());
        return SigmaDrmPacker.provideKeyResponse(this.mediaDrm, byArray, byArray2);
        Log.e((String)"ClearKeyUtil", (String)("Failed to adjust response data: " + Util.fromUtf8Bytes((byte[])byArray2)), (Throwable)jSONException);
        return SigmaDrmPacker.provideKeyResponse(this.mediaDrm, byArray, byArray2);
    }

    public ExoMediaDrm.ProvisionRequest getProvisionRequest() {
        Object object = ((SigmaMediaDrm)object).mediaDrm.getProvisionRequest();
        SigmaMediaDrm sigmaMediaDrm = object;
        object = sigmaMediaDrm.getData();
        return new ExoMediaDrm.ProvisionRequest((byte[])object, sigmaMediaDrm.getDefaultUrl());
    }

    public void provideProvisionResponse(byte[] byArray) {
        this.mediaDrm.provideProvisionResponse(byArray);
    }

    public Map<String, String> queryKeyStatus(byte[] byArray) {
        return this.mediaDrm.queryKeyStatus(byArray);
    }

    public boolean requiresSecureDecoder(byte[] byArray, String string) {
        boolean bl;
        String string2;
        MediaCrypto mediaCrypto;
        if (Util.SDK_INT >= 31) {
            return Api31.requiresSecureDecoder(this.mediaDrm, string);
        }
        MediaCrypto mediaCrypto2 = mediaCrypto;
        try {
            string2 = string;
            mediaCrypto2(this.uuid, byArray);
        }
        catch (MediaCryptoException mediaCryptoException) {
            return true;
        }
        try {
            bl = mediaCrypto.requiresSecureDecoderComponent(string2);
        }
        catch (Throwable throwable) {
            mediaCrypto2.release();
            throw throwable;
        }
        mediaCrypto2.release();
        return bl;
    }

    public synchronized void acquire() {
        Assertions.checkState((this.referenceCount > 0 ? 1 : 0) != 0);
        ++this.referenceCount;
    }

    public synchronized void release() {
        if (--this.referenceCount == 0) {
            this.mediaDrm.release();
        }
    }

    public void restoreKeys(byte[] byArray, byte[] byArray2) {
        this.mediaDrm.restoreKeys(byArray, byArray2);
    }

    @Nullable
    public PersistableBundle getMetrics() {
        if (Util.SDK_INT < 28) {
            return null;
        }
        return this.mediaDrm.getMetrics();
    }

    public String getPropertyString(String string) {
        return this.mediaDrm.getPropertyString(string);
    }

    public byte[] getPropertyByteArray(String string) {
        return this.mediaDrm.getPropertyByteArray(string);
    }

    public void setPropertyString(String string, String string2) {
        this.mediaDrm.setPropertyString(string, string2);
    }

    public void setPropertyByteArray(String string, byte[] byArray) {
        this.mediaDrm.setPropertyByteArray(string, byArray);
    }

    public FrameworkCryptoConfig createCryptoConfig(byte[] byArray) {
        boolean bl = Util.SDK_INT < 21 && C.WIDEVINE_UUID.equals(this.uuid) && "L3".equals(this.getPropertyString("securityLevel"));
        return new FrameworkCryptoConfig(SigmaMediaDrm.adjustUuid(this.uuid), byArray, bl);
    }

    public int getCryptoType() {
        return 2;
    }

    @RequiresApi(value=31)
    public static class Api31 {
        private Api31() {
        }

        @DoNotInline
        public static boolean requiresSecureDecoder(MediaDrm mediaDrm, String string) {
            return mediaDrm.requiresSecureDecoder(string);
        }
    }
}

