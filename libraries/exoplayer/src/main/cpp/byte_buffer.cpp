#include "byte_buffer.h"

ByteBuffer::ByteBuffer(const void* data, size_t length)
    : capacity_(length), position_(0) {
    buffer_ = static_cast<uint8_t*>(malloc(length));
    memset(buffer_, 0, length);
    if (data != nullptr && length > 0) {
        memcpy(buffer_, data, length);
    }
}

ByteBuffer::~ByteBuffer() {
    free(buffer_);
}

int32_t ByteBuffer::readInt32() {
    // Big-endian (network byte order): most significant byte first
    int32_t value = (static_cast<int32_t>(buffer_[position_]) << 24) |
                    (static_cast<int32_t>(buffer_[position_ + 1]) << 16) |
                    (static_cast<int32_t>(buffer_[position_ + 2]) << 8) |
                    (static_cast<int32_t>(buffer_[position_ + 3]));
    position_ += 4;
    return value;
}

uint8_t ByteBuffer::readByte() {
    return buffer_[position_++];
}

void ByteBuffer::readBytes(void* dest, size_t length) {
    memcpy(dest, buffer_ + position_, length);
    position_ += length;
}

void ByteBuffer::reset() {
    position_ = 0;
}

const uint8_t* ByteBuffer::data() const {
    return buffer_;
}

size_t ByteBuffer::size() const {
    return capacity_;
}

size_t ByteBuffer::remaining() const {
    return capacity_ - position_;
}