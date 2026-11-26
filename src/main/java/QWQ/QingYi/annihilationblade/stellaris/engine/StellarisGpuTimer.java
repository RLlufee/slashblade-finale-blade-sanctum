package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.EnumMap;
import java.util.Map;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * 4 帧环形缓冲异步 GPU 计时器（基于 {@code GL_TIME_ELAPSED}，零管线停顿）。
 * <p>
 * 若某个槽位在极高负载下仍未就绪，跳过该槽位的新写入，杜绝在 AMD/Intel 驱动上复用未完成 query 引发隐式同步卡顿。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisGpuTimer {
   public enum Stage {
      INGEST("ingest"),
      GEOMETRY("geo+part"),
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

   private static final int RING_SIZE = 4;
   private static final Stage[] STAGES = Stage.values();

   private final int[][] queries = new int[STAGES.length][RING_SIZE];
   private final boolean[][] issued = new boolean[STAGES.length][RING_SIZE];
   private final float[] lastMs = new float[STAGES.length];
   private final float[] smoothedMs = new float[STAGES.length];
   private final long[] cpuStartNs = new long[STAGES.length];

   private boolean initialized;
   private boolean gpuSupported;
   private int frameIndex;
   private Stage activeStage;
   private boolean activeQueryOpen;
   private float smoothedTotalMs;
   private float lastTotalMs;

   public void ensureInitialized() {
      if (this.initialized) {
         return;
      }
      RenderSystem.assertOnRenderThread();
      this.initialized = true;
      try {
         this.gpuSupported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
         if (this.gpuSupported) {
            for (int s = 0; s < STAGES.length; s++) {
               for (int r = 0; r < RING_SIZE; r++) {
                  this.queries[s][r] = GL15.glGenQueries();
               }
            }
         }
      } catch (Throwable throwable) {
         this.gpuSupported = false;
         Annihilationblade.LOGGER.warn("[Stellaris Engine] GL_TIME_ELAPSED unavailable, falling back to CPU stage timings: {}", throwable.toString());
      }
   }

   public boolean isGpuBacked() {
      return this.gpuSupported;
   }

   /** 新活跃帧开始前调用：无阻塞轮询最旧槽位的已完成查询。 */
   public void beginFrame() {
      this.ensureInitialized();
      this.activeStage = null;
      this.activeQueryOpen = false;
      int readSlot = (this.frameIndex + 1) % RING_SIZE;
      float frameSum = 0.0F;
      boolean anyUpdated = false;

      if (this.gpuSupported) {
         for (int s = 0; s < STAGES.length; s++) {
            if (!this.issued[s][readSlot]) {
               this.lastMs[s] = 0.0F;
               this.smoothedMs[s] *= 0.88F;
               continue;
            }
            int q = this.queries[s][readSlot];
            int available = GL15.glGetQueryObjecti(q, GL15.GL_QUERY_RESULT_AVAILABLE);
            if (available != 0) {
               long ns = GL33.glGetQueryObjectui64(q, GL15.GL_QUERY_RESULT);
               float ms = Math.max(0.0F, Math.min(250.0F, ns / 1_000_000.0F));
               this.lastMs[s] = ms;
               this.smoothedMs[s] = this.smoothedMs[s] <= 1.0E-4F ? ms : this.smoothedMs[s] * 0.85F + ms * 0.15F;
               this.issued[s][readSlot] = false;
               frameSum += ms;
               anyUpdated = true;
            } else {
               frameSum += this.lastMs[s];
            }
         }
      } else {
         for (int s = 0; s < STAGES.length; s++) {
            frameSum += this.lastMs[s];
            anyUpdated = true;
         }
      }

      if (anyUpdated) {
         this.lastTotalMs = frameSum;
         this.smoothedTotalMs = this.smoothedTotalMs <= 1.0E-4F ? frameSum : this.smoothedTotalMs * 0.85F + frameSum * 0.15F;
      }
   }

   public void begin(Stage stage) {
      if (stage == null || this.activeStage != null) {
         return;
      }
      this.activeStage = stage;
      this.activeQueryOpen = false;
      int idx = stage.ordinal();
      if (this.gpuSupported) {
         int writeSlot = this.frameIndex % RING_SIZE;
         int q = this.queries[idx][writeSlot];
         // 仅当当前槽位未被挂起占用（或已可读取回退）时才开启写入，防止阻塞
         if (q != 0) {
            if (this.issued[idx][writeSlot]) {
               if (GL15.glGetQueryObjecti(q, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                  return;
               }
               GL33.glGetQueryObjectui64(q, GL15.GL_QUERY_RESULT);
               this.issued[idx][writeSlot] = false;
            }
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, q);
            this.issued[idx][writeSlot] = true;
            this.activeQueryOpen = true;
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
         if (this.activeQueryOpen) {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            this.activeQueryOpen = false;
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
      this.frameIndex = (this.frameIndex + 1) % RING_SIZE;
   }

   /** 零活跃特效帧：把显示耗时平滑归零，确保 /stellaris debug 真实反映零开销。 */
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
         for (int s = 0; s < STAGES.length; s++) {
            for (int r = 0; r < RING_SIZE; r++) {
               if (this.queries[s][r] != 0) {
                  GL15.glDeleteQueries(this.queries[s][r]);
                  this.queries[s][r] = 0;
               }
               this.issued[s][r] = false;
            }
         }
      }
      this.initialized = false;
      this.activeStage = null;
      this.activeQueryOpen = false;
      this.recordIdleFrame();
   }
}
