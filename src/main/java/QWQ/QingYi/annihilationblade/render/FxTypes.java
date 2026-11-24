package QWQ.QingYi.annihilationblade.render;

/**
 * 特效事件类型编号（服务端与客户端共用）。
 * <p>
 * 只允许在末尾追加新编号，已有编号不可复用或改值，否则会造成新旧版本客户端解析错乱。
 */
public final class FxTypes {
   /** 湮灭之刃 SA「空间破碎」。 */
   public static final int SPATIAL_FRACTURE = 1;
   /** 无尽星空 SA「真空衰变·坍缩」（持续特效）。 */
   public static final int VACUUM_DECAY = 2;
   /** 魔龙夜陨 SA「神陨·宇宙夜陨降临」（持续特效）。 */
   public static final int COSMIC_DESCENT = 3;
   /** 血狱 SA「炼狱·杀戮」领域（持续特效）。 */
   public static final int BLOOD_DOMAIN = 4;
   /** 血狱领域内的闪击斩（一次性）。direction 字段携带「终点 - 起点」位移。 */
   public static final int BLOOD_DASH = 5;
   /** 血狱十印幻影剑爆破（一次性）。intParam = 剑数 | 命中延迟 << 8。 */
   public static final int BLOOD_BURST = 6;
   /** 魔龙夜陨 SA「夜陨次元斩」整体领域与终幕（持续特效）。intParam = 总斩数。 */
   public static final int JUDGEMENT_DOMAIN = 7;
   /** 夜陨次元斩单次切痕（一次性）。intParam = 斩序号，seed 低 24 位携带刀色。 */
   public static final int JUDGEMENT_CUT = 8;
   /** 魔龙夜陨 SA「鳞之卫」（一次性，客户端按剑阵公式复现每柄剑的轨迹）。intParam = 剑数。 */
   public static final int SCALE_GUARD = 9;
   /** 萝莉刀审判（一次性）。intParam = 0 锥形（SE）/ 1 范围（SA）；entityIds = 被审判的目标。 */
   public static final int LOLI_JUDGEMENT = 10;
   // 编号 11 原为 TERMINUS_MARK（终焉印记），已移除，按末尾追加规则留空不复用。
   /**
    * 湮灭之刃 SE「断空·瞬狱终闪」（持续特效）。intParam 低 4 位为种类：0 开场 / 1 闪现（高位为斩序号）。
    * 开场：origin = 起点胸口；闪现：origin = 落点胸口，direction = 落点 − 出发点，entityIds = [目标]。scale = 视觉倍率。
    */
   public static final int DANKONG = 12;
   /**
    * 湮灭之刃 SE「裂界·因果伤痕（神威空间扭曲处决）」（持续特效，双模式同步）。
    * intParam 位打包见 {@code AnnihilationFxEvents.packWorldRift}：低 4 位为阶段种类
    * （0 独立单链开眼 / 1 单链拧断处决 / 2 单链跃迁下一目标 / 3 断空联动预抓拍 / 4 断空联动残骸齐爆），
    * 中位为当前链跳序号，高位为最大链长；origin = 奇点中心/施法参考点，direction = 跃迁来源或劈砍方向，
    * radius = 空间扭曲与索敌半径，scale = 视觉倍率，entityIds / targetTicks 携带关联实体与错峰 tick。
    */
   public static final int WORLD_RIFT = 13;
   /**
    * 湮灭之刃 SE「因果坍缩·错位裂痕」（一次性发包 + 客户端自动池化合并）。
    * origin = 参考原点，direction = 施法者视线参考，radius = 裂痕局部覆盖包络，scale = 视觉倍率，
    * intParam 打包模式与存续时长（见 {@code AnnihilationFxEvents.packCausality}），
    * entityIds / targetTicks 按索引成对量化编码每道裂痕相对 origin 的 3D 坐标 (dx, dy, dz)、目标身形高度与错峰起裂 tick。
    */
   public static final int CAUSALITY_COLLAPSE = 14;
   /**
    * 湮灭之刃 SE「归墟回响·终焉余律」（持续特效：START 携带第 0 波，之后每波一个 UPDATE，序列结束 STOP）。
    * origin = 序列起点，direction = 朝向，radius = 基础射程，scale = 基础宽度，
    * intParam = 波序号 | 总波数 << 4 | 波间隔 << 8 | 视觉倍率×10 << 12，entityIds = 本波命中的目标。
    * 每波的实际起点、射程、宽度由 {@code TerminusEchoPattern} 推出。
    */
   public static final int TERMINUS_ECHO = 15;
   /**
    * 湮灭之刃 SE「星寂裁决·断天终判」（一次性）。origin = 判定带起点，direction = 朝向，radius = 射程，scale = 宽度，
    * intParam = 视觉倍率×10，entityIds = 判定带内的目标（按投影由近到远）。
    */
   public static final int STARLESS_JUDGEMENT = 16;
   // 编号 17 原为 ABYSSAL_DECREE（归墟天诏），已移除，按末尾追加规则留空不复用。
   /**
    * 湮灭之刃 SE「幻影审判·千刃星流」（持续特效：解离启阵 START，齐爆斩绝 UPDATE，随后 STOP）。
    * origin = 施法者中心，direction = 视线朝向，radius = 搜索范围，scale = 视觉倍率，
    * seed 高 32 位为共享随机种子 / 低 24 位为刀色，intParam 打包千刃集群与阶段类型（见 {@code AnnihilationFxEvents.packPhantom}），
    * entityIds = 锁定 / 最终处决的目标。时间轴与曲线数学与服务端共用 {@code PhantomJudgementPattern}。
    */
   public static final int PHANTOM_JUDGEMENT = 18;
   /**
    * 湮灭之刃 SE「虚无权域·万象湮灭」（持续特效）。intParam = 阶段；origin = 穹顶中心，direction = 视线，radius = 穹顶半径，
    * scale = 视觉倍率，entityIds = 当前剩余目标，targetTicks 与 entityIds 按索引对应其波前处决 tick。
    */
   public static final int VOID_DOMINION = 19;

   private FxTypes() {
   }
}
