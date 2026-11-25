package QWQ.QingYi.annihilationblade.annihilation_blade.client;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class TerminusTooltipEvents {
   private TerminusTooltipEvents() {
   }

   @SubscribeEvent
   public static void onRenderTooltipPre(RenderTooltipEvent.Pre event) {
      ItemStack stack = event.getItemStack();
      if (!TerminusTooltipRenderer.shouldRender(stack)) {
         return;
      }

      if (!ModConfig.CLIENT.clientTooltips.enableAnnihilationBladeRenderer.getValue()) {
         return;
      }

      Minecraft minecraft = Minecraft.getInstance();
      // 仅传递物品标题，避免每帧调用 stack.getTooltipLines 重复派发 ItemTooltipEvent
      List<Component> titleOnly = List.of(stack.getHoverName());
      TerminusTooltipRenderer.render(event.getGraphics(), event.getFont(), stack, titleOnly, event.getX(), event.getY());
      event.setCanceled(true);
   }
}
