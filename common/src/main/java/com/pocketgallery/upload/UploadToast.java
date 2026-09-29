package com.pocketgallery.upload;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Progress toast for the import queue: "Uploading 3/12", time left and a progress bar. */
public final class UploadToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/system");
    private static final int WIDTH = 200;
    private static final long LINGER_MS = 5000;

    private final UploadQueue queue;
    private long finishedAt = -1;
    private Visibility visibility = Visibility.SHOW;

    UploadToast(UploadQueue queue) {
        this.queue = queue;
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        if (queue.isRunning()) {
            finishedAt = -1;
            visibility = Visibility.SHOW;
        } else {
            if (finishedAt < 0) finishedAt = fullyVisibleForMs;
            visibility = fullyVisibleForMs - finishedAt > LINGER_MS ? Visibility.HIDE : Visibility.SHOW;
        }
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height());
        String title = queue.isRunning()
                ? "Pocket Gallery " + Math.min(queue.total(), queue.finished() + 1) + "/" + queue.total()
                : "Pocket Gallery";
        graphics.text(font, title, 10, 5, 0xFFFFFF00, false);

        String line = queue.status();
        long eta = queue.etaMs();
        if (queue.isRunning() && eta > 0) line = formatEta(eta) + " left";
        graphics.text(font, font.plainSubstrByWidth(line, WIDTH - 20), 10, 15, 0xFFFFFFFF, false);

        // progress bar
        int x0 = 10, x1 = WIDTH - 10, y = 26;
        graphics.fill(x0, y, x1, y + 2, 0xFF404040);
        int filled = x0 + Math.round((x1 - x0) * queue.progress());
        graphics.fill(x0, y, filled, y + 2, queue.failed() > 0 ? 0xFFE0A030 : 0xFF40C040);
    }

    static String formatEta(long ms) {
        long s = Math.max(1, ms / 1000);
        if (s < 60) return "~" + s + "s";
        long m = s / 60;
        if (m < 60) return "~" + m + "m " + (s % 60) + "s";
        return "~" + (m / 60) + "h " + (m % 60) + "m";
    }
}
