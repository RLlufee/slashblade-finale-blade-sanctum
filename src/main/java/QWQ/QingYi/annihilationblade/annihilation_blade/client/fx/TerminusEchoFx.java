package QWQ.QingYi.annihilationblade.annihilation_blade.client.fx;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusEchoPattern;
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
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
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
 * 湮灭之刃 SE「归墟回响·因果反噬」客户端旗舰双层渲染器。
 * <p>
 * 色盘采用高辨识度的「玄黑曜石暗镜（Body）× 终焉鎏金琥珀（主律）× 霜白青冷辉（空间折返）」，
 * 完整承载「绝对庇护」的防守反击五幕演出：
 * <pre>
 *  0 ~ 6   绝对偏折：受击来向展开倾斜六棱玄黑归墟偏折镜 + 六芒律印 + 反旋刻度环 + 6 枚棱晶角锚 + 折返火星；
 *                    若拦截了敌方弹射物，镜心额外弹出双层吞噬冲击环与瞬时黑蚀核（第一人称中心自动柔化绝不糊眼）。
 *  2 ~ 10  因果逆溯：偏折镜心射出微弧二次贝塞尔因果律主轨 + 金青双螺旋束缚丝 + 4 座空间折跃刻度环，
 *                    t = 7 钉死攻击者胸口，展开脚下六芒锁印与四向黑金卡尺。
 *  6 ~ 14  虚影具现：攻击者斜后方裂开玄黑虚空缝，抓拍并显现持刀玩家自身的黑金 3D 全息剑影与蓄压棱晶太刀，
 *                    t = 11..14 刀尖收束十字蓄刃金芒。
 * 14 ~ 22  因果反斩：服务端同步 ECHO_EXECUTE 后，玩家虚影瞬步穿身掠过目标（3 帧残影叠影 + 速度线），
 *                    挥出巨型交叉 3D 暗芯曲面刀弧（金×青撞色）与直线白热一闪 + 局部 fx_slice_scene 空间错位；
 *                    目标 3D 骨骼剪影沿斜刀面一分为二，白金晶化定格 2.5 tick 后错位滑离并金灰烧蚀。
 * 22 ~ 44  镜碎影散：偏折镜沿六棱碎成 12 枚真 3D 黑金镜片缓慢翻转飘散，因果轨倒卷收回，虚影自下而上烧蚀消散。
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public final class TerminusEchoFx extends FxInstance {
   private static final int TRACE_SAMPLES = 28;
   private static final int TRACE_RINGS = 4;
   private static final int DEFLECT_SPARKS = 16;
   private static final int MIRROR_SHARDS = 12;
   private static final int SLASH_SPARKS = 24;
   private static final int SLASH_SHARDS = 10;
   private static final int DASH_LINES = 8;
   private static final double SLICE_RANGE = 48.0;

   // 黑金 × 霜白青专属高反差色盘
   private static final float[] OBSIDIAN = {0.014F, 0.012F, 0.024F};
   private static final float[] DEEP_BRONZE = {0.14F, 0.08F, 0.025F};
   private static final float[] AMBER_GOLD = {1.00F, 0.72F, 0.18F};
   private static final float[] PALE_GOLD = {1.00F, 0.90F, 0.56F};
   private static final float[] WHITE_GOLD = {1.00F, 0.97F, 0.88F};
   private static final float[] FROST_CYAN = {0.50F, 0.92F, 1.00F};
   private static final float[] VOID_INDIGO = {0.34F, 0.20F, 0.74F};

   private final int casterId;
   private final float scale;
   private final boolean projectileIntercepted;
   private final boolean deflectOnly;
   private final long seed;
   private final RandomSource random;

   // 偏折镜锚点与正交基
   private final boolean hasMirror;
   private final Vec3 mirrorCenter;
   private final Vec3 mirrorNormal;
   private final Vec3 mirrorRight;
   private final Vec3 mirrorUp;
   private final Vec3[] deflectSparkVel = new Vec3[DEFLECT_SPARKS];
   private final Vec3[] mirrorShardDir = new Vec3[MIRROR_SHARDS];
   private final Vec3[] mirrorShardVel = new Vec3[MIRROR_SHARDS];
   private final float[] mirrorShardSpin = new float[MIRROR_SHARDS];
   private final float[] mirrorShardSize = new float[MIRROR_SHARDS];

   // 目标与因果溯源态
   private int targetEntityId = -1;
   private Vec3 targetCenter;
   private float targetWidth = 0.8F;
   private float targetHeight = 1.8F;

   // 玩家黑金虚影抓拍态（蓄刃 + 穿斩收刀双姿态）
   private boolean ghostCaptureTried;
   private float ghostHeight = 1.8F;
   private float[] ghostReadyQuads;
   private float[] ghostFinishQuads;
   private Vec3 ghostStartChest;
   private Vec3 ghostEndChest;

   // 处决同步与剪影斩断态
   private boolean executed;
   private float executeAge = TerminusEchoPattern.EXECUTE_TICK;
   private int abortedAt = -1;
   private int sustainDuration = Integer.MAX_VALUE;
   private Vec3 execCenter;
   private Vec3 slashForward;
   private Vec3 slashDir;
   private Vec3 slashNormal;
   private Vec3 slashAltDir;
   private Vec3 slashA;
   private Vec3 slashB;
   private Vec3 slashAltA;
   private Vec3 slashAltB;
   private float slashLength = 3.2F;
   private float slipSign = 1.0F;
   private Vec3 targetFeet;
   private float[] targetQuads;
   private float[] targetSides;
   private final Vec3[] slashSparkVel = new Vec3[SLASH_SPARKS];
   private final Vec3[] slashShardVel = new Vec3[SLASH_SHARDS];
   private final float[] slashShardSpin = new float[SLASH_SHARDS];
   private final float[] slashShardSize = new float[SLASH_SHARDS];
   private final Vec3[] dashOffsets = new Vec3[DASH_LINES];
   private final float[] dashPhases = new float[DASH_LINES];

   // 复用临时向量降低每帧 GC
   private final Vector3f q0 = new Vector3f();
   private final Vector3f q1 = new Vector3f();
   private final Vector3f q2 = new Vector3f();
   private final Vector3f q3 = new Vector3f();

   public TerminusEchoFx(ClientLevel level, FxEventPacket packet) {
      super(TerminusEchoPattern.TOTAL_LIFETIME);
      this.casterId = packet.casterId();
      this.seed = packet.seed();
      this.random = RandomSource.create(this.seed);
      int kind = AnnihilationFxEvents.echoKind(packet.intParam());
      this.projectileIntercepted = AnnihilationFxEvents.echoIsProjectile(packet.intParam());
      this.scale = AnnihilationFxEvents.echoVisualScale(packet.intParam());
      this.deflectOnly = kind == AnnihilationFxEvents.ECHO_DEFLECT_ONLY;

      int[] ids = packet.entityIds();
      if (ids.length > 0) {
         this.targetEntityId = ids[0];
      }

      if (kind == AnnihilationFxEvents.ECHO_EXECUTE) {
         // 中途进场玩家收到的 START 携带的是最近一次 UPDATE（处决拍点）：直接进入处决与余韵演播
         this.hasMirror = false;
         this.mirrorCenter = packet.origin();
         this.mirrorNormal = TerminusEchoPattern.safeNormalize(packet.direction().scale(-1.0), new Vec3(0.0, 0.0, 1.0));
         Vec3[] basis = buildPlaneBasis(this.mirrorNormal);
         this.mirrorRight = basis[0];
         this.mirrorUp = basis[1];
         this.targetCenter = packet.origin();
         this.targetWidth = Math.max(0.6F, packet.radius());
         this.targetHeight = Math.max(0.8F, packet.scale());
         this.initMirrorDebris();
         this.applyExecution(level, packet, this.age);
      } else if (this.deflectOnly) {
         this.lifetime = TerminusEchoPattern.DEFLECT_ONLY_LIFETIME;
         this.hasMirror = true;
         this.mirrorCenter = packet.origin();
         this.mirrorNormal = TerminusEchoPattern.safeNormalize(packet.direction(), new Vec3(0.0, 0.0, 1.0));
         Vec3[] basis = buildPlaneBasis(this.mirrorNormal);
         this.mirrorRight = basis[0];
         this.mirrorUp = basis[1];
         this.targetCenter = this.mirrorCenter.add(this.mirrorNormal.scale(2.0));
         this.initMirrorDebris();
      } else {
         this.hasMirror = true;
         this.mirrorCenter = packet.origin();
         Vec3 delta = packet.direction();
         this.targetCenter = delta.lengthSqr() > 1.0E-4 ? this.mirrorCenter.add(delta) : this.mirrorCenter.add(0.0, 0.0, 4.0);
         this.mirrorNormal = TerminusEchoPattern.safeNormalize(delta, new Vec3(0.0, 0.0, 1.0));
         Vec3[] basis = buildPlaneBasis(this.mirrorNormal);
         this.mirrorRight = basis[0];
         this.mirrorUp = basis[1];
         this.targetHeight = Math.max(0.8F, packet.scale());
         this.syncTrackedTarget(level);
         this.initMirrorDebris();
         this.updateGhostPlacement();
      }
   }

   private void initMirrorDebris() {
      for (int i = 0; i < DEFLECT_SPARKS; i++) {
         double angle = (i + this.random.nextDouble() * 0.7) / DEFLECT_SPARKS * Math.PI * 2.0;
         double radial = 0.14 + this.random.nextDouble() * 0.22;
         double rebound = 0.08 + this.random.nextDouble() * 0.20;
         this.deflectSparkVel[i] = this.mirrorRight.scale(Math.cos(angle) * radial)
            .add(this.mirrorUp.scale(Math.sin(angle) * radial))
            .add(this.mirrorNormal.scale(rebound));
      }
      for (int i = 0; i < MIRROR_SHARDS; i++) {
         double angle = (i + 0.5) / MIRROR_SHARDS * Math.PI * 2.0 + (this.random.nextDouble() - 0.5) * 0.25;
         double radius = 0.25 + (i % 2 == 0 ? 0.45 : 0.78);
         this.mirrorShardDir[i] = this.mirrorRight.scale(Math.cos(angle) * radius).add(this.mirrorUp.scale(Math.sin(angle) * radius));
         double speed = 0.045 + this.random.nextDouble() * 0.075;
         this.mirrorShardVel[i] = this.mirrorRight.scale(Math.cos(angle) * speed)
            .add(this.mirrorUp.scale(Math.sin(angle) * speed + 0.015))
            .add(this.mirrorNormal.scale((this.random.nextDouble() - 0.3) * 0.06));
         this.mirrorShardSpin[i] = (this.random.nextFloat() - 0.5F) * 0.75F;
         this.mirrorShardSize[i] = (0.14F + this.random.nextFloat() * 0.13F) * this.scale;
      }
   }

   // ───────────────────────────── 持续特效钩子 ─────────────────────────────

   @Override
   protected void onSustainedStart(int duration) {
      this.sustainDuration = duration;
      if (!this.deflectOnly) {
         this.lifetime = Math.max(this.lifetime, TerminusEchoPattern.TOTAL_LIFETIME);
      }
   }

   @Override
   public void onSustainedUpdate(ClientLevel level, FxEventPacket event) {
      if (this.deflectOnly || this.abortedAt >= 0) {
         return;
      }
      if (AnnihilationFxEvents.echoKind(event.intParam()) == AnnihilationFxEvents.ECHO_EXECUTE && !this.executed) {
         this.applyExecution(level, event, this.age);
      }
   }

   @Override
   public void onSustainedStop() {
      if (this.deflectOnly) {
         return;
      }
      if (this.executed) {
         // 处决已完成：保持余韵自然播完
         return;
      }
      // 目标在抵达处决前提前死亡或玩家切换状态：优雅淡出，不播放空砍处决
      if (this.abortedAt < 0) {
         this.abortedAt = this.age;
         this.lifetime = Math.min(this.lifetime, this.age + TerminusEchoPattern.ABORT_FADE_TICKS);
      }
   }

   private void applyExecution(ClientLevel level, FxEventPacket event, float atAge) {
      this.executed = true;
      this.executeAge = atAge;
      this.execCenter = event.origin();
      this.targetCenter = this.execCenter;
      this.targetWidth = Math.max(0.6F, event.radius());
      this.targetHeight = Math.max(0.8F, event.scale());
      if (event.entityIds().length > 0) {
         this.targetEntityId = event.entityIds()[0];
      }

      Vec3 traceDir = TerminusEchoPattern.safeNormalize(this.execCenter.subtract(this.mirrorCenter), event.direction());
      this.ghostStartChest = TerminusEchoPattern.resolveGhostChest(this.execCenter, traceDir, this.targetWidth, this.seed);
      this.slashForward = TerminusEchoPattern.safeNormalize(event.direction(), this.execCenter.subtract(this.ghostStartChest));
      double dashPass = Math.max(2.1, this.targetWidth * 0.9 + 1.55);
      this.ghostEndChest = this.execCenter.add(this.slashForward.scale(dashPass));

      Vec3[] slashPlane = buildPlaneBasis(this.slashForward);
      double angle = (0.48 + this.random.nextDouble() * 0.34) * (this.random.nextBoolean() ? 1.0 : -1.0);
      this.slashDir = TerminusEchoPattern.safeNormalize(
         slashPlane[0].scale(Math.cos(angle)).add(slashPlane[1].scale(Math.sin(angle))), slashPlane[0]
      );
      Vec3 crossNormal = this.slashDir.cross(this.slashForward);
      this.slashNormal = TerminusEchoPattern.safeNormalize(crossNormal, slashPlane[1]);

      double altAngle = angle + (angle > 0.0 ? -1.18 : 1.18);
      this.slashAltDir = TerminusEchoPattern.safeNormalize(
         slashPlane[0].scale(Math.cos(altAngle)).add(slashPlane[1].scale(Math.sin(altAngle))), slashPlane[1]
      );

      this.slashLength = (float)Math.max(2.8, this.targetHeight * 1.85) * this.scale;
      Vec3 half = this.slashDir.scale(this.slashLength * 0.5);
      this.slashA = this.execCenter.subtract(half);
      this.slashB = this.execCenter.add(half);
      Vec3 altHalf = this.slashAltDir.scale(this.slashLength * 0.42);
      this.slashAltA = this.execCenter.subtract(altHalf);
      this.slashAltB = this.execCenter.add(altHalf);
      this.slipSign = this.random.nextBoolean() ? 1.0F : -1.0F;

      for (int i = 0; i < SLASH_SPARKS; i++) {
         double along = (i % 2 == 0 ? 1.0 : -0.85) * (0.20 + this.random.nextDouble() * 0.28);
         double spread = (this.random.nextDouble() - 0.5) * 0.22;
         double up = 0.04 + this.random.nextDouble() * 0.14;
         this.slashSparkVel[i] = this.slashDir.scale(along).add(this.slashNormal.scale(spread)).add(0.0, up, 0.0);
      }
      for (int i = 0; i < SLASH_SHARDS; i++) {
         double side = i % 2 == 0 ? 1.0 : -1.0;
         this.slashShardVel[i] = this.slashNormal.scale(side * (0.09 + this.random.nextDouble() * 0.12))
            .add(this.slashDir.scale((this.random.nextDouble() - 0.5) * 0.16))
            .add(0.0, 0.03 + this.random.nextDouble() * 0.07, 0.0);
         this.slashShardSpin[i] = (this.random.nextFloat() - 0.5F) * 1.1F;
         this.slashShardSize[i] = (0.09F + this.random.nextFloat() * 0.10F) * this.scale;
      }
      for (int i = 0; i < DASH_LINES; i++) {
         double ang = (i + this.random.nextDouble() * 0.5) / DASH_LINES * Math.PI * 2.0;
         double rad = 0.28 + this.random.nextDouble() * 0.58;
         this.dashOffsets[i] = slashPlane[0].scale(Math.cos(ang) * rad).add(slashPlane[1].scale(Math.sin(ang) * rad));
         this.dashPhases[i] = this.random.nextFloat();
      }

      // 趁服务端尚未删除实体（先发包再 executeSilent），立刻抓拍目标 3D 骨骼剪影并按斜刀面拆分上下半身
      Entity rawTarget = this.targetEntityId >= 0 ? level.getEntity(this.targetEntityId) : null;
      if (rawTarget instanceof LivingEntity living) {
         float[] captured = AfterimageCapture.captureLiving(living);
         if (captured != null && captured.length >= 12) {
            this.targetFeet = living.position();
            this.targetQuads = captured;
            int faces = captured.length / 12;
            this.targetSides = new float[faces];
            for (int f = 0; f < faces; f++) {
               int idx = f * 12;
               double cx = this.targetFeet.x + (captured[idx] + captured[idx + 3] + captured[idx + 6] + captured[idx + 9]) * 0.25;
               double cy = this.targetFeet.y + (captured[idx + 1] + captured[idx + 4] + captured[idx + 7] + captured[idx + 10]) * 0.25;
               double cz = this.targetFeet.z + (captured[idx + 2] + captured[idx + 5] + captured[idx + 8] + captured[idx + 11]) * 0.25;
               double sideDot = (cx - this.execCenter.x) * this.slashNormal.x
                  + (cy - this.execCenter.y) * this.slashNormal.y
                  + (cz - this.execCenter.z) * this.slashNormal.z;
               this.targetSides[f] = sideDot >= 0.0 ? 1.0F : -1.0F;
            }
         }
      }

      // 同步用最终姿态补拍玩家收刃残影
      this.ghostCaptureTried = false;
      this.capturePlayerGhosts(level);

      // 确保完整播完 30 tick 的处决余韵与镜碎影散
      this.lifetime = Math.max(this.lifetime, (int)Math.ceil(atAge) + 30);
   }

   // ───────────────────────────── 焦点、逻辑 tick 与音效 ─────────────────────────────

   @Override
   public Vec3 focus() {
      if (!this.hasMirror || this.deflectOnly) {
         return this.mirrorCenter;
      }
      return this.mirrorCenter.lerp(this.targetCenter, 0.5);
   }

   @Override
   public double cullDistance() {
      if (this.deflectOnly) {
         return 96.0;
      }
      return this.mirrorCenter.distanceTo(this.targetCenter) + 176.0;
   }

   @Override
   public void tick(ClientLevel level) {
      int a = this.ageTicks();
      if (!this.deflectOnly && !this.executed && this.abortedAt < 0) {
         this.syncTrackedTarget(level);
         this.updateGhostPlacement();
         if (a >= this.sustainDuration) {
            this.abortedAt = a;
            this.lifetime = Math.min(this.lifetime, a + TerminusEchoPattern.ABORT_FADE_TICKS);
         }
      }
      if (!this.deflectOnly) {
         this.capturePlayerGhosts(level);
      }
      this.playTimelineSounds(level, a);
   }

   private void syncTrackedTarget(ClientLevel level) {
      if (this.targetEntityId < 0) {
         return;
      }
      Entity entity = level.getEntity(this.targetEntityId);
      if (entity != null && entity.isAlive()) {
         this.targetCenter = entity.getBoundingBox().getCenter();
         this.targetWidth = Math.max(0.6F, entity.getBbWidth());
         this.targetHeight = Math.max(0.8F, entity.getBbHeight());
      }
   }

   private void updateGhostPlacement() {
      Vec3 traceDir = TerminusEchoPattern.safeNormalize(this.targetCenter.subtract(this.mirrorCenter), this.mirrorNormal);
      this.ghostStartChest = TerminusEchoPattern.resolveGhostChest(this.targetCenter, traceDir, this.targetWidth, this.seed);
      Vec3 face = TerminusEchoPattern.safeNormalize(this.targetCenter.subtract(this.ghostStartChest), traceDir);
      this.ghostEndChest = this.targetCenter.add(face.scale(Math.max(2.1, this.targetWidth * 0.9 + 1.55)));
   }

   private void capturePlayerGhosts(ClientLevel level) {
      if (this.ghostCaptureTried || this.ghostStartChest == null) {
         return;
      }
      Entity caster = level.getEntity(this.casterId);
      if (!(caster instanceof AbstractClientPlayer player)) {
         return;
      }
      this.ghostCaptureTried = true;
      this.ghostHeight = player.getBbHeight();
      Vec3 face = TerminusEchoPattern.safeNormalize(this.targetCenter.subtract(this.ghostStartChest), this.mirrorNormal);
      float yaw = yawOf(face);
      float pitch = pitchOf(face);
      this.ghostReadyQuads = AfterimageCapture.capture(player, yaw, pitch, 0.14F);
      this.ghostFinishQuads = AfterimageCapture.capture(player, yaw, pitch, 0.68F);
   }

   private void playTimelineSounds(ClientLevel level, int a) {
      // 第一幕：绝对偏折晶鸣
      if (a == 0 && this.hasMirror) {
         play(level, this.mirrorCenter, SoundEvents.AMETHYST_BLOCK_CHIME, 0.95F, this.projectileIntercepted ? 1.55F : 1.35F);
         play(level, this.mirrorCenter, SoundEvents.SHIELD_BLOCK, 0.65F, 1.45F);
         if (this.projectileIntercepted) {
            play(level, this.mirrorCenter, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.75F, 1.75F);
         }
      }
      if (this.deflectOnly || this.abortedAt >= 0) {
         if (this.deflectOnly && a == 10) {
            play(level, this.mirrorCenter, SoundEvents.AMETHYST_BLOCK_BREAK, 0.38F, 1.7F);
         }
         return;
      }
      // 第二幕：因果溯源锁定
      if (a == TerminusEchoPattern.LOCK_TICK) {
         play(level, this.targetCenter, SoundEvents.END_PORTAL_FRAME_FILL, 0.65F, 1.65F);
      }
      // 第三幕：黑金虚影蓄刃扣鞘
      if (a == TerminusEchoPattern.EXECUTE_TICK - 3 && this.ghostStartChest != null) {
         play(level, this.ghostStartChest, SoundEvents.CROSSBOW_LOADING_END, 0.75F, 1.45F);
      }
      // 第四幕：因果反斩一闪
      if (this.executed) {
         int execTick = Math.round(this.executeAge);
         if (a == execTick) {
            Vec3 at = this.execCenter != null ? this.execCenter : this.targetCenter;
            play(level, at, SoundEvents.PLAYER_ATTACK_SWEEP, 1.15F, 0.78F);
            play(level, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.85F, 1.50F);
            play(level, at, SoundEvents.TRIDENT_RIPTIDE_1, 0.75F, 1.68F);
         } else if (a == execTick + 8 && this.hasMirror) {
            // 第五幕：镜碎余音
            play(level, this.mirrorCenter, SoundEvents.AMETHYST_BLOCK_BREAK, 0.55F, 1.55F);
         }
      }
   }

   private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
      level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int a = this.ageTicks();
      if (a == 0 && this.hasMirror) {
         for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI * 0.25;
            Vec3 p = this.mirrorCenter.add(this.mirrorRight.scale(Math.cos(ang) * 0.8)).add(this.mirrorUp.scale(Math.sin(ang) * 0.8));
            level.addParticle(ParticleTypes.ENCHANTED_HIT, p.x, p.y, p.z, 0.0, 0.0, 0.0);
         }
      }
      if (!this.deflectOnly && this.abortedAt < 0 && a >= TerminusEchoPattern.TRACE_START_TICK && a <= TerminusEchoPattern.LOCK_TICK) {
         Vec3 ctrl = TerminusEchoPattern.arcControlPoint(this.mirrorCenter, this.targetCenter, this.seed);
         float t = TerminusEchoPattern.traceProgress(a);
         Vec3 p = TerminusEchoPattern.bezierPoint(this.mirrorCenter, ctrl, this.targetCenter, t);
         level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0.0, 0.0, 0.0);
      }
      if (this.executed && a == Math.round(this.executeAge)) {
         Vec3 at = this.execCenter != null ? this.execCenter : this.targetCenter;
         level.addParticle(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 0.0, 0.0, 0.0);
         for (int i = 0; i < 6; i++) {
            level.addParticle(ParticleTypes.CRIT, at.x, at.y, at.z, (this.random.nextDouble() - 0.5) * 0.4, 0.15, (this.random.nextDouble() - 0.5) * 0.4);
         }
      }
   }

   // ───────────────────────────── 本体暗部层（高反差玄黑衬底） ─────────────────────────────

   @Override
   public boolean hasBody() {
      return true;
   }

   @Override
   public void renderBody(FxGeometry b, FxFrame frame) {
      float t = this.age(frame.partialTick);
      float abortMask = this.abortFade(t);
      if (abortMask <= 0.001F) {
         return;
      }

      if (this.hasMirror) {
         this.renderMirrorBody(b, frame, t, abortMask);
      }
      if (this.deflectOnly) {
         b.end();
         return;
      }
      this.renderTargetSealBody(b, frame, t, abortMask);
      this.renderGhostBody(b, frame, t, abortMask);
      if (this.executed) {
         this.renderExecutionBody(b, frame, t);
      }
      b.end();
   }

   private void renderMirrorBody(FxGeometry b, FxFrame frame, float t, float abortMask) {
      float shatterAt = this.mirrorShatterTime();
      float coreMask = this.mirrorCoreVisibility(frame);
      float rimMask = this.mirrorRimVisibility(frame);
      if (rimMask <= 0.01F) {
         return;
      }
      float viewScale = this.mirrorScaleForView(frame);
      Vector3f center = frame.rel(this.mirrorCenter);
      if (t < shatterAt) {
         float open = backOut(FxMath.window(t, 0.0F, 2.6F));
         float radius = (this.deflectOnly ? 0.92F : 1.24F) * this.scale * open * viewScale;
         float alpha = (this.deflectOnly ? 0.48F : 0.62F) * abortMask * coreMask * FxMath.window(t, 0.0F, 1.0F);
         // 6 片三角形（退化四边形）拼合正六边形玄黑镜底（dissolve 设负值确保零噪点孔洞）
         if (alpha > 0.01F) {
            b.begin(Style.AFTERIMAGE, 0.95F, 0.08F, 0.8F, 0.72F, 0.0F, -0.05F);
            Vector3f[] hex = hexVertices(center, this.mirrorRight, this.mirrorUp, radius, 0.0F);
            for (int i = 0; i < 6; i++) {
               Vector3f v1 = hex[i];
               Vector3f v2 = hex[(i + 1) % 6];
               b.rawQuad(center, v1, v2, center, OBSIDIAN[0], OBSIDIAN[1], OBSIDIAN[2], alpha);
            }
         }
         // 受击瞬间向敌人来向推出的 3D 弹反气浪锥暗金衬底
         if (t < 6.2F) {
            float ck = FxMath.window(t, 0.0F, 6.2F);
            float coneAlpha = (1.0F - ck) * (1.0F - ck) * 0.52F * abortMask * rimMask;
            if (coneAlpha > 0.01F) {
               float push = FxMath.easeOutCubic(ck);
               Vector3f n = toVec3f(this.mirrorNormal);
               Vector3f base = new Vector3f(center).fma(0.08F * this.scale * viewScale, n);
               Vector3f top = new Vector3f(center).fma((0.32F + 1.50F * push) * this.scale * viewScale, n);
               b.begin(Style.SHOCK_CONE, 0.85F, 0.12F, push, 2.6F, 0.55F, 0.45F);
               b.cylinder(base, top, 0.22F * this.scale * viewScale, (0.42F + 1.12F * push) * this.scale * viewScale, 20, t * 0.28F,
                  DEEP_BRONZE[0], DEEP_BRONZE[1], DEEP_BRONZE[2], coneAlpha);
            }
         }
         // 弹射物吞噬瞬间中心漆黑蚀核
         if (this.projectileIntercepted && t < 6.5F) {
            float coreK = FxMath.envelope(FxMath.window(t, 0.0F, 6.5F), 0.18F, 0.65F);
            b.geoCenter(center);
            b.begin(Style.CORONA, 0.95F, 0.05F, 1.8F, 1.1F, 2.0F, 0.6F);
            b.sphere(center, 0.34F * this.scale * coreK * viewScale, 10, 14, t * 0.25F, OBSIDIAN[0], OBSIDIAN[1], OBSIDIAN[2], 0.92F * coreK * coreMask);
            b.clearGeoCenter();
         }
      } else {
         float e = t - shatterAt;
         float life = this.deflectOnly ? 6.5F : 13.0F;
         if (e < life) {
            float k = 1.0F - e / life;
            b.begin(Style.CRYSTAL_SHARD, 0.9F, 0.0F, 1.8F, 0.8F, 0.2F, 1.0F - k);
            this.drawMirrorShards(b, frame, e, k, 0.85F * k * rimMask * abortMask, true);
         }
      }
   }

   private void renderTargetSealBody(FxGeometry b, FxFrame frame, float t, float abortMask) {
      if (t < TerminusEchoPattern.LOCK_TICK - 0.5F) {
         return;
      }
      float endT = this.executed ? this.executeAge + 7.0F : TerminusEchoPattern.SHATTER_START_TICK;
      if (t >= endT) {
         return;
      }
      float in = FxMath.easeOutCubic(FxMath.window(t, TerminusEchoPattern.LOCK_TICK - 0.5F, TerminusEchoPattern.LOCK_TICK + 2.0F));
      float out = 1.0F - FxMath.window(t, endT - 4.5F, endT);
      float alpha = 0.52F * in * out * abortMask;
      if (alpha <= 0.01F) {
         return;
      }
      Vec3 feet = this.targetCenter.subtract(0.0, this.targetHeight * 0.5 - 0.06, 0.0);
      Vector3f rel = frame.rel(feet);
      float rad = Math.max(0.95F, this.targetWidth * 0.95F) * this.scale * (0.65F + 0.35F * in);
      b.begin(Style.GLOW, 0.95F, 0.15F, 1.6F, 2.2F, 0.0F, 0.0F);
      b.quad(rel, new Vector3f(rad, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, rad), OBSIDIAN[0], OBSIDIAN[1], OBSIDIAN[2], alpha * nearFade(rel));
   }

   private void renderGhostBody(FxGeometry b, FxFrame frame, float t, float abortMask) {
      if (this.ghostStartChest == null || t < TerminusEchoPattern.GHOST_APPEAR_TICK - 0.5F) {
         return;
      }
      // 虚影降临处的玄黑虚空裂口
      float slitEnd = this.executed ? this.executeAge + 5.0F : TerminusEchoPattern.EXECUTE_TICK + 2.0F;
      if (t < slitEnd) {
         float open = FxMath.envelope(FxMath.window(t, TerminusEchoPattern.GHOST_APPEAR_TICK - 0.5F, slitEnd), 0.16F, 0.35F);
         if (open > 0.01F) {
            Vector3f c = frame.rel(this.ghostStartChest);
            Vector3f u = sideHorizontal(c, frame).mul(0.55F * this.scale);
            Vector3f v = new Vector3f(0.0F, 1.25F * this.scale, 0.0F);
            b.begin(Style.VOID_SLIT, 1.0F, 0.0F, open * 0.82F, 0.65F, 1.0F, 0.0F);
            b.quad(c, u, v, 1.0F, 1.0F, 1.0F, open * abortMask * nearFade(c));
         }
      }

      // 玩家黑金剑影暗影衬底
      float ghostFade = this.ghostAlpha(t) * abortMask;
      float dissolve = this.ghostDissolve(t);
      if (ghostFade > 0.01F && dissolve < 0.99F) {
         Vec3 chest = this.currentGhostChest(t);
         float[] quads = this.activeGhostQuads(t);
         if (quads != null) {
            b.begin(Style.AFTERIMAGE, 0.85F, 0.08F, 1.0F, 0.16F, 0.0F, dissolve);
            this.drawPlayerGhost(b, frame, chest, quads, ghostFade * 0.82F, true);
         }
         // 蓄压期的黑曜太刀晶核
         if (!this.executed || t < this.executeAge + 1.0F) {
            this.drawGhostBlade(b, frame, chest, t, ghostFade * 0.92F, true);
         }
      }
   }

   private void renderExecutionBody(FxGeometry b, FxFrame frame, float t) {
      float post = t - this.executeAge;
      if (post < 0.0F || this.execCenter == null) {
         return;
      }
      // 1. 交叉 3D 暗芯曲面刀弧衬底
      if (post < 10.5F) {
         float arcR = Math.max(1.8F, this.targetHeight * 1.15F) * this.scale;
         float head = FxMath.easeOutCubic(FxMath.window(post, 0.0F, 2.2F));
         float tail = FxMath.easeInCubic(FxMath.window(post, 3.0F, 10.5F)) * 0.92F;
         float a1 = (1.0F - FxMath.window(post, 5.5F, 10.5F)) * 0.90F;
         if (a1 > 0.01F) {
            Vector3f c = frame.rel(this.execCenter);
            Vector3f u = toVec3f(this.slashDir);
            Vector3f v = toVec3f(this.slashForward);
            b.begin(Style.SLASH_ARC, 0.45F, 0.15F, 6.5F, 0.85F, head, tail);
            b.slashArc(c, u, v, arcR * 0.42F, arcR * 1.24F, -1.35F, 1.35F, 24, OBSIDIAN[0], OBSIDIAN[1], OBSIDIAN[2], a1 * cutFade(c));
         }
         float post2 = post - 1.1F;
         if (post2 >= 0.0F && post2 < 9.5F) {
            float head2 = FxMath.easeOutCubic(FxMath.window(post2, 0.0F, 2.2F));
            float tail2 = FxMath.easeInCubic(FxMath.window(post2, 2.8F, 9.5F)) * 0.92F;
            float a2 = (1.0F - FxMath.window(post2, 4.8F, 9.5F)) * 0.80F;
            if (a2 > 0.01F) {
               Vector3f c = frame.rel(this.execCenter);
               Vector3f u = toVec3f(this.slashAltDir);
               Vector3f v = toVec3f(this.slashForward);
               b.begin(Style.SLASH_ARC, 0.45F, 0.15F, 6.5F, 0.85F, head2, tail2);
               b.slashArc(c, u, v, arcR * 0.36F, arcR * 1.06F, -1.25F, 1.25F, 22, DEEP_BRONZE[0], DEEP_BRONZE[1], DEEP_BRONZE[2], a2 * cutFade(c));
            }
         }
      }

      // 2. 穿透目标胸口的暗黑斩缝 + 原位缓缓闭合的立式黑金残缝暗腔
      if (post < 12.0F) {
         float slitOpen = FxMath.window(post, 0.0F, 1.2F) * (1.0F - FxMath.window(post, 6.5F, 12.0F));
         if (slitOpen > 0.01F) {
            b.begin(Style.RIBBON, 0.95F, 0.12F, 5.0F, 0.0F, 0.0F, 0.25F);
            this.drawLine(b, frame, this.slashA, this.slashB, 0.16F * this.scale * slitOpen, 0.88F * slitOpen, OBSIDIAN, true);
         }
      }
      if (post >= 0.5F && post < 18.0F) {
         float scarOpen = FxMath.envelope(FxMath.window(post, 0.5F, 18.0F), 0.10F, 0.55F);
         if (scarOpen > 0.01F) {
            Vector3f c = frame.rel(this.execCenter);
            Vector3f u = sideHorizontal(c, frame).mul(0.36F * this.scale);
            Vector3f v = new Vector3f(0.0F, Math.max(1.05F, this.targetHeight * 0.68F) * this.scale, 0.0F);
            b.begin(Style.VOID_SLIT, 1.0F, 0.0F, scarOpen * 0.62F, 0.72F, 1.0F, 0.0F);
            b.quad(c, u, v, 1.0F, 1.0F, 1.0F, scarOpen * cutFade(c));
         }
      }

      // 3. 目标 3D 剪影暗部衬底（与断空同级双截错位）
      if (this.targetQuads != null && post < 15.0F) {
         float dissolve = FxMath.window(post, 6.5F, 14.5F);
         if (dissolve < 0.99F) {
            float splitK = FxMath.easeOutCubic(FxMath.window(post, 2.2F, 6.5F));
            b.begin(Style.AFTERIMAGE, 0.82F, 0.08F, 1.0F, 0.12F, 0.0F, dissolve);
            this.drawTargetSplit(b, frame, 0.04F + 0.42F * splitK, 0.03F + 0.56F * splitK, 0.82F * (1.0F - dissolve * 0.5F), OBSIDIAN, OBSIDIAN);
         }
      }
   }

   // ───────────────────────────── HDR 网格发光层 ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      float t = this.age(frame.partialTick);
      float abortMask = this.abortFade(t);
      if (abortMask <= 0.001F) {
         return;
      }

      if (this.hasMirror) {
         this.renderMirrorGeometry(g, frame, t, abortMask);
      }
      if (this.deflectOnly) {
         g.end();
         return;
      }
      this.renderTraceGeometry(g, frame, t, abortMask);
      this.renderTargetSealGeometry(g, frame, t, abortMask);
      this.renderGhostGeometry(g, frame, t, abortMask);
      if (this.executed) {
         this.renderExecutionGeometry(g, frame, t);
      }
      g.end();
   }

   private void renderMirrorGeometry(FxGeometry g, FxFrame frame, float t, float abortMask) {
      float shatterAt = this.mirrorShatterTime();
      float coreMask = this.mirrorCoreVisibility(frame);
      float rimMask = this.mirrorRimVisibility(frame);
      if (rimMask <= 0.01F) {
         return;
      }
      float viewScale = this.mirrorScaleForView(frame);
      Vector3f center = frame.rel(this.mirrorCenter);
      Vector3f rAxis = toVec3f(this.mirrorRight);
      Vector3f uAxis = toVec3f(this.mirrorUp);
      Vector3f nAxis = toVec3f(this.mirrorNormal);

      if (t < shatterAt) {
         float open = backOut(FxMath.window(t, 0.0F, 2.6F));
         float baseRadius = (this.deflectOnly ? 0.92F : 1.24F) * this.scale * open * viewScale;
         float inMask = abortMask * FxMath.window(t, 0.0F, 0.9F);
         float coreFade = inMask * coreMask;
         float rimFade = inMask * rimMask;
         // 临近碎裂前过载闪烁预告
         float preShatter = FxMath.window(t, shatterAt - 2.5F, shatterAt);

         // 1. 内嵌六芒律印（中心采用 coreFade 保持第一人称视野通透不糊准星）
         float runeReveal = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 3.6F));
         float runeAlpha = coreFade * 0.78F * (1.0F + 0.35F * preShatter);
         if (runeAlpha > 0.01F) {
            g.begin(Style.RUNE, 2.9F, 0.12F, t * 0.055F, (float)(this.seed & 255L), 0.0F, runeReveal);
            g.quad(center, new Vector3f(rAxis).mul(baseRadius * 0.92F), new Vector3f(uAxis).mul(baseRadius * 0.92F),
               AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], runeAlpha);
         }

         // 2. 双层反旋虚线刻度环 + 倾斜 18° 立体 3D torus 机械律法环（外缘采用 rimFade 保持锋利高对比）
         g.begin(Style.RING, 3.6F, 0.10F, 0.94F, 0.024F, 24.0F, 0.55F);
         Vector3f frontPos = new Vector3f(center).fma(0.10F * this.scale * viewScale, nAxis);
         g.quad(frontPos, new Vector3f(rAxis).mul(baseRadius * 1.12F), new Vector3f(uAxis).mul(baseRadius * 1.12F),
            PALE_GOLD[0], PALE_GOLD[1], PALE_GOLD[2], rimFade * 0.88F);
         g.begin(Style.RING, 3.0F, 0.10F, 0.95F, 0.018F, 36.0F, -0.42F);
         Vector3f backPos = new Vector3f(center).fma(-0.08F * this.scale * viewScale, nAxis);
         g.quad(backPos, new Vector3f(rAxis).mul(baseRadius * 1.24F), new Vector3f(uAxis).mul(baseRadius * 1.24F),
            FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2], rimFade * 0.68F);

         // 倾斜进动的 3D torus 外环，补足侧面视角的立体厚度
         float precess = t * 0.11F;
         Vector3f tiltAxis = new Vector3f(nAxis).mul(0.951F)
            .fma(0.309F * Mth.cos(precess), rAxis)
            .fma(0.309F * Mth.sin(precess), uAxis)
            .normalize();
         g.begin(Style.RIBBON, 3.8F + 1.8F * preShatter, 0.08F, 7.0F, 0.0F, 0.0F, 0.35F);
         g.torus(center, tiltAxis, baseRadius * 1.18F, 0.016F * this.scale * viewScale, 32, 6, t * 0.22F,
            AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], rimFade * 0.72F);

         // 3. 六棱锋锐镜框（双层：琥珀金外框 + 白金细芯）
         Vector3f[] hex = hexVertices(center, this.mirrorRight, this.mirrorUp, baseRadius, 0.0F);
         g.begin(Style.RIBBON, 5.2F + 2.6F * preShatter, 0.08F, 7.5F, 0.0F, 0.0F, 0.35F);
         for (int i = 0; i < 6; i++) {
            Vector3f a = hex[i];
            Vector3f bPt = hex[(i + 1) % 6];
            List<Vector3f> seg = List.of(a, bPt);
            float w = 0.038F * this.scale * viewScale;
            g.ribbon(seg, new float[]{w, w}, new float[]{rimFade * 0.94F, rimFade * 0.94F}, AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2]);
            float wInner = 0.015F * this.scale * viewScale;
            g.ribbon(seg, new float[]{wInner, wInner}, new float[]{rimFade, rimFade}, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2]);
         }

         // 4. 六枚顶点 3D 棱晶爪扣（bladeShard 沿六角径向外翘，严格正交上脊向量）
         g.begin(Style.CRYSTAL_SHARD, 4.0F, 0.05F, 1.9F, 1.2F, 0.38F + 0.55F * preShatter, 0.0F);
         for (int i = 0; i < 6; i++) {
            float ang = (float)(i * Math.PI / 3.0);
            Vector3f radial = new Vector3f(rAxis).mul(Mth.cos(ang)).fma(Mth.sin(ang), uAxis).normalize();
            Vector3f tangent = new Vector3f(nAxis).cross(radial).normalize();
            Vector3f forward = new Vector3f(radial).mul(0.88F).fma(0.47F, nAxis).normalize();
            Vector3f shardUp = new Vector3f(tangent).cross(forward).normalize();
            Vector3f pos = new Vector3f(hex[i]).fma(0.05F * this.scale * viewScale, radial);
            float s = 0.25F * this.scale * viewScale;
            g.bladeShard(pos, forward, tangent, shardUp, s * 1.15F, s * 0.65F, s * 0.26F, s * 0.14F,
               i % 2 == 0 ? PALE_GOLD[0] : FROST_CYAN[0],
               i % 2 == 0 ? PALE_GOLD[1] : FROST_CYAN[1],
               i % 2 == 0 ? PALE_GOLD[2] : FROST_CYAN[2],
               rimFade * 0.95F);
         }
      } else {
         // 第五幕：12 枚真 3D 黑金镜片缓慢旋转崩散
         float e = t - shatterAt;
         float life = this.deflectOnly ? 6.5F : 13.0F;
         if (e < life) {
            float k = 1.0F - e / life;
            g.begin(Style.CRYSTAL_SHARD, 4.6F, 0.0F, 1.8F, 1.4F, 0.45F, 1.0F - k);
            this.drawMirrorShards(g, frame, e, k, k * rimMask * abortMask, false);
         }
      }

      // 5. 受击瞬间沿来向朝外推出的 3D 喇叭形弹反激波锥（Style.SHOCK_CONE，向外破风绝不挡准星）
      if (t < 6.2F) {
         float ck = FxMath.window(t, 0.0F, 6.2F);
         float push = FxMath.easeOutCubic(ck);
         float coneAlpha = (1.0F - ck) * (1.0F - ck) * abortMask * rimMask;
         Vector3f base = new Vector3f(center).fma(0.08F * this.scale * viewScale, nAxis);
         Vector3f top = new Vector3f(center).fma((0.32F + 1.55F * push) * this.scale * viewScale, nAxis);
         g.begin(Style.SHOCK_CONE, 4.6F, 0.12F, push, 3.0F, 0.65F, 0.72F);
         g.cylinder(base, top, 0.22F * this.scale * viewScale, (0.44F + 1.18F * push) * this.scale * viewScale, 24, t * 0.35F,
            AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], coneAlpha * 0.88F);
         Vector3f topInner = new Vector3f(center).fma((0.26F + 1.18F * push) * this.scale * viewScale, nAxis);
         g.cylinder(base, topInner, 0.16F * this.scale * viewScale, (0.30F + 0.82F * push) * this.scale * viewScale, 20, -t * 0.45F,
            FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2], coneAlpha * 0.65F);
      }

      // 6. 撞击点瞬时白金十字星闪 + 弹射物吞噬冲击环 + 16 道折返火星
      if (t < 6.0F) {
         float k = 1.0F - t / 6.0F;
         float flashScale = (this.isLocalFirstPerson(frame) ? 0.58F : 1.0F) * this.scale;
         g.begin(Style.GLOW, 8.8F, 0.0F, 6.0F, 3.0F, 1.25F, 0.0F);
         g.billboard(center, 0.95F * flashScale * (0.4F + 0.6F * k), 0.0F, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], k * k * coreMask * abortMask * 1.25F);
         g.billboard(center, 0.72F * flashScale * (0.4F + 0.6F * k), (float)(Math.PI * 0.5), FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2], k * k * 0.82F * rimMask * abortMask);
      }
      if (this.projectileIntercepted && t < 8.5F) {
         float k1 = FxMath.window(t, 0.0F, 7.0F);
         float k2 = FxMath.window(t, 1.2F, 8.5F);
         g.begin(Style.RIBBON, 4.8F, 0.08F, 7.0F, 0.0F, 0.0F, 0.4F);
         if (k1 < 1.0F) {
            float r1 = (0.20F + 1.55F * FxMath.easeOutCubic(k1)) * this.scale * viewScale;
            g.torus(center, nAxis, r1, 0.024F * this.scale * (1.0F - k1), 24, 6, t * 0.2F, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], (1.0F - k1) * rimMask * abortMask);
         }
         if (k2 > 0.0F && k2 < 1.0F) {
            float r2 = (0.28F + 1.95F * FxMath.easeOutCubic(k2)) * this.scale * viewScale;
            g.torus(center, nAxis, r2, 0.020F * this.scale * (1.0F - k2), 24, 6, -t * 0.25F, FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2], (1.0F - k2) * 0.85F * rimMask * abortMask);
         }
      }
      if (t < 9.0F) {
         float k = 1.0F - t / 9.0F;
         g.begin(Style.RIBBON, 5.4F, 0.08F, 6.5F, 0.0F, 0.0F, 0.35F);
         for (int i = 0; i < DEFLECT_SPARKS; i++) {
            Vec3 head = this.mirrorCenter.add(this.deflectSparkVel[i].scale(t * (1.0 - t / 18.0))).add(0.0, -0.008 * t * t, 0.0);
            Vec3 tail = head.subtract(this.deflectSparkVel[i].scale(0.85));
            float[] tint = i % 3 == 0 ? FROST_CYAN : (i % 3 == 1 ? AMBER_GOLD : WHITE_GOLD);
            g.streak(frame.rel(head), frame.rel(tail), 0.024F * this.scale * viewScale, tint[0], tint[1], tint[2], k * k * rimMask * abortMask);
         }
      }
   }

   private void drawMirrorShards(FxGeometry g, FxFrame frame, float e, float lifeRemain, float alpha, boolean body) {
      float viewScale = this.mirrorScaleForView(frame);
      for (int i = 0; i < MIRROR_SHARDS; i++) {
         Vec3 pos = this.mirrorCenter
            .add(this.mirrorShardDir[i].scale(this.scale * viewScale))
            .add(this.mirrorShardVel[i].scale(e * (1.0 - e / 26.0)))
            .add(0.0, -0.006 * e * e, 0.0);
         Vector3f rel = frame.rel(pos);
         float spin = this.mirrorShardSpin[i] * e;
         float sz = this.mirrorShardSize[i] * viewScale * (0.45F + 0.55F * lifeRemain);
         float cs = Mth.cos(spin);
         float sn = Mth.sin(spin);
         Vector3f u = new Vector3f(toVec3f(this.mirrorRight)).mul(cs * sz).fma(sn * sz, toVec3f(this.mirrorUp));
         Vector3f v = new Vector3f(toVec3f(this.mirrorUp)).mul(cs * sz * 0.85F).fma(-sn * sz * 0.85F, toVec3f(this.mirrorRight));
         Vector3f n = new Vector3f(u).cross(v);
         if (n.lengthSquared() < 1.0E-6F) {
            n.set(toVec3f(this.mirrorNormal));
         } else {
            n.normalize();
         }
         float[] tint = body ? OBSIDIAN : (i % 3 == 0 ? FROST_CYAN : (i % 2 == 0 ? AMBER_GOLD : PALE_GOLD));
         g.shard(rel, u, v, n, sz * 0.22F, tint[0], tint[1], tint[2], alpha);
      }
   }

   private void renderTraceGeometry(FxGeometry g, FxFrame frame, float t, float abortMask) {
      if (!this.hasMirror || t < TerminusEchoPattern.TRACE_START_TICK) {
         return;
      }
      float head = TerminusEchoPattern.traceProgress(t);
      float tail = TerminusEchoPattern.traceRetract(t);
      if (head <= tail + 0.005F) {
         return;
      }
      Vec3 start = this.mirrorCenter;
      Vec3 end = this.executed && this.execCenter != null ? this.execCenter : this.targetCenter;
      Vec3 ctrl = TerminusEchoPattern.arcControlPoint(start, end, this.seed);
      double dist = start.distanceTo(end);
      float overallFade = abortMask * (1.0F - FxMath.window(t, TerminusEchoPattern.SHATTER_START_TICK + 8.0F, TerminusEchoPattern.SHATTER_START_TICK + 12.0F));
      if (overallFade <= 0.01F) {
         return;
      }

      List<Vector3f> corePts = new ArrayList<>(TRACE_SAMPLES);
      List<Vector3f> helixGold = new ArrayList<>(TRACE_SAMPLES);
      List<Vector3f> helixCyan = new ArrayList<>(TRACE_SAMPLES);
      float[] wCore = new float[TRACE_SAMPLES];
      float[] wGlow = new float[TRACE_SAMPLES];
      float[] wHelix = new float[TRACE_SAMPLES];
      float[] aCore = new float[TRACE_SAMPLES];
      float[] aHelix = new float[TRACE_SAMPLES];

      float spiralRadius = (float)Mth.clamp(dist * 0.018, 0.14, 0.42) * this.scale;
      float turns = (float)Mth.clamp(dist * 0.14, 2.0, 7.0);

      for (int i = 0; i < TRACE_SAMPLES; i++) {
         float local = (float)i / (TRACE_SAMPLES - 1);
         float u = FxMath.lerp(tail, head, local);
         Vec3 p = TerminusEchoPattern.bezierPoint(start, ctrl, end, u);
         Vec3 tangent = TerminusEchoPattern.bezierTangent(start, ctrl, end, u);
         Vec3[] basis = buildPlaneBasis(tangent);

         Vector3f rel = frame.rel(p);
         float distWidth = Math.max(1.0F, rel.length() * 0.032F);
         float env = (float)Math.sin(Math.PI * local);
         float nf = nearFade(rel);

         corePts.add(rel);
         wCore[i] = 0.026F * this.scale * distWidth * (0.45F + 0.55F * env);
         wGlow[i] = 0.095F * this.scale * distWidth * (0.40F + 0.60F * env);
         aCore[i] = overallFade * nf * (0.35F + 0.65F * env);

         float phase = u * turns * FxMath.TAU - t * 0.55F;
         float r = spiralRadius * (0.35F + 0.65F * env);
         Vec3 offA = basis[0].scale(Mth.cos(phase) * r).add(basis[1].scale(Mth.sin(phase) * r));
         Vec3 offB = basis[0].scale(Mth.cos(-phase + (float)Math.PI) * r * 0.82F).add(basis[1].scale(Mth.sin(-phase + (float)Math.PI) * r * 0.82F));
         helixGold.add(frame.rel(p.add(offA)));
         helixCyan.add(frame.rel(p.add(offB)));
         wHelix[i] = 0.018F * this.scale * distWidth;
         aHelix[i] = overallFade * nf * env * 0.80F;
      }

      // 处决瞬间整条因果轨同步脉冲过载，呼应防反两端的终局联动
      float execSurge = this.executed
         ? (1.0F - FxMath.window(t, this.executeAge, this.executeAge + 3.2F)) * FxMath.window(t, this.executeAge - 0.1F, this.executeAge + 0.3F)
         : 0.0F;
      float surgeWidth = 1.0F + 0.45F * execSurge;
      for (int i = 0; i < TRACE_SAMPLES; i++) {
         wCore[i] *= surgeWidth;
         wGlow[i] *= surgeWidth;
      }

      // 1. 因果律主轨：琥珀金柔鞘 + 白金锐芯
      g.begin(Style.RIBBON, 3.0F + 2.4F * execSurge, 0.15F, 6.0F, 0.0F, 0.0F, 0.40F);
      g.ribbon(corePts, wGlow, aCore, AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2]);
      g.begin(Style.RIBBON, 8.0F + 3.2F * execSurge, 0.12F, 8.5F, 0.0F, 0.0F, 0.25F);
      g.ribbon(corePts, wCore, aCore, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2]);

      // 2. 反旋双螺旋空间束缚丝（金 × 霜青）
      g.begin(Style.RIBBON, 4.2F + 1.8F * execSurge, 0.12F, 7.0F, 0.0F, 0.0F, 0.30F);
      g.ribbon(helixGold, wHelix, aHelix, PALE_GOLD[0], PALE_GOLD[1], PALE_GOLD[2]);
      g.ribbon(helixCyan, wHelix, aHelix, FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2]);

      // 3. 沿轨 4 座空间折跃刻度环（随波前依次点亮）
      g.begin(Style.RING, 3.8F, 0.10F, 0.92F, 0.045F, 12.0F, 0.7F);
      for (int i = 0; i < TRACE_RINGS; i++) {
         float frac = (i + 1.0F) / (TRACE_RINGS + 1.0F);
         if (head < frac || tail > frac) {
            continue;
         }
         float ringOpen = FxMath.easeOutCubic(FxMath.clamp01((head - frac) / 0.18F));
         Vec3 p = TerminusEchoPattern.bezierPoint(start, ctrl, end, frac);
         Vec3 tangent = TerminusEchoPattern.bezierTangent(start, ctrl, end, frac);
         Vec3[] basis = buildPlaneBasis(tangent);
         Vector3f rel = frame.rel(p);
         float rad = (0.34F + 0.18F * ringOpen + 0.14F * execSurge) * this.scale;
         float[] tint = i % 2 == 0 ? AMBER_GOLD : FROST_CYAN;
         g.quad(rel, toVec3f(basis[0]).mul(rad), toVec3f(basis[1]).mul(rad), tint[0], tint[1], tint[2], overallFade * ringOpen * nearFade(rel) * 0.85F);
      }

      // 4. 溯源波前四芒穿刺星矢（t = 2..8.5）
      if (t <= TerminusEchoPattern.LOCK_TICK + 2.0F) {
         Vec3 tip = TerminusEchoPattern.bezierPoint(start, ctrl, end, head);
         Vector3f rel = frame.rel(tip);
         float fadeTip = (1.0F - FxMath.window(t, TerminusEchoPattern.LOCK_TICK, TerminusEchoPattern.LOCK_TICK + 2.0F)) * abortMask;
         g.begin(Style.SPRITE, 8.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F);
         g.billboard(rel, 0.52F * this.scale, t * 0.45F, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], fadeTip * nearFade(rel));
      }

      // 5. 蓄刃期二次因果脉冲光弹灌注（两颗高速脉冲依次从偏折镜疾驰注入目标/虚影）
      this.renderTracePulse(g, frame, start, ctrl, end, t, 7.4F, 11.2F, AMBER_GOLD, overallFade);
      this.renderTracePulse(g, frame, start, ctrl, end, t, 10.2F, 13.8F, FROST_CYAN, overallFade);
   }

   private void renderTracePulse(
      FxGeometry g, FxFrame frame, Vec3 start, Vec3 ctrl, Vec3 end, float t, float tStart, float tEnd, float[] tint, float alpha
   ) {
      if (t < tStart || t > tEnd || alpha <= 0.01F) {
         return;
      }
      float u = FxMath.easeOutCubic((t - tStart) / (tEnd - tStart));
      float tailU = Math.max(0.0F, u - 0.18F);
      int segs = 6;
      List<Vector3f> pts = new ArrayList<>(segs);
      float[] ws = new float[segs];
      float[] as = new float[segs];
      for (int i = 0; i < segs; i++) {
         float k = (float)i / (segs - 1);
         Vec3 p = TerminusEchoPattern.bezierPoint(start, ctrl, end, FxMath.lerp(tailU, u, k));
         Vector3f rel = frame.rel(p);
         pts.add(rel);
         ws[i] = (0.012F + 0.044F * k) * this.scale * Math.max(1.0F, rel.length() * 0.03F);
         as[i] = alpha * k * k * nearFade(rel);
      }
      g.begin(Style.RIBBON, 7.5F, 0.08F, 8.0F, 0.0F, 0.0F, 0.35F);
      g.ribbon(pts, ws, as, tint[0], tint[1], tint[2]);

      Vector3f headRel = pts.get(segs - 1);
      float sz = 0.42F * this.scale * Math.max(1.0F, headRel.length() * 0.025F);
      g.begin(Style.SPRITE, 9.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F);
      g.billboard(headRel, sz, t * 0.7F, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], alpha * nearFade(headRel));
   }

   private void renderTargetSealGeometry(FxGeometry g, FxFrame frame, float t, float abortMask) {
      if (t < TerminusEchoPattern.LOCK_TICK - 0.5F) {
         return;
      }
      float endT = this.executed ? this.executeAge + 7.5F : TerminusEchoPattern.SHATTER_START_TICK;
      if (t >= endT) {
         return;
      }
      float in = FxMath.easeOutCubic(FxMath.window(t, TerminusEchoPattern.LOCK_TICK - 0.5F, TerminusEchoPattern.LOCK_TICK + 2.0F));
      float out = 1.0F - FxMath.window(t, endT - 4.0F, endT);
      float alpha = in * out * abortMask;
      if (alpha <= 0.01F) {
         return;
      }
      Vec3 centerPos = this.executed && this.execCenter != null ? this.execCenter : this.targetCenter;
      Vec3 feet = centerPos.subtract(0.0, this.targetHeight * 0.5 - 0.08, 0.0);
      Vector3f relFeet = frame.rel(feet);
      float rad = Math.max(0.95F, this.targetWidth * 1.02F) * this.scale;

      // 1. 脚下黑金六芒落款定身阵
      g.begin(Style.RUNE, 3.6F, 0.12F, -t * 0.08F, (float)((this.seed >> 8) & 255L), 0.0F, in);
      g.quad(relFeet, new Vector3f(rad, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, rad),
         AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], alpha * 0.88F * nearFade(relFeet));

      // 2. 钉死瞬间缩径白金锁环
      float lockAge = t - TerminusEchoPattern.LOCK_TICK;
      if (lockAge >= 0.0F && lockAge < 5.5F) {
         float k = lockAge / 5.5F;
         float ringR = (rad * 2.1F * (1.0F - FxMath.easeOutCubic(k)) + rad * 0.45F);
         Vector3f relCenter = frame.rel(centerPos);
         g.begin(Style.RING, 5.5F, 0.08F, 0.94F, 0.03F, 0.0F, 0.0F);
         g.quad(relCenter, new Vector3f(ringR, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, ringR),
            WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], (1.0F - k) * abortMask * nearFade(relCenter));
      }

      // 3. 胸口四向 3D 黑金因果卡尺（锁定目标核心）
      float tighten = this.executed
         ? 1.0F
         : FxMath.window(t, TerminusEchoPattern.LOCK_TICK, TerminusEchoPattern.EXECUTE_TICK);
      float caliperDist = Math.max(0.75F, this.targetWidth * 0.82F) * this.scale * (1.35F - 0.42F * tighten);
      Vector3f relCenter = frame.rel(centerPos);
      g.begin(Style.CRYSTAL_SHARD, 4.0F, 0.05F, 1.9F, 1.5F, 0.35F + 0.55F * tighten, 0.0F);
      for (int i = 0; i < 4; i++) {
         float ang = (float)(i * Math.PI * 0.5) + t * 0.07F;
         Vector3f radial = new Vector3f(Mth.cos(ang), 0.0F, Mth.sin(ang));
         Vector3f inward = new Vector3f(radial).negate();
         Vector3f side = new Vector3f(-radial.z, 0.0F, radial.x);
         Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
         Vector3f pos = new Vector3f(relCenter).fma(caliperDist, radial);
         float sz = 0.28F * this.scale;
         float[] tint = i % 2 == 0 ? AMBER_GOLD : FROST_CYAN;
         g.bladeShard(pos, inward, side, up, sz * 1.25F, sz * 0.55F, sz * 0.22F, sz * 0.14F, tint[0], tint[1], tint[2], alpha * 0.90F * nearFade(pos));
      }
   }

   private void renderGhostGeometry(FxGeometry g, FxFrame frame, float t, float abortMask) {
      if (this.ghostStartChest == null || t < TerminusEchoPattern.GHOST_APPEAR_TICK - 0.5F) {
         return;
      }

      // 1. 降临虚空缝鎏金刃口
      float slitEnd = this.executed ? this.executeAge + 5.0F : TerminusEchoPattern.EXECUTE_TICK + 2.0F;
      if (t < slitEnd) {
         float open = FxMath.envelope(FxMath.window(t, TerminusEchoPattern.GHOST_APPEAR_TICK - 0.5F, slitEnd), 0.16F, 0.35F);
         if (open > 0.01F) {
            Vector3f c = frame.rel(this.ghostStartChest);
            Vector3f u = sideHorizontal(c, frame).mul(0.55F * this.scale);
            Vector3f v = new Vector3f(0.0F, 1.25F * this.scale, 0.0F);
            g.begin(Style.VOID_SLIT, 5.2F, 0.0F, open * 0.82F, 0.65F, 1.0F, 0.25F);
            g.quad(c, u, v, AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], open * abortMask * nearFade(c));
         }
      }

      // 2. 玩家黑金 3D 全息剑影（穿斩瞬间带 3 帧延迟叠影）
      float ghostFade = this.ghostAlpha(t) * abortMask;
      float dissolve = this.ghostDissolve(t);
      if (ghostFade > 0.01F && dissolve < 0.99F) {
         float flare = this.executed
            ? (1.0F - FxMath.window(t, this.executeAge, this.executeAge + 3.5F))
            : FxMath.window(t, TerminusEchoPattern.EXECUTE_TICK - 3.0F, TerminusEchoPattern.EXECUTE_TICK);
         g.begin(Style.AFTERIMAGE, 2.8F + 2.6F * flare, 0.08F, 1.15F, 0.08F, 4.0F, dissolve);
         Vec3 mainChest = this.currentGhostChest(t);
         float[] mainQuads = this.activeGhostQuads(t);
         if (mainQuads != null) {
            this.drawPlayerGhost(g, frame, mainChest, mainQuads, ghostFade, false);
         }

         // 瞬步穿斩 3 帧叠影
         if (this.executed && this.ghostReadyQuads != null) {
            float post = t - this.executeAge;
            if (post >= 0.0F && post < 4.5F) {
               float trailFade = (1.0F - post / 4.5F) * 0.55F;
               for (int i = 1; i <= 3; i++) {
                  float backT = Math.max(0.0F, post - i * 0.55F);
                  float k = FxMath.easeOutCubic(FxMath.clamp01(backT / 2.2F));
                  Vec3 trailPos = this.ghostStartChest.lerp(this.ghostEndChest, k);
                  this.drawPlayerGhost(g, frame, trailPos, this.ghostReadyQuads, trailFade * (1.0F - i * 0.24F), false);
               }
            }
         }

         // 蓄压期的黑金棱晶刀影 + 十字收束金芒
         if (!this.executed || t < this.executeAge + 1.0F) {
            this.drawGhostBlade(g, frame, mainChest, t, ghostFade, false);
            float charge = FxMath.window(t, TerminusEchoPattern.EXECUTE_TICK - 3.5F, TerminusEchoPattern.EXECUTE_TICK);
            if (charge > 0.01F) {
               Vec3 bladeTip = this.ghostBladeTip(mainChest);
               Vector3f relTip = frame.rel(bladeTip);
               float sz = (0.35F + 0.55F * charge) * this.scale;
               g.begin(Style.GLOW, 9.5F, 0.0F, 6.5F, 3.2F, 1.6F, 0.0F);
               g.billboard(relTip, sz, 0.0F, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], charge * abortMask * nearFade(relTip));
               g.billboard(relTip, sz * 0.78F, (float)(Math.PI * 0.5), AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], charge * 0.85F * abortMask * nearFade(relTip));
            }
         }
      }
   }

   private void renderExecutionGeometry(FxGeometry g, FxFrame frame, float t) {
      float post = t - this.executeAge;
      if (post < 0.0F || this.execCenter == null) {
         return;
      }

      // 1. 穿刺瞬步高速速度线
      if (post < 4.5F && this.ghostStartChest != null && this.ghostEndChest != null) {
         float k = 1.0F - post / 4.5F;
         Vec3 span = this.ghostEndChest.subtract(this.ghostStartChest);
         double len = span.length();
         if (len > 0.4) {
            Vec3 unit = span.scale(1.0 / len);
            g.begin(Style.RIBBON, 5.2F, 0.1F, 6.5F, 0.0F, 0.0F, 0.35F);
            for (int i = 0; i < DASH_LINES; i++) {
               double travel = FxMath.clamp01(post / 2.0F + this.dashPhases[i] * 0.30F);
               Vec3 head = this.ghostStartChest.add(span.scale(travel)).add(this.dashOffsets[i]);
               Vec3 tail = head.subtract(unit.scale(Math.min(len * travel, 2.4 * this.scale)));
               float[] tint = i % 3 == 0 ? FROST_CYAN : (i % 2 == 0 ? AMBER_GOLD : WHITE_GOLD);
               Vector3f rel = frame.rel(head);
               g.streak(rel, frame.rel(tail), 0.022F * this.scale, tint[0], tint[1], tint[2], k * nearFade(rel));
            }
         }
      }

      // 2. 交叉 3D 曲面刀光弧（主鎏金弧 × 副霜青弧）
      if (post < 10.5F) {
         float arcR = Math.max(1.8F, this.targetHeight * 1.15F) * this.scale;
         float head = FxMath.easeOutCubic(FxMath.window(post, 0.0F, 2.2F));
         float tail = FxMath.easeInCubic(FxMath.window(post, 3.0F, 10.5F)) * 0.92F;
         float a1 = 1.0F - FxMath.window(post, 5.0F, 10.5F);
         if (a1 > 0.01F) {
            Vector3f c = frame.rel(this.execCenter);
            Vector3f u = toVec3f(this.slashDir);
            Vector3f v = toVec3f(this.slashForward);
            g.begin(Style.SLASH_ARC, 6.2F, 0.12F, 7.5F, 0.78F, head, tail);
            g.slashArc(c, u, v, arcR * 0.44F, arcR * 1.26F, -1.35F, 1.35F, 24, AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], a1 * cutFade(c));
            g.slashArc(c, u, v, arcR * 0.88F, arcR * 1.24F, -1.30F, 1.30F, 24, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], a1 * 0.85F * cutFade(c));
         }
         float post2 = post - 1.1F;
         if (post2 >= 0.0F && post2 < 9.5F) {
            float head2 = FxMath.easeOutCubic(FxMath.window(post2, 0.0F, 2.2F));
            float tail2 = FxMath.easeInCubic(FxMath.window(post2, 2.8F, 9.5F)) * 0.92F;
            float a2 = 1.0F - FxMath.window(post2, 4.5F, 9.5F);
            if (a2 > 0.01F) {
               Vector3f c = frame.rel(this.execCenter);
               Vector3f u = toVec3f(this.slashAltDir);
               Vector3f v = toVec3f(this.slashForward);
               g.begin(Style.SLASH_ARC, 5.4F, 0.12F, 7.5F, 0.75F, head2, tail2);
               g.slashArc(c, u, v, arcR * 0.38F, arcR * 1.08F, -1.25F, 1.25F, 22, FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2], a2 * 0.92F * cutFade(c));
            }
         }
      }

      // 3. 直线白热一闪斩痕（主+副交叉）+ 原位黑金残缝 + 空间/地面双涟漪环 + 爆心十字星闪
      if (post < 12.0F) {
         float reveal = FxMath.easeOutCubic(FxMath.window(post, 0.0F, 1.3F));
         float fade = 1.0F - FxMath.window(post, 5.5F, 12.0F);
         Vec3 endPt = this.slashA.lerp(this.slashB, reveal);
         g.begin(Style.RIBBON, 3.6F, 0.12F, 6.5F, 0.0F, 0.0F, 0.4F);
         this.drawLine(g, frame, this.slashA, endPt, 0.18F * this.scale, fade * 0.38F, AMBER_GOLD, true);
         g.begin(Style.RIBBON, 10.5F, 0.10F, 9.0F, 0.0F, 0.0F, 0.25F);
         this.drawLine(g, frame, this.slashA, endPt, 0.058F * this.scale, fade, WHITE_GOLD, true);

         float post2 = post - 1.1F;
         if (post2 >= 0.0F && post2 < 10.0F && this.slashAltA != null && this.slashAltB != null) {
            float reveal2 = FxMath.easeOutCubic(FxMath.window(post2, 0.0F, 1.2F));
            float fade2 = 1.0F - FxMath.window(post2, 4.5F, 10.0F);
            Vec3 endPt2 = this.slashAltA.lerp(this.slashAltB, reveal2);
            g.begin(Style.RIBBON, 3.4F, 0.12F, 6.5F, 0.0F, 0.0F, 0.35F);
            this.drawLine(g, frame, this.slashAltA, endPt2, 0.13F * this.scale, fade2 * 0.35F, FROST_CYAN, true);
            g.begin(Style.RIBBON, 9.2F, 0.10F, 9.0F, 0.0F, 0.0F, 0.22F);
            this.drawLine(g, frame, this.slashAltA, endPt2, 0.040F * this.scale, fade2 * 0.92F, WHITE_GOLD, true);
         }
      }
      if (post >= 0.5F && post < 18.0F) {
         float scarOpen = FxMath.envelope(FxMath.window(post, 0.5F, 18.0F), 0.10F, 0.55F);
         if (scarOpen > 0.01F) {
            Vector3f c = frame.rel(this.execCenter);
            Vector3f u = sideHorizontal(c, frame).mul(0.36F * this.scale);
            Vector3f v = new Vector3f(0.0F, Math.max(1.05F, this.targetHeight * 0.68F) * this.scale, 0.0F);
            g.begin(Style.VOID_SLIT, 5.2F, 0.0F, scarOpen * 0.62F, 0.72F, 1.0F, 0.32F);
            g.quad(c, u, v, AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], scarOpen * cutFade(c));
         }
      }
      if (post < 9.0F) {
         Vec3[] basis = buildPlaneBasis(this.slashForward);
         Vector3f c = frame.rel(this.execCenter);
         Vec3 feet = this.execCenter.subtract(0.0, this.targetHeight * 0.5 - 0.08, 0.0);
         Vector3f relFeet = frame.rel(feet);
         g.begin(Style.RING, 4.4F, 0.2F, 0.95F, 0.032F, 0.0F, 0.0F);
         float k1 = FxMath.window(post, 0.0F, 6.0F);
         if (k1 < 1.0F) {
            float r = (0.35F + 2.7F * FxMath.easeOutCubic(k1)) * this.scale;
            g.quad(c, toVec3f(basis[0]).mul(r), toVec3f(basis[1]).mul(r), AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], (1.0F - k1) * cutFade(c));
         }
         float k2 = FxMath.window(post, 1.0F, 7.5F);
         if (k2 > 0.0F && k2 < 1.0F) {
            float r = (0.28F + 2.2F * FxMath.easeOutCubic(k2)) * this.scale;
            g.quad(c, toVec3f(basis[0]).mul(r), toVec3f(basis[1]).mul(r), FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2], (1.0F - k2) * 0.85F * cutFade(c));
         }
         // 目标脚下爆裂扩散双环，与上半空交叉刀弧形成天地呼应
         float gk1 = FxMath.window(post, 0.0F, 7.5F);
         if (gk1 < 1.0F) {
            float gr = (0.65F + 3.4F * FxMath.easeOutCubic(gk1)) * this.scale;
            g.quad(relFeet, new Vector3f(gr, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, gr), AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], (1.0F - gk1) * (1.0F - gk1) * nearFade(relFeet));
         }
         float gk2 = FxMath.window(post, 1.1F, 9.0F);
         if (gk2 > 0.0F && gk2 < 1.0F) {
            float gr = (0.55F + 2.8F * FxMath.easeOutCubic(gk2)) * this.scale;
            g.quad(relFeet, new Vector3f(gr, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, gr), FROST_CYAN[0], FROST_CYAN[1], FROST_CYAN[2], (1.0F - gk2) * 0.78F * nearFade(relFeet));
         }
      }
      if (post < 4.5F) {
         float k = 1.0F - post / 4.5F;
         Vector3f c = frame.rel(this.execCenter);
         float sz = 1.45F * this.scale * (0.45F + 0.55F * k);
         g.begin(Style.GLOW, 9.5F, 0.0F, 6.5F, 3.2F, 1.4F, 0.0F);
         g.billboard(c, sz, 0.0F, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], k * k * cutFade(c));
         g.billboard(c, sz * 0.85F, (float)(Math.PI * 0.5), AMBER_GOLD[0], AMBER_GOLD[1], AMBER_GOLD[2], k * k * 0.85F * cutFade(c));
      }

      // 4. 目标 3D 骨骼剪影一分为二（0..2.5t 白金晶化定格 → 错位滑开 → 6.5..14.5t 烧蚀）
      if (this.targetQuads != null && post < 15.0F) {
         float splitK = FxMath.easeOutCubic(FxMath.window(post, 2.2F, 6.5F));
         float sep = (0.04F + 0.42F * splitK) * this.scale;
         float slide = (0.03F + 0.56F * splitK) * this.scale;
         if (post < 2.6F) {
            float flash = 1.0F - FxMath.window(post, 0.8F, 2.6F);
            g.begin(Style.AFTERIMAGE, 7.8F, 0.08F, 1.3F, 0.55F, 0.0F, 0.0F);
            this.drawTargetSplit(g, frame, 0.0F, 0.0F, flash, WHITE_GOLD, WHITE_GOLD);
         }
         float dissolve = FxMath.window(post, 6.5F, 14.5F);
         if (dissolve < 0.99F) {
            float appear = FxMath.window(post, 0.8F, 2.6F);
            g.begin(Style.AFTERIMAGE, 3.4F + 1.8F * (1.0F - splitK), 0.08F, 1.05F, 0.08F, 3.5F, dissolve);
            this.drawTargetSplit(g, frame, sep, slide, Math.min(1.0F, 0.55F + 0.45F * appear), PALE_GOLD, FROST_CYAN);
         }
      }

      // 5. 爆裂黑金棱晶碎屑与慢升余烬（0 ~ 22t）
      if (post < 14.0F) {
         float k = 1.0F - post / 14.0F;
         g.begin(Style.CRYSTAL_SHARD, 4.8F, 0.0F, 1.8F, 1.6F, 0.55F, 1.0F - k);
         for (int i = 0; i < SLASH_SHARDS; i++) {
            Vec3 pos = this.execCenter.add(this.slashShardVel[i].scale(post * (1.0 - post / 24.0))).add(0.0, -0.007 * post * post, 0.0);
            Vector3f rel = frame.rel(pos);
            float spin = this.slashShardSpin[i] * post;
            float sz = this.slashShardSize[i] * (0.45F + 0.55F * k);
            float cs = Mth.cos(spin);
            float sn = Mth.sin(spin);
            Vector3f u = new Vector3f(cs * sz, sn * sz * 0.65F, -sn * sz * 0.75F);
            Vector3f v = new Vector3f(-sn * sz * 0.5F, cs * sz * 1.4F, cs * sz * 0.5F);
            Vector3f n = new Vector3f(u).cross(v);
            if (n.lengthSquared() < 1.0E-6F) {
               n.set(0.0F, 1.0F, 0.0F);
            } else {
               n.normalize();
            }
            float[] tint = i % 3 == 0 ? FROST_CYAN : (i % 2 == 0 ? AMBER_GOLD : WHITE_GOLD);
            g.shard(rel, u, v, n, sz * 0.28F, tint[0], tint[1], tint[2], k * nearFade(rel));
         }
      }
      if (post < 22.0F) {
         float k = 1.0F - post / 22.0F;
         g.begin(Style.RIBBON, 4.2F, 0.08F, 6.0F, 0.0F, 0.0F, 0.35F);
         for (int i = 0; i < SLASH_SPARKS; i++) {
            // 前半高速火花，后半转为缓升余烬
            double drag = 1.0 - Math.exp(-post * 0.22);
            Vec3 pos = this.execCenter.add(this.slashSparkVel[i].scale(drag * 4.6)).add(0.0, 0.032 * post, 0.0);
            Vec3 tail = pos.subtract(this.slashSparkVel[i].scale(0.65 * k)).add(0.0, -0.05, 0.0);
            Vector3f rel = frame.rel(pos);
            float[] tint = i % 4 == 0 ? FROST_CYAN : (i % 3 == 0 ? WHITE_GOLD : AMBER_GOLD);
            g.streak(rel, frame.rel(tail), 0.022F * this.scale * (0.4F + 0.6F * k), tint[0], tint[1], tint[2], k * k * cutFade(rel));
         }
      }
   }

   // ───────────────────────────── 虚影与模型绘制辅助 ─────────────────────────────

   private Vec3 currentGhostChest(float t) {
      if (!this.executed || this.ghostEndChest == null) {
         return this.ghostStartChest;
      }
      float k = FxMath.easeOutCubic(FxMath.window(t, this.executeAge, this.executeAge + 2.2F));
      return this.ghostStartChest.lerp(this.ghostEndChest, k);
   }

   private float[] activeGhostQuads(float t) {
      if (this.executed && t >= this.executeAge + 1.0F && this.ghostFinishQuads != null) {
         return this.ghostFinishQuads;
      }
      return this.ghostReadyQuads != null ? this.ghostReadyQuads : this.ghostFinishQuads;
   }

   private float ghostAlpha(float t) {
      float in = FxMath.easeOutCubic(FxMath.window(t, TerminusEchoPattern.GHOST_APPEAR_TICK, TerminusEchoPattern.GHOST_APPEAR_TICK + 2.0F));
      if (this.executed) {
         float out = 1.0F - FxMath.window(t, this.executeAge + 16.0F, this.executeAge + 22.0F);
         return in * out;
      }
      return in;
   }

   private float ghostDissolve(float t) {
      if (!this.executed) {
         return 0.0F;
      }
      return FxMath.window(t, this.executeAge + 11.0F, this.executeAge + 20.0F);
   }

   private void drawPlayerGhost(FxGeometry g, FxFrame frame, Vec3 chest, float[] quads, float alpha, boolean body) {
      Vec3 feet = chest.subtract(0.0, this.ghostHeight * 0.5, 0.0);
      Vector3f base = frame.rel(feet);
      float fade = alpha * FxMath.window(new Vector3f(base).add(0.0F, this.ghostHeight * 0.5F, 0.0F).length(), 0.9F, 2.4F);
      if (fade <= 0.005F) {
         return;
      }
      for (int i = 0; i + 11 < quads.length; i += 12) {
         this.q0.set(base.x + quads[i], base.y + quads[i + 1], base.z + quads[i + 2]);
         this.q1.set(base.x + quads[i + 3], base.y + quads[i + 4], base.z + quads[i + 5]);
         this.q2.set(base.x + quads[i + 6], base.y + quads[i + 7], base.z + quads[i + 8]);
         this.q3.set(base.x + quads[i + 9], base.y + quads[i + 10], base.z + quads[i + 11]);
         if (body) {
            g.rawQuad(this.q0, this.q1, this.q2, this.q3, OBSIDIAN[0], OBSIDIAN[1], OBSIDIAN[2], fade);
         } else {
            // 脚底深虚空紫 → 躯干鎏金琥珀 → 肩顶白金霜芒
            float k = FxMath.clamp01((quads[i + 1] + quads[i + 4] + quads[i + 7] + quads[i + 10]) * 0.25F / Math.max(0.5F, this.ghostHeight));
            float r;
            float gr;
            float bl;
            if (k < 0.45F) {
               float u = k / 0.45F;
               r = FxMath.lerp(VOID_INDIGO[0], AMBER_GOLD[0], u);
               gr = FxMath.lerp(VOID_INDIGO[1], AMBER_GOLD[1], u);
               bl = FxMath.lerp(VOID_INDIGO[2], AMBER_GOLD[2], u);
            } else {
               float u = (k - 0.45F) / 0.55F;
               r = FxMath.lerp(AMBER_GOLD[0], WHITE_GOLD[0], u);
               gr = FxMath.lerp(AMBER_GOLD[1], WHITE_GOLD[1], u);
               bl = FxMath.lerp(AMBER_GOLD[2], WHITE_GOLD[2], u);
            }
            g.rawQuad(this.q0, this.q1, this.q2, this.q3, r, gr, bl, fade);
         }
      }
   }

   private Vec3 ghostBladeTip(Vec3 chest) {
      Vec3 face = TerminusEchoPattern.safeNormalize(this.targetCenter.subtract(chest), this.mirrorNormal);
      Vec3[] basis = buildPlaneBasis(face);
      Vec3 hilt = chest.add(basis[0].scale(0.44)).add(0.0, -0.10, 0.0);
      Vec3 bladeDir = TerminusEchoPattern.safeNormalize(face.scale(0.75).add(basis[0].scale(0.45)).add(0.0, 0.38, 0.0), face);
      return hilt.add(bladeDir.scale(1.38 * this.scale));
   }

   private void drawGhostBlade(FxGeometry g, FxFrame frame, Vec3 chest, float t, float alpha, boolean body) {
      Vec3 face = TerminusEchoPattern.safeNormalize(this.targetCenter.subtract(chest), this.mirrorNormal);
      Vec3[] basis = buildPlaneBasis(face);
      Vec3 hilt = chest.add(basis[0].scale(0.44)).add(0.0, -0.10, 0.0);
      Vec3 bladeDir = TerminusEchoPattern.safeNormalize(face.scale(0.75).add(basis[0].scale(0.45)).add(0.0, 0.38, 0.0), face);
      Vec3 mid = hilt.add(bladeDir.scale(0.42 * this.scale));
      Vector3f relMid = frame.rel(mid);
      Vector3f fwd = toVec3f(bladeDir);
      Vector3f up = toVec3f(basis[1]);
      Vector3f right = new Vector3f(fwd).cross(up);
      if (right.lengthSquared() < 1.0E-5F) {
         right.set(toVec3f(basis[0]));
      } else {
         right.normalize();
      }
      float charge = FxMath.window(t, TerminusEchoPattern.EXECUTE_TICK - 4.0F, TerminusEchoPattern.EXECUTE_TICK);
      g.begin(Style.CRYSTAL_SHARD, body ? 0.95F : 4.8F + 2.5F * charge, 0.05F, 2.0F, 1.6F, 0.35F + 0.65F * charge, 0.0F);
      float[] tint = body ? OBSIDIAN : AMBER_GOLD;
      g.bladeShard(relMid, fwd, right, up, 0.96F * this.scale, 0.36F * this.scale, 0.085F * this.scale, 0.042F * this.scale,
         tint[0], tint[1], tint[2], alpha * nearFade(relMid));
   }

   private void drawTargetSplit(FxGeometry g, FxFrame frame, float separation, float slide, float alpha, float[] top, float[] bottom) {
      if (alpha <= 0.005F || this.targetQuads == null || this.targetFeet == null) {
         return;
      }
      Vector3f base = frame.rel(this.targetFeet);
      float fade = alpha * cutFade(new Vector3f(base).add(0.0F, this.targetHeight * 0.5F, 0.0F));
      if (fade <= 0.005F) {
         return;
      }
      float[] quads = this.targetQuads;
      for (int i = 0, f = 0; i + 11 < quads.length && f < this.targetSides.length; i += 12, f++) {
         float side = this.targetSides[f];
         float ox = (float)(this.slashNormal.x * separation + this.slashDir.x * slide) * side;
         float oy = (float)(this.slashNormal.y * separation + this.slashDir.y * slide) * side;
         float oz = (float)(this.slashNormal.z * separation + this.slashDir.z * slide) * side;
         this.q0.set(base.x + quads[i] + ox, base.y + quads[i + 1] + oy, base.z + quads[i + 2] + oz);
         this.q1.set(base.x + quads[i + 3] + ox, base.y + quads[i + 4] + oy, base.z + quads[i + 5] + oz);
         this.q2.set(base.x + quads[i + 6] + ox, base.y + quads[i + 7] + oy, base.z + quads[i + 8] + oz);
         this.q3.set(base.x + quads[i + 9] + ox, base.y + quads[i + 10] + oy, base.z + quads[i + 11] + oz);
         float k = FxMath.clamp01((quads[i + 1] + quads[i + 4] + quads[i + 7] + quads[i + 10]) * 0.25F / Math.max(0.4F, this.targetHeight));
         g.rawQuad(this.q0, this.q1, this.q2, this.q3,
            FxMath.lerp(bottom[0], top[0], k), FxMath.lerp(bottom[1], top[1], k), FxMath.lerp(bottom[2], top[2], k), fade);
      }
   }

   // ───────────────────────────── B 档全屏通道：复用的交叉双局部空间错位切线 ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      if (this.deflectOnly || !this.executed || this.execCenter == null || FxShaders.sliceScene == null) {
         return;
      }
      float post = this.age(frame.partialTick) - this.executeAge;
      if (post < 0.0F || post >= 6.8F) {
         return;
      }
      if (frame.camera.distanceTo(this.execCenter) > SLICE_RANGE) {
         return;
      }
      float[] matData = new float[16];
      int count = 0;
      float peakGap = 0.0F;

      if (post < 5.6F) {
         float k = 1.0F - post / 5.6F;
         if (packLocalSlice(frame, this.slashA, this.slashB, 0.011F * k * this.slipSign, 0.0034F, 0.72F * k, matData, count * 8)) {
            count++;
            peakGap = Math.max(peakGap, 0.62F * k);
         }
      }
      float post2 = post - 1.1F;
      if (post2 >= 0.0F && post2 < 5.5F && this.slashAltA != null && this.slashAltB != null) {
         float k2 = 1.0F - post2 / 5.5F;
         if (packLocalSlice(frame, this.slashAltA, this.slashAltB, -0.009F * k2 * this.slipSign, 0.0028F, 0.60F * k2, matData, count * 8)) {
            count++;
            peakGap = Math.max(peakGap, 0.52F * k2);
         }
      }
      if (count == 0) {
         return;
      }

      Matrix4f cutsA = new Matrix4f().set(matData);
      Matrix4f empty = new Matrix4f().zero();
      int finalCount = count;
      float finalGap = peakGap;
      out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.sliceScene, shader -> {
         shader.safeGetUniform("CutsA").set(cutsA);
         shader.safeGetUniform("CutsB").set(empty);
         shader.safeGetUniform("CutsC").set(empty);
         shader.safeGetUniform("CutsD").set(empty);
         shader.safeGetUniform("Slice").set((float)finalCount, finalGap, 0.95F, 0.0F);
      }));
   }

   private static boolean packLocalSlice(
      FxFrame frame, Vec3 a, Vec3 b, float slip, float width, float seam, float[] outMat, int offset
   ) {
      float[] uvA = frame.screenUv(a);
      float[] uvB = frame.screenUv(b);
      if (uvA == null || uvB == null) {
         return false;
      }
      float dx = uvB[0] - uvA[0];
      float dy = uvB[1] - uvA[1];
      float score = (float)Math.sqrt(dx * dx + dy * dy);
      if (score < 0.015F) {
         return false;
      }
      float influence = Math.min(1.0F, score * 3.6F);
      outMat[offset] = uvA[0];
      outMat[offset + 1] = uvA[1];
      outMat[offset + 2] = uvB[0];
      outMat[offset + 3] = uvB[1];
      outMat[offset + 4] = slip * influence;
      outMat[offset + 5] = width;
      outMat[offset + 6] = 0.0F; // mode = 0 有限窄带错位，绝不整屏甩动
      outMat[offset + 7] = seam * influence;
      return true;
   }

   // ───────────────────────────── 三段呼吸式镜头反馈与精准震屏 ─────────────────────────────

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      float t = this.age(frame.partialTick);
      // 1. 受击偏折瞬间：负 FOV 顿挫吸气 + 暗金瞬时暗角 + 清脆微闪
      if (this.hasMirror && t < 3.8F) {
         double dist = frame.camera.distanceTo(this.mirrorCenter);
         if (dist < 14.0) {
            float att = 1.0F - (float)(dist / 14.0);
            float k = 1.0F - t / 3.8F;
            float snap = FxMath.envelope(t / 3.8F, 0.18F, 0.72F);
            screen.addFovKick(-0.032F * snap * att);
            screen.addVignette(0x2B1904, 0.24F * k * att);
            screen.addFlash(0xFFE6A8, 0.13F * k * k * att, 0.32F);
            screen.addAberration(0.34F * k * att);
         }
      }
      // 2. 虚影蓄刃扣鞘瞬间（t = 10.5..14）：轻度拉紧视野，衬托随后的一闪弹射
      if (!this.deflectOnly && !this.executed && this.abortedAt < 0 && t >= 10.5F && t < TerminusEchoPattern.EXECUTE_TICK + 0.5F) {
         double dist = frame.camera.distanceTo(this.targetCenter);
         if (dist < 36.0) {
            float att = 1.0F - (float)(dist / 36.0);
            float build = FxMath.window(t, 10.5F, TerminusEchoPattern.EXECUTE_TICK);
            screen.addFovKick(-0.018F * build * att);
            screen.addBloomBoost(0.18F * build * att);
         }
      }
      // 3. 反斩一闪瞬间：1.1t 极短反相顿帧 + 正 FOV 爆冲弹射 + 清晰速度线 + 白金过曝微闪
      if (this.executed && this.execCenter != null) {
         float post = t - this.executeAge;
         if (post >= 0.0F && post < 5.6F) {
            double dist = frame.camera.distanceTo(this.execCenter);
            if (dist < 38.0) {
               float[] uv = frame.screenUv(this.execCenter);
               if (uv != null) {
                  float att = 1.0F - (float)(dist / 38.0);
                  float k = 1.0F - post / 5.6F;
                  if (post < 1.1F && dist < 28.0) {
                     float hitstop = (1.0F - post / 1.1F) * (1.0F - (float)(dist / 28.0));
                     screen.addImpactFrame(0.72F * hitstop, true);
                  }
                  screen.addFovKick(0.048F * k * k * att);
                  screen.addDashBlur(0.52F * k * att, uv);
                  screen.addAberration(0.58F * k * att);
                  screen.addBloomBoost(0.46F * k * att);
                  screen.addFlash(0xFFF3CC, 0.20F * k * k * att, 0.50F);
               }
            }
         }
      }
   }

   @Override
   public float shake(FxFrame frame) {
      float t = this.age(frame.partialTick);
      float strength = 0.0F;
      // FxManager 内部按 shake^2 * 2.4° 换算：0.46 -> ~0.5° 盾反短震，0.62 -> ~0.9° 一闪回弹
      if (this.hasMirror && t < 3.5F) {
         double dist = frame.camera.distanceTo(this.mirrorCenter);
         if (dist < 14.0) {
            float k = 1.0F - t / 3.5F;
            strength = Math.max(strength, 0.46F * (0.35F + 0.65F * k) * (1.0F - (float)(dist / 14.0)));
         }
      }
      if (this.executed && this.execCenter != null) {
         float post = t - this.executeAge;
         if (post >= 0.0F && post < 5.5F) {
            double dist = frame.camera.distanceTo(this.execCenter);
            if (dist < 38.0) {
               float k = post < 1.0F ? 1.0F : 1.0F - (post - 1.0F) / 4.5F;
               strength = Math.max(strength, 0.62F * k * (1.0F - (float)(dist / 38.0)));
            }
         }
      }
      return strength;
   }

   // ───────────────────────────── 通用数学与几何工具 ─────────────────────────────

   private float mirrorShatterTime() {
      if (this.deflectOnly) {
         return 9.5F;
      }
      return this.executed ? Math.max(TerminusEchoPattern.SHATTER_START_TICK, this.executeAge + 8.0F) : TerminusEchoPattern.SHATTER_START_TICK;
   }

   private float abortFade(float t) {
      if (this.abortedAt < 0) {
         return 1.0F;
      }
      return 1.0F - FxMath.window(t, this.abortedAt, this.abortedAt + TerminusEchoPattern.ABORT_FADE_TICKS);
   }

   private boolean isLocalFirstPerson(FxFrame frame) {
      Minecraft mc = Minecraft.getInstance();
      return mc.player != null
         && mc.player.getId() == this.casterId
         && mc.options.getCameraType().isFirstPerson();
   }

   /** 第一人称近距保护：中心黑曜镜底与律印轻柔通透不遮准星，外圈金框与刻度环保持锐利高对比。 */
   private float mirrorScaleForView(FxFrame frame) {
      return this.isLocalFirstPerson(frame) ? 0.78F : 1.0F;
   }

   private float mirrorCoreVisibility(FxFrame frame) {
      float dist = frame.rel(this.mirrorCenter).length();
      if (this.isLocalFirstPerson(frame)) {
         return 0.42F * FxMath.window(dist, 0.45F, 1.05F);
      }
      return FxMath.window(dist, 0.55F, 1.4F);
   }

   private float mirrorRimVisibility(FxFrame frame) {
      float dist = frame.rel(this.mirrorCenter).length();
      if (this.isLocalFirstPerson(frame)) {
         return 0.92F * FxMath.window(dist, 0.42F, 0.95F);
      }
      return FxMath.window(dist, 0.50F, 1.35F);
   }

   private static float backOut(float x) {
      float c1 = 1.70158F;
      float c3 = c1 + 1.0F;
      float m = FxMath.clamp01(x) - 1.0F;
      return Math.max(0.0F, 1.0F + c3 * m * m * m + c1 * m * m);
   }

   private static Vector3f[] hexVertices(Vector3f center, Vec3 right, Vec3 up, float radius, float rotation) {
      Vector3f[] verts = new Vector3f[6];
      Vector3f r = toVec3f(right);
      Vector3f u = toVec3f(up);
      for (int i = 0; i < 6; i++) {
         float ang = rotation + (float)(i * Math.PI / 3.0);
         verts[i] = new Vector3f(center).fma(Mth.cos(ang) * radius, r).fma(Mth.sin(ang) * radius, u);
      }
      return verts;
   }

   private static Vec3[] buildPlaneBasis(Vec3 normal) {
      Vec3 n = TerminusEchoPattern.safeNormalize(normal, new Vec3(0.0, 0.0, 1.0));
      Vec3 ref = Math.abs(n.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
      Vec3 right = TerminusEchoPattern.safeNormalize(n.cross(ref), new Vec3(1.0, 0.0, 0.0));
      Vec3 up = TerminusEchoPattern.safeNormalize(right.cross(n), new Vec3(0.0, 1.0, 0.0));
      return new Vec3[]{right, up};
   }

   private static Vector3f sideHorizontal(Vector3f rel, FxFrame frame) {
      Vector3f u = new Vector3f(0.0F, 1.0F, 0.0F).cross(new Vector3f(rel).negate());
      return u.lengthSquared() < 1.0E-6F ? new Vector3f(frame.left) : u.normalize();
   }

   private void drawLine(FxGeometry g, FxFrame frame, Vec3 from, Vec3 to, float width, float alpha, float[] color, boolean taper) {
      int n = 7;
      List<Vector3f> pts = new ArrayList<>(n);
      float[] widths = new float[n];
      float[] alphas = new float[n];
      for (int i = 0; i < n; i++) {
         float k = (float)i / (n - 1);
         Vector3f p = frame.rel(from.lerp(to, k));
         pts.add(p);
         float shape = taper ? (float)Math.pow(Math.sin(Math.PI * k), 0.6) : 0.6F + 0.4F * (float)Math.sin(Math.PI * k);
         widths[i] = width * Math.max(0.08F, shape);
         alphas[i] = alpha * cutFade(p) * Math.max(0.15F, shape);
      }
      g.ribbon(pts, widths, alphas, color[0], color[1], color[2]);
   }

   private static Vector3f toVec3f(Vec3 v) {
      return new Vector3f((float)v.x, (float)v.y, (float)v.z);
   }

   private static float yawOf(Vec3 direction) {
      return (float)Math.toDegrees(Math.atan2(-direction.x, direction.z));
   }

   private static float pitchOf(Vec3 direction) {
      double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
      return (float)-Math.toDegrees(Math.atan2(direction.y, horizontal));
   }

   private static float nearFade(Vector3f rel) {
      return FxMath.window(rel.length(), 0.75F, 2.2F);
   }

   private static float cutFade(Vector3f rel) {
      return 0.25F + 0.75F * FxMath.window(rel.length(), 0.9F, 2.8F);
   }
}
