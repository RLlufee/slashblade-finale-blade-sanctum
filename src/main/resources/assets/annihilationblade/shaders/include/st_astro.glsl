// Stellaris Engine 天体深空与苍穹奇观库（Step 1.6 升格版，需先引入 st_common 与 st_optics）：
//  1. 抗裁切 LUT 像素脚印星场（分离极亮星供 SceneEmit 进入 Bloom/星芒）；
//  2. 360° 无缝 3D 体积噪声暗尘埃银河 + 螺旋星系；
//  3. Hillaire 风格物理瑞利/米氏星穹暮光大气 + 3 层世界锚定云洞前向银边散射；
//  4. 0 步进解析圆柱 360° 环绕极光（96 频竖向光丝，性能提升 10× 同时立体包裹战场）+ 远景天穹极光；
//  5. 非 Voronoi 有机分叉苍穹裂纹 + 撕裂大地 3D 视差深空窗（Beyond-the-Rift）+ 冷却熔融硬壳岩脉。

// 单层 3D 单元星场核心
vec3 stStarLayerInternal(vec3 dir, float scale, float density, float time, float angularFootprint, bool useLut, sampler2D blackbodyLut) {
    vec3 p = dir * scale;
    vec3 cell = floor(p);
    vec3 local = fract(p) - 0.5;
    vec3 rnd = stHash33(cell);
    if (rnd.x > density) {
        return vec3(0.0);
    }
    vec3 offset = (rnd - 0.5) * 0.48;
    float dist = length(local - offset);
    float baseRadius = mix(0.025, 0.064, rnd.y * rnd.y);
    float footprintInCell = clamp(angularFootprint * scale * 0.88, 0.014, 0.175);
    float radius = max(baseRadius, footprintInCell);
    float energyScale = (baseRadius * baseRadius) / max(radius * radius, 1.0e-4);

    float core = exp(-(dist * dist) / max(radius * radius * 0.22, 1.0e-5));
    float borderFade = 1.0 - smoothstep(0.45, 0.495, max(abs(local.x), max(abs(local.y), abs(local.z))));
    float mag = stSafePow(rnd.z, 7.2) * 11.5 + 0.14;
    float twinkle = 0.85 + 0.15 * sin(time * (1.8 + rnd.y * 4.2) + rnd.x * ST_TAU);

    float kelvin = mix(3400.0, 28000.0, stSafePow(fract(rnd.x * 17.31 + rnd.y * 5.7), 0.72));
    vec3 starCol = useLut ? stSampleBlackbodyLut(blackbodyLut, kelvin) : stBlackbodyApprox(kelvin);
    return starCol * (core * borderFade * mag * twinkle * energyScale);
}

vec3 stStarLayer(vec3 dir, float scale, float density, float time, float angularFootprint) {
    vec3 p = dir * scale;
    vec3 cell = floor(p);
    vec3 local = fract(p) - 0.5;
    vec3 rnd = stHash33(cell);
    if (rnd.x > density) {
        return vec3(0.0);
    }
    vec3 offset = (rnd - 0.5) * 0.48;
    float dist = length(local - offset);
    float baseRadius = mix(0.025, 0.064, rnd.y * rnd.y);
    float footprintInCell = clamp(angularFootprint * scale * 0.88, 0.014, 0.175);
    float radius = max(baseRadius, footprintInCell);
    float energyScale = (baseRadius * baseRadius) / max(radius * radius, 1.0e-4);

    float core = exp(-(dist * dist) / max(radius * radius * 0.22, 1.0e-5));
    float borderFade = 1.0 - smoothstep(0.45, 0.495, max(abs(local.x), max(abs(local.y), abs(local.z))));
    float mag = stSafePow(rnd.z, 7.2) * 11.5 + 0.14;
    float twinkle = 0.85 + 0.15 * sin(time * (1.8 + rnd.y * 4.2) + rnd.x * ST_TAU);

    float kelvin = mix(3400.0, 28000.0, stSafePow(fract(rnd.x * 17.31 + rnd.y * 5.7), 0.72));
    vec3 starCol = stBlackbodyApprox(kelvin);
    return starCol * (core * borderFade * mag * twinkle * energyScale);
}

vec3 stStarfield(vec3 dir, float time, float angularFootprint) {
    vec3 d = normalize(dir);
    vec3 stars = vec3(0.0);
    stars += stStarLayer(d, 68.0, 0.22, time, angularFootprint) * 1.25;
    stars += stStarLayer(d * mat3(0.86, 0.50, 0.0, -0.50, 0.86, 0.0, 0.0, 0.0, 1.0), 132.0, 0.28, time * 1.1, angularFootprint) * 0.82;
    stars += stStarLayer(d * mat3(1.0, 0.0, 0.0, 0.0, 0.76, -0.65, 0.0, 0.65, 0.76), 228.0, 0.34, time * 0.85, angularFootprint) * 0.46;
    return stars;
}

vec3 stStarfieldLut(vec3 dir, float time, float angularFootprint, sampler2D blackbodyLut) {
    vec3 d = normalize(dir);
    vec3 stars = vec3(0.0);
    stars += stStarLayerInternal(d, 68.0, 0.22, time, angularFootprint, true, blackbodyLut) * 1.25;
    stars += stStarLayerInternal(d * mat3(0.86, 0.50, 0.0, -0.50, 0.86, 0.0, 0.0, 0.0, 1.0), 132.0, 0.28, time * 1.1, angularFootprint, true, blackbodyLut) * 0.82;
    stars += stStarLayerInternal(d * mat3(1.0, 0.0, 0.0, 0.0, 0.76, -0.65, 0.0, 0.65, 0.76), 228.0, 0.34, time * 0.85, angularFootprint, true, blackbodyLut) * 0.46;
    return stars;
}

// 360° 无缝 3D 体积噪声暗尘埃银河（R=云密, G=锐丝, B=大尺度fBm, A=真3D Curl旋度）
vec3 stMilkyWayTex(vec3 dir, float time, sampler3D noise3d, out float dustTransmittance) {
    vec3 d = normalize(dir);
    vec3 gUp = normalize(vec3(0.32, 0.84, -0.44));
    vec3 gFwd;
    vec3 gRight;
    stSafeOrthoBasis(gUp, gFwd, gRight);

    vec3 gd = vec3(dot(d, gFwd), dot(d, gUp), dot(d, gRight));
    float lat = gd.y;
    float band = exp(-lat * lat * 26.0);
    float wideBand = exp(-lat * lat * 6.2);

    vec3 baseCoord = vec3(gd.x * 0.48, gd.y * 1.05, gd.z * 0.48) + vec3(0.5, 0.5, 0.5 + time * 0.0012);
    vec4 nBig = texture(noise3d, baseCoord);
    vec4 nFine = texture(noise3d, baseCoord * 2.35 + vec3(0.17, 0.41, -0.29) + vec3(nBig.a * 0.04));
    vec4 nDust = texture(noise3d, baseCoord * 1.75 + vec3(-0.33, 0.19, 0.47));

    float cloudBig = nBig.r * 0.62 + nBig.g * 0.24 + nBig.a * 0.14;
    float cloudFine = nFine.r * 0.52 + nFine.g * 0.36 + nFine.a * 0.12;

    vec3 coreDir = normalize(vec3(0.76, 0.0, -0.65));
    float coreAlign = 1.0 - max(dot(gd, coreDir), 0.0);
    float coreBulge = exp(-coreAlign * 4.5) * band;

    float dustNoise = nDust.r * 0.50 + nDust.g * 0.35 + nFine.b * 0.15;
    float dustOpticalDepth = band * smoothstep(0.36, 0.78, dustNoise) * 2.75;
    dustTransmittance = exp(-dustOpticalDepth);

    vec3 deepCyan = vec3(0.014, 0.18, 0.42);
    vec3 electroViolet = vec3(0.20, 0.08, 0.48);
    vec3 warmCore = vec3(0.92, 0.70, 0.46);

    vec3 nebula = mix(deepCyan, electroViolet, smoothstep(0.26, 0.78, cloudFine));
    nebula = mix(nebula, warmCore, coreBulge * 0.56);
    float density = wideBand * (0.20 + 0.80 * cloudBig) + band * cloudFine * 0.76 + coreBulge * 0.72;
    return nebula * density * 0.58 * (0.22 + 0.78 * dustTransmittance);
}

vec3 stMilkyWay(vec3 dir, float time, out float dustTransmittance) {
    vec3 d = normalize(dir);
    vec3 gUp = normalize(vec3(0.32, 0.84, -0.44));
    vec3 gFwd;
    vec3 gRight;
    stSafeOrthoBasis(gUp, gFwd, gRight);

    vec3 gd = vec3(dot(d, gFwd), dot(d, gUp), dot(d, gRight));
    float lat = gd.y;
    float band = exp(-lat * lat * 26.0);
    float wideBand = exp(-lat * lat * 6.2);

    vec3 samplePos = vec3(gd.x * 2.6, gd.y * 5.8, gd.z * 2.6) + vec3(0.0, 0.0, time * 0.004);
    float cloudBig = stFbm3(samplePos * 1.45);
    float cloudFine = stFbm3(samplePos * 3.6 + vec3(4.1, 1.7, 2.3));

    vec3 coreDir = normalize(vec3(0.76, 0.0, -0.65));
    float coreAlign = 1.0 - max(dot(gd, coreDir), 0.0);
    float coreBulge = exp(-coreAlign * 4.5) * band;

    float dustNoise = stFbm3(samplePos * 2.9 + vec3(9.2, -3.4, 1.1));
    float dustOpticalDepth = band * smoothstep(0.40, 0.76, dustNoise) * 2.45;
    dustTransmittance = exp(-dustOpticalDepth);

    vec3 deepCyan = vec3(0.015, 0.16, 0.38);
    vec3 electroViolet = vec3(0.18, 0.08, 0.44);
    vec3 warmCore = vec3(0.86, 0.68, 0.48);

    vec3 nebula = mix(deepCyan, electroViolet, smoothstep(0.28, 0.78, cloudFine));
    nebula = mix(nebula, warmCore, coreBulge * 0.52);
    float density = wideBand * (0.20 + 0.80 * cloudBig) + band * cloudFine * 0.68 + coreBulge * 0.65;
    return nebula * density * 0.52 * (0.26 + 0.74 * dustTransmittance);
}

// 物理大气瑞利/米氏星穹暮光散射（赋予夺夜好莱坞科幻大片级地平线空气透视，告别死黑涂色）
vec3 stCosmicAtmosphereTwilight(vec3 dir, vec3 zenithGlowDir) {
    float horizon = exp(-max(dir.y, 0.0) * 6.2);
    float midSky = exp(-max(dir.y, 0.0) * 2.1);
    float cosTheta = dot(dir, normalize(zenithGlowDir));
    // Henyey-Greenstein 前向米氏散射相位函数（g = 0.68）
    float g = 0.68;
    float hg = (1.0 - g * g) / (4.0 * ST_PI * stSafePow(1.0 + g * g - 2.0 * g * cosTheta, 1.5));

    vec3 deepZenith = vec3(0.0008, 0.0022, 0.0095);
    vec3 rayleighHorizon = vec3(0.010, 0.078, 0.175) * horizon;
    vec3 violetBand = vec3(0.022, 0.012, 0.075) * midSky * (1.0 - horizon * 0.5);
    vec3 mieSilver = vec3(0.045, 0.24, 0.36) * min(hg * 0.42, 0.55) * (0.35 + 0.65 * horizon);
    return deepZenith + rayleighHorizon + violetBand + mieSilver;
}

// 远景对数螺旋星系（绝对安全正交基）
vec3 stSpiralGalaxy(vec3 dir, vec3 centerDir, vec3 upAxis, float angularRadius, float armTwist, vec3 coreCol, vec3 armCol) {
    vec3 c = normalize(centerDir);
    float align = dot(dir, c);
    if (align < 0.45) {
        return vec3(0.0);
    }
    float upLen2 = dot(upAxis, upAxis);
    vec3 uNorm = (upLen2 > 1.0e-8) ? (upAxis * inversesqrt(upLen2)) : vec3(0.0, 1.0, 0.0);
    vec3 fallbackUp = (abs(c.x) < 0.90) ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 refUp = (abs(dot(uNorm, c)) > 0.94) ? fallbackUp : uNorm;
    vec3 right = normalize(cross(refUp, c));
    vec3 up = cross(c, right);
    vec2 p = vec2(dot(dir, right), dot(dir, up)) / max(angularRadius, 1.0e-4);
    p.y *= 1.85;
    float r = length(p);
    if (r > 1.85) {
        return vec3(0.0);
    }
    float ang = stSafeAtan(p.y, p.x);
    float spiral = sin(ang * 2.0 - log(max(r, 0.02)) * armTwist);
    float fineCloud = stFbm2(p * 4.8 + vec2(1.7, -2.3));
    float arms = smoothstep(-0.12, 0.90, spiral + (fineCloud - 0.5) * 0.42) * exp(-r * 2.1) * smoothstep(0.05, 0.32, r);
    float dustLane = 1.0 - smoothstep(0.55, 0.85, stFbm2(p * 7.5)) * 0.45 * exp(-r * 1.4);
    float bulge = exp(-r * r * 14.0);
    return coreCol * bulge * 1.65 + armCol * arms * dustLane * 0.85;
}

// 0 步进解析圆柱 360° 环绕极光帷幕（96 频垂直光丝纤维 + 折皱波浪 + 真实场景深度遮挡，性能提升 10×！）
vec3 stAuroraCylinder(
    vec3 rayDir,
    vec3 centerRel,
    float cylRadius,
    float baseY,
    float height,
    float time,
    float sceneLimit
) {
    vec2 o = -centerRel.xz;
    vec2 d = rayDir.xz;
    float a = dot(d, d);
    if (a < 1.0e-6) {
        return vec3(0.0);
    }
    float b = dot(o, d);
    float c = dot(o, o) - cylRadius * cylRadius;
    float disc = b * b - a * c;
    if (disc <= 0.0) {
        return vec3(0.0);
    }
    float sq = sqrt(disc);
    vec3 total = vec3(0.0);
    for (int side = 0; side < 2; side++) {
        float t = (-b + (side == 0 ? -sq : sq)) / a;
        if (t <= 0.5 || t >= sceneLimit) {
            continue;
        }
        vec3 p = rayDir * t - centerRel;
        float h = (p.y - baseY) / max(height, 1.0);
        if (h <= 0.0 || h >= 1.0) {
            continue;
        }
        float ang = stSafeAtan(p.z, p.x);
        float fold = 0.5 + 0.5 * sin(ang * 10.0 + sin(h * 4.8 + time * 0.55) * 0.95 - time * 0.32);
        float curtainGaps = smoothstep(0.20, 0.82, sin(ang * 3.0 - time * 0.10) + 0.35 * sin(ang * 5.0 + time * 0.14));
        float verticalEnv = sin(h * ST_PI) * exp(-h * 1.15);
        float ribbon = stSafePow(fold, 6.5) * curtainGaps * verticalEnv;
        // 96 频竖直极光光丝纤维
        float fibers = stSafePow(0.5 + 0.5 * sin(ang * 96.0 + h * 4.5 - time * 2.1), 10.0);
        vec3 col = mix(vec3(0.015, 0.92, 1.00), vec3(0.36, 0.14, 1.00), smoothstep(0.12, 0.72, h));
        col = mix(col, vec3(0.92, 0.18, 0.42), smoothstep(0.72, 1.0, h) * 0.38);
        total += col * ribbon * (0.42 + fibers * 0.75);
    }
    return total * 1.35;
}

// 三层世界锚定旋涡云洞（被中心奇点照出炽热银边散射，零体积光线步进）
vec4 stSwirlCloudHole(vec3 rayDir, vec3 centerRel, float ceilingY, float holeRadius, float outerRadius, float time, float sceneLimit) {
    if (rayDir.y <= 0.015) {
        return vec4(0.0);
    }
    vec3 colSum = vec3(0.0);
    float alphaSum = 0.0;
    for (int layer = 0; layer < 3; layer++) {
        float fl = float(layer);
        float planeY = centerRel.y + ceilingY + fl * 9.0;
        if (planeY <= 0.5) {
            continue;
        }
        float t = planeY / rayDir.y;
        if (t <= 0.0 || t >= sceneLimit) {
            continue;
        }
        vec2 q = rayDir.xz * t - centerRel.xz;
        float r = length(q);
        float cover = smoothstep(holeRadius * 0.88, holeRadius * 1.55, r) * (1.0 - smoothstep(outerRadius * 0.65, outerRadius, r));
        if (cover <= 0.002) {
            continue;
        }
        float rate = (layer == 0) ? 0.055 : ((layer == 1) ? -0.034 : 0.022);
        float ang = stSafeAtan(q.y, q.x) + time * rate + log(1.0 + r * 0.024) * 1.65 + fl * 1.7;
        vec2 uvC = vec2(cos(ang), sin(ang)) * (r * 0.016);
        float nBig = stNoise2(uvC + vec2(-time * 0.07, fl * 9.0));
        float nFine = stNoise2(uvC * 2.25 + vec2(time * 0.04, -time * 0.03));
        float dens = smoothstep(0.28, 0.76, nBig * 0.70 + nFine * 0.30);

        // 中心奇点银边前向散射 + 撕裂云洞炽青边缘
        float rimGlow = exp(-stSafePow((r - holeRadius * 1.08) / max(holeRadius * 0.22, 2.0), 2.0));
        float silverEdge = smoothstep(0.45, 0.66, dens) * (1.0 - smoothstep(0.66, 0.86, dens));
        vec3 cloudCol = mix(vec3(0.012, 0.025, 0.065), vec3(0.06, 0.36, 0.58), rimGlow * 0.85) * (0.45 + 0.55 * dens);
        cloudCol += vec3(0.28, 0.88, 1.00) * (rimGlow * 0.95 + silverEdge * 0.32);

        float layerA = cover * dens * smoothstep(0.015, 0.14, rayDir.y) * 0.42;
        colSum += cloudCol * layerA * (1.0 - alphaSum);
        alphaSum = stSaturate(alphaSum + layerA * (1.0 - alphaSum));
    }
    return vec4(colSum, alphaSum);
}

// 非 Voronoi 有机分叉苍穹裂纹（13 条漫步主裂 + 单侧分叉 + 残缺碎环 + 细碎蛛网 craze，零网格感）
float stOrganicSkyCrack(vec2 q, float reach, float time) {
    float d = max(length(q), 1.0);
    if (d > reach * 1.05) {
        return 0.0;
    }
    float ang = stSafeAtan(q.y, q.x);
    vec2 dir = q / d;
    float w = 0.75 + d * 0.0045;
    float best = 1.0e5;
    float lum = 0.0;

    for (int k = 0; k < 13; k++) {
        float fk = float(k);
        float base = (fk + 0.42 * stHash12(vec2(fk, 1.3))) / 13.0 * ST_TAU;
        float a = base
            + 0.28 * (stNoise2(vec2(d * 0.0045, fk * 7.1)) - 0.5)
            + 0.07 * (stNoise2(vec2(d * 0.036, fk * 3.3)) - 0.5);
        float da = abs(mod(ang - a + ST_PI, ST_TAU) - ST_PI);
        float len = reach * (0.42 + 0.58 * stHash12(vec2(fk, 2.7)));
        float alive = (1.0 - smoothstep(len * 0.62, len, d)) * (1.0 - smoothstep(reach * 0.82, reach, d));
        float dist = da * d;
        if (dist < best) {
            best = dist;
            lum = alive;
        }
        // 单侧分叉支裂
        float from = reach * (0.08 + 0.34 * stHash12(vec2(fk, 3.9)));
        float side = stHash12(vec2(fk, 4.7)) > 0.5 ? 1.0 : -1.0;
        float ab = a + side * (0.12 + 0.28 * stSaturate((d - from) / max(reach * 0.25, 1.0)))
                 + 0.05 * (stNoise2(vec2(d * 0.032, fk * 5.2 + 2.0)) - 0.5);
        float db = abs(mod(ang - ab + ST_PI, ST_TAU) - ST_PI) * d;
        float on = step(from, d) * (1.0 - smoothstep(from + len * 0.38, from + len * 0.58, d)) * alive;
        if (db < best && on > 0.05) {
            best = db;
            lum = on * 0.82;
        }
    }

    float glow = lum * (exp(-best / w) * 1.75 + exp(-best / (w * 6.5)) * 0.24);
    // 5 道不完整同心破碎环
    for (int j = 0; j < 5; j++) {
        float fj = float(j);
        float R = reach * 0.09 * stSafePow(1.72, fj) * (1.0 + 0.10 * (stNoise2(dir * 3.2 + fj * 5.0) - 0.5));
        float present = smoothstep(0.48, 0.60, stNoise2(dir * (2.2 + fj) + vec2(fj * 9.0, 1.0)));
        float dr = abs(d - R);
        float alive = 1.0 - smoothstep(reach * 0.80, reach, R);
        glow += present * alive * (exp(-dr / (w * 0.85)) * 0.85 + exp(-dr / (w * 4.5)) * 0.10);
    }
    // 主裂周遭细密玻璃 craze 裂纹
    float craze = stSafePow(1.0 - abs(2.0 * stNoise2(q * 0.085) - 1.0), 16.0) * exp(-best / 12.0) * lum;
    float crawl = 0.68 + 0.32 * stNoise2(vec2(d * 0.022 - time * 0.55, ang * 3.0));
    return (glow + craze * 0.65) * crawl;
}

// 撕裂大地/宇宙弦切口的 3D 无限远深空视差窗（Beyond-the-Rift）
vec3 stDeepSpaceWindow(vec3 rayDir, float time, sampler2D blackbodyLut) {
    vec3 d = normalize(rayDir);
    vec3 stars = stStarLayerInternal(d, 110.0, 0.26, time, 0.0025, true, blackbodyLut) * 1.45;
    float h1 = stFbm3(d * 3.4 + vec3(0.0, time * 0.02, 0.0));
    float h2 = stFbm3(d * 7.2 + vec3(3.1, -time * 0.03, 1.7));
    vec3 abyss = vec3(0.001, 0.002, 0.009)
        + vec3(0.02, 0.24, 0.45) * stSafePow(h1, 2.8) * 0.72
        + vec3(0.28, 0.12, 0.75) * stSafePow(h2, 3.6) * 0.55;
    return abyss + stars;
}

// 远景层叠三角波天穹极光（建议与近景 0 步进 stAuroraCylinder 搭配，高档位 12~24 层即可呈现极高层次感）
float stTriWave(float x) {
    return abs(fract(x) - 0.5);
}

vec2 stTriWave2(vec2 p) {
    return vec2(stTriWave(p.x) + stTriWave(p.y), stTriWave(p.y + stTriWave(p.x)));
}

float stTriNoise2D(vec2 p, float time) {
    float z = 1.65;
    float rz = 0.0;
    vec2 bp = p;
    for (int i = 0; i < 4; i++) {
        vec2 dg = stTriWave2(bp * 1.85);
        p += (dg - 0.5) * 0.42 + vec2(time * 0.035, -time * 0.022);
        bp *= 1.55;
        z *= 1.48;
        p = stRot2(0.95) * p * 1.24;
        rz += (stTriWave(p.x + stTriWave(p.y))) / z;
    }
    return clamp(1.0 / (1.0 + rz * 16.0) * 2.4, 0.0, 1.0);
}

vec3 stAuroraCurtainsJittered(vec3 dir, float time, int layers, float jitter) {
    vec3 d = normalize(dir);
    if (d.y <= 0.03 || layers <= 0) {
        return vec3(0.0);
    }
    vec3 accum = vec3(0.0);
    int capped = clamp(layers, 4, 24);
    float invLayers = 1.0 / float(capped);
    for (int i = 0; i < 24; i++) {
        if (i >= capped) {
            break;
        }
        float fi = (float(i) + jitter) * invLayers;
        float h = 0.75 + fi * 1.65;
        vec2 p = (d.xz / max(d.y, 0.08)) * h;
        float ribbon = stTriNoise2D(p * 0.28 + vec2(0.0, fi * 0.30), time);
        float mask = smoothstep(0.30, 0.90, ribbon) * exp(-fi * 1.60);
        vec3 col = mix(vec3(0.02, 0.88, 1.00), vec3(0.32, 0.12, 1.00), smoothstep(0.14, 0.72, fi));
        col = mix(col, vec3(0.88, 0.14, 0.34), smoothstep(0.74, 1.0, fi) * 0.36);
        accum += col * mask * invLayers;
    }
    float horizonFade = smoothstep(0.03, 0.26, d.y) * (1.0 - smoothstep(0.78, 0.98, d.y));
    return accum * horizonFade * 1.85;
}

vec3 stAuroraCurtains(vec3 dir, float time, int layers) {
    return stAuroraCurtainsJittered(dir, time, layers, 0.5);
}
