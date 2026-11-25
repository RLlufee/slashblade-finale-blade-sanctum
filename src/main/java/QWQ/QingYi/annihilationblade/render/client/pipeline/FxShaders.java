package QWQ.QingYi.annihilationblade.render.client.pipeline;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

/**
 * 渲染器全部着色器均注册为原版 {@link ShaderInstance}（core shader）。
 * <p>
 * 这样做的原因：原版对 GL 程序、纹理单元、VAO、混合状态都有缓存，若绕过它直接调用 GL，
 * 很容易让原版缓存与真实状态不一致，导致后续方块/实体渲染错乱。走 ShaderInstance + BufferUploader
 * 可以让所有状态变更都经过原版缓存。
 * <p>
 * 注意：json 中必须与原版 61 个 core shader 一致声明标准 "blend" 字段（add / srcalpha / 1-srcalpha）。
 * 原版 {@link com.mojang.blaze3d.shaders.BlendMode#apply()} 仅在 {@code !this.equals(lastApplied)} 时改写 GL 状态；
 * 若省略 "blend"，MC 会默认构造 {@code opaque = true}，反而会在首次 apply 时强行关闭混合并在退出管线后污染后续原版绘制。
 * 配合 {@link FxPipeline} 入口对齐 {@code lastApplied}，管线内部各通道的真实混合模式统一由 {@code RenderSystem} 精确控制。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, bus = Bus.MOD, value = Dist.CLIENT)
public final class FxShaders {
   @Nullable public static ShaderInstance world;
   @Nullable public static ShaderInstance depthCopy;
   @Nullable public static ShaderInstance bloomDown;
   @Nullable public static ShaderInstance bloomUp;
   @Nullable public static ShaderInstance composite;
   @Nullable public static ShaderInstance fractureScene;
   @Nullable public static ShaderInstance fractureVolume;
   @Nullable public static ShaderInstance vacuumScene;
   @Nullable public static ShaderInstance vacuumVolume;
   @Nullable public static ShaderInstance descentScene;
   @Nullable public static ShaderInstance descentVolume;
   @Nullable public static ShaderInstance bloodScene;
   @Nullable public static ShaderInstance bloodVolume;
   @Nullable public static ShaderInstance sliceScene;
   @Nullable public static ShaderInstance woundScene;
   @Nullable public static ShaderInstance woundVolume;
   @Nullable public static ShaderInstance foldScene;
   @Nullable public static ShaderInstance foldVolume;
   @Nullable public static ShaderInstance eraseScene;
   @Nullable public static ShaderInstance eraseVolume;

   private static int failures;

   private FxShaders() {
   }

   @SubscribeEvent
   public static void onRegisterShaders(RegisterShadersEvent event) {
      failures = 0;
      register(event, "fx_world", DefaultVertexFormat.POSITION_COLOR_TEX, shader -> world = shader);
      register(event, "fx_depth_copy", DefaultVertexFormat.POSITION, shader -> depthCopy = shader);
      register(event, "fx_bloom_down", DefaultVertexFormat.POSITION, shader -> bloomDown = shader);
      register(event, "fx_bloom_up", DefaultVertexFormat.POSITION, shader -> bloomUp = shader);
      register(event, "fx_composite", DefaultVertexFormat.POSITION, shader -> composite = shader);
      register(event, "fx_fracture_scene", DefaultVertexFormat.POSITION, shader -> fractureScene = shader);
      register(event, "fx_fracture_volume", DefaultVertexFormat.POSITION, shader -> fractureVolume = shader);
      register(event, "fx_vacuum_scene", DefaultVertexFormat.POSITION, shader -> vacuumScene = shader);
      register(event, "fx_vacuum_volume", DefaultVertexFormat.POSITION, shader -> vacuumVolume = shader);
      register(event, "fx_descent_scene", DefaultVertexFormat.POSITION, shader -> descentScene = shader);
      register(event, "fx_descent_volume", DefaultVertexFormat.POSITION, shader -> descentVolume = shader);
      register(event, "fx_blood_scene", DefaultVertexFormat.POSITION, shader -> bloodScene = shader);
      register(event, "fx_blood_volume", DefaultVertexFormat.POSITION, shader -> bloodVolume = shader);
      register(event, "fx_slice_scene", DefaultVertexFormat.POSITION, shader -> sliceScene = shader);
      register(event, "fx_wound_scene", DefaultVertexFormat.POSITION, shader -> woundScene = shader);
      register(event, "fx_wound_volume", DefaultVertexFormat.POSITION, shader -> woundVolume = shader);
      register(event, "fx_fold_scene", DefaultVertexFormat.POSITION, shader -> foldScene = shader);
      register(event, "fx_fold_volume", DefaultVertexFormat.POSITION, shader -> foldVolume = shader);
      register(event, "fx_erase_scene", DefaultVertexFormat.POSITION, shader -> eraseScene = shader);
      register(event, "fx_erase_volume", DefaultVertexFormat.POSITION, shader -> eraseVolume = shader);
      if (failures == 0) {
         Annihilationblade.LOGGER.info("[AnnihilationBlade FX] All renderer shaders registered.");
      }
      FxPipeline.INSTANCE.onShadersReloaded();
   }

   private static void register(RegisterShadersEvent event, String name, VertexFormat format, Consumer<ShaderInstance> sink) {
      try {
         event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, name), format), sink);
      } catch (IOException | RuntimeException exception) {
         failures++;
         sink.accept(null);
         Annihilationblade.LOGGER.error("[AnnihilationBlade FX] Failed to load shader {}: {}", name, exception.toString());
      }
   }

   /** 渲染器运行所需的核心着色器是否齐全。 */
   public static boolean coreReady() {
      return world != null && depthCopy != null && composite != null;
   }
}
