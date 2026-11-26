package QWQ.QingYi.annihilationblade.stellaris.debug;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.render.client.core.FxCompat;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisGpuTimer;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisManager;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisPipeline;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisProceduralAssets;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisQuality;
import QWQ.QingYi.annihilationblade.stellaris.engine.StellarisShaders;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 星穹引擎纯客户端调试与实机验收指令：{@code /stellaris debug ...}
 * <ul>
 *   <li>{@code /stellaris debug status}：输出引擎激活状态、MRT 自检、AT ({@code viewArea}) 探测、纹理资产与各 Pass GPU 毫秒耗时；</li>
 *   <li>{@code /stellaris debug overlay on|off}：开关左上角实时 GPU 性能仪表盘；</li>
 *   <li>{@code /stellaris debug test <optics|bloom|streak|godrays|holdframe|impact_safe|impact_full|particles|silence|clouds>}：在前方召唤 Step 1 光学验收沙盒；</li>
 *   <li>{@code /stellaris debug preset <fallback|balanced|cinematic|ultimate|config>}：实时切换画质档对比；</li>
 *   <li>{@code /stellaris debug clear} / {@code reset}：清空沙盒实例或重置自适应/熔断器状态。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = Annihilationblade.MODID, value = Dist.CLIENT)
public final class StellarisDebugCommand {
   private static final SuggestionProvider<CommandSourceStack> TEST_SUGGESTIONS = (ctx, builder) ->
      SharedSuggestionProvider.suggest(
         new String[]{"optics", "scene", "bloom", "streak", "godrays", "holdframe", "impact_safe", "impact_full", "particles", "silence", "clouds"},
         builder
      );

   private static final SuggestionProvider<CommandSourceStack> PRESET_SUGGESTIONS = (ctx, builder) ->
      SharedSuggestionProvider.suggest(
         new String[]{"fallback", "balanced", "cinematic", "ultimate", "config"},
         builder
      );

   private StellarisDebugCommand() {
   }

   @SubscribeEvent
   public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
      CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
      dispatcher.register(
         Commands.literal("stellaris")
            .then(Commands.literal("debug")
               .then(Commands.literal("status").executes(ctx -> {
                  printStatus();
                  return 1;
               }))
               .then(Commands.literal("overlay")
                  .then(Commands.literal("on").executes(ctx -> {
                     StellarisDebugOverlay.setEnabled(true);
                     sendMsg("§b[Stellaris] §a已开启实时 GPU 耗时悬浮面板。");
                     return 1;
                  }))
                  .then(Commands.literal("off").executes(ctx -> {
                     StellarisDebugOverlay.setEnabled(false);
                     sendMsg("§b[Stellaris] §7已关闭实时 GPU 耗时悬浮面板。");
                     return 1;
                  }))
               )
               .then(Commands.literal("test")
                  .then(Commands.argument("mode", StringArgumentType.word())
                     .suggests(TEST_SUGGESTIONS)
                     .executes(ctx -> spawnTest(StringArgumentType.getString(ctx, "mode")))
                  )
               )
               .then(Commands.literal("preset")
                  .then(Commands.argument("level", StringArgumentType.word())
                     .suggests(PRESET_SUGGESTIONS)
                     .executes(ctx -> setPreset(StringArgumentType.getString(ctx, "level")))
                  )
               )
               .then(Commands.literal("clear").executes(ctx -> {
                  StellarisManager.clear();
                  sendMsg("§b[Stellaris] §7已清空全部星穹活跃特效与粒子池。");
                  return 1;
               }))
               .then(Commands.literal("reset").executes(ctx -> {
                  StellarisPipeline.INSTANCE.resetDiagnostics();
                  sendMsg("§b[Stellaris] §a已重置熔断状态、自适应降档与自动曝光缓冲。");
                  return 1;
               }))
            )
      );
   }

   private static void printStatus() {
      StellarisPipeline pipe = StellarisPipeline.INSTANCE;
      StellarisQuality quality = pipe.quality();
      StellarisQuality.Spec spec = quality.effectiveSpec();
      StellarisGpuTimer timer = pipe.gpuTimer();

      sendMsg("§b§l========== Stellaris Engine Status ==========");
      sendMsg(String.format(
         Locale.ROOT,
         "§7Engine: %s §7| CoreShaders: %s §7| FullOptics: %s §7| Failed: %s §7| HeavyDegraded: %s",
         pipe.active() ? "§aACTIVE" : "§cINACTIVE",
         StellarisShaders.coreReady() ? "§aOK" : "§cNO",
         StellarisShaders.fullOpticsReady() ? "§aOK" : "§ePARTIAL",
         pipe.isFailed() ? "§cYES" : "§aNO",
         pipe.isHeavyDegraded() ? "§eYES" : "§aNO"
      ));
      sendMsg(String.format(
         Locale.ROOT,
         "§7Preset: §f%s §7(config=%s, adaptiveDrop=%d) | HeavyScale: §b%.2fx §7| Budget: §e%.1f ms",
         spec.preset().name(),
         quality.configuredPreset().name(),
         quality.adaptiveDropLevels(),
         quality.currentHeavyScale(),
         spec.frameBudgetMs()
      ));
      sendMsg("§7MRT 3-Attachment: §f" + StellarisShaders.mrtDiagnostic()
         + " §7| Procedural(3D/BN/BB): " + (StellarisProceduralAssets.INSTANCE.ready() ? "§aREADY" : "§ePENDING")
         + " §7| Warmup: " + (pipe.warmup().isCompleted() ? "§aDONE" : (pipe.warmup().warmedCount() + "/" + pipe.warmup().totalCount())));
      sendMsg("§7WorldCapture AT Probe: §f" + StellarisManager.probeViewAreaAccess()
         + " §7| Iris/Oculus: §f" + (FxCompat.shaderPackActive() ? "ON" : "OFF"));

      StringBuilder timing = new StringBuilder(String.format(
         Locale.ROOT,
         "§eTimings (%s) Total=%.2f ms: §7",
         timer.isGpuBacked() ? "GL33 GPU" : "CPU",
         timer.smoothedTotalMs()
      ));
      for (Map.Entry<StellarisGpuTimer.Stage, Float> e : timer.snapshotSmoothed().entrySet()) {
         timing.append(String.format(Locale.ROOT, "%s=%.2f ", e.getKey().label, e.getValue()));
      }
      sendMsg(timing.toString());
   }

   private static int spawnTest(String modeArg) {
      Minecraft mc = Minecraft.getInstance();
      Player player = mc.player;
      if (player == null) {
         return 0;
      }
      StellarisDebugSandboxEffect.Mode mode;
      try {
         mode = StellarisDebugSandboxEffect.Mode.valueOf(modeArg.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
         sendMsg("§c未知测试模式: " + modeArg + "（可用: optics, scene, bloom, streak, godrays, holdframe, impact_safe, impact_full, particles, silence, clouds）");
         return 0;
      }

      if (mode == StellarisDebugSandboxEffect.Mode.IMPACT_FULL) {
         sendMsg("§e[Stellaris] §7当前冲击帧配置档位: §f" + ModConfig.CLIENT.stellarisEngine.impactFrames.getValue()
            + " §7| 光敏确认: §f" + ModConfig.CLIENT.stellarisEngine.fullImpactWarningAcknowledged.getValue()
            + "§7（需在配置切至 FULL 并勾选确认才解锁极性反转，否则自动安全回落 SAFE）");
      }

      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle().normalize();
      Vec3 pos = eye.add(look.scale(10.5));
      StellarisManager.add(new StellarisDebugSandboxEffect(mode, pos));
      sendMsg("§b[Stellaris] §a已于前方 10.5 格生成 Step 1 光学验证沙盒：§f" + mode.name());
      return 1;
   }

   private static int setPreset(String levelArg) {
      String lower = levelArg.toLowerCase(Locale.ROOT);
      if ("config".equals(lower)) {
         StellarisPipeline.INSTANCE.quality().setDebugOverride(null);
         sendMsg("§b[Stellaris] §a已恢复使用配置文件预设：§f" + ModConfig.CLIENT.stellarisEngine.qualityPreset.getValue().name());
         return 1;
      }
      ModConfig.StellarisQualityPreset preset;
      try {
         preset = ModConfig.StellarisQualityPreset.valueOf(levelArg.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
         sendMsg("§c未知画质档位: " + levelArg + "（可用: fallback, balanced, cinematic, ultimate, config）");
         return 0;
      }
      StellarisPipeline.INSTANCE.quality().setDebugOverride(preset);
      sendMsg("§b[Stellaris] §a已临时切换星穹画质档位为：§f" + preset.name());
      return 1;
   }

   private static void sendMsg(String text) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         mc.player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.WHITE), false);
      }
   }
}
