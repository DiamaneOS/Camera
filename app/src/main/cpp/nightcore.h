// SPDX-License-Identifier: MIT
#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace night {
struct Noise { float slope; float offset; };
struct Shading {
    const float* gains = nullptr; // Per-cell CFA site order, not fixed RGGB order.
    size_t gainCount = 0;
    int columns = 0, rows = 0;
    int left = 0, top = 0, right = 0, bottom = 0;
};
struct Frame {
    const uint8_t* data;
    size_t bytes;
    int rowStride;
    std::array<float, 4> black;
    float white;
    std::array<Noise, 4> noise;
    std::array<int, 4> periodicGrid{};
    Shading shading{};
};
struct Config {
    int width;
    int height;
    int cfa;
    int reference;
    float exposure;
    std::array<float, 9> color;
    // Internal RGB10 working buffer; the finisher returns ordinary ARGB8.
    // Saved DNG samples are never changed by this processing representation.
    bool highPrecision = false;
    // Noise-aware, overlapping spatial shrinkage after motion-rejected fusion.
    bool spatialDenoise = false;
    // Directional green interpolation and green-guided colour differences.
    bool edgeAwareDemosaic = false;
    // Continuous, frequency-shaped Wiener shrinkage. Qualification is per capture mode.
    bool frequencyDenoise = false;
};
struct Stats {
    double effectiveFrames = 0;
    int inputFrames = 0;
};
// Develop a normalized, contiguous Bayer working buffer without integer re-encoding.
// Negative below-black values are retained until colour conversion. No denoising or
// RAW-file writing occurs here; spatialDenoise/reference apply only to process().
// Input and output each contain width*height samples; the input stays read-only.
bool develop(const float* raw, const Config& config, uint32_t* argb,
             const std::atomic_bool& cancelled);
// Calibrated, motion-rejected linear fusion, before spatial denoising/development.
// Output contains width*height normalized Bayer values, including below-black
// values. spatialDenoise does not apply at this stage. Discard output on failure.
bool fuse(const Frame* frames, int count, const Config& config, float* output,
          const std::atomic_bool& cancelled, Stats& stats);
// Frames are fixed-exposure RAW16, row-strided, little endian. Output uses logical
// 0xAARRGGBB values by default. highPrecision uses internal A2R10G10B10, which
// must pass through finish(highPrecisionInput=true) before Bitmap.setPixels().
bool process(const Frame* frames, int count, const Config& config, uint32_t* argb,
             const std::atomic_bool& cancelled, Stats& stats);
}
