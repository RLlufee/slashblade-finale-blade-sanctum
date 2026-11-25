package QWQ.QingYi.annihilationblade.common;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 全局统一的实体悬停 / 定身（{@code NoGravity} / {@code NoAi}）引用计数管理器。
 * <p>
 * 供「虚无权域」「裂界」「因果坍缩」「归墟回响」四个 SE 共享使用：
 * <ul>
 *   <li>每个实体仅在首次进入受控态时拍摄一份不可变原始状态快照，后续其他 SE 叠加持锁绝不覆写原始值；</li>
 *   <li>按来源 {@code token} 分别追踪无重力持锁与禁 AI 持锁，任一维度计数归零即精确还原对应原始属性；</li>
 *   <li>统一在服务端监听实体死亡、离开维度与停服事件，避免跨 SE 竞态或异常退出留下永久失重/木桩生物。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Annihilationblade.MODID)
public final class EntitySuspensionManager {
   private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

   private EntitySuspensionManager() {
   }

   /**
    * 为指定实体登记持锁并施加所需控制状态。
    *
    * @param target         目标实体（仅服务端生效）
    * @param token          调用方唯一持锁标识（例如 {@code "void_dominion:" + ownerId}）
    * @param disableGravity 是否锁定无重力
    * @param disableAi      是否锁定禁 AI（仅对 {@link Mob} 生效）
    */
   public static void acquire(LivingEntity target, String token, boolean disableGravity, boolean disableAi) {
      if (target == null || token == null || target.level().isClientSide() || (!disableGravity && !disableAi)) {
         return;
      }
      UUID uuid = target.getUUID();
      Entry entry = ENTRIES.get(uuid);
      if (entry == null) {
         entry = new Entry(
            target,
            target.isNoGravity(),
            target instanceof Mob mob && mob.isNoAi(),
            target.getDeltaMovement()
         );
         ENTRIES.put(uuid, entry);
      } else {
         entry.entity = target;
      }

      if (disableGravity) {
         entry.gravityTokens.add(token);
         target.setNoGravity(true);
      }
      if (disableAi && target instanceof Mob mob) {
         entry.aiTokens.add(token);
         entry.restoreVelocityOnAiRelease = true;
         mob.setNoAi(true);
      }
   }

   /**
    * 释放指定 {@code token} 对目标实体的持锁；仅当对应维度持锁归零时才写回原始状态。
    */
   public static void release(UUID entityUuid, @Nullable LivingEntity resolved, String token) {
      if (entityUuid == null || token == null) {
         return;
      }
      Entry entry = ENTRIES.get(entityUuid);
      if (entry == null) {
         return;
      }
      if (resolved != null) {
         entry.entity = resolved;
      }

      boolean removedGravity = entry.gravityTokens.remove(token);
      boolean removedAi = entry.aiTokens.remove(token);
      if (!removedGravity && !removedAi) {
         return;
      }

      LivingEntity target = entry.entity;
      if (removedGravity && entry.gravityTokens.isEmpty()) {
         target.setNoGravity(entry.originalNoGravity);
         if (target.isAlive() && !target.isRemoved()) {
            target.hurtMarked = true;
            target.hasImpulse = true;
         }
      }
      if (removedAi && entry.aiTokens.isEmpty()) {
         if (target instanceof Mob mob) {
            mob.setNoAi(entry.originalNoAi);
         }
         if (entry.restoreVelocityOnAiRelease && target.isAlive() && !target.isRemoved()) {
            target.setDeltaMovement(entry.originalVelocity);
            target.hurtMarked = true;
            target.hasImpulse = true;
         }
      }

      if (entry.gravityTokens.isEmpty() && entry.aiTokens.isEmpty()) {
         ENTRIES.remove(entityUuid);
      }
   }

   /** 实体死亡或离界时一次性清除全部持锁并还原原始状态。 */
   public static void releaseAll(LivingEntity target) {
      if (target == null || target.level().isClientSide()) {
         return;
      }
      Entry entry = ENTRIES.remove(target.getUUID());
      if (entry != null) {
         restoreEntry(entry, target);
      }
   }

   /** 该实体当前是否被任意 SE 持锁（含重力或 AI）。 */
   public static boolean isManaged(UUID entityUuid) {
      return entityUuid != null && ENTRIES.containsKey(entityUuid);
   }

   /** 该实体当前是否被带有指定前缀的 SE 持锁（例如 {@code "void_dominion:"}）。 */
   public static boolean isHeldByPrefix(UUID entityUuid, String prefix) {
      if (entityUuid == null || prefix == null) {
         return false;
      }
      Entry entry = ENTRIES.get(entityUuid);
      if (entry == null) {
         return false;
      }
      for (String token : entry.gravityTokens) {
         if (token.startsWith(prefix)) {
            return true;
         }
      }
      for (String token : entry.aiTokens) {
         if (token.startsWith(prefix)) {
            return true;
         }
      }
      return false;
   }

   @SubscribeEvent
   public static void onLivingDeath(LivingDeathEvent event) {
      if (event.getEntity().level().isClientSide()) {
         return;
      }
      releaseAll(event.getEntity());
   }

   @SubscribeEvent
   public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
      if (event.getLevel().isClientSide()) {
         return;
      }
      if (event.getEntity() instanceof LivingEntity living) {
         releaseAll(living);
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (Entry entry : ENTRIES.values()) {
         restoreEntry(entry, entry.entity);
      }
      ENTRIES.clear();
   }

   private static void restoreEntry(Entry entry, LivingEntity target) {
      if (target == null) {
         return;
      }
      if (!entry.gravityTokens.isEmpty()) {
         target.setNoGravity(entry.originalNoGravity);
      }
      if (!entry.aiTokens.isEmpty() && target instanceof Mob mob) {
         mob.setNoAi(entry.originalNoAi);
      }
      if (entry.restoreVelocityOnAiRelease && target.isAlive() && !target.isRemoved()) {
         target.setDeltaMovement(entry.originalVelocity);
         target.hurtMarked = true;
         target.hasImpulse = true;
      }
   }

   private static final class Entry {
      LivingEntity entity;
      final boolean originalNoGravity;
      final boolean originalNoAi;
      final Vec3 originalVelocity;
      final Set<String> gravityTokens = new HashSet<>();
      final Set<String> aiTokens = new HashSet<>();
      boolean restoreVelocityOnAiRelease;

      Entry(LivingEntity entity, boolean originalNoGravity, boolean originalNoAi, Vec3 originalVelocity) {
         this.entity = entity;
         this.originalNoGravity = originalNoGravity;
         this.originalNoAi = originalNoAi;
         this.originalVelocity = originalVelocity;
      }
   }
}
