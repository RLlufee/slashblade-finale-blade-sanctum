package QWQ.QingYi.annihilationblade.infinity_stellaris.logic;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.common.EntitySuspensionManager;
import QWQ.QingYi.annihilationblade.common.ServerTickScheduler;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.infinity_stellaris.InfinityStellarisDefinitions;
import QWQ.QingYi.annihilationblade.infinity_stellaris.visual.InfinityStellarisVisuals;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.event.SlashBladeEvent.DoSlashEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * SE 2 · 宇宙弦切（Cosmic String Cut）服务端权威逻辑。
 * <p>
 * <ul>
 *   <li>收敛为中远程高张力二拍拨弦（常态 32 格 / Shift 64 格，最多锁定 10 个目标），告别 128 格一刀清图架空其他技能；</li>
 *   <li>第一拍通过 {@link EntitySuspensionManager} 按独立 {@code castToken} 束缚目标 9 tick，第二拍释放束缚并结算高额弦切伤害 + 叠加 1 层熵增印记；</li>
 *   <li>彻底拆除旧版 1875 发包粒子晶格与 36 雷电实体联动，支持独立拆装至任意未破损/未封印拔刀剑。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public final class CosmicStringCutLogic {
   private static final AtomicLong CAST_SEQUENCE = new AtomicLong(1L);
   private static final Map<UUID, Long> LAST_TRIGGER_TICK = new HashMap<>();
   private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();
   private static final Map<UUID, Set<PendingCast>> ACTIVE_CASTS = new HashMap<>();
   private static final Set<UUID> INTERNAL_DAMAGE = new HashSet<>();

   private CosmicStringCutLogic() {
   }

   public static boolean isInternalDamage(LivingEntity target) {
      return target != null && INTERNAL_DAMAGE.contains(target.getUUID());
   }

   public static void clearPlayer(Player player) {
      if (player == null) {
         return;
      }
      UUID playerId = player.getUUID();
      LAST_TRIGGER_TICK.remove(playerId);
      COOLDOWNS.remove(playerId);
      releaseActiveCasts(player.getServer(), playerId);
   }

   public static void clearAll() {
      LAST_TRIGGER_TICK.clear();
      COOLDOWNS.clear();
      ACTIVE_CASTS.clear();
      INTERNAL_DAMAGE.clear();
   }

   @SubscribeEvent
   public static void onDoingSlash(DoSlashEvent event) {
      if (!(event.getUser() instanceof ServerPlayer player) || player.level().isClientSide) {
         return;
      }

      ModConfig.CosmicStringCutConfig config = ModConfig.COMMON.infinityStellaris.cosmicStringCut;
      if (!config.enabled.getValue()) {
         return;
      }

      ISlashBladeState state = event.getSlashBladeState();
      if (state == null || state.isBroken() || state.isSealed()
            || !state.hasSpecialEffect(ModSpecialEffects.COSMIC_STRING_CUT.getId())) {
         return;
      }

      ServerLevel level = player.serverLevel();
      long gameTime = level.getGameTime();
      UUID playerId = player.getUUID();
      if (LAST_TRIGGER_TICK.getOrDefault(playerId, Long.MIN_VALUE) == gameTime) {
         return;
      }

      double range = player.isShiftKeyDown() ? config.shiftRange.getValue() : config.range.getValue();
      int maxTargets = Math.max(1, config.maxTargets.getValue());
      List<LivingEntity> targets = selectTargets(level, player, range, maxTargets);
      if (targets.isEmpty()) {
         return;
      }

      if (!SpecialEffectSupport.tryStartCooldown(COOLDOWNS, player, gameTime, Math.max(0, config.cooldownTicks.getValue()))) {
         return;
      }
      LAST_TRIGGER_TICK.put(playerId, gameTime);

      int bindTicks = Math.max(2, config.bindTicks.getValue());
      int entropyMarks = Math.max(0, config.entropyMarksApplied.getValue());
      float basePanel = Math.max(10.0F, state.getBaseAttackModifier() > 0.0F ? state.getBaseAttackModifier() : InfinityStellarisDefinitions.BASE_ATTACK_MODIFIER);
      String castToken = "cosmic_string_cut:" + playerId + ":" + CAST_SEQUENCE.getAndIncrement();

      Vec3 origin = SpecialEffectSupport.centerOf(player);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.4F, 1.35F);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.75F);

      List<UUID> targetIds = new ArrayList<>(targets.size());
      for (LivingEntity target : targets) {
         EntitySuspensionManager.acquire(target, castToken, true, true);
         target.setDeltaMovement(Vec3.ZERO);
         target.hasImpulse = true;
         targetIds.add(target.getUUID());
      }
      // 首拍立刻输出克制弦线锚点占位，消除 9 tick 束缚期的“空气定身”错觉
      InfinityStellarisVisuals.spawnStringBindPlaceholder(level, origin, targets);

      PendingCast cast = new PendingCast(castToken, targetIds);
      ACTIVE_CASTS.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(cast);

      ServerTickScheduler.schedule(bindTicks, () -> detonateCast(level, player, cast, basePanel, entropyMarks));
   }

   private static void detonateCast(ServerLevel level, ServerPlayer player, PendingCast cast, float basePanel, int entropyMarks) {
      UUID playerId = player.getUUID();
      Set<PendingCast> playerCasts = ACTIVE_CASTS.get(playerId);
      if (playerCasts == null || !playerCasts.remove(cast)) {
         // 已被离线/跨维/停服提前释放
         return;
      }
      if (playerCasts.isEmpty()) {
         ACTIVE_CASTS.remove(playerId);
      }

      boolean canResolveDamage = player.isAlive() && !player.isRemoved() && player.level() == level;
      // 使用 indirectMagic 而非 playerAttack，避免 64 格外弦切误触发裂界/其他近战直接攻击 SE
      DamageSource damageSource = canResolveDamage ? level.damageSources().indirectMagic(player, player) : null;
      boolean playedImpactSound = false;

      for (UUID targetId : cast.targetIds) {
         LivingEntity target = SpecialEffectSupport.findLivingEntity(level, targetId);
         EntitySuspensionManager.release(targetId, target, cast.token);

         if (!canResolveDamage || target == null || !target.isAlive() || !SlashBladeTargeting.canAttack(player, target)) {
            continue;
         }

         if (!playedImpactSound) {
            Vec3 soundPos = SpecialEffectSupport.centerOf(target);
            level.playSound(null, soundPos.x, soundPos.y, soundPos.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.5F, 1.6F);
            level.playSound(null, soundPos.x, soundPos.y, soundPos.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.4F, 1.35F);
            playedImpactSound = true;
         }

         // 先发视觉再结算伤害，为 Step 4 3D 活体网格切裂抓拍留出完整时序窗口
         InfinityStellarisVisuals.spawnStringCutPlaceholder(level, target);

         float damage = basePanel * 1.5F + target.getMaxHealth() * 0.15F;
         target.invulnerableTime = 0;
         INTERNAL_DAMAGE.add(targetId);
         try {
            target.hurt(damageSource, damage);
         } finally {
            INTERNAL_DAMAGE.remove(targetId);
         }

         if (!target.isAlive() && !target.isRemoved() && !TerminusLogic.preservesDeathSequence(target)) {
            // 跳过非 Boss 实体 20 tick 红尸侧翻动画，保持弦切崩解画面干净利落
            target.discard();
         } else if (target.isAlive() && entropyMarks > 0) {
            EntropyDissolutionLogic.applyMarks(level, player, target, entropyMarks);
         }
      }
   }

   private static List<LivingEntity> selectTargets(ServerLevel level, ServerPlayer player, double range, int maxTargets) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle().normalize();
      List<LivingEntity> candidates = SpecialEffectSupport.radialTargets(level, player, SpecialEffectSupport.centerOf(player), range);
      if (candidates.size() <= maxTargets) {
         return candidates;
      }

      // 优先选择位于准星前方且距离更近的目标，兼顾中远程指哪打哪的手感
      candidates.sort(Comparator.comparingDouble(entity -> {
         Vec3 toTarget = SpecialEffectSupport.centerOf(entity).subtract(eye);
         double dist = Math.max(0.5, toTarget.length());
         double align = toTarget.scale(1.0 / dist).dot(look);
         return dist * (1.35 - 0.55 * Math.max(-1.0, align));
      }));
      return SpecialEffectSupport.limit(candidates, maxTargets);
   }

   private static void releaseActiveCasts(MinecraftServer server, UUID playerId) {
      Set<PendingCast> casts = ACTIVE_CASTS.remove(playerId);
      if (casts == null || casts.isEmpty()) {
         return;
      }
      for (PendingCast cast : casts) {
         for (UUID targetId : cast.targetIds) {
            LivingEntity resolved = resolveLiving(server, targetId);
            EntitySuspensionManager.release(targetId, resolved, cast.token);
         }
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

   private record PendingCast(String token, List<UUID> targetIds) {
   }
}
