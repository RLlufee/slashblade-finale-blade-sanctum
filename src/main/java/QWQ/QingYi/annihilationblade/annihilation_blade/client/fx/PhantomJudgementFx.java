package QWQ.QingYi.annihilationblade.annihilation_blade.client.fx;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.PhantomJudgementPattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.core.FxInstance;
import QWQ.QingYi.annihilationblade.render.client.core.FxPass;
import QWQ.QingYi.annihilationblade.render.client.core.FxScreenState;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry.Style;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxShaders;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 湮灭之刃 SE「幻影审判·千刃星流」准 SA 导演级专属渲染（持续特效，零原生视觉实体）。
 * <p>
 * 借鉴魔刀千刃「散刃裂变 · 游鱼编队 · 环绕星牢 · 斩绝线晶爆 · 百刃归鞘」意象，基于终焉渲染器独立构建五幕演出：
 * <pre>
 *  第一幕（ 0 ─  8t）千刃解离：施法者身后/两侧弧扇浮现黑曜晶核母刃，星标与缩圈逐个锁定目标
 *  第二幕（ 8 ─ 34t）千刃追月：母刃沿 4 种有机曲线齐射，t=12 分裂寒星冰青子刃十字编队，t=16 再裂紫罗兰追踪碎刃，
 *                              逐级脱队如发光游鱼群般拖曳丝滑长尾立体包抄目标
 *  第三幕（34 ─ 56t）万刃星牢：碎刃汇入目标周身三倾角正反高速旋绕结笼，t=48~56 骤缩 45%、刃尖齐指核心蓄压吸气
 *  第四幕（56 ─ 74t）斩绝终判：双端同步引爆漫天立体斩绝线网（Body 暗腔 + VOID_SLIT 锯齿刃 + 灼热白芯）+ 交叉 3D 刀弧
 *                              + fx_slice_scene 空间碎切 + 1.8t 反相定格帧 + 百刃晶爆弹射
 *  第五幕（74 ─ 96t）百刃归鞘：爆散碎刃强阻尼慢动作微旋后集体调头，沿螺旋星河弧线倒卷回施法者刀侧收束重聚
 * </pre>
 *
 * @see PhantomJudgementPattern 双端共享关键帧与运动学公式
 */
@OnlyIn(Dist.CLIENT)
public final class PhantomJudgementFx extends FxInstance {
   // ───────────────────────────── 时间轴常量（双端共用） ─────────────────────────────
   private static final int UNSHEATHE_END = PhantomJudgementPattern.UNSHEATHE_END_TICK;
   private static final int SUB_SPLIT = PhantomJudgementPattern.SUB_SPLIT_TICK;
   private static final int TRACKER_SPLIT = PhantomJudgementPattern.TRACKER_SPLIT_TICK;
   private static final int CAGE_START = PhantomJudgementPattern.CAGE_TICK;
   private static final int COMPRESS_START = PhantomJudgementPattern.COMPRESS_START_TICK;
   private static final int DEFAULT_DETONATE = PhantomJudgementPattern.DETONATE_TICK;
   private static final int EPILOGUE_TICKS = PhantomJudgementPattern.EPILOGUE_TICKS;
   private static final int RECALL_OFFSET_FROM_DETONATE = PhantomJudgementPattern.RECALL_START_TICK - PhantomJudgementPattern.DETONATE_TICK;

   private static final int MAX_SLICES = 8;
   private static final int MAX_TOTAL_SLASH_STRINGS = 120;
   private static final int MAX_SPARKS = 140;
   private static final int MAX_LOCK_SOUNDS = 6;

   // ───────────────────────────── 色彩体系（黑曜暗底衬托三阶异色流，白天绝不糊白） ─────────────────────────────
   private static final float[] DEEP_OBSIDIAN = {0.07F, 0.018F, 0.16F};
   private static final float[] DEEP_VOID = {0.18F, 0.04F, 0.38F};
   private static final float[] VOID_PURPLE = {0.66F, 0.28F, 1.0F};
   private static final float[] ICE_CYAN = {0.32F, 0.88F, 1.0F};
   private static final float[] VIOLET = {0.82F, 0.44F, 1.0F};
   private static final float[] MAGENTA = {0.96F, 0.34F, 0.86F};
   private static final float[] GOLD = {1.0F, 0.78F, 0.34F};
   private static final float[] WHITE = {1.0F, 0.98F, 1.0F};

   private final int casterId;
   private final float scale;
   private final float range;
   private final float seedFloat;
   private final int masterCount;
   private final int subPerMaster;
   private final int trackerPerSub;
   private final int maxTotalShards;
   private final int stringsPerTarget;
   private final float[] masterTint;
   private final Vec3 initialLook;
   private final RandomSource random;

   private Vec3 casterAnchor;
   private Vec3 casterLook;
   private Vec3 focalAnchor;

   private final List<TargetAnchor> targets = new ArrayList<>();
   private final List<ShardUnit> shards = new ArrayList<>();
   private final List<SplitPulse> splitPulses = new ArrayList<>();
   private final List<SlashString> slashStrings = new ArrayList<>();
   private final List<BurstArc> burstArcs = new ArrayList<>();
   private final List<ResonanceChord> resonanceChords = new ArrayList<>();
   private final List<Spark> sparks = new ArrayList<>();

   private int detonateAt = DEFAULT_DETONATE;
   private boolean detonated = false;
   private int cancelledAt = -1;
   private int simulatedTicks = 0;

   public PhantomJudgementFx(ClientLevel level, FxEventPacket packet) {
      super(PhantomJudgementPattern.TOTAL_LIFETIME);
      this.casterId = packet.casterId();
      this.scale = clamp(packet.scale(), 0.45F, 2.2F);
      this.range = Math.max(8.0F, packet.radius());
      int packed = packet.intParam();
      this.masterCount = clampInt(AnnihilationFxEvents.phantomMasterCount(packed), 4, 16);
      this.subPerMaster = clampInt(AnnihilationFxEvents.phantomSubPerMaster(packed), 0, 6);
      this.trackerPerSub = clampInt(AnnihilationFxEvents.phantomTrackerPerSub(packed), 0, 4);
      this.maxTotalShards = clampInt(AnnihilationFxEvents.phantomMaxTotalShards(packed), 24, 200);
      this.stringsPerTarget = clampInt(AnnihilationFxEvents.phantomStringsPerTarget(packed), 4, 16);

      long rawSeed = packet.seed();
      int rgb = (int)(rawSeed & 0xFFFFFFL);
      long rngSeed = (rawSeed >>> 32) ^ 0x517CC1B727220A95L ^ ((long)this.casterId << 17);
      this.random = RandomSource.create(rngSeed);
      this.seedFloat = (float)((rngSeed & 0xFFFFL) / 65535.0 * 97.0);
      this.masterTint = decodeMasterColor(rgb);

      this.casterAnchor = packet.origin();
      this.initialLook = packet.direction().lengthSqr() > 1.0E-5 ? packet.direction().normalize() : new Vec3(0.0, 0.0, 1.0);
      this.casterLook = this.initialLook;
      this.focalAnchor = this.casterAnchor;

      this.initTargets(level, packet.entityIds());
      this.buildSwarm();

      // 若中途进场收到的是 PHANTOM_DETONATE 快照，立刻转入齐爆态避免空白帧
      if (AnnihilationFxEvents.phantomKind(packed) == AnnihilationFxEvents.PHANTOM_DETONATE) {
         this.triggerDetonate(level, 0, packet.entityIds(), false);
      }
   }

   /** 刀色解码：结合虚空紫基底提纯，确保母刃在 HDR 与暗核下都高饱和有辨识度。 */
   private static float[] decodeMasterColor(int rgb) {
      float r = ((rgb >> 16) & 0xFF) / 255.0F;
      float g = ((rgb >> 8) & 0xFF) / 255.0F;
      float b = (rgb & 0xFF) / 255.0F;
      if (r + g + b < 0.35F) {
         return VOID_PURPLE.clone();
      }
      return new float[]{
         FxMath.lerp(r, VOID_PURPLE[0], 0.28F),
         FxMath.lerp(g, VOID_PURPLE[1], 0.28F),
         FxMath.lerp(b, VOID_PURPLE[2], 0.28F)
      };
   }

   private void initTargets(ClientLevel level, int[] entityIds) {
      if (entityIds != null) {
         int n = entityIds.length;
         for (int i = 0; i < n; i++) {
            Entity e = level.getEntity(entityIds[i]);
            if (e == null) {
               continue;
            }
            float lockAt = 0.8F + (n <= 1 ? 0.0F : 4.8F * i / (n - 1));
            this.targets.add(new TargetAnchor(e.getId(), e.position(), Math.max(0.6F, e.getBbWidth()), Math.max(0.8F, e.getBbHeight()), lockAt, this.random.nextDouble() * FxMath.TAU));
         }
      }
      if (this.targets.isEmpty()) {
         Vec3 fallback = this.casterAnchor.add(this.initialLook.scale(8.0));
         this.targets.add(new TargetAnchor(-1, fallback.add(0.0, -0.9, 0.0), 0.9F, 1.8F, 1.0F, 0.0));
      }
      this.recomputeFocal();
   }

   /**
    * 构建三阶碎刃谱系（母刃 Role 0 → 寒星子刃 Role 1 → 紫罗兰追踪碎刃 Role 2）。
    * 单 Boss 时全员分三倾角层立体汇聚；群怪（至多 24 目标）时子刃/追踪刃自动补位覆盖每一个目标。
    */
   private void buildSwarm() {
      int tCount = this.targets.size();
      int[] assignedCount = new int[tCount];

      // 1. 生成母刃（Role 0）
      for (int m = 0; m < this.masterCount && this.shards.size() < this.maxTotalShards; m++) {
         int targetIdx = pickTarget(assignedCount, m % tCount);
         assignedCount[targetIdx]++;
         Vec3 unsheathe = PhantomJudgementPattern.unsheatheAnchor(this.casterAnchor, this.initialLook, m, this.masterCount, this.scale);
         int launchTick = UNSHEATHE_END + (m % 3);
         int arriveTick = 22 + (m % 5);
         ShardUnit master = new ShardUnit(
            this.shards.size(), 0, -1, m, targetIdx,
            this.casterAnchor, unsheathe,
            launchTick, launchTick, arriveTick,
            m % 4, 0,
            this.random.nextDouble() * FxMath.TAU,
            (m % 2 == 0 ? 1.0 : -1.0) * (0.28 + this.random.nextDouble() * 0.08),
            0.88F * this.scale, 0.44F * this.scale, 0.21F * this.scale, 0.11F * this.scale,
            22,
            m % 3 == 0 ? GOLD : this.masterTint,
            this.random
         );
         master.active = true;
         this.shards.add(master);
      }

      int actualMasters = this.shards.size();

      // 2. 生成寒星子刃（Role 1，t = SUB_SPLIT 从母刃四方位十字分裂）
      List<Integer> subIndices = new ArrayList<>();
      for (int m = 0; m < actualMasters; m++) {
         ShardUnit parent = this.shards.get(m);
         for (int s = 0; s < this.subPerMaster && this.shards.size() < this.maxTotalShards; s++) {
            int targetIdx = pickUnderCoveredOrParent(assignedCount, parent.targetIdx);
            assignedCount[targetIdx]++;
            int detachTick = SUB_SPLIT + 4 + (s % 4);
            int arriveTick = 26 + ((m + s) % 6);
            ShardUnit sub = new ShardUnit(
               this.shards.size(), 1, m, s, targetIdx,
               parent.unsheathePos, parent.unsheathePos,
               SUB_SPLIT, detachTick, arriveTick,
               (m + s + 1) % 4, 1 + (s & 1),
               this.random.nextDouble() * FxMath.TAU,
               ((m + s) % 2 == 0 ? -1.0 : 1.0) * (0.32 + this.random.nextDouble() * 0.10),
               0.58F * this.scale, 0.30F * this.scale, 0.145F * this.scale, 0.078F * this.scale,
               16,
               ICE_CYAN,
               this.random
            );
            subIndices.add(sub.id);
            this.shards.add(sub);
         }
      }

      // 3. 生成紫罗兰/品红追踪碎刃（Role 2，t = TRACKER_SPLIT 从子刃二次分裂）
      for (int subIdx : subIndices) {
         ShardUnit parent = this.shards.get(subIdx);
         for (int k = 0; k < this.trackerPerSub && this.shards.size() < this.maxTotalShards; k++) {
            int targetIdx = pickUnderCoveredOrParent(assignedCount, parent.targetIdx);
            assignedCount[targetIdx]++;
            int detachTick = TRACKER_SPLIT + 3 + ((subIdx + k) % 4);
            int arriveTick = 29 + ((subIdx + k) % 6);
            float[] tint = ((subIdx + k) % 5 == 0) ? MAGENTA : VIOLET;
            ShardUnit tracker = new ShardUnit(
               this.shards.size(), 2, subIdx, (k * 2 + (subIdx & 1)) % 4, targetIdx,
               parent.unsheathePos, parent.unsheathePos,
               TRACKER_SPLIT, detachTick, arriveTick,
               (subIdx + k) % 4, (subIdx + k) % 3,
               this.random.nextDouble() * FxMath.TAU,
               ((subIdx + k) % 2 == 0 ? 1.0 : -1.0) * (0.38 + this.random.nextDouble() * 0.12),
               0.40F * this.scale, 0.21F * this.scale, 0.105F * this.scale, 0.056F * this.scale,
               12,
               tint,
               this.random
            );
            this.shards.add(tracker);
         }
      }
   }

   private static int pickTarget(int[] assigned, int preferred) {
      for (int i = 0; i < assigned.length; i++) {
         if (assigned[i] == 0) {
            return i;
         }
      }
      return preferred;
   }

   private static int pickUnderCoveredOrParent(int[] assigned, int parentTarget) {
      int best = -1;
      int minCount = Integer.MAX_VALUE;
      for (int i = 0; i < assigned.length; i++) {
         if (assigned[i] < minCount) {
            minCount = assigned[i];
            best = i;
         }
      }
      // 只要存在分配不足（<2 枚）的目标就优先分流覆盖，否则跟随父刃集中轰击
      return (best >= 0 && minCount < 2) ? best : parentTarget;
   }

   // ───────────────────────────── 持续特效钩子（含中途进场快进） ─────────────────────────────

   @Override
   protected void onSustainedStart(int duration) {
      this.lifetime = Math.max(this.lifetime, PhantomJudgementPattern.TOTAL_LIFETIME);
      // 若其他玩家中途进入视野（bindSustained 已设置 age = elapsed > 0），静默快进物理模拟至当前帧
      if (this.age > 0 && this.simulatedTicks < this.age) {
         ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
         if (level != null) {
            int fastForwardTo = Math.min(this.age, this.detonateAt - 1);
            while (this.simulatedTicks < fastForwardTo) {
               this.stepSimulation(level, this.simulatedTicks, false);
               this.simulatedTicks++;
            }
         }
      }
   }

   @Override
   public void onSustainedUpdate(ClientLevel level, FxEventPacket event) {
      if (AnnihilationFxEvents.phantomKind(event.intParam()) == AnnihilationFxEvents.PHANTOM_DETONATE) {
         this.triggerDetonate(level, this.age, event.entityIds(), true);
      }
   }

   @Override
   public void onSustainedStop() {
      if (!this.detonated && this.cancelledAt < 0) {
         // 尚未引爆即被服务端中止（关闭配置/换维度/施法者死亡）：8 tick 内平滑溶解归隐
         this.cancelledAt = this.age;
         this.lifetime = Math.min(this.lifetime, this.age + 8);
      }
   }

   private void triggerDetonate(ClientLevel level, int tickNow, int[] finalTargetIds, boolean playAudio) {
      if (this.detonated) {
         return;
      }
      this.detonated = true;
      this.detonateAt = tickNow;
      this.lifetime = tickNow + EPILOGUE_TICKS;

      // 在目标被服务端移除前的这一瞬间，刷新所有存活目标的最终精确锚点
      if (finalTargetIds != null && finalTargetIds.length > 0) {
         for (int id : finalTargetIds) {
            Entity e = level.getEntity(id);
            if (e == null) {
               continue;
            }
            boolean matched = false;
            for (TargetAnchor t : this.targets) {
               if (t.entityId == id) {
                  t.updateFromEntity(e);
                  matched = true;
                  break;
               }
            }
            if (!matched && this.targets.size() < 32) {
               this.targets.add(new TargetAnchor(e.getId(), e.position(), Math.max(0.6F, e.getBbWidth()), Math.max(0.8F, e.getBbHeight()), 0.0F, this.random.nextDouble() * FxMath.TAU));
            }
         }
      }
      this.recomputeFocal();
      this.buildDetonationGeometry();
      this.launchShardBurst(tickNow);
      if (playAudio) {
         this.playDetonateSounds(level);
      }
   }

   /** 为全部目标生成漫天立体「斩绝线网」、核心交叉 3D 刀弧、目标间共鸣连弦与晶爆火花。 */
   private void buildDetonationGeometry() {
      this.slashStrings.clear();
      this.burstArcs.clear();
      this.resonanceChords.clear();

      int tCount = this.targets.size();
      int perTargetStrings;
      int perTargetArcs;
      if (tCount <= 3) {
         perTargetStrings = Math.min(14, this.stringsPerTarget + (tCount == 1 ? 2 : 0));
         perTargetArcs = 3;
      } else if (tCount <= 10) {
         perTargetStrings = Math.min(8, this.stringsPerTarget);
         perTargetArcs = 2;
      } else {
         perTargetStrings = Math.min(5, this.stringsPerTarget);
         perTargetArcs = 1;
      }
      float bossBoost = tCount == 1 ? 1.42F : (tCount <= 3 ? 1.18F : 1.0F);

      int totalStrings = 0;
      for (int ti = 0; ti < tCount; ti++) {
         TargetAnchor t = this.targets.get(ti);
         float baseRadius = Math.max(2.6F, Math.max(t.width * 2.2F, t.height * 1.35F)) * this.scale * bossBoost;

         for (int k = 0; k < perTargetStrings && totalStrings < MAX_TOTAL_SLASH_STRINGS; k++) {
            boolean major = k < 2;
            Vector3f dir = FxMath.randomUnit(this.random);
            // 避免斩线过于垂直或平行，拉出强烈对角交叉张力
            if (major && Math.abs(dir.y) > 0.85F) {
               dir.y *= 0.35F;
               dir.normalize();
            }
            Vector3f offset = FxMath.randomUnit(this.random).mul(baseRadius * (major ? 0.14F : 0.34F * this.random.nextFloat()));
            Vec3 mid = t.center.add(offset.x, offset.y * 0.65, offset.z);
            float halfSpan = baseRadius * (major ? (1.28F + this.random.nextFloat() * 0.45F) : (0.78F + this.random.nextFloat() * 0.55F));
            Vec3 span = new Vec3(dir.x, dir.y, dir.z).scale(halfSpan);
            float delay = major ? k * 0.45F : (0.5F + this.random.nextFloat() * 2.8F);
            float[] color = switch (k % 4) {
               case 0 -> ICE_CYAN;
               case 1 -> VIOLET;
               case 2 -> GOLD;
               default -> this.masterTint;
            };
            float slipSign = ((ti + k) & 1) == 0 ? 1.0F : -1.0F;
            this.slashStrings.add(new SlashString(mid.subtract(span), mid.add(span), delay, major, slipSign, major ? 1.25F : 0.82F, color));
            totalStrings++;
         }

         for (int aIdx = 0; aIdx < perTargetArcs; aIdx++) {
            Vector3f normal = FxMath.randomUnit(this.random);
            Vector3f u = FxMath.perpendicular(normal).normalize();
            Vector3f v = new Vector3f(normal).cross(u).normalize();
            float outerR = baseRadius * (0.72F + this.random.nextFloat() * 0.38F);
            float innerR = outerR * (0.64F + this.random.nextFloat() * 0.14F);
            float startAng = this.random.nextFloat() * FxMath.TAU;
            float span = (float)(Math.PI * (0.95 + this.random.nextDouble() * 0.55));
            float[] col = aIdx == 0 ? ICE_CYAN : (aIdx == 1 ? this.masterTint : GOLD);
            this.burstArcs.add(new BurstArc(t.center, u, v, innerR, outerR, startAng, startAng + span, aIdx * 0.85F, 12.0F, col));
         }

         // 晶爆星屑火花（总量严格封顶 MAX_SPARKS）
         int sparkBudget = Math.max(4, Math.min(36, MAX_SPARKS / Math.max(1, tCount)));
         for (int s = 0; s < sparkBudget && this.sparks.size() < MAX_SPARKS; s++) {
            float[] tint = switch (s % 5) {
               case 0 -> ICE_CYAN;
               case 1 -> VIOLET;
               case 2 -> GOLD;
               case 3 -> MAGENTA;
               default -> WHITE;
            };
            float spd = (0.48F + this.random.nextFloat() * 1.15F) * this.scale * bossBoost;
            this.sparks.add(Spark.burst(t.center, this.random, spd, 14 + this.random.nextInt(14), tint, -0.008F));
         }
      }

      // 多目标间建立共鸣斩弦（最多 16 条，将多座星牢连成立体审判网）
      if (tCount > 1) {
         int links = Math.min(16, tCount);
         for (int i = 0; i < links; i++) {
            TargetAnchor a = this.targets.get(i);
            TargetAnchor b = this.targets.get((i + 1) % tCount);
            if (a.center.distanceToSqr(b.center) > 0.5 && a.center.distanceToSqr(b.center) < 3600.0) {
               this.resonanceChords.add(new ResonanceChord(a.center, b.center, (i % 4) * 0.5F, i % 2 == 0 ? ICE_CYAN : VIOLET));
            }
         }
      }
   }

   /** 齐爆瞬间让全部碎刃由星牢高速爆射弹出，进入第四幕慢动作弹射与第五幕倒卷归鞘准备。 */
   private void launchShardBurst(int tickNow) {
      for (int i = 0; i < this.shards.size(); i++) {
         ShardUnit s = this.shards.get(i);
         TargetAnchor t = this.targets.get(s.targetIdx % this.targets.size());
         if (!s.active) {
            s.active = true;
            s.pos = t.center;
            s.prevPos = t.center;
         }
         Vec3 radial = s.pos.subtract(t.center);
         if (radial.lengthSqr() < 1.0E-4) {
            Vector3f rnd = FxMath.randomUnit(this.random);
            radial = new Vec3(rnd.x, Math.abs(rnd.y) + 0.25, rnd.z);
         }
         radial = radial.normalize();
         // 叠加轻微切线分量与上扬角，让晶爆呈立体的花瓣喷涌感
         Vector3f jitter = FxMath.randomUnit(this.random).mul(0.32F);
         Vec3 burstDir = radial.add(jitter.x, Math.abs(jitter.y) * 0.55 + 0.16, jitter.z).normalize();
         double speed = (0.78 + this.random.nextDouble() * 1.12) * this.scale * (s.role == 0 ? 1.15 : (s.role == 1 ? 1.0 : 0.88));
         s.vel = burstDir.scale(speed);
         s.dir = burstDir;
         s.burstAt = tickNow;
         s.recallAt = tickNow + RECALL_OFFSET_FROM_DETONATE + (i % 6);
         s.recallDur = 11 + (i % 5);
         s.recallStarted = false;
         s.recalled = false;
      }
   }

   // ───────────────────────────── 逻辑 tick（物理模拟 + 音频叙事） ─────────────────────────────

   @Override
   public Vec3 focus() {
      return this.focalAnchor;
   }

   @Override
   public double cullDistance() {
      return Math.max(200.0, this.range * 2.5 + 96.0);
   }

   @Override
   public boolean hasBody() {
      return true;
   }

   @Override
   public void tick(ClientLevel level) {
      // 同步施法者实时坐标与朝向（用于第一幕跟随与第五幕动态归鞘）
      Entity caster = level.getEntity(this.casterId);
      if (caster != null) {
         this.casterAnchor = PhantomJudgementPattern.casterCenter(caster.position(), caster.getBbHeight());
         Vec3 look = caster.getLookAngle();
         if (look.lengthSqr() > 1.0E-5) {
            this.casterLook = look.normalize();
         }
      }

      // 同步仍存活的目标坐标；目标若提前死亡则保持其最后有效星骸锚点
      if (!this.detonated) {
         for (TargetAnchor t : this.targets) {
            if (t.entityId >= 0) {
               Entity e = level.getEntity(t.entityId);
               if (e != null && e.isAlive()) {
                  t.updateFromEntity(e);
               }
            }
         }
         this.recomputeFocal();
      }

      int ageNow = this.ageTicks();
      // 若服务端因网络抖动延迟了 UPDATE，达到共享 DETONATE_TICK 时客户端保底自行齐爆不卡死
      if (!this.detonated && this.cancelledAt < 0 && ageNow >= this.detonateAt) {
         this.triggerDetonate(level, ageNow, null, true);
      }

      this.stepSimulation(level, ageNow, true);
      this.simulatedTicks = ageNow + 1;
   }

   private void stepSimulation(ClientLevel level, int simTick, boolean playAudio) {
      if (playAudio && this.cancelledAt < 0) {
         this.playTimelineSounds(level, simTick);
      }

      // 更新碎刃三阶状态机
      Vec3 recallGoal = this.currentRecallAnchor(level, 1.0F);
      for (ShardUnit s : this.shards) {
         this.tickShard(s, simTick, recallGoal);
      }

      // 更新裂变微闪脉冲
      Iterator<SplitPulse> pulseIt = this.splitPulses.iterator();
      while (pulseIt.hasNext()) {
         SplitPulse p = pulseIt.next();
         p.age++;
         if (p.age >= p.maxAge) {
            pulseIt.remove();
         }
      }

      // 更新动态晶爆火花
      Iterator<Spark> sparkIt = this.sparks.iterator();
      while (sparkIt.hasNext()) {
         Spark spark = sparkIt.next();
         spark.tick();
         if (spark.life <= 0) {
            sparkIt.remove();
         }
      }
   }

   private void tickShard(ShardUnit s, int simTick, Vec3 recallGoal) {
      // 未激活：等待裂变时刻从父刃位置诞生
      if (!s.active) {
         if (simTick < s.spawnTick || this.cancelledAt >= 0) {
            return;
         }
         s.active = true;
         Vec3 spawnPos = (s.parentIdx >= 0 && s.parentIdx < this.shards.size())
            ? this.shards.get(s.parentIdx).pos
            : this.casterAnchor;
         Vec3 parentDir = (s.parentIdx >= 0 && s.parentIdx < this.shards.size())
            ? this.shards.get(s.parentIdx).dir
            : this.casterLook;
         s.pos = spawnPos;
         s.prevPos = spawnPos;
         s.dir = parentDir;
         s.resetTrail(spawnPos);
         // 裂变瞬间记录一次短促晶裂微闪（仅母→子或抽样记录防过载）
         if (s.role == 1 || (s.id & 1) == 0) {
            this.splitPulses.add(new SplitPulse(spawnPos, s.color, s.role == 1 ? 0.65F * this.scale : 0.42F * this.scale, 5));
         }
      }

      s.prevPos = s.pos;
      TargetAnchor target = this.targets.get(s.targetIdx % this.targets.size());

      // 1. 已被中止（未引爆）：减速漂移并平滑溶解
      if (this.cancelledAt >= 0 && !this.detonated) {
         s.vel = s.vel.scale(0.86);
         s.pos = s.pos.add(s.vel);
         s.pushTrail(s.pos);
         return;
      }

      // 2. 第五幕：百刃倒流归鞘
      if (this.detonated && simTick >= s.recallAt) {
         if (!s.recallStarted) {
            s.recallStarted = true;
            s.recallStartPos = s.pos;
            // 构建优美螺旋归鞘中段控制点（侧弯 + 微扬；对近乎重合的起终点做安全向量兜底防止 NaN）
            Vec3 span = recallGoal.subtract(s.recallStartPos);
            double spanLen = span.length();
            Vec3 spanDir = spanLen > 1.0E-3 ? span.scale(1.0 / spanLen) : new Vec3(0.0, 0.0, 1.0);
            Vector3f axis = new Vector3f((float)spanDir.x, (float)spanDir.y, (float)spanDir.z);
            Vector3f perp = FxMath.perpendicular(axis);
            float ang = (float)(s.orbitPhase + s.id * 0.7);
            Vector3f side = new Vector3f(perp).rotateAxis(ang, axis.x, axis.y, axis.z);
            double bend = Math.min(8.5 * this.scale, 1.8 + spanLen * 0.24);
            s.recallControlOffset = new Vec3(side.x * bend, Math.abs(side.y) * bend * 0.65 + 1.1 * this.scale, side.z * bend);
         }
         int elapsed = simTick - s.recallAt;
         if (elapsed >= s.recallDur) {
            s.pos = recallGoal;
            s.recalled = true;
            s.pushTrail(s.pos);
            return;
         }
         double u = FxMath.easeInCubic((float)elapsed / s.recallDur);
         Vec3 ctrl = s.recallStartPos.lerp(recallGoal, 0.45).add(s.recallControlOffset.scale(1.0 - u * 0.65));
         double inv = 1.0 - u;
         Vec3 next = s.recallStartPos.scale(inv * inv).add(ctrl.scale(2.0 * inv * u)).add(recallGoal.scale(u * u));
         Vec3 delta = next.subtract(s.pos);
         if (delta.lengthSqr() > 1.0E-6) {
            s.dir = delta.normalize();
         }
         s.pos = next;
         s.pushTrail(s.pos);
         return;
      }

      // 3. 第四幕：齐爆晶碎弹射 → 强阻尼慢动作悬停
      if (this.detonated) {
         s.pos = s.pos.add(s.vel);
         s.vel = new Vec3(s.vel.x * 0.83, s.vel.y * 0.83 + 0.008, s.vel.z * 0.83);
         if (s.vel.lengthSqr() > 0.001) {
            s.dir = s.dir.lerp(s.vel.normalize(), 0.28).normalize();
         }
         s.pushTrail(s.pos);
         return;
      }

      // 4. 第一幕：母刃解离弧扇悬停（0 .. launchTick）
      if (s.role == 0 && simTick < s.detachTick) {
         Vec3 anchor = PhantomJudgementPattern.unsheatheAnchor(this.casterAnchor, this.casterLook, s.slot, this.masterCount, this.scale);
         double open = FxMath.easeOutCubic(FxMath.window(simTick + 1.0F, 0.0F, 5.5F));
         double hover = Math.sin(simTick * 0.55 + s.slot) * 0.08 * this.scale;
         s.pos = this.casterAnchor.lerp(anchor, open).add(0.0, hover, 0.0);
         Vec3 aim = target.center.subtract(s.pos);
         s.dir = aim.lengthSqr() > 1.0E-5 ? aim.normalize() : this.casterLook;
         s.pushTrail(s.pos);
         return;
      }

      // 5. 第二幕前段：子刃 / 追踪碎刃十字编队紧跟父刃（spawnTick .. detachTick）
      if (s.role > 0 && simTick < s.detachTick && s.parentIdx >= 0 && s.parentIdx < this.shards.size()) {
         ShardUnit parent = this.shards.get(s.parentIdx);
         double sideSpan = (s.role == 1 ? 1.55 : 0.92) * this.scale;
         double backSpan = (s.role == 1 ? 1.85 : 1.15) * this.scale;
         Vec3 desired = parent.pos.add(PhantomJudgementPattern.formationOffset(parent.dir, s.slot, sideSpan, backSpan));
         Vec3 step = desired.subtract(s.pos).scale(0.46);
         s.pos = s.pos.add(step);
         Vec3 moveDir = step.lengthSqr() > 0.002 ? step.normalize() : parent.dir;
         s.dir = s.dir.lerp(moveDir, 0.55).normalize();
         s.pushTrail(s.pos);
         return;
      }

      // 6. 第二幕主段：脱队自由追猎（带两端归零的 4 种有机曲线 + 宏大弧度包抄，精准对接星牢入口）
      if (simTick < s.arriveTick) {
         if (!s.pursueStarted) {
            s.pursueStarted = true;
            s.pursueStartPos = s.pos;
            s.pursueStartTick = simTick;
         }
         int spanTicks = Math.max(2, s.arriveTick - s.pursueStartTick);
         double u = clamp01((double)(simTick - s.pursueStartTick + 1) / spanTicks);
         double smooth = u * u * (3.0 - 2.0 * u);

         double cageR = PhantomJudgementPattern.cageRadius(target.width, target.height, s.orbitLayer, this.scale);
         Vec3 entryPos = PhantomJudgementPattern.cagePosition(target.center, cageR, s.orbitPhase, s.orbitLayer, target.yawOffset);
         Vec3 chord = entryPos.subtract(s.pursueStartPos);
         Vec3 baseDir = chord.lengthSqr() > 1.0E-5 ? chord.normalize() : this.casterLook;

         double envelope = Math.sin(Math.PI * u);
         double arcAmp = Math.min(7.5 * this.scale, (1.4 + chord.length() * 0.22) * s.arcScale);
         Vec3 organic = PhantomJudgementPattern.motionOffset(s.motionMode, baseDir, simTick * s.motionFreq, s.motionPhase, arcAmp * envelope);
         Vec3 lift = new Vec3(0.0, envelope * s.liftAmp * this.scale, 0.0);
         Vec3 next = s.pursueStartPos.lerp(entryPos, smooth).add(organic).add(lift);
         Vec3 move = next.subtract(s.pos);
         if (move.lengthSqr() > 1.0E-5) {
            s.dir = move.normalize();
         }
         s.pos = next;
         s.pushTrail(s.pos);
         return;
      }

      // 7. 第三幕：万刃星牢三倾角正反高速环绕 + 临界收弦骤缩指心
      float compress = FxMath.easeInCubic(FxMath.window(simTick, COMPRESS_START, this.detonateAt));
      double speedBoost = 1.0 + 2.25 * compress;
      s.orbitAngle += s.orbitOmega * speedBoost;

      double baseCageR = PhantomJudgementPattern.cageRadius(target.width, target.height, s.orbitLayer, this.scale);
      double curRadius = baseCageR * (1.0 - 0.46 * compress);
      Vec3 orbitPos = PhantomJudgementPattern.cagePosition(target.center, curRadius, s.orbitAngle, s.orbitLayer, target.yawOffset);
      s.pos = orbitPos;

      Vec3 tangent = PhantomJudgementPattern.cageTangent(s.orbitAngle, Math.signum(s.orbitOmega), s.orbitLayer, target.yawOffset);
      Vec3 inward = target.center.subtract(orbitPos);
      Vec3 toCore = inward.lengthSqr() > 1.0E-5 ? inward.normalize() : tangent;
      // 临界收弦期：碎刃由切线滑行迅速转向，百刃刃尖齐指目标核心！
      double aimFactor = FxMath.easeOutCubic(compress);
      Vec3 targetDir = tangent.lerp(toCore, aimFactor);
      s.dir = targetDir.lengthSqr() > 1.0E-5 ? targetDir.normalize() : toCore;
      s.pushTrail(s.pos);
   }

   private void playTimelineSounds(ClientLevel level, int age) {
      // 第一幕：解离晶鸣与逐个锁定声
      if (age == 0) {
         play(level, this.casterAnchor, SoundEvents.AMETHYST_BLOCK_CHIME, 1.35F, 1.18F);
         play(level, this.casterAnchor, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.85F, 1.65F);
      }
      int stride = Math.max(1, this.targets.size() / MAX_LOCK_SOUNDS);
      for (int i = 0; i < this.targets.size(); i += stride) {
         TargetAnchor t = this.targets.get(i);
         if ((int)Math.floor(t.lockAt) == age) {
            play(level, t.center, SoundEvents.AMETHYST_BLOCK_HIT, 0.45F, 1.55F + 0.06F * i);
         }
      }

      // 第二幕：母刃离弦 + 两段清脆阶梯裂变音
      if (age == UNSHEATHE_END) {
         play(level, this.casterAnchor, SoundEvents.TRIDENT_RIPTIDE_1, 1.15F, 1.58F);
      }
      if (age == SUB_SPLIT) {
         play(level, this.casterAnchor.lerp(this.focalAnchor, 0.35), SoundEvents.AMETHYST_BLOCK_CHIME, 1.4F, 1.45F);
         play(level, this.casterAnchor.lerp(this.focalAnchor, 0.35), SoundEvents.GLASS_BREAK, 0.55F, 1.75F);
      }
      if (age == TRACKER_SPLIT) {
         play(level, this.casterAnchor.lerp(this.focalAnchor, 0.58), SoundEvents.AMETHYST_BLOCK_CHIME, 1.45F, 1.78F);
         play(level, this.casterAnchor.lerp(this.focalAnchor, 0.58), SoundEvents.AMETHYST_CLUSTER_PLACE, 0.75F, 1.85F);
      }

      // 第三幕：星牢合围低鸣 + 临界收弦警鸣
      if (age == CAGE_START) {
         play(level, this.focalAnchor, SoundEvents.BEACON_ACTIVATE, 1.35F, 1.25F);
         play(level, this.focalAnchor, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.3F, 1.15F);
      }
      if (age == COMPRESS_START) {
         play(level, this.focalAnchor, SoundEvents.CROSSBOW_LOADING_END, 1.25F, 1.50F);
         play(level, this.focalAnchor, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.55F, 1.68F);
      }
      if (age == this.detonateAt - 3) {
         play(level, this.focalAnchor, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.75F, 1.98F);
      }

      // 第五幕：百刃倒流归鞘风声与清灵三连晶石落位音
      int recallStart = this.detonateAt + RECALL_OFFSET_FROM_DETONATE;
      if (this.detonated && age == recallStart) {
         play(level, this.casterAnchor, SoundEvents.TRIDENT_RETURN, 1.25F, 1.35F);
      }
      if (this.detonated && (age == recallStart + 11 || age == recallStart + 14 || age == recallStart + 17)) {
         float stepPitch = 1.25F + (age - (recallStart + 11)) * 0.10F;
         play(level, this.casterAnchor, SoundEvents.AMETHYST_BLOCK_CHIME, 1.15F, stepPitch);
      }
   }

   private void playDetonateSounds(ClientLevel level) {
      float vol = Math.min(2.8F, 1.45F + this.targets.size() * 0.08F);
      play(level, this.focalAnchor, SoundEvents.PLAYER_ATTACK_SWEEP, vol, 0.68F);
      play(level, this.focalAnchor, SoundEvents.GLASS_BREAK, vol * 1.15F, 0.56F);
      play(level, this.focalAnchor, SoundEvents.WARDEN_SONIC_BOOM, vol * 0.72F, 1.12F);
      play(level, this.focalAnchor, SoundEvents.AMETHYST_CLUSTER_BREAK, vol, 1.45F);
      play(level, this.focalAnchor, SoundEvents.TRIDENT_THUNDER, vol * 0.62F, 1.75F);
   }

   private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
      level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int ageNow = this.ageTicks();
      if (ageNow == 0) {
         for (int i = 0; i < 12; i++) {
            double ang = FxMath.TAU * i / 12.0;
            level.addParticle(ParticleTypes.WITCH, this.casterAnchor.x + Math.cos(ang) * 1.6, this.casterAnchor.y, this.casterAnchor.z + Math.sin(ang) * 1.6, 0.0, 0.04, 0.0);
         }
      }
      if (ageNow >= UNSHEATHE_END && ageNow < this.detonateAt && ageNow % 2 == 0) {
         int step = Math.max(1, this.shards.size() / 16);
         for (int i = 0; i < this.shards.size(); i += step) {
            ShardUnit s = this.shards.get(i);
            if (s.active) {
               level.addParticle(ParticleTypes.END_ROD, s.pos.x, s.pos.y, s.pos.z, 0.0, 0.0, 0.0);
            }
         }
      }
      if (this.detonated && ageNow == this.detonateAt) {
         for (TargetAnchor t : this.targets) {
            level.addParticle(ParticleTypes.FLASH, t.center.x, t.center.y, t.center.z, 0.0, 0.0, 0.0);
            level.addParticle(ParticleTypes.SWEEP_ATTACK, t.center.x, t.center.y, t.center.z, 0.0, 0.0, 0.0);
         }
      }
   }

   // ───────────────────────────── 时间与状态辅助 ─────────────────────────────

   private Vec3 currentCasterAnchor(FxFrame frame) {
      Entity caster = frame.level.getEntity(this.casterId);
      if (caster != null) {
         return PhantomJudgementPattern.casterCenter(caster.getPosition(frame.partialTick), caster.getBbHeight());
      }
      return this.casterAnchor;
   }

   private Vec3 currentRecallAnchor(ClientLevel level, float partialTick) {
      Entity caster = level.getEntity(this.casterId);
      if (caster != null) {
         return PhantomJudgementPattern.recallAnchor(caster.getPosition(partialTick), caster.getBbHeight(), caster.getLookAngle(), this.scale);
      }
      return PhantomJudgementPattern.recallAnchor(this.casterAnchor.add(0.0, -1.0, 0.0), 1.8F, this.casterLook, this.scale);
   }

   private void recomputeFocal() {
      if (this.targets.isEmpty()) {
         this.focalAnchor = this.casterAnchor;
         return;
      }
      double sx = 0.0;
      double sy = 0.0;
      double sz = 0.0;
      for (TargetAnchor t : this.targets) {
         sx += t.center.x;
         sy += t.center.y;
         sz += t.center.z;
      }
      double inv = 1.0 / this.targets.size();
      this.focalAnchor = new Vec3(sx * inv, sy * inv, sz * inv);
   }

   private float overloadFactor(float a) {
      if (this.cancelledAt >= 0) {
         return 0.0F;
      }
      if (!this.detonated) {
         return FxMath.easeInCubic(FxMath.window(a, COMPRESS_START, this.detonateAt));
      }
      return 1.0F - FxMath.window(a, this.detonateAt, this.detonateAt + 7.0F);
   }

   private float cancelDissolve(float a) {
      return this.cancelledAt < 0 ? 0.0F : FxMath.window(a, this.cancelledAt, this.cancelledAt + 8.0F);
   }

   private static float nearFade(Vector3f rel) {
      return FxMath.window(rel.length(), 1.05F, 2.45F);
   }

   private float blastAttenuation(FxFrame frame) {
      double minDist = frame.camera.distanceTo(this.focalAnchor);
      for (TargetAnchor t : this.targets) {
         minDist = Math.min(minDist, frame.camera.distanceTo(t.center));
      }
      double maxRange = Math.max(48.0, this.range * 1.45 + 32.0);
      float att = (float)Math.max(0.0, 1.0 - Math.max(0.0, minDist - 6.0) / maxRange);
      // 施法者本人即使在远距离施放仍保留清晰可感的斩绝冲击
      double casterDist = frame.camera.distanceTo(this.casterAnchor);
      if (casterDist < 6.0) {
         att = Math.max(att, 0.58F);
      }
      return att;
   }

   // ───────────────────────────── Body 暗部实体层（高反差黑曜晶核 & 斩绝暗腔） ─────────────────────────────

   @Override
   public void renderBody(FxGeometry b, FxFrame frame) {
      float a = this.age(frame.partialTick);
      float overload = this.overloadFactor(a);
      float cancelDissolve = this.cancelDissolve(a);

      // 1. 全部 3D 棱晶碎刃玄黑暗紫晶核（1 次合批，白天晴空高反差立体棱面）
      this.renderShardBodies(b, frame, a, overload, cancelDissolve, true);

      // 2. 第三幕星牢目标脚下暗紫底环（稳稳托出上层紫金符文阵）
      if (this.cancelledAt < 0 && a >= CAGE_START - 4.0F && a < this.detonateAt + 8.0F) {
         float cageIn = FxMath.easeOutCubic(FxMath.window(a, CAGE_START - 4.0F, CAGE_START + 2.0F));
         float cageOut = this.detonated ? (1.0F - FxMath.window(a, this.detonateAt, this.detonateAt + 8.0F)) : 1.0F;
         float alpha = cageIn * cageOut * 0.62F;
         if (alpha > 0.01F) {
            b.begin(Style.SLASH_ARC, 0.60F, 0.25F, 5.0F, 0.75F, 1.0F, 0.0F);
            Vector3f ux = new Vector3f(1.0F, 0.0F, 0.0F);
            Vector3f uz = new Vector3f(0.0F, 0.0F, 1.0F);
            for (TargetAnchor t : this.targets) {
               float r = (float)PhantomJudgementPattern.cageRadius(t.width, t.height, 0, this.scale) * (1.0F - 0.35F * overload);
               Vector3f foot = frame.rel(t.feet.add(0.0, 0.05, 0.0));
               float nf = nearFade(foot);
               float rot = a * 0.08F + (float)t.yawOffset;
               b.slashArc(foot, ux, uz, r * 0.58F, r * 1.14F, rot, rot + (float)Math.PI * 1.15F, 14, DEEP_OBSIDIAN[0], DEEP_OBSIDIAN[1], DEEP_OBSIDIAN[2], alpha * nf);
               b.slashArc(foot, ux, uz, r * 0.58F, r * 1.14F, rot + (float)Math.PI, rot + FxMath.TAU + 0.38F, 14, DEEP_OBSIDIAN[0], DEEP_OBSIDIAN[1], DEEP_OBSIDIAN[2], alpha * nf);
            }
            b.end();
         }
      }

      // 3. 第四幕：漫天立体「斩绝线」暗黑撕裂腔 + 核心交叉 3D 暗芯空间刀弧
      if (this.detonated) {
         float post = a - this.detonateAt;
         if (post >= 0.0F && post < 16.0F) {
            this.renderSlashStrings(b, frame, post, true);
            this.renderBurstArcs(b, frame, post, true);
         }
      }
   }

   // ───────────────────────────── HDR 主几何发光层（五幕绚烂群舞） ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      float a = this.age(frame.partialTick);
      float overload = this.overloadFactor(a);
      float cancelDissolve = this.cancelDissolve(a);

      // 1. 第一幕施法者身侧解离晶环 & 锁定光丝 / 瞄准星标
      if (this.cancelledAt < 0 && !this.detonated) {
         this.renderUnsheatheAndLocks(g, frame, a);
      }

      // 2. 全场三阶 3D 棱晶碎刃 HDR 锋刃晶棱（1 次合批）
      this.renderShardBodies(g, frame, a, overload, cancelDissolve, false);

      // 3. 全场百余条丝滑三色长流光尾（1 次 RIBBON 合批）
      this.renderShardTrails(g, frame, a, overload, cancelDissolve);

      // 4. 裂变瞬间晶爆微闪十字星芒
      this.renderSplitPulses(g, frame);

      // 5. 第三幕万刃星牢阵纹、三倾角星轨微环与临界收弦张力弦
      if (this.cancelledAt < 0 && a >= CAGE_START - 4.0F && a < this.detonateAt + 6.0F) {
         this.renderCageFormation(g, frame, a, overload);
      }

      // 6. 第四幕：漫天立体斩绝线网 + 交叉 3D 月牙刀弧 + 共鸣斩弦 + 双层 3D 激波环 + 爆心十字星芒
      if (this.detonated) {
         float post = a - this.detonateAt;
         if (post >= 0.0F && post < 22.0F) {
            this.renderSlashStrings(g, frame, post, false);
            this.renderBurstArcs(g, frame, post, false);
            this.renderDetonationRingsAndChords(g, frame, post, a);
         }
         // 第五幕：百刃归鞘刀侧收束微环与归位星芒
         this.renderRecallAura(g, frame, a);
      }

      // 7. 动态晶爆星屑火花
      this.renderSparks(g, frame);
   }

   /**
    * 统一合批绘制全部 3D 棱晶碎刃（Body 玄黑晶核与 HDR 晶棱共用同一套插值位置与正交朝向基）。
    */
   private void renderShardBodies(FxGeometry geo, FxFrame frame, float a, float overload, float cancelDissolve, boolean bodyPass) {
      if (this.shards.isEmpty()) {
         return;
      }
      if (bodyPass) {
         geo.begin(Style.CRYSTAL_SHARD, 0.88F, 0.12F, 2.2F, 1.4F, overload, cancelDissolve);
      } else {
         geo.begin(Style.CRYSTAL_SHARD, 1.75F + 1.45F * overload, 0.12F, 2.4F, 1.8F, overload, cancelDissolve);
      }

      Vector3f forward = new Vector3f();
      Vector3f right = new Vector3f();
      Vector3f up = new Vector3f();
      Vector3f tmpRef = new Vector3f();

      for (ShardUnit s : this.shards) {
         if (!s.active || s.recalled) {
            continue;
         }
         float alpha = 1.0F;
         if (this.cancelledAt >= 0 && !this.detonated) {
            alpha *= 1.0F - cancelDissolve;
         } else if (s.recallStarted) {
            float u = FxMath.window(a, s.recallAt, s.recallAt + s.recallDur);
            alpha *= 1.0F - FxMath.window(u, 0.82F, 1.0F);
         } else {
            alpha *= FxMath.easeOutCubic(FxMath.window(a, s.spawnTick, s.spawnTick + 2.2F));
         }
         if (alpha <= 0.01F) {
            continue;
         }

         Vec3 posW = s.interpolatedPos(frame.partialTick);
         Vector3f rel = frame.rel(posW);
         float nf = nearFade(rel);
         if (nf <= 0.01F) {
            continue;
         }

         forward.set((float)s.dir.x, (float)s.dir.y, (float)s.dir.z);
         if (forward.lengthSquared() < 1.0E-5F) {
            forward.set(0.0F, 0.0F, 1.0F);
         } else {
            forward.normalize();
         }
         tmpRef.set(Math.abs(forward.y) > 0.92F ? 1.0F : 0.0F, Math.abs(forward.y) > 0.92F ? 0.0F : 1.0F, 0.0F);
         right.set(forward).cross(tmpRef).normalize();
         up.set(right).cross(forward).normalize();

         // 碎刃绕前进轴自旋滚转（蓄压与爆散期转速提升，让八面体切面闪烁冷光）
         float roll = s.rollPhase + a * s.rollSpeed * (1.0F + 1.8F * overload);
         float cr = (float)Math.cos(roll);
         float sr = (float)Math.sin(roll);
         float rx = right.x * cr + up.x * sr;
         float ry = right.y * cr + up.y * sr;
         float rz = right.z * cr + up.z * sr;
         float ux = up.x * cr - right.x * sr;
         float uy = up.y * cr - right.y * sr;
         float uz = up.z * cr - right.z * sr;
         right.set(rx, ry, rz);
         up.set(ux, uy, uz);

         // 高速追击或归鞘时轻微拉长剑身，强化速度张力
         float stretch = s.recallStarted ? 1.28F : (1.0F + 0.18F * overload);
         float[] col = bodyPass ? DEEP_OBSIDIAN : s.color;
         geo.bladeShard(
            rel, forward, right, up,
            s.frontLen * stretch, s.backLen, s.halfWidth, s.halfThick,
            col[0], col[1], col[2], alpha * nf
         );
      }
      geo.end();
   }

   /** 1 次 RIBBON 合批渲染全场百余条丝滑三色长流光尾。 */
   private void renderShardTrails(FxGeometry g, FxFrame frame, float a, float overload, float cancelDissolve) {
      if (this.shards.isEmpty()) {
         return;
      }
      float fadeGlobal = (this.cancelledAt >= 0 && !this.detonated) ? (1.0F - cancelDissolve) : 1.0F;
      if (fadeGlobal <= 0.01F) {
         return;
      }
      g.begin(Style.RIBBON, 2.55F + 1.35F * overload, 0.15F, 6.2F, 5.0F, 9.0F, 0.42F);
      for (ShardUnit s : this.shards) {
         if (!s.active || s.trailCount < 2) {
            continue;
         }
         float localFade = fadeGlobal;
         if (s.recalled) {
            float sinceDone = a - (s.recallAt + s.recallDur);
            if (sinceDone >= 5.0F) {
               continue;
            }
            localFade *= 1.0F - FxMath.window(sinceDone, 0.0F, 5.0F);
         }
         if (localFade <= 0.01F) {
            continue;
         }

         int count = s.trailCount + (s.recalled ? 0 : 1);
         if (count < 2) {
            continue;
         }
         List<Vector3f> pts = new ArrayList<>(count);
         if (!s.recalled) {
            pts.add(frame.rel(s.interpolatedPos(frame.partialTick)));
         }
         for (int i = 0; i < s.trailCount; i++) {
            pts.add(frame.rel(s.trailAt(i)));
         }
         int n = pts.size();
         if (n < 2) {
            continue;
         }
         float[] widths = new float[n];
         float[] alphas = new float[n];
         float baseW = (s.role == 0 ? 0.072F : (s.role == 1 ? 0.048F : 0.032F)) * this.scale * (s.recallStarted ? 1.25F : 1.0F);
         for (int i = 0; i < n; i++) {
            float k = 1.0F - (float)i / (n - 1);
            float taper = k * k;
            widths[i] = baseW * (0.12F + 0.88F * k);
            alphas[i] = localFade * taper * 0.86F * nearFade(pts.get(i));
         }
         g.ribbon(pts, widths, alphas, s.color[0], s.color[1], s.color[2]);
      }
      g.end();
   }

   /** 第一幕：施法者解离扩散晶环 + 锁定光丝 + 目标头顶旋转四芒星标与收缩瞄准环。 */
   private void renderUnsheatheAndLocks(FxGeometry g, FxFrame frame, float a) {
      Vec3 casterPos = this.currentCasterAnchor(frame);
      Vector3f cc = frame.rel(casterPos);

      // 1.1 解离瞬间的双层水平晶环波纹（0 ~ 10 tick）
      if (a < 10.0F) {
         float p = FxMath.window(a, 0.0F, 10.0F);
         float ease = FxMath.easeOutCubic(p);
         float fade = (1.0F - p) * (1.0F - p * 0.35F);
         float r1 = (0.8F + 3.4F * ease) * this.scale;
         float r2 = (0.5F + 2.4F * ease) * this.scale;
         g.begin(Style.RING, 3.2F * fade, 0.2F, 0.94F, 0.025F, 24.0F, 0.45F);
         g.quad(cc, new Vector3f(r1, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r1), this.masterTint[0], this.masterTint[1], this.masterTint[2], fade * 0.85F);
         g.quad(cc, new Vector3f(r2, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r2), ICE_CYAN[0], ICE_CYAN[1], ICE_CYAN[2], fade * 0.75F);
         g.end();
      }

      // 1.2 锁定星丝（0.8 ~ 14 tick，从施法者身侧纤锐连向每个锁定目标头顶）
      float presence = 1.0F - FxMath.window(a, 11.0F, 16.0F);
      if (presence > 0.01F) {
         g.begin(Style.RIBBON, 3.6F, 0.15F, 7.0F, 0.0F, 0.0F, 0.35F);
         for (TargetAnchor t : this.targets) {
            float e = a - t.lockAt;
            if (e < 0.0F) {
               continue;
            }
            float reveal = FxMath.easeOutCubic(FxMath.window(e, 0.0F, 1.2F));
            Vec3 to = t.top().add(0.0, 0.35 * this.scale, 0.0);
            Vec3 from = casterPos.lerp(to, 0.16);
            Vec3 tip = from.lerp(to, reveal);
            Vector3f p0 = frame.rel(from);
            Vector3f p1 = frame.rel(tip);
            float al = presence * (0.35F + 0.65F * (1.0F - FxMath.window(e, 1.5F, 6.0F)));
            g.ribbon(List.of(p0, p1), new float[]{0.016F * this.scale, 0.010F * this.scale}, new float[]{al * nearFade(p0), al * nearFade(p1)}, ICE_CYAN[0], ICE_CYAN[1], ICE_CYAN[2]);
         }
         g.end();
      }

      // 1.3 目标锁定瞄准缩圈 & 旋转四芒星标（持续至齐爆前）
      float markFade = 1.0F - FxMath.window(a, COMPRESS_START + 4.0F, this.detonateAt);
      if (markFade > 0.01F) {
         g.begin(Style.RING, 3.0F, 0.25F, 0.94F, 0.032F, 4.0F, 0.35F);
         for (TargetAnchor t : this.targets) {
            float e = a - t.lockAt;
            if (e < 0.0F) {
               continue;
            }
            float shrink = FxMath.easeOutCubic(FxMath.window(e, 0.0F, 3.5F));
            float r = Math.max(0.85F, t.height * 0.72F) * (1.55F - 0.65F * shrink) * this.scale;
            float spin = a * 0.14F + t.lockAt;
            Vector3f c = frame.rel(t.center);
            Vector3f u = new Vector3f(frame.left).mul((float)Math.cos(spin)).add(new Vector3f(frame.up).mul((float)Math.sin(spin))).mul(r);
            Vector3f v = new Vector3f(frame.up).mul((float)Math.cos(spin)).sub(new Vector3f(frame.left).mul((float)Math.sin(spin))).mul(r);
            float al = markFade * (0.38F + 0.62F * (1.0F - FxMath.window(e, 4.0F, 10.0F))) * nearFade(c);
            g.quad(c, u, v, this.masterTint[0], this.masterTint[1], this.masterTint[2], al);
         }

         g.begin(Style.SPRITE, 5.2F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F);
         for (TargetAnchor t : this.targets) {
            float e = a - t.lockAt;
            if (e < 0.0F) {
               continue;
            }
            float in = FxMath.easeOutCubic(FxMath.window(e, 0.0F, 1.4F));
            Vector3f p = frame.rel(t.top().add(0.0, 0.62 * this.scale, 0.0));
            float pulse = 0.85F + 0.15F * (float)Math.sin(a * 0.55F + t.lockAt);
            float al = in * markFade * nearFade(p);
            g.billboard(p, 0.30F * this.scale * pulse, a * 0.08F, ICE_CYAN[0], ICE_CYAN[1], ICE_CYAN[2], al);
            g.billboard(p, 0.14F * this.scale, -a * 0.13F, WHITE[0], WHITE[1], WHITE[2], al);
         }
         g.end();
      }
   }

   /** 裂变微闪脉冲（母→子与子→追踪刃分裂瞬间的璀璨星芒）。 */
   private void renderSplitPulses(FxGeometry g, FxFrame frame) {
      if (this.splitPulses.isEmpty()) {
         return;
      }
      g.begin(Style.GLOW, 5.8F, 0.0F, 6.2F, 2.6F, 1.1F, 0.0F);
      for (SplitPulse p : this.splitPulses) {
         float k = 1.0F - (p.age + frame.partialTick) / p.maxAge;
         if (k <= 0.0F) {
            continue;
         }
         Vector3f rel = frame.rel(p.pos);
         float sz = p.size * (0.55F + 0.65F * (1.0F - k));
         g.billboard(rel, sz, p.age * 0.25F, p.color[0], p.color[1], p.color[2], k * k * nearFade(rel));
         g.billboard(rel, sz * 0.55F, 1.5707964F, WHITE[0], WHITE[1], WHITE[2], k * k * 0.85F * nearFade(rel));
      }
      g.end();
   }

   /** 第三幕：万刃星牢脚下迷你锁定阵、三倾角立体细星轨与临界收弦张力细丝。 */
   private void renderCageFormation(FxGeometry g, FxFrame frame, float a, float overload) {
      float cageIn = FxMath.easeOutCubic(FxMath.window(a, CAGE_START - 4.0F, CAGE_START + 2.0F));
      float cageOut = this.detonated ? (1.0F - FxMath.window(a, this.detonateAt, this.detonateAt + 6.0F)) : 1.0F;
      float alpha = cageIn * cageOut;
      if (alpha <= 0.01F) {
         return;
      }

      // 3.1 目标脚下旋转六芒锁定阵（星金 + 虚空紫，随收弦骤缩增亮）
      g.begin(Style.RUNE, 2.6F + 2.4F * overload, 0.0F, a * (0.07F + 0.14F * overload), this.seedFloat + 13.0F, 0.0F, cageIn);
      for (TargetAnchor t : this.targets) {
         float r = (float)PhantomJudgementPattern.cageRadius(t.width, t.height, 0, this.scale) * 1.12F * (1.0F - 0.36F * overload);
         Vector3f foot = frame.rel(t.feet.add(0.0, 0.06, 0.0));
         float[] col = overload > 0.45F ? GOLD : this.masterTint;
         g.quad(foot, new Vector3f(r, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r), col[0], col[1], col[2], alpha * 0.85F * nearFade(foot));
      }

      // 3.2 三倾角星牢细轨环（勾勒立体球笼轮廓）
      g.begin(Style.RIBBON, 2.1F + 1.8F * overload, 0.18F, 7.0F, 6.0F, 10.0F, 0.38F);
      for (TargetAnchor t : this.targets) {
         Vector3f tc = frame.rel(t.center);
         float nf = nearFade(tc);
         if (nf <= 0.01F) {
            continue;
         }
         double cy = Math.cos(t.yawOffset);
         double sy = Math.sin(t.yawOffset);
         for (int layer = 0; layer < 3; layer++) {
            float r = (float)PhantomJudgementPattern.cageRadius(t.width, t.height, layer, this.scale) * (1.0F - 0.44F * overload);
            double tilt = layer == 0 ? 0.14 : (layer == 1 ? 0.74 : -0.74);
            // 轨道法线向量
            double ny = Math.cos(tilt);
            double nz = -Math.sin(tilt);
            Vector3f normal = new Vector3f((float)(-nz * sy), (float)ny, (float)(nz * cy)).normalize();
            float[] col = layer == 0 ? GOLD : (layer == 1 ? ICE_CYAN : VIOLET);
            float tube = (0.018F + 0.014F * overload) * this.scale;
            g.torus(tc, normal, r, tube, 28, 4, a * (layer == 1 ? -0.10F : 0.09F), col[0], col[1], col[2], alpha * (0.42F + 0.38F * overload) * nf);
         }
      }

      // 3.3 临界收弦（48 ~ 56 tick）：每个母刃/子刃刃尖射向目标核心的紧绷蓄压张力弦！
      if (overload > 0.02F && !this.detonated) {
         g.begin(Style.RIBBON, 4.5F + 4.5F * overload, 0.12F, 8.5F, 0.0F, 0.0F, 0.30F);
         int step = Math.max(1, this.shards.size() / 40);
         for (int i = 0; i < this.shards.size(); i += step) {
            ShardUnit s = this.shards.get(i);
            if (!s.active) {
               continue;
            }
            TargetAnchor t = this.targets.get(s.targetIdx % this.targets.size());
            Vector3f p0 = frame.rel(s.interpolatedPos(frame.partialTick));
            Vector3f p1 = frame.rel(t.center);
            float w = (0.012F + 0.024F * overload) * this.scale;
            float al = overload * 0.82F * nearFade(p0);
            float[] col = s.role == 0 ? GOLD : ICE_CYAN;
            g.ribbon(List.of(p0, p1), new float[]{w * 0.4F, w}, new float[]{al * 0.5F, al}, col[0], col[1], col[2]);
         }
      }
      g.end();
   }

   /** 第四幕核心奇观：漫天立体交错「斩绝线网」（Body 暗腔 + HDR 锯齿刃口 + 灼热极细白芯）。 */
   private void renderSlashStrings(FxGeometry geo, FxFrame frame, float post, boolean bodyPass) {
      if (this.slashStrings.isEmpty()) {
         return;
      }
      if (bodyPass) {
         geo.begin(Style.VOID_SLIT, 1.0F, 0.0F, 0.58F, 0.68F, 1.0F, 0.0F);
         for (SlashString line : this.slashStrings) {
            float t = post - line.delay;
            if (t < 0.0F || t >= 13.0F) {
               continue;
            }
            float reveal = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 1.4F));
            float fade = 1.0F - FxMath.easeInCubic(FxMath.window(t, 4.5F, 13.0F));
            if (fade <= 0.01F) {
               continue;
            }
            Vec3 end = line.a.lerp(line.b, reveal);
            this.drawSlitQuad(geo, frame, line.a, end, 0.28F * line.weight * this.scale * (0.5F + 0.5F * fade), DEEP_OBSIDIAN, fade * 0.92F);
         }
         geo.end();
         return;
      }

      // HDR 层 1：VOID_SLIT 锯齿亮刃与内星
      geo.begin(Style.VOID_SLIT, 3.8F, 0.0F, 0.54F, 0.72F, 1.0F, 0.25F);
      for (SlashString line : this.slashStrings) {
         float t = post - line.delay;
         if (t < 0.0F || t >= 13.5F) {
            continue;
         }
         float reveal = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 1.4F));
         float fade = 1.0F - FxMath.easeInCubic(FxMath.window(t, 4.0F, 13.5F));
         if (fade <= 0.01F) {
            continue;
         }
         Vec3 end = line.a.lerp(line.b, reveal);
         this.drawSlitQuad(geo, frame, line.a, end, 0.30F * line.weight * this.scale * (0.45F + 0.55F * fade), line.color, fade * 0.95F);
      }

      // HDR 层 2：极细锐纯白灼热斩绝核心丝线（一闪贯穿，凌厉极致）
      geo.begin(Style.RIBBON, 9.2F, 0.12F, 9.5F, 0.0F, 0.0F, 0.25F);
      for (SlashString line : this.slashStrings) {
         float t = post - line.delay;
         if (t < 0.0F || t >= 9.5F) {
            continue;
         }
         float reveal = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 1.2F));
         float fade = 1.0F - FxMath.window(t, 2.2F, 9.5F);
         if (fade <= 0.01F) {
            continue;
         }
         Vec3 end = line.a.lerp(line.b, reveal);
         Vector3f pa = frame.rel(line.a);
         Vector3f pb = frame.rel(end);
         Vector3f mid = new Vector3f(pa).add(pb).mul(0.5F);
         float nf = 0.28F + 0.72F * nearFade(mid);
         float w = (line.major ? 0.052F : 0.032F) * this.scale * (0.35F + 0.65F * fade);
         geo.ribbon(List.of(pa, mid, pb), new float[]{w * 0.12F, w, w * 0.12F}, new float[]{0.15F * fade * nf, fade * nf, 0.15F * fade * nf}, WHITE[0], WHITE[1], WHITE[2]);
      }
      geo.end();
   }

   /** 第四幕：目标核心立体交叉 3D 曲面月牙刀弧（Body 暗黑切口 + HDR 高光锋刃）。 */
   private void renderBurstArcs(FxGeometry geo, FxFrame frame, float post, boolean bodyPass) {
      if (this.burstArcs.isEmpty()) {
         return;
      }
      for (BurstArc arc : this.burstArcs) {
         float t = post - arc.delay;
         if (t < 0.0F || t >= arc.duration) {
            continue;
         }
         float p = t / arc.duration;
         float head = FxMath.easeOutCubic(FxMath.window(p, 0.0F, 0.36F));
         float tail = FxMath.easeInCubic(FxMath.window(p, 0.30F, 0.96F));
         float fade = 1.0F - FxMath.window(p, 0.60F, 1.0F);
         if (fade <= 0.01F || head <= tail + 0.02F) {
            continue;
         }
         Vector3f centerRel = frame.rel(arc.center);
         float nf = nearFade(centerRel);
         if (bodyPass) {
            geo.begin(Style.SLASH_ARC, 0.70F, 0.20F, 6.0F, 0.85F, head, tail);
            geo.slashArc(centerRel, arc.u, arc.v, arc.innerR * 0.92F, arc.outerR * 1.04F, arc.startAng, arc.endAng, 16, DEEP_OBSIDIAN[0], DEEP_OBSIDIAN[1], DEEP_OBSIDIAN[2], fade * 0.90F * nf);
         } else {
            geo.begin(Style.SLASH_ARC, 4.6F * fade, 0.20F, 9.2F, 0.88F, head, tail);
            geo.slashArc(centerRel, arc.u, arc.v, arc.innerR, arc.outerR, arc.startAng, arc.endAng, 16, arc.color[0], arc.color[1], arc.color[2], fade * nf);
         }
      }
      geo.end();
   }

   /** 第四幕：爆心双层倾斜 3D 扩散激波环、多目标共鸣连弦与锐利十字星芒。 */
   private void renderDetonationRingsAndChords(FxGeometry g, FxFrame frame, float post, float a) {
      // 1. 每个目标爆心的双层倾斜轻盈 3D 扩散激波环（冰青 + 紫金）
      if (post < 15.0F) {
         float p = post / 15.0F;
         float ease = FxMath.easeOutCubic(p);
         float fade = (1.0F - p) * (1.0F - p * 0.4F);
         g.begin(Style.RIBBON, 4.6F * fade, 0.25F, 6.0F, 6.0F, 10.0F, 0.42F);
         Vector3f n1 = new Vector3f(0.28F, 0.94F, 0.18F).normalize();
         Vector3f n2 = new Vector3f(-0.34F, 0.91F, -0.22F).normalize();
         int ringLimit = Math.min(this.targets.size(), 12);
         for (int i = 0; i < ringLimit; i++) {
            TargetAnchor t = this.targets.get(i);
            Vector3f tc = frame.rel(t.center);
            float baseR = (float)PhantomJudgementPattern.cageRadius(t.width, t.height, 1, this.scale);
            float r1 = baseR * (0.45F + 2.35F * ease);
            float r2 = baseR * (0.35F + 1.80F * ease);
            float nf = nearFade(tc);
            g.torus(tc, n1, r1, (0.14F - 0.06F * p) * this.scale, 28, 5, a * 0.12F + i, ICE_CYAN[0], ICE_CYAN[1], ICE_CYAN[2], fade * 0.90F * nf);
            g.torus(tc, n2, r2, (0.10F - 0.04F * p) * this.scale, 24, 4, -a * 0.15F + i, GOLD[0], GOLD[1], GOLD[2], fade * 0.78F * nf);
         }
         g.end();
      }

      // 2. 多目标间共鸣斩弦
      if (!this.resonanceChords.isEmpty() && post < 11.0F) {
         g.begin(Style.RIBBON, 5.2F, 0.15F, 7.5F, 8.0F, 14.0F, 0.35F);
         for (ResonanceChord chord : this.resonanceChords) {
            float t = post - chord.delay;
            if (t < 0.0F || t >= 8.5F) {
               continue;
            }
            float reveal = FxMath.easeOutCubic(FxMath.window(t, 0.0F, 1.5F));
            float fade = 1.0F - FxMath.window(t, 2.5F, 8.5F);
            Vector3f pa = frame.rel(chord.a);
            Vector3f pb = frame.rel(chord.a.lerp(chord.b, reveal));
            float w = 0.036F * this.scale * fade;
            g.ribbon(List.of(pa, pb), new float[]{w, w}, new float[]{fade * nearFade(pa), fade * nearFade(pb)}, chord.color[0], chord.color[1], chord.color[2]);
         }
         g.end();
      }

      // 3. 爆心短促十字星芒（0 ~ 7.5 tick，清冽克制不洗白）
      if (post < 7.5F) {
         float k = 1.0F - post / 7.5F;
         g.begin(Style.GLOW, 8.5F, 0.2F, 6.8F, 3.0F, 1.6F, 0.0F);
         int flashLimit = Math.min(this.targets.size(), 12);
         for (int i = 0; i < flashLimit; i++) {
            TargetAnchor t = this.targets.get(i);
            Vector3f tc = frame.rel(t.center);
            float sz = Math.max(1.4F, t.height * 1.15F) * this.scale * (0.65F + 0.75F * (1.0F - k));
            float al = k * k * nearFade(tc);
            g.billboard(tc, sz, 0.0F, WHITE[0], WHITE[1], WHITE[2], al);
            g.billboard(tc, sz * 0.85F, 1.5707964F, ICE_CYAN[0], ICE_CYAN[1], ICE_CYAN[2], al * 0.82F);
         }
         g.end();
      }
   }

   /** 第五幕：百刃倒流抵近施法者刀侧时的收束星芒与清澈归鞘微环。 */
   private void renderRecallAura(FxGeometry g, FxFrame frame, float a) {
      int recallEndStart = this.detonateAt + RECALL_OFFSET_FROM_DETONATE + 9;
      float t = a - recallEndStart;
      if (t < 0.0F || t > 16.0F) {
         return;
      }
      Vec3 anchor = this.currentRecallAnchor(frame.level, frame.partialTick);
      Vector3f rel = frame.rel(anchor);
      float nf = nearFade(rel);
      if (nf <= 0.01F) {
         return;
      }

      float env = FxMath.envelope(FxMath.window(t, 0.0F, 14.0F), 0.25F, 0.55F);
      float r = (1.15F - 0.65F * FxMath.easeInCubic(FxMath.window(t, 0.0F, 12.0F))) * this.scale;
      g.begin(Style.RING, 3.4F * env, 0.15F, 0.92F, 0.028F, 16.0F, -0.75F);
      g.quad(rel, new Vector3f(frame.left).mul(r), new Vector3f(frame.up).mul(r), ICE_CYAN[0], ICE_CYAN[1], ICE_CYAN[2], env * 0.85F * nf);

      g.begin(Style.GLOW, 5.2F * env, 0.0F, 6.5F, 2.8F, 1.3F, 0.0F);
      g.billboard(rel, 0.52F * this.scale * (0.6F + 0.4F * env), a * 0.2F, WHITE[0], WHITE[1], WHITE[2], env * 0.90F * nf);
      g.billboard(rel, 0.68F * this.scale * env, -a * 0.15F, this.masterTint[0], this.masterTint[1], this.masterTint[2], env * 0.75F * nf);
      g.end();
   }

   private void renderSparks(FxGeometry g, FxFrame frame) {
      if (this.sparks.isEmpty()) {
         return;
      }
      g.begin(Style.RIBBON, 5.6F, 0.0F, 6.0F, 0.0F, 0.0F, 0.48F);
      for (Spark spark : this.sparks) {
         Vec3 p = spark.position(frame.partialTick);
         Vec3 tail = p.subtract(spark.vx * 2.5, spark.vy * 2.5, spark.vz * 2.5);
         float life = (float)spark.life / spark.maxLife;
         Vector3f rel = frame.rel(p);
         g.streak(rel, frame.rel(tail), 0.044F * Math.min(1.4F, this.scale), spark.color[0], spark.color[1], spark.color[2], life * nearFade(rel));
      }
      g.end();
   }

   private void drawSlitQuad(FxGeometry g, FxFrame frame, Vec3 startW, Vec3 endW, float halfWidth, float[] color, float alpha) {
      Vec3 midW = startW.lerp(endW, 0.5);
      Vector3f relA = frame.rel(startW);
      Vector3f relB = frame.rel(endW);
      Vector3f relMid = frame.rel(midW);
      Vector3f axisV = new Vector3f(relB).sub(relA).mul(0.5F);
      if (axisV.lengthSquared() < 1.0E-5F) {
         return;
      }
      Vector3f toCam = new Vector3f(relMid).negate();
      if (toCam.lengthSquared() < 1.0E-5F) {
         toCam.set(0.0F, 0.0F, 1.0F);
      }
      Vector3f cross = new Vector3f(axisV).cross(toCam);
      if (cross.lengthSquared() < 1.0E-5F) {
         return;
      }
      float nf = 0.26F + 0.74F * nearFade(relMid);
      Vector3f axisU = cross.normalize().mul(halfWidth);
      g.quad(relMid, axisU, axisV, color[0], color[1], color[2], alpha * nf);
   }

   // ───────────────────────────── B 档全屏空间切裂（仅齐爆 9.5 tick 内激活） ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      if (!this.detonated || FxShaders.sliceScene == null || this.slashStrings.isEmpty()) {
         return;
      }
      float post = this.age(frame.partialTick) - this.detonateAt;
      if (post < 0.0F || post >= 9.5F) {
         return;
      }
      float att = this.blastAttenuation(frame);
      if (att <= 0.02F) {
         return;
      }

      // 切屏曲线：0 ~ 1.8t 迅速劈开 → 1.8 ~ 9.5t 清冽合拢
      float snap = post < 1.6F ? FxMath.easeOutCubic(post / 1.6F) : (1.0F - FxMath.easeInCubic(FxMath.window(post, 1.6F, 9.5F)));
      if (snap <= 0.01F) {
         return;
      }

      List<SliceLine> candidates = new ArrayList<>();
      for (SlashString line : this.slashStrings) {
         if (post < line.delay) {
            continue;
         }
         float[] uvA = frame.screenUv(line.a);
         float[] uvB = frame.screenUv(line.b);
         if (uvA == null || uvB == null) {
            continue;
         }
         float dx = uvB[0] - uvA[0];
         float dy = uvB[1] - uvA[1];
         float len = (float)Math.sqrt(dx * dx + dy * dy);
         if (len < 0.02F) {
            continue;
         }
         float midX = (uvA[0] + uvB[0]) * 0.5F - 0.5F;
         float midY = (uvA[1] + uvB[1]) * 0.5F - 0.5F;
         float centerWeight = 1.0F / (1.0F + 2.4F * (midX * midX + midY * midY));
         float score = len * centerWeight * (line.major ? 1.55F : 1.0F);
         candidates.add(new SliceLine(uvA[0], uvA[1], uvB[0], uvB[1], line.slipSign, line.major, score, Math.min(1.0F, len * 3.4F)));
      }
      if (candidates.isEmpty()) {
         return;
      }

      candidates.sort(Comparator.comparingDouble((SliceLine l) -> l.score).reversed());
      int count = Math.min(MAX_SLICES, candidates.size());
      float[][] packed = new float[4][16];
      boolean fullWindow = post < 5.8F;
      for (int i = 0; i < count; i++) {
         SliceLine l = candidates.get(i);
         boolean infinite = fullWindow && i < 4;
         float slip = (infinite ? 0.036F : 0.015F) * snap * l.slipSign * att * l.influence;
         float width = infinite ? 0.0030F : 0.0022F;
         float seam = (infinite ? 0.96F : 0.62F) * snap * att * l.influence;
         float[] m = packed[i / 2];
         int base = (i % 2) * 8;
         m[base] = l.ax;
         m[base + 1] = l.ay;
         m[base + 2] = l.bx;
         m[base + 3] = l.by;
         m[base + 4] = slip;
         m[base + 5] = width;
         m[base + 6] = infinite ? 1.0F : 0.0F;
         m[base + 7] = seam;
      }

      Matrix4f cutsA = new Matrix4f().set(packed[0]);
      Matrix4f cutsB = new Matrix4f().set(packed[1]);
      Matrix4f cutsC = new Matrix4f().set(packed[2]);
      Matrix4f cutsD = new Matrix4f().set(packed[3]);
      float gap = fullWindow ? 0.88F : 0.55F;
      out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.sliceScene, shader -> {
         shader.safeGetUniform("CutsA").set(cutsA);
         shader.safeGetUniform("CutsB").set(cutsB);
         shader.safeGetUniform("CutsC").set(cutsC);
         shader.safeGetUniform("CutsD").set(cutsD);
         shader.safeGetUniform("Slice").set((float)count, gap, 1.0F, 0.0F);
      }));
   }

   // ───────────────────────────── 屏幕后处理与镜头震动（短促凌厉、高对比不糊屏） ─────────────────────────────

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      if (this.cancelledAt >= 0 && !this.detonated) {
         return;
      }
      float a = this.age(frame.partialTick);
      float att = this.blastAttenuation(frame);
      if (att <= 0.01F) {
         return;
      }
      float[] focusUv = frame.screenUv(this.focalAnchor);

      // 1. 第三幕临界收弦吸气（48 ~ 56 tick）：轻深紫暗角 + 微去色 + FOV 轻缩
      if (!this.detonated && a >= COMPRESS_START && a < this.detonateAt) {
         float k = FxMath.easeInCubic(FxMath.window(a, COMPRESS_START, this.detonateAt));
         screen.addVignette(0x140426, 0.34F * k * att);
         screen.addDesaturate(0.24F * k * att);
         screen.addAberration(0.28F * k * att);
         screen.addFovKick(-0.06F * k * att);
      }

      // 2. 第四幕齐爆斩绝：1.8 tick 凌厉反相定格 → 细腻紫白颗粒闪 + 中心清晰向心速度线 + FOV 弹射
      if (this.detonated) {
         float t = a - this.detonateAt;
         if (t >= 0.0F && t < 1.8F) {
            screen.addImpactFrame(att * 0.92F, t >= 0.7F && t < 1.45F);
         }
         if (t >= 1.8F && t < 8.5F) {
            float f = 1.0F - (t - 1.8F) / 6.7F;
            screen.addFlash(0xEFE2FF, 0.52F * f * f * att, 0.78F);
         }
         if (t >= 0.0F && t < 13.0F) {
            float decay = 1.0F - t / 13.0F;
            screen.addAberration(0.95F * decay * att);
            screen.addDashBlur(0.85F * decay * att, focusUv);
            screen.addBloomBoost(0.88F * Math.max(0.0F, 1.0F - t / 10.0F) * att);
            float kick = t < 2.0F ? FxMath.easeOutCubic(t / 2.0F) : (1.0F - FxMath.easeInOutSine(FxMath.window(t, 2.0F, 12.0F)));
            screen.addFovKick(0.11F * kick * att);
         }
      }
   }

   @Override
   public float shake(FxFrame frame) {
      if (this.cancelledAt >= 0 && !this.detonated) {
         return 0.0F;
      }
      float a = this.age(frame.partialTick);
      float att = this.blastAttenuation(frame);
      float value = 0.0F;
      // 第一幕解离瞬间微震
      if (a < 4.5F) {
         double castDist = frame.camera.distanceTo(this.casterAnchor);
         float cAtt = (float)Math.max(0.0, 1.0 - castDist / 24.0);
         value = Math.max(value, 0.18F * (1.0F - a / 4.5F) * cAtt);
      }
      // 第三幕临界收弦微颤
      if (!this.detonated && a >= COMPRESS_START && a < this.detonateAt) {
         float k = FxMath.easeInCubic(FxMath.window(a, COMPRESS_START, this.detonateAt));
         value = Math.max(value, 0.16F * k * att);
      }
      // 第四幕斩绝齐爆主震（高弹干净）
      if (this.detonated) {
         float t = a - this.detonateAt;
         if (t >= 0.0F && t < 14.0F) {
            float k = 1.0F - t / 14.0F;
            value = Math.max(value, 0.72F * k * k * att);
         }
      }
      return value;
   }

   // ───────────────────────────── 工具与内部数据结构 ─────────────────────────────

   private static float clamp(float v, float min, float max) {
      return Math.max(min, Math.min(max, v));
   }

   private static int clampInt(int v, int min, int max) {
      return Math.max(min, Math.min(max, v));
   }

   private static double clamp01(double v) {
      return Math.max(0.0, Math.min(1.0, v));
   }

   private static final class TargetAnchor {
      final int entityId;
      final float lockAt;
      final double yawOffset;
      Vec3 feet;
      Vec3 center;
      float width;
      float height;

      TargetAnchor(int entityId, Vec3 feet, float width, float height, float lockAt, double yawOffset) {
         this.entityId = entityId;
         this.feet = feet;
         this.width = width;
         this.height = height;
         this.center = feet.add(0.0, height * 0.5, 0.0);
         this.lockAt = lockAt;
         this.yawOffset = yawOffset;
      }

      void updateFromEntity(Entity entity) {
         this.feet = entity.position();
         this.width = Math.max(0.6F, entity.getBbWidth());
         this.height = Math.max(0.8F, entity.getBbHeight());
         this.center = this.feet.add(0.0, this.height * 0.5, 0.0);
      }

      Vec3 top() {
         return this.feet.add(0.0, this.height, 0.0);
      }
   }

   private static final class ShardUnit {
      final int id;
      final int role;
      final int parentIdx;
      final int slot;
      final int targetIdx;
      final Vec3 unsheathePos;
      final int spawnTick;
      final int detachTick;
      final int arriveTick;
      final int motionMode;
      final int orbitLayer;
      final double orbitPhase;
      final double orbitOmega;
      final double motionFreq;
      final double motionPhase;
      final double arcScale;
      final double liftAmp;
      final float frontLen;
      final float backLen;
      final float halfWidth;
      final float halfThick;
      final float rollPhase;
      final float rollSpeed;
      final float[] color;

      final Vec3[] trailRing;
      int trailHead = 0;
      int trailCount = 0;

      boolean active = false;
      boolean pursueStarted = false;
      Vec3 pursueStartPos;
      int pursueStartTick;

      double orbitAngle;
      Vec3 pos;
      Vec3 prevPos;
      Vec3 vel = Vec3.ZERO;
      Vec3 dir = new Vec3(0.0, 0.0, 1.0);

      int burstAt = -1;
      int recallAt = Integer.MAX_VALUE;
      int recallDur = 12;
      boolean recallStarted = false;
      boolean recalled = false;
      Vec3 recallStartPos = Vec3.ZERO;
      Vec3 recallControlOffset = Vec3.ZERO;

      ShardUnit(
         int id, int role, int parentIdx, int slot, int targetIdx,
         Vec3 initPos, Vec3 unsheathePos,
         int spawnTick, int detachTick, int arriveTick,
         int motionMode, int orbitLayer, double orbitPhase, double orbitOmega,
         float frontLen, float backLen, float halfWidth, float halfThick,
         int trailCapacity, float[] color, RandomSource rng
      ) {
         this.id = id;
         this.role = role;
         this.parentIdx = parentIdx;
         this.slot = slot;
         this.targetIdx = targetIdx;
         this.unsheathePos = unsheathePos;
         this.spawnTick = spawnTick;
         this.detachTick = detachTick;
         this.arriveTick = arriveTick;
         this.motionMode = motionMode;
         this.orbitLayer = orbitLayer;
         this.orbitPhase = orbitPhase;
         this.orbitAngle = orbitPhase;
         this.orbitOmega = orbitOmega;
         this.motionFreq = 0.24 + rng.nextDouble() * 0.22;
         this.motionPhase = rng.nextDouble() * FxMath.TAU;
         this.arcScale = 0.72 + rng.nextDouble() * 0.78;
         this.liftAmp = (rng.nextDouble() - 0.18) * 3.2;
         this.frontLen = frontLen;
         this.backLen = backLen;
         this.halfWidth = halfWidth;
         this.halfThick = halfThick;
         this.rollPhase = rng.nextFloat() * FxMath.TAU;
         this.rollSpeed = (rng.nextBoolean() ? 1.0F : -1.0F) * (0.14F + rng.nextFloat() * 0.22F);
         this.color = color;
         this.pos = initPos;
         this.prevPos = initPos;
         this.pursueStartPos = initPos;
         this.trailRing = new Vec3[trailCapacity];
         this.resetTrail(initPos);
      }

      void resetTrail(Vec3 origin) {
         this.trailHead = 0;
         this.trailCount = 1;
         this.trailRing[0] = origin;
      }

      void pushTrail(Vec3 p) {
         if (this.trailCount > 0 && this.trailRing[this.trailHead].distanceToSqr(p) < 1.0E-5) {
            return;
         }
         this.trailHead = (this.trailHead + 1) % this.trailRing.length;
         this.trailRing[this.trailHead] = p;
         if (this.trailCount < this.trailRing.length) {
            this.trailCount++;
         }
      }

      Vec3 trailAt(int ageIndex) {
         int idx = this.trailHead - ageIndex;
         if (idx < 0) {
            idx += this.trailRing.length;
         }
         return this.trailRing[idx];
      }

      Vec3 interpolatedPos(float partialTick) {
         return new Vec3(
            this.prevPos.x + (this.pos.x - this.prevPos.x) * partialTick,
            this.prevPos.y + (this.pos.y - this.prevPos.y) * partialTick,
            this.prevPos.z + (this.pos.z - this.prevPos.z) * partialTick
         );
      }
   }

   private static final class SplitPulse {
      final Vec3 pos;
      final float[] color;
      final float size;
      final int maxAge;
      int age;

      SplitPulse(Vec3 pos, float[] color, float size, int maxAge) {
         this.pos = pos;
         this.color = color;
         this.size = size;
         this.maxAge = maxAge;
      }
   }

   private record SlashString(Vec3 a, Vec3 b, float delay, boolean major, float slipSign, float weight, float[] color) {
   }

   private record BurstArc(Vec3 center, Vector3f u, Vector3f v, float innerR, float outerR, float startAng, float endAng, float delay, float duration, float[] color) {
   }

   private record ResonanceChord(Vec3 a, Vec3 b, float delay, float[] color) {
   }

   private record SliceLine(float ax, float ay, float bx, float by, float slipSign, boolean major, float score, float influence) {
   }

   private static final class Spark {
      double x;
      double y;
      double z;
      double px;
      double py;
      double pz;
      double vx;
      double vy;
      double vz;
      int life;
      final int maxLife;
      final float[] color;
      final float accelY;

      Spark(Vec3 origin, Vector3f velocity, int life, float[] color, float accelY) {
         this.x = this.px = origin.x;
         this.y = this.py = origin.y;
         this.z = this.pz = origin.z;
         this.vx = velocity.x;
         this.vy = velocity.y;
         this.vz = velocity.z;
         this.life = life;
         this.maxLife = life;
         this.color = color;
         this.accelY = accelY;
      }

      static Spark burst(Vec3 origin, RandomSource random, float speed, int life, float[] color, float accelY) {
         return new Spark(origin, FxMath.randomUnit(random).mul(speed), life, color, accelY);
      }

      void tick() {
         this.px = this.x;
         this.py = this.y;
         this.pz = this.z;
         this.x += this.vx;
         this.y += this.vy;
         this.z += this.vz;
         this.vx *= 0.88;
         this.vy = this.vy * 0.88 + this.accelY;
         this.vz *= 0.88;
         this.life--;
      }

      Vec3 position(float partialTick) {
         return new Vec3(
            this.px + (this.x - this.px) * partialTick,
            this.py + (this.y - this.py) * partialTick,
            this.pz + (this.z - this.pz) * partialTick
         );
      }
   }
}
