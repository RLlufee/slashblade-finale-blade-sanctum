#version 150

#moj_import <annihilationblade:fx_common.glsl>
#moj_import <annihilationblade:fx_space.glsl>

// 虚无权域场景通道：将波前外侧、边缘和已卸载区域映射为不同的现实状态。
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform mat4 InvProj;
uniform mat4 InvView;
uniform vec2 OutSize;
uniform float FxTime;
uniform vec4 Dome;    // xyz = 穹顶中心（相对相机），w = 当前穹顶半径
uniform vec4 Erase;   // x = 强度，y = 马赛克块像素，z = 波前半径，w = 波前宽度
uniform vec4 Grid;    // x = 一格细网，y = 四格主网，z = 去色，w = 反相强度
uniform vec4 CamFract;// xyz = 相机坐标小数部分，w = 原始穹顶半径
uniform vec4 Extra;   // x = 已卸载强度，y = 故障强度，z = 重绘波前半径，w = 阶段编号

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 rd = fxViewRay(texCoord, InvProj, InvView);
    float depth = texture(Sampler1, texCoord).r;
    float sceneT = fxSceneDistance(depth, texCoord, InvProj);
    vec3 p = rd * min(sceneT, 1.0e4);
    vec3 normal = normalize(cross(dFdx(p), dFdy(p)) + vec3(0.0, 1.0e-6, 0.0));
    vec3 world = p + CamFract.xyz;

    // 导数相关网格在所有像素路径上统一求值，避免分支内导数造成远景闪烁。
    float fineGrid = fxSurfaceGrid(world, normal, 1.0, 0.018);
    float mainGrid = fxSurfaceGrid(world, normal, 4.0, 0.026);
    vec3 base = texture(Sampler0, texCoord).rgb;
    float originalRadius = max(CamFract.w, 0.1);
    float phase = Extra.w;
    bool restoring = phase > 3.5;
    float effectRadius = ((phase > 1.5 && phase < 2.5) || restoring) ? originalRadius : Dome.w;
    vec2 hit = effectRadius > 0.1 ? fxSphere(rd, Dome.xyz, effectRadius) : vec2(1.0, -1.0);
    float distToCenter = length(p - Dome.xyz);
    float surfaceInside = (sceneT < 1.0e5 && hit.y > 0.0 && distToCenter < effectRadius) ? 1.0 : 0.0;

    float through = 0.0;
    if (surfaceInside < 0.5 && hit.y > 0.0 && max(hit.x, 0.0) < sceneT) {
        through = clamp((min(hit.y, sceneT) - max(hit.x, 0.0)) / (effectRadius * 2.0), 0.0, 1.0) * 0.72;
    }
    float inside = max(surfaceInside, through) * Erase.x;
    if (inside <= 0.001) {
        fragColor = vec4(base, 1.0);
        return;
    }

    float block = max(Erase.y, 1.0);
    vec2 cellUv = (floor(texCoord * OutSize / block) + 0.5) * block / OutSize;
    vec3 mosaic = mix(base, texture(Sampler0, cellUv).rgb, step(1.5, block));
    float luma = dot(mosaic, vec3(0.299, 0.587, 0.114));
    vec3 formatted = mix(mosaic, vec3(luma), clamp(Grid.z, 0.0, 1.0));

    float distanceToCenter = surfaceInside > 0.5 ? distToCenter : length(Dome.xyz - rd * dot(Dome.xyz, rd));
    float wiped = 0.0;
    if (phase > 1.5 && phase < 2.5) {
        wiped = 1.0 - smoothstep(Erase.z - Erase.w, Erase.z + Erase.w, distanceToCenter);
    } else if (phase >= 2.5 && phase < 3.5) {
        wiped = Extra.x;
    } else if (restoring) {
        wiped = smoothstep(Extra.z - 2.5, Extra.z + 2.5, distanceToCenter) * Extra.x;
    }
    wiped *= surfaceInside;

    float gridFade = 1.0 - smoothstep(effectRadius * 0.76, effectRadius, distanceToCenter);
    float linework = max(fineGrid * Grid.x, mainGrid * Grid.y) * gridFade;
    vec3 blueprint = vec3(luma * 0.13) + vec3(linework * 0.9);
    vec3 color = mix(formatted, blueprint, clamp(wiped, 0.0, 1.0));

    float frontDistance = abs(distanceToCenter - Erase.z);
    float shell = 1.0 - smoothstep(max(Erase.w * 0.25, 0.01), max(Erase.w, 0.02), frontDistance);
    if (Erase.z > 0.01 && phase > 1.5 && phase < 2.5) {
        float stripHash = fxHash11(floor(gl_FragCoord.y / 3.0) + floor(FxTime * 18.0));
        float strip = step(0.82, stripHash) * shell * Extra.y;
        float shift = (stripHash - 0.5) * 0.006 * Extra.y;
        vec3 torn = texture(Sampler0, clamp(texCoord + vec2(shift, 0.0), vec2(0.0), vec2(1.0))).rgb;
        color = mix(color, vec3(dot(torn, vec3(0.299, 0.587, 0.114))), strip * 0.82);
        vec2 pixel = 1.0 / max(OutSize, vec2(1.0));
        vec3 fringe = vec3(
            texture(Sampler0, clamp(texCoord + vec2(pixel.x, 0.0), vec2(0.0), vec2(1.0))).r,
            color.g,
            texture(Sampler0, clamp(texCoord - vec2(pixel.x, 0.0), vec2(0.0), vec2(1.0))).b
        );
        color = mix(color, fringe, shell * Extra.y * 0.16);
        color = mix(color, vec3(1.0) - color, shell * 0.9 * Erase.x);
        color += vec3(1.0) * exp(-frontDistance / max(Erase.w * 0.18, 0.035)) * 0.72 * Erase.x;
    }

    color = mix(color, vec3(1.0) - color, Grid.w * inside);
    float tc = dot(Dome.xyz, rd);
    if (effectRadius > 0.1 && tc > 0.0 && hit.y > 0.0 && max(hit.x, 0.0) < sceneT) {
        float closest = length(Dome.xyz - rd * tc);
        float rim = exp(-abs(closest - effectRadius) / max(effectRadius * 0.012, 0.04));
        color += vec3(0.92, 0.94, 0.97) * rim * 0.62 * Erase.x;
    }

    fragColor = vec4(color, 1.0);
}
