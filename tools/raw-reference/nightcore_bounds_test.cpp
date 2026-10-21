// SPDX-License-Identifier: MIT
// Input bounds of the Night core: frame count, total RAW bytes, row stride and cancellation.
#include "../../app/src/main/cpp/nightcore.h"
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>

namespace {
night::Config config(int w, int h) { return {w, h, 0, 0, 1, {1, 0, 0, 0, 1, 0, 0, 0, 1}}; }
night::Frame frame(const uint16_t* data, size_t bytes, int stride) {
    return {reinterpret_cast<const uint8_t*>(data), bytes, stride,
            {64, 64, 64, 64}, 1023, {{{0, .0001f}, {0, .0001f}, {0, .0001f}, {0, .0001f}}}};
}
}

int main() {
    std::atomic_bool live{false};
    night::Stats stats;
    constexpr int w = 64, h = 48;
    std::vector<uint16_t> raw(size_t(w) * h, 300);
    std::vector<uint32_t> out(raw.size());
    std::vector<night::Frame> frames(9, frame(raw.data(), raw.size() * 2, w * 2));
    auto cfg = config(w, h);
    assert(night::process(frames.data(), 2, cfg, out.data(), live, stats));
    assert(night::process(frames.data(), 8, cfg, out.data(), live, stats));
    // A ninth frame and an empty burst are refused.
    assert(!night::process(frames.data(), 9, cfg, out.data(), live, stats));
    assert(!night::process(frames.data(), 0, cfg, out.data(), live, stats));
    // Row stride: shorter than a row, odd, or beyond the slack, or a buffer too short for it.
    auto bad = frames;
    bad[1].rowStride = w * 2 - 2; assert(!night::process(bad.data(), 2, cfg, out.data(), live, stats));
    bad = frames; bad[1].rowStride = w * 2 + 1; assert(!night::process(bad.data(), 2, cfg, out.data(), live, stats));
    bad = frames; bad[1].rowStride = w * 2 + 4098; assert(!night::process(bad.data(), 2, cfg, out.data(), live, stats));
    bad = frames; bad[1].rowStride = w * 2 + 64; assert(!night::process(bad.data(), 2, cfg, out.data(), live, stats));
    // Declared bytes over the 240 MiB total are refused before any read.
    bad = frames; for (auto& f : bad) f.bytes = 31u * 1024 * 1024;
    assert(!night::process(bad.data(), 8, cfg, out.data(), live, stats));
    // A cancellation from another thread ends a large burst with failure, not a crash.
    constexpr int bw = 1024, bh = 768;
    std::vector<uint16_t> big(size_t(bw) * bh, 300);
    std::vector<uint32_t> bigOut(big.size());
    std::vector<night::Frame> bigFrames(8, frame(big.data(), big.size() * 2, bw * 2));
    auto bigCfg = config(bw, bh);
    std::atomic_bool cancel{false};
    std::thread stopper([&] { std::this_thread::sleep_for(std::chrono::milliseconds(5)); cancel = true; });
    bool finished = night::process(bigFrames.data(), 8, bigCfg, bigOut.data(), cancel, stats);
    stopper.join();
    std::cout << "cancelled run finished=" << finished << '\n';
    std::cout << "nightcore bounds: ok\n";
}
