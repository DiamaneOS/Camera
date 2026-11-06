// SPDX-License-Identifier: MIT
#include "nightfinish.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <thread>
#include <vector>

namespace night {
namespace {
using Plane = std::vector<float>;
struct Ycc { float y, co, cg; };
Ycc convert(uint32_t p, bool highPrecision) {
    float r = highPrecision ? float((p >> 20) & 1023) * (255.f/1023) : float((p >> 16) & 255);
    float g = highPrecision ? float((p >> 10) & 1023) * (255.f/1023) : float((p >> 8) & 255);
    float b = highPrecision ? float(p & 1023) * (255.f/1023) : float(p & 255);
    return {(r + 2 * g + b) * .25f, (r - b) * .5f, (2 * g - r - b) * .25f};
}
float sample(const Plane& p, int w, int h, float x, float y) {
    x = std::clamp(x, 0.f, float(w - 1)); y = std::clamp(y, 0.f, float(h - 1));
    int ix = int(x), iy = int(y), jx = std::min(ix + 1, w - 1), jy = std::min(iy + 1, h - 1);
    float u = x - ix, v = y - iy;
    return (1 - v) * ((1 - u) * p[iy * w + ix] + u * p[iy * w + jx]) +
        v * ((1 - u) * p[jy * w + ix] + u * p[jy * w + jx]);
}
float sigma(const Plane& p, int w, int h, int spacing = 1) {
    Plane detail;
    detail.reserve(size_t(w / 2) * (h / 2));
    for (int y = 0; y + spacing < h; y += 2 * spacing) for (int x = 0; x + spacing < w; x += 2 * spacing)
        detail.push_back(.5f * std::abs(p[y * w + x] + p[(y + spacing) * w + x + spacing] - p[y * w + x + spacing] - p[(y + spacing) * w + x]));
    if (detail.empty()) return 0;
    auto middle = detail.begin() + detail.size() / 2;
    std::nth_element(detail.begin(), middle, detail.end());
    return *middle / .67449f;
}
template<class Function> void rows(int height, Function fn) {
    std::thread second([&] { for (int y = height / 2; y < height; ++y) fn(y); });
    for (int y = 0; y < height / 2; ++y) fn(y);
    second.join();
}
float curve(float x, float k) { return x / std::max(.0001f, x + k * (1 - x)); }
float decodeSrgb(float v) { return v <= .04045f ? v / 12.92f : std::pow((v + .055f) / 1.055f, 2.4f); }
float encodeSrgb(float v) { return v <= .0031308f ? v * 12.92f : 1.055f * std::pow(v, 1 / 2.4f) - .055f; }
// Linear-light tables for the merged-JPEG finish: decode by gamma code (x4), encode by sqrt(linear).
struct Srgb {
    std::array<float, 1021> decode{};
    std::vector<float> encode = std::vector<float>(16385);
    Srgb() {
        for (int i = 0; i <= 1020; ++i) decode[size_t(i)] = decodeSrgb(i / 1020.f);
        for (int i = 0; i <= 16384; ++i) { float r = i / 16384.f; encode[size_t(i)] = 255 * encodeSrgb(r * r); }
    }
    float toLinear(float code) const {
        float x = std::clamp(code, 0.f, 255.f) * 4; int i = std::min(int(x), 1019); float u = x - i;
        return decode[size_t(i)] + u * (decode[size_t(i + 1)] - decode[size_t(i)]);
    }
    float toCode(float linear) const {
        float x = std::sqrt(std::clamp(linear, 0.f, 1.f)) * 16384; int i = std::min(int(x), 16383); float u = x - i;
        return encode[size_t(i)] + u * (encode[size_t(i + 1)] - encode[size_t(i)]);
    }
};
// The camera's black-level error shows as one linear offset per channel, largest in blue,
// which its gains carry into every pixel. Neutralise it where the scene is darkest, in
// 16x16 blocks of the finished planes, only in a night scene with dark areas in both the
// upper and the lower half that show the same offset. A dark blue sky above a neutral
// ground disagrees and keeps its colour. The offset is at most twice the dark luminance.
BlackPedestal measurePedestal(const Plane& y, const Plane& co, const Plane& cg, int w, int h, const Srgb& srgb) {
    BlackPedestal result;
    constexpr int block = 8;
    struct Cell { float y, r, g, b; bool upper; };
    std::vector<Cell> cells;
    std::vector<float> levels;
    for (int by = 0; by + block <= h; by += block) for (int bx = 0; bx + block <= w; bx += block) {
        double sy = 0, sa = 0, sb = 0;
        for (int yy = by; yy < by + block; ++yy) for (int xx = bx; xx < bx + block; ++xx) {
            int i = yy * w + xx; sy += y[size_t(i)]; sa += co[size_t(i)]; sb += cg[size_t(i)];
        }
        float my = float(sy / (block * block)), ma = float(sa / (block * block)), mb = float(sb / (block * block));
        cells.push_back({my, srgb.toLinear(my + ma - mb), srgb.toLinear(my + mb), srgb.toLinear(my - ma - mb), by + block <= h / 2});
        levels.push_back(my);
    }
    if (cells.size() < 400) return result;
    auto middle = levels.begin() + levels.size() / 2;
    std::nth_element(levels.begin(), middle, levels.end());
    if (*middle > 48) return result;
    auto tenth = levels.begin() + levels.size() / 10;
    std::nth_element(levels.begin(), tenth, levels.end());
    float limit = std::min(*tenth, 24.f);
    // Median excess over luminance per channel (0-2) and the dark luminance (3), per half.
    auto measure = [&](int half, std::array<float, 4>& out) {
        std::array<std::vector<float>, 4> values;
        for (const auto& c : cells) if (c.y <= limit && (half < 0 || c.upper == (half == 0))) {
            float luminance = .2126f * c.r + .7152f * c.g + .0722f * c.b;
            values[0].push_back(c.r - luminance); values[1].push_back(c.g - luminance);
            values[2].push_back(c.b - luminance); values[3].push_back(luminance);
        }
        if (values[0].size() < std::max<size_t>(20, cells.size() / 100)) return false;
        for (int k = 0; k < 4; ++k) {
            auto at = values[size_t(k)].begin() + values[size_t(k)].size() / 2;
            std::nth_element(values[size_t(k)].begin(), at, values[size_t(k)].end());
            out[size_t(k)] = *at;
        }
        return true;
    };
    std::array<float, 4> all{}, upper{}, lower{};
    if (!measure(-1, all) || !measure(0, upper) || !measure(1, lower)) return result;
    float offset[3];
    for (int c = 0; c < 3; ++c) {
        float a = upper[size_t(c)], b = lower[size_t(c)];
        if (std::abs(a - b) > .2f * std::max(std::abs(a), std::abs(b)) + .0002f) return result;
        float cap = std::min(.006f, 2 * all[3]);
        offset[c] = std::clamp(all[size_t(c)], -cap, cap);
    }
    result = {true, offset[0], offset[1], offset[2]};
    return result;
}
bool finishImpl(uint32_t* pixels, int width, int height, float target, const std::atomic_bool& cancelled, float zoom, float aspect, const float* toneTargets, bool spatialNoise, bool highPrecisionInput, bool steadyPhotoFinish, float jpegGain, BlackPedestal* pedestal) {
    if (!pixels || width < 8 || height < 8 || width > 8192 || height > 8192 || int64_t(width) * height > 16'000'000 ||
        !std::isfinite(target) || target < 0 || target > 1 || !std::isfinite(zoom) || zoom < 1 || zoom > 100 || !std::isfinite(aspect) || aspect < 1 || aspect > 2 ||
        !std::isfinite(jpegGain) || jpegGain < 0 || jpegGain > 4) return false;
    if (toneTargets) {
        for (int i = 0; i < 3; ++i)
            if (!std::isfinite(toneTargets[i]) || toneTargets[i] < 0 || toneTargets[i] > 1 || (i && toneTargets[i] < toneTargets[i - 1])) return false;
    }
    int w = (width + 1) / 2, h = (height + 1) / 2, n = w * h;
    Plane luminance(n), co(n), cg(n), smoothCo(n), smoothCg(n), smoothY(n), detailGain(n);
    int histogram[256] = {};
    double mean = 0;
    int samples = 0;
    float cropWidth = std::min(float(width), height * aspect) / zoom;
    float cropHeight = std::min(float(height), width / aspect) / zoom;
    for (int y = 0; y < h; ++y) {
        if (cancelled.load()) return false;
        for (int x = 0; x < w; ++x) {
            Ycc sum{};
            for (int dy = 0; dy < 2; ++dy) for (int dx = 0; dx < 2; ++dx) {
                Ycc p = convert(pixels[std::min(y * 2 + dy,height - 1) * width + std::min(x * 2 + dx,width - 1)],highPrecisionInput);
                sum.y += p.y; sum.co += p.co; sum.cg += p.cg;
            }
            int i = y * w + x;
            luminance[i] = sum.y * .25f; co[i] = sum.co * .25f; cg[i] = sum.cg * .25f;
            if (std::abs((x * 2 + 1) - width * .5f) <= cropWidth * .5f &&
                std::abs((y * 2 + 1) - height * .5f) <= cropHeight * .5f) {
                mean += luminance[i]; ++histogram[std::clamp(int(std::round(luminance[i])), 0, 255)]; ++samples;
            }
        }
    }
    float chromaNoise = std::clamp(std::max({sigma(co, w, h), sigma(cg, w, h),
        sigma(co, w, h, 2), sigma(cg, w, h, 2), sigma(co, w, h, 4), sigma(cg, w, h, 4)}), .75f, 16.f);
    float lumaNoise = std::clamp(std::max({sigma(luminance, w, h), sigma(luminance, w, h, 2), sigma(luminance, w, h, 4)}), 1.f, 20.f);
    // Robust impulse rejection before local variance estimation. An isolated
    // colour/luma spike otherwise looks like detail and survives Wiener filtering.
    // Opposite-neighbour support preserves thin coherent lines and edges.
    if (chromaNoise > 1.5f || lumaNoise > 2.f) {
        rows(h, [&](int y) {
            if (cancelled.load()) return;
            for (int x = 0; x < w; ++x) {
                int i=y*w+x; std::array<float,9> ys{},cs{},gs{};int k=0;
                for (int dy=-1;dy<=1;++dy) for (int dx=-1;dx<=1;++dx) {
                    int j=std::clamp(y+dy,0,h-1)*w+std::clamp(x+dx,0,w-1);
                    ys[k]=luminance[j];cs[k]=co[j];gs[k]=cg[j];++k;
                }
                auto opposite=[&](bool colour) {
                    constexpr int pairs[4][2]={{0,8},{1,7},{2,6},{3,5}};
                    for (auto& pair:pairs) {
                        auto distance=[&](int at) {return colour?std::hypot(cs[at]-co[i],gs[at]-cg[i]):std::abs(ys[at]-luminance[i]);};
                        float threshold=2.5f*(colour?chromaNoise:lumaNoise);
                        if(distance(pair[0])<=threshold && distance(pair[1])<=threshold) return true;
                    }
                    return false;
                };
                bool colorSupport=opposite(true),lumaSupport=opposite(false);
                std::nth_element(cs.begin(),cs.begin()+4,cs.end());std::nth_element(gs.begin(),gs.begin()+4,gs.end());
                std::nth_element(ys.begin(),ys.begin()+4,ys.end());
                bool spike=chromaNoise>1.5f && !colorSupport && std::hypot(co[i]-cs[4],cg[i]-gs[4])>2.5f*chromaNoise;
                smoothCo[i]=spike?cs[4]:co[i];smoothCg[i]=spike?gs[4]:cg[i];
                smoothY[i]=lumaNoise>2.f && !lumaSupport && std::abs(luminance[i]-ys[4])>3*lumaNoise?ys[4]:luminance[i];
            }
        });
        co.swap(smoothCo);cg.swap(smoothCg);luminance.swap(smoothY);
        if (cancelled.load()) return false;
    }
    // A JPEG ISP can leave very different noise across shadows and highlights.
    // Estimate bounded tile noise for the single-JPEG path; RAW retains its
    // existing calibrated finish and avoids these additional working planes.
    Plane localY, localC;
    if (spatialNoise) {
        constexpr int tile = 32;
        const int tw = (w + tile - 1) / tile, th = (h + tile - 1) / tile;
        Plane tileY(tw * th), tileC(tw * th);
        auto estimate = [&](const Plane& plane, int x0, int y0, int x1, int y1, float fallback) {
            float result = 0;
            for (int spacing : {1, 2, 4}) {
                Plane values;
                for (int yy = y0; yy + spacing < y1; yy += 2 * spacing)
                    for (int xx = x0; xx + spacing < x1; xx += 2 * spacing)
                        values.push_back(.5f * std::abs(plane[yy * w + xx] + plane[(yy + spacing) * w + xx + spacing] -
                            plane[yy * w + xx + spacing] - plane[(yy + spacing) * w + xx]));
                if (values.size() >= 16) {
                    auto middle = values.begin() + values.size() / 2;
                    std::nth_element(values.begin(), middle, values.end());
                    result = std::max(result, *middle / .67449f);
                }
            }
            return result > 0 ? result : fallback;
        };
        for (int yy = 0; yy < th; ++yy) {
            if (cancelled.load()) return false;
            for (int xx = 0; xx < tw; ++xx) {
                int x0 = xx * tile, y0 = yy * tile;
                int x1 = std::min(w, x0 + tile), y1 = std::min(h, y0 + tile), at = yy * tw + xx;
                tileY[at] = std::clamp(estimate(luminance, x0, y0, x1, y1, lumaNoise), .5f, std::min(32.f, lumaNoise * 3));
                tileC[at] = std::clamp(std::max(estimate(co, x0, y0, x1, y1, chromaNoise),
                    estimate(cg, x0, y0, x1, y1, chromaNoise)), .5f, std::min(24.f, chromaNoise * 3));
            }
        }
        localY.resize(n); localC.resize(n);
        for (int yy = 0; yy < h; ++yy) {
            if (cancelled.load()) return false;
            for (int xx = 0; xx < w; ++xx) {
                float tx = (xx + .5f) / tile - .5f, ty = (yy + .5f) / tile - .5f;
                localY[yy * w + xx] = sample(tileY, tw, th, tx, ty);
                localC[yy * w + xx] = sample(tileC, tw, th, tx, ty);
            }
        }
    }
    auto yn = [&](int i) { return localY.empty() ? lumaNoise : localY[i]; };
    auto cn = [&](int i) { return localC.empty() ? chromaNoise : localC[i]; };
    // Patch similarity rejects unrelated edges while averaging repeated noisy
    // neighborhoods. Work in the bounded half-resolution luminance/chroma planes.
    if (lumaNoise > 1.75f || chromaNoise > 1.5f) {
        Plane sumY=luminance,sumCo=co,sumCg=cg,total(n,1.f),distance(n),horizontal(n);
        const float yNoise2=std::max(1.f,lumaNoise*lumaNoise),cNoise2=std::max(1.f,chromaNoise*chromaNoise);
        for(int dy=-2;dy<=2;++dy) for(int dx=-2;dx<=2;++dx) {
            if(!dx && !dy) continue;
            if(cancelled.load()) return false;
            rows(h,[&](int y) {
                for(int x=0;x<w;++x) {
                    int i=y*w+x,j=std::clamp(y+dy,0,h-1)*w+std::clamp(x+dx,0,w-1);
                    float yy=luminance[i]-luminance[j],cc=co[i]-co[j],gg=cg[i]-cg[j];
                    float vy = localY.empty() ? yNoise2 : std::max(1.f, (yn(i) * yn(i) + yn(j) * yn(j)) * .5f);
                    float vc = localC.empty() ? cNoise2 : std::max(1.f, (cn(i) * cn(i) + cn(j) * cn(j)) * .5f);
                    distance[i] = .5f * (yy * yy / vy + .5f * (cc * cc + gg * gg) / vc);
                }
            });
            rows(h,[&](int y) {
                for(int x=0;x<w;++x) horizontal[y*w+x]=(distance[y*w+std::max(0,x-1)]+2*distance[y*w+x]+distance[y*w+std::min(w-1,x+1)])*.25f;
            });
            rows(h,[&](int y) {
                for(int x=0;x<w;++x) {
                    int i=y*w+x,j=std::clamp(y+dy,0,h-1)*w+std::clamp(x+dx,0,w-1);
                    float patch=(horizontal[std::max(0,y-1)*w+x]+2*horizontal[i]+horizontal[std::min(h-1,y+1)*w+x])*.25f;
                    float weight=std::exp(-std::max(0.f,patch-2.f)/1.44f);
                    sumY[i]+=weight*luminance[j];sumCo[i]+=weight*co[j];sumCg[i]+=weight*cg[j];total[i]+=weight;
                }
            });
        }
        for(int i=0;i<n;++i) {luminance[i]=sumY[i]/total[i];co[i]=sumCo[i]/total[i];cg[i]=sumCg[i]/total[i];}
    }
    constexpr float spatial[5] = {1, 4, 6, 4, 1};
    rows(h, [&](int y) {
        if (cancelled.load()) return;
        for (int x = 0; x < w; ++x) {
            int i = y * w + x;
            float sumCo = 0, sumCg = 0, total = 0, sumY = 0, sumY2 = 0, spatialTotal = 0;
            for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
                int j = std::clamp(y + dy, 0, h - 1) * w + std::clamp(x + dx, 0, w - 1);
                float dc = co[i] - co[j], dg = cg[i] - cg[j], dl = luminance[i] - luminance[j];
                float kernel = spatial[dy + 2] * spatial[dx + 2];
                sumY += kernel * luminance[j]; sumY2 += kernel * luminance[j] * luminance[j]; spatialTotal += kernel;
                float weight = spatial[dy + 2] * spatial[dx + 2] /
                    ((1 + dl * dl / std::max(1.f,6*yn(i)*yn(i))) * (1 + (dc * dc + dg * dg) / std::max(1.f,6*cn(i)*cn(i))));
                sumCo += weight * co[j]; sumCg += weight * cg[j]; total += weight;
            }
            smoothCo[i] = sumCo / total; smoothCg[i] = sumCg / total;
            float localMean = sumY / spatialTotal;
            float localVariance = std::max(0.f, sumY2 / spatialTotal - localMean * localMean);
            // Local Wiener shrinkage retains coherent edges and texture while reducing
            // random luma before a dark scene's tone curve can magnify it.
            float gain = std::clamp(1 - yn(i) * yn(i) / std::max(localVariance, .0001f), .12f, 1.f);
            detailGain[i] = gain;
            smoothY[i] = localMean + gain * (luminance[i] - localMean);
        }
    });
    if (cancelled.load()) return false;
    // A wider second scale treats demosaic-correlated colour blotches that a
    // neighbouring-pixel estimate misses. Low-noise colour remains on one scale.
    if (chromaNoise > 1.5f) for (int spacing : {2, 4}) {
        if (spacing == 4 && chromaNoise < 2.f) break;
        rows(h, [&](int y) {
            if (cancelled.load()) return;
            for (int x = 0; x < w; ++x) {
                int i = y * w + x;
                float sumCo = 0, sumCg = 0, total = 0;
                for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
                    int j = std::clamp(y + spacing * dy, 0, h - 1) * w + std::clamp(x + spacing * dx, 0, w - 1);
                    float dc = smoothCo[i] - smoothCo[j], dg = smoothCg[i] - smoothCg[j];
                    float dl = smoothY[i] - smoothY[j];
                    float weight = spatial[dy + 2] * spatial[dx + 2] /
                        ((1 + dl * dl / std::max(1.f,6*yn(i)*yn(i))) * (1 + (dc * dc + dg * dg) / std::max(1.f,6*cn(i)*cn(i))));
                    sumCo += weight * smoothCo[j]; sumCg += weight * smoothCg[j]; total += weight;
                }
                co[i] = sumCo / total; cg[i] = sumCg / total;
            }
        });
        smoothCo.swap(co); smoothCg.swap(cg);
    }
    if (cancelled.load()) return false;
    float k = 1;
    float current = samples > 0 ? float(mean / (samples * 255.)) : 0;
    if (target > 0 && current > .01f) {
        // Match the preview without forcing every night scene to a fixed middle grey.
        float desired = std::clamp(target, .08f, .75f);
        float low = .4f, high = 2.5f;
        for (int step = 0; step < 12; ++step) {
            float middle = (low + high) * .5f;
            double sum = 0;
            for (int bin = 0; bin < 256; ++bin) sum += histogram[bin] * curve(bin / 255.f, middle);
            if (sum / std::max(1, samples) > desired) low = middle; else high = middle;
        }
        k = (low + high) * .5f;
    }
    // Preserve the viewfinder's black/mid/white relationship for ordinary enhanced photos.
    // A monotone cubic curve avoids hard contrast joins; flat source ranges keep the mean curve.
    std::array<float, 1021> tone{};
    bool ranged = false;
    if (toneTargets && samples > 0) {
        auto quantile = [&](float fraction) {
            int cumulative = 0, wanted = std::max(1, int(std::ceil(samples * fraction)));
            for (int i = 0; i < 256; ++i) { cumulative += histogram[i]; if (cumulative >= wanted) return i / 255.f; }
            return 1.f;
        };
        float x[5] = {0, quantile(.01f), quantile(.5f), quantile(.99f), 1};
        float v[5] = {0, toneTargets[0], toneTargets[1], toneTargets[2], 1};
        ranged = x[1] > .004f && x[2] - x[1] > .004f && x[3] - x[2] > .004f && 1 - x[3] > .004f;
        if (ranged) {
            float slope[4], tangent[5];
            for (int i = 0; i < 4; ++i) slope[i] = (v[i + 1] - v[i]) / (x[i + 1] - x[i]);
            tangent[0] = slope[0]; tangent[4] = slope[3];
            for (int i = 1; i < 4; ++i) tangent[i] = slope[i - 1] > 0 && slope[i] > 0 ?
                std::min(2 * slope[i - 1] * slope[i] / (slope[i - 1] + slope[i]), 3 * std::min(slope[i - 1], slope[i])) : 0;
            for (int i = 0; i <= 1020; ++i) {
                float value = i / 1020.f; int segment = 0;
                while (segment < 3 && value > x[segment + 1]) ++segment;
                float span = x[segment + 1] - x[segment], t = (value - x[segment]) / span, t2 = t * t, t3 = t2 * t;
                tone[size_t(i)] = 255 * std::clamp((2*t3-3*t2+1)*v[segment] + (t3-2*t2+t)*span*tangent[segment] +
                    (-2*t3+3*t2)*v[segment+1] + (t3-t2)*span*tangent[segment+1], v[segment], v[segment+1]);
            }
        }
    }
    std::unique_ptr<Srgb> srgb;
    BlackPedestal black;
    if (jpegGain > 0) {
        srgb = std::make_unique<Srgb>();
        black = measurePedestal(smoothY, smoothCo, smoothCg, w, h, *srgb);
        if (pedestal) *pedestal = black;
    }
    // Shoulder above the knee: slope 1 at the knee, gain times the knee-relative range maps to 1.
    const float knee = .25f, shoulder = jpegGain > 1 ? (jpegGain - 1) / ((1 - knee) * (jpegGain - knee)) : 0;
    rows(height, [&](int y) {
        if (cancelled.load()) return;
        for (int x = 0; x < width; ++x) {
            int i = y * width + x;
            Ycc original = convert(pixels[i],highPrecisionInput);
            float sx = (x + .5f) * .5f - .5f, sy = (y + .5f) * .5f - .5f;
            float a = sample(smoothCo, w, h, sx, sy), b = sample(smoothCg, w, h, sx, sy);
            float chromaHere = localC.empty() ? chromaNoise : sample(localC, w, h, sx, sy);
            float lumaHere = localY.empty() ? lumaNoise : sample(localY, w, h, sx, sy);
            float difference = std::hypot(original.co - a, original.cg - b);
            float blend = std::min(1.f, 4 * chromaHere / std::max(difference, .001f));
            if (chromaHere>1.5f && difference>2*chromaHere) {
                float minCo=255,maxCo=-255,minCg=255,maxCg=-255;
                for(int dy=-1;dy<=1;++dy) for(int dx=-1;dx<=1;++dx) {
                    int j=std::clamp(int(std::round(sy))+dy,0,h-1)*w+std::clamp(int(std::round(sx))+dx,0,w-1);
                    minCo=std::min(minCo,co[j]);maxCo=std::max(maxCo,co[j]);minCg=std::min(minCg,cg[j]);maxCg=std::max(maxCg,cg[j]);
                }
                if(original.co<minCo-2*chromaHere || original.co>maxCo+2*chromaHere ||
                    original.cg<minCg-2*chromaHere || original.cg>maxCg+2*chromaHere) blend=1;
            }
            a = original.co + blend * (a - original.co);
            b = original.cg + blend * (b - original.cg);
            float strength = std::clamp((lumaHere - 1.25f) / 3.f, 0.f, 1.f);
            if (steadyPhotoFinish) {
                // A cleaner, longer exposure must not cross a blend threshold
                // that returns more of its original noise. Keep the structure
                // response and bound the extra strength to this qualified path.
                float structure = sample(detailGain, w, h, sx, sy);
                float flatStrength = (1 - structure * structure) *
                    std::clamp(1 - .5f / std::max(lumaHere, .001f), 0.f, 1.f) *
                    std::clamp((lumaHere - 1.25f) / .75f, 0.f, 1.f);
                strength = std::max(strength, flatStrength);
            }
            float base = sample(luminance, w, h, sx, sy);
            float filtered = sample(smoothY, w, h, sx, sy) + sample(detailGain, w, h, sx, sy) * (original.y - base);
            float cleanY = std::clamp(original.y + strength * (filtered - original.y), 0.f, 255.f);
            float mapped = ranged ? tone[size_t(std::clamp(int(std::round(cleanY * 4)), 0, 1020))] : curve(cleanY / 255.f, k) * 255;
            float scale = cleanY > .001f ? mapped / cleanY : 1;
            float channels[3] = {cleanY + a - b, cleanY + b, cleanY - a - b};
            if (toneTargets) {
                float maximum = std::max({channels[0], channels[1], channels[2]});
                if (maximum * scale > 255) scale = 255 / maximum;
            }
            if (srgb) {
                // Restore the withheld sensor gain on luminance in linear light: the full gain up
                // to the knee, then a shoulder that maps the frames' white to white, so the
                // highlights they still held roll off instead of clipping.
                float linear[3], offsets[3] = {black.red, black.green, black.blue};
                for (int c = 0; c < 3; ++c) linear[c] = srgb->toLinear(channels[c] * scale) - offsets[c];
                float luminance = std::max(0.f, .2126f * linear[0] + .7152f * linear[1] + .0722f * linear[2]);
                // Pixels darker than the offset would lose a channel and turn green or cyan.
                // Keep their luminance and fade them toward grey instead.
                float lowest = std::min({linear[0], linear[1], linear[2]});
                if (lowest < 0) {
                    float keep = luminance > 0 ? luminance / (luminance - lowest) : 0;
                    for (int c = 0; c < 3; ++c) linear[c] = std::max(0.f, luminance + keep * (linear[c] - luminance));
                }
                float lifted = jpegGain * luminance, over = lifted - knee;
                if (over > 0) lifted = knee + over / (1 + over * shoulder);
                float lift = luminance > 1e-7f ? lifted / luminance : jpegGain;
                float peak = std::max({linear[0], linear[1], linear[2]}) * lift;
                if (peak > 1) lift /= peak;
                for (int c = 0; c < 3; ++c) channels[c] = srgb->toCode(linear[c] * lift);
                scale = 1;
            }
            uint32_t packed = 0xff000000;
            for (int c = 0; c < 3; ++c)
                packed |= uint32_t(std::clamp(std::round(channels[c] * scale), 0.f, 255.f)) << ((2 - c) * 8);
            pixels[i] = packed;
        }
    });
    return !cancelled.load();
}
}
bool finish(uint32_t* pixels, int width, int height, float target, const std::atomic_bool& cancelled, float zoom, float aspect, const float* toneTargets, bool spatialNoise, bool highPrecisionInput, bool steadyPhotoFinish) {
    return finishImpl(pixels, width, height, target, cancelled, zoom, aspect, toneTargets, spatialNoise, highPrecisionInput, steadyPhotoFinish, 0, nullptr);
}
bool finishJpeg(uint32_t* pixels, int width, int height, float gain, const std::atomic_bool& cancelled, BlackPedestal* pedestal) {
    if (!std::isfinite(gain) || gain < 1 || gain > 4) return false;
    return finishImpl(pixels, width, height, 0, cancelled, 1, 4.f / 3, nullptr, true, true, false, gain, pedestal);
}
}
