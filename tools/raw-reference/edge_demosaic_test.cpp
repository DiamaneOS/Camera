// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include <algorithm>
#include <atomic>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <vector>

int main() {
    constexpr int patterns[4][4]={{0,1,1,2},{1,0,2,1},{1,2,0,1},{2,1,1,0}};
    constexpr int w=64,h=48;
    std::atomic_bool cancelled(false);
    std::vector<float> raw(w*h);std::vector<uint32_t> image(w*h);
    auto scene=[](int channel,int x,int y) {
        return channel==0?.10f+.05f*x/(w-1):channel==1?.15f+.03f*y/(h-1):.08f+.02f*(x+y)/(w+h-2);
    };
    auto gamma=[](float v) {return v<=.0031308f?12.92f*v:1.055f*std::pow(v,1.f/2.4f)-.055f;};
    double worst=0;
    for(int cfa=0;cfa<4;++cfa)for(bool precise:{false,true}) {
        for(int y=0;y<h;++y)for(int x=0;x<w;++x)raw[y*w+x]=scene(patterns[cfa][(y&1)*2+(x&1)],x,y);
        const auto original=raw;
        night::Config cfg{w,h,cfa,0,1,{1,0,0,0,1,0,0,0,1}};cfg.highPrecision=precise;cfg.edgeAwareDemosaic=true;
        assert(night::develop(raw.data(),cfg,image.data(),cancelled));assert(raw==original);
        const int maximum=precise?1023:255;
        for(int y=3;y<h-3;++y)for(int x=3;x<w-3;++x) {
            float r=scene(0,x,y),g=scene(1,x,y),b=scene(2,x,y),l=.2126f*r+.7152f*g+.0722f*b;
            for(int c=0;c<3;++c) {
                int actual=(image[y*w+x]>>((2-c)*(precise?10:8)))&maximum;
                int expected=int(std::round(gamma(scene(c,x,y)/(1+l))*maximum));
                worst=std::max(worst,double(std::abs(actual-expected)));assert(std::abs(actual-expected)<=1);
            }
        }
        // Signed below-black channels remain valid until colour conversion.
        std::fill(raw.begin(),raw.end(),-.01f);assert(night::develop(raw.data(),cfg,image.data(),cancelled));
        for(auto p:image)assert((p&(precise?0x3fffffffu:0xffffffu))==0);
        raw[5]=std::numeric_limits<float>::quiet_NaN();assert(!night::develop(raw.data(),cfg,image.data(),cancelled));
        raw[5]=0;cancelled=true;assert(!night::develop(raw.data(),cfg,image.data(),cancelled));cancelled=false;
        cfg.width=cfg.height=8;raw.resize(64);image.resize(64);
        for(int y=0;y<8;++y)for(int x=0;x<8;++x)raw[y*8+x]=scene(patterns[cfa][(y&1)*2+(x&1)],0,0);
        assert(night::develop(raw.data(),cfg,image.data(),cancelled));for(auto p:image)assert(p==image[0]);
        raw.resize(w*h);image.resize(w*h);
    }
    std::cout<<"Edge development all CFA/precision modes, affine RGB oracle max error "<<worst<<" code, signed samples, tiny borders, cancellation and original immutability passed\n";
}
