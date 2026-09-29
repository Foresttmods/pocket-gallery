package com.pocketgallery.art;

import java.util.List;

/** Which collections the Museums tab searches. */
public enum MuseumFilter {
    ALL("All museums"),
    AIC("Art Institute of Chicago"),
    MET("The Met"),
    CLEVELAND("Cleveland Museum of Art"),
    SMK("SMK (Denmark)"),
    COMMONS("Wikimedia Commons");

    private final String label;

    MuseumFilter(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public MuseumFilter next() {
        MuseumFilter[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public List<ArtSource> sources() {
        return switch (this) {
            case ALL -> List.of(ArtSource.AIC, ArtSource.MET, ArtSource.CLEVELAND, ArtSource.SMK, ArtSource.COMMONS);
            case AIC -> List.of(ArtSource.AIC);
            case MET -> List.of(ArtSource.MET);
            case CLEVELAND -> List.of(ArtSource.CLEVELAND);
            case SMK -> List.of(ArtSource.SMK);
            case COMMONS -> List.of(ArtSource.COMMONS);
        };
    }
}
