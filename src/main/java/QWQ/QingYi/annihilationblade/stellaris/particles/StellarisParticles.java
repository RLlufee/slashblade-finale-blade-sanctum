package QWQ.QingYi.annihilationblade.stellaris.particles;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisGlGuard;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisShaders;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/**
 * 纯客户端、零 GC 的万级 GPU 实例化天体物理粒子池（Step 1.6 升级版：上限 16,384 并发）。
 * <p>
 * <ul>
 *   <li>优先走 OpenGL 3.2 Core {@code #version 150} {@code samplerBuffer} TBO + {@code glDrawArraysInstanced}：
 *       单次 DrawCall 完成 5 种程序化粒子形态（星核/棱晶碎钻/六边形焦外散景/速度流线/贴地余烬）、完整 float32 线性 HDR 辐射度与 3D 浮雕法阵穿孔遮挡；</li>
 *   <li>老驱动不支持 TBO 时无缝回退至 {@code st_particle} {@code Tesselator} 路径，保持 100% 向后兼容。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisParticles {
   public static final int HARD_CAPACITY = 16384;
   private static final int FLOATS_PER_INSTANCE = 12;

   public enum Operator {
      GRAVITY_PULL(0),
      KEPLER_VORTEX(1),
      ZERO_G_DRIFT(2);

      final byte id;

      Operator(int id) {
         this.id = (byte)id;
      }
   }

   public enum Shape {
      STAR_CORE(0),
      DIAMOND_CRYSTAL(1),
      HEX_BOKEH(2),
      VELOCITY_STREAK(3),
      GROUND_EMBER(4);

      final byte id;

      Shape(int id) {
         this.id = (byte)id;
      }
   }

   private final double[] px = new double[HARD_CAPACITY];
   private final double[] py = new double[HARD_CAPACITY];
   private final double[] pz = new double[HARD_CAPACITY];
   private final double[] prevX = new double[HARD_CAPACITY];
   private final double[] prevY = new double[HARD_CAPACITY];
   private final double[] prevZ = new double[HARD_CAPACITY];
   private final float[] vx = new float[HARD_CAPACITY];
   private final float[] vy = new float[HARD_CAPACITY];
   private final float[] vz = new float[HARD_CAPACITY];

   private final double[] cx = new double[HARD_CAPACITY];
   private final double[] cy = new double[HARD_CAPACITY];
   private final double[] cz = new double[HARD_CAPACITY];
   private final float[] ax = new float[HARD_CAPACITY];
   private final float[] ay = new float[HARD_CAPACITY];
   private final float[] az = new float[HARD_CAPACITY];
   private final float[] fieldStrength = new float[HARD_CAPACITY];

   private final float[] size = new float[HARD_CAPACITY];
   private final float[] stretch = new float[HARD_CAPACITY];
   private final float[] r = new float[HARD_CAPACITY];
   private final float[] g = new float[HARD_CAPACITY];
   private final float[] b = new float[HARD_CAPACITY];
   private final float[] emission = new float[HARD_CAPACITY];
   private final int[] age = new int[HARD_CAPACITY];
   private final int[] maxAge = new int[HARD_CAPACITY];
   private final byte[] operator = new byte[HARD_CAPACITY];
   private final byte[] shape = new byte[HARD_CAPACITY];
   private final float[] seed = new float[HARD_CAPACITY];

   private final FloatBuffer tboUpload = ByteBuffer.allocateDirect(HARD_CAPACITY * FLOATS_PER_INSTANCE * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer();
   private final float[] matScratch = new float[16];

   private int count;
   private int qualityLimit = 8192;
   private int seedCounter;

   public void setQualityLimit(int maxParticles) {
      this.qualityLimit = Math.max(64, Math.min(HARD_CAPACITY, maxParticles));
      if (this.count > this.qualityLimit) {
         this.count = this.qualityLimit;
      }
   }

   public int activeCount() {
      return this.count;
   }

   public boolean isEmpty() {
      return this.count <= 0;
   }

   public void clear() {
      this.count = 0;
   }

   /** 兼容旧签名的粒子发射方法（自动按 stretchFactor 选用流线或星核形态）。 */
   public void spawn(
      double x, double y, double z,
      float vx, float vy, float vz,
      double centerX, double centerY, double centerZ,
      float axisX, float axisY, float axisZ,
      float strength,
      float radius, float stretchFactor,
      int rgb, float hdrEmission,
      int lifetimeTicks, Operator op
   ) {
      Shape autoShape = stretchFactor > 0.75F ? Shape.VELOCITY_STREAK : Shape.STAR_CORE;
      this.spawn(
         x, y, z, vx, vy, vz,
         centerX, centerY, centerZ, axisX, axisY, axisZ,
         strength, radius, stretchFactor,
         rgb, hdrEmission, lifetimeTicks, op, autoShape
      );
   }

   /** 完整 5 形态粒子发射接口（星核 / 菱晶碎钻 / 六边形散景 / 速度流线 / 贴地余烬）。 */
   public void spawn(
      double x, double y, double z,
      float vx, float vy, float vz,
      double centerX, double centerY, double centerZ,
      float axisX, float axisY, float axisZ,
      float strength,
      float radius, float stretchFactor,
      int rgb, float hdrEmission,
      int lifetimeTicks, Operator op, Shape particleShape
   ) {
      if (this.count >= this.qualityLimit || lifetimeTicks <= 0) {
         return;
      }
      int i = this.count++;
      this.px[i] = this.prevX[i] = x;
      this.py[i] = this.prevY[i] = y;
      this.pz[i] = this.prevZ[i] = z;
      this.vx[i] = vx;
      this.vy[i] = vy;
      this.vz[i] = vz;
      this.cx[i] = centerX;
      this.cy[i] = centerY;
      this.cz[i] = centerZ;

      float alen = (float)Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
      if (alen < 1.0E-5F) {
         this.ax[i] = 0.0F;
         this.ay[i] = 1.0F;
         this.az[i] = 0.0F;
      } else {
         this.ax[i] = axisX / alen;
         this.ay[i] = axisY / alen;
         this.az[i] = axisZ / alen;
      }

      this.fieldStrength[i] = strength;
      this.size[i] = Math.max(0.015F, radius);
      this.stretch[i] = Math.max(0.0F, Math.min(10.0F, stretchFactor));
      this.r[i] = ((rgb >> 16) & 0xFF) / 255.0F;
      this.g[i] = ((rgb >> 8) & 0xFF) / 255.0F;
      this.b[i] = (rgb & 0xFF) / 255.0F;
      this.emission[i] = Math.max(0.2F, Math.min(24.0F, hdrEmission));
      this.age[i] = 0;
      this.maxAge[i] = lifetimeTicks;
      this.operator[i] = op.id;
      this.shape[i] = particleShape == null ? Shape.STAR_CORE.id : particleShape.id;
      this.seedCounter = this.seedCounter * 1664525 + 1013904223;
      this.seed[i] = ((this.seedCounter >>> 8) & 0xFFFF) / 65536.0F * 0.98F;
   }

   public void tick() {
      int i = 0;
      while (i < this.count) {
         int nextAge = ++this.age[i];
         if (nextAge >= this.maxAge[i]) {
            this.removeAt(i);
            continue;
         }
         this.prevX[i] = this.px[i];
         this.prevY[i] = this.py[i];
         this.prevZ[i] = this.pz[i];

         byte op = this.operator[i];
         float k = this.fieldStrength[i];
         if (op == Operator.GRAVITY_PULL.id) {
            float dx = (float)(this.cx[i] - this.px[i]);
            float dy = (float)(this.cy[i] - this.py[i]);
            float dz = (float)(this.cz[i] - this.pz[i]);
            float d2 = dx * dx + dy * dy + dz * dz + 0.35F;
            float invD = 1.0F / (float)Math.sqrt(d2);
            float accel = k / d2;
            this.vx[i] = this.vx[i] * 0.94F + dx * invD * accel;
            this.vy[i] = this.vy[i] * 0.94F + dy * invD * accel;
            this.vz[i] = this.vz[i] * 0.94F + dz * invD * accel;
         } else if (op == Operator.KEPLER_VORTEX.id) {
            float rx = (float)(this.px[i] - this.cx[i]);
            float ry = (float)(this.py[i] - this.cy[i]);
            float rz = (float)(this.pz[i] - this.cz[i]);
            float axv = this.ax[i];
            float ayv = this.ay[i];
            float azv = this.az[i];
            float h = rx * axv + ry * ayv + rz * azv;
            float pxv = rx - axv * h;
            float pyv = ry - ayv * h;
            float pzv = rz - azv * h;
            float rad = (float)Math.sqrt(pxv * pxv + pyv * pyv + pzv * pzv) + 0.25F;
            float invR = 1.0F / rad;
            float rux = pxv * invR;
            float ruy = pyv * invR;
            float ruz = pzv * invR;
            float tx = ayv * ruz - azv * ruy;
            float ty = azv * rux - axv * ruz;
            float tz = axv * ruy - ayv * rux;
            float speed = k / (float)Math.sqrt(rad);
            float inward = Math.min(speed * 0.45F, (speed * speed) / (rad * 1.45F) + 0.012F * k);
            float tvx = tx * speed - rux * inward - axv * h * 0.08F;
            float tvy = ty * speed - ruy * inward - ayv * h * 0.08F;
            float tvz = tz * speed - ruz * inward - azv * h * 0.08F;
            this.vx[i] = this.vx[i] * 0.72F + tvx * 0.28F;
            this.vy[i] = this.vy[i] * 0.72F + tvy * 0.28F;
            this.vz[i] = this.vz[i] * 0.72F + tvz * 0.28F;
         } else {
            this.vx[i] *= 0.965F;
            this.vy[i] = this.vy[i] * 0.965F + 0.004F * k;
            this.vz[i] *= 0.965F;
         }

         this.px[i] += this.vx[i];
         this.py[i] += this.vy[i];
         this.pz[i] += this.vz[i];
         i++;
      }
   }

   private void removeAt(int idx) {
      int last = --this.count;
      if (idx == last) {
         return;
      }
      this.px[idx] = this.px[last];
      this.py[idx] = this.py[last];
      this.pz[idx] = this.pz[last];
      this.prevX[idx] = this.prevX[last];
      this.prevY[idx] = this.prevY[last];
      this.prevZ[idx] = this.prevZ[last];
      this.vx[idx] = this.vx[last];
      this.vy[idx] = this.vy[last];
      this.vz[idx] = this.vz[last];
      this.cx[idx] = this.cx[last];
      this.cy[idx] = this.cy[last];
      this.cz[idx] = this.cz[last];
      this.ax[idx] = this.ax[last];
      this.ay[idx] = this.ay[last];
      this.az[idx] = this.az[last];
      this.fieldStrength[idx] = this.fieldStrength[last];
      this.size[idx] = this.size[last];
      this.stretch[idx] = this.stretch[last];
      this.r[idx] = this.r[last];
      this.g[idx] = this.g[last];
      this.b[idx] = this.b[last];
      this.emission[idx] = this.emission[last];
      this.age[idx] = this.age[last];
      this.maxAge[idx] = this.maxAge[last];
      this.operator[idx] = this.operator[last];
      this.shape[idx] = this.shape[last];
      this.seed[idx] = this.seed[last];
   }

   public int render(FxFrame frame, int depthTexture) {
      return this.render(frame, depthTexture, 0, 1.0F, 1.0F, false);
   }

   /** 优先使用 OpenGL 3.2 TBO 实例化渲染万级多形态粒子，若驱动不支持自动无缝降级到 Tesselator 路径。 */
   public int render(FxFrame frame, int depthTexture, int reliefMetaTex, float reliefUvMaxX, float reliefUvMaxY, boolean hasRelief) {
      if (this.count <= 0) {
         return 0;
      }
      if (StellarisTboShaders.INSTANCE.ensureReady()) {
         return this.renderTboInstanced(frame, depthTexture, reliefMetaTex, reliefUvMaxX, reliefUvMaxY, hasRelief);
      }
      return this.renderFallbackTesselator(frame, depthTexture, reliefMetaTex, reliefUvMaxX, reliefUvMaxY, hasRelief);
   }

   private int renderTboInstanced(FxFrame frame, int depthTexture, int reliefMetaTex, float reliefUvMaxX, float reliefUvMaxY, boolean hasRelief) {
      float pt = frame.partialTick;
      double camX = frame.camera.x;
      double camY = frame.camera.y;
      double camZ = frame.camera.z;

      this.tboUpload.clear();
      int drawn = 0;
      for (int i = 0; i < this.count; i++) {
         float x = (float)(this.prevX[i] + (this.px[i] - this.prevX[i]) * pt - camX);
         float y = (float)(this.prevY[i] + (this.py[i] - this.prevY[i]) * pt - camY);
         float z = (float)(this.prevZ[i] + (this.pz[i] - this.prevZ[i]) * pt - camZ);

         float distSq = x * x + y * y + z * z;
         if (distSq > 220.0F * 220.0F || distSq < 0.04F) {
            continue;
         }

         float lifeT = (this.age[i] + pt) / Math.max(1.0F, (float)this.maxAge[i]);
         if (lifeT >= 1.0F) {
            continue;
         }
         float fadeIn = Math.min(1.0F, lifeT * 7.5F);
         float fadeOut = 1.0F - Math.max(0.0F, (lifeT - 0.58F) / 0.42F);
         float alpha = fadeIn * fadeOut * fadeOut;
         if (alpha <= 0.002F) {
            continue;
         }

         float rad = this.size[i] * (0.72F + 0.28F * fadeOut);
         float energy = this.emission[i] * alpha;
         float linR = srgbToLinear(this.r[i]) * energy;
         float linG = srgbToLinear(this.g[i]) * energy;
         float linB = srgbToLinear(this.b[i]) * energy;
         float packedMeta = (float)(this.shape[i] & 0xFF) + this.seed[i];

         this.tboUpload.put(x).put(y).put(z).put(rad);
         this.tboUpload.put(this.vx[i]).put(this.vy[i]).put(this.vz[i]).put(this.stretch[i]);
         this.tboUpload.put(linR).put(linG).put(linB).put(packedMeta);
         drawn++;
      }

      if (drawn <= 0) {
         return 0;
      }

      this.tboUpload.flip();
      StellarisTboShaders tbo = StellarisTboShaders.INSTANCE;
      tbo.uploadAndBindTbo(false, this.tboUpload);

      GL20.glUseProgram(tbo.particleProgram());
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + StellarisGlGuard.RELIEF_META_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, hasRelief ? reliefMetaTex : depthTexture);

      if (tbo.pLocData >= 0) GL20.glUniform1i(tbo.pLocData, StellarisGlGuard.TBO_UNIT);
      if (tbo.pLocDepth >= 0) GL20.glUniform1i(tbo.pLocDepth, 0);
      if (tbo.pLocRelief >= 0) GL20.glUniform1i(tbo.pLocRelief, StellarisGlGuard.RELIEF_META_UNIT);
      if (tbo.pLocViewProj >= 0) {
         frame.viewProjection.get(this.matScratch);
         GL20.glUniformMatrix4fv(tbo.pLocViewProj, false, this.matScratch);
      }
      if (tbo.pLocInvProj >= 0) {
         frame.inverseProjection.get(this.matScratch);
         GL20.glUniformMatrix4fv(tbo.pLocInvProj, false, this.matScratch);
      }
      if (tbo.pLocCamLeft >= 0) GL20.glUniform3f(tbo.pLocCamLeft, frame.left.x, frame.left.y, frame.left.z);
      if (tbo.pLocCamUp >= 0) GL20.glUniform3f(tbo.pLocCamUp, frame.up.x, frame.up.y, frame.up.z);
      if (tbo.pLocTime >= 0) GL20.glUniform1f(tbo.pLocTime, frame.time);
      if (tbo.pLocOutSize >= 0) GL20.glUniform2f(tbo.pLocOutSize, (float)frame.width, (float)frame.height);
      if (tbo.pLocSoftness >= 0) GL20.glUniform1f(tbo.pLocSoftness, 0.45F);
      if (tbo.pLocReliefParams >= 0) GL20.glUniform4f(tbo.pLocReliefParams, reliefUvMaxX, reliefUvMaxY, hasRelief ? 1.0F : 0.0F, 0.0F);

      GL30.glBindVertexArray(tbo.emptyVao());
      GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, drawn);
      GL30.glBindVertexArray(0);
      GL20.glUseProgram(0);
      StellarisGlGuard.syncActiveTexture(GL13.GL_TEXTURE0);
      return drawn;
   }

   private int renderFallbackTesselator(FxFrame frame, int depthTexture, int reliefMetaTex, float reliefUvMaxX, float reliefUvMaxY, boolean hasRelief) {
      ShaderInstance shader = StellarisShaders.particle;
      if (shader == null) {
         return 0;
      }

      RenderSystem.setShaderTexture(0, depthTexture);
      RenderSystem.setShaderTexture(1, hasRelief ? reliefMetaTex : depthTexture);
      shader.safeGetUniform("InvProj").set(frame.inverseProjection);
      shader.safeGetUniform("OutSize").set((float)frame.width, (float)frame.height);
      shader.safeGetUniform("Softness").set(0.45F);
      shader.safeGetUniform("ReliefParams").set(reliefUvMaxX, reliefUvMaxY, hasRelief ? 1.0F : 0.0F, 0.0F);
      RenderSystem.setShader(() -> shader);

      float pt = frame.partialTick;
      double camX = frame.camera.x;
      double camY = frame.camera.y;
      double camZ = frame.camera.z;
      Vector3f camLeft = frame.left;
      Vector3f camUp = frame.up;

      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      int drawn = 0;

      try {
         for (int i = 0; i < this.count; i++) {
            float x = (float)(this.prevX[i] + (this.px[i] - this.prevX[i]) * pt - camX);
            float y = (float)(this.prevY[i] + (this.py[i] - this.prevY[i]) * pt - camY);
            float z = (float)(this.prevZ[i] + (this.pz[i] - this.prevZ[i]) * pt - camZ);

            float distSq = x * x + y * y + z * z;
            if (distSq > 220.0F * 220.0F || distSq < 0.04F) {
               continue;
            }

            float lifeT = (this.age[i] + pt) / Math.max(1.0F, (float)this.maxAge[i]);
            if (lifeT >= 1.0F) {
               continue;
            }
            float fadeIn = Math.min(1.0F, lifeT * 7.5F);
            float fadeOut = 1.0F - Math.max(0.0F, (lifeT - 0.58F) / 0.42F);
            float alpha = fadeIn * fadeOut * fadeOut;
            if (alpha <= 0.002F) {
               continue;
            }

            float s = this.size[i] * (0.72F + 0.28F * fadeOut);
            float stretchAmt = this.stretch[i];

            float normEnergy = Math.max(0.0F, Math.min(1.0F, (this.emission[i] * alpha) / 18.0F));
            float packedAlpha = Math.max(0.004F, (float)Math.sqrt(normEnergy));
            float cr = this.r[i];
            float cg = this.g[i];
            float cb = this.b[i];

            float ux;
            float uy;
            float uz;
            float vxAxis;
            float vyAxis;
            float vzAxis;

            float spdSq = this.vx[i] * this.vx[i] + this.vy[i] * this.vy[i] + this.vz[i] * this.vz[i];
            if (stretchAmt > 0.05F && spdSq > 1.0E-5F) {
               float spd = (float)Math.sqrt(spdSq);
               float dirX = this.vx[i] / spd;
               float dirY = this.vy[i] / spd;
               float dirZ = this.vz[i] / spd;

               float sideX = y * dirZ - z * dirY;
               float sideY = z * dirX - x * dirZ;
               float sideZ = x * dirY - y * dirX;
               float sideLen = (float)Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
               if (sideLen > 1.0E-4F) {
                  float halfLen = s * (1.0F + Math.min(6.0F, spd * 6.5F * stretchAmt));
                  float halfW = s * 0.55F;
                  ux = dirX * halfLen;
                  uy = dirY * halfLen;
                  uz = dirZ * halfLen;
                  vxAxis = (sideX / sideLen) * halfW;
                  vyAxis = (sideY / sideLen) * halfW;
                  vzAxis = (sideZ / sideLen) * halfW;
               } else {
                  ux = camLeft.x * s;
                  uy = camLeft.y * s;
                  uz = camLeft.z * s;
                  vxAxis = camUp.x * s;
                  vyAxis = camUp.y * s;
                  vzAxis = camUp.z * s;
               }
            } else {
               ux = camLeft.x * s;
               uy = camLeft.y * s;
               uz = camLeft.z * s;
               vxAxis = camUp.x * s;
               vyAxis = camUp.y * s;
               vzAxis = camUp.z * s;
            }

            if (drawn == 0) {
               builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            }
            builder.vertex(x - ux - vxAxis, y - uy - vyAxis, z - uz - vzAxis).color(cr, cg, cb, packedAlpha).uv(-1.0F, -1.0F).endVertex();
            builder.vertex(x + ux - vxAxis, y + uy - vyAxis, z + uz - vzAxis).color(cr, cg, cb, packedAlpha).uv(1.0F, -1.0F).endVertex();
            builder.vertex(x + ux + vxAxis, y + uy + vyAxis, z + uz + vzAxis).color(cr, cg, cb, packedAlpha).uv(1.0F, 1.0F).endVertex();
            builder.vertex(x - ux + vxAxis, y - uy + vyAxis, z - uz + vzAxis).color(cr, cg, cb, packedAlpha).uv(-1.0F, 1.0F).endVertex();
            drawn++;
         }

         if (drawn > 0) {
            BufferUploader.drawWithShader(builder.end());
         }
      } finally {
         if (builder.building()) {
            BufferBuilder.RenderedBuffer stray = builder.endOrDiscardIfEmpty();
            if (stray != null) {
               stray.release();
            }
         }
      }
      return drawn;
   }

   private static float srgbToLinear(float c) {
      if (c <= 0.04045F) {
         return c / 12.92F;
      }
      return (float)Math.pow((c + 0.055) / 1.055, 2.4);
   }
}
