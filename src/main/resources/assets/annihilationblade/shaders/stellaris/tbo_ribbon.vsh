#version 150

// 星穹引擎 GPU 实例化 3D 能量丝带/虹吸轨迹顶点着色器（OpenGL 3.2 Core #version 150 + samplerBuffer TBO）
// 每条丝带段占 4 个 vec4（64 字节）：
//   base + 0: xyz = 起点相对相机坐标, w = 起点半宽
//   base + 1: xyz = 终点相对相机坐标, w = 终点半宽
//   base + 2: rgb = 线性 HDR 色彩辐射度, w = 起点透明度
//   base + 3: x = 终点透明度, y = uStart, z = uEnd, w = floor(style 0..3) + fract(seed)
uniform samplerBuffer RibbonData;
uniform mat4 ViewProj;
uniform vec3 CamUp;

out vec2 vUv;        // x = 沿丝带进度 u, y = 横截面 -1..1
out vec3 vHdrColor;
out float vAlpha;
out float vViewDist;
out float vStyle;
out float vSeed;

void main() {
    int base = gl_InstanceID * 4;
    vec4 d0 = texelFetch(RibbonData, base + 0);
    vec4 d1 = texelFetch(RibbonData, base + 1);
    vec4 d2 = texelFetch(RibbonData, base + 2);
    vec4 d3 = texelFetch(RibbonData, base + 3);

    vec3 p0 = d0.xyz;
    vec3 p1 = d1.xyz;
    float w0 = max(d0.w, 0.004);
    float w1 = max(d1.w, 0.004);

    vec3 tangent = p1 - p0;
    float segLen2 = dot(tangent, tangent);
    if (segLen2 < 1.0e-8) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vUv = vec2(0.0);
        vHdrColor = vec3(0.0);
        vAlpha = 0.0;
        vViewDist = 1.0e6;
        vStyle = 0.0;
        vSeed = 0.0;
        return;
    }
    tangent *= inversesqrt(segLen2);

    vec3 side0 = cross(tangent, -p0);
    float l0 = dot(side0, side0);
    side0 = (l0 > 1.0e-8) ? (side0 * inversesqrt(l0)) : CamUp;

    vec3 side1 = cross(tangent, -p1);
    float l1 = dot(side1, side1);
    side1 = (l1 > 1.0e-8) ? (side1 * inversesqrt(l1)) : side0;
    if (dot(side0, side1) < 0.0) {
        side1 = -side1;
    }

    // TRIANGLE_STRIP 4 顶点：0=(start,-1), 1=(start,+1), 2=(end,-1), 3=(end,+1)
    bool isEnd = (gl_VertexID & 2) != 0;
    float sideSign = (gl_VertexID & 1) == 0 ? -1.0 : 1.0;

    vec3 center = isEnd ? p1 : p0;
    vec3 side = isEnd ? side1 : side0;
    float halfW = isEnd ? w1 : w0;
    vec3 worldPos = center + side * (halfW * sideSign);

    gl_Position = ViewProj * vec4(worldPos, 1.0);
    vUv = vec2(isEnd ? d3.z : d3.y, sideSign);
    vHdrColor = max(d2.rgb, vec3(0.0));
    vAlpha = isEnd ? d3.x : d2.w;
    vViewDist = length(worldPos);
    vStyle = floor(d3.w + 0.0001);
    vSeed = fract(d3.w);
}
