package github.kasuminova.ae2ctl.client.gui.widget.impl.craftingtree;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.ReadableNumberConverter;
import github.kasuminova.ae2ctl.common.integration.ae2.data.LiteCraftTreeNode;
import github.kasuminova.ae2ctl.common.integration.ae2.data.LiteCraftTreeProc;
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
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

public class FlowchartExportHelper {

    private static final Logger LOGGER = LogManager.getLogger(FlowchartExportHelper.class);
    private static final AtomicBoolean EXPORTING = new AtomicBoolean(false);

    public enum ExportFormat {
        SVG("SVG", ".svg"),
        PNG("PNG", ".png");

        private final String label;
        private final String extension;

        ExportFormat(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        public String getLabel() {
            return label;
        }

        public String getExtension() {
            return extension;
        }

        public ExportFormat next() {
            ExportFormat[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private static ExportFormat currentFormat = ExportFormat.SVG;

    public static ExportFormat getCurrentFormat() {
        return currentFormat;
    }

    public static void setCurrentFormat(ExportFormat format) {
        currentFormat = format != null ? format : ExportFormat.SVG;
    }

    public static ExportFormat toggleFormat() {
        currentFormat = currentFormat.next();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() != null) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
        return currentFormat;
    }

    private static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 20);
    private static final Font FONT_META = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font FONT_ROOT_NAME = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private static final Font FONT_ROOT_COUNT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font FONT_NODE_NAME = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font FONT_NODE_COUNT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

    private static final BasicStroke STROKE_LINE = new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_HEADER = new BasicStroke(1.5f);
    private static final BasicStroke STROKE_ROOT_BOX = new BasicStroke(1.5f);
    private static final BasicStroke STROKE_NODE_BOX = new BasicStroke(1.2f);

    private static final Color COLOR_HEADER_TITLE = new Color(0x1f, 0x29, 0x37);
    private static final Color COLOR_HEADER_META = new Color(0x6b, 0x72, 0x80);
    private static final Color COLOR_HEADER_LINE = new Color(0xe5, 0xe7, 0xeb);
    private static final Color COLOR_CONNECTING_LINE = new Color(0x55, 0x55, 0x55);
    private static final Color COLOR_ROOT_BG = new Color(0x00, 0x96, 0x5e);
    private static final Color COLOR_ROOT_BORDER = new Color(0x00, 0x7a, 0x4d);
    private static final Color COLOR_ROOT_COUNT = new Color(0xd1, 0xfa, 0xe5);
    private static final Color COLOR_NODE_BORDER = new Color(0x33, 0x33, 0x33);
    private static final Color COLOR_NODE_NAME = new Color(0x22, 0x22, 0x22);
    private static final Color COLOR_NODE_COUNT = new Color(0x66, 0x66, 0x66);

    private static final double PADDING_LEFT = 48.0;
    private static final double PADDING_TOP = 88.0;
    private static final double PADDING_RIGHT = 48.0;
    private static final double PADDING_BOTTOM = 48.0;
    private static final double H_GAP = 28.0;
    private static final double V_GAP = 46.0;

    private static class NodeLayout {
        final LiteCraftTreeNode node;
        final IAEItemStack item;
        final String name;
        final double nameWidth;
        final String amountText;
        final boolean isRoot;
        final int itemIndex;
        final List<NodeLayout> children = new ArrayList<>();

        double boxW;
        double boxH = 38.0;
        double relX = 0.0;
        double subtreeOriginOffset = 0.0;
        double x = 0.0;
        double y = 0.0;

        NodeLayout(LiteCraftTreeNode node, boolean isRoot, int itemIndex,
                   String name, double nameWidth, String amountText) {
            this.node = node;
            this.item = node != null ? node.output() : null;
            this.isRoot = isRoot;
            this.itemIndex = itemIndex;
            this.name = name;
            this.nameWidth = nameWidth;
            this.amountText = amountText;
        }
    }

    private static class SubtreeContour {
        final List<Double> leftContour = new ArrayList<>();
        final List<Double> rightContour = new ArrayList<>();
    }

    public static void exportFlowchart(final CraftingTree tree) {
        exportFlowchart(tree, currentFormat);
    }

    public static void exportFlowchartSvg(final CraftingTree tree) {
        exportFlowchart(tree, ExportFormat.SVG);
    }

    public static void exportFlowchartPng(final CraftingTree tree) {
        exportFlowchart(tree, ExportFormat.PNG);
    }

    public static void exportFlowchart(final CraftingTree tree, final ExportFormat format) {
        if (!EXPORTING.compareAndSet(false, true)) {
            return;
        }

        final ExportFormat selectedFormat = format != null ? format : ExportFormat.SVG;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() != null) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }

        try {
            // Flowchart always exports the complete crafting recipe tree
            LiteCraftTreeNode rootNode = tree != null
                    ? (tree.getFullRoot() != null ? tree.getFullRoot() : tree.getRoot())
                    : null;

            if (rootNode == null || rootNode.output() == null) {
                notifyPlayer(new TextComponentTranslation("gui.crafting_tree.export_flowchart.empty"));
                EXPORTING.set(false);
                return;
            }

            List<IAEItemStack> uniqueItems = new ArrayList<>();
            Map<IAEItemStack, Integer> itemIndexMap = new HashMap<>();

            BufferedImage dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D gDummy = dummy.createGraphics();
            Font fontName = new Font(Font.SANS_SERIF, Font.BOLD, 13);
            Font fontCount = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            FontMetrics fmName = gDummy.getFontMetrics(fontName);
            FontMetrics fmCount = gDummy.getFontMetrics(fontCount);

            NodeLayout rootLayout = buildLayoutTree(rootNode, true, uniqueItems, itemIndexMap, fmName, fmCount);
            gDummy.dispose();

            if (rootLayout == null) {
                notifyPlayer(new TextComponentTranslation("gui.crafting_tree.export_flowchart.empty"));
                EXPORTING.set(false);
                return;
            }

            // Render 48x48 item icons in OpenGL framebuffer on main thread
            int iconSize = 48;
            BufferedImage atlasImage = null;
            int cols = 1;
            if (!uniqueItems.isEmpty() && OpenGlHelper.isFramebufferEnabled()) {
                cols = Math.max(1, (int) Math.ceil(Math.sqrt(uniqueItems.size())));
                int rows = Math.max(1, (int) Math.ceil((double) uniqueItems.size() / cols));
                int atlasW = cols * iconSize;
                int atlasH = rows * iconSize;
                atlasImage = renderIconAtlas(mc, uniqueItems, cols, rows, atlasW, atlasH);
            }

            final BufferedImage finalAtlas = atlasImage;
            final int finalCols = cols;
            final int finalIconSize = iconSize;
            final int uniqueCount = uniqueItems.size();
            final NodeLayout finalRoot = rootLayout;
            final File gameDir = mc.gameDir;

            ForkJoinPool.commonPool().execute(() -> {
                try {
                    // 1. Crop item icons
                    Map<Integer, BufferedImage> iconImages = new HashMap<>();
                    Map<Integer, String> iconBase64Map = new HashMap<>();
                    if (finalAtlas != null) {
                        for (int i = 0; i < uniqueCount; i++) {
                            int cx = (i % finalCols) * finalIconSize;
                            int cy = (i / finalCols) * finalIconSize;
                            if (cx + finalIconSize <= finalAtlas.getWidth() && cy + finalIconSize <= finalAtlas.getHeight()) {
                                BufferedImage iconImg = finalAtlas.getSubimage(cx, cy, finalIconSize, finalIconSize);
                                iconImages.put(i, iconImg);
                                if (selectedFormat == ExportFormat.SVG) {
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    ImageIO.write(iconImg, "png", baos);
                                    iconBase64Map.put(i, Base64.getEncoder().encodeToString(baos.toByteArray()));
                                }
                            }
                        }
                    }

                    // 2. Compute tidy tree layout
                    computeLayout(finalRoot);

                    // 3. Save to screenshots directory
                    File screenshotsDir = new File(gameDir, "screenshots");
                    if (!screenshotsDir.exists()) {
                        screenshotsDir.mkdirs();
                    }
                    String timeStr = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
                    File targetFile = getUniqueFile(screenshotsDir, "CraftingTree_Flowchart_" + timeStr, selectedFormat.getExtension());

                    if (selectedFormat == ExportFormat.SVG) {
                        String svgContent = generateSvg(finalRoot, iconBase64Map);
                        Files.write(targetFile.toPath(), svgContent.getBytes(StandardCharsets.UTF_8));
                    } else if (selectedFormat == ExportFormat.PNG) {
                        BufferedImage pngImage = generatePng(finalRoot, iconImages);
                        ImageIO.write(pngImage, "png", targetFile);
                    }

                    LOGGER.info("Crafting tree flowchart {} saved to {}", selectedFormat.getLabel(), targetFile.getAbsolutePath());

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
                } catch (Exception e) {
                    LOGGER.error("Failed to export flowchart " + selectedFormat.getLabel(), e);
                    notifyPlayer(new TextComponentTranslation("gui.crafting_tree.export_flowchart.failed", e.getMessage()));
                } finally {
                    EXPORTING.set(false);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to prepare flowchart export", e);
            notifyPlayer(new TextComponentTranslation("gui.crafting_tree.export_flowchart.failed", e.getMessage()));
            EXPORTING.set(false);
        }
    }

    private static NodeLayout buildLayoutTree(final LiteCraftTreeNode node, final boolean isRoot,
                                              final List<IAEItemStack> uniqueItems,
                                              final Map<IAEItemStack, Integer> itemIndexMap,
                                              final FontMetrics fmName, final FontMetrics fmCount) {
        if (node == null) {
            return null;
        }

        IAEItemStack out = node.output();
        int itemIndex = -1;
        String name = "";
        String amountText = "";

        if (out != null) {
            Integer idx = itemIndexMap.get(out);
            if (idx == null) {
                idx = uniqueItems.size();
                uniqueItems.add(out);
                itemIndexMap.put(out, idx);
            }
            itemIndex = idx;

            ItemStack def = out.getDefinition();
            if (def != null && !def.isEmpty()) {
                name = TextFormatting.getTextWithoutFormattingCodes(def.getDisplayName());
            }
            if (name == null) {
                name = "";
            }
            if (out.getStackSize() > 0) {
                amountText = "x" + ReadableNumberConverter.INSTANCE.toSlimReadableForm(out.getStackSize());
            }
        }

        int nw = fmName.stringWidth(name);
        NodeLayout layoutNode = new NodeLayout(node, isRoot, itemIndex, name, nw, amountText);

        // Calculate box width without missing information
        int cw = amountText.isEmpty() ? 0 : fmCount.stringWidth(amountText) + 8;
        int iw = (itemIndex >= 0) ? (24 + 8) : 0;
        layoutNode.boxW = Math.max(90.0, 12 + iw + nw + cw + 12);
        layoutNode.boxH = 38.0;

        for (LiteCraftTreeProc proc : node.inputs()) {
            for (LiteCraftTreeNode input : proc.inputs()) {
                NodeLayout child = buildLayoutTree(input, false, uniqueItems, itemIndexMap, fmName, fmCount);
                if (child != null) {
                    layoutNode.children.add(child);
                }
            }
        }

        return layoutNode;
    }

    private static BufferedImage renderIconAtlas(final Minecraft mc, final List<IAEItemStack> items,
                                                 final int cols, final int rows,
                                                 final int atlasW, final int atlasH) {
        int guiW = cols * 16;
        int guiH = rows * 16;

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

    private static void computeLayout(final NodeLayout root) {
        // Phase 1: Compute relative positions bottom-up using subtree contours
        computeSubtreeRelative(root);

        // Phase 2: Compute absolute positions top-down
        computeAbsolute(root, 0.0, 0);

        // Phase 3: Shift all nodes so that minX matches PADDING_LEFT
        double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        findBounds(root, bounds);
        double minX = bounds[0];
        double shiftX = PADDING_LEFT - minX;
        applyShiftX(root, shiftX);
    }

    private static SubtreeContour computeSubtreeRelative(final NodeLayout node) {
        SubtreeContour nodeContour = new SubtreeContour();

        if (node.children.isEmpty()) {
            node.relX = 0.0;
            nodeContour.leftContour.add(0.0);
            nodeContour.rightContour.add(node.boxW);
            return nodeContour;
        }

        List<SubtreeContour> childContours = new ArrayList<>();
        List<Double> combinedLeft = new ArrayList<>();
        List<Double> combinedRight = new ArrayList<>();

        for (int i = 0; i < node.children.size(); i++) {
            NodeLayout child = node.children.get(i);
            SubtreeContour childContour = computeSubtreeRelative(child);
            childContours.add(childContour);

            if (i == 0) {
                child.subtreeOriginOffset = 0.0;
                combinedLeft.addAll(childContour.leftContour);
                combinedRight.addAll(childContour.rightContour);
            } else {
                double shift = 0.0;
                int commonDepth = Math.min(combinedRight.size(), childContour.leftContour.size());
                for (int d = 0; d < commonDepth; d++) {
                    double overlap = combinedRight.get(d) + H_GAP - childContour.leftContour.get(d);
                    if (overlap > shift) {
                        shift = overlap;
                    }
                }
                child.subtreeOriginOffset = shift;

                for (int d = 0; d < childContour.rightContour.size(); d++) {
                    double shiftedRight = childContour.rightContour.get(d) + shift;
                    if (d < combinedRight.size()) {
                        combinedRight.set(d, Math.max(combinedRight.get(d), shiftedRight));
                    } else {
                        combinedRight.add(shiftedRight);
                    }
                }

                for (int d = 0; d < childContour.leftContour.size(); d++) {
                    double shiftedLeft = childContour.leftContour.get(d) + shift;
                    if (d >= combinedLeft.size()) {
                        combinedLeft.add(shiftedLeft);
                    }
                }
            }
        }

        NodeLayout firstChild = node.children.get(0);
        NodeLayout lastChild = node.children.get(node.children.size() - 1);

        double firstChildCenter = firstChild.subtreeOriginOffset + firstChild.relX + (firstChild.boxW / 2.0);
        double lastChildCenter = lastChild.subtreeOriginOffset + lastChild.relX + (lastChild.boxW / 2.0);
        double desiredCenter = (firstChildCenter + lastChildCenter) / 2.0;

        node.relX = desiredCenter - (node.boxW / 2.0);

        if (node.relX < 0.0) {
            double extraShift = -node.relX;
            node.relX = 0.0;
            for (NodeLayout child : node.children) {
                child.subtreeOriginOffset += extraShift;
            }
            for (int d = 0; d < combinedLeft.size(); d++) {
                combinedLeft.set(d, combinedLeft.get(d) + extraShift);
                combinedRight.set(d, combinedRight.get(d) + extraShift);
            }
        }

        nodeContour.leftContour.add(node.relX);
        nodeContour.rightContour.add(node.relX + node.boxW);

        for (int d = 0; d < combinedLeft.size(); d++) {
            nodeContour.leftContour.add(combinedLeft.get(d));
            nodeContour.rightContour.add(combinedRight.get(d));
        }

        return nodeContour;
    }

    private static void computeAbsolute(final NodeLayout node, final double subtreeOriginX, final int depth) {
        node.x = subtreeOriginX + node.relX;
        node.y = PADDING_TOP + depth * (node.boxH + V_GAP);

        for (NodeLayout child : node.children) {
            computeAbsolute(child, subtreeOriginX + child.subtreeOriginOffset, depth + 1);
        }
    }

    private static void findBounds(final NodeLayout node, final double[] bounds) {
        bounds[0] = Math.min(bounds[0], node.x);
        bounds[1] = Math.max(bounds[1], node.x + node.boxW);
        bounds[2] = Math.max(bounds[2], node.y + node.boxH);

        for (NodeLayout child : node.children) {
            findBounds(child, bounds);
        }
    }

    private static void applyShiftX(final NodeLayout node, final double shiftX) {
        node.x += shiftX;
        for (NodeLayout child : node.children) {
            applyShiftX(child, shiftX);
        }
    }

    private static String generateSvg(final NodeLayout root, final Map<Integer, String> iconBase64Map) {
        double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        findBounds(root, bounds);

        int totalNodes = countNodes(root);
        double maxX = bounds[1];
        double maxY = bounds[2];

        String fullTitle = I18n.format("gui.crafting_tree.title");
        if (!root.name.isEmpty()) {
            fullTitle += " - " + root.name + " " + root.amountText;
        }

        int svgWidth = (int) Math.ceil(Math.max(1000.0, maxX + PADDING_RIGHT));
        int svgHeight = (int) Math.ceil(maxY + PADDING_BOTTOM);

        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String metaText = dateStr + "   |   " + I18n.format("gui.crafting_tree.title")
                + "   |   Nodes: " + totalNodes + "   |   AE2 Crafting Tree";

        StringBuilder sb = new StringBuilder(65536);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ");
        sb.append("width=\"").append(svgWidth).append("\" height=\"").append(svgHeight).append("\" ");
        sb.append("viewBox=\"0 0 ").append(svgWidth).append(" ").append(svgHeight).append("\" ");
        sb.append("style=\"background-color: #ffffff; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, 'Microsoft YaHei', sans-serif;\">\n\n");

        // Definitions: Embedded 24x24 Item Icons
        sb.append("  <defs>\n");
        for (Map.Entry<Integer, String> entry : iconBase64Map.entrySet()) {
            sb.append("    <image id=\"icon-").append(entry.getKey()).append("\" width=\"24\" height=\"24\" ")
                    .append("href=\"data:image/png;base64,").append(entry.getValue()).append("\"/>\n");
        }
        sb.append("  </defs>\n\n");

        // Background
        sb.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n\n");

        // Header Title & Metadata
        sb.append("  <g id=\"header\">\n");
        sb.append("    <text x=\"48\" y=\"38\" font-size=\"20\" font-weight=\"bold\" fill=\"#1f2937\">")
                .append(escapeXml(fullTitle)).append("</text>\n");
        sb.append("    <text x=\"48\" y=\"62\" font-size=\"13\" fill=\"#6b7280\">")
                .append(escapeXml(metaText)).append("</text>\n");
        sb.append("    <line x1=\"48\" y1=\"74\" x2=\"").append(svgWidth - 48).append("\" y2=\"74\" stroke=\"#e5e7eb\" stroke-width=\"1.5\"/>\n");
        sb.append("  </g>\n\n");

        // Connecting Lines Layer
        sb.append("  <g id=\"connecting-lines\">\n");
        appendLines(sb, root);
        sb.append("  </g>\n\n");

        // Node Boxes Layer
        sb.append("  <g id=\"node-boxes\">\n");
        appendNodes(sb, root);
        sb.append("  </g>\n\n");

        sb.append("</svg>\n");
        return sb.toString();
    }

    private static BufferedImage generatePng(final NodeLayout root, final Map<Integer, BufferedImage> iconImages) {
        double[] bounds = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        findBounds(root, bounds);

        int totalNodes = countNodes(root);
        double maxX = bounds[1];
        double maxY = bounds[2];

        String fullTitle = I18n.format("gui.crafting_tree.title");
        if (!root.name.isEmpty()) {
            fullTitle += " - " + root.name + " " + root.amountText;
        }

        int logicalWidth = (int) Math.ceil(Math.max(1000.0, maxX + PADDING_RIGHT));
        int logicalHeight = (int) Math.ceil(maxY + PADDING_BOTTOM);

        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String metaText = dateStr + "   |   " + I18n.format("gui.crafting_tree.title")
                + "   |   Nodes: " + totalNodes + "   |   AE2 Crafting Tree";

        double scale = 2.0;
        if ((long) logicalWidth * 2 * logicalHeight * 2 > 64_000_000L) {
            scale = 1.0;
        }

        int imgWidth = (int) Math.ceil(logicalWidth * scale);
        int imgHeight = (int) Math.ceil(logicalHeight * scale);

        BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // Background
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, imgWidth, imgHeight);

            // Scale to logical coordinates
            g2d.scale(scale, scale);

            // Header Title & Metadata
            g2d.setColor(COLOR_HEADER_TITLE);
            g2d.setFont(FONT_TITLE);
            g2d.drawString(fullTitle, 48f, 38f);

            g2d.setColor(COLOR_HEADER_META);
            g2d.setFont(FONT_META);
            g2d.drawString(metaText, 48f, 62f);

            g2d.setColor(COLOR_HEADER_LINE);
            g2d.setStroke(STROKE_HEADER);
            g2d.draw(new Line2D.Double(48.0, 74.0, logicalWidth - 48.0, 74.0));

            // Connecting Lines
            g2d.setColor(COLOR_CONNECTING_LINE);
            g2d.setStroke(STROKE_LINE);
            renderConnectingLines(g2d, root);

            // Node Boxes
            renderNodeBoxes(g2d, root, iconImages);

        } finally {
            g2d.dispose();
        }

        return image;
    }

    private static void renderConnectingLines(final Graphics2D g2d, final NodeLayout node) {
        if (!node.children.isEmpty()) {
            double px = node.x + (node.boxW / 2.0);
            double py = node.y + node.boxH;

            if (node.children.size() == 1) {
                NodeLayout child = node.children.get(0);
                double cx = child.x + (child.boxW / 2.0);
                double cy = child.y;

                if (Math.abs(px - cx) < 1.0) {
                    g2d.draw(new Line2D.Double(px, py, px, cy));
                } else {
                    double my = py + ((cy - py) / 2.0);
                    double r = Math.min(6.0, Math.min(Math.abs(cx - px) / 2.0, (cy - py) / 4.0));
                    double dir = cx > px ? 1.0 : -1.0;

                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(px, py);
                    path.lineTo(px, my - r);
                    path.quadTo(px, my, px + dir * r, my);
                    path.lineTo(cx - dir * r, my);
                    path.quadTo(cx, my, cx, my + r);
                    path.lineTo(cx, cy);
                    g2d.draw(path);
                }
            } else {
                double firstCy = node.children.get(0).y;
                double my = py + ((firstCy - py) / 2.0);
                int numChildren = node.children.size();
                NodeLayout firstChild = node.children.get(0);
                NodeLayout lastChild = node.children.get(numChildren - 1);

                double firstCx = firstChild.x + (firstChild.boxW / 2.0);
                double lastCx = lastChild.x + (lastChild.boxW / 2.0);

                // Vertical drop from parent to midpoint Y
                g2d.draw(new Line2D.Double(px, py, px, my));

                // Leftmost child
                if (Math.abs(firstCx - px) < 1.0) {
                    g2d.draw(new Line2D.Double(firstCx, my, firstCx, firstCy));
                } else {
                    double r = Math.min(6.0, Math.min((px - firstCx) / 2.0, (firstCy - my) / 2.0));
                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(px, my);
                    path.lineTo(firstCx + r, my);
                    path.quadTo(firstCx, my, firstCx, my + r);
                    path.lineTo(firstCx, firstCy);
                    g2d.draw(path);
                }

                // Rightmost child
                double lastCy = lastChild.y;
                if (Math.abs(lastCx - px) < 1.0) {
                    g2d.draw(new Line2D.Double(lastCx, my, lastCx, lastCy));
                } else {
                    double r = Math.min(6.0, Math.min((lastCx - px) / 2.0, (lastCy - my) / 2.0));
                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(px, my);
                    path.lineTo(lastCx - r, my);
                    path.quadTo(lastCx, my, lastCx, my + r);
                    path.lineTo(lastCx, lastCy);
                    g2d.draw(path);
                }

                // Intermediate children
                for (int i = 1; i < numChildren - 1; i++) {
                    NodeLayout midChild = node.children.get(i);
                    double midCx = midChild.x + (midChild.boxW / 2.0);
                    double midCy = midChild.y;
                    g2d.draw(new Line2D.Double(midCx, my, midCx, midCy));
                }
            }

            for (NodeLayout child : node.children) {
                renderConnectingLines(g2d, child);
            }
        }
    }

    private static void renderNodeBoxes(final Graphics2D g2d, final NodeLayout node, final Map<Integer, BufferedImage> iconImages) {
        double bx = node.x;
        double by = node.y;
        double bw = node.boxW;
        double bh = node.boxH;

        RoundRectangle2D.Double box = new RoundRectangle2D.Double(bx, by, bw, bh, 8.0, 8.0);

        if (node.isRoot) {
            // Root Node: Solid green background with white text
            g2d.setColor(COLOR_ROOT_BG);
            g2d.fill(box);
            g2d.setColor(COLOR_ROOT_BORDER);
            g2d.setStroke(STROKE_ROOT_BOX);
            g2d.draw(box);

            double curX = bx + 12.0;
            if (node.itemIndex >= 0) {
                BufferedImage icon = iconImages.get(node.itemIndex);
                if (icon != null) {
                    g2d.drawImage(icon, (int) Math.round(curX), (int) Math.round(by + 7.0), 24, 24, null);
                }
                curX += 24.0 + 8.0;
            }

            g2d.setFont(FONT_ROOT_NAME);
            g2d.setColor(Color.WHITE);
            g2d.drawString(node.name, (float) curX, (float) (by + 24.0));

            if (!node.amountText.isEmpty()) {
                double textWidth = node.nameWidth + 8.0;
                g2d.setFont(FONT_ROOT_COUNT);
                g2d.setColor(COLOR_ROOT_COUNT);
                g2d.drawString(node.amountText, (float) (curX + textWidth), (float) (by + 24.0));
            }
        } else {
            // Non-root nodes: Clean bordered white box with dark text
            g2d.setColor(Color.WHITE);
            g2d.fill(box);
            g2d.setColor(COLOR_NODE_BORDER);
            g2d.setStroke(STROKE_NODE_BOX);
            g2d.draw(box);

            double curX = bx + 10.0;
            if (node.itemIndex >= 0) {
                BufferedImage icon = iconImages.get(node.itemIndex);
                if (icon != null) {
                    g2d.drawImage(icon, (int) Math.round(curX), (int) Math.round(by + 7.0), 24, 24, null);
                }
                curX += 24.0 + 8.0;
            }

            g2d.setFont(FONT_NODE_NAME);
            g2d.setColor(COLOR_NODE_NAME);
            g2d.drawString(node.name, (float) curX, (float) (by + 24.0));

            if (!node.amountText.isEmpty()) {
                double textWidth = node.nameWidth + 8.0;
                g2d.setFont(FONT_NODE_COUNT);
                g2d.setColor(COLOR_NODE_COUNT);
                g2d.drawString(node.amountText, (float) (curX + textWidth), (float) (by + 24.0));
            }
        }

        for (NodeLayout child : node.children) {
            renderNodeBoxes(g2d, child, iconImages);
        }
    }

    private static void appendLines(final StringBuilder sb, final NodeLayout node) {
        if (!node.children.isEmpty()) {
            double px = node.x + (node.boxW / 2.0);
            double py = node.y + node.boxH;

            if (node.children.size() == 1) {
                NodeLayout child = node.children.get(0);
                double cx = child.x + (child.boxW / 2.0);
                double cy = child.y;

                if (Math.abs(px - cx) < 1.0) {
                    sb.append("    <line x1=\"").append(formatDouble(px)).append("\" y1=\"").append(formatDouble(py))
                            .append("\" x2=\"").append(formatDouble(px)).append("\" y2=\"").append(formatDouble(cy))
                            .append("\" stroke=\"#555555\" stroke-width=\"2\"/>\n");
                } else {
                    double my = py + ((cy - py) / 2.0);
                    double r = Math.min(6.0, Math.min(Math.abs(cx - px) / 2.0, (cy - py) / 4.0));
                    double dir = cx > px ? 1.0 : -1.0;

                    sb.append("    <path d=\"M ").append(formatDouble(px)).append(",").append(formatDouble(py))
                            .append(" L ").append(formatDouble(px)).append(",").append(formatDouble(my - r))
                            .append(" Q ").append(formatDouble(px)).append(",").append(formatDouble(my))
                            .append(" ").append(formatDouble(px + dir * r)).append(",").append(formatDouble(my))
                            .append(" L ").append(formatDouble(cx - dir * r)).append(",").append(formatDouble(my))
                            .append(" Q ").append(formatDouble(cx)).append(",").append(formatDouble(my))
                            .append(" ").append(formatDouble(cx)).append(",").append(formatDouble(my + r))
                            .append(" L ").append(formatDouble(cx)).append(",").append(formatDouble(cy))
                            .append("\" stroke=\"#555555\" stroke-width=\"2\" fill=\"none\"/>\n");
                }
            } else {
                double firstCy = node.children.get(0).y;
                double my = py + ((firstCy - py) / 2.0);
                int numChildren = node.children.size();
                NodeLayout firstChild = node.children.get(0);
                NodeLayout lastChild = node.children.get(numChildren - 1);

                double firstCx = firstChild.x + (firstChild.boxW / 2.0);
                double lastCx = lastChild.x + (lastChild.boxW / 2.0);

                // Vertical drop from parent to midpoint Y
                sb.append("    <line x1=\"").append(formatDouble(px)).append("\" y1=\"").append(formatDouble(py))
                        .append("\" x2=\"").append(formatDouble(px)).append("\" y2=\"").append(formatDouble(my))
                        .append("\" stroke=\"#555555\" stroke-width=\"2\"/>\n");

                // Leftmost child (curves down at firstCx without any overshoot)
                if (Math.abs(firstCx - px) < 1.0) {
                    sb.append("    <line x1=\"").append(formatDouble(firstCx)).append("\" y1=\"").append(formatDouble(my))
                            .append("\" x2=\"").append(formatDouble(firstCx)).append("\" y2=\"").append(formatDouble(firstCy))
                            .append("\" stroke=\"#555555\" stroke-width=\"2\"/>\n");
                } else {
                    double r = Math.min(6.0, Math.min((px - firstCx) / 2.0, (firstCy - my) / 2.0));
                    sb.append("    <path d=\"M ").append(formatDouble(px)).append(",").append(formatDouble(my))
                            .append(" L ").append(formatDouble(firstCx + r)).append(",").append(formatDouble(my))
                            .append(" Q ").append(formatDouble(firstCx)).append(",").append(formatDouble(my))
                            .append(" ").append(formatDouble(firstCx)).append(",").append(formatDouble(my + r))
                            .append(" L ").append(formatDouble(firstCx)).append(",").append(formatDouble(firstCy))
                            .append("\" stroke=\"#555555\" stroke-width=\"2\" fill=\"none\"/>\n");
                }

                // Rightmost child (curves down at lastCx without any overshoot)
                double lastCy = lastChild.y;
                if (Math.abs(lastCx - px) < 1.0) {
                    sb.append("    <line x1=\"").append(formatDouble(lastCx)).append("\" y1=\"").append(formatDouble(my))
                            .append("\" x2=\"").append(formatDouble(lastCx)).append("\" y2=\"").append(formatDouble(lastCy))
                            .append("\" stroke=\"#555555\" stroke-width=\"2\"/>\n");
                } else {
                    double r = Math.min(6.0, Math.min((lastCx - px) / 2.0, (lastCy - my) / 2.0));
                    sb.append("    <path d=\"M ").append(formatDouble(px)).append(",").append(formatDouble(my))
                            .append(" L ").append(formatDouble(lastCx - r)).append(",").append(formatDouble(my))
                            .append(" Q ").append(formatDouble(lastCx)).append(",").append(formatDouble(my))
                            .append(" ").append(formatDouble(lastCx)).append(",").append(formatDouble(my + r))
                            .append(" L ").append(formatDouble(lastCx)).append(",").append(formatDouble(lastCy))
                            .append("\" stroke=\"#555555\" stroke-width=\"2\" fill=\"none\"/>\n");
                }

                // Intermediate children (index 1 to numChildren - 2)
                for (int i = 1; i < numChildren - 1; i++) {
                    NodeLayout midChild = node.children.get(i);
                    double midCx = midChild.x + (midChild.boxW / 2.0);
                    double midCy = midChild.y;
                    sb.append("    <line x1=\"").append(formatDouble(midCx)).append("\" y1=\"").append(formatDouble(my))
                            .append("\" x2=\"").append(formatDouble(midCx)).append("\" y2=\"").append(formatDouble(midCy))
                            .append("\" stroke=\"#555555\" stroke-width=\"2\"/>\n");
                }
            }

            for (NodeLayout child : node.children) {
                appendLines(sb, child);
            }
        }
    }

    private static void appendNodes(final StringBuilder sb, final NodeLayout node) {
        double bx = node.x;
        double by = node.y;
        double bw = node.boxW;
        double bh = node.boxH;

        if (node.isRoot) {
            // Root Node: Solid green background with white text matching reference image
            sb.append("    <g id=\"node-root\">\n");
            sb.append("      <rect x=\"").append(formatDouble(bx)).append("\" y=\"").append(formatDouble(by))
                    .append("\" width=\"").append(formatDouble(bw)).append("\" height=\"").append(formatDouble(bh))
                    .append("\" rx=\"4\" ry=\"4\" fill=\"#00965e\" stroke=\"#007a4d\" stroke-width=\"1.5\"/>\n");

            double curX = bx + 12.0;
            if (node.itemIndex >= 0) {
                sb.append("      <use href=\"#icon-").append(node.itemIndex).append("\" x=\"").append(formatDouble(curX))
                        .append("\" y=\"").append(formatDouble(by + 7.0)).append("\"/>\n");
                curX += 24.0 + 8.0;
            }

            sb.append("      <text x=\"").append(formatDouble(curX)).append("\" y=\"").append(formatDouble(by + 24.0))
                    .append("\" font-size=\"14\" font-weight=\"bold\" fill=\"#ffffff\">")
                    .append(escapeXml(node.name)).append("</text>\n");

            if (!node.amountText.isEmpty()) {
                double textWidth = node.nameWidth + 8.0;
                sb.append("      <text x=\"").append(formatDouble(curX + textWidth)).append("\" y=\"").append(formatDouble(by + 24.0))
                        .append("\" font-size=\"12\" fill=\"#d1fae5\">")
                        .append(escapeXml(node.amountText)).append("</text>\n");
            }
            sb.append("    </g>\n");
        } else {
            // Non-root nodes: Clean bordered white box with dark text matching reference image
            sb.append("    <g>\n");
            sb.append("      <rect x=\"").append(formatDouble(bx)).append("\" y=\"").append(formatDouble(by))
                    .append("\" width=\"").append(formatDouble(bw)).append("\" height=\"").append(formatDouble(bh))
                    .append("\" rx=\"4\" ry=\"4\" fill=\"#ffffff\" stroke=\"#333333\" stroke-width=\"1.2\"/>\n");

            double curX = bx + 10.0;
            if (node.itemIndex >= 0) {
                sb.append("      <use href=\"#icon-").append(node.itemIndex).append("\" x=\"").append(formatDouble(curX))
                        .append("\" y=\"").append(formatDouble(by + 7.0)).append("\"/>\n");
                curX += 24.0 + 8.0;
            }

            sb.append("      <text x=\"").append(formatDouble(curX)).append("\" y=\"").append(formatDouble(by + 24.0))
                    .append("\" font-size=\"13\" font-weight=\"600\" fill=\"#222222\">")
                    .append(escapeXml(node.name)).append("</text>\n");

            if (!node.amountText.isEmpty()) {
                double textWidth = node.nameWidth + 8.0;
                sb.append("      <text x=\"").append(formatDouble(curX + textWidth)).append("\" y=\"").append(formatDouble(by + 24.0))
                        .append("\" font-size=\"12\" fill=\"#666666\">")
                        .append(escapeXml(node.amountText)).append("</text>\n");
            }

            sb.append("    </g>\n");
        }

        for (NodeLayout child : node.children) {
            appendNodes(sb, child);
        }
    }

    private static int countNodes(final NodeLayout node) {
        int count = 1;
        for (NodeLayout child : node.children) {
            count += countNodes(child);
        }
        return count;
    }

    private static String formatDouble(double val) {
        return String.format(Locale.US, "%.1f", val);
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static File getUniqueFile(final File dir, final String baseName, final String ext) {
        File file = new File(dir, baseName + ext);
        int i = 1;
        while (file.exists()) {
            file = new File(dir, baseName + "_" + i + ext);
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
