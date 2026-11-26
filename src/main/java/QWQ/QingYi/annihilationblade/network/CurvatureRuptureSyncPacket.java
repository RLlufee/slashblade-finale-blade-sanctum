package QWQ.QingYi.annihilationblade.network;

import QWQ.QingYi.annihilationblade.infinity_stellaris.client.InfinityStellarisAiErasureKeyHandler;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent.Context;

/**
 * 曲率撕裂领域开关权威回执包（S2C）：由服务端校验 SE 挂载、配置开关与持刀状态落地后回传客户端，
 * 杜绝客户端乐观取反、切手丢包或跨维度导致的双端状态永久反相。
 */
public record CurvatureRuptureSyncPacket(boolean enabled, boolean showMessage) {
   public static void encode(CurvatureRuptureSyncPacket packet, FriendlyByteBuf buffer) {
      buffer.writeBoolean(packet.enabled);
      buffer.writeBoolean(packet.showMessage);
   }

   public static CurvatureRuptureSyncPacket decode(FriendlyByteBuf buffer) {
      return new CurvatureRuptureSyncPacket(buffer.readBoolean(), buffer.readBoolean());
   }

   public static void handle(CurvatureRuptureSyncPacket packet, Supplier<Context> context) {
      Context ctx = context.get();
      ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
         Dist.CLIENT, () -> () -> InfinityStellarisAiErasureKeyHandler.applyServerSync(packet.enabled, packet.showMessage)
      ));
      ctx.setPacketHandled(true);
   }
}
