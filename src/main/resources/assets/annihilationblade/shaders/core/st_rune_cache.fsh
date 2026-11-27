#version 150

#moj_import <annihilationblade:st_common.glsl>

// 星穹浑天巨阵 GPU 首次 1 帧预烘焙 4 通道精确距离场（2048² / 4096² RGBA16F）：
// 坐标域 p ∈ [-1.18, 1.18]²，存储精确归一化平面欧氏距离，供 st_relief.fsh 3D 圆管步进零解析开销、任意拉近刀锋般锐利：
//  - R: 外环主骨架（双浑天主环 + 144 精密游标刻度 + 12 宫大刻度 + 四极天文指针）
//  - G: 反旋内星阵（双重互锁六芒星/十二芒星轨 + 6 座星座节点圆环 + 中圈轨道）
//  - B: 精密天体微文带（48 组篆星几何微文，环布于 r ∈ [0.805, 0.925]）
//  - A: 核心共振环与径向星轨导弧

in vec2 texCoord;

out vec4 fragColor;

float segDist(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1.0e-6), 0.0, 1.0);
    return length(pa - ba * h);
}

// 正 N 边形精确有向/无向边距离场
float sdPolyEdge(vec2 p, float radius, float sides, float phase) {
    float a = stSafeAtan(p.y, p.x) + phase;
    float sector = ST_TAU / sides;
    float local = mod(a + sector * 0.5, sector) - sector * 0.5;
    float apothem = radius * cos(ST_PI / sides);
    return abs(length(p) * cos(local) - apothem);
}

// 径向均匀刻度线段距离场（返回像素点到最近一条径向刻度线段的精确欧氏距离）
float sdRadialTicks(vec2 p, float r0, float r1, float count) {
    float r = length(p);
    if (r < 1.0e-4) {
        return r0;
    }
    float ang = stSafeAtan(p.y, p.x);
    float sector = ST_TAU / count;
    float localA = mod(ang + sector * 0.5, sector) - sector * 0.5;
    vec2 q = vec2(cos(localA), abs(sin(localA))) * r;
    return segDist(q, vec2(r0, 0.0), vec2(r1, 0.0));
}

// 48 组程序化篆星天文微文精确距离场
float sdCelestialGlyphBand(vec2 p, float rInner, float rOuter, float count) {
    float r = length(p);
    if (r < rInner - 0.06 || r > rOuter + 0.06) {
        return 0.25;
    }
    float ang = mod(stSafeAtan(p.y, p.x) + ST_TAU, ST_TAU);
    float t = ang / ST_TAU * count;
    float sectorId = floor(t);
    float sa = (fract(t) - 0.5) * 2.0; // [-1, 1]
    float rMid = 0.5 * (rInner + rOuter);
    float halfBand = 0.5 * (rOuter - rInner);
    float halfArc = (ST_PI * rMid) / count;
    // 转成局部等比空间（单位与平面半径一致，确保距离场真欧氏度量）
    vec2 local = vec2(sa * halfArc, r - rMid);
    float cellScale = min(halfArc, halfBand) * 0.72;
    vec2 g = local / max(cellScale, 1.0e-4);

    float h = stHash12(vec2(sectorId, 17.3));
    if (h < 0.08) {
        return 0.25;
    }
    vec2 pts[9] = vec2[9](
        vec2(-0.85, -0.85), vec2(0.0, -0.85), vec2(0.85, -0.85),
        vec2(-0.85,  0.00), vec2(0.0,  0.00), vec2(0.85,  0.00),
        vec2(-0.85,  0.85), vec2(0.0,  0.85), vec2(0.85,  0.85)
    );
    float d = 10.0;
    for (int i = 0; i < 3; i++) {
        float ha = stHash11(h * 83.7 + float(i) * 19.1);
        float hb = stHash11(h * 41.3 + float(i) * 11.7 + 2.3);
        int ia = int(ha * 8.999);
        int ib = (ia + 1 + int(hb * 7.999)) % 9;
        d = min(d, segDist(g, pts[ia], pts[ib]));
    }
    // 偶数格加中心星点圆环，增强天文星盘机械质感
    if (mod(sectorId, 4.0) < 0.5) {
        d = min(d, abs(length(g) - 0.42));
    }
    return d * cellScale;
}

void main() {
    vec2 p = (texCoord - 0.5) * 2.36;
    float r = length(p);

    // 1. 通道 R：外浑天仪主骨架（144 细分游标 + 12 宫大刻度 + 四极天文指向针 + 双护环）
    float dOuter = abs(r - 0.985);
    dOuter = min(dOuter, abs(r - 0.942));
    dOuter = min(dOuter, abs(r - 0.790));
    dOuter = min(dOuter, abs(r - 0.742));
    dOuter = min(dOuter, sdRadialTicks(p, 0.746, 0.784, 144.0));
    dOuter = min(dOuter, sdRadialTicks(p, 0.946, 0.982, 72.0));
    dOuter = min(dOuter, sdRadialTicks(p, 0.720, 0.792, 12.0));
    // 四极外延天文准星箭尖
    vec2 q4 = abs(p);
    vec2 qMajor = (q4.x > q4.y) ? q4 : q4.yx;
    dOuter = min(dOuter, segDist(qMajor, vec2(0.985, 0.0), vec2(1.095, 0.0)));
    dOuter = min(dOuter, segDist(qMajor, vec2(1.035, 0.042), vec2(1.095, 0.0)));
    dOuter = min(dOuter, segDist(qMajor, vec2(0.985, 0.032), vec2(1.035, 0.042)));

    // 2. 通道 G：反旋内星阵（双重六芒星 + 外接六边形 + 6 座行星/星座节点圆环）
    float dInner = min(sdPolyEdge(p, 0.735, 3.0, 0.0), sdPolyEdge(p, 0.735, 3.0, ST_PI / 3.0));
    dInner = min(dInner, sdPolyEdge(p, 0.635, 6.0, ST_PI / 6.0));
    dInner = min(dInner, abs(r - 0.435));
    dInner = min(dInner, abs(r - 0.395));
    for (int i = 0; i < 6; i++) {
        float a = float(i) * (ST_PI / 3.0) + ST_PI / 6.0;
        vec2 c = vec2(cos(a), sin(a)) * 0.565;
        dInner = min(dInner, abs(length(p - c) - 0.068));
        dInner = min(dInner, abs(length(p - c) - 0.034));
    }
    // 12 道径向连接导轨（仅连于中圈与内圈之间）
    dInner = min(dInner, sdRadialTicks(p, 0.395, 0.435, 24.0));

    // 3. 通道 B：48 篆星精密微文带（外圈）+ 18 篆星微文带（内圈）
    float dRunes = sdCelestialGlyphBand(p, 0.808, 0.924, 48.0);
    dRunes = min(dRunes, sdCelestialGlyphBand(p, 0.240, 0.365, 18.0));

    // 4. 通道 A：核心奇点准环与三瓣轨道共振弧
    float dCore = abs(r - 0.215);
    dCore = min(dCore, abs(r - 0.165));
    dCore = min(dCore, sdPolyEdge(p, 0.215, 6.0, 0.0));
    dCore = min(dCore, sdRadialTicks(p, 0.165, 0.215, 12.0));

    fragColor = vec4(
        clamp(dOuter, 0.0, 2.0),
        clamp(dInner, 0.0, 2.0),
        clamp(dRunes, 0.0, 2.0),
        clamp(dCore,  0.0, 2.0)
    );
}
