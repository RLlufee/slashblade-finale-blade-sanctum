#version 150

#moj_import <annihilationblade:st_common.glsl>
#moj_import <annihilationblade:st_optics.glsl>

// 星穹引擎两阶段电影合成 Pass（Step 1.6 终极升格版）：
//  1. dFdx/dFdy 屏幕斜坡自适应双边升采样 + 1:1 满分辨率 texelFetch 短路（彻底消除近距斜坡锯齿与远距细柱透墙）；
//  2. Heavy MRT × 3D 物理浮雕法阵真实前后深度互穿裁切（立体星穹法阵可前后穿插环绕黑洞视界）；
//  3. Stage 4 SceneEmit 正式汇入纯自发光管线并经由 Troy Sobotka AgX 电影级局部保真色调映射输出；
//  4. 完整预合成支持（Hold-Frame 残像直接捕获含 Body/浮雕/泛光/星芒/体积光在内的全层视觉烙印）；
//  5. 精确 IEC sRGB + 单色蓝噪声三角抖动（守住绝对纯黑黑位、零彩噪）。
uniform sampler2D Sampler0;  // Mode 0/1: 线性场景 | Mode 2: 预合成线性 HDR 画面
uniform sampler2D Sampler1;  // Mode 0/1: HDR 几何 + TBO 粒子/丝带 | Mode 2: 半分辨率 Hold-Frame 残像
uniform sampler2D Sampler2;  // Mode 0/1: Heavy MRT Attachment 0 (世界替换/折射 + alpha)
uniform sampler2D Sampler3;  // Mode 0/1: Heavy MRT Attachment 1 (NEAREST 深度 + 辅助)
uniform sampler2D Sampler4;  // Mode 0/1: Heavy MRT Attachment 2 (纯自发光)
uniform sampler2D Sampler5;  // Mode 0/1: 6 级双频泛光
uniform sampler2D Sampler6;  // Mode 0/1: 1/4 分辨率电影变形水平星芒
uniform sampler2D Sampler7;  // Mode 0/1: 深度+浮雕遮挡径向体积光
uniform sampler2D Sampler8;  // Mode 0/1: 线性预乘 Alpha 暗部本体层
uniform sampler2D Sampler9;  // Mode 0/1: 1x1 全 GPU 自动曝光 (r=exposure)
uniform sampler2D Sampler10; // Mode 0/1: R32F 场景深度
uniform sampler2D Sampler11; // 64x64 蓝噪声

// 额外高槽位采样器（由 Java 端在 extraGlBinder 中绑定于 GL_TEXTURE12..14，严格小于 GL 3.2 最低保证 16 单元）
uniform sampler2D SceneEmitTex;   // GL_TEXTURE12: Stage 4 场景改写纯自发光层
uniform sampler2D ReliefColorTex; // GL_TEXTURE13: 3D 物理浮雕法阵 HDR 辐射层
uniform sampler2D ReliefMetaTex;  // GL_TEXTURE14: 3D 物理浮雕法阵 RGBA32F ReliefMeta (r=命中距离, g=透射率)

uniform mat4 InvProj;
uniform vec2 OutSize;
uniform vec4 FrameMeta;         // x=frameIdx
uniform vec2 HeavySize;         // Heavy MRT 动态分辨率活跃尺寸
uniform vec2 HeavyUvMax;        // Heavy MRT 有效 UV 上限
uniform vec2 ReliefSize;        // ReliefPass 动态分辨率活跃尺寸
uniform vec2 ReliefUvMax;       // ReliefPass 有效 UV 上限
uniform vec4 LayerWeights;      // x=bodyW, y=geoW, z=heavyW, w=detailPreservation
uniform vec4 ExtraLayerWeights; // x=reliefW, y=sceneEmitW, z=0, w=0
uniform vec4 OpticsWeights;     // x=bloomStrength, y=streakStrength, z=godRayW, w=autoExposureW
uniform vec4 StreakTint;        // rgb=星芒冷色偏
uniform vec4 HoldState;         // rgb=残像色调, a=残像当前强度
uniform vec4 Flash;             // rgb=爆闪色, a=强度
uniform vec4 Vignette;          // rgb=暗角色, a=强度
uniform vec4 PostParams;        // x=worldDimming, y=desaturate, z=aberration, w=zoomBlur
uniform vec2 Center;            // 径向效果中心 UV
uniform vec4 ImpactMeta;        // x=强度, y=是否反相(>0.5), z=描边权重, w=半调网点权重
uniform vec4 ImpactFg;          // rgb=前景色, a=水平撕裂偏移量
uniform vec4 ImpactBg;          // rgb=背景色, a=拍次编号
uniform float PostMode;         // 0=单趟直出, 1=HDR线性预合成, 2=单纹理多tap后处理+冲击帧

in vec2 texCoord;

out vec4 fragColor;

// 屏幕斜坡自适应 2x2 同步双边升采样 Heavy MRT（含 1:1 满分辨率 texelFetch 短路）
void sampleHeavyMrtPair(
    vec2 uv,
    float fullSceneT,
    float depthSlope,
    out vec4 outWorldLayer,
    out vec3 outEmissiveLayer,
    out vec4 outAux
) {
    vec2 safeActive = max(HeavySize, vec2(2.0));
    vec2 safeUvMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
    float pixelScale = max(OutSize.x / safeActive.x, OutSize.y / safeActive.y);
    float tolerance = max(0.25, max(fullSceneT * 0.005, min(depthSlope * pixelScale * 2.0, fullSceneT * 0.035)));

    // 1:1 满分辨率短路：直接单点 texelFetch，砍掉 75% 带宽并保住像素级锐度
    if (abs(safeActive.x - OutSize.x) < 1.5 && abs(safeActive.y - OutSize.y) < 1.5) {
        ivec2 dim = textureSize(Sampler2, 0);
        ivec2 p = clamp(ivec2(stSaturate2(uv) * safeActive), ivec2(0), dim - ivec2(1));
        vec4 a = stSanitize4(texelFetch(Sampler3, p, 0));
        if (a.r < 5.0e5 && (a.r - fullSceneT) > tolerance * 2.0) {
            outWorldLayer = vec4(0.0);
            outEmissiveLayer = vec3(0.0);
            outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
            return;
        }
        outWorldLayer = stSanitize4(texelFetch(Sampler2, p, 0));
        outEmissiveLayer = max(stSanitize(texelFetch(Sampler4, p, 0).rgb), vec3(0.0));
        outAux = a;
        return;
    }

    vec2 invTex = safeUvMax / safeActive;
    vec2 minUv = invTex * 0.5;
    vec2 maxUv = max(minUv, safeUvMax - invTex * 0.5);

    vec2 fCoord = stSaturate2(uv) * safeActive - 0.5;
    vec2 base = floor(fCoord);
    vec2 f = stSaturate2(fCoord - base);

    vec2 uv00 = clamp((base + vec2(0.5, 0.5)) * invTex, minUv, maxUv);
    vec2 uv10 = clamp((base + vec2(1.5, 0.5)) * invTex, minUv, maxUv);
    vec2 uv01 = clamp((base + vec2(0.5, 1.5)) * invTex, minUv, maxUv);
    vec2 uv11 = clamp((base + vec2(1.5, 1.5)) * invTex, minUv, maxUv);

    vec4 a00 = stSanitize4(texture(Sampler3, uv00));
    vec4 a10 = stSanitize4(texture(Sampler3, uv10));
    vec4 a01 = stSanitize4(texture(Sampler3, uv01));
    vec4 a11 = stSanitize4(texture(Sampler3, uv11));

    float d00 = a00.r - fullSceneT;
    float d10 = a10.r - fullSceneT;
    float d01 = a01.r - fullSceneT;
    float d11 = a11.r - fullSceneT;

    float v00 = (a00.r >= 5.0e5) ? 1.0 : ((d00 > tolerance * 2.0) ? 0.0 : exp(-max(d00, 0.0) / tolerance));
    float v10 = (a10.r >= 5.0e5) ? 1.0 : ((d10 > tolerance * 2.0) ? 0.0 : exp(-max(d10, 0.0) / tolerance));
    float v01 = (a01.r >= 5.0e5) ? 1.0 : ((d01 > tolerance * 2.0) ? 0.0 : exp(-max(d01, 0.0) / tolerance));
    float v11 = (a11.r >= 5.0e5) ? 1.0 : ((d11 > tolerance * 2.0) ? 0.0 : exp(-max(d11, 0.0) / tolerance));

    float w00 = (1.0 - f.x) * (1.0 - f.y) * v00;
    float w10 = f.x * (1.0 - f.y) * v10;
    float w01 = (1.0 - f.x) * f.y * v01;
    float w11 = f.x * f.y * v11;
    float wSum = w00 + w10 + w01 + w11;

    if (wSum < 1.0e-4) {
        outWorldLayer = vec4(0.0);
        outEmissiveLayer = vec3(0.0);
        outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
        return;
    }

    vec4 c00 = stSanitize4(texture(Sampler2, uv00));
    vec4 c10 = stSanitize4(texture(Sampler2, uv10));
    vec4 c01 = stSanitize4(texture(Sampler2, uv01));
    vec4 c11 = stSanitize4(texture(Sampler2, uv11));

    vec3 e00 = max(stSanitize(texture(Sampler4, uv00).rgb), vec3(0.0));
    vec3 e10 = max(stSanitize(texture(Sampler4, uv10).rgb), vec3(0.0));
    vec3 e01 = max(stSanitize(texture(Sampler4, uv01).rgb), vec3(0.0));
    vec3 e11 = max(stSanitize(texture(Sampler4, uv11).rgb), vec3(0.0));

    float invW = 1.0 / wSum;
    float edgeSoft = smoothstep(0.02, 0.25, wSum);
    outWorldLayer = ((c00 * w00 + c10 * w10 + c01 * w01 + c11 * w11) * invW) * edgeSoft;
    outEmissiveLayer = ((e00 * w00 + e10 * w10 + e01 * w01 + e11 * w11) * invW) * edgeSoft;
    outAux = (a00 * w00 + a10 * w10 + a01 * w01 + a11 * w11) * invW;
}

// 阶段 1：单点完整线性 HDR 合成（严格分离世界漫反射/折射层 与 纯自发光层，支持 Heavy × 3D 浮雕真实前后互穿）
vec3 evaluatePrimaryComposite(vec2 uv) {
    vec2 suv = clamp(uv, vec2(0.0005), vec2(0.9995));
    vec3 baseLin = max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
    float fullDepth = texture(Sampler10, suv).r;
    float fullSceneT = stSceneDistance(fullDepth, suv, InvProj);
    // 严格在一致控制流中预先计算屏幕深度斜坡导数，彻底无 GLSL UB
    float depthSlope = abs(dFdx(fullSceneT)) + abs(dFdy(fullSceneT));

    // 1. 世界压暗（夺夜/轨道炮高对比氛围）
    vec3 world = baseLin * (1.0 - PostParams.x * 0.72);

    // 2. 线性预乘 Alpha 暗部本体层（不进泛光，先于发光压暗背景）
    if (LayerWeights.x > 0.5) {
        vec4 body = stSanitize4(texture(Sampler8, suv));
        float bodyA = stSaturate(body.a);
        world = world * (1.0 - bodyA) + max(body.rgb, vec3(0.0));
    }

    // 3. 同步取样 Heavy MRT 与 3D 物理浮雕法阵，按真实命中距离决定前后遮挡与互穿顺序
    vec3 emitLin = vec3(0.0);

    bool useHeavy = LayerWeights.z > 0.5;
    vec4 heavyWorld = vec4(0.0);
    vec3 heavyEmit = vec3(0.0);
    vec4 heavyAux = vec4(1.0e6, 1.0, 0.0, 0.0);
    if (useHeavy) {
        sampleHeavyMrtPair(suv, fullSceneT, depthSlope, heavyWorld, heavyEmit, heavyAux);
        if (LayerWeights.w > 0.01 && heavyAux.a > 0.01 && heavyWorld.a > 0.01 && fullDepth < 0.9999 && abs(heavyAux.r - fullSceneT) < 1.2) {
            vec2 stepLow = 1.0 / max(HeavySize, vec2(2.0));
            vec3 blurWorld = (
                texture(Sampler0, clamp(suv + vec2(stepLow.x, 0.0), vec2(0.0005), vec2(0.9995))).rgb +
                texture(Sampler0, clamp(suv - vec2(stepLow.x, 0.0), vec2(0.0005), vec2(0.9995))).rgb +
                texture(Sampler0, clamp(suv + vec2(0.0, stepLow.y), vec2(0.0005), vec2(0.9995))).rgb +
                texture(Sampler0, clamp(suv - vec2(0.0, stepLow.y), vec2(0.0005), vec2(0.9995))).rgb
            ) * 0.25;
            vec3 detailRatio = clamp((baseLin + 0.04) / (max(stSanitize(blurWorld), vec3(0.0)) + 0.04), vec3(0.72), vec3(1.42));
            heavyWorld.rgb = max(heavyWorld.rgb, vec3(0.0)) * mix(vec3(1.0), detailRatio, LayerWeights.w * heavyAux.a);
        }
    }

    bool useRelief = ExtraLayerWeights.x > 0.5;
    vec4 reliefCol = vec4(0.0);
    vec4 reliefAux = vec4(1.0e6, 1.0, 0.0, 0.0);
    if (useRelief) {
        reliefCol = stBilateralUpsampleSlope(
            ReliefColorTex, ReliefMetaTex, suv,
            ReliefSize, OutSize, ReliefUvMax,
            fullSceneT, depthSlope, reliefAux
        );
    }

    float heavyA = stSaturate(heavyWorld.a);
    vec3 heavyWorldRgb = max(heavyWorld.rgb, vec3(0.0));
    float reliefA = stSaturate(reliefCol.a);
    vec3 reliefEmit = max(reliefCol.rgb, vec3(0.0));

    // 前后真深度互穿：立体浮雕环在黑洞前方时遮挡黑洞、在黑洞背后时被事件视界吞没
    bool reliefInFront = reliefA > 0.002 && (reliefAux.r < heavyAux.r);
    if (reliefInFront) {
        if (useHeavy) {
            world = world * (1.0 - heavyA) + heavyWorldRgb;
            emitLin += heavyEmit * (1.0 - reliefA);
        }
        world *= (1.0 - reliefA);
        emitLin += reliefEmit;
    } else {
        if (reliefA > 0.002) {
            world *= (1.0 - reliefA);
            emitLin += reliefEmit * (1.0 - heavyA);
        }
        if (useHeavy) {
            world = world * (1.0 - heavyA) + heavyWorldRgb;
            emitLin += heavyEmit;
        }
    }

    // 4. 全分辨率线性 HDR 几何层 + TBO GPU 实例化粒子与 3D 能量丝带
    if (LayerWeights.y > 0.5) {
        emitLin += max(stSanitize(texture(Sampler1, suv).rgb), vec3(0.0));
    }

    // 5. Stage 4 场景改写纯自发光（亮星 / 极光 / 遥测数字 / 熔融裂纹 / 虫洞环，正式汇入 AgX 泄白）
    if (ExtraLayerWeights.y > 0.5) {
        emitLin += max(stSanitize(texture(SceneEmitTex, suv).rgb), vec3(0.0));
    }

    // 6. 6 级能量守恒双频泛光
    if (OpticsWeights.x > 0.001) {
        vec3 bloom = max(stSanitize(texture(Sampler5, suv).rgb), vec3(0.0));
        emitLin += bloom * (OpticsWeights.x * 0.44);
    }

    // 7. 1/4 分辨率无串珠电影级变形水平星芒
    if (OpticsWeights.y > 0.001) {
        vec3 streak = max(stSanitize(texture(Sampler6, suv).rgb), vec3(0.0));
        emitLin += streak * StreakTint.rgb * (OpticsWeights.y * 0.48);
    }

    // 8. 深度+浮雕遮挡径向体积光（耶稣光）
    if (OpticsWeights.z > 0.5) {
        vec3 rays = max(stSanitize(texture(Sampler7, suv).rgb), vec3(0.0));
        emitLin += rays * 0.72;
    }

    // 9. 全镜头异步自动曝光（常态严格 1.0，爆发时平滑压暗衬托主体）
    float exposure = 1.0;
    if (OpticsWeights.w > 0.001) {
        float gpuExp = texture(Sampler9, vec2(0.5)).r;
        if (!isnan(gpuExp) && !isinf(gpuExp) && gpuExp > 0.15) {
            exposure = mix(1.0, clamp(gpuExp, 0.25, 1.0), OpticsWeights.w);
        }
    }

    // 10. 局部恒等保真 × Troy Sobotka AgX 电影能量合成
    return stCompositeHdrEnergy(world, emitLin, exposure);
}

// 感知 sRGB 亮度采样（专供阶段 2 动漫冲击帧 Sobel + 自适应阈值使用，完整保留 Body 与全部光效轮廓）
float samplePerceptualLuma(vec2 uv) {
    vec2 suv = clamp(uv, vec2(0.0005), vec2(0.9995));
    vec3 lin = max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
    return stLuma(stLinearToSrgb(lin));
}

void main() {
    vec2 uv = texCoord;

    // 阶段 1：单次预合成输出到闲置 RGBA16F 场景目标，供 Hold-Frame 捕获与阶段 2 多 tap 后处理复用
    if (PostMode > 0.5 && PostMode < 1.5) {
        fragColor = vec4(stSanitize(evaluatePrimaryComposite(uv)), 1.0);
        return;
    }

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 safeCenter = clamp(Center, vec2(-0.35), vec2(1.35));
    vec2 fromCenter = uv - safeCenter;
    float fcLen = length(fromCenter);
    if (fcLen > 1.4) {
        fromCenter *= 1.4 / fcLen;
    }
    float radialDist = length(fromCenter * vec2(aspect, 1.0));

    vec3 colorLin;
    if (PostMode < 0.5) {
        colorLin = evaluatePrimaryComposite(uv);
    } else {
        if (PostParams.w > 0.001) {
            vec3 sum = vec3(0.0);
            float wSum = 0.0;
            for (int i = 0; i < 8; i++) {
                float k = float(i) / 7.0;
                float w = 1.0 - k * 0.55;
                vec2 sUv = clamp(uv - fromCenter * PostParams.w * 0.095 * k, vec2(0.0005), vec2(0.9995));
                sum += max(stSanitize(texture(Sampler0, sUv).rgb), vec3(0.0)) * w;
                wSum += w;
            }
            colorLin = sum / wSum;
        } else {
            colorLin = max(stSanitize(texture(Sampler0, uv).rgb), vec3(0.0));
        }

        // 非对称径向 RGB 色差
        if (PostParams.z > 0.001) {
            vec2 shift = fromCenter * PostParams.z * 0.010;
            vec2 uvR = clamp(uv + shift * 1.08, vec2(0.0005), vec2(0.9995));
            vec2 uvB = clamp(uv - shift * 0.92, vec2(0.0005), vec2(0.9995));
            vec3 aberrated = vec3(
                max(stSanitize(texture(Sampler0, uvR).rgb).r, 0.0),
                colorLin.g,
                max(stSanitize(texture(Sampler0, uvB).rgb).b, 0.0)
            );
            colorLin = mix(colorLin, aberrated, stSaturate(PostParams.z * 0.85));
        }

        // Hold-Frame 视网膜闪盲残像（含完整 Body/3D浮雕/泛光/星芒/体积光烙印）
        if (HoldState.a > 0.003) {
            vec2 hPx = 2.4 / max(OutSize, vec2(1.0));
            vec3 held = (
                texture(Sampler1, uv).rgb * 0.36 +
                texture(Sampler1, clamp(uv + vec2( hPx.x,  hPx.y), vec2(0.001), vec2(0.999))).rgb * 0.16 +
                texture(Sampler1, clamp(uv + vec2(-hPx.x,  hPx.y), vec2(0.001), vec2(0.999))).rgb * 0.16 +
                texture(Sampler1, clamp(uv + vec2( hPx.x, -hPx.y), vec2(0.001), vec2(0.999))).rgb * 0.16 +
                texture(Sampler1, clamp(uv + vec2(-hPx.x, -hPx.y), vec2(0.001), vec2(0.999))).rgb * 0.16
            );
            held = max(stSanitize(held), vec3(0.0));
            float hLuma = stLuma(held);
            float mask = smoothstep(0.24, 1.25, hLuma);
            vec3 chroma = (hLuma > 1.0e-4) ? (held / max(hLuma, 0.35)) : vec3(1.0);
            vec3 ghostCol = stSaturate3(mix(chroma, HoldState.rgb, 0.35) * (mask * HoldState.a * 0.54));
            colorLin = vec3(1.0) - (vec3(1.0) - stSaturate3(colorLin)) * (vec3(1.0) - ghostCol);
        }
    }

    vec3 color = stLinearToSrgb(colorLin);

    if (PostParams.y > 0.001) {
        float luma = stLuma(color);
        color = mix(color, vec3(luma) * vec3(0.88, 0.94, 1.02), PostParams.y);
    }

    if (Vignette.a > 0.001) {
        float v = smoothstep(0.28, 1.02, length((uv - 0.5) * vec2(aspect, 1.0)) * 1.12);
        color = mix(color, Vignette.rgb, v * Vignette.a);
    }

    if (Flash.a > 0.001) {
        float core = 1.0 - smoothstep(0.0, 0.95, radialDist * 1.15);
        float glare = Flash.a * (0.72 + core * 0.48);
        color = mix(color + Flash.rgb * glare * 0.58, Flash.rgb, clamp(glare * 0.45, 0.0, 0.84));
    }

    // 12fps 动漫级多风格冲击帧（解析抗锯齿半调网点，无分支 fwidth）
    if (PostMode > 1.5 && ImpactMeta.x > 0.001) {
        float beatId = ImpactBg.a;
        float bandHash = stHash11(floor(uv.y * 14.0) + beatId * 19.37);
        float tearShift = (bandHash - 0.5) * 2.0 * ImpactFg.a * step(0.68, bandHash);
        vec2 iUv = clamp(vec2(uv.x + tearShift, uv.y), vec2(0.001), vec2(0.999));

        vec2 px = 1.4 / max(OutSize, vec2(1.0));
        float tl = samplePerceptualLuma(iUv + vec2(-px.x,  px.y));
        float tc = samplePerceptualLuma(iUv + vec2(  0.0,  px.y));
        float tr = samplePerceptualLuma(iUv + vec2( px.x,  px.y));
        float ml = samplePerceptualLuma(iUv + vec2(-px.x,   0.0));
        float mc = samplePerceptualLuma(iUv);
        float mr = samplePerceptualLuma(iUv + vec2( px.x,   0.0));
        float bl = samplePerceptualLuma(iUv + vec2(-px.x, -px.y));
        float bc = samplePerceptualLuma(iUv + vec2(  0.0, -px.y));
        float br = samplePerceptualLuma(iUv + vec2( px.x, -px.y));

        float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
        float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
        float sobel = sqrt(gx * gx + gy * gy) * ImpactMeta.z;

        float localMean = (tl + tc + tr + ml + mr + bl + bc + br) * 0.125;
        float relTone = stSaturate(mc * 0.72 + (mc - localMean) * 1.85 + 0.12);
        float adaptive = smoothstep(0.36, 0.58, relTone);

        float cellFreq = 165.0 / max(OutSize.y, 1.0);
        vec2 rotCoord = stRot2(0.78539816) * (gl_FragCoord.xy * cellFreq);
        float dotDist = length(fract(rotCoord) - 0.5);
        float dotRadius = sqrt(stSaturate(relTone)) * 0.54;
        float dotAa = clamp(cellFreq * 1.15, 0.015, 0.08);
        float halftone = 1.0 - smoothstep(dotRadius - dotAa, dotRadius + dotAa, dotDist);

        float ink = stSaturate(mix(adaptive, halftone, ImpactMeta.w * 0.65));
        ink = max(ink, smoothstep(0.24, 0.62, sobel));
        if (ImpactMeta.y > 0.5) {
            ink = 1.0 - ink;
        }
        vec3 impactColor = mix(ImpactBg.rgb, ImpactFg.rgb, ink);
        color = mix(color, impactColor, ImpactMeta.x);
    }

    vec3 finalSrgb = stDither8Blue(stSanitize(color), Sampler11, gl_FragCoord.xy, FrameMeta.x);
    fragColor = vec4(finalSrgb, 1.0);
}
