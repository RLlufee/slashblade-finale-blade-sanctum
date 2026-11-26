package QWQ.QingYi.annihilationblade.stellaris.optics;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 星穹引擎每帧聚合的光学与演出状态。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisOpticsState {
   public static final int MAX_GODRAY_SOURCES = 2;

   // 1. 泛光与变形水平星芒（Anamorphic Streak）
   public float bloomMultiplier = 1.0F;
   public float streakStrength;
   public float streakThreshold = 1.85F;
   public float streakTintR = 0.66F;
   public float streakTintG = 0.96F;
   public float streakTintB = 1.00F;

   // 2. 自发光专属径向体积光（God Rays，最多 2 个屏幕投影光源）
   public int godRayCount;
   public final float[] godRayU = new float[MAX_GODRAY_SOURCES];
   public final float[] godRayV = new float[MAX_GODRAY_SOURCES];
   public final float[] godRayStrength = new float[MAX_GODRAY_SOURCES];
   public final float[] godRayDecay = new float[MAX_GODRAY_SOURCES];
   public final float[] godRayR = new float[MAX_GODRAY_SOURCES];
   public final float[] godRayG = new float[MAX_GODRAY_SOURCES];
   public final float[] godRayB = new float[MAX_GODRAY_SOURCES];

   // 3. 基准 1.0 防过曝自动曝光控制（常态 1.0 不扭曲画面，仅高能爆发时收缩压稳）
   public float autoExposureWeight;
   public float exposureThreshold = 0.45F;
   public float exposureMin = 0.36F;
   public float exposureSlope = 0.55F;

   // 4. Hold-Frame 视网膜闪盲残像触发请求
   public boolean captureHoldFrame;
   public float holdInitialStrength;
   public float holdDecaySeconds = 0.95F;
   public float holdTintR = 0.92F;
   public float holdTintG = 0.97F;
   public float holdTintB = 1.00F;

   // 5. 12fps 动漫级多风格冲击帧请求
   public ImpactFrameSequencer.Preset impactPreset;
   public float impactElapsedSeconds;
   public float impactStrength;

   // 6. 电影级镜头与世界调色
   public float worldDimming;
   public float desaturate;
   public float aberration;
   public float zoomBlur;
   public float centerU = 0.5F;
   public float centerV = 0.5F;
   public float vignette;
   public float vignetteR = 0.01F;
   public float vignetteG = 0.02F;
   public float vignetteB = 0.06F;
   public float flash;
   public float flashR = 0.94F;
   public float flashG = 0.99F;
   public float flashB = 1.00F;
   public float fovKick;

   // 7. 低分辨率背景/折射重绘区域的乘法高频细节回传强度
   public float detailPreservation = 0.50F;

   public void reset() {
      this.bloomMultiplier = 1.0F;
      this.streakStrength = 0.0F;
      this.streakThreshold = 1.85F;
      this.streakTintR = 0.66F;
      this.streakTintG = 0.96F;
      this.streakTintB = 1.00F;

      this.godRayCount = 0;
      for (int i = 0; i < MAX_GODRAY_SOURCES; i++) {
         this.godRayU[i] = 0.5F;
         this.godRayV[i] = 0.5F;
         this.godRayStrength[i] = 0.0F;
         this.godRayDecay[i] = 0.965F;
         this.godRayR[i] = 0.65F;
         this.godRayG[i] = 0.96F;
         this.godRayB[i] = 1.00F;
      }

      this.autoExposureWeight = 0.0F;
      this.exposureThreshold = 0.45F;
      this.exposureMin = 0.36F;
      this.exposureSlope = 0.55F;

      this.captureHoldFrame = false;
      this.holdInitialStrength = 0.0F;
      this.holdDecaySeconds = 0.95F;
      this.holdTintR = 0.92F;
      this.holdTintG = 0.97F;
      this.holdTintB = 1.00F;

      this.impactPreset = null;
      this.impactElapsedSeconds = 0.0F;
      this.impactStrength = 0.0F;

      this.worldDimming = 0.0F;
      this.desaturate = 0.0F;
      this.aberration = 0.0F;
      this.zoomBlur = 0.0F;
      this.centerU = 0.5F;
      this.centerV = 0.5F;
      this.vignette = 0.0F;
      this.vignetteR = 0.01F;
      this.vignetteG = 0.02F;
      this.vignetteB = 0.06F;
      this.flash = 0.0F;
      this.flashR = 0.94F;
      this.flashG = 0.99F;
      this.flashB = 1.00F;
      this.fovKick = 0.0F;
      this.detailPreservation = 0.50F;
   }

   public boolean hasScreenPost() {
      return this.worldDimming > 1.0E-3F
         || this.desaturate > 1.0E-3F
         || this.aberration > 1.0E-3F
         || this.zoomBlur > 1.0E-3F
         || this.vignette > 1.0E-3F
         || this.flash > 1.0E-3F
         || this.impactStrength > 1.0E-3F
         || this.captureHoldFrame;
   }

   public void addBloomMultiplier(float multiplier) {
      this.bloomMultiplier = Math.max(this.bloomMultiplier, Math.min(2.5F, multiplier));
   }

   public void addAnamorphicStreak(float strength, float threshold, int rgb) {
      if (strength > this.streakStrength) {
         this.streakStrength = Math.min(2.0F, strength);
         this.streakThreshold = Math.max(0.6F, threshold);
         this.streakTintR = ((rgb >> 16) & 0xFF) / 255.0F;
         this.streakTintG = ((rgb >> 8) & 0xFF) / 255.0F;
         this.streakTintB = (rgb & 0xFF) / 255.0F;
      }
   }

   /**
    * 基于世界坐标注册体积光源：用相机视线余弦 {@code dot(camForward, toSource)} 做平滑背向淡出，
    * 彻底消除玩家转头使光源越过视锥边界时的 1 帧硬跳开关感。
    */
   public void addGodRaySource(FxFrame frame, Vec3 worldPos, float strength, float decay, int rgb) {
      if (frame == null || worldPos == null || strength <= 1.0E-3F) {
         return;
      }
      Vector3f rel = frame.rel(worldPos);
      float dist = rel.length();
      if (dist < 1.0E-4F) {
         return;
      }
      Vector4f viewPos = frame.view.transform(new Vector4f(rel.x, rel.y, rel.z, 1.0F));
      float viewCos = -viewPos.z / dist;
      float t = Math.max(0.0F, Math.min(1.0F, (viewCos - 0.08F) / (0.45F - 0.08F)));
      float cosFade = t * t * (3.0F - 2.0F * t);
      if (cosFade <= 1.0E-3F) {
         return;
      }
      float[] uv = frame.screenUv(worldPos);
      this.addGodRaySource(uv, strength * cosFade, decay, rgb);
   }

   public void addGodRaySource(float[] screenUv, float strength, float decay, int rgb) {
      if (screenUv == null || strength <= 1.0E-3F) {
         return;
      }
      float u = screenUv[0];
      float v = screenUv[1];
      float off = Math.max(Math.max(-u, u - 1.0F), Math.max(-v, v - 1.0F));
      float edgeFade = 1.0F - Math.max(0.0F, Math.min(1.0F, off / 0.45F));
      float effective = strength * edgeFade * edgeFade;
      if (effective <= 1.0E-3F) {
         return;
      }

      int slot = -1;
      if (this.godRayCount < MAX_GODRAY_SOURCES) {
         slot = this.godRayCount++;
      } else {
         int weakest = 0;
         for (int i = 1; i < MAX_GODRAY_SOURCES; i++) {
            if (this.godRayStrength[i] < this.godRayStrength[weakest]) {
               weakest = i;
            }
         }
         if (effective > this.godRayStrength[weakest]) {
            slot = weakest;
         }
      }
      if (slot >= 0) {
         this.godRayU[slot] = u;
         this.godRayV[slot] = v;
         this.godRayStrength[slot] = Math.min(2.0F, effective);
         this.godRayDecay[slot] = Math.max(0.85F, Math.min(0.992F, decay));
         this.godRayR[slot] = ((rgb >> 16) & 0xFF) / 255.0F;
         this.godRayG[slot] = ((rgb >> 8) & 0xFF) / 255.0F;
         this.godRayB[slot] = (rgb & 0xFF) / 255.0F;
      }
   }

   /** 启用基准 1.0 防过曝自动曝光：超过 {@code threshold} 后按 {@code slope} 压低，最低不低于 {@code minExposure}。 */
   public void enableAutoExposure(float weight, float threshold, float minExposure, float slope) {
      if (weight > this.autoExposureWeight) {
         this.autoExposureWeight = Math.max(0.0F, Math.min(1.0F, weight));
         this.exposureThreshold = Math.max(0.15F, Math.min(2.0F, threshold));
         this.exposureMin = Math.max(0.20F, Math.min(0.95F, minExposure));
         this.exposureSlope = Math.max(0.10F, Math.min(2.5F, slope));
      }
   }

   public void triggerHoldFrame(float initialStrength, float decaySeconds, int tintRgb) {
      if (initialStrength > this.holdInitialStrength) {
         this.captureHoldFrame = true;
         this.holdInitialStrength = Math.max(0.0F, Math.min(1.0F, initialStrength));
         this.holdDecaySeconds = Math.max(0.2F, Math.min(3.5F, decaySeconds));
         this.holdTintR = ((tintRgb >> 16) & 0xFF) / 255.0F;
         this.holdTintG = ((tintRgb >> 8) & 0xFF) / 255.0F;
         this.holdTintB = (tintRgb & 0xFF) / 255.0F;
      }
   }

   public void requestImpactFrame(ImpactFrameSequencer.Preset preset, float elapsedSeconds, float strength) {
      if (preset != null && strength > this.impactStrength) {
         this.impactPreset = preset;
         this.impactElapsedSeconds = Math.max(0.0F, elapsedSeconds);
         this.impactStrength = Math.max(0.0F, Math.min(1.0F, strength));
      }
   }

   public void addWorldDimming(float amount) {
      this.worldDimming = Math.max(this.worldDimming, Math.min(0.85F, amount));
   }

   public void addDesaturate(float amount) {
      this.desaturate = Math.max(this.desaturate, Math.min(1.0F, amount));
   }

   public void addAberration(float amount) {
      this.aberration = Math.max(this.aberration, Math.min(2.0F, amount));
   }

   public void addZoomBlur(float amount, float[] centerUv) {
      if (amount > this.zoomBlur) {
         this.zoomBlur = Math.min(1.5F, amount);
         if (centerUv != null) {
            this.centerU = centerUv[0];
            this.centerV = centerUv[1];
         }
      }
   }

   public void addVignette(int rgb, float strength) {
      if (strength > this.vignette) {
         this.vignette = Math.min(1.0F, strength);
         this.vignetteR = ((rgb >> 16) & 0xFF) / 255.0F;
         this.vignetteG = ((rgb >> 8) & 0xFF) / 255.0F;
         this.vignetteB = (rgb & 0xFF) / 255.0F;
      }
   }

   public void addFlash(int rgb, float strength) {
      if (strength > this.flash) {
         this.flash = Math.min(1.0F, strength);
         this.flashR = ((rgb >> 16) & 0xFF) / 255.0F;
         this.flashG = ((rgb >> 8) & 0xFF) / 255.0F;
         this.flashB = (rgb & 0xFF) / 255.0F;
      }
   }

   public void addFovKick(float kick) {
      if (Math.abs(kick) > Math.abs(this.fovKick)) {
         this.fovKick = Math.max(-0.35F, Math.min(0.35F, kick));
      }
   }
}
