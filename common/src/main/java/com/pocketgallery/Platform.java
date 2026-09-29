package com.pocketgallery;

import java.nio.file.Path;

/** The few things the shared code needs from the mod loader (NeoForge or Fabric). */
public interface Platform {
    /** "NeoForge" or "Fabric". */
    String loaderName();

    Path configDir();

    boolean isModLoaded(String modId);
}
