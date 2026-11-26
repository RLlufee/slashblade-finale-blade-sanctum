package QWQ.QingYi.annihilationblade.infinity_stellaris.client;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class InfinityStellarisTooltipEvents {
   private InfinityStellarisTooltipEvents() {
   }

   @SubscribeEvent
   public static void onRenderTooltipPre(RenderTooltipEvent.Pre event) {
      ItemStack stack = event.getItemStack();
      if (!InfinityStellarisItemSupport.isInfinityStellaris(stack)) {
         return;
      }

      if (!ModConfig.CLIENT.clientTooltips.enableInfinityStellarisRenderer.getValue()) {
         return;
      }

      // 仅传递物品名称，彻底消除每帧调用 stack.getTooltipLines 重复派发 ItemTooltipEvent 的性能开销
      List<Component> titleOnly = List.of(stack.getHoverName());
      InfinityStellarisTooltipRenderer.render(event.getGraphics(), event.getFont(), stack, titleOnly, event.getX(), event.getY());
      event.setCanceled(true);
   }
}
