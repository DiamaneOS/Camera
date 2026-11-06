// SPDX-License-Identifier: MIT
#include "jpegburst.h"
#include <cassert>
#include <stdexcept>
int main() {
    std::atomic_bool stop(false);
    for(auto dims:{std::array<int,2>{0,32},{32,0},{8193,32},{8192,8192},{-1,32}}) {
        bool failed=false;try{jpeg_burst::Merger merger(dims[0],dims[1],stop);}catch(const std::invalid_argument&){failed=true;}assert(failed);
    }
    for(auto dims:{std::array<int,2>{8,8},{10,12},{128,96}}) {
        std::vector<uint32_t> pixels(size_t(dims[0])*dims[1],0xff808080u),result;
        jpeg_burst::Merger merger(dims[0],dims[1],stop);
        assert(merger.add(pixels.data(),pixels.size()));assert(merger.add(pixels.data(),pixels.size()));
        jpeg_burst::Stats stats;assert(merger.finish(result,stats));assert(result==pixels);
        bool failed=false;try{merger.add(pixels.data(),pixels.size());}catch(const std::invalid_argument&){failed=true;}assert(failed);
    }
    jpeg_burst::Merger merger(32,32,stop);std::vector<uint32_t> pixels(1024,0xff404040u);
    bool bad=false;try{merger.add(nullptr,pixels.size());}catch(const std::invalid_argument&){bad=true;}assert(bad);
    bad=false;try{merger.add(pixels.data(),pixels.size()-1);}catch(const std::invalid_argument&){bad=true;}assert(bad);
    assert(merger.add(pixels.data(),pixels.size()));stop=true;assert(!merger.add(pixels.data(),pixels.size()));
    std::vector<uint32_t> output(5,7);jpeg_burst::Stats stats;assert(!merger.finish(output,stats));assert(output==std::vector<uint32_t>(5,7));
    // Finishing without a single frame is a state error (the JNI layer turns it into a
    // Java exception) and leaves the output untouched.
    std::atomic_bool live(false);jpeg_burst::Merger empty(16,16,live);
    std::vector<uint32_t> untouched(3,9);jpeg_burst::Stats emptyStats;
    bool refused=false;try{empty.finish(untouched,emptyStats);}catch(const std::logic_error&){refused=true;}
    assert(refused);assert(untouched==std::vector<uint32_t>(3,9));
}
