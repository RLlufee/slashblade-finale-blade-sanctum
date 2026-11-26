package QWQ.QingYi.annihilationblade.infinity_stellaris.logic;

import QWQ.QingYi.annihilationblade.common.EntitySuspensionManager;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.infinity_stellaris.InfinityStellarisDefinitions;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import QWQ.QingYi.annihilationblade.infinity_stellaris.visual.InfinityStellarisVisuals;
import QWQ.QingYi.annihilationblade.network.ModNetwork;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * SE 4 · 曲率撕裂（Curvature Rupture）服务端权威逻辑。
 * <p>
 * <ul>
 *   <li>全面接入全局 {@link EntitySuspensionManager}（Token：{@code curvature_rupture:<ownerId>}），
 *       离圈、关闭、切手、死亡、跨维、停服全部精确还原原始 {@code NoAi}/{@code NoGravity}，彻底根治永久木桩；</li>
 *   <li>支持 SE 独立拆装至任意未破损/未封印拔刀剑；</li>
 *   <li>收敛为单键 {@code I} + S2C 权威回执同步，杜绝双端反相；移除每 2 tick 72 粒子炸服粒子海；</li>
 *   <li>满应力层数触发度规撕裂坍缩：剥离增益与吸收盾、清无敌帧、向心捏压、高额曲率震荡伤害 + 叠 2 层熵增印记。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public final class CurvatureRuptureLogic {
   private static final Map<UUID, Boolean> DOMAIN_ENABLED = new HashMap<>();
   private static final Map<UUID, Set<UUID>> HELD_TARGETS = new HashMap<>();
   private static final Map<UUID, Map<UUID, Integer>> STRAIN_MARKS = new HashMap<>();
   private static final Set<UUID> INTERNAL_DAMAGE = new HashSet<>();

   private CurvatureRuptureLogic() {
   }

   public static boolean isInternalDamage(LivingEntity target) {
      return target != null && INTERNAL_DAMAGE.contains(target.getUUID());
   }

   @SubscribeEvent
   public static void onPlayerTick(PlayerTickEvent event) {
      if (event.phase != Phase.END || event.player.level().isClientSide) {
         return;
      }
      if (!(event.player instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
         return;
      }

      ModConfig.CurvatureRuptureConfig config = ModConfig.COMMON.infinityStellaris.curvatureRupture;
      Optional<ISlashBladeState> heldState = config.enabled.getValue()
         ? InfinityStellarisItemSupport.findHeldActiveSpecialEffect(player, ModSpecialEffects.CURVATURE_RUPTURE.getId())
         : Optional.empty();

      if (heldState.isEmpty()) {
         if (isAiErasureEnabled(player)) {
            disableAndSync(player, false);
         } else {
            releaseAllForPlayer(player.getServer(), player.getUUID());
         }
         return;
      }

      if (isAiErasureEnabled(player)) {
         float rawPanel = heldState.get().getBaseAttackModifier();
         float basePanel = Math.max(10.0F, rawPanel > 0.0F ? rawPanel : InfinityStellarisDefinitions.BASE_ATTACK_MODIFIER);
         tickDomain(level, player, basePanel, config);
      } else {
         releaseAllForPlayer(player.getServer(), player.getUUID());
      }
   }

   @SubscribeEvent
   public static void onLivingDeath(LivingDeathEvent event) {
      if (event.getEntity().level().isClientSide) {
         return;
      }
      if (event.getEntity() instanceof ServerPlayer player) {
         if (isAiErasureEnabled(player)) {
            disableAndSync(player, false);
         } else {
            clearPlayer(player);
         }
      }
      pruneTargetFromTracking(event.getEntity().getUUID());
   }

   @SubscribeEvent
   public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
      if (!event.getLevel().isClientSide && event.getEntity() instanceof LivingEntity living) {
         pruneTargetFromTracking(living.getUUID());
      }
   }

   public static boolean isAiErasureEnabled(Player player) {
      return player != null && DOMAIN_ENABLED.getOrDefault(player.getUUID(), false);
   }

   /**
    * 处理来自客户端单键 {@code I} 的权威切换请求并回传最终落地状态。
    */
   public static boolean handleToggleRequest(ServerPlayer player, boolean requested) {
      ModConfig.CurvatureRuptureConfig config = ModConfig.COMMON.infinityStellaris.curvatureRupture;
      boolean canEnable = config.enabled.getValue()
         && InfinityStellarisItemSupport.isHoldingActiveSpecialEffect(player, ModSpecialEffects.CURVATURE_RUPTURE.getId());

      if (requested && canEnable) {
         DOMAIN_ENABLED.put(player.getUUID(), true);
         ModNetwork.sendCurvatureRuptureSync(player, true, true);
         return true;
      }

      boolean wasEnabled = isAiErasureEnabled(player);
      DOMAIN_ENABLED.remove(player.getUUID());
      releaseAllForPlayer(player.getServer(), player.getUUID());
      // 若是主动关或非法开启请求，均下发服务端当前权威状态纠偏
      ModNetwork.sendCurvatureRuptureSync(player, false, !requested && canEnable && wasEnabled);
      return false;
   }

   public static void clearPlayer(Player player) {
      if (player == null) {
         return;
      }
      boolean wasEnabled = DOMAIN_ENABLED.remove(player.getUUID()) != null;
      STRAIN_MARKS.remove(player.getUUID());
      releaseAllForPlayer(player.getServer(), player.getUUID());
      if (wasEnabled && player instanceof ServerPlayer serverPlayer) {
         ModNetwork.sendCurvatureRuptureSync(serverPlayer, false, false);
      }
   }

   public static void clearAll() {
      DOMAIN_ENABLED.clear();
      HELD_TARGETS.clear();
      STRAIN_MARKS.clear();
      INTERNAL_DAMAGE.clear();
   }

   private static void disableAndSync(ServerPlayer player, boolean showMessage) {
      DOMAIN_ENABLED.remove(player.getUUID());
      STRAIN_MARKS.remove(player.getUUID());
      releaseAllForPlayer(player.getServer(), player.getUUID());
      ModNetwork.sendCurvatureRuptureSync(player, false, showMessage);
   }

   private static void tickDomain(
      ServerLevel level, ServerPlayer player, float basePanel, ModConfig.CurvatureRuptureConfig config
   ) {
      UUID playerId = player.getUUID();
      String token = tokenOf(playerId);
      double radius = Math.max(4.0, config.curvatureRadius.getValue());
      int maxTargets = Math.max(1, config.curvatureMaxTargets.getValue());

      List<LivingEntity> targets = SpecialEffectSupport.limit(
         SpecialEffectSupport.radialTargets(level, player, player.position(), radius),
         maxTargets
      );

      Set<UUID> currentIds = new HashSet<>(targets.size());
      for (LivingEntity target : targets) {
         currentIds.add(target.getUUID());
      }

      // 关键修复：立即释放已走出范围或死亡的目标，杜绝离圈/卸区块导致永久木桩
      Set<UUID> held = HELD_TARGETS.computeIfAbsent(playerId, ignored -> new HashSet<>());
      if (!held.isEmpty()) {
         Iterator<UUID> it = held.iterator();
         while (it.hasNext()) {
            UUID prevId = it.next();
            if (!currentIds.contains(prevId)) {
               releaseSingleTarget(player.getServer(), prevId, token, config.legacyKeepNoAiOnRelease.getValue());
               it.remove();
            }
         }
      }

      long gameTime = level.getGameTime();
      int interval = Math.max(4, config.curvatureTickInterval.getValue());
      boolean strainTick = gameTime % interval == 0L;

      for (LivingEntity target : targets) {
         UUID targetId = target.getUUID();
         EntitySuspensionManager.acquire(target, token, true, true);
         held.add(targetId);

         // 清空惯性动量与仇恨寻路
         target.stopRiding();
         target.ejectPassengers();
         target.setDeltaMovement(Vec3.ZERO);
         target.fallDistance = 0.0F;
         target.hasImpulse = true;
         if (target instanceof Mob mob) {
            mob.setTarget(null);
            mob.getNavigation().stop();
         }

         if (strainTick) {
            applyCurvatureStrain(level, player, target, basePanel, config);
         }
      }

      if (strainTick) {
         clearMissingStrainMarks(playerId, currentIds);
      }
      if (gameTime % 10L == 0L) {
         InfinityStellarisVisuals.spawnCurvatureDomainPulse(
            level, player.position(), radius, gameTime, targets, STRAIN_MARKS.get(playerId)
         );
      }
      if (held.isEmpty()) {
         HELD_TARGETS.remove(playerId);
      }
   }

   private static void applyCurvatureStrain(
      ServerLevel level, ServerPlayer player, LivingEntity target, float basePanel, ModConfig.CurvatureRuptureConfig config
   ) {
      if (!target.isAlive() || !SlashBladeTargeting.canAttack(player, target)) {
         return;
      }
      int marks = addStrainMark(player.getUUID(), target.getUUID());
      int threshold = Math.max(1, config.curvatureBurstMarks.getValue());
      if (marks >= threshold) {
         clearStrainMark(player.getUUID(), target.getUUID());
         triggerMetricCollapse(level, player, target, basePanel, config);
      }
   }

   private static void triggerMetricCollapse(
      ServerLevel level, ServerPlayer player, LivingEntity target, float basePanel, ModConfig.CurvatureRuptureConfig config
   ) {
      if (!target.isAlive() || !SlashBladeTargeting.canAttack(player, target)) {
         return;
      }

      // 剥离全部药水增益与吸收盾、清无敌帧并执行向心捏压（绝不绕过 EntitySuspensionManager 私写 setNoAi）
      target.stopRiding();
      target.ejectPassengers();
      target.removeAllEffects();
      target.setAbsorptionAmount(0.0F);
      target.invulnerableTime = 0;
      SpecialEffectSupport.pullToward(target, player.position().add(0.0, 1.0, 0.0), 0.55);
      target.hurtMarked = true;

      Vec3 center = SpecialEffectSupport.centerOf(target);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.4F, 0.85F);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.1F, 0.6F);
      InfinityStellarisVisuals.spawnCurvatureCollapsePlaceholder(level, target);

      float damage = basePanel * 2.0F + target.getMaxHealth() * 0.20F;
      DamageSource source = level.damageSources().indirectMagic(player, player);
      UUID targetId = target.getUUID();
      INTERNAL_DAMAGE.add(targetId);
      try {
         target.hurt(source, damage);
      } finally {
         INTERNAL_DAMAGE.remove(targetId);
      }

      int entropyMarks = Math.max(0, config.entropyMarksApplied.getValue());
      if (target.isAlive() && entropyMarks > 0) {
         EntropyDissolutionLogic.applyMarks(level, player, target, entropyMarks);
      }
   }

   private static void releaseAllForPlayer(MinecraftServer server, UUID playerId) {
      STRAIN_MARKS.remove(playerId);
      Set<UUID> held = HELD_TARGETS.remove(playerId);
      if (held == null || held.isEmpty()) {
         return;
      }
      boolean keepNoAi = ModConfig.COMMON.infinityStellaris.curvatureRupture.legacyKeepNoAiOnRelease.getValue();
      String token = tokenOf(playerId);
      for (UUID targetId : held) {
         releaseSingleTarget(server, targetId, token, keepNoAi);
      }
   }

   private static void releaseSingleTarget(MinecraftServer server, UUID targetId, String token, boolean legacyKeepNoAi) {
      LivingEntity resolved = resolveLiving(server, targetId);
      EntitySuspensionManager.release(targetId, resolved, token);
      // 仅当管理员显式开启历史高危兼容项时保留不恢复 AI 行为；默认 false 100% 安全还原
      if (legacyKeepNoAi && resolved instanceof Mob mob && mob.isAlive() && !EntitySuspensionManager.isManaged(targetId)) {
         mob.setNoAi(true);
      }
   }

   private static LivingEntity resolveLiving(MinecraftServer server, UUID uuid) {
      if (server == null) {
         return null;
      }
      for (ServerLevel level : server.getAllLevels()) {
         LivingEntity living = SpecialEffectSupport.findLivingEntity(level, uuid);
         if (living != null) {
            return living;
         }
      }
      return null;
   }

   private static String tokenOf(UUID playerId) {
      return "curvature_rupture:" + playerId;
   }

   private static int addStrainMark(UUID playerId, UUID targetId) {
      Map<UUID, Integer> playerMarks = STRAIN_MARKS.computeIfAbsent(playerId, ignored -> new HashMap<>());
      int marks = playerMarks.getOrDefault(targetId, 0) + 1;
      playerMarks.put(targetId, marks);
      return marks;
   }

   private static void clearStrainMark(UUID playerId, UUID targetId) {
      Map<UUID, Integer> playerMarks = STRAIN_MARKS.get(playerId);
      if (playerMarks != null) {
         playerMarks.remove(targetId);
         if (playerMarks.isEmpty()) {
            STRAIN_MARKS.remove(playerId);
         }
      }
   }

   private static void clearMissingStrainMarks(UUID playerId, Set<UUID> activeTargets) {
      Map<UUID, Integer> playerMarks = STRAIN_MARKS.get(playerId);
      if (playerMarks != null) {
         playerMarks.keySet().removeIf(id -> !activeTargets.contains(id));
         if (playerMarks.isEmpty()) {
            STRAIN_MARKS.remove(playerId);
         }
      }
   }

   private static void pruneTargetFromTracking(UUID targetId) {
      if (targetId == null) {
         return;
      }
      if (!STRAIN_MARKS.isEmpty()) {
         Iterator<Map.Entry<UUID, Map<UUID, Integer>>> it = STRAIN_MARKS.entrySet().iterator();
         while (it.hasNext()) {
            Map<UUID, Integer> marks = it.next().getValue();
            marks.remove(targetId);
            if (marks.isEmpty()) {
               it.remove();
            }
         }
      }
      if (!HELD_TARGETS.isEmpty()) {
         Iterator<Map.Entry<UUID, Set<UUID>>> it = HELD_TARGETS.entrySet().iterator();
         while (it.hasNext()) {
            Set<UUID> held = it.next().getValue();
            held.remove(targetId);
            if (held.isEmpty()) {
               it.remove();
            }
         }
      }
   }
}
