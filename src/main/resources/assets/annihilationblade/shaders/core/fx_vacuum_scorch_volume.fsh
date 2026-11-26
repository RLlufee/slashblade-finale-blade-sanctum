#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 真空衰变 · 非破坏地形焦痕体积/HDR 泛光通道（加法累积，进入 Bloom 金字塔）：
//  · 爆心 3D Voronoi 焦土龟裂炽热熔纹：沿物理黑体辐射曲线（白热 → 炽橙 → 暗红余炭）逐秒冷却；
//  · 交织无尽星空专属「星渊残脉冰青余辉」，体现黑洞热寂坍缩而非普通火球；
//  · 焦区中圈星散呼吸火星余炭点 + 爆心低空薄热雾微辉，随 8 秒平滑冷却熄灭。
uniform sampler2D Sampler1; // 场景原始深度
uniform mat4 InvProj;
uniform mat4 InvView;
uniform vec4 ScorchCenter; // xyz = 爆心地表中心（相对相机），w = 当前已烤焦覆盖半径
uniform vec4 ScorchState;  // x = 焦黑总可见度(0..1), y = 余热温度(0..1), z = 8秒末段消融进度(0..1), w = 空气热浪强度(0..1)
uniform vec4 ScorchExtra;  // x = 最大焦痕半径, y = 种子, z = 核心深坑基准半径, w = 爆炸后秒数
uniform float FxTime;

in vec2 texCoord;

out vec4 fragColor;

vec3 scorchBlackbody(float x) {
    vec3 c = mix(vec3(0.32, 0.015, 0.004), vec3(1.0, 0.24, 0.02), smoothstep(0.0, 0.42, x));
    c = mix(c, vec3(1.0, 0.68, 0.22), smoothstep(0.40, 0.82, x));
    return mix(c, vec3(1.0, 0.95, 0.86), smoothstep(0.82, 1.20, x));
}

void main() {
    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);
    bool sky = depth >= 0.9999;

    float activeRadius = ScorchCenter.w;
    float charVis = clamp(ScorchState.x, 0.0, 1.0);
    float heat = clamp(ScorchState.y, 0.0, 1.0);
    float dissolve = clamp(ScorchState.z, 0.0, 1.0);
    float maxR = max(ScorchExtra.x, 4.0);
    float seed = ScorchExtra.y;
    float coreR = max(ScorchExtra.z, 2.0);
    vec3 acc = vec3(0.0);

    // 1. 真实地表/山体/建筑上的 3D 龟裂炽热熔纹与星渊残脉（O(1) 像素级贴合，零穿模）
    if (!sky && sceneT > 0.35 && heat > 0.003 && activeRadius > 0.3) {
        vec3 surface = rd * min(sceneT, 4096.0);
        vec3 rel = surface - ScorchCenter.xyz;
        float dist3d = length(vec3(rel.x, rel.y * 1.38, rel.z));
        if (dist3d < activeRadius * 1.15) {
            float ang = fxSafeAtan(rel.z, rel.x);
            vec2 radialDir = vec2(cos(ang), sin(ang));
            float blastRays = fxFbm3(vec3(radialDir * 3.4 + vec2(seed * 0.17), rel.y * 0.06 + seed * 0.31));
            float edgeNoise = fxFbm3(rel * 0.095 + vec3(seed * 1.7));
            float jaggedR = activeRadius * (0.86 + 0.26 * blastRays + 0.12 * edgeNoise);
            float normDist = dist3d / max(jaggedR, 0.5);

            float dissolveNoise = edgeNoise * 0.58 + blastRays * 0.42;
            float fadeEdge = dissolve * 1.18 + normDist * dissolve * 0.42;
            float aliveMask = dissolve > 0.001
                ? smoothstep(fadeEdge - 0.24, fadeEdge + 0.18, dissolveNoise + (1.0 - normDist) * 0.38)
                : 1.0;

            if (aliveMask > 0.002 && normDist < 1.04) {
                // 3D Voronoi 地壳龟裂网（与场景通道完全同坐标对齐，焦黑裂缝中透出炙热红炭与幽青核脉！）
                vec3 cellId;
                vec3 vor = fxVoronoi3Fast(rel * 0.36 + vec3(seed * 0.5), cellId);
                float hCell = fxHash13(cellId);
                float breathe = 0.68 + 0.32 * sin(FxTime * (2.6 + hCell * 3.4) + hCell * 27.0);

                // 主裂纹细线 + 较宽余热暗晕
                float sharpCrack = exp(-vor.y * 22.0);
                float wideCrack = exp(-vor.y * 6.5);

                // 径向温度分布：爆心熔融白热/橙红，中圈暗红炭脉，外缘仅在冲击波刚烤过的前沿闪现微红余烬
                float radialHeat = exp(-dist3d / max(maxR * 0.34, 4.5));
                float midHeat = exp(-dist3d / max(maxR * 0.68, 8.0)) * 0.42;
                float temp = clamp((radialHeat + midHeat) * heat * (0.55 + 0.65 * hCell), 0.0, 1.25);

                // 物理黑体余烬光（随 heat 下降自动由白金 → 橙红 → 暗绛红冷却）
                vec3 emberLight = scorchBlackbody(temp * 0.92) * (sharpCrack * 2.15 + wideCrack * 0.42) * temp * breathe;

                // 无尽星空专属标识：最靠近奇点爆心（0..2.2*coreR）的裂缝深处残留切伦科夫幽青辐射脉动
                float stellarCore = exp(-(dist3d * dist3d) / max(coreR * coreR * 6.5, 16.0));
                float cyanPulse = 0.5 + 0.5 * sin(FxTime * 4.2 - dist3d * 0.8 + hCell * 15.0);
                vec3 stellarVein = vec3(0.14, 0.94, 1.0) * sharpCrack * stellarCore * heat * (0.55 + 0.85 * cyanPulse) * 1.65;

                // 焦炭区星散明暗呼吸的微小火星点（模拟炭化木炭/岩石上尚未熄灭的红点）
                vec3 sparkCell = floor(rel * 1.45);
                vec3 sparkF = fract(rel * 1.45) - 0.5;
                float sh = fxHash13(sparkCell + vec3(seed));
                float sparkDot = step(0.91, sh) * exp(-dot(sparkF, sparkF) * 42.0);
                float sparkTwinkle = 0.45 + 0.55 * sin(FxTime * (5.0 + sh * 8.0) + sh * 50.0);
                vec3 coalSparks = scorchBlackbody(0.35 + 0.55 * heat) * sparkDot * sparkTwinkle * (1.0 - smoothstep(0.25, 0.95, normDist)) * heat * 1.8;

                // 烤焦边缘刚被扫过时的余焰焦唇（随时间快速冷却消失）
                float rimLip = exp(-abs(normDist - 0.94) * 18.0) * fxSafePow(heat, 1.8) * 0.65;
                vec3 rimEmber = scorchBlackbody(0.25 + 0.5 * heat) * rimLip;

                acc += (emberLight + stellarVein + coalSparks + rimEmber) * aliveMask * charVis;
            }
        }
    }

    // 2. 爆心低空淡薄炙热残光晕（给焦坑正中垫一层极柔和的余温红青雾辉，绝不遮挡地形）
    if (heat > 0.01 && charVis > 0.01) {
        vec3 glowOrigin = ScorchCenter.xyz + vec3(0.0, 1.2, 0.0);
        float tc = dot(glowOrigin, rd);
        if (tc > 0.0 && tc - coreR * 3.0 < sceneT) {
            float dRay = length(glowOrigin - rd * tc);
            float q = dRay / max(coreR * 2.6, 4.0);
            float softGlow = exp(-(q * q)) * heat * heat * charVis * 0.42;
            vec3 mistCol = mix(scorchBlackbody(0.28 + 0.45 * heat), vec3(0.18, 0.72, 1.0), 0.28);
            acc += mistCol * softGlow;
        }
    }

    fragColor = vec4(max(fxSanitize(acc), vec3(0.0)), 1.0);
}
