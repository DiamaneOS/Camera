// SPDX-License-Identifier: MIT
#include "jpegburst.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <thread>

namespace jpeg_burst {
namespace {
using V = std::array<float, 3>;
struct Guide { int w = 0, h = 0; std::vector<V> p; };
struct Noise { int size = 0, w = 0, h = 0; std::vector<V> p; };
struct Point { int x, y; V value, gx, gy; };
struct Alignment { double x = 0, y = 0, uncertainty = std::numeric_limits<double>::infinity(); };

V ycc(float r, float g, float b) { return {(r+2*g+b)*.25f, (r-b)*.5f, (-r+2*g-b)*.25f}; }
V ycc(uint32_t p) { return ycc(float((p>>16)&255), float((p>>8)&255), float(p&255)); }
float median(std::vector<float>& values) {
    if (values.empty()) return 0;
    auto mid = values.begin()+values.size()/2;
    std::nth_element(values.begin(), mid, values.end());
    float value = *mid;
    if (values.size()%2 == 0) value = (value+*std::max_element(values.begin(), mid))*.5f;
    return value;
}
template<class F> void rows(int height, F function) {
    std::thread second([&] { for(int y=height/2;y<height;y++) function(y); });
    for(int y=0;y<height/2;y++) function(y);
    second.join();
}

Guide makeGuide(const uint32_t* input, int width, int height, const std::atomic_bool& stop) {
    // Equivalent interior kernel of two 5-tap Gaussian decimations. Unlike a
    // box guide this retains subpixel displacement of narrow edges.
    constexpr int k[13] = {1,4,10,20,31,40,44,40,31,20,10,4,1};
    Guide out; out.w=(width+3)/4; out.h=(height+3)/4;
    std::vector<V> horizontal(size_t(out.w)*height);
    out.p.resize(size_t(out.w)*out.h);
    rows(height,[&](int y) {
        if(stop.load(std::memory_order_relaxed))return;
        for(int x=0;x<out.w;x++) {
            V sum{};
            for(int d=-6;d<=6;d++) {
                uint32_t p=input[size_t(y)*width+std::clamp(x*4+d,0,width-1)];
                float weight=k[d+6]/256.f;
                sum[0]+=weight*((p>>16)&255);sum[1]+=weight*((p>>8)&255);sum[2]+=weight*(p&255);
            }
            horizontal[size_t(y)*out.w+x]=sum;
        }
    });
    if(stop.load())return {};
    rows(out.h,[&](int y) {
        if(stop.load(std::memory_order_relaxed))return;
        for(int x=0;x<out.w;x++) {
            V sum{};
            for(int d=-6;d<=6;d++) {
                auto value=horizontal[size_t(std::clamp(y*4+d,0,height-1))*out.w+x];
                float weight=k[d+6]/256.f;
                for(int c=0;c<3;c++)sum[c]+=weight*value[c];
            }
            out.p[size_t(y)*out.w+x]=ycc(sum[0],sum[1],sum[2]);
        }
    });
    return out;
}
Guide blur(const Guide& input) {
    Guide out{input.w,input.h,std::vector<V>(input.p.size())};
    constexpr int k[3]={1,2,1};
    for(int y=0;y<input.h;y++)for(int x=0;x<input.w;x++) {
        V value{};
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
            auto p=input.p[size_t(std::clamp(y+dy,0,input.h-1))*input.w+std::clamp(x+dx,0,input.w-1)];
            for(int c=0;c<3;c++)value[c]+=p[c]*(k[dy+1]*k[dx+1]/16.f);
        }
        out.p[size_t(y)*input.w+x]=value;
    }
    return out;
}
template<class Read> Noise estimateNoise(int width,int height,int tile,bool fine,Read read,const std::atomic_bool& stop) {
    Noise out{tile,(width+tile-1)/tile,(height+tile-1)/tile,{}};
    out.p.resize(size_t(out.w)*out.h);
    for(int ty=0;ty<out.h;ty++)for(int tx=0;tx<out.w;tx++) {
        if(stop.load())return {};
        int left=tx*tile,top=ty*tile,right=std::min(width,left+tile),bottom=std::min(height,top+tile);
        V estimate{.25f,.25f,.25f};bool measured=false;
        for(int spacing:{1,2,4}) {
            if(fine&&spacing==4)continue;
            std::array<std::vector<float>,3> values;
            for(int y=top;y+spacing<bottom;y+=2*spacing)for(int x=left;x+spacing<right;x+=2*spacing) {
                V a=read(x,y),b=read(x+spacing,y+spacing),c=read(x,y+spacing),d=read(x+spacing,y);
                for(int ch=0;ch<3;ch++)values[ch].push_back(.5f*std::abs(a[ch]+b[ch]-c[ch]-d[ch]));
            }
            if(values[0].size()<16)continue;
            measured=true;
            for(int c=0;c<3;c++)estimate[c]=std::max(estimate[c],median(values[c])/.67449f);
        }
        out.p[size_t(ty)*out.w+tx]=measured?estimate:V{1,1,1};
    }
    return out;
}
V at(const Noise& n,int x,int y) {
    return n.p[size_t(std::clamp(y/n.size,0,n.h-1))*n.w+std::clamp(x/n.size,0,n.w-1)];
}
template<class Read> V sample(int width,int height,double x,double y,Read read) {
    x=std::clamp(x,0.,double(width-1));y=std::clamp(y,0.,double(height-1));
    int x0=int(x),y0=int(y),x1=std::min(width-1,x0+1),y1=std::min(height-1,y0+1);
    float u=float(x-x0),v=float(y-y0);V out{};
    V a=read(x0,y0),b=read(x1,y0),c=read(x0,y1),d=read(x1,y1);
    for(int ch=0;ch<3;ch++)out[ch]=(1-v)*((1-u)*a[ch]+u*b[ch])+v*((1-u)*c[ch]+u*d[ch]);
    return out;
}
V sample(const Guide& g,double x,double y) {
    return sample(g.w,g.h,x,y,[&](int xx,int yy){return g.p[size_t(yy)*g.w+xx];});
}
V sample(const Noise& n,int width,int height,double x,double y) {
    return sample(width,height,x,y,[&](int xx,int yy){return at(n,xx,yy);});
}

Alignment registerFrame(const Guide& reference,const Guide& current,const Noise& refNoise,const Noise& altNoise,const std::atomic_bool& stop) {
    constexpr int margin=9,maximum=8;
    if(reference.w<=2*margin+4||reference.h<=2*margin+4)return {};
    Guide ref=blur(reference),alt=blur(current);
    int stride=std::max(2,int(std::ceil(std::sqrt(double((ref.w-2*margin)*(ref.h-2*margin))/50000))));
    std::vector<Point> points;std::array<double,3> sum{},squares{};
    for(int y=margin;y<ref.h-margin;y+=stride)for(int x=margin;x<ref.w-margin;x+=stride) {
        Point p{x,y,ref.p[size_t(y)*ref.w+x],{}, {}};
        for(int c=0;c<3;c++) {
            p.gx[c]=.5f*(ref.p[size_t(y)*ref.w+x+1][c]-ref.p[size_t(y)*ref.w+x-1][c]);
            p.gy[c]=.5f*(ref.p[size_t(y+1)*ref.w+x][c]-ref.p[size_t(y-1)*ref.w+x][c]);
            sum[c]+=p.value[c];squares[c]+=p.value[c]*p.value[c];
        }
        points.push_back(p);
    }
    std::array<double,3> variance{},sigma{};
    for(int c=0;c<3;c++) {
        variance[c]=std::max(0.,squares[c]/points.size()-std::pow(sum[c]/points.size(),2));
        std::vector<float> values;values.reserve(reference.p.size());
        for(int y=0;y<reference.h;y++)for(int x=0;x<reference.w;x++) {
            V a=at(refNoise,x,y),b=at(altNoise,x,y);values.push_back(std::sqrt(a[c]*a[c]+b[c]*b[c]));
        }
        sigma[c]=std::max(.5,double(median(values)));
    }
    std::array<std::vector<float>,3> residual;
    // Bound the expensive integer search separately from the dense subpixel
    // refinement. Reducing the latter made real noisy frames less precise.
    const size_t coarseStep=std::max(size_t(1),(points.size()+11999)/12000);
    for(auto& v:residual)v.resize((points.size()+coarseStep-1)/coarseStep);
    double best=std::numeric_limits<double>::infinity(),dx=0,dy=0;
    for(int y=-maximum;y<=maximum;y++)for(int x=-maximum;x<=maximum;x++) {
        if(stop.load())return {};
        for(size_t i=0;i<points.size();i+=coarseStep) {
            auto& p=points[i];V value=alt.p[size_t(p.y+y)*alt.w+p.x+x];
            for(int c=0;c<3;c++)residual[c][i/coarseStep]=value[c]-p.value[c];
        }
        double cost=0;
        for(int c=0;c<3;c++) {
            float offset=median(residual[c]);
            for(float value:residual[c])cost+=std::min(8.,std::abs(value-offset)/sigma[c]);
        }
        if(cost<best){best=cost;dx=x;dy=y;}
    }
    for(auto& values:residual)values.resize(points.size());
    double a=0,b=0,c=0,weightSum=0;
    std::vector<float> scratch(points.size());
    for(int iteration=0;iteration<12;iteration++) {
        if(stop.load())return {};
        for(size_t i=0;i<points.size();i++) {
            auto& p=points[i];V value=sample(alt,p.x+dx,p.y+dy);
            for(int ch=0;ch<3;ch++)residual[ch][i]=value[ch]-p.value[ch];
        }
        std::array<double,3> scale{},channel{};double channelSum=0;
        for(int ch=0;ch<3;ch++) {
            scratch=residual[ch];float offset=median(scratch);
            for(size_t i=0;i<points.size();i++){residual[ch][i]-=offset;scratch[i]=std::abs(residual[ch][i]);}
            scale[ch]=std::max({.25,sigma[ch]*.375,double(median(scratch))/.67449});
            channel[ch]=std::clamp((variance[ch]-.5*scale[ch]*scale[ch])/std::max(variance[ch],1e-6),0.,1.);
            channelSum+=channel[ch];
        }
        a=c=.001;b=weightSum=0;double bx=0,by=0;
        for(size_t i=0;i<points.size();i++) {
            double norm=0;std::array<double,3> r{};
            for(int ch=0;ch<3;ch++){r[ch]=residual[ch][i]/scale[ch];norm+=r[ch]*r[ch]*channel[ch];}
            norm/=std::max(channelSum,.01)*4.685*4.685;
            double weight=std::pow(std::max(0.,1-norm),2);
            weightSum+=weight*channelSum;
            for(int ch=0;ch<3;ch++) {
                double root=std::sqrt(channel[ch]);double gx=points[i].gx[ch]/scale[ch]*root,gy=points[i].gy[ch]/scale[ch]*root;
                double rr=r[ch]*root;a+=weight*gx*gx;b+=weight*gx*gy;c+=weight*gy*gy;bx+=weight*gx*rr;by+=weight*gy*rr;
            }
        }
        double det=a*c-b*b;
        if(det<1e-6||det/(a*c)<1e-4)break;
        double sx=std::clamp(-(c*bx-b*by)/det,-1.,1.),sy=std::clamp(-(-b*bx+a*by)/det,-1.,1.);
        dx=std::clamp(dx+sx,-8.,8.);dy=std::clamp(dy+sy,-8.,8.);
        if(std::max(std::abs(sx),std::abs(sy))<.001)break;
    }
    double sa=a-.1335*weightSum,sc=c-.1335*weightSum,det=sa*sc-b*b;
    double uncertainty=sa>0&&sc>0&&det>1e-6?4*std::sqrt(std::max(sa,sc)/det):std::numeric_limits<double>::infinity();
    return {dx*4,dy*4,uncertainty};
}
}

struct Merger::State {
    struct Sum { uint16_t r=0,g=0,b=0; };
    int width,height,seen=0;bool finished=false;
    const std::atomic_bool& stop;
    std::vector<uint32_t> reference;
    std::vector<Sum> sums;
    std::vector<uint8_t> counts;
    Guide referenceGuide;
    Noise referenceNoise,pixelNoise;
    std::vector<V> temporalVariance;
    std::vector<uint8_t> temporalPairs;
    Stats stats;
    State(int w,int h,const std::atomic_bool& s):width(w),height(h),stop(s) {
        if(w<8||h<8||w>8192||h>8192||uint64_t(w)*h>16'000'000)throw std::invalid_argument("JPEG merge dimensions");
    }
    V meanAt(int x,int y)const {
        size_t i=size_t(std::clamp(y,0,height-1))*width+std::clamp(x,0,width-1);
        float inv=1.f/counts[i];auto sum=sums[i];return ycc(sum.r*inv,sum.g*inv,sum.b*inv);
    }
};
Merger::Merger(int width,int height,const std::atomic_bool& stop):state_(std::make_unique<State>(width,height,stop)){}
Merger::~Merger()=default;

bool Merger::add(const uint32_t* pixels,size_t count) {
    auto& s=*state_;
    if(s.finished||!pixels||count!=size_t(s.width)*s.height||s.seen>=8)throw std::invalid_argument("JPEG merge frame");
    if(s.stop.load())return false;
    s.seen++;
    if(s.reference.empty()) {
        s.reference.assign(pixels,pixels+count);s.sums.resize(count);s.counts.assign(count,1);
        for(size_t i=0;i<count;i++)s.sums[i]={uint16_t((pixels[i]>>16)&255),uint16_t((pixels[i]>>8)&255),uint16_t(pixels[i]&255)};
        s.referenceGuide=makeGuide(pixels,s.width,s.height,s.stop);if(s.stop.load())return false;
        auto& g=s.referenceGuide;
        s.referenceNoise=estimateNoise(g.w,g.h,32,false,[&](int x,int y){return g.p[size_t(y)*g.w+x];},s.stop);
        s.pixelNoise=estimateNoise(s.width,s.height,128,true,[&](int x,int y){return ycc(pixels[size_t(y)*s.width+x]);},s.stop);
        s.temporalVariance.resize(s.pixelNoise.p.size());
        s.temporalPairs.assign(s.pixelNoise.p.size(),0);
        return !s.stop.load();
    }
    Guide g=makeGuide(pixels,s.width,s.height,s.stop);if(s.stop.load())return false;
    Noise noise=estimateNoise(g.w,g.h,32,false,[&](int x,int y){return g.p[size_t(y)*g.w+x];},s.stop);if(s.stop.load())return false;
    Alignment alignment=registerFrame(s.referenceGuide,g,s.referenceNoise,noise,s.stop);if(s.stop.load())return false;
    s.stats.registration.push_back({alignment.x,alignment.y,alignment.uncertainty});
    if(!std::isfinite(alignment.uncertainty)||alignment.uncertainty>.1){s.stats.rejectedFrames++;return true;}
    s.stats.maximumAcceptedShift=std::max(s.stats.maximumAcceptedShift,std::hypot(alignment.x,alignment.y));
    double dx=alignment.x,dy=alignment.y;
    std::vector<uint8_t> reject(g.p.size()),dilated(g.p.size());
    for(int y=0;y<g.h;y++)for(int x=0;x<g.w;x++) {
        double xx=x+dx/4,yy=y+dy/4;bool bad=xx<0||yy<0||xx>g.w-1||yy>g.h-1;
        V ref=s.referenceGuide.p[size_t(y)*g.w+x],alt=sample(g,xx,yy),a=at(s.referenceNoise,x,y),b=sample(noise,g.w,g.h,xx,yy);
        for(int c=0;c<3;c++)bad=bad||std::abs(ref[c]-alt[c])>3*std::sqrt(a[c]*a[c]+b[c]*b[c])+1||a[c]>32;
        reject[size_t(y)*g.w+x]=bad;
    }
    for(int y=0;y<g.h;y++)for(int x=0;x<g.w;x++) {
        bool bad=false;
        for(int yy=-1;yy<=1;yy++)for(int xx=-1;xx<=1;xx++)bad=bad||reject[size_t(std::clamp(y+yy,0,g.h-1))*g.w+std::clamp(x+xx,0,g.w-1)];
        dilated[size_t(y)*g.w+x]=bad;
    }
    // Spatial high-pass estimates miss the correlated chroma noise in ISP
    // JPEGs. Measure a second, conservative estimate from admitted frame pairs;
    // subtract the median to avoid counting a uniform exposure/color offset.
    // Motion-vetoed samples do not contribute. The interpolation factor is the
    // independent-sample approximation; the spatial estimate remains a floor.
    const double u=dx-std::floor(dx),v=dy-std::floor(dy);
    const float pairScale=float(std::sqrt(1+((1-u)*(1-u)+u*u)*((1-v)*(1-v)+v*v)));
    std::array<std::vector<float>,3> differences;
    for(auto& values:differences)values.reserve(4096);
    for(int ty=0;ty<s.pixelNoise.h;ty++)for(int tx=0;tx<s.pixelNoise.w;tx++) {
        if(s.stop.load())return false;
        for(auto& values:differences)values.clear();
        int left=tx*128,top=ty*128,right=std::min(s.width,left+128),bottom=std::min(s.height,top+128);
        for(int y=top;y<bottom;y+=2)for(int x=left;x<right;x+=2) {
            double xx=x+dx,yy=y+dy;
            if(dilated[size_t(y/4)*g.w+x/4]||xx<0||yy<0||xx>s.width-1||yy>s.height-1)continue;
            V ref=ycc(s.reference[size_t(y)*s.width+x]);
            V alt=sample(s.width,s.height,xx,yy,[&](int ax,int ay){return ycc(pixels[size_t(ay)*s.width+ax]);});
            for(int c=0;c<3;c++)differences[c].push_back(ref[c]-alt[c]);
        }
        if(differences[0].size()<64)continue;
        size_t tile=size_t(ty)*s.pixelNoise.w+tx;
        for(int c=0;c<3;c++) {
            float offset=median(differences[c]);
            for(auto& value:differences[c])value=std::abs(value-offset);
            float sigma=median(differences[c])/(.67449f*pairScale);
            s.temporalVariance[tile][c]+=sigma*sigma;
        }
        s.temporalPairs[tile]++;
    }
    rows(s.height,[&](int y) {
        if(s.stop.load(std::memory_order_relaxed))return;
        for(int x=0;x<s.width;x++) {
            if(dilated[size_t(y/4)*g.w+x/4])continue;
            double xx=x+dx,yy=y+dy;if(xx<0||yy<0||xx>s.width-1||yy>s.height-1)continue;
            V value=sample(s.width,s.height,xx,yy,[&](int ax,int ay){uint32_t p=pixels[size_t(ay)*s.width+ax];return V{float((p>>16)&255),float((p>>8)&255),float(p&255)};});
            size_t i=size_t(y)*s.width+x;auto& sum=s.sums[i];
            sum.r+=uint16_t(std::clamp(int(std::lround(value[0])),0,255));sum.g+=uint16_t(std::clamp(int(std::lround(value[1])),0,255));sum.b+=uint16_t(std::clamp(int(std::lround(value[2])),0,255));s.counts[i]++;
        }
    });
    return !s.stop.load();
}

bool Merger::finish(std::vector<uint32_t>& output,Stats& stats) {
    auto& s=*state_;
    if(s.finished||s.seen<1)throw std::logic_error("JPEG merge state");
    s.finished=true;if(s.stop.load())return false;
    uint64_t total=0;for(uint8_t count:s.counts)total+=count;
    s.stats.effectiveFrames=double(total)/s.counts.size();
    for(size_t i=0;i<s.pixelNoise.p.size();i++)if(s.temporalPairs[i]) {
        for(int c=0;c<3;c++)s.pixelNoise.p[i][c]=std::max(s.pixelNoise.p[i][c],
            std::sqrt(s.temporalVariance[i][c]/s.temporalPairs[i]));
    }
    // Reuse three decoded mean rows per thread rather than repeating nine
    // divisions and color conversions at every output pixel. Keep the same
    // accumulation order, including replicated border samples.
    std::array<std::vector<V>,2> cached;
    for(auto& cache:cached)cache.resize(size_t(s.width)*3);
    auto finishRows=[&](int worker,int begin,int end) {
        auto& cache=cached[worker];
        auto slot=[](int row){return (row+3)%3;};
        auto fill=[&](int row) {
            for(int x=0;x<s.width;x++)cache[size_t(slot(row))*s.width+x]=s.meanAt(x,row);
        };
        fill(begin-1);fill(begin);
        for(int y=begin;y<end;y++) {
            if(s.stop.load(std::memory_order_relaxed))return;
            fill(y+1);
            for(int x=0;x<s.width;x++) {
            V mean{},squares{};
            for(int yy=-1;yy<=1;yy++)for(int xx=-1;xx<=1;xx++) {
                V value=cache[size_t(slot(y+yy))*s.width+std::clamp(x+xx,0,s.width-1)];
                for(int c=0;c<3;c++){mean[c]+=value[c]/9;squares[c]+=value[c]*value[c]/9;}
            }
            size_t i=size_t(y)*s.width+x;V sigma=at(s.pixelNoise,x,y);float gain=0;
            for(int c=0;c<3;c++) {
                float variance=std::max(0.f,squares[c]-mean[c]*mean[c]);float noise=sigma[c]*sigma[c]/s.counts[i];
                // Suppress fluctuations close to the measured noise floor,
                // but fully anchor clear local detail to the unwarped frame.
                gain=std::max(gain,std::clamp((variance-1.25f*noise)/std::max(.75f*noise,.25f),0.f,1.f));if(sigma[c]>80)gain=1;
            }
            auto sum=s.sums[i];float inv=1.f/s.counts[i];float averaged[3]={sum.r*inv,sum.g*inv,sum.b*inv};uint32_t ref=s.reference[i],packed=0xff000000u;
            for(int c=0;c<3;c++){float original=float((ref>>(16-8*c))&255);int value=int(std::lround(averaged[c]+gain*(original-averaged[c])));packed|=uint32_t(std::clamp(value,0,255))<<(16-8*c);}
            s.reference[i]=packed;
            }
        }
    };
    std::thread second([&]{finishRows(1,s.height/2,s.height);});
    finishRows(0,0,s.height/2);second.join();
    if(s.stop.load())return false;
    stats=s.stats;output=std::move(s.reference);
    std::vector<State::Sum>().swap(s.sums);std::vector<uint8_t>().swap(s.counts);
    s.referenceGuide={};s.referenceNoise={};s.pixelNoise={};
    s.temporalVariance={};s.temporalPairs={};
    return true;
}
}
