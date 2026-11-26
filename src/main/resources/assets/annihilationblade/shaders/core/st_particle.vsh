#version 150

// 星穹引擎纯客户端物理粒子池顶点着色器（相对相机坐标 + 打包 HDR 自发光强度）
in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vColor;
out vec2 vUv;
out float vViewDist;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    vColor = Color;
    vUv = UV0;
    vViewDist = length(viewPos.xyz);
}
