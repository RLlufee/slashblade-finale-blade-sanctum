// Stellaris Engine 世界空间零纹理遥测 SDF 图元库（全图元 fwidth 像素级抗锯齿：六边形格、七段数码管、单位字形、虚线环、瞄准括弧、科技电路迹线、星芒）

// 六边形网格：返回 vec4(cellCenter.xy, 距边缘距离, 单元哈希)
vec4 stHexGrid(vec2 p) {
    const vec2 s = vec2(1.0, 1.7320508);
    vec4 hC = floor(vec4(p, p - vec2(0.5, 1.0)) / s.xyxy) + 0.5;
    vec4 h = vec4(p - hC.xy * s, p - (hC.zw + 0.5) * s);
    vec4 res = (dot(h.xy, h.xy) < dot(h.zw, h.zw)) ? vec4(h.xy, hC.xy) : vec4(h.zw, hC.zw + 0.5);
    vec2 a = abs(res.xy);
    float edgeDist = 0.5 - max(dot(a, s * 0.5), a.x);
    return vec4(res.zw, edgeDist, stHash12(res.zw));
}

// 解析线段距离场
float stSegDist(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1.0e-6), 0.0, 1.0);
    return length(pa - ba * h);
}

// 像素级自适应抗锯齿线框辅助（避免远距离闪烁断笔、近距离边缘发糊）
float stAaStroke(float dist, float halfWidth) {
    float aa = clamp(fwidth(dist) * 1.15, 0.002, max(halfWidth * 1.6, 0.02));
    return 1.0 - smoothstep(halfWidth - aa * 0.5, halfWidth + aa, dist);
}

// 七段数码管单字 SDF（uv ∈ [-0.5, 0.5] × [-0.8, 0.8]，digit ∈ [0..9]）
float stSevenSegDigit(vec2 uv, int digit) {
    int mask = 0;
    if (digit == 0) mask = 63;
    else if (digit == 1) mask = 6;
    else if (digit == 2) mask = 91;
    else if (digit == 3) mask = 79;
    else if (digit == 4) mask = 102;
    else if (digit == 5) mask = 109;
    else if (digit == 6) mask = 125;
    else if (digit == 7) mask = 7;
    else if (digit == 8) mask = 127;
    else if (digit == 9) mask = 111;

    float d = 1.0e5;
    if ((mask & 1) != 0)  d = min(d, stSegDist(uv, vec2(-0.28,  0.56), vec2( 0.28,  0.56)));
    if ((mask & 2) != 0)  d = min(d, stSegDist(uv, vec2( 0.34,  0.50), vec2( 0.34,  0.06)));
    if ((mask & 4) != 0)  d = min(d, stSegDist(uv, vec2( 0.34, -0.06), vec2( 0.34, -0.50)));
    if ((mask & 8) != 0)  d = min(d, stSegDist(uv, vec2(-0.28, -0.56), vec2( 0.28, -0.56)));
    if ((mask & 16) != 0) d = min(d, stSegDist(uv, vec2(-0.34, -0.06), vec2(-0.34, -0.50)));
    if ((mask & 32) != 0) d = min(d, stSegDist(uv, vec2(-0.34,  0.50), vec2(-0.34,  0.06)));
    if ((mask & 64) != 0) d = min(d, stSegDist(uv, vec2(-0.26,  0.00), vec2( 0.26,  0.00)));
    return stAaStroke(d, 0.056);
}

// 单位字形：开尔文「K」（供 SE1 热寂温度读数 0.00 K 使用）
float stUnitK(vec2 uv) {
    float d = stSegDist(uv, vec2(-0.24, -0.54), vec2(-0.24, 0.54));
    d = min(d, stSegDist(uv, vec2(-0.22, 0.02), vec2(0.28, 0.54)));
    d = min(d, stSegDist(uv, vec2(-0.08, 0.14), vec2(0.30, -0.54)));
    return stAaStroke(d, 0.052);
}

// 单位字形：光年「ly」（供 SA 七星锚点测距遥测使用）
float stUnitLy(vec2 uv) {
    float dl = stSegDist(uv - vec2(-0.32, 0.0), vec2(0.0, -0.52), vec2(0.0, 0.52));
    vec2 py = uv - vec2(0.24, 0.0);
    float dy = min(
        stSegDist(py, vec2(-0.22, 0.20), vec2(0.0, -0.18)),
        stSegDist(py, vec2(0.22, 0.20), vec2(-0.16, -0.56))
    );
    return stAaStroke(min(dl, dy), 0.050);
}

// 4 位含两位小数数码管读数（例如 00.00 ~ 99.99）
float stReadoutXX_XX(vec2 p, float value) {
    float v = clamp(value, 0.0, 99.99);
    int scaled = int(floor(v * 100.0 + 0.5));
    int d0 = (scaled / 1000) % 10;
    int d1 = (scaled / 100) % 10;
    int d2 = (scaled / 10) % 10;
    int d3 = scaled % 10;

    float outVal = 0.0;
    outVal = max(outVal, stSevenSegDigit(p - vec2(-1.65, 0.0), d0));
    outVal = max(outVal, stSevenSegDigit(p - vec2(-0.62, 0.0), d1));
    outVal = max(outVal, stAaStroke(length(p - vec2(0.0, -0.52)), 0.072));
    outVal = max(outVal, stSevenSegDigit(p - vec2( 0.62, 0.0), d2));
    outVal = max(outVal, stSevenSegDigit(p - vec2( 1.65, 0.0), d3));
    return outVal;
}

// 带虚线与刻度的同心测距环（径向 + 防 ±π 跳变角向双重 fwidth 抗锯齿）
float stDashedRing(vec2 p, float radius, float halfWidth, float segCount, float rotAngle) {
    float r = length(p);
    float ring = stAaStroke(abs(r - radius), halfWidth);
    if (segCount <= 0.5) {
        return ring;
    }
    float ang = stSafeAtan(p.y, p.x) + rotAngle;
    float rawPhase = ang * segCount / ST_TAU;
    float phase = fract(rawPhase);
    float phaseShift = fract(rawPhase + 0.5);
    float angAa = clamp(min(fwidth(phase), fwidth(phaseShift)) * 1.15, 0.015, 0.16);
    float dash = smoothstep(0.14 - angAa, 0.14 + angAa, phase)
               * (1.0 - smoothstep(0.86 - angAa, 0.86 + angAa, phase));
    return ring * dash;
}

// 四角瞄准括弧 SDF
float stCornerBrackets(vec2 p, vec2 halfExt, float cornerLen, float thick) {
    vec2 q = abs(p);
    float dH = stSegDist(q, vec2(halfExt.x - cornerLen, halfExt.y), halfExt);
    float dV = stSegDist(q, vec2(halfExt.x, halfExt.y - cornerLen), halfExt);
    return stAaStroke(min(dH, dV), thick);
}

// 跨格无缝连通的轨道科技电路迹线（共享边中点拓扑 + 45° 倒角走线 + 端节点焊盘过孔 + 流动脉冲）
float stCircuitTraces(vec2 p, float time) {
    vec2 cell = floor(p);
    vec2 f = fract(p) - 0.5;

    // 4 个方向共享边哈希：本格右/上边与邻格左/下边严格同一键，确保跨格 100% 连续无悬空断头
    bool portL = stHash13(vec3(cell.x,       cell.y,       0.0)) > 0.44;
    bool portR = stHash13(vec3(cell.x + 1.0, cell.y,       0.0)) > 0.44;
    bool portB = stHash13(vec3(cell.x,       cell.y,       1.0)) > 0.44;
    bool portT = stHash13(vec3(cell.x,       cell.y + 1.0, 1.0)) > 0.44;

    int deg = (portL ? 1 : 0) + (portR ? 1 : 0) + (portB ? 1 : 0) + (portT ? 1 : 0);
    if (deg == 0) {
        return 0.0;
    }

    float trace = 1.0e5;
    // 相邻两垂直端口用真实 45° 倒角拐弯走线，其余端口汇入格心节点
    if (deg == 2 && portL && portT) {
        trace = min(trace, stSegDist(f, vec2(-0.5, 0.0), vec2(-0.18, 0.0)));
        trace = min(trace, stSegDist(f, vec2(-0.18, 0.0), vec2(0.0, 0.18)));
        trace = min(trace, stSegDist(f, vec2(0.0, 0.18), vec2(0.0, 0.5)));
    } else if (deg == 2 && portT && portR) {
        trace = min(trace, stSegDist(f, vec2(0.0, 0.5), vec2(0.0, 0.18)));
        trace = min(trace, stSegDist(f, vec2(0.0, 0.18), vec2(0.18, 0.0)));
        trace = min(trace, stSegDist(f, vec2(0.18, 0.0), vec2(0.5, 0.0)));
    } else if (deg == 2 && portR && portB) {
        trace = min(trace, stSegDist(f, vec2(0.5, 0.0), vec2(0.18, 0.0)));
        trace = min(trace, stSegDist(f, vec2(0.18, 0.0), vec2(0.0, -0.18)));
        trace = min(trace, stSegDist(f, vec2(0.0, -0.18), vec2(0.0, -0.5)));
    } else if (deg == 2 && portB && portL) {
        trace = min(trace, stSegDist(f, vec2(0.0, -0.5), vec2(0.0, -0.18)));
        trace = min(trace, stSegDist(f, vec2(0.0, -0.18), vec2(-0.18, 0.0)));
        trace = min(trace, stSegDist(f, vec2(-0.18, 0.0), vec2(-0.5, 0.0)));
    } else {
        if (portL) trace = min(trace, stSegDist(f, vec2(-0.5, 0.0), vec2(0.0, 0.0)));
        if (portR) trace = min(trace, stSegDist(f, vec2( 0.5, 0.0), vec2(0.0, 0.0)));
        if (portB) trace = min(trace, stSegDist(f, vec2( 0.0,-0.5), vec2(0.0, 0.0)));
        if (portT) trace = min(trace, stSegDist(f, vec2( 0.0, 0.5), vec2(0.0, 0.0)));
    }

    float line = stAaStroke(trace, 0.028);
    // 在终端死头（deg == 1）与三/四通枢纽（deg >= 3）放置圆形焊盘过孔
    float via = (deg == 1 || deg >= 3) ? stAaStroke(abs(length(f) - 0.088), 0.024) : 0.0;
    float h = stHash12(cell);
    float pulse = 0.44 + 0.56 * sin((cell.x * 1.1 + cell.y * 1.5) - time * 5.2 + h * ST_TAU);
    return max(line * pulse, via * 1.25);
}

// 韦伯/光学镜头星芒基元（核心 + 十字芒 + 45° 斜芒 + 柔晕）
float stGlint(vec2 d, float radius) {
    vec2 p = d / max(radius, 1.0e-4);
    float r = length(p);
    float core = exp(-r * r * 18.0);
    float halo = exp(-r * 3.2) * 0.45;
    float crossMain = exp(-abs(p.y) * 26.0 - abs(p.x) * 2.4) + exp(-abs(p.x) * 26.0 - abs(p.y) * 2.4);
    vec2 diag = stRot2(0.78539816) * p;
    float crossDiag = (exp(-abs(diag.y) * 32.0 - abs(diag.x) * 4.2) + exp(-abs(diag.x) * 32.0 - abs(diag.y) * 4.2)) * 0.48;
    return core * 1.4 + halo + crossMain * 0.85 + crossDiag;
}
