package QWQ.QingYi.annihilationblade.stellaris.engine;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 3D 物理浮雕法阵 2 附件浮点 MRT 目标（Step 1.6）。
 * <ul>
 *   <li>{@code Attachment 0 (RGBA16F, LINEAR)}：{@code outColor} —— 3D 圆管立体法阵 HDR 自发光辐射度；</li>
 *   <li>{@code Attachment 1 (RGBA32F, NEAREST)}：{@code outAux (ReliefMeta)} —— {@code r = 最近命中距离(未命中 1e6)}、{@code g = 透射率(0..1)}、{@code b = 场景深度距离}、{@code a = 法阵 ID}。</li>
 * </ul>
 * 下游的几何体、光柱、黑洞、极光、TBO 粒子与体积光均可读取 {@code ReliefMeta}，实现光柱与万级星尘真实“穿过立体法阵环孔并被前环遮挡”。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisReliefTarget {
   private static final int[] DRAW_BUFFERS = new int[]{
      GL30.GL_COLOR_ATTACHMENT0,
      GL30.GL_COLOR_ATTACHMENT1
   };
   private static final float[] CLEAR_COLOR = new float[]{0.0F, 0.0F, 0.0F, 0.0F};
   private static final float[] CLEAR_META = new float[]{1.0E6F, 1.0F, 1.0E6F, 0.0F};

   private int fbo;
   private int allocWidth;
   private int allocHeight;
   private int activeWidth;
   private int activeHeight;
   private int colorTex;
   private int metaTex;

   public boolean ensureAllocated(int maxWidth, int maxHeight) {
      maxWidth = Math.max(1, maxWidth);
      maxHeight = Math.max(1, maxHeight);
      if (this.fbo != 0 && this.allocWidth == maxWidth && this.allocHeight == maxHeight) {
         return false;
      }
      RenderSystem.assertOnRenderThread();
      this.destroy();

      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevPbo = StellarisGlGuard.unbindPixelUnpackBuffer();
      try {
         this.allocWidth = maxWidth;
         this.allocHeight = maxHeight;
         this.activeWidth = maxWidth;
         this.activeHeight = maxHeight;

         this.colorTex = GlStateManager._genTexture();
         GlStateManager._bindTexture(this.colorTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, maxWidth, maxHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

         this.metaTex = GlStateManager._genTexture();
         GlStateManager._bindTexture(this.metaTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, maxWidth, maxHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

         this.fbo = GlStateManager.glGenFramebuffers();
         GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
         GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.colorTex, 0);
         GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, this.metaTex, 0);
         GL20.glDrawBuffers(DRAW_BUFFERS);

         int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete StellarisReliefTarget framebuffer: 0x" + Integer.toHexString(status));
         }
         GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR_COLOR);
         GL30.glClearBufferfv(GL11.GL_COLOR, 1, CLEAR_META);
         return true;
      } catch (Throwable throwable) {
         this.destroy();
         throw throwable;
      } finally {
         StellarisGlGuard.restorePixelUnpackBuffer(prevPbo);
         GlStateManager._bindTexture(prevTex);
         GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
         GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      }
   }

   public void bindAndClearActive(int targetW, int targetH) {
      this.activeWidth = Math.max(1, Math.min(this.allocWidth, targetW));
      this.activeHeight = Math.max(1, Math.min(this.allocHeight, targetH));
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
      GL20.glDrawBuffers(DRAW_BUFFERS);

      int clearW = Math.min(this.allocWidth, this.activeWidth + 2);
      int clearH = Math.min(this.allocHeight, this.activeHeight + 2);
      boolean partial = clearW < this.allocWidth || clearH < this.allocHeight;
      if (partial) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
         GL11.glScissor(0, 0, clearW, clearH);
      }
      GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR_COLOR);
      GL30.glClearBufferfv(GL11.GL_COLOR, 1, CLEAR_META);
      if (partial) {
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
      }
      GlStateManager._viewport(0, 0, this.activeWidth, this.activeHeight);
   }

   public int colorTexture() {
      return this.colorTex;
   }

   public int metaTexture() {
      return this.metaTex;
   }

   public int width() {
      return Math.max(1, this.activeWidth);
   }

   public int height() {
      return Math.max(1, this.activeHeight);
   }

   public float uvMaxX() {
      return this.allocWidth <= 0 ? 1.0F : (float)this.activeWidth / (float)this.allocWidth;
   }

   public float uvMaxY() {
      return this.allocHeight <= 0 ? 1.0F : (float)this.activeHeight / (float)this.allocHeight;
   }

   public boolean ready() {
      return this.fbo != 0;
   }

   public void destroy() {
      if (this.fbo != 0) {
         RenderSystem.assertOnRenderThread();
         GL30.glDeleteFramebuffers(this.fbo);
         this.fbo = 0;
      }
      if (this.colorTex != 0) {
         RenderSystem.assertOnRenderThread();
         GlStateManager._deleteTexture(this.colorTex);
         this.colorTex = 0;
      }
      if (this.metaTex != 0) {
         RenderSystem.assertOnRenderThread();
         GlStateManager._deleteTexture(this.metaTex);
         this.metaTex = 0;
      }
      this.allocWidth = 0;
      this.allocHeight = 0;
      this.activeWidth = 0;
      this.activeHeight = 0;
   }
}
