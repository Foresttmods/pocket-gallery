package com.pocketgallery.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A simple chooser: titled sections of buttons laid out in columns, scrollable when they don't fit. Used for the
 * Museums tab's "Browse" (artists, movements, subjects) and the Photos tab's topics.
 */
public final class PickerScreen<T> extends Screen {
    public record Option<T>(String label, T value, boolean current) {
    }

    public record Section<T>(String title, List<Option<T>> options) {
    }

    private static final int BUTTON_H = 20;
    private static final int ROW = 22;
    private static final int TOP = 26;

    private final Screen parent;
    private final List<Section<T>> sections;
    private final Consumer<T> onPick;
    private final List<Label> labels = new ArrayList<>();
    private int scroll;
    private int contentHeight;

    private record Label(String text, int x, int y) {
    }

    public PickerScreen(Screen parent, Component title, List<Section<T>> sections, Consumer<T> onPick) {
        super(title);
        this.parent = parent;
        this.sections = sections;
        this.onPick = onPick;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        labels.clear();
        int buttonW = 112;
        int cols = Math.max(1, Math.min(6, (width - 20) / (buttonW + 4)));
        int left = (width - cols * (buttonW + 4) + 4) / 2;
        int bottom = height - 30;
        int y = TOP - scroll;
        for (Section<T> section : sections) {
            if (y >= TOP - 12 && y < bottom) labels.add(new Label(section.title(), left, y));
            y += 12;
            int i = 0;
            for (Option<T> option : section.options()) {
                int bx = left + (i % cols) * (buttonW + 4);
                int by = y + (i / cols) * ROW;
                if (by >= TOP && by + BUTTON_H <= bottom) {
                    Button b = addRenderableWidget(Button.builder(Component.literal(option.label()), btn -> pick(option.value()))
                            .bounds(bx, by, buttonW, BUTTON_H).build());
                    b.active = !option.current();
                }
                i++;
            }
            y += ((section.options().size() + cols - 1) / cols) * ROW + 6;
        }
        contentHeight = y + scroll - TOP;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 50, height - 24, 100, 20).build());
    }

    private void pick(T value) {
        onPick.accept(value);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int max = Math.max(0, contentHeight - (height - 30 - TOP));
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY) * ROW));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        for (Label l : labels) g.text(font, l.text(), l.x(), l.y(), 0xFFFFD700);
        if (contentHeight > height - 30 - TOP) {
            g.text(font, "Scroll for more", 8, height - 18, 0xFF808080);
        }
        super.extractRenderState(g, mouseX, mouseY, a);
    }
}
