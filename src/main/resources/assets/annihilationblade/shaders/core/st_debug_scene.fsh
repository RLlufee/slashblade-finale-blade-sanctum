#version 150

#ifdef GL_ARB_explicit_attrib_location
#extension GL_ARB_explicit_attrib_location : enable
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outEmit;
#else
out vec4 outColor;
out vec4 outEmit;
#endif

#moj_import <annihilationblade:st_common.glsl>
#moj_import <annihilationblade:st_optics.glsl>
#moj_import <annihilationblade:st_astro.glsl>
#moj_import <annihilationblade:st_relativity.glsl>
#moj_import <annihilationblade:st_hud.glsl>

// Step 1.6 升级版双输出线性 HDR 场景改写验证 Pass（/stellaris debug test scene）：
//  - Attachment 0 (outColor): 改写后的线性世界画面（Hillaire 星穹暮光、3 层银边旋涡云洞、微虫洞折射、实时地形重打光与焦散、大地撕裂深空视差窗）；
//  - Attachment 1 (outEmit) : 阶段 4 纯 HDR 自发光增量（夺夜亮星、0 步进 360° 环绕极光、非 Voronoi 有机苍穹裂纹、世界锁定晶微闪、零 UB 解析遥测读数与爱因斯坦环），
//                             正式汇入 6 级泛光、变形星芒、自动曝光与 AgX 色调映射！
uniform sampler2D Sampler0; // 线性 HDR 场景
uniform sampler2D Sampler1; // R32F 共享不透明场景深度
uniform sampler2D Sampler2; // 256x1 普朗克×CIE 黑体色温 LUT
uniform sampler2D Sampler3; // 64x64 Void-and-Cluster 蓝噪声
uniform sampler3D Noise3D;  // 64^3 3D 体积噪声（GL_TEXTURE12）
uniform sampler2D PrevSceneEmit; // 上一场景 Pass 的纯自发光累积（GL_TEXTURE13）
uniform sampler2D ReliefMetaTex; // 3D 浮雕法阵 ReliefMeta（GL_TEXTURE14）

uniform mat4 InvProj;
uniform mat4 InvView;
uniform mat4 ViewProj;
uniform float StTime;
uniform vec2 OutSize;
uniform vec4 FrameMeta;     // x=frameIdx, y=bhSteps, z=auroraLayers
uniform vec4 AnchorPos;     // xyz=中心锚点(相对相机), w=半径
uniform vec4 SceneParams;   // x=总包络(0..1), y=夺夜强度, z=虫洞爱因斯坦半径(屏幕角), w=遥测读数
uniform vec4 ReliefParams;  // xy=reliefUvMax, z=hasRelief(>0.5), w=0

in vec2 texCoord;

void main() {
    vec2 uv = texCoord;
    // 严格在一致控制流最顶部求取视线方向与角足迹，绝不触碰动态分支导数 UB
    vec3 rd = stViewRay(uv, InvProj, InvView);
    float angularFootprint = max(length(dFdx(rd)) + length(dFdy(rd)), 1.0 / max(OutSize.y, 1.0));
    vec3 prevEmit = max(stSanitize(texture(PrevSceneEmit, uv).rgb), vec3(0.0));

    float env = stSaturate(SceneParams.x);
    if (env <= 0.001) {
        outColor = vec4(stSanitize(texture(Sampler0, uv).rgb), 1.0);
        outEmit = vec4(prevEmit, 1.0);
        return;
    }

    vec4 bn = stBlueNoise(Sampler3, gl_FragCoord.xy, FrameMeta.x);

    // 1. 解析点质量引力透镜（微虫洞）：以锚点上方 2.5 格为中心，双层遮挡保护弯曲背后景物
    vec2 sampleUv = uv;
    float lensRingGlow = 0.0;
    vec3 lensCenter = AnchorPos.xyz + vec3(0.0, 2.5, 0.0);
    vec4 clipLens = ViewProj * vec4(lensCenter, 1.0);
    float lensDist = length(lensCenter);
    if (clipLens.w > 0.25 && SceneParams.z > 0.001) {
        vec2 lensUv = (clipLens.xy / clipLens.w) * 0.5 + 0.5;
        float aspect = OutSize.x / max(OutSize.y, 1.0);
        vec2 delta = (uv - lensUv) * vec2(aspect, 1.0);
        float theta = length(delta);
        float thetaE = SceneParams.z * env;
        if (theta < thetaE * 3.8) {
            vec2 srcDelta = stPointMassLens(delta, thetaE);
            vec2 offset = (srcDelta - delta) / vec2(aspect, 1.0);
            float blend = 1.0 - smoothstep(thetaE * 2.2, thetaE * 3.8, theta);
            sampleUv = stSafeRefractUvRelief(
                uv, offset * blend, lensDist,
                Sampler1, ReliefMetaTex, ReliefParams.xy, ReliefParams.z > 0.5, InvProj
            );
            float qRing = (theta - thetaE) / max(thetaE * 0.13, 0.0028);
            lensRingGlow = exp(-qRing * qRing) * blend;
        }
    }

    float depth = texture(Sampler1, sampleUv).r;
    float sceneT = stSceneDistance(depth, sampleUv, InvProj);
    vec3 worldCol = max(stSanitize(texture(Sampler0, sampleUv).rgb), vec3(0.0));
    vec3 emitCol = vec3(0.0);

    // 2. 0 步进解析圆柱 360° 环绕极光（立体包裹整个结界周边，并受真实地形深度遮挡）
    if (SceneParams.y > 0.001) {
        vec3 cylAurora = stAuroraCylinder(rd, AnchorPos.xyz, max(AnchorPos.w * 1.45, 18.0), -2.0, 28.0, StTime, sceneT);
        emitCol += cylAurora * (SceneParams.y * env * 0.72);
    }

    // 3. 天空像素（depth >= 0.9999）纯着色器星穹夺夜：Hillaire 物理暮光 + 3D 暗尘埃银河 + 亮星进 Bloom + 旋涡云洞 + 有机苍穹裂纹
    if (depth >= 0.9999 && SceneParams.y > 0.001) {
        float nightMask = stSaturate(SceneParams.y * env * smoothstep(-0.08, 0.22, rd.y + 0.14));
        vec3 twilight = stCosmicAtmosphereTwilight(rd, normalize(vec3(0.25, 0.88, -0.40)));
        float dustTrans;
        vec3 milky = stMilkyWayTex(rd, StTime, Noise3D, dustTrans);
        vec3 stars = stStarfieldLut(rd, StTime, angularFootprint, Sampler2) * dustTrans;
        vec3 galaxyA = stSpiralGalaxy(
            rd,
            normalize(vec3(0.55, 0.62, -0.56)),
            vec3(0.0, 1.0, 0.0),
            0.20,
            4.8,
            vec3(0.95, 0.85, 0.65),
            vec3(0.14, 0.72, 1.00)
        );
        vec3 galaxyB = stSpiralGalaxy(
            rd,
            normalize(vec3(-0.62, 0.54, 0.56)),
            vec3(0.2, 1.0, 0.1),
            0.14,
            5.6,
            vec3(0.88, 0.82, 1.00),
            vec3(0.52, 0.26, 1.00)
        );
        int auroraLayers = clamp(int(FrameMeta.z + 0.5), 8, 24);
        vec3 skyAurora = stAuroraCurtainsJittered(rd, StTime, auroraLayers, bn.r);

        // 3 层世界锚定旋涡云洞（被星穹奇点照出炽热冰青银边）
        vec4 swirlCloud = stSwirlCloudHole(rd, AnchorPos.xyz, 26.0, 18.0, 130.0, StTime, sceneT);

        // 13 道非 Voronoi 有机苍穹裂纹（投影到天顶平面）
        float skyCrack = 0.0;
        if (rd.y > 0.10) {
            vec2 skyPlane = (rd.xz / max(rd.y, 0.12)) * 38.0;
            skyCrack = stOrganicSkyCrack(skyPlane, 56.0, StTime) * smoothstep(0.10, 0.34, rd.y);
        }

        // 低频柔和深空背景进入 outColor，高亮恒星核心/星系核/极光峰/苍穹裂纹进入 outEmit（真正触发 Bloom & 星芒！）
        vec3 skyBase = twilight + milky + (galaxyA + galaxyB) * 0.45;
        skyBase = mix(skyBase, swirlCloud.rgb, swirlCloud.a * 0.85);
        worldCol = mix(worldCol, skyBase, nightMask);

        vec3 skyEmission = stars * 1.15
            + (galaxyA + galaxyB) * 0.65
            + skyAurora * 0.68
            + vec3(0.20, 0.92, 1.00) * (skyCrack * 1.35)
            + vec3(0.95, 0.99, 1.00) * (stSafePow(stSaturate(skyCrack * 0.55), 2.5) * 1.65);
        emitCol += skyEmission * nightMask;
    } else if (depth < 0.9999) {
        // 4. 起伏地形表面：最小斜率法线 + 实时彩色重打光/电离焦散 + 零 UB 解析像素脚印全息遥测 + 世界锁定晶微闪 + 撕裂深空窗
        vec3 wp = stReconstructWorldRel(depth, sampleUv, InvProj, InvView);
        vec3 rel = wp - AnchorPos.xyz;
        float horizDist = length(rel.xz);
        float maxR = max(AnchorPos.w, 4.0);
        if (horizDist < maxR * 1.35 && abs(rel.y) < maxR * 0.85) {
            vec3 normal = stReconstructNormal3x3(Sampler1, sampleUv, OutSize, InvProj, InvView);
            vec3 an = abs(normal);
            float absNdotV = abs(dot(normal, -rd));

            // 4a. 实时世界动态彩色重打光 + 双频电离焦散（让星穹光芒真实洒满周围崖壁与大地）
            vec3 relight = stWorldRelight(
                wp, normal, lensCenter,
                vec3(0.06, 0.78, 1.00), maxR * 0.95, 1.25 * env, 0.85, StTime
            );
            worldCol += worldCol * relight * 1.15 + relight * 0.085;

            if (horizDist < maxR * 1.15 && abs(rel.y) < maxR * 0.75) {
                vec2 projUv = (an.y >= an.x && an.y >= an.z) ? rel.xz
                    : ((an.x >= an.z) ? rel.zy : rel.xy);

                float radialEnv = (1.0 - smoothstep(maxR * 0.72, maxR * 1.08, horizDist)) * env;
                float scanWave = exp(-abs(horizDist - fract(StTime * 0.28) * maxR) * 1.4);

                // 零 UB 解析像素脚印（按距离与视角掠射角解析推算，彻底消除动态分支内 fwidth 异常）
                float fpHex = stAnalyticFootprint(sceneT, OutSize.y, 0.85, absNdotV);
                float fpRing = stAnalyticFootprint(sceneT, OutSize.y, 1.0, absNdotV);
                float fpCircuit = stAnalyticFootprint(sceneT, OutSize.y, 0.65, absNdotV);
                float fpHud = stAnalyticFootprint(sceneT, OutSize.y, 1.15, absNdotV);

                vec4 hex = stHexGrid(projUv * 0.85);
                float hexLine = stAaStrokeFp(hex.z, 0.036, fpHex) * (0.28 + 0.72 * scanWave);
                float ringMain = stDashedRingFp(rel.xz, maxR * 0.62, 0.08, 36.0, StTime * 0.25, fpRing);
                float ringInner = stDashedRingFp(rel.xz, maxR * 0.30, 0.06, 18.0, -StTime * 0.40, fpRing);
                float circuits = stCircuitTracesFp(projUv * 0.65, StTime, fpCircuit) * smoothstep(maxR * 0.20, maxR * 0.65, horizDist);

                // 锚点旁水平地面刻蚀七段数码管光年读数（XX.XX ly，零 UB 解析抗锯齿）
                float readout = 0.0;
                if (an.y > 0.65) {
                    vec2 rUv = (rel.xz - vec2(0.0, -2.2)) * 1.15;
                    readout = stReadoutXX_XX_Fp(rUv, SceneParams.w, fpHud);
                    readout = max(readout, stUnitLyFp(rUv - vec2(2.75, 0.0), fpHud));
                }

                // 4b. 大地中心裂隙“撕裂真实深空视差窗”（Beyond-the-Rift）
                float riftRadius = maxR * 0.14;
                if (horizDist < riftRadius * 1.35 && an.y > 0.55) {
                    float jag = (stNoise2(rel.xz * 2.4 + vec2(StTime * 0.15)) - 0.5) * 0.35;
                    float riftMask = (1.0 - smoothstep(riftRadius * 0.65, riftRadius * 1.05, horizDist + jag)) * env;
                    float riftEdge = exp(-abs(horizDist + jag - riftRadius * 0.88) * 5.5) * env;
                    if (riftMask > 0.002) {
                        vec3 deepWindow = stDeepSpaceWindow(rd, StTime, Sampler2);
                        worldCol = mix(worldCol, deepWindow * 0.45, riftMask * 0.94);
                        emitCol += deepWindow * (riftMask * 0.95);
                    }
                    emitCol += vec3(0.18, 0.94, 1.00) * (riftEdge * 2.4);
                }

                // 4c. 世界空间锁定高频晶体微粒闪烁（死锁体素表面，转头闪动绝非屏幕贴纸）
                float sparkle = stWorldHashSparkle(rel * 1.0, rd, 7.5) * radialEnv;

                vec3 cyanLin = vec3(0.012, 0.88, 1.00);
                vec3 violetLin = vec3(0.24, 0.10, 1.00);
                vec3 starWhite = vec3(0.95, 0.99, 1.00);

                // 轻度压暗结界内地面底色以突出全息遥测对比度，同时所有发光线条进入 outEmit 触发泛光！
                worldCol *= mix(1.0, 0.66, radialEnv * 0.72);
                vec3 hudEmit = cyanLin * (hexLine * 0.72 + ringMain * 1.85 + circuits * 0.68)
                    + violetLin * (ringInner * 1.55)
                    + starWhite * (readout * 2.45 + scanWave * hexLine * 1.10 + sparkle * 2.20);
                emitCol += hudEmit * radialEnv;
            }
        }
    }

    // 5. 爱因斯坦微虫洞物理黑体蓝白锐边进入 outEmit（直接激发电影泛光与变形水平星芒！）
    if (lensRingGlow > 0.002) {
        vec3 einsteinCol = stSampleBlackbodyLut(Sampler2, 16500.0);
        emitCol += einsteinCol * (lensRingGlow * 2.85 * env);
    }

    outColor = vec4(stSanitize(worldCol), 1.0);
    outEmit = vec4(stSanitize(prevEmit + emitCol), 1.0);
}
