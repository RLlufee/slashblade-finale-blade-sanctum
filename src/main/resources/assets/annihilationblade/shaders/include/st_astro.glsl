// Stellaris Engine 天体深空库：抗裁切 LUT 像素脚印星场、360° 无缝 3D 体积噪声暗尘埃银河、安全正交基螺旋星系与蓝噪声极光（需先引入 st_common 与 st_optics）

// 单层 3D 单元星场核心：|offset| <= 0.24、radius <= 0.175，尾迹在格边自然归零无硬切角、无格边暗缝，
// 随像素角脚印做能量守恒缩放，转镜头零闪烁噪点。
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
    // 仅在最外沿 0.45..0.495 处做极窄数学封口，不压暗有效星点、不产生网格暗带
    float borderFade = 1.0 - smoothstep(0.45, 0.495, max(abs(local.x), max(abs(local.y), abs(local.z))));
    float mag = stSafePow(rnd.z, 7.2) * 10.0 + 0.14;
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
    float mag = stSafePow(rnd.z, 7.2) * 10.0 + 0.14;
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

// 物理黑体 LUT 硬件查表版星场（消除逐层 log/pow 解析计算，色彩与物理黑体 100% 对齐）
vec3 stStarfieldLut(vec3 dir, float time, float angularFootprint, sampler2D blackbodyLut) {
    vec3 d = normalize(dir);
    vec3 stars = vec3(0.0);
    stars += stStarLayerInternal(d, 68.0, 0.22, time, angularFootprint, true, blackbodyLut) * 1.25;
    stars += stStarLayerInternal(d * mat3(0.86, 0.50, 0.0, -0.50, 0.86, 0.0, 0.0, 0.0, 1.0), 132.0, 0.28, time * 1.1, angularFootprint, true, blackbodyLut) * 0.82;
    stars += stStarLayerInternal(d * mat3(1.0, 0.0, 0.0, 0.0, 0.76, -0.65, 0.0, 0.65, 0.76), 228.0, 0.34, time * 0.85, angularFootprint, true, blackbodyLut) * 0.46;
    return stars;
}

// 360° 全向无缝银河带 + exp(-τ) 真实暗尘埃带（硬件 64^3 3D 体积噪声高速版，3 次三线性采样替代 96 次过程哈希）
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
    vec4 nFine = texture(noise3d, baseCoord * 2.35 + vec3(0.17, 0.41, -0.29));
    vec4 nDust = texture(noise3d, baseCoord * 1.75 + vec3(-0.33, 0.19, 0.47));

    float cloudBig = nBig.r * 0.65 + nBig.g * 0.35;
    float cloudFine = nFine.r * 0.55 + nFine.g * 0.45;

    vec3 coreDir = normalize(vec3(0.76, 0.0, -0.65));
    float coreAlign = 1.0 - max(dot(gd, coreDir), 0.0);
    float coreBulge = exp(-coreAlign * 4.5) * band;

    float dustNoise = nDust.r * 0.52 + nDust.g * 0.33 + nFine.b * 0.15;
    float dustOpticalDepth = band * smoothstep(0.38, 0.78, dustNoise) * 2.55;
    dustTransmittance = exp(-dustOpticalDepth);

    vec3 deepCyan = vec3(0.015, 0.16, 0.38);
    vec3 electroViolet = vec3(0.18, 0.08, 0.44);
    vec3 warmCore = vec3(0.86, 0.68, 0.48);

    vec3 nebula = mix(deepCyan, electroViolet, smoothstep(0.26, 0.78, cloudFine));
    nebula = mix(nebula, warmCore, coreBulge * 0.54);
    float density = wideBand * (0.20 + 0.80 * cloudBig) + band * cloudFine * 0.72 + coreBulge * 0.68;
    return nebula * density * 0.54 * (0.24 + 0.76 * dustTransmittance);
}

// 无纹理过程兜底版 360° 全向无缝银河带
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

// 远景对数螺旋星系（绝对安全正交基，数学上杜绝任何方向组合除零 NaN）
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
    return coreCol * bulge * 1.55 + armCol * arms * dustLane * 0.78;
}

// 层叠三角波极光噪声与带蓝噪声层间抖动的极光帷幕（14~40 层均丝滑无阶梯）
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
    float layerCount = float(clamp(layers, 4, 40));
    float invLayers = 1.0 / layerCount;
    for (int i = 0; i < 40; i++) {
        if (i >= layers) {
            break;
        }
        float fi = (float(i) + jitter) * invLayers;
        float h = 0.75 + fi * 1.65;
        vec2 p = (d.xz / max(d.y, 0.08)) * h;
        float ribbon = stTriNoise2D(p * 0.28 + vec2(0.0, fi * 0.30), time);
        float mask = smoothstep(0.30, 0.90, ribbon) * exp(-fi * 1.60);
        // 底部切伦科夫冰青 → 中段伽马电紫 → 顶缘高空微绯
        vec3 col = mix(vec3(0.02, 0.88, 1.00), vec3(0.32, 0.12, 1.00), smoothstep(0.14, 0.72, fi));
        col = mix(col, vec3(0.88, 0.14, 0.34), smoothstep(0.74, 1.0, fi) * 0.36);
        accum += col * mask * invLayers;
    }
    float horizonFade = smoothstep(0.03, 0.26, d.y) * (1.0 - smoothstep(0.78, 0.98, d.y));
    return accum * horizonFade * 1.90;
}

vec3 stAuroraCurtains(vec3 dir, float time, int layers) {
    return stAuroraCurtainsJittered(dir, time, layers, 0.5);
}
