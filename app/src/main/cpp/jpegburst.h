// SPDX-License-Identifier: MIT
#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace jpeg_burst {
struct Stats {
    double effectiveFrames = 1;
    int rejectedFrames = 0;
    double maximumAcceptedShift = 0;
    std::vector<std::array<double, 3>> registration;
};

// Bounded RGB8 temporal fusion of camera JPEGs, used by Night on devices that opt in.
class Merger {
public:
    Merger(int width, int height, const std::atomic_bool& cancelled);
    ~Merger();
    Merger(const Merger&) = delete;
    Merger& operator=(const Merger&) = delete;
    bool add(const uint32_t* argb, size_t pixels);
    bool finish(std::vector<uint32_t>& output, Stats& stats);
private:
    struct State;
    std::unique_ptr<State> state_;
};
}
