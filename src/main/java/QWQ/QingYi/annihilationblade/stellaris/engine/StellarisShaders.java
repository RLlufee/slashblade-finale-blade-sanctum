package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisTboShaders;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
 * 星穹引擎（Stellaris Engine）着色器注册与 MRT 校验中心（Step 1.6 升级版）。
 * <p>
 * <ul>
 *   <li>新增 GPU 距离场烘焙 {@code st_rune_cache} 与 3D 物理圆管浮雕 {@code st_relief}（2 附件 MRT）；</li>
 *   <li>支持双输出 {@code SCENE} 着色器（{@code outColor + outEmit}）自动识别与绑定，打通阶段 4 HDR 泛光与星芒链路；</li>
 *   <li>若老显卡触发 {@code glLinkProgram} 重绑 MRT 槽位，自动经反射调用 {@code ShaderInstance#updateLocations} 同步刷新全部 uniform 与 sampler 位置，彻底杜绝老驱动黑屏。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, bus = Bus.MOD, value = Dist.CLIENT)
public final class StellarisShaders {
   @Nullable public static ShaderInstance sceneIngest;
   @Nullable public static ShaderInstance geometry;
   @Nullable public static ShaderInstance runeCache;
   @Nullable public static ShaderInstance relief;
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

   private static final Set<ShaderInstance> SCENE_DUAL_EMIT = Collections.newSetFromMap(new IdentityHashMap<>());

   private static int failures;
   private static boolean mrtVerified;
   private static boolean reliefMrtVerified;
   private static String mrtDiagnostic = "unverified";
   private static String reliefDiagnostic = "unverified";
   private static Method cachedUpdateLocations;
   private static boolean updateLocationsLookedUp;

   private StellarisShaders() {
   }

   @SubscribeEvent
   public static void onRegisterShaders(RegisterShadersEvent event) {
      failures = 0;
      mrtVerified = false;
      reliefMrtVerified = false;
      mrtDiagnostic = "pending";
      reliefDiagnostic = "pending";
      SCENE_DUAL_EMIT.clear();

      register(event, "st_scene_ingest", DefaultVertexFormat.POSITION, s -> sceneIngest = s);
      register(event, "st_geometry", DefaultVertexFormat.POSITION_COLOR_TEX, s -> geometry = s);
      register(event, "st_rune_cache", DefaultVertexFormat.POSITION, s -> runeCache = s);
      register(event, "st_relief", DefaultVertexFormat.POSITION, s -> {
         relief = s;
         if (s != null) {
            reliefMrtVerified = verifyTwoAttachmentMrt(s, "outColor", "outAux", true);
         } else {
            reliefDiagnostic = "compile_failed";
         }
      });
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
      register(event, "st_debug_scene", DefaultVertexFormat.POSITION, s -> {
         debugScene = s;
         if (s != null) {
            registerSceneDualEmitIfPresent(s);
         }
      });

      StellarisTboShaders.INSTANCE.onResourceReload(event.getResourceProvider());
      StellarisProceduralAssets.INSTANCE.startBackgroundBake();
      StellarisPipeline.INSTANCE.onShadersReloaded();
      if (failures == 0) {
         Annihilationblade.LOGGER.info("[Stellaris Engine] All 14 core shaders + 2 TBO instanced shaders queued for registration.");
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

   /** 检测并验证 SCENE Pass 着色器是否声明了双输出（outColor=0, outEmit=1）。 */
   public static boolean registerSceneDualEmitIfPresent(@Nullable ShaderInstance shader) {
      if (shader == null) {
         return false;
      }
      int program = shader.getId();
      if (GL30.glGetFragDataLocation(program, "outEmit") < 0 && GL30.glGetFragDataLocation(program, "outColor") < 0) {
         return false;
      }
      if (verifyTwoAttachmentMrt(shader, "outColor", "outEmit", false)) {
         SCENE_DUAL_EMIT.add(shader);
         return true;
      }
      return false;
   }

   /** 返回该 SCENE 着色器是否启用了双附件输出（outColor + outEmit）。 */
   public static boolean hasSceneDualEmit(@Nullable ShaderInstance shader) {
      return shader != null && SCENE_DUAL_EMIT.contains(shader);
   }

   /** 校验 3 附件重型 MRT 着色器（0=outColor, 1=outAux, 2=outEmissive），老驱动重链时同步刷新 uniform location。 */
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
      if (linkOk != 0 && loc0 == 0 && loc1 == 1 && loc2 == 2 && refreshShaderLocations(shader)) {
         mrtVerified = true;
         mrtDiagnostic = "OK(0/1/2 rebound+synced)";
         Annihilationblade.LOGGER.warn("[Stellaris Engine] MRT bindings rebound & uniform locations synced on {}.", shader.getName());
         return true;
      }

      mrtVerified = false;
      mrtDiagnostic = "FAIL(" + loc0 + "/" + loc1 + "/" + loc2 + ",link=" + linkOk + ")";
      Annihilationblade.LOGGER.error("[Stellaris Engine] MRT binding verification failed on {}: {}", shader.getName(), mrtDiagnostic);
      return false;
   }

   /** 校验 2 附件 MRT 着色器（用于 st_relief 与双输出 SCENE Pass），重链时同步刷新 uniform location。 */
   private static boolean verifyTwoAttachmentMrt(ShaderInstance shader, String name0, String name1, boolean isRelief) {
      int program = shader.getId();
      int loc0 = GL30.glGetFragDataLocation(program, name0);
      int loc1 = GL30.glGetFragDataLocation(program, name1);
      if (loc0 == 0 && loc1 == 1) {
         if (isRelief) {
            reliefDiagnostic = "OK(0/1 explicit)";
         }
         return true;
      }
      GL30.glBindFragDataLocation(program, 0, name0);
      GL30.glBindFragDataLocation(program, 1, name1);
      GL20.glLinkProgram(program);
      int linkOk = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS);
      loc0 = GL30.glGetFragDataLocation(program, name0);
      loc1 = GL30.glGetFragDataLocation(program, name1);
      if (linkOk != 0 && loc0 == 0 && loc1 == 1 && refreshShaderLocations(shader)) {
         if (isRelief) {
            reliefDiagnostic = "OK(0/1 rebound+synced)";
         }
         return true;
      }
      if (isRelief) {
         reliefDiagnostic = "FAIL(" + loc0 + "/" + loc1 + ",link=" + linkOk + ")";
      }
      Annihilationblade.LOGGER.error("[Stellaris Engine] 2-attachment MRT verification failed on {} ({}/{}): loc={}/{}", shader.getName(), name0, name1, loc0, loc1);
      return false;
   }

   /** 反射触发 Mojang {@code ShaderInstance#updateLocations()}（兼容官方名与 1.20.1 SRG 名 {@code m_173355_}）。 */
   private static boolean refreshShaderLocations(ShaderInstance shader) {
      if (!updateLocationsLookedUp) {
         updateLocationsLookedUp = true;
         for (String name : new String[]{"updateLocations", "m_173355_"}) {
            try {
               Method m = ShaderInstance.class.getDeclaredMethod(name);
               m.setAccessible(true);
               cachedUpdateLocations = m;
               break;
            } catch (NoSuchMethodException ignored) {
            }
         }
      }
      if (cachedUpdateLocations == null) {
         return false;
      }
      try {
         cachedUpdateLocations.invoke(shader);
         return true;
      } catch (Throwable throwable) {
         Annihilationblade.LOGGER.error("[Stellaris Engine] Failed to refresh uniform locations after MRT relink on {}.", shader.getName(), throwable);
         return false;
      }
   }

   /** 返回适合 1×1 离屏预热的着色器列表。 */
   public static List<ShaderInstance> warmupCandidates() {
      List<ShaderInstance> list = new ArrayList<>(12);
      addIfPresent(list, sceneIngest);
      addIfPresent(list, runeCache);
      addIfPresent(list, relief);
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

   public static boolean reliefReady() {
      return reliefMrtVerified && relief != null && runeCache != null;
   }

   public static int failureCount() {
      return failures;
   }

   public static String mrtDiagnostic() {
      return mrtDiagnostic;
   }

   public static String reliefDiagnostic() {
      return reliefDiagnostic;
   }
}
