package QWQ.QingYi.annihilationblade.stellaris.optics;

import QWQ.QingYi.annihilationblade.render.client.pipeline.FxTarget;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisTargets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 全 GPU 双缓冲异步自动曝光控制器（零 {@code glReadPixels} CPU-GPU 管线阻塞）。
 * <p>
 * 维护 {@code 1×1 RG16F} 乒乓目标：变暗时间常数 {@code 0.15 s}（爆发瞬间迅速压稳不洗白），
 * 变亮时间常数 {@code 0.60 s}（模拟人眼暗适应缓慢恢复）。
 */
@OnlyIn(Dist.CLIENT)
public final class AutoExposure {
   public static final float TAU_DARKEN = 0.15F;
   public static final float TAU_BRIGHTEN = 0.60F;

   private boolean pingPongB;
   private boolean initialized;

   public FxTarget previousTarget(StellarisTargets targets) {
      return this.pingPongB ? targets.exposureA : targets.exposureB;
   }

   public FxTarget currentTarget(StellarisTargets targets) {
      return this.pingPongB ? targets.exposureB : targets.exposureA;
   }

   public boolean initialized() {
      return this.initialized;
   }

   public void markUpdated() {
      this.initialized = true;
      this.pingPongB = !this.pingPongB;
   }

   /** 返回最新一帧写完后的可读曝光纹理 ID。 */
   public int readableTexture(StellarisTargets targets) {
      FxTarget target = this.pingPongB ? targets.exposureA : targets.exposureB;
      return target.ready() ? target.texture() : 0;
   }

   public void reset() {
      this.initialized = false;
      this.pingPongB = false;
   }
}
