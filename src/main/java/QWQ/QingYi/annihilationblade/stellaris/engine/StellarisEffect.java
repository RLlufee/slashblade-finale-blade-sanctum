package QWQ.QingYi.annihilationblade.stellaris.engine;

import QWQ.QingYi.annihilationblade.render.client.core.FxFrame;
import QWQ.QingYi.annihilationblade.render.client.geometry.FxGeometry;
import QWQ.QingYi.annihilationblade.stellaris.optics.StellarisOpticsState;
import QWQ.QingYi.annihilationblade.stellaris.particles.StellarisParticles;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 星穹引擎（Stellaris Engine）客户端特效实例基类。
 * <p>
 * 专为无尽星空次世代多阶段帧图设计，每个实例可自由参与：
 * <ul>
 *   <li>预乘 Alpha 暗部本体层（{@link #renderBody}）；</li>
 *   <li>HDR 几何层（{@link #renderGeometry}）与纯客户端物理粒子发射（{@link #emitParticles}）；</li>
 *   <li>低分辨率 3 附件重型 MRT Pass 与全分辨率线性 HDR 场景改写 Pass（{@link #collectPasses}）；</li>
 *   <li>电影级光学演出控制（变形星芒、体积光、自动曝光、Hold-Frame 残像、12fps 冲击帧、镜头震动与 FOV）。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public abstract class StellarisEffect {
   protected int age;
   protected int lifetime;
   private int handle = -1;

   protected StellarisEffect(int lifetime) {
      this.lifetime = Math.max(1, lifetime);
   }

   public final void bindSustained(int handle, int elapsed, int duration) {
      this.handle = handle;
      this.age = Math.max(0, elapsed);
      this.onSustainedStart(duration);
   }

   public final int handle() {
      return this.handle;
   }

   protected void onSustainedStart(int duration) {
      this.lifetime = Math.max(this.lifetime, duration);
   }

   public void onSustainedStop() {
      this.lifetime = Math.min(this.lifetime, this.age + 1);
   }

   public void extendLife(int ticks) {
      this.lifetime = Math.max(this.lifetime, this.age + Math.max(1, ticks));
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

   /** 每客户端 tick 向纯客户端粒子池注入物理粒子。 */
   public void emitParticles(ClientLevel level, StellarisParticles particles) {
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
         this.emitParticles(level, particles);
      } else {
         this.tickFallback(level);
      }
      this.age++;
   }

   public boolean finished() {
      return this.age >= this.lifetime;
   }

   public float age(float partialTick) {
      return Math.min(this.age + partialTick, this.lifetime);
   }

   public int ageTicks() {
      return this.age;
   }
}
