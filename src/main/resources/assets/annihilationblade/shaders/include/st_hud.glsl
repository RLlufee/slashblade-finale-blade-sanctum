// Stellaris Engine 世界空间零纹理遥测 SDF 图元库（Step 1.6 升格：彻底消除动态分支内 fwidth UB，改用解析像素脚印抗锯齿！）
// 全部图元支持传入由距离与掠射角解析推算的 pxFootprint（stAnalyticFootprint），在方块 90° 棱角、电路断头与远视距下 100% 零锯齿黑缝与驱动未定义行为。

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

// 零 UB 解析像素脚印抗锯齿描边（推荐：由外部用 stAnalyticFootprint 算好传入，绝不触发动态分支导数异常）
float stAaStrokeFp(float dist, float halfWidth, float pxFootprint) {
    float aa = clamp(pxFootprint * 1.15, 0.002, max(halfWidth * 1.8, 0.025));
    return 1.0 - smoothstep(halfWidth - aa * 0.45, halfWidth + aa, dist);
}

// 兼容无参入口（仅允许在一致控制流中使用，内部仍给保守下限防异常）
float stAaStroke(float dist, float halfWidth) {
    return stAaStrokeFp(dist, halfWidth, max(halfWidth * 0.45, 0.006));
}

// 七段数码管单字 SDF（解析抗锯齿版）
float stSevenSegDigitFp(vec2 uv, int digit, float pxFootprint) {
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
    return stAaStrokeFp(d, 0.058, pxFootprint);
}

float stSevenSegDigit(vec2 uv, int digit) {
    return stSevenSegDigitFp(uv, digit, 0.018);
}

// 单位字形：开尔文「K」（供 SE1 热寂温度读数 0.00 K 使用）
float stUnitKFp(vec2 uv, float pxFootprint) {
    float d = stSegDist(uv, vec2(-0.24, -0.54), vec2(-0.24, 0.54));
    d = min(d, stSegDist(uv, vec2(-0.22, 0.02), vec2(0.28, 0.54)));
    d = min(d, stSegDist(uv, vec2(-0.08, 0.14), vec2(0.30, -0.54)));
    return stAaStrokeFp(d, 0.054, pxFootprint);
}

float stUnitK(vec2 uv) {
    return stUnitKFp(uv, 0.018);
}

// 单位字形：光年「ly」（供 SA 七星锚点测距遥测使用）
float stUnitLyFp(vec2 uv, float pxFootprint) {
    float dl = stSegDist(uv - vec2(-0.32, 0.0), vec2(0.0, -0.52), vec2(0.0, 0.52));
    vec2 py = uv - vec2(0.24, 0.0);
    float dy = min(
        stSegDist(py, vec2(-0.22, 0.20), vec2(0.0, -0.18)),
        stSegDist(py, vec2(0.22, 0.20), vec2(-0.16, -0.56))
    );
    return stAaStrokeFp(min(dl, dy), 0.052, pxFootprint);
}

float stUnitLy(vec2 uv) {
    return stUnitLyFp(uv, 0.018);
}

// 4 位含两位小数数码管读数（例如 00.00 ~ 99.99，零 UB 解析抗锯齿）
float stReadoutXX_XX_Fp(vec2 p, float value, float pxFootprint) {
    float v = clamp(value, 0.0, 99.99);
    int scaled = int(floor(v * 100.0 + 0.5));
    int d0 = (scaled / 1000) % 10;
    int d1 = (scaled / 100) % 10;
    int d2 = (scaled / 10) % 10;
    int d3 = scaled % 10;

    float outVal = 0.0;
    outVal = max(outVal, stSevenSegDigitFp(p - vec2(-1.65, 0.0), d0, pxFootprint));
    outVal = max(outVal, stSevenSegDigitFp(p - vec2(-0.62, 0.0), d1, pxFootprint));
    outVal = max(outVal, stAaStrokeFp(length(p - vec2(0.0, -0.52)), 0.072, pxFootprint));
    outVal = max(outVal, stSevenSegDigitFp(p - vec2( 0.62, 0.0), d2, pxFootprint));
    outVal = max(outVal, stSevenSegDigitFp(p - vec2( 1.65, 0.0), d3, pxFootprint));
    return outVal;
}

float stReadoutXX_XX(vec2 p, float value) {
    return stReadoutXX_XX_Fp(p, value, 0.018);
}

// 带虚线与刻度的同心测距环（解析径向 + 角向抗锯齿，零 fwidth 分支 UB）
float stDashedRingFp(vec2 p, float radius, float halfWidth, float segCount, float rotAngle, float pxFootprint) {
    float r = length(p);
    float ring = stAaStrokeFp(abs(r - radius), halfWidth, pxFootprint);
    if (segCount <= 0.5 || ring <= 0.001) {
        return ring;
    }
    float ang = stSafeAtan(p.y, p.x) + rotAngle;
    float phase = fract(ang * segCount / ST_TAU);
    // 按周长精确推导角相位像素宽度：arcPerSeg = 2πr / segCount
    float arcPerSeg = max((ST_TAU * max(r, 0.2)) / segCount, 1.0e-3);
    float angAa = clamp(pxFootprint / arcPerSeg * 1.15, 0.015, 0.16);
    float dash = smoothstep(0.14 - angAa, 0.14 + angAa, phase)
               * (1.0 - smoothstep(0.86 - angAa, 0.86 + angAa, phase));
    return ring * dash;
}

float stDashedRing(vec2 p, float radius, float halfWidth, float segCount, float rotAngle) {
    return stDashedRingFp(p, radius, halfWidth, segCount, rotAngle, 0.02);
}

// 四角瞄准括弧 SDF
float stCornerBracketsFp(vec2 p, vec2 halfExt, float cornerLen, float thick, float pxFootprint) {
    vec2 q = abs(p);
    float dH = stSegDist(q, vec2(halfExt.x - cornerLen, halfExt.y), halfExt);
    float dV = stSegDist(q, vec2(halfExt.x, halfExt.y - cornerLen), halfExt);
    return stAaStrokeFp(min(dH, dV), thick, pxFootprint);
}

float stCornerBrackets(vec2 p, vec2 halfExt, float cornerLen, float thick) {
    return stCornerBracketsFp(p, halfExt, cornerLen, thick, 0.015);
}

// 跨格无缝连通的轨道科技电路迹线（解析像素脚印抗锯齿，彻底杜绝 deg==0 提前返回引发的 2x2 Quad 导数伪影）
float stCircuitTracesFp(vec2 p, float time, float pxFootprint) {
    vec2 cell = floor(p);
    vec2 f = fract(p) - 0.5;

    bool portL = stHash13(vec3(cell.x,       cell.y,       0.0)) > 0.44;
    bool portR = stHash13(vec3(cell.x + 1.0, cell.y,       0.0)) > 0.44;
    bool portB = stHash13(vec3(cell.x,       cell.y,       1.0)) > 0.44;
    bool portT = stHash13(vec3(cell.x,       cell.y + 1.0, 1.0)) > 0.44;

    int deg = (portL ? 1 : 0) + (portR ? 1 : 0) + (portB ? 1 : 0) + (portT ? 1 : 0);
    if (deg == 0) {
        return 0.0;
    }

    float trace = 1.0e5;
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

    float line = stAaStrokeFp(trace, 0.028, pxFootprint);
    float via = (deg == 1 || deg >= 3) ? stAaStrokeFp(abs(length(f) - 0.088), 0.024, pxFootprint) : 0.0;
    float h = stHash12(cell);
    float pulse = 0.44 + 0.56 * sin((cell.x * 1.1 + cell.y * 1.5) - time * 5.2 + h * ST_TAU);
    return max(line * pulse, via * 1.30);
}

float stCircuitTraces(vec2 p, float time) {
    return stCircuitTracesFp(p, time, 0.015);
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
