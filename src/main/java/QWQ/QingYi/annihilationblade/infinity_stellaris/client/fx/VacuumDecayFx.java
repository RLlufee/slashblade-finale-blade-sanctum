package QWQ.QingYi.annihilationblade.infinity_stellaris.client.fx;

import QWQ.QingYi.annihilationblade.annihilation_blade.client.ClientBladeVision;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.core.FxInstance;
import QWQ.QingYi.annihilationblade.render.client.core.FxPass;
import QWQ.QingYi.annihilationblade.render.client.core.FxScreenState;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry.Style;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxLightningBolt;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxShaders;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import QWQ.QingYi.annihilationblade.stellaris.pattern.SevenStarPattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;

/**
 * 无尽星空 SA「真空衰变·坍缩（Vacuum Decay Collapse）」高规格渲染实例。
 * <p>
 * 完整启用预乘 Alpha 暗部实体层（{@link #hasBody}）+ HDR 立体几何层 + B 档真实黑洞透镜/双波前体积通道 + 屏幕冲击帧/FOV 拉伸，
 * 严格对齐服务端 {@link SevenStarPattern} 184 tick 乐章时间轴：
 * <pre>
 *   0 ─  14   真空泡成核扩张、极光帷幕升起与虚空星海夺夜
 *   6 ─  44   天穹黑洞成形：3D 实心深渊日蚀球壳 + 引力透镜 + 倾斜吸积盘 + 双 3D 轨道环 + 相对论喷流
 *  44 ─  96   14 波七星齐射（共享种子精准同坐标落点 + 地表激波环），末段黑洞剧烈颤振脱锚
 *  96 ─ 116   天穹黑洞脱轨加速坠落：引力透镜与事件视界随黑洞同坠，包裹 3D 大气再入燃烧激波锥与尾焰柱，地表法阵锁定
 * 116 ─ 124   黑洞轰然坠地（地表 3D 马赫气浪裙 + 震地重响）→ 接地过载暴涨 1.72×，360° 能量流丝与 3D 棱晶疯狂倒灌
 * 124 ─ 129   极速重力坍缩内爆：视界、吸积盘、三道 3D 锁环与全部棱晶在 5 tick 内暴缩坍塌至针尖奇点，视野剧烈向心拉弓
 * 129 ─ 134   5 tick 绝对死寂呼吸拍：零雷、零震、冷色压暗、针尖白热奇点与宽银幕横向星芒悬于爆心
 * 134 ─ 172   超新星真空大爆炸（与服务端清算帧级同步）：光敏安全冲击帧 + 正向 FOV 弹射 + 双层沸腾爆裂火球壳
 *             + 冲天坍缩光柱 + 贴地双层马赫气浪锥 + 双波前体积冲击球 + 炸裂立体星环 + 54 枚 3D 黑曜棱晶爆射
 * 166 ─ 184   现实重绘余韵：零重力余烬升腾，极光与虚空平稳淡出回归世界
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public final class VacuumDecayFx extends FxInstance {
   private static final int DEFAULT_DURATION = SevenStarPattern.DEFAULT_DURATION;
   private static final int BLAST_DURATION = 38;
   private static final int OPEN_END = 14;
   private static final int MAX_BOLTS = 160;

   // 星穹视觉色彩规范：切伦科夫冰青 / 伽马电紫 / 恒星白热 / 黑体金 / 深渊黑曜底色
   private static final float[] CYAN = {0.11F, 0.96F, 1.0F};
   private static final float[] VIOLET = {0.49F, 0.36F, 1.0F};
   private static final float[] WHITE = {0.94F, 0.98F, 1.0F};
   private static final float[] GOLD = {1.0F, 0.74F, 0.32F};
   private static final float[] ABYSS = {0.08F, 0.03F, 0.16F};
   private static final float[] OBSIDIAN = {0.13F, 0.07F, 0.26F};
   private static final float[][] BOLT_COLORS = {CYAN, WHITE, VIOLET, CYAN, GOLD};

   private final Vec3 center;
   private final float half;
   private final float above;
   private final float below;
   private final double groundY;
   private final double ceilingY;
   private final boolean enclosedSpace;
   /** 天穹悬停阶段的黑洞出生高点。 */
   private final Vec3 zenithSingularity;
   /** 黑洞坠落后砸击并发生坍缩大爆炸的地面爆心。 */
   private final Vec3 impactSingularity;
   private final float bubbleMax;
   /** 黑洞事件视界基准半径（接地过载期膨胀至 1.72×，内爆坍缩期暴压至 0.06×）。 */
   private final float coreMax;
   private final float diskRadius;
   private final long seed;
   private final float seedFloat;
   private final int boltsPerTick;
   private final float visualScale;
   private final int rawIntParam;
   private final RandomSource random;

   private final List<FxLightningBolt> bolts = new ArrayList<>();
   private final List<Ember> embers = new ArrayList<>();
   private final List<StarPulse> starPulses = new ArrayList<>();
   private final List<InfallStream> infallStreams = new ArrayList<>();
   private final List<CollapseShard> collapseShards = new ArrayList<>();
   private final List<BlastShard> blastShards = new ArrayList<>();

   private int totalDuration = DEFAULT_DURATION;
   private int plungeStartAge = SevenStarPattern.TICK_PLUNGE_START;
   private int plungeImpactAge = SevenStarPattern.TICK_PLUNGE_IMPACT;
   private int implosionStartAge = SevenStarPattern.TICK_IMPLOSION_START;
   private int silenceStartAge = SevenStarPattern.TICK_SILENCE_START;
   private int reckoningAge = SevenStarPattern.TICK_FINAL_RECKONING;

   private boolean plungeSoundPlayed;
   private boolean impactTriggered;
   private boolean implosionSoundPlayed;
   private boolean detonationTriggered;
   private boolean infallInitialized;
   private float strike;

   public VacuumDecayFx(ClientLevel level, FxEventPacket packet) {
      super(DEFAULT_DURATION);
      this.center = packet.origin();
      this.half = Math.max(8.0F, packet.radius());
      float height = Math.max(8.0F, Math.min(96.0F, packet.scale()));
      this.above = height * 0.5F;
      this.below = height * 0.5F;
      this.seed = packet.seed();
      this.seedFloat = (float) ((packet.seed() & 0xFFFFL) / 65535.0 * 97.0);
      this.rawIntParam = packet.intParam();
      this.boltsPerTick = SevenStarPattern.unpackBoltsPerTick(this.rawIntParam);
      this.visualScale = SevenStarPattern.unpackVisualScale(this.rawIntParam);
      this.random = RandomSource.create(packet.seed());
      this.recomputeTimeline(DEFAULT_DURATION);

      // 地表与顶棚双重自适应探测：同时兼容平原、峡谷、深邃矿洞与下界基岩顶
      BlockHitResult groundHit = level.clip(new ClipContext(
         this.center.add(0.0, 2.5, 0.0), this.center.subtract(0.0, 28.0, 0.0),
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null
      ));
      this.groundY = groundHit.getType() == HitResult.Type.MISS ? this.center.y - 0.5 : groundHit.getLocation().y;

      double desiredSingularityY = this.center.y + (this.above + this.half * 0.16) * Mth.clamp(this.visualScale, 0.65F, 1.35F);
      double probeTop = Math.min(level.getMaxBuildHeight() - 2.0, desiredSingularityY + 12.0);
      BlockHitResult ceilingHit = level.clip(new ClipContext(
         new Vec3(this.center.x, this.groundY + 1.5, this.center.z),
         new Vec3(this.center.x, probeTop, this.center.z),
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null
      ));
      this.ceilingY = ceilingHit.getType() == HitResult.Type.BLOCK
         ? ceilingHit.getLocation().y - 1.5
         : probeTop;
      int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(this.center.x), (int) Math.floor(this.center.z));
      this.enclosedSpace = level.dimensionType().hasCeiling() || ceilingHit.getType() == HitResult.Type.BLOCK || this.groundY < surfaceY - 8.0;

      float sizeScale = Mth.clamp(this.visualScale, 0.55F, 1.6F);
      double clearance = Math.max(6.0, this.ceilingY - this.groundY);
      this.coreMax = (float) Math.min(this.half * 0.145F * sizeScale, clearance * 0.24);
      this.diskRadius = (float) Math.min(this.half * 0.82F * sizeScale, Math.max(12.0, clearance * 1.15));

      double zenithY = Math.min(desiredSingularityY, Math.max(this.groundY + this.coreMax + 3.5, this.ceilingY - this.coreMax * 1.15));
      // 地面爆心悬浮在真实地表正上方，兼顾贴地压迫感与低顶棚防穿模
      double impactClearance = Math.max(2.2, Math.min(this.coreMax * 0.78, clearance * 0.26));
      double impactY = Math.min(zenithY, this.groundY + impactClearance);
      this.zenithSingularity = new Vec3(this.center.x, zenithY, this.center.z);
      this.impactSingularity = new Vec3(this.center.x, impactY, this.center.z);
      this.bubbleMax = this.half * 1.45F;
   }

   /** 根据终幕清算拍点推算坠落、砸地、内爆、死寂的严格单调时间轴。 */
   private void recomputeTimeline(int duration) {
      this.totalDuration = Math.max(28, duration);
      int unpacked = SevenStarPattern.unpackReckoningTick(this.rawIntParam, this.totalDuration);
      if (unpacked >= this.totalDuration) {
         unpacked = Math.max(24, this.totalDuration - BLAST_DURATION);
      }
      this.reckoningAge = Math.max(22, unpacked);
      this.silenceStartAge = Math.max(18, this.reckoningAge - 5);
      this.implosionStartAge = Math.max(14, this.silenceStartAge - 5);
      this.plungeImpactAge = Math.max(10, this.implosionStartAge - 8);
      this.plungeStartAge = Math.max(6, this.plungeImpactAge - 20);
      this.lifetime = Math.max(this.totalDuration, this.reckoningAge + BLAST_DURATION);
   }

   @Override
   protected void onSustainedStart(int duration) {
      this.recomputeTimeline(duration);
   }

   @Override
   public void onSustainedStop() {
      if (this.age < this.reckoningAge) {
         // 提前中断时快速收束：若尚未坠地则直接快坠后内爆，确保平滑无跳变
         int now = this.age;
         this.plungeStartAge = Math.min(this.plungeStartAge, now);
         this.plungeImpactAge = Math.min(this.plungeImpactAge, now + 4);
         this.implosionStartAge = Math.min(this.implosionStartAge, this.plungeImpactAge + 2);
         this.silenceStartAge = Math.min(this.silenceStartAge, this.implosionStartAge + 3);
         this.reckoningAge = this.silenceStartAge + 3;
         this.totalDuration = this.reckoningAge + BLAST_DURATION;
         this.lifetime = this.totalDuration;
      }
   }

   @Override
   public Vec3 focus() {
      return this.center;
   }

   @Override
   public double cullDistance() {
      return Math.max(320.0, this.half + 256.0);
   }

   @Override
   public boolean hasBody() {
      return true;
   }

   // ───────────────────────────── 时间轴与黑洞动力学曲线 ─────────────────────────────

   private float open(float a) {
      return FxMath.easeOutCubic(FxMath.window(a, 0.0F, OPEN_END));
   }

   private float rise(float a) {
      return FxMath.easeOutCubic(FxMath.window(a, 4.0F, 20.0F));
   }

   /** 黑洞坠落进度（96t → 116t，0..1）。 */
   private float plungeProgress(float a) {
      return FxMath.window(a, this.plungeStartAge, this.plungeImpactAge);
   }

   /** 坠落活跃包络（坠落期间为 0..1，砸地瞬间平滑收尾）。 */
   private float plungeActive(float a) {
      if (a <= this.plungeStartAge || a >= this.plungeImpactAge + 2.0F) {
         return 0.0F;
      }
      if (a < this.plungeImpactAge) {
         float p = this.plungeProgress(a);
         return FxMath.easeOutCubic(Math.min(1.0F, p * 4.0F));
      }
      return 1.0F - FxMath.window(a, this.plungeImpactAge, this.plungeImpactAge + 2.0F);
   }

   /** 砸地瞬间冲击波进度（116t → 127t）。 */
   private float impactShock(float a) {
      return FxMath.window(a, this.plungeImpactAge, this.plungeImpactAge + 11.0F);
   }

   /** 接地过载暴涨进度（116t → 124t）。 */
   private float overload(float a) {
      if (a < this.plungeImpactAge || a >= this.reckoningAge) {
         return 0.0F;
      }
      if (a < this.implosionStartAge) {
         return FxMath.easeOutCubic(FxMath.window(a, this.plungeImpactAge, this.implosionStartAge));
      }
      return 1.0F;
   }

   /** 极速重力内爆坍缩进度（124t → 129t，0..1；129t 后保持 1.0 直至大爆炸）。 */
   private float implosion(float a) {
      if (a < this.implosionStartAge || a >= this.reckoningAge) {
         return 0.0F;
      }
      if (a < this.silenceStartAge) {
         return FxMath.easeInCubic(FxMath.window(a, this.implosionStartAge, this.silenceStartAge));
      }
      return 1.0F;
   }

   /** 5 tick 绝对死寂呼吸拍进度（129t → 134t）。 */
   private float silence(float a) {
      if (a < this.silenceStartAge || a >= this.reckoningAge) {
         return 0.0F;
      }
      return FxMath.window(a, this.silenceStartAge, this.reckoningAge);
   }

   /** A6 超新星大爆炸进度（134t → 134 + BLAST_DURATION）。 */
   private float aftermath(float a) {
      return FxMath.window(a, this.reckoningAge, this.reckoningAge + BLAST_DURATION);
   }

   /** 结界整体可见度：134t 爆裂后在 A7 窗口内平滑重绘回归现实世界。 */
   private float zoneAlpha(float a) {
      if (a < this.reckoningAge) {
         return 1.0F;
      }
      float fadeEnd = Math.max(this.reckoningAge + 18.0F, this.totalDuration);
      return 1.0F - FxMath.easeOutCubic(FxMath.window(a, this.reckoningAge + 8.0F, fadeEnd));
   }

   /**
    * 当前帧黑洞奇点的世界坐标：
    * 天穹悬停（含脱轨前颤振） → 重力加速坠向地面（t^2.35） → 钉死在地面爆心过载、内爆并引爆超新星！
    */
   private Vec3 singularityPos(float a) {
      if (a <= this.plungeStartAge) {
         float preJitter = FxMath.window(a, this.plungeStartAge - 8.0F, this.plungeStartAge);
         if (preJitter <= 0.0F) {
            return this.zenithSingularity;
         }
         double amp = this.coreMax * 0.075 * preJitter * preJitter;
         double jx = Math.sin(a * 3.7 + this.seedFloat) * amp;
         double jy = Math.cos(a * 4.3 + this.seedFloat * 0.7) * amp * 0.65;
         double jz = Math.sin(a * 3.1 - this.seedFloat * 1.3) * amp;
         return this.zenithSingularity.add(jx, jy, jz);
      }
      if (a < this.plungeImpactAge) {
         float p = this.plungeProgress(a);
         // 重力加速度下坠曲线：初段沉重脱锚，后半段势不可挡轰然砸地
         double drop = Math.pow(p, 2.35);
         double y = Mth.lerp(drop, this.zenithSingularity.y, this.impactSingularity.y);
         double wobble = Math.sin(p * Math.PI) * (1.0 - p) * this.coreMax * 0.12;
         double wx = Math.cos(a * 1.6 + this.seedFloat) * wobble;
         double wz = Math.sin(a * 1.6 + this.seedFloat) * wobble;
         return new Vec3(this.center.x + wx, y, this.center.z + wz);
      }
      // 接地过载期高频沸腾震颤，内爆后期收敛归零，死寂拍纹丝不动
      if (a < this.silenceStartAge) {
         float ov = this.overload(a);
         float imp = this.implosion(a);
         double amp = this.coreMax * 0.045 * ov * (1.0 - imp * 0.85);
         if (amp > 1.0E-4) {
            double jx = Math.sin(a * 5.3 + this.seedFloat) * amp;
            double jy = Math.cos(a * 6.1 - this.seedFloat) * amp * 0.6;
            double jz = Math.sin(a * 4.9 + this.seedFloat * 0.5) * amp;
            return this.impactSingularity.add(jx, jy, jz);
         }
      }
      return this.impactSingularity;
   }

   /**
    * 黑洞事件视界实时半径：
    * 诞生扩张 → 天穹稳定 → 坠落蓄势（1.08×） → 接地过载暴涨（1.72×） → 极速内爆急缩（0.06×） → 死寂针尖 → 爆发碎灭。
    */
   private float coreRadius(float a) {
      if (a < this.plungeStartAge) {
         return this.coreMax * FxMath.easeOutCubic(FxMath.window(a, 6.0F, 24.0F));
      }
      if (a < this.plungeImpactAge) {
         float p = this.plungeProgress(a);
         return this.coreMax * FxMath.lerp(1.0F, 1.08F, p * p);
      }
      if (a < this.implosionStartAge) {
         float ov = FxMath.easeOutCubic(FxMath.window(a, this.plungeImpactAge, this.implosionStartAge));
         float pulse = 1.0F + 0.045F * ov * (float) Math.sin(a * 2.8F);
         return this.coreMax * FxMath.lerp(1.08F, 1.72F, ov) * pulse;
      }
      if (a < this.silenceStartAge) {
         float imp = FxMath.easeInCubic(FxMath.window(a, this.implosionStartAge, this.silenceStartAge));
         return this.coreMax * FxMath.lerp(1.72F, 0.062F, imp);
      }
      if (a < this.reckoningAge) {
         float sil = this.silence(a);
         float beat = 1.0F + 0.14F * (float) Math.sin(sil * Math.PI * 3.0);
         return this.coreMax * FxMath.lerp(0.062F, 0.045F, sil) * beat;
      }
      return this.coreMax * 0.09F * (1.0F - FxMath.window(a, this.reckoningAge, this.reckoningAge + 2.5F));
   }

   // ───────────────────────────── 逻辑 tick ─────────────────────────────

   @Override
   public void tick(ClientLevel level) {
      int age = this.ageTicks();
      Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
      Vec3 curSingularity = this.singularityPos(age);
      this.strike *= 0.8F;

      // 结界夺夜期间平滑退让持刀永昼，保证黑洞与超新星深空高反差
      if (age < this.totalDuration - 8 && this.attenuation(camera) > 0.25F) {
         ClientBladeVision.suppressFullbrightForTicks(4);
      }

      // 更新闪电与七星激波环
      Iterator<FxLightningBolt> boltIterator = this.bolts.iterator();
      while (boltIterator.hasNext()) {
         FxLightningBolt bolt = boltIterator.next();
         bolt.tick(this.random);
         if (bolt.dead()) {
            boltIterator.remove();
         }
      }
      Iterator<StarPulse> pulseIterator = this.starPulses.iterator();
      while (pulseIterator.hasNext()) {
         StarPulse pulse = pulseIterator.next();
         pulse.age++;
         if (pulse.age >= pulse.maxAge) {
            pulseIterator.remove();
         }
      }

      // ── 阶段 A1~A3：4..96t 常规伽马天雷与 14 波七星齐射 ──
      if (age >= 4 && age < this.plungeStartAge) {
         FxLightningBolt nearest = null;
         double nearestDistance = Double.MAX_VALUE;

         int waveIndex = SevenStarPattern.volleyWaveIndex(age);
         if (waveIndex >= 0) {
            Vec3 impactBase = SevenStarPattern.waveImpactPos(this.seed, waveIndex, this.center, this.half);
            FxLightningBolt spear = this.spawnBoltAt(level, impactBase.x, impactBase.z, waveIndex % 2 == 0 ? WHITE : CYAN, 3);
            if (spear != null) {
               this.bolts.add(spear);
               this.starPulses.add(new StarPulse(new Vec3(spear.x, spear.ground + 0.12, spear.z), (float) Math.max(8.0, this.half * 0.24), spear.color));
               nearest = spear;
               nearestDistance = camera.distanceToSqr(spear.x, spear.ground, spear.z);
               int emberCount = Math.max(6, Math.round(14 * this.visualScale));
               for (int k = 0; k < emberCount; k++) {
                  this.embers.add(Ember.at(new Vec3(spear.x, spear.ground + 0.25, spear.z), this.random, spear.color));
               }
            }
         }

         int ambientBudget;
         if (age >= SevenStarPattern.TICK_VOLLEY_START && age < SevenStarPattern.TICK_VOLLEY_END_EXCLUSIVE) {
            ambientBudget = Math.max(2, Math.round(this.boltsPerTick * 0.7F * this.visualScale));
         } else {
            ambientBudget = age % 2 == 0 ? Math.max(1, Math.round(this.boltsPerTick * 0.3F * this.visualScale)) : 0;
         }

         for (int i = 0; i < ambientBudget && this.bolts.size() < MAX_BOLTS; i++) {
            FxLightningBolt bolt = this.spawnBolt(level);
            if (bolt == null) {
               continue;
            }
            this.bolts.add(bolt);
            double distance = camera.distanceToSqr(bolt.x, bolt.ground, bolt.z);
            if (distance < nearestDistance) {
               nearestDistance = distance;
               nearest = bolt;
            }
            if (distance < 64.0 * 64.0) {
               for (int k = 0; k < 3; k++) {
                  this.embers.add(Ember.at(new Vec3(bolt.x, bolt.ground + 0.2, bolt.z), this.random, bolt.color));
               }
            }
         }

         if (nearest != null) {
            if (waveIndex >= 0) {
               level.playLocalSound(nearest.x, nearest.ground, nearest.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 3.2F, 0.85F + this.random.nextFloat() * 0.2F, false);
               level.playLocalSound(nearest.x, nearest.ground, nearest.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.4F, 1.35F, false);
            } else if (age % 6 == 0) {
               level.playLocalSound(nearest.x, nearest.ground, nearest.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2.4F, 0.78F + this.random.nextFloat() * 0.2F, false);
            }
            if (nearestDistance < 40.0 * 40.0 && (waveIndex >= 0 || age % 4 == 0)) {
               level.playLocalSound(nearest.x, nearest.ground, nearest.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 1.6F, 0.55F + this.random.nextFloat() * 0.2F, false);
               this.strike = Math.max(this.strike, (float) (1.0 - Math.sqrt(nearestDistance) / 40.0) * 0.32F);
            }
         }
      }

      // ── 阶段 A4：天穹黑洞脱轨坠落（96..116t） ──
      if (age >= this.plungeStartAge && age < this.plungeImpactAge) {
         if (!this.plungeSoundPlayed) {
            this.plungeSoundPlayed = true;
            level.playLocalSound(curSingularity.x, curSingularity.y, curSingularity.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 3.4F, 0.36F, false);
            level.playLocalSound(this.impactSingularity.x, this.impactSingularity.y, this.impactSingularity.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 3.0F, 0.42F, false);
         }
         // 坠落风压把落点四周的地表星尘向上抽离，并从黑洞表层剥离燃烧火花
         int liftCount = Math.max(4, Math.round(9 * this.visualScale));
         float p = this.plungeProgress(age);
         for (int i = 0; i < liftCount; i++) {
            double ang = this.random.nextDouble() * Math.PI * 2.0;
            double dist = (3.0 + this.random.nextDouble() * this.half * 0.45) * (1.0 - p * 0.35);
            Vec3 basePos = new Vec3(this.center.x + Math.cos(ang) * dist, this.groundY + 0.25, this.center.z + Math.sin(ang) * dist);
            float[] color = i % 3 == 0 ? VIOLET : CYAN;
            this.embers.add(Ember.suckToward(basePos, curSingularity, 0.42 + p * 0.55, 10 + this.random.nextInt(8), color));
         }
         // 黑洞迎风面甩出灼热再入火花
         for (int i = 0; i < 4; i++) {
            Vector3f dir = FxMath.randomUnit(this.random);
            Vec3 spawn = curSingularity.add(dir.x * this.coreMax * 0.9, -Math.abs(dir.y) * this.coreMax * 0.7, dir.z * this.coreMax * 0.9);
            float[] color = i == 0 ? GOLD : (i % 2 == 0 ? WHITE : CYAN);
            this.embers.add(new Ember(spawn, dir.x * 0.45, 0.55 + this.random.nextDouble() * 0.6, dir.z * 0.45, 10 + this.random.nextInt(8), color, null));
         }
      }

      // ── 阶段 A5a 起点：黑洞坠地砸击瞬间（116t） ──
      if (!this.impactTriggered && age >= this.plungeImpactAge && age < this.reckoningAge) {
         this.impactTriggered = true;
         level.playLocalSound(this.impactSingularity.x, this.impactSingularity.y, this.impactSingularity.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3.8F, 0.42F, false);
         level.playLocalSound(this.impactSingularity.x, this.impactSingularity.y, this.impactSingularity.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 3.2F, 0.48F, false);
         this.starPulses.add(new StarPulse(new Vec3(this.center.x, this.groundY + 0.14, this.center.z), this.half * 0.92F, CYAN));
         // 砸地溅射地表火花环
         int splashCount = Math.max(24, Math.round(48 * this.visualScale));
         for (int i = 0; i < splashCount; i++) {
            double ang = (Math.PI * 2.0 * i) / splashCount + (this.random.nextDouble() - 0.5) * 0.12;
            double spd = 0.85 + this.random.nextDouble() * 1.35;
            Vec3 pos = new Vec3(this.center.x + Math.cos(ang) * this.coreMax * 0.8, this.groundY + 0.3, this.center.z + Math.sin(ang) * this.coreMax * 0.8);
            this.embers.add(new Ember(pos, Math.cos(ang) * spd, 0.35 + this.random.nextDouble() * 0.55, Math.sin(ang) * spd, 12 + this.random.nextInt(10), i % 3 == 0 ? WHITE : CYAN, null));
         }
      }

      // ── 阶段 A5a~A5b：接地过载暴涨 + 极速内爆坍缩（116..129t） ──
      if (age >= this.plungeImpactAge && age < this.silenceStartAge) {
         if (!this.infallInitialized) {
            this.initInfallStructures();
         }
         // 内爆启动拍（124t）：高频重力压缩鸣响
         if (!this.implosionSoundPlayed && age >= this.implosionStartAge) {
            this.implosionSoundPlayed = true;
            level.playLocalSound(this.impactSingularity.x, this.impactSingularity.y, this.impactSingularity.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 4.2F, 1.35F, false);
            level.playLocalSound(this.impactSingularity.x, this.impactSingularity.y, this.impactSingularity.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2.8F, 1.85F, false);
         }
         // 持续从四周疯狂向地面黑洞倒吸星屑流
         int suckCount = Math.max(8, Math.round((age >= this.implosionStartAge ? 22 : 14) * this.visualScale));
         for (int i = 0; i < suckCount; i++) {
            Vector3f dir = FxMath.randomUnit(this.random);
            double r = this.coreMax * (2.6 + this.random.nextDouble() * 4.8);
            Vec3 start = this.impactSingularity.add(dir.x * r, Math.max(-this.impactSingularity.y + this.groundY + 0.25, dir.y * r * 0.65), dir.z * r);
            float[] color = BOLT_COLORS[i % BOLT_COLORS.length];
            this.embers.add(Ember.suckToward(start, this.impactSingularity, 1.15 + this.random.nextDouble() * 1.1, 7 + this.random.nextInt(6), color));
         }
      }

      // 129..133t 绝对死寂呼吸拍：彻底清空残留雷电，令画面与听觉进入真空般寂静
      if (age >= this.silenceStartAge && age < this.reckoningAge && !this.bolts.isEmpty()) {
         this.bolts.clear();
      }

      // ── 阶段 A6：第 134 tick 超新星真空坍缩大爆炸（与服务端热寂清算帧级对齐！） ──
      if (!this.detonationTriggered && age >= this.reckoningAge) {
         this.detonationTriggered = true;
         this.bolts.clear();
         this.infallStreams.clear();
         this.collapseShards.clear();
         this.triggerSupernovaDetonation(level);
      }

      // 更新 3D 爆裂棱晶碎骸
      Iterator<BlastShard> blastIt = this.blastShards.iterator();
      while (blastIt.hasNext()) {
         BlastShard shard = blastIt.next();
         shard.tick(this.groundY);
         if (shard.life <= 0) {
            blastIt.remove();
         }
      }

      // 更新余烬
      Iterator<Ember> emberIterator = this.embers.iterator();
      while (emberIterator.hasNext()) {
         Ember ember = emberIterator.next();
         ember.tick();
         if (ember.life <= 0) {
            emberIterator.remove();
         }
      }
   }

   /** 初始化接地过载与极速内爆阶段使用的 24 条螺旋倒灌流丝 + 42 枚 3D 立体向心棱晶。 */
   private void initInfallStructures() {
      this.infallInitialized = true;
      this.infallStreams.clear();
      this.collapseShards.clear();

      int streamCount = Math.max(14, Math.round(24 * Mth.clamp(this.visualScale, 0.7F, 1.3F)));
      for (int i = 0; i < streamCount; i++) {
         float azimuth = FxMath.TAU * i / streamCount + (this.random.nextFloat() - 0.5F) * 0.25F;
         float elevation = -0.18F + this.random.nextFloat() * 0.92F;
         float startR = this.coreMax * (3.2F + this.random.nextFloat() * 3.8F);
         float twist = (1.6F + this.random.nextFloat() * 1.4F) * (i % 2 == 0 ? 1.0F : -1.0F);
         float width = Math.max(0.18F, this.coreMax * (0.045F + this.random.nextFloat() * 0.045F));
         float[] color = i % 4 == 0 ? WHITE : (i % 3 == 0 ? VIOLET : CYAN);
         this.infallStreams.add(new InfallStream(azimuth, elevation, startR, twist, width, color));
      }

      int shardCount = Math.max(24, Math.round(42 * Mth.clamp(this.visualScale, 0.7F, 1.3F)));
      for (int i = 0; i < shardCount; i++) {
         float azimuth = this.random.nextFloat() * FxMath.TAU;
         float elevation = -0.22F + this.random.nextFloat() * 1.05F;
         float orbitR = this.coreMax * (2.2F + this.random.nextFloat() * 3.4F);
         float orbitSpeed = (0.28F + this.random.nextFloat() * 0.34F) * (i % 2 == 0 ? 1.0F : -1.0F);
         float size = Math.max(0.32F, this.coreMax * (0.08F + this.random.nextFloat() * 0.10F));
         float[] color = i % 5 == 0 ? GOLD : (i % 2 == 0 ? CYAN : VIOLET);
         this.collapseShards.add(new CollapseShard(azimuth, elevation, orbitR, orbitSpeed, size, this.random.nextFloat() * FxMath.TAU, color));
      }
   }

   /** 触发第 134 tick 地面超新星真空坍缩大爆炸：叠层音效 + 54 枚 3D 棱晶碎骸 + 280+ 星屑流。 */
   private void triggerSupernovaDetonation(ClientLevel level) {
      double x = this.impactSingularity.x;
      double y = this.impactSingularity.y;
      double z = this.impactSingularity.z;
      level.playLocalSound(x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 4.6F, 0.36F, false);
      level.playLocalSound(x, y, z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 4.2F, 0.42F, false);
      level.playLocalSound(x, y, z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 4.5F, 0.46F, false);
      level.playLocalSound(x, y, z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 2.8F, 0.58F, false);

      float scale = Mth.clamp(this.visualScale, 0.65F, 1.5F);
      float burst = Math.max(0.7F, (this.half / 64.0F) * scale);

      // 54 枚 3D 立体黑曜棱晶碎骸 360° 狂暴抛射
      int shardCount = Math.max(30, Math.round(54 * scale));
      for (int i = 0; i < shardCount; i++) {
         Vector3f dir = FxMath.randomUnit(this.random);
         // 偏向上半球与水平抛射，避免一出生就钻地消失
         dir.y = Math.abs(dir.y) * 0.85F + 0.08F;
         dir.normalize();
         float speed = (1.35F + this.random.nextFloat() * 2.55F) * burst;
         float size = Math.max(0.45F, this.coreMax * (0.12F + this.random.nextFloat() * 0.16F));
         float[] color = i % 6 == 0 ? WHITE : (i % 4 == 0 ? GOLD : (i % 2 == 0 ? CYAN : VIOLET));
         this.blastShards.add(new BlastShard(
            this.impactSingularity,
            dir.x * speed, dir.y * speed, dir.z * speed,
            size,
            26 + this.random.nextInt(18),
            this.random,
            color
         ));
      }

      // 280+ 高速放射星屑流
      int emberCount = Math.max(120, Math.round(280 * scale));
      for (int i = 0; i < emberCount; i++) {
         float[] color = BOLT_COLORS[i % BOLT_COLORS.length];
         Vector3f dir = FxMath.randomUnit(this.random);
         if (dir.y < -0.15F) {
            dir.y = Math.abs(dir.y) * 0.7F;
            dir.normalize();
         }
         float speed = (2.2F + this.random.nextFloat() * 4.2F) * burst;
         this.embers.add(new Ember(this.impactSingularity, dir.x * speed, dir.y * speed, dir.z * speed, 24 + this.random.nextInt(24), color, null));
      }
   }

   private FxLightningBolt spawnBolt(ClientLevel level) {
      double angle = this.random.nextDouble() * Math.PI * 2.0;
      double dist = Math.sqrt(this.random.nextDouble()) * this.half;
      double x = this.center.x + Math.cos(angle) * dist;
      double z = this.center.z + Math.sin(angle) * dist;
      return this.spawnBoltAt(level, x, z, BOLT_COLORS[this.random.nextInt(BOLT_COLORS.length)], 1 + this.random.nextInt(3));
   }

   private FxLightningBolt spawnBoltAt(ClientLevel level, double x, double z, float[] color, int flashes) {
      int bx = (int) Math.floor(x);
      int bz = (int) Math.floor(z);
      if (!level.hasChunk(bx >> 4, bz >> 4)) {
         return null;
      }
      double ground;
      double top;
      if (this.enclosedSpace) {
         double probeStartY = Math.min(this.ceilingY, this.center.y + 18.0);
         double probeEndY = Math.max(level.getMinBuildHeight() + 1.0, this.groundY - 12.0);
         BlockHitResult floorHit = level.clip(new ClipContext(
            new Vec3(x, probeStartY, z), new Vec3(x, probeEndY, z),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null
         ));
         ground = floorHit.getType() == HitResult.Type.BLOCK ? floorHit.getLocation().y : this.groundY;
         top = Math.min(this.ceilingY, Math.max(ground + 12.0, this.zenithSingularity.y + 4.0));
      } else {
         int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
         ground = height <= level.getMinBuildHeight() ? this.groundY : height;
         top = Math.min(level.getMaxBuildHeight(), ground + 68.0 + this.random.nextDouble() * 36.0);
      }
      return new FxLightningBolt(x, z, top, ground, this.random.nextLong(), flashes, color);
   }

   @Override
   public void tickFallback(ClientLevel level) {
      int age = this.ageTicks();
      Vec3 cur = this.singularityPos(age);
      if (age == 0) {
         level.addParticle(ParticleTypes.FLASH, this.center.x, this.center.y + 1.0, this.center.z, 0.0, 0.0, 0.0);
         for (int i = 0; i < 60; i++) {
            Vector3f dir = FxMath.randomUnit(this.random).mul(1.2F);
            level.addParticle(ParticleTypes.REVERSE_PORTAL, this.center.x, this.center.y + 1.0, this.center.z, dir.x, dir.y, dir.z);
         }
      }
      if (age >= this.plungeStartAge && age < this.reckoningAge && age % 2 == 0) {
         level.addParticle(ParticleTypes.PORTAL, cur.x, cur.y, cur.z, 0.0, -0.35, 0.0);
         level.addParticle(ParticleTypes.END_ROD, cur.x, cur.y, cur.z, 0.0, 0.1, 0.0);
      }
      if (age == this.plungeImpactAge || age == this.reckoningAge) {
         level.addParticle(ParticleTypes.SONIC_BOOM, this.impactSingularity.x, this.impactSingularity.y, this.impactSingularity.z, 0.0, 0.0, 0.0);
         level.addParticle(ParticleTypes.FLASH, this.impactSingularity.x, this.impactSingularity.y, this.impactSingularity.z, 0.0, 0.0, 0.0);
      }
   }

   // ───────────────────────────── 预乘 Alpha 本体/暗部实体层 ─────────────────────────────

   @Override
   public void renderBody(FxGeometry body, FxFrame frame) {
      float a = this.age(frame.partialTick);
      float plungeAct = this.plungeActive(a);
      float ov = this.overload(a);
      float imp = this.implosion(a);
      float sil = this.silence(a);
      float after = this.aftermath(a);
      Vec3 curPos = this.singularityPos(a);
      Vector3f s = frame.rel(curPos);
      float coreR = this.coreRadius(a);

      // 1. 黑洞本体 3D 实心深渊日蚀球壳（压出深邃玄黑体积感，白昼与光影下同样漆黑摄人）
      if (coreR > 0.05F) {
         float waveAmp = 0.02F + plungeAct * 0.03F + ov * 0.095F * (1.0F - imp * 0.75F);
         float waveFreq = 2.4F + ov * 2.2F;
         float bodyAlpha = 0.96F * (1.0F - sil * 0.15F);
         body.geoCenter(s);
         body.begin(Style.CORONA, 0.85F, 0.35F, 2.2F, 1.6F, 0.7F + ov * 1.4F, 0.25F + ov * 0.75F);
         body.deformedSphere(s, coreR * 0.98F, 16, 24, a * 0.04F, waveAmp, waveFreq, a * 0.28F, ABYSS[0], ABYSS[1], ABYSS[2], bodyAlpha);
         body.end();
         body.clearGeoCenter();
      }

      // 2. 黑洞坠落暗焰再入火衣（包裹下坠迎风锥壳，赋予天体压境重量感）
      if (plungeAct > 0.01F && a < this.plungeImpactAge + 1.5F) {
         Vector3f nose = frame.rel(curPos.subtract(0.0, coreR * 1.25, 0.0));
         Vector3f tail = frame.rel(curPos.add(0.0, coreR * 3.6, 0.0));
         body.begin(Style.REENTRY, 0.92F, 0.5F, 1.3F, 2.0F, 2.6F, 0.45F);
         body.cylinder(nose, tail, coreR * 0.45F, coreR * 2.25F, 22, a * 0.09F, 0.14F, 0.05F, 0.28F, 0.82F * plungeAct);
         body.end();
      }

      // 3. 砸地瞬间贴地深渊气浪暗裙
      float impShock = this.impactShock(a);
      if (impShock > 0.0F && impShock < 1.0F) {
         float env = (1.0F - impShock) * (1.0F - impShock);
         float baseR = this.coreMax * 0.9F + this.half * 0.72F * FxMath.easeOutCubic(impShock);
         Vector3f gBase = frame.rel(new Vec3(this.center.x, this.groundY + 0.18, this.center.z));
         Vector3f gTop = frame.rel(new Vec3(this.center.x, this.groundY + 2.8 + this.coreMax * 0.85 * (1.0F - impShock * 0.4F), this.center.z));
         body.begin(Style.SHOCK_CONE, 0.9F, 0.4F, impShock, 2.4F, 1.0F, 0.45F);
         body.cylinder(gBase, gTop, baseR * 0.28F, baseR, 26, a * 0.05F, 0.11F, 0.06F, 0.24F, 0.75F * env);
         body.end();
      }

      // 4. 接地过载 & 内爆向心棱晶黑曜实体核
      if (!this.collapseShards.isEmpty() && a >= this.plungeImpactAge && a < this.silenceStartAge) {
         this.drawCollapseShards(body, frame, a, true);
      }

      // 5. 终幕超新星大爆炸：暗核裂壳 + 冲天暗烟柱 + 贴地暗烟气浪裙 + 爆射黑曜碎骸
      if (after > 0.0F && after < 1.0F) {
         Vector3f blastCenter = frame.rel(this.impactSingularity);
         float tShell = FxMath.window(a, this.reckoningAge, this.reckoningAge + 16.0F);
         if (tShell < 1.0F) {
            float shellR = this.coreMax * 0.4F + this.half * 0.78F * FxMath.easeOutCubic(tShell);
            float shellA = (1.0F - tShell) * (1.0F - tShell) * 0.78F;
            body.geoCenter(blastCenter);
            body.begin(Style.CORONA, 0.85F, 0.6F, 1.8F, 1.8F, 1.6F, 0.9F);
            body.deformedSphere(blastCenter, shellR, 14, 22, a * 0.07F, 0.085F * (1.0F - tShell * 0.5F), 3.0F, a * 0.35F, 0.10F, 0.04F, 0.22F, shellA);
            body.end();
            body.clearGeoCenter();
         }

         float tPillar = FxMath.window(a, this.reckoningAge, this.reckoningAge + 30.0F);
         if (tPillar < 1.0F) {
            double colTopY = this.enclosedSpace
               ? Math.max(this.impactSingularity.y + 8.0, this.ceilingY)
               : Math.min(this.impactSingularity.y + 135.0, this.ceilingY + 48.0);
            float colR = Math.max(2.6F, this.coreMax * (0.95F + 0.65F * FxMath.easeOutCubic(Math.min(1.0F, tPillar * 3.0F)))) * (1.0F - tPillar * 0.55F);
            Vector3f pBase = frame.rel(new Vec3(this.center.x, this.groundY - 0.5, this.center.z));
            Vector3f pTop = frame.rel(new Vec3(this.center.x, colTopY, this.center.z));
            body.begin(Style.PLASMA_BEAM, 0.9F, 0.6F, 2.0F, -2.4F, tPillar, Math.min(1.0F, tPillar * 2.2F));
            body.cylinder(pBase, pTop, colR * 1.15F, colR * 0.85F, 20, -a * 0.06F, 0.10F, 0.06F, 0.24F, 0.78F * (1.0F - tPillar));
            body.end();
         }

         float tCone = FxMath.window(a, this.reckoningAge, this.reckoningAge + 28.0F);
         if (tCone < 1.0F) {
            float skirtR = this.coreMax + this.half * 1.48F * FxMath.easeOutCubic(tCone);
            Vector3f cBase = frame.rel(new Vec3(this.center.x, this.groundY + 0.2, this.center.z));
            Vector3f cTop = frame.rel(new Vec3(this.center.x, this.groundY + 5.5 + this.coreMax * 1.4 * (1.0F - tCone * 0.5F), this.center.z));
            body.begin(Style.SHOCK_CONE, 0.9F, 0.5F, tCone, 2.8F, 1.1F, 0.65F);
            body.cylinder(cBase, cTop, skirtR * 0.22F, skirtR, 28, -a * 0.05F, 0.09F, 0.05F, 0.22F, 0.82F * (1.0F - tCone));
            body.end();
         }

         if (!this.blastShards.isEmpty()) {
            this.drawBlastShards(body, frame, true);
         }
      }
   }

   // ───────────────────────────── HDR 发光几何层 ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      float a = this.age(frame.partialTick);
      float open = this.open(a);
      float rise = this.rise(a);
      float plungeP = this.plungeProgress(a);
      float plungeAct = this.plungeActive(a);
      float impShock = this.impactShock(a);
      float ov = this.overload(a);
      float imp = this.implosion(a);
      float silence = this.silence(a);
      float after = this.aftermath(a);
      float alpha = this.zoneAlpha(a);

      double bottom = this.groundY - 2.0;
      double top = Math.min(this.ceilingY, this.center.y + this.above);
      if (top < bottom + 4.0) {
         top = bottom + 4.0;
      }

      Vec3 curPos = this.singularityPos(a);
      Vector3f s = frame.rel(curPos);
      Vector3f groundCenter = frame.rel(new Vec3(this.center.x, this.groundY + 0.1, this.center.z));
      float coreR = this.coreRadius(a);

      // 1. 真空泡成核
      if (a < 10.0F) {
         float t = a / 10.0F;
         g.begin(Style.GLOW, 12.0F, 0.6F, 6.0F, 2.4F, 1.6F, 0.0F);
         g.billboard(frame.rel(this.center.add(0.0, 1.0, 0.0)), 18.0F * this.visualScale * (0.3F + 0.7F * FxMath.easeOutCubic(Math.min(1.0F, a / 2.0F))), 0.0F, WHITE[0], WHITE[1], WHITE[2], (1.0F - t) * (1.0F - t));
         g.end();
      }
      if (open < 1.0F) {
         float r = this.bubbleMax * open;
         g.begin(Style.RING, 5.0F, 0.0F, 0.95F, 0.03F, 0.0F, 0.0F);
         g.quad(groundCenter, new Vector3f(r, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r), CYAN[0], CYAN[1], CYAN[2], 1.0F - open);
         g.end();
      }

      // 2. 四面极光帷幕 + 顶部边缘能量线 + 四角湍流紫光柱（死寂呼吸拍压暗以聚焦地面针尖）
      float stress = Mth.clamp(plungeAct * 0.45F + ov * 0.75F + imp * 0.25F, 0.0F, 1.0F);
      float curtainAlpha = alpha * (1.0F - 0.78F * silence);
      if (rise > 0.0F && curtainAlpha > 0.01F) {
         float midY = (float) ((bottom + top) * 0.5);
         float halfH = (float) ((top - bottom) * 0.5);
         Vector3f vertical = new Vector3f(0.0F, halfH, 0.0F);
         Vector3f alongX = new Vector3f(this.half, 0.0F, 0.0F);
         Vector3f alongZ = new Vector3f(0.0F, 0.0F, this.half);
         Vec3[] wallCenters = {
            new Vec3(this.center.x, midY, this.center.z - this.half),
            new Vec3(this.center.x, midY, this.center.z + this.half),
            new Vec3(this.center.x - this.half, midY, this.center.z),
            new Vec3(this.center.x + this.half, midY, this.center.z)
         };
         for (int i = 0; i < 4; i++) {
            g.begin(Style.CURTAIN, 2.2F + 2.2F * stress, 1.0F, this.seedFloat + i * 13.0F, 1.0F, rise, stress * 0.65F);
            g.quad(frame.rel(wallCenters[i]), i < 2 ? alongX : alongZ, vertical, CYAN[0], CYAN[1], CYAN[2], curtainAlpha);
         }
         g.end();

         double edgeY = bottom + (top - bottom) * rise;
         Vec3[] corners = {
            new Vec3(this.center.x - this.half, edgeY, this.center.z - this.half),
            new Vec3(this.center.x + this.half, edgeY, this.center.z - this.half),
            new Vec3(this.center.x + this.half, edgeY, this.center.z + this.half),
            new Vec3(this.center.x - this.half, edgeY, this.center.z + this.half)
         };
         g.begin(Style.BEAM, 4.0F, 0.3F, 24.0F, 1.5F, 0.6F, 0.6F);
         for (int i = 0; i < 4; i++) {
            List<Vector3f> edge = segment(frame, corners[i], corners[(i + 1) % 4], 12);
            g.ribbon(edge, constant(edge.size(), 0.45F), constant(edge.size(), curtainAlpha), CYAN[0], CYAN[1], CYAN[2]);
         }
         g.end();

         double pillarTop = Math.min(this.ceilingY, bottom + (top + 14.0 - bottom) * rise);
         g.begin(Style.BEAM, 5.0F, 0.6F, 8.0F, 2.0F, 1.0F, 0.6F);
         for (int i = 0; i < 4; i++) {
            Vec3 base = new Vec3(corners[i].x, bottom, corners[i].z);
            List<Vector3f> pillar = segment(frame, base, new Vec3(base.x, pillarTop, base.z), 10);
            float[] widths = new float[pillar.size()];
            for (int k = 0; k < widths.length; k++) {
               widths[k] = FxMath.lerp(1.7F, 0.5F, (float) k / (widths.length - 1)) * (1.0F + 0.5F * stress);
            }
            g.ribbon(pillar, widths, constant(pillar.size(), curtainAlpha), VIOLET[0], VIOLET[1], VIOLET[2]);
         }
         g.end();
      }

      // 3. 动态黑洞本体日冕、吸积盘、双倾斜 3D 轨道星环与相对论喷流
      if (coreR > 0.05F) {
         float grow = FxMath.easeOutCubic(FxMath.window(a, 6.0F, 26.0F));
         float hotMix = Mth.clamp(plungeAct * 0.4F + ov * 0.75F + imp * 0.9F, 0.0F, 1.0F);

         // 3a. 黑洞外层 360° 沸腾日冕球壳（不开启遮挡球，防止自身正面被误裁）
         float coronaAlpha = alpha * (1.0F - silence * 0.72F);
         if (coronaAlpha > 0.01F) {
            float waveAmp = 0.025F + plungeAct * 0.035F + ov * 0.11F * (1.0F - imp * 0.7F);
            float rCol = FxMath.lerp(VIOLET[0], CYAN[0], hotMix * 0.7F);
            float gCol = FxMath.lerp(VIOLET[1], CYAN[1], hotMix * 0.85F);
            float bCol = FxMath.lerp(VIOLET[2], WHITE[2], hotMix * 0.65F);
            g.geoCenter(s);
            g.begin(Style.CORONA, 4.2F + 5.5F * hotMix, 0.4F, 2.3F - ov * 0.55F, 1.5F + ov * 1.1F, 0.85F + ov * 1.6F, 0.35F + ov * 0.95F);
            g.deformedSphere(s, coreR * 1.05F, 16, 24, a * 0.045F, waveAmp, 2.6F + ov * 2.0F, a * 0.32F, rCol, gCol, bCol, coronaAlpha);
            g.end();
            g.clearGeoCenter();
         }

         // 以下盘/环/喷流开启视界遮挡球，保证穿到黑洞背后的半段产生真实 3D 空间前后遮挡
         g.occluder(s, coreR * 0.96F);

         // 3b. 深空引力外晕
         g.begin(Style.GLOW, 2.2F + 4.5F * hotMix, 0.8F, 2.6F, 1.5F, 0.5F + imp * 1.2F, 0.0F);
         float haloSize = this.half * (0.58F + 0.25F * ov) * (0.4F + 0.6F * grow) * (1.0F - 0.78F * imp) * (1.0F - 0.6F * silence);
         g.billboard(s, Math.max(coreR * 2.2F, haloSize), a * 0.03F, VIOLET[0], VIOLET[1], VIOLET[2], 0.72F * alpha);
         g.end();

         // 3c. 吸积盘（坠落时略向上拖拽拉起，过载期收窄炽化，内爆期急剧暴缩归零）
         float diskFade = FxMath.window(a, 8.0F, 26.0F) * alpha * (1.0F - imp * 0.95F) * (1.0F - silence);
         if (diskFade > 0.01F) {
            float diskR = this.diskRadius * (0.55F + 0.45F * grow) * (1.0F - plungeAct * 0.22F) * (1.0F - ov * 0.28F) * (1.0F - imp * 0.92F);
            if (diskR > coreR * 1.08F) {
               Vec3 diskPos = curPos.add(0.0, plungeAct * coreR * 0.85, 0.0);
               float tilt = 0.28F + plungeAct * 0.22F + ov * 0.14F * (float) Math.sin(a * 0.45F);
               float spin = a * (0.004F + ov * 0.012F);
               Vector3f u = new Vector3f((float) Math.cos(spin) * diskR, 0.0F, (float) Math.sin(spin) * diskR).rotateX(tilt);
               Vector3f v = new Vector3f(-(float) Math.sin(spin) * diskR, 0.0F, (float) Math.cos(spin) * diskR).rotateX(tilt);
               g.begin(Style.DISK, 3.8F + 4.2F * hotMix, 0.5F, 0.28F, 1.5F + ov * 2.2F, this.seedFloat, 0.68F);
               g.quad(frame.rel(diskPos), u, v, CYAN[0], CYAN[1], CYAN[2], diskFade);
               g.end();
            }
         }

         // 3d. 双倾斜 3D 立体轨道星环（坠落前夕剧烈偏摆；过载/内爆期演化为 3 道同心高速坍缩锁环暴缩归零！）
         float ringAlpha = FxMath.window(a, 12.0F, 28.0F) * alpha * (1.0F - silence);
         if (ringAlpha > 0.01F) {
            float shrink = 1.0F - imp * 0.96F;
            float ringBaseR = Math.max(coreR * 1.25F, this.coreMax * (2.35F - plungeAct * 0.35F + ov * 0.45F) * shrink);
            float tubeR = Math.max(0.08F, this.coreMax * 0.055F * (0.6F + 0.4F * shrink));
            float preWobble = FxMath.window(a, this.plungeStartAge - 10.0F, this.plungeStartAge) * 0.35F;
            float tiltA = 0.48F + preWobble * (float) Math.sin(a * 0.7F) + ov * 0.55F;
            float tiltB = -0.62F + preWobble * (float) Math.cos(a * 0.6F) - ov * 0.48F;
            Vector3f normA = new Vector3f((float) Math.sin(tiltA), (float) Math.cos(tiltA), 0.25F).normalize();
            Vector3f normB = new Vector3f(-0.35F, (float) Math.cos(tiltB), (float) Math.sin(tiltB)).normalize();

            g.begin(Style.RIBBON, 5.2F + 6.5F * hotMix, 0.3F, 4.5F, 6.0F, 10.0F, 0.65F);
            g.torus(s, normA, ringBaseR, tubeR, 32, 6, a * (0.08F + ov * 0.22F), CYAN[0], CYAN[1], CYAN[2], ringAlpha * 0.85F);
            g.torus(s, normB, ringBaseR * 1.32F, tubeR * 0.85F, 32, 6, -a * (0.06F + ov * 0.18F), VIOLET[0], VIOLET[1], VIOLET[2], ringAlpha * 0.75F);
            // 过载与内爆期激活第三重赤道白热锁环，高速暴缩锁死视界
            if (ov > 0.05F) {
               float lockR = Math.max(coreR * 1.12F, this.coreMax * (3.6F - ov * 1.1F) * shrink);
               Vector3f normC = new Vector3f((float) Math.sin(a * 0.22F) * 0.4F, 1.0F, (float) Math.cos(a * 0.22F) * 0.4F).normalize();
               g.torus(s, normC, lockR, tubeR * 1.15F, 36, 6, a * 0.28F, WHITE[0], WHITE[1], WHITE[2], ov * ringAlpha);
            }
            g.end();
         }

         // 3e. 相对论双极喷流（天穹悬停阶段贯通天地；黑洞起坠后自然退场交棒给再入燃烧锥与尾焰柱）
         float jet = FxMath.easeOutCubic(FxMath.window(a, 10.0F, 28.0F)) * alpha * (1.0F - FxMath.window(a, this.plungeStartAge, this.plungeStartAge + 6.0F));
         if (jet > 0.01F) {
            double upLength = Math.max(6.0, Math.min(150.0, this.ceilingY - curPos.y - coreR));
            float jetWidth = coreR * 0.55F;
            List<Vector3f> up = segment(frame, curPos.add(0.0, coreR * 0.8, 0.0), curPos.add(0.0, coreR * 0.8 + upLength * jet, 0.0), 16);
            List<Vector3f> down = segment(frame, curPos.subtract(0.0, coreR * 0.8, 0.0), new Vec3(curPos.x, this.groundY, curPos.z), 10);
            g.begin(Style.BEAM, 5.2F, 0.8F, 14.0F, 3.0F, 1.0F, 0.5F);
            g.ribbon(up, taper(up.size(), jetWidth, jetWidth * 0.25F), constant(up.size(), jet), CYAN[0], CYAN[1], CYAN[2]);
            g.ribbon(down, taper(down.size(), jetWidth * 0.8F, jetWidth * 0.45F), constant(down.size(), jet * 0.8F), CYAN[0], CYAN[1], CYAN[2]);
            g.end();
         }

         g.clearOccluder();
      }

      // 4. 阶段 A4 专属：天穹黑洞脱轨坠落（3D 大气再入燃烧激波锥 + 尾焰等离子柱 + 地表落点锁定法阵）
      if (plungeAct > 0.01F && a < this.plungeImpactAge + 2.0F) {
         // 4a. 双层 3D 大气再入燃烧激波锥（内层白热冰青迎风鼻锥 + 外层电紫高能火舌）
         Vector3f nose = frame.rel(curPos.subtract(0.0, coreR * 1.32, 0.0));
         Vector3f tail = frame.rel(curPos.add(0.0, coreR * 3.85, 0.0));
         g.begin(Style.REENTRY, 7.5F + 4.0F * plungeP, 0.4F, 1.45F, 2.0F, 3.2F, 0.5F + 0.4F * (float) Math.sin(a * 1.4F));
         g.cylinder(nose, tail, coreR * 0.35F, coreR * 2.15F, 24, a * 0.12F, CYAN[0], CYAN[1], CYAN[2], 0.92F * plungeAct);
         g.end();

         Vector3f outerNose = frame.rel(curPos.subtract(0.0, coreR * 1.12, 0.0));
         Vector3f outerTail = frame.rel(curPos.add(0.0, coreR * 5.2, 0.0));
         g.begin(Style.REENTRY, 4.8F + 2.8F * plungeP, 0.5F, 1.05F, 3.0F, 2.4F, 0.25F);
         g.cylinder(outerNose, outerTail, coreR * 0.62F, coreR * 2.85F, 22, -a * 0.09F, VIOLET[0], VIOLET[1], VIOLET[2], 0.72F * plungeAct);
         g.end();

         // 4b. 尾迹高能等离子拖曳柱（从黑洞顶面连向天穹高点，拉出强烈的下坠速度感）
         double trailTopY = Math.min(this.zenithSingularity.y + coreR * 1.5, curPos.y + Math.max(coreR * 4.5, (this.zenithSingularity.y - curPos.y) * 0.85));
         if (trailTopY > curPos.y + coreR * 1.5) {
            g.begin(Style.PLASMA_BEAM, 4.6F + 3.0F * plungeP, 0.5F, 2.0F, 2.8F, 0.15F, 0.18F);
            g.cylinder(frame.rel(curPos.add(0.0, coreR * 0.85, 0.0)), frame.rel(new Vec3(curPos.x, trailTopY, curPos.z)), coreR * 0.95F, coreR * 0.28F, 16, a * 0.08F, CYAN[0], CYAN[1], CYAN[2], 0.65F * plungeAct);
            g.end();
         }

         // 4c. 地面落点星穹锁定法阵 + 急速收缩的撞击预警环
         float runeR = Math.max(5.0F, this.coreMax * FxMath.lerp(4.2F, 2.5F, plungeP));
         g.begin(Style.RUNE, 4.5F + 5.5F * plungeP, 0.0F, a * 0.065F, this.seedFloat, 1.0F, Math.min(1.0F, plungeP * 2.2F));
         g.quad(groundCenter, new Vector3f(runeR, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, runeR), CYAN[0], CYAN[1], CYAN[2], 0.88F * plungeAct);
         g.end();

         float lockRingR = Math.max(2.0F, this.half * 0.62F * (1.0F - FxMath.easeInCubic(plungeP) * 0.78F));
         g.begin(Style.RING, 6.5F + 4.0F * plungeP, 0.0F, 0.92F, 0.045F, 12.0F, 1.8F);
         g.quad(groundCenter, new Vector3f(lockRingR, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, lockRingR), WHITE[0], WHITE[1], WHITE[2], 0.92F * plungeAct);
         g.end();
      }

      // 5. 阶段 A5a 起点：黑洞坠地砸击瞬间（地表 3D 马赫气浪锥裙 + 双重激波环）
      if (impShock > 0.0F && impShock < 1.0F) {
         float fade = (1.0F - impShock) * (1.0F - impShock);
         float waveR = this.coreMax * 0.9F + this.half * 0.82F * FxMath.easeOutCubic(impShock);
         Vector3f gBase = frame.rel(new Vec3(this.center.x, this.groundY + 0.16, this.center.z));
         Vector3f gTop = frame.rel(new Vec3(this.center.x, this.groundY + 3.0 + this.coreMax * 0.95 * (1.0F - impShock * 0.35F), this.center.z));
         g.begin(Style.SHOCK_CONE, 8.5F * fade, 0.4F, impShock, 2.8F, 1.05F, 0.75F);
         g.cylinder(gBase, gTop, waveR * 0.26F, waveR, 28, a * 0.08F, CYAN[0], CYAN[1], CYAN[2], fade);
         g.end();

         g.begin(Style.RING, 9.0F * fade, 0.0F, 0.93F, 0.055F, 0.0F, 0.0F);
         g.quad(groundCenter, new Vector3f(waveR * 1.08F, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, waveR * 1.08F), CYAN[0], CYAN[1], CYAN[2], fade);
         g.quad(groundCenter, new Vector3f(waveR * 0.65F, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, waveR * 0.65F), WHITE[0], WHITE[1], WHITE[2], fade * 0.85F);
         g.end();
      }

      // 6. 阶段 A5a~A5b：接地过载暴涨 + 极速内爆坍缩（360° 螺旋向心倒灌流丝 + 逆向收缩波 + 3D 棱晶吸入）
      if (a >= this.plungeImpactAge && a < this.silenceStartAge) {
         float collapseStage = FxMath.window(a, this.plungeImpactAge, this.silenceStartAge);
         g.occluder(s, coreR * 0.95F);

         // 6a. 24 条 3D 螺旋向心倒灌等离子流丝（随内爆进度越吸越紧、色温越炽白）
         if (!this.infallStreams.isEmpty()) {
            g.begin(Style.BEAM, 6.2F + 7.5F * imp, 0.35F, 10.0F, 4.5F + 4.0F * imp, 1.15F, 0.7F);
            for (InfallStream stream : this.infallStreams) {
               this.drawInfallStream(g, frame, stream, collapseStage, imp);
            }
            g.end();
         }

         // 6b. 42 枚 3D 棱晶碎片螺旋倒吸
         if (!this.collapseShards.isEmpty()) {
            this.drawCollapseShards(g, frame, a, false);
         }

         g.clearOccluder();

         // 6c. 3 道逆向向心收缩地表波纹（从结界外围拼命倒吸向地面爆心）
         g.begin(Style.RING, 6.8F + 5.0F * imp, 0.0F, 0.94F, 0.042F, 0.0F, 0.0F);
         for (int k = 0; k < 3; k++) {
            float cycle = (collapseStage * 2.4F + k * 0.333F) % 1.0F;
            float r = Math.max(coreR * 1.1F, this.half * 0.78F * (1.0F - FxMath.easeInCubic(cycle)));
            float ringA = (float) Math.sin(cycle * Math.PI) * (0.75F + 0.25F * imp);
            float[] col = k == 0 ? WHITE : (k == 1 ? CYAN : VIOLET);
            g.quad(groundCenter, new Vector3f(r, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r), col[0], col[1], col[2], ringA);
         }
         g.end();

         // 6d. 内爆急缩白热核心辉光（在 124..129t 把视觉压强拧到临界点）
         if (imp > 0.01F) {
            g.begin(Style.GLOW, 8.0F + 14.0F * imp, 0.7F, 6.5F, 2.2F, 1.5F + 2.2F * imp, 0.0F);
            float size = FxMath.lerp(this.coreMax * 4.5F, this.coreMax * 1.35F, imp);
            g.billboard(s, size, 0.0F, WHITE[0], WHITE[1], WHITE[2], 0.55F + 0.45F * imp);
            g.end();
         }
      }

      // 7. 阶段 A5c：5 tick 绝对死寂呼吸拍（针尖白热奇点 + 宽银幕横向冰青星芒 + 临界细环）
      if (silence > 0.0F) {
         boolean anamorphic = ModConfig.CLIENT.stellarisEngine.anamorphicStreaks.getValue();
         float pinpoint = FxMath.lerp(this.coreMax * 1.65F, this.coreMax * 0.62F, silence);
         float streakPower = anamorphic ? (3.8F + 2.2F * silence) : 0.8F;
         g.begin(Style.GLOW, 20.0F + 10.0F * silence, 0.8F, 9.5F, 3.2F, streakPower, 0.0F);
         g.billboard(s, pinpoint, 0.0F, WHITE[0], WHITE[1], WHITE[2], 0.92F + 0.08F * silence);
         g.end();

         // 若开启变形镜头星芒，再叠一道超宽极细冷青水平星线，打造剧场版「世界停跳」一瞬
         if (anamorphic) {
            float wideHalf = Math.max(10.0F, this.half * (0.28F + 0.14F * silence));
            g.begin(Style.GLOW, 11.0F + 6.0F * silence, 0.5F, 14.0F, 5.0F, 6.5F, 0.0F);
            g.billboard(s, wideHalf, 0.0F, CYAN[0], CYAN[1], CYAN[2], 0.52F + 0.28F * silence);
            g.end();
         }

         // 细如发丝的临界收缩环，预示下一瞬彻底引爆
         float hairR = Math.max(0.8F, this.coreMax * FxMath.lerp(2.2F, 0.55F, silence));
         g.begin(Style.RING, 9.0F, 0.0F, 0.96F, 0.018F, 0.0F, 0.0F);
         g.quad(s, new Vector3f(hairR, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, hairR), CYAN[0], CYAN[1], CYAN[2], 0.72F * (1.0F - silence * 0.35F));
         g.end();
      }

      // 8. 阶段 A6：多层超新星真空坍缩大爆炸（134..172t，全部自地面 impactSingularity 轰然引爆！）
      if (after > 0.0F && after < 1.0F) {
         Vector3f blastCenter = frame.rel(this.impactSingularity);
         boolean anamorphic = ModConfig.CLIENT.stellarisEngine.anamorphicStreaks.getValue();

         // 8a. 爆心核爆闪光 + 超宽变形镜头星芒
         float tFlash = FxMath.window(a, this.reckoningAge, this.reckoningAge + 11.0F);
         if (tFlash < 1.0F) {
            float f2 = (1.0F - tFlash) * (1.0F - tFlash);
            g.begin(Style.GLOW, 22.0F, 0.8F, 5.0F, 2.2F, anamorphic ? 4.8F : 1.4F, 0.0F);
            g.billboard(blastCenter, this.half * (0.95F + 1.35F * tFlash), 0.0F, WHITE[0], WHITE[1], WHITE[2], f2);
            g.end();
         }

         // 8b. 双层 3D 沸腾超新星爆裂火球壳（内层白热冰青核 + 外层电紫高能火球）
         float tInnerBall = FxMath.window(a, this.reckoningAge, this.reckoningAge + 22.0F);
         if (tInnerBall < 1.0F) {
            float rIn = this.coreMax * 0.35F + this.half * 0.76F * FxMath.easeOutCubic(tInnerBall);
            float aIn = (1.0F - tInnerBall) * (1.0F - tInnerBall);
            g.geoCenter(blastCenter);
            g.begin(Style.CORONA, 11.5F * aIn, 0.55F, 1.55F, 2.1F, 1.8F, 1.0F);
            g.deformedSphere(blastCenter, rIn, 16, 24, a * 0.08F, 0.095F * (1.0F - tInnerBall * 0.5F), 3.2F, a * 0.42F, CYAN[0], CYAN[1], CYAN[2], aIn);
            g.end();
            g.clearGeoCenter();
         }
         float tOuterBall = FxMath.window(a, this.reckoningAge, this.reckoningAge + 30.0F);
         if (tOuterBall < 1.0F) {
            float rOut = this.coreMax * 0.55F + this.half * 1.15F * FxMath.easeOutCubic(tOuterBall);
            float aOut = (1.0F - tOuterBall) * (1.0F - tOuterBall);
            g.geoCenter(blastCenter);
            g.begin(Style.CORONA, 7.2F * aOut, 0.65F, 2.15F, 1.85F, 1.35F, 0.8F);
            g.deformedSphere(blastCenter, rOut, 16, 24, -a * 0.06F, 0.065F * (1.0F - tOuterBall * 0.4F), 2.5F, -a * 0.3F, VIOLET[0], VIOLET[1], VIOLET[2], aOut * 0.85F);
            g.end();
            g.clearGeoCenter();
         }

         // 8c. 冲天超新星坍缩光柱（3D 双层嵌套等离子圆柱，从爆心直插天穹）
         float tPillar = FxMath.window(a, this.reckoningAge, this.reckoningAge + 32.0F);
         if (tPillar < 1.0F) {
            double colTopY = this.enclosedSpace
               ? Math.max(this.impactSingularity.y + 8.0, this.ceilingY)
               : Math.min(this.impactSingularity.y + 140.0, this.ceilingY + 52.0);
            float growCol = FxMath.easeOutCubic(Math.min(1.0F, tPillar * 3.5F));
            float colFade = (1.0F - tPillar) * (1.0F - tPillar * 0.45F);
            float outerR = Math.max(2.8F, this.coreMax * (1.05F + 0.85F * growCol)) * (1.0F - tPillar * 0.52F);
            float innerR = outerR * 0.48F;
            Vector3f pBase = frame.rel(new Vec3(this.center.x, this.groundY - 0.5, this.center.z));
            Vector3f pTop = frame.rel(new Vec3(this.center.x, colTopY, this.center.z));
            float wavePos = Math.min(1.0F, tPillar * 1.9F);

            // 外层冰青/电紫湍流柱
            g.begin(Style.PLASMA_BEAM, 9.0F * colFade, 0.55F, 2.0F, -2.8F, tPillar, wavePos);
            g.cylinder(pBase, pTop, outerR * 1.25F, outerR * 0.9F, 22, a * 0.08F, CYAN[0], CYAN[1], CYAN[2], colFade);
            g.end();
            // 内层恒星白热核心柱
            g.begin(Style.PLASMA_BEAM, 14.0F * colFade, 0.45F, 3.0F, -3.8F, tPillar, Math.min(1.0F, tPillar * 2.4F));
            g.cylinder(pBase, pTop, innerR * 1.1F, innerR * 0.72F, 16, -a * 0.12F, WHITE[0], WHITE[1], WHITE[2], colFade);
            g.end();
         }

         // 8d. 贴地双层超音速马赫气浪锥裙 + 三重地表冲击波环
         float tCone = FxMath.window(a, this.reckoningAge, this.reckoningAge + 30.0F);
         if (tCone < 1.0F) {
            float cFade = (1.0F - tCone) * (1.0F - tCone);
            float skirtR = this.coreMax + this.half * 1.62F * FxMath.easeOutCubic(tCone);
            Vector3f cBase = frame.rel(new Vec3(this.center.x, this.groundY + 0.18, this.center.z));
            Vector3f cTop = frame.rel(new Vec3(this.center.x, this.groundY + 6.0 + this.coreMax * 1.5 * (1.0F - tCone * 0.45F), this.center.z));
            g.begin(Style.SHOCK_CONE, 11.0F * cFade, 0.45F, tCone, 3.2F, 1.15F, 0.9F);
            g.cylinder(cBase, cTop, skirtR * 0.22F, skirtR, 30, a * 0.09F, CYAN[0], CYAN[1], CYAN[2], cFade);
            g.end();

            // 略高一层电紫次级激波锥
            Vector3f cTop2 = frame.rel(new Vec3(this.center.x, this.groundY + 11.0 + this.coreMax * 2.0 * (1.0F - tCone * 0.4F), this.center.z));
            g.begin(Style.SHOCK_CONE, 6.8F * cFade, 0.55F, Math.min(1.0F, tCone * 1.15F), 2.5F, 1.25F, 0.5F);
            g.cylinder(cBase, cTop2, skirtR * 0.16F, skirtR * 0.78F, 26, -a * 0.07F, VIOLET[0], VIOLET[1], VIOLET[2], cFade * 0.85F);
            g.end();
         }

         // 三重地表冲击波环（外圈冰青 + 中圈白热 + 内圈虚线电紫）
         float rOuter = this.half * 2.05F * FxMath.easeOutCubic(after);
         float rMid = this.half * 1.42F * FxMath.easeOutCubic(after);
         float rInner = this.half * 0.92F * FxMath.easeOutCubic(after);
         float ringFade = 1.0F - after;
         g.begin(Style.RING, 9.5F * ringFade, 0.0F, 0.94F, 0.045F, 0.0F, 0.0F);
         g.quad(groundCenter, new Vector3f(rOuter, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, rOuter), CYAN[0], CYAN[1], CYAN[2], ringFade);
         g.quad(groundCenter, new Vector3f(rMid, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, rMid), WHITE[0], WHITE[1], WHITE[2], ringFade * ringFade);
         g.end();
         g.begin(Style.RING, 7.0F * ringFade, 0.0F, 0.92F, 0.055F, 16.0F, -1.6F);
         g.quad(groundCenter, new Vector3f(rInner, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, rInner), VIOLET[0], VIOLET[1], VIOLET[2], ringFade * 0.9F);
         g.end();

         // 8e. 炸裂赤道双倾斜 3D 立体星环（自爆心急速甩出并渐细消散）
         float tRing3d = FxMath.window(a, this.reckoningAge, this.reckoningAge + 26.0F);
         if (tRing3d < 1.0F) {
            float r3d = this.coreMax * 0.6F + this.half * 1.38F * FxMath.easeOutCubic(tRing3d);
            float tube = Math.max(0.12F, this.coreMax * 0.28F * (1.0F - tRing3d * 0.72F));
            float a3d = (1.0F - tRing3d) * (1.0F - tRing3d);
            Vector3f n1 = new Vector3f(0.28F, 0.94F, 0.18F).normalize();
            Vector3f n2 = new Vector3f(-0.34F, 0.88F, -0.32F).normalize();
            g.begin(Style.RIBBON, 10.5F * a3d, 0.35F, 4.0F, 8.0F, 14.0F, 0.75F);
            g.torus(blastCenter, n1, r3d, tube, 36, 6, a * 0.12F, CYAN[0], CYAN[1], CYAN[2], a3d);
            g.torus(blastCenter, n2, r3d * 0.82F, tube * 0.85F, 34, 6, -a * 0.1F, GOLD[0], GOLD[1], GOLD[2], a3d * 0.8F);
            g.end();
         }

         // 8f. 54 枚 3D 棱晶碎骸 HDR 锋刃与烧蚀溶解
         if (!this.blastShards.isEmpty()) {
            this.drawBlastShards(g, frame, false);
         }
      }

      // 9. 七星落点地表激波环（与服务端 14 波齐射严格同坐标）
      if (!this.starPulses.isEmpty()) {
         g.begin(Style.RING, 7.5F, 0.0F, 0.92F, 0.05F, 0.0F, 0.0F);
         for (StarPulse pulse : this.starPulses) {
            float p = Mth.clamp((pulse.age + frame.partialTick) / pulse.maxAge, 0.0F, 1.0F);
            float r = pulse.maxRadius * FxMath.easeOutCubic(p);
            g.quad(frame.rel(pulse.pos), new Vector3f(r, 0.0F, 0.0F), new Vector3f(0.0F, 0.0F, r), pulse.color[0], pulse.color[1], pulse.color[2], (1.0F - p) * (1.0F - p));
         }
         g.end();
      }

      // 10. 伽马雷电与七星光矢
      if (!this.bolts.isEmpty()) {
         g.begin(Style.RIBBON, 2.5F, 0.4F, 2.0F, 9.0F, 18.0F, 0.9F);
         for (FxLightningBolt bolt : this.bolts) {
            if (bolt.visible()) {
               bolt.draw(g, frame, 4.0F, 0.55F, VIOLET);
            }
         }
         g.begin(Style.RIBBON, 10.0F, 0.4F, 9.0F, 12.0F, 24.0F, 0.2F);
         for (FxLightningBolt bolt : this.bolts) {
            if (bolt.visible()) {
               bolt.draw(g, frame, 1.0F, 1.0F, bolt.color);
            }
         }
         g.begin(Style.GLOW, 6.0F, 0.6F, 5.0F, 2.2F, 1.0F, 0.0F);
         for (FxLightningBolt bolt : this.bolts) {
            if (bolt.visible()) {
               g.billboard(frame.rel(new Vec3(bolt.x, bolt.ground + 0.6, bolt.z)), 3.5F, 0.0F, bolt.color[0], bolt.color[1], bolt.color[2], 1.0F);
            }
         }
         g.end();
      }

      // 11. 高速放射与向心倒灌余烬流
      if (!this.embers.isEmpty()) {
         g.begin(Style.RIBBON, 5.6F, 0.0F, 6.0F, 0.0F, 0.0F, 0.55F);
         for (Ember ember : this.embers) {
            Vec3 p = ember.position(frame.partialTick);
            Vec3 tail = p.subtract(ember.vx * 2.7, ember.vy * 2.7, ember.vz * 2.7);
            g.streak(frame.rel(p), frame.rel(tail), 0.08F, ember.color[0], ember.color[1], ember.color[2], (float) ember.life / ember.maxLife);
         }
         g.end();
      }
   }

   /** 绘制单条 3D 螺旋向心倒灌流丝（从四周空间拧卷吸入地面黑洞）。 */
   private void drawInfallStream(FxGeometry g, FxFrame frame, InfallStream stream, float collapseStage, float imp) {
      int segs = 10;
      float shrink = 1.0F - FxMath.easeInCubic(imp) * 0.94F;
      float outerR = Math.max(0.35F, stream.startRadius * (1.0F - collapseStage * 0.35F) * shrink);
      float innerR = Math.max(0.12F, this.coreMax * 0.45F * shrink);
      List<Vector3f> pts = new ArrayList<>(segs + 1);
      float[] widths = new float[segs + 1];
      float[] alphas = new float[segs + 1];
      float cosEl = (float) Math.cos(stream.elevation);
      float sinEl = (float) Math.sin(stream.elevation);

      for (int i = 0; i <= segs; i++) {
         float t = (float) i / segs;
         float r = FxMath.lerp(outerR, innerR, t * t);
         float ang = stream.azimuth + stream.twist * t + collapseStage * (3.2F + imp * 5.0F);
         double ox = Math.cos(ang) * cosEl * r;
         double oy = Math.max(this.groundY + 0.2 - this.impactSingularity.y, sinEl * r * (1.0 - t * 0.35));
         double oz = Math.sin(ang) * cosEl * r;
         pts.add(frame.rel(this.impactSingularity.add(ox, oy, oz)));
         widths[i] = stream.width * FxMath.lerp(0.28F, 1.15F, (float) Math.sin(t * Math.PI)) * (0.45F + 0.55F * shrink);
         alphas[i] = (float) Math.sin(t * Math.PI) * (0.65F + 0.35F * imp);
      }
      float cr = FxMath.lerp(stream.color[0], WHITE[0], imp * 0.7F);
      float cg = FxMath.lerp(stream.color[1], WHITE[1], imp * 0.7F);
      float cb = FxMath.lerp(stream.color[2], WHITE[2], imp * 0.7F);
      g.ribbon(pts, widths, alphas, cr, cg, cb);
   }

   /** 绘制过载与内爆阶段绕黑洞螺旋坠入的 3D 棱晶碎片（支持 Body 黑曜实体与 HDR 锋刃双层）。 */
   private void drawCollapseShards(FxGeometry g, FxFrame frame, float a, boolean bodyPass) {
      float collapseStage = FxMath.window(a, this.plungeImpactAge, this.silenceStartAge);
      float imp = this.implosion(a);
      float shrink = 1.0F - FxMath.easeInCubic(imp) * 0.96F;
      float alpha = Mth.clamp(collapseStage * 4.5F, 0.0F, 1.0F) * (1.0F - imp * 0.25F);
      if (alpha <= 0.01F || shrink <= 0.02F) {
         return;
      }

      if (bodyPass) {
         g.begin(Style.CRYSTAL_SHARD, 0.92F, 0.2F, 2.2F, 2.4F, imp, 0.0F);
      } else {
         g.begin(Style.CRYSTAL_SHARD, 6.2F + 8.0F * imp, 0.2F, 2.2F, 2.6F, imp, 0.0F);
      }
      Vector3f forward = new Vector3f();
      Vector3f right = new Vector3f();
      Vector3f up = new Vector3f();
      for (CollapseShard shard : this.collapseShards) {
         float r = Math.max(0.25F, shard.orbitRadius * (1.0F - collapseStage * 0.28F) * shrink);
         float ang = shard.azimuth + a * shard.orbitSpeed * (1.0F + imp * 1.8F);
         float cosEl = (float) Math.cos(shard.elevation);
         float sinEl = (float) Math.sin(shard.elevation);
         double ox = Math.cos(ang) * cosEl * r;
         double oy = Math.max(this.groundY + 0.35 - this.impactSingularity.y, sinEl * r);
         double oz = Math.sin(ang) * cosEl * r;
         Vec3 pos = this.impactSingularity.add(ox, oy, oz);

         // 碎刃尖端朝向黑洞中心切向俯冲
         forward.set((float) (-ox), (float) (-oy), (float) (-oz));
         if (forward.lengthSquared() < 1.0E-5F) {
            continue;
         }
         forward.normalize();
         float roll = shard.rollPhase + a * 0.25F;
         Vector3f basePerp = FxMath.perpendicular(forward);
         Vector3f baseUp = new Vector3f(forward).cross(basePerp).normalize();
         float cr = (float) Math.cos(roll);
         float sr = (float) Math.sin(roll);
         right.set(basePerp).mul(cr).fma(sr, baseUp);
         up.set(baseUp).mul(cr).fma(-sr, basePerp);

         float sz = shard.size * (0.45F + 0.55F * shrink);
         float[] col = bodyPass ? OBSIDIAN : shard.color;
         g.bladeShard(frame.rel(pos), forward, right, up, sz * 2.1F, sz * 0.9F, sz * 0.42F, sz * 0.26F, col[0], col[1], col[2], alpha);
      }
      g.end();
   }

   /** 绘制终幕超新星爆炸后 360° 高速抛射并沿晶脉烧蚀溶解的 3D 黑曜棱晶碎骸。 */
   private void drawBlastShards(FxGeometry g, FxFrame frame, boolean bodyPass) {
      Vector3f forward = new Vector3f();
      Vector3f right = new Vector3f();
      Vector3f up = new Vector3f();

      // 按碎骸生命周期溶解度分组直接绘制（统一过载与溶解感）
      for (int bucket = 0; bucket < 2; bucket++) {
         boolean dissolvingBucket = bucket == 1;
         float dissolve = dissolvingBucket ? 0.52F : 0.08F;
         if (bodyPass) {
            g.begin(Style.CRYSTAL_SHARD, 0.92F, 0.25F, 2.0F, 2.2F, 0.45F, dissolve);
         } else {
            g.begin(Style.CRYSTAL_SHARD, dissolvingBucket ? 6.5F : 9.5F, 0.25F, 2.0F, 2.6F, dissolvingBucket ? 0.85F : 0.55F, dissolve);
         }
         for (BlastShard shard : this.blastShards) {
            float life01 = 1.0F - (float) shard.life / shard.maxLife;
            boolean inDissolve = life01 > 0.55F;
            if (inDissolve != dissolvingBucket) {
               continue;
            }
            float fade = 1.0F - FxMath.easeInCubic(FxMath.window(life01, 0.55F, 1.0F));
            if (fade <= 0.02F) {
               continue;
            }
            Vec3 pos = shard.position(frame.partialTick);
            float spin = shard.spinBase + life01 * shard.spinSpeed;
            forward.set((float) shard.vx, (float) shard.vy, (float) shard.vz);
            if (forward.lengthSquared() < 1.0E-5F) {
               forward.set(shard.axis);
            }
            forward.normalize();
            Vector3f basePerp = FxMath.perpendicular(forward);
            Vector3f baseUp = new Vector3f(forward).cross(basePerp).normalize();
            float cs = (float) Math.cos(spin);
            float sn = (float) Math.sin(spin);
            right.set(basePerp).mul(cs).fma(sn, baseUp);
            up.set(baseUp).mul(cs).fma(-sn, basePerp);

            float sz = shard.size * (0.65F + 0.35F * fade);
            float[] col = bodyPass ? OBSIDIAN : shard.color;
            g.bladeShard(frame.rel(pos), forward, right, up, sz * 2.25F, sz * 0.95F, sz * 0.46F, sz * 0.28F, col[0], col[1], col[2], fade);
         }
         g.end();
      }
   }

   private static List<Vector3f> segment(FxFrame frame, Vec3 from, Vec3 to, int pieces) {
      List<Vector3f> points = new ArrayList<>(pieces + 1);
      for (int i = 0; i <= pieces; i++) {
         points.add(frame.rel(from.lerp(to, (double) i / pieces)));
      }
      return points;
   }

   private static float[] constant(int size, float value) {
      float[] out = new float[size];
      Arrays.fill(out, value);
      return out;
   }

   private static float[] taper(int size, float from, float to) {
      float[] out = new float[size];
      for (int i = 0; i < size; i++) {
         out[i] = FxMath.lerp(from, to, size <= 1 ? 0.0F : (float) i / (size - 1));
      }
      return out;
   }

   // ───────────────────────────── B 档全屏场景与体积通道 ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      float a = this.age(frame.partialTick);
      float alpha = this.zoneAlpha(a);
      float after = this.aftermath(a);
      if (alpha <= 0.005F && after >= 1.0F) {
         return;
      }

      Vector3f zone = frame.rel(this.center);
      // Core 每帧实时追踪下坠与接地后的动态黑洞位置：透镜、视界、光子环、虚空雾光与双波前爆炸球全部同步！
      Vector3f core = frame.rel(this.singularityPos(a));
      float strength = FxMath.window(a, 2.0F, 14.0F) * alpha;
      float bubbleRadius = Math.max(0.01F, this.bubbleMax * this.open(a));
      float wall = 1.0F - FxMath.window(a, OPEN_END - 4.0F, OPEN_END + 6.0F);
      float plungeAct = this.plungeActive(a);
      float ov = this.overload(a);
      float imp = this.implosion(a);
      float sil = this.silence(a);
      float implosionMix = Mth.clamp(ov * 0.55F + imp * 0.75F, 0.0F, 1.0F);
      float bubbleCollapse = Mth.clamp(ov * 0.25F + imp * 0.35F + sil * 0.20F + after * 0.55F, 0.0F, 1.0F);
      float coreR = this.coreRadius(a);

      // 砸地瞬间触发一次紧凑震波，终幕 134t 触发全结界双波前超新星真空冲击球！
      float shockRadius = 0.0F;
      float shockStrength = 0.0F;
      float shockThickness = this.half * 0.085F;
      float flash = 0.0F;
      if (after > 0.0F && after < 1.0F) {
         shockRadius = this.half * 2.05F * FxMath.easeOutCubic(after);
         shockStrength = (1.0F - after) * (1.0F - after);
         flash = Math.max(0.0F, 1.0F - (a - this.reckoningAge) / 10.0F);
      } else {
         float impShock = this.impactShock(a);
         if (impShock > 0.0F && impShock < 1.0F) {
            shockRadius = this.half * 0.78F * FxMath.easeOutCubic(impShock);
            shockStrength = (1.0F - impShock) * (1.0F - impShock) * 0.58F;
            shockThickness = this.half * 0.055F;
            flash = Math.max(0.0F, 1.0F - (a - this.plungeImpactAge) / 4.5F) * 0.45F;
         }
      }

      final float fShockR = shockRadius;
      final float fShockS = shockStrength;
      final float fShockT = shockThickness;
      final float fFlashSq = flash * flash;
      Consumer<ShaderInstance> uniforms = shader -> {
         shader.safeGetUniform("Zone").set(zone.x, zone.y, zone.z, this.half);
         shader.safeGetUniform("ZoneExtra").set(this.above, this.below, strength, this.seedFloat);
         shader.safeGetUniform("Bubble").set(bubbleRadius, Math.max(0.0F, wall), 1.6F, bubbleCollapse);
         shader.safeGetUniform("Core").set(core.x, core.y, core.z, coreR);
         shader.safeGetUniform("Shock").set(fShockR, fShockS, fShockT, fFlashSq);
         shader.safeGetUniform("Collapse").set(plungeAct, implosionMix, sil, after);
      };
      out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.vacuumScene, uniforms));
      out.add(new FxPass(FxPass.Kind.VOLUME, () -> FxShaders.vacuumVolume, uniforms));
   }

   // ───────────────────────────── 屏幕效果与镜头语言 ─────────────────────────────

   private boolean cameraInside(FxFrame frame) {
      Vec3 c = frame.camera;
      return Math.abs(c.x - this.center.x) < this.half && Math.abs(c.z - this.center.z) < this.half && c.y > this.center.y - this.below && c.y < this.center.y + this.above;
   }

   private float attenuation(FxFrame frame) {
      return this.attenuation(frame.camera);
   }

   private float attenuation(Vec3 camera) {
      double dx = Math.max(0.0, Math.abs(camera.x - this.center.x) - this.half);
      double dz = Math.max(0.0, Math.abs(camera.z - this.center.z) - this.half);
      return (float) Math.max(0.0, 1.0 - Math.sqrt(dx * dx + dz * dz) / 160.0);
   }

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      float a = this.age(frame.partialTick);
      float att = this.attenuation(frame);
      if (att <= 0.0F) {
         return;
      }

      Vec3 curPos = this.singularityPos(a);
      float[] zoneUv = frame.screenUv(this.center.add(0.0, 1.0, 0.0));
      float[] coreUv = frame.screenUv(curPos);

      // A1 开场真空泡成核
      if (a < 10.0F) {
         float t = 1.0F - a / 10.0F;
         screen.addFlash(0xCFE8FF, 0.45F * t * t * att, 0.45F);
         screen.addAberration(0.85F * t * att);
         screen.addZoomBlur(0.42F * t * att, zoneUv);
      }

      // 结界内部常驻深空压色与冷暗角
      float strength = FxMath.window(a, 2.0F, 14.0F) * this.zoneAlpha(a);
      if (strength > 0.0F && this.cameraInside(frame)) {
         screen.addDesaturate(0.24F * strength);
         screen.addVignette(0x02040D, 0.38F * strength);
      }

      // A4 黑洞坠落期：速度线随黑洞实时追踪 + 渐强色差
      float plungeP = this.plungeProgress(a);
      if (a >= this.plungeStartAge && a < this.plungeImpactAge) {
         screen.addDashBlur(0.32F * plungeP * att, coreUv);
         screen.addAberration(0.35F * plungeP * att);
      }

      // A5a 砸地瞬间重击反馈
      if (a >= this.plungeImpactAge && a < this.plungeImpactAge + 5.0F) {
         float t = 1.0F - (a - this.plungeImpactAge) / 5.0F;
         screen.addFlash(0xB8F5FF, 0.36F * t * t * att, 0.55F);
         screen.addZoomBlur(0.45F * t * att, coreUv);
         screen.addAberration(0.68F * t * att);
      }

      // A5a~A5b 过载与极速内爆：视野负向强力拉弓向心吸扯 + 速度线 + 色差急升
      if (a >= this.plungeImpactAge && a < this.silenceStartAge) {
         float ov = this.overload(a);
         float imp = this.implosion(a);
         float suckFov = -(0.09F * ov + 0.09F * imp) * att;
         screen.addFovKick(suckFov);
         screen.addDashBlur((0.26F * ov + 0.48F * imp) * att, coreUv);
         screen.addZoomBlur((0.18F * ov + 0.34F * imp) * att, coreUv);
         screen.addAberration((0.42F * ov + 0.55F * imp) * att);
      }

      // A5c 5 tick 绝对死寂呼吸拍：冷色抽离、深渊暗角压顶、负向视野死死拉满待爆！
      float silence = this.silence(a);
      if (silence > 0.0F) {
         screen.addFovKick(-0.18F * att);
         screen.addDesaturate(0.76F * silence * att);
         screen.addVignette(0x02040D, 0.74F * silence * att);
      }

      // A6 终幕超新星真空大爆炸（134t 帧级同步爆发！）
      if (a >= this.reckoningAge && a < this.reckoningAge + BLAST_DURATION) {
         float t = a - this.reckoningAge;

         // 遵守星穹引擎光敏安全配置（D3 铁律：OFF 关闭 / SAFE 默认非反相单拍 / FULL 剧场版三拍反相）
         ModConfig.StellarisEngine engineCfg = ModConfig.CLIENT.stellarisEngine;
         ModConfig.ImpactFrameMode impactMode = engineCfg.impactFrames.getValue();
         float impactIntensity = (float) Mth.clamp(engineCfg.impactFrameIntensity.getValue(), 0.0, 1.0);
         if (impactMode != ModConfig.ImpactFrameMode.OFF && impactIntensity > 0.01F && t < 3.0F) {
            if (impactMode == ModConfig.ImpactFrameMode.SAFE) {
               if (t < 1.6F) {
                  float fade = 1.0F - t / 1.6F;
                  screen.addImpactFrame(0.84F * fade * impactIntensity * att, false);
               }
            } else {
               // FULL 档：反相 → 正相 → 反相 三拍高能动画冲击帧
               boolean invert = t < 1.0F || t >= 2.0F;
               float strengthBeat = t < 1.0F ? 0.96F : (t < 2.0F ? 0.88F : 0.72F * (1.0F - (t - 2.0F)));
               screen.addImpactFrame(strengthBeat * impactIntensity * att, invert);
            }
         }

         // 视野从 -0.18 瞬间反向弹射至 +0.24，彻底释放坍缩积压的物理冲击力！
         if (t < 15.0F) {
            float k = 1.0F - t / 15.0F;
            screen.addFovKick(0.24F * k * k * att);
         }

         // 胶片颗粒过曝爆闪（若关闭致盲闪白则温和封顶）
         float flashCap = engineCfg.flashBlindnessEnabled.getValue() ? 0.90F : 0.40F;
         float flash = Math.max(0.0F, 1.0F - t / 9.5F);
         screen.addFlash(0xEFFBFF, flashCap * flash * flash * att, 1.35F);
         screen.addAberration(1.48F * Math.max(0.0F, 1.0F - t / 18.0F) * att);
         screen.addZoomBlur(0.95F * Math.max(0.0F, 1.0F - t / 14.0F) * att, coreUv);
         screen.addDashBlur(0.65F * Math.max(0.0F, 1.0F - t / 12.0F) * att, coreUv);
         screen.addBloomBoost(1.65F * Math.max(0.0F, 1.0F - t / 15.0F) * att);
      }
   }

   @Override
   public float shake(FxFrame frame) {
      float a = this.age(frame.partialTick);
      float att = this.attenuation(frame);
      // 5 tick 死寂呼吸拍绝对零抖动，将下一拍超新星炸裂反差推到极致
      if (a >= this.silenceStartAge && a < this.reckoningAge) {
         return 0.0F;
      }
      float value = 0.0F;
      if (a < 10.0F) {
         value = 0.58F * (1.0F - a / 10.0F);
      }
      if (a < this.plungeStartAge && this.cameraInside(frame)) {
         value = Math.max(value, 0.05F + this.strike);
      }
      // 黑洞坠落轰鸣渐强震颤
      if (a >= this.plungeStartAge && a < this.plungeImpactAge) {
         float p = this.plungeProgress(a);
         value = Math.max(value, 0.12F + 0.34F * p * p);
      }
      // 砸地重击一拍 + 过载内爆高频紧绷震颤
      if (a >= this.plungeImpactAge && a < this.silenceStartAge) {
         float impactSpike = Math.max(0.0F, 1.0F - (a - this.plungeImpactAge) / 6.0F) * 0.85F;
         float build = 0.22F + 0.42F * this.overload(a) + 0.22F * this.implosion(a);
         value = Math.max(value, Math.max(impactSpike, build));
      }
      // 终幕超新星爆发满幅震屏与余波衰减
      if (a >= this.reckoningAge && a < this.reckoningAge + BLAST_DURATION) {
         float after = this.aftermath(a);
         value = Math.max(value, 1.0F * (1.0F - after) * (1.0F - after * 0.35F));
      }
      return value * att;
   }

   // ───────────────────────────── 内部数据结构 ─────────────────────────────

   private static final class StarPulse {
      final Vec3 pos;
      final float maxRadius;
      final float[] color;
      int age;
      final int maxAge = 12;

      private StarPulse(Vec3 pos, float maxRadius, float[] color) {
         this.pos = pos;
         this.maxRadius = maxRadius;
         this.color = color;
      }
   }

   private static final class InfallStream {
      final float azimuth;
      final float elevation;
      final float startRadius;
      final float twist;
      final float width;
      final float[] color;

      private InfallStream(float azimuth, float elevation, float startRadius, float twist, float width, float[] color) {
         this.azimuth = azimuth;
         this.elevation = elevation;
         this.startRadius = startRadius;
         this.twist = twist;
         this.width = width;
         this.color = color;
      }
   }

   private static final class CollapseShard {
      final float azimuth;
      final float elevation;
      final float orbitRadius;
      final float orbitSpeed;
      final float size;
      final float rollPhase;
      final float[] color;

      private CollapseShard(float azimuth, float elevation, float orbitRadius, float orbitSpeed, float size, float rollPhase, float[] color) {
         this.azimuth = azimuth;
         this.elevation = elevation;
         this.orbitRadius = orbitRadius;
         this.orbitSpeed = orbitSpeed;
         this.size = size;
         this.rollPhase = rollPhase;
         this.color = color;
      }
   }

   private static final class BlastShard {
      double x;
      double y;
      double z;
      double px;
      double py;
      double pz;
      double vx;
      double vy;
      double vz;
      final float size;
      final float spinBase;
      final float spinSpeed;
      final Vector3f axis;
      int life;
      final int maxLife;
      final float[] color;

      private BlastShard(Vec3 origin, double vx, double vy, double vz, float size, int life, RandomSource random, float[] color) {
         this.x = this.px = origin.x;
         this.y = this.py = origin.y;
         this.z = this.pz = origin.z;
         this.vx = vx;
         this.vy = vy;
         this.vz = vz;
         this.size = size;
         this.spinBase = random.nextFloat() * FxMath.TAU;
         this.spinSpeed = (4.5F + random.nextFloat() * 7.5F) * (random.nextBoolean() ? 1.0F : -1.0F);
         this.axis = FxMath.randomUnit(random);
         this.life = life;
         this.maxLife = life;
         this.color = color;
      }

      void tick(double groundY) {
         this.px = this.x;
         this.py = this.y;
         this.pz = this.z;
         this.x += this.vx;
         this.y += this.vy;
         this.z += this.vz;
         if (this.y < groundY + 0.25) {
            this.y = groundY + 0.25;
            this.vy = Math.abs(this.vy) * 0.35;
         }
         this.vx *= 0.91;
         this.vy = this.vy * 0.91 - 0.018;
         this.vz *= 0.91;
         this.life--;
      }

      Vec3 position(float partialTick) {
         return new Vec3(this.px + (this.x - this.px) * partialTick, this.py + (this.y - this.py) * partialTick, this.pz + (this.z - this.pz) * partialTick);
      }
   }

   private static final class Ember {
      double x;
      double y;
      double z;
      double px;
      double py;
      double pz;
      double vx;
      double vy;
      double vz;
      int life;
      final int maxLife;
      final float[] color;
      /** 非空时持续受目标奇点向心加速（用于坠落抽尘与内爆倒吸火花）。 */
      final Vec3 attractTarget;

      private Ember(Vec3 origin, double vx, double vy, double vz, int life, float[] color, Vec3 attractTarget) {
         this.x = this.px = origin.x;
         this.y = this.py = origin.y;
         this.z = this.pz = origin.z;
         this.vx = vx;
         this.vy = vy;
         this.vz = vz;
         this.life = life;
         this.maxLife = life;
         this.color = color;
         this.attractTarget = attractTarget;
      }

      static Ember at(Vec3 origin, RandomSource random, float[] color) {
         Vector3f dir = FxMath.randomUnit(random).mul(0.35F);
         return new Ember(origin, dir.x, Math.abs(dir.y) + 0.25, dir.z, 10 + random.nextInt(10), color, null);
      }

      static Ember suckToward(Vec3 origin, Vec3 target, double speed, int life, float[] color) {
         Vec3 delta = target.subtract(origin);
         double len = Math.max(0.25, delta.length());
         Vec3 v = delta.scale(speed / len);
         return new Ember(origin, v.x, v.y, v.z, life, color, target);
      }

      void tick() {
         this.px = this.x;
         this.py = this.y;
         this.pz = this.z;
         if (this.attractTarget != null) {
            double dx = this.attractTarget.x - this.x;
            double dy = this.attractTarget.y - this.y;
            double dz = this.attractTarget.z - this.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < 1.8) {
               this.life = 0;
               return;
            }
            double inv = 0.28 / Math.sqrt(d2);
            this.vx = this.vx * 0.92 + dx * inv;
            this.vy = this.vy * 0.92 + dy * inv;
            this.vz = this.vz * 0.92 + dz * inv;
            this.x += this.vx;
            this.y += this.vy;
            this.z += this.vz;
         } else {
            this.x += this.vx;
            this.y += this.vy;
            this.z += this.vz;
            this.vx *= 0.9;
            this.vy = this.vy * 0.9 + 0.012;
            this.vz *= 0.9;
         }
         this.life--;
      }

      Vec3 position(float partialTick) {
         return new Vec3(this.px + (this.x - this.px) * partialTick, this.py + (this.y - this.py) * partialTick, this.pz + (this.z - this.pz) * partialTick);
      }
   }
}
