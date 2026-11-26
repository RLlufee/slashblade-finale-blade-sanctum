package QWQ.QingYi.annihilationblade.infinity_stellaris.item;

import QWQ.QingYi.annihilationblade.common.SlashBladeStateSupport;
import QWQ.QingYi.annihilationblade.infinity_stellaris.InfinityStellarisDefinitions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 无尽星空物品识别、服务端 10 tick 背包缓存以及 SE / SA 独立拆装判定工具。
 */
public final class InfinityStellarisItemSupport {
   private static final int INVENTORY_SCAN_INTERVAL = 10;
   private static final Map<UUID, Boolean> HAS_BLADE_CACHE = new HashMap<>();
   private static final Map<UUID, Integer> LAST_BLADE_SCAN_TICK = new HashMap<>();

   private InfinityStellarisItemSupport() {
   }

   public static boolean isInfinityStellaris(ItemStack stack) {
      return !stack.isEmpty()
         && stack.getItem() instanceof ItemSlashBlade
         && (InfinityStellarisDefinitions.DESCRIPTION_ID.equals(stack.getDescriptionId())
            || stack.hasTag() && stack.getTag().getBoolean(InfinityStellarisDefinitions.IDENTITY_TAG));
   }

   /** 严格判定主手是否持有无尽星空（用于近战攻击校验与主手友伤拦截，堵死副手越权）。 */
   public static boolean isMainHandInfinityStellaris(LivingEntity entity) {
      return entity != null && isInfinityStellaris(entity.getMainHandItem());
   }

   public static boolean isHoldingInfinityStellaris(LivingEntity entity) {
      return entity != null && (isInfinityStellaris(entity.getMainHandItem()) || isInfinityStellaris(entity.getOffhandItem()));
   }

   /**
    * 检测玩家背包中是否携带无尽星空。
    * 服务端自动启用 10 tick 扫描缓存（带 {@code tickCount >= lastScan} 防切档/重生锁死），主副手命中立即短路。
    */
   public static boolean hasInfinityStellarisInInventory(Player player) {
      if (player == null) {
         return false;
      }
      if (isHoldingInfinityStellaris(player)) {
         if (!player.level().isClientSide) {
            cacheBladeState(player, true);
         }
         return true;
      }

      if (!player.level().isClientSide) {
         UUID id = player.getUUID();
         Integer lastScan = LAST_BLADE_SCAN_TICK.get(id);
         if (lastScan != null && player.tickCount >= lastScan && player.tickCount - lastScan < INVENTORY_SCAN_INTERVAL) {
            return HAS_BLADE_CACHE.getOrDefault(id, false);
         }
         boolean found = scanInventory(player);
         cacheBladeState(player, found);
         return found;
      }

      return scanInventory(player);
   }

   private static boolean scanInventory(Player player) {
      for (ItemStack stack : player.getInventory().items) {
         if (isInfinityStellaris(stack)) {
            return true;
         }
      }
      return false;
   }

   private static void cacheBladeState(Player player, boolean hasBlade) {
      UUID id = player.getUUID();
      HAS_BLADE_CACHE.put(id, hasBlade);
      LAST_BLADE_SCAN_TICK.put(id, player.tickCount);
   }

   public static void clearCache(UUID playerId) {
      if (playerId != null) {
         HAS_BLADE_CACHE.remove(playerId);
         LAST_BLADE_SCAN_TICK.remove(playerId);
      }
   }

   public static void clearAllCaches() {
      HAS_BLADE_CACHE.clear();
      LAST_BLADE_SCAN_TICK.clear();
   }

   public static ItemStack heldInfinityStellaris(LivingEntity entity) {
      if (entity == null) {
         return ItemStack.EMPTY;
      }
      ItemStack mainHand = entity.getMainHandItem();
      if (isInfinityStellaris(mainHand)) {
         return mainHand;
      }

      ItemStack offHand = entity.getOffhandItem();
      return isInfinityStellaris(offHand) ? offHand : ItemStack.EMPTY;
   }

   /** 严格判定主手直接近战攻击（要求 directEntity == player 且来源为 player 攻击）。 */
   public static boolean isDirectInfinityAttack(Player player, DamageSource source) {
      return isMainHandInfinityStellaris(player)
         && source != null
         && source.getDirectEntity() == player
         && "player".equals(source.getMsgId());
   }

   public static boolean isInfinitySlashEntityAttack(Player player, Entity directSource) {
      return isSlashBladeEntity(directSource) && isHoldingInfinityStellaris(player);
   }

   public static boolean isSlashBladeEntity(Entity entity) {
      return entity != null && entity.getType().toString().contains("slashblade");
   }

   /** 校验单把拔刀剑是否处于有效态（未破损、未封印）且挂载指定 SE。 */
   public static boolean hasActiveSpecialEffect(ItemStack stack, ResourceLocation seId) {
      if (seId == null || !SlashBladeStateSupport.isSlashBlade(stack)) {
         return false;
      }
      return SlashBladeStateSupport.state(stack)
         .map(state -> !state.isBroken() && !state.isSealed() && state.hasSpecialEffect(seId))
         .orElse(false);
   }

   /** 校验单把拔刀剑是否处于有效态（未破损、未封印）且挂载指定 SA。 */
   public static boolean hasActiveSlashArt(ItemStack stack, ResourceLocation saId) {
      if (saId == null || !SlashBladeStateSupport.isSlashBlade(stack)) {
         return false;
      }
      return SlashBladeStateSupport.state(stack)
         .map(state -> !state.isBroken() && !state.isSealed() && saId.equals(state.getSlashArtsKey()))
         .orElse(false);
   }

   /** 校验玩家主/副手是否持有任意有效挂载指定 SE 的拔刀剑（供曲率气场与客户端按键检查）。 */
   public static boolean isHoldingActiveSpecialEffect(LivingEntity entity, ResourceLocation seId) {
      if (entity == null || seId == null) {
         return false;
      }
      return hasActiveSpecialEffect(entity.getMainHandItem(), seId)
         || hasActiveSpecialEffect(entity.getOffhandItem(), seId);
   }

   /** 获取玩家主/副手中有效挂载指定 SE 的拔刀剑状态（优先主手）。 */
   public static Optional<ISlashBladeState> findHeldActiveSpecialEffect(LivingEntity entity, ResourceLocation seId) {
      if (entity == null || seId == null) {
         return Optional.empty();
      }
      Optional<ISlashBladeState> main = activeSpecialEffectState(entity.getMainHandItem(), seId);
      if (main.isPresent()) {
         return main;
      }
      return activeSpecialEffectState(entity.getOffhandItem(), seId);
   }

   /**
    * 严格按攻击来源解析触发指定 SE 的有效拔刀剑状态（支持 SE 独立拆装，彻底杜绝副手越权）：
    * <ul>
    *   <li>直接近战攻击（{@code directSource == player && "player".equals(msgId)}）：仅允许主手拔刀剑；</li>
    *   <li>SlashBlade 刀光 / 幻影剑实体攻击：优先主手，其次副手；</li>
    *   <li>其他远程投射物（弓箭、雪球、三叉戟等）：拒绝触发。</li>
    * </ul>
    */
   public static Optional<ISlashBladeState> resolveAttackSpecialEffect(
      Player player, DamageSource source, Entity directSource, ResourceLocation seId
   ) {
      if (player == null || source == null || seId == null) {
         return Optional.empty();
      }
      boolean isDirectMelee = directSource == player && "player".equals(source.getMsgId());
      boolean isSlashEntity = isSlashBladeEntity(directSource);
      if (!isDirectMelee && !isSlashEntity) {
         return Optional.empty();
      }

      Optional<ISlashBladeState> main = activeSpecialEffectState(player.getMainHandItem(), seId);
      if (main.isPresent()) {
         return main;
      }
      if (!isDirectMelee) {
         return activeSpecialEffectState(player.getOffhandItem(), seId);
      }
      return Optional.empty();
   }

   /** 获取玩家主/副手中有效挂载指定 SA 的拔刀剑（优先主手）。 */
   public static ItemStack findHeldActiveSlashArtBlade(LivingEntity entity, ResourceLocation saId) {
      if (entity == null || saId == null) {
         return ItemStack.EMPTY;
      }
      if (hasActiveSlashArt(entity.getMainHandItem(), saId)) {
         return entity.getMainHandItem();
      }
      if (hasActiveSlashArt(entity.getOffhandItem(), saId)) {
         return entity.getOffhandItem();
      }
      return ItemStack.EMPTY;
   }

   private static Optional<ISlashBladeState> activeSpecialEffectState(ItemStack stack, ResourceLocation seId) {
      return SlashBladeStateSupport.state(stack)
         .filter(state -> !state.isBroken() && !state.isSealed() && state.hasSpecialEffect(seId));
   }
}
