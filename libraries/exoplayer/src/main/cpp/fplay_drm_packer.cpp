#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <chrono>
#include <functional>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <android/log.h>

#include "include/device_info.h"
#include "include/crypto.h"
#include "include/obfuscation.h"
#include "include/fptwv_parser.h"
#include "include/request_tracker.h"

#define LOG_TAG "FPlayDRM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Base64 encoding table
static const char B64_TABLE[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64Encode(const uint8_t* data, size_t len) {
    std::string result;
    result.reserve(((len + 2) / 3) * 4);
    for (size_t i = 0; i < len; i += 3) {
        uint32_t n = ((uint32_t)data[i]) << 16;
        if (i + 1 < len) n |= ((uint32_t)data[i + 1]) << 8;
        if (i + 2 < len) n |= data[i + 2];
        result += B64_TABLE[(n >> 18) & 0x3F];
        result += B64_TABLE[(n >> 12) & 0x3F];
        result += (i + 1 < len) ? B64_TABLE[(n >> 6) & 0x3F] : '=';
        result += (i + 2 < len) ? B64_TABLE[n & 0x3F] : '=';
    }
    return result;
}

// Convert bytes to hex string
std::string bytesToHex(const uint8_t* data, size_t len) {
    std::stringstream ss;
    ss << std::hex << std::setfill('0');
    for (size_t i = 0; i < len; i++) {
        ss << std::setw(2) << (int)data[i];
    }
    return ss.str();
}

// Generate requestId matching Sigma's algorithm (reverse-engineered from libdrmpacker.so).
//
// The UUID is NOT random — it's constructed by nibble-interleaving 3 data streams:
//   1. 8 random bytes → provide ALL low nibbles (16 nibbles for 16 output bytes)
//   2. CRC32(seed, deviceInfoJson) as 4 big-endian bytes → high nibbles of bytes 0,1,4,5,8,9,12,13
//   3. timestamp_ms as 4 big-endian bytes → high nibbles of bytes 2,3,6,7,10,11,14,15
//
// The CRC32 seed is derived from the first 4 random bytes (big-endian),
// optionally re-hashed if the challenge was seen in request history.
std::string generateRequestId(JNIEnv* env,
                              const uint8_t* challengeData, size_t challengeLen,
                              const std::string& deviceInfoJson) {
    // Step 1: Generate 8 random bytes
    jclass srCls = env->FindClass("java/security/SecureRandom");
    if (!srCls) { LOGE("Failed to find SecureRandom"); return ""; }

    jmethodID srCtor = env->GetMethodID(srCls, "<init>", "()V");
    jmethodID nextBytes = env->GetMethodID(srCls, "nextBytes", "([B)V");
    if (!srCtor || !nextBytes) {
        env->DeleteLocalRef(srCls);
        return "";
    }

    jobject srObj = env->NewObject(srCls, srCtor);
    jbyteArray arr = env->NewByteArray(8);
    env->CallVoidMethod(srObj, nextBytes, arr);

    uint8_t random[8];
    env->GetByteArrayRegion(arr, 0, 8, reinterpret_cast<jbyte*>(random));
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(srObj);
    env->DeleteLocalRef(srCls);

    // Step 2: Status flag — CLEAR bit 0 at random[random[0]>>5].
    // Decompiler showed "| 0x01" (set bit 0) but Sigma UUIDs always have bit 0 = 0.
    // v14 analysis: ALL failed FPlay requests had bit 0 = 1, ALL successes had bit 0 = 0.
    // The server validates this bit. Sigma clears it; decompiler misread the operation.
    {
        uint8_t idx = random[0] >> 5;
        random[idx] = random[idx] & 0xFE;  // Clear bit 0
    }

    // Step 3: Compute seed from first 4 random bytes (big-endian)
    uint32_t seed = ((uint32_t)random[0] << 24) | ((uint32_t)random[1] << 16) |
                    ((uint32_t)random[2] << 8)  | (uint32_t)random[3];

    // Step 4: Always reseed with challenge data
    seed = fplay::crc32(seed, challengeData, challengeLen);

    // Step 5: Hash device info with reseeded value
    uint32_t hashValue = fplay::crc32(seed,
        reinterpret_cast<const uint8_t*>(deviceInfoJson.data()), deviceInfoJson.size());

    // Step 6: Get timestamp in seconds (Sigma uses smulh division of ns by ~10^9)
    auto now = std::chrono::system_clock::now();
    auto sec = std::chrono::duration_cast<std::chrono::seconds>(now.time_since_epoch()).count();
    uint32_t timeSec = static_cast<uint32_t>(sec);

    // Step 7: Convert to big-endian byte arrays
    uint8_t hashBytes[4] = {
        (uint8_t)(hashValue >> 24), (uint8_t)(hashValue >> 16),
        (uint8_t)(hashValue >> 8),  (uint8_t)(hashValue)
    };
    uint8_t timeBytes[4] = {
        (uint8_t)(timeSec >> 24), (uint8_t)(timeSec >> 16),
        (uint8_t)(timeSec >> 8),  (uint8_t)(timeSec)
    };

    // Step 8: Interleave nibbles to produce 16 UUID bytes
    // For each iteration i (0..3), consume 2 random bytes, produce 4 output bytes:
    //   high nibbles from hashBytes[i] for bytes 0,1 and timeBytes[i] for bytes 2,3
    //   low nibbles from random[2i] and random[2i+1]
    uint8_t uuid[16];
    for (int i = 0; i < 4; i++) {
        uuid[4*i]   = (hashBytes[i] & 0xF0) | (random[2*i] >> 4);
        uuid[4*i+1] = ((hashBytes[i] & 0x0F) << 4) | (random[2*i] & 0x0F);
        uuid[4*i+2] = (timeBytes[i] & 0xF0) | (random[2*i+1] >> 4);
        uuid[4*i+3] = ((timeBytes[i] & 0x0F) << 4) | (random[2*i+1] & 0x0F);
    }

    // Step 9: Format as UUID string
    char uuidStr[37];
    snprintf(uuidStr, sizeof(uuidStr),
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             uuid[0], uuid[1], uuid[2], uuid[3],
             uuid[4], uuid[5], uuid[6], uuid[7],
             uuid[8], uuid[9],
             uuid[10], uuid[11], uuid[12], uuid[13], uuid[14], uuid[15]);

    return std::string(uuidStr);
}

// Build JSON string with device info using Java JSONObject via JNI
// This ensures the same non-deterministic field ordering as Sigma's implementation
std::string buildDeviceInfoJson(JNIEnv* env) {
    auto& state = fplay::GlobalState::getInstance();

    // Find org.json.JSONObject class
    jclass jsonCls = env->FindClass("org/json/JSONObject");
    if (!jsonCls) {
        LOGE("Failed to find JSONObject class");
        return "{}";
    }

    jmethodID jsonCtor = env->GetMethodID(jsonCls, "<init>", "()V");
    jmethodID putString = env->GetMethodID(jsonCls, "put",
        "(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;");
    jmethodID putInt = env->GetMethodID(jsonCls, "put",
        "(Ljava/lang/String;I)Lorg/json/JSONObject;");
    jmethodID toString = env->GetMethodID(jsonCls, "toString", "()Ljava/lang/String;");

    if (!jsonCtor || !putString || !putInt || !toString) {
        LOGE("Failed to find JSONObject methods");
        env->DeleteLocalRef(jsonCls);
        return "{}";
    }

    jobject jsonObj = env->NewObject(jsonCls, jsonCtor);
    if (!jsonObj) {
        env->DeleteLocalRef(jsonCls);
        return "{}";
    }

    // Helper lambda to put a string field
    auto putStr = [&](const char* key, const std::string& value) {
        jstring jKey = env->NewStringUTF(key);
        jstring jVal = env->NewStringUTF(value.c_str());
        env->CallObjectMethod(jsonObj, putString, jKey, jVal);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(jKey);
        env->DeleteLocalRef(jVal);
    };

    // Put all fields in Sigma's exact insertion order (verified from real device)
    // Android 11+ JSONObject uses LinkedHashMap → insertion order preserved

    // appVersionCode as integer (first in Sigma's order)
    {
        jstring jKey = env->NewStringUTF("appVersionCode");
        int versionCode = 0;
        try { versionCode = std::stoi(state.appVersionCode); } catch (...) {}
        env->CallObjectMethod(jsonObj, putInt, jKey, versionCode);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(jKey);
    }

    putStr("appVersion", state.appVersion);
    putStr("packageId", state.packageId);
    putStr("deviceId", state.deviceId);
    putStr("platform", state.platform);
    putStr("deviceName", state.deviceName);
    putStr("buildBoard", state.buildBoard);
    putStr("brand", state.brand);
    putStr("buildProduct", state.buildProduct);
    putStr("manufacture", state.manufacture);
    putStr("fingerprint", state.fingerprint);
    putStr("buildHost", state.buildHost);
    putStr("cpuInfo", state.cpuInfo);
    putStr("osBuild", state.osBuild);
    putStr("signature", state.signature);
    putStr("packerVersion", state.packerVersion);
    putStr("deviceModel", state.deviceModel);
    putStr("osVersion", state.osVersion);
    putStr("packageName", state.packageName);
    putStr("sdkVersion", state.sdkVersion);

    // Convert to string and fix slash escaping
    // JSONObject.toString() escapes '/' to '\/' but Sigma doesn't
    auto jResult = (jstring) env->CallObjectMethod(jsonObj, toString);
    std::string result;
    if (jResult) {
        const char* chars = env->GetStringUTFChars(jResult, nullptr);
        result = chars;
        env->ReleaseStringUTFChars(jResult, chars);
        env->DeleteLocalRef(jResult);

        // Remove '\/' escaping → '/' to match Sigma output
        std::string unescaped;
        unescaped.reserve(result.size());
        for (size_t i = 0; i < result.size(); i++) {
            if (result[i] == '\\' && i + 1 < result.size() && result[i + 1] == '/') {
                unescaped += '/';
                i++; // skip the '/'
            } else {
                unescaped += result[i];
            }
        }
        result = unescaped;
    } else {
        result = "{}";
    }

    env->DeleteLocalRef(jsonObj);
    env->DeleteLocalRef(jsonCls);
    return result;
}

// Helper: convert jbyteArray to std::vector<uint8_t>
std::vector<uint8_t> jbyteArrayToVector(JNIEnv* env, jbyteArray arr) {
    if (!arr) return {};
    jsize len = env->GetArrayLength(arr);
    std::vector<uint8_t> result(len);
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

// Helper: convert std::vector<uint8_t> to jbyteArray
jbyteArray vectorToJbyteArray(JNIEnv* env, const std::vector<uint8_t>& vec) {
    jbyteArray arr = env->NewByteArray(vec.size());
    env->SetByteArrayRegion(arr, 0, vec.size(), reinterpret_cast<const jbyte*>(vec.data()));
    return arr;
}

} // anonymous namespace

// =====================================================================
// JNI_OnLoad — Initialize device info on library load
// =====================================================================
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    fplay::GlobalState::getInstance().init(env, vm);
    return JNI_VERSION_1_6;
}

extern "C" {

// =====================================================================
// requestInfo — Generate requestId + encrypted device info
// =====================================================================
JNIEXPORT jobject JNICALL
Java_com_fptplay_drm_FPlayDrmPacker_requestInfo(
        JNIEnv* env, jclass clazz, jbyteArray challengeData) {

    // Build device info JSON via Java JSONObject (matches Sigma's field ordering)
    std::string deviceInfoJson = buildDeviceInfoJson(env);

    // Get challenge bytes for requestId generation
    auto challenge = jbyteArrayToVector(env, challengeData);

    // Generate requestId using Sigma's nibble-interleaving algorithm
    std::string requestId = generateRequestId(env, challenge.data(), challenge.size(), deviceInfoJson);

    // Create RequestInfo Java object
    jclass requestInfoCls = env->FindClass("com/fptplay/drm/RequestInfo");
    if (!requestInfoCls) {
        LOGE("Failed to find RequestInfo class");
        return nullptr;
    }

    jmethodID ctor = env->GetMethodID(requestInfoCls, "<init>",
                                       "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!ctor) {
        LOGE("Failed to find RequestInfo constructor");
        return nullptr;
    }

    jstring jRequestId = env->NewStringUTF(requestId.c_str());
    jstring jDeviceInfo = env->NewStringUTF(deviceInfoJson.c_str());

    jobject result = env->NewObject(requestInfoCls, ctor, jRequestId, jDeviceInfo);

    env->DeleteLocalRef(jRequestId);
    env->DeleteLocalRef(jDeviceInfo);
    env->DeleteLocalRef(requestInfoCls);

    return result;
}

// =====================================================================
// getKeyRequest — Call real MediaDrm.getKeyRequest() via JNI reflection
// =====================================================================
JNIEXPORT jobject JNICALL
Java_com_fptplay_drm_FPlayDrmPacker_getKeyRequest(
        JNIEnv* env, jclass clazz,
        jobject mediaDrm, jbyteArray scope, jbyteArray initData,
        jstring mimeType, jint keyType, jobject optionalParams) {

    // Deobfuscate class and method names
    std::string className = fplay::deobfuscate(fplay::OBF_MEDIADRM_CLASS, fplay::OBF_MEDIADRM_CLASS_LEN);
    std::string methodName = fplay::deobfuscate(fplay::OBF_GET_KEY_REQUEST, fplay::OBF_GET_KEY_REQUEST_LEN);
    std::string methodSig = fplay::deobfuscate(fplay::OBF_GET_KEY_REQUEST_SIG, fplay::OBF_GET_KEY_REQUEST_SIG_LEN);

    // Find the real MediaDrm class and method
    jclass mediaDrmCls = env->FindClass(className.c_str());
    if (!mediaDrmCls || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to find MediaDrm class");
        return nullptr;
    }

    jmethodID getKeyReqMid = env->GetMethodID(mediaDrmCls, methodName.c_str(), methodSig.c_str());
    if (!getKeyReqMid || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(mediaDrmCls);
        LOGE("Failed to find getKeyRequest method");
        return nullptr;
    }

    // Call the real getKeyRequest
    jobject keyRequest = env->CallObjectMethod(mediaDrm, getKeyReqMid,
                                                scope, initData, mimeType, keyType, optionalParams);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(mediaDrmCls);
        LOGE("getKeyRequest call failed");
        return nullptr;
    }

    // Track the request
    if (keyRequest) {
        jclass keyReqCls = env->GetObjectClass(keyRequest);
        jmethodID getDataMid = env->GetMethodID(keyReqCls, "getData", "()[B");
        if (getDataMid && !env->ExceptionCheck()) {
            auto reqData = (jbyteArray) env->CallObjectMethod(keyRequest, getDataMid);
            if (reqData && !env->ExceptionCheck()) {
                auto data = jbyteArrayToVector(env, reqData);
                fplay::RequestTracker::getInstance().track(data.data(), data.size());
                env->DeleteLocalRef(reqData);
            } else { env->ExceptionClear(); }
        } else { env->ExceptionClear(); }
        env->DeleteLocalRef(keyReqCls);
    }

    env->DeleteLocalRef(mediaDrmCls);
    return keyRequest;
}

// =====================================================================
// provideKeyResponse — Parse/decrypt response, call real provideKeyResponse
// =====================================================================
JNIEXPORT jbyteArray JNICALL
Java_com_fptplay_drm_FPlayDrmPacker_provideKeyResponse(
        JNIEnv* env, jclass clazz,
        jobject mediaDrm, jbyteArray scope, jbyteArray response) {

    // Get the raw response bytes
    std::vector<uint8_t> responseData = jbyteArrayToVector(env, response);

    // Parse and decrypt the response (handles SMWV, FPTWV, and raw)
    fplay::ParsedResponse parsed = fplay::parseAndDecryptResponse(
        responseData.data(), responseData.size());

    if (!parsed.success || parsed.decryptedPayload.empty()) {
        LOGE("Failed to parse/decrypt response");
        return nullptr;
    }

    // Deobfuscate class and method names
    std::string className = fplay::deobfuscate(fplay::OBF_MEDIADRM_CLASS, fplay::OBF_MEDIADRM_CLASS_LEN);
    std::string methodName = fplay::deobfuscate(fplay::OBF_PROVIDE_KEY_RESPONSE, fplay::OBF_PROVIDE_KEY_RESPONSE_LEN);
    std::string methodSig = fplay::deobfuscate(fplay::OBF_PROVIDE_KEY_RESPONSE_SIG, fplay::OBF_PROVIDE_KEY_RESPONSE_SIG_LEN);

    // Find the real MediaDrm class and method
    jclass mediaDrmCls = env->FindClass(className.c_str());
    if (!mediaDrmCls || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to find MediaDrm class");
        return nullptr;
    }

    jmethodID provideKeyRespMid = env->GetMethodID(mediaDrmCls, methodName.c_str(), methodSig.c_str());
    if (!provideKeyRespMid || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(mediaDrmCls);
        LOGE("Failed to find provideKeyResponse method");
        return nullptr;
    }

    // Create jbyteArray for the decrypted license
    jbyteArray decryptedArr = vectorToJbyteArray(env, parsed.decryptedPayload);

    // Call the real provideKeyResponse with decrypted Widevine license
    auto keySetId = (jbyteArray) env->CallObjectMethod(
        mediaDrm, provideKeyRespMid, scope, decryptedArr);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("provideKeyResponse call failed");
        env->DeleteLocalRef(decryptedArr);
        env->DeleteLocalRef(mediaDrmCls);
        return nullptr;
    }

    env->DeleteLocalRef(decryptedArr);
    env->DeleteLocalRef(mediaDrmCls);

    return keySetId;
}

// =====================================================================
// decryptResponse — Decrypt only, do NOT call MediaDrm.provideKeyResponse()
// Used for verification: compare FPlay decryption against Sigma's.
// =====================================================================
JNIEXPORT jbyteArray JNICALL
Java_com_fptplay_drm_FPlayDrmPacker_decryptResponse(
        JNIEnv* env, jclass clazz, jbyteArray response) {

    std::vector<uint8_t> responseData = jbyteArrayToVector(env, response);

    LOGE("decryptResponse: input size=%zu", responseData.size());
    // Hex dump first 128 bytes to understand SMWV binary structure
    {
        size_t dumpLen = std::min(responseData.size(), (size_t)128);
        std::string hexDump;
        for (size_t i = 0; i < dumpLen; i++) {
            char buf[4];
            snprintf(buf, sizeof(buf), "%02x ", responseData[i]);
            hexDump += buf;
            if ((i + 1) % 32 == 0) {
                LOGE("decryptResponse hex [%03zu]: %s", i - 31, hexDump.c_str());
                hexDump.clear();
            }
        }
        if (!hexDump.empty()) {
            LOGE("decryptResponse hex [%03zu]: %s",
                 (dumpLen / 32) * 32, hexDump.c_str());
        }
    }

    fplay::ParsedResponse parsed = fplay::parseAndDecryptResponse(
        responseData.data(), responseData.size());

    LOGE("decryptResponse: result success=%d format=%d payloadSize=%zu",
         parsed.success, (int)parsed.format, parsed.decryptedPayload.size());

    if (!parsed.success || parsed.decryptedPayload.empty()) {
        LOGE("decryptResponse: Failed to parse/decrypt");
        return nullptr;
    }

    return vectorToJbyteArray(env, parsed.decryptedPayload);
}

} // extern "C"
