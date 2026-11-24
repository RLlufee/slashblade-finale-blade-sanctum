package QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.CausalityCollapsePattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.CausalityCollapsePattern.ScarSpec;
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
 * 定位为承接「裂界·万花筒神威」斩杀余威的空间撕裂美学 SE，完全移除致死处决，仅在裂痕初裂瞬间对局部范围内敌对生物
 * 施加零伤害、不抢人头的极短暂微扰定身（默认 6 tick = 0.3 秒）：
 * <ul>
 *   <li><b>神威双模式无缝接管（断空群体联动 + 独立单链）</b>：在神威拧杀目标将躯壳即将吸穿奇点的瞬间，
 *       跳过黑洞闭合回弹，于每个被斩杀敌人的精确 3D 身位区域留下一道持久的裂界同源螺旋扭曲 × 虹彩色散时空裂痕，营造强烈的战后余威震慑；</li>
 *   <li><b>独立普攻挥刀触发模式</b>：平时挥刀（含对空，且未触发断空或单链裂界时）直接在视线焦点前方斜劈出一道局部时空裂痕。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public class CausalityCollapse extends SpecialEffect {
   private static final int MAX_SCARS_PER_BURST = 96;
   private static final Map<UUID, Long> LAST_STANDALONE_TRIGGER = new HashMap<>();
   private static final List<PendingScarBurst> PENDING_BURSTS = new ArrayList<>();
   private static final List<ActiveStasis> ACTIVE_STASIS = new ArrayList<>();
   private static final Map<UUID, TargetSuspension> SUSPENSIONS = new HashMap<>();

   public CausalityCollapse() {
      super(0, false, false);
   }

   public static void clearPlayer(UUID playerId) {
      PENDING_BURSTS.removeIf(burst -> burst.player.getUUID().equals(playerId));
      Iterator<ActiveStasis> iterator = ACTIVE_STASIS.iterator();
      while (iterator.hasNext()) {
         ActiveStasis stasis = iterator.next();
         if (stasis.ownerId.equals(playerId)) {
            releaseStasis(stasis);
            iterator.remove();
         }
      }
      LAST_STANDALONE_TRIGGER.remove(playerId);
   }

   // ───────────────────────────── 三路触发入口 ─────────────────────────────

   /**
    * 独立空挥入口：由 {@code AnnihilationInputRouter} 在确认非左+右虚无权域双键、未触发断空且非裂界单链时调用。
    * 对空气挥刀即可在视线前方安全位置切出一道局部 UV 错位因果裂痕。
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

      Vec3 anchor = CausalityCollapsePattern.resolveAirScarAnchor(level, player);
      Vec3 look = safeDir(player.getLookAngle());
      List<ScarSpec> specs = List.of(new ScarSpec(anchor, 2.0F, 0));
      emitScarBurst(
         player, level, anchor, look, specs, AnnihilationFxEvents.CAUSALITY_MODE_STANDALONE,
         config.scarDurationTicks.getValue(), config.visualScale.getValue(),
         config.stasisRadius.getValue(), config.stasisTicks.getValue(), config.maxStasisTargets.getValue()
      );
      return true;
   }

   /**
    * 神威独立单链联动入口：每拧断一个目标，等待其 3D 躯壳吸穿奇点后，在该敌人精确原位留下一道 UV 错位因果裂痕。
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
      PENDING_BURSTS.add(new PendingScarBurst(
         player, level, center, safeDir(lookHint), specs,
         AnnihilationFxEvents.CAUSALITY_MODE_LINKED_SINGLE,
         config.scarDurationTicks.getValue(), config.visualScale.getValue(),
         config.stasisRadius.getValue(), config.stasisTicks.getValue(), config.maxStasisTargets.getValue(),
         level.getGameTime() + CausalityCollapsePattern.singleRiftScarDelay()
      ));
   }

   /**
    * 断空 + 神威群体联动入口：预约在每个残骸神威奇点吸穿的错峰瞬间，于全战场各敌人原位绽开一片因果时空裂痕群。
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
      PENDING_BURSTS.add(new PendingScarBurst(
         player, level, dankongOrigin, safeDir(player.getLookAngle()), copy,
         AnnihilationFxEvents.CAUSALITY_MODE_LINKED_GROUP,
         config.scarDurationTicks.getValue(), config.visualScale.getValue(),
         config.stasisRadius.getValue(), config.stasisTicks.getValue(), config.maxStasisTargets.getValue(),
         level.getGameTime() + CausalityCollapsePattern.groupRiftScarDelay()
      ));
   }

   private static void emitScarBurst(
      ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 direction, List<ScarSpec> specs, int mode,
      int scarDurationTicks, double visualScale, double stasisRadius, int stasisTicks, int maxStasisTargets
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

      // 裂痕初裂瞬间对各裂痕局部范围内的存活敌对生物施加 0 伤害、不抢人头的极短暂 0.3s 微扰定身
      if (stasisTicks > 0 && stasisRadius > 0.5) {
         engageLocalMicroStasis(player, level, specs, stasisRadius, stasisTicks, maxStasisTargets);
      }
   }

   // ───────────────────────────── 服务端调度与零伤害微扰维护 ─────────────────────────────

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
               pending.stasisRadius, pending.stasisTicks, pending.maxStasisTargets
            );
         }
      }
      if (!ACTIVE_STASIS.isEmpty()) {
         Iterator<ActiveStasis> iterator = ACTIVE_STASIS.iterator();
         while (iterator.hasNext()) {
            ActiveStasis stasis = iterator.next();
            stasis.remainingTicks--;
            if (stasis.remainingTicks <= 0) {
               releaseStasis(stasis);
               iterator.remove();
               continue;
            }
            maintainStasis(stasis);
         }
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
      if (event.getEntity() instanceof LivingEntity target) {
         releaseSuspensionByEntity(target);
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (ActiveStasis stasis : ACTIVE_STASIS) {
         releaseStasis(stasis);
      }
      ACTIVE_STASIS.clear();
      PENDING_BURSTS.clear();
      SUSPENSIONS.clear();
      LAST_STANDALONE_TRIGGER.clear();
   }

   // ───────────────────────────── 零伤害局部微扰定身辅助 ─────────────────────────────

   private static void engageLocalMicroStasis(
      ServerPlayer player, ServerLevel level, List<ScarSpec> specs, double radius, int stasisTicks, int maxTargets
   ) {
      Set<UUID> seen = new HashSet<>();
      List<HeldTarget> held = new ArrayList<>();
      for (ScarSpec spec : specs) {
         if (held.size() >= maxTargets) {
            break;
         }
         List<LivingEntity> candidates = SpecialEffectSupport.radialTargets(
            level, player, spec.center(), radius,
            entity -> !(entity instanceof Player)
               && !seen.contains(entity.getUUID())
               && !WorldRiftChain.isEntitySuspended(entity.getUUID())
               && !VoidDominion.isEntitySuspended(entity.getUUID())
               && !PhantomJudgement.isEntityLocked(entity.getUUID())
         );
         for (LivingEntity target : candidates) {
            if (held.size() >= maxTargets) {
               break;
            }
            UUID uuid = target.getUUID();
            seen.add(uuid);
            TargetSuspension suspension = SUSPENSIONS.get(uuid);
            if (suspension == null) {
               suspension = new TargetSuspension(
                  target, target.isNoGravity(), target instanceof Mob mob && mob.isNoAi(), target.getDeltaMovement()
               );
               SUSPENSIONS.put(uuid, suspension);
            }
            suspension.references++;
            held.add(new HeldTarget(target.getId(), uuid, spec.center()));
            applyMicroTug(target, spec.center(), true);
         }
      }
      if (!held.isEmpty()) {
         ACTIVE_STASIS.add(new ActiveStasis(player.getUUID(), level, held, stasisTicks));
      }
   }

   private static void maintainStasis(ActiveStasis stasis) {
      Iterator<HeldTarget> iterator = stasis.targets.iterator();
      while (iterator.hasNext()) {
         HeldTarget held = iterator.next();
         Entity raw = stasis.level.getEntity(held.entityId);
         if (raw instanceof LivingEntity target && target.getUUID().equals(held.uuid) && target.isAlive()) {
            applyMicroTug(target, held.scarAnchor, false);
         } else {
            releaseOneSuspension(held.uuid, raw instanceof LivingEntity living ? living : null);
            iterator.remove();
         }
      }
   }

   /** 极短暂 0 伤害定身与错位轻扯：仅减速与向裂痕锚点微拉，不造成任何伤害、绝不抢人头。 */
   private static void applyMicroTug(LivingEntity target, Vec3 scarAnchor, boolean initialCrack) {
      target.setNoGravity(true);
      if (target instanceof Mob mob) {
         mob.setNoAi(true);
      }
      Vec3 velocity = target.getDeltaMovement();
      Vec3 toScar = scarAnchor.subtract(SpecialEffectSupport.centerOf(target));
      double dist = toScar.length();
      double tug = initialCrack && dist > 0.4 ? Math.min(0.14, dist * 0.04) : 0.0;
      Vec3 pull = dist > 1.0E-4 ? toScar.scale(tug / dist) : Vec3.ZERO;
      double lift = target.onGround() ? 0.032 : 0.004;
      target.setDeltaMovement(
         velocity.x * 0.08 + pull.x,
         Math.max(-0.01, Math.min(0.05, velocity.y * 0.08 + lift + pull.y * 0.5)),
         velocity.z * 0.08 + pull.z
      );
      target.fallDistance = 0.0F;
      target.hasImpulse = true;
   }

   private static void releaseStasis(ActiveStasis stasis) {
      for (HeldTarget held : stasis.targets) {
         Entity raw = stasis.level.getEntity(held.entityId);
         LivingEntity resolved = raw instanceof LivingEntity living && living.getUUID().equals(held.uuid) ? living : null;
         releaseOneSuspension(held.uuid, resolved);
      }
      stasis.targets.clear();
   }

   private static void releaseSuspensionByEntity(LivingEntity target) {
      UUID uuid = target.getUUID();
      for (ActiveStasis stasis : ACTIVE_STASIS) {
         Iterator<HeldTarget> iterator = stasis.targets.iterator();
         while (iterator.hasNext()) {
            HeldTarget held = iterator.next();
            if (held.uuid.equals(uuid)) {
               releaseOneSuspension(uuid, target);
               iterator.remove();
            }
         }
      }
   }

   private static void releaseOneSuspension(UUID uuid, LivingEntity resolved) {
      TargetSuspension suspension = SUSPENSIONS.get(uuid);
      if (suspension == null) {
         return;
      }
      suspension.references--;
      if (suspension.references > 0) {
         return;
      }
      SUSPENSIONS.remove(uuid);
      LivingEntity target = resolved != null ? resolved : suspension.entity;
      target.setNoGravity(suspension.originalNoGravity);
      if (target instanceof Mob mob) {
         mob.setNoAi(suspension.originalNoAi);
      }
      target.setDeltaMovement(suspension.originalVelocity.scale(0.35));
      target.hasImpulse = true;
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
      final double stasisRadius;
      final int stasisTicks;
      final int maxStasisTargets;
      final long fireAtGameTime;

      PendingScarBurst(
         ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 direction, List<ScarSpec> specs, int mode,
         int scarDurationTicks, double visualScale, double stasisRadius, int stasisTicks, int maxStasisTargets, long fireAtGameTime
      ) {
         this.player = player;
         this.level = level;
         this.origin = origin;
         this.direction = direction;
         this.specs = specs;
         this.mode = mode;
         this.scarDurationTicks = scarDurationTicks;
         this.visualScale = visualScale;
         this.stasisRadius = stasisRadius;
         this.stasisTicks = stasisTicks;
         this.maxStasisTargets = maxStasisTargets;
         this.fireAtGameTime = fireAtGameTime;
      }
   }

   private static final class ActiveStasis {
      final UUID ownerId;
      final ServerLevel level;
      final List<HeldTarget> targets;
      int remainingTicks;

      ActiveStasis(UUID ownerId, ServerLevel level, List<HeldTarget> targets, int remainingTicks) {
         this.ownerId = ownerId;
         this.level = level;
         this.targets = targets;
         this.remainingTicks = remainingTicks;
      }
   }

   private static final class HeldTarget {
      final int entityId;
      final UUID uuid;
      final Vec3 scarAnchor;

      HeldTarget(int entityId, UUID uuid, Vec3 scarAnchor) {
         this.entityId = entityId;
         this.uuid = uuid;
         this.scarAnchor = scarAnchor;
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
