package QWQ.QingYi.annihilationblade.render.client.core;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 光影包（现代 Iris / 旧版 Coderbot Iris / Oculus / OptiFine）兼容探测中心（Step 1.6 增强版）。
 * <p>
 * <ul>
 *   <li>双包名探测 {@code net.irisshaders.iris.api.v0.IrisApi} 与 {@code net.coderbot.iris.api.v0.IrisApi}，并兼容 OptiFine {@code net.optifine.shaders.Shaders.shaderPackLoaded}；</li>
 *   <li>{@link #shaderPackActive()} 带 250ms 缓存，避免每渲染帧重复反射；</li>
 *   <li>{@link #shadowPass()} 使用 {@link MethodHandle} 无装箱实时查询，保证阴影 Pass 剔除零延迟零反射开销。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class FxCompat {
   private static final String[] IRIS_API_CLASSES = new String[]{
      "net.irisshaders.iris.api.v0.IrisApi",
      "net.coderbot.iris.api.v0.IrisApi"
   };
   private static final long PACK_CACHE_NS = 250_000_000L;

   private static boolean resolved;
   private static boolean irisLoaded;
   private static boolean optifineLoaded;
   private static Object irisApi;
   private static MethodHandle shaderPackInUseHandle;
   private static MethodHandle renderingShadowPassHandle;
   private static Field optifineShaderPackField;

   private static long lastPackQueryNs;
   private static boolean cachedPackActive;
   private static boolean packCacheValid;

   private FxCompat() {
   }

   private static synchronized void resolve() {
      if (resolved) {
         return;
      }
      resolved = true;
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();

      for (String className : IRIS_API_CLASSES) {
         try {
            Class<?> apiClass = Class.forName(className, false, FxCompat.class.getClassLoader());
            Object instance = apiClass.getMethod("getInstance").invoke(null);
            if (instance != null) {
               Method inUseMethod = apiClass.getMethod("isShaderPackInUse");
               shaderPackInUseHandle = lookup.unreflect(inUseMethod);
               try {
                  Method shadowMethod = apiClass.getMethod("isRenderingShadowPass");
                  renderingShadowPassHandle = lookup.unreflect(shadowMethod);
               } catch (NoSuchMethodException ignored) {
                  renderingShadowPassHandle = null;
               }
               irisApi = instance;
               irisLoaded = true;
               Annihilationblade.LOGGER.info("[AnnihilationBlade FX] Iris/Oculus API detected ({}), shader-pack compatibility path enabled.", className);
               return;
            }
         } catch (Throwable ignored) {
         }
      }

      try {
         Class<?> optifineClass = Class.forName("net.optifine.shaders.Shaders", false, FxCompat.class.getClassLoader());
         optifineShaderPackField = optifineClass.getField("shaderPackLoaded");
         optifineLoaded = true;
         Annihilationblade.LOGGER.info("[AnnihilationBlade FX] OptiFine shader API detected.");
      } catch (Throwable ignored) {
         optifineShaderPackField = null;
      }
   }

   /** 是否检测到 Iris 或 Oculus（用于决定是否允许启用延迟手部重绘兼容）。 */
   public static boolean isIrisLoaded() {
      resolve();
      return irisLoaded;
   }

   /** 当前是否有光影包正在启用（250ms 缓存，热路径零反射开销）。 */
   public static boolean shaderPackActive() {
      resolve();
      if (!irisLoaded && !optifineLoaded) {
         return false;
      }
      long now = System.nanoTime();
      if (packCacheValid && now - lastPackQueryNs < PACK_CACHE_NS) {
         return cachedPackActive;
      }
      lastPackQueryNs = now;
      packCacheValid = true;
      boolean active = false;
      try {
         if (irisApi != null && shaderPackInUseHandle != null) {
            active = (boolean)shaderPackInUseHandle.invoke(irisApi);
         } else if (optifineShaderPackField != null) {
            active = optifineShaderPackField.getBoolean(null);
         }
      } catch (Throwable ignored) {
         active = false;
      }
      cachedPackActive = active;
      return active;
   }

   /** 当前是否正处于光影阴影 Pass（实时查询，不跨帧缓存，MethodHandle 零反射开销）。 */
   public static boolean shadowPass() {
      resolve();
      if (irisApi == null || renderingShadowPassHandle == null) {
         return false;
      }
      try {
         return (boolean)renderingShadowPassHandle.invoke(irisApi);
      } catch (Throwable ignored) {
         return false;
      }
   }
}
