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

   /** 裂界神威阶段（intParam 低 4 位）：独立模式开启首个单链节点（活体定身扭曲）。 */
   public static final int RIFT_SINGLE_START = 0;
   /** 裂界神威阶段（intParam 低 4 位）：独立模式当前节点临界拧断处决。 */
   public static final int RIFT_SINGLE_SNAP = 1;
   /** 裂界神威阶段（intParam 低 4 位）：独立模式跃迁至下一个最近目标并开始扭曲。 */
   public static final int RIFT_SINGLE_HOP = 2;
   /** 裂界神威阶段（intParam 低 4 位）：断空联动预抓拍（在断空处决目标前让客户端静默捕获活体模型）。 */
   public static final int RIFT_LINK_PREPARE = 3;
   /** 裂界神威阶段（intParam 低 4 位）：断空收刀齐斩完毕，同步引爆所有已斩杀目标残骸的神威扭杀。 */
   public static final int RIFT_LINK_UNLEASH = 4;

   /** 裂界神威阶段附加标记位：后方紧跟因果坍缩接管余威裂痕（跳过合缝回弹，直接坍缩成裂痕）。 */
   public static final int RIFT_FLAG_CAUSALITY_HANDOFF = 1 << 16;

   /** 打包裂界 intParam：bits 0..3 = 阶段种类 | bits 4..9 = 跳序号 | bits 10..15 = 最大链长 | bit 16 = 因果接管标记。 */
   public static int packWorldRift(int kind, int hopIndex, int maxChain) {
      return packWorldRift(kind, hopIndex, maxChain, false);
   }

   public static int packWorldRift(int kind, int hopIndex, int maxChain, boolean causalityHandoff) {
      int packed = (kind & 0xF)
         | ((Math.max(0, Math.min(63, hopIndex)) & 0x3F) << 4)
         | ((Math.max(1, Math.min(63, maxChain)) & 0x3F) << 10);
      return causalityHandoff ? (packed | RIFT_FLAG_CAUSALITY_HANDOFF) : packed;
   }

   public static int worldRiftKind(int packed) {
      return packed & 0xF;
   }

   public static int worldRiftHop(int packed) {
      return (packed >> 4) & 0x3F;
   }

   public static int worldRiftMaxChain(int packed) {
      return Math.max(1, (packed >> 10) & 0x3F);
   }

   public static boolean worldRiftCausalityHandoff(int packed) {
      return (packed & RIFT_FLAG_CAUSALITY_HANDOFF) != 0;
   }

   /**
    * 开启裂界·神威持续特效（独立单链开眼或断空联动预抓拍）。
    */
   public static int worldRiftStart(
      ServerLevel level, Player attacker, Vec3 origin, Vec3 direction, double radius, double visualScale,
      int kind, int hopIndex, int maxChain, long seed, int[] entityIds, int[] targetTicks, int duration
   ) {
      return worldRiftStart(level, attacker, origin, direction, radius, visualScale, kind, hopIndex, maxChain, false, seed, entityIds, targetTicks, duration);
   }

   public static int worldRiftStart(
      ServerLevel level, Player attacker, Vec3 origin, Vec3 direction, double radius, double visualScale,
      int kind, int hopIndex, int maxChain, boolean causalityHandoff, long seed, int[] entityIds, int[] targetTicks, int duration
   ) {
      return FxSustained.start(level, new FxEventPacket(
         FxTypes.WORLD_RIFT,
         origin,
         direction,
         (float)radius,
         (float)visualScale,
         packWorldRift(kind, hopIndex, maxChain, causalityHandoff),
         seed,
         attacker.getId(),
         entityIds,
         targetTicks
      ), duration);
   }

   /**
    * 更新裂界·神威持续特效（拧断拍、单链跃迁、断空预抓拍追加或断空残骸爆发）。
    */
   public static void worldRiftUpdate(
      int handle, ServerLevel level, Player attacker, Vec3 origin, Vec3 direction, double radius, double visualScale,
      int kind, int hopIndex, int maxChain, long seed, int[] entityIds, int[] targetTicks
   ) {
      worldRiftUpdate(handle, level, attacker, origin, direction, radius, visualScale, kind, hopIndex, maxChain, false, seed, entityIds, targetTicks);
   }

   public static void worldRiftUpdate(
      int handle, ServerLevel level, Player attacker, Vec3 origin, Vec3 direction, double radius, double visualScale,
      int kind, int hopIndex, int maxChain, boolean causalityHandoff, long seed, int[] entityIds, int[] targetTicks
   ) {
      FxSustained.update(handle, new FxEventPacket(
         FxTypes.WORLD_RIFT,
         origin,
         direction,
         (float)radius,
         (float)visualScale,
         packWorldRift(kind, hopIndex, maxChain, causalityHandoff),
         seed,
         attacker.getId(),
         entityIds,
         targetTicks
      ));
   }

   /** 结束裂界·神威持续特效句柄；客户端会继续演完当前已开启奇点的缝合尾声，未引爆的预抓拍则静默销毁。 */
   public static void worldRiftEnd(int handle) {
      FxSustained.stop(handle);
   }

   /** 因果坍缩模式：独立挥刀单道因果裂痕（含智能准星索敌与空挥）。 */
   public static final int CAUSALITY_MODE_STANDALONE = 0;
   /** 因果坍缩模式：断空 + 神威群体联动全战场裂痕群。 */
   public static final int CAUSALITY_MODE_LINKED_GROUP = 1;
   /** 因果坍缩模式：神威独立单链逐目标原位裂痕。 */
   public static final int CAUSALITY_MODE_LINKED_SINGLE = 2;
   /** 因果坍缩模式：裂痕核心吞噬并空间绞杀目标瞬间同步事件（必须在 executeSilent 之前发送）。 */
   public static final int CAUSALITY_MODE_DEVOUR = 3;

   /** 打包因果坍缩 intParam：bits 0..1 = 模式 | bits 4..11 = 单道裂痕存续 tick（20..200）。 */
   public static int packCausality(int mode, int scarDurationTicks) {
      int clamped = Math.max(20, Math.min(200, scarDurationTicks));
      return (mode & 0x3) | ((clamped & 0xFF) << 4);
   }

   public static int causalityMode(int packed) {
      return packed & 0x3;
   }

   public static int causalityScarDuration(int packed) {
      int duration = (packed >> 4) & 0xFF;
      return Math.max(20, Math.min(200, duration == 0 ? 64 : duration));
   }

   /**
    * 下发一批局部 UV 错位因果裂痕（客户端自动并入同一池化实例，单 Pass 渲染，不互相打断）。
    * entityIds[] 与 targetTicks[] 复用承载量化编码的 (dx, dz) 与 (dy, heightDeci, staggerTick)。
    */
   public static void causalityCollapseScars(
      ServerLevel level, Player attacker, Vec3 origin, Vec3 direction, double envelopeRadius, double visualScale,
      int mode, int scarDurationTicks, long seed, int[] packedXZ, int[] packedYMeta
   ) {
      FxEvents.send(level, new FxEventPacket(
         FxTypes.CAUSALITY_COLLAPSE,
         origin,
         direction,
         (float)envelopeRadius,
         (float)visualScale,
         packCausality(mode, scarDurationTicks),
         seed,
         attacker.getId(),
         packedXZ,
         packedYMeta
      ));
   }

   /**
    * 下发单次裂痕核心空间绞杀吞噬事件（必须在 {@code TerminusLogic.executeSilent} 之前发送，以便客户端抓拍活体 3D 模型）。
    * origin = 裂痕中心，direction = 被绞杀生物实时中心，radius = 生物碰撞箱宽度，scale = 视觉倍率，
    * entityIds = [目标实体 ID]，targetTicks = [目标高度 × 10]。
    */
   public static void causalityCollapseDevour(
      ServerLevel level, Player attacker, Vec3 scarCenter, LivingEntity target, double visualScale
   ) {
      Vec3 targetCenter = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
      int heightDeci = Math.max(6, Math.min(120, Math.round(target.getBbHeight() * 10.0F)));
      FxEvents.send(level, new FxEventPacket(
         FxTypes.CAUSALITY_COLLAPSE,
         scarCenter,
         targetCenter,
         Math.max(0.5F, target.getBbWidth()),
         (float)visualScale,
         packCausality(CAUSALITY_MODE_DEVOUR, 20),
         level.getRandom().nextLong(),
         attacker.getId(),
         new int[]{target.getId()},
         new int[]{heightDeci}
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

   /** 归墟回响事件种类（intParam 低 4 位）：受击启动偏折镜、因果逆溯与虚影具现（持续 START）。 */
   public static final int ECHO_START = 0;
   /** 归墟回响事件种类（intParam 低 4 位）：抵达处决拍点，同步最新目标坐标与活体抓拍（持续 UPDATE）。 */
   public static final int ECHO_EXECUTE = 1;
   /** 归墟回响事件种类（intParam 低 4 位）：无合法攻击者或超距时的轻量偏折镜波纹（一次性）。 */
   public static final int ECHO_DEFLECT_ONLY = 2;
   /** 归墟回响标志位：本次受击拦截并吞噬了敌方弹射物。 */
   public static final int ECHO_FLAG_PROJECTILE = 1 << 4;

   /** 打包归墟回响 intParam：bits 0..3 = 种类 | bit 4 = 弹射物标志 | bits 8..15 = 视觉倍率×10（4..30）。 */
   public static int packEcho(int kind, boolean projectileIntercepted, double visualScale) {
      int scaleDeci = (int)Math.max(4, Math.min(30, Math.round(visualScale * 10.0)));
      int packed = (kind & 0xF) | (scaleDeci << 8);
      return projectileIntercepted ? (packed | ECHO_FLAG_PROJECTILE) : packed;
   }

   public static int echoKind(int packed) {
      return packed & 0xF;
   }

   public static boolean echoIsProjectile(int packed) {
      return (packed & ECHO_FLAG_PROJECTILE) != 0;
   }

   public static float echoVisualScale(int packed) {
      int scaleDeci = (packed >> 8) & 0xFF;
      if (scaleDeci <= 0) {
         return 1.0F;
      }
      return Math.max(0.4F, Math.min(3.0F, scaleDeci / 10.0F));
   }

   /**
    * 归墟回响·因果反噬启动（持续特效 START）。
    * origin = 偏折镜中心，direction = 目标中心 − 偏折镜中心，radius = 广播包络，scale = 目标身形高度兜底。
    */
   public static int echoCounterStart(
      ServerLevel level, Player defender, Vec3 mirrorCenter, Vec3 targetCenter,
      double visualScale, boolean projectileIntercepted, LivingEntity target, long seed, int duration
   ) {
      Vec3 delta = targetCenter.subtract(mirrorCenter);
      float broadcastRadius = (float)Math.max(12.0, delta.length() + 16.0);
      return FxSustained.start(level, new FxEventPacket(
         FxTypes.TERMINUS_ECHO,
         mirrorCenter,
         delta,
         broadcastRadius,
         Math.max(0.6F, target.getBbHeight()),
         packEcho(ECHO_START, projectileIntercepted, visualScale),
         seed,
         defender.getId(),
         new int[]{target.getId()}
      ), duration);
   }

   /**
    * 归墟回响·因果反斩同步（持续特效 UPDATE），必须在 {@code TerminusLogic.executeSilent} 之前调用以确保客户端抓拍活体模型。
    * origin = 目标最新实时胸口坐标，direction = 残影斩击方向，radius = 目标宽，scale = 目标高。
    */
   public static void echoCounterExecute(
      int handle, ServerLevel level, Player defender, Vec3 targetCenter, Vec3 slashDir,
      double visualScale, boolean projectileIntercepted, LivingEntity target, long seed
   ) {
      FxSustained.update(handle, new FxEventPacket(
         FxTypes.TERMINUS_ECHO,
         targetCenter,
         slashDir,
         Math.max(0.6F, target.getBbWidth()),
         Math.max(0.6F, target.getBbHeight()),
         packEcho(ECHO_EXECUTE, projectileIntercepted, visualScale),
         seed,
         defender.getId(),
         new int[]{target.getId()}
      ));
   }

   /**
    * 归墟回响·仅偏折镜波纹（一次性事件）：用于无攻击者环境伤害或超距攻击被吸收时的轻量镜盾反馈。
    */
   public static void echoDeflectOnly(
      ServerLevel level, Player defender, Vec3 mirrorCenter, Vec3 incomingDir,
      double visualScale, boolean projectileIntercepted
   ) {
      FxEvents.send(level, new FxEventPacket(
         FxTypes.TERMINUS_ECHO,
         mirrorCenter,
         incomingDir,
         6.0F,
         1.8F,
         packEcho(ECHO_DEFLECT_ONLY, projectileIntercepted, visualScale),
         level.getRandom().nextLong(),
         defender.getId(),
         new int[0]
      ));
   }

   /** 归墟回响结束（持续特效 STOP）：已触发 ECHO_EXECUTE 时客户端继续播完余韵；未处决时平滑淡出。 */
   public static void echoEnd(int handle) {
      FxSustained.stop(handle);
   }

   /** 断空收刀（持续特效 STOP）：客户端播放「納刀」。 */
   public static void dankongFinish(int handle) {
      FxSustained.stop(handle);
   }
}
