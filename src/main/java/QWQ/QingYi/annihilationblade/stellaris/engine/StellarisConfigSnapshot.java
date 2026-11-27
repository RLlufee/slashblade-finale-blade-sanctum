package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.config.ModConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹渲染引擎帧级不可变配置快照（Step 1.6）。
 * <p>
 * 借鉴不可变配置快照思想：在每帧入口一次性将 Jupiter 配置条目读取并冻结为不可变 {@code record}，
 * 既避免渲染热路径每帧重复触发数十次 {@code ConfigEntry.getValue()} 与自动拆箱，
 * 又杜绝用户在配置 UI 敲击输入字符期间造成同一渲染帧前半段与后半段配置撕裂。
 */
@OnlyIn(Dist.CLIENT)
public record StellarisConfigSnapshot(
   boolean rendererEnabled,
   boolean volumetricEnabled,
   boolean bloomEnabled,
   float bloomStrength,
   float screenFlash,
   float chromaticAberration,
   boolean withShaderPack,
   ModConfig.StellarisQualityPreset qualityPreset,
   boolean worldCaptureEnabled,
   boolean anamorphicStreaks,
   ModConfig.ImpactFrameMode impactFrames,
   float impactFrameIntensity,
   boolean fullImpactWarningAcknowledged,
   boolean flashBlindnessEnabled,
   boolean audioSilencingEnabled,
   ModConfig.SaCinematicMode saCinematicMode,
   boolean bladeCosmicParallax,
   boolean auraEnabled,
   float auraAlpha,
   boolean gpuAdaptive,
   boolean disableHeavyPassesWithShaderPack,
   boolean irisDeferHandWithShaderPack,
   boolean relief3dEnabled
) {
   private static volatile StellarisConfigSnapshot latest = captureNow();

   /** 立即从 ModConfig 捕获一份原子一致的最新快照并发布为最新帧快照。 */
   public static StellarisConfigSnapshot refresh() {
      StellarisConfigSnapshot snap = captureNow();
      latest = snap;
      return snap;
   }

   /** 返回最近一次刷新的不可变配置快照（无锁、零分配）。 */
   public static StellarisConfigSnapshot current() {
      return latest;
   }

   private static StellarisConfigSnapshot captureNow() {
      ModConfig.ClientRenderer r = ModConfig.CLIENT.clientRenderer;
      ModConfig.StellarisEngine s = ModConfig.CLIENT.stellarisEngine;
      return new StellarisConfigSnapshot(
         Boolean.TRUE.equals(r.enabled.getValue()),
         Boolean.TRUE.equals(r.volumetric.getValue()),
         Boolean.TRUE.equals(r.bloom.getValue()),
         clampFloat(r.bloomStrength.getValue(), 0.0F, 2.5F, 1.0F),
         clampFloat(r.screenFlash.getValue(), 0.0F, 1.0F, 1.0F),
         clampFloat(r.chromaticAberration.getValue(), 0.0F, 2.0F, 1.0F),
         Boolean.TRUE.equals(r.withShaderPack.getValue()),
         s.qualityPreset.getValue() != null ? s.qualityPreset.getValue() : ModConfig.StellarisQualityPreset.CINEMATIC,
         Boolean.TRUE.equals(s.worldCaptureEnabled.getValue()),
         Boolean.TRUE.equals(s.anamorphicStreaks.getValue()),
         s.impactFrames.getValue() != null ? s.impactFrames.getValue() : ModConfig.ImpactFrameMode.SAFE,
         clampFloat(s.impactFrameIntensity.getValue(), 0.0F, 1.0F, 1.0F),
         Boolean.TRUE.equals(s.fullImpactWarningAcknowledged.getValue()),
         Boolean.TRUE.equals(s.flashBlindnessEnabled.getValue()),
         Boolean.TRUE.equals(s.audioSilencingEnabled.getValue()),
         s.saCinematicMode.getValue() != null ? s.saCinematicMode.getValue() : ModConfig.SaCinematicMode.COMPACT_2S,
         Boolean.TRUE.equals(s.bladeCosmicParallax.getValue()),
         Boolean.TRUE.equals(s.auraEnabled.getValue()),
         clampFloat(s.auraAlpha.getValue(), 0.0F, 1.0F, 1.0F),
         Boolean.TRUE.equals(s.gpuAdaptive.getValue()),
         Boolean.TRUE.equals(s.disableHeavyPassesWithShaderPack.getValue()),
         Boolean.TRUE.equals(s.irisDeferHandWithShaderPack.getValue()),
         Boolean.TRUE.equals(s.relief3dEnabled.getValue())
      );
   }

   private static float clampFloat(Double val, float min, float max, float fallback) {
      if (val == null || !Double.isFinite(val)) {
         return fallback;
      }
      return Math.max(min, Math.min(max, val.floatValue()));
   }
}
