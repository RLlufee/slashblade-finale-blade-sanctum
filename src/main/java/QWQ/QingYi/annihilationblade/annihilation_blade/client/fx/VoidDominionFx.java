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
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxPipeline;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/** 虚无权域「黑白世界格式化」五幕持续渲染器。 */
@OnlyIn(Dist.CLIENT)
public final class VoidDominionFx extends FxInstance {
   private static final int MAX_VISUAL_TARGETS = 16;
   private static final int MAX_VOXELS = 320;
   private static final float[] WHITE = {1.0F, 1.0F, 1.0F};
   private static final float[] GREY = {0.72F, 0.73F, 0.75F};
   private static final float[] BLACK = {0.0F, 0.0F, 0.0F};

   private Vec3 center;
   private Vec3 ground;
   private final float radius;
   private final float scale;
   private final int casterId;
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
      this.casterId = packet.casterId();
      this.phase = packet.intParam();
      this.collapseStarted = this.phase >= AnnihilationFxEvents.VOID_PHASE_COLLAPSE;
      this.updateGround(level);
      this.applySnapshot(level, packet, false);
   }

   @Override
   protected void onSustainedStart(int duration) {
      this.lifetime = Math.max(VoidDominionPattern.TOTAL_LIFETIME, duration);
   }

   @Override
   public void onSustainedUpdate(ClientLevel level, FxEventPacket packet) {
      this.center = packet.origin();
      this.updateGround(level);
      int nextPhase = packet.intParam();
      this.applySnapshot(level, packet, nextPhase >= AnnihilationFxEvents.VOID_PHASE_SWEEP);
      this.phase = nextPhase;
      this.collapseStarted |= nextPhase >= AnnihilationFxEvents.VOID_PHASE_COLLAPSE;
   }

   private void applySnapshot(ClientLevel level, FxEventPacket packet, boolean showDepartures) {
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

      if (showDepartures) {
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
      int assignedVoxels = 0;
      int targetsWithoutVoxels = 0;
      for (Erased target : this.targets.values()) {
         if (target.voxels.length == 0) {
            targetsWithoutVoxels++;
         } else {
            assignedVoxels += target.voxels.length;
         }
      }
      int remainingBudget = Math.max(0, MAX_VOXELS - assignedVoxels);
      int debrisPerTarget = targetsWithoutVoxels == 0 ? 0 : remainingBudget / targetsWithoutVoxels;
      int extraDebris = targetsWithoutVoxels == 0 ? 0 : remainingBudget % targetsWithoutVoxels;
      RandomSource random = RandomSource.create(packet.seed() ^ 0x71D0A11L);
      int targetIndex = 0;
      for (Erased target : this.targets.values()) {
         if (target.voxels.length == 0) {
            int targetBudget = debrisPerTarget + (targetIndex < extraDebris ? 1 : 0);
            if (targetBudget > 0) {
               target.createVoxels(random, targetBudget, this.scale);
            }
            targetIndex++;
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
      float height = Math.max(0.8F, entity.getBbHeight());
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

   private void updateGround(ClientLevel level) {
      int x = Mth.floor(this.center.x);
      int z = Mth.floor(this.center.z);
      double surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
      this.ground = new Vec3(this.center.x, surface + 0.05, this.center.z);
   }

   @Override
   public Vec3 focus() {
      return this.center;
   }

   @Override
   public double cullDistance() {
      return this.radius + 192.0;
   }

   @Override
   public void tick(ClientLevel level) {
      int age = this.ageTicks();
      if (age == 0 && this.phase == AnnihilationFxEvents.VOID_PHASE_START) {
         play(level, this.center, SoundEvents.CONDUIT_ACTIVATE, 1.0F, 0.58F);
         play(level, this.center, SoundEvents.BEACON_ACTIVATE, 0.7F, 1.65F);
      }
      if (age == VoidDominionPattern.CATALOG_END_TICK - 12) {
         play(level, this.center, SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.65F, 1.45F);
      }
      if (age == VoidDominionPattern.SWEEP_START_TICK) {
         play(level, this.center, SoundEvents.TRIDENT_RIPTIDE_1, 0.9F, 0.62F);
      }
      if (age == VoidDominionPattern.COLLAPSE_TICK) {
         play(level, this.center, SoundEvents.WARDEN_SONIC_BOOM, 1.2F, 0.58F);
         play(level, this.center, SoundEvents.BEACON_DEACTIVATE, 0.8F, 0.48F);
      }
      if (age == VoidDominionPattern.ZERO_SNAP_TICK) {
         play(level, this.center, SoundEvents.GLASS_BREAK, 0.7F, 1.25F);
         play(level, this.center, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 0.75F, 1.5F);
         play(level, this.center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.65F, 1.85F);
      }
      if (age == VoidDominionPattern.RESTORE_START_TICK + 4) {
         play(level, this.center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.28F, 1.2F);
      }

      int catalogCount = 0;
      for (Erased target : this.targets.values()) {
         if (this.activeTargets.contains(target.id)) {
            Entity entity = level.getEntity(target.id);
            if (entity != null) {
               target.foot = entity.position();
               target.width = Math.max(0.45F, entity.getBbWidth());
               target.height = Math.max(0.8F, entity.getBbHeight());
               target.center = target.foot.add(0.0, target.height * 0.5, 0.0);
               if (age >= VoidDominionPattern.CATALOG_END_TICK - 12 && age < VoidDominionPattern.SWEEP_START_TICK
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
      play(level, target.center, SoundEvents.UI_BUTTON_CLICK.value(), 0.33F, 1.45F + target.jitter * 0.45F);
      play(level, target.center, SoundEvents.GLASS_BREAK, 0.24F, 1.7F + target.jitter * 0.3F);
   }

   private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
      level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int age = this.ageTicks();
      if (age == 0) {
         for (int i = 0; i < 12; i++) {
            level.addParticle(ParticleTypes.END_ROD, this.center.x, this.center.y, this.center.z, 0.0, 0.015, 0.0);
         }
      }
      for (Erased target : this.targets.values()) {
         if (target.impactAge == age) {
            for (int i = 0; i < 8; i++) {
               level.addParticle(ParticleTypes.WHITE_ASH, target.center.x, target.center.y, target.center.z, 0.0, 0.035, 0.0);
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

   private float abortFade(float t) {
      return this.aborted ? 1.0F - FxMath.window(t, this.abortStartAge, this.abortStartAge + 8.0F) : 1.0F;
   }

   private float domainRadius(float t) {
      if (t < VoidDominionPattern.EXPAND_END_TICK) {
         return this.radius * FxMath.easeOutCubic(FxMath.window(t, 0.0F, VoidDominionPattern.EXPAND_END_TICK));
      }
      if (t < VoidDominionPattern.COLLAPSE_TICK) {
         return this.radius;
      }
      if (t < VoidDominionPattern.RESTORE_START_TICK) {
         return this.radius * (1.0F - FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.RESTORE_START_TICK)));
      }
      return 0.0F;
   }

   private float presence(float t) {
      float fadeIn = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 2.5F));
      float fadeOut = 1.0F - FxMath.window(t, VoidDominionPattern.TOTAL_LIFETIME - 4.0F, VoidDominionPattern.TOTAL_LIFETIME);
      return fadeIn * fadeOut * this.abortFade(t);
   }

   private static float nullScale(float t) {
      float x = FxMath.window(t, 0.0F, 2.6F);
      float overshoot = 1.0F + 0.7F * (float)Math.pow(1.0F - x, 2.0) * (float)Math.sin(x * 8.0F);
      return FxMath.easeOutCubic(x) * overshoot;
   }

   private float nullPresence(float t) {
      return (1.0F - FxMath.window(t, 65.0F, 69.0F)) * this.abortFade(t);
   }

   private float restoreRadius(float t) {
      return this.radius * FxMath.easeInOutSine(FxMath.window(t, VoidDominionPattern.RESTORE_START_TICK, VoidDominionPattern.TOTAL_LIFETIME));
   }

   @Override
   public boolean hasBody() {
      return true;
   }

   @Override
   public void renderBody(FxGeometry body, FxFrame frame) {
      float t = this.age(frame.partialTick);
      float alpha = this.presence(t);
      Vector3f center = frame.rel(this.center);
      this.renderNullCore(body, frame, center, t, alpha);
      body.begin(Style.AFTERIMAGE, 0.65F, 0.0F, 1.0F, 0.9F, 0.0F, 0.0F);
      for (Erased target : this.targets.values()) {
         float elapsed = t - target.impactAge;
         if (target.impactAge < 0 || elapsed < 0.0F || elapsed >= 9.0F) {
            continue;
         }
         float grow = FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 0.55F));
         float settle = 1.0F - 0.88F * FxMath.easeInCubic(FxMath.window(elapsed, 5.0F, 9.0F));
         float sx = Math.max(0.18F, target.width * 0.5F) * grow * settle;
         float sy = Math.max(0.3F, target.height * 0.5F) * grow * settle;
         float sz = Math.max(0.18F, target.width * 0.5F) * grow * settle;
         Vector3f c = frame.rel(target.center);
         body.orientedCube(c, new Vector3f(sx, 0.0F, 0.0F), new Vector3f(0.0F, sy, 0.0F), new Vector3f(0.0F, 0.0F, sz), 0.0F, 0.0F, 0.0F, alpha);
      }
   }

   private void renderNullCore(FxGeometry body, FxFrame frame, Vector3f center, float t, float alpha) {
      float presence = this.nullPresence(t) * nullScale(t) * alpha;
      if (presence <= 0.001F) {
         return;
      }
      float snap = FxMath.window(t, 61.0F, 64.0F);
      float vertical = 1.0F - FxMath.easeInCubic(snap);
      float horizontal = t < 64.0F ? 1.0F : Math.max(0.015F, 1.0F - FxMath.easeInCubic(FxMath.window(t, 64.0F, 65.4F)));
      Vector3f u = new Vector3f(frame.left).mul(1.25F * this.scale * horizontal);
      Vector3f v = new Vector3f(frame.up).mul(1.25F * this.scale * vertical);
      Vector3f w = viewVector(frame).mul(0.16F * this.scale);
      body.begin(Style.AFTERIMAGE, 0.9F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
      body.geoCenter(center);
      body.sphere(center, Math.max(0.025F, 0.42F * this.scale * horizontal), 8, 16, 0.0F, 0.0F, 0.0F, 0.0F, 0.95F * presence);
      body.orientedCube(center, u, v, w, 0.0F, 0.0F, 0.0F, 0.8F * presence);
      body.clearGeoCenter();
   }

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
      this.renderCatalogAndDeletions(geometry, frame, t, alpha);
   }

   private void renderNullMonolith(FxGeometry g, FxFrame frame, Vector3f center, float t, float alpha) {
      float glyph = this.nullPresence(t) * nullScale(t) * alpha;
      if (glyph <= 0.001F) {
         return;
      }

      float snap = FxMath.window(t, 61.0F, 64.0F);
      float vertical = 1.0F - FxMath.easeInCubic(snap);
      float horizontal = t < 64.0F ? 1.0F : Math.max(0.02F, 1.0F - FxMath.easeInCubic(FxMath.window(t, 64.0F, 65.4F)));
      float pulse = 1.0F + 0.18F * (float)Math.sin(t * 0.48F);
      Vector3f view = viewVector(frame).normalize();
      Vector3f tilt = new Vector3f(frame.up).fma(0.42F, frame.left).add(view).normalize();
      float ringRadius = 1.12F * this.scale * pulse * horizontal;

      g.begin(Style.RING, 4.6F, 0.0F, 0.95F, 0.025F, 0.0F, 0.0F);
      g.torus(center, view, ringRadius, Math.max(0.012F, 0.035F * this.scale * vertical), 48, 6, t * 0.025F, 1.0F, 1.0F, 1.0F, glyph);
      g.torus(center, tilt, ringRadius * 1.12F, Math.max(0.009F, 0.019F * this.scale * vertical), 48, 5, -t * 0.019F, 0.77F, 0.79F, 0.82F, glyph * 0.82F);
      g.torus(center, new Vector3f(frame.left).normalize(), ringRadius * 0.92F, Math.max(0.008F, 0.014F * this.scale * vertical), 40, 5, t * 0.032F, 0.9F, 0.91F, 0.93F, glyph * 0.62F);

      g.begin(Style.RIBBON, 6.5F, 0.0F, 8.0F, 0.0F, 0.0F, 0.25F);
      float slashAngle = (float)Math.toRadians(-48.0) + t * 0.012F;
      Vector3f slash = new Vector3f(frame.left).mul((float)Math.cos(slashAngle)).add(new Vector3f(frame.up).mul((float)Math.sin(slashAngle)));
      Vector3f slashExtent = new Vector3f(slash).mul(1.62F * this.scale * horizontal);
      Vector3f slashTop = new Vector3f(center).add(new Vector3f(slashExtent).mul(vertical)).add(new Vector3f(view).mul(0.1F));
      Vector3f slashBottom = new Vector3f(center).sub(new Vector3f(slashExtent).mul(vertical)).sub(new Vector3f(view).mul(0.1F));
      g.orientedCylinder(slashBottom, slashTop, view, 0.042F * this.scale, 0.085F * this.scale, 0.018F * this.scale, 0.036F * this.scale, 4, 1.0F, 1.0F, 1.0F, glyph);

      float caliperRadius = 1.42F * this.scale * horizontal;
      g.begin(Style.RIBBON, 3.0F, 0.0F, 7.0F, 0.0F, 0.0F, 0.15F);
      for (int i = 0; i < 4; i++) {
         float angle = (float)(Math.PI * 0.5 * i) + t * 0.007F;
         Vector3f radial = new Vector3f(frame.left).mul((float)Math.cos(angle)).add(new Vector3f(frame.up).mul((float)Math.sin(angle)));
         Vector3f tangent = new Vector3f(frame.left).mul(-(float)Math.sin(angle)).add(new Vector3f(frame.up).mul((float)Math.cos(angle)));
         Vector3f base = new Vector3f(center).fma(caliperRadius, radial);
         Vector3f tail = new Vector3f(base).fma(-0.16F * this.scale, radial);
         Vector3f a = new Vector3f(base).fma(-0.14F * this.scale, tangent);
         Vector3f b = new Vector3f(base).fma(0.14F * this.scale, tangent);
         this.line(g, tail, base, 0.018F * this.scale, 0.9F, 0.91F, 0.93F, glyph * 0.8F);
         this.line(g, a, b, 0.018F * this.scale, 0.9F, 0.91F, 0.93F, glyph * 0.8F);
      }
   }

   private void renderDomainArchitecture(FxGeometry g, FxFrame frame, Vector3f center, float t, float alpha) {
      float activeRadius = this.domainRadius(t);
      float architecture = alpha * (1.0F - FxMath.window(t, 66.0F, 69.0F));
      if (activeRadius < 0.2F || architecture <= 0.001F) {
         if (t >= VoidDominionPattern.RESTORE_START_TICK && t < VoidDominionPattern.TOTAL_LIFETIME) {
            this.renderRestoreScan(g, frame, t, alpha);
         }
         return;
      }

      Vector3f ground = frame.rel(this.ground);
      float grid = FxMath.window(t, 1.0F, 5.0F) * (1.0F - FxMath.window(t, 62.0F, 68.0F)) * architecture;
      g.begin(Style.RING, 2.2F, 0.0F, 0.95F, 0.008F, 0.0F, 0.0F);
      for (int i = 1; i <= 5; i++) {
         float ring = activeRadius * i / 5.0F;
         g.quad(ground, new Vector3f(ring, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, ring), 0.78F, 0.8F, 0.83F, grid * (i == 5 ? 0.7F : 0.35F));
      }
      g.begin(Style.RIBBON, 2.0F, 0.0F, 7.0F, 0.0F, 0.0F, 0.2F);
      for (int i = 0; i < 16; i++) {
         float angle = FxMath.TAU * i / 16.0F;
         Vector3f end = new Vector3f(ground).add((float)Math.cos(angle) * activeRadius, 0.0F, (float)Math.sin(angle) * activeRadius);
         this.line(g, ground, end, 0.012F, 0.75F, 0.77F, 0.8F, grid * (i % 2 == 0 ? 0.65F : 0.35F));
      }
      Vector3f axisX = new Vector3f(ground).add(activeRadius, 0.015F, 0.0F);
      Vector3f axisX0 = new Vector3f(ground).add(-activeRadius, 0.015F, 0.0F);
      Vector3f axisZ = new Vector3f(ground).add(0.0F, 0.015F, activeRadius);
      Vector3f axisZ0 = new Vector3f(ground).add(0.0F, 0.015F, -activeRadius);
      this.line(g, axisX0, axisX, 0.012F, 0.84F, 0.85F, 0.87F, grid * 0.75F);
      this.line(g, axisZ0, axisZ, 0.012F, 0.84F, 0.85F, 0.87F, grid * 0.75F);

      float skeleton = architecture * (0.38F + 0.16F * (float)Math.sin(t * 0.12F));
      g.begin(Style.RIBBON, 2.3F, 0.0F, 7.0F, 0.0F, 0.0F, 0.18F);
      for (int i = 0; i < 8; i++) {
         float longitude = FxMath.TAU * i / 8.0F + t * 0.003F;
         this.drawMeridian(g, frame, center, activeRadius, longitude, skeleton);
      }
      for (int i = 1; i <= 3; i++) {
         float latitude = (float)(Math.PI * i / 5.0);
         this.drawLatitude(g, center, activeRadius, latitude, 72, skeleton * 0.72F);
      }

      if (t >= VoidDominionPattern.SWEEP_START_TICK && t <= VoidDominionPattern.SWEEP_END_TICK + 1.0F) {
         float front = VoidDominionPattern.frontRadius(t, this.radius);
         float edgeFade = 1.0F - FxMath.window(t, VoidDominionPattern.SWEEP_END_TICK - 0.5F, VoidDominionPattern.SWEEP_END_TICK + 1.0F);
         float shellAlpha = alpha * edgeFade;
         Vector3f view = viewVector(frame).normalize();
         g.begin(Style.GLOW, 4.8F, 0.0F, 1.0F, 0.2F, 0.0F, 0.0F);
         g.geoCenter(center);
         g.sphere(center, Math.max(0.08F, front), 12, 24, t * 0.01F, 0.92F, 0.94F, 0.98F, shellAlpha * 0.22F);
         g.clearGeoCenter();
         g.begin(Style.RING, 5.5F, 0.0F, 0.95F, 0.02F, 0.0F, 0.0F);
         g.torus(center, view, Math.max(0.1F, front), 0.035F * this.scale, 56, 6, t * 0.04F, 1.0F, 1.0F, 1.0F, shellAlpha);
         g.begin(Style.RIBBON, 4.4F, 0.0F, 8.0F, 0.0F, 0.0F, 0.2F);
         this.drawMeridian(g, frame, center, front, t * 0.009F, shellAlpha * 0.68F);
         this.drawMeridian(g, frame, center, front, t * 0.009F + (float)Math.PI * 0.5F, shellAlpha * 0.68F);
         Vector3f groundEdge = new Vector3f(ground).add(0.0F, 0.018F, front);
         this.line(g, new Vector3f(ground).add(0.0F, 0.018F, -front), groundEdge, 0.032F, 1.0F, 1.0F, 1.0F, shellAlpha);
      }

      if (t >= VoidDominionPattern.COLLAPSE_TICK && t < VoidDominionPattern.ZERO_SNAP_TICK + 2.0F) {
         float collapse = FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));
         float filament = alpha * (0.42F + 0.58F * collapse);
         g.begin(Style.RIBBON, 4.0F, 0.0F, 7.0F, 0.0F, 0.0F, 0.2F);
         for (int i = 0; i < 12; i++) {
            float angle = FxMath.TAU * i / 12.0F + t * 0.018F;
            Vector3f radial = new Vector3f((float)Math.cos(angle), (float)Math.sin(angle) * 0.45F, (float)Math.sin(angle));
            Vector3f from = new Vector3f(center).fma(this.radius * (1.0F - collapse * 0.12F), radial);
            Vector3f to = new Vector3f(center).fma(Math.max(0.03F, activeRadius * 0.22F), radial);
            this.line(g, from, to, 0.025F, 0.86F, 0.88F, 0.92F, filament);
         }
      }

      this.renderRestoreScan(g, frame, t, alpha);
   }

   private void renderRestoreScan(FxGeometry g, FxFrame frame, float t, float alpha) {
      if (t < VoidDominionPattern.RESTORE_START_TICK || t >= VoidDominionPattern.TOTAL_LIFETIME) {
         return;
      }
      float restore = this.restoreRadius(t);
      float fade = (1.0F - FxMath.window(t, 86.0F, 92.0F)) * alpha;
      Vector3f center = frame.rel(this.center);
      g.begin(Style.RING, 3.4F, 0.0F, 0.95F, 0.015F, 0.0F, 0.0F);
      g.quad(frame.rel(this.ground), new Vector3f(restore, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, restore), 0.92F, 0.93F, 0.95F, fade * 0.7F);
      g.begin(Style.RING, 4.2F, 0.0F, 0.95F, 0.022F, 0.0F, 0.0F);
      g.torus(center, new Vector3f(0.0F, 1.0F, 0.0F), Math.max(0.04F, restore), 0.03F * this.scale, 56, 5, t * 0.025F, 0.95F, 0.96F, 0.98F, fade);
   }

   private void drawMeridian(FxGeometry g, FxFrame frame, Vector3f center, float radius, float longitude, float alpha) {
      List<Vector3f> points = new ArrayList<>(25);
      float[] widths = new float[25];
      float[] alphas = new float[25];
      for (int i = 0; i <= 24; i++) {
         float latitude = (float)(Math.PI * i / 24.0);
         float x = radius * (float)Math.sin(latitude) * (float)Math.cos(longitude);
         float y = radius * (float)Math.cos(latitude);
         float z = radius * (float)Math.sin(latitude) * (float)Math.sin(longitude);
         points.add(new Vector3f(center).add(x, y, z));
         widths[i] = 0.012F * this.scale;
         alphas[i] = alpha;
      }
      g.ribbon(points, widths, alphas, 0.8F, 0.82F, 0.85F);
   }

   private void drawLatitude(FxGeometry g, Vector3f center, float radius, float latitude, int segments, float alpha) {
      float ringRadius = radius * (float)Math.sin(latitude);
      float y = radius * (float)Math.cos(latitude);
      List<Vector3f> points = new ArrayList<>(segments + 1);
      float[] widths = new float[segments + 1];
      float[] alphas = new float[segments + 1];
      for (int i = 0; i <= segments; i++) {
         float angle = FxMath.TAU * i / segments;
         points.add(new Vector3f(center).add(ringRadius * (float)Math.cos(angle), y, ringRadius * (float)Math.sin(angle)));
         widths[i] = 0.009F * this.scale;
         alphas[i] = alpha;
      }
      g.ribbon(points, widths, alphas, 0.77F, 0.79F, 0.82F);
   }

   private void renderCatalogAndDeletions(FxGeometry g, FxFrame frame, float t, float alpha) {
      float catalog = FxMath.window(t, 14.0F, 17.0F) * (1.0F - FxMath.window(t, 25.0F, 27.0F)) * alpha;
      g.begin(Style.RIBBON, 3.3F, 0.0F, 7.0F, 0.0F, 0.0F, 0.18F);
      int rendered = 0;
      for (Erased target : this.targets.values()) {
         boolean catalogTarget = rendered < MAX_VISUAL_TARGETS;
         Vector3f foot = frame.rel(target.foot);
         Vector3f top = frame.rel(target.foot.add(0.0, target.height + 0.35, 0.0));
         if (catalogTarget && catalog > 0.001F && this.activeTargets.contains(target.id)) {
            this.drawSelectionBox(g, frame, target, catalog);
            this.line(g, foot, top, 0.012F, 0.86F, 0.88F, 0.91F, catalog * 0.55F);
            Vector3f base = frame.rel(target.foot.add(0.0, 0.035, 0.0));
            g.begin(Style.RING, 2.3F, 0.0F, 0.95F, 0.016F, 0.0F, 0.0F);
            g.quad(base, new Vector3f(target.width * 0.72F, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, target.width * 0.72F), 0.92F, 0.93F, 0.95F, catalog * 0.8F);
            Vector3f cursor = new Vector3f(top).add(0.0F, 0.14F, 0.0F);
            g.begin(Style.AFTERIMAGE, 2.7F, 0.0F, 1.0F, 0.9F, 0.0F, 0.0F);
            g.cube(cursor, 0.055F * this.scale, 0.055F * this.scale, 0.055F * this.scale, WHITE[0], WHITE[1], WHITE[2], catalog);
         }
         if ((catalogTarget && catalog > 0.001F && this.activeTargets.contains(target.id)) || target.impactAge >= 0) {
            g.begin(Style.AFTERIMAGE, 1.7F, 0.0F, 1.0F, 0.8F, 0.0F, 0.0F);
            this.renderCapturedModel(g, frame, target, t, catalog > 0.001F ? catalog : alpha);
         }
         this.renderTargetDeletion(g, frame, target, t, alpha);
         rendered++;
      }

      float restore = (1.0F - FxMath.window(t, 68.0F, 92.0F)) * alpha;
      if (restore > 0.001F) {
         g.begin(Style.AFTERIMAGE, 2.0F, 0.0F, 1.0F, 0.9F, 0.0F, 0.0F);
         for (Erased target : this.targets.values()) {
            for (Voxel voxel : target.voxels) {
               Vector3f p = frame.rel(voxel.position(t, target.impactAge, this.center));
               float size = voxel.size * (0.35F + 0.65F * restore);
               g.cube(p, size, size, size, voxel.shade, voxel.shade, voxel.shade, restore * voxel.alpha);
            }
         }
      }

      this.renderGroundMarks(g, frame, t, alpha);
   }

   private void drawSelectionBox(FxGeometry g, FxFrame frame, Erased target, float alpha) {
      Vector3f center = frame.rel(target.center);
      float hx = target.width * 0.62F;
      float hy = target.height * 0.59F;
      float hz = target.width * 0.62F;
      Vector3f[] corners = boxCorners(center, hx, hy, hz);
      for (int i = 0; i < corners.length; i++) {
         for (int axis = 1; axis <= 4; axis <<= 1) {
            if ((i & axis) == 0) {
               int j = i | axis;
               Vector3f delta = new Vector3f(corners[j]).sub(corners[i]);
               float length = delta.length();
               if (length > 0.0F) {
                  delta.normalize().mul(Math.min(length, 0.22F * this.scale));
                  this.line(g, corners[i], new Vector3f(corners[i]).add(delta), 0.016F * this.scale,
                     0.92F, 0.93F, 0.95F, alpha);
               }
            }
         }
      }
   }

   private void renderCapturedModel(FxGeometry g, FxFrame frame, Erased target, float t, float alpha) {
      float[] quads = target.capturedQuads;
      if (quads == null) {
         return;
      }
      float elapsed = target.impactAge < 0 ? 0.0F : t - target.impactAge;
      if (elapsed < 0.0F || (target.impactAge >= 0 && elapsed >= 9.0F)) {
         return;
      }
      float fade = target.impactAge < 0 ? 0.44F : 1.0F - FxMath.window(elapsed, 5.0F, 9.0F);
      Vector3f right = new Vector3f(frame.left).mul(0.08F * this.scale);
      for (int i = 0; i + 11 < quads.length; i += 12) {
         float averageY = (quads[i + 1] + quads[i + 4] + quads[i + 7] + quads[i + 10]) * 0.25F;
         int band = Mth.clamp((int)(averageY / target.height * 8.0F), 0, 7);
         float bandOffset = target.impactAge < 0 ? 0.0F
            : ((band % 2 == 0 ? 1.0F : -1.0F) * (0.06F + 0.045F * band)) * FxMath.window(elapsed, 0.0F, 0.4F);
         float jitter = target.impactAge < 0 ? 0.0F : 0.018F * (float)Math.sin(t * 7.0F + band * 4.8F + target.jitter * 12.0F);
         Vector3f shift = new Vector3f(right).mul(bandOffset + jitter);
         Vector3f a = frame.rel(target.foot.add(quads[i], quads[i + 1], quads[i + 2])).add(shift);
         Vector3f b = frame.rel(target.foot.add(quads[i + 3], quads[i + 4], quads[i + 5])).add(shift);
         Vector3f c = frame.rel(target.foot.add(quads[i + 6], quads[i + 7], quads[i + 8])).add(shift);
         Vector3f d = frame.rel(target.foot.add(quads[i + 9], quads[i + 10], quads[i + 11])).add(shift);
         float shade = band % 3 == 0 ? 0.92F : 0.72F;
         g.rawQuad(a, b, c, d, shade, shade, shade, alpha * fade * 0.64F);
      }
   }

   private void renderTargetDeletion(FxGeometry g, FxFrame frame, Erased target, float t, float alpha) {
      if (target.impactAge < 0) {
         return;
      }
      float elapsed = t - target.impactAge;
      if (elapsed < 0.0F || elapsed > 28.0F) {
         return;
      }
      float flash = 1.0F - FxMath.window(elapsed, 0.3F, 2.2F);
      float lineWidth = Math.max(1.2F, target.width * 1.6F) * this.scale;
      Vector3f center = frame.rel(target.center);
      Vector3f start = new Vector3f(center).sub(new Vector3f(frame.left).mul(lineWidth * 0.55F));
      Vector3f end = new Vector3f(center).add(new Vector3f(frame.left).mul(lineWidth * 0.55F));
      g.begin(Style.RIBBON, 8.0F, 0.0F, 9.0F, 0.0F, 0.0F, 0.2F);
      this.line(g, start, end, 0.035F * this.scale, 1.0F, 1.0F, 1.0F, flash * alpha);
      Vector3f vertical = new Vector3f(frame.up).mul(target.height * 0.58F);
      this.line(g, new Vector3f(center).sub(vertical), new Vector3f(center).add(vertical), 0.018F * this.scale, 0.88F, 0.9F, 0.93F, flash * alpha * 0.7F);

      float box = FxMath.easeOutCubic(FxMath.window(elapsed, 0.0F, 0.7F))
         * (1.0F - 0.92F * FxMath.easeInCubic(FxMath.window(elapsed, 5.0F, 10.0F)));
      if (box > 0.001F) {
         float hx = Math.max(0.25F, target.width * 0.52F) * box;
         float hy = Math.max(0.4F, target.height * 0.52F) * box;
         float hz = Math.max(0.25F, target.width * 0.52F) * box;
         Vector3f[] corners = boxCorners(center, hx, hy, hz);
         g.begin(Style.RIBBON, 4.1F, 0.0F, 8.0F, 0.0F, 0.0F, 0.16F);
         for (int i = 0; i < corners.length; i++) {
            for (int axis = 1; axis <= 4; axis <<= 1) {
               if ((i & axis) == 0) {
                  this.line(g, corners[i], corners[i | axis], 0.018F * this.scale, 0.96F, 0.97F, 0.98F, box * alpha);
               }
            }
         }
      }

      float debrisAge = elapsed - 5.0F;
      if (debrisAge >= 0.0F && debrisAge < 30.0F) {
         float collapse = FxMath.easeInCubic(FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));
         g.begin(Style.AFTERIMAGE, 2.6F, 0.0F, 1.0F, 0.9F, 0.0F, 0.0F);
         for (Voxel voxel : target.voxels) {
            Vector3f position = frame.rel(voxel.position(t, target.impactAge, this.center));
            float size = voxel.size * (1.0F - 0.55F * FxMath.window(debrisAge, 18.0F, 30.0F)) * (1.0F - collapse * 0.76F);
            float flicker = 0.72F + 0.28F * (float)Math.sin(t * 1.7F + voxel.phase);
            g.cube(position, size, size, size, voxel.shade, voxel.shade, voxel.shade, alpha * voxel.alpha * flicker);
         }
      }
   }

   private void renderGroundMarks(FxGeometry g, FxFrame frame, float t, float alpha) {
      int rendered = 0;
      for (Erased target : this.targets.values()) {
         if (target.impactAge < 0 || rendered >= MAX_VISUAL_TARGETS) {
            continue;
         }
         float elapsed = t - target.impactAge;
         if (elapsed < 0.0F || elapsed > 18.0F) {
            continue;
         }
         Vector3f mark = frame.rel(new Vec3(target.foot.x, this.ground.y + 0.035, target.foot.z));
         float size = Math.max(0.32F, target.width * 0.48F) * (1.0F + 0.25F * FxMath.window(elapsed, 0.0F, 3.0F));
         float fade = (1.0F - FxMath.window(elapsed, 11.0F, 18.0F)) * alpha;
         g.begin(Style.AFTERIMAGE, 0.55F, 0.0F, 1.0F, 0.9F, 0.0F, 0.0F);
         g.quad(mark, new Vector3f(size, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, size), BLACK[0], BLACK[1], BLACK[2], fade * 0.86F);
         g.begin(Style.RING, 3.2F, 0.0F, 0.95F, 0.018F, 0.0F, 0.0F);
         g.quad(mark.add(0.0F, 0.008F, 0.0F), new Vector3f(size * 1.08F, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, size * 1.08F), WHITE[0], WHITE[1], WHITE[2], fade);
         rendered++;
      }
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

   private void line(FxGeometry g, Vector3f from, Vector3f to, float width, float r, float green, float blue, float alpha) {
      if (alpha <= 0.001F) {
         return;
      }
      g.ribbon(List.of(from, to), new float[]{width, width}, new float[]{alpha, alpha}, r, green, blue);
   }

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      float t = this.age(frame.partialTick);
      float present = this.presence(t);
      if (present <= 0.001F) {
         return;
      }
      Vector3f center = frame.rel(this.center);
      float radius = t >= VoidDominionPattern.RESTORE_START_TICK ? this.restoreRadius(t) : this.domainRadius(t);
      float front = t >= VoidDominionPattern.SWEEP_START_TICK && t <= VoidDominionPattern.SWEEP_END_TICK
         ? VoidDominionPattern.frontRadius(t, this.radius) : this.radius;
      float mosaic;
      if (t < VoidDominionPattern.EXPAND_END_TICK) {
         mosaic = Mth.lerp(FxMath.window(t, 0.0F, 3.0F), 1.0F, 16.0F);
      } else if (t < VoidDominionPattern.RESTORE_START_TICK) {
         mosaic = Mth.lerp(FxMath.window(t, VoidDominionPattern.ZERO_SNAP_TICK, VoidDominionPattern.RESTORE_START_TICK), 16.0F, 9.0F);
      } else {
         float restore = FxMath.window(t, VoidDominionPattern.RESTORE_START_TICK, VoidDominionPattern.TOTAL_LIFETIME);
         mosaic = restore < 0.28F ? 16.0F : (restore < 0.55F ? 8.0F : (restore < 0.8F ? 4.0F : 1.0F));
      }
      float fineGrid = present * (0.22F + 0.18F * FxMath.window(t, 0.0F, 8.0F));
      float mainGrid = present * (0.24F + 0.25F * FxMath.window(t, 2.0F, 14.0F));
      float desaturate = t < VoidDominionPattern.RESTORE_START_TICK ? 0.96F : 1.0F - FxMath.window(t, VoidDominionPattern.RESTORE_START_TICK, VoidDominionPattern.TOTAL_LIFETIME);
      float invert = 0.86F * FxMath.window(t, 54.0F, 54.6F) * (1.0F - FxMath.window(t, 56.0F, 58.0F));
      float restoreFront = t < VoidDominionPattern.RESTORE_START_TICK ? 0.0F : this.restoreRadius(t);
      float glitch = t >= VoidDominionPattern.SWEEP_START_TICK && t <= VoidDominionPattern.SWEEP_END_TICK ? 0.72F : 0.0F;
      float phase = t < 14.0F ? 0.0F : t < 26.0F ? 1.0F : t < 54.0F ? 2.0F : t < 68.0F ? 3.0F : 4.0F;
      Vector4fData data = new Vector4fData(
         center, radius, present, mosaic, front, 0.62F, fineGrid, mainGrid, desaturate, invert,
         (float)(frame.camera.x - Math.floor(frame.camera.x)), (float)(frame.camera.y - Math.floor(frame.camera.y)),
         (float)(frame.camera.z - Math.floor(frame.camera.z)), this.radius, 1.0F, glitch, restoreFront, phase
      );
      if (FxShaders.eraseScene != null) {
         out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.eraseScene, shader -> {
            shader.safeGetUniform("Dome").set(data.center.x, data.center.y, data.center.z, data.radius);
            shader.safeGetUniform("Erase").set(data.presence, data.mosaic, data.front, data.frontWidth);
            shader.safeGetUniform("Grid").set(data.fineGrid, data.mainGrid, data.desaturate, data.invert);
            shader.safeGetUniform("CamFract").set(data.camX, data.camY, data.camZ, data.baseRadius);
            shader.safeGetUniform("Extra").set(data.wipedStrength, data.glitch, data.restoreRadius, data.phase);
         }));
      }
      if (FxShaders.eraseVolume != null) {
         float zeroSnap = FxMath.envelope(FxMath.window(t, 63.4F, 65.4F), 0.18F, 0.62F);
         float collapse = FxMath.window(t, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK);
         float volumeStrength = present * (t >= VoidDominionPattern.RESTORE_START_TICK ? 0.18F : 1.0F);
         float age = t;
         out.add(new FxPass(FxPass.Kind.VOLUME, () -> FxShaders.eraseVolume, shader -> {
            shader.safeGetUniform("Dome").set(data.center.x, data.center.y, data.center.z, Math.max(0.01F, data.radius));
            shader.safeGetUniform("Erase").set(data.presence, data.front, 0.48F, volumeStrength);
            shader.safeGetUniform("Phase").set(age, collapse, volumeStrength, zeroSnap);
            shader.safeGetUniform("Extra").set(data.baseRadius, restoreFront, this.scale, this.radius);
         }));
      }
   }

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      float t = this.age(frame.partialTick);
      float distance = (float)frame.camera.distanceTo(this.center);
      float attenuation = 1.0F - FxMath.window(distance, this.radius + 10.0F, this.radius + 48.0F);
      if (attenuation <= 0.001F) {
         return;
      }
      float inside = 1.0F - FxMath.window(distance, this.radius - 2.0F, this.radius + 2.0F);
      if (inside > 0.0F && t < VoidDominionPattern.RESTORE_START_TICK) {
         screen.addDesaturate(0.82F * inside * this.presence(t));
      }
      if (t < 3.0F) {
         screen.addFlash(0xFFFFFF, 0.24F * (1.0F - t / 3.0F) * attenuation, 0.32F);
      }
      if (t >= 54.0F && t < 57.5F) {
         float hit = 1.0F - FxMath.window(t, 54.0F, 57.5F);
         screen.addImpactFrame(0.86F * hit * attenuation, hit > 0.36F);
         screen.addDashBlur(0.62F * hit * attenuation, frame.screenUv(this.center));
         screen.addFovKick(-0.07F * hit * attenuation);
         screen.addBloomBoost(0.42F * hit * attenuation);
      } else if (t >= 57.5F && t < 64.0F) {
         float collapse = FxMath.easeInCubic(FxMath.window(t, 57.5F, 64.0F));
         screen.addDashBlur(0.36F * collapse * attenuation, frame.screenUv(this.center));
         screen.addAberration(0.12F * collapse * attenuation);
         screen.addFovKick(-0.045F * collapse * attenuation);
      }
      if (t >= 63.6F && t < 66.0F) {
         float snap = 1.0F - FxMath.window(t, 64.0F, 66.0F);
         screen.addFlash(0xFFFFFF, 0.52F * snap * attenuation, 0.7F);
         screen.addImpactFrame(0.76F * snap * attenuation, false);
         screen.addAberration(0.2F * snap * attenuation);
      }
   }

   @Override
   public float shake(FxFrame frame) {
      float t = this.age(frame.partialTick);
      float distance = (float)frame.camera.distanceTo(this.center);
      float attenuation = 1.0F - FxMath.window(distance, this.radius + 4.0F, this.radius + 42.0F);
      if (t < 4.0F) {
         return 0.18F * (1.0F - t / 4.0F) * attenuation;
      }
      if (t >= 54.0F && t < 60.0F) {
         return 0.64F * (1.0F - FxMath.window(t, 54.0F, 60.0F)) * attenuation;
      }
      if (t >= 64.0F && t < 68.0F) {
         return 0.3F * (1.0F - FxMath.window(t, 64.0F, 68.0F)) * attenuation;
      }
      return 0.0F;
   }

   private static final class Erased {
      final int id;
      Vec3 foot;
      Vec3 center;
      float width;
      float height;
      int eraseTick;
      int impactAge = -1;
      float jitter;
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
            Vector3f direction = FxMath.randomUnit(random);
            float lift = random.nextFloat() * this.height * 0.72F;
            Vec3 start = this.center.add(direction.x * this.width * 0.48, lift - this.height * 0.36, direction.z * this.width * 0.48);
            double speed = 0.035 + random.nextDouble() * 0.08;
            Vec3 velocity = new Vec3(direction.x * speed, 0.045 + random.nextDouble() * 0.12, direction.z * speed);
            float size = (0.045F + random.nextFloat() * 0.07F) * scale;
            float shade = random.nextBoolean() ? 0.92F : (0.46F + random.nextFloat() * 0.3F);
            this.voxels[i] = new Voxel(start, velocity, size, shade, 0.7F + random.nextFloat() * 0.3F, random.nextFloat() * FxMath.TAU);
         }
      }
   }

   private record Voxel(Vec3 origin, Vec3 velocity, float size, float shade, float alpha, float phase) {
      Vec3 position(float age, int impactAge, Vec3 collapseCenter) {
         float elapsed = Math.max(0.0F, age - impactAge - 5.0F);
         Vec3 drifting = this.origin.add(this.velocity.scale(elapsed));
         if (age < VoidDominionPattern.COLLAPSE_TICK || impactAge < 0) {
            return drifting;
         }
         float collapse = FxMath.easeInCubic(FxMath.window(age, VoidDominionPattern.COLLAPSE_TICK, VoidDominionPattern.ZERO_SNAP_TICK));
         return new Vec3(
            Mth.lerp(collapse, drifting.x, collapseCenter.x),
            Mth.lerp(collapse, drifting.y, collapseCenter.y),
            Mth.lerp(collapse, drifting.z, collapseCenter.z)
         );
      }
   }

   private record Vector4fData(Vector3f center, float radius, float presence, float mosaic, float front, float frontWidth,
                               float fineGrid, float mainGrid, float desaturate, float invert, float camX, float camY, float camZ,
                               float baseRadius, float wipedStrength, float glitch, float restoreRadius, float phase) {
   }
}
