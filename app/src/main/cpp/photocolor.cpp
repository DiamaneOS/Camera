// SPDX-License-Identifier: MIT
#include "photocolor.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace photo {
namespace {
using Color = std::array<double, 3>;
using Matrix = std::array<Color, 3>;
struct Pair { Color source{}, target{}; double weight = 1; };
Color color(uint32_t p) { return {double((p >> 16) & 255) / 255, double((p >> 8) & 255) / 255, double(p & 255) / 255}; }
Color transform(const Matrix& matrix, const Color& value) {
    Color output{};
    for (int c = 0; c < 3; ++c) for (int j = 0; j < 3; ++j) output[c] += matrix[c][j] * value[j];
    return output;
}
double error(const Color& a, const Color& b) {
    double sum = 0; for (int c = 0; c < 3; ++c) sum += (a[c] - b[c]) * (a[c] - b[c]);
    return std::sqrt(sum / 3);
}
bool solve(Matrix matrix, Color values, Color& output) {
    for (int c = 0; c < 3; ++c) {
        int pivot = c;
        for (int row = c + 1; row < 3; ++row) if (std::abs(matrix[row][c]) > std::abs(matrix[pivot][c])) pivot = row;
        if (std::abs(matrix[pivot][c]) < 1e-9) return false;
        std::swap(matrix[c], matrix[pivot]); std::swap(values[c], values[pivot]);
        double scale = matrix[c][c];
        for (int j = c; j < 3; ++j) matrix[c][j] /= scale;
        values[c] /= scale;
        for (int row = 0; row < 3; ++row) if (row != c) {
            double factor = matrix[row][c];
            for (int j = c; j < 3; ++j) matrix[row][j] -= factor * matrix[c][j];
            values[row] -= factor * values[c];
        }
    }
    output = values;
    return true;
}
}
bool match(uint32_t* pixels, int width, int height, const uint32_t* preview,
           int rotation, float zoom, float aspect, const std::atomic_bool& cancel, ColorStats& stats) {
    stats = {};
    if (!pixels || !preview || width < 8 || height < 8 || int64_t(width) * height > 16'000'000 ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) ||
        !std::isfinite(zoom) || zoom < 1 || zoom > 100 || !std::isfinite(aspect) || aspect < 1 || aspect > 2) return false;
    float cw = std::min(float(width), height * aspect) / zoom, ch = std::min(float(height), width / aspect) / zoom;
    std::vector<Pair> pairs; pairs.reserve(44 * 44);
    // Average a small grid per cell, keeping sensor/preview edge distortion and
    // sparse saturated pixels out of this global color estimate.
    for (int y = 2; y < 46; ++y) {
        if (cancel.load()) return false;
        for (int x = 2; x < 46; ++x) {
            Pair pair;
            for (int dy = 0; dy < 2; ++dy) for (int dx = 0; dx < 2; ++dx) {
                auto p = color(preview[(y * 2 + dy) * 96 + x * 2 + dx]);
                for (int c = 0; c < 3; ++c) pair.target[c] += p[c] / 4;
            }
            for (int dy = 0; dy < 4; ++dy) for (int dx = 0; dx < 4; ++dx) {
                float u = (x + (dx + .5f) / 4) / 48, v = (y + (dy + .5f) / 4) / 48;
                float sx = u, sy = v;
                if (rotation == 90) { sx = v; sy = 1 - u; }
                if (rotation == 180) { sx = 1 - u; sy = 1 - v; }
                if (rotation == 270) { sx = 1 - v; sy = u; }
                int px = std::clamp(int(width * .5f + (sx - .5f) * cw), 0, width - 1);
                int py = std::clamp(int(height * .5f + (sy - .5f) * ch), 0, height - 1);
                auto p = color(pixels[py * width + px]);
                for (int c = 0; c < 3; ++c) pair.source[c] += p[c] / 16;
            }
            if (*std::min_element(pair.source.begin(), pair.source.end()) <= .01 ||
                *std::max_element(pair.source.begin(), pair.source.end()) >= .99 ||
                *std::min_element(pair.target.begin(), pair.target.end()) <= .01 ||
                *std::max_element(pair.target.begin(), pair.target.end()) >= .99) continue;
            pairs.push_back(pair);
        }
    }
    if (pairs.size() < 256) return true;
    Matrix correction{};
    for (int iteration = 0; iteration < 4; ++iteration) {
        if (cancel.load()) return false;
        Matrix covariance{}, products{};
        double energy=0;
        for(const auto& p:pairs) for(double channel:p.source) energy+=channel*channel/3;
        // Regularization has the same units as the covariance. A fixed prior
        // overwhelmed dark-scene measurements and biased the fit toward identity.
        double prior = std::max(1e-5,energy * .001);
        for (int c = 0; c < 3; ++c) { covariance[c][c] = prior; products[c][c] = prior; }
        for (const auto& p : pairs) for (int c = 0; c < 3; ++c) for (int j = 0; j < 3; ++j) {
            covariance[c][j] += p.weight * p.source[c] * p.source[j];
            products[c][j] += p.weight * p.target[c] * p.source[j];
        }
        for (int c = 0; c < 3; ++c) if (!solve(covariance, products[c], correction[c])) return true;
        for (auto& p : pairs) p.weight = std::min(1., .05 / std::max(.001, error(transform(correction, p.source), p.target)));
    }
    // Keep every existing bound: contract an otherwise out-of-range fit
    // toward identity, then retain the usual determinant and error checks.
    double fraction=1;
    auto constrain=[&](double value,double origin,double lower,double upper) {
        if(value>upper)fraction=std::min(fraction,(upper-origin)/(value-origin));
        if(value<lower)fraction=std::min(fraction,(lower-origin)/(value-origin));
    };
    double proposedNeutral=0;
    for(int c=0;c<3;c++) {
        double sum=0;
        for(int j=0;j<3;j++) {constrain(correction[c][j],c==j?1.:0.,c==j?.25:-1.,c==j?2.:1.);sum+=correction[c][j];}
        constrain(sum,1,.5,1.75);proposedNeutral+=sum*(c==0?.2126:c==1?.7152:.0722);
    }
    constrain(proposedNeutral,1,.85,1.15);
    if(fraction<1) {
        fraction=std::clamp(fraction*(1-1e-8),0.,1.);
        for(int c=0;c<3;c++)for(int j=0;j<3;j++)correction[c][j]=(c==j?1.:0.)*(1-fraction)+correction[c][j]*fraction;
    }
    double neutralLuminance=0;
    constexpr double luminanceWeights[3]={.2126,.7152,.0722};
    for (int c = 0; c < 3; ++c) {
        double sum = 0;
        for (int j = 0; j < 3; ++j) {
            double value = correction[c][j]; sum += value;
            if (!std::isfinite(value) || (c == j ? value < .25 || value > 2 : std::abs(value) > 1)) return true;
        }
        // White balance may need different channel responses. Constrain the
        // overall neutral luminance rather than forbidding the required correction.
        if (sum < .5 || sum > 1.75) return true;
        neutralLuminance+=luminanceWeights[c]*sum;
    }
    if(neutralLuminance<.85 || neutralLuminance>1.15) return true;
    double determinant = correction[0][0] * (correction[1][1]*correction[2][2]-correction[1][2]*correction[2][1]) -
        correction[0][1] * (correction[1][0]*correction[2][2]-correction[1][2]*correction[2][0]) +
        correction[0][2] * (correction[1][0]*correction[2][1]-correction[1][1]*correction[2][0]);
    if (determinant < .125 || determinant > 8) return true;
    for (const auto& p : pairs) {
        double before = error(p.source, p.target), after = error(transform(correction, p.source), p.target);
        stats.before += before * before; stats.after += after * after;
        if (after < .08) stats.inliers += 1;
    }
    stats.before = std::sqrt(stats.before / pairs.size()); stats.after = std::sqrt(stats.after / pairs.size()); stats.inliers /= pairs.size();
    if (stats.before < .02 || stats.after >= stats.before * .9 || stats.after > .09 || stats.inliers < .7) return true;
    for (int y = 0; y < height; ++y) {
        if (cancel.load()) return false;
        for (int x = 0; x < width; ++x) {
            int i = y * width + x; auto rgb = transform(correction, color(pixels[i])); uint32_t value = 0xff000000;
            for (int c = 0; c < 3; ++c) value |= uint32_t(std::clamp(std::round(rgb[c] * 255), 0., 255.)) << ((2 - c) * 8);
            pixels[i] = value;
        }
    }
    stats.applied = true;
    return true;
}
}
