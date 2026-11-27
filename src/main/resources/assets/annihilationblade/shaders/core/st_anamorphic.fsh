#version 150

#moj_import <annihilationblade:st_common.glsl>

// 电影级变形水平星芒（Step 1.6 四源合流版 Anamorphic Streak）：
//  - Pass 0（mode < 0.5）：1/4 分辨率 3x3 抗闪烁高斯高阈值提取，同步接纳 hdrGeo + HeavyEmit + 3D 浮雕法阵 + Stage 4 SceneEmit；
//  - Pass 1..4（mode >= 0.5）：4 趟严格单调平滑步长（1.0 -> 2.2 -> 4.8 -> 10.5，按屏宽归一化），呈现丝绸般冰青冷色散电影横芒。
uniform sampler2D Sampler0; // Pass 0: hdrGeo | Pass 1..4: 上一趟星芒
uniform sampler2D Sampler1; // Pass 0: Heavy MRT Attachment 2 纯自发光
uniform sampler2D Sampler2; // Pass 0: 3D 物理浮雕法阵 ReliefPass HDR 辐射层
uniform sampler2D Sampler3; // Pass 0: Stage 4 SceneEmit 纯自发光层

uniform vec2 TexelSize;
uniform vec2 HeavyUvMax;
uniform vec2 ReliefUvMax;
uniform vec4 PassParams;   // mode 0: (0, threshold, 0, 0) | mode 1: (1, normStride, decay, 0)
uniform vec4 LayerWeights; // Pass 0: x=hdrGeo, y=heavyEmit, z=reliefColor, w=sceneEmit

in vec2 texCoord;

out vec4 fragColor;

vec3 sampleExtract(vec2 uv) {
    vec2 suv = clamp(uv, vec2(0.001), vec2(0.999));
    vec3 c = vec3(0.0);
    if (LayerWeights.x > 0.5) {
        c += max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
    }
    if (LayerWeights.y > 0.5) {
        vec2 safeMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
        vec2 hUv = clamp(suv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.001)));
        c += max(stSanitize(texture(Sampler1, hUv).rgb), vec3(0.0));
    }
    if (LayerWeights.z > 0.5) {
        vec2 safeR = clamp(ReliefUvMax, vec2(0.05), vec2(1.0));
        vec2 rUv = clamp(suv * safeR, vec2(0.0005), max(vec2(0.0005), safeR - vec2(0.001)));
        c += max(stSanitize(texture(Sampler2, rUv).rgb), vec3(0.0));
    }
    if (LayerWeights.w > 0.5) {
        c += max(stSanitize(texture(Sampler3, suv).rgb), vec3(0.0));
    }
    float peak = max(max(c.r, c.g), c.b);
    float threshold = max(PassParams.y, 0.5);
    float excess = max(peak - threshold, 0.0);
    float scale = (excess / max(peak, 1.0e-4)) * min(1.0, 14.0 / max(excess, 1.0e-4));
    return c * scale;
}

void main() {
    if (PassParams.x < 0.5) {
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
