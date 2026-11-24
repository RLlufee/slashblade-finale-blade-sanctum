#version 150

#moj_import <annihilationblade:fx_common.glsl>
#moj_import <annihilationblade:fx_space.glsl>

// 虚无权域 · 场景通道（黑白现实降维 × 建筑线稿蓝图 × 虚无墨穹 × 现实重绘）：
//  · Phase 0 (展开)  ：球形边界向外划开，域内转为高反差电影黑白并浮现微弱测绘主网，天空沉为虚无墨穹；
//  · Phase 1 (编目)  ：域内脉动测绘波纹扫过地表，凸显几何轮廓与坐标线；
//  · Phase 2 (扫描)  ：球形波前刀刃带局部反相与极克制的扫描线微撕裂，扫过之处卸除贴图转为纯黑墨底 + 锐利瓷白建筑线稿与深度轮廓；
//  · Phase 3 (坍缩)  ：蓝图虚空向中枢奇点收束坍缩，外侧保留抽色静默态，绝不突兀回弹；
//  · Phase 4 (重绘)  ：现实重绘波自奇点向外平滑展开，逐格还原真实色彩与原本天空。
uniform sampler2D Sampler0; // 场景颜色
uniform sampler2D Sampler1; // 场景原始深度（R32F）
uniform mat4 InvProj;
uniform mat4 InvView;
uniform vec2 OutSize;
uniform float FxTime;
uniform vec4 Dome;     // xyz = 穹顶中心（相对相机），w = 当前外边界半径
uniform vec4 Erase;    // x = 总存在度，y = 活跃蓝图/虚空内半径，z = 波前半径，w = 波前宽度
uniform vec4 Grid;     // x = 1m 细网权重，y = 4m 主网权重，z = 黑白降维强度，w = 全域反相脉冲
uniform vec4 CamFract; // xyz = 相机坐标小数部分，w = 原始满半径
uniform vec4 Extra;    // x = 虚无墨穹天空强度，y = 波前局部故障强度，z = 现实重绘半径，w = 阶段编号

in vec2 texCoord;
out vec4 fragColor;

float sampleLinearDist(vec2 uv) {
    vec2 suv = clamp(uv, vec2(0.0005), vec2(0.9995));
    float d = texture(Sampler1, suv).r;
    return min(fxSceneDistance(d, suv, InvProj), 120.0);
}

// 虚无墨穹：深邃墨黑天顶 → 苍银地平微雾 + 极细天球经纬测绘刻度 + 微弱星点。
vec3 renderVoidSky(vec3 rd, vec3 domeCenter, float hitFar) {
    float elev = clamp(rd.y, -0.2, 1.0);
    vec3 zenith = vec3(0.022, 0.026, 0.036);
    vec3 horizon = vec3(0.20, 0.22, 0.26);
    vec3 sky = mix(horizon, zenith, pow(max(clamp(elev * 1.25 + 0.14, 0.0, 1.0), 1.0e-5), 0.72));

    vec3 skyDir = normalize(rd * max(hitFar, 1.0) - domeCenter);
    float az = atan(skyDir.z, skyDir.x) / FX_TAU + 0.5;
    float el = asin(clamp(skyDir.y, -1.0, 1.0)) / FX_PI + 0.5;
    float mer = 1.0 - smoothstep(0.0035, 0.011, abs(fract(az * 16.0) - 0.5));
    float lat = 1.0 - smoothstep(0.0035, 0.011, abs(fract(el * 8.0) - 0.5));
    float polarFade = 1.0 - smoothstep(0.82, 0.98, abs(skyDir.y));
    float skyGrid = max(mer * 0.42 * polarFade, lat * 0.55);

    vec3 sp = skyDir * 96.0;
    float st = fxHash13(floor(sp));
    float star = step(0.9925, st) * (1.0 - smoothstep(0.0, 0.36, length(fract(sp) - 0.5)));
    float twinkle = 0.7 + 0.3 * sin(FxTime * 2.2 + st * 40.0);
    sky += vec3(0.82, 0.87, 0.95) * (skyGrid * 0.14 + star * 0.36 * twinkle);
    return sky;
}

void main() {
    vec2 uv = texCoord;
    vec3 rd = fxViewRay(uv, InvProj, InvView);
    float depth = texture(Sampler1, uv).r;
    float sceneT = fxSceneDistance(depth, uv, InvProj);
    bool isSky = depth >= 0.9999;

    // 所有导数与网格在一致控制流中先行求值，避免分支内导数造成远景闪烁
    vec3 p = rd * min(sceneT, 1.0e4);
    vec3 dpdx = dFdx(p);
    vec3 dpdy = dFdy(p);
    float dSpan = max(length(dpdx), length(dpdy));
    float normalValid = step(sceneT, 1.0e5) * step(dSpan, max(sceneT * 0.28, 0.18));
    vec3 gradNormal = normalize(cross(dpdx, dpdy) + vec3(0.0, 1.0e-6, 0.0));
    vec3 normal = mix(vec3(0.0, 1.0, 0.0), gradNormal, normalValid);
    vec3 world = p + CamFract.xyz;

    float fineGrid = fxSurfaceGrid(world, normal, 1.0, 0.018) * normalValid;
    float mainGrid = fxSurfaceGrid(world, normal, 4.0, 0.026) * normalValid;
    // 2 格高度建筑等高线（仅刻在垂直墙面/山崖/树干上，拉出建筑蓝图立体层次）
    float yCoord = world.y * 0.5;
    float yFw = min(fwidth(yCoord), 0.45);
    float contour = (1.0 - smoothstep(0.014, 0.014 + yFw * 1.6, 0.5 - abs(fract(yCoord) - 0.5)))
        * (1.0 - abs(normal.y)) * normalValid;

    // 拉普拉斯深度轮廓检测：平坦斜面二阶差近似 0（绝无掠射噪点），仅在方块棱角、山脊、树木与实体外轮廓亮起锐利线条
    vec2 px = 1.0 / max(OutSize, vec2(1.0));
    float cT = min(sceneT, 120.0);
    float lT = sampleLinearDist(uv - vec2(px.x, 0.0));
    float rT = sampleLinearDist(uv + vec2(px.x, 0.0));
    float dT = sampleLinearDist(uv - vec2(0.0, px.y));
    float uT = sampleLinearDist(uv + vec2(0.0, px.y));
    float lapDepth = max(abs(lT + rT - 2.0 * cT), abs(dT + uT - 2.0 * cT)) / max(cT, 1.0);
    float depthEdge = isSky ? 0.0 : smoothstep(0.032, 0.135, lapDepth);

    vec3 base = texture(Sampler0, uv).rgb;
    float outerR = Dome.w;
    if (outerR <= 0.08 || Erase.x <= 0.001) {
        fragColor = vec4(base, 1.0);
        return;
    }

    vec2 hit = fxSphere(rd, Dome.xyz, outerR);
    float distToCenter = length(p - Dome.xyz);
    bool surfIn = (!isSky && hit.y > 0.0 && distToCenter < outerR);
    bool rayThrough = (hit.y > 0.0 && max(hit.x, 0.0) < sceneT);
    if (!surfIn && !rayThrough) {
        fragColor = vec4(base, 1.0);
        return;
    }

    float phase = Extra.w;
    float restoreR = Extra.z;
    float baseR = max(CamFract.w, 0.1);
    float luma = dot(base, vec3(0.2126, 0.7152, 0.0722));

    // ─── 1. 天空或透过穹顶看远景：沉降为虚无墨穹，并随现实重绘平滑复原 ───
    if (!surfIn) {
        float chord = clamp((min(hit.y, sceneT) - max(hit.x, 0.0)) / (outerR * 1.45), 0.0, 1.0);
        float skyRestore = 0.0;
        if (phase > 3.5 && restoreR > 0.01) {
            float tc = max(dot(Dome.xyz, rd), 0.0);
            float rayDist = length(Dome.xyz - rd * tc);
            skyRestore = 1.0 - smoothstep(restoreR - 2.2, restoreR + 1.8, rayDist);
            // 相机身处球内时，重绘波也随进度同步解放全天空
            float camIn = 1.0 - smoothstep(outerR * 0.85, outerR * 1.05, length(Dome.xyz));
            skyRestore = max(skyRestore, camIn * smoothstep(0.15, 0.92, restoreR / baseR));
        }

        vec3 color = base;
        float camInside = 1.0 - smoothstep(outerR * 0.85, outerR * 1.02, length(Dome.xyz));
        if (isSky) {
            vec3 voidSky = renderVoidSky(rd, Dome.xyz, hit.y);
            float skyCover = max(smoothstep(0.0, 0.35, chord), camInside);
            float skyMix = clamp(Extra.x * Erase.x * skyCover * 0.94 * (1.0 - skyRestore), 0.0, 0.94);
            color = mix(base, voidSky, skyMix);
        } else {
            // 透过穹顶看到的远山/背景：柔和冷银单色折射感，不糊成死灰
            vec3 tint = vec3(luma) * vec3(0.86, 0.91, 0.98);
            color = mix(base, tint, smoothstep(0.0, 0.45, chord) * 0.48 * Grid.z * Erase.x * (1.0 - skyRestore));
        }

        // 外穹顶切线银白轮廓光（仅当球体边缘在视野前方时）
        float tc = dot(Dome.xyz, rd);
        if (tc > 0.0) {
            float closest = length(Dome.xyz - rd * tc);
            float rim = exp(-abs(closest - outerR) / max(outerR * 0.010, 0.045));
            color += vec3(0.90, 0.94, 1.0) * rim * 0.55 * Erase.x * (1.0 - skyRestore);
        }
        color = mix(color, vec3(1.0) - color, Grid.w * chord * Erase.x * (1.0 - skyRestore));
        fragColor = vec4(color, 1.0);
        return;
    }

    // ─── 2. 域内真实场景表面：黑白降维态 / 蓝图删档态 / 现实重绘态 ───
    float domEdge = 1.0 - smoothstep(outerR - 0.55, outerR, distToCenter);
    float restored = (phase > 3.5 && restoreR > 0.01)
        ? (1.0 - smoothstep(restoreR - 1.35, restoreR + 1.35, distToCenter))
        : 0.0;

    // 2A. 黑白现实降维态（电影 S 曲线高反差单色 + 漫画深度轮廓 + 淡雅测绘网）
    float contrast = clamp(mix(luma, smoothstep(0.04, 0.92, luma), 0.72), 0.0, 1.0);
    vec3 mono = vec3(contrast) * vec3(0.94, 0.97, 1.02);
    float preGrid = max(mainGrid * Grid.y * 0.44, fineGrid * Grid.x * 0.16);
    // 亮面压黑轮廓、暗面提白轮廓，保证任何地形都线条清晰
    vec3 edgeInk = mix(vec3(0.90, 0.94, 1.0), vec3(0.03, 0.035, 0.05), smoothstep(0.32, 0.62, contrast));
    mono = mix(mono, edgeInk, depthEdge * 0.46);
    mono += vec3(0.88, 0.93, 1.0) * preGrid;

    // 编目幕（Phase 1）：自中枢向外脉动的测绘波纹，标定域内所有几何
    if (phase > 0.5 && phase < 1.5) {
        float pulse = exp(-abs(fract(distToCenter / 7.5 - FxTime * 1.15) - 0.5) * 28.0);
        mono += vec3(0.92, 0.96, 1.0) * pulse * (0.14 + max(fineGrid, mainGrid) * 0.48 + depthEdge * 0.35);
    }
    vec3 formatted = mix(base, mono, clamp(Grid.z, 0.0, 1.0));

    // 2B. 已删档蓝图态（绝对漆黑墨底 + 锐利瓷白 CAD 线框、等高线与深度外轮廓）
    float voidR = Erase.y;
    float waveW = max(Erase.w, 0.15);
    float wiped = voidR > 0.05 ? (1.0 - smoothstep(voidR - waveW, voidR + waveW, distToCenter)) : 0.0;
    float gridFade = 1.0 - smoothstep(outerR * 0.86, outerR, distToCenter);
    float cadLines = max(max(fineGrid * Grid.x * 0.58, mainGrid * Grid.y * 1.08), contour * Grid.x * 0.54) * gridFade;
    vec3 blueprint = vec3(pow(max(luma, 1.0e-5), 1.35) * 0.055) * vec3(0.82, 0.88, 1.0);
    blueprint += vec3(0.92, 0.96, 1.0) * clamp(cadLines * 0.85 + depthEdge * 0.96, 0.0, 1.25);

    vec3 domColor = mix(formatted, blueprint, clamp(wiped, 0.0, 1.0));

    // 2C. 展开边界刀锋（Phase 0 展开瞬间划过地表的瓷白锋线）
    if (phase < 0.5) {
        float expandRim = exp(-abs(distToCenter - outerR) / 0.28);
        domColor = mix(domColor, vec3(1.0) - domColor, expandRim * 0.42);
        domColor += vec3(0.95, 0.98, 1.0) * expandRim * 0.85;
    }

    // 2D. 扫描波前刀锋（Phase 2 删档前沿：严格局限于窄带的反相 + 瓷白刃口 + 极细水平扫描线微撕裂）
    if (phase > 1.5 && phase < 2.5 && Erase.z > 0.05) {
        float frontDist = abs(distToCenter - Erase.z);
        float shell = 1.0 - smoothstep(waveW * 0.22, waveW * 1.15, frontDist);
        if (shell > 0.001) {
            float stripHash = fxHash11(floor(gl_FragCoord.y / 3.0) + floor(FxTime * 22.0));
            float strip = step(0.84, stripHash) * shell * Extra.y;
            float shift = (stripHash - 0.5) * 0.009 * Extra.y;
            vec2 tornUv = clamp(uv + vec2(shift, 0.0), vec2(0.001), vec2(0.999));
            vec3 torn = texture(Sampler0, tornUv).rgb;
            domColor = mix(domColor, vec3(dot(torn, vec3(0.2126, 0.7152, 0.0722))), strip * 0.78);
            // 极克制的 1.5px 边缘色散
            vec3 fringe = vec3(
                texture(Sampler0, clamp(uv + vec2(px.x * 1.5, 0.0), vec2(0.001), vec2(0.999))).r,
                domColor.g,
                texture(Sampler0, clamp(uv - vec2(px.x * 1.5, 0.0), vec2(0.001), vec2(0.999))).b
            );
            domColor = mix(domColor, fringe, shell * Extra.y * 0.20);
            // 窄带黑白反相 + 瓷白锋线
            domColor = mix(domColor, vec3(1.0) - domColor, shell * 0.85);
            domColor += vec3(0.96, 0.98, 1.0) * exp(-frontDist / max(waveW * 0.16, 0.05)) * 1.05;
        }
    }

    // 2E. 坍缩边界刀锋（Phase 3 蓝图向心抽回时的锐利冷白收束边）
    if (phase >= 2.5 && phase < 3.5 && voidR > 0.08) {
        float collapseDist = abs(distToCenter - voidR);
        float cBlade = exp(-collapseDist / max(waveW * 0.35, 0.14));
        domColor += vec3(0.94, 0.97, 1.0) * cBlade * 0.92;
    }

    // 2F. 合成域内色彩与现实重绘还原
    float effectiveMask = domEdge * Erase.x * (1.0 - restored);
    vec3 color = mix(base, domColor, effectiveMask);

    // 2G. 现实重绘波前（Phase 4 色彩回流瞬间的清澈银白网格闪光）
    if (phase > 3.5 && restoreR > 0.05) {
        float rDist = abs(distToCenter - restoreR);
        float rBand = exp(-rDist / 0.42) * domEdge * Erase.x;
        float rGrid = max(fineGrid * 0.6, max(mainGrid, depthEdge * 0.85));
        color += vec3(0.92, 0.96, 1.0) * rBand * (0.48 + rGrid * 0.65);
    }

    // 2H. 奇点坍缩瞬间的全域反相脉冲
    color = mix(color, vec3(1.0) - color, Grid.w * effectiveMask);

    fragColor = vec4(color, 1.0);
}
