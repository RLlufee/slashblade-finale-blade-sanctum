package QWQ.QingYi.annihilationblade.annihilation_blade.client.fx;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.VoidDominionPattern;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.joml.Vector3f;

/**
 * 湮灭之刃 SE「虚无权域·万象湮灭」五幕导演级持续渲染器。
 * <p>
 * 视觉母题：「极简漆黑与瓷白的现实格式化 · 漫画线稿蓝图领域 · 虚无圣印 ∅ · 奇点 CRT 归零」。
 * <pre>
 *  第一幕  0 ~ 14t  领域降维：悬于目标群上方的巨型 ∅ 日蚀圣印与三轴浑天仪展开，球形边界划开高反差黑白世界与虚无墨穹；
 *  第二幕 14 ~ 26t  万象编目：测绘波纹掠过地表，目标被 3D 角标框定、全息线框扫描并与中枢奇点建立测绘总线；
 *  第三幕 26 ~ 52t  球形删档：球形扫描波前扫过之处化为漆黑墨底 + 锐利白线蓝图，目标随波前抵达被横斩删除线、
 *                   盖上 [REDACTED] 漆黑删档盒、八层水平撕裂烧蚀并崩解为黑白对比体素残骸；
 *  第四幕 54 ~ 68t  奇点归零：反相冲击帧爆发，蓝图虚空与全部体素沿螺旋流线被暴力抽回中枢 ∅；
 *                   61 ~ 65.4t 圣印纵向压扁为水平细线再横向塌缩为白星（CRT 关机归零），留下 4 tick 静默单色寂域；
 *  第五幕 68 ~ 92t  现实重绘：清澈银白扫描环自归零点平滑向外扩张，逐格把真实色彩与原本天空还给世界。
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public final class VoidDominionFx extends FxInstance {
   private static final int MAX_VISUAL_TARGETS = 16;
   private static final int MAX_TETHER_TARGETS = 8;
   private static final int MAX_VOXELS = 300;

   // 色盘：漆黑墨底 × 瓷白冷光 × 苍银微调（与其余彩色 SE 彻底拉开气质）
   private static final float[] INK = {0.03F, 0.035F, 0.055F};
   private static final float[] PURE_BLACK = {0.0F, 0.0F, 0.0F};
   private static final float[] WHITE = {0.97F, 0.985F, 1.0F};
   private static final float[] SILVER = {0.78F, 0.84F, 0.94F};
   private static final float[] PALE = {0.88F, 0.92F, 0.98F};
   private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);

   private Vec3 center;
   private Vec3 ground;
   private final float radius;
   private final float scale;
   private final float lift;
   private final Map<Integer, Erased> targets = new LinkedHashMap<>();
   private final Set<Integer> activeTargets = new HashSet<>();
   private int phase;
   private int abortStartAge = -1;
   private boolean collapseStarted;
   private boolean aborted;

   public VoidDominionFx(ClientLevel level, FxEventPacket packet) {
      super(VoidDominionPattern.TOTAL_LIFETIME);
      this.center = packet.origin();
      this.radius = Math.max(4.0F, packet.radius());
      this.scale = Mth.clamp(packet.scale(), 0.4F, 3.0F);
      this.lift = (float)VoidDominionPattern.centerLift(this.radius);
      this.phase = AnnihilationFxEvents.voidPhase(packet.intParam());
      this.collapseStarted = this.phase >= AnnihilationFxEvents.VOID_PHASE_COLLAPSE;
      this.updateGround();
      this.applySnapshot(level, packet, false);
   }

   @Override
   protected void onSustainedStart(int duration) {
      this.lifetime = Math.max(VoidDominionPattern.TOTAL_LIFETIME, duration);
   }

   @Override
   public void onSustainedUpdate(ClientLevel level, FxEventPacket packet) {
      this.center = packet.origin();
      this.updateGround();
      int nextPhase = AnnihilationFxEvents.voidPhase(packet.intParam());
      boolean hasErase = AnnihilationFxEvents.voidHasErase(packet.intParam());
      this.applySnapshot(level, packet, hasErase);
      this.phase = Math.max(this.phase, nextPhase);
      this.collapseStarted |= nextPhase >= AnnihilationFxEvents.VOID_PHASE_COLLAPSE;
   }

   private void applySnapshot(ClientLevel level, FxEventPacket packet, boolean triggerDepartures) {
      int[] ids = packet.entityIds();
      int[] ticks = packet.targetTicks();
      Set<Integer> next = new HashSet<>();
      for (int i = 0; i < ids.length && i < FxEventPacket.MAX_ENTITIES; i++) {
         int id = ids[i];
         next.add(id);
         Erased target = this.targets.get(id);
         if (target == null) {
            target = this.createTarget(level, id, i < ticks.length ? ticks[i] : VoidDominionPattern.SWEEP_START_TICK, packet.seed());
            if (target != null) {
               this.targets.put(id, target);
            }
         } else if (i < ticks.length) {
            target.eraseTick = ticks[i];
         }
      }

      if (triggerDepartures) {
         for (int id : this.activeTargets) {
            if (!next.contains(id)) {
               Erased target = this.targets.get(id);
               if (target != null && target.impactAge < 0) {
                  target.impactAge = this.ageTicks();
                  this.playImpact(level, target);
               }
            }
         }
      }
      this.activeTargets.clear();
      this.activeTargets.addAll(next);

      // 按预算为新捕获目标分配黑白体素残骸（总数上限 MAX_VOXELS）
      int assigned = 0;
      int pending = 0;
      for (Erased target : this.targets.values()) {
         if (target.voxels.length == 0) {
            pending++;
         } else {
            assigned += target.voxels.length;
         }
      }
      if (pending > 0) {
         int budget = Math.max(0, MAX_VOXELS - assigned);
         int basePerTarget = Math.min(22, budget / pending);
         int extra = basePerTarget < 22 ? (budget % pending) : 0;
         RandomSource random = RandomSource.create(packet.seed() ^ 0x71D0A11L);
         int idx = 0;
         for (Erased target : this.targets.values()) {
            if (target.voxels.length == 0) {
               int count = basePerTarget + (idx < extra ? 1 : 0);
               if (count > 0) {
                  target.createVoxels(random, count, this.scale);
               }
               idx++;
            }
         }
      }
   }

   private Erased createTarget(ClientLevel level, int id, int eraseTick, long seed) {
      Entity entity = level.getEntity(id);
      if (entity == null) {
         return null;
      }
      LivingEntity living = entity instanceof LivingEntity value ? value : null;
      Vec3 foot = entity.position();
      float width = Math.max(0.45F, entity.getBbWidth());
      float height = Math.max(0.85F, entity.getBbHeight());
      RandomSource random = RandomSource.create(seed ^ ((long)id * 0x9E3779B97F4A7C15L));
      Erased target = new Erased(id, foot, width, height, eraseTick, random);
      if (living != null && this.capturedTargetCount() < MAX_VISUAL_TARGETS) {
         target.capturedQuads = AfterimageCapture.captureLiving(living);
      }
      return target;
   }

   private int capturedTargetCount() {
      int count = 0;
      for (Erased target : this.targets.values()) {
         if (target.capturedQuads != null) {
            count++;
         }
      }
      return count;
   }

   private void updateGround() {
      this.ground = new Vec3(this.center.x, this.center.y - this.lift + 0.06, this.center.z);
   }

   @Override
   public Vec3 focus() {
      return this.center;
   }

   @Override
   public double cullDistance() {
      return this.radius + 192.0;
   }

   // ───────────────────────────── 逻辑与音效时间轴 ─────────────────────────────

   @Override
   public void tick(ClientLevel level) {
      int age = this.ageTicks();
      if (age == 0 && this.phase == AnnihilationFxEvents.VOID_PHASE_START) {
         play(level, this.center, SoundEvents.CONDUIT_ACTIVATE, 1.15F, 0.52F);
         play(level, this.center, SoundEvents.BEACON_ACTIVATE, 0.78F, 1.65F);
      }
      if (age == VoidDominionPattern.EXPAND_END_TICK) {
         play(level, this.center, SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.72F, 1.48F);
      }
      if (age == VoidDominionPattern.SWEEP_START_TICK) {
         play(level, this.center, SoundEvents.TRIDENT_RIPTIDE_1, 0.95F, 0.58F);
         play(level, this.center, SoundEvents.BEACON_POWER_SELECT, 0.55F, 1.85F);
      }
      if (age == VoidDominionPattern.COLLAPSE_TICK) {
         play(level, this.center, SoundEvents.WARDEN_SONIC_BOOM, 1.25F, 0.54F);
         play(level, this.center, SoundEvents.BEACON_DEACTIVATE, 0.9F, 0.45F);
      }
      if (age == VoidDominionPattern.ZERO_SNAP_TICK) {
         play(level, this.center, SoundEvents.GLASS_BREAK, 0.8F, 1.35F);
         play(level, this.center, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 0.85F, 1.6F);
         play(level, this.center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.75F, 1.9F);
      }
      if (age == VoidDominionPattern.RESTORE_START_TICK + 2) {
         play(level, this.center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.42F, 1.25F);
      }

      int catalogCount = 0;
      for (Erased target : this.targets.values()) {
         if (this.activeTargets.contains(target.id)) {
            Entity entity = level.getEntity(target.id);
            if (entity != null) {
               target.foot = entity.position();
               target.width = Math.max(0.45F, entity.getBbWidth());
               target.height = Math.max(0.85F, entity.getBbHeight());
               target.center = target.foot.add(0.0, target.height * 0.5, 0.0);
               if (age >= VoidDominionPattern.EXPAND_END_TICK && age < VoidDominionPattern.SWEEP_START_TICK
                     && catalogCount < MAX_VISUAL_TARGETS && age % 3 == 0 && entity instanceof LivingEntity living) {
                  float[] fresh = AfterimageCapture.captureLiving(living);
                  if (fresh != null) {
                     target.capturedQuads = fresh;
                  }
               }
            }
            if (target.eraseTick <= age && target.impactAge < 0 && age <= VoidDominionPattern.SWEEP_END_TICK) {
               target.impactAge = age;
               this.playImpact(level, target);
            }
            catalogCount++;
         }
      }
   }

   private void playImpact(ClientLevel level, Erased target) {
      if (target.soundPlayed) {
         return;
      }
      target.soundPlayed = true;
      int stride = Math.max(1, (this.targets.size() + 9) / 10);
      int index = 0;
      for (Erased candidate : this.targets.values()) {
         if (candidate.id == target.id) {
            break;
         }
         index++;
      }
      if (index % stride != 0) {
         return;
      }
      play(level, target.center, SoundEvents.UI_BUTTON_CLICK.value(), 0.36F, 1.48F + target.jitter * 0.45F);
      play(level, target.center, SoundEvents.GLASS_BREAK, 0.28F, 1.75F + target.jitter * 0.28F);
   }

   private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
      level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int age = this.ageTicks();
      if (age == 0) {
         for (int i = 0; i < 16; i++) {
            level.addParticle(ParticleTypes.END_ROD, this.center.x, this.center.y, this.center.z, 0.0, 0.02, 0.0);
         }
      }
      for (Erased target : this.targets.values()) {
         if (target.impactAge == age) {
            for (int i = 0; i < 8; i++) {
               level.addParticle(ParticleTypes.WHITE_ASH, target.center.x, target.center.y, target.center.z, 0.0, 0.04, 0.0);
            }
         }
      }
   }

   @Override
   public void onSustainedStop() {
      if (this.collapseStarted || this.ageTicks() >= VoidDominionPattern.COLLAPSE_TICK) {
         return;
      }
      this.aborted = true;
      this.abortStartAge = this.ageTicks();
      this.lifetime = Math.min(this.lifetime, this.abortStartAge + 8);
   }

   // ───────────────────────────── 时间轴曲线 ─────────────────────────────

   private float abortFade(float t) {
      return this.aborted ? 1.0F - FxMath.window(t, this.abortStartAge, this.abortStartAge + 8.0F) : 1.0F;
   }

   /** 外穹顶半径：0..14 展开至 R，此后保持 R（绝不在坍缩与重绘间跳变）。 */
   private float outerRadius(float t) {
      if (t < VoidDominionPattern.EXPAND_END_TICK) {
         return this.radius * FxMath.easeOutCubic(FxMath.window(t, 0.0F, VoidDominionPattern.EXPAND_END_TICK));
      }
      return this.radius;
   }

   /** 活跃蓝图/虚空内半径：26..52 随波前扩至 R，54..64 坍缩回 0，其余阶段为 0。 */
   private float activeVoidRadius(float t) {
      if (t < VoidDominionPattern.SWEEP_START_TICK) {
         return 0.0F;
      }
      if (t < VoidDominionPattern.COLLAPSE_TICK) {
         return VoidDominionPattern.frontRadius(t, this.radius);
      }
      if (t < VoidDominionPattern.ZERO_SNAP_TICK) {
         return this.radius * (1.0F - FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK)));
      }
      return 0.0F;
   }

   /** 3D 骨架缩放半径：0..14 展开、14..54 满半径、54..64 坍缩回 0。 */
   private float architectureRadius(float t) {
      if (t < VoidDominionPattern.EXPAND_END_TICK) {
         return this.radius * FxMath.easeOutCubic(FxMath.window(t, 0.0F, VoidDominionPattern.EXPAND_END_TICK));
      }
      if (t < VoidDominionPattern.COLLAPSE_TICK) {
         return this.radius;
      }
      if (t < VoidDominionPattern.ZERO_SNAP_TICK) {
         return this.radius * (1.0F - FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK)));
      }
      return 0.0F;
   }

   private float restoreRadius(float t) {
      return this.radius * FxMath.easeInOutSine(FxMath.window(t, VoidDominionPattern.RESTORE_START_TICK, VoidDominionPattern.TOTAL_LIFETIME));
   }

   private float presence(float t) {
      float fadeIn = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 2.5F));
      float fadeOut = 1.0F - FxMath.window(t, VoidDominionPattern.TOTAL_LIFETIME - 4.0F, VoidDominionPattern.TOTAL_LIFETIME);
      return fadeIn * fadeOut * this.abortFade(t);
   }

   private static float nullScale(float t) {
      float x = FxMath.window(t, 0.0F, 3.2F);
      float overshoot = 1.0F + 0.45F * (float)Math.pow(1.0F - x, 2.0) * (float)Math.sin(x * 7.5F);
      return FxMath.easeOutCubic(x) * overshoot;
   }

   private float nullPresence(float t) {
      return (1.0F - FxMath.window(t, 64.8F, 66.8F)) * this.abortFade(t);
   }

   /** 球面与地面平面的真实截圆半径（未触地时返回 0）。 */
   private float groundIntersectionRadius(float sphereRadius) {
      float dy = this.lift - 0.06F;
      float sq = sphereRadius * sphereRadius - dy * dy;
      return sq > 0.04F ? (float)Math.sqrt(sq) : 0.0F;
   }

   // ───────────────────────────── 本体层（预乘 Alpha 漆黑墨底，保证白天高反差） ─────────────────────────────

   @Override
   public boolean hasBody() {
      return true;
   }

   @Override
   public void renderBody(FxGeometry body, FxFrame frame) {
      float t = this.age(frame.partialTick);
      float alpha = this.presence(t);
      if (alpha <= 0.001F) {
         return;
      }
      Vector3f center = frame.rel(this.center);
      this.renderNullCoreBody(body, frame, center, t, alpha);
      this.renderTargetsBody(body, frame, t, alpha);
      body.end();
   }

   private void renderNullCoreBody(FxGeometry body, FxFrame frame, Vector3f center, float t, float alpha) {
      float glyph = this.nullPresence(t) * nullScale(t) * alpha;
      if (glyph <= 0.001F) {
         return;
      }
      float vSnap = 1.0F - FxMath.easeInCubic(FxMath.window(t, 60.5F, 64.0F));
      float hSnap = t < 64.0F ? 1.0F : Math.max(0.015F, 1.0F - FxMath.easeInCubic(FxMath.window(t, 64.0F, 65.5F)));
      float pulse = 1.0F + 0.08F * (float)Math.sin(t * 0.42F);
      float collapseBoost = 1.0F + 0.25F * FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, 60.5F);
      float baseRadius = 2.35F * this.scale * pulse * hSnap;
      Vector3f view = viewVector(frame);

      // 1. 绝对漆黑日蚀球核（Style.CORONA + geoCenter 平滑球面法线，白天 100% 实黑绝不发灰）
      float coreR = Math.max(0.03F, 0.88F * this.scale * collapseBoost * Math.min(hSnap, Math.max(0.18F, vSnap)));
      if (vSnap > 0.06F) {
         body.begin(Style.CORONA, 0.28F, 0.0F, 2.8F, 0.9F, 0.14F, 0.35F);
         body.geoCenter(center);
         body.sphere(center, coreR, 14, 24, t * 0.015F, INK[0], INK[1], INK[2], 0.98F * glyph);
         body.clearGeoCenter();
      }

      // 2. 主 ∅ 环与交叉环的墨黑描边衬底（略粗于白环，在亮天空下勾勒清晰黑边）
      if (vSnap > 0.04F) {
         Vector3f tilt = new Vector3f(frame.up).fma(0.45F, frame.left).add(view).normalize();
         body.begin(Style.RIBBON, 0.22F, 0.0F, 2.6F, 0.0F, 0.0F, 0.48F);
         body.torus(center, view, baseRadius, Math.max(0.025F, 0.12F * this.scale * vSnap), 48, 6, t * 0.022F, INK[0], INK[1], INK[2], 0.92F * glyph);
         body.torus(center, tilt, baseRadius * 1.15F, Math.max(0.018F, 0.072F * this.scale * vSnap), 44, 5, -t * 0.018F, INK[0], INK[1], INK[2], 0.72F * glyph);
      }

      // 3. 贯穿 ∅ 的 3D 黑曜石斜切圣刃暗芯
      if (vSnap > 0.05F) {
         float slashAngle = (float)Math.toRadians(-48.0) + t * 0.008F;
         Vector3f slashDir = new Vector3f(frame.left).mul((float)Math.cos(slashAngle)).add(new Vector3f(frame.up).mul((float)Math.sin(slashAngle))).normalize();
         Vector3f slashSide = new Vector3f(view).cross(slashDir).normalize();
         float halfLen = 3.45F * this.scale * hSnap * vSnap;
         body.begin(Style.CRYSTAL_SHARD, 0.24F, 0.0F, 1.8F, 0.45F, 0.15F, 0.0F);
         body.bladeShard(center, slashDir, slashSide, view, halfLen, halfLen, 0.165F * this.scale * hSnap, 0.095F * this.scale, INK[0], INK[1], INK[2], 0.96F * glyph);
      }

      // 4. 坍缩归零前的水平墨黑裂痕（纯黑白无杂色，为 CRT 压缩铺垫黑腔反差）
      if (t >= VoidDominionPattern.COLLAPSE_TICK && t < 65.2F) {
         float open = FxMath.envelope(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, 65.2F), 0.16F, 0.72F);
         if (open > 0.01F) {
            float span = 3.9F * this.scale * hSnap;
            Vector3f leftEnd = new Vector3f(center).fma(-span, frame.left);
            Vector3f rightEnd = new Vector3f(center).fma(span, frame.left);
            body.begin(Style.RIBBON, 0.16F, 0.0F, 2.2F, 0.0F, 0.0F, 0.65F);
            this.line(body, leftEnd, rightEnd, Math.max(0.05F, 0.34F * this.scale * open * Math.max(0.18F, vSnap)),
               PURE_BLACK[0], PURE_BLACK[1], PURE_BLACK[2], 0.96F * alpha);
         }
      }
   }

   private void renderTargetsBody(FxGeometry body, FxFrame frame, float t, float alpha) {
      float catalog = FxMath.window(t, 14.0F, 17.0F) * (1.0F - FxMath.window(t, 25.0F, 27.0F)) * alpha;

      // 1. 目标涂黑删档盒 [REDACTED]（带弹性展开 + 噪声烧蚀消散）
      for (Erased target : this.targets.values()) {
         if (target.impactAge < 0) {
            continue;
         }
         float elapsed = t - target.impactAge;
         if (elapsed < 0.0F || elapsed >= 8.8F) {
            continue;
         }
         float grow = FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 0.55F));
         float shrink = 1.0F - 0.65F * FxMath.easeInCubic(FxMath.window(elapsed, 5.0F, 8.8F));
         float dissolve = FxMath.window(elapsed, 4.4F, 8.6F);
         float sx = Math.max(0.22F, target.width * 0.54F) * grow * shrink;
         float sy = Math.max(0.38F, target.height * 0.54F) * grow * shrink;
         float sz = Math.max(0.22F, target.width * 0.54F) * grow * shrink;
         body.begin(Style.AFTERIMAGE, 0.15F, 0.0F, 1.0F, 0.95F, 0.0F, dissolve);
         body.orientedCube(frame.rel(target.center), new Vector3f(sx, 0.0F, 0.0F), new Vector3f(0.0F, sy, 0.0F), new Vector3f(0.0F, 0.0F, sz),
            PURE_BLACK[0], PURE_BLACK[1], PURE_BLACK[2], 0.96F * alpha);
      }

      // 2. 编目与被撕裂目标的暗墨剪影衬底（压暗原版实体彩色贴图，突出黑白线框）
      int capturedIdx = 0;
      for (Erased target : this.targets.values()) {
         if (target.capturedQuads == null) {
            continue;
         }
         boolean inCatalog = capturedIdx < MAX_VISUAL_TARGETS && catalog > 0.001F && this.activeTargets.contains(target.id) && target.impactAge < 0;
         float elapsed = target.impactAge < 0 ? 0.0F : t - target.impactAge;
         boolean inErase = target.impactAge >= 0 && elapsed >= 0.0F && elapsed < 8.4F;
         capturedIdx++;
         if (!inCatalog && !inErase) {
            continue;
         }
         float dissolve = inErase ? FxMath.window(elapsed, 2.2F, 8.2F) : 0.0F;
         float a = inErase ? alpha * (1.0F - FxMath.window(elapsed, 5.5F, 8.4F)) * 0.85F : catalog * 0.52F;
         body.begin(Style.AFTERIMAGE, 0.22F, 0.0F, 1.0F, 0.35F, 3.0F, dissolve);
         this.emitCapturedQuads(body, frame, target, t, elapsed, a, true);
      }

      // 3. 暗黑对比体素残骸（与 HDR 瓷白体素严格分流，构成黑白碎块雨）
      if (t < VoidDominionPattern.ZERO_SNAP_TICK + 1.5F) {
         float collapse = FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));
         body.begin(Style.AFTERIMAGE, 0.18F, 0.0F, 1.0F, 0.95F, 0.0F, 0.0F);
         for (Erased target : this.targets.values()) {
            if (target.impactAge < 0) {
               continue;
            }
            float debrisAge = t - target.impactAge - 3.2F;
            if (debrisAge < 0.0F || debrisAge > 32.0F) {
               continue;
            }
            float ageFade = FxMath.window(debrisAge, 0.0F, 1.2F) * (1.0F - FxMath.window(debrisAge, 22.0F, 32.0F));
            for (Voxel voxel : target.voxels) {
               if (!voxel.dark) {
                  continue;
               }
               Vector3f pos = frame.rel(voxel.position(t, target.impactAge, target.center, this.center));
               float size = voxel.size * (1.0F - collapse * 0.72F);
               if (size > 0.008F) {
                  body.cube(pos, size, size, size, INK[0], INK[1], INK[2], alpha * ageFade * 0.94F);
               }
            }
         }
      }

      // 4. 锚定在目标真实脚底高度的涂黑地面残痕（绝不使用全局 ground.y 导致悬空穿地）
      int marks = 0;
      body.begin(Style.AFTERIMAGE, 0.16F, 0.25F, 1.0F, 0.95F, 0.0F, 0.0F);
      for (Erased target : this.targets.values()) {
         if (target.impactAge < 0 || marks >= MAX_VISUAL_TARGETS) {
            continue;
         }
         float elapsed = t - target.impactAge;
         if (elapsed < 0.0F || elapsed > 22.0F) {
            continue;
         }
         Vector3f mark = frame.rel(new Vec3(target.foot.x, target.foot.y + 0.04, target.foot.z));
         float size = Math.max(0.36F, target.width * 0.56F) * (1.0F + 0.22F * FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 3.0F)));
         float fade = (1.0F - FxMath.window(elapsed, 13.0F, 22.0F)) * alpha;
         body.quad(mark, new Vector3f(size, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, size), PURE_BLACK[0], PURE_BLACK[1], PURE_BLACK[2], fade * 0.90F);
         marks++;
      }
   }

   // ───────────────────────────── HDR 发光层（瓷白精工几何） ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry geometry, FxFrame frame) {
      float t = this.age(frame.partialTick);
      float alpha = this.presence(t);
      if (alpha <= 0.001F) {
         return;
      }
      Vector3f center = frame.rel(this.center);
      this.renderNullMonolith(geometry, frame, center, t, alpha);
      this.renderDomainArchitecture(geometry, frame, center, t, alpha);
      this.renderCatalogAndDeletions(geometry, frame, center, t, alpha);
      geometry.end();
   }

   private void renderNullMonolith(FxGeometry g, FxFrame frame, Vector3f center, float t, float alpha) {
      float glyph = this.nullPresence(t) * nullScale(t) * alpha;
      float vSnap = 1.0F - FxMath.easeInCubic(FxMath.window(t, 60.5F, 64.0F));
      float hSnap = t < 64.0F ? 1.0F : Math.max(0.015F, 1.0F - FxMath.easeInCubic(FxMath.window(t, 64.0F, 65.5F)));
      float pulse = 1.0F + 0.08F * (float)Math.sin(t * 0.42F);
      float collapseBoost = 1.0F + 0.25F * FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, 60.5F);
      Vector3f view = viewVector(frame);

      if (glyph > 0.001F) {
         float mainR = 2.35F * this.scale * pulse * hSnap;

         // 1. 黑核冷白日蚀边缘光（仅勾勒边缘，中心仍由 Body 层维持纯黑）
         if (vSnap > 0.08F) {
            float coreR = Math.max(0.035F, 0.91F * this.scale * collapseBoost * Math.min(hSnap, Math.max(0.2F, vSnap)));
            g.begin(Style.CORONA, 2.6F, 0.0F, 5.2F, 0.65F, 0.14F, 0.28F);
            g.geoCenter(center);
            g.sphere(center, coreR, 14, 24, t * 0.015F, PALE[0], PALE[1], PALE[2], glyph * 0.68F);
            g.clearGeoCenter();
         }

         // 2. ∅ 主环与三轴浑天仪 3D 星轨（严格使用 Style.RIBBON + FxParams.x > 0 驱动 torus）
         Vector3f tiltA = new Vector3f(frame.up).fma(0.45F, frame.left).add(view).normalize();
         Vector3f tiltB = new Vector3f(frame.left).fma(-0.55F, frame.up).add(new Vector3f(view).mul(0.55F)).normalize();
         float majorTube = distWidth(center, 0.072F * this.scale, 0.0026F) * Math.max(0.18F, vSnap);
         float minorTube = distWidth(center, 0.042F * this.scale, 0.0018F) * Math.max(0.18F, vSnap);

         g.begin(Style.RIBBON, 4.4F, 0.0F, 5.4F, 0.0F, 0.0F, 0.36F);
         g.torus(center, view, mainR, majorTube, 56, 6, t * 0.022F, WHITE[0], WHITE[1], WHITE[2], glyph);
         g.torus(center, tiltA, mainR * 1.15F, minorTube, 48, 5, -t * 0.018F, SILVER[0], SILVER[1], SILVER[2], glyph * 0.82F);
         g.torus(center, tiltB, mainR * 0.86F, minorTube * 0.82F, 44, 5, t * 0.028F, PALE[0], PALE[1], PALE[2], glyph * 0.65F);

         // 3. 外圈 48 段精密虚线游标刻度环（Style.RING + 面向相机 quad）
         if (vSnap > 0.06F) {
            float dialR = 2.95F * this.scale * hSnap;
            Vector3f dialU = new Vector3f(frame.left).mul(dialR);
            Vector3f dialV = new Vector3f(frame.up).mul(dialR * vSnap);
            g.begin(Style.RING, 2.8F, 0.0F, 0.945F, 0.014F, 48.0F, 0.08F);
            g.quad(center, dialU, dialV, SILVER[0], SILVER[1], SILVER[2], glyph * 0.78F);
            g.begin(Style.RING, 2.2F, 0.0F, 0.86F, 0.007F, 12.0F, -0.05F);
            g.quad(center, dialU, dialV, WHITE[0], WHITE[1], WHITE[2], glyph * 0.55F);
         }

         // 4. 贯穿 ∅ 的终焉斜刃「/」：真 3D 棱晶八面体 + 灼热中心刃芒线
         if (vSnap > 0.05F) {
            float slashAngle = (float)Math.toRadians(-48.0) + t * 0.008F;
            Vector3f slashDir = new Vector3f(frame.left).mul((float)Math.cos(slashAngle)).add(new Vector3f(frame.up).mul((float)Math.sin(slashAngle))).normalize();
            Vector3f slashSide = new Vector3f(view).cross(slashDir).normalize();
            float halfLen = 3.45F * this.scale * hSnap * vSnap;

            g.begin(Style.CRYSTAL_SHARD, 3.2F, 0.0F, 2.2F, 0.55F, 0.35F, 0.0F);
            g.bladeShard(center, slashDir, slashSide, view, halfLen, halfLen, 0.135F * this.scale * hSnap, 0.082F * this.scale,
               WHITE[0], WHITE[1], WHITE[2], glyph);

            Vector3f tipA = new Vector3f(center).fma(halfLen * 1.06F, slashDir);
            Vector3f tipB = new Vector3f(center).fma(-halfLen * 1.06F, slashDir);
            g.begin(Style.RIBBON, 6.2F, 0.0F, 8.0F, 0.0F, 0.0F, 0.28F);
            this.line(g, tipB, tipA, distWidth(center, 0.052F * this.scale, 0.0022F), WHITE[0], WHITE[1], WHITE[2], glyph);
         }

         // 5. 四向精密游标卡尺（随呼吸微张合，坍缩时收束至水平线）
         float caliperR = (3.18F + 0.14F * (float)Math.sin(t * 0.28F)) * this.scale * hSnap;
         float caliperW = distWidth(center, 0.036F * this.scale, 0.0018F);
         g.begin(Style.RIBBON, 3.4F, 0.0F, 6.8F, 0.0F, 0.0F, 0.2F);
         for (int i = 0; i < 4; i++) {
            float angle = (float)(Math.PI * 0.5 * i) + t * 0.006F;
            float ca = (float)Math.cos(angle);
            float sa = (float)Math.sin(angle);
            Vector3f radial = new Vector3f(frame.left).mul(ca).add(new Vector3f(frame.up).mul(sa * Math.max(0.08F, vSnap)));
            Vector3f tangent = new Vector3f(frame.left).mul(-sa).add(new Vector3f(frame.up).mul(ca * Math.max(0.08F, vSnap)));
            Vector3f base = new Vector3f(center).fma(caliperR, radial);
            Vector3f tail = new Vector3f(base).fma(-0.34F * this.scale, radial);
            Vector3f a = new Vector3f(base).fma(-0.28F * this.scale, tangent);
            Vector3f b = new Vector3f(base).fma(0.28F * this.scale, tangent);
            this.line(g, tail, base, caliperW, PALE[0], PALE[1], PALE[2], glyph * 0.88F);
            this.line(g, a, b, caliperW, WHITE[0], WHITE[1], WHITE[2], glyph * 0.92F);
         }

         // 6. 坍缩期水平归零扫描锋线（纯瓷白，与 CRT 压扁无缝衔接）
         if (t >= VoidDominionPattern.COLLAPSE_TICK && t < 65.5F) {
            float open = FxMath.envelope(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, 65.5F), 0.16F, 0.68F);
            if (open > 0.01F) {
               float span = (3.9F + 1.4F * (1.0F - vSnap)) * this.scale * hSnap;
               Vector3f leftEnd = new Vector3f(center).fma(-span, frame.left);
               Vector3f rightEnd = new Vector3f(center).fma(span, frame.left);
               g.begin(Style.RIBBON, 6.4F, 0.0F, 9.0F, 0.0F, 0.0F, 0.30F);
               this.line(g, leftEnd, rightEnd, distWidth(center, 0.065F * this.scale * open, 0.0024F),
                  WHITE[0], WHITE[1], WHITE[2], alpha * open);
            }
         }
      }

      // 7. CRT 归零奇点横闪星芒（t = 63.0..67.0，水平扫描线瞬间压成一点白星熄灭）
      if (t >= 63.0F && t < 67.2F) {
         float snapEnv = FxMath.envelope(FxMath.window(t, 63.0F, 67.2F), 0.22F, 0.45F);
         float streakSize = (2.8F + 2.4F * FxMath.window(t, 63.0F, 64.2F)) * this.scale * (1.0F - 0.78F * FxMath.easeInCubic(FxMath.window(t, 64.2F, 67.2F)));
         g.begin(Style.GLOW, 6.5F, 0.0F, 3.6F, 1.2F, 2.8F, 0.0F);
         g.billboard(center, Math.max(0.15F, streakSize), 0.0F, WHITE[0], WHITE[1], WHITE[2], snapEnv * alpha);
      }
   }

   private void renderDomainArchitecture(FxGeometry g, FxFrame frame, Vector3f center, float t, float alpha) {
      float archR = this.architectureRadius(t);
      if (archR >= 0.25F && t < VoidDominionPattern.ZERO_SNAP_TICK + 1.0F) {
         Vector3f ground = frame.rel(this.ground);
         float groundOuterR = this.groundIntersectionRadius(archR);
         float gridAlpha = FxMath.window(t, 1.0F, 6.0F) * (1.0F - FxMath.window(t, 60.0F, 64.0F)) * alpha;

         // 1. 地表建筑罗盘（使用真实截圆半径与柔和深度淡出，消除硬切穿地）
         if (groundOuterR > 0.5F && gridAlpha > 0.001F) {
            g.begin(Style.RING, 2.8F, 0.45F, 0.985F, 0.006F, 72.0F, 0.04F);
            g.quad(ground, new Vector3f(groundOuterR, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, groundOuterR),
               WHITE[0], WHITE[1], WHITE[2], gridAlpha * 0.85F);

            g.begin(Style.RING, 2.0F, 0.45F, 0.97F, 0.008F, 0.0F, 0.0F);
            for (int i = 1; i <= 2; i++) {
               float r = groundOuterR * i / 3.0F;
               g.quad(ground, new Vector3f(r, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r),
                  SILVER[0], SILVER[1], SILVER[2], gridAlpha * 0.42F);
            }

            // 8 方位测绘刻度线（中心留白，避免圆心重叠过曝）
            float lineW = distWidth(ground, 0.045F, 0.0022F);
            g.begin(Style.RIBBON, 2.4F, 0.35F, 6.5F, 0.0F, 0.0F, 0.22F);
            float innerGap = Math.min(2.4F, groundOuterR * 0.16F);
            for (int i = 0; i < 8; i++) {
               float angle = FxMath.TAU * i / 8.0F;
               float ca = (float)Math.cos(angle);
               float sa = (float)Math.sin(angle);
               Vector3f from = new Vector3f(ground).add(ca * innerGap, 0.02F, sa * innerGap);
               Vector3f to = new Vector3f(ground).add(ca * groundOuterR, 0.02F, sa * groundOuterR);
               this.line(g, from, to, lineW, SILVER[0], SILVER[1], SILVER[2], gridAlpha * (i % 2 == 0 ? 0.62F : 0.34F));
            }
         }

         // 2. 穹顶稀疏经纬建筑线框（距离自适应线宽，清晰不闪烁，与体积解析薄壳互补）
         float skelAlpha = alpha * (1.0F - FxMath.window(t, 61.0F, 64.0F)) * (0.34F + 0.12F * (float)Math.sin(t * 0.14F));
         if (skelAlpha > 0.001F) {
            float skelW = distWidth(center, 0.042F * this.scale, 0.0020F);
            g.begin(Style.RIBBON, 2.4F, 0.0F, 6.2F, 0.0F, 0.0F, 0.22F);
            for (int i = 0; i < 8; i++) {
               float lon = FxMath.TAU * i / 8.0F + t * 0.0025F;
               this.drawMeridian(g, center, archR, lon, skelW, skelAlpha);
            }
            for (int i = 1; i <= 2; i++) {
               float lat = (float)(Math.PI * i / 3.5);
               this.drawLatitude(g, center, archR, lat, 64, skelW * 0.85F, skelAlpha * 0.78F);
            }
         }

         // 3. 球形扫描波前 3D 结构（t = 26..53.5：赤道光环 + 双正交经环 + 真实地面截交环）
         if (t >= VoidDominionPattern.SWEEP_START_TICK && t <= VoidDominionPattern.SWEEP_END_TICK + 1.5F) {
            float front = VoidDominionPattern.frontRadius(t, this.radius);
            float waveFade = 1.0F - FxMath.window(t, VoidDominionPattern.SWEEP_END_TICK - 0.5F, VoidDominionPattern.SWEEP_END_TICK + 1.5F);
            float shellAlpha = alpha * waveFade;
            if (front > 0.15F && shellAlpha > 0.001F) {
               Vector3f view = viewVector(frame);
               float waveTube = distWidth(center, 0.075F * this.scale, 0.0028F);
               g.begin(Style.RIBBON, 5.2F, 0.0F, 5.8F, 0.0F, 0.0F, 0.35F);
               g.torus(center, view, front, waveTube, 56, 6, t * 0.035F, WHITE[0], WHITE[1], WHITE[2], shellAlpha * 0.92F);
               g.torus(center, UP, front, waveTube * 0.72F, 56, 5, -t * 0.025F, PALE[0], PALE[1], PALE[2], shellAlpha * 0.65F);

               float frontW = distWidth(center, 0.055F * this.scale, 0.0022F);
               g.begin(Style.RIBBON, 4.4F, 0.0F, 7.2F, 0.0F, 0.0F, 0.25F);
               this.drawMeridian(g, center, front, t * 0.012F, frontW, shellAlpha * 0.65F);
               this.drawMeridian(g, center, front, t * 0.012F + (float)Math.PI * 0.5F, frontW, shellAlpha * 0.65F);

               // 球形波前切入地面时的精确截交光环
               float groundWaveR = this.groundIntersectionRadius(front);
               if (groundWaveR > 0.2F) {
                  g.begin(Style.RING, 5.6F, 0.55F, 0.975F, 0.014F, 0.0F, 0.0F);
                  g.quad(new Vector3f(ground).add(0.0F, 0.04F, 0.0F), new Vector3f(groundWaveR, 0.0F, 0.0F),
                     new Vector3f(0.0F, 0.0F, groundWaveR), WHITE[0], WHITE[1], WHITE[2], shellAlpha);
               }
            }
         }

         // 4. 坍缩幕向心收束流线与压缩压环（t = 54..64）
         if (t >= VoidDominionPattern.COLLAPSE_TICK && t < VoidDominionPattern.ZERO_SNAP_TICK + 0.5F) {
            float collapse = FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));
            float filamentAlpha = alpha * (0.45F + 0.55F * collapse) * (1.0F - FxMath.window(t, 63.0F, 64.5F));
            float fW = distWidth(center, 0.055F * this.scale, 0.0022F);
            g.begin(Style.RIBBON, 4.8F, 0.0F, 6.8F, 0.0F, 0.0F, 0.28F);
            for (int i = 0; i < 16; i++) {
               float az = FxMath.TAU * i / 16.0F + t * 0.032F;
               float el = (i % 3 - 1) * 0.42F;
               float ce = (float)Math.cos(el);
               Vector3f dir = new Vector3f((float)Math.cos(az) * ce, (float)Math.sin(el), (float)Math.sin(az) * ce);
               Vector3f from = new Vector3f(center).fma(Math.max(0.2F, archR * 1.05F), dir);
               Vector3f to = new Vector3f(center).fma(Math.max(0.06F, archR * 0.14F), dir);
               this.line(g, from, to, fW, WHITE[0], WHITE[1], WHITE[2], filamentAlpha);
            }
            if (archR > 0.35F) {
               g.torus(center, viewVector(frame), archR, distWidth(center, 0.085F * this.scale, 0.003F), 52, 6, -t * 0.06F,
                  WHITE[0], WHITE[1], WHITE[2], filamentAlpha * 0.92F);
            }
         }
      }

      // 5. 第五幕 · 现实重绘扫描环（t = 68..92，清澈银白光环平滑展开还回色彩）
      if (t >= VoidDominionPattern.RESTORE_START_TICK && t < VoidDominionPattern.TOTAL_LIFETIME) {
         float restore = this.restoreRadius(t);
         float fade = (1.0F - FxMath.window(t, 85.0F, 92.0F)) * alpha;
         if (restore > 0.12F && fade > 0.001F) {
            float tubeW = distWidth(center, 0.06F * this.scale, 0.0024F);
            g.begin(Style.RIBBON, 3.8F, 0.0F, 6.0F, 0.0F, 0.0F, 0.32F);
            g.torus(center, UP, restore, tubeW, 56, 5, t * 0.02F, WHITE[0], WHITE[1], WHITE[2], fade * 0.85F);
            g.torus(center, viewVector(frame), restore, tubeW * 0.65F, 56, 5, -t * 0.015F, SILVER[0], SILVER[1], SILVER[2], fade * 0.45F);

            float groundRestoreR = this.groundIntersectionRadius(restore);
            if (groundRestoreR > 0.2F) {
               g.begin(Style.RING, 3.6F, 0.5F, 0.975F, 0.014F, 64.0F, 0.06F);
               g.quad(frame.rel(this.ground), new Vector3f(groundRestoreR, 0.0F, 0.0F),
                  new Vector3f(0.0F, 0.0F, groundRestoreR), WHITE[0], WHITE[1], WHITE[2], fade * 0.82F);
            }
         }
      }
   }

   private void renderCatalogAndDeletions(FxGeometry g, FxFrame frame, Vector3f domeCenter, float t, float alpha) {
      float catalog = FxMath.window(t, 14.0F, 17.0F) * (1.0F - FxMath.window(t, 25.0F, 27.0F)) * alpha;
      float catalogLock = FxMath.easeOutCubic(FxMath.window(t, 14.0F, 18.5F));

      // ─── 1. 编目幕（14..26t）：3D 角标框 + 中枢测绘连线 + 脚底虚线环 + 头顶棱晶指针 ───
      if (catalog > 0.001F) {
         // 1A. 3D 角标框与中枢数据总线（统一 RIBBON 批次，零状态泄漏）
         g.begin(Style.RIBBON, 3.6F, 0.0F, 6.8F, 0.0F, 0.0F, 0.22F);
         int rendered = 0;
         for (Erased target : this.targets.values()) {
            if (rendered >= MAX_VISUAL_TARGETS) {
               break;
            }
            if (!this.activeTargets.contains(target.id) || target.impactAge >= 0) {
               continue;
            }
            float lockScale = Mth.lerp(catalogLock, 1.32F, 1.0F);
            this.drawSelectionBrackets(g, frame, target, lockScale, catalog);
            Vector3f headMark = frame.rel(target.foot.add(0.0, target.height + 0.42, 0.0));
            Vector3f footRel = frame.rel(target.foot);
            this.line(g, footRel, headMark, distWidth(footRel, 0.018F * this.scale, 0.001F), SILVER[0], SILVER[1], SILVER[2], catalog * 0.48F);
            if (rendered < MAX_TETHER_TARGETS) {
               this.line(g, domeCenter, headMark, distWidth(headMark, 0.022F * this.scale, 0.0012F), SILVER[0], SILVER[1], SILVER[2], catalog * 0.36F);
            }
            rendered++;
         }

         // 1B. 脚底旋转虚拟定位刻度环（统一 RING 批次）
         g.begin(Style.RING, 2.8F, 0.25F, 0.94F, 0.024F, 12.0F, 0.25F);
         rendered = 0;
         for (Erased target : this.targets.values()) {
            if (rendered >= MAX_VISUAL_TARGETS) {
               break;
            }
            if (!this.activeTargets.contains(target.id) || target.impactAge >= 0) {
               continue;
            }
            float ringR = Math.max(0.55F, target.width * 0.82F) * Mth.lerp(catalogLock, 1.35F, 1.0F);
            Vector3f base = frame.rel(target.foot.add(0.0, 0.05, 0.0));
            g.quad(base, new Vector3f(ringR, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, ringR), WHITE[0], WHITE[1], WHITE[2], catalog * 0.85F);
            rendered++;
         }

         // 1C. 头顶悬停旋转 3D 棱晶编目指针（统一 CRYSTAL_SHARD 批次）
         g.begin(Style.CRYSTAL_SHARD, 3.2F, 0.0F, 2.0F, 0.4F, 0.4F, 0.0F);
         rendered = 0;
         for (Erased target : this.targets.values()) {
            if (rendered >= MAX_VISUAL_TARGETS) {
               break;
            }
            if (!this.activeTargets.contains(target.id) || target.impactAge >= 0) {
               continue;
            }
            float bob = 0.08F * (float)Math.sin(t * 0.35F + target.jitter * FxMath.TAU);
            Vector3f cursor = frame.rel(target.foot.add(0.0, target.height + 0.56 + bob, 0.0));
            float rot = t * 0.14F + target.jitter * FxMath.TAU;
            Vector3f right = new Vector3f((float)Math.cos(rot), 0.0F, (float)Math.sin(rot));
            Vector3f up = new Vector3f(-right.z, 0.0F, right.x);
            g.bladeShard(cursor, new Vector3f(0.0F, -1.0F, 0.0F), right, up, 0.22F * this.scale, 0.14F * this.scale,
               0.095F * this.scale, 0.095F * this.scale, WHITE[0], WHITE[1], WHITE[2], catalog);
            rendered++;
         }
      }

      // ─── 2. 目标全息线框模型扫描 & 处决八层水平错位烧蚀（单次低填充 + 清晰扫描线） ───
      int capturedIdx = 0;
      for (Erased target : this.targets.values()) {
         if (target.capturedQuads == null) {
            continue;
         }
         boolean inCatalog = capturedIdx < MAX_VISUAL_TARGETS && catalog > 0.001F && this.activeTargets.contains(target.id) && target.impactAge < 0;
         float elapsed = target.impactAge < 0 ? 0.0F : t - target.impactAge;
         boolean inErase = target.impactAge >= 0 && elapsed >= 0.0F && elapsed < 8.4F;
         capturedIdx++;
         if (!inCatalog && !inErase) {
            continue;
         }
         float dissolve = inErase ? FxMath.window(elapsed, 2.2F, 8.2F) : 0.0F;
         float modelAlpha = inErase ? alpha * (1.0F - FxMath.window(elapsed, 5.8F, 8.4F)) : catalog * 0.78F;
         g.begin(Style.AFTERIMAGE, inErase ? 1.65F : 1.15F, 0.0F, 1.05F, 0.04F, 4.5F, dissolve);
         this.emitCapturedQuads(g, frame, target, t, elapsed, modelAlpha, false);
      }

      // ─── 3. 逐目标处决：闪电删除线 + 12 棱白框 + 十字星闪 ───
      g.begin(Style.RIBBON, 7.2F, 0.0F, 8.2F, 0.0F, 0.0F, 0.26F);
      for (Erased target : this.targets.values()) {
         if (target.impactAge < 0) {
            continue;
         }
         float elapsed = t - target.impactAge;
         if (elapsed < 0.0F || elapsed > 9.5F) {
            continue;
         }
         Vector3f c = frame.rel(target.center);

         // 3A. 0..2.6t 终焉横斩删除线（0.8t 内自左向右闪电划过）
         if (elapsed < 2.8F) {
            float draw = FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 0.75F));
            float flash = 1.0F - FxMath.window(elapsed, 0.35F, 2.6F);
            float span = Math.max(1.6F, target.width * 2.2F) * this.scale;
            Vector3f leftEnd = new Vector3f(c).fma(-span * 0.55F, frame.left);
            Vector3f rightEnd = new Vector3f(leftEnd).fma(span * 1.1F * draw, frame.left);
            this.line(g, leftEnd, rightEnd, distWidth(c, 0.048F * this.scale, 0.0022F), WHITE[0], WHITE[1], WHITE[2], flash * alpha);
            Vector3f vHalf = new Vector3f(frame.up).mul(target.height * 0.68F * draw);
            this.line(g, new Vector3f(c).sub(vHalf), new Vector3f(c).add(vHalf), distWidth(c, 0.022F * this.scale, 0.0012F),
               PALE[0], PALE[1], PALE[2], flash * alpha * 0.75F);
         }

         // 3B. 0..8.8t [REDACTED] 涂黑盒的 12 棱锐利瓷白外框
         float box = FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 0.55F))
            * (1.0F - 0.65F * FxMath.easeInCubic(FxMath.window(elapsed, 5.0F, 8.8F)));
         float boxAlpha = (1.0F - FxMath.window(elapsed, 6.2F, 8.8F)) * alpha;
         if (box > 0.01F && boxAlpha > 0.001F) {
            float hx = Math.max(0.24F, target.width * 0.56F) * box;
            float hy = Math.max(0.40F, target.height * 0.56F) * box;
            float hz = Math.max(0.24F, target.width * 0.56F) * box;
            Vector3f[] corners = boxCorners(c, hx, hy, hz);
            float edgeW = distWidth(c, 0.024F * this.scale, 0.0014F);
            for (int i = 0; i < corners.length; i++) {
               for (int axis = 1; axis <= 4; axis <<= 1) {
                  if ((i & axis) == 0) {
                     this.line(g, corners[i], corners[i | axis], edgeW, WHITE[0], WHITE[1], WHITE[2], boxAlpha);
                  }
               }
            }
         }
      }

      // 3C. 处决瞬间横向星闪（Style.GLOW + billboard）
      g.begin(Style.GLOW, 5.2F, 0.0F, 3.8F, 1.4F, 1.8F, 0.0F);
      for (Erased target : this.targets.values()) {
         if (target.impactAge < 0) {
            continue;
         }
         float elapsed = t - target.impactAge;
         if (elapsed < 0.0F || elapsed > 2.4F) {
            continue;
         }
         float flash = 1.0F - FxMath.window(elapsed, 0.25F, 2.4F);
         float sz = Math.max(0.85F, target.width * 1.25F) * this.scale * (0.6F + 0.6F * FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 0.6F)));
         g.billboard(frame.rel(target.center), sz, 0.0F, WHITE[0], WHITE[1], WHITE[2], flash * alpha);
      }

      // ─── 4. 瓷白发光体素残骸（严格单次绘制、仅在处决 3.2t 后出现、坍缩期螺旋吸入奇点） ───
      if (t < VoidDominionPattern.ZERO_SNAP_TICK + 1.5F) {
         float collapse = FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));
         g.begin(Style.AFTERIMAGE, 2.5F, 0.0F, 1.2F, 0.45F, 0.0F, 0.0F);
         for (Erased target : this.targets.values()) {
            if (target.impactAge < 0) {
               continue;
            }
            float debrisAge = t - target.impactAge - 3.2F;
            if (debrisAge < 0.0F || debrisAge > 32.0F) {
               continue;
            }
            float ageFade = FxMath.window(debrisAge, 0.0F, 1.2F) * (1.0F - FxMath.window(debrisAge, 22.0F, 32.0F));
            for (Voxel voxel : target.voxels) {
               if (voxel.dark) {
                  continue;
               }
               Vector3f pos = frame.rel(voxel.position(t, target.impactAge, target.center, this.center));
               float size = voxel.size * (1.0F - collapse * 0.72F);
               float flicker = 0.76F + 0.24F * (float)Math.sin(t * 2.1F + voxel.phase);
               if (size > 0.008F) {
                  g.cube(pos, size, size, size, voxel.shade, voxel.shade * 1.02F, Math.min(1.0F, voxel.shade * 1.06F),
                     alpha * ageFade * voxel.alpha * flicker);
               }
            }
         }
      }

      // ─── 5. 锚定目标脚底的地面抹除白框 ───
      int marks = 0;
      g.begin(Style.RING, 3.2F, 0.25F, 0.94F, 0.022F, 0.0F, 0.0F);
      for (Erased target : this.targets.values()) {
         if (target.impactAge < 0 || marks >= MAX_VISUAL_TARGETS) {
            continue;
         }
         float elapsed = t - target.impactAge;
         if (elapsed < 0.0F || elapsed > 22.0F) {
            continue;
         }
         Vector3f mark = frame.rel(new Vec3(target.foot.x, target.foot.y + 0.05, target.foot.z));
         float size = Math.max(0.40F, target.width * 0.62F) * (1.0F + 0.22F * FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 3.0F)));
         float fade = (1.0F - FxMath.window(elapsed, 13.0F, 22.0F)) * alpha;
         g.quad(mark, new Vector3f(size, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, size), WHITE[0], WHITE[1], WHITE[2], fade);
         marks++;
      }
   }

   private void drawSelectionBrackets(FxGeometry g, FxFrame frame, Erased target, float scaleMul, float alpha) {
      Vector3f c = frame.rel(target.center);
      float hx = target.width * 0.64F * scaleMul;
      float hy = target.height * 0.58F * scaleMul;
      float hz = target.width * 0.64F * scaleMul;
      Vector3f[] corners = boxCorners(c, hx, hy, hz);
      float w = distWidth(c, 0.022F * this.scale, 0.0012F);
      float arm = 0.28F * this.scale;
      for (int i = 0; i < corners.length; i++) {
         for (int axis = 1; axis <= 4; axis <<= 1) {
            int j = i ^ axis;
            Vector3f delta = new Vector3f(corners[j]).sub(corners[i]);
            float len = delta.length();
            if (len > 1.0E-4F) {
               delta.normalize().mul(Math.min(len * 0.36F, arm));
               this.line(g, corners[i], new Vector3f(corners[i]).add(delta), w, WHITE[0], WHITE[1], WHITE[2], alpha);
            }
         }
      }
   }

   private void emitCapturedQuads(FxGeometry g, FxFrame frame, Erased target, float t, float elapsed, float alpha, boolean bodyPass) {
      float[] quads = target.capturedQuads;
      if (quads == null || alpha <= 0.001F) {
         return;
      }
      boolean erased = target.impactAge >= 0;
      Vector3f right = new Vector3f(frame.left).mul(0.11F * this.scale);
      float tearProg = erased ? FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 0.85F)) : 0.0F;
      for (int i = 0; i + 11 < quads.length; i += 12) {
         float avgY = (quads[i + 1] + quads[i + 4] + quads[i + 7] + quads[i + 10]) * 0.25F;
         int band = Mth.clamp((int)(avgY / Math.max(0.2F, target.height) * 8.0F), 0, 7);
         float offset = erased ? ((band % 2 == 0 ? 1.0F : -1.0F) * (0.12F + 0.08F * (band % 4)) * tearProg) : 0.0F;
         float jitter = erased ? 0.022F * (float)Math.sin(t * 9.0F + band * 5.2F + target.jitter * 14.0F) : 0.0F;
         Vector3f shift = new Vector3f(right).mul(offset + jitter);
         Vector3f a = frame.rel(target.foot.add(quads[i], quads[i + 1], quads[i + 2])).add(shift);
         Vector3f b = frame.rel(target.foot.add(quads[i + 3], quads[i + 4], quads[i + 5])).add(shift);
         Vector3f c = frame.rel(target.foot.add(quads[i + 6], quads[i + 7], quads[i + 8])).add(shift);
         Vector3f d = frame.rel(target.foot.add(quads[i + 9], quads[i + 10], quads[i + 11])).add(shift);
         if (bodyPass) {
            g.rawQuad(a, b, c, d, INK[0], INK[1], INK[2], alpha);
         } else {
            float shade = band % 3 == 0 ? 0.96F : 0.78F;
            g.rawQuad(a, b, c, d, shade, shade * 1.01F, Math.min(1.0F, shade * 1.04F), alpha);
         }
      }
   }

   private void drawMeridian(FxGeometry g, Vector3f center, float radius, float longitude, float width, float alpha) {
      int segs = 28;
      List<Vector3f> points = new ArrayList<>(segs + 1);
      float[] widths = new float[segs + 1];
      float[] alphas = new float[segs + 1];
      float cl = (float)Math.cos(longitude);
      float sl = (float)Math.sin(longitude);
      for (int i = 0; i <= segs; i++) {
         float lat = (float)(Math.PI * i / segs);
         float sinLat = (float)Math.sin(lat);
         float x = radius * sinLat * cl;
         float y = radius * (float)Math.cos(lat);
         float z = radius * sinLat * sl;
         points.add(new Vector3f(center).add(x, y, z));
         widths[i] = width;
         alphas[i] = alpha * (0.25F + 0.75F * sinLat);
      }
      g.ribbon(points, widths, alphas, SILVER[0], SILVER[1], SILVER[2]);
   }

   private void drawLatitude(FxGeometry g, Vector3f center, float radius, float latitude, int segments, float width, float alpha) {
      float ringR = radius * (float)Math.sin(latitude);
      float y = radius * (float)Math.cos(latitude);
      List<Vector3f> points = new ArrayList<>(segments + 1);
      float[] widths = new float[segments + 1];
      float[] alphas = new float[segments + 1];
      for (int i = 0; i <= segments; i++) {
         float angle = FxMath.TAU * i / segments;
         points.add(new Vector3f(center).add(ringR * (float)Math.cos(angle), y, ringR * (float)Math.sin(angle)));
         widths[i] = width;
         alphas[i] = alpha;
      }
      g.ribbon(points, widths, alphas, SILVER[0], SILVER[1], SILVER[2]);
   }

   private static Vector3f[] boxCorners(Vector3f center, float halfX, float halfY, float halfZ) {
      Vector3f[] corners = new Vector3f[8];
      for (int i = 0; i < corners.length; i++) {
         corners[i] = new Vector3f(center).add((i & 1) == 0 ? -halfX : halfX, (i & 2) == 0 ? -halfY : halfY, (i & 4) == 0 ? -halfZ : halfZ);
      }
      return corners;
   }

   private static Vector3f viewVector(FxFrame frame) {
      return new Vector3f(frame.up).cross(frame.left).normalize();
   }

   /** 远处线条按距离适度加粗，防止 26 格外细线缩成亚像素产生锯齿闪烁。 */
   private static float distWidth(Vector3f rel, float base, float perBlock) {
      return Math.max(base, rel.length() * perBlock);
   }

   private void line(FxGeometry g, Vector3f from, Vector3f to, float width, float r, float green, float blue, float alpha) {
      if (alpha <= 0.001F) {
         return;
      }
      g.ribbon(List.of(from, to), new float[]{width, width}, new float[]{alpha, alpha}, r, green, blue);
   }

   // ───────────────────────────── B 档场景通道 & 解析体积通道 ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      float t = this.age(frame.partialTick);
      float present = this.presence(t);
      if (present <= 0.001F) {
         return;
      }
      Vector3f center = frame.rel(this.center);
      float outerR = this.outerRadius(t);
      float voidR = this.activeVoidRadius(t);
      float restoreR = t >= VoidDominionPattern.RESTORE_START_TICK ? this.restoreRadius(t) : 0.0F;
      float frontR = (t >= VoidDominionPattern.SWEEP_START_TICK && t <= VoidDominionPattern.SWEEP_END_TICK + 1.5F)
         ? VoidDominionPattern.frontRadius(t, this.radius)
         : 0.0F;
      float phaseFloat = t < VoidDominionPattern.EXPAND_END_TICK ? 0.0F
         : t < VoidDominionPattern.SWEEP_START_TICK ? 1.0F
         : t < VoidDominionPattern.COLLAPSE_TICK ? 2.0F
         : t < VoidDominionPattern.RESTORE_START_TICK ? 3.0F : 4.0F;

      float fineGrid = present * (0.28F + 0.42F * FxMath.window(t, 24.0F, 34.0F));
      float mainGrid = present * (0.38F + 0.48F * FxMath.window(t, 2.0F, 14.0F));
      float monoStrength = t < VoidDominionPattern.COLLAPSE_TICK
         ? 0.94F
         : Mth.lerp(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.RESTORE_START_TICK), 0.94F, 0.80F);
      float invert = 0.88F * FxMath.window(t, 54.0F, 54.5F) * (1.0F - FxMath.window(t, 55.8F, 57.6F));
      float skyDark = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 7.0F))
         * (1.0F - 0.34F * FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.RESTORE_START_TICK));
      float glitch = (t >= VoidDominionPattern.SWEEP_START_TICK && t <= VoidDominionPattern.SWEEP_END_TICK + 1.0F) ? 0.78F : 0.0F;
      float camX = (float)(frame.camera.x - Math.floor(frame.camera.x));
      float camY = (float)(frame.camera.y - Math.floor(frame.camera.y));
      float camZ = (float)(frame.camera.z - Math.floor(frame.camera.z));

      if (FxShaders.eraseScene != null) {
         out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.eraseScene, shader -> {
            shader.safeGetUniform("Dome").set(center.x, center.y, center.z, outerR);
            shader.safeGetUniform("Erase").set(present, voidR, frontR, 0.78F);
            shader.safeGetUniform("Grid").set(fineGrid, mainGrid, monoStrength, invert);
            shader.safeGetUniform("CamFract").set(camX, camY, camZ, this.radius);
            shader.safeGetUniform("Extra").set(skyDark, glitch, restoreR, phaseFloat);
         }));
      }

      if (FxShaders.eraseVolume != null) {
         float volWaveR;
         float volWaveStrength;
         if (t >= VoidDominionPattern.SWEEP_START_TICK && t <= VoidDominionPattern.SWEEP_END_TICK + 1.5F) {
            volWaveR = frontR;
            volWaveStrength = 1.05F * (1.0F - FxMath.window(t, VoidDominionPattern.SWEEP_END_TICK - 0.5F, VoidDominionPattern.SWEEP_END_TICK + 1.5F));
         } else if (t >= VoidDominionPattern.COLLAPSE_TICK && t < VoidDominionPattern.ZERO_SNAP_TICK + 0.5F) {
            volWaveR = voidR;
            volWaveStrength = 0.95F * (1.0F - FxMath.window(t, VoidDominionPattern.ZERO_SNAP_TICK - 1.5F, VoidDominionPattern.ZERO_SNAP_TICK + 0.5F));
         } else if (t >= VoidDominionPattern.RESTORE_START_TICK) {
            volWaveR = restoreR;
            volWaveStrength = 0.62F * (1.0F - FxMath.window(t, 86.0F, 92.0F));
         } else {
            volWaveR = 0.0F;
            volWaveStrength = 0.0F;
         }
         float collapseProg = (t >= VoidDominionPattern.COLLAPSE_TICK && t < VoidDominionPattern.ZERO_SNAP_TICK + 0.5F)
            ? FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK)
            : 0.0F;
         float coreHalo = this.nullPresence(t) * nullScale(t) * present;
         float zeroSnap = FxMath.envelope(FxMath.window(t, 63.2F, 66.4F), 0.16F, 0.48F) * present;
         float outerShellStrength = 0.46F * (1.0F - FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));

         out.add(new FxPass(FxPass.Kind.VOLUME, () -> FxShaders.eraseVolume, shader -> {
            shader.safeGetUniform("Dome").set(center.x, center.y, center.z, Math.max(0.01F, outerR));
            shader.safeGetUniform("Erase").set(present, volWaveR, 0.52F, volWaveStrength);
            shader.safeGetUniform("Phase").set(t, collapseProg, coreHalo, zeroSnap);
            shader.safeGetUniform("Extra").set(this.radius, outerShellStrength, this.scale, phaseFloat);
         }));
      }
   }

   // ───────────────────────────── 屏幕级镜头演出 ─────────────────────────────

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      float t = this.age(frame.partialTick);
      float distance = (float)frame.camera.distanceTo(this.center);
      float attenuation = 1.0F - FxMath.window(distance, this.radius + 10.0F, this.radius + 48.0F);
      if (attenuation <= 0.001F) {
         return;
      }
      // 领域内施加克制墨黑暗角增强压迫感，色彩与单色降维完全交给 3D 场景通道精确裁切
      float inside = 1.0F - FxMath.window(distance, this.radius - 2.0F, this.radius + 4.0F);
      if (inside > 0.0F && t < VoidDominionPattern.RESTORE_START_TICK) {
         float vigFade = 1.0F - 0.45F * FxMath.window(t, VoidDominionPattern.ZERO_SNAP_TICK, VoidDominionPattern.RESTORE_START_TICK);
         screen.addVignette(0x04060B, 0.30F * inside * this.presence(t) * vigFade);
      }

      if (t < 3.5F) {
         float open = 1.0F - t / 3.5F;
         screen.addFlash(0xEEF4FF, 0.22F * open * attenuation, 0.25F);
         screen.addFovKick(0.032F * open * attenuation);
      }

      if (t >= 54.0F && t < 57.5F) {
         float hit = 1.0F - FxMath.window(t, 54.0F, 57.5F);
         screen.addImpactFrame(0.88F * hit * attenuation, hit > 0.38F);
         screen.addDashBlur(0.68F * hit * attenuation, frame.screenUv(this.center));
         screen.addFovKick(-0.075F * hit * attenuation);
         screen.addBloomBoost(0.42F * hit * attenuation);
      } else if (t >= 57.5F && t < 64.0F) {
         float collapse = FxMath.easeInCubic(FxMath.window(t, 57.5F, 64.0F));
         screen.addDashBlur(0.44F * collapse * attenuation, frame.screenUv(this.center));
         screen.addAberration(0.14F * collapse * attenuation);
         screen.addFovKick(-0.05F * collapse * attenuation);
      }

      if (t >= 63.6F && t < 66.4F) {
         float snap = 1.0F - FxMath.window(t, 64.0F, 66.4F);
         screen.addFlash(0xFFFFFF, 0.50F * snap * attenuation, 0.65F);
         screen.addImpactFrame(0.74F * snap * attenuation, false);
         screen.addAberration(0.18F * snap * attenuation);
      }
   }

   @Override
   public float shake(FxFrame frame) {
      float t = this.age(frame.partialTick);
      float distance = (float)frame.camera.distanceTo(this.center);
      float attenuation = 1.0F - FxMath.window(distance, this.radius + 4.0F, this.radius + 42.0F);
      if (t < 4.0F) {
         return 0.22F * (1.0F - t / 4.0F) * attenuation;
      }
      if (t >= 54.0F && t < 60.0F) {
         return 0.68F * (1.0F - FxMath.window(t, 54.0F, 60.0F)) * attenuation;
      }
      if (t >= 64.0F && t < 68.0F) {
         return 0.32F * (1.0F - FxMath.window(t, 64.0F, 68.0F)) * attenuation;
      }
      return 0.0F;
   }

   // ───────────────────────────── 内部数据结构 ─────────────────────────────

   private static final class Erased {
      final int id;
      Vec3 foot;
      Vec3 center;
      float width;
      float height;
      int eraseTick;
      int impactAge = -1;
      final float jitter;
      float[] capturedQuads;
      boolean soundPlayed;
      Voxel[] voxels = new Voxel[0];

      Erased(int id, Vec3 foot, float width, float height, int eraseTick, RandomSource random) {
         this.id = id;
         this.foot = foot;
         this.center = foot.add(0.0, height * 0.5, 0.0);
         this.width = width;
         this.height = height;
         this.eraseTick = eraseTick;
         this.jitter = random.nextFloat();
      }

      void createVoxels(RandomSource random, int count, float scale) {
         this.voxels = new Voxel[count];
         for (int i = 0; i < count; i++) {
            Vector3f dir = FxMath.randomUnit(random);
            float lift = random.nextFloat() * this.height * 0.78F;
            // 存储相对目标中心的偏移，确保目标在编目前移动后体素仍从真实处决位置崩解
            Vec3 offset = new Vec3(dir.x * this.width * 0.48, lift - this.height * 0.38, dir.z * this.width * 0.48);
            double speed = 0.032 + random.nextDouble() * 0.075;
            Vec3 velocity = new Vec3(dir.x * speed, 0.045 + random.nextDouble() * 0.11, dir.z * speed);
            float size = (0.045F + random.nextFloat() * 0.068F) * scale;
            boolean dark = random.nextFloat() < 0.40F;
            float shade = random.nextBoolean() ? 0.95F : (0.68F + random.nextFloat() * 0.22F);
            this.voxels[i] = new Voxel(offset, velocity, size, shade, 0.76F + random.nextFloat() * 0.24F, random.nextFloat() * FxMath.TAU, dark);
         }
      }
   }

   private record Voxel(Vec3 offset, Vec3 velocity, float size, float shade, float alpha, float phase, boolean dark) {
      Vec3 position(float age, int impactAge, Vec3 targetCenter, Vec3 collapseCenter) {
         float elapsed = Math.max(0.0F, age - impactAge - 3.2F);
         // 轻阻尼悬浮飘散，不至于在坍缩前飞太远
         float driftTime = (float)(1.0 - Math.exp(-elapsed * 0.11)) / 0.11F;
         Vec3 drifting = targetCenter.add(this.offset).add(this.velocity.scale(driftTime));
         if (age < VoidDominionPattern.COLLAPSE_TICK || impactAge < 0) {
            return drifting;
         }
         float collapse = FxMath.easeInCubic(FxMath.window(age, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));
         // 向心螺旋卷吸：在拉向奇点的同时绕垂直轴轻旋，增强奇点引力感
         double dx = drifting.x - collapseCenter.x;
         double dy = drifting.y - collapseCenter.y;
         double dz = drifting.z - collapseCenter.z;
         double swirl = collapse * (1.2 + 0.5 * Math.sin(this.phase));
         double cos = Math.cos(swirl);
         double sin = Math.sin(swirl);
         double rx = dx * cos - dz * sin;
         double rz = dx * sin + dz * cos;
         double scale = 1.0 - collapse;
         return new Vec3(
            collapseCenter.x + rx * scale,
            collapseCenter.y + dy * scale,
            collapseCenter.z + rz * scale
         );
      }
   }
}
