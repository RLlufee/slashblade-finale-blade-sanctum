#version 150

#moj_import <annihilationblade:st_common.glsl>

// 全 GPU 1x1 异步自动曝光（基准 = 1.0 电影防过曝压光型）：
//  - 开泛光时：同步测光线性场景 + 泛光最低级；
//  - 关泛光时：自动改测线性场景 + hdrGeo + MRT Attachment 2 纯自发光，确保开/关泛光压光曲线高度一致！
//  - 白昼雪地/晴空（无高能特效）稳定保持 1.0 不误暗，高能爆发瞬间迅速（0.15s）收缩光圈，退去后缓慢（0.60s）暗适应回亮。
uniform sampler2D Sampler0; // 线性 HDR 场景
uniform sampler2D Sampler1; // 泛光最低级（SourceFlags.x > 0.5）或 hdrGeo（SourceFlags.x <= 0.5）
uniform sampler2D Sampler2; // 上一帧 1x1 RG16F 状态（r=exposure, g=adaptedLuma）
uniform sampler2D Sampler3; // Heavy MRT Attachment 2 纯自发光（关泛光回退测光用）
uniform vec2 HeavyUvMax;    // 动态分辨率 MRT 有效 UV 上限
uniform vec4 AdaptParams;   // x=dt, y=tauDarken, z=tauBrighten, w=initialized (>0.5)
uniform vec4 ExposureBounds;// x=起始压光阈值 L0, y=minExposure, z=压光斜率 k, w=保留
uniform vec4 SourceFlags;   // x=hasBloom, y=hasHdrGeo, z=hasHeavyMrt, w=保留

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 safeMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
    float lumaSum = 0.0;
    float weightSum = 0.0;
    for (int y = 0; y < 4; y++) {
        float v = (float(y) + 0.5) * 0.25;
        for (int x = 0; x < 4; x++) {
            float u = (float(x) + 0.5) * 0.25;
            vec2 suv = vec2(u, v);
            vec3 sceneCol = max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
            float emitLuma = 0.0;
            if (SourceFlags.x > 0.5) {
                vec3 bloomCol = max(stSanitize(texture(Sampler1, suv).rgb), vec3(0.0));
                emitLuma = stLuma(bloomCol) * 1.25;
            } else {
                vec3 directEmit = vec3(0.0);
                if (SourceFlags.y > 0.5) {
                    directEmit += max(stSanitize(texture(Sampler1, suv).rgb), vec3(0.0));
                }
                if (SourceFlags.z > 0.5) {
                    vec2 hUv = clamp(suv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.001)));
                    directEmit += max(stSanitize(texture(Sampler3, hUv).rgb), vec3(0.0));
                }
                emitLuma = min(stLuma(directEmit), 16.0) * 0.85;
            }
            float luma = stLuma(sceneCol) * 0.35 + emitLuma;
            vec2 d = suv - 0.5;
            float w = 1.0 - dot(d, d) * 1.1;
            lumaSum += luma * w;
            weightSum += w;
        }
    }
    float targetLuma = clamp(lumaSum / max(weightSum, 1.0e-4), 0.0, 28.0);

    float prevLuma = targetLuma;
    if (AdaptParams.w > 0.5) {
        vec2 prev = texture(Sampler2, vec2(0.5)).rg;
        if (!isnan(prev.g) && !isinf(prev.g) && prev.g >= 0.0) {
            prevLuma = prev.g;
        }
    }

    float dt = clamp(AdaptParams.x, 0.0005, 0.1);
    float tau = (targetLuma > prevLuma) ? max(AdaptParams.y, 0.02) : max(AdaptParams.z, 0.05);
    float alpha = 1.0 - exp(-dt / tau);
    float adaptedLuma = mix(prevLuma, targetLuma, alpha);

    // 基准 1.0 压光曲线：正常场景（含白昼雪地/晴空）严格 = 1.0，仅当高亮爆发超过 L0 时顺滑收缩光圈
    float excess = max(adaptedLuma - ExposureBounds.x, 0.0);
    float rawExposure = 1.0 / (1.0 + excess * max(ExposureBounds.z, 0.1));
    float exposure = clamp(rawExposure, clamp(ExposureBounds.y, 0.20, 1.0), 1.0);
    fragColor = vec4(exposure, adaptedLuma, 0.0, 1.0);
}
