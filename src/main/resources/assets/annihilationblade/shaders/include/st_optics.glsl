// Stellaris Engine 电影光学库（Step 1.6 升格版）：
//  1. Troy Sobotka AgX 电影级局部保真色调映射（守护冰青/电紫宝石色度 + 白昼强光穿透）；
//  2. dFdx/dFdy 斜坡自适应双边升采样（1:1 满分辨率自动 texelFetch 短路）；
//  3. 三重遮挡保护折射（SceneDepth + ReliefMeta）与球面双相压缩空气折射 + 世界锁定漫画刻面；
//  4. 实时世界动态法线重打光与双频电离焦散波纹。
// （使用前需先引入 st_common）

// 光谱泄白（Film Emission）：中高能段（1.5 ~ 6.0）保住物理黑体金红与切伦科夫冰青饱和度，极端奇点（12 ~ 25+）顺滑泄向星白
vec3 stFilmEmit(vec3 emission) {
    vec3 e = max(stSanitize(emission), vec3(0.0));
    float L = stLuma(e);
    if (L <= 1.0e-5) {
        return vec3(0.0);
    }
    const vec3 starWhite = vec3(0.96, 0.99, 1.00);
    float whiteLuma = stLuma(starWhite);
    float bleed = min(1.0 - exp(-0.16 * max(L - 0.85, 0.0)), 0.92);
    vec3 whiteTarget = starWhite * (L / whiteLuma);
    return mix(e, whiteTarget, bleed * bleed);
}

// AgX 6 阶多项式电影对比度 Sigmoid（返回近似 sRGB 域 [0, 1]）
vec3 stAgxContrastApprox(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return stSaturate3(
        + 15.5    * x4 * x2
        - 40.14   * x4 * x
        + 31.96   * x4
        - 6.868   * x2 * x
        + 0.4298  * x2
        + 0.1191  * x
        - 0.00232
    );
}

// 完整 AgX 电影色调映射（输入线性 HDR，输出线性 [0, 1]）：
// 相比 Narkowicz ACES 彻底消除高饱和电紫偏洋红、深蓝发脏的色相扭曲，且在 6+ 档动态范围内高光顺滑滚落星白。
vec3 stAgxToneMap(vec3 hdrLinear) {
    vec3 val = max(stSanitize(hdrLinear), vec3(0.0));
    // Rec.709 Linear -> AgX Inset（列主序）
    const mat3 agxMat = mat3(
        0.842479062253094,  0.0423282422610123, 0.0423756549057051,
        0.0784335999999992, 0.878468636469772,  0.0784336,
        0.0792237451477643, 0.0791661274605434, 0.879142973793104
    );
    // AgX Outset -> Display（列主序）
    const mat3 agxMatInv = mat3(
         1.19687900512017,   -0.0528968517574562, -0.0529716355144438,
        -0.0980208811401368,  1.15190312990417,   -0.0980434501171241,
        -0.0990297440797205, -0.0989611768448433,  1.15107367264116
    );
    const float minEv = -12.47393;
    const float maxEv =   4.026069;

    val = agxMat * val;
    val = clamp(log2(max(val, vec3(1.0e-6))), vec3(minEv), vec3(maxEv));
    val = (val - minEv) / (maxEv - minEv);
    val = stAgxContrastApprox(val);
    val = agxMatInv * val;
    // 转回线性空间以与后续 IEC sRGB 收尾无缝对接
    return stSrgbToLinear(stSaturate3(val));
}

// 兼容保留 Narkowicz ACES 拟合
vec3 stAcesNarkowicz(vec3 x) {
    x = max(stSanitize(x), vec3(0.0));
    return stSaturate3((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14));
}

// 局部恒等保真 × AgX 电影能量合成（Step 1.6 核心画质升级）：
//  1. 零发光且世界处于 [0, 1] 时严格恒等于 worldLin * exposure（原版白昼/暗夜色彩 0.00% 偏移）；
//  2. 有柔和外晕时经单调保底曲线杜绝白昼晴空出现脏灰暗圈；
//  3. 中高能自发光顺滑切入 AgX 电影色调映射并赋予白昼强光穿透力（绝不被白天草地/晴空吃虚）；
//  4. 极高能奇点核心（>5.0）叠加锋利白热脊。
vec3 stCompositeHdrEnergy(vec3 worldLin, vec3 rawEmitLin, float exposure) {
    vec3 rawWorld = max(stSanitize(worldLin), vec3(0.0)) * exposure;
    vec3 base = stSaturate3(rawWorld);
    vec3 worldOverflow = max(rawWorld - vec3(1.0), vec3(0.0));
    vec3 emit = max(stSanitize(rawEmitLin), vec3(0.0)) * exposure + worldOverflow;
    float emitPeak = max(max(emit.r, emit.g), emit.b);
    if (emitPeak <= 1.0e-5) {
        return base;
    }

    vec3 shaped = stFilmEmit(emit);
    vec3 agxTotal = stAgxToneMap(rawWorld + shaped * 1.18);
    // 白昼强光单调保底：即使 base ≈ 0.85，高能光柱/星环依然单调增亮且穿透天空
    vec3 monotonicFloor = stSaturate3(base + (shaped / (shaped + vec3(1.55))) * (vec3(1.0) - base * 0.62));
    float hdrMix = stSaturate(1.0 - exp(-emitPeak * 1.35));
    vec3 composed = mix(base, max(agxTotal, monotonicFloor), hdrMix);
    // 奇点/光子环极高能白热冲顶脊
    composed += max(shaped - vec3(4.5), vec3(0.0)) * 0.013;
    return stSaturate3(composed);
}

// 采样 256x1 RGBA16F 精确普朗克×CIE 物理黑体 LUT（1000 K ~ 40000 K 对数映射）
vec3 stSampleBlackbodyLut(sampler2D blackbodyLut, float kelvin) {
    float k = clamp(kelvin, 1000.0, 40000.0);
    float u = log(k / 1000.0) / log(40.0);
    return stSanitize(texture(blackbodyLut, vec2(stSaturate(u), 0.5)).rgb);
}

// 无纹理备选黑体近似（线性空间）
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

// 基础深度遮挡保护折射 UV
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

// 双重边界遮挡保护折射 UV（同时校验 SceneDepth 与 3D 浮雕法阵 ReliefMeta，彻底消除跨物体鬼影）
vec2 stSafeRefractUvRelief(
    vec2 baseUv,
    vec2 offset,
    float effectT,
    sampler2D depthTex,
    sampler2D reliefMetaTex,
    vec2 reliefUvMax,
    bool hasRelief,
    mat4 invProj
) {
    vec2 target = stSafeRefractUv(baseUv, offset, effectT, depthTex, invProj);
    if (!hasRelief || dot(target - baseUv, target - baseUv) < 1.0e-10) {
        return target;
    }
    vec2 safeMax = clamp(reliefUvMax, vec2(0.05), vec2(1.0));
    vec4 rSrc = texture(reliefMetaTex, clamp(baseUv * safeMax, vec2(0.0005), safeMax - vec2(0.0005)));
    vec4 rDst = texture(reliefMetaTex, clamp(target * safeMax, vec2(0.0005), safeMax - vec2(0.0005)));
    float srcVis = (effectT > rSrc.r + 0.05) ? rSrc.g : 1.0;
    if (rDst.r < effectT && abs(rDst.g - srcVis) > 0.16) {
        return baseUv;
    }
    return target;
}

// 实时世界表面动态彩色重打光 + 双频电离焦散波纹（让黑洞/光柱/星矢真正照亮周围山川、崖壁与建筑）
vec3 stWorldRelight(
    vec3 surfWorldRel,
    vec3 surfNormal,
    vec3 lightPosRel,
    vec3 lightColorLin,
    float lightRadius,
    float intensity,
    float causticAmount,
    float time
) {
    vec3 toLight = lightPosRel - surfWorldRel;
    float dist = length(toLight);
    if (dist > lightRadius * 2.2 || intensity <= 0.001) {
        return vec3(0.0);
    }
    vec3 lDir = toLight / max(dist, 1.0e-4);
    float ndl = stSaturate(dot(surfNormal, lDir));
    float wrapDiffuse = 0.24 + 0.76 * ndl;
    float atten = exp(-dist / max(lightRadius * 0.48, 0.5)) * (1.0 - smoothstep(lightRadius * 1.4, lightRadius * 2.2, dist));

    float caustic = 1.0;
    if (causticAmount > 0.001) {
        vec2 cp = surfWorldRel.xz - lightPosRel.xz;
        float ca = stNoise2(cp * 0.48 + vec2(time * 0.55, -time * 0.34));
        float cb = stNoise2(cp * 0.66 - vec2(time * 0.38,  time * 0.46) + 7.3);
        float web = stSafePow(1.0 - abs(ca - cb), 5.0);
        caustic = 1.0 + web * causticAmount * max(surfNormal.y, 0.2);
    }
    return lightColorLin * (atten * wrapDiffuse * caustic * intensity);
}

// 球面双相空气压缩波折射 + 世界锁定作画级漫画刻面（SA 相变泡 / 曲率坍缩 / 极点爆裂）
// 返回折射后 UV，并通过 outFacetInk 输出 vec2(纯白闪边, 墨黑阴影刻面)
vec2 stSphericalShockRefract(
    vec2 uv,
    vec3 rayDir,
    vec3 centerRel,
    float radius,
    float refractStrength,
    float inkStrength,
    float sceneT,
    mat4 viewProj,
    vec2 outSize,
    sampler2D depthTex,
    mat4 invProj,
    out vec2 outFacetInk
) {
    outFacetInk = vec2(0.0);
    if (radius <= 0.05 || (refractStrength <= 0.001 && inkStrength <= 0.001)) {
        return uv;
    }
    vec3 offset = -centerRel;
    float closest = -dot(offset, rayDir);
    float thickness = clamp(radius * 0.062, 1.8, 9.0);
    float perp = length(offset + rayDir * closest);
    float band = (radius - perp) / thickness;
    if (band < -0.6 || band > 3.0 || closest <= 0.0) {
        return uv;
    }
    float entry = closest - sqrt(max(0.0, radius * radius - perp * perp));
    if (entry < 0.05 || entry >= sceneT - 0.05) {
        return uv;
    }
    vec3 normal = normalize(offset + rayDir * entry);
    vec4 projTan = viewProj * vec4(normal, 0.0);
    vec2 dirPx = (projTan.xy - (uv * 2.0 - 1.0) * projTan.w) * outSize;
    dirPx /= max(length(dirPx), 1.0);

    // 物理双相压波：前沿压缩峰（crest）+ 后沿稀疏回弹谷（release）
    float crest = exp(-6.0 * (band - 1.65) * (band - 1.65));
    float release = exp(-6.0 * (band - 2.40) * (band - 2.40));
    float env = smoothstep(-0.6, -0.18, band) * (1.0 - smoothstep(2.0, 3.0, band));
    vec2 uvOffset = (dirPx / max(outSize, vec2(1.0))) * (outSize.y * 0.013 * (crest - 0.54 * release) * env * refractStrength);
    vec2 refracted = stSafeRefractUv(uv, uvOffset, entry, depthTex, invProj);

    // 世界空间锁定作画级刻面斩痕：转镜头纹丝不动，缝隙处仍透出真实空气扭曲
    if (inkStrength > 0.001) {
        vec3 radial = normalize(offset + rayDir * closest);
        float slashes = abs(fract(dot(radial, vec3(8.3, 5.1, -6.4))) * 2.0 - 1.0);
        float broad = abs(fract(dot(radial, vec3(-2.6, 3.8, 2.2))) * 2.0 - 1.0);
        float stroke = smoothstep(0.18, 0.68, broad + 0.34 * slashes);
        float phase = band + 0.36 * slashes + 0.22 * broad - 0.28;
        float whiteEdge = smoothstep(-0.52, -0.30, phase) * (1.0 - smoothstep(-0.08, 0.40, phase));
        float inkShadow = smoothstep(0.08, 0.42, phase) * (1.0 - smoothstep(1.18, 1.56, phase));
        outFacetInk = vec2(whiteEdge * stroke, inkShadow * (0.40 + 0.60 * stroke)) * inkStrength;
    }
    return refracted;
}

// 屏幕斜坡自适应 2x2 双边升采样（Step 1.6：带 dFdx/dFdy 连续斜坡容差 + 1:1 满分辨率短路）
vec4 stBilateralUpsampleSlope(
    sampler2D colorTex,
    sampler2D auxTex,
    vec2 uv,
    vec2 activeSize,
    vec2 outSize,
    vec2 uvMax,
    float fullSceneT,
    float depthSlope,
    out vec4 outAux
) {
    vec2 safeActive = max(activeSize, vec2(2.0));
    vec2 safeUvMax = clamp(uvMax, vec2(0.05), vec2(1.0));
    float pixelScale = max(outSize.x / safeActive.x, outSize.y / safeActive.y);
    float tolerance = max(0.25, max(fullSceneT * 0.005, min(depthSlope * pixelScale * 2.0, fullSceneT * 0.035)));

    // 1:1 满分辨率（ULTIMATE 1.0x）直接单点 texelFetch 短路，砍掉 75% 升采样带宽！
    if (abs(safeActive.x - outSize.x) < 1.5 && abs(safeActive.y - outSize.y) < 1.5) {
        ivec2 texDim = textureSize(colorTex, 0);
        ivec2 p = clamp(ivec2(stSaturate2(uv) * safeActive), ivec2(0), texDim - ivec2(1));
        vec4 a = stSanitize4(texelFetch(auxTex, p, 0));
        if (a.r < 5.0e5 && (a.r - fullSceneT) > tolerance * 2.0) {
            outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
            return vec4(0.0);
        }
        outAux = a;
        return stSanitize4(texelFetch(colorTex, p, 0));
    }

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

    vec4 a00 = stSanitize4(texture(auxTex, uv00));
    vec4 a10 = stSanitize4(texture(auxTex, uv10));
    vec4 a01 = stSanitize4(texture(auxTex, uv01));
    vec4 a11 = stSanitize4(texture(auxTex, uv11));

    float d00 = a00.r - fullSceneT;
    float d10 = a10.r - fullSceneT;
    float d01 = a01.r - fullSceneT;
    float d11 = a11.r - fullSceneT;

    float v00 = (a00.r >= 5.0e5) ? 1.0 : ((d00 > tolerance * 2.0) ? 0.0 : exp(-max(d00, 0.0) / tolerance));
    float v10 = (a10.r >= 5.0e5) ? 1.0 : ((d10 > tolerance * 2.0) ? 0.0 : exp(-max(d10, 0.0) / tolerance));
    float v01 = (a01.r >= 5.0e5) ? 1.0 : ((d01 > tolerance * 2.0) ? 0.0 : exp(-max(d01, 0.0) / tolerance));
    float v11 = (a11.r >= 5.0e5) ? 1.0 : ((d11 > tolerance * 2.0) ? 0.0 : exp(-max(d11, 0.0) / tolerance));

    float w00 = (1.0 - f.x) * (1.0 - f.y) * v00;
    float w10 = f.x * (1.0 - f.y) * v10;
    float w01 = (1.0 - f.x) * f.y * v01;
    float w11 = f.x * f.y * v11;
    float wSum = w00 + w10 + w01 + w11;

    if (wSum < 1.0e-4) {
        outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
        return vec4(0.0);
    }

    vec4 c00 = stSanitize4(texture(colorTex, uv00));
    vec4 c10 = stSanitize4(texture(colorTex, uv10));
    vec4 c01 = stSanitize4(texture(colorTex, uv01));
    vec4 c11 = stSanitize4(texture(colorTex, uv11));

    float invW = 1.0 / wSum;
    outAux = (a00 * w00 + a10 * w10 + a01 * w01 + a11 * w11) * invW;
    vec4 blended = (c00 * w00 + c10 * w10 + c01 * w01 + c11 * w11) * invW;
    float edgeSoft = smoothstep(0.02, 0.25, wSum);
    return blended * edgeSoft;
}
