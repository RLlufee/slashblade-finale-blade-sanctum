package QWQ.QingYi.annihilationblade.annihilation_blade.item;

import QWQ.QingYi.annihilationblade.annihilation_blade.AnnihilationBladeDefinitions;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.level.Level;

public class ItemAnnihilationCore extends Item {
   public ItemAnnihilationCore() {
      super(new Properties().stacksTo(1));
   }

   @Nonnull
   public InteractionResultHolder<ItemStack> use(@Nonnull Level level, @Nonnull Player player, @Nonnull InteractionHand hand) {
      ItemStack itemStack = player.getItemInHand(hand);
      if (level.isClientSide) {
         return InteractionResultHolder.sidedSuccess(itemStack, true);
      }
      ItemStack godSword = getGodBladeFromManager(level);
      if (godSword.isEmpty()) {
         return InteractionResultHolder.fail(itemStack);
      }
      // 核心默认不可堆叠：直接替换当前手持槽位，彻底解决满背包时核心占最后一格导致唤醒失败的问题
      if (itemStack.getCount() <= 1) {
         player.setItemInHand(hand, godSword);
         return InteractionResultHolder.consume(godSword);
      }
      itemStack.shrink(1);
      if (!player.getInventory().add(godSword)) {
         player.drop(godSword, false);
      }
      return InteractionResultHolder.consume(itemStack);
   }

   public void appendHoverText(@Nonnull ItemStack stack, @Nullable Level level, @Nonnull List<Component> tooltip, @Nonnull TooltipFlag flag) {
      super.appendHoverText(stack, level, tooltip, flag);
      tooltip.add(Component.translatable("item.annihilationblade.annihilation_core.tip"));
   }

   private static ItemStack getGodBladeFromManager(Level level) {
      return AnnihilationBladeDefinitions.createStack(level);
   }
}
