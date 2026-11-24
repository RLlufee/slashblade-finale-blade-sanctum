package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * SE「因果坍缩·错位裂痕」双端共享的时间轴、量化坐标编码与锚点工具。
 * <p>
 * 仅引用双端通用 Minecraft 数学与坐标类型，禁止引用任何客户端渲染类。
 */
public final class CausalityCollapsePattern {
   /** 裂痕骤裂崩开阶段时长（tick）：极速撕开并把 UV 错位拉至峰值。 */
   public static final int OPEN_TICKS = 4;
   /** 裂痕临终因果校准对齐合缝阶段时长（tick）。 */
   public static final int CLOSE_TICKS = 16;
   /** 单道因果裂痕默认存续总时长（tick，64 tick ≈ 3.2 秒，其中约 2.2 秒静默滞留震慑）。 */
   public static final int DEFAULT_SCAR_DURATION = 64;

   private CausalityCollapsePattern() {
   }

   /** 神威独立单链模式：在目标被拧断处决（SNAP_TICK）后，等待 3D 躯壳即将吸穿奇点并由因果坍缩接管的延迟（tick）。 */
   public static int singleRiftScarDelay() {
      return Math.max(2, WorldRiftPattern.SUCK_END_TICK - WorldRiftPattern.SNAP_TICK - 2);
   }

   /** 断空 + 神威群体联动模式：在引爆群体残骸神威后，于奇点吸穿残骸瞬间无缝接管的基准延迟（tick；各裂痕再叠加自身 stagger）。 */
   public static int groupRiftScarDelay() {
      return Math.max(4, WorldRiftPattern.LINKAGE_SUCK_END - 2);
   }

   /**
    * 独立空挥模式解析单道裂痕锚点：置于玩家准星前方 ~5.6 格处（兼顾近景震撼与周边地形卷入涡旋）；
    * 若前方有实心方块遮挡则微退至墙面之前，避免被方块吞没。
    */
   public static Vec3 resolveAirScarAnchor(ServerLevel level, ServerPlayer player) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle();
      if (look.lengthSqr() < 1.0E-6) {
         look = new Vec3(0.0, 0.0, 1.0);
      } else {
         look = look.normalize();
      }
      double focalDist = 6.2;
      Vec3 far = eye.add(look.scale(focalDist));
      BlockHitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
      if (hit.getType() == HitResult.Type.BLOCK) {
         double hitDist = eye.distanceTo(hit.getLocation());
         double pullback = Math.min(1.4, hitDist * 0.28);
         return eye.add(look.scale(Math.max(2.0, hitDist - pullback)));
      }
      return far;
   }

   // ───────────────────────────── 逐目标多点 3D 坐标量化编解码（±2048 格，1/16 格精度） ─────────────────────────────

   /** 将相对 origin 的 (dx, dz) 量化为 16-bit 有符号整数打包至一个 int。 */
   public static int packXZ(Vec3 origin, Vec3 pos) {
      int qx = Mth.clamp((int)Math.round((pos.x - origin.x) * 16.0), -32768, 32767);
      int qz = Mth.clamp((int)Math.round((pos.z - origin.z) * 16.0), -32768, 32767);
      return (qx & 0xFFFF) | ((qz & 0xFFFF) << 16);
   }

   /** 将相对 origin 的 dy（16-bit 有符号）、目标身形高度（0.1 格步进，8-bit）与错峰延迟（8-bit）打包至一个 int。 */
   public static int packYMeta(Vec3 origin, Vec3 pos, float entityHeight, int staggerTick) {
      int qy = Mth.clamp((int)Math.round((pos.y - origin.y) * 16.0), -32768, 32767);
      int heightDeci = Mth.clamp((int)Math.round(entityHeight * 10.0F), 8, 80);
      int stagger = Mth.clamp(staggerTick, 0, 255);
      return (qy & 0xFFFF) | ((heightDeci & 0xFF) << 16) | ((stagger & 0xFF) << 24);
   }

   public static Vec3 unpackPos(Vec3 origin, int packedXZ, int packedYMeta) {
      short qx = (short)(packedXZ & 0xFFFF);
      short qz = (short)((packedXZ >>> 16) & 0xFFFF);
      short qy = (short)(packedYMeta & 0xFFFF);
      return origin.add(qx / 16.0, qy / 16.0, qz / 16.0);
   }

   public static float unpackHeight(int packedYMeta) {
      int dec = (packedYMeta >>> 16) & 0xFF;
      return Math.max(0.8F, dec * 0.1F);
   }

   public static int unpackStagger(int packedYMeta) {
      return (packedYMeta >>> 24) & 0xFF;
   }

   /** 服务端传递单道因果裂痕位置、目标身形高度与错峰起裂 tick 的轻量描述体。 */
   public record ScarSpec(Vec3 center, float entityHeight, int staggerTick) {
   }
}
