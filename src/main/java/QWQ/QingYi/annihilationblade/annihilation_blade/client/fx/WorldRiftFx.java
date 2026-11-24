package QWQ.QingYi.annihilationblade.annihilation_blade.client.fx;

import QWQ.QingYi.annihilationblade.annihilation_blade.logic.WorldRiftPattern;
import QWQ.QingYi.annihilationblade.annihilation_blade.visual.AnnihilationFxEvents;
import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.core.FxInstance;
import QWQ.QingYi.annihilationblade.render.client.core.FxPass;
import QWQ.QingYi.annihilationblade.render.client.core.FxScreenState;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry.Style;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxMath;
import QWQ.QingYi.annihilationblade.render.client.pipeline.FxShaders;
import QWQ.QingYi.annihilationblade.render.network.FxEventPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 湮灭之刃 SE「裂界·因果伤痕（万花筒神威空间扭曲处决）」专属持续渲染器。
 * <p>
 * 视觉母题：「品红 × 虚空紫 × 漆黑奇点 · 多圈对数螺旋空间绞杀 · 3D 躯壳拧麻花吸入 · 奇点内爆归零回弹」。
 * 支持两种完整演出模式：
 * <ul>
 *   <li><b>独立单链模式（断空关闭时，路线 A）</b>：目标活体先在屏幕空间被神威对数螺旋拧弯、受 3D 螺旋空间丝与倾斜剪切环箍紧；
 *       到达临界拧断帧（14t）瞬间切换至抓拍的 3D 模型躯壳，上下半身反向暴拧成麻花并沿螺线被彻底吸穿进漆黑奇点，
 *       紧接着发射空间跃迁裂线跳向下一最近目标，尾声奇点内爆归零并伴随空间弹性回弹细环余波。</li>
 *   <li><b>断空联动模式（断空开启斩杀后）</b>：在断空每闪斩杀前静默预抓拍活体 3D 模型与劈砍法线；待断空收刀齐斩错位结束（s = 11t）瞬间，
 *       对全部已斩杀目标残骸按距离错峰展开神威奇点，将断成两截的 3D 躯壳分别反向拧成双股螺旋卷入黑洞后内爆归零，
 *       并在单个 {@code fx_wound_scene} Pass 内同步打包最多 8 个活跃神威涡心与最多 4 个 {@code fx_wound_volume} 解析空间漏斗。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class WorldRiftFx extends FxInstance {
   private static final int MAX_SCENE_VORTICES = 8;
   private static final int MAX_VOLUME_VORTICES = 4;
   private static final int MAX_CAPTURED_MESHES = 20;
   private static final int MAX_SHARD_BUDGET = 110;
   private static final int MAX_SPARK_BUDGET = 220;
   private static final int SPIRAL_ARMS = 6;
   private static final int SPIRAL_STEPS = 18;
   private static final int HOP_BOLT_POINTS = 15;
   private static final double SCREEN_RANGE = 42.0;

   // 色盘：漆黑奇点 × 深虚空紫 × 裂界品红 × 兰粉白热锋芒
   private static final float[] VOID_BLACK = {0.014F, 0.008F, 0.028F};
   private static final float[] DEEP_BODY = {0.07F, 0.015F, 0.13F};
   private static final float[] DEEP_VIOLET = {0.40F, 0.11F, 0.90F};
   private static final float[] VIOLET = {0.60F, 0.22F, 1.0F};
   private static final float[] MAGENTA = {0.94F, 0.20F, 0.96F};
   private static final float[] HOT_ORCHID = {1.0F, 0.68F, 0.98F};
   private static final float[] WHITE_HOT = {1.0F, 0.95F, 1.0F};
   private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);

   private final float radius;
   private final float scale;
   private final RandomSource random;
   private final Map<Integer, VortexNode> nodesById = new LinkedHashMap<>();
   private final List<VortexNode> nodes = new ArrayList<>();
   private final List<HopBridge> hops = new ArrayList<>();
   private final Vector3f q0 = new Vector3f();
   private final Vector3f q1 = new Vector3f();
   private final Vector3f q2 = new Vector3f();
   private final Vector3f q3 = new Vector3f();

   private Vec3 focusPoint;
   private boolean linkageMode;
   private boolean unleashed;
   private int capturedMeshCount;
   private int assignedShards;
   private int assignedSparks;
   private int soundThrottle;

   public WorldRiftFx(ClientLevel level, FxEventPacket packet) {
      super(WorldRiftPattern.NODE_LIFETIME + 16);
      this.focusPoint = packet.origin();
      this.radius = Math.max(2.0F, packet.radius());
      this.scale = Mth.clamp(packet.scale(), 0.4F, 3.0F);
      this.random = RandomSource.create(packet.seed() ^ 0x51A7F3C9L);
      this.applyPacket(level, packet, true);
   }

   @Override
   protected void onSustainedStart(int duration) {
      this.lifetime = Math.max(this.lifetime, duration + WorldRiftPattern.NODE_LIFETIME);
   }

   @Override
   public void onSustainedUpdate(ClientLevel level, FxEventPacket event) {
      this.applyPacket(level, event, false);
   }

   @Override
   public void onSustainedStop() {
      if (this.linkageMode && !this.unleashed) {
         // 断空异常中止且尚未进入尸骸扭杀爆发阶段，直接静默移除
         this.lifetime = Math.min(this.lifetime, this.age + 1);
         return;
      }
      int maxEnd = this.age + 2;
      for (VortexNode node : this.nodes) {
         if (node.startAge >= 0) {
            maxEnd = Math.max(maxEnd, node.endAge());
         }
      }
      for (HopBridge hop : this.hops) {
         maxEnd = Math.max(maxEnd, hop.startAge + hop.duration + 2);
      }
      this.lifetime = Math.max(this.age + 1, maxEnd);
   }

   private void applyPacket(ClientLevel level, FxEventPacket packet, boolean initial) {
      int kind = AnnihilationFxEvents.worldRiftKind(packet.intParam());
      int hopIndex = AnnihilationFxEvents.worldRiftHop(packet.intParam());
      int[] ids = packet.entityIds();
      int[] ticks = packet.targetTicks();

      switch (kind) {
         case AnnihilationFxEvents.RIFT_SINGLE_START -> {
            this.linkageMode = false;
            this.unleashed = true;
            this.focusPoint = packet.origin();
            int entityId = ids.length > 0 ? ids[0] : -1;
            int snapOffset = ticks.length > 0 && ticks[0] > 0 ? ticks[0] : WorldRiftPattern.SNAP_TICK;
            VortexNode node = this.createNode(level, entityId, packet.origin(), Vec3.ZERO, false, hopIndex, this.age, this.age + snapOffset);
            this.registerNode(node);
            this.ensureLifeFor(node.endAge());
         }
         case AnnihilationFxEvents.RIFT_SINGLE_SNAP -> {
            this.linkageMode = false;
            this.unleashed = true;
            this.focusPoint = packet.origin();
            int entityId = ids.length > 0 ? ids[0] : -1;
            VortexNode node = entityId >= 0 ? this.nodesById.get(entityId) : (this.nodes.isEmpty() ? null : this.nodes.get(this.nodes.size() - 1));
            if (node == null && initial) {
               // 中途进入视野的玩家恰好收到 SNAP：直接补建已进入拧断状态的奇点
               node = this.createNode(level, entityId, packet.origin(), Vec3.ZERO, false, hopIndex, Math.max(0, this.age - WorldRiftPattern.SNAP_TICK), this.age);
               this.registerNode(node);
            }
            if (node != null) {
               this.refreshEntityPose(level, node, true);
               node.center = packet.origin();
               node.foot = packet.origin().subtract(0.0, node.height * 0.56, 0.0);
               if (!node.snapped) {
                  node.snapped = true;
                  node.snapAge = node.snapAge >= 0 ? Math.min(node.snapAge, this.age) : this.age;
                  this.playSnapSound(level, node.center, false);
               }
               this.ensureLifeFor(node.endAge());
            }
         }
         case AnnihilationFxEvents.RIFT_SINGLE_HOP -> {
            this.linkageMode = false;
            this.unleashed = true;
            this.focusPoint = packet.origin();
            Vec3 from = packet.direction().lengthSqr() > 1.0E-4 ? packet.direction() : this.focusPoint;
            int entityId = ids.length > 0 ? ids[0] : -1;
            int snapOffset = ticks.length > 0 && ticks[0] > 0 ? ticks[0] : WorldRiftPattern.SNAP_TICK;
            Vec3 hopDir = packet.origin().subtract(from);
            VortexNode node = this.createNode(level, entityId, packet.origin(), hopDir, false, hopIndex, this.age, this.age + snapOffset);
            this.registerNode(node);
            if (from.distanceToSqr(packet.origin()) > 0.25) {
               HopBridge bridge = new HopBridge(from, packet.origin(), this.age, 9, this.random.nextLong());
               this.hops.add(bridge);
               this.ensureLifeFor(bridge.startAge + bridge.duration + 2);
            }
            this.playHopSound(level, from, packet.origin());
            this.ensureLifeFor(node.endAge());
         }
         case AnnihilationFxEvents.RIFT_LINK_PREPARE -> {
            this.linkageMode = true;
            this.focusPoint = packet.origin();
            this.extendLife(120);
            if (ids.length > 0) {
               int entityId = ids[0];
               if (!this.nodesById.containsKey(entityId)) {
                  VortexNode node = this.createNode(level, entityId, packet.origin(), packet.direction(), true, hopIndex, -1, -1);
                  this.registerNode(node);
               }
            }
         }
         case AnnihilationFxEvents.RIFT_LINK_UNLEASH -> {
            this.linkageMode = true;
            this.unleashed = true;
            this.focusPoint = packet.origin();
            int maxEnd = this.age + WorldRiftPattern.LINKAGE_NODE_LIFETIME + 2;
            for (int i = 0; i < ids.length; i++) {
               int id = ids[i];
               int stagger = i < ticks.length ? Mth.clamp(ticks[i], 0, WorldRiftPattern.LINKAGE_STAGGER_MAX) : 0;
               VortexNode node = this.nodesById.get(id);
               if (node == null) {
                  // 若中途刚进入视野、未收到之前的预抓拍包，尝试回退解析实体
                  Entity fallback = level.getEntity(id);
                  if (fallback != null) {
                     node = this.createNode(level, id, WorldRiftPattern.vortexCenter(fallback), packet.direction(), true, i, -1, -1);
                     this.registerNode(node);
                  }
               }
               if (node != null && node.startAge < 0) {
                  node.startAge = this.age + stagger;
                  node.snapAge = node.startAge + WorldRiftPattern.LINKAGE_SNAP_OFFSET;
                  node.snapped = true;
                  maxEnd = Math.max(maxEnd, node.endAge());
               }
            }
            // 同时激活所有此前在预抓拍队列里登记过但未被漏掉的节点
            for (VortexNode node : this.nodes) {
               if (node.startAge < 0) {
                  node.startAge = this.age;
                  node.snapAge = this.age + WorldRiftPattern.LINKAGE_SNAP_OFFSET;
                  node.snapped = true;
                  maxEnd = Math.max(maxEnd, node.endAge());
               }
            }
            if (!this.nodes.isEmpty()) {
               play(level, this.focusPoint, SoundEvents.END_PORTAL_FRAME_FILL, 0.95F, 0.52F);
               play(level, this.focusPoint, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 0.90F, 0.62F);
            }
            this.ensureLifeFor(maxEnd);
         }
         default -> {
         }
      }
   }

   private void registerNode(VortexNode node) {
      this.nodes.add(node);
      if (node.entityId >= 0) {
         this.nodesById.put(node.entityId, node);
      }
   }

   private VortexNode createNode(
      ClientLevel level, int entityId, Vec3 fallbackCenter, Vec3 hintDir,
      boolean linkage, int index, int startAge, int snapAge
   ) {
      Entity entity = entityId >= 0 ? level.getEntity(entityId) : null;
      float width = 0.85F;
      float height = 1.85F;
      Vec3 center = fallbackCenter;
      Vec3 foot = fallbackCenter.subtract(0.0, height * 0.56, 0.0);
      float[] quads = null;

      if (entity != null) {
         width = Math.max(0.55F, entity.getBbWidth());
         height = Math.max(0.85F, entity.getBbHeight());
         foot = entity.position();
         center = WorldRiftPattern.vortexCenter(foot, height);
         if (entity instanceof LivingEntity living && this.capturedMeshCount < MAX_CAPTURED_MESHES) {
            quads = AfterimageCapture.captureLiving(living);
            if (quads != null) {
               this.capturedMeshCount++;
            }
         }
      }

      // 构建每个奇点固定的 3D 空间姿态（绝不再用水平跟随相机的 2D 纸片）
      Vector3f normal = hintDir.lengthSqr() > 1.0E-4
         ? new Vector3f((float)hintDir.x, (float)hintDir.y * 0.35F, (float)hintDir.z).normalize()
         : FxMath.randomUnit(this.random);
      if (Math.abs(normal.y) > 0.82F) {
         normal.set((float)Math.cos(index * 1.7 + this.random.nextFloat()), 0.25F, (float)Math.sin(index * 1.7 + this.random.nextFloat())).normalize();
      }
      Vector3f axisU = FxMath.perpendicular(normal);
      Vector3f axisV = new Vector3f(normal).cross(axisU).normalize();

      // 断空联动时构造斜向刀口法线，把抓拍模型按断空斩击切成两半并在神威里反向暴拧
      Vector3f cutDir = new Vector3f(axisU).mul(0.75F).add(new Vector3f(axisV).mul((this.random.nextBoolean() ? 0.65F : -0.65F))).normalize();
      Vector3f cutNormal = new Vector3f(normal).cross(cutDir).normalize();

      int shardCount = Math.min(linkage ? 5 : 8, Math.max(0, MAX_SHARD_BUDGET - this.assignedShards));
      this.assignedShards += shardCount;
      int sparkCount = Math.min(linkage ? 10 : 16, Math.max(0, MAX_SPARK_BUDGET - this.assignedSparks));
      this.assignedSparks += sparkCount;

      float spinSign = (index % 2 == 0) ? 1.0F : -1.0F;
      return new VortexNode(
         entityId, foot, center, width, height, normal, axisU, axisV, cutNormal, cutDir,
         linkage, spinSign, startAge, snapAge, quads, shardCount, sparkCount, this.random
      );
   }

   private void ensureLifeFor(int targetAge) {
      this.lifetime = Math.max(this.lifetime, targetAge + 2);
   }

   @Override
   public Vec3 focus() {
      return this.focusPoint;
   }

   @Override
   public double cullDistance() {
      return Math.max(192.0, this.radius + 160.0);
   }

   // ───────────────────────────── 逻辑与音效时间轴 ─────────────────────────────

   @Override
   public void tick(ClientLevel level) {
      int age = this.ageTicks();
      this.soundThrottle = 0;

      for (VortexNode node : this.nodes) {
         if (node.startAge < 0) {
            continue;
         }
         int local = age - node.startAge;
         if (local < 0 || age > node.endAge()) {
            continue;
         }

         // 独立模式活体扭曲阶段：追踪实时坐标并每 2 tick 刷新一次模型姿势抓拍，确保拧断瞬间无缝衔接
         if (!node.linkageMode && !node.snapped && local <= WorldRiftPattern.SNAP_TICK) {
            boolean capture = (local % 2 == 0) || local == WorldRiftPattern.SNAP_TICK - 1;
            this.refreshEntityPose(level, node, capture);
         }

         if (local == 0 && !node.openSoundPlayed) {
            node.openSoundPlayed = true;
            if (!node.linkageMode) {
               play(level, node.center, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 0.78F, 0.72F);
               play(level, node.center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.72F, 0.55F);
            }
         }

         if (!node.linkageMode && local == 4 && !node.tensionSoundPlayed) {
            node.tensionSoundPlayed = true;
            play(level, node.center, SoundEvents.CHORUS_FRUIT_TELEPORT, 0.58F, 0.58F);
            play(level, node.center, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.65F, 1.35F);
         }

         if (node.linkageMode && age == node.snapAge && !node.snapSoundPlayed) {
            node.snapSoundPlayed = true;
            if (this.soundThrottle < 3) {
               this.soundThrottle++;
               this.playSnapSound(level, node.center, true);
            }
         }

         int sealTick = node.linkageMode ? WorldRiftPattern.LINKAGE_SUCK_END : WorldRiftPattern.SEAL_START_TICK;
         if (local == sealTick && !node.sealSoundPlayed) {
            node.sealSoundPlayed = true;
            if (!node.linkageMode || this.soundThrottle < 2) {
               this.soundThrottle++;
               play(level, node.center, SoundEvents.SCULK_CATALYST_BLOOM, node.linkageMode ? 0.42F : 0.60F, 1.65F + node.pitchJitter * 0.2F);
               play(level, node.center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.45F, 1.85F);
            }
         }
      }
   }

   private void refreshEntityPose(ClientLevel level, VortexNode node, boolean recaptureMesh) {
      if (node.entityId < 0) {
         return;
      }
      Entity entity = level.getEntity(node.entityId);
      if (entity == null || !entity.isAlive()) {
         return;
      }
      node.foot = entity.position();
      node.width = Math.max(0.55F, entity.getBbWidth());
      node.height = Math.max(0.85F, entity.getBbHeight());
      node.center = WorldRiftPattern.vortexCenter(node.foot, node.height);
      if (recaptureMesh && entity instanceof LivingEntity living && (node.capturedQuads != null || this.capturedMeshCount < MAX_CAPTURED_MESHES)) {
         float[] fresh = AfterimageCapture.captureLiving(living);
         if (fresh != null) {
            if (node.capturedQuads == null) {
               this.capturedMeshCount++;
            }
            node.setQuads(fresh);
         }
      }
   }

   private void playSnapSound(ClientLevel level, Vec3 at, boolean linkage) {
      float volScale = linkage ? 0.72F : 1.0F;
      play(level, at, SoundEvents.WARDEN_SONIC_BOOM, 0.56F * volScale, 1.42F + this.random.nextFloat() * 0.16F);
      play(level, at, SoundEvents.GLASS_BREAK, 0.68F * volScale, 0.68F + this.random.nextFloat() * 0.14F);
      play(level, at, SoundEvents.CHORUS_FRUIT_TELEPORT, 0.55F * volScale, 1.55F);
   }

   private void playHopSound(ClientLevel level, Vec3 from, Vec3 to) {
      Vec3 mid = from.lerp(to, 0.5);
      play(level, mid, SoundEvents.ENDERMAN_TELEPORT, 0.72F, 1.45F);
      play(level, to, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.65F, 1.55F);
   }

   private static void play(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
      level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
   }

   // ───────────────────────────── 降级粒子表现 ─────────────────────────────

   @Override
   public void tickFallback(ClientLevel level) {
      int age = this.ageTicks();
      for (VortexNode node : this.nodes) {
         if (node.startAge < 0 || age < node.startAge || age > node.endAge()) {
            continue;
         }
         int local = age - node.startAge;
         if (local % 2 == 0) {
            double r = (1.6 - Math.min(1.3, local * 0.08)) * this.scale;
            for (int i = 0; i < 4; i++) {
               double ang = local * 0.75 + i * Math.PI * 0.5;
               double px = node.center.x + Math.cos(ang) * r;
               double py = node.center.y + (this.random.nextDouble() - 0.5) * node.height * 0.6;
               double pz = node.center.z + Math.sin(ang) * r;
               level.addParticle(ParticleTypes.PORTAL, px, py, pz, (node.center.x - px) * 0.25, (node.center.y - py) * 0.25, (node.center.z - pz) * 0.25);
            }
         }
         if (age == node.snapAge) {
            level.addParticle(ParticleTypes.REVERSE_PORTAL, node.center.x, node.center.y, node.center.z, 0.0, 0.0, 0.0);
         }
      }
   }

   // ───────────────────────────── 预乘 Alpha 本体层（漆黑奇点核 + 暗紫暴拧躯壳） ─────────────────────────────

   @Override
   public boolean hasBody() {
      return true;
   }

   @Override
   public void renderBody(FxGeometry b, FxFrame frame) {
      float a = this.age(frame.partialTick);

      // 1. 漆黑奇点事件视界球（deformedSphere + geoCenter 解析法线，白天高反差纯黑吞光）
      for (VortexNode node : this.nodes) {
         if (!node.isVisibleAt(a)) {
            continue;
         }
         float holeR = node.blackHoleRadius(a, this.scale);
         if (holeR <= 0.025F) {
            continue;
         }
         Vector3f rel = frame.rel(node.center);
         float nf = nearFade(rel, 0.65F, 1.45F, 0.0F);
         if (nf <= 0.005F) {
            continue;
         }
         b.geoCenter(rel);
         b.begin(Style.CORONA, 0.24F, 0.0F, 2.4F, 1.35F, 1.8F * node.spinSign, 0.55F);
         b.deformedSphere(rel, holeR, 12, 18, a * 0.22F * node.spinSign, 0.06F, 2.4F, a * 0.35F, VOID_BLACK[0], VOID_BLACK[1], VOID_BLACK[2], 0.96F * nf);
         b.end();
         b.clearGeoCenter();
      }

      // 2. 拧断后的 3D 螺旋扭曲目标躯壳本体层（压出暗紫黑实心剪影；断空联动从 local=0 无缝承接已劈成两半的残骸）
      boolean bodyMeshStarted = false;
      for (VortexNode node : this.nodes) {
         if (!node.isVisibleAt(a) || node.capturedQuads == null) {
            continue;
         }
         float meshAge = node.linkageMode ? (a - node.startAge) : node.sinceSnap(a);
         if (meshAge < 0.0F) {
            continue;
         }
         float effectiveSnap = Math.max(0.0F, node.sinceSnap(a));
         float suckDur = node.suckDuration();
         if (effectiveSnap >= suckDur) {
            continue;
         }
         float dissolve = FxMath.window(effectiveSnap, suckDur * 0.62F, suckDur);
         b.begin(Style.AFTERIMAGE, 0.62F, 0.06F, 1.0F, 0.72F, 3.0F, dissolve);
         this.emitTwistedMesh(b, frame, node, a, meshAge, effectiveSnap, true);
         bodyMeshStarted = true;
      }
      if (bodyMeshStarted) {
         b.end();
      }

      // 3. 3D 棱晶空间碎刃本体暗核（高反差黑曜紫晶）
      b.begin(Style.CRYSTAL_SHARD, 0.42F, 0.05F, 2.2F, 1.6F, 0.35F, 0.0F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderNodeShards(b, frame, node, a, true);
         }
      }
      b.end();
   }

   // ───────────────────────────── HDR 网格发光层 ─────────────────────────────

   @Override
   public void renderGeometry(FxGeometry g, FxFrame frame) {
      float a = this.age(frame.partialTick);

      // 1. 围绕奇点的 3D 阿基米德螺旋空间旋臂（品红外丝 + 白热细芯）
      g.begin(Style.RIBBON, 4.6F, 0.16F, 5.5F, 6.0F, 14.0F, 0.55F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderSpiralArms(g, frame, node, a, false);
         }
      }
      g.begin(Style.RIBBON, 8.8F, 0.12F, 10.5F, 0.0F, 0.0F, 0.22F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderSpiralArms(g, frame, node, a, true);
         }
      }

      // 2. 正交倾斜的 3D 空间剪切压缩环（torus，随拧紧向心挤压）
      g.begin(Style.PLASMA_BEAM, 3.8F, 0.14F, 2.0F, 4.2F, 0.0F, 0.5F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderShearRings(g, frame, node, a);
         }
      }

      // 3. 拧断瞬间 3D 喉部激波锥（SHOCK_CONE）与奇点过载星芒（GLOW）
      g.begin(Style.SHOCK_CONE, 6.2F, 0.12F, 0.5F, 4.5F, 0.9F, 1.0F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderSnapCone(g, frame, node, a);
         }
      }

      g.begin(Style.GLOW, 6.8F, 0.0F, 4.2F, 2.4F, 1.15F, 0.0F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderCoreFlares(g, frame, node, a);
         }
      }

      // 4. 被暴拧成麻花的 3D 目标躯壳发光断茬（AFTERIMAGE 边缘 + 烧蚀前沿）
      for (VortexNode node : this.nodes) {
         if (!node.isVisibleAt(a) || node.capturedQuads == null) {
            continue;
         }
         float meshAge = node.linkageMode ? (a - node.startAge) : node.sinceSnap(a);
         if (meshAge < 0.0F) {
            continue;
         }
         float effectiveSnap = Math.max(0.0F, node.sinceSnap(a));
         float suckDur = node.suckDuration();
         if (effectiveSnap >= suckDur) {
            continue;
         }
         float flash = 1.0F - FxMath.window(effectiveSnap, 0.0F, 2.6F);
         float dissolve = FxMath.window(effectiveSnap, suckDur * 0.58F, suckDur);
         g.begin(Style.AFTERIMAGE, 5.2F + 4.8F * flash, 0.06F, 1.35F, 0.12F + 0.35F * flash, 4.5F, dissolve);
         this.emitTwistedMesh(g, frame, node, a, meshAge, effectiveSnap, false);
      }

      // 5. 3D 棱晶空间碎刃（HDR 锋芒层）
      g.begin(Style.CRYSTAL_SHARD, 5.4F, 0.05F, 2.4F, 2.1F, 0.75F, 0.0F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderNodeShards(g, frame, node, a, false);
         }
      }

      // 6. 沿螺旋被吸入奇点的空间尘埃拖尾（streak）
      g.begin(Style.RIBBON, 6.2F, 0.14F, 7.0F, 0.0F, 0.0F, 0.35F);
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderSpiralSparks(g, frame, node, a);
         }
      }

      // 7. 尾声：奇点内爆归零与空间弹性回弹余波（不再留下外科手术缝合疤线）
      for (VortexNode node : this.nodes) {
         if (node.isVisibleAt(a)) {
            this.renderImplosionCollapse(g, frame, node, a);
         }
      }

      // 8. 单链跃迁虚空折线 + 双螺旋传导丝
      if (!this.hops.isEmpty()) {
         this.renderHopBridges(g, frame, a);
      }

      g.end();
   }

   // ───────────────────────────── 各子模块 3D 几何绘制 ─────────────────────────────

   /** 6 条 3D 立体对数螺旋旋臂：随拧紧加速旋绕、向奇点黑洞急剧收束。 */
   private void renderSpiralArms(FxGeometry g, FxFrame frame, VortexNode node, float a, boolean corePass) {
      float local = a - node.startAge;
      float open = node.openFactor(a);
      float lifeFade = node.vortexEnvelope(a);
      if (open <= 0.01F || lifeFade <= 0.01F) {
         return;
      }
      Vector3f rel = frame.rel(node.center);
      float nf = nearFade(rel, 0.75F, 1.65F, 0.25F);
      float baseRadius = node.effectRadius(this.scale);
      float sinceSnap = node.sinceSnap(a);
      float snapBoost = sinceSnap >= 0.0F ? (1.0F + 0.65F * (1.0F - FxMath.window(sinceSnap, 0.0F, 4.0F))) : 1.0F;
      float collapse = sinceSnap >= 0.0F ? FxMath.window(sinceSnap, 1.5F, node.suckDuration() + 1.5F) : 0.0F;
      float outerR = baseRadius * (1.08F - 0.62F * collapse) * (0.45F + 0.55F * open);
      float innerR = Math.max(0.06F, node.blackHoleRadius(a, this.scale) * 0.92F);
      float spin = node.spinSign * (local * 0.42F + (sinceSnap > 0.0F ? sinceSnap * 0.68F : 0.0F));

      List<Vector3f> pts = new ArrayList<>(SPIRAL_STEPS);
      float[] widths = new float[SPIRAL_STEPS];
      float[] alphas = new float[SPIRAL_STEPS];

      for (int arm = 0; arm < SPIRAL_ARMS; arm++) {
         pts.clear();
         float baseAngle = FxMath.TAU * arm / SPIRAL_ARMS + spin;
         float depthWave = (arm % 2 == 0 ? 1.0F : -1.0F) * 0.36F * baseRadius * (1.0F - collapse * 0.75F);
         float[] col = corePass ? WHITE_HOT : (arm % 2 == 0 ? MAGENTA : VIOLET);
         float wBase = (corePass ? 0.014F : 0.048F) * this.scale * snapBoost;
         wBase = Math.max(wBase, rel.length() * (corePass ? 0.0012F : 0.0030F));

         for (int s = 0; s < SPIRAL_STEPS; s++) {
            float k = (float)s / (SPIRAL_STEPS - 1);
            // k=0 在外围，k=1 卷入奇点中心，越靠近中心螺距越紧
            float inv = 1.0F - k;
            float r = FxMath.lerp(outerR, innerR, (float)Math.pow(k, 0.78));
            float ang = baseAngle + node.spinSign * k * (4.2F + 2.6F * open);
            float cs = (float)Math.cos(ang);
            float sn = (float)Math.sin(ang);
            // 沿法向呈漏斗状凹陷，增强 3D 立体纵深
            float axial = depthWave * inv * inv * (float)Math.sin(k * Math.PI);
            Vector3f p = new Vector3f(rel)
               .fma(r * cs, node.axisU)
               .fma(r * sn, node.axisV)
               .fma(axial, node.axisNormal);
            pts.add(p);

            float taper = (float)Math.sin(Math.PI * k);
            widths[s] = wBase * (0.28F + 0.85F * taper) * (0.45F + 0.55F * inv);
            alphas[s] = lifeFade * nf * (0.20F + 0.80F * taper) * (corePass ? 0.85F : 0.92F);
         }
         g.ribbon(pts, widths, alphas, col[0], col[1], col[2]);
      }
   }

   /** 两组正交倾斜的 3D 空间压环：活体扭曲时急速箍紧目标躯干，拧断瞬间收爆。 */
   private void renderShearRings(FxGeometry g, FxFrame frame, VortexNode node, float a) {
      float local = a - node.startAge;
      float sinceSnap = node.sinceSnap(a);
      if (sinceSnap > 5.0F) {
         return;
      }
      Vector3f rel = frame.rel(node.center);
      float nf = nearFade(rel, 0.85F, 1.8F, 0.25F);
      float open = FxMath.easeOutCubic(FxMath.window(local, 0.0F, 3.5F));
      float squeeze = node.linkageMode
         ? FxMath.window(local, 0.0F, WorldRiftPattern.LINKAGE_SNAP_OFFSET + 3.0F)
         : WorldRiftPattern.liveTwistProgress(local);
      float fade = sinceSnap >= 0.0F ? (1.0F - FxMath.window(sinceSnap, 0.5F, 4.8F)) : open;
      if (fade <= 0.01F) {
         return;
      }

      float baseR = Math.max(node.width * 1.15F, 1.05F) * this.scale;
      float r1 = baseR * FxMath.lerp(1.55F, 0.42F, squeeze);
      float r2 = baseR * FxMath.lerp(1.20F, 0.30F, squeeze);
      float minor = Math.max(0.030F * this.scale, rel.length() * 0.0028F);

      Vector3f tiltA = new Vector3f(node.axisNormal).mul(0.80F).add(new Vector3f(node.axisU).mul(0.60F)).normalize();
      Vector3f tiltB = new Vector3f(node.axisNormal).mul(0.76F).add(new Vector3f(node.axisV).mul(-0.65F)).normalize();
      g.torus(rel, tiltA, r1, minor, 24, 6, local * 0.42F * node.spinSign, MAGENTA[0], MAGENTA[1], MAGENTA[2], 0.72F * fade * nf);
      g.torus(rel, tiltB, r2, minor * 0.82F, 20, 6, -local * 0.58F * node.spinSign, DEEP_VIOLET[0], DEEP_VIOLET[1], DEEP_VIOLET[2], 0.65F * fade * nf);
   }

   /** 拧断临界拍的 3D 喉部空间激波锥。 */
   private void renderSnapCone(FxGeometry g, FxFrame frame, VortexNode node, float a) {
      float sinceSnap = node.sinceSnap(a);
      if (sinceSnap < 0.0F || sinceSnap > 6.0F) {
         return;
      }
      float k = sinceSnap / 6.0F;
      float fade = (1.0F - k) * (1.0F - k);
      Vector3f rel = frame.rel(node.center);
      float nf = nearFade(rel, 0.9F, 1.9F, 0.2F);
      float len = (0.75F + 1.65F * FxMath.easeOutCubic(k)) * this.scale;
      float rBase = Math.max(0.10F, node.blackHoleRadius(a, this.scale) * 0.8F);
      float rTop = (0.55F + 1.85F * FxMath.easeOutCubic(k)) * this.scale;

      Vector3f front = new Vector3f(rel).fma(len, node.axisNormal);
      Vector3f back = new Vector3f(rel).fma(-len, node.axisNormal);
      g.cylinder(rel, front, rBase, rTop, 18, sinceSnap * 0.4F * node.spinSign, MAGENTA[0], MAGENTA[1], MAGENTA[2], 0.68F * fade * nf);
      g.cylinder(rel, back, rBase, rTop * 0.85F, 18, -sinceSnap * 0.4F * node.spinSign, VIOLET[0], VIOLET[1], VIOLET[2], 0.52F * fade * nf);
   }

   /** 开眼白闪、拧断十字耀斑与塌缩归零针尖星芒。 */
   private void renderCoreFlares(FxGeometry g, FxFrame frame, VortexNode node, float a) {
      float local = a - node.startAge;
      Vector3f rel = frame.rel(node.center);
      float nf = nearFade(rel, 0.7F, 1.5F, 0.25F);

      // 开眼瞬闪
      if (local >= 0.0F && local < 3.0F) {
         float k = 1.0F - local / 3.0F;
         g.billboard(rel, (0.55F + 0.65F * (1.0F - k)) * this.scale, local * 0.3F, WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], k * k * 0.85F * nf);
      }
      // 拧断瞬间高能横纵交叉闪芒
      float sinceSnap = node.sinceSnap(a);
      if (sinceSnap >= 0.0F && sinceSnap < 4.5F) {
         float k = 1.0F - sinceSnap / 4.5F;
         float sz = (0.95F + 1.25F * FxMath.easeOutCubic(1.0F - k)) * this.scale;
         g.billboard(rel, sz, 0.0F, HOT_ORCHID[0], HOT_ORCHID[1], HOT_ORCHID[2], k * k * 0.92F * nf);
         g.billboard(rel, sz * 0.65F, (float)(Math.PI * 0.5), WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], k * k * 0.95F * nf);
      }
      // 吞噬收尾奇点归零星闪
      float sealStart = node.linkageMode ? WorldRiftPattern.LINKAGE_SUCK_END - 1.0F : WorldRiftPattern.SEAL_START_TICK;
      if (local >= sealStart && local < sealStart + 4.5F) {
         float k = FxMath.envelope(FxMath.window(local, sealStart, sealStart + 4.5F), 0.25F, 0.35F);
         g.billboard(rel, 0.85F * this.scale * (0.4F + 0.6F * k), (float)(Math.PI * 0.25), WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], k * 0.90F * nf);
      }
   }

   /**
    * 核心表现：3D 实体躯壳“拧毛巾式螺旋暴拧 → 撕裂 → 抽缩进漆黑奇点”。
    * 独立模式对完整抓拍躯壳沿纵轴施加高达 ±2.8π 的上下反向扭角；
    * 断空联动模式则承接断空的一刀两断切面，把上下两截残骸分别反旋卷成双股麻花吸入黑洞。
    */
   private void emitTwistedMesh(FxGeometry g, FxFrame frame, VortexNode node, float a, float meshAge, float sinceSnap, boolean bodyPass) {
      float[] quads = node.capturedQuads;
      if (quads == null || quads.length < 12) {
         return;
      }
      Vector3f relCenter = frame.rel(node.center);
      float nf = nearFade(relCenter, 0.65F, 1.45F, bodyPass ? 0.0F : 0.25F);
      if (nf <= 0.005F) {
         return;
      }

      float suckDur = node.suckDuration();
      float p = WorldRiftPattern.implodeProgress(sinceSnap, suckDur);
      float entryFade = node.linkageMode ? FxMath.easeOutCubic(FxMath.window(meshAge, 0.0F, 1.6F)) : 1.0F;
      float fade = entryFade * (1.0F - FxMath.window(sinceSnap, suckDur * 0.72F, suckDur)) * nf;
      if (fade <= 0.005F) {
         return;
      }

      float halfH = Math.max(0.42F, node.height * 0.5F);
      float centerOffsetY = node.height * 0.56F;
      // 暴拧角随时间呈非线性飙升：越拧越死、越抽越细；联动开场前 2t 先带轻微预拧震颤再爆旋
      float preTwist = node.linkageMode ? FxMath.window(meshAge, 0.0F, WorldRiftPattern.LINKAGE_SNAP_OFFSET) * 0.9F : 0.0F;
      float baseTwist = node.spinSign * (1.5F * (node.linkageMode ? (0.35F + 0.65F * preTwist) : 1.0F) + 8.8F * p);
      float globalSpin = node.spinSign * (0.6F * (node.linkageMode ? preTwist : 1.0F) + 6.4F * p * p);
      float tearStart = FxMath.window(sinceSnap, 1.5F, suckDur * 0.58F);
      float radialCollapse = Math.max(0.015F, (1.0F - p) * (1.0F - 0.35F * p));
      float verticalCollapse = Math.max(0.015F, (1.0F + 0.42F * (float)Math.sin(Math.PI * p)) * (1.0F - p * p));

      for (int i = 0, face = 0; i + 11 < quads.length; i += 12, face++) {
         float avgY = ((quads[i + 1] + quads[i + 4] + quads[i + 7] + quads[i + 10]) * 0.25F) - centerOffsetY;
         float ny = Mth.clamp(avgY / halfH, -1.2F, 1.2F);
         int band = Mth.clamp((int)((ny + 1.0F) * 3.0F), 0, 5);
         float cutSide = node.quadSides != null && face < node.quadSides.length ? node.quadSides[face] : (ny >= 0.0F ? 1.0F : -1.0F);

         // 断空联动：两截残骸先带着断空的分离偏移，再各自反向暴拧卷回奇点
         float splitGap = node.linkageMode ? (0.42F * (1.0F - p) * (1.0F - 0.5F * p) * cutSide) : (0.16F * tearStart * (1.0F - p) * cutSide);
         // 分层切向撕扯抖动，强化“空间把躯体拧断撕散”的破碎张力
         float bandTear = tearStart * (1.0F - p) * 0.22F * ((band % 2 == 0) ? 1.0F : -1.0F);

         this.transformTwistedVertex(
            quads[i], quads[i + 1] - centerOffsetY, quads[i + 2],
            halfH, baseTwist, globalSpin, radialCollapse, verticalCollapse, splitGap, bandTear, cutSide, node, relCenter, this.q0
         );
         this.transformTwistedVertex(
            quads[i + 3], quads[i + 4] - centerOffsetY, quads[i + 5],
            halfH, baseTwist, globalSpin, radialCollapse, verticalCollapse, splitGap, bandTear, cutSide, node, relCenter, this.q1
         );
         this.transformTwistedVertex(
            quads[i + 6], quads[i + 7] - centerOffsetY, quads[i + 8],
            halfH, baseTwist, globalSpin, radialCollapse, verticalCollapse, splitGap, bandTear, cutSide, node, relCenter, this.q2
         );
         this.transformTwistedVertex(
            quads[i + 9], quads[i + 10] - centerOffsetY, quads[i + 11],
            halfH, baseTwist, globalSpin, radialCollapse, verticalCollapse, splitGap, bandTear, cutSide, node, relCenter, this.q3
         );

         if (bodyPass) {
            g.rawQuad(this.q0, this.q1, this.q2, this.q3, DEEP_BODY[0], DEEP_BODY[1], DEEP_BODY[2], fade * 0.90F);
         } else {
            // 靠近拧断腰线/切口处点亮炙热兰粉白边，两端过渡为虚空紫与品红
            float seamHot = (float)Math.exp(-ny * ny * 7.5F);
            float r = FxMath.lerp(FxMath.lerp(VIOLET[0], MAGENTA[0], Math.abs(ny)), WHITE_HOT[0], seamHot * 0.72F);
            float gr = FxMath.lerp(FxMath.lerp(VIOLET[1], MAGENTA[1], Math.abs(ny)), WHITE_HOT[1], seamHot * 0.72F);
            float bl = FxMath.lerp(FxMath.lerp(VIOLET[2], MAGENTA[2], Math.abs(ny)), WHITE_HOT[2], seamHot * 0.72F);
            g.rawQuad(this.q0, this.q1, this.q2, this.q3, r, gr, bl, fade * (0.72F + 0.28F * seamHot));
         }
      }
   }

   private void transformTwistedVertex(
      float lx, float ly, float lz,
      float halfH, float baseTwist, float globalSpin,
      float radialScale, float verticalScale, float splitGap, float bandTear,
      float cutSide, VortexNode node, Vector3f relCenter, Vector3f out
   ) {
      float ny = Mth.clamp(ly / halfH, -1.25F, 1.25F);
      // 上下半身/断空两截按相反趋势拧转，拧出强烈麻花扭曲形态
      float localAngle = node.linkageMode
         ? (globalSpin * cutSide + baseTwist * (ny * 0.95F + 0.45F * cutSide))
         : (globalSpin + baseTwist * (ny * 1.15F + 0.30F * (float)Math.sin(ny * Math.PI)));
      float cs = (float)Math.cos(localAngle);
      float sn = (float)Math.sin(localAngle);

      // 拧紧处的腰部额外收细（像被空间捏断）
      float waistPinch = 1.0F - 0.45F * (float)Math.exp(-ny * ny * 3.2F) * (1.0F - radialScale);
      float rx = (lx * cs - lz * sn) * radialScale * waistPinch;
      float rz = (lx * sn + lz * cs) * radialScale * waistPinch;
      float ry = ly * verticalScale;

      out.set(relCenter.x + rx, relCenter.y + ry, relCenter.z + rz)
         .fma(splitGap, node.cutNormal)
         .fma(bandTear, node.cutDir);
   }

   /** 拧断时崩出并被反向吸回黑洞奇点的 3D 空间棱晶碎刃（bladeShard）。 */
   private void renderNodeShards(FxGeometry g, FxFrame frame, VortexNode node, float a, boolean bodyPass) {
      float sinceSnap = node.sinceSnap(a);
      if (sinceSnap < 0.0F || node.shards.length == 0) {
         return;
      }
      float suckDur = node.suckDuration() + 1.5F;
      if (sinceSnap >= suckDur) {
         return;
      }
      Vector3f relCenter = frame.rel(node.center);
      float nf = nearFade(relCenter, 0.7F, 1.5F, bodyPass ? 0.0F : 0.25F);
      if (nf <= 0.005F) {
         return;
      }

      float k = sinceSnap / suckDur;
      // 前 32% 瞬间外崩悬滞，后 68% 沿螺旋轨道高速倒吸进奇点黑洞
      float distProfile = k < 0.32F
         ? FxMath.easeOutCubic(k / 0.32F)
         : (1.0F - FxMath.easeInCubic((k - 0.32F) / 0.68F));
      float sizeScale = this.scale * (1.0F - 0.85F * FxMath.window(k, 0.55F, 1.0F));
      float alpha = (1.0F - FxMath.window(k, 0.78F, 1.0F)) * nf;
      if (alpha <= 0.005F || sizeScale <= 0.01F) {
         return;
      }

      for (int i = 0; i < node.shards.length; i++) {
         ShardSpec s = node.shards[i];
         float orbitAng = s.azimuth + node.spinSign * (k * 6.5F + s.spinSpeed * sinceSnap * 0.14F);
         float r = s.maxRadius * this.scale * distProfile;
         float elev = s.elevOffset * this.scale * distProfile;
         float cs = (float)Math.cos(orbitAng);
         float sn = (float)Math.sin(orbitAng);

         Vector3f pos = new Vector3f(relCenter)
            .fma(r * cs, node.axisU)
            .fma(r * sn, node.axisV)
            .fma(elev, node.axisNormal);

         // 碎刃尖端朝向螺旋切线 + 吸回阶段指向奇点中心
         Vector3f tangent = new Vector3f(node.axisU).mul(-sn * node.spinSign).fma(cs * node.spinSign, node.axisV);
         Vector3f toCenter = new Vector3f(relCenter).sub(pos);
         if (toCenter.lengthSquared() > 1.0E-5F) {
            toCenter.normalize();
         } else {
            toCenter.set(tangent);
         }
         float aimCenter = FxMath.window(k, 0.28F, 0.65F);
         Vector3f fwd = new Vector3f(tangent).lerp(toCenter, aimCenter);
         if (fwd.lengthSquared() < 1.0E-5F) {
            fwd.set(node.axisNormal);
         } else {
            fwd.normalize();
         }
         Vector3f right = FxMath.perpendicular(fwd);
         Vector3f up = new Vector3f(fwd).cross(right).normalize();

         float fLen = s.length * sizeScale;
         float bLen = fLen * 0.42F;
         float hw = fLen * 0.24F;
         float ht = fLen * 0.14F;
         float[] col = bodyPass ? DEEP_BODY : (i % 3 == 0 ? HOT_ORCHID : (i % 2 == 0 ? MAGENTA : VIOLET));
         g.bladeShard(pos, fwd, right, up, fLen, bLen, hw, ht, col[0], col[1], col[2], alpha * (bodyPass ? 0.92F : 0.95F));
      }
   }

   /** 持续沿三维螺线被抽进涡眼的品红/紫空间尘埃流丝。 */
   private void renderSpiralSparks(FxGeometry g, FxFrame frame, VortexNode node, float a) {
      float local = a - node.startAge;
      float life = node.vortexEnvelope(a);
      if (life <= 0.01F || node.sparks.length == 0) {
         return;
      }
      Vector3f relCenter = frame.rel(node.center);
      float nf = nearFade(relCenter, 0.75F, 1.6F, 0.25F);
      float maxR = node.effectRadius(this.scale) * 1.35F;

      for (int i = 0; i < node.sparks.length; i++) {
         SparkSpec sp = node.sparks[i];
         float phase = ((local * sp.speed + sp.phase) % 1.0F + 1.0F) % 1.0F;
         float headP = FxMath.easeInCubic(phase);
         float tailP = FxMath.easeInCubic(Math.max(0.0F, phase - 0.14F));

         float rHead = maxR * (1.0F - headP);
         float rTail = maxR * (1.0F - tailP);
         float angHead = sp.azimuth + node.spinSign * headP * 4.8F;
         float angTail = sp.azimuth + node.spinSign * tailP * 4.8F;

         Vector3f head = new Vector3f(relCenter)
            .fma(rHead * (float)Math.cos(angHead), node.axisU)
            .fma(rHead * (float)Math.sin(angHead), node.axisV)
            .fma(sp.axial * this.scale * (1.0F - headP), node.axisNormal);
         Vector3f tail = new Vector3f(relCenter)
            .fma(rTail * (float)Math.cos(angTail), node.axisU)
            .fma(rTail * (float)Math.sin(angTail), node.axisV)
            .fma(sp.axial * this.scale * (1.0F - tailP), node.axisNormal);

         float alpha = (float)Math.sin(Math.PI * phase) * life * nf;
         float[] col = i % 4 == 0 ? WHITE_HOT : (i % 2 == 0 ? MAGENTA : VIOLET);
         g.streak(head, tail, 0.024F * this.scale, col[0], col[1], col[2], alpha * 0.85F);
      }
   }

   /** 尾声收束（方案 A）：奇点内爆收缩为紫白针尖星芒归零，并向周围弹出一道克制的空间弹性回弹细环余波。 */
   private void renderImplosionCollapse(FxGeometry g, FxFrame frame, VortexNode node, float a) {
      float local = a - node.startAge;
      float implStart = node.linkageMode ? WorldRiftPattern.LINKAGE_SUCK_END - 1.5F : WorldRiftPattern.SEAL_START_TICK - 0.5F;
      float implEnd = Math.min((float)node.lifetimeTicks(), implStart + 7.5F);
      if (local < implStart || local >= implEnd) {
         return;
      }
      Vector3f rel = frame.rel(node.center);
      float nf = nearFade(rel, 0.7F, 1.5F, 0.25F);
      float prog = FxMath.window(local, implStart, implEnd);

      // 1. 奇点捏缩至针尖白热闪灭（prog 0.0 -> 0.42）
      if (prog < 0.44F) {
         float k = prog / 0.44F;
         float pinchAlpha = FxMath.envelope(k, 0.25F, 0.50F) * nf;
         if (pinchAlpha > 0.005F) {
            float coreSize = (0.58F * (1.0F - 0.72F * k)) * this.scale;
            g.begin(Style.GLOW, 9.2F, 0.0F, 6.0F, 3.0F, 1.85F, 0.0F);
            g.billboard(rel, coreSize, 0.0F, WHITE_HOT[0], WHITE_HOT[1], WHITE_HOT[2], pinchAlpha);
            g.billboard(rel, coreSize * 1.35F, (float)(Math.PI * 0.5), MAGENTA[0], MAGENTA[1], MAGENTA[2], pinchAlpha * 0.65F);
         }
      }

      // 2. 空间弹性回弹细环余波（prog 0.14 -> 1.0）：极细锐利品红/虚空紫波纹随空间复原向外弹散
      if (prog >= 0.14F) {
         float rk = (prog - 0.14F) / 0.86F;
         float ringAlpha = (1.0F - rk) * (1.0F - rk) * nf;
         if (ringAlpha > 0.005F) {
            float maxR = node.effectRadius(this.scale) * 0.92F;
            float rOuter = maxR * (0.14F + 0.86F * FxMath.easeOutCubic(rk));
            float rInner = maxR * (0.08F + 0.68F * FxMath.easeOutCubic(rk));
            g.begin(Style.RING, 4.4F, 0.12F, 0.86F, 0.018F, 0.0F, 0.0F);
            g.quad(rel, new Vector3f(node.axisU).mul(rOuter), new Vector3f(node.axisV).mul(rOuter),
               MAGENTA[0], MAGENTA[1], MAGENTA[2], ringAlpha * 0.78F);
            g.quad(rel, new Vector3f(frame.left).mul(rInner), new Vector3f(frame.up).mul(rInner),
               VIOLET[0], VIOLET[1], VIOLET[2], ringAlpha * 0.52F);
         }
      }
   }

   /** 单链跳跃时的虚空扭曲跃迁裂线 + 伴生双螺旋能量丝。 */
   private void renderHopBridges(FxGeometry g, FxFrame frame, float a) {
      g.begin(Style.RIBBON, 7.2F, 0.15F, 6.0F, 9.0F, 18.0F, 0.55F);
      for (HopBridge hop : this.hops) {
         float e = a - hop.startAge;
         if (e < 0.0F || e >= hop.duration) {
            continue;
         }
         float reveal = FxMath.easeOutCubic(FxMath.window(e, 0.0F, 2.5F));
         float fade = 1.0F - FxMath.window(e, hop.duration * 0.45F, hop.duration);
         this.drawHopBolt(g, frame, hop, reveal, fade, 0.055F * this.scale, MAGENTA, 0.0F);
         this.drawHopBolt(g, frame, hop, reveal, fade * 0.72F, 0.028F * this.scale, VIOLET, 1.0F);
      }

      g.begin(Style.RIBBON, 11.0F, 0.10F, 11.0F, 0.0F, 0.0F, 0.22F);
      for (HopBridge hop : this.hops) {
         float e = a - hop.startAge;
         if (e < 0.0F || e >= hop.duration) {
            continue;
         }
         float reveal = FxMath.easeOutCubic(FxMath.window(e, 0.0F, 2.5F));
         float fade = 1.0F - FxMath.window(e, hop.duration * 0.40F, hop.duration);
         this.drawHopBolt(g, frame, hop, reveal, fade, 0.018F * this.scale, WHITE_HOT, 0.0F);
      }
   }

   private void drawHopBolt(FxGeometry g, FxFrame frame, HopBridge hop, float reveal, float fade, float width, float[] color, float helixScale) {
      int count = Math.max(2, (int)Math.ceil(HOP_BOLT_POINTS * reveal));
      List<Vector3f> pts = new ArrayList<>(count);
      float[] widths = new float[count];
      float[] alphas = new float[count];
      Vec3 dir = hop.to.subtract(hop.from);
      Vector3f axis = dir.lengthSqr() > 1.0E-5 ? new Vector3f((float)dir.x, (float)dir.y, (float)dir.z).normalize() : new Vector3f(0.0F, 0.0F, 1.0F);
      Vector3f u = FxMath.perpendicular(axis);
      Vector3f v = new Vector3f(axis).cross(u).normalize();

      for (int i = 0; i < count; i++) {
         float k = (float)i / (HOP_BOLT_POINTS - 1);
         Vec3 base = WorldRiftPattern.hopArcPoint(hop.from, hop.to, k, hop.seed);
         Vector3f p = frame.rel(base);
         if (helixScale > 0.0F) {
            float env = (float)Math.sin(Math.PI * k) * 0.28F * this.scale * helixScale;
            float ang = k * 14.0F - frame.time * 22.0F;
            p.fma(env * (float)Math.cos(ang), u).fma(env * (float)Math.sin(ang), v);
         } else if (i > 0 && i < count - 1) {
            float jitter = (float)Math.sin(i * 11.7F + frame.time * 28.0F + (hop.seed & 255L)) * 0.14F * this.scale;
            p.fma(jitter, u);
         }
         pts.add(p);
         float shape = (float)Math.sin(Math.PI * Math.min(1.0F, k));
         widths[i] = Math.max(width * (0.35F + 0.65F * shape), p.length() * 0.0014F);
         alphas[i] = fade * nearFade(p, 0.7F, 1.5F, 0.25F) * (0.35F + 0.65F * shape);
      }
      g.ribbon(pts, widths, alphas, color[0], color[1], color[2]);
   }

   // ───────────────────────────── B 档全屏通道：神威屏幕扭曲 + 解析空间漏斗体积 ─────────────────────────────

   @Override
   public void collectPasses(FxFrame frame, List<FxPass> out) {
      float a = this.age(frame.partialTick);

      // 收集本帧所有可见神威奇点，按屏幕居中度与近距离打分排序
      List<ActiveVortex> sceneList = new ArrayList<>();
      float maxRebound = 0.0F;
      for (VortexNode node : this.nodes) {
         if (!node.isVisibleAt(a)) {
            continue;
         }
         float[] uv = frame.screenUv(node.center);
         if (uv == null || uv[0] < -0.35F || uv[0] > 1.35F || uv[1] < -0.35F || uv[1] > 1.35F) {
            continue;
         }
         Vector3f rel = frame.rel(node.center);
         float viewDist = rel.length();
         if (viewDist < 0.55F) {
            continue;
         }
         float worldR = node.warpWorldRadius(this.scale);
         float[] top = frame.screenUv(node.center.add(0.0, worldR, 0.0));
         float screenR = top != null ? Math.abs(top[1] - uv[1]) : (worldR / Math.max(1.2F, viewDist));
         screenR = Mth.clamp(screenR, 0.035F, 0.44F);

         VortexWarp warp = node.evaluateWarp(a, this.scale);
         if (Math.abs(warp.twist) < 0.02F && Math.abs(warp.suck) < 0.01F && warp.holeNorm < 0.005F) {
            continue;
         }
         maxRebound = Math.max(maxRebound, warp.reboundFlash);

         // 距离补偿：减去目标自身半径，避免活体正面像素被深度保护误屏蔽，同时继续挡住目标前方的墙体
         float occDist = Math.max(0.45F, viewDist - Math.max(0.65F, node.width * 0.85F));
         float centerBias = 1.0F / (0.25F + (float)Math.hypot(uv[0] - 0.5F, uv[1] - 0.5F));
         float score = (screenR * 3.0F + warp.intensity) * centerBias / (1.0F + viewDist * 0.04F);
         sceneList.add(new ActiveVortex(node, rel, uv[0], uv[1], screenR, occDist, warp, score));
      }

      if (sceneList.isEmpty()) {
         return;
      }
      sceneList.sort(Comparator.comparingDouble((ActiveVortex v) -> v.score).reversed());

      // 1. 场景通道：单 Pass 打包最多 8 个神威对数螺旋奇点
      if (FxShaders.woundScene != null) {
         int count = Math.min(MAX_SCENE_VORTICES, sceneList.size());
         float[][] packed = new float[4][16];
         for (int i = 0; i < count; i++) {
            ActiveVortex av = sceneList.get(i);
            float[] m = packed[i / 2];
            int base = (i % 2) * 8;
            m[base] = av.uvX;
            m[base + 1] = av.uvY;
            m[base + 2] = av.screenR;
            m[base + 3] = av.occDist;
            m[base + 4] = av.warp.twist;
            m[base + 5] = av.warp.suck;
            m[base + 6] = av.warp.holeNorm;
            m[base + 7] = av.warp.intensity;
         }
         Matrix4f matA = new Matrix4f().set(packed[0]);
         Matrix4f matB = new Matrix4f().set(packed[1]);
         Matrix4f matC = new Matrix4f().set(packed[2]);
         Matrix4f matD = new Matrix4f().set(packed[3]);
         float rebound = maxRebound;
         out.add(new FxPass(FxPass.Kind.SCENE, () -> FxShaders.woundScene, shader -> {
            shader.safeGetUniform("VortexA").set(matA);
            shader.safeGetUniform("VortexB").set(matB);
            shader.safeGetUniform("VortexC").set(matC);
            shader.safeGetUniform("VortexD").set(matD);
            shader.safeGetUniform("Meta").set((float)count, 1.05F, rebound, 1.0F);
         }));
      }

      // 2. 体积通道：单 Pass 打包最多 4 个 3D 解析空间漏斗与地形截交环
      if (FxShaders.woundVolume != null) {
         int volCount = Math.min(MAX_VOLUME_VORTICES, sceneList.size());
         float[][] volPacked = new float[2][16];
         for (int i = 0; i < volCount; i++) {
            ActiveVortex av = sceneList.get(i);
            float[] m = volPacked[i / 2];
            int base = (i % 2) * 8;
            float outerR = av.node.effectRadius(this.scale) * 1.18F;
            float holeR = Math.max(0.10F, av.node.blackHoleRadius(a, this.scale));
            float sinceSnap = av.node.sinceSnap(a);
            float snapPulse = sinceSnap >= 0.0F && sinceSnap < 4.5F ? (1.0F - sinceSnap / 4.5F) : 0.0F;
            m[base] = av.rel.x;
            m[base + 1] = av.rel.y;
            m[base + 2] = av.rel.z;
            m[base + 3] = outerR;
            m[base + 4] = 8.5F * av.node.spinSign;
            m[base + 5] = av.warp.intensity;
            m[base + 6] = holeR;
            m[base + 7] = snapPulse;
         }
         Matrix4f volA = new Matrix4f().set(volPacked[0]);
         Matrix4f volB = new Matrix4f().set(volPacked[1]);
         out.add(new FxPass(FxPass.Kind.VOLUME, () -> FxShaders.woundVolume, shader -> {
            shader.safeGetUniform("VolA").set(volA);
            shader.safeGetUniform("VolB").set(volB);
            shader.safeGetUniform("Meta").set((float)volCount, 1.0F, 0.0F, 0.0F);
         }));
      }
   }

   // ───────────────────────────── 屏幕冲击与镜头震动 ─────────────────────────────

   @Override
   public void screen(FxFrame frame, FxScreenState screen) {
      float a = this.age(frame.partialTick);
      float bestImpact = 0.0F;
      float bestDash = 0.0F;
      float bestAb = 0.0F;
      float bestVig = 0.0F;
      float bestFov = 0.0F;
      float[] bestUv = null;

      for (VortexNode node : this.nodes) {
         if (!node.isVisibleAt(a)) {
            continue;
         }
         double dist = frame.camera.distanceTo(node.center);
         if (dist > SCREEN_RANGE) {
            continue;
         }
         float atten = 1.0F - (float)(dist / SCREEN_RANGE);
         atten = atten * atten;
         float sinceSnap = node.sinceSnap(a);
         float local = a - node.startAge;

         // 活体拧紧期微弱暗角 + 视野轻微收束（聚焦空间压迫感）
         if (!node.linkageMode && local >= 0.0F && local < WorldRiftPattern.SNAP_TICK) {
            float k = WorldRiftPattern.liveTwistProgress(local);
            bestVig = Math.max(bestVig, 0.24F * k * atten);
            bestAb = Math.max(bestAb, 0.25F * k * atten);
            float fov = -0.045F * k * atten;
            if (Math.abs(fov) > Math.abs(bestFov)) {
               bestFov = fov;
            }
         }

         // 拧断爆发帧：1.6t 高反差黑白反相定格 + 向心速度线 + 色差脉冲
         if (sinceSnap >= 0.0F && sinceSnap < 7.0F) {
            float snapAtten = node.linkageMode ? atten * 0.85F : atten;
            if (sinceSnap < 1.6F) {
               float imp = (1.0F - sinceSnap / 1.6F) * 0.72F * snapAtten;
               bestImpact = Math.max(bestImpact, imp);
            }
            float dash = (1.0F - FxMath.window(sinceSnap, 0.0F, 5.5F)) * 0.72F * snapAtten;
            if (dash > bestDash) {
               bestDash = dash;
               bestUv = frame.screenUv(node.center);
            }
            bestAb = Math.max(bestAb, (1.0F - sinceSnap / 7.0F) * 0.85F * snapAtten);
            bestVig = Math.max(bestVig, (1.0F - sinceSnap / 7.0F) * 0.34F * snapAtten);
            float fovKick = (1.0F - FxMath.window(sinceSnap, 0.0F, 4.0F)) * 0.055F * snapAtten;
            if (Math.abs(fovKick) > Math.abs(bestFov)) {
               bestFov = fovKick;
            }
         }
      }

      if (bestImpact > 0.02F) {
         screen.addImpactFrame(bestImpact, true);
      }
      if (bestDash > 0.01F) {
         screen.addDashBlur(bestDash, bestUv);
      }
      if (bestAb > 0.01F) {
         screen.addAberration(bestAb);
      }
      if (bestVig > 0.01F) {
         screen.addVignette(0x280638, bestVig);
      }
      if (Math.abs(bestFov) > 0.002F) {
         screen.addFovKick(bestFov);
      }
   }

   @Override
   public float shake(FxFrame frame) {
      float a = this.age(frame.partialTick);
      float peak = 0.0F;
      for (VortexNode node : this.nodes) {
         if (!node.isVisibleAt(a)) {
            continue;
         }
         double dist = frame.camera.distanceTo(node.center);
         if (dist > SCREEN_RANGE) {
            continue;
         }
         float atten = 1.0F - (float)(dist / SCREEN_RANGE);
         float local = a - node.startAge;
         float sinceSnap = node.sinceSnap(a);
         if (!node.linkageMode && local >= 0.0F && local < WorldRiftPattern.SNAP_TICK) {
            peak = Math.max(peak, 0.16F * WorldRiftPattern.liveTwistProgress(local) * atten);
         }
         if (sinceSnap >= 0.0F && sinceSnap < 6.0F) {
            float k = 1.0F - sinceSnap / 6.0F;
            peak = Math.max(peak, (node.linkageMode ? 0.42F : 0.56F) * k * k * atten);
         }
      }
      return peak;
   }

   private static float nearFade(Vector3f rel, float from, float to, float floor) {
      return floor + (1.0F - floor) * FxMath.window(rel.length(), from, to);
   }

   // ───────────────────────────── 内部数据结构 ─────────────────────────────

   private record VortexWarp(float twist, float suck, float holeNorm, float intensity, float reboundFlash) {
   }

   private record ActiveVortex(
      VortexNode node, Vector3f rel, float uvX, float uvY, float screenR, float occDist, VortexWarp warp, float score
   ) {
   }

   private static final class HopBridge {
      final Vec3 from;
      final Vec3 to;
      final int startAge;
      final int duration;
      final long seed;

      HopBridge(Vec3 from, Vec3 to, int startAge, int duration, long seed) {
         this.from = from;
         this.to = to;
         this.startAge = startAge;
         this.duration = duration;
         this.seed = seed;
      }
   }

   private static final class ShardSpec {
      final float azimuth;
      final float maxRadius;
      final float elevOffset;
      final float length;
      final float spinSpeed;

      ShardSpec(RandomSource random) {
         this.azimuth = random.nextFloat() * FxMath.TAU;
         this.maxRadius = 1.15F + random.nextFloat() * 1.45F;
         this.elevOffset = (random.nextFloat() - 0.5F) * 1.25F;
         this.length = 0.24F + random.nextFloat() * 0.26F;
         this.spinSpeed = 2.0F + random.nextFloat() * 3.5F;
      }
   }

   private static final class SparkSpec {
      final float azimuth;
      final float axial;
      final float speed;
      final float phase;

      SparkSpec(RandomSource random) {
         this.azimuth = random.nextFloat() * FxMath.TAU;
         this.axial = (random.nextFloat() - 0.5F) * 1.35F;
         this.speed = 0.075F + random.nextFloat() * 0.065F;
         this.phase = random.nextFloat();
      }
   }

   private static final class VortexNode {
      final int entityId;
      Vec3 foot;
      Vec3 center;
      float width;
      float height;
      final Vector3f axisNormal;
      final Vector3f axisU;
      final Vector3f axisV;
      final Vector3f cutNormal;
      final Vector3f cutDir;
      final boolean linkageMode;
      final float spinSign;
      final float pitchJitter;
      final ShardSpec[] shards;
      final SparkSpec[] sparks;
      int startAge;
      int snapAge;
      boolean snapped;
      boolean openSoundPlayed;
      boolean tensionSoundPlayed;
      boolean snapSoundPlayed;
      boolean sealSoundPlayed;
      float[] capturedQuads;
      float[] quadSides;

      VortexNode(
         int entityId, Vec3 foot, Vec3 center, float width, float height,
         Vector3f axisNormal, Vector3f axisU, Vector3f axisV, Vector3f cutNormal, Vector3f cutDir,
         boolean linkageMode, float spinSign, int startAge, int snapAge,
         float[] capturedQuads, int shardCount, int sparkCount, RandomSource random
      ) {
         this.entityId = entityId;
         this.foot = foot;
         this.center = center;
         this.width = width;
         this.height = height;
         this.axisNormal = axisNormal;
         this.axisU = axisU;
         this.axisV = axisV;
         this.cutNormal = cutNormal;
         this.cutDir = cutDir;
         this.linkageMode = linkageMode;
         this.spinSign = spinSign;
         this.startAge = startAge;
         this.snapAge = snapAge;
         this.pitchJitter = random.nextFloat() - 0.5F;
         this.shards = new ShardSpec[shardCount];
         for (int i = 0; i < shardCount; i++) {
            this.shards[i] = new ShardSpec(random);
         }
         this.sparks = new SparkSpec[sparkCount];
         for (int i = 0; i < sparkCount; i++) {
            this.sparks[i] = new SparkSpec(random);
         }
         this.setQuads(capturedQuads);
      }

      void setQuads(float[] quads) {
         this.capturedQuads = quads;
         if (quads == null || quads.length < 12) {
            this.quadSides = null;
            return;
         }
         int faceCount = quads.length / 12;
         this.quadSides = new float[faceCount];
         float midY = this.height * 0.56F;
         for (int i = 0, face = 0; face < faceCount; i += 12, face++) {
            float cx = (quads[i] + quads[i + 3] + quads[i + 6] + quads[i + 9]) * 0.25F;
            float cy = (quads[i + 1] + quads[i + 4] + quads[i + 7] + quads[i + 10]) * 0.25F - midY;
            float cz = (quads[i + 2] + quads[i + 5] + quads[i + 8] + quads[i + 11]) * 0.25F;
            float dot = cx * this.cutNormal.x + cy * this.cutNormal.y + cz * this.cutNormal.z;
            this.quadSides[face] = dot >= 0.0F ? 1.0F : -1.0F;
         }
      }

      int lifetimeTicks() {
         return this.linkageMode ? WorldRiftPattern.LINKAGE_NODE_LIFETIME : WorldRiftPattern.NODE_LIFETIME;
      }

      float suckDuration() {
         return this.linkageMode
            ? (float)(WorldRiftPattern.LINKAGE_SUCK_END - WorldRiftPattern.LINKAGE_SNAP_OFFSET)
            : (float)(WorldRiftPattern.SUCK_END_TICK - WorldRiftPattern.SNAP_TICK);
      }

      int endAge() {
         if (this.startAge < 0) {
            return -1;
         }
         int baseEnd = this.startAge + this.lifetimeTicks();
         if (this.snapAge >= 0) {
            baseEnd = Math.max(baseEnd, this.snapAge + (int)this.suckDuration() + 8);
         }
         return baseEnd;
      }

      boolean isVisibleAt(float a) {
         return this.startAge >= 0 && a >= this.startAge && a <= this.endAge();
      }

      float sinceSnap(float a) {
         if (this.snapAge < 0) {
            return -1.0F;
         }
         return a - this.snapAge;
      }

      float effectRadius(float visualScale) {
         return Math.max(1.35F, Math.max(this.width * 1.45F, this.height * 0.92F)) * visualScale;
      }

      float warpWorldRadius(float visualScale) {
         return this.effectRadius(visualScale) * 2.15F;
      }

      float openFactor(float a) {
         float local = a - this.startAge;
         float openDur = this.linkageMode ? 2.0F : (float)WorldRiftPattern.OPEN_END_TICK;
         return FxMath.easeOutCubic(FxMath.window(local, 0.0F, openDur));
      }

      float vortexEnvelope(float a) {
         float local = a - this.startAge;
         float sealStart = this.linkageMode ? WorldRiftPattern.LINKAGE_SUCK_END - 1.5F : WorldRiftPattern.SEAL_START_TICK;
         float open = this.openFactor(a);
         float close = 1.0F - FxMath.easeInCubic(FxMath.window(local, sealStart - 1.5F, sealStart + 2.5F));
         return open * close;
      }

      float blackHoleRadius(float a, float visualScale) {
         float env = this.vortexEnvelope(a);
         if (env <= 0.005F) {
            return 0.0F;
         }
         float sinceSnap = this.sinceSnap(a);
         float base = Math.max(0.26F, Math.min(0.68F, this.width * 0.36F)) * visualScale;
         if (sinceSnap >= 0.0F) {
            float burst = 1.0F + 0.52F * FxMath.envelope(FxMath.window(sinceSnap, 0.0F, 5.0F), 0.2F, 0.55F);
            return base * env * burst;
         }
         float twist = WorldRiftPattern.liveTwistProgress(a - this.startAge);
         return base * env * (0.48F + 0.62F * twist);
      }

      /** 计算传递给 fx_wound_scene 的神威多圈螺旋扭角、向心抽吸、漆黑穿孔与弹性回弹。 */
      VortexWarp evaluateWarp(float a, float visualScale) {
         float local = a - this.startAge;
         float sinceSnap = this.sinceSnap(a);
         float sealStart = this.linkageMode ? WorldRiftPattern.LINKAGE_SUCK_END - 1.0F : WorldRiftPattern.SEAL_START_TICK;
         float reboundStart = sealStart;
         float reboundEnd = sealStart + 4.5F;

         // 弹性回弹段：空间瞬间反向微过冲再平复
         if (local >= reboundStart && local < reboundEnd) {
            float rProg = (local - reboundStart) / (reboundEnd - reboundStart);
            float bounce = (float)Math.sin(Math.PI * rProg) * (1.0F - rProg);
            return new VortexWarp(
               -this.spinSign * 2.4F * bounce,
               -0.16F * bounce,
               0.0F,
               0.65F * bounce,
               bounce
            );
         }
         if (local >= reboundEnd) {
            return new VortexWarp(0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
         }

         float open = this.openFactor(a);
         float close = 1.0F - FxMath.easeInCubic(FxMath.window(local, sealStart - 2.0F, sealStart));
         float env = open * close;
         if (env <= 0.005F) {
            return new VortexWarp(0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
         }

         float twistMag;
         float suckMag;
         float holeNorm;
         float intensity;
         if (sinceSnap >= 0.0F) {
            float impl = FxMath.window(sinceSnap, 0.0F, this.suckDuration());
            float peak = 1.0F - 0.35F * impl;
            twistMag = (20.0F + 8.0F * (1.0F - FxMath.window(sinceSnap, 0.0F, 4.0F))) * peak * env;
            suckMag = (0.76F + 0.16F * (1.0F - impl)) * env;
            holeNorm = (0.16F + 0.06F * (1.0F - FxMath.window(sinceSnap, 0.0F, 4.5F))) * env;
            intensity = (1.10F + 0.35F * (1.0F - FxMath.window(sinceSnap, 0.0F, 3.5F))) * env;
         } else {
            // Route A 活体强扭期：扭角从 5 rad 飙升至 22 rad（约 3.5 圈），直接把活体怪和背景拧成神威螺旋
            float prog = WorldRiftPattern.liveTwistProgress(local);
            twistMag = (5.0F + 17.5F * prog) * open;
            suckMag = (0.22F + 0.56F * prog) * open;
            holeNorm = (0.04F + 0.11F * prog) * open;
            intensity = (0.52F + 0.60F * prog) * open;
         }

         return new VortexWarp(this.spinSign * twistMag, suckMag, holeNorm, intensity, 0.0F);
      }
   }
}
