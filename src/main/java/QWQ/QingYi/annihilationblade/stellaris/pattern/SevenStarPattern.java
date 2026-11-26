package QWQ.QingYi.annihilationblade.stellaris.pattern;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * SA「真空衰变·坍缩（Vacuum Decay Collapse）」双端共享七星星座与时间轴契约。
 * <p>
 * 服务端波次判定与客户端渲染共用同一 {@code seed} 与时间常数，确保：
 * <ul>
 *   <li>44~96 tick 共精确 14 波（每 4 tick 一波，七星各两轮），100 tick 干净过渡到临界捏压，不再多打第 15 波；</li>
 *   <li>每波星矢落点与七座高空星座锚点 100% 确定性对齐（兼顾 Step 0.5 过渡视觉与 Step 7 星穹引擎终极演出）；</li>
 *   <li>通过 {@link #packIntParam} 在既有 {@code FxEventPacket.intParam} 中无损编码雷暴密度、视觉倍率与终幕清算拍点。</li>
 * </ul>
 */
public final class SevenStarPattern {
   public static final int STAR_COUNT = 7;
   public static final int VOLLEY_WAVES = 14;

   public static final int TICK_GRAVITY_LIFT = 28;
   public static final int TICK_VOLLEY_START = 44;
   public static final int TICK_VOLLEY_STEP = 4;
   /** 齐射结束上界（开区间）：44, 48, ..., 96 共 14 波。 */
   public static final int TICK_VOLLEY_END_EXCLUSIVE = TICK_VOLLEY_START + VOLLEY_WAVES * TICK_VOLLEY_STEP;
   /** 第 96 tick（第 14 波星矢落定瞬间）：天穹黑洞脱轨加速坠向地面中心。 */
   public static final int TICK_PLUNGE_START = 96;
   /** 第 100 tick：视界临界捏压，剥离增益并将敌群强力扯向黑洞坠落点中心悬停。 */
   public static final int TICK_CRITICAL_PINCH = 100;
   /** 第 116 tick：黑洞轰然坠地，触发地表马赫气浪与二次强向心压榨，进入过载暴涨期。 */
   public static final int TICK_PLUNGE_IMPACT = 116;
   /** 第 124 tick：黑洞由过载 1.72× 极速内爆坍缩为针尖奇点。 */
   public static final int TICK_IMPLOSION_START = 124;
   /** 第 129 tick：5 tick 绝对死寂呼吸拍起点（零雷、零震、针尖白热奇点悬停）。 */
   public static final int TICK_SILENCE_START = 129;
   /** 第 134 tick：超新星真空坍缩大爆炸与全场热寂同步清算。 */
   public static final int TICK_FINAL_RECKONING = 134;
   public static final int DEFAULT_DURATION = 184;

   private SevenStarPattern() {
   }

   /** 根据当前已运行 tick 计算七星齐射波次序号（0..13），不在齐射拍点则返回 -1。 */
   public static int volleyWaveIndex(int age) {
      if (age < TICK_VOLLEY_START || age >= TICK_VOLLEY_END_EXCLUSIVE) {
         return -1;
      }
      int offset = age - TICK_VOLLEY_START;
      return offset % TICK_VOLLEY_STEP == 0 ? offset / TICK_VOLLEY_STEP : -1;
   }

   /** 计算第 {@code starIndex}（0..6）座高空星座锚点相对结界中心的水平偏移。 */
   public static Vec3 starAnchorOffset(long seed, int starIndex, double radius) {
      int idx = Math.floorMod(starIndex, STAR_COUNT);
      double baseAngle = hash01(seed, 101) * Math.PI * 2.0;
      double angleStep = Math.PI * 2.0 / STAR_COUNT;
      double jitterAngle = (hash01(seed, 200 + idx * 17) - 0.5) * 0.34;
      double angle = baseAngle + idx * angleStep + jitterAngle;
      // 交错内外环半径，呈现自然星图层次而非机械正七边形
      double ringFactor = (idx % 2 == 0 ? 0.56 : 0.38) + hash01(seed, 300 + idx * 29) * 0.22;
      double r = Math.max(4.0, radius * ringFactor);
      return new Vec3(Math.cos(angle) * r, 0.0, Math.sin(angle) * r);
   }

   /** 计算第 {@code waveIndex}（0..13）波星矢的确定性落点世界坐标（Y 取中心基准，由双端各自贴地）。 */
   public static Vec3 waveImpactPos(long seed, int waveIndex, Vec3 center, double radius) {
      int clampedWave = Math.floorMod(waveIndex, VOLLEY_WAVES);
      int starIndex = clampedWave % STAR_COUNT;
      int round = clampedWave / STAR_COUNT;
      Vec3 anchor = starAnchorOffset(seed, starIndex, radius);
      // 第二轮略向内收缩并旋转小偏角，营造向视界收束的合围节奏
      double shrink = round == 0 ? 1.0 : 0.72;
      double fineAngle = hash01(seed, 500 + clampedWave * 31) * Math.PI * 2.0;
      double fineDist = radius * (0.06 + 0.08 * hash01(seed, 700 + clampedWave * 37));
      double ox = anchor.x * shrink + Math.cos(fineAngle) * fineDist;
      double oz = anchor.z * shrink + Math.sin(fineAngle) * fineDist;
      double maxR = Math.max(4.0, radius * 0.88);
      double len = Math.sqrt(ox * ox + oz * oz);
      if (len > maxR) {
         double scale = maxR / len;
         ox *= scale;
         oz *= scale;
      }
      return new Vec3(center.x + ox, center.y, center.z + oz);
   }

   /**
    * 打包 {@code FxEventPacket.intParam}（零协议破坏）：
    * <ul>
    *   <li>bits 0..7：过渡期客户端雷暴速率（0..24）；</li>
    *   <li>bits 8..15：视觉倍率×10（2..25，0 回退 1.0）；</li>
    *   <li>bits 16..27：终幕清算爆发 tick（如 122，0 回退整段结束）。</li>
    * </ul>
    */
   public static int packIntParam(int boltsPerTick, float visualScale, int reckoningTick) {
      int bolts = Mth.clamp(boltsPerTick, 0, 24);
      int scaleTenths = Mth.clamp(Math.round(visualScale * 10.0F), 2, 25);
      int reckoning = Mth.clamp(reckoningTick, 0, 4095);
      return bolts | (scaleTenths << 8) | (reckoning << 16);
   }

   public static int unpackBoltsPerTick(int intParam) {
      return Mth.clamp(intParam & 0xFF, 0, 24);
   }

   public static float unpackVisualScale(int intParam) {
      int tenths = (intParam >>> 8) & 0xFF;
      return tenths <= 0 ? 1.0F : Mth.clamp(tenths / 10.0F, 0.25F, 2.5F);
   }

   public static int unpackReckoningTick(int intParam, int fallbackEndAge) {
      int tick = (intParam >>> 16) & 0xFFF;
      return tick > 0 ? Math.min(tick, Math.max(1, fallbackEndAge)) : Math.max(1, fallbackEndAge);
   }

   /** 无状态 SplitMix64 哈希，返回 [0, 1) 均匀分布。 */
   private static double hash01(long seed, int salt) {
      long z = seed + 0x9E3779B97F4A7C15L * (salt + 1L);
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      z = z ^ (z >>> 31);
      return ((z >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
   }
}
