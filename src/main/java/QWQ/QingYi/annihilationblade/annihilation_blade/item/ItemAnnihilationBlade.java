package QWQ.QingYi.annihilationblade.annihilation_blade.item;

import QWQ.QingYi.annihilationblade.annihilation_blade.AnnihilationBladeDefinitions;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;

public class ItemAnnihilationBlade extends ItemSlashBlade {
   public ItemAnnihilationBlade() {
      super(Tiers.NETHERITE, 100, -2.4F, new Properties().fireResistant().stacksTo(1));
   }

   public boolean isDamageable(ItemStack stack) {
      return false;
   }

   public ItemStack getDefaultInstance() {
      ItemStack stack = new ItemStack(this);
      AnnihilationBladeDefinitions.applyStats(stack);
      return stack;
   }

   public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
      super.inventoryTick(stack, level, entity, slotId, isSelected);
      // 玩家背包中的周期校准已由 ModEventHandler 统一处理（带脏检查）；此处仅兜底非玩家实体持有场景
      if (!level.isClientSide && !(entity instanceof net.minecraft.world.entity.player.Player) && entity.tickCount % 20 == 0) {
         AnnihilationBladeDefinitions.ensureStats(stack, level);
      }
   }
}
