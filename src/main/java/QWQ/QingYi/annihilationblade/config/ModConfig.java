package QWQ.QingYi.annihilationblade.config;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.iafenvoy.jupiter.config.container.AutoInitConfigContainer;
import com.iafenvoy.jupiter.config.entry.BooleanEntry;
import com.iafenvoy.jupiter.config.entry.DoubleEntry;
import com.iafenvoy.jupiter.config.entry.EnumEntry;
import com.iafenvoy.jupiter.config.entry.IntegerEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class ModConfig {
   public static final Common COMMON = new Common();
   public static final Client CLIENT = new Client();

   private ModConfig() {
   }

   public static final class Common extends AutoInitConfigContainer {
      public final AnnihilationBlade annihilationBlade;
      public final BloodPrison bloodPrison;
      public final InfinityStellaris infinityStellaris;
      public final NightfallDragon nightfallDragon;
      public final LoliBlade loliBlade;
      public final SpatialFracture spatialFracture;
      public final Dankong dankong;
      public final WorldRift worldRift;
      public final TerminusEcho terminusEcho;
      public final VoidDominion voidDominion;
      public final CausalityCollapse causalityCollapse;
      public final PhantomJudgement phantomJudgement;
      public final Domain bloodPrisonDomain;
      public final PhantomBurst bloodPrisonPhantomBurst;
      public final EntropyDissolutionConfig entropyDissolution;
      public final CosmicStringCutConfig cosmicStringCut;
      public final GammaThunderburstConfig gammaThunderburst;
      public final CurvatureRuptureConfig curvatureRupture;
      public final VacuumDecayConfig vacuumDecay;

      private Common() {
         super(ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "common"), "config.annihilationblade.common.title", "./config/annihilationblade/common.json");
         this.annihilationBlade = new AnnihilationBlade();
         this.bloodPrison = new BloodPrison();
         this.infinityStellaris = new InfinityStellaris();
         this.nightfallDragon = new NightfallDragon();
         this.loliBlade = new LoliBlade();
         this.spatialFracture = this.annihilationBlade.spatialFracture;
         this.dankong = this.annihilationBlade.dankong;
         this.worldRift = this.annihilationBlade.worldRift;
         this.terminusEcho = this.annihilationBlade.terminusEcho;
         this.voidDominion = this.annihilationBlade.voidDominion;
         this.causalityCollapse = this.annihilationBlade.causalityCollapse;
         this.phantomJudgement = this.annihilationBlade.phantomJudgement;
         this.bloodPrisonDomain = this.bloodPrison.domain;
         this.bloodPrisonPhantomBurst = this.bloodPrison.phantomBurst;
         this.entropyDissolution = this.infinityStellaris.entropyDissolution;
         this.cosmicStringCut = this.infinityStellaris.cosmicStringCut;
         this.gammaThunderburst = this.infinityStellaris.gammaThunderburst;
         this.curvatureRupture = this.infinityStellaris.curvatureRupture;
         this.vacuumDecay = this.infinityStellaris.vacuumDecay;
      }

   }

   public static final class Client extends AutoInitConfigContainer {
      public final ClientTooltips clientTooltips = new ClientTooltips();
      public final ClientRenderer clientRenderer = new ClientRenderer();
      public final StellarisEngine stellarisEngine = new StellarisEngine();

      private Client() {
         super(ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "client"), "config.annihilationblade.client.title", "./config/annihilationblade/client.json");
      }

   }

   /** 星穹渲染引擎画质预设枚举。 */
   public enum StellarisQualityPreset {
      FALLBACK("config.annihilationblade.stellaris_engine.quality.fallback"),
      BALANCED("config.annihilationblade.stellaris_engine.quality.balanced"),
      CINEMATIC("config.annihilationblade.stellaris_engine.quality.cinematic"),
      ULTIMATE("config.annihilationblade.stellaris_engine.quality.ultimate");

      private final String translationKey;

      StellarisQualityPreset(String translationKey) {
         this.translationKey = translationKey;
      }

      public String translationKey() {
         return this.translationKey;
      }
   }

   /** 12fps 冲击帧光敏安全档位枚举（D3 定案：默认 SAFE）。 */
   public enum ImpactFrameMode {
      OFF("config.annihilationblade.stellaris_engine.impact_frames.off"),
      SAFE("config.annihilationblade.stellaris_engine.impact_frames.safe"),
      FULL("config.annihilationblade.stellaris_engine.impact_frames.full");

      private final String translationKey;

      ImpactFrameMode(String translationKey) {
         this.translationKey = translationKey;
      }

      public String translationKey() {
         return this.translationKey;
      }
   }

   /** SA「真空衰变·坍缩」星海跃迁导演过场模式（D2 定案：默认 B 方案 2.4 s）。 */
   public enum SaCinematicMode {
      OFF("config.annihilationblade.stellaris_engine.sa_cinematic.off"),
      COMPACT_2S("config.annihilationblade.stellaris_engine.sa_cinematic.compact_2s");

      private final String translationKey;

      SaCinematicMode(String translationKey) {
         this.translationKey = translationKey;
      }

      public String translationKey() {
         return this.translationKey;
      }
   }

   /**
    * 无尽星空专属「星穹渲染引擎（Stellaris Engine）」客户端配置。
    * 仅控制客户端视觉、画质降级、光敏安全档与音效演出，不影响服务端玩法判定。
    * 镜头震动上限与总闪白系数继承自 {@link ClientRenderer}，光影包启用时自动与 {@link ClientRenderer#withShaderPack} 协同。
    */
   public static final class StellarisEngine extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final EnumEntry<StellarisQualityPreset> qualityPreset = enumKey(
         "stellaris_quality_preset", StellarisQualityPreset.CINEMATIC, StellarisQualityPreset::translationKey
      );
      public final BooleanEntry worldCaptureEnabled = boolValue(
         "stellaris_world_capture_enabled", true, "config.annihilationblade.stellaris_world_capture_enabled.tooltip"
      );
      public final BooleanEntry anamorphicStreaks = boolValue(
         "stellaris_anamorphic_streaks", true, "config.annihilationblade.stellaris_anamorphic_streaks.tooltip"
      );
      public final EnumEntry<ImpactFrameMode> impactFrames = enumKey(
         "stellaris_impact_frames", ImpactFrameMode.SAFE, ImpactFrameMode::translationKey
      );
      public final DoubleEntry impactFrameIntensity = doubleKey("stellaris_impact_frame_intensity", 1.0, 0.0, 1.0);
      public final BooleanEntry fullImpactWarningAcknowledged = boolValue(
         "stellaris_full_impact_warning_acknowledged", false, "config.annihilationblade.stellaris_full_impact_warning_acknowledged.tooltip"
      );
      public final BooleanEntry flashBlindnessEnabled = boolValue(
         "stellaris_flash_blindness_enabled", true, "config.annihilationblade.stellaris_flash_blindness_enabled.tooltip"
      );
      public final BooleanEntry audioSilencingEnabled = boolValue(
         "stellaris_audio_silencing_enabled", true, "config.annihilationblade.stellaris_audio_silencing_enabled.tooltip"
      );
      public final EnumEntry<SaCinematicMode> saCinematicMode = enumKey(
         "stellaris_sa_cinematic_mode", SaCinematicMode.COMPACT_2S, SaCinematicMode::translationKey
      );
      public final BooleanEntry bladeCosmicParallax = boolValue(
         "stellaris_blade_cosmic_parallax", true, "config.annihilationblade.stellaris_blade_cosmic_parallax.tooltip"
      );
      public final BooleanEntry auraEnabled = boolValue(
         "stellaris_aura_enabled", true, "config.annihilationblade.stellaris_aura_enabled.tooltip"
      );
      public final DoubleEntry auraAlpha = doubleKey("stellaris_aura_alpha", 1.0, 0.0, 1.0);
      public final BooleanEntry gpuAdaptive = boolValue(
         "stellaris_gpu_adaptive", true, "config.annihilationblade.stellaris_gpu_adaptive.tooltip"
      );
      public final BooleanEntry disableHeavyPassesWithShaderPack = boolValue(
         "stellaris_disable_heavy_passes_with_shader_pack", false, "config.annihilationblade.stellaris_disable_heavy_passes_with_shader_pack.tooltip"
      );

      private StellarisEngine() {
         super("stellaris_engine", "config.annihilationblade.stellaris_engine.title");
      }
   }

   /**
    * 专属特效渲染器（客户端）配置。只包含纯视觉、低风险参数。
    */
   public static final class ClientRenderer extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      /** 渲染器总开关；关闭或渲染器初始化失败时回退为客户端简化粒子。 */
      public final BooleanEntry enabled = boolValue("fx_renderer_enabled", true, "config.annihilationblade.fx_renderer_enabled.tooltip");
      /** B 档全屏体积/空间扭曲通道（光线步进）。 */
      public final BooleanEntry volumetric = boolValue("fx_volumetric_enabled", true, "config.annihilationblade.fx_volumetric_enabled.tooltip");
      public final IntegerEntry volumetricQuality = intKey("fx_volumetric_quality", 2, 1, 3);
      public final BooleanEntry bloom = boolValue("fx_bloom_enabled", true, "config.annihilationblade.fx_bloom_enabled.tooltip");
      public final DoubleEntry bloomStrength = doubleKey("fx_bloom_strength", 1.0, 0.0, 2.5);
      public final DoubleEntry screenShake = doubleKey("fx_screen_shake", 1.0, 0.0, 2.0);
      /** 本模组所有镜头震动（含魔龙夜陨原有震屏）合并后每个轴的最大偏转角度。 */
      public final DoubleEntry screenShakeMax = doubleKey("fx_screen_shake_max", 6.0, 0.0, 25.0);
      /** 闪白强度，光敏玩家可调低至 0。 */
      public final DoubleEntry screenFlash = doubleKey("fx_screen_flash", 1.0, 0.0, 1.0);
      public final DoubleEntry chromaticAberration = doubleKey("fx_chromatic_aberration", 1.0, 0.0, 2.0);
      /** 检测到 Iris/Oculus 光影包时是否仍启用渲染器（延迟到光影合成之后绘制）。 */
      public final BooleanEntry withShaderPack = boolValue("fx_with_shader_pack", true, "config.annihilationblade.fx_with_shader_pack.tooltip");
      /** 持刀永昼视界开关；开启后在星穹夺夜/压暗演出与光影包下仍会自动平滑退让以守护深空对比度。 */
      public final BooleanEntry bladeFullbrightVision = boolValue(
         "blade_fullbright_vision", true, "config.annihilationblade.blade_fullbright_vision.tooltip"
      );

      private ClientRenderer() {
         super("client_renderer", "config.annihilationblade.client_renderer.title");
      }
   }

   public static final class ClientTooltips extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enableAnnihilationBladeRenderer = boolValue("enable_annihilation_blade_renderer", true, "config.annihilationblade.client_tooltips.enable_annihilation_blade_renderer.tooltip");
      public final BooleanEntry enableInfinityStellarisRenderer = boolValue("enable_infinity_stellaris_renderer", true, "config.annihilationblade.client_tooltips.enable_infinity_stellaris_renderer.tooltip");

      private ClientTooltips() {
         super("client_tooltips", "config.annihilationblade.client_tooltips.title");
      }
   }

   public static final class AnnihilationBlade {
      public final SpatialFracture spatialFracture;
      public final Dankong dankong;
      public final WorldRift worldRift;
      public final TerminusEcho terminusEcho;
      public final VoidDominion voidDominion;
      public final CausalityCollapse causalityCollapse;
      public final PhantomJudgement phantomJudgement;

      private AnnihilationBlade() {
         this.spatialFracture = new SpatialFracture();
         this.dankong = new Dankong();
         this.worldRift = new WorldRift();
         this.terminusEcho = new TerminusEcho();
         this.voidDominion = new VoidDominion();
         this.causalityCollapse = new CausalityCollapse();
         this.phantomJudgement = new PhantomJudgement();
      }
   }

   public static final class LoliBlade extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry attackEnabled;
      public final BooleanEntry defenseEnabled;
      public final BooleanEntry reflectDamage;
      public final BooleanEntry restoreHealth;
      public final BooleanEntry grantFlight;
      public final BooleanEntry voidProtection;
      public final BooleanEntry clearInventory;
      public final BooleanEntry kickPlayer;
      public final BooleanEntry forceDeath;
      public final BooleanEntry removeEntity;
      public final DoubleEntry attackRange;
      public final DoubleEntry slashArtRange;
      public final IntegerEntry maxTargets;
      public final IntegerEntry facingCooldownTicks;
      public final IntegerEntry areaCooldownTicks;
      /**以不可拦截的物理抹除替换原版伤害链兜底（五路血量归零 + 非玩家硬移除）。 */
      public final BooleanEntry ultimateObliterate;
      /**方法级不可杀：通过 Mixin 在字节码层拦截 hurt/die/actuallyHurt/kill/tickDeath。 */
      public final BooleanEntry ultimateInvincible;
      /**服务端每 tick 校验，被任何手段移除后以同 UUID 重建持有者。 */
      public final BooleanEntry ultimateTheseus;

      private LoliBlade() {
         super("loli_blade", "config.annihilationblade.loli_blade.title");
         this.attackEnabled = boolValue("attack_enabled", true, "config.annihilationblade.attack_enabled.tooltip");
         this.defenseEnabled = boolValue("defense_enabled", true, "config.annihilationblade.defense_enabled.tooltip");
         this.reflectDamage = boolValue("reflect_damage", true, "config.annihilationblade.reflect_damage.tooltip");
         this.restoreHealth = boolValue("restore_health", true, "config.annihilationblade.restore_health.tooltip");
         this.grantFlight = boolValue("grant_flight", true, "config.annihilationblade.grant_flight.tooltip");
         this.voidProtection = boolValue("void_protection", true, "config.annihilationblade.void_protection.tooltip");
         this.clearInventory = boolValue("clear_inventory", false, "config.annihilationblade.clear_inventory.tooltip");
         this.kickPlayer = boolValue("kick_player", false, "config.annihilationblade.kick_player.tooltip");
         this.forceDeath = boolValue("force_death", true, "config.annihilationblade.force_death.tooltip");
         this.removeEntity = boolValue("remove_entity", false, "config.annihilationblade.remove_entity.tooltip");
         this.attackRange = doubleValue("attack_range", 48.0, 4.0, 128.0, "萝莉刀 SE 扇形处决距离，默认 48 格。", "Loli Blade SE cone execution range. Default: 48 blocks.");
         this.slashArtRange = doubleValue("slash_art_range", 128.0, 16.0, 256.0, "萝莉刀 SA 范围处决半径，默认 128 格。", "Loli Blade SA area execution radius. Default: 128 blocks.");
         this.maxTargets = intValue("max_targets", 128, 1, 512, "萝莉刀每次范围处决最多目标数，默认 128。", "Maximum targets per Loli Blade execution. Default: 128.");
         this.facingCooldownTicks = intValue("facing_cooldown_ticks", 5, 0, 200, "萝莉刀 SE 扇形处决冷却，默认 5 tick。", "Loli Blade SE cone execution cooldown. Default: 5 ticks.");
         this.areaCooldownTicks = intValue("area_cooldown_ticks", 10, 0, 200, "萝莉刀 SA 范围处决冷却，默认 10 tick。", "Loli Blade SA area execution cooldown. Default: 10 ticks.");
         this.ultimateObliterate = boolValue("ultimate_obliterate", false, "config.annihilationblade.ultimate_obliterate.tooltip");
         this.ultimateInvincible = boolValue("ultimate_invincible", false, "config.annihilationblade.ultimate_invincible.tooltip");
         this.ultimateTheseus = boolValue("ultimate_theseus", false, "config.annihilationblade.ultimate_theseus.tooltip");
      }
   }

   public static final class SpatialFracture extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final DoubleEntry maxDistance;
      public final DoubleEntry fractureRadius;
      /** 历史兼容占位项，当前版本已由解析锁定替代、不再读取。 */
      @Deprecated
      public final DoubleEntry rayStep;
      public final DoubleEntry raySampleRadius;
      public final DoubleEntry entityLockRadius;
      public final DoubleEntry backupRadius;
      public final IntegerEntry maxTargets;
      public final IntegerEntry maxVisualizedTargets;
      public final IntegerEntry fractureSlashes;
      /** 历史兼容占位项，当前版本已由专属渲染器替代、不再读取。 */
      @Deprecated
      public final IntegerEntry centerSlashes;
      public final DoubleEntry visualScale;

      private SpatialFracture() {
         super("annihilation_blade.spatial_fracture", "config.annihilationblade.annihilation_blade.spatial_fracture.title");
         this.maxDistance = doubleValue("max_distance", 160.0, 32.0, 256.0, "最大施法距离，建议 64-192。", "Maximum cast distance. Suggested: 64-192.");
         this.fractureRadius = doubleValue("fracture_radius", 20.0, 4.0, 64.0, "空间破碎主判定半径，建议 12-32。", "Main Spatial Fracture target radius. Suggested: 12-32.");
         this.rayStep = doubleValue("ray_step", 4.0, 1.0, 12.0, "【历史兼容保留，当前版本未使用】原沿视线补充扫描步长。", "[Legacy compatibility placeholder; unused in current version] Former ray scan step.");
         this.raySampleRadius = doubleValue("ray_sample_radius", 5.0, 1.0, 16.0, "沿视线每个采样点的扫描半径，建议 3-8。", "Backup ray sample radius. Suggested: 3-8.");
         this.entityLockRadius = doubleValue("entity_lock_radius", 3.0, 0.5, 10.0, "准星锁定实体的宽容半径，建议 2-5。", "Aim lock tolerance around entities. Suggested: 2-5.");
         this.backupRadius = doubleValue("backup_radius", 48.0, 8.0, 96.0, "没有命中目标时的备用搜索半径，建议 24-64。", "Fallback search radius when no target is found. Suggested: 24-64.");
         this.maxTargets = intValue("max_targets", 128, 1, 256, "最大影响目标数，建议 32-160。", "Maximum affected targets. Suggested: 32-160.");
         this.maxVisualizedTargets = intValue("max_visualized_targets", 32, 0, 128, "最多播放单体斩击视觉的目标数，建议 16-48。", "Targets with individual slash visuals. Suggested: 16-48.");
         this.fractureSlashes = intValue("fracture_slashes", 24, 0, 96, "裂隙风暴斩击线数量（设为 0 可关闭），建议 12-36。", "Fracture storm visual slash count (0 disables). Suggested: 12-36.");
         this.centerSlashes = intValue("center_slashes", 12, 0, 48, "【历史兼容保留，当前版本未使用】原中心 SlashBlade 斩击数量。", "[Legacy compatibility placeholder; unused in current version] Former center SlashBlade slash count.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class Dankong extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry range;
      public final IntegerEntry maxTargets;
      public final IntegerEntry stepInterval;
      public final IntegerEntry cooldownTicks;
      public final DoubleEntry visualScale;

      private Dankong() {
         super("annihilation_blade.dankong", "config.annihilationblade.annihilation_blade.dankong.title");
         this.enabled = seEnabled("dankong");
         this.range = doubleValue("range", 72.0, 8.0, 256.0, "断空搜索范围，建议 32-96。", "Severed Space target search range. Suggested: 32-96.");
         this.maxTargets = intValue("max_targets", 48, 1, 128, "连续闪现最多目标数，建议 16-64。", "Maximum blink targets. Suggested: 16-64.");
         this.stepInterval = intValue("step_interval_ticks", 3, 1, 12, "连续闪现间隔 tick，20 tick=1秒，建议 3-6。", "Ticks between blink steps; 20 ticks = 1 second. Suggested: 3-6.");
         this.cooldownTicks = intValue("cooldown_ticks", 12, 0, 100, "触发冷却 tick，建议 8-24。", "Trigger cooldown in ticks. Suggested: 8-24.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class WorldRift extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final BooleanEntry dankongLinkage;
      public final DoubleEntry radius;
      public final IntegerEntry maxTargets;
      public final IntegerEntry chainCount;
      public final DoubleEntry chainRange;
      public final IntegerEntry cooldownTicks;
      public final DoubleEntry visualScale;

      private WorldRift() {
         super("annihilation_blade.world_rift", "config.annihilationblade.annihilation_blade.world_rift.title");
         this.enabled = seEnabled("world_rift");
         this.dankongLinkage = boolValue("dankong_linkage", true, "config.annihilationblade.dankong_linkage.tooltip");
         this.radius = doubleValue("radius", 12.0, 2.0, 32.0, "裂界单链索敌与空间扭曲半径，建议 6-14。", "World Rift chain search and warp radius. Suggested: 6-14.");
         this.maxTargets = intValue("max_targets", 24, 1, 96, "断空联动尸骸神威扭杀最大目标数，建议 12-36。", "Maximum targets for Dankong-linked Kamui corpse twist. Suggested: 12-36.");
         this.chainCount = intValue("chain_count", 3, 1, 10, "断空关闭时裂界单链最多跳跃次数，建议 3-5。", "Maximum standalone single-chain hop count while Dankong is disabled. Suggested: 3-5.");
         this.chainRange = doubleValue("chain_range", 256.0, 1.0, 1024.0, "裂界单链跳跃距最初攻击者的距离上限，建议 128-256。", "World Rift chain range cap from the original attacker. Suggested: 128-256.");
         this.cooldownTicks = intValue("cooldown_ticks", 20, 0, 200, "断空关闭时独立裂界触发冷却，默认 20 tick。", "Standalone World Rift trigger cooldown when Dankong is disabled. Default: 20 ticks.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class TerminusEcho extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final BooleanEntry requireHeld;
      public final BooleanEntry interceptProjectiles;
      public final BooleanEntry deflectEnvironmentFx;
      public final DoubleEntry counterRange;
      public final IntegerEntry cooldownTicks;
      public final IntegerEntry maxActiveCounters;
      public final DoubleEntry visualScale;

      private TerminusEcho() {
         super("annihilation_blade.terminus_echo", "config.annihilationblade.annihilation_blade.terminus_echo.title");
         this.enabled = seEnabled("terminus_echo");
         this.requireHeld = boolValue("require_held", true, "config.annihilationblade.require_held.tooltip");
         this.interceptProjectiles = boolValue("intercept_projectiles", true, "config.annihilationblade.intercept_projectiles.tooltip");
         this.deflectEnvironmentFx = boolValue("deflect_environment_fx", false, "config.annihilationblade.deflect_environment_fx.tooltip");
         this.counterRange = doubleValue("counter_range", 64.0, 8.0, 160.0, "归墟回响因果逆溯反噬的最大距离，建议 32-96。", "Maximum Terminus Echo causal reprisal range. Suggested: 32-96.");
         this.cooldownTicks = intValue("cooldown_ticks", 10, 0, 200, "两次受击因果反噬之间的触发冷却（tick，20 tick=1秒），建议 8-30。", "Trigger cooldown between causal reprisals in ticks (20 ticks = 1s). Suggested: 8-30.");
         this.maxActiveCounters = intValue("max_active_counters", 3, 1, 8, "同一玩家最多同时推进的因果反噬数量，建议 2-4。", "Maximum simultaneous causal reprisals per player. Suggested: 2-4.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class VoidDominion extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry range;
      public final IntegerEntry maxTargets;
      public final IntegerEntry cooldownTicks;
      public final IntegerEntry chordWindowTicks;
      public final DoubleEntry visualScale;

      private VoidDominion() {
         super("annihilation_blade.void_dominion", "config.annihilationblade.annihilation_blade.void_dominion.title");
         this.enabled = seEnabled("void_dominion");
         this.range = doubleValue("range", 26.0, 4.0, 64.0, "虚无权域半径，建议 16-36。", "Void Dominion radius. Suggested: 16-36.");
         this.maxTargets = intValue("max_targets", 64, 1, 160, "虚无权域最多影响目标数，建议 32-96。", "Maximum Void Dominion targets. Suggested: 32-96.");
         this.cooldownTicks = intValue("cooldown_ticks", 70, 0, 200, "触发冷却 tick（权域展开期间自动防重叠，提前中断时仍按此冷却生效），建议 50-120。", "Trigger cooldown in ticks (overlap is blocked while active; still enforced if interrupted early). Suggested: 50-120.");
         this.chordWindowTicks = intValue(
            "chord_window_ticks", 2, 0, 6,
            "左键+右键同时按下判定缓冲窗口（tick，20 tick = 1 秒），用于防止略微错帧按下时误先触发断空或幻影审判。建议 2-3。",
            "Left+Right simultaneous press buffer window in ticks (20 ticks = 1s) to prevent accidental Dankong or Phantom Judgement misfire. Suggested: 2-3."
         );
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class CausalityCollapse extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final BooleanEntry worldRiftLinkage;
      public final BooleanEntry linkageDevour;
      public final BooleanEntry devourExecution;
      public final BooleanEntry lockOnLinkage;
      public final DoubleEntry lockOnRange;
      public final DoubleEntry aimLockRange;
      public final DoubleEntry aimLockAngle;
      public final DoubleEntry suctionRadius;
      public final IntegerEntry maxSuctionTargets;
      public final IntegerEntry scarDurationTicks;
      public final IntegerEntry cooldownTicks;
      public final DoubleEntry visualScale;

      private CausalityCollapse() {
         super("annihilation_blade.causality_collapse", "config.annihilationblade.annihilation_blade.causality_collapse.title");
         this.enabled = seEnabled("causality_collapse");
         this.worldRiftLinkage = boolValue("world_rift_linkage", true, "config.annihilationblade.world_rift_linkage.tooltip");
         this.linkageDevour = boolValue("linkage_devour", true, "config.annihilationblade.linkage_devour.tooltip");
         this.devourExecution = boolValue("devour_execution", true, "config.annihilationblade.devour_execution.tooltip");
         this.lockOnLinkage = boolValue("lock_on_linkage", true, "config.annihilationblade.lock_on_linkage.tooltip");
         this.lockOnRange = doubleValue(
            "lock_on_range", 64.0, 8.0, 128.0,
            "联动原版拔刀剑 Shift 锁敌时的最大远程狙杀距离（格，完整覆盖原版 40 格锁定并留有移动余量），建议 48-80。",
            "Maximum remote snipe range in blocks when linked with vanilla SlashBlade Shift lock-on (covers vanilla 40-block lock-on). Suggested: 48-80."
         );
         this.aimLockRange = doubleValue(
            "aim_lock_range",32.0, 0.0, 128.0,
            "免 Shift 准星智能索敌最大距离（格；默认 24 格覆盖中近距可见敌人且自动避开实心墙体，设为 0 则仅 Shift 锁敌才索敌），建议 16-28。",
            "Free crosshair smart lock-on range in blocks without holding Shift (default 24 blocks with 3-point visual LOS; 0 for Shift lock-on only). Suggested: 16-28."
         );
         this.aimLockAngle = doubleValue(
            "aim_lock_angle", 13.0, 3.0, 35.0,
            "免 Shift 准星智能索敌允许的最大偏角（度；准星偏离目标超过此角度即自由砍空气/地形），建议 10-16。",
            "Maximum crosshair deviation angle in degrees for free smart lock-on (aiming further away falls back to air/surface slash). Suggested: 10-16."
         );
         this.suctionRadius = doubleValue(
            "suction_radius", 6.0, 0.0, 16.0,
            "单道时空裂痕持续引力漩涡的吸扯半径（格；设为 0 则关闭引力吸扯与绞杀，纯观赏），建议 4.5-8。",
            "Continuous gravitational vortex pull radius of each spacetime rift scar in blocks (0 for pure visual). Suggested: 4.5-8."
         );
         this.maxSuctionTargets = intValue(
            "max_suction_targets", 24, 1, 96,
            "单道裂痕同时牵引与绞杀的最大敌对生物数量，建议 12-32。",
            "Maximum hostile targets pulled and crushed simultaneously by a single rift scar. Suggested: 12-32."
         );
         this.scarDurationTicks = intValue(
            "scar_duration_ticks", 64, 20, 200,
            "单道因果时空裂痕存续总时长（tick，20 tick = 1 秒，默认 64 tick ≈ 3.2 秒）。",
            "Lifetime in ticks of each causal spacetime rift scar (20 ticks = 1s; default 64 ticks ≈ 3.2s)."
         );
         this.cooldownTicks = intValue(
            "cooldown_ticks", 16, 0, 120,
            "独立挥刀触发单道因果裂痕的冷却 tick，建议 12-30（神威联动不受此冷却限制）。",
            "Standalone slash causal rift cooldown in ticks. Suggested: 12-30 (World Rift linkage ignores this cooldown)."
         );
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class PhantomJudgement extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry range;
      public final IntegerEntry swordCount;
      public final IntegerEntry subShardsPerMaster;
      public final IntegerEntry trackerShardsPerSub;
      public final IntegerEntry maxTotalShards;
      public final IntegerEntry slashStringsPerTarget;
      public final IntegerEntry maxTargets;
      public final IntegerEntry cooldownTicks;
      public final DoubleEntry visualScale;

      private PhantomJudgement() {
         super("annihilation_blade.phantom_judgement", "config.annihilationblade.annihilation_blade.phantom_judgement.title");
         this.enabled = seEnabled("phantom_judgement");
         this.range = doubleValue("range", 40.0, 8.0, 96.0, "幻影审判·千刃星流索敌范围，建议 24-56。", "Phantom Judgement search range. Suggested: 24-56.");
         this.swordCount = intValue("search_sword_count", 8, 4, 16, "千刃解离初始母刃数量，建议 6-12。", "Initial master shard blade count. Suggested: 6-12.");
         this.subShardsPerMaster = intValue("sub_shards_per_master", 4, 0, 6, "每枚母刃分裂出的寒星子刃数量，建议 3-4。", "Sub-shards split from each master blade. Suggested: 3-4.");
         this.trackerShardsPerSub = intValue("tracker_shards_per_sub", 2, 0, 4, "每枚子刃分裂出的紫罗兰追踪碎刃数量，建议 1-3。", "Tracker shards split from each sub-shard. Suggested: 1-3.");
         this.maxTotalShards = intValue("max_total_shards", 128, 24, 200, "单次千刃星流同屏碎刃总数硬上限，建议 96-144。", "Hard cap of simultaneous shards per cast. Suggested: 96-144.");
         this.slashStringsPerTarget = intValue("slash_strings_per_target", 10, 4, 16, "单目标终幕爆裂斩绝线基础数量，建议 8-12。", "Base slash-string count per target on detonation. Suggested: 8-12.");
         this.maxTargets = intValue("max_targets", 24, 1, 96, "幻影审判最多锁定目标数，建议 12-36。", "Maximum Phantom Judgement targets. Suggested: 12-36.");
         this.cooldownTicks = intValue("cooldown_ticks", 20, 0, 240, "左键发射冷却 tick（20 tick=1秒），建议 20-60。", "Left-click launch cooldown in ticks (20 ticks = 1s). Suggested: 20-60.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class BloodPrison {
      public final Domain domain;
      public final PhantomBurst phantomBurst;

      private BloodPrison() {
         this.domain = new Domain();
         this.phantomBurst = new PhantomBurst();
      }
   }

   public static final class Domain extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final IntegerEntry durationTicks;
      public final DoubleEntry radius;
      /** 历史兼容占位项，当前版本已由专属渲染器替代、不再读取。 */
      @Deprecated
      public final IntegerEntry borderIntervalTicks;
      /** 历史兼容占位项，当前版本已由专属渲染器替代、不再读取。 */
      @Deprecated
      public final IntegerEntry playerAuraIntervalTicks;
      public final IntegerEntry pulseIntervalTicks;
      public final DoubleEntry visualScale;

      private Domain() {
         super("blood_prison.domain", "config.annihilationblade.blood_prison.domain.title");
         this.durationTicks = intValue("duration_ticks", 400, 40, 1200, "血狱领域持续 tick，20 tick=1秒，建议 200-600。", "Blood Prison domain duration in ticks. Suggested: 200-600.");
         this.radius = doubleValue("radius", 10.0, 3.0, 32.0, "血狱领域半径，建议 8-14。", "Blood Prison domain radius. Suggested: 8-14.");
         this.borderIntervalTicks = intValue("border_interval_ticks", 10, 2, 40, "【历史兼容保留，当前版本未使用】原领域边界粒子刷新间隔。", "[Legacy compatibility placeholder; unused in current version] Former domain border particle interval.");
         this.playerAuraIntervalTicks = intValue("player_aura_interval_ticks", 4, 1, 40, "【历史兼容保留，当前版本未使用】原领域内玩家血气粒子间隔。", "[Legacy compatibility placeholder; unused in current version] Former player aura particle interval.");
         this.pulseIntervalTicks = intValue("pulse_interval_ticks", 20, 4, 80, "领域脉冲视觉间隔，建议 16-30。", "Domain pulse visual interval. Suggested: 16-30.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class PhantomBurst extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final IntegerEntry swordCount;
      public final IntegerEntry swordDelayTicks;
      public final DoubleEntry burstRadiusScale;
      public final DoubleEntry visualScale;

      private PhantomBurst() {
         super("blood_prison.phantom_burst", "config.annihilationblade.blood_prison.phantom_burst.title");
         this.swordCount = intValue("sword_count", 10, 1, 32, "血狱幻影爆发剑数量，建议 6-14。", "Blood Prison phantom burst sword count. Suggested: 6-14.");
         this.swordDelayTicks = intValue("sword_delay_ticks", 18, 0, 80, "幻影剑命中延迟 tick，建议 12-24。", "Phantom sword impact delay in ticks. Suggested: 12-24.");
         this.burstRadiusScale = doubleValue("burst_radius_scale", 2.2, 0.5, 5.0, "爆发视觉半径与目标宽度倍率，建议 1.5-2.8。", "Burst visual radius scale against target width. Suggested: 1.5-2.8.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class InfinityStellaris {
      public final EntropyDissolutionConfig entropyDissolution;
      public final CosmicStringCutConfig cosmicStringCut;
      public final GammaThunderburstConfig gammaThunderburst;
      public final CurvatureRuptureConfig curvatureRupture;
      public final VacuumDecayConfig vacuumDecay;

      private InfinityStellaris() {
         this.entropyDissolution = new EntropyDissolutionConfig();
         this.cosmicStringCut = new CosmicStringCutConfig();
         this.gammaThunderburst = new GammaThunderburstConfig();
         this.curvatureRupture = new CurvatureRuptureConfig();
         this.vacuumDecay = new VacuumDecayConfig();
      }
   }

   public static final class EntropyDissolutionConfig extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry entropyPercent;
      public final IntegerEntry entropyMarks;
      public final IntegerEntry entropyBlacklistTicks;
      public final DoubleEntry visualScale;

      private EntropyDissolutionConfig() {
         super("infinity_stellaris.entropy_dissolution", "config.annihilationblade.infinity_stellaris.entropy_dissolution.title");
         this.enabled = infinitySeEnabled("entropy_dissolution");
         this.entropyPercent = doubleValue("entropy_percent", 0.10, 0.0, 1.0, "熵增蚀解每次命中额外削减的目标最大生命比例。", "Max-health ratio stripped per hit by Entropy Dissolution.");
         this.entropyMarks = intValue("entropy_marks", 10, 1, 100, "触发热寂归零处决所需累计熵增层数。", "Entropy marks required to trigger Heat Death execution.");
         this.entropyBlacklistTicks = intValue("entropy_blacklist_ticks", 40, 0, 400, "普通非玩家目标处决后的防重复触发黑名单时长（tick）。", "Short blacklist duration in ticks after non-player execution.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class CosmicStringCutConfig extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry range;
      public final DoubleEntry shiftRange;
      public final IntegerEntry maxTargets;
      public final IntegerEntry bindTicks;
      public final IntegerEntry cooldownTicks;
      public final IntegerEntry entropyMarksApplied;
      public final DoubleEntry visualScale;

      private CosmicStringCutConfig() {
         super("infinity_stellaris.cosmic_string_cut", "config.annihilationblade.infinity_stellaris.cosmic_string_cut.title");
         this.enabled = infinitySeEnabled("cosmic_string_cut");
         this.range = doubleValue("range", 32.0, 6.0, 96.0, "宇宙弦切常态中远程弦网索敌半径（格）。", "Default Cosmic String Cut web range in blocks.");
         this.shiftRange = doubleValue("shift_range", 64.0, 12.0, 128.0, "潜行（Shift）挥刀时的远距弦网索敌半径（格）。", "Extended Cosmic String Cut range in blocks while sneaking.");
         this.maxTargets = intValue("max_targets", 10, 1, 32, "单次宇宙弦网最多锁定的目标数量。", "Maximum targets bound by a single Cosmic String Cut.");
         this.bindTicks = intValue("bind_ticks", 9, 2, 30, "弦网束缚至拨弦崩断结算的延迟 tick（默认 9 tick）。", "Ticks from string binding to pluck detonation (default 9).");
         this.cooldownTicks = intValue("cooldown_ticks", 14, 0, 120, "宇宙弦切触发冷却（tick）。", "Cosmic String Cut trigger cooldown in ticks.");
         this.entropyMarksApplied = intValue("entropy_marks_applied", 1, 0, 10, "每次拨弦结算为命中目标叠加的熵增印记层数。", "Entropy marks applied to each hit target on pluck.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class GammaThunderburstConfig extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final IntegerEntry warningTicks;
      public final IntegerEntry beamTicks;
      public final DoubleEntry radius;
      public final IntegerEntry cooldownTicks;
      public final IntegerEntry entropyMarksApplied;
      public final DoubleEntry visualScale;

      private GammaThunderburstConfig() {
         super("infinity_stellaris.gamma_thunderburst", "config.annihilationblade.infinity_stellaris.gamma_thunderburst.title");
         this.enabled = infinitySeEnabled("gamma_thunderburst");
         this.warningTicks = intValue("warning_ticks", 12, 4, 40, "天基轨道炮锁定预警时长（tick，默认 12 tick）。", "Orbital cannon targeting warning duration in ticks.");
         this.beamTicks = intValue("beam_ticks", 18, 6, 60, "伽马光柱贯地持续打击时长（tick，默认 18 tick）。", "Gamma beam sustained strike duration in ticks.");
         this.radius = doubleValue("radius", 5.5, 2.0, 16.0, "伽马光柱地表打击半径（格，默认 5.5）。", "Gamma beam impact radius in blocks (default 5.5).");
         this.cooldownTicks = intValue("cooldown_ticks", 30, 0, 200, "伽马霆爆触发冷却（tick）。", "Gamma Thunderburst trigger cooldown in ticks.");
         this.entropyMarksApplied = intValue("entropy_marks_applied", 2, 0, 10, "轨道炮命中时为范围内目标叠加的熵增印记层数。", "Entropy marks applied by Gamma Thunderburst.");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class CurvatureRuptureConfig extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry curvatureRadius;
      public final IntegerEntry curvatureTickInterval;
      public final IntegerEntry curvatureMaxTargets;
      public final IntegerEntry curvatureBurstMarks;
      public final IntegerEntry entropyMarksApplied;
      public final BooleanEntry legacyKeepNoAiOnRelease;
      public final DoubleEntry visualScale;

      private CurvatureRuptureConfig() {
         super("infinity_stellaris.curvature_rupture", "config.annihilationblade.infinity_stellaris.curvature_rupture.title");
         this.enabled = infinitySeEnabled("curvature_rupture");
         this.curvatureRadius = doubleValue("curvature_radius", 22.0, 4.0, 64.0, "曲率撕裂领域压制与重力漏斗作用半径（格，默认 22）。", "Curvature Rupture domain radius in blocks (default 22).");
         this.curvatureTickInterval = intValue("curvature_tick_interval", 20, 4, 100, "曲率应力印记叠加间隔（tick，默认 20 tick = 1 秒）。", "Curvature strain mark interval in ticks (default 20).");
         this.curvatureMaxTargets = intValue("curvature_max_targets", 32, 1, 128, "曲率领域同时压制的最大目标数。", "Maximum targets simultaneously suppressed by Curvature Rupture.");
         this.curvatureBurstMarks = intValue("curvature_burst_marks", 4, 1, 20, "触发度规撕裂坍缩所需的应力层数（默认 4 层）。", "Strain marks required to trigger metric rupture collapse.");
         this.entropyMarksApplied = intValue("entropy_marks_applied", 2, 0, 10, "度规坍缩爆发时叠加的熵增印记层数。", "Entropy marks applied on metric rupture collapse.");
         this.legacyKeepNoAiOnRelease = boolValue("legacy_keep_no_ai_on_release", false, "config.annihilationblade.infinity_stellaris.legacy_keep_no_ai_on_release.tooltip");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class VacuumDecayConfig extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry castRange;
      public final DoubleEntry zoneRadius;
      public final IntegerEntry durationTicks;
      public final IntegerEntry cooldownTicks;
      public final BooleanEntry clearDrops;
      public final DoubleEntry visualScale;

      private VacuumDecayConfig() {
         super("infinity_stellaris.vacuum_decay", "config.annihilationblade.infinity_stellaris.vacuum_decay.title");
         this.enabled = boolValue("enabled", true, "config.annihilationblade.infinity_stellaris.vacuum_decay.enabled.tooltip");
         this.castRange = doubleValue("cast_range", 160.0, 16.0, 256.0, "真空衰变·坍缩最大视线施法距离（格，与视距对齐避免远距隐身）。", "Maximum cast distance in blocks aligned with render range.");
         this.zoneRadius = doubleValue("zone_radius", 64.0, 16.0, 128.0, "真空衰变·坍缩结界水平半径（格，默认 64）。", "Vacuum Decay Collapse domain horizontal radius in blocks.");
         this.durationTicks = intValue("duration_ticks", 184, 140, 320, "真空衰变·坍缩总演出与结界时长（tick，默认 184 = 9.2 秒）。", "Total Vacuum Decay Collapse duration in ticks (default 184 = 9.2s).");
         this.cooldownTicks = intValue("cooldown_ticks", 200, 0, 1200, "真空衰变·坍缩施法冷却（tick，默认 200 = 10 秒）。", "Vacuum Decay Collapse cast cooldown in ticks.");
         this.clearDrops = boolValue("clear_drops", false, "config.annihilationblade.infinity_stellaris.vacuum_decay.clear_drops.tooltip");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class NightfallDragon extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final DoubleEntry judgementCutRange;
      public final IntegerEntry judgementCutTotalCuts;
      public final IntegerEntry judgementCutIntervalTicks;
      public final DoubleEntry judgementCutDamage;
      public final DoubleEntry judgementCutScale;

      public final IntegerEntry scaleGuardSwordCount;

      public final DoubleEntry cosmicDescentMaxDistance;
      public final DoubleEntry cosmicDescentVortexRadius;
      public final DoubleEntry cosmicDescentExplosionRadius;
      public final IntegerEntry cosmicDescentMeteorWaves;
      public final IntegerEntry cosmicDescentSwordsPerWave;
      public final DoubleEntry cosmicDescentMeteorDamage;
      public final DoubleEntry cosmicDescentCollapsePanelMultiplier;

      public final DoubleEntry demonicBloodExtraDamagePercent;
      public final IntegerEntry demonicBloodPhantomBurstMarks;

      public final DoubleEntry absoluteDomainRadius;
      public final IntegerEntry absoluteDomainMaxTargets;
      public final IntegerEntry bladeStormSwords;
      public final DoubleEntry worldCleavingRange;

      private NightfallDragon() {
         super("nightfall_dragon", "config.annihilationblade.nightfall_dragon.title");
         this.judgementCutRange = doubleValue("judgement_cut_range", 50.0, 10.0, 200.0, "夜陨次元斩索敌与落点最大半径。", "Nightfall Judgement Cut search radius.");
         this.judgementCutTotalCuts = intValue("judgement_cut_total_cuts", 100, 10, 500, "夜陨次元斩单次释放总切痕数。", "Nightfall Judgement Cut total cuts.");
         this.judgementCutIntervalTicks = intValue("judgement_cut_interval_ticks", 2, 1, 20, "夜陨次元斩每斩间隔 tick。", "Ticks between Judgement Cut slashes.");
         this.judgementCutDamage = doubleValue("judgement_cut_damage", 91.0, 1.0, 1000.0, "单次次元斩基础伤害。", "Base damage per Judgement Cut.");
         this.judgementCutScale = doubleValue("judgement_cut_scale", 8.0, 1.0, 32.0, "次元斩判定与模型尺寸倍率。", "Judgement Cut size scale multiplier.");

         this.scaleGuardSwordCount = intValue("scale_guard_sword_count", 16, 4, 64, "鳞之卫魔龙剑环召唤的幻影剑数量。", "Scale Guard sword count.");

         this.cosmicDescentMaxDistance = doubleValue("cosmic_descent_max_distance", 120.0, 20.0, 500.0, "神陨降临焦点搜索最大距离。", "Cosmic Nightfall Descent max focal distance.");
         this.cosmicDescentVortexRadius = doubleValue("cosmic_descent_vortex_radius", 72.0, 10.0, 256.0, "神陨降临引力漩涡拉扯半径。", "Vortex pull radius for Cosmic Descent.");
         this.cosmicDescentExplosionRadius = doubleValue("cosmic_descent_explosion_radius", 72.0, 10.0, 256.0, "神陨降临暗星坍缩最终爆炸半径。", "Final collapse explosion radius for Cosmic Descent.");
         this.cosmicDescentMeteorWaves = intValue("cosmic_descent_meteor_waves", 20, 3, 50, "神陨降临夜陨星雨轰炸波次数。", "Meteor rain wave count for Cosmic Descent.");
         this.cosmicDescentSwordsPerWave = intValue("cosmic_descent_swords_per_wave", 5, 1, 20, "神陨降临夜陨星雨每波降落的剑雨数量。", "Falling swords per meteor wave for Cosmic Descent.");
         this.cosmicDescentMeteorDamage = doubleValue("cosmic_descent_meteor_damage", 78.0, 1.0, 1000.0, "夜陨星雨单波基础伤害。", "Base meteor wave damage.");
         this.cosmicDescentCollapsePanelMultiplier = doubleValue("cosmic_descent_collapse_panel_multiplier", 6.0, 1.0, 50.0, "暗星坍缩最终爆发面板伤害倍率。", "Collapse explosion panel damage multiplier.");

         this.demonicBloodExtraDamagePercent = doubleValue("demonic_blood_extra_damage_percent", 0.05, 0.0, 1.0, "魔血寄生命中时扣除的最大生命值百分比。", "Demonic Blood Parasite extra max health damage percent.");
         this.demonicBloodPhantomBurstMarks = intValue("demonic_blood_phantom_burst_marks", 20, 1, 100, "触发魔血幻影爆发所需印记层数。", "Demonic Blood Parasite phantom burst required marks.");

         this.absoluteDomainRadius = doubleValue("absolute_domain_radius", 64.0, 8.0, 256.0, "终焉绝对结界辐射半径。", "Absolute Annihilation Domain radius.");
         this.absoluteDomainMaxTargets = intValue("absolute_domain_max_targets", 12, 1, 64, "终焉绝对结界单次处理最大目标数。", "Absolute Annihilation Domain max targets per sweep.");
         this.bladeStormSwords = intValue("blade_storm_swords", 16, 10, 300, "万刃龙魂挥刀生成的灭世龙刃总数量。", "Myriad Dragon Blade Storm summoned sword count.");
         this.worldCleavingRange = doubleValue("world_cleaving_range", 72.0, 10.0, 300.0, "灭界龙威剑气撕裂苍穹最大射程。", "World Cleaving Slash beam max range.");
      }
   }

   private static DoubleEntry visualScale(double defaultValue) {
      return doubleValue("visual_scale", defaultValue, 0.25, 2.0, "视觉倍率，只影响低风险视觉半径/粒子密度，建议 0.5-1.25。", "Visual scale; only affects low-risk visual radius/particle density. Suggested: 0.5-1.25.");
   }

   private static IntegerEntry intValue(
      String name, int defaultValue, int min, int max, String chineseComment, String englishComment
   ) {
      return IntegerEntry.builder("config.annihilationblade." + name, defaultValue)
         .range(min, max)
         .key(name)
         .tooltip(Component.literal(chineseComment + " 推荐最小/最大值: " + min + "-" + max + ".\n" + englishComment + " Suggested min/max: " + min + "-" + max + "."))
         .build();
   }

   private static DoubleEntry doubleValue(
      String name, double defaultValue, double min, double max, String chineseComment, String englishComment
   ) {
      return DoubleEntry.builder("config.annihilationblade." + name, defaultValue)
         .range(min, max)
         .key(name)
         .tooltip(Component.literal(chineseComment + " 推荐最小/最大值: " + min + "-" + max + ".\n" + englishComment + " Suggested min/max: " + min + "-" + max + "."))
         .build();
   }

   /** 名称与提示均使用本地化键：config.annihilationblade.{name} / config.annihilationblade.{name}.tooltip。 */
   private static IntegerEntry intKey(String name, int defaultValue, int min, int max) {
      return IntegerEntry.builder("config.annihilationblade." + name, defaultValue)
         .range(min, max)
         .key(name)
         .tooltip("config.annihilationblade." + name + ".tooltip")
         .build();
   }

   /** 名称与提示均使用本地化键：config.annihilationblade.{name} / config.annihilationblade.{name}.tooltip。 */
   private static DoubleEntry doubleKey(String name, double defaultValue, double min, double max) {
      return DoubleEntry.builder("config.annihilationblade." + name, defaultValue)
         .range(min, max)
         .key(name)
         .tooltip("config.annihilationblade." + name + ".tooltip")
         .build();
   }

   private static BooleanEntry seEnabled(String seId) {
      return BooleanEntry.builder("config.annihilationblade.se_enabled", true)
         .key("enabled")
         .tooltip("config.annihilationblade.annihilation_blade." + seId + ".enabled.tooltip")
         .build();
   }

   private static BooleanEntry infinitySeEnabled(String seId) {
      return BooleanEntry.builder("config.annihilationblade.se_enabled", true)
         .key("enabled")
         .tooltip("config.annihilationblade.infinity_stellaris." + seId + ".enabled.tooltip")
         .build();
   }

   private static <T extends Enum<T>> EnumEntry<T> enumKey(
      String name, T defaultValue, java.util.function.Function<T, String> keyProvider
   ) {
      return EnumEntry.builder("config.annihilationblade." + name, defaultValue)
         .key(name)
         .tooltip("config.annihilationblade." + name + ".tooltip")
         .nameProvider(value -> Component.translatable(keyProvider.apply(value)))
         .build();
   }

   private static BooleanEntry boolValue(String name, boolean defaultValue, String tooltipKey) {
      return BooleanEntry.builder("config.annihilationblade." + name, defaultValue)
         .key(name)
         .tooltip(tooltipKey)
         .build();
   }
}
