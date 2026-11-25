#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 空间破碎 · 体积通道：在碎裂球体内光线步进，累积 3D Voronoi 裂隙薄片的发光；
// 另含展开波前、核心闪光、黑洞吸积环、冲击波光边。输出 HDR，加法混合到体积层。
uniform sampler2D Sampler1; // 场景原始深度
uniform mat4 InvProj;
uniform mat4 InvView;
uniform vec4 Sphere;
uniform vec4 Phase;
uniform vec4 Shock;
uniform vec4 Extra;
uniform float FxTime;
uniform float Steps;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);

    vec3 center = Sphere.xyz;
    float radius = Sphere.w;
    float reveal = Phase.x;
    float collapse = Phase.y;
    float fade = Phase.z;
    float seed = Phase.w;
    float cellSize = max(Extra.z, 0.25);

    float tc = dot(center, rd);
    float dRay = length(center - rd * tc);
    vec3 acc = vec3(0.0);

    vec2 hit = fxSphere(rd, center, radius);
    if (fade > 0.0 && hit.y > 0.0) {
        float t0 = max(hit.x, 0.0);
        float t1 = min(hit.y, sceneT);
        if (t0 < t1) {
            int steps = int(clamp(Steps, 4.0, 48.0));
            float dt = (t1 - t0) / float(steps);
            float jitter = fxIGN(gl_FragCoord.xy, FxTime);
            for (int i = 0; i < 48; i++) {
                if (i >= steps) {
                    break;
                }
                float t = t0 + (float(i) + jitter) * dt;
                vec3 p = rd * t - center;
                float rel = length(p) / radius;
                float front = 1.0 - smoothstep(reveal - 0.12, reveal, rel);
                float boundary = 1.0 - smoothstep(0.75, 1.0, rel);
                float m = front * boundary;
                if (m <= 0.001) {
                    continue;
                }
                vec3 cell;
                vec3 v = fxVoronoi3Fast(p / cellSize + seed, cell);
                float sheet = exp(-v.y * 22.0);
                float h = fxHash13(cell);
                vec3 tint = mix(vec3(0.36, 0.86, 1.0), vec3(0.62, 0.28, 1.0), h);
                float pulse = 0.65 + 0.35 * sin(FxTime * 6.0 + h * 30.0 + rel * 12.0);
                float wave = exp(-abs(rel - reveal) * 25.0) * (1.0 - reveal * 0.7);
                acc += (tint * sheet * pulse * (1.0 + 2.5 * collapse) + vec3(0.8, 0.7, 1.0) * wave * 0.6) * m * dt;
            }
            acc *= 0.9 / cellSize * fade;
        }
    }

    // 核心闪光（裂界与湮灭瞬间：锐利亮芯 + 深紫青星云外晕，避免大面积洗白）
    if (Extra.x > 0.0 && tc > 0.0 && tc - radius * 0.2 < sceneT) {
        float qCore = dRay / max(radius * 0.16, 0.25);
        float qHalo = dRay / max(radius * 0.38, 0.5);
        float sharpCore = exp(-(qCore * qCore));
        float chromaHalo = exp(-(qHalo * qHalo));
        acc += (vec3(1.0, 0.94, 1.0) * sharpCore * 2.4 + vec3(0.58, 0.36, 1.0) * chromaHalo * 0.85) * Extra.x;
    }

    // 黑洞光子环、吸积环与外晕
    float coreR = Shock.w;
    if (coreR > 0.0 && tc > 0.0 && tc - coreR < sceneT) {
        float photonRing = exp(-abs(dRay - coreR * 1.12) / max(coreR * 0.08, 0.04));
        float ring = exp(-abs(dRay - coreR * 1.42) / max(coreR * 0.22, 0.08));
        acc += vec3(1.0, 0.88, 0.65) * photonRing * 2.4;
        acc += vec3(0.76, 0.44, 1.0) * ring * 2.2;
        acc += vec3(0.36, 0.72, 1.0) * exp(-max(dRay - coreR, 0.0) / max(coreR * 1.65, 0.2)) * step(coreR, dRay) * 0.65;
    }

    // 双波段绚烂空间冲击波壳（前沿星金冷白激波 + 尾随虚空紫青星云余波）
    if (Shock.y > 0.0 && tc > 0.0) {
        float frontT = tc - sqrt(max(Shock.x * Shock.x - dRay * dRay, 0.0));
        if (frontT < sceneT) {
            float q1 = (dRay - Shock.x) / max(Shock.z, 0.1);
            float q2 = (dRay - Shock.x * 0.84) / max(Shock.z * 1.45, 0.15);
            float wave1 = exp(-(q1 * q1));
            float wave2 = exp(-(q2 * q2));
            float turb = 0.72 + 0.28 * fxNoise3(rd * 11.0 + vec3(FxTime * 1.8, -FxTime * 1.3, seed));
            vec3 leadCol = mix(vec3(1.0, 0.84, 0.48), vec3(0.95, 0.98, 1.0), wave1 * 0.65);
            vec3 trailCol = mix(vec3(0.64, 0.26, 1.0), vec3(0.32, 0.88, 1.0), turb);
            acc += (leadCol * wave1 * 1.85 + trailCol * wave2 * 1.15 * turb) * Shock.y;
        }
    }

    fragColor = vec4(acc, 1.0);
}
