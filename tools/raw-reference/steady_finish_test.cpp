// SPDX-License-Identifier: MIT
#include "nightfinish.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>
void checkFinish(bool highPrecision) {
    std::atomic_bool stop(false);
    auto pack=[&](int v) {
        if (!highPrecision) return 0xff000000u | uint32_t(v)*0x10101u;
        uint32_t q=uint32_t(std::round(v*1023.0/255));
        return (q<<20)|(q<<10)|q;
    };
    for(int w : {8,10,128}) for(int h : {8,12,96}) {
        std::vector<uint32_t> flat(w*h,pack(96)), original=flat;
        assert(night::finish(flat.data(),w,h,0,stop,1,4.f/3,nullptr,false,highPrecision,true));
        assert(flat==std::vector<uint32_t>(w*h,0xff606060u));
        flat=original;
        std::atomic_bool cancelled(true);
        assert(!night::finish(flat.data(),w,h,0,cancelled,1,4.f/3,nullptr,false,highPrecision,true));
        assert(flat==original);
    }
    const int w=256,h=192;
    std::mt19937 rng(520);
    std::normal_distribution<float> noise(0,4.8);
    std::vector<uint32_t> flat(w*h),texture(w*h);
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
        flat[y*w+x]=pack(std::clamp(int(std::round(80+noise(rng))),0,255));
        texture[y*w+x]=pack(std::clamp(int(std::round(80+25*std::cos(x*6.283185307/12)+noise(rng))),0,255));
    }
    auto baseline=flat, baselineTexture=texture;
    assert(night::finish(baseline.data(),w,h,0,stop,1,4.f/3,nullptr,false,highPrecision,false));
    assert(night::finish(flat.data(),w,h,0,stop,1,4.f/3,nullptr,false,highPrecision,true));
    assert(night::finish(baselineTexture.data(),w,h,0,stop,1,4.f/3,nullptr,false,highPrecision,false));
    assert(night::finish(texture.data(),w,h,0,stop,1,4.f/3,nullptr,false,highPrecision,true));
    double oldError=0,error=0,oldContrast=0,contrast=0;
    for(int y=16;y<h-16;++y) for(int x=16;x<w-16;++x) {
        int i=y*w+x;
        double a=int((baseline[i]>>8)&255)-80,b=int((flat[i]>>8)&255)-80;
        oldError+=a*a;error+=b*b;
        oldContrast+=int((baselineTexture[i]>>8)&255)*std::cos(x*6.283185307/12);
        contrast+=int((texture[i]>>8)&255)*std::cos(x*6.283185307/12);
    }
    std::cout<<"Measured flat ratio "<<error/oldError<<", texture "<<contrast/oldContrast<<std::endl;
    assert(error<oldError*.8);
    assert(contrast>=oldContrast*.98);
    std::cout<<"Gated finish flat MSE ratio "<<error/oldError<<", strong texture contrast "<<contrast/oldContrast<<"\n";
}
int main() {
    checkFinish(false);
    checkFinish(true);
}
