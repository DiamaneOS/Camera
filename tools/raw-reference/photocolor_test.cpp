// SPDX-License-Identifier: MIT
#include "photocolor.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <vector>
uint32_t corrected(uint32_t p) {
    double c[3]={double((p>>16)&255),double((p>>8)&255),double(p&255)};
    double m[3][3]={{.7,.15,.15},{-.15,1.1,.05},{-.15,.05,1.1}};
    uint32_t v=0xff000000;
    for(int i=0;i<3;i++) { double sum=0;for(int j=0;j<3;j++)sum+=m[i][j]*c[j];v|=uint32_t(std::clamp(std::round(sum),0.,255.))<<((2-i)*8); }
    return v;
}
int main() {
    const int w=192,h=144;
    std::atomic_bool cancel(false);photo::ColorStats stats;
    std::vector<uint32_t> source(w*h),preview(96*96);
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
        int r=60+x*110/w,g=60+y*100/h,b=70+((x/24+y/18)%2)*70;
        source[y*w+x]=0xff000000|(r<<16)|(g<<8)|b;
    }
    for(int rotation:{0,90,180,270}) {
        for(int y=0;y<96;y++) for(int x=0;x<96;x++) {
            double u=(x+.5)/96,v=(y+.5)/96,sx=u,sy=v;
            if(rotation==90){sx=v;sy=1-u;}if(rotation==180){sx=1-u;sy=1-v;}if(rotation==270){sx=1-v;sy=u;}
            preview[y*96+x]=corrected(source[std::min(h-1,int(sy*h))*w+std::min(w-1,int(sx*w))]);
        }
        auto out=source;
        assert(photo::match(out.data(),w,h,preview.data(),rotation,1,4.f/3,cancel,stats));
        assert(stats.applied && stats.after<stats.before*.4 && stats.inliers>.9);
        for(int i:{w*30+50,w*70+100,w*100+150}) for(int c=0;c<3;c++) {
            int shift=(2-c)*8;assert(std::abs(int((out[i]>>shift)&255)-int((corrected(source[i])>>shift)&255))<=4);
        }
    }
    // A real white-balance error needs unequal channel responses, including in shadows.
    // Per-row neutral bounds used to reject this valid correction outright.
    std::vector<uint32_t> tinted(w*h);
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
        int r=25+x*45/w,g=20+y*40/h,b=30+((x/24+y/18)%2)*30;
        tinted[y*w+x]=0xff000000|(r<<16)|(g<<8)|b;
    }
    for(int y=0;y<96;y++) for(int x=0;x<96;x++) {
        uint32_t p=tinted[std::min(h-1,int((y+.5)*h/96))*w+std::min(w-1,int((x+.5)*w/96))];
        int r=std::round(((p>>16)&255)*.6),g=std::round(((p>>8)&255)*1.2),b=std::round((p&255)*.7);
        preview[y*96+x]=0xff000000|(r<<16)|(g<<8)|b;
    }
    assert(photo::match(tinted.data(),w,h,preview.data(),0,1,4.f/3,cancel,stats));
    assert(stats.applied && stats.after<stats.before*.2 && stats.inliers>.9);
    // A fit just beyond a coefficient limit can be safely contracted toward
    // identity, retaining all bounds instead of discarding a useful correction.
    for(int y=0;y<96;y++) for(int x=0;x<96;x++) {
        uint32_t p=source[std::min(h-1,int((y+.5)*h/96))*w+std::min(w-1,int((x+.5)*w/96))];
        double c[3]={double((p>>16)&255),double((p>>8)&255),double(p&255)};
        const double m[3][3]={{2.05,-.20,-.98},{.128,.809,.037},{-.134,-.051,1.165}};
        uint32_t v=0xff000000;
        for(int row=0;row<3;row++) {
            double value=0;for(int column=0;column<3;column++)value+=m[row][column]*c[column];
            v|=uint32_t(std::clamp(std::round(value),0.,255.))<<((2-row)*8);
        }
        preview[y*96+x]=v;
    }
    auto limited=source;
    assert(photo::match(limited.data(),w,h,preview.data(),0,1,4.f/3,cancel,stats));
    assert(stats.applied && stats.after<stats.before*.4 && stats.inliers>.8);
    // An unrelated scene must not dictate a color change.
    auto flat=std::vector<uint32_t>(w*h,0xff808080),unchanged=flat;
    for(int y=0;y<96;y++) for(int x=0;x<96;x++) preview[y*96+x]=(x/8+y/8)%2?0xffc83232:0xff3232c8;
    assert(photo::match(flat.data(),w,h,preview.data(),0,1,4.f/3,cancel,stats));assert(!stats.applied && flat==unchanged);
    cancel=true;assert(!photo::match(flat.data(),w,h,preview.data(),0,1,4.f/3,cancel,stats));
    cancel=false;assert(!photo::match(flat.data(),w,h,preview.data(),45,1,4.f/3,cancel,stats));
    assert(!photo::match(flat.data(),w,h,preview.data(),0,std::numeric_limits<float>::quiet_NaN(),4.f/3,cancel,stats));
    std::cout<<"Photo color fit, orientation, mismatch and cancellation checks passed\n";
}
