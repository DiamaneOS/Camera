// SPDX-License-Identifier: MIT
#pragma once
#include "nightcore.h"
#include <array>
#include <cstring>
#include <vector>
#include <cmath>

namespace night {
constexpr size_t maxShadingBytes = 24 + 8 * (24 + 64 * 64 * 4 * 4);

// The payload is little endian and maps are already in the capture's CFA site
// order. Own the decoded floats; the caller keeps no pointers into Java memory.
inline bool decodeShading(const uint8_t* bytes, size_t size, int count, int width, int height, int cfa,
        std::array<Frame, 8>& frames, std::array<std::vector<float>, 8>& storage) {
    if (!bytes || size < 24 || size > maxShadingBytes || count < 2 || count > 8 ||
        width < 8 || height < 8 || uint64_t(width) * height > 16'000'000 || cfa < 0 || cfa > 3) return false;
    size_t at = 0;
    auto integer = [&]() -> uint32_t {
        uint32_t v = uint32_t(bytes[at]) | uint32_t(bytes[at + 1]) << 8 |
            uint32_t(bytes[at + 2]) << 16 | uint32_t(bytes[at + 3]) << 24;
        at += 4; return v;
    };
    if (integer() != 0x3143534c || integer() != 1 || integer() != uint32_t(count) ||
        integer() != uint32_t(width) || integer() != uint32_t(height) || integer() != uint32_t(cfa)) return false;
    for (int i = 0; i < count; ++i) {
        if (size - at < 24) return false;
        uint32_t columns = integer(), rows = integer(), left = integer(), top = integer(), right = integer(), bottom = integer();
        if (columns < 1 || columns > 64 || rows < 1 || rows > 64 || left >= uint32_t(width) || top >= uint32_t(height) ||
            right > uint32_t(width) || bottom > uint32_t(height) || right <= left + 1 || bottom <= top + 1) return false;
        size_t values = size_t(columns) * rows * 4;
        if (size - at < values * 4) return false;
        auto& data = storage[size_t(i)]; data.resize(values);
        for (size_t n = 0; n < values; ++n) {
            uint32_t bits = integer(); float gain;
            std::memcpy(&gain, &bits, sizeof(gain));
            if (!std::isfinite(gain) || gain < 1 || gain > 32) return false;
            data[n] = gain;
        }
        frames[size_t(i)].shading = {data.data(), data.size(), int(columns), int(rows), int(left), int(top), int(right), int(bottom)};
    }
    return at == size;
}
}
