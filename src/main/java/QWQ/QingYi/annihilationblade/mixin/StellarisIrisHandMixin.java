package QWQ.QingYi.annihilationblade.mixin;

import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisHandCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 现代 Iris / Oculus（{@code net.irisshaders.iris.pathways.HandRenderer}）延迟绘手保护（{@code @Pseudo} 可选目标）。
 * <p>
 * 仅在已安装对应包名光影模组、光影包开启且星穹引擎活跃时生效；原版零加载、零开销。
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class StellarisIrisHandMixin {
   @Inject(method = "isRenderingSolid", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private void annihilationblade$lateHandSolid(CallbackInfoReturnable<Boolean> cir) {
      if (StellarisHandCompat.renderingLateHand) {
         cir.setReturnValue(true);
      }
   }

   @Inject(method = "isHandTranslucent", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private void annihilationblade$lateHandTranslucent(CallbackInfoReturnable<Boolean> cir) {
      if (StellarisHandCompat.renderingLateHand) {
         cir.setReturnValue(false);
      }
   }

   @Inject(method = {"renderSolid", "renderTranslucent"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
   private void annihilationblade$deferEarlyHand(CallbackInfo ci) {
      if (StellarisHandCompat.shouldDeferIrisHand()) {
         ci.cancel();
      }
   }
}
