#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 因果坍缩 · 裂界同源时空裂痕场景通道（单 Pass 最多打包 16 处局部裂痕）：
//  · 以弯曲锯齿裂隙为奇点中轴，在无硬截断的 C2 五次包络引力场内施加对数螺旋扭转、向心抽吸与微热浪畸变；
//  · 采用 7 采样 2D 光学散焦模糊 + 5 波段连续光谱色散，叠加外圈虚空靛紫海市蜃楼大气柔化，消除刚性折边；
//  · 裂口内部压出漆黑虚空深渊（含深紫星云暗流与微弱星尘），外缘勾勒裂界品红 / 兰粉白热锋芒与极细棱青微芒；
//  · 接入平滑深度遮挡混合（Sampler1），避免前景树冠或墙体产生锯齿割裂边。
uniform sampler2D Sampler0; // 场景颜色
uniform sampler2D Sampler1; // 场景原始深度（R32F）
uniform mat4 InvProj;
uniform vec2 OutSize;
uniform float FxTime;
// 每个 mat4 打包 2 处时空裂痕：
//   列 0 / 列 2 = (centerU, centerV, halfLenScreen, viewDist)
//   列 1 / 列 3 = (tiltAngleRad, signedTwistRad, crackOpen, seedPhase)
uniform mat4 RiftA;
uniform mat4 RiftB;
uniform mat4 RiftC;
uniform mat4 RiftD;
uniform mat4 RiftE;
uniform mat4 RiftF;
uniform mat4 RiftG;
uniform mat4 RiftH;
// x = 活跃裂痕数(0..16), y = 色散倍率, z = 边缘辉光倍率, w = 全局强度
uniform vec4 Meta;

in vec2 texCoord;
out vec4 fragColor;

vec2 rotate2(vec2 v, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

// 五次平滑包络：在边缘 t=0 和核心 t=1 处一阶、二阶导数同时为零，消除折痕突变
float quintic01(float t) {
    float x = clamp(t, 0.0, 1.0);
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
}

// 平滑前景深度防穿帮偏移：当折射终点接近前景遮挡物时渐进回退，避免硬像素阶跃
vec2 fxSoftRefractUv(vec2 baseUv, vec2 offset, float effectT, sampler2D depthTex, mat4 invProj) {
    if (dot(offset, offset) < 1.0e-8) {
        return baseUv;
    }
    vec2 target = clamp(baseUv + offset, vec2(0.001), vec2(0.999));
    float targetDepth = texture(depthTex, target).r;
    float targetT = fxSceneDistance(targetDepth, target, invProj);
    float depthKeep = smoothstep(effectT - 1.75, effectT - 0.15, targetT);
    return clamp(baseUv + (target - baseUv) * depthKeep, vec2(0.001), vec2(0.999));
}

// 7 采样 2D 光学散焦 + 5 波段连续光谱采样（RGB 三通道权重总和严格恒等于 1.0，零偏色零闪烁）
vec3 sampleBlurredSpectrum(
    vec2 baseUv, vec2 totalOffset, vec2 caVec, vec2 blurDirA, float aspect,
    float blurR, float minRefractT
) {
    vec2 safeDir = dot(blurDirA, blurDirA) > 1.0e-8 ? normalize(blurDirA) : vec2(1.0, 0.0);
    vec2 orthoDir = vec2(-safeDir.y, safeDir.x);

    vec2 tapPos[7] = vec2[7](
        vec2( 0.00,  0.00),
        vec2(-0.86, -0.34),
        vec2(-0.42,  0.68),
        vec2( 0.42, -0.68),
        vec2( 0.86,  0.34),
        vec2( 0.18,  0.90),
        vec2(-0.18, -0.90)
    );
    float tapSpecT[7] = float[7](0.00, -1.00, -0.50, 0.50, 1.00, -0.20, 0.20);
    vec3 tapW[7] = vec3[7](
        vec3(0.10, 0.22, 0.10),
        vec3(0.42, 0.04, 0.00),
        vec3(0.32, 0.26, 0.03),
        vec3(0.03, 0.26, 0.32),
        vec3(0.07, 0.04, 0.43),
        vec3(0.04, 0.09, 0.06),
        vec3(0.02, 0.09, 0.06)
    );

    vec3 accum = vec3(0.0);
    for (int i = 0; i < 7; i++) {
        vec2 blurAspect = (safeDir * tapPos[i].x + orthoDir * tapPos[i].y) * blurR;
        vec2 off = totalOffset + caVec * tapSpecT[i] + vec2(blurAspect.x / aspect, blurAspect.y);
        vec2 suv = fxSoftRefractUv(baseUv, off, minRefractT, Sampler1, InvProj);
        accum += texture(Sampler0, suv).rgb * tapW[i];
    }
    return accum;
}

void applyCausalScar(
    vec4 pos, vec4 prm, vec2 p, float aspect, float sceneT,
    inout vec2 totalOffset, inout vec2 caShift, inout vec2 totalBlurFlow,
    inout float maxBlur, inout float outerHaze, inout float minRefractT,
    inout float maxAbyss, inout float rimGlow, inout float cyanRim,
    inout float spiralSheen, inout float darkMask
) {
    float halfLen = pos.z;
    float signedTwist = prm.y;
    float twistMag = abs(signedTwist);
    float crackOpen = prm.z;
    if (halfLen <= 1.0e-4 || (twistMag <= 1.0e-3 && crackOpen <= 1.0e-4)) {
        return;
    }

    vec2 centerA = vec2(pos.x * aspect, pos.y);
    vec2 rel = p - centerA;
    // 外包圆严格大于下方的椭圆外边界 (1.92 / 0.72 + spine ~ 2.75)，杜绝圆形裁切椭圆造成的尖角硬边
    float maxBound = halfLen * 2.95;
    if (dot(rel, rel) > maxBound * maxBound) {
        return;
    }

    float viewDist = pos.w;
    // 更柔和的前景遮挡衰减区间，防止树叶/墙体边缘出现硬切
    float occMask = smoothstep(max(0.15, viewDist - 1.95), max(0.45, viewDist + 0.05), sceneT);
    if (occMask <= 1.0e-4) {
        return;
    }

    float tilt = prm.x;
    float c = cos(tilt);
    float s = sin(tilt);
    vec2 dir = vec2(c, s);     // 沿裂痕长轴方向
    vec2 nrm = vec2(-s, c);    // 垂直于裂痕方向

    float along = dot(rel, dir);
    float across = dot(rel, nrm);
    float u = along / halfLen;
    float uClamped = clamp(u, -1.0, 1.0);
    float taper = pow(max(1.0 - uClamped * uClamped, 1.0e-5), 0.72);

    float seed = prm.w;
    // 紧致的三频弯曲裂隙脊线（与 fx_fold_volume 及 CausalityCollapseFx 数学完全锁步）
    float jag = sin(uClamped * 3.8 + seed) * 0.042
        + sin(uClamped * 10.5 + seed * 2.1) * 0.018
        + cos(uClamped * 21.0 + seed * 3.3) * 0.008;
    float spineOffset = jag * halfLen * taper;

    // 像素到弯曲裂痕曲线的真实欧氏距离
    vec2 toSlit = vec2(along - uClamped * halfLen, across - spineOffset);
    float dEdge = length(toSlit);
    float side = (across - spineOffset) >= 0.0 ? 1.0 : -1.0;

    // 统一椭圆涡旋场（vortexR == 1.0 时所有畸变、色散、模糊与海市蜃楼晕同步平滑归零）
    vec2 q = vec2(along * 0.72, across - spineOffset * 0.65);
    float vortexR = length(q) / (halfLen * 1.92);
    if (vortexR >= 1.0) {
        return;
    }

    float vNorm = clamp(vortexR, 0.0, 1.0);
    float fall = max(1.0 - vNorm, 1.0e-5);
    float q5 = quintic01(fall);
    float coreCurve = q5 * pow(fall, 1.45);
    float pullCurve = q5 * pow(fall, 1.65);
    float spinSign = signedTwist >= 0.0 ? 1.0 : -1.0;
    float warpIntensity = clamp(twistMag / 9.2, 0.0, 1.38);

    // 极坐标对数螺旋空间拧转 + 动态空气剪切波纹（外缘以 C2 五次函数柔顺收零）
    float theta = fxSafeAtan(q.y, q.x);
    float logR = log(max(vNorm, 0.02));
    float airShear = sin(theta * 3.0 + logR * 7.6 - FxTime * 11.2 * spinSign);
    float rippleMod = 1.0 + 0.08 * airShear * q5;
    float angle = signedTwist * coreCurve * rippleMod * occMask;
    vec2 twisted = rotate2(rel, angle);

    // 向心引力抽吸：强扭曲主要凝聚在裂隙中内环，外缘渐进弯曲不陡立拉丝
    float pull = clamp(warpIntensity * 0.54 * pullCurve * occMask, 0.0, 0.86);
    float radialScale = 1.0 + pull * (0.52 + 1.45 * q5);
    vec2 warpedRel = twisted * radialScale;

    // 紧贴裂口处叠加局部因果剪切滑移
    float slitShearBand = exp(-dEdge / max(halfLen * 0.13, 0.008)) * taper * occMask;
    float faultAmt = halfLen * 0.062 * warpIntensity * slitShearBand;
    warpedRel += dir * (side * faultAmt) - nrm * (side * faultAmt * 0.28);

    // 最外圈极微弱热浪空间微抖（让树叶与天空在进入漩涡前先微微晃动散焦）
    float mirageGate = q5 * smoothstep(0.15, 0.72, vNorm) * warpIntensity * occMask;
    float shimmerWaveA = sin(theta * 4.0 + vNorm * 16.0 - FxTime * 6.5 * spinSign + seed);
    float shimmerWaveB = cos(theta * 6.0 - vNorm * 12.0 + FxTime * 4.8);
    warpedRel += (nrm * shimmerWaveA + dir * shimmerWaveB * 0.6) * (halfLen * 0.011 * mirageGate);

    vec2 dispAspect = warpedRel - rel;
    vec2 dispUv = vec2(dispAspect.x / aspect, dispAspect.y);
    float dispLen = length(dispUv);
    if (dispLen > 0.32) {
        dispUv *= 0.32 / dispLen;
    }
    totalOffset += dispUv;

    // 连续光谱色散：C2 包络自然收敛至 0，无硬边界彩虹切条
    float dist = length(rel);
    vec2 safeDir = dist > 1.0e-4 ? (rel / dist) : dir;
    vec2 tang = vec2(-safeDir.y, safeDir.x) * spinSign;
    float caWeight = q5 * (0.20 + 0.80 * pow(fall, 0.90)) * warpIntensity * occMask;
    caShift += vec2(tang.x / aspect, tang.y) * caWeight * 0.0145 + dispUv * 0.085;

    // 光学散焦模糊权重：略早于强扭曲在过渡带铺开，形成柔和的引力透镜柔焦带
    float outerBlurBand = q5 * (0.46 + 0.54 * smoothstep(0.08, 0.56, vNorm));
    float strainBlur = smoothstep(0.002, 0.065, dispLen) * q5;
    float blurW = clamp((outerBlurBand * 0.82 + strainBlur * 0.58) * (0.35 + 0.65 * warpIntensity) * occMask, 0.0, 1.15);
    maxBlur = max(maxBlur, blurW);
    totalBlurFlow += tang * (0.55 * caWeight + 0.15 * blurW) + dispAspect * 1.9 + safeDir * (0.12 * blurW);

    // 外圈虚空海市蜃楼大气薄纱（过渡带微微柔化对比并染上淡靛紫空灵雾气）
    float hazeBell = q5 * smoothstep(0.24, 0.78, vNorm) * warpIntensity * occMask;
    outerHaze = max(outerHaze, hazeBell);

    minRefractT = min(minRefractT, max(viewDist - 1.35, 0.0));

    // 外圈螺旋空气剪切纹与引力暗晕
    float band = pow(max(0.5 + 0.5 * airShear, 1.0e-4), 4.0) * smoothstep(0.05, 0.34, vNorm) * q5;
    spiralSheen += band * warpIntensity * occMask;
    darkMask = max(darkMask, q5 * pow(fall, 1.35) * 0.38 * warpIntensity * occMask);

    // 中央漆黑虚空暗缝 + 品红/兰粉灼热内锋 + 极细棱青点缀
    float slitW = halfLen * (0.016 + 0.036 * crackOpen) * (0.14 + 0.86 * taper);
    float openGate = smoothstep(0.0, 0.05, crackOpen);
    float abyss = (1.0 - smoothstep(slitW * 0.50, slitW * 1.05, dEdge)) * openGate * occMask;
    maxAbyss = max(maxAbyss, abyss);

    float rimBand = exp(-abs(dEdge - slitW * 1.04) / max(slitW * 0.42, halfLen * 0.011))
        * openGate * (0.24 + 0.76 * taper) * occMask
        * (0.85 + 0.20 * sin(uClamped * 22.0 - FxTime * 11.0 * spinSign + seed));
    rimGlow += rimBand;

    float prismPulse = pow(max(0.5 + 0.5 * cos(uClamped * 14.0 + FxTime * 6.0 + seed * 2.1), 1.0e-4), 4.0);
    float prismBand = exp(-abs(dEdge - slitW * 1.58) / max(slitW * 0.56, halfLen * 0.016))
        * openGate * taper * occMask * prismPulse;
    cyanRim += prismBand;
}

void main() {
    vec2 uv = texCoord;
    int count = int(Meta.x + 0.5);
    if (count <= 0) {
        fragColor = vec4(texture(Sampler0, uv).rgb, 1.0);
        return;
    }

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 p = vec2(uv.x * aspect, uv.y);
    float depth = texture(Sampler1, uv).r;
    float sceneT = fxSceneDistance(depth, uv, InvProj);

    vec2 totalOffset = vec2(0.0);
    vec2 caShift = vec2(0.0);
    vec2 totalBlurFlow = vec2(0.0);
    float maxBlur = 0.0;
    float outerHaze = 0.0;
    float minRefractT = 1.0e6;
    float maxAbyss = 0.0;
    float rimGlow = 0.0;
    float cyanRim = 0.0;
    float spiralSheen = 0.0;
    float darkMask = 0.0;

    if (count > 0) applyCausalScar(RiftA[0], RiftA[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 1) applyCausalScar(RiftA[2], RiftA[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 2) applyCausalScar(RiftB[0], RiftB[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 3) applyCausalScar(RiftB[2], RiftB[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 4) applyCausalScar(RiftC[0], RiftC[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 5) applyCausalScar(RiftC[2], RiftC[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 6) applyCausalScar(RiftD[0], RiftD[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 7) applyCausalScar(RiftD[2], RiftD[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 8) applyCausalScar(RiftE[0], RiftE[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 9) applyCausalScar(RiftE[2], RiftE[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 10) applyCausalScar(RiftF[0], RiftF[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 11) applyCausalScar(RiftF[2], RiftF[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 12) applyCausalScar(RiftG[0], RiftG[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 13) applyCausalScar(RiftG[2], RiftG[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 14) applyCausalScar(RiftH[0], RiftH[1], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);
    if (count > 15) applyCausalScar(RiftH[2], RiftH[3], p, aspect, sceneT, totalOffset, caShift, totalBlurFlow, maxBlur, outerHaze, minRefractT, maxAbyss, rimGlow, cyanRim, spiralSheen, darkMask);

    if (dot(totalOffset, totalOffset) < 1.0e-8 && maxBlur < 1.0e-4 && outerHaze < 1.0e-4 && maxAbyss < 1.0e-4 && rimGlow < 1.0e-4 && cyanRim < 1.0e-4) {
        fragColor = vec4(texture(Sampler0, uv).rgb, 1.0);
        return;
    }

    float totalLen = length(totalOffset);
    if (totalLen > 0.34) {
        totalOffset *= 0.34 / totalLen;
    }

    vec2 ca = clamp(caShift * Meta.y, vec2(-0.024), vec2(0.024));
    float blurR = clamp(maxBlur * 0.0155, 0.0, 0.022);
    vec3 color;
    if (blurR > 0.0004 || dot(ca, ca) > 1.0e-7) {
        color = sampleBlurredSpectrum(uv, totalOffset, ca, totalBlurFlow, aspect, blurR, minRefractT);
    } else {
        vec2 warpedUv = fxSoftRefractUv(uv, totalOffset, minRefractT, Sampler1, InvProj);
        color = texture(Sampler0, warpedUv).rgb;
    }

    // 过渡带大气薄纱：在明快草地/天空与漩涡之间轻轻去饱和并蒙上一层靛紫引力雾，衔接自然不生硬
    vec3 voidPurple = vec3(0.42, 0.12, 0.90);
    vec3 kamuiMagenta = vec3(0.95, 0.24, 0.96);
    vec3 hotOrchid = vec3(1.0, 0.78, 0.99);
    vec3 prismCyan = vec3(0.32, 0.88, 1.0);

    float hazeAmt = clamp(outerHaze, 0.0, 1.0);
    if (hazeAmt > 0.001) {
        float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
        color = mix(color, vec3(luma), hazeAmt * 0.22);
        color = mix(color, color * 0.86 + voidPurple * 0.24, hazeAmt * 0.28);
    }

    // 涡心引力压暗 + 虚空紫/裂界品红空间旋流细纹
    color *= 1.0 - clamp(darkMask, 0.0, 0.52);
    color += mix(voidPurple, kamuiMagenta, clamp(spiralSheen * 0.75, 0.0, 1.0)) * min(spiralSheen, 1.25) * 0.22 * Meta.z;

    // 裂隙内部：深邃漆黑虚无视界，透出极暗紫虚空流云与针尖星芒
    if (maxAbyss > 0.001) {
        vec2 abyssUv = uv * OutSize * 0.28;
        float nebula = fxNoise3(vec3(uv * 16.0, FxTime * 0.45));
        float star = pow(max(fxHash12(floor(abyssUv + vec2(0.0, FxTime * 1.8))), 1.0e-4), 20.0);
        vec3 voidCore = vec3(0.008, 0.003, 0.020)
            + voidPurple * smoothstep(0.48, 0.92, nebula) * 0.12
            + hotOrchid * star * 0.65;
        color = mix(color, voidCore, clamp(maxAbyss * 0.97, 0.0, 0.98));
    }

    // 裂界同源品红/兰粉内锋 + 克制的冰青棱镜点缀
    float rim = clamp(rimGlow, 0.0, 1.9);
    float edgeMask = 1.0 - maxAbyss * 0.75;
    float edgeBoost = Meta.z;
    color += mix(kamuiMagenta, hotOrchid, clamp(rim * 0.48, 0.0, 1.0)) * rim * 0.52 * edgeMask * edgeBoost;
    color += prismCyan * min(cyanRim, 1.4) * 0.28 * edgeMask * edgeBoost;

    fragColor = vec4(clamp(color, vec3(0.0), vec3(1.0)), 1.0);
}
