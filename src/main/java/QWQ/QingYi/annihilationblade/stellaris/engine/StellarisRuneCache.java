package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹浑天法阵 GPU 首次 1 帧预烘焙距离场缓存（Step 1.6 {@code RuneCache}）。
 * <p>
 * 将极其繁复的 144 刻度游标、双外浑天环、反旋十二芒星轨、星象节点与 48 篆星微文一次性由 {@code st_rune_cache.fsh}
 * 烘焙至 {@code RGBA16F} 三通道精确距离场贴图（{@code CINEMATIC = 2048²}、{@code ULTIMATE = 4096²}），
 * 随后 {@code st_relief.fsh} 仅需数次硬件线性纹理采样即可步进出任意放大零锯齿的 3D 物理圆管立体法阵。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisRuneCache {
   public static final int SIZE_CINEMATIC = 2048;
   public static final int SIZE_ULTIMATE = 4096;

   private final FxTarget target = new FxTarget(FxTarget.Format.RGBA16F);
   private int bakedSize;
   private boolean baked;

   /** 确保指定分辨率的距离场已烘焙就绪；若尚未烘焙或画质档切换，则在当前渲染上下文执行一次 1 帧 GPU 烘焙。 */
   public boolean ensureBaked(StellarisPipeline pipeline, int desiredSize) {
      int size = desiredSize >= SIZE_ULTIMATE ? SIZE_ULTIMATE : SIZE_CINEMATIC;
      if (this.baked && this.bakedSize == size && this.target.ready()) {
         return true;
      }
      ShaderInstance shader = StellarisShaders.runeCache;
      if (shader == null) {
         return false;
      }
      RenderSystem.assertOnRenderThread();
      this.target.ensure(size, size);
      this.target.bindWrite();
      RenderSystem.disableBlend();
      RenderSystem.disableDepthTest();
      RenderSystem.depthMask(false);
      RenderSystem.disableCull();
      pipeline.drawFullscreen(shader, null, null, size, size);
      this.bakedSize = size;
      this.baked = true;
      Annihilationblade.LOGGER.info("[Stellaris Engine] Baked {}x{} RGBA16F celestial distance-field RuneCache on GPU.", size, size);
      return true;
   }

   public void invalidate() {
      this.baked = false;
   }

   public boolean ready() {
      return this.baked && this.target.ready();
   }

   public int texture() {
      return this.target.texture();
   }

   public int size() {
      return this.bakedSize;
   }

   public void destroy() {
      this.target.destroy();
      this.baked = false;
      this.bakedSize = 0;
   }
}
