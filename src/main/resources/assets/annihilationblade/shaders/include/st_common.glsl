// Stellaris Engine 公共数学、精确 IEC sRGB 色彩、安全防护、深度法线重建、单色蓝噪声抖动与解析求交库（GLSL 150 原创）

#define ST_PI 3.14159265359
#define ST_TAU 6.28318530718
#define ST_INV_PI 0.31830988618

float stSaturate(float x) {
    return clamp(x, 0.0, 1.0);
}

vec2 stSaturate2(vec2 v) {
    return clamp(v, vec2(0.0), vec2(1.0));
}

vec3 stSaturate3(vec3 v) {
    return clamp(v, vec3(0.0), vec3(1.0));
}

vec4 stSaturate4(vec4 v) {
    return clamp(v, vec4(0.0), vec4(1.0));
}

// NaN / Inf 防火墙：阻断异常浮点进入 6 级 HDR 泛光金字塔导致全屏黑块
vec3 stSanitize(vec3 c) {
    bvec3 bad = bvec3(
        isnan(c.r) || isinf(c.r),
        isnan(c.g) || isinf(c.g),
        isnan(c.b) || isinf(c.b)
    );
    return mix(c, vec3(0.0), bad);
}

vec4 stSanitize4(vec4 c) {
    float a = (isnan(c.a) || isinf(c.a)) ? 0.0 : c.a;
    return vec4(stSanitize(c.rgb), a);
}

float stSafeDiv(float a, float b) {
    return a / (abs(b) < 1.0e-5 ? (b < 0.0 ? -1.0e-5 : 1.0e-5) : b);
}

float stSafePow(float base, float exponent) {
    return pow(max(base, 1.0e-5), exponent);
}

vec3 stSafePow3(vec3 base, vec3 exponent) {
    return pow(max(base, vec3(1.0e-5)), exponent);
}

float stSafeAtan(float y, float x) {
    return (abs(x) + abs(y) < 1.0e-6) ? 0.0 : atan(y, x);
}

mat2 stRot2(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat2(c, -s, s, c);
}

// 安全构造与法线正交的单位切空间基底（彻底避免平行向量叉积产生 NaN）
void stSafeOrthoBasis(vec3 n, out vec3 tangent, out vec3 bitangent) {
    vec3 up = (abs(n.y) < 0.92) ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, n));
    bitangent = cross(n, tangent);
}

// 精确 IEC 61966-2-1 分段 sRGB <-> Linear 转换：严守 0.0 -> 0.0 绝对纯黑黑位且无 pow(0) NaN
vec3 stSrgbToLinear(vec3 srgb) {
    vec3 c = stSaturate3(stSanitize(srgb));
    vec3 lo = c / 12.92;
    vec3 hi = stSafePow3((c + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(vec3(0.04045), c));
}

vec3 stLinearToSrgb(vec3 lin) {
    vec3 c = max(stSanitize(lin), vec3(0.0));
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * stSafePow3(c, vec3(1.0 / 2.4)) - 0.055;
    return stSaturate3(mix(lo, hi, step(vec3(0.0031308), c)));
}

float stLuma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// 轻量无三角函数哈希与值噪声
float stHash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float stHash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float stHash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 stHash33(vec3 p3) {
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yxx) * p3.zyx);
}

float stNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = stHash12(i);
    float b = stHash12(i + vec2(1.0, 0.0));
    float c = stHash12(i + vec2(0.0, 1.0));
    float d = stHash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float stNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    float n000 = stHash13(i);
    float n100 = stHash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = stHash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = stHash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = stHash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = stHash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = stHash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = stHash13(i + vec3(1.0, 1.0, 1.0));
    return mix(
        mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
        mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y),
        u.z
    );
}

float stFbm2(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    mat2 rot = mat2(0.8, -0.6, 0.6, 0.8);
    for (int i = 0; i < 4; i++) {
        sum += amp * stNoise2(p);
        p = rot * p * 2.02 + vec2(1.7, 9.2);
        amp *= 0.5;
    }
    return sum;
}

float stFbm3(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * stNoise3(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        amp *= 0.5;
    }
    return sum;
}

// Interleaved Gradient Noise 与时空黄金比例蓝噪声采样
float stIGN(vec2 fragCoord, float frameIdx) {
    vec2 p = fragCoord + vec2(fract(frameIdx * 0.61803399) * 59.0, fract(frameIdx * 0.38196601) * 37.0);
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

vec4 stBlueNoise(sampler2D blueNoiseTex, vec2 fragCoord, float frameIdx) {
    vec2 uv = fract(fragCoord / 64.0);
    vec4 bn = texture(blueNoiseTex, uv);
    return fract(bn + vec4(frameIdx * 0.61803399, frameIdx * 0.75487767, frameIdx * 0.56984029, frameIdx * 0.43015971));
}

// 精确位运算 Bayer 矩阵（GLSL 150 整型位交织，8K 分辨率零 float32 精度溢出，严格输出 [0..63]/64）
float stBayer2(vec2 p) {
    ivec2 c = ivec2(floor(p)) & 1;
    int v = (((c.x ^ c.y) << 1) | c.y);
    return float(v) * 0.25;
}

float stBayer4(vec2 p) {
    ivec2 c = ivec2(floor(p)) & 3;
    int v = 0;
    for (int bit = 1; bit >= 0; bit--) {
        int bx = (c.x >> bit) & 1;
        int by = (c.y >> bit) & 1;
        v = (v << 2) | (((bx ^ by) << 1) | by);
    }
    return float(v) * (1.0 / 16.0);
}

float stBayer8(vec2 p) {
    ivec2 c = ivec2(floor(p)) & 7;
    int v = 0;
    for (int bit = 2; bit >= 0; bit--) {
        int bx = (c.x >> bit) & 1;
        int by = (c.y >> bit) & 1;
        v = (v << 2) | (((bx ^ by) << 1) | by);
    }
    return float(v) * (1.0 / 64.0);
}

// 单色三角分布蓝噪声抖动：严格无彩噪，彻底消除 8-bit 暗空渐变色带
vec3 stDither8Blue(vec3 srgbColor, sampler2D blueNoiseTex, vec2 fragCoord, float frameIdx) {
    vec4 bn = stBlueNoise(blueNoiseTex, fragCoord, frameIdx);
    float tri = (bn.r - bn.g) * 0.85;
    // 纯黑区域（< 0.001）不施加正抖动，守住绝对深空黑洞黑位
    float blackMask = smoothstep(0.0005, 0.004, max(max(srgbColor.r, srgbColor.g), srgbColor.b));
    return stSaturate3(srgbColor + vec3(tri * blackMask / 255.0));
}

// 深度与世界相对坐标重建（统一使用 depth >= 0.9999 判天空，兼容光影包）
vec3 stViewRay(vec2 uv, mat4 invProj, mat4 invView) {
    vec4 v = invProj * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    v.xyz /= max(abs(v.w), 1.0e-5) * (v.w < 0.0 ? -1.0 : 1.0);
    return normalize((invView * vec4(v.xyz, 0.0)).xyz);
}

float stSceneDistance(float depth, vec2 uv, mat4 invProj) {
    if (depth >= 0.9999) {
        return 1.0e6;
    }
    vec4 v = invProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 vp = v.xyz / (abs(v.w) < 1.0e-5 ? 1.0e-5 : v.w);
    return length(vp);
}

vec3 stReconstructWorldRel(float depth, vec2 uv, mat4 invProj, mat4 invView) {
    vec4 v = invProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 vp = v.xyz / (abs(v.w) < 1.0e-5 ? 1.0e-5 : v.w);
    return (invView * vec4(vp, 0.0)).xyz;
}

// 最小斜率深度法线重建（含细前景/天空边界跳变防护）：彻底抑制方块棱角与栅栏边缘的法线炸裂
vec3 stReconstructNormal3x3(sampler2D depthTex, vec2 uv, vec2 outSize, mat4 invProj, mat4 invView) {
    vec2 px = 1.0 / max(outSize, vec2(1.0));
    float dC = texture(depthTex, uv).r;
    if (dC >= 0.9999) {
        return vec3(0.0, 1.0, 0.0);
    }
    vec3 pC = stReconstructWorldRel(dC, uv, invProj, invView);

    vec2 uvL = clamp(uv - vec2(px.x, 0.0), vec2(0.0005), vec2(0.9995));
    vec2 uvR = clamp(uv + vec2(px.x, 0.0), vec2(0.0005), vec2(0.9995));
    vec2 uvD = clamp(uv - vec2(0.0, px.y), vec2(0.0005), vec2(0.9995));
    vec2 uvU = clamp(uv + vec2(0.0, px.y), vec2(0.0005), vec2(0.9995));

    float dL = texture(depthTex, uvL).r;
    float dR = texture(depthTex, uvR).r;
    float dD = texture(depthTex, uvD).r;
    float dU = texture(depthTex, uvU).r;

    bool skyL = (dL >= 0.9999);
    bool skyR = (dR >= 0.9999);
    bool skyD = (dD >= 0.9999);
    bool skyU = (dU >= 0.9999);
    if ((skyL && skyR) || (skyD && skyU)) {
        return vec3(0.0, 1.0, 0.0);
    }

    bool pickR = (!skyR && (skyL || abs(dR - dC) < abs(dC - dL)));
    bool pickU = (!skyU && (skyD || abs(dU - dC) < abs(dC - dD)));

    vec3 dx = pickR
        ? (stReconstructWorldRel(dR, uvR, invProj, invView) - pC)
        : (pC - stReconstructWorldRel(dL, uvL, invProj, invView));
    vec3 dy = pickU
        ? (stReconstructWorldRel(dU, uvU, invProj, invView) - pC)
        : (pC - stReconstructWorldRel(dD, uvD, invProj, invView));

    vec3 n = cross(dx, dy);
    float len2 = dot(n, n);
    if (len2 < 1.0e-10) {
        return vec3(0.0, 1.0, 0.0);
    }
    return n * inversesqrt(len2);
}

// 解析射线求交（射线起点 ro，单位方向 rd）
vec2 stIntersectSphere(vec3 ro, vec3 rd, vec3 center, float radius) {
    vec3 oc = ro - center;
    float b = dot(oc, rd);
    float c = dot(oc, oc) - radius * radius;
    float h = b * b - c;
    if (h < 0.0) {
        return vec2(-1.0);
    }
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

// 有限高度圆柱侧面求交（底面圆心 base，单位轴 axis，半径 radius，高度 height）
vec2 stIntersectCylinder(vec3 ro, vec3 rd, vec3 base, vec3 axis, float radius, float height) {
    vec3 oc = ro - base;
    float card = dot(axis, rd);
    float caoc = dot(axis, oc);
    float a = 1.0 - card * card;
    float b = dot(oc, rd) - caoc * card;
    float c = dot(oc, oc) - caoc * caoc - radius * radius;
    float h = b * b - a * c;
    if (h < 0.0 || abs(a) < 1.0e-6) {
        return vec2(-1.0);
    }
    h = sqrt(h);
    float t0 = (-b - h) / a;
    float t1 = (-b + h) / a;
    float y0 = caoc + t0 * card;
    float y1 = caoc + t1 * card;
    if ((y0 < 0.0 && y1 < 0.0) || (y0 > height && y1 > height)) {
        return vec2(-1.0);
    }
    return vec2(t0, t1);
}

float stIntersectPlane(vec3 ro, vec3 rd, vec3 planePoint, vec3 planeNormal) {
    float denom = dot(rd, planeNormal);
    if (abs(denom) < 1.0e-5) {
        return -1.0;
    }
    return dot(planePoint - ro, planeNormal) / denom;
}
