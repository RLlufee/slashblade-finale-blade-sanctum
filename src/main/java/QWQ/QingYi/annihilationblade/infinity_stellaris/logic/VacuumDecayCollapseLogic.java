package QWQ.QingYi.annihilationblade.infinity_stellaris.logic;

import QWQ.QingYi.annihilationblade.common.EntitySuspensionManager;
import QWQ.QingYi.annihilationblade.common.ServerTickScheduler;
import QWQ.QingYi.annihilationblade.common.SlashBladeStateSupport;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.infinity_stellaris.InfinityStellarisDefinitions;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import QWQ.QingYi.annihilationblade.registry.ModSlashArts;
import QWQ.QingYi.annihilationblade.render.FxSustained;
import QWQ.QingYi.annihilationblade.render.FxTypes;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import QWQ.QingYi.annihilationblade.stellaris.pattern.SevenStarPattern;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * SA · 真空衰变·坍缩（Vacuum Decay Collapse）服务端权威波次核心逻辑。
 * <p>
 * <ul>
 *   <li>支持 SA 独立移植至任意未破损/未封印拔刀剑；</li>
 *   <li>全参数接入 {@code ModConfig.COMMON.infinityStellaris.vacuumDecay}（默认 184 tick = 9.2 s、射程 160 格与视距对齐、{@code clear_drops = false}）；</li>
 *   <li>与客户端共用 {@link SevenStarPattern} 种子与时间轴：28t 引力抬升 → 44~96t 精确 14 波七星点名（暂缓提前热寂保留终幕高潮）→ 96~116t 天穹黑洞脱轨坠落（100t 临界捏压向心牵引，116t 坠地重压）→ 116~129t 接地过载暴涨与极速重力内爆坍缩 → 129~134t 呼吸拍静默 → 134t 全场同步超新星热寂清算；</li>
 *   <li>玩家死亡、离界或跨维度立即终止结界、释放悬停持锁并停止持续特效；过渡期通过 {@code intParam} 打包终幕拍点与 {@code visualScale} 对齐 {@link FxTypes#VACUUM_DECAY}，Step 7 切至 {@link FxTypes#STELLARIS_VACUUM_COLLAPSE}。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public final class VacuumDecayCollapseLogic {
   private static final String NO_DROPS_TAG = "AnnihilationBladeAbsoluteAnnihilationNoDrops";
   private static final double ZONE_HEIGHT = 64.0;
   private static final int LEGACY_BOLTS_PER_TICK = 10;

   private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
   private static final Map<UUID, AnnihilationZone> ACTIVE_ZONES = new HashMap<>();
   private static final Set<UUID> INTERNAL_DAMAGE = new HashSet<>();

   private VacuumDecayCollapseLogic() {
   }

   public static boolean isInternalDamage(LivingEntity target) {
      return target != null && INTERNAL_DAMAGE.contains(target.getUUID());
   }

   public static void clearPlayer(UUID playerId) {
      if (playerId != null) {
         COOLDOWNS.remove(playerId);
         AnnihilationZone active = ACTIVE_ZONES.remove(playerId);
         if (active != null) {
            abortZone(active);
         }
      }
   }

   public static void clearAll() {
      for (AnnihilationZone zone : ACTIVE_ZONES.values()) {
         releasePinchedTargets(zone);
      }
      COOLDOWNS.clear();
      ACTIVE_ZONES.clear();
      INTERNAL_DAMAGE.clear();
   }

   public static void prepareCast(Player player) {
      ItemStack blade = InfinityStellarisItemSupport.heldInfinityStellaris(player);
      if (!blade.isEmpty()) {
         InfinityStellarisDefinitions.ensureStats(blade, player.level());
      }
   }

   public static void unleash(Player player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }

      ModConfig.VacuumDecayConfig config = ModConfig.COMMON.infinityStellaris.vacuumDecay;
      if (!config.enabled.getValue()) {
         return;
      }

      ItemStack saBlade = InfinityStellarisItemSupport.findHeldActiveSlashArtBlade(player, ModSlashArts.VACUUM_DECAY_COLLAPSE.getId());
      if (saBlade.isEmpty()) {
         return;
      }

      UUID playerId = player.getUUID();
      if (ACTIVE_ZONES.containsKey(playerId)) {
         return;
      }
      long gameTime = level.getGameTime();
      int cooldownTicks = Math.max(0, config.cooldownTicks.getValue());
      if (!SpecialEffectSupport.tryStartCooldown(COOLDOWNS, player, gameTime, cooldownTicks)) {
         long last = COOLDOWNS.getOrDefault(playerId, gameTime);
         long remainTicks = Math.max(1L, cooldownTicks - (gameTime - last));
         double remainSec = Math.ceil(remainTicks / 2.0) / 10.0;
         player.displayClientMessage(
            Component.translatable("message.annihilationblade.vacuum_decay_cooldown", String.format(java.util.Locale.ROOT, "%.1f", remainSec)),
            true
         );
         return;
      }

      double castRange = Math.max(16.0, config.castRange.getValue());
      double zoneRadius = Math.max(16.0, config.zoneRadius.getValue());
      int durationTicks = Math.max(SevenStarPattern.TICK_FINAL_RECKONING + 6, config.durationTicks.getValue());
      boolean clearDrops = config.clearDrops.getValue();
      float visualScale = (float) Mth.clamp(config.visualScale.getValue(), 0.25, 2.0);
      float basePanel = SlashBladeStateSupport.state(saBlade)
         .map(ISlashBladeState::getBaseAttackModifier)
         .filter(val -> val > 0.0F)
         .orElse(InfinityStellarisDefinitions.BASE_ATTACK_MODIFIER);

      long seed = player.getRandom().nextLong();
      Vec3 center = findCastCenter(level, player, castRange);
      playZoneOpening(level, center);
      int fxHandle = startZoneFx(level, player, center, zoneRadius, castRange, durationTicks, visualScale, seed);

      AnnihilationZone zone = new AnnihilationZone(
         level, player, center, zoneRadius, durationTicks, clearDrops, basePanel, fxHandle, seed
      );
      ACTIVE_ZONES.put(playerId, zone);
      tickZone(zone);
   }

   @SubscribeEvent
   public static void onLivingDrops(LivingDropsEvent event) {
      if (event.getEntity().getPersistentData().getBoolean(NO_DROPS_TAG)) {
         event.getDrops().clear();
      }
   }

   @SubscribeEvent
   public static void onLivingExperienceDrop(LivingExperienceDropEvent event) {
      if (event.getEntity().getPersistentData().getBoolean(NO_DROPS_TAG)) {
         event.setDroppedExperience(0);
      }
   }

   private static Vec3 findCastCenter(ServerLevel level, Player player, double castRange) {
      Vec3 start = player.getEyePosition();
      Vec3 look = player.getLookAngle();
      Vec3 end = start.add(look.scale(castRange));

      BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
      if (hit.getType() == HitResult.Type.BLOCK) {
         BlockPos pos = hit.getBlockPos();
         return Vec3.atCenterOf(pos);
      }

      // 朝向天空或超远无方块时，先向下贴合真实战场地面，避免结界中心悬在半空导致地表遥测与伤害落空
      Vec3 forward = start.add(look.scale(Math.min(64.0, castRange)));
      double probeBottom = Math.max(level.getMinBuildHeight() + 1.0, forward.y - 96.0);
      BlockHitResult ground = level.clip(new ClipContext(
         forward,
         new Vec3(forward.x, probeBottom, forward.z),
         ClipContext.Block.COLLIDER,
         ClipContext.Fluid.NONE,
         player
      ));
      if (ground.getType() == HitResult.Type.BLOCK) {
         return ground.getLocation().add(0.0, 0.5, 0.0);
      }
      double clampedY = Mth.clamp(forward.y, player.getY() - 8.0, player.getY() + 12.0);
      return new Vec3(forward.x, clampedY, forward.z);
   }

   private static void tickZone(AnnihilationZone zone) {
      UUID playerId = zone.player.getUUID();
      if (ACTIVE_ZONES.get(playerId) != zone) {
         return;
      }
      if (!zone.player.isAlive() || zone.player.isRemoved() || zone.player.level() != zone.level) {
         ACTIVE_ZONES.remove(playerId, zone);
         abortZone(zone);
         return;
      }

      if (zone.age == SevenStarPattern.TICK_GRAVITY_LIFT) {
         executeGravityLift(zone);
      }

      int waveIndex = SevenStarPattern.volleyWaveIndex(zone.age);
      if (waveIndex >= 0) {
         executeStarVolleyWave(zone, waveIndex);
      }

      if (zone.age == SevenStarPattern.TICK_CRITICAL_PINCH) {
         executeCriticalPinch(zone);
      }

      if (zone.age == SevenStarPattern.TICK_PLUNGE_IMPACT) {
         executePlungeImpact(zone);
      }

      // 100..133t 临界悬停与向心坍缩期：坠落与内爆阶段持续向爆心收敛，129~133t 死寂拍完全定格
      if (zone.age > SevenStarPattern.TICK_CRITICAL_PINCH && zone.age < SevenStarPattern.TICK_FINAL_RECKONING) {
         maintainCriticalSuspension(zone);
      }

      if (zone.age == SevenStarPattern.TICK_FINAL_RECKONING) {
         releasePinchedTargets(zone);
         executeFinalReckoning(zone);
      }

      playZonePulse(zone.level, zone.center, zone.age);
      zone.age++;

      if (zone.age < zone.durationTicks) {
         ServerTickScheduler.schedule(1, () -> tickZone(zone));
      } else {
         releasePinchedTargets(zone);
         ACTIVE_ZONES.remove(playerId, zone);
      }
   }

   /** 第 28 tick：潮汐引力抬升，令区域内敌群失重离地。 */
   private static void executeGravityLift(AnnihilationZone zone) {
      for (LivingEntity target : findTargets(zone)) {
         Vec3 velocity = target.getDeltaMovement();
         target.setDeltaMovement(velocity.x * 0.25, Math.max(0.42, velocity.y + 0.42), velocity.z * 0.25);
         target.hasImpulse = true;
         target.hurtMarked = true;
      }
   }

   /**
    * 第 44~96 tick（每 4 tick 一波，精确 14 波）：基于 {@link SevenStarPattern} 共享种子的七星定点打击。
    * <p>
    * 仅打击当发星矢落点附近与轮巡点名目标，并暂缓提前热寂处决（压低至临界 1 HP 且熵增最多叠至满层前 1 层），
    * 把全场集体热寂晶化爆碎完整保留到 117~122t 呼吸拍后的第 122 tick 终幕瞬间。
    */
   private static void executeStarVolleyWave(AnnihilationZone zone, int waveIndex) {
      List<LivingEntity> allTargets = findTargets(zone);
      if (allTargets.isEmpty()) {
         return;
      }

      Vec3 spearPos = SevenStarPattern.waveImpactPos(zone.seed, waveIndex, zone.center, zone.zoneRadius);
      double impactRadius = Math.max(10.0, zone.zoneRadius * 0.25);
      double impactRadiusSqr = impactRadius * impactRadius;

      Set<LivingEntity> struck = new HashSet<>();
      for (LivingEntity candidate : allTargets) {
         double dx = candidate.getX() - spearPos.x;
         double dz = candidate.getZ() - spearPos.z;
         if (dx * dx + dz * dz <= impactRadiusSqr) {
            struck.add(candidate);
         }
      }
      // 轮巡额外点名 1 名区域内目标，确保星矢落点偏移时仍有明确打击反馈
      struck.add(allTargets.get(waveIndex % allTargets.size()));

      DamageSource source = zone.level.damageSources().indirectMagic(zone.player, zone.player);
      for (LivingEntity target : struck) {
         if (!target.isAlive()) {
            continue;
         }
         UUID id = target.getUUID();
         float rawDamage = zone.basePanel * 0.85F + target.getMaxHealth() * 0.06F;
         // 保留至少 1.0F 临界血量，防止 A4 首波清光导致 A5 死寂与 A6 终幕爆裂演给空气看
         float clampedDamage = Math.min(rawDamage, Math.max(0.0F, target.getHealth() - 1.0F));
         if (clampedDamage > 0.05F) {
            target.invulnerableTime = 0;
            INTERNAL_DAMAGE.add(id);
            try {
               target.hurt(source, clampedDamage);
            } finally {
               INTERNAL_DAMAGE.remove(id);
            }
         } else {
            // 已处于临界残血：轻抬起并同步受击反馈，维持高潮张力
            target.setDeltaMovement(target.getDeltaMovement().x * 0.4, Math.max(0.18, target.getDeltaMovement().y), target.getDeltaMovement().z * 0.4);
            target.hurtMarked = true;
         }

         if (target.isAlive()) {
            EntropyDissolutionLogic.applyMarksDeferred(zone.level, zone.player, target, 1);
         }
      }
   }

   /** 第 100 tick：黑洞坠落中途发动视界临界捏压，剥离增益与吸收盾、清无敌帧并向坠落中心牵引悬停。 */
   private static void executeCriticalPinch(AnnihilationZone zone) {
      zone.level.playSound(null, zone.center.x, zone.center.y, zone.center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.2F, 0.52F);
      String token = pinchTokenOf(zone.player.getUUID());
      for (LivingEntity target : findTargets(zone)) {
         target.removeAllEffects();
         target.setAbsorptionAmount(0.0F);
         target.invulnerableTime = 0;
         EntitySuspensionManager.acquire(target, token, true, true);
         zone.pinchedTargets.add(target.getUUID());
         SpecialEffectSupport.pullToward(target, zone.center.add(0.0, 2.5, 0.0), 0.78);
      }
   }

   /** 第 116 tick：天穹黑洞轰然坠地，同步播放坠地震撼音效并将结界敌群强力压向地面爆心。 */
   private static void executePlungeImpact(AnnihilationZone zone) {
      zone.level.playSound(null, zone.center.x, zone.center.y, zone.center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3.2F, 0.42F);
      zone.level.playSound(null, zone.center.x, zone.center.y, zone.center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.8F, 0.48F);
      String token = pinchTokenOf(zone.player.getUUID());
      Vec3 crushCore = zone.center.add(0.0, 2.0, 0.0);
      for (LivingEntity target : findTargets(zone)) {
         if (zone.pinchedTargets.add(target.getUUID())) {
            target.removeAllEffects();
            target.setAbsorptionAmount(0.0F);
            EntitySuspensionManager.acquire(target, token, true, true);
         }
         target.invulnerableTime = 0;
         SpecialEffectSupport.pullToward(target, crushCore, 1.05);
      }
   }

   private static void maintainCriticalSuspension(AnnihilationZone zone) {
      if (zone.pinchedTargets.isEmpty()) {
         return;
      }
      // 129~133t 呼吸拍内完全定格，强化绝对死寂；116~128t 过载内爆期持续向地面爆心强力吸聚
      boolean silenceFreeze = zone.age >= SevenStarPattern.TICK_SILENCE_START;
      boolean imploding = zone.age >= SevenStarPattern.TICK_PLUNGE_IMPACT && !silenceFreeze;
      Vec3 crushCore = zone.center.add(0.0, 2.2, 0.0);
      for (UUID targetId : zone.pinchedTargets) {
         LivingEntity target = SpecialEffectSupport.findLivingEntity(zone.level, targetId);
         if (target != null && target.isAlive()) {
            if (silenceFreeze) {
               target.setDeltaMovement(Vec3.ZERO);
            } else if (imploding) {
               Vec3 delta = crushCore.subtract(target.position());
               double dist = delta.length();
               if (dist > 1.2) {
                  double pull = zone.age >= SevenStarPattern.TICK_IMPLOSION_START ? 0.42 : 0.22;
                  target.setDeltaMovement(delta.scale(Math.min(1.1, pull / Math.max(dist, 0.5)) * Math.min(dist, 4.5)));
               } else {
                  target.setDeltaMovement(target.getDeltaMovement().scale(0.35));
               }
            } else {
               Vec3 v = target.getDeltaMovement();
               target.setDeltaMovement(new Vec3(v.x * 0.72, v.y * 0.72 + 0.015, v.z * 0.72));
            }
            target.fallDistance = 0.0F;
            target.hasImpulse = true;
            target.hurtMarked = true;
         }
      }
   }

   /** 第 134 tick：超新星坍缩终幕最终清算；仅当 clear_drops = true 时清空掉落物与经验。 */
   private static void executeFinalReckoning(AnnihilationZone zone) {
      zone.level.playSound(null, zone.center.x, zone.center.y, zone.center.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 3.6F, 0.42F);
      zone.level.playSound(null, zone.center.x, zone.center.y, zone.center.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 3.2F, 0.38F);
      for (LivingEntity target : findTargets(zone)) {
         if (zone.clearDrops) {
            target.getPersistentData().putBoolean(NO_DROPS_TAG, true);
         }
         EntropyDissolutionLogic.executeFinal(target, zone.player, true);
      }
   }

   private static void abortZone(AnnihilationZone zone) {
      releasePinchedTargets(zone);
      FxSustained.stop(zone.fxHandle);
   }

   private static void releasePinchedTargets(AnnihilationZone zone) {
      if (zone.pinchedTargets.isEmpty()) {
         return;
      }
      String token = pinchTokenOf(zone.player.getUUID());
      MinecraftServer server = zone.level.getServer();
      for (UUID targetId : zone.pinchedTargets) {
         LivingEntity resolved = SpecialEffectSupport.findLivingEntity(zone.level, targetId);
         if (resolved == null && server != null) {
            for (ServerLevel other : server.getAllLevels()) {
               resolved = SpecialEffectSupport.findLivingEntity(other, targetId);
               if (resolved != null) {
                  break;
               }
            }
         }
         EntitySuspensionManager.release(targetId, resolved, token);
      }
      zone.pinchedTargets.clear();
   }

   private static String pinchTokenOf(UUID playerId) {
      return "vacuum_decay_pinch:" + playerId;
   }

   private static List<LivingEntity> findTargets(AnnihilationZone zone) {
      double radiusSqr = zone.zoneRadius * zone.zoneRadius;
      AABB killBox = AABB.ofSize(zone.center, zone.zoneRadius * 2.0, ZONE_HEIGHT, zone.zoneRadius * 2.0);
      return zone.level.getEntitiesOfClass(LivingEntity.class, killBox, entity -> {
         if (!SlashBladeTargeting.canAttack(zone.player, entity)) {
            return false;
         }
         double dx = entity.getX() - zone.center.x;
         double dz = entity.getZ() - zone.center.z;
         return dx * dx + dz * dz <= radiusSqr;
      });
   }

   private static void playZoneOpening(ServerLevel level, Vec3 center) {
      level.playSound(null, center.x, center.y, center.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 3.5F, 0.35F);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 3.0F, 0.35F);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.8F, 0.65F);
   }

   private static void playZonePulse(ServerLevel level, Vec3 center, int age) {
      // 黑洞坠落启动前保持低频脉冲，96t 坠落起交由坠落/砸地/内爆/死寂专属音效接管
      if (age > 0 && age % 20 == 0 && age < SevenStarPattern.TICK_PLUNGE_START) {
         level.playSound(null, center.x, center.y, center.z, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1.8F, 0.55F + (age % 40) * 0.01F);
      }
   }

   @SuppressWarnings("deprecation")
   private static int startZoneFx(
      ServerLevel level,
      Player player,
      Vec3 center,
      double zoneRadius,
      double castRange,
      int durationTicks,
      float visualScale,
      long seed
   ) {
      // 确保 FxSustained.viewRange() >= castRange，防止调大 cast_range 且调小 zone_radius 时远程隐身
      float heightParam = (float) Math.max(ZONE_HEIGHT, Math.max(0.0, castRange - 160.0));
      int packed = SevenStarPattern.packIntParam(LEGACY_BOLTS_PER_TICK, visualScale, SevenStarPattern.TICK_FINAL_RECKONING);
      return FxSustained.start(level, new FxEventPacket(
         FxTypes.VACUUM_DECAY,
         center,
         player.getLookAngle(),
         (float) zoneRadius,
         heightParam,
         packed,
         seed,
         player.getId(),
         new int[0]), durationTicks);
   }

   private static final class AnnihilationZone {
      private final ServerLevel level;
      private final Player player;
      private final Vec3 center;
      private final double zoneRadius;
      private final int durationTicks;
      private final boolean clearDrops;
      private final float basePanel;
      private final int fxHandle;
      private final long seed;
      private final Set<UUID> pinchedTargets = new HashSet<>();
      private int age;

      private AnnihilationZone(
         ServerLevel level,
         Player player,
         Vec3 center,
         double zoneRadius,
         int durationTicks,
         boolean clearDrops,
         float basePanel,
         int fxHandle,
         long seed
      ) {
         this.level = level;
         this.player = player;
         this.center = center;
         this.zoneRadius = zoneRadius;
         this.durationTicks = durationTicks;
         this.clearDrops = clearDrops;
         this.basePanel = basePanel;
         this.fxHandle = fxHandle;
         this.seed = seed;
      }
   }
}
