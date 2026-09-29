package com.pocketgallery.art;

import com.pocketgallery.gallery.ImportSource;

import java.util.List;

/**
 * Topics of the Photos tab. Each topic belongs to one source:
 * <ul>
 * <li>Nature / Architecture: Wikimedia Commons' hand-curated "Featured pictures of ..." categories
 * ({@code incategory:A|B}); whole category trees ({@code deepcat:}) leak into unrelated sub-categories, so they are
 * only used where the tree is tight (churches) or with a keyword that keeps results on topic (castles).</li>
 * <li>Space: the NASA Image and Video Library (a search phrase).</li>
 * <li>Vintage: collections of the Library of Congress (a collection id).</li>
 * <li>Wallpapers: Wallhaven's general, safe-for-work wallpapers (a search phrase, empty = most loved), and Pixabay
 * (an image type; needs a free API key).</li>
 * </ul>
 * Free text typed in the search box narrows any topic further.
 */
public enum PhotoTopic {
    LANDSCAPES(Group.NATURE, "Landscapes", ArtSource.COMMONS, featured("landscapes", "mountains", "lakes", "forests",
            "waterfalls", "sunsets", "coasts", "beaches", "volcanoes", "snow", "trees", "clouds")),
    MOUNTAINS(Group.NATURE, "Mountains", ArtSource.COMMONS, featured("mountains", "volcanoes")),
    FORESTS(Group.NATURE, "Forests & trees", ArtSource.COMMONS, featured("forests", "trees")),
    LAKES(Group.NATURE, "Lakes", ArtSource.COMMONS, featured("lakes")),
    WATERFALLS(Group.NATURE, "Waterfalls", ArtSource.COMMONS, featured("waterfalls")),
    COASTS(Group.NATURE, "Coasts & beaches", ArtSource.COMMONS, featured("coasts", "beaches", "islands")),
    SUNSETS(Group.NATURE, "Sunsets & skies", ArtSource.COMMONS, featured("sunsets", "clouds")),
    SNOW(Group.NATURE, "Snow & winter", ArtSource.COMMONS, featured("snow")),
    ANIMALS(Group.NATURE, "Animals & wildlife", ArtSource.COMMONS, "deepcat:\"Featured pictures of animals\""),
    BIRDS(Group.NATURE, "Birds", ArtSource.COMMONS, "deepcat:\"Featured pictures of birds\""),
    ARCHITECTURE(Group.ARCHITECTURE, "All architecture", ArtSource.COMMONS, featured("architecture", "buildings",
            "cityscapes", "skylines", "castles", "palaces", "bridges", "towers", "lighthouses", "windmills",
            "monuments_and_memorials", "church_interiors")),
    BUILDINGS(Group.ARCHITECTURE, "Buildings", ArtSource.COMMONS, featured("buildings")),
    CITYSCAPES(Group.ARCHITECTURE, "Cityscapes & skylines", ArtSource.COMMONS, featured("cityscapes", "skylines")),
    CHURCHES(Group.ARCHITECTURE, "Churches", ArtSource.COMMONS, "deepcat:\"Featured pictures of churches\""),
    INTERIORS(Group.ARCHITECTURE, "Church interiors", ArtSource.COMMONS, "deepcat:\"Featured pictures of church interiors\""),
    CASTLES(Group.ARCHITECTURE, "Castles", ArtSource.COMMONS, "deepcat:\"Featured pictures of castles\" castle"),
    PALACES(Group.ARCHITECTURE, "Palaces", ArtSource.COMMONS, featured("palaces")),
    BRIDGES(Group.ARCHITECTURE, "Bridges", ArtSource.COMMONS, featured("bridges")),
    TOWERS(Group.ARCHITECTURE, "Towers, lighthouses & windmills", ArtSource.COMMONS, featured("towers", "lighthouses", "windmills")),

    NEBULAE(Group.SPACE, "Nebulae", ArtSource.NASA, "nebula"),
    GALAXIES(Group.SPACE, "Galaxies", ArtSource.NASA, "galaxy"),
    PLANETS(Group.SPACE, "Planets & moons", ArtSource.NASA, "planet"),
    EARTH(Group.SPACE, "Earth from orbit", ArtSource.NASA, "earth observation"),
    AURORAS(Group.SPACE, "Auroras", ArtSource.NASA, "aurora"),
    LAUNCHES(Group.SPACE, "Rocket launches", ArtSource.NASA, "rocket launch"),
    SPACEWALKS(Group.SPACE, "Astronauts & spacewalks", ArtSource.NASA, "spacewalk"),
    NASA_ANY(Group.SPACE, "Any NASA image (type words)", ArtSource.NASA, ""),

    PHOTOCHROM(Group.VINTAGE, "Photochrom color prints (1890s)", ArtSource.LOC, "photochrom-prints"),
    EARLY_COLOR(Group.VINTAGE, "Early color photos (1905-1915)", ArtSource.LOC, "prokudin-gorskii"),
    JAPANESE_PRINTS(Group.VINTAGE, "Japanese woodblock prints", ArtSource.LOC, "japanese-fine-prints-pre-1915", ImportSource.MUSEUM),
    WPA_POSTERS(Group.VINTAGE, "WPA posters (1930s)", ArtSource.LOC, "works-progress-administration-posters", ImportSource.MUSEUM),

    WALLS_TOP(Group.WALLPAPERS, "Most loved wallpapers", ArtSource.WALLHAVEN, ""),
    WALLS_LANDSCAPES(Group.WALLPAPERS, "Landscape wallpapers", ArtSource.WALLHAVEN, "landscape"),
    WALLS_SPACE(Group.WALLPAPERS, "Space wallpapers", ArtSource.WALLHAVEN, "space"),
    WALLS_ANIMALS(Group.WALLPAPERS, "Animal wallpapers", ArtSource.WALLHAVEN, "animals"),
    WALLS_FANTASY(Group.WALLPAPERS, "Fantasy art", ArtSource.WALLHAVEN, "fantasy art"),
    WALLS_CITIES(Group.WALLPAPERS, "City wallpapers", ArtSource.WALLHAVEN, "city"),
    WALLS_MINIMAL(Group.WALLPAPERS, "Minimalist wallpapers", ArtSource.WALLHAVEN, "minimalism"),
    PIXABAY_ART(Group.WALLPAPERS, "Pixabay illustrations (key)", ArtSource.PIXABAY, "illustration"),
    PIXABAY_PHOTOS(Group.WALLPAPERS, "Pixabay photos (key)", ArtSource.PIXABAY, "photo"),

    ANY(Group.OTHER, "Any quality photo (type words)", ArtSource.COMMONS, "incategory:Quality_images");

    public enum Group {
        NATURE("Nature"), ARCHITECTURE("Architecture"), SPACE("Space (NASA)"), VINTAGE("Vintage (Library of Congress)"),
        WALLPAPERS("Wallpapers"), OTHER("Other");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Group group;
    private final String label;
    private final ArtSource source;
    private final String filter;
    private final ImportSource kind;

    PhotoTopic(Group group, String label, ArtSource source, String filter) {
        this(group, label, source, filter, source.defaultKind());
    }

    PhotoTopic(Group group, String label, ArtSource source, String filter, ImportSource kind) {
        this.group = group;
        this.label = label;
        this.source = source;
        this.filter = filter;
        this.kind = kind;
    }

    public Group group() {
        return group;
    }

    public String label() {
        return label;
    }

    public ArtSource source() {
        return source;
    }

    /**
     * What selects the topic at its source: the Commons search part, the NASA / Wallhaven search phrase, the Library
     * of Congress collection id, or the Pixabay image type.
     */
    public String filter() {
        return filter;
    }

    /** What imports count as: photos, wallpapers, or museum art (the Library of Congress' prints and posters). */
    public ImportSource kind() {
        return kind;
    }

    /** {@code incategory:} OR-list of "Featured pictures of <subject>" categories. */
    static String featured(String... subjects) {
        return "incategory:" + String.join("|", List.of(subjects).stream()
                .map(s -> "Featured_pictures_of_" + s.replace(' ', '_')).toList());
    }
}
