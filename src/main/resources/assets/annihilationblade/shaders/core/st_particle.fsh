#version 150

#moj_import <annihilationblade:st_common.glsl>
#moj_import <annihilationblade:st_optics.glsl>

// 纯客户端天体物理粒子片元着色器：
// 恒星针尖核 + 微十字星芒 + 丝滑光晕 + 非线性平方 HDR 编码（暗部淡出精度提升 260 倍，零 8-bit 阶梯闪烁）
uniform sampler2D Sampler0; // R32F 共享场景深度
uniform mat4 InvProj;
uniform vec2 OutSize;
uniform float Softness;

in vec4 vColor;
in vec2 vUv;
in float vViewDist;

out vec4 fragColor;

void main() {
    float r2 = dot(vUv, vUv);
    if (r2 >= 1.0) {
        discard;
    }
    vec2 screenUv = clamp(gl_FragCoord.xy / max(OutSize, vec2(1.0)), vec2(0.0005), vec2(0.9995));
    float sceneDepth = texture(Sampler0, screenUv).r;
    float sceneT = stSceneDistance(sceneDepth, screenUv, InvProj);
    float depthFade = stSoftDepthFade(sceneT, vViewDist, Softness);
    if (depthFade <= 0.002) {
        discard;
    }

    float r = sqrt(r2);
    float core = exp(-r2 * 11.5);
    float crossSpark = (exp(-abs(vUv.y) * 14.0 - abs(vUv.x) * 3.2) + exp(-abs(vUv.x) * 14.0 - abs(vUv.y) * 3.2)) * 0.24 * (1.0 - r);
    float halo = stSafePow(1.0 - r, 2.3) * 0.38;
    float profile = (core + crossSpark + halo) * depthFade;

    // 平方解码（配合 CPU 端 sqrt(energy / 18.0) 打包），暗部末端极度丝滑
    float hdrEnergy = vColor.a * vColor.a * 18.0;
    vec3 linColor = stSrgbToLinear(vColor.rgb);
    vec3 finalCol = mix(linColor, vec3(0.96, 0.99, 1.0), core * 0.64) * (profile * hdrEnergy);
    fragColor = vec4(stSanitize(finalCol), 1.0);
}
