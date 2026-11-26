package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 星穹引擎（Stellaris Engine）着色器注册中心。
 * <p>
 * 遵循 v3 混合架构：全部着色器注册为原版 {@link ShaderInstance}（保留 {@code F3+T} 热重载与 {@code #moj_import}）；
 * 包含原生线性 HDR 几何着色器 {@code st_geometry}（彻底与旧 {@code fx_world.fsh} 解耦）、全链路光学后处理着色器、
 * 3 附件 MRT 测试着色器与全链路场景验证着色器。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, bus = Bus.MOD, value = Dist.CLIENT)
public final class StellarisShaders {
   @Nullable public static ShaderInstance sceneIngest;
   @Nullable public static ShaderInstance geometry;
   @Nullable public static ShaderInstance bloomDown;
   @Nullable public static ShaderInstance bloomUp;
   @Nullable public static ShaderInstance anamorphic;
   @Nullable public static ShaderInstance godRays;
   @Nullable public static ShaderInstance exposure;
   @Nullable public static ShaderInstance holdCopy;
   @Nullable public static ShaderInstance composite;
   @Nullable public static ShaderInstance particle;
   @Nullable public static ShaderInstance debugHeavyMrt;
   @Nullable public static ShaderInstance debugScene;

   private static int failures;
   private static boolean mrtVerified;
   private static String mrtDiagnostic = "unverified";

   private StellarisShaders() {
   }

   @SubscribeEvent
   public static void onRegisterShaders(RegisterShadersEvent event) {
      failures = 0;
      mrtVerified = false;
      mrtDiagnostic = "pending";

      register(event, "st_scene_ingest", DefaultVertexFormat.POSITION, s -> sceneIngest = s);
      register(event, "st_geometry", DefaultVertexFormat.POSITION_COLOR_TEX, s -> geometry = s);
      register(event, "st_bloom_down", DefaultVertexFormat.POSITION, s -> bloomDown = s);
      register(event, "st_bloom_up", DefaultVertexFormat.POSITION, s -> bloomUp = s);
      register(event, "st_anamorphic", DefaultVertexFormat.POSITION, s -> anamorphic = s);
      register(event, "st_godrays", DefaultVertexFormat.POSITION, s -> godRays = s);
      register(event, "st_exposure", DefaultVertexFormat.POSITION, s -> exposure = s);
      register(event, "st_hold_copy", DefaultVertexFormat.POSITION, s -> holdCopy = s);
      register(event, "st_composite", DefaultVertexFormat.POSITION, s -> composite = s);
      register(event, "st_particle", DefaultVertexFormat.POSITION_COLOR_TEX, s -> particle = s);
      register(event, "st_debug_heavy_mrt", DefaultVertexFormat.POSITION, s -> {
         debugHeavyMrt = s;
         if (s != null) {
            verifyMrtBindings(s);
         } else {
            mrtDiagnostic = "compile_failed";
         }
      });
      register(event, "st_debug_scene", DefaultVertexFormat.POSITION, s -> debugScene = s);

      StellarisProceduralAssets.INSTANCE.startBackgroundBake();
      StellarisPipeline.INSTANCE.onShadersReloaded();
      if (failures == 0) {
         Annihilationblade.LOGGER.info("[Stellaris Engine] All 12 shaders queued for registration.");
      }
   }

   private static void register(RegisterShadersEvent event, String name, VertexFormat format, Consumer<ShaderInstance> sink) {
      try {
         event.registerShader(
            new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, name), format),
            sink
         );
      } catch (IOException | RuntimeException exception) {
         failures++;
         sink.accept(null);
         Annihilationblade.LOGGER.error("[Stellaris Engine] Failed to load shader {}: {}", name, exception.toString());
      }
   }

   /**
    * 校验 MRT 片元着色器的 3 个输出槽位（0=outColor, 1=outAux, 2=outEmissive）。
    * 若显卡支持 {@code GL_ARB_explicit_attrib_location} 则直接零重链通过；
    * 极少数老驱动触发重绑时同步通过警告标出，避免静默误用旧 uniform location。
    */
   public static boolean verifyMrtBindings(ShaderInstance shader) {
      if (shader == null) {
         mrtVerified = false;
         mrtDiagnostic = "shader_null";
         return false;
      }
      int program = shader.getId();
      int loc0 = GL30.glGetFragDataLocation(program, "outColor");
      int loc1 = GL30.glGetFragDataLocation(program, "outAux");
      int loc2 = GL30.glGetFragDataLocation(program, "outEmissive");

      if (loc0 == 0 && loc1 == 1 && loc2 == 2) {
         mrtVerified = true;
         mrtDiagnostic = "OK(0/1/2 explicit)";
         Annihilationblade.LOGGER.info("[Stellaris Engine] MRT bindings verified on {}: outColor=0, outAux=1, outEmissive=2", shader.getName());
         return true;
      }

      GL30.glBindFragDataLocation(program, 0, "outColor");
      GL30.glBindFragDataLocation(program, 1, "outAux");
      GL30.glBindFragDataLocation(program, 2, "outEmissive");
      GL20.glLinkProgram(program);
      int linkOk = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS);
      loc0 = GL30.glGetFragDataLocation(program, "outColor");
      loc1 = GL30.glGetFragDataLocation(program, "outAux");
      loc2 = GL30.glGetFragDataLocation(program, "outEmissive");
      if (linkOk != 0 && loc0 == 0 && loc1 == 1 && loc2 == 2) {
         mrtVerified = true;
         mrtDiagnostic = "OK(0/1/2 rebound)";
         Annihilationblade.LOGGER.warn("[Stellaris Engine] MRT bindings rebound on {} (driver lacks explicit attrib location macro).", shader.getName());
         return true;
      }

      mrtVerified = false;
      mrtDiagnostic = "FAIL(" + loc0 + "/" + loc1 + "/" + loc2 + ",link=" + linkOk + ")";
      Annihilationblade.LOGGER.error("[Stellaris Engine] MRT binding verification failed on {}: {}", shader.getName(), mrtDiagnostic);
      return false;
   }

   /** 返回所有适合 1×1 离屏全屏预热的着色器列表。 */
   public static List<ShaderInstance> warmupCandidates() {
      List<ShaderInstance> list = new ArrayList<>(10);
      addIfPresent(list, sceneIngest);
      addIfPresent(list, bloomDown);
      addIfPresent(list, bloomUp);
      addIfPresent(list, anamorphic);
      addIfPresent(list, godRays);
      addIfPresent(list, exposure);
      addIfPresent(list, holdCopy);
      addIfPresent(list, composite);
      addIfPresent(list, debugHeavyMrt);
      addIfPresent(list, debugScene);
      return list;
   }

   private static void addIfPresent(List<ShaderInstance> list, @Nullable ShaderInstance s) {
      if (s != null) {
         list.add(s);
      }
   }

   public static boolean coreReady() {
      return sceneIngest != null && composite != null && geometry != null;
   }

   public static boolean fullOpticsReady() {
      return coreReady() && bloomDown != null && bloomUp != null && anamorphic != null && godRays != null && exposure != null && holdCopy != null;
   }

   public static boolean mrtReady() {
      return mrtVerified && debugHeavyMrt != null;
   }

   public static int failureCount() {
      return failures;
   }

   public static String mrtDiagnostic() {
      return mrtDiagnostic;
   }
}
