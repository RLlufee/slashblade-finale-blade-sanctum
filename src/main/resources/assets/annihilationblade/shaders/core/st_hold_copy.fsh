#version 150

#moj_import <annihilationblade:st_common.glsl>

// Hold-Frame 视网膜残像捕获：将爆发瞬间的线性 HDR 场景 + 几何 + MRT 辐射度压缩捕获至半分辨率持久缓冲
uniform sampler2D Sampler0; // 线性 HDR 场景
uniform sampler2D Sampler1; // HDR 几何 + 粒子
uniform sampler2D Sampler2; // Heavy MRT Attachment 0（遮挡/折射）
uniform sampler2D Sampler3; // Heavy MRT Attachment 2（纯自发光）
uniform vec2 HeavyUvMax;
uniform float GeoWeight;
uniform float HeavyWeight;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    // 仅压低捕获原版背景以突出高能星核/星环的视网膜灼烧残像轮廓，避免白昼草地变成全屏雾霾
    vec3 col = max(stSanitize(texture(Sampler0, texCoord).rgb), vec3(0.0)) * 0.28;
    if (HeavyWeight > 0.5) {
        vec2 safeMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
        vec2 hUv = clamp(texCoord * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.001)));
        vec4 heavy = stSanitize4(texture(Sampler2, hUv));
        vec3 heavyEmit = max(stSanitize(texture(Sampler3, hUv).rgb), vec3(0.0));
        col = col * (1.0 - stSaturate(heavy.a)) + max(heavy.rgb, vec3(0.0)) * 0.35 + heavyEmit;
    }
    if (GeoWeight > 0.5) {
        col += max(stSanitize(texture(Sampler1, texCoord).rgb), vec3(0.0));
    }
    col = col / (vec3(1.0) + col * 0.45);
    fragColor = vec4(stSanitize(col), 1.0);
}
