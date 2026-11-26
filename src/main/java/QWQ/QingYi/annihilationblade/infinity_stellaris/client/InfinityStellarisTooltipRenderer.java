package QWQ.QingYi.annihilationblade.infinity_stellaris.client;

import QWQ.QingYi.annihilationblade.infinity_stellaris.InfinityStellarisDefinitions;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.SpecialEffectsRegistry;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryManager;
import org.joml.Matrix4f;

/**
 * 无尽星空（Infinity Stellaris）星穹观测终端自定义 Tooltip 渲染器（Step 0.5 批处理与美学升级版）。
 * <p>
 * <ul>
 *   <li>引入 {@link #beginQuadBatch()} / {@link #endQuadBatch()}，将原本单帧 ~800 次 Draw Call 压缩至 ≤ 5 次，消除 GL 状态抖动；</li>
 *   <li>色彩严格对齐 v3 第 1.1 节规范：深空蓝黑 {@code #02040D} × 切伦科夫冰青 {@code #1DF5FF} × 恒星白热 {@code #EFFBFF} × 伽马电紫 {@code #7C5CFF}，
 *       黑体金 {@code #FFB84D} 仅用于魂数与 SA 终极徽标点缀；</li>
 *   <li>外环重塑为带 72 细分游标刻度的浑天仪星盘，全画布接入视口裁剪与高 GUI Scale 智能边距回缩，零穿屏；</li>
 *   <li>技能芯片动态读取真实 {@code bladeState} 的已挂载 SE 与 SA（完整呈现玩家移植的外部词条）；附魔缓存纳入当前语言代码。</li>
 * </ul>
 */
public final class InfinityStellarisTooltipRenderer {
   private static final ResourceLocation BLACK_HOLE_BACKGROUND = ResourceLocation.fromNamespaceAndPath(
      "annihilationblade", "textures/gui/infinity_stellaris_black_hole.png"
   );
   private static final ResourceLocation STAR_MAP_OVERLAY = ResourceLocation.fromNamespaceAndPath(
      "annihilationblade", "textures/gui/infinity_stellaris_star_map.png"
   );

   private static final int BLACK_HOLE_TEXTURE_WIDTH = 1280;
   private static final int BLACK_HOLE_TEXTURE_HEIGHT = 853;
   private static final int STAR_MAP_TEXTURE_WIDTH = 1734;
   private static final int STAR_MAP_TEXTURE_HEIGHT = 907;

   private static final int WIDTH = 324;
   private static final int MIN_WIDTH = 224;

   // 严格遵循 v3 色彩规范：切伦科夫冰青 / 恒星白热 / 天青 / 伽马电紫 / 冰白 / 深星蓝
   private static final int COLOR_CYAN = 0x1DF5FF;
   private static final int COLOR_STAR_WHITE = 0xEFFBFF;
   private static final int COLOR_VIOLET = 0x7C5CFF;
   private static final int COLOR_ASTRO_BLUE = 0x4EA8FF;
   private static final int COLOR_BLACKBODY_GOLD = 0xFFB84D;
   private static final int COLOR_SUBTEXT = 0x9AD8FF;

   private static final int[] STELLAR_SPECTRUM = new int[]{
      COLOR_CYAN, COLOR_STAR_WHITE, 0x6AD9FF, COLOR_VIOLET, 0x99F8FF, COLOR_ASTRO_BLUE
   };
   private static final int[] SKILL_COLOR_POOL = new int[]{
      COLOR_CYAN, COLOR_STAR_WHITE, COLOR_VIOLET, COLOR_ASTRO_BLUE
   };
   private static final long TITLE_CYCLE_MILLIS = 4600L;
   private static final String FINAL_WEAPON_KEY = "item.annihilationblade.infinity_stellaris.tooltip.final_weapon";
   private static final String DURABILITY_ETERNAL_KEY = "item.annihilationblade.infinity_stellaris.tooltip.durability.eternal";

   private static final AuthorityChip[] AUTHORITY_CHIPS = new AuthorityChip[]{
      new AuthorityChip("item.annihilationblade.infinity_stellaris.tooltip.chip.flight", COLOR_ASTRO_BLUE),
      new AuthorityChip("item.annihilationblade.infinity_stellaris.tooltip.chip.invulnerable", COLOR_STAR_WHITE),
      new AuthorityChip("item.annihilationblade.infinity_stellaris.tooltip.chip.kill_fallback", COLOR_VIOLET),
      new AuthorityChip("item.annihilationblade.infinity_stellaris.tooltip.chip.ritual_forged", COLOR_BLACKBODY_GOLD),
      new AuthorityChip("item.annihilationblade.infinity_stellaris.tooltip.chip.fullbright", COLOR_CYAN)
   };

   private static BufferBuilder activeQuadBatch = null;
   private static boolean activeQuadBatchHasVertices = false;

   private static ItemStack cachedEnchantStack = ItemStack.EMPTY;
   private static CompoundTag cachedEnchantTag = null;
   private static String cachedEnchantLang = "";
   private static List<EnchantmentLine> cachedEnchantmentLines = null;

   private InfinityStellarisTooltipRenderer() {
   }

   public static void render(
      GuiGraphics graphics, Font font, ItemStack stack, List<Component> vanillaLines, int mouseX, int mouseY
   ) {
      int screenWidth = graphics.guiWidth();
      int screenHeight = graphics.guiHeight();

      int width = Math.min(WIDTH, Math.max(MIN_WIDTH, screenWidth - 18));
      int contentWidth = width - 24;

      // 1. 动态读取真实技能芯片（SE + SA）与附魔，按真实行数精确核算面板高度
      List<SkillChip> skillChips = readActiveSkillChips(stack);
      int skillColumns = resolveSkillColumns(font, skillChips, contentWidth);
      int skillRows = skillChips.isEmpty() ? 1 : (skillChips.size() + skillColumns - 1) / skillColumns;
      int skillBlockHeight = skillRows * 14 + 6;

      List<EnchantmentLine> enchantments = getEnchantments(stack);
      int enchantRows = (enchantments.size() + 1) / 2;
      int enchantRowHeight = screenHeight < 300 ? 9 : 10;
      int rawHeight = 166 + skillBlockHeight + Math.max(1, enchantRows) * enchantRowHeight;
      int height = Math.min(rawHeight, Math.max(198, screenHeight - 12));

      // 2. 高 GUI Scale 下按剩余空间动态收缩外环边距，彻底防止黑洞背景与浑天仪冲出屏幕
      int marginX = Math.max(0, Math.min(26, (screenWidth - width) / 2 - 4));
      int marginY = Math.max(0, Math.min(24, (screenHeight - height) / 2 - 4));
      int safePadX = Math.max(6, marginX);
      int safePadY = Math.max(6, marginY);

      int x = mouseX + 12;
      int y = mouseY - 14;
      if (x + width > screenWidth - safePadX) {
         x = mouseX - width - 18;
      }
      x = Mth.clamp(x, safePadX, Math.max(safePadX, screenWidth - width - safePadX));
      if (y + height > screenHeight - safePadY) {
         y = screenHeight - height - safePadY;
      }
      y = Mth.clamp(y, safePadY, Math.max(safePadY, screenHeight - height - safePadY));

      float time = (float) (System.currentTimeMillis() % 120000L) / 1000.0F;

      graphics.pose().pushPose();
      graphics.pose().translate(0.0F, 0.0F, 760.0F);
      try {
         renderFrame(graphics, x, y, width, height, screenWidth, screenHeight, marginX, marginY, time);
         renderContent(
            graphics, font, stack, vanillaLines, skillChips, skillColumns, skillBlockHeight,
            enchantments, x, y, width, height, contentWidth, enchantRowHeight, time
         );
      } finally {
         endQuadBatch();
         graphics.pose().popPose();
      }
   }

   private static void renderFrame(
      GuiGraphics graphics,
      int x,
      int y,
      int width,
      int height,
      int screenWidth,
      int screenHeight,
      int outerPadX,
      int outerPadY,
      float time
   ) {
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();

      Matrix4f matrix = graphics.pose().last().pose();
      int backdropPad = Math.min(12, Math.min(outerPadX, outerPadY));
      int outerX = x - outerPadX;
      int outerY = y - outerPadY;
      int outerWidth = width + outerPadX * 2;
      int outerHeight = height + outerPadY * 2;
      int backdropX = Math.max(0, outerX - backdropPad);
      int backdropY = Math.max(0, outerY - backdropPad);
      int backdropRight = Math.min(screenWidth, outerX + outerWidth + backdropPad);
      int backdropBottom = Math.min(screenHeight, outerY + outerHeight + backdropPad);
      int backdropWidth = Math.max(1, backdropRight - backdropX);
      int backdropHeight = Math.max(1, backdropBottom - backdropY);

      // 1. 外延深空黑洞衬底（严格视口裁剪）
      graphics.enableScissor(backdropX, backdropY, backdropRight, backdropBottom);
      blitCover(
         graphics, BLACK_HOLE_BACKGROUND, backdropX, backdropY, backdropWidth, backdropHeight,
         BLACK_HOLE_TEXTURE_WIDTH, BLACK_HOLE_TEXTURE_HEIGHT, -10.0F, time * 0.24F, 0.84F
      );
      graphics.fillGradient(backdropX, backdropY, backdropRight, backdropBottom, 0x4802040D, 0x8602040D);
      graphics.disableScissor();

      // 2. 批处理批次 1：外层浑天仪 72 刻度星盘 + 轨道细框 + 巡游星符（全屏视口裁剪防高倍率穿屏）
      graphics.enableScissor(0, 0, screenWidth, screenHeight);
      beginQuadBatch();
      drawOuterArmillary(matrix, x + width * 0.5F, y + height * 0.5F, outerWidth, outerHeight, time);
      if (outerPadX >= 8 && outerPadY >= 8) {
         renderOrbitBorder(matrix, outerX, outerY, outerWidth, outerHeight, time);
         drawOrbitingSigils(matrix, outerX, outerY, outerWidth, outerHeight, time);
      }
      endQuadBatch();
      graphics.disableScissor();

      // 3. 观测终端主体深空底板 + 星图叠加
      graphics.fillGradient(x, y, x + width, y + height, 0xC602040D, 0xD80A1128);
      graphics.enableScissor(x + 2, y + 2, x + width - 2, y + height - 2);
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      graphics.fillGradient(x + 2, y + 2, x + width - 2, y + height - 2, 0xAE020512, 0xDF090F22);
      blitCover(
         graphics, STAR_MAP_OVERLAY, x, y, width, height,
         STAR_MAP_TEXTURE_WIDTH, STAR_MAP_TEXTURE_HEIGHT, 8.0F, time * 0.36F, 0.17F
      );
      graphics.fillGradient(x + 4, y + 4, x + width - 4, y + 46, 0x8C02040D, 0x2402040D);
      graphics.fillGradient(x + 5, y + 132, x + width - 5, y + height - 5, 0x4402040D, 0xBE02040D);

      // 4. 批处理批次 2：内框斜掠遥测光线 + 深度星尘微点
      beginQuadBatch();
      drawMovingAccents(matrix, x, y, width, height, time);
      endQuadBatch();
      graphics.disableScissor();

      // 5. 批处理批次 3：多层冰青×星白×电紫流彩边框 + 观测终端四角卡尺
      beginQuadBatch();
      renderGradientBorder(matrix, x, y, width, height, time);
      endQuadBatch();
   }

   private static void blitCover(
      GuiGraphics graphics,
      ResourceLocation texture,
      int x,
      int y,
      int width,
      int height,
      int textureWidth,
      int textureHeight,
      float biasX,
      float time,
      float alpha
   ) {
      float imageAspect = (float) textureWidth / textureHeight;
      float targetAspect = (float) width / height;
      int imageWidth;
      int imageHeight;
      if (imageAspect > targetAspect) {
         imageHeight = height;
         imageWidth = Math.round(height * imageAspect);
      } else {
         imageWidth = width;
         imageHeight = Math.round(width / imageAspect);
      }

      int imageX = x - (imageWidth - width) / 2 + Math.round(biasX + Mth.sin(time * 0.18F) * 2.5F);
      int imageY = y - (imageHeight - height) / 2 + Math.round(Mth.cos(time * 0.16F) * 2.5F);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
      graphics.blit(texture, imageX, imageY, imageWidth, imageHeight, 0.0F, 0.0F, textureWidth, textureHeight, textureWidth, textureHeight);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
   }

   private static void renderContent(
      GuiGraphics graphics,
      Font font,
      ItemStack stack,
      List<Component> vanillaLines,
      List<SkillChip> skillChips,
      int skillColumns,
      int skillBlockHeight,
      List<EnchantmentLine> enchantments,
      int x,
      int y,
      int width,
      int height,
      int contentWidth,
      int enchantRowHeight,
      float time
   ) {
      int center = x + width / 2;
      Component itemName = vanillaLines.isEmpty() ? stack.getHoverName() : vanillaLines.get(0);
      drawGlowText(graphics, font, itemName, center - font.width(itemName) / 2, y + 8, COLOR_STAR_WHITE, COLOR_CYAN);
      MutableComponent title = buildSpectralTitle(time);
      drawGlowText(graphics, font, title, center - font.width(title) / 2, y + 20, COLOR_STAR_WHITE, COLOR_VIOLET);
      graphics.drawCenteredString(
         font,
         Component.translatable("item.annihilationblade.infinity_stellaris.tooltip.subtitle"),
         center, y + 34, COLOR_SUBTEXT
      );
      graphics.renderItem(stack, x + 13, y + 13);

      int cursorY = y + 51;
      drawSectionTitle(
         graphics, font, x + 12, cursorY, contentWidth,
         Component.translatable("item.annihilationblade.infinity_stellaris.tooltip.section.authority"), time
      );
      cursorY += 13;
      drawAuthorityChips(graphics, font, x + 12, cursorY, contentWidth, time);
      cursorY += 17;

      drawSectionTitle(
         graphics, font, x + 12, cursorY, contentWidth,
         Component.translatable("item.annihilationblade.infinity_stellaris.tooltip.section.attributes"), time + 0.45F
      );
      cursorY += 13;
      drawStats(graphics, font, stack, x + 12, cursorY, contentWidth, time);
      cursorY += 25;

      drawSectionTitle(
         graphics, font, x + 12, cursorY, contentWidth,
         Component.translatable("item.annihilationblade.infinity_stellaris.tooltip.section.effects"), time + 0.9F
      );
      cursorY += 13;
      drawSkillChips(graphics, font, x + 12, cursorY, contentWidth, skillChips, skillColumns, time);
      cursorY += skillBlockHeight;

      int remainingHeight = height - (cursorY - y) - 10;
      if (remainingHeight > 18) {
         drawSectionTitle(
            graphics, font, x + 12, cursorY, contentWidth,
            Component.translatable("item.annihilationblade.infinity_stellaris.tooltip.section.enchantments"), time + 1.35F
         );
         cursorY += 13;
         drawEnchantments(
            graphics, font, x + 12, cursorY, contentWidth, height - (cursorY - y) - 10, enchantRowHeight, enchantments, time
         );
      }

      int pulse = (int) ((contentWidth - 2) * (0.5F + 0.5F * Mth.sin(time * 2.1F)));
      graphics.fill(x + 13, y + height - 7, x + width - 13, y + height - 6, withAlpha(COLOR_CYAN, 0.34F));
      graphics.fill(x + 13, y + height - 7, x + 13 + pulse, y + height - 6, withAlpha(COLOR_STAR_WHITE, 0.85F));
   }

   private static void drawSectionTitle(
      GuiGraphics graphics, Font font, int x, int y, int width, Component title, float phase
   ) {
      int accent = sampleSpectrum(phase * 1.1F);
      int railWidth = Math.max(46, Math.round(width * 0.64F));
      float pulsePhase = 0.5F + 0.5F * Mth.sin(phase * 2.2F);
      int pulseWidth = Math.max(18, Math.round(railWidth * (0.24F + pulsePhase * 0.34F)));
      graphics.fill(x, y + 10, x + railWidth, y + 11, withAlpha(accent, 0.28F));
      graphics.fill(x, y + 10, x + pulseWidth, y + 11, withAlpha(accent, 0.85F));
      graphics.drawString(font, title, x + 2, y, withAlpha(COLOR_STAR_WHITE, 0.96F), false);
   }

   private static void drawAuthorityChips(GuiGraphics graphics, Font font, int x, int y, int width, float time) {
      int gap = 3;
      int chipWidth = (width - gap * (AUTHORITY_CHIPS.length - 1)) / AUTHORITY_CHIPS.length;
      for (int i = 0; i < AUTHORITY_CHIPS.length; i++) {
         AuthorityChip chip = AUTHORITY_CHIPS[i];
         drawSmallChip(
            graphics, font, x + i * (chipWidth + gap), y, chipWidth, 12,
            Component.translatable(chip.key()), chip.color(), time + i * 0.31F
         );
      }
   }

   private static void drawStats(GuiGraphics graphics, Font font, ItemStack stack, int x, int y, int width, float time) {
      BladeStats stats = readBladeStats(stack);
      StatCell[] cells = new StatCell[]{
         new StatCell("item.annihilationblade.infinity_stellaris.tooltip.stat.kills", formatNumber(stats.killCount()), COLOR_CYAN),
         new StatCell("item.annihilationblade.infinity_stellaris.tooltip.stat.souls", formatNumber(stats.proudSoul()), COLOR_BLACKBODY_GOLD),
         new StatCell("item.annihilationblade.infinity_stellaris.tooltip.stat.refine", formatNumber(stats.refine()), COLOR_ASTRO_BLUE),
         new StatCell("item.annihilationblade.infinity_stellaris.tooltip.stat.attack", formatAttack(stats.baseAttack()), COLOR_STAR_WHITE),
         new StatCell("item.annihilationblade.infinity_stellaris.tooltip.stat.durability", I18n.get(DURABILITY_ETERNAL_KEY), COLOR_VIOLET)
      };
      int gap = 3;
      int cellWidth = (width - gap * (cells.length - 1)) / cells.length;
      for (int i = 0; i < cells.length; i++) {
         drawStatCell(graphics, font, x + i * (cellWidth + gap), y, cellWidth, 20, cells[i], time + i * 0.27F);
      }
   }

   private static void drawSkillChips(
      GuiGraphics graphics,
      Font font,
      int x,
      int y,
      int width,
      List<SkillChip> chips,
      int columns,
      float time
   ) {
      if (chips.isEmpty()) {
         drawSmallChip(graphics, font, x, y, width, 12, Component.literal("—"), COLOR_ASTRO_BLUE, time);
         return;
      }
      int gap = 4;
      int chipWidth = (width - gap * (columns - 1)) / columns;
      for (int i = 0; i < chips.size(); i++) {
         int col = i % columns;
         int row = i / columns;
         SkillChip chip = chips.get(i);
         drawSmallChip(
            graphics, font, x + col * (chipWidth + gap), y + row * 14, chipWidth, 12,
            chip.label(), chip.color(), time + i * 0.42F
         );
      }
   }

   private static void drawEnchantments(
      GuiGraphics graphics,
      Font font,
      int x,
      int y,
      int width,
      int height,
      int rowHeight,
      List<EnchantmentLine> enchantments,
      float time
   ) {
      if (enchantments.isEmpty() || height <= 8) {
         return;
      }

      int columns = 2;
      int gap = 5;
      int colWidth = (width - gap) / columns;
      int rows = Math.min((enchantments.size() + 1) / 2, Math.max(1, height / rowHeight));
      graphics.fill(x, y - 2, x + width, y + rows * rowHeight + 4, 0x82020716);
      graphics.fill(x, y - 2, x + width, y - 1, withAlpha(sampleSpectrum(time * 0.75F), 0.78F));
      for (int i = 0; i < enchantments.size() && i < rows * columns; i++) {
         int col = i % columns;
         int row = i / columns;
         EnchantmentLine line = enchantments.get(i);
         int entryX = x + col * (colWidth + gap);
         int entryY = y + row * rowHeight;
         int color = sampleSpectrum(time * 0.55F + i * 0.37F);
         int railWidth = Math.max(28, Math.round(colWidth * 0.66F));
         int pulseWidth = Math.max(
            16, Math.round(railWidth * (0.28F + 0.36F * (0.5F + 0.5F * Mth.sin(time * 2.0F + i * 0.31F))))
         );
         graphics.fill(entryX, entryY + rowHeight - 2, entryX + railWidth, entryY + rowHeight - 1, withAlpha(color, 0.18F));
         graphics.fill(entryX, entryY + rowHeight - 2, entryX + pulseWidth, entryY + rowHeight - 1, withAlpha(color, 0.44F));
         graphics.drawString(font, trimToWidth(font, line.text(), colWidth - 4), entryX + 2, entryY, 0xE6F7FF, false);
      }
   }

   private static void drawSmallChip(
      GuiGraphics graphics, Font font, int x, int y, int width, int height, Component text, int rgb, float phase
   ) {
      graphics.fill(x, y, x + width, y + height, 0x92040B1C);
      graphics.fill(x, y, x + width, y + 1, withAlpha(rgb, 0.82F));
      int railWidth = Math.max(14, Math.round((width - 4) * 0.58F));
      int pulse = Math.max(8, Math.round(railWidth * (0.28F + 0.38F * (0.5F + 0.5F * Mth.sin(phase * 2.5F)))));
      graphics.fill(x + 2, y + height - 2, x + 2 + railWidth, y + height - 1, withAlpha(rgb, 0.22F));
      graphics.fill(x + 2, y + height - 2, x + 2 + pulse, y + height - 1, withAlpha(rgb, 0.74F));
      String plain = ChatFormatting.stripFormatting(text.getString());
      graphics.drawString(font, trimToWidth(font, plain == null ? "" : plain, width - 7), x + 4, y + 2, COLOR_STAR_WHITE, false);
   }

   private static void drawStatCell(
      GuiGraphics graphics, Font font, int x, int y, int width, int height, StatCell cell, float phase
   ) {
      int accent = cell.color();
      graphics.fill(x, y, x + width, y + height, 0x94040A1A);
      graphics.fill(x, y, x + width, y + 1, withAlpha(accent, 0.76F));
      int railWidth = Math.max(14, Math.round((width - 4) * 0.56F));
      int pulse = Math.max(8, Math.round(railWidth * (0.28F + 0.34F * (0.5F + 0.5F * Mth.sin(phase * 2.2F)))));
      graphics.fill(x + 2, y + height - 3, x + 2 + railWidth, y + height - 2, withAlpha(accent, 0.18F));
      graphics.fill(x + 2, y + height - 3, x + 2 + pulse, y + height - 2, withAlpha(accent, 0.72F));
      graphics.drawString(font, trimToWidth(font, Component.translatable(cell.labelKey()).getString(), width - 4), x + 3, y + 2, COLOR_SUBTEXT, false);
      graphics.drawString(font, trimToWidth(font, cell.value(), width - 4), x + 3, y + 11, COLOR_STAR_WHITE, false);
   }

   private static MutableComponent buildSpectralTitle(float time) {
      String text = ChatFormatting.stripFormatting(I18n.get(FINAL_WEAPON_KEY));
      if (text == null || text.isEmpty()) {
         text = "Infinity Stellaris";
      }

      MutableComponent title = Component.empty();
      float drift = ((System.currentTimeMillis() % TITLE_CYCLE_MILLIS) / (float) TITLE_CYCLE_MILLIS) * STELLAR_SPECTRUM.length;
      int visualIndex = 0;
      for (int offset = 0; offset < text.length();) {
         int codePoint = text.codePointAt(offset);
         String glyph = new String(Character.toChars(codePoint));
         int color = sampleSpectrum(visualIndex * 0.36F - drift + Mth.sin(time * 0.75F) * 0.12F);
         title.append(Component.literal(glyph).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)).withBold(true)));
         offset += Character.charCount(codePoint);
         if (!Character.isWhitespace(codePoint)) {
            visualIndex++;
         }
      }
      return title;
   }

   /**
    * 动态读取拔刀剑真实挂载的 SE 与 SA，完整兼容玩家铁砧/指令移植的任意外部技能。
    * <p>
    * 刻意通过 NBT（{@code bladeState.SpecialEffects}，缺失时由 {@code serializeNBT()} 兜底）读取 SE 列表，
    * 不直接调用 {@code ISlashBladeState.getSpecialEffects()}，避免不同版本 SlashBlade Resharped
    * 将该方法返回类型由 {@code List} 改为 {@code Collection} 时触发 {@link NoSuchMethodError}。
    */
   private static List<SkillChip> readActiveSkillChips(ItemStack stack) {
      List<SkillChip> chips = new ArrayList<>();
      IForgeRegistry<?> seRegistry = SpecialEffectsRegistry.REGISTRY.get();
      IForgeRegistry<?> saRegistry = RegistryManager.ACTIVE.getRegistry(SlashArts.REGISTRY_KEY);

      CompoundTag bladeState = null;
      if (stack.hasTag() && stack.getTag().contains("bladeState", 10)) {
         bladeState = stack.getTag().getCompound("bladeState");
      }

      ResourceLocation[] saHolder = new ResourceLocation[1];
      CompoundTag[] serializedFallback = new CompoundTag[1];
      stack.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
         ResourceLocation sa = state.getSlashArtsKey();
         if (sa != null && !"none".equals(sa.getPath()) && (saRegistry == null || saRegistry.containsKey(sa))) {
            saHolder[0] = sa;
         }
         serializedFallback[0] = state.serializeNBT();
      });

      CompoundTag effectiveState = (bladeState != null && bladeState.contains("SpecialEffects", 9))
         ? bladeState
         : serializedFallback[0];

      List<ResourceLocation> seIds = new ArrayList<>();
      if (effectiveState != null) {
         ListTag list = effectiveState.getList("SpecialEffects", 8);
         for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id != null && !seIds.contains(id) && (seRegistry == null || seRegistry.containsKey(id))) {
               seIds.add(id);
            }
         }
         if (saHolder[0] == null && effectiveState.contains("SpecialAttackType", 8)) {
            ResourceLocation sa = ResourceLocation.tryParse(effectiveState.getString("SpecialAttackType"));
            if (sa != null && !"none".equals(sa.getPath()) && (saRegistry == null || saRegistry.containsKey(sa))) {
               saHolder[0] = sa;
            }
         }
      }

      for (int i = 0; i < seIds.size(); i++) {
         ResourceLocation id = seIds.get(i);
         int color = colorForSpecialEffect(id, i);
         Component label = Component.translatable("se." + id.getNamespace() + "." + id.getPath());
         chips.add(new SkillChip(label, color));
      }

      if (saHolder[0] != null) {
         ResourceLocation sa = saHolder[0];
         Component label = Component.translatable("slash_art." + sa.getNamespace() + "." + sa.getPath());
         chips.add(new SkillChip(label, COLOR_BLACKBODY_GOLD));
      }
      return chips;
   }

   private static int colorForSpecialEffect(ResourceLocation id, int index) {
      return switch (id.getPath()) {
         case "entropy_dissolution" -> COLOR_CYAN;
         case "cosmic_string_cut" -> COLOR_STAR_WHITE;
         case "curvature_rupture" -> COLOR_VIOLET;
         case "gamma_thunderburst" -> COLOR_ASTRO_BLUE;
         default -> SKILL_COLOR_POOL[index % SKILL_COLOR_POOL.length];
      };
   }

   private static int resolveSkillColumns(Font font, List<SkillChip> chips, int contentWidth) {
      if (chips.size() <= 1) {
         return 1;
      }
      int maxWidth = 0;
      for (SkillChip chip : chips) {
         String plain = ChatFormatting.stripFormatting(chip.label().getString());
         maxWidth = Math.max(maxWidth, font.width(plain == null ? "" : plain));
      }
      int minChipWidth = Math.max(76, Math.min(118, maxWidth + 12));
      int cols = Math.max(2, Math.min(3, (contentWidth + 4) / (minChipWidth + 4)));
      return Math.min(cols, chips.size());
   }

   private static List<EnchantmentLine> getEnchantments(ItemStack stack) {
      CompoundTag tag = stack.getTag();
      String langCode = Minecraft.getInstance().getLanguageManager().getSelected();
      if (cachedEnchantmentLines != null
            && Objects.equals(cachedEnchantLang, langCode)
            && ItemStack.matches(cachedEnchantStack, stack)
            && Objects.equals(cachedEnchantTag, tag)) {
         return cachedEnchantmentLines;
      }

      List<EnchantmentLine> lines = new ArrayList<>();
      for (Entry<Enchantment, Integer> entry : EnchantmentHelper.getEnchantments(stack).entrySet()) {
         Enchantment enchantment = entry.getKey();
         int level = entry.getValue();
         ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
         String sortKey = id == null ? enchantment.getDescriptionId() : id.toString();
         lines.add(new EnchantmentLine(sortKey, enchantment.getFullname(level).getString()));
      }

      lines.sort(Comparator.comparing(EnchantmentLine::sortKey));
      cachedEnchantStack = stack.copy();
      cachedEnchantTag = tag != null ? tag.copy() : null;
      cachedEnchantLang = langCode;
      cachedEnchantmentLines = lines;
      return lines;
   }

   private static BladeStats readBladeStats(ItemStack stack) {
      return stack.getCapability(ItemSlashBlade.BLADESTATE)
         .map(state -> new BladeStats(
            state.getProudSoulCount(),
            state.getKillCount(),
            state.getRefine(),
            state.getBaseAttackModifier() > 0.0F ? state.getBaseAttackModifier() : InfinityStellarisDefinitions.BASE_ATTACK_MODIFIER
         ))
         .orElseGet(() -> readBladeStatsFromTag(stack));
   }

   private static BladeStats readBladeStatsFromTag(ItemStack stack) {
      CompoundTag tag = stack.getTag();
      CompoundTag bladeState = tag == null ? null : tag.getCompound("bladeState");
      int proudSoul = firstAvailable(
         readInt(bladeState, "proudSoul", "ProudSoul", "ProudSoulCount", "proudsoul"),
         readInt(tag, "ProudSoul", "ProudSoulCount", "proudSoul", "proudsoul")
      );
      int killCount = firstAvailable(readInt(bladeState, "killCount", "KillCount"), readInt(tag, "KillCount", "killCount"));
      int refine = firstAvailable(
         readInt(bladeState, "RepairCounter", "refine", "Refine", "RefineCount", "refineCount"),
         readInt(tag, "RepairCounter", "Refine", "RefineCount", "refine", "refineCount")
      );
      float baseAttack = bladeState != null && bladeState.contains("baseAttackModifier")
         ? bladeState.getFloat("baseAttackModifier")
         : InfinityStellarisDefinitions.BASE_ATTACK_MODIFIER;
      if (baseAttack <= 0.0F) {
         baseAttack = InfinityStellarisDefinitions.BASE_ATTACK_MODIFIER;
      }
      return new BladeStats(proudSoul, killCount, refine, baseAttack);
   }

   private static int firstAvailable(int preferred, int fallback) {
      return preferred != 0 ? preferred : fallback;
   }

   private static int readInt(CompoundTag tag, String... keys) {
      if (tag == null) {
         return 0;
      }
      for (String key : keys) {
         if (tag.contains(key)) {
            return tag.getInt(key);
         }
      }
      return 0;
   }

   private static String formatNumber(int value) {
      return String.format(Locale.ROOT, "%,d", value);
   }

   private static String formatAttack(float value) {
      if (Math.abs(value - Math.round(value)) < 0.01F) {
         return String.format(Locale.ROOT, "%,d", Math.round(value));
      }
      return String.format(Locale.ROOT, "%,.1f", value);
   }

   private static String trimToWidth(Font font, String text, int width) {
      if (width <= 0 || font.width(text) <= width) {
         return text;
      }
      String ellipsis = "...";
      int avail = Math.max(0, width - font.width(ellipsis));
      return font.plainSubstrByWidth(text, avail) + ellipsis;
   }

   private static void drawGlowText(
      GuiGraphics graphics, Font font, Component text, int x, int y, int color, int glow
   ) {
      graphics.drawString(font, text, x - 1, y, withAlpha(glow, 0.42F), false);
      graphics.drawString(font, text, x + 1, y, withAlpha(glow, 0.42F), false);
      graphics.drawString(font, text, x, y - 1, withAlpha(COLOR_STAR_WHITE, 0.26F), false);
      graphics.drawString(font, text, x, y + 1, withAlpha(COLOR_VIOLET, 0.24F), false);
      graphics.drawString(font, text, x, y, color, true);
   }

   private static void drawMovingAccents(Matrix4f matrix, int x, int y, int width, int height, float time) {
      for (int i = 0; i < 6; i++) {
         float phase = (time * 0.08F + i * 0.167F) % 1.0F;
         float sx = x - 34.0F + phase * (width + 68.0F);
         int colorA = withAlpha(sampleSpectrum(i * 0.65F + time * 0.18F), 0.14F);
         int colorB = withAlpha(COLOR_STAR_WHITE, 0.06F);
         drawBeam(matrix, sx, y + height + 12.0F, sx + 58.0F, y - 16.0F, 0.9F, colorA, colorB);
      }

      for (int i = 0; i < 36; i++) {
         float px = x + 8.0F + noise(i * 18.73F) * (width - 16.0F);
         float py = y + 8.0F + noise(i * 8.39F + 3.0F) * (height - 16.0F);
         float pulse = 0.40F + 0.60F * Mth.sin(time * 1.8F + i * 0.61F);
         int starRgb = i % 6 == 0 ? COLOR_STAR_WHITE : (i % 3 == 0 ? COLOR_CYAN : COLOR_ASTRO_BLUE);
         int color = withAlpha(starRgb, 0.12F + pulse * 0.18F);
         float size = 0.9F + pulse * 0.9F;
         drawQuad(matrix, px, py, px + size, py + size, color);
      }
   }

   /** 外层浑天仪 72 刻度游标星盘（合批绘制，零冗余 Draw Call）。 */
   private static void drawOuterArmillary(Matrix4f matrix, float cx, float cy, int outerWidth, int outerHeight, float time) {
      float radius = Math.max(outerWidth, outerHeight) * 0.51F + 8.0F;
      float spin = time * 0.14F;
      float counterSpin = -time * 0.10F;
      int cyanGhost = withAlpha(COLOR_CYAN, 0.14F);
      int whiteSoft = withAlpha(COLOR_STAR_WHITE, 0.22F + 0.05F * (0.5F + 0.5F * Mth.sin(time * 1.3F)));
      int violetSoft = withAlpha(COLOR_VIOLET, 0.18F);
      int tickBright = withAlpha(COLOR_STAR_WHITE, 0.48F + 0.10F * (0.5F + 0.5F * Mth.sin(time * 1.9F)));

      drawCircle(matrix, cx, cy, radius, spin, 72, 0.62F, whiteSoft);
      drawCircle(matrix, cx, cy, radius * 0.94F, counterSpin, 72, 0.42F, cyanGhost);
      drawCircle(matrix, cx, cy, radius * 0.81F, spin * 1.35F, 48, 0.48F, violetSoft);
      drawPolygon(matrix, cx, cy, radius * 0.86F, 6, spin + Mth.PI / 6.0F, 0.52F, cyanGhost);
      drawSteppedStar(matrix, cx, cy, radius * 0.72F, 12, 5, counterSpin * 0.9F, 0.42F, withAlpha(COLOR_STAR_WHITE, 0.13F));

      // 72 细分游标刻度（每 6 格长标尺，呼应 v3「毫米级精密细节」）
      for (int i = 0; i < 72; i++) {
         float angle = spin + i * Mth.TWO_PI / 72.0F;
         boolean major = i % 6 == 0;
         boolean medium = !major && i % 3 == 0;
         float inner = radius * (major ? 0.87F : (medium ? 0.91F : 0.935F));
         float outer = radius * (major ? 1.025F : 0.99F);
         Point2 a = radialPoint(cx, cy, inner, angle);
         Point2 b = radialPoint(cx, cy, outer, angle);
         int color = major ? tickBright : (medium ? withAlpha(COLOR_CYAN, 0.28F) : withAlpha(COLOR_STAR_WHITE, 0.12F));
         drawBeam(matrix, a.x(), a.y(), b.x(), b.y(), major ? 0.65F : 0.34F, color, color);
      }

      for (int i = 0; i < 8; i++) {
         float angle = counterSpin * 1.15F + i * Mth.TWO_PI / 8.0F;
         Point2 p = radialPoint(cx, cy, radius * 0.99F, angle);
         drawRotatingDiamond(matrix, p.x(), p.y(), 4.8F, spin * 1.8F + i, withAlpha(i % 2 == 0 ? COLOR_CYAN : COLOR_STAR_WHITE, 0.42F));
      }
   }

   private static void renderOrbitBorder(Matrix4f matrix, int x, int y, int width, int height, float time) {
      int glow = withAlpha(COLOR_CYAN, 0.11F);
      int base = withAlpha(COLOR_STAR_WHITE, 0.28F);
      drawBeam(matrix, x, y, x + width, y, 2.2F, glow, glow);
      drawBeam(matrix, x, y + height, x + width, y + height, 2.2F, glow, glow);
      drawBeam(matrix, x, y, x, y + height, 2.2F, glow, glow);
      drawBeam(matrix, x + width, y, x + width, y + height, 2.2F, glow, glow);
      drawBeam(matrix, x, y, x + width, y, 0.65F, base, base);
      drawBeam(matrix, x, y + height, x + width, y + height, 0.65F, base, base);
      drawBeam(matrix, x, y, x, y + height, 0.65F, base, base);
      drawBeam(matrix, x + width, y, x + width, y + height, 0.65F, base, base);

      for (int i = 0; i < 6; i++) {
         float start = (time * 0.052F + i / 6.0F) % 1.0F;
         float length = 0.045F + 0.014F * (0.5F + 0.5F * Mth.sin(time * 1.7F + i));
         int head = withAlpha(COLOR_STAR_WHITE, 0.76F);
         int tail = withAlpha(i % 2 == 0 ? COLOR_CYAN : COLOR_VIOLET, 0.12F);
         drawBorderTrail(matrix, x, y, width, height, start, length, 1.0F, head, tail);
      }
   }

   private static void drawBorderTrail(
      Matrix4f matrix, int x, int y, int width, int height, float start, float length, float thickness, int headColor, int tailColor
   ) {
      int segments = 16;
      for (int i = 0; i < segments; i++) {
         float a = start + length * i / segments;
         float b = start + length * (i + 1) / segments;
         Point2 p1 = pointOnBorder(x, y, width, height, a);
         Point2 p2 = pointOnBorder(x, y, width, height, b);
         float blend = i / (float) Math.max(1, segments - 1);
         int colorA = lerpArgb(headColor, tailColor, blend);
         int colorB = lerpArgb(headColor, tailColor, Math.min(1.0F, blend + 1.0F / segments));
         drawBeam(matrix, p1.x(), p1.y(), p2.x(), p2.y(), thickness, colorA, colorB);
      }
   }

   private static void drawOrbitingSigils(Matrix4f matrix, int x, int y, int width, int height, float time) {
      for (int i = 0; i < 4; i++) {
         float progress = (time * 0.044F + i * 0.25F) % 1.0F;
         Point2 point = pointOnBorder(x, y, width, height, progress);
         float radius = 7.2F + (i % 2) * 1.4F;
         float rotation = time * (1.15F + i * 0.13F) + i * 0.9F;
         drawRotatingHexagram(matrix, point.x(), point.y(), radius + 1.8F, rotation, withAlpha(COLOR_CYAN, 0.20F));
         drawRotatingHexagram(matrix, point.x(), point.y(), radius, -rotation * 0.78F, withAlpha(COLOR_STAR_WHITE, 0.82F));
         drawRotatingDiamond(matrix, point.x(), point.y(), radius * 0.76F, rotation * 1.6F, withAlpha(COLOR_VIOLET, 0.65F));
      }
   }

   private static Point2 pointOnBorder(int x, int y, int width, int height, float progress) {
      float p = progress - (float) Math.floor(progress);
      float perimeter = (width + height) * 2.0F;
      float distance = p * perimeter;
      if (distance < width) {
         return new Point2(x + distance, y);
      }
      distance -= width;
      if (distance < height) {
         return new Point2(x + width, y + distance);
      }
      distance -= height;
      if (distance < width) {
         return new Point2(x + width - distance, y + height);
      }
      distance -= width;
      return new Point2(x, y + height - distance);
   }

   private static void drawRotatingHexagram(Matrix4f matrix, float cx, float cy, float radius, float rotation, int color) {
      float[] xs = new float[6];
      float[] ys = new float[6];
      for (int i = 0; i < 6; i++) {
         float angle = rotation + i * Mth.PI / 3.0F;
         xs[i] = cx + Mth.cos(angle) * radius;
         ys[i] = cy + Mth.sin(angle) * radius;
      }
      drawBeam(matrix, xs[0], ys[0], xs[2], ys[2], 0.42F, color, color);
      drawBeam(matrix, xs[2], ys[2], xs[4], ys[4], 0.42F, color, color);
      drawBeam(matrix, xs[4], ys[4], xs[0], ys[0], 0.42F, color, color);
      drawBeam(matrix, xs[1], ys[1], xs[3], ys[3], 0.42F, color, color);
      drawBeam(matrix, xs[3], ys[3], xs[5], ys[5], 0.42F, color, color);
      drawBeam(matrix, xs[5], ys[5], xs[1], ys[1], 0.42F, color, color);
   }

   private static void drawRotatingDiamond(Matrix4f matrix, float cx, float cy, float radius, float rotation, int color) {
      float x1 = cx + Mth.cos(rotation) * radius;
      float y1 = cy + Mth.sin(rotation) * radius;
      float x2 = cx + Mth.cos(rotation + Mth.HALF_PI) * radius * 0.62F;
      float y2 = cy + Mth.sin(rotation + Mth.HALF_PI) * radius * 0.62F;
      float x3 = cx + Mth.cos(rotation + Mth.PI) * radius;
      float y3 = cy + Mth.sin(rotation + Mth.PI) * radius;
      float x4 = cx + Mth.cos(rotation + Mth.PI + Mth.HALF_PI) * radius * 0.62F;
      float y4 = cy + Mth.sin(rotation + Mth.PI + Mth.HALF_PI) * radius * 0.62F;
      drawBeam(matrix, x1, y1, x2, y2, 0.52F, color, color);
      drawBeam(matrix, x2, y2, x3, y3, 0.52F, color, color);
      drawBeam(matrix, x3, y3, x4, y4, 0.52F, color, color);
      drawBeam(matrix, x4, y4, x1, y1, 0.52F, color, color);
   }

   private static void drawCircle(
      Matrix4f matrix, float cx, float cy, float radius, float rotation, int segments, float thickness, int color
   ) {
      Point2 previous = radialPoint(cx, cy, radius, rotation);
      for (int i = 1; i <= segments; i++) {
         float angle = rotation + i * Mth.TWO_PI / segments;
         Point2 current = radialPoint(cx, cy, radius, angle);
         drawBeam(matrix, previous.x(), previous.y(), current.x(), current.y(), thickness, color, color);
         previous = current;
      }
   }

   private static void drawPolygon(
      Matrix4f matrix, float cx, float cy, float radius, int points, float rotation, float thickness, int color
   ) {
      Point2 previous = radialPoint(cx, cy, radius, rotation);
      for (int i = 1; i <= points; i++) {
         float angle = rotation + i * Mth.TWO_PI / points;
         Point2 current = radialPoint(cx, cy, radius, angle);
         drawBeam(matrix, previous.x(), previous.y(), current.x(), current.y(), thickness, color, color);
         previous = current;
      }
   }

   private static void drawSteppedStar(
      Matrix4f matrix, float cx, float cy, float radius, int points, int step, float rotation, float thickness, int color
   ) {
      for (int i = 0; i < points; i++) {
         Point2 a = radialPoint(cx, cy, radius, rotation + i * Mth.TWO_PI / points);
         Point2 b = radialPoint(cx, cy, radius, rotation + ((i + step) % points) * Mth.TWO_PI / points);
         drawBeam(matrix, a.x(), a.y(), b.x(), b.y(), thickness, color, color);
      }
   }

   private static Point2 radialPoint(float cx, float cy, float radius, float angle) {
      return new Point2(cx + Mth.cos(angle) * radius, cy + Mth.sin(angle) * radius);
   }

   private static void renderGradientBorder(Matrix4f matrix, int x, int y, int width, int height, float time) {
      for (int layer = 3; layer >= 1; layer--) {
         float spread = layer * 1.8F;
         float alpha = 0.08F + (3 - layer) * 0.08F;
         drawSegmentedBorder(matrix, x, y, width, height, spread, 0.75F + layer * 0.16F, time * 0.08F + layer * 0.13F, alpha);
      }
      drawSegmentedBorder(matrix, x, y, width, height, 0.7F, 1.25F, time * 0.11F, 0.90F);
      drawCornerCalipers(matrix, x, y, width, height, time);
   }

   private static void drawSegmentedBorder(
      Matrix4f matrix, int x, int y, int width, int height, float spread, float thickness, float drift, float alpha
   ) {
      int segments = 18;
      for (int i = 0; i < segments; i++) {
         float t1 = (float) i / segments;
         float t2 = (float) (i + 1) / segments;
         int c1 = withAlpha(sampleSpectrum(t1 * STELLAR_SPECTRUM.length + drift), alpha);
         int c2 = withAlpha(sampleSpectrum(t2 * STELLAR_SPECTRUM.length + drift), alpha);
         float sx = x + width * t1;
         float ex = x + width * t2;
         drawQuad(matrix, sx, y - spread, ex, y - spread, ex, y - spread + thickness, sx, y - spread + thickness, c1, c2, c2, c1);
         drawQuad(matrix, sx, y + height + spread, ex, y + height + spread, ex, y + height + spread - thickness, sx, y + height + spread - thickness, c2, c1, c1, c2);
         if (i < segments / 2) {
            float vt1 = (float) i / (segments / 2);
            float vt2 = (float) (i + 1) / (segments / 2);
            int v1 = withAlpha(sampleSpectrum(vt1 * STELLAR_SPECTRUM.length - drift), alpha * 0.9F);
            int v2 = withAlpha(sampleSpectrum(vt2 * STELLAR_SPECTRUM.length - drift), alpha * 0.9F);
            float sy = y + height * vt1;
            float ey = y + height * vt2;
            drawQuad(matrix, x - spread, sy, x - spread + thickness, sy, x - spread + thickness, ey, x - spread, ey, v1, v1, v2, v2);
            drawQuad(matrix, x + width + spread, sy, x + width + spread - thickness, sy, x + width + spread - thickness, ey, x + width + spread, ey, v2, v2, v1, v1);
         }
      }
   }

   /** 精密观测终端四角 L 型卡尺标线。 */
   private static void drawCornerCalipers(Matrix4f matrix, int x, int y, int width, int height, float time) {
      int cyan = withAlpha(COLOR_CYAN, 0.72F + 0.18F * (0.5F + 0.5F * Mth.sin(time * 2.0F)));
      int white = withAlpha(COLOR_STAR_WHITE, 0.82F);
      int violet = withAlpha(COLOR_VIOLET, 0.62F);
      float arm = 12.0F;
      // 左上
      drawBeam(matrix, x + 5.0F, y + 5.0F, x + 5.0F + arm, y + 5.0F, 0.95F, white, cyan);
      drawBeam(matrix, x + 5.0F, y + 5.0F, x + 5.0F, y + 5.0F + arm, 0.95F, white, violet);
      // 右上
      drawBeam(matrix, x + width - 5.0F, y + 5.0F, x + width - 5.0F - arm, y + 5.0F, 0.95F, white, cyan);
      drawBeam(matrix, x + width - 5.0F, y + 5.0F, x + width - 5.0F, y + 5.0F + arm, 0.95F, white, violet);
      // 左下
      drawBeam(matrix, x + 5.0F, y + height - 5.0F, x + 5.0F + arm, y + height - 5.0F, 0.95F, white, cyan);
      drawBeam(matrix, x + 5.0F, y + height - 5.0F, x + 5.0F, y + height - 5.0F - arm, 0.95F, white, violet);
      // 右下
      drawBeam(matrix, x + width - 5.0F, y + height - 5.0F, x + width - 5.0F - arm, y + height - 5.0F, 0.95F, white, cyan);
      drawBeam(matrix, x + width - 5.0F, y + height - 5.0F, x + width - 5.0F, y + height - 5.0F - arm, 0.95F, white, violet);
   }

   private static void beginQuadBatch() {
      if (activeQuadBatch != null) {
         return;
      }
      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      activeQuadBatch = builder;
      activeQuadBatchHasVertices = false;
   }

   private static void endQuadBatch() {
      BufferBuilder builder = activeQuadBatch;
      if (builder == null) {
         return;
      }
      activeQuadBatch = null;
      boolean hasVertices = activeQuadBatchHasVertices;
      activeQuadBatchHasVertices = false;
      if (!hasVertices) {
         if (builder.building()) {
            BufferBuilder.RenderedBuffer stray = builder.endOrDiscardIfEmpty();
            if (stray != null) {
               stray.release();
            }
         }
         return;
      }
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.disableDepthTest();
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      BufferUploader.drawWithShader(builder.end());
      RenderSystem.enableDepthTest();
      RenderSystem.disableBlend();
   }

   private static void drawBeam(Matrix4f matrix, float x1, float y1, float x2, float y2, float width, int colorA, int colorB) {
      float dx = x2 - x1;
      float dy = y2 - y1;
      float length = Mth.sqrt(dx * dx + dy * dy);
      if (length < 0.001F) {
         return;
      }
      float nx = -dy / length * width;
      float ny = dx / length * width;
      drawQuad(matrix, x1 - nx, y1 - ny, x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, colorA, colorA, colorB, colorB);
   }

   private static void drawQuad(Matrix4f matrix, float x1, float y1, float x2, float y2, int color) {
      drawQuad(matrix, x1, y1, x2, y1, x2, y2, x1, y2, color, color, color, color);
   }

   private static void drawQuad(
      Matrix4f matrix,
      float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
      int color1, int color2, int color3, int color4
   ) {
      if (activeQuadBatch != null) {
         vertex(activeQuadBatch, matrix, x1, y1, color1);
         vertex(activeQuadBatch, matrix, x2, y2, color2);
         vertex(activeQuadBatch, matrix, x3, y3, color3);
         vertex(activeQuadBatch, matrix, x4, y4, color4);
         activeQuadBatchHasVertices = true;
         return;
      }
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.disableDepthTest();
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      BufferBuilder builder = Tesselator.getInstance().getBuilder();
      builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      vertex(builder, matrix, x1, y1, color1);
      vertex(builder, matrix, x2, y2, color2);
      vertex(builder, matrix, x3, y3, color3);
      vertex(builder, matrix, x4, y4, color4);
      BufferUploader.drawWithShader(builder.end());
      RenderSystem.enableDepthTest();
      RenderSystem.disableBlend();
   }

   private static void vertex(BufferBuilder builder, Matrix4f matrix, float x, float y, int color) {
      builder.vertex(matrix, x, y, 0.0F)
         .color(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >>> 24 & 0xFF)
         .endVertex();
   }

   private static int sampleSpectrum(float position) {
      int length = STELLAR_SPECTRUM.length;
      float wrapped = position - (float) Math.floor(position / length) * length;
      if (wrapped >= length) {
         wrapped = 0.0F;
      }
      int index = Math.min(length - 1, Math.max(0, (int) Math.floor(wrapped)));
      int next = index == length - 1 ? 0 : index + 1;
      float blend = wrapped - index;
      blend = blend * blend * (3.0F - 2.0F * blend);
      return lerpColor(STELLAR_SPECTRUM[index], STELLAR_SPECTRUM[next], blend);
   }

   private static int lerpColor(int from, int to, float blend) {
      int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * blend);
      int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * blend);
      int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * blend);
      return r << 16 | g << 8 | b;
   }

   private static int lerpArgb(int from, int to, float blend) {
      float t = Mth.clamp(blend, 0.0F, 1.0F);
      int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
      int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
      int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
      int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
      return a << 24 | r << 16 | g << 8 | b;
   }

   private static int withAlpha(int rgb, float alpha) {
      int a = Mth.clamp(Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F), 0, 255);
      return (rgb & 0xFFFFFF) | a << 24;
   }

   private static float noise(float seed) {
      float value = Mth.sin(seed) * 43758.545F;
      return value - (float) Math.floor(value);
   }

   private record AuthorityChip(String key, int color) {
   }

   private record SkillChip(Component label, int color) {
   }

   private record StatCell(String labelKey, String value, int color) {
   }

   private record EnchantmentLine(String sortKey, String text) {
   }

   private record BladeStats(int proudSoul, int killCount, int refine, float baseAttack) {
   }

   private record Point2(float x, float y) {
   }
}
