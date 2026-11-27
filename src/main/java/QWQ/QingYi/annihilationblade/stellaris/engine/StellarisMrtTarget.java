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
 * 星穹引擎 3 附件浮点 MRT 渲染目标（支持零显存重建动态分辨率视口缩放）：
 * <ul>
 *   <li>{@code Attachment 0 (RGBA16F, LINEAR)}：世界替换/引力透镜折射辐射度 + 遮挡覆盖率 {@code alpha}（不进 {@code stFilmEmit}）；</li>
 *   <li>{@code Attachment 1 (RGBA16F, NEAREST)}：辅助信息（{@code r = 线性有效深度}, {@code g = 透射率}, {@code b = 体积光遮罩}, {@code a = 表面细节回传权重}）；</li>
 *   <li>{@code Attachment 2 (RGBA16F, LINEAR)}：纯自发光层，独立送入双边升采样、{@code stFilmEmit}、泛光、变形星芒与径向体积光。</li>
 * </ul>
 * 按最大目标尺寸一次性分配 3 张浮点纹理，动态分辨率缩放时仅变更活跃视口 {@code (activeW, activeH)} 与有效 UV 上限，
 * 彻底杜绝动态分辨率切换时的 FBO 销毁重建卡顿与驱动同步尖峰。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisMrtTarget {
   public static final int ATTACHMENTS = 3;
   private static final int[] DRAW_BUFFERS = new int[]{
      GL30.GL_COLOR_ATTACHMENT0,
      GL30.GL_COLOR_ATTACHMENT1,
      GL30.GL_COLOR_ATTACHMENT2
   };
   private static final float[] CLEAR_ZERO = new float[]{0.0F, 0.0F, 0.0F, 0.0F};
   /** Attachment 1 默认清为 r = 1e6（无穷远深度），g = 1.0（未遮挡），b = 0，a = 0。 */
   private static final float[] CLEAR_AUX = new float[]{1.0E6F, 1.0F, 0.0F, 0.0F};

   private int fbo;
   private int allocWidth;
   private int allocHeight;
   private int activeWidth;
   private int activeHeight;
   private final int[] textures = new int[ATTACHMENTS];

   /** 确保底层显存容量匹配最大分辨率（仅窗口大小变更或预设最大尺寸变动时重建）。 */
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
         for (int i = 0; i < ATTACHMENTS; i++) {
            boolean nearest = (i == 1);
            int tex = GlStateManager._genTexture();
            GlStateManager._bindTexture(tex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, maxWidth, maxHeight, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
            int filter = nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR;
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            this.textures[i] = tex;
         }

         this.fbo = GlStateManager.glGenFramebuffers();
         GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
         for (int i = 0; i < ATTACHMENTS; i++) {
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, this.textures[i], 0);
         }
         GL20.glDrawBuffers(DRAW_BUFFERS);

         int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete StellarisMrtTarget framebuffer: 0x" + Integer.toHexString(status));
         }
         this.clearFull();
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

   /** 兼容 1×1 预热等固定尺寸用法。 */
   public boolean ensure(int width, int height) {
      boolean rebuilt = this.ensureAllocated(width, height);
      this.activeWidth = this.allocWidth;
      this.activeHeight = this.allocHeight;
      return rebuilt;
   }

   /** 设定本帧动态分辨率活跃子区域，仅清屏活跃子区域 + 2px 护边（避免双边采样越界脏读）。 */
   public void bindAndClearActive(int targetActiveW, int targetActiveH) {
      this.activeWidth = Math.max(1, Math.min(this.allocWidth, targetActiveW));
      this.activeHeight = Math.max(1, Math.min(this.allocHeight, targetActiveH));
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
      GL20.glDrawBuffers(DRAW_BUFFERS);

      int clearW = Math.min(this.allocWidth, this.activeWidth + 2);
      int clearH = Math.min(this.allocHeight, this.activeHeight + 2);
      boolean partial = clearW < this.allocWidth || clearH < this.allocHeight;
      if (partial) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
         GL11.glScissor(0, 0, clearW, clearH);
      }
      GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR_ZERO);
      GL30.glClearBufferfv(GL11.GL_COLOR, 1, CLEAR_AUX);
      GL30.glClearBufferfv(GL11.GL_COLOR, 2, CLEAR_ZERO);
      if (partial) {
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
      }
      GlStateManager._viewport(0, 0, this.activeWidth, this.activeHeight);
   }

   public void bindWrite() {
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
      GL20.glDrawBuffers(DRAW_BUFFERS);
      GlStateManager._viewport(0, 0, this.activeWidth, this.activeHeight);
   }

   private void clearFull() {
      if (this.fbo == 0) {
         return;
      }
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
      GL20.glDrawBuffers(DRAW_BUFFERS);
      GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR_ZERO);
      GL30.glClearBufferfv(GL11.GL_COLOR, 1, CLEAR_AUX);
      GL30.glClearBufferfv(GL11.GL_COLOR, 2, CLEAR_ZERO);
   }

   public int colorTexture() {
      return this.textures[0];
   }

   public int auxTexture() {
      return this.textures[1];
   }

   public int emissiveTexture() {
      return this.textures[2];
   }

   public int framebuffer() {
      return this.fbo;
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
      for (int i = 0; i < ATTACHMENTS; i++) {
         if (this.textures[i] != 0) {
            RenderSystem.assertOnRenderThread();
            GlStateManager._deleteTexture(this.textures[i]);
            this.textures[i] = 0;
         }
      }
      this.allocWidth = 0;
      this.allocHeight = 0;
      this.activeWidth = 0;
      this.activeHeight = 0;
   }
}
