// SPDX-License-Identifier: MIT
#include "nightfinish.h"
#include <cassert>
#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <vector>
int main() {
    int w=128,h=96;std::atomic_bool stop(false);
    std::vector<uint32_t> p(w*h,0xff808080), original=p;
    assert(night::finish(p.data(),w,h,0,stop));assert(p==original);
    std::mt19937 rng(91);std::normal_distribution<float> noise(0,10);
    double before=0,after=0,meanBefore=0,meanAfter=0;
    for(auto& v:p){ int r=std::clamp(int(128+noise(rng)),0,255),b=std::clamp(int(128+noise(rng)),0,255);v=0xff000000|(r<<16)|(128<<8)|b;before+=(r-b)*(r-b);meanBefore+=(r+256+b)*.25; }
    assert(night::finish(p.data(),w,h,0,stop));
    for(auto v:p){int r=(v>>16)&255,g=(v>>8)&255,b=v&255;after+=(r-b)*(r-b);meanAfter+=(r+2*g+b)*.25;}
    assert(after<before*.35);assert(std::abs(meanAfter-meanBefore)/(w*h)<.1);
    // Correlated grey noise has no chroma: the finish must now reduce luminance
    // grain without moving mean brightness or erasing a coherent edge/texture.
    std::normal_distribution<float> greyNoise(0,10);
    std::vector<uint32_t> grey(w*h), edge(w*h), texture(w*h);
    double greyBefore=0,greyAfter=0,textureBefore=0,textureAfter=0;
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
        int i=y*w+x;
        auto pack=[](int value){return 0xff000000u|uint32_t(value)*0x10101u;};
        int value=std::clamp(int(std::round(128+greyNoise(rng))),0,255);
        grey[i]=pack(value);greyBefore+=(value-128)*(value-128);
        int base=x<w/2?55:200;
        edge[i]=pack(std::clamp(int(std::round(base+greyNoise(rng))),0,255));
        float wave=128+25*std::cos(x*6.2831853f/12);
        value=std::clamp(int(std::round(wave+greyNoise(rng))),0,255);
        texture[i]=pack(value);textureBefore+=(value-wave)*(value-wave);
    }
    assert(night::finish(grey.data(),w,h,0,stop));
    assert(night::finish(edge.data(),w,h,0,stop));
    assert(night::finish(texture.data(),w,h,0,stop));
    double left=0,right=0;
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
        int i=y*w+x;float value=float((grey[i]>>8)&255);
        greyAfter+=(value-128)*(value-128);
        float wave=128+25*std::cos(x*6.2831853f/12);
        value=float((texture[i]>>8)&255);textureAfter+=(value-wave)*(value-wave);
        if(x==w/2-1)left+=(edge[i]>>8)&255;
        if(x==w/2)right+=(edge[i]>>8)&255;
    }
    assert(greyAfter<greyBefore*.3);
    assert(textureAfter<textureBefore*.8);
    assert(right/h-left/h>135);
    // Demosaicing correlates colour noise across neighbouring pixels. A
    // single-pixel noise estimator must not leave coherent random colour blocks.
    std::vector<float> blocks((w/4)*(h/4));
    for(auto& value:blocks)value=noise(rng);
    std::vector<uint32_t> blotches(w*h);double colourBefore=0,colourAfter=0;
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){
        int d=int(std::round(blocks[(y/4)*(w/4)+x/4]));
        int r=std::clamp(128+d,0,255),b=std::clamp(128-d,0,255);
        blotches[y*w+x]=0xff000000u|(uint32_t(r)<<16)|(128u<<8)|uint32_t(b);
        colourBefore+=(r-b)*(r-b);
    }
    assert(night::finish(blotches.data(),w,h,0,stop));
    for(auto value:blotches){int d=int((value>>16)&255)-int(value&255);colourAfter+=d*d;}
    assert(colourAfter<colourBefore*.35);
    // Bright isolated chroma impulses should not be protected as coherent detail.
    // Thin coloured lines have opposite-neighbour support and must survive.
    std::vector<uint32_t> impulses(w*h),line(w*h);std::normal_distribution<float> colourNoise(0,12);
    for(int y=0;y<h;++y)for(int x=0;x<w;++x) {
        int r=std::clamp(int(64+colourNoise(rng)),0,255),g=std::clamp(int(64+colourNoise(rng)),0,255),b=std::clamp(int(64+colourNoise(rng)),0,255);
        impulses[y*w+x]=0xff000000u|(uint32_t(r)<<16)|(uint32_t(g)<<8)|uint32_t(b);
        if(x>=62&&x<66) {r=std::clamp(int(200+colourNoise(rng)),0,255);g=std::clamp(int(50+colourNoise(rng)),0,255);b=std::clamp(int(30+colourNoise(rng)),0,255);}
        line[y*w+x]=0xff000000u|(uint32_t(r)<<16)|(uint32_t(g)<<8)|uint32_t(b);
    }
    for(int y=16;y<h-8;y+=20)for(int x=16;x<w-8;x+=20)
        for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx)impulses[(y+dy)*w+x+dx]=0xffc020c0;
    assert(night::finish(impulses.data(),w,h,0,stop));assert(night::finish(line.data(),w,h,0,stop));
    double remaining=0,lineRed=0;int impulseCount=0;
    for(int y=16;y<h-8;y+=20)for(int x=16;x<w-8;x+=20) {
        auto value=impulses[y*w+x];remaining+=std::abs(int((value>>16)&255)+int(value&255)-2*int((value>>8)&255));++impulseCount;
    }
    for(int y=8;y<h-8;++y)lineRed+=(line[y*w+64]>>16)&255;
    assert(remaining/impulseCount<80);assert(lineRed/(h-16)>150);
    p.assign(w*h,0xff404040);assert(night::finish(p.data(),w,h,.4f,stop));assert(((p[0]>>8)&255)>95);
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) p[y*w+x]=x<w/2?0xffff0000:0xff0000ff;
    assert(night::finish(p.data(),w,h,0,stop));
    assert(((p[w/2-1]>>16)&255)>248 && (p[w/2-1]&255)<7);
    // Meter only the selected output crop: bright material outside 16:9 must not
    // darken the subject compared with the wider 4:3 metering region.
    std::vector<uint32_t> framed(160*160,0xff404040);
    for(int y=0;y<160;++y) if(y<32 || y>=128)
        for(int x=0;x<160;++x) framed[y*160+x]=0xffffffff;
    auto wide=framed, standard=framed;
    assert(night::finish(wide.data(),160,160,.4f,stop,1,16.f/9));
    assert(night::finish(standard.data(),160,160,.4f,stop,1,4.f/3));
    assert(int((wide[80*160+80]>>8)&255)>int((standard[80*160+80]>>8)&255)+20);
    assert(!night::finish(p.data(),w,h,0,stop,1,0));
    assert(!night::finish(p.data(),w,h,0,stop,1,std::numeric_limits<float>::quiet_NaN()));
    std::vector<uint32_t> steps(w*h);
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) steps[y*w+x]=x<w/3?0xff404040:x<2*w/3?0xff808080:0xffc0c0c0;
    float targets[3]={.08f,.55f,.9f};
    assert(night::finish(steps.data(),w,h,.55f,stop,1,4.f/3,targets));
    int shadow=(steps[w/6]>>8)&255, middleValue=(steps[w/2]>>8)&255, light=(steps[5*w/6]>>8)&255;
    assert(std::abs(shadow-20)<=2 && std::abs(middleValue-140)<=2 && std::abs(light-230)<=2);
    float invalidTargets[3]={.5f,.2f,.9f};
    assert(!night::finish(p.data(),w,h,0,stop,1,4.f/3,invalidTargets));
    std::vector<uint32_t> red(w*h,0xffdc1e14);
    assert(night::finish(red.data(),w,h,.75f,stop,1,4.f/3,targets));
    int rr=(red[0]>>16)&255,gg=(red[0]>>8)&255,bb=red[0]&255;
    assert(rr==255 && std::abs(gg-35)<=2 && std::abs(bb-23)<=2);
    // Similar noisy neighborhoods should combine without flattening repeated edges.
    std::vector<uint32_t> patterned(w*h);
    std::normal_distribution<float> patchNoise(0,8);
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
        int base=(x/16)%2?100:70;
        int value=std::clamp(int(std::round(base+patchNoise(rng))),0,255);
        patterned[y*w+x]=0xff000000|(value<<16)|(value<<8)|value;
    }
    double errorBefore=0,errorAfter=0,dark=0,lightValue=0;int darkCount=0,lightCount=0;
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {int target=(x/16)%2?100:70;double d=int((patterned[y*w+x]>>8)&255)-target;errorBefore+=d*d;}
    assert(night::finish(patterned.data(),w,h,0,stop));
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
        int target=(x/16)%2?100:70;int value=(patterned[y*w+x]>>8)&255;double d=value-target;errorAfter+=d*d;
        if(x%16>3 && x%16<12) {if(target==70) {dark+=value;darkCount++;} else {lightValue+=value;lightCount++;}}
    }
    assert(errorAfter<errorBefore*.65);
    assert(lightValue/lightCount-dark/darkCount>25);
    // JPEG noise varies spatially after ISP tone mapping. Reduce a noisy flat
    // half while retaining low-noise stripes and the boundary between halves.
    const int jw=256,jh=192;
    std::vector<uint32_t> varying(jw*jh),truth(jw*jh);
    std::normal_distribution<float> unit(0,1);
    double jpegBefore=0,jpegAfter=0,quietError=0;int quietCount=0;
    for(int y=0;y<jh;++y) for(int x=0;x<jw;++x) {
        int base=x<jw/2?70:((x/16)%2?150:190);
        int value=std::clamp(int(std::round(base+unit(rng)*(x<jw/2?18:2))),0,255);
        varying[y*jw+x]=0xff000000u|uint32_t(value)*0x10101u;
        truth[y*jw+x]=uint32_t(base);
        if(x>8 && x<jw/2-8 && y>8 && y<jh-8) {jpegBefore+=(value-base)*(value-base);}
    }
    assert(night::finish(varying.data(),jw,jh,0,stop,1,4.f/3,nullptr,true));
    for(int y=8;y<jh-8;++y) for(int x=8;x<jw-8;++x) {
        int value=(varying[y*jw+x]>>8)&255,base=truth[y*jw+x];
        if(x<jw/2-8)jpegAfter+=(value-base)*(value-base);
        if(x>jw/2+8 && x%16>3 && x%16<12) {quietError+=(value-base)*(value-base);++quietCount;}
    }
    assert(jpegAfter<jpegBefore*.35);assert(quietError/quietCount<6);
    std::vector<uint32_t> small(8*8,0xff808080u),smallOriginal=small;
    assert(night::finish(small.data(),8,8,0,stop,1,4.f/3,nullptr,true));assert(small==smallOriginal);
    auto preCancelled=varying;std::atomic_bool jpegStop(true);
    assert(!night::finish(varying.data(),jw,jh,0,jpegStop,1,4.f/3,nullptr,true));assert(varying==preCancelled);
    std::cout<<"Spatial JPEG noise ratio "<<jpegAfter/jpegBefore<<"; quiet-region MSE "<<quietError/quietCount<<"\n";
    stop=true;assert(!night::finish(p.data(),w,h,0,stop));
    assert(!night::finish(p.data(),w,h,2,stop));
    std::cout<<"Night chroma variance ratio "<<after/before<<"; luminance preserved\n";
}
