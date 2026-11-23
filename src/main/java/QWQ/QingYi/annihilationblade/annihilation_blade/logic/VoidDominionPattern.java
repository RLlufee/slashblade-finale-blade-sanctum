package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.LivingEntity;

/**
 * 虚无权域服务端与客户端共用的时间轴和空间曲线。
 */
public final class VoidDominionPattern {
   public static final int EXPAND_END_TICK = 14;
   public static final int CATALOG_END_TICK = 26;
   public static final int SWEEP_START_TICK = 26;
   public static final int SWEEP_END_TICK = 52;
   public static final int COLLAPSE_TICK = 54;
   public static final int ZERO_SNAP_TICK = 64;
   public static final int RESTORE_START_TICK = 68;
   public static final int TOTAL_LIFETIME = 92;

   private static final double MIN_ANCHOR_DISTANCE = 5.0;
   private static final double FALLBACK_ANCHOR_DISTANCE = 10.0;
   private static final double MAX_ANCHOR_DISTANCE = 22.0;

   private VoidDominionPattern() {
   }

   /** 球形扫描波前半径。末段加入轻微的向外加速。 */
   public static float frontRadius(float tick, float maxRadius) {
      float progress = Mth.clamp((tick - SWEEP_START_TICK) / (float)(SWEEP_END_TICK - SWEEP_START_TICK), 0.0F, 1.0F);
      float sine = 0.5F - 0.5F * Mth.cos((float)Math.PI * progress);
      float tail = progress * progress * progress * progress * progress * progress;
      float eased = sine * 0.965F + tail * 0.035F;
      return Math.max(0.0F, maxRadius) * eased;
   }

   /** 返回波前首次到达目标距离的整数 tick。 */
   public static int eraseTick(double distance, double maxRadius) {
      if (!(maxRadius > 0.0) || distance <= 0.0) {
         return SWEEP_START_TICK;
      }
      if (distance >= maxRadius) {
         return SWEEP_END_TICK;
      }

      float targetDistance = (float)distance;
      float radius = (float)maxRadius;
      int low = SWEEP_START_TICK;
      int high = SWEEP_END_TICK;
      while (low < high) {
         int middle = (low + high) >>> 1;
         if (frontRadius(middle, radius) < targetDistance) {
            low = middle + 1;
         } else {
            high = middle;
         }
      }
      return low;
   }

   /**
    * 将视线锚点落在前方地表。优先采用准星命中的实体或方块，空视线时回退到玩家前方十格。
    */
   public static Vec3 resolveCenter(Level level, Vec3 eyePos, Vec3 lookDir, double range, @Nullable LivingEntity focusTarget) {
      double maxDistance = Math.max(MIN_ANCHOR_DISTANCE, Math.min(MAX_ANCHOR_DISTANCE, range * 0.75));
      double distance = Math.min(FALLBACK_ANCHOR_DISTANCE, maxDistance);
      Vec3 direction = lookDir.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : lookDir.normalize();
      Vec3 end = eyePos.add(direction.scale(maxDistance));

      if (focusTarget != null) {
         double targetDistance = eyePos.distanceTo(focusTarget.getBoundingBox().getCenter());
         distance = Mth.clamp(targetDistance, MIN_ANCHOR_DISTANCE, maxDistance);
      }

      BlockHitResult blockHit = level.clip(new ClipContext(eyePos, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, focusTarget));
      if (blockHit.getType() == HitResult.Type.BLOCK) {
         double blockDistance = eyePos.distanceTo(blockHit.getLocation());
         if (focusTarget == null || blockDistance < eyePos.distanceTo(focusTarget.getBoundingBox().getCenter())) {
            distance = Mth.clamp(blockDistance, MIN_ANCHOR_DISTANCE, maxDistance);
         }
      }

      Vec3 aimPoint = eyePos.add(direction.scale(distance));
      BlockPos surfacePos = BlockPos.containing(aimPoint.x, aimPoint.y, aimPoint.z);
      double surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, surfacePos.getX(), surfacePos.getZ());
      double centerY = surfaceY + Math.max(2.0, range * 0.48);
      return new Vec3(aimPoint.x, centerY, aimPoint.z);
   }
}
