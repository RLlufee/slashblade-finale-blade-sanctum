#version 150

#moj_import <annihilationblade:st_common.glsl>

// Hold-Frame 视网膜闪盲残像捕获（Step 1.6 升级版）：
// 直接从完整两阶段线性 HDR 预合成画面（已完整包含 Body 暗核剪影、3D 浮雕法阵、Bloom、星芒、体积光与 SceneEmit）
// 执行 5-tap 抗锯齿降采样并提取高对比视网膜灼烧烙印！
uniform sampler2D Sampler0; // 完整线性 HDR 预合成画面（PostMode=1 输出）
uniform vec2 TexelSize;     // 预合成全分辨率像素尺寸倒数

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 o = TexelSize * 0.85;
    vec3 c0 = max(stSanitize(texture(Sampler0, texCoord).rgb), vec3(0.0));
    vec3 c1 = max(stSanitize(texture(Sampler0, clamp(texCoord + vec2(-o.x, -o.y), vec2(0.0005), vec2(0.9995))).rgb), vec3(0.0));
    vec3 c2 = max(stSanitize(texture(Sampler0, clamp(texCoord + vec2( o.x, -o.y), vec2(0.0005), vec2(0.9995))).rgb), vec3(0.0));
    vec3 c3 = max(stSanitize(texture(Sampler0, clamp(texCoord + vec2(-o.x,  o.y), vec2(0.0005), vec2(0.9995))).rgb), vec3(0.0));
    vec3 c4 = max(stSanitize(texture(Sampler0, clamp(texCoord + vec2( o.x,  o.y), vec2(0.0005), vec2(0.9995))).rgb), vec3(0.0));
    vec3 avg = c0 * 0.40 + (c1 + c2 + c3 + c4) * 0.15;

    // 非线性压低低亮普通地形、保留星环/奇点/法阵亮纹与暗核剪影反差
    float luma = stLuma(avg);
    float highlight = smoothstep(0.18, 0.92, luma);
    vec3 burnt = avg * (0.25 + 1.15 * highlight);
    fragColor = vec4(stSanitize(burnt), 1.0);
}
