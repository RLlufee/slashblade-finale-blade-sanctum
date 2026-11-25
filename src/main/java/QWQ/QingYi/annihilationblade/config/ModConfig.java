package QWQ.QingYi.annihilationblade.config;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import com.iafenvoy.jupiter.config.container.AutoInitConfigContainer;
import com.iafenvoy.jupiter.config.entry.BooleanEntry;
import com.iafenvoy.jupiter.config.entry.DoubleEntry;
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
      public final StarlessJudgement starlessJudgement;
      public final PhantomJudgement phantomJudgement;
      public final Domain bloodPrisonDomain;
      public final PhantomBurst bloodPrisonPhantomBurst;

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
         this.starlessJudgement = this.annihilationBlade.starlessJudgement;
         this.phantomJudgement = this.annihilationBlade.phantomJudgement;
         this.bloodPrisonDomain = this.bloodPrison.domain;
         this.bloodPrisonPhantomBurst = this.bloodPrison.phantomBurst;
      }

   }

   public static final class Client extends AutoInitConfigContainer {
      public final ClientTooltips clientTooltips = new ClientTooltips();
      public final ClientRenderer clientRenderer = new ClientRenderer();

      private Client() {
         super(ResourceLocation.fromNamespaceAndPath(Annihilationblade.MODID, "client"), "config.annihilationblade.client.title", "./config/annihilationblade/client.json");
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
      public final StarlessJudgement starlessJudgement;
      public final PhantomJudgement phantomJudgement;

      private AnnihilationBlade() {
         this.spatialFracture = new SpatialFracture();
         this.dankong = new Dankong();
         this.worldRift = new WorldRift();
         this.terminusEcho = new TerminusEcho();
         this.voidDominion = new VoidDominion();
         this.causalityCollapse = new CausalityCollapse();
         this.starlessJudgement = new StarlessJudgement();
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
      public final DoubleEntry rayStep;
      public final DoubleEntry raySampleRadius;
      public final DoubleEntry entityLockRadius;
      public final DoubleEntry backupRadius;
      public final IntegerEntry maxTargets;
      public final IntegerEntry maxVisualizedTargets;
      public final IntegerEntry fractureSlashes;
      public final IntegerEntry centerSlashes;
      public final DoubleEntry visualScale;

      private SpatialFracture() {
         super("annihilation_blade.spatial_fracture", "config.annihilationblade.annihilation_blade.spatial_fracture.title");
         this.maxDistance = doubleValue("max_distance", 160.0, 32.0, 256.0, "最大施法距离，建议 64-192。", "Maximum cast distance. Suggested: 64-192.");
         this.fractureRadius = doubleValue("fracture_radius", 20.0, 4.0, 64.0, "空间破碎主判定半径，建议 12-32。", "Main Spatial Fracture target radius. Suggested: 12-32.");
         this.rayStep = doubleValue("ray_step", 4.0, 1.0, 12.0, "沿视线补充扫描步长，越小越密，建议 2-6。", "Backup ray scan step; lower means denser scan. Suggested: 2-6.");
         this.raySampleRadius = doubleValue("ray_sample_radius", 5.0, 1.0, 16.0, "沿视线每个采样点的扫描半径，建议 3-8。", "Backup ray sample radius. Suggested: 3-8.");
         this.entityLockRadius = doubleValue("entity_lock_radius", 3.0, 0.5, 10.0, "准星锁定实体的宽容半径，建议 2-5。", "Aim lock tolerance around entities. Suggested: 2-5.");
         this.backupRadius = doubleValue("backup_radius", 48.0, 8.0, 96.0, "没有命中目标时的备用搜索半径，建议 24-64。", "Fallback search radius when no target is found. Suggested: 24-64.");
         this.maxTargets = intValue("max_targets", 128, 1, 256, "最大影响目标数，建议 32-160。", "Maximum affected targets. Suggested: 32-160.");
         this.maxVisualizedTargets = intValue("max_visualized_targets", 32, 0, 128, "最多播放单体斩击视觉的目标数，建议 16-48。", "Targets with individual slash visuals. Suggested: 16-48.");
         this.fractureSlashes = intValue("fracture_slashes", 24, 0, 96, "裂隙风暴斩击线数量，建议 12-36。", "Fracture storm visual slash count. Suggested: 12-36.");
         this.centerSlashes = intValue("center_slashes", 12, 0, 48, "中心 SlashBlade 斩击数量，建议 6-18。", "Center SlashBlade slash count. Suggested: 6-18.");
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
         this.cooldownTicks = intValue("cooldown_ticks", 70, 0, 200, "触发冷却 tick，建议 50-100。", "Trigger cooldown in ticks. Suggested: 50-100.");
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
      public final IntegerEntry scarDurationTicks;
      public final DoubleEntry stasisRadius;
      public final IntegerEntry stasisTicks;
      public final IntegerEntry maxStasisTargets;
      public final IntegerEntry cooldownTicks;
      public final DoubleEntry visualScale;

      private CausalityCollapse() {
         super("annihilation_blade.causality_collapse", "config.annihilationblade.annihilation_blade.causality_collapse.title");
         this.enabled = seEnabled("causality_collapse");
         this.worldRiftLinkage = boolValue("world_rift_linkage", true, "config.annihilationblade.world_rift_linkage.tooltip");
         this.scarDurationTicks = intValue(
            "scar_duration_ticks", 64, 20, 200,
            "单道因果时空裂痕存续总时长（tick，20 tick = 1 秒，默认 64 tick ≈ 3.2 秒）。",
            "Lifetime in ticks of each causal spacetime rift scar (20 ticks = 1s; default 64 ticks ≈ 3.2s)."
         );
         this.stasisRadius = doubleValue(
            "stasis_radius", 4.5, 1.5, 16.0,
            "单道因果裂痕初裂瞬间施加零伤害微扰定身的局部半径，建议 3-6。",
            "Local radius around each causal rift scar for the zero-damage micro-stasis. Suggested: 3-6."
         );
         this.stasisTicks = intValue(
            "stasis_ticks", 6, 0, 20,
            "裂痕初裂瞬间对局部范围内敌对生物施加的零伤害微扰定身时长（tick，6 tick = 0.3 秒；设为 0 则纯观赏无定身）。",
            "Duration in ticks of the zero-damage micro-stasis applied on rift crack (6 ticks = 0.3s; 0 for pure visual)."
         );
         this.maxStasisTargets = intValue("max_stasis_targets", 32, 1, 96, "单次裂痕最多施加微扰定身的敌对生物数，建议 16-48。", "Maximum hostile targets affected by rift micro-stasis. Suggested: 16-48.");
         this.cooldownTicks = intValue("cooldown_ticks", 16, 0, 120, "独立空挥触发单道因果裂痕的冷却 tick，建议 12-30（神威联动不受此冷却限制）。", "Standalone air-slash causal rift cooldown in ticks. Suggested: 12-30 (World Rift linkage ignores this cooldown).");
         this.visualScale = visualScale(1.0);
      }
   }

   public static final class StarlessJudgement extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final BooleanEntry enabled;
      public final DoubleEntry range;
      public final DoubleEntry width;
      public final IntegerEntry cooldownTicks;
      public final IntegerEntry maxTargets;
      public final DoubleEntry visualScale;

      private StarlessJudgement() {
         super("annihilation_blade.starless_judgement", "config.annihilationblade.annihilation_blade.starless_judgement.title");
         this.enabled = seEnabled("starless_judgement");
         this.range = doubleValue("range", 56.0, 8.0, 128.0, "星寂裁决射程，建议 36-72。", "Starless Judgement beam range. Suggested: 36-72.");
         this.width = doubleValue("width", 8.5, 1.0, 24.0, "星寂裁决宽度，建议 5-12。", "Starless Judgement beam width. Suggested: 5-12.");
         this.cooldownTicks = intValue("cooldown_ticks", 34, 0, 160, "触发冷却 tick，建议 24-60。", "Trigger cooldown in ticks. Suggested: 24-60.");
         this.maxTargets = intValue("max_targets", 80, 1, 200, "星寂裁决最多影响目标数，建议 40-120。", "Maximum Starless Judgement targets. Suggested: 40-120.");
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
      public final IntegerEntry borderIntervalTicks;
      public final IntegerEntry playerAuraIntervalTicks;
      public final IntegerEntry pulseIntervalTicks;
      public final DoubleEntry visualScale;

      private Domain() {
         super("blood_prison.domain", "config.annihilationblade.blood_prison.domain.title");
         this.durationTicks = intValue("duration_ticks", 400, 40, 1200, "血狱领域持续 tick，20 tick=1秒，建议 200-600。", "Blood Prison domain duration in ticks. Suggested: 200-600.");
         this.radius = doubleValue("radius", 10.0, 3.0, 32.0, "血狱领域半径，建议 8-14。", "Blood Prison domain radius. Suggested: 8-14.");
         this.borderIntervalTicks = intValue("border_interval_ticks", 10, 2, 40, "领域边界粒子刷新间隔，建议 8-16。", "Domain border particle interval. Suggested: 8-16.");
         this.playerAuraIntervalTicks = intValue("player_aura_interval_ticks", 4, 1, 40, "领域内玩家血气粒子间隔，建议 4-10。", "Player aura particle interval inside domain. Suggested: 4-10.");
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

   public static final class InfinityStellaris extends AutoInitConfigContainer.AutoInitConfigCategoryBase {
      public final DoubleEntry entropyPercent;
      public final IntegerEntry entropyMarks;
      public final IntegerEntry entropyBlacklistTicks;
      public final DoubleEntry curvatureRadius;
      public final IntegerEntry curvatureTickInterval;
      public final IntegerEntry curvatureMaxTargets;
      public final IntegerEntry curvatureBurstMarks;
      public final BooleanEntry enableAuraParticles;
      public final BooleanEntry enableStringLatticeParticles;

      private InfinityStellaris() {
         super("infinity_stellaris", "config.annihilationblade.infinity_stellaris.title");
         this.entropyPercent = doubleValue("entropy_percent", 0.10, 0.0, 1.0, "熵增蚀解每次追加的最大生命百分比。", "Max-health percentage added by Entropy Dissolution.");
         this.entropyMarks = intValue("entropy_marks", 10, 1, 100, "触发热寂归零所需累计次数。", "Marks required before heat-death zeroing.");
         this.entropyBlacklistTicks = intValue("entropy_blacklist_ticks", 40, 0, 400, "非玩家目标最终阶段后的短期黑名单 tick。", "Temporary blacklist ticks after final non-player execution.");
         this.curvatureRadius = doubleValue("curvature_radius", 32.0, 1.0, 128.0, "曲率撕裂冻结半径。", "Curvature rupture freeze radius.");
         this.curvatureTickInterval = intValue("curvature_tick_interval", 20, 1, 100, "曲率压缩伤害间隔 tick。", "Curvature strain mark interval in ticks.");
         this.curvatureMaxTargets = intValue("curvature_max_targets", 64, 1, 256, "曲率撕裂每次最多压制目标数。", "Maximum targets suppressed by Curvature Rupture.");
         this.curvatureBurstMarks = intValue("curvature_burst_marks", 5, 1, 100, "触发曲率爆裂所需层数。", "Marks required to trigger curvature burst.");
         this.enableAuraParticles = boolValue("enable_aura_particles", true, "config.annihilationblade.infinity_stellaris.enable_aura_particles.tooltip");
         this.enableStringLatticeParticles = boolValue("enable_string_lattice_particles", false, "config.annihilationblade.infinity_stellaris.enable_string_lattice_particles.tooltip");
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

   private static BooleanEntry boolValue(String name, boolean defaultValue, String tooltipKey) {
      return BooleanEntry.builder("config.annihilationblade." + name, defaultValue)
         .key(name)
         .tooltip(tooltipKey)
         .build();
   }
}
