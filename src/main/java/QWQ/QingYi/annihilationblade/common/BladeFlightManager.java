package QWQ.QingYi.annihilationblade.common;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 全局跨刀共享飞行权限（{@code mayfly}）引用计数管理器。
 * <p>
 * 供「湮灭之刃」「无尽星空」等赋予生存飞行的武器共享使用：
 * <ul>
 *   <li>按武器来源 {@code token}（如 {@code "annihilation_blade"}、{@code "infinity_stellaris"}）维护引用集合；</li>
 *   <li>仅当首次由本模组开启飞行时标记 {@code grantedByMod = true}，避免释放时误剥夺创造模式或其他飞行模组的飞行能力；</li>
 *   <li>仅当该玩家的全部武器 {@code token} 归零时才关闭飞行，彻底根除背包同时携带多把神刀时扔掉其中一把导致断飞 1 tick 的互踩问题；</li>
 *   <li>跨维度时不主动清零 {@code mayfly}，避免过传送门瞬间高空坠落。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Annihilationblade.MODID)
public final class BladeFlightManager {
   private static final Map<UUID, FlightEntry> ENTRIES = new HashMap<>();

   private BladeFlightManager() {
   }

   /**
    * 幂等同步指定武器对玩家的飞行持锁状态。
    *
    * @param player 目标玩家（仅服务端生效）
    * @param token  武器唯一标识（如 {@code "infinity_stellaris"}）
    * @param active 是否应当持有飞行权限
    */
   public static void syncToken(Player player, String token, boolean active) {
      if (player == null || token == null || player.level().isClientSide) {
         return;
      }
      if (active) {
         acquire(player, token);
      } else {
         release(player, token);
      }
   }

   public static void acquire(Player player, String token) {
      if (player == null || token == null || player.level().isClientSide) {
         return;
      }
      UUID id = player.getUUID();
      FlightEntry entry = ENTRIES.computeIfAbsent(id, ignored -> new FlightEntry());
      entry.tokens.add(token);

      if (!player.isCreative() && !player.isSpectator() && !player.getAbilities().mayfly) {
         player.getAbilities().mayfly = true;
         entry.grantedByMod = true;
         player.onUpdateAbilities();
      }
   }

   public static void release(Player player, String token) {
      if (player == null || token == null || player.level().isClientSide) {
         return;
      }
      UUID id = player.getUUID();
      FlightEntry entry = ENTRIES.get(id);
      if (entry == null || !entry.tokens.remove(token)) {
         return;
      }
      if (!entry.tokens.isEmpty()) {
         return;
      }

      ENTRIES.remove(id);
      if (entry.grantedByMod && !player.isCreative() && !player.isSpectator()) {
         if (player.getAbilities().mayfly || player.getAbilities().flying) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
         }
      }
   }

   /** 查询该玩家当前是否被本模组任意武器持锁赋予飞行。 */
   public static boolean hasAnyToken(UUID playerId) {
      FlightEntry entry = playerId == null ? null : ENTRIES.get(playerId);
      return entry != null && !entry.tokens.isEmpty();
   }

   public static void clearPlayer(UUID playerId) {
      if (playerId != null) {
         ENTRIES.remove(playerId);
      }
   }

   @SubscribeEvent
   public static void onPlayerLogout(PlayerLoggedOutEvent event) {
      if (!event.getEntity().level().isClientSide) {
         ENTRIES.remove(event.getEntity().getUUID());
      }
   }

   @SubscribeEvent
   public static void onPlayerRespawn(PlayerRespawnEvent event) {
      if (!event.getEntity().level().isClientSide) {
         ENTRIES.remove(event.getEntity().getUUID());
      }
   }

   @SubscribeEvent
   public static void onPlayerClone(PlayerEvent.Clone event) {
      if (!event.getEntity().level().isClientSide) {
         ENTRIES.remove(event.getEntity().getUUID());
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      ENTRIES.clear();
   }

   private static final class FlightEntry {
      final Set<String> tokens = new HashSet<>();
      boolean grantedByMod;
   }
}
