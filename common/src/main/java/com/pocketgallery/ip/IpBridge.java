package com.pocketgallery.ip;

import com.pocketgallery.PocketGallery;
import net.conczin.immersive_paintings.ClientPaintingManager;
import net.conczin.immersive_paintings.Painting;
import net.conczin.immersive_paintings.entity.ImmersivePaintingEntity;
import net.conczin.immersive_paintings.network.LazyNetworkManager;
import net.conczin.immersive_paintings.network.payload.c2s.ImageUploadPayload;
import net.conczin.immersive_paintings.network.payload.c2s.PaintingRegisterPayload;
import net.conczin.immersive_paintings.util.ImageManipulations;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only place (besides the mixin) that links directly against Immersive Paintings. Callers must check
 * {@link IpCompat#isAvailable()} first; every method additionally catches {@link LinkageError}s and disables the
 * integration instead of crashing.
 */
public final class IpBridge {
    private static Field textureMapField;
    private static boolean textureMapFailed;

    private IpBridge() {
    }

    public record PaintingRef(Identifier id, String name, int width, int height) {
    }

    /**
     * Queues the image chunks and the register request in IP's rate limited packet queue, exactly like IP's own
     * upload screen does. Must run on the client main thread (LazyNetworkManager is not thread safe).
     */
    public static boolean sendUpload(byte[] png, int width, int height, int resolution, String name, boolean nsfw) {
        try {
            ImageManipulations.processByteArrayInChunks(png,
                    (bytes, split, splits) -> LazyNetworkManager.sendToServer(new ImageUploadPayload(bytes, split, splits)));
            EnumSet<Painting.Flag> flags = EnumSet.noneOf(Painting.Flag.class);
            if (nsfw) flags.add(Painting.Flag.NSFW);
            // Sent through the same queue, so it's guaranteed to arrive after the last image chunk.
            LazyNetworkManager.sendToServer(new PaintingRegisterPayload(width, height, resolution, name, flags));
            return true;
        } catch (LinkageError | RuntimeException e) {
            IpCompat.disable("Uploading through Immersive Paintings failed: " + e, e);
            return false;
        }
    }

    /** Seconds until IP's client packet queue is drained. */
    public static float remainingUploadSeconds() {
        try {
            return LazyNetworkManager.getRemainingTime();
        } catch (LinkageError | RuntimeException e) {
            return 0;
        }
    }

    /** All non-datapack paintings by the given player that the client knows about. */
    public static List<PaintingRef> paintingsBy(UUID author) {
        List<PaintingRef> list = new ArrayList<>();
        try {
            Map<Identifier, Painting> map = ClientPaintingManager.getPaintings();
            synchronized (map) {
                for (Map.Entry<Identifier, Painting> e : map.entrySet()) {
                    Painting p = e.getValue();
                    if (author.equals(p.authorUUID()) && !p.is(Painting.Type.DATAPACK)) {
                        list.add(new PaintingRef(e.getKey(), p.name(), p.width(), p.height()));
                    }
                }
            }
        } catch (LinkageError | RuntimeException e) {
            PocketGallery.LOG.warn("Could not read Immersive Paintings' painting list", e);
        }
        return list;
    }

    /** Name of a painting the client knows, or null. */
    public static String paintingName(Identifier id) {
        try {
            return ClientPaintingManager.getPainting(id).map(Painting::name).orElse(null);
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    public static boolean hasPainting(Identifier id) {
        try {
            return ClientPaintingManager.getPaintings().containsKey(id);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** The painting shown by an IP painting/graffiti entity, or null for other entities. */
    public static Identifier motiveOf(Entity entity) {
        try {
            if (entity instanceof ImmersivePaintingEntity painting) return painting.getMotive();
        } catch (LinkageError | RuntimeException ignored) {
            // treat as not a painting
        }
        return null;
    }

    /**
     * Reads an int from IP's common config ({@code Config.COMMON.<field>}) reflectively - the config classes extend
     * fzzy_config types we don't compile against.
     */
    public static int commonConfigInt(String field, int fallback) {
        try {
            Class<?> config = Class.forName("net.conczin.immersive_paintings.registry.Config");
            Object common = config.getField("COMMON").get(null);
            return common.getClass().getField(field).getInt(common);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return fallback;
        }
    }

    /**
     * IP keeps its textures in the private {@code ClientPaintingManager.textureMap}: painting id -> (Size -> texture
     * id). Returns size name -> texture id (a copy), or an empty map when unavailable.
     */
    public static Map<String, Identifier> textureIds(Identifier painting) {
        Map<String, Identifier> result = new HashMap<>();
        if (textureMapFailed) return result;
        try {
            if (textureMapField == null) {
                textureMapField = ClientPaintingManager.class.getDeclaredField("textureMap");
                textureMapField.setAccessible(true);
            }
            @SuppressWarnings("unchecked")
            Map<Identifier, Map<?, Identifier>> map = (Map<Identifier, Map<?, Identifier>>) textureMapField.get(null);
            Map<?, Identifier> sizes = map.get(painting);
            if (sizes != null) {
                // the inner map is a plain HashMap written from IP's worker threads; copy defensively
                Object[] entries;
                try {
                    entries = sizes.entrySet().toArray();
                } catch (java.util.ConcurrentModificationException busy) {
                    return result; // IP is adding a size right now; try again next tick
                }
                for (Object o : entries) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                    if (e.getKey() instanceof Enum<?> size && e.getValue() instanceof Identifier id) {
                        result.put(size.name(), id);
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            textureMapFailed = true;
            PocketGallery.LOG.warn("GIF animation disabled: cannot access Immersive Paintings' texture map ({})", e.toString());
        }
        return result;
    }

    public static boolean animationSupported() {
        return !textureMapFailed;
    }
}
