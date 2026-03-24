#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <cstdlib>

class ByteBuffer {
public:
    ByteBuffer(const void* data, size_t length);
    ~ByteBuffer();

    int32_t readInt32();      // big-endian (network byte order)
    uint8_t readByte();
    void readBytes(void* dest, size_t length);
    void reset();

    const uint8_t* data() const;
    size_t size() const;
    size_t remaining() const;

private:
    uint8_t* buffer_;
    size_t capacity_;
    size_t position_;
};