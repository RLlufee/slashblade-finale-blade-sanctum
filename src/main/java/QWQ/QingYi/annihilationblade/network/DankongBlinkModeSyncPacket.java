package QWQ.QingYi.annihilationblade.network;

import QWQ.QingYi.annihilationblade.annihilation_blade.client.DankongBlinkKeyHandler;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent.Context;

/**
 * 断空位移模式权威回执包（S2C）：由服务端校验持刀状态并落地后回传给客户端，
 * 防止客户端乐观切换与切手丢包导致双端状态永久反相。
 */
public record DankongBlinkModeSyncPacket(boolean enabled, boolean showMessage) {
   public static void encode(DankongBlinkModeSyncPacket packet, FriendlyByteBuf buffer) {
      buffer.writeBoolean(packet.enabled);
      buffer.writeBoolean(packet.showMessage);
   }

   public static DankongBlinkModeSyncPacket decode(FriendlyByteBuf buffer) {
      return new DankongBlinkModeSyncPacket(buffer.readBoolean(), buffer.readBoolean());
   }

   public static void handle(DankongBlinkModeSyncPacket packet, Supplier<Context> context) {
      Context ctx = context.get();
      ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DankongBlinkKeyHandler.applyServerSync(packet.enabled, packet.showMessage)));
      ctx.setPacketHandled(true);
   }
}
