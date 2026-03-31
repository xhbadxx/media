#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>

namespace fplay {

// AES-128 key (16 bytes) -- hardcoded in binary
extern const uint8_t HARDCODED_AES_KEY[16];

// AES-128-CBC encrypt (for device info -> server)
// Uses PKCS7 padding
std::vector<uint8_t> aes_cbc_encrypt(
    const uint8_t* plaintext, size_t len,
    const uint8_t* key, const uint8_t* iv);

// AES-128-CBC decrypt (for FPTWV/SMWV response from server)
// Caller must handle PKCS7 unpadding via pkcs7_unpad()
std::vector<uint8_t> aes_cbc_decrypt(
    const uint8_t* ciphertext, size_t len,
    const uint8_t* key, const uint8_t* iv);

// AES-128-ECB decrypt (for obfuscated string decryption)
std::vector<uint8_t> aes_ecb_decrypt(
    const uint8_t* ciphertext, size_t len,
    const uint8_t* key);

// XOR decrypt (type 1 fallback in SMWV/FPTWV)
std::vector<uint8_t> xor_decrypt(
    const uint8_t* data, size_t data_len,
    const uint8_t* key, size_t key_len);

// PKCS7 unpad -- returns actual data size, or -1 on invalid padding
int pkcs7_unpad(const uint8_t* data, size_t len);

// SHA-256 hash
std::vector<uint8_t> sha256(const uint8_t* data, size_t len);

// MD5 hash
std::vector<uint8_t> md5(const uint8_t* data, size_t len);

} // namespace fplay