package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import QWQ.QingYi.annihilationblade.common.AnnihilationBladeItemSupport;
import QWQ.QingYi.annihilationblade.common.SlashBladeStateSupport;
import QWQ.QingYi.annihilationblade.common.SlashBladeTargeting;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.Tags;

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
            ItemStack blade = findScoringBlade(attacker);
            int killCountBefore = getKillCount(blade);
            target.hurt(source, EXECUTION_DAMAGE);
            if (target.isAlive()) {
               target.invulnerableTime = 0;
               // 兜底强制死亡前补齐玩家击杀归属，避免被免疫 hurt 时丢失经验球与 killed_by_player 战利品
               target.setLastHurtByPlayer(attacker);
               target.setHealth(0.0F);
               target.die(source);
            }

            if (target.isAlive() && !preservesDeathSequence(target)) {
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
    * 随后立即移除常规非玩家实体残尸，跳过原版 20 tick 侧翻红尸动画，避免与 3D 螺旋/碎解残骸重影穿帮。
    * 对玩家、末影龙及带有 {@code forge:bosses} 标签的 Boss 保留原生 {@code tickDeath()} 死亡结算流程，
    * 避免跳过经验/龙蛋/传送门生成或 Boss 多阶段死亡逻辑。
    */
   public static void executeSilent(LivingEntity target, Player attacker) {
      execute(target, attacker);
      if (!target.level().isClientSide && !preservesDeathSequence(target) && !target.isAlive() && !target.isRemoved()) {
         target.discard();
      }
   }

   /** 判断实体是否依赖原生死亡动画/tickDeath 结算（玩家、末影龙、Boss 标签实体）。 */
   public static boolean preservesDeathSequence(LivingEntity target) {
      if (target instanceof Player || target instanceof EnderDragon) {
         return true;
      }
      return target.getType().is(Tags.EntityTypes.BOSSES);
   }

   /** 查找用于记入杀敌数的拔刀剑：优先主手，其次副手，延迟处决期间切走快捷栏时回退搜索背包中的湮灭之刃。 */
   private static ItemStack findScoringBlade(Player attacker) {
      ItemStack main = attacker.getMainHandItem();
      if (SlashBladeStateSupport.isSlashBlade(main)) {
         return main;
      }
      ItemStack off = attacker.getOffhandItem();
      if (SlashBladeStateSupport.isSlashBlade(off)) {
         return off;
      }
      for (ItemStack stack : attacker.getInventory().items) {
         if (AnnihilationBladeItemSupport.isAnnihilationBlade(stack)) {
            return stack;
         }
      }
      return main;
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
