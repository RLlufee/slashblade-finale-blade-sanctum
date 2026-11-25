package QWQ.QingYi.annihilationblade.mixin;

import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.loli_blade.LoliBladeProtectionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 补齐 {@link ServerPlayer#die} 的方法级拦截（原版 {@code ServerPlayer.die} 完全重写且不调用 {@code super.die}）。
 */
@Mixin(value = ServerPlayer.class, priority = Integer.MAX_VALUE)
public abstract class LoliBladeServerPlayerMixin {

   @Inject(method = "die", at = @At("HEAD"), cancellable = true)
   private void loliInvincibleServerPlayerDie(DamageSource source, CallbackInfo ci) {
      ServerPlayer self = (ServerPlayer) (Object) this;
      if (!self.level().isClientSide
            && ModConfig.COMMON.loliBlade.ultimateInvincible.getValue()
            && LoliBladeProtectionManager.isProtected(self.getUUID())) {
         ci.cancel();
      }
   }
}
