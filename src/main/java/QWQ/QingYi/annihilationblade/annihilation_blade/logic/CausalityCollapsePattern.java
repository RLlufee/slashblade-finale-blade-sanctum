package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

/**
 * SE「因果坍缩·时空裂痕」双端共享的时间轴、智能索敌、引力绞杀阈值与量化坐标工具。
 * <p>
 * 仅引用双端通用 Minecraft 数学与坐标类型，禁止引用任何客户端渲染类。
 */
public final class CausalityCollapsePattern {
   /** 裂痕骤裂崩开阶段时长（tick）：极速撕开并把空间扭曲拉至峰值。 */
   public static final int OPEN_TICKS = 4;
   /** 裂痕临终因果校准对齐合缝阶段时长（tick）。 */
   public static final int CLOSE_TICKS = 16;
   /** 单道因果裂痕默认存续总时长（tick，64 tick ≈ 3.2 秒，其中约 2.2 秒满张滞留吸扯绞杀）。 */
   public static final int DEFAULT_SCAR_DURATION = 64;
   /** 被准星直接锁定开缝的目标在裂痕喉心受压绞杀所需的最小 tick（覆盖 4t 骤裂 + 4t 强压拉丝，确保肉眼看清吸入感）。 */
   public static final int DIRECT_CRUSH_TICKS = 8;
   /** 从外围被漩涡吸引进入裂痕喉心的目标，抵达核心后受压绞杀所需 tick（已有拉入位移过程，更利落干脆）。 */
   public static final int PULLED_CRUSH_TICKS = 5;
   /** 客户端单次裂痕吞噬绞杀（3D 躯壳沿狭缝拉丝撕裂吸入）完整演出时长（tick）。 */
   public static final int DEVOUR_FX_TICKS = 16;
   /** 单道裂痕吞噬目标后自身痉挛脉动过载持续时长（tick）。 */
   public static final int DEVOUR_PULSE_TICKS = 11;

   private CausalityCollapsePattern() {
   }

   /** 神威独立单链模式：在目标被拧断处决（SNAP_TICK）后，等待 3D 躯壳即将吸穿奇点并由因果坍缩接管的延迟（tick）。 */
   public static int singleRiftScarDelay() {
      return Math.max(2, WorldRiftPattern.SUCK_END_TICK - WorldRiftPattern.SNAP_TICK - 2);
   }

   /** 断空 + 神威群体联动模式：在引爆群体残骸神威后，于奇点吸穿残骸瞬间无缝接管的基准延迟（tick；各裂痕再叠加自身 stagger）。 */
   public static int groupRiftScarDelay() {
      return Math.max(4, WorldRiftPattern.LINKAGE_SUCK_END - 2);
   }

   /** 计算单道裂痕的活跃物理吸扯时长（在临终合缝尾声最后 10 tick 前平滑停吸放行）。 */
   public static int activeSuctionTicks(int scarDurationTicks) {
      return Math.max(OPEN_TICKS + 4, scarDurationTicks - Math.max(6, CLOSE_TICKS - 6));
   }

   /** 计算特定碰撞箱宽度实体触发裂痕喉心空间绞杀的核心半径。 */
   public static double coreCrushRadius(double entityWidth) {
      return Mth.clamp(entityWidth * 0.5 + 0.95, 1.15, 2.35);
   }

   /**
    * 解析拔刀剑原版按住 Shift 锁定的目标实体（{@link ISlashBladeState#getTargetEntity}）：
    * 支持多部位实体（{@link PartEntity}）自动解包，并校验合法性、存活状态、距离上限与跨 SE 避让条件。
    */
   @Nullable
   public static LivingEntity resolveSlashBladeLockOnTarget(
      ServerLevel level, ServerPlayer player, @Nullable ISlashBladeState state,
      double maxRange, @Nullable Predicate<LivingEntity> extraFilter
   ) {
      if (state == null || maxRange <= 0.5) {
         return null;
      }
      Entity raw = state.getTargetEntity(level);
      if (raw instanceof PartEntity<?> part) {
         raw = part.getParent();
      }
      if (!(raw instanceof LivingEntity living) || living instanceof Player) {
         return null;
      }
      if (!living.isAlive() || living.isRemoved() || living.level() != level) {
         return null;
      }
      if (!SlashBladeTargeting.canAttack(player, living)) {
         return null;
      }
      if (extraFilter != null && !extraFilter.test(living)) {
         return null;
      }
      double distSqr = SpecialEffectSupport.centerOf(living).distanceToSqr(player.getEyePosition());
      if (distSqr > maxRange * maxRange) {
         return null;
      }
      return living;
   }

   /** 根据目标碰撞箱生成贴合其身位正中且不埋地的裂痕锚点。 */
   public static AimResolution targetResolution(LivingEntity target) {
      float height = Math.max(0.9F, target.getBbHeight());
      // 矮小生物将裂痕重心略微抬高至距地 0.72 格以上，防止裂痕半截埋入地面
      double anchorY = target.getY() + Math.max(height * 0.54, 0.72);
      Vec3 anchor = new Vec3(target.getX(), anchorY, target.getZ());
      return new AimResolution(anchor, Math.max(1.15F, height), true, target.getId());
   }

   /**
    * 独立挥刀索敌与自由空挥解析（按三级优先级回退）：
    * <ol>
    *   <li><b>原版拔刀剑 Shift 锁敌联动</b>（当 {@code lockOnLinkage} 开启且刀上已通过 Shift 锁定合法生物）：
    *       无视准星偏角与薄障，在 {@code lockOnRange}（默认 64 格，完整覆盖原版 40 格锁敌）内直接于锁定目标身位正中斩开时空裂痕实现远程狙杀；</li>
    *   <li><b>自由准星锥角辅助索敌</b>（当未处于 Shift 锁敌且 {@code maxRange > 0} 时）：
    *       在 {@code maxRange} 与最大允许偏角 {@code maxAngleDeg} 锥体内选取最贴合准星且无实心墙遮挡的目标；</li>
    *   <li><b>自由空挥回退</b>：回退至准星前方 {@code 6.2} 格空气位置，保留自由挥刀斩开空气的体验。</li>
    * </ol>
    */
   public static AimResolution resolveStandaloneScar(
      ServerLevel level, ServerPlayer player, @Nullable ISlashBladeState state,
      boolean lockOnLinkage, double lockOnRange, double maxRange, double maxAngleDeg,
      Predicate<LivingEntity> extraFilter
   ) {
      if (lockOnLinkage) {
         LivingEntity locked = resolveSlashBladeLockOnTarget(level, player, state, lockOnRange, extraFilter);
         if (locked != null) {
            return targetResolution(locked);
         }
      }
      return resolveAimOrAirScar(level, player, maxRange, maxAngleDeg, extraFilter);
   }

   /**
    * 未启用或未命中 Shift 锁敌时的自由准星锥角索敌与空挥回退解析。
    */
   public static AimResolution resolveAimOrAirScar(
      ServerLevel level, ServerPlayer player, double maxRange, double maxAngleDeg, Predicate<LivingEntity> extraFilter
   ) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = safeDir(player.getLookAngle());

      if (maxRange > 0.5 && maxAngleDeg > 0.5) {
         double clampedRange = Mth.clamp(maxRange, 1.0, 64.0);
         double clampedAngleRad = Math.toRadians(Mth.clamp(maxAngleDeg, 2.0, 45.0));
         double tanMax = Math.tan(clampedAngleRad);
         double cosMax = Math.cos(clampedAngleRad);
         // 近距宽容硬底线：防止 0.5 格贴身时把侧方 80° 处的敌人误锁进来
         double cosHardFloor = Math.cos(Math.toRadians(Math.min(48.0, maxAngleDeg + 18.0)));

         Vec3 farEnd = eye.add(look.scale(clampedRange));
         double expand = clampedRange * Math.sin(clampedAngleRad) + 2.5;
         AABB searchBox = new AABB(eye, farEnd).inflate(expand);
         List<LivingEntity> candidates = level.getEntitiesOfClass(
            LivingEntity.class,
            searchBox,
            entity -> !(entity instanceof Player)
               && SlashBladeTargeting.canAttack(player, entity)
               && (extraFilter == null || extraFilter.test(entity))
         );

         LivingEntity best = null;
         double bestScore = Double.MAX_VALUE;
         for (LivingEntity candidate : candidates) {
            Vec3 center = SpecialEffectSupport.centerOf(candidate);
            Vec3 toTarget = center.subtract(eye);
            double distSqr = toTarget.lengthSqr();
            if (distSqr < 0.12 || distSqr > clampedRange * clampedRange) {
               continue;
            }
            double dist = Math.sqrt(distSqr);
            double proj = toTarget.dot(look);
            if (proj <= 0.15) {
               continue;
            }
            double cosAngle = proj / dist;
            if (cosAngle < cosHardFloor) {
               continue;
            }
            double perpSqr = Math.max(0.0, distSqr - proj * proj);
            // 允许准星瞄到目标身躯轮廓，同时远距离严格收束在设定锥角内
            double allowedPerp = proj * tanMax + candidate.getBbWidth() * 0.55 + 0.35;
            if (perpSqr > allowedPerp * allowedPerp) {
               continue;
            }
            // 视线无实心墙遮挡校验（允许命中实体身前紧邻薄障或略低于目标质心距离 0.45 格内）
            BlockHitResult clip = level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (clip.getType() == HitResult.Type.BLOCK && eye.distanceTo(clip.getLocation()) + 0.45 < dist) {
               continue;
            }
            double angleNorm = Math.max(0.0, 1.0 - cosAngle) / Math.max(1.0E-4, 1.0 - cosMax);
            double distNorm = dist / clampedRange;
            double score = angleNorm * 0.72 + distNorm * 0.28;
            if (score < bestScore) {
               bestScore = score;
               best = candidate;
            }
         }

         if (best != null) {
            return targetResolution(best);
         }
      }

      return new AimResolution(resolveAirScarAnchor(level, player), 2.0F, false, -1);
   }

   /**
    * 独立空挥模式解析单道裂痕锚点：置于玩家准星前方 6.2 格处（兼顾近景震撼与周边地形卷入涡旋）；
    * 若前方有实心方块遮挡则微退至墙面之前，避免被方块吞没。
    */
   public static Vec3 resolveAirScarAnchor(ServerLevel level, ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = safeDir(player.getLookAngle());
      double focalDist = 6.2;
      Vec3 far = eye.add(look.scale(focalDist));
      BlockHitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
      if (hit.getType() == HitResult.Type.BLOCK) {
         double hitDist = eye.distanceTo(hit.getLocation());
         double pullback = Math.min(1.4, hitDist * 0.28);
         return eye.add(look.scale(Math.max(2.0, hitDist - pullback)));
      }
      return far;
   }

   private static Vec3 safeDir(Vec3 dir) {
      if (dir == null || dir.lengthSqr() < 1.0E-6) {
         return new Vec3(0.0, 0.0, 1.0);
      }
      return dir.normalize();
   }

   // ───────────────────────────── 逐目标多点 3D 坐标量化编解码（±2048 格，1/16 格精度） ─────────────────────────────

   /** 将相对 origin 的 (dx, dz) 量化为 16-bit 有符号整数打包至一个 int。 */
   public static int packXZ(Vec3 origin, Vec3 pos) {
      int qx = Mth.clamp((int)Math.round((pos.x - origin.x) * 16.0), -32768, 32767);
      int qz = Mth.clamp((int)Math.round((pos.z - origin.z) * 16.0), -32768, 32767);
      return (qx & 0xFFFF) | ((qz & 0xFFFF) << 16);
   }

   /** 将相对 origin 的 dy（16-bit 有符号）、目标身形高度（0.1 格步进，8-bit）与错峰延迟（8-bit）打包至一个 int。 */
   public static int packYMeta(Vec3 origin, Vec3 pos, float entityHeight, int staggerTick) {
      int qy = Mth.clamp((int)Math.round((pos.y - origin.y) * 16.0), -32768, 32767);
      int heightDeci = Mth.clamp((int)Math.round(entityHeight * 10.0F), 8, 80);
      int stagger = Mth.clamp(staggerTick, 0, 255);
      return (qy & 0xFFFF) | ((heightDeci & 0xFF) << 16) | ((stagger & 0xFF) << 24);
   }

   public static Vec3 unpackPos(Vec3 origin, int packedXZ, int packedYMeta) {
      short qx = (short)(packedXZ & 0xFFFF);
      short qz = (short)((packedXZ >>> 16) & 0xFFFF);
      short qy = (short)(packedYMeta & 0xFFFF);
      return origin.add(qx / 16.0, qy / 16.0, qz / 16.0);
   }

   public static float unpackHeight(int packedYMeta) {
      int dec = (packedYMeta >>> 16) & 0xFF;
      return Math.max(0.8F, dec * 0.1F);
   }

   public static int unpackStagger(int packedYMeta) {
      return (packedYMeta >>> 24) & 0xFF;
   }

   /** 服务端传递单道因果裂痕位置、目标身形高度与错峰起裂 tick 的轻量描述体。 */
   public record ScarSpec(Vec3 center, float entityHeight, int staggerTick) {
   }

   /** 独立挥刀准星智能索敌与空挥回退结果。 */
   public record AimResolution(Vec3 anchor, float entityHeight, boolean lockedTarget, int targetEntityId) {
   }
}
