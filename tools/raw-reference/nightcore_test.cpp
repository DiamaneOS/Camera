// SPDX-License-Identifier: MIT
#include "../../app/src/main/cpp/nightcore.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <vector>

namespace {
constexpr int width = 256, height = 192;
night::Config config() { return {width, height, 0, 0, 1, {1, 0, 0, 0, 1, 0, 0, 0, 1}}; }
night::Frame frame(const std::vector<uint16_t>& raw) {
    return {reinterpret_cast<const uint8_t*>(raw.data()), raw.size() * 2, width * 2,
            {64, 64, 64, 64}, 1023, {{{0, .0001f}, {0, .0001f}, {0, .0001f}, {0, .0001f}}}};
}
float channel(uint32_t c) { return float((c >> 8) & 255); }
double noise(const std::vector<uint32_t>& pixels) {
    double sum = 0, squares = 0;
    int count = 0;
    for (int y = 100; y < 160; ++y) for (int x = 50; x < 200; ++x) {
        float value = channel(pixels[size_t(y) * width + x]); sum += value; squares += value * value; ++count;
    }
    return std::sqrt(squares / count - std::pow(sum / count, 2));
}
}

int main() {
    std::atomic_bool cancelled{false};
    std::vector<std::vector<uint16_t>> raw(8, std::vector<uint16_t>(size_t(width) * height));
    std::mt19937 random(1);
    std::normal_distribution<float> distribution(0, .01f);
    for (auto& image : raw) for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        float signal = .2f + (y < 70 ? .15f * ((x / 11 + y / 13) % 2) : 0);
        image[size_t(y) * width + x] = uint16_t(std::round(64 + 959 * (signal + distribution(random))));
    }
    std::vector<night::Frame> frames;
    for (const auto& image : raw) frames.push_back(frame(image));
    auto cfg = config();
    std::vector<uint32_t> output(size_t(width) * height), reference(output.size());
    night::Stats stats;
    auto duplicate = std::array<night::Frame, 2>{frames[0], frames[0]};
    assert(night::process(duplicate.data(), 2, cfg, reference.data(), cancelled, stats));
    const auto untouchedRaw = raw;
    assert(night::process(frames.data(), 8, cfg, output.data(), cancelled, stats));
    assert(raw == untouchedRaw);
    double ratio = noise(output) / noise(reference);
    std::cout << "eight-frame noise ratio: " << ratio << ", effective frames: " << stats.effectiveFrames << '\n';
    assert(ratio < .6 && stats.effectiveFrames > 5);
    assert((output[1000] & 0xff000000) == 0xff000000);
    // Isolated saturated defects must not become coloured sparkles. Supported
    // white points and thin bright lines retain their neighbouring signal.
    std::vector<uint16_t> defectRaw(width*height,100);
    int centre=(height/2)*width+width/2;defectRaw[centre]=1023;
    std::array<night::Frame,2> defect{frame(defectRaw),frame(defectRaw)};
    assert(night::process(defect.data(),2,cfg,output.data(),cancelled,stats));
    assert(std::abs(channel(output[centre])-channel(output[centre+20]))<3);
    defectRaw[centre+1]=1023;defectRaw[centre+width]=1023;defectRaw[centre+width+1]=1023;
    assert(night::process(defect.data(),2,cfg,output.data(),cancelled,stats));
    assert(channel(output[centre])>150);
    defectRaw.assign(width*height,100);
    for(int y=0;y<height;++y)defectRaw[y*width+width/2]=1023;
    defect={frame(defectRaw),frame(defectRaw)};
    assert(night::process(defect.data(),2,cfg,output.data(),cancelled,stats));
    assert(channel(output[centre])>100);
    // A calibrated periodic RAW-site artifact must not survive as a grid.
    // Constant samples and a supported edge stay unchanged away from that phase.
    std::vector<uint16_t> gridRaw(width*height,240);
    for(int y=2;y<height;y+=4) for(int x=6;x<width;x+=8) gridRaw[y*width+x]=280;
    std::array<night::Frame,2> periodic{frame(gridRaw),frame(gridRaw)};
    for(auto& f:periodic) f.periodicGrid={8,4,6,2};
    assert(night::process(periodic.data(),2,cfg,output.data(),cancelled,stats));
    float lo=255,hi=0;
    for(int y=8;y<height-8;++y) for(int x=8;x<width-8;++x) {
        float v=channel(output[y*width+x]);lo=std::min(lo,v);hi=std::max(hi,v);
    }
    assert(hi-lo<1);
    periodic[0].periodicGrid={2,4,0,2};
    assert(!night::process(periodic.data(),2,cfg,output.data(),cancelled,stats));
    periodic[0].periodicGrid={8,4,8,2};
    assert(!night::process(periodic.data(),2,cfg,output.data(),cancelled,stats));
    cancelled.store(true);
    assert(!night::process(frames.data(), 8, cfg, output.data(), cancelled, stats));
    cancelled.store(false);
    assert(!night::process(nullptr, 8, cfg, output.data(), cancelled, stats));
    assert(!night::process(frames.data(), 9, cfg, output.data(), cancelled, stats));
    auto invalid = cfg; invalid.width = 257;
    assert(!night::process(frames.data(), 8, invalid, output.data(), cancelled, stats));
    invalid = cfg; invalid.color[0] = std::numeric_limits<float>::quiet_NaN();
    assert(!night::process(frames.data(), 8, invalid, output.data(), cancelled, stats));
    auto shortFrame = frames; shortFrame[0].bytes = 4;
    assert(!night::process(shortFrame.data(), 8, cfg, output.data(), cancelled, stats));
    auto black = frames; black[0].black[0] = 1023;
    assert(!night::process(black.data(), 8, cfg, output.data(), cancelled, stats));
    auto tinyWhite = frames; tinyWhite[0].white = 1e-30f; tinyWhite[0].black = {0,0,0,0};
    assert(!night::process(tinyWhite.data(), 8, cfg, output.data(), cancelled, stats));
    auto giantWhite = frames; giantWhite[0].white = std::numeric_limits<float>::max();
    assert(!night::process(giantWhite.data(), 8, cfg, output.data(), cancelled, stats));
    auto negativeBlack = frames; negativeBlack[0].black[0] = -1;
    assert(!night::process(negativeBlack.data(), 8, cfg, output.data(), cancelled, stats));
    auto giantNoise = frames; giantNoise[0].noise[0].slope = std::numeric_limits<float>::max();
    assert(!night::process(giantNoise.data(), 8, cfg, output.data(), cancelled, stats));
    std::cout << "native noise, cancellation and input-boundary checks passed\n";
}
