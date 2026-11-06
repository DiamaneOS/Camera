// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

int main() {
    constexpr int width=128,height=96;
    std::atomic_bool cancel(false);
    std::mt19937 rng(451);
    std::normal_distribution<float> noise(0,.01f);
    std::vector<std::vector<uint16_t>> raw(8,std::vector<uint16_t>(width*height));
    for(auto& frame:raw)for(int y=0;y<height;++y)for(int x=0;x<width;++x) {
        float signal=.1f;
        if(y>=height/2)signal+=(x>=width/2?.2f:0)+.025f*std::cos(x*.35f);
        frame[y*width+x]=uint16_t(std::clamp(std::lround(64+4031*(signal+noise(rng))),0L,4095L));
    }
    const auto original=raw;
    std::vector<night::Frame> frames;
    for(const auto& data:raw)frames.push_back({reinterpret_cast<const uint8_t*>(data.data()),data.size()*2,width*2,
        {64,64,64,64},4095,{{{0,.0001f},{0,.0001f},{0,.0001f},{0,.0001f}}}});
    std::vector<uint32_t> old(width*height),shaped(old.size());
    double worstNoise=0,worstEdge=0;
    for(int cfa=0;cfa<4;++cfa)for(bool precise:{false,true})for(bool edge:{false,true}) {
        night::Config cfg{width,height,cfa,0,1,{1,0,0,0,1,0,0,0,1}};
        cfg.highPrecision=precise;cfg.edgeAwareDemosaic=edge;cfg.spatialDenoise=true;
        night::Stats stats;
        assert(night::process(frames.data(),8,cfg,old.data(),cancel,stats));
        cfg.frequencyDenoise=true;
        assert(night::process(frames.data(),8,cfg,shaped.data(),cancel,stats));
        auto green=[&](uint32_t p){return precise?double((p>>10)&1023)*255/1023:double((p>>8)&255);};
        auto flat=[&](const auto& pixels){double sum=0,squares=0;int n=0;
            for(int y=8;y<height/2-8;++y)for(int x=8;x<width-8;++x){double v=green(pixels[y*width+x]);sum+=v;squares+=v*v;++n;}
            return std::array<double,2>{sum/n,std::sqrt(std::max(0.,squares/n-(sum/n)*(sum/n)))};};
        auto a=flat(old),b=flat(shaped);assert(std::abs(a[0]-b[0])<.3);assert(b[1]<=a[1]*1.02);
        double before=0,after=0;
        for(int y=height/2+8;y<height-8;++y){before+=green(old[y*width+width/2+6])-green(old[y*width+width/2-6]);after+=green(shaped[y*width+width/2+6])-green(shaped[y*width+width/2-6]);}
        assert(before>0 && std::abs(after/before-1)<.02);
        worstNoise=std::max(worstNoise,b[1]/a[1]);worstEdge=std::max(worstEdge,std::abs(after/before-1));
        assert(raw==original);
        // Unknown/zero noise must leave the original deterministic development intact.
        auto zero=frames;for(auto& frame:zero)frame.noise={};
        cfg.frequencyDenoise=false;assert(night::process(zero.data(),8,cfg,old.data(),cancel,stats));
        cfg.frequencyDenoise=true;assert(night::process(zero.data(),8,cfg,shaped.data(),cancel,stats));assert(old==shaped);
    }
    std::vector<uint16_t> tiny(64,100);night::Frame frame{reinterpret_cast<const uint8_t*>(tiny.data()),128,16,{64,64,64,64},4095,
        {{{0,.0001f},{0,.0001f},{0,.0001f},{0,.0001f}}}};night::Frame pair[2]={frame,frame};night::Config cfg{8,8,0,0,1,{1,0,0,0,1,0,0,0,1}};
    cfg.spatialDenoise=cfg.frequencyDenoise=true;night::Stats stats;
    assert(night::process(pair,2,cfg,shaped.data(),cancel,stats));for(int i=1;i<64;++i)assert(shaped[i]==shaped[0]);
    cancel=true;assert(!night::process(pair,2,cfg,shaped.data(),cancel,stats));assert(raw==original);
    std::cout<<"All CFA/precision/interpolation combinations passed; worst flat-noise ratio "<<worstNoise
             <<", strongest edge relative difference "<<worstEdge<<"; zero-noise bypass, tiny bounds and cancellation passed\n";
}
