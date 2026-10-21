// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include "rawshading.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>
#include <random>
#include <vector>

namespace {
void integer(std::vector<uint8_t>& out, uint32_t value) {
    for (int i = 0; i < 4; ++i) out.push_back(uint8_t(value >> (i * 8)));
}
void floating(std::vector<uint8_t>& out, float value) {
    uint32_t bits; std::memcpy(&bits, &value, 4); integer(out, bits);
}
std::vector<uint8_t> encoded(int cfa = 0) {
    std::vector<uint8_t> bytes;
    for (uint32_t v : {0x3143534cu, 1u, 2u, 16u, 12u, uint32_t(cfa)}) integer(bytes, v);
    for (int frame = 0; frame < 2; ++frame) {
        for (uint32_t v : {3u, 2u, 2u, 2u, 14u, 10u}) integer(bytes, v);
        for (int y = 0; y < 2; ++y) for (int x = 0; x < 3; ++x) for (int site = 0; site < 4; ++site)
            floating(bytes, 1.f + .1f * site + .2f * x / 2 + .3f * y + .1f * x / 2 * y);
    }
    return bytes;
}
}

int main() {
    constexpr int w = 16, h = 12;
    std::atomic_bool cancelled(false);
    std::vector<uint16_t> raw(w * h);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) raw[y * w + x] = uint16_t(50 + (x * 7 + y * 3) % 160);
    const auto original = raw;
    night::Frame frame{reinterpret_cast<const uint8_t*>(raw.data()), raw.size() * 2, w * 2, {64,64,64,64}, 1023, {}};
    night::Config cfg{w,h,0,0,1,{1,0,0,0,1,0,0,0,1}};
    std::vector<float> fused(w * h), expected(w * h);
    std::vector<uint32_t> rendered(w * h), oracle(w * h);
    night::Stats stats;
    for (int cfa = 0; cfa < 4; ++cfa) {
        auto bytes = encoded(cfa);
        std::array<night::Frame, 8> frames{}; frames[0] = frame; frames[1] = frame;
        std::array<std::vector<float>, 8> storage;
        assert(night::decodeShading(bytes.data(), bytes.size(), 2, w, h, cfa, frames, storage));
        cfg.cfa = cfa;
        assert(night::fuse(frames.data(), 2, cfg, fused.data(), cancelled, stats));
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            float u = std::clamp((x - 2.f) / 11.f, 0.f, 1.f), v = std::clamp((y - 2.f) / 7.f, 0.f, 1.f);
            float gain = 1.f + .1f * ((y & 1) * 2 + (x & 1)) + .2f * u + .3f * v + .1f * u * v;
            expected[y * w + x] = (float(raw[y * w + x]) - 64) / 959 * gain;
            assert(std::abs(fused[y * w + x] - expected[y * w + x]) < 2e-7f);
        }
        for (bool precise : {false, true}) {
            cfg.highPrecision = precise;
            assert(night::process(frames.data(), 2, cfg, rendered.data(), cancelled, stats));
            assert(night::develop(expected.data(), cfg, oracle.data(), cancelled));
            for (size_t i = 0; i < oracle.size(); ++i) for (int c = 0; c < 3; ++c) {
                int shift = c * (precise ? 10 : 8), mask = precise ? 1023 : 255;
                assert(std::abs(int((rendered[i] >> shift) & mask) - int((oracle[i] >> shift) & mask)) <= 1);
            }
        }
        assert(fused[0] < 0); // Below-black signal is retained, including at map boundaries.
        cancelled = true; assert(!night::fuse(frames.data(), 2, cfg, fused.data(), cancelled, stats)); cancelled = false;
        for (size_t length = 0; length < bytes.size(); ++length) {
            std::array<night::Frame, 8> parsed{}; std::array<std::vector<float>, 8> memory;
            assert(!night::decodeShading(bytes.data(), length, 2, w, h, cfa, parsed, memory));
        }
        bytes.push_back(0);
        assert(!night::decodeShading(bytes.data(), bytes.size(), 2, w, h, cfa, frames, storage));
    }
    assert(raw == original);

    // A corrected value above one is not sensor saturation. Both frames must
    // still contribute when their actual sensor samples are below white.
    std::fill(raw.begin(), raw.end(), 640);
    std::vector<float> constant(4, 3.f);
    std::array<night::Frame, 2> bright{frame, frame};
    for (auto& f : bright) f.shading = {constant.data(), constant.size(), 1, 1, 0, 0, w, h};
    cfg.cfa = 0;
    assert(night::fuse(bright.data(), 2, cfg, fused.data(), cancelled, stats));
    assert(stats.effectiveFrames == 2 && fused[0] > 1);
    for (float bad : {0.9f, 32.1f, std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
        constant[0] = bad; assert(!night::fuse(bright.data(), 2, cfg, fused.data(), cancelled, stats));
    }
    constant[0] = 3;
    bright[0].shading.gainCount = 3; assert(!night::fuse(bright.data(), 2, cfg, fused.data(), cancelled, stats));

    // Fuzz the bounded metadata carrier, including high-bit dimensions and
    // unusual float encodings. Accepted data must also remain safe to process.
    std::mt19937 random(4020);
    const auto clean = encoded();
    for (int trial = 0; trial < 1000; ++trial) {
        auto bytes = clean;
        for (int change = 0; change < 1 + trial % 4; ++change) bytes[random() % bytes.size()] ^= uint8_t(1u << (random() % 8));
        std::array<night::Frame, 8> frames{}; frames[0] = frame; frames[1] = frame;
        std::array<std::vector<float>, 8> storage;
        if (night::decodeShading(bytes.data(), bytes.size(), 2, w, h, 0, frames, storage))
            assert(night::fuse(frames.data(), 2, cfg, fused.data(), cancelled, stats));
    }
    for (int mw : {128, 256}) for (bool calibrated : {false, true}) for (int shift : {-4, 4}) {
        const int mh = mw * 3 / 4;
        auto scene = [](int x, int y) {
            return .2f + .06f * std::sin(x * .071f) + .03f * std::cos(y * .11f) +
                .04f * std::sin((x + y) * .048f) + ((x > 37 && x < 55 && y > 24 && y < 61) ? .07f : 0);
        };
        std::array<std::vector<uint16_t>, 2> moving;
        std::vector<float> gains;
        for (int yy = 0; yy < 2; ++yy) for (int xx = 0; xx < 2; ++xx)
            for (int site = 0; site < 4; ++site) gains.push_back(1.f + .8f * xx + .5f * yy);
        std::array<night::Frame, 2> frames;
        for (int i = 0; i < 2; ++i) {
            moving[i].resize(mw * mh);
            for (int y = 0; y < mh; ++y) for (int x = 0; x < mw; ++x) {
                float gain = calibrated ? 1.f + .8f * x / (mw - 1) + .5f * y / (mh - 1) : 1.f;
                moving[i][y * mw + x] = uint16_t(std::round(4096 + 61439 * scene(x - i * shift, y - i * shift) / gain));
            }
            frames[i] = {reinterpret_cast<const uint8_t*>(moving[i].data()), moving[i].size() * 2,
                mw * 2, {4096,4096,4096,4096}, 65535, {}};
            if (calibrated) frames[i].shading = {gains.data(), gains.size(), 2, 2, 0, 0, mw, mh};
        }
        night::Config motion{mw,mh,0,0,1,{1,0,0,0,1,0,0,0,1}};
        std::vector<float> output(mw * mh);
        assert(night::fuse(frames.data(), 2, motion, output.data(), cancelled, stats));
        double mse = 0; int samples = 0;
        for (int y = 8; y < mh - 8; ++y) for (int x = 8; x < mw - 8; ++x) {
            double difference = output[y * mw + x] - scene(x, y); mse += difference * difference; ++samples;
        }
        // A noiseless reference needs no averaging; conservative registration
        // rejection is acceptable when it preserves that exact scene.
        assert(mse / samples < 1e-5 && stats.effectiveFrames >= 1);
        std::cout << "Noiseless shifted-scene preservation MSE " << mse / samples
                  << " effective " << stats.effectiveFrames << "\n";
        std::mt19937 sensorNoise(8042);
        std::normal_distribution<float> gaussian(0, std::sqrt(2e-6f));
        for (int i = 0; i < 2; ++i) {
            for (int y = 0; y < mh; ++y) for (int x = 0; x < mw; ++x) {
                float gain = calibrated ? 1.f + .8f * x / (mw - 1) + .5f * y / (mh - 1) : 1.f;
                moving[i][y * mw + x] = uint16_t(std::round(4096 + 61439 * (scene(x - i * shift, y - i * shift) / gain + gaussian(sensorNoise))));
            }
            for (auto& noise : frames[i].noise) noise = {0, 2e-6f};
        }
        assert(night::fuse(frames.data(), 2, motion, output.data(), cancelled, stats));
        mse = 0; double referenceMse = 0;
        for (int y = 8; y < mh - 8; ++y) for (int x = 8; x < mw - 8; ++x) {
            double difference = output[y * mw + x] - scene(x, y); mse += difference * difference;
            double gain = calibrated ? 1 + .8 * x / (mw - 1) + .5 * y / (mh - 1) : 1.;
            double reference = (double(moving[0][y * mw + x]) - 4096) / 61439 * gain;
            difference = reference - scene(x, y); referenceMse += difference * difference;
        }
        std::cout << "Noisy shifted-scene (width " << mw << ", shading " << calibrated << ", shift " << shift << ") MSE " << mse / samples << " reference " << referenceMse / samples
                  << " effective " << stats.effectiveFrames << std::endl;
        assert(mse < referenceMse * .85 && stats.effectiveFrames > 1.2);
    }
    std::cout << "Residual shading: all CFA/precision oracles, signed signal, geometry, saturation, cancellation, immutability, truncation and 1000 malformed carriers passed\n";
}
