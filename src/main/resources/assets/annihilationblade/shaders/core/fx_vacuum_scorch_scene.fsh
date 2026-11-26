#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 真空衰变 · 非破坏地形黑洞烤焦场景通道：
//  · 不改动世界方块，基于深度重建真实 3D 地表/山体/树冠/建筑表面，压上一层高质感黑曜炭化焦层；
//  · 中心极深黢黑吸光并浮现 3D Voronoi 焦土龟裂暗缝与余热灼痕，外缘呈放射状冲击风蚀齿缘过渡；
//  · 焦区上空施加随冷却减弱的上升空气热浪折射（Heat Shimmer）；
//  · 8 秒冷却尾段沿 3D 噪声由外向内优雅消融复原，零硬边界跳变。
uniform sampler2D Sampler0; // 场景颜色
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
    vec3 c = mix(vec3(0.28, 0.015, 0.005), vec3(1.0, 0.24, 0.02), smoothstep(0.0, 0.42, x));
    c = mix(c, vec3(1.0, 0.68, 0.22), smoothstep(0.40, 0.82, x));
    return mix(c, vec3(1.0, 0.96, 0.88), smoothstep(0.82, 1.20, x));
}

void main() {
    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);
    bool sky = depth >= 0.9999;

    // 导数必须在一致控制流中求取，用于迎热面法线光照衰减与真实立体炭化
    vec3 surface = rd * min(sceneT, 4096.0);
    vec3 surfaceNormal = normalize(cross(dFdx(surface), dFdy(surface)) + vec3(0.0, 1.0e-6, 0.0));

    float activeRadius = ScorchCenter.w;
    float charVis = clamp(ScorchState.x, 0.0, 1.0);
    float heat = clamp(ScorchState.y, 0.0, 1.0);
    float dissolve = clamp(ScorchState.z, 0.0, 1.0);
    float shimmerPower = clamp(ScorchState.w, 0.0, 1.0);
    float maxR = max(ScorchExtra.x, 4.0);
    float seed = ScorchExtra.y;
    float coreR = max(ScorchExtra.z, 2.0);

    // 1. 焦坑上空热浪空气折射（Heat Shimmer）：近地翻涌热流扭曲背景景色，带前景深度保护
    vec2 sampleUv = uv;
    if (shimmerPower > 0.002 && activeRadius > 0.5) {
        vec3 toCenter = ScorchCenter.xyz;
        float tc = dot(toCenter, rd);
        if (tc > 0.5 && tc < sceneT + activeRadius) {
            vec3 pHeat = rd * min(tc, sceneT);
            vec3 hRel = pHeat - ScorchCenter.xyz;
            float hDist = length(hRel.xz);
            float aboveGround = hRel.y;
            float radialMask = exp(-(hDist * hDist) / max(activeRadius * activeRadius * 0.42, 16.0));
            float heightMask = smoothstep(-2.0, 1.5, aboveGround) * exp(-max(aboveGround, 0.0) / 14.0);
            float sWeight = radialMask * heightMask * shimmerPower;
            if (sWeight > 0.003) {
                float w1 = fxNoise3(vec3(uv * 38.0, FxTime * 3.4 + seed)) - 0.5;
                float w2 = fxNoise3(vec3(uv * 54.0 + vec2(7.3, 4.1), FxTime * 4.2 - seed)) - 0.5;
                vec2 wob = vec2(w1, w2 * 0.7) * 0.0048 * sWeight;
                sampleUv = fxSafeRefractUv(uv, wob, max(tc - activeRadius * 0.6, 0.5), Sampler1, InvProj);
            }
        }
    }

    vec3 color = texture(Sampler0, sampleUv).rgb;

    // 2. 非破坏真实 3D 地形黢黑烤焦层（仅作用于实景表面，绝不染黑天空或贴脸镜头）
    if (!sky && sceneT > 0.35 && charVis > 0.001 && activeRadius > 0.2) {
        vec3 rel = surface - ScorchCenter.xyz;
        // 高度轴略微收紧为扁椭球，完整烤焦丘陵、树冠、建筑与洞壁，同时避免误染高空浮岛
        float dist3d = length(vec3(rel.x, rel.y * 1.38, rel.z));
        if (dist3d < activeRadius * 1.28) {
            float ang = fxSafeAtan(rel.z, rel.x);
            vec2 radialDir = vec2(cos(ang), sin(ang));

            // 放射状冲击风蚀边界与焦灰流向噪声：拒绝呆板正圆，打造真实爆风烤灼齿缘
            float blastRays = fxFbm3(vec3(radialDir * 3.4 + vec2(seed * 0.17), rel.y * 0.06 + seed * 0.31));
            float fineRays = fxNoise3(vec3(radialDir * 11.0, dist3d * 0.06 + seed));
            float edgeNoise = fxFbm3(rel * 0.095 + vec3(seed * 1.7));
            float jaggedR = activeRadius * (0.86 + 0.26 * blastRays + 0.12 * edgeNoise);

            // 径向焦黑强度：内圈重度炭化，中圈浓黑带放射风蚀条纹，外圈自然烟熏渐淡
            float normDist = dist3d / max(jaggedR, 0.5);
            float baseChar = 1.0 - smoothstep(0.18, 1.0, normDist);
            float blastStreaks = smoothstep(0.38, 0.85, fineRays) * (1.0 - smoothstep(0.65, 1.08, normDist)) * 0.28;
            float rawChar = clamp(baseChar + blastStreaks, 0.0, 1.0);

            // 8 秒尾段优雅消融：由外围向爆心、沿 3D 焦灰噪声块逐片褪色还清真实地表
            float dissolveNoise = edgeNoise * 0.58 + blastRays * 0.42;
            float fadeEdge = dissolve * 1.18 + normDist * dissolve * 0.42;
            float aliveMask = dissolve > 0.001
                ? smoothstep(fadeEdge - 0.26, fadeEdge + 0.18, dissolveNoise + (1.0 - normDist) * 0.38)
                : 1.0;

            float charMask = clamp(rawChar * aliveMask * charVis, 0.0, 1.0);
            if (charMask > 0.002) {
                // 迎向爆心与朝天表面的受热因子：树干/山坡迎爆面烤得最黢黑，背爆面留存少许原色层次
                vec3 toBlast = normalize(-rel + vec3(0.0, 2.8, 0.0));
                float facing = 0.62 + 0.38 * clamp(dot(surfaceNormal, toBlast) * 0.65 + max(surfaceNormal.y, 0.0) * 0.55, 0.0, 1.0);
                float effectiveChar = clamp(charMask * facing, 0.0, 1.0);

                // 焦土微表面粗糙炭粒与原方块明暗纹理保留（防死黑平板，保持 MC 体素立体质感！）
                float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                float sootGrain = fxFbm3(rel * 0.42 + vec3(seed * 3.7));
                float deepCore = 1.0 - smoothstep(0.0, coreR * 2.6, dist3d);

                // 3D Voronoi 焦土龟裂暗缝：把烤干碳化的地壳裂纹直接刻进原方块表面
                vec3 cellId;
                vec3 vor = fxVoronoi3Fast(rel * 0.36 + vec3(seed * 0.5), cellId);
                float crackLine = exp(-vor.y * 16.0);
                float cellHash = fxHash13(cellId);

                // 三段真实炭化色阶：外缘烟熏深棕灰 → 中区黑曜炭黑 → 爆心极深吸光黑焦壳
                vec3 smokedOuter = color * vec3(0.30, 0.24, 0.22);
                vec3 charcoalMid = vec3(0.022, 0.020, 0.028) + vec3(0.085, 0.072, 0.088) * (luma * (0.42 + 0.58 * sootGrain));
                vec3 obsidianCore = vec3(0.008, 0.007, 0.014) + vec3(0.032, 0.028, 0.044) * (luma * (0.35 + 0.65 * sootGrain));

                vec3 darkSurface = mix(smokedOuter, charcoalMid, smoothstep(0.08, 0.58, effectiveChar));
                darkSurface = mix(darkSurface, obsidianCore, clamp(deepCore * 0.85 + smoothstep(0.68, 0.96, effectiveChar) * 0.55, 0.0, 1.0));
                // 压深龟裂碳缝，并令爆心附近形成视觉上的微微焦坑暗影凹陷感
                darkSurface *= (1.0 - crackLine * 0.48 * effectiveChar);
                darkSurface *= (1.0 - deepCore * 0.35 * charVis);

                // 场景层叠加少量克制余炭基底红光（主要高光泛光交由 volume 通道写入 HDR Bloom）
                if (heat > 0.01) {
                    float coreHeat = exp(-dist3d / max(maxR * 0.36, 4.0)) * heat;
                    float emberMask = crackLine * coreHeat * (0.45 + 0.55 * cellHash);
                    darkSurface += scorchBlackbody(0.18 + 0.65 * coreHeat) * emberMask * 0.55 * aliveMask;
                }

                color = mix(color, darkSurface, clamp(effectiveChar * 0.96, 0.0, 0.97));
            }
        }
    }

    fragColor = vec4(max(fxSanitize(color), vec3(0.0)), 1.0);
}
