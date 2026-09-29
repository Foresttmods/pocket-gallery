package com.pocketgallery.gui;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.Workers;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.gallery.GalleryEntry;
import com.pocketgallery.gallery.GalleryState;
import com.pocketgallery.gallery.GalleryTab;
import com.pocketgallery.gallery.GalleryTabs;
import com.pocketgallery.gallery.TabHost;
import com.pocketgallery.image.GifDecoder;
import com.pocketgallery.image.ImageLoader;
import com.pocketgallery.image.PaintingPipeline;
import com.pocketgallery.image.PixelLook;
import com.pocketgallery.ip.IpBridge;
import com.pocketgallery.ip.IpCompat;
import com.pocketgallery.upload.UploadQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gallery: one tab per source (Folder, Museums, Photos, ...), a paged thumbnail grid with multi-select, a preview
 * panel with the auto-computed painting size and the per-image pixel look, and the import controls.
 */
public final class GalleryScreen extends Screen implements TabHost {
    private static final int THUMB_PX = 160;
    private static final ThumbnailCache THUMBS = new ThumbnailCache("thumb", THUMB_PX, 400,
            PocketGallery.thumbnailCacheDir(), Workers.THUMBNAILS);
    private static final ThumbnailCache PREVIEWS = new ThumbnailCache("preview", 512, 6, null, Workers.IO);
    /** Pixel-look previews: the actual upload image, drawn enlarged with nearest filtering. */
    private static final ThumbnailCache PIXEL_PREVIEWS = new ThumbnailCache("pixel", 512, 6, null, Workers.IO, true);
    /** Only preview the pixel look when the result is small enough for pixels to be visible in the panel. */
    private static final int PIXEL_PREVIEW_MAX = 512;
    /** Pixel sizes read from file headers (key -> {w, h}); {0,0} while unknown/failed. */
    private static final Map<String, int[]> SIZES = new ConcurrentHashMap<>();

    private static final int TOP = 54;
    private static final int GAP = 4;
    private static final int LOOK_ROWS_H = 46;

    private final @Nullable Screen parent;
    private final GalleryState state = GalleryState.get();

    private EditBox searchBox;
    private Button importButton;
    private Button cancelButton;
    private Button prevButton;
    private Button nextButton;
    private Button sizeLabel;
    private Button cropButton;
    private Button densityButton;
    private Button colorsButton;
    private Button applyLookButton;

    private int page;
    private int anchorIndex = -1;
    private @Nullable GalleryEntry focused;
    /** Sizing settings incl. IP's limits; refreshed every tick because reading IP's config is reflective. */
    private PaintingPipeline.Settings sizing;
    private boolean shownOnce;

    // layout (computed in init)
    private int gridX, gridY, gridW, gridH, cell, cols, rows, panelX, panelW, statusX;

    public GalleryScreen(@Nullable Screen parent) {
        super(Component.translatable("pocket_gallery.gui.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    // ================================================================= layout & widgets

    @Override
    protected void init() {
        GalleryTab tab = state.tab();

        panelW = Math.max(120, Math.min(200, width / 3));
        panelX = width - panelW - 8;
        gridX = 8;
        gridY = TOP;
        gridW = panelX - 8 - gridX;
        gridH = height - 54 - TOP;
        cell = gridH >= 3 * 68 && gridW >= 4 * 68 ? 64 : 52;
        cols = Math.max(1, (gridW + GAP) / (cell + GAP));
        rows = Math.max(1, (gridH + GAP) / (cell + GAP));

        // --- top bar: tabs, search, settings
        int tx = 8;
        for (GalleryTab t : GalleryTabs.all()) {
            Component label = t.label();
            int w = font.width(label) + 12;
            Button b = addRenderableWidget(Button.builder(label, btn -> switchTab(t)).bounds(tx, 6, w, 20).build());
            b.active = tab != t;
            tx += w + 2;
        }

        int settingsW = 54;
        boolean remote = tab.isRemote();
        int searchBtnW = remote ? 50 : 0;
        int searchX = tx + 4;
        int searchW = width - 8 - settingsW - 4 - (searchBtnW > 0 ? searchBtnW + 4 : 0) - searchX;
        searchBox = new EditBox(font, searchX, 7, Math.max(40, searchW), 18, null, Component.translatable("pocket_gallery.gui.search"));
        searchBox.setMaxLength(512);
        searchBox.setValue(tab.search());
        searchBox.setResponder(s -> tab.setSearch(s, this));
        searchBox.setHint(tab.searchHint().copy().withStyle(EditBox.SEARCH_HINT_STYLE));
        addRenderableWidget(searchBox);
        if (searchBtnW > 0) {
            addRenderableWidget(Button.builder(Component.translatable("pocket_gallery.gui.search.button"), b -> runSearch())
                    .bounds(searchX + searchBox.getWidth() + 4, 6, searchBtnW, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("pocket_gallery.gui.settings"),
                b -> minecraft.gui.setScreen(new SettingsScreen(this))).bounds(width - 8 - settingsW, 6, settingsW, 20).build());

        // --- second row: tab specific
        statusX = tab.addControls(this, 8, 30) + 6;

        // --- pixel look for the focused image (top of the preview panel)
        int lx = panelX + 6;
        int lw = panelW - 12;
        int half = (lw - 2) / 2;
        densityButton = addRenderableWidget(Button.builder(Component.empty(), b -> changeLook(PixelLook::nextDensity))
                .bounds(lx, gridY + 14, half, 20)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.pixels.tooltip"))).build());
        colorsButton = addRenderableWidget(Button.builder(Component.empty(), b -> changeLook(PixelLook::nextColors))
                .bounds(lx + half + 2, gridY + 14, lw - half - 2, 20)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.colors.tooltip"))).build());
        applyLookButton = addRenderableWidget(Button.builder(Component.empty(), b -> applyLookToSelection())
                .bounds(lx, gridY + 36, lw, 20)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.apply_look.tooltip"))).build());

        // --- pager
        prevButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> setPage(page - 1))
                .bounds(gridX, height - 50, 20, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> setPage(page + 1))
                .bounds(gridX + gridW - 20, height - 50, 20, 20).build());

        // --- bottom row: selection, size, crop, import
        int x = 8;
        int y = height - 26;
        addRenderableWidget(Button.builder(Component.translatable("pocket_gallery.gui.select_page"), b -> selectPage())
                .bounds(x, y, 66, 20).build());
        x += 68;
        addRenderableWidget(Button.builder(Component.translatable("pocket_gallery.gui.select_none"), b -> {
            state.selected.clear();
            anchorIndex = -1;
        }).bounds(x, y, 34, 20).build());
        x += 40;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> changeLongSide(-1)).bounds(x, y, 14, 20).build());
        x += 14;
        sizeLabel = addRenderableWidget(Button.builder(Component.empty(), b -> {
        }).bounds(x, y, 44, 20).tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.long_side.tooltip"))).build());
        x += 44;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> changeLongSide(1)).bounds(x, y, 14, 20).build());
        x += 18;
        cropButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleCrop())
                .bounds(x, y, 62, 20).tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.crop.tooltip"))).build());
        x += 66;
        int importW = Math.max(70, Math.min(110, width - 8 - x - 50));
        importButton = addRenderableWidget(Button.builder(Component.empty(), b -> startImport()).bounds(x, y, importW, 20).build());
        x += importW + 2;
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> UploadQueue.get().cancel())
                .bounds(x, y, Math.max(40, Math.min(60, width - 8 - x)), 20).build());

        updateLabels();
        updatePager();

        if (!shownOnce) {
            shownOnce = true;
            tab.onShown(this);
        }
    }

    private void switchTab(GalleryTab tab) {
        if (state.tab() == tab) return;
        state.setTab(tab);
        page = 0;
        anchorIndex = -1;
        rebuildWidgets();
        tab.onShown(this);
    }

    private void updateLabels() {
        GalleryConfig c = PocketGallery.config();
        sizing = pipelineSettings(c);
        sizeLabel.setMessage(Component.translatable("pocket_gallery.gui.long_side", c.longSideBlocks));
        cropButton.setMessage(Component.translatable(c.cropToFit ? "pocket_gallery.gui.crop.on" : "pocket_gallery.gui.crop.off"));
        int n = state.selected.size();
        importButton.setMessage(Component.translatable("pocket_gallery.gui.import", n));
        boolean ipOk = IpCompat.isAvailable();
        importButton.active = n > 0 && ipOk && minecraft.player != null;
        importButton.setTooltip(ipOk ? null : Tooltip.create(Component.literal(IpCompat.problem())));
        cancelButton.active = UploadQueue.get().isRunning() && !UploadQueue.get().isCancelled();

        boolean hasFocus = focused != null;
        densityButton.visible = colorsButton.visible = applyLookButton.visible = hasFocus;
        if (hasFocus) {
            PixelLook look = state.lookOf(focused);
            densityButton.setMessage(Component.translatable("pocket_gallery.gui.pixels", look.densityLabel()));
            colorsButton.setMessage(Component.translatable("pocket_gallery.gui.colors", look.colorsLabel()));
            int others = 0;
            for (GalleryEntry e : state.selected.values()) if (!e.key().equals(focused.key())) others++;
            applyLookButton.setMessage(Component.translatable("pocket_gallery.gui.apply_look", n));
            applyLookButton.active = others > 0;
        }
    }

    // ================================================================= TabHost

    @Override
    public <T extends AbstractWidget> T addTabWidget(T widget) {
        return addRenderableWidget(widget);
    }

    @Override
    public Font font() {
        return font;
    }

    @Override
    public Screen screen() {
        return this;
    }

    @Override
    public void open(Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    @Override
    public void runSearch() {
        page = 0;
        anchorIndex = -1;
        PREVIEWS.clearFailures();
        THUMBS.clearFailures();
        state.tab().newSearch();
    }

    @Override
    public void resetPage() {
        page = 0;
        anchorIndex = -1;
        updatePager();
    }

    @Override
    public void rebuild() {
        rebuildWidgets();
    }

    @Override
    public void clearImageCaches() {
        THUMBS.clearFailures();
        SIZES.clear();
    }

    @Override
    public void focusSearch() {
        if (searchBox != null) setFocused(searchBox);
    }

    // ================================================================= actions

    private void changeLongSide(int delta) {
        GalleryConfig c = PocketGallery.config().copy();
        c.longSideBlocks = Math.max(1, Math.min(16, c.longSideBlocks + delta));
        PocketGallery.setConfig(c);
        updateLabels();
    }

    private void toggleCrop() {
        GalleryConfig c = PocketGallery.config().copy();
        c.cropToFit = !c.cropToFit;
        PocketGallery.setConfig(c);
        updateLabels();
    }

    private void changeLook(java.util.function.UnaryOperator<PixelLook> change) {
        if (focused == null) return;
        state.setLook(focused, change.apply(state.lookOf(focused)));
        updateLabels();
    }

    private void applyLookToSelection() {
        if (focused == null) return;
        PixelLook look = state.lookOf(focused);
        for (GalleryEntry e : state.selected.values()) state.setLook(e, look);
        updateLabels();
    }

    private void selectPage() {
        List<GalleryEntry> entries = state.entries();
        int start = page * perPage();
        state.selectAll(entries.subList(Math.min(start, entries.size()), Math.min(entries.size(), start + perPage())));
    }

    private void startImport() {
        if (!IpCompat.isAvailable() || state.selected.isEmpty()) return;
        GalleryConfig c = PocketGallery.config();
        PaintingPipeline.Settings base = pipelineSettings(c);
        List<UploadQueue.Request> requests = new ArrayList<>();
        for (GalleryEntry e : state.selectedList()) {
            requests.add(new UploadQueue.Request(e, new UploadQueue.Settings(base.withLook(state.lookOf(e)), c.sourcePrefix)));
        }
        UploadQueue.get().enqueue(requests);
        state.selected.clear();
        anchorIndex = -1;
    }

    /** Current sizing settings combined with Immersive Paintings' (possibly server-synced) limits. */
    static PaintingPipeline.Settings pipelineSettings(GalleryConfig c) {
        int maxW = IpCompat.isAvailable() ? IpBridge.commonConfigInt("maxUserImageWidth", 4096) : 4096;
        int maxH = IpCompat.isAvailable() ? IpBridge.commonConfigInt("maxUserImageHeight", 4096) : 4096;
        int minRes = IpCompat.isAvailable() ? IpBridge.commonConfigInt("minPaintingResolution", 8) : 8;
        int maxRes = IpCompat.isAvailable() ? IpBridge.commonConfigInt("maxPaintingResolution", 256) : 256;
        return new PaintingPipeline.Settings(c.longSideBlocks, c.cropToFit, Math.max(16, Math.min(maxW, maxH)), minRes, Math.max(minRes, maxRes));
    }

    // ================================================================= paging

    private int perPage() {
        return cols * rows;
    }

    private int pageCount() {
        int n = state.entries().size();
        int pages = Math.max(1, (n + perPage() - 1) / perPage());
        if (state.tab().isRemote() && state.tab().more() && n % perPage() == 0 && n > 0) pages++;
        return pages;
    }

    private void setPage(int p) {
        page = Math.max(0, Math.min(pageCount() - 1, p));
        updatePager();
    }

    private void updatePager() {
        if (prevButton == null) return;
        prevButton.active = page > 0;
        nextButton.active = page < pageCount() - 1 || (state.tab().isRemote() && state.tab().more());
    }

    @Override
    public void tick() {
        super.tick();
        // infinite scrolling for web searches: make sure the current page (and the next) are loaded
        GalleryTab tab = state.tab();
        if (tab.isRemote() && tab.searched() && tab.error() == null) {
            int needed = (page + 2) * perPage();
            if (tab.entries().size() < needed && tab.more() && !tab.loading()) tab.fetchNextPage();
        }
        if (page > 0 && page >= pageCount()) page = pageCount() - 1;
        updatePager();
        updateLabels();
    }

    // ================================================================= input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int index = cellAt(event.x(), event.y());
        if (index < 0) return false;
        List<GalleryEntry> entries = state.entries();
        GalleryEntry entry = entries.get(index);
        setFocused(null);
        focused = entry;
        if (event.button() == 0) {
            if (event.hasShiftDown() && anchorIndex >= 0 && anchorIndex < entries.size()) {
                int a = Math.min(anchorIndex, index);
                int b = Math.max(anchorIndex, index);
                state.selectAll(entries.subList(a, b + 1));
            } else {
                state.toggle(entry);
                anchorIndex = index;
            }
        }
        updateLabels();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x < gridX + gridW && y >= gridY && y < gridY + gridH) {
            if (scrollY < 0) setPage(page + 1);
            else if (scrollY > 0) setPage(page - 1);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchBox != null && searchBox.isFocused() && event.isConfirmation()) {
            if (state.tab().isRemote()) runSearch();
            return true;
        }
        if (getFocused() != searchBox) {
            if (event.isLeft()) {
                setPage(page - 1);
                return true;
            }
            if (event.isRight()) {
                setPage(page + 1);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Index into the current entry list of the cell under the mouse, or -1. */
    private int cellAt(double mx, double my) {
        if (mx < gridX || my < gridY) return -1;
        int c = (int) ((mx - gridX) / (cell + GAP));
        int r = (int) ((my - gridY) / (cell + GAP));
        if (c >= cols || r >= rows) return -1;
        if (mx - gridX - c * (cell + GAP) >= cell || my - gridY - r * (cell + GAP) >= cell) return -1;
        int index = page * perPage() + r * cols + c;
        return index < state.entries().size() ? index : -1;
    }

    // ================================================================= rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(gridX - 2, gridY - 2, gridX + gridW + 2, gridY + gridH + 2, 0x60000000);
        g.fill(panelX, gridY - 2, panelX + panelW, height - 30, 0x60000000);

        extractStatusLine(g);
        extractGrid(g, mouseX, mouseY);
        extractPanel(g);

        String pageText = (page + 1) + " / " + pageCount() + (state.tab().isRemote() && state.tab().more() ? "+" : "");
        g.centeredText(font, pageText, gridX + gridW / 2, height - 44, 0xFFFFFFFF);

        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void extractStatusLine(GuiGraphicsExtractor g) {
        GalleryTab.Status status = state.tab().status();
        g.text(font, font.plainSubstrByWidth(status.text(), width - statusX - 8), statusX, 36, status.color());
    }

    private void extractGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<GalleryEntry> entries = state.entries();
        int start = page * perPage();
        int hovered = cellAt(mouseX, mouseY);
        if (entries.isEmpty()) {
            int ty = gridY + gridH / 2 - 10;
            for (FormattedCharSequence line : font.split(Component.literal(state.tab().emptyMessage()), gridW - 16)) {
                g.centeredText(font, line, gridX + gridW / 2, ty, 0xFFAAAAAA);
                ty += 10;
            }
            return;
        }
        for (int i = 0; i < perPage() && start + i < entries.size(); i++) {
            GalleryEntry entry = entries.get(start + i);
            int cx = gridX + (i % cols) * (cell + GAP);
            int cy = gridY + (i / cols) * (cell + GAP);
            g.fill(cx, cy, cx + cell, cy + cell, 0xFF202020);

            ThumbnailCache.Thumb thumb = THUMBS.get(entry.thumbKey(), thumbLoader(entry));
            if (thumb != null) {
                float s = Math.min((float) (cell - 2) / thumb.width(), (float) (cell - 2) / thumb.height());
                int dw = Math.max(1, Math.round(thumb.width() * s));
                int dh = Math.max(1, Math.round(thumb.height() * s));
                g.blit(RenderPipelines.GUI_TEXTURED, thumb.texture(), cx + (cell - dw) / 2, cy + (cell - dh) / 2, 0, 0,
                        dw, dh, thumb.width(), thumb.height(), thumb.width(), thumb.height());
            } else if (THUMBS.failed(entry.thumbKey())) {
                g.centeredText(font, "?", cx + cell / 2, cy + cell / 2 - 4, 0xFFFF6060);
            } else {
                g.centeredText(font, "...", cx + cell / 2, cy + cell / 2 - 4, 0xFF808080);
            }

            if (entry.isGif()) {
                g.fill(cx + 1, cy + 1, cx + 21, cy + 10, 0xC0000000);
                g.text(font, "GIF", cx + 3, cy + 2, 0xFF55FFFF, false);
            }
            String badge = state.lookOf(entry).badge();
            if (!badge.isEmpty()) {
                int bw = font.width(badge) + 3;
                g.fill(cx + 1, cy + cell - 10, cx + 1 + bw, cy + cell - 1, 0xC0000000);
                g.text(font, badge, cx + 3, cy + cell - 9, 0xFF80FF80, false);
            }
            if (state.isSelected(entry)) {
                g.outline(cx - 1, cy - 1, cell + 2, cell + 2, 0xFFFFD700);
                g.outline(cx, cy, cell, cell, 0xFFFFD700);
                g.fill(cx + cell - 11, cy + 1, cx + cell - 1, cy + 11, 0xFFFFD700);
                g.text(font, "✔", cx + cell - 10, cy + 2, 0xFF000000, false);
            } else if (start + i == hovered) {
                g.outline(cx, cy, cell, cell, 0xFFFFFFFF);
            }
            if (focused != null && focused.key().equals(entry.key())) {
                g.outline(cx + 1, cy + 1, cell - 2, cell - 2, 0x8055FFFF);
            }
        }
        if (hovered >= 0) {
            GalleryEntry e = entries.get(hovered);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(e.name()));
            for (String line : e.tooltipLines()) tip.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
            PixelLook look = state.lookOf(e);
            if (!look.isOff()) {
                tip.add(Component.translatable("pocket_gallery.gui.look_tip", look.densityLabel(), look.colorsLabel())
                        .withStyle(ChatFormatting.GREEN));
            }
            tip.add(Component.translatable("pocket_gallery.gui.click_hint").withStyle(ChatFormatting.DARK_GRAY));
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    private void extractPanel(GuiGraphicsExtractor g) {
        int x = panelX + 6;
        int w = panelW - 12;
        int y = gridY + 2;

        g.text(font, Component.translatable("pocket_gallery.gui.selected", state.selected.size()), x, y, 0xFFFFD700);
        y += 12;

        GalleryEntry e = focused;
        if (e == null) {
            for (FormattedCharSequence line : font.split(Component.translatable("pocket_gallery.gui.preview_hint"), w)) {
                g.text(font, line, x, y, 0xFF999999);
                y += 10;
            }
        } else {
            y += LOOK_ROWS_H;
            PixelLook look = state.lookOf(e);
            int[] size = pixelSize(e);
            int maxH = Math.max(40, (height - 30 - y) / 2 - 10);

            ThumbnailCache.Thumb normal = PREVIEWS.get(e.key(), previewLoader(e));
            // size for the plan: the real pixel size, or the preview's shape while that is unknown
            int[] planSize = size != null && size[0] > 0 ? size
                    : normal != null ? new int[]{normal.width(), normal.height()} : null;
            boolean approximate = planSize != null && (size == null || size[0] <= 0);
            PaintingPipeline.Settings lookSizing = sizing.withLook(look);
            PaintingPipeline.Plan plan = planSize == null ? null : PaintingPipeline.plan(planSize[0], planSize[1], lookSizing);

            ThumbnailCache.Thumb preview = null;
            if (plan != null && !look.isOff() && Math.max(plan.outWidth(), plan.outHeight()) <= PIXEL_PREVIEW_MAX) {
                preview = PIXEL_PREVIEWS.get(pixelKey(e, lookSizing), pixelLoader(e, lookSizing));
            }
            if (preview == null) preview = normal;
            if (preview == null) preview = THUMBS.get(e.thumbKey(), thumbLoader(e));
            if (preview != null) {
                float s = Math.min((float) w / preview.width(), (float) maxH / preview.height());
                int dw = Math.max(1, Math.round(preview.width() * s));
                int dh = Math.max(1, Math.round(preview.height() * s));
                g.blit(RenderPipelines.GUI_TEXTURED, preview.texture(), x + (w - dw) / 2, y, 0, 0,
                        dw, dh, preview.width(), preview.height(), preview.width(), preview.height());
                y += dh + 4;
            } else {
                g.centeredText(font, "...", x + w / 2, y + 10, 0xFF808080);
                y += 24;
            }
            for (FormattedCharSequence line : font.split(Component.literal(e.name()), w)) {
                g.text(font, line, x, y, 0xFFFFFFFF);
                y += 10;
            }
            if (size != null && size[0] > 0) {
                g.text(font, size[0] + " x " + size[1] + " px" + (e.isGif() ? " (GIF)" : ""), x, y, 0xFFCCCCCC);
                y += 10;
            } else if (size != null && !approximate) {
                g.text(font, "size unknown", x, y, 0xFF999999);
                y += 10;
            }
            if (plan != null) {
                g.text(font, font.plainSubstrByWidth("Painting: " + plan.blocks().width() + " x " + plan.blocks().height()
                        + " blocks", w), x, y, 0xFF80FF80);
                y += 10;
                if (!approximate || look.isPixelated()) {
                    g.text(font, font.plainSubstrByWidth(plan.outWidth() + " x " + plan.outHeight() + " px, "
                            + plan.resolution() + " px/block" + (plan.colors() > 0 ? ", " + plan.colors() + " colors" : ""), w), x, y, 0xFFAAAAAA);
                    y += 10;
                } else {
                    g.text(font, font.plainSubstrByWidth("exact size after download", w), x, y, 0xFF999999);
                    y += 10;
                }
                if (!approximate && (plan.crop().width() != planSize[0] || plan.crop().height() != planSize[1])) {
                    g.text(font, font.plainSubstrByWidth("cropped to " + plan.crop().width() + " x " + plan.crop().height(), w), x, y, 0xFFAAAAAA);
                    y += 10;
                }
            }
            for (GalleryEntry.InfoLine line : e.previewLines()) {
                g.text(font, font.plainSubstrByWidth(line.text(), w), x, y, line.color());
                y += 10;
            }
        }

        // upload progress / problems at the panel bottom
        int by = height - 34;
        UploadQueue q = UploadQueue.get();
        if (!IpCompat.isAvailable()) {
            List<FormattedCharSequence> lines = font.split(Component.literal(IpCompat.problem()), w);
            by -= lines.size() * 10;
            for (FormattedCharSequence line : lines) {
                g.text(font, line, x, by, 0xFFFF6060);
                by += 10;
            }
        } else if (q.total() > 0) {
            List<FormattedCharSequence> lines = font.split(Component.literal(q.status()), w);
            int top = by - 6 - lines.size() * 10 - (q.failed() > 0 ? 10 : 0);
            int ly = top;
            for (FormattedCharSequence line : lines) {
                g.text(font, line, x, ly, 0xFFFFFFFF);
                ly += 10;
            }
            if (q.failed() > 0) {
                g.text(font, q.failed() + " failed (hover)", x, ly, 0xFFFF8080);
                if (isMouseOverText(x, ly, w)) {
                    List<Component> errs = new ArrayList<>();
                    List<String> all = q.errors();
                    for (int i = Math.max(0, all.size() - 12); i < all.size(); i++) errs.add(Component.literal(all.get(i)));
                    g.setComponentTooltipForNextFrame(font, errs, lastMouseX, lastMouseY);
                }
            }
            g.fill(x, by, x + w, by + 3, 0xFF404040);
            g.fill(x, by, x + Math.round(w * q.progress()), by + 3, q.failed() > 0 ? 0xFFE0A030 : 0xFF40C040);
        }
    }

    private int lastMouseX, lastMouseY;

    @Override
    public void mouseMoved(double x, double y) {
        lastMouseX = (int) x;
        lastMouseY = (int) y;
        super.mouseMoved(x, y);
    }

    private boolean isMouseOverText(int x, int y, int w) {
        return lastMouseX >= x && lastMouseX < x + w && lastMouseY >= y && lastMouseY < y + 10;
    }

    // ================================================================= loaders

    private static String pixelKey(GalleryEntry e, PaintingPipeline.Settings s) {
        return e.thumbKey() + "|" + s.longSideBlocks() + "|" + s.cropToFit() + "|" + s.look().pixelsPerBlock() + "|" + s.look().colors();
    }

    private static ThumbnailCache.Loader thumbLoader(GalleryEntry e) {
        return () -> ImageLoader.thumbnail(e.thumbnailBytes(), THUMB_PX);
    }

    private static ThumbnailCache.Loader previewLoader(GalleryEntry e) {
        return () -> ImageLoader.thumbnail(e.previewBytes(), 512);
    }

    /** The upload image for the pixel look (computed from the preview-size source; same crop and density). */
    private static ThumbnailCache.Loader pixelLoader(GalleryEntry e, PaintingPipeline.Settings s) {
        return () -> {
            BufferedImage src = ImageLoader.decode(e.previewBytes(), 1024);
            return PaintingPipeline.process(src, src.getWidth(), src.getHeight(), s).image();
        };
    }

    /** Known pixel size, or null while it's being read. {0,0} = unknown. */
    private static int[] pixelSize(GalleryEntry e) {
        if (e.knownWidth() > 0) return new int[]{e.knownWidth(), e.knownHeight()};
        Path file = e.localFile();
        if (file == null) return new int[]{0, 0};
        int[] cached = SIZES.get(e.key());
        if (cached != null) return cached[0] < 0 ? null : cached;
        SIZES.put(e.key(), new int[]{-1, -1});
        Workers.IO.execute(() -> {
            int[] result = {0, 0};
            try {
                if (ImageLoader.isGif(file)) {
                    GifDecoder.Info info = GifDecoder.readInfo(new ByteArrayInputStream(Files.readAllBytes(file)));
                    result = new int[]{info.width(), info.height()};
                } else {
                    ImageLoader.Size s = ImageLoader.readSize(file);
                    result = new int[]{s.width(), s.height()};
                }
            } catch (Exception ignored) {
                // stays unknown
            }
            SIZES.put(e.key(), result);
        });
        return null;
    }
}
