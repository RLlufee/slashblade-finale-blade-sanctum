// Stellaris Engine 广义相对论与解析引力透镜库（GLSL 150 原创物理公式实现，需先引入 st_common）

// 史瓦西零测地线光线加速度：a = -(3/2) * r_s * h^2 * x / r^5，其中 h = |x × v|
vec3 stSchwarzschildAccel(vec3 pos, float h2, float rs) {
    float r2 = max(dot(pos, pos), 1.0e-5);
    float r5 = r2 * r2 * sqrt(r2);
    return (-1.5 * rs * h2 / max(r5, 1.0e-7)) * pos;
}

// 自适应 RK4 测地线积分步长：靠近视界自动收细，远离视界平滑放大兼顾精度与帧率
float stAdaptiveGeodesicDt(float r, float rs, float qualityScale) {
    float nearCap = 0.30 * max(r - rs, 0.0) + 0.025 * rs;
    return min(qualityScale * 0.065 * r, nearCap);
}

// 单步 4 阶龙格-库塔（RK4）零测地线积分（已数值验算临界冲击参数 b_c = 2.59801 * r_s）
void stRk4GeodesicStep(inout vec3 pos, inout vec3 dir, float rs, float dt) {
    vec3 hVec = cross(pos, dir);
    float h2 = dot(hVec, hVec);

    vec3 k1x = dir;
    vec3 k1v = stSchwarzschildAccel(pos, h2, rs);

    vec3 p2 = pos + 0.5 * dt * k1x;
    vec3 v2 = dir + 0.5 * dt * k1v;
    vec3 k2x = v2;
    vec3 k2v = stSchwarzschildAccel(p2, h2, rs);

    vec3 p3 = pos + 0.5 * dt * k2x;
    vec3 v3 = dir + 0.5 * dt * k2v;
    vec3 k3x = v3;
    vec3 k3v = stSchwarzschildAccel(p3, h2, rs);

    vec3 p4 = pos + dt * k3x;
    vec3 v4 = dir + dt * k3v;
    vec3 k4x = v4;
    vec3 k4v = stSchwarzschildAccel(p4, h2, rs);

    pos += (dt / 6.0) * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
    dir = normalize(dir + (dt / 6.0) * (k1v + 2.0 * k2v + 2.0 * k3v + k4v));
}

// 球外远场解析偏折（爱因斯坦广义相对论弱场近似 Δα = 2 * r_s / b，零步进用于透镜球外加速）
vec3 stFarDeflect(vec3 pos, vec3 dir, float rs) {
    vec3 hVec = cross(pos, dir);
    float b = length(hVec);
    if (b < rs * 1.5) {
        return dir;
    }
    vec3 perpToCenter = normalize(cross(hVec, dir));
    float alpha = min(2.0 * rs / max(b, 1.0e-4), 0.75);
    return normalize(dir * cos(alpha) - perpToCenter * sin(alpha));
}

// 临界冲击参数 b_c = (3 * sqrt(3) / 2) * r_s ≈ 2.598076 * r_s 处的抗闪烁光子环能量
float stPhotonRingGlow(float impactParam, float rs, float minBandwidth) {
    float bc = 2.59807621 * rs;
    float bw = max(minBandwidth, 0.032 * rs);
    float delta = (impactParam - bc) / bw;
    return exp(-delta * delta);
}

// 相对论多普勒聚束因子 g = 1 / (γ * (1 - β · n̂))，其中 toObserver 为指向观察者的单位向量
float stDopplerFactor(vec3 beta, vec3 toObserver) {
    float b2 = min(dot(beta, beta), 0.90);
    float gamma = inversesqrt(max(1.0 - b2, 0.05));
    float denom = max(1.0 - dot(beta, toObserver), 0.12);
    return 1.0 / (gamma * denom);
}

// 史瓦西引力红移因子 sqrt(1 - r_s / r)
float stGravitationalRedshift(float r, float rs) {
    return sqrt(max(1.0 - rs / max(r, rs * 1.01), 0.02));
}

// 相对论光行差偏转：cosθ' = (cosθ - β) / (1 - β·cosθ)
vec3 stAberrationDir(vec3 dir, vec3 velDir, float beta) {
    float b = clamp(beta, -0.75, 0.75);
    if (abs(b) < 1.0e-4) {
        return dir;
    }
    vec3 v = normalize(velDir);
    float c = dot(dir, v);
    vec3 perp = dir - v * c;
    float invDen = 1.0 / max(1.0 - b * c, 0.15);
    float cPrime = (c - b) * invDen;
    float sScale = sqrt(max(1.0 - b * b, 0.05)) * invDen;
    return normalize(v * cPrime + perp * sScale);
}

// 弱场点质量解析引力透镜：θ_src = θ - θ_E^2 / θ（θ < θ_E 时自然产生镜像与爱因斯坦环，零步进）
vec2 stPointMassLens(vec2 delta, float thetaE) {
    float theta = length(delta);
    if (theta < 1.0e-5) {
        return delta;
    }
    float mapped = theta - (thetaE * thetaE) / max(theta, thetaE * 0.14);
    return delta * (mapped / theta);
}
