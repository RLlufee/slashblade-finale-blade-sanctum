package QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.AnnihilationInputRouter;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.PhantomJudgementPattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 湮灭之刃 SE「幻影审判·千刃星流」服务端逻辑。
 * <p>
 * 彻底移除服务端视觉实体（原原生召唤剑与残留剑），改由鼠标左键（拔刀剑 {@link InputCommand#L_DOWN} 按下沿）独立发射，
 * 避免与右键挥刀连招（如虚无权域、断空等）争抢触发窗口，并支持多组千刃序列并发运行：
 * <ul>
 *   <li>t = 0：左键锁定范围内合法目标（优先未锁定目标，单 Boss 自动回退允许叠锁），发送持续特效 {@code PHANTOM_START}（无目标不触发、不消耗冷却）；</li>
 *   <li>t = SUPPRESS_TICK（22）：母刃初抵，施加轻减速与微悬浮压制，防止目标跑散且不提前秒杀；</li>
 *   <li>t = CAGE_TICK ~ DETONATE_TICK - 1（34 ~ 55）：万刃星牢合围，温和锚定悬停等待终判；</li>
 *   <li>t = DETONATE_TICK（56）：先发送 {@code PHANTOM_DETONATE} 锁定最终目标，再同步执行 {@link TerminusLogic#execute} 终焉处决并结束服务端占位，由客户端独立演完斩绝余韵与百刃归鞘。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "annihilationblade")
public class PhantomJudgement extends SpecialEffect {
   /** 单玩家同屏允许并发推进的千刃星流序列上限（20t 冷却下 56t 寿命正常最多并发 3 组，留 1 组余量防 0 冷却刷爆）。 */
   private static final int MAX_ACTIVE_SEQUENCES = 4;
   private static final Map<UUID, List<Sequence>> ACTIVE = new HashMap<>();
   private static final Map<UUID, Long> LAST_TRIGGER = new HashMap<>();

   public PhantomJudgement() {
      super(0, false, false);
   }

   /** 保留对外入口并委托给 {@link AnnihilationInputRouter} 统一完成输入监听注册。 */
   public static void registerInputListener() {
      AnnihilationInputRouter.registerInputListener();
   }

   public static void clearPlayer(Level level, UUID playerId) {
      List<Sequence> sequences = ACTIVE.remove(playerId);
      if (sequences != null) {
         for (Sequence sequence : sequences) {
            AnnihilationFxEvents.phantomEnd(sequence.fxHandle);
         }
      }
      LAST_TRIGGER.remove(playerId);
   }

   /** 判断指定实体当前是否正处于幻影审判星牢锁定中（供因果坍缩微扰定身避让，避免覆盖星牢悬停）。 */
   public static boolean isEntityLocked(UUID entityUuid) {
      if (entityUuid == null || ACTIVE.isEmpty()) {
         return false;
      }
      for (List<Sequence> sequences : ACTIVE.values()) {
         for (Sequence sequence : sequences) {
            for (TargetLock lock : sequence.targets) {
               if (lock.targetId.equals(entityUuid)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   /** 由 {@link AnnihilationInputRouter} 在确认纯左键（未凑成左+右虚无权域双键）时调用。 */
   public static boolean tryTriggerFromInput(ServerPlayer player) {
      if (player == null || player.isSpectator() || !player.isAlive() || Dankong.isActive(player)) {
         return false;
      }
      ItemStack blade = player.getMainHandItem();
      if (blade.isEmpty()) {
         return false;
      }
      ISlashBladeState state = blade.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
      if (state == null || state.isBroken() || state.isSealed() || !state.hasSpecialEffect(ModSpecialEffects.PHANTOM_JUDGEMENT.getId())) {
         return false;
      }
      ModConfig.PhantomJudgement config = ModConfig.COMMON.annihilationBlade.phantomJudgement;
      if (!config.enabled.getValue()) {
         return false;
      }
      List<Sequence> activeList = ACTIVE.get(player.getUUID());
      if (activeList != null && activeList.size() >= MAX_ACTIVE_SEQUENCES) {
         return false;
      }
      long gameTime = player.level().getGameTime();
      int cooldown = config.cooldownTicks.getValue();
      long last = LAST_TRIGGER.getOrDefault(player.getUUID(), -cooldown * 2L - 10L);
      if (gameTime - last < cooldown) {
         return false;
      }

      ServerLevel level = player.serverLevel();
      List<TargetLock> locks = collectTargets(level, player, config, activeList);
      if (locks.isEmpty()) {
         return false;
      }

      LAST_TRIGGER.put(player.getUUID(), gameTime);
      Sequence sequence = new Sequence(state.getColorCode(), locks);
      ACTIVE.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>()).add(sequence);
      player.swing(InteractionHand.MAIN_HAND, true);

      Vec3 center = PhantomJudgementPattern.casterCenter(player.position(), player.getBbHeight());
      sequence.fxHandle = AnnihilationFxEvents.phantomStart(level, player, center, sequence.color, lockedEntityIds(level, locks));

      // 服务端仅播放低沉凝练的启阵解离基音，其余全部裂变/结阵/收弦/晶爆/归刃音效由客户端逐拍卡点
      level.playSound(null, center.x, center.y, center.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.35F, 1.45F);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.95F, 0.72F);
      return true;
   }

   @SubscribeEvent
   public static void onPlayerTick(PlayerTickEvent event) {
      if (event.phase != Phase.END || !(event.player instanceof ServerPlayer player)) {
         return;
      }
      List<Sequence> sequences = ACTIVE.get(player.getUUID());
      if (sequences == null || sequences.isEmpty()) {
         return;
      }
      ServerLevel level = player.serverLevel();
      if (!ModConfig.COMMON.annihilationBlade.phantomJudgement.enabled.getValue() || !player.isAlive() || player.isRemoved()) {
         clearPlayer(level, player.getUUID());
         return;
      }

      Iterator<Sequence> iterator = sequences.iterator();
      while (iterator.hasNext()) {
         Sequence sequence = iterator.next();
         sequence.age++;

         // 阶段一：母刃初抵目标，轻减速 + 微升空失重，避免怪物四散脱离星牢
         if (sequence.age == PhantomJudgementPattern.SUPPRESS_TICK) {
            suppressTargets(level, player, sequence);
         }

         // 阶段二：万刃星牢合围期间，锚定目标于阵心微悬停（每 3 tick 维护一次）
         if (sequence.age == PhantomJudgementPattern.CAGE_TICK) {
            refreshCageAnchors(level, player, sequence);
            holdTargetsInCage(level, player, sequence);
         } else if (sequence.age > PhantomJudgementPattern.CAGE_TICK
            && sequence.age < PhantomJudgementPattern.DETONATE_TICK
            && (sequence.age - PhantomJudgementPattern.CAGE_TICK) % 3 == 0) {
            holdTargetsInCage(level, player, sequence);
         }

         // 阶段三：齐爆斩绝瞬间——先发 UPDATE 通知客户端精准同步引爆，再执行终焉处决
         if (sequence.age >= PhantomJudgementPattern.DETONATE_TICK) {
            iterator.remove();
            detonateJudgement(level, player, sequence);
         }
      }

      if (sequences.isEmpty()) {
         ACTIVE.remove(player.getUUID());
      }
   }

   private static void suppressTargets(ServerLevel level, ServerPlayer player, Sequence sequence) {
      for (TargetLock lock : sequence.targets) {
         LivingEntity target = SpecialEffectSupport.findLivingEntity(level, lock.targetId);
         if (target == null || !target.isAlive() || !SpecialEffectSupport.canTarget(player, target)) {
            continue;
         }
         lock.anchor = SpecialEffectSupport.centerOf(target);
         Vec3 vel = target.getDeltaMovement();
         target.setDeltaMovement(vel.x * 0.38, Math.max(0.10, vel.y * 0.35), vel.z * 0.38);
         target.hasImpulse = true;
      }
   }

   private static void refreshCageAnchors(ServerLevel level, ServerPlayer player, Sequence sequence) {
      for (TargetLock lock : sequence.targets) {
         LivingEntity target = SpecialEffectSupport.findLivingEntity(level, lock.targetId);
         if (target != null && target.isAlive() && SpecialEffectSupport.canTarget(player, target)) {
            lock.anchor = SpecialEffectSupport.centerOf(target);
         }
      }
   }

   private static void holdTargetsInCage(ServerLevel level, ServerPlayer player, Sequence sequence) {
      for (TargetLock lock : sequence.targets) {
         LivingEntity target = SpecialEffectSupport.findLivingEntity(level, lock.targetId);
         if (target == null || !target.isAlive() || !SpecialEffectSupport.canTarget(player, target)) {
            continue;
         }
         Vec3 currentCenter = SpecialEffectSupport.centerOf(target);
         Vec3 delta = lock.anchor.subtract(currentCenter);
         double distSqr = delta.lengthSqr();
         if (distSqr > 0.25 && distSqr < 64.0) {
            SpecialEffectSupport.pullToward(target, lock.anchor, Math.min(0.45, Math.sqrt(distSqr) * 0.32));
         }
         Vec3 vel = target.getDeltaMovement();
         target.setDeltaMovement(vel.x * 0.22, Math.max(0.03, vel.y * 0.25), vel.z * 0.22);
         target.hasImpulse = true;
      }
   }

   private static void detonateJudgement(ServerLevel level, ServerPlayer player, Sequence sequence) {
      ModConfig.PhantomJudgement config = ModConfig.COMMON.annihilationBlade.phantomJudgement;
      Vec3 center = PhantomJudgementPattern.casterCenter(player.position(), player.getBbHeight());

      // 先收集本次最终处决的存活合法目标并下发 UPDATE，确保客户端能在实体移除前读取到精确位置与尺寸
      List<LivingEntity> targets = new ArrayList<>();
      int maxTargets = config.maxTargets.getValue();
      for (TargetLock lock : sequence.targets) {
         if (targets.size() >= maxTargets) {
            break;
         }
         LivingEntity target = SpecialEffectSupport.findLivingEntity(level, lock.targetId);
         if (target != null && target.isAlive() && SpecialEffectSupport.canTarget(player, target)) {
            targets.add(target);
         }
      }

      AnnihilationFxEvents.phantomDetonate(sequence.fxHandle, level, player, center, sequence.color, targets);

      for (LivingEntity target : targets) {
         if (target.isAlive()) {
            TerminusLogic.execute(target, player);
         }
      }

      AnnihilationFxEvents.phantomEnd(sequence.fxHandle);
   }

   private static List<TargetLock> collectTargets(
      ServerLevel level, ServerPlayer player, ModConfig.PhantomJudgement config, List<Sequence> activeSequences
   ) {
      List<LivingEntity> entities = SpecialEffectSupport.radialTargets(level, player, player.position(), config.range.getValue());
      if (entities.isEmpty()) {
         return List.of();
      }
      Set<UUID> alreadyLocked = new HashSet<>();
      if (activeSequences != null) {
         for (Sequence active : activeSequences) {
            for (TargetLock lock : active.targets) {
               alreadyLocked.add(lock.targetId);
            }
         }
      }

      List<TargetLock> locks = new ArrayList<>();
      int maxTargets = config.maxTargets.getValue();
      // 优先锁定尚未被其他进行中星牢锁定的新目标，避免连射全砸在将死目标身上漏掉其余敌群
      for (LivingEntity entity : entities) {
         if (!alreadyLocked.contains(entity.getUUID())) {
            locks.add(new TargetLock(entity.getUUID(), SpecialEffectSupport.centerOf(entity)));
            if (locks.size() >= maxTargets) {
               return locks;
            }
         }
      }
      // 若范围内全部目标皆已处于星牢中（如单挑 Boss 连发），则回退允许叠锁
      if (locks.isEmpty()) {
         for (LivingEntity entity : entities) {
            locks.add(new TargetLock(entity.getUUID(), SpecialEffectSupport.centerOf(entity)));
            if (locks.size() >= maxTargets) {
               break;
            }
         }
      }
      return locks;
   }

   private static int[] lockedEntityIds(ServerLevel level, List<TargetLock> locks) {
      int[] ids = new int[locks.size()];
      int count = 0;
      for (TargetLock lock : locks) {
         LivingEntity target = SpecialEffectSupport.findLivingEntity(level, lock.targetId);
         if (target != null) {
            ids[count++] = target.getId();
         }
      }
      return count == ids.length ? ids : Arrays.copyOf(ids, count);
   }

   private static final class Sequence {
      private final int color;
      private final List<TargetLock> targets;
      private int age;
      private int fxHandle = -1;

      private Sequence(int color, List<TargetLock> targets) {
         this.color = color;
         this.targets = targets;
      }
   }

   private static final class TargetLock {
      private final UUID targetId;
      private Vec3 anchor;

      private TargetLock(UUID targetId, Vec3 anchor) {
         this.targetId = targetId;
         this.anchor = anchor;
      }
   }
}
