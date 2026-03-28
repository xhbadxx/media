#include "include/request_tracker.h"

namespace fplay {

uint32_t crc32(uint32_t seed, const uint8_t* data, size_t len) {
    uint32_t crc = ~seed;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int j = 0; j < 8; j++) {
            uint32_t mask = -(crc & 1u);  // 0xFFFFFFFF if bit 0 set, else 0
            crc = (crc >> 1) ^ (0xEDB88320u & mask);
        }
    }
    return ~crc;
}

RequestTracker& RequestTracker::getInstance() {
    static RequestTracker instance;
    return instance;
}

void RequestTracker::addHash(uint32_t hash) {
    std::lock_guard<std::mutex> lock(mutex_);
    hashes_.push_back(hash);
    if (hashes_.size() > MAX_ENTRIES) {
        hashes_.erase(hashes_.begin());
    }
}

bool RequestTracker::contains(uint32_t hash) const {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto h : hashes_) {
        if (h == hash) return true;
    }
    return false;
}

void RequestTracker::track(const uint8_t* data, size_t len) {
    addHash(crc32(0, data, len));
}

} // namespace fplay