package QWQ.QingYi.annihilationblade.network;

import QWQ.QingYi.annihilationblade.infinity_stellaris.logic.CurvatureRuptureLogic;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent.Context;

/**
 * 曲率撕裂领域开关请求包（C2S）：由客户端按 {@code I} 键提交目标状态，
 * 服务端校验 SE 挂载与配置后落地，并强制回发 {@link CurvatureRuptureSyncPacket} 权威回执。
 */
public record InfinityStellarisAiErasurePacket(boolean enabled) {
   public static void encode(InfinityStellarisAiErasurePacket packet, FriendlyByteBuf buffer) {
      buffer.writeBoolean(packet.enabled);
   }

   public static InfinityStellarisAiErasurePacket decode(FriendlyByteBuf buffer) {
      return new InfinityStellarisAiErasurePacket(buffer.readBoolean());
   }

   public static void handle(InfinityStellarisAiErasurePacket packet, Supplier<Context> context) {
      Context ctx = context.get();
      ctx.enqueueWork(() -> {
         ServerPlayer player = ctx.getSender();
         if (player != null) {
            CurvatureRuptureLogic.handleToggleRequest(player, packet.enabled);
         }
      });
      ctx.setPacketHandled(true);
   }
}
