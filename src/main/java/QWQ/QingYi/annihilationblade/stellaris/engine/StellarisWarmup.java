package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹着色器 1×1 离屏分帧预热器（Step 1.6：全对称 {@link StellarisGlGuard} 隔离，安全预热含 {@code st_rune_cache} / {@code st_relief} 在内的全部着色器）。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisWarmup {
   private static final int SHADERS_PER_FRAME = 2;

   private final StellarisMrtTarget warmupTarget = new StellarisMrtTarget();
   private List<ShaderInstance> queue = Collections.emptyList();
   private int nextIndex;
   private boolean completed;

   public void reset() {
      this.queue = Collections.emptyList();
      this.nextIndex = 0;
      this.completed = false;
   }

   public boolean isCompleted() {
      return this.completed;
   }

   public int warmedCount() {
      return this.nextIndex;
   }

   public int totalCount() {
      return this.queue.size();
   }

   /** 每渲染帧最多推进 2 个着色器的 1×1 预热，全部完成后自动释放 1×1 临时目标。 */
   public void step(StellarisPipeline pipeline) {
      if (this.completed || !StellarisShaders.coreReady()) {
         return;
      }
      if (this.queue.isEmpty()) {
         this.queue = StellarisShaders.warmupCandidates();
         this.nextIndex = 0;
         if (this.queue.isEmpty()) {
            this.completed = true;
            return;
         }
      }

      StellarisProceduralAssets.INSTANCE.ensureUploaded();

      StellarisGlGuard guard = StellarisGlGuard.capture();
      try {
         FxPipeline.syncBlendCache();
         this.warmupTarget.ensure(1, 1);
         this.warmupTarget.bindWrite();
         RenderSystem.disableBlend();
         RenderSystem.disableDepthTest();
         RenderSystem.depthMask(false);
         RenderSystem.disableCull();

         int dummyTex = StellarisProceduralAssets.INSTANCE.ready()
            ? StellarisProceduralAssets.INSTANCE.blueNoiseTexture()
            : Minecraft.getInstance().getMainRenderTarget().getColorTextureId();
         for (int i = 0; i < 12; i++) {
            RenderSystem.setShaderTexture(i, dummyTex);
         }

         int budget = SHADERS_PER_FRAME;
         while (this.nextIndex < this.queue.size() && budget-- > 0) {
            ShaderInstance shader = this.queue.get(this.nextIndex++);
            if (shader != null) {
               if (shader == StellarisShaders.relief) {
                  shader.safeGetUniform("ReliefConfig").set(0.0F, 1024.0F, 36.0F, 0.0F);
               }
               pipeline.drawWarmupDummy(shader, dummyTex);
            }
         }

         if (this.nextIndex >= this.queue.size()) {
            this.completed = true;
            this.warmupTarget.destroy();
            Annihilationblade.LOGGER.info("[Stellaris Engine] Shader warmup completed ({} shaders precompiled on 1x1 FBO).", this.queue.size());
         }
      } catch (Throwable throwable) {
         this.completed = true;
         this.warmupTarget.destroy();
         Annihilationblade.LOGGER.warn("[Stellaris Engine] Shader warmup aborted safely: {}", throwable.toString());
      } finally {
         guard.restore();
      }
   }

   public void destroy() {
      this.warmupTarget.destroy();
      this.reset();
   }
}
