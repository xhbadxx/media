#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>

namespace fplay {

enum class ResponseFormat {
    RAW,    // Not SMWV/FPTWV — pass through as-is
    SMWV,   // Sigma format (4-byte magic "SMWV")
    FPTWV   // FPlay format (5-byte magic "FPTWV")
};

struct ParsedResponse {
    ResponseFormat format = ResponseFormat::RAW;
    std::vector<uint8_t> decryptedPayload;
    bool success = false;

    // Raw SMWV components (populated when decryption fails, for probing)
    uint8_t encType = 0;
    uint8_t fieldB = 0;
    std::vector<uint8_t> keyMaterial;
    uint8_t iv[16] = {0};
    std::vector<uint8_t> innerData;
    std::vector<uint8_t> encLicense;  // remaining bytes after metadata section
};

// Parse and decrypt an SMWV or FPTWV response.
// Returns decrypted Widevine license bytes, or the raw input if not a known format.
ParsedResponse parseAndDecryptResponse(const uint8_t* data, size_t len);

} // namespace fplay
