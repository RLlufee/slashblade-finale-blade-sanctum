package QWQ.QingYi.annihilationblade.infinity_stellaris.logic;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.common.ServerTickScheduler;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.infinity_stellaris.InfinityStellarisDefinitions;
import QWQ.QingYi.annihilationblade.infinity_stellaris.entity.GammaThunderboltEntity;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import QWQ.QingYi.annihilationblade.infinity_stellaris.visual.InfinityStellarisVisuals;
import QWQ.QingYi.annihilationblade.registry.ModEntities;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * SE 3 · 伽马霆爆（Gamma Thunderburst）—— 天基轨道炮服务端权威逻辑。
 * <p>
 * <ul>
 *   <li>支持 SE 独立拆装（读取 {@code ISlashBladeState.hasSpecialEffect} 且校验未破损/未封印）与独立冷却；</li>
 *   <li>彻底停止为无尽星空刷出 36 个服务端雷电实体，改为「12 tick 预警 → 18 tick 贯地光柱（半径 5.5 格，破无敌帧 + 叠 2 层熵增）」权威结算；</li>
 *   <li>保留 {@link #spawnBolt(ServerLevel, Vec3)} 与 {@link #spawnBolt(ServerLevel, Vec3, int)} 兼容外壳，确保魔龙夜陨灭界龙威零回归。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public final class GammaThunderburstLogic {
   private static final int BEAM_DAMAGE_INTERVAL = 3;
   private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
   private static final Map<UUID, Long> LAST_TRIGGER_TICK = new HashMap<>();
   private static final Set<UUID> INTERNAL_DAMAGE = new HashSet<>();

   private GammaThunderburstLogic() {
   }

   public static boolean isInternalDamage(LivingEntity target) {
      return target != null && INTERNAL_DAMAGE.contains(target.getUUID());
   }

   public static void clearPlayer(UUID playerId) {
      if (playerId != null) {
         COOLDOWNS.remove(playerId);
         LAST_TRIGGER_TICK.remove(playerId);
      }
   }

   public static void clearAll() {
      COOLDOWNS.clear();
      LAST_TRIGGER_TICK.clear();
      INTERNAL_DAMAGE.clear();
   }

   @SubscribeEvent(priority = EventPriority.LOW)
   public static void onHurt(LivingHurtEvent event) {
      LivingEntity target = event.getEntity();
      if (event.isCanceled()
            || target.level().isClientSide
            || EntropyDissolutionLogic.isInternalExecution(target)
            || TerminusLogic.isExecuting()
            || isInternalDamage(target)
            || CosmicStringCutLogic.isInternalDamage(target)
            || CurvatureRuptureLogic.isInternalDamage(target)
            || VacuumDecayCollapseLogic.isInternalDamage(target)) {
         return;
      }

      ModConfig.GammaThunderburstConfig config = ModConfig.COMMON.infinityStellaris.gammaThunderburst;
      if (!config.enabled.getValue()) {
         return;
      }

      Entity source = event.getSource().getEntity();
      Entity directSource = event.getSource().getDirectEntity();
      if (!(source instanceof ServerPlayer player) || directSource instanceof GammaThunderboltEntity) {
         return;
      }
      if (!SlashBladeTargeting.canAttack(player, target)) {
         return;
      }

      Optional<ISlashBladeState> stateOpt = InfinityStellarisItemSupport.resolveAttackSpecialEffect(
         player, event.getSource(), directSource, ModSpecialEffects.GAMMA_THUNDERBURST.getId()
      );
      if (stateOpt.isEmpty()) {
         return;
      }

      ServerLevel level = player.serverLevel();
      long gameTime = level.getGameTime();
      UUID playerId = player.getUUID();
      if (LAST_TRIGGER_TICK.getOrDefault(playerId, Long.MIN_VALUE) == gameTime) {
         return;
      }
      if (!SpecialEffectSupport.tryStartCooldown(COOLDOWNS, player, gameTime, Math.max(0, config.cooldownTicks.getValue()))) {
         return;
      }
      LAST_TRIGGER_TICK.put(playerId, gameTime);

      float rawPanel = stateOpt.get().getBaseAttackModifier();
      float basePanel = Math.max(10.0F, rawPanel > 0.0F ? rawPanel : InfinityStellarisDefinitions.BASE_ATTACK_MODIFIER);
      scheduleOrbitalStrike(level, player, target.position(), basePanel, config);
   }

   private static void scheduleOrbitalStrike(
      ServerLevel level, ServerPlayer player, Vec3 targetPos, float basePanel, ModConfig.GammaThunderburstConfig config
   ) {
      int warningTicks = Math.max(4, config.warningTicks.getValue());
      int beamTicks = Math.max(6, config.beamTicks.getValue());
      double radius = Math.max(2.0, config.radius.getValue());
      int entropyMarks = Math.max(0, config.entropyMarksApplied.getValue());

      // 地表贴合 + 洞穴/下界净空探测：确保空中飞怪也把光柱贯地落于战场，并在室内自适应阵列高度
      StrikeAnchor anchor = resolveStrikeAnchor(level, targetPos);
      Vec3 groundPos = anchor.groundPos();
      double ceilingY = anchor.ceilingY();

      // 预警期（tick 0 与中点）：双拍收缩定位提示
      level.playSound(null, groundPos.x, groundPos.y, groundPos.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.5F, 1.65F);
      InfinityStellarisVisuals.spawnGammaWarningPlaceholder(level, groundPos, ceilingY, radius, false);
      int midWarning = warningTicks / 2;
      if (midWarning >= 2 && midWarning < warningTicks) {
         ServerTickScheduler.schedule(midWarning, () -> {
            if (player.isAlive() && !player.isRemoved() && player.level() == level) {
               InfinityStellarisVisuals.spawnGammaWarningPlaceholder(level, groundPos, ceilingY, radius, true);
            }
         });
      }

      Set<UUID> markedTargets = new HashSet<>();
      for (int offset = 0; offset < beamTicks; offset += BEAM_DAMAGE_INTERVAL) {
         boolean firstPulse = offset == 0;
         int delay = warningTicks + offset;
         ServerTickScheduler.schedule(delay, () -> executeBeamPulse(level, player, groundPos, ceilingY, radius, basePanel, entropyMarks, markedTargets, firstPulse));
      }
   }

   private static StrikeAnchor resolveStrikeAnchor(ServerLevel level, Vec3 targetPos) {
      double minProbeY = Math.max(level.getMinBuildHeight() + 1.0, targetPos.y - 48.0);
      BlockHitResult floorHit = level.clip(new ClipContext(
         targetPos.add(0.0, 1.0, 0.0),
         new Vec3(targetPos.x, minProbeY, targetPos.z),
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null
      ));
      Vec3 groundPos = floorHit.getType() == HitResult.Type.BLOCK
         ? floorHit.getLocation()
         : targetPos;

      double maxProbeY = Math.min(level.getMaxBuildHeight() - 2.0, Math.max(groundPos.y, targetPos.y) + 64.0);
      BlockHitResult ceilingHit = level.clip(new ClipContext(
         groundPos.add(0.0, 1.5, 0.0),
         new Vec3(groundPos.x, maxProbeY, groundPos.z),
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null
      ));
      double ceilingY = ceilingHit.getType() == HitResult.Type.BLOCK
         ? Math.max(targetPos.y + 6.0, ceilingHit.getLocation().y - 1.0)
         : maxProbeY;
      return new StrikeAnchor(groundPos, ceilingY);
   }

   private static void executeBeamPulse(
      ServerLevel level,
      ServerPlayer player,
      Vec3 groundPos,
      double ceilingY,
      double radius,
      float basePanel,
      int entropyMarks,
      Set<UUID> markedTargets,
      boolean firstPulse
   ) {
      if (!player.isAlive() || player.isRemoved() || player.level() != level) {
         return;
      }

      if (firstPulse) {
         // 贯地首帧：带距离衰减的适度低频雷鸣，彻底告别 10000.0F 爆音
         level.playSound(null, groundPos.x, groundPos.y, groundPos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 3.0F, 0.72F);
         level.playSound(null, groundPos.x, groundPos.y, groundPos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.8F, 1.25F);
      }
      InfinityStellarisVisuals.spawnGammaBeamPulsePlaceholder(level, groundPos, ceilingY, radius, firstPulse);

      double topY = Math.max(groundPos.y + 32.0, ceilingY);
      AABB column = new AABB(
         groundPos.x - radius, groundPos.y - 2.0, groundPos.z - radius,
         groundPos.x + radius, topY, groundPos.z + radius
      );
      double radiusSqr = radius * radius;
      List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, column, entity -> {
         if (!SlashBladeTargeting.canAttack(player, entity)) {
            return false;
         }
         double dx = entity.getX() - groundPos.x;
         double dz = entity.getZ() - groundPos.z;
         return dx * dx + dz * dz <= radiusSqr;
      });

      DamageSource source = level.damageSources().indirectMagic(player, player);
      for (LivingEntity target : targets) {
         UUID targetId = target.getUUID();
         float pulseDamage = basePanel * 0.65F + target.getMaxHealth() * 0.05F;
         target.invulnerableTime = 0;
         INTERNAL_DAMAGE.add(targetId);
         try {
            target.hurt(source, pulseDamage);
         } finally {
            INTERNAL_DAMAGE.remove(targetId);
         }

         if (target.isAlive() && entropyMarks > 0 && markedTargets.add(targetId)) {
            EntropyDissolutionLogic.applyMarks(level, player, target, entropyMarks);
         }
      }
   }

   private record StrikeAnchor(Vec3 groundPos, double ceilingY) {
   }

   /**
    * 兼容外壳：保留供魔龙夜陨 {@code NightfallDragonFinalFormLogic} 生成固定色雷电，无尽星空自身不再调用。
    */
   public static void spawnBolt(ServerLevel level, Vec3 position) {
      spawnBolt(level, position, -1);
   }

   /**
    * 兼容外壳：保留供魔龙夜陨 {@code NightfallDragonFinalFormLogic} 生成固定色雷电，无尽星空自身不再调用。
    */
   public static void spawnBolt(ServerLevel level, Vec3 position, int fixedColor) {
      GammaThunderboltEntity bolt = ModEntities.GAMMA_THUNDERBOLT.get().create(level);
      if (bolt != null) {
         bolt.setFixedColor(fixedColor);
         bolt.moveTo(position.x, position.y, position.z);
         level.addFreshEntity(bolt);
      }
   }
}
