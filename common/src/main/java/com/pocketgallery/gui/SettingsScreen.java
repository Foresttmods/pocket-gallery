package com.pocketgallery.gui;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.gallery.GalleryTab;
import com.pocketgallery.gallery.GalleryTabs;
import com.pocketgallery.image.PixelLook;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Settings panel for {@code config/pocket_gallery.json}. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final GalleryConfig draft;

    private EditBox folder, pixabayKey, gifFrames, maxAnimated, memory, latestYear;
    private final List<Label> labels = new ArrayList<>();

    private record Label(Component text, int x, int y) {
    }

    public SettingsScreen(Screen parent) {
        super(Component.translatable("pocket_gallery.gui.settings.title"));
        this.parent = parent;
        this.draft = PocketGallery.config().copy();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        labels.clear();
        int colW = Math.min(180, (width - 40) / 2);
        int left = width / 2 - colW - 5;
        int right = width / 2 + 5;
        int y = 30;
        int row = 30;

        folder = box("pocket_gallery.settings.folder", left, y, colW * 2 + 10, draft.folderPath, 1024);
        folder.setHint(Component.literal(PocketGallery.imageFolder().toString()).withStyle(EditBox.SEARCH_HINT_STYLE));
        y += row;
        pixabayKey = box("pocket_gallery.settings.pixabay_key", left, y, colW, draft.pixabayApiKey, 128);
        // show the key as dots; the real value stays in the box
        pixabayKey.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        pixabayKey.setTooltip(Tooltip.create(Component.translatable("pocket_gallery.settings.pixabay_key.tooltip")));
        latestYear = box("pocket_gallery.settings.latest_year", right, y, colW, String.valueOf(draft.artLatestYear), 4);
        latestYear.setTooltip(Tooltip.create(Component.translatable("pocket_gallery.settings.latest_year.tooltip")));
        y += row;
        gifFrames = box("pocket_gallery.settings.max_gif_frames", left, y, colW, String.valueOf(draft.maxGifFrames), 5);
        maxAnimated = box("pocket_gallery.settings.max_animated", right, y, colW, String.valueOf(draft.maxAnimatedPaintings), 4);
        y += row;
        memory = box("pocket_gallery.settings.memory", left, y, colW, String.valueOf(draft.maxAnimationMemoryMb), 5);
        addRenderableWidget(Checkbox.builder(Component.translatable("pocket_gallery.settings.animate"), font)
                .pos(right, y + 9).selected(draft.animateGifs).onValueChange((c, v) -> draft.animateGifs = v).build());
        y += row;
        addRenderableWidget(Checkbox.builder(Component.translatable("pocket_gallery.settings.source_prefix"), font)
                .pos(left, y + 9).selected(draft.sourcePrefix).onValueChange((c, v) -> draft.sourcePrefix = v)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.settings.source_prefix.tooltip"))).build());
        labels.add(new Label(Component.translatable("pocket_gallery.settings.default_look"), right, y));
        int half = (colW - 2) / 2;
        addRenderableWidget(Button.builder(densityLabel(), b -> {
                    PixelLook l = draft.defaultLook().nextDensity();
                    draft.defaultPixelsPerBlock = l.pixelsPerBlock();
                    b.setMessage(densityLabel());
                }).bounds(right, y + 9, half, 18)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.settings.default_look.tooltip"))).build());
        addRenderableWidget(Button.builder(colorsLabel(), b -> {
                    PixelLook l = draft.defaultLook().nextColors();
                    draft.defaultColors = l.colors();
                    b.setMessage(colorsLabel());
                }).bounds(right + half + 2, y + 9, colW - half - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.settings.default_look.tooltip"))).build());

        int by = Math.max(y + 34, height - 26);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> save()).bounds(width / 2 - 104, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 4, by, 100, 20).build());
    }

    private Component densityLabel() {
        return Component.translatable("pocket_gallery.gui.pixels", draft.defaultLook().densityLabel());
    }

    private Component colorsLabel() {
        return Component.translatable("pocket_gallery.gui.colors", draft.defaultLook().colorsLabel());
    }

    private EditBox box(String labelKey, int x, int y, int w, String value, int maxLength) {
        labels.add(new Label(Component.translatable(labelKey), x, y));
        EditBox box = new EditBox(font, x, y + 10, w, 16, Component.translatable(labelKey));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        return addRenderableWidget(box);
    }

    private void save() {
        GalleryConfig before = PocketGallery.config().copy();
        draft.folderPath = folder.getValue().trim();
        draft.pixabayApiKey = pixabayKey.getValue().trim();
        draft.maxGifFrames = parse(gifFrames, draft.maxGifFrames);
        draft.maxAnimatedPaintings = parse(maxAnimated, draft.maxAnimatedPaintings);
        draft.maxAnimationMemoryMb = parse(memory, draft.maxAnimationMemoryMb);
        draft.artLatestYear = parse(latestYear, draft.artLatestYear);
        // settings edited in the gallery itself may have changed meanwhile
        draft.longSideBlocks = before.longSideBlocks;
        draft.cropToFit = before.cropToFit;
        draft.museumFilter = before.museumFilter;
        draft.photoTopic = before.photoTopic;
        PocketGallery.setConfig(draft);
        GalleryConfig after = PocketGallery.config();
        for (GalleryTab tab : GalleryTabs.all()) tab.onConfigChanged(before, after);
        onClose();
    }

    private static int parse(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        for (Label l : labels) g.text(font, l.text(), l.x(), l.y(), 0xFFA0A0A0);
        super.extractRenderState(g, mouseX, mouseY, a);
    }
}
