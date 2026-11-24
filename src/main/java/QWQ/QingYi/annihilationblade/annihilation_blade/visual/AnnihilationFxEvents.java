package QWQ.QingYi.annihilationblade.annihilation_blade.visual;

import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.render.FxEvents;
import QWQ.QingYi.annihilationblade.render.FxSustained;
import QWQ.QingYi.annihilationblade.render.FxTypes;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 湮灭之刃特效事件的服务端发送入口：只描述「发生了什么」，表现全部交给客户端渲染器。
 * <p>
 * 所有带目标的事件都必须在 {@code TerminusLogic.execute} 之前调用，客户端才能解析到尚未移除的实体。
 */
public final class AnnihilationFxEvents {
   /** 断空事件种类（intParam 低 4 位）：开场断面门。 */
   public static final int DANKONG_OPEN = 0;
   /** 断空事件种类（intParam 低 4 位）：一次闪现斩击，高位为斩序号。 */
   public static final int DANKONG_STRIKE = 1;

   /** 虚无权域持续阶段：宣言与领域展开。 */
   public static final int VOID_PHASE_START = 0;
   /** 虚无权域持续阶段：实体编目与冻结。 */
   public static final int VOID_PHASE_CATALOG = 1;
   /** 虚无权域持续阶段：球形扫描处决。 */
   public static final int VOID_PHASE_SWEEP = 2;
   /** 虚无权域持续阶段：全域归零与撤场。 */
   public static final int VOID_PHASE_COLLAPSE = 3;
   /** 虚无权域快照标志位：本次更新包含波前/坍缩真实处决离场的目标，避免意外死亡误播删档演出。 */
   public static final int VOID_FLAG_ERASE = 1 << 4;

   /** 解包虚无权域阶段编号（低 4 位）。 */
   public static int voidPhase(int intParam) {
      return intParam & 0xF;
   }

   /** 解包虚无权域本次更新是否触发处决离场表现。 */
   public static boolean voidHasErase(int intParam) {
      return (intParam & VOID_FLAG_ERASE) != 0;
   }

   private AnnihilationFxEvents() {
   }

   /**
    * 断空开场（持续特效 START）。origin = 起点胸口，direction = 视线，radius = 搜索范围（用于补发范围），scale = 视觉倍率，
    * entityIds = 本次锁定的全部目标（客户端为每个目标挂上「死兆星」）。
    *
    * @return 持续特效句柄
    */
   public static int dankongOpen(ServerLevel level, Player player, Vec3 chest, double range, double visualScale, int duration, int[] targetIds) {
      return FxSustained.start(level, new FxEventPacket(
         FxTypes.DANKONG,
         chest,
         player.getLookAngle(),
         (float)range,
         (float)visualScale,
         DANKONG_OPEN,
         level.getRandom().nextLong(),
         player.getId(),
         targetIds
      ), duration);
   }

   /**
    * 断空单次闪现（持续特效 UPDATE），必须在本次斩击伤害之前调用。
    * origin = 落点胸口，direction = 落点 − 出发点，radius = 目标体高（兜底），scale = 视觉倍率。
    */
   public static void dankongStrike(int handle, ServerLevel level, Player player, Vec3 fromChest, Vec3 toChest, LivingEntity target, int index, double visualScale) {
      FxSustained.update(handle, new FxEventPacket(
         FxTypes.DANKONG,
         toChest,
         toChest.subtract(fromChest),
         target.getBbHeight(),
         (float)visualScale,
         DANKONG_STRIKE | (index << 4),
         level.getRandom().nextLong(),
         player.getId(),
         new int[]{target.getId()}
      ));
   }

   /**
    * 裂界：伤口在 center 撕开并把 targets 拖入，必须在处决这些目标之前调用。
    *
    * @param depth    当前连锁层数（从 1 开始）
    * @param maxDepth 最大连锁层数（客户端据此决定是否画出向下一层蔓延的伤痕）
    */
   public static void worldRift(ServerLevel level, Player attacker, Vec3 center, double radius, double visualScale, int depth, int maxDepth, List<LivingEntity> targets) {
      int count = Math.min(targets.size(), FxEventPacket.MAX_ENTITIES);
      int[] ids = new int[count];
      for (int i = 0; i < count; i++) {
         ids[i] = targets.get(i).getId();
      }
      FxEvents.send(level, new FxEventPacket(
         FxTypes.WORLD_RIFT,
         center,
         Vec3.ZERO,
         (float)(radius * visualScale),
         (float)visualScale,
         (depth & 0xF) | ((maxDepth & 0xF) << 4),
         level.getRandom().nextLong(),
         attacker.getId(),
         ids
      ));
   }

   /** 因果坍缩：从 start 出发按顺序串起 chain，必须在处决链上目标之前调用。 */
   public static void causalityCollapse(ServerLevel level, Player attacker, Vec3 start, double radius, double visualScale, List<LivingEntity> chain) {
      int count = Math.min(chain.size(), FxEventPacket.MAX_ENTITIES);
      int[] ids = new int[count];
      for (int i = 0; i < count; i++) {
         ids[i] = chain.get(i).getId();
      }
      FxEvents.send(level, new FxEventPacket(
         FxTypes.CAUSALITY_COLLAPSE,
         start,
         attacker.getLookAngle(),
         (float)(radius * visualScale),
         (float)visualScale,
         count,
         level.getRandom().nextLong(),
         attacker.getId(),
         ids
      ));
   }

   /** 虚无权域持续事件开始。 */
   public static int voidDominionStart(ServerLevel level, Player player, Vec3 center, double range, double visualScale, int[] targetIds, int[] targetTicks, long seed, int duration) {
      return FxSustained.start(level, voidDominionPacket(level, player, center, range, visualScale, VOID_PHASE_START, targetIds, targetTicks, seed), duration);
   }

   /** 虚无权域时间轴阶段更新，targetTicks 与 entityIds 按索引一一对应。 */
   public static void voidDominionUpdate(int handle, ServerLevel level, Player player, Vec3 center, double range, double visualScale,
                                         int phase, int[] targetIds, int[] targetTicks, long seed) {
      FxSustained.update(handle, voidDominionPacket(level, player, center, range, visualScale, phase, targetIds, targetTicks, seed));
   }

   /** 虚无权域服务端阶段结束，客户端依阶段决定完成收束或异常撤域。 */
   public static void voidDominionEnd(int handle) {
      FxSustained.stop(handle);
   }

   private static FxEventPacket voidDominionPacket(ServerLevel level, Player player, Vec3 center, double range, double visualScale,
                                                   int phase, int[] targetIds, int[] targetTicks, long seed) {
      return new FxEventPacket(
         FxTypes.VOID_DOMINION, center, player.getLookAngle(), (float)range, (float)visualScale,
         phase, seed, player.getId(), targetIds, targetTicks
      );
   }

   /** 幻影审判事件种类：千刃解离启阵（START）。 */
   public static final int PHANTOM_START = 0;
   /** 幻影审判事件种类：千刃碎破齐爆斩绝（UPDATE，在处决目标前发送）。 */
   public static final int PHANTOM_DETONATE = 1;

   /**
    * 幻影审判参数位打包：
    * bits 0..4 = 母刃数(4..16) | bits 5..7 = 每母刃子刃数(0..6) | bits 8..10 = 每子刃追踪刃数(0..4)
    * | bits 11..18 = 总碎刃硬上限(24..200) | bits 19..23 = 单目标斩绝线数(4..16) | bits 24..25 = 事件种类。
    */
   public static int packPhantom(int kind) {
      ModConfig.PhantomJudgement config = ModConfig.COMMON.annihilationBlade.phantomJudgement;
      int masters = Math.max(1, Math.min(31, config.swordCount.getValue()));
      int subs = Math.max(0, Math.min(7, config.subShardsPerMaster.getValue()));
      int trackers = Math.max(0, Math.min(7, config.trackerShardsPerSub.getValue()));
      int maxTotal = Math.max(24, Math.min(255, config.maxTotalShards.getValue()));
      int strings = Math.max(4, Math.min(31, config.slashStringsPerTarget.getValue()));
      return masters | (subs << 5) | (trackers << 8) | (maxTotal << 11) | (strings << 19) | ((kind & 0x3) << 24);
   }

   public static int phantomMasterCount(int packed) {
      return Math.max(1, packed & 0x1F);
   }

   public static int phantomSubPerMaster(int packed) {
      return (packed >> 5) & 0x7;
   }

   public static int phantomTrackerPerSub(int packed) {
      return (packed >> 8) & 0x7;
   }

   public static int phantomMaxTotalShards(int packed) {
      return Math.max(24, (packed >> 11) & 0xFF);
   }

   public static int phantomStringsPerTarget(int packed) {
      return Math.max(4, (packed >> 19) & 0x1F);
   }

   public static int phantomKind(int packed) {
      return (packed >> 24) & 0x3;
   }

   /** 高 32 位为共享随机种子，低 24 位为刀色 RGB。 */
   private static long phantomSeed(ServerLevel level, int color) {
      return ((long)level.getRandom().nextInt() << 32) | (color & 0xFFFFFFL);
   }

   /** 幻影审判·千刃星流开始（持续特效 START）：origin = 施法者中心，direction = 视线，lockedIds = 锁定目标。 */
   public static int phantomStart(ServerLevel level, Player player, Vec3 center, int color, int[] lockedIds) {
      ModConfig.PhantomJudgement config = ModConfig.COMMON.annihilationBlade.phantomJudgement;
      int duration = QWQ.QingYi.annihilationblade.annihilation_blade.logic.PhantomJudgementPattern.DETONATE_TICK + 6;
      return FxSustained.start(level, new FxEventPacket(
         FxTypes.PHANTOM_JUDGEMENT, center, player.getLookAngle(), config.range.getValue().floatValue(), config.visualScale.getValue().floatValue(),
         packPhantom(PHANTOM_START), phantomSeed(level, color), player.getId(), lockedIds
      ), duration);
   }

   /** 幻影审判·千刃碎破齐爆斩绝（持续特效 UPDATE），必须在处决目标之前调用。 */
   public static void phantomDetonate(int handle, ServerLevel level, Player player, Vec3 center, int color, List<LivingEntity> targets) {
      ModConfig.PhantomJudgement config = ModConfig.COMMON.annihilationBlade.phantomJudgement;
      int size = Math.min(targets.size(), FxEventPacket.MAX_ENTITIES);
      int[] ids = new int[size];
      for (int i = 0; i < size; i++) {
         ids[i] = targets.get(i).getId();
      }
      FxSustained.update(handle, new FxEventPacket(
         FxTypes.PHANTOM_JUDGEMENT, center, player.getLookAngle(), config.range.getValue().floatValue(), config.visualScale.getValue().floatValue(),
         packPhantom(PHANTOM_DETONATE), phantomSeed(level, color), player.getId(), ids
      ));
   }

   /** 幻影审判结束（持续特效 STOP）：已齐爆时客户端继续播放斩绝余韵与百刃归鞘直至结束；未齐爆则提前平滑消散。 */
   public static void phantomEnd(int handle) {
      FxSustained.stop(handle);
   }

   /** 星寂裁决：沿 direction 劈开天幕并审判 targets（按投影由近到远），必须在处决之前调用。 */
   public static void starlessJudgement(ServerLevel level, Player player, Vec3 start, Vec3 direction, double range, double width, double visualScale, List<LivingEntity> targets) {
      int size = Math.min(targets.size(), FxEventPacket.MAX_ENTITIES);
      int[] ids = new int[size];
      for (int i = 0; i < size; i++) {
         ids[i] = targets.get(i).getId();
      }
      int scale = (int)Math.max(1, Math.min(255, Math.round(visualScale * 10.0)));
      FxEvents.send(level, new FxEventPacket(
         FxTypes.STARLESS_JUDGEMENT, start, direction, (float)range, (float)width, scale, level.getRandom().nextLong(), player.getId(), ids
      ));
   }

   /** 归墟回响：打包 intParam = 波序号 | 总波数 << 4 | 波间隔 << 8 | 视觉倍率×10 << 12。 */
   public static int packEcho(int wave, int count, int interval, double visualScale) {
      int scale = (int)Math.max(1, Math.min(255, Math.round(visualScale * 10.0)));
      return (wave & 0xF) | ((count & 0xF) << 4) | ((interval & 0xF) << 8) | (scale << 12);
   }

   private static FxEventPacket echoPacket(ServerLevel level, Player player, Vec3 sequenceStart, Vec3 direction, double baseRange, double baseWidth,
                                           int wave, int count, int interval, double visualScale, List<LivingEntity> targets) {
      int size = Math.min(targets.size(), FxEventPacket.MAX_ENTITIES);
      int[] ids = new int[size];
      for (int i = 0; i < size; i++) {
         ids[i] = targets.get(i).getId();
      }
      return new FxEventPacket(
         FxTypes.TERMINUS_ECHO, sequenceStart, direction, (float)baseRange, (float)baseWidth,
         packEcho(wave, count, interval, visualScale), level.getRandom().nextLong(), player.getId(), ids
      );
   }

   /** 归墟回响开始（持续特效 START，携带第 0 波）。必须在处决第 0 波目标之前调用。 */
   public static int echoBegin(ServerLevel level, Player player, Vec3 sequenceStart, Vec3 direction, double baseRange, double baseWidth,
                               int count, int interval, double visualScale, List<LivingEntity> targets, int duration) {
      return FxSustained.start(level, echoPacket(level, player, sequenceStart, direction, baseRange, baseWidth, 0, count, interval, visualScale, targets), duration);
   }

   /** 归墟回响后续一波（持续特效 UPDATE）。必须在处决本波目标之前调用。 */
   public static void echoWave(int handle, ServerLevel level, Player player, Vec3 sequenceStart, Vec3 direction, double baseRange, double baseWidth,
                               int wave, int count, int interval, double visualScale, List<LivingEntity> targets) {
      FxSustained.update(handle, echoPacket(level, player, sequenceStart, direction, baseRange, baseWidth, wave, count, interval, visualScale, targets));
   }

   /** 归墟回响结束（持续特效 STOP）：客户端在最后一波走完后回放乐章。 */
   public static void echoEnd(int handle) {
      FxSustained.stop(handle);
   }

   /** 断空收刀（持续特效 STOP）：客户端播放「納刀」。 */
   public static void dankongFinish(int handle) {
      FxSustained.stop(handle);
   }
}
