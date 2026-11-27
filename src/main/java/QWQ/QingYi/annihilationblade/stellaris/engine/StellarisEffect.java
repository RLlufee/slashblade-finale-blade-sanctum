package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.stellaris.optics.StellarisOpticsState;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisParticles;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisRibbons;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹引擎（Stellaris Engine）客户端特效实例基类（Step 1.6 升级版）。
 * <p>
 * 新增能力：
 * <ul>
 *   <li>平滑取消淡出生命周期（{@link #beginCancelFade(int)} / {@link #cancelAlpha(float)}），持续技能中断或替换时优雅收拢绝不 1 帧硬断；</li>
 *   <li>3D 物理圆管浮雕法阵提交钩子（{@link #collectReliefArrays}）；</li>
 *   <li>GPU 实例化 3D 能量丝带/虹吸轨迹提交钩子（{@link #collectRibbons}）；</li>
 *   <li>前透明阶段（{@code AFTER_BLOCK_ENTITIES}）提前渲染钩子（{@link #renderPreTranslucent}），供极少数需要写入水/玻璃前的星穹基元使用。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public abstract class StellarisEffect {
   protected int age;
   protected int lifetime;
   private int handle = -1;
   private boolean cancelling;
   private int cancelAge;
   private int cancelDuration;

   protected StellarisEffect(int lifetime) {
      this.lifetime = Math.max(1, lifetime);
   }

   public final void bindSustained(int handle, int elapsed, int duration) {
      this.handle = handle;
      this.age = Math.max(0, elapsed);
      this.cancelling = false;
      this.cancelAge = 0;
      this.cancelDuration = 0;
      this.onSustainedStart(duration);
   }

   public final int handle() {
      return this.handle;
   }

   /** 解除服务端持续句柄绑定，使其以独立平滑淡出残影形态自然收尾（避免被新技能覆盖时瞬间消失）。 */
   final void detachHandleForFade(int fadeTicks) {
      this.handle = -1;
      this.beginCancelFade(fadeTicks);
   }

   protected void onSustainedStart(int duration) {
      this.lifetime = Math.max(this.lifetime, duration);
   }

   /** 持续特效被服务端停止时默认进入 10 tick（0.5s）平滑淡出收尾，不再 1 tick 硬砍闪灭。 */
   public void onSustainedStop() {
      this.beginCancelFade(10);
   }

   /** 主动进入平滑取消淡出阶段（若已在更短的淡出中则保持现有节奏）。 */
   public final void beginCancelFade(int fadeTicks) {
      int duration = Math.max(1, fadeTicks);
      if (!this.cancelling) {
         this.cancelling = true;
         this.cancelAge = 0;
         this.cancelDuration = duration;
      } else {
         int remaining = Math.max(1, this.cancelDuration - this.cancelAge);
         if (duration < remaining) {
            this.cancelDuration = this.cancelAge + duration;
         }
      }
      this.lifetime = Math.max(this.lifetime, this.age + Math.max(1, this.cancelDuration - this.cancelAge));
   }

   public final boolean isCancelling() {
      return this.cancelling;
   }

   /** 返回取消淡出系数（正常态恒为 {@code 1.0}，淡出期间按平方缓动丝滑归零）。 */
   public final float cancelAlpha(float partialTick) {
      if (!this.cancelling || this.cancelDuration <= 0) {
         return 1.0F;
      }
      float t = Math.max(0.0F, Math.min(1.0F, (this.cancelAge + partialTick) / (float)this.cancelDuration));
      float remain = 1.0F - t;
      return remain * remain;
   }

   public void extendLife(int ticks) {
      if (!this.cancelling) {
         this.lifetime = Math.max(this.lifetime, this.age + Math.max(1, ticks));
      }
   }

   public abstract Vec3 focus();

   public double cullDistance() {
      return 220.0;
   }

   public void tick(ClientLevel level) {
   }

   /** 引擎熔断或处于 FALLBACK 档时的客户端降级粒子表现。 */
   public void tickFallback(ClientLevel level) {
   }

   /** 每客户端 tick 向纯客户端 TBO 物理粒子池注入粒子。 */
   public void emitParticles(ClientLevel level, StellarisParticles particles) {
   }

   /** 每帧向 GPU 实例化 3D 能量丝带池注入动态虹吸线/星轨连线/电弧丝带。 */
   public void collectRibbons(FxFrame frame, StellarisRibbons ribbons) {
   }

   /** 每帧向 3D 物理圆管浮雕法阵通道（ReliefPass）提交立体天体仪描述符。 */
   public void collectReliefArrays(FxFrame frame, StellarisQuality.Spec quality, List<StellarisReliefArray> out) {
   }

   /** 可选：在 {@code AFTER_BLOCK_ENTITIES}（不透明世界刚画完、水与半透明块前）执行的前置钩子。 */
   public void renderPreTranslucent(FxFrame frame) {
   }

   public boolean hasBody() {
      return false;
   }

   public void renderBody(FxGeometry body, FxFrame frame) {
   }

   public void renderGeometry(FxGeometry geometry, FxFrame frame) {
   }

   public void collectPasses(FxFrame frame, StellarisQuality.Spec quality, List<StellarisPass> out) {
   }

   public void contributeOptics(FxFrame frame, StellarisOpticsState optics) {
   }

   public float shake(FxFrame frame) {
      return 0.0F;
   }

   public void onRemoved() {
   }

   final void advance(ClientLevel level, boolean engineActive, StellarisParticles particles) {
      this.tick(level);
      if (engineActive) {
         if (!this.cancelling) {
            this.emitParticles(level, particles);
         }
      } else if (!this.cancelling) {
         this.tickFallback(level);
      }
      this.age++;
      if (this.cancelling) {
         this.cancelAge++;
      }
   }

   public boolean finished() {
      if (this.cancelling && this.cancelAge >= this.cancelDuration) {
         return true;
      }
      return this.age >= this.lifetime;
   }

   public float age(float partialTick) {
      return Math.min(this.age + partialTick, this.lifetime);
   }

   public int ageTicks() {
      return this.age;
   }
}
