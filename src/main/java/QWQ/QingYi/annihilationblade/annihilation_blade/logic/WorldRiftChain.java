package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 湮灭之刃 SE「裂界·神威绞杀」服务端权威状态机。
 * <p>
 * 负责两条互补的战斗链路：
 * <ol>
 *   <li><b>独立单链模式（断空处于关闭状态）</b>：命中目标 {@code A} 时触发路线 A（先钳制致命首击保留活体并定身扭曲，
 *       到达 {@link WorldRiftPattern#SNAP_TICK} 时同步拧断处决并静默清理非玩家残尸，{@link WorldRiftPattern#HOP_TICK} 时跃迁至邻域内最近的下一个目标 {@code B}，
 *       循环直至达到 {@code chainCount} 或无合法目标）。</li>
 *   <li><b>断空联动模式（断空开启斩杀）</b>：断空每次 {@code strikeTarget} 处决目标前先记录并下发预抓拍事件；
 *       断空收刀齐斩后延迟 {@link WorldRiftPattern#LINKAGE_DELAY_TICKS}，对所有被断空斩杀的目标残骸统一发起错峰神威扭杀。</li>
 * </ol>
 */
@EventBusSubscriber(modid = "annihilationblade")
public final class WorldRiftChain {
   private static final Map<UUID, Long> LAST_TRIGGER = new HashMap<>();
   private static final Map<UUID, SingleChainSequence> ACTIVE_CHAINS = new HashMap<>();
   private static final Map<UUID, TargetSuspension> SUSPENSIONS = new HashMap<>();
   private static final Map<UUID, LinkageBuffer> PREPARING_LINKAGES = new HashMap<>();
   private static final List<PendingLinkage> PENDING_LINKAGES = new ArrayList<>();
   private static boolean executingChain;

   private WorldRiftChain() {
   }

   public static boolean isExecutingChain() {
      return executingChain;
   }

   /** 玩家离线、换维度或死亡时清理其全部裂界活跃态与未爆发联动。 */
   public static void clearPlayer(UUID playerId) {
      SingleChainSequence chain = ACTIVE_CHAINS.remove(playerId);
      if (chain != null) {
         releaseCurrent(chain);
         AnnihilationFxEvents.worldRiftEnd(chain.handle);
      }
      cancelDankongLinkage(playerId);
      LAST_TRIGGER.remove(playerId);
   }

   // ───────────────────────────── 独立模式：断空关闭时的单链跳跃（路线 A） ─────────────────────────────

   /**
    * 命中事件入口（仅在已确认刀上持有 WORLD_RIFT 且断空处于关闭状态时由 {@code ModEventHandler} 调用）。
    */
   public static void tryTriggerFromHit(ServerLevel level, ServerPlayer player, LivingHurtEvent event) {
      if (executingChain) {
         return;
      }
      ModConfig.WorldRift config = ModConfig.COMMON.annihilationBlade.worldRift;
      if (!config.enabled.getValue()) {
         return;
      }
      LivingEntity target = event.getEntity();
      if (target == null || !target.isAlive() || !SlashBladeTargeting.canAttack(player, target)) {
         return;
      }
      if (ACTIVE_CHAINS.containsKey(player.getUUID()) || SUSPENSIONS.containsKey(target.getUUID())) {
         return;
      }
      if (!SpecialEffectSupport.tryStartCooldown(LAST_TRIGGER, player, level.getGameTime(), config.cooldownTicks.getValue())) {
         return;
      }

      // 路线 A 保底：非玩家目标若将被 50 面板直击直接秒杀，则保留 1 HP 进入活体定身扭曲阶段，
      // 到 SNAP_TICK 再由 TerminusLogic.executeSilent 拧断抹除，避免首帧直接倒地红尸。
      if (!(target instanceof Player) && event.getAmount() >= target.getHealth() - 0.5F) {
         event.setAmount(Math.max(0.0F, target.getHealth() - 1.0F));
      }

      int maxChain = Math.max(1, config.chainCount.getValue());
      double radius = config.radius.getValue();
      double visualScale = config.visualScale.getValue();
      Vec3 attackerOrigin = SpecialEffectSupport.centerOf(player);
      Vec3 center = WorldRiftPattern.vortexCenter(target);
      long seed = level.getRandom().nextLong();
      int duration = WorldRiftPattern.singleChainDuration(maxChain);

      int handle = AnnihilationFxEvents.worldRiftStart(
         level, player, center, Vec3.ZERO, radius, visualScale,
         AnnihilationFxEvents.RIFT_SINGLE_START, 0, maxChain, seed,
         new int[]{target.getId()}, new int[]{WorldRiftPattern.SNAP_TICK}, duration
      );

      SingleChainSequence sequence = new SingleChainSequence(
         player, level, handle, attackerOrigin, radius, visualScale, maxChain, seed,
         target.getId(), target.getUUID(), center
      );
      sequence.visited.add(target.getUUID());
      suspendTarget(sequence, target);
      ACTIVE_CHAINS.put(player.getUUID(), sequence);
   }

   // ───────────────────────────── 断空联动模式：收刀齐斩后残骸神威扭杀 ─────────────────────────────

   /**
    * 断空每次闪现斩击 {@code target} 之前调用（必须在 {@code TerminusLogic.executeSilent} 之前，确保客户端能抓拍活体模型）。
    */
   public static void recordDankongStrike(ServerPlayer player, ServerLevel level, LivingEntity target, Vec3 dankongOrigin, Vec3 slashDir) {
      ModConfig.WorldRift config = ModConfig.COMMON.annihilationBlade.worldRift;
      if (!config.enabled.getValue() || !config.dankongLinkage.getValue()) {
         return;
      }
      int maxTargets = Math.max(1, Math.min(FxEventPacketCap.MAX_LINKAGE_TARGETS, config.maxTargets.getValue()));
      LinkageBuffer buffer = PREPARING_LINKAGES.get(player.getUUID());
      if (buffer != null && buffer.level != level) {
         AnnihilationFxEvents.worldRiftEnd(buffer.handle);
         PREPARING_LINKAGES.remove(player.getUUID());
         buffer = null;
      }

      Vec3 center = WorldRiftPattern.vortexCenter(target);
      Vec3 normDir = slashDir.lengthSqr() < 1.0E-5 ? player.getLookAngle() : slashDir.normalize();
      double radius = config.radius.getValue();
      double visualScale = config.visualScale.getValue();

      if (buffer == null) {
         long seed = level.getRandom().nextLong();
         // 预留充裕寿命覆盖高 stepInterval 断空连斩全程 + 收刀齐斩 + 神威扭杀演出
         int handle = AnnihilationFxEvents.worldRiftStart(
            level, player, center, normDir, radius, visualScale,
            AnnihilationFxEvents.RIFT_LINK_PREPARE, 0, maxTargets, seed,
            new int[]{target.getId()}, new int[0], 600
         );
         buffer = new LinkageBuffer(player, level, handle, dankongOrigin, radius, visualScale, seed, maxTargets);
         PREPARING_LINKAGES.put(player.getUUID(), buffer);
      } else {
         if (buffer.records.size() >= buffer.maxTargets || buffer.seenIds.contains(target.getId())) {
            return;
         }
         AnnihilationFxEvents.worldRiftUpdate(
            buffer.handle, level, player, center, normDir, buffer.radius, buffer.visualScale,
            AnnihilationFxEvents.RIFT_LINK_PREPARE, buffer.records.size(), buffer.maxTargets, buffer.seed,
            new int[]{target.getId()}, new int[0]
         );
      }

      buffer.seenIds.add(target.getId());
      buffer.records.add(new LinkageTarget(target.getId(), center));
   }

   /**
    * 断空 {@code finishSequence} 回到起点收刀时调用：排期在齐斩错位结束（11 tick）后同步引爆全部已抓拍残骸的神威扭杀。
    */
   public static void scheduleDankongLinkage(ServerPlayer player, Vec3 dankongOrigin) {
      LinkageBuffer buffer = PREPARING_LINKAGES.remove(player.getUUID());
      if (buffer == null) {
         return;
      }
      if (buffer.records.isEmpty() || !ModConfig.COMMON.annihilationBlade.worldRift.enabled.getValue()) {
         AnnihilationFxEvents.worldRiftEnd(buffer.handle);
         return;
      }
      PENDING_LINKAGES.add(new PendingLinkage(buffer, dankongOrigin, WorldRiftPattern.LINKAGE_DELAY_TICKS));
   }

   /** 断空异常中止（死亡/离线/空挥取消）时撤掉尚未引爆的预抓拍缓冲区。 */
   public static void cancelDankongLinkage(UUID playerId) {
      LinkageBuffer buffer = PREPARING_LINKAGES.remove(playerId);
      if (buffer != null) {
         AnnihilationFxEvents.worldRiftEnd(buffer.handle);
      }
   }

   // ───────────────────────────── 服务端生命周期与主 Tick ─────────────────────────────

   @SubscribeEvent
   public static void onServerTick(TickEvent.ServerTickEvent event) {
      if (event.phase != TickEvent.Phase.END) {
         return;
      }
      if (!ACTIVE_CHAINS.isEmpty()) {
         tickSingleChains();
      }
      if (!PENDING_LINKAGES.isEmpty()) {
         tickPendingLinkages();
      }
   }

   private static void tickSingleChains() {
      ModConfig.WorldRift config = ModConfig.COMMON.annihilationBlade.worldRift;
      Iterator<Map.Entry<UUID, SingleChainSequence>> iterator = ACTIVE_CHAINS.entrySet().iterator();
      while (iterator.hasNext()) {
         SingleChainSequence sequence = iterator.next().getValue();
         ServerPlayer player = sequence.player;
         if (!player.isAlive() || player.isRemoved() || player.serverLevel() != sequence.level || !config.enabled.getValue()) {
            releaseCurrent(sequence);
            AnnihilationFxEvents.worldRiftEnd(sequence.handle);
            iterator.remove();
            continue;
         }

         LivingEntity current = resolveCurrentTarget(sequence);
         if (!sequence.snapped) {
            if (current != null && current.isAlive()) {
               sequence.lastCenter = WorldRiftPattern.vortexCenter(current);
               if (sequence.nodeAge < WorldRiftPattern.SNAP_TICK) {
                  holdSuspendedTarget(current, sequence.lastCenter);
               }
            } else {
               // 目标被外部提前击杀：立即切入拧断帧，让客户端使用预抓拍模型完整收尾
               releaseCurrent(sequence);
               sequence.snapped = true;
               sequence.nodeAge = WorldRiftPattern.SNAP_TICK;
               AnnihilationFxEvents.worldRiftUpdate(
                  sequence.handle, sequence.level, player, sequence.lastCenter, Vec3.ZERO,
                  sequence.radius, sequence.visualScale,
                  AnnihilationFxEvents.RIFT_SINGLE_SNAP, sequence.hopIndex, sequence.maxChain, sequence.seed,
                  new int[]{sequence.currentTargetId}, new int[]{0}
               );
            }
         }

         if (!sequence.snapped && sequence.nodeAge >= WorldRiftPattern.SNAP_TICK) {
            sequence.snapped = true;
            if (current != null) {
               sequence.lastCenter = WorldRiftPattern.vortexCenter(current);
            }
            // 先发 SNAP 更新再静默处决，保证客户端可在移除前做最后一次姿态同步
            AnnihilationFxEvents.worldRiftUpdate(
               sequence.handle, sequence.level, player, sequence.lastCenter, Vec3.ZERO,
               sequence.radius, sequence.visualScale,
               AnnihilationFxEvents.RIFT_SINGLE_SNAP, sequence.hopIndex, sequence.maxChain, sequence.seed,
               current != null ? new int[]{current.getId()} : new int[]{sequence.currentTargetId}, new int[]{0}
            );
            releaseCurrent(sequence);
            if (current != null && current.isAlive() && SlashBladeTargeting.canAttack(player, current)) {
               executingChain = true;
               try {
                  TerminusLogic.executeSilent(current, player);
               } finally {
                  executingChain = false;
               }
            }
         }

         if (sequence.snapped && sequence.nodeAge >= WorldRiftPattern.HOP_TICK) {
            LivingEntity nextTarget = sequence.hopIndex + 1 < sequence.maxChain ? findNextHopTarget(sequence, config) : null;
            if (nextTarget != null) {
               Vec3 prevCenter = sequence.lastCenter;
               Vec3 nextCenter = WorldRiftPattern.vortexCenter(nextTarget);
               sequence.hopIndex++;
               sequence.nodeAge = 0;
               sequence.snapped = false;
               sequence.currentTargetId = nextTarget.getId();
               sequence.currentTargetUuid = nextTarget.getUUID();
               sequence.lastCenter = nextCenter;
               sequence.visited.add(nextTarget.getUUID());
               suspendTarget(sequence, nextTarget);

               AnnihilationFxEvents.worldRiftUpdate(
                  sequence.handle, sequence.level, player, nextCenter, prevCenter,
                  sequence.radius, sequence.visualScale,
                  AnnihilationFxEvents.RIFT_SINGLE_HOP, sequence.hopIndex, sequence.maxChain, sequence.seed,
                  new int[]{nextTarget.getId()}, new int[]{WorldRiftPattern.SNAP_TICK}
               );
               continue;
            }

            AnnihilationFxEvents.worldRiftEnd(sequence.handle);
            iterator.remove();
            continue;
         }

         sequence.nodeAge++;
      }
   }

   private static void tickPendingLinkages() {
      Iterator<PendingLinkage> iterator = PENDING_LINKAGES.iterator();
      while (iterator.hasNext()) {
         PendingLinkage pending = iterator.next();
         LinkageBuffer buffer = pending.buffer;
         if (buffer.level.getServer() == null || !buffer.level.getServer().isRunning()) {
            AnnihilationFxEvents.worldRiftEnd(buffer.handle);
            iterator.remove();
            continue;
         }
         pending.delayTicks--;
         if (pending.delayTicks > 0) {
            continue;
         }

         // 计算各残骸距断空起点的错峰起爆 tick（0 .. LINKAGE_STAGGER_MAX）
         double maxDist = 4.0;
         for (LinkageTarget record : buffer.records) {
            maxDist = Math.max(maxDist, record.center.distanceTo(pending.origin));
         }
         int count = buffer.records.size();
         int[] ids = new int[count];
         int[] staggers = new int[count];
         for (int i = 0; i < count; i++) {
            LinkageTarget record = buffer.records.get(i);
            ids[i] = record.entityId;
            staggers[i] = WorldRiftPattern.linkageStaggerTick(record.center.distanceTo(pending.origin), maxDist);
         }

         AnnihilationFxEvents.worldRiftUpdate(
            buffer.handle, buffer.level, buffer.player, pending.origin, buffer.player.getLookAngle(),
            buffer.radius, buffer.visualScale,
            AnnihilationFxEvents.RIFT_LINK_UNLEASH, 0, count, buffer.seed, ids, staggers
         );
         // 立刻通知 STOP：客户端收到 UNLEASH 后会自行完整演完群体残骸扭杀与缝合，服务端不额外滞留句柄
         AnnihilationFxEvents.worldRiftEnd(buffer.handle);
         iterator.remove();
      }
   }

   @SubscribeEvent
   public static void onLivingDeath(LivingDeathEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         clearPlayer(player.getUUID());
      }
      releaseSuspensionByEntity(event.getEntity());
   }

   @SubscribeEvent
   public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
      if (event.getEntity() instanceof LivingEntity living) {
         releaseSuspensionByEntity(living);
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (SingleChainSequence sequence : ACTIVE_CHAINS.values()) {
         releaseCurrent(sequence);
         AnnihilationFxEvents.worldRiftEnd(sequence.handle);
      }
      for (LinkageBuffer buffer : PREPARING_LINKAGES.values()) {
         AnnihilationFxEvents.worldRiftEnd(buffer.handle);
      }
      for (PendingLinkage pending : PENDING_LINKAGES) {
         AnnihilationFxEvents.worldRiftEnd(pending.buffer.handle);
      }
      ACTIVE_CHAINS.clear();
      PREPARING_LINKAGES.clear();
      PENDING_LINKAGES.clear();
      SUSPENSIONS.clear();
      LAST_TRIGGER.clear();
   }

   // ───────────────────────────── 索敌与定身恢复辅助 ─────────────────────────────

   private static LivingEntity findNextHopTarget(SingleChainSequence sequence, ModConfig.WorldRift config) {
      double chainRange = config.chainRange.getValue();
      double chainRangeSqr = chainRange * chainRange;
      List<LivingEntity> candidates = SpecialEffectSupport.radialTargets(
         sequence.level,
         sequence.player,
         sequence.lastCenter,
         sequence.radius,
         entity -> !sequence.visited.contains(entity.getUUID())
            && !SUSPENSIONS.containsKey(entity.getUUID())
            && SpecialEffectSupport.centerOf(entity).distanceToSqr(sequence.attackerOrigin) <= chainRangeSqr
      );
      return candidates.isEmpty() ? null : candidates.get(0);
   }

   private static LivingEntity resolveCurrentTarget(SingleChainSequence sequence) {
      Entity entity = sequence.level.getEntity(sequence.currentTargetId);
      if (entity instanceof LivingEntity living && living.getUUID().equals(sequence.currentTargetUuid)
            && SlashBladeTargeting.canAttack(sequence.player, living)) {
         return living;
      }
      return null;
   }

   private static void suspendTarget(SingleChainSequence sequence, LivingEntity target) {
      if (target instanceof Player) {
         sequence.suspended = false;
         return;
      }
      TargetSuspension suspension = SUSPENSIONS.get(target.getUUID());
      if (suspension == null) {
         suspension = new TargetSuspension(
            target, target.isNoGravity(), target instanceof Mob mob && mob.isNoAi(), target.getDeltaMovement()
         );
         SUSPENSIONS.put(target.getUUID(), suspension);
      }
      suspension.references++;
      sequence.suspended = true;
      holdSuspendedTarget(target, WorldRiftPattern.vortexCenter(target));
   }

   private static void holdSuspendedTarget(LivingEntity target, Vec3 anchor) {
      if (target instanceof Player) {
         return;
      }
      target.setNoGravity(true);
      if (target instanceof Mob mob) {
         mob.setNoAi(true);
      }
      Vec3 velocity = target.getDeltaMovement();
      double lift = target.onGround() ? 0.045 : 0.008;
      target.setDeltaMovement(velocity.x * 0.05, Math.max(-0.004, Math.min(0.05, velocity.y * 0.1 + lift)), velocity.z * 0.05);
      target.fallDistance = 0.0F;
      target.hasImpulse = true;
   }

   private static void releaseCurrent(SingleChainSequence sequence) {
      if (!sequence.suspended) {
         return;
      }
      sequence.suspended = false;
      Entity entity = sequence.level.getEntity(sequence.currentTargetId);
      LivingEntity living = entity instanceof LivingEntity value && value.getUUID().equals(sequence.currentTargetUuid) ? value : null;
      releaseSuspension(sequence.currentTargetUuid, living);
   }

   private static void releaseSuspensionByEntity(LivingEntity target) {
      UUID targetId = target.getUUID();
      for (SingleChainSequence sequence : ACTIVE_CHAINS.values()) {
         if (sequence.suspended && sequence.currentTargetUuid.equals(targetId)) {
            sequence.suspended = false;
            releaseSuspension(targetId, target);
         }
      }
   }

   private static void releaseSuspension(UUID targetUuid, LivingEntity resolved) {
      TargetSuspension suspension = SUSPENSIONS.get(targetUuid);
      if (suspension == null) {
         return;
      }
      suspension.references--;
      if (suspension.references > 0) {
         return;
      }
      SUSPENSIONS.remove(targetUuid);
      LivingEntity restore = resolved != null ? resolved : suspension.entity;
      restore.setNoGravity(suspension.originalNoGravity);
      restore.setDeltaMovement(suspension.originalVelocity);
      restore.hasImpulse = true;
      if (restore instanceof Mob mob) {
         mob.setNoAi(suspension.originalNoAi);
      }
   }

   // ───────────────────────────── 数据结构 ─────────────────────────────

   private static final class FxEventPacketCap {
      static final int MAX_LINKAGE_TARGETS = 96;
   }

   private static final class SingleChainSequence {
      final ServerPlayer player;
      final ServerLevel level;
      final int handle;
      final Vec3 attackerOrigin;
      final double radius;
      final double visualScale;
      final int maxChain;
      final long seed;
      final Set<UUID> visited = new HashSet<>();
      int currentTargetId;
      UUID currentTargetUuid;
      Vec3 lastCenter;
      int hopIndex;
      int nodeAge;
      boolean snapped;
      boolean suspended;

      SingleChainSequence(
         ServerPlayer player, ServerLevel level, int handle, Vec3 attackerOrigin,
         double radius, double visualScale, int maxChain, long seed,
         int currentTargetId, UUID currentTargetUuid, Vec3 lastCenter
      ) {
         this.player = player;
         this.level = level;
         this.handle = handle;
         this.attackerOrigin = attackerOrigin;
         this.radius = radius;
         this.visualScale = visualScale;
         this.maxChain = maxChain;
         this.seed = seed;
         this.currentTargetId = currentTargetId;
         this.currentTargetUuid = currentTargetUuid;
         this.lastCenter = lastCenter;
      }
   }

   private static final class LinkageBuffer {
      final ServerPlayer player;
      final ServerLevel level;
      final int handle;
      final Vec3 origin;
      final double radius;
      final double visualScale;
      final long seed;
      final int maxTargets;
      final Set<Integer> seenIds = new HashSet<>();
      final List<LinkageTarget> records = new ArrayList<>();

      LinkageBuffer(ServerPlayer player, ServerLevel level, int handle, Vec3 origin, double radius, double visualScale, long seed, int maxTargets) {
         this.player = player;
         this.level = level;
         this.handle = handle;
         this.origin = origin;
         this.radius = radius;
         this.visualScale = visualScale;
         this.seed = seed;
         this.maxTargets = maxTargets;
      }
   }

   private static final class LinkageTarget {
      final int entityId;
      final Vec3 center;

      LinkageTarget(int entityId, Vec3 center) {
         this.entityId = entityId;
         this.center = center;
      }
   }

   private static final class PendingLinkage {
      final LinkageBuffer buffer;
      final Vec3 origin;
      int delayTicks;

      PendingLinkage(LinkageBuffer buffer, Vec3 origin, int delayTicks) {
         this.buffer = buffer;
         this.origin = origin;
         this.delayTicks = delayTicks;
      }
   }

   private static final class TargetSuspension {
      final LivingEntity entity;
      final boolean originalNoGravity;
      final boolean originalNoAi;
      final Vec3 originalVelocity;
      int references;

      TargetSuspension(LivingEntity entity, boolean originalNoGravity, boolean originalNoAi, Vec3 originalVelocity) {
         this.entity = entity;
         this.originalNoGravity = originalNoGravity;
         this.originalNoAi = originalNoAi;
         this.originalVelocity = originalVelocity;
      }
   }
}
