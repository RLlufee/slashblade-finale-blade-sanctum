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
 * 星穹引擎全屏/子矩形渲染通道描述符（Step 1.6 升级：8 顶点真实 {@code ViewProj} 投影 + {@code w <= 0.001} 全屏回退）。
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
      /** 低分辨率 3 附件重型 Pass（黑洞 Binet RK4、体积吸积盘、尘墙、云洞、测试星核）。 */
      HEAVY_MRT,
      /** 全分辨率线性 HDR 场景改写 Pass（夺夜、动态重打光、锥形亏角双像折射、地表遥测、解析透镜，支持双输出 SceneEmit）。 */
      SCENE
   }

   /** 归一化屏幕空间裁剪矩形（[0, 1]×[0, 1]，左下原点）；null 表示全屏执行，EMPTY 表示在视锥外直接零开销跳过。 */
   public record ScreenRect(float minU, float minV, float maxU, float maxV) {
      public static final ScreenRect EMPTY = new ScreenRect(0.0F, 0.0F, 0.0F, 0.0F);
      public static final ScreenRect FULLSCREEN = new ScreenRect(0.0F, 0.0F, 1.0F, 1.0F);

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
    * 精确投影 3D 轴对齐包围盒（AABB 8 顶点真实 {@code ViewProj} 变换 + {@code clip.w <= 0.001} 全屏安全回退）。
    * <p>
    * 彻底消除广角 FOV（如 Quake Pro 110° + {@code fovKick}）下球体斜角椭圆近似裁剪导致的屏幕边缘竖直切边问题。
    * <ul>
    *   <li>任意角点跨越相机近裁剪面（{@code clip.w <= 0.001}）→ 若全 8 点均在相机背后则返回 {@link ScreenRect#EMPTY}，否则安全回退 {@code null}（全屏）；</li>
    *   <li>8 点全在视锥外同一侧 → 返回 {@link ScreenRect#EMPTY}（零开销跳过）；</li>
    *   <li>位于视锥内 → 返回带 {@code 0.015} 安全护边的紧致屏幕裁剪框。</li>
    * </ul>
    */
   @Nullable
   public static ScreenRect projectAabbRect(FxFrame frame, Vec3 worldCenter, float rx, float ry, float rz) {
      Vector3f rel = frame.rel(worldCenter);
      float padX = Math.max(0.05F, rx);
      float padY = Math.max(0.05F, ry);
      float padZ = Math.max(0.05F, rz);

      // 相机位于 AABB 内部或紧贴边界 → 全屏安全渲染
      if (Math.abs(rel.x) <= padX * 1.08F && Math.abs(rel.y) <= padY * 1.08F && Math.abs(rel.z) <= padZ * 1.08F) {
         return null;
      }

      float minU = 1.0F;
      float minV = 1.0F;
      float maxU = 0.0F;
      float maxV = 0.0F;
      int behindCount = 0;
      boolean crossesNear = false;
      Vector4f clip = new Vector4f();

      for (int c = 0; c < 8; c++) {
         float px = rel.x + ((c & 1) == 0 ? -padX : padX);
         float py = rel.y + ((c & 2) == 0 ? -padY : padY);
         float pz = rel.z + ((c & 4) == 0 ? -padZ : padZ);
         frame.viewProjection.transform(clip.set(px, py, pz, 1.0F));
         if (clip.w <= 0.001F) {
            behindCount++;
            crossesNear = true;
            continue;
         }
         float u = (clip.x / clip.w) * 0.5F + 0.5F;
         float v = (clip.y / clip.w) * 0.5F + 0.5F;
         if (!Float.isFinite(u) || !Float.isFinite(v)) {
            return null;
         }
         minU = Math.min(minU, u - 0.015F);
         minV = Math.min(minV, v - 0.015F);
         maxU = Math.max(maxU, u + 0.015F);
         maxV = Math.max(maxV, v + 0.015F);
      }

      // 全部 8 个角点均落在相机背后 → 确定在视锥外，零开销跳过
      if (behindCount == 8) {
         return ScreenRect.EMPTY;
      }
      // 部分角点跨过近裁剪面（w <= 0.001）→ 回退全屏，避免投影符号翻转切丢画面
      if (crossesNear) {
         return null;
      }
      if (maxU < 0.0F || minU > 1.0F || maxV < 0.0F || minV > 1.0F) {
         return ScreenRect.EMPTY;
      }
      return ScreenRect.ofClamped(minU, minV, maxU, maxV);
   }

   /** 将 3D 包围球通过外接立方体 8 顶点精确投影为屏幕裁剪框（兼容任意广角 FOV 与透视拉伸）。 */
   @Nullable
   public static ScreenRect projectSphereRect(FxFrame frame, Vec3 worldCenter, float boundRadius) {
      return projectAabbRect(frame, worldCenter, boundRadius, boundRadius, boundRadius);
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
