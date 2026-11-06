#include "jpegburst.h"
#include <cassert>
#include <chrono>
#include <thread>
int main() {
    constexpr int width=1024,height=768;
    std::vector<uint32_t> pixels(size_t(width)*height);
    uint32_t seed=71429;
    for(auto& pixel:pixels){seed=seed*1664525u+1013904223u;pixel=0xff000000u|(seed&0xffffffu);}
    std::atomic_bool stop(false);jpeg_burst::Merger merger(width,height,stop);
    assert(merger.add(pixels.data(),pixels.size()));
    bool completed=true;
    std::thread worker([&]{completed=merger.add(pixels.data(),pixels.size());});
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
    auto began=std::chrono::steady_clock::now();stop=true;worker.join();
    assert(!completed);assert(std::chrono::steady_clock::now()-began<std::chrono::seconds(2));
    std::vector<uint32_t> out(3,17);jpeg_burst::Stats stats;
    assert(!merger.finish(out,stats));assert(out==std::vector<uint32_t>(3,17));
}
