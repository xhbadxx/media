package com.fptplay.drm;

import android.media.MediaDrm;
import java.util.HashMap;

public class FPlayDrmPacker {
    static {
        System.loadLibrary("fplaydrm");
    }

    public static native RequestInfo requestInfo(byte[] challengeData);

    public static native MediaDrm.KeyRequest getKeyRequest(
        MediaDrm mediaDrm, byte[] scope, byte[] initData,
        String mimeType, int keyType, HashMap<String, String> optionalParams);

    public static native byte[] provideKeyResponse(
        MediaDrm mediaDrm, byte[] scope, byte[] response);

    /**
     * Decrypt SMWV/FPTWV response without calling MediaDrm.provideKeyResponse().
     * Used for verification: compare FPlay's decryption against Sigma's.
     * Returns decrypted Widevine license bytes, or null if decryption fails.
     */
    public static native byte[] decryptResponse(byte[] response);
}