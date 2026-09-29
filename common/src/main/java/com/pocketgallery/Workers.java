package com.pocketgallery;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background executors. Everything slow (disk, network, decoding) runs here, never on the render thread.
 */
public final class Workers {
    /** Thumbnail decoding/downloading: several threads, low priority. */
    public static final ExecutorService THUMBNAILS = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)), factory("thumb", Thread.MIN_PRIORITY + 1));
    /** Folder scans, Danbooru searches, previews. */
    public static final ExecutorService IO = Executors.newFixedThreadPool(2, factory("io", Thread.NORM_PRIORITY - 1));
    /** Import pipeline (download, decode, encode). One at a time keeps memory bounded. */
    public static final ExecutorService IMPORT = Executors.newSingleThreadExecutor(factory("import", Thread.NORM_PRIORITY - 1));
    /** GIF frame preparation for animations. */
    public static final ExecutorService ANIMATION = Executors.newSingleThreadExecutor(factory("anim", Thread.MIN_PRIORITY + 1));

    private Workers() {
    }

    private static ThreadFactory factory(String name, int priority) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "PocketGallery-" + name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(priority);
            // ImageIO plugin lookup (e.g. IP's bundled webp reader) uses the context class loader
            t.setContextClassLoader(Workers.class.getClassLoader());
            return t;
        };
    }
}
