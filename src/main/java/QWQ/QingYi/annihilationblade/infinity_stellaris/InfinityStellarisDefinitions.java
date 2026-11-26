package QWQ.QingYi.annihilationblade.infinity_stellaris;

import QWQ.QingYi.annihilationblade.common.NamedBladeStacks;
import java.util.Map;
import javax.annotation.Nullable;
import mods.flammpfeil.slashblade.client.renderer.CarryType;
import mods.flammpfeil.slashblade.init.SBItems;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

/**
 * 无尽星空（Infinity Stellaris）刀体标准定义与无损运行期维护工具。
 * <p>
 * <ul>
 *   <li>基础攻击力三处统一为 {@link #BASE_ATTACK_MODIFIER}（150.0F，数据包 / 定义代码 / Tooltip 动态读取一致）；</li>
 *   <li>运行期仅做脏检查，<strong>绝不覆写</strong>玩家通过铁砧或命令移植到刀上的外部 SE 与自定义 SA；</li>
 *   <li>旧存档中遗留的 {@code 1000000.0F} 或 {@code 214748367.0F} 异常面板在首次手持时通过一次性标志安全迁移。</li>
 * </ul>
 */
public final class InfinityStellarisDefinitions {
   public static final String NAME = "infinity_stellaris";
   public static final String DESCRIPTION_ID = "item.annihilationblade.infinity_stellaris";
   public static final String IDENTITY_TAG = "IsInfinityStellaris";
   /** 三处统一的基础面板攻击力（D6 定案，湮灭之刃 50.0F 的 3 倍，规避 float 精度溢出并完整保留技能闭环）。 */
   public static final float BASE_ATTACK_MODIFIER = 150.0F;
   public static final int MAX_DAMAGE = 2000;
   private static final String STATS_V3_MIGRATED_KEY = "InfinityStellarisStatsV3Migrated";
   private static final ResourceLocation BLADE_MODEL = ResourceLocation.fromNamespaceAndPath("annihilationblade", "model/infinity_stellaris.obj");
   private static final ResourceLocation BLADE_TEXTURE = ResourceLocation.fromNamespaceAndPath("annihilationblade", "model/infinity_stellaris.png");
   private static final ResourceLocation VACUUM_DECAY_COLLAPSE = ResourceLocation.fromNamespaceAndPath("annihilationblade", "vacuum_decay_collapse");
   private static final int SUMMONED_SWORD_COLOR = 4248287;
   private static final String[] INFINITY_SPECIAL_EFFECTS = new String[]{
      "annihilationblade:entropy_dissolution",
      "annihilationblade:curvature_rupture",
      "annihilationblade:gamma_thunderburst",
      "annihilationblade:cosmic_string_cut"
   };

   private InfinityStellarisDefinitions() {
   }

   public static ItemStack createStack() {
      return createStack(null);
   }

   public static ItemStack createStack(@Nullable Level level) {
      ItemStack datapackStack = NamedBladeStacks.get(level, NAME);
      if (!datapackStack.isEmpty()) {
         ensureRuntimeStats(datapackStack);
         return datapackStack;
      }

      ItemStack stack = new ItemStack(SBItems.slashblade);
      applyFallbackStats(stack);
      return stack;
   }

   public static void ensureStats(ItemStack stack) {
      if (!stack.isEmpty()) {
         ensureRuntimeStats(stack);
      }
   }

   public static void ensureStats(ItemStack stack, @Nullable Level level) {
      if (!stack.isEmpty()) {
         ensureRuntimeStats(stack);
      }
   }

   /** 兜底初始化（仅在无法读取数据包定义时使用），与数据包 infinity_stellaris.json 100% 对齐。 */
   private static void applyFallbackStats(ItemStack stack) {
      if (stack.isEmpty()) {
         return;
      }
      CompoundTag tag = stack.getOrCreateTag();
      ensureIdentityDirty(stack);
      tag.putString("SlashArts", VACUUM_DECAY_COLLAPSE.toString());
      tag.putBoolean(STATS_V3_MIGRATED_KEY, true);
      applyEnchantments(stack);
      stack.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
         state.setTranslationKey(DESCRIPTION_ID);
         state.setSlashArtsKey(VACUUM_DECAY_COLLAPSE);
         state.setBaseAttackModifier(BASE_ATTACK_MODIFIER);
         state.setMaxDamage(MAX_DAMAGE);
         state.setDefaultBewitched(true);
         state.setCarryType(CarryType.NINJA);
         state.setModel(BLADE_MODEL);
         state.setTexture(BLADE_TEXTURE);
         state.setColorCode(SUMMONED_SWORD_COLOR);
         state.setSpecialEffects(createSpecialEffects());
         state.setBroken(false);
         tag.put("bladeState", state.serializeNBT());
      });
   }

   /**
    * 运行期无损脏检查：
    * 仅补全身份/外观/不可破坏字段并在首次遇到旧存档超模数值时执行一次性迁移，
    * 绝不覆写玩家自行移植的外部 SE、自定义 SA 或成长统计。
    */
   private static void ensureRuntimeStats(ItemStack stack) {
      boolean tagDirty = ensureIdentityDirty(stack);
      CompoundTag tag = stack.getOrCreateTag();
      boolean migrated = tag.getBoolean(STATS_V3_MIGRATED_KEY);

      stack.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
         boolean stateDirty = tagDirty;

         if (!DESCRIPTION_ID.equals(state.getTranslationKey())) {
            state.setTranslationKey(DESCRIPTION_ID);
            stateDirty = true;
         }
         if (!state.isDefaultBewitched()) {
            state.setDefaultBewitched(true);
            stateDirty = true;
         }
         if (!BLADE_MODEL.equals(state.getModel().orElse(null))) {
            state.setModel(BLADE_MODEL);
            stateDirty = true;
         }
         if (!BLADE_TEXTURE.equals(state.getTexture().orElse(null))) {
            state.setTexture(BLADE_TEXTURE);
            stateDirty = true;
         }
         if (state.getColorCode() != SUMMONED_SWORD_COLOR) {
            state.setColorCode(SUMMONED_SWORD_COLOR);
            stateDirty = true;
         }
         if (state.isBroken()) {
            state.setBroken(false);
            stateDirty = true;
         }
         if (state.getMaxDamage() <= 0) {
            state.setMaxDamage(MAX_DAMAGE);
            stateDirty = true;
         }

         // 一次性旧存档面板攻击力迁移：仅规范历史冲突值（1000000F / 214748367F / 2147483647F），不粗暴覆写其他合法强化
         if (!migrated) {
            float currentAttack = state.getBaseAttackModifier();
            if (isLegacyConflictingAttack(currentAttack) || currentAttack <= 0.0F) {
               state.setBaseAttackModifier(BASE_ATTACK_MODIFIER);
               stateDirty = true;
            }
            tag.putBoolean(STATS_V3_MIGRATED_KEY, true);
         }

         // 仅当 bladeState 尚未序列化或发生实际字段变更时才写回 NBT，避免每 20 tick 触发无效物品栏同步
         if (stateDirty || !tag.contains("bladeState", 10)) {
            tag.put("bladeState", state.serializeNBT());
         }
      });
   }

   private static boolean isLegacyConflictingAttack(float attack) {
      return Math.abs(attack - 1000000.0F) < 1.0F || attack >= 10000000.0F;
   }

   private static boolean ensureIdentityDirty(ItemStack stack) {
      CompoundTag tag = stack.getOrCreateTag();
      boolean dirty = false;
      String modelStr = BLADE_MODEL.toString();
      String textureStr = BLADE_TEXTURE.toString();
      if (!tag.getBoolean(IDENTITY_TAG)) {
         tag.putBoolean(IDENTITY_TAG, true);
         dirty = true;
      }
      if (!modelStr.equals(tag.getString("ModelName"))) {
         tag.putString("ModelName", modelStr);
         dirty = true;
      }
      if (!textureStr.equals(tag.getString("TextureName"))) {
         tag.putString("TextureName", textureStr);
         dirty = true;
      }
      if (tag.getInt("SummonedSwordColor") != SUMMONED_SWORD_COLOR) {
         tag.putInt("SummonedSwordColor", SUMMONED_SWORD_COLOR);
         dirty = true;
      }
      if (!tag.getBoolean("Unbreakable")) {
         tag.putBoolean("Unbreakable", true);
         dirty = true;
      }
      return dirty;
   }

   private static ListTag createSpecialEffects() {
      ListTag list = new ListTag();
      for (String effect : INFINITY_SPECIAL_EFFECTS) {
         list.add(StringTag.valueOf(effect));
      }
      return list;
   }

   private static void applyEnchantments(ItemStack stack) {
      Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
      putMaxEnchant(enchantments, Enchantments.SHARPNESS, 10);
      putMaxEnchant(enchantments, Enchantments.SMITE, 10);
      putMaxEnchant(enchantments, Enchantments.BANE_OF_ARTHROPODS, 10);
      putMaxEnchant(enchantments, Enchantments.KNOCKBACK, 10);
      putMaxEnchant(enchantments, Enchantments.MOB_LOOTING, 10);
      putMaxEnchant(enchantments, Enchantments.SWEEPING_EDGE, 10);
      putMaxEnchant(enchantments, Enchantments.POWER_ARROWS, 10);
      putMaxEnchant(enchantments, Enchantments.PUNCH_ARROWS, 10);
      putMaxEnchant(enchantments, Enchantments.INFINITY_ARROWS, 10);
      putMaxEnchant(enchantments, Enchantments.QUICK_CHARGE, 10);
      putMaxEnchant(enchantments, Enchantments.MULTISHOT, 10);
      putMaxEnchant(enchantments, Enchantments.PIERCING, 10);
      putMaxEnchant(enchantments, Enchantments.UNBREAKING, 10);
      putMaxEnchant(enchantments, Enchantments.MENDING, 10);
      putMaxEnchant(enchantments, Enchantments.THORNS, 10);
      EnchantmentHelper.setEnchantments(enchantments, stack);
   }

   private static void putMaxEnchant(Map<Enchantment, Integer> enchantments, Enchantment enchantment, int level) {
      enchantments.put(enchantment, Math.max(level, enchantments.getOrDefault(enchantment, 0)));
   }
}
