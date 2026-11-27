package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.render.client.core.FxCompat;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Iris / Oculus 光影包下第一人称手部延迟重绘兼容桥（Step 1.6）。
 * <p>
 * 开启 Iris/Oculus 光影包时，光影管线会在 {@code LevelRenderer.renderLevel} 内部提前绘制第一人称手部，
 * 导致随后在 {@code AFTER_LEVEL} 执行的星穹后处理（冲击帧、黑洞折射、压暗、色差）直接糊在玩家持握的刀身上。
 * 本桥接类仅在「光影包激活 + 配置开启延迟手部 + 当前帧确实存在活跃星穹渲染 + 引擎未熔断」四重条件同时满足时生效；
 * 原版无光影或零星穹特效时恒返回 {@code false}，零开销直通放行。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisHandCompat {
   /** 标记当前是否正在由 {@code StellarisGameRendererMixin} 执行延迟后的第一人称手部重绘。 */
   public static boolean renderingLateHand;

   private StellarisHandCompat() {
   }

   /** 当前帧是否应取消 Iris 提前绘手，改在 {@code AFTER_LEVEL} 之后的 {@code GameRenderer#renderItemInHand} 中重绘。 */
   public static boolean shouldDeferIrisHand() {
      if (renderingLateHand) {
         return false;
      }
      StellarisConfigSnapshot cfg = StellarisConfigSnapshot.current();
      if (!cfg.irisDeferHandWithShaderPack() || !cfg.rendererEnabled() || !cfg.withShaderPack()) {
         return false;
      }
      if (!FxCompat.isIrisLoaded() || !FxCompat.shaderPackActive() || StellarisPipeline.INSTANCE.isFailed()) {
         return false;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
         return false;
      }
      return StellarisManager.hasWorkToRender(mc.gameRenderer.getMainCamera().getPosition());
   }
}
