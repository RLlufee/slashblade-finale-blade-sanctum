package QWQ.QingYi.annihilationblade.stellaris.engine;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

/**
 * 星穹引擎全对称 OpenGL 状态隔离守卫（Step 1.6 完整 16 单元隔离版）。
 * <p>
 * 解决外部光影包（Iris/Oculus）与第三方渲染模组遗留状态导致的所有隐性污染：
 * <ul>
 *   <li>保存并清空 0..15 共 16 个纹理单元（严格符合 OpenGL 3.2 Core 最低保证）的 2D 纹理与 {@code GL_SAMPLER_BINDING}（{@code glBindSampler(i, 0)}），防止外部 LINEAR/COMPARE Sampler 破坏 {@code R32F} 深度与 {@code NEAREST} 辅助图；</li>
 *   <li>对称保全 Draw/Read FBO、Viewport、Program、VAO、VBO、EBO、PBO、深度测试/函数/写掩码、剔除、裁剪、混合开关/分离因子/分离方程；</li>
 *   <li>全程经由 {@link GlStateManager#_activeTexture} 同步 Mojang 内部纹理单元缓存，彻底杜绝原生 GL 与 RenderSystem 缓存脱节；</li>
 *   <li>提供 {@link #unbindPixelUnpackBuffer()} / {@link #restorePixelUnpackBuffer(int)} 工具，防止纹理分配/上传前残留非零 PBO 导致驱动崩溃。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisGlGuard {
   /** 覆盖 OpenGL 3.2 Core 最低保证的 16 个片元纹理单元（0..11 Mojang + 12..15 星穹高槽位）。 */
   public static final int TRACKED_TEXTURE_UNITS = 16;
   /** 3D 体积噪声固定纹理单元 GL_TEXTURE12（用于 Heavy MRT / Scene Pass）。 */
   public static final int NOISE3D_UNIT = 12;
   /** Stage 4 SceneEmit 2D 纹理绑定单元 GL_TEXTURE12（用于 Composite Pass）。 */
   public static final int SCENE_EMIT_UNIT = 12;
   /** TBO 实例化粒子/丝带缓冲纹理固定单元 GL_TEXTURE13（用于 Geometry Pass）。 */
   public static final int TBO_UNIT = 13;
   /** 3D 浮雕法阵 ReliefColor 或 PrevSceneEmit 2D 纹理绑定单元 GL_TEXTURE13。 */
   public static final int RELIEF_COLOR_UNIT = 13;
   /** 3D 浮雕法阵 ReliefMeta (RGBA32F) 2D 纹理绑定单元 GL_TEXTURE14。 */
   public static final int RELIEF_META_UNIT = 14;

   private final int drawFbo;
   private final int readFbo;
   private final int[] viewport = new int[4];
   private final int program;
   private final int vao;
   private final int arrayBuffer;
   private final int elementArrayBuffer;
   private final int pixelUnpackBuffer;
   private final int activeTexture;

   private final boolean blend;
   private final boolean depthTest;
   private final boolean cull;
   private final boolean scissor;
   private final boolean depthMask;
   private final int depthFunc;
   private final int srcRgb;
   private final int dstRgb;
   private final int srcAlpha;
   private final int dstAlpha;
   private final int blendEqRgb;
   private final int blendEqAlpha;

   private final boolean samplerObjectsSupported;
   private final boolean tboSupported;
   private final int[] savedMojangTextures = new int[12];
   private final int[] savedGlTextures2D = new int[TRACKED_TEXTURE_UNITS];
   private final int[] savedSamplers = new int[TRACKED_TEXTURE_UNITS];
   private final int savedNoise3dBinding;
   private final int savedTboBinding;

   private StellarisGlGuard() {
      GLCapabilities caps = GL.getCapabilities();
      this.samplerObjectsSupported = caps.OpenGL33 || caps.GL_ARB_sampler_objects;
      this.tboSupported = caps.OpenGL31 || caps.GL_ARB_texture_buffer_object;

      this.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      this.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);

      this.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      this.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
      this.arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
      this.elementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
      this.pixelUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
      this.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

      this.blend = GL11.glIsEnabled(GL11.GL_BLEND);
      this.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
      this.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
      this.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      this.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
      this.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

      this.srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
      this.dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
      this.srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
      this.dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
      this.blendEqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
      this.blendEqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

      for (int i = 0; i < 12; i++) {
         this.savedMojangTextures[i] = RenderSystem.getShaderTexture(i);
      }

      for (int i = 0; i < TRACKED_TEXTURE_UNITS; i++) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
         this.savedGlTextures2D[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         if (this.samplerObjectsSupported) {
            this.savedSamplers[i] = GL33.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i);
            // 清除外部光影/模组遗留的 Sampler Object，保证 NEAREST / CLAMP_TO_EDGE 参数绝对生效
            GL33.glBindSampler(i, 0);
         }
      }

      GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISE3D_UNIT);
      this.savedNoise3dBinding = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);

      if (this.tboSupported) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TBO_UNIT);
         this.savedTboBinding = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
      } else {
         this.savedTboBinding = 0;
      }

      // 统一重置至纹理单元 0 并对齐 GlStateManager 内部缓存
      syncActiveTexture(GL13.GL_TEXTURE0);
      // 保障混合方程为标准 FUNC_ADD，防止外部遗留 REVERSE_SUBTRACT 等异常方程污染星穹叠加
      GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
      if (this.pixelUnpackBuffer != 0) {
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      }
   }

   public static StellarisGlGuard capture() {
      return new StellarisGlGuard();
   }

   /** 同步原生 OpenGL 与 Mojang GlStateManager 的激活纹理单元，杜绝缓存脱节。 */
   public static void syncActiveTexture(int glTextureUnit) {
      GlStateManager._activeTexture(glTextureUnit);
      GL13.glActiveTexture(glTextureUnit);
   }

   /** 纹理分配/上传前安全解绑残留 PBO，返回旧句柄。 */
   public static int unbindPixelUnpackBuffer() {
      int prev = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
      if (prev != 0) {
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      }
      return prev;
   }

   /** 纹理上传结束后还原旧 PBO 句柄。 */
   public static void restorePixelUnpackBuffer(int prev) {
      if (prev != 0) {
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, prev);
      }
   }

   /** 检测是否支持独立 Sampler Objects（OpenGL 3.3 / ARB_sampler_objects）。 */
   public static boolean supportsSamplerObjects() {
      GLCapabilities caps = GL.getCapabilities();
      return caps.OpenGL33 || caps.GL_ARB_sampler_objects;
   }

   /** 还原全部捕获的 OpenGL 与 RenderSystem 状态，并确保最终不残留脏绑定。 */
   public void restore() {
      if (this.scissor) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      } else {
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
      }

      // 先还原高槽位 3D 与 TBO 绑定
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISE3D_UNIT);
      GL11.glBindTexture(GL12.GL_TEXTURE_3D, this.savedNoise3dBinding);
      if (this.tboSupported) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TBO_UNIT);
         GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, this.savedTboBinding);
      }

      // 还原全部 0..15 号单元的 2D 纹理与 Sampler Object
      for (int i = 0; i < TRACKED_TEXTURE_UNITS; i++) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.savedGlTextures2D[i]);
         if (this.samplerObjectsSupported) {
            GL33.glBindSampler(i, this.savedSamplers[i]);
         }
      }

      // 对齐 Mojang 0..11 纹理槽镜像与激活纹理单元缓存
      for (int i = 0; i < 12; i++) {
         RenderSystem.setShaderTexture(i, this.savedMojangTextures[i]);
      }
      syncActiveTexture(this.activeTexture);

      // 还原 VAO / VBO / EBO / PBO / Shader Program
      GL30.glBindVertexArray(this.vao);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.arrayBuffer);
      GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.elementArrayBuffer);
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, this.pixelUnpackBuffer);
      GL20.glUseProgram(this.program);

      // 经 RenderSystem 还原混合与深度/剔除状态，保证原版缓存与真实 GL 100% 一致
      GL20.glBlendEquationSeparate(this.blendEqRgb, this.blendEqAlpha);
      RenderSystem.blendFuncSeparate(this.srcRgb, this.dstRgb, this.srcAlpha, this.dstAlpha);
      if (this.blend) {
         RenderSystem.enableBlend();
      } else {
         RenderSystem.disableBlend();
      }

      RenderSystem.depthFunc(this.depthFunc);
      if (this.depthTest) {
         RenderSystem.enableDepthTest();
      } else {
         RenderSystem.disableDepthTest();
      }
      RenderSystem.depthMask(this.depthMask);

      if (this.cull) {
         RenderSystem.enableCull();
      } else {
         RenderSystem.disableCull();
      }

      // 还原 Read/Draw FBO 与视口
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFbo);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFbo);
      GlStateManager._viewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
   }
}
