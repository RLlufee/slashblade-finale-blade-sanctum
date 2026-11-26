package QWQ.QingYi.annihilationblade.stellaris.optics;

import QWQ.QingYi.annihilationblade.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 12fps 动漫级多风格冲击帧序列器（严格遵守 D3 光敏安全定案）。
 * <ul>
 *   <li>{@code OFF}：彻底关闭；</li>
 *   <li>{@code SAFE}（默认）：无极性反转、无高频撕裂、背景始终保持深空冷暗（严格零高反差明暗闪变），
 *       同时以 6fps 平稳展现 Sobel 漫画线稿与细腻半调网点变化，避免 0.5 秒高潮卡死在第一拍；</li>
 *   <li>{@code FULL}：解锁 12fps 完整极性反转与高能水平撕裂（须同时勾选光敏确认）。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ImpactFrameSequencer {
   public enum Preset {
      STRING_SNAP,
      GAMMA_IMPACT,
      VACUUM_CLIMAX
   }

   public record ResolvedBeat(
      float strength,
      boolean invert,
      float edgeWeight,
      float halftoneWeight,
      float tearOffset,
      float fgR, float fgG, float fgB,
      float bgR, float bgG, float bgB,
      int beatIndex
   ) {
      public static final ResolvedBeat NONE = new ResolvedBeat(
         0.0F, false, 0.0F, 0.0F, 0.0F,
         1.0F, 1.0F, 1.0F,
         0.0F, 0.0F, 0.0F,
         0
      );
   }

   private record BeatDef(boolean invert, float edgeWeight, float halftone, float tear, int fgRgb, int bgRgb) {
   }

   private static final BeatDef[] STRING_BEATS = new BeatDef[]{
      new BeatDef(false, 1.15F, 0.55F, 0.008F, 0xEFFBFF, 0x02040D),
      new BeatDef(true,  1.25F, 0.45F, 0.014F, 0x1DF5FF, 0x050817),
      new BeatDef(false, 0.90F, 0.35F, 0.004F, 0x1DF5FF, 0x02040D)
   };

   private static final BeatDef[] GAMMA_BEATS = new BeatDef[]{
      new BeatDef(false, 1.20F, 0.50F, 0.006F, 0x1DF5FF, 0x040314),
      new BeatDef(true,  1.30F, 0.60F, 0.012F, 0xEFFBFF, 0x1B0C38),
      new BeatDef(false, 1.10F, 0.55F, 0.009F, 0x7C5CFF, 0x02040D),
      new BeatDef(false, 0.85F, 0.30F, 0.003F, 0xEFFBFF, 0x02040D)
   };

   private static final BeatDef[] CLIMAX_BEATS = new BeatDef[]{
      new BeatDef(false, 1.25F, 0.55F, 0.007F, 0x1DF5FF, 0x02040D),
      new BeatDef(true,  1.35F, 0.65F, 0.015F, 0xEFFBFF, 0x170B33),
      new BeatDef(false, 1.30F, 0.50F, 0.018F, 0xEFFBFF, 0x02040D),
      new BeatDef(true,  1.40F, 0.70F, 0.012F, 0x7C5CFF, 0x030714),
      new BeatDef(false, 1.25F, 0.60F, 0.020F, 0x1DF5FF, 0x040212),
      new BeatDef(true,  1.10F, 0.35F, 0.010F, 0xEFFBFF, 0x091326),
      new BeatDef(false, 0.95F, 0.40F, 0.005F, 0x1DF5FF, 0x02040D),
      new BeatDef(false, 0.75F, 0.25F, 0.002F, 0xEFFBFF, 0x02040D)
   };

   // SAFE 档独立光敏安全拍表：背景恒锁深空暗色 #02040D、零极性反转、零水平撕裂、前景色限定窄亮度区间，
   // 以 6fps 平稳推进线稿权重与细腻半调网点变化，既杜绝 >3Hz 明暗闪变又避免短高潮锁死首帧。
   private static final BeatDef[] STRING_SAFE_BEATS = new BeatDef[]{
      new BeatDef(false, 1.10F, 0.35F, 0.0F, 0x5CEEFF, 0x02040D),
      new BeatDef(false, 1.28F, 0.52F, 0.0F, 0x8EF6FF, 0x02040D),
      new BeatDef(false, 0.92F, 0.28F, 0.0F, 0x6AD9FF, 0x02040D)
   };

   private static final BeatDef[] GAMMA_SAFE_BEATS = new BeatDef[]{
      new BeatDef(false, 1.12F, 0.38F, 0.0F, 0x5CEEFF, 0x02040D),
      new BeatDef(false, 1.32F, 0.56F, 0.0F, 0x99F7FF, 0x02040D),
      new BeatDef(false, 1.18F, 0.48F, 0.0F, 0x8F84FF, 0x02040D),
      new BeatDef(false, 0.88F, 0.26F, 0.0F, 0x70E6FF, 0x02040D)
   };

   private static final BeatDef[] CLIMAX_SAFE_BEATS = new BeatDef[]{
      new BeatDef(false, 1.15F, 0.40F, 0.0F, 0x5CEEFF, 0x02040D),
      new BeatDef(false, 1.32F, 0.56F, 0.0F, 0x9CF7FF, 0x02040D),
      new BeatDef(false, 1.24F, 0.48F, 0.0F, 0x7EE9FF, 0x02040D),
      new BeatDef(false, 1.36F, 0.62F, 0.0F, 0x968CFF, 0x02040D),
      new BeatDef(false, 1.22F, 0.52F, 0.0F, 0x6BEBFF, 0x02040D),
      new BeatDef(false, 1.08F, 0.38F, 0.0F, 0x8DF4FF, 0x02040D),
      new BeatDef(false, 0.94F, 0.30F, 0.0F, 0x68DBFF, 0x02040D),
      new BeatDef(false, 0.78F, 0.22F, 0.0F, 0x7CEAFF, 0x02040D)
   };

   private boolean fullWarningSentThisSession;

   public ResolvedBeat resolve(StellarisOpticsState optics) {
      if (optics.impactPreset == null || optics.impactStrength <= 1.0E-3F) {
         return ResolvedBeat.NONE;
      }

      ModConfig.StellarisEngine cfg = ModConfig.CLIENT.stellarisEngine;
      ModConfig.ImpactFrameMode mode = cfg.impactFrames.getValue();
      float userScale = (float)(double)cfg.impactFrameIntensity.getValue();
      float globalFlashScale = (float)(double)ModConfig.CLIENT.clientRenderer.screenFlash.getValue();
      float combinedScale = userScale * globalFlashScale;
      if (mode == ModConfig.ImpactFrameMode.OFF || combinedScale <= 1.0E-3F) {
         return ResolvedBeat.NONE;
      }

      boolean allowFull = false;
      if (mode == ModConfig.ImpactFrameMode.FULL) {
         if (cfg.fullImpactWarningAcknowledged.getValue()) {
            allowFull = true;
         } else {
            this.notifyFullRequiresAck();
         }
      }

      BeatDef[] table;
      if (allowFull) {
         table = switch (optics.impactPreset) {
            case STRING_SNAP -> STRING_BEATS;
            case GAMMA_IMPACT -> GAMMA_BEATS;
            case VACUUM_CLIMAX -> CLIMAX_BEATS;
         };
      } else {
         table = switch (optics.impactPreset) {
            case STRING_SNAP -> STRING_SAFE_BEATS;
            case GAMMA_IMPACT -> GAMMA_SAFE_BEATS;
            case VACUUM_CLIMAX -> CLIMAX_SAFE_BEATS;
         };
      }

      // FULL 档 12fps 完整切拍；SAFE 档恒暗无反相无撕裂，按 6fps 平稳推进线稿与细腻半调网点（严守 D3）
      float beatRate = allowFull ? 12.0F : 6.0F;
      int rawBeat = Math.max(0, (int)Math.floor(optics.impactElapsedSeconds * beatRate));
      int idx = Math.min(table.length - 1, rawBeat);
      BeatDef def = table[idx];

      boolean invert = allowFull && def.invert;
      float strengthCap = allowFull ? 1.0F : 0.62F;
      float strength = Math.min(strengthCap, optics.impactStrength * combinedScale);
      float tear = allowFull ? def.tear : 0.0F;

      return new ResolvedBeat(
         strength,
         invert,
         def.edgeWeight,
         def.halftone,
         tear,
         ((def.fgRgb >> 16) & 0xFF) / 255.0F,
         ((def.fgRgb >> 8) & 0xFF) / 255.0F,
         (def.fgRgb & 0xFF) / 255.0F,
         ((def.bgRgb >> 16) & 0xFF) / 255.0F,
         ((def.bgRgb >> 8) & 0xFF) / 255.0F,
         (def.bgRgb & 0xFF) / 255.0F,
         rawBeat
      );
   }

   private void notifyFullRequiresAck() {
      if (this.fullWarningSentThisSession) {
         return;
      }
      this.fullWarningSentThisSession = true;
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         mc.player.displayClientMessage(
            Component.translatable("message.annihilationblade.stellaris.impact_full_requires_ack").withStyle(ChatFormatting.YELLOW),
            false
         );
      }
   }

   public void resetSession() {
      this.fullWarningSentThisSession = false;
   }
}
