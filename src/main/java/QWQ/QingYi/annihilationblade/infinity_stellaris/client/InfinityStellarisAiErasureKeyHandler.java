package QWQ.QingYi.annihilationblade.infinity_stellaris.client;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.infinity_stellaris.item.InfinityStellarisItemSupport;
import QWQ.QingYi.annihilationblade.network.InfinityStellarisAiErasurePacket;
import QWQ.QingYi.annihilationblade.network.ModNetwork;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
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

/**
 * 曲率撕裂领域单键 {@code I} 客户端按键处理器（服务端权威回执同步）。
 * <p>
 * 已移除高危且易冲突的 {@code C} 键绑定；按 {@code I} 仅向服务端提交切换请求，
 * 必须等待 {@link #applyServerSync} 收到服务端权威回执后才更新本地布尔与 ActionBar 提示，杜绝双端反相。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class InfinityStellarisAiErasureKeyHandler {
   private static final String KEY_NAME = "key.annihilationblade.toggle_infinity_ai_erasure";
   private static final String KEY_CATEGORY = "key.categories.annihilationblade";
   private static final String MESSAGE_TOGGLE = "message.annihilationblade.infinity_ai_erasure_toggle";
   private static final String MESSAGE_STATE_ON = "message.annihilationblade.infinity_ai_erasure.on";
   private static final String MESSAGE_STATE_OFF = "message.annihilationblade.infinity_ai_erasure.off";
   private static final KeyMapping TOGGLE_AI_ERASURE = new KeyMapping(
      KEY_NAME, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, KEY_CATEGORY
   );
   private static boolean curvatureEnabled;

   private InfinityStellarisAiErasureKeyHandler() {
   }

   /** 查询当前由服务端同步下来的曲率领域状态（供 Step 6 客户端视觉联动使用）。 */
   public static boolean isCurvatureEnabled() {
      return curvatureEnabled;
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

      boolean holdingCurvature = InfinityStellarisItemSupport.isHoldingActiveSpecialEffect(
         player, ModSpecialEffects.CURVATURE_RUPTURE.getId()
      );
      boolean pendingState = curvatureEnabled;
      boolean clicked = false;
      while (TOGGLE_AI_ERASURE.consumeClick()) {
         if (!holdingCurvature) {
            continue;
         }
         pendingState = !pendingState;
         clicked = true;
      }

      if (clicked) {
         // 仅提交目标状态，等待服务端 S2C 权威回执确认后再更新本地状态与提示
         ModNetwork.CHANNEL.sendToServer(new InfinityStellarisAiErasurePacket(pendingState));
      }
   }

   public static void applyServerSync(boolean enabled, boolean showMessage) {
      curvatureEnabled = enabled;
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

   @SubscribeEvent
   public static void onLoggingOut(LoggingOut event) {
      curvatureEnabled = false;
   }

   @EventBusSubscriber(modid = Annihilationblade.MODID, bus = Bus.MOD, value = Dist.CLIENT)
   public static final class ModBusEvents {
      private ModBusEvents() {
      }

      @SubscribeEvent
      public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
         event.register(TOGGLE_AI_ERASURE);
      }
   }
}
