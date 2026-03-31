#include "crypto.h"
#include "mbedtls/aes.h"
#include "mbedtls/sha256.h"
#include "mbedtls/md5.h"
#include <cstring>
#include <algorithm>

namespace fplay {

// FPlay's own AES-128 key (spells "OplayDrmPackerKy")
// In production, replace with truly random bytes.
const uint8_t HARDCODED_AES_KEY[16] = {
    0x4F, 0x70, 0x6C, 0x61, 0x79, 0x44, 0x72, 0x6D,
    0x50, 0x61, 0x63, 0x6B, 0x65, 0x72, 0x4B, 0x79
};

std::vector<uint8_t> aes_cbc_encrypt(
    const uint8_t* plaintext, size_t len,
    const uint8_t* key, const uint8_t* iv) {

    // PKCS7 padding
    size_t pad_len = 16 - (len % 16);
    size_t padded_len = len + pad_len;
    std::vector<uint8_t> padded(padded_len);
    memcpy(padded.data(), plaintext, len);
    memset(padded.data() + len, static_cast<uint8_t>(pad_len), pad_len);

    // Encrypt
    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    mbedtls_aes_setkey_enc(&ctx, key, 128);

    std::vector<uint8_t> output(padded_len);
    uint8_t iv_copy[16];
    memcpy(iv_copy, iv, 16);  // mbedtls modifies IV in-place
    mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_ENCRYPT, padded_len,
                          iv_copy, padded.data(), output.data());
    mbedtls_aes_free(&ctx);
    return output;
}

std::vector<uint8_t> aes_cbc_decrypt(
    const uint8_t* ciphertext, size_t len,
    const uint8_t* key, const uint8_t* iv) {

    if (len == 0 || len % 16 != 0) return {};

    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    mbedtls_aes_setkey_dec(&ctx, key, 128);

    std::vector<uint8_t> output(len);
    uint8_t iv_copy[16];
    memcpy(iv_copy, iv, 16);
    mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_DECRYPT, len,
                          iv_copy, ciphertext, output.data());
    mbedtls_aes_free(&ctx);
    return output;
}

std::vector<uint8_t> aes_ecb_decrypt(
    const uint8_t* ciphertext, size_t len,
    const uint8_t* key) {

    if (len == 0 || len % 16 != 0) return {};

    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    mbedtls_aes_setkey_dec(&ctx, key, 128);

    std::vector<uint8_t> output(len);
    for (size_t i = 0; i < len; i += 16) {
        mbedtls_aes_crypt_ecb(&ctx, MBEDTLS_AES_DECRYPT,
                              ciphertext + i, output.data() + i);
    }
    mbedtls_aes_free(&ctx);
    return output;
}

// Sigma's feedback XOR cipher (confirmed from x86_64 disasm at 0x74fc0):
//   feedback = 0
//   for each byte i:
//     key_byte = key[i % keyLen]
//     result[i] = data[i] ^ (feedback & 0xFF) ^ key_byte
//     feedback ^= key_byte
//     feedback ^= result[i]
std::vector<uint8_t> xor_decrypt(
    const uint8_t* data, size_t data_len,
    const uint8_t* key, size_t key_len) {

    std::vector<uint8_t> result(data_len);
    uint32_t feedback = 0;
    for (size_t i = 0; i < data_len; i++) {
        uint8_t key_byte = key[i % key_len];
        result[i] = data[i] ^ (uint8_t)(feedback & 0xFF) ^ key_byte;
        feedback ^= key_byte;
        feedback ^= result[i];
    }
    return result;
}

int pkcs7_unpad(const uint8_t* data, size_t len) {
    if (len == 0) return -1;
    uint8_t pad_val = data[len - 1];
    if (pad_val == 0 || pad_val > 16) return -1;
    if (pad_val > len) return -1;
    // Verify all padding bytes
    for (size_t i = len - pad_val; i < len; i++) {
        if (data[i] != pad_val) return -1;
    }
    return static_cast<int>(len - pad_val);
}

std::vector<uint8_t> sha256(const uint8_t* data, size_t len) {
    std::vector<uint8_t> hash(32);
    mbedtls_sha256(data, len, hash.data(), 0); // 0 = SHA-256 (not SHA-224)
    return hash;
}

std::vector<uint8_t> md5(const uint8_t* data, size_t len) {
    std::vector<uint8_t> hash(16);
    mbedtls_md5(data, len, hash.data());
    return hash;
}

} // namespace fplay