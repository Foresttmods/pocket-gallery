package com.pocketgallery.neoforge;

import com.pocketgallery.ClientHooks;
import com.pocketgallery.Platform;
import com.pocketgallery.PocketGallery;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/** NeoForge entry point: forwards NeoForge's events to the shared {@link ClientHooks}. */
@Mod(value = PocketGallery.MOD_ID, dist = Dist.CLIENT)
public final class PocketGalleryNeoForge {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(ClientHooks.KEY_CATEGORY_ID);
    private static final KeyMapping OPEN_GALLERY = ClientHooks.createOpenKey(CATEGORY);

    public PocketGalleryNeoForge(IEventBus modBus) {
        PocketGallery.init(new NeoForgePlatform());
        modBus.addListener(PocketGalleryNeoForge::registerKeys);
        modBus.addListener(PocketGalleryNeoForge::clientSetup);
        NeoForge.EVENT_BUS.register(PocketGalleryNeoForge.class);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_GALLERY);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(PocketGallery::clientSetup);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientHooks.onClientTick();
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        ClientHooks.onRenderFrame();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientHooks.onLogout();
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        ClientHooks.onScreenInit(event.getScreen());
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        ClientHooks.onScreenRender(event.getScreen(), event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    @SubscribeEvent
    public static void onScreenClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (ClientHooks.onScreenClick(event.getScreen(), event.getMouseButtonEvent(), event.isDoubleClick())) {
            event.setCanceled(true);
        }
    }

    private static final class NeoForgePlatform implements Platform {
        @Override
        public String loaderName() {
            return "NeoForge";
        }

        @Override
        public Path configDir() {
            return FMLPaths.CONFIGDIR.get();
        }

        @Override
        public boolean isModLoaded(String modId) {
            return ModList.get().isLoaded(modId);
        }
    }
}
