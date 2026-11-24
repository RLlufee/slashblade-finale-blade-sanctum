package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import QWQ.QingYi.annihilationblade.common.SlashBladeStateSupport;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class TerminusLogic {
   private static final float EXECUTION_DAMAGE = 1.0E9F;
   private static int executionDepth;

   /** 当前服务端线程是否正处于 TerminusLogic 终焉处决调用栈内，用于防止命中监听误拦截其他 SE/SA 的终焉处决。 */
   public static boolean isExecuting() {
      return executionDepth > 0;
   }

   public static void execute(LivingEntity target, Player attacker) {
      if (!target.level().isClientSide && SlashBladeTargeting.canAttack(attacker, target)) {
         executionDepth++;
         try {
            target.invulnerableTime = 0;
            DamageSource source = target.level().damageSources().playerAttack(attacker);
            ItemStack blade = attacker.getMainHandItem();
            int killCountBefore = getKillCount(blade);
            target.hurt(source, 1.0E9F);
            if (target.isAlive()) {
               target.invulnerableTime = 0;
               target.setHealth(0.0F);
               target.die(source);
            }

            if (target.isAlive()) {
               target.discard();
            }

            if (!target.isAlive()) {
               ensureKillCountIncremented(blade, killCountBefore);
            }
         } finally {
            executionDepth--;
         }
      }
   }

   /**
    * 静默终焉处决：完整执行伤害、死亡事件、战利品/经验掉落与拔刀剑杀敌数结算，
    * 随后立即移除非玩家实体残尸，跳过原版 20 tick 侧翻红尸动画，避免与 3D 螺旋/碎解残骸重影穿帮。
    */
   public static void executeSilent(LivingEntity target, Player attacker) {
      execute(target, attacker);
      if (!target.level().isClientSide && !(target instanceof Player) && !target.isAlive() && !target.isRemoved()) {
         target.discard();
      }
   }

   private static int getKillCount(ItemStack blade) {
      return SlashBladeStateSupport.isSlashBlade(blade) ? SlashBladeStateSupport.killCount(blade) : -1;
   }

   private static void ensureKillCountIncremented(ItemStack blade, int killCountBefore) {
      if (killCountBefore >= 0) {
         SlashBladeStateSupport.state(blade).ifPresent(state -> {
            if (state.getKillCount() == killCountBefore) {
               state.setKillCount(killCountBefore + 1);
            }
         });
      }
   }
}
