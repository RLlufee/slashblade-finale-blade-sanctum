package QWQ.QingYi.annihilationblade.mixin;

import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisCloudSuppression;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 星穹引擎唯一允许的客户端最小 Mixin（D4 定案）。
 * <p>
 * 仅当星穹引擎处于夺夜 / 撕裂云洞窗口时取消原版云层绘制（原版云层开启 {@code depthMask(true)}，会污染天空深度判定）。
 * 平时 {@link StellarisCloudSuppression#shouldSuppressVanillaClouds()} 为 {@code false}，零额外开销，
 * 且兼容任意维度与其他天空渲染模组。
 */
@Mixin(LevelRenderer.class)
public abstract class StellarisLevelRendererMixin {
   @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
   private void annihilationblade$suppressCloudsDuringStellarisNight(
      PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, double camX, double camY, double camZ, CallbackInfo ci
   ) {
      if (StellarisCloudSuppression.shouldSuppressVanillaClouds()) {
         ci.cancel();
      }
   }
}
