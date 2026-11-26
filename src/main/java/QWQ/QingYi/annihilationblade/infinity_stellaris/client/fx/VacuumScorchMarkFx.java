package QWQ.QingYi.annihilationblade.infinity_stellaris.client.fx;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.core.FxInstance;
import QWQ.QingYi.annihilationblade.render.client.core.FxPass;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry.Style;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxShaders;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * 无尽星空 SA「真空衰变·坍缩」非破坏地形黑洞烤焦焦痕与 8 秒冷却余烬特效。
 * <p>
 * 零方块破坏、纯客户端独立生命周期：
 * <pre>
 *   0 ─ detonateDelay        黑洞砸地瞬间在落点炙烤出深黑初焦坑与熔融龟裂
 *   detonateDelay + 0..26t   紧随超新星相变刀锋前沿横扫全境，将受灾地表/树冠/建筑烤成黢黑炭化焦土
 *   detonateDelay + 0..104t  焦土保持深黑炭化质感，3D Voronoi 地缝沿黑体色温（白热→炽橙→暗红余炭）
 *                            与爆心星渊幽青残脉逐秒冷却，伴随上升热浪空气折射、袅袅黑烟丝与飘浮火星
 *   detonateDelay + 104..160t（爆炸后整整 8.0 秒 = 160 tick）沿 3D 焦灰噪声由外向内优雅消融复原
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public final class VacuumScorchMarkFx extends FxInstance {
   /** 爆炸之后持续的总时长：8.0 秒 = 160 tick。 */
   public static final int POST_DETONATION_TICKS = 160;
   /** 末段优雅消融渐隐窗口（最后 2.8 秒 = 56 tick）。 */
   private static final int DISSOLVE_TICKS = 56;
   private static final int MAX_EMBERS = 140;
   private static final int MAX_PLUMES = 26;

   private static final float[] EMBER_HOT = {1.0F, 0.72F, 0.28F};
   private static final float[] EMBER_RED = {1.0F, 0.26F, 0.05F};
   private static final float[] STELLAR_CYAN = {0.14F, 0.94F, 1.0F};
   private static final float[] SOOT_DARK = {0.06F, 0.05F, 0.08F};

   private final Vec3 groundOrigin;
   private final float impactRadius;
   private final float maxRadius;
   private final float coreRadius;
   private final float visualScale;
   private final boolean enclosedSpace;
   private final int detonateDelay;
   private final float seedFloat;
   private final RandomSource random;
   private final List<SmokePlume> plumes = new ArrayList<>();
   private final List<ScorchEmber> embers = new ArrayList<>();

   public VacuumScorchMarkFx(
      Vec3 groundOrigin,
      float zoneRadius,
      float coreMax,
      float visualScale,
      boolean enclosedSpace,
      int detonateDelayTicks,
      long seed,
      int initialAge
   ) {
      super(Math.max(0, detonateDelayTicks) + POST_DETONATION_TICKS);
      this.groundOrigin = groundOrigin;
      this.maxRadius = Math.max(10.0F, zoneRadius * 1.04F);
      this.coreRadius = Math.max(2.2F, coreMax);
      this.impactRadius = Math.min(this.maxRadius * 0.44F, Math.max(coreMax * 3.8F, zoneRadius * 0.28F));
      this.visualScale = Mth.clamp(visualScale, 0.5F, 1.8F);
      this.enclosedSpace = enclosedSpace;
      this.detonateDelay = Math.max(0, detonateDelayTicks);
      this.seedFloat = (float) ((seed & 0xFFFFL) / 65535.0 * 97.0);
      this.random = RandomSource.create(seed ^ 0x5C0BC471L);
      this.age = Mth.clamp(initialAge, 0, Math.max(0, this.lifetime - 1));
   }

   @Override
   public Vec3 focus() {
      return this.groundOrigin;
   }

   @Override
   public double cullDistance() {
      return Math.max(320.0, this.maxRadius + 240.0);
   }

   @Override
   public boolean hasBody() {
      return true;
   }

   // ───────────────────────────── 状态曲线（精确 8 秒冷却消融） ─────────────────────────────

   /** 当前已烤焦覆盖半径：砸地先烧出核心焦坑，爆炸瞬间紧随相变刀锋前沿铺满受灾大地。 */
   private float currentRadius(float a) {
      if (a < this.detonateDelay) {
         float t = FxMath.easeOutCubic(FxMath.window(a, 0.0F, Math.max(4.0F, this.detonateDelay * 0.55F)));
         return this.impactRadius * (0.35F + 0.65F * t);
      }
      // 紧随主相变波前（38t 扩至 2.02*half），26 tick 内铺至 maxRadius，始终贴在冰青刀锋后半拍
      float spread = FxMath.easeOutCubic(FxMath.window(a, this.detonateDelay, this.detonateDelay + 26.0F));
      return FxMath.lerp(this.impactRadius, this.maxRadius, spread);
   }

   /** 焦黑炭化层总可见度：砸地瞬起，最后 24 tick 平滑归零。 */
   private float charVisibility(float a) {
      float in = FxMath.easeOutCubic(FxMath.window(a, 0.0F, 5.0F));
      float end = this.detonateDelay + POST_DETONATION_TICKS;
      float out = 1.0F - FxMath.easeInCubic(FxMath.window(a, end - 26.0F, end));
      return Mth.clamp(in * out, 0.0F, 1.0F);
   }

   /** 物理冷却余热温度（0..1）：砸地高热 → 爆炸冲顶 1.0 → 随后 8 秒按自然指数曲线从白热冷至熄灭。 */
   private float heatLevel(float a) {
      if (a < this.detonateDelay) {
         float ramp = FxMath.window(a, 0.0F, Math.max(1.0F, this.detonateDelay));
         return 0.72F + 0.28F * ramp;
      }
      float sinceSec = Math.max(0.0F, (a - this.detonateDelay) / 20.0F);
      float expCool = (float) Math.exp(-sinceSec * 0.34);
      float tailCut = 1.0F - FxMath.window(a, this.detonateDelay + POST_DETONATION_TICKS - 24.0F, this.detonateDelay + POST_DETONATION_TICKS);
      return Mth.clamp(expCool * tailCut, 0.0F, 1.0F);
   }

   /** 8 秒末段噪声消融进度（爆炸后 5.2s → 8.0s，由外圈向爆心丝滑褪去）。 */
   private float dissolveProgress(float a) {
      float start = this.detonateDelay + POST_DETONATION_TICKS - DISSOLVE_TICKS;
      float end = this.detonateDelay + POST_DETONATION_TICKS;
      return FxMath.easeInOutSine(FxMath.window(a, start, end));
   }

   /** 焦坑上空空气热浪畸变强度。 */
   private float shimmerLevel(float a) {
      float h = this.heatLevel(a);
      float vis = this.charVisibility(a);
      return Mth.clamp(h * vis * 0.92F, 0.0F, 1.0F);
   }

   // ───────────────────────────── 客户端 tick：黑烟丝、余烬火星与余炭音效 ─────────────────────────────

   @Override
   public void tick(ClientLevel level) {
      int age = this.ageTicks();
      float vis = this.charVisibility(age);
      float heat = this.heatLevel(age);
      float curR = this.currentRadius(age);
      Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

      // 更新焦烟丝与余烬火星
      Iterator<SmokePlume> plumeIt = this.plumes.iterator();
      while (plumeIt.hasNext()) {
         SmokePlume plume = plumeIt.next();
         plume.age++;
         if (plume.age >= plume.maxAge) {
            plumeIt.remove();
         }
      }
      Iterator<ScorchEmber> emberIt = this.embers.iterator();
      while (emberIt.hasNext()) {
         ScorchEmber ember = emberIt.next();
         ember.tick();
         if (ember.life <= 0) {
            emberIt.remove();
         }
      }

      // 在焦黑尚未进入最末消散期前，持续在真实地表升起暗黑焦烟丝与炽热余烬火星
      if (vis > 0.18F && age < this.lifetime - 18) {
         // 1. 袅袅上升的暗黑焦烟丝（主要集中在爆心与受灾重度炭化区）
         if (this.plumes.size() < MAX_PLUMES && age % 3 == 0 && (age >= this.detonateDelay || age % 6 == 0)) {
            int spawnPlumes = age >= this.detonateDelay && heat > 0.35F ? 2 : 1;
            for (int i = 0; i < spawnPlumes && this.plumes.size() < MAX_PLUMES; i++) {
               Vec3 base = this.sampleSurfacePos(level, curR * (0.08F + 0.68F * this.random.nextFloat()));
               float height = (6.0F + this.random.nextFloat() * 11.0F) * Mth.clamp(this.visualScale, 0.7F, 1.4F);
               float radius = 0.65F + this.random.nextFloat() * 1.15F;
               float driftX = (this.random.nextFloat() - 0.5F) * 2.4F;
               float driftZ = (this.random.nextFloat() - 0.5F) * 2.4F;
               int maxAge = 34 + this.random.nextInt(26);
               this.plumes.add(new SmokePlume(base, height, radius, driftX, driftZ, this.random.nextFloat() * FxMath.TAU, maxAge));
            }
         }

         // 2. 焦土升腾火星（橙红余炭为主，爆心夹杂星渊冰青辐射星屑，随冷却平滑减量）
         int emberBudget = Math.max(1, Math.round((2.0F + 6.5F * heat) * Mth.clamp(this.visualScale, 0.65F, 1.35F)));
         for (int i = 0; i < emberBudget && this.embers.size() < MAX_EMBERS; i++) {
            float rFrac = (float) Math.pow(this.random.nextDouble(), 0.72);
            Vec3 pos = this.sampleSurfacePos(level, curR * (0.04F + 0.88F * rFrac)).add(0.0, 0.18, 0.0);
            float[] col;
            if (rFrac < 0.24F && this.random.nextFloat() < 0.36F) {
               col = STELLAR_CYAN;
            } else if (this.random.nextFloat() < heat * 0.72F) {
               col = EMBER_HOT;
            } else {
               col = EMBER_RED;
            }
            double vx = (this.random.nextDouble() - 0.5) * 0.08;
            double vy = 0.07 + this.random.nextDouble() * (0.14 + 0.12 * heat);
            double vz = (this.random.nextDouble() - 0.5) * 0.08;
            this.embers.add(new ScorchEmber(pos, vx, vy, vz, 18 + this.random.nextInt(24), this.random.nextFloat() * FxMath.TAU, col));
         }
      }

      // 3. 克制的灾后余炭噼啪声（仅爆炸后前 5.5 秒在听距内轻响）
      int post = age - this.detonateDelay;
      if (post > 16 && post < 116 && post % 28 == 0 && camera.distanceToSqr(this.groundOrigin) < (this.maxRadius + 36.0) * (this.maxRadius + 36.0)) {
         float vol = 0.45F + 0.65F * heat;
         level.playLocalSound(this.groundOrigin.x, this.groundOrigin.y + 1.0, this.groundOrigin.z, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.AMBIENT, vol, 0.68F + this.random.nextFloat() * 0.25F, false);
      }
   }

   private Vec3 sampleSurfacePos(ClientLevel level, float radius) {
      double ang = this.random.nextDouble() * Math.PI * 2.0;
      double x = this.groundOrigin.x + Math.cos(ang) * radius;
      double z = this.groundOrigin.z + Math.sin(ang) * radius;
      if (this.enclosedSpace) {
         return new Vec3(x, this.groundOrigin.y + 0.1, z);
      }
      int bx = Mth.floor(x);
      int bz = Mth.floor(z);
      if (!level.hasChunk(bx >> 4, bz >> 4)) {
         return new Vec3(x, this.groundOrigin.y + 0.1, z);
      }
      int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
      double y = (h <= level.getMinBuildHeight() || Math.abs(h - this.groundOrigin.y) > 22.0) ? this.groundOrigin.y : h;
      return new Vec3(x, y + 0.08, z);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int age = this.ageTicks();
      float heat = this.heatLevel(age);
      if (heat <= 0.05F || age % 2 != 0) {
         return;
      }
      float curR = this.currentRadius(age);
      for (int i = 0; i < 6; i++) {
         Vec3 p = this.sampleSurfacePos(level, curR * this.random.nextFloat());
         level.addParticle(i % 2 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME, p.x, p.y + 0.2, p.z, 0.0, 0.08, 0.0);
      }
   }

   // ───────────────────────────── Body 暗烟层 & HDR 余烬火星层 ─────────────────────────────

   @Override
   public void renderBody(FxGeometry body, FxFrame frame) {
      if (this.plumes.isEmpty()) {
         return;
      }
      float a = this.age(frame.partialTick);
      float vis = this.charVisibility(a);
      if (vis <= 0.02F) {
         return;
      }

      // 焦土上空缓慢飘摇升腾的暗炭热烟（预乘 Alpha 暗层，在白天蓝天下清晰衬出灾后焦烟质感）
      body.begin(Style.PLASMA_BEAM, 0.32F, 0.75F, 1.0F, -0.55F, 0.35F, 0.0F);
      for (SmokePlume plume : this.plumes) {
         float p = Mth.clamp((plume.age + frame.partialTick) / plume.maxAge, 0.0F, 1.0F);
         float env = FxMath.envelope(p, 0.22F, 0.55F) * vis * 0.42F;
         if (env <= 0.01F) {
            continue;
         }
         float riseH = plume.height * (0.35F + 0.65F * FxMath.easeOutCubic(p));
         float sway = (float) Math.sin(a * 0.07F + plume.phase) * 0.65F;
         Vec3 topPos = plume.base.add(plume.driftX * p + sway, riseH, plume.driftZ * p - sway * 0.6F);
         float baseR = plume.radius * (0.65F + 0.35F * p);
         float topR = plume.radius * (1.25F + 1.15F * p);
         body.cylinder(frame.rel(plume.base.subtract(0.0, 0.3, 0.0)), frame.rel(topPos), baseR, topR, 10, plume.phase + p * 0.6F, SOOT_DARK[0], SOOT_DARK[1], SOOT_DARK[2], env);
      }
      body.end();
   }

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      if (this.embers.isEmpty()) {
         return;
      }
      float a = this.age(frame.partialTick);
      float vis = this.charVisibility(a);
      float heat = this.heatLevel(a);
      if (vis <= 0.02F) {
         return;
      }

      // 从焦黑地面缓缓升腾并明暗呼吸的滚烫余烬火星
      g.begin(Style.RIBBON, 4.6F + 2.8F * heat, 0.0F, 6.0F, 0.0F, 0.0F, 0.65F);
      for (ScorchEmber ember : this.embers) {
         float lifeFrac = (float) ember.life / ember.maxLife;
         float twinkle = 0.55F + 0.45F * (float) Math.sin(a * 0.45F + ember.phase);
         float alpha = lifeFrac * twinkle * vis;
         if (alpha <= 0.01F) {
            continue;
         }
         Vec3 p = ember.position(frame.partialTick);
         Vec3 tail = p.subtract(ember.vx * 3.8, ember.vy * 3.8, ember.vz * 3.8);
         g.streak(frame.rel(p), frame.rel(tail), 0.065F, ember.color[0], ember.color[1], ember.color[2], alpha);
      }
      g.end();
   }

   // ───────────────────────────── B 档全屏焦黑地形与余烬泛光通道 ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      float a = this.age(frame.partialTick);
      float charVis = this.charVisibility(a);
      if (charVis <= 0.004F) {
         return;
      }
      Vector3f relOrigin = frame.rel(this.groundOrigin);
      float activeRadius = this.currentRadius(a);
      float heat = this.heatLevel(a);
      float dissolve = this.dissolveProgress(a);
      float shimmer = this.shimmerLevel(a);
      float sinceBlastSec = Math.max(0.0F, (a - this.detonateDelay) / 20.0F);

      Consumer<ShaderInstance> uniforms = shader -> {
         shader.safeGetUniform("ScorchCenter").set(relOrigin.x, relOrigin.y, relOrigin.z, activeRadius);
         shader.safeGetUniform("ScorchState").set(charVis, heat, dissolve, shimmer);
         shader.safeGetUniform("ScorchExtra").set(this.maxRadius, this.seedFloat, this.coreRadius, sinceBlastSec);
      };
      out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.vacuumScorchScene, uniforms));
      if (heat > 0.005F) {
         out.add(new FxPass(FxPass.Kind.VOLUME, () -> FxShaders.vacuumScorchVolume, uniforms));
      }
   }

   // ───────────────────────────── 内部数据结构 ─────────────────────────────

   private static final class SmokePlume {
      final Vec3 base;
      final float height;
      final float radius;
      final float driftX;
      final float driftZ;
      final float phase;
      final int maxAge;
      int age;

      private SmokePlume(Vec3 base, float height, float radius, float driftX, float driftZ, float phase, int maxAge) {
         this.base = base;
         this.height = height;
         this.radius = radius;
         this.driftX = driftX;
         this.driftZ = driftZ;
         this.phase = phase;
         this.maxAge = maxAge;
      }
   }

   private static final class ScorchEmber {
      double x;
      double y;
      double z;
      double px;
      double py;
      double pz;
      double vx;
      double vy;
      double vz;
      int life;
      final int maxLife;
      final float phase;
      final float[] color;

      private ScorchEmber(Vec3 origin, double vx, double vy, double vz, int life, float phase, float[] color) {
         this.x = this.px = origin.x;
         this.y = this.py = origin.y;
         this.z = this.pz = origin.z;
         this.vx = vx;
         this.vy = vy;
         this.vz = vz;
         this.life = life;
         this.maxLife = life;
         this.phase = phase;
         this.color = color;
      }

      void tick() {
         this.px = this.x;
         this.py = this.y;
         this.pz = this.z;
         float drift = (float) Math.sin(this.life * 0.25 + this.phase) * 0.015F;
         this.vx = this.vx * 0.96 + drift;
         this.vz = this.vz * 0.96 - drift * 0.7;
         this.vy = this.vy * 0.975 + 0.003;
         this.x += this.vx;
         this.y += this.vy;
         this.z += this.vz;
         this.life--;
      }

      Vec3 position(float partialTick) {
         return new Vec3(this.px + (this.x - this.px) * partialTick, this.py + (this.y - this.py) * partialTick, this.pz + (this.z - this.pz) * partialTick);
      }
   }
}
