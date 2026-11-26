#version 150

#moj_import <annihilationblade:st_common.glsl>

// 6 级 HDR 泛光帐篷升采样（分频权重导出紧凑锐核 + 广域星云大气辉光）
uniform sampler2D Sampler0; // 更低分辨率一级
uniform sampler2D Sampler1; // 当前级降采样
uniform vec2 TexelSize;
uniform vec2 BlendParams;   // x = 滤波半径, y = 扩散混合权重

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 d = TexelSize * BlendParams.x;
    vec3 up =
        texture(Sampler0, texCoord + vec2(-d.x,  d.y)).rgb +
        texture(Sampler0, texCoord + vec2( 0.0,  d.y)).rgb * 2.0 +
        texture(Sampler0, texCoord + vec2( d.x,  d.y)).rgb +
        texture(Sampler0, texCoord + vec2(-d.x,  0.0)).rgb * 2.0 +
        texture(Sampler0, texCoord).rgb * 4.0 +
        texture(Sampler0, texCoord + vec2( d.x,  0.0)).rgb * 2.0 +
        texture(Sampler0, texCoord + vec2(-d.x, -d.y)).rgb +
        texture(Sampler0, texCoord + vec2( 0.0, -d.y)).rgb * 2.0 +
        texture(Sampler0, texCoord + vec2( d.x, -d.y)).rgb;
    up = stSanitize(up * (1.0 / 16.0));
    vec3 current = stSanitize(texture(Sampler1, texCoord).rgb);
    // 能量守恒双频插值（彻底消除 6 级纯加法导致的 3.3x 中心大白团过曝爆炸）
    float scatter = clamp(BlendParams.y, 0.05, 0.92);
    fragColor = vec4(mix(current, up, scatter), 1.0);
}
