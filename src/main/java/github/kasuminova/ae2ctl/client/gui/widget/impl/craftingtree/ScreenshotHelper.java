package github.kasuminova.ae2ctl.client.gui.widget.impl.craftingtree;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.ReadableNumberConverter;
import github.kasuminova.ae2ctl.AE2CTLegacy;
import github.kasuminova.ae2ctl.client.gui.widget.base.DynamicWidget;
import github.kasuminova.ae2ctl.common.integration.ae2.data.LiteCraftTreeNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenshotHelper {

    private static final Logger LOGGER = LogManager.getLogger(ScreenshotHelper.class);
    private static final AtomicBoolean TAKING_SCREENSHOT = new AtomicBoolean(false);

    /** Scale factor: S = 8 guarantees native 128x128 item icons (16 * 8) and 160x160 slots (20 * 8). */
    public static final int SCALE = 8;
    public static final int ITEM_SIZE = 16 * SCALE; // 128 px
    public static final int SLOT_SIZE = 20 * SCALE; // 160 px

    private static class RenderableNode {
        final int guiX;
        final int guiY;
        final int itemIndex;
        final String amountText;
        final boolean isMissing;
        final long missingAmount;
        final boolean isRoot;
        final int linkedSubNodes;
        final boolean hasChildren;

        RenderableNode(int guiX, int guiY, int itemIndex, String amountText,
                       boolean isMissing, long missingAmount, boolean isRoot,
                       int linkedSubNodes, boolean hasChildren) {
            this.guiX = guiX;
            this.guiY = guiY;
            this.itemIndex = itemIndex;
            this.amountText = amountText;
            this.isMissing = isMissing;
            this.missingAmount = missingAmount;
            this.isRoot = isRoot;
            this.linkedSubNodes = linkedSubNodes;
            this.hasChildren = hasChildren;
        }
    }

    private static class ItemAtlas {
        final BufferedImage image;
        final int cols;
        final int itemRenderSize;
        final int startIndex;
        final int count;

        ItemAtlas(BufferedImage image, int cols, int itemRenderSize, int startIndex, int count) {
            this.image = image;
            this.cols = cols;
            this.itemRenderSize = itemRenderSize;
            this.startIndex = startIndex;
            this.count = count;
        }

        BufferedImage getItemSprite(int globalIndex) {
            int localIndex = globalIndex - startIndex;
            if (localIndex < 0 || localIndex >= count) {
                return null;
            }
            int cx = (localIndex % cols) * itemRenderSize;
            int cy = (localIndex / cols) * itemRenderSize;
            if (cx + itemRenderSize <= image.getWidth() && cy + itemRenderSize <= image.getHeight()) {
                return image.getSubimage(cx, cy, itemRenderSize, itemRenderSize);
            }
            return null;
        }
    }

    public static void takeScreenshot(final CraftingTree tree) {
        if (!TAKING_SCREENSHOT.compareAndSet(false, true)) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() != null) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }

        try {
            if (tree == null || tree.getRoot() == null || tree.getWidgets().isEmpty()) {
                notifyPlayer(new TextComponentTranslation("gui.crafting_tree.screenshot.empty"));
                TAKING_SCREENSHOT.set(false);
                return;
            }

            List<DynamicWidget> rows = tree.getWidgets();
            List<IAEItemStack> uniqueItems = new ArrayList<>();
            Map<IAEItemStack, Integer> itemIndexMap = new HashMap<>();
            List<RenderableNode> renderableNodes = new ArrayList<>();

            int rowY = 0;
            int totalNodes = 0;
            int missingNodes = 0;

            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;

            for (DynamicWidget rowWidget : rows) {
                if (!(rowWidget instanceof TreeRow treeRow) || treeRow.isDisabled()) {
                    continue;
                }
                int curRowY = rowY + treeRow.getMarginUp();
                int x = 0;
                for (DynamicWidget nodeWidget : treeRow.getWidgets()) {
                    if (nodeWidget.isDisabled()) {
                        continue;
                    }
                    if (nodeWidget instanceof TreeNode treeNode) {
                        int nodeX = x + treeNode.getMarginLeft();
                        int nodeY = curRowY + treeNode.getMarginUp();
                        LiteCraftTreeNode lNode = treeNode.getNode();

                        int itemIdx = -1;
                        String amountText = "";
                        boolean isMissing = false;
                        long missingAmount = 0;
                        boolean isRoot = treeNode.isRoot();
                        int linkedSubNodes = treeNode.getLinkedSubNodes();
                        boolean hasChildren = false;

                        if (lNode != null) {
                            totalNodes++;
                            isMissing = LiteCraftTreeNode.isMissing(lNode);
                            if (isMissing) {
                                missingNodes++;
                                missingAmount = lNode.missing();
                            }
                            hasChildren = !lNode.inputs().isEmpty();

                            IAEItemStack out = lNode.output();
                            if (out != null) {
                                Integer idx = itemIndexMap.get(out);
                                if (idx == null) {
                                    idx = uniqueItems.size();
                                    uniqueItems.add(out);
                                    itemIndexMap.put(out, idx);
                                }
                                itemIdx = idx;

                                if (out.getStackSize() > 0) {
                                    amountText = ReadableNumberConverter.INSTANCE.toSlimReadableForm(out.getStackSize());
                                }
                            }
                        }

                        renderableNodes.add(new RenderableNode(
                                nodeX, nodeY, itemIdx, amountText, isMissing, missingAmount, isRoot, linkedSubNodes, hasChildren
                        ));

                        minX = Math.min(minX, nodeX);
                        maxX = Math.max(maxX, nodeX + 20);
                        minY = Math.min(minY, nodeY);
                        maxY = Math.max(maxY, nodeY + 26);
                        if (linkedSubNodes > 0) {
                            int hLineEnd = nodeX + 9 + (linkedSubNodes * 26) + 1;
                            maxX = Math.max(maxX, hLineEnd);
                        }
                    }
                    x += nodeWidget.getMarginLeft() + nodeWidget.getWidth() + nodeWidget.getMarginRight();
                }
                rowY += treeRow.getMarginUp() + treeRow.getHeight() + treeRow.getMarginDown();
            }

            if (renderableNodes.isEmpty()) {
                notifyPlayer(new TextComponentTranslation("gui.crafting_tree.screenshot.empty"));
                TAKING_SCREENSHOT.set(false);
                return;
            }

            final int S = SCALE;
            final int itemRenderSize = ITEM_SIZE;

            List<ItemAtlas> atlases = Collections.emptyList();
            if (!uniqueItems.isEmpty() && OpenGlHelper.isFramebufferEnabled()) {
                atlases = renderItemAtlases(mc, uniqueItems, itemRenderSize, S);
            }

            boolean darkMode = tree.isDarkMode();
            BufferedImage guiTexture = null;
            ResourceLocation resLoc = new ResourceLocation(AE2CTLegacy.MOD_ID,
                    darkMode ? "textures/gui/guicraftingtree_dark.png" : "textures/gui/guicraftingtree_light.png");
            try (InputStream is = mc.getResourceManager().getResource(resLoc).getInputStream()) {
                guiTexture = ImageIO.read(is);
            } catch (Exception e) {
                LOGGER.warn("Failed to load GUI texture for screenshot, fallback to procedural slots", e);
            }

            LiteCraftTreeNode rootNode = tree.getRoot();
            String rootItemName = "";
            String rootItemCount = "";
            if (rootNode != null && rootNode.output() != null) {
                IAEItemStack rootStack = rootNode.output();
                ItemStack def = rootStack.getDefinition();
                if (def != null && !def.isEmpty()) {
                    rootItemName = TextFormatting.getTextWithoutFormattingCodes(def.getDisplayName());
                }
                if (rootItemName == null) {
                    rootItemName = "";
                }
                rootItemCount = "x" + ReadableNumberConverter.INSTANCE.toSlimReadableForm(rootStack.getStackSize());
            }
            boolean isMissingOnly = tree.isMissingOnly();

            final List<ItemAtlas> finalAtlases = atlases;
            final BufferedImage finalGuiTex = guiTexture;
            final List<RenderableNode> finalNodes = renderableNodes;
            final String finalItemName = rootItemName;
            final String finalItemCount = rootItemCount;
            final boolean finalMissingOnly = isMissingOnly;
            final int finalTotalNodes = totalNodes;
            final int finalMissingNodes = missingNodes;
            final boolean finalDarkMode = darkMode;
            final int finalMinX = minX;
            final int finalMaxX = maxX;
            final int finalMinY = minY;
            final int finalMaxY = maxY;
            final File gameDir = mc.gameDir;

            ForkJoinPool.commonPool().execute(() -> {
                try {
                    BufferedImage screenshot = renderFullTree(
                            finalNodes, finalAtlases, finalGuiTex,
                            finalItemName, finalItemCount, finalMissingOnly,
                            finalTotalNodes, finalMissingNodes, finalDarkMode,
                            S, finalMinX, finalMaxX, finalMinY, finalMaxY
                    );
                    saveAndNotify(gameDir, screenshot);
                } catch (Exception e) {
                    LOGGER.error("Failed to generate crafting tree screenshot", e);
                    notifyPlayer(new TextComponentTranslation("gui.crafting_tree.screenshot.failed", e.getMessage()));
                } finally {
                    TAKING_SCREENSHOT.set(false);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to prepare crafting tree screenshot", e);
            notifyPlayer(new TextComponentTranslation("gui.crafting_tree.screenshot.failed", e.getMessage()));
            TAKING_SCREENSHOT.set(false);
        }
    }

    private static List<ItemAtlas> renderItemAtlases(final Minecraft mc, final List<IAEItemStack> items,
                                                    final int itemRenderSize, final int S) {
        int maxTexSize = 4096;
        try {
            maxTexSize = Math.max(4096, GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE));
        } catch (Throwable ignored) {
        }

        int maxCols = Math.max(1, maxTexSize / itemRenderSize);
        int maxRows = Math.max(1, maxTexSize / itemRenderSize);
        int maxPerAtlas = maxCols * maxRows;

        List<ItemAtlas> result = new ArrayList<>();
        int totalItems = items.size();

        for (int startIdx = 0; startIdx < totalItems; startIdx += maxPerAtlas) {
            int endIdx = Math.min(totalItems, startIdx + maxPerAtlas);
            List<IAEItemStack> subList = items.subList(startIdx, endIdx);

            int cols = Math.max(1, (int) Math.ceil(Math.sqrt(subList.size())));
            cols = Math.min(cols, maxCols);
            int rowsCount = Math.max(1, (int) Math.ceil((double) subList.size() / cols));
            int atlasW = cols * itemRenderSize;
            int atlasH = rowsCount * itemRenderSize;

            BufferedImage atlasImg = renderSingleAtlas(mc, subList, cols, rowsCount, atlasW, atlasH);
            if (atlasImg != null) {
                result.add(new ItemAtlas(atlasImg, cols, itemRenderSize, startIdx, subList.size()));
            }
        }

        return result;
    }

    private static BufferedImage renderSingleAtlas(final Minecraft mc, final List<IAEItemStack> items,
                                                   final int cols, final int rowsCount,
                                                   final int atlasW, final int atlasH) {
        // Compute virtual GUI dimensions in 16x16 units matching Minecraft's ScaledResolution approach.
        // By setting the ortho projection to (0..guiW, 0..guiH) while mapping to the (atlasW x atlasH) viewport,
        // the GPU rasterizes each 16x16 GUI item natively into 128x128 framebuffer pixels without any Modelview
        // scaling hacks. This preserves 100% authentic Minecraft GUI lighting, normals, and 3D proportions.
        int guiW = cols * 16;
        int guiH = rowsCount * 16;

        Framebuffer fb = new Framebuffer(atlasW, atlasH, true);
        fb.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
        fb.framebufferClear();
        fb.bindFramebuffer(true);

        GlStateManager.viewport(0, 0, atlasW, atlasH);

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, (double) guiW, (double) guiH, 0.0D, 1000.0D, 3000.0D);

        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0.0F, 0.0F, -2000.0F);

        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );

        for (int i = 0; i < items.size(); i++) {
            int gx = (i % cols) * 16;
            int gy = (i / cols) * 16;
            ItemStack stack = items.get(i).getCachedItemStack(1);
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
            RenderHelper.enableGUIStandardItemLighting();

            mc.getRenderItem().renderItemAndEffectIntoGUI(null, stack, gx, gy);
        }

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();

        int totalPixels = atlasW * atlasH;
        IntBuffer pixelBuffer = BufferUtils.createIntBuffer(totalPixels);
        int[] pixelArray = new int[totalPixels];

        GlStateManager.bindTexture(fb.framebufferTexture);
        GlStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        pixelBuffer.get(pixelArray);
        TextureUtil.processPixelValues(pixelArray, atlasW, atlasH);

        BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        atlas.setRGB(0, 0, atlasW, atlasH, pixelArray, 0, atlasW);

        fb.deleteFramebuffer();
        mc.getFramebuffer().bindFramebuffer(true);

        return atlas;
    }

    private static BufferedImage renderFullTree(final List<RenderableNode> nodes,
                                                final List<ItemAtlas> atlases,
                                                final BufferedImage guiTex,
                                                final String itemName,
                                                final String itemCount,
                                                final boolean isMissingOnly,
                                                final int totalNodes,
                                                final int missingNodes,
                                                final boolean darkMode,
                                                final int S,
                                                final int minX,
                                                final int maxX,
                                                final int minY,
                                                final int maxY) {
        int PAD = 48;
        int HEADER_HEIGHT = 100;

        int treeWidth = Math.max(26, maxX - minX);
        int treeHeight = Math.max(26, maxY - minY);
        int contentW = treeWidth * S;
        int contentH = treeHeight * S;

        // Fonts
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 26);
        Font tagFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);
        Font subFont = new Font(Font.SANS_SERIF, Font.PLAIN, 18);
        Font wmFont = new Font(Font.SANS_SERIF, Font.BOLD, 18);

        String fullTitle = I18n.format("gui.crafting_tree.title");
        if (!itemName.isEmpty()) {
            fullTitle += " - " + itemName + " " + itemCount;
        }
        String missingOnlyTag = isMissingOnly ? I18n.format("gui.crafting_tree.screenshot.missing_only_tag") : "";

        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String subInfo = dateStr + "   |   Nodes: " + totalNodes;
        if (missingNodes > 0) {
            subInfo += "   |   Missing: " + missingNodes;
        }
        String watermark = "AE2 Crafting Tree";

        // Measure text widths using a dummy graphics context to prevent overlaps
        BufferedImage dummyImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D gDummy = dummyImg.createGraphics();
        gDummy.setFont(titleFont);
        int titleW = gDummy.getFontMetrics().stringWidth(fullTitle);
        gDummy.setFont(tagFont);
        int tagW = isMissingOnly ? gDummy.getFontMetrics().stringWidth(missingOnlyTag) : 0;
        gDummy.setFont(subFont);
        int subLeftW = gDummy.getFontMetrics().stringWidth(subInfo);
        gDummy.setFont(wmFont);
        int wmW = gDummy.getFontMetrics().stringWidth(watermark);
        gDummy.dispose();

        int titleTotalW = titleW + (isMissingOnly ? tagW + 28 : 0);
        int minReqW = Math.max(1200, Math.max(titleTotalW + PAD * 2, subLeftW + wmW + PAD * 4));
        int imgW = Math.max(minReqW, contentW + PAD * 2);
        int imgH = contentH + PAD * 2 + HEADER_HEIGHT;

        int offsetX = PAD;
        if (imgW > contentW + PAD * 2) {
            offsetX = (imgW - contentW) / 2;
        }

        BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // Background
        Color bgColor = darkMode ? new Color(0x24, 0x26, 0x31) : new Color(0x9A, 0x9F, 0xB4);
        g.setColor(bgColor);
        g.fillRect(0, 0, imgW, imgH);

        // Header bar
        Color headerBg = darkMode ? new Color(0x16, 0x18, 0x22) : new Color(0x7D, 0x82, 0x96);
        g.setColor(headerBg);
        g.fillRect(0, 0, imgW, HEADER_HEIGHT);
        Color headerBorder = darkMode ? new Color(0x35, 0x38, 0x47) : new Color(0xB5, 0xBA, 0xCE);
        g.setColor(headerBorder);
        g.fillRect(0, HEADER_HEIGHT - 3, imgW, 3);

        // Header text rendering
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Line 1: Title
        g.setFont(titleFont);
        g.setColor(Color.WHITE);
        g.drawString(fullTitle, PAD, 48);

        // Line 1: Missing Only badge
        if (isMissingOnly) {
            int tagX = PAD + titleW + 16;
            int badgeH = 28;
            int badgeY = 26;
            int badgeW = tagW + 16;
            g.setColor(new Color(0xD9, 0x53, 0x4F));
            g.fillRoundRect(tagX, badgeY, badgeW, badgeH, 8, 8);
            g.setFont(tagFont);
            g.setColor(Color.WHITE);
            g.drawString(missingOnlyTag, tagX + 8, badgeY + 21);
        }

        // Line 2: Subtitle info on left
        g.setFont(subFont);
        g.setColor(darkMode ? new Color(0x9E, 0xA3, 0xB5) : new Color(0x28, 0x2C, 0x37));
        g.drawString(subInfo, PAD, 84);

        // Line 2: Watermark on right
        g.setFont(wmFont);
        g.setColor(darkMode ? new Color(0x68, 0x7E, 0xA5) : new Color(0x3B, 0x47, 0x5E));
        g.drawString(watermark, imgW - PAD - wmW, 84);

        // GUI slot textures
        BufferedImage normalSlot = null;
        BufferedImage missingSlot = null;
        BufferedImage missingOverlay = null;

        if (guiTex != null && guiTex.getWidth() >= 60 && guiTex.getHeight() >= 256) {
            normalSlot = guiTex.getSubimage(0, 216, 20, 20);
            missingSlot = guiTex.getSubimage(0, 236, 20, 20);
            missingOverlay = guiTex.getSubimage(40, 216, 20, 20);
        }

        // Connecting lines configuration
        Color normalLineColor = new Color(0xF2, 0xF2, 0xF2);
        Color normalShadowColor = new Color(0x4D, 0x4D, 0x67);
        Color missingLineColor = new Color(0xEE, 0x63, 0x63);
        Color missingShadowColor = new Color(0x8B, 0x3A, 0x3A);

        int slotSize = SLOT_SIZE;
        int lineThick = S; // 8 px
        int lineOffset = (slotSize - lineThick) / 2; // (160 - 8) / 2 = 76 px
        int shadowOffset = Math.max(1, S / 2); // 4 px

        // Pass 1: Render all line shadows first
        for (RenderableNode node : nodes) {
            int nx = (node.guiX - minX) * S + offsetX;
            int ny = (node.guiY - minY) * S + PAD + HEADER_HEIGHT;
            int lineX = nx + lineOffset;
            Color sColor = node.isMissing ? missingShadowColor : normalShadowColor;
            g.setColor(sColor);

            if (!node.isRoot) {
                g.fillRect(lineX + shadowOffset, ny - 2 * S + shadowOffset, lineThick, 4 * S);
            }

            if (node.hasChildren) {
                if (node.linkedSubNodes > 0) {
                    g.fillRect(lineX + shadowOffset, ny + 22 * S + shadowOffset, lineThick, 2 * S);
                    int hWidth = node.linkedSubNodes * 26 * S + lineThick;
                    g.fillRect(lineX + shadowOffset, ny + 24 * S + shadowOffset, hWidth, lineThick);
                } else {
                    g.fillRect(lineX + shadowOffset, ny + 22 * S + shadowOffset, lineThick, 6 * S);
                }
            }
        }

        // Pass 2: Render all foreground lines
        for (RenderableNode node : nodes) {
            int nx = (node.guiX - minX) * S + offsetX;
            int ny = (node.guiY - minY) * S + PAD + HEADER_HEIGHT;
            int lineX = nx + lineOffset;
            Color lColor = node.isMissing ? missingLineColor : normalLineColor;
            g.setColor(lColor);

            if (!node.isRoot) {
                g.fillRect(lineX, ny - 2 * S, lineThick, 4 * S);
            }

            if (node.hasChildren) {
                if (node.linkedSubNodes > 0) {
                    g.fillRect(lineX, ny + 22 * S, lineThick, 2 * S);
                    int hWidth = node.linkedSubNodes * 26 * S + lineThick;
                    g.fillRect(lineX, ny + 24 * S, hWidth, lineThick);
                } else {
                    g.fillRect(lineX, ny + 22 * S, lineThick, 6 * S);
                }
            }
        }

        // Pass 3: Draw slots and items (pixel-art crisp integer scaled slot + native 128x128 items)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        int itemRenderSize = ITEM_SIZE;
        int itemOffset = 2 * S; // 16 px

        for (RenderableNode node : nodes) {
            int nx = (node.guiX - minX) * S + offsetX;
            int ny = (node.guiY - minY) * S + PAD + HEADER_HEIGHT;
            int slotX = nx;
            int slotY = ny + 2 * S;

            if (node.isMissing && missingSlot != null) {
                g.drawImage(missingSlot, slotX, slotY, slotSize, slotSize, null);
            } else if (normalSlot != null) {
                g.drawImage(normalSlot, slotX, slotY, slotSize, slotSize, null);
            } else {
                Color borderCol = node.isMissing ? new Color(0xAA, 0x30, 0x30) : (darkMode ? new Color(0x45, 0x48, 0x58) : new Color(0x70, 0x75, 0x88));
                Color fillCol = node.isMissing ? new Color(0x50, 0x18, 0x18) : (darkMode ? new Color(0x2A, 0x2C, 0x38) : new Color(0x8B, 0x90, 0xA2));
                g.setColor(fillCol);
                g.fillRect(slotX, slotY, slotSize, slotSize);
                g.setColor(borderCol);
                for (int b = 0; b < S; b++) {
                    g.drawRect(slotX + b, slotY + b, slotSize - 1 - 2 * b, slotSize - 1 - 2 * b);
                }
            }

            if (node.itemIndex >= 0 && atlases != null) {
                BufferedImage itemSprite = null;
                for (ItemAtlas atlas : atlases) {
                    itemSprite = atlas.getItemSprite(node.itemIndex);
                    if (itemSprite != null) {
                        break;
                    }
                }
                if (itemSprite != null) {
                    g.drawImage(itemSprite, slotX + itemOffset, slotY + itemOffset, itemRenderSize, itemRenderSize, null);
                }
            }

            if (node.isMissing && node.missingAmount > 0 && missingOverlay != null) {
                g.drawImage(missingOverlay, slotX, slotY, slotSize, slotSize, null);
            }
        }

        // Pass 4: Draw stack size text (high quality anti-aliased font)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Font amountFont = new Font(Font.SANS_SERIF, Font.BOLD, 36);
        g.setFont(amountFont);
        FontMetrics afm = g.getFontMetrics();

        for (RenderableNode node : nodes) {
            if (!node.amountText.isEmpty()) {
                int nx = (node.guiX - minX) * S + offsetX;
                int ny = (node.guiY - minY) * S + PAD + HEADER_HEIGHT;
                int slotX = nx;
                int slotY = ny + 2 * S;

                int tw = afm.stringWidth(node.amountText);
                int tx = slotX + slotSize - tw - (2 * S);
                int ty = slotY + slotSize - (2 * S);

                g.setColor(new Color(0x10, 0x10, 0x10));
                g.drawString(node.amountText, tx + shadowOffset, ty + shadowOffset);
                g.setColor(Color.WHITE);
                g.drawString(node.amountText, tx, ty);
            }
        }

        g.dispose();
        return image;
    }

    private static void saveAndNotify(final File gameDir, final BufferedImage screenshot) throws Exception {
        File screenshotsDir = new File(gameDir, "screenshots");
        if (!screenshotsDir.exists()) {
            screenshotsDir.mkdirs();
        }
        String timeStr = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        File targetFile = getUniqueFile(screenshotsDir, "CraftingTree_" + timeStr);
        boolean success = ImageIO.write(screenshot, "png", targetFile);
        if (!success) {
            throw new Exception("ImageIO failed to write PNG to " + targetFile.getAbsolutePath());
        }

        LOGGER.info("Crafting tree screenshot saved to {}", targetFile.getAbsolutePath());

        Minecraft.getMinecraft().addScheduledTask(() -> {
            ITextComponent link = new TextComponentString(targetFile.getName());
            link.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, targetFile.getAbsolutePath()));
            link.getStyle().setUnderlined(true);
            link.getStyle().setColor(TextFormatting.AQUA);
            ITextComponent message = new TextComponentTranslation("screenshot.success", link);
            if (Minecraft.getMinecraft().player != null) {
                Minecraft.getMinecraft().player.sendMessage(message);
            }
        });
    }

    private static File getUniqueFile(final File dir, final String baseName) {
        File file = new File(dir, baseName + ".png");
        int i = 1;
        while (file.exists()) {
            file = new File(dir, baseName + "_" + i + ".png");
            i++;
        }
        return file;
    }

    private static void notifyPlayer(final ITextComponent component) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (Minecraft.getMinecraft().player != null) {
                Minecraft.getMinecraft().player.sendMessage(component);
            }
        });
    }
}
