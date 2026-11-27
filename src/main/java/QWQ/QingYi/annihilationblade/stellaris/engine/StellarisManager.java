package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.render.client.core.FxCameraShake;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.stellaris.optics.AudioSilencer;
import QWQ.QingYi.annihilationblade.stellaris.optics.StellarisOpticsState;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisParticles;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisRibbons;
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
 * 星穹引擎（Stellaris Engine）客户端调度中心（Step 1.6 升级版）。
 * <p>
 * <ul>
 *   <li>支持持续句柄替换时的平滑取消淡出剥离（{@link StellarisEffect#detachHandleForFade(int)}），绝不 1 帧硬切；</li>
 *   <li>新增 {@link #renderPreTranslucent(FxFrame)} 钩子，于 {@code AFTER_BLOCK_ENTITIES} 执行前透明星穹绘制；</li>
 *   <li>新增暂停安全震屏时钟（单人 ESC 暂停时画面稳如泰山不抽搐）；</li>
 *   <li>统一托管万级 GPU 实例化粒子池 {@link StellarisParticles} 与 3D 能量丝带池 {@link StellarisRibbons}。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisManager {
   private static final int MAX_ACTIVE = 32;
   private static final List<StellarisEffect> ACTIVE = new ArrayList<>();
   private static final List<StellarisEffect> PENDING_ADD = new ArrayList<>();
   private static final Int2ObjectOpenHashMap<StellarisEffect> SUSTAINED = new Int2ObjectOpenHashMap<>();
   private static final StellarisOpticsState OPTICS = new StellarisOpticsState();
   private static final StellarisParticles PARTICLES = new StellarisParticles();
   private static final StellarisRibbons RIBBONS = new StellarisRibbons();

   private static boolean ticking;
   private static ClientLevel lastLevel;
   private static float shake;
   private static float fovKick;
   private static double shakeClockSec;
   private static long lastShakeNs;
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
         // 优先淘汰正在取消淡出或最旧的一次性特效，保护持续大招不中断
         int victim = 0;
         for (int i = 0; i < ACTIVE.size(); i++) {
            StellarisEffect cand = ACTIVE.get(i);
            if (cand.isCancelling() || cand.handle() < 0) {
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
            // 剥离旧句柄进入平滑取消淡出，而非生硬删帧
            previous.detachHandleForFade(10);
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

   public static StellarisRibbons ribbons() {
      return RIBBONS;
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
      RIBBONS.resetFrame();
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
      StellarisConfigSnapshot.refresh();
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

   /** 于 {@code AFTER_BLOCK_ENTITIES}（不透明方块/实体刚渲染完毕、水体与透明玻璃之前）执行可选前置绘制钩子。 */
   public static void renderPreTranslucent(FxFrame frame) {
      if (ACTIVE.isEmpty() || !StellarisPipeline.INSTANCE.active()) {
         return;
      }
      for (StellarisEffect effect : ACTIVE) {
         if (effect.focus().distanceTo(frame.camera) <= effect.cullDistance()) {
            effect.renderPreTranslucent(frame);
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
            strongestShake = Math.max(strongestShake, effect.shake(frame) * effect.cancelAlpha(frame.partialTick));
         }
      }
      shake = strongestShake;
      fovKick = OPTICS.fovKick;

      StellarisPipeline.INSTANCE.render(frame, visible, OPTICS, PARTICLES, RIBBONS);
   }

   private static void contributeShake(float partialTick, float[] out) {
      Minecraft mc = Minecraft.getInstance();
      long now = System.nanoTime();
      if (!mc.isPaused()) {
         if (lastShakeNs != 0L) {
            double dt = Math.max(0.0, Math.min(0.1, (now - lastShakeNs) / 1.0E9));
            shakeClockSec += dt;
         }
      }
      lastShakeNs = now;

      float amount = shake * shake;
      if (amount <= 1.0E-4F || mc.isPaused()) {
         return;
      }
      double t = shakeClockSec;
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
