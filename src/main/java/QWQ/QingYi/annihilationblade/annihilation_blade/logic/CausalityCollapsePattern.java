package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import net.minecraft.core.Direction;
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
 * SE「因果坍缩·时空裂痕」双端共享的时间轴、分层智能索敌、抗遮挡落点与量化坐标工具。
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
   /** 被准星/锁敌直接开缝的目标在裂痕喉心受压绞杀所需的最小 tick（覆盖 4t 骤裂 + 4t 强压拉丝，确保肉眼看清吸入感）。 */
   public static final int DIRECT_CRUSH_TICKS = 8;
   /** 从外围被漩涡吸引进入裂痕喉心的目标，抵达核心后受压绞杀所需 tick（已有拉入位移过程，更利落干脆）。 */
   public static final int PULLED_CRUSH_TICKS = 5;
   /** 客户端单次裂痕吞噬绞杀（3D 躯壳沿狭缝拉丝撕裂吸入）完整演出时长（tick）。 */
   public static final int DEVOUR_FX_TICKS = 16;
   /** 单道裂痕吞噬目标后自身痉挛脉动过载持续时长（tick）。 */
   public static final int DEVOUR_PULSE_TICKS = 11;
   /** 空挥“所见即所斩”命中地面/墙面的最大探距（格）。 */
   public static final double AIR_SURFACE_TRACE_RANGE = 14.0;
   /** 对着纯天空或超距开阔视野空挥时的默认中景距离（格）。 */
   public static final double AIR_OPEN_SKY_DISTANCE = 8.5;

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
    * 独立挥刀三段分层解析：
    * <ol>
    *   <li><b>第 1 层 · 原版拔刀剑 Shift 锁敌（超远距狙杀）</b>：若 {@code lockOnLinkage} 开启且刀上已通过 Shift 锁定合法生物，
    *       在 {@code lockOnRange}（默认 64 格，覆盖原版 40 格锁敌）内无视偏角与薄障，直接在锁定目标身位正中开缝；</li>
    *   <li><b>第 2 层 · 免 Shift 准星智能索敌（中近距直觉）</b>：在 {@code maxRange}（默认 24 格）与最大允许偏角
    *       {@code maxAngleDeg}（默认 13°）锥体内，以 {@code VISUAL} 头/胸/下躯干三点抗遮挡视线校验选出肉眼可见的最优敌对目标；</li>
    *   <li><b>第 3 层 · 智能空挥落点（所见即所斩）</b>：准星偏离目标或无可见目标时，14 格内命中地面/墙面则按表面外法线贴合落点，
    *       纯天空/远景则落在 8.5 格舒适中景处。</li>
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
      LivingEntity aimed = findCrosshairTarget(level, player, maxRange, maxAngleDeg, extraFilter);
      if (aimed != null) {
         return targetResolution(aimed);
      }
      return new AimResolution(resolveAirScarAnchor(level, player), 2.0F, false, -1);
   }

   /**
    * 免 Shift 准星锥角智能索敌（含 {@code ClipContext.Block.VISUAL} 头/胸/下躯干三点抗遮挡校验）。
    * 透过草丛、玻璃、栅栏、半砖或半身矮掩体依然能准确锁定肉眼可见目标，同时严格防止隔实心墙/山体/矿洞穿墙误锁。
    */
   @Nullable
   public static LivingEntity findCrosshairTarget(
      ServerLevel level, ServerPlayer player, double maxRange, double maxAngleDeg, @Nullable Predicate<LivingEntity> extraFilter
   ) {
      if (maxRange <= 0.5 || maxAngleDeg <= 0.5) {
         return null;
      }
      Vec3 eye = player.getEyePosition();
      Vec3 look = safeDir(player.getLookAngle());
      double clampedRange = Mth.clamp(maxRange, 1.0, 64.0);
      double clampedAngleRad = Math.toRadians(Mth.clamp(maxAngleDeg, 2.0, 45.0));
      double tanMax = Math.tan(clampedAngleRad);
      double cosMax = Math.cos(clampedAngleRad);
      // 近距宽容硬底线：防止贴身 0.5 格时把侧面 80° 的目标误吸进锥体
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
         if (distSqr < 1.0E-4 || distSqr > clampedRange * clampedRange) {
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
         // 计入目标碰撞箱半宽，确保准星擦过敌人肩/躯干轮廓同样跟手
         double allowedPerp = proj * tanMax + candidate.getBbWidth() * 0.55 + 0.30;
         if (perpSqr > allowedPerp * allowedPerp) {
            continue;
         }
         if (!hasVisualLineOfSight(level, player, eye, candidate)) {
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
      return best;
   }

   /**
    * 三点视觉遮挡检测（胸口 56% → 头部 84% → 下躯干 28%）：
    * 使用 {@link ClipContext.Block#VISUAL} 跳过草、花与玻璃等视觉透明物，只要任意部位未被实心墙体挡住即视为可见。
    */
   private static boolean hasVisualLineOfSight(ServerLevel level, ServerPlayer player, Vec3 eye, LivingEntity target) {
      double height = Math.max(0.5, target.getBbHeight());
      double x = target.getX();
      double y = target.getY();
      double z = target.getZ();
      return isPointVisuallyClear(level, player, eye, new Vec3(x, y + height * 0.56, z))
         || isPointVisuallyClear(level, player, eye, new Vec3(x, y + height * 0.84, z))
         || isPointVisuallyClear(level, player, eye, new Vec3(x, y + height * 0.28, z));
   }

   private static boolean isPointVisuallyClear(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 sample) {
      BlockHitResult clip = level.clip(new ClipContext(eye, sample, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
      if (clip.getType() == HitResult.Type.MISS) {
         return true;
      }
      double sampleDist = eye.distanceTo(sample);
      double hitDist = eye.distanceTo(clip.getLocation());
      // 目标紧贴墙面/半砖站立时允许 0.35 格贴边误差，但整堵实心墙挡在前方的目标会被严格拒绝
      return hitDist + 0.35 >= sampleDist;
   }

   /**
    * 智能空挥落点解析（“所见即所斩” + 表面外法线防穿模 + 防贴墙埋入）：
    * <ul>
    *   <li>沿视线用 {@code COLLIDER} 探测 {@value #AIR_SURFACE_TRACE_RANGE} 格（自动穿透野草花朵）：</li>
    *   <li>命中地面（{@code UP}）：将裂痕重心抬升至地表上方 ~1.12 格（低矮矿洞自动取地顶中点），下尖轻吻地表不埋地；</li>
    *   <li>命中墙面/树干/天花板：沿方块表面外法线推出并受 {@code hitDist * 0.45} 钳制，贴墙砍绝不穿入墙背或飞到玩家脑后；
    *       贴近墙根时自动做底部地面净空微抬；</li>
    *   <li>纯天空或超距开阔视野：落在视线前方 {@value #AIR_OPEN_SKY_DISTANCE} 格舒适中景位置。</li>
    * </ul>
    */
   public static Vec3 resolveAirScarAnchor(ServerLevel level, ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = safeDir(player.getLookAngle());
      Vec3 traceEnd = eye.add(look.scale(AIR_SURFACE_TRACE_RANGE));
      BlockHitResult hit = level.clip(new ClipContext(eye, traceEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
      if (hit.getType() == HitResult.Type.MISS) {
         return eye.add(look.scale(AIR_OPEN_SKY_DISTANCE));
      }

      Vec3 hitPos = hit.getLocation();
      double hitDist = eye.distanceTo(hitPos);
      Direction face = hit.getDirection();

      if (face == Direction.UP) {
         // 命中地面：检查正上方是否有低矮天花板，避免在 2 格矿洞中把裂痕顶进洞顶
         double lift = 1.12;
         Vec3 base = hitPos.add(0.0, 0.05, 0.0);
         BlockHitResult ceiling = level.clip(new ClipContext(base, base.add(0.0, 1.35, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
         if (ceiling.getType() == HitResult.Type.BLOCK) {
            double gap = Math.max(0.4, ceiling.getLocation().y - hitPos.y);
            lift = Math.min(lift, gap * 0.5);
         }
         return hitPos.add(0.0, lift, 0.0);
      }

      if (face == Direction.DOWN) {
         // 命中天花板/悬崖底面：向下沿法线温柔推出，且贴顶时不超过距离的 45%
         double drop = Math.min(1.05, Math.max(0.22, hitDist * 0.45));
         return hitPos.add(0.0, -drop, 0.0);
      }

      // 命中垂直墙面/柱子/树干：沿墙面外法线推出，既保留切墙光刃又不穿模、不反推到玩家脑后
      Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
      double push = Math.min(0.78, Math.max(0.18, hitDist * 0.45));
      Vec3 anchor = hitPos.add(normal.scale(push));
      // 1 格宽狭缝保护：若推出路径撞到对面墙体，则自动停在两墙中点
      Vec3 pushStart = hitPos.add(normal.scale(0.03));
      BlockHitResult opposite = level.clip(new ClipContext(pushStart, anchor, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
      if (opposite.getType() == HitResult.Type.BLOCK) {
         anchor = hitPos.lerp(opposite.getLocation(), 0.5);
      }

      // 若劈在靠近地面的墙根位置，稍微抬高离地避免裂痕下半截埋入地板（同时受头顶净空约束）
      BlockHitResult floor = level.clip(new ClipContext(anchor, anchor.add(0.0, -0.95, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
      if (floor.getType() == HitResult.Type.BLOCK) {
         double desiredY = floor.getLocation().y + 0.92;
         if (desiredY > anchor.y) {
            BlockHitResult ceiling = level.clip(new ClipContext(anchor, new Vec3(anchor.x, desiredY + 0.35, anchor.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (ceiling.getType() == HitResult.Type.MISS) {
               anchor = new Vec3(anchor.x, desiredY, anchor.z);
            }
         }
      }
      return anchor;
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
