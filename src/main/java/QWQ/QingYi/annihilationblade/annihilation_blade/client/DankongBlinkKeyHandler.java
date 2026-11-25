package QWQ.QingYi.annihilationblade.annihilation_blade.client;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.Dankong;
import QWQ.QingYi.annihilationblade.network.DankongBlinkModePacket;
import QWQ.QingYi.annihilationblade.network.ModNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class DankongBlinkKeyHandler {
   private static final String KEY_NAME = "key.annihilationblade.toggle_dankong_blink";
   private static final String KEY_CATEGORY = "key.categories.annihilationblade";
   private static final String MESSAGE_TOGGLE = "message.annihilationblade.dankong_blink_toggle";
   private static final String MESSAGE_STATE_ON = "message.annihilationblade.dankong_blink.on";
   private static final String MESSAGE_STATE_OFF = "message.annihilationblade.dankong_blink.off";
   private static final KeyMapping TOGGLE_DANKONG_BLINK = new KeyMapping(
      KEY_NAME, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL, KEY_CATEGORY
   );
   private static boolean blinkEnabled = true;

   private DankongBlinkKeyHandler() {
   }

   @SubscribeEvent
   public static void onClientTick(ClientTickEvent event) {
      if (event.phase != Phase.END) {
         return;
      }

      Minecraft minecraft = Minecraft.getInstance();
      Player player = minecraft.player;
      if (player == null) {
         return;
      }

      boolean pendingState = blinkEnabled;
      boolean clicked = false;
      while (consumeToggleClick()) {
         if (!Dankong.isHoldingDankongBlade(player)) {
            continue;
         }
         pendingState = !pendingState;
         clicked = true;
      }
      if (clicked) {
         // 仅向服务端提交目标状态，等待服务端 S2C 权威回执确认后再更新本地状态与提示，避免切手丢包导致双端反相
         ModNetwork.CHANNEL.sendToServer(new DankongBlinkModePacket(pendingState));
      }
   }

   public static void applyServerSync(boolean enabled, boolean showMessage) {
      blinkEnabled = enabled;
      if (showMessage) {
         Player player = Minecraft.getInstance().player;
         if (player != null) {
            player.displayClientMessage(
               Component.translatable(MESSAGE_TOGGLE, Component.translatable(enabled ? MESSAGE_STATE_ON : MESSAGE_STATE_OFF)),
               true
            );
         }
      }
   }

   public static boolean consumeToggleClick() {
      return TOGGLE_DANKONG_BLINK.consumeClick();
   }

   public static void resetBlinkMode() {
      blinkEnabled = true;
   }

   @SubscribeEvent
   public static void onLoggingOut(LoggingOut event) {
      resetBlinkMode();
   }

   @EventBusSubscriber(modid = Annihilationblade.MODID, bus = Bus.MOD, value = Dist.CLIENT)
   public static final class ModBusEvents {
      private ModBusEvents() {
      }

      @SubscribeEvent
      public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
         event.register(TOGGLE_DANKONG_BLINK);
      }
   }
}
