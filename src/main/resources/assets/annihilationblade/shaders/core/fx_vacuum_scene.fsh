#version 150

#moj_import <annihilationblade:fx_common.glsl>
#moj_import <annihilationblade:fx_space.glsl>

// 真空衰变 · 场景通道：
//  · 真空泡壁（开场）沿轮廓折射；
//  · 结界内「物理法则改写」：场景冷色去饱和 + 地表刻蚀网格与向外扩散的脉冲环；
//  · 身处结界内时，天空被替换为虚空星海；
//  · 奇点黑洞的引力透镜与事件视界、终结冲击波折射。
uniform sampler2D Sampler0; // 场景颜色
uniform sampler2D Sampler1; // 场景原始深度
uniform mat4 InvProj;
uniform mat4 InvView;
uniform mat4 ViewProj;
uniform vec4 Zone;      // xyz = 结界中心（相对相机），w = 水平半边长
uniform vec4 ZoneExtra; // x = 中心以上高度, y = 中心以下深度, z = 结界强度, w = 种子
uniform vec4 Bubble;    // x = 真空泡半径, y = 泡壁强度, z = 泡壁厚度, w = 崩解进度
uniform vec4 Core;      // xyz = 奇点中心（相对相机，随坠落动态下移），w = 黑洞半径
uniform vec4 Shock;     // x = 冲击波半径, y = 强度, z = 厚度, w = 奇点闪光
uniform vec4 Collapse;  // x = 坠落强度, y = 过载/内爆坍缩强度, z = 死寂呼吸拍, w = 大爆炸进度
uniform float FxTime;

in vec2 texCoord;

out vec4 fragColor;

vec2 projectUv(vec3 p, out float w) {
    vec4 clip = ViewProj * vec4(p, 1.0);
    w = clip.w;
    return clip.xy / max(clip.w, 1.0e-4) * 0.5 + 0.5;
}

// 结界体积遮罩（软边），local 为相对结界中心的坐标。
float zoneMask(vec3 local, float bubbleRadius) {
    float halfSize = Zone.w;
    float ex = 1.0 - smoothstep(halfSize - 2.0, halfSize, abs(local.x));
    float ez = 1.0 - smoothstep(halfSize - 2.0, halfSize, abs(local.z));
    float ey = smoothstep(-ZoneExtra.y - 2.0, -ZoneExtra.y, local.y) * (1.0 - smoothstep(ZoneExtra.x - 2.0, ZoneExtra.x, local.y));
    float eb = 1.0 - smoothstep(bubbleRadius - 4.0, bubbleRadius, length(local));
    return ex * ez * ey * eb;
}

void main() {
    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);
    bool sky = depth >= 0.9999;
    float strength = ZoneExtra.z * (1.0 - Bubble.w);
    // 导数必须在一致控制流中求取，因此在任何分支之前计算表面点与法线
    vec3 surface = rd * min(sceneT, 4096.0);
    vec3 surfaceNormal = normalize(cross(dFdx(surface), dFdy(surface)) + vec3(0.0, 1.0e-6, 0.0));
    float gridMinor = fxSurfaceGrid(surface - Zone.xyz, surfaceNormal, 4.0, 0.035);
    float gridMajor = fxSurfaceGrid(surface - Zone.xyz, surfaceNormal, 16.0, 0.02);
    vec2 offset = vec2(0.0);
    float refractT = sceneT;
    bool anyRefract = false;

    // 真空泡壁：轮廓处的径向折射（仅在泡壁仍活跃时参与前景深度保护，防止进入结界后把 refractT 归零引发近景鬼影）
    vec3 zc = Zone.xyz;
    float tcZone = dot(zc, rd);
    float zw;
    vec2 zuv = projectUv(zc, zw);
    if (Bubble.y > 0.0 && zw > 0.0 && tcZone > 0.0) {
        float dRay = length(zc - rd * tcZone);
        float qBubble = (dRay - Bubble.x) / max(Bubble.z, 0.5);
        float ring = exp(-(qBubble * qBubble));
        float frontT = tcZone - sqrt(max(Bubble.x * Bubble.x - dRay * dRay, 0.0));
        vec2 dir = uv - zuv;
        float len = length(dir);
        if (frontT > 0.0 && frontT < sceneT && len > 1.0e-4 && ring > 0.005) {
            offset += dir / len * ring * Bubble.y * 0.03;
            refractT = min(refractT, frontT);
            anyRefract = true;
        }
    }

    // 奇点：坠落与内爆坍缩期强化的真实黑洞引力透镜 + 终结双波前冲击波折射
    vec3 core = Core.xyz;
    float tc = dot(core, rd);
    float dCore = length(core - rd * tc);
    float cw;
    vec2 cuv = projectUv(core, cw);
    bool coreAhead = cw > 0.0 && tc > 0.0;
    if (Core.w > 0.0 && coreAhead && tc - Core.w < sceneT) {
        // 坠落与坍缩内爆时增大透镜弯折倍率，令周遭地形/云层被强力吸扭向视界中心
        float lensBoost = 1.0 + Collapse.x * 0.35 + Collapse.y * 0.72 + Collapse.z * 0.25;
        vec2 lensOff = fxLensOffset(uv, cuv, dCore, Core.w) * lensBoost;
        if (dot(lensOff, lensOff) > 1.0e-10) {
            offset += lensOff;
            refractT = min(refractT, max(tc - Core.w, 0.0));
            anyRefract = true;
        }
    }
    if (Shock.y > 0.0 && coreAhead) {
        vec2 dir = uv - cuv;
        float len = length(dir);
        if (len > 1.0e-4) {
            vec2 radial = dir / len;
            float thick = max(Shock.z, 0.1);
            // 主外真空相变波前：强烈向外径向折射
            float qOuter = (dCore - Shock.x) / thick;
            float ringOuter = exp(-(qOuter * qOuter));
            float frontOuter = tc - sqrt(max(Shock.x * Shock.x - dCore * dCore, 0.0));
            if (frontOuter > 0.0 && frontOuter < sceneT && ringOuter > 0.005) {
                offset += radial * ringOuter * Shock.y * 0.052;
                refractT = min(refractT, frontOuter);
                anyRefract = true;
            }
            // 次级内热核冲击波前：紧随其后产生反向压缩畸变，强化厚重气浪质感
            float innerR = Shock.x * 0.66;
            float qInner = (dCore - innerR) / max(thick * 0.72, 0.1);
            float ringInner = exp(-(qInner * qInner));
            float frontInner = tc - sqrt(max(innerR * innerR - dCore * dCore, 0.0));
            if (frontInner > 0.0 && frontInner < sceneT && ringInner > 0.005) {
                offset -= radial * ringInner * Shock.y * 0.028;
                refractT = min(refractT, frontInner);
                anyRefract = true;
            }
        }
    }

    vec2 safeUv = anyRefract ? fxSafeRefractUv(uv, offset, refractT, Sampler1, InvProj) : uv;
    vec3 color = texture(Sampler0, safeUv).rgb;

    if (strength > 0.0) {
        if (!sky) {
            vec3 local = surface - zc;
            float field = zoneMask(local, Bubble.x) * strength;
            if (field > 0.0) {
                float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                vec3 cold = vec3(luma) * vec3(0.5, 0.68, 1.0);
                cold = cold * cold * 1.35 + cold * 0.15;
                color = mix(color, cold, 0.75 * field);
                float wave = fract(length(local.xz) / 22.0 - FxTime * 0.45);
                float pulse = fxSafePow(wave, 10.0);
                color += vec3(0.35, 0.8, 1.0) * (gridMinor * (0.12 + 0.88 * pulse) * 0.45 + gridMajor * 0.5) * field;
            }
        } else {
            // 身处结界中：天空坍缩为虚空
            float inside = zoneMask(-zc, Bubble.x) * strength;
            if (inside > 0.0) {
                vec3 space = fxVoidSky(rd, vec3(ZoneExtra.w), FxTime) * 1.25 + color * 0.15;
                color = mix(color, space, 0.85 * inside);
            }
        }
    }

    if (Core.w > 0.0 && coreAhead) {
        color = fxEventHorizon(color, rd, core, Core.w, dCore, tc, sceneT);
    }

    fragColor = vec4(color, 1.0);
}
