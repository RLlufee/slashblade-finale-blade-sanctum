package QWQ.QingYi.annihilationblade.stellaris.particles;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisGlGuard;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/**
 * 星穹引擎 GPU 实例化 3D 能量丝带/虹吸轨池（Step 1.6）。
 * <p>
 * 零 GC 直接打包每帧提交的 3D 折线段至 {@code FloatBuffer}，由 {@code tbo_ribbon.vsh/fsh}
 * 在 GPU 上一次 {@code glDrawArraysInstanced} 展开面向相机的立体双螺旋虹吸线、等离子丝带与高频电弧，
 * 并同步接入共享深度与 3D 物理浮雕法阵前后穿孔遮挡！
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisRibbons {
   public static final int MAX_SEGMENTS = 4096;
   private static final int FLOATS_PER_SEG = 16;

   public enum Style {
      SOLID_ENERGY(0),
      FLOWING_PULSE(1),
      LIGHTNING_FILAMENT(2),
      CELESTIAL_SIPHON(3);

      final int id;

      Style(int id) {
         this.id = id;
      }
   }

   private final FloatBuffer uploadBuffer = ByteBuffer.allocateDirect(MAX_SEGMENTS * FLOATS_PER_SEG * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer();
   private final float[] matScratch = new float[16];
   private int segmentCount;

   public void resetFrame() {
      this.segmentCount = 0;
      this.uploadBuffer.clear();
   }

   public boolean isEmpty() {
      return this.segmentCount <= 0;
   }

   public int segmentCount() {
      return this.segmentCount;
   }

   /** 提交单段 3D 能量丝带（输入为相对相机坐标，color 内部自动转入线性 HDR 空间）。 */
   public void addSegmentRel(
      float x0, float y0, float z0, float halfW0, float alpha0, float u0,
      float x1, float y1, float z1, float halfW1, float alpha1, float u1,
      int rgb, float hdrEmission, Style style, float seed01
   ) {
      if (this.segmentCount >= MAX_SEGMENTS || (alpha0 <= 0.002F && alpha1 <= 0.002F) || hdrEmission <= 0.002F) {
         return;
      }
      float linR = srgbToLinear(((rgb >> 16) & 0xFF) / 255.0F) * hdrEmission;
      float linG = srgbToLinear(((rgb >> 8) & 0xFF) / 255.0F) * hdrEmission;
      float linB = srgbToLinear((rgb & 0xFF) / 255.0F) * hdrEmission;
      float packedStyle = (float)style.id + Math.max(0.0F, Math.min(0.995F, seed01));

      this.uploadBuffer.put(x0).put(y0).put(z0).put(Math.max(0.004F, halfW0));
      this.uploadBuffer.put(x1).put(y1).put(z1).put(Math.max(0.004F, halfW1));
      this.uploadBuffer.put(linR).put(linG).put(linB).put(Math.max(0.0F, Math.min(1.0F, alpha0)));
      this.uploadBuffer.put(Math.max(0.0F, Math.min(1.0F, alpha1))).put(u0).put(u1).put(packedStyle);
      this.segmentCount++;
   }

   /** 便捷提交一条连接两世界坐标的弧形/螺旋 3D 能量丝带（例如黑洞吸积虹吸丝、星座连线、光柱能量环链）。 */
   public void addCurvedRibbon(
      FxFrame frame,
      Vec3 worldStart,
      Vec3 worldEnd,
      Vec3 controlOffset,
      int steps,
      float halfWidth,
      int rgb,
      float hdrEmission,
      float alpha,
      Style style,
      float seed01
   ) {
      if (steps < 1 || alpha <= 0.002F || this.segmentCount + steps > MAX_SEGMENTS) {
         return;
      }
      double sx = worldStart.x - frame.camera.x;
      double sy = worldStart.y - frame.camera.y;
      double sz = worldStart.z - frame.camera.z;
      double ex = worldEnd.x - frame.camera.x;
      double ey = worldEnd.y - frame.camera.y;
      double ez = worldEnd.z - frame.camera.z;
      double mx = (sx + ex) * 0.5 + controlOffset.x;
      double my = (sy + ey) * 0.5 + controlOffset.y;
      double mz = (sz + ez) * 0.5 + controlOffset.z;

      float prevX = (float)sx;
      float prevY = (float)sy;
      float prevZ = (float)sz;
      for (int i = 1; i <= steps; i++) {
         float t0 = (float)(i - 1) / (float)steps;
         float t1 = (float)i / (float)steps;
         float omt = 1.0F - t1;
         float nx = (float)(omt * omt * sx + 2.0F * omt * t1 * mx + t1 * t1 * ex);
         float ny = (float)(omt * omt * sy + 2.0F * omt * t1 * my + t1 * t1 * ey);
         float nz = (float)(omt * omt * sz + 2.0F * omt * t1 * mz + t1 * t1 * ez);

         float taper0 = (float)Math.sin(Math.PI * t0);
         float taper1 = (float)Math.sin(Math.PI * t1);
         float w0 = halfWidth * (0.28F + 0.72F * taper0);
         float w1 = halfWidth * (0.28F + 0.72F * taper1);
         float a0 = alpha * (0.15F + 0.85F * taper0);
         float a1 = alpha * (0.15F + 0.85F * taper1);

         this.addSegmentRel(prevX, prevY, prevZ, w0, a0, t0, nx, ny, nz, w1, a1, t1, rgb, hdrEmission, style, seed01);
         prevX = nx;
         prevY = ny;
         prevZ = nz;
      }
   }

   /** 一次 {@code glDrawArraysInstanced} 绘制本帧全部 3D 能量丝带段。 */
   public int render(FxFrame frame, int depthTex, int reliefMetaTex, float reliefUvMaxX, float reliefUvMaxY, boolean hasRelief) {
      if (this.segmentCount <= 0) {
         return 0;
      }
      StellarisTboShaders tbo = StellarisTboShaders.INSTANCE;
      if (!tbo.ensureReady()) {
         return 0;
      }

      this.uploadBuffer.flip();
      tbo.uploadAndBindTbo(true, this.uploadBuffer);

      GL20.glUseProgram(tbo.ribbonProgram());
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + StellarisGlGuard.RELIEF_META_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, hasRelief ? reliefMetaTex : depthTex);

      if (tbo.rLocData >= 0) GL20.glUniform1i(tbo.rLocData, StellarisGlGuard.TBO_UNIT);
      if (tbo.rLocDepth >= 0) GL20.glUniform1i(tbo.rLocDepth, 0);
      if (tbo.rLocRelief >= 0) GL20.glUniform1i(tbo.rLocRelief, StellarisGlGuard.RELIEF_META_UNIT);
      if (tbo.rLocViewProj >= 0) {
         frame.viewProjection.get(this.matScratch);
         GL20.glUniformMatrix4fv(tbo.rLocViewProj, false, this.matScratch);
      }
      if (tbo.rLocInvProj >= 0) {
         frame.inverseProjection.get(this.matScratch);
         GL20.glUniformMatrix4fv(tbo.rLocInvProj, false, this.matScratch);
      }
      if (tbo.rLocCamUp >= 0) GL20.glUniform3f(tbo.rLocCamUp, frame.up.x, frame.up.y, frame.up.z);
      if (tbo.rLocTime >= 0) GL20.glUniform1f(tbo.rLocTime, frame.time);
      if (tbo.rLocOutSize >= 0) GL20.glUniform2f(tbo.rLocOutSize, (float)frame.width, (float)frame.height);
      if (tbo.rLocSoftness >= 0) GL20.glUniform1f(tbo.rLocSoftness, 0.32F);
      if (tbo.rLocReliefParams >= 0) GL20.glUniform4f(tbo.rLocReliefParams, reliefUvMaxX, reliefUvMaxY, hasRelief ? 1.0F : 0.0F, 0.0F);

      GL30.glBindVertexArray(tbo.emptyVao());
      GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, this.segmentCount);
      GL30.glBindVertexArray(0);
      GL20.glUseProgram(0);
      StellarisGlGuard.syncActiveTexture(GL13.GL_TEXTURE0);
      return this.segmentCount;
   }

   private static float srgbToLinear(float c) {
      if (c <= 0.04045F) {
         return c / 12.92F;
      }
      return (float)Math.pow((c + 0.055) / 1.055, 2.4);
   }
}
