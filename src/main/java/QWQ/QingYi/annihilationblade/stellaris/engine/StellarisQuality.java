package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.config.ModConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹引擎 4 档画质规格表与 GPU 耗时自适应降档状态机（Step 1.6：帧快照读取 + 16384 万级 TBO 粒子预算 + 3D 浮雕步进预算）。
 */
@OnlyIn(Dist.CLIENT)
public final class StellarisQuality {
   public record Spec(
      ModConfig.StellarisQualityPreset preset,
      int blackHoleSteps,
      float heavyScaleMin,
      float heavyScaleMax,
      int captureFaceSize,
      int godRaySteps,
      boolean fullResGodRays,
      int auroraLayers,
      int maxParticles,
      int reliefSteps,
      int runeCacheSize,
      float frameBudgetMs
   ) {
      public boolean heavyEnabled() {
         return this.preset != ModConfig.StellarisQualityPreset.FALLBACK;
      }
   }

   public static final Spec FALLBACK = new Spec(
      ModConfig.StellarisQualityPreset.FALLBACK, 0, 0.5F, 0.5F, 0, 0, false, 0, 512, 0, StellarisRuneCache.SIZE_CINEMATIC, 3.0F
   );
   public static final Spec BALANCED = new Spec(
      ModConfig.StellarisQualityPreset.BALANCED, 120, 0.5F, 0.5F, 256, 12, false, 14, 2048, 56, StellarisRuneCache.SIZE_CINEMATIC, 6.0F
   );
   public static final Spec CINEMATIC = new Spec(
      ModConfig.StellarisQualityPreset.CINEMATIC, 220, 0.6F, 1.0F, 384, 24, false, 24, 8192, 84, StellarisRuneCache.SIZE_CINEMATIC, 8.5F
   );
   public static final Spec ULTIMATE = new Spec(
      ModConfig.StellarisQualityPreset.ULTIMATE, 400, 1.0F, 1.0F, 512, 40, true, 24, 16384, 112, StellarisRuneCache.SIZE_ULTIMATE, 11.5F
   );

   private static final float[] SCALE_LADDER = new float[]{0.60F, 0.72F, 0.85F, 1.00F};
   private static final int OVER_BUDGET_FRAMES = 30;
   private static final int UNDER_BUDGET_FRAMES = 120;
   private static final int SCALE_STEP_FRAMES = 18;
   private static final int LOCK_TICKS = 100;
   private static final int SCALE_COOLDOWN_TICKS = 30;

   private ModConfig.StellarisQualityPreset debugOverride;
   private int adaptiveDropLevels;
   private int overBudgetStreak;
   private int underBudgetStreak;
   private int scaleOverStreak;
   private int scaleUnderStreak;
   private int downgradeLockTicks;
   private int scaleCooldownTicks;
   private int scaleLadderIdx = 2; // 默认 0.85x

   public static Spec of(ModConfig.StellarisQualityPreset preset) {
      if (preset == null) {
         return CINEMATIC;
      }
      return switch (preset) {
         case FALLBACK -> FALLBACK;
         case BALANCED -> BALANCED;
         case CINEMATIC -> CINEMATIC;
         case ULTIMATE -> ULTIMATE;
      };
   }

   public ModConfig.StellarisQualityPreset configuredPreset() {
      if (this.debugOverride != null) {
         return this.debugOverride;
      }
      return StellarisConfigSnapshot.current().qualityPreset();
   }

   public Spec effectiveSpec() {
      ModConfig.StellarisQualityPreset base = this.configuredPreset();
      if (!StellarisConfigSnapshot.current().gpuAdaptive() || this.adaptiveDropLevels <= 0) {
         return of(base);
      }
      int ordinal = Math.max(0, base.ordinal() - this.adaptiveDropLevels);
      ModConfig.StellarisQualityPreset[] values = ModConfig.StellarisQualityPreset.values();
      return of(values[Math.min(ordinal, values.length - 1)]);
   }

   /** 返回本帧重型 MRT / 3D 浮雕 Pass 的离散动态分辨率缩放比例（0.5 ~ 1.0）。 */
   public float currentHeavyScale() {
      Spec spec = this.effectiveSpec();
      if (spec.heavyScaleMin >= spec.heavyScaleMax - 1.0E-4F) {
         return spec.heavyScaleMax;
      }
      float chosen = SCALE_LADDER[Math.max(0, Math.min(SCALE_LADDER.length - 1, this.scaleLadderIdx))];
      return Math.max(spec.heavyScaleMin, Math.min(spec.heavyScaleMax, chosen));
   }

   public int adaptiveDropLevels() {
      return this.adaptiveDropLevels;
   }

   public void setDebugOverride(ModConfig.StellarisQualityPreset preset) {
      this.debugOverride = preset;
      this.resetAdaptive();
   }

   public void resetAdaptive() {
      this.adaptiveDropLevels = 0;
      this.overBudgetStreak = 0;
      this.underBudgetStreak = 0;
      this.scaleOverStreak = 0;
      this.scaleUnderStreak = 0;
      this.downgradeLockTicks = 0;
      this.scaleCooldownTicks = 0;
      this.scaleLadderIdx = 2;
   }

   public void tick() {
      if (this.downgradeLockTicks > 0) {
         this.downgradeLockTicks--;
      }
      if (this.scaleCooldownTicks > 0) {
         this.scaleCooldownTicks--;
      }
   }

   public void recordFrameGpuMs(float totalMs) {
      if (!StellarisConfigSnapshot.current().gpuAdaptive() || totalMs <= 0.01F || !Float.isFinite(totalMs)) {
         return;
      }
      Spec spec = this.effectiveSpec();
      float budget = spec.frameBudgetMs;

      if (spec.heavyScaleMax > spec.heavyScaleMin + 0.01F && this.scaleCooldownTicks <= 0) {
         if (totalMs > budget * 0.88F) {
            this.scaleOverStreak++;
            this.scaleUnderStreak = 0;
            if (this.scaleOverStreak >= SCALE_STEP_FRAMES && this.scaleLadderIdx > 0) {
               this.scaleLadderIdx--;
               this.scaleOverStreak = 0;
               this.scaleCooldownTicks = SCALE_COOLDOWN_TICKS;
            }
         } else if (totalMs < budget * 0.62F) {
            this.scaleUnderStreak++;
            this.scaleOverStreak = 0;
            if (this.scaleUnderStreak >= SCALE_STEP_FRAMES * 3 && this.scaleLadderIdx < SCALE_LADDER.length - 1) {
               this.scaleLadderIdx++;
               this.scaleUnderStreak = 0;
               this.scaleCooldownTicks = SCALE_COOLDOWN_TICKS;
            }
         } else {
            this.scaleOverStreak = Math.max(0, this.scaleOverStreak - 1);
            this.scaleUnderStreak = 0;
         }
      }

      if (totalMs > budget) {
         this.overBudgetStreak++;
         this.underBudgetStreak = 0;
         if (this.overBudgetStreak >= OVER_BUDGET_FRAMES && this.downgradeLockTicks <= 0) {
            int maxDrop = Math.max(0, this.configuredPreset().ordinal() - ModConfig.StellarisQualityPreset.BALANCED.ordinal());
            if (this.adaptiveDropLevels < maxDrop) {
               this.adaptiveDropLevels++;
               this.downgradeLockTicks = LOCK_TICKS;
               this.scaleLadderIdx = SCALE_LADDER.length - 1;
            }
            this.overBudgetStreak = 0;
         }
      } else if (totalMs < budget * 0.58F) {
         this.underBudgetStreak++;
         this.overBudgetStreak = 0;
         if (this.underBudgetStreak >= UNDER_BUDGET_FRAMES && this.downgradeLockTicks <= 0 && this.adaptiveDropLevels > 0) {
            this.adaptiveDropLevels--;
            this.underBudgetStreak = 0;
            this.scaleLadderIdx = 1;
         }
      } else {
         this.overBudgetStreak = Math.max(0, this.overBudgetStreak - 1);
         this.underBudgetStreak = 0;
      }
   }
}
