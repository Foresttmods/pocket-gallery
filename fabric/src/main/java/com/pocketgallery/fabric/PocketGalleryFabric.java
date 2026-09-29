package com.pocketgallery.fabric;

import com.pocketgallery.ClientHooks;
import com.pocketgallery.Platform;
import com.pocketgallery.PocketGallery;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

import java.nio.file.Path;

/**
 * Fabric entry point: forwards Fabric API events to the shared {@link ClientHooks}. The per-frame hook for GIF
 * animations comes from {@code GameRendererMixin} (Fabric API has no event right before a frame is rendered).
 */
public final class PocketGalleryFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PocketGallery.init(new FabricPlatform());

        KeyMapping.Category category = KeyMapping.Category.register(ClientHooks.KEY_CATEGORY_ID);
        KeyMappingHelper.registerKeyMapping(ClientHooks.createOpenKey(category));

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> PocketGallery.clientSetup());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> ClientHooks.onClientTick());
        ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> ClientHooks.onLogout());

        // Screen-specific events are reset whenever a screen is (re)initialized, so they are registered again here.
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            ClientHooks.onScreenInit(screen);
            if (ClientHooks.isHooked(screen)) {
                ScreenEvents.afterExtract(screen).register(ClientHooks::onScreenRender);
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !ClientHooks.onScreenClick(s, event, false));
            }
        });
    }

    private static final class FabricPlatform implements Platform {
        @Override
        public String loaderName() {
            return "Fabric";
        }

        @Override
        public Path configDir() {
            return FabricLoader.getInstance().getConfigDir();
        }

        @Override
        public boolean isModLoaded(String modId) {
            return FabricLoader.getInstance().isModLoaded(modId);
        }
    }
}
