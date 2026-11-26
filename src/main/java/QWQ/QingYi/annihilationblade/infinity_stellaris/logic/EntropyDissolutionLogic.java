package QWQ.QingYi.annihilationblade.infinity_stellaris.logic;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.common.ServerTickScheduler;
import QWQ.QingYi.annihilationblade.common.SlashBladeStateSupport;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import QWQ.QingYi.annihilationblade.infinity_stellaris.visual.InfinityStellarisVisuals;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * SE 1 · 熵增蚀解（Entropy Dissolution）与安全热寂处决核心逻辑。
 * <p>
 * <ul>
 *   <li>完整支持 SE 独立拆装（读取 {@code ISlashBladeState.hasSpecialEffect} 且校验未破损/未封印）；</li>
 *   <li>作为无尽星空整套战斗闭环的终结核心，对外暴露 {@link #applyMarks} 供弦切、伽马、曲率与 SA 统一叠熵；</li>
 *   <li>{@link #executeFinal} 纳入与 {@link TerminusLogic} 同级的 Boss 死亡序列保护（豁免 {@code Player}、{@code EnderDragon} 与 {@code forge:bosses}），
 *       补齐 {@code setLastHurtByPlayer}、杀敌计数兜底，并让 {@code entropy_blacklist_ticks} 真实生效；</li>
 *   <li>保留 {@code executeFinal(LivingEntity, Player)} 方法签名，魔龙夜陨调用时自动获益于 Boss 保护且零回归。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public final class EntropyDissolutionLogic {
   private static final float EXECUTION_DAMAGE = 1.0E9F;
   private static final Map<UUID, Map<UUID, Integer>> MARKS = new HashMap<>();
   private static final Map<UUID, Long> BLACKLISTED_UNTIL = new HashMap<>();
   private static final Set<UUID> INTERNAL_EXECUTION = new HashSet<>();

   private EntropyDissolutionLogic() {
   }

   @SubscribeEvent(priority = EventPriority.HIGH)
   public static void onHurt(LivingHurtEvent event) {
      LivingEntity target = event.getEntity();
      if (event.isCanceled()
            || target.level().isClientSide
            || isInternalExecution(target)
            || TerminusLogic.isExecuting()
            || CosmicStringCutLogic.isInternalDamage(target)
            || GammaThunderburstLogic.isInternalDamage(target)
            || CurvatureRuptureLogic.isInternalDamage(target)
            || VacuumDecayCollapseLogic.isInternalDamage(target)) {
         return;
      }

      ModConfig.EntropyDissolutionConfig config = ModConfig.COMMON.infinityStellaris.entropyDissolution;
      if (!config.enabled.getValue()) {
         return;
      }

      long gameTime = target.level().getGameTime();
      purgeBlacklist(gameTime);
      if (isBlacklisted(target, gameTime)) {
         return;
      }

      Entity source = event.getSource().getEntity();
      Entity directSource = event.getSource().getDirectEntity();
      if (!(source instanceof Player player)) {
         return;
      }
      if (InfinityStellarisItemSupport.resolveAttackSpecialEffect(
            player, event.getSource(), directSource, ModSpecialEffects.ENTROPY_DISSOLUTION.getId()).isEmpty()) {
         return;
      }
      if (!SlashBladeTargeting.canAttack(player, target)) {
         return;
      }

      // 修复低血量反治疗边界：仅在目标当前血量高于 1.0F 时才做百分比削减，防止 < 1.0F 被抬回 1.0F
      double percent = config.entropyPercent.getValue();
      float entropyDamage = (float) (target.getMaxHealth() * percent);
      float currentHealth = target.getHealth();
      if (entropyDamage > 0.0F && currentHealth > 1.0F) {
         target.setHealth(Math.max(1.0F, currentHealth - entropyDamage));
      }

      if (target.level() instanceof ServerLevel level) {
         applyMarksInternal(level, player, target, 1, false, true);
         // 若在 HIGH 优先级已触发热寂终结杀死目标（含保留死亡序列的 Boss），必须取消外层受击事件防止原版 actuallyHurt 二次调用 die()
         if (!target.isAlive() || target.isRemoved()) {
            event.setCanceled(true);
         }
      }
   }

   /**
    * 统一叠熵入口：供宇宙弦切(+1)、伽马霆爆(+2)、曲率坍缩(+2)等 SE 联动调用。
    * 严格遵守 SE 独立拆装规范：仅当持刀者当前主/副手挂载有效 {@code entropy_dissolution} SE 时才叠加印记并触发热寂归零。
    *
    * @return 叠加后的当前层数（触发热寂归零或未挂载 SE 时返回 0）
    */
   public static int applyMarks(ServerLevel level, Player player, LivingEntity target, int delta) {
      return applyMarksInternal(level, player, target, delta, true, true);
   }

   /**
    * 延迟终结叠熵入口：供 SA「真空衰变·坍缩」A4 七星齐射阶段调用。
    * 仅累加至满层前 1 层并输出反馈，不提前处决目标，把全场集体热寂晶化爆碎完整留给第 122 tick 终幕。
    */
   public static int applyMarksDeferred(ServerLevel level, Player player, LivingEntity target, int delta) {
      return applyMarksInternal(level, player, target, delta, false, false);
   }

   private static int applyMarksInternal(
      ServerLevel level, Player player, LivingEntity target, int delta, boolean requireSe, boolean allowExecution
   ) {
      if (level == null || player == null || target == null || delta <= 0) {
         return 0;
      }
      ModConfig.EntropyDissolutionConfig config = ModConfig.COMMON.infinityStellaris.entropyDissolution;
      if (!config.enabled.getValue() || !target.isAlive() || !SlashBladeTargeting.canAttack(player, target)) {
         return 0;
      }
      if (requireSe && !InfinityStellarisItemSupport.isHoldingActiveSpecialEffect(player, ModSpecialEffects.ENTROPY_DISSOLUTION.getId())) {
         return 0;
      }
      long gameTime = level.getGameTime();
      purgeBlacklist(gameTime);
      if (isBlacklisted(target, gameTime)) {
         return 0;
      }

      int required = Math.max(1, config.entropyMarks.getValue());
      int marks = addMarks(player, target, delta);
      if (allowExecution && marks >= required) {
         clearMark(player, target);
         executeFinal(target, player, true);
         return 0;
      }
      if (!allowExecution && marks >= required) {
         marks = Math.max(1, required - 1);
         setMarks(player, target, marks);
      }
      InfinityStellarisVisuals.spawnEntropyMarkFeedback(level, target, marks, required);
      return marks;
   }

   public static int getMarks(Player player, LivingEntity target) {
      if (player == null || target == null) {
         return 0;
      }
      Map<UUID, Integer> playerMarks = MARKS.get(player.getUUID());
      return playerMarks == null ? 0 : playerMarks.getOrDefault(target.getUUID(), 0);
   }

   /** 兼容辅助方法：严格判断是否为无尽星空主手直接攻击或拔刀剑实体攻击。 */
   public static boolean isInfinityDamage(Player player, DamageSource source, Entity directSource) {
      return InfinityStellarisItemSupport.isDirectInfinityAttack(player, source)
         || InfinityStellarisItemSupport.isInfinitySlashEntityAttack(player, directSource);
   }

   public static boolean isInternalExecution(LivingEntity target) {
      return target != null && INTERNAL_EXECUTION.contains(target.getUUID());
   }

   public static boolean isBlacklisted(LivingEntity target) {
      return target != null && isBlacklisted(target, target.level().getGameTime());
   }

   private static boolean isBlacklisted(LivingEntity target, long gameTime) {
      Long expiry = BLACKLISTED_UNTIL.get(target.getUUID());
      return expiry != null && expiry > gameTime;
   }

   /**
    * 外部兼容入口（供魔龙夜陨等跨刀模块复用 Boss 死亡序列保护与安全斩杀，不串台播放无尽星空专属热寂视效）。
    */
   public static void executeFinal(LivingEntity target, Player attacker) {
      executeFinal(target, attacker, false);
   }

   /**
    * 安全热寂终结处决。
    * <ul>
    *   <li>对 {@code Player}、{@code EnderDragon} 及 {@code forge:bosses} 完整保留原生 {@code tickDeath()} 死亡序列，
    *       不设 {@code NoAi}、不强制 {@code discard()}，确保龙蛋、经验与末地传送门 100% 正常结算；</li>
    *   <li>强制死亡前补齐 {@code setLastHurtByPlayer(attacker)} 保留玩家击杀战利品与经验；</li>
    *   <li>兜底保障拔刀剑杀敌数递增；</li>
    *   <li>仅当 {@code playHeatDeathFx = true} 时触发无尽星空冰青热寂音效与视觉占位/事件，彻底隔离魔龙夜陨跨刀调用。</li>
    * </ul>
    */
   public static void executeFinal(LivingEntity target, Player attacker, boolean playHeatDeathFx) {
      if (target == null || attacker == null || target.level().isClientSide) {
         return;
      }
      if (!SlashBladeTargeting.canAttack(attacker, target) || isInternalExecution(target)) {
         return;
      }

      long gameTime = target.level().getGameTime();
      purgeBlacklist(gameTime);
      if (isBlacklisted(target, gameTime)) {
         return;
      }

      boolean preserveDeath = TerminusLogic.preservesDeathSequence(target);
      ItemStack scoringBlade = findScoringBlade(attacker);
      int killCountBefore = getKillCount(scoringBlade);

      if (playHeatDeathFx && target.level() instanceof ServerLevel level) {
         playHeatDeathAudioAndPlaceholder(level, target);
      }

      UUID targetId = target.getUUID();
      INTERNAL_EXECUTION.add(targetId);
      try {
         target.invulnerableTime = 0;
         DamageSource source = target.level().damageSources().playerAttack(attacker);
         target.hurt(source, EXECUTION_DAMAGE);
         if (target.isAlive()) {
            target.invulnerableTime = 0;
            // 兜底死亡前必须补齐玩家击杀归属，避免被免疫 hurt 的目标丢失经验与 killed_by_player 战利品
            target.setLastHurtByPlayer(attacker);
            target.setHealth(0.0F);
            target.die(source);
         }

         if (!preserveDeath) {
            if (target.isAlive()) {
               // 仅针对锁血/防死常规非 Boss 实体做硬移除兜底
               eraseNonBossTarget(target);
               ServerTickScheduler.schedule(1, () -> {
                  if (!target.isRemoved() && !TerminusLogic.preservesDeathSequence(target)) {
                     eraseNonBossTarget(target);
                  }
               });
            } else if (!target.isRemoved()) {
               target.discard();
            }
            blacklist(target);
         }

         if (!target.isAlive() || target.isRemoved()) {
            ensureKillCountIncremented(scoringBlade, killCountBefore);
         }
      } finally {
         INTERNAL_EXECUTION.remove(targetId);
      }
   }

   public static void clearPlayer(UUID playerId) {
      if (playerId != null) {
         MARKS.remove(playerId);
      }
   }

   public static void clearAll() {
      MARKS.clear();
      BLACKLISTED_UNTIL.clear();
      INTERNAL_EXECUTION.clear();
   }

   @SubscribeEvent
   public static void onLivingDeath(LivingDeathEvent event) {
      if (!event.getEntity().level().isClientSide) {
         removeTargetFromAllMarks(event.getEntity().getUUID());
      }
   }

   @SubscribeEvent
   public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
      if (!event.getLevel().isClientSide && event.getEntity() instanceof LivingEntity living) {
         removeTargetFromAllMarks(living.getUUID());
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      clearAll();
   }

   private static void removeTargetFromAllMarks(UUID targetId) {
      if (MARKS.isEmpty()) {
         return;
      }
      Iterator<Map.Entry<UUID, Map<UUID, Integer>>> it = MARKS.entrySet().iterator();
      while (it.hasNext()) {
         Map<UUID, Integer> playerMarks = it.next().getValue();
         playerMarks.remove(targetId);
         if (playerMarks.isEmpty()) {
            it.remove();
         }
      }
   }

   private static int addMarks(Player player, LivingEntity target, int delta) {
      Map<UUID, Integer> playerMarks = MARKS.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
      int marks = playerMarks.getOrDefault(target.getUUID(), 0) + delta;
      playerMarks.put(target.getUUID(), marks);
      return marks;
   }

   private static void setMarks(Player player, LivingEntity target, int marks) {
      Map<UUID, Integer> playerMarks = MARKS.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
      playerMarks.put(target.getUUID(), marks);
   }

   private static void clearMark(Player player, LivingEntity target) {
      Map<UUID, Integer> playerMarks = MARKS.get(player.getUUID());
      if (playerMarks != null) {
         playerMarks.remove(target.getUUID());
         if (playerMarks.isEmpty()) {
            MARKS.remove(player.getUUID());
         }
      }
   }

   private static void disableNonBossTarget(LivingEntity target) {
      target.stopRiding();
      target.ejectPassengers();
      target.setDeltaMovement(Vec3.ZERO);
      target.hasImpulse = true;
      if (target instanceof Mob mob) {
         mob.setTarget(null);
         mob.getNavigation().stop();
         mob.setNoAi(true);
      }
   }

   private static void eraseNonBossTarget(LivingEntity target) {
      if (TerminusLogic.preservesDeathSequence(target)) {
         return;
      }
      disableNonBossTarget(target);
      target.remove(Entity.RemovalReason.KILLED);
      target.discard();
   }

   private static void blacklist(LivingEntity target) {
      int duration = ModConfig.COMMON.infinityStellaris.entropyDissolution.entropyBlacklistTicks.getValue();
      if (duration <= 0) {
         return;
      }
      long expiry = target.level().getGameTime() + duration;
      BLACKLISTED_UNTIL.put(target.getUUID(), expiry);
   }

   private static void purgeBlacklist(long gameTime) {
      if (BLACKLISTED_UNTIL.isEmpty()) {
         return;
      }
      BLACKLISTED_UNTIL.entrySet().removeIf(entry -> entry.getValue() <= gameTime);
   }

   private static ItemStack findScoringBlade(Player attacker) {
      ItemStack main = attacker.getMainHandItem();
      if (SlashBladeStateSupport.isSlashBlade(main)) {
         return main;
      }
      ItemStack off = attacker.getOffhandItem();
      if (SlashBladeStateSupport.isSlashBlade(off)) {
         return off;
      }
      ItemStack anyBlade = ItemStack.EMPTY;
      for (ItemStack stack : attacker.getInventory().items) {
         if (InfinityStellarisItemSupport.isInfinityStellaris(stack)) {
            return stack;
         }
         if (anyBlade.isEmpty() && SlashBladeStateSupport.isSlashBlade(stack)) {
            anyBlade = stack;
         }
      }
      return anyBlade.isEmpty() ? main : anyBlade;
   }

   private static int getKillCount(ItemStack blade) {
      return SlashBladeStateSupport.isSlashBlade(blade) ? SlashBladeStateSupport.killCount(blade) : -1;
   }

   private static void ensureKillCountIncremented(ItemStack blade, int killCountBefore) {
      if (killCountBefore >= 0) {
         SlashBladeStateSupport.state(blade).ifPresent(state -> {
            if (state.getKillCount() == killCountBefore) {
               state.setKillCount(killCountBefore + 1);
            }
         });
      }
   }

   private static void playHeatDeathAudioAndPlaceholder(ServerLevel level, LivingEntity target) {
      Vec3 center = target.position().add(0.0, target.getBbHeight() * 0.55, 0.0);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.5F, 0.48F);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.2F, 1.45F);
      InfinityStellarisVisuals.spawnHeatDeathPlaceholder(level, target);
   }
}
