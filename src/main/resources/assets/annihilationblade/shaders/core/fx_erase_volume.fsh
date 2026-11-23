#version 150

#moj_import <annihilationblade:fx_common.glsl>

// 虚无权域体积通道：冷白穹顶薄壳、球形扫描刃、体素微尘与 CRT 归零耀斑。
uniform sampler2D Sampler1;
uniform mat4 InvProj;
uniform mat4 InvView;
uniform mat4 ViewProj;
uniform vec2 OutSize;
uniform vec4 Dome;   // xyz = 穹顶中心（相对相机），w = 当前穹顶半径
uniform vec4 Erase;  // x = 可见度，y = 扫描半径，z = 波前宽度，w = 体积强度
uniform vec4 Phase;  // x = 演出 tick，y = 坍缩进度，z = 体积强度，w = CRT 归零强度
uniform vec4 Extra;  // x = 原始半径，y = 重绘半径，z = 视觉倍率，w = 保留参数
uniform float FxTime;
uniform float Steps;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    float depth = texture(Sampler1, uv).r;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float sceneT = fxSceneDistance(depth, uv, InvProj);
    float radius = max(Dome.w, Extra.y);
    vec3 center = Dome.xyz;
    vec3 acc = vec3(0.0);

    if (radius > 0.05 && Erase.x > 0.001 && Erase.w > 0.001) {
        vec2 hit = fxSphere(rd, center, radius);
        float t0 = max(hit.x, 0.0);
        float t1 = min(hit.y, sceneT);
        if (hit.y > 0.0 && t0 < t1) {
            int steps = int(clamp(Steps, 12.0, 32.0));
            float dt = (t1 - t0) / float(steps);
            float jitter = fxIGN(gl_FragCoord.xy, FxTime);
            for (int i = 0; i < 32; i++) {
                if (i >= steps) {
                    break;
                }
                float t = t0 + (float(i) + jitter) * dt;
                vec3 local = rd * t - center;
                float distanceToCenter = length(local);
                float relative = distanceToCenter / radius;

                float wall = exp(-abs(relative - 1.0) * 42.0);
                float latitude = abs(fract(atan(local.z, local.x) * 12.0 / 6.2831853) - 0.5);
                float longitudeLine = 1.0 - smoothstep(0.018, 0.08, latitude);
                float meridian = longitudeLine * wall;
                float scan = exp(-abs(distanceToCenter - Erase.y) / max(Erase.z, 0.12));

                vec3 voxelCell = floor(local * 1.35 + vec3(FxTime * 0.025));
                float voxelHash = fxHash13(voxelCell);
                float voxel = smoothstep(0.965, 1.0, voxelHash);
                float drift = 0.6 + 0.4 * sin(FxTime * 2.6 + voxelHash * 19.0 + relative * 9.0);
                float inner = 1.0 - smoothstep(0.52, 0.96, relative);
                float converge = Phase.y * (0.35 + 0.65 * inner);
                float dust = voxel * inner * drift * (0.55 + converge * 1.7);

                float groundLattice = exp(-abs(local.y + Extra.x * 0.46) * 0.22)
                    * (1.0 - smoothstep(0.5, 0.95, relative));
                vec3 white = vec3(0.84, 0.88, 0.93);
                float contribution = (wall * (0.22 + meridian * 0.5) + scan * 1.35 + dust * 0.46 + groundLattice * 0.12)
                    * Erase.w * Erase.x;
                acc += white * contribution * dt * (0.52 + 0.48 * drift);
            }
            acc *= 0.72 / max(radius * 0.08, 0.6);
        }
    }

    float tc = dot(center, rd);
    float distanceToRay = length(center - rd * tc);
    if (tc > 0.0 && tc < sceneT) {
        float core = exp(-pow(distanceToRay / max(Extra.z * 0.12, 0.2), 2.0));
        acc += vec3(0.82, 0.86, 0.92) * core * (0.2 + Phase.y * 0.55) * Phase.z;
    }

    vec4 clipCenter = ViewProj * vec4(center, 1.0);
    if (Phase.w > 0.001 && clipCenter.w > 0.05) {
        vec2 centerUv = clipCenter.xy / clipCenter.w * 0.5 + 0.5;
        float horizontal = exp(-abs(uv.y - centerUv.y) * 520.0);
        float point = exp(-length((uv - centerUv) * vec2(OutSize.x / max(OutSize.y, 1.0), 1.0)) * 180.0);
        acc += vec3(0.95, 0.97, 1.0) * (horizontal * 0.7 + point * 1.8) * Phase.w;
    }

    fragColor = vec4(acc, 1.0);
}
