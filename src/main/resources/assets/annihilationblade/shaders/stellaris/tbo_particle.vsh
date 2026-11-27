#version 150

// 星穹引擎 GPU 实例化多形态粒子顶点着色器（OpenGL 3.2 Core #version 150 + samplerBuffer TBO）
// 每个粒子占 3 个 vec4（48 字节）：
//   base + 0: xyz = 相对相机世界坐标, w = 世界半径
//   base + 1: xyz = 速度向量(格/tick), w = 速度拉伸系数
//   base + 2: rgb = 完整 float32 线性 HDR 辐射度(零 8-bit 量化断层), w = floor(形态0..4) + fract(旋转随机种子)
uniform samplerBuffer ParticleData;
uniform mat4 ViewProj;
uniform vec3 CamLeft;
uniform vec3 CamUp;
uniform float StTime;

out vec2 vUv;
out vec3 vHdrColor;
out float vViewDist;
out float vShapeId;
out float vSeed;

void main() {
    int base = gl_InstanceID * 3;
    vec4 d0 = texelFetch(ParticleData, base + 0);
    vec4 d1 = texelFetch(ParticleData, base + 1);
    vec4 d2 = texelFetch(ParticleData, base + 2);

    vec3 pos = d0.xyz;
    float radius = max(d0.w, 0.008);
    vec3 vel = d1.xyz;
    float stretch = max(d1.w, 0.0);

    float shapeId = floor(d2.w + 0.0001);
    float seed = fract(d2.w);

    // TRIANGLE_STRIP 4 顶点顺序：(-1,-1), (1,-1), (-1,1), (1,1)
    vec2 corner = vec2((gl_VertexID & 1) == 0 ? -1.0 : 1.0, (gl_VertexID & 2) == 0 ? -1.0 : 1.0);

    vec3 axisU;
    vec3 axisV;
    float spd2 = dot(vel, vel);
    bool useStreak = (shapeId > 2.5 && shapeId < 3.5) || (stretch > 0.05 && spd2 > 1.0e-6);

    if (useStreak && spd2 > 1.0e-7) {
        float spd = sqrt(spd2);
        vec3 velDir = vel / spd;
        vec3 side = cross(pos, velDir);
        float sideLen2 = dot(side, side);
        if (sideLen2 > 1.0e-7) {
            side *= inversesqrt(sideLen2);
            float streakBoost = (shapeId > 2.5 && shapeId < 3.5) ? max(stretch, 1.5) : stretch;
            float halfLen = radius * (1.0 + min(7.5, spd * 7.2 * streakBoost));
            float halfW = radius * ((shapeId > 2.5 && shapeId < 3.5) ? 0.44 : 0.56);
            axisU = velDir * halfLen;
            axisV = side * halfW;
        } else {
            axisU = CamLeft * radius;
            axisV = CamUp * radius;
        }
    } else {
        float spinRate = (shapeId > 0.5 && shapeId < 1.5) ? 1.8 : 0.45;
        float ang = seed * 6.2831853 + StTime * spinRate * ((seed > 0.5) ? 1.0 : -1.0);
        float c = cos(ang);
        float s = sin(ang);
        axisU = (CamLeft * c + CamUp * s) * radius;
        axisV = (CamUp * c - CamLeft * s) * radius;
    }

    vec3 worldPos = pos + axisU * corner.x + axisV * corner.y;
    gl_Position = ViewProj * vec4(worldPos, 1.0);
    vUv = corner;
    vHdrColor = max(d2.rgb, vec3(0.0));
    vViewDist = length(worldPos);
    vShapeId = shapeId;
    vSeed = seed;
}
