package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.render.client.core.FxCameraShake;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.stellaris.optics.AudioSilencer;
import QWQ.QingYi.annihilationblade.stellaris.optics.StellarisOpticsState;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisParticles;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹引擎（Stellaris Engine）客户端调度中心。
 * <p>
 * 支持一次性特效与带服务端同步句柄的持续特效（领域 / 结界 / SA），满载时优先淘汰一次性特效以保护持续大招不中断。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisManager {
   private static final int MAX_ACTIVE = 32;
   private static final List<StellarisEffect> ACTIVE = new ArrayList<>();
   private static final List<StellarisEffect> PENDING_ADD = new ArrayList<>();
   private static final Int2ObjectOpenHashMap<StellarisEffect> SUSTAINED = new Int2ObjectOpenHashMap<>();
   private static final StellarisOpticsState OPTICS = new StellarisOpticsState();
   private static final StellarisParticles PARTICLES = new StellarisParticles();

   private static boolean ticking;
   private static ClientLevel lastLevel;
   private static float shake;
   private static float fovKick;
   private static boolean shakeRegistered;

   private StellarisManager() {
   }

   public static void ensureShakeRegistered() {
      if (!shakeRegistered) {
         shakeRegistered = true;
         FxCameraShake.addSource(StellarisManager::contributeShake);
      }
   }

   public static void add(StellarisEffect effect) {
      if (effect == null) {
         return;
      }
      ensureShakeRegistered();
      if (ticking) {
         PENDING_ADD.add(effect);
         return;
      }
      addNow(effect);
   }

   private static void addNow(StellarisEffect effect) {
      ClientLevel level = Minecraft.getInstance().level;
      if (level != lastLevel) {
         lastLevel = level;
         clear();
      }
      while (ACTIVE.size() >= MAX_ACTIVE) {
         // 优先淘汰最旧的一次性特效，保留持续特效（曲率领域、SA 等）
         int victim = 0;
         for (int i = 0; i < ACTIVE.size(); i++) {
            if (ACTIVE.get(i).handle() < 0) {
               victim = i;
               break;
            }
         }
         remove(ACTIVE.remove(victim));
      }
      ACTIVE.add(effect);
      if (effect.handle() >= 0) {
         StellarisEffect previous = SUSTAINED.put(effect.handle(), effect);
         if (previous != null && previous != effect) {
            ACTIVE.remove(previous);
            previous.onRemoved();
         }
      }
   }

   /** 按服务端同步句柄查找持续星穹特效（UPDATE / STOP 使用）。 */
   @Nullable
   public static StellarisEffect sustained(int handle) {
      return SUSTAINED.get(handle);
   }

   private static void remove(StellarisEffect effect) {
      if (effect.handle() >= 0) {
         SUSTAINED.remove(effect.handle(), effect);
      }
      effect.onRemoved();
   }

   public static StellarisParticles particles() {
      return PARTICLES;
   }

   public static int activeEffectCount() {
      return ACTIVE.size();
   }

   public static void clear() {
      for (StellarisEffect effect : ACTIVE) {
         effect.onRemoved();
      }
      ACTIVE.clear();
      PENDING_ADD.clear();
      SUSTAINED.clear();
      PARTICLES.clear();
      StellarisCloudSuppression.clear();
      AudioSilencer.restoreNow();
      shake = 0.0F;
      fovKick = 0.0F;
   }

   public static boolean hasWorkToRender(Vec3 cameraPos) {
      if (!StellarisPipeline.INSTANCE.active()) {
         return false;
      }
      if (!PARTICLES.isEmpty() || StellarisPipeline.INSTANCE.holdFrame().active()) {
         return true;
      }
      for (StellarisEffect effect : ACTIVE) {
         if (effect.focus().distanceTo(cameraPos) <= effect.cullDistance()) {
            return true;
         }
      }
      return false;
   }

   public static void onClientTick(ClientLevel level, boolean paused) {
      ensureShakeRegistered();
      if (level != lastLevel) {
         lastLevel = level;
         clear();
      }
      AudioSilencer.tick();
      if (level == null || paused) {
         return;
      }

      StellarisCloudSuppression.tick();
      StellarisPipeline.INSTANCE.quality().tick();
      PARTICLES.setQualityLimit(StellarisPipeline.INSTANCE.quality().effectiveSpec().maxParticles());
      PARTICLES.tick();

      if (ACTIVE.isEmpty()) {
         return;
      }

      boolean engineActive = StellarisPipeline.INSTANCE.active();
      ticking = true;
      try {
         Iterator<StellarisEffect> it = ACTIVE.iterator();
         while (it.hasNext()) {
            StellarisEffect effect = it.next();
            try {
               effect.advance(level, engineActive, PARTICLES);
            } catch (RuntimeException exception) {
               Annihilationblade.LOGGER.error("[Stellaris Engine] Effect tick failed, removing {}", effect.getClass().getSimpleName(), exception);
               it.remove();
               remove(effect);
               continue;
            }
            if (effect.finished()) {
               it.remove();
               remove(effect);
            }
         }
      } finally {
         ticking = false;
         if (!PENDING_ADD.isEmpty()) {
            List<StellarisEffect> spawned = new ArrayList<>(PENDING_ADD);
            PENDING_ADD.clear();
            for (StellarisEffect child : spawned) {
               addNow(child);
            }
         }
      }
   }

   public static void renderFrame(FxFrame frame) {
      shake = 0.0F;
      fovKick = 0.0F;
      if (!StellarisPipeline.INSTANCE.active()) {
         StellarisPipeline.INSTANCE.gpuTimer().recordIdleFrame();
         return;
      }

      if (!hasWorkToRender(frame.camera)) {
         StellarisPipeline.INSTANCE.gpuTimer().recordIdleFrame();
         StellarisPipeline.INSTANCE.tickWarmupIfIdle();
         return;
      }

      List<StellarisEffect> visible = new ArrayList<>(ACTIVE.size());
      OPTICS.reset();
      float strongestShake = 0.0F;
      for (StellarisEffect effect : ACTIVE) {
         if (effect.focus().distanceTo(frame.camera) <= effect.cullDistance()) {
            visible.add(effect);
            effect.contributeOptics(frame, OPTICS);
            strongestShake = Math.max(strongestShake, effect.shake(frame));
         }
      }
      shake = strongestShake;
      fovKick = OPTICS.fovKick;

      StellarisPipeline.INSTANCE.render(frame, visible, OPTICS, PARTICLES);
   }

   private static void contributeShake(float partialTick, float[] out) {
      float amount = shake * shake;
      if (amount <= 1.0E-4F) {
         return;
      }
      double t = (System.nanoTime() % 100_000_000_000L) / 1.0E9;
      out[0] += (float)(Math.sin(t * 41.0) * 0.6 + Math.sin(t * 67.0) * 0.4) * 2.4F * amount;
      out[1] += (float)(Math.sin(t * 47.0 + 1.1) * 0.6 + Math.sin(t * 73.0) * 0.4) * 2.0F * amount;
      out[2] += (float)Math.sin(t * 31.0 + 0.5) * 2.6F * amount;
   }

   public static float fovKick() {
      return fovKick;
   }

   public static void markIdleFrame() {
      shake = 0.0F;
      fovKick = 0.0F;
      StellarisPipeline.INSTANCE.gpuTimer().recordIdleFrame();
   }

   public static void onLoggingOut() {
      clear();
      StellarisPipeline.INSTANCE.release();
   }

   public static String probeViewAreaAccess() {
      try {
         LevelRenderer lr = Minecraft.getInstance().levelRenderer;
         if (lr == null) {
            return "LevelRenderer=null";
         }
         ViewArea area = lr.viewArea;
         if (area == null) {
            return "AT OK (viewArea=null, not in world)";
         }
         int count = area.chunks == null ? 0 : area.chunks.length;
         return "AT OK (chunks=" + count + ")";
      } catch (Throwable throwable) {
         return "AT FAIL: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
      }
   }
}
