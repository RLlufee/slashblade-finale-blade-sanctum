#version 150

#ifdef GL_ARB_explicit_attrib_location
#extension GL_ARB_explicit_attrib_location : enable
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outAux;
#else
out vec4 outColor;
out vec4 outAux;
#endif

#moj_import <annihilationblade:st_common.glsl>

// 星穹引擎 3D 物理圆管浮雕法阵步进着色器（Step 1.6 ReliefPass MRT）：
//  - Attachment 0 (outColor, RGBA16F): 带 3D 管壁法线金属/冰晶高光、棱边菲涅尔与环形充能波的线性 HDR 辐射度；
//  - Attachment 1 (outAux,   RGBA32F): ReliefMeta = vec4(最近命中距离, 剩余透射率, 场景深度距离, 命中法阵ID)。
// 结合 GPU 预烘焙 2048²/4096² RuneCache 距离场 + 8 步亚像素精化，任意放大零锯齿，且允许光柱与万级粒子真实穿孔遮挡！
#define ST_RELIEF_MAX 8

uniform sampler2D Sampler0; // R32F 共享不透明场景深度
uniform sampler2D Sampler1; // 2048²/4096² RGBA16F 预烘焙星穹距离场 RuneCache

uniform mat4 InvProj;
uniform mat4 InvView;
uniform float StTime;
uniform vec2 OutSize;
uniform vec4 ReliefConfig; // x = arrayCount, y = cacheTexSize, z = stepBudget (48..112), w = unused

// 数组 uniform 由 Java 端经 glUniform4fv 在 shader.apply() 后直接上传（避开 Mojang Uniform 16-float 上限）
uniform vec4 ArrayCenterRadius[ST_RELIEF_MAX]; // xyz = 相对相机中心, w = 世界半径
uniform vec4 ArrayNormalSpin[ST_RELIEF_MAX];   // xyz = 单位法线,     w = 外环旋转弧度
uniform vec4 ArrayMotion[ST_RELIEF_MAX];       // x = 内星阵旋转弧度, y = 管径倍率, z = 展开进度(0..1), w = HDR 强度
uniform vec4 ArrayColor[ST_RELIEF_MAX];        // rgb = 线性主色,     a = 变体(0主阵/1聚焦阵/2七星环)
uniform vec4 ArrayViewBounds[ST_RELIEF_MAX];   // xy = minUV, zw = maxUV

in vec2 texCoord;

// 采样 4 通道预烘焙距离场（带外环/内星阵独立反旋）
vec4 samplePlanarFields(vec2 lp, float outerSpin, float innerSpin, float reveal, float variant, float radius) {
    vec2 pOut = stRot2(outerSpin) * (lp / radius);
    vec2 pIn  = stRot2(innerSpin) * (lp / radius);
    vec2 uvOut = pOut / 2.36 + 0.5;
    vec2 uvIn  = pIn  / 2.36 + 0.5;

    // 越界保护：用径向解析距离平滑外推，避免步进在贴图边缘停滞
    float rNorm = length(pOut);
    if (rNorm > 1.15) {
        float ext = (rNorm - 1.08) * radius;
        return vec4(ext, ext + 0.25 * radius, ext + 0.35 * radius, ext + 0.5 * radius);
    }

    vec4 sOut = texture(Sampler1, clamp(uvOut, vec2(0.0005), vec2(0.9995)));
    vec4 sIn  = texture(Sampler1, clamp(uvIn,  vec2(0.0005), vec2(0.9995)));

    // 展开动画：初期先显影外主环，随后展开内星阵与篆星微文
    float innerMask = smoothstep(0.18, 0.72, reveal);
    float runeMask  = smoothstep(0.35, 0.92, reveal);

    float dOuter = sOut.r * radius;
    float dInner = mix(radius * 2.0, sIn.g * radius, innerMask);
    float dRunes = mix(radius * 2.0, sOut.b * radius, runeMask);
    float dCore  = mix(radius * 2.0, sIn.a * radius, smoothstep(0.10, 0.55, reveal));

    // 变体差异：1 = 轨道聚焦阵（强调外环 + 核心聚焦）；2 = 七星子阵（外环 + 核心 + 精简微文）
    if (variant > 1.5) {
        dInner = radius * 2.0;
    } else if (variant > 0.5) {
        dRunes = mix(dRunes, radius * 2.0, 0.35);
    }
    return vec4(dOuter, dInner, dRunes, dCore);
}

// 3D 圆管距离场：length(vec2(planarDist, localY)) - tubeRadius
vec4 tubesFromPlanar(vec4 d, float y, vec4 radii) {
    return vec4(
        length(vec2(d.x, y)) - radii.x,
        length(vec2(d.y, y)) - radii.y,
        length(vec2(d.z, y)) - radii.z,
        length(vec2(d.w, y)) - radii.w
    );
}

float min4(vec4 v) {
    return min(min(v.x, v.y), min(v.z, v.w));
}

bool intersectSlab(vec3 o, vec3 d, vec3 halfSize, out float tEnter, out float tExit) {
    vec3 invD = vec3(
        abs(d.x) > 1.0e-6 ? 1.0 / d.x : 1.0e6,
        abs(d.y) > 1.0e-6 ? 1.0 / d.y : 1.0e6,
        abs(d.z) > 1.0e-6 ? 1.0 / d.z : 1.0e6
    );
    vec3 t0 = (-halfSize - o) * invD;
    vec3 t1 = ( halfSize - o) * invD;
    vec3 lo = min(t0, t1);
    vec3 hi = max(t0, t1);
    tEnter = max(0.0, max(lo.x, max(lo.y, lo.z)));
    tExit  = min(hi.x, min(hi.y, hi.z));
    return tExit >= tEnter;
}

bool traceReliefArray(
    int idx,
    vec3 rdWorld,
    float sceneLimit,
    int stepBudget,
    float cacheTexSize,
    out float outHitT,
    out vec3 outRadiant,
    out float outOpacity
) {
    vec4 cr = ArrayCenterRadius[idx];
    vec4 ns = ArrayNormalSpin[idx];
    vec4 mo = ArrayMotion[idx];
    vec4 colMeta = ArrayColor[idx];

    float radius = max(cr.w, 0.5);
    float reveal = stSaturate(mo.z);
    float intensity = mo.w;
    if (reveal <= 0.005 || intensity <= 0.005) {
        return false;
    }

    vec3 center = cr.xyz;
    vec3 normal = normalize(ns.xyz);
    vec3 uAxis;
    vec3 vAxis;
    stSafeOrthoBasis(normal, uAxis, vAxis);

    // 射线转入法阵局部坐标系（X = uAxis, Y = 法线高度, Z = vAxis）
    vec3 relCam = -center;
    vec3 roL = vec3(dot(relCam, uAxis), dot(relCam, normal), dot(relCam, vAxis));
    vec3 rdL = vec3(dot(rdWorld, uAxis), dot(rdWorld, normal), dot(rdWorld, vAxis));

    float baseTube = clamp(radius * 0.0085, 0.024, 0.24) * mo.y;
    vec4 tubeRadii = vec4(baseTube * 1.18, baseTube * 0.88, baseTube * 0.56, baseTube * 0.96);
    float halfY = max(tubeRadii.x * 2.1, 0.14);

    float tStart;
    float tEnd;
    if (!intersectSlab(roL, rdL, vec3(radius * 1.16, halfY, radius * 1.16), tStart, tEnd)) {
        return false;
    }
    tEnd = min(tEnd, sceneLimit);
    if (tEnd <= tStart) {
        return false;
    }

    float outerSpin = ns.w;
    float innerSpin = mo.x;
    float variant = colMeta.a;
    // 距离场纹理双线性插值误差上界，保守步进避免跳穿极细篆星微文
    float cacheError = (radius * 2.36 / max(cacheTexSize, 512.0)) * 0.72;
    float eps = clamp((tStart / max(OutSize.y, 1.0)) * 0.72, 0.0025, 0.18);

    float t = tStart;
    for (int step = 0; step < 112; step++) {
        if (step >= stepBudget || t > tEnd) {
            return false;
        }
        vec3 pL = roL + rdL * t;
        vec4 planar = samplePlanarFields(pL.xz, outerSpin, innerSpin, reveal, variant, radius);
        float d = min4(tubesFromPlanar(planar, pL.y, tubeRadii));

        if (d < eps) {
            // 8 步亚像素精化：保住远距亚像素细环的真实直径与锋利度，绝不膨胀成粗圆饼
            vec3 bestP = pL;
            float bestD = d;
            float bestT = t;
            for (int sub = 1; sub <= 8; sub++) {
                float candT = min(tEnd, t + eps * float(sub) * 0.25);
                vec3 candP = roL + rdL * candT;
                vec4 cp = samplePlanarFields(candP.xz, outerSpin, innerSpin, reveal, variant, radius);
                float cd = min4(tubesFromPlanar(cp, candP.y, tubeRadii));
                if (cd < bestD) {
                    bestD = cd;
                    bestP = candP;
                    bestT = candT;
                }
                if (candT >= tEnd) {
                    break;
                }
            }

            // 仅需额外 4 次平面采样（Y 轴直接复用 planar 零额外纹理采样）即可重建精确 3D 圆管表面法线！
            float e = max(0.0012, radius * 0.00022);
            vec4 pBase = samplePlanarFields(bestP.xz, outerSpin, innerSpin, reveal, variant, radius);
            float dx = min4(tubesFromPlanar(samplePlanarFields(bestP.xz + vec2(e, 0.0), outerSpin, innerSpin, reveal, variant, radius), bestP.y, tubeRadii))
                     - min4(tubesFromPlanar(samplePlanarFields(bestP.xz - vec2(e, 0.0), outerSpin, innerSpin, reveal, variant, radius), bestP.y, tubeRadii));
            float dy = min4(tubesFromPlanar(pBase, bestP.y + e, tubeRadii))
                     - min4(tubesFromPlanar(pBase, bestP.y - e, tubeRadii));
            float dz = min4(tubesFromPlanar(samplePlanarFields(bestP.xz + vec2(0.0, e), outerSpin, innerSpin, reveal, variant, radius), bestP.y, tubeRadii))
                     - min4(tubesFromPlanar(samplePlanarFields(bestP.xz - vec2(0.0, e), outerSpin, innerSpin, reveal, variant, radius), bestP.y, tubeRadii));
            vec3 nL = normalize(vec3(dx, dy, dz) + vec3(0.0, 1.0e-5, 0.0));

            // 3D 晶体/星穹合金管壁立体受光：顶面明亮、侧面金属高光、掠射角白热棱边 + 环向流动脉冲
            float face = abs(nL.y);
            float bevelEdge = 4.0 * face * (1.0 - face);
            float ndv = stSaturate(abs(dot(nL, -rdL)));
            float fresnel = stSafePow(1.0 - ndv, 2.6);
            vec3 keyLightL = normalize(vec3(0.58, 0.62, 0.52));
            float sideLit = 0.55 + 0.45 * abs(dot(nL, keyLightL));
            vec3 halfVec = normalize(keyLightL - rdL);
            float spec = stSafePow(stSaturate(abs(dot(nL, halfVec))), 24.0);

            float ang = stSafeAtan(bestP.z, bestP.x);
            float flowWave = stSafePow(0.5 + 0.5 * sin(ang * 3.0 - StTime * 2.4 + float(idx) * 1.1), 6.0);
            float rNorm = length(bestP.xz) / radius;
            float radialPulse = 0.88 + 0.12 * sin(rNorm * 18.0 - StTime * 4.2);

            vec3 baseTint = max(colMeta.rgb, vec3(0.01));
            vec3 accentViolet = vec3(0.28, 0.14, 1.00);
            vec3 starWhite = vec3(0.95, 0.99, 1.00);

            // 外环与内星阵自动做双色温立体分化，拉出丰富层次
            vec4 tubeDist = tubesFromPlanar(pBase, bestP.y, tubeRadii);
            bool isInnerOrRune = (min(tubeDist.y, tubeDist.z) < min(tubeDist.x, tubeDist.w));
            vec3 matCol = isInnerOrRune ? mix(baseTint, accentViolet, 0.42) : baseTint;

            vec3 shaded = matCol * (0.48 * sideLit + 0.85 * smoothstep(0.15, 0.95, face));
            shaded += mix(matCol, starWhite, 0.55) * (bevelEdge * 0.55 + fresnel * 0.85 + spec * 1.15);
            shaded += starWhite * (flowWave * 0.65);

            // 展开弧度前沿脉冲
            float sweepAng = (ang + ST_PI) / ST_TAU;
            float arcReveal = (reveal >= 0.995) ? 1.0 : smoothstep(reveal + 0.02, reveal - 0.04, sweepAng);
            float leadFlash = (reveal >= 0.995) ? 0.0 : exp(-abs(sweepAng - reveal) * 42.0) * 1.8;

            float coverage = 1.0 - smoothstep(-eps, eps, bestD);
            float alpha = stSaturate(coverage * arcReveal * smoothstep(0.0, 0.12, reveal));
            if (alpha <= 0.002) {
                return false;
            }
            outHitT = bestT;
            outRadiant = (shaded * radialPulse + starWhite * leadFlash) * intensity;
            outOpacity = alpha;
            return true;
        }
        t += max(0.002, (d - cacheError) * 0.72);
    }
    return false;
}

void main() {
    vec3 rd = stViewRay(texCoord, InvProj, InvView);
    float depth = texture(Sampler0, texCoord).r;
    float sceneLimit = stSceneDistance(depth, texCoord, InvProj);

    int count = clamp(int(ReliefConfig.x + 0.5), 0, ST_RELIEF_MAX);
    if (count <= 0) {
        outColor = vec4(0.0);
        outAux = vec4(1.0e6, 1.0, sceneLimit, 0.0);
        return;
    }

    float cacheSize = max(ReliefConfig.y, 1024.0);
    int stepBudget = clamp(int(ReliefConfig.z + 0.5), 36, 112);

    float hitDist[ST_RELIEF_MAX];
    float hitAlpha[ST_RELIEF_MAX];
    vec3 hitCol[ST_RELIEF_MAX];
    int hitId[ST_RELIEF_MAX];
    for (int i = 0; i < ST_RELIEF_MAX; i++) {
        hitDist[i] = 1.0e6;
        hitAlpha[i] = 0.0;
        hitCol[i] = vec3(0.0);
        hitId[i] = 0;
    }
    int hitCount = 0;

    for (int i = 0; i < ST_RELIEF_MAX; i++) {
        if (i >= count) {
            break;
        }
        vec4 b = ArrayViewBounds[i];
        if (texCoord.x < b.x || texCoord.y < b.y || texCoord.x > b.z || texCoord.y > b.w) {
            continue;
        }
        float tHit;
        vec3 colHit;
        float alphaHit;
        if (traceReliefArray(i, rd, sceneLimit, stepBudget, cacheSize, tHit, colHit, alphaHit)) {
            int pos = hitCount;
            for (int j = ST_RELIEF_MAX - 1; j > 0; j--) {
                if (j > hitCount || j > pos) {
                    continue;
                }
                if (hitDist[j - 1] <= tHit) {
                    break;
                }
                hitDist[j] = hitDist[j - 1];
                hitAlpha[j] = hitAlpha[j - 1];
                hitCol[j] = hitCol[j - 1];
                hitId[j] = hitId[j - 1];
                pos = j - 1;
            }
            hitDist[pos] = tHit;
            hitAlpha[pos] = alphaHit;
            hitCol[pos] = colHit;
            hitId[pos] = i + 1;
            hitCount++;
        }
    }

    if (hitCount == 0) {
        outColor = vec4(0.0);
        outAux = vec4(1.0e6, 1.0, sceneLimit, 0.0);
        return;
    }

    // 严格由近至远遮挡合成，记录最近命中距离与剩余透射率供光柱/粒子穿孔遮挡！
    float transmittance = 1.0;
    vec3 accum = vec3(0.0);
    for (int i = 0; i < ST_RELIEF_MAX; i++) {
        if (i >= hitCount) {
            break;
        }
        accum += hitCol[i] * hitAlpha[i] * transmittance;
        transmittance *= (1.0 - hitAlpha[i]);
        if (transmittance < 0.002) {
            transmittance = 0.0;
            break;
        }
    }

    outColor = vec4(stSanitize(accum), 1.0 - transmittance);
    outAux = vec4(hitDist[0], transmittance, sceneLimit, float(hitId[0]));
}
