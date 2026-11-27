#version 150

#moj_import <annihilationblade:st_common.glsl>
#moj_import <annihilationblade:st_optics.glsl>

// 纯客户端天体物理粒子回退着色器（Tesselator 路径，供无 TBO 驱动兜底）：
// 修复使用精确 texelFetch 读取共享 R32F 深度，并同步接入 3D 浮雕法阵 ReliefMeta 前后穿孔遮挡
uniform sampler2D Sampler0; // R32F 共享场景深度
uniform sampler2D Sampler1; // 3D 浮雕法阵 RGBA32F ReliefMeta
uniform mat4 InvProj;
uniform vec2 OutSize;
uniform float Softness;
uniform vec4 ReliefParams;  // xy=reliefUvMax, z=hasRelief(>0.5), w=0

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
    ivec2 depthDim = textureSize(Sampler0, 0);
    ivec2 depthPx = clamp(ivec2(gl_FragCoord.xy), ivec2(0), depthDim - ivec2(1));
    float sceneDepth = texelFetch(Sampler0, depthPx, 0).r;
    float sceneT = stSceneDistance(sceneDepth, screenUv, InvProj);
    float depthFade = stSoftDepthFade(sceneT, vViewDist, Softness);

    if (ReliefParams.z > 0.5) {
        vec2 safeMax = clamp(ReliefParams.xy, vec2(0.05), vec2(1.0));
        vec2 rUv = clamp(screenUv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.0005)));
        vec4 rMeta = texture(Sampler1, rUv);
        if (rMeta.r < 5.0e5) {
            float behind = smoothstep(rMeta.r - 0.12, rMeta.r + 0.16, vViewDist);
            depthFade *= mix(1.0, stSaturate(rMeta.g), behind);
        }
    }

    if (depthFade <= 0.002) {
        discard;
    }

    float r = sqrt(r2);
    float core = exp(-r2 * 11.5);
    float crossSpark = (exp(-abs(vUv.y) * 14.0 - abs(vUv.x) * 3.2) + exp(-abs(vUv.x) * 14.0 - abs(vUv.y) * 3.2)) * 0.24 * (1.0 - r);
    float halo = stSafePow(1.0 - r, 2.3) * 0.38;
    float profile = (core + crossSpark + halo) * depthFade;

    float hdrEnergy = vColor.a * vColor.a * 18.0;
    vec3 linColor = stSrgbToLinear(vColor.rgb);
    vec3 finalCol = mix(linColor, vec3(0.96, 0.99, 1.0), core * 0.64) * (profile * hdrEnergy);
    fragColor = vec4(stSanitize(finalCol), 1.0);
}
