package QWQ.QingYi.annihilationblade.annihilation_blade.client.fx;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 把玩家模型「拍」成一组静态四边形，供断空的全息残影使用。
 * <p>
 * 只调用玩家模型本体的 {@code renderToBuffer}，不经过渲染器的盔甲 / 手持物品 / 名牌等渲染层，
 * 因此不会有任何几何被写进真实的缓冲区；整个过程只在 CPU 上收集顶点，不触碰 GL 状态。
 * 输出坐标以残影脚底为原点（已包含身体朝向与原版玩家模型的缩放），每 12 个浮点为一个面（4 个顶点 × xyz）。
 */
@OnlyIn(Dist.CLIENT)
final class AfterimageCapture {
   /** 单个残影最多截取的面数（扩至 768 以兼容高模模组生物）。 */
   private static final int MAX_QUADS = 768;
   private static final int MAX_CONSECUTIVE_FAILURES = 4;
   private static final Map<EntityType<?>, Integer> TYPE_FAILURE_COUNTS = new HashMap<>();
   private static int playerFailureCount;
   private static boolean playerFailureLogged;

   private AfterimageCapture() {
   }

   /**
    * @param bodyYaw    残影身体朝向（度）
    * @param headPitch  头部俯仰（度）
    * @param attackTime 挥刀姿势进度（0..1，约 0.3 时手臂正处于斩下的位置）
    */
   @Nullable
   static float[] capture(AbstractClientPlayer player, float bodyYaw, float headPitch, float attackTime) {
      if (playerFailureCount >= MAX_CONSECUTIVE_FAILURES) {
         return null;
      }
      EntityRenderer<? super AbstractClientPlayer> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
      if (!(renderer instanceof PlayerRenderer playerRenderer)) {
         return null;
      }
      PlayerModel<AbstractClientPlayer> model = playerRenderer.getModel();
      boolean prevYoung = model.young;
      boolean prevRiding = model.riding;
      boolean prevCrouching = model.crouching;
      float prevAttackTime = model.attackTime;
      HumanoidModel.ArmPose prevRightArm = model.rightArmPose;
      HumanoidModel.ArmPose prevLeftArm = model.leftArmPose;
      try {
         PoseStack pose = new PoseStack();
         // 与 LivingEntityRenderer / PlayerRenderer 的模型变换一致
         pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
         pose.scale(-1.0F, -1.0F, 1.0F);
         pose.scale(0.9375F, 0.9375F, 0.9375F);
         pose.translate(0.0F, -1.501F, 0.0F);

         // 确保全部肢体可见，防止旁观模式或第三方模组隐藏部件后残影只剩悬浮人头
         model.setAllVisible(true);
         model.young = false;
         model.riding = false;
         model.crouching = false;
         model.attackTime = attackTime;
         model.rightArmPose = HumanoidModel.ArmPose.ITEM;
         model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
         model.prepareMobModel(player, 0.0F, 0.0F, 1.0F);
         model.setupAnim(player, 0.0F, 0.0F, player.tickCount, 0.0F, headPitch);

         Collector collector = new Collector();
         model.renderToBuffer(pose, collector, 0xF000F0, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
         playerFailureCount = 0;
         float[] quads = collector.quads();
         return quads.length == 0 ? null : quads;
      } catch (RuntimeException exception) {
         playerFailureCount++;
         if (!playerFailureLogged) {
            playerFailureLogged = true;
            Annihilationblade.LOGGER.warn("[AnnihilationBlade FX] Afterimage capture error (count={}): {}", playerFailureCount, exception.toString());
         }
         return null;
      } finally {
         model.young = prevYoung;
         model.riding = prevRiding;
         model.crouching = prevCrouching;
         model.attackTime = prevAttackTime;
         model.rightArmPose = prevRightArm;
         model.leftArmPose = prevLeftArm;
      }
   }

   /**
    * 截取任意生物当前姿势的模型（断空「剪影斩断」用），坐标以生物脚底为原点。
    * <p>
    * 只支持使用 {@link LivingEntityRenderer} 的生物；渲染器自带的额外缩放（史莱姆、巨人等）不在模型里，
    * 因此截取后按碰撞箱高度重新归一化。某种生物连续截取失败达阈值后跳过该类型。
    */
   @Nullable
   @SuppressWarnings("unchecked")
   static float[] captureLiving(LivingEntity entity) {
      if (TYPE_FAILURE_COUNTS.getOrDefault(entity.getType(), 0) >= MAX_CONSECUTIVE_FAILURES) {
         return null;
      }
      EntityRenderer<? super LivingEntity> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
      if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) {
         return null;
      }
      EntityModel<LivingEntity> model = (EntityModel<LivingEntity>)living.getModel();
      boolean prevYoung = model.young;
      boolean prevRiding = model.riding;
      float prevAttackTime = model.attackTime;
      HumanoidModel<?> humanoid = model instanceof HumanoidModel<?> h ? h : null;
      boolean prevCrouching = humanoid != null && humanoid.crouching;
      HumanoidModel.ArmPose prevRightArm = humanoid != null ? humanoid.rightArmPose : null;
      HumanoidModel.ArmPose prevLeftArm = humanoid != null ? humanoid.leftArmPose : null;
      try {
         PoseStack pose = new PoseStack();
         pose.mulPose(Axis.YP.rotationDegrees(180.0F - entity.yBodyRot));
         pose.scale(-1.0F, -1.0F, 1.0F);
         pose.translate(0.0F, -1.501F, 0.0F);
         float limbPosition = entity.walkAnimation.position();
         float limbSpeed = Math.min(1.0F, entity.walkAnimation.speed());
         model.young = entity.isBaby();
         model.riding = false;
         model.attackTime = entity.getAttackAnim(1.0F);
         if (humanoid != null) {
            humanoid.setAllVisible(true);
            humanoid.crouching = entity.isCrouching();
            humanoid.rightArmPose = HumanoidModel.ArmPose.EMPTY;
            humanoid.leftArmPose = HumanoidModel.ArmPose.EMPTY;
         }
         float headYaw = Mth.wrapDegrees(entity.yHeadRot - entity.yBodyRot);
         model.prepareMobModel(entity, limbPosition, limbSpeed, 1.0F);
         model.setupAnim(entity, limbPosition, limbSpeed, entity.tickCount, headYaw, entity.getXRot());
         Collector collector = new Collector();
         model.renderToBuffer(pose, collector, 0xF000F0, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
         TYPE_FAILURE_COUNTS.remove(entity.getType());
         float[] quads = collector.quads();
         return quads.length == 0 ? null : fitHeight(quads, entity.getBbHeight());
      } catch (RuntimeException exception) {
         TYPE_FAILURE_COUNTS.merge(entity.getType(), 1, Integer::sum);
         Annihilationblade.LOGGER.debug("[AnnihilationBlade FX] Silhouette capture skipped for {}: {}", entity.getType(), exception.toString());
         return null;
      } finally {
         model.young = prevYoung;
         model.riding = prevRiding;
         model.attackTime = prevAttackTime;
         if (humanoid != null) {
            humanoid.crouching = prevCrouching;
            humanoid.rightArmPose = prevRightArm;
            humanoid.leftArmPose = prevLeftArm;
         }
      }
   }

   /** 截取高度与碰撞箱差距超过 25% 时，绕脚底等比缩放到碰撞箱高度。 */
   private static float[] fitHeight(float[] quads, float boxHeight) {
      float minY = Float.MAX_VALUE;
      float maxY = -Float.MAX_VALUE;
      for (int i = 1; i < quads.length; i += 3) {
         minY = Math.min(minY, quads[i]);
         maxY = Math.max(maxY, quads[i]);
      }
      float height = maxY - minY;
      if (height < 0.1F || boxHeight < 0.1F || Math.abs(height - boxHeight) / boxHeight < 0.25F) {
         return quads;
      }
      float k = boxHeight / height;
      for (int i = 0; i < quads.length; i += 3) {
         quads[i] *= k;
         quads[i + 1] = (quads[i + 1] - minY) * k;
         quads[i + 2] *= k;
      }
      return quads;
   }

   /** 只记录顶点位置的收集器：每 4 个顶点组成一个面。 */
   private static final class Collector implements VertexConsumer {
      private final float[] data = new float[MAX_QUADS * 12];
      private int vertices;
      private float px;
      private float py;
      private float pz;

      float[] quads() {
         int quads = this.vertices / 4;
         float[] out = new float[quads * 12];
         System.arraycopy(this.data, 0, out, 0, out.length);
         return out;
      }

      private void add(float x, float y, float z) {
         int index = this.vertices * 3;
         if (index + 3 > this.data.length) {
            return;
         }
         this.data[index] = x;
         this.data[index + 1] = y;
         this.data[index + 2] = z;
         this.vertices++;
      }

      @Override
      public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay, int light, float nx, float ny, float nz) {
         this.add(x, y, z);
      }

      @Override
      public VertexConsumer vertex(double x, double y, double z) {
         this.px = (float)x;
         this.py = (float)y;
         this.pz = (float)z;
         return this;
      }

      @Override
      public VertexConsumer color(int r, int g, int b, int a) {
         return this;
      }

      @Override
      public VertexConsumer uv(float u, float v) {
         return this;
      }

      @Override
      public VertexConsumer overlayCoords(int u, int v) {
         return this;
      }

      @Override
      public VertexConsumer uv2(int u, int v) {
         return this;
      }

      @Override
      public VertexConsumer normal(float x, float y, float z) {
         return this;
      }

      @Override
      public void endVertex() {
         this.add(this.px, this.py, this.pz);
      }

      @Override
      public void defaultColor(int r, int g, int b, int a) {
      }

      @Override
      public void unsetDefaultColor() {
      }
   }
}
