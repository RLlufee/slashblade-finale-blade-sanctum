package QWQ.QingYi.annihilationblade.annihilation_blade.client;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.common.AnnihilationBladeItemSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import QWQ.QingYi.annihilationblade.render.client.core.FxCompat;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 持刀永昼视界（{@code gamma = 15.0}）客户端统一管理器。
 * <p>
 * <ul>
 *   <li>合并湮灭之刃与无尽星空的 10 tick 背包扫描缓存，消除冗余遍历与旧状态错误续期漏洞；</li>
 *   <li>受 {@code ModConfig.CLIENT.clientRenderer.bladeFullbrightVision} 开关控制；</li>
 *   <li>检测到 Iris/Oculus 光影包或星穹引擎处于「夺夜 / 世界压暗 / 导演过场」演出窗口时自动平滑退让回玩家原始亮度，
 *       彻底避免 {@code gamma = 15.0} 洗平深空暗部与黑洞 HDR 对比度；</li>
 *   <li>在 {@link LoggingOut} 立即还原原始亮度，防止退游时将 {@code gamma:15.0} 误写入 {@code options.txt}。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class ClientBladeVision {
   private static final int INVENTORY_SCAN_INTERVAL = 10;
   private static final double FULL_BRIGHT_GAMMA = 15.0D;
   private static UUID lastPlayerId;
   private static int lastScanTick = Integer.MIN_VALUE;
   private static boolean cachedHasBlade;
   private static boolean cachedHasInfinityStellaris;
   private static int dramaticSuppressTicks;
   private static boolean gammaBoostActive;
   private static double savedGamma = -1.0D;
   private static Field gammaValueField;
   private static boolean gammaFieldLookupFailed;
   private static boolean gammaFieldWarningLogged;

   private ClientBladeVision() {
   }

   /**
    * 演出期间请求临时退让永昼亮度（供 SA 夺夜、伽马世界压暗、导演过场等每 tick 续期调用）。
    */
   public static void suppressFullbrightForTicks(int ticks) {
      if (ticks > dramaticSuppressTicks) {
         dramaticSuppressTicks = ticks;
      }
   }

   public static boolean hasBladeInInventory() {
      Player player = Minecraft.getInstance().player;
      if (player == null) {
         clearCache();
         return false;
      }
      if (AnnihilationBladeItemSupport.isHoldingAnnihilationBlade(player)) {
         return true;
      }
      ensureInventoryCache(player);
      return cachedHasBlade;
   }

   public static boolean hasVisionBladeInInventory() {
      Player player = Minecraft.getInstance().player;
      if (player == null) {
         clearCache();
         return false;
      }

      if (AnnihilationBladeItemSupport.isHoldingAnnihilationBlade(player)
            || InfinityStellarisItemSupport.isHoldingInfinityStellaris(player)) {
         return true;
      }

      ensureInventoryCache(player);
      return cachedHasBlade || cachedHasInfinityStellaris;
   }

   private static void ensureInventoryCache(Player player) {
      UUID playerId = player.getUUID();
      if (playerId.equals(lastPlayerId)
            && player.tickCount >= lastScanTick
            && player.tickCount - lastScanTick < INVENTORY_SCAN_INTERVAL) {
         return;
      }

      boolean hasAnnihilation = AnnihilationBladeItemSupport.isHoldingAnnihilationBlade(player);
      boolean hasInfinity = InfinityStellarisItemSupport.isHoldingInfinityStellaris(player);
      if (!hasAnnihilation || !hasInfinity) {
         for (ItemStack stack : player.getInventory().items) {
            if (!hasAnnihilation && AnnihilationBladeItemSupport.isAnnihilationBlade(stack)) {
               hasAnnihilation = true;
            }
            if (!hasInfinity && InfinityStellarisItemSupport.isInfinityStellaris(stack)) {
               hasInfinity = true;
            }
            if (hasAnnihilation && hasInfinity) {
               break;
            }
         }
      }
      cache(player, hasAnnihilation, hasInfinity);
   }

   @SubscribeEvent
   public static void onLoggingOut(LoggingOut event) {
      dramaticSuppressTicks = 0;
      clearCache();
      applyGammaBoost(Minecraft.getInstance(), false);
   }

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent event) {
      if (event.phase != Phase.END) {
         return;
      }

      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         dramaticSuppressTicks = 0;
         clearCache();
         applyGammaBoost(minecraft, false);
         return;
      }

      boolean suppressed = dramaticSuppressTicks > 0;
      if (dramaticSuppressTicks > 0) {
         dramaticSuppressTicks--;
      }

      boolean configEnabled = ModConfig.CLIENT.clientRenderer.bladeFullbrightVision.getValue();
      boolean shaderPackYield = FxCompat.shaderPackActive();
      boolean shouldBoost = configEnabled && !suppressed && !shaderPackYield && hasVisionBladeInInventory();
      applyGammaBoost(minecraft, shouldBoost);
   }

   private static void applyGammaBoost(Minecraft minecraft, boolean shouldBoost) {
      if (minecraft == null || minecraft.options == null) {
         return;
      }
      OptionInstance<Double> gamma = minecraft.options.gamma();
      double currentGamma = gamma.get();
      double targetGamma = gammaFieldLookupFailed ? 1.0D : FULL_BRIGHT_GAMMA;
      if (shouldBoost) {
         if (!gammaBoostActive) {
            savedGamma = currentGamma;
            gammaBoostActive = true;
         } else if (!sameGamma(currentGamma, targetGamma) && !sameGamma(currentGamma, savedGamma)) {
            savedGamma = currentGamma;
         }

         if (!sameGamma(currentGamma, targetGamma)) {
            setGammaValue(gamma, FULL_BRIGHT_GAMMA);
         }
      } else if (gammaBoostActive) {
         if (savedGamma >= 0.0D && sameGamma(currentGamma, targetGamma)) {
            setGammaValue(gamma, savedGamma);
         }

         gammaBoostActive = false;
         savedGamma = -1.0D;
      }
   }

   private static void setGammaValue(OptionInstance<Double> gamma, double value) {
      Field valueField = getGammaValueField(gamma);
      if (valueField == null) {
         gamma.set(clampGamma(value));
         return;
      }

      try {
         valueField.set(gamma, value);
      } catch (IllegalAccessException | IllegalArgumentException exception) {
         gammaValueField = null;
         gammaFieldLookupFailed = true;
         if (!gammaFieldWarningLogged) {
            Annihilationblade.LOGGER.warn("Failed to write gamma option value reflectively", exception);
            gammaFieldWarningLogged = true;
         }
         gamma.set(clampGamma(value));
      }
   }

   private static Field getGammaValueField(OptionInstance<Double> gamma) {
      if (gammaFieldLookupFailed) {
         return null;
      }
      if (gammaValueField != null && isGammaValueField(gammaValueField, gamma)) {
         return gammaValueField;
      }

      gammaValueField = null;
      for (Field field : gamma.getClass().getDeclaredFields()) {
         int modifiers = field.getModifiers();
         if (!Modifier.isStatic(modifiers) && canHoldGammaValue(field) && isGammaValueField(field, gamma)) {
            gammaValueField = field;
            return field;
         }
      }

      gammaFieldLookupFailed = true;
      if (!gammaFieldWarningLogged) {
         Annihilationblade.LOGGER.warn("Could not locate mutable OptionInstance value field for gamma fullbright");
         gammaFieldWarningLogged = true;
      }
      return null;
   }

   private static boolean canHoldGammaValue(Field field) {
      Class<?> type = field.getType();
      return type == double.class || type == Double.class || type == Object.class || Number.class.isAssignableFrom(type);
   }

   private static boolean isGammaValueField(Field field, OptionInstance<Double> gamma) {
      try {
         field.setAccessible(true);
         Object originalValue = field.get(gamma);
         if (!(originalValue instanceof Number number) || !sameGamma(number.doubleValue(), gamma.get())) {
            return false;
         }

         double probeValue = sameGamma(gamma.get(), 0.25D) ? 0.75D : 0.25D;
         field.set(gamma, probeValue);
         boolean updatesGamma = sameGamma(gamma.get(), probeValue);
         field.set(gamma, originalValue);
         return updatesGamma && sameGamma(gamma.get(), number.doubleValue());
      } catch (ReflectiveOperationException | RuntimeException exception) {
         return false;
      }
   }

   private static boolean sameGamma(double first, double second) {
      return Math.abs(first - second) < 1.0E-9D;
   }

   private static double clampGamma(double value) {
      return Math.max(0.0D, Math.min(1.0D, value));
   }

   private static void cache(Player player, boolean hasBlade, boolean hasInfinity) {
      lastPlayerId = player.getUUID();
      lastScanTick = player.tickCount;
      cachedHasBlade = hasBlade;
      cachedHasInfinityStellaris = hasInfinity;
   }

   private static void clearCache() {
      lastPlayerId = null;
      lastScanTick = Integer.MIN_VALUE;
      cachedHasBlade = false;
      cachedHasInfinityStellaris = false;
   }
}
