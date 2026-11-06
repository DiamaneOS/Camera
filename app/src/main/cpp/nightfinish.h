// SPDX-License-Identifier: MIT
#pragma once
#include <atomic>
#include <cstdint>
namespace night {
// Noise-adaptive luminance detail shrinkage and edge-guided chroma filtering on a half-resolution grid.
// highPrecisionInput reads the core's packed RGB10 buffer; output is always ARGB8.
// Optional target is the scene's preview luminance, in [0,1]; zero leaves the tone curve alone.
// steadyPhotoFinish is qualified jointly with longer, scene/gyro-gated Photo acquisition.
// It must not be enabled as a general postprocessing denoiser or for Night.
bool finish(uint32_t* pixels, int width, int height, float target, const std::atomic_bool& cancelled, float zoom = 1, float aspect = 4.f / 3, const float* toneTargets = nullptr, bool spatialNoise = false, bool highPrecisionInput = false, bool steadyPhotoFinish = false);
struct BlackPedestal { bool applied = false; float red = 0, green = 0, blue = 0; };
// Finish for merged camera JPEGs (RGB10 input). The frames were taken with up to gain
// times less sensor gain: restore it in linear light with a highlight roll-off. A night
// scene's constant per-channel linear pedestal (camera black-level offset times its
// colour gains) is measured in the darkest areas and removed before the gain.
bool finishJpeg(uint32_t* pixels, int width, int height, float gain, const std::atomic_bool& cancelled, BlackPedestal* pedestal = nullptr);
}
