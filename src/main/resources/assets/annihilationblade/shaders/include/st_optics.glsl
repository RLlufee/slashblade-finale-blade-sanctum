// Stellaris Engine 光学与物理色彩库：光谱泄白、恒等保真电影合成、黑体 LUT、薄膜虹彩干涉、防透墙双边升采样（需先引入 st_common）

// 光谱泄白（Film Emission）：为 v3 宽动态范围（0.8 ~ 20+）定制，
// 中高能段（1.5 ~ 6.0）保住物理黑体金红与切伦科夫冰青饱和度，仅极端星核（12 ~ 25+）顺滑泄向星白
vec3 stFilmEmit(vec3 emission) {
    vec3 e = max(stSanitize(emission), vec3(0.0));
    float L = stLuma(e);
    if (L <= 1.0e-5) {
        return vec3(0.0);
    }
    const vec3 starWhite = vec3(0.96, 0.99, 1.00);
    float whiteLuma = stLuma(starWhite);
    float bleed = min(1.0 - exp(-0.18 * max(L - 0.75, 0.0)), 0.94);
    vec3 whiteTarget = starWhite * (L / whiteLuma);
    return mix(e, whiteTarget, bleed * bleed);
}

// Narkowicz ACES 拟合 + 切伦科夫冰青高光微保色
vec3 stAcesNarkowicz(vec3 x) {
    x = max(stSanitize(x), vec3(0.0));
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return stSaturate3((x * (a * x + b)) / (x * (c * x + d) + e));
}

vec3 stAcesChromaPreserve(vec3 hdrLinear) {
    vec3 c = max(stSanitize(hdrLinear), vec3(0.0));
    vec3 mapped = stAcesNarkowicz(c);
    float peak = max(max(c.r, c.g), c.b);
    float mPeak = max(max(mapped.r, mapped.g), mapped.b);
    vec3 chromaPreserved = (peak > 1.0e-4) ? (c * (mPeak / peak)) : mapped;
    float preserveWeight = 0.24 * exp(-peak * 0.26);
    return stSaturate3(mix(mapped, chromaPreserved, preserveWeight));
}

// 恒等保真物理能量叠加：
//  1. 无发光（emit == 0）时严格恒等于 worldLin * exposure，100% 保持原版 MC 白昼/暗夜色彩零偏移；
//  2. 有发光时在任意背景亮度下严格单调增亮（彻底杜绝白昼晴空叠加柔和外晕反出脏灰暗圈）；
//  3. 极高能自发光经 stFilmEmit + 透射软肩平滑冲顶星白，兼具暗处饱和与强光电影高动态对比。
vec3 stCompositeHdrEnergy(vec3 worldLin, vec3 rawEmitLin, float exposure) {
    vec3 base = stSaturate3(max(stSanitize(worldLin), vec3(0.0)) * exposure);
    vec3 shapedEmit = stFilmEmit(max(stSanitize(rawEmitLin), vec3(0.0))) * exposure;
    float emitPeak = max(max(shapedEmit.r, shapedEmit.g), shapedEmit.b);
    if (emitPeak <= 1.0e-5) {
        return base;
    }
    // 宽动态范围软肩透射压缩（留出 5+ 档 HDR 阶调：中亮吸积盘丝缕分明，仅 18+ 奇点核心白热冲顶）
    vec3 transmit = shapedEmit / (shapedEmit + vec3(1.35));
    vec3 composed = base + (vec3(1.0) - base) * transmit;
    // 对超高能奇点针尖核心叠加微量线性白热冲顶，拉出锋利高光脊
    composed += max(shapedEmit - vec3(4.0), vec3(0.0)) * 0.014;
    return stSaturate3(composed);
}

// 采样 256x1 RGBA16F 物理黑体色温 LUT（1000 K ~ 40000 K 对数映射）
vec3 stSampleBlackbodyLut(sampler2D blackbodyLut, float kelvin) {
    float k = clamp(kelvin, 1000.0, 40000.0);
    float u = log(k / 1000.0) / log(40.0);
    return stSanitize(texture(blackbodyLut, vec2(stSaturate(u), 0.5)).rgb);
}

// 无纹理备选物理黑体近似（线性空间）
vec3 stBlackbodyApprox(float kelvin) {
    float t = clamp(kelvin, 1000.0, 40000.0) / 100.0;
    vec3 rgb;
    if (t <= 66.0) {
        rgb.r = 1.0;
        rgb.g = (99.4708 * log(max(t, 1.0)) - 161.1196) / 255.0;
        rgb.b = (t <= 19.0) ? 0.0 : (138.5177 * log(max(t - 10.0, 1.0)) - 305.0448) / 255.0;
    } else {
        rgb.r = (329.6987 * stSafePow(t - 60.0, -0.1332)) / 255.0;
        rgb.g = (288.1222 * stSafePow(t - 60.0, -0.0755)) / 255.0;
        rgb.b = 1.0;
    }
    return stSrgbToLinear(stSaturate3(rgb));
}

// 物理薄膜虹彩干涉（光程差 φ = 4π·n·d·cosθ / λ，三原色波长 650/532/450 nm）
vec3 stThinFilmIridescence(float cosTheta, float thicknessNm) {
    float opd = 2.0 * 1.38 * thicknessNm * stSaturate(cosTheta);
    vec3 phase = (ST_TAU * opd) / vec3(650.0, 532.0, 450.0);
    vec3 interference = 0.5 + 0.5 * cos(phase);
    return mix(vec3(0.06, 0.90, 1.0), interference * vec3(1.05, 1.0, 1.12), 0.74);
}

float stSoftDepthFade(float sceneT, float fragT, float softness) {
    if (softness <= 1.0e-4) {
        return fragT < sceneT ? 1.0 : 0.0;
    }
    return stSaturate((sceneT - fragT) / softness);
}

vec2 stSafeRefractUv(vec2 baseUv, vec2 offset, float effectT, sampler2D depthTex, mat4 invProj) {
    if (dot(offset, offset) < 1.0e-8) {
        return baseUv;
    }
    vec2 target = clamp(baseUv + offset, vec2(0.001), vec2(0.999));
    float targetDepth = texture(depthTex, target).r;
    float targetT = stSceneDistance(targetDepth, target, invProj);
    if (targetT < effectT - 0.45) {
        return baseUv;
    }
    return target;
}

// 2x2 深度感知双边升采样（支持动态分辨率视口 UV 上限 + 修复全遮挡细柱透墙漏洞）：
// 当全部 4 个低分辨率采样点都位于前景物体之后时，直接返回 vec4(0.0) 杜绝归一化透墙渗漏。
vec4 stBilateralUpsampleMrt(
    sampler2D colorTex,
    sampler2D auxTex,
    vec2 uv,
    vec2 activeSize,
    vec2 uvMax,
    float fullSceneT,
    out vec4 outAux
) {
    vec2 safeActive = max(activeSize, vec2(2.0));
    vec2 safeUvMax = clamp(uvMax, vec2(0.05), vec2(1.0));
    vec2 invTex = safeUvMax / safeActive;
    vec2 minUv = invTex * 0.5;
    vec2 maxUv = max(minUv, safeUvMax - invTex * 0.5);

    vec2 fCoord = stSaturate2(uv) * safeActive - 0.5;
    vec2 base = floor(fCoord);
    vec2 f = stSaturate2(fCoord - base);

    vec2 uv00 = clamp((base + vec2(0.5, 0.5)) * invTex, minUv, maxUv);
    vec2 uv10 = clamp((base + vec2(1.5, 0.5)) * invTex, minUv, maxUv);
    vec2 uv01 = clamp((base + vec2(0.5, 1.5)) * invTex, minUv, maxUv);
    vec2 uv11 = clamp((base + vec2(1.5, 1.5)) * invTex, minUv, maxUv);

    vec4 c00 = stSanitize4(texture(colorTex, uv00));
    vec4 c10 = stSanitize4(texture(colorTex, uv10));
    vec4 c01 = stSanitize4(texture(colorTex, uv01));
    vec4 c11 = stSanitize4(texture(colorTex, uv11));

    vec4 a00 = stSanitize4(texture(auxTex, uv00));
    vec4 a10 = stSanitize4(texture(auxTex, uv10));
    vec4 a01 = stSanitize4(texture(auxTex, uv01));
    vec4 a11 = stSanitize4(texture(auxTex, uv11));

    // 硬判可见性（避免全部被遮挡时微小权重被归一化回 1.0 导致细柱/树叶透墙）
    float v00 = (a00.r > fullSceneT + 0.30 && a00.r < 5.0e5) ? 0.0 : 1.0;
    float v10 = (a10.r > fullSceneT + 0.30 && a10.r < 5.0e5) ? 0.0 : 1.0;
    float v01 = (a01.r > fullSceneT + 0.30 && a01.r < 5.0e5) ? 0.0 : 1.0;
    float v11 = (a11.r > fullSceneT + 0.30 && a11.r < 5.0e5) ? 0.0 : 1.0;

    float b00 = (1.0 - f.x) * (1.0 - f.y);
    float b10 = f.x * (1.0 - f.y);
    float b01 = (1.0 - f.x) * f.y;
    float b11 = f.x * f.y;

    float w00 = b00 * v00;
    float w10 = b10 * v10;
    float w01 = b01 * v01;
    float w11 = b11 * v11;
    float wSum = w00 + w10 + w01 + w11;

    if (wSum < 1.0e-4) {
        outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
        return vec4(0.0);
    }

    float invW = 1.0 / wSum;
    outAux = (a00 * w00 + a10 * w10 + a01 * w01 + a11 * w11) * invW;
    vec4 blended = (c00 * w00 + c10 * w10 + c01 * w01 + c11 * w11) * invW;
    // 当仅极小角落露边时按可见占比平滑衰减，保持前景剪影干净无毛刺
    float edgeSoft = smoothstep(0.02, 0.28, wSum);
    return blended * edgeSoft;
}
