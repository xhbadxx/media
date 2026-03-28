#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>
#include <mutex>

namespace fplay {

// CRC32 computation matching Sigma's libdrmpacker.so
// Uses the standard CRC32 polynomial with a configurable seed.
uint32_t crc32(uint32_t seed, const uint8_t* data, size_t len);

class RequestTracker {
public:
    static RequestTracker& getInstance();

    // Add a request hash. Trims to MAX_ENTRIES.
    void addHash(uint32_t hash);

    // Check if a hash exists in history.
    bool contains(uint32_t hash) const;

    // Track a request (compute CRC32 hash + store).
    void track(const uint8_t* data, size_t len);

private:
    RequestTracker() = default;
    static constexpr size_t MAX_ENTRIES = 50;
    std::vector<uint32_t> hashes_;
    mutable std::mutex mutex_;
};

} // namespace fplay