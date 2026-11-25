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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 湮灭之刃 SE「因果坍缩·时空裂痕」客户端池化多裂痕渲染器。
 * <p>
 * 视觉母题与「裂界·万花筒神威」一脉相承（漆黑虚空视界 × 深虚空紫 × 裂界品红 × 兰粉白热锋芒 × 强虹彩色散旋涡 × 极细冰青棱镜点缀）：
 * <ul>
 *   <li>承接裂界拧杀吸穿奇点后的空间残余撕裂，在每个敌人原位留下一道持续约 3.2 秒的局部神威旋涡裂痕；</li>
 *   <li>平时挥刀可智能锁敌在目标正中斩裂空间（偏离或无敌时自由斜劈空气）；</li>
 *   <li>当生物被裂痕引力漩涡卷入喉心并触发 {@code CAUSALITY_MODE_DEVOUR} 空间绞杀时，瞬间抓拍其 3D 活体躯壳，
 *       呈现「沿裂隙脊线错位剪断 → 潮汐纵向拉丝 + 横向压扁吸入漆黑狭缝」的专属吞噬演出，并激发裂痕自身的痉挛脉动过载；</li>
 *   <li>全剧场通过 {@link #createOrAbsorb} 池化为单个活跃实例（最多并存 72 道裂痕 + 18 组并发吞噬），单帧恒定 1 次
 *       {@code fx_fold_scene}（16 处）+ 1 次 {@code fx_fold_volume}（8 处）。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class CausalityCollapseFx extends FxInstance {
   private static final int MAX_POOLED_SCARS = 72;
   private static final int MAX_POOLED_DEVOURS = 18;
   private static final int MAX_CAPTURED_DEVOUR_MESHES = 12;
   private static final int MAX_SCENE_SCARS = 16;
   private static final int MAX_VOLUME_SCARS = 8;
   private static final int JAG_SEGMENTS = 44;
   private static final int SPIRAL_ARMS = 5;
   private static final int SPIRAL_STEPS = 32;
   private static final int RING_SEGMENTS = 48;
   private static final int SHARDS_PER_SCAR = 6;
   private static final int STREAKS_PER_SCAR = 10;
   private static final int DEVOUR_SHARDS = 8;
   private static final int DEVOUR_STREAKS = 12;

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
   private final List<DevourBurst> devours = new ArrayList<>();
   private final Vector3f q0 = new Vector3f();
   private final Vector3f q1 = new Vector3f();
   private final Vector3f q2 = new Vector3f();
   private final Vector3f q3 = new Vector3f();

   private Vec3 focusPoint;
   private int openSoundThrottle;
   private int closeSoundThrottle;
   private int devourSoundThrottle;

   private CausalityCollapseFx(ClientLevel level, FxEventPacket packet) {
      super(CausalityCollapsePattern.DEFAULT_SCAR_DURATION + 4);
      this.focusPoint = packet.origin();
      this.absorbPacket(level, packet);
   }

   /**
    * 池化工厂入口：若当前世界已有活跃因果裂痕池，则直接并入新裂痕或吞噬事件并顺延寿命，返回 {@code null} 避免重复入列。
    */
   public static FxInstance createOrAbsorb(ClientLevel level, FxEventPacket packet) {
      if (activePool != null && activePoolLevel == level && !activePool.finished()) {
         activePool.absorbPacket(level, packet);
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

   private void absorbPacket(ClientLevel level, FxEventPacket packet) {
      int mode = AnnihilationFxEvents.causalityMode(packet.intParam());
      if (mode == AnnihilationFxEvents.CAUSALITY_MODE_DEVOUR) {
         this.absorbDevourPacket(level, packet);
         return;
      }

      Vec3 origin = packet.origin();
      Vec3 lookHint = packet.direction();
      if (lookHint.lengthSqr() < 1.0E-6) {
         lookHint = new Vec3(0.0, 0.0, 1.0);
      } else {
         lookHint = lookHint.normalize();
      }
      float visualScale = Mth.clamp(packet.scale(), 0.45F, 2.5F);
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

   /** 接收裂痕核心空间绞杀同步包：立即抓拍活体 3D 躯壳、触发对应裂痕痉挛脉动并播报吞噬演出。 */
   private void absorbDevourPacket(ClientLevel level, FxEventPacket packet) {
      Vec3 scarCenter = packet.origin();
      Vec3 targetCenter = packet.direction();
      if (targetCenter.lengthSqr() < 1.0E-6 && scarCenter.lengthSqr() > 1.0E-6) {
         targetCenter = scarCenter;
      }
      float visualScale = Mth.clamp(packet.scale(), 0.45F, 2.5F);
      float entityWidth = Mth.clamp(packet.radius(), 0.5F, 4.5F);
      int[] entityIds = packet.entityIds();
      int[] heightDecis = packet.targetTicks();
      int entityId = entityIds.length > 0 ? entityIds[0] : -1;
      float entityHeight = heightDecis.length > 0 ? Mth.clamp(heightDecis[0] * 0.1F, 0.7F, 8.0F) : 1.8F;

      // 查找距吞噬点最近的活跃裂痕，对齐其 3D 骨架坐标系并激发痉挛吞噬脉冲
      ScarNode host = this.findHostScar(scarCenter);
      RandomSource random = RandomSource.create(packet.seed() ^ 0x3D91A42BL);
      Vector3f spine;
      Vector3f faceNormal;
      Vector3f across;
      float spinSign;
      float scarHalfH;
      float seedPhase;
      if (host != null) {
         host.triggerDevourPulse(this.age);
         spine = new Vector3f(host.spine);
         faceNormal = new Vector3f(host.faceNormal);
         across = new Vector3f(host.across);
         spinSign = host.spinSign;
         scarHalfH = host.halfHeight;
         seedPhase = host.seedPhase;
      } else {
         float yaw = random.nextFloat() * FxMath.TAU;
         Vector3f horiz = new Vector3f((float)Math.cos(yaw), 0.0F, (float)Math.sin(yaw));
         Vector3f side = new Vector3f(-horiz.z, 0.0F, horiz.x);
         spine = new Vector3f(0.0F, 1.0F, 0.0F).fma((random.nextBoolean() ? 0.38F : -0.38F), side).normalize();
         faceNormal = new Vector3f(horiz).fma(-horiz.dot(spine), spine);
         if (faceNormal.lengthSquared() < 1.0E-4F) {
            faceNormal.set(FxMath.perpendicular(spine));
         } else {
            faceNormal.normalize();
         }
         across = new Vector3f(faceNormal).cross(spine).normalize();
         spinSign = random.nextBoolean() ? 1.0F : -1.0F;
         scarHalfH = Mth.clamp(entityHeight * 0.62F, 0.95F, 1.85F) * visualScale;
         seedPhase = random.nextFloat() * 62.83F;
      }

      // 抓拍即将被静默处决的活体 3D 网格（控制在预算内防止大群怪掉帧）
      float[] capturedQuads = null;
      if (entityId >= 0 && level != null && this.countCapturedDevourMeshes() < MAX_CAPTURED_DEVOUR_MESHES) {
         Entity raw = level.getEntity(entityId);
         if (raw instanceof LivingEntity living) {
            entityHeight = Math.max(0.7F, living.getBbHeight());
            entityWidth = Math.max(0.5F, living.getBbWidth());
            targetCenter = living.position().add(0.0, entityHeight * 0.5, 0.0);
            capturedQuads = AfterimageCapture.captureLiving(living);
         }
      }

      while (this.devours.size() >= MAX_POOLED_DEVOURS) {
         this.devours.remove(0);
      }
      DevourBurst burst = new DevourBurst(
         scarCenter, targetCenter, entityWidth, entityHeight, scarHalfH, visualScale,
         spine, faceNormal, across, spinSign, seedPhase, this.age, capturedQuads, random
      );
      this.devours.add(burst);
      this.focusPoint = scarCenter;
      this.extendLife(Math.max(1, CausalityCollapsePattern.DEVOUR_FX_TICKS + 4));

      // 即刻触发吞噬空间碾碎音效（每 tick 节流防群体同时处决叠音过响）
      if (level != null && this.devourSoundThrottle < 3) {
         this.devourSoundThrottle++;
         float jitter = (seedPhase % 1.0F) * 0.14F;
         play(level, scarCenter, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.72F, 1.42F + jitter);
         play(level, scarCenter, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 0.54F, 1.15F + jitter * 0.6F);
         if (this.devourSoundThrottle == 1) {
            play(level, scarCenter, SoundEvents.CHORUS_FRUIT_TELEPORT, 0.58F, 1.62F);
            play(level, scarCenter, SoundEvents.GLASS_BREAK, 0.44F, 0.78F + jitter);
         }
      }
   }

   private ScarNode findHostScar(Vec3 scarCenter) {
      ScarNode best = null;
      double bestDistSqr = 2.6 * 2.6;
      int now = this.ageTicks();
      for (ScarNode scar : this.scars) {
         int local = now - scar.startAge;
         if (local < -2 || local > scar.duration) {
            continue;
         }
         double dSqr = scar.center.distanceToSqr(scarCenter);
         if (dSqr < bestDistSqr) {
            bestDistSqr = dSqr;
            best = scar;
         }
      }
      return best;
   }

   private int countCapturedDevourMeshes() {
      int count = 0;
      for (DevourBurst burst : this.devours) {
         if (burst.capturedQuads != null) {
            count++;
         }
      }
      return count;
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
         ? Mth.clamp(entityHeight * 0.58F, 0.95F, 1.75F) * visualScale
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
      if (!this.scars.isEmpty() || !this.devours.isEmpty()) {
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
      this.devourSoundThrottle = 0;

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

      Iterator<DevourBurst> devourIt = this.devours.iterator();
      while (devourIt.hasNext()) {
         DevourBurst burst = devourIt.next();
         int local = age - burst.startAge;
         if (local > CausalityCollapsePattern.DEVOUR_FX_TICKS) {
            devourIt.remove();
            continue;
         }
         // 残骸完全被狭缝吞没归零的收口轻鸣（local == 11）
         if (local == 11 && !burst.sealSoundPlayed) {
            burst.sealSoundPlayed = true;
            if (this.closeSoundThrottle < 2) {
               this.closeSoundThrottle++;
               play(level, burst.scarCenter, SoundEvents.SCULK_CATALYST_BLOOM, 0.38F, 1.90F);
            }
         }
      }

      if (!this.scars.isEmpty()) {
         this.focusPoint = this.scars.get(this.scars.size() - 1).center;
      } else if (!this.devours.isEmpty()) {
         this.focusPoint = this.devours.get(this.devours.size() - 1).scarCenter;
      }
   }

   private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
      Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
      if (camera != null) {
         Vec3 camPos = camera.getPosition();
         Vec3 delta = at.subtract(camPos);
         double dist = delta.length();
         // 原版 playLocalSound 常规衰减距离约 16 格；远程 Shift 狙杀（14..72 格）时沿原方向拉近虚拟声源并柔和衰减音量，
         // 既保留空间方位感，又确保 40 格外远程处决时能听清空间裂解与吞噬反馈
         if (dist > 14.0 && dist <= 72.0) {
            Vec3 relay = camPos.add(delta.scale(6.0 / dist));
            float atten = (float)Mth.clamp(0.76 - (dist - 14.0) / 140.0, 0.36, 0.76);
            level.playLocalSound(relay.x, relay.y, relay.z, sound, SoundSource.PLAYERS, volume * atten, pitch, false);
            return;
         }
      }
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
      for (DevourBurst burst : this.devours) {
         int local = age - burst.startAge;
         if (local == 0 || local == 4) {
            for (int i = 0; i < 10; i++) {
               double ang = FxMath.TAU * i / 10.0 + burst.seedPhase;
               double r = (local == 0 ? 0.9 : 0.4) * burst.visualScale;
               double ox = Math.cos(ang) * r;
               double oz = Math.sin(ang) * r;
               level.addParticle(
                  local == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.DRAGON_BREATH,
                  burst.scarCenter.x + ox, burst.scarCenter.y + (i % 3 - 1) * 0.3, burst.scarCenter.z + oz,
                  -ox * 0.18, 0.0, -oz * 0.18
               );
            }
         }
      }
   }

   // ───────────────────────────── 单裂痕与吞噬脉动包络 ─────────────────────────────

   /** 裂痕吞噬目标后的痉挛脉动过载曲线（0..1，瞬时暴冲后阻尼衰减）。 */
   private static float devourPulseCurve(ScarNode scar, float t) {
      if (scar.devourPulseStartAge < 0) {
         return 0.0F;
      }
      float elapsed = t - scar.devourPulseStartAge;
      if (elapsed < 0.0F || elapsed > CausalityCollapsePattern.DEVOUR_PULSE_TICKS) {
         return 0.0F;
      }
      float k = elapsed / (float)CausalityCollapsePattern.DEVOUR_PULSE_TICKS;
      return FxMath.envelope(k, 0.18F, 0.32F) * (1.0F + 0.18F * (float)Math.sin(elapsed * 1.45F));
   }

   /** 时空涡旋扭角强度曲线：骤裂瞬间过冲爆拧（1.30x），滞留期缓流呼吸，吞噬瞬间狂旋过载，尾声平滑阻尼退旋归零。 */
   private static float swirlCurve(ScarNode scar, float local, float t) {
      int duration = scar.duration;
      if (local < 0.0F || local > duration) {
         return 0.0F;
      }
      float openEnd = CausalityCollapsePattern.OPEN_TICKS;
      float closeStart = Math.max(openEnd + 4.0F, duration - CausalityCollapsePattern.CLOSE_TICKS);
      float base;
      if (local < openEnd) {
         float x = FxMath.clamp01(local / openEnd);
         base = FxMath.easeOutCubic(x) * (1.0F + 0.30F * (float)Math.sin(Math.PI * x));
      } else if (local <= closeStart) {
         base = 0.90F + 0.08F * (float)Math.sin(local * 0.24F + scar.seedPhase);
      } else {
         float closeT = FxMath.clamp01((local - closeStart) / Math.max(1.0F, duration - closeStart));
         base = 0.90F * (1.0F - FxMath.easeInOutSine(closeT));
      }
      float pulse = devourPulseCurve(scar, t);
      return base * (1.0F + 0.48F * pulse);
   }

   /** 漆黑视界暗缝张开度：0~3t 猛裂，滞留期稳定微呼吸，吞噬瞬间猛然张裂，尾声平滑收拢合缝。 */
   private static float crackOpenCurve(ScarNode scar, float local, float t) {
      int duration = scar.duration;
      if (local < 0.0F || local > duration) {
         return 0.0F;
      }
      float open = FxMath.easeOutCubic(FxMath.window(local, 0.0F, 3.0F));
      float closeStart = Math.max(8.0F, duration - CausalityCollapsePattern.CLOSE_TICKS);
      float close = 1.0F - FxMath.easeInCubic(FxMath.window(local, closeStart, duration - 1.2F));
      float tremble = 0.93F + 0.07F * (float)Math.sin(local * 0.52F + scar.seedPhase);
      float pulse = devourPulseCurve(scar, t);
      return open * close * tremble * (1.0F + 0.42F * pulse);
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

   // ───────────────────────────── 预乘 Alpha 本体层（锁步弯曲暗缝 + 奇点瞳孔 + 吞噬 3D 躯壳暗体 + 暗晶核） ─────────────────────────────

   @Override
   public boolean hasBody() {
      return true;
   }

   @Override
   public void renderBody(FxGeometry b, FxFrame frame) {
      if (this.scars.isEmpty() && this.devours.isEmpty()) {
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
         float crack = crackOpenCurve(scar, local, t);
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
         this.emitCurvedAbyssStrip(b, scar, c, halfH, halfW, scar.across, Math.min(0.98F, 0.96F * crack));
         this.emitCurvedAbyssStrip(b, scar, c, halfH * 0.94F, halfW * 0.58F, scar.faceNormal, Math.min(0.90F, 0.80F * crack));
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
         float crack = crackOpenCurve(scar, local, t);
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
         b.deformedSphere(eyePos, holeR, 8, 12, t * 0.25F * scar.spinSign, 0.05F, 2.2F, t * 0.3F, VOID_BLACK[0], VOID_BLACK[1], VOID_BLACK[2], Math.min(0.96F, 0.94F * crack));
         b.end();
         b.clearGeoCenter();
      }

      // 3. 被时空裂痕吞噬绞杀的 3D 活体躯壳暗体层（狭缝错位剪断 + 潮汐拉丝吸入漆黑虚空）
      boolean devourMeshStarted = false;
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local < 0.0F || local > 13.0F || burst.capturedQuads == null) {
            continue;
         }
         Vector3f relCheck = frame.rel(burst.scarCenter);
         if (relCheck.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         float dissolve = FxMath.window(local, 7.2F, 13.0F);
         b.begin(Style.AFTERIMAGE, 0.65F, 0.06F, 1.0F, 0.78F, 3.2F, dissolve);
         this.emitDevourMesh(b, frame, burst, local, true);
         devourMeshStarted = true;
      }
      if (devourMeshStarted) {
         b.end();
      }

      // 4. 裂隙旁悬浮旋转与吞噬迸散倒吸的 3D 玄黑晶核碎刃
      boolean shardStarted = false;
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(scar, local, t);
         float swirl = swirlCurve(scar, local, t);
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
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local < 0.0F || local > CausalityCollapsePattern.DEVOUR_FX_TICKS) {
            continue;
         }
         Vector3f c = frame.rel(burst.scarCenter);
         if (c.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         if (!shardStarted) {
            b.begin(Style.CRYSTAL_SHARD, 0.36F, 0.05F, 2.2F, 1.5F, 0.35F, 0.0F);
            shardStarted = true;
         }
         this.emitDevourShards(b, burst, c, local, true);
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

   // ───────────────────────────── HDR 网格发光层（品红/兰粉裂口锋刃 × 螺旋空间丝 × 约束环 × 吞噬绞杀演出） ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      if (this.scars.isEmpty() && this.devours.isEmpty()) {
         return;
      }
      float t = this.age(frame.partialTick);

      // 1. 锯齿双唇品红主锋 + 围绕裂隙的 5 条裂界同源螺旋旋臂 + 倾斜空间约束环（外圈品红/虚空紫层）
      g.begin(Style.RIBBON, 4.8F, 0.14F, 5.8F, 5.5F, 13.0F, 0.52F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(scar, local, t);
         float swirl = swirlCurve(scar, local, t);
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
         float flashBoost = 1.0F + 0.85F * openFlashCurve(local) + 0.90F * devourPulseCurve(scar, t);

         // 左右锯齿裂口主刃（裂界品红锋芒）
         if (crack > 0.01F) {
            this.drawJaggedLip(g, scar, c, halfH, lipSep, 1.0F, 0.024F * scar.visualScale, MAGENTA, Math.min(1.6F, 0.88F * crack * flashBoost));
            this.drawJaggedLip(g, scar, c, halfH, lipSep, -1.0F, 0.024F * scar.visualScale, MAGENTA, Math.min(1.6F, 0.88F * crack * flashBoost));
         }

         // 5 条 3D 顺滑螺旋空间丝（外层品红/虚空紫）
         if (swirl > 0.02F) {
            this.drawScarSpiralArms(g, scar, c, halfH, local, crack, swirl, false);
            this.drawScarOrbitRing(g, scar, c, local, crack, swirl, false);
         }
      }
      g.end();

      // 2. 兰粉白热内锋芯 + 螺旋细白芯 + 约束环亮芯 + 吞噬沿脊白热绞杀刃闪
      g.begin(Style.RIBBON, 8.2F, 0.10F, 9.8F, 0.0F, 0.0F, 0.24F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(scar, local, t);
         float swirl = swirlCurve(scar, local, t);
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
         float flashBoost = 1.0F + 0.85F * openFlashCurve(local) + 0.95F * devourPulseCurve(scar, t);
         if (crack > 0.01F) {
            this.drawJaggedLip(g, scar, c, halfH, lipSep, 1.0F, 0.009F * scar.visualScale, HOT_ORCHID, Math.min(1.5F, 0.82F * crack * flashBoost));
            this.drawJaggedLip(g, scar, c, halfH, lipSep, -1.0F, 0.009F * scar.visualScale, HOT_ORCHID, Math.min(1.5F, 0.82F * crack * flashBoost));
         }
         if (swirl > 0.02F) {
            this.drawScarSpiralArms(g, scar, c, halfH, local, crack, swirl, true);
            this.drawScarOrbitRing(g, scar, c, local, crack, swirl, true);
         }
      }
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local >= 0.0F && local <= 8.5F) {
            Vector3f c = frame.rel(burst.scarCenter);
            if (c.lengthSquared() <= 160.0F * 160.0F) {
               this.drawDevourSpineSlash(g, burst, c, local);
            }
         }
      }
      g.end();

      // 3. 被吞噬绞杀生物的 3D 躯壳 HDR 发光断茬（沿狭缝拉丝压扁 + 错位切面灼白）
      boolean devourHdrStarted = false;
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local < 0.0F || local > 13.0F || burst.capturedQuads == null) {
            continue;
         }
         Vector3f relCheck = frame.rel(burst.scarCenter);
         if (relCheck.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         float flash = 1.0F - FxMath.window(local, 0.0F, 3.2F);
         float dissolve = FxMath.window(local, 6.4F, 13.0F);
         g.begin(Style.AFTERIMAGE, 5.4F + 5.2F * flash, 0.06F, 1.4F, 0.14F + 0.38F * flash, 4.8F, dissolve);
         this.emitDevourMesh(g, frame, burst, local, false);
         devourHdrStarted = true;
      }
      if (devourHdrStarted) {
         g.end();
      }

      // 4. 沿螺旋被持续抽进裂口的空间尘埃流丝 + 吞噬瞬间倒卷碎流
      g.begin(Style.RIBBON, 6.2F, 0.12F, 6.5F, 0.0F, 0.0F, 0.30F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float swirl = swirlCurve(scar, local, t);
         if (swirl <= 0.02F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 120.0F * 120.0F) {
            continue;
         }
         this.drawScarStreaks(g, scar, c, local, swirl);
      }
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local < 0.0F || local > CausalityCollapsePattern.DEVOUR_FX_TICKS) {
            continue;
         }
         Vector3f c = frame.rel(burst.scarCenter);
         if (c.lengthSquared() > 120.0F * 120.0F) {
            continue;
         }
         this.drawDevourStreaks(g, burst, c, local);
      }
      g.end();

      // 5. 纤锐 3D 棱晶空间碎刃（克制亮度防止泛光糊成白块，突出玄黑晶核与冷冽棱边）
      g.begin(Style.CRYSTAL_SHARD, 2.8F, 0.05F, 2.6F, 2.0F, 0.30F, 0.0F);
      for (ScarNode scar : this.scars) {
         float local = t - scar.startAge;
         if (local < 0.0F || local > scar.duration) {
            continue;
         }
         float crack = crackOpenCurve(scar, local, t);
         float swirl = swirlCurve(scar, local, t);
         if (crack <= 0.02F) {
            continue;
         }
         Vector3f c = frame.rel(scar.center);
         if (c.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         this.emitScarShards(g, scar, c, local, crack, swirl, false);
      }
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local < 0.0F || local > CausalityCollapsePattern.DEVOUR_FX_TICKS) {
            continue;
         }
         Vector3f c = frame.rel(burst.scarCenter);
         if (c.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         this.emitDevourShards(g, burst, c, local, false);
      }
      g.end();

      // 6. 吞噬瞬间双侧喉部 3D 空间激波锥（0..6t）
      if (!this.devours.isEmpty()) {
         g.begin(Style.SHOCK_CONE, 5.6F, 0.12F, 0.5F, 4.2F, 0.85F, 1.0F);
         for (DevourBurst burst : this.devours) {
            float local = t - burst.startAge;
            if (local < 0.0F || local > 6.5F) {
               continue;
            }
            Vector3f c = frame.rel(burst.scarCenter);
            if (c.lengthSquared() > 140.0F * 140.0F) {
               continue;
            }
            this.drawDevourShockCone(g, burst, c, local);
         }
         g.end();
      }

      // 7. 初裂空间涟漪环（0..7t）+ 吞噬瞬间「外环向内塌缩碾压 + 内环冰青爆扩」双环
      g.begin(Style.RING, 4.8F, 0.10F, 0.85F, 0.020F, 0.0F, 0.0F);
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
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local < 0.0F || local > 9.5F) {
            continue;
         }
         Vector3f c = frame.rel(burst.scarCenter);
         if (c.lengthSquared() > 140.0F * 140.0F) {
            continue;
         }
         // 外环：品红空间合拢环，自外向喉心急剧塌缩挤压
         float implodeK = FxMath.clamp01(local / 9.5F);
         float implodeR = FxMath.lerp(1.48F, 0.08F, FxMath.easeInCubic(implodeK)) * burst.visualScale;
         float implodeAlpha = FxMath.envelope(implodeK, 0.15F, 0.45F) * 0.92F;
         if (implodeAlpha > 0.01F) {
            g.quad(c, new Vector3f(burst.across).mul(implodeR), new Vector3f(burst.spine).mul(implodeR), MAGENTA[0], MAGENTA[1], MAGENTA[2], implodeAlpha);
         }
         // 内环：0..6t 冰青棱镜爆扩环（横切裂痕平面，强化空间碾碎层次）
         if (local <= 6.0F) {
            float expK = local / 6.0F;
            float expR = (0.18F + 1.05F * FxMath.easeOutCubic(expK)) * burst.visualScale;
            float expAlpha = (1.0F - expK) * (1.0F - expK) * 0.78F;
            g.quad(c, new Vector3f(burst.across).mul(expR), new Vector3f(burst.faceNormal).mul(expR), PRISM_CYAN[0], PRISM_CYAN[1], PRISM_CYAN[2], expAlpha);
         }
      }
      g.end();

      // 8. 初裂兰粉白热耀斑 & 吞噬十字棱镜星芒 & 临终合缝针尖星芒
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
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local < 0.0F || local > 14.0F) {
            continue;
         }
         Vector3f c = frame.rel(burst.scarCenter);
         if (c.lengthSquared() > 160.0F * 160.0F) {
            continue;
         }
         // 吞噬初瞬（0..5.5t）白热 × 品红锐芒
         if (local <= 5.5F) {
            float k = FxMath.envelope(local / 5.5F, 0.14F, 0.34F);
            float sz = (0.58F + 0.68F * k) * burst.visualScale;
            g.billboard(c, sz, local * 0.16F * burst.spinSign, HOT_ORCHID[0], HOT_ORCHID[1], HOT_ORCHID[2], 0.94F * k);
            g.billboard(c, sz * 0.62F, (float)(Math.PI * 0.25) - local * 0.14F * burst.spinSign, WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], 0.92F * k);
         }
         // 残骸彻底咽入黑缝瞬间（9.5..14t）针尖冰青收口星闪
         if (local >= 9.5F && local <= 14.0F) {
            float gk = FxMath.envelope((local - 9.5F) / 4.5F, 0.30F, 0.48F);
            float sz = (0.32F + 0.38F * gk) * burst.visualScale;
            g.billboard(c, sz, local * 0.28F, WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], 0.88F * gk);
            g.billboard(c, sz * 0.78F, -local * 0.22F, PRISM_CYAN[0], PRISM_CYAN[1], PRISM_CYAN[2], 0.76F * gk);
         }
      }
      g.end();
   }

   // ───────────────────────────── 吞噬绞杀专属 3D 几何绘制 ─────────────────────────────

   /**
    * 狭缝潮汐撕裂 3D 躯壳绘制：
    * 将抓拍到的生物网格投影到裂痕局部三维坐标系（spine / across / faceNormal）：
    * 前 3 tick 沿裂口脊线产生锐利错位剪断，随后在横向与法向急剧压扁成薄片、沿裂痕长轴潮汐拉伸（Spaghettification）
    * 并螺旋抽缩进漆黑裂隙喉心彻底抹除。
    */
   private void emitDevourMesh(FxGeometry g, FxFrame frame, DevourBurst burst, float local, boolean bodyPass) {
      float[] quads = burst.capturedQuads;
      if (quads == null || quads.length < 12) {
         return;
      }
      float moveK = FxMath.easeOutCubic(FxMath.window(local, 0.0F, 5.5F));
      Vec3 curCenterWorld = burst.targetCenter.lerp(burst.scarCenter, moveK);
      Vector3f relCenter = frame.rel(curCenterWorld);

      // 0..3t 错位剪开，4..12t 随吸入合拢归零
      float shearOpen = FxMath.easeOutCubic(FxMath.window(local, 0.0F, 2.4F))
         * (1.0F - FxMath.easeInCubic(FxMath.window(local, 4.2F, 11.8F)));
      // 压扁与向心抽缩进度
      float flattenK = FxMath.easeInCubic(FxMath.window(local, 1.5F, 12.6F));
      float acrossScale = FxMath.lerp(1.0F, 0.03F, flattenK);
      float faceScale = FxMath.lerp(1.0F, 0.02F, flattenK);
      // 沿裂隙长轴先潮汐拉长，再向喉心急剧抽缩归零
      float stretchArch = (float)Math.sin(Math.PI * FxMath.window(local, 0.6F, 10.2F));
      float lengthCollapse = 1.0F - FxMath.easeInCubic(FxMath.window(local, 6.2F, 13.0F));
      float spineScale = (1.0F + 0.50F * stretchArch) * lengthCollapse;

      float twistAmp = burst.spinSign * 2.55F * flattenK;
      float halfH = Math.max(0.45F, burst.entityHeight * 0.5F);
      float centerOffsetY = burst.entityHeight * 0.5F;
      float fade = 1.0F - FxMath.window(local, 10.2F, 13.0F);
      if (fade <= 0.01F || spineScale <= 0.01F) {
         return;
      }

      for (int i = 0; i + 11 < quads.length; i += 12) {
         // 计算面片中心在裂痕局部坐标中的位置，用于统一归属左右错位半区与接缝发光度
         float cx = (quads[i] + quads[i + 3] + quads[i + 6] + quads[i + 9]) * 0.25F;
         float cy = (quads[i + 1] + quads[i + 4] + quads[i + 7] + quads[i + 10]) * 0.25F - centerOffsetY;
         float cz = (quads[i + 2] + quads[i + 5] + quads[i + 8] + quads[i + 11]) * 0.25F;
         float cAcross = cx * burst.across.x + cy * burst.across.y + cz * burst.across.z;
         float sideSign = cAcross >= 0.0F ? 1.0F : -1.0F;
         float slitProx = (float)Math.exp(-(cAcross * cAcross) / Math.max(0.05F, burst.entityWidth * burst.entityWidth * 0.16F));

         float slipSpine = sideSign * 0.28F * shearOpen * burst.visualScale;
         float splitAcross = sideSign * 0.11F * shearOpen * burst.visualScale;

         this.transformDevourVertex(
            quads[i], quads[i + 1] - centerOffsetY, quads[i + 2],
            burst, halfH, spineScale, acrossScale, faceScale, twistAmp, slipSpine, splitAcross, relCenter, this.q0
         );
         this.transformDevourVertex(
            quads[i + 3], quads[i + 4] - centerOffsetY, quads[i + 5],
            burst, halfH, spineScale, acrossScale, faceScale, twistAmp, slipSpine, splitAcross, relCenter, this.q1
         );
         this.transformDevourVertex(
            quads[i + 6], quads[i + 7] - centerOffsetY, quads[i + 8],
            burst, halfH, spineScale, acrossScale, faceScale, twistAmp, slipSpine, splitAcross, relCenter, this.q2
         );
         this.transformDevourVertex(
            quads[i + 9], quads[i + 10] - centerOffsetY, quads[i + 11],
            burst, halfH, spineScale, acrossScale, faceScale, twistAmp, slipSpine, splitAcross, relCenter, this.q3
         );

         if (bodyPass) {
            g.rawQuad(this.q0, this.q1, this.q2, this.q3, DEEP_BODY[0], DEEP_BODY[1], DEEP_BODY[2], fade * 0.92F);
         } else {
            // 靠近裂痕剪断脊线处点亮炙热兰粉白刃与微弱棱青高光，外侧渐变为裂界品红与虚空紫
            float seamHot = slitProx * (0.45F + 0.55F * (1.0F - FxMath.window(local, 0.0F, 6.0F)));
            float cyanHint = slitProx * FxMath.window(local, 2.0F, 9.0F) * 0.35F;
            float baseR = FxMath.lerp(VIOLET[0], MAGENTA[0], slitProx);
            float baseG = FxMath.lerp(VIOLET[1], MAGENTA[1], slitProx);
            float baseB = FxMath.lerp(VIOLET[2], MAGENTA[2], slitProx);
            float r = FxMath.lerp(FxMath.lerp(baseR, WHITE_HOT[0], seamHot * 0.78F), PRISM_CYAN[0], cyanHint);
            float gr = FxMath.lerp(FxMath.lerp(baseG, WHITE_HOT[1], seamHot * 0.78F), PRISM_CYAN[1], cyanHint);
            float bl = FxMath.lerp(FxMath.lerp(baseB, WHITE_HOT[2], seamHot * 0.78F), PRISM_CYAN[2], cyanHint);
            g.rawQuad(this.q0, this.q1, this.q2, this.q3, r, gr, bl, fade * (0.70F + 0.30F * seamHot));
         }
      }
   }

   private void transformDevourVertex(
      float lx, float ly, float lz, DevourBurst burst,
      float halfH, float spineScale, float acrossScale, float faceScale,
      float twistAmp, float slipSpine, float splitAcross,
      Vector3f relCenter, Vector3f out
   ) {
      float uSpine = lx * burst.spine.x + ly * burst.spine.y + lz * burst.spine.z;
      float uAcross = lx * burst.across.x + ly * burst.across.y + lz * burst.across.z;
      float uFace = lx * burst.faceNormal.x + ly * burst.faceNormal.y + lz * burst.faceNormal.z;

      float ny = Mth.clamp(uSpine / halfH, -1.4F, 1.4F);
      float ang = twistAmp * ny;
      float cs = (float)Math.cos(ang);
      float sn = (float)Math.sin(ang);

      float rotAcross = uAcross * cs - uFace * sn;
      float rotFace = uAcross * sn + uFace * cs;

      float finalSpine = uSpine * spineScale + slipSpine;
      float finalAcross = rotAcross * acrossScale + splitAcross;
      float finalFace = rotFace * faceScale;

      out.set(relCenter)
         .fma(finalSpine, burst.spine)
         .fma(finalAcross, burst.across)
         .fma(finalFace, burst.faceNormal);
   }

   /** 沿裂痕脊线劈下并急速收束的绞杀白热刃闪。 */
   private void drawDevourSpineSlash(FxGeometry g, DevourBurst burst, Vector3f center, float local) {
      float k = FxMath.clamp01(local / 8.5F);
      float env = FxMath.envelope(k, 0.14F, 0.35F);
      if (env <= 0.01F) {
         return;
      }
      float halfH = burst.scarHalfH * (1.28F - 0.45F * FxMath.easeInCubic(k));
      int segs = 24;
      List<Vector3f> pts = new ArrayList<>(segs + 1);
      float[] widths = new float[segs + 1];
      float[] alphas = new float[segs + 1];
      float baseW = (0.042F * (1.0F - 0.55F * k)) * burst.visualScale;
      baseW = Math.max(baseW, center.length() * 0.0022F * (1.0F - 0.45F * k));

      for (int i = 0; i <= segs; i++) {
         float u = (float)i / segs * 2.0F - 1.0F;
         float taper = (float)Math.pow(Math.max(0.0F, 1.0F - u * u), 0.65);
         float jag = evalJag(u, burst.seedPhase) * halfH * taper;
         pts.add(new Vector3f(center).fma(u * halfH, burst.spine).fma(jag, burst.across));
         widths[i] = baseW * (0.20F + 0.80F * taper);
         alphas[i] = env * (0.25F + 0.75F * taper);
      }
      g.ribbon(pts, widths, alphas, WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2]);

      // 极细冰青棱镜伴锋（微偏法向，增强冷冽斩断感）
      for (int i = 0; i <= segs; i++) {
         pts.get(i).fma(0.018F * burst.visualScale, burst.faceNormal);
         widths[i] *= 0.58F;
         alphas[i] *= 0.72F;
      }
      g.ribbon(pts, widths, alphas, PRISM_CYAN[0], PRISM_CYAN[1], PRISM_CYAN[2]);
   }

   /** 吞噬瞬间双侧喉部 3D 激波锥。 */
   private void drawDevourShockCone(FxGeometry g, DevourBurst burst, Vector3f center, float local) {
      float k = FxMath.clamp01(local / 6.5F);
      float fade = (1.0F - k) * (1.0F - k);
      if (fade <= 0.01F) {
         return;
      }
      float len = (0.48F + 1.25F * FxMath.easeOutCubic(k)) * burst.visualScale;
      float rBase = 0.08F * burst.visualScale;
      float rTop = (0.36F + 1.28F * FxMath.easeOutCubic(k)) * burst.visualScale;
      Vector3f front = new Vector3f(center).fma(len, burst.faceNormal);
      Vector3f back = new Vector3f(center).fma(-len, burst.faceNormal);
      g.cylinder(center, front, rBase, rTop, 16, local * 0.45F * burst.spinSign, MAGENTA[0], MAGENTA[1], MAGENTA[2], 0.62F * fade);
      g.cylinder(center, back, rBase, rTop * 0.86F, 16, -local * 0.45F * burst.spinSign, VIOLET[0], VIOLET[1], VIOLET[2], 0.46F * fade);
   }

   /** 吞噬瞬间先外崩、随即急速沿螺旋倒吸进裂痕喉心的 3D 玄黑棱晶碎刃。 */
   private void emitDevourShards(FxGeometry g, DevourBurst burst, Vector3f center, float local, boolean bodyPass) {
      float k = FxMath.clamp01(local / (float)CausalityCollapsePattern.DEVOUR_FX_TICKS);
      // 前 28% 外崩悬滞，后 72% 沿螺线加速倒吸入黑缝消失
      float radialProfile = k < 0.28F
         ? FxMath.easeOutCubic(k / 0.28F)
         : (1.0F - FxMath.easeInCubic((k - 0.28F) / 0.72F));
      float sizeScale = burst.visualScale * (1.0F - 0.82F * FxMath.window(k, 0.58F, 1.0F));
      float alpha = (1.0F - FxMath.window(k, 0.78F, 1.0F));
      if (alpha <= 0.01F || sizeScale <= 0.01F) {
         return;
      }

      for (int i = 0; i < burst.shards.size(); i++) {
         ScarSplinter sp = burst.shards.get(i);
         float ang = sp.phase + burst.spinSign * (k * 5.6F + local * sp.spin);
         float r = sp.radius * radialProfile * burst.visualScale;
         float axial = sp.along * burst.scarHalfH * radialProfile;
         Vector3f pos = new Vector3f(center)
            .fma(axial, burst.spine)
            .fma(r * (float)Math.cos(ang), burst.across)
            .fma(r * (float)Math.sin(ang), burst.faceNormal);

         Vector3f tangent = new Vector3f(burst.across).mul(-(float)Math.sin(ang) * burst.spinSign)
            .fma((float)Math.cos(ang) * burst.spinSign, burst.faceNormal);
         Vector3f toCore = new Vector3f(center).sub(pos);
         if (toCore.lengthSquared() > 1.0E-5F) {
            toCore.normalize();
         } else {
            toCore.set(burst.spine);
         }
         Vector3f fwd = new Vector3f(tangent).lerp(toCore, FxMath.window(k, 0.24F, 0.62F));
         if (fwd.lengthSquared() < 1.0E-5F) {
            fwd.set(burst.spine);
         } else {
            fwd.normalize();
         }
         Vector3f right = FxMath.perpendicular(fwd);
         Vector3f up = new Vector3f(fwd).cross(right).normalize();

         float len = sp.length * sizeScale;
         float[] col = bodyPass ? DEEP_BODY : (sp.cyanAccent ? PRISM_CYAN : (i % 2 == 0 ? HOT_ORCHID : MAGENTA));
         g.bladeShard(pos, fwd, right, up, len, len * 0.44F, len * 0.15F, len * 0.09F, col[0], col[1], col[2], alpha * (bodyPass ? 0.92F : 0.82F));
      }
   }

   /** 吞噬爆发的 12 条急速倒卷星尘流丝。 */
   private void drawDevourStreaks(FxGeometry g, DevourBurst burst, Vector3f center, float local) {
      float maxR = (1.15F + 0.38F * burst.entityWidth) * burst.visualScale;
      for (int i = 0; i < burst.streakAzimuth.length; i++) {
         float startT = burst.streakDelay[i];
         float dur = 10.5F;
         float phase = (local - startT) / dur;
         if (phase <= 0.0F || phase >= 1.0F) {
            continue;
         }
         float headP = FxMath.easeInCubic(phase);
         float tailP = FxMath.easeInCubic(Math.max(0.0F, phase - 0.18F));
         float rHead = maxR * (1.0F - headP);
         float rTail = maxR * (1.0F - tailP);
         float angHead = burst.streakAzimuth[i] + burst.spinSign * headP * 4.4F;
         float angTail = burst.streakAzimuth[i] + burst.spinSign * tailP * 4.4F;

         Vector3f head = new Vector3f(center)
            .fma(rHead * (float)Math.cos(angHead), burst.across)
            .fma(rHead * (float)Math.sin(angHead), burst.faceNormal)
            .fma(burst.streakAxial[i] * burst.scarHalfH * (1.0F - headP), burst.spine);
         Vector3f tail = new Vector3f(center)
            .fma(rTail * (float)Math.cos(angTail), burst.across)
            .fma(rTail * (float)Math.sin(angTail), burst.faceNormal)
            .fma(burst.streakAxial[i] * burst.scarHalfH * (1.0F - tailP), burst.spine);

         float alpha = (float)Math.sin(Math.PI * phase) * 0.92F;
         float[] col = i % 4 == 0 ? PRISM_CYAN : (i % 3 == 0 ? WHITE_HOT : (i % 2 == 0 ? MAGENTA : HOT_ORCHID));
         float streakW = Math.max(0.022F * burst.visualScale, center.length() * 0.0014F);
         g.streak(head, tail, streakW, col[0], col[1], col[2], alpha);
      }
   }

   // ───────────────────────────── 常驻裂痕 3D 几何绘制 ─────────────────────────────

   private void drawJaggedLip(
      FxGeometry g, ScarNode scar, Vector3f center, float halfH, float lipSep, float sideSign,
      float baseWidth, float[] color, float alpha
   ) {
      float effectiveWidth = Math.max(baseWidth, center.length() * (baseWidth >= 0.015F ? 0.0018F : 0.0009F));
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
         widths[i] = effectiveWidth * (0.28F + 0.72F * taper);
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
            alphas[s] = Math.min(1.4F, swirl * (0.18F + 0.82F * taper) * (corePass ? 0.84F : 0.86F));
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
      float baseAlpha = Math.min(1.2F, swirl * (corePass ? 0.72F : 0.52F));

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

         float alpha = Math.min(1.2F, (float)Math.sin(Math.PI * phase) * swirl * 0.85F);
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
         g.bladeShard(pos, fwd, right, up, len, len * 0.42F, len * 0.13F, len * 0.08F, col[0], col[1], col[2], Math.min(0.95F, (bodyPass ? 0.92F : 0.68F) * crack));
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
         float swirl = swirlCurve(scar, local, t);
         float crack = crackOpenCurve(scar, local, t);
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

         float pulse = devourPulseCurve(scar, t);
         float signedTwist = scar.spinSign * scar.twistBase * swirl;
         float centerBias = 1.0F / (0.22F + (float)Math.hypot(uvCenter[0] - 0.5F, uvCenter[1] - 0.5F));
         float score = (halfLenScreen * 3.0F + swirl + crack * 0.6F + pulse * 1.4F) * centerBias / (1.0F + viewDist * 0.04F);
         visible.add(new ProjectedScar(
            scar, rel, viewDist, uvCenter[0], uvCenter[1], halfLenScreen, tiltAngle, signedTwist, crack, swirl,
            halfH, Math.max(openFlashCurve(local), pulse * 0.95F), alignGlintCurve(local, scar.duration), score
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

   // ───────────────────────────── 克制的初裂与吞噬瞬间镜头微颤（滞留期零干扰） ─────────────────────────────

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
      float peakDevour = 0.0F;
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local >= 0.0F && local <= 5.0F) {
            Vector3f rel = frame.rel(burst.scarCenter);
            float distAtten = 1.0F / (1.0F + rel.length() * 0.045F);
            peakDevour = Math.max(peakDevour, FxMath.envelope(local / 5.0F, 0.16F, 0.36F) * distAtten);
         }
      }
      if (peakOpen > 0.02F || peakDevour > 0.02F) {
         screen.addAberration(Math.min(0.45F, 0.26F * peakOpen + 0.30F * peakDevour));
         screen.addFovKick(-Math.min(0.040F, 0.022F * peakOpen + 0.028F * peakDevour));
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
      float peakDevour = 0.0F;
      for (DevourBurst burst : this.devours) {
         float local = t - burst.startAge;
         if (local >= 0.0F && local <= 4.5F) {
            Vector3f rel = frame.rel(burst.scarCenter);
            float distAtten = 1.0F / (1.0F + rel.length() * 0.05F);
            peakDevour = Math.max(peakDevour, FxMath.envelope(local / 4.5F, 0.18F, 0.36F) * distAtten);
         }
      }
      return Math.min(0.42F, 0.22F * peakOpen + 0.28F * peakDevour);
   }

   /** 与 fx_fold_scene / fx_fold_volume 完全锁步的紧致三频裂隙脊线采样。 */
   private static float evalJag(float u, float seedPhase) {
      return (float)(
         Math.sin(u * 3.8 + seedPhase) * 0.042
            + Math.sin(u * 10.5 + seedPhase * 2.1) * 0.018
            + Math.cos(u * 21.0 + seedPhase * 3.3) * 0.008
      );
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
      int devourPulseStartAge = -1;
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

      void triggerDevourPulse(int poolAge) {
         this.devourPulseStartAge = poolAge;
      }

      float jagSample(float u) {
         return evalJag(u, this.seedPhase);
      }
   }

   private static final class DevourBurst {
      final Vec3 scarCenter;
      final Vec3 targetCenter;
      final float entityWidth;
      final float entityHeight;
      final float scarHalfH;
      final float visualScale;
      final Vector3f spine;
      final Vector3f faceNormal;
      final Vector3f across;
      final float spinSign;
      final float seedPhase;
      final int startAge;
      final float[] capturedQuads;
      final List<ScarSplinter> shards = new ArrayList<>(DEVOUR_SHARDS);
      final float[] streakAzimuth = new float[DEVOUR_STREAKS];
      final float[] streakAxial = new float[DEVOUR_STREAKS];
      final float[] streakDelay = new float[DEVOUR_STREAKS];
      boolean sealSoundPlayed;

      DevourBurst(
         Vec3 scarCenter, Vec3 targetCenter, float entityWidth, float entityHeight,
         float scarHalfH, float visualScale, Vector3f spine, Vector3f faceNormal, Vector3f across,
         float spinSign, float seedPhase, int startAge, float[] capturedQuads, RandomSource random
      ) {
         this.scarCenter = scarCenter;
         this.targetCenter = targetCenter;
         this.entityWidth = entityWidth;
         this.entityHeight = entityHeight;
         this.scarHalfH = scarHalfH;
         this.visualScale = visualScale;
         this.spine = spine;
         this.faceNormal = faceNormal;
         this.across = across;
         this.spinSign = spinSign;
         this.seedPhase = seedPhase;
         this.startAge = startAge;
         this.capturedQuads = capturedQuads;

         for (int i = 0; i < DEVOUR_SHARDS; i++) {
            float along = random.nextFloat() * 1.40F - 0.70F;
            float radius = 0.42F + random.nextFloat() * 0.56F;
            float len = (0.14F + random.nextFloat() * 0.14F);
            float spin = 0.08F + random.nextFloat() * 0.10F;
            this.shards.add(new ScarSplinter(along, radius, len, spin, i == 0 || i == 4, random.nextFloat() * FxMath.TAU));
         }
         for (int i = 0; i < DEVOUR_STREAKS; i++) {
            this.streakAzimuth[i] = random.nextFloat() * FxMath.TAU;
            this.streakAxial[i] = (random.nextFloat() - 0.5F) * 1.35F;
            this.streakDelay[i] = random.nextFloat() * 2.6F;
         }
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
