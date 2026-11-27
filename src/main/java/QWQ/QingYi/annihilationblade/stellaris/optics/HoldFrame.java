package QWQ.QingYi.annihilationblade.stellaris.optics;

import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisConfigSnapshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Hold-Frame 视网膜闪盲残像状态机（Step 1.6：读取不可变帧配置快照，捕获完整预合成视觉烙印）。
 */
@OnlyIn(Dist.CLIENT)
public final class HoldFrame {
   private float currentStrength;
   private float decaySeconds = 0.95F;
   private float tintR = 0.92F;
   private float tintG = 0.97F;
   private float tintB = 1.00F;
   private boolean hasCapturedFrame;

   /** 推进残像衰减；返回 {@code true} 表示本帧需要在阶段 8 预合成完成后立即执行一次半分辨率画面捕获。 */
   public boolean update(StellarisOpticsState optics, float dtSeconds) {
      StellarisConfigSnapshot cfg = StellarisConfigSnapshot.current();
      if (!cfg.flashBlindnessEnabled() || cfg.screenFlash() <= 1.0E-3F) {
         this.reset();
         return false;
      }
      boolean shouldCapture = false;
      if (optics.captureHoldFrame && optics.holdInitialStrength > 1.0E-3F) {
         this.currentStrength = Math.max(this.currentStrength, optics.holdInitialStrength);
         this.decaySeconds = optics.holdDecaySeconds;
         this.tintR = optics.holdTintR;
         this.tintG = optics.holdTintG;
         this.tintB = optics.holdTintB;
         this.hasCapturedFrame = true;
         shouldCapture = true;
      } else if (this.currentStrength > 1.0E-4F) {
         float dt = Math.max(0.0F, Math.min(0.1F, dtSeconds));
         float rate = 3.2F / Math.max(0.2F, this.decaySeconds);
         this.currentStrength *= (float)Math.exp(-dt * rate);
         if (this.currentStrength < 0.004F) {
            this.currentStrength = 0.0F;
            this.hasCapturedFrame = false;
         }
      }
      return shouldCapture;
   }

   public boolean active() {
      return this.hasCapturedFrame && this.currentStrength > 0.004F;
   }

   public float strength() {
      float flashScale = StellarisConfigSnapshot.current().screenFlash();
      return this.active() ? this.currentStrength * flashScale : 0.0F;
   }

   public float tintR() {
      return this.tintR;
   }

   public float tintG() {
      return this.tintG;
   }

   public float tintB() {
      return this.tintB;
   }

   public void reset() {
      this.currentStrength = 0.0F;
      this.hasCapturedFrame = false;
   }
}
