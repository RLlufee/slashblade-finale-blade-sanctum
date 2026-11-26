package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 星穹引擎全屏/子矩形渲染通道描述符。
 */
@OnlyIn(Dist.CLIENT)
public record StellarisPass(
   Kind kind,
   Supplier<ShaderInstance> shader,
   Consumer<ShaderInstance> uniforms,
   @Nullable IntConsumer extraGlBinder,
   @Nullable ScreenRect scissorRect
) {
   public enum Kind {
      /** 低分辨率 3 附件重型 Pass（黑洞 RK4、轨道聚焦阵列 SDF、尘墙、云洞、测试星核）。 */
      HEAVY_MRT,
      /** 全分辨率线性 HDR 场景改写 Pass（夺夜、锥形亏角双像折射、地表遥测、解析透镜）。 */
      SCENE
   }

   /** 归一化屏幕空间裁剪矩形（[0, 1]×[0, 1]，左下原点）；null 表示全屏执行，EMPTY 表示在视锥外直接零开销跳过。 */
   public record ScreenRect(float minU, float minV, float maxU, float maxV) {
      public static final ScreenRect EMPTY = new ScreenRect(0.0F, 0.0F, 0.0F, 0.0F);

      public static ScreenRect ofClamped(float minU, float minV, float maxU, float maxV) {
         float x0 = Math.max(0.0F, Math.min(1.0F, minU));
         float y0 = Math.max(0.0F, Math.min(1.0F, minV));
         float x1 = Math.max(x0, Math.min(1.0F, maxU));
         float y1 = Math.max(y0, Math.min(1.0F, maxV));
         return new ScreenRect(x0, y0, x1, y1);
      }

      public boolean empty() {
         return this.maxU - this.minU <= 1.0E-4F || this.maxV - this.minV <= 1.0E-4F;
      }
   }

   /**
    * 将 3D 包围球准确投影为屏幕 Scissor 裁剪框（供 Step 1 沙盒与 Step 2 黑洞共用）：
    * <ul>
    *   <li>相机位于球体内部或擦边 → 返回 {@code null}（全屏绘制）；</li>
    *   <li>球体完全位于相机背后或屏幕视锥之外 → 返回 {@link ScreenRect#EMPTY}（零开销跳过整个 Pass）；</li>
    *   <li>球体位于视锥内 → 返回带安全护边的紧致屏幕裁剪框。</li>
    * </ul>
    */
   @Nullable
   public static ScreenRect projectSphereRect(FxFrame frame, Vec3 worldCenter, float boundRadius) {
      Vector3f rel = frame.rel(worldCenter);
      float dist = rel.length();
      if (dist <= boundRadius * 1.25F) {
         return null;
      }
      // 转到观察空间（相机朝 -Z 看）：viewZ > boundRadius 表示整颗包围球都落在相机背后，直接零开销裁剪！
      Vector4f viewPos = frame.view.transform(new Vector4f(rel.x, rel.y, rel.z, 1.0F));
      if (viewPos.z > boundRadius) {
         return ScreenRect.EMPTY;
      }
      // 若包围球跨过近平面（-viewPos.z <= boundRadius），安全回退全屏绘制避免投影奇点
      float depth = -viewPos.z;
      if (depth <= boundRadius * 1.15F) {
         return null;
      }
      float projScaleX = Math.abs(frame.projection.m00());
      float projScaleY = Math.abs(frame.projection.m11());
      float centerU = (viewPos.x * projScaleX / depth) * 0.5F + 0.5F;
      float centerV = (viewPos.y * projScaleY / depth) * 0.5F + 0.5F;
      float safeDepth = Math.max(depth - boundRadius, 0.35F);
      float halfU = (boundRadius / safeDepth) * projScaleX * 0.54F + 0.05F;
      float halfV = (boundRadius / safeDepth) * projScaleY * 0.54F + 0.05F;

      if (centerU + halfU < 0.0F || centerU - halfU > 1.0F || centerV + halfV < 0.0F || centerV - halfV > 1.0F) {
         return ScreenRect.EMPTY;
      }
      return ScreenRect.ofClamped(centerU - halfU, centerV - halfV, centerU + halfU, centerV + halfV);
   }

   public static StellarisPass heavyMrt(
      Supplier<ShaderInstance> shader,
      Consumer<ShaderInstance> uniforms,
      @Nullable IntConsumer extraGlBinder,
      @Nullable ScreenRect scissorRect
   ) {
      return new StellarisPass(Kind.HEAVY_MRT, shader, uniforms, extraGlBinder, scissorRect);
   }

   public static StellarisPass scene(
      Supplier<ShaderInstance> shader,
      Consumer<ShaderInstance> uniforms,
      @Nullable IntConsumer extraGlBinder
   ) {
      return new StellarisPass(Kind.SCENE, shader, uniforms, extraGlBinder, null);
   }
}
