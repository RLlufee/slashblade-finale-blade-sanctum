package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxPipeline;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * 星穹着色器 1×1 离屏分帧预热器（完整保存/恢复 0..11 全部 12 个纹理槽位与 3D 纹理状态，零泄漏）。
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

      int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + StellarisPipeline.NOISE3D_TEXTURE_UNIT);
      int prevNoise3dBinding = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
      GL13.glActiveTexture(prevActiveTex);
      int[] viewport = new int[4];
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
      FxPipeline.StateGuard guard = FxPipeline.StateGuard.capture();
      int[] savedTex = new int[12];
      for (int i = 0; i < savedTex.length; i++) {
         savedTex[i] = RenderSystem.getShaderTexture(i);
      }

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
               pipeline.drawWarmupDummy(shader);
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
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + StellarisPipeline.NOISE3D_TEXTURE_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, prevNoise3dBinding);
         GL13.glActiveTexture(prevActiveTex);
         for (int i = 0; i < savedTex.length; i++) {
            RenderSystem.setShaderTexture(i, savedTex[i]);
         }
         guard.restore();
         GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
         GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
         GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
      }
   }

   public void destroy() {
      this.warmupTarget.destroy();
      this.reset();
   }
}
