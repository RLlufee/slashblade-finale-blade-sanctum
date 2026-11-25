package QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusEchoPattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.WorldRiftChain;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.common.EntitySuspensionManager;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 湮灭之刃 SE「归墟回响·因果反噬」服务端权威状态机。
 * <p>
 * 彻底移除普通挥刀（{@code DoSlashEvent}）触发，转而具象化「绝对庇护」并在受击与弹射物拦截瞬间触发防守反击：
 * <ol>
 *   <li>t = 0：吞噬敌方攻击或弹射物，在受击来向展开「归墟偏折镜」并射出因果逆溯轨；</li>
 *   <li>t = {@link TerminusEchoPattern#LOCK_TICK}（7）：溯源轨钉入攻击者，施加短时归墟落款定身（自动避让其他 SE 锁定态）；</li>
 *   <li>t = {@link TerminusEchoPattern#EXECUTE_TICK}（14）：先向客户端同步攻击者最新实时坐标（无视瞬移）并抓拍活体模型，
 *       随即由玩家黑金剑影一闪执行 {@link TerminusLogic#executeSilent} 终焉处决。</li>
 * </ol>
 */
@EventBusSubscriber(modid = "annihilationblade")
public class TerminusEcho extends SpecialEffect {
   /** 轻量偏折镜波纹（无合法攻击者或主冷却期弹射物拦截）的独立节流间隔，防止岩浆/箭雨高频刷屏。 */
   private static final int DEFLECT_ONLY_COOLDOWN_TICKS = 10;

   private static final String SUSPENSION_PREFIX = "terminus_echo:";
   private static final Map<UUID, List<CounterSequence>> ACTIVE = new HashMap<>();
   private static final Map<UUID, Long> LAST_COUNTER = new HashMap<>();
   private static final Map<UUID, Long> LAST_DEFLECT_ONLY = new HashMap<>();
   private static final Set<UUID> LOCKED_TARGETS = new HashSet<>();
   private static int counterExecutionDepth;

   public TerminusEcho() {
      super(0, false, false);
   }

   /** 当前服务端线程是否处于归墟回响处决调用栈中（用于阻断荆棘/反伤无限递归）。 */
   public static boolean isExecutingCounter() {
      return counterExecutionDepth > 0;
   }

   /** 指定实体当前是否正处于归墟回响因果锁印中（供因果坍缩微扰定身避让）。 */
   public static boolean isEntityLocked(UUID entityUuid) {
      return entityUuid != null && (LOCKED_TARGETS.contains(entityUuid) || EntitySuspensionManager.isHeldByPrefix(entityUuid, SUSPENSION_PREFIX));
   }

   /** 玩家离线、换维度或死亡时彻底清理其进行中的因果反噬与定身快照。 */
   public static void clearPlayer(UUID playerId) {
      List<CounterSequence> sequences = ACTIVE.remove(playerId);
      if (sequences != null) {
         for (CounterSequence sequence : sequences) {
            releaseSequenceTarget(sequence);
            AnnihilationFxEvents.echoEnd(sequence.fxHandle);
         }
      }
      LAST_COUNTER.remove(playerId);
      LAST_DEFLECT_ONLY.remove(playerId);
   }

   // ───────────────────────────── 受击与弹射物拦截入口 ─────────────────────────────

   /**
    * 由 {@code ModEventHandler.onLivingAttack} 在绝对庇护取消伤害后调用。
    */
   public static boolean onAbsorbedAttack(
      ServerPlayer defender, DamageSource source, float amount, @Nullable Vec3 impactPos, boolean projectileIntercepted
   ) {
      if (defender == null || source == null || defender.level().isClientSide() || defender.isSpectator() || !defender.isAlive()) {
         return false;
      }
      if (TerminusLogic.isExecuting() || WorldRiftChain.isExecutingChain() || isExecutingCounter()) {
         return false;
      }
      if (source.is(DamageTypes.THORNS)) {
         return false;
      }
      ModConfig.TerminusEcho config = ModConfig.COMMON.annihilationBlade.terminusEcho;
      if (!config.enabled.getValue() || !hasEligibleEchoBlade(defender, config.requireHeld.getValue())) {
         return false;
      }

      Entity direct = source.getDirectEntity();
      boolean isProjectile = projectileIntercepted || direct instanceof Projectile;
      LivingEntity attacker = resolveAttacker(source);
      Vec3 incomingDir = resolveIncomingDirection(defender, source, attacker, impactPos);
      boolean triggered = triggerCounterOrDeflect(defender, attacker, incomingDir, isProjectile, config);

      // 若本次伤害来自敌方投射物且反噬或偏折镜成功接管，安全化解投射物（保护玩家投掷的忠诚三叉戟不被永久销毁）
      if (triggered && direct instanceof Projectile projectile && !projectile.isRemoved() && projectile.getOwner() != defender) {
         neutralizeProjectile(projectile, defender);
      }
      return triggered;
   }

   /**
    * 兼容「背包无湮灭之刃，但持有其他挂载了 terminus_echo SE 的拔刀剑」时的受击拦截入口。
    * 统一遵循 {@code config.requireHeld}，仅当确认存在合法因果反噬时才取消本次受击伤害，不越权赋予常规拔刀剑永久无敌。
    */
   public static boolean tryInterceptExternalAttack(ServerPlayer defender, LivingAttackEvent event) {
      if (defender == null || event == null || defender.level().isClientSide() || defender.isSpectator() || !defender.isAlive()) {
         return false;
      }
      if (TerminusLogic.isExecuting() || WorldRiftChain.isExecutingChain() || isExecutingCounter()) {
         return false;
      }
      DamageSource source = event.getSource();
      if (source == null || source.is(DamageTypes.THORNS)) {
         return false;
      }
      ModConfig.TerminusEcho config = ModConfig.COMMON.annihilationBlade.terminusEcho;
      if (!config.enabled.getValue() || !hasEligibleEchoBlade(defender, config.requireHeld.getValue())) {
         return false;
      }
      LivingEntity attacker = resolveAttacker(source);
      if (attacker == null) {
         return false;
      }
      Vec3 incomingDir = resolveIncomingDirection(defender, source, attacker, null);
      boolean isProjectile = source.getDirectEntity() instanceof Projectile;
      if (tryLaunchCounter(defender, attacker, incomingDir, isProjectile, config)) {
         event.setCanceled(true);
         if (source.getDirectEntity() instanceof Projectile projectile && !projectile.isRemoved() && projectile.getOwner() != defender) {
            neutralizeProjectile(projectile, defender);
         }
         return true;
      }
      return false;
   }

   /**
    * 主动拦截即将命中玩家的敌方弹射物（箭矢、火球、三叉戟、凋灵之首、潜影弹等），
    * 在撞上归墟偏折镜的瞬间直接吞噬并顺藤摸瓜溯源反杀发射者。
    */
   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onProjectileImpact(ProjectileImpactEvent event) {
      Projectile projectile = event.getProjectile();
      if (projectile == null || projectile.level().isClientSide() || projectile.isRemoved()) {
         return;
      }
      if (!(event.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity() instanceof ServerPlayer defender)) {
         return;
      }
      if (defender.isSpectator() || !defender.isAlive() || TerminusLogic.isExecuting() || WorldRiftChain.isExecutingChain() || isExecutingCounter()) {
         return;
      }
      Entity owner = projectile.getOwner();
      if (owner == defender) {
         return;
      }
      ModConfig.TerminusEcho config = ModConfig.COMMON.annihilationBlade.terminusEcho;
      if (!config.enabled.getValue() || !config.interceptProjectiles.getValue()) {
         return;
      }
      if (!hasEligibleEchoBlade(defender, config.requireHeld.getValue())) {
         return;
      }

      LivingEntity attacker = unwrapLiving(owner);
      Vec3 defenderCenter = SpecialEffectSupport.centerOf(defender);
      Vec3 impactPos = hit.getLocation();
      Vec3 incomingDir;
      if (projectile.getDeltaMovement().lengthSqr() > 1.0E-5) {
         incomingDir = projectile.getDeltaMovement().normalize().scale(-1.0);
      } else if (impactPos != null && impactPos.distanceToSqr(defenderCenter) > 1.0E-4) {
         incomingDir = impactPos.subtract(defenderCenter).normalize();
      } else if (attacker != null) {
         incomingDir = SpecialEffectSupport.centerOf(attacker).subtract(defenderCenter);
      } else {
         incomingDir = defender.getLookAngle();
      }

      boolean hasAnnihilationProtection = SpecialEffectSupport.hasAnnihilationBlade(defender);
      boolean handled = tryLaunchCounter(defender, attacker, incomingDir, true, config);
      if (!handled && (hasAnnihilationProtection || attacker != null)) {
         // 湮灭之刃本体拦截一切弹射物；外置移植 SE 仅拦截存在明确射手的弹射物，避免对无主发射器永久白嫖无敌
         handled = tryEmitDeflectOnly(defender, incomingDir, true, config);
      }
      if (handled) {
         event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
         neutralizeProjectile(projectile, defender);
      }
   }

   // ───────────────────────────── 核心调度逻辑 ─────────────────────────────

   private static boolean triggerCounterOrDeflect(
      ServerPlayer defender, @Nullable LivingEntity attacker, Vec3 incomingDir, boolean isProjectile, ModConfig.TerminusEcho config
   ) {
      if (tryLaunchCounter(defender, attacker, incomingDir, isProjectile, config)) {
         return true;
      }
      // 存在明确外部攻击者（冷却期/满并发）或弹射物时，始终允许弹出轻量偏折镜反馈；
      // deflect_environment_fx 严格仅控制无明确攻击者的环境伤害（岩浆/窒息/摔落等）。
      if (isProjectile || (attacker != null && attacker != defender) || (attacker == null && config.deflectEnvironmentFx.getValue())) {
         return tryEmitDeflectOnly(defender, incomingDir, isProjectile, config);
      }
      return false;
   }

   private static boolean tryLaunchCounter(
      ServerPlayer defender, @Nullable LivingEntity attacker, Vec3 incomingDir, boolean isProjectile, ModConfig.TerminusEcho config
   ) {
      if (attacker == null || attacker == defender || !attacker.isAlive() || attacker.isRemoved()) {
         return false;
      }
      if (attacker.level() != defender.level() || !SlashBladeTargeting.canAttack(defender, attacker)) {
         return false;
      }
      UUID attackerUuid = attacker.getUUID();
      if (LOCKED_TARGETS.contains(attackerUuid)) {
         return false;
      }

      ServerLevel level = defender.serverLevel();
      Vec3 defenderCenter = SpecialEffectSupport.centerOf(defender);
      Vec3 targetCenter = SpecialEffectSupport.centerOf(attacker);
      double maxRange = config.counterRange.getValue();
      if (defenderCenter.distanceToSqr(targetCenter) > maxRange * maxRange) {
         return false;
      }

      List<CounterSequence> sequences = ACTIVE.get(defender.getUUID());
      if (sequences != null && sequences.size() >= config.maxActiveCounters.getValue()) {
         return false;
      }

      long now = level.getGameTime();
      if (!SpecialEffectSupport.tryStartCooldown(LAST_COUNTER, defender, now, config.cooldownTicks.getValue())) {
         return false;
      }

      Vec3 mirrorCenter = TerminusEchoPattern.resolveMirrorCenter(defenderCenter, incomingDir);
      double visualScale = config.visualScale.getValue();
      long seed = level.getRandom().nextLong();
      int handle = AnnihilationFxEvents.echoCounterStart(
         level, defender, mirrorCenter, targetCenter, visualScale, isProjectile, attacker, seed, TerminusEchoPattern.SERVER_SUSTAIN_DURATION
      );

      CounterSequence sequence = new CounterSequence(
         defender, level, handle, attacker.getId(), attackerUuid, mirrorCenter, targetCenter, visualScale, isProjectile, seed, now
      );
      LOCKED_TARGETS.add(attackerUuid);
      ACTIVE.computeIfAbsent(defender.getUUID(), ignored -> new ArrayList<>()).add(sequence);
      return true;
   }

   private static boolean tryEmitDeflectOnly(
      ServerPlayer defender, Vec3 incomingDir, boolean isProjectile, ModConfig.TerminusEcho config
   ) {
      ServerLevel level = defender.serverLevel();
      if (!SpecialEffectSupport.tryStartCooldown(LAST_DEFLECT_ONLY, defender, level.getGameTime(), DEFLECT_ONLY_COOLDOWN_TICKS)) {
         return false;
      }
      Vec3 defenderCenter = SpecialEffectSupport.centerOf(defender);
      Vec3 dir = TerminusEchoPattern.safeNormalize(incomingDir, defender.getLookAngle());
      Vec3 mirrorCenter = TerminusEchoPattern.resolveMirrorCenter(defenderCenter, dir);
      AnnihilationFxEvents.echoDeflectOnly(level, defender, mirrorCenter, dir, config.visualScale.getValue(), isProjectile);
      return true;
   }

   // ───────────────────────────── 服务端时间轴推进与安全清理 ─────────────────────────────

   @SubscribeEvent
   public static void onServerTick(TickEvent.ServerTickEvent event) {
      if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
         return;
      }
      boolean enabled = ModConfig.COMMON.annihilationBlade.terminusEcho.enabled.getValue();
      List<PendingCounterExecution> pendingExecutions = new ArrayList<>();
      Iterator<Map.Entry<UUID, List<CounterSequence>>> playerIt = ACTIVE.entrySet().iterator();
      while (playerIt.hasNext()) {
         List<CounterSequence> sequences = playerIt.next().getValue();
         Iterator<CounterSequence> seqIt = sequences.iterator();
         while (seqIt.hasNext()) {
            CounterSequence sequence = seqIt.next();
            ServerPlayer defender = sequence.defender;
            if (!enabled || defender.isRemoved() || !defender.isAlive() || defender.serverLevel() != sequence.level) {
               releaseSequenceTarget(sequence);
               AnnihilationFxEvents.echoEnd(sequence.fxHandle);
               seqIt.remove();
               continue;
            }

            LivingEntity target = resolveTrackedTarget(sequence);
            if (target == null || !target.isAlive() || target.isRemoved() || target.level() != sequence.level) {
               releaseSequenceTarget(sequence);
               AnnihilationFxEvents.echoEnd(sequence.fxHandle);
               seqIt.remove();
               continue;
            }

            // 持续追踪最新坐标（含末影人等瞬移后的新位置）
            sequence.lastTargetCenter = SpecialEffectSupport.centerOf(target);
            long elapsed = sequence.level.getGameTime() - sequence.startGameTime;

            if (elapsed >= TerminusEchoPattern.LOCK_TICK && !sequence.locked) {
               sequence.locked = true;
               trySuspendTarget(sequence, target);
            } else if (sequence.suspended) {
               maintainSuspension(target);
            }

            if (elapsed >= TerminusEchoPattern.EXECUTE_TICK) {
               seqIt.remove();
               pendingExecutions.add(new PendingCounterExecution(sequence, target));
            }
         }
         if (sequences.isEmpty()) {
            playerIt.remove();
         }
      }

      // 延后至外层 ACTIVE 迭代完毕再执行处决，防止 PVP 反杀玩家时 LivingDeathEvent 修改 ACTIVE 引发 CME
      for (PendingCounterExecution execution : pendingExecutions) {
         if (execution.target.isAlive() && !execution.target.isRemoved()
               && execution.sequence.defender.isAlive()
               && !execution.sequence.defender.isRemoved()
               && execution.sequence.defender.serverLevel() == execution.sequence.level) {
            executeSequence(execution.sequence, execution.target);
         } else {
            releaseSequenceTarget(execution.sequence);
            AnnihilationFxEvents.echoEnd(execution.sequence.fxHandle);
         }
      }
   }

   private record PendingCounterExecution(CounterSequence sequence, LivingEntity target) {
   }

   private static void executeSequence(CounterSequence sequence, LivingEntity target) {
      ServerPlayer defender = sequence.defender;
      ServerLevel level = sequence.level;
      Vec3 targetCenter = SpecialEffectSupport.centerOf(target);
      Vec3 traceDir = TerminusEchoPattern.safeNormalize(targetCenter.subtract(sequence.mirrorCenter), defender.getLookAngle());
      Vec3 ghostChest = TerminusEchoPattern.resolveGhostChest(targetCenter, traceDir, target.getBbWidth(), sequence.seed);
      Vec3 slashDir = TerminusEchoPattern.safeNormalize(targetCenter.subtract(ghostChest), traceDir);

      // 先发 ECHO_EXECUTE 同步最新坐标与活体实体 ID，确保客户端先完成 3D 模型抓拍再执行处决
      AnnihilationFxEvents.echoCounterExecute(
         sequence.fxHandle, level, defender, targetCenter, slashDir, sequence.visualScale, sequence.projectileIntercepted, target, sequence.seed
      );

      releaseSequenceTarget(sequence);

      counterExecutionDepth++;
      try {
         if (target instanceof Player) {
            TerminusLogic.execute(target, defender);
         } else {
            TerminusLogic.executeSilent(target, defender);
         }
      } finally {
         counterExecutionDepth--;
      }

      // 服务端占位结束；客户端在已收到 ECHO_EXECUTE 后会继续播完斩裂与余烬尾韵
      AnnihilationFxEvents.echoEnd(sequence.fxHandle);
   }

   @SubscribeEvent
   public static void onLivingDeath(LivingDeathEvent event) {
      if (event.getEntity().level().isClientSide()) {
         return;
      }
      if (event.getEntity() instanceof ServerPlayer player) {
         clearPlayer(player.getUUID());
      }
      releaseSuspensionByEntity(event.getEntity());
   }

   @SubscribeEvent
   public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
      if (event.getLevel().isClientSide()) {
         return;
      }
      if (event.getEntity() instanceof LivingEntity living) {
         releaseSuspensionByEntity(living);
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (List<CounterSequence> sequences : ACTIVE.values()) {
         for (CounterSequence sequence : sequences) {
            releaseSequenceTarget(sequence);
            AnnihilationFxEvents.echoEnd(sequence.fxHandle);
         }
      }
      ACTIVE.clear();
      LAST_COUNTER.clear();
      LAST_DEFLECT_ONLY.clear();
      LOCKED_TARGETS.clear();
      counterExecutionDepth = 0;
   }

   // ───────────────────────────── 短时定身快照管理（委托全局 EntitySuspensionManager） ─────────────────────────────

   private static void trySuspendTarget(CounterSequence sequence, LivingEntity target) {
      if (target instanceof Player) {
         return;
      }
      UUID uuid = target.getUUID();
      // 避让已被裂界、虚无权域或幻影审判独占锁定的实体；即使此前已被因果坍缩施加无重力，统一管理器也会保留真正原始快照
      if (EntitySuspensionManager.isHeldByPrefix(uuid, SUSPENSION_PREFIX)
            || WorldRiftChain.isEntitySuspended(uuid)
            || VoidDominion.isEntitySuspended(uuid)
            || PhantomJudgement.isEntityLocked(uuid)) {
         return;
      }
      EntitySuspensionManager.acquire(target, sequence.suspensionToken, true, true);
      sequence.suspended = true;
      target.setDeltaMovement(0.0, 0.02, 0.0);
      target.hurtMarked = true;
   }

   private static void maintainSuspension(LivingEntity target) {
      if (target.isAlive() && !target.isRemoved()) {
         target.setDeltaMovement(0.0, 0.0, 0.0);
         target.hurtMarked = true;
      }
   }

   private static void releaseSequenceTarget(CounterSequence sequence) {
      LOCKED_TARGETS.remove(sequence.targetUuid);
      if (sequence.suspended) {
         sequence.suspended = false;
         Entity raw = sequence.level.getEntity(sequence.targetEntityId);
         LivingEntity resolved = raw instanceof LivingEntity living && living.getUUID().equals(sequence.targetUuid) ? living : null;
         EntitySuspensionManager.release(sequence.targetUuid, resolved, sequence.suspensionToken);
      }
   }

   private static void releaseSuspensionByEntity(Entity entity) {
      if (entity == null) {
         return;
      }
      UUID uuid = entity.getUUID();
      LOCKED_TARGETS.remove(uuid);
      LivingEntity resolved = entity instanceof LivingEntity living ? living : null;
      for (List<CounterSequence> sequences : ACTIVE.values()) {
         for (CounterSequence sequence : sequences) {
            if (sequence.suspended && sequence.targetUuid.equals(uuid)) {
               sequence.suspended = false;
               EntitySuspensionManager.release(uuid, resolved, sequence.suspensionToken);
            }
         }
      }
   }

   // ───────────────────────────── 持刀与来源解析辅助 ─────────────────────────────

   /** 校验玩家是否持有未损坏、未封印且挂载了 TERMINUS_ECHO 的拔刀剑。 */
   public static boolean hasEligibleEchoBlade(ServerPlayer player, boolean requireHeld) {
      if (isEligibleEchoBlade(player.getMainHandItem()) || isEligibleEchoBlade(player.getOffhandItem())) {
         return true;
      }
      if (requireHeld) {
         return false;
      }
      for (ItemStack stack : player.getInventory().items) {
         if (isEligibleEchoBlade(stack)) {
            return true;
         }
      }
      return false;
   }

   private static boolean isEligibleEchoBlade(ItemStack stack) {
      if (stack.isEmpty()) {
         return false;
      }
      ISlashBladeState state = stack.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
      return state != null
         && !state.isBroken()
         && !state.isSealed()
         && state.hasSpecialEffect(ModSpecialEffects.TERMINUS_ECHO.getId());
   }

   /**
    * 安全化解被偏折镜截停的敌方弹射物：
    * 对玩家投掷的三叉戟（尤其附魔忠诚）绝不直接 {@code discard()}，改为反向弹开使其自然落地或触发忠诚飞回原主人；
    * 其余常规弹射物则直接清除。
    */
   private static void neutralizeProjectile(Projectile projectile, ServerPlayer defender) {
      if (projectile == null || projectile.isRemoved()) {
         return;
      }
      if (projectile instanceof ThrownTrident trident && trident.getOwner() instanceof Player) {
         Vec3 pushDir = projectile.position().subtract(SpecialEffectSupport.centerOf(defender));
         Vec3 away = pushDir.lengthSqr() > 1.0E-4 ? pushDir.normalize() : defender.getLookAngle().normalize();
         trident.setDeltaMovement(away.scale(0.45).add(0.0, 0.2, 0.0));
         trident.hurtMarked = true;
         trident.hasImpulse = true;
         return;
      }
      projectile.discard();
   }

   /** 解包多部位 Boss（如末影龙 {@link PartEntity}）并返回真实宿主活体。 */
   @Nullable
   private static LivingEntity unwrapLiving(@Nullable Entity entity) {
      Entity current = entity;
      int guard = 0;
      while (current instanceof PartEntity<?> part && guard++ < 4) {
         current = part.getParent();
      }
      return current instanceof LivingEntity living ? living : null;
   }

   @Nullable
   private static LivingEntity resolveAttacker(DamageSource source) {
      Entity entity = source.getEntity();
      LivingEntity resolved = unwrapLiving(entity);
      if (resolved != null) {
         return resolved;
      }
      Entity direct = source.getDirectEntity();
      if (direct instanceof Projectile projectile) {
         LivingEntity owner = unwrapLiving(projectile.getOwner());
         if (owner != null) {
            return owner;
         }
      }
      resolved = unwrapLiving(direct);
      if (resolved != null) {
         return resolved;
      }
      if (direct instanceof AreaEffectCloud cloud && cloud.getOwner() != null) {
         return cloud.getOwner();
      }
      if (direct instanceof EvokerFangs fangs && fangs.getOwner() != null) {
         return fangs.getOwner();
      }
      if (direct instanceof PrimedTnt tnt && tnt.getOwner() != null) {
         return tnt.getOwner();
      }
      if (entity instanceof AreaEffectCloud cloud && cloud.getOwner() != null) {
         return cloud.getOwner();
      }
      if (entity instanceof EvokerFangs fangs && fangs.getOwner() != null) {
         return fangs.getOwner();
      }
      if (entity instanceof PrimedTnt tnt && tnt.getOwner() != null) {
         return tnt.getOwner();
      }
      return null;
   }

   private static Vec3 resolveIncomingDirection(
      ServerPlayer defender, DamageSource source, @Nullable LivingEntity attacker, @Nullable Vec3 impactPos
   ) {
      Vec3 defenderCenter = SpecialEffectSupport.centerOf(defender);
      if (impactPos != null && impactPos.distanceToSqr(defenderCenter) > 1.0E-4) {
         return impactPos.subtract(defenderCenter).normalize();
      }
      Entity direct = source.getDirectEntity();
      if (direct != null && direct != defender) {
         Vec3 directPos = direct instanceof LivingEntity living ? SpecialEffectSupport.centerOf(living) : direct.position();
         if (directPos.distanceToSqr(defenderCenter) > 1.0E-4) {
            return directPos.subtract(defenderCenter).normalize();
         }
      }
      if (attacker != null && attacker != defender) {
         Vec3 attackerCenter = SpecialEffectSupport.centerOf(attacker);
         if (attackerCenter.distanceToSqr(defenderCenter) > 1.0E-4) {
            return attackerCenter.subtract(defenderCenter).normalize();
         }
      }
      Vec3 sourcePos = source.getSourcePosition();
      if (sourcePos != null && sourcePos.distanceToSqr(defenderCenter) > 1.0E-4) {
         return sourcePos.subtract(defenderCenter).normalize();
      }
      return TerminusEchoPattern.safeNormalize(defender.getLookAngle(), new Vec3(0.0, 0.0, 1.0));
   }

   @Nullable
   private static LivingEntity resolveTrackedTarget(CounterSequence sequence) {
      Entity byId = sequence.level.getEntity(sequence.targetEntityId);
      if (byId instanceof LivingEntity living && living.getUUID().equals(sequence.targetUuid)) {
         return living;
      }
      return SpecialEffectSupport.findLivingEntity(sequence.level, sequence.targetUuid);
   }

   // ───────────────────────────── 内部状态类 ─────────────────────────────

   private static final class CounterSequence {
      final ServerPlayer defender;
      final ServerLevel level;
      final int fxHandle;
      final int targetEntityId;
      final UUID targetUuid;
      final Vec3 mirrorCenter;
      Vec3 lastTargetCenter;
      final double visualScale;
      final boolean projectileIntercepted;
      final long seed;
      final long startGameTime;
      final String suspensionToken;
      boolean locked;
      boolean suspended;

      private CounterSequence(
         ServerPlayer defender, ServerLevel level, int fxHandle, int targetEntityId, UUID targetUuid,
         Vec3 mirrorCenter, Vec3 initialTargetCenter, double visualScale, boolean projectileIntercepted, long seed, long startGameTime
      ) {
         this.defender = defender;
         this.level = level;
         this.fxHandle = fxHandle;
         this.targetEntityId = targetEntityId;
         this.targetUuid = targetUuid;
         this.mirrorCenter = mirrorCenter;
         this.lastTargetCenter = initialTargetCenter;
         this.visualScale = visualScale;
         this.projectileIntercepted = projectileIntercepted;
         this.seed = seed;
         this.startGameTime = startGameTime;
         this.suspensionToken = SUSPENSION_PREFIX + defender.getUUID() + ":" + fxHandle;
      }
   }
}
