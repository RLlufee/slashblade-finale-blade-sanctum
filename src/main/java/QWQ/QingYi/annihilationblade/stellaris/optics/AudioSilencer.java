package QWQ.QingYi.annihilationblade.stellaris.optics;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisConfigSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 呼吸拍无回弹绝对静音控制器（Step 1.6 升级：2~3 tick 平滑抽空斜坡 + 星穹专属白名单放行）。
 * <p>
 * <ul>
 *   <li>进入静默窗口时用 3 tick 指数斜坡快速抽空（杜绝 0ms 硬切导致的耳机直流咔哒声，同时营造真空失压听觉张力）；</li>
 *   <li>窗口内拦截原版环境音与生物杂音，但放行 {@code ui.*} 与 {@code annihilationblade:*}（确保星穹自身奇点心跳与终幕爆裂音绝不被误杀）；</li>
 *   <li>退出窗口、按 ESC 暂停、切世界、登出或关闭配置时按玩家最新主音量平滑还原，绝不写入 {@code options.txt}。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class AudioSilencer {
   private static final int DUCK_RAMP_TICKS = 3;
   private static final int RESTORE_RAMP_TICKS = 2;

   private static int remainingTicks;
   private static int duckElapsedTicks;
   private static int restoreRemainingTicks;
   private static boolean mutedByStellaris;

   private AudioSilencer() {
   }

   /** 请求持续 {@code ticks} 个客户端 tick 的呼吸拍真空静音（支持续期）。 */
   public static void requestSilence(int ticks) {
      if (ticks <= 0 || !StellarisConfigSnapshot.current().audioSilencingEnabled()) {
         return;
      }
      if (ticks > remainingTicks) {
         remainingTicks = ticks;
      }
      restoreRemainingTicks = 0;
      if (!mutedByStellaris) {
         Minecraft mc = Minecraft.getInstance();
         if (mc.level != null && !mc.isPaused()) {
            mutedByStellaris = true;
            duckElapsedTicks = 0;
            applyMasterGainRatio(0.35F);
         }
      }
   }

   public static boolean isSilenced() {
      return mutedByStellaris && remainingTicks > 0;
   }

   public static int remainingTicks() {
      return remainingTicks;
   }

   /** 每客户端 tick 推进一次抽空/恢复斜坡；到期、暂停或切世界立即安全还原。 */
   public static void tick() {
      Minecraft mc = Minecraft.getInstance();
      boolean configOn = StellarisConfigSnapshot.current().audioSilencingEnabled();
      if (mc.level == null || mc.isPaused() || !configOn) {
         if (mutedByStellaris || remainingTicks > 0 || restoreRemainingTicks > 0) {
            restoreNow();
         }
         return;
      }

      if (remainingTicks > 0) {
         duckElapsedTicks++;
         float ratio = duckElapsedTicks >= DUCK_RAMP_TICKS
            ? 0.0F
            : Math.max(0.0F, 1.0F - (float)duckElapsedTicks / (float)DUCK_RAMP_TICKS);
         applyMasterGainRatio(ratio * ratio);
         remainingTicks--;
         if (remainingTicks <= 0) {
            restoreRemainingTicks = RESTORE_RAMP_TICKS;
         }
         return;
      }

      if (restoreRemainingTicks > 0) {
         restoreRemainingTicks--;
         if (restoreRemainingTicks <= 0) {
            restoreNow();
         } else {
            float progress = 1.0F - (float)restoreRemainingTicks / (float)RESTORE_RAMP_TICKS;
            applyMasterGainRatio(0.45F + 0.55F * progress);
         }
      } else if (mutedByStellaris) {
         restoreNow();
      }
   }

   private static void applyMasterGainRatio(float ratio) {
      Minecraft mc = Minecraft.getInstance();
      try {
         float userMaster = mc.options != null ? mc.options.getSoundSourceVolume(SoundSource.MASTER) : 1.0F;
         mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, Math.max(0.0F, Math.min(1.0F, userMaster * ratio)));
      } catch (Throwable throwable) {
         Annihilationblade.LOGGER.warn("[Stellaris Engine] SoundManager master gain ramp failed: {}", throwable.toString());
      }
   }

   /** 立即还原玩家配置的真实主音量（登出、切维度、按 ESC、调试重置均调用）。 */
   public static void restoreNow() {
      remainingTicks = 0;
      duckElapsedTicks = 0;
      restoreRemainingTicks = 0;
      if (!mutedByStellaris) {
         return;
      }
      mutedByStellaris = false;
      applyMasterGainRatio(1.0F);
   }

   @SubscribeEvent
   public static void onPlaySound(PlaySoundEvent event) {
      if (!isSilenced() || event.getSound() == null) {
         return;
      }
      ResourceLocation loc = event.getSound().getLocation();
      if (loc != null) {
         // 放行 UI 点击音与本模组专属星穹奇点/爆裂音效，仅压制外界环境与杂音
         if (loc.getPath().startsWith("ui.") || Annihilationblade.MODID.equals(loc.getNamespace())) {
            return;
         }
      }
      event.setSound(null);
   }
}
