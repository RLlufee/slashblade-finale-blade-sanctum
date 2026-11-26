package QWQ.QingYi.annihilationblade.stellaris.debug;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisCloudSuppression;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisEffect;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisPass;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisQuality;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisShaders;
import QWQ.QingYi.annihilationblade.stellaris.optics.AudioSilencer;
import QWQ.QingYi.annihilationblade.stellaris.optics.ImpactFrameSequencer;
import QWQ.QingYi.annihilationblade.stellaris.optics.StellarisOpticsState;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisParticles;
import java.util.List;
import java.util.Random;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * Step 1.5 专属全链路光学与深空实机沙盒测试特效（仅由 {@code /stellaris debug test <mode>} 触发，零玩法影响）。
 * <p>
 * 覆盖全部 8 阶段线性 HDR 帧图与全部 5 个 {@code st_*.glsl} 公共库：
 * <ul>
 *   <li>{@code optics}：完整星核 MRT + 原生线性浑天仪闭合双环 + 开普勒旋涡粒子 + 双频泛光 + 无串珠星芒 + 体积光 + 自动曝光；</li>
 *   <li>{@code scene}：阶段 4 场景乒乓 + 无缝暗尘埃银河/星场/星系/极光夺夜 + 3×3 法线起伏地表遥测/七段读数 + 解析微虫洞；</li>
 *   <li>{@code bloom / streak / godrays / holdframe / impact_safe / impact_full / particles / silence / clouds}：各子系统独立体检。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisDebugSandboxEffect extends StellarisEffect {
   public enum Mode {
      OPTICS(240),
      SCENE(260),
      BLOOM(160),
      STREAK(160),
      GODRAYS(200),
      HOLDFRAME(90),
      IMPACT_SAFE(50),
      IMPACT_FULL(50),
      PARTICLES(180),
      SILENCE(50),
      CLOUDS(140);

      final int durationTicks;

      Mode(int durationTicks) {
         this.durationTicks = durationTicks;
      }
   }

   private final Mode mode;
   private final Vec3 center;
   private final float radius;
   private final Random rng = new Random(0x57E11A215L);
   private boolean holdTriggered;

   public StellarisDebugSandboxEffect(Mode mode, Vec3 center) {
      super(mode.durationTicks);
      this.mode = mode;
      this.center = center;
      this.radius = 2.2F;
   }

   @Override
   public Vec3 focus() {
      return this.center;
   }

   @Override
   public boolean hasBody() {
      return this.mode == Mode.OPTICS || this.mode == Mode.BLOOM;
   }

   @Override
   public void tick(ClientLevel level) {
      if (this.mode == Mode.CLOUDS) {
         StellarisCloudSuppression.requestSuppress(4);
      } else if (this.mode == Mode.SCENE) {
         // 等待夺夜幕布展开（>= 10t）再交接抑制原版方云，并在消退前平滑释放，避免晴空首帧凭空闪灭
         if (this.ageTicks() >= 10 && this.ageTicks() <= this.lifetime - 20) {
            StellarisCloudSuppression.requestSuppress(3);
         }
      } else if (this.mode == Mode.SILENCE) {
         if (this.ageTicks() >= 5 && this.ageTicks() < 30) {
            AudioSilencer.requestSilence(3);
         }
      }
   }

   @Override
   public void emitParticles(ClientLevel level, StellarisParticles particles) {
      if (this.mode != Mode.OPTICS && this.mode != Mode.PARTICLES) {
         return;
      }
      int perTick = this.mode == Mode.PARTICLES ? 64 : 26;
      for (int i = 0; i < perTick; i++) {
         double ang = this.rng.nextDouble() * Math.PI * 2.0;
         double dist = this.radius * (1.05 + this.rng.nextDouble() * 2.45);
         double tiltY = (this.rng.nextDouble() - 0.5) * 0.85;
         double px = this.center.x + Math.cos(ang) * dist;
         double py = this.center.y + tiltY + Math.sin(ang) * dist * 0.28;
         double pz = this.center.z + Math.sin(ang) * dist;
         int rgb = this.rng.nextFloat() < 0.65F ? 0x1DF5FF : (this.rng.nextFloat() < 0.72F ? 0xEFFBFF : 0x7C5CFF);
         float emit = 2.2F + this.rng.nextFloat() * 4.5F;
         particles.spawn(
            px, py, pz,
            0.0F, 0.0F, 0.0F,
            this.center.x, this.center.y, this.center.z,
            0.22F, 0.94F, 0.25F,
            0.34F,
            0.045F + this.rng.nextFloat() * 0.065F,
            1.6F,
            rgb, emit,
            42 + this.rng.nextInt(36),
            StellarisParticles.Operator.KEPLER_VORTEX
         );
      }
   }

   @Override
   public void tickFallback(ClientLevel level) {
      if (this.ageTicks() % 4 == 0) {
         level.addParticle(ParticleTypes.END_ROD, this.center.x, this.center.y, this.center.z, 0.0, 0.02, 0.0);
      }
   }

   @Override
   public void renderBody(FxGeometry body, FxFrame frame) {
      float age = this.age(frame.partialTick);
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.82F);
      if (env <= 0.01F) {
         return;
      }
      // 修复：torus 正确搭配 Style.RIBBON（uv.x=沿环0..1, uv.y=管截面-1..1），绘制完整闭合深空暗环
      Vector3f rel = frame.rel(this.center);
      Vector3f axis = new Vector3f(0.22F, 0.94F, 0.25F).normalize();
      body.begin(FxGeometry.Style.RIBBON, 0.65F, 0.35F, 2.2F, 0.0F, 0.0F, 0.25F);
      body.torus(rel, axis, this.radius * 1.44F, 0.075F, 64, 8, age * 0.03F, 0.02F, 0.04F, 0.14F, 0.82F * env);
      body.end();
   }

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      if (this.mode == Mode.CLOUDS || this.mode == Mode.SILENCE || this.mode == Mode.PARTICLES || this.mode == Mode.SCENE) {
         return;
      }
      float age = this.age(frame.partialTick);
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.84F);
      if (env <= 0.01F) {
         return;
      }
      Vector3f rel = frame.rel(this.center);
      Vector3f axisA = new Vector3f(0.22F, 0.94F, 0.25F).normalize();
      Vector3f axisB = new Vector3f(-0.46F, 0.82F, 0.34F).normalize();

      // 修复：torus 正确搭配 Style.RIBBON + 浑天仪精密刻度段数（36 / 24 段），呈现完整锐利的冰青/电紫双轨道环
      float emitBoost = this.mode == Mode.HOLDFRAME ? 7.5F : (this.mode == Mode.STREAK ? 8.2F : 5.4F);
      g.begin(FxGeometry.Style.RIBBON, emitBoost * env, 0.25F, 3.6F, 36.0F, 0.35F, 0.35F);
      g.torus(rel, axisA, this.radius * 1.95F, 0.036F, 72, 8, age * 0.030F, 0.11F, 0.96F, 1.00F, 0.95F * env);
      g.end();

      g.begin(FxGeometry.Style.RIBBON, emitBoost * 0.88F * env, 0.25F, 3.4F, 24.0F, -0.45F, 0.30F);
      g.torus(rel, axisB, this.radius * 1.52F, 0.028F, 64, 8, -age * 0.048F, 0.49F, 0.36F, 1.00F, 0.90F * env);
      g.end();

      // 仅在独立 BLOOM 模式绘制紧凑高锐度冰青星核公告板（绝不在 HOLDFRAME/STREAK 的黑洞奇点上硬贴巨型白球遮住黑洞！）
      if (this.mode == Mode.BLOOM) {
         g.begin(FxGeometry.Style.GLOW, 6.2F * env, 0.2F, 12.0F, 3.2F, 0.85F, 0.0F);
         g.billboard(rel, this.radius * 0.62F, 0.0F, 0.42F, 0.94F, 1.00F, env);
         g.end();
      }
   }

   @Override
   public void collectPasses(FxFrame frame, StellarisQuality.Spec quality, List<StellarisPass> out) {
      float age = this.age(frame.partialTick);
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.84F);
      if (env <= 0.01F) {
         return;
      }

      if (this.mode == Mode.SCENE) {
         if (StellarisShaders.debugScene != null) {
            Vector3f rel = frame.rel(this.center);
            float readoutVal = 42.85F - (age / this.lifetime) * 42.85F;
            out.add(StellarisPass.scene(
               () -> StellarisShaders.debugScene,
               shader -> {
                  shader.safeGetUniform("AnchorPos").set(rel.x, rel.y - 1.5F, rel.z, 15.0F);
                  shader.safeGetUniform("SceneParams").set(env, 0.94F, 0.075F, Math.max(0.0F, readoutVal));
               },
               null
            ));
         }
         return;
      }

      if (this.mode != Mode.OPTICS && this.mode != Mode.GODRAYS && this.mode != Mode.STREAK && this.mode != Mode.HOLDFRAME) {
         return;
      }
      if (StellarisShaders.debugHeavyMrt == null) {
         return;
      }

      // 使用公共精确球体投影裁剪框：背对星核时返回 EMPTY，直接 0 开销跳过重型 MRT！
      StellarisPass.ScreenRect rect = StellarisPass.projectSphereRect(frame, this.center, this.radius * 2.85F);
      if (rect != null && rect.empty()) {
         return;
      }

      Vector3f rel = frame.rel(this.center);
      float energy = (this.mode == Mode.HOLDFRAME ? 1.45F : 1.25F) * env;
      float tilt = 0.36F + (float)Math.sin(age * 0.035F) * 0.16F;

      out.add(StellarisPass.heavyMrt(
         () -> StellarisShaders.debugHeavyMrt,
         shader -> {
            shader.safeGetUniform("CoreSphere").set(rel.x, rel.y, rel.z, this.radius);
            shader.safeGetUniform("CoreParams").set(energy, tilt, age / this.lifetime, 0.0F);
         },
         null,
         rect
      ));
   }

   @Override
   public void contributeOptics(FxFrame frame, StellarisOpticsState optics) {
      float age = this.age(frame.partialTick);
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.84F);
      float[] uv = frame.screenUv(this.center);

      switch (this.mode) {
         case OPTICS -> {
            optics.addBloomMultiplier(1.25F);
            optics.addAnamorphicStreak(1.15F * env, 1.55F, 0x8EF7FF);
            optics.addGodRaySource(frame, this.center, 1.15F * env, 0.968F, 0xA8F7FF);
            optics.enableAutoExposure(0.85F * env, 0.48F, 0.42F, 0.52F);
            optics.addVignette(0x02040D, 0.24F * env);
         }
         case SCENE -> {
            optics.addBloomMultiplier(1.15F);
            optics.addVignette(0x02040D, 0.26F * env);
         }
         case BLOOM -> {
            optics.addBloomMultiplier(1.38F);
            optics.addVignette(0x02040D, 0.20F * env);
         }
         case STREAK -> {
            optics.addAnamorphicStreak(1.55F * env, 1.35F, 0x7DF5FF);
            optics.addBloomMultiplier(1.05F);
         }
         case GODRAYS -> {
            optics.addGodRaySource(frame, this.center, 1.65F * env, 0.972F, 0xBDFBFF);
            optics.addWorldDimming(0.42F * env);
         }
         case HOLDFRAME -> {
            if (!this.holdTriggered && this.ageTicks() >= 14) {
               this.holdTriggered = true;
               optics.triggerHoldFrame(0.68F, 1.55F, 0xD8F8FF);
               optics.addFlash(0xE8FAFF, 0.36F);
               optics.addFovKick(0.12F);
            }
            optics.addAnamorphicStreak(1.15F * env, 1.45F, 0x8EF7FF);
            optics.enableAutoExposure(0.85F * env, 0.42F, 0.38F, 0.58F);
         }
         case IMPACT_SAFE, IMPACT_FULL -> {
            float elapsedSec = age / 20.0F;
            float fade = 1.0F - FxMath.window(age, 36.0F, 50.0F);
            optics.requestImpactFrame(ImpactFrameSequencer.Preset.VACUUM_CLIMAX, elapsedSec, 0.96F * fade);
            optics.addAberration(0.85F * fade);
            optics.addZoomBlur(0.45F * fade, uv);
         }
         case CLOUDS -> {
            optics.addWorldDimming(0.35F * env);
            optics.addVignette(0x02040D, 0.32F * env);
         }
         case PARTICLES, SILENCE -> {
            optics.addBloomMultiplier(1.15F);
         }
      }
   }
}
