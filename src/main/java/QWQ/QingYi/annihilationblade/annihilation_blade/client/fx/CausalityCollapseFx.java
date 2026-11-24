package QWQ.QingYi.annihilationblade.annihilation_blade.client.fx;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.CausalityCollapsePattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.core.FxInstance;
import QWQ.QingYi.annihilationblade.render.client.core.FxPass;
import QWQ.QingYi.annihilationblade.render.client.core.FxScreenState;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry.Style;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxShaders;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 湮灭之刃 SE「因果坍缩·时空裂痕」客户端池化多裂痕渲染器。
 * <p>
 * 视觉母题与「裂界·万花筒神威」一脉相承（漆黑虚空视界 × 深虚空紫 × 裂界品红 × 兰粉白热锋芒 × 强虹彩色散旋涡）：
 * <ul>
 *   <li>承接裂界拧杀吸穿奇点后的空间残余撕裂，在每个敌人原位留下一道持续约 3.2 秒的局部神威旋涡裂痕；</li>
 *   <li>平时挥刀（含对空）亦可在视野前方斜劈出单道同源时空裂痕；</li>
 *   <li>全剧场通过 {@link #createOrAbsorb} 池化为单个活跃实例（最多并存 72 道），单帧恒定 1 次 {@code fx_fold_scene}（16 处）+ 1 次 {@code fx_fold_volume}（8 处）。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class CausalityCollapseFx extends FxInstance {
   private static final int MAX_POOLED_SCARS = 72;
   private static final int MAX_SCENE_SCARS = 16;
   private static final int MAX_VOLUME_SCARS = 8;
   private static final int JAG_SEGMENTS = 44;
   private static final int SPIRAL_ARMS = 5;
   private static final int SPIRAL_STEPS = 32;
   private static final int RING_SEGMENTS = 48;
   private static final int SHARDS_PER_SCAR = 6;
   private static final int STREAKS_PER_SCAR = 10;

   // 与 WorldRiftFx 同源高反差神威色盘 + 极细冰青棱镜微芒点缀
   private static final float[] VOID_BLACK = {0.008F, 0.003F, 0.018F};
   private static final float[] DEEP_BODY = {0.045F, 0.008F, 0.085F};
   private static final float[] DEEP_VIOLET = {0.40F, 0.11F, 0.90F};
   private static final float[] VIOLET = {0.60F, 0.22F, 1.00F};
   private static final float[] MAGENTA = {0.94F, 0.20F, 0.96F};
   private static final float[] HOT_ORCHID = {1.00F, 0.68F, 0.98F};
   private static final float[] WHITE_HOT = {1.00F, 0.95F, 1.00F};
   private static final float[] PRISM_CYAN = {0.34F, 0.90F, 1.00F};

   private static CausalityCollapseFx activePool;
   private static ClientLevel activePoolLevel;

   private final List<ScarNode> scars = new ArrayList<>();
   private Vec3 focusPoint;
   private int openSoundThrottle;
   private int closeSoundThrottle;

   private CausalityCollapseFx(ClientLevel level, FxEventPacket packet) {
      super(CausalityCollapsePattern.DEFAULT_SCAR_DURATION + 4);
      this.focusPoint = packet.origin();
      this.absorbPacket(packet);
   }

   /**
    * 池化工厂入口：若当前世界已有活跃因果裂痕池，则直接并入新裂痕并顺延寿命，返回 {@code null} 避免重复入列。
    */
   public static FxInstance createOrAbsorb(ClientLevel level, FxEventPacket packet) {
      if (activePool != null && activePoolLevel == level && !activePool.finished()) {
         activePool.absorbPacket(packet);
         return null;
      }
      CausalityCollapseFx created = new CausalityCollapseFx(level, packet);
      activePool = created;
      activePoolLevel = level;
      return created;
   }

   @Override
   public void onRemoved() {
      if (activePool == this) {
         activePool = null;
         activePoolLevel = null;
      }
   }

   private void absorbPacket(FxEventPacket packet) {
      Vec3 origin = packet.origin();
      Vec3 lookHint = packet.direction();
      if (lookHint.lengthSqr() < 1.0E-6) {
         lookHint = new Vec3(0.0, 0.0, 1.0);
      } else {
         lookHint = lookHint.normalize();
      }
      float visualScale = Mth.clamp(packet.scale(), 0.45F, 2.5F);
      int mode = AnnihilationFxEvents.causalityMode(packet.intParam());
      int duration = AnnihilationFxEvents.causalityScarDuration(packet.intParam());
      boolean standalone = mode == AnnihilationFxEvents.CAUSALITY_MODE_STANDALONE;
      int[] packedXZ = packet.entityIds();
      int[] packedYMeta = packet.targetTicks();
      int count = Math.min(packedXZ.length, packedYMeta.length);
      RandomSource random = RandomSource.create(packet.seed());

      if (count <= 0) {
         this.addScar(origin, 2.0F, 0, duration, visualScale, lookHint, standalone, random);
         return;
      }

      for (int i = 0; i < count; i++) {
         Vec3 pos = CausalityCollapsePattern.unpackPos(origin, packedXZ[i], packedYMeta[i]);
         float entityHeight = CausalityCollapsePattern.unpackHeight(packedYMeta[i]);
         int stagger = CausalityCollapsePattern.unpackStagger(packedYMeta[i]);
         this.addScar(pos, entityHeight, stagger, duration, visualScale, lookHint, standalone, random);
      }
      this.focusPoint = CausalityCollapsePattern.unpackPos(origin, packedXZ[0], packedYMeta[0]);
   }

   private void addScar(
      Vec3 pos, float entityHeight, int stagger, int duration, float visualScale,
      Vec3 lookHint, boolean standalone, RandomSource random
   ) {
      while (this.scars.size() >= MAX_POOLED_SCARS) {
         this.scars.remove(0);
      }
      int startAge = this.age + Math.max(0, stagger);
      float halfHeight = standalone
         ? Mth.clamp(entityHeight * 0.56F, 0.95F, 1.55F) * visualScale
         : Mth.clamp(entityHeight * 0.66F, 0.90F, 1.95F) * visualScale;

      // 独立挥刀采用利落的斜劈角（±22°~48°）；裂界余威保留竖向偏斜（±12°~30°）呼应被斩灭身位
      float yaw = (float)Math.atan2(lookHint.z, lookHint.x) + (random.nextFloat() - 0.5F) * (standalone ? 0.55F : 1.45F);
      float leanMag = standalone ? (0.40F + random.nextFloat() * 0.52F) : (0.20F + random.nextFloat() * 0.32F);
      float leanSign = random.nextBoolean() ? 1.0F : -1.0F;
      Vector3f horiz = new Vector3f((float)Math.cos(yaw), 0.0F, (float)Math.sin(yaw));
      Vector3f side = new Vector3f(-horiz.z, 0.0F, horiz.x);
      Vector3f spine = new Vector3f(0.0F, 1.0F, 0.0F)
         .fma(leanMag * leanSign, side)
         .fma((random.nextFloat() - 0.5F) * 0.24F, horiz)
         .normalize();

      Vector3f faceNormal = new Vector3f(horiz).fma(-horiz.dot(spine), spine);
      if (faceNormal.lengthSquared() < 1.0E-4F) {
         faceNormal.set(FxMath.perpendicular(spine));
      } else {
         faceNormal.normalize();
      }
      Vector3f across = new Vector3f(faceNormal).cross(spine).normalize();

      // 较强斜倾的时空约束环法向（在第一人称下呈扁平透视椭圆而非呆板正圆）
      Vector3f ringNormal = new Vector3f(spine)
         .mul(0.82F)
         .fma(0.55F * (random.nextBoolean() ? 1.0F : -1.0F), across)
         .fma(0.32F * (random.nextFloat() - 0.5F), faceNormal)
         .normalize();

      float seedPhase = random.nextFloat() * 62.83F;
      float spinSign = random.nextBoolean() ? 1.0F : -1.0F;
      float twistBase = (11.5F + random.nextFloat() * 3.5F) * (0.90F + 0.10F * visualScale);
      ScarNode node = new ScarNode(
         pos, halfHeight, visualScale, spine, faceNormal, across, ringNormal,
         startAge, duration, seedPhase, spinSign, twistBase, random
      );
      this.scars.add(node);
      this.extendLife(Math.max(1, (startAge + duration + 2) - this.age));
   }

   @Override
   public Vec3 focus() {
      // 池化实例以相机自身位置为池级焦点，条目在各自绘制通道内按 160 格独立剔除，防止远近裂痕互相误杀
      if (!this.scars.isEmpty()) {
         Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
         if (camera != null) {
            return camera.getPosition();
         }
      }
      return this.focusPoint;
   }

   @Override
   public double cullDistance() {
      return 256.0;
   }

   // ───────────────────────────── 时间轴与音效 ─────────────────────────────

   @Override
   public void tick(ClientLevel level) {
      int age = this.ageTicks();
      this.openSoundThrottle = 0;
      this.closeSoundThrottle = 0;

      Iterator<ScarNode> iterator = this.scars.iterator();
      while (iterator.hasNext()) {
         ScarNode scar = iterator.next();
         int local = age - scar.startAge;
         if (local > scar.duration) {
            iterator.remove();
            continue;
         }
         if (local < 0) {
            continue;
         }
         if (local == 0 && !scar.openSoundPlayed) {
            scar.openSoundPlayed = true;
            if (this.openSoundThrottle < 3) {
               this.openSoundThrottle++;
               float pitchJitter = (scar.seedPhase % 1.0F) * 0.16F;
               play(level, scar.center, SoundEvents.GLASS_BREAK, 0.56F, 1.32F + pitchJitter);
               play(level, scar.center, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.58F, 1.15F + pitchJitter);
               if (this.openSoundThrottle == 1) {
                  play(level, scar.center, SoundEvents.CHORUS_FRUIT_TELEPORT, 0.48F, 1.38F);
               }
            }
         }
         int closeClickTick = Math.max(CausalityCollapsePattern.OPEN_TICKS + 4, scar.duration - 4);
         if (local == closeClickTick && !scar.closeSoundPlayed) {
            scar.closeSoundPlayed = true;
            if (this.closeSoundThrottle < 2) {
               this.closeSoundThrottle++;
               play(level, scar.center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.45F, 1.78F + (scar.seedPhase % 0.18F));
               play(level, scar.center, SoundEvents.SCULK_CATALYST_BLOOM, 0.34F, 1.75F);
            }
         }
      }
      if (!this.scars.isEmpty()) {
         this.focusPoint = this.scars.get(this.scars.size() - 1).center;
      }
   }

   private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
      level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int age = this.ageTicks();
      for (ScarNode scar : this.scars) {
         int local = age - scar.startAge;
         if (local < 0 || local > scar.duration) {
            continue;
         }
         if (local == 0 || local % 6 == 0) {
            for (int k = -2; k <= 2; k++) {
               double along = k * 0.36 * scar.halfHeight;
               level.addParticle(
                  local == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.PORTAL,
                  scar.center.x + scar.spine.x * along,
                  scar.center.y + scar.spine.y * along,
                  scar.center.z + scar.spine.z * along,
                  0.0, 0.02, 0.0
               );
            }
         }
      }
   }

   // ───────────────────────────── 单裂痕动画包络 ─────────────────────────────

   /** 时空涡旋扭角强度曲线：骤裂瞬间过冲爆拧（1.30x），滞留期缓流呼吸，尾声平滑阻尼退旋归零。 */
   private static float swirlCurve(float local, int duration, float seed) {
      if (local < 0.0F || local > duration) {
         return 0.0F;
      }
      float openEnd = CausalityCollapsePattern.OPEN_TICKS;
      float closeStart = Math.max(openEnd + 4.0F, duration - CausalityCollapsePattern.CLOSE_TICKS);
      if (local < openEnd) {
         float x = FxMath.clamp01(local / openEnd);
         return FxMath.easeOutCubic(x) * (1.0F + 0.30F * (float)Math.sin(Math.PI * x));
      }
      if (local <= closeStart) {
         return 0.90F + 0.08F * (float)Math.sin(local * 0.24F + seed);
      }
      float closeT = FxMath.clamp01((local - closeStart) / Math.max(1.0F, duration - closeStart));
      return 0.90F * (1.0F - FxMath.easeInOutSine(closeT));
   }

   /** 漆黑视界暗缝张开度：0~3t 猛裂，滞留期稳定微呼吸，尾声平滑收拢合缝。 */
   private static float crackOpenCurve(float local, int duration, float seed) {
      if (local < 0.0F || local > duration) {
         return 0.0F;
      }
      float open = FxMath.easeOutCubic(FxMath.window(local, 0.0F, 3.0F));
      float closeStart = Math.max(8.0F, duration - CausalityCollapsePattern.CLOSE_TICKS);
      float close = 1.0F - FxMath.easeInCubic(FxMath.window(local, closeStart, duration - 1.2F));
      float tremble = 0.93F + 0.07F * (float)Math.sin(local * 0.52F + seed);
      return open * close * tremble;
   }

   /** 裂痕纵向长度伸缩（合缝最后几帧自上下两端向中点拉紧收束）。 */
   private static float lengthScale(float local, int duration) {
      float open = FxMath.easeOutCubic(FxMath.window(local, 0.0F, 2.5F));
      float closeStart = Math.max(8.0F, duration - CausalityCollapsePattern.CLOSE_TICKS + 4.0F);
      float close = 1.0F - FxMath.easeInCubic(FxMath.window(local, closeStart, duration));
      return open * close;
   }

   private static float openFlashCurve(float local) {
      if (local < 0.0F || local > 5.0F) {
         return 0.0F;
      }
      return FxMath.envelope(local / 5.0F, 0.16F, 0.36F);
   }

   private static float alignGlintCurve(float local, int duration) {
      float start = duration - 4.8F;
      float end = duration - 0.3F;
      if (local < start || local > end) {
         return 0.0F;
      }
      return FxMath.envelope((local - start) / Math.max(1.0F, end - start), 0.35F, 0.55F);
   }

   // ───────────────────────────── 预乘 Alpha 本体层（锁步弯曲暗缝 + 中心奇点瞳孔 + 暗晶核） ─────────────────────────────

   @Override
   public boolean hasBody() {
      return true;
   }

   @Override
   public void renderBody(FxGeometry b, FxFrame frame) {
      if (this.scars.isEmpty()) {
         return;
      }
      float t = this.age(frame.partialTick);

      // 1. 严格沿 jagSample(u) 弯曲的双交叉 3D 暗缝片（彻底根除直轴 VOID_SLIT 错位问题）
      boolean cavityStarted = false;
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(local, scar.duration, scar.seedPhase);
         float lenScale = lengthScale(local, scar.duration);
         if (crack <= 0.01F || lenScale <= 0.01F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 160.0F * 160.0F) {
            continue;
         }
         if (!cavityStarted) {
            // p3 = -0.05 确保 fx_world.fsh 的 smoothstep(dissolve±0.02) 恒为 1.0，不出现 Worley 孔洞
            b.begin(Style.AFTERIMAGE, 0.70F, 0.12F, 0.45F, 0.88F, 0.0F, -0.05F);
            cavityStarted = true;
         }
         float halfH = scar.halfHeight * lenScale;
         float halfW = (0.045F + 0.080F * crack) * scar.visualScale * lenScale;
         this.emitCurvedAbyssStrip(b, scar, c, halfH, halfW, scar.across, 0.96F * crack);
         this.emitCurvedAbyssStrip(b, scar, c, halfH * 0.94F, halfW * 0.58F, scar.faceNormal, 0.80F * crack);
      }
      if (cavityStarted) {
         b.end();
      }

      // 2. 裂痕喉心漆黑奇点视界核（CORONA 日蚀黑核：赋予裂痕"吞光黑洞残口"的立体深度）
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(local, scar.duration, scar.seedPhase);
         float lenScale = lengthScale(local, scar.duration);
         if (crack <= 0.04F || lenScale <= 0.04F) {
            continue;
         }
         Vector3f baseC = frame.rel(scar.center);
         if (baseC.lengthSquared() > 120.0F * 120.0F) {
            continue;
         }
         float halfH = scar.halfHeight * lenScale;
         Vector3f eyePos = new Vector3f(baseC).fma(scar.jagSample(0.0F) * halfH, scar.across);
         float holeR = (0.12F + 0.11F * crack) * scar.visualScale * lenScale;
         if (holeR <= 0.025F) {
            continue;
         }
         b.geoCenter(eyePos);
         b.begin(Style.CORONA, 0.22F, 0.0F, 2.5F, 1.3F, 1.6F * scar.spinSign, 0.50F);
         b.deformedSphere(eyePos, holeR, 8, 12, t * 0.25F * scar.spinSign, 0.05F, 2.2F, t * 0.3F, VOID_BLACK[0], VOID_BLACK[1], VOID_BLACK[2], 0.94F * crack);
         b.end();
         b.clearGeoCenter();
      }

      // 3. 裂隙旁悬浮旋转的 3D 玄黑晶核碎刃
      boolean shardStarted = false;
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(local, scar.duration, scar.seedPhase);
         float swirl = swirlCurve(local, scar.duration, scar.seedPhase);
         if (crack <= 0.02F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         if (!shardStarted) {
            b.begin(Style.CRYSTAL_SHARD, 0.36F, 0.05F, 2.2F, 1.5F, 0.35F, 0.0F);
            shardStarted = true;
         }
         this.emitScarShards(b, scar, c, local, crack, swirl, true);
      }
      if (shardStarted) {
         b.end();
      }
   }

   private void emitCurvedAbyssStrip(
      FxGeometry b, ScarNode scar, Vector3f center, float halfH, float halfW, Vector3f widthAxis, float alpha
   ) {
      Vector3f prevL = null;
      Vector3f prevR = null;
      for (int i = 0; i <= JAG_SEGMENTS; i++) {
         float u = (float)i / JAG_SEGMENTS * 2.0F - 1.0F;
         float taper = (float)Math.pow(Math.max(0.0F, 1.0F - u * u), 0.72);
         float jag = scar.jagSample(u) * halfH * taper;
         Vector3f mid = new Vector3f(center)
            .fma(u * halfH, scar.spine)
            .fma(jag, scar.across);
         float w = halfW * taper;
         Vector3f curL = new Vector3f(mid).fma(-w, widthAxis);
         Vector3f curR = new Vector3f(mid).fma(w, widthAxis);
         if (prevL != null) {
            b.rawQuad(prevL, prevR, curR, curL, VOID_BLACK[0], VOID_BLACK[1], VOID_BLACK[2], alpha);
         }
         prevL = curL;
         prevR = curR;
      }
   }

   // ───────────────────────────── HDR 网格发光层（品红/兰粉裂口锋刃 × 螺旋空间丝 × 倾斜约束环 × 吸积流丝） ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      if (this.scars.isEmpty()) {
         return;
      }
      float t = this.age(frame.partialTick);

      // 1. 锯齿双唇品红主锋 + 围绕裂隙的 4 条裂界同源螺旋旋臂 + 倾斜空间约束环（外圈品红/虚空紫层）
      g.begin(Style.RIBBON, 4.8F, 0.14F, 5.8F, 5.5F, 13.0F, 0.52F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(local, scar.duration, scar.seedPhase);
         float swirl = swirlCurve(local, scar.duration, scar.seedPhase);
         float lenScale = lengthScale(local, scar.duration);
         if ((crack <= 0.01F && swirl <= 0.01F) || lenScale <= 0.01F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 160.0F * 160.0F) {
            continue;
         }

         float halfH = scar.halfHeight * lenScale;
         float lipSep = (0.024F + 0.048F * crack) * scar.visualScale * lenScale;
         float flashBoost = 1.0F + 0.85F * openFlashCurve(local);

         // 左右锯齿裂口主刃（裂界品红锋芒）
         if (crack > 0.01F) {
            this.drawJaggedLip(g, scar, c, halfH, lipSep, 1.0F, 0.024F * scar.visualScale, MAGENTA, 0.88F * crack * flashBoost);
            this.drawJaggedLip(g, scar, c, halfH, lipSep, -1.0F, 0.024F * scar.visualScale, MAGENTA, 0.88F * crack * flashBoost);
         }

         // 5 条 3D 顺滑螺旋空间丝（外层品红/虚空紫）
         if (swirl > 0.02F) {
            this.drawScarSpiralArms(g, scar, c, halfH, local, crack, swirl, false);
            this.drawScarOrbitRing(g, scar, c, local, crack, swirl, false);
         }
      }
      g.end();

      // 2. 兰粉白热内锋芯 + 螺旋细白芯 + 约束环亮芯（复刻图中明亮紫白拉丝核心）
      g.begin(Style.RIBBON, 8.2F, 0.10F, 9.8F, 0.0F, 0.0F, 0.24F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(local, scar.duration, scar.seedPhase);
         float swirl = swirlCurve(local, scar.duration, scar.seedPhase);
         float lenScale = lengthScale(local, scar.duration);
         if ((crack <= 0.01F && swirl <= 0.01F) || lenScale <= 0.01F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 160.0F * 160.0F) {
            continue;
         }
         float halfH = scar.halfHeight * lenScale;
         float lipSep = (0.020F + 0.038F * crack) * scar.visualScale * lenScale;
         float flashBoost = 1.0F + 0.85F * openFlashCurve(local);
         if (crack > 0.01F) {
            this.drawJaggedLip(g, scar, c, halfH, lipSep, 1.0F, 0.009F * scar.visualScale, HOT_ORCHID, 0.82F * crack * flashBoost);
            this.drawJaggedLip(g, scar, c, halfH, lipSep, -1.0F, 0.009F * scar.visualScale, HOT_ORCHID, 0.82F * crack * flashBoost);
         }
         if (swirl > 0.02F) {
            this.drawScarSpiralArms(g, scar, c, halfH, local, crack, swirl, true);
            this.drawScarOrbitRing(g, scar, c, local, crack, swirl, true);
         }
      }
      g.end();

      // 3. 沿螺旋被持续抽进裂口的空间尘埃流丝（streak）
      g.begin(Style.RIBBON, 6.2F, 0.12F, 6.5F, 0.0F, 0.0F, 0.30F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float swirl = swirlCurve(local, scar.duration, scar.seedPhase);
         if (swirl <= 0.02F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 120.0F * 120.0F) {
            continue;
         }
         this.drawScarStreaks(g, scar, c, local, swirl);
      }
      g.end();

      // 4. 纤锐 3D 棱晶空间碎刃（克制亮度防止泛光糊成白块，突出玄黑晶核与冷冽棱边）
      g.begin(Style.CRYSTAL_SHARD, 2.6F, 0.05F, 2.6F, 2.0F, 0.28F, 0.0F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(local, scar.duration, scar.seedPhase);
         float swirl = swirlCurve(local, scar.duration, scar.seedPhase);
         if (crack <= 0.02F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         this.emitScarShards(g, scar, c, local, crack, swirl, false);
      }
      g.end();

      // 5. 初裂空间涟漪环（0..7t）
      g.begin(Style.RING, 4.6F, 0.10F, 0.85F, 0.020F, 0.0F, 0.0F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > 7.0F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         float rk = local / 7.0F;
         float ringAlpha = (1.0F - rk) * (1.0F - rk) * 0.85F;
         float r = (0.28F + 1.15F * FxMath.easeOutCubic(rk)) * scar.visualScale;
         g.quad(c, new Vector3f(scar.across).mul(r), new Vector3f(scar.spine).mul(r), MAGENTA[0], MAGENTA[1], MAGENTA[2], ringAlpha);
      }
      g.end();

      // 6. 初裂兰粉白热耀斑 & 临终合缝针尖星芒
      g.begin(Style.GLOW, 7.8F, 0.0F, 4.2F, 2.4F, 1.05F, 0.0F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float openFlash = openFlashCurve(local);
         float alignGlint = alignGlintCurve(local, scar.duration);
         if (openFlash <= 0.01F && alignGlint <= 0.01F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 160.0F * 160.0F) {
            continue;
         }
         if (openFlash > 0.01F) {
            float sz = (0.48F + 0.56F * openFlash) * scar.visualScale;
            g.billboard(c, sz, 0.0F, HOT_ORCHID[0], HOT_ORCHID[1], HOT_ORCHID[2], 0.88F * openFlash);
            g.billboard(c, sz * 0.65F, (float)(Math.PI * 0.5), WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], 0.85F * openFlash);
         }
         if (alignGlint > 0.01F) {
            float sz = (0.34F + 0.42F * alignGlint) * scar.visualScale;
            g.billboard(c, sz, local * 0.22F, WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], 0.92F * alignGlint);
            g.billboard(c, sz * 0.72F, -local * 0.18F, PRISM_CYAN[0], PRISM_CYAN[1], PRISM_CYAN[2], 0.65F * alignGlint);
         }
      }
      g.end();
   }

   private void drawJaggedLip(
      FxGeometry g, ScarNode scar, Vector3f center, float halfH, float lipSep, float sideSign,
      float baseWidth, float[] color, float alpha
   ) {
      List<Vector3f> pts = new ArrayList<>(JAG_SEGMENTS + 1);
      float[] widths = new float[JAG_SEGMENTS + 1];
      float[] alphas = new float[JAG_SEGMENTS + 1];
      for (int i = 0; i <= JAG_SEGMENTS; i++) {
         float u = (float)i / JAG_SEGMENTS * 2.0F - 1.0F;
         float taper = (float)Math.pow(Math.max(0.0F, 1.0F - u * u), 0.72);
         float jag = scar.jagSample(u) * halfH * taper;
         Vector3f p = new Vector3f(center)
            .fma(u * halfH, scar.spine)
            .fma(jag + sideSign * lipSep * taper, scar.across)
            .fma(sideSign * lipSep * 0.20F * taper, scar.faceNormal);
         pts.add(p);
         widths[i] = baseWidth * (0.28F + 0.72F * taper);
         alphas[i] = alpha * (0.24F + 0.76F * taper);
      }
      g.ribbon(pts, widths, alphas, color[0], color[1], color[2]);
   }

   /** 围绕每道裂痕中轴的 5 条顺滑 3D 对数螺旋空间余威丝（32 段精细采样，消除折线拐角）。 */
   private void drawScarSpiralArms(
      FxGeometry g, ScarNode scar, Vector3f center, float halfH, float local, float crack, float swirl, boolean corePass
   ) {
      float outerR = (0.44F + 0.56F * swirl) * scar.visualScale;
      float innerR = (0.04F + 0.05F * crack) * scar.visualScale;
      float spin = scar.spinSign * (local * 0.28F + scar.seedPhase);
      float wBase = (corePass ? 0.010F : 0.030F) * scar.visualScale;
      wBase = Math.max(wBase, center.length() * (corePass ? 0.0008F : 0.0019F));

      List<Vector3f> pts = new ArrayList<>(SPIRAL_STEPS);
      float[] widths = new float[SPIRAL_STEPS];
      float[] alphas = new float[SPIRAL_STEPS];

      for (int arm = 0; arm < SPIRAL_ARMS; arm++) {
         pts.clear();
         float baseAng = FxMath.TAU * arm / SPIRAL_ARMS + spin;
         float axialSpan = ((arm % 2 == 0) ? 1.0F : -1.0F) * halfH * (0.42F + 0.14F * (arm % 3));
         float[] col = corePass ? WHITE_HOT : (arm % 2 == 0 ? MAGENTA : VIOLET);

         for (int s = 0; s < SPIRAL_STEPS; s++) {
            float k = (float)s / (SPIRAL_STEPS - 1);
            float inv = 1.0F - k;
            float r = FxMath.lerp(outerR, innerR, (float)Math.pow(k, 0.72));
            float ang = baseAng + scar.spinSign * k * (4.2F + 2.0F * swirl);
            float cs = (float)Math.cos(ang);
            float sn = (float)Math.sin(ang);
            float axial = axialSpan * inv * (float)Math.sin(k * Math.PI);
            Vector3f p = new Vector3f(center)
               .fma(r * cs, scar.across)
               .fma(r * sn, scar.faceNormal)
               .fma(axial, scar.spine);
            pts.add(p);

            float taper = (float)Math.sin(Math.PI * k);
            widths[s] = wBase * (0.25F + 0.85F * taper) * (0.45F + 0.55F * inv);
            alphas[s] = swirl * (0.18F + 0.82F * taper) * (corePass ? 0.84F : 0.86F);
         }
         g.ribbon(pts, widths, alphas, col[0], col[1], col[2]);
      }
   }

   /** 环绕裂痕喉心的斜倾时空约束丝环（48 段顺滑透视椭圆 + 非均匀光脉，避免呆板纯圆圈）。 */
   private void drawScarOrbitRing(
      FxGeometry g, ScarNode scar, Vector3f center, float local, float crack, float swirl, boolean corePass
   ) {
      float radius = (0.42F + 0.22F * crack) * scar.visualScale * (0.35F + 0.65F * swirl);
      if (radius <= 0.08F) {
         return;
      }
      Vector3f u = FxMath.perpendicular(scar.ringNormal);
      Vector3f v = new Vector3f(scar.ringNormal).cross(u).normalize();
      float spin = scar.spinSign * local * 0.22F + scar.seedPhase;
      float w = (corePass ? 0.007F : 0.018F) * scar.visualScale;
      w = Math.max(w, center.length() * (corePass ? 0.0006F : 0.0014F));
      float[] col = corePass ? WHITE_HOT : HOT_ORCHID;
      float baseAlpha = swirl * (corePass ? 0.72F : 0.52F);

      List<Vector3f> pts = new ArrayList<>(RING_SEGMENTS + 1);
      float[] widths = new float[RING_SEGMENTS + 1];
      float[] alphas = new float[RING_SEGMENTS + 1];
      for (int i = 0; i <= RING_SEGMENTS; i++) {
         float k = (float)i / RING_SEGMENTS;
         float ang = FxMath.TAU * k + spin;
         Vector3f p = new Vector3f(center)
            .fma(radius * (float)Math.cos(ang), u)
            .fma(radius * (float)Math.sin(ang), v);
         pts.add(p);
         float wave = 0.45F + 0.55F * (float)Math.sin(k * FxMath.TAU + local * 0.35F * scar.spinSign);
         widths[i] = w * (0.55F + 0.65F * wave);
         alphas[i] = baseAlpha * (0.28F + 0.72F * wave);
      }
      g.ribbon(pts, widths, alphas, col[0], col[1], col[2]);
   }

   private void drawScarStreaks(FxGeometry g, ScarNode scar, Vector3f center, float local, float swirl) {
      float maxR = (0.95F + 0.45F * swirl) * scar.visualScale;
      for (int i = 0; i < scar.streakAzimuth.length; i++) {
         float phase = ((local * scar.streakSpeed[i] + scar.streakPhase[i]) % 1.0F + 1.0F) % 1.0F;
         float headP = FxMath.easeInCubic(phase);
         float tailP = FxMath.easeInCubic(Math.max(0.0F, phase - 0.15F));
         float rHead = maxR * (1.0F - headP);
         float rTail = maxR * (1.0F - tailP);
         float angHead = scar.streakAzimuth[i] + scar.spinSign * headP * 3.8F;
         float angTail = scar.streakAzimuth[i] + scar.spinSign * tailP * 3.8F;

         Vector3f head = new Vector3f(center)
            .fma(rHead * (float)Math.cos(angHead), scar.across)
            .fma(rHead * (float)Math.sin(angHead), scar.faceNormal)
            .fma(scar.streakAxial[i] * scar.halfHeight * (1.0F - headP), scar.spine);
         Vector3f tail = new Vector3f(center)
            .fma(rTail * (float)Math.cos(angTail), scar.across)
            .fma(rTail * (float)Math.sin(angTail), scar.faceNormal)
            .fma(scar.streakAxial[i] * scar.halfHeight * (1.0F - tailP), scar.spine);

         float alpha = (float)Math.sin(Math.PI * phase) * swirl * 0.85F;
         float[] col = i % 4 == 0 ? WHITE_HOT : (i % 2 == 0 ? MAGENTA : VIOLET);
         g.streak(head, tail, 0.020F * scar.visualScale, col[0], col[1], col[2], alpha);
      }
   }

   private void emitScarShards(
      FxGeometry g, ScarNode scar, Vector3f center, float local, float crack, float swirl, boolean bodyPass
   ) {
      float pull = 0.25F + 0.75F * swirl;
      for (int i = 0; i < scar.splinters.size(); i++) {
         ScarSplinter sp = scar.splinters.get(i);
         float orbit = sp.phase + scar.spinSign * local * sp.spin;
         float r = sp.radius * pull * scar.visualScale;
         float bob = (float)Math.sin(local * 0.18F + sp.phase) * 0.07F * scar.visualScale;
         Vector3f pos = new Vector3f(center)
            .fma(sp.along * scar.halfHeight * (0.45F + 0.55F * crack), scar.spine)
            .fma(r * (float)Math.cos(orbit), scar.across)
            .fma(r * (float)Math.sin(orbit), scar.faceNormal);
         pos.y += bob;

         Vector3f fwd = new Vector3f(scar.across).mul(-(float)Math.sin(orbit) * scar.spinSign)
            .fma((float)Math.cos(orbit) * scar.spinSign, scar.faceNormal)
            .fma(0.25F, scar.spine)
            .normalize();
         Vector3f right = FxMath.perpendicular(fwd);
         Vector3f up = new Vector3f(fwd).cross(right).normalize();

         float len = sp.length * (0.35F + 0.65F * crack);
         float[] col = bodyPass ? DEEP_BODY : (sp.cyanAccent ? PRISM_CYAN : (i % 2 == 0 ? MAGENTA : VIOLET));
         g.bladeShard(pos, fwd, right, up, len, len * 0.42F, len * 0.13F, len * 0.08F, col[0], col[1], col[2], (bodyPass ? 0.92F : 0.68F) * crack);
      }
   }

   // ───────────────────────────── B 档全屏通道：16 处旋涡扭曲色散 + 8 处 3D 体积纺锤裂壳 ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      if (this.scars.isEmpty()) {
         return;
      }
      float t = this.age(frame.partialTick);
      float aspect = frame.width / Math.max(1.0F, (float)frame.height);

      List<ProjectedScar> visible = new ArrayList<>();
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float swirl = swirlCurve(local, scar.duration, scar.seedPhase);
         float crack = crackOpenCurve(local, scar.duration, scar.seedPhase);
         float lenScale = lengthScale(local, scar.duration);
         if ((swirl <= 0.005F && crack <= 0.005F) || lenScale <= 0.01F) {
            continue;
         }
         Vector3f rel = frame.rel(scar.center);
         float viewDist = rel.length();
         if (viewDist > 160.0F || viewDist < 0.45F) {
            continue;
         }
         float[] uvCenter = frame.screenUv(scar.center);
         if (uvCenter == null || uvCenter[0] < -0.45F || uvCenter[0] > 1.45F || uvCenter[1] < -0.45F || uvCenter[1] > 1.45F) {
            continue;
         }

         float halfH = scar.halfHeight * lenScale;
         Vec3 topWorld = scar.center.add(scar.spine.x * halfH, scar.spine.y * halfH, scar.spine.z * halfH);
         Vec3 botWorld = scar.center.subtract(scar.spine.x * halfH, scar.spine.y * halfH, scar.spine.z * halfH);
         float[] uvTop = frame.screenUv(topWorld);
         float[] uvBot = frame.screenUv(botWorld);

         float tiltAngle = (float)(Math.PI * 0.5);
         float halfLenScreen;
         if (uvTop != null && uvBot != null) {
            float dx = (uvTop[0] - uvBot[0]) * aspect;
            float dy = uvTop[1] - uvBot[1];
            float span = (float)Math.sqrt(dx * dx + dy * dy);
            if (span > 1.0E-4F) {
               tiltAngle = (float)Math.atan2(dy, dx);
               halfLenScreen = span * 0.5F;
            } else {
               halfLenScreen = halfH / Math.max(viewDist, 1.4F) * 0.76F;
            }
         } else {
            halfLenScreen = halfH / Math.max(viewDist, 1.4F) * 0.76F;
         }
         halfLenScreen = Mth.clamp(halfLenScreen, 0.028F, 0.44F);

         // 超出屏幕边界过多且自身柔焦外包圆又摸不到屏幕的裂痕跳过，避免浪费宝贵槽位
         if (Math.abs(uvCenter[0] - 0.5F) > 0.55F + halfLenScreen * 2.15F
               || Math.abs(uvCenter[1] - 0.5F) > 0.55F + halfLenScreen * 2.15F) {
            continue;
         }

         float signedTwist = scar.spinSign * scar.twistBase * swirl;
         float centerBias = 1.0F / (0.22F + (float)Math.hypot(uvCenter[0] - 0.5F, uvCenter[1] - 0.5F));
         float score = (halfLenScreen * 3.0F + swirl + crack * 0.6F) * centerBias / (1.0F + viewDist * 0.04F);
         visible.add(new ProjectedScar(
            scar, rel, viewDist, uvCenter[0], uvCenter[1], halfLenScreen, tiltAngle, signedTwist, crack, swirl,
            halfH, openFlashCurve(local), alignGlintCurve(local, scar.duration), score
         ));
      }

      if (visible.isEmpty()) {
         return;
      }
      visible.sort(Comparator.comparingDouble((ProjectedScar a) -> a.score).reversed());

      // 1. 场景通道：单 Pass 打包最多 16 处裂界同源时空裂痕的旋涡扭转与虹彩色散
      if (FxShaders.foldScene != null) {
         int sceneCount = Math.min(MAX_SCENE_SCARS, visible.size());
         float[][] packed = new float[8][16];
         for (int i = 0; i < sceneCount; i++) {
            ProjectedScar ps = visible.get(i);
            float[] m = packed[i >> 1];
            int base = (i & 1) << 3;
            m[base] = ps.u;
            m[base + 1] = ps.v;
            m[base + 2] = ps.halfLenScreen;
            m[base + 3] = ps.viewDist;
            m[base + 4] = ps.tiltAngle;
            m[base + 5] = ps.signedTwist;
            m[base + 6] = ps.crack;
            m[base + 7] = ps.scar.seedPhase;
         }
         Matrix4f matA = new Matrix4f().set(packed[0]);
         Matrix4f matB = new Matrix4f().set(packed[1]);
         Matrix4f matC = new Matrix4f().set(packed[2]);
         Matrix4f matD = new Matrix4f().set(packed[3]);
         Matrix4f matE = new Matrix4f().set(packed[4]);
         Matrix4f matF = new Matrix4f().set(packed[5]);
         Matrix4f matG = new Matrix4f().set(packed[6]);
         Matrix4f matH = new Matrix4f().set(packed[7]);
         final int finalCount = sceneCount;
         out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.foldScene, shader -> {
            shader.safeGetUniform("RiftA").set(matA);
            shader.safeGetUniform("RiftB").set(matB);
            shader.safeGetUniform("RiftC").set(matC);
            shader.safeGetUniform("RiftD").set(matD);
            shader.safeGetUniform("RiftE").set(matE);
            shader.safeGetUniform("RiftF").set(matF);
            shader.safeGetUniform("RiftG").set(matG);
            shader.safeGetUniform("RiftH").set(matH);
            shader.safeGetUniform("Meta").set((float)finalCount, 1.15F, 1.05F, 1.0F);
         }));
      }

      // 2. 体积通道：单 Pass 打包最多 8 处裂痕的纺锤引力壳 + 锁步 3D 唇面 + 地形截交切痕
      if (FxShaders.foldVolume != null) {
         int volCount = Math.min(MAX_VOLUME_SCARS, visible.size());
         float[][] volPacked = new float[8][16];
         for (int i = 0; i < volCount; i++) {
            ProjectedScar ps = visible.get(i);
            float[] m = volPacked[i];
            m[0] = ps.rel.x;
            m[1] = ps.rel.y;
            m[2] = ps.rel.z;
            m[3] = ps.worldHalfH;
            m[4] = ps.scar.spine.x;
            m[5] = ps.scar.spine.y;
            m[6] = ps.scar.spine.z;
            m[7] = ps.crack;
            m[8] = ps.scar.faceNormal.x;
            m[9] = ps.scar.faceNormal.y;
            m[10] = ps.scar.faceNormal.z;
            m[11] = ps.scar.spinSign * Math.max(0.25F, ps.swirl);
            m[12] = ps.scar.seedPhase;
            m[13] = ps.openFlash;
            m[14] = ps.alignGlint;
            m[15] = 0.0F;
         }
         Matrix4f volA = new Matrix4f().set(volPacked[0]);
         Matrix4f volB = new Matrix4f().set(volPacked[1]);
         Matrix4f volC = new Matrix4f().set(volPacked[2]);
         Matrix4f volD = new Matrix4f().set(volPacked[3]);
         Matrix4f volE = new Matrix4f().set(volPacked[4]);
         Matrix4f volF = new Matrix4f().set(volPacked[5]);
         Matrix4f volG = new Matrix4f().set(volPacked[6]);
         Matrix4f volH = new Matrix4f().set(volPacked[7]);
         final int finalVolCount = volCount;
         out.add(new FxPass(FxPass.Kind.VOLUME, () -> FxShaders.foldVolume, shader -> {
            shader.safeGetUniform("ScarA").set(volA);
            shader.safeGetUniform("ScarB").set(volB);
            shader.safeGetUniform("ScarC").set(volC);
            shader.safeGetUniform("ScarD").set(volD);
            shader.safeGetUniform("ScarE").set(volE);
            shader.safeGetUniform("ScarF").set(volF);
            shader.safeGetUniform("ScarG").set(volG);
            shader.safeGetUniform("ScarH").set(volH);
            shader.safeGetUniform("Meta").set((float)finalVolCount, 1.0F, 0.0F, 0.0F);
         }));
      }
   }

   // ───────────────────────────── 克制的初裂镜头微颤（滞留期零干扰） ─────────────────────────────

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      float t = this.age(frame.partialTick);
      float peakOpen = 0.0F;
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local >= 0.0F && local <= 4.5F) {
            peakOpen = Math.max(peakOpen, openFlashCurve(local));
         }
      }
      if (peakOpen > 0.02F) {
         screen.addAberration(0.26F * peakOpen);
         screen.addFovKick(-0.022F * peakOpen);
      }
   }

   @Override
   public float shake(FxFrame frame) {
      float t = this.age(frame.partialTick);
      float peakOpen = 0.0F;
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local >= 0.0F && local <= 4.5F) {
            peakOpen = Math.max(peakOpen, openFlashCurve(local));
         }
      }
      return 0.22F * peakOpen;
   }

   // ───────────────────────────── 内部数据结构 ─────────────────────────────

   private static final class ScarNode {
      final Vec3 center;
      final float halfHeight;
      final float visualScale;
      final Vector3f spine;
      final Vector3f faceNormal;
      final Vector3f across;
      final Vector3f ringNormal;
      final int startAge;
      final int duration;
      final float seedPhase;
      final float spinSign;
      final float twistBase;
      final float[] streakAzimuth = new float[STREAKS_PER_SCAR];
      final float[] streakAxial = new float[STREAKS_PER_SCAR];
      final float[] streakSpeed = new float[STREAKS_PER_SCAR];
      final float[] streakPhase = new float[STREAKS_PER_SCAR];
      final List<ScarSplinter> splinters = new ArrayList<>(SHARDS_PER_SCAR);
      boolean openSoundPlayed;
      boolean closeSoundPlayed;

      ScarNode(
         Vec3 center, float halfHeight, float visualScale, Vector3f spine, Vector3f faceNormal,
         Vector3f across, Vector3f ringNormal, int startAge, int duration, float seedPhase,
         float spinSign, float twistBase, RandomSource random
      ) {
         this.center = center;
         this.halfHeight = halfHeight;
         this.visualScale = visualScale;
         this.spine = spine;
         this.faceNormal = faceNormal;
         this.across = across;
         this.ringNormal = ringNormal;
         this.startAge = startAge;
         this.duration = duration;
         this.seedPhase = seedPhase;
         this.spinSign = spinSign;
         this.twistBase = twistBase;

         for (int i = 0; i < STREAKS_PER_SCAR; i++) {
            this.streakAzimuth[i] = random.nextFloat() * FxMath.TAU;
            this.streakAxial[i] = (random.nextFloat() - 0.5F) * 1.4F;
            this.streakSpeed[i] = 0.045F + random.nextFloat() * 0.045F;
            this.streakPhase[i] = random.nextFloat();
         }
         for (int i = 0; i < SHARDS_PER_SCAR; i++) {
            float along = (random.nextFloat() * 1.40F - 0.70F);
            float radius = 0.24F + random.nextFloat() * 0.36F;
            float len = (0.10F + random.nextFloat() * 0.11F) * visualScale;
            float spin = 0.05F + random.nextFloat() * 0.07F;
            this.splinters.add(new ScarSplinter(along, radius, len, spin, i == 0 || i == 4, random.nextFloat() * FxMath.TAU));
         }
      }

      /** 与 fx_fold_scene / fx_fold_volume 完全锁步的紧致三频裂隙脊线采样。 */
      float jagSample(float u) {
         return (float)(
            Math.sin(u * 3.8 + this.seedPhase) * 0.042
               + Math.sin(u * 10.5 + this.seedPhase * 2.1) * 0.018
               + Math.cos(u * 21.0 + this.seedPhase * 3.3) * 0.008
         );
      }
   }

   private static final class ScarSplinter {
      final float along;
      final float radius;
      final float length;
      final float spin;
      final boolean cyanAccent;
      final float phase;

      ScarSplinter(float along, float radius, float length, float spin, boolean cyanAccent, float phase) {
         this.along = along;
         this.radius = radius;
         this.length = length;
         this.spin = spin;
         this.cyanAccent = cyanAccent;
         this.phase = phase;
      }
   }

   private record ProjectedScar(
      ScarNode scar, Vector3f rel, float viewDist,
      float u, float v, float halfLenScreen, float tiltAngle, float signedTwist, float crack, float swirl,
      float worldHalfH, float openFlash, float alignGlint, float score
   ) {
   }
}
