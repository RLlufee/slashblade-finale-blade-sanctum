package QWQ.QingYi.annihilationblade.mixin;

import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisHandCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris / Oculus 光影包开启且星穹特效活跃时的第一人称手部延迟重绘钩子。
 * <p>
 * 配合 {@link StellarisIrisHandMixin} / {@link StellarisLegacyIrisHandMixin}：
 * 仅在光影包生效且星穹渲染器活跃时，将手部绘制从 {@code renderLevel} 内部延后至 {@code AFTER_LEVEL} 之后，
 * 使手持无尽星空刀体始终锐利清澈，不被全屏冲击帧、黑洞扭曲与色差糊脏。
 */
@Mixin(GameRenderer.class)
public abstract class StellarisGameRendererMixin {
   @Shadow @Final private Minecraft minecraft;
   @Shadow @Final private RenderBuffers renderBuffers;
   @Shadow @Final private LightTexture lightTexture;
   @Shadow @Final public ItemInHandRenderer itemInHandRenderer;
   @Shadow private boolean panoramicMode;

   @Shadow public abstract Matrix4f getProjectionMatrix(double fov);
   @Shadow public abstract void resetProjectionMatrix(Matrix4f projection);
   @Shadow protected abstract double getFov(Camera camera, float partialTick, boolean useFovSetting);
   @Shadow protected abstract void bobHurt(PoseStack poseStack, float partialTick);
   @Shadow protected abstract void bobView(PoseStack poseStack, float partialTick);

   @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
   private void annihilationblade$renderDeferredHandAfterStellaris(PoseStack viewMatrix, Camera camera, float partialTick, CallbackInfo ci) {
      if (!StellarisHandCompat.shouldDeferIrisHand()) {
         return;
      }
      ci.cancel();
      if (this.panoramicMode || this.minecraft.player == null || this.minecraft.gameMode == null) {
         return;
      }
      this.resetProjectionMatrix(this.getProjectionMatrix(this.getFov(camera, partialTick, false)));
      PoseStack pose = new PoseStack();
      pose.mulPoseMatrix(viewMatrix.last().pose().invert(new Matrix4f()));
      PoseStack modelView = RenderSystem.getModelViewStack();
      modelView.pushPose();
      modelView.mulPoseMatrix(viewMatrix.last().pose());
      RenderSystem.applyModelViewMatrix();
      try {
         this.bobHurt(pose, partialTick);
         if (this.minecraft.options.bobView().get()) {
            this.bobView(pose, partialTick);
         }
         boolean sleeping = this.minecraft.getCameraEntity() instanceof LivingEntity living && living.isSleeping();
         if (this.minecraft.options.getCameraType().isFirstPerson()
            && !sleeping
            && !this.minecraft.options.hideGui
            && this.minecraft.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            this.lightTexture.turnOnLightLayer();
            StellarisHandCompat.renderingLateHand = true;
            try {
               this.itemInHandRenderer.renderHandsWithItems(
                  partialTick,
                  pose,
                  this.renderBuffers.bufferSource(),
                  this.minecraft.player,
                  this.minecraft.getEntityRenderDispatcher().getPackedLightCoords(this.minecraft.player, partialTick)
               );
               this.renderBuffers.bufferSource().endBatch();
            } finally {
               StellarisHandCompat.renderingLateHand = false;
               this.lightTexture.turnOffLightLayer();
            }
         }
         if (this.minecraft.options.getCameraType().isFirstPerson() && !sleeping) {
            ScreenEffectRenderer.renderScreenEffect(this.minecraft, new PoseStack());
         }
      } finally {
         modelView.popPose();
         RenderSystem.applyModelViewMatrix();
      }
   }
}
