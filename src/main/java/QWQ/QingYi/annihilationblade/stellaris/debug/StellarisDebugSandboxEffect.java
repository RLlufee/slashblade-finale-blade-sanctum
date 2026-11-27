package QWQ.QingYi.annihilationblade.stellaris.debug;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisCloudSuppression;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisEffect;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisPass;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisQuality;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisReliefArray;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisShaders;
import QWQ.QingYi.annihilationblade.stellaris.optics.AudioSilencer;
import QWQ.QingYi.annihilationblade.stellaris.optics.ImpactFrameSequencer;
import QWQ.QingYi.annihilationblade.stellaris.optics.StellarisOpticsState;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisParticles;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisRibbons;
import java.util.List;
import java.util.Random;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * Step 1.6 专属全链路星穹引擎实机验收沙盒（仅由 {@code /stellaris debug test <mode>} 触发，零玩法影响）。
 * <p>
 * 全面展示 Step 1.6 终极升格后的 9 阶段视觉盛宴：
 * <ul>
 *   <li>{@code optics}：2D 共面 Binet RK4 史瓦西黑洞（真弯曲主/背双像吸积盘 + 光子环 + 引力透镜）× 环绕 3D 物理浮雕法阵前后互穿 + TBO 多形态旋涡粒子 + 3D 虹吸丝带 + AgX 电影色调 + 深度遮挡耶稣光；</li>
 *   <li>{@code relief}：3 座嵌套反旋 3D 物理圆管浮雕浑天仪 + 贯穿法阵环孔的等离子光柱 + 万级 TBO 5 形态粒子 + 3D 能量丝带，直观验收真实穿孔前后遮挡；</li>
 *   <li>{@code scene}：双输出 {@code SceneEmit} 苍穹夺夜（Hillaire 暮光 + 3 层银边旋涡云洞 + 13 道有机裂纹 + 0 步进 360° 环绕极光 + 实时地形重打光焦散 + 大地深空裂隙视差窗 + 零 UB 解析遥测）；</li>
 *   <li>{@code particles}：万级 GPU 实例化 TBO 5 形态粒子（星核/棱晶碎钻/六边形散景/速度流线/贴地余烬）+ 3D 双螺旋丝带压测；</li>
 *   <li>{@code bloom / streak / godrays / holdframe / impact_safe / impact_full / silence / clouds}：各子系统独立体检。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisDebugSandboxEffect extends StellarisEffect {
   public enum Mode {
      OPTICS(260),
      RELIEF(260),
      SCENE(280),
      BLOOM(160),
      STREAK(160),
      GODRAYS(200),
      HOLDFRAME(90),
      IMPACT_SAFE(50),
      IMPACT_FULL(50),
      PARTICLES(200),
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
      this.radius = 2.25F;
   }

   @Override
   public Vec3 focus() {
      return this.center;
   }

   @Override
   public boolean hasBody() {
      return this.mode == Mode.OPTICS || this.mode == Mode.BLOOM || this.mode == Mode.RELIEF;
   }

   @Override
   public void tick(ClientLevel level) {
      if (this.mode == Mode.CLOUDS) {
         StellarisCloudSuppression.requestSuppress(4);
      } else if (this.mode == Mode.SCENE) {
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
      if (this.mode != Mode.OPTICS && this.mode != Mode.PARTICLES && this.mode != Mode.RELIEF) {
         return;
      }
      // 末段坍缩退场期间（后 22% 寿命）停止新发射粒子，让既有星尘优雅收拢淡出，绝不抢戏或突兀切断
      if (this.mode == Mode.OPTICS && this.ageTicks() > (int)(this.lifetime * 0.78F)) {
         return;
      }
      int perTick = this.mode == Mode.PARTICLES ? 120 : (this.mode == Mode.RELIEF ? 48 : 22);
      StellarisParticles.Shape[] shapes = StellarisParticles.Shape.values();
      for (int i = 0; i < perTick; i++) {
         double ang = this.rng.nextDouble() * Math.PI * 2.0;
         // OPTICS 模式把星尘外推至吸积盘外缘（1.55R..3.6R），彻底让出漆黑事件视界与双像吸积盘的居中视觉主镜头！
         double minMult = this.mode == Mode.OPTICS ? 1.55 : 0.95;
         double spanMult = this.mode == Mode.RELIEF ? 3.2 : (this.mode == Mode.OPTICS ? 2.15 : 2.6);
         double dist = this.radius * (minMult + this.rng.nextDouble() * spanMult);
         double tiltY = (this.rng.nextDouble() - 0.5) * (this.mode == Mode.RELIEF ? 2.4 : 0.82);
         double px = this.center.x + Math.cos(ang) * dist;
         double py = this.center.y + tiltY + Math.sin(ang) * dist * 0.26;
         double pz = this.center.z + Math.sin(ang) * dist;

         int rgb = this.rng.nextFloat() < 0.56F ? 0x1DF5FF : (this.rng.nextFloat() < 0.65F ? 0xEFFBFF : 0x7C5CFF);
         float emit = 2.2F + this.rng.nextFloat() * 4.6F;
         StellarisParticles.Shape shape = shapes[this.rng.nextInt(shapes.length)];
         float stretch = shape == StellarisParticles.Shape.VELOCITY_STREAK ? 2.2F : 0.45F;
         float pSize = shape == StellarisParticles.Shape.HEX_BOKEH
            ? (0.072F + this.rng.nextFloat() * 0.085F)
            : (0.038F + this.rng.nextFloat() * 0.056F);

         particles.spawn(
            px, py, pz,
            0.0F, 0.0F, 0.0F,
            this.center.x, this.center.y, this.center.z,
            0.22F, 0.94F, 0.25F,
            this.mode == Mode.RELIEF ? 0.40F : 0.34F,
            pSize,
            stretch,
            rgb, emit,
            40 + this.rng.nextInt(32),
            StellarisParticles.Operator.KEPLER_VORTEX,
            shape
         );
      }
   }

   @Override
   public void collectRibbons(FxFrame frame, StellarisRibbons ribbons) {
      if (this.mode != Mode.OPTICS && this.mode != Mode.RELIEF && this.mode != Mode.PARTICLES) {
         return;
      }
      float age = this.age(frame.partialTick);
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.84F) * this.cancelAlpha(frame.partialTick);
      if (env <= 0.01F) {
         return;
      }
      // 绘制 6 条环绕中心奇点/立体法阵交织穿行的 3D 星穹双螺旋虹吸丝与电弧丝带
      int strands = this.mode == Mode.RELIEF ? 8 : 5;
      for (int i = 0; i < strands; i++) {
         double a0 = (Math.PI * 2.0 * i / strands) + age * 0.028;
         double a1 = a0 + 1.85;
         double rOut = this.radius * (this.mode == Mode.RELIEF ? 3.05 : 2.55);
         Vec3 start = this.center.add(Math.cos(a0) * rOut, Math.sin(a0 * 1.5) * 1.8, Math.sin(a0) * rOut);
         Vec3 end = this.mode == Mode.RELIEF
            ? this.center.add(Math.cos(a1) * rOut * 0.45, Math.cos(a1 * 1.3) * 2.6, Math.sin(a1) * rOut * 0.45)
            : this.center.add(Math.cos(a1) * this.radius * 0.95, Math.sin(a1) * 0.65, Math.sin(a1) * this.radius * 0.95);
         Vec3 ctrl = new Vec3(Math.sin(a0 + age * 0.04) * 1.8, Math.cos(a0 * 2.0) * 1.6, Math.cos(a0 - age * 0.03) * 1.8);

         int rgb = (i % 3 == 0) ? 0x1DF5FF : ((i % 3 == 1) ? 0x7C5CFF : 0x9AF7FF);
         StellarisRibbons.Style style = (i % 3 == 0) ? StellarisRibbons.Style.CELESTIAL_SIPHON
            : ((i % 3 == 1) ? StellarisRibbons.Style.FLOWING_PULSE : StellarisRibbons.Style.LIGHTNING_FILAMENT);
         ribbons.addCurvedRibbon(frame, start, end, ctrl, 14, 0.085F, rgb, 3.8F, 0.90F * env, style, i * 0.17F);
      }
   }

   @Override
   public void collectReliefArrays(FxFrame frame, StellarisQuality.Spec quality, List<StellarisReliefArray> out) {
      if (this.mode != Mode.RELIEF && this.mode != Mode.OPTICS && this.mode != Mode.HOLDFRAME) {
         return;
      }
      float age = this.age(frame.partialTick);
      float reveal = FxMath.easeInOutSine(FxMath.window(age, 0.0F, 24.0F));
      float fadeOut = 1.0F - FxMath.window(age, this.lifetime - 22.0F, this.lifetime);
      float env = reveal * fadeOut * this.cancelAlpha(frame.partialTick);
      if (env <= 0.01F) {
         return;
      }

      if (this.mode == Mode.RELIEF) {
         // 3 座嵌套反旋 3D 物理圆管浮雕天体仪：主浑天仪 + 斜倾聚焦环 + 下方七星副环
         out.add(new StellarisReliefArray(
            this.center,
            new Vector3f(0.18F, 0.96F, 0.21F),
            this.radius * 2.85F,
            age * 0.022F,
            -age * 0.042F,
            1.15F,
            reveal,
            2.35F * fadeOut * this.cancelAlpha(frame.partialTick),
            0x1DF5FF,
            StellarisReliefArray.VARIANT_GRAND_ARMILLARY
         ));
         out.add(new StellarisReliefArray(
            this.center.add(0.0, 0.35, 0.0),
            new Vector3f(-0.62F, 0.68F, 0.38F),
            this.radius * 1.95F,
            -age * 0.036F,
            age * 0.058F,
            1.05F,
            reveal,
            2.15F * fadeOut * this.cancelAlpha(frame.partialTick),
            0x7C5CFF,
            StellarisReliefArray.VARIANT_ORBITAL_FOCUS
         ));
         out.add(new StellarisReliefArray(
            this.center.add(0.0, -2.4, 0.0),
            new Vector3f(0.0F, 1.0F, 0.0F),
            this.radius * 3.40F,
            age * 0.014F,
            -age * 0.028F,
            0.95F,
            reveal,
            1.95F * fadeOut * this.cancelAlpha(frame.partialTick),
            0x7EE9FF,
            StellarisReliefArray.VARIANT_SEVEN_STAR_RING
         ));
      } else {
         // OPTICS / HOLDFRAME：单座大半径立体浮雕浑天仪斜环黑洞，直观展示黑洞视界 × 3D 浮雕法阵前后互穿！
         out.add(new StellarisReliefArray(
            this.center,
            new Vector3f(-0.34F, 0.86F, 0.38F),
            this.radius * 2.55F,
            age * 0.020F,
            -age * 0.038F,
            1.05F,
            reveal,
            2.10F * fadeOut * this.cancelAlpha(frame.partialTick),
            0x1DF5FF,
            StellarisReliefArray.VARIANT_GRAND_ARMILLARY
         ));
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
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.82F) * this.cancelAlpha(frame.partialTick);
      if (env <= 0.01F) {
         return;
      }
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
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.84F) * this.cancelAlpha(frame.partialTick);
      if (env <= 0.01F) {
         return;
      }
      Vector3f rel = frame.rel(this.center);

      if (this.mode == Mode.RELIEF) {
         // 贯穿三层 3D 浮雕法阵环孔的炽热等离子光柱，直观验收 stReliefMask 真实前后穿孔遮挡！
         Vector3f base = new Vector3f(rel.x, rel.y - 5.5F, rel.z);
         Vector3f top = new Vector3f(rel.x, rel.y + 6.5F, rel.z);
         g.begin(FxGeometry.Style.PLASMA_BEAM, 5.2F * env, 0.35F, 2.0F, 1.45F, 0.0F, (age * 0.04F) % 1.0F);
         g.cylinder(base, top, 0.42F, 0.68F, 24, age * 0.05F, 0.12F, 0.94F, 1.00F, 0.95F * env);
         g.end();
         g.begin(FxGeometry.Style.PLASMA_BEAM, 7.6F * env, 0.25F, 3.0F, -1.9F, 0.0F, (age * 0.06F + 0.5F) % 1.0F);
         g.cylinder(base, top, 0.16F, 0.22F, 16, -age * 0.08F, 0.94F, 0.98F, 1.00F, 1.0F * env);
         g.end();
         return;
      }

      Vector3f axisA = new Vector3f(0.22F, 0.94F, 0.25F).normalize();
      Vector3f axisB = new Vector3f(-0.46F, 0.82F, 0.34F).normalize();

      float emitBoost = this.mode == Mode.HOLDFRAME ? 7.5F : (this.mode == Mode.STREAK ? 8.2F : 5.4F);
      g.begin(FxGeometry.Style.RIBBON, emitBoost * env, 0.25F, 3.6F, 36.0F, 0.35F, 0.35F);
      g.torus(rel, axisA, this.radius * 1.95F, 0.036F, 72, 8, age * 0.030F, 0.11F, 0.96F, 1.00F, 0.95F * env);
      g.end();

      g.begin(FxGeometry.Style.RIBBON, emitBoost * 0.88F * env, 0.25F, 3.4F, 24.0F, -0.45F, 0.30F);
      g.torus(rel, axisB, this.radius * 1.52F, 0.028F, 64, 8, -age * 0.048F, 0.49F, 0.36F, 1.00F, 0.90F * env);
      g.end();

      if (this.mode == Mode.BLOOM) {
         g.begin(FxGeometry.Style.GLOW, 6.2F * env, 0.2F, 12.0F, 3.2F, 0.85F, 0.0F);
         g.billboard(rel, this.radius * 0.62F, 0.0F, 0.42F, 0.94F, 1.00F, env);
         g.end();
      }
   }

   @Override
   public void collectPasses(FxFrame frame, StellarisQuality.Spec quality, List<StellarisPass> out) {
      float age = this.age(frame.partialTick);
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.84F) * this.cancelAlpha(frame.partialTick);
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

      // 平滑诞生（0..10% 绽放）→ 稳态巡航（10%..78%）→ 引力坍缩蒸发收尾（78%..100% 平滑收缩至针尖再归零，彻底消灭突兀消失！）
      float progress = age / (float)this.lifetime;
      float birthT = FxMath.window(progress, 0.0F, 0.10F);
      float collapseT = FxMath.window(progress, 0.78F, 1.00F);
      float birthScale = FxMath.easeOutCubic(birthT);
      float collapseRemain = 1.0F - collapseT;
      float radiusScale = (0.08F + 0.92F * birthScale) * (0.06F + 0.94F * (1.0F - FxMath.easeInCubic(collapseT)));
      float fadeEnv = FxMath.easeInOutSine(birthT) * (collapseRemain * collapseRemain) * this.cancelAlpha(frame.partialTick);
      if (fadeEnv <= 0.002F) {
         return;
      }

      float dynamicR = this.radius * radiusScale;
      StellarisPass.ScreenRect rect = StellarisPass.projectSphereRect(frame, this.center, dynamicR * 4.60F);
      if (rect != null && rect.empty()) {
         return;
      }

      Vector3f rel = frame.rel(this.center);
      // 末段坍缩时短暂激起一抹霍金蒸发微亮脉冲，随后丝滑归零
      float hawkingPulse = (float)Math.exp(-Math.pow((collapseT - 0.45F) / 0.22F, 2.0)) * 0.42F * collapseRemain;
      float energy = ((this.mode == Mode.HOLDFRAME ? 1.48F : 1.32F) + hawkingPulse) * fadeEnv;
      float tilt = 0.36F + (float)Math.sin(age * 0.035F) * 0.16F;
      float lensStrength = 1.65F;

      out.add(StellarisPass.heavyMrt(
         () -> StellarisShaders.debugHeavyMrt,
         shader -> {
            shader.safeGetUniform("CoreSphere").set(rel.x, rel.y, rel.z, dynamicR);
            shader.safeGetUniform("CoreParams").set(energy, tilt, lensStrength, fadeEnv);
         },
         null,
         rect
      ));
   }

   @Override
   public void contributeOptics(FxFrame frame, StellarisOpticsState optics) {
      float age = this.age(frame.partialTick);
      float env = FxMath.envelope(age / this.lifetime, 0.08F, 0.84F) * this.cancelAlpha(frame.partialTick);
      float[] uv = frame.screenUv(this.center);

      switch (this.mode) {
         case OPTICS -> {
            optics.addBloomMultiplier(1.25F);
            optics.addAnamorphicStreak(1.20F * env, 1.50F, 0x8EF7FF);
            optics.addGodRaySource(frame, this.center, 1.20F * env, 0.968F, 0xA8F7FF);
            optics.enableAutoExposure(0.85F * env, 0.48F, 0.42F, 0.52F);
            optics.addVignette(0x02040D, 0.24F * env);
         }
         case RELIEF -> {
            optics.addBloomMultiplier(1.22F);
            optics.addAnamorphicStreak(1.10F * env, 1.55F, 0x7DF5FF);
            optics.addGodRaySource(frame, this.center.add(0.0, 2.2, 0.0), 1.35F * env, 0.970F, 0x9AF7FF);
            optics.enableAutoExposure(0.80F * env, 0.52F, 0.44F, 0.50F);
            optics.addVignette(0x02040D, 0.22F * env);
         }
         case SCENE -> {
            optics.addBloomMultiplier(1.22F);
            optics.addAnamorphicStreak(0.95F * env, 1.70F, 0x8EF7FF);
            optics.addVignette(0x02040D, 0.24F * env);
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
            optics.addBloomMultiplier(1.18F);
         }
      }
   }
}
