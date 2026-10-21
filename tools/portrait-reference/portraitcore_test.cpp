// SPDX-License-Identifier: MIT
#include "portraitcore.h"
#include <cassert>
#include <algorithm>
#include <limits>
#include <cmath>
#include <iostream>
#include <vector>
int main() {
    const int w=96,h=64;
    std::vector<uint32_t> image(w*h,0xff204080), out(w*h);
    std::vector<float> mask(w*h,1);
    std::atomic_bool cancel(false);
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel));
    assert(image==out);
    std::fill(mask.begin(),mask.end(),0);
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel));
    assert(image==out);
    for(int y=16;y<48;++y) for(int x=32;x<64;++x){ image[y*w+x]=0xffff0000;mask[y*w+x]=1; }
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel));
    assert(out[32*w+48]==0xffff0000);
    assert(((out[32*w+20]>>16)&255)<45); // foreground must not bleed into the background
    std::vector<float> depth(w*h);
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
        int grey=(x/2)%2?192:64;
        image[y*w+x]=0xff000000|(grey<<16)|(grey<<8)|grey;
        mask[y*w+x]=x<16?1:0;
        depth[y*w+x]=x<32?7:x<64?4:2;
    }
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel,depth.data(),w,h,.08f,.5f));
    // The support plane at the subject distance stays sharp; a remote plane blurs.
    assert(out[32*w+20]==image[32*w+20]);
    double before=0,after=0;
    for(int y=20;y<44;y++) for(int x=72;x<88;x++) {
        before+=std::pow(int(image[y*w+x]&255)-128,2);after+=std::pow(int(out[y*w+x]&255)-128,2);
    }
    assert(after<before*.15);
    // A sleeve whose matte is missed but whose depth belongs to the selected
    // subject stays sharp, including a small depth variation across the body.
    for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
        int grey=(x/2)%2?192:64;
        image[y*w+x]=0xff000000u|uint32_t(grey)*0x10101u;
        mask[y*w+x]=x<16?1:0;depth[y*w+x]=x<16?7:x<32?7.2f:2;
    }
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel,depth.data(),w,h,.08f,.5f));
    assert(out[32*w+20]==image[32*w+20]);
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,0,out.data(),cancel,depth.data(),w,h,.08f,.5f));
    assert(out==image);
    // A selected face and torso can span a broad inverse-depth interval. A
    // missed torso patch and an outstretched nearer arm both remain sharp.
    for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
        mask[y*w+x]=x<16?1:0;
        depth[y*w+x]=x<8?7:x<24?5:x<32?8:2;
    }
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel,depth.data(),w,h,.04f,.5f));
    assert(out[32*w+20]==image[32*w+20] && out[32*w+28]==image[32*w+28]);
    // A constant/uninformative depth estimate falls back to the existing renderer.
    std::fill(depth.begin(),depth.end(),2);std::vector<uint32_t> fallback(w*h);
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel,depth.data(),w,h,.08f,.5f));
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,fallback.data(),cancel));assert(out==fallback);
    depth[0]=-1;assert(!portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel,depth.data(),w,h,.08f,.5f));
    depth[0]=2;assert(!portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel,depth.data(),1,h,.08f,.5f));
    // An additional sharp subject must not leave a red halo in the far plane.
    for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
        bool other=x>=40&&x<56&&y>=16&&y<48;
        image[y*w+x]=other?0xfff02020:0xff202060;mask[y*w+x]=x<16?1:0;depth[y*w+x]=other||x<16?7:2;
    }
    assert(portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel,depth.data(),w,h,.08f,.5f));
    assert(((out[32*w+30]>>16)&255)<45);
    cancel=true;
    assert(!portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel));
    cancel=false;mask[0]=2;
    assert(!portrait::render(image.data(),w,h,mask.data(),w,h,8,out.data(),cancel));
    assert(!portrait::render(image.data(),w,h,mask.data(),w,h,0,out.data(),cancel));
    // The live matte shares saved-photo refinement: align a deliberately soft
    // mask to a real image edge, retaining confident interiors without overshoot.
    const int rw=128,rh=96;
    std::vector<uint32_t> step(rw*rh);std::vector<float> soft(rw*rh), refined(rw*rh);
    double beforeError=0,afterError=0;
    for(int y=0;y<rh;++y) for(int x=0;x<rw;++x) {
        step[y*rw+x]=x<rw/2?0xffe0d0c0:0xff203040;
        soft[y*rw+x]=std::clamp((rw/2+8.f-x)/16.f,0.f,1.f);
        float target=x<rw/2?1.f:0.f;beforeError+=std::pow(soft[y*rw+x]-target,2);
    }
    auto originalStep=step; auto originalSoft=soft;
    assert(portrait::refineMask(step.data(),rw,rh,soft.data(),rw,rh,refined.data(),cancel));
    for(int y=0;y<rh;++y) for(int x=0;x<rw;++x) {
        float value=refined[y*rw+x];assert(std::isfinite(value) && value>=0 && value<=1);
        afterError+=std::pow(value-(x<rw/2?1.f:0.f),2);
    }
    assert(afterError<beforeError*.7);assert(step==originalStep && soft==originalSoft);
    soft[0]=std::numeric_limits<float>::quiet_NaN();
    assert(!portrait::refineMask(step.data(),rw,rh,soft.data(),rw,rh,refined.data(),cancel));
    soft[0]=1;cancel=true;
    assert(!portrait::refineMask(step.data(),rw,rh,soft.data(),rw,rh,refined.data(),cancel));cancel=false;
    assert(!portrait::refineMask(step.data(),513,rh,soft.data(),rw,rh,refined.data(),cancel));
    // Live controls preserve a missed sleeve/support plane at the focus distance,
    // while selecting a strong defocus radius and safe colour source in the far plane.
    std::vector<float> liveMask(rw*rh),liveDepth(rw*rh);
    std::vector<uint32_t> controls(rw*rh);
    for(int y=0;y<rh;++y)for(int x=0;x<rw;++x) {
        liveMask[y*rw+x]=x<rw/4?1.f:0.f;liveDepth[y*rw+x]=x<rw/2?7.f:2.f;
    }
    auto inputPixels=step;auto inputMask=liveMask;auto inputDepth=liveDepth;
    assert(portrait::previewControls(step.data(),rw,rh,liveMask.data(),rw,rh,liveDepth.data(),rw,rh,.1f,.5f,controls.data(),cancel));
    assert(((controls[(rh/2)*rw+rw*3/8]>>8)&255)==0);
    assert((controls[(rh/2)*rw+rw*3/8]&255)==0);
    assert(((controls[(rh/2)*rw+rw*7/8]>>8)&255)>240);
    assert((controls[(rh/2)*rw+rw*7/8]&255)>240);
    assert(step==inputPixels && liveMask==inputMask && liveDepth==inputDepth);
    assert(portrait::previewControls(step.data(),rw,rh,liveMask.data(),rw,rh,nullptr,0,0,.1f,.5f,controls.data(),cancel));
    for(auto control:controls)assert(((control>>8)&255)==255);
    liveDepth[0]=std::numeric_limits<float>::quiet_NaN();
    assert(!portrait::previewControls(step.data(),rw,rh,liveMask.data(),rw,rh,liveDepth.data(),rw,rh,.1f,.5f,controls.data(),cancel));
    liveDepth[0]=7;cancel=true;
    assert(!portrait::previewControls(step.data(),rw,rh,liveMask.data(),rw,rh,liveDepth.data(),rw,rh,.1f,.5f,controls.data(),cancel));cancel=false;
    assert(!portrait::previewControls(step.data(),513,rh,liveMask.data(),rw,rh,liveDepth.data(),rw,rh,.1f,.5f,controls.data(),cancel));
    assert(!portrait::previewControls(step.data(),rw,rh,liveMask.data(),rw,rh,liveDepth.data(),513,rh,.1f,.5f,controls.data(),cancel));
    assert(!portrait::previewControls(step.data(),rw,rh,liveMask.data(),rw,rh,liveDepth.data(),rw,rh,-.1f,.5f,controls.data(),cancel));
    std::cout<<"Live matte boundary error ratio "<<afterError/beforeError<<"\n";
    {
        // Background as bright as the subject and touching it must blur like the rest of
        // the background, not stay sharp as a spur along the subject's side.
        const int sw=256,sh=256,smw=32,smh=32;
        std::vector<uint32_t> strip(size_t(sw)*sh),stripOut(strip.size());std::vector<float> stripMask(size_t(smw)*smh);
        auto inside=[](int x,int y){return x>=64 && x<128 && y>=32 && y<224;};
        for(int y=0;y<sh;++y)for(int x=0;x<sw;++x)
            strip[size_t(y)*sw+x]=0xff000000u|uint32_t(inside(x,y)?225:(y>=112 && y<144)?220:60)*0x10101u;
        for(int y=0;y<smh;++y)for(int x=0;x<smw;++x)stripMask[size_t(y)*smw+x]=inside(x*8+4,y*8+4);
        assert(portrait::render(strip.data(),sw,sh,stripMask.data(),smw,smh,8,stripOut.data(),cancel));
        auto step=[&](int x){return int(stripOut[size_t(113)*sw+x]&255)-int(stripOut[size_t(109)*sw+x]&255);};
        assert(step(132)<=step(180)*5/4);
    }
    std::cout<<"Portrait native invariants passed\n";
}
