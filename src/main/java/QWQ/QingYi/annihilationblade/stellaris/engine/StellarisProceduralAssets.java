package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * 星穹引擎程序化 GPU 纹理资产库（零外部 PNG 体积膨胀，后台异步烘焙 + 渲染线程零停顿上传）：
 * <ol>
 *   <li><b>64×64×64 可平铺 3D 体积噪声（{@code GL_TEXTURE_3D, RGBA8}）</b>：
 *       R = 无缝 Perlin-Worley 云密度、G = 锐利 Worley 脊线、B = 低频大尺度无缝 Perlin fBm、A = 旋度/相变扰动；</li>
 *   <li><b>64×64 可平铺四通道蓝噪声（{@code GL_TEXTURE_2D, RGBA8}）</b>：
 *       通过高频空域能量压制生成的高质量蓝噪声，配合黄金比例时间旋转大幅抑制体积光与光线步进噪点；</li>
 *   <li><b>256×1 物理黑体色温 LUT（{@code GL_TEXTURE_2D, RGBA16F}）</b>：
 *       覆盖 {@code 1000 K ~ 40000 K} 的普朗克黑体色温与切伦科夫高温冰青外延，供吸积盘多普勒与热寂冷却查表。</li>
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

   /** 在进世界或资源加载期提前触发后台 CPU 烘焙，彻底消除渲染线程首次调用卡顿。 */
   public synchronized void startBackgroundBake() {
      if (this.bakedPayload != null || this.bakingTask != null || this.failed) {
         return;
      }
      this.bakingTask = CompletableFuture.supplyAsync(() -> {
         long startNs = System.nanoTime();
         byte[] noise3d = bakeNoise3D(NOISE3D_SIZE);
         byte[] blueNoise = bakeBlueNoise(BLUE_NOISE_SIZE);
         float[] blackbody = bakeBlackbodyLut(BLACKBODY_SIZE);
         long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
         Annihilationblade.LOGGER.info("[Stellaris Engine] Procedural GPU assets baked on worker thread in {} ms.", elapsedMs);
         BakedPayload payload = new BakedPayload(noise3d, blueNoise, blackbody);
         this.bakedPayload = payload;
         return payload;
      });
   }

   /** 确保三张纹理已上传至 GPU；若后台尚未算完则仅在首次按需同步等待。 */
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
      try {
         // 1. 上传 64^3 3D 体积噪声纹理
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

         // 2. 上传 64x64 蓝噪声纹理
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

         // 3. 上传 256x1 RGBA16F 黑体色温 LUT
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

   // ─────────────────────────── 1. 64^3 可平铺 3D 体积噪声烘焙 ───────────────────────────

   private static byte[] bakeNoise3D(int size) {
      byte[] data = new byte[size * size * size * 4];
      float inv = 1.0F / size;
      int idx = 0;
      for (int z = 0; z < size; z++) {
         float fz = z * inv;
         for (int y = 0; y < size; y++) {
            float fy = y * inv;
            for (int x = 0; x < size; x++) {
               float fx = x * inv;

               // 低频周期 Perlin fBm（4 / 8 / 16 周期，确保边界 100% 无缝平铺）
               float pLow = tileValueNoise3(fx, fy, fz, 4, 17) * 0.56F
                  + tileValueNoise3(fx, fy, fz, 8, 53) * 0.29F
                  + tileValueNoise3(fx, fy, fz, 16, 131) * 0.15F;

               // 多频周期 Worley 细胞噪声（1 - F1 形成蓬松云团与高亮脊线）
               float w4 = 1.0F - tileWorley3(fx, fy, fz, 4, 211);
               float w8 = 1.0F - tileWorley3(fx, fy, fz, 8, 307);
               float w16 = 1.0F - tileWorley3(fx, fy, fz, 16, 419);

               // R：Perlin-Worley 雕刻密度（经典影视体积云/星云塑造）
               float worleyFbm = w4 * 0.58F + w8 * 0.28F + w16 * 0.14F;
               float perlinWorley = remap01(pLow, (1.0F - worleyFbm) * 0.65F, 1.0F);

               // G：高频锐利 Worley 脊丝（等离子丝 / 吸积盘湍流 / 晶化裂脉）
               float ridge = clamp01((w8 * 0.52F + w16 * 0.48F) * 1.15F - 0.12F);
               ridge = ridge * ridge;

               // B：柔和周期 fBm（大尺度星云/虚空雾密度）
               float softFbm = clamp01(pLow);

               // A：相位偏移扰动场（用于旋度近似与扭曲位移）
               float curlHint = tileValueNoise3(fx + 0.37F, fy + 0.71F, fz + 0.19F, 6, 541) * 0.65F
                  + tileValueNoise3(fx + 0.13F, fy + 0.59F, fz + 0.83F, 12, 673) * 0.35F;

               data[idx++] = toByte(perlinWorley);
               data[idx++] = toByte(ridge);
               data[idx++] = toByte(softFbm);
               data[idx++] = toByte(curlHint);
            }
         }
      }
      return data;
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

   // ─────────────────────────── 2. 64x64 可平铺四通道蓝噪声烘焙 ───────────────────────────

   /**
    * 通过周期高通滤波 + 直方图均匀化（Rank Equalization）迭代生成 4 通道独立可平铺蓝噪声：
    * 能量集中在高频频段且严格服从 [0, 1] 均匀分布，显著优于白噪声和普通哈希。
    */
   private static byte[] bakeBlueNoise(int size) {
      int n = size * size;
      byte[] rgba = new byte[n * 4];
      float[] field = new float[n];
      float[] tmp = new float[n];
      Integer[] order = new Integer[n];

      for (int ch = 0; ch < 4; ch++) {
         int seed = 9187 + ch * 3571;
         for (int i = 0; i < n; i++) {
            field[i] = hash01(i % size, i / size, ch, seed);
            order[i] = i;
         }
         // 8 轮周期高斯高通剥离低频团聚 + 秩均衡保持严格均匀直方图
         for (int iter = 0; iter < 8; iter++) {
            for (int y = 0; y < size; y++) {
               int ym = (y - 1 + size) & (size - 1);
               int yp = (y + 1) & (size - 1);
               for (int x = 0; x < size; x++) {
                  int xm = (x - 1 + size) & (size - 1);
                  int xp = (x + 1) & (size - 1);
                  float blur = (
                     field[ym * size + xm] + field[ym * size + xp] + field[yp * size + xm] + field[yp * size + xp]
                        + 2.0F * (field[ym * size + x] + field[yp * size + x] + field[y * size + xm] + field[y * size + xp])
                        + 4.0F * field[y * size + x]
                  ) * (1.0F / 16.0F);
                  tmp[y * size + x] = field[y * size + x] - blur * 0.88F;
               }
            }
            System.arraycopy(tmp, 0, field, 0, n);
            java.util.Arrays.sort(order, (a, b) -> Float.compare(field[a], field[b]));
            float denom = Math.max(1.0F, n - 1.0F);
            for (int rank = 0; rank < n; rank++) {
               field[order[rank]] = rank / denom;
            }
         }
         for (int i = 0; i < n; i++) {
            rgba[i * 4 + ch] = toByte(field[i]);
         }
      }
      return rgba;
   }

   // ─────────────────────────── 3. 256x1 物理黑体色温 LUT（1000 K ~ 40000 K） ───────────────────────────

   private static float[] bakeBlackbodyLut(int width) {
      float[] rgba = new float[width * 4];
      for (int i = 0; i < width; i++) {
         float u = i / (float)(width - 1);
         // 对数映射使低温红/金段与高温蓝白段分辨率同样细腻（1000 K ~ 40000 K）
         double kelvin = 1000.0 * Math.pow(40.0, u);
         double t = kelvin / 100.0;
         double r;
         double g;
         double b;

         if (t <= 66.0) {
            r = 1.0;
            g = (99.4708025861 * Math.log(Math.max(1.0, t)) - 161.1195681661) / 255.0;
            b = t <= 19.0 ? 0.0 : (138.5177312231 * Math.log(t - 10.0) - 305.0447927307) / 255.0;
         } else {
            r = (329.698727446 * Math.pow(t - 60.0, -0.1332047592)) / 255.0;
            g = (288.1221695283 * Math.pow(t - 60.0, -0.0755148492)) / 255.0;
            b = 1.0;
         }

         // 转线性空间以便在星穹线性 HDR 管线中直接相乘，避免双重 Gamma
         float lr = srgbToLinear(clamp01((float)r));
         float lg = srgbToLinear(clamp01((float)g));
         float lb = srgbToLinear(clamp01((float)b));

         // 极高温段（> 20000 K）顺滑引入少量切伦科夫冰青特征，贴合无尽星空色彩母题
         if (kelvin > 20000.0) {
            float cherenkov = clamp01((float)((kelvin - 20000.0) / 20000.0)) * 0.28F;
            lr = lerp(lr, 0.08F, cherenkov);
            lg = lerp(lg, 0.92F, cherenkov);
            lb = lerp(lb, 1.00F, cherenkov);
         }

         // 归一化峰值，保留色度纯度；A 通道存相对光度引导值
         float peak = Math.max(1.0E-4F, Math.max(lr, Math.max(lg, lb)));
         rgba[i * 4] = lr / peak;
         rgba[i * 4 + 1] = lg / peak;
         rgba[i * 4 + 2] = lb / peak;
         rgba[i * 4 + 3] = clamp01(u);
      }
      return rgba;
   }

   private static float srgbToLinear(float c) {
      return (float)Math.pow(Math.max(0.0F, c), 2.2);
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
