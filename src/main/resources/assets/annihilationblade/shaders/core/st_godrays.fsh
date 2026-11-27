#version 150

#moj_import <annihilationblade:st_common.glsl>

// 自发光专属径向体积光（Step 1.6 升级版：连续世界深度 + 3D 浮雕法阵遮挡耶稣光）：
//  - 优先采用半分辨率已抗闪烁预滤波的 bloomDown[0]（涵盖 4 大自发光源），或回退多源直采；
//  - 步进时按光源真实世界距离与 R32F 地形深度 / RGBA32F ReliefMeta 做连续平滑遮挡（非二值天空硬切），
//    让山脊、立柱与立体法阵环孔自然切出震撼的电影级丁达尔光束，且零天空交界闪跳！
//  - 衰减严格按归一化步进进度 exp(-t * density) 积分，确保跨画质档位亮度与光束长度完全一致。
uniform sampler2D Sampler0; // 预滤波自发光（bloomDown[0]）或 hdrGeo
uniform sampler2D Sampler1; // Heavy MRT Attachment 2 纯自发光（回退直采用）
uniform sampler2D Sampler2; // 64x64 蓝噪声
uniform sampler2D Sampler3; // 共享不透明 R32F 场景深度
uniform sampler2D Sampler4; // 3D 浮雕法阵 RGBA32F ReliefMeta（r=命中距离, g=透射率）
uniform sampler2D Sampler5; // 3D 浮雕法阵 HDR 辐射层（回退直采用）
uniform sampler2D Sampler6; // Stage 4 SceneEmit 纯自发光层（回退直采用）

uniform mat4 InvProj;
uniform vec2 OutSize;
uniform vec2 HeavyUvMax;
uniform vec2 ReliefUvMax;
uniform vec4 Meta;            // x=步数, y=frameIdx, z=usePrefiltered(>0.5), w=hasRelief(>0.5)
uniform vec4 FallbackWeights; // x=hdrGeo, y=heavyEmit, z=reliefColor, w=sceneEmit
uniform vec4 Source0;         // xy=屏幕UV, z=强度, w=衰减系数
uniform vec4 Color0;          // rgb=色调, a=光源0世界距离
uniform vec4 Source1;
uniform vec4 Color1;          // rgb=色调, a=光源1世界距离

in vec2 texCoord;

out vec4 fragColor;

vec3 sampleEmissive(vec2 uv) {
    vec2 suv = clamp(uv, vec2(0.0005), vec2(0.9995));
    vec3 c = vec3(0.0);
    if (Meta.z > 0.5) {
        c = max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
    } else {
        if (FallbackWeights.x > 0.5) {
            c += max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
        }
        if (FallbackWeights.y > 0.5) {
            vec2 safeMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
            vec2 hUv = clamp(suv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.001)));
            c += max(stSanitize(texture(Sampler1, hUv).rgb), vec3(0.0));
        }
        if (FallbackWeights.z > 0.5) {
            vec2 safeR = clamp(ReliefUvMax, vec2(0.05), vec2(1.0));
            vec2 rUv = clamp(suv * safeR, vec2(0.0005), max(vec2(0.0005), safeR - vec2(0.001)));
            c += max(stSanitize(texture(Sampler5, rUv).rgb), vec3(0.0));
        }
        if (FallbackWeights.w > 0.5) {
            c += max(stSanitize(texture(Sampler6, suv).rgb), vec3(0.0));
        }
    }
    float peak = max(max(c.r, c.g), c.b);
    if (peak > 8.0) {
        c *= 8.0 / peak;
    }
    return c;
}

float sampleOcclusion(vec2 uv, float srcDist, bool hasRelief) {
    vec2 suv = clamp(uv, vec2(0.0005), vec2(0.9995));
    float sceneD = texture(Sampler3, suv).r;
    float sceneT = stSceneDistance(sceneD, suv, InvProj);
    // 连续平滑深度遮挡：地形/山峰距离显著小于光源距离时阻挡光束，切出真实耶稣光暗影缝，无天空边界跳变
    float margin = clamp(srcDist * 0.08, 0.75, 4.2);
    float worldVis = smoothstep(srcDist - margin, srcDist + margin * 0.35, sceneT);

    float reliefVis = 1.0;
    if (hasRelief) {
        vec2 safeR = clamp(ReliefUvMax, vec2(0.05), vec2(1.0));
        vec2 rUv = clamp(suv * safeR, vec2(0.0005), max(vec2(0.0005), safeR - vec2(0.0005)));
        vec4 rMeta = texture(Sampler4, rUv);
        if (rMeta.r < 5.0e5) {
            // 仅当光源位于 3D 浮雕管壁后方时，管壁实心体对光束投影出立体放射暗隙
            float behindRing = smoothstep(rMeta.r + 0.25, rMeta.r + 1.20, srcDist);
            reliefVis = mix(1.0, stSaturate(rMeta.g), behindRing);
        }
    }
    return worldVis * reliefVis;
}

vec3 traceRaySource(vec4 src, vec4 colMeta, int steps, float jitter, bool hasRelief) {
    if (src.z <= 0.001) {
        return vec3(0.0);
    }
    vec2 delta = src.xy - texCoord;
    float dist = length(delta);
    float density = -log(clamp(src.w, 0.80, 0.995)) * 24.0;
    float span = min(dist, 0.96);
    vec2 rayDir = delta / max(dist, 1.0e-4);
    float invSteps = 1.0 / float(steps);
    float srcDist = max(colMeta.a, 2.0);

    vec3 sum = vec3(0.0);
    float wSum = 0.0;
    for (int i = 0; i < 40; i++) {
        if (i >= steps) {
            break;
        }
        float t = (float(i) + jitter) * invSteps;
        vec2 sampleUv = texCoord + rayDir * (span * t);
        if (sampleUv.x < 0.0 || sampleUv.x > 1.0 || sampleUv.y < 0.0 || sampleUv.y > 1.0) {
            break;
        }
        float w = exp(-t * density);
        float occ = sampleOcclusion(sampleUv, srcDist, hasRelief);
        // 保持分母 wSum 不受 occ 影响，使被遮挡区域真正沉出深邃丁达尔暗影柱
        sum += sampleEmissive(sampleUv) * (occ * w);
        wSum += w;
    }

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    float radialDist = length(delta * vec2(aspect, 1.0));
    float radialMask = 1.0 - smoothstep(0.16, 1.30, radialDist);
    vec3 avg = sum / max(wSum, 1.0e-3);
    return avg * colMeta.rgb * (src.z * (0.30 + 0.70 * radialMask));
}

void main() {
    int steps = clamp(int(Meta.x + 0.5), 8, 40);
    bool hasRelief = Meta.w > 0.5;
    vec4 bn = stBlueNoise(Sampler2, gl_FragCoord.xy, Meta.y);
    vec3 rays = traceRaySource(Source0, Color0, steps, bn.r, hasRelief);
    if (Source1.z > 0.001) {
        rays += traceRaySource(Source1, Color1, max(8, steps / 2), bn.g, hasRelief);
    }
    fragColor = vec4(stSanitize(rays), 1.0);
}
