# FPlay DRM Packer — Technical Documentation

## Overview

FPlay DRM Packer (`libfplaydrm.so`) is a C++/JNI native library that replaces the proprietary Sigma DRM packer AAR (`com.sigma.packer:media3-1.6.1:1.0.3`). It intercepts the Widevine DRM license exchange to:

1. Generate a **requestId** and **deviceInfo** for the Sigma DRM license server
2. Proxy **getKeyRequest** through to Android's `MediaDrm`
3. Decrypt the **encrypted license response** (SMWV/FPTWV format) before passing to `MediaDrm.provideKeyResponse()`

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  ExoPlayer DRM Session                                          │
│  (DefaultDrmSessionManager)                                     │
└──────────┬──────────────────────────────┬───────────────────────┘
           │                              │
           ▼                              ▼
┌─────────────────────┐       ┌──────────────────────────────┐
│  FPlayMediaDrm      │       │  HttpMediaDrmCallback        │
│  (ExoMediaDrm impl) │       │  (license server HTTP call)  │
│                     │       │                              │
│  getKeyRequest() ───┼──┐    │  executeKeyRequest():        │
│  provideKeyResponse()│  │    │  1. requestInfo() → reqId    │
│                     │  │    │  2. POST to license server   │
└─────────────────────┘  │    │  3. Extract JSON "license"   │
                         │    └──────────────────────────────┘
                         ▼
              ┌─────────────────────────┐
              │  FPlayDrmPacker (JNI)   │
              │  libfplaydrm.so         │
              │                         │
              │  requestInfo()          │
              │  getKeyRequest()        │
              │  provideKeyResponse()   │
              │  decryptResponse()      │
              └─────────────────────────┘
                         │
                         ▼
              ┌─────────────────────────┐
              │  android.media.MediaDrm │
              │  (Widevine CDM)         │
              └─────────────────────────┘
```

---

## 1. Device Info Collection (`device_info.cpp`)

On `JNI_OnLoad`, `GlobalState::init()` collects all device properties via JNI:

### Properties (18 fields)

| Index | Short Name       | Source                                      | Example                  |
|-------|------------------|---------------------------------------------|--------------------------|
| 0     | deviceId         | `Settings.Secure.getString("android_id")`   | `"8010a3899890c7b9"`     |
| 1     | packageName      | `PackageManager.getApplicationLabel()`      | `"FPT Play"`             |
| 2     | packageId        | `Context.getPackageName()`                  | `"net.fptplay.ottbox"`   |
| 3     | appVersion       | `PackageInfo.versionName`                   | `"7.29.1"`               |
| 4     | appVersionCode   | `PackageInfo.versionCode`                   | `"4290"`                 |
| 5     | signature        | SHA-1 of APK signing cert (uppercase hex)   | `"9DFF668B73E0...A612"`  |
| 6     | osVersion        | `Build.VERSION.RELEASE`                     | `"11"`                   |
| 7     | osBuild          | `Build.DISPLAY`                             | `"RTT0.211222.001..."`   |
| 8     | deviceModel      | `Build.MODEL`                               | `"FIAT2W"`               |
| 9     | cpuInfo          | `Build.HARDWARE`                            | `"amlogic"`              |
| 10    | sdkVersion       | `Build.VERSION.SDK_INT`                     | `"30"`                   |
| 11    | fingerprint      | `Build.FINGERPRINT`                         | `"FPT-Play/FIAT2W/..."` |
| 12    | manufacture      | `Build.MANUFACTURER`                        | `"Innopia"`              |
| 13    | buildHost        | `Build.HOST`                                | `"..."`                  |
| 14    | buildProduct     | `Build.PRODUCT`                             | `"FIAT2W"`               |
| 15    | brand            | `Build.BRAND`                               | `"FPT-Play"`             |
| 16    | buildBoard       | `Build.BOARD`                               | `"FIAT2W"`               |
| 17    | packerVersion    | Hardcoded                                   | `"1.0.3"`                |

Additional fields not in the index table but included in deviceInfo JSON:
- `platform` = `"android"` (always)
- `deviceName` = `Build.DEVICE`

### String Obfuscation

All JNI class names, method names, and signatures are stored as AES-128-ECB encrypted byte arrays in `obfuscation.h`. They are decrypted at runtime using `HARDCODED_AES_KEY` + PKCS7 unpad. This prevents static analysis from revealing which Android APIs the library uses.

---

## 2. requestInfo — Generate requestId + deviceInfo

**Java:** `FPlayDrmPacker.requestInfo(byte[] challengeData) -> RequestInfo`

**Returns:** `RequestInfo { String requestId, String deviceInfo }`

### 2.1 Build deviceInfo JSON

Uses `org.json.JSONObject` via JNI to build a JSON object with all 20 fields in a specific insertion order:

```
{"appVersionCode":4290,"appVersion":"7.29.1","packageId":"net.fptplay.ottbox",
 "deviceId":"8010a3899890c7b9","platform":"android","deviceName":"FIAT2W",
 "buildBoard":"FIAT2W","brand":"FPT-Play","buildProduct":"FIAT2W",
 "manufacture":"Innopia","fingerprint":"FPT-Play/FIAT2W/...",
 "buildHost":"...","cpuInfo":"amlogic","osBuild":"RTT0.211222.001...",
 "signature":"9DFF668B73E0...","packerVersion":"1.0.3",
 "deviceModel":"FIAT2W","osVersion":"11","packageName":"FPT Play",
 "sdkVersion":"30"}
```

**Note:** `appVersionCode` is inserted as integer, all others as strings. JSON `\/` escaping is removed to match Sigma output.

### 2.2 Generate requestId (UUID)

The requestId is a UUID constructed by **nibble-interleaving** 3 data streams. It is NOT random.

**Algorithm:**

```
Step 1: Generate 8 random bytes using java.security.SecureRandom
        random[0..7]

Step 2: Status flag — clear bit 0 at random[random[0] >> 5]
        idx = random[0] >> 5  (0..7)
        random[idx] &= 0xFE   // server validates this bit

Step 3: Compute seed from first 4 random bytes (big-endian)
        seed = (random[0] << 24) | (random[1] << 16) | (random[2] << 8) | random[3]

Step 4: Reseed with challenge data
        seed = CRC32(seed, challengeData)

Step 5: Hash device info JSON with reseeded value
        hashValue = CRC32(seed, deviceInfoJson)

Step 6: Get current timestamp in seconds
        timeSec = (uint32_t)(currentTimeMillis / 1000)

Step 7: Convert to big-endian byte arrays
        hashBytes[4] = big-endian(hashValue)
        timeBytes[4] = big-endian(timeSec)

Step 8: Interleave nibbles → 16 UUID bytes
        For i = 0..3:
            uuid[4i+0] = (hashBytes[i] & 0xF0) | (random[2i] >> 4)
            uuid[4i+1] = ((hashBytes[i] & 0x0F) << 4) | (random[2i] & 0x0F)
            uuid[4i+2] = (timeBytes[i] & 0xF0) | (random[2i+1] >> 4)
            uuid[4i+3] = ((timeBytes[i] & 0x0F) << 4) | (random[2i+1] & 0x0F)

Step 9: Format as UUID string
        "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
```

**CRC32:** Standard polynomial `0xEDB88320` with configurable seed (`~seed` init, `~crc` finalize).

### 2.3 Integration in HttpMediaDrmCallback

```java
// In executeKeyRequest(), before sending HTTP POST:
RequestInfo fplayInfo = FPlayDrmPacker.requestInfo(request.getData());
// Add to sigma-custom-data JSON:
originalData.put("reqId", fplayInfo.requestId);
originalData.put("deviceInfo", fplayInfo.deviceInfo);
// Base64-encode and set as "custom-data" HTTP header
```

---

## 3. getKeyRequest — Proxy to MediaDrm

**Java:** `FPlayDrmPacker.getKeyRequest(MediaDrm, scope, initData, mimeType, keyType, optionalParams) -> MediaDrm.KeyRequest`

### Algorithm

1. Deobfuscate `"android/media/MediaDrm"` and `"getKeyRequest"` method name/signature
2. Call real `MediaDrm.getKeyRequest()` via JNI reflection
3. Track the request data via `RequestTracker` (CRC32 hash stored in history, max 50 entries)
4. Return the `KeyRequest` object unchanged

The tracking is used by `generateRequestId` to detect duplicate challenges and re-hash the seed.

---

## 4. provideKeyResponse — Decrypt + Forward to MediaDrm

**Java:** `FPlayDrmPacker.provideKeyResponse(MediaDrm, scope, response) -> byte[]`

### Algorithm

1. Receive encrypted response bytes (SMWV or FPTWV format)
2. Call `parseAndDecryptResponse()` to decrypt
3. Deobfuscate `"provideKeyResponse"` method name/signature
4. Call real `MediaDrm.provideKeyResponse(scope, decryptedLicense)` via JNI reflection
5. Return the `keySetId` from MediaDrm

---

## 5. Response Decryption (SMWV/FPTWV Format)

### 5.1 Binary Format

**SMWV format** (Sigma's original):
```
Offset  Size    Field
0       4       Magic: "SMWV" (0x534D5756)
4       4       totalSize (int32, big-endian)
8       4       metadataSize (int32)
12      1       encryptionType (1=XOR, 2=AES-128-CBC)
13      1       fieldB (separator byte for key derivation)
14      1       numKeyBytes (number of key material indices)
15      N       keyMaterial[numKeyBytes] — indices into property table
15+N    16      IV (initialization vector for AES-CBC)
31+N    4       innerSize (int32)
35+N    M       innerData[innerSize] — additional data for key derivation
35+N+M  *       encryptedLicense (remaining bytes)
```

**FPTWV format** (FPlay's own):
```
Offset  Size    Field
0       5       Magic: "FPTWV"
5       4       totalSize (int32, big-endian)
9       ...     Same structure as SMWV after the header
```

**RAW format:** If neither magic matches, the response is passed through as-is (standard Widevine).

### 5.2 Key Derivation Algorithm

The 16-byte decryption key is derived from device properties:

```
Step 1: For each keyMaterial[i] (i = 0..numKeyBytes-1):
            idx = keyMaterial[i]
            propValue = getPropertyByIndex(idx)  // lookup from 18-entry table

Step 2: Concatenate with fieldB separator AFTER EVERY value (including last):
            concat = propValue[0] + chr(fieldB) + propValue[1] + chr(fieldB) + ... + propValue[N-1] + chr(fieldB)

Step 3: Append innerData raw bytes:
            concat = concat + innerData

Step 4: Hash:
            key = MD5(concat)  // 16 bytes
```

**Example:**
```
keyMaterial = [3, 4, 11, 7, 9, 10, 5, 0, 16, 12, 15]
fieldB = 0x2A ('*')

concat = "7.29.1" + "*" + "4290" + "*" + "FPT-Play/FIAT2W/..." + "*" + ... + "FPT-Play" + "*" + innerData
key = MD5(concat) = cd7792b3afb8a77bab96f1240af10c79
```

### 5.3 Decryption

**encryptionType = 2 (AES-128-CBC):**
```
decrypted = AES-128-CBC-Decrypt(encryptedLicense, key, IV)
result = PKCS7_Unpad(decrypted)
```

**encryptionType = 1 (Feedback XOR Cipher):**

This is NOT simple repeating-key XOR. It uses a running feedback variable:

```
feedback = 0  (uint32)
for i = 0 to len-1:
    key_byte = key[i % 16]
    result[i] = data[i] XOR (feedback & 0xFF) XOR key_byte
    feedback = feedback XOR key_byte
    feedback = feedback XOR result[i]
```

The feedback creates a cipher-block-chaining effect where each output byte influences all subsequent decryption.

### 5.4 Output

The decrypted payload is a standard **Widevine License protobuf**:
- First byte `0x08` = protobuf field 1 (varint)
- Contains encrypted content keys that Widevine CDM uses for decryption

---

## 6. File Structure

### C++ Native Code
| File | Purpose |
|------|---------|
| `fplay_drm_packer.cpp` | JNI entry points: requestInfo, getKeyRequest, provideKeyResponse, decryptResponse |
| `fptwv_parser.cpp` | SMWV/FPTWV parser, key derivation (`deriveKey`), `getPropertyByIndex` |
| `crypto.cpp` | AES-128-CBC/ECB, feedback XOR, PKCS7, SHA-256, MD5 |
| `device_info.cpp` | GlobalState: collect all device properties via JNI |
| `request_tracker.cpp` | CRC32 + request history tracking |
| `include/obfuscation.h` | AES-encrypted string constants for JNI class/method names |
| `include/byte_buffer.h` | ByteBuffer for binary parsing |

### Java Interface
| File | Purpose |
|------|---------|
| `com/fptplay/drm/FPlayDrmPacker.java` | JNI interface (native method declarations) |
| `com/fptplay/drm/FPlayMediaDrm.java` | ExoMediaDrm wrapper — routes through FPlayDrmPacker |
| `com/fptplay/drm/RequestInfo.java` | Data class: requestId + deviceInfo |

### Integration Points
| File | Purpose |
|------|---------|
| `HttpMediaDrmCallback.java` | Injects requestId + deviceInfo into HTTP headers; extracts JSON "license" field |
| `DefaultDrmSessionManagerProvider.java` | Uses `FPlayMediaDrm.DEFAULT_PROVIDER` when `isSigmaDrm = true` |

---

## 7. DRM Flow — End to End

```
1. App sets MediaItem with DrmConfiguration(isSigmaDrm=true)
   └→ DefaultDrmSessionManagerProvider selects FPlayMediaDrm.DEFAULT_PROVIDER

2. ExoPlayer opens DRM session
   └→ FPlayMediaDrm.openSession() → MediaDrm.openSession()

3. ExoPlayer requests license
   └→ FPlayMediaDrm.getKeyRequest(scope, schemeDatas, keyType, params)
       └→ FPlayDrmPacker.getKeyRequest() [JNI]
           └→ MediaDrm.getKeyRequest() — returns Widevine challenge bytes

4. HttpMediaDrmCallback.executeKeyRequest()
   a. FPlayDrmPacker.requestInfo(challengeData)
      └→ Returns {requestId: "uuid", deviceInfo: "{json}"}
   b. Adds reqId + deviceInfo to custom-data header (Base64)
   c. HTTP POST to Sigma license server
   d. Server returns: {"license": "<base64-encoded SMWV data>"}
   e. Base64-decode → raw SMWV bytes → return to ExoPlayer

5. ExoPlayer provides license response
   └→ FPlayMediaDrm.provideKeyResponse(scope, smwvBytes)
       └→ FPlayDrmPacker.provideKeyResponse() [JNI]
           a. parseAndDecryptResponse(smwvBytes)
              - Parse SMWV header
              - Extract keyMaterial, fieldB, IV, innerData
              - deriveKey: lookup properties → concat with fieldB → append innerData → MD5
              - Decrypt: AES-CBC or Feedback XOR
              - Result: raw Widevine license protobuf
           b. MediaDrm.provideKeyResponse(scope, decryptedLicense)
              - Widevine CDM installs content keys
              - Returns keySetId

6. Content playback begins with decrypted media keys
```

---

## 8. Build

```bash
./gradlew :lib-exoplayer:publishReleasePublicationToMavenLocal
```

CMakeLists.txt compiles:
- `fplay_drm_packer.cpp`, `fptwv_parser.cpp`, `crypto.cpp`, `device_info.cpp`, `request_tracker.cpp`, `obfuscation.cpp`
- Links: `mbedtls` (AES, MD5, SHA-256), `log` (Android logging)
- Output: `libfplaydrm.so` for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)