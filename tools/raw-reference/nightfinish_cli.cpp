// SPDX-License-Identifier: MIT
#include "nightfinish.h"
#include <atomic>
#include <fstream>
#include <iostream>
#include <vector>
int main(int argc,char** argv) {
    if(argc!=3) return 2;
    std::ifstream input(argv[1],std::ios::binary);std::string magic;int w,h,max;
    input>>magic>>w>>h>>max;input.get();
    if(magic!="P6"||w<8||h<8||int64_t(w)*h>16'000'000||max!=255)return 3;
    std::vector<unsigned char> rgb(size_t(w)*h*3);input.read(reinterpret_cast<char*>(rgb.data()),rgb.size());
    if(!input)return 4;
    std::vector<uint32_t> pixels(size_t(w)*h);
    for(size_t i=0;i<pixels.size();++i)pixels[i]=0xff000000|(uint32_t(rgb[i*3])<<16)|(uint32_t(rgb[i*3+1])<<8)|rgb[i*3+2];
    std::atomic_bool cancel(false);
    if(!night::finish(pixels.data(),w,h,0,cancel))return 5;
    for(size_t i=0;i<pixels.size();++i){rgb[i*3]=(pixels[i]>>16)&255;rgb[i*3+1]=(pixels[i]>>8)&255;rgb[i*3+2]=pixels[i]&255;}
    std::ofstream output(argv[2],std::ios::binary);output<<"P6\n"<<w<<" "<<h<<"\n255\n";output.write(reinterpret_cast<char*>(rgb.data()),rgb.size());
    return output?0:6;
}
