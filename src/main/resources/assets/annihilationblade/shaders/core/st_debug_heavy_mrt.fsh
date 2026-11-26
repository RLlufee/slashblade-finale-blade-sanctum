#version 150

#ifdef GL_ARB_explicit_attrib_location
#extension GL_ARB_explicit_attrib_location : enable
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outAux;
layout(location = 2) out vec4 outEmissive;
#else
out vec4 outColor;
out vec4 outAux;
out vec4 outEmissive;
#endif

#moj_import <annihilationblade:st_common.glsl>
#moj_import <annihilationblade:st_optics.glsl>
#moj_import <annihilationblade:st_relativity.glsl>

// Step 1.5 专属 3 附件 MRT 光学沙盒测试着色器：
// 严格遵循 3 附件物理分工：
//  - Attachment 0 (outColor)    : 世界遮挡与弱引力透镜折射层（不进 stFilmEmit，保住弯曲后真实山川原色 + 纯黑视界）；
//  - Attachment 1 (outAux)      : NEAREST 线性有效深度 + 仅在透镜折射区启用表面细节回传；
//  - Attachment 2 (outEmissive) : 纯自发光层（多普勒黑体吸积盘 + 薄膜虹彩光子刃 + 3D 体积星云日冕），统一进入 stFilmEmit/泛光/星芒/体积光。
uniform sampler2D Sampler0; // 线性 HDR 场景
uniform sampler2D Sampler1; // R32F 场景深度
uniform sampler2D Sampler2; // 256x1 RGBA16F 黑体色温 LUT
uniform sampler2D Sampler3; // 64x64 蓝噪声
uniform sampler3D Noise3D;  // 64^3 3D 体积噪声（由 StellarisPipeline 在高纹理槽独立绑定）

uniform mat4 InvProj;
uniform mat4 InvView;
uniform mat4 ViewProj;
uniform float StTime;
uniform vec2 OutSize;
uniform vec4 FrameMeta;     // x=frameIdx
uniform vec4 CoreSphere;    // xyz=球心(相对相机), w=半径
uniform vec4 CoreParams;    // x=能量倍率, y=星环倾角, z=动画相位, w=保留

in vec2 texCoord;

void main() {
    vec3 rd = stViewRay(texCoord, InvProj, InvView);
    float sceneDepth = texture(Sampler1, texCoord).r;
    float sceneT = stSceneDistance(sceneDepth, texCoord, InvProj);

    vec3 center = CoreSphere.xyz;
    float R = max(CoreSphere.w, 0.35);
    float energy = max(CoreParams.x, 0.2);

    // 1. 外围星云包络球（2.65R）早退
    vec2 outerHit = stIntersectSphere(vec3(0.0), rd, center, R * 2.65);
    if (outerHit.y <= 0.0 || outerHit.x >= sceneT) {
        outColor = vec4(0.0);
        outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
        outEmissive = vec4(0.0);
        return;
    }

    vec4 bn = stBlueNoise(Sampler3, gl_FragCoord.xy, FrameMeta.x);

    // 2. 内核纯黑奇点球（0.55R）
    vec2 coreHit = stIntersectSphere(vec3(0.0), rd, center, R * 0.55);
    bool hitCore = (coreHit.y > 0.0 && coreHit.x > 0.0 && coreHit.x < sceneT);

    // 3. 倾斜双色温物理黑体星环求交（盘面法线随倾角旋转）
    vec3 ringNormal = normalize(vec3(sin(CoreParams.y), cos(CoreParams.y), 0.26));
    vec3 ringU;
    vec3 ringV;
    stSafeOrthoBasis(ringNormal, ringU, ringV);
    float planeT = stIntersectPlane(vec3(0.0), rd, center, ringNormal);

    vec3 ringFrontEmit = vec3(0.0);
    vec3 ringBackEmit = vec3(0.0);
    float ringFrontT = 1.0e6;
    if (planeT > 0.0 && planeT < sceneT) {
        vec3 hp = rd * planeT - center;
        float pr = length(hp);
        float innerR = R * 0.85;
        float outerR = R * 2.45;
        if (pr > innerR && pr < outerR) {
            float uNorm = (pr - innerR) / (outerR - innerR);
            float ang = stSafeAtan(dot(hp, ringV), dot(hp, ringU));
            float omega = StTime * (1.2 / stSafePow(max(pr / R, 0.4), 1.35));
            vec3 nCoord = vec3(cos(ang - omega) * 0.45 + 0.5, sin(ang - omega) * 0.45 + 0.5, uNorm * 1.4 + StTime * 0.08);
            vec4 n3 = texture(Noise3D, nCoord);
            float strands = smoothstep(0.24, 0.86, n3.r * 0.58 + n3.g * 0.42);
            float radialEnv = smoothstep(0.0, 0.16, uNorm) * (1.0 - smoothstep(0.62, 1.0, uNorm));

            vec3 tangentDir = normalize(-sin(ang) * ringU + cos(ang) * ringV);
            float gDoppler = stDopplerFactor(tangentDir * 0.34, -rd);
            float baseTemp = mix(18500.0, 2900.0, stSafePow(uNorm, 0.72));
            vec3 bbCol = stSampleBlackbodyLut(Sampler2, baseTemp * gDoppler);
            // 拉开等离子湍流暗纹与高热亮丝的对比度，杜绝整个吸积盘糊成一片惨白
            float boost = stSafePow(gDoppler, 2.2) * radialEnv * (0.16 + 1.15 * strands * strands) * energy * 2.5;
            vec3 ringCol = bbCol * boost;

            if (!hitCore || planeT < coreHit.x) {
                ringFrontEmit = ringCol;
                ringFrontT = planeT;
            } else {
                ringBackEmit = ringCol * 0.75;
            }
        }
    }

    // 4. 体积星云日冕（8 步蓝噪声抖动光线步进采样 64^3 3D 噪声，杜绝裸 pow 负底数）
    float tStart = max(0.0, outerHit.x);
    float tEnd = min(outerHit.y, hitCore ? coreHit.x : sceneT);
    vec3 volRadiance = vec3(0.0);
    float volAlpha = 0.0;
    if (tEnd > tStart + 0.01) {
        const int STEPS = 8;
        float stepLen = (tEnd - tStart) / float(STEPS);
        float t = tStart + stepLen * bn.r;
        for (int i = 0; i < STEPS; i++) {
            vec3 p = rd * t - center;
            float rNorm = length(p) / R;
            vec3 q = p / (R * 2.4) + vec3(StTime * 0.06, -StTime * 0.04, StTime * 0.05);
            vec4 n3 = texture(Noise3D, q);
            float qr = rNorm - 0.92;
            float shell = exp(-(qr * qr) * 3.2) * (n3.r * 0.6 + n3.g * 0.4);
            float dens = shell * 0.24 * (stepLen / R);
            vec3 nCol = mix(vec3(0.015, 0.78, 1.0), vec3(0.28, 0.12, 1.0), n3.b);
            volRadiance += nCol * dens * energy * 1.15 * (1.0 - volAlpha);
            volAlpha = stSaturate(volAlpha + dens * 0.34);
            t += stepLen;
        }
    }

    // 5. 严格分离世界遮挡/折射层（worldLayer）与纯自发光层（emissiveLayer）
    vec3 worldLayerRgb = vec3(0.0);
    float worldLayerAlpha = 0.0;
    float detailWeight = 0.0;
    vec3 emissiveLayer = ringBackEmit;
    float bestT = min(max(outerHit.x, 0.01), ringFrontT);

    // 紧贴视界外围做以真实球心投影 UV 为中心的解析弱引力透镜（转头视角绝不偏歪）
    float closestApproach = length(cross(center, rd));
    if (!hitCore && closestApproach < R * 1.65 && outerHit.x < sceneT) {
        float ql = (closestApproach / R - 0.85) / 0.48;
        float lensRing = exp(-(ql * ql));
        vec4 clipC = ViewProj * vec4(center, 1.0);
        vec2 centerUv = (clipC.w > 0.1) ? ((clipC.xy / clipC.w) * 0.5 + 0.5) : vec2(0.5);
        vec2 radialDir = texCoord - centerUv;
        float rLen = length(radialDir);
        vec2 dirUv = (rLen > 1.0e-4) ? (radialDir / rLen) : vec2(1.0, 0.0);
        // 按屏幕纵横比归一化偏移量，确保 720p~4K 与动态分辨率阶梯（0.60x~1.00x）切换时折射幅度 100% 恒定零跳变
        float aspect = OutSize.x / max(OutSize.y, 1.0);
        vec2 lensOffset = dirUv * vec2(1.0 / max(aspect, 0.25), 1.0) * (0.0085 * lensRing);
        vec2 refractUv = stSafeRefractUv(texCoord, lensOffset, max(0.0, outerHit.x), Sampler1, InvProj);
        vec3 bgLensed = max(stSanitize(texture(Sampler0, refractUv).rgb), vec3(0.0));
        float lensAlpha = lensRing * 0.55;
        worldLayerRgb = bgLensed * lensAlpha;
        worldLayerAlpha = lensAlpha;
        // 位移折射像素严禁开启原位表面细节回传，杜绝把未折射位置方块砖缝鬼影拓印到透镜环上
        detailWeight = 0.0;
    }

    if (hitCore) {
        vec3 hitP = rd * coreHit.x;
        vec3 normal = normalize(hitP - center);
        float ndv = stSaturate(dot(normal, -rd));
        float fresnel = stSafePow(1.0 - ndv, 3.4);
        vec3 irid = stThinFilmIridescence(ndv, 520.0 + 140.0 * sin(StTime * 1.4));
        vec3 rim = irid * fresnel * energy * 6.5;
        // 纯黑事件视界 100% 吞噬背后（worldLayerRgb = 0, alpha = 1），光子刃走纯自发光通道，前景星云雾压薄守住深渊纯黑中心
        worldLayerRgb = vec3(0.0);
        worldLayerAlpha = 1.0;
        detailWeight = 0.0;
        emissiveLayer = rim;
        bestT = min(coreHit.x, ringFrontT);
    }

    emissiveLayer += ringFrontEmit + volRadiance * (hitCore ? 0.28 : 1.0);
    // 若仅有半透明星云而无视界/透镜，保持 worldLayerAlpha 极低，避免发灰遮挡原世界
    worldLayerAlpha = max(worldLayerAlpha, volAlpha * 0.18);

    outColor = vec4(stSanitize(worldLayerRgb), stSaturate(worldLayerAlpha));
    outAux = vec4(bestT, 1.0 - stSaturate(worldLayerAlpha), stSaturate(stLuma(emissiveLayer) * 0.25), detailWeight);
    outEmissive = vec4(stSanitize(emissiveLayer), 1.0);
}
