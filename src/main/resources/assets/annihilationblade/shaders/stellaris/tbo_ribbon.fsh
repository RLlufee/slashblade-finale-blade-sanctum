#version 150

// 星穹引擎 GPU 实例化 3D 能量丝带/虹吸轨迹片元着色器（4 风格 + texelFetch 深度 + 3D 浮雕法阵穿孔遮挡）
// 风格：0=致密等离子能流, 1=高速能量脉冲, 2=高频跃迁电弧, 3=星穹双螺旋虹吸丝
uniform sampler2D DepthTex;
uniform sampler2D ReliefMetaTex;
uniform mat4 InvProj;
uniform vec2 OutSize;
uniform float StTime;
uniform float Softness;
uniform vec4 ReliefParams; // xy=reliefUvMax, z=hasRelief(>0.5), w=0

in vec2 vUv;
in vec3 vHdrColor;
in float vAlpha;
in float vViewDist;
in float vStyle;
in float vSeed;

out vec4 fragColor;

float linearSceneDist(float depth, vec2 uv) {
    if (depth >= 0.9999) {
        return 1.0e6;
    }
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 viewPos = InvProj * ndc;
    return length(viewPos.xyz / max(abs(viewPos.w), 1.0e-5));
}

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

void main() {
    if (vAlpha <= 0.002) {
        discard;
    }
    vec2 screenUv = clamp(gl_FragCoord.xy / max(OutSize, vec2(1.0)), vec2(0.0005), vec2(0.9995));
    ivec2 depthDim = textureSize(DepthTex, 0);
    ivec2 depthPx = clamp(ivec2(gl_FragCoord.xy), ivec2(0), depthDim - ivec2(1));
    float sceneDepth = texelFetch(DepthTex, depthPx, 0).r;
    float sceneT = linearSceneDist(sceneDepth, screenUv);
    float depthFade = clamp((sceneT - vViewDist) / max(Softness, 0.05), 0.0, 1.0);

    if (ReliefParams.z > 0.5) {
        vec2 safeMax = clamp(ReliefParams.xy, vec2(0.05), vec2(1.0));
        vec2 rUv = clamp(screenUv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.0005)));
        vec4 rMeta = texture(ReliefMetaTex, rUv);
        if (rMeta.r < 5.0e5) {
            float behind = smoothstep(rMeta.r - 0.10, rMeta.r + 0.14, vViewDist);
            depthFade *= mix(1.0, clamp(rMeta.g, 0.0, 1.0), behind);
        }
    }

    if (depthFade <= 0.002) {
        discard;
    }

    int style = int(vStyle + 0.5);
    float u = vUv.x;
    float y = abs(vUv.y);
    float edgeFade = 1.0 - smoothstep(0.76, 1.0, y);
    float energy = 0.0;
    float hot = 0.0;

    if (style == 0) {
        // 0. 致密等离子能流
        float core = exp(-y * y * 9.0);
        float halo = exp(-y * 2.8) * 0.45;
        energy = (core + halo) * edgeFade;
        hot = core * core;
    } else if (style == 1) {
        // 1. 高速流脉冲波节
        float pulse = 0.35 + 0.85 * pow(0.5 + 0.5 * sin(u * 18.0 - StTime * 11.0 + vSeed * 6.28318), 4.0);
        float core = exp(-y * y * 11.0);
        float halo = exp(-y * 2.5) * 0.40;
        energy = (core + halo) * pulse * edgeFade;
        hot = core * pulse;
    } else if (style == 2) {
        // 2. 高频锯齿跃迁电弧
        float seg = floor(u * 14.0 + floor(StTime * 18.0) * 0.37);
        float jitter = (hash11(seg + vSeed * 57.1) - 0.5) * 0.62;
        float dy = abs(vUv.y - jitter);
        float bolt = exp(-dy * dy * 42.0);
        float corona = exp(-dy * 4.2) * 0.48;
        float flicker = 0.65 + 0.35 * sin(StTime * 36.0 + vSeed * 40.0);
        energy = (bolt * 1.45 + corona) * flicker * edgeFade;
        hot = bolt;
    } else {
        // 3. 星穹双螺旋虹吸能量丝
        float phase = u * 16.0 - StTime * 5.2 + vSeed * 6.28318;
        float s1 = sin(phase) * 0.46;
        float s2 = -s1;
        float strandA = exp(-abs(vUv.y - s1) * 11.0);
        float strandB = exp(-abs(vUv.y - s2) * 11.0);
        float bridge = exp(-y * 3.0) * 0.25;
        float pulse = 0.72 + 0.45 * pow(0.5 + 0.5 * sin(u * 10.0 - StTime * 8.5), 3.0);
        energy = (max(strandA, strandB) * 1.25 + bridge) * pulse * edgeFade;
        hot = max(strandA, strandB) * 0.85;
    }

    float peak = max(max(vHdrColor.r, vHdrColor.g), vHdrColor.b);
    vec3 outCol = mix(vHdrColor, vec3(0.95, 0.99, 1.0) * peak, clamp(hot * 0.55, 0.0, 0.85));
    outCol *= energy * vAlpha * depthFade;
    if (isnan(outCol.r) || isnan(outCol.g) || isnan(outCol.b) || isinf(outCol.r) || isinf(outCol.g) || isinf(outCol.b)) {
        outCol = vec3(0.0);
    }
    fragColor = vec4(max(outCol, vec3(0.0)), 1.0);
}
