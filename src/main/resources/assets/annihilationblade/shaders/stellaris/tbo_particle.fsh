#version 150

// 星穹引擎 GPU 实例化 5 形态 HDR 天体粒子片元着色器（全 float32 线性 HDR + texelFetch 深度 + 3D 浮雕法阵穿孔遮挡）
// 形态：0=星核十字闪芒, 1=棱面菱晶碎钻, 2=电影六边形焦外散景, 3=速度拖尾流线, 4=贴地炽热余烬
uniform sampler2D DepthTex;
uniform sampler2D ReliefMetaTex;
uniform mat4 InvProj;
uniform vec2 OutSize;
uniform float Softness;
uniform vec4 ReliefParams; // xy=reliefUvMax, z=hasRelief(>0.5), w=0

in vec2 vUv;
in vec3 vHdrColor;
in float vViewDist;
in float vShapeId;
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

void main() {
    float r2 = dot(vUv, vUv);
    if (r2 >= 1.05) {
        discard;
    }

    vec2 screenUv = clamp(gl_FragCoord.xy / max(OutSize, vec2(1.0)), vec2(0.0005), vec2(0.9995));
    ivec2 depthDim = textureSize(DepthTex, 0);
    ivec2 depthPx = clamp(ivec2(gl_FragCoord.xy), ivec2(0), depthDim - ivec2(1));
    float sceneDepth = texelFetch(DepthTex, depthPx, 0).r;
    float sceneT = linearSceneDist(sceneDepth, screenUv);
    float depthFade = clamp((sceneT - vViewDist) / max(Softness, 0.05), 0.0, 1.0);

    // 3D 物理浮雕法阵前后穿孔遮挡：粒子穿过法阵环孔后被前环真实遮挡
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

    int shape = int(vShapeId + 0.5);
    float r = sqrt(r2);
    float profile = 0.0;
    float hotCore = 0.0;

    if (shape == 0) {
        // 0. 恒星针尖核 + 四芒微衍射星芒 + 丝滑光晕
        float core = exp(-r2 * 12.0);
        float crossSpark = (exp(-abs(vUv.y) * 15.0 - abs(vUv.x) * 3.0) + exp(-abs(vUv.x) * 15.0 - abs(vUv.y) * 3.0)) * 0.28 * max(1.0 - r, 0.0);
        float halo = pow(max(1.0 - r, 0.0), 2.3) * 0.36;
        profile = core + crossSpark + halo;
        hotCore = core;
    } else if (shape == 1) {
        // 1. 棱面菱晶碎钻（锐利菱形边界 + 内部折射高光脊）
        vec2 a = abs(vUv);
        float d = a.x * 0.72 + a.y * 1.15;
        float body = 1.0 - smoothstep(0.45, 0.72, d);
        float facetRidge = exp(-abs(a.x - a.y * 0.65) * 16.0) * body;
        float rim = exp(-abs(d - 0.58) * 20.0);
        profile = body * 0.85 + rim * 0.72 + exp(-r2 * 5.0) * 0.22;
        hotCore = clamp(facetRidge * 0.9 + rim * 0.55, 0.0, 1.0);
    } else if (shape == 2) {
        // 2. 电影六边形光圈焦外散景（Hexagonal Aperture Bokeh）
        vec2 p = abs(vUv);
        float hexD = max(p.x * 0.8660254 + p.y * 0.5, p.y);
        float disk = 1.0 - smoothstep(0.68, 0.82, hexD);
        float rimCaustic = smoothstep(0.48, 0.72, hexD) * disk;
        profile = disk * (0.42 + 0.75 * rimCaustic);
        hotCore = rimCaustic * 0.55;
    } else if (shape == 3) {
        // 3. 高速速度拖尾流线（头部尖锐白热、尾部柔长衰减）
        float along = vUv.x * 0.5 + 0.5; // 0=尾, 1=头
        float taper = mix(0.20, 1.0, smoothstep(0.0, 0.85, along));
        float across = abs(vUv.y) / max(taper, 0.12);
        float spine = exp(-across * across * 7.5);
        float headFlash = exp(-((vUv.x - 0.55) * (vUv.x - 0.55)) * 6.0 - vUv.y * vUv.y * 10.0);
        float ends = smoothstep(0.0, 0.18, along) * (1.0 - smoothstep(0.88, 1.0, along));
        profile = (spine * 0.75 + headFlash * 1.15) * ends;
        hotCore = headFlash;
    } else {
        // 4. 贴地炽热余烬（靠近地形表面时激发额外接触火星辉光）
        float core = exp(-r2 * 10.0);
        float halo = pow(max(1.0 - r, 0.0), 2.0) * 0.48;
        float groundContact = 1.0 + 0.65 * exp(-abs(sceneT - vViewDist) * 1.8);
        profile = (core + halo) * groundContact;
        hotCore = core;
    }

    vec3 outCol = mix(vHdrColor, vec3(0.96, 0.99, 1.0) * max(max(vHdrColor.r, vHdrColor.g), vHdrColor.b), clamp(hotCore * 0.58, 0.0, 0.85));
    outCol *= profile * depthFade;
    if (isnan(outCol.r) || isnan(outCol.g) || isnan(outCol.b) || isinf(outCol.r) || isinf(outCol.g) || isinf(outCol.b)) {
        outCol = vec3(0.0);
    }
    fragColor = vec4(max(outCol, vec3(0.0)), 1.0);
}
