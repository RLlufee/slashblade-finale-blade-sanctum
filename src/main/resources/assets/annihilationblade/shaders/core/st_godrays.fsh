#version 150

#moj_import <annihilationblade:st_common.glsl>

// 自发光专属径向体积光（God Rays）：
//  - 优先采用半分辨率已抗闪烁预滤波的 bloomDown[0]（或回退自发光原层），根除细星环低步数雪花噪点；
//  - 衰减严格按归一化步进进度 exp(-t * density) 积分，确保 BALANCED(12步) / CINEMATIC(24步) / ULTIMATE(40步) 亮度与光束长度 100% 一致；
//  - 彻底移除天空深度硬切，消除光源在山体与天空交界移动时的体积光闪跳。
uniform sampler2D Sampler0; // 预滤波自发光（bloomDown[0]）或 hdrGeo
uniform sampler2D Sampler1; // MRT Attachment 2 纯自发光（仅未用 bloomDown[0] 时启用）
uniform sampler2D Sampler2; // 64x64 蓝噪声
uniform vec2 OutSize;
uniform vec2 HeavyUvMax;
uniform vec4 Meta;          // x=步数, y=frameIdx, z=primaryW, w=heavyW
uniform vec4 Source0;       // xy=屏幕UV, z=强度, w=衰减系数(0.85..0.992，自动换算为步数无关密度)
uniform vec4 Color0;        // rgb=色调
uniform vec4 Source1;
uniform vec4 Color1;

in vec2 texCoord;

out vec4 fragColor;

vec3 sampleEmissive(vec2 uv) {
    vec2 suv = clamp(uv, vec2(0.0005), vec2(0.9995));
    vec3 c = vec3(0.0);
    if (Meta.z > 0.5) {
        c += max(stSanitize(texture(Sampler0, suv).rgb), vec3(0.0));
    }
    if (Meta.w > 0.5) {
        vec2 safeMax = clamp(HeavyUvMax, vec2(0.05), vec2(1.0));
        vec2 hUv = clamp(suv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.001)));
        c += max(stSanitize(texture(Sampler1, hUv).rgb), vec3(0.0));
    }
    float peak = max(max(c.r, c.g), c.b);
    if (peak > 8.0) {
        c *= 8.0 / peak;
    }
    return c;
}

vec3 traceRaySource(vec4 src, vec3 tint, int steps, float jitter) {
    if (src.z <= 0.001) {
        return vec3(0.0);
    }
    vec2 delta = src.xy - texCoord;
    float dist = length(delta);
    // 按参考步数 24 将单步 decay 换算为步数无关的连续衰减密度，彻底消除跨画质亮度倒挂
    float density = -log(clamp(src.w, 0.80, 0.995)) * 24.0;
    float span = min(dist, 0.96);
    vec2 rayDir = delta / max(dist, 1.0e-4);
    float invSteps = 1.0 / float(steps);

    vec3 sum = vec3(0.0);
    float wSum = 0.0;
    for (int i = 0; i < 40; i++) {
        if (i >= steps) {
            break;
        }
        float t = (float(i) + jitter) * invSteps;
        vec2 sampleUv = texCoord + rayDir * (span * t);
        if (sampleUv.x < 0.0 || sampleUv.x > 1.0 || sampleUv.y < 0.0 || sampleUv.y > 1.0) {
            break;
        }
        float w = exp(-t * density);
        sum += sampleEmissive(sampleUv) * w;
        wSum += w;
    }

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    float radialDist = length(delta * vec2(aspect, 1.0));
    float radialMask = 1.0 - smoothstep(0.16, 1.30, radialDist);
    vec3 avg = sum / max(wSum, 1.0e-3);
    return avg * tint * (src.z * (0.28 + 0.72 * radialMask));
}

void main() {
    int steps = clamp(int(Meta.x + 0.5), 8, 40);
    vec4 bn = stBlueNoise(Sampler2, gl_FragCoord.xy, Meta.y);
    vec3 rays = traceRaySource(Source0, Color0.rgb, steps, bn.r);
    if (Source1.z > 0.001) {
        rays += traceRaySource(Source1, Color1.rgb, max(8, steps / 2), bn.g);
    }
    fragColor = vec4(stSanitize(rays), 1.0);
}
