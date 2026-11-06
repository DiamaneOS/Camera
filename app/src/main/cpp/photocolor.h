// SPDX-License-Identifier: MIT
#pragma once
#include <atomic>
#include <cstdint>
namespace photo {
struct ColorStats { bool applied = false; double before = 0, after = 0, inliers = 0; };
// A bounded global correction against the same viewfinder, never a replacement
// image. Reject scene mismatches and unstable fits; retain calibrated RAW colors.
bool match(uint32_t* pixels, int width, int height, const uint32_t* preview,
           int rotation, float zoom, float aspect, const std::atomic_bool& cancel, ColorStats& stats);
}
