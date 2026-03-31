#include "include/fptwv_parser.h"
#include "include/byte_buffer.h"
#include "include/crypto.h"
#include "include/device_info.h"
#include <android/log.h>
#include <cstring>
#include <string>
#define LOG_TAG "FPlayDRM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fplay {

static const uint32_t MAGIC_SMWV = 0x534D5756;
static const uint8_t MAGIC_FPTWV[] = {'F', 'P', 'T', 'W', 'V'};

// Sigma lookup table — confirmed via x86_64 disasm of name table at 0x7ee48-0x7f0f5
// The name table has 18 entries (0x1b0 / 24 = 18), allocated at 0x7f10a.
// Indices map to Sigma's internal short names, NOT directly to system properties.
// All values come from GlobalState which collects them via JNI + Build fields.
static std::string getPropertyByIndex(uint8_t index) {
    auto& state = GlobalState::getInstance();
    switch (index) {
        case 0:  return state.deviceId;         // "deviceId" — ANDROID_ID
        case 1:  return state.packageName;      // "packageName" — app label (e.g. "FPT Play")
        case 2:  return state.packageId;        // "packageId" — "net.fptplay.ottbox"
        case 3:  return state.appVersion;       // "appVersion" — versionName "7.29.1"
        case 4:  return state.appVersionCode;   // "appVersionCode" — versionCode "4290"
        case 5:  return state.signature;        // "signature" — SHA-1 hex
        case 6:  return state.osVersion;        // "osVersion" — ro.build.version.release
        case 7:  return state.osBuild;          // "osBuild" — ro.build.display.id
        case 8:  return state.deviceModel;      // "deviceModel" — ro.product.model
        case 9:  return state.cpuInfo;          // "cpuInfo" — ro.hardware
        case 10: return state.sdkVersion;       // "sdkVersion" — ro.build.version.sdk
        case 11: return state.fingerprint;      // "fingerprint" — ro.build.fingerprint
        case 12: return state.manufacture;      // "manufacture" — ro.product.manufacturer
        case 13: return state.buildHost;        // "buildHost" — ro.build.host
        case 14: return state.buildProduct;     // "buildProduct" — ro.product.name
        case 15: return state.brand;            // "brand" — ro.product.brand
        case 16: return state.buildBoard;       // "buildBoard" — ro.product.board
        case 17: return state.packerVersion;    // "packerVersion" — "1.0.3"
        default: return "";
    }
}

// Derive AES/XOR key using Sigma's algorithm (confirmed from x86_64 disasm):
// 1. For each keyMaterial[i], look up device property value
// 2. Write propValue + fieldB for EVERY element (including last → trailing separator)
// 3. Append innerData raw bytes
// 4. MD5(concat) → 16-byte key
static std::vector<uint8_t> deriveKey(const std::vector<uint8_t>& keyMaterial,
                                       uint8_t fieldB,
                                       const uint8_t* innerData, size_t innerSize) {
    std::string concat;
    for (size_t i = 0; i < keyMaterial.size(); i++) {
        uint8_t idx = keyMaterial[i];
        std::string propValue = getPropertyByIndex(idx);
        LOGE("deriveKey: [%zu] idx=%d → \"%s\"", i, idx, propValue.c_str());
        if (propValue.empty()) {
            LOGE("deriveKey: EMPTY property for index %d", idx);
            return {};
        }
        concat += propValue;
        concat += (char)fieldB;  // trailing separator after EVERY value (including last)
    }

    // Append innerData raw bytes
    if (innerData && innerSize > 0) {
        concat.append(reinterpret_cast<const char*>(innerData), innerSize);
    }

    LOGE("deriveKey: concat len=%zu (props+innerData=%zu), fieldB=0x%02x('%c')",
         concat.size(), innerSize, fieldB, fieldB);

    auto key = md5(reinterpret_cast<const uint8_t*>(concat.data()), concat.size());
    {
        std::string keyHex;
        for (int i = 0; i < 16; i++) {
            char h[4]; snprintf(h, sizeof(h), "%02x", key[i]); keyHex += h;
        }
        LOGE("deriveKey: key = %s", keyHex.c_str());
    }
    return key;
}

// Parse and decrypt the common payload (shared between SMWV and FPTWV)
static ParsedResponse decryptPayload(ByteBuffer& buf, int32_t totalSize,
                                     size_t headerSize, ResponseFormat format,
                                     const uint8_t* rawData, size_t rawLen) {
    ParsedResponse result;
    result.format = format;

    size_t remaining = buf.remaining();
    if (remaining + headerSize != (size_t)totalSize) {
        LOGE("Size mismatch: remaining=%zu + header=%zu != totalSize=%d",
             remaining, headerSize, totalSize);
        result.decryptedPayload.assign(rawData, rawData + rawLen);
        result.format = ResponseFormat::RAW;
        result.success = true;
        return result;
    }

    int32_t metadataSize = buf.readInt32();
    if (metadataSize >= totalSize || metadataSize <= 0) {
        LOGE("Invalid metadataSize: %d (totalSize=%d)", metadataSize, totalSize);
        result.decryptedPayload.assign(rawData, rawData + rawLen);
        result.format = ResponseFormat::RAW;
        result.success = true;
        return result;
    }

    uint8_t encryptionType = buf.readByte();
    uint8_t fieldB = buf.readByte();
    uint8_t numKeyBytes = buf.readByte();
    LOGE("decryptPayload: metadataSize=%d encType=%d fieldB=0x%02x numKeyBytes=%d",
         metadataSize, encryptionType, fieldB, numKeyBytes);

    std::vector<uint8_t> keyMaterial(numKeyBytes);
    if (numKeyBytes > 0) {
        buf.readBytes(keyMaterial.data(), numKeyBytes);
    }

    uint8_t iv[16] = {0};
    buf.readBytes(iv, 16);

    int32_t innerSize = buf.readInt32();
    std::vector<uint8_t> innerData;
    if (innerSize > 0 && (size_t)innerSize <= buf.remaining()) {
        innerData.resize(innerSize);
        buf.readBytes(innerData.data(), innerSize);
    }

    size_t encLicenseSize = buf.remaining();
    std::vector<uint8_t> encLicense(encLicenseSize);
    if (encLicenseSize > 0) {
        buf.readBytes(encLicense.data(), encLicenseSize);
    }

    LOGE("decryptPayload: innerSize=%d encLicenseSize=%zu (mod16=%zu)",
         innerSize, encLicenseSize, encLicenseSize % 16);

    // Derive key: MD5(concat_of_properties + innerData)
    std::vector<uint8_t> derivedKey = deriveKey(keyMaterial, fieldB,
        innerData.data(), innerData.size());
    if (derivedKey.size() != 16) {
        LOGE("decryptPayload: key derivation failed");
        result.success = false;
        return result;
    }

    if (encryptionType == 2 && encLicenseSize > 0 && encLicenseSize % 16 == 0) {
        uint8_t iv_copy[16];
        memcpy(iv_copy, iv, 16);
        auto decrypted = aes_cbc_decrypt(encLicense.data(), encLicenseSize,
                                          derivedKey.data(), iv_copy);
        if (!decrypted.empty()) {
            int unpadded = pkcs7_unpad(decrypted.data(), decrypted.size());
            LOGE("decryptPayload: AES first4=%02x%02x%02x%02x unpad=%d",
                 decrypted[0], decrypted[1], decrypted[2], decrypted[3], unpadded);
            if (unpadded > 0) decrypted.resize(unpadded);
            result.decryptedPayload = std::move(decrypted);
            result.success = true;
            return result;
        }
    }

    if (encryptionType == 1 && encLicenseSize > 0) {
        auto decrypted = xor_decrypt(encLicense.data(), encLicenseSize,
                                      derivedKey.data(), 16);
        LOGE("decryptPayload: XOR first4=%02x%02x%02x%02x",
             decrypted[0], decrypted[1], decrypted[2], decrypted[3]);
        result.decryptedPayload = std::move(decrypted);
        result.success = true;
        return result;
    }

    LOGE("decryptPayload: unsupported encType=%d or empty license", encryptionType);
    result.success = false;
    return result;
}

ParsedResponse parseAndDecryptResponse(const uint8_t* data, size_t len) {
    ParsedResponse result;

    if (!data || len < 8) {
        result.format = ResponseFormat::RAW;
        result.decryptedPayload.assign(data, data + len);
        result.success = true;
        return result;
    }

    if (len >= 5 && memcmp(data, MAGIC_FPTWV, 5) == 0) {
        ByteBuffer buf(data, len);
        for (int i = 0; i < 5; i++) buf.readByte();
        int32_t totalSize = buf.readInt32();
        return decryptPayload(buf, totalSize, 9, ResponseFormat::FPTWV, data, len);
    }

    ByteBuffer buf(data, len);
    int32_t magic = buf.readInt32();

    if ((uint32_t)magic == MAGIC_SMWV) {
        int32_t totalSize = buf.readInt32();
        return decryptPayload(buf, totalSize, 8, ResponseFormat::SMWV, data, len);
    }

    result.format = ResponseFormat::RAW;
    result.decryptedPayload.assign(data, data + len);
    result.success = true;
    return result;
}

} // namespace fplay