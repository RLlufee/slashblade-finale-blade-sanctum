package QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect;

import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;

/**
 * 湮灭之刃 SE「裂界·因果伤痕（神威空间扭曲处决）」。
 * <p>
 * 双模式运行逻辑由 {@code WorldRiftChain} 驱动：
 * <ul>
 *   <li>断空关闭（配置禁用 / 按住 Shift / Left Ctrl 切关 / 刀无断空）时：命中目标触发单链跳跃神威扭杀（活体定身扭曲 → 拧断抹除 → 跃迁至下一最近目标）；</li>
 *   <li>断空开启且发动斩杀时：在断空收刀齐斩完毕后联动接力，对所有已斩杀目标残骸同步展开神威尸骸扭杀。</li>
 * </ul>
 */
public class WorldRift extends SpecialEffect {
   public WorldRift() {
      super(0, false, false);
   }
}
