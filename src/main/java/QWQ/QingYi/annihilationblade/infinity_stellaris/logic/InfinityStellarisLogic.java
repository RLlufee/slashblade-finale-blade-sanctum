package QWQ.QingYi.annihilationblade.infinity_stellaris.logic;

import QWQ.QingYi.annihilationblade.common.BladeFlightManager;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.VoidRescueSupport;
import QWQ.QingYi.annihilationblade.infinity_stellaris.InfinityStellarisDefinitions;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 无尽星空常驻护主、跨刀共享飞行、维度安全虚空救助、主手友伤拦截与生命周期清理中枢。
 * <p>
 * 已彻底移除旧版每 2 tick 发送 9 粒服务端龙息/附魔/末地烛的粒子海（Step 3 由纯客户端浑天仪星翼接管）。
 */
@EventBusSubscriber(modid = "annihilationblade")
public final class InfinityStellarisLogic {
   private static final int BLADE_REFRESH_INTERVAL = 20;
   private static final String FLIGHT_TOKEN = "infinity_stellaris";

   private InfinityStellarisLogic() {
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onLivingAttack(LivingAttackEvent event) {
      if (event.getEntity().level().isClientSide) {
         return;
      }

      // 1. 持刀者绝对免疫护主（背包内带 10 tick 缓存）
      if (event.getEntity() instanceof Player defender
            && InfinityStellarisItemSupport.hasInfinityStellarisInInventory(defender)) {
         event.setCanceled(true);
         restoreFinalWeaponBearer(defender);
         return;
      }

      // 2. 主手友伤基础伤害拦截（防止 150 基础面板平 A 或刀光误伤队友/驯服宠物）
      if (event.getSource().getEntity() instanceof Player attacker
            && (InfinityStellarisItemSupport.isMainHandInfinityStellaris(attacker)
               || InfinityStellarisItemSupport.isInfinitySlashEntityAttack(attacker, event.getSource().getDirectEntity()))
            && !SlashBladeTargeting.canAttack(attacker, event.getEntity())) {
         event.setCanceled(true);
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onLivingHurt(LivingHurtEvent event) {
      if (!event.getEntity().level().isClientSide && event.getEntity() instanceof Player player
            && InfinityStellarisItemSupport.hasInfinityStellarisInInventory(player)) {
         event.setCanceled(true);
         restoreFinalWeaponBearer(player);
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onLivingDeath(LivingDeathEvent event) {
      if (!event.getEntity().level().isClientSide && event.getEntity() instanceof Player player
            && InfinityStellarisItemSupport.hasInfinityStellarisInInventory(player)) {
         event.setCanceled(true);
         restoreFinalWeaponBearer(player);
         player.deathTime = 0;
      }
   }

   @SubscribeEvent
   public static void onPlayerTick(PlayerTickEvent event) {
      if (event.phase != Phase.END || event.player.level().isClientSide) {
         return;
      }

      Player player = event.player;
      boolean holding = InfinityStellarisItemSupport.isHoldingInfinityStellaris(player);
      boolean inInventory = InfinityStellarisItemSupport.hasInfinityStellarisInInventory(player);

      if (holding && player.tickCount % BLADE_REFRESH_INTERVAL == 0) {
         refreshHeldStats(player);
      }

      if (inInventory) {
         if (player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
         }
         player.getFoodData().setFoodLevel(20);
         player.getFoodData().setSaturation(20.0F);
         player.fallDistance = 0.0F;
         BladeFlightManager.syncToken(player, FLIGHT_TOKEN, true);
         VoidRescueSupport.rescueIfInVoid(player);
      } else {
         BladeFlightManager.syncToken(player, FLIGHT_TOKEN, false);
      }
   }

   @SubscribeEvent
   public static void onPlayerLogout(PlayerLoggedOutEvent event) {
      if (!event.getEntity().level().isClientSide) {
         clearPlayer(event.getEntity());
      }
   }

   @SubscribeEvent
   public static void onPlayerChangedDimension(PlayerChangedDimensionEvent event) {
      if (!event.getEntity().level().isClientSide) {
         // 跨维仅清背包缓存与技能状态，不关闭 BladeFlightManager 飞行防止过传送门断飞坠落
         clearPlayer(event.getEntity());
      }
   }

   @SubscribeEvent
   public static void onPlayerRespawn(PlayerRespawnEvent event) {
      if (!event.getEntity().level().isClientSide) {
         InfinityStellarisItemSupport.clearCache(event.getEntity().getUUID());
      }
   }

   @SubscribeEvent
   public static void onPlayerClone(PlayerEvent.Clone event) {
      if (!event.getEntity().level().isClientSide) {
         InfinityStellarisItemSupport.clearCache(event.getEntity().getUUID());
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      InfinityStellarisItemSupport.clearAllCaches();
      EntropyDissolutionLogic.clearAll();
      CosmicStringCutLogic.clearAll();
      GammaThunderburstLogic.clearAll();
      CurvatureRuptureLogic.clearAll();
      VacuumDecayCollapseLogic.clearAll();
   }

   private static void refreshHeldStats(Player player) {
      if (InfinityStellarisItemSupport.isInfinityStellaris(player.getMainHandItem())) {
         InfinityStellarisDefinitions.ensureStats(player.getMainHandItem(), player.level());
      }
      if (InfinityStellarisItemSupport.isInfinityStellaris(player.getOffhandItem())) {
         InfinityStellarisDefinitions.ensureStats(player.getOffhandItem(), player.level());
      }
   }

   private static void clearPlayer(Player player) {
      UUID id = player.getUUID();
      InfinityStellarisItemSupport.clearCache(id);
      EntropyDissolutionLogic.clearPlayer(id);
      CosmicStringCutLogic.clearPlayer(player);
      GammaThunderburstLogic.clearPlayer(id);
      CurvatureRuptureLogic.clearPlayer(player);
      VacuumDecayCollapseLogic.clearPlayer(id);
   }

   private static void restoreFinalWeaponBearer(Player player) {
      player.invulnerableTime = Math.max(player.invulnerableTime, 20);
      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
      player.deathTime = 0;
   }
}
