// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <vector>
int main() {
    constexpr int w=64,h=48;
    std::atomic_bool stop(false);
    std::vector<uint16_t> raw(w*h);
    std::vector<float> linear(w*h);
    for(int y=0;y<h;++y)for(int x=0;x<w;++x) {
        int i=y*w+x;raw[i]=uint16_t(320+(x*7+y*11)%300);
        linear[i]=(float(raw[i])-512)/511;
    }
    const auto original=raw;const auto originalLinear=linear;
    night::Frame frame{reinterpret_cast<const uint8_t*>(raw.data()),raw.size()*2,w*2,{512,512,512,512},1023,{}};
    std::array<night::Frame,2> duplicated{frame,frame};
    night::Config cfg{w,h,0,0,1,{1,0,0,0,1,0,0,0,1}};night::Stats stats;
    std::vector<uint32_t> single(w*h),duplicate(w*h),developed(w*h);
    for(int cfa=0;cfa<4;++cfa)for(bool precise:{false,true}) {
        cfg.cfa=cfa;cfg.highPrecision=precise;
        assert(night::process(&frame,1,cfg,single.data(),stop,stats));
        assert(stats.inputFrames==1 && stats.effectiveFrames==1);
        std::vector<float> fused(w*h);
        assert(night::fuse(&frame,1,cfg,fused.data(),stop,stats));
        assert(fused==originalLinear);
        assert(night::process(duplicated.data(),2,cfg,duplicate.data(),stop,stats));
        assert(night::develop(linear.data(),cfg,developed.data(),stop));
        assert(single==duplicate && single==developed);
    }
    assert(raw==original && linear==originalLinear);
    // Keep fractional sensor codes: integer RAW re-encoding would erase this signal.
    cfg.cfa=0;cfg.highPrecision=true;std::fill(linear.begin(),linear.end(),.0201f);
    assert(night::develop(linear.data(),cfg,single.data(),stop));
    std::fill(linear.begin(),linear.end(),.0204f);
    assert(night::develop(linear.data(),cfg,developed.data(),stop));
    assert(single!=developed);
    for(float invalid:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),65536.f,-65536.f}) {
        linear[17]=invalid;assert(!night::develop(linear.data(),cfg,developed.data(),stop));
    }
    linear[17]=0;stop=true;
    assert(!night::develop(linear.data(),cfg,developed.data(),stop));
    assert(!night::process(&frame,1,cfg,developed.data(),stop,stats));
    stop=false;
    assert(!night::develop(nullptr,cfg,developed.data(),stop));
    assert(!night::develop(linear.data(),cfg,nullptr,stop));
    assert(!night::process(&frame,0,cfg,developed.data(),stop,stats));
    cfg.reference=1;assert(!night::process(&frame,1,cfg,developed.data(),stop,stats));
    cfg.reference=0;cfg.width=8192;cfg.height=8192;
    assert(!night::develop(linear.data(),cfg,developed.data(),stop));
    cfg.width=w;cfg.height=h;cfg.color[0]=std::numeric_limits<float>::infinity();
    assert(!night::develop(linear.data(),cfg,developed.data(),stop));
    std::cout<<"Single-frame and float Bayer development: all CFA/precision modes, fractional signal, original immutability, cancellation and bounds passed\n";
}
