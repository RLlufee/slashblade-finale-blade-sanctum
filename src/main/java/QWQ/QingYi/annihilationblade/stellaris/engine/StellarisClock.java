package QWQ.QingYi.annihilationblade.stellaris.engine;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹引擎平滑追帧时钟与连续积分相位工具（Step 1.6）。
 * <p>
 * <ul>
 *   <li><b>多人联机平滑追帧</b>：面对服务端 tick 同步抖动，偏差在 {@code ±12 tick} 内按每 tick 最多 {@code ±0.6} 平滑收敛，避免特效时间轴抽搐或回退；偏差超过 12 tick（如刚进服/传送入场）则立即对齐；</li>
 *   <li><b>连续速度积分相位</b>：所有加速旋转的星环、极光、风暴云必须对角速度曲线做解析积分（{@code ∫ω(t)dt}），严禁写 {@code t * speed(t)}，确保变速或中途入场时永不倒转跳变。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisClock {
   private static final float SNAP_THRESHOLD = 12.0F;
   private static final float PULL_FACTOR = 0.25F;
   private static final float MAX_CORRECTION = 0.60F;

   private float prevTicks;
   private float currTicks;
   private boolean initialized;

   public void reset() {
      this.prevTicks = 0.0F;
      this.currTicks = 0.0F;
      this.initialized = false;
   }

   /** 按服务端权威已运行 tick 推进一次客户端平滑时钟。 */
   public void tickServerSynced(int serverTicks) {
      if (!this.initialized) {
         this.prevTicks = serverTicks;
         this.currTicks = serverTicks;
         this.initialized = true;
         return;
      }
      this.prevTicks = this.currTicks;
      float gap = serverTicks - (this.currTicks + 1.0F);
      if (Math.abs(gap) > SNAP_THRESHOLD) {
         this.prevTicks = serverTicks;
         this.currTicks = serverTicks;
      } else {
         float corr = Math.max(-MAX_CORRECTION, Math.min(MAX_CORRECTION, gap * PULL_FACTOR));
         this.currTicks += 1.0F + corr;
      }
   }

   /** 纯客户端自由递增 1 tick。 */
   public void tickLocal() {
      if (!this.initialized) {
         this.prevTicks = 0.0F;
         this.currTicks = 1.0F;
         this.initialized = true;
         return;
      }
      this.prevTicks = this.currTicks;
      this.currTicks += 1.0F;
   }

   /** 返回带 partialTick 插值的连续平滑 tick。 */
   public float at(float partialTick) {
      return this.prevTicks + (this.currTicks - this.prevTicks) * partialTick;
   }

   /** 返回带 partialTick 插值的连续秒数。 */
   public float seconds(float partialTick) {
      return this.at(partialTick) * 0.05F;
   }

   /**
    * 对平滑加速曲线 {@code ω(t) = baseSpeed + extraSpeed * smoothstep(t0, t1, t)} 求精确解析积分。
    * <p>
    * 其中 {@code ∫₀ᵘ (3x² - 2x³) dx = u³ - 0.5 u⁴}，保证任意时刻速度连续、相位单调不倒转，且中途入场客户端计算结果完全一致。
    */
   public static float integratedRampPhase(float t, float t0, float t1, float baseSpeed, float extraSpeed) {
      float span = Math.max(1.0E-4F, t1 - t0);
      float u = Math.max(0.0F, Math.min(1.0F, (t - t0) / span));
      float rampArea = span * (u * u * u - 0.5F * u * u * u * u);
      float postRamp = Math.max(0.0F, t - t1);
      return t * baseSpeed + extraSpeed * (rampArea + postRamp);
   }
}
