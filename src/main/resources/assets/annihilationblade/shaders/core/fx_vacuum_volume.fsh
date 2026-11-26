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

    // 爆心紧凑锐利奇点核闪（收敛半径，绝不大面积糊白洗屏）
    float flashRadius = max(Shock.z * 2.1, 2.8);
    if (Shock.w > 0.0 && tc > 0.0 && tc - flashRadius < sceneT) {
        float qCore = dCore / max(flashRadius * 0.36, 0.35);
        float qRim = dCore / flashRadius;
        acc += (vec3(0.95, 0.99, 1.0) * exp(-(qCore * qCore)) * 2.4 + vec3(0.18, 0.92, 1.0) * exp(-(qRim * qRim)) * 1.15) * Shock.w;
    }

    // 5. 无尽星空专属：像素级解析「真真空相变刀锋前沿 + 星云暗纹界膜 + 引力波细环 + 地表实景切割辉光」
    if (Shock.y > 0.0) {
        bool isDetonation = Collapse.w > 0.001;
        if (isDetonation) {
            // 5a. 100% 像素级平滑的解析相变穹顶前沿（不限 tc > 0，玩家身处球内 360° 转头亦零截断）
            vec2 domeHit = fxSphere(rd, core, Shock.x);
            for (int i = 0; i < 2; i++) {
                float tHit = i == 0 ? domeHit.x : domeHit.y;
                if (tHit > 0.0 && tHit < sceneT) {
                    vec3 hitPos = rd * tHit - core;
                    vec3 n = hitPos / max(Shock.x, 1.0e-3);
                    float ndv = clamp(abs(dot(n, rd)), 0.0, 1.0);
                    // 极锐利的刀锋菲涅尔边 + 球面星云丝状干涉细纹（仅在外侧边微亮，内部清澈见底）
                    float razorRim = fxSafePow(1.0 - ndv, 5.5);
                    float softLimb = fxSafePow(1.0 - ndv, 2.8);
                    float filament = smoothstep(0.46, 0.88, fxFbm3(n * 4.8 + vec3(FxTime * 0.22, -FxTime * 0.35, ZoneExtra.w * 0.13)));
                    float iridPhase = fxSafeAtan(n.z, n.x) * 0.42 + n.y * 0.85 - FxTime * 0.75;
                    vec3 edgeCol = mix(vec3(0.14, 0.96, 1.0), iridescence(iridPhase), 0.32);
                    vec3 rimLight = edgeCol * (razorRim * 1.75 + softLimb * filament * 0.55)
                        + vec3(0.95, 0.99, 1.0) * fxSafePow(1.0 - ndv, 12.0) * 1.35;
                    acc += rimLight * Shock.y * (i == 0 ? 1.0 : 0.42);
                }
            }

            // 5b. 次级黑体金→电紫引力波星环细纹（位于 0.74× 半径，细锐冷暖撞色）
            if (tc > 0.0) {
                float innerR = Shock.x * 0.74;
                float frontInner = tc - sqrt(max(innerR * innerR - dCore * dCore, 0.0));
                if (frontInner < sceneT) {
                    float qInner = (dCore - innerR) / max(Shock.z * 0.24, 0.14);
                    float fineWave = exp(-(qInner * qInner));
                    vec3 goldViolet = mix(vec3(0.55, 0.38, 1.0), vec3(1.0, 0.76, 0.34), fineWave * 0.8);
                    acc += goldViolet * fineWave * Shock.y * 1.35;
                }
            }

            // 5c. 相变球壳与真实地表/建筑的 3D 交线切割辉光（360° 随方块起伏实时扫过）
            if (depth < 0.9999) {
                float dSurf = length(surface - core);
                float seam = abs(dSurf - Shock.x);
                float cutGlow = exp(-(seam * seam) / 1.6);
                acc += vec3(0.20, 0.96, 1.0) * cutGlow * Shock.y * 1.25;
            }

            // 5d. 爆心反冲暗蚀核外圈：爆发前几帧在漆黑反冲核边缘燃起极细爱因斯坦白金光子刃
            float eclipseWindow = sin(clamp(Collapse.w / 0.28, 0.0, 1.0) * FX_PI);
            if (eclipseWindow > 0.01 && tc > 0.0) {
                float eclipseR = max(Shock.z * 0.85, 1.6) * eclipseWindow;
                if (tc - eclipseR < sceneT) {
                    float einstein = exp(-abs(dCore - eclipseR * 1.12) / max(eclipseR * 0.11, 0.08));
                    acc += mix(vec3(1.0, 0.82, 0.42), vec3(0.95, 0.99, 1.0), einstein) * einstein * eclipseWindow * 3.2;
                }
            }
        } else if (tc > 0.0) {
            // 砸地瞬间紧凑震波光边
            float frontT = tc - sqrt(max(Shock.x * Shock.x - dCore * dCore, 0.0));
            if (frontT < sceneT) {
                float qShock = (dCore - Shock.x) / max(Shock.z * 0.65, 0.12);
                acc += vec3(0.32, 0.92, 1.0) * exp(-(qShock * qShock)) * Shock.y * 1.35;
            }
        }
    }

    fragColor = vec4(acc, 1.0);
}
