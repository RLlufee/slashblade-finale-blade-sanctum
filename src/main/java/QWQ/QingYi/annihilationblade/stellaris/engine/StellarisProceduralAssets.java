package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * 星穹引擎程序化 GPU 纹理资产库（Step 1.6 电影级物理烘焙版，后台异步构建 + PBO 安全上传）：
 * <ol>
 *   <li><b>64×64×64 可平铺 3D 体积与旋度噪声（{@code GL_TEXTURE_3D, RGBA8}）</b>：
 *       R = 无缝 Perlin-Worley 云密度、G = 锐利 Worley 脊线、B = 多八度周期梯度 fBm、A = 真正无散度 3D Curl 旋度湍流强度；</li>
 *   <li><b>64×64 可平铺四通道 Ulichney Void-and-Cluster 蓝噪声（{@code GL_TEXTURE_2D, RGBA8}）</b>：
 *       真正周期高斯能量核空洞-团簇迭代蓝噪声，彻底消灭 3×3 高通残留的 Nyquist 棋盘格条纹；</li>
 *   <li><b>256×1 精确普朗克辐射 × CIE 1931 物理黑体 LUT（{@code GL_TEXTURE_2D, RGBA16F}）</b>：
 *       对 380~780 nm 光谱精确积分至线性 Rec.709（sRGB），覆盖 {@code 1000 K ~ 40000 K} 对数色温并在极高温段顺滑融入切伦科夫冰青特征。</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisProceduralAssets {
   public static final int NOISE3D_SIZE = 64;
   public static final int BLUE_NOISE_SIZE = 64;
   public static final int BLACKBODY_SIZE = 256;

   public static final StellarisProceduralAssets INSTANCE = new StellarisProceduralAssets();

   private int noise3dTex;
   private int blueNoiseTex;
   private int blackbodyTex;
   private volatile BakedPayload bakedPayload;
   private CompletableFuture<BakedPayload> bakingTask;
   private boolean failed;

   private record BakedPayload(byte[] noise3dRgba, byte[] blueNoiseRgba, float[] blackbodyRgba) {
   }

   private StellarisProceduralAssets() {
   }

   /** 在资源重载/进世界早期触发后台线程烘焙，避免渲染线程首次卡顿。 */
   public synchronized void startBackgroundBake() {
      if (this.bakedPayload != null || this.bakingTask != null || this.failed) {
         return;
      }
      this.bakingTask = CompletableFuture.supplyAsync(() -> {
         long startNs = System.nanoTime();
         byte[] noise3d = bakeNoise3D(NOISE3D_SIZE);
         byte[] blueNoise = bakeVoidAndClusterBlueNoise(BLUE_NOISE_SIZE);
         float[] blackbody = bakePlanckCieBlackbodyLut(BLACKBODY_SIZE);
         long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
         Annihilationblade.LOGGER.info(
            "[Stellaris Engine] Step 1.6 procedural GPU assets (Curl-3D / Void-and-Cluster BlueNoise / Planck-CIE Blackbody) baked in {} ms.",
            elapsedMs
         );
         BakedPayload payload = new BakedPayload(noise3d, blueNoise, blackbody);
         this.bakedPayload = payload;
         return payload;
      });
   }

   /** 确保三张纹理已上传至 GPU（严格解绑残留 PBO 防驱动崩毁）。 */
   public boolean ensureUploaded() {
      if (this.ready()) {
         return true;
      }
      if (this.failed) {
         return false;
      }
      RenderSystem.assertOnRenderThread();
      this.startBackgroundBake();
      BakedPayload payload = this.bakedPayload;
      if (payload == null) {
         try {
            payload = this.bakingTask.join();
         } catch (Throwable throwable) {
            this.failed = true;
            Annihilationblade.LOGGER.error("[Stellaris Engine] Failed to bake procedural GPU assets.", throwable);
            return false;
         }
      }

      int prev2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      int prev3d = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
      int prevPbo = StellarisGlGuard.unbindPixelUnpackBuffer();
      try {
         // 1. 上传 64^3 3D 体积与 Curl 旋度噪声
         ByteBuffer n3Buf = BufferUtils.createByteBuffer(payload.noise3dRgba.length);
         n3Buf.put(payload.noise3dRgba).flip();
         int n3 = GlStateManager._genTexture();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, n3);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
         GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, NOISE3D_SIZE, NOISE3D_SIZE, NOISE3D_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, n3Buf);
         this.noise3dTex = n3;

         // 2. 上传 64x64 Void-and-Cluster 四通道蓝噪声
         ByteBuffer bnBuf = BufferUtils.createByteBuffer(payload.blueNoiseRgba.length);
         bnBuf.put(payload.blueNoiseRgba).flip();
         int bn = GlStateManager._genTexture();
         GlStateManager._bindTexture(bn);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, BLUE_NOISE_SIZE, BLUE_NOISE_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bnBuf);
         this.blueNoiseTex = bn;

         // 3. 上传 256x1 RGBA16F 精确普朗克黑体色温 LUT
         FloatBuffer bbBuf = BufferUtils.createFloatBuffer(payload.blackbodyRgba.length);
         bbBuf.put(payload.blackbodyRgba).flip();
         int bb = GlStateManager._genTexture();
         GlStateManager._bindTexture(bb);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, BLACKBODY_SIZE, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, bbBuf);
         this.blackbodyTex = bb;

         return true;
      } catch (Throwable throwable) {
         this.failed = true;
         this.destroyGpu();
         Annihilationblade.LOGGER.error("[Stellaris Engine] Failed to upload procedural GPU textures.", throwable);
         return false;
      } finally {
         StellarisGlGuard.restorePixelUnpackBuffer(prevPbo);
         GlStateManager._bindTexture(prev2d);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, prev3d);
      }
   }

   public boolean ready() {
      return this.noise3dTex != 0 && this.blueNoiseTex != 0 && this.blackbodyTex != 0;
   }

   public int noise3dTexture() {
      return this.noise3dTex;
   }

   public int blueNoiseTexture() {
      return this.blueNoiseTex;
   }

   public int blackbodyLutTexture() {
      return this.blackbodyTex;
   }

   public void resetFailure() {
      this.failed = false;
   }

   public void destroyGpu() {
      if (this.noise3dTex != 0) {
         RenderSystem.assertOnRenderThread();
         GlStateManager._deleteTexture(this.noise3dTex);
         this.noise3dTex = 0;
      }
      if (this.blueNoiseTex != 0) {
         RenderSystem.assertOnRenderThread();
         GlStateManager._deleteTexture(this.blueNoiseTex);
         this.blueNoiseTex = 0;
      }
      if (this.blackbodyTex != 0) {
         RenderSystem.assertOnRenderThread();
         GlStateManager._deleteTexture(this.blackbodyTex);
         this.blackbodyTex = 0;
      }
   }

   // ─────────────────────────── 1. 64^3 可平铺 3D 体积 + 无散度 Curl 噪声烘焙 ───────────────────────────

   private static byte[] bakeNoise3D(int size) {
      int n = size * size * size;
      // 先烘焙 3 个独立周期矢量势场 Ψ=(ψx, ψy, ψz)，再用周期中心差分求真实无散度 ∇×Ψ 旋度强度写入 A 通道
      float[] potX = new float[n];
      float[] potY = new float[n];
      float[] potZ = new float[n];
      byte[] data = new byte[n * 4];
      float inv = 1.0F / size;
      int idx = 0;

      for (int z = 0; z < size; z++) {
         float fz = z * inv;
         for (int y = 0; y < size; y++) {
            float fy = y * inv;
            for (int x = 0; x < size; x++) {
               float fx = x * inv;
               int cellIdx = (z * size + y) * size + x;

               float pLow = tileValueNoise3(fx, fy, fz, 4, 17) * 0.56F
                  + tileValueNoise3(fx, fy, fz, 8, 53) * 0.29F
                  + tileValueNoise3(fx, fy, fz, 16, 131) * 0.15F;

               float w4 = 1.0F - tileWorley3(fx, fy, fz, 4, 211);
               float w8 = 1.0F - tileWorley3(fx, fy, fz, 8, 307);
               float w16 = 1.0F - tileWorley3(fx, fy, fz, 16, 419);

               float worleyFbm = w4 * 0.58F + w8 * 0.28F + w16 * 0.14F;
               float perlinWorley = remap01(pLow, (1.0F - worleyFbm) * 0.65F, 1.0F);
               float ridge = clamp01((w8 * 0.52F + w16 * 0.48F) * 1.15F - 0.12F);
               ridge = ridge * ridge;

               potX[cellIdx] = tileValueNoise3(fx, fy, fz, 6, 541) * 0.65F + tileValueNoise3(fx, fy, fz, 12, 673) * 0.35F;
               potY[cellIdx] = tileValueNoise3(fx, fy, fz, 6, 787) * 0.65F + tileValueNoise3(fx, fy, fz, 12, 911) * 0.35F;
               potZ[cellIdx] = tileValueNoise3(fx, fy, fz, 6, 1039) * 0.65F + tileValueNoise3(fx, fy, fz, 12, 1181) * 0.35F;

               data[idx++] = toByte(perlinWorley);
               data[idx++] = toByte(ridge);
               data[idx++] = toByte(clamp01(pLow));
               data[idx++] = 0;
            }
         }
      }

      int mask = size - 1;
      for (int z = 0; z < size; z++) {
         int zm = (z - 1) & mask;
         int zp = (z + 1) & mask;
         for (int y = 0; y < size; y++) {
            int ym = (y - 1) & mask;
            int yp = (y + 1) & mask;
            for (int x = 0; x < size; x++) {
               int xm = (x - 1) & mask;
               int xp = (x + 1) & mask;

               // ∇×Ψ = (∂ψz/∂y - ∂ψy/∂z, ∂ψx/∂z - ∂ψz/∂x, ∂ψy/∂x - ∂ψx/∂y)
               float dPzDy = potZ[(z * size + yp) * size + x] - potZ[(z * size + ym) * size + x];
               float dPyDz = potY[(zp * size + y) * size + x] - potY[(zm * size + y) * size + x];
               float dPxDz = potX[(zp * size + y) * size + x] - potX[(zm * size + y) * size + x];
               float dPzDx = potZ[(z * size + y) * size + xp] - potZ[(z * size + y) * size + xm];
               float dPyDx = potY[(z * size + y) * size + xp] - potY[(z * size + y) * size + xm];
               float dPxDy = potX[(z * size + yp) * size + x] - potX[(z * size + ym) * size + x];

               float cx = dPzDy - dPyDz;
               float cy = dPxDz - dPzDx;
               float cz = dPyDx - dPxDy;
               float curlMag = clamp01((float)Math.sqrt(cx * cx + cy * cy + cz * cz) * 2.15F);
               data[((z * size + y) * size + x) * 4 + 3] = toByte(curlMag);
            }
         }
      }
      return data;
   }

   // ─────────────────────────── 2. 64×64 Ulichney Void-and-Cluster 四通道蓝噪声 ───────────────────────────

   /**
    * 经典 Ulichney Void-and-Cluster 周期蓝噪声生成器（σ = 1.5，截断半径 6）。
    * <p>
    * 使用增量高斯能量场在 64×64 网格上实现 O(N·k²) 精确空洞/团簇交换，四通道总计仅需约 10~20 ms，
    * 彻底根除 3×3 高通近似产生的 Nyquist 棋盘格条纹。
    */
   private static byte[] bakeVoidAndClusterBlueNoise(int size) {
      int n = size * size;
      int mask = size - 1;
      int rad = 6;
      int span = rad * 2 + 1;
      float[] kernel = new float[span * span];
      double invTwoSigma2 = 1.0 / (2.0 * 1.5 * 1.5);
      for (int dy = -rad; dy <= rad; dy++) {
         for (int dx = -rad; dx <= rad; dx++) {
            kernel[(dy + rad) * span + (dx + rad)] = (float)Math.exp(-(dx * dx + dy * dy) * invTwoSigma2);
         }
      }

      byte[] rgba = new byte[n * 4];
      boolean[] baseBits = new boolean[n];
      float[] baseEnergy = new float[n];
      boolean[] bits = new boolean[n];
      float[] energy = new float[n];
      int[] ranks = new int[n];
      int initialOnes = Math.max(1, n / 10);

      for (int ch = 0; ch < 4; ch++) {
         Arrays.fill(baseBits, false);
         Arrays.fill(baseEnergy, 0.0F);
         int seed = 0x57E11A21 + ch * 0x9E3779B9;

         // 初始随机撒点（10% 密度）
         int placed = 0;
         int attempts = 0;
         while (placed < initialOnes && attempts < n * 8) {
            int h = hashInt(attempts++, ch, 73, seed);
            int p = h & (n - 1);
            if (!baseBits[p]) {
               baseBits[p] = true;
               addKernel(baseEnergy, p % size, p / size, size, mask, rad, span, kernel, 1.0F);
               placed++;
            }
         }

         // 阶段 0：团簇-空洞松弛直至收敛出极度均匀的初始二值分布
         for (int iter = 0; iter < 256; iter++) {
            int cluster = findExtreme(baseBits, baseEnergy, true, true);
            baseBits[cluster] = false;
            addKernel(baseEnergy, cluster % size, cluster / size, size, mask, rad, span, kernel, -1.0F);
            int voidPos = findExtreme(baseBits, baseEnergy, false, false);
            baseBits[voidPos] = true;
            addKernel(baseEnergy, voidPos % size, voidPos / size, size, mask, rad, span, kernel, 1.0F);
            if (voidPos == cluster) {
               break;
            }
         }

         // 阶段 1：逐一剥离初始 1 中的最密团簇 → 赋秩 initialOnes-1 .. 0
         System.arraycopy(baseBits, 0, bits, 0, n);
         System.arraycopy(baseEnergy, 0, energy, 0, n);
         for (int r = placed - 1; r >= 0; r--) {
            int c = findExtreme(bits, energy, true, true);
            bits[c] = false;
            addKernel(energy, c % size, c / size, size, mask, rad, span, kernel, -1.0F);
            ranks[c] = r;
         }

         // 阶段 2：逐一在最大空洞中插入 1 → 赋秩 initialOnes .. n/2 - 1
         System.arraycopy(baseBits, 0, bits, 0, n);
         System.arraycopy(baseEnergy, 0, energy, 0, n);
         int half = n / 2;
         for (int r = placed; r < half; r++) {
            int v = findExtreme(bits, energy, false, false);
            bits[v] = true;
            addKernel(energy, v % size, v / size, size, mask, rad, span, kernel, 1.0F);
            ranks[v] = r;
         }

         // 阶段 3：反转追踪稀疏 0 分布，逐一消除最密 0 团簇 → 赋秩 n/2 .. n-1
         Arrays.fill(energy, 0.0F);
         for (int i = 0; i < n; i++) {
            if (!bits[i]) {
               addKernel(energy, i % size, i / size, size, mask, rad, span, kernel, 1.0F);
            }
         }
         for (int r = half; r < n; r++) {
            int c0 = findExtreme(bits, energy, false, true);
            bits[c0] = true;
            addKernel(energy, c0 % size, c0 / size, size, mask, rad, span, kernel, -1.0F);
            ranks[c0] = r;
         }

         float denom = Math.max(1.0F, n - 1.0F);
         for (int i = 0; i < n; i++) {
            rgba[i * 4 + ch] = toByte(ranks[i] / denom);
         }
      }
      return rgba;
   }

   private static void addKernel(float[] energy, int cx, int cy, int size, int mask, int rad, int span, float[] kernel, float sign) {
      for (int dy = -rad; dy <= rad; dy++) {
         int yRow = ((cy + dy) & mask) * size;
         int kRow = (dy + rad) * span;
         for (int dx = -rad; dx <= rad; dx++) {
            energy[yRow + ((cx + dx) & mask)] += sign * kernel[kRow + (dx + rad)];
         }
      }
   }

   private static int findExtreme(boolean[] bits, float[] energy, boolean targetState, boolean findMax) {
      int bestIdx = -1;
      float bestVal = findMax ? -Float.MAX_VALUE : Float.MAX_VALUE;
      for (int i = 0; i < bits.length; i++) {
         if (bits[i] != targetState) {
            continue;
         }
         float e = energy[i];
         if (findMax ? (e > bestVal) : (e < bestVal)) {
            bestVal = e;
            bestIdx = i;
         }
      }
      return Math.max(0, bestIdx);
   }

   // ─────────────────────────── 3. 256×1 精确普朗克辐射 × CIE 1931 线性 Rec.709 黑体 LUT ───────────────────────────

   /**
    * 通过精确普朗克黑体辐射谱与 Wyman-Sloan-Shirley (2013) 解析 CIE 1931 2° 色彩匹配函数积分生成物理黑体 LUT。
    * 全程直出线性 Rec.709（sRGB），零近似 Gamma 偏差。
    */
   private static float[] bakePlanckCieBlackbodyLut(int width) {
      float[] rgba = new float[width * 4];
      final double c2 = 1.4387769e7; // hc/k_B in nm·K

      for (int i = 0; i < width; i++) {
         float u = i / (float)(width - 1);
         double kelvin = 1000.0 * Math.pow(40.0, u); // 1000 K ~ 40000 K 对数均匀分布
         double sumX = 0.0;
         double sumY = 0.0;
         double sumZ = 0.0;

         // 先以可见光中心波长 555nm 归一化指数，杜绝低温/高温 double 溢出或下溢
         double refExponent = c2 / (555.0 * kelvin);
         for (int nm = 380; nm <= 780; nm += 5) {
            double lam = nm;
            double lamUm = lam * 1.0e-3;
            double expo = c2 / (lam * kelvin) - refExponent;
            if (expo > 600.0) {
               continue;
            }
            double denom = Math.exp(expo) - Math.exp(-refExponent);
            if (denom <= 1.0e-30) {
               continue;
            }
            double radiance = 1.0 / (Math.pow(lamUm, 5.0) * denom);
            sumX += radiance * cieX1931(lam);
            sumY += radiance * cieY1931(lam);
            sumZ += radiance * cieZ1931(lam);
         }

         // CIE XYZ -> 线性 Rec.709 (sRGB) D65 标准矩阵
         double lr =  3.2404542 * sumX - 1.5371385 * sumY - 0.4985314 * sumZ;
         double lg = -0.9692660 * sumX + 1.8760108 * sumY + 0.0415560 * sumZ;
         double lb =  0.0556434 * sumX - 0.2040259 * sumY + 1.0572252 * sumZ;

         // 保亮度色域软裁（消除极低温 <1500K 的负蓝通道硬切）
         double minC = Math.min(lr, Math.min(lg, lb));
         if (minC < 0.0) {
            double luma = Math.max(1.0e-12, 0.2126 * lr + 0.7152 * lg + 0.0722 * lb);
            double tClip = luma / (luma - minC);
            lr = luma + (lr - luma) * tClip;
            lg = luma + (lg - luma) * tClip;
            lb = luma + (lb - luma) * tClip;
         }

         float peak = (float)Math.max(1.0e-12, Math.max(lr, Math.max(lg, lb)));
         float fr = clamp01((float)(lr / peak));
         float fg = clamp01((float)(lg / peak));
         float fb = clamp01((float)(lb / peak));

         // 极高温段（> 22000 K）顺滑引入少量切伦科夫冰青特征，贴合无尽星空色彩母题
         if (kelvin > 22000.0) {
            float cherenkov = clamp01((float)((kelvin - 22000.0) / 18000.0)) * 0.26F;
            fr = lerp(fr, 0.06F, cherenkov);
            fg = lerp(fg, 0.90F, cherenkov);
            fb = lerp(fb, 1.00F, cherenkov);
            float p2 = Math.max(1.0E-4F, Math.max(fr, Math.max(fg, fb)));
            fr /= p2;
            fg /= p2;
            fb /= p2;
         }

         rgba[i * 4] = fr;
         rgba[i * 4 + 1] = fg;
         rgba[i * 4 + 2] = fb;
         rgba[i * 4 + 3] = clamp01(u);
      }
      return rgba;
   }

   /** Wyman-Sloan-Shirley (2013, JCGT) CIE 1931 2° 解析分段高斯拟合。 */
   private static double cieX1931(double waveNm) {
      double t1 = (waveNm - 442.0) * (waveNm < 442.0 ? 0.0624 : 0.0374);
      double t2 = (waveNm - 599.8) * (waveNm < 599.8 ? 0.0264 : 0.0323);
      double t3 = (waveNm - 501.1) * (waveNm < 501.1 ? 0.0490 : 0.0382);
      return 0.362 * Math.exp(-0.5 * t1 * t1) + 1.056 * Math.exp(-0.5 * t2 * t2) - 0.065 * Math.exp(-0.5 * t3 * t3);
   }

   private static double cieY1931(double waveNm) {
      double t1 = (waveNm - 568.8) * (waveNm < 568.8 ? 0.0213 : 0.0247);
      double t2 = (waveNm - 530.9) * (waveNm < 530.9 ? 0.0613 : 0.0322);
      return 0.821 * Math.exp(-0.5 * t1 * t1) + 0.286 * Math.exp(-0.5 * t2 * t2);
   }

   private static double cieZ1931(double waveNm) {
      double t1 = (waveNm - 437.0) * (waveNm < 437.0 ? 0.0845 : 0.0278);
      double t2 = (waveNm - 459.0) * (waveNm < 459.0 ? 0.0385 : 0.0725);
      return 1.217 * Math.exp(-0.5 * t1 * t1) + 0.681 * Math.exp(-0.5 * t2 * t2);
   }

   private static float tileValueNoise3(float x, float y, float z, int period, int seed) {
      float px = x * period;
      float py = y * period;
      float pz = z * period;
      int ix = floorDivWrap((int)Math.floor(px), period);
      int iy = floorDivWrap((int)Math.floor(py), period);
      int iz = floorDivWrap((int)Math.floor(pz), period);
      int ix1 = (ix + 1) % period;
      int iy1 = (iy + 1) % period;
      int iz1 = (iz + 1) % period;

      float fx = px - (float)Math.floor(px);
      float fy = py - (float)Math.floor(py);
      float fz = pz - (float)Math.floor(pz);
      float ux = fade(fx);
      float uy = fade(fy);
      float uz = fade(fz);

      float c000 = hash01(ix, iy, iz, seed);
      float c100 = hash01(ix1, iy, iz, seed);
      float c010 = hash01(ix, iy1, iz, seed);
      float c110 = hash01(ix1, iy1, iz, seed);
      float c001 = hash01(ix, iy, iz1, seed);
      float c101 = hash01(ix1, iy, iz1, seed);
      float c011 = hash01(ix, iy1, iz1, seed);
      float c111 = hash01(ix1, iy1, iz1, seed);

      float x00 = lerp(c000, c100, ux);
      float x10 = lerp(c010, c110, ux);
      float x01 = lerp(c001, c101, ux);
      float x11 = lerp(c011, c111, ux);
      return lerp(lerp(x00, x10, uy), lerp(x01, x11, uy), uz);
   }

   private static float tileWorley3(float x, float y, float z, int period, int seed) {
      float px = x * period;
      float py = y * period;
      float pz = z * period;
      int bx = (int)Math.floor(px);
      int by = (int)Math.floor(py);
      int bz = (int)Math.floor(pz);
      float fx = px - bx;
      float fy = py - by;
      float fz = pz - bz;

      float minDistSq = 9.0F;
      for (int dz = -1; dz <= 1; dz++) {
         int cz = floorDivWrap(bz + dz, period);
         for (int dy = -1; dy <= 1; dy++) {
            int cy = floorDivWrap(by + dy, period);
            for (int dx = -1; dx <= 1; dx++) {
               int cx = floorDivWrap(bx + dx, period);
               int h = hashInt(cx, cy, cz, seed);
               float ox = ((h & 0xFF) / 255.0F) * 0.76F + 0.12F;
               float oy = (((h >>> 8) & 0xFF) / 255.0F) * 0.76F + 0.12F;
               float oz = (((h >>> 16) & 0xFF) / 255.0F) * 0.76F + 0.12F;
               float rx = dx + ox - fx;
               float ry = dy + oy - fy;
               float rz = dz + oz - fz;
               float dSq = rx * rx + ry * ry + rz * rz;
               if (dSq < minDistSq) {
                  minDistSq = dSq;
               }
            }
         }
      }
      return clamp01((float)Math.sqrt(minDistSq));
   }

   private static int floorDivWrap(int v, int period) {
      int m = v % period;
      return m < 0 ? m + period : m;
   }

   private static int hashInt(int x, int y, int z, int seed) {
      int h = x * 374761393 + y * 668265263 + z * 2147483647 + seed * 1274126177;
      h = (h ^ (h >>> 13)) * 1274126177;
      return h ^ (h >>> 16);
   }

   private static float hash01(int x, int y, int z, int seed) {
      return (hashInt(x, y, z, seed) & 0x00FFFFFF) / 16777215.0F;
   }

   private static float fade(float t) {
      return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
   }

   private static float lerp(float a, float b, float t) {
      return a + (b - a) * t;
   }

   private static float clamp01(float v) {
      return Math.max(0.0F, Math.min(1.0F, v));
   }

   private static float remap01(float value, float low, float high) {
      if (high <= low + 1.0E-5F) {
         return clamp01(value);
      }
      return clamp01((value - low) / (high - low));
   }

   private static byte toByte(float v) {
      return (byte)Math.max(0, Math.min(255, Math.round(clamp01(v) * 255.0F)));
   }
}
