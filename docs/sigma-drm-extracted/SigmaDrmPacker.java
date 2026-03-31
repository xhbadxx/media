/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.media.MediaDrm
 *  android.media.MediaDrm$KeyRequest
 */
package com.sigma.packer;

import android.media.MediaDrm;
import com.sigma.packer.RequestInfo;
import java.util.HashMap;

public class SigmaDrmPacker {
    public static native RequestInfo requestInfo(byte[] var0);

    public static native MediaDrm.KeyRequest getKeyRequest(MediaDrm var0, byte[] var1, byte[] var2, String var3, int var4, HashMap<String, String> var5);

    public static native byte[] provideKeyResponse(MediaDrm var0, byte[] var1, byte[] var2);
}

