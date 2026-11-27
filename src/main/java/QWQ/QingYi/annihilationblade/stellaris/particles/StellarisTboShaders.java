package QWQ.QingYi.annihilationblade.stellaris.particles;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisGlGuard;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GLCapabilities;

/**
 * 星穹引擎 OpenGL 3.2 Core {@code #version 150} TBO 实例化渲染基础设施（Step 1.6）。
 * <p>
 * 负责编译与热重载（F3+T）{@code tbo_particle} 与 {@code tbo_ribbon} 原生 GLSL 程序，
 * 管理空 VAO、动态流式 VBO 与 {@code GL_TEXTURE_BUFFER (RGBA32F)} 缓冲纹理；
 * 若老驱动不支持 TBO 或编译失败则平滑降级回旧 {@code st_particle} 路径，绝不崩溃。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisTboShaders {
   public static final StellarisTboShaders INSTANCE = new StellarisTboShaders();

   private static final ResourceLocation PARTICLE_VSH = ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "shaders/stellaris/tbo_particle.vsh");
   private static final ResourceLocation PARTICLE_FSH = ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "shaders/stellaris/tbo_particle.fsh");
   private static final ResourceLocation RIBBON_VSH = ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "shaders/stellaris/tbo_ribbon.vsh");
   private static final ResourceLocation RIBBON_FSH = ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "shaders/stellaris/tbo_ribbon.fsh");

   private String particleVertSrc;
   private String particleFragSrc;
   private String ribbonVertSrc;
   private String ribbonFragSrc;
   private boolean sourcesDirty;

   private boolean compiled;
   private boolean supported;
   private boolean failed;
   private String diagnostic = "pending";

   private int particleProgram;
   private int ribbonProgram;
   private int emptyVao;
   private int particleVbo;
   private int particleTboTex;
   private int ribbonVbo;
   private int ribbonTboTex;

   // 缓存常用 uniform 位置，避免每帧 glGetUniformLocation 字符串查找
   int pLocData = -1, pLocDepth = -1, pLocRelief = -1, pLocViewProj = -1, pLocInvProj = -1;
   int pLocCamLeft = -1, pLocCamUp = -1, pLocTime = -1, pLocOutSize = -1, pLocSoftness = -1, pLocReliefParams = -1;

   int rLocData = -1, rLocDepth = -1, rLocRelief = -1, rLocViewProj = -1, rLocInvProj = -1;
   int rLocCamUp = -1, rLocTime = -1, rLocOutSize = -1, rLocSoftness = -1, rLocReliefParams = -1;

   private StellarisTboShaders() {
   }

   /** 在 {@code RegisterShadersEvent}（启动与每次 F3+T 资源重载）中读取最新 GLSL 源码。 */
   public void onResourceReload(ResourceProvider provider) {
      try {
         this.particleVertSrc = readText(provider, PARTICLE_VSH);
         this.particleFragSrc = readText(provider, PARTICLE_FSH);
         this.ribbonVertSrc = readText(provider, RIBBON_VSH);
         this.ribbonFragSrc = readText(provider, RIBBON_FSH);
         this.sourcesDirty = true;
         this.failed = false;
         this.diagnostic = "loaded_sources";
         if (RenderSystem.isOnRenderThread()) {
            this.ensureReady();
         }
      } catch (Throwable throwable) {
         this.failed = true;
         this.diagnostic = "io_fail:" + throwable.getClass().getSimpleName();
         Annihilationblade.LOGGER.warn("[Stellaris TBO] Failed to read TBO shader sources; falling back to Tesselator.", throwable);
      }
   }

   private static String readText(ResourceProvider provider, ResourceLocation id) throws IOException {
      Optional<Resource> opt = provider.getResource(id);
      if (opt.isEmpty()) {
         throw new IOException("Missing shader resource: " + id);
      }
      StringBuilder sb = new StringBuilder(2048);
      try (BufferedReader reader = opt.get().openAsReader()) {
         String line;
         while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
         }
      }
      return sb.toString();
   }

   /** 确保原生 TBO 程序与缓冲对象在渲染线程就绪。 */
   public boolean ensureReady() {
      if (this.failed) {
         return false;
      }
      if (this.compiled && !this.sourcesDirty) {
         return this.supported && this.particleProgram != 0 && this.ribbonProgram != 0;
      }
      if (this.particleVertSrc == null || this.particleFragSrc == null || this.ribbonVertSrc == null || this.ribbonFragSrc == null) {
         return false;
      }
      RenderSystem.assertOnRenderThread();
      this.destroyGlObjects();

      GLCapabilities caps = GL.getCapabilities();
      this.supported = (caps.OpenGL32 || (caps.OpenGL31 && caps.GL_ARB_texture_buffer_object));
      if (!this.supported) {
         this.compiled = true;
         this.sourcesDirty = false;
         this.diagnostic = "unsupported_gl";
         Annihilationblade.LOGGER.info("[Stellaris TBO] Driver lacks OpenGL 3.1/3.2 TBO; using Tesselator fallback.");
         return false;
      }

      try {
         this.particleProgram = compileAndLink("tbo_particle", this.particleVertSrc, this.particleFragSrc);
         this.ribbonProgram = compileAndLink("tbo_ribbon", this.ribbonVertSrc, this.ribbonFragSrc);
         this.cacheUniformLocations();

         this.emptyVao = GL30.glGenVertexArrays();
         this.particleVbo = GL15.glGenBuffers();
         this.particleTboTex = GL11.glGenTextures();
         this.ribbonVbo = GL15.glGenBuffers();
         this.ribbonTboTex = GL11.glGenTextures();

         // 预初始化最小有效缓冲并关联至 RGBA32F GL_TEXTURE_BUFFER
         int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + StellarisGlGuard.TBO_UNIT);
         int prevTboTex = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
         try {
            initTboPair(this.particleVbo, this.particleTboTex, 12 * 4);
            initTboPair(this.ribbonVbo, this.ribbonTboTex, 16 * 4);
         } finally {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, prevTboTex);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
            StellarisGlGuard.syncActiveTexture(prevActive);
         }

         this.compiled = true;
         this.sourcesDirty = false;
         this.diagnostic = "OK(GL32 TBO RGBA32F)";
         Annihilationblade.LOGGER.info("[Stellaris TBO] OpenGL 3.2 GPU-instanced particle & 3D ribbon TBO pipeline ready.");
         return true;
      } catch (Throwable throwable) {
         this.destroyGlObjects();
         this.failed = true;
         this.compiled = true;
         this.sourcesDirty = false;
         this.diagnostic = "compile_fail";
         Annihilationblade.LOGGER.error("[Stellaris TBO] Failed to compile/link TBO shaders; falling back to Tesselator.", throwable);
         return false;
      }
   }

   private static void initTboPair(int vbo, int tex, int initBytes) {
      GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, vbo);
      GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, initBytes, GL15.GL_STREAM_DRAW);
      GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
      GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, vbo);
   }

   private static int compileAndLink(String label, String vertSrc, String fragSrc) {
      int vs = compileStage(label + ".vsh", GL20.GL_VERTEX_SHADER, vertSrc);
      int fs = 0;
      int prog = 0;
      try {
         fs = compileStage(label + ".fsh", GL20.GL_FRAGMENT_SHADER, fragSrc);
         prog = GL20.glCreateProgram();
         GL20.glAttachShader(prog, vs);
         GL20.glAttachShader(prog, fs);
         GL30.glBindFragDataLocation(prog, 0, "fragColor");
         GL20.glLinkProgram(prog);
         if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(prog, 4096);
            throw new IllegalStateException("Link error in " + label + ": " + log);
         }
         return prog;
      } catch (Throwable t) {
         if (prog != 0) {
            GL20.glDeleteProgram(prog);
         }
         throw t;
      } finally {
         if (vs != 0) {
            GL20.glDeleteShader(vs);
         }
         if (fs != 0) {
            GL20.glDeleteShader(fs);
         }
      }
   }

   private static int compileStage(String name, int type, String src) {
      int id = GL20.glCreateShader(type);
      GL20.glShaderSource(id, src);
      GL20.glCompileShader(id);
      if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
         String log = GL20.glGetShaderInfoLog(id, 4096);
         GL20.glDeleteShader(id);
         throw new IllegalStateException("Compile error in " + name + ": " + log);
      }
      return id;
   }

   private void cacheUniformLocations() {
      this.pLocData = GL20.glGetUniformLocation(this.particleProgram, "ParticleData");
      this.pLocDepth = GL20.glGetUniformLocation(this.particleProgram, "DepthTex");
      this.pLocRelief = GL20.glGetUniformLocation(this.particleProgram, "ReliefMetaTex");
      this.pLocViewProj = GL20.glGetUniformLocation(this.particleProgram, "ViewProj");
      this.pLocInvProj = GL20.glGetUniformLocation(this.particleProgram, "InvProj");
      this.pLocCamLeft = GL20.glGetUniformLocation(this.particleProgram, "CamLeft");
      this.pLocCamUp = GL20.glGetUniformLocation(this.particleProgram, "CamUp");
      this.pLocTime = GL20.glGetUniformLocation(this.particleProgram, "StTime");
      this.pLocOutSize = GL20.glGetUniformLocation(this.particleProgram, "OutSize");
      this.pLocSoftness = GL20.glGetUniformLocation(this.particleProgram, "Softness");
      this.pLocReliefParams = GL20.glGetUniformLocation(this.particleProgram, "ReliefParams");

      this.rLocData = GL20.glGetUniformLocation(this.ribbonProgram, "RibbonData");
      this.rLocDepth = GL20.glGetUniformLocation(this.ribbonProgram, "DepthTex");
      this.rLocRelief = GL20.glGetUniformLocation(this.ribbonProgram, "ReliefMetaTex");
      this.rLocViewProj = GL20.glGetUniformLocation(this.ribbonProgram, "ViewProj");
      this.rLocInvProj = GL20.glGetUniformLocation(this.ribbonProgram, "InvProj");
      this.rLocCamUp = GL20.glGetUniformLocation(this.ribbonProgram, "CamUp");
      this.rLocTime = GL20.glGetUniformLocation(this.ribbonProgram, "StTime");
      this.rLocOutSize = GL20.glGetUniformLocation(this.ribbonProgram, "OutSize");
      this.rLocSoftness = GL20.glGetUniformLocation(this.ribbonProgram, "Softness");
      this.rLocReliefParams = GL20.glGetUniformLocation(this.ribbonProgram, "ReliefParams");
   }

   /** 采用 Buffer Orphaning 流式上传粒子或丝带数据至 TBO，彻底避免 CPU/GPU 同步等待。 */
   public void uploadAndBindTbo(boolean isRibbon, FloatBuffer data) {
      int vbo = isRibbon ? this.ribbonVbo : this.particleVbo;
      int tex = isRibbon ? this.ribbonTboTex : this.particleTboTex;
      GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, vbo);
      GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long)data.remaining() * 4L, GL15.GL_STREAM_DRAW);
      GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, data);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + StellarisGlGuard.TBO_UNIT);
      GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
      GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, vbo);
      GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
   }

   public int particleProgram() {
      return this.particleProgram;
   }

   public int ribbonProgram() {
      return this.ribbonProgram;
   }

   public int emptyVao() {
      return this.emptyVao;
   }

   public boolean isReady() {
      return this.compiled && this.supported && !this.failed && this.particleProgram != 0 && this.ribbonProgram != 0;
   }

   public String diagnostic() {
      return this.diagnostic;
   }

   public void destroyGlObjects() {
      if (this.particleProgram != 0) {
         GL20.glDeleteProgram(this.particleProgram);
         this.particleProgram = 0;
      }
      if (this.ribbonProgram != 0) {
         GL20.glDeleteProgram(this.ribbonProgram);
         this.ribbonProgram = 0;
      }
      if (this.emptyVao != 0) {
         GL30.glDeleteVertexArrays(this.emptyVao);
         this.emptyVao = 0;
      }
      if (this.particleVbo != 0) {
         GL15.glDeleteBuffers(this.particleVbo);
         this.particleVbo = 0;
      }
      if (this.particleTboTex != 0) {
         GL11.glDeleteTextures(this.particleTboTex);
         this.particleTboTex = 0;
      }
      if (this.ribbonVbo != 0) {
         GL15.glDeleteBuffers(this.ribbonVbo);
         this.ribbonVbo = 0;
      }
      if (this.ribbonTboTex != 0) {
         GL11.glDeleteTextures(this.ribbonTboTex);
         this.ribbonTboTex = 0;
      }
      this.compiled = false;
   }
}
