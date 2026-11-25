package QWQ.QingYi.annihilationblade.network;

import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.Dankong;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent.Context;

public record DankongBlinkModePacket(boolean enabled) {
   public static void encode(DankongBlinkModePacket packet, FriendlyByteBuf buffer) {
      buffer.writeBoolean(packet.enabled);
   }

   public static DankongBlinkModePacket decode(FriendlyByteBuf buffer) {
      return new DankongBlinkModePacket(buffer.readBoolean());
   }

   public static void handle(DankongBlinkModePacket packet, Supplier<Context> context) {
      Context ctx = context.get();
      ctx.enqueueWork(() -> {
         ServerPlayer player = ctx.getSender();
         if (player == null) {
            return;
         }
         if (Dankong.isHoldingDankongBlade(player)) {
            Dankong.setBlinkEnabled(player, packet.enabled);
            ModNetwork.sendDankongBlinkModeSync(player, packet.enabled, true);
         } else {
            // 若切手同 tick 丢弃切换请求，回发服务端当前权威状态纠偏，防止双端永久反相
            ModNetwork.sendDankongBlinkModeSync(player, Dankong.isBlinkEnabled(player), false);
         }
      });
      ctx.setPacketHandled(true);
   }
}
