package QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.CausalityCollapsePattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.CausalityCollapsePattern.AimResolution;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.CausalityCollapsePattern.ScarSpec;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.WorldRiftChain;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
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
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 湮灭之刃 SE「因果坍缩·时空裂痕」服务端逻辑。
 * <p>
 * 兼顾「裂界战后余威无缝接管」与「独立挥刀智能索敌 + 引力漩涡空间绞杀」双轨体验：
 * <ul>
 *   <li><b>神威双模式无缝接管（断空群体联动 + 独立单链）</b>：在神威拧杀吸穿奇点的瞬间跳过闭合回弹，
 *       于每个被斩杀敌人的精确 3D 原位留下一道持久的裂界同源时空裂痕；开启 {@code linkage_devour} 时，
 *       战后残留裂痕同样会持续吸引路过的敌对生物并将其卷入喉心绞杀（单链跳跃期间自动暂缓处决以防断链）；</li>
 *   <li><b>独立挥刀智能索敌 / 自由空挥</b>：平时右键挥刀（未触发断空与单链裂界时）先沿准星锥角做智能索敌，
 *       命中目标则直接将时空裂痕斜劈在目标身位中心；准星偏离太远或前方无目标时平滑回退至前方 6.2 格空气处，
 *       保留自由挥刀斩裂空气的手感，同时所劈出的裂痕本身就是一座持续约 2.6 秒的引力绞杀陷阱。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public class CausalityCollapse extends SpecialEffect {
   private static final int MAX_SCARS_PER_BURST = 96;
   /** 单玩家同时维护的服务端物理引力场上限（超出时仍保留 100% 视觉渲染，仅裁剪最旧联动物理场防卡顿）。 */
   private static final int MAX_ACTIVE_VORTEX_SCARS_PER_PLAYER = 20;
   /** 联动群发裂痕间距小于此值时自动合并物理引力场，避免重叠场叠加拉飞生物或重复扫描。 */
   private static final double LINKED_FIELD_MERGE_DIST_SQR = 2.4 * 2.4;

   private static final Map<UUID, Long> LAST_STANDALONE_TRIGGER = new HashMap<>();
   private static final List<PendingScarBurst> PENDING_BURSTS = new ArrayList<>();
   private static final List<ActiveVortexScar> ACTIVE_SCARS = new ArrayList<>();
   private static final Map<UUID, GravityHold> GRAVITY_HOLDS = new HashMap<>();

   public CausalityCollapse() {
      super(0, false, false);
   }

   public static void clearPlayer(UUID playerId) {
      PENDING_BURSTS.removeIf(burst -> burst.player.getUUID().equals(playerId));
      Iterator<ActiveVortexScar> iterator = ACTIVE_SCARS.iterator();
      while (iterator.hasNext()) {
         ActiveVortexScar scar = iterator.next();
         if (scar.ownerId.equals(playerId)) {
            releaseAllVictims(scar);
            iterator.remove();
         }
      }
      LAST_STANDALONE_TRIGGER.remove(playerId);
   }

   // ───────────────────────────── 三路触发入口 ─────────────────────────────

   /**
    * 判断玩家当前是否通过原版拔刀剑 Shift 锁敌锁定了中远程合法目标（距离 > 4.5 格）。
    * 供 {@code AnnihilationInputRouter} 在确认不可能触发贴脸裂界单链时跳过 2 tick 命中等待，做到远程狙杀 0 帧直出。
    */
   public static boolean hasRemoteLockOnTarget(ServerPlayer player, ISlashBladeState state) {
      if (player == null || state == null || player.level().isClientSide()) {
         return false;
      }
      ModConfig.CausalityCollapse config = ModConfig.COMMON.annihilationBlade.causalityCollapse;
      if (!config.enabled.getValue() || !config.lockOnLinkage.getValue()) {
         return false;
      }
      LivingEntity locked = CausalityCollapsePattern.resolveSlashBladeLockOnTarget(
         player.serverLevel(), player, state, config.lockOnRange.getValue(), CausalityCollapse::isAvailableForRift
      );
      if (locked == null) {
         return false;
      }
      return SpecialEffectSupport.centerOf(locked).distanceToSqr(player.getEyePosition()) > 4.5 * 4.5;
   }

   /**
    * 独立挥刀入口：由 {@code AnnihilationInputRouter} 在确认非左+右虚无权域双键、未触发断空且非裂界单链时调用。
    * 优先联动原版拔刀剑 Shift 锁敌（实现超远距精准狙杀），其次按配置走准星辅助索敌，未锁定时自由斜劈空气。
    */
   public static boolean tryStandaloneSlash(ServerPlayer player, ISlashBladeState state) {
      if (player == null || state == null || player.isSpectator() || player.level().isClientSide() || Dankong.isActive(player)) {
         return false;
      }
      if (!state.hasSpecialEffect(ModSpecialEffects.CAUSALITY_COLLAPSE.getId())) {
         return false;
      }
      ModConfig.CausalityCollapse config = ModConfig.COMMON.annihilationBlade.causalityCollapse;
      if (!config.enabled.getValue()) {
         return false;
      }
      ServerLevel level = player.serverLevel();
      if (!SpecialEffectSupport.tryStartCooldown(LAST_STANDALONE_TRIGGER, player, level.getGameTime(), config.cooldownTicks.getValue())) {
         return false;
      }

      AimResolution aim = CausalityCollapsePattern.resolveStandaloneScar(
         level, player, state,
         config.lockOnLinkage.getValue(), config.lockOnRange.getValue(),
         config.aimLockRange.getValue(), config.aimLockAngle.getValue(),
         CausalityCollapse::isAvailableForRift
      );
      Vec3 look = safeDir(player.getLookAngle());
      List<ScarSpec> specs = List.of(new ScarSpec(aim.anchor(), aim.entityHeight(), 0));
      emitScarBurst(
         player, level, aim.anchor(), look, specs, AnnihilationFxEvents.CAUSALITY_MODE_STANDALONE,
         config.scarDurationTicks.getValue(), config.visualScale.getValue(),
         config.suctionRadius.getValue(), config.maxSuctionTargets.getValue(),
         config.devourExecution.getValue(), aim.targetEntityId()
      );
      return true;
   }

   /**
    * 神威独立单链联动入口：每拧断一个目标，等待其 3D 躯壳吸穿奇点后，在该敌人精确原位留下一道同源时空裂痕。
    */
   public static void scheduleSingleRiftScar(
      ServerPlayer player, ServerLevel level, Vec3 center, float entityHeight, Vec3 lookHint
   ) {
      if (player == null || level == null || !player.isAlive() || player.isRemoved()) {
         return;
      }
      ModConfig.CausalityCollapse config = ModConfig.COMMON.annihilationBlade.causalityCollapse;
      if (!config.enabled.getValue() || !config.worldRiftLinkage.getValue()) {
         return;
      }
      List<ScarSpec> specs = List.of(new ScarSpec(center, Math.max(0.9F, entityHeight), 0));
      boolean enablePhysics = config.linkageDevour.getValue();
      PENDING_BURSTS.add(new PendingScarBurst(
         player, level, center, safeDir(lookHint), specs,
         AnnihilationFxEvents.CAUSALITY_MODE_LINKED_SINGLE,
         config.scarDurationTicks.getValue(), config.visualScale.getValue(),
         enablePhysics ? config.suctionRadius.getValue() : 0.0,
         config.maxSuctionTargets.getValue(),
         enablePhysics && config.devourExecution.getValue(),
         level.getGameTime() + CausalityCollapsePattern.singleRiftScarDelay()
      ));
   }

   /**
    * 断空 + 神威群体联动入口：预约在每个残骸神威奇点吸穿的错峰瞬间，于全战场各敌人原位绽开一片时空裂痕群。
    */
   public static void scheduleGroupRiftScars(
      ServerPlayer player, ServerLevel level, Vec3 dankongOrigin, List<ScarSpec> specs
   ) {
      if (player == null || level == null || !player.isAlive() || player.isRemoved() || specs == null || specs.isEmpty()) {
         return;
      }
      ModConfig.CausalityCollapse config = ModConfig.COMMON.annihilationBlade.causalityCollapse;
      if (!config.enabled.getValue() || !config.worldRiftLinkage.getValue()) {
         return;
      }
      int limit = Math.min(MAX_SCARS_PER_BURST, specs.size());
      List<ScarSpec> copy = new ArrayList<>(specs.subList(0, limit));
      boolean enablePhysics = config.linkageDevour.getValue();
      PENDING_BURSTS.add(new PendingScarBurst(
         player, level, dankongOrigin, safeDir(player.getLookAngle()), copy,
         AnnihilationFxEvents.CAUSALITY_MODE_LINKED_GROUP,
         config.scarDurationTicks.getValue(), config.visualScale.getValue(),
         enablePhysics ? config.suctionRadius.getValue() : 0.0,
         config.maxSuctionTargets.getValue(),
         enablePhysics && config.devourExecution.getValue(),
         level.getGameTime() + CausalityCollapsePattern.groupRiftScarDelay()
      ));
   }

   private static void emitScarBurst(
      ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 direction, List<ScarSpec> specs, int mode,
      int scarDurationTicks, double visualScale, double suctionRadius, int maxSuctionTargets,
      boolean devourExecution, int directLockedEntityId
   ) {
      if (specs.isEmpty()) {
         return;
      }
      int count = Math.min(MAX_SCARS_PER_BURST, specs.size());
      int[] packedXZ = new int[count];
      int[] packedYMeta = new int[count];
      double maxSpread = 4.0;
      for (int i = 0; i < count; i++) {
         ScarSpec spec = specs.get(i);
         packedXZ[i] = CausalityCollapsePattern.packXZ(origin, spec.center());
         packedYMeta[i] = CausalityCollapsePattern.packYMeta(origin, spec.center(), spec.entityHeight(), spec.staggerTick());
         maxSpread = Math.max(maxSpread, spec.center().distanceTo(origin));
      }

      long seed = level.getRandom().nextLong();
      AnnihilationFxEvents.causalityCollapseScars(
         level, player, origin, direction, maxSpread + 8.0, visualScale,
         mode, scarDurationTicks, seed, packedXZ, packedYMeta
      );

      if (suctionRadius > 0.5 && maxSuctionTargets > 0) {
         registerVortexScars(
            player, level, specs, count, mode != AnnihilationFxEvents.CAUSALITY_MODE_STANDALONE,
            scarDurationTicks, visualScale, suctionRadius, maxSuctionTargets, devourExecution, directLockedEntityId
         );
      }
   }

   // ───────────────────────────── 服务端调度与持续引力绞杀场 ─────────────────────────────

   private static void registerVortexScars(
      ServerPlayer player, ServerLevel level, List<ScarSpec> specs, int count, boolean linkedMode,
      int scarDurationTicks, double visualScale, double suctionRadius, int maxSuctionTargets,
      boolean devourExecution, int directLockedEntityId
   ) {
      long now = level.getGameTime();
      int activeTicks = CausalityCollapsePattern.activeSuctionTicks(scarDurationTicks);
      UUID ownerId = player.getUUID();

      for (int i = 0; i < count; i++) {
         ScarSpec spec = specs.get(i);
         // 联动群发裂痕若与同玩家已有活跃引力场过近，复用已有物理场不重复叠加，避免多场共扫卡顿或拉飞目标
         if (linkedMode && hasNearbyActiveField(ownerId, level, spec.center())) {
            continue;
         }
         trimOldestFieldsIfNeeded(ownerId);

         long activateAt = now + Math.max(0, spec.staggerTick());
         long expireAt = activateAt + activeTicks;
         float spinSign = ((i ^ (int)(now & 0xFF)) & 1) == 0 ? 1.0F : -1.0F;
         ActiveVortexScar scar = new ActiveVortexScar(
            player, ownerId, level, spec.center(), suctionRadius, maxSuctionTargets,
            visualScale, devourExecution, linkedMode, spinSign, activateAt, expireAt,
            i == 0 ? directLockedEntityId : -1
         );
         ACTIVE_SCARS.add(scar);

         // 独立挥刀立即生效时首帧即刻施加一次初始牵引，让锁中目标 0 帧感受到时空滞留与拉扯
         if (!linkedMode && spec.staggerTick() == 0) {
            List<DevourRequest> immediateExecutions = new ArrayList<>();
            tickSingleVortexScar(scar, now, new HashSet<>(), immediateExecutions);
            executeDevourRequests(immediateExecutions);
         }
      }
   }

   private static boolean hasNearbyActiveField(UUID ownerId, ServerLevel level, Vec3 center) {
      for (ActiveVortexScar existing : ACTIVE_SCARS) {
         if (existing.ownerId.equals(ownerId) && existing.level == level
               && existing.center.distanceToSqr(center) < LINKED_FIELD_MERGE_DIST_SQR) {
            return true;
         }
      }
      return false;
   }

   private static void trimOldestFieldsIfNeeded(UUID ownerId) {
      int owned = 0;
      int oldestLinkedIndex = -1;
      int oldestAnyIndex = -1;
      for (int i = 0; i < ACTIVE_SCARS.size(); i++) {
         ActiveVortexScar scar = ACTIVE_SCARS.get(i);
         if (!scar.ownerId.equals(ownerId)) {
            continue;
         }
         owned++;
         if (oldestAnyIndex < 0) {
            oldestAnyIndex = i;
         }
         if (scar.linkedMode && oldestLinkedIndex < 0) {
            oldestLinkedIndex = i;
         }
      }
      if (owned < MAX_ACTIVE_VORTEX_SCARS_PER_PLAYER) {
         return;
      }
      int removeIdx = oldestLinkedIndex >= 0 ? oldestLinkedIndex : oldestAnyIndex;
      if (removeIdx >= 0) {
         ActiveVortexScar removed = ACTIVE_SCARS.remove(removeIdx);
         releaseAllVictims(removed);
      }
   }

   @SubscribeEvent
   public static void onServerTick(TickEvent.ServerTickEvent event) {
      if (event.phase != TickEvent.Phase.END) {
         return;
      }
      if (!PENDING_BURSTS.isEmpty()) {
         Iterator<PendingScarBurst> iterator = PENDING_BURSTS.iterator();
         while (iterator.hasNext()) {
            PendingScarBurst pending = iterator.next();
            ServerPlayer player = pending.player;
            if (player.isRemoved() || !player.isAlive() || player.serverLevel() != pending.level
                  || !ModConfig.COMMON.annihilationBlade.causalityCollapse.enabled.getValue()) {
               iterator.remove();
               continue;
            }
            // 使用绝对 gameTime 判定，避免与 WorldRiftChain 同在 ServerTickEvent.END 下的监听器顺序造成 ±1 tick 偏移
            if (pending.level.getGameTime() < pending.fireAtGameTime) {
               continue;
            }
            iterator.remove();
            emitScarBurst(
               player, pending.level, pending.origin, pending.direction, pending.specs, pending.mode,
               pending.scarDurationTicks, pending.visualScale,
               pending.suctionRadius, pending.maxSuctionTargets, pending.devourExecution, -1
            );
         }
      }

      if (!ACTIVE_SCARS.isEmpty()) {
         boolean seEnabled = ModConfig.COMMON.annihilationBlade.causalityCollapse.enabled.getValue();
         // 每 tick 同一实体只接受距离最近的/最先命中的一道裂痕的牵引，防止多道裂痕叠加把生物拉穿地形
         Set<UUID> pulledThisTick = new HashSet<>();
         List<DevourRequest> pendingExecutions = new ArrayList<>();
         Iterator<ActiveVortexScar> iterator = ACTIVE_SCARS.iterator();
         while (iterator.hasNext()) {
            ActiveVortexScar scar = iterator.next();
            ServerPlayer owner = scar.owner;
            long now = scar.level.getGameTime();
            if (!seEnabled || owner.isRemoved() || !owner.isAlive() || owner.serverLevel() != scar.level
                  || now >= scar.expireAtGameTime) {
               releaseAllVictims(scar);
               iterator.remove();
               continue;
            }
            if (now < scar.activateAtGameTime) {
               continue;
            }
            tickSingleVortexScar(scar, now, pulledThisTick, pendingExecutions);
         }
         executeDevourRequests(pendingExecutions);
      }
   }

   private static void tickSingleVortexScar(
      ActiveVortexScar scar, long now, Set<UUID> pulledThisTick, List<DevourRequest> pendingExecutions
   ) {
      // 同一游戏 tick 内防重复步进（例如独立挥刀首帧已立即牵引过一次）
      if (scar.lastTickedGameTime == now) {
         return;
      }
      scar.lastTickedGameTime = now;
      ServerPlayer owner = scar.owner;
      ServerLevel level = scar.level;
      double radius = scar.suctionRadius;
      double releaseDistSqr = (radius + 1.25) * (radius + 1.25);

      // 1. 维护已捕获的受害者（剔除死亡、离场、超距或被更高优先级 SE 接管的目标）
      Iterator<Map.Entry<UUID, TrackedVictim>> existingIt = scar.victims.entrySet().iterator();
      while (existingIt.hasNext()) {
         Map.Entry<UUID, TrackedVictim> entry = existingIt.next();
         UUID uuid = entry.getKey();
         TrackedVictim victim = entry.getValue();
         Entity raw = level.getEntity(victim.entityId);
         if (!(raw instanceof LivingEntity target)
               || !target.getUUID().equals(uuid)
               || !target.isAlive()
               || !SlashBladeTargeting.canAttack(owner, target)
               || !isAvailableForRift(target)
               || SpecialEffectSupport.centerOf(target).distanceToSqr(scar.center) > releaseDistSqr) {
            releaseGravityHold(uuid, raw instanceof LivingEntity living ? living : null);
            existingIt.remove();
         }
      }

      // 2. 扫描吸附半径内的新合法非玩家目标补入裂痕漩涡场
      if (scar.victims.size() < scar.maxTargets) {
         List<LivingEntity> candidates = SpecialEffectSupport.radialTargets(
            level, owner, scar.center, radius,
            entity -> !(entity instanceof Player)
               && !scar.victims.containsKey(entity.getUUID())
               && isAvailableForRift(entity)
         );
         for (LivingEntity candidate : candidates) {
            if (scar.victims.size() >= scar.maxTargets) {
               break;
            }
            UUID uuid = candidate.getUUID();
            boolean bornInCore = candidate.getId() == scar.directLockedEntityId
               || SpecialEffectSupport.centerOf(candidate).distanceTo(scar.center)
                  <= CausalityCollapsePattern.coreCrushRadius(candidate.getBbWidth());
            acquireGravityHold(candidate);
            scar.victims.put(uuid, new TrackedVictim(candidate.getId(), bornInCore));
         }
      }

      if (scar.victims.isEmpty()) {
         return;
      }

      // 裂界独立单链正在跳跃期间，联动余威裂痕暂缓致死绞杀，避免提前吃掉单链的下一跳目标导致断链
      boolean deferLethalForSingleChain = scar.linkedMode && WorldRiftChain.isActiveFor(owner);

      // 3. 逐目标施加漩涡向心引力并推进核心空间绞杀判定
      Iterator<Map.Entry<UUID, TrackedVictim>> pullIt = scar.victims.entrySet().iterator();
      while (pullIt.hasNext()) {
         Map.Entry<UUID, TrackedVictim> entry = pullIt.next();
         UUID uuid = entry.getKey();
         TrackedVictim victim = entry.getValue();
         Entity raw = level.getEntity(victim.entityId);
         if (!(raw instanceof LivingEntity target) || !target.isAlive() || !isAvailableForRift(target)) {
            releaseGravityHold(uuid, raw instanceof LivingEntity living ? living : null);
            pullIt.remove();
            continue;
         }
         if (!pulledThisTick.add(uuid)) {
            continue;
         }

         Vec3 targetCenter = SpecialEffectSupport.centerOf(target);
         Vec3 toScar = scar.center.subtract(targetCenter);
         double dist = toScar.length();
         double coreRadius = CausalityCollapsePattern.coreCrushRadius(target.getBbWidth());

         applyVortexPull(target, toScar, dist, radius, coreRadius, scar.spinSign);

         if (dist <= coreRadius) {
            victim.coreTicks++;
            int requiredTicks = victim.bornInCore
               ? CausalityCollapsePattern.DIRECT_CRUSH_TICKS
               : CausalityCollapsePattern.PULLED_CRUSH_TICKS;
            if (scar.devourExecution && !deferLethalForSingleChain && victim.coreTicks >= requiredTicks) {
               // 先移出并释放重力持锁，延后至 ACTIVE_SCARS 外层迭代完毕后再发包与处决，彻底避免回调改集合引发 CME
               releaseGravityHold(uuid, target);
               pullIt.remove();
               pendingExecutions.add(new DevourRequest(owner, level, scar.center, target, scar.visualScale));
            }
         } else if (victim.coreTicks > 0) {
            victim.coreTicks = Math.max(0, victim.coreTicks - 1);
         }
      }
   }

   private static void executeDevourRequests(List<DevourRequest> requests) {
      if (requests.isEmpty()) {
         return;
      }
      for (DevourRequest req : requests) {
         if (req.owner.isRemoved() || !req.owner.isAlive() || req.owner.serverLevel() != req.level) {
            continue;
         }
         if (!req.target.isAlive() || req.target.isRemoved() || !SlashBladeTargeting.canAttack(req.owner, req.target)) {
            continue;
         }
         // 必须先发客户端吞噬绞杀包（保证目标尚未被移除可完整抓拍 3D 活体网格），再执行静默终焉抹除
         AnnihilationFxEvents.causalityCollapseDevour(req.level, req.owner, req.scarCenter, req.target, req.visualScale);
         TerminusLogic.executeSilent(req.target, req.owner);
      }
   }

   /**
    * 持续时空漩涡物理牵引：
    * 不设置 {@code Mob.setNoAi(true)}（避免原版跳过 {@code travel()} 导致生物被钉死拉不动），
    * 而是暂停寻路并叠加强向心引力 + 切向旋涡速度 + 轻度防卡地抬升，使目标如被空间气流卷入裂口般丝滑飞向喉心。
    */
   private static void applyVortexPull(
      LivingEntity target, Vec3 toScar, double dist, double suctionRadius, double coreRadius, float spinSign
   ) {
      target.setNoGravity(true);
      target.fallDistance = 0.0F;
      if (target instanceof Mob mob) {
         mob.getNavigation().stop();
      }

      Vec3 curVel = target.getDeltaMovement();
      if (dist <= coreRadius * 0.72) {
         // 喉心挤压态：紧贴裂痕中心轻微悬浮震颤，准备进入空间绞杀
         Vec3 holdVel = toScar.scale(0.32);
         target.setDeltaMovement(
            curVel.x * 0.25 + holdVel.x,
            Mth.clamp(curVel.y * 0.25 + holdVel.y, -0.18, 0.22),
            curVel.z * 0.25 + holdVel.z
         );
         target.hurtMarked = true;
         target.hasImpulse = true;
         return;
      }

      Vec3 radialDir = dist > 1.0E-4 ? toScar.scale(1.0 / dist) : Vec3.ZERO;
      double span = Math.max(0.6, suctionRadius - coreRadius * 0.72);
      double proximity = 1.0 - Mth.clamp((dist - coreRadius * 0.72) / span, 0.0, 1.0);
      // 越靠近裂痕引力呈二次曲线急剧增强
      double radialSpeed = Mth.lerp(proximity * proximity, 0.17, 0.52);

      // 绕世界竖轴叠加柔和切向旋涡分量，让被吸生物划出弧线卷入裂缝而非僵硬直飞
      Vec3 horiz = new Vec3(toScar.x, 0.0, toScar.z);
      double horizLen = horiz.length();
      Vec3 tang = horizLen > 1.0E-4
         ? new Vec3(-horiz.z / horizLen * spinSign, 0.0, horiz.x / horizLen * spinSign)
         : Vec3.ZERO;
      double tangSpeed = 0.14 * (1.0 - 0.58 * proximity);

      Vec3 desired = radialDir.scale(radialSpeed).add(tang.scale(tangSpeed));
      double desiredY = desired.y;
      if (target.onGround() && desiredY < 0.10) {
         // 贴地目标略微抬升离地，避免被草丛、半砖或 1 格台阶卡住
         desiredY = 0.11;
      }

      Vec3 nextVel = new Vec3(
         curVel.x * 0.52 + desired.x * 0.48,
         curVel.y * 0.50 + desiredY * 0.50,
         curVel.z * 0.52 + desired.z * 0.48
      );
      double speedSqr = nextVel.lengthSqr();
      double maxSpeed = 0.82;
      if (speedSqr > maxSpeed * maxSpeed) {
         nextVel = nextVel.scale(maxSpeed / Math.sqrt(speedSqr));
      }

      target.setDeltaMovement(nextVel);
      target.hurtMarked = true;
      target.hasImpulse = true;
   }

   /** 跨 SE 控制权避让检查：已被裂界、虚无权域、幻影审判或归墟回响锁定的目标交由对应 SE 独占。 */
   private static boolean isAvailableForRift(LivingEntity entity) {
      UUID uuid = entity.getUUID();
      return !WorldRiftChain.isEntitySuspended(uuid)
         && !VoidDominion.isEntitySuspended(uuid)
         && !PhantomJudgement.isEntityLocked(uuid)
         && !TerminusEcho.isEntityLocked(uuid);
   }

   @SubscribeEvent
   public static void onLivingDeath(LivingDeathEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         clearPlayer(player.getUUID());
      }
      releaseEntityAcrossAllScars(event.getEntity());
   }

   @SubscribeEvent
   public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
      if (event.getEntity() instanceof LivingEntity target) {
         releaseEntityAcrossAllScars(target);
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (ActiveVortexScar scar : ACTIVE_SCARS) {
         releaseAllVictims(scar);
      }
      ACTIVE_SCARS.clear();
      PENDING_BURSTS.clear();
      GRAVITY_HOLDS.clear();
      LAST_STANDALONE_TRIGGER.clear();
   }

   // ───────────────────────────── 无重力引用计数管理（保证全路径无损还原） ─────────────────────────────

   private static void acquireGravityHold(LivingEntity target) {
      UUID uuid = target.getUUID();
      GravityHold hold = GRAVITY_HOLDS.get(uuid);
      if (hold == null) {
         hold = new GravityHold(target, target.isNoGravity());
         GRAVITY_HOLDS.put(uuid, hold);
      }
      hold.references++;
      target.setNoGravity(true);
   }

   private static void releaseAllVictims(ActiveVortexScar scar) {
      for (Map.Entry<UUID, TrackedVictim> entry : scar.victims.entrySet()) {
         Entity raw = scar.level.getEntity(entry.getValue().entityId);
         LivingEntity resolved = raw instanceof LivingEntity living && living.getUUID().equals(entry.getKey()) ? living : null;
         releaseGravityHold(entry.getKey(), resolved);
      }
      scar.victims.clear();
   }

   private static void releaseEntityAcrossAllScars(LivingEntity target) {
      UUID uuid = target.getUUID();
      for (ActiveVortexScar scar : ACTIVE_SCARS) {
         if (scar.victims.remove(uuid) != null) {
            releaseGravityHold(uuid, target);
         }
      }
   }

   private static void releaseGravityHold(UUID uuid, LivingEntity resolved) {
      GravityHold hold = GRAVITY_HOLDS.get(uuid);
      if (hold == null) {
         return;
      }
      hold.references--;
      if (hold.references > 0) {
         return;
      }
      GRAVITY_HOLDS.remove(uuid);
      LivingEntity target = resolved != null ? resolved : hold.entity;
      target.setNoGravity(hold.originalNoGravity);
      if (target.isAlive() && !target.isRemoved()) {
         Vec3 vel = target.getDeltaMovement();
         target.setDeltaMovement(vel.scale(0.45));
         target.hurtMarked = true;
         target.hasImpulse = true;
      }
   }

   private static Vec3 safeDir(Vec3 dir) {
      if (dir == null || dir.lengthSqr() < 1.0E-6) {
         return new Vec3(0.0, 0.0, 1.0);
      }
      return dir.normalize();
   }

   // ───────────────────────────── 状态记录 ─────────────────────────────

   private static final class PendingScarBurst {
      final ServerPlayer player;
      final ServerLevel level;
      final Vec3 origin;
      final Vec3 direction;
      final List<ScarSpec> specs;
      final int mode;
      final int scarDurationTicks;
      final double visualScale;
      final double suctionRadius;
      final int maxSuctionTargets;
      final boolean devourExecution;
      final long fireAtGameTime;

      PendingScarBurst(
         ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 direction, List<ScarSpec> specs, int mode,
         int scarDurationTicks, double visualScale, double suctionRadius, int maxSuctionTargets,
         boolean devourExecution, long fireAtGameTime
      ) {
         this.player = player;
         this.level = level;
         this.origin = origin;
         this.direction = direction;
         this.specs = specs;
         this.mode = mode;
         this.scarDurationTicks = scarDurationTicks;
         this.visualScale = visualScale;
         this.suctionRadius = suctionRadius;
         this.maxSuctionTargets = maxSuctionTargets;
         this.devourExecution = devourExecution;
         this.fireAtGameTime = fireAtGameTime;
      }
   }

   private static final class ActiveVortexScar {
      final ServerPlayer owner;
      final UUID ownerId;
      final ServerLevel level;
      final Vec3 center;
      final double suctionRadius;
      final int maxTargets;
      final double visualScale;
      final boolean devourExecution;
      final boolean linkedMode;
      final float spinSign;
      final long activateAtGameTime;
      final long expireAtGameTime;
      final int directLockedEntityId;
      final Map<UUID, TrackedVictim> victims = new HashMap<>();
      long lastTickedGameTime = Long.MIN_VALUE;

      ActiveVortexScar(
         ServerPlayer owner, UUID ownerId, ServerLevel level, Vec3 center, double suctionRadius, int maxTargets,
         double visualScale, boolean devourExecution, boolean linkedMode, float spinSign,
         long activateAtGameTime, long expireAtGameTime, int directLockedEntityId
      ) {
         this.owner = owner;
         this.ownerId = ownerId;
         this.level = level;
         this.center = center;
         this.suctionRadius = suctionRadius;
         this.maxTargets = maxTargets;
         this.visualScale = visualScale;
         this.devourExecution = devourExecution;
         this.linkedMode = linkedMode;
         this.spinSign = spinSign;
         this.activateAtGameTime = activateAtGameTime;
         this.expireAtGameTime = expireAtGameTime;
         this.directLockedEntityId = directLockedEntityId;
      }
   }

   private static final class TrackedVictim {
      final int entityId;
      final boolean bornInCore;
      int coreTicks;

      TrackedVictim(int entityId, boolean bornInCore) {
         this.entityId = entityId;
         this.bornInCore = bornInCore;
      }
   }

   private static final class GravityHold {
      final LivingEntity entity;
      final boolean originalNoGravity;
      int references;

      GravityHold(LivingEntity entity, boolean originalNoGravity) {
         this.entity = entity;
         this.originalNoGravity = originalNoGravity;
      }
   }

   private record DevourRequest(
      ServerPlayer owner, ServerLevel level, Vec3 scarCenter, LivingEntity target, double visualScale
   ) {
   }
}
