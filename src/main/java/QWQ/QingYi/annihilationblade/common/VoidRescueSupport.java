package QWQ.QingYi.annihilationblade.common;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 维度自适应虚空救助工具，供「湮灭之刃」「无尽星空」等神刀共享使用。
 * <p>
 * <ul>
 *   <li>触发阈值读取 {@link Level#getMinBuildHeight()}，兼容主世界（-64）、下界/末地（0）及自定义维度；</li>
 *   <li>下界等带基岩顶盖维度优先在顶盖下方扫描安全无流体双格空腔，避免把玩家传送到基岩顶层 Y=320；</li>
 *   <li>主世界/末地等无顶盖维度安全送回当前维度最高建筑高度并拉起飞行态。</li>
 * </ul>
 */
public final class VoidRescueSupport {
   private VoidRescueSupport() {
   }

   /**
    * 若玩家坠入当前维度最低建筑高度以下，则立即传送至安全高度并清除坠落速度。
    *
    * @return 本次是否执行了虚空救助
    */
   public static boolean rescueIfInVoid(Player player) {
      if (player == null || player.level().isClientSide) {
         return false;
      }
      int minBuildY = player.level().getMinBuildHeight();
      if (player.getY() >= (double) minBuildY) {
         return false;
      }

      double targetY = resolveVoidRescueY(player);
      player.teleportTo(player.getX(), targetY, player.getZ());
      player.setDeltaMovement(0.0, 0.0, 0.0);
      player.fallDistance = 0.0F;
      if (!player.getAbilities().flying && (player.getAbilities().mayfly || player.isCreative() || player.isSpectator())) {
         player.getAbilities().flying = true;
         player.onUpdateAbilities();
      }
      return true;
   }

   /** 解析适合当前维度的安全救助 Y 坐标。 */
   public static double resolveVoidRescueY(Player player) {
      Level level = player.level();
      if (!level.dimensionType().hasCeiling()) {
         return level.getMaxBuildHeight();
      }

      int minY = level.getMinBuildHeight() + 8;
      // logicalHeight 为相对 minBuildHeight 的逻辑高度，累加 minBuildHeight 以兼容负底面自定义顶棚维度
      int maxSafeY = Math.max(minY, level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 12);
      int preferredY = Math.min(maxSafeY, Math.max(minY, level.getMinBuildHeight() + 64));
      BlockPos base = BlockPos.containing(player.getX(), preferredY, player.getZ());

      // 第一轮优先找无碰撞且无流体（避开岩浆海）的双格安全空腔
      for (int offset = 0; offset <= maxSafeY - minY; offset++) {
         int up = preferredY + offset;
         if (up <= maxSafeY && isDryAirHeadroom(level, base.atY(up))) {
            return up;
         }
         int down = preferredY - offset;
         if (offset > 0 && down >= minY && isDryAirHeadroom(level, base.atY(down))) {
            return down;
         }
      }

      // 第二轮退而求其次，仅要求无碰撞方块（避免极端全岩浆柱无处可落）
      for (int offset = 0; offset <= maxSafeY - minY; offset++) {
         int up = preferredY + offset;
         if (up <= maxSafeY && isAirHeadroom(level, base.atY(up))) {
            return up;
         }
         int down = preferredY - offset;
         if (offset > 0 && down >= minY && isAirHeadroom(level, base.atY(down))) {
            return down;
         }
      }
      return preferredY;
   }

   private static boolean isDryAirHeadroom(Level level, BlockPos feet) {
      return isAirHeadroom(level, feet)
         && level.getFluidState(feet).isEmpty()
         && level.getFluidState(feet.above()).isEmpty();
   }

   private static boolean isAirHeadroom(Level level, BlockPos feet) {
      return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
         && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
   }
}
