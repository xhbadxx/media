#include "include/fptwv_parser.h"
#include "include/byte_buffer.h"
#include "include/crypto.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "FPlayDRM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fplay {

// SMWV magic: 0x534D5756 ("SMWV" as big-endian int32)
static const uint32_t MAGIC_SMWV = 0x534D5756;

// FPTWV magic: "FPTWV" (5 bytes)
static const uint8_t MAGIC_FPTWV[] = {'F', 'P', 'T', 'W', 'V'};

// Parse and decrypt the common payload (shared between SMWV and FPTWV)
// At this point, the buffer is positioned after the magic and totalSize.
static ParsedResponse decryptPayload(ByteBuffer& buf, int32_t totalSize,
                                     size_t headerSize, ResponseFormat format,
                                     const uint8_t* rawData, size_t rawLen) {
    ParsedResponse result;
    result.format = format;

    // Validate total size
    size_t remaining = buf.remaining();
    if (remaining + headerSize != (size_t)totalSize) {
        LOGE("Size mismatch: remaining=%zu + header=%zu != totalSize=%d",
             remaining, headerSize, totalSize);
        result.decryptedPayload.assign(rawData, rawData + rawLen);
        result.format = ResponseFormat::RAW;
        result.success = true;
        return result;
    }

    int32_t payloadSize = buf.readInt32();
    if (payloadSize >= totalSize || payloadSize <= 0) {
        LOGE("Invalid payloadSize: %d (totalSize=%d)", payloadSize, totalSize);
        result.decryptedPayload.assign(rawData, rawData + rawLen);
        result.format = ResponseFormat::RAW;
        result.success = true;
        return result;
    }

    uint8_t encryptionType = buf.readByte();  // 1 = XOR, 2 = AES
    uint8_t unknown = buf.readByte();
    uint8_t numExtraFields = buf.readByte();
    (void)unknown;

    // Read key material
    std::vector<uint8_t> keyMaterial(numExtraFields);
    if (numExtraFields > 0) {
        buf.readBytes(keyMaterial.data(), numExtraFields);
    }

    // Read IV (16 bytes)
    uint8_t iv[16] = {0};
    buf.readBytes(iv, 16);

    // Read encrypted payload
    int32_t encPayloadSize = buf.readInt32();
    if (encPayloadSize <= 0 || (size_t)encPayloadSize > buf.remaining()) {
        LOGE("Invalid encPayloadSize: %d (remaining=%zu)", encPayloadSize, buf.remaining());
        result.decryptedPayload.assign(rawData, rawData + rawLen);
        result.format = ResponseFormat::RAW;
        result.success = true;
        return result;
    }

    std::vector<uint8_t> encPayload(encPayloadSize);
    buf.readBytes(encPayload.data(), encPayloadSize);

    // Decrypt based on type
    if (encryptionType == 2 && numExtraFields >= 16) {
        // AES-128-CBC decrypt
        std::vector<uint8_t> decrypted = aes_cbc_decrypt(
            encPayload.data(), encPayload.size(),
            keyMaterial.data(), iv);

        if (decrypted.empty()) {
            LOGE("AES decrypt failed");
            result.success = false;
            return result;
        }

        // PKCS7 unpadding
        int actualSize = pkcs7_unpad(decrypted.data(), decrypted.size());
        if (actualSize <= 0) {
            LOGE("PKCS7 unpad failed");
            result.success = false;
            return result;
        }

        result.decryptedPayload.assign(decrypted.begin(), decrypted.begin() + actualSize);
        result.success = true;

    } else if (encryptionType == 1 && !keyMaterial.empty()) {
        // XOR decrypt
        result.decryptedPayload = xor_decrypt(
            encPayload.data(), encPayload.size(),
            keyMaterial.data(), keyMaterial.size());
        result.success = true;

    } else {
        LOGE("Unknown encryptionType=%d or insufficient key material (%d bytes)",
             encryptionType, numExtraFields);
        result.success = false;
    }

    return result;
}

ParsedResponse parseAndDecryptResponse(const uint8_t* data, size_t len) {
    ParsedResponse result;

    if (!data || len < 8) {
        // Too small for any format — pass through
        result.format = ResponseFormat::RAW;
        result.decryptedPayload.assign(data, data + len);
        result.success = true;
        return result;
    }

    // Check for FPTWV (5-byte magic)
    if (len >= 5 && memcmp(data, MAGIC_FPTWV, 5) == 0) {
        ByteBuffer buf(data, len);
        // Skip 5-byte magic
        for (int i = 0; i < 5; i++) buf.readByte();
        int32_t totalSize = buf.readInt32();
        // headerSize = 5 (magic) + 4 (totalSize) = 9
        return decryptPayload(buf, totalSize, 9, ResponseFormat::FPTWV, data, len);
    }

    // Check for SMWV (4-byte magic, read as int32 big-endian)
    ByteBuffer buf(data, len);
    int32_t magic = buf.readInt32();

    if ((uint32_t)magic == MAGIC_SMWV) {
        int32_t totalSize = buf.readInt32();
        // headerSize = 4 (magic) + 4 (totalSize) = 8
        return decryptPayload(buf, totalSize, 8, ResponseFormat::SMWV, data, len);
    }

    // Not a known format — pass through raw
    result.format = ResponseFormat::RAW;
    result.decryptedPayload.assign(data, data + len);
    result.success = true;
    return result;
}

} // namespace fplay
