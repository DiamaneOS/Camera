// SPDX-License-Identifier: MIT
#include "jpegburst.h"
#include "nightfinish.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>
namespace {
float encode(float v) { return v <= .0031308f ? v * 12.92f : 1.055f * std::pow(v, 1 / 2.4f) - .055f; }
float decode(float v) { return v <= .04045f ? v / 12.92f : std::pow((v + .055f) / 1.055f, 2.4f); }
uint32_t rgb10(float r, float g, float b) {
    auto q = [](float v) { return uint32_t(std::lround(std::clamp(encode(std::clamp(v, 0.f, 1.f)), 0.f, 1.f) * 1023)); };
    return 0xc0000000u | q(r) << 20 | q(g) << 10 | q(b);
}
float channel(uint32_t p, int c) { return decode(float((p >> (16 - 8 * c)) & 255) / 255); }
}
int main() {
    std::atomic_bool stop(false);
    const int w = 512, h = 384;
    std::mt19937 random(7);
    std::normal_distribution<float> noise(0, .0004f);
    // A night scene: dark ground and sky, trees darker still in both halves, a few lit
    // windows, and the camera's constant
    // blue-heavy linear pedestal on every pixel.
    const float pedestal[3] = {.0006f, 0, .0045f};
    std::vector<uint32_t> night(size_t(w) * h);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        float base = (x / 48 + 2 * (y / 48)) % 5 == 0 ? .0012f : y < h / 3 ? .006f : .0025f, light = (x / 32 + y / 32) % 7 == 0 ? .25f : 0;
        float r = base * 1.1f + light, g = base + light, b = base * .9f + light * .8f;
        night[size_t(y) * w + x] = rgb10(r + pedestal[0] + noise(random), g + pedestal[1] + noise(random), b + pedestal[2] + noise(random));
    }
    auto pixels = night;
    night::BlackPedestal black;
    assert(night::finishJpeg(pixels.data(), w, h, 1, stop, &black));
    assert(black.applied);
    // The measured offsets are relative to the dark areas' luminance, so compare differences.
    assert(std::abs((black.blue - black.green) - (pedestal[2] - pedestal[1])) < .0008f);
    assert(std::abs((black.red - black.green) - (pedestal[0] - pedestal[1])) < .0008f);
    double sum[3] = {};
    int count = 0;
    for (int y = h / 2; y < h; ++y) for (int x = 0; x < w; ++x) {
        if ((x / 32 + y / 32) % 7 == 0 || (x / 32 + y / 32) % 7 == 1 || (x / 32 + y / 32) % 7 == 6) continue;
        for (int c = 0; c < 3; ++c) sum[c] += channel(pixels[size_t(y) * w + x], c);
        ++count;
    }
    double blueExcess = (sum[2] - sum[1]) / count;
    std::cout << "dark ground blue minus green after: " << blueExcess * 1000 << " x1000 (before " << pedestal[2] * 1000 - .25 << ")\n";
    assert(std::abs(blueExcess) < .0006);
    // A truly blue dark sky above a neutral dark ground is scene colour, not an offset:
    // the halves disagree, so nothing is removed and both keep their colours.
    for (int blue : {20, 10}) {
        std::vector<uint32_t> sky(size_t(w) * h);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            float v = decode((y < h / 2 ? 4.f : 12.f) / 255), b = decode((y < h / 2 ? float(blue) : 12.f) / 255);
            sky[size_t(y) * w + x] = rgb10(v + noise(random) * .1f, v + noise(random) * .1f, b + noise(random) * .1f);
        }
        assert(night::finishJpeg(sky.data(), w, h, 1, stop, &black));
        assert(!black.applied);
        uint32_t ground = sky[size_t(h - 20) * w + w / 2], upper = sky[size_t(20) * w + w / 2];
        assert(std::abs(int(ground & 255) - 12) <= 1 && std::abs(int((ground >> 16) & 255) - 12) <= 1);
        assert(std::abs(int(upper & 255) - blue) <= 1);
    }
    // A bright scene keeps its colours: no pedestal is measured.
    std::vector<uint32_t> bright(size_t(w) * h);
    for (size_t i = 0; i < bright.size(); ++i) bright[i] = rgb10(.3f, .25f, .35f);
    assert(night::finishJpeg(bright.data(), w, h, 1, stop, &black));
    assert(!black.applied);
    // Gain: full gain in the shadows, a monotone shoulder, white stays white and nothing clips below it.
    std::vector<uint32_t> ramp(size_t(w) * h);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        float v = std::pow(float(x) / (w - 1), 2.f);
        ramp[size_t(y) * w + x] = rgb10(v, v, v);
    }
    assert(night::finishJpeg(ramp.data(), w, h, 2, stop, &black));
    int row = h / 2;
    float previous = -1;
    for (int x = 0; x < w; ++x) {
        float v = std::pow(float(x) / (w - 1), 2.f), out = channel(ramp[size_t(row) * w + x], 1);
        assert(out >= previous - .002f);
        previous = out;
        if (v > .002f && v < .1f) assert(std::abs(out / v - 2) < .12f);
        if (x < w - 1) assert(((ramp[size_t(row) * w + x] >> 8) & 255) < 255 || v > .95f);
    }
    assert(((ramp[size_t(row) * w + w - 1] >> 8) & 255) == 255);
    for (float bad : {.5f, 4.5f, NAN}) assert(!night::finishJpeg(ramp.data(), w, h, bad, stop, &black));
    // The merger's high-precision output keeps the averaged code at ten bits.
    std::vector<uint32_t> frame(size_t(64) * 48, 0xff102030u), merged;
    jpeg_burst::Merger merger(64, 48, stop);
    assert(merger.add(frame.data(), frame.size()) && merger.add(frame.data(), frame.size()));
    jpeg_burst::Stats stats;
    assert(merger.finish(merged, stats, true));
    for (uint32_t p : merged) assert(p == (0xc0000000u | uint32_t(std::lround(16 * 1023. / 255)) << 20 | uint32_t(std::lround(32 * 1023. / 255)) << 10 | uint32_t(std::lround(48 * 1023. / 255))));
    std::cout << "JPEG finish pedestal, gain and precision checks passed\n";
}
