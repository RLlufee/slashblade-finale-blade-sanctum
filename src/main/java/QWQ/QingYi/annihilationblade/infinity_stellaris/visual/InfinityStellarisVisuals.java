package QWQ.QingYi.annihilationblade.infinity_stellaris.visual;

import QWQ.QingYi.annihilationblade.common.SpecialEffectSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 无尽星空服务端轻量过渡视觉占位工具（Step 0.5 强化版）。
 * <p>
 * 已彻底废除旧版每目标 330 个粒子网络包的 5 tick 锁链与数万级粒子海；
 * 在 Step 3 ~ Step 7 正式接入「星穹渲染引擎（Stellaris Engine）」客户端着色器前，
 * 本类为全部 4 项 SE 的每个阶段（束缚/预警/持续打击/领域边界/应力/坍缩）提供严格限流的清晰可视化反馈，
 * 杜绝“空气定身、空气掉血、开域无感”。
 */
public final class InfinityStellarisVisuals {
   private InfinityStellarisVisuals() {
   }

   /** 兼容旧签名：不再调度 5 tick × 66 次粒子锁链，仅在目标中心生成极少量占位星芒。 */
   public static void spawnDamageChain(ServerLevel level, Player player, LivingEntity target) {
      if (level == null || target == null) {
         return;
      }
      spawnEntropyMarkFeedback(level, target, 1, 10);
   }

   /** 兼容旧签名：仅在终点位置输出极少量轻量粒子。 */
   public static void spawnDamageChain(ServerLevel level, Vec3 start, Vec3 end) {
      if (level == null || end == null) {
         return;
      }
      level.sendParticles(ParticleTypes.END_ROD, end.x, end.y, end.z, 4, 0.25, 0.25, 0.25, 0.02);
   }

   /** SE1 熵增叠层轻量占位反馈（≤ 6 粒）。 */
   public static void spawnEntropyMarkFeedback(ServerLevel level, LivingEntity target, int marks, int maxMarks) {
      if (level == null || target == null) {
         return;
      }
      Vec3 center = SpecialEffectSupport.centerOf(target);
      double spread = Math.max(0.35, target.getBbWidth() * 0.45);
      int count = Math.min(6, 2 + (marks * 4) / Math.max(1, maxMarks));
      level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, count, spread, spread, spread, 0.02);
   }

   /** SE1 热寂归零终结轻量占位反馈（≤ 8 粒）。 */
   public static void spawnHeatDeathPlaceholder(ServerLevel level, LivingEntity target) {
      if (level == null || target == null) {
         return;
      }
      Vec3 center = SpecialEffectSupport.centerOf(target);
      double spread = Math.max(0.45, target.getBbWidth() * 0.65);
      level.sendParticles(ParticleTypes.FLASH, center.x, center.y, center.z, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 6, spread, spread, spread, 0.08);
   }

   /**
    * SE2 宇宙弦切首拍束缚占位（严格限流：整次 ≤ 24 次发包）：
    * 沿施法者到每个锁定目标拉出 2~3 个节点星芒并在目标中心落锚，消除 9 tick 束缚期的“空气卡顿感”。
    */
   public static void spawnStringBindPlaceholder(ServerLevel level, Vec3 origin, List<LivingEntity> targets) {
      if (level == null || origin == null || targets == null || targets.isEmpty()) {
         return;
      }
      int visualTargets = Math.min(targets.size(), 8);
      int knotsPerString = visualTargets <= 4 ? 3 : 2;
      for (int i = 0; i < visualTargets; i++) {
         LivingEntity target = targets.get(i);
         Vec3 end = SpecialEffectSupport.centerOf(target);
         for (int k = 1; k <= knotsPerString; k++) {
            double t = k / (double) (knotsPerString + 1);
            Vec3 p = origin.lerp(end, t);
            level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
         }
         level.sendParticles(ParticleTypes.ELECTRIC_SPARK, end.x, end.y, end.z, 3, 0.25, 0.35, 0.25, 0.01);
      }
   }

   /** SE2 宇宙弦切次拍拨弦崩断轻量占位反馈（≤ 6 粒）。 */
   public static void spawnStringCutPlaceholder(ServerLevel level, LivingEntity target) {
      if (level == null || target == null) {
         return;
      }
      Vec3 center = SpecialEffectSupport.centerOf(target);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y, center.z, 4, 0.35, 0.45, 0.35, 0.08);
      level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 2, 0.3, 0.3, 0.3, 0.05);
   }

   /**
    * SE3 伽马霆爆天基轨道炮预警占位（严格限流 ≤ 10 次发包）：
    * 绘制 4 点收缩地表框与 1 道垂向细标线，让 12 tick 预警清晰可辨。
    */
   public static void spawnGammaWarningPlaceholder(ServerLevel level, Vec3 groundPos, double ceilingY, double radius, boolean secondBeat) {
      if (level == null || groundPos == null) {
         return;
      }
      double ringR = secondBeat ? radius * 0.55 : radius * 0.92;
      double baseAngle = secondBeat ? Math.PI * 0.25 : 0.0;
      for (int i = 0; i < 4; i++) {
         double a = baseAngle + i * Math.PI * 0.5;
         level.sendParticles(
            ParticleTypes.ELECTRIC_SPARK,
            groundPos.x + Math.cos(a) * ringR,
            groundPos.y + 0.35,
            groundPos.z + Math.sin(a) * ringR,
            2, 0.12, 0.15, 0.12, 0.01
         );
      }
      double columnHeight = Math.min(28.0, Math.max(6.0, ceilingY - groundPos.y));
      for (int k = 1; k <= 4; k++) {
         double py = groundPos.y + columnHeight * (k / 4.0);
         level.sendParticles(ParticleTypes.END_ROD, groundPos.x, py, groundPos.z, 1, 0.04, 0.15, 0.04, 0.0);
      }
   }

   /**
    * SE3 伽马霆爆贯地光柱单脉冲占位（严格限流 ≤ 8 次发包）：
    * 每 3 tick 沿光柱轴心与落点边缘补一次脉动星芒，消除 18 tick 持续打击后半段的“空气掉血感”。
    */
   public static void spawnGammaBeamPulsePlaceholder(
      ServerLevel level, Vec3 groundPos, double ceilingY, double radius, boolean firstPulse
   ) {
      if (level == null || groundPos == null) {
         return;
      }
      if (firstPulse) {
         level.sendParticles(ParticleTypes.FLASH, groundPos.x, groundPos.y + 1.0, groundPos.z, 1, 0.0, 0.0, 0.0, 0.0);
      }
      double columnHeight = Math.min(30.0, Math.max(8.0, ceilingY - groundPos.y));
      int segments = firstPulse ? 5 : 3;
      for (int k = 0; k < segments; k++) {
         double py = groundPos.y + 0.6 + columnHeight * (k / (double) Math.max(1, segments - 1));
         level.sendParticles(ParticleTypes.END_ROD, groundPos.x, py, groundPos.z, 2, 0.25, 0.4, 0.25, 0.02);
      }
      level.sendParticles(
         ParticleTypes.ELECTRIC_SPARK,
         groundPos.x, groundPos.y + 0.45, groundPos.z,
         firstPulse ? 6 : 3, radius * 0.45, 0.3, radius * 0.45, 0.05
      );
   }

   /** 保留旧占位方法签名以防外部调用。 */
   public static void spawnGammaStrikePlaceholder(ServerLevel level, Vec3 pos, boolean impact) {
      if (impact) {
         spawnGammaBeamPulsePlaceholder(level, pos, pos.y + 24.0, 5.5, true);
      } else {
         spawnGammaWarningPlaceholder(level, pos, pos.y + 24.0, 5.5, false);
      }
   }

   /**
    * SE4 曲率撕裂领域常驻轻量化占位（每 10 tick 一次，总发包 ≤ 18）：
    * 旋转描出 12 个极清透的边界星盘刻度点，并为最多 6 名被控目标标示随应力层数增强的压制星芒。
    */
   public static void spawnCurvatureDomainPulse(
      ServerLevel level,
      Vec3 center,
      double radius,
      long gameTime,
      List<LivingEntity> targets,
      Map<UUID, Integer> strainMarks
   ) {
      if (level == null || center == null) {
         return;
      }
      double spin = (gameTime * 0.06) % (Math.PI * 2.0);
      int rimPoints = 12;
      for (int i = 0; i < rimPoints; i++) {
         double a = spin + i * (Math.PI * 2.0 / rimPoints);
         level.sendParticles(
            ParticleTypes.END_ROD,
            center.x + Math.cos(a) * radius,
            center.y + 0.35,
            center.z + Math.sin(a) * radius,
            1, 0.02, 0.05, 0.02, 0.0
         );
      }
      if (targets == null || targets.isEmpty()) {
         return;
      }
      int shown = Math.min(targets.size(), 6);
      for (int i = 0; i < shown; i++) {
         LivingEntity target = targets.get(i);
         int marks = strainMarks == null ? 0 : strainMarks.getOrDefault(target.getUUID(), 0);
         Vec3 pos = SpecialEffectSupport.centerOf(target);
         int count = Math.min(4, 1 + marks);
         level.sendParticles(
            marks >= 3 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.END_ROD,
            pos.x, pos.y + 0.15, pos.z, count, 0.35, 0.2, 0.35, 0.01
         );
      }
   }

   /** SE4 曲率撕裂度规坍缩轻量占位反馈（≤ 6 粒）。 */
   public static void spawnCurvatureCollapsePlaceholder(ServerLevel level, LivingEntity target) {
      if (level == null || target == null) {
         return;
      }
      Vec3 center = SpecialEffectSupport.centerOf(target);
      level.sendParticles(ParticleTypes.SONIC_BOOM, center.x, center.y + 0.25, center.z, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 5, 0.45, 0.45, 0.45, 0.06);
   }
}
