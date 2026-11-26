package QWQ.QingYi.annihilationblade.stellaris.debug;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.render.client.core.FxCompat;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisCloudSuppression;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisGpuTimer;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisManager;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisPipeline;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisProceduralAssets;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisQuality;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisShaders;
import QWQ.QingYi.annihilationblade.stellaris.optics.AudioSilencer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 星穹引擎实时 GPU 性能与管线状态悬浮 HUD（通过 {@code /stellaris debug overlay on|off} 开关，默认关闭零开销）。
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class StellarisDebugOverlay {
   private static boolean enabled;

   private StellarisDebugOverlay() {
   }

   public static void setEnabled(boolean value) {
      enabled = value;
   }

   public static boolean isEnabled() {
      return enabled;
   }

   @SubscribeEvent
   public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
      if (!enabled || event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) {
         return;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || mc.options.renderDebug) {
         return;
      }
      GuiGraphics g = event.getGuiGraphics();
      Font font = mc.font;
      StellarisPipeline pipe = StellarisPipeline.INSTANCE;
      StellarisQuality quality = pipe.quality();
      StellarisQuality.Spec spec = quality.effectiveSpec();
      StellarisGpuTimer timer = pipe.gpuTimer();

      List<String> lines = new ArrayList<>(14);
      lines.add("§b§l[Stellaris Engine Debug]");
      lines.add(String.format(
         Locale.ROOT,
         "§7Preset: §f%s§7 (cfg=%s, drop=%d) | HeavyScale: §b%.2fx",
         spec.preset().name(),
         quality.configuredPreset().name(),
         quality.adaptiveDropLevels(),
         quality.currentHeavyScale()
      ));
      lines.add(String.format(
         Locale.ROOT,
         "§7Active: §f%s§7 | MRT: §f%s§7 | Warmup: §f%s",
         pipe.active() ? "§aON" : "§cOFF",
         StellarisShaders.mrtDiagnostic(),
         pipe.warmup().isCompleted() ? "§aDONE" : (pipe.warmup().warmedCount() + "/" + pipe.warmup().totalCount())
      ));
      lines.add(String.format(
         Locale.ROOT,
         "§7Assets(3D/BN/BB): §f%s§7 | Iris: §f%s§7 | Fx: §b%d§7 | Part: §b%d",
         StellarisProceduralAssets.INSTANCE.ready() ? "§aREADY" : "§eBAKING",
         FxCompat.shaderPackActive() ? "ON" : "OFF",
         StellarisManager.activeEffectCount(),
         StellarisManager.particles().activeCount()
      ));
      lines.add(String.format(
         Locale.ROOT,
         "§7CloudSuppress: §f%dt§7 | Silence: §f%dt§7 | Hold: §f%.2f",
         StellarisCloudSuppression.remainingTicks(),
         AudioSilencer.remainingTicks(),
         pipe.holdFrame().strength()
      ));
      lines.add(String.format(
         Locale.ROOT,
         "§eTotal (%s): §f%.2f ms §7/ budget %.1f ms",
         timer.isGpuBacked() ? "GPU" : "CPU",
         timer.smoothedTotalMs(),
         spec.frameBudgetMs()
      ));

      Map<StellarisGpuTimer.Stage, Float> snap = timer.snapshotSmoothed();
      StringBuilder row1 = new StringBuilder("§8");
      StringBuilder row2 = new StringBuilder("§8");
      int idx = 0;
      for (Map.Entry<StellarisGpuTimer.Stage, Float> entry : snap.entrySet()) {
         String seg = String.format(Locale.ROOT, "%s:%.2f  ", entry.getKey().label, entry.getValue());
         if (idx < 5) {
            row1.append(seg);
         } else {
            row2.append(seg);
         }
         idx++;
      }
      lines.add(row1.toString());
      lines.add(row2.toString());

      int maxW = 0;
      for (String line : lines) {
         maxW = Math.max(maxW, font.width(line));
      }
      int x = 8;
      int y = 8;
      int pad = 5;
      int lineH = 10;
      int boxW = maxW + pad * 2;
      int boxH = lines.size() * lineH + pad * 2;
      g.fill(x, y, x + boxW, y + boxH, 0xB8030816);
      g.renderOutline(x, y, boxW, boxH, 0xCC1DF5FF);

      int ty = y + pad;
      for (String line : lines) {
         g.drawString(font, line, x + pad, ty, 0xFFEFFBFF, false);
         ty += lineH;
      }
   }
}
