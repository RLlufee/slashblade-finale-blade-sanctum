package QWQ.QingYi.annihilationblade.mixin;

import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.loli_blade.LoliBladeProtectionManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 补齐 {@link Player} 覆写且不调用 {@code super} 的方法级保护入口（{@code actuallyHurt} / {@code die}），
 * 避免仅注入 {@code LivingEntity} 时对玩家不生效。
 */
@Mixin(value = Player.class, priority = Integer.MAX_VALUE)
public abstract class LoliBladePlayerMixin {

   @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
   private void loliInvinciblePlayerActuallyHurt(DamageSource source, float amount, CallbackInfo ci) {
      if (isProtected()) {
         ci.cancel();
      }
   }

   @Inject(method = "die", at = @At("HEAD"), cancellable = true)
   private void loliInvinciblePlayerDie(DamageSource source, CallbackInfo ci) {
      if (isProtected()) {
         ci.cancel();
      }
   }

   private boolean isProtected() {
      Player self = (Player) (Object) this;
      if (self.level().isClientSide || !ModConfig.COMMON.loliBlade.ultimateInvincible.getValue()) {
         return false;
      }
      return LoliBladeProtectionManager.isProtected(self.getUUID());
   }
}
