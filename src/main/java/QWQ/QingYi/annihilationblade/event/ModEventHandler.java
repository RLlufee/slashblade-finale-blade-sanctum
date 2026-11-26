package QWQ.QingYi.annihilationblade.event;

import QWQ.QingYi.annihilationblade.annihilation_blade.AnnihilationBladeDefinitions;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.AnnihilationInputRouter;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.WorldRiftChain;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.CausalityCollapse;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.Dankong;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.PhantomJudgement;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.TerminusEcho;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.VoidDominion;
import QWQ.QingYi.annihilationblade.blood_prison.logic.BloodPrisonLogic;
import QWQ.QingYi.annihilationblade.common.BladeFlightManager;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.common.VoidRescueSupport;
import QWQ.QingYi.annihilationblade.infinity_stellaris.logic.CosmicStringCutLogic;
import QWQ.QingYi.annihilationblade.infinity_stellaris.logic.CurvatureRuptureLogic;
import QWQ.QingYi.annihilationblade.infinity_stellaris.logic.EntropyDissolutionLogic;
import QWQ.QingYi.annihilationblade.infinity_stellaris.logic.GammaThunderburstLogic;
import QWQ.QingYi.annihilationblade.infinity_stellaris.logic.VacuumDecayCollapseLogic;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber(modid = "annihilationblade")
public class ModEventHandler {
   private static final int INVENTORY_SCAN_INTERVAL = 10;
   private static final int BLADE_REFRESH_INTERVAL = 20;
   private static final String FLIGHT_TOKEN = "annihilation_blade";
   private static final Map<UUID, Boolean> HAS_BLADE_CACHE = new HashMap<>();
   private static final Map<UUID, Integer> LAST_BLADE_SCAN_TICK = new HashMap<>();

   private static boolean isGodBlade(ItemStack stack) {
      return SpecialEffectSupport.isAnnihilationBlade(stack);
   }

   private static boolean hasBladeInInventory(Player player) {
      if (!isGodBlade(player.getMainHandItem()) && !isGodBlade(player.getOffhandItem())) {
         UUID id = player.getUUID();
         Integer lastScan = LAST_BLADE_SCAN_TICK.get(id);
         if (lastScan != null && player.tickCount >= lastScan && player.tickCount - lastScan < INVENTORY_SCAN_INTERVAL) {
            return HAS_BLADE_CACHE.getOrDefault(id, false);
         }

         for (ItemStack stack : player.getInventory().items) {
            if (isGodBlade(stack)) {
               cacheBladeState(player, true);
               return true;
            }
         }

         cacheBladeState(player, false);
         return false;
      } else {
         cacheBladeState(player, true);
         return true;
      }
   }

   private static void cacheBladeState(Player player, boolean hasBlade) {
      UUID id = player.getUUID();
      HAS_BLADE_CACHE.put(id, hasBlade);
      LAST_BLADE_SCAN_TICK.put(id, player.tickCount);
   }

   private static void refreshBladeGrowthData(Player player) {
      refreshBladeGrowthData(player.getMainHandItem(), player);
      refreshBladeGrowthData(player.getOffhandItem(), player);

      for (ItemStack stack : player.getInventory().items) {
         refreshBladeGrowthData(stack, player);
      }
   }

   private static void refreshBladeGrowthData(ItemStack stack, Player player) {
      if (isGodBlade(stack)) {
         AnnihilationBladeDefinitions.ensureStats(stack, player.level());
      }
   }

   private static boolean hasGodBladeInHands(Player player) {
      return isGodBlade(player.getMainHandItem()) || isGodBlade(player.getOffhandItem());
   }

   private static boolean isDirectGodBladeAttack(DamageSource source, Player player) {
      return isGodBlade(player.getMainHandItem()) && source.getDirectEntity() == player && "player".equals(source.getMsgId());
   }

   private static boolean isGodBladeSlashEntityAttack(Entity directSource, Player player) {
      return directSource != null && directSource.getType().toString().contains("slashblade") && hasGodBladeInHands(player);
   }

   @SubscribeEvent
   @OnlyIn(Dist.CLIENT)
   public static void onItemTooltip(ItemTooltipEvent event) {
      ItemStack stack = event.getItemStack();
      if (isGodBlade(stack)) {
         List<Component> tooltip = event.getToolTip();
         removeVanillaAttackLines(tooltip);
         tooltip.add(Component.literal(" "));
         tooltip.add(buildRainbowDamageLine());
         if (!tooltip.isEmpty()) {
            tooltip.add(1, Component.literal(" "));
            tooltip.add(
               1,
               Component.translatable("item.annihilationblade.desc.line4").withStyle(new ChatFormatting[]{ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC})
            );
            tooltip.add(
               1,
               Component.translatable("item.annihilationblade.desc.line3").withStyle(new ChatFormatting[]{ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC})
            );
            tooltip.add(
               1,
               Component.translatable("item.annihilationblade.desc.line2").withStyle(new ChatFormatting[]{ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC})
            );
            tooltip.add(
               1,
               Component.translatable("item.annihilationblade.desc.line1").withStyle(new ChatFormatting[]{ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC})
            );
            tooltip.add(1, Component.literal(" "));
            tooltip.add(1, Component.translatable("item.annihilationblade.passive"));
         }
      }
   }

   private static void removeVanillaAttackLines(List<Component> tooltip) {
      Iterator<Component> it = tooltip.iterator();

      while (it.hasNext()) {
         String text = it.next().getString();
         if (text.contains("攻击伤害")
            || text.contains("攻擊傷害")
            || text.contains("Attack Damage")
            || text.contains("在主手时")
            || text.contains("在慣用手時")
            || text.contains("When in main hand")
            || text.contains("When in Main Hand")
            || text.contains("攻击速度")
            || text.contains("攻擊速度")
            || text.contains("Attack Speed")
            || text.contains("攻击范围")
            || text.contains("攻擊範圍")
            || text.contains("攻击距离")
            || text.contains("攻擊距離")
            || text.contains("Attack Range")
            || text.contains("Entity Reach")) {
            it.remove();
         }
      }
   }

   private static MutableComponent buildRainbowDamageLine() {
      MutableComponent rainbowText = Component.literal(" ");
      String rawText = Component.translatable("item.annihilationblade.infinite_damage").getString();
      long time = System.currentTimeMillis();

      for (int i = 0; i < rawText.length(); i++) {
         float hue = ((float)(time % 3000L) / 3000.0F + i * 0.08F) % 1.0F;
         int rgb = Mth.hsvToRgb(hue, 1.0F, 1.0F);
         rainbowText.append(Component.literal(String.valueOf(rawText.charAt(i))).withStyle(style -> style.withColor(rgb)));
      }

      return rainbowText;
   }

   @SubscribeEvent(priority = EventPriority.LOWEST)
   public static void onHurt(LivingHurtEvent event) {
      if (TerminusLogic.isExecuting() || WorldRiftChain.isExecutingChain()) {
         return;
      }

      if (BloodPrisonLogic.isPhantomBurstDamage(event.getEntity())
            || EntropyDissolutionLogic.isInternalExecution(event.getEntity())
            || CosmicStringCutLogic.isInternalDamage(event.getEntity())
            || GammaThunderburstLogic.isInternalDamage(event.getEntity())
            || CurvatureRuptureLogic.isInternalDamage(event.getEntity())
            || VacuumDecayCollapseLogic.isInternalDamage(event.getEntity())) {
         return;
      }

      Entity source = event.getSource().getEntity();
      Entity directSource = event.getSource().getDirectEntity();
      if (source instanceof ServerPlayer player && SlashBladeTargeting.canAttack(player, event.getEntity())) {
         boolean isDirect = directSource == player && "player".equals(event.getSource().getMsgId());
         boolean isSlashEntity = directSource != null && directSource.getType().toString().contains("slashblade");
         if (isDirect || isSlashEntity) {
            // 开放给任意挂载 WORLD_RIFT 的拔刀剑（支持 SE 独立拆出移植）
            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();
            ISlashBladeState mainState = main.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
            ISlashBladeState state = (mainState != null && !mainState.isBroken() && !mainState.isSealed()
                  && mainState.hasSpecialEffect(ModSpecialEffects.WORLD_RIFT.getId()))
               ? mainState
               : (!isDirect ? off.getCapability(ItemSlashBlade.BLADESTATE).orElse(null) : null);
            // 仅在刀上挂载 WORLD_RIFT 且断空处于关闭状态（非激活、且配置/Shift/Ctrl 关闭或无断空）时走独立单链裂界
            if (state != null
                  && !state.isBroken()
                  && !state.isSealed()
                  && state.hasSpecialEffect(ModSpecialEffects.WORLD_RIFT.getId())
                  && !Dankong.isActive(player)
                  && Dankong.isDisabledFor(player, state)
                  && event.getEntity().level() instanceof ServerLevel level) {
               WorldRiftChain.tryTriggerFromHit(level, player, event);
            }
         }
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onGodBladeAttack(LivingAttackEvent event) {
      if (event.getSource().getEntity() instanceof Player player
         && isGodBlade(player.getMainHandItem())
         && !SlashBladeTargeting.canAttack(player, event.getEntity())) {
         event.setCanceled(true);
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onLivingAttack(LivingAttackEvent event) {
      if (!event.getEntity().level().isClientSide && event.getEntity() instanceof ServerPlayer player) {
         if (hasBladeInInventory(player)) {
            event.setCanceled(true);
            TerminusEcho.onAbsorbedAttack(player, event.getSource(), event.getAmount(), null, false);
         } else {
            TerminusEcho.tryInterceptExternalAttack(player, event);
         }
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onLivingDeath(LivingDeathEvent event) {
      if (!event.getEntity().level().isClientSide) {
         if (event.getEntity() instanceof Player player && hasBladeInInventory(player)) {
            event.setCanceled(true);
            player.setHealth(player.getMaxHealth());
         }
      }
   }

   @SubscribeEvent
   public static void onPlayerTick(PlayerTickEvent event) {
      if (event.phase == Phase.END) {
         Player player = event.player;
         if (!player.level().isClientSide) {
            UUID key = player.getUUID();
            boolean hasBlade = hasBladeInInventory(player);
            if (hasBlade && player.tickCount % BLADE_REFRESH_INTERVAL == 0) {
               refreshBladeGrowthData(player);
            }

            if (hasBlade) {
               if (player.getHealth() < player.getMaxHealth()) {
                  player.setHealth(player.getMaxHealth());
               }

               player.getFoodData().setFoodLevel(20);
               player.getFoodData().setSaturation(20.0F);
               BladeFlightManager.syncToken(player, FLIGHT_TOKEN, true);
               VoidRescueSupport.rescueIfInVoid(player);
            } else {
               BladeFlightManager.syncToken(player, FLIGHT_TOKEN, false);
            }
         }
      }
   }

   @SubscribeEvent
   public static void onPlayerLogout(PlayerLoggedOutEvent event) {
      clearPlayerState(event.getEntity());
      Dankong.clearBlinkMode(event.getEntity().getUUID());
   }

   @SubscribeEvent
   public static void onPlayerChangedDimension(PlayerChangedDimensionEvent event) {
      clearPlayerState(event.getEntity());
   }

   @SubscribeEvent
   public static void onPlayerRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
      UUID id = event.getEntity().getUUID();
      HAS_BLADE_CACHE.remove(id);
      LAST_BLADE_SCAN_TICK.remove(id);
   }

   @SubscribeEvent
   public static void onPlayerClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
      UUID id = event.getEntity().getUUID();
      HAS_BLADE_CACHE.remove(id);
      LAST_BLADE_SCAN_TICK.remove(id);
   }

   @SubscribeEvent
   public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
      HAS_BLADE_CACHE.clear();
      LAST_BLADE_SCAN_TICK.clear();
   }

   private static void clearPlayerState(Player player) {
      UUID id = player.getUUID();
      HAS_BLADE_CACHE.remove(id);
      LAST_BLADE_SCAN_TICK.remove(id);
      AnnihilationInputRouter.clearPlayer(id);
      Dankong.clearPlayer(id);
      WorldRiftChain.clearPlayer(id);
      TerminusEcho.clearPlayer(id);
      PhantomJudgement.clearPlayer(player.level(), id);
      CausalityCollapse.clearPlayer(id);
      VoidDominion.clearPlayer(id);
   }
}
