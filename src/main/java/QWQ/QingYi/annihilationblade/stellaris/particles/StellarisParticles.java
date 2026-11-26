package QWQ.QingYi.annihilationblade.stellaris.particles;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisShaders;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * 纯客户端、零 GC 的 SoA 天体物理粒子池（目标 4096~8192 并发，服务端零发包）。
 * <p>
 * 顶点 Alpha 采用平方根非线性打包（暗部淡出精度提升 260 倍，零 8-bit 阶梯闪烁），
 * 开普勒旋涡算子补齐向心平衡防止内圈离心甩飞。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisParticles {
   public static final int HARD_CAPACITY = 8192;

   public enum Operator {
      GRAVITY_PULL(0),
      KEPLER_VORTEX(1),
      ZERO_G_DRIFT(2);

      final byte id;

      Operator(int id) {
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

   private int count;
   private int qualityLimit = 4096;

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
      this.emission[i] = Math.max(0.2F, Math.min(18.0F, hdrEmission));
      this.age[i] = 0;
      this.maxAge[i] = lifetimeTicks;
      this.operator[i] = op.id;
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
            // 补偿欧拉步长离心漂移（约 speed^2 / (2*rad)）+ 轻微向心旋入，保持内圈轨道紧致圆润
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
   }

   public int render(FxFrame frame, int depthTexture) {
      if (this.count <= 0) {
         return 0;
      }
      ShaderInstance shader = StellarisShaders.particle;
      if (shader == null) {
         return 0;
      }

      RenderSystem.setShaderTexture(0, depthTexture);
      shader.safeGetUniform("InvProj").set(frame.inverseProjection);
      shader.safeGetUniform("OutSize").set((float)frame.width, (float)frame.height);
      shader.safeGetUniform("Softness").set(0.45F);
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

            // 非线性平方根编码进 8-bit Alpha（片元平方还原），将暗部淡出精度提升 260 倍，彻底消灭阶梯跳灭
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

            // 惰性 begin：仅在真正存在可绘制粒子时才启动全局 BufferBuilder，杜绝空批次触发 MC 1.20.1 discard() 不清 building 标志导致的 Already building! 崩溃
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
}
