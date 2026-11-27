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

// Step 1.6 精修版 3 附件重型 MRT 史瓦西黑洞着色器（2D 共面轨道平面精确 Binet RK4）：
//  - 强引力透镜升级：外包络扩至 4.5R + 远场偏折补全 + 零双重曝光实心折射 + 微弱光子环色散，
//    让天空、云海、山脊与岸线围绕奇点呈现深邃丝滑的 360° 爱因斯坦弯曲；
//  - 平滑诞生与坍缩蒸发：由 CoreParams.w (fadeEnv 0..1) 同步平滑驱动事件视界透明度、折射强度与吸积盘辐射，
//    配合 CPU 端半径收缩，彻底消除末帧生硬突兀消失！
uniform sampler2D Sampler0; // 线性 HDR 场景
uniform sampler2D Sampler1; // R32F 共享不透明场景深度
uniform sampler2D Sampler2; // 256x1 RGBA16F 普朗克×CIE 物理黑体 LUT
uniform sampler2D Sampler3; // 64x64 Void-and-Cluster 蓝噪声
uniform sampler3D Noise3D;  // 64^3 3D 体积噪声（A 通道为真无散度 3D Curl 旋度）
uniform sampler2D ReliefMetaTex; // GL_TEXTURE14: 3D 物理浮雕法阵 ReliefMeta

uniform mat4 InvProj;
uniform mat4 InvView;
uniform mat4 ViewProj;
uniform float StTime;
uniform vec2 OutSize;
uniform vec4 FrameMeta;     // x=frameIdx, y=bhSteps(120/220/400)
uniform vec4 CoreSphere;    // xyz=黑洞中心(相对相机), w=当前动态缩放后基准半径 R
uniform vec4 CoreParams;    // x=能量倍率, y=吸积盘倾角, z=引力透镜强度倍率(>0), w=平滑出生/坍缩包络 fadeEnv(0..1)
uniform vec4 ReliefParams;  // xy=reliefUvMax, z=hasRelief(>0.5), w=0

in vec2 texCoord;

// 采样史瓦西倾斜吸积盘单次穿越的相对论多普勒黑体辐射度（含引力红移与 3D Curl 等离子涡丝）
vec4 evalAccretionDiskCrossing(
    vec3 hitRelCenter,
    vec3 rayDirAtDisk,
    vec3 ringU,
    vec3 ringV,
    float rs,
    float innerR,
    float outerR,
    float energy,
    int orderIndex
) {
    float r = length(hitRelCenter);
    if (r <= innerR * 0.92 || r >= outerR) {
        return vec4(0.0);
    }
    float uNorm = stSaturate((r - innerR) / max(outerR - innerR, 1.0e-3));
    float ang = stSafeAtan(dot(hitRelCenter, ringV), dot(hitRelCenter, ringU));
    float rRatio = max(r / max(rs, 0.05), 2.5);
    float omega = StTime * (3.2 / stSafePow(rRatio * 0.32, 1.35));
    float spiralAng = ang - omega + log(max(rRatio, 1.2)) * 2.85;
    vec3 nCoord = vec3(cos(spiralAng) * 0.42 + 0.5, sin(spiralAng) * 0.42 + 0.5, uNorm * 1.45 + StTime * 0.07);
    vec4 n3 = texture(Noise3D, nCoord);
    vec4 nFine = texture(Noise3D, nCoord * 2.15 + vec3(0.27, -0.19, 0.35));

    float strands = smoothstep(0.18, 0.88, n3.r * 0.48 + n3.g * 0.32 + nFine.a * 0.20);
    float radialEnv = smoothstep(0.0, 0.14, uNorm) * (1.0 - smoothstep(0.56, 1.0, uNorm));

    // 开普勒切向轨道速度（近 ISCO 达 0.52c，清晰拉出左蓝白聚束/右炽金红移反差）
    float betaMag = clamp(sqrt(0.5 * rs / max(r - rs, rs * 0.55)), 0.06, 0.54);
    vec3 tangentDir = normalize(-sin(ang) * ringU + cos(ang) * ringV);
    float gDoppler = stDopplerFactor(tangentDir * betaMag, -rayDirAtDisk);
    float gGrav = stGravitationalRedshift(r, rs);
    float gTotal = clamp(gDoppler * gGrav, 0.28, 2.75);

    // 内热外冷物理黑体色温（ISCO ~ 18500 K 冰蓝白，中盘 ~ 5500 K 暖白金，外盘 ~ 2400 K 深橘金红）
    float baseTemp = mix(18500.0, 2400.0, stSafePow(uNorm, 0.65));
    vec3 bbCol = stSampleBlackbodyLut(Sampler2, baseTemp * gTotal);

    float orderGain = (orderIndex == 0) ? 1.0 : ((orderIndex == 1) ? 0.88 : 0.64);
    float boost = stSafePow(gTotal, 2.35) * radialEnv * (0.16 + 1.28 * strands * strands) * energy * 2.65 * orderGain;
    float alpha = stSaturate(radialEnv * (0.30 + 0.64 * strands) * (orderIndex == 0 ? 0.90 : 0.76));
    return vec4(bbCol * boost, alpha);
}

void main() {
    float fadeEnv = stSaturate(CoreParams.w);
    float R = max(CoreSphere.w, 0.05);
    float energy = max(CoreParams.x, 0.0);
    if (fadeEnv <= 0.001 || R <= 0.06) {
        outColor = vec4(0.0);
        outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
        outEmissive = vec4(0.0);
        return;
    }

    vec3 rd = stViewRay(texCoord, InvProj, InvView);
    float sceneDepth = texture(Sampler1, texCoord).r;
    float sceneT = stSceneDistance(sceneDepth, texCoord, InvProj);

    vec3 center = CoreSphere.xyz;
    float camDistCenter = length(center);
    float lensGain = max(CoreParams.z, 0.5);

    // 强化物理比例：rs = 0.295*R -> 临界光子阴影 b_c ≈ 0.766*R，ISCO = 3*rs ≈ 0.885*R，外吸积盘 = 2.65*R，广域透镜包络 = 4.45*R
    float rs = R * 0.295;
    float innerR = rs * 3.0;
    float outerR = R * 2.65;
    float escapeR = R * 4.45;

    // 1. 广域引力透镜包络球快速早退
    vec2 outerHit = stIntersectSphere(vec3(0.0), rd, center, escapeR);
    if (outerHit.y <= 0.0 || outerHit.x >= sceneT) {
        outColor = vec4(0.0);
        outAux = vec4(1.0e6, 1.0, 0.0, 0.0);
        outEmissive = vec4(0.0);
        return;
    }

    vec4 bn = stBlueNoise(Sampler3, gl_FragCoord.xy, FrameMeta.x);

    vec3 ringNormal = normalize(vec3(sin(CoreParams.y), cos(CoreParams.y), 0.26));
    vec3 ringU;
    vec3 ringV;
    stSafeOrthoBasis(ringNormal, ringU, ringV);

    // 2. 启动 2D 共面轨道平面精确 Binet 方程 RK4（严格守恒角动量，零步数漂移）
    vec3 relCam = -center;
    StBinetOrbit orb = stInitBinetOrbit(relCam, rd, rs);

    int maxSteps = clamp(int(FrameMeta.y * 0.30 + 44.0), 72, 128);
    float baseDphi = (FrameMeta.y > 260.0) ? 0.062 : ((FrameMeta.y > 160.0) ? 0.082 : 0.108);

    float n1 = dot(orb.e1, ringNormal);
    float n2 = dot(orb.e2, ringNormal);

    float u = orb.u;
    float du = orb.du;
    float phi = orb.phi;
    float hPrev = n1 * cos(phi) + n2 * sin(phi);

    bool captured = !orb.valid;
    bool hitForegroundTerrain = false;
    vec3 diskAccum = vec3(0.0);
    float diskTrans = 1.0;
    float bestHitT = 1.0e6;
    int diskCrossings = 0;

    float uHorizon = 1.0 / max(rs * 1.02, 1.0e-4);
    float uEscape = 1.0 / max(escapeR * 1.04, 0.5);

    if (!captured && orb.r0 > escapeR * 1.01 && outerHit.x > 0.0) {
        vec3 entryRel = (rd * outerHit.x) - center;
        StBinetOrbit entryOrb = stInitBinetOrbit(entryRel, rd, rs);
        if (!entryOrb.valid) {
            captured = true;
        } else {
            orb = entryOrb;
            n1 = dot(orb.e1, ringNormal);
            n2 = dot(orb.e2, ringNormal);
            u = orb.u;
            du = orb.du;
            phi = 0.0;
            hPrev = n1;
        }
    }

    if (!captured) {
        float jitterStep = 0.85 + 0.30 * bn.r;
        for (int step = 0; step < 128; step++) {
            if (step >= maxSteps) {
                break;
            }
            float dphi = stBinetAdaptiveDphi(u, rs, baseDphi) * (step == 0 ? jitterStep : 1.0);
            float uPrev = u;
            float duPrev = du;
            float phiPrev = phi;

            stBinetStep(u, du, rs, dphi);
            phi += dphi;

            if (u >= uHorizon) {
                captured = true;
                vec3 capPos = stBinetPos(orb, uHorizon, phi);
                bestHitT = min(bestHitT, length(capPos - relCam));
                break;
            }

            if (sceneDepth < 0.9999) {
                vec3 curPos = stBinetPos(orb, max(u, 1.0e-4), phi);
                float camDist = length(curPos - relCam);
                if (camDist >= sceneT) {
                    hitForegroundTerrain = true;
                    break;
                }
            }

            float hCur = n1 * cos(phi) + n2 * sin(phi);
            if ((hPrev <= 0.0 && hCur >= 0.0) || (hPrev >= 0.0 && hCur <= 0.0)) {
                float frac = abs(hPrev) / max(abs(hPrev) + abs(hCur), 1.0e-6);
                float uCross = mix(uPrev, u, frac);
                float duCross = mix(duPrev, du, frac);
                float phiCross = mix(phiPrev, phi, frac);
                if (uCross > 1.0e-4 && uCross < uHorizon) {
                    vec3 hitPos = stBinetPos(orb, uCross, phiCross);
                    float hitCamDist = length(hitPos - relCam);
                    if (hitCamDist < sceneT) {
                        vec3 hitDir = stBinetDir(orb, uCross, duCross, phiCross);
                        vec4 sampleDisk = evalAccretionDiskCrossing(hitPos, hitDir, ringU, ringV, rs, innerR, outerR, energy, diskCrossings);
                        if (sampleDisk.a > 0.002) {
                            diskAccum += sampleDisk.rgb * diskTrans;
                            diskTrans *= (1.0 - sampleDisk.a * 0.72);
                            bestHitT = min(bestHitT, hitCamDist);
                            diskCrossings++;
                        }
                    }
                }
            }
            hPrev = hCur;

            if (u <= uEscape && du < 0.0) {
                break;
            }
            if (phi > ST_TAU * 2.2) {
                captured = true;
                break;
            }
        }
    }

    // 3. 临界冲击参数 b_c = 2.598*rs 处的刀锋光子环与薄膜虹彩边缘
    float pixelWorldWidth = (camDistCenter / max(OutSize.y, 1.0)) * 1.40;
    float photonRing = stPhotonRingGlow(orb.b, rs, pixelWorldWidth);
    vec3 photonRingCol = vec3(0.0);
    if (photonRing > 0.003 && !hitForegroundTerrain && energy > 0.001) {
        vec3 irid = stThinFilmIridescence(stSaturate(abs(orb.b - 2.598076 * rs) / max(rs * 0.35, 0.05)), 480.0 + 130.0 * sin(StTime * 1.5));
        vec3 bbRing = stSampleBlackbodyLut(Sampler2, 14500.0);
        photonRingCol = mix(bbRing, irid, 0.34) * (photonRing * energy * 3.8);
        bestHitT = min(bestHitT, max(0.2, camDistCenter - rs * 2.5));
    }

    // 4. 体积星云日冕（仅包裹在吸积盘外侧，保持中心事件视界深邃漆黑！）
    vec2 coronaHit = stIntersectSphere(vec3(0.0), rd, center, R * 2.85);
    float tStart = max(0.0, coronaHit.x);
    float tEnd = min(coronaHit.y, min(sceneT, captured ? max(bestHitT, camDistCenter - rs * 2.5) : coronaHit.y));
    vec3 volRadiance = vec3(0.0);
    float volAlpha = 0.0;
    if (coronaHit.y > 0.0 && tEnd > tStart + 0.02 && energy > 0.001) {
        const int VSTEPS = 8;
        float stepLen = (tEnd - tStart) / float(VSTEPS);
        float t = tStart + stepLen * bn.g;
        for (int i = 0; i < VSTEPS; i++) {
            vec3 p = rd * t - center;
            float rNorm = length(p) / R;
            vec3 q = p / (R * 2.6) + vec3(StTime * 0.05, -StTime * 0.035, StTime * 0.045);
            vec4 n3 = texture(Noise3D, q);
            float qr = rNorm - 1.28;
            float shell = exp(-(qr * qr) * 3.0) * (n3.r * 0.50 + n3.g * 0.25 + n3.a * 0.25);
            float dens = shell * 0.16 * (stepLen / R);
            vec3 nCol = mix(vec3(0.015, 0.78, 1.0), vec3(0.32, 0.12, 1.0), n3.b);
            volRadiance += nCol * dens * energy * 0.95 * (1.0 - volAlpha);
            volAlpha = stSaturate(volAlpha + dens * 0.24);
            t += stepLen;
        }
        bestHitT = min(bestHitT, tStart);
    }

    // 5. 严格分离世界遮挡/强引力透镜折射层（Attachment 0）与纯自发光层（Attachment 2）
    vec3 worldLayerRgb = vec3(0.0);
    float worldLayerAlpha = 0.0;

    float horizonFade = smoothstep(0.0, 0.32, fadeEnv);
    float lensFade = smoothstep(0.0, 0.42, fadeEnv);

    if (captured && !hitForegroundTerrain) {
        // 绝对深渊事件视界：随 fadeEnv 平滑显影与蒸发，彻底杜绝末帧闪灭
        worldLayerRgb = vec3(0.0);
        worldLayerAlpha = horizonFade;
    } else if (!hitForegroundTerrain && lensFade > 0.002) {
        // 逃逸光线精确偏折 + 远场弱引力补全（消除包络外截断感）
        vec3 escPos = stBinetPos(orb, max(u, 1.0e-5), phi);
        vec3 outDir = stBinetDir(orb, max(u, 1.0e-5), du, phi);
        outDir = stFarDeflect(escPos, outDir, rs);

        // 在三维切向角上平滑放大广义相对论偏折，拉出更具震撼力的爱因斯坦引力井（零硬切边）
        vec3 baseDefl = outDir - rd;
        float deflMag = length(baseDefl);
        if (deflMag > 0.0005) {
            float envEdge = 1.0 - smoothstep(R * 1.15, escapeR * 0.98, orb.b);
            float boostedScale = lensGain * envEdge * lensFade;
            vec3 finalDir = normalize(rd + baseDefl * boostedScale);

            // 若大角度强偏折将光线弯折至相机侧后方（clipDefl.w <= 0.15），回退至近地平线最大有效偏转角
            vec4 clipDefl = ViewProj * vec4(finalDir * 64.0, 1.0);
            vec2 offset;
            if (clipDefl.w > 0.15) {
                offset = ((clipDefl.xy / clipDefl.w) * 0.5 + 0.5) - texCoord;
            } else {
                vec4 clipCenter = ViewProj * vec4(center, 1.0);
                vec2 centerUv = (clipCenter.w > 0.1) ? ((clipCenter.xy / clipCenter.w) * 0.5 + 0.5) : vec2(0.5);
                vec2 rad = texCoord - centerUv;
                float rLen = max(length(rad), 1.0e-4);
                offset = (rad / rLen) * (0.38 * envEdge * lensFade);
            }

            // 平滑径向长度软限幅（杜绝方形 clamp 折痕），并随外边界连续归零 -> 零双影鬼影、零外圆切缝！
            float oLen = length(offset);
            if (oLen > 0.0001) {
                float softLen = 0.44 * tanh(oLen / 0.44);
                offset *= (softLen / oLen);
            }

            // 前景保护仅针对黑洞核心前方的近景障碍物，避免下侧地形被误拦导致下半球扭曲变弱
            float protectT = max(0.15, camDistCenter - R * 1.15);
            vec2 uvG = stSafeRefractUvRelief(
                texCoord, offset, protectT,
                Sampler1, ReliefMetaTex, ReliefParams.xy, ReliefParams.z > 0.5, InvProj
            );

            // 强透镜区微弱引力棱镜色散（R/B 沿弯曲方向 ±3% 分离，赋予真实镜头与时空曲率质感）
            float dispWeight = smoothstep(0.015, 0.14, length(uvG - texCoord));
            vec2 uvR = stSafeRefractUvRelief(
                texCoord, offset * (1.0 + 0.035 * dispWeight), protectT,
                Sampler1, ReliefMetaTex, ReliefParams.xy, ReliefParams.z > 0.5, InvProj
            );
            vec2 uvB = stSafeRefractUvRelief(
                texCoord, offset * (1.0 - 0.035 * dispWeight), protectT,
                Sampler1, ReliefMetaTex, ReliefParams.xy, ReliefParams.z > 0.5, InvProj
            );

            vec3 lensedBg = vec3(
                max(stSanitize(texture(Sampler0, uvR).rgb).r, 0.0),
                max(stSanitize(texture(Sampler0, uvG).rgb).g, 0.0),
                max(stSanitize(texture(Sampler0, uvB).rgb).b, 0.0)
            );
            // 只要存在真实偏移即以 100% 实心折射取代底图（偏移本身已平滑归零），彻底根除 50% 双重曝光发虚！
            float realShift = length(uvG - texCoord);
            float solidAlpha = smoothstep(0.0003, 0.0038, realShift) * lensFade;
            worldLayerRgb = lensedBg * solidAlpha;
            worldLayerAlpha = solidAlpha;
            bestHitT = min(bestHitT, max(0.2, camDistCenter - rs * 2.0));
        }
    }

    // 被事件视界捕获像素压薄前景雾气，守住深渊中心纯黑反差
    vec3 emissiveLayer = diskAccum + photonRingCol + volRadiance * (captured ? 0.14 : 1.0);
    worldLayerAlpha = max(worldLayerAlpha, volAlpha * 0.12 * horizonFade);

    if (hitForegroundTerrain && diskCrossings == 0 && worldLayerAlpha <= 0.002) {
        bestHitT = 1.0e6;
    } else if (bestHitT > 5.0e5) {
        bestHitT = max(0.2, camDistCenter - rs * 2.0);
    }

    outColor = vec4(stSanitize(worldLayerRgb), stSaturate(worldLayerAlpha));
    outAux = vec4(bestHitT, 1.0 - stSaturate(worldLayerAlpha), stSaturate(stLuma(emissiveLayer) * 0.25), 0.0);
    outEmissive = vec4(stSanitize(emissiveLayer), 1.0);
}
