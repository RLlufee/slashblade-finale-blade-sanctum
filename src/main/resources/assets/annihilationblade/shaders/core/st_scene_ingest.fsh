#version 150

#moj_import <annihilationblade:st_common.glsl>

// 阶段 1：将原版 sRGB 主画面精确转换到线性 RGBA16F HDR 空间，消除后续 8-bit 截断
uniform sampler2D Sampler0;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 srgb = stSanitize(texture(Sampler0, texCoord).rgb);
    fragColor = vec4(stSrgbToLinear(srgb), 1.0);
}
