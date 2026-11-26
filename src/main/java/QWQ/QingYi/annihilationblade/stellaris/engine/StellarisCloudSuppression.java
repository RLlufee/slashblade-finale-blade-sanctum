package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.annihilation_blade.client.ClientBladeVision;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹引擎云层抑制与夺夜退让控制器。
 * <p>
 * 原版 {@code LevelRenderer#renderClouds} 会以 {@code depthMask(true)} 写入主深度，
 * 在 SA「真空衰变·坍缩」夺夜 / 云洞期间若不取消，原版方云会戳破 {@code depth >= 0.9999} 的天空夺夜判定。
 * 本类只维护一个极轻量的渲染线程/客户端 tick 续期窗口：平时恒为 {@code false}（零开销），
 * 仅在夺夜、云洞或调试验证期间由客户端续期开启，同时自动通知 {@link ClientBladeVision} 退让持刀永昼。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisCloudSuppression {
   private static volatile int suppressTicks;

   private StellarisCloudSuppression() {
   }

   /**
    * 在接下来 {@code ticks} 个客户端 tick 内抑制原版云层绘制，并同步退让持刀永昼视界。
    */
   public static void requestSuppress(int ticks) {
      if (ticks <= 0) {
         return;
      }
      if (ticks > suppressTicks) {
         suppressTicks = ticks;
      }
      ClientBladeVision.suppressFullbrightForTicks(ticks + 2);
   }

   /** 供客户端 Mixin 在 {@code LevelRenderer#renderClouds} 头部调用。 */
   public static boolean shouldSuppressVanillaClouds() {
      return suppressTicks > 0;
   }

   /** 返回当前剩余抑制 tick 数（供调试面板显示）。 */
   public static int remainingTicks() {
      return suppressTicks;
   }

   /** 每客户端 tick 衰减一次；在切世界/登出时请调用 {@link #clear()}。 */
   public static void tick() {
      int current = suppressTicks;
      if (current > 0) {
         suppressTicks = current - 1;
      }
   }

   /** 立即清除云层抑制状态。 */
   public static void clear() {
      suppressTicks = 0;
   }
}
