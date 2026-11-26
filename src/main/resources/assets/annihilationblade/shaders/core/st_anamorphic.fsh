#version 150

#moj_import <annihilationblade:st_common.glsl>

// 电影级变形水平星芒（Anamorphic Streak）：
//  - Pass 0（mode < 0.5）：1/4 分辨率 3x3 抗闪烁高斯高阈值提取，消灭细星环垂直跳闪；
//  - Pass 1..4（mode >= 0.5）：4 趟严格单调平滑步长（1.0 -> 2.2 -> 4.8 -> 10.5，按屏宽归一化），
//    彻底消除串珠振铃与 4K 分辨率缩水，呈现丝绸般冰青冷色散电影横芒。
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 TexelSize;
uniform vec2 HeavyUvMax;
uniform vec4 PassParams; // mode 0: (0, threshold, primaryW, heavyW) | mode 1: (1, normStride, decay, 0)

in vec2 texCoord;

out vec4 fragColor;

vec3 sampleExtract(vec2 uv) {
    vec2 suv = clamp(uv, vec2(0.001), vec2(0.999));
    vec3 c = vec3(0.0);
    if (PassParams.z > 0.5) {
        c += max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
    }
    if (PassParams.w > 0.5) {
        vec2 safeMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
        vec2 hUv = clamp(suv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.001)));
        c += max(stSanitize(texture(Sampler1, hUv).rgb), vec3(0.0));
    }
    float peak = max(max(c.r, c.g), c.b);
    float threshold = max(PassParams.y, 0.5);
    float excess = max(peak - threshold, 0.0);
    float scale = (excess / max(peak, 1.0e-4)) * min(1.0, 14.0 / max(excess, 1.0e-4));
    return c * scale;
}

void main() {
    if (PassParams.x < 0.5) {
        // 3x3 高斯抗闪烁提取：有效吸收单像素水平细环在俯仰转动时的垂直频闪
        vec2 o = TexelSize * 0.75;
        vec3 sum =
            sampleExtract(texCoord + vec2(-o.x, -o.y)) * 1.0 +
            sampleExtract(texCoord + vec2( 0.0, -o.y)) * 2.0 +
            sampleExtract(texCoord + vec2( o.x, -o.y)) * 1.0 +
            sampleExtract(texCoord + vec2(-o.x,  0.0)) * 2.0 +
            sampleExtract(texCoord)                    * 4.0 +
            sampleExtract(texCoord + vec2( o.x,  0.0)) * 2.0 +
            sampleExtract(texCoord + vec2(-o.x,  o.y)) * 1.0 +
            sampleExtract(texCoord + vec2( 0.0,  o.y)) * 2.0 +
            sampleExtract(texCoord + vec2( o.x,  o.y)) * 1.0;
        fragColor = vec4(stSanitize(sum * (1.0 / 16.0)), 1.0);
        return;
    }

    // 9-tap 水平丝滑扩散核：normStride 已在 Java 端按屏宽归一化（720p~4K 屏宽占比恒定）
    float strideX = PassParams.y;
    float decay = clamp(PassParams.z, 0.55, 0.92);
    vec3 accum = vec3(0.0);
    float wSum = 0.0;
    for (int i = -4; i <= 4; i++) {
        float fi = float(i);
        float w = stSafePow(decay, abs(fi));
        float dx = fi * strideX * TexelSize.x;
        // 温和变形镜头色散（蓝青略宽、红芯紧凑）
        vec2 uvR = clamp(vec2(texCoord.x + dx * 0.92, texCoord.y), vec2(0.0005), vec2(0.9995));
        vec2 uvG = clamp(vec2(texCoord.x + dx * 1.00, texCoord.y), vec2(0.0005), vec2(0.9995));
        vec2 uvB = clamp(vec2(texCoord.x + dx * 1.08, texCoord.y), vec2(0.0005), vec2(0.9995));
        vec3 s = vec3(
            texture(Sampler0, uvR).r,
            texture(Sampler0, uvG).g,
            texture(Sampler0, uvB).b
        );
        accum += max(stSanitize(s), vec3(0.0)) * w;
        wSum += w;
    }
    fragColor = vec4(stSanitize(accum / max(wSum, 1.0e-4)), 1.0);
}
