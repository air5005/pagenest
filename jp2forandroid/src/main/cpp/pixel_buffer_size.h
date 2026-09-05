#ifndef PAGENEST_PIXEL_BUFFER_SIZE_H
#define PAGENEST_PIXEL_BUFFER_SIZE_H

#include <stdint.h>

namespace pagenest {

// The JNI result is an int array containing width, height, alpha, then ARGB pixels.
// allocationLimit is SIZE_MAX at runtime and can model a narrower ABI in tests.
inline bool checkedArgbBufferSize(int64_t width, int64_t height,
                                  uint64_t allocationLimit, uint64_t *byteCount) {
    if (byteCount == nullptr) {
        return false;
    }
    *byteCount = 0;
    const int64_t maxPixelCount = INT32_MAX - 3;
    if (width <= 0 || height <= 0 || width > maxPixelCount / height) {
        return false;
    }

    const uint64_t pixelCount = static_cast<uint64_t>(width) * height;
    if (pixelCount > allocationLimit / sizeof(int32_t)) {
        return false;
    }
    *byteCount = pixelCount * sizeof(int32_t);
    return true;
}

} // namespace pagenest

#endif // PAGENEST_PIXEL_BUFFER_SIZE_H
