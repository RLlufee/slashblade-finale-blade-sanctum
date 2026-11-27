package QWQ.QingYi.annihilationblade.stellaris.engine;

import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * 3D 物理圆管浮雕星穹法阵描述符（Step 1.6 {@code ReliefPass} 输入）。
 * <p>
 * 每个实例描述一座悬浮在世界空间中的立体天体仪器/轨道聚焦阵：支持任意三维法线倾角、外环与内星阵独立反旋、
 * 3D 圆管厚度缩放、展开进度与 HDR 辐射倍率，并在 {@code st_relief.fsh} 中经由 {@code RuneCache} 距离场步进生成真实金属/冰晶立体受光与前后深度遮挡。
 */
@OnlyIn(Dist.CLIENT)
public record StellarisReliefArray(
   Vec3 center,
   Vector3f normal,
   float radius,
   float outerSpinRad,
   float innerSpinRad,
   float tubeScale,
   float reveal,
   float intensity,
   int rgb,
   int variant
) {
   /** 单帧并行支持的最大 3D 浮雕法阵数量（足以覆盖天顶主阵 + 七星子阵）。 */
   public static final int MAX_ARRAYS = 8;

   public static final int VARIANT_GRAND_ARMILLARY = 0;
   public static final int VARIANT_ORBITAL_FOCUS = 1;
   public static final int VARIANT_SEVEN_STAR_RING = 2;

   public StellarisReliefArray {
      normal = (normal == null || normal.lengthSquared() < 1.0E-6F)
         ? new Vector3f(0.0F, 1.0F, 0.0F)
         : new Vector3f(normal).normalize();
      radius = Math.max(0.5F, radius);
      tubeScale = Math.max(0.25F, Math.min(4.0F, tubeScale));
      reveal = Math.max(0.0F, Math.min(1.0F, reveal));
      intensity = Math.max(0.0F, intensity);
   }
}
