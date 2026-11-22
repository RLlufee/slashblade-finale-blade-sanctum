package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

/**
 * 湮灭之刃 SA「空间破碎·万象裂隙」双端共享演出时间轴常量。
 * <p>
 * 服务端（{@link SpatialFractureExecutor}）与客户端专属渲染（{@code SpatialFractureFx}）
 * 严格共用同一份时间轴节点，确保双环蓄能、光柱贯穿、空间禁锢、奇点坍缩与终极爆轰处决严丝合缝。
 * <pre>
 *   0 ─ 14   第一幕：施法者前方第一重竖直主法阵展开
 *  11 ─ 24   第二幕：更前方第二重竖直聚能环依次展开
 *  20 ─ 33   第三幕：双环反向高速旋转蓄能，29─33 tick 临界收束吸气
 *  33 ─ 39   第四幕：粗虚空光柱穿透双环突射，经 6 tick 贯抵目标点（光柱持续压制至 54 tick）
 *  39 ─ 74   第五幕：光柱命中引爆地面大阵，空间碎裂折射、3D 刀弧与悬停刀痕交错斩切
 *  66 ─ 82   第六幕：万物向中心奇点黑洞倒卷坍缩，主斜劈切线横贯蓄力
 *  82 ─ 136  第七幕：终极绚烂超新星空间爆轰（反相冲击帧 + 全屏齐斩 + 多壳层星云爆球 + 通天光柱 + 落雷 + 慢动作 3D 星晶）与余韵
 * </pre>
 */
public final class SpatialFracturePattern {
   private SpatialFracturePattern() {
   }

   /** 第一重竖直主法阵展开起止 tick。 */
   public static final int RING1_OPEN_START = 0;
   public static final int RING1_OPEN_END = 12;

   /** 第二重竖直聚能环依次展开起止 tick（留出清晰节拍让玩家先看清第一环）。 */
   public static final int RING2_OPEN_START = 11;
   public static final int RING2_OPEN_END = 22;

   /** 双环加速对旋蓄能起止 tick（29~33 为发炮前压缩吸气瞬间）。 */
   public static final int CHARGE_START = 20;
   public static final int COMPRESS_START = 29;

   /** 突发光束射出 tick（穿过第一、第二环爆射）。 */
   public static final int BEAM_FIRE_TICK = 33;
   /** 光束飞抵目标核心并引爆空间破碎场的 tick。 */
   public static final int BEAM_HIT_TICK = 39;
   /** 光柱持续贯穿压制结束 tick。 */
   public static final int BEAM_HOLD_END_TICK = 54;
   /** 炮口双环优雅收束消散窗口。 */
   public static final int GATE_FADE_START = 50;
   public static final int GATE_FADE_END = 62;

   /** 域内空间碎裂展开与刃风暴斩切窗口。 */
   public static final int FIELD_REVEAL_END = 53;
   public static final int STORM_START_TICK = 43;
   public static final int STORM_END_TICK = 68;

   /** 奇点黑洞坍缩聚能开始 tick。 */
   public static final int COLLAPSE_START_TICK = 66;
   /** 终极绚烂大爆炸与服务端终焉处决同步引爆 tick。 */
   public static final int DETONATE_TICK = 82;

   /** 爆炸冲击波球壳主扩散时长（tick）。 */
   public static final int SHOCK_DURATION = 24;
   /** 特效总寿命（tick，约 6.8 秒）。 */
   public static final int TOTAL_LIFETIME = 136;
}
