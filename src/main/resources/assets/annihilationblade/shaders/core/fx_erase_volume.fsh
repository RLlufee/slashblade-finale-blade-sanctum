#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 虚无权域 · 体积通道（解析薄壳 × 地形截交光刃 × 坍缩收敛流 × 深度遮挡 CRT 归零耀斑）：
//  · 外穹顶与活跃扫描/坍缩/重绘球壳均采用解析射线-球求交，零步进条纹、零欠采样闪烁；
//  · 波前与地表/山体相交处触发锐利截交光环；
//  · 仅在坍缩收束期启用 16 步轻量平滑收敛流，避免大面积白雾洗白画面。
uniform sampler2D Sampler1; // 场景原始深度（R32F）
uniform mat4 InvProj;
uniform mat4 InvView;
uniform mat4 ViewProj;
uniform vec2 OutSize;
uniform vec4 Dome;  // xyz = 穹顶中心（相对相机），w = 外穹顶半径
uniform vec4 Erase; // x = 总存在度，y = 活跃波前半径，z = 波前宽度，w = 波前强度
uniform vec4 Phase; // x = 演出 tick，y = 坍缩进度 (0..1)，z = 核心光晕强度，w = CRT 归零耀斑强度
uniform vec4 Extra; // x = 原始满半径，y = 外穹顶薄壳强度，z = 视觉倍率，w = 阶段编号
uniform float FxTime;
uniform float Steps;

in vec2 texCoord;
out vec4 fragColor;

// 解析计算单个球壳交点处的菲涅尔薄壳、经纬建筑细刻度与地形截交锋线
vec3 evalShellHit(vec3 rd, vec3 center, float radius, float tHit, float sceneT, float gridAmp, float rimPow, float intersectAmp) {
    if (tHit <= 0.02 || tHit >= sceneT) {
        return vec3(0.0);
    }
    vec3 local = rd * tHit - center;
    vec3 n = local / max(radius, 0.001);
    float ndv = clamp(abs(dot(n, rd)), 0.0, 1.0);
    float fresnel = pow(max(1.0 - ndv, 1.0e-5), rimPow);

    float az = atan(n.z, n.x) / FX_TAU + 0.5;
    float el = asin(clamp(n.y, -1.0, 1.0)) / FX_PI + 0.5;
    float mer = 1.0 - smoothstep(0.006, 0.020, abs(fract(az * 16.0 + FxTime * 0.015) - 0.5));
    float lat = 1.0 - smoothstep(0.006, 0.020, abs(fract(el * 8.0) - 0.5));
    float polarFade = 1.0 - smoothstep(0.84, 0.98, abs(n.y));
    float lattice = max(mer * polarFade * 0.65, lat * 0.85) * gridAmp;

    // 球壳切入地形/方块表面时的锐利截交光刃
    float terrainCut = exp(-max(sceneT - tHit, 0.0) * 2.6) * intersectAmp;
    float energy = fresnel * 0.52 + lattice * (0.28 + 0.72 * fresnel) + terrainCut;
    vec3 coldWhite = mix(vec3(0.76, 0.84, 0.96), vec3(1.0, 1.0, 1.0), clamp(terrainCut + fresnel * 0.6, 0.0, 1.0));
    return coldWhite * energy;
}

void main() {
    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);

    vec3 center = Dome.xyz;
    float outerR = Dome.w;
    float presence = Erase.x;
    vec3 acc = vec3(0.0);

    // ─── 1. 解析外穹顶建筑薄壳（清晰冷银轮廓 + 经纬细刻度 + 地形截交线）───
    if (outerR > 0.1 && presence > 0.001 && Extra.y > 0.001) {
        vec2 hit = fxSphere(rd, center, outerR);
        if (hit.y > 0.0) {
            vec3 nearShell = evalShellHit(rd, center, outerR, hit.x, sceneT, 0.42, 3.4, 0.55);
            vec3 farShell = evalShellHit(rd, center, outerR, hit.y, sceneT, 0.24, 3.8, 0.35) * 0.55;
            acc += (nearShell + farShell) * Extra.y * presence;
        }
    }

    // ─── 2. 解析活跃波前球壳（仅在扫描 / 坍缩 / 重绘阶段点亮，杜绝常驻乳白雾球）───
    float waveR = Erase.y;
    float waveStrength = Erase.w;
    if (waveR > 0.08 && presence > 0.001 && waveStrength > 0.001) {
        vec2 wHit = fxSphere(rd, center, waveR);
        if (wHit.y > 0.0) {
            float isRestore = step(3.5, Extra.w);
            float rimPow = mix(2.2, 3.0, isRestore);
            float cutAmp = mix(1.35, 0.75, isRestore);
            vec3 nearWave = evalShellHit(rd, center, waveR, wHit.x, sceneT, 0.58, rimPow, cutAmp);
            vec3 farWave = evalShellHit(rd, center, waveR, wHit.y, sceneT, 0.32, rimPow + 0.6, cutAmp * 0.55) * 0.48;
            acc += (nearWave + farWave) * waveStrength * presence;
        }
    }

    // ─── 3. 坍缩向心收敛流（仅在 Phase 3 坍缩期间 16 步轻量步进，低频平滑无闪烁）───
    float collapse = Phase.y;
    if (collapse > 0.01 && waveR > 0.25 && presence > 0.001) {
        vec2 cHit = fxSphere(rd, center, waveR);
        float t0 = max(cHit.x, 0.0);
        float t1 = min(cHit.y, sceneT);
        if (cHit.y > 0.0 && t0 < t1) {
            int steps = int(clamp(Steps * 0.75, 10.0, 20.0));
            float dt = (t1 - t0) / float(steps);
            float jitter = fxIGN(gl_FragCoord.xy, FxTime);
            vec3 streamAcc = vec3(0.0);
            for (int i = 0; i < 20; i++) {
                if (i >= steps) {
                    break;
                }
                float t = t0 + (float(i) + jitter) * dt;
                vec3 local = rd * t - center;
                float d = length(local);
                float rel = d / max(waveR, 0.1);
                vec3 dir = local / max(d, 0.001);
                float az = atan(dir.z, dir.x);
                float el = asin(clamp(dir.y, -1.0, 1.0));
                // 平滑向心射线束（朝圆心高速收缩）
                float rays = pow(max(0.5 + 0.5 * sin(az * 10.0 + FxTime * 3.5), 1.0e-5), 6.0)
                    * pow(max(0.5 + 0.5 * cos(el * 6.0 - FxTime * 2.0), 1.0e-5), 4.0);
                float inwardPulse = 0.55 + 0.45 * sin(d * 0.85 + FxTime * 16.0);
                float radialMask = smoothstep(0.05, 0.30, rel) * (1.0 - smoothstep(0.62, 1.0, rel));
                streamAcc += vec3(0.84, 0.90, 1.0) * rays * inwardPulse * radialMask * dt;
            }
            acc += streamAcc * (0.42 * collapse * presence / max(waveR * 0.12, 0.6));
        }
    }

    // ─── 4. 中枢奇点日蚀冷银微晕（严格受深度遮挡，绝不穿墙）───
    float tc = dot(center, rd);
    float dRay = length(center - rd * tc);
    if (tc > 0.0 && tc < sceneT && Phase.z > 0.001) {
        float coreR = max(Extra.z * 0.65, 0.35);
        float ringGlow = exp(-abs(dRay - coreR * 1.15) / max(coreR * 0.32, 0.12));
        float haloNorm = dRay / max(coreR * 2.2, 0.6);
        float softHalo = exp(-(haloNorm * haloNorm));
        acc += (vec3(0.95, 0.98, 1.0) * ringGlow * 0.55 + vec3(0.76, 0.84, 0.96) * softHalo * 0.22)
            * (0.45 + collapse * 0.95) * Phase.z;
    }

    // ─── 5. CRT 归零水平扫描裂线与奇点星芒（严格深度遮挡检查）───
    vec4 clipCenter = ViewProj * vec4(center, 1.0);
    if (Phase.w > 0.001 && tc > 0.0 && tc < sceneT && clipCenter.w > 0.05) {
        vec2 centerUv = clipCenter.xy / clipCenter.w * 0.5 + 0.5;
        float aspect = OutSize.x / max(OutSize.y, 1.0);
        vec2 delta = (uv - centerUv) * vec2(aspect, 1.0);
        float horizontal = exp(-abs(uv.y - centerUv.y) * 440.0) * exp(-abs(uv.x - centerUv.x) * 1.35);
        float vertical = exp(-abs(uv.x - centerUv.x) * 320.0 * aspect) * exp(-abs(uv.y - centerUv.y) * 5.2);
        float point = exp(-length(delta) * 115.0);
        float halo = exp(-length(delta) * 18.0) * 0.28;
        acc += vec3(0.96, 0.98, 1.0) * (horizontal * 1.15 + vertical * 0.42 + point * 2.2 + halo) * Phase.w;
    }

    fragColor = vec4(acc, 1.0);
}
