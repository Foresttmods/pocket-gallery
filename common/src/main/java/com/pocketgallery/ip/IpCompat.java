package com.pocketgallery.ip;

import com.pocketgallery.PocketGallery;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks - without linking against Immersive Paintings - that every IP class/method this mod calls exists.
 * {@link IpBridge} (which does link against IP) is only used when {@link #isAvailable()} is true, so a different IP
 * version shows a message instead of crashing with a NoSuchMethodError.
 */
public final class IpCompat {
    public static final String MOD_ID = "immersive_paintings";
    private static final String P = "net.conczin.immersive_paintings.";

    private static volatile Boolean available;
    private static volatile String problem = "";

    private IpCompat() {
    }

    public static boolean isAvailable() {
        Boolean a = available;
        if (a == null) {
            synchronized (IpCompat.class) {
                if (available == null) available = check();
                a = available;
            }
        }
        return a;
    }

    /** True for Immersive Paintings' painting selection screen (checked by name, no linking). */
    public static boolean isPaintingScreen(Object screen) {
        return screen != null && screen.getClass().getName().equals(P + "client.gui.ImmersivePaintingScreen");
    }

    /** Human readable reason when {@link #isAvailable()} is false. */
    public static String problem() {
        isAvailable();
        return problem;
    }

    /** Marks IP as unusable at runtime (e.g. a call failed with a LinkageError). */
    public static void disable(String reason, Throwable t) {
        if (Boolean.FALSE.equals(available)) return;
        available = false;
        problem = reason;
        PocketGallery.LOG.error("Disabling Immersive Paintings integration: {}", reason, t);
    }

    private static boolean check() {
        try {
            if (PocketGallery.platform() != null && !PocketGallery.platform().isModLoaded(MOD_ID)) {
                problem = "Immersive Paintings is not installed.";
                return false;
            }
        } catch (Throwable ignored) {
            // mod list unavailable (should not happen in game) - fall through to class checks
        }
        List<String> missing = new ArrayList<>();
        ClassLoader cl = IpCompat.class.getClassLoader();
        try {
            Class<?> payload = Class.forName(P + "network.payload.ImmersivePayload", false, cl);
            Class<?> lazy = Class.forName(P + "network.LazyNetworkManager", false, cl);
            need(missing, lazy, "sendToServer", payload);
            need(missing, lazy, "getRemainingTime");
            Class<?> manip = Class.forName(P + "util.ImageManipulations", false, cl);
            need(missing, manip, "processByteArrayInChunks", byte[].class, Class.forName("org.apache.logging.log4j.util.TriConsumer", false, cl));
            Class<?> upload = Class.forName(P + "network.payload.c2s.ImageUploadPayload", false, cl);
            needCtor(missing, upload, byte[].class, int.class, int.class);
            Class<?> register = Class.forName(P + "network.payload.c2s.PaintingRegisterPayload", false, cl);
            needCtor(missing, register, int.class, int.class, int.class, String.class, java.util.EnumSet.class);
            Class<?> manager = Class.forName(P + "ClientPaintingManager", false, cl);
            need(missing, manager, "getPaintings");
            Class<?> painting = Class.forName(P + "Painting", false, cl);
            for (String m : new String[]{"name", "authorUUID", "width", "height", "resolution"}) need(missing, painting, m);
            Class.forName(P + "Painting$Flag", false, cl);
            Class<?> entity = Class.forName(P + "entity.ImmersivePaintingEntity", false, cl);
            need(missing, entity, "getMotive");
        } catch (ClassNotFoundException | LinkageError e) {
            missing.add(e.getMessage());
        }
        if (!missing.isEmpty()) {
            problem = "Incompatible Immersive Paintings version (missing: " + String.join(", ", missing)
                    + "). Pocket Gallery was built for Immersive Paintings 0.7.9.";
            PocketGallery.LOG.error(problem);
            return false;
        }
        return true;
    }

    private static void need(List<String> missing, Class<?> c, String name, Class<?>... params) {
        try {
            c.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            missing.add(c.getSimpleName() + "." + name);
        }
    }

    private static void needCtor(List<String> missing, Class<?> c, Class<?>... params) {
        try {
            c.getConstructor(params);
        } catch (NoSuchMethodException e) {
            missing.add(c.getSimpleName() + "()");
        }
    }
}
