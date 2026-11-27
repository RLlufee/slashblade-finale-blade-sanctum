#version 150

#moj_import <annihilationblade:st_common.glsl>

// 星穹引擎原生线性 HDR 几何着色器（与旧 fx_world.fsh 彻底解耦，17/17 全 FxGeometry.Style 完整覆盖）：
//  - 顶点色经精确 IEC stSrgbToLinear 转入线性空间，彻底消灭双重 Gamma 导致的暗部浮灰与冰青/电紫洗白；
//  - 支持 HDR 加法发光层（FxBlendMode = 0）与预乘 Alpha 深空暗部本体层（FxBlendMode = 1）；
//  - 全部 pow/atan/normalize 严格走安全封装，导数均在 discard 之前求取，杜绝 NaN 与驱动未定义行为。
uniform sampler2D Sampler0; // 共享 R32F 场景深度
uniform sampler2D Sampler1; // 无缝流场噪声贴图（R=fBm云雾, G=Worley脊线, BA=旋度偏移）
uniform sampler2D Sampler2; // 3D 物理浮雕法阵 RGBA32F ReliefMeta（r=最近命中距离, g=透射率）
uniform mat4 ProjMat;
uniform vec2 FxOutSize;
uniform vec4 FxReliefParams; // xy=reliefUvMax, z=hasRelief(>0.5), w=0
uniform float FxStyle;
uniform float FxEmission;
uniform float FxSoft;
uniform float FxTime;
uniform vec4 FxParams;
uniform vec4 FxOccluder;
uniform vec4 FxGeoCenter;
uniform float FxBlendMode;

in vec4 vertexColor;
in vec2 texCoord0;
in float viewDistance;
in vec3 relativePos;

out vec4 fragColor;

vec3 gFaceNormal = vec3(0.0, 1.0, 0.0);
float gRadialWidth = 0.002;
float gCurtainWidth = 0.01;
vec3 gLinColor = vec3(1.0);

float stLinearDepth(float depth) {
    float ndc = depth * 2.0 - 1.0;
    return ProjMat[3][2] / (ndc + ProjMat[2][2]);
}

float stDepthMask() {
    ivec2 dim = textureSize(Sampler0, 0);
    ivec2 coord = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dim - ivec2(1));
    float depth = texelFetch(Sampler0, coord, 0).r;
    float scene = depth >= 0.9999 ? 1.0e6 : stLinearDepth(depth);
    float diff = scene - viewDistance;
    if (FxSoft <= 0.0) {
        return diff > -(0.03 + viewDistance * 0.002) ? 1.0 : 0.0;
    }
    return clamp(diff / FxSoft, 0.0, 1.0);
}

// 3D 物理浮雕法阵真实前后穿孔遮挡：光柱/剑气位于立体管壁后方时自动按透射率淡隐
float stReliefMask() {
    if (FxReliefParams.z < 0.5) {
        return 1.0;
    }
    vec2 screenUv = clamp(gl_FragCoord.xy / max(FxOutSize, vec2(1.0)), vec2(0.0005), vec2(0.9995));
    vec2 safeMax = clamp(FxReliefParams.xy, vec2(0.05), vec2(1.0));
    vec2 rUv = clamp(screenUv * safeMax, vec2(0.0005), max(vec2(0.0005), safeMax - vec2(0.0005)));
    vec4 meta = texture(Sampler2, rUv);
    if (meta.r >= 5.0e5) {
        return 1.0;
    }
    float fragDist = length(relativePos);
    float behind = smoothstep(meta.r - 0.12, meta.r + 0.16, fragDist);
    return mix(1.0, stSaturate(meta.g), behind);
}

float stOccluderMask() {
    if (FxOccluder.w <= 0.0) {
        return 1.0;
    }
    float fragDist = length(relativePos);
    vec3 rd = relativePos / max(fragDist, 1.0e-4);
    float tc = dot(FxOccluder.xyz, rd);
    if (tc <= 0.0) {
        return 1.0;
    }
    float dRay = length(FxOccluder.xyz - rd * tc);
    float r = FxOccluder.w;
    float entry = tc - sqrt(max(r * r - dRay * dRay, 0.0));
    if (fragDist <= entry) {
        return 1.0;
    }
    return smoothstep(r * 0.96, r * 1.03, dRay);
}

vec3 stSurfaceNormal() {
    if (FxGeoCenter.w > 0.5) {
        vec3 delta = relativePos - FxGeoCenter.xyz;
        float len2 = dot(delta, delta);
        if (len2 > 1.0e-10) {
            return delta * inversesqrt(len2);
        }
    }
    return gFaceNormal;
}

vec3 stSafeViewDir(vec3 relPos) {
    float len2 = dot(relPos, relPos);
    return len2 > 1.0e-8 ? (-relPos * inversesqrt(len2)) : vec3(0.0, 0.0, -1.0);
}

vec2 stSampleFlow(vec2 uv, float speed) {
    float t = FxTime * speed;
    vec4 fastSample = texture(Sampler1, fract(vec2(uv.x * 2.0 + t * 0.42, uv.y * 0.85 - t * 0.65)));
    vec4 slowSample = texture(Sampler1, fract(vec2(uv.x * 1.0 - t * 0.18, uv.y * 1.45 - t * 0.28)));
    vec2 v1 = fastSample.ba * 2.0 - 1.0;
    vec2 v2 = slowSample.ba * 2.0 - 1.0;
    return v1 * 0.085 + v2 * 0.055;
}

float stAaLine(float dist, float halfWidth, float aa) {
    return 1.0 - smoothstep(halfWidth, halfWidth + aa, abs(dist));
}

float stSeg2(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1.0e-6), 0.0, 1.0);
    return length(pa - ba * h);
}

float stDot2(vec2 v) {
    return dot(v, v);
}

float stSdHeart(vec2 p) {
    p.x = abs(p.x);
    if (p.y + p.x > 1.0) {
        return sqrt(stDot2(p - vec2(0.25, 0.75))) - 0.35355339;
    }
    return sqrt(min(stDot2(p - vec2(0.0, 1.0)), stDot2(p - 0.5 * max(p.x + p.y, 0.0)))) * sign(p.x - p.y);
}

float stSdStar5(vec2 p, float r, float rf) {
    const vec2 k1 = vec2(0.809016994375, -0.587785252292);
    const vec2 k2 = vec2(-0.809016994375, -0.587785252292);
    p.x = abs(p.x);
    p -= 2.0 * max(dot(k1, p), 0.0) * k1;
    p -= 2.0 * max(dot(k2, p), 0.0) * k2;
    p.x = abs(p.x);
    p.y -= r;
    vec2 ba = rf * vec2(-k1.y, k1.x) - vec2(0.0, 1.0);
    float h = clamp(dot(p, ba) / max(dot(ba, ba), 1.0e-6), 0.0, r);
    return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y);
}

float stSdPolygon(vec2 p, float r, float n, float phase) {
    float a = stSafeAtan(p.y, p.x) + phase;
    float sector = ST_TAU / max(n, 3.0);
    float local = mod(a + sector * 0.5, sector) - sector * 0.5;
    float apothem = r * cos(ST_PI / max(n, 3.0));
    return length(p) * cos(local) - apothem;
}

float stFilamentWave(float coord, float width, float feather) {
    float d = abs(fract(coord) - 0.5);
    float core = 1.0 - smoothstep(width, width + feather, d);
    float halo = 1.0 - smoothstep(width * 2.4, width * 2.4 + feather * 2.2, d);
    return max(core, halo * 0.36);
}

float stRuneGlyph(vec2 local, float h, float aa) {
    float strokes = 0.0;
    vec2 grid[9] = vec2[9](
        vec2(-1.0, -1.0), vec2(0.0, -1.0), vec2(1.0, -1.0),
        vec2(-1.0,  0.0), vec2(0.0,  0.0), vec2(1.0,  0.0),
        vec2(-1.0,  1.0), vec2(0.0,  1.0), vec2(1.0,  1.0)
    );
    for (int i = 0; i < 3; i++) {
        float ha = stHash11(h * 91.7 + float(i) * 13.1);
        float hb = stHash11(h * 37.3 + float(i) * 7.9 + 1.0);
        int ia = int(ha * 8.999);
        int ib = (ia + 1 + int(hb * 7.999)) % 9;
        float d = stSeg2(local, grid[ia], grid[ib]);
        strokes = max(strokes, stAaLine(d, 0.16, aa * 6.0));
    }
    return strokes;
}

float stRuneBand(float angle, float r, float inner, float outer, float count, float seed, float aa) {
    if (r < inner || r > outer) {
        return 0.0;
    }
    float t = angle / ST_TAU * count;
    float sector = floor(t);
    float sa = fract(t) * 2.0 - 1.0;
    float rb = (r - inner) / max(outer - inner, 1.0e-4) * 2.0 - 1.0;
    float h = stHash12(vec2(sector, seed));
    if (h < 0.12) {
        return 0.0;
    }
    vec2 local = vec2(sa * 1.45, rb * 1.45);
    if (abs(local.x) > 1.15 || abs(local.y) > 1.15) {
        return 0.0;
    }
    return stRuneGlyph(local, h, aa);
}

// 0. GLOW：针尖星核 + 柔晕 + 可选横向星芒
vec4 stStyleGlow(vec2 uv) {
    float r = length(uv);
    float core = exp(-r * r * max(FxParams.x, 0.5) * 4.2);
    float halo = exp(-r * max(FxParams.y, 0.5) * 2.1);
    float streak = FxParams.z * exp(-abs(uv.y) * 28.0) * (1.0 - smoothstep(0.0, 1.0, abs(uv.x)));
    float edge = 1.0 - smoothstep(0.72, 1.0, r);
    float intensity = (core * 1.25 + halo * 0.50) * edge + streak * 0.65;
    vec3 col = gLinColor * intensity + vec3(0.96, 0.99, 1.0) * (core * core * 0.85 * edge);
    float alpha = stSaturate(core * edge + halo * 0.45 * edge);
    return vec4(col, alpha);
}

// 1. RIBBON（原生适配 3D 浑天仪星环 torus 与闪电/星翼条带）：
// params: x = 管芯锐度, y = 游标刻度段数(>0) 或噪声频率(<0) 或实线(0), z = 流动速度, w = 边缘辉光
vec4 stStyleRibbon(vec2 uv) {
    float y = abs(uv.y);
    float sharpness = max(FxParams.x, 1.2);
    float core = exp(-y * sharpness);
    float glow = exp(-y * 2.2) * max(FxParams.w, 0.0);
    float edge = 1.0 - smoothstep(0.84, 1.0, y);

    float pattern = 1.0;
    if (FxParams.y > 0.5) {
        float segs = floor(FxParams.y + 0.5);
        float phase = fract(uv.x * segs - FxTime * FxParams.z);
        float dash = smoothstep(0.04, 0.14, phase) * (1.0 - smoothstep(0.68, 0.78, phase));
        float fineTick = 0.5 + 0.5 * sin(uv.x * ST_TAU * segs * 2.0);
        pattern = mix(0.28, 1.0, max(dash, fineTick * 0.45));
    } else if (FxParams.y < -0.5) {
        float n = stNoise2(vec2(uv.x * abs(FxParams.y) - FxTime * FxParams.z, uv.y * 2.0));
        pattern = 0.65 + 0.55 * n;
    }

    if (FxBlendMode > 0.5) {
        float solidMask = (1.0 - smoothstep(0.55, 0.96, y)) * (0.72 + 0.28 * pattern);
        vec3 darkCol = gLinColor * (0.35 + core * 0.65);
        return vec4(darkCol, stSaturate(solidMask));
    }

    float intensity = (core * 1.15 + glow * 0.55) * pattern * edge;
    vec3 col = gLinColor * intensity + vec3(0.95, 0.99, 1.0) * (core * core * core * 0.72 * pattern * edge);
    return vec4(col, stSaturate((core + glow * 0.5) * edge));
}

// 2. RUNE：程序化星穹法阵（六芒星 / 主浑天阵 / 爱心变体，全线性抗锯齿）
vec4 stStyleRune(vec2 uv) {
    float r = length(uv);
    if (r > 1.0) {
        return vec4(0.0);
    }
    float aa = max(gRadialWidth * 1.2, 0.0015);
    float rotation = FxParams.x;
    float seed = FxParams.y;
    float grand = step(0.5, FxParams.z);
    float reveal = FxParams.w;
    float baseAngle = stSafeAtan(uv.y, uv.x);

    float lines = 0.0;
    float detail = 0.0;
    if (FxParams.z > 1.5) {
        lines += stAaLine(r - 0.975, 0.007, aa) + stAaLine(r - 0.91, 0.003, aa) + stAaLine(r - 0.60, 0.003, aa);
        vec2 hp = stRot2(sin(rotation * 0.5) * 0.15) * uv * 2.1 + vec2(0.0, 0.62);
        lines += stAaLine(stSdHeart(hp) / 2.1, 0.006, aa) * 1.2;
        detail += (1.0 - smoothstep(-0.02, 0.0, stSdHeart(hp) / 2.1)) * 0.12;
        for (int i = 0; i < 6; i++) {
            float a = float(i) * ST_PI / 3.0 + rotation;
            vec2 c = vec2(cos(a), sin(a)) * 0.755;
            vec2 sp = (uv - c) * 9.0 + vec2(0.0, 0.5);
            lines += stAaLine(stSdHeart(sp) / 9.0, 0.004, aa);
        }
        float a2 = mod(baseAngle - rotation * 0.6 + ST_TAU, ST_TAU);
        float tick = abs(fract(a2 / ST_TAU * 40.0) - 0.5);
        detail += (1.0 - smoothstep(0.10, 0.10 + aa * 25.0, tick)) * step(0.915, r) * step(r, 0.965);
        detail += stRuneBand(mod(baseAngle + rotation * 0.8 + ST_TAU, ST_TAU), r, 0.42, 0.56, 22.0, seed, aa) * 0.8;
    } else if (grand > 0.5) {
        lines += stAaLine(r - 0.985, 0.006, aa) + stAaLine(r - 0.95, 0.003, aa);
        lines += stAaLine(r - 0.80, 0.004, aa) + stAaLine(r - 0.74, 0.003, aa);
        lines += stAaLine(r - 0.42, 0.004, aa) + stAaLine(r - 0.38, 0.0025, aa);
        float a1 = mod(baseAngle + rotation + ST_TAU, ST_TAU);
        detail += stRuneBand(a1, r, 0.81, 0.94, 48.0, seed, aa);
        float a2 = mod(baseAngle - rotation * 0.5 + ST_TAU, ST_TAU);
        float tick = abs(fract(a2 / ST_TAU * 144.0) - 0.5);
        float major = step(0.5, fract(a2 / ST_TAU * 14.4));
        detail += (1.0 - smoothstep(0.06, 0.06 + aa * 40.0, tick)) * step(0.745, r) * step(r, 0.745 + mix(0.03, 0.05, major));
        vec2 star = stRot2(-rotation * 0.7) * uv;
        float hex = min(abs(stSdPolygon(star, 0.735, 3.0, 0.0)), abs(stSdPolygon(star, 0.735, 3.0, ST_PI / 3.0)));
        lines += stAaLine(hex, 0.0035, aa);
        for (int i = 0; i < 6; i++) {
            float a = float(i) * ST_PI / 3.0 + ST_PI / 6.0 - rotation * 0.7;
            vec2 c = vec2(cos(a), sin(a)) * 0.58;
            lines += stAaLine(length(uv - c) - 0.06, 0.003, aa) * 0.8;
        }
        float a3 = mod(baseAngle - rotation * 1.6 + ST_TAU, ST_TAU);
        detail += stRuneBand(a3, r, 0.22, 0.34, 16.0, seed + 11.0, aa) * 0.9;
        lines += stAaLine(r - 0.20, 0.003, aa);
    } else {
        lines += stAaLine(r - 0.97, 0.008, aa) + stAaLine(r - 0.88, 0.004, aa);
        vec2 star = stRot2(rotation) * uv;
        float hex = min(abs(stSdPolygon(star, 0.875, 3.0, 0.0)), abs(stSdPolygon(star, 0.875, 3.0, ST_PI / 3.0)));
        lines += stAaLine(hex, 0.006, aa);
        lines += stAaLine(r - 0.44, 0.004, aa) + stAaLine(r - 0.12, 0.005, aa);
        float a1 = mod(baseAngle - rotation * 0.8 + ST_TAU, ST_TAU);
        float tick = abs(fract(a1 / ST_TAU * 72.0) - 0.5);
        detail += (1.0 - smoothstep(0.08, 0.08 + aa * 30.0, tick)) * step(0.885, r) * step(r, 0.955);
        detail += stRuneBand(mod(baseAngle + rotation * 1.3 + ST_TAU, ST_TAU), r, 0.50, 0.62, 18.0, seed, aa) * 0.8;
    }

    float sweep = (baseAngle + ST_PI) / ST_TAU;
    float visible = reveal >= 1.0 ? 1.0 : smoothstep(reveal, reveal - 0.02, sweep);
    float leading = reveal >= 1.0 ? 0.0 : exp(-abs(sweep - reveal) * 60.0) * step(0.3, r);
    float shimmer = 0.85 + 0.15 * sin(FxTime * 7.0 + baseAngle * 3.0 + r * 9.0);
    float fill = (exp(-abs(r - 0.985) * 40.0) * 0.45 + (1.0 - r) * 0.05) * visible;
    float shape = clamp(lines + detail * 0.85, 0.0, 1.6) * visible * shimmer + fill + leading * 1.5;
    if (FxBlendMode > 0.5) {
        return vec4(gLinColor * 0.35, stSaturate(shape * 0.85));
    }
    vec3 col = gLinColor * shape + vec3(0.95, 0.99, 1.0) * (stSaturate(lines) * 0.35 * visible);
    return vec4(col, stSaturate(shape));
}

// 3. RING（平面 quad，uv ∈ [-1, 1]×[-1, 1]）
vec4 stStyleRing(vec2 uv) {
    float r = length(uv);
    if (r > 1.0) {
        return vec4(0.0);
    }
    float width = max(FxParams.y, 1.0e-4);
    float d = abs(r - FxParams.x);
    float ring = exp(-(d * d) / (width * width)) + 0.30 * exp(-d / (width * 3.8));
    if (FxParams.z > 0.5) {
        float a = (stSafeAtan(uv.y, uv.x) + ST_PI) / ST_TAU;
        float f = fract(a * FxParams.z + FxTime * FxParams.w);
        float dash = smoothstep(0.0, 0.08, f) * (1.0 - smoothstep(0.55, 0.63, f));
        ring *= mix(0.22, 1.0, dash);
    }
    if (FxBlendMode > 0.5) {
        return vec4(gLinColor * 0.45, stSaturate(ring));
    }
    vec3 col = gLinColor * ring + vec3(0.95, 0.99, 1.0) * (exp(-(d * d) / (width * width * 0.22)) * 0.48);
    return vec4(col, stSaturate(ring));
}

// 4. BEAM：湍流线性能量光束（ribbon）
vec4 stStyleBeam(vec2 uv) {
    float y = abs(uv.y);
    float flow = FxTime * FxParams.y;
    float n1 = stNoise3(vec3(uv.x * FxParams.x - flow, uv.y * 2.0, 1.7));
    float n2 = stNoise3(vec3(uv.x * FxParams.x * 2.3 - flow * 1.7 + 7.0, uv.y * 4.0, 4.3));
    float turbulence = n1 * 0.65 + n2 * 0.35;
    float radial = stSaturate(y + (turbulence - 0.5) * 0.45 * FxParams.z);
    float core = stSafePow(1.0 - radial, 6.0);
    float hot = stSafePow(1.0 - radial, 16.0);
    float shell = stSafePow(1.0 - radial, 1.6) * smoothstep(0.3, 0.8, turbulence);
    float rim = exp(-abs(radial - 0.7) * 12.0) * FxParams.w;
    float edge = 1.0 - smoothstep(0.86, 1.0, y);
    float intensity = (core * 0.92 + shell * 0.58 + rim * 0.48) * edge;
    if (FxBlendMode > 0.5) {
        return vec4(gLinColor * 0.25, stSaturate(intensity * 0.85));
    }
    vec3 col = gLinColor * intensity + vec3(0.95, 0.99, 1.0) * (hot * 1.15 * edge);
    return vec4(col, stSaturate(intensity));
}

// 5. CURTAIN：极光帷幕墙（quad）
vec4 stStyleCurtain(vec2 uv) {
    float h = uv.y * 0.5 + 0.5;
    float rise = FxParams.z;
    float topFade = 1.0 - smoothstep(rise - 0.10, rise, h);
    if (topFade <= 0.0) {
        return vec4(0.0);
    }
    float x = uv.x;
    float seed = FxParams.x;
    float bands = stNoise3(vec3(x * 18.0, h * 1.5 - FxTime * 0.6, seed));
    float bands2 = stNoise3(vec3(x * 47.0 + 3.0, h * 3.0 - FxTime * 1.1, seed + 5.0));
    float streak = stSafePow(bands * 0.6 + bands2 * 0.4, 3.0) * 2.2;
    float base = exp(-h * 5.0) * 1.15;
    float crest = exp(-abs(h - rise) * 30.0) * 0.8 * step(rise, 0.995);
    float scan = stSafePow(0.5 + 0.5 * sin(h * 120.0 - FxTime * 8.0), 18.0) * 0.35 * FxParams.y;
    float gx = abs(fract(x * 16.0) - 0.5);
    float vline = (1.0 - smoothstep(0.02, 0.02 + gCurtainWidth * 1.5, gx)) * 0.25 * FxParams.y;
    float ends = 1.0 - smoothstep(0.96, 1.0, abs(x));
    float dissolve = FxParams.w > 0.0
        ? smoothstep(FxParams.w - 0.15, FxParams.w + 0.15, stNoise3(vec3(x * 9.0, h * 6.0, seed + 9.0)) * 0.8 + h * 0.2)
        : 1.0;
    float intensity = (streak * (1.0 - h * 0.6) + base + crest + scan + vline) * topFade * ends * dissolve;
    vec3 upperVioletLin = vec3(0.28, 0.14, 1.0);
    vec3 col = mix(gLinColor, upperVioletLin, h * 0.65) * intensity;
    return vec4(col, stSaturate(intensity));
}

// 6. DISK：吸积盘（quad，线性高对比）
vec4 stStyleDisk(vec2 uv) {
    float r = length(uv);
    float inner = FxParams.x;
    if (r > 1.0 || r < inner * 0.8) {
        return vec4(0.0);
    }
    vec2 q = stRot2(FxTime * FxParams.y / max(r, 0.15)) * uv;
    float bands = 0.50 + 0.50 * stNoise3(vec3(q * 7.0, r * 12.0 + FxParams.z));
    float fine = stNoise3(vec3(q * 21.0, r * 30.0 + FxParams.z));
    float profile = smoothstep(inner * 0.9, inner * 1.15, r) * stSafePow(1.0 - smoothstep(inner, 1.0, r), 1.5);
    float doppler = 1.0 + FxParams.w * sin(stSafeAtan(uv.y, uv.x));
    float hot = exp(-(r - inner) * 14.0);
    vec3 insideLin = vec3(1.0, 0.68, 0.32);
    vec3 tint = mix(insideLin, gLinColor, smoothstep(inner, 0.8, r));
    vec3 col = (tint * (bands * 0.78 + fine * 0.42) + vec3(0.96, 0.94, 0.88) * hot * 1.6) * profile * max(doppler, 0.1);
    return vec4(col, stSaturate(profile));
}

// 7. SHIELD：球面能量屏障（sphere + geoCenter）
vec4 stStyleShield(vec2 uv) {
    vec3 normal = stSurfaceNormal();
    vec3 viewDir = stSafeViewDir(relativePos);
    float ndv = stSaturate(abs(dot(normal, viewDir)));
    float rim = stSafePow(1.0 - ndv, 1.75);
    float hardRim = stSafePow(1.0 - ndv, 4.8);

    float rows = max(FxParams.x, 4.0);
    vec2 flow = stSampleFlow(vec2(uv.x * 2.0, uv.y * 1.5), 0.18);
    float cloud = texture(Sampler1, fract(uv * vec2(3.5, 2.2) + vec2(FxTime * 0.06, -FxTime * 0.08))).r;

    float latLine = stFilamentWave((uv.y + flow.y * 0.35) * rows * 1.1, 0.032, 0.024);
    float diagA = stFilamentWave((uv.x * 1.8 + uv.y * 0.85 + flow.x * 0.25) * rows * 0.65 + FxTime * 0.04, 0.028, 0.024);
    float diagB = stFilamentWave((uv.x * 1.8 - uv.y * 0.85 - flow.y * 0.25) * rows * 0.65 - FxTime * 0.035, 0.026, 0.022);
    float threads = max(latLine * 0.7, max(diagA, diagB));

    vec2 q = vec2(uv.x * rows * 2.0, uv.y * rows);
    float row = floor(q.y);
    q.x += mod(row, 2.0) * 0.5;
    vec2 cell = floor(q);
    vec2 f = fract(q);
    float d = length(vec2(f.x - 0.5, f.y * 1.15)) - 0.62;
    float scaleEdge = exp(-abs(d) * 18.0);
    float h = stHash12(cell + 7.0);
    float shimmer = 0.5 + 0.5 * sin(FxParams.z * 3.0 + h * 20.0 + uv.y * 12.0);
    float crack = 1.0 - smoothstep(FxParams.y - 0.06, FxParams.y, h);

    if (FxBlendMode > 0.5) {
        float membrane = 0.16 + cloud * 0.18 + rim * 0.45 + threads * 0.14 + scaleEdge * 0.12;
        vec3 baseCol = mix(gLinColor * 0.18, gLinColor * 0.62, cloud * 0.5 + rim * 0.5);
        baseCol += mix(gLinColor, vec3(0.95, 0.92, 1.0), 0.45) * (hardRim * 0.60 + crack * 0.35);
        return vec4(baseCol, clamp(membrane, 0.0, 0.88));
    }

    float energy = rim * (0.55 + FxParams.w * 0.85) + hardRim * (1.2 + FxParams.w * 0.8)
        + threads * (0.28 + cloud * 0.45) * (0.45 + rim)
        + scaleEdge * (0.22 + 0.35 * shimmer) * (0.35 + rim)
        + crack * (0.65 + scaleEdge * 0.7);
    vec3 col = gLinColor * energy + vec3(0.95, 0.99, 1.0) * (hardRim * 0.80 + crack * scaleEdge * 0.42 + threads * hardRim * 0.38);
    return vec4(col, stSaturate(energy));
}

// 8. SPRITE：精灵公告板（0=爱心, 1=四芒星, 2=五角星）
vec4 stStyleSprite(vec2 uv) {
    float shape = FxParams.x;
    float d;
    if (shape < 0.5) {
        d = stSdHeart(uv * 0.95 + vec2(0.0, 0.5)) / 0.95;
    } else if (shape < 1.5) {
        vec2 a = abs(uv);
        d = (sqrt(a.x) + sqrt(a.y)) - 0.75;
    } else {
        d = stSdStar5(uv * 1.05, 0.8, 0.45) / 1.05;
    }
    float fill = 1.0 - smoothstep(-0.03, 0.02, d);
    float edge = exp(-abs(d) * 28.0);
    float glow = exp(-max(d, 0.0) * 5.0) * 0.45 * (1.0 - smoothstep(0.85, 1.0, length(uv)));
    vec3 col = gLinColor * (fill * 0.65 + glow) + vec3(0.96, 0.99, 1.0) * (edge * 0.85 + fill * 0.15);
    return vec4(col, stSaturate(fill + glow));
}

// 9. PLASMA_BEAM：3D 圆柱/锥台嵌套等离子光柱（伽马轨道炮 / 磁流体喷流）
vec4 stStylePlasmaBeam(vec2 uv) {
    vec3 normal = gFaceNormal;
    vec3 viewDir = stSafeViewDir(relativePos);
    float ndv = stSaturate(abs(dot(normal, viewDir)));
    float softEdge = smoothstep(0.05, 0.48, ndv);
    float rim = stSafePow(1.0 - ndv, 2.2) * softEdge;

    float scale = max(floor(FxParams.x + 0.5), 1.0);
    float speed = FxParams.y;
    float collapse = stSaturate(FxParams.z);
    float wavePos = FxParams.w;

    vec2 offset = stSampleFlow(vec2(uv.x * scale, uv.y * 2.2 * scale), abs(speed) * 0.6 + 0.4);
    float t = FxTime * speed;
    vec4 s1 = texture(Sampler1, fract(vec2(uv.x * 3.0 * scale + offset.x, uv.y * 3.2 * scale + t * 0.48 + offset.y)));
    vec4 s2 = texture(Sampler1, fract(vec2(uv.x * 5.0 * scale - t * 0.22 - offset.y, uv.y * 6.0 * scale + t * 0.85)));

    float smokeField = stSaturate(s1.r * 0.62 + s2.g * 0.44 + abs(offset.x) * 0.55);
    float smokeGroove = smoothstep(0.56, 0.90, smokeField);
    float smokeRidge = smoothstep(0.28, 0.66, smokeField) * (1.0 - smoothstep(0.74, 0.96, smokeField));

    float streakA = smoothstep(0.65, 1.0, 0.5 + 0.5 * sin(uv.y * 20.0 * scale + t * 7.5 + (uv.x + offset.x) * ST_TAU * 3.0));
    float streakB = smoothstep(0.76, 1.0, 0.5 + 0.5 * sin(uv.y * 13.0 * scale + t * 5.2 - (uv.x - offset.y) * ST_TAU * 2.0));
    float streaks = (streakA + streakB * 0.72 + smokeRidge * 0.95) * (1.0 - smokeGroove * 0.55);

    float qWave = (uv.y - wavePos) * 5.5;
    float waveFront = exp(-(qWave * qWave));
    float endFade = smoothstep(0.0, 0.06, uv.y) * (1.0 - smoothstep(0.92, 1.0, uv.y));
    float collapseFade = stSafePow(1.0 - collapse, 1.3 + (1.0 - uv.y) * 0.4);

    if (FxBlendMode > 0.5) {
        float bodyAlpha = (0.32 + smokeField * 0.42 + smokeRidge * 0.26) * softEdge * endFade * collapseFade;
        vec3 darkShell = mix(gLinColor * 0.12, gLinColor * 0.50, smokeRidge * 0.7 + streaks * 0.3);
        darkShell *= 1.0 - smokeGroove * 0.45;
        return vec4(darkShell, clamp(bodyAlpha, 0.0, 0.92));
    }

    float energy = (0.18 + streaks * 0.62 + rim * 0.48 + waveFront * 0.55) * softEdge * endFade * collapseFade;
    float whiteHot = stSaturate(stSafePow(ndv, 2.4) * 0.45 + waveFront * 0.35 + smokeRidge * 0.25);
    vec3 col = mix(gLinColor, vec3(0.96, 0.98, 1.0), whiteHot * 0.62) * energy;
    col *= 1.0 - smokeGroove * 0.32;
    return vec4(col, stSaturate(energy));
}

// 10. REENTRY：3D 大气再入燃烧锥壳 / 沸腾火球
vec4 stStyleReentry(vec2 uv) {
    vec3 normal = stSurfaceNormal();
    vec3 viewDir = stSafeViewDir(relativePos);
    float ndv = stSaturate(abs(dot(normal, viewDir)));
    float rim = stSafePow(1.0 - ndv, 1.55);

    float front = stSaturate(1.0 - uv.y);
    float frontPower = max(FxParams.x, 0.4);
    float nScale = max(floor(FxParams.y + 0.5), 1.0);
    float speed = FxParams.z;
    float pulse = FxParams.w;

    float t = FxTime * speed;
    vec2 flow = stSampleFlow(vec2(uv.x * 2.0 * nScale, uv.y * 1.8 * nScale), speed * 0.5);
    float coarse = texture(Sampler1, fract(vec2(uv.x * 2.0 * nScale + flow.x, uv.y * 1.4 * nScale - t * 0.55))).r;
    float tongues = texture(Sampler1, fract(vec2(uv.x * 5.0 * nScale - flow.y, uv.y * 3.0 * nScale - t * 1.15))).g;
    float sharp = texture(Sampler1, fract(vec2(uv.x * 9.0 * nScale, uv.y * 5.2 * nScale - t * 1.75))).g;

    float turb = coarse * 0.46 + tongues * 0.36 + sharp * 0.18;
    float frontMask = stSafePow(front, frontPower);
    float tailFade = smoothstep(0.0, 0.32, front);
    float lick = smoothstep(0.32, 0.88, turb + front * 0.28 + rim * 0.16);
    float qShock = (front - (0.76 + pulse * 0.08)) * 12.0;
    float shockRing = exp(-(qShock * qShock));
    float noseHot = front * front * front * (0.72 + turb * 0.48);

    if (FxBlendMode > 0.5) {
        float a = clamp(frontMask * (0.28 + lick * 0.65) + rim * tailFade * 0.42, 0.0, 0.90);
        vec3 ember = mix(gLinColor * 0.10, gLinColor * 0.48, turb * 0.8 + front * 0.2);
        return vec4(ember, a);
    }

    float energy = frontMask * (0.22 + lick * 0.72 + noseHot * 0.55) + rim * tailFade * (0.35 + lick * 0.75) + shockRing * 0.55;
    vec3 col = mix(gLinColor * 0.42, gLinColor, stSaturate(turb * 1.15 + front * 0.25));
    col = mix(col, vec3(1.0, 0.92, 0.76), stSaturate(noseHot * 0.60 + shockRing * 0.42));
    return vec4(col * energy, stSaturate(energy));
}

// 11. SLASH_ARC：3D 定向曲面锋刃刀光弧（宇宙弦切 / 剑气）
vec4 stStyleSlashArc(vec2 uv) {
    float headReveal = stSaturate(FxParams.z);
    if (uv.x > headReveal) {
        return vec4(0.0);
    }
    float tailClip = clamp(FxParams.w, 0.0, 0.95);
    if (uv.x < tailClip) {
        return vec4(0.0);
    }
    float along = stSaturate((uv.x - tailClip) / max(headReveal - tailClip, 0.05));

    vec4 n1 = texture(Sampler1, fract(vec2(uv.x * 2.4 - FxTime * 1.8, uv.y * 1.6)));
    vec4 n2 = texture(Sampler1, fract(vec2(uv.x * 6.0 - FxTime * 3.1, uv.y * 3.5 + n1.b * 0.2)));
    float noise = mix(1.0, n1.r * 0.55 + n2.g * 0.55, stSaturate(FxParams.y));

    float outerCut = 1.0 - smoothstep(0.86, 1.0, uv.y);
    float innerFade = smoothstep(0.05, 0.55 + (1.0 - along) * 0.35, uv.y + (noise - 0.5) * 0.35);
    float tipFade = smoothstep(0.0, 0.12, along) * (1.0 - smoothstep(0.92, 1.0, along) * 0.35);
    float bladeBody = outerCut * innerFade * tipFade;

    float sharpness = max(FxParams.x, 2.0);
    float qEdge = abs(uv.y - 0.84) * sharpness;
    float edgeLine = exp(-(qEdge * qEdge)) * tipFade;
    float streak = smoothstep(0.45, 0.92, n2.g) * bladeBody * (0.4 + 0.6 * along);

    if (FxBlendMode > 0.5) {
        float bodyMask = clamp(bladeBody * (0.55 + 0.45 * noise), 0.0, 0.94);
        vec3 darkCol = gLinColor * (0.14 + edgeLine * 0.38);
        return vec4(darkCol, bodyMask);
    }

    float energy = (edgeLine * 1.35 + bladeBody * (0.35 + streak * 0.85)) * (0.45 + 0.55 * along);
    vec3 col = gLinColor * energy + vec3(0.96, 0.99, 1.0) * (edgeLine * edgeLine * 1.05);
    return vec4(col, stSaturate(energy));
}

// 12. SHOCK_CONE：3D 喇叭形多层马赫冲击波锥
vec4 stStyleShockCone(vec2 uv) {
    vec3 normal = gFaceNormal;
    vec3 viewDir = stSafeViewDir(relativePos);
    float ndv = stSaturate(abs(dot(normal, viewDir)));
    float softEdge = smoothstep(0.03, 0.35, ndv);

    float progress = stSaturate(FxParams.x);
    float speed = FxParams.y;
    float tear = FxParams.z;
    float throat = FxParams.w;

    float nA = texture(Sampler1, fract(vec2(uv.x * 4.0 + FxTime * 0.35, uv.y * 2.2 - FxTime * speed * 0.5))).r;
    float nB = texture(Sampler1, fract(vec2(uv.x * 8.0 - FxTime * 0.5, uv.y * 4.0 - FxTime * speed * 0.85))).g;
    float shiftedY = fract(uv.y - FxTime * speed * 0.45 + (nA - 0.5) * 0.16 * tear);

    float w1 = 1.0 - smoothstep(0.03, 0.13, abs(shiftedY - 0.22));
    float w2 = (1.0 - smoothstep(0.03, 0.12, abs(fract(shiftedY + 0.34) - 0.22))) * 0.78;
    float w3 = (1.0 - smoothstep(0.02, 0.10, abs(fract(shiftedY + 0.67) - 0.22))) * 0.52;
    float bands = (w1 + w2 + w3) * (0.55 + 0.65 * nB);

    float qFront = (uv.y - progress) * 7.5;
    float frontWave = exp(-(qFront * qFront));
    float endFade = smoothstep(0.0, 0.08, uv.y) * (1.0 - smoothstep(0.75, 1.0, uv.y));
    float throatGlow = exp(-uv.y * 6.0) * throat;

    float energy = (bands * 0.72 + frontWave * 1.15 + throatGlow * 0.85) * endFade * softEdge;
    if (FxBlendMode > 0.5) {
        return vec4(gLinColor * 0.20, clamp(energy * 0.65, 0.0, 0.82));
    }
    vec3 col = mix(gLinColor, vec3(0.96, 0.98, 1.0), stSaturate(frontWave * 0.52 + throatGlow * 0.42)) * energy;
    return vec4(col, stSaturate(energy));
}

// 13. CORONA：360° 三平面无缝沸腾日冕 / 日蚀球壳
vec4 stStyleCorona(vec2 uv) {
    vec3 normal = stSurfaceNormal();
    vec3 viewDir = stSafeViewDir(relativePos);
    float ndv = stSaturate(abs(dot(normal, viewDir)));
    float rimPower = max(FxParams.x, 1.0);
    float contrast = max(FxParams.y, 0.5);
    float t = FxTime * FxParams.z;
    float pulse = FxParams.w;

    vec3 an = abs(normal);
    vec3 w = an * an * an;
    w /= max(w.x + w.y + w.z, 1.0e-4);
    vec4 sXY = texture(Sampler1, fract(normal.xy * 0.85 + vec2(t * 0.16, -t * 0.11)));
    vec4 sYZ = texture(Sampler1, fract(normal.yz * 0.85 + vec2(-t * 0.13, t * 0.19)));
    vec4 sXZ = texture(Sampler1, fract(normal.xz * 0.85 + vec2(t * 0.11, t * 0.15)));
    vec4 baseSample = sXY * w.z + sYZ * w.x + sXZ * w.y;

    float rim = stSafePow(1.0 - ndv, rimPower);
    float hardRim = stSafePow(1.0 - ndv, rimPower * 2.3);
    if (FxBlendMode > 0.5) {
        float alpha = stSaturate(0.86 + (1.0 - baseSample.r) * 0.12 + rim * 0.18);
        vec3 darkCore = gLinColor * (0.14 + baseSample.g * 0.30 * pulse + rim * 0.42);
        return vec4(darkCore, alpha);
    }
    float energy = rim * (0.60 + baseSample.r * contrast * 0.85) + hardRim * (1.15 + pulse * 0.8);
    vec3 col = mix(gLinColor, vec3(0.96, 0.99, 1.0), stSaturate(hardRim * 0.65)) * energy;
    return vec4(col, stSaturate(energy));
}

// 14. AFTERIMAGE：全息残影 / 晶化线框
vec4 stStyleAfterimage(vec2 uv) {
    vec2 a = abs(uv);
    float edge = max(a.x, a.y);
    float lineWidth = max(gRadialWidth * 2.4, 0.045) * max(FxParams.x, 0.1);
    float edgeLine = smoothstep(1.0 - lineWidth * 2.2, 1.0 - lineWidth * 0.3, edge);
    vec3 viewDir = stSafeViewDir(relativePos);
    float ndv = stSaturate(abs(dot(gFaceNormal, viewDir)));
    float rim = stSafePow(1.0 - ndv, 2.2);
    float scan = smoothstep(0.55, 1.0, 0.5 + 0.5 * sin(relativePos.y * FxParams.z * ST_TAU - FxTime * 9.0));
    float noise = texture(Sampler1, fract(uv * 0.23 + vec2(relativePos.x + relativePos.z, relativePos.y) * 0.17)).g;
    float dissolve = FxParams.w;
    float alive = smoothstep(dissolve - 0.02, dissolve + 0.02, noise);
    if (alive <= 0.001) {
        return vec4(0.0);
    }
    float burn = dissolve > 0.001 ? (1.0 - smoothstep(dissolve, dissolve + 0.12, noise)) : 0.0;
    if (FxBlendMode > 0.5) {
        return vec4(gLinColor * 0.20, stSaturate(0.24 + rim * 0.32 + FxParams.y) * alive);
    }
    float intensity = edgeLine * 1.45 + rim * 0.80 + FxParams.y + scan * 0.20;
    vec3 col = gLinColor * intensity * alive + vec3(0.96, 0.99, 1.0) * (edgeLine * edgeLine * 0.55 + burn * 2.2) * alive;
    return vec4(col, stSaturate(intensity * alive));
}

// 15. VOID_SLIT：虚空裂口 / 维度直刃切口
float stSlitHalfWidth(float y, float open, float jag, float variant) {
    float ay = abs(y);
    float profile = variant < 0.5 ? stSafePow(max(1.0 - ay * ay, 0.0), 0.75) : (1.0 - smoothstep(0.82, 1.0, ay));
    float ridge = texture(Sampler1, fract(vec2(0.13 + variant * 0.37, y * 0.45 + FxTime * 0.07))).g;
    float fine = stNoise2(vec2(y * 23.0 + FxTime * 3.0, variant * 3.1));
    float tear = (ridge - 0.5) * 0.7 + (fine - 0.5) * 0.3;
    return max(0.0, 0.5 * open * profile + tear * jag * 0.18 * open * sqrt(max(profile, 0.0)));
}

vec4 stStyleVoidSlit(vec2 uv) {
    float open = FxParams.x;
    float variant = FxParams.z;
    float w = stSlitHalfWidth(uv.y, open, FxParams.y, variant);
    float d = abs(uv.x) - w;
    float aa = max(gRadialWidth * 1.5, 0.004);
    float inside = 1.0 - smoothstep(-aa, aa, d);
    float endFade = 1.0 - smoothstep(0.90, 1.0, abs(uv.y));

    if (FxBlendMode > 0.5) {
        float depth01 = w > 1.0e-4 ? stSaturate(1.0 - abs(uv.x) / w) : 0.0;
        vec3 cavity = mix(vec3(0.015, 0.004, 0.032), vec3(0.0004, 0.0002, 0.0018), sqrt(depth01));
        return vec4(cavity, inside * 0.98 * endFade);
    }

    float rimWidth = 0.022 + 0.02 * FxParams.y;
    float rim = exp(-abs(d) / rimWidth);
    float innerHot = exp(-max(-d, 0.0) / 0.012) * inside;
    float halo = exp(-max(d, 0.0) / 0.18) * (1.0 - inside) * 0.35;
    vec3 col = gLinColor * (rim * 1.4 + halo) + mix(gLinColor, vec3(0.96, 0.99, 1.0), 0.65) * innerHot * 0.9;
    float alpha = rim + halo + innerHot;

    if (inside > 0.0) {
        vec2 grid = vec2(uv.x * 14.0, uv.y * 9.0 + FxTime * 0.15);
        vec2 cell = floor(grid);
        vec2 f = fract(grid) - 0.5;
        float h = stHash12(cell);
        float threshold = variant < 0.5 ? 0.82 : mix(0.95, 0.72, stSaturate(FxParams.w));
        float star = step(threshold, h) * exp(-dot(f, f) * 60.0) * (0.5 + 0.5 * sin(FxTime * 5.0 + h * 40.0));
        col += vec3(0.65, 0.88, 1.0) * star * inside * 0.85;
        alpha += star * inside * 0.5;
        if (variant < 0.5 && FxParams.w > 0.0) {
            float eye = FxParams.w;
            vec2 e = vec2(uv.x / max(w, 0.05), uv.y / 0.38);
            float r = length(e);
            float iris = exp(-abs(r - 0.62 * eye) / 0.05) * eye;
            float pupil = (1.0 - smoothstep(0.035 * eye, 0.055 * eye, abs(uv.x))) * (1.0 - smoothstep(0.24 * eye, 0.30 * eye, abs(uv.y)));
            vec3 goldLin = vec3(1.0, 0.54, 0.10);
            col += goldLin * (iris * 1.2 + pupil * 2.0) * inside;
            alpha += (iris + pupil) * inside;
        }
    }
    return vec4(col * endFade, stSaturate(alpha * endFade));
}

// 16. CRYSTAL_SHARD：3D 黑曜棱晶碎刃
vec4 stStyleCrystalShard(vec2 uv) {
    vec3 normal = gFaceNormal;
    vec3 viewDir = stSafeViewDir(relativePos);
    float ndv = stSaturate(abs(dot(normal, viewDir)));
    float rimPower = max(FxParams.x, 1.2);
    float flowSpeed = FxParams.y;
    float overload = stSaturate(FxParams.z);
    float dissolve = stSaturate(FxParams.w);

    vec2 veinUv = fract(vec2(uv.x * 1.4 - FxTime * flowSpeed * 0.45, uv.y * 1.8 + FxTime * flowSpeed * 0.20));
    vec4 flowSample = texture(Sampler1, veinUv);
    float veinMask = smoothstep(0.56, 0.92, flowSample.g);
    float alive = 1.0;
    float burn = 0.0;
    if (dissolve > 0.001) {
        float metric = flowSample.r * 0.65 + (0.5 - uv.x * 0.35);
        alive = smoothstep(dissolve - 0.04, dissolve + 0.04, metric);
        if (alive <= 0.001) {
            return vec4(0.0);
        }
        burn = (1.0 - smoothstep(dissolve, dissolve + 0.16, metric)) * alive;
    }

    float borderMetric = abs(uv.x) + abs(uv.y);
    float outerEdge = smoothstep(0.76, 0.995, borderMetric);
    float fineEdge = smoothstep(0.90, 1.0, borderMetric);
    float spine = exp(-abs(uv.y) * 10.5) * (1.0 - abs(uv.x) * 0.30);
    float tipFocus = smoothstep(0.28, 1.0, uv.x);

    vec3 keyDir = normalize(vec3(0.45, 0.82, 0.35));
    float keyLight = stSaturate(abs(dot(normal, keyDir)));
    float facet = 0.25 + 0.75 * stSafePow(keyLight, 1.6);
    float rim = stSafePow(1.0 - ndv, rimPower);
    float hardRim = stSafePow(1.0 - ndv, rimPower * 2.1);

    if (FxBlendMode > 0.5) {
        vec3 baseDark = gLinColor * (0.14 + facet * 0.32);
        vec3 rimTint = mix(gLinColor, vec3(0.55, 0.76, 1.0), 0.35);
        vec3 col = baseDark + rimTint * (outerEdge * 0.22 + veinMask * (0.12 + 0.26 * overload));
        float outA = clamp((0.88 + outerEdge * 0.10 + rim * 0.08) * alive, 0.0, 0.98);
        return vec4(col, outA);
    }

    float pulse = 1.0 + overload * (0.45 + 0.35 * sin(FxTime * 22.0 + uv.x * 6.0));
    float bodyGlow = (0.14 + facet * 0.32 + rim * 0.65) * pulse;
    float edgeGlow = (outerEdge * 0.88 + spine * 0.42 + tipFocus * 0.48 + veinMask * (0.35 + 0.75 * overload)) * pulse;
    float whiteHot = clamp(fineEdge * 0.65 + hardRim * 0.55 + tipFocus * fineEdge * 0.60 + overload * 0.38 + burn * 1.4, 0.0, 1.5);
    vec3 col = gLinColor * (bodyGlow + edgeGlow) + mix(gLinColor, vec3(0.96, 0.99, 1.0), 0.80) * whiteHot;
    float alpha = stSaturate((bodyGlow + edgeGlow + whiteHot * 0.5) * alive);
    return vec4(col * alive, alpha);
}

void main() {
    // 全部导数必须在任何 discard 之前求取，并用无偏长度平方判零杜绝退化三角面 NaN
    vec3 rawN = cross(dFdx(relativePos), dFdy(relativePos));
    float rawLen2 = dot(rawN, rawN);
    gFaceNormal = (rawLen2 > 1.0e-12) ? (rawN * inversesqrt(rawLen2)) : vec3(0.0, 1.0, 0.0);
    gRadialWidth = fwidth(length(texCoord0));
    gCurtainWidth = fwidth(texCoord0.x);
    gLinColor = stSrgbToLinear(vertexColor.rgb);

    float mask = stDepthMask() * stOccluderMask() * stReliefMask();
    if (mask <= 0.0 || vertexColor.a <= 0.0) {
        discard;
    }

    int styleId = int(floor(FxStyle + 0.5));
    vec4 res;
    if (styleId == 0)       res = stStyleGlow(texCoord0);
    else if (styleId == 1)  res = stStyleRibbon(texCoord0);
    else if (styleId == 2)  res = stStyleRune(texCoord0);
    else if (styleId == 3)  res = stStyleRing(texCoord0);
    else if (styleId == 4)  res = stStyleBeam(texCoord0);
    else if (styleId == 5)  res = stStyleCurtain(texCoord0);
    else if (styleId == 6)  res = stStyleDisk(texCoord0);
    else if (styleId == 7)  res = stStyleShield(texCoord0);
    else if (styleId == 8)  res = stStyleSprite(texCoord0);
    else if (styleId == 9)  res = stStylePlasmaBeam(texCoord0);
    else if (styleId == 10) res = stStyleReentry(texCoord0);
    else if (styleId == 11) res = stStyleSlashArc(texCoord0);
    else if (styleId == 12) res = stStyleShockCone(texCoord0);
    else if (styleId == 13) res = stStyleCorona(texCoord0);
    else if (styleId == 14) res = stStyleAfterimage(texCoord0);
    else if (styleId == 15) res = stStyleVoidSlit(texCoord0);
    else                    res = stStyleCrystalShard(texCoord0);

    vec3 safeRgb = max(stSanitize(res.rgb), vec3(0.0));
    float safeA = (isnan(res.a) || isinf(res.a)) ? 0.0 : res.a;
    if (FxBlendMode > 0.5) {
        float outAlpha = stSaturate(safeA * vertexColor.a * mask);
        if (outAlpha <= 0.002) {
            discard;
        }
        fragColor = vec4(safeRgb * FxEmission * outAlpha, outAlpha);
    } else {
        fragColor = vec4(safeRgb * vertexColor.a * FxEmission * mask, 1.0);
    }
}
