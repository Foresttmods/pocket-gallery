package com.pocketgallery.art;

import com.pocketgallery.gallery.ImportSource;

/**
 * The web sources the Museums and Photos tabs search. Only Pixabay needs a (free) API key; everything else works
 * without an account.
 */
public enum ArtSource {
    AIC("aic", "Art Institute of Chicago", "Chicago", ImportSource.MUSEUM),
    MET("met", "The Metropolitan Museum of Art", "The Met", ImportSource.MUSEUM),
    CLEVELAND("cleveland", "Cleveland Museum of Art", "Cleveland", ImportSource.MUSEUM),
    SMK("smk", "SMK - National Gallery of Denmark", "SMK", ImportSource.MUSEUM),
    COMMONS("commons", "Wikimedia Commons", "Wikimedia Commons", ImportSource.PHOTO),
    NASA("nasa", "NASA Image Library", "NASA", ImportSource.PHOTO),
    LOC("loc", "Library of Congress", "Library of Congress", ImportSource.PHOTO),
    WALLHAVEN("wallhaven", "Wallhaven", "Wallhaven", ImportSource.WALLPAPER),
    PIXABAY("pixabay", "Pixabay", "Pixabay", ImportSource.WALLPAPER);

    private final String id;
    private final String displayName;
    private final String shortName;
    private final ImportSource defaultKind;

    ArtSource(String id, String displayName, String shortName, ImportSource defaultKind) {
        this.id = id;
        this.displayName = displayName;
        this.shortName = shortName;
        this.defaultKind = defaultKind;
    }

    /** Stable id used in selection keys and cache folder names. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String shortName() {
        return shortName;
    }

    /** What imports from here count as, unless the search says otherwise (e.g. Commons paintings are museum art). */
    public ImportSource defaultKind() {
        return defaultKind;
    }
}
