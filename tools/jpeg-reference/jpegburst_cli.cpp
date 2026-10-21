// SPDX-License-Identifier: MIT
#include "jpegburst.h"
#include <fstream>
#include <iostream>
#include <string>
#include <cmath>

std::vector<uint32_t> read(const char* name,int& width,int& height) {
    std::ifstream input(name,std::ios::binary);std::string magic;int maximum=0;
    input>>magic>>width>>height>>maximum;input.get();
    if(!input||magic!="P6"||width<8||height<8||width>8192||height>8192||uint64_t(width)*height>16'000'000||maximum!=255)
        throw std::runtime_error("PPM input");
    std::vector<uint8_t> bytes(size_t(width)*height*3);input.read(reinterpret_cast<char*>(bytes.data()),bytes.size());
    if(!input)throw std::runtime_error("PPM truncated");
    std::vector<uint32_t> pixels(size_t(width)*height);
    for(size_t i=0;i<pixels.size();i++)pixels[i]=0xff000000u|(uint32_t(bytes[3*i])<<16)|(uint32_t(bytes[3*i+1])<<8)|bytes[3*i+2];
    return pixels;
}
int main(int argc,char** argv) {
    if(argc<3||argc>10)return 2;
    try {
        int width=0,height=0;std::atomic_bool stop(false);std::unique_ptr<jpeg_burst::Merger> merger;
        for(int i=2;i<argc;i++) {
            int w=0,h=0;auto input=read(argv[i],w,h);
            if(!merger){width=w;height=h;merger=std::make_unique<jpeg_burst::Merger>(w,h,stop);}
            if(w!=width||h!=height||!merger->add(input.data(),input.size()))return 3;
        }
        std::vector<uint32_t> pixels;jpeg_burst::Stats stats;
        if(!merger->finish(pixels,stats))return 4;
        std::ofstream output(argv[1],std::ios::binary);output<<"P6\n"<<width<<" "<<height<<"\n255\n";
        for(uint32_t pixel:pixels){char value[3]={char((pixel>>16)&255),char((pixel>>8)&255),char(pixel&255)};output.write(value,3);}
        if(!output)return 5;
        std::cout<<"{\"effectiveFrames\":"<<stats.effectiveFrames<<",\"rejectedFrames\":"<<stats.rejectedFrames
            <<",\"maximumAcceptedShift\":"<<stats.maximumAcceptedShift<<",\"registration\":[";
        bool first=true;
        for(auto value:stats.registration){if(!first)std::cout<<",";first=false;std::cout<<"["<<value[0]<<","<<value[1]<<",";
            if(std::isfinite(value[2]))std::cout<<value[2];else std::cout<<"null";std::cout<<"]";}
        std::cout<<"]}\n";
    } catch(const std::exception& error){std::cerr<<error.what()<<"\n";return 6;}
}
