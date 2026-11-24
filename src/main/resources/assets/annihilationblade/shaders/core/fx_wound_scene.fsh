#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 裂界 · 万花筒神威场景通道（单 Pass 最多打包 8 个奇点）：
//  · 非线性多圈对数螺旋扭转 + 强力向心抽吸（连带目标活体像素与周围场景一起拧成螺旋）；
//  · 严格接入 Sampler1 场景深度：前景墙体遮挡保护 + fxSafeRefractUv 防近景误吸；
//  · 螺旋切向 RGB 色散 + 漆黑视界奇点穿孔 + 品红/虚空紫撕裂内沿。
uniform sampler2D Sampler0; // 场景颜色
uniform sampler2D Sampler1; // 场景原始深度（R32F）
uniform mat4 InvProj;
uniform vec2 OutSize;
uniform float FxTime;
// 每个 mat4 打包 2 个神威奇点：
//   列 0 / 列 2 = (uv.x, uv.y, screenRadius, viewDist)
//   列 1 / 列 3 = (twistRadians, suckStrength, holeRadiusNorm, intensity)
uniform mat4 VortexA;
uniform mat4 VortexB;
uniform mat4 VortexC;
uniform mat4 VortexD;
// x = 活跃奇点数(0..8), y = 切向色散倍率, z = 空间回弹脉动, w = 螺旋细纹强度
uniform vec4 Meta;

in vec2 texCoord;
out vec4 fragColor;

vec2 rotate2(vec2 v, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

void applyVortex(
    vec4 pos, vec4 prm, vec2 p, float aspect, float sceneT,
    inout vec2 totalOffset, inout vec2 tangentShift, inout float minRefractT,
    inout float maxHole, inout float rimGlow, inout float spiralSheen, inout float darkMask
) {
    float radius = pos.z;
    float intensity = prm.w;
    if (radius <= 1.0e-4 || intensity <= 1.0e-4) {
        return;
    }

    vec2 centerA = vec2(pos.x * aspect, pos.y);
    vec2 rel = p - centerA;
    float dist = length(rel);
    float r = dist / radius;
    if (r >= 1.0) {
        return;
    }

    // 深度遮挡保护：前景方块明显挡在奇点前面时抑制扭曲，避免隔墙穿帮
    float viewDist = pos.w;
    float occMask = smoothstep(viewDist - 1.45, viewDist - 0.15, sceneT);
    if (occMask <= 1.0e-4) {
        return;
    }

    float fall = 1.0 - r;
    float smoothFall = fall * fall * (3.0 - 2.0 * fall);
    float coreCurve = pow(fall, 2.35);

    // 极坐标对数螺旋扭角 + 空气剪切细纹
    float theta = atan(rel.y, rel.x);
    float logR = log(max(r, 0.018));
    float airShear = sin(theta * 3.0 + logR * 7.2 - FxTime * 15.0 * sign(prm.x + 1.0e-4));
    float rippleMod = 1.0 + 0.065 * airShear * smoothFall * Meta.w;
    float angle = prm.x * coreCurve * rippleMod * occMask;
    vec2 twisted = rotate2(rel, angle);

    // 径向向心抽吸：采样坐标外移 (>1) 使画面与活体向涡心收缩拉细；回弹期 (<1) 产生弹性复原
    float pull = clamp(prm.y * pow(fall, 1.55) * occMask, -0.26, 0.92);
    float radialScale = 1.0 + pull * (0.70 + 1.45 * smoothFall);
    vec2 warpedRel = twisted * radialScale;

    vec2 dispAspect = warpedRel - rel;
    vec2 dispUv = vec2(dispAspect.x / aspect, dispAspect.y);
    // 截断单点极端位移避免大跨度边缘拖影
    float dispLen = length(dispUv);
    if (dispLen > 0.34) {
        dispUv *= 0.34 / dispLen;
    }
    totalOffset += dispUv;

    // 切向色散方向（沿螺旋切线拆开品红/冰青边缘）
    vec2 safeDir = dist > 1.0e-4 ? (rel / dist) : vec2(1.0, 0.0);
    vec2 tang = vec2(-safeDir.y, safeDir.x) * sign(prm.x + 1.0e-4);
    float caWeight = smoothFall * (0.35 + 0.65 * coreCurve) * intensity * occMask;
    tangentShift += vec2(tang.x / aspect, tang.y) * caWeight * 0.013 + dispUv * 0.075;

    minRefractT = min(minRefractT, max(viewDist - 1.6, 0.0));

    // 漆黑奇点视界穿孔 + 高反差品红/虚空紫扭曲边缘
    float holeR = max(prm.z, 0.0);
    if (holeR > 0.002) {
        float hole = 1.0 - smoothstep(holeR * 0.68, holeR * 1.12, r);
        maxHole = max(maxHole, hole * occMask * clamp(intensity, 0.0, 1.0));
        float rimBand = exp(-abs(r - holeR * 1.08) / max(holeR * 0.28, 0.022));
        rimGlow += rimBand * intensity * occMask * (0.75 + 0.25 * airShear);
    }

    // 外圈空间螺旋剪切高光（让背景本身浮现淡淡的紫红扭曲涡纹）
    float band = pow(max(0.5 + 0.5 * airShear, 0.0), 4.0) * smoothstep(0.06, 0.32, r) * smoothFall;
    spiralSheen += band * intensity * occMask * Meta.w;
    darkMask = max(darkMask, pow(fall, 2.2) * 0.42 * intensity * occMask);
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
    vec2 tangentShift = vec2(0.0);
    float minRefractT = 1.0e6;
    float maxHole = 0.0;
    float rimGlow = 0.0;
    float spiralSheen = 0.0;
    float darkMask = 0.0;

    if (count > 0) applyVortex(VortexA[0], VortexA[1], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);
    if (count > 1) applyVortex(VortexA[2], VortexA[3], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);
    if (count > 2) applyVortex(VortexB[0], VortexB[1], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);
    if (count > 3) applyVortex(VortexB[2], VortexB[3], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);
    if (count > 4) applyVortex(VortexC[0], VortexC[1], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);
    if (count > 5) applyVortex(VortexC[2], VortexC[3], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);
    if (count > 6) applyVortex(VortexD[0], VortexD[1], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);
    if (count > 7) applyVortex(VortexD[2], VortexD[3], p, aspect, sceneT, totalOffset, tangentShift, minRefractT, maxHole, rimGlow, spiralSheen, darkMask);

    if (dot(totalOffset, totalOffset) < 1.0e-8 && maxHole < 1.0e-4 && rimGlow < 1.0e-4) {
        fragColor = vec4(texture(Sampler0, uv).rgb, 1.0);
        return;
    }

    float totalLen = length(totalOffset);
    if (totalLen > 0.42) {
        totalOffset *= 0.42 / totalLen;
    }

    float ca = clamp(Meta.y, 0.0, 2.0);
    vec2 uvG = fxSafeRefractUv(uv, totalOffset, minRefractT, Sampler1, InvProj);
    vec2 uvR = fxSafeRefractUv(uv, totalOffset + tangentShift * ca, minRefractT, Sampler1, InvProj);
    vec2 uvB = fxSafeRefractUv(uv, totalOffset - tangentShift * ca, minRefractT, Sampler1, InvProj);

    vec3 color = vec3(
        texture(Sampler0, uvR).r,
        texture(Sampler0, uvG).g,
        texture(Sampler0, uvB).b
    );

    // 涡心深渊压暗与品红/虚空紫空间拉丝染边
    color *= 1.0 - clamp(darkMask, 0.0, 0.55);
    vec3 voidPurple = vec3(0.42, 0.12, 0.88);
    vec3 kamuiMagenta = vec3(0.95, 0.24, 0.96);
    vec3 hotOrchid = vec3(1.0, 0.78, 0.99);
    color += mix(voidPurple, kamuiMagenta, clamp(spiralSheen * 0.8, 0.0, 1.0)) * min(spiralSheen, 1.2) * 0.22;

    // 奇点漆黑穿孔（高反差黑洞喉口）
    vec3 abyss = vec3(0.012, 0.006, 0.024);
    color = mix(color, abyss, clamp(maxHole, 0.0, 0.98));

    // 奇点刃口品红冷芒与回弹闪纹
    float rim = clamp(rimGlow, 0.0, 1.8);
    color += mix(kamuiMagenta, hotOrchid, clamp(rim * 0.55 + Meta.z * 0.45, 0.0, 1.0)) * rim * (0.48 + 0.35 * Meta.z);

    fragColor = vec4(clamp(color, vec3(0.0), vec3(1.0)), 1.0);
}
