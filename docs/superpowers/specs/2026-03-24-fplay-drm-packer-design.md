# FPlay DRM Packer Library — Design Spec

## Overview

Build `libfplaydrm.so` — a native C++ DRM packer library that replaces Sigma's `libdrmpacker.so`. Architecture mirrors Sigma exactly, with added support for custom "FPTWV" response format while maintaining backward compatibility with Sigma's "SMWV" format.

## Goals

- Replace Sigma's `com.sigma.packer` with `com.fptplay.drm`
- Compatible with existing Sigma DRM server (SMWV format)
- Support new FPTWV format for future custom server
- Same level of obfuscation as Sigma (AES encrypted strings, C++ native)
- Build directly in Media3 fork, publish as AAR later

## Package & Naming

```
Package:    com.fptplay.drm
Library:    libfplaydrm.so
Magic:      "FPTWV" (FPlay Tech WideVine)
Branch:     feature/fplay-drm-packer (from fplay_v1.9)
```

## Project Structure

```
libraries/exoplayer/src/main/java/com/fptplay/drm/
├── FPlayMediaDrm.java        — Wrapper ExoMediaDrm (giống SigmaMediaDrm)
├── FPlayDrmPacker.java        — 3 native JNI methods
└── RequestInfo.java           — POJO {requestId, deviceInfo}

libraries/exoplayer/src/main/cpp/
├── CMakeLists.txt             — Build config
├── fplay_drm_packer.cpp       — 3 JNI functions + JNI_OnLoad
├── device_info.cpp            — Thu thập device info qua JNI
├── crypto.cpp                 — AES-128 encrypt/decrypt (mbedTLS)
├── obfuscation.cpp            — String encrypt/decrypt
├── fptwv_parser.cpp           — Parse "FPTWV"/"SMWV" response
├── request_tracker.cpp        — Track request hash (FIFO max 50)
├── byte_buffer.cpp            — ByteBuffer utility
└── include/
    ├── crypto.h
    ├── device_info.h
    ├── obfuscation.h
    ├── fptwv_parser.h
    ├── request_tracker.h
    └── byte_buffer.h
```

## Java Classes

### FPlayDrmPacker.java

```java
package com.fptplay.drm;

public class FPlayDrmPacker {
    public static native RequestInfo requestInfo(byte[] challengeData);
    public static native MediaDrm.KeyRequest getKeyRequest(
        MediaDrm mediaDrm, byte[] scope, byte[] initData,
        String mimeType, int keyType, HashMap<String, String> optionalParams);
    public static native byte[] provideKeyResponse(
        MediaDrm mediaDrm, byte[] scope, byte[] response);
}
```

### FPlayMediaDrm.java

Copy of SigmaMediaDrm with:
- `SigmaDrmPacker` → `FPlayDrmPacker`
- `SigmaMediaDrm` → `FPlayMediaDrm`
- `System.loadLibrary("drmpacker")` → `System.loadLibrary("fplaydrm")` (trong static block của FPlayMediaDrm)
- Includes `Api31` inner class for `requiresSecureDecoder` (API 31+)
- All other methods remain pass-through to `android.media.MediaDrm`

Only 2 methods differ from FrameworkMediaDrm:
- `getKeyRequest()` → `FPlayDrmPacker.getKeyRequest()`
- `provideKeyResponse()` → `FPlayDrmPacker.provideKeyResponse()`

### RequestInfo.java

```java
package com.fptplay.drm;

public class RequestInfo {
    public String requestId;
    public String deviceInfo;
    public RequestInfo(String requestId, String deviceInfo) {
        this.requestId = requestId;
        this.deviceInfo = deviceInfo;
    }
}
```

## C++ Native Functions

### JNI_OnLoad

- Create GlobalState singleton (device info cache)
- Collect device info via JNI: model, fingerprint, androidId, sdkVersion, appVersion
- Store in GlobalState for reuse

### getKeyRequest (pass-through + tracking)

1. Decrypt obfuscated class/method names (AES)
2. Call `mediaDrm.getKeyRequest()` via JNI reflection
3. Track request hash (FIFO, max 50 entries / 200 bytes of uint32 hashes)
4. Return original KeyRequest — NO modification

### requestInfo (encrypt device info)

1. Get device info from GlobalState
2. Generate UUID → requestId
3. AES-128 encrypt: deviceInfo + challenge data
4. Base64 encode
5. Return `RequestInfo { requestId, encryptedDeviceInfo }`

### provideKeyResponse (decrypt response)

1. Check magic bytes:
   - "FPTWV" (5 bytes) → new format
   - "SMWV" (4 bytes) → Sigma format (backward compatible)
   - Neither → raw Widevine, pass through
2. Parse header: totalSize, payloadSize, encryptionType, key, IV
3. Decrypt:
   - type 2: AES-128-CBC + PKCS7 unpadding
   - type 1: XOR
4. Call `mediaDrm.provideKeyResponse(decryptedLicense)` via JNI
5. Return keySetId

## FPTWV Response Format

```
Offset  Size    Field
──────  ──────  ────────────────
0x00    5       Magic "FPTWV"
0x05    4       Total size
0x09    4       Payload size
0x0D    1       Encryption type (1=XOR, 2=AES)
0x0E    1       Key derivation param (matches SMWV unknown field)
0x0F    1       Key material length (N)
0x10    N       Key material
0x10+N  16      IV (for AES-CBC)
...     4       Encrypted payload size
...     M       Encrypted Widevine license
```

Also supports SMWV format (4-byte magic, same structure after magic).

Multi-byte fields use big-endian (network byte order).

## Crypto

- Library: mbedTLS (lightweight, BSD license, ~100KB)
- Include: `aes.c`, `aes.h`, `platform.h`, `platform_util.c` (minimal dependencies)
- Operations:
  - AES-128-CBC encrypt with hardcoded key (device info → server)
  - AES-128-CBC decrypt with key from response header (FPTWV/SMWV response → Widevine license)
  - AES-128-ECB decrypt with hardcoded key (obfuscated strings)
  - PKCS7 padding/unpadding
  - XOR decrypt (type 1 fallback)

**Important**: Response decryption uses the key material embedded in the response header (`extraFields`), NOT the hardcoded key. The hardcoded key is only for string obfuscation + device info encryption.

## String Obfuscation

- All Android API names AES encrypted at build time
- Python script generates encrypted byte arrays
- Runtime: decrypt → use → free from memory
- Strings to encrypt:
  - `"android/media/MediaDrm"`
  - `"getKeyRequest"`, `"provideKeyResponse"`
  - `"android/app/ActivityThread"`
  - `"android.os.Build"`, `"android_id"`
  - All JNI method signatures

## Key Management

- 1 hardcoded AES-128 key (16 bytes) in .so binary
- Used for: encrypt device info + decrypt obfuscated strings
- Response decryption uses key from response header (NOT hardcoded key)
- Known limitation: extracting the hardcoded key compromises device info encryption and string obfuscation. Acceptable for parity with Sigma; addressed in future by per-device key derivation.

## Error Handling

- Every JNI call followed by `ExceptionCheck()` → `ExceptionClear()` → return NULL on failure
- `provideKeyResponse`: returns NULL if decryption fails (bad key, corrupted data, invalid padding)
- `getKeyRequest`: returns NULL if JNI reflection fails
- No logging of sensitive data (keys, device info) in release builds

## Thread Safety

- `GlobalState` caches `JavaVM*` (safe to share across threads)
- Do NOT cache `JNIEnv*` (thread-local in JNI)
- Each native function receives its own `JNIEnv*` as first parameter
- `GlobalState` device info fields set once in `JNI_OnLoad`, read-only after that

## Integration Changes in Media3

### DefaultDrmSessionManagerProvider.java

```java
// Line 132-133: Change provider
// Rename isSigmaDrm → isCustomDrm (or keep for backward compat)
if (drmConfiguration.isSigmaDrm) {
    provider = FPlayMediaDrm.DEFAULT_PROVIDER;
}
```

### HttpMediaDrmCallback.java

```java
// Line 185: Change requestInfo call
FPlayDrmPacker.RequestInfo info = FPlayDrmPacker.requestInfo(request.getData());
// was: SigmaDrmPacker.requestInfo(...)
```

### build.gradle (exoplayer module)

```gradle
android {
    defaultConfig {
        externalNativeBuild {
            cmake { cppFlags "-std=c++17" }
        }
        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64' }
    }
    externalNativeBuild {
        cmake { path "src/main/cpp/CMakeLists.txt" }
    }
}
```

## Compatibility Matrix

| Server | Format | Supported |
|---|---|---|
| Sigma DRM server | SMWV | Yes (backward compatible) |
| FPlay DRM server | FPTWV | Yes (new format) |
| Standard Widevine | Raw bytes | Yes (pass-through) |

## Future Enhancements (not in scope)

- OLLVM control flow flattening
- Anti-debug (Frida detection)
- Anti-tamper (.so integrity check)
- Key derivation per device
- White-box cryptography
