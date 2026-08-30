package io.github.tritium_launcher.tritiumcompanion.client.icons;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import io.github.tritium_launcher.tritiumcompanion.TCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.client.Minecraft.ON_OSX;

/**
 * Renders item/block icons into typed, per-namespace atlas pages.
 *
 * <p>Layout (per-namespace-per-type, subdir per family):</p>
 * <pre>
 *   icons/{type}/{namespace}/{family}/atlas_{N}.png
 *   type   ∈ { item, block, &lt;customTypeId&gt; }        (item/block are built-in types)
 *   family ∈ { gl, blit }                              (gl = 256px GL-rendered, blit = 16px texture-blit)
 * </pre>
 * A cell may hold ALL raw animation frames as a vertical spritesheet
 * ({@code frameCount} × {@code frameSizeY}); the launcher plays them via its
 * existing {@code AnimatedItemMngr}/{@code parseAnimationMeta} path.
 */
public class RegistryIconRenderer {
    /**
     * An icon's location within a typed atlas.
     *
     * @param itemId            namespaced id.
     * @param namespace         namespace that owns this icon.
     * @param type              atlas type: `item`, `block`, or a Mod-API custom type id.
     * @param family           `gl` (256px GL-rendered) or `blit` (16px texture-blit).
     * @param renderFingerprint hash over this entry's resolved render graph; unchanged ⇒ skip re-render.
     * @param frameSizeY        height of one animation frame.
     */
    public record AtlasEntry(
            String itemId,
            String namespace,
            String type,
            String family,
            String renderFingerprint,
            int x, int y, int size, int page, int frameCount, int frameSizeY
    ) {
        public AtlasEntry(String itemId, int x, int y, int size, int page, int frameCount, int frameSizeY) {
            this(itemId, namespaceOf(itemId), "item", "gl", "", x, y, size, page, frameCount, frameSizeY);
        }
    }

    /** Extracts the namespace from a namespaced id (`ns:path` → `ns`). */
    private static String namespaceOf(String itemId) {
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(0, colon) : itemId;
    }

    /** A GL-render task: the stack to render, its id, and its atlas type. */
    public record RenderTask(ItemStack stack, String id, String type) {}

    private static final float GUI_FAR_PLANE = 21000.0f;
    private static final int BATCH_SIZE = 64;
    private static final int ATLAS_SIZE = 4096;
    private static final int ICON_SIZE = 256;
    private static final int ITEM_CELL_SIZE = 16;

    /**
     * Computes the dynamic page edge for a family: the smallest power-of-2 that fits
     * {@code cellCount} {@code cellSize} cells, clamped into the family's
     * {@code [minAtlasSize, maxAtlasSize]}. Overflow past {@code maxAtlasSize} is left
     * to the packer to chunk into the next {@code atlas_N.png}.
     *
     * @param cellCount    number of cells the group is expected to hold
     * @param cellSize     edge of one square cell in pixels
     * @param minAtlasSize smallest power-of-2 page edge for the family
     * @param maxAtlasSize largest power-of-2 page edge before chunking
     */
    static int pageSizeFor(int cellCount, int cellSize, int minAtlasSize, int maxAtlasSize) {
        int rows = (int) Math.ceil(Math.sqrt(Math.max(1, cellCount)));
        int neededEdge = rows * cellSize;
        int edge = 1;
        while (edge < neededEdge) edge <<= 1;
        return Math.max(minAtlasSize, Math.min(edge, maxAtlasSize));
    }

    /** One typed, per-namespace, per-family page sequence. */
    private static final class AtlasGroup {
        final String type;
        final String namespace;
        final String family;
        final int cellSize;
        final int pageSize;

        NativeImage cur;
        int page = 0, x = 0, y = 0;
        final List<NativeImage> finished = new ArrayList<>();

        AtlasGroup(String type, String namespace, String family, int cellSize, int pageSize) {
            this.type = type;
            this.namespace = namespace;
            this.family = family;
            this.cellSize = cellSize;
            this.pageSize = pageSize;
        }

        NativeImage ensure() {
            if (cur == null) {
                cur = new NativeImage(NativeImage.Format.RGBA, pageSize, pageSize, true);
            }
            return cur;
        }

        int nextPage() {
            page++;
            x = 0;
            y = 0;
            return page - 1;
        }

        /** Returns the page file name for an emitted page. */
        String fileName(int pageIndex) {
            return "atlas_" + pageIndex + ".png";
        }
    }

    private static final Map<String, AtlasGroup> GROUPS = new LinkedHashMap<>();
    private static RenderTarget target;

    private static String groupKey(String type, String namespace, String family) {
        return type + "\u0000" + namespace + "\u0000" + family;
    }

    /** Gets or lazily creates the page group for (type, namespace, family). */
    private static AtlasGroup group(String type, String namespace, String family, int cellSize) {
        return GROUPS.computeIfAbsent(groupKey(type, namespace, family),
                k -> new AtlasGroup(type, namespace, family, cellSize, ATLAS_SIZE));
    }

    /**
     * Pre-sizes a (type, ns, family) group so pages are tightly packed to the cell
     * count instead of always 4096².
     */
    public static void seedGroup(String type, String namespace, String family, int cellSize,
                                 int minAtlasSize, int maxAtlasSize, int cellCount) {
        GROUPS.computeIfAbsent(groupKey(type, namespace, family),
                k -> new AtlasGroup(type, namespace, family, cellSize,
                        pageSizeFor(cellCount, cellSize, minAtlasSize, maxAtlasSize)));
    }

    /** Pages that were explicitly rendered/emitted this dump, as relative paths under the atlas root. */
    private static final List<String> EMITTED_PAGES = new ArrayList<>();

    private static void emitPage(AtlasGroup g) {
        g.finished.add(g.cur);
        g.cur = null;
    }

    private static void ensureTarget() {
        if (target == null) {
            target = new TextureTarget(ICON_SIZE, ICON_SIZE, true, ON_OSX);
        }
    }

    /** Blits a GL-rendered 256px image into its typed group; page numbering is per (type, ns, family). */
    private static AtlasEntry blitToAtlas(NativeImage icon, RenderTask task) {
        AtlasGroup g = group(task.type(), namespaceOf(task.id()), "gl", ICON_SIZE);
        g.ensure();
        icon.flipY();

        if (g.x + ICON_SIZE > g.pageSize) {
            g.x = 0;
            g.y += ICON_SIZE;
        }
        if (g.y + ICON_SIZE > g.pageSize) {
            emitPage(g);
            g.ensure();
            g.nextPage();
        }

        for (int py = 0; py < ICON_SIZE; py++) {
            for (int px = 0; px < ICON_SIZE; px++) {
                g.cur.setPixelRGBA(g.x + px, g.y + py, icon.getPixelRGBA(px, py));
            }
        }

        AtlasEntry entry = new AtlasEntry(task.id(), namespaceOf(task.id()), task.type(), "gl",
                "", g.x, g.y, ICON_SIZE, g.page, 1, ICON_SIZE);
        g.x += ICON_SIZE;
        return entry;
    }

    /**
     * Writes every page emitted since the last call into {@code outDir}.
     *
     * @param outDir the atlas root; pages go under
     *               {@code {type}/{namespace}/{family}/atlas_N.png}.
     * @return relative paths of the written page files.
     */
    public static List<String> finalizeAtlas(Path outDir) throws IOException {
        List<String> written = new ArrayList<>();
        for (AtlasGroup g : GROUPS.values()) {
            if (g.cur != null && (g.x > 0 || g.y > 0)) {
                g.finished.add(g.cur);
                g.cur = null;
            }
            if (g.finished.isEmpty()) continue;
            Path dir = outDir.resolve(g.type).resolve(g.namespace).resolve(g.family);
            Files.createDirectories(dir);
            for (int i = 0; i < g.finished.size(); i++) {
                try (NativeImage page = g.finished.get(i)) {
                    String name = g.fileName(i);
                    page.writeToFile(dir.resolve(name));
                    written.add(g.type + "/" + g.namespace + "/" + g.family + "/" + name);
                }
            }
        }
        GROUPS.clear();
        EMITTED_PAGES.clear();
        return written;
    }

    /** Blits a flat 16px sprite into its typed blit group. */
    public static AtlasEntry blitTextureToAtlas(String itemId, String type, NativeImage texture) {
        AtlasGroup g = group(type, namespaceOf(itemId), "blit", ITEM_CELL_SIZE);
        g.ensure();

        if (g.x + ITEM_CELL_SIZE > g.pageSize) {
            g.x = 0;
            g.y += ITEM_CELL_SIZE;
        }
        if (g.y + ITEM_CELL_SIZE > g.pageSize) {
            emitPage(g);
            g.ensure();
            g.nextPage();
        }

        int srcW = Math.min(texture.getWidth(), ITEM_CELL_SIZE);
        int srcH = Math.min(texture.getHeight(), ITEM_CELL_SIZE);

        for (int py = 0; py < srcH; py++) {
            for (int px = 0; px < srcW; px++) {
                g.cur.setPixelRGBA(
                        g.x + px,
                        g.y + py,
                        texture.getPixelRGBA(px, py)
                );
            }
        }

        AtlasEntry entry = new AtlasEntry(itemId, namespaceOf(itemId), type, "blit",
                "", g.x, g.y, ITEM_CELL_SIZE, g.page, 1, ITEM_CELL_SIZE);
        g.x += ITEM_CELL_SIZE;
        return entry;
    }

    /** Renders GL items via the render thread and blits each into its typed group. */
    public static List<AtlasEntry> renderIcons(List<RenderTask> tasks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return List.of();

        int total = tasks.size();
        List<AtlasEntry> results = new ArrayList<>(total);

        NativeImage[] prevBatch = null;
        int prevBatchOffset = 0;
        int prevBatchSize = 0;
        boolean first = true;

        for (int offset = 0; offset < total; offset += BATCH_SIZE) {
            int batchSize = Math.min(BATCH_SIZE, total - offset);
            NativeImage[] currentBatch = new NativeImage[batchSize];
            CompletableFuture<Void> future = new CompletableFuture<>();

            int finalOffset = offset;
            RenderSystem.recordRenderCall(() -> {
                try {
                    ensureTarget();
                    ItemRenderer itemRenderer = mc.getItemRenderer();
                    for (int i = 0; i < batchSize; i++) {
                        currentBatch[i] = renderIcon(mc, itemRenderer, tasks.get(finalOffset + i).stack());
                    }
                } finally {
                    future.complete(null);
                }
            });

            if (!first) {
                for (int i = 0; i < prevBatchSize; i++) {
                    AtlasEntry entry = blitToAtlas(prevBatch[i], tasks.get(prevBatchOffset + i));
                    results.add(entry);
                    prevBatch[i].close();
                }
            }

            future.join();
            prevBatch = currentBatch;
            prevBatchOffset = offset;
            prevBatchSize = batchSize;
            first = false;
        }

        if (prevBatch != null) {
            for (int i = 0; i < prevBatchSize; i++) {
                AtlasEntry entry = blitToAtlas(prevBatch[i], tasks.get(prevBatchOffset + i));
                results.add(entry);
                prevBatch[i].close();
            }
        }

        return results;
    }

    private static NativeImage renderIcon(Minecraft mc, ItemRenderer itemRenderer, ItemStack stack) {
        target.bindWrite(true);
        target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        target.clear(ON_OSX);
        target.bindWrite(true);

        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0f, ICON_SIZE, ICON_SIZE, 0.0f, 1000.0f, GUI_FAR_PLANE),
                VertexSorting.ORTHOGRAPHIC_Z
        );

        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().translation(0.0f, 0.0f, 10000.0f - GUI_FAR_PLANE);
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        Lighting.setupFor3DItems();

        PoseStack poseStack = new PoseStack();
        poseStack.translate(ICON_SIZE / 2.0f, ICON_SIZE / 2.0f, 150.0f);
        poseStack.scale(ICON_SIZE, -ICON_SIZE, ICON_SIZE);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BakedModel model = itemRenderer.getModel(stack, mc.level, mc.player, 0);

        itemRenderer.render(
                stack,
                ItemDisplayContext.GUI,
                false,
                poseStack,
                bufferSource,
                15728880,
                OverlayTexture.NO_OVERLAY,
                model
        );

        bufferSource.endBatch();

        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        target.unbindWrite();

        return takeScreenshot(target);
    }

    private static NativeImage takeScreenshot(RenderTarget target) {
        NativeImage nativeImage = new NativeImage(target.width, target.height, false);
        RenderSystem.bindTexture(target.getColorTextureId());
        nativeImage.downloadTexture(0, false);
        return nativeImage;
    }

    /** Result of a scoped atlas build: the index entries plus the exact page file names produced. */
    public record ScopedAtlasResult(List<AtlasEntry> entries, Set<String> pageFiles) {}

    private static Path scopedPriorDir;
    private static Map<String, AtlasEntry> scopedPriorIndex = Map.of();

    /**
     * Gives the renderer the previous dump's atlas for resume.
     */
    public static void beginScopedResume(Path priorAtlasDir, Map<String, AtlasEntry> priorIndex) {
        scopedPriorDir = priorAtlasDir;
        scopedPriorIndex = priorIndex != null ? new HashMap<>(priorIndex) : Map.of();
        TCompanion.LOGGER.info("[AtlasScoped] begin resume: priorDir={}, priorCells={}",
                priorAtlasDir == null ? "none" : priorAtlasDir, scopedPriorIndex.size());
    }

    /** Prior page path for a (type, ns, family) group + index, or null if absent. */
    private static Path scopedPriorPagePath(String type, String namespace, String family, int index) {
        if (scopedPriorDir == null) return null;
        return scopedPriorDir.resolve(type).resolve(namespace).resolve(family).resolve("atlas_" + index + ".png");
    }

    /** Highest prior page index for the given (type, ns, family), or -1 if none. */
    private static int maxPriorPageIndex(String type, String namespace, String family) {
        int max = -1;
        for (AtlasEntry e : scopedPriorIndex.values()) {
            if (!type.equals(e.type()) || !namespace.equals(e.namespace()) || !family.equals(e.family())) continue;
            if (e.page() > max) max = e.page();
        }
        return max;
    }

    private static AtlasEntry scopedEntry(String itemId, String type, String family, int x, int y, int size, int page,
                                          int frameCount, int frameSizeY, Map<String, String> fingerprints) {
        String fp = fingerprints.getOrDefault(itemId, "");
        int colon = itemId.indexOf(':');
        String ns = colon >= 0 ? itemId.substring(0, colon) : itemId;
        return new AtlasEntry(itemId, ns, type, family, fp, x, y, size, page, frameCount, frameSizeY);
    }

    /** Renders a list of item stacks via the GL render thread, mirroring renderIcons batching. */
    private static List<NativeImage> renderGlStacks(List<ItemStack> stacks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return List.of();
        int total = stacks.size();
        List<NativeImage> out = new ArrayList<>(total);
        for (int offset = 0; offset < total; offset += BATCH_SIZE) {
            int size = Math.min(BATCH_SIZE, total - offset);
            NativeImage[] batch = new NativeImage[size];
            CompletableFuture<Void> future = new CompletableFuture<>();
            int finalOffset = offset;
            RenderSystem.recordRenderCall(() -> {
                try {
                    ensureTarget();
                    ItemRenderer itemRenderer = mc.getItemRenderer();
                    for (int i = 0; i < size; i++) {
                        batch[i] = renderIcon(mc, itemRenderer, stacks.get(finalOffset + i));
                    }
                } finally {
                    future.complete(null);
                }
            });
            future.join();
            out.addAll(Arrays.asList(batch));
        }
        return out;
    }

    /** Copies src pixels into dst at (x,y), truncating at dst bounds. */
    private static void blitNativeInto(NativeImage dst, NativeImage src, int x, int y) {
        int w = Math.min(src.getWidth(), dst.getWidth() - x);
        int h = Math.min(src.getHeight(), dst.getHeight() - y);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                dst.setPixelRGBA(x + px, y + py, src.getPixelRGBA(px, py));
            }
        }
    }

    /** One typed (type, namespace, family) page sequence for the scoped path. */
    private static final class ScopedPanel {
        final String type;
        final String namespace;
        final String family;
        final int cellSize;
        final Map<Integer, NativeImage> buffers = new HashMap<>();
        final Set<Integer> carriedPages = new TreeSet<>();
        final Set<String> pageFiles = new TreeSet<>();
        final List<AtlasEntry> entries = new ArrayList<>();

        ScopedPanel(String type, String namespace, String family, int cellSize) {
            this.type = type;
            this.namespace = namespace;
            this.family = family;
            this.cellSize = cellSize;
        }

        String fileName(int index) {
            return "atlas_" + index + ".png";
        }

        /** Returns the composed buffer for a page index, seeding from the prior page if present. */
        NativeImage bufferFor(int index) {
            NativeImage b = buffers.get(index);
            if (b != null) return b;
            Path prior = index < 0 ? null : scopedPriorPagePath(type, namespace, family, index);
            if (prior != null && Files.exists(prior)) {
                try {
                    b = NativeImage.read(Files.newInputStream(prior));
                } catch (IOException e) {
                    b = null;
                }
            }
            if (b == null) {
                b = new NativeImage(NativeImage.Format.RGBA, ATLAS_SIZE, ATLAS_SIZE, true);
            }
            buffers.put(index, b);
            return b;
        }
    }

    private static final class ScopedPlan {
        ItemStack stack;
        String itemId;
        String type;
        String family;
        int cellSize;
        AtlasEntry reuse;
        boolean fresh;
    }

    /**
     * Composes the scoped atlas for the current in-scope item set.
     *
     * @param glTasks      in-scope GL-rendered tasks: stack -> RenderTask.
     * @param texTasks     in-scope flat-sprite items: id -> encoded PNG bytes.
     * @param texTypes     id -> atlas type for each texture task.
     * @param fingerprints id -> render fingerprint for every item in the two maps.
     * @param outDir       directory to write page PNGs into snapshot icons/ dir.
     */
    public static ScopedAtlasResult renderScopedAtlas(
            List<RenderTask> glTasks,
            Map<String, byte[]> texTasks,
            Map<String, String> texTypes,
            Map<String, String> fingerprints,
            Path outDir
    ) throws IOException {
        Files.createDirectories(outDir);
        TCompanion.LOGGER.info("[AtlasScoped] render: glTasks={}, texTasks={}, priorCells={}",
                glTasks.size(), texTasks.size(), scopedPriorIndex.size());

        List<ScopedPlan> glPlans = new ArrayList<>();
        List<ItemStack> glRenderStacks = new ArrayList<>();
        Map<String, ScopedPanel> glPanels = new HashMap<>();
        for (RenderTask t : glTasks) {
            String id = t.id();
            AtlasEntry prior = scopedPriorIndex.get(id);
            String fp = fingerprints.get(id);
            boolean priorUsable = prior != null
                    && t.type().equals(prior.type())
                    && "gl".equals(prior.family());
            boolean unchanged = priorUsable && fp != null && fp.equals(prior.renderFingerprint());
            ScopedPanel panel = glPanels.computeIfAbsent(groupKey(t.type(), namespaceOf(id), "gl"),
                    k -> new ScopedPanel(t.type(), namespaceOf(id), "gl", ICON_SIZE));
            if (unchanged) {
                panel.carriedPages.add(prior.page());
                panel.entries.add(scopedEntry(id, t.type(), "gl", prior.x(), prior.y(), prior.size(), prior.page(),
                        prior.frameCount(), prior.frameSizeY(), fingerprints));
                continue;
            }
            ScopedPlan p = new ScopedPlan();
            p.stack = t.stack();
            p.itemId = id;
            p.type = t.type();
            p.family = "gl";
            p.cellSize = ICON_SIZE;
            if (priorUsable) {
                p.reuse = scopedEntry(id, t.type(), "gl", prior.x(), prior.y(), prior.size(), prior.page(),
                        prior.frameCount(), prior.frameSizeY(), fingerprints);
            } else {
                p.fresh = true;
            }
            glPlans.add(p);
            glRenderStacks.add(p.stack);
        }
        int carriedCount = 0;
        for (ScopedPanel panel : glPanels.values()) {
            carriedCount += panel.entries.size();
        }
        TCompanion.LOGGER.info("[AtlasScoped] GL: carried={}, changed/fresh={}",
                carriedCount - glPlans.size(), glPlans.size());

        List<NativeImage> rendered = renderGlStacks(glRenderStacks);
        if (rendered.size() != glPlans.size()) {
            TCompanion.LOGGER.error("[AtlasScoped] render produced {} images for {} plans; aborting resume",
                    rendered.size(), glPlans.size());
            for (NativeImage img : rendered) if (img != null) img.close();
            return new ScopedAtlasResult(List.of(), Set.of());
        }

        int ri = 0;
        Map<String, int[]> freshCursor = new HashMap<>();
        for (ScopedPlan p : glPlans) {
            NativeImage img = rendered.get(ri++);
            if (img == null) continue;
            img.flipY();
            String key = groupKey(p.type, namespaceOf(p.itemId), "gl");
            ScopedPanel panel = glPanels.get(key);
            if (p.fresh) {
                int[] cur = freshCursor.computeIfAbsent(key,
                        k -> new int[]{maxPriorPageIndex(p.type, namespaceOf(p.itemId), "gl") + 1, 0, 0});
                if (cur[1] + ICON_SIZE > ATLAS_SIZE) { cur[1] = 0; cur[2] += ICON_SIZE; }
                if (cur[2] + ICON_SIZE > ATLAS_SIZE) { cur[0]++; cur[1] = 0; cur[2] = 0; }
                NativeImage pageBuf = panel.bufferFor(cur[0]);
                blitNativeInto(pageBuf, img, cur[1], cur[2]);
                panel.carriedPages.remove(cur[0]);
                panel.entries.add(scopedEntry(p.itemId, p.type, "gl", cur[1], cur[2], ICON_SIZE, cur[0],
                        1, ICON_SIZE, fingerprints));
                cur[1] += ICON_SIZE;
            } else {
                AtlasEntry reuse = p.reuse;
                NativeImage pageBuf = panel.bufferFor(reuse.page());
                blitNativeInto(pageBuf, img, reuse.x(), reuse.y());
                panel.carriedPages.remove(reuse.page());
                panel.entries.add(scopedEntry(p.itemId, p.type, "gl", reuse.x(), reuse.y(), reuse.size(), reuse.page(),
                        reuse.frameCount(), reuse.frameSizeY(), fingerprints));
            }
            img.close();
        }

        Map<String, ScopedPanel> texPanels = new HashMap<>();
        Map<String, int[]> texFresh = new HashMap<>();
        for (Map.Entry<String, byte[]> e : texTasks.entrySet()) {
            String id = e.getKey();
            String ns = namespaceOf(id);
            String type = texTypes.getOrDefault(id, "item");
            ScopedPanel panel = texPanels.computeIfAbsent(groupKey(type, ns, "blit"),
                    k -> new ScopedPanel(type, ns, "blit", ITEM_CELL_SIZE));
            AtlasEntry prior = scopedPriorIndex.get(id);
            String fp = fingerprints.get(id);
            boolean priorUsable = prior != null
                    && type.equals(prior.type())
                    && "blit".equals(prior.family());
            boolean unchanged = priorUsable && fp != null && fp.equals(prior.renderFingerprint());
            if (unchanged) {
                panel.carriedPages.add(prior.page());
                panel.entries.add(scopedEntry(id, type, "blit", prior.x(), prior.y(), prior.size(), prior.page(),
                        prior.frameCount(), prior.frameSizeY(), fingerprints));
                continue;
            }
            NativeImage img;
            try {
                img = NativeImage.read(new java.io.ByteArrayInputStream(e.getValue()));
            } catch (Exception ex) {
                TCompanion.LOGGER.warn("[AtlasScoped] failed to decode texture for {}: {}", id, ex.getMessage());
                continue;
            }
            NativeImage pageBuf;
            if (priorUsable) {
                pageBuf = panel.bufferFor(prior.page());
                blitNativeInto(pageBuf, img, prior.x(), prior.y());
                panel.carriedPages.remove(prior.page());
                panel.entries.add(scopedEntry(id, type, "blit", prior.x(), prior.y(), prior.size(), prior.page(),
                        prior.frameCount(), prior.frameSizeY(), fingerprints));
            } else {
                String key = groupKey(type, ns, "blit");
                int[] cur = texFresh.computeIfAbsent(key,
                        k -> new int[]{maxPriorPageIndex(type, ns, "blit") + 1, 0, 0});
                if (cur[1] + ITEM_CELL_SIZE > ATLAS_SIZE) { cur[1] = 0; cur[2] += ITEM_CELL_SIZE; }
                if (cur[2] + ITEM_CELL_SIZE > ATLAS_SIZE) { cur[0]++; cur[1] = 0; cur[2] = 0; }
                pageBuf = panel.bufferFor(cur[0]);
                blitNativeInto(pageBuf, img, cur[1], cur[2]);
                panel.carriedPages.remove(cur[0]);
                panel.entries.add(scopedEntry(id, type, "blit", cur[1], cur[2], ITEM_CELL_SIZE, cur[0],
                        1, ITEM_CELL_SIZE, fingerprints));
                cur[1] += ITEM_CELL_SIZE;
            }
            img.close();
        }

        for (ScopedPanel panel : glPanels.values()) {
            writePanel(panel, outDir);
        }
        for (ScopedPanel panel : texPanels.values()) {
            writePanel(panel, outDir);
        }

        List<AtlasEntry> allEntries = new ArrayList<>();
        Set<String> pageFiles = new LinkedHashSet<>();
        for (ScopedPanel panel : glPanels.values()) {
            allEntries.addAll(panel.entries);
            pageFiles.addAll(panel.pageFiles);
        }
        for (ScopedPanel panel : texPanels.values()) {
            allEntries.addAll(panel.entries);
            pageFiles.addAll(panel.pageFiles);
        }

        TCompanion.LOGGER.info("[AtlasScoped] done: entries={}, pages={}", allEntries.size(), pageFiles.size());
        return new ScopedAtlasResult(allEntries, pageFiles);
    }

    private static void writePanel(ScopedPanel panel, Path outDir) throws IOException {
        Path dir = outDir.resolve(panel.type).resolve(panel.namespace).resolve(panel.family);
        Files.createDirectories(dir);
        for (Map.Entry<Integer, NativeImage> entry : panel.buffers.entrySet()) {
            String name = panel.fileName(entry.getKey());
            try (NativeImage page = entry.getValue()) {
                page.writeToFile(dir.resolve(name));
            }
            panel.pageFiles.add(panel.type + "/" + panel.namespace + "/" + panel.family + "/" + name);
        }
        for (int index : panel.carriedPages) {
            String name = panel.fileName(index);
            Path prior = scopedPriorPagePath(panel.type, panel.namespace, panel.family, index);
            if (prior != null && Files.exists(prior)) {
                Files.copy(prior, dir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                try (NativeImage blank = new NativeImage(NativeImage.Format.RGBA, ATLAS_SIZE, ATLAS_SIZE, true)) {
                    blank.writeToFile(dir.resolve(name));
                }
            }
            panel.pageFiles.add(panel.type + "/" + panel.namespace + "/" + panel.family + "/" + name);
        }
    }
}
