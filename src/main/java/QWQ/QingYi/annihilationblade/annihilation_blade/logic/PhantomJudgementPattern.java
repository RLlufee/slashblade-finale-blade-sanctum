package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import net.minecraft.world.phys.Vec3;

/**
 * 湮灭之刃 SE「幻影审判·千刃星流」双端共享演出时间轴与几何运动数学库。
 * <p>
 * 服务端（{@code PhantomJudgement}）与客户端专属渲染（{@code PhantomJudgementFx}）严格共用同一份
 * 五幕关键帧节点，确保碎刃解离、三阶鱼群追猎、万刃星牢禁锢、漫天斩绝线同步处决与百刃归鞘严丝合缝：
 * <pre>
 *   0 ─  8  第一幕：千刃解离·初阵浮现（施法者身后/两侧弧扇母刃展开，星标逐个锁定）
 *   8 ─ 34  第二幕：三阶裂变·千刃追月（t=12 分裂寒星子刃，t=16 再裂紫罗兰追踪碎刃，有机曲线游鱼围猎）
 *  34 ─ 56  第三幕：万刃星牢·环绕蓄压（三倾角正反高速旋绕，t=48~56 骤缩指心收弦吸气）
 *  56 ─ 74  第四幕：千刃碎破·斩绝终判（双端同步终焉处决 + 漫天立体斩绝线 + 切屏 + 反相定格 + 百刃晶爆）
 *  74 ─ 96  第五幕：百刃归鞘·星流倒卷（慢动作悬停后螺旋倒流回施法者刀侧重聚收束）
 * </pre>
 */
public final class PhantomJudgementPattern {
   private static final double TAU = Math.PI * 2.0;

   /** 第一幕结束（母刃开始齐射离弦）tick。 */
   public static final int UNSHEATHE_END_TICK = 8;
   /** 第二幕节点 A：母刃晶裂分出寒星子刃（十字编队）tick。 */
   public static final int SUB_SPLIT_TICK = 12;
   /** 第二幕节点 B：子刃再分出紫罗兰追踪碎刃 tick。 */
   public static final int TRACKER_SPLIT_TICK = 16;
   /** 服务端第一段轻压制失重（母刃初抵，防目标跑散且不提前秒杀）tick。 */
   public static final int SUPPRESS_TICK = 22;
   /** 第三幕开始：全员汇入万刃星牢环绕，服务端施加星牢锚定禁锢 tick。 */
   public static final int CAGE_TICK = 34;
   /** 第三幕临界收弦：轨道骤缩、转速飙升、刃尖齐指核心蓄压吸气开始 tick。 */
   public static final int COMPRESS_START_TICK = 48;
   /** 第四幕高潮：双端同步齐爆漫天斩绝线并执行 TerminusLogic 终焉处决 tick。 */
   public static final int DETONATE_TICK = 56;
   /** 第五幕收尾：爆散碎刃减速悬停后集体倒流归鞘开始 tick。 */
   public static final int RECALL_START_TICK = 74;
   /** 齐爆后的客户端尾声时长（含斩绝余韵 + 慢动作悬停 + 百刃归鞘）。 */
   public static final int EPILOGUE_TICKS = 40;
   /** 演出总寿命（tick，约 4.8 秒）。 */
   public static final int TOTAL_LIFETIME = DETONATE_TICK + EPILOGUE_TICKS;

   private PhantomJudgementPattern() {
   }

   /** 施法者胸腹基准中心（脚底 + 体高 0.58）。 */
   public static Vec3 casterCenter(Vec3 feet, float height) {
      return feet.add(0.0, height * 0.58, 0.0);
   }

   /**
    * 第一幕母刃解离的初始悬停锚点：在施法者后侧与左右双翼呈立体半月弧扇展开，
    * 刻意让开第一人称正前方准星区域，同时保证第三人称如千刃羽翼展开。
    */
   public static Vec3 unsheatheAnchor(Vec3 casterCenter, Vec3 lookDir, int index, int count, float scale) {
      Vec3 forward = safeNormalize(lookDir, new Vec3(0.0, 0.0, 1.0));
      Vec3 flatForward = new Vec3(forward.x, 0.0, forward.z);
      if (flatForward.lengthSqr() < 1.0E-5) {
         flatForward = new Vec3(0.0, 0.0, 1.0);
      } else {
         flatForward = flatForward.normalize();
      }
      Vec3 right = new Vec3(-flatForward.z, 0.0, flatForward.x);
      Vec3 up = new Vec3(0.0, 1.0, 0.0);

      // 左右交替由内向外排布成对称双翼弧扇（避开正中 -0.25..+0.25 弧度）
      int side = (index & 1) == 0 ? 1 : -1;
      int rank = index / 2;
      int halfCount = Math.max(1, (count + 1) / 2);
      double frac = halfCount <= 1 ? 0.5 : (double)rank / (halfCount - 1);

      double lateral = side * (1.25 + frac * 2.05) * scale;
      double back = -(0.65 + frac * 1.15) * scale;
      double vertical = (0.35 + Math.sin(frac * Math.PI) * 1.35 + (index % 3 - 1) * 0.22) * scale;

      return casterCenter
         .add(right.scale(lateral))
         .add(flatForward.scale(back))
         .add(up.scale(vertical));
   }

   /**
    * 四方位十字编队偏置（母刃 → 子刃 / 子刃 → 追踪刃跟随飞行时使用），
    * 令编队在空中形成鲜明的发光鱼群箭头阵列。
    */
   public static Vec3 formationOffset(Vec3 forwardDir, int slot, double sideSpan, double backSpan) {
      Vec3 forward = safeNormalize(forwardDir, new Vec3(0.0, 0.0, 1.0));
      Vec3 refUp = Math.abs(forward.y) > 0.9 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
      Vec3 right = safeNormalize(forward.cross(refUp), new Vec3(1.0, 0.0, 0.0));
      Vec3 localUp = safeNormalize(right.cross(forward), new Vec3(0.0, 1.0, 0.0));

      int s = Math.floorMod(slot, 4);
      double rx;
      double uy;
      switch (s) {
         case 0 -> {
            rx = 0.0;
            uy = sideSpan;
         }
         case 1 -> {
            rx = sideSpan * 0.92;
            uy = 0.0;
         }
         case 2 -> {
            rx = -sideSpan * 0.92;
            uy = 0.0;
         }
         default -> {
            rx = 0.0;
            uy = -sideSpan;
         }
      }
      return right.scale(rx).add(localUp.scale(uy)).add(forward.scale(-backSpan));
   }

   /**
    * 四种有机曲线扰动偏移（独立实现：螺旋轨道、水平蛇行、椭圆摆动、双频波纹），
    * 赋予漫天碎刃鱼群般灵动的曲线绕袭轨迹，彻底告别死板直线飞弹。
    */
   public static Vec3 motionOffset(int mode, Vec3 baseDir, double t, double phase, double amp) {
      Vec3 dir = safeNormalize(baseDir, new Vec3(0.0, 0.0, 1.0));
      Vec3 refUp = Math.abs(dir.y) > 0.9 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
      Vec3 right = safeNormalize(dir.cross(refUp), new Vec3(1.0, 0.0, 0.0));
      Vec3 localUp = safeNormalize(right.cross(dir), new Vec3(0.0, 1.0, 0.0));

      double angle = t + phase;
      double cs = Math.cos(angle);
      double sn = Math.sin(angle);
      return switch (Math.floorMod(mode, 4)) {
         case 0 -> right.scale(cs * amp).add(localUp.scale(sn * amp));
         case 1 -> right.scale(sn * amp * 1.15);
         case 2 -> right.scale(cs * amp * 0.85).add(localUp.scale(sn * amp * 0.55));
         default -> {
            double nx = Math.sin(angle * 1.35) * 0.55 + Math.cos(angle * 0.72) * 0.45;
            double ny = Math.sin(angle * 0.92 + 1.25) * 0.55 + Math.cos(angle * 1.18) * 0.45;
            yield right.scale(nx * amp).add(localUp.scale(ny * amp * 0.72));
         }
      };
   }

   /**
    * 万刃星牢三倾角环绕轨道半径（随目标体型与视觉倍率自适应缩放）。
    */
   public static double cageRadius(float targetWidth, float targetHeight, int layer, float scale) {
      double base = Math.max(1.55, Math.max(targetWidth * 1.45, targetHeight * 0.82)) * scale;
      return base * switch (Math.floorMod(layer, 3)) {
         case 0 -> 0.92;
         case 1 -> 1.18;
         default -> 1.44;
      };
   }

   /**
    * 万刃星牢三倾角轨道位置（0 = 赤道微倾，1 = +42° 倾斜星轨，2 = -42° 倾斜星轨）。
    */
   public static Vec3 cagePosition(Vec3 targetCenter, double radius, double angle, int layer, double yawOffset) {
      double x0 = Math.cos(angle) * radius;
      double z0 = Math.sin(angle) * radius;
      double tilt = switch (Math.floorMod(layer, 3)) {
         case 0 -> 0.14;
         case 1 -> 0.74;
         default -> -0.74;
      };
      double ct = Math.cos(tilt);
      double st = Math.sin(tilt);
      double y1 = z0 * st;
      double z1 = z0 * ct;
      double cy = Math.cos(yawOffset);
      double sy = Math.sin(yawOffset);
      double x2 = x0 * cy - z1 * sy;
      double z2 = x0 * sy + z1 * cy;
      return targetCenter.add(x2, y1, z2);
   }

   /**
    * 万刃星牢切线方向（用于碎刃环绕期的平滑机头朝向）。
    */
   public static Vec3 cageTangent(double angle, double angularSign, int layer, double yawOffset) {
      double dx0 = -Math.sin(angle) * angularSign;
      double dz0 = Math.cos(angle) * angularSign;
      double tilt = switch (Math.floorMod(layer, 3)) {
         case 0 -> 0.14;
         case 1 -> 0.74;
         default -> -0.74;
      };
      double ct = Math.cos(tilt);
      double st = Math.sin(tilt);
      double dy1 = dz0 * st;
      double dz1 = dz0 * ct;
      double cy = Math.cos(yawOffset);
      double sy = Math.sin(yawOffset);
      double dx2 = dx0 * cy - dz1 * sy;
      double dz2 = dx0 * sy + dz1 * cy;
      return safeNormalize(new Vec3(dx2, dy1, dz2), new Vec3(1.0, 0.0, 0.0));
   }

   /**
    * 归鞘终点（施法者身侧右下方刀锋收束锚点，第一人称优雅掠过右下方且绝不挡住准星）。
    */
   public static Vec3 recallAnchor(Vec3 casterFeet, float height, Vec3 lookDir, float scale) {
      Vec3 center = casterCenter(casterFeet, height);
      Vec3 forward = safeNormalize(lookDir, new Vec3(0.0, 0.0, 1.0));
      Vec3 flatForward = new Vec3(forward.x, 0.0, forward.z);
      if (flatForward.lengthSqr() < 1.0E-5) {
         flatForward = new Vec3(0.0, 0.0, 1.0);
      } else {
         flatForward = flatForward.normalize();
      }
      Vec3 right = new Vec3(-flatForward.z, 0.0, flatForward.x);
      return center.add(right.scale(0.68 * scale)).add(flatForward.scale(0.55 * scale)).add(0.0, -0.22 * scale, 0.0);
   }

   public static double tau() {
      return TAU;
   }

   private static Vec3 safeNormalize(Vec3 v, Vec3 fallback) {
      return v.lengthSqr() < 1.0E-6 ? fallback : v.normalize();
   }
}
