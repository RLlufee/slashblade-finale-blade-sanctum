package QWQ.QingYi.annihilationblade.annihilation_blade.client.fx;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.SpatialFracturePattern;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.core.FxInstance;
import QWQ.QingYi.annihilationblade.render.client.core.FxPass;
import QWQ.QingYi.annihilationblade.render.client.core.FxScreenState;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry.Style;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxLightningBolt;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxShaders;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 湮灭之刃终极 SA「空间破碎·万象裂隙」导演级专属渲染（v3 循序渐进魔导炮 + 绚烂超新星爆轰版）。
 * <p>
 * 整体架构严格遵守「施法者前方双竖直法阵依次展开 → 反向旋转蓄能与临界压缩 → 突发光柱穿透双环射向目标
 * → 命中后展开空间破碎领域与斩切 → 奇点黑洞坍缩 → 终极绚烂超新星空间爆轰与星河余韵」七幕节奏，
 * 以 Body 暗部层稳稳压出色彩对比，杜绝前期拥挤与惨白过曝。
 *
 * @see SpatialFracturePattern 双端共享关键帧时间轴
 */
@OnlyIn(Dist.CLIENT)
public final class SpatialFractureFx extends FxInstance {
   // ───────────────────────────── 时间轴常量（引用双端共享 Pattern） ─────────────────────────────
   private static final int LIFETIME = SpatialFracturePattern.TOTAL_LIFETIME;
   private static final float RING1_START = SpatialFracturePattern.RING1_OPEN_START;
   private static final float RING1_END = SpatialFracturePattern.RING1_OPEN_END;
   private static final float RING2_START = SpatialFracturePattern.RING2_OPEN_START;
   private static final float RING2_END = SpatialFracturePattern.RING2_OPEN_END;
   private static final float CHARGE_START = SpatialFracturePattern.CHARGE_START;
   private static final float COMPRESS_START = SpatialFracturePattern.COMPRESS_START;
   private static final int BEAM_FIRE = SpatialFracturePattern.BEAM_FIRE_TICK;
   private static final int BEAM_HIT = SpatialFracturePattern.BEAM_HIT_TICK;
   private static final float BEAM_HOLD_END = SpatialFracturePattern.BEAM_HOLD_END_TICK;
   private static final float GATE_FADE_START = SpatialFracturePattern.GATE_FADE_START;
   private static final float GATE_FADE_END = SpatialFracturePattern.GATE_FADE_END;
   private static final float FIELD_REVEAL_END = SpatialFracturePattern.FIELD_REVEAL_END;
   private static final int STORM_START = SpatialFracturePattern.STORM_START_TICK;
   private static final int STORM_END = SpatialFracturePattern.STORM_END_TICK;
   private static final int COLLAPSE_START = SpatialFracturePattern.COLLAPSE_START_TICK;
   private static final int DETONATE = SpatialFracturePattern.DETONATE_TICK;
   private static final int SHOCK_DURATION = SpatialFracturePattern.SHOCK_DURATION;

   private static final float MAX_RENDER_RADIUS = 48.0F;
   private static final int MAX_SLICES = 8;
   private static final float MAX_SPIN_BOOST = 0.24F;

   // ───────────────────────────── 色彩体系（暗底衬托四色星辉，绚烂绝不惨白） ─────────────────────────────
   private static final float[] VOID = {0.62F, 0.24F, 1.0F};
   private static final float[] DEEP_ABYSS = {0.09F, 0.02F, 0.22F};
   private static final float[] DEEP_VIOLET = {0.24F, 0.06F, 0.48F};
   private static final float[] CYAN = {0.30F, 0.88F, 1.0F};
   private static final float[] GOLD = {1.0F, 0.78F, 0.34F};
   private static final float[] MAGENTA = {0.96F, 0.34F, 0.88F};
   private static final float[] WHITE = {1.0F, 0.97F, 1.0F};

   // ───────────────────────────── 空间锚点与坐标系 ─────────────────────────────
   private final Vec3 center;
   private final Vec3 impactCenter;
   private final double groundY;
   private final float radius;
   private final float scale;
   private final float seedFloat;
   private final int casterId;
   private final double rayDist;
   private final float distScale;

   /** 施法者前方两重竖直魔导环的中心、半径与正交朝向基（锚定于施法瞬间轴线，防转头穿帮）。 */
   private final Vec3 ring1Center;
   private final Vec3 ring2Center;
   private final float ring1Radius;
   private final float ring2Radius;
   private final Vec3 beamStart;
   private final Vec3 beamEnd;
   private final Vec3 beamDir;
   private final Vector3f axisRight;
   private final Vector3f axisUp;
   private final Vector3f axisForward;

   private final int[] targetIds;
   private final Vec3[] targetPositions;
   private final float[] targetSizes;

   // ───────────────────────────── 预生成与动态演出要素 ─────────────────────────────
   private final List<Slash> slashes = new ArrayList<>();
   private final List<CutScar> cutScars = new ArrayList<>();
   private final List<DomainArc> preArcs = new ArrayList<>();
   private final List<DomainArc> blastArcs = new ArrayList<>();
   private final List<MirrorShard> mirrorShards = new ArrayList<>();
   private final List<CrystalShard> crystalShards = new ArrayList<>();
   private final List<List<Vector3f>> fieldBolts = new ArrayList<>();
   private final List<FxLightningBolt> skyBolts = new ArrayList<>();
   private final List<Spark> sparks = new ArrayList<>();
   private final RandomSource random;

   public SpatialFractureFx(ClientLevel level, FxEventPacket packet) {
      super(LIFETIME);
      this.center = packet.origin();
      this.impactCenter = this.center.add(0.0, 1.0, 0.0);
      this.radius = Math.max(3.5F, Math.min(MAX_RENDER_RADIUS, packet.radius()));
      this.scale = Math.max(0.25F, packet.scale());
      this.seedFloat = (float)((packet.seed() & 0xFFFFL) / 65535.0 * 97.0);
      this.casterId = packet.casterId();
      this.random = RandomSource.create(packet.seed());

      Entity caster = level.getEntity(this.casterId);
      Vec3 rawLook = packet.direction().lengthSqr() > 1.0E-6 ? packet.direction().normalize() : new Vec3(0.0, 0.0, 1.0);
      Vec3 castAnchor = caster != null ? caster.getEyePosition().add(0.0, -0.32, 0.0) : this.center.add(rawLook.scale(-18.0));
      this.groundY = findGround(level, this.center, caster);

      Vec3 ray = this.impactCenter.subtract(castAnchor);
      this.rayDist = ray.length();
      this.beamDir = this.rayDist > 0.35 ? ray.normalize() : rawLook;

      // 构造垂直于炮击轴线的正交局部基（确保竖直法阵严格正对施法方向且上轴朝天）
      this.axisForward = new Vector3f((float)this.beamDir.x, (float)this.beamDir.y, (float)this.beamDir.z).normalize();
      Vector3f worldUp = Math.abs(this.axisForward.y) > 0.92F ? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
      this.axisRight = new Vector3f(this.axisForward).cross(worldUp).normalize();
      this.axisUp = new Vector3f(this.axisRight).cross(this.axisForward).normalize();

      // 施法者前方双竖直法阵自适应间距与尺寸：
      // 常规距离：第一环（后主阵）在前方 4.6 格、半径 9.25；第二环（前聚能环）在前方 9.4 格、半径 6.65。
      // 第一人称下视角分离度 > 2:1，形成极清澈的“外紫大环套内青聚焦环”隧道景深；近距施法自动按比例压缩不穿模。
      float sc = clamp(this.scale, 0.65F, 1.6F);
      this.distScale = clamp((float)(this.rayDist / 14.0), 0.50F, 1.0F);
      double d1;
      double d2;
      if (this.rayDist >= 12.0) {
         d1 = clampD(4.6 * sc, 3.6, 5.8);
         d2 = clampD(9.4 * sc, d1 + 3.2, Math.min(13.5, this.rayDist * 0.56));
      } else {
         d1 = clampD(this.rayDist * 0.28, 1.7, 3.8);
         d2 = clampD(this.rayDist * 0.60, d1 + 1.15, Math.max(d1 + 1.15, this.rayDist - 1.0));
      }
      this.ring1Center = castAnchor.add(this.beamDir.scale(d1));
      this.ring2Center = castAnchor.add(this.beamDir.scale(d2));
      this.ring1Radius = 9.25F * sc * this.distScale;
      this.ring2Radius = 6.65F * sc * this.distScale;

      // 光束从第一环中心略偏后起射，穿透第二环轰抵目标核心（避免贴近眼睛导致第一人称糊白）
      Vec3 start = this.ring1Center.subtract(this.beamDir.scale(0.35 * this.distScale));
      Vec3 end = this.impactCenter;
      if (end.subtract(start).dot(this.beamDir) < 1.5) {
         end = start.add(this.beamDir.scale(Math.max(2.2, this.rayDist)));
      }
      this.beamStart = start;
      this.beamEnd = end;

      // 记录锁定目标初始坐标与尺寸
      this.targetIds = packet.entityIds();
      this.targetPositions = new Vec3[this.targetIds.length];
      this.targetSizes = new float[this.targetIds.length];
      for (int i = 0; i < this.targetIds.length; i++) {
         Entity target = level.getEntity(this.targetIds[i]);
         this.targetPositions[i] = target != null ? target.position() : this.center;
         this.targetSizes[i] = target != null ? Math.max(0.9F, target.getBbWidth() * 1.6F) : 1.2F;
      }

      this.buildSlashes(Math.max(0, packet.intParam()));
      this.buildCutScars();
      this.buildDomainArcs();
      this.buildMirrorShards();
      this.regenerateFieldBolts();
   }

   private static double findGround(ClientLevel level, Vec3 from, Entity caster) {
      BlockHitResult hit = level.clip(new ClipContext(from.add(0.0, 2.0, 0.0), from.subtract(0.0, 28.0, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
      return hit.getType() == HitResult.Type.MISS ? from.y - 1.0 : hit.getLocation().y;
   }

   private void buildSlashes(int count) {
      int total = Math.min(44, Math.max(14, count));
      for (int i = 0; i < total; i++) {
         Vector3f direction = FxMath.randomUnit(this.random);
         Vector3f offset = FxMath.randomUnit(this.random).mul((float)(this.random.nextDouble() * this.radius * 0.56));
         float length = Math.min(this.radius * 1.7F, (float)((9.0 + this.random.nextDouble() * 20.0) * this.scale));
         Vec3 middle = this.center.add(offset.x, Math.abs(offset.y) * 0.75 + (1.0 + this.random.nextDouble() * 3.8) * this.scale, offset.z);
         Vec3 half = new Vec3(direction.x, direction.y, direction.z).scale(length * 0.5);
         Vector3f bulgeAxis = FxMath.perpendicular(direction);
         float twist = (float)(this.random.nextDouble() * FxMath.TAU);
         Vector3f bulge = bulgeAxis.rotateAxis(twist, direction.x, direction.y, direction.z).mul(length * 0.16F);
         float spawn = STORM_START + (STORM_END - STORM_START - 4.0F) * (i + this.random.nextFloat() * 0.5F) / Math.max(1, total);
         float width = (float)((0.15 + this.random.nextDouble() * 0.12) * this.scale);
         this.slashes.add(new Slash(middle.subtract(half), middle.add(half), new Vec3(bulge.x, bulge.y, bulge.z), spawn, width, i % 4 == 0));
      }
   }

   /** 生成域内悬停空间刀痕：前 4 条为贯穿域内的主斜劈（坍缩蓄力后在爆轰瞬间升级为全屏错位齐斩）。 */
   private void buildCutScars() {
      Vec3 core = this.impactCenter.add(0.0, 0.4 * this.scale, 0.0);
      for (int i = 0; i < 16; i++) {
         boolean major = i < 4;
         Vector3f offset = FxMath.randomUnit(this.random).mul(this.radius * (major ? 0.16F : 0.48F * this.random.nextFloat()));
         Vec3 mid = core.add(offset.x, Math.max(-0.4, offset.y * 0.55), offset.z);
         Vector3f dir;
         if (major) {
            // 4 道主斜劈在屏幕视野内错开不同倾角，切屏时形成极富张力的多边裂界拼图
            double angle = (i - 1.5) * 0.62 + (this.random.nextDouble() - 0.5) * 0.18;
            dir = new Vector3f(this.axisRight).mul((float)Math.cos(angle)).add(new Vector3f(this.axisUp).mul((float)Math.sin(angle))).normalize();
         } else {
            dir = FxMath.randomUnit(this.random);
         }
         float halfSpan = this.radius * (major ? 0.96F : 0.40F + 0.34F * this.random.nextFloat());
         Vec3 span = new Vec3(dir.x, dir.y, dir.z).scale(halfSpan);
         float spawn = major ? 54.0F + i * 3.8F : STORM_START + (STORM_END - STORM_START - 2.0F) * ((i - 4) / 12.0F);
         float slipSign = i % 2 == 0 ? 1.0F : -1.0F;
         this.cutScars.add(new CutScar(mid.subtract(span), mid.add(span), spawn, major, slipSign, major ? 1.38F : 0.78F));
      }
   }

   /** 生成域内预斩 3D 立体刀弧（8 道）与终极爆轰「次元之花」巨型 3D 刀弧（10 道）。 */
   private void buildDomainArcs() {
      for (int i = 0; i < 8; i++) {
         Vector3f normal = FxMath.randomUnit(this.random);
         Vector3f u = FxMath.perpendicular(normal).normalize();
         Vector3f v = new Vector3f(normal).cross(u).normalize();
         float outerR = this.radius * (0.45F + this.random.nextFloat() * 0.42F);
         float innerR = outerR * (0.72F + this.random.nextFloat() * 0.12F);
         float startAng = this.random.nextFloat() * FxMath.TAU;
         float span = (float)(Math.PI * (0.85 + this.random.nextDouble() * 0.60));
         float spawn = STORM_START + 2.0F + i * 2.6F;
         float[] tint = i % 3 == 0 ? GOLD : (i % 2 == 0 ? CYAN : VOID);
         this.preArcs.add(new DomainArc(u, v, innerR, outerR, startAng, startAng + span, spawn, 11.0F, tint));
      }
      for (int i = 0; i < 10; i++) {
         // 围绕爆心呈立体星莲花瓣分布的 10 道终极巨型空间刀弧
         double az = i * FxMath.TAU / 10.0 + (this.random.nextDouble() - 0.5) * 0.2;
         double pitch = (i % 2 == 0 ? 0.45 : -0.45) + (this.random.nextDouble() - 0.5) * 0.45;
         Vector3f normal = new Vector3f(
            (float)(Math.cos(az) * Math.cos(pitch)),
            (float)Math.sin(pitch),
            (float)(Math.sin(az) * Math.cos(pitch))
         ).normalize();
         Vector3f u = FxMath.perpendicular(normal).normalize();
         Vector3f v = new Vector3f(normal).cross(u).normalize();
         float outerR = this.radius * (0.78F + this.random.nextFloat() * 0.48F);
         float innerR = outerR * (0.64F + this.random.nextFloat() * 0.14F);
         float startAng = this.random.nextFloat() * FxMath.TAU;
         float span = (float)(Math.PI * (1.05 + this.random.nextDouble() * 0.55));
         float spawn = DETONATE + (i % 5) * 0.7F;
         float[] tint = i % 4 == 0 ? WHITE : (i % 4 == 1 ? GOLD : (i % 4 == 2 ? CYAN : MAGENTA));
         this.blastArcs.add(new DomainArc(u, v, innerR, outerR, startAng, startAng + span, spawn, 18.0F, tint));
      }
   }

   /** 生成 32 片碎裂空间镜像片（域内悬停震颤，爆轰时高速炸散并沿烧蚀前沿溶解）。 */
   private void buildMirrorShards() {
      Vec3 core = this.impactCenter;
      for (int i = 0; i < 32; i++) {
         double theta = this.random.nextDouble() * FxMath.TAU;
         double elev = (this.random.nextDouble() - 0.25) * 1.45;
         float dist = this.radius * (0.20F + 0.68F * (float)Math.sqrt(this.random.nextDouble()));
         Vec3 pos = core.add(Math.cos(theta) * dist, elev * Math.min(this.radius * 0.48F, 5.2F * this.scale), Math.sin(theta) * dist);
         Vector3f outward = new Vector3f((float)(pos.x - core.x), (float)(pos.y - core.y + 0.8), (float)(pos.z - core.z));
         if (outward.lengthSquared() < 1.0E-4F) {
            outward.set(0.0F, 1.0F, 0.0F);
         } else {
            outward.normalize();
         }
         Vector3f u = FxMath.randomUnit(this.random);
         Vector3f v = FxMath.perpendicular(u).rotateAxis((float)(this.random.nextDouble() * 1.8 - 0.9), u.x, u.y, u.z).normalize();
         float spawn = STORM_START + 2.0F + 20.0F * (i / 32.0F);
         float size = (0.44F + 0.65F * this.random.nextFloat()) * this.scale;
         float speed = (0.45F + 0.85F * this.random.nextFloat()) * this.scale;
         float spin = (this.random.nextFloat() - 0.5F) * 0.36F;
         float[] color = i % 4 == 0 ? GOLD : (i % 4 == 1 ? CYAN : (i % 4 == 2 ? VOID : MAGENTA));
         this.mirrorShards.add(new MirrorShard(pos, outward, u, v, spawn, size, speed, spin, color));
      }
   }

   private void regenerateFieldBolts() {
      this.fieldBolts.clear();
      for (int i = 0; i < 7; i++) {
         Vector3f end = FxMath.randomUnit(this.random).mul(this.radius * (0.52F + this.random.nextFloat() * 0.48F));
         end.y = Math.abs(end.y) * 0.65F + 0.5F;
         this.fieldBolts.add(lightning(new Vector3f(0.0F, 1.0F, 0.0F), end, 4, this.random));
      }
   }

   private static List<Vector3f> lightning(Vector3f from, Vector3f to, int depth, RandomSource random) {
      List<Vector3f> points = new ArrayList<>();
      points.add(new Vector3f(from));
      points.add(new Vector3f(to));
      float amplitude = from.distance(to) * 0.16F;
      for (int level = 0; level < depth; level++) {
         List<Vector3f> next = new ArrayList<>(points.size() * 2);
         for (int i = 0; i < points.size() - 1; i++) {
            Vector3f a = points.get(i);
            Vector3f b = points.get(i + 1);
            Vector3f mid = new Vector3f(a).add(b).mul(0.5F);
            Vector3f jitter = FxMath.randomUnit(random).mul(amplitude * (0.5F + random.nextFloat() * 0.5F));
            next.add(a);
            next.add(mid.add(jitter));
         }
         next.add(points.get(points.size() - 1));
         points = next;
         amplitude *= 0.5F;
      }
      return points;
   }

   @Override
   public Vec3 focus() {
      return this.center;
   }

   @Override
   public double cullDistance() {
      return Math.max(220.0, this.rayDist + this.radius * 3.5 + 96.0);
   }

   @Override
   public boolean hasBody() {
      return true;
   }

   // ───────────────────────────── 逻辑 tick（精确音频叙事 + 粒子物理模拟） ─────────────────────────────

   @Override
   public void tick(ClientLevel level) {
      int age = this.ageTicks();

      // ── 第一幕：第一环展开声 ──
      if (age == 0) {
         play(level, this.ring1Center, SoundEvents.AMETHYST_BLOCK_CHIME, 1.8F, 0.68F);
         play(level, this.ring1Center, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.2F, 0.85F);
      }

      // ── 第二幕：第二环依次展开声（更高清脆音阶） ──
      if (age == (int)RING2_START) {
         play(level, this.ring2Center, SoundEvents.AMETHYST_BLOCK_CHIME, 2.0F, 1.05F);
         play(level, this.ring2Center, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.8F, 1.25F);
         play(level, this.ring2Center, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.3F, 1.18F);
      }

      // ── 第三幕：双环加速对旋与临界收束 ──
      if (age == 22) {
         play(level, this.ring2Center, SoundEvents.BEACON_POWER_SELECT, 1.8F, 0.95F);
      }
      if (age == (int)COMPRESS_START) {
         play(level, this.ring2Center, SoundEvents.CROSSBOW_LOADING_END, 1.6F, 1.45F);
         play(level, this.ring2Center, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.8F, 1.85F);
      }

      // 蓄能期间：从双环外缘向炮口轴心吸入细微光丝火花
      if (age >= CHARGE_START && age < BEAM_FIRE) {
         for (int i = 0; i < 3; i++) {
            double ang = this.random.nextDouble() * FxMath.TAU;
            float r = this.ring1Radius * (0.9F + this.random.nextFloat() * 0.45F);
            Vec3 from = this.ring1Center
               .add(this.axisRight.x * Math.cos(ang) * r, this.axisRight.y * Math.cos(ang) * r, this.axisRight.z * Math.cos(ang) * r)
               .add(this.axisUp.x * Math.sin(ang) * r, this.axisUp.y * Math.sin(ang) * r, this.axisUp.z * Math.sin(ang) * r);
            Vec3 vel = this.ring2Center.subtract(from).normalize().scale(0.55 + this.random.nextDouble() * 0.35);
            this.sparks.add(new Spark(from, new Vector3f((float)vel.x, (float)vel.y, (float)vel.z), 8 + this.random.nextInt(5), i % 2 == 0 ? CYAN : VOID, 0.0F, 0.96));
         }
      }

      // ── 第四幕：突发光束爆射 ──
      if (age == BEAM_FIRE) {
         play(level, this.ring2Center, SoundEvents.WARDEN_SONIC_BOOM, 2.2F, 0.72F);
         play(level, this.ring2Center, SoundEvents.TRIDENT_THUNDER, 2.0F, 1.55F);
         play(level, this.ring2Center, SoundEvents.BEACON_DEACTIVATE, 2.0F, 1.35F);
         for (int i = 0; i < 32; i++) {
            double ang = this.random.nextDouble() * FxMath.TAU;
            float spread = 0.25F + this.random.nextFloat() * 0.75F;
            Vector3f dir = new Vector3f(this.axisForward).mul(0.85F + this.random.nextFloat() * 1.1F)
               .add(new Vector3f(this.axisRight).mul((float)Math.cos(ang) * spread))
               .add(new Vector3f(this.axisUp).mul((float)Math.sin(ang) * spread));
            this.sparks.add(new Spark(this.ring2Center, dir, 12 + this.random.nextInt(10), i % 3 == 0 ? GOLD : (i % 2 == 0 ? CYAN : WHITE), -0.004F, 0.90));
         }
      }

      // ── 第五幕：光束命中目标核心，展开空间破碎场 ──
      if (age == BEAM_HIT) {
         play(level, this.impactCenter, SoundEvents.END_PORTAL_SPAWN, 3.0F, 1.25F);
         play(level, this.impactCenter, SoundEvents.GENERIC_EXPLODE, 2.4F, 0.78F);
         play(level, this.impactCenter, SoundEvents.GLASS_BREAK, 2.0F, 0.85F);
         int hitCount = Math.min(64, (int)(44 * this.scale));
         for (int i = 0; i < hitCount; i++) {
            float[] tint = i % 3 == 0 ? CYAN : (i % 3 == 1 ? VOID : WHITE);
            this.sparks.add(Spark.burst(this.impactCenter, this.random, (0.55F + this.random.nextFloat() * 1.1F) * this.scale, 14 + this.random.nextInt(12), tint, -0.01F));
         }
      }

      if (age == 48 || age == 56 || age == 64) {
         play(level, this.impactCenter, SoundEvents.GLASS_BREAK, 1.15F, 1.18F + (age - 48) * 0.025F);
      }

      // ── 第六幕：奇点黑洞坍缩低鸣与临界紧绷 ──
      if (age == COLLAPSE_START) {
         play(level, this.impactCenter, SoundEvents.RESPAWN_ANCHOR_CHARGE, 2.6F, 0.45F);
         play(level, this.impactCenter, SoundEvents.BEACON_ACTIVATE, 2.4F, 0.42F);
      }
      if (age == DETONATE - 4) {
         play(level, this.impactCenter, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.6F, 1.95F);
      }

      if (age >= BEAM_HIT && age < DETONATE && age % 2 == 0) {
         this.regenerateFieldBolts();
      }

      for (Slash slash : this.slashes) {
         if (!slash.sparked && age >= slash.spawn) {
            slash.sparked = true;
            Vec3 mid = slash.point(0.5);
            for (int i = 0; i < 4; i++) {
               this.sparks.add(Spark.burst(mid, this.random, 0.28F * this.scale, 10 + this.random.nextInt(8), slash.golden ? GOLD : CYAN, -0.008F));
            }
         }
      }

      // ── 第七幕：终极绚烂超新星空间爆轰 ──
      if (age == DETONATE) {
         this.detonate(level);
      }

      // 更新动态天雷、3D 星晶与银河余烬
      Iterator<FxLightningBolt> boltIt = this.skyBolts.iterator();
      while (boltIt.hasNext()) {
         FxLightningBolt bolt = boltIt.next();
         bolt.tick(this.random);
         if (bolt.dead()) {
            boltIt.remove();
         }
      }

      Iterator<CrystalShard> crystalIt = this.crystalShards.iterator();
      while (crystalIt.hasNext()) {
         CrystalShard shard = crystalIt.next();
         shard.tick();
         if (shard.life <= 0) {
            crystalIt.remove();
         }
      }

      Iterator<Spark> sparkIt = this.sparks.iterator();
      while (sparkIt.hasNext()) {
         Spark spark = sparkIt.next();
         spark.tick();
         if (spark.life <= 0) {
            sparkIt.remove();
         }
      }
   }

   private void detonate(ClientLevel level) {
      float volume = Math.min(5.0F, 2.2F + this.targetIds.length / 18.0F);
      play(level, this.impactCenter, SoundEvents.WARDEN_SONIC_BOOM, volume, 0.36F);
      play(level, this.impactCenter, SoundEvents.GENERIC_EXPLODE, volume, 0.56F);
      play(level, this.impactCenter, SoundEvents.LIGHTNING_BOLT_THUNDER, Math.min(6.0F, volume * 1.4F), 0.72F);
      play(level, this.impactCenter, SoundEvents.GLASS_BREAK, 3.0F, 0.42F);
      play(level, this.impactCenter, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 3.2F, 0.48F);
      level.setSkyFlashTime(4);

      // 1. 爆区双环 14 道分形虚空天雷（贴合真实地表高度）
      int boltCount = 14;
      for (int i = 0; i < boltCount; i++) {
         boolean inner = i < 6;
         double ang = (inner ? i * FxMath.TAU / 6.0 : (i - 6) * FxMath.TAU / 8.0 + 0.35) + (this.random.nextDouble() - 0.5) * 0.22;
         double dist = this.radius * (inner ? (0.34 + this.random.nextDouble() * 0.22) : (0.72 + this.random.nextDouble() * 0.38));
         double bx = this.center.x + Math.cos(ang) * dist;
         double bz = this.center.z + Math.sin(ang) * dist;
         double ground = this.groundY;
         int ix = (int)Math.floor(bx);
         int iz = (int)Math.floor(bz);
         if (level.hasChunk(ix >> 4, iz >> 4)) {
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz);
            if (h > level.getMinBuildHeight()) {
               ground = h;
            }
         }
         double top = Math.min(level.getMaxBuildHeight(), ground + 42.0 + this.random.nextDouble() * 26.0);
         float[] tint = i % 3 == 0 ? GOLD : (i % 3 == 1 ? CYAN : VOID);
         this.skyBolts.add(new FxLightningBolt(bx, bz, top, ground, this.random.nextLong(), 1 + this.random.nextInt(3), tint));
      }

      // 2. 72 枚真 3D 立体双锥棱镜星晶（高速爆射 → 强阻尼进入慢动作浮空旋转飘升）
      int crystalCount = 72;
      for (int i = 0; i < crystalCount; i++) {
         Vector3f dir = FxMath.randomUnit(this.random);
         dir.y = Math.abs(dir.y) * 0.78F + 0.14F;
         dir.normalize();
         float speed = (0.75F + this.random.nextFloat() * 2.15F) * Math.min(1.6F, this.scale);
         float size = (0.36F + this.random.nextFloat() * 0.62F) * Math.min(1.5F, this.scale);
         float[] color = switch (i % 5) {
            case 0 -> GOLD;
            case 1 -> CYAN;
            case 2 -> VOID;
            case 3 -> MAGENTA;
            default -> WHITE;
         };
         Vec3 startPos = this.impactCenter.add(dir.x * 1.4, dir.y * 1.4, dir.z * 1.4);
         this.crystalShards.add(new CrystalShard(
            startPos,
            dir.x * speed, dir.y * speed, dir.z * speed,
            34 + this.random.nextInt(20),
            size,
            color,
            this.random.nextFloat() * FxMath.TAU,
            (this.random.nextFloat() - 0.5F) * 0.28F
         ));
      }

      // 3. 200 条多彩银河星尘余烬（120 条超新星高速辐射射线 + 80 条螺旋慢漂星尘）
      int burstCount = Math.min(200, Math.max(120, (int)(180 * Math.min(1.4F, this.scale))));
      for (int i = 0; i < burstCount; i++) {
         float[] color = switch (i % 5) {
            case 0 -> GOLD;
            case 1 -> CYAN;
            case 2 -> VOID;
            case 3 -> MAGENTA;
            default -> WHITE;
         };
         boolean slowNebula = i >= burstCount * 3 / 5;
         if (slowNebula) {
            Vector3f dir = FxMath.randomUnit(this.random);
            dir.y = Math.abs(dir.y) * 0.65F + 0.15F;
            dir.normalize().mul((0.55F + this.random.nextFloat() * 1.15F) * Math.min(1.4F, this.scale));
            this.sparks.add(new Spark(this.impactCenter, dir, 34 + this.random.nextInt(20), color, 0.012F, 0.90));
         } else {
            this.sparks.add(Spark.burst(this.impactCenter, this.random, (1.1F + this.random.nextFloat() * 2.4F) * Math.min(1.5F, this.scale), 22 + this.random.nextInt(22), color, -0.012F));
         }
      }
   }

   private static void play(ClientLevel level, Vec3 at, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
      level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int age = this.ageTicks();
      if (age == 0) {
         verticalRing(level, ParticleTypes.REVERSE_PORTAL, this.ring1Center, this.ring1Radius, 36);
      }
      if (age == (int)RING2_START) {
         verticalRing(level, ParticleTypes.END_ROD, this.ring2Center, this.ring2Radius, 28);
      }
      if (age == BEAM_FIRE) {
         for (int i = 0; i <= 24; i++) {
            Vec3 p = this.beamStart.lerp(this.beamEnd, i / 24.0);
            level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0.0, 0.0, 0.0);
         }
      }
      if (age == BEAM_HIT) {
         level.addParticle(ParticleTypes.FLASH, this.impactCenter.x, this.impactCenter.y, this.impactCenter.z, 0.0, 0.0, 0.0);
         horizontalRing(level, ParticleTypes.END_ROD, this.center.add(0.0, 0.3, 0.0), this.radius, 48);
      }
      for (Slash slash : this.slashes) {
         if (age == (int)slash.spawn) {
            for (int i = 0; i <= 6; i++) {
               Vec3 p = slash.point(i / 6.0);
               level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0.0, 0.0, 0.0);
            }
         }
      }
      if (age == DETONATE) {
         level.addParticle(ParticleTypes.SONIC_BOOM, this.impactCenter.x, this.impactCenter.y, this.impactCenter.z, 0.0, 0.0, 0.0);
         level.addParticle(ParticleTypes.EXPLOSION_EMITTER, this.impactCenter.x, this.impactCenter.y, this.impactCenter.z, 0.0, 0.0, 0.0);
         horizontalRing(level, ParticleTypes.DRAGON_BREATH, this.impactCenter, this.radius * 0.65F, 64);
      }
   }

   private void verticalRing(ClientLevel level, ParticleOptions particle, Vec3 c, float r, int points) {
      for (int i = 0; i < points; i++) {
         double ang = FxMath.TAU * i / points;
         double cs = Math.cos(ang) * r;
         double sn = Math.sin(ang) * r;
         level.addParticle(
            particle,
            c.x + this.axisRight.x * cs + this.axisUp.x * sn,
            c.y + this.axisRight.y * cs + this.axisUp.y * sn,
            c.z + this.axisRight.z * cs + this.axisUp.z * sn,
            0.0, 0.0, 0.0
         );
      }
   }

   private static void horizontalRing(ClientLevel level, ParticleOptions particle, Vec3 center, float radius, int points) {
      for (int i = 0; i < points; i++) {
         double angle = FxMath.TAU * i / points;
         level.addParticle(particle, center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius, 0.0, 0.0, 0.0);
      }
   }

   // ───────────────────────────── 时间轴曲线辅助 ─────────────────────────────

   /** 严格单调递增的平滑加速旋转积分（蓄能加速、发炮后平滑惯性滑行，永不倒转跳变）。 */
   private static float gateSpinAngle(float a) {
      float base = a * 0.032F;
      if (a <= CHARGE_START) {
         return base;
      }
      float accelDur = BEAM_FIRE - CHARGE_START;
      if (a <= BEAM_FIRE) {
         float u = (a - CHARGE_START) / accelDur;
         return base + 0.5F * MAX_SPIN_BOOST * accelDur * u * u;
      }
      float dt = a - BEAM_FIRE;
      float decelWindow = 20.0F;
      float clamped = Math.min(dt, decelWindow);
      float post = MAX_SPIN_BOOST * (dt - 0.35F * clamped * clamped / decelWindow);
      return base + 0.5F * MAX_SPIN_BOOST * accelDur + post;
   }

   private float fieldReveal(float a) {
      return a < BEAM_HIT ? 0.0F : FxMath.easeOutCubic(FxMath.window(a, BEAM_HIT, FIELD_REVEAL_END));
   }

   private float collapse(float a) {
      return FxMath.easeInCubic(FxMath.window(a, COLLAPSE_START, DETONATE));
   }

   private float fieldFade(float a) {
      if (a < BEAM_HIT) {
         return 0.0F;
      }
      return 1.0F - FxMath.window(a, DETONATE + 3.0F, DETONATE + 18.0F);
   }

   private float shockProgress(float a) {
      return a < DETONATE ? -1.0F : FxMath.window(a, DETONATE, DETONATE + SHOCK_DURATION);
   }

   private float coreRadius(float a) {
      if (a < COLLAPSE_START) {
         return 0.0F;
      }
      if (a < DETONATE) {
         return this.radius * 0.155F * this.collapse(a);
      }
      return this.radius * 0.155F * (1.0F - FxMath.window(a, DETONATE, DETONATE + 2.5F));
   }

   private float shatterCutIntensity(float a) {
      if (a < BEAM_HIT) {
         return 0.0F;
      }
      float stormPhase = FxMath.window(a, BEAM_HIT + 2.0F, BEAM_HIT + 10.0F) * (1.0F - FxMath.window(a, DETONATE + 6.0F, DETONATE + 18.0F));
      float peak = a >= COLLAPSE_START && a < DETONATE + 8.0F
         ? FxMath.window(a, COLLAPSE_START, DETONATE - 4.0F) * (1.0F - FxMath.window(a, DETONATE + 2.0F, DETONATE + 8.0F))
         : 0.0F;
      return Math.min(1.5F, stormPhase * 0.82F + peak * 0.68F);
   }

   private int markStart(int index) {
      return BEAM_HIT + 3 + (int)(Math.min(index, 24) * 0.85F);
   }

   private Vec3 targetPos(ClientLevel level, int index, float partialTick) {
      Entity entity = level.getEntity(this.targetIds[index]);
      if (entity != null && entity.isAlive()) {
         this.targetPositions[index] = entity.getPosition(partialTick);
      }
      return this.targetPositions[index];
   }

   private static float nearFade(Vector3f rel) {
      return FxMath.window(rel.length(), 0.7F, 2.1F);
   }

   // ───────────────────────────── Body 暗部实体层（高反差电影级质感之魂） ─────────────────────────────

   @Override
   public void renderBody(FxGeometry b, FxFrame frame) {
      float a = this.age(frame.partialTick);

      // 1. 施法者前方双竖直法阵暗紫底膜（白天晴空下也能清晰衬出每一条金青阵纹）
      this.renderVerticalGatesBody(b, frame, a);

      // 2. 突发光柱深紫暗烟外鞘（赋予巨型光柱厚重体积感，避免惨白纸片感）
      if (a >= BEAM_FIRE && a < BEAM_HOLD_END) {
         float reach = FxMath.easeOutCubic(FxMath.window(a, BEAM_FIRE, BEAM_HIT));
         float fadeOut = 1.0F - FxMath.easeInCubic(FxMath.window(a, BEAM_HIT + 4.0F, BEAM_HOLD_END));
         if (reach > 0.02F && fadeOut > 0.01F) {
            Vec3 toW = this.beamStart.lerp(this.beamEnd, reach);
            Vector3f from = frame.rel(this.beamStart);
            Vector3f to = frame.rel(toW);
            float rBase = Math.min(1.55F, this.ring2Radius * 0.46F) * (0.45F + 0.55F * fadeOut);
            b.begin(Style.PLASMA_BEAM, 0.85F, 0.45F, 1.0F, -5.0F, 1.0F - fadeOut, reach < 0.99F ? reach : (a * 0.22F) % 1.0F);
            b.cylinder(from, to, rBase * 0.85F, rBase * 1.25F, 18, a * 0.07F, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], fadeOut * 0.86F);
            b.end();
         }
      }

      // 3. 命中后：域内 3D 暗黑空间切口刀弧 & 悬停刀痕暗腔 & 碎裂镜片暗核
      if (a >= STORM_START) {
         Vector3f impactRel = frame.rel(this.impactCenter);
         this.renderDomainArcs(b, impactRel, this.preArcs, a, true);

         float scarFade = 1.0F - FxMath.window(a, DETONATE + 4.0F, DETONATE + 12.0F);
         if (scarFade > 0.01F) {
            for (CutScar scar : this.cutScars) {
               float t = a - scar.spawn;
               if (t < 0.0F) {
                  continue;
               }
               float open = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 2.0F)) * (scar.major && a >= COLLAPSE_START + 4.0F ? 1.50F : 1.0F);
               b.begin(Style.VOID_SLIT, 1.0F, 0.0F, open * 0.60F, 0.65F, 1.0F, 0.0F);
               this.drawCutQuad(b, frame, scar, 0.46F * scar.weight * this.scale, DEEP_VIOLET, scarFade * 0.94F);
               b.end();
            }
         }
         this.renderMirrorShardLayer(b, frame, a, true);
      }

      // 4. 终极绚烂爆炸暗部层：深渊膨胀星云内壳 + 次元之花 10 道巨型暗黑刀弧 + 通天暗紫烟柱 + 爆心余缝暗腔
      if (a >= DETONATE) {
         this.renderDetonationBody(b, frame, a);
      }
   }

   private void renderVerticalGatesBody(FxGeometry b, FxFrame frame, float a) {
      float gateFade = 1.0F - FxMath.easeInCubic(FxMath.window(a, GATE_FADE_START, GATE_FADE_END));
      if (gateFade <= 0.005F) {
         return;
      }
      float spin = gateSpinAngle(a);
      float compress = FxMath.easeInCubic(FxMath.window(a, COMPRESS_START, BEAM_FIRE));
      float recoil = a >= BEAM_FIRE && a < BEAM_FIRE + 9.0F ? (float)Math.sin(Math.PI * FxMath.window(a, BEAM_FIRE, BEAM_FIRE + 9.0F)) * 0.14F : 0.0F;

      // 第一环暗紫流场衬底环带（中心通透不遮挡视野，仅在符文环带背后压暗以提升白天晴空对比度）
      float open1 = FxMath.easeOutCubic(FxMath.window(a, RING1_START, RING1_END));
      if (open1 > 0.01F) {
         Vector3f c1 = frame.rel(this.ring1Center);
         float r1 = this.ring1Radius * (0.20F + 0.80F * open1) * (1.0F - 0.10F * compress + recoil) * (0.50F + 0.50F * gateFade);
         float alpha = open1 * gateFade * 0.58F * nearFade(c1);
         if (alpha > 0.005F) {
            b.begin(Style.SLASH_ARC, 0.55F, 0.25F, 5.2F, 0.85F, open1, 0.0F);
            b.slashArc(c1, this.axisRight, this.axisUp, r1 * 0.62F, r1 * 1.05F, spin, spin + (float)Math.PI * 1.12F, 18, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha);
            b.slashArc(c1, this.axisRight, this.axisUp, r1 * 0.62F, r1 * 1.05F, spin + (float)Math.PI, spin + FxMath.TAU + 0.36F, 18, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha);
            b.begin(Style.PLASMA_BEAM, 0.65F, 0.2F, 1.0F, 1.8F, 0.0F, 0.5F);
            b.torus(c1, this.axisForward, r1 * 1.02F, Math.max(0.06F, r1 * 0.032F), 32, 5, spin * 0.8F, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha * 0.85F);
         }
      }

      // 第二环暗紫炮膛环框衬底 + 蓄能奇点纯黑微核
      float open2 = FxMath.easeOutCubic(FxMath.window(a, RING2_START, RING2_END));
      if (open2 > 0.01F) {
         Vector3f c2 = frame.rel(this.ring2Center);
         float r2 = this.ring2Radius * (0.20F + 0.80F * open2) * (1.0F - 0.14F * compress + recoil * 1.25F) * (0.50F + 0.50F * gateFade);
         float alpha = open2 * gateFade * 0.62F * nearFade(c2);
         if (alpha > 0.005F) {
            float rot2 = -spin * 1.45F;
            b.begin(Style.SLASH_ARC, 0.55F, 0.25F, 5.2F, 0.85F, open2, 0.0F);
            b.slashArc(c2, this.axisRight, this.axisUp, r2 * 0.58F, r2 * 1.06F, rot2, rot2 + (float)Math.PI * 1.12F, 16, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha);
            b.slashArc(c2, this.axisRight, this.axisUp, r2 * 0.58F, r2 * 1.06F, rot2 + (float)Math.PI, rot2 + FxMath.TAU + 0.36F, 16, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha);
            b.begin(Style.PLASMA_BEAM, 0.68F, 0.2F, 1.0F, -2.2F, 0.0F, 0.5F);
            b.torus(c2, this.axisForward, r2 * 1.04F, Math.max(0.065F, r2 * 0.038F), 28, 5, -spin * 1.2F, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha * 0.90F);
         }
         // 临界压缩时的炮口暗核（让白青压缩核心显得无比深邃锋利）
         if (a >= CHARGE_START + 2.0F && a < BEAM_FIRE + 2.0F) {
            float charge = FxMath.window(a, CHARGE_START + 2.0F, BEAM_FIRE);
            float coreR = this.ring2Radius * (0.16F + 0.16F * charge) * (1.0F - 0.35F * compress);
            b.geoCenter(c2);
            b.begin(Style.CORONA, 0.45F, 0.2F, 2.0F, 1.2F, 1.2F, 0.6F);
            b.deformedSphere(c2, coreR, 12, 16, a * 0.12F, 0.06F, 1.5F, a * 0.4F, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], charge * 0.90F * nearFade(c2));
            b.clearGeoCenter();
         }
      }
      b.end();
   }

   private void renderDetonationBody(FxGeometry b, FxFrame frame, float a) {
      float post = a - DETONATE;
      Vector3f coreRel = frame.rel(this.impactCenter);

      // 1. 爆炸中心深渊暗紫膨胀球壳（先压出极具宇宙深邃感的暗星内核，再衬托表层绚烂星云火海）
      if (post < 28.0F) {
         float expand = FxMath.easeOutCubic(FxMath.window(post, 0.0F, 16.0F));
         float fadeOut = 1.0F - FxMath.easeInCubic(FxMath.window(post, 12.0F, 28.0F));
         float sphereR = this.radius * (0.12F + 0.56F * expand);
         if (fadeOut > 0.01F) {
            b.geoCenter(coreRel);
            b.begin(Style.CORONA, 0.48F, 0.6F, 2.0F, 1.4F, 1.1F, 0.85F);
            b.deformedSphere(coreRel, sphereR, 18, 26, a * 0.06F, 0.065F * (1.0F - post / 36.0F), 1.4F, a * 0.32F, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], fadeOut * 0.88F);
            b.clearGeoCenter();
         }
      }

      // 2. 次元之花：10 道巨型 3D 暗黑空间切口刀弧
      this.renderDomainArcs(b, coreRel, this.blastArcs, a, true);

      // 3. 通天 3D 湮灭光柱深紫暗烟外鞘（修长威严不挡死视野）
      if (post < 26.0F) {
         float pillarIn = FxMath.easeOutCubic(FxMath.window(post, 0.0F, 2.5F));
         float pillarOut = 1.0F - FxMath.window(post, 10.0F, 26.0F);
         float pillarAlpha = pillarIn * pillarOut;
         if (pillarAlpha > 0.01F) {
            double ceiling = Math.min(frame.level.getMaxBuildHeight(), this.groundY + Math.max(58.0, this.radius * 3.2));
            Vector3f base = frame.rel(new Vec3(this.center.x, this.groundY, this.center.z));
            Vector3f top = frame.rel(new Vec3(this.center.x, ceiling, this.center.z));
            float pillarR = Math.min(6.4F, this.radius * 0.25F) * (0.65F + 0.35F * pillarAlpha);
            float collapseProg = FxMath.window(post, 10.0F, 26.0F);
            b.begin(Style.PLASMA_BEAM, 0.85F, 0.85F, 1.5F, -3.4F, collapseProg, FxMath.window(post, 0.0F, 7.0F));
            b.cylinder(base, top, pillarR * 1.20F, pillarR * 0.72F, 20, -a * 0.09F, DEEP_VIOLET[0], DEEP_VIOLET[1], DEEP_VIOLET[2], pillarAlpha * 0.76F);
            b.end();
         }
      }

      // 4. 爆心残留竖直虚空裂隙暗腔（余韵缓缓闭合）
      if (post >= 10.0F && post < 52.0F) {
         float slitOpen = FxMath.easeOutCubic(FxMath.window(post, 10.0F, 16.0F)) * (1.0F - FxMath.easeInCubic(FxMath.window(post, 30.0F, 52.0F)));
         if (slitOpen > 0.01F) {
            b.begin(Style.VOID_SLIT, 1.0F, 0.0F, slitOpen * 0.65F, 0.7F, 0.0F, 0.0F);
            this.drawVerticalSlit(b, frame, this.impactCenter.add(0.0, 2.2 * this.scale, 0.0), 1.6F * this.scale, 5.4F * this.scale, DEEP_ABYSS, slitOpen * 0.92F);
            b.end();
         }
      }
   }

   // ───────────────────────────── HDR 主几何发光层（七幕循序渐进演出） ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      float a = this.age(frame.partialTick);
      float collapse = this.collapse(a);
      float fieldFade = this.fieldFade(a);
      float shrink = 1.0F - 0.68F * collapse;
      Vector3f centerRel = frame.rel(this.center);
      Vector3f impactRel = frame.rel(this.impactCenter);

      // 第一～三幕：施法者前方双竖直法阵依次展开 + 反向旋转蓄能 + 轴向聚能丝 + 临界奇点
      this.renderVerticalMagicGates(g, frame, a);

      // 第四幕：突发光束贯通（穿过第一、第二环轰抵目标，含炮口反冲激波锥、加速立体环与双螺旋）
      this.renderBeam(g, frame, a);

      // 预兆期目标点克制地面定位细环（14~39 tick，不抢炮口戏份）
      if (a >= 14.0F && a < BEAM_HIT + 4.0F) {
         this.renderTargetLockPreview(g, frame, a);
      }

      // 第五～六幕：光束命中后才循序展开目标区域全部破碎与坍缩奇观
      if (a >= BEAM_HIT) {
         this.renderGroundFormation(g, frame, centerRel, impactRel, a, collapse, fieldFade, shrink);
         this.renderSingularityCore(g, frame, impactRel, a, collapse);
         this.renderSlashesAndCuts(g, frame, impactRel, a, fieldFade);
         this.renderMirrorShardLayer(g, frame, a, false);
         this.renderFieldLightningAndMarks(g, frame, centerRel, a, shrink);
      }

      // 第七幕：终极绚烂超新星空间大爆炸（多壳层星云爆球 + 破碎晶穹 + 通天光柱 + 双气浪锥 + 四重彩环 + 次元之花 + 14道天雷 + 72枚慢动作3D星晶）
      if (a >= DETONATE) {
         this.renderSupernovaDetonation(g, frame, impactRel, a);
      }

      // 全程动态流光火花 & 银河星尘余烬
      this.renderSparks(g, frame);
   }

   // ═════════════════════════════ 施法者前方双竖直魔导环 ═════════════════════════════

   private void renderVerticalMagicGates(FxGeometry g, FxFrame frame, float a) {
      float gateFade = 1.0F - FxMath.easeInCubic(FxMath.window(a, GATE_FADE_START, GATE_FADE_END));
      if (gateFade <= 0.005F) {
         return;
      }
      float spin = gateSpinAngle(a);
      float charge = FxMath.window(a, CHARGE_START, BEAM_FIRE);
      float compress = FxMath.easeInCubic(FxMath.window(a, COMPRESS_START, BEAM_FIRE));
      float recoil = a >= BEAM_FIRE && a < BEAM_FIRE + 9.0F ? (float)Math.sin(Math.PI * FxMath.window(a, BEAM_FIRE, BEAM_FIRE + 9.0F)) * 0.14F : 0.0F;
      float firePulse = a >= BEAM_FIRE && a < BEAM_FIRE + 6.0F ? 1.0F - (a - BEAM_FIRE) / 6.0F : 0.0F;

      Vector3f c1 = frame.rel(this.ring1Center);
      float near1 = nearFade(c1);

      // ── 第一环（后方大主阵 · 虚空紫星盘 + 6 顶点六芒星卫星阵，0 ~ 12 tick 率先优雅展开） ──
      float open1 = FxMath.easeOutCubic(FxMath.window(a, RING1_START, RING1_END));
      float alpha1 = open1 * gateFade * near1;
      if (alpha1 > 0.005F) {
         float r1 = this.ring1Radius * (0.20F + 0.80F * open1) * (1.0F - 0.10F * compress + recoil) * (0.48F + 0.52F * gateFade);
         float emBoost = 1.0F + 0.65F * charge + 0.75F * firePulse;

         // 1.1 主法阵盘面（Rune variant=1 逐弧扫绘展开）
         Vector3f u1 = rotatedBasis(this.axisRight, this.axisUp, spin, r1);
         Vector3f v1 = rotatedBasis(this.axisRight, this.axisUp, spin + 1.5707964F, r1);
         g.begin(Style.RUNE, 2.8F * emBoost, 0.25F, spin * 0.55F, this.seedFloat + 7.0F, 1.0F, open1);
         g.quad(c1, u1, v1, VOID[0], VOID[1], VOID[2], alpha1 * 0.95F);

         // 1.2 外层 3D 立体金属感护环（侧面看有真实厚度）+ 内虚线金刻度环
         g.begin(Style.RIBBON, 3.2F * emBoost, 0.2F, 6.0F, 8.0F, 5.0F, 0.45F);
         g.torus(c1, this.axisForward, r1 * 1.02F, Math.max(0.04F, r1 * 0.022F), 36, 5, spin * 0.8F, CYAN[0], CYAN[1], CYAN[2], alpha1 * 0.88F);
         g.begin(Style.RING, 3.0F * emBoost, 0.2F, 0.88F, 0.014F, 48.0F, -0.55F - charge * 1.4F);
         g.quad(c1, new Vector3f(this.axisRight).mul(r1 * 1.03F), new Vector3f(this.axisUp).mul(r1 * 1.03F), GOLD[0], GOLD[1], GOLD[2], alpha1 * 0.90F);
         g.end();

         // 1.3 正反旋转双六边形交织 12 角星骨架
         float polyW = Math.max(0.042F, r1 * 0.018F);
         this.drawVerticalPolygon(g, c1, r1 * 0.89F, 6, spin * 1.05F, polyW, CYAN, alpha1 * 0.92F, 3.6F * emBoost);
         this.drawVerticalPolygon(g, c1, r1 * 0.78F, 6, -spin * 1.35F + 0.5236F, polyW * 0.85F, WHITE, alpha1 * 0.85F, 3.8F * emBoost);
         g.end();

         // 1.4 六方位顶点卫星小六芒星阵（随第一环外缘展开并反向自转）
         float satOpen = FxMath.easeOutCubic(FxMath.window(a, RING1_START + 4.0F, RING1_END + 2.0F));
         if (satOpen > 0.01F) {
            float satDist = r1 * 0.92F;
            float satR = r1 * 0.21F * satOpen;
            for (int i = 0; i < 6; i++) {
               double theta = spin + i * (Math.PI / 3.0);
               Vector3f satPos = new Vector3f(c1)
                  .add(new Vector3f(this.axisRight).mul((float)Math.cos(theta) * satDist))
                  .add(new Vector3f(this.axisUp).mul((float)Math.sin(theta) * satDist))
                  .add(new Vector3f(this.axisForward).mul(0.14F * this.distScale));
               Vector3f su = rotatedBasis(this.axisRight, this.axisUp, -spin * 2.1F + i, satR);
               Vector3f sv = rotatedBasis(this.axisRight, this.axisUp, -spin * 2.1F + i + 1.5707964F, satR);
               float[] satCol = i % 2 == 0 ? CYAN : GOLD;
               g.begin(Style.RUNE, 3.6F * emBoost, 0.15F, -spin * 1.8F, this.seedFloat + i * 2.7F, 0.0F, satOpen);
               g.quad(satPos, su, sv, satCol[0], satCol[1], satCol[2], alpha1 * satOpen * 0.92F);
            }
            g.end();
         }
      }

      // ── 衔接导引光丝：在 8 ~ 13 tick 从第一环中心射向第二环位置，优雅引出第二环展开 ──
      if (a >= 8.0F && a < RING2_END + 2.0F) {
         float guideProg = FxMath.easeOutCubic(FxMath.window(a, 8.0F, 12.0F));
         float guideFade = 1.0F - FxMath.window(a, 13.0F, RING2_END + 2.0F);
         if (guideProg > 0.01F && guideFade > 0.01F) {
            Vector3f gEnd = frame.rel(this.ring1Center.lerp(this.ring2Center, guideProg));
            g.begin(Style.RIBBON, 5.2F, 0.15F, 8.0F, 6.0F, 8.0F, 0.35F);
            g.ribbon(List.of(c1, new Vector3f(c1).lerp(gEnd, 0.5F), gEnd), new float[]{0.03F, 0.07F, 0.02F}, new float[]{guideFade, guideFade, guideFade}, CYAN[0], CYAN[1], CYAN[2]);
            g.end();
         }
      }

      // ── 第二环（前级聚能聚焦环 · 宇宙冰青与璨金八芒聚焦架 + 4 枚 3D 聚焦棱晶，11 ~ 22 tick 依次展开） ──
      float open2 = FxMath.easeOutCubic(FxMath.window(a, RING2_START, RING2_END));
      float backKick = a >= BEAM_FIRE && a < BEAM_FIRE + 10.0F
         ? (float)Math.sin(Math.PI * FxMath.window(a, BEAM_FIRE, BEAM_FIRE + 10.0F)) * 0.38F * this.distScale
         : 0.0F;
      Vec3 ring2World = this.ring2Center.subtract(this.beamDir.scale(backKick));
      Vector3f c2 = frame.rel(ring2World);
      float near2 = nearFade(c2);
      float alpha2 = open2 * gateFade * near2;
      if (alpha2 > 0.005F) {
         float r2 = this.ring2Radius * (0.16F + 0.84F * open2) * (1.0F - 0.14F * compress + recoil * 1.35F) * (0.48F + 0.52F * gateFade);
         float emBoost = 1.0F + 0.80F * charge + 0.90F * firePulse;

         // 2.1 反向高速旋转的六芒星聚焦主阵（冰青主色，与第一环虚空紫形成鲜明层次反差）
         float rot2 = -spin * 1.45F;
         Vector3f u2 = rotatedBasis(this.axisRight, this.axisUp, rot2, r2);
         Vector3f v2 = rotatedBasis(this.axisRight, this.axisUp, rot2 + 1.5707964F, r2);
         g.begin(Style.RUNE, 3.2F * emBoost, 0.2F, rot2 * 0.7F, this.seedFloat + 29.0F, 0.0F, open2);
         g.quad(c2, u2, v2, CYAN[0], CYAN[1], CYAN[2], alpha2 * 0.95F);

         // 2.2 前后错位双层 3D 立体炮膛加速环（外环终焉金 + 前置内环冷白青）
         g.begin(Style.RIBBON, 3.8F * emBoost, 0.2F, 6.5F, 9.0F, 6.0F, 0.45F);
         g.torus(c2, this.axisForward, r2 * 1.04F, Math.max(0.045F, r2 * 0.028F), 32, 6, -spin * 1.2F, GOLD[0], GOLD[1], GOLD[2], alpha2 * 0.95F);
         Vector3f c2Front = new Vector3f(c2).add(new Vector3f(this.axisForward).mul(0.28F * this.distScale));
         g.torus(c2Front, this.axisForward, r2 * 0.76F, Math.max(0.032F, r2 * 0.020F), 28, 5, spin * 1.8F, CYAN[0], CYAN[1], CYAN[2], alpha2 * 0.90F);
         g.begin(Style.RING, 3.4F * emBoost, 0.2F, 0.93F, 0.016F, 36.0F, 0.85F + charge * 1.8F);
         g.quad(c2, new Vector3f(this.axisRight).mul(r2 * 1.06F), new Vector3f(this.axisUp).mul(r2 * 1.06F), WHITE[0], WHITE[1], WHITE[2], alpha2 * 0.85F);
         g.end();

         // 2.3 双交错正方形八芒聚焦架
         float polyW = Math.max(0.038F, r2 * 0.020F);
         this.drawVerticalPolygon(g, c2, r2 * 0.86F, 4, rot2 * 1.2F, polyW, GOLD, alpha2 * 0.90F, 3.8F * emBoost);
         this.drawVerticalPolygon(g, c2, r2 * 0.86F, 4, rot2 * 1.2F + 0.785398F, polyW * 0.9F, CYAN, alpha2 * 0.90F, 4.0F * emBoost);
         g.end();

         // 2.4 四方位 3D 立体聚焦棱晶（尖端指向炮口轴心，蓄能时向内紧缩，发射时向外弹震）
         float crystalDist = r2 * (1.16F - 0.18F * compress + 0.26F * firePulse);
         float crystalLen = r2 * 0.26F * open2;
         g.begin(Style.GLOW, 5.2F * emBoost, 0.1F, 2.2F, 1.1F, 0.0F, 0.0F);
         for (int i = 0; i < 4; i++) {
            float ang = rot2 * 0.85F + i * 1.5707964F;
            float cs = (float)Math.cos(ang);
            float sn = (float)Math.sin(ang);
            Vector3f radial = new Vector3f(this.axisRight).mul(cs).add(new Vector3f(this.axisUp).mul(sn)).normalize();
            Vector3f tangent = new Vector3f(this.axisRight).mul(-sn).add(new Vector3f(this.axisUp).mul(cs)).normalize();
            Vector3f pos = new Vector3f(c2).add(new Vector3f(radial).mul(crystalDist));
            Vector3f su = new Vector3f(radial).mul(crystalLen);
            Vector3f sv = new Vector3f(tangent).mul(crystalLen * 0.42F);
            float[] tint = i % 2 == 0 ? GOLD : CYAN;
            g.shard(pos, su, sv, this.axisForward, crystalLen * 0.32F, tint[0], tint[1], tint[2], alpha2);
         }
         g.end();

         // 2.5 两环之间的 6 道锥形螺旋聚能通道（直观展现第一环向第二环高速输能加速）
         float linkAlpha = FxMath.easeOutCubic(FxMath.window(a, RING2_START + 3.0F, RING2_END + 2.0F)) * gateFade * Math.min(near1, near2);
         if (linkAlpha > 0.01F) {
            float r1Now = this.ring1Radius * (0.20F + 0.80F * open1) * (1.0F - 0.10F * compress + recoil) * (0.48F + 0.52F * gateFade);
            g.begin(Style.RIBBON, (2.6F + 3.2F * charge + 2.4F * firePulse), 0.15F, 7.0F, 8.0F, 10.0F + 10.0F * charge, 0.4F);
            int segs = 12;
            for (int k = 0; k < 6; k++) {
               float baseAng = spin + k * (FxMath.TAU / 6.0F);
               List<Vector3f> pts = new ArrayList<>(segs + 1);
               float[] widths = new float[segs + 1];
               float[] alphas = new float[segs + 1];
               for (int i = 0; i <= segs; i++) {
                  float u = (float)i / segs;
                  float twist = baseAng + u * (0.85F + 1.4F * charge);
                  float rr = FxMath.lerp(r1Now * 0.88F, r2 * 0.82F, u);
                  Vector3f p = new Vector3f(c1).lerp(c2, u)
                     .add(new Vector3f(this.axisRight).mul((float)Math.cos(twist) * rr))
                     .add(new Vector3f(this.axisUp).mul((float)Math.sin(twist) * rr));
                  pts.add(p);
                  widths[i] = (0.045F + 0.055F * charge) * this.distScale * (0.6F + 0.4F * (float)Math.sin(Math.PI * u));
                  alphas[i] = linkAlpha * (0.45F + 0.55F * charge);
               }
               float[] tint = k % 2 == 0 ? CYAN : VOID;
               g.ribbon(pts, widths, alphas, tint[0], tint[1], tint[2]);
            }
            g.end();
         }

         // 2.6 第三幕蓄能奇点光核 + 临界压缩十字星芒（20 ~ 36 tick）
         if (a >= CHARGE_START && a < BEAM_FIRE + 4.0F) {
            float chargeIn = FxMath.window(a, CHARGE_START, BEAM_FIRE);
            float postFade = a < BEAM_FIRE ? 1.0F : 1.0F - FxMath.window(a, BEAM_FIRE, BEAM_FIRE + 4.0F);
            float coreAlpha = chargeIn * postFade * near2;
            if (coreAlpha > 0.01F) {
               float coreR = this.ring2Radius * (0.20F + 0.24F * chargeIn) * (1.0F - 0.35F * compress);
               g.geoCenter(c2);
               g.begin(Style.CORONA, 3.6F + 4.8F * compress, 0.2F, 2.2F, 1.4F, 1.4F + 1.4F * compress, 0.5F + 0.5F * compress);
               g.deformedSphere(c2, coreR, 14, 18, -a * 0.16F, 0.07F, 1.8F, a * 0.5F, CYAN[0], CYAN[1], CYAN[2], coreAlpha * 0.92F);
               g.clearGeoCenter();

               // 29~33 tick 临界吸气瞬间：锐利十字星芒提示“即将开炮！”
               if (compress > 0.01F || firePulse > 0.01F) {
                  float flare = Math.max(compress, firePulse);
                  float flareSize = this.ring2Radius * (0.45F + 0.65F * flare);
                  g.begin(Style.GLOW, 8.5F, 0.2F, 6.5F, 2.8F, 1.8F, 0.0F);
                  g.billboard(c2, flareSize, 0.0F, WHITE[0], WHITE[1], WHITE[2], flare * near2);
                  g.billboard(c2, flareSize * 0.85F, 1.5707964F, CYAN[0], CYAN[1], CYAN[2], flare * 0.85F * near2);
                  g.end();
               }
            }
         }
      }
   }

   // ═════════════════════════════ 第四幕：突发光束贯通 ═════════════════════════════

   private void renderBeam(FxGeometry g, FxFrame frame, float a) {
      if (a < BEAM_FIRE || a >= BEAM_HOLD_END) {
         return;
      }
      float tFire = a - BEAM_FIRE;
      float reach = FxMath.easeOutCubic(FxMath.window(a, BEAM_FIRE, BEAM_HIT));
      float fadeOut = 1.0F - FxMath.easeInCubic(FxMath.window(a, BEAM_HIT + 4.0F, BEAM_HOLD_END));
      if (reach <= 0.02F || fadeOut <= 0.01F) {
         return;
      }
      Vec3 toW = this.beamStart.lerp(this.beamEnd, reach);
      Vector3f from = frame.rel(this.beamStart);
      Vector3f to = frame.rel(toW);
      Vector3f c2 = frame.rel(this.ring2Center);
      float rOuter = Math.min(1.45F, this.ring2Radius * 0.44F) * (0.40F + 0.60F * fadeOut);
      float waveFront = reach < 0.99F ? reach : (tFire * 0.20F) % 1.0F;

      // 1. 三层嵌套 3D 等离子光柱（外紫焰鞘 / 中冰青高速层 / 内细白热芯，层次分明不发白）
      g.begin(Style.PLASMA_BEAM, 3.6F * fadeOut, 0.35F, 1.0F, -5.8F, 1.0F - fadeOut, waveFront);
      g.cylinder(from, to, rOuter * 0.72F, rOuter * 1.10F, 20, a * 0.14F, VOID[0], VOID[1], VOID[2], fadeOut * 0.92F);
      g.begin(Style.PLASMA_BEAM, 6.2F * fadeOut, 0.25F, 2.0F, -9.5F, 0.0F, waveFront);
      g.cylinder(from, to, rOuter * 0.40F, rOuter * 0.56F, 16, -a * 0.24F, CYAN[0], CYAN[1], CYAN[2], fadeOut);
      g.begin(Style.PLASMA_BEAM, 10.5F * fadeOut, 0.15F, 1.0F, -13.0F, 0.0F, 0.5F);
      g.cylinder(from, to, rOuter * 0.16F, rOuter * 0.22F, 12, a * 0.36F, WHITE[0], WHITE[1], WHITE[2], fadeOut);
      g.end();

      // 2. 光束前端箭簇破空激波锥（飞行期 33~39 tick，肉眼清晰追踪激光贯穿长空！）
      if (reach > 0.05F && reach < 0.995F) {
         Vec3 headTrail = toW.subtract(this.beamDir.scale(Math.min(5.5 * this.distScale, this.beamStart.distanceTo(toW) * 0.85)));
         g.begin(Style.SHOCK_CONE, 7.2F, 0.3F, 0.45F, 5.2F, 0.9F, 1.0F);
         g.cylinder(to, frame.rel(headTrail), rOuter * 0.22F, rOuter * 2.25F, 16, a * 0.45F, CYAN[0], CYAN[1], CYAN[2], 0.95F);
         g.begin(Style.GLOW, 10.0F, 0.3F, 6.0F, 2.5F, 1.4F, 0.0F);
         g.billboard(to, rOuter * 2.2F, a * 0.3F, WHITE[0], WHITE[1], WHITE[2], 0.95F);
         g.end();
      }

      // 3. 炮口第二环反冲 3D 喇叭形激波锥 + 2 道外扩炮口冲击环（33~45 tick）
      float muzzleT = FxMath.window(a, BEAM_FIRE, BEAM_FIRE + 11.0F);
      if (muzzleT > 0.0F && muzzleT < 1.0F) {
         float ease = FxMath.easeOutCubic(muzzleT);
         float muzzleAlpha = (1.0F - muzzleT) * nearFade(c2);
         Vec3 coneTipW = this.ring2Center.add(this.beamDir.scale((2.2 + 5.6 * ease) * this.distScale));
         g.begin(Style.SHOCK_CONE, 5.8F * (1.0F - muzzleT), 0.3F, ease, 4.0F, 0.95F, 0.95F);
         g.cylinder(c2, frame.rel(coneTipW), this.ring2Radius * 0.32F, this.ring2Radius * (0.95F + 0.95F * ease), 20, a * 0.22F, CYAN[0], CYAN[1], CYAN[2], muzzleAlpha);
         g.begin(Style.RIBBON, 5.2F * (1.0F - muzzleT), 0.2F, 6.0F, 8.0F, 8.0F, 0.45F);
         g.torus(c2, this.axisForward, this.ring2Radius * (0.85F + 1.35F * ease), 0.16F * this.distScale, 28, 5, a * 0.2F, GOLD[0], GOLD[1], GOLD[2], muzzleAlpha);
         g.end();
      }

      // 4. 沿光束轴线高速推进的 5 道 3D 立体加速环（torus）
      g.begin(Style.RIBBON, 4.6F * fadeOut, 0.25F, 6.0F, 8.0F, 10.0F, 0.45F);
      for (int k = 0; k < 5; k++) {
         float frac = ((tFire * 0.14F + k * 0.20F) % 1.0F) * reach;
         if (frac < 0.04F) {
            continue;
         }
         Vec3 rp = this.beamStart.lerp(this.beamEnd, frac);
         Vector3f rRel = frame.rel(rp);
         float rr = rOuter * (1.35F + 0.55F * (float)Math.sin(frac * Math.PI));
         float[] tint = k % 2 == 0 ? CYAN : GOLD;
         g.torus(rRel, this.axisForward, rr, 0.10F * this.distScale, 20, 5, a * 0.25F + k, tint[0], tint[1], tint[2], fadeOut * 0.85F * nearFade(rRel));
      }

      // 5. 缠绕光束的双螺旋金青能量带
      int segs = 26;
      for (int s = 0; s < 2; s++) {
         List<Vector3f> pts = new ArrayList<>(segs + 1);
         float[] widths = new float[segs + 1];
         float[] alphas = new float[segs + 1];
         float sign = s == 0 ? 1.0F : -1.0F;
         for (int i = 0; i <= segs; i++) {
            float u = (float)i / segs;
            float angle = sign * (u * FxMath.TAU * 2.8F - a * 0.55F) + s * 3.14159F;
            float rad = rOuter * (0.88F + 0.36F * u);
            Vector3f offset = new Vector3f(this.axisRight).mul((float)Math.cos(angle) * rad)
               .add(new Vector3f(this.axisUp).mul((float)Math.sin(angle) * rad));
            pts.add(new Vector3f(from).lerp(to, u).add(offset));
            widths[i] = 0.19F * this.distScale * (0.4F + 0.6F * (float)Math.sin(Math.PI * u));
            alphas[i] = fadeOut;
         }
         float[] tint = s == 0 ? CYAN : GOLD;
         g.begin(Style.RIBBON, 6.2F * fadeOut, 0.2F, 6.5F, 5.0F, 9.0F, 0.4F);
         g.ribbon(pts, widths, alphas, tint[0], tint[1], tint[2]);
      }
      g.end();
   }

   /** 蓄力期在目标落点地面投射极克制的暗紫青定位细环，预告打击中心而不喧宾夺主。 */
   private void renderTargetLockPreview(FxGeometry g, FxFrame frame, float a) {
      float in = FxMath.easeOutCubic(FxMath.window(a, 14.0F, 24.0F));
      float out = 1.0F - FxMath.window(a, BEAM_HIT, BEAM_HIT + 4.0F);
      float alpha = in * out * 0.42F;
      if (alpha <= 0.005F) {
         return;
      }
      Vector3f ground = frame.rel(new Vec3(this.center.x, this.groundY + 0.06, this.center.z));
      float r = this.radius * FxMath.lerp(0.55F, 0.32F, FxMath.window(a, 14.0F, BEAM_HIT));
      g.begin(Style.RING, 1.5F, 0.0F, 0.96F, 0.012F, 32.0F, 0.35F);
      g.quad(ground, new Vector3f(r, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r), VOID[0], VOID[1], VOID[2], alpha);
      g.begin(Style.RING, 1.8F, 0.0F, 0.94F, 0.010F, 0.0F, 0.0F);
      g.quad(new Vector3f(ground).add(0.0F, 0.02F, 0.0F), new Vector3f(r * 0.38F, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r * 0.38F), CYAN[0], CYAN[1], CYAN[2], alpha * 0.9F);
      g.end();
   }

   // ═════════════════════════════ 第五～六幕：命中展开地面大阵 + 空间碎裂斩切 + 奇点坍缩 ═════════════════════════════

   private void renderGroundFormation(FxGeometry g, FxFrame frame, Vector3f c, Vector3f impactRel, float a, float collapse, float fade, float shrink) {
      // 命中瞬间第一道落地接触冲击锥与冲击环（39 ~ 51 tick）
      float hitT = FxMath.window(a, BEAM_HIT, BEAM_HIT + 12.0F);
      if (hitT > 0.0F && hitT < 1.0F) {
         float ease = FxMath.easeOutCubic(hitT);
         Vector3f ground = frame.rel(new Vec3(this.center.x, this.groundY + 0.08, this.center.z));
         Vector3f coneTop = frame.rel(new Vec3(this.center.x, this.groundY + 2.2 + this.radius * 0.28 * ease, this.center.z));
         g.begin(Style.SHOCK_CONE, 4.6F * (1.0F - hitT), 0.5F, ease, 3.6F, 0.9F, 0.85F);
         g.cylinder(ground, coneTop, this.radius * 0.08F, this.radius * (0.22F + 0.72F * ease), 24, a * 0.08F, CYAN[0], CYAN[1], CYAN[2], 1.0F - hitT);
         g.begin(Style.RING, 4.8F * (1.0F - hitT), 0.0F, 0.94F, 0.045F, 0.0F, 0.0F);
         float rr = this.radius * (0.15F + 0.95F * ease);
         g.quad(ground, new Vector3f(rr, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, rr), VOID[0], VOID[1], VOID[2], (1.0F - hitT) * 0.9F);
         g.end();
      }

      // 地面巨型复合魔导大阵
      float circleIn = FxMath.easeOutCubic(FxMath.window(a, BEAM_HIT, BEAM_HIT + 12.0F));
      float circleAlpha = circleIn * fade;
      if (circleAlpha > 0.005F) {
         Vector3f ground = frame.rel(new Vec3(this.center.x, this.groundY + 0.08, this.center.z));
         float r = this.radius * shrink;
         g.begin(Style.RUNE, 2.4F + 3.2F * collapse, 0.0F, a * 0.032F, this.seedFloat, 1.0F, circleIn);
         g.quad(ground, new Vector3f(r, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r), VOID[0], VOID[1], VOID[2], circleAlpha * 0.88F);
         g.begin(Style.RUNE, 3.4F + 3.6F * collapse, 0.0F, -a * 0.058F, this.seedFloat + 5.0F, 0.0F, circleIn);
         g.quad(new Vector3f(ground).add(0.0F, 0.04F, 0.0F), new Vector3f(r * 0.54F, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r * 0.54F), CYAN[0], CYAN[1], CYAN[2], circleAlpha * 0.92F);
         float polyW = Math.max(0.055F, this.radius * 0.011F);
         this.drawHorizontalPolygon(g, new Vector3f(ground).add(0.0F, 0.07F, 0.0F), r * 0.94F, 6, a * 0.026F, polyW, GOLD, circleAlpha * 0.72F);
         g.end();
      }

      // 域内悬浮三层星轨 + 双陀螺仪竖环（坍缩时向奇点收拢）
      float haloAlpha = FxMath.window(a, BEAM_HIT + 3.0F, BEAM_HIT + 12.0F) * fade;
      if (haloAlpha > 0.005F) {
         float[] heights = {1.4F, 3.2F, 5.2F};
         float[] radii = {0.76F, 0.52F, 0.30F};
         float[][] colors = {VOID, CYAN, GOLD};
         for (int i = 0; i < 3; i++) {
            float rr = this.radius * radii[i] * shrink;
            float y = FxMath.lerp(heights[i] * this.scale, 1.0F, collapse);
            float rot = a * (0.048F + 0.028F * i) * (i % 2 == 0 ? 1.0F : -1.0F);
            Vector3f u = new Vector3f((float)Math.cos(rot) * rr, 0.0F, (float)Math.sin(rot) * rr);
            Vector3f v = new Vector3f(-(float)Math.sin(rot) * rr, 0.0F, (float)Math.cos(rot) * rr);
            g.begin(Style.RING, 2.4F + 2.8F * collapse, 0.35F, 0.95F, 0.017F, 40.0F + 12.0F * i, 0.45F);
            g.quad(new Vector3f(c).add(0.0F, y, 0.0F), u, v, colors[i][0], colors[i][1], colors[i][2], haloAlpha * 0.85F);
         }
         float gyro = this.radius * 0.84F * shrink;
         g.begin(Style.RIBBON, 2.6F + 3.4F * collapse, 0.35F, 6.0F, 8.0F, 5.0F, 0.45F);
         for (int i = 0; i < 2; i++) {
            float theta = a * (0.085F + 0.045F * i) + i * 1.5708F;
            Vector3f normal = new Vector3f((float)Math.cos(theta), 0.22F * (i == 0 ? 1.0F : -1.0F), (float)Math.sin(theta)).normalize();
            float[] col = i == 0 ? CYAN : VOID;
            g.torus(impactRel, normal, gyro, 0.10F * this.scale, 30, 5, a * 0.08F, col[0], col[1], col[2], haloAlpha * 0.72F);
         }
         g.end();
      }
   }

   /** 命中瞬间闪核 + 第六幕奇点黑洞（倾斜差速吸积盘 + 沸腾日冕 + 相对论喷流 + 螺旋能量抽吸）。 */
   private void renderSingularityCore(FxGeometry g, FxFrame frame, Vector3f impactRel, float a, float collapse) {
      // 光束命中瞬间中心闪芒（39 ~ 48 tick，克制不洗白）
      if (a >= BEAM_HIT && a < BEAM_HIT + 9.0F) {
         float t = (a - BEAM_HIT) / 9.0F;
         g.begin(Style.GLOW, 8.0F, 0.5F, 6.5F, 2.8F, 1.4F, 0.0F);
         g.billboard(impactRel, this.radius * 0.34F * (0.4F + 0.6F * t), 0.0F, WHITE[0], WHITE[1], WHITE[2], (1.0F - t) * (1.0F - t));
         g.begin(Style.GLOW, 2.8F, 0.5F, 3.2F, 2.0F, 0.0F, 0.0F);
         g.billboard(impactRel, this.radius * 0.68F * (0.3F + 0.7F * t), 0.0F, CYAN[0], CYAN[1], CYAN[2], (1.0F - t) * 0.65F);
         g.end();
      }

      // 第六幕：奇点黑洞坍缩（66 ~ 82 tick）
      float coreR = this.coreRadius(a);
      if (a >= COLLAPSE_START && a < DETONATE + 2.0F && coreR > 0.05F) {
         float k = a < DETONATE ? collapse : 1.0F - FxMath.window(a, DETONATE, DETONATE + 2.0F);
         g.occluder(impactRel, coreR);

         // 1. 倾斜高速旋转的紫金吸积盘（Style.DISK）
         float diskR = this.radius * (0.62F - 0.24F * k);
         float spin = a * 0.04F;
         Vector3f du = new Vector3f((float)Math.cos(spin) * diskR, 0.0F, (float)Math.sin(spin) * diskR).rotateX(0.32F).rotateZ(0.18F);
         Vector3f dv = new Vector3f(-(float)Math.sin(spin) * diskR, 0.0F, (float)Math.cos(spin) * diskR).rotateX(0.32F).rotateZ(0.18F);
         g.begin(Style.DISK, 3.4F + 3.8F * k, 0.45F, 0.26F, 2.2F, this.seedFloat, 0.65F);
         g.quad(impactRel, du, dv, VOID[0], VOID[1], VOID[2], k * 0.95F);
         g.end();

         // 2. 沸腾日冕外环
         g.geoCenter(impactRel);
         g.begin(Style.CORONA, 2.8F + 4.0F * k, 0.4F, 2.3F, 1.4F, 1.3F + 1.2F * k, 0.5F + 0.5F * k);
         g.deformedSphere(impactRel, coreR * 1.18F, 16, 22, -a * 0.09F, 0.06F, 1.6F, a * 0.35F, GOLD[0], GOLD[1], GOLD[2], k * 0.90F);
         g.clearGeoCenter();

         // 3. 奇点上下相对论细喷流
         double jetH = Math.min(36.0, this.radius * 1.6) * k;
         if (jetH > 1.0) {
            Vector3f jetTop = frame.rel(this.impactCenter.add(0.0, coreR * 0.7 + jetH, 0.0));
            Vector3f jetBot = frame.rel(new Vec3(this.center.x, this.groundY + 0.05, this.center.z));
            float jetR = Math.max(0.18F, coreR * 0.26F);
            g.begin(Style.PLASMA_BEAM, 5.8F * k, 0.4F, 1.0F, -6.0F, 0.0F, 0.5F);
            g.cylinder(new Vector3f(impactRel).add(0.0F, coreR * 0.7F, 0.0F), jetTop, jetR, jetR * 0.25F, 12, a * 0.2F, CYAN[0], CYAN[1], CYAN[2], k * 0.88F);
            g.cylinder(new Vector3f(impactRel).sub(0.0F, coreR * 0.6F, 0.0F), jetBot, jetR * 0.9F, jetR * 0.4F, 12, -a * 0.2F, CYAN[0], CYAN[1], CYAN[2], k * 0.75F);
            g.end();
         }

         // 4. 6 道从域外螺旋卷入奇点的能量抽吸流
         float outerR = this.radius * 0.92F;
         float innerR = Math.max(coreR * 1.2F, 0.8F);
         float ln = (float)Math.log(Math.max(1.2F, outerR / innerR));
         g.begin(Style.RIBBON, 2.8F + 3.2F * k, 0.35F, 6.5F, 5.0F, 8.0F, 0.45F);
         for (int arm = 0; arm < 6; arm++) {
            float theta0 = arm * FxMath.TAU / 6.0F + a * (0.14F + 0.18F * k);
            int n = 18;
            List<Vector3f> pts = new ArrayList<>(n + 1);
            float[] widths = new float[n + 1];
            float[] alphas = new float[n + 1];
            for (int i = 0; i <= n; i++) {
               float u = (float)i / n;
               float r = outerR * (float)Math.exp(-u * ln);
               float theta = theta0 + u * FxMath.TAU * 0.72F;
               double y = FxMath.lerp((float)this.groundY + 0.5F + (arm % 3) * 1.4F, (float)this.impactCenter.y, FxMath.easeInOutSine(u));
               pts.add(frame.rel(new Vec3(this.center.x + Math.cos(theta) * r, y, this.center.z + Math.sin(theta) * r)));
               widths[i] = 0.16F * this.scale * (1.0F - u * 0.65F);
               alphas[i] = k * (float)Math.sin(Math.PI * u) * 0.75F;
            }
            float[] tint = arm % 3 == 0 ? GOLD : (arm % 3 == 1 ? CYAN : VOID);
            g.ribbon(pts, widths, alphas, tint[0], tint[1], tint[2]);
         }
         g.end();
         g.clearOccluder();
      }
   }

   private void renderSlashesAndCuts(FxGeometry g, FxFrame frame, Vector3f impactRel, float a, float fade) {
      // 1. 域内预斩 3D 立体刀弧（亮刃层，与 renderBody 暗黑切口成对）
      this.renderDomainArcs(g, impactRel, this.preArcs, a, false);

      // 2. 高速弧形空间斩击丝带
      for (Slash slash : this.slashes) {
         float t = a - slash.spawn;
         if (t < 0.0F || t > 7.5F) {
            continue;
         }
         float head = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 2.2F));
         float tail = FxMath.easeInCubic(FxMath.window(t, 1.4F, 6.8F));
         if (head - tail < 0.01F) {
            continue;
         }
         float life = 1.0F - FxMath.window(t, 4.2F, 7.5F);
         int n = 22;
         List<Vector3f> points = new ArrayList<>(n + 1);
         float[] widths = new float[n + 1];
         float[] alphas = new float[n + 1];
         for (int i = 0; i <= n; i++) {
            float k = (float)i / n;
            float u = FxMath.lerp(tail, head, k);
            points.add(frame.rel(slash.point(u)));
            widths[i] = slash.width * (float)Math.pow(Math.sin(Math.PI * k), 0.75);
            alphas[i] = life * (0.35F + 0.65F * k);
         }
         float[] tint = slash.golden ? GOLD : CYAN;
         g.begin(Style.RIBBON, 2.4F, 0.3F, 2.5F, 3.0F, 5.0F, 0.8F);
         g.ribbon(points, scaled(widths, 3.0F), alphas, VOID[0], VOID[1], VOID[2]);
         g.begin(Style.RIBBON, 7.8F, 0.3F, 7.0F, 6.0F, 9.0F, 0.3F);
         g.ribbon(points, widths, alphas, tint[0], tint[1], tint[2]);
      }
      g.end();

      // 3. 域内悬停空间刀痕（VOID_SLIT 亮刃，主斜劈在坍缩期蓄亮准备切屏）
      float scarFade = 1.0F - FxMath.window(a, DETONATE + 4.0F, DETONATE + 12.0F);
      if (scarFade > 0.01F) {
         for (CutScar scar : this.cutScars) {
            float t = a - scar.spawn;
            if (t < 0.0F) {
               continue;
            }
            boolean majorBoost = scar.major && a >= COLLAPSE_START + 4.0F;
            float open = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 2.0F)) * (majorBoost ? 1.50F : 1.0F);
            float pulse = majorBoost ? 1.45F : 1.0F;
            float[] tint = scar.major ? WHITE : CYAN;
            g.begin(Style.VOID_SLIT, 5.8F * pulse, 0.0F, open * 0.55F, 0.65F, 1.0F, 0.25F);
            this.drawCutQuad(g, frame, scar, 0.44F * scar.weight * this.scale, tint, scarFade * Math.max(fade, majorBoost ? 0.85F : 0.0F));
            g.end();
         }
      }
   }

   /** 通用 3D 立体空间刀弧渲染器（Body 暗黑空间切口 + HDR 高能亮刃）。 */
   private void renderDomainArcs(FxGeometry g, Vector3f centerRel, List<DomainArc> arcs, float a, boolean bodyPass) {
      for (DomainArc arc : arcs) {
         float lt = a - arc.spawn;
         if (lt <= 0.0F || lt >= arc.duration) {
            continue;
         }
         float head = FxMath.easeOutCubic(Math.min(1.0F, lt / 2.6F));
         float tail = FxMath.easeInCubic(FxMath.window(lt, arc.duration * 0.35F, arc.duration));
         if (tail >= head - 0.01F) {
            continue;
         }
         float alpha = 1.0F - FxMath.window(lt, arc.duration * 0.65F, arc.duration);
         if (bodyPass) {
            g.begin(Style.SLASH_ARC, 0.32F, 0.45F, 6.2F, 0.88F, head, tail);
            g.slashArc(centerRel, arc.u, arc.v, arc.innerR * 0.93F, arc.outerR * 1.03F, arc.startAngle, arc.endAngle, 20, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha * 0.94F);
         } else {
            g.begin(Style.SLASH_ARC, 6.2F, 0.45F, 9.2F, 0.85F, head, tail);
            g.slashArc(centerRel, arc.u, arc.v, arc.innerR, arc.outerR, arc.startAngle, arc.endAngle, 20, arc.color[0], arc.color[1], arc.color[2], alpha);
         }
      }
      g.end();
   }

   /** 32 片碎裂空间镜像片（悬停震颤 → 坍缩向心吸拉 → 爆轰高速飞溅与灼烧溶解）。 */
   private void renderMirrorShardLayer(FxGeometry g, FxFrame frame, float a, boolean bodyPass) {
      float shatterAlpha = 1.0F - FxMath.window(a, DETONATE + 24.0F, LIFETIME - 4.0F);
      if (shatterAlpha <= 0.005F) {
         return;
      }
      float collapse = this.collapse(a);
      for (MirrorShard shard : this.mirrorShards) {
         if (a < shard.spawn) {
            continue;
         }
         float appear = FxMath.easeOutCubic(FxMath.window(a, shard.spawn, shard.spawn + 3.5F));
         float post = Math.max(0.0F, a - DETONATE);
         float dissolve = post > 14.0F ? FxMath.window(post, 14.0F, 38.0F) : 0.0F;
         if (dissolve >= 0.98F) {
            continue;
         }
         // 爆裂前先悬停震颤、坍缩时向奇点略收拢；爆裂后向外急加速飞溅再转慢漂
         float pullIn = post <= 0.0F ? -collapse * 0.35F * (float)shard.origin.distanceTo(this.impactCenter) : 0.0F;
         float burst = post > 0.0F ? (float)(Math.pow(post, 0.72) * 3.2F) * shard.speed : 0.0F;
         float tremor = post <= 0.0F ? (float)Math.sin(a * 0.9F + shard.spawn) * 0.09F * this.scale : 0.0F;
         float radialMove = pullIn + burst + tremor;
         Vec3 worldPos = shard.origin.add(
            shard.outward.x * radialMove,
            shard.outward.y * radialMove + (post > 0.0F ? 0.025 * post : 0.0),
            shard.outward.z * radialMove
         );
         Vector3f rel = frame.rel(worldPos);
         float angle = (a - shard.spawn) * shard.spin * (post > 0.0F ? 2.2F : 0.35F);
         float cs = (float)Math.cos(angle);
         float sn = (float)Math.sin(angle);
         Vector3f ru = new Vector3f(shard.axisU).mul(cs).add(new Vector3f(shard.axisV).mul(sn));
         Vector3f rv = new Vector3f(shard.axisV).mul(cs).sub(new Vector3f(shard.axisU).mul(sn));
         Vector3f u = new Vector3f(ru).mul(shard.size * appear);
         Vector3f v = new Vector3f(ru).mul(0.42F).add(new Vector3f(rv).mul(1.35F)).mul(shard.size * appear);
         float alpha = appear * shatterAlpha * (1.0F - dissolve) * nearFade(rel);
         if (alpha <= 0.005F) {
            continue;
         }
         if (bodyPass) {
            g.begin(Style.AFTERIMAGE, 0.85F, 0.0F, 1.4F, 0.74F, 0.0F, dissolve);
            g.quad(rel, u, v, DEEP_ABYSS[0], DEEP_ABYSS[1], DEEP_ABYSS[2], alpha * 0.90F);
         } else {
            g.begin(Style.AFTERIMAGE, post > 0.0F && post < 8.0F ? 6.8F : 3.8F, 0.0F, 1.6F, 0.14F, 4.0F, dissolve);
            g.quad(rel, u, v, shard.color[0], shard.color[1], shard.color[2], alpha);
         }
      }
      g.end();
   }

   private void renderFieldLightningAndMarks(FxGeometry g, FxFrame frame, Vector3f c, float a, float shrink) {
      float boltAlpha = FxMath.window(a, BEAM_HIT + 1.0F, BEAM_HIT + 5.0F) * (1.0F - FxMath.window(a, DETONATE - 2.0F, DETONATE));
      if (boltAlpha > 0.0F) {
         float flicker = 0.55F + 0.45F * (float)Math.abs(Math.sin(a * 5.3F + this.seedFloat));
         for (List<Vector3f> bolt : this.fieldBolts) {
            List<Vector3f> points = new ArrayList<>(bolt.size());
            float[] widths = new float[bolt.size()];
            float[] alphas = new float[bolt.size()];
            for (int i = 0; i < bolt.size(); i++) {
               points.add(new Vector3f(bolt.get(i)).mul(shrink).add(c));
               float k = (float)i / (bolt.size() - 1);
               widths[i] = (0.085F * (1.0F - k * 0.7F)) * this.scale;
               alphas[i] = boltAlpha * flicker * (1.0F - k * 0.5F);
            }
            g.begin(Style.RIBBON, 2.0F, 0.2F, 2.0F, 9.0F, 14.0F, 0.9F);
            g.ribbon(points, scaled(widths, 3.8F), alphas, VOID[0], VOID[1], VOID[2]);
            g.begin(Style.RIBBON, 6.8F, 0.2F, 9.0F, 12.0F, 20.0F, 0.2F);
            g.ribbon(points, widths, alphas, CYAN[0], CYAN[1], CYAN[2]);
         }
         g.end();
      }

      // 锁定目标脚下处决星阵 + 交叉空间斩痕（命中后才逐个点亮锁定，直至终极爆轰）
      for (int i = 0; i < this.targetIds.length; i++) {
         float t = a - this.markStart(i);
         if (t < 0.0F || a > DETONATE + 6.0F) {
            continue;
         }
         Vec3 pos = this.targetPos(frame.level, i, frame.partialTick);
         float size = this.targetSizes[i];
         float open = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 4.5F));
         float life = 1.0F - FxMath.window(a, DETONATE, DETONATE + 6.0F);
         Vector3f foot = frame.rel(pos.add(0.0, 0.05, 0.0));
         float rr = size * 1.5F * (0.6F + 0.4F * open);
         g.begin(Style.RUNE, 4.2F, 0.0F, t * 0.11F, this.seedFloat + i * 1.7F, 0.0F, open);
         g.quad(foot, new Vector3f(rr, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, rr), GOLD[0], GOLD[1], GOLD[2], life * 0.9F);
         g.end();

         float cutLife = 1.0F - FxMath.window(t, 5.0F, 12.0F);
         if (cutLife > 0.0F) {
            Vector3f mid = frame.rel(pos.add(0.0, size * 0.7, 0.0));
            float cut = FxMath.easeOutCubic(FxMath.window(t, 0.5F, 3.2F));
            float span = size * 1.55F;
            g.begin(Style.RIBBON, 8.2F, 0.2F, 8.0F, 5.0F, 8.0F, 0.35F);
            for (int k = 0; k < 2; k++) {
               Vector3f dir = k == 0 ? new Vector3f(1.0F, 0.75F, 0.6F) : new Vector3f(-0.6F, 0.75F, 1.0F);
               dir.normalize().mul(span);
               Vector3f from = new Vector3f(mid).sub(dir);
               Vector3f to = new Vector3f(from).lerp(new Vector3f(mid).add(dir), cut);
               List<Vector3f> line = List.of(from, new Vector3f(from).lerp(to, 0.5F), to);
               float w = 0.11F * size;
               g.ribbon(line, new float[]{0.0F, w, w * 0.4F}, new float[]{cutLife, cutLife, cutLife}, WHITE[0], WHITE[1], WHITE[2]);
            }
            g.end();
         }
      }
   }

   // ═════════════════════════════ 第七幕：终极绚烂超新星空间爆轰 ═════════════════════════════

   private void renderSupernovaDetonation(FxGeometry g, FxFrame frame, Vector3f impactRel, float a) {
      float post = a - DETONATE;
      Vector3f ground = frame.rel(new Vec3(this.center.x, this.groundY + 0.12, this.center.z));

      // ── 奇观 ①：多壳层超新星沸腾星云球 + 龟裂崩碎空间晶格穹顶（0 ~ 28 tick） ──
      if (post < 28.0F) {
         float expand = FxMath.easeOutCubic(FxMath.window(post, 0.0F, 16.0F));
         float fadeOut = 1.0F - FxMath.easeInCubic(FxMath.window(post, 12.0F, 28.0F));
         if (fadeOut > 0.01F) {
            float innerR = this.radius * (0.14F + 0.52F * expand);
            float midR = this.radius * (0.20F + 0.72F * expand);
            // 色彩随爆炸进程自然演进：初爆白金 → 宇宙冰青 → 虚空星云紫品红
            float colorT = FxMath.window(post, 1.0F, 14.0F);
            float inR = FxMath.lerp(GOLD[0], CYAN[0], colorT);
            float inG = FxMath.lerp(GOLD[1], CYAN[1], colorT);
            float inB = FxMath.lerp(GOLD[2], CYAN[2], colorT);
            float outR = FxMath.lerp(MAGENTA[0], VOID[0], colorT);
            float outG = FxMath.lerp(MAGENTA[1], VOID[1], colorT);
            float outB = FxMath.lerp(MAGENTA[2], VOID[2], colorT);

            g.geoCenter(impactRel);
            g.begin(Style.CORONA, (6.5F - 2.6F * colorT) * fadeOut, 0.65F, 2.1F, 1.45F, 1.5F, 0.85F);
            g.deformedSphere(impactRel, innerR, 18, 26, -a * 0.08F, 0.08F * (1.0F - post / 36.0F), 1.6F, a * 0.36F, inR, inG, inB, fadeOut * 0.92F);
            g.begin(Style.CORONA, 4.0F * fadeOut, 0.75F, 2.6F, 1.35F, 1.1F, 0.65F);
            g.deformedSphere(impactRel, midR, 18, 24, a * 0.06F, 0.06F, 1.3F, -a * 0.28F, outR, outG, outB, fadeOut * 0.76F);

            // 外层破碎空间晶穹（Style.SHIELD 随爆炸迅速龟裂点亮炸开）
            float domeT = FxMath.window(post, 0.0F, 20.0F);
            if (domeT < 1.0F) {
               float domeR = this.radius * (0.35F + 0.82F * FxMath.easeOutCubic(domeT));
               float domeFade = (1.0F - domeT) * (1.0F - domeT * 0.4F);
               g.begin(Style.SHIELD, 4.6F * domeFade, 0.6F, 11.0F, FxMath.clamp01(domeT * 1.35F), a * 0.65F, 1.05F);
               g.deformedSphere(impactRel, domeR, 16, 24, a * 0.04F, 0.03F, 2.0F, a * 0.25F, CYAN[0], CYAN[1], CYAN[2], domeFade * 0.85F);
            }
            g.clearGeoCenter();
         }
      }

      // 爆炸瞬间锐利核心星芒（短促克制 0 ~ 10 tick，不过曝洗白）
      if (post < 10.0F) {
         float t = post / 10.0F;
         float k = (1.0F - t) * (1.0F - t);
         g.begin(Style.GLOW, 11.5F, 0.6F, 6.2F, 2.8F, 1.8F, 0.0F);
         g.billboard(impactRel, this.radius * (0.36F + 0.55F * t), 0.0F, WHITE[0], WHITE[1], WHITE[2], k);
         g.begin(Style.GLOW, 4.2F, 0.6F, 3.2F, 2.0F, 1.2F, 0.0F);
         g.billboard(impactRel, this.radius * (0.65F + 0.95F * t), 0.7854F, GOLD[0], GOLD[1], GOLD[2], (1.0F - t) * 0.80F);
         g.end();
      }

      // ── 奇观 ②：通天 3D 湮灭光柱 + 5 道冲天加速立体压环 + 底座耀斑冠（0 ~ 26 tick） ──
      if (post < 26.0F) {
         float pillarIn = FxMath.easeOutCubic(FxMath.window(post, 0.0F, 2.5F));
         float pillarOut = 1.0F - FxMath.window(post, 10.0F, 26.0F);
         float pillarAlpha = pillarIn * pillarOut;
         if (pillarAlpha > 0.01F) {
            double ceiling = Math.min(frame.level.getMaxBuildHeight(), this.groundY + Math.max(58.0, this.radius * 3.2));
            Vector3f base = frame.rel(new Vec3(this.center.x, this.groundY, this.center.z));
            Vector3f top = frame.rel(new Vec3(this.center.x, ceiling, this.center.z));
            float pillarR = Math.min(5.6F, this.radius * 0.22F) * (0.68F + 0.32F * pillarAlpha);
            float coreR = pillarR * 0.42F;
            float collapseProg = FxMath.window(post, 10.0F, 26.0F);
            float wavePos = FxMath.window(post, 0.0F, 8.0F);

            g.begin(Style.PLASMA_BEAM, 4.4F * pillarAlpha, 0.75F, 1.5F, -3.8F, collapseProg, wavePos);
            g.cylinder(base, top, pillarR * 1.12F, pillarR * 0.66F, 20, a * 0.12F, MAGENTA[0], MAGENTA[1], MAGENTA[2], pillarAlpha * 0.88F);
            g.begin(Style.PLASMA_BEAM, 6.2F * pillarAlpha, 0.65F, 2.0F, -5.2F, collapseProg, wavePos);
            g.cylinder(base, top, pillarR * 0.70F, pillarR * 0.44F, 16, -a * 0.17F, CYAN[0], CYAN[1], CYAN[2], pillarAlpha * 0.94F);
            g.begin(Style.PLASMA_BEAM, 10.8F * pillarAlpha, 0.55F, 1.0F, -7.2F, collapseProg, wavePos);
            g.cylinder(base, top, coreR, coreR * 0.52F, 12, a * 0.24F, WHITE[0], WHITE[1], WHITE[2], pillarAlpha);

            // 5 道沿光柱冲天飙升的立体 3D 能量压环
            g.begin(Style.RIBBON, 5.6F * pillarAlpha, 0.5F, 6.5F, 8.0F, 10.0F, 0.45F);
            for (int k = 0; k < 5; k++) {
               float ringT = (post * 0.085F + k * 0.20F) % 1.0F;
               double y = this.groundY + (ceiling - this.groundY) * ringT * 0.68;
               float[] col = k % 2 == 0 ? GOLD : CYAN;
               g.torus(frame.rel(new Vec3(this.center.x, y, this.center.z)), new Vector3f(0.0F, 1.0F, 0.0F), pillarR * (1.55F + 1.25F * (1.0F - ringT)), 0.26F, 24, 6, a * 0.18F + k, col[0], col[1], col[2], pillarAlpha * (1.0F - ringT * 0.55F));
            }

            // 贴地沸腾耀斑底座
            Vector3f crownTop = frame.rel(new Vec3(this.center.x, this.groundY + 6.5 * this.scale, this.center.z));
            g.begin(Style.REENTRY, 4.6F * pillarAlpha, 0.5F, 1.1F, 1.5F, 2.8F, 0.55F);
            g.cylinder(base, crownTop, pillarR * 2.2F, pillarR * 0.9F, 18, -a * 0.14F, GOLD[0], GOLD[1], GOLD[2], pillarAlpha * 0.85F);
            g.end();
         }
      }

      // ── 奇观 ③：双层 3D 喇叭形气浪冲击锥 + 四重分时跨色立体冲击环 ──
      float shockA = FxMath.window(post, 0.0F, 22.0F);
      if (shockA > 0.0F && shockA < 1.0F) {
         float ease = FxMath.easeOutCubic(shockA);
         // 贴地紫金横扫气浪锥
         Vector3f topA = frame.rel(new Vec3(this.center.x, this.groundY + 3.5 + this.radius * 0.34 * ease, this.center.z));
         g.begin(Style.SHOCK_CONE, 5.4F * (1.0F - shockA), 0.7F, ease, 3.0F, 1.0F, 0.90F);
         g.cylinder(ground, topA, this.radius * 0.12F, this.radius * (0.28F + 1.42F * ease), 28, a * 0.06F, GOLD[0], GOLD[1], GOLD[2], 1.0F - shockA);
      }
      float shockB = FxMath.window(post, 2.0F, 24.0F);
      if (shockB > 0.0F && shockB < 1.0F) {
         float ease = FxMath.easeOutCubic(shockB);
         // 高扬冲天冰青空间激波锥
         Vector3f topB = frame.rel(new Vec3(this.center.x, this.groundY + 6.0 + this.radius * 0.82 * ease, this.center.z));
         g.begin(Style.SHOCK_CONE, 5.0F * (1.0F - shockB), 0.7F, ease, 3.8F, 1.0F, 0.80F);
         g.cylinder(ground, topB, this.radius * 0.16F, this.radius * (0.22F + 0.96F * ease), 24, -a * 0.09F, CYAN[0], CYAN[1], CYAN[2], 1.0F - shockB);
      }
      g.end();

      // 四重分时跨色立体 3D 冲击环（白金 → 冰青斜环 → 虚空紫反斜环 → 星辉品红高位余震环）
      float[] ringDelays = {0.0F, 3.0F, 6.0F, 9.0F};
      float[] ringDurs = {20.0F, 21.0F, 22.0F, 24.0F};
      float[] ringMaxR = {1.90F, 1.65F, 1.55F, 1.40F};
      float[] ringYOff = {0.5F, 2.4F, 3.6F, 5.2F};
      Vector3f[] ringNormals = {
         new Vector3f(0.0F, 1.0F, 0.0F),
         new Vector3f(0.36F, 0.92F, 0.14F).normalize(),
         new Vector3f(-0.40F, 0.89F, -0.22F).normalize(),
         new Vector3f(0.08F, 0.99F, -0.06F).normalize()
      };
      float[][] ringColors = {GOLD, CYAN, VOID, MAGENTA};
      g.begin(Style.RIBBON, 5.8F, 0.45F, 5.5F, 6.0F, 8.0F, 0.45F);
      for (int i = 0; i < 4; i++) {
         float p = FxMath.window(post, ringDelays[i], ringDelays[i] + ringDurs[i]);
         if (p <= 0.0F || p >= 1.0F) {
            continue;
         }
         float ease = FxMath.easeOutCubic(p);
         float r = this.radius * (0.12F + ringMaxR[i] * ease);
         float fade = (1.0F - p) * (1.0F - p * 0.35F);
         Vector3f rc = frame.rel(new Vec3(this.center.x, this.groundY + ringYOff[i], this.center.z));
         g.torus(rc, ringNormals[i], r, (0.48F - 0.18F * p) * Math.min(1.4F, this.scale), 36, 6, a * (i % 2 == 0 ? 0.08F : -0.09F), ringColors[i][0], ringColors[i][1], ringColors[i][2], fade);
      }
      g.end();

      // 地面贴地扩散白金符文冲击波
      if (shockA > 0.0F && shockA < 1.0F) {
         float gr = this.radius * 1.95F * FxMath.easeOutCubic(shockA);
         g.begin(Style.RING, 5.6F * (1.0F - shockA), 0.0F, 0.94F, 0.042F, 0.0F, 0.0F);
         g.quad(ground, new Vector3f(gr, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, gr), GOLD[0], GOLD[1], GOLD[2], 1.0F - shockA);
         g.end();
      }

      // ── 奇观 ④：次元之花（10 道巨型 3D 空间刀弧高光刃） ──
      this.renderDomainArcs(g, impactRel, this.blastArcs, a, false);

      // ── 奇观 ⑤：14 道环绕爆区的分形虚空天雷 ──
      if (!this.skyBolts.isEmpty()) {
         g.begin(Style.RIBBON, 2.4F, 0.4F, 2.2F, 9.0F, 18.0F, 0.85F);
         for (FxLightningBolt bolt : this.skyBolts) {
            if (bolt.visible()) {
               bolt.draw(g, frame, 3.4F, 0.52F, VOID);
            }
         }
         g.begin(Style.RIBBON, 9.5F, 0.4F, 9.5F, 12.0F, 24.0F, 0.2F);
         for (FxLightningBolt bolt : this.skyBolts) {
            if (bolt.visible()) {
               bolt.draw(g, frame, 0.85F, 1.0F, bolt.color);
            }
         }
         g.end();
      }

      // ── 奇观 ⑥：72 枚真 3D 慢动作浮空旋转棱镜星晶（1 次合批绘制，五色璀璨！） ──
      if (!this.crystalShards.isEmpty()) {
         g.begin(Style.GLOW, 5.6F, 0.0F, 2.2F, 1.1F, 0.0F, 0.0F);
         for (CrystalShard shard : this.crystalShards) {
            Vec3 p = shard.position(frame.partialTick);
            Vector3f rel = frame.rel(p);
            float lifeAlpha = (float)shard.life / shard.maxLife;
            float alpha = (float)Math.pow(lifeAlpha, 0.75) * nearFade(rel);
            if (alpha <= 0.01F) {
               continue;
            }
            float spin = shard.phase + a * shard.spinSpeed;
            float cs = (float)Math.cos(spin);
            float sn = (float)Math.sin(spin);
            float s = shard.size * (0.55F + 0.45F * lifeAlpha);
            Vector3f u = new Vector3f(cs * s, sn * s * 0.65F, -sn * s * 0.76F);
            Vector3f v = new Vector3f(-sn * s * 0.55F, cs * s * 1.45F, cs * s * 0.55F);
            Vector3f n = new Vector3f(u).cross(v);
            if (n.lengthSquared() < 1.0E-5F) {
               continue;
            }
            n.normalize();
            g.shard(rel, u, v, n, s * 0.34F, shard.color[0], shard.color[1], shard.color[2], alpha);
         }
         g.end();
      }

      // ── 奇观 ⑦：爆心余韵虚空残缝（缓缓闭合归寂） ──
      if (post >= 10.0F && post < 52.0F) {
         float slitOpen = FxMath.easeOutCubic(FxMath.window(post, 10.0F, 16.0F)) * (1.0F - FxMath.easeInCubic(FxMath.window(post, 30.0F, 52.0F)));
         if (slitOpen > 0.01F) {
            g.begin(Style.VOID_SLIT, 5.2F * slitOpen, 0.0F, slitOpen * 0.62F, 0.7F, 0.0F, 0.35F);
            this.drawVerticalSlit(g, frame, this.impactCenter.add(0.0, 2.2 * this.scale, 0.0), 1.65F * this.scale, 5.4F * this.scale, CYAN, slitOpen);
            g.end();
         }
      }
   }

   private void renderSparks(FxGeometry g, FxFrame frame) {
      if (this.sparks.isEmpty()) {
         return;
      }
      g.begin(Style.RIBBON, 6.0F, 0.0F, 6.0F, 0.0F, 0.0F, 0.5F);
      for (Spark spark : this.sparks) {
         Vec3 p = spark.position(frame.partialTick);
         Vec3 tail = p.subtract(spark.vx * 2.8, spark.vy * 2.8, spark.vz * 2.8);
         float life = (float)spark.life / spark.maxLife;
         Vector3f rel = frame.rel(p);
         g.streak(rel, frame.rel(tail), 0.055F * Math.min(1.4F, this.scale), spark.color[0], spark.color[1], spark.color[2], life * nearFade(rel));
      }
      g.end();
   }

   // ───────────────────────────── 几何绘制辅助 ─────────────────────────────

   private void drawCutQuad(FxGeometry g, FxFrame frame, CutScar scar, float halfWidth, float[] color, float alpha) {
      Vec3 midW = scar.a.lerp(scar.b, 0.5);
      Vector3f relA = frame.rel(scar.a);
      Vector3f relB = frame.rel(scar.b);
      Vector3f relMid = frame.rel(midW);
      Vector3f axisV = new Vector3f(relB).sub(relA).mul(0.5F);
      Vector3f toCam = new Vector3f(relMid).negate();
      if (toCam.lengthSquared() < 1.0E-5F) {
         toCam.set(0.0F, 0.0F, 1.0F);
      }
      Vector3f cross = new Vector3f(axisV).cross(toCam);
      if (cross.lengthSquared() < 1.0E-5F) {
         return;
      }
      Vector3f axisU = cross.normalize().mul(halfWidth);
      g.quad(relMid, axisU, axisV, color[0], color[1], color[2], alpha);
   }

   private void drawVerticalSlit(FxGeometry g, FxFrame frame, Vec3 centerW, float halfW, float halfH, float[] color, float alpha) {
      Vector3f rel = frame.rel(centerW);
      Vector3f axisV = new Vector3f(0.0F, halfH, 0.0F);
      Vector3f toCam = new Vector3f(rel.x, 0.0F, rel.z).negate();
      if (toCam.lengthSquared() < 1.0E-5F) {
         toCam.set(1.0F, 0.0F, 0.0F);
      }
      Vector3f axisU = new Vector3f(axisV).cross(toCam).normalize().mul(halfW);
      g.quad(rel, axisU, axisV, color[0], color[1], color[2], alpha);
   }

   private void drawVerticalPolygon(FxGeometry g, Vector3f center, float r, int sides, float rot, float width, float[] color, float alpha, float emission) {
      List<Vector3f> pts = new ArrayList<>(sides + 1);
      float[] widths = new float[sides + 1];
      float[] alphas = new float[sides + 1];
      for (int i = 0; i <= sides; i++) {
         double theta = rot + (double)(i % sides) / sides * FxMath.TAU;
         pts.add(new Vector3f(center)
            .add(new Vector3f(this.axisRight).mul((float)Math.cos(theta) * r))
            .add(new Vector3f(this.axisUp).mul((float)Math.sin(theta) * r)));
         widths[i] = width;
         alphas[i] = alpha;
      }
      g.begin(Style.RIBBON, emission, 0.15F, 7.0F, 0.0F, 0.0F, 0.45F);
      g.ribbon(pts, widths, alphas, color[0], color[1], color[2]);
   }

   private void drawHorizontalPolygon(FxGeometry g, Vector3f center, float r, int sides, float rot, float width, float[] color, float alpha) {
      List<Vector3f> pts = new ArrayList<>(sides + 1);
      float[] widths = new float[sides + 1];
      float[] alphas = new float[sides + 1];
      for (int i = 0; i <= sides; i++) {
         double theta = rot + (double)(i % sides) / sides * FxMath.TAU;
         pts.add(new Vector3f(center.x + (float)Math.cos(theta) * r, center.y, center.z + (float)Math.sin(theta) * r));
         widths[i] = width;
         alphas[i] = alpha;
      }
      g.begin(Style.RIBBON, 3.6F, 0.0F, 7.0F, 0.0F, 0.0F, 0.45F);
      g.ribbon(pts, widths, alphas, color[0], color[1], color[2]);
   }

   private static Vector3f rotatedBasis(Vector3f right, Vector3f up, float angle, float radius) {
      return new Vector3f(right).mul((float)Math.cos(angle) * radius).add(new Vector3f(up).mul((float)Math.sin(angle) * radius));
   }

   private static float[] scaled(float[] values, float factor) {
      float[] out = new float[values.length];
      for (int i = 0; i < values.length; i++) {
         out[i] = values[i] * factor;
      }
      return out;
   }

   private static float clamp(float v, float min, float max) {
      return Math.max(min, Math.min(max, v));
   }

   private static double clampD(double v, double min, double max) {
      return Math.max(min, Math.min(max, v));
   }

   // ───────────────────────────── B 档全屏通道（命中后才激活，绝不提前干扰双环） ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      float a = this.age(frame.partialTick);
      if (a < BEAM_HIT) {
         return;
      }
      float fade = this.fieldFade(a);
      float shock = this.shockProgress(a);
      boolean fieldVisible = fade > 0.001F;
      boolean shockVisible = shock >= 0.0F && shock < 1.0F;
      if (!fieldVisible && !shockVisible) {
         return;
      }

      // 通道 1：域内空间碎裂折射 + 3D Voronoi 体积裂隙网 + 黑洞事件视界 + 双波段宇宙冲击波壳
      Vector3f c = frame.rel(this.impactCenter);
      float reveal = this.fieldReveal(a);
      float collapse = this.collapse(a);
      float shockRadius = shockVisible ? this.radius * 1.95F * FxMath.easeOutCubic(shock) : 0.0F;
      float shockStrength = shockVisible ? (1.0F - shock) * (1.0F - shock) : 0.0F;
      float coreRadius = this.coreRadius(a);
      float hitFlash = a >= BEAM_HIT && a < BEAM_HIT + 6.0F ? (1.0F - (a - BEAM_HIT) / 6.0F) * 0.65F : 0.0F;
      float blastFlash = a >= DETONATE && a < DETONATE + 9.0F ? (1.0F - (a - DETONATE) / 9.0F) * 1.05F : 0.0F;
      float flash = Math.max(hitFlash, blastFlash);
      float voidRatio = 0.06F + collapse * 0.64F;
      float cutIntensity = this.shatterCutIntensity(a);
      float cellSize = this.radius * (cutIntensity > 0.1F ? 0.145F : 0.185F);
      float fieldFade = fieldVisible ? fade : 0.0F;
      java.util.function.Consumer<ShaderInstance> fractureUniforms = shader -> {
         shader.safeGetUniform("Sphere").set(c.x, c.y, c.z, this.radius);
         shader.safeGetUniform("Phase").set(reveal, collapse, fieldFade, this.seedFloat);
         shader.safeGetUniform("Shock").set(shockRadius, shockStrength, Math.max(0.9F, this.radius * 0.085F), coreRadius);
         shader.safeGetUniform("Extra").set(Math.max(0.0F, flash), voidRatio, cellSize, cutIntensity);
      };
      out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.fractureScene, fractureUniforms));
      out.add(new FxPass(FxPass.Kind.VOLUME, () -> FxShaders.fractureVolume, fractureUniforms));

      // 通道 2：域内局部斩痕切裂 + 终极爆轰全屏 4 线齐斩错位撕裂
      this.collectSlicePass(frame, out, a);
   }

   private void collectSlicePass(FxFrame frame, List<FxPass> out, float a) {
      if (FxShaders.sliceScene == null) {
         return;
      }
      float att = this.blastAttenuation(frame);
      if (att <= 0.02F) {
         return;
      }
      List<SliceLine> lines = new ArrayList<>();
      boolean fullScreenPhase = a >= COLLAPSE_START + 5.0F && a < DETONATE + 10.0F;
      if (fullScreenPhase) {
         float slip;
         float seam;
         if (a < DETONATE) {
            float build = FxMath.easeInCubic(FxMath.window(a, COLLAPSE_START + 5.0F, DETONATE));
            slip = 0.022F * build;
            seam = 0.72F * build;
         } else {
            float snap = 1.0F - FxMath.window(a, DETONATE, DETONATE + 9.5F);
            slip = 0.062F * snap * snap;
            seam = 1.12F * snap;
         }
         for (CutScar scar : this.cutScars) {
            if (scar.major && a >= scar.spawn) {
               addSlice(frame, lines, scar, slip * scar.slipSign * att, 0.0038F, 1.0F, seam * att);
            }
         }
      }
      if (a >= STORM_START && a < DETONATE + 2.0F) {
         for (CutScar scar : this.cutScars) {
            if (scar.major && fullScreenPhase) {
               continue;
            }
            float t = a - scar.spawn;
            if (t < 0.0F || t > 18.0F) {
               continue;
            }
            float pulse = t < 3.5F ? 1.0F - t / 3.5F * 0.45F : (1.0F - FxMath.window(t, 3.5F, 18.0F)) * 0.55F;
            addSlice(frame, lines, scar, 0.016F * pulse * scar.slipSign * att, 0.0026F, 0.0F, 0.65F * pulse * att);
         }
      }
      if (lines.isEmpty()) {
         return;
      }
      lines.sort(Comparator.comparingDouble((SliceLine l) -> l.score).reversed());
      int count = Math.min(MAX_SLICES, lines.size());
      float[][] packed = new float[4][16];
      for (int i = 0; i < count; i++) {
         SliceLine line = lines.get(i);
         float[] m = packed[i / 2];
         int base = (i % 2) * 8;
         m[base] = line.ax;
         m[base + 1] = line.ay;
         m[base + 2] = line.bx;
         m[base + 3] = line.by;
         m[base + 4] = line.slip;
         m[base + 5] = line.width;
         m[base + 6] = line.mode;
         m[base + 7] = line.seam;
      }
      Matrix4f cutsA = new Matrix4f().set(packed[0]);
      Matrix4f cutsB = new Matrix4f().set(packed[1]);
      Matrix4f cutsC = new Matrix4f().set(packed[2]);
      Matrix4f cutsD = new Matrix4f().set(packed[3]);
      float gap = fullScreenPhase ? 1.0F : 0.62F;
      out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.sliceScene, shader -> {
         shader.safeGetUniform("CutsA").set(cutsA);
         shader.safeGetUniform("CutsB").set(cutsB);
         shader.safeGetUniform("CutsC").set(cutsC);
         shader.safeGetUniform("CutsD").set(cutsD);
         shader.safeGetUniform("Slice").set((float)count, gap, 1.0F, 0.0F);
      }));
   }

   private static void addSlice(FxFrame frame, List<SliceLine> lines, CutScar scar, float slip, float width, float mode, float seam) {
      float[] uvA = frame.screenUv(scar.a);
      float[] uvB = frame.screenUv(scar.b);
      if (uvA == null || uvB == null) {
         return;
      }
      float dx = uvB[0] - uvA[0];
      float dy = uvB[1] - uvA[1];
      float score = (float)Math.sqrt(dx * dx + dy * dy);
      if (score < 0.015F) {
         return;
      }
      float influence = Math.min(1.0F, score * 3.8F);
      lines.add(new SliceLine(uvA[0], uvA[1], uvB[0], uvB[1], slip * influence, width, mode, seam * influence, score));
   }

   // ───────────────────────────── 屏幕后处理与镜头反馈（全程克制清澈，高潮精准爆裂） ─────────────────────────────

   private float castAttenuation(FxFrame frame) {
      double distance = frame.camera.distanceTo(this.ring1Center);
      return (float)Math.max(0.0, 1.0 - Math.max(0.0, distance - 8.0) / 56.0);
   }

   private float blastAttenuation(FxFrame frame) {
      double distance = frame.camera.distanceTo(this.center);
      double range = Math.max(this.radius * 4.5 + 96.0, this.rayDist * 1.18 + 64.0);
      float att = (float)Math.max(0.0, 1.0 - Math.max(0.0, distance - this.radius) / range);
      // 远距狙击施法者仍保有清晰可感的终极震慑
      if (this.castAttenuation(frame) > 0.35F) {
         att = Math.max(att, 0.50F);
      }
      return att;
   }

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      float a = this.age(frame.partialTick);
      float castAtt = this.castAttenuation(frame);
      float blastAtt = this.blastAttenuation(frame);

      // 1. 蓄能临界吸气（26 ~ 33 tick）：极轻暗角 + FOV 微缩，零白屏
      if (castAtt > 0.01F && a >= 26.0F && a < BEAM_FIRE) {
         float k = FxMath.easeInCubic(FxMath.window(a, 26.0F, BEAM_FIRE));
         screen.addVignette(0x120324, 0.24F * k * castAtt);
         screen.addFovKick(-0.055F * k * castAtt);
      }

      // 2. 光束突射瞬间（33 ~ 41 tick）：强方向感边缘向心速度线 + FOV 轻弹，中心视野 100% 清晰！
      if (castAtt > 0.01F && a >= BEAM_FIRE && a < BEAM_FIRE + 8.0F) {
         float t = (a - BEAM_FIRE) / 8.0F;
         float k = 1.0F - t;
         float[] muzzleUv = frame.screenUv(this.ring2Center);
         screen.addFlash(0xC8ECFF, 0.16F * k * k * castAtt, 0.35F);
         screen.addDashBlur(0.58F * k * castAtt, muzzleUv);
         screen.addAberration(0.55F * k * castAtt);
         float kick = t < 0.25F ? t / 0.25F : 1.0F - FxMath.easeInOutSine((t - 0.25F) / 0.75F);
         screen.addFovKick(0.075F * kick * castAtt);
      }

      if (blastAtt <= 0.01F) {
         return;
      }
      float[] blastUv = frame.screenUv(this.impactCenter);

      // 3. 光束命中目标（39 ~ 45 tick）：短促清脆的裂界反馈
      if (a >= BEAM_HIT && a < BEAM_HIT + 6.0F) {
         float k = 1.0F - (a - BEAM_HIT) / 6.0F;
         screen.addFlash(0xDEC8FF, 0.26F * k * k * blastAtt, 0.45F);
         screen.addAberration(0.65F * k * blastAtt);
         screen.addDashBlur(0.45F * k * blastAtt, blastUv);
      }

      // 4. 奇点黑洞坍缩吸气（66 ~ 82 tick）：深紫压暗、去色、视野收缩，将压迫感推至顶点
      if (a >= COLLAPSE_START && a < DETONATE) {
         float k = this.collapse(a);
         screen.addVignette(0x16042C, 0.56F * k * blastAtt);
         screen.addDesaturate(0.44F * k * blastAtt);
         screen.addDashBlur(0.46F * k * blastAtt, blastUv);
         screen.addAberration(0.50F * k * blastAtt);
         screen.addFovKick(-0.09F * k * blastAtt);
      }

      // 5. 终极绚烂超新星爆炸（82 tick 起）：2.5 tick 反相冲击帧 → 细腻过曝爆闪 → 中心高清的强速度线与广域泛光
      if (a >= DETONATE) {
         float t = a - DETONATE;
         if (t < 2.5F) {
            screen.addImpactFrame(blastAtt, t >= 1.0F && t < 2.0F);
         }
         // 冲击帧之后再给 6.5 tick 短促颗粒爆闪，让多壳层星云球与通天光柱清晰绽放不糊白
         float flash = t < 2.5F ? 0.0F : Math.max(0.0F, 1.0F - (t - 2.5F) / 6.5F);
         screen.addFlash(0xF6E6FF, 0.72F * flash * flash * blastAtt, 0.95F);
         screen.addAberration(1.38F * Math.max(0.0F, 1.0F - t / 15.0F) * blastAtt);
         screen.addDashBlur(1.18F * Math.max(0.0F, 1.0F - t / 13.0F) * blastAtt, blastUv);
         screen.addBloomBoost(1.25F * Math.max(0.0F, 1.0F - t / 12.0F) * blastAtt);
         float kick = t < 2.2F ? FxMath.easeOutCubic(t / 2.2F) : 1.0F - FxMath.easeInOutSine(FxMath.window(t, 2.2F, 15.0F));
         screen.addFovKick(0.16F * kick * blastAtt);
      }
   }

   @Override
   public float shake(FxFrame frame) {
      float a = this.age(frame.partialTick);
      float castAtt = this.castAttenuation(frame);
      float blastAtt = this.blastAttenuation(frame);
      float value = 0.0F;
      // 发炮后坐力轻震
      if (a >= BEAM_FIRE && a < BEAM_FIRE + 9.0F) {
         value = Math.max(value, 0.42F * (1.0F - (a - BEAM_FIRE) / 9.0F) * castAtt);
      }
      // 光束命中震颤
      if (a >= BEAM_HIT && a < BEAM_HIT + 8.0F) {
         value = Math.max(value, 0.48F * (1.0F - (a - BEAM_HIT) / 8.0F) * blastAtt);
      }
      // 奇点坍缩低频震动
      if (a >= COLLAPSE_START && a < DETONATE) {
         value = Math.max(value, 0.26F * this.collapse(a) * blastAtt);
      }
      // 终极爆轰强震
      if (a >= DETONATE && a < DETONATE + SHOCK_DURATION) {
         value = Math.max(value, 0.95F * (1.0F - FxMath.window(a, DETONATE, DETONATE + SHOCK_DURATION)) * blastAtt);
      }
      return value;
   }

   // ───────────────────────────── 数据结构 ─────────────────────────────

   private record SliceLine(float ax, float ay, float bx, float by, float slip, float width, float mode, float seam, float score) {
   }

   private record DomainArc(Vector3f u, Vector3f v, float innerR, float outerR, float startAngle, float endAngle, float spawn, float duration, float[] color) {
   }

   private static final class CutScar {
      final Vec3 a;
      final Vec3 b;
      final float spawn;
      final boolean major;
      final float slipSign;
      final float weight;

      CutScar(Vec3 a, Vec3 b, float spawn, boolean major, float slipSign, float weight) {
         this.a = a;
         this.b = b;
         this.spawn = spawn;
         this.major = major;
         this.slipSign = slipSign;
         this.weight = weight;
      }
   }

   private static final class MirrorShard {
      final Vec3 origin;
      final Vector3f outward;
      final Vector3f axisU;
      final Vector3f axisV;
      final float spawn;
      final float size;
      final float speed;
      final float spin;
      final float[] color;

      MirrorShard(Vec3 origin, Vector3f outward, Vector3f axisU, Vector3f axisV, float spawn, float size, float speed, float spin, float[] color) {
         this.origin = origin;
         this.outward = outward;
         this.axisU = axisU;
         this.axisV = axisV;
         this.spawn = spawn;
         this.size = size;
         this.speed = speed;
         this.spin = spin;
         this.color = color;
      }
   }

   private static final class CrystalShard {
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
      final float size;
      final float[] color;
      final float phase;
      final float spinSpeed;

      CrystalShard(Vec3 origin, double vx, double vy, double vz, int life, float size, float[] color, float phase, float spinSpeed) {
         this.x = this.px = origin.x;
         this.y = this.py = origin.y;
         this.z = this.pz = origin.z;
         this.vx = vx;
         this.vy = vy;
         this.vz = vz;
         this.life = life;
         this.maxLife = life;
         this.size = size;
         this.color = color;
         this.phase = phase;
         this.spinSpeed = spinSpeed;
      }

      void tick() {
         this.px = this.x;
         this.py = this.y;
         this.pz = this.z;
         this.x += this.vx;
         this.y += this.vy;
         this.z += this.vz;
         // 爆炸初段高速喷涌，迅速阻尼减速转入电影级慢动作悬停飘升
         this.vx *= 0.85;
         this.vy = this.vy * 0.85 + 0.016;
         this.vz *= 0.85;
         this.life--;
      }

      Vec3 position(float partialTick) {
         return new Vec3(this.px + (this.x - this.px) * partialTick, this.py + (this.y - this.py) * partialTick, this.pz + (this.z - this.pz) * partialTick);
      }
   }

   private static final class Slash {
      final Vec3 start;
      final Vec3 end;
      final Vec3 bulge;
      final float spawn;
      final float width;
      final boolean golden;
      boolean sparked;

      Slash(Vec3 start, Vec3 end, Vec3 bulge, float spawn, float width, boolean golden) {
         this.start = start;
         this.end = end;
         this.bulge = bulge;
         this.spawn = spawn;
         this.width = width;
         this.golden = golden;
      }

      Vec3 point(double u) {
         Vec3 control = this.start.add(this.end).scale(0.5).add(this.bulge.scale(2.0));
         double inv = 1.0 - u;
         return this.start.scale(inv * inv).add(control.scale(2.0 * inv * u)).add(this.end.scale(u * u));
      }
   }

   private static final class Spark {
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
      final float[] color;
      final float accelY;
      final double drag;

      Spark(Vec3 origin, Vector3f velocity, int life, float[] color, float accelY, double drag) {
         this.x = this.px = origin.x;
         this.y = this.py = origin.y;
         this.z = this.pz = origin.z;
         this.vx = velocity.x;
         this.vy = velocity.y;
         this.vz = velocity.z;
         this.life = life;
         this.maxLife = life;
         this.color = color;
         this.accelY = accelY;
         this.drag = drag;
      }

      static Spark burst(Vec3 origin, RandomSource random, float speed, int life, float[] color, float accelY) {
         return new Spark(origin, FxMath.randomUnit(random).mul(speed), life, color, accelY, 0.88);
      }

      void tick() {
         this.px = this.x;
         this.py = this.y;
         this.pz = this.z;
         this.x += this.vx;
         this.y += this.vy;
         this.z += this.vz;
         this.vx *= this.drag;
         this.vy = this.vy * this.drag + this.accelY;
         this.vz *= this.drag;
         this.life--;
      }

      Vec3 position(float partialTick) {
         return new Vec3(this.px + (this.x - this.px) * partialTick, this.py + (this.y - this.py) * partialTick, this.pz + (this.z - this.pz) * partialTick);
      }
   }
}
