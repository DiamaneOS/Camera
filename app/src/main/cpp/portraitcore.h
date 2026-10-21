// SPDX-License-Identifier: MIT
#pragma once
#include <atomic>
#include <cstdint>
namespace portrait {
// Shares the saved-photo edge refinement; the live worker is bounded to 512 x 512.
bool refineMask(const uint32_t* source, int width, int height, const float* mask,
                int maskWidth, int maskHeight, float* output, const std::atomic_bool& cancel);
// Bounded live controls share saved-photo focus selection and edge refinement.
bool previewControls(const uint32_t* source,int width,int height,const float* mask,int maskWidth,int maskHeight,
                     const float* depth,int depthWidth,int depthHeight,float subjectX,float subjectY,
                     uint32_t* output,const std::atomic_bool& cancel);
bool render(const uint32_t* source, int width, int height, const float* mask,
            int maskWidth, int maskHeight, int radius, uint32_t* output, const std::atomic_bool& cancel,
            const float* depth = nullptr, int depthWidth = 0, int depthHeight = 0, float subjectX = .5f, float subjectY = .5f);
}
