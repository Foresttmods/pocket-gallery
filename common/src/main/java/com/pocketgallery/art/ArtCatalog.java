package com.pocketgallery.art;

import java.util.List;

/** Quick picks for the Museums tab's "Browse" screen. Each one simply becomes the search text. */
public final class ArtCatalog {
    private ArtCatalog() {
    }

    public record Section(String title, List<String> picks) {
    }

    public static final List<Section> SECTIONS = List.of(
            new Section("Subjects", List.of(
                    "Landscape", "Seascape", "Architecture", "Cityscape", "Village", "Castle", "Church", "Ruins",
                    "Bridge", "Harbor", "River", "Forest", "Mountains", "Garden", "Flowers", "Still life", "Winter",
                    "Night", "Sunset", "Animals", "Horses", "Portrait")),
            new Section("Movements", List.of(
                    "Impressionism", "Post-Impressionism", "Pointillism", "Baroque", "Renaissance", "Rococo",
                    "Romanticism", "Realism", "Neoclassicism", "Symbolism", "Dutch", "Hudson River School",
                    "Barbizon", "Ukiyo-e")),
            new Section("Artists", List.of(
                    "Claude Monet", "Vincent van Gogh", "Rembrandt", "Johannes Vermeer", "Pierre-Auguste Renoir",
                    "Edgar Degas", "Paul Cezanne", "Georges Seurat", "Camille Pissarro", "Alfred Sisley",
                    "Paul Gauguin", "J. M. W. Turner", "John Constable", "Caspar David Friedrich", "Canaletto",
                    "Jacob van Ruisdael", "Hubert Robert", "Katsushika Hokusai", "Utagawa Hiroshige", "Caravaggio",
                    "Peter Paul Rubens", "Titian", "El Greco", "Francisco Goya", "Jean-Francois Millet",
                    "Gustave Courbet", "Winslow Homer", "John Singer Sargent", "Albert Bierstadt", "Thomas Cole"))
    );
}
