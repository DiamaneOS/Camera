// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace night {
namespace {
constexpr int patterns[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
struct Image {
    int w, h;
    std::vector<float> values;
    Image(int width, int height) : w(width), h(height), values(size_t(width) * height) {}
    float at(int x, int y) const { return values[size_t(y) * w + x]; }
};
struct Shift { float x = 0, y = 0; };
struct Flow {
    int w, h;
    std::vector<Shift> shifts;
    Flow(int width, int height) : w(width), h(height), shifts(size_t(width) * height) {}
    Shift at(float gx, float gy) const {
        // Each alignment tile covers 32 guide pixels, or 128 sensor pixels.
        float x = std::clamp((gx - 16) / 32, 0.f, float(w - 1));
        float y = std::clamp((gy - 16) / 32, 0.f, float(h - 1));
        int ix = int(x), iy = int(y), nx = std::min(ix + 1, w - 1), ny = std::min(iy + 1, h - 1);
        float fx = x - ix, fy = y - iy;
        auto sample = [&](int sx, int sy) { return shifts[size_t(sy) * w + sx]; };
        Shift a = sample(ix, iy), b = sample(nx, iy), c = sample(ix, ny), d = sample(nx, ny);
        return {(1 - fy) * ((1 - fx) * a.x + fx * b.x) + fy * ((1 - fx) * c.x + fx * d.x),
                (1 - fy) * ((1 - fx) * a.y + fx * b.y) + fy * ((1 - fx) * c.y + fx * d.y)};
    }
};

float shadingGain(const Frame& frame, float x, float y, int site) {
    const auto& s = frame.shading;
    if (!s.gains) return 1.f;
    float gx = std::clamp((x - s.left) / (s.right - s.left - 1), 0.f, 1.f) * (s.columns - 1);
    float gy = std::clamp((y - s.top) / (s.bottom - s.top - 1), 0.f, 1.f) * (s.rows - 1);
    int ix = int(gx), iy = int(gy), nx = std::min(ix + 1, s.columns - 1), ny = std::min(iy + 1, s.rows - 1);
    float fx = gx - ix, fy = gy - iy;
    auto at = [&](int xx, int yy) { return s.gains[(size_t(yy) * s.columns + xx) * 4 + site]; };
    return (1 - fy) * ((1 - fx) * at(ix, iy) + fx * at(nx, iy)) +
        fy * ((1 - fx) * at(ix, ny) + fx * at(nx, ny));
}

// A separable coordinate lookup avoids repeating grid divisions for every
// Bayer tap. It stores only width+height entries, never a full corrected RAW.
// Constructed after input validation; integer samples retain the same math.
struct Sampler {
    struct Axis { int index, next; float fraction; };
    const Frame& frame;
    std::vector<Axis> xs, ys;
    Sampler(const Frame& f, int width, int height) : frame(f) {
        if (!f.shading.gains) return;
        const auto& s = f.shading;
        auto axis = [](int count, int first, int last, int cells) {
            std::vector<Axis> result; result.reserve(size_t(count));
            for (int p = 0; p < count; ++p) {
                float g = std::clamp((float(p) - first) / (last - first - 1), 0.f, 1.f) * (cells - 1);
                int i = int(g); result.push_back({i, std::min(i + 1, cells - 1), g - i});
            }
            return result;
        };
        xs = axis(width, s.left, s.right, s.columns);
        ys = axis(height, s.top, s.bottom, s.rows);
    }
    float gain(int x, int y, int site) const {
        const auto& s = frame.shading;
        if (!s.gains) return 1.f;
        const auto& xx = xs[size_t(x)]; const auto& yy = ys[size_t(y)];
        auto at = [&](int gx, int gy) { return s.gains[(size_t(gy) * s.columns + gx) * 4 + site]; };
        return (1 - yy.fraction) * ((1 - xx.fraction) * at(xx.index, yy.index) + xx.fraction * at(xx.next, yy.index)) +
            yy.fraction * ((1 - xx.fraction) * at(xx.index, yy.next) + xx.fraction * at(xx.next, yy.next));
    }
};

float sensorValue(const Frame& frame, int x, int y) {
    const uint8_t* p = frame.data + size_t(y) * frame.rowStride + size_t(x) * 2;
    unsigned value = unsigned(p[0]) | unsigned(p[1]) << 8;
    float black = frame.black[(y & 1) * 2 + (x & 1)];
    return (float(value) - black) / (frame.white - black);
}

float sensorPixel(const Sampler& sampler, int x, int y) {
    float value = sensorValue(sampler.frame, x, y);
    return sampler.frame.shading.gains ? value * sampler.gain(x, y, (y & 1) * 2 + (x & 1)) : value;
}

float pixelVariance(const Sampler& sampler, int x, int y, float value) {
    int site = (y & 1) * 2 + (x & 1);
    auto noise = sampler.frame.noise[size_t(site)];
    float gain = sampler.gain(x, y, site);
    return std::max(noise.slope * gain * std::max(0.f, value) + noise.offset * gain * gain, 1e-8f);
}

float pixel(const Sampler& sampler, int x, int y, int width, int height) {
    const auto& frame = sampler.frame;
    const auto& grid=frame.periodicGrid;
    if(grid[0] && x>=2 && y>=2 && x+2<width && y+2<height && x%grid[0]==grid[2] && y%grid[1]==grid[3]) {
        // A qualified periodic sensor site is reconstructed from the same Bayer colour.
        // The bounded median protects against one unrelated edge or noisy neighbour.
        std::array<float,4> same={sensorPixel(sampler,x-2,y),sensorPixel(sampler,x+2,y),sensorPixel(sampler,x,y-2),sensorPixel(sampler,x,y+2)};
        std::sort(same.begin(),same.end());return (same[1]+same[2])*.5f;
    }
    float sensor = sensorValue(frame, x, y);
    float value = frame.shading.gains ? sensor * sampler.gain(x, y, (y & 1) * 2 + (x & 1)) : sensor;
    if (sensor < .98f || x < 2 || y < 2 || x + 2 >= width || y + 2 >= height) return value;
    // Suppress only an isolated saturated sensor site. A real white point or
    // fine line has neighbouring support; ordinary noise/details are untouched.
    std::array<float, 8> same{};
    int index = 0;
    for (int dy = -2; dy <= 2; dy += 2) for (int dx = -2; dx <= 2; dx += 2) {
        if (!dx && !dy) continue;
        float neighbour = sensorPixel(sampler, x + dx, y + dy);
        if (sensorValue(frame, x + dx, y + dy) >= .5f) return value;
        same[size_t(index++)] = neighbour;
    }
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx)
        if ((dx || dy) && sensorValue(frame, x + dx, y + dy) >= .25f) return value;
    std::nth_element(same.begin(), same.begin() + 4, same.end());
    return same[4];
}

Image makeGuide(const Sampler& sampler, int width, int height, const std::atomic_bool& cancelled) {
    Image guide(width / 4, height / 4);
    for (int y = 0; y < guide.h; ++y) {
        if (cancelled.load(std::memory_order_relaxed)) return guide;
        for (int x = 0; x < guide.w; ++x) {
            float sum = 0;
            for (int dy = 0; dy < 4; ++dy)
                for (int dx = 0; dx < 4; ++dx) sum += pixel(sampler, x * 4 + dx, y * 4 + dy, width, height);
            guide.values[size_t(y) * guide.w + x] = std::max(0.f, sum / 16);
        }
    }
    return guide;
}

Image half(const Image& source) {
    Image output(source.w / 2, source.h / 2);
    for (int y = 0; y < output.h; ++y)
        for (int x = 0; x < output.w; ++x)
            output.values[size_t(y) * output.w + x] = (source.at(x * 2, y * 2) + source.at(x * 2 + 1, y * 2) +
                source.at(x * 2, y * 2 + 1) + source.at(x * 2 + 1, y * 2 + 1)) * .25f;
    return output;
}

float cost(const Image& a, const Image& b, int dx, int dy, int x0, int y0, int x1, int y1, int step) {
    if (x0 + dx < 0 || y0 + dy < 0 || x1 + dx > b.w || y1 + dy > b.h || x1 <= x0 || y1 <= y0)
        return std::numeric_limits<float>::infinity();
    double sum = 0;
    int count = 0;
    for (int y = y0; y < y1; y += step) {
        for (int x = x0; x < x1; x += step) {
            float delta = a.at(x, y) - b.at(x + dx, y + dy);
            sum += std::min(delta * delta, .01f);
            ++count;
        }
    }
    return count ? float(sum / count) : std::numeric_limits<float>::infinity();
}

Shift global(const Image& a, const Image& b) {
    std::vector<Image> pa{a}, pb{b};
    while (std::min(pa.back().w, pa.back().h) > 64) {
        pa.push_back(half(pa.back())); pb.push_back(half(pb.back()));
    }
    Shift result;
    for (int level = int(pa.size()) - 1; level >= 0; --level) {
        if (level != int(pa.size()) - 1) { result.x *= 2; result.y *= 2; }
        const auto& reference = pa[size_t(level)];
        const auto& alternate = pb[size_t(level)];
        int radius = level == int(pa.size()) - 1 ? 8 : 2;
        int cx = int(std::round(result.x)), cy = int(std::round(result.y));
        int margin = std::max(std::abs(cx), std::abs(cy)) + radius + 1;
        int step = std::max(1, std::min(reference.w, reference.h) / 64);
        float best = std::numeric_limits<float>::infinity();
        for (int y = cy - radius; y <= cy + radius; ++y) {
            for (int x = cx - radius; x <= cx + radius; ++x) {
                float value = cost(reference, alternate, x, y, margin, margin, reference.w - margin, reference.h - margin, step);
                // Prefer smaller motion on an exact tie in a flat/repeated image.
                if (value < best || (value == best && x * x + y * y < result.x * result.x + result.y * result.y)) {
                    best = value; result = {float(x), float(y)};
                }
            }
        }
    }
    if (std::max(std::abs(result.x), std::abs(result.y)) > std::min(a.w, a.h) / 8) return {};
    return result;
}

Flow align(const Image& a, const Image& b, const Frame& reference, const std::atomic_bool& cancelled) {
    Shift initial = global(a, b);
    Flow flow((a.w + 31) / 32, (a.h + 31) / 32);
    float noiseFloor = 0;
    for (const auto& n : reference.noise) noiseFloor += n.offset / 64;
    noiseFloor = std::max(noiseFloor, 1e-8f);
    for (int ty = 0; ty < flow.h && !cancelled.load(std::memory_order_relaxed); ++ty) {
        for (int tx = 0; tx < flow.w; ++tx) {
            int x0 = tx * 32, y0 = ty * 32, x1 = std::min(x0 + 32, a.w), y1 = std::min(y0 + 32, a.h);
            double sum = 0, squares = 0;
            for (int y = y0; y < y1; ++y) for (int x = x0; x < x1; ++x) {
                float value = a.at(x, y); sum += value; squares += value * value;
            }
            double samples = (x1 - x0) * (y1 - y0);
            float variance = float(squares / samples - (sum / samples) * (sum / samples));
            float tileNoiseFloor = noiseFloor;
            if (reference.shading.gains) {
                tileNoiseFloor = 0;
                for (int site = 0; site < 4; ++site) {
                    float gain = shadingGain(reference, float((x0 + x1) * 2), float((y0 + y1) * 2), site);
                    const auto noise = reference.noise[size_t(site)];
                    tileNoiseFloor += (noise.slope * gain * std::max(0.f, float(sum / samples)) +
                        noise.offset * gain * gain) / 64;
                }
                tileNoiseFloor = std::max(tileNoiseFloor, 1e-8f);
            }
            Shift chosen = initial;
            if (variance > 3 * tileNoiseFloor) {
                float best = std::numeric_limits<float>::infinity();
                int cx = int(std::round(initial.x)), cy = int(std::round(initial.y));
                // Use the same valid overlap for every candidate, including
                // the neighbours used for refinement. Border tiles otherwise
                // discard real motion simply because one edge leaves the frame.
                int sx0 = std::max(x0, 4 - cx), sy0 = std::max(y0, 4 - cy);
                int sx1 = std::min(x1, b.w - cx - 4), sy1 = std::min(y1, b.h - cy - 4);
                if (sx1 - sx0 < 8 || sy1 - sy0 < 8) {
                    flow.shifts[size_t(ty) * flow.w + tx] = chosen;
                    continue;
                }
                for (int y = cy - 3; y <= cy + 3; ++y) for (int x = cx - 3; x <= cx + 3; ++x) {
                    float value = cost(a, b, x, y, sx0, sy0, sx1, sy1, 1);
                    if (value < best) { best = value; chosen = {float(x), float(y)}; }
                }
                // Refining an already noise-limited integer match invents a
                // fractional shift from noise/asymmetric edge costs and blurs
                // the alternate. Keep that exact match on either RAW path.
                if (std::isfinite(best) && best > 3 * tileNoiseFloor) {
                    int x = int(chosen.x), y = int(chosen.y);
                    float left = cost(a, b, x - 1, y, sx0, sy0, sx1, sy1, 1);
                    float right = cost(a, b, x + 1, y, sx0, sy0, sx1, sy1, 1);
                    float up = cost(a, b, x, y - 1, sx0, sy0, sx1, sy1, 1);
                    float down = cost(a, b, x, y + 1, sx0, sy0, sx1, sy1, 1);
                    auto sub = [&](float l, float r) {
                        float denominator = l - 2 * best + r;
                        return std::isfinite(denominator) && denominator > 1e-12f ?
                            std::clamp(.5f * (l - r) / denominator, -.5f, .5f) : 0.f;
                    };
                    chosen.x += sub(left, right); chosen.y += sub(up, down);
                }
            }
            flow.shifts[size_t(ty) * flow.w + tx] = chosen;
        }
    }
    // Reject isolated tile matches before interpolating a smooth motion field.
    auto original = flow.shifts;
    for (int y = 0; y < flow.h; ++y) for (int x = 0; x < flow.w; ++x) {
        std::array<float, 9> xs{}, ys{};
        int n = 0;
        for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
            auto shift = original[size_t(std::clamp(y + dy, 0, flow.h - 1)) * flow.w + std::clamp(x + dx, 0, flow.w - 1)];
            xs[size_t(n)] = shift.x; ys[size_t(n++)] = shift.y;
        }
        std::nth_element(xs.begin(), xs.begin() + 4, xs.end());
        std::nth_element(ys.begin(), ys.begin() + 4, ys.end());
        flow.shifts[size_t(y) * flow.w + x] = {xs[4], ys[4]};
    }
    return flow;
}

bool sample(const Sampler& sampler, const Config& cfg, int x, int y, Shift shift, float& output, float* variance = nullptr) {
    // Interpolate each Bayer colour plane separately. Guide pixels cover 4 sensor pixels.
    int px = x & 1, py = y & 1;
    float sx = (x - px) * .5f + shift.x * 2;
    float sy = (y - py) * .5f + shift.y * 2;
    int width = cfg.width / 2, height = cfg.height / 2;
    if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) return false;
    int ix = int(sx), iy = int(sy), nx = std::min(ix + 1, width - 1), ny = std::min(iy + 1, height - 1);
    float fx = sx - ix, fy = sy - iy;
    int xs[4] = {ix * 2 + px, nx * 2 + px, ix * 2 + px, nx * 2 + px};
    int ys[4] = {iy * 2 + py, iy * 2 + py, ny * 2 + py, ny * 2 + py};
    float weights[4] = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
    float values[4]{};
    for (int i = 0; i < 4; ++i) if (weights[i] > 0)
        values[i] = pixel(sampler, xs[i], ys[i], cfg.width, cfg.height);
    output = (1 - fy) * ((1 - fx) * values[0] + fx * values[1]) +
        fy * ((1 - fx) * values[2] + fx * values[3]);
    if (variance) {
        // Boundary taps may refer to the same sample; merge their weights before
        // computing variance rather than assuming duplicated samples independent.
        for (int i = 0; i < 4; ++i) for (int j = 0; j < i; ++j)
            if (xs[i] == xs[j] && ys[i] == ys[j]) { weights[j] += weights[i]; weights[i] = 0; break; }
        *variance = 0;
        for (int i = 0; i < 4; ++i) if (weights[i] > 0)
            *variance += weights[i] * weights[i] * pixelVariance(sampler, xs[i], ys[i], values[i]);
    }
    return true;
}

int mirror(int x, int size) { return x < 0 ? -x : x >= size ? size * 2 - 2 - x : x; }

float filter(const float* raw, const Config& cfg, int x, int y, const float* kernel, bool transpose = false) {
    float sum = 0;
    for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
        float k = transpose ? kernel[(dx + 2) * 5 + dy + 2] : kernel[(dy + 2) * 5 + dx + 2];
        if (k) sum += k * raw[size_t(mirror(y + dy, cfg.height)) * cfg.width + mirror(x + dx, cfg.width)];
    }
    return sum / 8;
}

bool spatialDenoise(std::vector<float>& merged, const Frame* frames, int count,
                    const Config& cfg, double effective,
                    const std::atomic_bool& cancelled) {
    // Overlapping orthonormal DCT Wiener shrinkage in each Bayer colour plane.
    // Preserve the DC coefficient and strong structure; suppress only AC energy
    // comparable to the calibrated residual noise after temporal fusion.
    const int width = cfg.width / 2, height = cfg.height / 2;
    float transform[8][8], window[8], frequencyPenalty[8][8];
    constexpr float pi = 3.14159265358979323846f;
    for (int k = 0; k < 8; ++k) for (int x = 0; x < 8; ++x)
        transform[k][x] = std::sqrt(k ? 2.f / 8 : 1.f / 8) * std::cos(pi * (2 * x + 1) * k / 16);
    for (int x = 0; x < 8; ++x) {
        float value = std::sin(pi * (x + .5f) / 8);
        window[x] = value * value;
    }
    for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x)
        frequencyPenalty[y][x] = .5f + std::sqrt(float(x * x + y * y)) / 7.f;
    auto reflect = [](int x, int size) {
        const int period = 2 * (size - 1);
        x %= period;
        if (x < 0) x += period;
        return x < size ? x : period - x;
    };
    const float effectiveFrames = float(std::max(1., effective));
    // Process one plane at a time: the two accumulation buffers use at most
    // 32 MB in total at the input pixel limit. Input RAW buffers stay read-only.
    std::vector<float> sums(size_t(width) * height), weights(sums.size());
    for (int site = 0; site < 4; ++site) {
        float slope = 0, offset = 0;
        for (int i = 0; i < count; ++i) {
            slope += frames[i].noise[size_t(site)].slope / count;
            offset += frames[i].noise[size_t(site)].offset / count;
        }
        if (slope == 0 && offset == 0) continue;
        std::fill(sums.begin(), sums.end(), 0);
        std::fill(weights.begin(), weights.end(), 0);
        for (int by = -4; by < height; by += 4) {
            if (cancelled.load(std::memory_order_relaxed)) return false;
            for (int bx = -4; bx < width; bx += 4) {
                float input[8][8], temporary[8][8], coefficients[8][8], mean = 0;
                for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x) {
                    float value = merged[size_t(reflect(by + y, height) * 2 + site / 2) * cfg.width +
                                         reflect(bx + x, width) * 2 + site % 2];
                    input[y][x] = value;
                    mean += value / 64;
                }
                for (int y = 0; y < 8; ++y) for (int k = 0; k < 8; ++k) {
                    float sum = 0;
                    for (int x = 0; x < 8; ++x) sum += transform[k][x] * input[y][x];
                    temporary[y][k] = sum;
                }
                float localSlope = slope, localOffset = offset;
                if (std::any_of(frames, frames + count, [](const Frame& f) { return f.shading.gains != nullptr; })) {
                    localSlope = 0; localOffset = 0;
                    float xx = float(reflect(bx + 4, width) * 2 + site % 2);
                    float yy = float(reflect(by + 4, height) * 2 + site / 2);
                    for (int i = 0; i < count; ++i) {
                        float gain = shadingGain(frames[i], xx, yy, site);
                        localSlope += frames[i].noise[size_t(site)].slope * gain / count;
                        localOffset += frames[i].noise[size_t(site)].offset * gain * gain / count;
                    }
                }
                const float variance = (localSlope * std::max(mean, 0.f) + localOffset) / effectiveFrames;
                for (int ky = 0; ky < 8; ++ky) for (int kx = 0; kx < 8; ++kx) {
                    float sum = 0;
                    for (int y = 0; y < 8; ++y) sum += transform[ky][y] * temporary[y][kx];
                    if (kx || ky) {
                        const float power = sum * sum;
                        // A continuous Wiener response retains weak coefficients rather
                        // than introducing a hard cutoff. Increase the noise penalty
                        // gradually with spatial frequency; leave DC unchanged.
                        sum *= cfg.frequencyDenoise ?
                            power / std::max(power + frequencyPenalty[ky][kx] * variance, 1e-20f) :
                            std::max(0.f, 1 - .5f * variance / std::max(power, 1e-20f));
                    }
                    coefficients[ky][kx] = sum;
                }
                for (int ky = 0; ky < 8; ++ky) for (int x = 0; x < 8; ++x) {
                    float sum = 0;
                    for (int kx = 0; kx < 8; ++kx) sum += transform[kx][x] * coefficients[ky][kx];
                    temporary[ky][x] = sum;
                }
                for (int y = 0; y < 8; ++y) for (int x = 0; x < 8; ++x) {
                    int xx = bx + x, yy = by + y;
                    if (xx < 0 || yy < 0 || xx >= width || yy >= height) continue;
                    float sum = 0;
                    for (int ky = 0; ky < 8; ++ky) sum += transform[ky][y] * temporary[ky][x];
                    float weight = window[y] * window[x];
                    sums[size_t(yy) * width + xx] += weight * sum;
                    weights[size_t(yy) * width + xx] += weight;
                }
            }
        }
        for (int y = 0; y < height; ++y) {
            if (cancelled.load(std::memory_order_relaxed)) return false;
            for (int x = 0; x < width; ++x)
                merged[size_t(y * 2 + site / 2) * cfg.width + x * 2 + site % 2] =
                    sums[size_t(y) * width + x] / std::max(weights[size_t(y) * width + x], 1e-10f);
        }
    }
    return true;
}
bool validConfig(const Config& cfg) {
    if(cfg.width<8 || cfg.height<8 || cfg.width>8192 || cfg.height>8192 || cfg.width%4 || cfg.height%4 ||
        size_t(cfg.width)*cfg.height>16'000'000 || cfg.cfa<0 || cfg.cfa>3 ||
        !std::isfinite(cfg.exposure) || cfg.exposure<.125f || cfg.exposure>32)return false;
    for(float value:cfg.color)if(!std::isfinite(value) || std::abs(value)>32)return false;
    return true;
}

bool developImpl(const float* raw,const Config& cfg,uint32_t* argb,const std::atomic_bool& cancelled) {
    const float green[25] = {0, 0, -1, 0, 0, 0, 0, 2, 0, 0, -1, 2, 4, 2, -1, 0, 0, 2, 0, 0, 0, 0, -1, 0, 0};
    const float horizontal[25] = {0, 0, .5f, 0, 0, 0, -1, 0, -1, 0, -1, 4, 5, 4, -1, 0, -1, 0, -1, 0, 0, 0, .5f, 0, 0};
    const float opposite[25] = {0, 0, -1.5f, 0, 0, 0, 2, 0, 2, 0, -1.5f, 0, 6, 0, -1.5f, 0, 2, 0, 2, 0, 0, 0, -1.5f, 0, 0};
    std::array<uint16_t, 16385> gamma{};
    for (size_t i = 0; i < gamma.size(); ++i) {
        float value = float(i) / 16384;
        value = value <= .0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - .055f;
        gamma[i] = uint16_t(std::round(std::clamp(value, 0.f, 1.f) * (cfg.highPrecision ? 1023 : 255)));
    }
    std::vector<float> greens(cfg.edgeAwareDemosaic ? size_t(cfg.width)*cfg.height : 0);
    auto rawAt=[&](int x,int y) {return raw[size_t(mirror(y,cfg.height))*cfg.width+mirror(x,cfg.width)];};
    if(cfg.edgeAwareDemosaic)for(int y=0;y<cfg.height;++y) {
        if(cancelled.load(std::memory_order_relaxed))return false;
        for(int x=0;x<cfg.width;++x) {
            const int colour=patterns[cfg.cfa][(y&1)*2+(x&1)];float g=rawAt(x,y);
            if(colour!=1) {
                float gl=rawAt(x-1,y),gr=rawAt(x+1,y),gu=rawAt(x,y-1),gd=rawAt(x,y+1);
                float hl=2*g-rawAt(x-2,y)-rawAt(x+2,y),vl=2*g-rawAt(x,y-2)-rawAt(x,y+2);
                float gh=(gl+gr)*.5f+hl*.25f,gv=(gu+gd)*.5f+vl*.25f;
                float dh=std::abs(gl-gr)+std::abs(hl),dv=std::abs(gu-gd)+std::abs(vl);
                g=(dh+1e-8f)*1.5f<dv?gh:(dv+1e-8f)*1.5f<dh?gv:(gh+gv)*.5f;
            }
            greens[size_t(y)*cfg.width+x]=g;
        }
    }
    auto greenAt=[&](int x,int y) {return greens[size_t(mirror(y,cfg.height))*cfg.width+mirror(x,cfg.width)];};
    for (int y = 0; y < cfg.height; ++y) {
        if (cancelled.load(std::memory_order_relaxed)) return false;
        for (int x = 0; x < cfg.width; ++x) {
            int site = (y & 1) * 2 + (x & 1), colour = patterns[cfg.cfa][site];
            std::array<float, 3> camera{}, rgb{};
            if(cfg.edgeAwareDemosaic) {
            camera[1]=greenAt(x,y);
            for(int c : {0,2}) {
                if(c==colour) {camera[size_t(c)]=rawAt(x,y);continue;}
                float sum=0,weight=0;
                auto add=[&](int dx,int dy) {float g=greenAt(x+dx,y+dy);float w=1.f/(1e-6f+std::abs(camera[1]-g));sum+=w*(rawAt(x+dx,y+dy)-g);weight+=w;};
                if(colour==1) {
                    if(patterns[cfg.cfa][(y&1)*2+((x^1)&1)]==c) {add(-1,0);add(1,0);} else {add(0,-1);add(0,1);}
                } else {add(-1,-1);add(1,-1);add(-1,1);add(1,1);}
                camera[size_t(c)]=camera[1]+sum/weight;
            }
            } else {
            for (int c = 0; c < 3; ++c) {
                if (c == colour) camera[size_t(c)] = raw[size_t(y) * cfg.width + x];
                else if (c == 1) camera[size_t(c)] = filter(raw, cfg, x, y, green);
                else if (colour != 1) camera[size_t(c)] = filter(raw, cfg, x, y, opposite);
                else camera[size_t(c)] = filter(raw, cfg, x, y, horizontal, patterns[cfg.cfa][(y & 1) * 2 + (1 - (x & 1))] != c);
            }
            }
            for (int c = 0; c < 3; ++c)
                rgb[size_t(c)] = std::max(0.f, cfg.exposure * (cfg.color[size_t(c) * 3] * camera[0] + cfg.color[size_t(c) * 3 + 1] * camera[1] + cfg.color[size_t(c) * 3 + 2] * camera[2]));
            float luminance = .2126f * rgb[0] + .7152f * rgb[1] + .0722f * rgb[2];
            uint32_t packed = cfg.highPrecision ? 0xc0000000 : 0xff000000;
            for (int c = 0; c < 3; ++c) {
                float mapped = std::clamp(rgb[size_t(c)] / (1 + luminance), 0.f, 1.f);
                packed |= uint32_t(gamma[size_t(std::round(mapped * 16384))]) << ((2 - c) * (cfg.highPrecision ? 10 : 8));
            }
            argb[size_t(y) * cfg.width + x] = packed;
        }
    }
    return true;
}
}

bool develop(const float* raw,const Config& cfg,uint32_t* argb,const std::atomic_bool& cancelled) {
    if(!raw || !argb || !validConfig(cfg))return false;
    for(int y=0;y<cfg.height;++y) {
        if(cancelled.load(std::memory_order_relaxed))return false;
        for(int x=0;x<cfg.width;++x) {
            float value=raw[size_t(y)*cfg.width+x];
            if(!std::isfinite(value) || std::abs(value)>65535.f)return false;
        }
    }
    return developImpl(raw,cfg,argb,cancelled);
}

bool fuse(const Frame* frames, int count, const Config& cfg, float* merged,
             const std::atomic_bool& cancelled, Stats& stats) {
    if (!frames || !merged || count < 1 || count > 8 || !validConfig(cfg) || cfg.reference < 0 || cfg.reference >= count) return false;
    uint64_t rawBytes = 0;
    bool hasShading = false;
    for (int i = 0; i < count; ++i) {
        const auto& f = frames[i];
        const auto& shading = f.shading;
        if (shading.gains) {
            if (shading.columns < 1 || shading.columns > 64 || shading.rows < 1 || shading.rows > 64 ||
                shading.gainCount != size_t(shading.columns) * shading.rows * 4 || shading.left < 0 || shading.top < 0 ||
                shading.left >= cfg.width || shading.top >= cfg.height ||
                shading.right > cfg.width || shading.bottom > cfg.height ||
                shading.right <= shading.left + 1 || shading.bottom <= shading.top + 1) return false;
            for (size_t j = 0; j < shading.gainCount; ++j)
                if (!std::isfinite(shading.gains[j]) || shading.gains[j] < 1 || shading.gains[j] > 32) return false;
            hasShading = true;
        } else if (shading.gainCount || shading.columns || shading.rows) return false;
        const auto& grid=f.periodicGrid;
        if(grid[0]!=0 || grid[1]!=0 || grid[2]!=0 || grid[3]!=0) {
            if(grid[0]<4 || grid[0]>32 || grid[1]<4 || grid[1]>32 || grid[0]%2 || grid[1]%2 ||
                grid[2]<0 || grid[2]>=grid[0] || grid[3]<0 || grid[3]>=grid[1]) return false;
        }
        rawBytes += f.bytes;
        if (!f.data || rawBytes > 240 * 1024 * 1024 || f.rowStride < cfg.width * 2 ||
            f.rowStride > cfg.width * 2 + 4096 || f.rowStride % 2 ||
            size_t(f.rowStride) * (cfg.height - 1) + size_t(cfg.width) * 2 > f.bytes ||
            !std::isfinite(f.white) || f.white < 1 || f.white > 65535) return false;
        for (int c = 0; c < 4; ++c) {
            if (!std::isfinite(f.black[size_t(c)]) || f.black[size_t(c)] < 0 || f.black[size_t(c)] > f.white - 1 ||
                !std::isfinite(f.noise[size_t(c)].slope) || !std::isfinite(f.noise[size_t(c)].offset) ||
                f.noise[size_t(c)].slope < 0 || f.noise[size_t(c)].offset < 0 ||
                f.noise[size_t(c)].slope > 16 || f.noise[size_t(c)].offset > 16) return false;
        }
    }
    std::vector<Sampler> samplers;
    samplers.reserve(size_t(count));
    for (int i = 0; i < count; ++i) samplers.emplace_back(frames[i], cfg.width, cfg.height);
    const auto& referenceSampler = samplers[size_t(cfg.reference)];
    std::vector<Image> guides;
    guides.reserve(size_t(count));
    for (int i = 0; count>1 && i < count; ++i) {
        if (cancelled.load(std::memory_order_relaxed)) return false;
        guides.push_back(makeGuide(samplers[size_t(i)], cfg.width, cfg.height, cancelled));
    }
    std::vector<Flow> flows;
    flows.reserve(size_t(count));
    for (int i = 0; i < count; ++i) {
        if (cancelled.load(std::memory_order_relaxed)) return false;
        flows.push_back(i == cfg.reference ? Flow(1, 1) : align(guides[size_t(cfg.reference)], guides[size_t(i)], frames[cfg.reference], cancelled));
    }
    double effective = 0;
    for (int y = 0; y < cfg.height; ++y) {
        if (cancelled.load(std::memory_order_relaxed)) return false;
        for (int x = 0; x < cfg.width; ++x) {
            float reference = pixel(referenceSampler, x, y, cfg.width, cfg.height), sum = reference, weight = 1;
            int site = (y & 1) * 2 + (x & 1);
            float referenceGain = hasShading ? referenceSampler.gain(x, y, site) : 1.f;
            float referenceVariance = hasShading ? pixelVariance(referenceSampler, x, y, reference) : 0.f;
            for (int i = 0; i < count; ++i) {
                if (i == cfg.reference) continue;
                float value, alternateVariance = 0;
                const auto shift = flows[size_t(i)].at(x * .25f, y * .25f);
                if (!sample(samplers[size_t(i)], cfg, x, y, shift, value, hasShading ? &alternateVariance : nullptr)) continue;
                float alternateGain = 1.f;
                if (hasShading) {
                    float sx = x + shift.x * 4, sy = y + shift.y * 4;
                    alternateGain = sx == int(sx) && sy == int(sy) ?
                        samplers[size_t(i)].gain(int(sx), int(sy), site) : shadingGain(frames[i], sx, sy, site);
                }
                if (value >= .995f * alternateGain || reference >= .995f * referenceGain) continue;
                auto a = frames[i].noise[size_t(site)], b = frames[cfg.reference].noise[size_t(site)];
                float variance = std::max((a.slope + b.slope) * std::max(0.f, reference) + a.offset + b.offset, 1e-8f);
                if (hasShading) variance = std::max(alternateVariance + referenceVariance, 1e-8f);
                float difference = value - reference;
                float robust = std::min(1.f, 9 * variance / std::max(difference * difference, 1e-12f));
                robust *= robust;
                sum += value * robust; weight += robust;
            }
            merged[size_t(y) * cfg.width + x] = sum / weight;
            effective += weight;
        }
    }
    stats.effectiveFrames = effective / (double(cfg.width) * cfg.height);
    stats.inputFrames = count;
    return true;
}

bool process(const Frame* frames,int count,const Config& cfg,uint32_t* argb,
             const std::atomic_bool& cancelled,Stats& stats) {
    if(!frames || !argb || count<1 || count>8 || !validConfig(cfg))return false;
    std::vector<float> merged(size_t(cfg.width)*cfg.height);
    if(!fuse(frames,count,cfg,merged.data(),cancelled,stats))return false;
    if(cfg.spatialDenoise && !spatialDenoise(merged,frames,count,cfg,stats.effectiveFrames,cancelled))return false;
    return developImpl(merged.data(),cfg,argb,cancelled);

}
}
