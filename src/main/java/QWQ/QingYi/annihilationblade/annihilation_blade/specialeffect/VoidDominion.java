package QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.VoidDominionPattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.event.SlashBladeEvent.DoSlashEvent;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/** 虚无权域的服务端权威时间轴与逐目标处决调度。 */
@EventBusSubscriber(modid = "annihilationblade")
public class VoidDominion extends SpecialEffect {
   private static final Map<UUID, Long> LAST_TRIGGER = new HashMap<>();
   private static final Map<UUID, DominionSequence> ACTIVE = new HashMap<>();
   private static final Map<UUID, TargetSuspension> SUSPENSIONS = new HashMap<>();

   public VoidDominion() {
      super(0, false, false);
   }

   public static void clearPlayer(UUID playerId) {
      DominionSequence sequence = ACTIVE.remove(playerId);
      if (sequence != null) {
         sequence.releaseTargets();
         AnnihilationFxEvents.voidDominionEnd(sequence.handle);
      }
      LAST_TRIGGER.remove(playerId);
   }

   @SubscribeEvent
   public static void onPlayerDeath(LivingDeathEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         clearPlayer(player.getUUID());
      }
      releaseSuspension(event.getEntity());
   }

   @SubscribeEvent
   public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
      if (event.getEntity() instanceof LivingEntity target) {
         releaseSuspension(target);
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (DominionSequence sequence : ACTIVE.values()) {
         sequence.releaseTargets();
         AnnihilationFxEvents.voidDominionEnd(sequence.handle);
      }
      ACTIVE.clear();
      LAST_TRIGGER.clear();
      SUSPENSIONS.clear();
   }

   @SubscribeEvent
   public static void onDoingSlash(DoSlashEvent event) {
      if (!(event.getUser() instanceof ServerPlayer player) || Dankong.isActive(player)) {
         return;
      }

      ISlashBladeState state = event.getSlashBladeState();
      if (!state.hasSpecialEffect(ModSpecialEffects.VOID_DOMINION.getId())) {
         return;
      }

      ModConfig.VoidDominion config = ModConfig.COMMON.annihilationBlade.voidDominion;
      if (!config.enabled.getValue() || ACTIVE.containsKey(player.getUUID())) {
         return;
      }

      long gameTime = player.level().getGameTime();
      int configuredCooldown = config.cooldownTicks.getValue();
      // 防止 92 tick 的收尾尚未完成时同屏展开第二座权域。
      int cooldown = Math.max(configuredCooldown, VoidDominionPattern.TOTAL_LIFETIME);
      long last = LAST_TRIGGER.getOrDefault(player.getUUID(), -cooldown * 2L);
      if (gameTime - last < cooldown) {
         return;
      }

      ServerLevel level = player.serverLevel();
      double range = config.range.getValue();
      double visualScale = config.visualScale.getValue();
      Vec3 eye = player.getEyePosition();
      Vec3 direction = player.getLookAngle().normalize();
      LivingEntity focusTarget = rayTarget(player, eye, direction, range);
      Vec3 center = VoidDominionPattern.resolveCenter(level, eye, direction, range, focusTarget);
      List<LivingEntity> targets = SpecialEffectSupport.limit(
         SpecialEffectSupport.radialTargets(level, player, center, range), Math.max(1, config.maxTargets.getValue())
      );
      if (targets.isEmpty()) {
         return;
      }

      List<TargetPlan> plans = new ArrayList<>(targets.size());
      for (LivingEntity target : targets) {
         int eraseTick = VoidDominionPattern.eraseTick(SpecialEffectSupport.centerOf(target).distanceTo(center), range);
         plans.add(new TargetPlan(target.getId(), target.getUUID(), eraseTick));
      }

      long seed = level.getRandom().nextLong();
      int handle = AnnihilationFxEvents.voidDominionStart(
         level, player, center, range, visualScale, ids(plans), ticks(plans), seed, VoidDominionPattern.TOTAL_LIFETIME
      );
      ACTIVE.put(player.getUUID(), new DominionSequence(player, level, handle, center, range, visualScale, seed, plans));
      LAST_TRIGGER.put(player.getUUID(), gameTime);
   }

   @SubscribeEvent
   public static void onServerTick(TickEvent.ServerTickEvent event) {
      if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
         return;
      }

      Iterator<Map.Entry<UUID, DominionSequence>> iterator = ACTIVE.entrySet().iterator();
      while (iterator.hasNext()) {
         DominionSequence sequence = iterator.next().getValue();
         ServerPlayer player = sequence.player;
         ModConfig.VoidDominion config = ModConfig.COMMON.annihilationBlade.voidDominion;
         if (!player.isAlive() || player.isRemoved() || player.serverLevel() != sequence.level || !config.enabled.getValue()) {
            sequence.releaseTargets();
            AnnihilationFxEvents.voidDominionEnd(sequence.handle);
            iterator.remove();
            continue;
         }

         int age = sequence.age;
         if (age == VoidDominionPattern.CATALOG_END_TICK - 12) {
            prune(sequence);
            sendUpdate(sequence, AnnihilationFxEvents.VOID_PHASE_CATALOG);
         }
         if (age >= VoidDominionPattern.CATALOG_END_TICK - 12 && age < VoidDominionPattern.COLLAPSE_TICK) {
            freeze(sequence, age);
         }
         if (age >= VoidDominionPattern.CATALOG_END_TICK && age <= VoidDominionPattern.SWEEP_END_TICK) {
            if (age == VoidDominionPattern.SWEEP_START_TICK) {
               refreshSchedule(sequence);
               sendUpdate(sequence, AnnihilationFxEvents.VOID_PHASE_SWEEP);
            }
            executeArrived(sequence, age);
         }
         if (age == VoidDominionPattern.COLLAPSE_TICK) {
            AnnihilationFxEvents.voidDominionUpdate(
               sequence.handle, sequence.level, player, sequence.center, sequence.radius, sequence.scale,
               AnnihilationFxEvents.VOID_PHASE_COLLAPSE, new int[0], new int[0], sequence.seed
            );
            for (TargetPlan plan : sequence.targets) {
               LivingEntity target = resolveTarget(sequence, plan);
               if (target != null && SlashBladeTargeting.canAttack(player, target)) {
                  release(plan, target);
                  TerminusLogic.execute(target, player);
               }
            }
            sequence.releaseTargets();
            AnnihilationFxEvents.voidDominionEnd(sequence.handle);
            iterator.remove();
            continue;
         }
         sequence.age++;
      }
   }

   private static LivingEntity rayTarget(ServerPlayer player, Vec3 eye, Vec3 direction, double range) {
      double reach = Math.max(5.0, Math.min(22.0, range * 0.75));
      Vec3 end = eye.add(direction.scale(reach));
      AABB bounds = player.getBoundingBox().expandTowards(direction.scale(reach)).inflate(1.0);
      EntityHitResult result = ProjectileUtil.getEntityHitResult(
         player, eye, end, bounds,
         entity -> entity instanceof LivingEntity living && SpecialEffectSupport.canTarget(player, living),
         reach * reach
      );
      return result != null && result.getEntity() instanceof LivingEntity living ? living : null;
   }

   private static void prune(DominionSequence sequence) {
      pruneUnavailable(sequence);
   }

   private static void refreshSchedule(DominionSequence sequence) {
      pruneUnavailable(sequence);
      for (TargetPlan plan : sequence.targets) {
         LivingEntity target = resolveTarget(sequence, plan);
         if (target != null) {
            plan.eraseTick = VoidDominionPattern.eraseTick(SpecialEffectSupport.centerOf(target).distanceTo(sequence.center), sequence.radius);
         }
      }
   }

   private static void freeze(DominionSequence sequence, int age) {
      if ((age - (VoidDominionPattern.CATALOG_END_TICK - 12)) % 3 != 0) {
         return;
      }
      for (TargetPlan plan : sequence.targets) {
         LivingEntity target = resolveTarget(sequence, plan);
         if (target == null) {
            continue;
         }
         if (!plan.suspended) {
            TargetSuspension suspension = SUSPENSIONS.get(plan.uuid);
            if (suspension == null) {
               suspension = new TargetSuspension(
                  target, target.isNoGravity(), target instanceof Mob mob && mob.isNoAi(), target.getDeltaMovement()
               );
               SUSPENSIONS.put(plan.uuid, suspension);
            }
            suspension.references++;
            plan.suspended = true;
         }
         Vec3 velocity = target.getDeltaMovement();
         target.setNoGravity(true);
         if (target instanceof Mob mob) {
            mob.setNoAi(true);
         }
         target.setDeltaMovement(velocity.x * 0.08, Math.max(-0.005, Math.min(0.015, velocity.y * 0.08 + 0.004)), velocity.z * 0.08);
         target.fallDistance = 0.0F;
         target.hasImpulse = true;
      }
   }

   private static void executeArrived(DominionSequence sequence, int age) {
      List<TargetPlan> arrived = new ArrayList<>();
      sequence.targets.removeIf(plan -> {
         if (plan.eraseTick > age) {
            return false;
         }
         arrived.add(plan);
         return true;
      });
      if (arrived.isEmpty()) {
         return;
      }

      sendUpdate(sequence, AnnihilationFxEvents.VOID_PHASE_SWEEP);
      for (TargetPlan plan : arrived) {
         Entity entity = sequence.level.getEntity(plan.entityId);
         LivingEntity target = entity instanceof LivingEntity living && living.getUUID().equals(plan.uuid) ? living : null;
         release(plan, target);
         if (target != null) {
            if (SlashBladeTargeting.canAttack(sequence.player, target)) {
               TerminusLogic.execute(target, sequence.player);
            }
         }
      }
   }

   private static void pruneUnavailable(DominionSequence sequence) {
      Iterator<TargetPlan> iterator = sequence.targets.iterator();
      while (iterator.hasNext()) {
         TargetPlan plan = iterator.next();
         if (resolveTarget(sequence, plan) != null) {
            continue;
         }
         Entity entity = sequence.level.getEntity(plan.entityId);
         LivingEntity target = entity instanceof LivingEntity living && living.getUUID().equals(plan.uuid) ? living : null;
         release(plan, target);
         iterator.remove();
      }
   }

   private static void release(TargetPlan plan, LivingEntity target) {
      if (!plan.suspended) {
         return;
      }
      plan.suspended = false;
      TargetSuspension suspension = SUSPENSIONS.get(plan.uuid);
      if (suspension == null) {
         return;
      }
      suspension.references--;
      if (suspension.references > 0) {
         return;
      }
      SUSPENSIONS.remove(plan.uuid);
      LivingEntity restoreTarget = target != null ? target : suspension.entity;
      restoreTarget.setNoGravity(suspension.originalNoGravity);
      restoreTarget.setDeltaMovement(suspension.originalVelocity);
      restoreTarget.hasImpulse = true;
      if (restoreTarget instanceof Mob mob) {
         mob.setNoAi(suspension.originalNoAi);
      }
   }

   private static void releaseSuspension(LivingEntity target) {
      UUID targetId = target.getUUID();
      for (DominionSequence sequence : ACTIVE.values()) {
         for (TargetPlan plan : sequence.targets) {
            if (plan.uuid.equals(targetId)) {
               release(plan, target);
            }
         }
      }
   }

   private static void sendUpdate(DominionSequence sequence, int phase) {
      AnnihilationFxEvents.voidDominionUpdate(
         sequence.handle, sequence.level, sequence.player, sequence.center, sequence.radius, sequence.scale,
         phase, ids(sequence.targets), ticks(sequence.targets), sequence.seed
      );
   }

   private static LivingEntity resolveTarget(DominionSequence sequence, TargetPlan plan) {
      Entity entity = sequence.level.getEntity(plan.entityId);
      if (entity instanceof LivingEntity target && target.getUUID().equals(plan.uuid) && SlashBladeTargeting.canAttack(sequence.player, target)) {
         return target;
      }
      return null;
   }

   private static int[] ids(List<TargetPlan> plans) {
      int[] ids = new int[plans.size()];
      for (int i = 0; i < plans.size(); i++) {
         ids[i] = plans.get(i).entityId;
      }
      return ids;
   }

   private static int[] ticks(List<TargetPlan> plans) {
      int[] ticks = new int[plans.size()];
      for (int i = 0; i < plans.size(); i++) {
         ticks[i] = plans.get(i).eraseTick;
      }
      return ticks;
   }

   private static final class DominionSequence {
      final ServerPlayer player;
      final ServerLevel level;
      final int handle;
      final Vec3 center;
      final double radius;
      final double scale;
      final long seed;
      final List<TargetPlan> targets;
      int age;

      DominionSequence(ServerPlayer player, ServerLevel level, int handle, Vec3 center, double radius, double scale, long seed, List<TargetPlan> targets) {
         this.player = player;
         this.level = level;
         this.handle = handle;
         this.center = center;
         this.radius = radius;
         this.scale = scale;
         this.seed = seed;
         this.targets = targets;
      }

      void releaseTargets() {
         for (TargetPlan plan : this.targets) {
            Entity entity = this.level.getEntity(plan.entityId);
            if (entity instanceof LivingEntity target && target.getUUID().equals(plan.uuid)) {
               release(plan, target);
            } else {
               release(plan, null);
            }
         }
      }
   }

   private static final class TargetPlan {
      final int entityId;
      final UUID uuid;
      int eraseTick;
      boolean suspended;

      TargetPlan(int entityId, UUID uuid, int eraseTick) {
         this.entityId = entityId;
         this.uuid = uuid;
         this.eraseTick = eraseTick;
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
