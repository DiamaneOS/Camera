// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

int main() {
    constexpr int width = 256, height = 192;
    std::atomic_bool cancelled(false);
    std::mt19937 generator(8);
    std::normal_distribution<float> noise(0, .01f);
    std::vector<std::vector<uint16_t>> raw(8, std::vector<uint16_t>(width * height));
    for (auto& image : raw) for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        // Flat shadow above, a high-contrast edge and real fine texture below.
        float value = .1f;
        if (y >= height / 2) value += (x >= width / 2 ? .2f : 0) + .025f * std::cos(x * .35f);
        image[y * width + x] = uint16_t(std::round(64 + 4031 * (value + noise(generator))));
    }
    const auto original = raw;
    std::vector<night::Frame> frames;
    for (const auto& image : raw)
        frames.push_back({reinterpret_cast<const uint8_t*>(image.data()), image.size() * 2, width * 2,
                          {64,64,64,64},4095, {{{0,.0001f},{0,.0001f},{0,.0001f},{0,.0001f}}}});
    night::Config cfg{width,height,0,0,1,{1,0,0,0,1,0,0,0,1}};
    night::Stats stats;
    std::vector<uint32_t> before(width * height), after(before.size());
    assert(night::process(frames.data(),8,cfg,before.data(),cancelled,stats));
    cfg.spatialDenoise = true;
    assert(night::process(frames.data(),8,cfg,after.data(),cancelled,stats));
    auto green = [](uint32_t value) {return double((value >> 8) & 255);};
    auto flat = [&](const std::vector<uint32_t>& image) {
        double sum=0,squares=0;int count=0;
        for(int y=8;y<height/2-8;++y) for(int x=8;x<width-8;++x) {
            double value=green(image[y*width+x]);sum+=value;squares+=value*value;++count;
        }
        return std::array<double,2>{sum/count,std::sqrt(squares/count-std::pow(sum/count,2))};
    };
    auto a=flat(before),b=flat(after);
    assert(b[1] < a[1] * .8 && std::abs(a[0]-b[0]) < .3);
    double edgeBefore=0,edgeAfter=0;
    for(int y=height/2+8;y<height-8;++y) {
        edgeBefore+=green(before[y*width+width/2+6])-green(before[y*width+width/2-6]);
        edgeAfter+=green(after[y*width+width/2+6])-green(after[y*width+width/2-6]);
    }
    assert(std::abs(edgeAfter/edgeBefore-1)<.02);
    assert(raw==original);
    // Zero-noise metadata must bypass spatial shrinkage exactly.
    for(auto& frame:frames) frame.noise={};
    cfg.spatialDenoise=false;assert(night::process(frames.data(),8,cfg,before.data(),cancelled,stats));
    cfg.spatialDenoise=true;assert(night::process(frames.data(),8,cfg,after.data(),cancelled,stats));
    assert(before==after);
    // Minimum dimensions exercise multiple reflected DCT samples on every edge.
    std::vector<uint16_t> tiny(8*8,240);const auto tinyOriginal=tiny;
    night::Frame tinyFrame{reinterpret_cast<const uint8_t*>(tiny.data()),tiny.size()*2,16,{64,64,64,64},4095,
                          {{{0,.0001f},{0,.0001f},{0,.0001f},{0,.0001f}}}};
    night::Frame tinyFrames[2]={tinyFrame,tinyFrame};cfg.width=cfg.height=8;
    assert(night::process(tinyFrames,2,cfg,after.data(),cancelled,stats));
    for(int i=1;i<64;++i) assert(after[i]==after[0]);
    assert(tiny==tinyOriginal);
    cancelled=true;assert(!night::process(tinyFrames,2,cfg,after.data(),cancelled,stats));
    std::cout<<"Spatial flat-noise ratio "<<b[1]/a[1]<<", edge-contrast ratio "<<edgeAfter/edgeBefore
             <<"; tiny bounds, cancellation and original RAW passed\n";
}
