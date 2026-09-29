package com.pocketgallery;

import com.pocketgallery.anim.AnimationManager;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.gallery.GalleryTabs;
import com.pocketgallery.gallery.SourceIndex;
import com.pocketgallery.web.WebClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loader-independent core of the mod. The NeoForge and Fabric entry points call {@link #init} once while the game
 * starts and {@link #clientSetup} once it is ready; everything else lives in the shared code.
 */
public final class PocketGallery {
    public static final String MOD_ID = "pocket_gallery";
    public static final Logger LOG = LoggerFactory.getLogger("PocketGallery");

    private static Platform platform;
    private static volatile GalleryConfig config = new GalleryConfig();
    private static final WebClient WEB = new WebClient(() -> cacheDir().resolve("api"));
    private static SourceIndex sources = new SourceIndex(null);

    private PocketGallery() {
    }

    public static void init(Platform loader) {
        platform = loader;
        migrateLegacyConfig();
        config = GalleryConfig.load(configFile());
        AnimationManager.init(configDir().resolve(MOD_ID + "_animated.json"));
        sources = new SourceIndex(configDir().resolve(MOD_ID + "_sources.json"));
        sources.load();
        GalleryTabs.registerBuiltIn();
        LOG.info("Pocket Gallery {} ({} edition) loaded on {}", BuildInfo.VERSION, BuildInfo.EDITION, loader.loaderName());
    }

    /** Runs on the client thread once the game is set up. */
    public static void clientSetup() {
        // Let ImageIO pick up readers from mod jars (e.g. the webp reader bundled with Immersive Paintings).
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(PocketGallery.class.getClassLoader());
            ImageIO.scanForPlugins();
        } catch (Throwable t) {
            LOG.warn("ImageIO plugin scan failed", t);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        try {
            Files.createDirectories(imageFolder());
        } catch (IOException e) {
            LOG.warn("Could not create image folder {}", imageFolder(), e);
        }
    }

    public static Platform platform() {
        return platform;
    }

    public static Path configDir() {
        return platform.configDir();
    }

    /** The mod was called "Painting Gallery" (id painting_gallery) up to 1.1.0; carry its config files over once. */
    private static void migrateLegacyConfig() {
        Path dir = configDir();
        for (String suffix : new String[] {".json", "_animated.json", "_sources.json"}) {
            Path legacy = dir.resolve("painting_gallery" + suffix);
            Path current = dir.resolve(MOD_ID + suffix);
            if (Files.isRegularFile(legacy) && !Files.exists(current)) {
                try {
                    Files.move(legacy, current);
                    LOG.info("Migrated {} to {}", legacy.getFileName(), current.getFileName());
                } catch (IOException e) {
                    LOG.warn("Could not migrate {}", legacy, e);
                }
            }
        }
    }

    public static GalleryConfig config() {
        return config;
    }

    /** Replaces and persists the config. */
    public static void setConfig(GalleryConfig newConfig) {
        config = newConfig.sanitize();
        try {
            config.save(configFile());
        } catch (IOException e) {
            LOG.error("Could not save {}", configFile(), e);
        }
    }

    public static Path configFile() {
        return configDir().resolve(MOD_ID + ".json");
    }

    /** The configured image folder, default {@code <gameDir>/paintings}. */
    public static Path imageFolder() {
        String p = config.folderPath;
        if (p != null && !p.isBlank()) {
            try {
                return Path.of(p.trim()).toAbsolutePath();
            } catch (RuntimeException e) {
                LOG.warn("Invalid folder path '{}', using default", p);
            }
        }
        return Minecraft.getInstance().gameDirectory.toPath().resolve("paintings").toAbsolutePath();
    }

    public static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("cache").resolve(MOD_ID);
    }

    public static Path thumbnailCacheDir() {
        return cacheDir().resolve("thumbs");
    }

    /** HTTP client for the museum, photo and wallpaper sources. */
    public static WebClient web() {
        return WEB;
    }

    /** Which source each imported painting came from. */
    public static SourceIndex sources() {
        return sources;
    }
}
