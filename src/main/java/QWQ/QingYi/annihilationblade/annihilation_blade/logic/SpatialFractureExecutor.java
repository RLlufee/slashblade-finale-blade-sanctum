package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import QWQ.QingYi.annihilationblade.common.ServerTickScheduler;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.render.FxEvents;
import QWQ.QingYi.annihilationblade.render.FxTypes;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

/**
 * 湮灭之刃 SA「空间破碎·万象裂隙」服务端执行器。
 * <p>
 * 严格遵循 {@link SpatialFracturePattern} 双端共享时间轴：
 * <ul>
 *   <li>t = 0：锁定落点、收集目标、发送客户端特效包（不刷贴脸原生刀光以免遮挡双环与误触挥刀 SE）；</li>
 *   <li>t = BEAM_HIT_TICK：光束轰击落点，对域内合法目标施加空间禁锢与温和向心牵引；</li>
 *   <li>t = COLLAPSE_START_TICK：奇点黑洞成形，强力引力将目标卷向裂隙核心；</li>
 *   <li>t = DETONATE_TICK：伴随终极超新星爆轰，对全部锁定及域内合法目标同步执行 {@link TerminusLogic#execute} 终焉处决。</li>
 * </ul>
 */
public final class SpatialFractureExecutor {
   private SpatialFractureExecutor() {
   }

   public static void unleash(LivingEntity entity) {
      if (!(entity instanceof Player player) || entity.level().isClientSide) {
         return;
      }
      ServerLevel level = (ServerLevel)entity.level();
      ModConfig.SpatialFracture config = ModConfig.COMMON.annihilationBlade.spatialFracture;
      double radius = config.fractureRadius.getValue();
      double visualScale = config.visualScale.getValue();
      Vec3 center = getFractureCenter(level, player, config);
      Set<LivingEntity> initialTargets = gatherTargets(level, player, center, config);

      // 仅在施法者身前播放低沉启阵音效，其余全部高潮音效由客户端按时间轴精确卡点播放
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.5F, 0.48F);

      // 必须先发事件包，确保客户端在目标被处决前能完整解析目标实体 ID 与位置尺寸
      sendFractureFx(level, player, center, initialTargets, radius * visualScale, visualScale, config);

      Set<UUID> lockedIds = new LinkedHashSet<>(initialTargets.size());
      for (LivingEntity target : initialTargets) {
         lockedIds.add(target.getUUID());
      }
      int maxTargets = config.maxTargets.getValue();
      Vec3 core = center.add(0.0, 1.0, 0.0);

      // 阶段一：光束命中目标（空间破碎展开，温和向心束缚 + 持续阶段脉冲牵引，防止高速目标中途逃出爆区造成音画脱节）
      ServerTickScheduler.schedule(SpatialFracturePattern.BEAM_HIT_TICK, () -> {
         if (!isContextValid(level, player)) {
            return;
         }
         Set<LivingEntity> active = resolveActiveTargets(level, player, center, radius, lockedIds, maxTargets);
         for (LivingEntity target : active) {
            SpecialEffectSupport.pullToward(target, core, 0.55);
            Vec3 vel = target.getDeltaMovement();
            target.setDeltaMovement(vel.x * 0.35, Math.max(0.14, vel.y * 0.35), vel.z * 0.35);
            target.hasImpulse = true;
         }
      });

      // 中段持续束缚脉冲（每 6 tick 补一次向心牵引，覆盖 BEAM_HIT 到 COLLAPSE 之间的空档期）
      for (int holdTick = SpatialFracturePattern.BEAM_HIT_TICK + 6; holdTick < SpatialFracturePattern.COLLAPSE_START_TICK; holdTick += 6) {
         ServerTickScheduler.schedule(holdTick, () -> {
            if (!isContextValid(level, player)) {
               return;
            }
            Set<LivingEntity> active = resolveActiveTargets(level, player, center, radius, lockedIds, maxTargets);
            for (LivingEntity target : active) {
               SpecialEffectSupport.pullToward(target, core, 0.38);
               Vec3 vel = target.getDeltaMovement();
               target.setDeltaMovement(vel.x * 0.55, Math.max(0.06, vel.y * 0.55), vel.z * 0.55);
               target.hasImpulse = true;
            }
         });
      }

      // 阶段二：奇点黑洞坍缩（强力引力将目标卷向爆心等待终焉）
      ServerTickScheduler.schedule(SpatialFracturePattern.COLLAPSE_START_TICK, () -> {
         if (!isContextValid(level, player)) {
            return;
         }
         Set<LivingEntity> active = resolveActiveTargets(level, player, center, radius, lockedIds, maxTargets);
         for (LivingEntity target : active) {
            SpecialEffectSupport.pullToward(target, core, 1.45);
            Vec3 vel = target.getDeltaMovement();
            target.setDeltaMovement(vel.x, Math.max(0.22, vel.y), vel.z);
            target.hasImpulse = true;
         }
      });

      // 坍缩尾段持续吸聚（防止目标在 COLLAPSE 到 DETONATE 间弹飞）
      for (int pullTick = SpatialFracturePattern.COLLAPSE_START_TICK + 5; pullTick < SpatialFracturePattern.DETONATE_TICK; pullTick += 5) {
         ServerTickScheduler.schedule(pullTick, () -> {
            if (!isContextValid(level, player)) {
               return;
            }
            Set<LivingEntity> active = resolveActiveTargets(level, player, center, radius, lockedIds, maxTargets);
            for (LivingEntity target : active) {
               SpecialEffectSupport.pullToward(target, core, 0.95);
               Vec3 vel = target.getDeltaMovement();
               target.setDeltaMovement(vel.x * 0.65, Math.max(0.08, vel.y * 0.65), vel.z * 0.65);
               target.hasImpulse = true;
            }
         });
      }

      // 阶段三：终极绚烂超新星爆轰瞬间——同步终焉处决全部目标
      ServerTickScheduler.schedule(SpatialFracturePattern.DETONATE_TICK, () -> {
         if (!isContextValid(level, player)) {
            return;
         }
         Set<LivingEntity> active = resolveActiveTargets(level, player, center, radius, lockedIds, maxTargets);
         for (LivingEntity target : active) {
            if (target.isAlive()) {
               TerminusLogic.execute(target, player);
            }
         }
      });
   }

   private static boolean isContextValid(ServerLevel level, Player player) {
      return level.getServer() != null
         && level.getServer().isRunning()
         && !player.isRemoved()
         && player.level() == level;
   }

   private static Set<LivingEntity> resolveActiveTargets(
      ServerLevel level, Player player, Vec3 center, double radius, Set<UUID> lockedIds, int maxTargets
   ) {
      Set<LivingEntity> result = new LinkedHashSet<>();
      for (UUID uuid : lockedIds) {
         if (result.size() >= maxTargets) {
            return result;
         }
         Entity entity = level.getEntity(uuid);
         if (entity instanceof LivingEntity living && living.isAlive() && canTarget(player, living)) {
            result.add(living);
         }
      }
      AABB fracture = new AABB(center, center).inflate(radius);
      for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, fracture, entity -> canTarget(player, entity))) {
         if (result.size() >= maxTargets) {
            break;
         }
         if (candidate.isAlive() && candidate.position().distanceToSqr(center) <= radius * radius) {
            result.add(candidate);
         }
      }
      return result;
   }

   /**
    * 发送「空间破碎」特效事件包（保持协议字段完全不变）。
    */
   private static void sendFractureFx(
      ServerLevel level, Player player, Vec3 center, Set<LivingEntity> targets, double visualRadius, double visualScale, ModConfig.SpatialFracture config
   ) {
      int limit = Math.min(Math.min(targets.size(), config.maxVisualizedTargets.getValue()), FxEventPacket.MAX_ENTITIES);
      int[] ids = new int[limit];
      int index = 0;
      for (LivingEntity target : targets) {
         if (index >= limit) {
            break;
         }
         ids[index++] = target.getId();
      }
      FxEvents.send(level, new FxEventPacket(
         FxTypes.SPATIAL_FRACTURE,
         center,
         player.getLookAngle(),
         (float)visualRadius,
         (float)visualScale,
         config.fractureSlashes.getValue(),
         player.getRandom().nextLong(),
         player.getId(),
         ids
      ));
   }

   private static Vec3 getFractureCenter(ServerLevel level, Player player, ModConfig.SpatialFracture config) {
      double maxDistance = config.maxDistance.getValue();
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle().normalize();
      Vec3 end = eye.add(look.scale(maxDistance));
      HitResult hit = level.clip(new ClipContext(eye, end, Block.COLLIDER, Fluid.NONE, player));
      Vec3 blockOrAir = hit.getType() == Type.MISS ? end : hit.getLocation();
      Vec3 aimedTarget = findAimedTargetCenter(level, player, eye, look, eye.distanceTo(blockOrAir), config.entityLockRadius.getValue());
      return aimedTarget == null ? blockOrAir : aimedTarget;
   }

   private static Vec3 findAimedTargetCenter(ServerLevel level, Player player, Vec3 eye, Vec3 look, double maxDistance, double entityLockRadius) {
      Vec3 end = eye.add(look.scale(maxDistance));
      AABB rayArea = new AABB(eye, end).inflate(entityLockRadius);
      Vec3 bestCenter = null;
      double bestProjection = Double.MAX_VALUE;

      for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, rayArea, entity -> canTarget(player, entity))) {
         Vec3 candidateCenter = candidate.position().add(0.0, candidate.getBbHeight() * 0.5, 0.0);
         double projection = candidateCenter.subtract(eye).dot(look);
         if (!(projection < 0.0) && !(projection > maxDistance)) {
            Vec3 nearestPoint = eye.add(look.scale(projection));
            double lockRadius = entityLockRadius + candidate.getBbWidth() * 0.5;
            if (candidateCenter.distanceToSqr(nearestPoint) <= lockRadius * lockRadius && projection < bestProjection) {
               bestProjection = projection;
               bestCenter = candidateCenter;
            }
         }
      }

      return bestCenter;
   }

   private static Set<LivingEntity> gatherTargets(ServerLevel level, Player player, Vec3 center, ModConfig.SpatialFracture config) {
      double radius = config.fractureRadius.getValue();
      int maxTargets = config.maxTargets.getValue();
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle().normalize();
      Set<LivingEntity> targets = new LinkedHashSet<>();
      AABB fracture = new AABB(center, center).inflate(radius);

      for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, fracture, entity -> canTarget(player, entity))) {
         if (candidate.position().distanceToSqr(center) <= radius * radius) {
            targets.add(candidate);
            if (targets.size() >= maxTargets) {
               return targets;
            }
         }
      }

      double pathLength = Math.min(config.maxDistance.getValue(), eye.distanceTo(center));
      if (pathLength >= 2.0 && targets.size() < maxTargets) {
         double sampleRadius = config.raySampleRadius.getValue();
         double sampleRadiusSqr = sampleRadius * sampleRadius;
         Vec3 rayEnd = eye.add(look.scale(pathLength));
         AABB rayBox = new AABB(eye, rayEnd).inflate(sampleRadius);

         for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, rayBox, entity -> canTarget(player, entity))) {
            if (targets.contains(candidate)) {
               continue;
            }
            Vec3 candidatePos = candidate.position().add(0.0, candidate.getBbHeight() * 0.5, 0.0);
            Vec3 v = candidatePos.subtract(eye);
            double proj = v.dot(look);
            if (proj >= 2.0 && proj <= pathLength) {
               double distToRaySqr = v.lengthSqr() - proj * proj;
               if (distToRaySqr <= sampleRadiusSqr) {
                  targets.add(candidate);
                  if (targets.size() >= maxTargets) {
                     break;
                  }
               }
            }
         }
      }

      if (targets.isEmpty()) {
         targets.addAll(SpecialEffectSupport.limit(
            SpecialEffectSupport.radialTargets(level, player, player.position(), config.backupRadius.getValue()),
            maxTargets
         ));
      }

      return targets;
   }

   private static boolean canTarget(Player player, LivingEntity candidate) {
      return SlashBladeTargeting.canAttack(player, candidate);
   }
}
