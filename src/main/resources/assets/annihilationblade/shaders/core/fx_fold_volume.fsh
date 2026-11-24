#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 因果坍缩 · 裂界同源 3D 体积时空裂痕通道（单 Pass 最多打包 8 处局部裂痕）：
//  1. 全解析纺锤状 3D 空间引力畸变壳（射线-椭球求交）：在裂痕四周浮现缓慢旋转的虚空紫/品红螺旋流纹，
//     并在壳体切入草地、树干与墙体处烙出锐利的品红/兰白空间截交刃线；
//  2. 严格沿弯曲锯齿中脊锁步的浅 V 型 3D 裂隙折面（修正两侧唇面锯齿同向弯折数学）；
//  3. 初裂 & 合缝归位瞬间的沿脊锋芒与奇点星闪。
uniform sampler2D Sampler1; // 场景原始深度（R32F）
uniform mat4 InvProj;
uniform mat4 InvView;
uniform float FxTime;
// 每个 mat4 打包 1 处 3D 时空裂痕：
//   列 0 = (relX, relY, relZ, halfHeight)
//   列 1 = (spineX, spineY, spineZ, crackOpen)
//   列 2 = (normalX, normalY, normalZ, signedSwirlIntensity)
//   列 3 = (seedPhase, openFlash, alignGlint, 0)
uniform mat4 ScarA;
uniform mat4 ScarB;
uniform mat4 ScarC;
uniform mat4 ScarD;
uniform mat4 ScarE;
uniform mat4 ScarF;
uniform mat4 ScarG;
uniform mat4 ScarH;
// x = 活跃裂痕数(0..8), y = 全局亮度倍率, z/w = 未用
uniform vec4 Meta;

in vec2 texCoord;
out vec4 fragColor;

vec3 rotateAround(vec3 v, vec3 axis, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

float evalJag(float u, float seed) {
    return sin(u * 3.8 + seed) * 0.042
        + sin(u * 10.5 + seed * 2.1) * 0.018
        + cos(u * 21.0 + seed * 3.3) * 0.008;
}

vec3 evalSpindleShellHit(
    vec3 pLoc, vec3 rdLocal, float tHit, float sceneT, float rx, float ry,
    float spinRate, float intensity, float seed
) {
    if (tHit <= 0.04 || tHit >= sceneT) {
        return vec3(0.0);
    }
    vec3 inv2 = vec3(1.0 / max(rx * rx, 0.01), 1.0 / max(ry * ry, 0.01), 1.0 / max(rx * rx, 0.01));
    vec3 n = normalize(pLoc * inv2);
    float ndv = clamp(abs(dot(n, rdLocal)), 0.0, 1.0);
    float fresnel = pow(max(1.0 - ndv, 1.0e-4), 2.3);
    // 掠射轮廓淡出，防止 3D 引力椭球外壳外边缘出现硬截断线
    float shellFade = smoothstep(0.0, 0.34, ndv);

    // 纺锤壳面绕脊线螺旋卷吸的虚空丝纹
    float az = atan(pLoc.z, pLoc.x);
    float el = clamp(pLoc.y / max(ry, 0.1), -1.0, 1.0);
    float endFade = pow(max(1.0 - el * el, 1.0e-4), 0.75);
    float spiralA = 0.5 + 0.5 * sin(az * 3.0 + el * 6.5 - FxTime * spinRate + seed);
    float spiralB = 0.5 + 0.5 * sin(az * 5.0 - el * 4.0 + FxTime * spinRate * 0.72 - seed * 0.5);
    float filaments = (pow(max(spiralA, 1.0e-4), 5.0) * 0.78 + pow(max(spiralB, 1.0e-4), 6.0) * 0.46) * endFade;

    // 纺锤空间畸变壳切入地形方块时的锐利品红-兰白截交刃线（同受轮廓柔化避免掠射硬边）
    float terrainCut = exp(-max(sceneT - tHit, 0.0) * 3.6) * smoothstep(0.0, 0.22, ndv);

    vec3 deepViolet = vec3(0.46, 0.12, 0.94);
    vec3 kamuiMagenta = vec3(0.96, 0.24, 0.98);
    vec3 hotOrchid = vec3(1.0, 0.80, 0.99);

    vec3 col = mix(deepViolet, kamuiMagenta, clamp(fresnel * 0.65 + filaments * 0.55, 0.0, 1.0))
        * (fresnel * 0.28 + filaments * (0.22 + 0.78 * fresnel)) * endFade * shellFade;
    col += mix(kamuiMagenta, hotOrchid, 0.45) * terrainCut * 0.88;
    return col * intensity;
}

vec3 evalFractureLip(
    vec3 center, vec3 spine, vec3 acrossBase, vec3 lipNormal, float halfH, float lipWidth,
    float crackOpen, float seed, float sideSign, vec3 rd, float sceneT
) {
    float denom = dot(rd, lipNormal);
    if (abs(denom) < 1.0e-4) {
        return vec3(0.0);
    }
    float tHit = dot(center, lipNormal) / denom;
    if (tHit <= 0.05 || tHit >= sceneT) {
        return vec3(0.0);
    }

    vec3 local = rd * tHit - center;
    float u = dot(local, spine) / max(halfH, 0.25);
    if (abs(u) > 1.03) {
        return vec3(0.0);
    }

    float taper = pow(max(1.0 - u * u, 1.0e-5), 0.72);
    if (taper <= 0.01) {
        return vec3(0.0);
    }

    // 与世界空间 acrossBase 锁步的三频锯齿脊线：左右唇面沿同一方向弯折，杜绝反向错层
    float jagWorld = evalJag(u, seed) * halfH * taper;
    float acrossSigned = dot(local, acrossBase) - jagWorld;
    float distOut = acrossSigned * sideSign;
    float v = distOut / max(lipWidth, 0.10);
    float maxV = (0.32 + 0.68 * taper) * (0.48 + 0.52 * crackOpen);
    if (v < 0.0 || v > maxV) {
        return vec3(0.0);
    }

    float lipEdge = exp(-v / max(0.14 * maxV, 0.04)) * taper;
    float outerFacet = smoothstep(maxV * 0.72, maxV * 0.98, v) * taper;
    float fresnel = pow(max(1.0 - abs(denom), 1.0e-4), 2.3);
    float shimmer = 0.65 + 0.35 * sin(u * 16.0 - FxTime * 6.5 * sideSign + seed * 2.1);
    float terrainCut = exp(-max(sceneT - tHit, 0.0) * 4.8) * taper;

    vec3 deepViolet = vec3(0.38, 0.09, 0.88);
    vec3 kamuiMagenta = vec3(0.95, 0.22, 0.98);
    vec3 hotOrchid = vec3(1.0, 0.82, 0.99);
    vec3 prismCyan = vec3(0.34, 0.90, 1.0);

    vec3 col = deepViolet * (0.06 + 0.20 * fresnel) * taper;
    col += mix(kamuiMagenta, hotOrchid, 0.45) * lipEdge * 0.74 * shimmer;
    col += prismCyan * outerFacet * 0.28;
    col += mix(kamuiMagenta, hotOrchid, 0.35) * terrainCut * 1.05;
    return col;
}

vec3 evalCausalScarVolume(mat4 s, vec3 rd, float sceneT) {
    float halfH = s[0].w;
    float signedIntensity = s[2].w;
    float intensity = abs(signedIntensity);
    float crackOpen = s[1].w;
    if (halfH <= 0.05 || (intensity <= 1.0e-4 && crackOpen <= 1.0e-4)) {
        return vec3(0.0);
    }

    vec3 center = s[0].xyz;
    float boundR = halfH * 1.45;
    float tc = dot(center, rd);
    if (tc < -boundR || tc - boundR > sceneT) {
        return vec3(0.0);
    }
    vec3 perp = center - rd * tc;
    if (dot(perp, perp) > boundR * boundR) {
        return vec3(0.0);
    }

    vec3 spine = normalize(s[1].xyz);
    vec3 faceN = normalize(s[2].xyz - spine * dot(s[2].xyz, spine));
    vec3 acrossBase = normalize(cross(faceN, spine));
    float spinSign = signedIntensity >= 0.0 ? 1.0 : -1.0;
    float seed = s[3].x;
    float openFlash = clamp(s[3].y, 0.0, 1.5);
    float alignGlint = clamp(s[3].z, 0.0, 1.5);

    vec3 acc = vec3(0.0);

    // 1. 纺锤形 3D 空间引力畸变壳（裂界同源旋流壳 + 地形切痕）
    float shellRx = halfH * 0.72 * (0.45 + 0.55 * clamp(crackOpen, 0.0, 1.2));
    float shellRy = halfH * 1.18 * (0.65 + 0.35 * clamp(crackOpen, 0.0, 1.2));
    vec3 cLocal = vec3(dot(center, acrossBase), dot(center, spine), dot(center, faceN));
    vec3 rdLocal = vec3(dot(rd, acrossBase), dot(rd, spine), dot(rd, faceN));
    vec3 invRadii = vec3(1.0 / max(shellRx, 0.08), 1.0 / max(shellRy, 0.12), 1.0 / max(shellRx, 0.08));
    vec3 rdScaledRaw = rdLocal * invRadii;
    float rdLen = length(rdScaledRaw);
    if (rdLen > 1.0e-5 && intensity > 1.0e-3) {
        vec3 rdScaled = rdScaledRaw / rdLen;
        vec2 hitS = fxSphere(rdScaled, cLocal * invRadii, 1.0);
        if (hitS.y > 0.0) {
            float tNear = hitS.x / rdLen;
            float tFar = hitS.y / rdLen;
            float spinRate = 5.2 * spinSign;
            acc += evalSpindleShellHit(rdLocal * tNear - cLocal, rdLocal, tNear, sceneT, shellRx, shellRy, spinRate, intensity * 0.85, seed);
            acc += evalSpindleShellHit(rdLocal * tFar - cLocal, rdLocal, tFar, sceneT, shellRx, shellRy, -spinRate * 0.8, intensity * 0.42, seed + 2.7);
        }
    }

    // 2. 沿弯曲锯齿脊线锁步的浅 V 型 3D 裂隙唇面
    if (crackOpen > 0.01) {
        float dihedral = 0.44;
        vec3 lipN0 = rotateAround(faceN, spine, dihedral);
        vec3 lipN1 = rotateAround(faceN, spine, -dihedral);
        float lipWidth = halfH * 0.34;
        acc += evalFractureLip(center, spine, acrossBase, lipN0, halfH, lipWidth, crackOpen, seed, 1.0, rd, sceneT) * intensity;
        acc += evalFractureLip(center, spine, acrossBase, lipN1, halfH, lipWidth, crackOpen, seed, -1.0, rd, sceneT) * intensity;
    }

    // 3. 初裂或合缝瞬间的沿脊破空冷芒 + 奇点十字星闪
    float flashPeak = max(openFlash, alignGlint * 0.9);
    if (flashPeak > 0.005 && tc > 0.08 && tc < sceneT) {
        float along = dot(perp, spine);
        vec3 crossVec = perp - spine * along;
        float dSpine = length(crossVec);
        float lenMask = exp(-abs(along) / max(halfH * 1.15, 0.4));
        float sharp = exp(-dSpine / max(halfH * 0.048, 0.035));
        vec3 flashCol = openFlash >= alignGlint
            ? mix(vec3(0.96, 0.24, 0.98), vec3(1.0, 0.92, 1.0), 0.55)
            : mix(vec3(0.45, 0.90, 1.0), vec3(1.0, 0.86, 1.0), 0.50);
        acc += flashCol * sharp * lenMask * flashPeak * 1.35;
    }

    return acc;
}

void main() {
    int count = int(Meta.x + 0.5);
    float globalScale = Meta.y;
    if (count <= 0 || globalScale <= 1.0e-4) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 uv = texCoord;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float depth = texture(Sampler1, uv).r;
    float sceneT = fxSceneDistance(depth, uv, InvProj);

    vec3 acc = vec3(0.0);
    if (count > 0) acc += evalCausalScarVolume(ScarA, rd, sceneT);
    if (count > 1) acc += evalCausalScarVolume(ScarB, rd, sceneT);
    if (count > 2) acc += evalCausalScarVolume(ScarC, rd, sceneT);
    if (count > 3) acc += evalCausalScarVolume(ScarD, rd, sceneT);
    if (count > 4) acc += evalCausalScarVolume(ScarE, rd, sceneT);
    if (count > 5) acc += evalCausalScarVolume(ScarF, rd, sceneT);
    if (count > 6) acc += evalCausalScarVolume(ScarG, rd, sceneT);
    if (count > 7) acc += evalCausalScarVolume(ScarH, rd, sceneT);

    fragColor = vec4(acc * globalScale, 1.0);
}
