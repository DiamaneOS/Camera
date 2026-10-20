// SPDX-License-Identifier: MIT
#include "nightfinish.h"
#include <array>
#include <cassert>
#include <vector>

#ifdef NIGHT_FINISH_REFERENCE
namespace night_reference {
bool finish(uint32_t*,int,int,float,const std::atomic_bool&,float,float,const float*,bool,bool,bool);
}
#endif

int main() {
    std::atomic_bool stop(false);
    for(auto size:std::array<std::array<int,2>,8>{{{8,9},{9,8},{9,9},{127,71},{128,71},{127,72},{128,96},{512,288}}}) {
        int w=size[0],h=size[1];size_t count=size_t(w)*h;
        std::vector<uint32_t> guarded(count+2,0xff808080u);
        guarded.front()=0x13572468;guarded.back()=0x24681357;
        assert(night::finish(guarded.data()+1,w,h,0,stop));
        assert(guarded.front()==0x13572468 && guarded.back()==0x24681357);
        for(size_t i=1;i<=count;++i)assert(guarded[i]==0xff808080u);
        uint32_t seed=9183;
        for(size_t i=1;i<=count;++i) {seed=seed*1664525u+1013904223u;guarded[i]=0xff000000u|(seed&0xffffffu);}
        auto original=guarded;
        assert(night::finish(guarded.data()+1,w,h,.24f,stop,1,4.f/3,nullptr,true));
        assert(guarded.front()==0x13572468 && guarded.back()==0x24681357);
        for(size_t i=1;i<=count;++i)assert((guarded[i]>>24)==255);
#ifdef NIGHT_FINISH_REFERENCE
        if(w%2==0 && h%2==0) {
            auto reference=original;
            assert(night_reference::finish(reference.data()+1,w,h,.24f,stop,1,4.f/3,nullptr,true,false,false));
            assert(guarded==reference);
        }
#endif
        stop=true;auto cancelled=original;
        assert(!night::finish(cancelled.data()+1,w,h,0,stop));assert(cancelled==original);stop=false;
    }
}
