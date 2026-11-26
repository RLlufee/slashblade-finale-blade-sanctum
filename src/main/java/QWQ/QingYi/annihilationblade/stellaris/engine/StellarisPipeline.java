package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.render.client.core.FxCompat;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxPipeline;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxTarget;
import QWQ.QingYi.annihilationblade.stellaris.optics.AutoExposure;
import QWQ.QingYi.annihilationblade.stellaris.optics.HoldFrame;
import QWQ.QingYi.annihilationblade.stellaris.optics.ImpactFrameSequencer;
import QWQ.QingYi.annihilationblade.stellaris.optics.StellarisOpticsState;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisParticles;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * 星穹渲染引擎（Stellaris Engine）主帧图管线（Step 1.5 极致画质精修版）。
 * <p>
 * 核心特性：
 * <ul>
 *   <li>原生线性 {@code st_geometry} 几何层 + 精确 IEC sRGB 编解码，杜绝双重 Gamma 洗白与黑电平浮灰；</li>
 *   <li>零显存重建动态分辨率 3 附件 MRT（视口缩放 + {@code HeavyUvMax}），分离世界遮挡/折射与纯自发光通道；</li>
 *   <li>4 趟屏宽归一化无串珠变形星芒、步数无关抗雪花体积光、基准 {@code 1.0} 全镜头异步自动曝光；</li>
 *   <li>两阶段电影预合成（复用闲置 {@code sceneHdrB}，砍掉 85%+ 多 tap 采样）+ 完整 12 槽位纹理状态保护。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisPipeline {
   public static final StellarisPipeline INSTANCE = new StellarisPipeline();
   /** 3D 体积噪声固定绑定的高纹理单元 GL_TEXTURE12（彻底避开 MC 原版 0..11 的 shaderTextures）。 */
   public static final int NOISE3D_TEXTURE_UNIT = 12;

   private static final Matrix4f IDENTITY = new Matrix4f();
   private static final ResourceLocation FLOW_NOISE = ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "textures/fx/fx_flow_noise.png");

   private final StellarisTargets targets = new StellarisTargets();
   private final StellarisQuality quality = new StellarisQuality();
   private final StellarisGpuTimer gpuTimer = new StellarisGpuTimer();
   private final StellarisWarmup warmup = new StellarisWarmup();
   private final AutoExposure autoExposure = new AutoExposure();
   private final HoldFrame holdFrame = new HoldFrame();
   private final ImpactFrameSequencer impactSequencer = new ImpactFrameSequencer();

   private VertexBuffer fullscreenVbo;
   private boolean failed;
   private boolean heavyDegraded;
   private boolean loggedFailure;
   private long lastFrameNs;
   private int frameCounter;

   private StellarisPipeline() {
   }

   public StellarisQuality quality() {
      return this.quality;
   }

   public StellarisGpuTimer gpuTimer() {
      return this.gpuTimer;
   }

   public StellarisWarmup warmup() {
      return this.warmup;
   }

   public ImpactFrameSequencer impactSequencer() {
      return this.impactSequencer;
   }

   public HoldFrame holdFrame() {
      return this.holdFrame;
   }

   public boolean isFailed() {
      return this.failed;
   }

   public boolean isHeavyDegraded() {
      return this.heavyDegraded;
   }

   public boolean active() {
      ModConfig.ClientRenderer rendererCfg = ModConfig.CLIENT.clientRenderer;
      if (this.failed || !rendererCfg.enabled.getValue() || !StellarisShaders.coreReady()) {
         return false;
      }
      if (this.quality.effectiveSpec().preset() == ModConfig.StellarisQualityPreset.FALLBACK) {
         return false;
      }
      return !FxCompat.shaderPackActive() || rendererCfg.withShaderPack.getValue();
   }

   public boolean heavyPassesAllowed(StellarisQuality.Spec spec) {
      if (this.heavyDegraded || !spec.heavyEnabled() || !StellarisShaders.mrtReady()) {
         return false;
      }
      if (!ModConfig.CLIENT.clientRenderer.volumetric.getValue()) {
         return false;
      }
      return !FxCompat.shaderPackActive() || !ModConfig.CLIENT.stellarisEngine.disableHeavyPassesWithShaderPack.getValue();
   }

   public void onShadersReloaded() {
      this.failed = false;
      this.heavyDegraded = false;
      this.loggedFailure = false;
      this.autoExposure.reset();
      this.holdFrame.reset();
      this.warmup.reset();
      StellarisProceduralAssets.INSTANCE.resetFailure();
   }

   public void resetDiagnostics() {
      this.failed = false;
      this.heavyDegraded = false;
      this.loggedFailure = false;
      this.quality.resetAdaptive();
      this.autoExposure.reset();
      this.holdFrame.reset();
   }

   public void tickWarmupIfIdle() {
      if (!this.failed && !this.warmup.isCompleted() && this.active()) {
         this.warmup.step(this);
      }
   }

   public void render(FxFrame frame, List<StellarisEffect> effects, StellarisOpticsState optics, StellarisParticles particles) {
      if (!this.active()) {
         this.gpuTimer.recordIdleFrame();
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      RenderTarget main = mc.getMainRenderTarget();
      int width = Math.max(1, main.width);
      int height = Math.max(1, main.height);

      int depthTex = FxPipeline.INSTANCE.ensureSharedDepthTexture();
      if (depthTex <= 0) {
         return;
      }
      if (!StellarisProceduralAssets.INSTANCE.ensureUploaded()) {
         return;
      }

      long nowNs = System.nanoTime();
      float dtSeconds = this.lastFrameNs == 0L ? (1.0F / 60.0F) : Math.max(1.0E-4F, Math.min(0.1F, (nowNs - this.lastFrameNs) / 1.0E9F));
      this.lastFrameNs = nowNs;
      this.frameCounter++;

      StellarisQuality.Spec spec = this.quality.effectiveSpec();
      boolean allowHeavy = this.heavyPassesAllowed(spec);

      // 提前收集并过滤视锥外的空 Scissor 重型 Pass（例如背对黑洞时直接 0 开销跳过！）
      List<StellarisPass> rawPasses = new ArrayList<>();
      for (StellarisEffect effect : effects) {
         effect.collectPasses(frame, spec, rawPasses);
      }
      List<StellarisPass> heavyPasses = new ArrayList<>();
      List<StellarisPass> scenePasses = new ArrayList<>();
      for (StellarisPass pass : rawPasses) {
         if (pass.shader().get() == null) {
            continue;
         }
         if (pass.kind() == StellarisPass.Kind.HEAVY_MRT) {
            if (allowHeavy && (pass.scissorRect() == null || !pass.scissorRect().empty())) {
               heavyPasses.add(pass);
            }
         } else {
            scenePasses.add(pass);
         }
      }

      boolean captureHoldThisFrame = this.holdFrame.update(optics, dtSeconds);
      boolean hasParticles = !particles.isEmpty() && StellarisShaders.particle != null;

      FxPipeline.StateGuard guard = FxPipeline.StateGuard.capture();
      // 完整保存 0..11 共 12 个原版纹理槽位，以及 GL_ACTIVE_TEXTURE 与高槽位 GL_TEXTURE12 的 3D 纹理绑定（100% 无痕隔离）
      int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISE3D_TEXTURE_UNIT);
      int prevNoise3dBinding = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
      GL13.glActiveTexture(prevActiveTex);
      int[] savedTextures = new int[12];
      for (int i = 0; i < savedTextures.length; i++) {
         savedTextures[i] = RenderSystem.getShaderTexture(i);
      }
      RenderSystem.backupProjectionMatrix();
      PoseStack modelView = RenderSystem.getModelViewStack();
      modelView.pushPose();

      this.gpuTimer.beginFrame();
      try {
         FxPipeline.syncBlendCache();
         RenderSystem.disableDepthTest();
         RenderSystem.depthMask(false);
         RenderSystem.disableCull();

         int blueNoiseTex = StellarisProceduralAssets.INSTANCE.blueNoiseTexture();
         int blackbodyTex = StellarisProceduralAssets.INSTANCE.blackbodyLutTexture();
         int noise3dTex = StellarisProceduralAssets.INSTANCE.noise3dTexture();

         // ───────────────── 阶段 2：星穹原生线性暗部本体层 + HDR 几何层 + 纯客户端粒子池 ─────────────────
         this.gpuTimer.begin(StellarisGpuTimer.Stage.GEOMETRY);
         RenderSystem.setShaderTexture(0, depthTex);
         RenderSystem.setShaderTexture(1, FLOW_NOISE);
         RenderSystem.setProjectionMatrix(frame.projection, VertexSorting.DISTANCE_TO_ORIGIN);
         modelView.setIdentity();
         modelView.mulPoseMatrix(frame.view);
         RenderSystem.applyModelViewMatrix();

         boolean anyBodyRequested = false;
         for (StellarisEffect effect : effects) {
            if (effect.hasBody()) {
               anyBodyRequested = true;
               break;
            }
         }
         boolean hasBody = false;
         if (anyBodyRequested) {
            this.targets.body.ensure(width, height);
            this.targets.body.clear();
            this.targets.body.bindWrite();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
            FxGeometry bodyGeo = new FxGeometry(frame, true, StellarisShaders.geometry);
            for (StellarisEffect effect : effects) {
               if (effect.hasBody()) {
                  effect.renderBody(bodyGeo, frame);
                  bodyGeo.clearOccluder();
                  bodyGeo.clearGeoCenter();
               }
            }
            hasBody = bodyGeo.drawCalls() > 0;
         }

         this.targets.hdrGeo.ensure(width, height);
         this.targets.hdrGeo.clear();
         this.targets.hdrGeo.bindWrite();
         RenderSystem.disableDepthTest();
         RenderSystem.depthMask(false);
         RenderSystem.disableCull();
         RenderSystem.enableBlend();
         RenderSystem.blendFunc(SourceFactor.ONE, DestFactor.ONE);

         FxGeometry hdrGeo = new FxGeometry(frame, false, StellarisShaders.geometry);
         for (StellarisEffect effect : effects) {
            effect.renderGeometry(hdrGeo, frame);
            hdrGeo.clearOccluder();
            hdrGeo.clearGeoCenter();
         }
         boolean hasHdrGeo = hdrGeo.drawCalls() > 0;
         if (hasParticles) {
            int drawnParticles = particles.render(frame, depthTex);
            if (drawnParticles > 0) {
               hasHdrGeo = true;
            }
         }
         modelView.setIdentity();
         RenderSystem.applyModelViewMatrix();
         RenderSystem.disableBlend();
         this.gpuTimer.end(StellarisGpuTimer.Stage.GEOMETRY);

         boolean hasHeavyMrt = !heavyPasses.isEmpty();
         boolean hasScenePasses = !scenePasses.isEmpty();
         boolean hasHoldActive = this.holdFrame.active();
         if (!hasBody && !hasHdrGeo && !hasHeavyMrt && !hasScenePasses && !hasHoldActive && !optics.hasScreenPost()) {
            this.gpuTimer.endFrame();
            return;
         }

         // ───────────────── 阶段 1：主画面精确 IEC 线性 HDR 摄入（Main RGBA8 → SceneHdrA RGBA16F） ─────────────────
         this.gpuTimer.begin(StellarisGpuTimer.Stage.INGEST);
         this.targets.sceneHdrA.ensure(width, height);
         this.targets.sceneHdrA.bindWrite();
         RenderSystem.disableBlend();
         RenderSystem.disableDepthTest();
         RenderSystem.depthMask(false);
         RenderSystem.disableCull();
         RenderSystem.setShaderTexture(0, main.getColorTextureId());
         this.drawFullscreen(StellarisShaders.sceneIngest, null, null, width, height);
         int currentSceneTex = this.targets.sceneHdrA.texture();
         this.gpuTimer.end(StellarisGpuTimer.Stage.INGEST);

         // ───────────────── 阶段 3：零重建动态分辨率 3 附件重型 MRT Pass ─────────────────
         boolean heavyRendered = false;
         float heavyUvMaxX = 1.0F;
         float heavyUvMaxY = 1.0F;
         if (hasHeavyMrt) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.HEAVY_MRT);
            try {
               int allocW = Math.max(2, Math.round(width * spec.heavyScaleMax()));
               int allocH = Math.max(2, Math.round(height * spec.heavyScaleMax()));
               this.targets.heavyMrt.ensureAllocated(allocW, allocH);

               float heavyScale = this.quality.currentHeavyScale();
               int activeW = Math.max(2, Math.min(allocW, Math.round(width * heavyScale)));
               int activeH = Math.max(2, Math.min(allocH, Math.round(height * heavyScale)));
               this.targets.heavyMrt.bindAndClearActive(activeW, activeH);
               heavyUvMaxX = this.targets.heavyMrt.uvMaxX();
               heavyUvMaxY = this.targets.heavyMrt.uvMaxY();

               RenderSystem.disableDepthTest();
               RenderSystem.depthMask(false);
               RenderSystem.disableCull();
               RenderSystem.disableBlend();

               for (StellarisPass pass : heavyPasses) {
                  ShaderInstance shader = pass.shader().get();
                  if (shader == null) {
                     continue;
                  }
                  RenderSystem.setShaderTexture(0, currentSceneTex);
                  RenderSystem.setShaderTexture(1, depthTex);
                  RenderSystem.setShaderTexture(2, blackbodyTex);
                  RenderSystem.setShaderTexture(3, blueNoiseTex);
                  this.setCommonUniforms(shader, frame, activeW, activeH, spec);
                  pass.uniforms().accept(shader);
                  IntConsumer combinedExtra = programId -> {
                     bindNoise3D(programId, noise3dTex);
                     if (pass.extraGlBinder() != null) {
                        pass.extraGlBinder().accept(programId);
                     }
                  };
                  this.drawFullscreen(shader, combinedExtra, pass.scissorRect(), activeW, activeH);
                  heavyRendered = true;
               }
            } catch (Throwable heavyError) {
               this.heavyDegraded = true;
               heavyRendered = false;
               Annihilationblade.LOGGER.error("[Stellaris Engine] Heavy MRT pass degraded safely until resource reload.", heavyError);
            } finally {
               this.gpuTimer.end(StellarisGpuTimer.Stage.HEAVY_MRT);
            }
         }

         // ───────────────── 阶段 4：全分辨率线性 HDR 场景改写 Pass（SceneHdr A↔B 乒乓） ─────────────────
         if (hasScenePasses) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.SCENE);
            this.targets.sceneHdrB.ensure(width, height);
            boolean writeB = true;
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            for (StellarisPass pass : scenePasses) {
               ShaderInstance shader = pass.shader().get();
               if (shader == null) {
                  continue;
               }
               FxTarget out = writeB ? this.targets.sceneHdrB : this.targets.sceneHdrA;
               out.bindWrite();
               RenderSystem.setShaderTexture(0, currentSceneTex);
               RenderSystem.setShaderTexture(1, depthTex);
               RenderSystem.setShaderTexture(2, blackbodyTex);
               RenderSystem.setShaderTexture(3, blueNoiseTex);
               this.setCommonUniforms(shader, frame, width, height, spec);
               pass.uniforms().accept(shader);
               IntConsumer combinedExtra = programId -> {
                  bindNoise3D(programId, noise3dTex);
                  if (pass.extraGlBinder() != null) {
                     pass.extraGlBinder().accept(programId);
                  }
               };
               this.drawFullscreen(shader, combinedExtra, null, width, height);
               currentSceneTex = out.texture();
               writeB = !writeB;
            }
            this.gpuTimer.end(StellarisGpuTimer.Stage.SCENE);
         }

         // ───────────────── 阶段 5a：6 级 HDR 双频泛光金字塔 ─────────────────
         ModConfig.ClientRenderer rendererCfg = ModConfig.CLIENT.clientRenderer;
         float bloomStrength = rendererCfg.bloom.getValue()
            ? (float)(rendererCfg.bloomStrength.getValue() * optics.bloomMultiplier)
            : 0.0F;
         int bloomTex = 0;
         int bloomDown0Tex = 0;
         int lowestBloomTex = 0;
         if ((hasHdrGeo || heavyRendered) && bloomStrength > 0.001F && StellarisShaders.bloomDown != null && StellarisShaders.bloomUp != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.BLOOM);
            int[] bloomResult = this.runBloomPyramid(width, height, hasHdrGeo, heavyRendered, heavyUvMaxX, heavyUvMaxY);
            bloomTex = bloomResult[0];
            lowestBloomTex = bloomResult[1];
            bloomDown0Tex = bloomResult[2];
            this.gpuTimer.end(StellarisGpuTimer.Stage.BLOOM);
         }

         // ───────────────── 阶段 5b：1/4 分辨率 4 趟屏宽归一化无串珠变形水平星芒 ─────────────────
         int streakTex = 0;
         float effectiveStreak = ModConfig.CLIENT.stellarisEngine.anamorphicStreaks.getValue() ? optics.streakStrength : 0.0F;
         if ((hasHdrGeo || heavyRendered) && effectiveStreak > 0.001F && StellarisShaders.anamorphic != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.STREAK);
            streakTex = this.runAnamorphicStreak(width, height, hasHdrGeo, heavyRendered, heavyUvMaxX, heavyUvMaxY, optics);
            this.gpuTimer.end(StellarisGpuTimer.Stage.STREAK);
         }

         // ───────────────── 阶段 6：自发光专属径向体积光（步数无关衰减 + 优先采 bloomDown[0] 抗雪花） ─────────────────
         int godRayTex = 0;
         if ((hasHdrGeo || heavyRendered) && optics.godRayCount > 0 && spec.godRaySteps() > 0 && StellarisShaders.godRays != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.GOD_RAYS);
            godRayTex = this.runGodRays(width, height, blueNoiseTex, bloomDown0Tex, hasHdrGeo, heavyRendered, heavyUvMaxX, heavyUvMaxY, optics, spec);
            this.gpuTimer.end(StellarisGpuTimer.Stage.GOD_RAYS);
         }

         // ───────────────── 阶段 7：基准 1.0 全 GPU 1×1 异步自动曝光（开/关泛光双源等价测光） ─────────────────
         int exposureTex = 0;
         if (optics.autoExposureWeight > 0.001F && StellarisShaders.exposure != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.EXPOSURE);
            exposureTex = this.runAutoExposure(
               currentSceneTex, lowestBloomTex, hasHdrGeo, heavyRendered, heavyUvMaxX, heavyUvMaxY, dtSeconds, optics
            );
            this.gpuTimer.end(StellarisGpuTimer.Stage.EXPOSURE);
         }

         // ───────────────── 阶段 8a：Hold-Frame 视网膜残像捕获 ─────────────────
         this.gpuTimer.begin(StellarisGpuTimer.Stage.COMPOSITE);
         if (captureHoldThisFrame && StellarisShaders.holdCopy != null) {
            int hw = Math.max(2, width / 2);
            int hh = Math.max(2, height / 2);
            this.targets.holdFrame.ensure(hw, hh);
            this.targets.holdFrame.bindWrite();
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShaderTexture(0, currentSceneTex);
            RenderSystem.setShaderTexture(1, hasHdrGeo ? this.targets.hdrGeo.texture() : currentSceneTex);
            RenderSystem.setShaderTexture(2, heavyRendered ? this.targets.heavyMrt.colorTexture() : currentSceneTex);
            RenderSystem.setShaderTexture(3, heavyRendered ? this.targets.heavyMrt.emissiveTexture() : currentSceneTex);
            ShaderInstance holdCopy = StellarisShaders.holdCopy;
            holdCopy.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
            holdCopy.safeGetUniform("GeoWeight").set(hasHdrGeo ? 1.0F : 0.0F);
            holdCopy.safeGetUniform("HeavyWeight").set(heavyRendered ? 1.0F : 0.0F);
            this.drawFullscreen(holdCopy, null, null, hw, hh);
         }

         // ───────────────── 阶段 8b：两阶段电影合成回写主画面 ─────────────────
         ImpactFrameSequencer.ResolvedBeat beat = this.impactSequencer.resolve(optics);
         float aberration = (float)(optics.aberration * rendererCfg.chromaticAberration.getValue());
         boolean holdReady = this.holdFrame.active() && this.targets.holdFrame.ready();
         boolean needTwoPassPost = optics.zoomBlur > 0.001F || aberration > 0.001F || holdReady || beat.strength() > 0.001F;

         ShaderInstance comp = StellarisShaders.composite;
         int fallbackTex = currentSceneTex;
         RenderSystem.disableDepthTest();
         RenderSystem.depthMask(false);
         RenderSystem.disableCull();
         RenderSystem.disableBlend();

         RenderSystem.setShaderTexture(0, currentSceneTex);
         RenderSystem.setShaderTexture(1, hasHdrGeo ? this.targets.hdrGeo.texture() : fallbackTex);
         RenderSystem.setShaderTexture(2, heavyRendered ? this.targets.heavyMrt.colorTexture() : fallbackTex);
         RenderSystem.setShaderTexture(3, heavyRendered ? this.targets.heavyMrt.auxTexture() : fallbackTex);
         RenderSystem.setShaderTexture(4, heavyRendered ? this.targets.heavyMrt.emissiveTexture() : fallbackTex);
         RenderSystem.setShaderTexture(5, bloomTex != 0 ? bloomTex : fallbackTex);
         RenderSystem.setShaderTexture(6, streakTex != 0 ? streakTex : fallbackTex);
         RenderSystem.setShaderTexture(7, godRayTex != 0 ? godRayTex : fallbackTex);
         RenderSystem.setShaderTexture(8, hasBody ? this.targets.body.texture() : fallbackTex);
         RenderSystem.setShaderTexture(9, exposureTex != 0 ? exposureTex : fallbackTex);
         RenderSystem.setShaderTexture(10, depthTex);
         RenderSystem.setShaderTexture(11, blueNoiseTex);

         this.setCommonUniforms(comp, frame, width, height, spec);
         comp.safeGetUniform("HeavySize").set(
            heavyRendered ? (float)this.targets.heavyMrt.width() : (float)width,
            heavyRendered ? (float)this.targets.heavyMrt.height() : (float)height
         );
         comp.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
         comp.safeGetUniform("LayerWeights").set(
            hasBody ? 1.0F : 0.0F,
            hasHdrGeo ? 1.0F : 0.0F,
            heavyRendered ? 1.0F : 0.0F,
            optics.detailPreservation
         );
         comp.safeGetUniform("OpticsWeights").set(
            bloomTex != 0 ? bloomStrength : 0.0F,
            streakTex != 0 ? effectiveStreak : 0.0F,
            godRayTex != 0 ? 1.0F : 0.0F,
            exposureTex != 0 ? optics.autoExposureWeight : 0.0F
         );
         comp.safeGetUniform("StreakTint").set(optics.streakTintR, optics.streakTintG, optics.streakTintB, 1.0F);
         comp.safeGetUniform("HoldState").set(
            this.holdFrame.tintR(), this.holdFrame.tintG(), this.holdFrame.tintB(), holdReady ? this.holdFrame.strength() : 0.0F
         );
         float flashStrength = (float)(optics.flash * rendererCfg.screenFlash.getValue());
         comp.safeGetUniform("Flash").set(optics.flashR, optics.flashG, optics.flashB, Math.min(1.0F, flashStrength));
         comp.safeGetUniform("Vignette").set(optics.vignetteR, optics.vignetteG, optics.vignetteB, optics.vignette);
         comp.safeGetUniform("PostParams").set(optics.worldDimming, optics.desaturate, aberration, optics.zoomBlur);
         comp.safeGetUniform("Center").set(optics.centerU, optics.centerV);
         comp.safeGetUniform("ImpactMeta").set(
            beat.strength(),
            beat.invert() ? 1.0F : 0.0F,
            beat.edgeWeight(),
            beat.halftoneWeight()
         );
         comp.safeGetUniform("ImpactFg").set(beat.fgR(), beat.fgG(), beat.fgB(), beat.tearOffset());
         comp.safeGetUniform("ImpactBg").set(beat.bgR(), beat.bgG(), beat.bgB(), (float)(beat.beatIndex() & 63));

         if (needTwoPassPost) {
            // 复用闲置的另一张浮点场景缓冲执行单次 HDR 线性预合成，零新增显存
            FxTarget precompTarget = (currentSceneTex == this.targets.sceneHdrA.texture()) ? this.targets.sceneHdrB : this.targets.sceneHdrA;
            precompTarget.ensure(width, height);
            precompTarget.bindWrite();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            comp.safeGetUniform("PostMode").set(1.0F);
            this.drawFullscreen(comp, null, null, width, height);

            // 阶段 2：仅读单张预合成纹理 + 残像 + 蓝噪声，执行高速多 tap 后处理与冲击帧回写主画面
            main.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderTexture(0, precompTarget.texture());
            RenderSystem.setShaderTexture(1, holdReady ? this.targets.holdFrame.texture() : precompTarget.texture());
            RenderSystem.setShaderTexture(11, blueNoiseTex);
            comp.safeGetUniform("PostMode").set(2.0F);
            this.drawFullscreen(comp, null, null, width, height);
         } else {
            main.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            comp.safeGetUniform("PostMode").set(0.0F);
            this.drawFullscreen(comp, null, null, width, height);
         }
         this.gpuTimer.end(StellarisGpuTimer.Stage.COMPOSITE);

         this.gpuTimer.endFrame();
         this.quality.recordFrameGpuMs(this.gpuTimer.smoothedTotalMs());
      } catch (Throwable throwable) {
         this.fail("render", throwable);
      } finally {
         // 使用 endOrDiscardIfEmpty() 真正重置 BufferBuilder.building=false（1.20.1 的 discard() 不会清除 building 标志）
         BufferBuilder globalBuilder = Tesselator.getInstance().getBuilder();
         if (globalBuilder.building()) {
            BufferBuilder.RenderedBuffer stray = globalBuilder.endOrDiscardIfEmpty();
            if (stray != null) {
               stray.release();
            }
         }
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISE3D_TEXTURE_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, prevNoise3dBinding);
         GL13.glActiveTexture(prevActiveTex);
         modelView.popPose();
         RenderSystem.applyModelViewMatrix();
         RenderSystem.restoreProjectionMatrix();
         for (int i = 0; i < savedTextures.length; i++) {
            RenderSystem.setShaderTexture(i, savedTextures[i]);
         }
         guard.restore();
         main.bindWrite(true);
      }
   }

   /** 运行 6 级双频泛光金字塔，返回 {@code {最终升采样纹理, 最低级降采样纹理, 首级降采样纹理 bloomDown[0]}}。 */
   private int[] runBloomPyramid(int width, int height, boolean hasHdrGeo, boolean hasHeavyMrt, float heavyUvMaxX, float heavyUvMaxY) {
      ShaderInstance down = StellarisShaders.bloomDown;
      ShaderInstance up = StellarisShaders.bloomUp;
      int levels = 0;
      int srcW = width;
      int srcH = height;
      int srcTex = hasHdrGeo ? this.targets.hdrGeo.texture() : this.targets.heavyMrt.emissiveTexture();
      int heavyEmissiveTex = hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : srcTex;
      RenderSystem.disableBlend();

      for (int i = 0; i < StellarisTargets.BLOOM_LEVELS; i++) {
         int w = width >> (i + 1);
         int h = height >> (i + 1);
         if (w < 2 || h < 2) {
            break;
         }
         FxTarget target = this.targets.bloomDown[i];
         target.ensure(w, h);
         target.bindWrite();
         RenderSystem.setShaderTexture(0, srcTex);
         RenderSystem.setShaderTexture(1, i == 0 ? heavyEmissiveTex : srcTex);
         down.safeGetUniform("TexelSize").set(1.0F / srcW, 1.0F / srcH);
         down.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
         down.safeGetUniform("Prefilter").set(i == 0 ? 1.0F : 0.0F);
         down.safeGetUniform("PrimaryWeight").set(i == 0 && !hasHdrGeo ? 0.0F : 1.0F);
         down.safeGetUniform("EmissiveWeight").set(i == 0 && hasHeavyMrt ? 1.0F : 0.0F);
         this.drawFullscreen(down, null, null, w, h);
         srcTex = target.texture();
         srcW = w;
         srcH = h;
         levels++;
      }

      if (levels == 0) {
         return new int[]{0, 0, 0};
      }
      int down0Tex = this.targets.bloomDown[0].texture();
      int lowestTex = this.targets.bloomDown[levels - 1].texture();
      int lowerTex = lowestTex;
      int lowerW = this.targets.bloomDown[levels - 1].width();
      int lowerH = this.targets.bloomDown[levels - 1].height();

      for (int i = levels - 2; i >= 0; i--) {
         FxTarget target = this.targets.bloomUp[i];
         int tw = this.targets.bloomDown[i].width();
         int th = this.targets.bloomDown[i].height();
         target.ensure(tw, th);
         target.bindWrite();
         RenderSystem.setShaderTexture(0, lowerTex);
         RenderSystem.setShaderTexture(1, this.targets.bloomDown[i].texture());
         up.safeGetUniform("TexelSize").set(1.0F / lowerW, 1.0F / lowerH);
         // 能量守恒双频插值权重：低频广域层（i>=2）高扩散拉出星云大气晕，高频近核层（i<2）保住紧凑光晕不糊死中心
         float scatter = i >= 2 ? 0.72F : 0.54F;
         float radius = i >= 2 ? 1.15F : 0.92F;
         up.safeGetUniform("BlendParams").set(radius, scatter);
         this.drawFullscreen(up, null, null, tw, th);
         lowerTex = target.texture();
         lowerW = tw;
         lowerH = th;
      }
      return new int[]{lowerTex, lowestTex, down0Tex};
   }

   /** 运行 1/4 分辨率 4 趟屏宽归一化无串珠变形水平星芒（Anamorphic Streak）。 */
   private int runAnamorphicStreak(
      int width, int height, boolean hasHdrGeo, boolean hasHeavyMrt, float heavyUvMaxX, float heavyUvMaxY, StellarisOpticsState optics
   ) {
      ShaderInstance shader = StellarisShaders.anamorphic;
      int sw = Math.max(4, width / 4);
      int sh = Math.max(4, height / 4);
      this.targets.streakA.ensure(sw, sh);
      this.targets.streakB.ensure(sw, sh);
      RenderSystem.disableBlend();

      // Pass 0：3x3 抗闪烁高斯高阈值提取
      this.targets.streakA.bindWrite();
      int primaryTex = hasHdrGeo ? this.targets.hdrGeo.texture() : this.targets.heavyMrt.emissiveTexture();
      int heavyTex = hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : primaryTex;
      RenderSystem.setShaderTexture(0, primaryTex);
      RenderSystem.setShaderTexture(1, heavyTex);
      shader.safeGetUniform("TexelSize").set(1.0F / sw, 1.0F / sh);
      shader.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
      shader.safeGetUniform("PassParams").set(0.0F, optics.streakThreshold, hasHdrGeo ? 1.0F : 0.0F, hasHeavyMrt ? 1.0F : 0.0F);
      this.drawFullscreen(shader, null, null, sw, sh);

      // Pass 1..4：经数值验算零串珠振铃的 4 趟平滑步长，并按 1080p 参考宽度归一化（720p~4K 屏宽占比恒定）
      float resScale = Math.max(0.5F, sw / 480.0F);
      float[] strides = new float[]{1.0F, 2.2F, 4.8F, 10.5F};
      float[] decays = new float[]{0.84F, 0.82F, 0.78F, 0.74F};
      boolean readA = true;
      for (int p = 0; p < strides.length; p++) {
         FxTarget src = readA ? this.targets.streakA : this.targets.streakB;
         FxTarget dst = readA ? this.targets.streakB : this.targets.streakA;
         dst.bindWrite();
         RenderSystem.setShaderTexture(0, src.texture());
         RenderSystem.setShaderTexture(1, src.texture());
         shader.safeGetUniform("TexelSize").set(1.0F / sw, 1.0F / sh);
         shader.safeGetUniform("HeavyUvMax").set(1.0F, 1.0F);
         shader.safeGetUniform("PassParams").set(1.0F, strides[p] * resScale, decays[p], 0.0F);
         this.drawFullscreen(shader, null, null, sw, sh);
         readA = !readA;
      }
      return (readA ? this.targets.streakA : this.targets.streakB).texture();
   }

   /** 运行自发光专属径向体积光（优先采抗闪烁 bloomDown[0]，跨画质步数归一化亮度一致）。 */
   private int runGodRays(
      int width, int height, int blueNoiseTex, int bloomDown0Tex,
      boolean hasHdrGeo, boolean hasHeavyMrt, float heavyUvMaxX, float heavyUvMaxY,
      StellarisOpticsState optics, StellarisQuality.Spec spec
   ) {
      ShaderInstance shader = StellarisShaders.godRays;
      int gw = spec.fullResGodRays() ? width : Math.max(2, width / 2);
      int gh = spec.fullResGodRays() ? height : Math.max(2, height / 2);
      this.targets.godRays.ensure(gw, gh);
      this.targets.godRays.bindWrite();
      RenderSystem.disableBlend();

      boolean usePrefiltered = bloomDown0Tex != 0;
      int primaryTex = usePrefiltered ? bloomDown0Tex : (hasHdrGeo ? this.targets.hdrGeo.texture() : this.targets.heavyMrt.emissiveTexture());
      int heavyTex = hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : primaryTex;
      RenderSystem.setShaderTexture(0, primaryTex);
      RenderSystem.setShaderTexture(1, heavyTex);
      RenderSystem.setShaderTexture(2, blueNoiseTex);

      shader.safeGetUniform("OutSize").set((float)gw, (float)gh);
      shader.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
      shader.safeGetUniform("Meta").set(
         (float)spec.godRaySteps(),
         (float)(this.frameCounter & 1023),
         (usePrefiltered || hasHdrGeo) ? 1.0F : 0.0F,
         (!usePrefiltered && hasHeavyMrt) ? 1.0F : 0.0F
      );
      shader.safeGetUniform("Source0").set(optics.godRayU[0], optics.godRayV[0], optics.godRayStrength[0], optics.godRayDecay[0]);
      shader.safeGetUniform("Color0").set(optics.godRayR[0], optics.godRayG[0], optics.godRayB[0], 1.0F);
      float s1 = optics.godRayCount >= 2 ? optics.godRayStrength[1] : 0.0F;
      shader.safeGetUniform("Source1").set(optics.godRayU[1], optics.godRayV[1], s1, optics.godRayDecay[1]);
      shader.safeGetUniform("Color1").set(optics.godRayR[1], optics.godRayG[1], optics.godRayB[1], 1.0F);
      this.drawFullscreen(shader, null, null, gw, gh);
      return this.targets.godRays.texture();
   }

   /** 在 1×1 RG16F 双缓冲目标上纯 GPU 计算基准 1.0 防过曝自动曝光（开泛光测泛光最低级，关泛光直测 hdrGeo + MRT 自发光，保证一致）。 */
   private int runAutoExposure(
      int sceneTex,
      int lowestBloomTex,
      boolean hasHdrGeo,
      boolean hasHeavyMrt,
      float heavyUvMaxX,
      float heavyUvMaxY,
      float dtSeconds,
      StellarisOpticsState optics
   ) {
      ShaderInstance shader = StellarisShaders.exposure;
      FxTarget prev = this.autoExposure.previousTarget(this.targets);
      FxTarget curr = this.autoExposure.currentTarget(this.targets);
      prev.ensure(1, 1);
      curr.ensure(1, 1);
      curr.bindWrite();
      RenderSystem.disableBlend();

      boolean hasBloom = lowestBloomTex != 0;
      int sampler1Tex = hasBloom ? lowestBloomTex : (hasHdrGeo ? this.targets.hdrGeo.texture() : sceneTex);
      int sampler3Tex = hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : sceneTex;
      RenderSystem.setShaderTexture(0, sceneTex);
      RenderSystem.setShaderTexture(1, sampler1Tex);
      RenderSystem.setShaderTexture(2, prev.texture());
      RenderSystem.setShaderTexture(3, sampler3Tex);
      shader.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
      shader.safeGetUniform("AdaptParams").set(
         dtSeconds,
         AutoExposure.TAU_DARKEN,
         AutoExposure.TAU_BRIGHTEN,
         this.autoExposure.initialized() ? 1.0F : 0.0F
      );
      shader.safeGetUniform("ExposureBounds").set(
         optics.exposureThreshold,
         optics.exposureMin,
         optics.exposureSlope,
         0.0F
      );
      shader.safeGetUniform("SourceFlags").set(
         hasBloom ? 1.0F : 0.0F,
         hasHdrGeo ? 1.0F : 0.0F,
         hasHeavyMrt ? 1.0F : 0.0F,
         0.0F
      );
      this.drawFullscreen(shader, null, null, 1, 1);
      this.autoExposure.markUpdated();
      return this.autoExposure.readableTexture(this.targets);
   }

   private void setCommonUniforms(ShaderInstance shader, FxFrame frame, int outW, int outH, StellarisQuality.Spec spec) {
      shader.safeGetUniform("InvProj").set(frame.inverseProjection);
      shader.safeGetUniform("InvView").set(frame.inverseView);
      shader.safeGetUniform("ViewProj").set(frame.viewProjection);
      shader.safeGetUniform("StTime").set(frame.time);
      shader.safeGetUniform("OutSize").set((float)outW, (float)outH);
      shader.safeGetUniform("FrameMeta").set((float)(this.frameCounter & 4095), (float)spec.blackHoleSteps(), (float)spec.auroraLayers(), 0.0F);
   }

   /** 将 64³ 3D 体积噪声绑定到高纹理单元 GL_TEXTURE12 并设置可选 uniform {@code Noise3D}。 */
   public static void bindNoise3D(int programId, int noise3dTex) {
      if (noise3dTex == 0 || programId <= 0) {
         return;
      }
      int loc = GL20.glGetUniformLocation(programId, "Noise3D");
      if (loc >= 0) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISE3D_TEXTURE_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, noise3dTex);
         GL20.glUniform1i(loc, NOISE3D_TEXTURE_UNIT);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
   }

   private void ensureFullscreenVbo() {
      if (this.fullscreenVbo != null) {
         return;
      }
      VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      builder.begin(Mode.TRIANGLES, DefaultVertexFormat.POSITION);
      builder.vertex(-1.0, -1.0, 0.0).endVertex();
      builder.vertex(3.0, -1.0, 0.0).endVertex();
      builder.vertex(-1.0, 3.0, 0.0).endVertex();
      vbo.bind();
      vbo.upload(builder.end());
      VertexBuffer.unbind();
      this.fullscreenVbo = vbo;
   }

   public void drawFullscreen(
      ShaderInstance shader,
      @Nullable IntConsumer extraGlBinder,
      @Nullable StellarisPass.ScreenRect scissorRect,
      int targetW,
      int targetH
   ) {
      this.ensureFullscreenVbo();
      for (int i = 0; i < 12; i++) {
         shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
      }
      if (shader.MODEL_VIEW_MATRIX != null) {
         shader.MODEL_VIEW_MATRIX.set(IDENTITY);
      }
      if (shader.PROJECTION_MATRIX != null) {
         shader.PROJECTION_MATRIX.set(IDENTITY);
      }

      boolean scissorApplied = false;
      if (scissorRect != null) {
         int x0 = Math.max(0, (int)Math.floor(scissorRect.minU() * targetW) - 2);
         int y0 = Math.max(0, (int)Math.floor(scissorRect.minV() * targetH) - 2);
         int x1 = Math.min(targetW, (int)Math.ceil(scissorRect.maxU() * targetW) + 2);
         int y1 = Math.min(targetH, (int)Math.ceil(scissorRect.maxV() * targetH) + 2);
         if (x1 <= x0 || y1 <= y0) {
            return;
         }
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
         GL11.glScissor(x0, y0, x1 - x0, y1 - y0);
         scissorApplied = true;
      }

      this.fullscreenVbo.bind();
      shader.apply();
      try {
         if (extraGlBinder != null) {
            extraGlBinder.accept(shader.getId());
         }
         this.fullscreenVbo.draw();
      } finally {
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         shader.clear();
         VertexBuffer.unbind();
         if (scissorApplied) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
         }
      }
   }

   void drawWarmupDummy(ShaderInstance shader) {
      this.drawFullscreen(shader, programId -> bindNoise3D(programId, StellarisProceduralAssets.INSTANCE.noise3dTexture()), null, 1, 1);
   }

   private void fail(String stage, Throwable throwable) {
      this.failed = true;
      if (!this.loggedFailure) {
         this.loggedFailure = true;
         Annihilationblade.LOGGER.error("[Stellaris Engine] Disabled after error during {}; falling back until F3+T reload.", stage, throwable);
      }
   }

   public void release() {
      this.targets.destroyAll();
      this.gpuTimer.destroy();
      this.warmup.destroy();
      this.autoExposure.reset();
      this.holdFrame.reset();
      this.impactSequencer.resetSession();
      this.lastFrameNs = 0L;
      if (this.fullscreenVbo != null) {
         RenderSystem.assertOnRenderThread();
         this.fullscreenVbo.close();
         this.fullscreenVbo = null;
      }
   }
}
