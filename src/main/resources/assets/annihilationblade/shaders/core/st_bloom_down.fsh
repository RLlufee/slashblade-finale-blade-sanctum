#version 150

#moj_import <annihilationblade:st_common.glsl>

// 6 级 HDR 泛光降采样（13-tap 双线性 + 纯净 Soft-Knee 软阈值 + Karis Average 抗闪烁 + 动态分辨率 UV 钳制）
uniform sampler2D Sampler0; // 主 HDR 层（几何+粒子）或上一级泛光
uniform sampler2D Sampler1; // MRT 附件 2 纯自发光提取层（仅首级启用）
uniform vec2 TexelSize;
uniform vec2 HeavyUvMax;    // 动态分辨率 MRT 有效 UV 上限（[0.05..1.0]）
uniform float Prefilter;
uniform float PrimaryWeight;
uniform float EmissiveWeight;

in vec2 texCoord;

out vec4 fragColor;

vec3 prefilterHdr(vec3 c) {
    vec3 col = max(stSanitize(c), vec3(0.0));
    float peak = max(max(col.r, col.g), col.b);
    const float clampPeak = 24.0;
    if (peak > clampPeak) {
        col *= clampPeak / peak;
        peak = clampPeak;
    }
    // 严格纯净 Soft-Knee（移除强制 10% 暗部底晕，让低亮精密刻度与数码管刀锋般锐利）
    const float threshold = 0.95;
    const float knee = 0.55;
    float soft = clamp(peak - threshold + knee, 0.0, 2.0 * knee);
    soft = (soft * soft) / (4.0 * knee + 1.0e-4);
    float contrib = max(soft, peak - threshold) / max(peak, 1.0e-4);
    return col * stSaturate(contrib);
}

vec3 tapSample(vec2 offset) {
    vec2 uv = clamp(texCoord + offset * TexelSize, vec2(0.0005), vec2(0.9995));
    vec3 col = vec3(0.0);
    if (PrimaryWeight > 0.5) {
        col += max(stSanitize(texture(Sampler0, uv).rgb), vec3(0.0));
    }
    if (EmissiveWeight > 0.5) {
        vec2 safeMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
        vec2 hUv = clamp(uv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.001)));
        col += max(stSanitize(texture(Sampler1, hUv).rgb), vec3(0.0));
    }
    if (Prefilter > 0.5) {
        col = prefilterHdr(col);
    }
    return stSanitize(col);
}

float karisWeight(vec3 c) {
    return 1.0 / (1.0 + stLuma(c));
}

void main() {
    vec3 a = tapSample(vec2(-2.0,  2.0));
    vec3 b = tapSample(vec2( 0.0,  2.0));
    vec3 c = tapSample(vec2( 2.0,  2.0));
    vec3 d = tapSample(vec2(-2.0,  0.0));
    vec3 e = tapSample(vec2( 0.0,  0.0));
    vec3 f = tapSample(vec2( 2.0,  0.0));
    vec3 g = tapSample(vec2(-2.0, -2.0));
    vec3 h = tapSample(vec2( 0.0, -2.0));
    vec3 i = tapSample(vec2( 2.0, -2.0));
    vec3 j = tapSample(vec2(-1.0,  1.0));
    vec3 k = tapSample(vec2( 1.0,  1.0));
    vec3 l = tapSample(vec2(-1.0, -1.0));
    vec3 m = tapSample(vec2( 1.0, -1.0));

    vec3 outCol;
    if (Prefilter > 0.5) {
        vec3 g0 = (a + b + d + e) * 0.25;
        vec3 g1 = (b + c + e + f) * 0.25;
        vec3 g2 = (d + e + g + h) * 0.25;
        vec3 g3 = (e + f + h + i) * 0.25;
        vec3 g4 = (j + k + l + m) * 0.25;
        float w0 = karisWeight(g0) * 0.125;
        float w1 = karisWeight(g1) * 0.125;
        float w2 = karisWeight(g2) * 0.125;
        float w3 = karisWeight(g3) * 0.125;
        float w4 = karisWeight(g4) * 0.500;
        outCol = (g0 * w0 + g1 * w1 + g2 * w2 + g3 * w3 + g4 * w4) / max(w0 + w1 + w2 + w3 + w4, 1.0e-4);
    } else {
        outCol = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
    }
    fragColor = vec4(stSanitize(outCol), 1.0);
}
