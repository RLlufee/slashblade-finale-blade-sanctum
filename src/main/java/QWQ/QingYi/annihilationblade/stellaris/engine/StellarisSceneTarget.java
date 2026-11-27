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
 * 阶段 4 全分辨率线性 HDR 场景乒乓目标（Step 1.6 升级：支持双附件 {@code outColor + outEmit}）。
 * <p>
 * 彻底解决阶段 4 场景 Pass（夺夜亮星、极光高光、地表遥测七段读数、电路过孔、熔融裂纹、爱因斯坦虫洞环）
 * 此前被硬截断且无法进入泛光与变形星芒的核心痛点：
 * <ul>
 *   <li>{@code Attachment 0 (RGBA16F, LINEAR)}：{@code outColor} —— 改写后的线性世界画面（夺夜底色、重打光、折射、撕裂窗）；</li>
 *   <li>{@code Attachment 1 (RGBA16F, LINEAR)}：{@code outEmit} —— 阶段 4 产出的纯 HDR 自发光增量，直接合流进 6 级泛光、变形星芒、体积光与 AgX 泄白。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisSceneTarget {
   private static final int[] DRAW_DUAL = new int[]{
      GL30.GL_COLOR_ATTACHMENT0,
      GL30.GL_COLOR_ATTACHMENT1
   };
   private static final int[] DRAW_SINGLE = new int[]{
      GL30.GL_COLOR_ATTACHMENT0
   };
   private static final float[] CLEAR_ZERO = new float[]{0.0F, 0.0F, 0.0F, 0.0F};

   private int fbo;
   private int width;
   private int height;
   private int colorTex;
   private int emitTex;

   public boolean ensure(int w, int h) {
      w = Math.max(1, w);
      h = Math.max(1, h);
      if (this.fbo != 0 && this.width == w && this.height == h) {
         return false;
      }
      RenderSystem.assertOnRenderThread();
      this.destroy();

      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevPbo = StellarisGlGuard.unbindPixelUnpackBuffer();
      try {
         this.width = w;
         this.height = h;
         this.colorTex = createLinearRgba16f(w, h);
         this.emitTex = createLinearRgba16f(w, h);

         this.fbo = GlStateManager.glGenFramebuffers();
         GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
         GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.colorTex, 0);
         GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, this.emitTex, 0);
         GL20.glDrawBuffers(DRAW_DUAL);

         int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete StellarisSceneTarget framebuffer: 0x" + Integer.toHexString(status));
         }
         GL30.glClearBufferfv(GL11.GL_COLOR, 0, CLEAR_ZERO);
         GL30.glClearBufferfv(GL11.GL_COLOR, 1, CLEAR_ZERO);
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

   private static int createLinearRgba16f(int w, int h) {
      int tex = GlStateManager._genTexture();
      GlStateManager._bindTexture(tex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      return tex;
   }

   /** 绑定为单输出（仅写 Attachment 0，用于阶段 1 线性摄入或两阶段预合成）。 */
   public void bindWriteSingle() {
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
      GL20.glDrawBuffers(DRAW_SINGLE);
      GlStateManager._viewport(0, 0, this.width, this.height);
   }

   /** 绑定为双输出（同步写入 Attachment 0 改写场景与 Attachment 1 纯 HDR 自发光）。 */
   public void bindWriteDual() {
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
      GL20.glDrawBuffers(DRAW_DUAL);
      GlStateManager._viewport(0, 0, this.width, this.height);
   }

   /** 仅清零 Attachment 1 自发光通道（单输出摄入首帧前使用）。 */
   public void clearEmitOnly() {
      if (this.fbo == 0) {
         return;
      }
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fbo);
      GL20.glDrawBuffers(DRAW_DUAL);
      GL30.glClearBufferfv(GL11.GL_COLOR, 1, CLEAR_ZERO);
   }

   public int colorTexture() {
      return this.colorTex;
   }

   public int emitTexture() {
      return this.emitTex;
   }

   public int width() {
      return Math.max(1, this.width);
   }

   public int height() {
      return Math.max(1, this.height);
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
      if (this.emitTex != 0) {
         RenderSystem.assertOnRenderThread();
         GlStateManager._deleteTexture(this.emitTex);
         this.emitTex = 0;
      }
      this.width = 0;
      this.height = 0;
   }
}
