// SPDX-License-Identifier: MIT
#include "portraitcore.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace portrait {
namespace {
using Plane = std::vector<float>;
float sample(const float* p, int w, int h, float x, float y) {
    x = std::clamp(x, 0.f, float(w - 1)); y = std::clamp(y, 0.f, float(h - 1));
    int ix = int(x), iy = int(y), jx = std::min(ix + 1, w - 1), jy = std::min(iy + 1, h - 1);
    float u = x - ix, v = y - iy;
    return (1 - v) * ((1 - u) * p[iy * w + ix] + u * p[iy * w + jx]) +
        v * ((1 - u) * p[jy * w + ix] + u * p[jy * w + jx]);
}
Plane box(const Plane& p, int w, int h, int r, bool fast=false) {
    const double inverse=1.0/(2*r+1);
    Plane horizontal(p.size()), out(p.size());
    for (int y = 0; y < h; ++y) {
        double sum = 0;
        for (int x = -r; x <= r; ++x) sum += p[y * w + std::clamp(x, 0, w - 1)];
        for (int x = 0; x < w; ++x) {
            horizontal[y * w + x] = (fast ? float(sum*inverse) : float(sum / (2 * r + 1)));
            sum += p[y * w + std::clamp(x + r + 1, 0, w - 1)] - p[y * w + std::clamp(x - r, 0, w - 1)];
        }
    }
    if(fast) {
        // Traverse contiguous rows instead of rereading one strided column at a
        // time. Each column keeps the same double-precision accumulation order.
        std::vector<double> sums(w);
        for(int y=-r;y<=r;++y) {
            const int row=std::clamp(y,0,h-1)*w;
            for(int x=0;x<w;++x)sums[x]+=horizontal[row+x];
        }
        for(int y=0;y<h;++y) {
            const int row=y*w,added=std::clamp(y+r+1,0,h-1)*w,removed=std::clamp(y-r,0,h-1)*w;
            for(int x=0;x<w;++x) {
                out[row+x]=float(sums[x]*inverse);
                sums[x]+=horizontal[added+x]-horizontal[removed+x];
            }
        }
    } else {
        for (int x = 0; x < w; ++x) {
            double sum = 0;
            for (int y = -r; y <= r; ++y) sum += horizontal[std::clamp(y, 0, h - 1) * w + x];
            for (int y = 0; y < h; ++y) {
                out[y * w + x] = float(sum / (2 * r + 1));
                sum += horizontal[std::clamp(y + r + 1, 0, h - 1) * w + x] - horizontal[std::clamp(y - r, 0, h - 1) * w + x];
            }
        }
    }
    return out;
}
float luma(uint32_t p) { return (.2126f * ((p >> 16) & 255) + .7152f * ((p >> 8) & 255) + .0722f * (p & 255)) / 255; }
struct GuidedFit { Plane a, b, meanI, meanII; };
GuidedFit fitMatte(const Plane& guide, const Plane& alpha, int w, int h, bool fast=false) {
    const int n = w * h;
    Plane product(n), square(n);
    for (int i = 0; i < n; ++i) { product[i] = guide[i] * alpha[i]; square[i] = guide[i] * guide[i]; }
    GuidedFit fit{Plane(n), Plane(n), box(guide, w, h, 5, fast), box(square, w, h, 5, fast)};
    auto meanP = box(alpha, w, h, 5, fast), meanIP = box(product, w, h, 5, fast);
    for (int i = 0; i < n; ++i) {
        fit.a[i] = (meanIP[i] - fit.meanI[i] * meanP[i]) /
            std::max(.001f, fit.meanII[i] - fit.meanI[i] * fit.meanI[i] + .001f);
        fit.b[i] = meanP[i] - fit.a[i] * fit.meanI[i];
    }
    fit.a = box(fit.a, w, h, 5, fast); fit.b = box(fit.b, w, h, 5, fast);
    return fit;
}

}
namespace {
struct FocusRange { bool enabled=false; float low=0,band=0,span=0; };
bool resolveFocus(const float* mask,int mw,int mh,const float* depth,int dw,int dh,
        float subjectX,float subjectY,const std::atomic_bool& cancel,FocusRange& range) {
    bool useDepth = false;
    float focus = 0, band = 0, span = 0, focusLow = 0;
    if (depth) {
        Plane scene, foreground, local;
        scene.reserve(size_t(dw) * dh);
        for (int y = 0; y < dh; ++y) {
            if (cancel.load()) return false;
            for (int x = 0; x < dw; ++x) {
                float value = depth[y * dw + x];
                if (!std::isfinite(value) || value < 0 || value > 10'000) return false;
                scene.push_back(value);
                float u = (x + .5f) / dw, v = (y + .5f) / dh;
                if (sample(mask, mw, mh, u * mw - .5f, v * mh - .5f) > .8f) {
                    foreground.push_back(value);
                    if (std::abs(u - subjectX) < .035f && std::abs(v - subjectY) < .035f) local.push_back(value);
                }
            }
        }
        std::sort(scene.begin(), scene.end());
        float range = scene[(scene.size() - 1) * 98 / 100] - scene[(scene.size() - 1) * 2 / 100];
        auto& selected = local.size() >= 16 ? local : foreground;
        if (selected.size() >= 16 && range > .0001f) {
            std::sort(selected.begin(), selected.end());
            focus = selected[(selected.size() - 1) / 2];
            float spread = selected[(selected.size() - 1) * 3 / 4] - selected[(selected.size() - 1) / 4];
            band = std::clamp(2 * spread, range * .05f, range * .2f);
            std::sort(foreground.begin(), foreground.end());
            focusLow = std::min(foreground[(foreground.size() - 1) * 5 / 100], focus);
            span = std::max(range * .25f, band * 2);
            useDepth = true;
        }
    }
    range={useDepth,focusLow,band,span};return true;
}
}

bool refineMask(const uint32_t* source, int width, int height, const float* mask,
        int mw, int mh, float* output, const std::atomic_bool& cancel) {
    if (!source || !mask || !output || width < 2 || height < 2 || width > 512 || height > 512 ||
        mw < 2 || mh < 2 || mw > 512 || mh > 512) return false;
    for (int i = 0; i < mw * mh; ++i) if (!std::isfinite(mask[i]) || mask[i] < 0 || mask[i] > 1) return false;
    if (cancel.load()) return false;
    Plane guide(width * height), alpha(width * height);
    for (int y = 0; y < height; ++y) {
        if (cancel.load()) return false;
        for (int x = 0; x < width; ++x) {
            guide[y * width + x] = luma(source[y * width + x]);
            alpha[y * width + x] = mw==width && mh==height ? mask[y*width+x] : sample(mask, mw, mh, (x + .5f) * mw / width - .5f, (y + .5f) * mh / height - .5f);
        }
    }
    auto fit = fitMatte(guide, alpha, width, height, true);
    if (cancel.load()) return false;
    for (int i = 0; i < width * height; ++i)
        output[i] = std::clamp((fit.a[i] * guide[i] + fit.b[i] - .03f) / .94f, 0.f, 1.f);
    return true;
}

// R: refined matte, G: relative blur radius, B: background convolution weight.
// These are control data, not colour pixels. Renderers must sample them without colour conversion.
bool previewControls(const uint32_t* source,int w,int h,const float* mask,int mw,int mh,
        const float* depth,int dw,int dh,float subjectX,float subjectY,uint32_t* output,
        const std::atomic_bool& cancel) {
    if(!source || !mask || !output || w<2 || h<2 || w>512 || h>512 || mw<2 || mh<2 || mw>512 || mh>512 ||
        !std::isfinite(subjectX) || !std::isfinite(subjectY) || subjectX<0 || subjectX>1 || subjectY<0 || subjectY>1)return false;
    if(depth && (dw<2 || dh<2 || dw>512 || dh>512))return false;
    for(int i=0;i<mw*mh;++i)if(!std::isfinite(mask[i]) || mask[i]<0 || mask[i]>1)return false;
    if(cancel.load())return false;
    FocusRange range;
    if(!resolveFocus(mask,mw,mh,depth,dw,dh,subjectX,subjectY,cancel,range))return false;
    const int n=w*h;Plane guide(n),alpha(n);
    for(int y=0;y<h;++y) {
        if(cancel.load())return false;
        for(int x=0;x<w;++x) {
            guide[y*w+x]=luma(source[y*w+x]);
            alpha[y*w+x]=mw==w && mh==h ? mask[y*w+x] : sample(mask,mw,mh,(x+.5f)*mw/w-.5f,(y+.5f)*mh/h-.5f);
        }
    }
    auto fit=fitMatte(guide,alpha,w,h,true);
    Plane amount(n,1.f);
    if(range.enabled) {
        Plane product(n),coefficient(n),intercept(n);
        for(int y=0;y<h;++y) {
            if(cancel.load())return false;
            for(int x=0;x<w;++x) {
                int i=y*w+x;float value=sample(depth,dw,dh,(x+.5f)*dw/w-.5f,(y+.5f)*dh/h-.5f);
                amount[i]=std::clamp((std::max(range.low-value,0.f)-range.band)/range.span,0.f,1.f);
                product[i]=guide[i]*amount[i];
            }
        }
        auto mean=box(amount,w,h,5,true),meanProduct=box(product,w,h,5,true);
        for(int i=0;i<n;++i) {
            coefficient[i]=(meanProduct[i]-fit.meanI[i]*mean[i])/
                std::max(.001f,fit.meanII[i]-fit.meanI[i]*fit.meanI[i]+.001f);
            intercept[i]=mean[i]-coefficient[i]*fit.meanI[i];
        }
        coefficient=box(coefficient,w,h,5,true);intercept=box(intercept,w,h,5,true);
        for(int y=0;y<h;++y) {
            if(cancel.load())return false;
            for(int x=0;x<w;++x) {
                int i=y*w+x;float value=sample(depth,dw,dh,(x+.5f)*dw/w-.5f,(y+.5f)*dh/h-.5f);
                amount[i]=value>=range.low-range.band?0.f:std::clamp(coefficient[i]*guide[i]+intercept[i],0.f,1.f);
            }
        }
    }
    for(int y=0;y<h;++y) {
        if(cancel.load())return false;
        for(int x=0;x<w;++x) {
            int i=y*w+x;float matte=std::clamp(fit.a[i]*guide[i]+fit.b[i],0.f,1.f);
            float refined=std::clamp((matte-.03f)/.94f,0.f,1.f);
            // An 8-bit preview render target cannot preserve tiny premultiplied
            // colour/alpha values. Exclude the focus plane without attenuating
            // remote colour by squared blur strength; radius remains continuous.
            float eligible=range.enabled?(amount[i]>=1.f/255?1.f:0.f):1.f;
            float weight=(1-matte)*eligible;
            output[i]=0xff000000u|(uint32_t(refined*255)<<16)|(uint32_t(amount[i]*255)<<8)|uint32_t(weight*255);
        }
    }
    return true;
}

bool render(const uint32_t* source, int width, int height, const float* mask,
        int mw, int mh, int radius, uint32_t* output, const std::atomic_bool& cancel,
        const float* depth, int dw, int dh, float subjectX, float subjectY) {
    if (!source || !mask || !output || width < 2 || height < 2 || int64_t(width) * height > 16'000'000 ||
        mw < 2 || mh < 2 || int64_t(mw) * mh > 1'000'000 || radius < 0 || radius > 40) return false;
    for (int i = 0; i < mw * mh; ++i) if (!std::isfinite(mask[i]) || mask[i] < 0 || mask[i] > 1) return false;
    if (cancel.load()) return false;
    if (radius == 0) { std::copy(source, source + size_t(width) * height, output); return true; }
    if (depth && (dw < 2 || dh < 2 || int64_t(dw) * dh > 1'000'000 || !std::isfinite(subjectX) ||
        !std::isfinite(subjectY) || subjectX < 0 || subjectX > 1 || subjectY < 0 || subjectY > 1)) return false;
    FocusRange range;
    if(!resolveFocus(mask,mw,mh,depth,dw,dh,subjectX,subjectY,cancel,range))return false;
    bool useDepth=range.enabled;
    float band=range.band,span=range.span,focusLow=range.low;
    float scale = std::min(1.f, 1024.f / std::max(width, height));
    int w = std::max(2, int(std::round(width * scale))), h = std::max(2, int(std::round(height * scale)));
    int n = w * h;
    Plane guide(n), alpha(n), product(n);
    std::vector<uint32_t> small(n);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        int i = y * w + x;
        small[i] = source[std::min(height - 1, int((y + .5f) / scale)) * width + std::min(width - 1, int((x + .5f) / scale))];
        guide[i] = luma(small[i]); alpha[i] = sample(mask, mw, mh, (x + .5f) * mw / w - .5f, (y + .5f) * mh / h - .5f);
    }
    auto fit = fitMatte(guide, alpha, w, h);
    auto& a = fit.a; auto& b = fit.b; auto& mi = fit.meanI; auto& ii = fit.meanII;
    // The luma-guided matte also spreads onto background of the subject's brightness that
    // touches it, which then stayed partly sharp as a spur. Keep it within about one mask
    // cell of the segmentation: full where the mask is at least one half, none where it is zero.
    auto reach = [&](float x, float y) { return std::clamp(2 * sample(mask, mw, mh, x * mw - .5f, y * mh - .5f), 0.f, 1.f); };
    Plane weight(n);
    for (int i = 0; i < n; ++i)
        weight[i] = 1 - std::min(std::clamp(a[i] * guide[i] + b[i], 0.f, 1.f), reach((i % w + .5f) / w, (i / w + .5f) / h));
    Plane depthA, depthB;
    if (useDepth) {
        Plane amount(n);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            int i = y * w + x;
            float value = sample(depth, dw, dh, (x + .5f) * dw / w - .5f, (y + .5f) * dh / h - .5f);
            // The pinned model returns inverse depth: larger is nearer. This
            // is background blur, so nearer arms and missed sleeves stay sharp.
            float distance = std::max(focusLow - value, 0.f);
            amount[i] = std::clamp((distance - band) / span, 0.f, 1.f);
            product[i] = guide[i] * amount[i];
        }
        auto md = box(amount, w, h, 5), id = box(product, w, h, 5);
        depthA.resize(n); depthB.resize(n);
        for (int i = 0; i < n; ++i) {
            depthA[i] = (id[i] - mi[i] * md[i]) / std::max(.001f, ii[i] - mi[i] * mi[i] + .001f);
            depthB[i] = md[i] - depthA[i] * mi[i];
        }
        depthA = box(depthA, w, h, 5); depthB = box(depthB, w, h, 5);
        // Other objects and support surfaces at the focus distance must not
        // spread their color into a blurred remote background.
        for (int i = 0; i < n; ++i) {
            float amount = std::clamp(depthA[i] * guide[i] + depthB[i], 0.f, 1.f);
            float value = sample(depth, dw, dh, (i % w + .5f) * dw / w - .5f, (i / w + .5f) * dh / h - .5f);
            weight[i] = value >= focusLow - band ? 0.f : weight[i] * amount * amount;
        }
    }
    std::vector<int> radii = useDepth ? std::vector<int>{std::max(1, radius / 4), std::max(1, radius / 2), radius} : std::vector<int>{radius};
    radii.erase(std::unique(radii.begin(), radii.end()), radii.end());
    std::vector<std::array<Plane, 3>> backgrounds(radii.size());
    for (size_t level = 0; level < radii.size(); ++level) {
        Plane denominator = weight;
        for (int k = 0; k < 3; ++k) denominator = box(denominator, w, h, radii[level]);
        for (int c = 0; c < 3; ++c) {
            if (cancel.load()) return false;
            Plane values(n);
            for (int i = 0; i < n; ++i) values[i] = float((small[i] >> ((2 - c) * 8)) & 255) * weight[i];
            for (int k = 0; k < 3; ++k) values = box(values, w, h, radii[level]);
            for (int i = 0; i < n; ++i) {
                if (useDepth && denominator[i] < .0001f) values[i] = float((small[i] >> ((2 - c) * 8)) & 255);
                else values[i] /= std::max(denominator[i], .0001f);
            }
            backgrounds[level][c] = std::move(values);
        }
    }
    for (int y = 0; y < height; ++y) {
        if (cancel.load()) return false;
        for (int x = 0; x < width; ++x) {
            int i = y * width + x;
            float sx = (x + .5f) * w / width - .5f, sy = (y + .5f) * h / height - .5f;
            float luminance = luma(source[i]);
            float matte = std::min(std::clamp((sample(a.data(), w, h, sx, sy) * luminance + sample(b.data(), w, h, sx, sy) - .03f) / .94f, 0.f, 1.f),
                reach((x + .5f) / width, (y + .5f) / height));
            float wanted = useDepth ? radius * std::clamp(sample(depthA.data(), w, h, sx, sy) * luminance + sample(depthB.data(), w, h, sx, sy), 0.f, 1.f) : radius;
            if (useDepth && sample(depth, dw, dh, (x + .5f) * dw / width - .5f, (y + .5f) * dh / height - .5f) >= focusLow - band)
                wanted = 0;
            size_t level = 0;
            while (level + 1 < radii.size() && wanted > radii[level]) ++level;
            float lower = level ? radii[level - 1] : 0;
            float fraction = std::clamp((wanted - lower) / (radii[level] - lower), 0.f, 1.f);
            uint32_t result = 0xff000000;
            for (int c = 0; c < 3; ++c) {
                int shift = (2 - c) * 8;
                float original = float((source[i] >> shift) & 255);
                float low = level ? sample(backgrounds[level - 1][c].data(), w, h, sx, sy) : original;
                float high = sample(backgrounds[level][c].data(), w, h, sx, sy);
                float value = matte * original + (1 - matte) * ((1 - fraction) * low + fraction * high);
                result |= uint32_t(std::clamp(std::round(value), 0.f, 255.f)) << shift;
            }
            output[i] = result;
        }
    }
    return true;
}
}
