// PixR engine : upscaling analytique (bicubique Catmull-Rom) + netteté, par tuiles.
// Aucune IA générative : uniquement des opérations mathématiques déterministes.
#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <cstddef>
#include <cstdlib>

namespace {

constexpr int kMargin = 2;

inline float catmullRom(float x) {
    x = std::fabs(x);
    if (x < 1.0f) return 1.5f * x * x * x - 2.5f * x * x + 1.0f;
    if (x < 2.0f) return -0.5f * x * x * x + 2.5f * x * x - 4.0f * x + 2.0f;
    return 0.0f;
}

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

struct Params {
    const int32_t* src;
    int sw, sh, factor, mode;
    float grain;  // 0..1 : part du détail/grain d'origine conservée
};

// Remplit buf (3 floats par pixel) pour la région [rx0,rx1) x [ry0,ry1) de l'image de sortie.
void upscaleRegion(const Params& p, int rx0, int ry0, int rw, int rh, std::vector<float>& buf) {
    const float inv = 1.0f / static_cast<float>(p.factor);
    std::vector<int> xi(rw * 4);
    std::vector<float> xw(rw * 4);
    for (int x = 0; x < rw; ++x) {
        float sx = (rx0 + x + 0.5f) * inv - 0.5f;
        int ix = static_cast<int>(std::floor(sx));
        float fx = sx - ix;
        for (int k = 0; k < 4; ++k) {
            xi[x * 4 + k] = std::min(std::max(ix - 1 + k, 0), p.sw - 1);
            xw[x * 4 + k] = catmullRom(fx - (k - 1));
        }
    }
    for (int y = 0; y < rh; ++y) {
        float sy = (ry0 + y + 0.5f) * inv - 0.5f;
        int iy = static_cast<int>(std::floor(sy));
        float fy = sy - iy;
        int yi[4];
        float yw[4];
        for (int k = 0; k < 4; ++k) {
            yi[k] = std::min(std::max(iy - 1 + k, 0), p.sh - 1);
            yw[k] = catmullRom(fy - (k - 1));
        }
        for (int x = 0; x < rw; ++x) {
            float r = 0, g = 0, b = 0;
            for (int j = 0; j < 4; ++j) {
                const int32_t* row = p.src + static_cast<size_t>(yi[j]) * p.sw;
                for (int i = 0; i < 4; ++i) {
                    float w = yw[j] * xw[x * 4 + i];
                    uint32_t c = static_cast<uint32_t>(row[xi[x * 4 + i]]);
                    r += w * static_cast<float>((c >> 16) & 0xFF);
                    g += w * static_cast<float>((c >> 8) & 0xFF);
                    b += w * static_cast<float>(c & 0xFF);
                }
            }
            float* o = &buf[(static_cast<size_t>(y) * rw + x) * 3];
            o[0] = r; o[1] = g; o[2] = b;
        }
    }
}

// Flou 3x3 (bords répliqués) de buf vers lp.
void blur3x3(const std::vector<float>& buf, int w, int h, std::vector<float>& lp) {
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float acc[3] = {0, 0, 0};
            for (int dy = -1; dy <= 1; ++dy) {
                int yy = std::min(std::max(y + dy, 0), h - 1);
                for (int dx = -1; dx <= 1; ++dx) {
                    int xx = std::min(std::max(x + dx, 0), w - 1);
                    const float* s = &buf[(static_cast<size_t>(yy) * w + xx) * 3];
                    acc[0] += s[0]; acc[1] += s[1]; acc[2] += s[2];
                }
            }
            float* o = &lp[(static_cast<size_t>(y) * w + x) * 3];
            o[0] = acc[0] / 9.0f; o[1] = acc[1] / 9.0f; o[2] = acc[2] / 9.0f;
        }
    }
}

// mode : 0 = Standard fidèle, 1 = Photo compressée (anti-blocs JPEG), 2 = Texte & documents
void enhance(const Params& p, std::vector<float>& buf, const std::vector<float>& lp) {
    float amount = 0.6f;
    float smooth = 0.0f;
    if (p.mode == 1) { amount = 0.5f; smooth = 0.0f; }  // déblocage bilatéral fait en amont
    if (p.mode == 2) { amount = 1.2f; }
    const size_t n = buf.size();
    for (size_t i = 0; i < n; ++i) {
        float base = buf[i] * (1.0f - smooth) + lp[i] * smooth;
        float sharp = base + amount * (base - lp[i]);
        float v = lp[i] + p.grain * (sharp - lp[i]);
        if (p.mode == 2) {
            float t = clampf(v / 255.0f, 0.0f, 1.0f);
            t = clampf((t - 0.5f) * 1.15f + 0.5f, 0.0f, 1.0f);
            v = t * 255.0f;
        }
        buf[i] = clampf(v, 0.0f, 255.0f);
    }
}


// Filtre bilatéral rapide 5x5 (spatial gaussien + noyau de gamme LUT) : supprime les artefacts
// de blocs JPEG sans flouter les contours réels. Appliqué sur la source avant l'agrandissement.
std::vector<int32_t> bilateralDeblock(const int32_t* src, int w, int h) {
    constexpr int R = 2;
    constexpr float sigS = 1.5f, sigR = 20.0f;
    float sw[2 * R + 1][2 * R + 1];
    for (int dy = -R; dy <= R; ++dy)
        for (int dx = -R; dx <= R; ++dx)
            sw[dy + R][dx + R] = std::exp(-(dx * dx + dy * dy) / (2.0f * sigS * sigS));
    float lut[256];
    for (int d = 0; d < 256; ++d) lut[d] = std::exp(-(static_cast<float>(d) * d) / (2.0f * sigR * sigR));

    std::vector<int32_t> out(static_cast<size_t>(w) * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint32_t c = static_cast<uint32_t>(src[static_cast<size_t>(y) * w + x]);
            int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
            float ar = 0, ag = 0, ab = 0, ws = 0;
            for (int dy = -R; dy <= R; ++dy) {
                int yy = std::min(std::max(y + dy, 0), h - 1);
                const int32_t* row = src + static_cast<size_t>(yy) * w;
                for (int dx = -R; dx <= R; ++dx) {
                    int xx = std::min(std::max(x + dx, 0), w - 1);
                    uint32_t c2 = static_cast<uint32_t>(row[xx]);
                    int r = (c2 >> 16) & 0xFF, g = (c2 >> 8) & 0xFF, b = c2 & 0xFF;
                    int dist = (std::abs(r - cr) + std::abs(g - cg) + std::abs(b - cb)) / 3;
                    float wgt = sw[dy + R][dx + R] * lut[dist];
                    ar += wgt * r; ag += wgt * g; ab += wgt * b; ws += wgt;
                }
            }
            float inv = 1.0f / ws;
            uint32_t r = static_cast<uint32_t>(clampf(ar * inv, 0.0f, 255.0f) + 0.5f);
            uint32_t g = static_cast<uint32_t>(clampf(ag * inv, 0.0f, 255.0f) + 0.5f);
            uint32_t b = static_cast<uint32_t>(clampf(ab * inv, 0.0f, 255.0f) + 0.5f);
            out[static_cast<size_t>(y) * w + x] = static_cast<int32_t>(0xFF000000u | (r << 16) | (g << 8) | b);
        }
    }
    return out;
}

// ---------------------------------------------------------------------------------------------
// Fusion multi-photos : alignement par block matching (grossier 1/4 puis raffinement plein format
// avec sous-pixel parabolique), rejet des zones en mouvement, fusion pondérée sur grille x2.
// ---------------------------------------------------------------------------------------------

inline float lumaOf(uint32_t c) {
    return 0.299f * static_cast<float>((c >> 16) & 0xFF) + 0.587f * static_cast<float>((c >> 8) & 0xFF) +
           0.114f * static_cast<float>(c & 0xFF);
}

struct Motion {
    float dx = 0.0f, dy = 0.0f;
    bool ok = false;
};

constexpr int kFineBlock = 16;
constexpr int kCoarseBlock = 8;
constexpr int kCoarseRange = 6;
constexpr int kFineRange = 3;
constexpr float kBlockSadMax = 20.0f;   // écart moyen max (niveaux de gris) pour accepter un bloc
constexpr float kPixelReject = 30.0f;   // écart luma à partir duquel un pixel est rejeté (mouvement)

void toLuma(const int32_t* s, int n, std::vector<float>& L) {
    L.resize(n);
    for (int i = 0; i < n; ++i) L[i] = lumaOf(static_cast<uint32_t>(s[i]));
}

void downsample4(const std::vector<float>& L, int w, int h, std::vector<float>& D, int& dw, int& dh) {
    dw = std::max(1, w / 4);
    dh = std::max(1, h / 4);
    D.assign(static_cast<size_t>(dw) * dh, 0.0f);
    for (int y = 0; y < dh; ++y)
        for (int x = 0; x < dw; ++x) {
            float acc = 0.0f;
            for (int j = 0; j < 4; ++j) {
                int yy = std::min(y * 4 + j, h - 1);
                for (int i = 0; i < 4; ++i) acc += L[static_cast<size_t>(yy) * w + std::min(x * 4 + i, w - 1)];
            }
            D[static_cast<size_t>(y) * dw + x] = acc / 16.0f;
        }
}

// Écart absolu moyen entre le bloc de A et le bloc de B décalé de (dx,dy) (bords répliqués).
float blockSad(const float* A, const float* B, int w, int h, int bx, int by, int bs, int dx, int dy, int stride) {
    float sum = 0.0f;
    int count = 0;
    int ye = std::min(by + bs, h), xe = std::min(bx + bs, w);
    for (int y = by; y < ye; y += stride) {
        int yb = std::min(std::max(y + dy, 0), h - 1);
        for (int x = bx; x < xe; x += stride) {
            int xb = std::min(std::max(x + dx, 0), w - 1);
            sum += std::fabs(A[static_cast<size_t>(y) * w + x] - B[static_cast<size_t>(yb) * w + xb]);
            ++count;
        }
    }
    return count > 0 ? sum / static_cast<float>(count) : 1e9f;
}

inline float parabolicOffset(float a, float b, float c) {
    float denom = a - 2.0f * b + c;
    if (denom < 1e-6f) return 0.0f;
    return clampf(0.5f * (a - c) / denom, -0.5f, 0.5f);
}

// Mouvement de chaque bloc 16x16 : la frame F à (x+dx, y+dy) correspond à la référence en (x, y).
void computeMotion(const std::vector<float>& LR, const std::vector<float>& LF, int w, int h,
                   std::vector<Motion>& motion, int& gbw, int& gbh) {
    std::vector<float> DR, DF;
    int dw, dh;
    downsample4(LR, w, h, DR, dw, dh);
    int dw2, dh2;
    downsample4(LF, w, h, DF, dw2, dh2);

    int cbw = (dw + kCoarseBlock - 1) / kCoarseBlock;
    int cbh = (dh + kCoarseBlock - 1) / kCoarseBlock;
    std::vector<int> cx(static_cast<size_t>(cbw) * cbh, 0), cy(static_cast<size_t>(cbw) * cbh, 0);
    for (int by = 0; by < cbh; ++by)
        for (int bx = 0; bx < cbw; ++bx) {
            float best = 1e30f;
            int bdx = 0, bdy = 0;
            for (int dy = -kCoarseRange; dy <= kCoarseRange; ++dy)
                for (int dx = -kCoarseRange; dx <= kCoarseRange; ++dx) {
                    float s = blockSad(DR.data(), DF.data(), dw, dh, bx * kCoarseBlock, by * kCoarseBlock,
                                       kCoarseBlock, dx, dy, 1);
                    // léger biais vers le mouvement nul pour éviter les faux appariements sur zones plates
                    s += 0.02f * static_cast<float>(std::abs(dx) + std::abs(dy));
                    if (s < best) { best = s; bdx = dx; bdy = dy; }
                }
            cx[static_cast<size_t>(by) * cbw + bx] = bdx;
            cy[static_cast<size_t>(by) * cbw + bx] = bdy;
        }

    gbw = (w + kFineBlock - 1) / kFineBlock;
    gbh = (h + kFineBlock - 1) / kFineBlock;
    motion.assign(static_cast<size_t>(gbw) * gbh, Motion());
    constexpr int N = 2 * kFineRange + 1;
    for (int by = 0; by < gbh; ++by)
        for (int bx = 0; bx < gbw; ++bx) {
            int sxc = std::min(((bx * kFineBlock + kFineBlock / 2) / 4) / kCoarseBlock, cbw - 1);
            int syc = std::min(((by * kFineBlock + kFineBlock / 2) / 4) / kCoarseBlock, cbh - 1);
            int gx = cx[static_cast<size_t>(syc) * cbw + sxc] * 4;
            int gy = cy[static_cast<size_t>(syc) * cbw + sxc] * 4;
            float grid[N][N];
            float best = 1e30f;
            int bi = 0, bj = 0;
            for (int j = 0; j < N; ++j)
                for (int i = 0; i < N; ++i) {
                    float s = blockSad(LR.data(), LF.data(), w, h, bx * kFineBlock, by * kFineBlock, kFineBlock,
                                       gx + i - kFineRange, gy + j - kFineRange, 2);
                    grid[j][i] = s;
                    if (s < best) { best = s; bi = i; bj = j; }
                }
            float ox = 0.0f, oy = 0.0f;
            if (bi > 0 && bi < N - 1) ox = parabolicOffset(grid[bj][bi - 1], grid[bj][bi], grid[bj][bi + 1]);
            if (bj > 0 && bj < N - 1) oy = parabolicOffset(grid[bj - 1][bi], grid[bj][bi], grid[bj + 1][bi]);
            Motion& m = motion[static_cast<size_t>(by) * gbw + bx];
            m.dx = static_cast<float>(gx + bi - kFineRange) + ox;
            m.dy = static_cast<float>(gy + bj - kFineRange) + oy;
            m.ok = best < kBlockSadMax;
        }
}

inline void sampleBilinear(const int32_t* img, int w, int h, float fx, float fy, float* rgb) {
    fx = clampf(fx, 0.0f, static_cast<float>(w - 1));
    fy = clampf(fy, 0.0f, static_cast<float>(h - 1));
    int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
    int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    float tx = fx - x0, ty = fy - y0;
    uint32_t c00 = static_cast<uint32_t>(img[static_cast<size_t>(y0) * w + x0]);
    uint32_t c10 = static_cast<uint32_t>(img[static_cast<size_t>(y0) * w + x1]);
    uint32_t c01 = static_cast<uint32_t>(img[static_cast<size_t>(y1) * w + x0]);
    uint32_t c11 = static_cast<uint32_t>(img[static_cast<size_t>(y1) * w + x1]);
    for (int ch = 0; ch < 3; ++ch) {
        int sh = 16 - 8 * ch;
        float a = static_cast<float>((c00 >> sh) & 0xFF), b = static_cast<float>((c10 >> sh) & 0xFF);
        float c = static_cast<float>((c01 >> sh) & 0xFF), d = static_cast<float>((c11 >> sh) & 0xFF);
        rgb[ch] = (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_pixr_app_NativeEngine_upscale(JNIEnv* env, jobject /*thiz*/, jintArray srcArr, jint w, jint h,
                                       jint factor, jint mode, jint grainPercent, jint tileSize) {
    if (w <= 0 || h <= 0 || factor < 1 || factor > 8 || tileSize < 16) return nullptr;
    const int64_t ow = static_cast<int64_t>(w) * factor;
    const int64_t oh = static_cast<int64_t>(h) * factor;
    if (ow * oh > 0x7FFFFFFFLL / 2) return nullptr;
    if (env->GetArrayLength(srcArr) < w * h) return nullptr;

    jint* src = env->GetIntArrayElements(srcArr, nullptr);
    if (!src) return nullptr;
    jintArray outArr = env->NewIntArray(static_cast<jsize>(ow * oh));
    if (!outArr) {
        env->ReleaseIntArrayElements(srcArr, src, JNI_ABORT);
        return nullptr;
    }
    jint* out = env->GetIntArrayElements(outArr, nullptr);
    if (!out) {
        env->ReleaseIntArrayElements(srcArr, src, JNI_ABORT);
        return nullptr;
    }

    std::vector<int32_t> deblocked;
    const int32_t* srcPtr = src;
    if (mode == 1) {
        deblocked = bilateralDeblock(src, w, h);
        srcPtr = deblocked.data();
    }
    Params p{srcPtr, w, h, factor, mode, clampf(grainPercent / 100.0f, 0.0f, 1.0f)};
    std::vector<float> buf, lp;
    for (int64_t ty = 0; ty < oh; ty += tileSize) {
        for (int64_t tx = 0; tx < ow; tx += tileSize) {
            int tw = static_cast<int>(std::min<int64_t>(tileSize, ow - tx));
            int th = static_cast<int>(std::min<int64_t>(tileSize, oh - ty));
            int rx0 = static_cast<int>(std::max<int64_t>(0, tx - kMargin));
            int ry0 = static_cast<int>(std::max<int64_t>(0, ty - kMargin));
            int rx1 = static_cast<int>(std::min<int64_t>(ow, tx + tw + kMargin));
            int ry1 = static_cast<int>(std::min<int64_t>(oh, ty + th + kMargin));
            int rw = rx1 - rx0, rh = ry1 - ry0;
            buf.assign(static_cast<size_t>(rw) * rh * 3, 0.0f);
            lp.assign(buf.size(), 0.0f);
            upscaleRegion(p, rx0, ry0, rw, rh, buf);
            blur3x3(buf, rw, rh, lp);
            enhance(p, buf, lp);
            for (int y = 0; y < th; ++y) {
                int by = static_cast<int>(ty) + y - ry0;
                for (int x = 0; x < tw; ++x) {
                    int bx = static_cast<int>(tx) + x - rx0;
                    const float* s = &buf[(static_cast<size_t>(by) * rw + bx) * 3];
                    uint32_t r = static_cast<uint32_t>(s[0] + 0.5f);
                    uint32_t g = static_cast<uint32_t>(s[1] + 0.5f);
                    uint32_t b = static_cast<uint32_t>(s[2] + 0.5f);
                    out[(ty + y) * ow + (tx + x)] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
                }
            }
        }
    }
    env->ReleaseIntArrayElements(outArr, out, 0);
    env->ReleaseIntArrayElements(srcArr, src, JNI_ABORT);
    return outArr;
}


extern "C" JNIEXPORT jintArray JNICALL
Java_com_pixr_app_NativeEngine_fuse(JNIEnv* env, jobject /*thiz*/, jobjectArray framesArr, jint w, jint h,
                                    jint grainPercent) {
    if (!framesArr || w < 64 || h < 64) return nullptr;
    const int n = env->GetArrayLength(framesArr);
    if (n < 2 || n > 8) return nullptr;
    const int64_t ow = static_cast<int64_t>(w) * 2;
    const int64_t oh = static_cast<int64_t>(h) * 2;
    if (ow * oh > 0x7FFFFFFFLL / 2) return nullptr;

    std::vector<jintArray> arrs(n, nullptr);
    std::vector<jint*> px(n, nullptr);
    auto releaseAll = [&]() {
        for (int k = 0; k < n; ++k)
            if (px[k]) env->ReleaseIntArrayElements(arrs[k], px[k], JNI_ABORT);
    };
    for (int k = 0; k < n; ++k) {
        arrs[k] = static_cast<jintArray>(env->GetObjectArrayElement(framesArr, k));
        if (!arrs[k] || env->GetArrayLength(arrs[k]) < w * h) { releaseAll(); return nullptr; }
        px[k] = env->GetIntArrayElements(arrs[k], nullptr);
        if (!px[k]) { releaseAll(); return nullptr; }
    }

    // Mouvement de chaque frame (k >= 1) par rapport à la référence (frame 0).
    std::vector<std::vector<Motion>> motions(n);
    int gbw = 0, gbh = 0;
    {
        std::vector<float> LR, LF;
        toLuma(px[0], w * h, LR);
        for (int k = 1; k < n; ++k) {
            toLuma(px[k], w * h, LF);
            computeMotion(LR, LF, w, h, motions[k], gbw, gbh);
        }
    }

    jintArray outArr = env->NewIntArray(static_cast<jsize>(ow * oh));
    if (!outArr) { releaseAll(); return nullptr; }
    jint* out = env->GetIntArrayElements(outArr, nullptr);
    if (!out) { releaseAll(); return nullptr; }

    const float grain = clampf(grainPercent / 100.0f, 0.0f, 1.0f);
    Params p{px[0], w, h, 2, 0, 1.0f};
    constexpr int tileSize = 256;
    std::vector<float> buf, lp;
    for (int64_t ty = 0; ty < oh; ty += tileSize) {
        for (int64_t tx = 0; tx < ow; tx += tileSize) {
            int tw = static_cast<int>(std::min<int64_t>(tileSize, ow - tx));
            int th = static_cast<int>(std::min<int64_t>(tileSize, oh - ty));
            int rx0 = static_cast<int>(std::max<int64_t>(0, tx - kMargin));
            int ry0 = static_cast<int>(std::max<int64_t>(0, ty - kMargin));
            int rx1 = static_cast<int>(std::min<int64_t>(ow, tx + tw + kMargin));
            int ry1 = static_cast<int>(std::min<int64_t>(oh, ty + th + kMargin));
            int rw = rx1 - rx0, rh = ry1 - ry0;
            buf.assign(static_cast<size_t>(rw) * rh * 3, 0.0f);
            lp.assign(buf.size(), 0.0f);
            upscaleRegion(p, rx0, ry0, rw, rh, buf);   // référence bicubique
            blur3x3(buf, rw, rh, lp);
            for (int y = 0; y < th; ++y) {
                int oy = static_cast<int>(ty) + y;
                float sy = (oy + 0.5f) * 0.5f - 0.5f;
                int bgy = std::min(std::max(oy / 2 / kFineBlock, 0), gbh - 1);
                for (int x = 0; x < tw; ++x) {
                    int ox = static_cast<int>(tx) + x;
                    float sx = (ox + 0.5f) * 0.5f - 0.5f;
                    int bgx = std::min(std::max(ox / 2 / kFineBlock, 0), gbw - 1);
                    size_t bi = (static_cast<size_t>(y) + (ty - ry0)) * rw + (x + (tx - rx0));
                    const float* ref = &buf[bi * 3];
                    const float* low = &lp[bi * 3];
                    float refL = 0.299f * ref[0] + 0.587f * ref[1] + 0.114f * ref[2];
                    float acc[3] = {ref[0], ref[1], ref[2]};
                    float wsum = 1.0f;
                    for (int k = 1; k < n; ++k) {
                        const Motion& m = motions[k][static_cast<size_t>(bgy) * gbw + bgx];
                        if (!m.ok) continue;
                        float rgb[3];
                        sampleBilinear(px[k], w, h, sx + m.dx, sy + m.dy, rgb);
                        float lk = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2];
                        float d = std::fabs(lk - refL);
                        if (d >= kPixelReject) continue;   // mouvement local : on rejette (pas de fantôme)
                        float q = d / kPixelReject;
                        float wk = 1.0f - q * q;
                        acc[0] += wk * rgb[0]; acc[1] += wk * rgb[1]; acc[2] += wk * rgb[2];
                        wsum += wk;
                    }
                    float inv = 1.0f / wsum;
                    // Le moyennage atténue le grain : on réinjecte la part d'origine demandée.
                    float restore = grain * 0.6f * (1.0f - inv);
                    uint32_t c[3];
                    for (int ch = 0; ch < 3; ++ch) {
                        float v = acc[ch] * inv + restore * (ref[ch] - low[ch]);
                        c[ch] = static_cast<uint32_t>(clampf(v, 0.0f, 255.0f) + 0.5f);
                    }
                    out[static_cast<size_t>(oy) * ow + ox] =
                        static_cast<jint>(0xFF000000u | (c[0] << 16) | (c[1] << 8) | c[2]);
                }
            }
        }
    }
    env->ReleaseIntArrayElements(outArr, out, 0);
    releaseAll();
    return outArr;
}


// ---------------------------------------------------------------------------------------------
// Fusion RAW_SENSOR multi-trames (Étape 3) : normalisation Bayer linéaire, alignement sur la
// luminance 2x2, rejet anti-fantômes, fusion directe des échantillons couleur sur la grille de
// sortie (régression à noyau gaussien), matrice couleur + gamma sRGB, accentuation douce (texte).
// 100 % déterministe, aucune IA générative.
// ---------------------------------------------------------------------------------------------
namespace {

// Couleur (0=R, 1=G, 2=B) par index raster du motif 2x2 pour RGGB, GRBG, GBRG, BGGR.
constexpr int kCfaColor[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
constexpr float kRawReject = 32.0f;  // écart de luminance (0..255) rejetant un échantillon en mouvement

// Débruitage déterministe préservant les contours (filtre bilatéral 5x5 sur luminance + chrominance).
// Il ne fait que moyenner des pixels réels voisins de valeur proche : aucun détail n'est créé.
// rgb : 3 flottants 0..255 par pixel (modifié en place) ; strength 0..1 ; tmp : tampon de travail.
void bilateralDenoiseRgb(std::vector<float>& rgb, int w, int h, float strength, std::vector<float>& tmp) {
    const size_t n = static_cast<size_t>(w) * h;
    tmp.resize(n * 3);
    for (size_t i = 0; i < n; ++i) {
        const float r = rgb[i * 3], g = rgb[i * 3 + 1], b = rgb[i * 3 + 2];
        const float y = 0.299f * r + 0.587f * g + 0.114f * b;
        tmp[i * 3] = y;
        tmp[i * 3 + 1] = (b - y) * 0.564f;
        tmp[i * 3 + 2] = (r - y) * 0.713f;
    }
    const float sy = 3.0f + 12.0f * strength;  // tolérance luminance : les contours plus forts sont préservés
    const float sc = 6.0f + 24.0f * strength;  // tolérance chrominance : le bruit couleur se lisse plus largement
    const float invY = 1.0f / (2.0f * sy * sy);
    const float invC = 1.0f / (2.0f * sc * sc);
    static const float kSp[3] = {1.0f, 0.6065f, 0.1353f};  // gaussienne spatiale pour des écarts de 0, 1 et 2 px
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t c = static_cast<size_t>(y) * w + x;
            const float cy = tmp[c * 3], cb = tmp[c * 3 + 1], cr = tmp[c * 3 + 2];
            float sumY = 0.0f, sumB = 0.0f, sumR = 0.0f, wY = 0.0f, wC = 0.0f;
            for (int dy = -2; dy <= 2; ++dy) {
                const int yy = y + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -2; dx <= 2; ++dx) {
                    const int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    const size_t q = static_cast<size_t>(yy) * w + xx;
                    const float dY = tmp[q * 3] - cy, dB = tmp[q * 3 + 1] - cb, dR = tmp[q * 3 + 2] - cr;
                    const float sp = kSp[dx < 0 ? -dx : dx] * kSp[dy < 0 ? -dy : dy];
                    const float wy = sp * std::exp(-dY * dY * invY);
                    const float wc = sp * std::exp(-(dY * dY + dB * dB + dR * dR) * invC);
                    sumY += wy * tmp[q * 3];
                    wY += wy;
                    sumB += wc * tmp[q * 3 + 1];
                    sumR += wc * tmp[q * 3 + 2];
                    wC += wc;
                }
            }
            const float fy = wY > 1e-6f ? sumY / wY : cy;
            const float fb = wC > 1e-6f ? sumB / wC : cb;
            const float fr = wC > 1e-6f ? sumR / wC : cr;
            const float r = fy + fr / 0.713f;
            const float b = fy + fb / 0.564f;
            const float g = (fy - 0.299f * r - 0.114f * b) / 0.587f;
            rgb[c * 3] = clampf(r, 0.0f, 255.0f);
            rgb[c * 3 + 1] = clampf(g, 0.0f, 255.0f);
            rgb[c * 3 + 2] = clampf(b, 0.0f, 255.0f);
        }
    }
}

struct RawCtx {
    int w = 0, h = 0, cfa = 0;
    float black[4] = {0, 0, 0, 0};
    float invRange = 1.0f;
    float gainIdx[4] = {1, 1, 1, 1};  // gain de balance des blancs par position du motif
    float lumaW[4] = {0, 0, 0, 0};    // poids de luminance par position du motif
};

inline float rawVal(const RawCtx& c, const jshort* d, int x, int y) {
    const int idx = ((y & 1) << 1) | (x & 1);
    const float v = static_cast<float>(static_cast<uint16_t>(d[static_cast<size_t>(y) * c.w + x]));
    return (v - c.black[idx]) * c.invRange * c.gainIdx[idx];
}

// Luminance (0..255) du bloc 2x2 d'origine paire (bx*2, by*2).
inline float rawLuma(const RawCtx& c, const jshort* d, int bx, int by) {
    float acc = 0.0f;
    for (int j = 0; j < 2; ++j) {
        for (int i = 0; i < 2; ++i) {
            const int idx = (j << 1) | i;
            acc += rawVal(c, d, bx * 2 + i, by * 2 + j) * c.lumaW[idx];
        }
    }
    return acc * 255.0f;
}

void rawLumaPlane(const RawCtx& c, const jshort* d, std::vector<float>& plane) {
    const int w2 = c.w / 2, h2 = c.h / 2;
    plane.resize(static_cast<size_t>(w2) * h2);
    for (int y = 0; y < h2; ++y)
        for (int x = 0; x < w2; ++x) plane[static_cast<size_t>(y) * w2 + x] = rawLuma(c, d, x, y);
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_pixr_app_NativeEngine_fuseRawBurst(JNIEnv* env, jobject /*thiz*/, jobjectArray framesArr, jint w, jint h,
                                            jint cfa, jintArray blackArr, jint whiteLevel, jfloatArray gainsArr,
                                            jfloatArray ccmArr, jint factor, jintArray cropArr, jboolean textMode,
                                            jfloatArray toneArr) {
    if (!framesArr || !blackArr || !gainsArr || !ccmArr || !cropArr || !toneArr) return nullptr;
    if (env->GetArrayLength(toneArr) < 3) return nullptr;
    const int n = env->GetArrayLength(framesArr);
    if (n < 2 || n > 16 || w < 256 || h < 256 || (w & 1) || (h & 1)) return nullptr;
    if (cfa < 0 || cfa > 3 || factor < 1 || factor > 2 || whiteLevel <= 0) return nullptr;
    if (env->GetArrayLength(blackArr) < 4 || env->GetArrayLength(gainsArr) < 4 || env->GetArrayLength(ccmArr) < 9 ||
        env->GetArrayLength(cropArr) < 4)
        return nullptr;

    jint black[4], crop[4];
    jfloat gains[4], ccm[9];
    env->GetIntArrayRegion(blackArr, 0, 4, black);
    env->GetIntArrayRegion(cropArr, 0, 4, crop);
    env->GetFloatArrayRegion(gainsArr, 0, 4, gains);
    env->GetFloatArrayRegion(ccmArr, 0, 9, ccm);
    if (crop[0] < 0 || crop[1] < 0 || crop[2] > w || crop[3] > h || crop[2] - crop[0] < 2 || crop[3] - crop[1] < 2)
        return nullptr;
    const int64_t ow = static_cast<int64_t>(crop[2] - crop[0]) * factor;
    const int64_t oh = static_cast<int64_t>(crop[3] - crop[1]) * factor;
    if (ow * oh > 40000000LL) return nullptr;

    std::vector<jshortArray> arrs(n, nullptr);
    std::vector<jshort*> px(n, nullptr);
    auto releaseAll = [&]() {
        for (int k = 0; k < n; ++k)
            if (px[k]) env->ReleaseShortArrayElements(arrs[k], px[k], JNI_ABORT);
    };
    for (int k = 0; k < n; ++k) {
        arrs[k] = static_cast<jshortArray>(env->GetObjectArrayElement(framesArr, k));
        if (!arrs[k] || env->GetArrayLength(arrs[k]) < w * h) { releaseAll(); return nullptr; }
        px[k] = env->GetShortArrayElements(arrs[k], nullptr);
        if (!px[k]) { releaseAll(); return nullptr; }
    }

    RawCtx ctx;
    ctx.w = w; ctx.h = h; ctx.cfa = cfa;
    for (int i = 0; i < 4; ++i) ctx.black[i] = static_cast<float>(black[i]);
    // Le niveau de noir de référence est celui du motif ; la plage utile est blanc - noir moyen.
    const float meanBlack = 0.25f * (ctx.black[0] + ctx.black[1] + ctx.black[2] + ctx.black[3]);
    ctx.invRange = 1.0f / std::max(1.0f, static_cast<float>(whiteLevel) - meanBlack);
    const float gainByColor[3] = {gains[0], 0.5f * (gains[1] + gains[2]), gains[3]};
    const float lumaByColor[3] = {0.299f, 0.2935f, 0.114f};
    for (int i = 0; i < 4; ++i) {
        ctx.gainIdx[i] = gainByColor[kCfaColor[cfa][i]];
        ctx.lumaW[i] = lumaByColor[kCfaColor[cfa][i]];
    }

    // Alignement : luminance 2x2 (demi-résolution) ; le déplacement est exprimé en pixels de luminance.
    const int w2 = w / 2, h2 = h / 2;
    std::vector<float> LR, LF;
    rawLumaPlane(ctx, px[0], LR);
    // Rendu : exposition automatique douce (point noir + gain sur le blanc), puis courbe de contraste et
    // saturation. Statistiques prises sur la luminance linéaire de la trame de référence, dans le recadrage.
    jfloat tone[3];
    env->GetFloatArrayRegion(toneArr, 0, 3, tone);
    const float evGain = std::min(std::max(tone[0], 0.25f), 4.0f);
    const float contrast = std::min(std::max(tone[1], 0.0f), 1.0f);
    const float saturation = std::min(std::max(tone[2], 0.0f), 2.0f);
    float denoise = 0.0f;
    if (env->GetArrayLength(toneArr) >= 4) {
        jfloat d = 0.0f;
        env->GetFloatArrayRegion(toneArr, 3, 1, &d);
        denoise = std::min(std::max(d, 0.0f), 1.0f);
    }
    float blackPt = 0.0f, autoGain = 1.0f;
    {
        constexpr int kBins = 1024;
        std::vector<int> hist(kBins, 0);
        int64_t total = 0;
        const int bx0 = crop[0] / 2, bx1 = std::max(bx0 + 1, crop[2] / 2);
        const int by0 = crop[1] / 2, by1 = std::max(by0 + 1, crop[3] / 2);
        for (int y = by0; y < std::min(by1, h2); ++y)
            for (int x = bx0; x < std::min(bx1, w2); ++x) {
                const float l = std::min(std::max(LR[static_cast<size_t>(y) * w2 + x] / 255.0f, 0.0f), 1.0f);
                ++hist[std::min(static_cast<int>(l * (kBins - 1) + 0.5f), kBins - 1)];
                ++total;
            }
        if (total > 0) {
            auto pct = [&](double q) {
                const int64_t target = static_cast<int64_t>(q * static_cast<double>(total));
                int64_t acc = 0;
                for (int i = 0; i < kBins; ++i) { acc += hist[i]; if (acc >= target) return static_cast<float>(i) / (kBins - 1); }
                return 1.0f;
            };
            blackPt = std::min(pct(0.002), 0.04f);
            const float white = std::max(pct(0.997), blackPt + 0.05f);
            autoGain = std::min(std::max(0.94f / (white - blackPt), 1.0f), 2.5f);
        }
    }
    const float toneGain = autoGain * evGain;
    std::vector<std::vector<Motion>> motions(n);
    int gbw = 0, gbh = 0;
    for (int k = 1; k < n; ++k) {
        rawLumaPlane(ctx, px[k], LF);
        computeMotion(LR, LF, w2, h2, motions[k], gbw, gbh);
    }

    jintArray outArr = env->NewIntArray(static_cast<jsize>(ow * oh));
    if (!outArr) { releaseAll(); return nullptr; }
    jint* out = env->GetIntArrayElements(outArr, nullptr);
    if (!out) { releaseAll(); return nullptr; }

    // Noyau gaussien tabulé sur d² ∈ [0, 4).
    const float sigma = factor == 2 ? 0.55f : 0.65f;
    constexpr int kLutN = 512;
    std::vector<float> wl(kLutN);
    for (int i = 0; i < kLutN; ++i) wl[i] = std::exp(-(static_cast<float>(i) / 128.0f) / (2.0f * sigma * sigma));
    // Gamma sRGB tabulé (valeur linéaire 0..1 -> 0..255).
    constexpr int kGammaN = 4096;
    std::vector<float> gammaLut(kGammaN + 1);
    for (int i = 0; i <= kGammaN; ++i) {
        float v = static_cast<float>(i) / kGammaN;
        float e = v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
        gammaLut[i] = clampf(e, 0.0f, 1.0f) * 255.0f;
    }

    const float invF = 1.0f / static_cast<float>(factor);
    const float sharpen = textMode ? 0.6f : 0.0f;
    constexpr int tileSize = 128;
    std::vector<float> buf, lp, dnTmp;
    for (int64_t ty = 0; ty < oh; ty += tileSize) {
        for (int64_t tx = 0; tx < ow; tx += tileSize) {
            const int tw = static_cast<int>(std::min<int64_t>(tileSize, ow - tx));
            const int th = static_cast<int>(std::min<int64_t>(tileSize, oh - ty));
            constexpr int kHalo = 3;
            const int rx0 = static_cast<int>(std::max<int64_t>(0, tx - kHalo));
            const int ry0 = static_cast<int>(std::max<int64_t>(0, ty - kHalo));
            const int rx1 = static_cast<int>(std::min<int64_t>(ow, tx + tw + kHalo));
            const int ry1 = static_cast<int>(std::min<int64_t>(oh, ty + th + kHalo));
            const int rw = rx1 - rx0, rh = ry1 - ry0;
            buf.assign(static_cast<size_t>(rw) * rh * 3, 0.0f);
            for (int y = 0; y < rh; ++y) {
                const float py = static_cast<float>(crop[1]) + (static_cast<float>(ry0 + y) + 0.5f) * invF - 0.5f;
                const int lby = std::min(std::max(static_cast<int>(std::max(py, 0.0f)) >> 1, 0), h2 - 1);
                const int bgy = std::min(std::max(static_cast<int>(std::max(py, 0.0f)) >> 5, 0), gbh - 1);
                for (int x = 0; x < rw; ++x) {
                    const float pxf = static_cast<float>(crop[0]) + (static_cast<float>(rx0 + x) + 0.5f) * invF - 0.5f;
                    const int lbx = std::min(std::max(static_cast<int>(std::max(pxf, 0.0f)) >> 1, 0), w2 - 1);
                    const int bgx = std::min(std::max(static_cast<int>(std::max(pxf, 0.0f)) >> 5, 0), gbw - 1);
                    const float refL = LR[static_cast<size_t>(lby) * w2 + lbx];
                    float acc[3] = {0, 0, 0}, ws[3] = {0, 0, 0};
                    for (int k = 0; k < n; ++k) {
                        float qx = pxf, qy = py, wk = 1.0f;
                        if (k > 0) {
                            const Motion& m = motions[k][static_cast<size_t>(bgy) * gbw + bgx];
                            if (!m.ok) continue;
                            qx = pxf + 2.0f * m.dx;
                            qy = py + 2.0f * m.dy;
                            if (qx < 0.0f || qy < 0.0f || qx > static_cast<float>(w - 1) || qy > static_cast<float>(h - 1))
                                continue;
                            const int kbx = std::min(static_cast<int>(qx) >> 1, w2 - 1);
                            const int kby = std::min(static_cast<int>(qy) >> 1, h2 - 1);
                            const float d = std::fabs(rawLuma(ctx, px[k], kbx, kby) - refL);
                            if (d >= kRawReject) continue;  // mouvement local : pas de fantôme
                            const float q = d / kRawReject;
                            wk = 1.0f - q * q;
                        }
                        const int ix = static_cast<int>(std::floor(qx));
                        const int iy = static_cast<int>(std::floor(qy));
                        for (int yy = iy - 1; yy <= iy + 2; ++yy) {
                            if (yy < 0 || yy >= h) continue;
                            const float dy = static_cast<float>(yy) - qy;
                            for (int xx = ix - 1; xx <= ix + 2; ++xx) {
                                if (xx < 0 || xx >= w) continue;
                                const float dx = static_cast<float>(xx) - qx;
                                const float d2 = dx * dx + dy * dy;
                                if (d2 >= 4.0f) continue;
                                const float wgt = wk * wl[static_cast<int>(d2 * 128.0f)];
                                const int col = kCfaColor[cfa][((yy & 1) << 1) | (xx & 1)];
                                acc[col] += wgt * rawVal(ctx, px[k], xx, yy);
                                ws[col] += wgt;
                            }
                        }
                    }
                    float v[3];
                    for (int c = 0; c < 3; ++c) v[c] = ws[c] > 1e-6f ? std::min(acc[c] / ws[c], 1.0f) : 0.0f;
                    float* o = &buf[(static_cast<size_t>(y) * rw + x) * 3];
                    float g[3];
                    for (int r = 0; r < 3; ++r) {
                        float lin = ccm[r * 3] * v[0] + ccm[r * 3 + 1] * v[1] + ccm[r * 3 + 2] * v[2];
                        lin = clampf((lin - blackPt) * toneGain, 0.0f, 1.0f);
                        g[r] = gammaLut[static_cast<int>(lin * kGammaN + 0.5f)] * (1.0f / 255.0f);
                    }
                    // Contraste : S-curve douce (smoothstep) mélangée à l'identité, sur chaque canal.
                    for (int r = 0; r < 3; ++r) {
                        const float x = g[r];
                        g[r] = x + contrast * (x * x * (3.0f - 2.0f * x) - x);
                    }
                    // Saturation autour de la luminance (préserve les gris neutres).
                    const float yl = 0.2126f * g[0] + 0.7152f * g[1] + 0.0722f * g[2];
                    for (int r = 0; r < 3; ++r) o[r] = clampf(yl + saturation * (g[r] - yl), 0.0f, 1.0f) * 255.0f;
                }
            }
            if (denoise > 0.0f) bilateralDenoiseRgb(buf, rw, rh, denoise, dnTmp);
            if (sharpen > 0.0f) {
                lp.assign(buf.size(), 0.0f);
                blur3x3(buf, rw, rh, lp);
            }
            for (int y = 0; y < th; ++y) {
                const int by = static_cast<int>(ty) + y - ry0;
                for (int x = 0; x < tw; ++x) {
                    const int bx = static_cast<int>(tx) + x - rx0;
                    const size_t bi = (static_cast<size_t>(by) * rw + bx) * 3;
                    uint32_t c[3];
                    for (int ch = 0; ch < 3; ++ch) {
                        float val = buf[bi + ch];
                        if (sharpen > 0.0f) val += sharpen * (val - lp[bi + ch]);
                        c[ch] = static_cast<uint32_t>(clampf(val, 0.0f, 255.0f) + 0.5f);
                    }
                    out[(ty + y) * ow + (tx + x)] = static_cast<jint>(0xFF000000u | (c[0] << 16) | (c[1] << 8) | c[2]);
                }
            }
        }
    }
    env->ReleaseIntArrayElements(outArr, out, 0);
    releaseAll();
    return outArr;
}
