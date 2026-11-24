package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * SE「裂界·神威绞杀」服务端与客户端共用的时间轴、锚点与空间跃迁曲线。
 * <p>
 * 仅引用双端通用的 Minecraft 数学与实体坐标类型，禁止引用任何客户端渲染类。
 */
public final class WorldRiftPattern {
   // ───────── 独立模式（断空关闭）：单链神威跳跃 Route A 单节点节拍 ─────────
   /** 奇点涡眼急剧张开完成（tick）。 */
   public static final int OPEN_END_TICK = 3;
   /** 活体强扭达到临界点，服务端在此 tick 同步抹除目标、客户端切换 3D 螺旋躯壳暴拧（tick，相对初版加长 0.2s）。 */
   public static final int SNAP_TICK = 14;
   /** 单链向下一个最近目标发射空间裂线并跃迁的节拍（tick）。 */
   public static final int HOP_TICK = 16;
   /** 被拧断的 3D 残骸完全抽缩归零进入奇点黑洞（tick）。 */
   public static final int SUCK_END_TICK = 25;
   /** 奇点内爆归零与空间弹性回弹余波起点（tick）。 */
   public static final int SEAL_START_TICK = 24;
   /** 单节点自开眼至奇点归零与回弹淡出完毕总时长（tick）。 */
   public static final int NODE_LIFETIME = 32;

   // ───────── 断空联动模式：齐斩收尾接力群体神威尸骸扭杀节拍 ─────────
   /** 断空收刀（finishSequence）后等待 DankongFx 齐斩错位结束（SLIDE_END = 11）再接力引爆的延迟（tick）。 */
   public static final int LINKAGE_DELAY_TICKS = 11;
   /** 断空联动群体目标按距离由近到远错峰展开神威的最大跨度（tick）。 */
   public static final int LINKAGE_STAGGER_MAX = 5;
   /** 断空联动单个残骸奇点急剧张开并开始反向暴拧的时间点（相对该目标起爆 tick）。 */
   public static final int LINKAGE_SNAP_OFFSET = 2;
   /** 断空联动单个残骸完全被吸穿归零的时间点（相对该目标起爆 tick，相对初版加长 0.2s）。 */
   public static final int LINKAGE_SUCK_END = 21;
   /** 断空联动单个残骸自爆发至奇点归零回弹淡出完毕总时长（tick）。 */
   public static final int LINKAGE_NODE_LIFETIME = 30;

   private WorldRiftPattern() {
   }

   /** 神威奇点锚点：落在实体胸口偏上位置（高度 56%），更契合扭曲躯干与头颈的视觉重心。 */
   public static Vec3 vortexCenter(Entity entity) {
      return vortexCenter(entity.position(), entity.getBbHeight());
   }

   /** 由脚底坐标与身高推导神威奇点中心。 */
   public static Vec3 vortexCenter(Vec3 foot, double height) {
      return foot.add(0.0, Math.max(0.6, height) * 0.56, 0.0);
   }

   /** 独立模式单节点最大持续时长估算（服务端持续特效基础寿命）。 */
   public static int singleChainDuration(int maxChain) {
      int clamped = Math.max(1, Math.min(16, maxChain));
      return clamped * HOP_TICK + NODE_LIFETIME + 8;
   }

   /** 断空联动模式计算按距离分布的错峰起爆 tick（0 .. LINKAGE_STAGGER_MAX）。 */
   public static int linkageStaggerTick(double distance, double maxDistance) {
      double norm = Mth.clamp(distance / Math.max(4.0, maxDistance), 0.0, 1.0);
      return Mth.clamp((int)Math.round(norm * LINKAGE_STAGGER_MAX), 0, LINKAGE_STAGGER_MAX);
   }

   /** 独立模式活体扭曲阶段进度（0..1，平滑加速向临界点飙升）。 */
   public static float liveTwistProgress(float localAge) {
      float t = Mth.clamp(localAge / (float)SNAP_TICK, 0.0F, 1.0F);
      return t * t * (0.35F + 0.65F * t);
   }

   /** 拧断后 3D 躯壳沿螺旋急速抽缩进奇点黑洞的进度（0..1）。 */
   public static float implodeProgress(float sinceSnap, float duration) {
      float t = Mth.clamp(sinceSnap / Math.max(1.0F, duration), 0.0F, 1.0F);
      return t * t * (2.2F - 1.2F * t);
   }

   /** 单链跃迁折线弧采样点：在两点间加入轻微偏置弧度，增强空间裂线折跃感。 */
   public static Vec3 hopArcPoint(Vec3 from, Vec3 to, double t, long seed) {
      double clamped = Mth.clamp(t, 0.0, 1.0);
      Vec3 linear = from.lerp(to, clamped);
      Vec3 diff = to.subtract(from);
      double dist = diff.length();
      if (dist < 1.0E-4) {
         return linear;
      }
      Vec3 dir = diff.scale(1.0 / dist);
      Vec3 side = dir.cross(new Vec3(0.0, 1.0, 0.0));
      if (side.lengthSqr() < 1.0E-5) {
         side = new Vec3(1.0, 0.0, 0.0);
      } else {
         side = side.normalize();
      }
      Vec3 up = side.cross(dir).normalize();
      double sign = (seed & 1L) == 0L ? 1.0 : -1.0;
      double arch = Math.sin(Math.PI * clamped) * Math.min(2.2, dist * 0.16);
      return linear.add(side.scale(arch * 0.65 * sign)).add(up.scale(arch * 0.45));
   }
}
