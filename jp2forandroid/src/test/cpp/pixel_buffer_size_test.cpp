#include "../../main/cpp/pixel_buffer_size.h"

struct TestCase {
    const char *name;
    int64_t width;
    int64_t height;
    uint64_t allocationLimit;
    bool expectedValid;
    uint64_t expectedBytes;
};

int runPixelBufferSizeTests() {
    static const TestCase cases[] = {
        {"single pixel", 1, 1, UINT32_MAX, true, 4},
        {"normal image", 640, 480, UINT32_MAX, true, 1228800},
        {"zero width", 0, 10, UINT64_MAX, false, 0},
        {"zero height", 10, 0, UINT64_MAX, false, 0},
        {"negative width", -1, 10, UINT64_MAX, false, 0},
        {"negative height", 10, -1, UINT64_MAX, false, 0},
        {"JNI pixel limit", INT32_MAX - 3LL, 1, UINT64_MAX, true, 8589934576ULL},
        {"JNI header overflow", INT32_MAX - 2LL, 1, UINT64_MAX, false, 0},
        {"signed pixel multiplication overflow", 46341, 46341, UINT64_MAX, false, 0},
        {"unsigned component size", UINT32_MAX, 1, UINT64_MAX, false, 0},
        {"huge dimensions", INT64_MAX, INT64_MAX, UINT64_MAX, false, 0},
        {"last 32-bit allocation", 1073741823, 1, UINT32_MAX, true, 4294967292ULL},
        {"32-bit allocation overflow", 1073741824, 1, UINT32_MAX, false, 0},
        {"same image on 64-bit", 1073741824, 1, UINT64_MAX, true, 4294967296ULL},
        {"exact allocation budget", 2, 2, 16, true, 16},
        {"insufficient allocation budget", 2, 2, 15, false, 0},
    };
    int caseNumber = 0;
    for (const TestCase &test : cases) {
        ++caseNumber;
        uint64_t bytes = UINT64_MAX;
        const bool valid = pagenest::checkedArgbBufferSize(
                test.width, test.height, test.allocationLimit, &bytes);
        if (valid != test.expectedValid || bytes != test.expectedBytes) {
            return caseNumber;
        }
    }
    if (pagenest::checkedArgbBufferSize(1, 1, UINT64_MAX, nullptr)) {
        return caseNumber + 1;
    }
    return 0;
}

#if defined(_WIN32)
extern "C" __declspec(dllexport) int pixel_buffer_size_self_test() {
    return runPixelBufferSizeTests();
}

extern "C" int DllMainCRTStartup(void *, unsigned long, void *) {
    return 1;
}
#else
int main() {
    return runPixelBufferSizeTests();
}
#endif
