package com.pocketgallery;

import com.pocketgallery.anim.AnimationManager;
import com.pocketgallery.gui.GalleryScreen;
import com.pocketgallery.ip.IpCompat;
import com.pocketgallery.ip.YoursScreenAccess;
import com.pocketgallery.ip.YoursSourceFilter;
import com.pocketgallery.upload.UploadQueue;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.ref.WeakReference;

/**
 * What the loader events do, shared by NeoForge and Fabric: the key binding, ticking the upload queue and
 * animations, and the "Gallery..." / "Source: ..." buttons on Immersive Paintings' screen. The loader modules only
 * forward their events to these methods.
 */
public final class ClientHooks {
    public static final Identifier KEY_CATEGORY_ID = Identifier.fromNamespaceAndPath(PocketGallery.MOD_ID, "main");
    public static final String OPEN_KEY_NAME = "key.pocket_gallery.open";

    private static KeyMapping openGallery;

    /**
     * The button on IP's screen is drawn and clicked through screen events instead of being added as a widget,
     * because IP's screen clears all widgets whenever it switches pages.
     */
    private static WeakReference<Screen> hookedScreen = new WeakReference<>(null);
    private static Button galleryButton;
    /** "Source: ..." filter on IP's "Yours" page (only when the screen mixin applied). */
    private static Button sourceButton;

    private ClientHooks() {
    }

    /** Creates the "open gallery" key (K) in the given category; the loader registers both. */
    public static KeyMapping createOpenKey(KeyMapping.Category category) {
        openGallery = new KeyMapping(OPEN_KEY_NAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category);
        return openGallery;
    }

    /** End of every client tick. */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (openGallery != null) {
            while (openGallery.consumeClick()) {
                if (mc.gui.screen() == null && mc.player != null) {
                    mc.gui.setScreen(new GalleryScreen(null));
                }
            }
        }
        try {
            UploadQueue.get().tick();
        } catch (RuntimeException e) {
            PocketGallery.LOG.error("Upload queue tick failed", e);
        }
        try {
            AnimationManager.get().tick();
        } catch (RuntimeException e) {
            PocketGallery.LOG.error("Animation tick failed", e);
        }
    }

    /** Right before the game renders a frame. */
    public static void onRenderFrame() {
        try {
            AnimationManager.get().onRenderFrame();
        } catch (RuntimeException e) {
            PocketGallery.LOG.error("Animation frame failed", e);
        }
    }

    /** The player leaves a world or server. */
    public static void onLogout() {
        AnimationManager.get().stopAll(false);
    }

    // ------------------------------------------------------------------ IP screen buttons

    /** After a screen's init; hooks Immersive Paintings' painting screen. */
    public static void onScreenInit(Screen screen) {
        if (!IpCompat.isPaintingScreen(screen)) return;
        int w = 72;
        // top right corner if it's clear of IP's 400px wide centered layout, otherwise bottom right
        int x = screen.width - w - 4;
        int y = screen.width / 2 + 200 < x - 2 ? 4 : screen.height - 24;
        galleryButton = Button.builder(Component.translatable("pocket_gallery.gui.open_button"),
                        b -> Minecraft.getInstance().gui.setScreen(new GalleryScreen(screen)))
                .bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.open_button.tooltip")))
                .build();
        sourceButton = null;
        if (screen instanceof YoursScreenAccess access) {
            // bottom left, level with IP's page buttons (which sit centered at height/2 + 80)
            sourceButton = Button.builder(sourceLabel(), b -> {
                        YoursSourceFilter.cycle(false);
                        b.setMessage(sourceLabel());
                        access.paintingGallery$refresh();
                    })
                    .bounds(screen.width / 2 - 196, screen.height / 2 + 80, 112, 20)
                    .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.source_filter.tooltip")))
                    .build();
        }
        hookedScreen = new WeakReference<>(screen);
    }

    /** True when the screen is the hooked IP screen (the loaders skip the other hooks otherwise). */
    public static boolean isHooked(Screen screen) {
        return screen != null && hookedScreen.get() == screen;
    }

    private static Component sourceLabel() {
        return Component.translatable("pocket_gallery.gui.source_filter", YoursSourceFilter.label());
    }

    private static boolean sourceButtonVisible(Screen screen) {
        return sourceButton != null && screen instanceof YoursScreenAccess access && access.paintingGallery$isYoursPage();
    }

    /** After the hooked screen rendered: draws our buttons on top. */
    public static void onScreenRender(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!isHooked(screen)) return;
        if (galleryButton != null) galleryButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (sourceButtonVisible(screen)) sourceButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Before the hooked screen handles a click. Returns true when one of our buttons took it. */
    public static boolean onScreenClick(Screen screen, MouseButtonEvent event, boolean doubleClick) {
        if (!isHooked(screen)) return false;
        if (galleryButton != null && galleryButton.mouseClicked(event, doubleClick)) return true;
        return sourceButtonVisible(screen) && sourceButton.mouseClicked(event, doubleClick);
    }
}
