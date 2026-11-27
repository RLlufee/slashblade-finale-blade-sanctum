package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

/**
 * 8 帧环形缓冲异步 GPU 时间戳计时器（Step 1.6 升级：基于 {@code GL_TIMESTAMP} / {@code glQueryCounter}）。
 * <p>
 * 相比 {@code GL_TIME_ELAPSED}：
 * <ul>
 *   <li>支持任意子阶段并行/嵌套记录，彻底免疫与 Iris/外部性能探针同时开启 {@code GL_TIME_ELAPSED} 触发 {@code GL_INVALID_OPERATION} 的冲突；</li>
 *   <li>8 帧环形槽位 + 仅在 {@code GL_QUERY_RESULT_AVAILABLE} 为真时读回，彻底零 CPU-GPU 流水线停顿；</li>
 *   <li>不支持 {@code glQueryCounter} 的老显卡自动无缝回退至高精度 CPU 阶段耗时。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisGpuTimer {
   public enum Stage {
      INGEST("ingest"),
      RELIEF("relief"),
      GEOMETRY("geo+tbo"),
      HEAVY_MRT("heavy_mrt"),
      SCENE("scene"),
      BLOOM("bloom"),
      STREAK("streak"),
      GOD_RAYS("godrays"),
      EXPOSURE("exposure"),
      COMPOSITE("composite");

      public final String label;

      Stage(String label) {
         this.label = label;
      }
   }

   private static final int RING_SIZE = 8;
   private static final Stage[] STAGES = Stage.values();

   private final int[][] startQueries = new int[RING_SIZE][STAGES.length];
   private final int[][] endQueries = new int[RING_SIZE][STAGES.length];
   private final boolean[][] used = new boolean[RING_SIZE][STAGES.length];
   private final boolean[] pending = new boolean[RING_SIZE];
   private final int[] lastStage = new int[RING_SIZE];
   private final float[] lastMs = new float[STAGES.length];
   private final float[] smoothedMs = new float[STAGES.length];
   private final long[] cpuStartNs = new long[STAGES.length];

   private boolean initialized;
   private boolean gpuSupported;
   private int cursor;
   private int activeFrame = -1;
   private Stage activeStage;
   private float smoothedTotalMs;
   private float lastTotalMs;

   public void ensureInitialized() {
      if (this.initialized) {
         return;
      }
      RenderSystem.assertOnRenderThread();
      this.initialized = true;
      Arrays.fill(this.lastStage, -1);
      try {
         GLCapabilities caps = GL.getCapabilities();
         this.gpuSupported = caps.glQueryCounter != 0L && caps.glGetQueryObjectui64v != 0L
            && (caps.OpenGL33 || caps.GL_ARB_timer_query);
         if (this.gpuSupported) {
            for (int r = 0; r < RING_SIZE; r++) {
               for (int s = 0; s < STAGES.length; s++) {
                  this.startQueries[r][s] = GL15.glGenQueries();
                  this.endQueries[r][s] = GL15.glGenQueries();
               }
            }
         }
      } catch (Throwable throwable) {
         this.gpuSupported = false;
         Annihilationblade.LOGGER.warn("[Stellaris Engine] GL_TIMESTAMP unavailable, falling back to CPU stage timings: {}", throwable.toString());
      }
   }

   public boolean isGpuBacked() {
      return this.gpuSupported;
   }

   /** 新活跃帧开始：非阻塞收割已完成槽位的 GPU 时间戳并分配本帧空闲槽位。 */
   public void beginFrame() {
      this.ensureInitialized();
      this.activeStage = null;

      if (this.gpuSupported) {
         for (int r = 0; r < RING_SIZE; r++) {
            if (!this.pending[r]) {
               continue;
            }
            int tail = this.lastStage[r];
            if (tail < 0) {
               this.pending[r] = false;
               continue;
            }
            int tailQuery = this.endQueries[r][tail];
            if (GL15.glGetQueryObjecti(tailQuery, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            float frameSum = 0.0F;
            for (int s = 0; s < STAGES.length; s++) {
               if (!this.used[r][s]) {
                  this.lastMs[s] = 0.0F;
                  this.smoothedMs[s] *= 0.88F;
                  continue;
               }
               long t0 = GL33.glGetQueryObjectui64(this.startQueries[r][s], GL15.GL_QUERY_RESULT);
               long t1 = GL33.glGetQueryObjectui64(this.endQueries[r][s], GL15.GL_QUERY_RESULT);
               float ms = t1 >= t0 ? Math.min(250.0F, (t1 - t0) / 1_000_000.0F) : 0.0F;
               this.lastMs[s] = ms;
               this.smoothedMs[s] = this.smoothedMs[s] <= 1.0E-4F ? ms : this.smoothedMs[s] * 0.85F + ms * 0.15F;
               frameSum += ms;
            }
            this.lastTotalMs = frameSum;
            this.smoothedTotalMs = this.smoothedTotalMs <= 1.0E-4F ? frameSum : this.smoothedTotalMs * 0.85F + frameSum * 0.15F;
            this.pending[r] = false;
         }

         this.activeFrame = -1;
         for (int n = 0; n < RING_SIZE; n++) {
            int r = (this.cursor + n) % RING_SIZE;
            if (!this.pending[r]) {
               this.activeFrame = r;
               this.cursor = (r + 1) % RING_SIZE;
               Arrays.fill(this.used[r], false);
               this.lastStage[r] = -1;
               break;
            }
         }
      } else {
         float frameSum = 0.0F;
         for (int s = 0; s < STAGES.length; s++) {
            frameSum += this.lastMs[s];
         }
         this.lastTotalMs = frameSum;
         this.smoothedTotalMs = this.smoothedTotalMs <= 1.0E-4F ? frameSum : this.smoothedTotalMs * 0.85F + frameSum * 0.15F;
      }
   }

   public void begin(Stage stage) {
      if (stage == null || this.activeStage != null) {
         return;
      }
      this.activeStage = stage;
      int idx = stage.ordinal();
      if (this.gpuSupported) {
         if (this.activeFrame >= 0 && this.startQueries[this.activeFrame][idx] != 0) {
            GL33.glQueryCounter(this.startQueries[this.activeFrame][idx], GL33.GL_TIMESTAMP);
         }
      } else {
         this.cpuStartNs[idx] = System.nanoTime();
      }
   }

   public void end(Stage stage) {
      if (stage == null || this.activeStage != stage) {
         return;
      }
      int idx = stage.ordinal();
      if (this.gpuSupported) {
         if (this.activeFrame >= 0 && this.endQueries[this.activeFrame][idx] != 0) {
            GL33.glQueryCounter(this.endQueries[this.activeFrame][idx], GL33.GL_TIMESTAMP);
            this.used[this.activeFrame][idx] = true;
            this.lastStage[this.activeFrame] = idx;
         }
      } else {
         float ms = Math.max(0.0F, (System.nanoTime() - this.cpuStartNs[idx]) / 1_000_000.0F);
         this.lastMs[idx] = ms;
         this.smoothedMs[idx] = this.smoothedMs[idx] <= 1.0E-4F ? ms : this.smoothedMs[idx] * 0.85F + ms * 0.15F;
      }
      this.activeStage = null;
   }

   public void endFrame() {
      if (this.activeStage != null) {
         this.end(this.activeStage);
      }
      if (this.gpuSupported && this.activeFrame >= 0) {
         if (this.lastStage[this.activeFrame] >= 0) {
            this.pending[this.activeFrame] = true;
         }
         this.activeFrame = -1;
      }
   }

   /** 零活跃特效帧：将显示耗时平滑归零，真实反映零开销。 */
   public void recordIdleFrame() {
      this.lastTotalMs = 0.0F;
      this.smoothedTotalMs = 0.0F;
      for (int s = 0; s < STAGES.length; s++) {
         this.lastMs[s] = 0.0F;
         this.smoothedMs[s] = 0.0F;
      }
   }

   public float smoothedTotalMs() {
      return this.smoothedTotalMs;
   }

   public float lastTotalMs() {
      return this.lastTotalMs;
   }

   public float smoothedMs(Stage stage) {
      return this.smoothedMs[stage.ordinal()];
   }

   public Map<Stage, Float> snapshotSmoothed() {
      EnumMap<Stage, Float> map = new EnumMap<>(Stage.class);
      for (Stage stage : STAGES) {
         map.put(stage, this.smoothedMs[stage.ordinal()]);
      }
      return map;
   }

   public void destroy() {
      if (!this.initialized) {
         return;
      }
      if (this.gpuSupported) {
         RenderSystem.assertOnRenderThread();
         for (int r = 0; r < RING_SIZE; r++) {
            for (int s = 0; s < STAGES.length; s++) {
               if (this.startQueries[r][s] != 0) {
                  GL15.glDeleteQueries(this.startQueries[r][s]);
                  this.startQueries[r][s] = 0;
               }
               if (this.endQueries[r][s] != 0) {
                  GL15.glDeleteQueries(this.endQueries[r][s]);
                  this.endQueries[r][s] = 0;
               }
               this.used[r][s] = false;
            }
            this.pending[r] = false;
            this.lastStage[r] = -1;
         }
      }
      this.initialized = false;
      this.activeFrame = -1;
      this.activeStage = null;
      this.recordIdleFrame();
   }
}
