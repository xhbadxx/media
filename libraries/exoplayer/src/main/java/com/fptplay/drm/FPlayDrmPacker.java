package com.fptplay.drm;

import android.media.MediaDrm;
import java.util.HashMap;

public class FPlayDrmPacker {
    public static native RequestInfo requestInfo(byte[] challengeData);

    public static native MediaDrm.KeyRequest getKeyRequest(
        MediaDrm mediaDrm, byte[] scope, byte[] initData,
        String mimeType, int keyType, HashMap<String, String> optionalParams);

    public static native byte[] provideKeyResponse(
        MediaDrm mediaDrm, byte[] scope, byte[] response);
}