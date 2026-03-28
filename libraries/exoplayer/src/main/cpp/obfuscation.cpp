#include "include/obfuscation.h"
#include "include/crypto.h"
#include <vector>

namespace fplay {

std::string deobfuscate(const uint8_t* data, size_t len) {
    std::vector<uint8_t> decrypted = aes_ecb_decrypt(data, len, HARDCODED_AES_KEY);
    if (decrypted.empty()) return "";

    int unpadded = pkcs7_unpad(decrypted.data(), decrypted.size());
    if (unpadded <= 0) return "";

    return std::string(reinterpret_cast<const char*>(decrypted.data()), unpadded);
}

} // namespace fplay
