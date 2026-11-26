package QWQ.QingYi.annihilationblade.stellaris.optics;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 呼吸拍无回弹绝对静音控制器（零 Mixin，走公开 {@link net.minecraft.client.sounds.SoundManager#updateSourceVolume} + {@link PlaySoundEvent}）。
 * <p>
 * 相比 {@code pause()/resume()} 会把静音前未播完的雨声/旧受击声卡住并在恢复瞬间倒灌回弹到超新星爆裂拍，
 * 对 {@link SoundSource#MASTER} 调整增益仅修改内存级 OpenAL {@code Listener.setGain}（绝不写 {@code options.txt}）：
 * <ul>
 *   <li>进入窗口：立刻将监听增益压至 {@code 0.0F}，并拦截窗口内非 UI 新音效；原有背景声在静默中自然结束，恢复瞬间零回弹；</li>
 *   <li>退出窗口、按 ESC 暂停、关闭配置、切换世界或登出时按 {@code mc.options.getSoundSourceVolume(MASTER)} 精准还原玩家最新主音量，杜绝永久静音。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class AudioSilencer {
   private static int remainingTicks;
   private static boolean mutedByStellaris;

   private AudioSilencer() {
   }

   /** 请求持续 {@code ticks} 个客户端 tick 的无回弹呼吸拍静音（支持续期）。 */
   public static void requestSilence(int ticks) {
      if (ticks <= 0 || !ModConfig.CLIENT.stellarisEngine.audioSilencingEnabled.getValue()) {
         return;
      }
      if (ticks > remainingTicks) {
         remainingTicks = ticks;
      }
      if (!mutedByStellaris) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null && !mc.isPaused()) {
            try {
               mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, 0.0F);
               mutedByStellaris = true;
            } catch (Throwable throwable) {
               Annihilationblade.LOGGER.warn("[Stellaris Engine] SoundManager master gain duck failed: {}", throwable.toString());
            }
         }
      }
   }

   public static boolean isSilenced() {
      return mutedByStellaris && remainingTicks > 0;
   }

   public static int remainingTicks() {
      return remainingTicks;
   }

   /** 每客户端 tick 推进一次，到期、按 ESC 暂停、切世界或关闭配置时立即安全还原真实主音量。 */
   public static void tick() {
      if (remainingTicks <= 0) {
         if (mutedByStellaris) {
            restoreNow();
         }
         return;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || mc.isPaused() || !ModConfig.CLIENT.stellarisEngine.audioSilencingEnabled.getValue()) {
         restoreNow();
         return;
      }
      remainingTicks--;
      if (remainingTicks <= 0) {
         restoreNow();
      }
   }

   /** 立即还原玩家配置的真实主音量（登出、切维度、按 ESC、调试重置均调用）。 */
   public static void restoreNow() {
      remainingTicks = 0;
      if (!mutedByStellaris) {
         return;
      }
      mutedByStellaris = false;
      Minecraft mc = Minecraft.getInstance();
      try {
         float userMaster = mc.options != null ? mc.options.getSoundSourceVolume(SoundSource.MASTER) : 1.0F;
         mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, userMaster);
      } catch (Throwable throwable) {
         Annihilationblade.LOGGER.warn("[Stellaris Engine] SoundManager master gain restore failed: {}", throwable.toString());
      }
   }

   @SubscribeEvent
   public static void onPlaySound(PlaySoundEvent event) {
      if (!isSilenced() || event.getSound() == null) {
         return;
      }
      // 放行 UI 点击音，避免界面反馈失声
      ResourceLocation loc = event.getSound().getLocation();
      if (loc != null && loc.getPath().startsWith("ui.")) {
         return;
      }
      event.setSound(null);
   }
}
