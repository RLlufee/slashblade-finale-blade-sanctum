package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.render.client.pipeline.FxTarget;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹引擎全部离屏浮点目标的统一仓库（Step 1.6 升级：双输出 SceneEmit + RuneCache + 3D ReliefPass MRT）。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisTargets {
   public static final int BLOOM_LEVELS = 6;

   /** 全分辨率线性 HDR 场景双附件乒乓目标（Attachment 0=改写世界, Attachment 1=场景纯自发光 SceneEmit）。 */
   public final StellarisSceneTarget sceneA = new StellarisSceneTarget();
   public final StellarisSceneTarget sceneB = new StellarisSceneTarget();
   /** GPU 首次 1 帧预烘焙 2048²/4096² 星穹浑天距离场缓存。 */
   public final StellarisRuneCache runeCache = new StellarisRuneCache();
   /** 3D 物理圆管浮雕法阵 2 附件 MRT（Attachment 0=HDR 辐射度, Attachment 1=RGBA32F ReliefMeta 深度与透射率）。 */
   public final StellarisReliefTarget relief = new StellarisReliefTarget();
   /** 预乘 Alpha 暗部本体层（按需分配，与 HDR 几何层分离、不直接进入泛光）。 */
   public final FxTarget body = new FxTarget(FxTarget.Format.RGBA16F);
   /** 全分辨率 HDR 几何与 TBO 粒子/丝带累积层（加法混合，进入泛光与变形星芒）。 */
   public final FxTarget hdrGeo = new FxTarget(FxTarget.Format.RGBA16F);
   /** 低分辨率（0.5 ~ 1.0x）3 附件 MRT 目标：0=主辐射度/折射、1=线性深度/遮挡、2=纯自发光提取。 */
   public final StellarisMrtTarget heavyMrt = new StellarisMrtTarget();

   /** 6 级 HDR 泛光金字塔（降采样与升采样，导出近核锐利与远晕广域双频权重）。 */
   public final FxTarget[] bloomDown = new FxTarget[BLOOM_LEVELS];
   public final FxTarget[] bloomUp = new FxTarget[BLOOM_LEVELS];

   /** 1/4 分辨率电影级变形水平星芒（Anamorphic Streak）乒乓缓冲。 */
   public final FxTarget streakA = new FxTarget(FxTarget.Format.RGBA16F);
   public final FxTarget streakB = new FxTarget(FxTarget.Format.RGBA16F);

   /** 自发光专属径向体积光（God Rays，带连续深度与 3D 浮雕遮挡）。 */
   public final FxTarget godRays = new FxTarget(FxTarget.Format.RGBA16F);

   /** 1×1 双缓冲全 GPU 自动曝光状态纹理（R = 当前曝光系数，G = 适应亮度），零 glReadPixels 阻塞。 */
   public final FxTarget exposureA = new FxTarget(FxTarget.Format.RG16F);
   public final FxTarget exposureB = new FxTarget(FxTarget.Format.RG16F);

   /** 半分辨率 Hold-Frame 视网膜闪盲残像持久目标（捕获完整预合成视觉烙印）。 */
   public final FxTarget holdFrame = new FxTarget(FxTarget.Format.RGBA16F);

   /** 预留给 Step 3 刀尖 Warp 尾迹的反向扭曲遮罩缓冲（a=强度, gb=位移向量，按需分配）。 */
   public final FxTarget warpMask = new FxTarget(FxTarget.Format.RGBA8);

   public StellarisTargets() {
      for (int i = 0; i < BLOOM_LEVELS; i++) {
         this.bloomDown[i] = new FxTarget(FxTarget.Format.RGBA16F);
         this.bloomUp[i] = new FxTarget(FxTarget.Format.RGBA16F);
      }
   }

   public void destroyAll() {
      this.sceneA.destroy();
      this.sceneB.destroy();
      this.runeCache.destroy();
      this.relief.destroy();
      this.body.destroy();
      this.hdrGeo.destroy();
      this.heavyMrt.destroy();
      for (int i = 0; i < BLOOM_LEVELS; i++) {
         this.bloomDown[i].destroy();
         this.bloomUp[i].destroy();
      }
      this.streakA.destroy();
      this.streakB.destroy();
      this.godRays.destroy();
      this.exposureA.destroy();
      this.exposureB.destroy();
      this.holdFrame.destroy();
      this.warpMask.destroy();
   }
}
