#include "crypto.h"
#include "mbedtls/aes.h"
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

std::vector<uint8_t> xor_decrypt(
    const uint8_t* data, size_t data_len,
    const uint8_t* key, size_t key_len) {

    std::vector<uint8_t> result(data_len);
    for (size_t i = 0; i < data_len; i++) {
        result[i] = data[i] ^ key[i % key_len];
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

} // namespace fplay