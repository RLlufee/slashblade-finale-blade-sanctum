package QWQ.QingYi.annihilationblade.annihilation_blade.logic;

import QWQ.QingYi.annihilationblade.Annihilationblade;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.CausalityCollapse;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.Dankong;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.PhantomJudgement;
import QWQ.QingYi.annihilationblade.annihilation_blade.specialeffect.VoidDominion;
import QWQ.QingYi.annihilationblade.config.ModConfig;
import QWQ.QingYi.annihilationblade.registry.ModSpecialEffects;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.event.SlashBladeEvent.DoSlashEvent;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.util.InputCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 湮灭之刃服务端输入并发路由器。
 * <p>
 * 负责统一监听拔刀剑 {@code InputCommandEvent}（左/右键按键状态）与 {@link DoSlashEvent}，
 * 在「虚无权域（左键 + 右键同时按）」就绪时提供默认 2 tick 并发缓冲窗口，避免人类按键相差 1~2 帧时
 * 误先起手「断空」、「因果坍缩」或「幻影审判」；当虚无权域未挂载、关闭或处于冷却期时，全部单键 SE 保持 0 帧直出。
 */
@EventBusSubscriber(modid = Annihilationblade.MODID)
public final class AnnihilationInputRouter {
   /**
    * 兼容新旧版本 SlashBlade Resharped 的 InputCommandEvent 包路径：
    * 1.9.x 新版位于 event.handler，旧版位于 event。害苦了我啊！
    */
   private static final String[] INPUT_EVENT_CANDIDATES = {
      "mods.flammpfeil.slashblade.event.handler.InputCommandEvent",
      "mods.flammpfeil.slashblade.event.InputCommandEvent"
   };
   private static final Map<UUID, PlayerInputState> STATES = new HashMap<>();
   private static boolean inputListenerRegistered;

   private AnnihilationInputRouter() {
   }

   /** 运行时探测并动态绑定拔刀剑 InputCommandEvent，集中驱动左键、右键与双键组合技。 */
   public static void registerInputListener() {
      if (inputListenerRegistered) {
         return;
      }
      ClassLoader loader = AnnihilationInputRouter.class.getClassLoader();
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      for (String className : INPUT_EVENT_CANDIDATES) {
         try {
            Class<?> rawClass = Class.forName(className, false, loader);
            if (!Event.class.isAssignableFrom(rawClass)) {
               continue;
            }
            MethodHandle getEntity = lookup.findVirtual(rawClass, "getEntity", MethodType.methodType(ServerPlayer.class));
            MethodHandle getOld = lookup.findVirtual(rawClass, "getOld", MethodType.methodType(EnumSet.class));
            MethodHandle getCurrent = lookup.findVirtual(rawClass, "getCurrent", MethodType.methodType(EnumSet.class));
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>)rawClass;
            bindInputEvent(eventClass, getEntity, getOld, getCurrent);
            inputListenerRegistered = true;
            Annihilationblade.LOGGER.info("[AnnihilationBlade] InputRouter bound InputCommandEvent to {}", className);
            return;
         } catch (ClassNotFoundException ignored) {
            // 继续尝试另一版本包路径
         } catch (ReflectiveOperationException exception) {
            Annihilationblade.LOGGER.warn("[AnnihilationBlade] Failed to inspect {}: {}", className, exception.toString());
         }
      }
      Annihilationblade.LOGGER.error("[AnnihilationBlade] Unable to locate SlashBlade InputCommandEvent; left/chord input routing disabled.");
   }

   private static <T extends Event> void bindInputEvent(
      Class<T> eventClass, MethodHandle getEntity, MethodHandle getOld, MethodHandle getCurrent
   ) {
      MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, eventClass, event -> {
         try {
            ServerPlayer player = (ServerPlayer)getEntity.invoke(event);
            EnumSet<?> oldCommands = (EnumSet<?>)getOld.invoke(event);
            EnumSet<?> currentCommands = (EnumSet<?>)getCurrent.invoke(event);
            handleInputCommand(player, oldCommands, currentCommands);
         } catch (Throwable throwable) {
            Annihilationblade.LOGGER.error("[AnnihilationBlade] Error handling InputCommandEvent", throwable);
         }
      });
   }

   public static void clearPlayer(UUID playerId) {
      STATES.remove(playerId);
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      STATES.clear();
   }

   private static void handleInputCommand(ServerPlayer player, EnumSet<?> oldCommands, EnumSet<?> currentCommands) {
      if (player == null || oldCommands == null || currentCommands == null || player.isSpectator() || !player.isAlive()) {
         return;
      }
      boolean oldLeft = oldCommands.contains(InputCommand.L_DOWN);
      boolean oldRight = oldCommands.contains(InputCommand.R_DOWN);
      boolean curLeft = currentCommands.contains(InputCommand.L_DOWN);
      boolean curRight = currentCommands.contains(InputCommand.R_DOWN);

      PlayerInputState input = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerInputState());
      input.player = player;
      input.leftDown = curLeft;
      input.rightDown = curRight;

      ISlashBladeState bladeState = activeBladeState(player);
      if (bladeState == null) {
         input.clearPending();
         return;
      }

      long now = player.level().getGameTime();
      int chordWindow = Math.max(0, ModConfig.COMMON.annihilationBlade.voidDominion.chordWindowTicks.getValue());
      boolean bothOld = oldLeft && oldRight;
      boolean bothNow = curLeft && curRight;

      // 1. 左键 + 右键同时成立上升沿：最高优先级触发「虚无权域」，并吞掉同一窗口内的单键缓冲
      if (bothNow && !bothOld) {
         if (Dankong.isActive(player)) {
            return;
         }
         input.clearPending();
         if (bladeState.hasSpecialEffect(ModSpecialEffects.VOID_DOMINION.getId())
               && ModConfig.COMMON.annihilationBlade.voidDominion.enabled.getValue()) {
            input.chordConsumedUntilTick = now + Math.max(2, chordWindow);
            VoidDominion.tryTriggerFromChord(player, bladeState);
         }
         return;
      }

      // 2. 纯左键按下沿（当前未按住右键）：触发「幻影审判」，虚无权域就绪时延迟 chordWindow 等待是否凑成双键
      if (!oldLeft && curLeft && !curRight) {
         if (now <= input.chordConsumedUntilTick || Dankong.isActive(player)) {
            return;
         }
         if (!bladeState.hasSpecialEffect(ModSpecialEffects.PHANTOM_JUDGEMENT.getId())
               || !ModConfig.COMMON.annihilationBlade.phantomJudgement.enabled.getValue()) {
            return;
         }
         if (chordWindow > 0 && VoidDominion.canAcceptChord(player, bladeState)) {
            input.pendingLeftExpireTick = now + chordWindow;
         } else {
            PhantomJudgement.tryTriggerFromInput(player);
         }
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public static void onDoingSlash(DoSlashEvent event) {
      if (!(event.getUser() instanceof ServerPlayer player) || player.level().isClientSide() || Dankong.isActive(player)) {
         return;
      }
      ISlashBladeState state = event.getSlashBladeState();
      if (state == null || state.isBroken() || state.isSealed()) {
         return;
      }
      boolean hasDankong = state.hasSpecialEffect(ModSpecialEffects.DANKONG.getId());
      boolean hasCausality = state.hasSpecialEffect(ModSpecialEffects.CAUSALITY_COLLAPSE.getId());
      boolean hasVoid = state.hasSpecialEffect(ModSpecialEffects.VOID_DOMINION.getId());
      if (!hasDankong && !hasCausality && !hasVoid) {
         return;
      }

      long now = player.level().getGameTime();
      PlayerInputState input = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerInputState());
      input.player = player;
      if (now <= input.chordConsumedUntilTick) {
         return;
      }

      int chordWindow = Math.max(0, ModConfig.COMMON.annihilationBlade.voidDominion.chordWindowTicks.getValue());
      // 若虚无权域当前已就绪：先挂起 2 tick，防止右键早按 1~2 帧导致断空或因果坍缩抢跑
      if (chordWindow > 0 && VoidDominion.canAcceptChord(player, state)) {
         // 若在同 tick 内双键已同时处于按下态，则直接走虚无权域而不必再挂起
         if (input.leftDown && input.rightDown) {
            input.clearPending();
            input.chordConsumedUntilTick = now + Math.max(2, chordWindow);
            VoidDominion.tryTriggerFromChord(player, state);
            return;
         }
         input.pendingSlashExpireTick = now + chordWindow;
         return;
      }

      // 若断空当前关闭且裂界单链就绪：挂起 2 tick 等待本次挥刀是否命中敌人，避免贴脸命中瞬间抢先在准星前空挥出一道多余裂痕；
      // 若已通过原版 Shift 锁定中远程目标（> 4.5 格，近战刀光不可能命中），则免等待 0 帧瞬发远程狙杀。
      if (hasCausality
            && state.hasSpecialEffect(ModSpecialEffects.WORLD_RIFT.getId())
            && Dankong.isDisabledFor(player, state)
            && WorldRiftChain.isStandaloneReady(player, now)
            && !CausalityCollapse.hasRemoteLockOnTarget(player, state)) {
         input.pendingSlashExpireTick = now + 2L;
         return;
      }

      dispatchSlashEffects(player, state);
   }

   @SubscribeEvent
   public static void onServerTick(TickEvent.ServerTickEvent event) {
      if (event.phase != TickEvent.Phase.END || STATES.isEmpty()) {
         return;
      }
      Iterator<Map.Entry<UUID, PlayerInputState>> iterator = STATES.entrySet().iterator();
      while (iterator.hasNext()) {
         PlayerInputState input = iterator.next().getValue();
         ServerPlayer player = input.player;
         if (player == null || player.isRemoved() || !player.isAlive()) {
            iterator.remove();
            continue;
         }

         long now = player.level().getGameTime();
         boolean hasLeftPending = input.pendingLeftExpireTick >= 0L;
         boolean hasSlashPending = input.pendingSlashExpireTick >= 0L;
         if (!hasLeftPending && !hasSlashPending) {
            continue;
         }

         ISlashBladeState bladeState = activeBladeState(player);
         if (bladeState == null) {
            input.clearPending();
            continue;
         }

         if (hasLeftPending && now >= input.pendingLeftExpireTick) {
            input.pendingLeftExpireTick = -1L;
            if (!input.rightDown && now > input.chordConsumedUntilTick && !Dankong.isActive(player)) {
               PhantomJudgement.tryTriggerFromInput(player);
            }
         }

         if (hasSlashPending && now >= input.pendingSlashExpireTick) {
            input.pendingSlashExpireTick = -1L;
            if (!(input.leftDown && input.rightDown) && now > input.chordConsumedUntilTick && !Dankong.isActive(player)) {
               dispatchSlashEffects(player, bladeState);
            }
         }
      }
   }

   /**
    * 确认非左右键双按后，按优先级派发普通挥刀系 SE：
    * 1) 优先尝试启动「断空」（若有合法目标且断空开启，后续收刀后将自动衔接裂界神威与因果坍缩联动）；
    * 2) 若断空未启动且当前未处于「裂界」单链跳跃中，则派发「因果坍缩」独立挥刀（智能准星锁敌或自由空挥）。
    */
   private static void dispatchSlashEffects(ServerPlayer player, ISlashBladeState state) {
      if (Dankong.tryTriggerFromSlash(player, state)) {
         return;
      }
      if (!WorldRiftChain.isActiveFor(player)) {
         CausalityCollapse.tryStandaloneSlash(player, state);
      }
   }

   private static ISlashBladeState activeBladeState(ServerPlayer player) {
      ItemStack blade = player.getMainHandItem();
      if (blade.isEmpty()) {
         return null;
      }
      ISlashBladeState state = blade.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
      if (state == null || state.isBroken() || state.isSealed()) {
         return null;
      }
      return state;
   }

   private static final class PlayerInputState {
      ServerPlayer player;
      boolean leftDown;
      boolean rightDown;
      long pendingLeftExpireTick = -1L;
      long pendingSlashExpireTick = -1L;
      long chordConsumedUntilTick = -1L;

      void clearPending() {
         this.pendingLeftExpireTick = -1L;
         this.pendingSlashExpireTick = -1L;
      }
   }
}
