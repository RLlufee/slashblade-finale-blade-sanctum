#version 150

#moj_import <annihilationblade:fx_common.glsl>
#moj_import <annihilationblade:fx_space.glsl>

// 真空衰变 · 体积通道（HDR，加法累积）：
//  · 真空泡壁：薄壳按掠射角增亮（limb brightening），带流动的虹彩色；
//  · 结界内的稀薄虚空雾，被奇点照亮；
//  · 地表网格的柔和辉光（与场景通道的锐利网格叠加形成泛光）；
//  · 奇点光子环与外晕、终结闪光与冲击波光边。
uniform sampler2D Sampler1; // 场景原始深度
uniform mat4 InvProj;
uniform mat4 InvView;
uniform vec4 Zone;
uniform vec4 ZoneExtra;
uniform vec4 Bubble;
uniform vec4 Core;
uniform vec4 Shock;
uniform vec4 Collapse;  // x = 坠落强度, y = 过载/内爆坍缩强度, z = 死寂呼吸拍, w = 大爆炸进度
uniform float FxTime;
uniform float Steps;

in vec2 texCoord;

out vec4 fragColor;

float zoneMask(vec3 local, float bubbleRadius) {
    float halfSize = Zone.w;
    float ex = 1.0 - smoothstep(halfSize - 2.0, halfSize, abs(local.x));
    float ez = 1.0 - smoothstep(halfSize - 2.0, halfSize, abs(local.z));
    float ey = smoothstep(-ZoneExtra.y - 2.0, -ZoneExtra.y, local.y) * (1.0 - smoothstep(ZoneExtra.x - 2.0, ZoneExtra.x, local.y));
    float eb = 1.0 - smoothstep(bubbleRadius - 4.0, bubbleRadius, length(local));
    return ex * ez * ey * eb;
}

vec3 iridescence(float t) {
    return 0.5 + 0.5 * cos(FX_TAU * (vec3(0.0, 0.33, 0.67) + t));
}

void main() {
    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);
    float strength = ZoneExtra.z * (1.0 - Bubble.w);
    vec3 zc = Zone.xyz;
    vec3 acc = vec3(0.0);
    // 导数必须在一致控制流中求取，因此在任何分支之前计算表面点与法线
    vec3 surface = rd * min(sceneT, 4096.0);
    vec3 surfaceNormal = normalize(cross(dFdx(surface), dFdy(surface)) + vec3(0.0, 1.0e-6, 0.0));
    float glowMajor = fxSurfaceGrid(surface - Zone.xyz, surfaceNormal, 16.0, 0.06);
    float glowMinor = fxSurfaceGrid(surface - Zone.xyz, surfaceNormal, 4.0, 0.08);

    // 1. 真空泡壁
    if (Bubble.y > 0.0) {
        vec2 hit = fxSphere(rd, zc, Bubble.x);
        for (int i = 0; i < 2; i++) {
            float t = i == 0 ? hit.x : hit.y;
            if (t > 0.0 && t < sceneT) {
                vec3 local = rd * t - zc;
                vec3 n = local / max(Bubble.x, 1.0e-3);
                float limb = min(Bubble.z / max(abs(dot(n, rd)), 0.06), 14.0);
                float band = fxSafeAtan(local.z, local.x) * 0.48 + local.y * 0.02 + FxTime * 0.6;
                vec3 tint = mix(vec3(0.4, 0.85, 1.0), iridescence(band), 0.55);
                float boxFade = 1.0 - smoothstep(Zone.w * 1.05, Zone.w * 1.4, max(abs(local.x), abs(local.z)));
                acc += tint * limb * Bubble.y * 0.3 * (0.4 + 0.6 * boxFade);
            }
        }
    }

    // 2. 结界内的虚空雾
    if (strength > 0.0) {
        vec3 boxMin = zc - vec3(Zone.w, ZoneExtra.y, Zone.w);
        vec3 boxMax = zc + vec3(Zone.w, ZoneExtra.x, Zone.w);
        vec2 box = fxBox(rd, boxMin, boxMax);
        float t0 = max(box.x, 0.0);
        float t1 = min(box.y, min(sceneT, 400.0));
        if (t0 < t1) {
            int steps = int(clamp(Steps, 4.0, 48.0));
            float dt = (t1 - t0) / float(steps);
            float jitter = fxIGN(gl_FragCoord.xy, FxTime);
            vec3 fog = vec3(0.0);
            for (int i = 0; i < 48; i++) {
                if (i >= steps) {
                    break;
                }
                float t = t0 + (float(i) + jitter) * dt;
                vec3 p = rd * t;
                vec3 local = p - zc;
                float m = zoneMask(local, Bubble.x);
                if (m <= 0.001) {
                    continue;
                }
                float n = fxNoise3(local * 0.045 + vec3(0.0, -FxTime * 0.4, FxTime * 0.15)) * 0.7 + fxNoise3(local * 0.11 + FxTime * 0.3) * 0.3;
                float density = smoothstep(0.45, 0.85, n);
                float dc = length(p - Core.xyz);
                float light = 1.0 / (1.0 + dc * dc * 0.004);
                fog += mix(vec3(0.3, 0.15, 0.6), vec3(0.4, 0.85, 1.0), light) * density * (0.25 + light * 1.5) * m;
            }
            acc += fog * dt * 0.02 * strength;
        }

        // 3. 地表网格辉光
        if (depth < 0.9999) {
            vec3 local = surface - zc;
            float field = zoneMask(local, Bubble.x) * strength;
            if (field > 0.0) {
                float wave = fract(length(local.xz) / 22.0 - FxTime * 0.45);
                float pulse = fxSafePow(wave, 10.0);
                acc += vec3(0.3, 0.75, 1.0) * (glowMajor * 0.35 + glowMinor * pulse * 0.6) * field;
            }
        }
    }

    // 4. 奇点：动态演化的双层光子环、内爆白热光晕、死寂针尖与爆心体积闪光
    vec3 core = Core.xyz;
    float tc = dot(core, rd);
    float dCore = length(core - rd * tc);
    float plunge = clamp(Collapse.x, 0.0, 1.0);
    float implosion = clamp(Collapse.y, 0.0, 1.0);
    float silence = clamp(Collapse.z, 0.0, 1.0);
    if (Core.w > 0.0 && tc > 0.0 && tc - Core.w < sceneT) {
        float hotShift = clamp(plunge * 0.45 + implosion * 0.95 + silence, 0.0, 1.0);
        // 紧贴视界的内光子环：常态为电紫+冰青，坠落与内爆期急剧炽化为切伦科夫白热与黑体金
        float innerWidth = Core.w * mix(0.18, 0.11, implosion);
        float innerRing = exp(-abs(dCore - Core.w * mix(1.32, 1.18, implosion)) / max(innerWidth, 0.04));
        vec3 innerColor = mix(vec3(0.76, 0.48, 1.0), vec3(0.95, 0.98, 1.0), hotShift * 0.82);
        innerColor = mix(innerColor, vec3(0.18, 0.96, 1.0), plunge * 0.35 * (1.0 - silence));
        acc += innerColor * innerRing * (2.5 + implosion * 2.2 + plunge * 0.7);

        // 第二重临界引力散射光圈：坠落与内爆坍缩时由黑体金与冷青交织收束
        float outerRadius = Core.w * mix(1.85, 1.48, implosion);
        float outerRing = exp(-abs(dCore - outerRadius) / max(Core.w * 0.24, 0.06));
        vec3 outerColor = mix(vec3(0.24, 0.85, 1.0), vec3(1.0, 0.76, 0.36), implosion * 0.65 + plunge * 0.25);
        acc += outerColor * outerRing * (0.55 + plunge * 0.85 + implosion * 1.45) * (1.0 - silence * 0.75);

        // 外围柔和深空引力辉晕（死寂呼吸拍收窄以突出针尖反差）
        float haloDecay = max(Core.w * mix(1.8, 0.95, silence), 0.12);
        acc += mix(vec3(0.42, 0.32, 1.0), vec3(0.25, 0.92, 1.0), hotShift * 0.65)
            * exp(-max(dCore - Core.w, 0.0) / haloDecay) * step(Core.w * 0.95, dCore) * (0.72 + implosion * 0.55) * (1.0 - silence * 0.45);

        // 死寂呼吸拍：中心针尖纯白核 + 紧绷临界光针
        if (silence > 0.01) {
            float pinR = max(Core.w * 1.35, 0.22);
            float qPin = dCore / pinR;
            acc += vec3(0.96, 0.99, 1.0) * exp(-(qPin * qPin)) * silence * 4.2;
        }
    }

    // 爆心超新星白热体积闪核（随 Shock.z 缩放）
    float flashRadius = max(Shock.z, 0.5) * 9.5;
    if (Shock.w > 0.0 && tc > 0.0 && tc - flashRadius < sceneT) {
        float qFlash = dCore / flashRadius;
        float qCore = dCore / max(flashRadius * 0.32, 0.3);
        acc += (vec3(0.94, 0.98, 1.0) * exp(-(qCore * qCore)) * 4.8 + vec3(0.26, 0.92, 1.0) * exp(-(qFlash * qFlash)) * 2.6) * Shock.w;
    }

    // 5. 双波前超新星真空冲击球（主外虹彩相变壳 + 次内白热等离子壳 + 球壳间薄电离辉光）
    if (Shock.y > 0.0 && tc > 0.0) {
        float thick = max(Shock.z, 0.15);
        // 主外波前：切伦科夫冰青 + 流动虹彩真空相变壳
        float frontOuter = tc - sqrt(max(Shock.x * Shock.x - dCore * dCore, 0.0));
        if (frontOuter < sceneT) {
            float qOuter = (dCore - Shock.x) / thick;
            float outerBand = exp(-(qOuter * qOuter));
            vec3 hitLocal = rd * max(frontOuter, 0.0) - core;
            float iridPhase = fxSafeAtan(hitLocal.z, hitLocal.x) * 0.35 + hitLocal.y * 0.04 - FxTime * 0.85;
            vec3 outerTint = mix(vec3(0.28, 0.94, 1.0), iridescence(iridPhase), 0.42);
            acc += outerTint * outerBand * Shock.y * 2.4;
        }
        // 次级内波前：半径 0.66×，炽烈恒星白热 + 伽马电紫火球冲击层
        float innerR = Shock.x * 0.66;
        float frontInner = tc - sqrt(max(innerR * innerR - dCore * dCore, 0.0));
        if (frontInner < sceneT) {
            float qInner = (dCore - innerR) / max(thick * 0.72, 0.12);
            float innerBand = exp(-(qInner * qInner));
            vec3 innerTint = mix(vec3(0.62, 0.46, 1.0), vec3(0.96, 0.99, 1.0), innerBand * 0.75);
            acc += innerTint * innerBand * Shock.y * 2.1;
            // 球壳内稀薄高能电离膜（沿掠射边缘微亮，赋予爆炸球厚重立体体积感）
            if (dCore < Shock.x && innerR > 0.5) {
                float radial01 = clamp(dCore / max(Shock.x, 0.01), 0.0, 1.0);
                float shellFill = smoothstep(0.45, 0.98, radial01) * (1.0 - smoothstep(0.98, 1.0, radial01));
                acc += vec3(0.24, 0.55, 1.0) * shellFill * Shock.y * 0.48;
            }
        }
    }

    fragColor = vec4(acc, 1.0);
}
