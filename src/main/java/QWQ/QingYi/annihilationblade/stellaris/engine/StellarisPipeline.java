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
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisRibbons;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisTboShaders;
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
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * 星穹渲染引擎（Stellaris Engine）主帧图管线（Step 1.6 终极升格版）。
 * <p>
 * 完整 9 阶段帧图顺序：
 * <ol>
 *   <li><b>RuneCache + ReliefPass</b>：GPU 1 帧预烘焙 2048²/4096² 星穹浑天距离场 + 8 座 3D 物理圆管浮雕法阵步进，输出 {@code ReliefColor} + {@code ReliefMeta}；</li>
 *   <li><b>Geometry + TBO Particles + TBO Ribbons</b>：暗部本体层 + 全分辨率线性 HDR 几何 + 万级 GPU 实例化 5 形态粒子与 3D 能量丝带，全部自动接入 {@code ReliefMeta} 真实穿孔遮挡；</li>
 *   <li><b>Scene Ingest</b>：主画面精确 IEC 线性 HDR 摄入 {@code sceneA.colorTex} 并清零 {@code sceneA.emitTex}；</li>
 *   <li><b>Heavy MRT</b>：零重建动态分辨率 3 附件重型 MRT（如 2D 共面 Binet RK4 史瓦西黑洞），可读 {@code Noise3D} + {@code ReliefMeta}；</li>
 *   <li><b>Scene Rewrite (Dual Output)</b>：全分辨率线性 HDR 场景改写乒乓（{@code outColor + outEmit}），让夺夜亮星、极光、遥测、熔融裂纹与虫洞环正式进入泛光与星芒；</li>
 *   <li><b>Bloom (4-source) + Anamorphic Streak (4-source)</b>：同步吸收 {@code hdrGeo + heavyEmit + reliefColor + sceneEmit}；</li>
 *   <li><b>God Rays (Depth + Relief Occluded)</b>：连续平滑世界深度 + 3D 浮雕法阵管壁遮挡的物理耶稣光；</li>
 *   <li><b>Async Auto Exposure</b>：基准 1.0 全 GPU 1×1 异步防过曝测光（开/关泛光 4 源一致）；</li>
 *   <li><b>Two-Stage AgX Filmic Composite + Full Hold-Frame Capture</b>：斜坡自适应双边升采样、Heavy×Relief 前后真深度互穿、AgX 色调映射，以及完整预合成 Hold-Frame 捕获。</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisPipeline {
   public static final StellarisPipeline INSTANCE = new StellarisPipeline();
   /** 3D 体积噪声固定绑定的高纹理单元 GL_TEXTURE12。 */
   public static final int NOISE3D_TEXTURE_UNIT = StellarisGlGuard.NOISE3D_UNIT;

   private static final Matrix4f IDENTITY = new Matrix4f();
   private static final ResourceLocation FLOW_NOISE = ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "textures/fx/fx_flow_noise.png");

   private final StellarisTargets targets = new StellarisTargets();
   private final StellarisQuality quality = new StellarisQuality();
   private final StellarisGpuTimer gpuTimer = new StellarisGpuTimer();
   private final StellarisWarmup warmup = new StellarisWarmup();
   private final AutoExposure autoExposure = new AutoExposure();
   private final HoldFrame holdFrame = new HoldFrame();
   private final ImpactFrameSequencer impactSequencer = new ImpactFrameSequencer();

   // 复用 3D 浮雕法阵 uniform 上传缓冲（零 GC）
   private final float[] reliefCenterRadius = new float[StellarisReliefArray.MAX_ARRAYS * 4];
   private final float[] reliefNormalSpin = new float[StellarisReliefArray.MAX_ARRAYS * 4];
   private final float[] reliefMotion = new float[StellarisReliefArray.MAX_ARRAYS * 4];
   private final float[] reliefColor = new float[StellarisReliefArray.MAX_ARRAYS * 4];
   private final float[] reliefViewBounds = new float[StellarisReliefArray.MAX_ARRAYS * 4];

   private VertexBuffer fullscreenVbo;
   private boolean failed;
   private boolean heavyDegraded;
   private boolean reliefDegraded;
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

   public StellarisTargets targets() {
      return this.targets;
   }

   public boolean isFailed() {
      return this.failed;
   }

   public boolean isHeavyDegraded() {
      return this.heavyDegraded;
   }

   public boolean isReliefDegraded() {
      return this.reliefDegraded;
   }

   public boolean active() {
      StellarisConfigSnapshot snap = StellarisConfigSnapshot.current();
      if (this.failed || !snap.rendererEnabled() || !StellarisShaders.coreReady()) {
         return false;
      }
      if (this.quality.effectiveSpec().preset() == ModConfig.StellarisQualityPreset.FALLBACK) {
         return false;
      }
      return !FxCompat.shaderPackActive() || snap.withShaderPack();
   }

   public boolean heavyPassesAllowed(StellarisQuality.Spec spec) {
      StellarisConfigSnapshot snap = StellarisConfigSnapshot.current();
      if (this.heavyDegraded || !spec.heavyEnabled() || !StellarisShaders.mrtReady() || !snap.volumetricEnabled()) {
         return false;
      }
      return !FxCompat.shaderPackActive() || !snap.disableHeavyPassesWithShaderPack();
   }

   public boolean reliefPassesAllowed(StellarisQuality.Spec spec) {
      StellarisConfigSnapshot snap = StellarisConfigSnapshot.current();
      if (this.reliefDegraded || !snap.relief3dEnabled() || !spec.heavyEnabled() || !StellarisShaders.reliefReady()) {
         return false;
      }
      return !FxCompat.shaderPackActive() || !snap.disableHeavyPassesWithShaderPack();
   }

   public void onShadersReloaded() {
      this.failed = false;
      this.heavyDegraded = false;
      this.reliefDegraded = false;
      this.loggedFailure = false;
      this.targets.runeCache.invalidate();
      this.autoExposure.reset();
      this.holdFrame.reset();
      this.warmup.reset();
      StellarisProceduralAssets.INSTANCE.resetFailure();
   }

   public void resetDiagnostics() {
      this.failed = false;
      this.heavyDegraded = false;
      this.reliefDegraded = false;
      this.loggedFailure = false;
      this.targets.runeCache.invalidate();
      this.quality.resetAdaptive();
      this.autoExposure.reset();
      this.holdFrame.reset();
   }

   public void tickWarmupIfIdle() {
      StellarisConfigSnapshot.refresh();
      if (!this.failed && !this.warmup.isCompleted() && this.active()) {
         this.warmup.step(this);
      }
   }

   public void render(
      FxFrame frame,
      List<StellarisEffect> effects,
      StellarisOpticsState optics,
      StellarisParticles particles,
      StellarisRibbons ribbons
   ) {
      StellarisConfigSnapshot snap = StellarisConfigSnapshot.refresh();
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
      boolean allowRelief = this.reliefPassesAllowed(spec);

      // 提前收集 3D 浮雕法阵、重型 MRT Pass、全屏场景 Pass 与 3D 丝带
      List<StellarisReliefArray> rawRelief = new ArrayList<>();
      List<StellarisPass> rawPasses = new ArrayList<>();
      ribbons.resetFrame();
      for (StellarisEffect effect : effects) {
         if (allowRelief) {
            effect.collectReliefArrays(frame, spec, rawRelief);
         }
         effect.collectPasses(frame, spec, rawPasses);
         effect.collectRibbons(frame, ribbons);
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
      boolean hasParticles = !particles.isEmpty() && (StellarisTboShaders.INSTANCE.isReady() || StellarisShaders.particle != null);
      boolean hasRibbons = !ribbons.isEmpty();

      StellarisGlGuard guard = StellarisGlGuard.capture();
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

         // ───────────────── 阶段 0：GPU 1 帧预烘焙 RuneCache + 3D 物理圆管浮雕法阵（ReliefPass） ─────────────────
         boolean reliefRendered = false;
         float reliefUvMaxX = 1.0F;
         float reliefUvMaxY = 1.0F;
         int reliefMetaTex = 0;
         int reliefColorTex = 0;
         if (allowRelief && !rawRelief.isEmpty()) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.RELIEF);
            try {
               if (this.targets.runeCache.ensureBaked(this, spec.runeCacheSize())) {
                  reliefRendered = this.runReliefPass(frame, rawRelief, depthTex, width, height, spec);
                  if (reliefRendered) {
                     reliefUvMaxX = this.targets.relief.uvMaxX();
                     reliefUvMaxY = this.targets.relief.uvMaxY();
                     reliefMetaTex = this.targets.relief.metaTexture();
                     reliefColorTex = this.targets.relief.colorTexture();
                  }
               }
            } catch (Throwable reliefError) {
               this.reliefDegraded = true;
               reliefRendered = false;
               Annihilationblade.LOGGER.error("[Stellaris Engine] 3D ReliefPass degraded safely until resource reload.", reliefError);
            } finally {
               this.gpuTimer.end(StellarisGpuTimer.Stage.RELIEF);
            }
         }

         // ───────────────── 阶段 2：星穹原生线性暗部本体层 + HDR 几何 + 万级 TBO 粒子 + 3D 能量丝带 ─────────────────
         this.gpuTimer.begin(StellarisGpuTimer.Stage.GEOMETRY);
         RenderSystem.setShaderTexture(0, depthTex);
         RenderSystem.setShaderTexture(1, FLOW_NOISE);
         RenderSystem.setShaderTexture(2, reliefRendered ? reliefMetaTex : depthTex);
         if (StellarisShaders.geometry != null) {
            StellarisShaders.geometry.safeGetUniform("FxOutSize").set((float)width, (float)height);
            StellarisShaders.geometry.safeGetUniform("FxReliefParams").set(reliefUvMaxX, reliefUvMaxY, reliefRendered ? 1.0F : 0.0F, 0.0F);
         }

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
            int drawnParticles = particles.render(frame, depthTex, reliefMetaTex, reliefUvMaxX, reliefUvMaxY, reliefRendered);
            if (drawnParticles > 0) {
               hasHdrGeo = true;
            }
         }
         if (hasRibbons) {
            int drawnRibbons = ribbons.render(frame, depthTex, reliefMetaTex, reliefUvMaxX, reliefUvMaxY, reliefRendered);
            if (drawnRibbons > 0) {
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
         if (!hasBody && !hasHdrGeo && !reliefRendered && !hasHeavyMrt && !hasScenePasses && !hasHoldActive && !optics.hasScreenPost()) {
            this.gpuTimer.endFrame();
            return;
         }

         // ───────────────── 阶段 1：主画面精确 IEC 线性 HDR 摄入（Main RGBA8 → SceneA.color RGBA16F） ─────────────────
         this.gpuTimer.begin(StellarisGpuTimer.Stage.INGEST);
         this.targets.sceneA.ensure(width, height);
         this.targets.sceneA.clearEmitOnly();
         this.targets.sceneA.bindWriteSingle();
         RenderSystem.disableBlend();
         RenderSystem.disableDepthTest();
         RenderSystem.depthMask(false);
         RenderSystem.disableCull();
         RenderSystem.setShaderTexture(0, main.getColorTextureId());
         this.drawFullscreen(StellarisShaders.sceneIngest, null, null, width, height);
         int currentSceneTex = this.targets.sceneA.colorTexture();
         int currentEmitTex = this.targets.sceneA.emitTexture();
         boolean hasSceneEmit = false;
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

               final int finalReliefMeta = reliefRendered ? reliefMetaTex : depthTex;
               final float finalReliefMaxX = reliefUvMaxX;
               final float finalReliefMaxY = reliefUvMaxY;
               final boolean finalHasRelief = reliefRendered;

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
                     bindOptional2D(programId, "ReliefMetaTex", StellarisGlGuard.RELIEF_META_UNIT, finalReliefMeta);
                     setOptionalVec4(programId, "ReliefParams", finalReliefMaxX, finalReliefMaxY, finalHasRelief ? 1.0F : 0.0F, 0.0F);
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

         // ───────────────── 阶段 4：全分辨率线性 HDR 场景改写 Pass（双输出 SceneA↔B 乒乓，支持 SceneEmit 合流） ─────────────────
         if (hasScenePasses) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.SCENE);
            this.targets.sceneB.ensure(width, height);
            boolean writeB = true;
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();

            final int finalReliefMeta = reliefRendered ? reliefMetaTex : depthTex;
            final float finalReliefMaxX = reliefUvMaxX;
            final float finalReliefMaxY = reliefUvMaxY;
            final boolean finalHasRelief = reliefRendered;

            for (StellarisPass pass : scenePasses) {
               ShaderInstance shader = pass.shader().get();
               if (shader == null) {
                  continue;
               }
               StellarisSceneTarget out = writeB ? this.targets.sceneB : this.targets.sceneA;
               boolean dualEmit = StellarisShaders.hasSceneDualEmit(shader);
               if (dualEmit) {
                  out.bindWriteDual();
               } else {
                  out.bindWriteSingle();
               }
               RenderSystem.setShaderTexture(0, currentSceneTex);
               RenderSystem.setShaderTexture(1, depthTex);
               RenderSystem.setShaderTexture(2, blackbodyTex);
               RenderSystem.setShaderTexture(3, blueNoiseTex);
               this.setCommonUniforms(shader, frame, width, height, spec);
               pass.uniforms().accept(shader);
               final int prevEmitForPass = currentEmitTex;
               IntConsumer combinedExtra = programId -> {
                  bindNoise3D(programId, noise3dTex);
                  bindOptional2D(programId, "PrevSceneEmit", StellarisGlGuard.RELIEF_COLOR_UNIT, prevEmitForPass);
                  bindOptional2D(programId, "ReliefMetaTex", StellarisGlGuard.RELIEF_META_UNIT, finalReliefMeta);
                  setOptionalVec4(programId, "ReliefParams", finalReliefMaxX, finalReliefMaxY, finalHasRelief ? 1.0F : 0.0F, 0.0F);
                  if (pass.extraGlBinder() != null) {
                     pass.extraGlBinder().accept(programId);
                  }
               };
               this.drawFullscreen(shader, combinedExtra, null, width, height);
               currentSceneTex = out.colorTexture();
               if (dualEmit) {
                  currentEmitTex = out.emitTexture();
                  hasSceneEmit = true;
               }
               writeB = !writeB;
            }
            this.gpuTimer.end(StellarisGpuTimer.Stage.SCENE);
         }

         boolean anyEmissiveSource = hasHdrGeo || heavyRendered || reliefRendered || hasSceneEmit;

         // ───────────────── 阶段 5a：6 级四源合流 HDR 双频泛光金字塔 ─────────────────
         float bloomStrength = snap.bloomEnabled() ? (snap.bloomStrength() * optics.bloomMultiplier) : 0.0F;
         int bloomTex = 0;
         int bloomDown0Tex = 0;
         int lowestBloomTex = 0;
         if (anyEmissiveSource && bloomStrength > 0.001F && StellarisShaders.bloomDown != null && StellarisShaders.bloomUp != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.BLOOM);
            int[] bloomResult = this.runBloomPyramid(
               width, height,
               hasHdrGeo, heavyRendered, reliefRendered, hasSceneEmit,
               heavyUvMaxX, heavyUvMaxY, reliefUvMaxX, reliefUvMaxY,
               reliefColorTex, currentEmitTex
            );
            bloomTex = bloomResult[0];
            lowestBloomTex = bloomResult[1];
            bloomDown0Tex = bloomResult[2];
            this.gpuTimer.end(StellarisGpuTimer.Stage.BLOOM);
         }

         // ───────────────── 阶段 5b：1/4 分辨率 4 趟四源合流变形水平星芒 ─────────────────
         int streakTex = 0;
         float effectiveStreak = snap.anamorphicStreaks() ? optics.streakStrength : 0.0F;
         if (anyEmissiveSource && effectiveStreak > 0.001F && StellarisShaders.anamorphic != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.STREAK);
            streakTex = this.runAnamorphicStreak(
               width, height,
               hasHdrGeo, heavyRendered, reliefRendered, hasSceneEmit,
               heavyUvMaxX, heavyUvMaxY, reliefUvMaxX, reliefUvMaxY,
               reliefColorTex, currentEmitTex, optics
            );
            this.gpuTimer.end(StellarisGpuTimer.Stage.STREAK);
         }

         // ───────────────── 阶段 6：连续世界深度 + 3D 浮雕遮挡径向体积光（耶稣光） ─────────────────
         int godRayTex = 0;
         if (anyEmissiveSource && optics.godRayCount > 0 && spec.godRaySteps() > 0 && StellarisShaders.godRays != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.GOD_RAYS);
            godRayTex = this.runGodRays(
               frame, width, height, depthTex, blueNoiseTex, bloomDown0Tex,
               hasHdrGeo, heavyRendered, reliefRendered, hasSceneEmit,
               heavyUvMaxX, heavyUvMaxY, reliefUvMaxX, reliefUvMaxY,
               reliefColorTex, reliefMetaTex, currentEmitTex, optics, spec
            );
            this.gpuTimer.end(StellarisGpuTimer.Stage.GOD_RAYS);
         }

         // ───────────────── 阶段 7：基准 1.0 全 GPU 1×1 异步自动曝光（4 源一致测光） ─────────────────
         int exposureTex = 0;
         if (optics.autoExposureWeight > 0.001F && StellarisShaders.exposure != null) {
            this.gpuTimer.begin(StellarisGpuTimer.Stage.EXPOSURE);
            exposureTex = this.runAutoExposure(
               currentSceneTex, lowestBloomTex,
               hasHdrGeo, heavyRendered, reliefRendered, hasSceneEmit,
               heavyUvMaxX, heavyUvMaxY, reliefUvMaxX, reliefUvMaxY,
               reliefColorTex, currentEmitTex, dtSeconds, optics
            );
            this.gpuTimer.end(StellarisGpuTimer.Stage.EXPOSURE);
         }

         // ───────────────── 阶段 8：两阶段 AgX 电影合成 + 完整预合成 Hold-Frame 残像捕获 ─────────────────
         this.gpuTimer.begin(StellarisGpuTimer.Stage.COMPOSITE);
         ImpactFrameSequencer.ResolvedBeat beat = this.impactSequencer.resolve(optics);
         float aberration = optics.aberration * snap.chromaticAberration();
         boolean holdReady = this.holdFrame.active() && (captureHoldThisFrame || this.targets.holdFrame.ready());
         boolean needTwoPassPost = captureHoldThisFrame || optics.zoomBlur > 0.001F || aberration > 0.001F || holdReady || beat.strength() > 0.001F;

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
         comp.safeGetUniform("ReliefSize").set(
            reliefRendered ? (float)this.targets.relief.width() : (float)width,
            reliefRendered ? (float)this.targets.relief.height() : (float)height
         );
         comp.safeGetUniform("ReliefUvMax").set(reliefUvMaxX, reliefUvMaxY);
         comp.safeGetUniform("LayerWeights").set(
            hasBody ? 1.0F : 0.0F,
            hasHdrGeo ? 1.0F : 0.0F,
            heavyRendered ? 1.0F : 0.0F,
            optics.detailPreservation
         );
         comp.safeGetUniform("ExtraLayerWeights").set(
            reliefRendered ? 1.0F : 0.0F,
            hasSceneEmit ? 1.0F : 0.0F,
            0.0F,
            0.0F
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
         float flashStrength = optics.flash * snap.screenFlash();
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

         final int compSceneEmit = hasSceneEmit ? currentEmitTex : fallbackTex;
         final int compReliefColor = reliefRendered ? reliefColorTex : fallbackTex;
         final int compReliefMeta = reliefRendered ? reliefMetaTex : fallbackTex;
         IntConsumer compExtraBinder = programId -> {
            bindOptional2D(programId, "SceneEmitTex", StellarisGlGuard.SCENE_EMIT_UNIT, compSceneEmit);
            bindOptional2D(programId, "ReliefColorTex", StellarisGlGuard.RELIEF_COLOR_UNIT, compReliefColor);
            bindOptional2D(programId, "ReliefMetaTex", StellarisGlGuard.RELIEF_META_UNIT, compReliefMeta);
         };

         if (needTwoPassPost) {
            StellarisSceneTarget precompTarget = (currentSceneTex == this.targets.sceneA.colorTexture()) ? this.targets.sceneB : this.targets.sceneA;
            precompTarget.ensure(width, height);
            precompTarget.bindWriteSingle();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            comp.safeGetUniform("PostMode").set(1.0F);
            this.drawFullscreen(comp, compExtraBinder, null, width, height);

            // 爆发瞬间直接从完整线性 HDR 预合成画面（含 Body / 3D 浮雕 / 泛光 / 星芒 / 体积光）捕获视网膜残像！
            if (captureHoldThisFrame && StellarisShaders.holdCopy != null) {
               int hw = Math.max(2, width / 2);
               int hh = Math.max(2, height / 2);
               this.targets.holdFrame.ensure(hw, hh);
               this.targets.holdFrame.bindWrite();
               RenderSystem.disableBlend();
               RenderSystem.disableDepthTest();
               RenderSystem.depthMask(false);
               RenderSystem.disableCull();
               RenderSystem.setShaderTexture(0, precompTarget.colorTexture());
               ShaderInstance holdCopy = StellarisShaders.holdCopy;
               holdCopy.safeGetUniform("TexelSize").set(1.0F / width, 1.0F / height);
               this.drawFullscreen(holdCopy, null, null, hw, hh);
            }

            main.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderTexture(0, precompTarget.colorTexture());
            RenderSystem.setShaderTexture(1, (holdReady && this.targets.holdFrame.ready()) ? this.targets.holdFrame.texture() : precompTarget.colorTexture());
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
            this.drawFullscreen(comp, compExtraBinder, null, width, height);
         }
         this.gpuTimer.end(StellarisGpuTimer.Stage.COMPOSITE);

         this.gpuTimer.endFrame();
         this.quality.recordFrameGpuMs(this.gpuTimer.smoothedTotalMs());
      } catch (Throwable throwable) {
         this.fail("render", throwable);
      } finally {
         BufferBuilder globalBuilder = Tesselator.getInstance().getBuilder();
         if (globalBuilder.building()) {
            BufferBuilder.RenderedBuffer stray = globalBuilder.endOrDiscardIfEmpty();
            if (stray != null) {
               stray.release();
            }
         }
         modelView.popPose();
         RenderSystem.applyModelViewMatrix();
         RenderSystem.restoreProjectionMatrix();
         guard.restore();
         main.bindWrite(true);
      }
   }

   /** 执行 3D 物理圆管浮雕法阵步进通道（最多 8 座并行 + 视锥外裁剪 + 联合 Scissor）。 */
   private boolean runReliefPass(
      FxFrame frame,
      List<StellarisReliefArray> rawArrays,
      int depthTex,
      int width,
      int height,
      StellarisQuality.Spec spec
   ) {
      ShaderInstance shader = StellarisShaders.relief;
      if (shader == null || !this.targets.runeCache.ready()) {
         return false;
      }

      int visibleCount = 0;
      float unionMinU = 1.0F;
      float unionMinV = 1.0F;
      float unionMaxU = 0.0F;
      float unionMaxV = 0.0F;

      for (int i = 0; i < rawArrays.size() && visibleCount < StellarisReliefArray.MAX_ARRAYS; i++) {
         StellarisReliefArray arr = rawArrays.get(i);
         if (arr.reveal() <= 0.005F || arr.intensity() <= 0.005F) {
            continue;
         }
         StellarisPass.ScreenRect rect = StellarisPass.projectSphereRect(frame, arr.center(), arr.radius() * 1.24F);
         if (rect != null && rect.empty()) {
            continue;
         }
         float minU = rect == null ? 0.0F : Math.max(0.0F, rect.minU() - 0.015F);
         float minV = rect == null ? 0.0F : Math.max(0.0F, rect.minV() - 0.015F);
         float maxU = rect == null ? 1.0F : Math.min(1.0F, rect.maxU() + 0.015F);
         float maxV = rect == null ? 1.0F : Math.min(1.0F, rect.maxV() + 0.015F);

         unionMinU = Math.min(unionMinU, minU);
         unionMinV = Math.min(unionMinV, minV);
         unionMaxU = Math.max(unionMaxU, maxU);
         unionMaxV = Math.max(unionMaxV, maxV);

         Vector3f rel = frame.rel(arr.center());
         int o = visibleCount * 4;
         this.reliefCenterRadius[o]     = rel.x;
         this.reliefCenterRadius[o + 1] = rel.y;
         this.reliefCenterRadius[o + 2] = rel.z;
         this.reliefCenterRadius[o + 3] = arr.radius();

         this.reliefNormalSpin[o]     = arr.normal().x;
         this.reliefNormalSpin[o + 1] = arr.normal().y;
         this.reliefNormalSpin[o + 2] = arr.normal().z;
         this.reliefNormalSpin[o + 3] = arr.outerSpinRad();

         this.reliefMotion[o]     = arr.innerSpinRad();
         this.reliefMotion[o + 1] = arr.tubeScale();
         this.reliefMotion[o + 2] = arr.reveal();
         this.reliefMotion[o + 3] = arr.intensity();

         this.reliefColor[o]     = srgbToLinear(((arr.rgb() >> 16) & 0xFF) / 255.0F);
         this.reliefColor[o + 1] = srgbToLinear(((arr.rgb() >> 8) & 0xFF) / 255.0F);
         this.reliefColor[o + 2] = srgbToLinear((arr.rgb() & 0xFF) / 255.0F);
         this.reliefColor[o + 3] = (float)arr.variant();

         this.reliefViewBounds[o]     = minU;
         this.reliefViewBounds[o + 1] = minV;
         this.reliefViewBounds[o + 2] = maxU;
         this.reliefViewBounds[o + 3] = maxV;

         visibleCount++;
      }

      if (visibleCount <= 0 || unionMaxU <= unionMinU || unionMaxV <= unionMinV) {
         return false;
      }

      int allocW = Math.max(2, Math.round(width * spec.heavyScaleMax()));
      int allocH = Math.max(2, Math.round(height * spec.heavyScaleMax()));
      this.targets.relief.ensureAllocated(allocW, allocH);

      float scale = this.quality.currentHeavyScale();
      int activeW = Math.max(2, Math.min(allocW, Math.round(width * scale)));
      int activeH = Math.max(2, Math.min(allocH, Math.round(height * scale)));
      this.targets.relief.bindAndClearActive(activeW, activeH);

      RenderSystem.disableDepthTest();
      RenderSystem.depthMask(false);
      RenderSystem.disableCull();
      RenderSystem.disableBlend();
      RenderSystem.setShaderTexture(0, depthTex);
      RenderSystem.setShaderTexture(1, this.targets.runeCache.texture());

      shader.safeGetUniform("InvProj").set(frame.inverseProjection);
      shader.safeGetUniform("InvView").set(frame.inverseView);
      shader.safeGetUniform("StTime").set(frame.time);
      shader.safeGetUniform("OutSize").set((float)activeW, (float)activeH);
      shader.safeGetUniform("ReliefConfig").set((float)visibleCount, (float)this.targets.runeCache.size(), (float)spec.reliefSteps(), 0.0F);

      StellarisPass.ScreenRect unionRect = (unionMinU <= 0.005F && unionMinV <= 0.005F && unionMaxU >= 0.995F && unionMaxV >= 0.995F)
         ? null
         : new StellarisPass.ScreenRect(unionMinU, unionMinV, unionMaxU, unionMaxV);

      IntConsumer arrayUploader = programId -> {
         uploadVec4Array(programId, "ArrayCenterRadius", this.reliefCenterRadius);
         uploadVec4Array(programId, "ArrayNormalSpin", this.reliefNormalSpin);
         uploadVec4Array(programId, "ArrayMotion", this.reliefMotion);
         uploadVec4Array(programId, "ArrayColor", this.reliefColor);
         uploadVec4Array(programId, "ArrayViewBounds", this.reliefViewBounds);
      };
      this.drawFullscreen(shader, arrayUploader, unionRect, activeW, activeH);
      return true;
   }

   /** 运行 6 级四源合流双频泛光金字塔，返回 {@code {最终升采样纹理, 最低级降采样纹理, 首级降采样纹理 bloomDown[0]}}。 */
   private int[] runBloomPyramid(
      int width, int height,
      boolean hasHdrGeo, boolean hasHeavyMrt, boolean hasRelief, boolean hasSceneEmit,
      float heavyUvMaxX, float heavyUvMaxY, float reliefUvMaxX, float reliefUvMaxY,
      int reliefColorTex, int sceneEmitTex
   ) {
      ShaderInstance down = StellarisShaders.bloomDown;
      ShaderInstance up = StellarisShaders.bloomUp;
      int levels = 0;
      int srcW = width;
      int srcH = height;
      int safeFallback = hasHdrGeo ? this.targets.hdrGeo.texture()
         : (hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture()
         : (hasRelief ? reliefColorTex : sceneEmitTex));
      int srcTex = hasHdrGeo ? this.targets.hdrGeo.texture() : safeFallback;
      int heavyEmissiveTex = hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : safeFallback;
      int reliefTex = hasRelief ? reliefColorTex : safeFallback;
      int emitTex = hasSceneEmit ? sceneEmitTex : safeFallback;
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
         RenderSystem.setShaderTexture(2, i == 0 ? reliefTex : srcTex);
         RenderSystem.setShaderTexture(3, i == 0 ? emitTex : srcTex);
         down.safeGetUniform("TexelSize").set(1.0F / srcW, 1.0F / srcH);
         down.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
         down.safeGetUniform("ReliefUvMax").set(reliefUvMaxX, reliefUvMaxY);
         down.safeGetUniform("Prefilter").set(i == 0 ? 1.0F : 0.0F);
         if (i == 0) {
            down.safeGetUniform("LayerWeights").set(
               hasHdrGeo ? 1.0F : 0.0F,
               hasHeavyMrt ? 1.0F : 0.0F,
               hasRelief ? 1.0F : 0.0F,
               hasSceneEmit ? 1.0F : 0.0F
            );
         } else {
            down.safeGetUniform("LayerWeights").set(1.0F, 0.0F, 0.0F, 0.0F);
         }
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

   /** 运行 1/4 分辨率 4 趟四源合流屏宽归一化变形水平星芒（Anamorphic Streak）。 */
   private int runAnamorphicStreak(
      int width, int height,
      boolean hasHdrGeo, boolean hasHeavyMrt, boolean hasRelief, boolean hasSceneEmit,
      float heavyUvMaxX, float heavyUvMaxY, float reliefUvMaxX, float reliefUvMaxY,
      int reliefColorTex, int sceneEmitTex,
      StellarisOpticsState optics
   ) {
      ShaderInstance shader = StellarisShaders.anamorphic;
      int sw = Math.max(4, width / 4);
      int sh = Math.max(4, height / 4);
      this.targets.streakA.ensure(sw, sh);
      this.targets.streakB.ensure(sw, sh);
      RenderSystem.disableBlend();

      this.targets.streakA.bindWrite();
      int safeFallback = hasHdrGeo ? this.targets.hdrGeo.texture()
         : (hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture()
         : (hasRelief ? reliefColorTex : sceneEmitTex));
      RenderSystem.setShaderTexture(0, hasHdrGeo ? this.targets.hdrGeo.texture() : safeFallback);
      RenderSystem.setShaderTexture(1, hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : safeFallback);
      RenderSystem.setShaderTexture(2, hasRelief ? reliefColorTex : safeFallback);
      RenderSystem.setShaderTexture(3, hasSceneEmit ? sceneEmitTex : safeFallback);
      shader.safeGetUniform("TexelSize").set(1.0F / sw, 1.0F / sh);
      shader.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
      shader.safeGetUniform("ReliefUvMax").set(reliefUvMaxX, reliefUvMaxY);
      shader.safeGetUniform("PassParams").set(0.0F, optics.streakThreshold, 0.0F, 0.0F);
      shader.safeGetUniform("LayerWeights").set(
         hasHdrGeo ? 1.0F : 0.0F,
         hasHeavyMrt ? 1.0F : 0.0F,
         hasRelief ? 1.0F : 0.0F,
         hasSceneEmit ? 1.0F : 0.0F
      );
      this.drawFullscreen(shader, null, null, sw, sh);

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
         RenderSystem.setShaderTexture(2, src.texture());
         RenderSystem.setShaderTexture(3, src.texture());
         shader.safeGetUniform("TexelSize").set(1.0F / sw, 1.0F / sh);
         shader.safeGetUniform("HeavyUvMax").set(1.0F, 1.0F);
         shader.safeGetUniform("ReliefUvMax").set(1.0F, 1.0F);
         shader.safeGetUniform("PassParams").set(1.0F, strides[p] * resScale, decays[p], 0.0F);
         shader.safeGetUniform("LayerWeights").set(1.0F, 0.0F, 0.0F, 0.0F);
         this.drawFullscreen(shader, null, null, sw, sh);
         readA = !readA;
      }
      return (readA ? this.targets.streakA : this.targets.streakB).texture();
   }

   /** 运行带连续世界深度 + 3D 浮雕法阵遮挡的自发光专属径向体积光（耶稣光）。 */
   private int runGodRays(
      FxFrame frame, int width, int height, int depthTex, int blueNoiseTex, int bloomDown0Tex,
      boolean hasHdrGeo, boolean hasHeavyMrt, boolean hasRelief, boolean hasSceneEmit,
      float heavyUvMaxX, float heavyUvMaxY, float reliefUvMaxX, float reliefUvMaxY,
      int reliefColorTex, int reliefMetaTex, int sceneEmitTex,
      StellarisOpticsState optics, StellarisQuality.Spec spec
   ) {
      ShaderInstance shader = StellarisShaders.godRays;
      int gw = spec.fullResGodRays() ? width : Math.max(2, width / 2);
      int gh = spec.fullResGodRays() ? height : Math.max(2, height / 2);
      this.targets.godRays.ensure(gw, gh);
      this.targets.godRays.bindWrite();
      RenderSystem.disableBlend();

      boolean usePrefiltered = bloomDown0Tex != 0;
      int safeFallback = usePrefiltered ? bloomDown0Tex
         : (hasHdrGeo ? this.targets.hdrGeo.texture()
         : (hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture()
         : (hasRelief ? reliefColorTex : sceneEmitTex)));
      int primaryTex = usePrefiltered ? bloomDown0Tex : (hasHdrGeo ? this.targets.hdrGeo.texture() : safeFallback);
      RenderSystem.setShaderTexture(0, primaryTex);
      RenderSystem.setShaderTexture(1, hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : safeFallback);
      RenderSystem.setShaderTexture(2, blueNoiseTex);
      RenderSystem.setShaderTexture(3, depthTex);
      RenderSystem.setShaderTexture(4, hasRelief ? reliefMetaTex : depthTex);
      RenderSystem.setShaderTexture(5, hasRelief ? reliefColorTex : safeFallback);
      RenderSystem.setShaderTexture(6, hasSceneEmit ? sceneEmitTex : safeFallback);

      shader.safeGetUniform("InvProj").set(frame.inverseProjection);
      shader.safeGetUniform("OutSize").set((float)gw, (float)gh);
      shader.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
      shader.safeGetUniform("ReliefUvMax").set(reliefUvMaxX, reliefUvMaxY);
      shader.safeGetUniform("Meta").set(
         (float)spec.godRaySteps(),
         (float)(this.frameCounter & 1023),
         usePrefiltered ? 1.0F : 0.0F,
         hasRelief ? 1.0F : 0.0F
      );
      shader.safeGetUniform("FallbackWeights").set(
         hasHdrGeo ? 1.0F : 0.0F,
         hasHeavyMrt ? 1.0F : 0.0F,
         hasRelief ? 1.0F : 0.0F,
         hasSceneEmit ? 1.0F : 0.0F
      );
      shader.safeGetUniform("Source0").set(optics.godRayU[0], optics.godRayV[0], optics.godRayStrength[0], optics.godRayDecay[0]);
      shader.safeGetUniform("Color0").set(optics.godRayR[0], optics.godRayG[0], optics.godRayB[0], optics.godRayDist[0]);
      float s1 = optics.godRayCount >= 2 ? optics.godRayStrength[1] : 0.0F;
      shader.safeGetUniform("Source1").set(optics.godRayU[1], optics.godRayV[1], s1, optics.godRayDecay[1]);
      shader.safeGetUniform("Color1").set(optics.godRayR[1], optics.godRayG[1], optics.godRayB[1], optics.godRayDist[1]);
      this.drawFullscreen(shader, null, null, gw, gh);
      return this.targets.godRays.texture();
   }

   /** 在 1×1 RG16F 双缓冲目标上纯 GPU 计算基准 1.0 防过曝自动曝光（4 源一致测光）。 */
   private int runAutoExposure(
      int sceneTex, int lowestBloomTex,
      boolean hasHdrGeo, boolean hasHeavyMrt, boolean hasRelief, boolean hasSceneEmit,
      float heavyUvMaxX, float heavyUvMaxY, float reliefUvMaxX, float reliefUvMaxY,
      int reliefColorTex, int sceneEmitTex,
      float dtSeconds, StellarisOpticsState optics
   ) {
      ShaderInstance shader = StellarisShaders.exposure;
      FxTarget prev = this.autoExposure.previousTarget(this.targets);
      FxTarget curr = this.autoExposure.currentTarget(this.targets);
      prev.ensure(1, 1);
      curr.ensure(1, 1);
      curr.bindWrite();
      RenderSystem.disableBlend();

      boolean hasBloom = lowestBloomTex != 0;
      RenderSystem.setShaderTexture(0, sceneTex);
      RenderSystem.setShaderTexture(1, hasBloom ? lowestBloomTex : (hasHdrGeo ? this.targets.hdrGeo.texture() : sceneTex));
      RenderSystem.setShaderTexture(2, prev.texture());
      RenderSystem.setShaderTexture(3, hasHeavyMrt ? this.targets.heavyMrt.emissiveTexture() : sceneTex);
      RenderSystem.setShaderTexture(4, hasRelief ? reliefColorTex : sceneTex);
      RenderSystem.setShaderTexture(5, hasSceneEmit ? sceneEmitTex : sceneTex);
      shader.safeGetUniform("HeavyUvMax").set(heavyUvMaxX, heavyUvMaxY);
      shader.safeGetUniform("ReliefUvMax").set(reliefUvMaxX, reliefUvMaxY);
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
         hasRelief ? 1.0F : 0.0F
      );
      shader.safeGetUniform("ExtraFlags").set(hasSceneEmit ? 1.0F : 0.0F, 0.0F, 0.0F, 0.0F);
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
         StellarisGlGuard.syncActiveTexture(GL13.GL_TEXTURE0);
      }
   }

   /** 绑定可选高槽位 2D 纹理（unit ∈ [12..15]），若着色器未声明该 uniform 则 0 开销跳过。 */
   public static void bindOptional2D(int programId, String uniformName, int textureUnit, int textureId) {
      if (programId <= 0 || textureId == 0) {
         return;
      }
      int loc = GL20.glGetUniformLocation(programId, uniformName);
      if (loc >= 0) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
         GL20.glUniform1i(loc, textureUnit);
         StellarisGlGuard.syncActiveTexture(GL13.GL_TEXTURE0);
      }
   }

   /** 设置可选 vec4 uniform（不强制在 .json 中声明）。 */
   public static void setOptionalVec4(int programId, String uniformName, float x, float y, float z, float w) {
      if (programId <= 0) {
         return;
      }
      int loc = GL20.glGetUniformLocation(programId, uniformName);
      if (loc >= 0) {
         GL20.glUniform4f(loc, x, y, z, w);
      }
   }

   /** 上传 vec4[] 数组 uniform（兼容 NVIDIA/AMD/Intel 驱动的 baseName 与 baseName[0] 命名习惯）。 */
   private static void uploadVec4Array(int programId, String baseName, float[] values) {
      int loc = GL20.glGetUniformLocation(programId, baseName);
      if (loc < 0) {
         loc = GL20.glGetUniformLocation(programId, baseName + "[0]");
      }
      if (loc >= 0) {
         GL20.glUniform4fv(loc, values);
      }
   }

   private static float srgbToLinear(float c) {
      if (c <= 0.04045F) {
         return c / 12.92F;
      }
      return (float)Math.pow((c + 0.055) / 1.055, 2.4);
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
         StellarisGlGuard.syncActiveTexture(GL13.GL_TEXTURE0);
         shader.clear();
         VertexBuffer.unbind();
         if (scissorApplied) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
         }
      }
   }

   void drawWarmupDummy(ShaderInstance shader, int dummy2dTex) {
      this.drawFullscreen(shader, programId -> {
         bindNoise3D(programId, StellarisProceduralAssets.INSTANCE.noise3dTexture());
         bindOptional2D(programId, "SceneEmitTex", StellarisGlGuard.SCENE_EMIT_UNIT, dummy2dTex);
         bindOptional2D(programId, "PrevSceneEmit", StellarisGlGuard.RELIEF_COLOR_UNIT, dummy2dTex);
         bindOptional2D(programId, "ReliefColorTex", StellarisGlGuard.RELIEF_COLOR_UNIT, dummy2dTex);
         bindOptional2D(programId, "ReliefMetaTex", StellarisGlGuard.RELIEF_META_UNIT, dummy2dTex);
      }, null, 1, 1);
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
      StellarisTboShaders.INSTANCE.destroyGlObjects();
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
