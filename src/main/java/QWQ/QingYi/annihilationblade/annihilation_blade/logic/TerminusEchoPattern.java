package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 湮灭之刃 SE「归墟回响·因果反噬」双端共享时间轴与空间几何数学。
 * <p>
 * 服务端定身/处决节拍与客户端偏折镜、因果逆溯轨、玩家虚影与反斩演出全部以此类为准，确保毫秒级同步。
 */
public final class TerminusEchoPattern {
   /** 因果溯源轨自偏折镜中心射出的起始 tick。 */
   public static final int TRACE_START_TICK = 2;
   /** 攻击者身侧裂开虚空缝并显现玩家黑金剑影的 tick。 */
   public static final int GHOST_APPEAR_TICK = 6;
   /** 溯源轨钉死攻击者、绽开落款锁印并触发服务端短时定身的 tick。 */
   public static final int LOCK_TICK = 7;
   /** 服务端先发 ECHO_EXECUTE 同步实时坐标与活体抓拍，再执行终焉静默处决的 tick。 */
   public static final int EXECUTE_TICK = 14;
   /** 偏折镜脆裂、溯源轨倒卷收回与虚影开始烧蚀的 tick。 */
   public static final int SHATTER_START_TICK = 22;
   /** 完整因果反噬客户端总寿命（tick，约 2.2 秒）。 */
   public static final int TOTAL_LIFETIME = 44;
   /** 仅偏折镜波纹（无合法攻击者或超距拦截）客户端寿命（tick）。 */
   public static final int DEFLECT_ONLY_LIFETIME = 16;
   /** 服务端持续事件占位时长（正常会在 EXECUTE_TICK 处决完主动 stop，此值为兜底上限）。 */
   public static final int SERVER_SUSTAIN_DURATION = EXECUTE_TICK + 6;
   /** 客户端提前中止（目标在 t < EXECUTE_TICK 提前死亡或撤场）后的平滑淡出时长。 */
   public static final int ABORT_FADE_TICKS = 8;

   private TerminusEchoPattern() {
   }

   public static Vec3 safeNormalize(Vec3 vector, Vec3 fallback) {
      if (vector == null || vector.lengthSqr() < 1.0E-6) {
         return fallback;
      }
      return vector.normalize();
   }

   /**
    * 计算归墟偏折镜在世界空间中的锚点中心：位于防御者胸口沿攻击来向推进 1.22 格处，
    * 避免贴近第一人称摄像机近裁剪面，同时在第三人称/旁观视角下呈现清晰的护身屏障感。
    */
   public static Vec3 resolveMirrorCenter(Vec3 defenderCenter, Vec3 incomingDir) {
      Vec3 dir = safeNormalize(incomingDir, new Vec3(0.0, 0.0, 1.0));
      return defenderCenter.add(dir.scale(1.22));
   }

   /**
    * 计算玩家黑金虚影的胸口落点：位于目标沿溯源方向的斜后方，既贴合「瞬移至敌后居合反斩」的意象，
    * 又带轻微侧偏保证从防御者正面望去时虚影不被目标躯体完全挡住。
    */
   public static Vec3 resolveGhostChest(Vec3 targetCenter, Vec3 traceDir, float targetWidth, long seed) {
      Vec3 horiz = new Vec3(traceDir.x, 0.0, traceDir.z);
      horiz = safeNormalize(horiz, new Vec3(0.0, 0.0, 1.0));
      Vec3 side = new Vec3(-horiz.z, 0.0, horiz.x);
      double sideSign = (seed & 1L) == 0L ? 1.0 : -1.0;
      double backDist = Math.max(1.55, targetWidth * 0.65 + 1.15);
      double sideOffset = Math.max(0.42, targetWidth * 0.35 + 0.25) * sideSign;
      return targetCenter.add(horiz.scale(backDist)).add(side.scale(sideOffset));
   }

   /**
    * 计算二次贝塞尔因果溯源轨的控制点：在中点上方按距离平滑抬拱，确保远距或地形遮挡时依然可视。
    */
   public static Vec3 arcControlPoint(Vec3 start, Vec3 end, long seed) {
      Vec3 mid = start.lerp(end, 0.5);
      double dist = start.distanceTo(end);
      double lift = Mth.clamp(dist * 0.16 + 0.55, 0.55, 4.8);
      Vec3 delta = end.subtract(start);
      Vec3 side = new Vec3(-delta.z, 0.0, delta.x);
      side = safeNormalize(side, new Vec3(1.0, 0.0, 0.0));
      double sideSign = ((seed >> 2) & 1L) == 0L ? 1.0 : -1.0;
      double lateral = Math.min(2.2, dist * 0.06) * sideSign;
      return mid.add(0.0, lift, 0.0).add(side.scale(lateral));
   }

   /** 二次贝塞尔曲线采样点（t in [0, 1]）。 */
   public static Vec3 bezierPoint(Vec3 p0, Vec3 p1, Vec3 p2, float t) {
      float u = Mth.clamp(t, 0.0F, 1.0F);
      float inv = 1.0F - u;
      return new Vec3(
         inv * inv * p0.x + 2.0F * inv * u * p1.x + u * u * p2.x,
         inv * inv * p0.y + 2.0F * inv * u * p1.y + u * u * p2.y,
         inv * inv * p0.z + 2.0F * inv * u * p1.z + u * u * p2.z
      );
   }

   /** 二次贝塞尔曲线切线（已归一化）。 */
   public static Vec3 bezierTangent(Vec3 p0, Vec3 p1, Vec3 p2, float t) {
      float u = Mth.clamp(t, 0.0F, 1.0F);
      double dx = 2.0 * (1.0 - u) * (p1.x - p0.x) + 2.0 * u * (p2.x - p1.x);
      double dy = 2.0 * (1.0 - u) * (p1.y - p0.y) + 2.0 * u * (p2.y - p1.y);
      double dz = 2.0 * (1.0 - u) * (p1.z - p0.z) + 2.0 * u * (p2.z - p1.z);
      return safeNormalize(new Vec3(dx, dy, dz), safeNormalize(p2.subtract(p0), new Vec3(0.0, 0.0, 1.0)));
   }

   /** 因果溯源轨前端推进进度（t = TRACE_START_TICK .. LOCK_TICK -> 0 .. 1）。 */
   public static float traceProgress(float age) {
      if (age <= TRACE_START_TICK) {
         return 0.0F;
      }
      float k = Mth.clamp((age - TRACE_START_TICK) / (float)(LOCK_TICK - TRACE_START_TICK), 0.0F, 1.0F);
      float inv = 1.0F - k;
      return 1.0F - inv * inv * inv;
   }

   /** 因果溯源轨尾段倒卷收回进度（t = SHATTER_START_TICK .. SHATTER_START_TICK + 11 -> 0 .. 1）。 */
   public static float traceRetract(float age) {
      if (age <= SHATTER_START_TICK) {
         return 0.0F;
      }
      float k = Mth.clamp((age - SHATTER_START_TICK) / 11.0F, 0.0F, 1.0F);
      return k * k;
   }
}
