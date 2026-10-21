// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include "nightfinish.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <set>
#include <vector>
int main() {
    constexpr int w=256,h=192;
    std::atomic_bool stop(false);
    std::vector<uint16_t> raw(w*h,240); const auto original=raw;
    night::Frame frame{reinterpret_cast<const uint8_t*>(raw.data()),raw.size()*2,w*2,{64,64,64,64},4095,{}};
    night::Frame frames[2]={frame,frame};
    night::Config cfg{w,h,0,0,1,{1,0,0,0,1,0,0,0,1}};night::Stats stats;
    std::vector<uint32_t> old(w*h),precise(w*h);
    assert(night::process(frames,2,cfg,old.data(),stop,stats));
    cfg.highPrecision=true;assert(night::process(frames,2,cfg,precise.data(),stop,stats));
    for(auto p:precise) assert(p>>30==3);
    assert(night::finish(old.data(),w,h,0,stop));
    assert(night::finish(precise.data(),w,h,0,stop,1,4.f/3,nullptr,false,true));
    for(int i=0;i<w*h;++i) for(int shift:{0,8,16})
        assert(std::abs(int((old[i]>>shift)&255)-int((precise[i]>>shift)&255))<=1);
    assert(raw==original);
    // A dark fine ramp must retain fractional code values until the final
    // brightness curve; quantizing before that curve produces visible steps.
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
        unsigned v=unsigned(x/4),low=unsigned(std::round(v*255./1023));
        precise[y*w+x]=0xc0000000u|(v<<20)|(v<<10)|v;
        old[y*w+x]=0xff000000u|(low<<16)|(low<<8)|low;
    }
    assert(night::finish(old.data(),w,h,.3f,stop));
    assert(night::finish(precise.data(),w,h,.3f,stop,1,4.f/3,nullptr,false,true));
    std::set<unsigned> oldLevels,preciseLevels;
    for(int x=8;x<w-8;++x) {oldLevels.insert(old[h/2*w+x]&255);preciseLevels.insert(precise[h/2*w+x]&255);}
    assert(preciseLevels.size()>oldLevels.size()*1.5);
    for(auto p:precise) assert(p>>24==255);
    std::cout<<"Dark-ramp output levels: old="<<oldLevels.size()<<", precise="<<preciseLevels.size()<<"; original RAW unchanged\n";
}
