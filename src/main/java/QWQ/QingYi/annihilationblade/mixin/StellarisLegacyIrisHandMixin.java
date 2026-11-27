package QWQ.QingYi.annihilationblade.mixin;

import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisHandCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 旧版 Iris / Oculus（{@code net.coderbot.iris.pipeline.HandRenderer}）延迟绘手保护（{@code @Pseudo} 可选目标）。
 */
@Pseudo
@Mixin(targets = "net.coderbot.iris.pipeline.HandRenderer", remap = false)
public abstract class StellarisLegacyIrisHandMixin {
   @Inject(method = "isRenderingSolid", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private void annihilationblade$legacyLateHandSolid(CallbackInfoReturnable<Boolean> cir) {
      if (StellarisHandCompat.renderingLateHand) {
         cir.setReturnValue(true);
      }
   }

   @Inject(method = "isHandTranslucent", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private void annihilationblade$legacyLateHandTranslucent(CallbackInfoReturnable<Boolean> cir) {
      if (StellarisHandCompat.renderingLateHand) {
         cir.setReturnValue(false);
      }
   }

   @Inject(method = {"renderSolid", "renderTranslucent"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private void annihilationblade$legacyDeferEarlyHand(CallbackInfo ci) {
      if (StellarisHandCompat.shouldDeferIrisHand()) {
         ci.cancel();
      }
   }
}
