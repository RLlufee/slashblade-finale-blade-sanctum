#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 裂界 · 万花筒神威解析体积通道（最多 4 个 3D 奇点）：
//  · 解析射线-球求交呈现向心旋转的虚空紫/品红空间引力壳与螺旋流纹，零步进噪点闪烁；
//  · 球壳切入地表/墙体处点亮锐利品红空间截交刃环；
//  · 奇点事件视界内部严格置黑（0 发光），仅在外沿刻画细锐品红光环与拧断脉冲星芒。
uniform sampler2D Sampler1; // 场景原始深度（R32F）
uniform mat4 InvProj;
uniform mat4 InvView;
uniform float FxTime;
// 每个 mat4 打包 2 个 3D 体积奇点：
//   列 0 / 列 2 = (relX, relY, relZ, outerRadius)
//   列 1 / 列 3 = (spinRate, intensity, holeRadius, snapPulse)
uniform mat4 VolA;
uniform mat4 VolB;
// x = 奇点数(0..4), y = 全局倍率, z = 0, w = 0
uniform vec4 Meta;

in vec2 texCoord;
out vec4 fragColor;

vec3 evalKamuiShell(vec3 rd, vec3 center, float radius, float tHit, float sceneT, float spinRate, float intensity) {
    if (tHit <= 0.03 || tHit >= sceneT) {
        return vec3(0.0);
    }
    vec3 local = rd * tHit - center;
    vec3 n = local / max(radius, 0.001);
    float ndv = clamp(abs(dot(n, rd)), 0.0, 1.0);
    float fresnel = pow(max(1.0 - ndv, 1.0e-4), 2.8);

    // 球面三臂对数螺旋旋流
    float az = atan(n.z, n.x);
    float el = asin(clamp(n.y, -1.0, 1.0));
    float spiralA = 0.5 + 0.5 * sin(az * 3.0 + el * 6.5 - FxTime * spinRate);
    float spiralB = 0.5 + 0.5 * sin(az * 5.0 - el * 4.0 + FxTime * spinRate * 0.72);
    float filaments = pow(spiralA, 5.0) * 0.75 + pow(spiralB, 6.0) * 0.45;

    // 空间畸变球切入方块地形时的锐利品红截交线
    float terrainCut = exp(-max(sceneT - tHit, 0.0) * 3.2);
    vec3 violet = vec3(0.48, 0.14, 0.96);
    vec3 magenta = vec3(0.96, 0.24, 0.98);
    vec3 hot = vec3(1.0, 0.84, 0.99);

    vec3 col = mix(violet, magenta, clamp(fresnel * 0.7 + filaments * 0.5, 0.0, 1.0)) * (fresnel * 0.42 + filaments * (0.26 + 0.74 * fresnel));
    col += mix(magenta, hot, 0.45) * terrainCut * 0.95;
    return col * intensity;
}

vec3 evalKamuiVolume(vec4 pos, vec4 prm, vec3 rd, float sceneT) {
    float radius = pos.w;
    float intensity = prm.y;
    if (radius <= 0.05 || intensity <= 1.0e-4) {
        return vec3(0.0);
    }

    vec3 center = pos.xyz;
    float spinRate = prm.x;
    float holeR = max(prm.z, 0.08);
    float snapPulse = clamp(prm.w, 0.0, 1.5);
    vec3 acc = vec3(0.0);

    // 1. 解析外空间漏斗壳（前后两面交点 + 地形截交线）
    vec2 hit = fxSphere(rd, center, radius);
    if (hit.y > 0.0) {
        vec3 nearHit = evalKamuiShell(rd, center, radius, hit.x, sceneT, spinRate, intensity);
        vec3 farHit = evalKamuiShell(rd, center, radius, hit.y, sceneT, -spinRate * 0.85, intensity * 0.48);
        acc += nearHit + farHit;
    }

    // 2. 奇点事件视界锐利外环（严格保留中心纯黑，并受 sceneT 遮挡）
    float tc = dot(center, rd);
    if (tc > 0.05 && tc < sceneT) {
        float dRay = length(center - rd * tc);
        float outsideHole = smoothstep(holeR * 0.82, holeR * 1.02, dRay);
        float ringEdge = exp(-abs(dRay - holeR * 1.08) / max(holeR * 0.24, 0.06));
        float softAura = exp(-pow(max(dRay - holeR, 0.0) / max(holeR * 1.85, 0.25), 2.0)) * 0.28;

        // 拧断瞬间沿奇点放射一圈紧凑紫白星芒
        vec3 rightAxis = normalize(cross(abs(rd.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), rd));
        vec3 upAxis = cross(rd, rightAxis);
        vec3 perp = (center - rd * tc);
        float px = dot(perp, rightAxis);
        float py = dot(perp, upAxis);
        float flareH = exp(-abs(py) / max(holeR * 0.16, 0.035)) * exp(-abs(px) / max(holeR * 4.2, 0.6));
        float flareV = exp(-abs(px) / max(holeR * 0.16, 0.035)) * exp(-abs(py) / max(holeR * 3.0, 0.45));

        vec3 rimCol = mix(vec3(0.92, 0.20, 0.98), vec3(1.0, 0.86, 1.0), clamp(ringEdge * 0.55 + snapPulse * 0.45, 0.0, 1.0));
        acc += rimCol * (ringEdge * 0.92 + softAura) * outsideHole * intensity;
        acc += vec3(1.0, 0.72, 0.98) * (flareH + flareV * 0.7) * snapPulse * 1.15;
    }

    return acc;
}

void main() {
    int count = int(Meta.x + 0.5);
    if (count <= 0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);

    vec3 acc = vec3(0.0);
    if (count > 0) acc += evalKamuiVolume(VolA[0], VolA[1], rd, sceneT);
    if (count > 1) acc += evalKamuiVolume(VolA[2], VolA[3], rd, sceneT);
    if (count > 2) acc += evalKamuiVolume(VolB[0], VolB[1], rd, sceneT);
    if (count > 3) acc += evalKamuiVolume(VolB[2], VolB[3], rd, sceneT);

    fragColor = vec4(acc * Meta.y, 1.0);
}
