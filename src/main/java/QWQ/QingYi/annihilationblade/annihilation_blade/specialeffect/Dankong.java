package QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.TerminusLogic;
import QWQ.QingYi.annihilationblade.annihilation_blade.logic.WorldRiftChain;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@EventBusSubscriber(modid = "annihilationblade")
public class Dankong extends SpecialEffect {
   private static final Map<UUID, Dankong.Sequence> ACTIVE = new HashMap<>();
   private static final Map<UUID, Long> LAST_TRIGGER = new HashMap<>();
   private static final Map<UUID, Boolean> BLINK_ENABLED = new HashMap<>();

   public Dankong() {
      super(0, false, false);
   }

   public static boolean isActive(Player player) {
      return ACTIVE.containsKey(player.getUUID());
   }

   public static boolean isBlinkEnabled(Player player) {
      return BLINK_ENABLED.getOrDefault(player.getUUID(), true);
   }

   /** 判断玩家主手或副手是否持有未损坏、未封印且挂载了「断空」SE 的任意拔刀剑（支持 SE 独立移植）。 */
   public static boolean isHoldingDankongBlade(Player player) {
      if (player == null) {
         return false;
      }
      return hasDankongEffect(player.getMainHandItem()) || hasDankongEffect(player.getOffhandItem());
   }

   private static boolean hasDankongEffect(net.minecraft.world.item.ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      ISlashBladeState state = stack.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
      return state != null && !state.isBroken() && !state.isSealed() && state.hasSpecialEffect(ModSpecialEffects.DANKONG.getId());
   }

   /**
    * 判断玩家当前是否处于「断空关闭状态」：
    * 配置禁用、按住 Shift 临时抑制、Left Ctrl 临时切关闪现、或刀上未挂载断空 SE 均视为关闭。
    */
   public static boolean isDisabledFor(Player player, ISlashBladeState state) {
      return !ModConfig.COMMON.annihilationBlade.dankong.enabled.getValue()
         || player.isShiftKeyDown()
         || !isBlinkEnabled(player)
         || state == null
         || !state.hasSpecialEffect(ModSpecialEffects.DANKONG.getId());
   }

   public static void setBlinkEnabled(Player player, boolean enabled) {
      BLINK_ENABLED.put(player.getUUID(), enabled);
      if (!enabled && player instanceof ServerPlayer serverPlayer) {
         Dankong.Sequence sequence = ACTIVE.get(player.getUUID());
         if (sequence != null) {
            finishSequence(serverPlayer, sequence);
         }
      }
   }

   public static void clearBlinkMode(UUID playerId) {
      BLINK_ENABLED.remove(playerId);
   }

   public static void clearPlayer(UUID playerId) {
      cancelActiveSequence(playerId);
      LAST_TRIGGER.remove(playerId);
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (Dankong.Sequence sequence : ACTIVE.values()) {
         AnnihilationFxEvents.dankongFinish(sequence.fxHandle);
      }
      ACTIVE.clear();
      LAST_TRIGGER.clear();
      BLINK_ENABLED.clear();
   }

   /**
    * 由 {@code AnnihilationInputRouter} 在确认非左+右虚无权域双键后调用。
    *
    * @return 若成功启动断空闪斩序列则返回 true（阻断同次挥刀触发独立空气版因果坍缩）
    */
   public static boolean tryTriggerFromSlash(ServerPlayer player, ISlashBladeState state) {
      if (player == null || state == null || player.level().isClientSide() || !ModConfig.COMMON.annihilationBlade.dankong.enabled.getValue()) {
         return false;
      }
      if (!state.hasSpecialEffect(ModSpecialEffects.DANKONG.getId()) || player.isShiftKeyDown() || !isBlinkEnabled(player)) {
         return false;
      }
      if (ACTIVE.containsKey(player.getUUID())) {
         return false;
      }
      long gameTime = player.level().getGameTime();
      int cooldown = ModConfig.COMMON.annihilationBlade.dankong.cooldownTicks.getValue();
      long last = LAST_TRIGGER.getOrDefault(player.getUUID(), -cooldown * 2L);
      if (gameTime - last < cooldown) {
         return false;
      }

      Vec3 origin = player.position();
      List<UUID> targets = collectTargets(player, origin);
      if (targets.isEmpty()) {
         return false;
      }

      LAST_TRIGGER.put(player.getUUID(), gameTime);
      boolean linkWorldRift = state.hasSpecialEffect(ModSpecialEffects.WORLD_RIFT.getId())
         && ModConfig.COMMON.annihilationBlade.worldRift.enabled.getValue()
         && ModConfig.COMMON.annihilationBlade.worldRift.dankongLinkage.getValue();
      Dankong.Sequence sequence = new Dankong.Sequence(origin, player.getYRot(), player.getXRot(), targets, linkWorldRift);
      ACTIVE.put(player.getUUID(), sequence);
      int stepInterval = ModConfig.COMMON.annihilationBlade.dankong.stepInterval.getValue();
      sequence.fxHandle = AnnihilationFxEvents.dankongOpen(
         player.serverLevel(),
         player,
         chestOf(player, origin),
         ModConfig.COMMON.annihilationBlade.dankong.range.getValue(),
         ModConfig.COMMON.annihilationBlade.dankong.visualScale.getValue(),
         targets.size() * stepInterval + stepInterval * 2 + 20,
         entityIds(player.serverLevel(), targets)
      );
      player.level()
         .playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.2F, 1.8F);
      return true;
   }

   @SubscribeEvent
   public static void onPlayerTick(PlayerTickEvent event) {
      if (event.phase == Phase.END) {
         if (event.player instanceof ServerPlayer player) {
            Dankong.Sequence sequence = ACTIVE.get(player.getUUID());
            if (sequence != null) {
               if (!ModConfig.COMMON.annihilationBlade.dankong.enabled.getValue() || player.isShiftKeyDown() || !isBlinkEnabled(player)) {
                  finishSequence(player, sequence);
                  return;
               }

               sequence.age++;
               if (sequence.age % ModConfig.COMMON.annihilationBlade.dankong.stepInterval.getValue() == 0) {
                  ServerLevel level = player.serverLevel();

                  while (sequence.index < sequence.targets.size()) {
                     LivingEntity target = findTarget(level, sequence.targets.get(sequence.index++));
                     if (target != null && canTarget(player, target)) {
                        strikeTarget(level, player, target, sequence);
                        return;
                     }
                  }

                  finishSequence(player, sequence);
               }
            }
         }
      }
   }

   private static void cancelActiveSequence(UUID playerId) {
      Dankong.Sequence sequence = ACTIVE.remove(playerId);
      if (sequence != null) {
         AnnihilationFxEvents.dankongFinish(sequence.fxHandle);
         if (sequence.riftLinkage) {
            WorldRiftChain.cancelDankongLinkage(playerId);
         }
      }
   }

   private static void finishSequence(ServerPlayer player, Dankong.Sequence sequence) {
      ServerLevel level = player.serverLevel();
      player.teleportTo(level, sequence.origin.x, sequence.origin.y, sequence.origin.z, sequence.yRot, sequence.xRot);
      player.setDeltaMovement(Vec3.ZERO);
      level.playSound(null, sequence.origin.x, sequence.origin.y, sequence.origin.z, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.PLAYERS, 1.4F, 0.6F);
      AnnihilationFxEvents.dankongFinish(sequence.fxHandle);
      ACTIVE.remove(player.getUUID());
      if (sequence.riftLinkage) {
         if (sequence.strikes > 0) {
            WorldRiftChain.scheduleDankongLinkage(player, sequence.origin);
         } else {
            WorldRiftChain.cancelDankongLinkage(player.getUUID());
         }
      }
   }

   /** 锁定目标的实体 ID（仅用于客户端视觉：死兆星）。 */
   private static int[] entityIds(ServerLevel level, List<UUID> targets) {
      int[] ids = new int[Math.min(targets.size(), 256)];
      int count = 0;
      for (UUID uuid : targets) {
         if (count >= ids.length) {
            break;
         }
         LivingEntity entity = findTarget(level, uuid);
         if (entity != null) {
            ids[count++] = entity.getId();
         }
      }
      return count == ids.length ? ids : java.util.Arrays.copyOf(ids, count);
   }

   private static Vec3 chestOf(Player player, Vec3 feet) {
      return feet.add(0.0, player.getBbHeight() * 0.5, 0.0);
   }

   private static List<UUID> collectTargets(ServerPlayer player, Vec3 origin) {
      double range = ModConfig.COMMON.annihilationBlade.dankong.range.getValue();
      int maxTargets = ModConfig.COMMON.annihilationBlade.dankong.maxTargets.getValue();
      AABB area = new AABB(origin, origin).inflate(range);
      List<LivingEntity> entities = player.serverLevel()
         .getEntitiesOfClass(
            LivingEntity.class,
            area,
            entityx -> SpecialEffectSupport.canTarget(player, entityx) && SpecialEffectSupport.distanceToBoxSqr(origin, entityx.getBoundingBox()) <= range * range
         );
      entities.sort(
         (a, b) -> Double.compare(
            SpecialEffectSupport.distanceToBoxSqr(origin, b.getBoundingBox()), SpecialEffectSupport.distanceToBoxSqr(origin, a.getBoundingBox())
         )
      );
      List<UUID> result = new ArrayList<>();

      for (LivingEntity entity : entities) {
         result.add(entity.getUUID());
         if (result.size() >= maxTargets) {
            break;
         }
      }

      return result;
   }

   private static LivingEntity findTarget(ServerLevel level, UUID uuid) {
      return SpecialEffectSupport.findLivingEntity(level, uuid);
   }

   private static void strikeTarget(ServerLevel level, ServerPlayer player, LivingEntity target, Dankong.Sequence sequence) {
      Vec3 origin = sequence.origin;
      Vec3 targetCenter = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
      Vec3 away = target.position().subtract(origin);
      if (away.lengthSqr() < 0.01) {
         away = player.getLookAngle();
      }

      Vec3 attackPos = resolveSafeStrikePos(level, player, target, away);
      float yaw = yawToFace(attackPos, targetCenter);
      float pitch = pitchToFace(attackPos, targetCenter);
      Vec3 travelStart = chestOf(player, player.position());
      player.teleportTo(level, attackPos.x, attackPos.y, attackPos.z, yaw, pitch);
      player.setDeltaMovement(Vec3.ZERO);
      // 先发事件再斩击：客户端需要在目标被处决前解析到它
      AnnihilationFxEvents.dankongStrike(
         sequence.fxHandle, level, player, travelStart, chestOf(player, attackPos), target, sequence.strikes++,
         ModConfig.COMMON.annihilationBlade.dankong.visualScale.getValue()
      );
      if (sequence.riftLinkage) {
         WorldRiftChain.recordDankongStrike(player, level, target, sequence.origin, targetCenter.subtract(attackPos));
      }
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 1.9F);
      // 仅播挥手动画而不生成高伤范围 EntitySlashEffect，防止首闪提前劈死相邻排队目标、破坏多目标瞬闪与裂界/因果联动
      player.swing(InteractionHand.MAIN_HAND, true);
      if (sequence.riftLinkage) {
         TerminusLogic.executeSilent(target, player);
      } else {
         TerminusLogic.execute(target, player);
      }
   }

   private static boolean canTarget(Player player, LivingEntity candidate) {
      return SpecialEffectSupport.canTarget(player, candidate);
   }

   /**
    * 瞬移落点碰撞安全检测：依次尝试理想外侧、近距、反侧、左右侧向及目标原位，
    * 避免在矿洞或贴墙战斗时把玩家传送进实心方块内部导致窒息或黑屏。
    */
   private static Vec3 resolveSafeStrikePos(ServerLevel level, ServerPlayer player, LivingEntity target, Vec3 away) {
      Vec3 horiz = new Vec3(away.x, 0.0, away.z);
      Vec3 dir = horiz.lengthSqr() > 1.0E-4 ? horiz.normalize() : new Vec3(player.getLookAngle().x, 0.0, player.getLookAngle().z);
      if (dir.lengthSqr() <= 1.0E-4) {
         dir = new Vec3(0.0, 0.0, 1.0);
      } else {
         dir = dir.normalize();
      }
      Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
      Vec3 base = target.position().add(0.0, 0.15, 0.0);
      Vec3[] candidates = new Vec3[]{
         base.add(dir.scale(1.8)),
         base.add(dir.scale(1.1)),
         base.add(dir.scale(-1.5)),
         base.add(side.scale(1.4)),
         base.add(side.scale(-1.4)),
         target.position().add(0.0, 0.25, 0.0)
      };
      Vec3 playerPos = player.position();
      AABB playerBox = player.getBoundingBox();
      for (Vec3 candidate : candidates) {
         AABB moved = playerBox.move(candidate.x - playerPos.x, candidate.y - playerPos.y, candidate.z - playerPos.z);
         if (level.noCollision(player, moved)) {
            return candidate;
         }
      }
      return playerPos;
   }

   private static float yawToFace(Vec3 from, Vec3 to) {
      Vec3 diff = to.subtract(from);
      return (float)(Mth.atan2(diff.z, diff.x) * 180.0F / (float)Math.PI) - 90.0F;
   }

   private static float pitchToFace(Vec3 from, Vec3 to) {
      Vec3 diff = to.subtract(from);
      double horizontal = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
      return (float)(-(Mth.atan2(diff.y, horizontal) * 180.0F / (float)Math.PI));
   }

   private static class Sequence {
      private final Vec3 origin;
      private final float yRot;
      private final float xRot;
      private final List<UUID> targets;
      private final boolean riftLinkage;
      private int index;
      private int age;
      private int strikes;
      private int fxHandle = -1;

      private Sequence(Vec3 origin, float yRot, float xRot, List<UUID> targets, boolean riftLinkage) {
         this.origin = origin;
         this.yRot = yRot;
         this.xRot = xRot;
         this.targets = targets;
         this.riftLinkage = riftLinkage;
      }
   }
}
