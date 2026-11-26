#version 150

#moj_import <annihilationblade:st_common.glsl>
#moj_import <annihilationblade:st_optics.glsl>
#moj_import <annihilationblade:st_astro.glsl>
#moj_import <annihilationblade:st_relativity.glsl>
#moj_import <annihilationblade:st_hud.glsl>

// Step 1.5 全链路线性 HDR 场景改写验证 Pass（/stellaris debug test scene）：
// 一次实机肉眼验收：
//  1. 阶段 4 SceneHdr A<->B 浮点乒乓；
//  2. st_astro 360° 无缝暗尘埃银河 + 抗裁切星场 + 旋涡星系 + 蓝噪声极光帷幕夺夜；
//  3. st_common 最小斜率深度法线重建 + 按主法线轴选平面 UV 的起伏地表六边形遥测 + 七段数码管 + 电路迹线；
//  4. st_relativity 零步进解析点质量引力透镜微虫洞（爱因斯坦环与背景镜像）。
uniform sampler2D Sampler0; // 线性 HDR 场景
uniform sampler2D Sampler1; // R32F 共享场景深度
uniform sampler2D Sampler2; // 256x1 黑体色温 LUT
uniform sampler2D Sampler3; // 64x64 蓝噪声
uniform sampler3D Noise3D;  // 64^3 3D 体积噪声（由 StellarisPipeline 绑定至 GL_TEXTURE12）

uniform mat4 InvProj;
uniform mat4 InvView;
uniform mat4 ViewProj;
uniform float StTime;
uniform vec2 OutSize;
uniform vec4 FrameMeta;     // x=frameIdx, y=bhSteps, z=auroraLayers
uniform vec4 AnchorPos;     // xyz=中心锚点(相对相机), w=半径
uniform vec4 SceneParams;   // x=总包络(0..1), y=夺夜强度, z=虫洞爱因斯坦半径(屏幕角), w=遥测读数

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    float env = stSaturate(SceneParams.x);
    if (env <= 0.001) {
        fragColor = vec4(stSanitize(texture(Sampler0, uv).rgb), 1.0);
        return;
    }

    vec3 rd = stViewRay(uv, InvProj, InvView);
    float angularFootprint = max(length(dFdx(rd)) + length(dFdy(rd)), 1.0 / max(OutSize.y, 1.0));
    vec4 bn = stBlueNoise(Sampler3, gl_FragCoord.xy, FrameMeta.x);

    // 1. 解析点质量引力透镜（微虫洞）：以锚点上方 2.5 格处为中心，零步进弯曲其背后景物
    vec2 sampleUv = uv;
    float lensRingGlow = 0.0;
    vec3 lensCenter = AnchorPos.xyz + vec3(0.0, 2.4, 0.0);
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
            sampleUv = stSafeRefractUv(uv, offset * blend, lensDist, Sampler1, InvProj);
            float qRing = (theta - thetaE) / max(thetaE * 0.14, 0.003);
            lensRingGlow = exp(-qRing * qRing) * blend;
        }
    }

    float depth = texture(Sampler1, sampleUv).r;
    vec3 worldCol = max(stSanitize(texture(Sampler0, sampleUv).rgb), vec3(0.0));

    // 2. 天空像素（depth >= 0.9999）纯着色器夺夜：360° 无缝暗尘埃银河 + 抗裁切星场 + 旋涡星系 + 蓝噪声极光
    if (depth >= 0.9999 && SceneParams.y > 0.001) {
        float nightMask = stSaturate(SceneParams.y * env * smoothstep(-0.08, 0.22, rd.y + 0.14));
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
        int auroraLayers = clamp(int(FrameMeta.z + 0.5), 8, 40);
        vec3 aurora = stAuroraCurtainsJittered(rd, StTime, auroraLayers, bn.r);
        vec3 deepSpace = vec3(0.0012, 0.0028, 0.011) + milky + stars + galaxyA + galaxyB + aurora * 0.72;
        worldCol = mix(worldCol, deepSpace, nightMask);
    } else if (depth < 0.9999) {
        // 3. 起伏地形表面：最小斜率深度法线重建 + 按主法线轴选平面 UV 的零纹理六边形遥测、电路迹线与七段读数
        vec3 wp = stReconstructWorldRel(depth, sampleUv, InvProj, InvView);
        vec3 rel = wp - AnchorPos.xyz;
        float horizDist = length(rel.xz);
        float maxR = max(AnchorPos.w, 4.0);
        if (horizDist < maxR * 1.15 && abs(rel.y) < maxR * 0.75) {
            vec3 normal = stReconstructNormal3x3(Sampler1, sampleUv, OutSize, InvProj, InvView);
            vec3 an = abs(normal);
            // 按主法线轴选取平面投影坐标，让竖直崖壁与阶梯立面也有干净无缝网格
            vec2 projUv = (an.y >= an.x && an.y >= an.z) ? rel.xz
                : ((an.x >= an.z) ? rel.zy : rel.xy);

            float radialEnv = (1.0 - smoothstep(maxR * 0.72, maxR * 1.08, horizDist)) * env;
            float scanWave = exp(-abs(horizDist - fract(StTime * 0.28) * maxR) * 1.4);

            vec4 hex = stHexGrid(projUv * 0.85);
            float hexLine = stAaStroke(hex.z, 0.036) * (0.28 + 0.72 * scanWave);
            float ringMain = stDashedRing(rel.xz, maxR * 0.62, 0.08, 36.0, StTime * 0.25);
            float ringInner = stDashedRing(rel.xz, maxR * 0.30, 0.06, 18.0, -StTime * 0.40);
            float circuits = stCircuitTraces(projUv * 0.65, StTime) * smoothstep(maxR * 0.20, maxR * 0.65, horizDist);

            // 锚点旁水平地面刻蚀七段数码管光年读数（XX.XX ly）
            float readout = 0.0;
            if (an.y > 0.65) {
                vec2 rUv = (rel.xz - vec2(0.0, -2.2)) * 1.15;
                readout = stReadoutXX_XX(rUv, SceneParams.w);
                readout = max(readout, stUnitLy(rUv - vec2(2.75, 0.0)));
            }

            vec3 cyanLin = vec3(0.012, 0.88, 1.00);
            vec3 violetLin = vec3(0.24, 0.10, 1.00);
            vec3 starWhite = vec3(0.92, 0.98, 1.00);

            vec3 hudCol = cyanLin * (hexLine * 0.65 + ringMain * 1.45 + circuits * 0.55)
                + violetLin * (ringInner * 1.25)
                + starWhite * (readout * 1.85 + scanWave * hexLine * 0.90);

            // 轻度压暗结界内地面底色以突出全息遥测对比度
            worldCol *= mix(1.0, 0.68, radialEnv * 0.75);
            worldCol += hudCol * radialEnv;
        }
    }

    // 爱因斯坦微虫洞物理黑体蓝白锐边（同步校验阶段 4 的 Sampler2 黑体 LUT 绑定）
    if (lensRingGlow > 0.002) {
        vec3 einsteinCol = stSampleBlackbodyLut(Sampler2, 16500.0);
        worldCol += einsteinCol * (lensRingGlow * 1.65 * env);
    }

    fragColor = vec4(stSanitize(worldCol), 1.0);
}
